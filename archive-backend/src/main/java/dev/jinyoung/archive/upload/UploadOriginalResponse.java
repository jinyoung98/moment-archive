package dev.jinyoung.archive.upload;

import java.util.UUID;

import dev.jinyoung.archive.media.MediaAssetStatus;

/**
 * @param reused   이 사용자에게 동일 내용 자산이 이미 있어 새 행을 만들지 않았는지 여부.
 *                 스토리지 계층의 {@code StoredOriginal.reused()}(물리 객체 중복)와는
 *                 다른 신호 — 여기는 DB 자산 행 기준.
 * @param thumbKey 썸네일 storage_key. 이미 THUMBED 인 중복일 때만 값. 새 자산은 처리 전이라 null —
 *                 프론트는 GET /media/{id} 로 상태를 폴링해 THUMBED 가 되면 썸네일 요청.
 */
public record UploadOriginalResponse(UUID assetId, MediaAssetStatus status, boolean reused, String thumbKey) {
}
