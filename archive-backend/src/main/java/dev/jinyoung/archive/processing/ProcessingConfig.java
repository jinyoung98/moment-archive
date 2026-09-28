package dev.jinyoung.archive.processing;

import java.time.Clock;
import java.util.Random;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.jinyoung.archive.processing.job.Backoff;
import dev.jinyoung.archive.processing.job.ClockSkewGuard;
import dev.jinyoung.archive.processing.job.JobQueue;
import dev.jinyoung.archive.processing.job.WorkerProperties;
import dev.jinyoung.archive.processing.thumb.ThumbnailProperties;

/**
 * processing 패키지 설정 바인딩 + 워커 배선.
 *
 * {@link JobWorker} 는 항상 빈 — 테스트가 직접 {@code drain()}. 스레드를 띄우는 {@link WorkerLoop} 만
 * {@code archive.worker.enabled} 로 켜고 끔 (ADR A25).
 */
@Configuration
@EnableConfigurationProperties({ThumbnailProperties.class, WorkerProperties.class})
public class ProcessingConfig {

    /** 운영은 시드 없는 난수. 테스트는 Backoff 를 직접 만들어 시드 고정. */
    @Bean
    public Backoff backoff() {
        return new Backoff(new Random());
    }

    @Bean
    public ClockSkewGuard clockSkewGuard(Clock clock, JobQueue queue, WorkerProperties properties) {
        return new ClockSkewGuard(clock, queue::databaseNow, properties.maxClockSkew());
    }

    @Bean
    @ConditionalOnProperty(name = "archive.worker.enabled", havingValue = "true", matchIfMissing = true)
    public WorkerLoop workerLoop(JobWorker worker, WorkerProperties properties, ClockSkewGuard skewGuard) {
        return new WorkerLoop(worker, properties, skewGuard);
    }
}
