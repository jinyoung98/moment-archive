package dev.jinyoung.archive.media;

/**
 * 스토리지 키를 만드는 유일한 자리.
 *
 * 버킷 하나(archive), 접두사로 구분 (docs/infra.md §2.5). 키는 내용 해시만의 함수 —
 * 파일명·업로더·시각 배제, 같은 내용은 항상 같은 자리, 중복 업로드는 덮어쓰기로 멱등 (I2).
 *
 * <pre>
 *   originals/ab/cd/abcdef0123...   ← 해시 앞 4자를 2/2 로 쪼개 디렉토리 분산
 * </pre>
 *
 * 분산 목적: S3 성능(이 규모에선 무관)이 아니라 사람·도구용 — {@code mc ls}, 파일시스템
 * 백엔드에서 한 디렉토리에 수만 개 적재 시 육안·부분 동기화 모두 불가.
 *
 * 확장자 미부여. 이름 기반 추측은 실제 포맷과 불일치 가능 (아이폰 {@code .jpg} 가 실제
 * HEIC인 경우), MIME 타입은 {@code media_assets} 소관이라 키 중복 불필요.
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
