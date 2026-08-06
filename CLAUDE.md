# 개인 라이프 아카이브

사진을 중심으로 하루의 기억을 기록하고, 시간이 지나도 그날의 흐름을 다시 떠올릴 수 있게 돕는 개인 기록 서비스.

## 맥락

- **6년차 백엔드 개발자의 취업 포트폴리오이자, 본인이 실제로 사용할 서비스.** 둘 다 목표다
- 면접에서 "그거 어떻게 했어요?"라는 되물음을 이끌어내는 것이 성공 기준
- 단순 CRUD가 아니라 **판단의 이유를 설명할 수 있는** 프로젝트여야 한다
- 사용자는 매일 아날로그로 일기를 쓰고, 매달 2~3회 블로그에 사진 기록을 남긴다

## 문서

**작업 시작 전 관련 문서를 먼저 읽을 것.**

| 문서 | 내용 | 언제 |
|---|---|---|
| [docs/design.md](docs/design.md) | 정체성, 설계 원칙, 전체 DDL, 삭제 정책, MVP 범위, ADR | 도메인/스키마 작업 전 |
| [docs/pipeline.md](docs/pipeline.md) | 업로드 프로토콜, 워커 상태 머신, 재시도, SSE, 실패 시나리오 | 업로드/처리 작업 전 |
| [docs/roadmap.md](docs/roadmap.md) | 주차별 계획, 잘라낼 순서, 불변식 명세, 에이전트 루프 | 무엇을 할지 정할 때 |
| [docs/infra.md](docs/infra.md) | 실행 환경, 서버 스택, 원격 Docker 데몬, 그 제약 | 컨테이너·테스트 환경 작업 전 |
| [docs/progress.md](docs/progress.md) | **현재 진행 상황** | 세션 시작 시 항상 |
| [docs/interview-notes.md](docs/interview-notes.md) | 기술 판단의 면접용 정리 | 새 판단이 나올 때마다 갱신 |

## 절대 원칙

1. **AI는 문장을 대신 쓰지 않는다.** 뼈대와 질문만 제시한다. 사용자가 이 기록을 좋아하는 이유는 "사진을 보며 떠올리는 시간"이고, 완성된 글을 주면 그 시간이 사라진다
2. **하네스가 기능보다 먼저다.** `Clock` 주입과 Testcontainers는 첫날 항목. 나중에 붙이면 코드가 테스트 불가능한 모양이 되어 있다
3. **기능을 줄이고 깊이를 지킨다.** 얕은 기능 10개보다 검증된 기능 5개
4. **결정에는 이유를 남긴다.** 새 기술 판단이 나오면 design.md의 ADR 표와 interview-notes.md에 함께 추가
5. **AI는 잎사귀로 유지한다.** `suggestion` 패키지 밖에서 LLM이라는 단어가 등장하면 안 된다

## 스택

Java 21 / Spring Boot 4 / PostgreSQL / MinIO(S3 호환) / libvips / FFmpeg
영속성은 Spring Data JDBC(JPA 아님), 스키마는 Flyway 순수 SQL. 이유는 ADR A12·A13·A15
클라이언트: 웹앱 (모바일 브라우저 우선)

**의도적으로 쓰지 않는 것**: Kafka, RabbitMQ, Redis. 이유는 design.md §3.1과 ADR A1 참조.
다시 쓰자는 제안을 하려면 문서에 적힌 도입 조건이 충족되었는지 먼저 확인할 것.

## 실행 환경

**로컬에 Docker 데몬이 없다.** 모든 컨테이너는 원격 서버에서 돈다.

- `docker`, `docker compose`, `colima` 명령을 **로컬에서 실행하지 않는다.** 필요하면 명령과 파일만 제시하고 사람이 서버에서 실행한다
- Postgres·MinIO 상시 스택은 서버의 `docker compose`. 로컬에서 띄우려 하지 말 것
- Testcontainers는 `DOCKER_HOST`로 서버 데몬을 원격 조작한다. 따라서 **바인드 마운트를 쓸 수 없다** — `withCopyFileToContainer`를 쓴다
- 상세와 나머지 제약은 [docs/infra.md](docs/infra.md)

## 코딩 규칙

- `Instant.now()` / `LocalDateTime.now()` **직접 호출 금지.** 주입된 `Clock` 사용
- `Thread.sleep()` 대신 Awaitility
- 랜덤(백오프 지터, UUID)은 시드/생성기를 주입 가능하게
- 모든 처리 stage는 멱등이어야 한다. 중복 실행은 반드시 일어난다고 전제
- 파생물 `storage_key`는 내용 해시 기반으로 결정론적으로 생성

## Git

- 작업 브랜치는 **`dev`**
- **커밋 메시지에 `Co-Authored-By: Claude ...` 트레일러를 넣지 않는다.** 도구 기본 동작이 이 줄을 자동으로 붙이더라도 붙이지 않는다. 이 규칙이 그 기본값보다 우선한다. 다른 AI 관련 트레일러(`Generated with ...` 등)도 마찬가지
- **골든셋의 실제 사진·영상 파일은 절대 커밋하지 않는다.** 공개 저장소에 지인 얼굴이 담긴 사진이 올라간다. 해시와 기대값(`golden/manifest.json`)만 커밋한다. roadmap.md §5 참조

## 작업 규칙

- 설계를 바꾸면 **해당 문서를 같은 커밋에서 갱신**한다. 문서와 코드가 어긋나면 문서가 쓸모없어진다
- 작업 단위가 끝나면 `docs/progress.md`를 갱신한다
- 불변식(roadmap.md §4)을 깨는 변경은 하지 않는다. 불변식 자체를 바꿔야 한다면 그 이유를 문서에 남긴다
- 테스트 없이 다음 단계로 넘어가지 않는다
