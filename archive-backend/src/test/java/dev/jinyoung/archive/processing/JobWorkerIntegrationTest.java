package dev.jinyoung.archive.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import dev.jinyoung.archive.auth.UserAccountService;
import dev.jinyoung.archive.media.ContentAddressedStore;
import dev.jinyoung.archive.media.DerivativeRecipe;
import dev.jinyoung.archive.media.MediaAsset;
import dev.jinyoung.archive.media.MediaAssetRepository;
import dev.jinyoung.archive.media.MediaAssetStatus;
import dev.jinyoung.archive.media.MediaDerivativeRepository;
import dev.jinyoung.archive.media.ObjectStorage;
import dev.jinyoung.archive.media.StorageKeys;
import dev.jinyoung.archive.media.StoredOriginal;
import dev.jinyoung.archive.processing.job.Backoff;
import dev.jinyoung.archive.processing.job.ClockSkewGuard;
import dev.jinyoung.archive.processing.job.JobQueue;
import dev.jinyoung.archive.processing.job.JobState;
import dev.jinyoung.archive.processing.job.Lane;
import dev.jinyoung.archive.processing.job.ProcessingJob;
import dev.jinyoung.archive.processing.job.Stage;
import dev.jinyoung.archive.processing.job.WorkerProperties;
import dev.jinyoung.archive.processing.thumb.Thumbnailer;
import dev.jinyoung.archive.support.IntegrationTest;

/**
 * 워커가 큐에서 작업을 잡아 PROBE → THUMB 을 끝까지 처리하는지. 실제 Postgres·MinIO 위.
 *
 * 기본은 루프 없이 {@link JobWorker#drain} 직접 호출 — 결정론. 루프 자체는 마지막 테스트에서
 * {@link WorkerLoop} 를 손으로 만들어 확인 (컨텍스트 재기동 없이).
 *
 * THUMB 단정은 libvips 게이트 ({@code assumeTrue}). 없으면 THUMB 작업이 DEAD, 자산은 PROBED.
 */
class JobWorkerIntegrationTest extends IntegrationTest {

    private static final DerivativeRecipe THUMB = DerivativeRecipe.THUMB_256;
    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    private JobWorker worker;
    @Autowired
    private JobQueue queue;
    @Autowired
    private ContentAddressedStore store;
    @Autowired
    private ObjectStorage storage;
    @Autowired
    private MediaAssetRepository assets;
    @Autowired
    private MediaDerivativeRepository derivatives;
    @Autowired
    private Thumbnailer thumbnailer;
    @Autowired
    private UserAccountService userAccounts;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private Clock clock;
    @Autowired
    private ProbeStage probeStage;
    @Autowired
    private JdbcAggregateOperations jdbcOps;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private WorkerProperties workerProperties;

    private UUID ownerId;

    @BeforeEach
    void 정리한다() {
        assets.deleteAll();     // media_derivatives·processing_jobs 는 FK ON DELETE CASCADE
        storage.listKeys(StorageKeys.ORIGINALS_PREFIX).forEach(storage::delete);
        storage.listKeys(StorageKeys.DERIVATIVES_PREFIX).forEach(storage::delete);
        ownerId = userAccounts.findOrCreate("GOOGLE", "worker-sub", "w@example.com", "워커").id();
    }

    @Test
    @DisplayName("PROBE 가 끝나면 THUMB 이 이어서 등록되고, drain 하면 THUMBED 까지 간다")
    void 단계_연쇄() throws Exception {
        UUID id = ingest(jpeg(1024, 768, 1));

        int processed = worker.drain(Lane.PHOTO);

        assertThat(processed).isEqualTo(2);    // PROBE + THUMB
        MediaAsset asset = assets.findById(id).orElseThrow();
        assertThat(asset.width()).isEqualTo(1024);
        assertThat(asset.height()).isEqualTo(768);
        assertThat(jobStates(id, Stage.PROBE)).containsExactly(JobState.DONE);

        assumeTrue(thumbnailer.isAvailable(), "libvips 미설치 — THUMB 단정 스킵");

        assertThat(asset.status()).isEqualTo(MediaAssetStatus.THUMBED);
        assertThat(jobStates(id, Stage.THUMB)).containsExactly(JobState.DONE);
        var thumb = derivatives.findByAssetIdAndRecipeAndRecipeVer(id, THUMB.recipeName(), THUMB.version())
                .orElseThrow();
        assertThat(storage.exists(thumb.storageKey())).isTrue();
    }

