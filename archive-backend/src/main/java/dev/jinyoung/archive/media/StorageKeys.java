package dev.jinyoung.archive.media;

/**
 * 스토리지 키를 만드는 유일한 자리.
 *
 * <p>버킷은 하나(`archive`)이고 접두사로 나눈다 (docs/infra.md §2.5).
 * 키는 <b>내용 해시만의 함수</b>다 — 파일명도, 업로더도, 시각도 섞지 않는다. 그래야
 * 같은 내용이 항상 같은 자리에 놓이고, 중복 업로드가 덮어쓰기가 되어 멱등해진다 (I2).
 *
 * <pre>
 *   originals/ab/cd/abcdef0123...   ← 해시 앞 4자를 2/2 로 쪼개 디렉토리 분산
 * </pre>
 *
 * <p>앞자리를 쪼개는 것은 S3 자체를 위한 것이 아니라(접두사 성능 문제는 이 규모에서 없다)
 * 사람과 도구를 위한 것이다. {@code mc ls} 나 파일시스템 백엔드에서 한 디렉토리에 수만 개가
 * 평평하게 쌓이면 눈으로 볼 수도, 부분 동기화할 수도 없다.
 *
 * <p>파일 확장자를 붙이지 않는다. 이름에서 내용을 추측하게 만들면 실제 포맷과 어긋날 수 있고
 * (아이폰이 준 {@code .jpg} 가 실제로 HEIC 인 경우), MIME 타입은 {@code media_assets} 에
 * 있으니 키에 중복시킬 이유가 없다.
 */
public final class StorageKeys {

    public static final String ORIGINALS_PREFIX = "originals/";

    private StorageKeys() {
    }

    public static String original(ContentHash hash) {
        String hex = hash.hex();
        return ORIGINALS_PREFIX + hex.substring(0, 2) + "/" + hex.substring(2, 4) + "/" + hex;
    }
}
