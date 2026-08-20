package dev.jinyoung.archive.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import dev.jinyoung.archive.media.MediaAssetRepository;
import dev.jinyoung.archive.media.MediaAssetStatus;
import dev.jinyoung.archive.media.ObjectStorage;
import dev.jinyoung.archive.media.StorageKeys;
import dev.jinyoung.archive.support.IntegrationTest;

/**
 * 단일 파일 업로드 W1 수직 슬라이스: PUT /media → CAS 저장 → media_assets 행 → 동기 처리.
 *
 * 처리가 요청 안에서 돌기 때문에(roadmap.md §2) 유효한 이미지 바이트가 필요하다 — 예전의
 * 랜덤 바이트는 PROBE 에서 손상으로 판정된다. 그래서 ImageIO 로 실제 JPEG 을 만든다.
 *
 * libvips 없는 환경에서는 THUMB 가 건너뛰어져 PROBED 에서 멈춘다 (VipsThumbnailer 참조).
 * 그래서 상태 단정은 PROBED 이상으로 느슨하게 둔다 — THUMB 바이트 검증은 별도 처리 테스트에서.
 */
@AutoConfigureMockMvc
class UploadControllerIntegrationTest extends IntegrationTest {

    private static final long SEED = 20260813L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MediaAssetRepository assets;

    @Autowired
    private ObjectStorage storage;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void 정리한다() {
        assets.deleteAll();     // media_derivatives 는 FK ON DELETE CASCADE 로 함께 삭제
        storage.listKeys(StorageKeys.ORIGINALS_PREFIX).forEach(storage::delete);
        storage.listKeys(StorageKeys.DERIVATIVES_PREFIX).forEach(storage::delete);
    }

    @Test
    @DisplayName("새 사진을 올리면 PROBE 로 크기가 채워지고 최소 PROBED 까지 진행한다")
    void 새_파일_업로드() throws Exception {
        byte[] content = jpeg(640, 480);

        UploadOriginalResponse response = upload(content, "photo.jpg", "image/jpeg");

        assertThat(response.reused()).isFalse();
        assertThat(response.status())
                .isIn(MediaAssetStatus.PROBED, MediaAssetStatus.THUMBED);

        var stored = assets.findById(response.assetId()).orElseThrow();
        assertThat(stored.byteSize()).isEqualTo(content.length);
        assertThat(stored.mimeType()).isEqualTo("image/jpeg");
        assertThat(stored.width()).isEqualTo(640);
        assertThat(stored.height()).isEqualTo(480);
    }

    @Test
    @DisplayName("같은 사용자가 같은 내용을 다시 올리면 기존 자산을 재사용하고 새 행을 만들지 않는다")
    void 중복_업로드는_재사용() throws Exception {
        byte[] content = jpeg(320, 240);

        UploadOriginalResponse first = upload(content, "a.jpg", "image/jpeg");
        UploadOriginalResponse second = upload(content, "b.jpg", "image/jpeg");

        assertThat(first.reused()).isFalse();
        assertThat(second.reused()).isTrue();
        assertThat(second.assetId()).isEqualTo(first.assetId());
        assertThat(assets.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("손상된 이미지는 PROBE 에서 걸러져 FAILED 가 된다")
    void 손상된_이미지는_FAILED() throws Exception {
        byte[] garbage = new byte[4096];
        new Random(SEED).nextBytes(garbage);       // JPEG 헤더가 아닌 임의 바이트

        UploadOriginalResponse response = upload(garbage, "broken.jpg", "image/jpeg");

        assertThat(response.status()).isEqualTo(MediaAssetStatus.FAILED);
        assertThat(response.thumbKey()).isNull();
    }

    @Test
    @DisplayName("지원하지 않는 형식은 400을 돌려준다")
    void 지원하지_않는_형식은_400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart(HttpMethod.PUT, "/media").file(file))
                .andExpect(status().isBadRequest());
    }

    private UploadOriginalResponse upload(byte[] content, String filename, String contentType) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, contentType, content);

        MvcResult result = mockMvc.perform(multipart(HttpMethod.PUT, "/media").file(file))
                .andExpect(status().isOk())
                .andReturn();

        return json.readValue(result.getResponse().getContentAsString(), UploadOriginalResponse.class);
    }

    /** EXIF 없는 유효 JPEG. 크기는 SOF 마커에서 읽히므로 PROBE 가 width/height 를 채운다. */
    private byte[] jpeg(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(120, 140, 160));
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
