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
 * 동기 클라이언트 사용. 스토리지 호출자는 워커, 이미 자체 스레드에서 {@code checkpoint}
 * 기록하며 순차 진행 (pipeline.md §4). 비동기 클라이언트는 그 순차성 재조립 코드만 증가.
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
                // MinIO: 가상 호스트 스타일(bucket.host) 미사용. 미설정 시 SDK 가 존재하지
                // 않는 호스트명 조립 → DNS 실패.
                .forcePathStyle(true);

        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }
}
