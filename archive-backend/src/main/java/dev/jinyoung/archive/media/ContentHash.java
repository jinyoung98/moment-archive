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
 * 파일 내용의 SHA-256. 미디어의 진짜 정체성 (design.md P2, ADR A4).
 *
 * 값 타입 사용 이유: 원본 해시·파생물 해시·제안 다이제스트 등 같은 모양의 16진 64자
 * 문자열이 다수 혼재 — {@code String} 이면 인자 순서 실수도 컴파일 통과. 스토리지 키
 * 생성 입력이라 그 실수는 엉뚱한 자리 저장으로 뒤늦게 발견.
 *
 * 표기: 소문자 16진 64자 고정. DB 컬럼이 {@code char(64)} 이고 키에 그대로 사용 —
 * 대소문자 혼재 시 같은 내용도 다른 키.
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
     * S3 반환 base64 체크섬 파싱.
     *
     * 스토리지 보관 값이라 우리 규격 미준수 가능 — 다른 알고리즘, 다른 길이 등. 그런 값은
     * {@link Optional#empty()} 로 "확인 불가"·"불일치" 구분. 섞으면 무결성 감사가
     * 정상 객체를 손상으로 오신고.
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

    /** 파일을 스트리밍하며 해시 계산, 전체를 메모리에 안 올림 — 골든셋 수백MB 영상 때문 (roadmap.md §5). */
    public static ContentHash of(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return of(in);
        }
    }

    /** 스트림 끝까지 읽기. 닫기는 호출자 책임. */
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
     * S3 {@code x-amz-checksum-sha256} 헤더용 base64 표기.
     *
     * 두 표기를 쓰는 이유: DB·스토리지 키는 16진(가독성, SQL 비교), S3 헤더는 base64.
     */
    public String base64() {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256: JDK 필수 알고리즘. 여기 도달 시 JVM 이상.
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
