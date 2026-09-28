# 세션 일지

> **읽지 않는 문서다.** progress.md 에서 뺀 기록 보관소이고, 세션 시작 때 로딩하지 않는다.
> 필요할 때만 — "그때 왜 그렇게 했더라" 를 되짚을 때만 — 해당 항목을 찾아 읽는다.
>
> 여기 있는 판단은 대부분 이미 ADR·interview-notes·코드 주석으로 옮겨졌다.
> 이 파일은 **시점과 맥락**을 남기는 용도다: 무엇을 언제 알았고, 무엇을 착각했는가.
>
> 새 항목은 위에 붙인다. 길이 제한은 두지 않는다.

---

## 측정 기록

> roadmap.md §7 이 "무엇을 재야 하는가" 의 원본이다. 여기는 잰 값을 쌓는다.
> **숫자 없는 포트폴리오는 절반짜리다.**

| 항목 | 값 | 측정일 |
|---|---|---|
| `./gradlew test` — Postgres 만, 테스트 4건 | **23초** | 2026-08-06 |
| `./gradlew clean test` — Postgres+MinIO, 테스트 20건 | **10초** | 2026-08-11 |
| `./gradlew test` — Postgres+MinIO, 테스트 23건 | **58초** | 2026-08-13 |
| `./gradlew test` — Postgres+MinIO, 테스트 29건(THUMB 2건 스킵) | **13초** | 2026-08-20 |
| `./gradlew test` — Postgres+MinIO, 테스트 36건(vips 설치·THUMB 포함) | **14초** | 2026-08-20 |
| 프론트 `npm run build` (tsc --noEmit + vite build), 78 모듈 | **~0.4초**(빌드만) | 2026-08-21 |
| `./gradlew test` — Postgres+MinIO, 테스트 65건(큐·워커 포함, vips 설치) | **22초** | 2026-09-28 |

> **테스트가 5배로 늘었는데 시간이 줄어든 것을 개선으로 읽지 말 것.** 조건이 다르다 —
> 서버에 이미지가 캐시된 상태였고, Gradle 데몬도 떠 있었다. 8/6 의 23초에는 이미지 pull 이
> 섞여 있다. 두 컨테이너를 `Startables` 로 병렬 기동한 것만이 실제 개선분이고, 그 크기는
> 아직 따로 재지 않았다. 성능 기준선은 여기서 재지 않는다 ([infra.md](../infra.md) §5).
> 8/13 의 58초도 같은 이유로 앞선 값들과 비교하지 말 것 — 직전에 서버 `docker` 를
> 재시작해 이미지 캐시가 비어 있었다 (아래 항목 참조).

---

## 2026-09-28 — W2 ① 처리 작업 큐 + 워커

W2 첫 조각. 동기 처리(`MediaProcessingService`)를 걷어내고 `processing_jobs` 큐 + `JobWorker` 로.
업로드는 자산 행 + PROBE 등록을 한 트랜잭션에 넣고 즉시 응답(INGESTED). 프론트는 `GET /media/{id}`
1초 폴링(SSE 전 임시). 테스트 65건 통과, `I9` 도입. 브라우저에서 새 사진(처리 중… → THUMBED)·
중복 사진(즉시 이미 있던 사진) 확인 — 상시 스택 DB 에 V2 적용됨.

- **W2 순서를 큐 먼저로.** 큐가 들어오면 업로드 응답이 "즉시, 결과는 나중"으로 바뀌어 나머지(세션·SSE·
  영상)가 전부 작업 등록 하나로 붙음. 세션을 먼저 하면 동기 처리 위에 쌓았다 뜯게 됨
- **`:now` vs DB `now()`** — 사용자와 논의. 테스트 가능성 때문에 `:now` 를 택하고, 대가인 워커 간 시계
  오차는 "회수자 시계가 90초 넘게 빠르면 살아 있는 작업을 뺏어 attempt 를 올린다" 한 곳만 위험하다고
  좁힘 → 예산 강제 + 기동 시 DB 시계 비교 (ADR A24)
- 설계 중 발견: `locked_by = workerId` 는 같은 프로세스 두 스레드의 회수·재획득에서 소유권 확인을
  통과해 버림 → 획득마다 잠금 토큰 (ADR A26, 펜싱 토큰)
