package dev.jinyoung.archive.processing.job;

import java.time.Instant;
import java.util.UUID;

/**
 * processing_jobs 한 행 (checkpoint 제외 — 영상 재개 도입 시 추가). 읽기 전용 스냅샷.
 * 상태 변경은 전부 {@link JobQueue} 의 조건부 UPDATE 로만 — 소유권 확인이 SQL WHERE 에 있어야 해서.
 */
public record ProcessingJob(
        long id,
        UUID assetId,
        Stage stage,
        Lane lane,
        JobState state,
        int attempt,
        int maxAttempts,
        Instant runAfter,
        String lockedBy,
        Instant lockedAt,
        String lastError,
        Instant createdAt) {
}
