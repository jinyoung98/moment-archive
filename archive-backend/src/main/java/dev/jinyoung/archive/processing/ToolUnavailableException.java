package dev.jinyoung.archive.processing;

/**
 * 외부 도구(libvips 등) 부재. 배포 문제이지 미디어 문제 아님 — 자산은 FAILED 로 안 만들고 작업만
 * DEAD (P5). 재시도해도 같은 결과라 백오프 대상 아님. DEAD 작업으로 남아 사람 눈에 띄는 게 목적 —
 * 도구 설치 후 재등록.
 */
public class ToolUnavailableException extends RuntimeException {

    public ToolUnavailableException(String message) {
        super(message);
    }
}
