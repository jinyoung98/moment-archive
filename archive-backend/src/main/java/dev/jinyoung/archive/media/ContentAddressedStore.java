package dev.jinyoung.archive.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * 내용으로 주소를 정하는 저장소 (content-addressed storage).
 *
 * {@link ObjectStorage} 가 "이 키에 이 바이트 저장"이라면, 이 층은 키 결정과 저장
 * 여부 판단 담당. 판단은 단일 — 같은 내용이 이미 존재하면 전송 생략.
 *
 * DB {@code UNIQUE (owner_id, content_hash)} 와 유사해 보이나 막는 대상 상이. DB
 * 제약은 동일 사용자 자산 행 중복 방지, 이쪽은 물리 객체 중복 방지. 다중 사용자 시
 * 자산 행은 여럿, 파일은 하나 — I2 의 "물리적으로 하나"는 이 층에서만 성립.
 *
 * 이 층은 DB 미인지. 자산 행 기록은 업로드 담당, 여기까지가 W1 스토리지 계층 경계.
 */
@Component
public class ContentAddressedStore {

    private final ObjectStorage storage;

    public ContentAddressedStore(ObjectStorage storage) {
        this.storage = storage;
    }

    /**
     * 파일을 내용 해시 결정 위치에 저장. 동일 입력 반복 호출 시 결과 동일(멱등).
     *
     * 멱등성이 요구사항인 이유: 호출부가 재시도 대상 stage (I9). lease 만료 회수 작업이
     * 실제로는 저장 직후였을 가능성.
     */
    public StoredOriginal storeOriginal(Path file, String contentType) throws IOException {
        ContentHash hash = ContentHash.of(file);
        long byteSize = Files.size(file);
        String key = StorageKeys.original(hash);

        Optional<ObjectStat> existing = storage.stat(key);
        if (existing.isPresent() && existing.get().byteSize() == byteSize) {
            return new StoredOriginal(hash, key, byteSize, true);
        }
        // 키 존재·크기 불일치 = 이전 저장 중단. 동일 해시·다른 내용이라는 설명은 SHA-256
        // 충돌 의미라 배제. 덮어쓰기가 자가 치유, 체크섬 대조로 이번 쓰기 정합성 보장.
        storage.put(key, file, contentType, hash);
        return new StoredOriginal(hash, key, byteSize, false);
    }

    /**
     * 저장 바이트 전체 재읽기·해시 재계산.
     *
     * {@link ObjectStat#checksum()} 로 왕복 한 번에 끝낼 수 있음에도 전체 재읽기하는
     * 이유: 그 값은 스토리지 측 주장일 뿐. I1 검증 대상에 스토리지 자체도 포함되므로
     * 실제 바이트 확인이 필수.
     */
    public ContentHash hashOf(String storageKey) {
        try (InputStream in = storage.open(storageKey)) {
            return ContentHash.of(in);
        } catch (IOException e) {
            throw new StorageException("객체를 읽는 중 실패: " + storageKey, e);
        }
    }
}
