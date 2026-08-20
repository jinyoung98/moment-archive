package dev.jinyoung.archive.media;

/** 스토리지 호출 실패. 원인은 {@code cause} 참조. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
