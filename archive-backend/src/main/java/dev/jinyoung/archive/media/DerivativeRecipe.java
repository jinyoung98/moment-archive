package dev.jinyoung.archive.media;

/**
 * 파생물 레시피와 현재 버전. schema.md §5.4 media_derivatives.recipe / recipe_ver.
 *
 * 버전을 값에 묶은 이유: 파생물은 데이터가 아니라 (원본 × 레시피 버전)의 함수인 캐시
 * (design.md P3, ADR A6). 알고리즘·인코더 옵션 변경 시 버전 상향 → 옛·새 파생물이 다른
 * storage_key 에 공존, 무중단 백필·롤백 가능. 버전 고정 채 덮어쓰면 재생성 중 화면 깨짐.
 *
 * W1 은 THUMB_256 하나. PREVIEW_1600·POSTER·VIDEO_720P 는 DERIVE(W2) 소관.
 */
public enum DerivativeRecipe {

    /** libvips 최장변 256px 썸네일. 화면을 가장 먼저 채우는 산출물 (design.md §13). */
    THUMB_256(1);

    private final int version;

    DerivativeRecipe(int version) {
        this.version = version;
    }

    /** DB recipe 컬럼 값. enum 이름 그대로. */
    public String recipeName() {
        return name();
    }

    public int version() {
        return version;
    }
}
