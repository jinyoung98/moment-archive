# 업로드 · 처리 파이프라인 상세

> [design.md](./design.md)의 데이터 모델을 전제로 한다. 여기서는 그 위에서 벌어지는 동작을 다룬다.

---

## 1. 프로토콜 선택 — 바이트가 서버를 통과하는가

| | A. Presigned URL | B. 서버 경유 |
|---|---|---|
| 흐름 | 브라우저 → MinIO 직접 | 브라우저 → API 서버 → MinIO |
| 서버 부하 | 없음 | 모든 바이트 통과 |
| CORS | MinIO 설정 필요 | 불필요 (동일 오리진) |
| 진행률 | 클라이언트가 보고해야 함 | 서버가 직접 파악 |

**MVP는 B를 채택한다.**

- CORS 설정 삽질이 없다
- 서버가 진행 상태를 직접 통제한다. A는 클라이언트 보고가 유실되면 서버 기록과 실제가 어긋난다
- 개인 서비스 규모에서 대역폭은 병목이 아니다

서버는 청크를 디스크에 임시 저장하지 않는다. 받은 청크를 그대로 S3 멀티파트의 `uploadPart`로 흘려보내므로 메모리에는 청크 하나분만 존재한다.

> **향후 전환 경로**: 대역폭이 실제 병목으로 측정되면 presigned URL로 전환한다. 전환 전후 서버 CPU·네트워크를 측정해 기록할 것.

### 청크 크기

| 파일 크기 | 방식 |
|---|---|
| < 10MB | 단일 요청 (`PUT .../content`) |
| ≥ 10MB | 5MB 청크 멀티파트 |

S3 멀티파트는 마지막을 제외한 파트가 최소 5MB여야 한다. 사진은 대부분 3~8MB라 단일 요청으로 끝나고, 멀티파트는 사실상 영상 전용이 된다. 사진의 재개 단위가 파일 전체가 되지만 재전송 비용이 작아 문제되지 않는다. **재개가 실제로 필요한 것은 200MB급 영상이다.**

---

## 2. API

```
POST /upload-sessions
     { recordId, insertAfterPosition,
       items: [{ filename, byteSize, mimeType }] }      ← 해시 없이 즉시 생성
  →  { sessionId, items: [{ itemId, ord }] }

POST /upload-items/{itemId}/hash
     { contentHash }                                     ← 계산되는 대로 개별 보고
  →  { state: "SKIPPED_DUPLICATE", assetId, assetStatus, thumbKey? }
     또는
     { state: "PENDING", uploadMode: "SINGLE"|"MULTIPART", chunkSize }

PUT  /upload-items/{itemId}/content                      # 단일 업로드
     Content-Type: application/octet-stream
  →  { state: "UPLOADED", assetId }

POST /upload-items/{itemId}/chunks/{index}               # 멀티파트 청크
  →  { received: [0,1,2,5] }                             # 지금까지 받은 청크

POST /upload-items/{itemId}/complete
  →  { state: "UPLOADED", assetId }

GET  /upload-sessions/{sessionId}                        # 재개/폴백용 전체 상태
  →  { status, totalItems, items: [...] }

GET  /upload-sessions/{sessionId}/events                 # SSE
```

### 세션 생성 시 해시를 받지 않는 이유

해시를 한꺼번에 요구하면 **모든 파일의 해싱이 끝나야 업로드를 시작**할 수 있다. 70장이면 초반 수 초 동안 화면에 아무 일도 일어나지 않는다.

파일별로 나중에 보고받으면 계산이 끝난 것부터 곧바로 올릴 수 있다 (§3.2 파이프라인화).

이에 따라 `upload_items.content_hash`는 **nullable**이다.

### 청크 응답이 `received`를 항상 싣는 이유

클라이언트가 별도 조회 없이 매 응답에서 "내가 무엇을 더 보내야 하는지"를 알게 된다. 재개 로직이 단순해진다.

### 중복 판정 시 세 갈래

