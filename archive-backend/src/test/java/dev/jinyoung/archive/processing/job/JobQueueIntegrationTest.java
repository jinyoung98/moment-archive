package dev.jinyoung.archive.processing.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.jinyoung.archive.auth.UserAccountService;
import dev.jinyoung.archive.media.MediaAssetRepository;
import dev.jinyoung.archive.support.IntegrationTest;

/**
 * 큐 SQL 을 실제 Postgres 위에서 검증. 시각은 전부 명시적 Instant — 시계를 돌려 백오프·lease 를
 * 기다림 없이 확인 (ADR A24). 워커 없이 JobQueue 만 직접 호출.
 */
class JobQueueIntegrationTest extends IntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-28T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final int MAX_ATTEMPTS = 3;

    @Autowired
    private JobQueue queue;
    @Autowired
    private MediaAssetRepository assets;
    @Autowired
    private UserAccountService userAccounts;
    @Autowired
    private JdbcClient jdbc;

    private UUID ownerId;
    private int assetSeq;

    @BeforeEach
    void 정리한다() {
        assets.deleteAll();     // processing_jobs 는 FK ON DELETE CASCADE
        ownerId = userAccounts.findOrCreate("GOOGLE", "queue-sub", "q@example.com", "큐").id();
    }

    @Test
    @DisplayName("run_after 가 지난 QUEUED 작업만, 해당 레인에서, 등록 순으로 잡힌다")
    void 획득_조건() {
        long first = enqueue(Stage.PROBE, Lane.PHOTO, T0);
        enqueue(Stage.PROBE, Lane.VIDEO, T0);                    // 다른 레인
        long second = enqueue(Stage.PROBE, Lane.PHOTO, T0);

        assertThat(queue.claim(Lane.PHOTO, "w#1", T0)).map(ProcessingJob::id).contains(first);
        ProcessingJob job = queue.claim(Lane.PHOTO, "w#2", T0).orElseThrow();
        assertThat(job.id()).isEqualTo(second);
        assertThat(job.state()).isEqualTo(JobState.RUNNING);
        assertThat(job.lockedBy()).isEqualTo("w#2");
        assertThat(job.lockedAt()).isEqualTo(T0);
        assertThat(queue.claim(Lane.PHOTO, "w#3", T0)).isEmpty();  // 남은 건 VIDEO 뿐
    }

    @Test
    @DisplayName("동시에 여러 워커가 잡아도 한 작업은 정확히 한 번만 잡힌다 (SKIP LOCKED)")
    void 동시_획득() throws Exception {
        int jobs = 40;
        int workers = 8;
        for (int i = 0; i < jobs; i++) {
            enqueue(Stage.PROBE, Lane.PHOTO, T0);
        }

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> results = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            String token = "w" + w;
            results.add(pool.submit(() -> {
                start.await();
                List<Long> mine = new ArrayList<>();
                Optional<ProcessingJob> job;
                int n = 0;
                while ((job = queue.claim(Lane.PHOTO, token + "#" + n++, T0)).isPresent()) {
                    mine.add(job.get().id());
                }
                return mine;
            }));
        }
        start.countDown();

        List<Long> all = Collections.synchronizedList(new ArrayList<>());
        for (Future<List<Long>> r : results) {
            all.addAll(r.get());
        }
        pool.shutdown();

        assertThat(all).hasSize(jobs);
        assertThat(new HashSet<>(all)).hasSize(jobs);   // 중복 획득 없음
    }

    @Test
    @DisplayName("잠금 토큰이 다르면 완료·실패 처리가 먹지 않는다 (소유권 확인)")
    void 소유권_확인() {
        long id = enqueue(Stage.PROBE, Lane.PHOTO, T0);
        queue.claim(Lane.PHOTO, "owner#1", T0).orElseThrow();

        assertThat(queue.markDone(id, "stranger#9")).isFalse();
        assertThat(queue.markDead(id, "stranger#9", "x")).isFalse();
        assertThat(queue.retryLater(id, "stranger#9", T0, "x")).isEmpty();
        assertThat(queue.heartbeat(id, "stranger#9", T0)).isFalse();
        assertThat(state(id)).isEqualTo(JobState.RUNNING);

        assertThat(queue.markDone(id, "owner#1")).isTrue();
        assertThat(state(id)).isEqualTo(JobState.DONE);
        assertThat(queue.markDone(id, "owner#1")).isFalse();    // 두 번째 완료는 0행
    }

    @Test
    @DisplayName("재시도 예약은 run_after 전엔 안 잡히고, max_attempts 에 닿으면 DEAD")
    void 재시도와_소진() {
        long id = enqueue(Stage.THUMB, Lane.PHOTO, T0);

        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            Instant now = T0.plusSeconds(attempt * 100L);
            queue.claim(Lane.PHOTO, "w#" + attempt, now).orElseThrow();
            Instant runAfter = now.plusSeconds(10);

            assertThat(queue.retryLater(id, "w#" + attempt, runAfter, "boom")).contains(JobState.QUEUED);
            assertThat(queue.claim(Lane.PHOTO, "early", runAfter.minusMillis(1))).isEmpty();
        }

        Instant last = T0.plusSeconds(1_000);
        queue.claim(Lane.PHOTO, "w#last", last).orElseThrow();
        assertThat(queue.retryLater(id, "w#last", last.plusSeconds(10), "boom")).contains(JobState.DEAD);

        ProcessingJob dead = queue.findById(id).orElseThrow();
        assertThat(dead.attempt()).isEqualTo(MAX_ATTEMPTS);
        assertThat(dead.lastError()).isEqualTo("boom");
        assertThat(queue.claim(Lane.PHOTO, "w#after", last.plusSeconds(100_000))).isEmpty();
    }

    @Test
    @DisplayName("lease 가 지난 RUNNING 은 회수되고, 하트비트가 도는 작업은 회수되지 않는다")
    void 회수와_하트비트() {
        long dead = enqueue(Stage.PROBE, Lane.PHOTO, T0);
        long alive = enqueue(Stage.PROBE, Lane.PHOTO, T0);
        queue.claim(Lane.PHOTO, "crashed#1", T0).orElseThrow();
        queue.claim(Lane.PHOTO, "alive#1", T0).orElseThrow();

        // 30초마다 하트비트 — alive 만.
        for (int s = 30; s <= 150; s += 30) {
            assertThat(queue.heartbeat(alive, "alive#1", T0.plusSeconds(s))).isTrue();
        }

        Instant now = T0.plusSeconds(150);    // crashed 는 150초 무소식 > lease 120초
        assertThat(queue.reapExpired(now, LEASE)).isEqualTo(1);

        ProcessingJob reaped = queue.findById(dead).orElseThrow();
        assertThat(reaped.state()).isEqualTo(JobState.QUEUED);
        assertThat(reaped.attempt()).isEqualTo(1);
        assertThat(reaped.lockedBy()).isNull();
        assertThat(reaped.lastError()).isEqualTo("worker lease expired");
        assertThat(state(alive)).isEqualTo(JobState.RUNNING);

        // 회수당한 워커의 뒤늦은 완료는 버려짐.
        assertThat(queue.markDone(dead, "crashed#1")).isFalse();
    }

    @Test
    @DisplayName("워커를 반복해서 죽이는 작업은 무한 회수 대신 max_attempts 에서 DEAD")
    void 독성_작업() {
        long id = enqueue(Stage.PROBE, Lane.PHOTO, T0);
        Instant now = T0;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            queue.claim(Lane.PHOTO, "w#" + i, now).orElseThrow();
            now = now.plus(LEASE).plusSeconds(1);
            queue.reapExpired(now, LEASE);
        }
        assertThat(state(id)).isEqualTo(JobState.DEAD);
        assertThat(queue.claim(Lane.PHOTO, "w#x", now)).isEmpty();
    }

    @Test
    @DisplayName("같은 자산·단계의 살아 있는 작업은 하나만 등록되고, 끝난 뒤엔 다시 등록된다")
    void 중복_등록_흡수() {
        UUID assetId = newAsset();

        assertThat(queue.enqueue(assetId, Stage.THUMB, Lane.PHOTO, MAX_ATTEMPTS, T0)).isTrue();
        assertThat(queue.enqueue(assetId, Stage.THUMB, Lane.PHOTO, MAX_ATTEMPTS, T0)).isFalse();
        assertThat(queue.enqueue(assetId, Stage.PROBE, Lane.PHOTO, MAX_ATTEMPTS, T0)).isTrue(); // 단계가 다르면 별개

        ProcessingJob thumb = queue.claim(Lane.PHOTO, "w#1", T0).orElseThrow();
        assertThat(queue.enqueue(assetId, Stage.THUMB, Lane.PHOTO, MAX_ATTEMPTS, T0)).isFalse(); // RUNNING 도 살아 있음
        queue.markDone(thumb.id(), "w#1");
        assertThat(queue.enqueue(assetId, Stage.THUMB, Lane.PHOTO, MAX_ATTEMPTS, T0)).isTrue();  // DONE 은 이력
    }

    @Test
    @DisplayName("DB 시계를 읽을 수 있다 (시계 오차 검사용)")
    void DB_시계() {
        assertThat(queue.databaseNow()).isNotNull();
    }

    private long enqueue(Stage stage, Lane lane, Instant now) {
        UUID assetId = newAsset();
        assertThat(queue.enqueue(assetId, stage, lane, MAX_ATTEMPTS, now)).isTrue();
        return jdbc.sql("SELECT id FROM processing_jobs WHERE asset_id = :a AND stage = :s")
                .param("a", assetId)
                .param("s", stage.name())
                .query(Long.class)
                .single();
    }

    private UUID newAsset() {
        UUID id = UUID.randomUUID();
        String hash = String.format("%064x", ++assetSeq);
        assets.insertIngestedIfAbsent(id, ownerId, hash, 1, "image/jpeg", "PHOTO",
                "originals/test/" + hash, T0);
        return id;
    }

    private JobState state(long id) {
        return queue.findById(id).orElseThrow().state();
    }
}
