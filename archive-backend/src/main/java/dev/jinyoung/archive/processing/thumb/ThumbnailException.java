package dev.jinyoung.archive.processing.thumb;

/**
 * 썸네일 생성 실패. 원인은 실행 불가·타임아웃·libvips 비정상 종료 등.
 *
 * 원본은 무사 — 실패한 건 파생물 생성뿐. 호출부는 자산을 FAILED 로 만들지 않고 PROBED 에
 * 남긴 뒤 나중 재시도 (P5, "덜 위험한 실패").
 */
public class ThumbnailException extends RuntimeException {

    public ThumbnailException(String message) {
        super(message);
    }

    public ThumbnailException(String message, Throwable cause) {
        super(message, cause);
    }
}
