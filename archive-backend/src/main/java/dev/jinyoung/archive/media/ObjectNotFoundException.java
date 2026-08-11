package dev.jinyoung.archive.media;

/**
 * 있어야 할 객체가 없다.
 *
 * <p>일반적인 스토리지 오류와 구분하는 이유는 의미가 전혀 다르기 때문이다. 나머지 실패는
 * 대개 일시적이고 재시도의 대상이지만, 이것은 <b>DB 는 가리키는데 파일이 없다</b>는 뜻이라
 * 깨진 참조 — design.md §7.4 가 순서를 정해 피하려 한 바로 그 상태다. 재시도해도 낫지 않고,
 * 무결성 감사가 보고해야 하는 사건이다.
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
