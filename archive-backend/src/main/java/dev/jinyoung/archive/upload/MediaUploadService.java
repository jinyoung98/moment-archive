package dev.jinyoung.archive.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import dev.jinyoung.archive.auth.DevUserProvider;
import dev.jinyoung.archive.media.ContentAddressedStore;
import dev.jinyoung.archive.media.MediaAsset;
import dev.jinyoung.archive.media.MediaAssetRepository;
import dev.jinyoung.archive.media.MediaKind;
import dev.jinyoung.archive.media.StoredOriginal;

/**
 * W1 단일 파일 업로드. 청크·세션 없음 (pipeline.md 는 W2 이후 프로토콜).
 *
 * 스토리지 계층은 DB 를 모르는 채로 끝났으므로(media 패키지), 자산 행을 쓰는 것이 여기서
 * 처음 등장한다.
 */
@Service
public class MediaUploadService {

    private final ContentAddressedStore store;
    private final MediaAssetRepository assets;
    private final JdbcAggregateOperations jdbcOps;
    private final DevUserProvider ownerProvider;
    private final Clock clock;

    public MediaUploadService(
            ContentAddressedStore store,
            MediaAssetRepository assets,
            JdbcAggregateOperations jdbcOps,
            DevUserProvider ownerProvider,
            Clock clock) {
        this.store = store;
        this.assets = assets;
        this.jdbcOps = jdbcOps;
        this.ownerProvider = ownerProvider;
        this.clock = clock;
    }

    public UploadOriginalResponse upload(MultipartFile file) throws IOException {
        String contentType = file.getContentType();
        MediaKind kind = MediaKind.fromMimeType(contentType);
        UUID ownerId = ownerProvider.currentUserId();

        Path tempFile = Files.createTempFile("upload-original-", ".tmp");
        try {
            file.transferTo(tempFile);
            StoredOriginal stored = store.storeOriginal(tempFile, contentType);
            return findOrCreateAsset(ownerId, stored, contentType, kind);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private UploadOriginalResponse findOrCreateAsset(UUID ownerId, StoredOriginal stored, String contentType, MediaKind kind) {
        var existing = assets.findByOwnerIdAndContentHash(ownerId, stored.hash().hex());
        if (existing.isPresent()) {
            MediaAsset asset = existing.get();
            return new UploadOriginalResponse(asset.id(), asset.status(), true);
        }

        MediaAsset toInsert = MediaAsset.newlyIngested(
                UUID.randomUUID(), ownerId, stored, contentType, kind, clock.instant());
        try {
            MediaAsset inserted = jdbcOps.insert(toInsert);
            return new UploadOriginalResponse(inserted.id(), inserted.status(), false);
        } catch (DuplicateKeyException e) {
            // 같은 사용자가 같은 파일을 동시에 두 번 올린 경합. UNIQUE(owner_id, content_hash)
            // 충돌은 에러가 아니라 정상 경로다 (pipeline.md §10.1).
            MediaAsset asset = assets.findByOwnerIdAndContentHash(ownerId, stored.hash().hex()).orElseThrow(() -> e);
            return new UploadOriginalResponse(asset.id(), asset.status(), true);
        }
    }
}
