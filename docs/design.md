# 개인 라이프 아카이브 — 설계 문서

> 사진을 중심으로 하루의 기억을 기록하고, 시간이 지나도 그날의 흐름을 다시 떠올릴 수 있게 돕는 개인 기록 서비스.

- **작성 시점**: 2026-08-05
- **스택**: Java 21 / Spring Boot 4 / PostgreSQL / MinIO(S3 호환) / libvips / FFmpeg
- **클라이언트**: React + TypeScript + Vite 웹앱 (모바일 브라우저 우선). 서버 상태는 TanStack Query

### 문서 구성

| 문서 | 내용 |
|---|---|
| **design.md** (이 문서) | 프로젝트 정체성, 설계 원칙, 데이터 모델, 삭제 정책, MVP 범위, ADR |
| [pipeline.md](./pipeline.md) | 업로드 프로토콜, 워커 상태 머신, 재시도·백오프, SSE, 실패 시나리오 |
| [roadmap.md](./roadmap.md) | 5주 계획, 잘라낼 순서, 불변식 명세, 에이전트 루프, 측정 항목 |
| [interview-notes.md](./interview-notes.md) | 기술 판단을 면접 질문 형태로 정리 (구현하며 계속 갱신) |

---

## 1. 이 프로젝트가 무엇인가

### 1.1 배경

작성자는 매일 아날로그로 모닝페이지와 일기를 쓰고, 매달 2~3회 네이버 블로그에 며칠간의 일상을 사진과 함께 기록한다.

블로그를 쓸 때 사진을 고르고, 순서를 정하고, 글을 쓰는 과정이 오래 걸린다. 하지만 그 과정을 좋아하는 이유는 **사진을 보며 "이날 이런 일이 있었지" 하고 기억을 떠올릴 수 있기 때문**이다.

### 1.2 설계 철학

> **"고르는 일"은 남기고 "추리는 일"과 "배치하는 일"을 없앤다.**

즐거운 것은 사진을 보며 그날을 떠올리는 것이고, 지겨운 것은 순서를 맞추고 어디서 끊을지 정하는 일이다. 뒤엣것만 기계가 한다.

AI는 **문장을 대신 쓰지 않는다.** 기억을 꺼내는 것을 도울 뿐이다. 완성된 문단을 먼저 던져주면 사용자는 백지에서 떠올리는 대신 남의 글을 다듬게 되고, 그러면 이 서비스의 존재 이유가 사라진다.

### 1.3 목표

1. 작성자가 실제로 꾸준히 사용하는 서비스가 될 것
2. 백엔드 포트폴리오로 설명할 수 있는 기술적 깊이를 가질 것
3. 검증을 자동화해 에이전트가 자율적으로 개선 루프를 돌 수 있는 구조일 것

---

## 2. 핵심 설계 원칙

| # | 원칙 | 이유 |
|---|---|---|
| P1 | 미디어와 기록을 참조로 연결한다 | "이 사진이 어떤 기록에 쓰였나"를 인덱스로 즉시 답하기 위해 |
| P2 | 미디어는 내용 해시(SHA-256)로 식별한다 | 중복 제거 + 무결성 검증이 공짜로 따라옴 |
| P3 | 파생물은 (원본 × 레시피 버전)의 함수다 | 무중단 백필과 롤백이 가능해짐. 파생물은 데이터가 아니라 캐시 |
| P4 | 무거운 작업은 API 서버에서 하지 않는다 | 응답 지연과 처리량을 분리 |
| P5 | 덜 위험한 실패 쪽으로 순서를 정한다 | 깨진 참조(치명적)보다 고아 파일(회수 가능)이 낫다 |
| P6 | AI는 잎사귀(leaf)로 유지한다 | 외부 의존성이 죽어도 핵심 기능은 동작해야 함 |

---

## 3. 시스템 구성

```
┌─────────────┐
│  웹 클라이언트 │  해시 선계산(Web Worker) · 청크 업로드 · SSE 수신
└──────┬──────┘
       │ HTTP / SSE
┌──────▼──────┐        ┌──────────────┐
│  API 서버    │───────▶│  PostgreSQL  │  메타데이터 + 작업 큐
│ (Spring Boot)│        └──────▲───────┘
└──────┬──────┘               │
       │                      │ SKIP LOCKED 폴링
       │              ┌───────┴───────┐
       │              │  워커 (PHOTO)  │  libvips
       │              │  워커 (VIDEO)  │  FFmpeg
       │              └───────┬───────┘
       │                      │
┌──────▼──────────────────────▼───────┐
│  MinIO (S3 호환)                     │  원본 + 파생물
└─────────────────────────────────────┘
```

### 3.1 메시지 브로커를 쓰지 않는 이유

Kafka도 RabbitMQ도 Redis도 쓰지 않는다. 작업 큐는 PostgreSQL 테이블 + `SELECT ... FOR UPDATE SKIP LOCKED`로 구현한다.

- **트랜잭션 일관성이 공짜다.** 미디어 자산 생성과 처리 작업 등록이 같은 트랜잭션에 묶여, "자산은 생겼는데 작업이 유실됨"이 원천적으로 불가능하다. 브로커를 쓰면 아웃박스 패턴을 따로 만들어야 한다.
- **운영 컴포넌트가 하나 줄어든다.** 로컬 개발, 통합 테스트, 배포가 모두 가벼워진다.
- 이 규모(개인용, 초당 수 건)에서 브로커의 처리량 이점은 무의미하다.

**Redis를 도입할 조건**: 워커를 3대 이상으로 늘려 DB 폴링이 부담될 때, 또는 업로드 진행률을 다중 클라이언트에 브로드캐스트해야 할 때. 그 전까지는 넣지 않는다.

---

## 4. 화면 흐름

```
① 새 기록 작성
   └─ records 1행 생성 (status=DRAFT, version=0)

② ⊕ 블록 추가 → 사진/영상 선택 (예: 20장)
   ├─ 브라우저: Web Worker에서 스트리밍 SHA-256 계산
   ├─ upload_sessions 생성 (record_id, insert_after_position)
   ├─ 서버: 기존 해시 조회 → 중복은 SKIPPED_DUPLICATE (전송 생략)
   └─ 나머지만 청크 업로드

③ 파일별 완료 시
   ├─ 서버가 해시 재검증
   ├─ media_assets 생성 + processing_jobs 등록 (같은 트랜잭션)
   └─ SSE로 진행 상황 전송 (썸네일 준비되는 대로 화면 교체)

④ 세션 완료 시 — 블록 자동 생성
   ├─ 촬영시각 정렬 → 장면 분할
   ├─ DATE_HEADER / GALLERY / 빈 TEXT 블록을 지정 위치에 삽입
   └─ record_days 갱신

⑤ 사용자가 글 작성 · 레이아웃 변경 · 사진 제거 · 갤러리 분할/병합
   └─ 3초 디바운스 자동저장 (변경된 블록만 PATCH)

⑥ ②로 돌아가 추가 업로드 (여러 번 가능, 세션이 여러 개 생김)

⑦ 발행
   └─ records.status = PUBLISHED → 홈 타임라인에 등장
      ※ 영상 처리가 끝나지 않아도 발행 가능. 해당 영상만 "준비 중" 표시
```

