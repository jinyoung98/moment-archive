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

import dev.jinyoung.archive.auth.CurrentUser;
import dev.jinyoung.archive.media.ContentAddressedStore;
import dev.jinyoung.archive.media.DerivativeRecipe;
import dev.jinyoung.archive.media.MediaAsset;
import dev.jinyoung.archive.media.MediaAssetRepository;
import dev.jinyoung.archive.media.MediaAssetStatus;
import dev.jinyoung.archive.media.MediaDerivativeRepository;
import dev.jinyoung.archive.media.MediaKind;
import dev.jinyoung.archive.media.StoredOriginal;
import dev.jinyoung.archive.processing.MediaProcessingService;

/**
 * W1 단일 파일 업로드. 청크·세션 없음 (pipeline.md 는 W2 이후 프로토콜).
 *
 * 스토리지 계층(media 패키지)은 DB 를 모르는 채로 끝남 — 자산 행 기록은 여기서 처음 등장.
 * 처리(PROBE/THUMB)는 W1 동기 실행이라 업로드 요청 안에서 바로 돈다 (roadmap.md §2).
 */
@Service
public class MediaUploadService {

    private static final DerivativeRecipe THUMB = DerivativeRecipe.THUMB_256;

    private final ContentAddressedStore store;
    private final MediaAssetRepository assets;
    private final MediaDerivativeRepository derivatives;
    private final MediaProcessingService processing;
    private final JdbcAggregateOperations jdbcOps;
    private final CurrentUser currentUser;
    private final Clock clock;

    public MediaUploadService(
            ContentAddressedStore store,
            MediaAssetRepository assets,
            MediaDerivativeRepository derivatives,
            MediaProcessingService processing,
            JdbcAggregateOperations jdbcOps,
            CurrentUser currentUser,
            Clock clock) {
        this.store = store;
        this.assets = assets;
        this.derivatives = derivatives;
        this.processing = processing;
        this.jdbcOps = jdbcOps;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public UploadOriginalResponse upload(MultipartFile file) throws IOException {
        String contentType = file.getContentType();
        MediaKind kind = MediaKind.fromMimeType(contentType);
        UUID ownerId = currentUser.requireUserId();

        Path tempFile = Files.createTempFile("upload-original-", ".tmp");
        try {
            file.transferTo(tempFile);
            StoredOriginal stored = store.storeOriginal(tempFile, contentType);
            Resolved resolved = findOrCreateAsset(ownerId, stored, contentType, kind);
            MediaAsset asset = maybeProcess(resolved.asset());
            return new UploadOriginalResponse(asset.id(), asset.status(), resolved.reused(), thumbKeyOf(asset));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /** 새 자산은 처리, 이미 처리된 중복은 건너뜀 (중복은 전송도 처리도 없음, pipeline.md §3). */
    private MediaAsset maybeProcess(MediaAsset asset) {
        if (asset.status() == MediaAssetStatus.INGESTED) {
            return processing.process(asset);
        }
        return asset;
    }

    private String thumbKeyOf(MediaAsset asset) {
        return derivatives
                .findByAssetIdAndRecipeAndRecipeVer(asset.id(), THUMB.recipeName(), THUMB.version())
                .map(d -> d.storageKey())
                .orElse(null);
    }

    private Resolved findOrCreateAsset(UUID ownerId, StoredOriginal stored, String contentType, MediaKind kind) {
        var existing = assets.findByOwnerIdAndContentHash(ownerId, stored.hash().hex());
        if (existing.isPresent()) {
            return new Resolved(existing.get(), true);
        }

        MediaAsset toInsert = MediaAsset.newlyIngested(
                UUID.randomUUID(), ownerId, stored, contentType, kind, clock.instant());
        try {
            return new Resolved(jdbcOps.insert(toInsert), false);
        } catch (DuplicateKeyException e) {
            // 같은 사용자가 같은 파일을 동시에 두 번 올린 경합. UNIQUE(owner_id, content_hash)
            // 충돌은 에러가 아닌 정상 경로 (pipeline.md §10.1).
            MediaAsset asset = assets.findByOwnerIdAndContentHash(ownerId, stored.hash().hex()).orElseThrow(() -> e);
            return new Resolved(asset, true);
        }
    }

    /** 자산 행 + 이미 있던 것인지 여부. */
    private record Resolved(MediaAsset asset, boolean reused) {
    }
}
