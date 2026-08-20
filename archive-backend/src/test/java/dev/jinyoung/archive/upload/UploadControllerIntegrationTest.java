package dev.jinyoung.archive.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Random;

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
 * 단일 파일 업로드 W1 수직 슬라이스: PUT /media → CAS 저장 → media_assets 행.
 *
 * 이 파일이 자산 행을 처음 쓰는 자리다. DB 정리도 여기서 처음 필요해졌다 — 지금까지의
 * 통합 테스트는 스토리지만 건드렸다.
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
        assets.deleteAll();
        storage.listKeys(StorageKeys.ORIGINALS_PREFIX).forEach(storage::delete);
    }

    @Test
    @DisplayName("새 파일을 올리면 INGESTED 상태의 자산 행이 하나 생긴다")
    void 새_파일_업로드() throws Exception {
        byte[] content = randomContent(4096);

        UploadOriginalResponse response = upload(content, "photo.jpg", "image/jpeg");

        assertThat(response.reused()).isFalse();
        assertThat(response.status()).isEqualTo(MediaAssetStatus.INGESTED);

        var stored = assets.findById(response.assetId()).orElseThrow();
        assertThat(stored.byteSize()).isEqualTo(content.length);
        assertThat(stored.mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("같은 사용자가 같은 내용을 다시 올리면 기존 자산을 재사용하고 새 행을 만들지 않는다")
    void 중복_업로드는_재사용() throws Exception {
        byte[] content = randomContent(2048);

        UploadOriginalResponse first = upload(content, "a.jpg", "image/jpeg");
        UploadOriginalResponse second = upload(content, "b.jpg", "image/jpeg");

        assertThat(first.reused()).isFalse();
        assertThat(second.reused()).isTrue();
        assertThat(second.assetId()).isEqualTo(first.assetId());
        assertThat(assets.count()).isEqualTo(1);
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

    private byte[] randomContent(int size) {
        byte[] content = new byte[size];
        new Random(SEED + size).nextBytes(content);
        return content;
    }
}
