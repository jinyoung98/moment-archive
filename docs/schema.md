# 데이터 모델 — 전체 DDL

> [design.md](./design.md) 의 **§5** 다. 파일만 분리했을 뿐 절 번호는 그대로 유지한다 —
> `V1__baseline.sql` 과 다른 문서들이 `§5.3` 같은 번호로 이 안을 가리키고 있기 때문이다.
>
> 분리한 이유는 성격이 다르기 때문이다. 여기는 DDL 뿐이고, design.md 의 나머지는 판단과
> 정책이다. 스키마를 볼 때 정책까지, 정책을 볼 때 DDL 까지 같이 읽을 이유가 없다.

- **관련**: [design.md §7 삭제 정책](./design.md), [design.md §11 ADR](./design.md), [pipeline.md](./pipeline.md)
- **구현**: `archive-backend/src/main/resources/db/migration/`

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
