package dev.jinyoung.archive.media;

import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.jinyoung.archive.auth.CurrentUser;

/**
 * 파생물 바이트를 클라이언트로 내려주는 유일한 자리. 프론트는 {@code thumbKey} 대신 이 경로로 받음
 * — storage_key(내부 스토리지 좌표)를 URL 에 비노출.
 *
 * media 스토리지 3층({@link ObjectStorage} 등)은 DB 미인지. 이 컨트롤러는 그 위 웹 서빙 층 —
 * 자산 소유 확인(DB) + 바이트 스트리밍(스토리지)이 만나는 유일한 곳이라 여기 둠.
 *
 * W1 은 THUMB_256 하나. PREVIEW·POSTER 등은 레시피 늘 때 확장.
 */
@RestController
public class MediaThumbnailController {

    private static final DerivativeRecipe THUMB = DerivativeRecipe.THUMB_256;

    private final MediaAssetRepository assets;
    private final MediaDerivativeRepository derivatives;
    private final ObjectStorage storage;
    private final CurrentUser currentUser;

    public MediaThumbnailController(
            MediaAssetRepository assets,
            MediaDerivativeRepository derivatives,
            ObjectStorage storage,
            CurrentUser currentUser) {
        this.assets = assets;
        this.derivatives = derivatives;
        this.storage = storage;
        this.currentUser = currentUser;
    }

    @GetMapping("/media/{id}/thumbnail")
    public ResponseEntity<InputStreamResource> thumbnail(@PathVariable UUID id) {
        UUID ownerId = currentUser.requireUserId();

        // 남의 자산·없는 자산 모두 404 — 소유자에게만 존재 노출(403 은 "있긴 하다"를 흘림).
        // 미인증은 여기 도달 전 401 (SecurityConfig).
        MediaAsset asset = assets.findById(id)
                .filter(a -> a.ownerId().equals(ownerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // THUMB 미도달(PROBED 정지·FAILED·libvips 부재)이면 파생물 행 없음. 프론트는 404 를
        // "아직 썸네일 없음"으로 읽고 플레이스홀더 유지.
        MediaDerivative thumb = derivatives
                .findByAssetIdAndRecipeAndRecipeVer(asset.id(), THUMB.recipeName(), THUMB.version())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        InputStreamResource body = new InputStreamResource(storage.open(thumb.storageKey()));
        return ResponseEntity.ok()
                // THUMB_256 산출물은 항상 webp (VipsThumbnailer).
                .contentType(MediaType.valueOf("image/webp"))
                .contentLength(thumb.byteSize())
                // storage_key 내용 해시 기반 → 바이트 불변, 길게 캐시 안전 (design.md P3).
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePrivate().immutable())
                .body(body);
    }
}
