# 진행 상황

> 세션이 바뀌어도 이어갈 수 있도록 유지하는 상태 파일.
> **작업 단위가 끝날 때마다 갱신할 것.**

- **최종 갱신**: 2026-08-06
- **현재 단계**: 설계 완료 / W1 착수 전

---

## 지금 해야 할 일

**W1 시작 — 프로젝트 스캐폴딩**

1. ~~`git init`~~ ✅
2. ~~`docker-compose.yml` — PostgreSQL + MinIO~~ ✅ 작성 완료. **서버에서 실행 대기 중**
3. ~~Gradle + Spring Boot 4 프로젝트 생성~~ ✅ `./gradlew compileTestJava` 통과 (경고 0)
4. ~~Testcontainers 골격 + `Clock` 빈 주입~~ ✅ 작성 완료. **실행 검증 대기 중**
5. 통과하는 통합 테스트 1개 (컨테이너 위에서) ← **서버 셋업이 있어야 진행 가능**

상세는 [roadmap.md](./roadmap.md) §2 W1 참조.

> ⏸ **막힌 지점**: `./gradlew test` 가 `Could not find a valid Docker environment` 에서 멈춘다.
> 코드·빌드 문제가 아니라 Docker 데몬이 아직 없기 때문이다. 컴파일은 경고 없이 통과한다.
>
> **사람이 해야 할 일** — [infra.md](./infra.md) §6 체크리스트.
> 서버에서 compose 기동, Twingate Resource 등록(포트 제한 없이), dockerd 사설 IP 바인딩.
> 그다음 로컬에 `DOCKER_HOST` 와 `TESTCONTAINERS_HOST_OVERRIDE` 를 설정한다.
>
> 그때까지 "테스트가 통과함"은 주장하지 않는다.

---

## 단계별 상태

| 단계 | 상태 | 완료 기준 |
|---|---|---|
| 설계 | ✅ 완료 | 데이터 모델·파이프라인·로드맵 문서화 |
| W1 뼈대 | ⬜ 미착수 | 브라우저에서 사진 1장 → 썸네일 표시 |
| W2 업로드·파이프라인 | ⬜ 미착수 | 50장+영상 3개 업로드, 중단 후 재개 |
| W3 기록·블록 | ⬜ 미착수 | 기록 하나를 처음부터 끝까지 작성 |
| W4 하네스 | ⬜ 미착수 | `./gradlew verify` 하나로 전부 검증 |
| W5 에이전트 루프 | ⬜ 미착수 | 포트폴리오 제출 가능 |

## 불변식 도입 현황

| # | 불변식 | 상태 |
|---|---|---|
| I1 | 원본 바이트 불변 | ⬜ W1 |
| I2 | 중복 저장 없음 | ⬜ W1 |
| I3 | 파생물 결정론 | ⬜ W2 |
| I4 | 재개 동등성 | ⬜ W2 |
| I9 | stage 멱등성 | ⬜ W2 |
| I5 | 순서키 속성 | ⬜ W3 |
| I6 | `record_days` 재생성 | ⬜ W3 |
| I10 | 세션 완료 판정 유일성 | ⬜ W3 |
| I7 | 참조 있으면 삭제 안 됨 | ⬜ W4 |
| I8 | AI 제안 골든셋 | ⬜ W5 |

---

## 측정 기록

> 구현하며 채울 것. **숫자 없는 포트폴리오는 절반짜리다.**

| 항목 | 값 | 측정일 |
|---|---|---|
| 사진 70장 → 전부 READY | — | |
| 첫 썸네일 표시까지 | — | |
| 레인 분리 전 (사진 전부 표시) | — | |
| 레인 분리 후 (사진 전부 표시) | — | |
| 재개 시 절약된 전송량 | — | |
| 45초 4K 영상 트랜스코딩 | — | |
| 원본 대비 파생물 용량 비율 | — | |
| 에이전트 평균 수렴 횟수 | — | |

---

## 결정 대기 중

실측이 필요해 아직 정하지 못한 것들. 구현하며 채운다.

