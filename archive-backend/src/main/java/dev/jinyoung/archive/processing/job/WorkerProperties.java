package dev.jinyoung.archive.processing.job;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 워커 설정 ({@code archive.worker.*}).
 *
 * 역할 분리는 같은 jar + 설정 (ADR A25). api 전용 = {@code enabled=false},
 * 워커 전용 = {@code spring.main.web-application-type=none}. 개발·단일 서버는 둘 다 켬.
 *
 * lease·하트비트·시계 오차 예산의 관계를 생성 시점에 강제 — 어긋나면 살아 있는 작업을 회수기가
 * 뺏기 시작하므로 (ADR A24). 조건: lease > heartbeat + 2·maxClockSkew. 2배인 이유: 각 워커는 DB
 * 대비 ±maxClockSkew 만 보장되므로 두 워커 사이 차이는 최대 2배 (한쪽 +, 다른 쪽 −).
 *
 * @param enabled         이 프로세스에서 워커 루프를 돌릴지. 테스트는 끄고 {@code drain()} 을 직접 호출
 * @param id              워커 인스턴스 ID. 비면 호스트명·PID 로 생성
 * @param lanes           처리할 레인. W2 초반은 PHOTO 만 — VIDEO 처리기는 아직 없음
 * @param concurrency     레인당 동시 처리 스레드 수
 * @param pollInterval    큐가 비었을 때 다시 볼 간격
 * @param heartbeat       하트비트 간격 (pipeline.md §5.2)
 * @param lease           이만큼 하트비트가 없으면 죽은 워커로 보고 회수 (§5.3)
 * @param maxClockSkew    앱 시계와 DB 시계 차이 허용치. 넘으면 기동 거부
 * @param maxAttempts     작업당 최대 시도 횟수
 */
@ConfigurationProperties("archive.worker")
public record WorkerProperties(
        Boolean enabled,
        String id,
        List<Lane> lanes,
        Integer concurrency,
        Duration pollInterval,
        Duration heartbeat,
        Duration lease,
        Duration maxClockSkew,
        Integer maxAttempts) {

    public WorkerProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (lanes == null || lanes.isEmpty()) {
            lanes = List.of(Lane.PHOTO);
        }
        if (concurrency == null) {
            concurrency = 2;
        }
        if (pollInterval == null) {
            pollInterval = Duration.ofSeconds(1);
        }
        if (heartbeat == null) {
            heartbeat = Duration.ofSeconds(30);
        }
        if (lease == null) {
            lease = Duration.ofMinutes(2);
        }
        if (maxClockSkew == null) {
            maxClockSkew = Duration.ofSeconds(30);
        }
        if (maxAttempts == null) {
            maxAttempts = 5;
        }
        if (lease.compareTo(heartbeat.plus(maxClockSkew.multipliedBy(2))) <= 0) {
            throw new IllegalArgumentException(
                    "lease(" + lease + ") 는 heartbeat(" + heartbeat + ") + 2×maxClockSkew(" + maxClockSkew
                            + ") 보다 커야 함 — 아니면 살아 있는 작업이 회수됨");
        }
    }
}
