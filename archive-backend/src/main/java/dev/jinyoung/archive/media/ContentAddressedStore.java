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
 * <p>{@link ObjectStorage} 가 "이 키에 이 바이트를 써라" 라면, 이 층은 <b>키를 정하고
 * 쓸지 말지를 판단한다.</b> 판단은 하나뿐이다 — 같은 내용이 이미 그 자리에 있으면
 * 전송하지 않는다.
 *
 * <p>중복 제거가 DB 의 {@code UNIQUE (owner_id, content_hash)} 와 겹쳐 보이지만 막는 것이
 * 다르다. DB 제약은 <b>같은 사용자의</b> 자산 행이 둘 생기는 것을 막고, 이쪽은 물리 객체가
 * 둘 생기는 것을 막는다. 사용자가 여럿이면 자산 행은 둘이고 파일은 하나다 — I2 가 말하는
 * "물리적으로 하나" 는 이 층에서만 성립한다.
 *
 * <p>이 층은 DB 를 모른다. 자산 행을 쓰는 것은 업로드의 일이고, 여기까지가 W1 스토리지
 * 계층의 경계다.
 */
@Component
public class ContentAddressedStore {

    private final ObjectStorage storage;

    public ContentAddressedStore(ObjectStorage storage) {
        this.storage = storage;
    }

    /**
     * 파일을 내용 해시가 정하는 자리에 놓는다. <b>같은 입력에 몇 번 호출해도 결과가 같다.</b>
     *
     * <p>멱등성이 성질이 아니라 요구사항인 이유는, 이 메서드를 부르는 쪽이 재시도되는 stage 이기
     * 때문이다 (I9). lease 가 만료되어 회수된 작업은 사실 저장 직후였을 수 있다.
     */
    public StoredOriginal storeOriginal(Path file, String contentType) throws IOException {
        ContentHash hash = ContentHash.of(file);
        long byteSize = Files.size(file);
        String key = StorageKeys.original(hash);

        Optional<ObjectStat> existing = storage.stat(key);
        if (existing.isPresent() && existing.get().byteSize() == byteSize) {
            return new StoredOriginal(hash, key, byteSize, true);
        }
        // 키는 있는데 크기가 다르면 앞선 저장이 중간에 끊긴 것이다. 해시가 같은 서로 다른
        // 내용이라는 설명은 SHA-256 충돌을 뜻하므로 고려 대상이 아니다. 덮어쓰는 것이
        // 자가 치유이고, 스토리지가 체크섬을 대조하므로 이번 쓰기의 정합성은 보장된다.
        storage.put(key, file, contentType, hash);
        return new StoredOriginal(hash, key, byteSize, false);
    }

    /**
     * 저장된 바이트를 처음부터 다시 읽어 해시를 계산한다.
     *
     * <p>{@link ObjectStat#checksum()} 이 있으면 왕복 한 번으로 끝나는데도 전부 읽는 이유는,
     * 그 값이 <b>스토리지의 주장</b>이라는 데 있다. I1 이 검증하려는 대상에 스토리지 자신이
     * 포함되므로, 확인은 실제 바이트로 해야 의미가 있다.
     */
    public ContentHash hashOf(String storageKey) {
        try (InputStream in = storage.open(storageKey)) {
            return ContentHash.of(in);
        } catch (IOException e) {
            throw new StorageException("객체를 읽는 중 실패: " + storageKey, e);
        }
    }
}