해시가 이미 존재한다는 것은 그 자산이 시스템에 있고 대개 처리도 끝나 있다는 뜻이다. **중복 사진은 전송도 처리도 없으므로 오히려 가장 빨리 화면에 뜬다.**

| 기존 자산 상태 | 응답 | 화면 동작 |
|---|---|---|
| `READY` | `thumbKey` 포함 | **즉시 표시** (수백 ms) |
| 처리 중 (`INGESTED`~`THUMBED`) | `assetId`만 | 플레이스홀더 → 해당 `assetId`의 `asset.thumbed` 대기 |
| `FAILED` | `processing_jobs` 재등록 | 플레이스홀더 → 재처리 결과 대기 |

**`FAILED`일 때 재업로드를 요구하면 안 된다.** 원본 바이트는 이미 저장돼 있고 실패한 것은 처리 단계다. 같은 바이트를 다시 보내도 결과는 같으므로, 처리 작업만 다시 등록한다.

삭제 유예 중인 자산이면 되살리기가 선행된다 (§10.2).

#### 중복은 예외가 아니라 일상이다

- **업로드 재개** — 탭을 닫았다 돌아와 같은 70장을 재선택하면 완료분 43장이 전부 여기 해당한다. **그 43장이 즉시 화면에 뜨는 것이 재개 UX의 핵심이다.** 재선택 후 화면이 비어 있으면 사용자는 "처음부터 다시 올라가나?" 하고 불안해한다
- 실수로 같은 사진을 다시 선택
- 다른 기록에 같은 사진을 사용 (허용된다, §8.3)

---

## 3. 클라이언트

### 3.1 스트리밍 해싱

브라우저 내장 `crypto.subtle.digest`는 **스트리밍을 지원하지 않는다.** 전체 `ArrayBuffer`를 한 번에 받아야 하므로 큰 파일에서 메모리가 터진다.

```js
// ❌ 200MB 영상에서 실패
const buf = await file.arrayBuffer();
const hash = await crypto.subtle.digest('SHA-256', buf);
```

SHA-256은 데이터를 순차로 먹으며 내부 상태를 이어가야 하는데, 그 중간 상태를 노출하는 표준 API가 없어 쪼개 넣을 수 없다.

**WASM 기반 스트리밍 구현을 Web Worker에서 돌린다.**

```js
// upload-hasher.worker.js
import { createSHA256 } from 'hash-wasm';

self.onmessage = async ({ data: { file, id } }) => {
  const hasher = await createSHA256();
  hasher.init();

  const reader = file.stream().getReader();
  let processed = 0;

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    hasher.update(value);
    processed += value.length;
    self.postMessage({ id, type: 'progress', processed, total: file.size });
  }

  self.postMessage({ id, type: 'done', hash: hasher.digest('hex') });
};
```

메모리는 청크 하나분만 사용한다. 대략 8MB 사진 ~50ms, 200MB 영상 1~2초.

> **폴백 안**: 해싱 속도가 실측에서 문제가 되면, 전체 해시 대신 **부분 지문**(앞 1MB + 뒤 1MB + 파일 크기)으로 중복을 판정할 수 있다. 단 무결성 보장(I1, I2)이 약해지므로 실제로 느릴 때만 검토한다.

### 3.2 해싱과 업로드 파이프라인화

```
❌ 순차:  [해싱 N개 ─────────][업로드 시작...]
                              ↑ 여기까지 화면이 비어 있음

✅ 겹치기: [해싱1][해싱2][해싱3][해싱4]...
              └─[업로드1]
                    └─[업로드2]
                          └─[업로드3]
           ↑ 첫 사진이 빠르게 화면에 뜬다
```

- 해싱 Worker 2개
- 동시 업로드 3개
- 모바일에서 과도한 병렬은 오히려 느려진다. 실측 후 조정할 것

파일 개수와 무관하게 동작한다. 5장을 올리든 70장을 올리든, 글을 쓰다가 3장만 추가하든 같은 경로다.

