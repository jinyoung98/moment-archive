package dev.jinyoung.archive.processing;

import dev.jinyoung.archive.media.MediaAsset;
import dev.jinyoung.archive.processing.job.Stage;

/**
 * 단계 하나의 실제 작업. 멱등 필수 (I9) — lease 회수로 두 번 실행되는 게 정상 경로.
 *
 * 실패 분류는 예외 타입으로 (pipeline.md §5.5): {@code UnreadableMediaException} = 영구(자산 FAILED),
 * {@link ToolUnavailableException} = 영구(자산 유지), 그 외 = 일시적(백오프 재시도).
 */
public interface StageProcessor {

    Stage stage();

    StageOutcome run(MediaAsset asset);
}
