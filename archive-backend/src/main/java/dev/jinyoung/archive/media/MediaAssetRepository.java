package dev.jinyoung.archive.media;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface MediaAssetRepository extends CrudRepository<MediaAsset, UUID> {

    Optional<MediaAsset> findByOwnerIdAndContentHash(UUID ownerId, String contentHash);

    /** 행 잠금 조회. 같은 자산을 동시에 갱신하는 처리 커밋끼리 직렬화 (JobWorker.updateAsset). */
    @Query("SELECT * FROM media_assets WHERE id = :id FOR UPDATE")
    Optional<MediaAsset> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 업로드 직후 자산 행. UNIQUE(owner_id, content_hash) 충돌이면 아무것도 안 함 (pipeline.md §10.1).
     *
     * insert 후 DuplicateKeyException 을 잡는 방식 대신 ON CONFLICT — Postgres 는 오류 난 트랜잭션을
     * 통째로 중단시켜, 같은 트랜잭션의 작업 등록(I9 원자성)을 이어갈 수 없음.
     *
     * @return 1 = 새로 만듦, 0 = 이미 있음
     */
    @Modifying
    @Query("""
            INSERT INTO media_assets
                (id, owner_id, content_hash, byte_size, mime_type, kind, storage_key, status, created_at)
            VALUES
                (:id, :ownerId, :contentHash, :byteSize, :mimeType, :kind, :storageKey, 'INGESTED', :createdAt)
            ON CONFLICT (owner_id, content_hash) DO NOTHING
            """)
    int insertIngestedIfAbsent(
            @Param("id") UUID id,
            @Param("ownerId") UUID ownerId,
            @Param("contentHash") String contentHash,
            @Param("byteSize") long byteSize,
            @Param("mimeType") String mimeType,
            @Param("kind") String kind,
            @Param("storageKey") String storageKey,
            @Param("createdAt") Instant createdAt);
}