---

## 4. 업로드 상태 머신

### 상태의 의미

| state | 뜻 | 도달 조건 |
|---|---|---|
| `PENDING` | 올릴 준비 됨 | 해시 보고 완료, 서버가 신규 파일로 판정 |
| `SKIPPED_DUPLICATE` | 올릴 필요 없음 | 해시가 이미 존재. **전송하지 않음** |
| `UPLOADING` | 전송 중 | 첫 청크 도착 |
| `UPLOADED` | 바이트 수신 완료 | 자산 생성됨. **아직 미검증** |
| `VERIFIED` | 검증 통과 | PROBE에서 해시 재계산 → 일치 |
| `FAILED` | 실패 | 해시 불일치 또는 재시도 소진 |

### 전이

```
   (항목 생성)
        │
        │ 클라이언트가 해시 보고
        ▼
   ┌────────────────────────┐
   │ 서버: 이 해시가 있는가?  │
   └───┬────────────────┬───┘
      있음              없음
       │                │
       ▼                ▼
SKIPPED_DUPLICATE    PENDING
   (전송 생략)           │ 전송 시작
       │                ▼
       │            UPLOADING ⟲ 청크 실패 시 그 청크만 재전송
       │                │ 마지막 청크 + complete
       │                ▼
       │            UPLOADED ──▶ media_assets 생성 (INGESTED)
       │                │        + PROBE 작업 등록  ※ 같은 트랜잭션
       │                │
       │                │ 워커가 PROBE 중 해시 재계산
       │          ┌─────┴─────┐
       │        일치        불일치
       │          │           │
       │          ▼           ▼
       └──────▶ VERIFIED    FAILED
                   │
                   ▼
    세션의 모든 항목이 종착 상태에 도달 → 블록 생성 (§8)
```

### `UPLOADED`와 `VERIFIED`를 나눈 이유

바이트를 다 받았다고 올바른 파일이라는 보장이 없다. 클라이언트가 보낸 해시를 신뢰하지 않기 때문이다.

그렇다고 방금 올린 200MB를 다시 다운로드해 검증하면 낭비다. **`PROBE`가 어차피 파일을 스트리밍하므로 읽는 김에 SHA-256을 함께 계산한다.** 추가 I/O가 사실상 0이고, 대신 검증 시점이 뒤로 밀린다.

---

## 5. 워커

### 5.1 작업 획득

```sql
-- 트랜잭션
SELECT * FROM processing_jobs
 WHERE state = 'QUEUED' AND lane = :lane AND run_after <= now()
 ORDER BY id LIMIT 1
 FOR UPDATE SKIP LOCKED;

UPDATE processing_jobs
   SET state = 'RUNNING', locked_by = :workerId, locked_at = now()
 WHERE id = :jobId;
```

`SKIP LOCKED`가 없으면 워커들이 같은 행에서 서로를 기다려 사실상 직렬화된다. 이 한 줄이 큐를 만든다.

### 5.2 하트비트

회수 기준을 정할 때 딜레마가 있다.

- 기준 2분 → 정상적으로 2분 걸리는 영상 트랜스코딩 작업을 빼앗는다
- 기준 10분 → 진짜 죽었을 때 10분간 방치된다

**하트비트로 해결한다.** 워커가 처리 중 30초마다 `locked_at`을 갱신하면, 회수 기준을 2분으로 짧게 유지하면서도 긴 작업이 안전하다.

```java
@Scheduled(fixedDelay = 30_000)
void heartbeat() {
    activeJobs.forEach(jobId -> jobRepository.touch(jobId, workerId));
}
```

### 5.3 죽은 작업 회수

```sql
UPDATE processing_jobs
   SET state = 'QUEUED', locked_by = NULL, locked_at = NULL,
       attempt = attempt + 1,
       last_error = 'worker lease expired'
 WHERE state = 'RUNNING'
   AND locked_at < now() - interval '2 minutes';
```

