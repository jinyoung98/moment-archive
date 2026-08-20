package dev.jinyoung.archive.upload;

import java.util.UUID;

import dev.jinyoung.archive.media.MediaAssetStatus;

/**
 * @param reused   이 사용자에게 동일 내용 자산이 이미 있어 새 행을 만들지 않았는지 여부.
 *                 스토리지 계층의 {@code StoredOriginal.reused()}(물리 객체 중복)와는
 *                 다른 신호 — 여기는 DB 자산 행 기준.
 * @param thumbKey 썸네일 storage_key. THUMBED 도달 시에만 값, 아니면 null (libvips 부재 등).
 *                 프론트가 이 키로 썸네일을 받아 플레이스홀더를 교체.
 */
public record UploadOriginalResponse(UUID assetId, MediaAssetStatus status, boolean reused, String thumbKey) {
}
