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

> **테스트가 5배로 늘었는데 시간이 줄어든 것을 개선으로 읽지 말 것.** 조건이 다르다 —
> 서버에 이미지가 캐시된 상태였고, Gradle 데몬도 떠 있었다. 8/6 의 23초에는 이미지 pull 이
> 섞여 있다. 두 컨테이너를 `Startables` 로 병렬 기동한 것만이 실제 개선분이고, 그 크기는
> 아직 따로 재지 않았다. 성능 기준선은 여기서 재지 않는다 ([infra.md](../infra.md) §5).
> 8/13 의 58초도 같은 이유로 앞선 값들과 비교하지 말 것 — 직전에 서버 `docker` 를
> 재시작해 이미지 캐시가 비어 있었다 (아래 항목 참조).

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
