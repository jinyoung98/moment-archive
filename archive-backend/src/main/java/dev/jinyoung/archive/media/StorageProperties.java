package dev.jinyoung.archive.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 스토리지 접속 정보.
 *
 * @param endpoint  S3 API 주소. MinIO 대상 시에만 의미, 실제 AWS S3 전환 시 공백 처리로
 *                  SDK 기본 엔드포인트 사용
 * @param region    MinIO 는 미사용이나 SDK 서명 계산에 필수
 * @param accessKey MinIO 루트 사용자 (docs/infra.md §2.3)
 * @param secretKey 동일 계정 비밀번호. 저장소 커밋 금지
 * @param bucket    버킷 단일, 원본·파생물은 접두사로 구분 ({@link StorageKeys})
 */
@ConfigurationProperties("archive.storage")
public record StorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket) {
}
