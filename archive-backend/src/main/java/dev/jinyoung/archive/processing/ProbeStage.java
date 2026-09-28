package dev.jinyoung.archive.processing;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

import dev.jinyoung.archive.media.MediaAsset;
import dev.jinyoung.archive.processing.job.Stage;
import dev.jinyoung.archive.processing.probe.MediaProbe;

/**
 * PROBE — EXIF 추출. 순수 함수라 멱등 (같은 바이트 → 같은 메타). 손상 파일은
 * {@code UnreadableMediaException} 이 그대로 올라가 JobWorker 가 영구 실패로 처리.
 */
@Component
public class ProbeStage implements StageProcessor {

    private final OriginalFetcher fetcher;
    private final MediaProbe probe;

    public ProbeStage(OriginalFetcher fetcher, MediaProbe probe) {
        this.fetcher = fetcher;
        this.probe = probe;
    }

    @Override
    public Stage stage() {
        return Stage.PROBE;
    }

    @Override
    public StageOutcome run(MediaAsset asset) {
        Path original = fetcher.download(asset);
        try {
            return new StageOutcome.Probed(probe.probe(original));
        } finally {
            OriginalFetcher.deleteQuietly(original);
        }
    }
}
