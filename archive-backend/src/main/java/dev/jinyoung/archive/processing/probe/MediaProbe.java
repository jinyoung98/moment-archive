package dev.jinyoung.archive.processing.probe;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Date;
import java.util.TimeZone;

import org.springframework.stereotype.Component;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.jpeg.JpegDirectory;

/**
 * 사진 EXIF 추출. metadata-extractor 사용 — 순수 Java, 네이티브 의존 없음 (ADR A18).
 *
 * 순수 함수. 같은 바이트 → 같은 결과 → 멱등 (I9). 중복 실행 시 같은 메타를 덮어쓸 뿐이라
 * 호출부가 안심하고 재실행 가능 (pipeline.md §6).
 *
 * W1 은 사진만. 영상 PROBE(ffprobe·duration·poster)는 W2 (roadmap.md §2).
 */
@Component
public class MediaProbe {

    /** 시각 없는 EXIF 를 UTC 로 해석. offset 태그가 있으면 그쪽 우선. */
    private static final TimeZone FALLBACK_TZ = TimeZone.getTimeZone("UTC");

    public ProbeResult probe(Path file) {
        Metadata metadata = read(file);

        Dimensions dims = dimensions(metadata);
        Integer orientation = orientation(metadata);
        CapturedAt captured = capturedAt(metadata);
        Gps gps = gps(metadata);

        return new ProbeResult(
                captured.instant(),
                captured.instant() != null ? ProbeResult.SRC_EXIF : null,
                captured.tzOffsetMin(),
                gps.lat(),
                gps.lon(),
                dims.width(),
                dims.height(),
                orientation,
                null);
    }

    private Metadata read(Path file) {
        try {
            return ImageMetadataReader.readMetadata(file.toFile());
        } catch (ImageProcessingException | java.io.IOException e) {
            // 미디어로 해석 불가 = 영구 실패. 원본은 보존, 처리 단계만 FAILED (pipeline.md §5.5).
            throw new UnreadableMediaException("이미지 메타를 읽을 수 없다: " + file, e);
        }
    }

    private Dimensions dimensions(Metadata metadata) {
        // JPEG 은 SOF 마커(JpegDirectory)에 실제 픽셀 크기 — EXIF 없어도 확보됨.
        JpegDirectory jpeg = metadata.getFirstDirectoryOfType(JpegDirectory.class);
        if (jpeg != null) {
            return new Dimensions(
                    intOrNull(jpeg, JpegDirectory.TAG_IMAGE_WIDTH),
                    intOrNull(jpeg, JpegDirectory.TAG_IMAGE_HEIGHT));
        }
        // 비 JPEG 은 EXIF 이미지 크기 태그로 폴백. 그것도 없으면 null (THUMB 가 실제 크기 확정).
        ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (exif != null) {
            return new Dimensions(
                    intOrNull(exif, ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH),
                    intOrNull(exif, ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT));
        }
        return new Dimensions(null, null);
    }

    private Integer orientation(Metadata metadata) {
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        return ifd0 == null ? null : intOrNull(ifd0, ExifIFD0Directory.TAG_ORIENTATION);
    }

    private CapturedAt capturedAt(Metadata metadata) {
        ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (exif == null || !exif.containsTag(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)) {
            return new CapturedAt(null, null);
        }
        // offset 태그가 있으면 metadata-extractor 가 그걸로 해석, 없으면 FALLBACK_TZ.
        Date date = exif.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, null, FALLBACK_TZ);
        if (date == null) {
            return new CapturedAt(null, null);
        }
        Integer tz = tzOffsetMin(exif);
        return new CapturedAt(date.toInstant(), tz);
    }

    private Integer tzOffsetMin(ExifSubIFDDirectory exif) {
        // "+09:00" 형태. 파싱 실패 시 null — 있으면 좋고 없어도 그만 (W1).
        String raw = exif.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL);
        if (raw == null || raw.length() < 6) {
            return null;
        }
        try {
            int sign = raw.charAt(0) == '-' ? -1 : 1;
            int hours = Integer.parseInt(raw.substring(1, 3));
            int minutes = Integer.parseInt(raw.substring(4, 6));
            return sign * (hours * 60 + minutes);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Gps gps(Metadata metadata) {
        GpsDirectory dir = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        if (dir == null) {
            return new Gps(null, null);
        }
        GeoLocation loc = dir.getGeoLocation();
        if (loc == null || loc.isZero()) {
            return new Gps(null, null);
        }
        // schema 는 numeric(9,6) — 소수 6자리로 맞춤.
        return new Gps(
                BigDecimal.valueOf(loc.getLatitude()).setScale(6, java.math.RoundingMode.HALF_UP),
                BigDecimal.valueOf(loc.getLongitude()).setScale(6, java.math.RoundingMode.HALF_UP));
    }

    private Integer intOrNull(Directory dir, int tag) {
        return dir.containsTag(tag) ? dir.getInteger(tag) : null;
    }

    private record Dimensions(Integer width, Integer height) {
    }

    private record CapturedAt(java.time.Instant instant, Integer tzOffsetMin) {
    }

    private record Gps(BigDecimal lat, BigDecimal lon) {
    }
}
