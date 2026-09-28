package dev.jinyoung.archive.processing.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 앱 시계 ↔ DB 시계 오차 감시 (ADR A24).
 *
 * 큐 시각은 워커마다 자기 {@link Clock} 으로 씀. 회수기 시계가 하트비트 쓴 워커보다 Δ 빠르면
 * 작업이 Δ 만큼 늙어 보임 → Δ > lease − heartbeat 이면 살아 있는 작업을 뺏고 attempt 를 올려
 * 멀쩡한 파일이 DEAD. 조용히 일어나는 실패라 여기서 시끄럽게 바꿈 — 기동 시 초과면 거부,
 * 이후 주기 검사는 경고. 워커끼리 직접 비교 대신 DB 를 공통 기준점으로.
 *
 * 측정 오차는 왕복 시간의 절반 이내 — 앞뒤 앱 시각의 중점과 비교.
 *
 * 검사 대상은 워커 프로세스뿐. api 전용 인스턴스도 enqueue 의 run_after·created_at 을 자기 시계로
 * 쓰지만, 그쪽이 어긋나면 처리가 Δ 만큼 늦게 시작될 뿐(회수 오작동 아님)이라 두지 않음.
 */
public class ClockSkewGuard {

    private static final Logger log = LoggerFactory.getLogger(ClockSkewGuard.class);

    private final Clock clock;
    private final Supplier<Instant> databaseClock;
    private final Duration maxSkew;

    public ClockSkewGuard(Clock clock, Supplier<Instant> databaseClock, Duration maxSkew) {
        this.clock = clock;
        this.databaseClock = databaseClock;
        this.maxSkew = maxSkew;
    }

    /** |앱 − DB| 추정치. */
    public Duration measure() {
        Instant before = clock.instant();
        Instant db = databaseClock.get();
        Instant after = clock.instant();
        Instant mid = before.plus(Duration.between(before, after).dividedBy(2));
        return Duration.between(mid, db).abs();
    }

    /** 기동 시. 예산 초과면 예외 — 워커가 뜨지 않음. */
    public void verify() {
        Duration skew = measure();
        if (skew.compareTo(maxSkew) > 0) {
            throw new IllegalStateException(
                    "앱-DB 시계 오차 " + skew + " > 허용 " + maxSkew + " — NTP 확인. 이대로 돌면 lease 회수가 오작동");
        }
        log.info("시계 오차 {} (허용 {})", skew, maxSkew);
    }

    /** 주기 검사. 이미 도는 작업을 끊지 않고 경고만. */
    public void check() {
        Duration skew = measure();
        if (skew.compareTo(maxSkew) > 0) {
            log.warn("앱-DB 시계 오차 {} > 허용 {} — lease 회수 오작동 위험", skew, maxSkew);
        }
    }
}
