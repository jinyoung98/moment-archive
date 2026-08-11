package dev.jinyoung.archive.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 컨테이너가 필요 없는 부분. 키 규칙과 해시는 순수 함수라 여기서 다 검증된다. */
class ContentHashTest {

    /**
     * 알려진 값으로 못 박는다. 구현을 바꿔도(버퍼 크기, 스트리밍 방식) 결과가 같아야 한다는
     * 것이 요점이다 — 해시가 바뀌면 이미 저장된 모든 객체의 주소가 무효가 된다.
     */
    @Test
    @DisplayName("빈 입력과 알려진 문자열의 SHA-256 이 규격값과 일치한다")
    void 알려진_값의_해시가_일치한다() {
        assertThat(ContentHash.of(new byte[0]).hex())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        assertThat(ContentHash.of("abc".getBytes(StandardCharsets.UTF_8)).hex())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("스트리밍 계산과 한 번에 계산한 결과가 같다")
    void 스트리밍과_일괄_계산이_일치한다(@TempDir Path tempDir) throws IOException {
        // 버퍼 경계에서 어긋나는 구현을 잡으려면 버퍼(64KB)보다 큰 입력이어야 한다.
        byte[] content = new byte[64 * 1024 * 3 + 7];
        new Random(20260811L).nextBytes(content);

        Path file = Files.write(tempDir.resolve("large.bin"), content);

        ContentHash expected = ContentHash.of(content);
        assertThat(ContentHash.of(file)).isEqualTo(expected);
        assertThat(ContentHash.of(new ByteArrayInputStream(content))).isEqualTo(expected);
    }

    @Test
    @DisplayName("base64 로 나갔다 돌아와도 같은 값이다")
    void base64_왕복이_보존된다() {
        ContentHash hash = ContentHash.of("round trip".getBytes(StandardCharsets.UTF_8));

        assertThat(ContentHash.fromBase64(hash.base64())).contains(hash);
    }

    @Test
    @DisplayName("SHA-256 이 아닌 체크섬은 '확인 불가' 로 돌려준다")
    void 알_수_없는_체크섬은_비어_있다() {
        // MinIO·S3 는 CRC32 처럼 다른 알고리즘의 체크섬을 보관할 수도 있다. 그것을 예외로
        // 다루면 무결성 감사가 멀쩡한 객체 앞에서 멈춘다.
        assertThat(ContentHash.fromBase64("q1V4Ig==")).isEmpty();
        assertThat(ContentHash.fromBase64("이건 base64 가 아니다")).isEmpty();
        assertThat(ContentHash.fromBase64(null)).isEmpty();
        assertThat(ContentHash.fromBase64("")).isEmpty();
    }

    @Test
    @DisplayName("규격을 어긋난 표기는 만들어지지 않는다")
    void 잘못된_표기를_거부한다() {
        String valid = "a".repeat(64);

        assertThatThrownBy(() -> new ContentHash(valid.substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContentHash(null))
                .isInstanceOf(IllegalArgumentException.class);
        // 대문자를 허용하면 같은 내용이 두 개의 키를 얻는다.
        assertThatThrownBy(() -> new ContentHash("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContentHash("z".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