### 4.1 블록 추가 메뉴

```
⊕ ─┬─ 📷 사진 / 영상
    ├─ ✏️ 텍스트
    ├─ 📅 날짜
    ├─ 📍 장소
    └─ ➖ 구분선
```

### 4.2 화면별 노출 규칙

| 화면 | 보이는 것 |
|---|---|
| 홈 / 타임라인 | 발행된 기록만 |
| 기록 작성 화면 | 해당 기록에 속한 블록과 사진 |
| 최근 삭제된 항목 | 고아 상태이면서 아직 물리 삭제되지 않은 자산 |

어떤 기록에도 쓰이지 않은 사진은 홈에 나타나지 않는다.

---

## 5. 데이터 모델

### 5.1 테이블 목록

| 테이블 | 우리말 | 한 줄 설명 |
|---|---|---|
| `users` | 사용자 | 계정 (소셜 로그인) |
| `media_assets` | 미디어 자산 | 업로드된 사진/영상 원본 1건 |
| `media_derivatives` | 파생물 | 썸네일·리사이즈·트랜스코딩 결과물 |
| `upload_sessions` | 업로드 세션 | "이 기록에 N개 올리기" 한 묶음 |
| `upload_items` | 업로드 항목 | 세션 안의 파일 1개와 진행 상태 |
| `processing_jobs` | 처리 작업 | 워커가 집어갈 일감 |
| `records` | 기록 | 게시글 1건 |
| `record_blocks` | 기록 블록 | 글 안의 문단·갤러리·장소 하나 |
| `record_block_media` | 블록-미디어 연결 | 블록이 어떤 사진을 쓰는지 |
| `record_days` | 기록 날짜 색인 | 글 안의 날짜 목차 (파생 데이터) |
| `suggestions` | AI 제안 | 초안 제안 요청과 결과 |

---

### 5.2 `users` — 사용자

```sql
CREATE TABLE users (
  id           uuid PRIMARY KEY,
  provider     text NOT NULL,       -- 'GOOGLE' | 'NAVER' 등 소셜 제공자
  provider_uid text NOT NULL,       -- 제공자 쪽 사용자 식별자
  email        text,
  display_name text,
  created_at   timestamptz NOT NULL DEFAULT now(),

  UNIQUE (provider, provider_uid)
);
```

자체 회원가입/비밀번호는 만들지 않는다. 이메일 인증, 비밀번호 재설정, 계정 복구까지 구현하면 최소 1주가 소모되는데 포트폴리오 가치는 거의 없다.

---

### 5.3 `media_assets` — 미디어 자산

```sql
CREATE TABLE media_assets (
  id              uuid PRIMARY KEY,
  owner_id        uuid NOT NULL REFERENCES users(id),

  content_hash    char(64) NOT NULL,    -- 파일 내용의 SHA-256. 이것이 진짜 정체성.
                                        -- 같은 파일을 다시 올려도 하나로 취급된다
  byte_size       bigint  NOT NULL,     -- 원본 크기 (바이트)
  mime_type       text    NOT NULL,     -- image/jpeg, image/heic, video/quicktime ...
  kind            text    NOT NULL,     -- 'PHOTO' | 'VIDEO'. 처리 레인을 가르는 값
  storage_key     text    NOT NULL,     -- MinIO 내 위치. 해시 기반 경로로 디렉토리 분산
                                        -- 예: originals/ab/cd/abcdef0123...

  captured_at     timestamptz,          -- 실제 촬영 시각. EXIF에서 추출. 없을 수 있음
  captured_at_src text,                 -- 위 값의 출처: 'EXIF' | 'FILE_MTIME' | 'MANUAL'
                                        -- 날짜가 충돌할 때 무엇을 믿을지 판단하는 근거
  tz_offset_min   int,                  -- 촬영 시각의 시간대 오프셋(분)
                                        -- 여행 사진에서 이 값이 없으면 시간축이 어긋난다

  gps_lat         numeric(9,6),         -- 위도 (없을 수 있음)
  gps_lon         numeric(9,6),         -- 경도

  width           int,                  -- 가로 픽셀
  height          int,                  -- 세로 픽셀
  orientation     int,                  -- EXIF 회전 플래그(1~8). 무시하면 사진이 눕는다
  duration_ms     int,                  -- 영상 길이. 사진이면 NULL

  phash           bigint,               -- perceptual hash. 연사 중복 감지용 (2차 기능)

  status          text NOT NULL,        -- 처리 도달 단계:
                                        -- 'INGESTED' 원본 저장 완료
                                        -- 'PROBED'   메타데이터 추출 완료
                                        -- 'THUMBED'  썸네일 생성 완료 (화면 표시 가능)
                                        -- 'READY'    모든 파생물 생성 완료
                                        -- 'FAILED'   복구 불가 실패

  orphaned_at     timestamptz,          -- 어떤 기록에서도 참조되지 않게 된 시각
                                        -- NULL이면 사용 중
  purge_after     timestamptz,          -- 이 시각 이후 물리 삭제 대상 (기본 orphaned_at + 30일)

  created_at      timestamptz NOT NULL DEFAULT now(),

  UNIQUE (owner_id, content_hash)       -- 같은 사용자의 같은 파일은 하나만 존재
);

CREATE INDEX idx_assets_captured  ON media_assets (owner_id, captured_at);
CREATE INDEX idx_assets_purge     ON media_assets (purge_after) WHERE purge_after IS NOT NULL;
```

**`status`가 `processing_jobs.state`와 중복 아닌가?** 역할이 다르다. `processing_jobs.state`는 개별 작업의 상태이고, `media_assets.status`는 자산이 도달한 단계를 요약한 값이다. 사진 70장을 화면에 뿌릴 때마다 jobs를 집계하면 느리므로 자산 쪽에 요약해 둔다. 조회 성능을 위해 파생 데이터를 중복시키는 정당한 경우.

**제외한 컬럼**
- `quality_score` (선명도) — 흔들린 사진을 후순위로 미는 용도였으나, 작성자가 촬영본의 대부분을 사용하므로 가치가 없다
- `pipeline_ver` — 파생물 재생성은 `media_derivatives.recipe_ver`가 담당한다. 메타 추출 로직 자체가 바뀔 때 필요해지면 그때 추가한다

---

### 5.4 `media_derivatives` — 파생물

