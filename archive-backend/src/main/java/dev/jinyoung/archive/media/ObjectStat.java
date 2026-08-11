package dev.jinyoung.archive.media;

import java.util.Optional;

/**
 * 객체를 내려받지 않고 알 수 있는 것.
 *
 * @param byteSize 저장된 크기
 * @param checksum 스토리지가 보관 중인 SHA-256. <b>비어 있을 수 있다</b> — 우리가 체크섬을
 *                 붙이기 전에 올라간 객체이거나, 구현이 이 헤더를 돌려주지 않는 경우다.
 *                 값이 있으면 바이트를 한 번도 읽지 않고 무결성을 확인할 수 있어서
 *                 (W4 무결성 감사) 원본 수천 개를 훑는 배치의 비용이 달라진다.
 */
public record ObjectStat(long byteSize, Optional<ContentHash> checksum) {
}
