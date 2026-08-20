package dev.jinyoung.archive.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;

import dev.jinyoung.archive.auth.DevUserProvider;
import dev.jinyoung.archive.media.ContentAddressedStore;
import dev.jinyoung.archive.media.DerivativeRecipe;
import dev.jinyoung.archive.media.MediaAsset;
import dev.jinyoung.archive.media.MediaAssetRepository;
import dev.jinyoung.archive.media.MediaAssetStatus;
import dev.jinyoung.archive.media.MediaDerivative;
import dev.jinyoung.archive.media.MediaDerivativeRepository;
import dev.jinyoung.archive.media.MediaKind;
import dev.jinyoung.archive.media.ObjectStorage;
import dev.jinyoung.archive.media.StorageKeys;
import dev.jinyoung.archive.media.StoredOriginal;
import dev.jinyoung.archive.processing.thumb.Thumbnailer;
import dev.jinyoung.archive.support.IntegrationTest;

/**
 * PROBE → THUMB 동기 처리 통합 검증. 실제 MinIO 위에서 돈다.
 *
 * PROBE·상태전이·멱등(I9)은 어디서나 검증한다. THUMB 은 libvips 가 있어야 하므로
 * {@code assumeTrue} 로 게이트한다 — 골든셋 파일 부재 시 스킵과 같은 결(roadmap.md §5).
 * libvips 없으면 그 단정만 건너뛰고 나머지는 그대로 돈다.
 */
class MediaProcessingIntegrationTest extends IntegrationTest {

    private static final DerivativeRecipe THUMB = DerivativeRecipe.THUMB_256;

    @Autowired
    private MediaProcessingService processing;
    @Autowired
    private ContentAddressedStore store;
    @Autowired
    private ObjectStorage storage;
    @Autowired
    private MediaAssetRepository assets;
    @Autowired
    private MediaDerivativeRepository derivatives;
    @Autowired
    private Thumbnailer thumbnailer;
    @Autowired
    private DevUserProvider ownerProvider;
    @Autowired
    private JdbcAggregateOperations jdbcOps;
    @Autowired
    private Clock clock;

    @BeforeEach
    void 정리한다() {
        assets.deleteAll();     // media_derivatives 는 FK ON DELETE CASCADE 로 함께 삭제
        storage.listKeys(StorageKeys.ORIGINALS_PREFIX).forEach(storage::delete);
        storage.listKeys(StorageKeys.DERIVATIVES_PREFIX).forEach(storage::delete);
    }

    @Test
    @DisplayName("사진을 처리하면 PROBED 로 크기가 채워지고, libvips 가 있으면 THUMBED + 파생물이 생긴다")
    void 처리_해피패스() throws Exception {
        MediaAsset ingested = ingestPhoto(1024, 768);

        MediaAsset processed = processing.process(ingested);

        assertThat(processed.width()).isEqualTo(1024);
        assertThat(processed.height()).isEqualTo(768);
        assertThat(processed.status()).isIn(MediaAssetStatus.PROBED, MediaAssetStatus.THUMBED);

        assumeTrue(thumbnailer.isAvailable(), "libvips 미설치 — THUMB 단정 스킵");

        assertThat(processed.status()).isEqualTo(MediaAssetStatus.THUMBED);
        MediaDerivative thumb = derivatives
                .findByAssetIdAndRecipeAndRecipeVer(ingested.id(), THUMB.recipeName(), THUMB.version())
                .orElseThrow();
        assertThat(thumb.byteSize()).isPositive();
        assertThat(storage.exists(thumb.storageKey())).isTrue();
    }

    @Test
    @DisplayName("두 번 처리해도 결과가 같다 (I9 멱등)")
    void 멱등() throws Exception {
        MediaAsset ingested = ingestPhoto(640, 640);

        MediaAsset first = processing.process(ingested);
        // 두 번째는 이미 PROBED/THUMBED 인 자산을 다시 통과 — 같은 메타 덮어쓰기, 같은 키 재사용.
        MediaAsset second = processing.process(first);

        assertThat(second.status()).isEqualTo(first.status());
        assertThat(second.width()).isEqualTo(first.width());
        assertThat(second.height()).isEqualTo(first.height());

        assumeTrue(thumbnailer.isAvailable(), "libvips 미설치 — THUMB 멱등 단정 스킵");

        // 파생물 행은 UPSERT 라 여전히 하나, content_hash 동일 (결정론, I3 선취).
        var one = derivatives.findByAssetIdAndRecipeAndRecipeVer(ingested.id(), THUMB.recipeName(), THUMB.version());
        var two = derivatives.findByAssetIdAndRecipeAndRecipeVer(ingested.id(), THUMB.recipeName(), THUMB.version());
        assertThat(one).isPresent();
        assertThat(two.orElseThrow().contentHash()).isEqualTo(one.orElseThrow().contentHash());
    }

    /** 원본을 MinIO 에 올리고 INGESTED 자산 행을 만든다 — 업로드가 끝난 직후 상태. */
    private MediaAsset ingestPhoto(int width, int height) throws Exception {
        Path temp = Files.createTempFile("test-original-", ".jpg");
        try {
            writeJpeg(temp, width, height);
            StoredOriginal stored = store.storeOriginal(temp, "image/jpeg");
            UUID ownerId = ownerProvider.currentUserId();
            MediaAsset ingested = MediaAsset.newlyIngested(
                    UUID.randomUUID(), ownerId, stored, "image/jpeg", MediaKind.PHOTO, clock.instant());
            return jdbcOps.insert(ingested);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void writeJpeg(Path file, int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 120, 60));
        g.fillRect(0, 0, width, height);
        g.dispose();
        try (OutputStream out = Files.newOutputStream(file)) {
            ImageIO.write(img, "jpg", out);
        }
    }
}
