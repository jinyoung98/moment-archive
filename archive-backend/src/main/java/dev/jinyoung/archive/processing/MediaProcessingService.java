package dev.jinyoung.archive.processing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Service;

import dev.jinyoung.archive.media.ContentHash;
import dev.jinyoung.archive.media.DerivativeRecipe;
import dev.jinyoung.archive.media.MediaAsset;
import dev.jinyoung.archive.media.MediaAssetStatus;
import dev.jinyoung.archive.media.MediaDerivativeRepository;
import dev.jinyoung.archive.media.MediaKind;
import dev.jinyoung.archive.media.ObjectStorage;
import dev.jinyoung.archive.media.StorageException;
import dev.jinyoung.archive.media.StorageKeys;
import dev.jinyoung.archive.processing.probe.MediaProbe;
import dev.jinyoung.archive.processing.probe.ProbeResult;
import dev.jinyoung.archive.processing.probe.UnreadableMediaException;
import dev.jinyoung.archive.processing.thumb.ThumbnailException;
import dev.jinyoung.archive.processing.thumb.Thumbnailer;

/**
 * PROBE → THUMB 를 동기로 실행 (W1). W2 부터 이 로직이 SKIP LOCKED 워커로 옮겨감 —
 * 지금은 큐 없이 업로드 요청 안에서 바로 돈다 (roadmap.md §2, pipeline.md §5).
 *
 * 원본을 스토리지에서 다시 읽어 처리한다. 방금 올린 임시 파일을 재사용하지 않는 이유:
 * 이 모양이 곧 W2 워커의 모양이고, 저장 바이트를 되읽어 I1 을 한 번 더 통과시킴.
 *
 * 각 단계 멱등 (I9). lease 회수(W2)로 두 번 실행돼도 같은 메타를 덮어쓰고 같은 키에 다시 씀.
 * 상태 전이: INGESTED → PROBED → THUMBED. READY 는 DERIVE(W2) 이후.
 */
@Service
public class MediaProcessingService {

    private static final Logger log = LoggerFactory.getLogger(MediaProcessingService.class);
    private static final DerivativeRecipe THUMB = DerivativeRecipe.THUMB_256;

    private final ObjectStorage storage;
    private final MediaProbe probe;
    private final Thumbnailer thumbnailer;
    private final MediaDerivativeRepository derivatives;
    private final JdbcAggregateOperations jdbcOps;
    private final Clock clock;

    public MediaProcessingService(
            ObjectStorage storage,
            MediaProbe probe,
            Thumbnailer thumbnailer,
            MediaDerivativeRepository derivatives,
            JdbcAggregateOperations jdbcOps,
            Clock clock) {
        this.storage = storage;
        this.probe = probe;
        this.thumbnailer = thumbnailer;
        this.derivatives = derivatives;
        this.jdbcOps = jdbcOps;
        this.clock = clock;
    }

    /** 자산 하나를 끝까지(W1 기준 THUMBED) 처리. 최종 상태의 자산을 반환. */
    public MediaAsset process(MediaAsset asset) {
        if (asset.kind() != MediaKind.PHOTO) {
            // 영상 처리(ffprobe·poster·트랜스코딩)는 W2. W1 은 INGESTED 로 둠.
            log.info("영상은 W1 처리 대상 아님, INGESTED 유지: {}", asset.id());
            return asset;
        }

        Path original = download(asset);
        try {
            MediaAsset probed = runProbe(asset, original);
            if (probed.status() == MediaAssetStatus.FAILED) {
                return probed;
            }
            return runThumb(probed, original);
        } finally {
            deleteQuietly(original);
        }
    }

    private MediaAsset runProbe(MediaAsset asset, Path original) {
        ProbeResult result;
        try {
            result = probe.probe(original);
        } catch (UnreadableMediaException e) {
            // 손상·미지원 = 영구 실패. 원본은 보존, 처리만 FAILED (pipeline.md §5.5).
            log.warn("PROBE 실패, 자산 FAILED: {} ({})", asset.id(), e.getMessage());
            return persist(asset.withStatus(MediaAssetStatus.FAILED));
        }
        MediaAsset probed = asset.probed(
                result.capturedAt(), result.capturedAtSrc(), result.tzOffsetMin(),
                result.gpsLat(), result.gpsLon(),
                result.width(), result.height(), result.orientation(), result.durationMs());
        return persist(probed);
    }

    private MediaAsset runThumb(MediaAsset asset, Path original) {
        if (!thumbnailer.isAvailable()) {
            // libvips 미설치 = 배포 문제이지 미디어 문제 아님. FAILED 로 만들지 않고 PROBED 유지 (P5).
            log.warn("libvips 없음, THUMB 건너뜀 (PROBED 유지): {}", asset.id());
            return asset;
        }
        Path thumb = null;
        try {
            thumb = Files.createTempFile("thumb-", ".webp");
            thumbnailer.thumbnail(original, thumb);

            ContentHash thumbHash = ContentHash.of(thumb);
            long byteSize = Files.size(thumb);
            String key = StorageKeys.derivative(new ContentHash(asset.contentHash()), THUMB);

            storage.put(key, thumb, "image/webp", thumbHash);
            derivatives.upsert(
                    UUID.randomUUID(), asset.id(), THUMB.recipeName(), THUMB.version(),
                    key, byteSize, thumbHash.hex(), clock.instant());

            return persist(asset.withStatus(MediaAssetStatus.THUMBED));
        } catch (ThumbnailException e) {
            // 파생물 생성 실패는 원본과 무관 — PROBED 에 남겨 나중 재시도 (P5).
            log.warn("THUMB 실패 (PROBED 유지): {} ({})", asset.id(), e.getMessage());
            return asset;
        } catch (IOException e) {
            throw new ThumbnailException("썸네일 임시 파일 처리 실패", e);
        } finally {
            deleteQuietly(thumb);
        }
    }

    private Path download(MediaAsset asset) {
        // 내려받기 실패는 스토리지 I/O — 일시적 분류 (pipeline.md §5.5). 미디어 손상이 아니므로
        // UnreadableMediaException(영구)이 아닌 StorageException. 자산을 FAILED 로 만들지 않는다.
        try {
            Path temp = Files.createTempFile("process-original-", ".tmp");
            try (InputStream in = storage.open(asset.storageKey())) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            return temp;
        } catch (IOException e) {
            throw new StorageException("원본 내려받기 실패: " + asset.storageKey(), e);
        }
    }

    private MediaAsset persist(MediaAsset asset) {
        return jdbcOps.update(asset);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("임시 파일 삭제 실패: {}", path, e);
        }
    }
}
