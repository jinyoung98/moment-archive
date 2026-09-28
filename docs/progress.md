# 진행 상황

> 세션이 바뀌어도 이어갈 수 있도록 유지하는 상태 파일.
> **작업 단위가 끝날 때마다 갱신할 것.**
>
> **여기에는 세 가지만 둔다** — 다음에 뭘 하나 / 매번 필요한 환경 / 나머지는 어디에 있나.
> 매 세션 시작에 읽히는 파일이라 길이가 그대로 비용이다.
>
> 표·목록을 새로 만들고 싶어지면 원본 문서가 이미 있는지 먼저 볼 것. 불변식 명세는
> roadmap.md §4, 측정 항목은 §7, 미결 사항은 design.md §12 와 pipeline.md §11 에 있다.
> 새 판단이 나오면 여기가 아니라 **ADR·interview-notes·코드 주석**으로 보낸다.
> 세션 기록은 [journal/sessions.md](./journal/sessions.md) 에 쌓고 여기서는 읽지 않는다.

- **최종 갱신**: 2026-09-28
- **현재 단계**: **W2 진행 중** — ① 큐+워커 완료, 다음 ② 업로드 세션

---

## 지금 해야 할 일

> **다음 첫 작업: W2 ② 업로드 세션 (사진 경로).**
> `upload_sessions`/`upload_items` 마이그레이션 → 세션 생성 → 해시 보고 → 중복 세 갈래
> (READY 즉시 / 처리 중 대기 / FAILED 는 작업 재등록) → 단일 `PUT .../content` → PROBE 에서 해시
> 재계산해 `VERIFIED`. 프로토콜·상태 머신은 [pipeline.md](./pipeline.md) §2~4, 스키마는 schema.md §5.5.
> 도입 불변식 `I3`. 블록 자동 생성(§8)은 records 가 필요해 W3 — W2 세션은 "전 항목 종착"까지만.
>
> **W2 순서** (넘치면 ⑤의 트랜스코딩을 W4 로, roadmap 경고): ① 큐+워커 ✅ → ② 업로드 세션 →
> ③ SSE + 프론트 해싱(Web Worker)·진행률 → ④ 청크 멀티파트 + 재개(`I4`) → ⑤ 영상(ffprobe·포스터).
> ③까지 끝나면 "사진 50장"은 완결.
>
> **① 완료 (브라우저 확인 2026-09-28).** 업로드는 자산 행 + PROBE 작업만 한 트랜잭션에 넣고 즉시 응답(INGESTED), 처리는
> `JobWorker`(SKIP LOCKED, 잠금 토큰, 하트비트·lease 회수, 백오프+지터). 프론트는 `GET /media/{id}`
> 1초 폴링(SSE 전 임시). `I9` 테스트 고정. 판단은 ADR A24(`:now`+시계 오차 방어)·A25(같은 jar+설정)·
> A26(펜싱 토큰), pipeline.md §5.8, interview-notes §3.

### W1 수직 슬라이스 — 완료 (2026-09-23)

**브라우저에서 로그인 → 사진 1장 → 썸네일(`새 사진 · THUMBED`) 수동 확인.** 하네스·스토리지·
업로드·처리·인증·썸네일 서빙·프론트. 판단은 ADR A16~A23, 경과는 [journal/sessions.md](./journal/sessions.md).

### 실행 환경

새 셸마다 필요하다. 없으면 `Could not find a valid Docker environment` 로 실패한다.

```bash
export DOCKER_HOST=tcp://192.168.133.221:2375
export TESTCONTAINERS_HOST_OVERRIDE=192.168.133.221
```

상시 스택 Postgres 호스트 포트는 **5434**. 서버 셋업·제약은 [infra.md](./infra.md).

THUMB 를 로컬에서 실측하려면 libvips 필요 (`brew install vips` → `vips` 가 PATH 에).
없으면 앱은 그대로 뜨고 THUMB 만 건너뜀(PROBED 유지), 관련 테스트는 스킵. 실행 파일 경로는
`archive.processing.thumbnail.command` 로 덮어쓸 수 있음.