`attempt`를 증가시키는 것이 중요하다. 특정 파일이 워커를 반복적으로 죽이는 경우(메모리 폭발을 유발하는 손상 영상 등) 무한 루프에 빠지지 않고 `max_attempts`에서 `DEAD`가 된다.

### 5.4 소유권 확인

하트비트도 실패할 수 있다(DB 순단). 워커는 작업을 마칠 때 소유권을 확인한다.

```sql
UPDATE processing_jobs SET state = 'DONE'
 WHERE id = :jobId AND locked_by = :workerId;   -- 0행이면 이미 뺏긴 것
```

0행이면 결과를 조용히 버린다. 모든 단계가 멱등이므로 중복 실행 자체는 무해하다.

### 5.5 재시도 분류

| 분류 | 예시 | 처리 |
|---|---|---|
| **일시적** | 스토리지 타임아웃, 연결 끊김, OOM | 지수 백오프 재시도 |
| **영구적** | 손상 파일, 미지원 코덱, 해시 불일치 | 즉시 `DEAD` |
| **불명** | 예상 못 한 예외 | 재시도하되 `max_attempts` 낮게 |

```java
catch (StorageTimeoutException | IOException e) {
    retryWithBackoff(job, e);
} catch (CorruptedMediaException | UnsupportedCodecException | HashMismatchException e) {
    markDead(job, e);
}
```

영구 실패를 재시도하면 5회 헛돌며 시간을 낭비하고 그동안 큐가 막힌다.

### 5.6 지수 백오프 + 지터

```
1차 실패 → 1초    2차 → 4초    3차 → 16초    4차 → 64초    5차 → 256초
```

**즉시 재시도가 위험한 이유**

- 원인이 아직 사라지지 않았으므로 또 실패한다
- 다수가 동시에 실패한 상황에서 즉시 재시도는 부하를 키운다 (retry storm)

**지터가 필요한 이유**

작업 50개가 동시에 실패하면 백오프가 같아 재시도도 동시에 몰린다. 지수적으로 늘려도 몰림 자체는 해소되지 않는다.

```java
long base  = 1000L * (long) Math.pow(4, attempt);        // 1s, 4s, 16s, 64s, 256s
long delay = ThreadLocalRandom.current().nextLong(base); // full jitter
job.setRunAfter(now().plusMillis(delay));
```

`random(0, base)`로 구간 전체에 흩뿌린다(full jitter). 이것을 넣지 않으면 부하 상황에서 시스템이 주기적으로 맥박치듯 뛴다.

### 5.7 단계 연쇄

```java
@Transactional
void complete(Job job, ProbeResult result) {
    assetRepository.updateMetadata(job.assetId(), result);
    assetRepository.updateStatus(job.assetId(), PROBED);
    jobRepository.markDone(job.id());
    jobRepository.enqueue(job.assetId(), THUMB, job.lane());   // 다음 단계
    notifier.notify("asset.probed", job.assetId());
}
```

이 트랜잭션이 깨지면 전부 롤백되고, 작업은 `RUNNING`에 남았다가 lease 만료로 회수된다. **"메타는 저장됐는데 다음 작업이 등록되지 않음" 같은 상태가 존재할 수 없다.** 메시지 브로커를 쓰지 않은 판단(ADR A1)이 여기서 값을 한다.

---

## 6. 멱등성

lease 만료로 회수된 작업이 실제로는 완료 직전이었을 수 있으므로, **같은 작업의 중복 실행은 반드시 일어난다고 전제한다.**

| stage | 중복 실행 시 | 안전 조치 |
|---|---|---|
| `PROBE` | 같은 메타를 덮어씀 | 순수 함수이므로 안전 |
| `THUMB` / `DERIVE` | 같은 키에 다시 씀 | 오브젝트 스토리지 덮어쓰기는 원자적 |
| derivatives 행 | UNIQUE 위반 | **UPSERT 필수** |

