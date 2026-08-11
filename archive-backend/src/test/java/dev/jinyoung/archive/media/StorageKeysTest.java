package dev.jinyoung.archive.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StorageKeysTest {

    @Test
    @DisplayName("원본 키는 해시 앞 4자를 2/2 로 쪼갠 경로 아래에 놓인다")
    void 원본_키_형태() {
        ContentHash hash = ContentHash.of("abc".getBytes(StandardCharsets.UTF_8));

        assertThat(StorageKeys.original(hash)).isEqualTo(
                "originals/ba/78/ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    /**
     * 이 단정이 중복 제거와 stage 멱등성의 토대다. 키에 시각이나 UUID 가 섞이면 같은 파일이
     * 매번 새 자리에 저장되고, 재실행마다 고아 객체가 하나씩 남는다.
     */
    @Test
    @DisplayName("같은 해시는 항상 같은 키를, 다른 해시는 다른 키를 얻는다")
    void 키는_해시만의_함수다() {
        ContentHash hash = ContentHash.of("same".getBytes(StandardCharsets.UTF_8));
        ContentHash other = ContentHash.of("different".getBytes(StandardCharsets.UTF_8));

        assertThat(StorageKeys.original(hash)).isEqualTo(StorageKeys.original(hash));
        assertThat(StorageKeys.original(hash)).isNotEqualTo(StorageKeys.original(other));
    }

    @Test
    @DisplayName("모든 원본 키는 originals/ 아래에 있다")
    void 원본_접두사를_지킨다() {
        ContentHash hash = ContentHash.of("prefix".getBytes(StandardCharsets.UTF_8));

        // 고아 파일 회수와 무결성 감사가 이 접두사로 스캔 범위를 정한다 (design.md §7.4).
        assertThat(StorageKeys.original(hash)).startsWith(StorageKeys.ORIGINALS_PREFIX);
    }
}
