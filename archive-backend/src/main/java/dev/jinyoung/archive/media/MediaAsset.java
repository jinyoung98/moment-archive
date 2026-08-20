package dev.jinyoung.archive.media;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 업로드된 사진/영상 원본 1건. schema.md §5.3.
 *
 * captured_at 이하 PROBE 산출 필드와 phash 는 W1 시점엔 전부 비어 있다 — 그 단계가 아직
 * 없기 때문이다. 그래도 필드를 다 갖춘 이유는 테이블 전체 열을 매핑해야 조회가 온전히
 * 되기 때문이고, 채우는 일은 각 단계(PROBE 등)의 몫으로 남긴다.
 */
@Table("media_assets")
public record MediaAsset(
        @Id UUID id,
        @Column("owner_id") UUID ownerId,
        @Column("content_hash") String contentHash,
        @Column("byte_size") long byteSize,
        @Column("mime_type") String mimeType,
        MediaKind kind,
        @Column("storage_key") String storageKey,
        @Column("captured_at") Instant capturedAt,
        @Column("captured_at_src") String capturedAtSrc,
        @Column("tz_offset_min") Integer tzOffsetMin,
        @Column("gps_lat") BigDecimal gpsLat,
        @Column("gps_lon") BigDecimal gpsLon,
        Integer width,
        Integer height,
        Integer orientation,
        @Column("duration_ms") Integer durationMs,
        Long phash,
        MediaAssetStatus status,
        @Column("orphaned_at") Instant orphanedAt,
        @Column("purge_after") Instant purgeAfter,
        @Column("created_at") Instant createdAt) {

    /** 업로드 직후 상태. 아직 아무 처리 단계도 거치지 않았다. */
    public static MediaAsset newlyIngested(
            UUID id, UUID ownerId, StoredOriginal stored, String mimeType, MediaKind kind, Instant createdAt) {
        return new MediaAsset(
                id, ownerId, stored.hash().hex(), stored.byteSize(), mimeType, kind, stored.storageKey(),
                null, null, null, null, null, null, null, null, null, null,
                MediaAssetStatus.INGESTED, null, null, createdAt);
    }
}
