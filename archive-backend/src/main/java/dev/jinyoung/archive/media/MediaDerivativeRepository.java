package dev.jinyoung.archive.media;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface MediaDerivativeRepository extends CrudRepository<MediaDerivative, UUID> {

    Optional<MediaDerivative> findByAssetIdAndRecipeAndRecipeVer(UUID assetId, String recipe, int recipeVer);

    /**
     * 멱등 저장. lease 만료로 회수된 THUMB 가 실제로는 완료 직전이었을 수 있어 중복 실행 전제
     * (pipeline.md §6, I9). UNIQUE(asset_id, recipe, recipe_ver) 충돌은 에러가 아닌 정상 경로 —
     * 같은 자리에 덮어씀.
     *
     * Data JDBC 는 ON CONFLICT 를 파생하지 못해 직접 SQL. created_at 은 최초 삽입값 유지
     * (충돌 시 갱신 목록에서 제외) — 재생성해도 "언제 처음 만들었나"는 안 바뀜.
     */
    @Modifying
    @Query("""
            INSERT INTO media_derivatives
                (id, asset_id, recipe, recipe_ver, storage_key, byte_size, content_hash, created_at)
            VALUES
                (:id, :assetId, :recipe, :recipeVer, :storageKey, :byteSize, :contentHash, :createdAt)
            ON CONFLICT (asset_id, recipe, recipe_ver)
            DO UPDATE SET storage_key  = EXCLUDED.storage_key,
                          byte_size    = EXCLUDED.byte_size,
                          content_hash = EXCLUDED.content_hash
            """)
    void upsert(
            @Param("id") UUID id,
            @Param("assetId") UUID assetId,
            @Param("recipe") String recipe,
            @Param("recipeVer") int recipeVer,
            @Param("storageKey") String storageKey,
            @Param("byteSize") long byteSize,
            @Param("contentHash") String contentHash,
            @Param("createdAt") Instant createdAt);
}
