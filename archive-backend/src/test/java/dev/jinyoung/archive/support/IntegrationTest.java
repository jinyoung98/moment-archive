package dev.jinyoung.archive.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * 컨테이너 위에서 도는 통합 테스트의 공통 기반.
 *
 * <p>컨테이너를 정적 필드에 두고 static 블록에서 한 번만 띄운다. JUnit 의 {@code @Testcontainers}
 * 확장을 쓰면 테스트 클래스마다 컨테이너가 새로 뜨는데, 이 프로젝트는 Docker 데몬이 원격이라
 * (docs/infra.md) 기동 비용이 로컬보다 훨씬 크다. 클래스가 늘어날수록 차이가 벌어진다.
 *
 * <p>둘을 {@link Startables} 로 <b>병렬</b> 기동한다. 원격 데몬에서는 기동 시간이 그대로
 * 테스트 반복 주기가 되고, 그 주기가 로드맵 §6 에이전트 루프의 속도를 정한다.
 *
 * <p>종료는 명시하지 않는다. Testcontainers 의 Ryuk 정리 컨테이너가 JVM 종료 시 회수한다.
 * 원격 데몬에서는 Ryuk 에도 접속이 되어야 하므로, 컨테이너가 남는다면 docs/infra.md §5 를 볼 것.
 *
 * <p>워커 루프는 끈다. 백그라운드 스레드가 큐를 비우면 테스트가 경합에 좌우됨 — 처리는 테스트가
 * {@code JobWorker.drain()} 을 직접 호출해 결정론적으로 돌린다.
 */
@SpringBootTest(properties = "archive.worker.enabled=false")
public abstract class IntegrationTest {

    /**
     * 상시 스택과 같은 이름을 쓰지 않는다. 테스트가 실수로 서버의 상시 MinIO 를 가리키게 되는
     * 사고가 나면, 버킷 이름이 다르다는 사실이 마지막 안전망이 된다.
     */
    protected static final String TEST_BUCKET = "archive-test";

    // Testcontainers 2.x 의 좌표. org.testcontainers.containers.PostgreSQLContainer 는 deprecated 다.
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    // MinIO 모듈은 2.x 에서도 org.testcontainers.containers 에 남아 있다 (postgresql 과 다르다).
    // TODO: docker-compose.yml 의 minio 태그를 digest 로 고정할 때 여기도 같이 고정한다.
    //       latest 는 로드맵 §5 결정론 체크리스트와 어긋난다 (docs/infra.md §4).
    protected static final MinIOContainer MINIO =
            new MinIOContainer(DockerImageName.parse("minio/minio:latest"));

    static {
        Startables.deepStart(POSTGRES, MINIO).join();
        createTestBucket();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("archive.storage.endpoint", MINIO::getS3URL);
        registry.add("archive.storage.access-key", MINIO::getUserName);
        registry.add("archive.storage.secret-key", MINIO::getPassword);
        registry.add("archive.storage.bucket", () -> TEST_BUCKET);
    }

    /**
     * 더미 구글 자격. oauth2Login 은 ClientRegistrationRepository 가 있어야 컨텍스트가 뜬다.
     * 실제 구글 왕복은 테스트에서 하지 않는다 — 인증된 주체를 직접 주입한다(AuthTestSupport).
     */
    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.registration.google.client-id", () -> "test-client-id");
        registry.add("spring.security.oauth2.client.registration.google.client-secret", () -> "test-client-secret");
    }

    /**
     * 운영에서 버킷을 만드는 것은 애플리케이션이 아니라 {@code minio-init} 컨테이너다
     * (docs/infra.md §2.5). 그 역할을 여기서 대신한다 — 버킷 생성을 프로덕션 코드에 넣으면
     * 테스트만을 위한 권한과 경로가 운영에 남는다.
     */
    private static void createTestBucket() {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .forcePathStyle(true)
                .build()) {
            s3.createBucket(request -> request.bucket(TEST_BUCKET));
        }
    }
}