- 업로드의 `DuplicateKeyException` 잡기 방식은 트랜잭션 안에서 못 씀 — Postgres 가 오류 난 트랜잭션을
  통째로 중단시켜 같은 트랜잭션의 작업 등록을 이어갈 수 없음 → `ON CONFLICT DO NOTHING`
- **I9 스냅샷 테스트의 사각.** PROBE 재실행이 THUMB 을 다시 끌고 와 최종 상태가 복구되므로, 중간의
  THUMBED→PROBED 역행을 못 잡음. 상태 전진 로직을 일부러 빼는 변이로 확인 — I9 통과, 새 테스트만 실패
- **리뷰(code-reviewer)에서 HIGH 2건, 둘 다 WorkerLoop.** ① `RuntimeException` 만 잡아 OOM 이면
  `scheduleWithFixedDelay` 가 drain 을 로그 없이 영구 취소 → 레인이 조용히 죽음. ② 종료 중에도 drain 이
  백로그를 계속 claim → 배포마다 attempt 상승. `Throwable` 포획 + `stopping` 플래그로 수정
- 리뷰 MEDIUM 반영: PROBE DEAD(재시도 소진·회수 소진·처리기 부재) → 자산 FAILED (안 그러면 작업 없이
  INGESTED 정지), 상태 응답에 `processing`(THUMB DEAD 로 PROBED 정지와 처리 중을 구분), 자산 갱신을
  `FOR UPDATE` 로(동시 커밋 역행), 시계 예산 2배(워커끼리는 ±오차 두 개), 워커 수준 펜싱 테스트 추가
- 미반영(§11 미결로): 스케줄러 스레드 공유로 하트비트 지연, 다중 워커 libvips 버전 차이 시 파생물 해시 어긋남

> 정본: ADR A24·A25·A26([design.md](../design.md) §11), [pipeline.md](../pipeline.md) §5(§5.8 신설),
> [schema.md](../schema.md) §5.6, [interview-notes.md](../interview-notes.md) §3, `JobQueue`·`JobWorker`·`WorkerLoop` 주석.

---

## 2026-09-23 — W1 종료 (브라우저 E2E)

한 달 공백 뒤 복귀. 8/21 작업분이 미커밋 상태였음 → 백엔드 테스트 39건 전건 통과·프론트 빌드 재확인 후
커밋. 브라우저에서 로그인 → 사진 1장 → 썸네일(`새 사진 · THUMBED`) 확인으로 **W1 종료**.

- 첫 업로드가 500. 원인은 코드가 아니라 로컬 `.envrc` 의 `S3_ACCESS_KEY`/`S3_SECRET_KEY` 가 자리표시자
  (`archive`) 그대로였던 것 → MinIO 가 `HeadObject` 에 403. 7자라 MinIO 루트 비밀번호(8자 이상)일 수 없다는
  데서 바로 좁혀짐. 헬스 200·시계 오차 없음 확인으로 네트워크·서명 시각 원인은 배제
- **테스트가 이걸 못 잡는 구조.** Testcontainers 가 자체 MinIO 에 자기 자격을 주입하므로 로컬 설정 오류는
  bootRun 에서만 드러남. 기동 시 버킷 HEAD 로 자격을 미리 검증하는 fail-fast 는 후보로만 남겨 둠
- 저장소 오류가 스택 트레이스 그대로 500 으로 나감. W1 범위 밖이라 보류 — 오류 응답 매핑은 W2 이후 판단

---

## 2026-08-21 — 프론트 (W1 수직 슬라이스 끝단) + 썸네일 서빙

W1 마지막 조각. `archive-frontend/`(React+Vite+TS+TanStack Query)를 세우고, 프론트가 필요로 하는
백엔드 구멍 둘을 같이 메웠다. 백엔드 테스트 전건 통과(썸네일 webp 바이트 실측 포함), 프론트
빌드·타입체크 통과. **브라우저 E2E 1회 수동 확인은 남겨 둠** — 구글 자격 + 상시 스택이 있어야 도는 단계.

- **썸네일은 assetId 로 서빙**(`GET /media/{id}/thumbnail`), storage_key 는 URL 에 안 내보낸다.
  내부 스토리지 좌표라 바뀔 수 있고 열거되면 안 됨. 남의 자산·없는 자산을 같은 404 로 응답 —
  403 은 "있긴 하다"를 흘린다. 소유 확인을 붙일 자리가 assetId 경로였다 (ADR A21)
