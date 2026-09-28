package dev.jinyoung.archive.processing.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Postgres 테이블 위의 작업 큐 (ADR A1 — 브로커 없음). pipeline.md §5.
 *
 * {@code locked_by} 에는 워커 ID 가 아니라 <b>획득마다 새로 발급한 잠금 토큰</b>. 같은 프로세스의 두
 * 스레드가 회수·재획득으로 같은 작업을 번갈아 잡아도 서로의 완료를 덮지 않게 (펜싱 토큰).
 *
 * 모든 시각은 호출부가 넘기는 {@code now} — SQL 의 now() 미사용 (ADR A24). 테스트가 시계만 돌려
 * 백오프·lease 를 검증. 상태 변경 UPDATE 는 전부 {@code locked_by = :lockToken AND state = 'RUNNING'}
 * 조건부 — 회수당한 워커의 뒤늦은 결과가 남의 작업을 덮지 않게 (§5.4 소유권 확인).
 *
 * Data JDBC 리포지토리 대신 JdbcClient: SKIP LOCKED·RETURNING·조건부 CASE 가 파생 쿼리로 안 나옴.
 * timestamptz 는 OffsetDateTime(UTC)로 주고받음 — pgjdbc 가 Instant 를 직접 바인딩 안 함.
 */
@Repository
public class JobQueue {

    private static final String COLUMNS = """
            id, asset_id, stage, lane, state, attempt, max_attempts,
            run_after, locked_by, locked_at, last_error, created_at""";

    private static final RowMapper<ProcessingJob> ROW_MAPPER = JobQueue::mapRow;

    private final JdbcClient jdbc;

    public JobQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 작업 등록. 같은 자산·단계의 살아 있는(QUEUED/RUNNING) 작업이 이미 있으면 무시 —
     * uq_jobs_active 부분 유니크 인덱스. 중복 실행된 PROBE 가 THUMB 을 또 거는 경합 흡수.
     *
     * @return 새로 등록했으면 true
     */
    public boolean enqueue(UUID assetId, Stage stage, Lane lane, int maxAttempts, Instant now) {
        int rows = jdbc.sql("""
                        INSERT INTO processing_jobs
                            (asset_id, stage, lane, state, attempt, max_attempts, run_after, created_at)
                        VALUES (:assetId, :stage, :lane, 'QUEUED', 0, :maxAttempts, :now, :now)
                        ON CONFLICT (asset_id, stage) WHERE state IN ('QUEUED', 'RUNNING') DO NOTHING
                        """)
                .param("assetId", assetId)
                .param("stage", stage.name())
                .param("lane", lane.name())
                .param("maxAttempts", maxAttempts)
                .param("now", ts(now))
                .update();
        return rows == 1;
    }

    /**
     * 실행 가능한 작업 하나를 잡음. SKIP LOCKED — 다른 워커가 잠근 행은 기다리지 않고 건너뜀.
     * 이 한 줄이 없으면 워커들이 같은 행에서 줄 서서 사실상 직렬화 (pipeline.md §5.1).
     *
     * SELECT FOR UPDATE + UPDATE 두 문장을 서브쿼리 UPDATE … RETURNING 한 문장으로 — 자동 커밋
     * 단일 문장이라 트랜잭션 경계를 호출부가 신경 쓸 필요 없음.
     */
    public Optional<ProcessingJob> claim(Lane lane, String lockToken, Instant now) {
        return jdbc.sql("""
                        UPDATE processing_jobs
                           SET state = 'RUNNING', locked_by = :lockToken, locked_at = :now
                         WHERE id = (
                                SELECT id FROM processing_jobs
                                 WHERE state = 'QUEUED' AND lane = :lane AND run_after <= :now
                                 ORDER BY id
                                 LIMIT 1
                                 FOR UPDATE SKIP LOCKED)
                        RETURNING\s""" + COLUMNS)
                .param("lockToken", lockToken)
                .param("lane", lane.name())
                .param("now", ts(now))
                .query(ROW_MAPPER)
                .optional();
    }

    /** 완료 처리. 0행 = 이미 회수당함 → 호출부는 결과를 버림 (§5.4). */
    public boolean markDone(long jobId, String lockToken) {
        return jdbc.sql("""
                        UPDATE processing_jobs SET state = 'DONE'
                         WHERE id = :id AND locked_by = :lockToken AND state = 'RUNNING'
                        """)
                .param("id", jobId)
                .param("lockToken", lockToken)
                .update() == 1;
    }

    /**
     * 일시적 실패 → 재시도 예약. attempt 가 상한에 닿으면 DEAD. 판정을 SQL CASE 로 — 행의
     * 현재 attempt 기준이라 읽고 쓰는 사이 경합 없음.
     *
     * @return 전이 후 상태. 소유권을 잃었으면 empty
     */
    public Optional<JobState> retryLater(long jobId, String lockToken, Instant runAfter, String error) {
        return jdbc.sql("""
                        UPDATE processing_jobs
                           SET state = CASE WHEN attempt + 1 >= max_attempts THEN 'DEAD' ELSE 'QUEUED' END,
                               attempt = attempt + 1,
                               run_after = :runAfter,
                               locked_by = NULL, locked_at = NULL,
                               last_error = :error
                         WHERE id = :id AND locked_by = :lockToken AND state = 'RUNNING'
                        RETURNING state
                        """)
                .param("id", jobId)
                .param("lockToken", lockToken)
                .param("runAfter", ts(runAfter))
                .param("error", error)
                .query((rs, n) -> JobState.valueOf(rs.getString("state")))
                .optional();
    }