```sql
CREATE TABLE media_derivatives (
  id           uuid PRIMARY KEY,
  asset_id     uuid NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,

  recipe       text NOT NULL,        -- 레시피 이름 (아래 표 참조)
  recipe_ver   int  NOT NULL,        -- 레시피 버전. 알고리즘을 바꾸면 올린다

  storage_key  text   NOT NULL,      -- MinIO 내 위치
  byte_size    bigint NOT NULL,
  content_hash char(64) NOT NULL,    -- 결과물의 해시.
                                     -- "같은 입력 → 같은 출력" 불변식 검증에 사용
  created_at   timestamptz NOT NULL DEFAULT now(),

  UNIQUE (asset_id, recipe, recipe_ver)
);
```

#### 레시피 정의

| recipe | ver | 내용 | 대상 |
|---|---|---|---|
| `THUMB_256` | 1 | 256×256 정사각 센터크롭, WebP q80 | 사진·영상 공통 |
| `PREVIEW_1600` | 1 | 긴 변 1600px, WebP q82, 회전 보정 | 사진 |
| `POSTER` | 1 | 1초 지점 프레임 캡처 → JPEG | 영상 |
| `VIDEO_720P` | 1 | H.264 720p CRF23 / AAC 128k | 영상 |

#### 레시피 버전이 필요한 이유

썸네일 품질을 올리고 싶어졌다고 하자. 버전이 없으면 전부 지우고 다시 만들어야 하고, 재생성 중에는 화면이 깨지며, 결과가 나빠도 되돌릴 수 없다.

버전이 있으면:

1. `THUMB_256`의 `recipe_ver`를 1 → 2로 올린다
2. 새로 올라오는 자산은 자동으로 v2 생성
3. 백필 배치가 `recipe_ver = 1`인 것을 골라 천천히 v2를 만든다 (**v1은 그대로 둔다**)
4. 조회는 "v2가 있으면 v2, 없으면 v1" → 재처리 중에도 화면이 깨지지 않는다
5. 완료 후 v1 삭제. 결과가 나쁘면 v2를 버리고 롤백

**결정론**: 같은 원본 + 같은 레시피 버전이면 항상 같은 바이트가 나와야 한다. 이것이 성립하면 파생물은 데이터가 아니라 캐시이므로, 백업은 원본만 하면 된다.

---

### 5.5 `upload_sessions` / `upload_items` — 업로드

```sql
CREATE TABLE upload_sessions (
  id                    uuid PRIMARY KEY,
  owner_id              uuid NOT NULL REFERENCES users(id),
  record_id             uuid NOT NULL REFERENCES records(id),
                                            -- 어느 기록에 올리는 중인지.
                                            -- 기록을 먼저 만들고 업로드하는 흐름
  insert_after_position text,               -- 어느 블록 뒤에 꽂을지.
                                            -- NULL이면 기록 맨 끝
  status                text NOT NULL,      -- 'OPEN' | 'COMPLETED' | 'ABORTED'
  total_items           int  NOT NULL,      -- 클라이언트가 선언한 파일 개수 (진행률 분모)
  created_at            timestamptz NOT NULL DEFAULT now(),
  completed_at          timestamptz
);

CREATE TABLE upload_items (
  id             uuid PRIMARY KEY,
  session_id     uuid NOT NULL REFERENCES upload_sessions(id) ON DELETE CASCADE,

  content_hash   char(64),             -- 클라이언트가 계산해 보고한 해시. 신뢰하지 않고
                                      -- PROBE 단계에서 재계산해 검증한다.
                                      -- NULL 허용: 세션 생성 시점에는 아직 계산 전이다.
                                      -- (해싱과 업로드를 겹쳐 돌리기 위한 설계. pipeline.md §2 참조)
  filename       text,                -- 원래 파일명 (표시용. 신뢰하지 않는다)
  byte_size      bigint NOT NULL,
  mime_type      text,

  state          text NOT NULL,       -- 'PENDING'            대기
                                      -- 'SKIPPED_DUPLICATE'  이미 존재 → 전송 생략
                                      -- 'UPLOADING'          전송 중
                                      -- 'VERIFIED'           전송 완료 + 서버 해시 재검증 통과
                                      -- 'FAILED'
  uploaded_bytes bigint DEFAULT 0,    -- 진행률 표시용
  chunk_bitmap   bytea,               -- 완료된 청크를 비트로 표시.
                                      -- 끊겼을 때 "몇 번 청크부터 다시"를 여기서 계산
  asset_id       uuid REFERENCES media_assets(id),
  error_code     text,                -- 실패 원인. 재시도 가능 여부 판단에 사용
  ord            int NOT NULL         -- 세션 내 순서 (블록 생성 시 참고)
);

CREATE INDEX idx_upload_items_session ON upload_items (session_id, state);
```

#### 세션이 필요한 이유

`upload_items`만으로는 해결되지 않는 것들:

1. **"이번에 올린 묶음"을 정의할 수 없다.** 시간 범위로 자르는 것은 부정확하다
2. **삽입 위치를 담을 곳이 없다.** `insert_after_position`은 묶음 단위 속성이다
3. **중단된 업로드를 재개할 수 없다.** "진행 중이던 업로드가 있습니다"를 띄우려면 묶음 식별자가 필요하다
4. **진행률의 분모가 없다.** `total_items`가 있어야 "70개 중 43개"를 계산할 수 있고, 선언보다 적게 도착하면 이상을 감지할 수 있다

#### 업로드 재개 정책

| 상황 | 동작 |
|---|---|
| 네트워크 끊김, 청크 실패 (같은 화면 유지) | **자동 재개.** `chunk_bitmap` 기준으로 실패 청크부터 재전송 |
| 탭을 닫았다가 재방문 | **파일 재선택 필요.** 단 완료분은 해시 조회에서 걸러져 재전송 바이트 0 |
| 탭을 닫은 채로 백그라운드 업로드 | **불가능.** 웹 플랫폼 한계. `beforeunload`로 이탈 경고를 띄운다 |

브라우저는 페이지를 벗어나면 `File` 객체를 잃는다. 따라서 두 번째 경우 사용자가 같은 파일을 다시 선택해야 하지만, 내용 해시 기반 중복 제거 덕분에 이미 올라간 파일은 한 바이트도 다시 보내지 않는다.

---

### 5.6 `processing_jobs` — 처리 작업 큐

