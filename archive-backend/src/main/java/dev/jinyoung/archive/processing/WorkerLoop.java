package dev.jinyoung.archive.processing;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import dev.jinyoung.archive.processing.job.ClockSkewGuard;
import dev.jinyoung.archive.processing.job.Lane;
import dev.jinyoung.archive.processing.job.WorkerProperties;

/**
 * {@link JobWorker} 를 스레드 위에 올리는 층. 레인마다 {@code concurrency} 개 스레드가 큐를 비우고,
 * 비면 {@code pollInterval} 뒤 다시 봄. 하트비트·회수·시계 검사도 같은 스케줄러에서.
 *
 * 기동 시 시계 오차 검사 — 초과면 예외로 컨텍스트 기동 실패 (ADR A24). 조용한 오작동보다 안 뜨는 게 나음.
 *
 * 종료 시 새 claim 을 멈추고(stopping) 진행 중 작업은 유예 시간만큼 기다림. 못 끝낸 작업은
 * RUNNING 으로 남고 lease 만료 후 다른 워커가 회수 — 종료가 정확할 필요 없음, 멱등 설계의 이득.
 */
public class WorkerLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);
    private static final Duration SKEW_CHECK_INTERVAL = Duration.ofMinutes(5);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    private final JobWorker worker;
    private final WorkerProperties properties;
    private final ClockSkewGuard skewGuard;

    private volatile ScheduledExecutorService scheduler;
    /** 종료 시작 표시. drain 이 작업 사이마다 확인해 새 claim 을 멈춤. */
    private volatile boolean stopping;

    public WorkerLoop(JobWorker worker, WorkerProperties properties, ClockSkewGuard skewGuard) {
        this.worker = worker;
        this.properties = properties;
        this.skewGuard = skewGuard;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        skewGuard.verify();
        stopping = false;

        int drainThreads = properties.lanes().size() * properties.concurrency();
        scheduler = Executors.newScheduledThreadPool(drainThreads + 1, Thread.ofPlatform().name("worker-", 0).factory());

        long poll = properties.pollInterval().toMillis();
        for (Lane lane : properties.lanes()) {
            for (int i = 0; i < properties.concurrency(); i++) {
                scheduler.scheduleWithFixedDelay(guarded("drain " + lane, () -> worker.drain(lane, () -> !stopping)),
                        0, poll, TimeUnit.MILLISECONDS);
            }
        }
        long heartbeat = properties.heartbeat().toMillis();
        scheduler.scheduleWithFixedDelay(guarded("heartbeat", worker::heartbeat),
                heartbeat, heartbeat, TimeUnit.MILLISECONDS);
        // 회수 주기는 하트비트와 같게. lease(2분) 대비 충분히 촘촘.
        scheduler.scheduleWithFixedDelay(guarded("reap", worker::reapExpired),
                heartbeat, heartbeat, TimeUnit.MILLISECONDS);
        long skewCheck = SKEW_CHECK_INTERVAL.toMillis();
        scheduler.scheduleWithFixedDelay(guarded("clock-skew", skewGuard::check),
                skewCheck, skewCheck, TimeUnit.MILLISECONDS);

        log.info("워커 시작: id={} lanes={} concurrency={}", worker.workerId(), properties.lanes(), properties.concurrency());
    }

    @Override
    public synchronized void stop() {
        if (scheduler == null) {
            return;
        }
        stopping = true;        // 먼저 — 잡은 작업 하나만 마무리하고 새 claim 은 안 함
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("워커 종료 유예 초과 — 남은 작업은 lease 만료 후 회수됨");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        scheduler = null;
        log.info("워커 종료: id={}", worker.workerId());
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    /**
     * 예외가 새면 scheduleWithFixedDelay 가 이후 실행을 로그 없이 영구 취소 — 반드시 삼킴.
     * Error 까지 잡는 이유: 독성 파일의 OOM 이 drain 스레드를 하나씩 죽이면 레인 전체가 조용히 멈추는데
     * isRunning·하트비트·회수는 계속 돌아 겉보기엔 정상. 레인이 먼저 죽으면 max_attempts 방어도 못 먹음.
     */
    private static Runnable guarded(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable e) {
                log.error("워커 작업 실패: {}", name, e);
            }
        };
    }
}
