package dev.jinyoung.archive.media;

/**
 * 존재해야 할 객체의 부재.
 *
 * 일반 스토리지 오류와 구분 이유: 의미 상이. 나머지 실패는 대개 일시적·재시도 대상,
 * 이건 DB 참조는 있으나 파일 부재 — design.md §7.4 가 순서로 방지하려 한 깨진 참조
 * 상태. 재시도로 해결 불가, 무결성 감사 보고 대상 사건.
 */
public class ObjectNotFoundException extends StorageException {

    private final String key;

    public ObjectNotFoundException(String key) {
        super("스토리지에 객체가 없다: " + key);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
