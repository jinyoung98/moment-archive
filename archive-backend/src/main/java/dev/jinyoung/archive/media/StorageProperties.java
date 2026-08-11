package dev.jinyoung.archive.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 스토리지 접속 정보.
 *
 * @param endpoint  S3 API 주소. MinIO 를 가리킬 때만 의미가 있고, 실제 AWS S3 를 쓰게 되면
 *                  비워서 SDK 의 기본 엔드포인트를 쓴다
 * @param region    MinIO 는 이 값을 쓰지 않지만 SDK 가 서명 계산에 요구한다
 * @param accessKey MinIO 의 루트 사용자 (docs/infra.md §2.3)
 * @param secretKey 같은 곳의 비밀번호. 저장소에 넣지 않는다
 * @param bucket    버킷은 하나이고 원본·파생물은 접두사로 나눈다 ({@link StorageKeys})
 */
@ConfigurationProperties("archive.storage")
public record StorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket) {
}
