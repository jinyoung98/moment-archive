package dev.jinyoung.archive.media;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 원본에서 파생된 산출물 1건 (썸네일 등). schema.md §5.4.
 *
 * 쓰기는 {@link MediaDerivativeRepository#upsert} 가 담당 — 재실행 시 UNIQUE 충돌을
 * ON CONFLICT 로 흡수 (pipeline.md §6, I9). 이 record 는 주로 조회용.
 *
 * content_hash 는 파생물 자신의 SHA-256 — I3("같은 원본+레시피 → 같은 바이트") 검증 근거.
 * 원본 해시가 아님에 주의.
 */
@Table("media_derivatives")
public record MediaDerivative(
        @Id UUID id,
        @Column("asset_id") UUID assetId,
        String recipe,
        @Column("recipe_ver") int recipeVer,
        @Column("storage_key") String storageKey,
        @Column("byte_size") long byteSize,
        @Column("content_hash") String contentHash,
        @Column("created_at") Instant createdAt) {
}