```sql
INSERT INTO media_derivatives (id, asset_id, recipe, recipe_ver, storage_key, byte_size, content_hash)
VALUES (...)
ON CONFLICT (asset_id, recipe, recipe_ver)
DO UPDATE SET storage_key  = EXCLUDED.storage_key,
              byte_size    = EXCLUDED.byte_size,
              content_hash = EXCLUDED.content_hash;
```

### 결정론적 storage_key

```
derivatives/{content_hash}/{recipe}_{recipe_ver}.webp
```

랜덤 UUID를 키로 쓰면 재실행할 때마다 새 객체가 생기고 이전 것이 고아가 된다. 해시 기반이면 같은 자리에 덮어쓰므로 쓰레기가 남지 않는다.

---

## 7. 실시간 알림

### 7.1 문제

워커는 API 서버와 별도 프로세스다. 워커가 썸네일을 만들었다는 것을 API 서버의 `SseEmitter`가 알 방법이 필요하다. Redis pub/sub이 흔한 답이지만 Redis를 도입하지 않기로 했다(ADR).

### 7.2 PostgreSQL LISTEN/NOTIFY

```sql
-- 워커 (트랜잭션 커밋 시 전달)
NOTIFY media_events, '{"type":"asset.thumbed","assetId":"...","sessionId":"..."}';

-- API 서버
LISTEN media_events;
```

```java
@Component
class PgNotificationListener {
    private final PGConnection conn;   // 전용 커넥션 (풀에서 분리)

    @PostConstruct void start() {
        exec("LISTEN media_events");
        Thread.ofVirtual().start(() -> {
            while (running) {
                PGNotification[] ns = conn.getNotifications(5_000);
                if (ns != null) for (var n : ns) dispatch(parse(n.getParameter()));
            }
        });
    }
}
```

**주의사항 3가지**

- **전용 커넥션 필요.** 커넥션 풀에서 빌려 쓰면 반납 시 LISTEN이 사라진다
- 페이로드 8000바이트 제한. ID만 보내고 상세는 수신 측이 조회한다
- **내구성 없음.** 구독자가 없는 동안의 알림은 유실된다

### 7.3 SSE

서버 → 클라이언트 단방향 푸시. HTTP 응답을 닫지 않고 열어둔 채 텍스트를 흘려보낸다.

```
GET /upload-sessions/abc/events
Content-Type: text/event-stream

data: {"type":"asset.probed","assetId":"a1","capturedAt":"2026-07-18T11:20:00Z"}

data: {"type":"asset.thumbed","assetId":"a1","thumbKey":"..."}
```

```java
@GetMapping(value = "/upload-sessions/{id}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
SseEmitter events(@PathVariable UUID id) {
    var emitter = new SseEmitter(30 * 60_000L);
    registry.register(id, emitter);
    emitter.onCompletion(() -> registry.remove(id, emitter));
    return emitter;
}
```

**WebSocket 대신 SSE인 이유**

| | SSE | WebSocket |
|---|---|---|
| 방향 | 서버 → 클라이언트 | 양방향 |
| 프로토콜 | 그냥 HTTP | 업그레이드 필요 |
| 자동 재연결 | **브라우저 내장** | 직접 구현 |
| 프록시 통과 | 대체로 무난 | 종종 차단 |

필요한 방향이 단방향뿐이다. 업로드는 일반 HTTP 요청으로 처리하므로 양방향이 필요 없고, WebSocket을 쓰면 연결 관리·하트비트·재연결을 직접 구현해야 한다.

> **제약**: HTTP/1.1에서 브라우저는 도메인당 동시 연결 6개 제한이 있고 SSE가 하나를 점유한다. HTTP/2에서는 해소된다.

### 7.4 연결 수명

HTTP 응답을 종료하지 않고 TCP 연결을 유지한다. 닫히는 경우는 넷이다.

