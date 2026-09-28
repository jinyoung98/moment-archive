package dev.jinyoung.archive.media;

/** 자산이 도달한 처리 단계 요약. schema.md §5.3 media_assets.status. */
public enum MediaAssetStatus {
    INGESTED, PROBED, THUMBED, READY, FAILED;

    /**
     * 전진만 하는 전이. 이미 더 나아간 단계면 그대로. FAILED 는 종착.
     *
     * 중복 실행 전제(I9) 때문에 필요 — THUMBED 뒤에 회수된 PROBE 가 늦게 끝나도 PROBED 로
     * 되돌아가지 않음.
     */
    public MediaAssetStatus advanceTo(MediaAssetStatus target) {
        if (this == FAILED || target == FAILED) {
            return FAILED;
        }
        return target.ordinal() > ordinal() ? target : this;
    }
}