    /** 영구 실패 → 즉시 DEAD (손상 파일·도구 부재 등, §5.5). 소유권을 잃었으면 false. */
    public boolean markDead(long jobId, String lockToken, String error) {
        return jdbc.sql("""
                        UPDATE processing_jobs
                           SET state = 'DEAD', attempt = attempt + 1, last_error = :error,
                               locked_by = NULL, locked_at = NULL
                         WHERE id = :id AND locked_by = :lockToken AND state = 'RUNNING'
                        """)
                .param("id", jobId)
                .param("lockToken", lockToken)
                .param("error", error)
                .update() == 1;
    }

    /**
     * 하트비트. 처리 중인 작업의 locked_at 갱신 → 긴 작업도 짧은 lease 로 안전 (§5.2).
     * 0행 = 이미 회수당함.
     */
    public boolean heartbeat(long jobId, String lockToken, Instant now) {
        return jdbc.sql("""
                        UPDATE processing_jobs SET locked_at = :now
                         WHERE id = :id AND locked_by = :lockToken AND state = 'RUNNING'
                        """)
                .param("now", ts(now))
                .param("id", jobId)
                .param("lockToken", lockToken)
                .update() == 1;
    }

    /**
     * lease 만료 작업 회수 (§5.3). attempt 를 올리는 게 핵심 — 워커를 반복해서 죽이는 파일
     * (메모리 폭발 영상 등)이 무한 회수 루프 대신 max_attempts 에서 DEAD.
     *
     * 대상 행을 id 순 SKIP LOCKED 로 먼저 고름 — 여러 워커 프로세스의 회수기가 동시에 돌 때 잠금
     * 순서가 엇갈려 교착하지 않게. PROBE 가 여기서 DEAD 가 되면 같은 문장에서 자산을 FAILED 로
     * (JobWorker.onDead 와 같은 규칙 — 살아 있는 작업 없이 INGESTED 에 멈추지 않게).
     *
     * @return 회수(또는 DEAD 처리)한 행 수
     */
    public int reapExpired(Instant now, Duration lease) {
        return jdbc.sql("""
                        WITH reaped AS (
                            UPDATE processing_jobs
                               SET state = CASE WHEN attempt + 1 >= max_attempts THEN 'DEAD' ELSE 'QUEUED' END,
                                   attempt = attempt + 1,
                                   run_after = :now,
                                   locked_by = NULL, locked_at = NULL,
                                   last_error = 'worker lease expired'
                             WHERE id IN (
                                    SELECT id FROM processing_jobs
                                     WHERE state = 'RUNNING' AND locked_at < :cutoff
                                     ORDER BY id
                                     FOR UPDATE SKIP LOCKED)
                            RETURNING asset_id, stage, state
                        ), failed AS (
                            UPDATE media_assets SET status = 'FAILED'
                             WHERE id IN (SELECT asset_id FROM reaped WHERE stage = 'PROBE' AND state = 'DEAD')
                        )
                        SELECT count(*) FROM reaped
                        """)
                .param("now", ts(now))
                .param("cutoff", ts(now.minus(lease)))
                .query(Integer.class)
                .single();
    }

    /** 자산에 살아 있는(QUEUED/RUNNING) 작업이 있는지. 화면이 "처리 중"을 멈출 기준. */
    public boolean hasActiveJob(UUID assetId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM processing_jobs
                                        WHERE asset_id = :assetId AND state IN ('QUEUED', 'RUNNING'))
                        """)
                .param("assetId", assetId)
                .query(Boolean.class)
                .single();
    }

    public Optional<ProcessingJob> findById(long jobId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM processing_jobs WHERE id = :id")
                .param("id", jobId)
                .query(ROW_MAPPER)
                .optional();
    }

    /** DB 서버 시계. 시계 오차 검사 전용 (ClockSkewGuard) — 큐 로직엔 쓰지 않음. */
    public Instant databaseNow() {
        return jdbc.sql("SELECT clock_timestamp()")
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .single();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static ProcessingJob mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ProcessingJob(
                rs.getLong("id"),
                rs.getObject("asset_id", UUID.class),
                Stage.valueOf(rs.getString("stage")),
                Lane.valueOf(rs.getString("lane")),
                JobState.valueOf(rs.getString("state")),
                rs.getInt("attempt"),
                rs.getInt("max_attempts"),
                instant(rs, "run_after"),
                rs.getString("locked_by"),
                instant(rs, "locked_at"),
                rs.getString("last_error"),
                instant(rs, "created_at"));
    }
}
