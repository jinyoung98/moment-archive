package dev.jinyoung.archive.processing.job;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * 지수 백오프 + full jitter (pipeline.md §5.6). 상한 base = 1s·4^attempt, 실제 지연은 [0, base) 균등.
 *
 * 지터 없으면 동시 실패한 작업들이 같은 시각에 다시 몰림 (retry storm). 난수원은 주입 —
 * 테스트가 시드로 고정 (CLAUDE.md 코딩 규칙). attempt 상한으로 오버플로 방지.
 */
public class Backoff {

    private static final long BASE_MILLIS = 1_000L;
    private static final int FACTOR = 4;
    private static final int MAX_EXPONENT = 8;     // 4^8 s ≈ 18시간. 그 이상은 의미 없음

    private final RandomGenerator random;

    public Backoff(RandomGenerator random) {
        this.random = Objects.requireNonNull(random);
    }

    /** {@code attempt} = 지금까지 실패 횟수(0부터). 0 → [0,1s), 1 → [0,4s), 2 → [0,16s) … */
    public Duration delay(int attempt) {
        long cap = ceiling(attempt).toMillis();
        return Duration.ofMillis(random.nextLong(cap));
    }

    /** 지터 적용 전 상한. 테스트·로그용. */
    public Duration ceiling(int attempt) {
        int exponent = Math.clamp(attempt, 0, MAX_EXPONENT);
        long cap = BASE_MILLIS;
        for (int i = 0; i < exponent; i++) {
            cap *= FACTOR;
        }
        return Duration.ofMillis(cap);
    }
}
