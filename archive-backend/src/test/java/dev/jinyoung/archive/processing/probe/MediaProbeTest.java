package dev.jinyoung.archive.processing.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PROBE 는 순수 Java(metadata-extractor)라 컨테이너 없이 검증한다 (design.md §10 테스트 전략).
 *
 * EXIF 있는 사진(회전 플래그·GPS·촬영시각)은 골든셋(W4) 몫이다 — 여기서는 EXIF 없는 파일과
 * 손상 파일이라는, 합성만으로 재현되는 두 경로를 고정한다.
 */
class MediaProbeTest {

    private final MediaProbe probe = new MediaProbe();

    @Test
    @DisplayName("EXIF 없는 JPEG 은 크기만 채우고 촬영시각·GPS·회전은 null")
    void EXIF_없는_JPEG(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("plain.jpg");
        writeJpeg(file, 800, 600);

        ProbeResult result = probe.probe(file);

        assertThat(result.width()).isEqualTo(800);
        assertThat(result.height()).isEqualTo(600);
        assertThat(result.capturedAt()).isNull();
        assertThat(result.capturedAtSrc()).isNull();
        assertThat(result.orientation()).isNull();
        assertThat(result.gpsLat()).isNull();
        assertThat(result.gpsLon()).isNull();
        assertThat(result.durationMs()).isNull();
    }

    @Test
    @DisplayName("이미지로 해석할 수 없는 바이트는 UnreadableMediaException")
    void 손상_파일(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.jpg");
        byte[] garbage = new byte[2048];
        new Random(42).nextBytes(garbage);
        Files.write(file, garbage);

        assertThatThrownBy(() -> probe.probe(file))
                .isInstanceOf(UnreadableMediaException.class);
    }

    private void writeJpeg(Path file, int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(30, 90, 150));
        g.fillRect(0, 0, width, height);
        g.dispose();
        try (OutputStream out = Files.newOutputStream(file)) {
            ImageIO.write(img, "jpg", out);
        }
    }
}
