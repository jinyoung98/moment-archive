package dev.jinyoung.archive.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 컨테이너 위에서 도는 통합 테스트의 공통 기반.
 *
 * <p>컨테이너를 정적 필드에 두고 static 블록에서 한 번만 띄운다. JUnit 의 {@code @Testcontainers}
 * 확장을 쓰면 테스트 클래스마다 컨테이너가 새로 뜨는데, 이 프로젝트는 Docker 데몬이 원격이라
 * (docs/infra.md) 기동 비용이 로컬보다 훨씬 크다. 클래스가 늘어날수록 차이가 벌어진다.
 *
 * <p>종료는 명시하지 않는다. Testcontainers 의 Ryuk 정리 컨테이너가 JVM 종료 시 회수한다.
 * 원격 데몬에서는 Ryuk 에도 접속이 되어야 하므로, 컨테이너가 남는다면 docs/infra.md §5 를 볼 것.
 *
 * <p>MinIO 컨테이너는 스토리지 계층이 들어오는 시점에 여기에 추가한다.
 */
@SpringBootTest
public abstract class IntegrationTest {

    // Testcontainers 2.x 의 좌표. org.testcontainers.containers.PostgreSQLContainer 는 deprecated 다.
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
