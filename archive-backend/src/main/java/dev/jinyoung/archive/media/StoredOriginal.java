package dev.jinyoung.archive.media;

/**
 * 원본 하나가 스토리지에 놓인 결과.
 *
 * @param hash       내용 해시. {@code media_assets.content_hash} 값이자 키 산출 근거
 * @param storageKey 저장 위치. {@code media_assets.storage_key} 값
 * @param byteSize   원본 크기
 * @param reused     동일 내용 기존재로 전송 생략 여부. 업로드 항목 {@code SKIPPED_DUPLICATE}
 *                   표시, "절약 전송 바이트"(roadmap.md §7) 집계 근거 — 결과에서 제거 금지
 */
public record StoredOriginal(ContentHash hash, String storageKey, long byteSize, boolean reused) {
}