**앱을 실제로 띄우려면 구글 OAuth 자격이 필요하다** (소셜 로그인이라 자격 없이는 기동 실패).
Google Cloud Console → OAuth 클라이언트 ID(웹) 발급, 리다이렉트 URI 에
`http://localhost:8080/login/oauth2/code/google` 등록. 값은 커밋 안 하는
`application-local.yml`(또는 환경변수)에:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: <발급값>
            client-secret: <발급값>
archive:
  auth:
    allowed-emails: dev.jinyoung98@gmail.com   # 여러 명이면 쉼표로. 비우면 전체 허용(경고)
```

테스트는 더미 자격을 주입하므로(IntegrationTest) 이 설정 없이도 돈다. (자격을 env 로 줄 거면
`application.yml` 의 `${GOOGLE_CLIENT_ID:}` 등이 그대로 받으므로 `application-local.yml` 없이
`~/.zshenv`·direnv `.envrc` 만으로도 됨.)

`.envrc` 의 `S3_ACCESS_KEY`/`S3_SECRET_KEY` 는 **서버 `.env` 의 `MINIO_ROOT_USER`/`PASSWORD` 와 같아야**
한다 — 틀리면 업로드가 `HeadObject` 403 → 500 (테스트는 자체 MinIO 라 통과해 버려서 못 잡음).

`./gradlew :archive-backend:bootRun` 은 테스트와 달리 **상시 스택(Postgres 5434·MinIO 9000)에
노트북이 직접 닿아야** 한다 — 서버 `.env` 의 `BIND_HOST=<사설 IP>` 로 열고(infra.md §3.1),
`nc -z 192.168.133.221 5434` 로 확인. THUMB 실측엔 libvips 도 필요.
bootRun 은 워커 루프까지 한 프로세스에서 돈다(기본). 끄려면 `ARCHIVE_WORKER_ENABLED=false` — 그러면
업로드는 INGESTED 에서 멈춤. 워커는 기동 시 앱-DB 시계 오차 30초 초과면 뜨지 않음 (ADR A24).

**브라우저 E2E 절차** (로컬에서 앱 전체 띄우기):

1. Google Console OAuth 클라이언트에 리다이렉트 URI **`http://localhost:5173/login/oauth2/code/google`** 추가 (프록시 단일 오리진, ADR A23)
2. 백엔드: `./gradlew :archive-backend:bootRun` (상시 스택 도달 + 구글 자격 필요)
3. 프론트: `cd archive-frontend && npm install && npm run dev` → `http://localhost:5173`
4. 브라우저에서 "구글로 로그인" → 사진 1장 선택 → 썸네일 + `새 사진 · THUMBED`

프론트 자체 검증(네트워크 불필요): `cd archive-frontend && npm run build` (tsc --noEmit + vite build).

---

## 나머지는 어디에 있나

이 파일에 두지 않는 것들. **여기서 복사해 오지 말 것** — 원본이 둘이 되는 순간 어느 쪽이
최신인지 알 수 없어진다.

| 찾는 것 | 어디에 |
|---|---|
| 불변식 명세 (I1~I10) | [roadmap.md](./roadmap.md) §4 · 주차 배정은 §2 |
| 주차별 계획과 완료 기준 | [roadmap.md](./roadmap.md) §2 · 체크포인트 질문은 §8 |
| 측정할 항목 / 잰 값 | [roadmap.md](./roadmap.md) §7 / [journal/sessions.md](./journal/sessions.md) |
| 미결 사항 (청크 크기, 장면 분할 임계값, HEIC …) | [design.md](./design.md) §12 · [pipeline.md](./pipeline.md) §11 |
| 블록 자동 생성 ↔ 자동저장 충돌 | [pipeline.md](./pipeline.md) §8.3 |
| 프론트 검증을 `./gradlew verify` 에 묶을 것인가 | [design.md](./design.md) §10 — W4 에서 결정 |
| 지난 세션에 무엇을 했나 | [journal/sessions.md](./journal/sessions.md) · `git log` |
