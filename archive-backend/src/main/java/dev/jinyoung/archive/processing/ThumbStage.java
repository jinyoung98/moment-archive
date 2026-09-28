package dev.jinyoung.archive.processing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import dev.jinyoung.archive.media.ContentHash;
import dev.jinyoung.archive.media.DerivativeRecipe;
import dev.jinyoung.archive.media.MediaAsset;
import dev.jinyoung.archive.media.ObjectStorage;
import dev.jinyoung.archive.media.StorageKeys;
import dev.jinyoung.archive.processing.job.Stage;
import dev.jinyoung.archive.processing.thumb.ThumbnailException;
import dev.jinyoung.archive.processing.thumb.Thumbnailer;

/**
 * THUMB — libvips 256px webp. 멱등: 키가 원본 해시 기반이라 재실행해도 같은 자리에 덮어씀
 * (pipeline.md §6). 스토리지 쓰기는 여기서, 파생물 행은 JobWorker 트랜잭션에서.
 *
 * {@link ThumbnailException} 은 일시적으로 분류 — 타임아웃·비정상 종료는 부하 탓일 수 있음.
 * 계속 실패하면 max_attempts 에서 DEAD, 자산은 PROBED 유지 (P5).
 */
@Component
public class ThumbStage implements StageProcessor {

    private static final DerivativeRecipe THUMB = DerivativeRecipe.THUMB_256;

    private final OriginalFetcher fetcher;
    private final Thumbnailer thumbnailer;
    private final ObjectStorage storage;

    public ThumbStage(OriginalFetcher fetcher, Thumbnailer thumbnailer, ObjectStorage storage) {
        this.fetcher = fetcher;
        this.thumbnailer = thumbnailer;
        this.storage = storage;
    }

    @Override
    public Stage stage() {
        return Stage.THUMB;
    }

    @Override
    public StageOutcome run(MediaAsset asset) {
        if (!thumbnailer.isAvailable()) {
            throw new ToolUnavailableException("libvips 없음 — THUMB 불가");
        }
        Path original = fetcher.download(asset);
        Path thumb = null;
        try {
            thumb = Files.createTempFile("thumb-", ".webp");
            thumbnailer.thumbnail(original, thumb);

            ContentHash thumbHash = ContentHash.of(thumb);
            long byteSize = Files.size(thumb);
            String key = StorageKeys.derivative(new ContentHash(asset.contentHash()), THUMB);
            storage.put(key, thumb, "image/webp", thumbHash);

            return new StageOutcome.Thumbed(THUMB.recipeName(), THUMB.version(), key, byteSize, thumbHash.hex());
        } catch (IOException e) {
            throw new ThumbnailException("썸네일 임시 파일 처리 실패", e);
        } finally {
            OriginalFetcher.deleteQuietly(original);
            OriginalFetcher.deleteQuietly(thumb);
        }
    }
}
