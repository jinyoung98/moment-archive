package dev.jinyoung.archive.processing.probe;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * PROBE 산출 메타데이터. media_assets 의 EXIF 유래 컬럼에 그대로 매핑.
 *
 * 전부 nullable — EXIF 없는 스크린샷·메신저 수신 이미지가 흔함 (design.md §8.1 골든셋).
 * 없으면 null, 억지로 채우지 않음. 장면 분할(W3)이 captured_at null 을 감안 (pipeline.md §8.3).
 *
 * @param capturedAtSrc 'EXIF' | 'FILE_MTIME' | 'MANUAL'. capturedAt 있을 때만 의미
 * @param durationMs    영상 길이. 사진이면 null (영상 PROBE 는 W2 ffprobe)
 */
public record ProbeResult(
        Instant capturedAt,
        String capturedAtSrc,
        Integer tzOffsetMin,
        BigDecimal gpsLat,
        BigDecimal gpsLon,
        Integer width,
        Integer height,
        Integer orientation,
        Integer durationMs) {

    public static final String SRC_EXIF = "EXIF";
}