- **SPA CSRF 는 쿠키만 켜는 걸로 안 끝났다.** Spring Security 6 이 토큰을 지연 로딩해서 첫 로드에
  `XSRF-TOKEN` 쿠키가 안 나가고 → 첫 업로드가 403. 매 요청 토큰을 읽어 쿠키를 강제하는
  `CsrfCookieFilter` 를 넣고, XOR 핸들러(쿠키 원문≠헤더 값)를 비-XOR 로 바꿔야 SPA 가 쿠키를
  그대로 되보낼 수 있다. 둘 다 레퍼런스 SPA 패턴 (ADR A22)
- **개발 프록시 크로스오리진 쿠키 함정.** :8080 에 심긴 세션 쿠키는 :5173 요청에 안 붙는다.
  Vite 프록시를 `changeOrigin:false` 로 둬 Host 를 :5173 로 보존 → Spring 이 redirect_uri·쿠키를
  :5173 기준으로 만들고 OAuth 왕복 전체가 단일 오리진이 된다. 대가는 Google Console 에 :5173
  리다이렉트 URI 하나 더 (ADR A23)
- TanStack Query 는 W1 에선 `/api/me` 캐싱뿐이라 과해 보이지만, W2 의 SSE·폴링 이중 갱신이
  이걸 고른 이유라 처음부터 그 위에 올렸다 (ADR A16). `retry:false` — 401 은 재시도해도 401
- 함정: `HttpError` 를 client 에서 던지는데 App 이 media 에서 import 하려다 컴파일 실패. media 를
  서버 접점 단일 창구로 두려고 거기서 되노출로 정리. TanStack v5 는 `isLoading` 이 아니라
  `isPending` 로 분기해야 이후 `data` 가 non-undefined 로 좁혀진다

> 정본: ADR A21·A22·A23([design.md](../design.md) §11), [interview-notes.md](../interview-notes.md) §1,
> `MediaThumbnailController.java`·`SecurityConfig.java`·`vite.config.ts`·`api/` 주석.

---

## 2026-08-20 — 인증 (구글 OIDC + 쿠키 세션)

W1 마지막 백엔드 조각. `DevUserProvider`(고정 사용자)를 실제 소셜 로그인으로 대체. 정본은
ADR A20, interview-notes §1.

- **JWT 가 아니라 쿠키 세션.** JWT 는 무효화하려면 결국 서버 상태(블랙리스트)가 필요해
  stateless 이점이 사라진다. 개인 서비스라 다중 서버 확장도 없어서 서버가 무효화를 직접
  통제하는 쿠키 세션이 그냥 낫다. 세션은 인메모리 — 유지 필요 시 Redis 아닌 Spring Session JDBC
- **신원은 `(provider, provider_uid)`.** provider 를 키에 넣어 Kakao(예정)를 표 변경 없이 추가.
  로그인 시 find-or-create 로 내부 `users.id` 확정 → 세션 주체 `ArchiveUser` 에 실어 둠.
  컨트롤러는 구글 sub 이 아니라 그 내부 id 만 봄 (`CurrentUser.requireUserId()`)
- **공개 배포 대비 이메일 허용목록.** OAuth 성공해도 목록 밖이면 거부. 목록은 여러 명 가능,
  비면 전체 허용 + 시작 경고. 허용 판정을 네트워크와 분리(`ArchiveOidcUserService.process`)해
  합성 사용자로 단위 테스트 — libvips 를 CLI 뒤로 뺀 것과 같은 발상
- **미인증은 302 아닌 401**(SPA 소비 API), **CSRF 유지**(더블서밋 쿠키, XSRF-TOKEN 만 httpOnly off)
- **실제 구글 왕복은 미검증** — 자격이 있어야 한다. 테스트는 `spring-security-test` 로 인증 주체를
  주입하고, oauth2Login 컨텍스트 기동용 더미 자격만 IntegrationTest 가 넣는다. 브라우저 1회
  수동 확인은 프론트 붙일 때
- 함정: CSRF 켠 상태에서 미인증 PUT 은 CSRF(403)가 인증(401)보다 먼저 걸린다. "미인증=401" 을
  드러내려면 테스트에서 `csrf()` 는 통과시키고 인증만 빼야 함

> 정본: ADR A20([design.md](../design.md) §11), [interview-notes.md](../interview-notes.md) §1,
> `auth/` 패키지와 `AuthIntegrationTest.java` 주석.

