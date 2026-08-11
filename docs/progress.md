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

- **최종 갱신**: 2026-08-11
- **현재 단계**: W1 진행 중 — 하네스·스토리지 완료, 업로드 착수 전

---

## 지금 해야 할 일

> **다음 첫 작업: 업로드.**
> 단일 파일 `PUT` 엔드포인트 → `ContentAddressedStore.storeOriginal()` → `media_assets` 행 생성.
> 스토리지 계층은 DB 를 모르는 상태로 끝냈으므로, 자산 행을 쓰는 것이 여기서 처음 나온다.
> 중복(`reused == true`)일 때 기존 자산을 재사용하는 경로를 같이 만든다 —
> `UNIQUE (owner_id, content_hash)` 충돌은 에러가 아니라 정상 경로다 (interview-notes.md §7).

### W1 수직 슬라이스

목표는 **브라우저에서 사진 1장을 올리면 썸네일이 뜬다** ([roadmap.md](./roadmap.md) §2 W1).

- [x] 하네스 — Testcontainers + `Clock` 주입, 통합 테스트 4건 통과
- [x] 스토리지 — S3 추상화, 해시 기반 키, CAS 저장/조회. `I1`·`I2` 를 MinIO 위에서 검증
- [ ] 업로드 — 단일 파일 `PUT` (청크 없음)
- [ ] 처리 — `PROBE`(EXIF) + `THUMB`(libvips), **동기 실행**
- [ ] 인증 — 소셜 로그인 1종
- [ ] 프론트 — `archive-frontend/` 스캐폴딩, 파일 선택 + 결과 표시, 스타일 없음 (ADR A16)

### 실행 환경

새 셸마다 필요하다. 없으면 `Could not find a valid Docker environment` 로 실패한다.

```bash
export DOCKER_HOST=tcp://192.168.133.221:2375
export TESTCONTAINERS_HOST_OVERRIDE=192.168.133.221
```

상시 스택 Postgres 호스트 포트는 **5434**. 서버 셋업·제약은 [infra.md](./infra.md).

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
