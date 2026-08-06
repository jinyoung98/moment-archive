# 진행 상황

> 세션이 바뀌어도 이어갈 수 있도록 유지하는 상태 파일.
> **작업 단위가 끝날 때마다 갱신할 것.**

- **최종 갱신**: 2026-08-06
- **현재 단계**: W1 진행 중 — 하네스 완료(테스트 4건 통과), 수직 슬라이스 착수 전

---

## 지금 해야 할 일

**W1 시작 — 프로젝트 스캐폴딩**

**W1 스캐폴딩 완료** — 하네스가 컨테이너 위에서 실제로 돈다.

1. ~~`git init`~~ ✅
2. ~~`docker-compose.yml` — PostgreSQL + MinIO~~ ✅ 서버에서 기동 확인
3. ~~Gradle + Spring Boot 4 프로젝트 생성~~ ✅
4. ~~Testcontainers 골격 + `Clock` 빈 주입~~ ✅
5. ~~통과하는 통합 테스트 1개~~ ✅ **4건 통과, 23초**

### 다음 — W1 수직 슬라이스

목표는 여전히 **브라우저에서 사진 1장을 올리면 썸네일이 뜬다**([roadmap.md](./roadmap.md) §2 W1).

- [ ] 스토리지 — S3 클라이언트 추상화, 해시 기반 키, CAS 저장/조회 (`I1`, `I2` 본 검증)
- [ ] 업로드 — 단일 파일 `PUT` (청크 없음)
- [ ] 처리 — `PROBE`(EXIF) + `THUMB`(libvips), **동기 실행**
- [ ] 인증 — 소셜 로그인 1종
- [ ] 프론트 — `archive-frontend/` 에 React + Vite + TS 스캐폴딩, 파일 선택 + 결과 표시, 스타일 없음 (ADR A16)

### 실행 환경 (확정)

서버 `192.168.133.222`. Twingate 로 접속하고, Docker 데몬은 `tcp://192.168.133.222:2375`
에서 사설 IP 로만 열려 있다. 로컬에 필요한 것:

```bash
export DOCKER_HOST=tcp://192.168.133.222:2375
export TESTCONTAINERS_HOST_OVERRIDE=192.168.133.222
```

상시 스택 Postgres 호스트 포트는 **5434** 다 (서버에 다른 Postgres 가 5432·5433 을 쓰는 중).
상세는 [infra.md](./infra.md).

---

## 단계별 상태

| 단계 | 상태 | 완료 기준 |
|---|---|---|
| 설계 | ✅ 완료 | 데이터 모델·파이프라인·로드맵 문서화 |
| W1 뼈대 | 🔶 진행 중 | 브라우저에서 사진 1장 → 썸네일 표시 (하네스는 완료) |
| W2 업로드·파이프라인 | ⬜ 미착수 | 50장+영상 3개 업로드, 중단 후 재개 |
| W3 기록·블록 | ⬜ 미착수 | 기록 하나를 처음부터 끝까지 작성 |
| W4 하네스 | ⬜ 미착수 | `./gradlew verify` 하나로 전부 검증 |
| W5 에이전트 루프 | ⬜ 미착수 | 포트폴리오 제출 가능 |

## 불변식 도입 현황

| # | 불변식 | 상태 |
|---|---|---|
| I1 | 원본 바이트 불변 | ⬜ W1 — 스토리지 계층 필요 |
| I2 | 중복 저장 없음 | 🔶 W1 — DB 제약(`UNIQUE(owner_id, content_hash)`)만 검증. 속성 테스트는 스토리지 이후 |
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
| `./gradlew test` (원격 데몬, 컨테이너 기동 포함) | **23초** / 테스트 4건 | 2026-08-06 |
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

- [ ] **프론트 검증을 `./gradlew verify` 에 묶을 것인가** (W4에서 결정, [design.md](./design.md) §10)
      — node-gradle 플러그인 / `Exec` 태스크 / 루트 `verify` 스크립트 셋 중 하나.
      프론트에 어떤 검증이 실제로 생기는지 본 뒤에 정한다
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

### 2026-08-06 — 서버 셋업과 하네스 검증
서버에서 상시 스택을 띄우고 원격 Docker 데몬을 열어, **통합 테스트 4건이 컨테이너 위에서 통과**하는 것을 확인했다. 23초.

- 서버 `192.168.133.222`, Twingate 경유. dockerd 는 사설 IP 에만 바인딩(`0.0.0.0` 아님)
- systemd override 는 기존 `ExecStart` 를 읽어 뒤에 덧붙이는 방식으로 작성했다. 통째로 덮어쓰면 배포판이 넣어둔 `--containerd=…` 가 사라진다
- 서버에 다른 Postgres 가 5432·5433 을 점유 중이라 호스트 포트를 **5434** 로. compose 를 고치는 대신 `.env` 변수로 뺐다 (`POSTGRES_PORT`, `MINIO_API_PORT`, `MINIO_CONSOLE_PORT`)
- 이 충돌은 Testcontainers 와 무관하다. 매번 빈 포트를 스스로 고르기 때문
- Ryuk 회수 정상 동작 확인 — 테스트 후 서버에 남는 컨테이너 없음

**W1 하네스 완료.** 남은 것은 수직 슬라이스(스토리지 → 업로드 → PROBE/THUMB → 프론트).

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

### 2026-08-06 — 설계
주제 선정부터 전체 설계까지. 데이터 모델 확정, 업로드·처리 파이프라인 설계, 5주 로드맵과 불변식 명세 작성.

설계 과정에서 제거된 것: 그룹 관련 테이블 3개(촬영본 대부분을 사용하므로 "추려내기" 불필요), `scene_overrides`(장면 분할이 1회성 계산이라 override 보존 불필요), `PHOTO`/`VIDEO` 블록 타입(`GALLERY`로 통합), `quality_score` 컬럼.