```sql
CREATE TABLE processing_jobs (
  id           bigserial PRIMARY KEY,
  asset_id     uuid NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,

  stage        text NOT NULL,        -- 'PROBE'  메타데이터 추출
                                     -- 'THUMB'  썸네일/포스터 (최우선, 가볍게)
                                     -- 'DERIVE' 나머지 파생물 (영상 트랜스코딩 포함)
  lane         text NOT NULL,        -- 'PHOTO' | 'VIDEO'
                                     -- 워커 풀을 나누는 기준 (아래 설명)

  state        text NOT NULL,        -- 'QUEUED' | 'RUNNING' | 'DONE'
                                     -- | 'FAILED' 재시도 예정 | 'DEAD' 포기
  attempt      int  NOT NULL DEFAULT 0,
  max_attempts int  NOT NULL DEFAULT 5,

  run_after    timestamptz NOT NULL DEFAULT now(),
                                     -- 이 시각 이후 실행. 실패 시 지수 백오프로 미룬다
  locked_by    text,                 -- 작업을 잡은 워커 인스턴스 ID
  locked_at    timestamptz,          -- 잡은 시각. 오래되면 죽은 워커로 보고 회수

  checkpoint   jsonb,                -- 중간 진행 상태.
                                     -- 예: 영상 트랜스코딩 {"processed_sec": 84}
                                     -- 워커가 죽어도 여기서부터 재개
  last_error   text,
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_pickup ON processing_jobs (lane, state, run_after, id);
```

#### 워커의 작업 획득 쿼리

```sql
SELECT * FROM processing_jobs
 WHERE state = 'QUEUED' AND lane = :lane AND run_after <= now()
 ORDER BY id
 LIMIT 1
 FOR UPDATE SKIP LOCKED;   -- 다른 워커가 잡은 행은 건너뛴다. 이것이 큐의 핵심
```

#### 단계별 실제 작업

**사진** (`IMG_1234.HEIC`, 3.5MB, 4032×3024)

| stage | 하는 일 | 도구 | 소요 |
|---|---|---|---|
| `PROBE` | 크기, EXIF 촬영시각, GPS, 회전 플래그, 실제 포맷 | metadata-extractor | ~20ms |
| `THUMB` | 256px 썸네일 (HEIC→WebP 변환 포함) | libvips | ~150ms |
| `DERIVE` | 1600px 프리뷰, 회전 보정 | libvips | ~300ms |

**영상** (`IMG_5678.MOV`, 180MB, 45초, 4K)

| stage | 하는 일 | 도구 | 소요 |
|---|---|---|---|
| `PROBE` | 코덱, 해상도, 길이, 회전, 촬영시각 | ffprobe | ~200ms |
| `THUMB` | 1초 지점 프레임 캡처 → 포스터 | ffmpeg | ~1초 |
| `DERIVE` | H.264 720p 트랜스코딩 | ffmpeg | **30초~2분** |

#### 단계를 나누는 이유

**빠른 정보를 먼저 주기 위해서다.** `PROBE`가 20ms 만에 끝나면 촬영시각을 알게 되어 사진을 시간순으로 배열할 수 있다. 썸네일이 없어도 자리는 잡힌다. 이어서 `THUMB`이 끝나는 대로 그림이 하나씩 채워진다.

한 덩어리로 묶으면 몇 분간 아무것도 보이지 않다가 한꺼번에 뜬다. 그동안 사용자는 고장 난 줄 안다.

**실패 단위를 좁히는 것**도 이유다. 트랜스코딩만 실패했다면 `DERIVE`만 재시도하면 된다.

#### `lane`이 필요한 이유

워커 4개, 사진 66장 + 영상 4개를 올린 상황:

- **레인 없음**: 큐 앞쪽 영상 4개를 워커 4개가 전부 물고 2분간 놓지 않는다. 사진 66장은 그동안 아무것도 처리되지 않고 화면은 비어 있다
- **레인 분리** (사진 3 + 영상 1): 사진 66장이 20초 만에 뜨고, 영상은 뒤에서 천천히 익는다. 사용자는 사진을 보며 글을 쓰기 시작할 수 있다

전형적인 헤드 오브 라인 블로킹 회피.

---

### 5.7 `records` — 기록

```sql
CREATE TABLE records (
  id             uuid PRIMARY KEY,
  owner_id       uuid NOT NULL REFERENCES users(id),
  title          text,                -- "2026, 7월의 조각 (2)"
  status         text NOT NULL,       -- 'DRAFT' 임시저장 | 'PUBLISHED' 발행됨
  visibility     text NOT NULL DEFAULT 'PRIVATE',
                                      -- 'PRIVATE' | 'PUBLIC'
  version        int  NOT NULL DEFAULT 0,
                                      -- 낙관적 락. 저장 시 클라이언트가 알던 버전을 함께 보낸다.
                                      -- 서버 버전과 다르면 409 Conflict
  cover_asset_id uuid REFERENCES media_assets(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  published_at   timestamptz
);
```

#### `version`이 하는 일

웹앱이라 탭 두 개만 열어도 발생하는 상황이다.

1. 탭 A가 기록을 읽음 → `version = 7`
2. 탭 B가 같은 기록을 읽음 → `version = 7`
3. 탭 B가 저장 → `version = 8`
4. 탭 A가 저장 시도, `expected_version = 7` 전송
5. 서버: 현재 8인데 7을 기대 → **409 Conflict**
6. 탭 A: "다른 곳에서 수정되었습니다. 새로고침할까요?"

이것이 없으면 탭 A의 저장이 탭 B의 수정을 조용히 덮어쓴다.

자동저장은 자기 자신과 충돌하지 않는다. 응답으로 새 `version`을 받아 갱신하기 때문이다.

#### 기간 제한

기록 하나가 담을 수 있는 날짜 범위에 상한을 두지 않는다. 하루일 수도, 석 달일 수도 있다. 날짜가 연속일 필요도 없다.

블록이 수백 개가 되면 조회가 무거워지지만, 개인 글이 500개를 넘기기 어려우므로 일단 전부 전송한다. 느려지면 그때 페이지네이션을 도입한다.

---

### 5.8 `record_blocks` — 기록 블록

```sql
CREATE TABLE record_blocks (
  id             uuid PRIMARY KEY,
  record_id      uuid NOT NULL REFERENCES records(id) ON DELETE CASCADE,

  position       text NOT NULL,      -- 순서키. 정수가 아니라 문자열(fractional index).
                                     -- "a0" < "a0V" < "a1" 처럼 사전순 정렬.
                                     -- 두 블록 사이에 끼울 때 중간값 문자열을 만들면
                                     -- 다른 블록을 건드리지 않고 INSERT 한 번으로 끝난다
  type           text NOT NULL,      -- 아래 블록 타입 표 참조
  schema_version int  NOT NULL DEFAULT 1,
                                     -- payload 구조의 버전. 지금은 전부 1이지만 반드시 둔다
  payload        jsonb NOT NULL,     -- 타입별 고유 내용.
                                     -- ※ 미디어 ID는 여기 넣지 않는다 (아래 참조)
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),

  UNIQUE (record_id, position)
);

CREATE INDEX idx_blocks_record ON record_blocks (record_id, position);
```

#### 블록 타입

