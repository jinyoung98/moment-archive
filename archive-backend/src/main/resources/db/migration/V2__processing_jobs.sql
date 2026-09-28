-- W2 처리 작업 큐. schema.md §5.6 의 DDL. 설계 문서가 원본 — 바꿀 때 schema.md 를 같은 커밋에서.
--
-- 시각 컬럼(run_after, locked_at, created_at)은 전부 애플리케이션이 주입된 Clock 값으로 넣는다.
-- DEFAULT now() 는 안전망. 큐 SQL 도 now() 대신 :now 파라미터 — 백오프·lease 를 시계만 돌려
-- 검증하기 위함. 대가인 워커 간 시계 오차는 기동 시 검사로 막음 (ADR A24, ClockSkewGuard).

CREATE TABLE processing_jobs (
  id           bigserial PRIMARY KEY,
  asset_id     uuid NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,

  stage        text NOT NULL,        -- PROBE | THUMB | DERIVE
  lane         text NOT NULL,        -- PHOTO | VIDEO. 워커 풀을 나누는 기준

  state        text NOT NULL,        -- QUEUED | RUNNING | DONE | DEAD
                                     -- 재시도 대기는 별도 상태 없이 QUEUED + 미래 run_after
  attempt      int  NOT NULL DEFAULT 0,   -- 실패(또는 lease 회수) 누적 횟수
  max_attempts int  NOT NULL DEFAULT 5,

  run_after    timestamptz NOT NULL DEFAULT now(),
  locked_by    text,                 -- 작업을 잡은 워커 인스턴스 ID
  locked_at    timestamptz,          -- 잡은 시각. 하트비트가 갱신. 오래되면 회수

  checkpoint   jsonb,                -- 중간 진행 상태 (영상 트랜스코딩 재개용, W2 후반)
  last_error   text,
  created_at   timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT ck_jobs_stage CHECK (stage IN ('PROBE', 'THUMB', 'DERIVE')),
  CONSTRAINT ck_jobs_lane  CHECK (lane  IN ('PHOTO', 'VIDEO')),
  CONSTRAINT ck_jobs_state CHECK (state IN ('QUEUED', 'RUNNING', 'DONE', 'DEAD'))
);

-- 획득 쿼리(lane, state, run_after 순 필터 → id 순 정렬) 전용.
CREATE INDEX idx_jobs_pickup ON processing_jobs (lane, state, run_after, id);

-- 같은 자산·단계의 살아 있는 작업은 하나만. 중복 PROBE 가 THUMB 을 또 등록하는 경합을
-- 에러 없이 흡수 (enqueue 가 ON CONFLICT DO NOTHING). DONE/DEAD 는 이력이라 제외.
CREATE UNIQUE INDEX uq_jobs_active ON processing_jobs (asset_id, stage)
  WHERE state IN ('QUEUED', 'RUNNING');

-- lease 회수 스캔용.
CREATE INDEX idx_jobs_running ON processing_jobs (locked_at) WHERE state = 'RUNNING';
