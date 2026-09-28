package dev.jinyoung.archive.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import dev.jinyoung.archive.auth.CurrentUser;
import dev.jinyoung.archive.media.ContentAddressedStore;
import dev.jinyoung.archive.media.DerivativeRecipe;
import dev.jinyoung.archive.media.MediaAsset;
import dev.jinyoung.archive.media.MediaAssetRepository;
import dev.jinyoung.archive.media.MediaDerivativeRepository;
import dev.jinyoung.archive.media.MediaKind;
import dev.jinyoung.archive.media.StoredOriginal;
import dev.jinyoung.archive.processing.job.JobQueue;
import dev.jinyoung.archive.processing.job.Lane;
import dev.jinyoung.archive.processing.job.Stage;
import dev.jinyoung.archive.processing.job.WorkerProperties;

/**
 * 단일 파일 업로드. 청크·세션 없음 — 세션 프로토콜(pipeline.md §2)은 W2 다음 단계.
 *
 * 스토리지 계층(media 패키지)은 DB 를 모르는 채로 끝남 — 자산 행 기록은 여기서 처음 등장.
 * 처리는 하지 않음. 새 자산이면 PROBE 작업만 <b>같은 트랜잭션</b>에서 등록하고 즉시 응답 —
 * "자산은 있는데 처리 작업이 없음" 상태가 존재 불가. 결과는 워커가 뒤에서 (pipeline.md §5).
 */
@Service
public class MediaUploadService {

    private static final DerivativeRecipe THUMB = DerivativeRecipe.THUMB_256;

    private final ContentAddressedStore store;
    private final MediaAssetRepository assets;
    private final MediaDerivativeRepository derivatives;
    private final JobQueue jobs;
    private final WorkerProperties workerProperties;
    private final TransactionTemplate tx;
    private final CurrentUser currentUser;
    private final Clock clock;

    public MediaUploadService(
            ContentAddressedStore store,
            MediaAssetRepository assets,
            MediaDerivativeRepository derivatives,
            JobQueue jobs,
            WorkerProperties workerProperties,
            TransactionTemplate tx,
            CurrentUser currentUser,
            Clock clock) {
        this.store = store;
        this.assets = assets;
        this.derivatives = derivatives;
        this.jobs = jobs;
        this.workerProperties = workerProperties;
        this.tx = tx;
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
            // 원본 저장은 트랜잭션 밖. 스토리지 쓰기 동안 커넥션을 잡지 않음 — CAS 라 재시도 안전 (I1).
            StoredOriginal stored = store.storeOriginal(tempFile, contentType);
            Resolved resolved = tx.execute(s -> findOrCreateAsset(ownerId, stored, contentType, kind));
            MediaAsset asset = resolved.asset();
            return new UploadOriginalResponse(asset.id(), asset.status(), resolved.reused(), thumbKeyOf(asset));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private String thumbKeyOf(MediaAsset asset) {
        return derivatives
                .findByAssetIdAndRecipeAndRecipeVer(asset.id(), THUMB.recipeName(), THUMB.version())
                .map(d -> d.storageKey())
                .orElse(null);
    }

    /**
     * 트랜잭션 안. 새 자산이면 PROBE 등록까지. 이미 있으면(중복·동시 업로드 경합) 기존 행 재사용 —
     * 중복은 전송도 처리도 없음 (pipeline.md §2). FAILED 자산 재처리는 세션 API 의 해시 선조회에서.
     */
    private Resolved findOrCreateAsset(UUID ownerId, StoredOriginal stored, String contentType, MediaKind kind) {
        String hash = stored.hash().hex();
        var existing = assets.findByOwnerIdAndContentHash(ownerId, hash);
        if (existing.isPresent()) {
            return new Resolved(existing.get(), true);
        }

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        int inserted = assets.insertIngestedIfAbsent(
                id, ownerId, hash, stored.byteSize(), contentType, kind.name(), stored.storageKey(), now);
        if (inserted == 0) {
            // 같은 사용자가 같은 파일을 동시에 두 번 올린 경합. 에러 아닌 정상 경로 (§10.1).
            return new Resolved(assets.findByOwnerIdAndContentHash(ownerId, hash).orElseThrow(), true);
        }
        jobs.enqueue(id, Stage.PROBE, Lane.of(kind), workerProperties.maxAttempts(), now);
        return new Resolved(assets.findById(id).orElseThrow(), false);
    }

    /** 자산 행 + 이미 있던 것인지 여부. */
    private record Resolved(MediaAsset asset, boolean reused) {
    }
}
