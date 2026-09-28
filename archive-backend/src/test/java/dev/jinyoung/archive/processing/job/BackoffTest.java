package dev.jinyoung.archive.processing.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private static final long SEED = 20260928L;

    @Test
    @DisplayName("상한은 1s·4^attempt 로 지수 증가하고 너무 큰 attempt 에서도 넘치지 않는다")
    void 상한() {
        Backoff backoff = new Backoff(new Random(SEED));

        assertThat(backoff.ceiling(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.ceiling(1)).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.ceiling(2)).isEqualTo(Duration.ofSeconds(16));
        assertThat(backoff.ceiling(4)).isEqualTo(Duration.ofSeconds(256));
        assertThat(backoff.ceiling(1_000)).isEqualTo(backoff.ceiling(8));
    }

    @Test
    @DisplayName("지연은 항상 [0, 상한) 안이고, 같은 attempt 라도 흩어진다 (full jitter)")
    void 지터() {
        Backoff backoff = new Backoff(new Random(SEED));
        Set<Duration> seen = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            Duration d = backoff.delay(2);
            assertThat(d).isGreaterThanOrEqualTo(Duration.ZERO).isLessThan(Duration.ofSeconds(16));
            seen.add(d);
        }
        // 지터가 없으면 전부 같은 값 → 동시 실패가 동시 재시도로 몰림.
        assertThat(seen).hasSizeGreaterThan(100);
    }

    @Test
    @DisplayName("같은 시드면 같은 지연 순서 — 결정론")
    void 시드_결정론() {
        Backoff a = new Backoff(new Random(SEED));
        Backoff b = new Backoff(new Random(SEED));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(a.delay(attempt)).isEqualTo(b.delay(attempt));
        }
    }
}
