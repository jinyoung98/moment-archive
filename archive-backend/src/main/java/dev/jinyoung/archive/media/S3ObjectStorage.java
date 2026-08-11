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
 * {@link ObjectStorage} 의 S3 구현. 개발·테스트에서는 MinIO 를 가리킨다.
 *
 * <p>모든 SDK 예외를 {@link StorageException} 으로 바꿔 내보낸다. SDK 예외 타입이 상위로
 * 새어 나가면 워커와 서비스 코드가 {@code software.amazon.awssdk} 를 import 하게 되고,
 * 그 순간 스토리지를 갈아 끼울 수 있다는 이 인터페이스의 약속이 형식만 남는다.
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
                // 서버가 수신 바이트로 SHA-256 을 재계산해 대조한다. 어긋나면 400 이고
                // 객체는 만들어지지 않는다 — 손상된 원본이 저장되는 경로가 닫힌다 (I1).
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
                // 이것을 빼면 스토리지는 체크섬 헤더를 아예 보내지 않는다. 기본값이 꺼짐인 것을
                // 모르면 "MinIO 가 SHA-256 을 보관하지 않는다" 고 결론짓게 된다 — 보관은 한다.
                .checksumMode(ChecksumMode.ENABLED)
                .build();
        try {
            HeadObjectResponse head = s3.headObject(request);
            return Optional.of(new ObjectStat(
                    head.contentLength(), ContentHash.fromBase64(head.checksumSHA256())));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            // HEAD 응답에는 본문이 없어서 오류 코드를 실어 보낼 자리가 없다. 그래서 없는 키가
            // NoSuchKeyException 이 아니라 상태코드만 있는 S3Exception 으로 오는 구현이 있다.
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
