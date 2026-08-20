package dev.jinyoung.archive.processing.thumb;

import java.nio.file.Path;

/**
 * 썸네일 생성 seam. 구현은 libvips CLI ({@link VipsThumbnailer}).
 *
 * {@code ObjectStorage}↔{@code S3ObjectStorage} 와 같은 경계 — 네이티브 도구를 인터페이스
 * 뒤에 둠. JNI 바인딩 대신 CLI 를 택한 이유(ADR A19): 클래스패스에 네이티브 심볼이 붙지
 * 않아 libvips 없는 환경(이 개발 노트북·일부 CI)에서도 앱 컨텍스트는 그대로 뜨고, THUMB
 * 만 {@link #isAvailable()} 로 건너뜀.
 */
public interface Thumbnailer {

    /**
     * libvips 실행 가능 여부. 시작 확인·테스트 스킵 판정용 (골든셋 부재 시 스킵과 같은 결).
     * 없으면 자산을 FAILED 로 만들지 않음 — 배포 문제이지 미디어 문제가 아니므로 (P5).
     */
    boolean isAvailable();

    /**
     * {@code source} 를 최장변 축소 썸네일로 만들어 {@code output} 에 씀 (webp). EXIF 회전 자동 적용.
     * 결정론 요구 — 같은 입력·같은 libvips 버전 → 같은 바이트 (I3). 버전이 바뀌면 레시피 버전 상향.
     *
     * @throws ThumbnailException 생성 실패 (실행 불가·타임아웃·비정상 종료)
     */
    void thumbnail(Path source, Path output);
}
