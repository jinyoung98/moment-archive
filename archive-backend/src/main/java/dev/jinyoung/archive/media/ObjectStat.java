package dev.jinyoung.archive.media;

import java.util.Optional;

/**
 * 객체를 내려받지 않고 알 수 있는 것.
 *
 * @param byteSize 저장된 크기
 * @param checksum 스토리지 보관 SHA-256. 값 부재 가능 — 체크섬 도입 이전 업로드 객체이거나
 *                 구현 미반환 헤더. 값 존재 시 바이트 미열람 무결성 확인 가능
 *                 (W4 무결성 감사), 배치 비용 절감.
 */
public record ObjectStat(long byteSize, Optional<ContentHash> checksum) {
}