- [ ] 청크 크기 (5MB가 모바일 네트워크에서 적절한가)
- [ ] 동시 업로드 개수 (3개 가정)
- [ ] 해싱 Worker 개수 (2개 가정)
- [ ] lease 만료 2분 / 하트비트 30초의 적정성
- [ ] 장면 분할 임계값 (20분 가정) — 실제 사진으로 검증 필요
- [ ] iOS Safari의 HEIC → JPEG 자동 변환 실제 동작
- [ ] 네이버 지역검색 API 현재 요금 정책 (2차 도입 전 확인)

## 미해결 설계 이슈

- **블록 자동 생성과 자동저장 충돌** ([pipeline.md](./pipeline.md) §8.3)
  업로드를 걸어두고 다른 블록에 글을 쓰는 것이 자연스러운 패턴이라 회피 불가.
  현재 방침: `session.blocks_created` 수신 시 조용히 재로드하되 타이핑 중인 텍스트는 로컬 값 유지.
  실사용하며 다듬을 것.

---

## 세션 기록

작업한 내용을 짧게 남긴다. 무엇을 왜 했는지가 다음 세션의 맥락이 된다.

### 2026-08-06 — W1 착수 (검증 전)
실행 환경을 확정하고 프로젝트 뼈대를 작성했다. **아직 빌드·테스트를 한 번도 돌리지 못했다.**

- 로컬에 Docker 데몬이 없는 환경으로 확정 → [infra.md](./infra.md) 신설. 상시 스택은 서버 compose, Testcontainers는 `DOCKER_HOST`로 원격 데몬. 접속은 **Twingate**
- 문서에 없던 결정 2개를 확정하고 ADR A12·A13 추가 — 영속성은 **Spring Data JDBC**, 마이그레이션은 **Flyway 순수 SQL**. 원격 데몬 제약은 A14
- **Spring Boot 4.1** 로 정정(A15). 3.5가 2026-06-30 OSS EOL 이라 3.x 로 시작할 이유가 없어졌다. 스타터 이름이 바뀐 것에 주의 — `-web` → `-webmvc`, Flyway 는 `spring-boot-starter-flyway` 필요
- `build.gradle`, `V1__baseline.sql`(users·media_assets·media_derivatives), `ClockConfig`, `IntegrationTest` 기반 클래스, `HarnessIntegrationTest` 작성

**빌드 통과까지 걸린 것들** — 버전을 올린 대가. 추정으로 적은 좌표가 대부분 틀렸다.

| 추정 | 실제 |
|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `flyway-core` 직접 선언 | `spring-boot-starter-flyway` 필요 |
| `spring-boot-starter-test` 하나 | 기술별로 분리 — `-webmvc-test`, `-data-jdbc-test`, `-flyway-test` |
| Boot BOM 이 Testcontainers 관리 | 관리 안 함. 버전 직접 지정 |
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` (2.x 부터 접두사). 옛 좌표는 1.21.4 에서 멈춤 |
| `org.testcontainers.containers.PostgreSQLContainer` | `org.testcontainers.postgresql.PostgreSQLContainer` (구 좌표는 deprecated, 제네릭 파라미터 없어짐) |

`-Xlint:deprecation`·`-Xlint:unchecked` 를 빌드에 상시 켰다. 방금처럼 조용한 deprecated 사용을 놓치지 않기 위해서다.

**남은 것**: `./gradlew test` 는 Docker 데몬이 없어 실패한다. 서버 셋업 후 재검증.

### 2026-08-06 — 설계
주제 선정부터 전체 설계까지. 데이터 모델 확정, 업로드·처리 파이프라인 설계, 5주 로드맵과 불변식 명세 작성.

설계 과정에서 제거된 것: 그룹 관련 테이블 3개(촬영본 대부분을 사용하므로 "추려내기" 불필요), `scene_overrides`(장면 분할이 1회성 계산이라 override 보존 불필요), `PHOTO`/`VIDEO` 블록 타입(`GALLERY`로 통합), `quality_score` 컬럼.