| 원인 | 브라우저 자동 재연결 |
|---|---|
| 클라이언트가 `es.close()` 호출 | 안 함 (의도된 종료) |
| 사용자가 페이지를 떠남 | 안 함 |
| 서버 타임아웃 (`SseEmitter` 30분) | **함** |
| 네트워크 끊김 / 프록시의 유휴 연결 정리 | **함** |

아래 두 경우를 브라우저가 알아서 복구해 주는 것이 WebSocket 대비 가장 큰 이점이다.

**닫는 시점**: 세션의 모든 자산이 `READY`가 되면, 또는 편집 화면을 벗어날 때.

#### keepalive는 필수

프록시와 로드밸런서는 데이터가 흐르지 않는 연결을 죽은 것으로 보고 끊는다(보통 30~60초). 그런데 영상 트랜스코딩 2분 동안은 보낼 이벤트가 없다.

```java
@Scheduled(fixedDelay = 15_000)
void keepAlive() {
    registry.forEach(emitter -> emitter.send(SseEmitter.event().comment("ping")));
}
```

`:`로 시작하는 코멘트는 SSE 규격상 무시되지만 바이트는 흐르므로 유휴 판정을 피한다.

### 7.5 유실 허용 원칙

> **화면의 근거는 DB 조회이고, SSE는 그것을 더 빨리 알려주는 지름길일 뿐이다.**
> **지름길이 막혀도 큰길로 가면 도착한다.**

#### 왜 이 원칙이 필요한가

```
14:00:00  지하철 진입 → SSE 연결 끊김
14:00:05  서버: 썸네일 완성 → NOTIFY → 듣는 사람 없음 → 유실
   ⋮      (12개가 같은 방식으로 유실)
14:00:20  브라우저가 SSE 재연결 성공
```

**재연결되어도 유실된 12개 이벤트는 다시 오지 않는다.** LISTEN/NOTIFY에 내구성이 없기 때문이다.

| 설계 | 결과 |
|---|---|
| SSE만 신뢰 | 그 12장은 영원히 플레이스홀더. 서버엔 썸네일이 멀쩡히 있는데 화면만 모른다 → **버그** |
| 폴백 있음 | 다음 폴링에서 "12개 모두 준비됨" 확인 후 채움 → **최대 5초 지연, 정상** |

#### 구현

```js
const timer = setInterval(async () => {
  const state = await fetch(`/upload-sessions/${id}`).then(r => r.json());
  reconcile(state);                       // 화면을 서버 상태에 맞춘다
  if (state.status === 'COMPLETED' && allReady(state)) {
    clearInterval(timer);
    es.close();
  }
}, 5000);
```

SSE 생존 여부에 따라 폴링 주기를 늘리는 최적화도 가능하지만, 개인 서비스 규모에서는 **단순함이 낫다.**

#### 이 원칙이 없애주는 것

SSE를 신뢰의 근거로 삼으면 아래가 전부 개별 버그가 되고, 재현이 어려워 잡기 힘들다.

- 연결이 끊긴 사이의 이벤트 유실
- 서버 재배포 중 알림 유실
- 이벤트 도착 순서 뒤바뀜
- 재연결 직후의 상태 불일치

폴백이 있으면 전부 "다음 폴링에서 수렴"으로 끝난다.

### 7.5 이벤트 목록

```
asset.probed   { assetId, capturedAt, width, height, orientation }
               → 시간순 배치 가능 (아직 이미지 없음)
asset.thumbed  { assetId, thumbKey }
               → 플레이스홀더를 실제 이미지로 교체
asset.ready    { assetId }
               → "준비 중" 배지 제거
asset.failed   { assetId, reason }
session.blocks_created { blockIds }
               → 에디터가 블록 재로드
```

업로드 진행률은 포함하지 않는다. 클라이언트가 직접 전송하므로 이미 알고 있다.

---

## 8. 블록 자동 생성

### 8.1 트리거 조건

세션의 모든 항목이 아래 중 하나에 도달했을 때:

- `VERIFIED`이면서 자산이 **`PROBED` 이상**
- `SKIPPED_DUPLICATE`
- `FAILED` (포기)

