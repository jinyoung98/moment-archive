package dev.jinyoung.archive.media;

/**
 * 원본 하나가 스토리지에 놓인 결과.
 *
 * @param hash       내용 해시. {@code media_assets.content_hash} 가 되고 키의 근거가 된다
 * @param storageKey 놓인 자리. {@code media_assets.storage_key} 가 된다
 * @param byteSize   원본 크기
 * @param reused     <b>이미 같은 내용이 있어서 전송하지 않았다</b>는 뜻. 업로드 항목을
 *                   {@code SKIPPED_DUPLICATE} 로 표시하고 "절약된 전송 바이트"
 *                   (roadmap.md §7)를 세는 근거이므로, 결과에서 지워버리면 안 된다
 */
public record StoredOriginal(ContentHash hash, String storageKey, long byteSize, boolean reused) {
}
