package dev.jinyoung.archive.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간의 단일 출처.
 *
 * <p>애플리케이션 코드에서 {@code Instant.now()} / {@code LocalDateTime.now()} 를 직접 호출하지 않는다.
 * 항상 주입된 {@link Clock} 을 거친다. 테스트가 시간을 고정할 수 없으면 만료·백오프·유예 삭제처럼
 * 시간에 의존하는 로직을 검증할 방법이 없어지고, 로드맵 §6 에이전트 루프의 전제인 결정론이 깨진다.
 *
 * <p>UTC 로 고정한다. 저장은 전부 {@code timestamptz} 이고 표시 시점의 시간대 변환은
 * 표현 계층의 책임이다. 서버 기본 시간대에 동작이 좌우되면 안 된다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
