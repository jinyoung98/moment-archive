package dev.jinyoung.archive.processing;

import dev.jinyoung.archive.processing.probe.ProbeResult;

/**
 * 단계 처리 결과. 무거운 일(다운로드·libvips)은 트랜잭션 밖에서 끝내고, DB 반영은 이 값을 받은
 * {@link JobWorker} 가 소유권 확인과 한 트랜잭션에서 (pipeline.md §5.7). 처리기는 DB 에 직접 안 씀.
 */
public sealed interface StageOutcome {

    record Probed(ProbeResult result) implements StageOutcome {
    }

    /** 파생물은 이미 스토리지에 씀. 남은 건 행 UPSERT + 상태 전이. */
    record Thumbed(String recipe, int recipeVer, String storageKey, long byteSize, String contentHash)
            implements StageOutcome {
    }
}
