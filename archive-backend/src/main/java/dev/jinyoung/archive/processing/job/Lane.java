package dev.jinyoung.archive.processing.job;

import dev.jinyoung.archive.media.MediaKind;

/**
 * 워커 풀 구분. 영상 트랜스코딩(분 단위)이 사진(ms 단위)을 막지 않게 — 헤드 오브 라인 블로킹 회피
 * (schema.md §5.6). 값은 {@link MediaKind} 와 1:1 이지만 별도 타입: 자산 분류와 워커 배치는 다른 관심사.
 */
public enum Lane {
    PHOTO, VIDEO;

    public static Lane of(MediaKind kind) {
        return switch (kind) {
            case PHOTO -> PHOTO;
            case VIDEO -> VIDEO;
        };
    }
}