---

## 2026-08-20 — 처리 (PROBE + THUMB) 동기 실행

W1 수직 슬라이스의 처리 단계. 업로드 요청 안에서 `PROBE → THUMB` 를 큐 없이 바로 돌린다
(`INGESTED → PROBED → THUMBED`). 정본은 ADR A18·A19, interview-notes §3.

- **libvips 를 CLI 로, 인터페이스 뒤에 뒀다.** JNI/FFM 바인딩은 네이티브 링크 실패가 컨텍스트
  기동을 통째로 막는다. `Thumbnailer` 인터페이스 + `vips thumbnail` 서브프로세스면 libvips
  없어도 앱은 뜨고 THUMB 만 `isAvailable()` 로 건너뛴다 — `ObjectStorage` seam 과 같은 꼴
- **이 노트북엔 libvips 도 ffmpeg 도 없다.** 그래서 THUMB 통합 단정은 `assumeTrue` 로 스킵되고
  PROBE·상태전이·멱등(I9)만 실측했다. 골든셋 부재 시 스킵과 같은 결(roadmap §5). THUMB 바이트
  실측은 `brew install vips` 한 환경에서 별도로 돌려야 함 — **아직 THUMB 산출 자체는 미실측**
- **PROBE 는 순수 Java(metadata-extractor).** libvips 를 CLI 로 뺀 것과 대비되는데 의도한 것 —
  PROBE 를 "컨테이너도 네이티브도 없는" 단위 테스트 층에 두려고. 영상 ffprobe 는 W2
- **랜덤 바이트를 이미지로 위장한 기존 테스트가 깨졌다.** 처리가 동기라 PROBE 가 그걸 손상으로
  판정한다. `ImageIO` 로 실제 JPEG 을 만들게 바꾸고, 손상 파일 → `FAILED` 케이스를 따로 고정
- 파생물 키는 `derivatives/ab/cd/{원본해시}/THUMB_256_1.webp` — pipeline §6 에 originals 와 같은
  2/2 샤딩을 더했다(문서도 같은 커밋에서 수정). 행은 `ON CONFLICT` UPSERT 로 멱등
- libvips 부재를 자산 `FAILED` 로 보지 않는다 — 배포 문제이지 미디어 문제가 아니므로 `PROBED`
  유지(P5). 이 구분이 상태 머신을 어지럽히지 않는 열쇠

> 정본: ADR A18·A19([design.md](../design.md) §11), [interview-notes.md](../interview-notes.md) §3,
> `processing/` 패키지와 `MediaProcessingIntegrationTest.java` 주석.

---

## 2026-08-13 — 업로드 엔드포인트

`PUT /media` — `MultipartFile` → temp file → `ContentAddressedStore.storeOriginal()` →
`media_assets` 행 find-or-create. 통합 테스트 3건 통과.

- **인증이 없는 채로 `owner_id` 를 채워야 했다.** `DevUserProvider` 를 만들어
  provider+provider_uid 로 find-or-create 하게 했다 — 이 로직은 실제 소셜 로그인이 붙어도
  그대로 남는다, 신원의 출처(고정값 → OAuth 토큰)만 바뀐다. 컨트롤러가 SecurityContext 에서
  `owner_id` 를 꺼내는 순간 이 클래스는 통째로 삭제된다
- **`media_assets`/`users` 가 이 프로젝트 첫 Spring Data JDBC 엔티티다.** 클라이언트가
  UUID PK 를 미리 할당(`UUID.randomUUID()`)하는데, `CrudRepository.save()` 는 PK 가
  null 이 아니면 "기존 행"으로 보고 UPDATE 를 시도해 0행 갱신으로 실패한다. `Persistable`
  구현 대신 `JdbcAggregateOperations.insert()` 를 직접 호출해 신규/기존 판단 자체를
  건너뛰었다 — 엔티티가 record 로 남을 수 있는 이유이기도 하다
- **Boot 4 / Jackson 3 로 옮겨간 패키지를 또 한 번 밟았다** (Q&A 1장의 Boot 4 항목과 같은
  종류). 테스트에서 `@AutoConfigureMockMvc` 는 `org.springframework.boot.webmvc.test.autoconfigure`,
  `ObjectMapper` 는 `tools.jackson.databind` 로 옮겨져 있었다. `compileTestJava` 를 먼저
  돌려 컨테이너 기동 전에 잡아냈다