**`THUMB`이나 `DERIVE`는 기다리지 않는다.** 장면 분할에 필요한 것은 `captured_at`뿐이고 그것은 `PROBE`에서 확보된다. 영상 트랜스코딩을 기다리면 블록 생성이 수 분 지연된다.

### 8.2 트랜잭션

```java
@Transactional
void createBlocks(UUID sessionId) {
    var session = sessions.findForUpdate(sessionId);      // 행 락
    if (session.blocksCreatedAt() != null) return;        // 멱등

    var assets = assets.findBySession(sessionId).stream()
        .sorted(comparing(MediaAsset::capturedAt, nullsLast(naturalOrder())))
        .toList();

    var scenes = sceneSplitter.split(assets);
    var anchor = resolveAnchor(session);
    var keys   = PositionKey.allocate(anchor, blocksNeeded(scenes));

    for (var scene : scenes) {
        if (scene.startsNewDay()) insertDateHeader(...);
        insertGallery(scene, ...);          // + record_block_media
        insertEmptyText(...);
    }

    recordDays.rebuild(session.recordId());
    records.bumpVersion(session.recordId());
    sessions.markBlocksCreated(sessionId);
    notifier.notify("session.blocks_created", sessionId);
}
```

### 8.3 엣지 케이스

**삽입 기준 블록이 삭제된 경우**
업로드 중 사용자가 그 블록을 지웠을 수 있다. 순서키가 사전순이므로 **삭제된 키보다 큰 것 중 가장 작은 블록** 앞에 삽입하고, 없으면 기록 맨 끝에 붙인다.

**`captured_at`이 없는 파일**
EXIF 없는 스크린샷, 메신저로 받은 사진 등. 우선순위는 `EXIF → 파일 mtime → 업로드 순서(ord)`이며 `captured_at_src`에 출처를 남긴다. 시각을 전혀 알 수 없는 것들은 **맨 뒤에 별도 갤러리로 모은다.**

> 이 배치는 **초기값일 뿐**이다. 사용자는 이후 어떤 사진이든 자유롭게 다른 갤러리로 옮길 수 있다. 데이터 모델에 "이 사진은 이 장면 소속"이라는 고정이 존재하지 않으므로(그래서 그룹 테이블을 없앴다) 이동에 제약이 없다. 사진 이동은 `record_block_media` 행 하나를 옮기고 `ord`를 재정렬하는 것이 전부다.

**사진 재사용은 제한하지 않는다.**

- 같은 사진을 한 기록 안의 여러 갤러리에 넣을 수 있다
- 같은 사진을 여러 기록에 넣을 수 있다

추가 구현이 필요 없다. `record_block_media`에 행이 하나 더 생길 뿐이고, 물리 파일은 내용 해시 기반이라 여전히 하나다.

삭제도 자동으로 올바르게 동작한다. 기록 1에서 사진을 빼도 기록 2가 참조 중이면 고아 판정에서 제외되므로 지워지지 않는다. **삭제 기준을 "사용자가 지웠나"가 아니라 "참조가 0인가"로 정한 판단(design.md §7.2)이 여기서 값을 한다.**

> 이 요구사항은 **미디어 ID를 payload가 아니라 참조 테이블에 둔 결정(ADR A5)이 옳았음을 검증한다.** payload 내 배열이었다면 참조 개수를 셀 수 없어 삭제 가능 여부를 판정할 방법이 없었고, "지웠더니 다른 기록의 사진이 깨지는" 사고가 발생했을 것이다.

**파생 기능**: `idx_block_media_asset` 덕분에 "이 사진이 쓰인 기록 목록"을 즉시 조회할 수 있다. 인덱스가 이미 존재하므로 추가 비용이 거의 없고, "다시 떠올리는 경험"과 결이 맞는 재료다. (2차 이후)

