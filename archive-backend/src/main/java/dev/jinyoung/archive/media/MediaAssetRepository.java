package dev.jinyoung.archive.media;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

public interface MediaAssetRepository extends CrudRepository<MediaAsset, UUID> {

    Optional<MediaAsset> findByOwnerIdAndContentHash(UUID ownerId, String contentHash);
}
