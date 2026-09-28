package dev.jinyoung.archive.processing.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClockSkewGuardTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final Clock APP = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration MAX = Duration.ofSeconds(30);

    @Test
    @DisplayName("오차가 허용치 안이면 기동을 막지 않는다")
    void 허용치_안() {
        var guard = new ClockSkewGuard(APP, () -> NOW.plusSeconds(5), MAX);

        assertThat(guard.measure()).isEqualTo(Duration.ofSeconds(5));
        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DB 가 앞서든 뒤처지든 허용치를 넘으면 기동을 거부한다")
    void 허용치_초과() {
        var ahead = new ClockSkewGuard(APP, () -> NOW.plusSeconds(31), MAX);
        var behind = new ClockSkewGuard(APP, () -> NOW.minusSeconds(95), MAX);

        assertThatThrownBy(ahead::verify).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(behind::verify).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("주기 검사는 초과해도 예외 없이 경고만 — 도는 작업을 끊지 않는다")
    void 주기_검사는_경고만() {
        var guard = new ClockSkewGuard(APP, () -> NOW.plusSeconds(120), MAX);

        assertThatCode(guard::check).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("lease 가 heartbeat + 2×오차 예산 이하인 설정은 생성 시점에 거부된다")
    void 설정_예산_검증() {
        // 70 > 30 + 30 이지만 워커 둘이 +30/−30 이면 차이 60 > lease − heartbeat 40 → 거부해야 함.
        assertThatThrownBy(() -> new WorkerProperties(
                true, null, null, null, null,
                Duration.ofSeconds(30), Duration.ofSeconds(70), Duration.ofSeconds(30), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease");

        assertThatCode(() -> new WorkerProperties(null, null, null, null, null, null, null, null, null))
                .doesNotThrowAnyException();
    }
}
