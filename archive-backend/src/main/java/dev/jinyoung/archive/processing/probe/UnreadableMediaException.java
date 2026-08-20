package dev.jinyoung.archive.processing.probe;

/**
 * 파일을 미디어로 해석할 수 없음 — 손상 JPEG, 미지원 포맷 등.
 *
 * 영구 실패 분류 (pipeline.md §5.5). 재시도 무의미 → 호출부는 자산을 FAILED 로. 원본 바이트는
 * 보존 (I1) — 실패한 것은 처리 단계뿐.
 */
public class UnreadableMediaException extends RuntimeException {

    public UnreadableMediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