| type | 설명 | payload 예시 |
|---|---|---|
| `DATE_HEADER` | 날짜 구분 헤더 | `{"date": "2026-07-18"}` |
| `GALLERY` | 사진/영상 묶음 (1개 이상) | `{"layout": "SLIDE"}` |
| `TEXT` | 글 문단 | `{"text": "오늘은 쩡이와..."}` |
| `PLACE` | 장소 카드 | 아래 참조 |
| `DIVIDER` | 구분선 | `{}` |

#### `GALLERY` 레이아웃

```jsonc
{ "layout": "SLIDE" }     // 기본. 좌우로 넘기는 캐러셀
{ "layout": "COLLAGE" }   // 격자 배치 (2장이면 나란히, 4장이면 2×2)
{ "layout": "LIST" }      // 세로로 쭉 나열
```

**`PHOTO` / `VIDEO` 블록 타입은 두지 않는다.** 사진 1장짜리 갤러리가 곧 단일 사진이다. 별도 타입으로 분리하면 "슬라이드 → 개별 나열" 전환 시 갤러리 블록 1개를 사진 블록 N개로 쪼개고 순서키를 다시 매겨야 한다. 통합하면 **payload 한 필드 UPDATE로 끝난다.**

영상도 갤러리 안에 사진과 섞인다. 실제로 사진을 찍다가 영상을 찍고 또 사진을 찍으므로, 시간순으로 한 장면 안에 섞여 있는 것이 자연스럽다.

#### `PLACE` payload

```jsonc
{
  "name": "카츠노이에 성신여대본점",
  "address": "서울특별시 성북구 동소문로22길 29-2 1층",
  "lat": 37.5926,
  "lon": 127.0165,
  "external_url": "https://naver.me/xxxxx"
}
```

**지도 API를 호출하지 않는다.** 참고한 블로그의 장소 카드는 지도 타일을 렌더링하지 않고 아이콘 + 상호명 + 주소 + 링크만 보여준다. 저장된 값을 그리기만 하면 되므로 호출 0회, 비용 0원이다.

장소 입력 방식의 단계적 계획:

1. **MVP** — 상호명·주소 수동 입력 + 링크 붙여넣기
2. **2차** — 네이버 지역검색 API 연동 (오픈API, 무료 한도 넉넉)
3. **3차(선택)** — 공유 URL 붙여넣기 → 서버가 장소 정보 추출. 스크래핑이라 외부 구조 변경에 취약하며, 파서 버저닝·실패 시 수동 입력 강등·깨짐 자동 감지가 필요하다

> ⚠️ 네이버 지도 API(NCP)와 지역검색 API의 무료 한도 및 요금 정책은 변경될 수 있다. 도입 전에 직접 확인할 것. 한도 초과 시 과금되므로 호출 루프에 주의.

#### `schema_version`이 필요한 이유

나중에 갤러리에 사진별 캡션을 추가하고 싶어지면 payload 구조가 바뀐다.

```jsonc
// v1
{ "layout": "COLLAGE" }
// v2
{ "layout": "COLLAGE", "captions": ["영화표 인증", "리클라이너 최고"] }
```

버전 필드가 있으면 읽을 때 분기하거나 배치로 마이그레이션할 수 있다. 없으면 기존 데이터가 어느 구조인지 영원히 알 수 없다.

Java에서는 타입별 `sealed interface` + Jackson 다형성 역직렬화로 매핑한다.

---

### 5.9 `record_block_media` — 블록-미디어 연결

```sql
CREATE TABLE record_block_media (
  block_id uuid NOT NULL REFERENCES record_blocks(id) ON DELETE CASCADE,
  asset_id uuid NOT NULL REFERENCES media_assets(id),
  ord      int  NOT NULL,     -- 갤러리 안에서의 순서
  PRIMARY KEY (block_id, ord)
);

CREATE INDEX idx_block_media_asset ON record_block_media (asset_id);
```

#### 사진 재사용은 제한하지 않는다

같은 사진을 한 기록 안의 여러 갤러리에 넣어도 되고, 여러 기록에 넣어도 된다. 행이 하나 더 생길 뿐이며 물리 파일은 여전히 하나다.

삭제도 자동으로 맞게 동작한다. 기록 1에서 빼도 기록 2가 참조 중이면 고아 판정에서 제외된다(§7.2).

#### 미디어 ID를 payload에 넣지 않는 이유

`{"asset_ids": ["uuid1", "uuid2"]}` 형태로 JSONB 안에 넣고 싶어지지만 하지 않는다.

- **역추적이 안 된다.** "이 사진이 어떤 기록에 쓰였나"를 알려면 모든 블록의 JSON을 뒤져야 하고 인덱스도 제대로 걸리지 않는다. `idx_block_media_asset` 하나면 즉시 답이 나온다
- **참조 무결성이 없다.** 자산을 삭제해도 JSON 안의 UUID는 남아 깨진 참조가 된다
- **삭제 판정에 쓸 수 없다.** 고아 자산 스윕이 이 테이블을 기준으로 동작한다

---

### 5.10 `record_days` — 날짜 색인 (파생 데이터)

```sql
CREATE TABLE record_days (
  record_id            uuid NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  day_date             date NOT NULL,
  first_block_position text NOT NULL,   -- 그 날짜 섹션이 시작되는 블록의 순서키
  PRIMARY KEY (record_id, day_date)
);

CREATE INDEX idx_record_days_date ON record_days (day_date);
```

**블록이 여기 들어가지 않는다. 목차일 뿐이다.**

예시 — "2026, 7월의 조각 (2)" 기록의 블록이 아래와 같을 때,

| position | type | payload |
|---|---|---|
| `a0` | DATE_HEADER | `{"date":"2026-07-18"}` |
| `a1` | TEXT | `{"text":"오늘은 쩡이와 현정이를..."}` |
| `a2` | GALLERY | `{"layout":"COLLAGE"}` |
| `a3` | TEXT | `{"text":"호프의 후기가 호불호가..."}` |
| … | | |
| `b0` | DATE_HEADER | `{"date":"2026-07-22"}` |

`record_days`는 이렇게 된다.

| record_id | day_date | first_block_position |
|---|---|---|
| R1 | 2026-07-18 | `a0` |
| R1 | 2026-07-22 | `b0` |

**용도**

1. "2026년 7월 18일에 뭐 했지?"를 한 번의 조회로 답한다. 이것이 없으면 모든 블록의 JSONB를 뒤져야 한다
2. 긴 기록 안에서 특정 날짜로 점프하는 목차
3. 홈 타임라인 구성

**파생 데이터**이므로 `record_blocks`의 `DATE_HEADER`를 순회하면 언제든 재생성할 수 있다. 블록 저장 시 함께 갱신하며, "통째로 지우고 다시 만들어도 결과가 같다"가 검증 대상 불변식이 된다.

---

### 5.11 `suggestions` — AI 제안

