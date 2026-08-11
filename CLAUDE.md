# 개인 라이프 아카이브

사진으로 하루의 기억을 기록하고, 시간이 지나도 그날의 흐름을 다시 떠올리게 돕는 개인 기록 서비스.
**6년차 백엔드 개발자의 취업 포트폴리오이자 본인이 실제로 쓸 서비스** — 둘 다 목표.
성공 기준은 면접에서 "그거 어떻게 했어요?"라는 되물음을 이끌어내는 것. 단순 CRUD가 아니라
**판단의 이유를 설명할 수 있는** 프로젝트여야 함.

## 문서

문서 하나가 만 단위 토큰이다. **작업 전 관련 절만 읽기** — 통째로 읽으면 일을 시작하기도 전에 컨텍스트가 참.

| 문서 | 절 | 언제 |
|---|---|---|
| [progress.md](docs/progress.md) | 짧음, 전체 | **세션 시작 시 항상** — 다음 작업, 실행 환경, 나머지 위치 |
| [schema.md](docs/schema.md) | §5.2 `users` ~ §5.11 `suggestions` | 스키마 작업 전, 해당 테이블 절만 |
| [design.md](docs/design.md) | §1~4 정체성·원칙·구성, §6 장면 분할, §7 삭제 정책, §8 불변식, §9 MVP, §10 패키지, §11 ADR | 도메인 판단 전 |
| [pipeline.md](docs/pipeline.md) | §1~4 프로토콜·API·상태 머신, §5 워커, §6 멱등성, §7 SSE, §8 블록 자동 생성, §9~10 실패·동시성 | 업로드/처리 작업 전 |
| [roadmap.md](docs/roadmap.md) | §2 주차별 계획, §4 불변식 명세, §5 하네스 스택, §6 에이전트 루프 | 무엇을 할지 정할 때 |
| [infra.md](docs/infra.md) | 실행 환경, 서버 스택, 원격 Docker 데몬 | 컨테이너·테스트 환경 작업 전 |
| [journal/sessions.md](docs/journal/sessions.md) | 지난 세션 기록, 잰 값 | **읽지 말고 추가만** — 작업 단위가 끝날 때 |
| [interview-notes.md](docs/interview-notes.md) | 기술 판단의 면접용 정리 | **읽지 말고 추가만** — 새 판단이 나올 때 |

## 절대 원칙

1. **AI는 문장을 대신 쓰지 않기.** 뼈대와 질문만. 사용자가 이 기록을 좋아하는 이유는 "사진을 보며 떠올리는 시간"이고, 완성된 글을 주면 그 시간이 사라짐
2. **하네스가 기능보다 먼저.** `Clock` 주입·Testcontainers는 첫날 항목. 나중에 붙이면 코드가 이미 테스트 불가능한 모양
3. **기능을 줄이고 깊이를 지키기.** 얕은 기능 10개보다 검증된 5개
4. **결정에는 이유를 남기기.** 새 기술 판단은 design.md ADR 표와 interview-notes.md에 함께 추가
5. **AI는 잎사귀로.** `suggestion` 패키지 밖에서 LLM이라는 단어가 등장하면 안 됨

## 스택

Java 21 / Spring Boot 4 / PostgreSQL / MinIO(S3 호환) / libvips / FFmpeg.
영속성은 Spring Data JDBC(JPA 아님), 스키마는 Flyway 순수 SQL — ADR A12·A13·A15.
클라이언트는 React + TypeScript + Vite(모바일 브라우저 우선), 서버 상태는 TanStack Query.

구조는 `archive-backend/`, `archive-frontend/`, `docs/`. Gradle wrapper 와 `settings.gradle` 은
**루트**에 있음 — `./gradlew :archive-backend:test` 처럼 루트에서 실행.

**의도적으로 안 쓰는 것**: Kafka, RabbitMQ, Redis (design.md §3.1, ADR A1).
다시 쓰자고 제안하려면 문서에 적힌 도입 조건이 충족됐는지 먼저 확인.

## 실행 환경 — 로컬에 Docker 데몬 없음

- `docker` / `docker compose` / `colima` 를 **로컬에서 실행하지 않음.** 명령과 파일만 제시하고 사람이 서버에서 실행
- Postgres·MinIO 상시 스택은 서버의 `docker compose`. 로컬에서 띄우려 하지 말 것
- Testcontainers 는 `DOCKER_HOST` 로 서버 데몬을 원격 조작 → **바인드 마운트 불가**, `withCopyFileToContainer` 사용
- 나머지 제약은 [infra.md](docs/infra.md)

## 코딩 규칙

- `Instant.now()` / `LocalDateTime.now()` 직접 호출 금지 → 주입된 `Clock`
- `Thread.sleep()` 대신 Awaitility
- 랜덤(백오프 지터, UUID)은 시드/생성기를 주입 가능하게
- 모든 처리 stage 는 멱등. 중복 실행은 반드시 일어난다고 전제
- 파생물 `storage_key` 는 내용 해시 기반으로 결정론적 생성

## 작업 규칙

- 테스트 없이 다음 단계로 넘어가지 않기
- 불변식(roadmap.md §4)을 깨는 변경은 하지 않기. 불변식 자체를 바꿔야 하면 이유를 문서에 남기기
- 설계를 바꾸면 **해당 문서를 같은 커밋에서** 갱신. 어긋나는 순간 문서가 쓸모없어짐
- 작업 단위가 끝나면 나눠서 적기 — 다음 할 일·환경은 `progress.md`, 무엇을 했고 알게 됐는지는
  `journal/sessions.md`. progress.md 는 매 세션 읽히므로 짧게 유지하고, 표나 목록을 새로 만들기 전에
  원본 문서가 있는지 확인해 있으면 포인터만 남기기
- **판단은 progress.md 에 쌓지 않기.** 정본은 design.md ADR 표·interview-notes.md·코드 주석 중 맞는 곳

## Git

- 작업 브랜치는 **`dev`**
- **커밋 메시지에 `Co-Authored-By: Claude ...`, `Generated with ...` 등 AI 트레일러 금지.** 도구가 자동으로 붙여도 빼기. 이 규칙이 도구 기본값보다 우선
- **골든셋의 실제 사진·영상 파일은 절대 커밋하지 않기** — 공개 저장소에 지인 얼굴이 올라감. 해시와 기대값(`golden/manifest.json`)만 커밋. roadmap.md §5

## 언어

설명·질문·작업 진행 상황은 한국어. 코드·명령어·파일명·클래스명·변수명은 원래 형식 유지.