    @Test
    @DisplayName("손상 파일은 재시도 없이 PROBE 작업 DEAD + 자산 FAILED, THUMB 은 등록되지 않는다")
    void 영구_실패() throws Exception {
        byte[] garbage = new byte[4096];
        new Random(20260928L).nextBytes(garbage);
        UUID id = ingest(garbage);

        worker.drain(Lane.PHOTO);

        assertThat(assets.findById(id).orElseThrow().status()).isEqualTo(MediaAssetStatus.FAILED);
        ProcessingJob probe = onlyJob(id, Stage.PROBE);
        assertThat(probe.state()).isEqualTo(JobState.DEAD);
        assertThat(probe.attempt()).isEqualTo(1);       // 한 번에 포기 — 영구 실패를 5회 헛돌리지 않음
        assertThat(jobStates(id, Stage.THUMB)).isEmpty();
    }

    @Test
    @DisplayName("스토리지 오류는 일시적 실패 — 작업이 attempt+1 로 미래에 재예약되고 자산은 그대로")
    void 일시적_실패() throws Exception {
        UUID id = ingest(jpeg(320, 240, 2));
        // 원본을 지워 내려받기를 실패시킴 (스토리지 장애 흉내).
        storage.delete(assets.findById(id).orElseThrow().storageKey());

        Instant before = clock.instant();
        assertThat(worker.runOnce(Lane.PHOTO)).isTrue();
        Instant after = clock.instant();

        ProcessingJob probe = onlyJob(id, Stage.PROBE);
        assertThat(probe.state()).isEqualTo(JobState.QUEUED);
        assertThat(probe.attempt()).isEqualTo(1);
        assertThat(probe.lockedBy()).isNull();
        assertThat(probe.lastError()).isNotBlank();
        // 첫 재시도 지연은 [0, 1s) — 실패 시각 + 지터 구간 안.
        assertThat(probe.runAfter()).isBetween(before, after.plus(Duration.ofSeconds(1)));
        // run_after 전엔 안 잡힘.
        assertThat(queue.claim(Lane.PHOTO, "early#1", probe.runAfter().minusMillis(1))).isEmpty();
        assertThat(assets.findById(id).orElseThrow().status()).isEqualTo(MediaAssetStatus.INGESTED);
    }

    /**
     * I9 — 어떤 stage 도 두 번 실행되면 최종 상태가 같다 (roadmap.md §4).
     *
     * lease 회수로 "끝났는데 다시 도는" 상황을 재현: 끝까지 처리한 뒤 같은 단계를 다시 등록해 돌림.
     * PROBE 재실행은 THUMB 까지 다시 끌고 옴 — 그래도 스냅샷(자산 행·파생물 행·스토리지 키)이 같아야.
     * 특히 THUMBED 뒤의 PROBE 가 상태를 PROBED 로 되돌리지 않는지가 핵심.
     */
    @ParameterizedTest(name = "I9: {0} 재실행")
    @EnumSource(value = Stage.class, names = {"PROBE", "THUMB"})
    @DisplayName("I9 — 같은 단계를 두 번 실행해도 최종 상태가 같다")
    void I9_멱등(Stage stage) throws Exception {
        if (stage == Stage.THUMB) {
            assumeTrue(thumbnailer.isAvailable(), "libvips 미설치 — THUMB 재실행 스킵");
        }
        UUID id = ingest(jpeg(640, 480, 3));
        worker.drain(Lane.PHOTO);
        Snapshot first = snapshot(id);

        assertThat(queue.enqueue(id, stage, Lane.PHOTO, MAX_ATTEMPTS, clock.instant())).isTrue();
        worker.drain(Lane.PHOTO);
        Snapshot second = snapshot(id);

        assertThat(second).isEqualTo(first);
    }

