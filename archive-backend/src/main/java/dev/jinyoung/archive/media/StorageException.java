package dev.jinyoung.archive.media;

/** 스토리지 호출이 실패했다. 사유는 {@code cause} 에 있다. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
