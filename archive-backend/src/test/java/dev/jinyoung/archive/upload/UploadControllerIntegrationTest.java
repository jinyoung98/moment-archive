package dev.jinyoung.archive.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static dev.jinyoung.archive.support.AuthTestSupport.archiveUser;
import static dev.jinyoung.archive.support.AuthTestSupport.asUser;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.UUID;

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

import dev.jinyoung.archive.auth.ArchiveUser;
import dev.jinyoung.archive.auth.User;
import dev.jinyoung.archive.auth.UserAccountService;
import dev.jinyoung.archive.media.MediaAssetRepository;
import dev.jinyoung.archive.media.MediaAssetStatus;
import dev.jinyoung.archive.media.ObjectStorage;
import dev.jinyoung.archive.media.StorageKeys;
import dev.jinyoung.archive.processing.JobWorker;
import dev.jinyoung.archive.processing.job.Lane;
import dev.jinyoung.archive.support.IntegrationTest;

/**
 * 단일 파일 업로드: PUT /media → CAS 저장 → media_assets 행 + PROBE 작업 등록 → 즉시 응답.
 * 처리는 워커 몫 — 응답은 INGESTED, 결과는 {@code worker.drain()} 뒤 GET /media/{id} 로 확인.
 *
 * PROBE 가 실제로 이미지를 읽으므로 유효한 JPEG 이 필요 (랜덤 바이트는 손상 판정). libvips 없는
 * 환경에선 THUMB 작업이 DEAD 되고 PROBED 에 멈춤 — 상태 단정은 PROBED 이상으로 느슨하게.
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

    @Autowired
    private UserAccountService userAccounts;

    @Autowired
    private JobWorker worker;

    private final ObjectMapper json = new ObjectMapper();

    private ArchiveUser owner;

    @BeforeEach
    void 정리한다() {
        assets.deleteAll();     // media_derivatives 는 FK ON DELETE CASCADE 로 함께 삭제
        storage.listKeys(StorageKeys.ORIGINALS_PREFIX).forEach(storage::delete);
        storage.listKeys(StorageKeys.DERIVATIVES_PREFIX).forEach(storage::delete);
        User user = userAccounts.findOrCreate("GOOGLE", "test-sub", "me@example.com", "나");
        owner = archiveUser(user.id(), user.email());
    }

    @Test
    @DisplayName("새 사진을 올리면 처리 전 INGESTED 로 즉시 응답하고, 워커가 돌면 PROBED 이상이 된다")
    void 새_파일_업로드() throws Exception {
        byte[] content = jpeg(640, 480);

        UploadOriginalResponse response = upload(content, "photo.jpg", "image/jpeg");

        assertThat(response.reused()).isFalse();
        assertThat(response.status()).isEqualTo(MediaAssetStatus.INGESTED);
        assertThat(response.thumbKey()).isNull();

        worker.drain(Lane.PHOTO);

        var stored = assets.findById(response.assetId()).orElseThrow();
        assertThat(stored.byteSize()).isEqualTo(content.length);
        assertThat(stored.mimeType()).isEqualTo("image/jpeg");
        assertThat(stored.width()).isEqualTo(640);
        assertThat(stored.height()).isEqualTo(480);
        assertThat(stored.status()).isIn(MediaAssetStatus.PROBED, MediaAssetStatus.THUMBED);
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
        // 작업도 하나만 — 중복은 처리도 없음. PROBE 1 + THUMB 1.
        assertThat(worker.drain(Lane.PHOTO)).isEqualTo(2);
    }

    @Test
    @DisplayName("손상된 이미지는 워커의 PROBE 에서 걸러져 FAILED 가 된다")
    void 손상된_이미지는_FAILED() throws Exception {
        byte[] garbage = new byte[4096];
        new Random(SEED).nextBytes(garbage);       // JPEG 헤더가 아닌 임의 바이트

        UploadOriginalResponse response = upload(garbage, "broken.jpg", "image/jpeg");
        worker.drain(Lane.PHOTO);

        assertThat(assets.findById(response.assetId()).orElseThrow().status()).isEqualTo(MediaAssetStatus.FAILED);
    }

    @Test
    @DisplayName("GET /media/{id} 는 처리 진행에 따라 상태를 돌려준다")
    void 상태_조회() throws Exception {
        UUID assetId = upload(jpeg(200, 100), "s.jpg", "image/jpeg").assetId();

        mockMvc.perform(get("/media/{id}", assetId).with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INGESTED"))
                .andExpect(jsonPath("$.processing").value(true));

        worker.drain(Lane.PHOTO);

        var after = json.readTree(mockMvc.perform(get("/media/{id}", assetId).with(asUser(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(after.get("status").asString()).isIn("PROBED", "THUMBED");
        assertThat(after.get("processing").asBoolean()).isFalse();   // 큐가 비었으니 폴링 멈춤
    }

    @Test
    @DisplayName("남의 자산 상태는 존재를 흘리지 않고 404")
    void 타인_자산_상태는_404() throws Exception {
        UUID assetId = upload(jpeg(200, 100), "s.jpg", "image/jpeg").assetId();

        User other = userAccounts.findOrCreate("GOOGLE", "other-sub", "other@example.com", "남");
        mockMvc.perform(get("/media/{id}", assetId).with(asUser(archiveUser(other.id(), other.email()))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("지원하지 않는 형식은 400을 돌려준다")
    void 지원하지_않는_형식은_400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart(HttpMethod.PUT, "/media").file(file).with(asUser(owner)).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private UploadOriginalResponse upload(byte[] content, String filename, String contentType) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, contentType, content);

        MvcResult result = mockMvc.perform(
                        multipart(HttpMethod.PUT, "/media").file(file).with(asUser(owner)).with(csrf()))
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
