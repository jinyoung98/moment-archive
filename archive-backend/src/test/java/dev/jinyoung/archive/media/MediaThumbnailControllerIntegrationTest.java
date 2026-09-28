package dev.jinyoung.archive.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static dev.jinyoung.archive.support.AuthTestSupport.archiveUser;
import static dev.jinyoung.archive.support.AuthTestSupport.asUser;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
import dev.jinyoung.archive.processing.JobWorker;
import dev.jinyoung.archive.processing.job.Lane;
import dev.jinyoung.archive.processing.thumb.Thumbnailer;
import dev.jinyoung.archive.support.IntegrationTest;
import dev.jinyoung.archive.upload.UploadOriginalResponse;

/**
 * GET /media/{id}/thumbnail 서빙 검증. 업로드 + 워커 drain 으로 실제 파생물을 만든 뒤 그 바이트를 되받는다.
 *
 * THUMB 바이트 단정은 libvips 게이트({@code assumeTrue}) — 없으면 THUMBED 에 못 가 파생물이 없고,
 * 그때 서빙 결과는 404 다(그 경로는 libvips 무관하게 검증 가능). 소유·부재 404 는 항상 검증한다.
 */
@AutoConfigureMockMvc
class MediaThumbnailControllerIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MediaAssetRepository assets;
    @Autowired
    private ObjectStorage storage;
    @Autowired
    private UserAccountService userAccounts;
    @Autowired
    private Thumbnailer thumbnailer;
    @Autowired
    private JobWorker worker;

    private final ObjectMapper json = new ObjectMapper();

    private ArchiveUser owner;

    @BeforeEach
    void 정리한다() {
        assets.deleteAll();     // media_derivatives 는 FK ON DELETE CASCADE 로 함께 삭제
        storage.listKeys(StorageKeys.ORIGINALS_PREFIX).forEach(storage::delete);
        storage.listKeys(StorageKeys.DERIVATIVES_PREFIX).forEach(storage::delete);
        User user = userAccounts.findOrCreate("GOOGLE", "owner-sub", "owner@example.com", "주인");
        owner = archiveUser(user.id(), user.email());
    }

    @Test
    @DisplayName("libvips 가 있으면 올린 사진의 썸네일을 webp 로 되받는다")
    void 썸네일_서빙() throws Exception {
        assumeTrue(thumbnailer.isAvailable(), "libvips 미설치 — 썸네일 바이트 서빙 스킵");

        UUID assetId = upload(jpeg(800, 600));
        worker.drain(Lane.PHOTO);   // 처리는 비동기 — 워커를 직접 돌려 THUMBED 까지

        MvcResult result = mockMvc.perform(get("/media/{id}/thumbnail", assetId).with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/webp"))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        assertThat(body.length).isEqualTo((int) result.getResponse().getContentLengthLong());
    }

    @Test
    @DisplayName("처리 전이면 썸네일은 404 — 프론트는 플레이스홀더 유지")
    void 처리_전은_404() throws Exception {
        UUID assetId = upload(jpeg(320, 240));   // drain 안 함 → INGESTED

        mockMvc.perform(get("/media/{id}/thumbnail", assetId).with(asUser(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 자산은 404")
    void 부재는_404() throws Exception {
        mockMvc.perform(get("/media/{id}/thumbnail", UUID.randomUUID()).with(asUser(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("남의 자산은 존재를 흘리지 않고 404")
    void 타인_자산은_404() throws Exception {
        UUID assetId = upload(jpeg(320, 240));   // owner 가 올림

        User other = userAccounts.findOrCreate("GOOGLE", "other-sub", "other@example.com", "남");
        ArchiveUser stranger = archiveUser(other.id(), other.email());

        mockMvc.perform(get("/media/{id}/thumbnail", assetId).with(asUser(stranger)))
                .andExpect(status().isNotFound());
    }

    private UUID upload(byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", content);
        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .multipart(HttpMethod.PUT, "/media")
                                .file(file)
                                .with(asUser(owner))
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), UploadOriginalResponse.class).assetId();
    }

    /** EXIF 없는 유효 JPEG. UploadControllerIntegrationTest 와 같은 방식. */
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
