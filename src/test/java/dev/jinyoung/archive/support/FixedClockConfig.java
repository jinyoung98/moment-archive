package dev.jinyoung.archive.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 테스트에서 시간을 고정한다.
 *
 * <p>메서드 이름을 {@code clock} 이 아닌 {@code fixedClock} 으로 둔 것은 의도적이다.
 * 이름이 같으면 운영 빈과 정의가 충돌해 컨텍스트가 뜨지 않는다. 이름을 다르게 두고
 * {@link Primary} 로 우선순위만 뒤집으면 타입으로 주입받는 쪽은 그대로 동작한다.
 */
@TestConfiguration
public class FixedClockConfig {

    /** 설계 문서를 쓴 날. 특별한 의미는 없고, 고정값이라는 사실만이 중요하다. */
    public static final Instant FIXED_INSTANT = Instant.parse("2026-08-06T00:00:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }
}
