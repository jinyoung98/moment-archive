package dev.jinyoung.archive.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 파일 내용의 SHA-256. 미디어의 진짜 정체성이다 (design.md 원칙 P2, ADR A4).
 *
 * <p>{@code String} 대신 값 타입으로 두는 이유는 이 프로젝트에 64자 16진 문자열이 여러 종류
 * 돌아다니기 때문이다 — 원본 해시, 파생물 해시, 제안 입력 다이제스트. 전부 {@code String} 이면
 * 인자 순서를 바꿔 넣어도 컴파일이 통과한다. 스토리지 키를 만드는 입력이라 그 실수의 결과는
 * "엉뚱한 자리에 저장" 이고, 나중에 발견된다.
 *
 * <p>표기는 <b>소문자 16진 64자</b>로 고정한다. DB 컬럼이 {@code char(64)} 이고
 * 스토리지 키에 그대로 들어가므로, 대소문자가 섞이면 같은 내용이 다른 키를 얻는다.
 */
public record ContentHash(String hex) {

    private static final int STREAM_BUFFER_BYTES = 64 * 1024;

    public ContentHash {
        if (hex == null || hex.length() != 64) {
            throw new IllegalArgumentException("SHA-256 은 16진 64자여야 한다: " + hex);
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean lowerHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!lowerHex) {
                throw new IllegalArgumentException("소문자 16진만 허용한다: " + hex);
            }
        }
    }

    public static ContentHash of(byte[] content) {
        return new ContentHash(HexFormat.of().formatHex(newDigest().digest(content)));
    }

    /**
     * S3 가 돌려준 base64 체크섬을 되읽는다.
     *
     * <p>스토리지가 보관 중인 값이라 우리 규격을 지킬 이유가 없다 — SHA-256 이 아닌 알고리즘의
     * 체크섬이거나 길이가 다를 수 있다. 그런 값은 {@link Optional#empty()} 로 돌려
     * "확인할 수 없음" 과 "불일치" 를 구분한다. 둘을 섞으면 무결성 감사가 멀쩡한 객체를
     * 손상으로 신고한다.
     */
    public static Optional<ContentHash> fromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            if (raw.length != 32) {
                return Optional.empty();
            }
            return Optional.of(new ContentHash(HexFormat.of().formatHex(raw)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 파일을 스트리밍하며 해시를 계산한다. 전체를 메모리에 올리지 않는다 —
     * 골든셋에 수백 MB 영상이 들어 있고 (roadmap.md §5), 그런 파일을 바이트 배열로 읽으면
     * 워커가 죽는다.
     */
    public static ContentHash of(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return of(in);
        }
    }

    /** 스트림을 끝까지 읽는다. 닫는 것은 호출자의 책임이다. */
    public static ContentHash of(InputStream in) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[STREAM_BUFFER_BYTES];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return new ContentHash(HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * S3 의 {@code x-amz-checksum-sha256} 헤더가 요구하는 base64 표기.
     *
     * <p>같은 값을 두 가지로 표기하는 이유는 각자 남의 규격이기 때문이다. DB 와 스토리지 키는
     * 16진(사람이 읽고 SQL 로 비교한다), S3 체크섬 헤더는 base64 다.
     */
    public String base64() {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 필수 알고리즘이다. 여기 오면 JVM 이 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
