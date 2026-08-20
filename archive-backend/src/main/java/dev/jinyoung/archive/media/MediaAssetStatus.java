package dev.jinyoung.archive.media;

/** 자산이 도달한 처리 단계 요약. schema.md §5.3 media_assets.status. */
public enum MediaAssetStatus {
    INGESTED, PROBED, THUMBED, READY, FAILED
}