```sql
CREATE TABLE suggestions (
  id           uuid PRIMARY KEY,
  owner_id     uuid NOT NULL REFERENCES users(id),
  record_id    uuid REFERENCES records(id),

  input_digest char(64) NOT NULL,  -- 입력 자산 해시들을 정렬해 다시 해싱한 값.
                                   -- 같은 사진 묶음으로 재요청하면 캐시 히트
  model        text NOT NULL,
  prompt_ver   int  NOT NULL,      -- 프롬프트 버전. 품질 회귀 추적용

  state        text NOT NULL,      -- 'PENDING' | 'DONE' | 'FAILED'
  result       jsonb,              -- { "timeline": [...], "questions": [...] }
  token_in     int,
  token_out    int,
  latency_ms   int,
  created_at   timestamptz NOT NULL DEFAULT now(),

  UNIQUE (owner_id, input_digest, prompt_ver)
);
```

#### AI가 하는 일과 하지 않는 일

**하지 않는 것: 완성된 문장 작성.**

참고 블로그의 문장들 — "쩡이가 호프라는 영화를 보고 싶다고 하길래", "치즈 카츠 하나 터졌다고 두개를 다시 해주셨어요" — 은 사진에 없는 정보이며 그 자리에 있던 사람만 안다. AI가 쓸 수 없고, 억지로 쓰면 빈 수사가 된다.

더 중요한 문제는, 완성된 문단을 먼저 제시하면 사용자가 백지에서 떠올리는 대신 남의 글을 다듬게 된다는 것이다. **이 서비스가 지키려는 경험이 바로 그 "떠올리는 시간"이다.**

**하는 것 ① 뼈대와 단서**

```
11:20 성신여대입구 도착 · 12:00 CGV · 14:30 카츠노이에 · 16:00 인생네컷
눈에 띄는 것: 리클라이너 좌석, 치즈 카츠, 거울 셀카
```

**하는 것 ② 질문 던지기**

```
"이 돈카츠집 어땠어?"
"이 장면을 세 번 찍었네, 뭐가 좋았어?"
```

기억을 대신하는 것이 아니라 꺼내는 방식이다.

#### 잎사귀 원칙

- `suggestion` 패키지 **밖에서는 LLM이라는 단어가 등장하지 않는다**
- 이 테이블이 통째로 비어 있어도 서비스는 정상 동작한다
- 사용자가 "제안 받기"를 누를 때만 비동기로 호출하고, 실패하면 빈 블록으로 진행한다

---

## 6. 장면 분할

업로드된 사진들을 촬영시각 순으로 늘어놓고, 사진 사이 간격이 크면 다른 장면으로 끊는다.

```
11:20 ─┐
11:22  │  간격 2분, 3분  →  같은 장면 (3장)
11:25 ─┘
   ⋮      40분 공백      ←  임계값 초과, 여기서 끊는다
12:05 ─┐
12:08 ─┘  →  다음 장면
```

### 6.1 의사코드

```
fun splitScenes(assets: List<MediaAsset>, gapMinutes: Int = 20): List<Scene>
    sorted = assets.sortedBy { it.capturedAt }
    scenes = []
    current = [sorted.first()]

    for (prev, curr) in sorted.zipWithNext():
        gap = curr.capturedAt - prev.capturedAt
        movedFar = distance(prev.gps, curr.gps) > 500m   // 좌표가 있을 때만
        dayChanged = curr.date != prev.date

        if (dayChanged || gap > gapMinutes || movedFar):
            scenes += Scene(current); current = []
        current += curr

    scenes += Scene(current)
    return scenes
```

### 6.2 이 방식의 한계 — 그리고 그것이 괜찮은 이유

**자주 틀린다.** 영화관에서 12:08에 사진을 찍고 14:10에 나와서 또 찍으면 2시간 간격이라 끊기지만, 사용자에게는 "영화 보기"라는 하나의 장면이다. GPS 보완책이 있지만 실내에서는 부정확하고, 아이폰이 위치를 남기지 않는 경우도 있다.

그래서 **사용자가 갤러리를 쪼개고 합칠 수 있어야 하며, 이것은 MVP 필수 기능이다.**

### 6.3 별도 override 테이블을 두지 않는 이유

장면 분할은 **업로드 시점에 한 번만** 계산되고 그 결과가 곧바로 블록으로 굳는다. 나중에 재계산할 일이 없으므로, 사용자 조정을 따로 보존할 이유도 없다.

갤러리를 쪼개는 것은 그냥 **블록 편집**이다. 갤러리 블록 하나를 둘로 나누고 순서키를 하나 발급하면 끝이고, 합치는 것은 그 반대다. 이미 존재하는 블록 조작이지 별도 개념이 아니다.

> 설계 과정에서 `media_groups` / `media_group_members` / `media_group_overrides` 3개 테이블을 두었다가, 작성자가 촬영본의 대부분을 사용한다는 사실이 확인되면서 "추려내기"가 불필요해졌고, 최종적으로 전부 삭제되었다.

---

## 7. 삭제 정책

### 7.1 유예 기간 후 물리 삭제

```
기록에서 사진 제거
   ↓
어떤 기록에서도 참조되지 않음 → 고아 상태 (orphaned_at 기록)
   ↓  30일
정리 배치가 스토리지 원본 + 파생물 + DB 행을 삭제
```

유예 기간 동안은 "최근 삭제된 항목"에서 복구할 수 있고, "지금 완전히 삭제" 버튼으로 즉시 지울 수도 있다.

**원본을 영구 보관하지 않는다.** 사용자가 지웠는데 서버에 영원히 남아 있으면 "지웠다"는 말이 거짓이 된다.

### 7.2 참조 카운트가 아니라 스윕

```sql
-- 고아 판정 (주기적 실행)
UPDATE media_assets
   SET orphaned_at = now(), purge_after = now() + interval '30 days'
 WHERE orphaned_at IS NULL
   AND id NOT IN (SELECT asset_id FROM record_block_media);

-- 다시 사용되면 되살리기
UPDATE media_assets
   SET orphaned_at = NULL, purge_after = NULL
 WHERE orphaned_at IS NOT NULL
   AND id IN (SELECT asset_id FROM record_block_media);

-- 물리 삭제 대상
SELECT * FROM media_assets
 WHERE purge_after IS NOT NULL AND purge_after <= now();
```

참조 카운트 컬럼을 두고 증감시키는 방식은 쓰지 않는다. 동시에 여러 곳에서 넣고 빼면 카운트가 어긋나고, 한번 어긋나면 고칠 방법이 없다. **주기적으로 실제 참조를 세는 스윕은 느리지만 자가 치유된다.**

### 7.3 CAS의 함정

내용 해시로 저장하므로 **같은 사진을 두 기록이 쓰면 물리 파일은 하나인데 참조는 여럿**이다. 기록 A에서 지웠다고 파일을 삭제하면 기록 B의 사진이 깨진다.

