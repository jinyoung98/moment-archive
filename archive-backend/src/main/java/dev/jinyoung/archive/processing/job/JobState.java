package dev.jinyoung.archive.processing.job;

/**
 * 작업 상태. 재시도 대기용 FAILED 상태 없음 — 재시도는 QUEUED + 미래 {@code run_after} 로 표현.
 * 획득 쿼리가 QUEUED 하나만 보면 되고, lease 회수도 같은 전이(→ QUEUED)를 씀.
 */
public enum JobState {
    QUEUED, RUNNING, DONE, DEAD
}
