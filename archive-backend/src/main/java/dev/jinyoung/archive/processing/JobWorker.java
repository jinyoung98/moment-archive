package dev.jinyoung.archive.processing;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import dev.jinyoung.archive.media.MediaAsset;
import dev.jinyoung.archive.media.MediaAssetRepository;
import dev.jinyoung.archive.media.MediaAssetStatus;
import dev.jinyoung.archive.media.MediaDerivativeRepository;
import dev.jinyoung.archive.processing.job.Backoff;
import dev.jinyoung.archive.processing.job.JobQueue;
import dev.jinyoung.archive.processing.job.JobState;
import dev.jinyoung.archive.processing.job.Lane;
import dev.jinyoung.archive.processing.job.ProcessingJob;
import dev.jinyoung.archive.processing.job.Stage;
import dev.jinyoung.archive.processing.job.WorkerProperties;
import dev.jinyoung.archive.processing.probe.ProbeResult;
import dev.jinyoung.archive.processing.probe.UnreadableMediaException;

/**
 * 작업 하나를 잡아 끝까지 처리 (pipeline.md §5). 스레드·스케줄은 모름 — 그건 {@link WorkerLoop}.
 * 테스트는 루프 없이 {@link #drain} 을 직접 호출해 결정론적으로 돌림.
 *
 * 한 작업의 흐름:
 * 1. 획득 — 자동 커밋 단일 문장 (SKIP LOCKED). 잠금 토큰 발급
 * 2. 처리 — 트랜잭션 밖. 다운로드·libvips 동안 DB 커넥션·락을 잡지 않음
 * 3. 반영 — 한 트랜잭션: 소유권 확인(markDone) → 자산 갱신 → 다음 단계 등록.
 *    소유권을 잃었으면 결과를 버림. "메타는 저장됐는데 다음 작업이 없음" 상태가 존재 불가 (§5.7)
 *
 * 처리 중 프로세스가 죽으면 작업은 RUNNING 에 남고 lease 만료 후 회수 → 재실행. 그래서 모든
 * 반영이 멱등이어야 함 (I9): 상태는 전진만, 파생물은 UPSERT, 다음 단계 등록은 중복 무시.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final int MAX_ERROR_LENGTH = 1_000;

    private final JobQueue queue;
    private final Map<Stage, StageProcessor> processors;
    private final MediaAssetRepository assets;
    private final MediaDerivativeRepository derivatives;
    private final JdbcAggregateOperations jdbcOps;
    private final TransactionTemplate tx;
    private final Backoff backoff;
    private final WorkerProperties properties;
    private final Clock clock;

    private final String workerId;
    private final AtomicLong claimSequence = new AtomicLong();
    /** 처리 중인 작업 → 잠금 토큰. 하트비트 대상. */
    private final Map<Long, String> active = new ConcurrentHashMap<>();

    public JobWorker(
            JobQueue queue,
            List<StageProcessor> processors,
            MediaAssetRepository assets,
            MediaDerivativeRepository derivatives,
            JdbcAggregateOperations jdbcOps,
            TransactionTemplate tx,
            Backoff backoff,
            WorkerProperties properties,
            Clock clock) {
        this.queue = queue;
        this.processors = processors.stream().collect(Collectors.toMap(StageProcessor::stage, Function.identity()));
        this.assets = assets;
        this.derivatives = derivatives;
        this.jdbcOps = jdbcOps;
        this.tx = tx;
        this.backoff = backoff;
        this.properties = properties;
        this.clock = clock;
        this.workerId = properties.id() != null && !properties.id().isBlank() ? properties.id() : defaultWorkerId();
    }

    /** 실행 가능한 작업이 없을 때까지 처리. @return 처리한 작업 수 */
    public int drain(Lane lane) {
        return drain(lane, () -> true);
    }

    /**
     * {@code keepGoing} 이 false 가 되면 새 작업을 잡지 않고 멈춤. 종료 중 워커가 백로그를 계속
     * 잡아 종료 유예를 넘기면 claim 만 커밋된 작업이 RUNNING 으로 남고, 회수마다 attempt 가 올라
     * 배포를 반복할수록 멀쩡한 파일이 DEAD 에 가까워짐.
     */
    public int drain(Lane lane, BooleanSupplier keepGoing) {
        int count = 0;
        while (keepGoing.getAsBoolean() && runOnce(lane)) {
            count++;
        }
        return count;
    }

    /** 작업 하나. @return 잡은 작업이 있었으면 true */
    public boolean runOnce(Lane lane) {
        String lockToken = workerId + "#" + claimSequence.incrementAndGet();
        Optional<ProcessingJob> claimed = queue.claim(lane, lockToken, clock.instant());
        if (claimed.isEmpty()) {
            return false;
        }
        ProcessingJob job = claimed.get();
        active.put(job.id(), lockToken);
        try {
            process(job, lockToken);
        } finally {
            active.remove(job.id());
        }
        return true;
    }

    /** 처리 중인 작업의 lease 연장. WorkerLoop 가 주기 호출. */
    public void heartbeat() {
        Instant now = clock.instant();
        active.forEach((jobId, token) -> {
            // 순회 중 방금 끝나 active 에서 빠진 작업은 0행이 정상 — 실제 회수와 구분해 경고.
            if (!queue.heartbeat(jobId, token, now) && token.equals(active.get(jobId))) {
                // 이미 회수당함. 처리는 계속 — 끝날 때 소유권 확인에서 결과가 버려짐.
                log.warn("하트비트 실패, 작업 회수됨: job={}", jobId);
            }
        });
    }

    /** lease 만료 작업 회수. 어느 워커 프로세스가 돌려도 됨 (조건부 UPDATE 라 중복 무해). */
    public int reapExpired() {
        int reaped = queue.reapExpired(clock.instant(), properties.lease());
        if (reaped > 0) {
            log.warn("lease 만료 작업 {}건 회수", reaped);
        }
        return reaped;
    }

    public String workerId() {
        return workerId;
    }

    private void process(ProcessingJob job, String lockToken) {
        try {
            Optional<MediaAsset> asset = assets.findById(job.assetId());
            if (asset.isEmpty()) {
                // 자산이 지워지면 작업도 CASCADE 로 지워지므로 사실상 경합 창에서만. 할 일 없음.
                tx.executeWithoutResult(s -> queue.markDone(job.id(), lockToken));
                return;
            }
            StageOutcome outcome = processorFor(job.stage()).run(asset.get());
            tx.executeWithoutResult(s -> commit(job, lockToken, outcome));
        } catch (UnreadableMediaException e) {
            // 손상·미지원 = 영구. 원본은 보존, 처리만 FAILED (§5.5).
            log.warn("영구 실패, 자산 FAILED: job={} asset={} ({})", job.id(), job.assetId(), e.getMessage());
            tx.executeWithoutResult(s -> {
                if (queue.markDead(job.id(), lockToken, describe(e))) {
                    updateAsset(job.assetId(), a -> a.withStatus(MediaAssetStatus.FAILED));
                }
            });
        } catch (ToolUnavailableException e) {
            log.warn("도구 부재, 작업 DEAD: job={} ({})", job.id(), e.getMessage());
            tx.executeWithoutResult(s -> {
                if (queue.markDead(job.id(), lockToken, describe(e))) {
                    onDead(job);
                }
            });
        } catch (RuntimeException e) {
            retryLater(job, lockToken, e);
        } catch (Error e) {
            // OOM·StackOverflow — 독성 파일일 수 있음. 가능하면 attempt 를 올려 max_attempts 방어가
            // 먹게 하고 다시 던짐. DB 호출마저 실패하면 RUNNING 으로 남아 lease 회수가 대신 올림.
            try {
                retryLater(job, lockToken, e);
            } catch (Throwable ignored) {
                // 원래 Error 를 가리지 않음
            }
            throw e;
        }
    }

    private void commit(ProcessingJob job, String lockToken, StageOutcome outcome) {
        if (!queue.markDone(job.id(), lockToken)) {
            // 회수당해 다른 워커가 잡았거나 이미 재대기. 결과를 버림 — 멱등이라 중복 실행 무해 (§5.4).
            log.info("소유권 상실, 결과 폐기: job={}", job.id());
            return;
        }
        Instant now = clock.instant();
        switch (outcome) {
            case StageOutcome.Probed probed -> {
                MediaAsset updated = updateAsset(job.assetId(), a -> applyProbe(a, probed.result()));
                // 이미 THUMBED 이상이면 THUMB 재실행 불필요 (늦게 끝난 중복 PROBE).
                if (updated != null && updated.status() == MediaAssetStatus.PROBED) {
                    queue.enqueue(job.assetId(), Stage.THUMB, job.lane(), properties.maxAttempts(), now);
                }
            }
            case StageOutcome.Thumbed thumbed -> {
                derivatives.upsert(
                        UUID.randomUUID(), job.assetId(), thumbed.recipe(), thumbed.recipeVer(),
                        thumbed.storageKey(), thumbed.byteSize(), thumbed.contentHash(), now);
                updateAsset(job.assetId(), a -> a.withStatus(a.status().advanceTo(MediaAssetStatus.THUMBED)));
            }
        }
    }

    private void retryLater(ProcessingJob job, String lockToken, Throwable e) {
        Instant runAfter = clock.instant().plus(backoff.delay(job.attempt()));
        Optional<JobState> next = tx.execute(s -> {
            Optional<JobState> state = queue.retryLater(job.id(), lockToken, runAfter, describe(e));
            if (state.isPresent() && state.get() == JobState.DEAD) {
                onDead(job);
            }
            return state;
        });
        if (next == null || next.isEmpty()) {
            log.info("소유권 상실, 재시도 예약 생략: job={}", job.id());
        } else if (next.get() == JobState.DEAD) {
            log.error("재시도 소진, 작업 DEAD: job={} asset={}", job.id(), job.assetId(), e);
        } else {
            log.warn("일시적 실패, {} 에 재시도: job={} attempt={} ({})",
                    runAfter, job.id(), job.attempt() + 1, e.toString());
        }
    }

    /**
     * 작업이 DEAD 가 된 뒤 자산 처리. PROBE 가 죽으면 메타가 없어 쓸 수 없는 자산 → FAILED.
     * 안 그러면 살아 있는 작업 없이 INGESTED 에 영구 정지하고 화면은 "처리 중"을 계속 보임.
     * THUMB 이 죽으면 PROBED 유지 (P5 — 파생물 실패는 원본·메타와 무관). lease 회수 경로의 같은
     * 규칙은 {@link JobQueue#reapExpired} SQL 안에.
     */
    private void onDead(ProcessingJob job) {
        if (job.stage() == Stage.PROBE) {
            updateAsset(job.assetId(), a -> a.withStatus(MediaAssetStatus.FAILED));
        }
    }

    /** 메타는 덮어쓰되 상태는 전진만 — THUMBED 뒤 늦게 끝난 중복 PROBE 가 PROBED 로 되돌리지 않게. */
    private static MediaAsset applyProbe(MediaAsset asset, ProbeResult r) {
        MediaAssetStatus status = asset.status().advanceTo(MediaAssetStatus.PROBED);
        return asset.probed(
                        r.capturedAt(), r.capturedAtSrc(), r.tzOffsetMin(),
                        r.gpsLat(), r.gpsLon(),
                        r.width(), r.height(), r.orientation(), r.durationMs())
                .withStatus(status);
    }

    /**
     * 트랜잭션 안에서 최신 행을 <b>잠그고</b> 다시 읽어 갱신. 잠금 없이 읽으면 같은 자산의 두 커밋
     * (중복 PROBE 와 THUMB)이 둘 다 PROBED 를 보고, 나중 커밋이 THUMBED 를 덮어 역행 — READ COMMITTED
     * 에서 advanceTo 가 낡은 스냅샷 기준이 됨. media_assets 에 version 컬럼이 없어 비관적 잠금으로.
     */
    private MediaAsset updateAsset(UUID assetId, Function<MediaAsset, MediaAsset> change) {
        return assets.findByIdForUpdate(assetId)
                .map(change)
                .map(jdbcOps::update)
                .orElse(null);
    }

    private StageProcessor processorFor(Stage stage) {
        StageProcessor processor = processors.get(stage);
        if (processor == null) {
            // DERIVE 등 아직 없는 단계. 설정 실수라 재시도 무의미 → 도구 부재와 같은 취급.
            throw new ToolUnavailableException("처리기 없음: " + stage);
        }
        return processor;
    }

    private static String describe(Throwable e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }

    private static String defaultWorkerId() {
        String host = System.getenv().getOrDefault("HOSTNAME", "local");
        return host + "-" + ProcessHandle.current().pid();
    }
}