따라서 삭제 판정은 "사용자가 지웠나"가 아니라 **"참조가 0인가"** 여야 한다.

### 7.4 삭제 순서

**DB 먼저, 스토리지는 나중에.**

| 순서 | 중간 실패 시 결과 | 심각도 |
|---|---|---|
| 스토리지 → DB | DB엔 있는데 파일이 없음 = **깨진 참조** | 치명적, 복구 어려움 |
| DB → 스토리지 | 아무도 안 가리키는 **고아 파일** | 경미, 다음 스윕이 회수 |

**덜 위험한 실패 쪽으로 순서를 정한다**(원칙 P5). 별도로 "스토리지에는 있으나 DB에 없는 파일"을 찾아 지우는 잡을 무결성 감사와 함께 주기 실행한다.

---

## 8. 검증 대상 불변식

에이전트 자율 개선 루프의 채점표이자 CI 게이트.

| # | 불변식 | 검증 방법 |
|---|---|---|
| I1 | 원본 바이트는 어떤 처리 후에도 불변 | 저장 후 `content_hash` 재계산 비교 |
| I2 | 같은 내용의 파일은 물리적으로 하나만 존재 | `(owner_id, content_hash)` 유니크 + 스토리지 키 대조 |
| I3 | 같은 원본 + 같은 레시피 버전 → 항상 같은 파생물 바이트 | 파생물 삭제 후 재생성, `content_hash` 비교 |
| I4 | 업로드 중단 후 재개 결과 = 무중단 업로드 결과 | 임의 지점에서 워커/연결 강제 종료 후 재개, 최종 해시 비교 |
| I5 | 블록 순서키는 record 내에서 유일하며 정렬이 보존된다 | 임의의 삽입·이동·삭제 시퀀스에 대한 속성 기반 테스트 |
| I6 | `record_days`는 블록에서 재생성해도 동일 | 전체 삭제 후 재구축, 결과 비교 |
| I7 | 참조가 1 이상인 자산은 절대 물리 삭제되지 않는다 | 스윕 실행 후 `record_block_media` 전체 참조 유효성 검사 |
| I8 | AI 제안 결과는 스키마를 지키며 사진에 없는 장소명을 만들지 않는다 | 골든셋 대비 회귀 |

### 8.1 골든 데이터셋

실제 아카이브에서 나온 지저분한 파일들로 구성한다.

- 손상된 JPEG
- EXIF가 없는 파일
- 시간대 정보가 없는 파일
- 회전 플래그가 붙은 이미지
- HEIC
- 4GB 영상
- 파일명에 이모지가 들어간 파일
- 같은 순간의 연사 20장

### 8.2 카오스 주입

- 처리 중 워커 `SIGKILL`
- 스토리지 쓰기 실패
- 업로드 중 연결 단절
- 디스크 풀

---

## 9. MVP 범위

### 9.1 포함

- [ ] 소셜 로그인 1종
- [ ] 기록 생성 / 수정 / 발행, 3초 디바운스 자동저장
- [ ] 재개 가능한 청크 업로드 (해시 선조회 중복 제거 포함)
- [ ] 미디어 처리 파이프라인 (PROBE / THUMB / DERIVE, 레인 분리)
- [ ] 장면 분할 → 블록 자동 생성
- [ ] 블록 편집 (추가·삭제·이동·갤러리 분할/병합·레이아웃 전환)
- [ ] 5종 블록 타입
- [ ] 타임라인 조회
- [ ] 공개/비공개 플래그
- [ ] 유예 삭제 + 정리 배치
- [ ] AI 제안 (뼈대 + 질문)

### 9.2 제외 — 그리고 그 이유

| 항목 | 이유 |
|---|---|
| 자체 회원가입 / 비밀번호 재설정 | 최소 1주 소요, 포트폴리오 가치 없음 |
| 리치 WYSIWYG 에디터 | 프론트에 시간이 잠식됨. 블록 리스트 + 위/아래 버튼으로 충분 |
| 댓글 / 팔로우 / 좋아요 | 이 서비스가 말하려는 것과 무관 |
| 지도 렌더링 | 장소 카드에 지도 타일이 필요 없음 |
| pHash 유사도 인덱싱 (BK-tree/LSH) | 촬영본 대부분을 사용하므로 추려낼 것이 없음 |
| Redis | 도입 조건이 충족되지 않음 (3.1 참조) |
| Kafka / RabbitMQ | 같음 |

### 9.3 2차 이후

- 네이버 지역검색 API 연동
- 공유 링크 발급 (서명된 URL, 만료 처리, CDN 캐시와 권한의 충돌 해결)
- 연사 중복 감지 (pHash)
- AI 제안 문체 개인화
- 파생물 레시피 v2 백필 (무중단 재처리 실증)

---

## 10. 패키지 구조

백엔드와 프론트엔드를 한 저장소에 두되 최상위 디렉토리로 나눈다. 개인 프로젝트라
저장소를 쪼갤 이유가 없고, API 변경과 클라이언트 변경이 한 커밋에 묶이는 편이 낫다.

```
archive/
├── archive-backend/     # Spring Boot
├── archive-frontend/    # React + Vite
├── docs/
├── golden/              # 골든셋 (media/ 는 커밋 안 함)
├── docker-compose.yml   # 서버에서 실행하는 상시 스택
├── settings.gradle      # include 'archive-backend'
└── gradlew              # 진입점은 루트에 둔다 (W4: ./gradlew verify 하나로 전부)
```

Gradle wrapper 를 `archive-backend/` 안이 아니라 루트에 둔다. 단일 모듈 멀티프로젝트의
관례이고, 루트에서 `./gradlew :archive-backend:test` 로 바로 실행된다.

> **W4 의 "`./gradlew verify` 하나로 전부 검증"이 프론트까지 포함하는지는 아직 미정이다.**
> Gradle 은 JVM 빌드 도구라 프론트를 자동으로 검증하지 못한다. 배선이 필요하고, 선택지는 셋이다.
>
> | 방법 | 장점 | 걸리는 점 |
> |---|---|---|
> | `com.github.node-gradle.node` 플러그인 | Gradle 이 Node 를 직접 관리해 재현성이 높다 | 최신이 7.1.0(2024-09). Gradle 9 지원 명시 없음 |
> | 순수 `Exec` 태스크로 npm 호출 | 플러그인 의존 0 | Node 가 머신에 있어야 하고 버전 고정은 별도 수단 |
> | Gradle 대신 루트 `verify` 스크립트 | 가장 단순. 두 도구를 대등하게 다룸 | Gradle 태스크 그래프의 캐싱·증분 이점을 못 씀 |
>
> **W4 에서 정한다.** 프론트에 어떤 검증(vitest, `tsc --noEmit`, eslint, e2e)이 실제로
> 생길지 모르는 상태에서 미리 고르면 안 쓰는 배선을 만들게 된다. wrapper 위치는 어느
> 쪽을 택해도 손해가 없으므로 지금 결정할 필요가 없다.

