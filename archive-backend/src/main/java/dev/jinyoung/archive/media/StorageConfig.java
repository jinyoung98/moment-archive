package dev.jinyoung.archive.media;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 클라이언트 빈.
 *
 * <p>동기 클라이언트를 쓴다. 이 프로젝트의 스토리지 호출자는 워커이고, 워커는 이미
 * 자기 스레드에서 도는 데다 진행 상황을 {@code checkpoint} 로 남기며 순차적으로 움직인다
 * (pipeline.md §4). 비동기 클라이언트를 얹으면 그 순차성을 다시 조립하는 코드만 늘어난다.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    public S3Client s3Client(StorageProperties properties) {
        var builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                // MinIO 는 가상 호스트 스타일(bucket.host)을 쓰지 않는다. 이걸 켜지 않으면
                // SDK 가 존재하지 않는 호스트명을 조립해 DNS 에서 실패한다.
                .forcePathStyle(true);

        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }
}
