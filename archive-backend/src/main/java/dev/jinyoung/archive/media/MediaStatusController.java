package dev.jinyoung.archive.media;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.jinyoung.archive.auth.CurrentUser;
import dev.jinyoung.archive.processing.job.JobQueue;

/**
 * 자산 처리 상태 조회. 처리가 비동기가 된 뒤(W2) 프론트가 썸네일 도착을 알 수단 — 지금은 폴링.
 * SSE(pipeline.md §7)가 붙어도 폴백 경로로 남음.
 *
 * 소유 확인은 썸네일 서빙과 같은 규칙 — 남의 자산·없는 자산 모두 404 (ADR A21).
 */
@RestController
public class MediaStatusController {

    private final MediaAssetRepository assets;
    private final JobQueue jobs;
    private final CurrentUser currentUser;

    public MediaStatusController(MediaAssetRepository assets, JobQueue jobs, CurrentUser currentUser) {
        this.assets = assets;
        this.jobs = jobs;
        this.currentUser = currentUser;
    }

    @GetMapping("/media/{id}")
    public MediaStatusResponse status(@PathVariable UUID id) {
        UUID ownerId = currentUser.requireUserId();
        MediaAsset asset = assets.findById(id)
                .filter(a -> a.ownerId().equals(ownerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new MediaStatusResponse(asset.id(), asset.status(), jobs.hasActiveJob(asset.id()));
    }

    /**
     * storage_key 등 내부 좌표 비노출. 썸네일은 {@code /media/{id}/thumbnail}.
     *
     * @param processing 살아 있는 처리 작업이 있는지. 상태만으론 "아직 처리 중"과 "THUMB 이 DEAD 라
     *                   PROBED 에서 멈춤"(libvips 부재 등)을 구분 못 함 — 프론트가 폴링을 멈출 기준
     */
    public record MediaStatusResponse(UUID assetId, MediaAssetStatus status, boolean processing) {
    }
}
