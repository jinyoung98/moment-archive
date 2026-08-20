package dev.jinyoung.archive.media;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link ObjectStorage} 의 S3 구현. 개발·테스트 환경은 MinIO 대상.
 *
 * 모든 SDK 예외를 {@link StorageException} 으로 변환. SDK 예외 누출 시 워커·서비스
 * 코드가 {@code software.amazon.awssdk} import, 스토리지 교체 가능성이라는 인터페이스
 * 약속이 형식만 남음.
 */
@Component
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final String bucket;

    public S3ObjectStorage(S3Client s3, StorageProperties properties) {
        this.s3 = s3;
        this.bucket = properties.bucket();
    }

    @Override
    public void put(String key, Path source, String contentType, ContentHash checksum) {
        put(key, contentType, checksum, RequestBody.fromFile(source));
    }

    @Override
    public void put(String key, byte[] content, String contentType, ContentHash checksum) {
        put(key, contentType, checksum, RequestBody.fromBytes(content));
    }

    private void put(String key, String contentType, ContentHash checksum, RequestBody body) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                // 서버가 수신 바이트로 SHA-256 재계산·대조. 불일치 시 400, 객체 미생성 —
                // 손상 원본 저장 경로 차단 (I1).
                .checksumSHA256(checksum.base64())
                .build();
        try {
            s3.putObject(request, body);
        } catch (SdkException e) {
            throw new StorageException("객체 저장 실패: " + key, e);
        }
    }

    @Override
    public InputStream open(String key) {
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        try {
            return s3.getObject(request);
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException(key);
        } catch (SdkException e) {
            throw new StorageException("객체 조회 실패: " + key, e);
        }
    }

    @Override
    public Optional<ObjectStat> stat(String key) {
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                // 미설정 시 체크섬 헤더 자체 미전송. 기본값 꺼짐을 모르면 "MinIO 는 SHA-256
                // 미보관"으로 오판 — 실제로는 보관함.
                .checksumMode(ChecksumMode.ENABLED)
                .build();
        try {
            HeadObjectResponse head = s3.headObject(request);
            return Optional.of(new ObjectStat(
                    head.contentLength(), ContentHash.fromBase64(head.checksumSHA256())));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            // HEAD 응답은 본문 없음 — 오류 코드 전달 지면 없음. 그래서 일부 구현은 없는 키를
            // NoSuchKeyException 대신 상태코드뿐인 S3Exception 으로 반환.
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new StorageException("객체 조회 실패: " + key, e);
        } catch (SdkException e) {
            throw new StorageException("객체 조회 실패: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(bucket).key(key).build();
        try {
            s3.deleteObject(request);
        } catch (SdkException e) {
            throw new StorageException("객체 삭제 실패: " + key, e);
        }
    }

    @Override
    public List<String> listKeys(String prefix) {
        ListObjectsV2Request request =
                ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();
        try {
            List<String> keys = new ArrayList<>();
            s3.listObjectsV2Paginator(request)
                    .contents()
                    .forEach(object -> keys.add(object.key()));
            return keys;
        } catch (SdkException e) {
            throw new StorageException("객체 목록 조회 실패: " + prefix, e);
        }
    }
}
