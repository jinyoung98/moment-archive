package dev.jinyoung.archive.processing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jinyoung.archive.media.MediaAsset;
import dev.jinyoung.archive.media.ObjectStorage;
import dev.jinyoung.archive.media.StorageException;

/**
 * 원본을 임시 파일로 내려받음. 단계마다 스토리지에서 다시 읽음 — 워커는 업로드 임시 파일을 모르고,
 * 저장된 바이트를 되읽어 I1 을 한 번 더 통과. 사진 한 장에 PROBE·THUMB 두 번 받는 비용은 수 MB라 감수.
 */
@Component
public class OriginalFetcher {

    private static final Logger log = LoggerFactory.getLogger(OriginalFetcher.class);

    private final ObjectStorage storage;

    public OriginalFetcher(ObjectStorage storage) {
        this.storage = storage;
    }

    /** 내려받기 실패는 스토리지 I/O — 일시적 분류. 미디어 손상 아님. */
    public Path download(MediaAsset asset) {
        try {
            Path temp = Files.createTempFile("process-original-", ".tmp");
            try (InputStream in = storage.open(asset.storageKey())) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            return temp;
        } catch (IOException e) {
            throw new StorageException("원본 내려받기 실패: " + asset.storageKey(), e);
        }
    }

    public static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | UncheckedIOException e) {
            log.warn("임시 파일 삭제 실패: {}", path, e);
        }
    }
}
