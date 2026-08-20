package dev.jinyoung.archive.processing.thumb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * libvips 호출 설정.
 *
 * @param command        실행 파일. 기본 {@code vips} (하위 명령 {@code thumbnail} 사용)
 * @param size           최장변 목표 픽셀. THUMB_256 과 맞춰 256
 * @param quality        webp 품질 (1~100)
 * @param timeoutSeconds 프로세스 상한. 초과 시 강제 종료 후 실패 처리 — 손상 입력이 워커를 물지 않게
 */
@ConfigurationProperties("archive.processing.thumbnail")
public record ThumbnailProperties(
        String command,
        Integer size,
        Integer quality,
        Integer timeoutSeconds) {

    public ThumbnailProperties {
        if (command == null || command.isBlank()) {
            command = "vips";
        }
        if (size == null) {
            size = 256;
        }
        if (quality == null) {
            quality = 80;
        }
        if (timeoutSeconds == null) {
            timeoutSeconds = 30;
        }
    }
}
