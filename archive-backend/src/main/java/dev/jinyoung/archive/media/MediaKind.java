package dev.jinyoung.archive.media;

/** 처리 레인을 가르는 값 (pipeline.md §5.7 lane). schema.md §5.3 media_assets.kind. */
public enum MediaKind {
    PHOTO, VIDEO;

    public static MediaKind fromMimeType(String mimeType) {
        if (mimeType != null && mimeType.startsWith("image/")) {
            return PHOTO;
        }
        if (mimeType != null && mimeType.startsWith("video/")) {
            return VIDEO;
        }
        throw new IllegalArgumentException("지원하지 않는 파일 형식: " + mimeType);
    }
}
