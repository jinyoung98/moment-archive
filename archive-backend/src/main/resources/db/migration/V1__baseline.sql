-- W1 기준선 스키마.
-- schema.md §5.2 ~ §5.4 의 DDL을 그대로 옮긴 것이다. 설계 문서가 원본이므로,
-- 스키마를 바꿀 때는 schema.md 를 같은 커밋에서 함께 고친다.
--
-- W1 범위는 users / media_assets / media_derivatives 세 개까지다.
-- upload_sessions, processing_jobs, records 계열은 W2~W3 에서 별도 마이그레이션으로 추가한다.
--
-- created_at 의 DEFAULT now() 는 안전망일 뿐이다.
-- 애플리케이션은 주입된 Clock 에서 얻은 값을 항상 명시적으로 넣는다 (ClockConfig 참조).
-- DB 시계에 의존하면 Clock.fixed() 로 시간을 고정한 테스트가 의미를 잃는다.

CREATE TABLE users (
  id           uuid PRIMARY KEY,
  provider     text NOT NULL,       -- 'GOOGLE' | 'NAVER' 등 소셜 제공자
  provider_uid text NOT NULL,       -- 제공자 쪽 사용자 식별자
  email        text,
  display_name text,
  created_at   timestamptz NOT NULL DEFAULT now(),

  UNIQUE (provider, provider_uid)
);

CREATE TABLE media_assets (
  id              uuid PRIMARY KEY,
  owner_id        uuid NOT NULL REFERENCES users(id),

  content_hash    char(64) NOT NULL,    -- 파일 내용의 SHA-256. 이것이 진짜 정체성
  byte_size       bigint  NOT NULL,     -- 원본 크기 (바이트)
  mime_type       text    NOT NULL,     -- image/jpeg, image/heic, video/quicktime ...
  kind            text    NOT NULL,     -- 'PHOTO' | 'VIDEO'. 처리 레인을 가르는 값
  storage_key     text    NOT NULL,     -- 예: originals/ab/cd/abcdef0123...

  captured_at     timestamptz,          -- 실제 촬영 시각. EXIF 에서 추출. 없을 수 있음
  captured_at_src text,                 -- 'EXIF' | 'FILE_MTIME' | 'MANUAL'
  tz_offset_min   int,                  -- 촬영 시각의 시간대 오프셋(분)

  gps_lat         numeric(9,6),
  gps_lon         numeric(9,6),

  width           int,
  height          int,
  orientation     int,                  -- EXIF 회전 플래그(1~8)
  duration_ms     int,                  -- 영상 길이. 사진이면 NULL

  phash           bigint,               -- perceptual hash. 연사 중복 감지용 (2차 기능)

  status          text NOT NULL,        -- INGESTED | PROBED | THUMBED | READY | FAILED

  orphaned_at     timestamptz,          -- 어떤 기록에서도 참조되지 않게 된 시각
  purge_after     timestamptz,          -- 이 시각 이후 물리 삭제 대상

  created_at      timestamptz NOT NULL DEFAULT now(),

  UNIQUE (owner_id, content_hash)       -- I2: 같은 사용자의 같은 파일은 하나만 존재
);

CREATE INDEX idx_assets_captured ON media_assets (owner_id, captured_at);
CREATE INDEX idx_assets_purge    ON media_assets (purge_after) WHERE purge_after IS NOT NULL;

CREATE TABLE media_derivatives (
  id           uuid PRIMARY KEY,
  asset_id     uuid NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,

  recipe       text NOT NULL,        -- THUMB_256 | PREVIEW_1600 | POSTER | VIDEO_720P
  recipe_ver   int  NOT NULL,        -- 레시피 버전. 알고리즘을 바꾸면 올린다

  storage_key  text   NOT NULL,
  byte_size    bigint NOT NULL,
  content_hash char(64) NOT NULL,    -- I3: "같은 입력 → 같은 출력" 검증에 사용
  created_at   timestamptz NOT NULL DEFAULT now(),

  UNIQUE (asset_id, recipe, recipe_ver)
);