- **서버에 얹은 Kubernetes 가 Docker 를 막았다.** 통합 테스트가 전부
  `iptables: No chain/target/match by that name` 로 컨테이너 기동에 실패했는데, 원인은
  `kube-proxy` 가 iptables 규칙을 재조정하며 Docker 의 `DOCKER` 체인과 충돌한 것으로
  보인다. 그 노드의 K8s 런타임이 `containerd` 직접이라(`dockerd` 를 안 거침) `docker
  restart` 로 안전하게 복구됐고 파드에는 영향이 없었다. **재발 가능성은 남아 있다** —
  `kube-proxy` 가 다시 규칙을 정리하면 또 깨질 수 있어, 반복되면 격리(예: K8s 안에
  Docker-in-Docker 파드) 를 검토해야 한다. 이 재발 여부는 두 시스템을 한 서버에 같이
  둔 이상 계속 지켜봐야 하는 항목이다

---

## 2026-08-11 — 스토리지 계층

`media` 패키지에 S3 추상화 → 해시 기반 키 → CAS 를 넣고 **MinIO 컨테이너 위에서 20건 통과**.

- 층을 셋으로 나눴다 (해시·키 규칙 / 바이트 입출력 / 중복 판단). 검증 방법이 층마다 다르다 —
  앞의 것은 순수 함수라 컨테이너가 필요 없다. 원격 데몬 환경에서 이 구분이 곧 반복 속도다
- 업로드에 `x-amz-checksum-sha256` 을 실어 **스토리지가 수신 바이트를 대조하게** 했다.
  방금 올린 원본을 다시 내려받지 않고 `I1` 의 절반을 얻는다 (ADR A17)
- **`HeadObject` 는 `checksumMode=ENABLED` 없이는 체크섬을 안 준다.** 응답이 계속 비어서
  MinIO 가 보관을 안 하는 줄 알았는데 기본값이 꺼짐이었을 뿐이다. 저장은 처음부터 되고 있었다
- `I2` 를 jqwik 으로 못 썼다. `@Property` 는 자체 생명주기라 **Spring 확장이 안 붙어**
  컨테이너·빈 주입과 같이 쓸 수 없다. 시드를 상수로 박은 무작위 테스트로 대체했고,
  진짜 속성 테스트는 Spring 이 필요 없는 순서키(`I5`)에서 W4 에 도입한다
- 스토리지에는 트랜잭션 롤백이 없다. 컨테이너를 공유하므로 각 테스트 앞에서 `originals/` 를 비운다
- 스토리지 계층은 **DB 를 모른다.** 자산 행을 쓰는 것은 업로드의 일로 남겼다

> 이 판단들의 정본: ADR A17 ([design.md](../design.md) §11), [interview-notes.md](../interview-notes.md)
> §1·§9, 그리고 `S3ObjectStorage.java` · `ContentAddressedStoreIntegrationTest.java` 의 주석.

---

## 2026-08-06 — 서버 셋업과 하네스 검증

원격 Docker 데몬을 열고 **통합 테스트 4건이 컨테이너 위에서 통과**하는 것을 확인했다. 23초.

- 서버 `192.168.133.221`, Twingate 경유. dockerd 는 사설 IP 에만 바인딩(`0.0.0.0` 아님)
- systemd override 는 기존 `ExecStart` 를 읽어 뒤에 덧붙였다. 통째로 덮어쓰면 배포판이 넣어둔 `--containerd=…` 가 사라진다
- 서버에 다른 Postgres 가 5432·5433 을 점유해 호스트 포트를 **5434** 로. compose 를 고치는 대신 `.env` 변수로 뺐다. 이 충돌은 Testcontainers 와 무관하다 — 매번 빈 포트를 스스로 고르기 때문
- Ryuk 회수 정상 — 테스트 후 서버에 남는 컨테이너 없음
- 저장소를 `archive-backend/` + `archive-frontend/` 로 나누고 Gradle 멀티프로젝트로 전환

> 이 항목들의 정본은 [infra.md](../infra.md) 다 — 서버 셋업 절차와 제약이 거기 정리돼 있다.

---

> 이전 기록(W1 착수, ADR A12~A16)은 남기지 않았다. 판단은 design.md ADR 표에,
> Boot 4 좌표에서 틀렸던 것들은 `build.gradle` 주석에 있다.