    /**
     * I9 스냅샷 비교만으론 못 잡는 경우. PROBE 재실행이 THUMB 을 다시 끌고 와 최종 상태는 복구되지만,
     * 그 사이 THUMBED → PROBED 역행이 보이면 화면 썸네일이 깜빡임. PROBE 하나만 돌리고 바로 확인.
     */
    @Test
    @DisplayName("THUMBED 뒤 늦게 도는 중복 PROBE 는 상태를 되돌리지 않는다")
    void 상태_역행_없음() throws Exception {
        assumeTrue(thumbnailer.isAvailable(), "libvips 미설치 — THUMBED 도달 불가");
        UUID id = ingest(jpeg(400, 300, 7));
        worker.drain(Lane.PHOTO);
        assertThat(assets.findById(id).orElseThrow().status()).isEqualTo(MediaAssetStatus.THUMBED);

        queue.enqueue(id, Stage.PROBE, Lane.PHOTO, MAX_ATTEMPTS, clock.instant());
        worker.runOnce(Lane.PHOTO);     // PROBE 만. 뒤따라 등록된 THUMB 은 아직 안 돌림

        assertThat(assets.findById(id).orElseThrow().status()).isEqualTo(MediaAssetStatus.THUMBED);
    }

    @Test
    @DisplayName("워커 루프를 켜면 백그라운드에서 큐를 비운다")
    void 워커_루프() throws Exception {
        List<UUID> ids = List.of(ingest(jpeg(200, 200, 4)), ingest(jpeg(300, 200, 5)), ingest(jpeg(200, 300, 6)));
        WorkerProperties fast = new WorkerProperties(
                true, null, List.of(Lane.PHOTO), 2, Duration.ofMillis(50), null, null, null, null);
        // 시계 검사는 스텁 — 원격 Docker 호스트 시계에 테스트가 좌우되지 않게. 검사 자체는 ClockSkewGuardTest.
        WorkerLoop loop = new WorkerLoop(worker, fast, new ClockSkewGuard(clock, clock::instant, Duration.ofSeconds(30)));

        loop.start();
        try {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(ids).allSatisfy(id -> assertThat(jobStates(id, Stage.THUMB))
                            .containsExactly(thumbnailer.isAvailable() ? JobState.DONE : JobState.DEAD)));
        } finally {
            loop.stop();
        }
        assertThat(ids).allSatisfy(id ->
                assertThat(assets.findById(id).orElseThrow().status())
                        .isIn(MediaAssetStatus.PROBED, MediaAssetStatus.THUMBED));
    }

    /**
     * 소유권 상실 end-to-end (§5.4). 처리 도중 lease 가 만료돼 회수되면, 뒤늦게 끝난 결과는 자산·
     * 파생물·다음 단계 어디에도 반영되지 않아야. 처리기를 감싸 run() 중간에 회수를 끼워 넣음.
     */
    @Test
    @DisplayName("처리 중 회수당한 워커의 결과는 버려진다 — 자산·다음 단계 반영 없음")
    void 소유권_상실_결과_폐기() throws Exception {
        UUID id = ingest(jpeg(320, 240, 8));
        StageProcessor reapingProbe = new StageProcessor() {
            @Override
            public Stage stage() {
                return Stage.PROBE;
            }

            @Override
            public StageOutcome run(MediaAsset asset) {
                StageOutcome outcome = probeStage.run(asset);
                queue.reapExpired(clock.instant().plus(Duration.ofMinutes(10)), Duration.ofMinutes(2));
                return outcome;
            }
        };
        JobWorker slow = workerWith(List.of(reapingProbe));

        assertThat(slow.runOnce(Lane.PHOTO)).isTrue();

        MediaAsset asset = assets.findById(id).orElseThrow();
        assertThat(asset.status()).isEqualTo(MediaAssetStatus.INGESTED);
        assertThat(asset.width()).isNull();
        assertThat(jobStates(id, Stage.THUMB)).isEmpty();
        ProcessingJob probe = onlyJob(id, Stage.PROBE);
        assertThat(probe.state()).isEqualTo(JobState.QUEUED);   // 회수돼 다시 대기
        assertThat(probe.attempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("처리기 없는 단계는 재시도 없이 DEAD, PROBE 가 DEAD 면 자산 FAILED")
    void 처리기_부재() throws Exception {
        UUID id = ingest(jpeg(320, 240, 9));
        JobWorker noProbe = workerWith(List.of());

        noProbe.drain(Lane.PHOTO);

        ProcessingJob probe = onlyJob(id, Stage.PROBE);
        assertThat(probe.state()).isEqualTo(JobState.DEAD);
        assertThat(probe.attempt()).isEqualTo(1);
        assertThat(probe.lockedBy()).isNull();
        // 살아 있는 작업 없이 INGESTED 에 멈추지 않음.
        assertThat(assets.findById(id).orElseThrow().status()).isEqualTo(MediaAssetStatus.FAILED);
        assertThat(queue.hasActiveJob(id)).isFalse();
    }

    @Test
    @DisplayName("lease 회수로 PROBE 가 소진돼 DEAD 가 되면 자산도 FAILED")
    void 회수_소진_시_자산_FAILED() throws Exception {
        UUID id = ingest(jpeg(320, 240, 10));
        Instant now = clock.instant();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            queue.claim(Lane.PHOTO, "crashed#" + i, now).orElseThrow();
            now = now.plus(Duration.ofMinutes(3));
            queue.reapExpired(now, Duration.ofMinutes(2));
        }

        assertThat(onlyJob(id, Stage.PROBE).state()).isEqualTo(JobState.DEAD);
        assertThat(assets.findById(id).orElseThrow().status()).isEqualTo(MediaAssetStatus.FAILED);
    }

    /** 처리기만 바꾼 JobWorker. 나머지 협력자는 실제 빈. */
    private JobWorker workerWith(List<StageProcessor> processors) {
        return new JobWorker(queue, processors, assets, derivatives, jdbcOps, tx,
                new Backoff(new Random(20260928L)), workerProperties, clock);
    }

    /** 업로드가 하는 일 흉내: 원본 저장 → INGESTED 행 → PROBE 등록. */
    private UUID ingest(byte[] content) throws Exception {
        Path temp = Files.createTempFile("test-original-", ".jpg");
        try {
            Files.write(temp, content);
            StoredOriginal stored = store.storeOriginal(temp, "image/jpeg");
            UUID id = UUID.randomUUID();
            assets.insertIngestedIfAbsent(id, ownerId, stored.hash().hex(), stored.byteSize(),
                    "image/jpeg", "PHOTO", stored.storageKey(), clock.instant());
            queue.enqueue(id, Stage.PROBE, Lane.PHOTO, MAX_ATTEMPTS, clock.instant());
            return id;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private List<JobState> jobStates(UUID assetId, Stage stage) {
        return jdbc.sql("SELECT state FROM processing_jobs WHERE asset_id = :a AND stage = :s ORDER BY id")
                .param("a", assetId)
                .param("s", stage.name())
                .query((rs, n) -> JobState.valueOf(rs.getString(1)))
                .list();
    }

    private ProcessingJob onlyJob(UUID assetId, Stage stage) {
        long jobId = jdbc.sql("SELECT id FROM processing_jobs WHERE asset_id = :a AND stage = :s")
                .param("a", assetId)
                .param("s", stage.name())
                .query(Long.class)
                .single();
        return queue.findById(jobId).orElseThrow();
    }

    /** I9 비교 대상. 파생물은 id·created_at 제외 — 행 정체성이 아니라 내용이 같아야. */
    private Snapshot snapshot(UUID assetId) {
        MediaAsset asset = assets.findById(assetId).orElseThrow();
        Set<String> derived = new TreeSet<>();
        derivatives.findByAssetIdAndRecipeAndRecipeVer(assetId, THUMB.recipeName(), THUMB.version())
                .ifPresent(d -> derived.add(d.recipe() + "|" + d.recipeVer() + "|" + d.storageKey()
                        + "|" + d.byteSize() + "|" + d.contentHash()));
        Set<String> keys = new TreeSet<>(storage.listKeys(StorageKeys.DERIVATIVES_PREFIX));
        return new Snapshot(asset, derived, keys);
    }

    private record Snapshot(MediaAsset asset, Set<String> derivatives, Set<String> storageKeys) {
    }

    /** 유효 JPEG. {@code shade} 로 내용을 달리해 해시가 겹치지 않게. */
    private byte[] jpeg(int width, int height, int shade) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(100 + shade * 10, 120, 60));
        g.fillRect(0, 0, width, height);
        g.dispose();
        Path temp = Files.createTempFile("jpeg-", ".jpg");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                ImageIO.write(img, "jpg", out);
            }
            return Files.readAllBytes(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