**자동저장과의 충돌**
`records.bumpVersion`이 사용자 편집 중 버전을 올려 자동저장이 409를 받는다. 따라서 **블록 생성은 클라이언트가 예상하는 이벤트여야 한다.** `session.blocks_created`를 받으면 조용히 재로드하되, 사용자가 타이핑 중이던 텍스트 블록은 로컬 값을 유지한다.

> ⚠️ 이 부분은 완전히 깔끔하게 해결되지 않았다. 업로드를 걸어두고 다른 블록에 글을 쓰는 것이 자연스러운 사용 패턴이라 회피할 수 없다. 실제 사용해보며 다듬을 것.

---

## 9. 실패 시나리오

| 시나리오 | 결과 | 복구 |
|---|---|---|
| 청크 전송 실패 | 해당 청크만 미수신 | 응답의 `received` 참조해 재전송. **자동** |
| 업로드 중 네트워크 끊김 | `UPLOADING`에서 정지 | 복구 시 이어서. **자동** |
| 탭 닫음 → 재방문 | 파일 접근 불가 | 재선택 필요. 완료분은 **재전송 0바이트** |
| 청크 수신 중 서버 재시작 | 진행 중 청크만 유실 | 클라이언트 타임아웃 후 재전송 |
| 해시 불일치 | 자산 `FAILED` | 재시도 안 함. "파일 손상" 표시 |
| 트랜스코딩 중 워커 `SIGKILL` | `RUNNING` 잔류 | 2분 후 lease 만료 → 회수 → 체크포인트부터 재개 |
| 스토리지 쓰기 실패 | 작업 `FAILED` | 지수 백오프 재시도 |
| 미지원 코덱 | 작업 `DEAD` | 재시도 안 함. 원본 보존, 재생 불가 표시 |
| 디스크 풀 | 모든 `DERIVE` 실패 | 백오프로 계속 재시도 (사람이 조치할 때까지) |
| 블록 생성 실패 | 트랜잭션 롤백 | `blocks_created_at`이 NULL이므로 스케줄러가 재시도 |

---

## 10. 동시성 함정

### 10.1 같은 파일을 두 세션에서 동시에 업로드

`UNIQUE (owner_id, content_hash)` 충돌이 발생한다. **에러가 아니라 정상 경로로 처리한다.**

```java
try {
    return assets.insert(newAsset);
} catch (DuplicateKeyException e) {
    return assets.findByHash(ownerId, hash).orElseThrow();
}
```

### 10.2 삭제 유예 중인 자산의 재업로드 ⚠️

30일 유예 중인 자산의 해시가 중복 조회에 걸린다. 그대로 재사용하면 **곧 삭제될 파일을 새 기록이 참조하게 되고, 30일 뒤 사진이 조용히 사라진다.**

```java
var existing = assets.findByHash(ownerId, hash);
if (existing.isPresent()) {
    assets.revive(existing.get().id());   // orphaned_at, purge_after = NULL
    return existing.get();
}
```

**재현에 30일이 걸리는 종류의 버그**다. 반드시 테스트로 고정할 것.

### 10.3 회수가 살아있는 워커의 작업을 탈취

하트비트(§5.2)로 1차 방어, 완료 시 소유권 확인(§5.4)으로 2차 방어.

### 10.4 세션 완료 판정 경합

여러 워커가 각자 마지막 자산을 끝내며 동시에 "세션 완료"를 판정할 수 있다. 세션 행에 `FOR UPDATE` 락을 잡고 `blocks_created_at`을 확인한다. **블록이 두 벌 생기는 것을 막는 유일한 방어선이다.**

---

## 11. 미결 사항

- [ ] 청크 크기 실측 (5MB가 모바일 네트워크에서 적절한가)
- [ ] 동시 업로드 개수 실측 (3개가 적절한가)
- [ ] 해싱 Worker 개수 실측
- [ ] lease 만료 기준 2분 / 하트비트 30초의 적정성
- [ ] `beforeunload` 경고 문구
- [ ] 블록 자동 생성과 자동저장 충돌의 UX 다듬기 (§8.3)