### 백엔드 패키지

```
dev.jinyoung.archive
├── auth           # 소셜 로그인, 세션
├── media          # 자산, 해시, 스토리지 추상화
├── upload         # 세션, 청크, 재개, 중복 감지
├── processing     # 큐, 워커, stage별 프로세서
│   ├── probe
│   ├── thumb
│   └── derive
├── scene          # 장면 분할 → 블록 생성
├── record         # 기록, 블록, 순서키, 낙관적 락
├── timeline       # 날짜 기반 조회
├── suggestion     # AI 제안 (외부 의존성 격리)
├── retention      # 고아 스윕, 유예 삭제, 무결성 감사
└── common         # 스토리지 클라이언트, Clock, 해시 유틸
```

### 프론트엔드

```
archive-frontend/
├── src/
│   ├── api/       # 서버 호출, SSE 구독, 폴링 폴백
│   ├── upload/    # Web Worker 해싱, 청크 전송, 재개
│   ├── record/    # 블록 에디터
│   └── ui/        # 화면 조각
└── vite.config.ts # 개발 중에는 /api 를 :8080 으로 프록시
```

---

## 11. 결정 기록 (ADR 초안)

| # | 결정 | 이유 | 대안과 기각 사유 |
|---|---|---|---|
| A1 | 작업 큐를 PostgreSQL로 구현 | 도메인 변경과 작업 등록의 트랜잭션 일관성 | Kafka — 아웃박스 패턴이 추가로 필요, 규모 대비 과잉 |
| A2 | 사진/영상 처리 레인 분리 | 헤드 오브 라인 블로킹 회피 | 단일 큐 — 영상 1건이 사진 70장을 막음 |
| A3 | 블록 순서키를 fractional index로 | 중간 삽입이 INSERT 1회 | 정수 index — 삽입 시 뒤쪽 전체 UPDATE, 동시 편집에 취약 |
| A4 | 미디어를 내용 해시로 식별 | 중복 제거 + 무결성 + 재업로드 시 전송 생략 | UUID — 중복을 감지할 수 없음 |
| A5 | 미디어 참조를 별도 테이블로 | 역추적 인덱스, 참조 무결성, 삭제 판정 근거 | payload 내 배열 — 인덱스 불가, 깨진 참조 발생 |
| A6 | 파생물에 레시피 버전 부여 | 무중단 백필과 롤백 | 버전 없음 — 전체 재생성 중 화면이 깨지고 롤백 불가 |
| A7 | 삭제를 참조 스윕으로 판정 | 카운터 어긋남 방지, 자가 치유 | 참조 카운트 컬럼 — 동시성으로 어긋나면 복구 불가 |
| A8 | DB 삭제 후 스토리지 삭제 | 깨진 참조보다 고아 파일이 덜 위험 | 역순 — 중간 실패 시 복구 어려운 깨진 참조 발생 |
| A9 | `GALLERY` 단일 타입으로 통합 | 레이아웃 전환이 payload 1필드 수정 | PHOTO/GALLERY 분리 — 전환 시 블록 분해·재구성 필요 |
| A10 | 장면 override 테이블 미도입 | 분할은 1회성 계산, 이후 조정은 일반 블록 편집 | override 테이블 — 재계산이 없으므로 존재 이유 없음 |
| A11 | AI를 잎사귀로 격리 | 확률적으로 실패하는 외부 의존성을 핵심 경로에서 제외 | 핵심 경로 통합 — 장애 시 서비스 전체 중단 |
| A12 | 영속성을 Spring Data JDBC로 | 지연로딩·더티체킹이 없어 발생 SQL이 예측 가능. 불변식 테스트와 에이전트 루프의 전제인 결정론에 직결 | JPA — 큐·순서키는 결국 native query, 영속성 컨텍스트가 멱등성 테스트를 가림 |
| A13 | 스키마를 Flyway 순수 SQL로 | design.md의 DDL을 번역 없이 옮겨 문서와 코드의 표류를 막음 | Liquibase — 단일 DB에서 추상화 이점 없음 / `ddl-auto` — 스키마가 버전 관리되지 않음 |
| A14 | Docker 데몬을 원격 서버에 둠 | 개발 노트북에 Docker를 두지 않는 환경 제약 | 로컬 데몬 — 선택지가 아니었음. 대가는 바인드 마운트 불가와 테스트 지연 ([infra.md](./infra.md) §5) |
| A15 | Spring Boot 4 로 시작 | Boot 3.x는 2026-06-30 자로 전 브랜치 OSS 지원 종료. 신규 프로젝트라 3→4 마이그레이션 비용(breaking change 115건)이 발생하지 않음 | Boot 3.5 — 예제·레퍼런스는 많지만 보안 패치가 끊긴 상태로 시작하게 됨 |
| A16 | 프론트를 React + Vite + TS, 서버 상태는 TanStack Query | 이 클라이언트의 난점은 렌더링이 아니라 **SSE·폴링 두 경로가 같은 서버 상태를 갱신**하는 것(§7.3 "화면의 근거는 DB 조회"). 무효화·재조회·폴링 주기가 라이브러리의 기본 기능 | 바닐라 TS — W3 에디터에서 상태 관리를 직접 만들게 되어 재작성 / Svelte — 번들은 가볍지만 SSE+캐시+Worker 조합의 참고 자료가 적어 일정 리스크(§0)를 키움 |

---

## 12. 미결 사항

- [ ] 청크 크기 결정 (5MB? 8MB?) — 모바일 네트워크 실측 후 결정
- [ ] 장면 분할 임계값의 초기값 검증 — 실제 사진으로 돌려보고 조정
- [ ] `beforeunload` 경고 문구
- [ ] AI 제안의 프롬프트 설계 및 골든셋 구성
- [ ] MinIO CORS 설정 (브라우저 직접 업로드용)
- [ ] iOS Safari의 HEIC → JPEG 자동 변환 동작 실측

---

## 13. 부록 — HEIC 대응

| 상황 | 처리 |
|---|---|
| iOS에서 파일 선택 시 JPEG로 변환되어 옴 (대다수) | `URL.createObjectURL(file)`로 **즉시** 미리보기 |
| HEIC 원본이 그대로 옴 | 파일명 + 회색 플레이스홀더 → 서버 `THUMB` 완료 시 SSE로 교체 (수 초) |

클라이언트 측 WASM 디코딩(`heic2any` 등)은 쓰지 않는다. 번들이 수 MB이고 장당 변환에 1초 가까이 걸려, 70장이면 브라우저가 멈춘다. 서버가 훨씬 빠르다.

`THUMB`을 독립 stage로 승격한 이유가 여기에 있다. 무거운 `DERIVE`를 기다리지 않고 썸네일만 먼저 내보내 화면을 채운다.
