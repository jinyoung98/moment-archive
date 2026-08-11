package dev.jinyoung.archive.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import dev.jinyoung.archive.support.IntegrationTest;

/**
 * 스토리지 계층을 실제 MinIO 위에서 검증한다. {@code I1}·{@code I2} 가 처음으로 본래 형태를
 * 갖는 자리다 — 지금까지 {@code I2} 는 DB 제약이 살아 있는지만 확인했다.
 *
 * <p>DB 와 달리 <b>스토리지에는 트랜잭션 롤백이 없다.</b> 컨테이너를 여러 테스트 클래스가
 * 공유하므로 각 테스트 앞에서 {@code originals/} 를 비운다. 이것이 없으면 앞선 테스트가
 * 남긴 객체 때문에 "객체 몇 개인가" 를 세는 단정이 실행 순서에 따라 달라진다
 * (roadmap.md §5 결정론 체크리스트).
 *
 * <p>{@code I2} 를 jqwik 속성 테스트가 아니라 <b>시드를 고정한 무작위 테스트</b>로 쓴 이유가
 * 있다. jqwik 의 {@code @Property} 는 자체 실행 생명주기를 쓰기 때문에 Spring 의 JUnit 확장이
 * 붙지 않아 컨테이너와 빈 주입을 함께 쓸 수 없다. 시드를 상수로 박아 두면 재현성은 같으므로,
 * 진짜 속성 테스트는 Spring 이 필요 없는 순수 도메인(순서키 {@code I5}) 에서 W4 에 도입한다.
 */
class ContentAddressedStoreIntegrationTest extends IntegrationTest {

    /** 실패하면 이 값을 바꾸지 말고 그대로 재현할 것. 바꾸는 순간 반례를 잃는다. */
    private static final long SEED = 20260811L;

    @Autowired
    private ContentAddressedStore store;

    @Autowired
    private ObjectStorage storage;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void 스토리지를_비운다() {
        storage.listKeys(StorageKeys.ORIGINALS_PREFIX).forEach(storage::delete);
    }

    @Test
    @DisplayName("I1: 저장한 원본은 바이트 하나까지 그대로 돌아온다")
    void I1_원본_바이트가_보존된다() throws IOException {
        byte[] content = randomContent(3 * 1024 * 1024 + 17);
        Path file = write("photo.jpg", content);

        StoredOriginal stored = store.storeOriginal(file, "image/jpeg");

        assertThat(store.hashOf(stored.storageKey()))
                .as("다시 읽어 계산한 해시가 원본과 같아야 한다")
                .isEqualTo(ContentHash.of(content));
        assertThat(readAll(stored.storageKey()))
                .as("해시 비교만으로 만족하지 않는다 — 바이트를 직접 대조한다")
                .isEqualTo(content);
        assertThat(stored.byteSize()).isEqualTo(content.length);
    }

    @Test
    @DisplayName("I1: 내용과 다른 체크섬을 붙이면 스토리지가 저장을 거부한다")
    void I1_체크섬이_어긋나면_저장되지_않는다() {
        byte[] content = randomContent(1024);
        ContentHash wrong = ContentHash.of("전혀 다른 내용".getBytes(StandardCharsets.UTF_8));
        String key = StorageKeys.original(wrong);

        // 전송 중 손상을 서버가 잡아내는지 본다. 이 방어선이 없으면 깨진 바이트가 정상 해시의
        // 자리에 앉고, 그 사실은 몇 달 뒤 사진이 안 열릴 때 발견된다.
        assertThatThrownBy(() -> storage.put(key, content, "image/jpeg", wrong))
                .isInstanceOf(StorageException.class);

        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    @DisplayName("I2: 같은 내용을 중복 섞인 임의 순서로 올려도 객체는 내용 종류만큼만 생긴다")
    void I2_중복은_물리적으로_하나만_저장된다() throws IOException {
        List<byte[]> distinct = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            distinct.add(randomContent(4096 + i));
        }

        Random random = new Random(SEED);
        for (int i = 0; i < 20; i++) {
            byte[] picked = distinct.get(random.nextInt(distinct.size()));
            store.storeOriginal(write("upload-" + i + ".jpg", picked), "image/jpeg");
        }

        assertThat(storage.listKeys(StorageKeys.ORIGINALS_PREFIX))
                .as("seed=%d 로 재현할 수 있다", SEED)
                .hasSize(distinct.size());
    }

    @Test
    @DisplayName("I2: 두 번째 저장은 전송하지 않고 같은 자리를 돌려준다")
    void 두_번째_저장은_전송을_생략한다() throws IOException {
        byte[] content = randomContent(2048);

        StoredOriginal first = store.storeOriginal(write("a.jpg", content), "image/jpeg");
        // 파일명이 달라도 내용이 같으면 같은 객체다. 키가 내용만의 함수라는 사실의 결과다.
        StoredOriginal second = store.storeOriginal(write("b.jpg", content), "image/jpeg");

        assertThat(first.reused()).isFalse();
        assertThat(second.reused())
                .as("이 값이 업로드 항목을 SKIPPED_DUPLICATE 로 표시하는 근거가 된다")
                .isTrue();
        assertThat(second.storageKey()).isEqualTo(first.storageKey());
        assertThat(second.hash()).isEqualTo(first.hash());
    }

    @Test
    @DisplayName("끊긴 앞선 저장이 남긴 크기가 다른 객체는 덮어써서 고친다")
    void 크기가_어긋난_객체는_다시_쓴다() throws IOException {
        byte[] content = randomContent(8192);
        ContentHash hash = ContentHash.of(content);
        String key = StorageKeys.original(hash);

        // 중간에 끊긴 업로드를 흉내낸다 — 키는 맞는데 내용이 잘려 있는 상태.
        byte[] truncated = new byte[100];
        System.arraycopy(content, 0, truncated, 0, truncated.length);
        storage.put(key, truncated, "image/jpeg", ContentHash.of(truncated));

        StoredOriginal stored = store.storeOriginal(write("resumed.jpg", content), "image/jpeg");

        assertThat(stored.reused()).isFalse();
        assertThat(store.hashOf(stored.storageKey())).isEqualTo(hash);
    }

    @Test
    @DisplayName("stat 은 내려받지 않고 크기와 보관 중인 체크섬을 돌려준다")
    void stat_이_체크섬을_돌려준다() throws IOException {
        byte[] content = randomContent(1500);
        StoredOriginal stored = store.storeOriginal(write("stat.jpg", content), "image/jpeg");

        ObjectStat stat = storage.stat(stored.storageKey()).orElseThrow();

        assertThat(stat.byteSize()).isEqualTo(content.length);
        // 이 값이 채워진다는 것은 무결성 감사가 원본 수천 개를 내려받지 않고 훑을 수 있다는
        // 뜻이다 (W4). 스토리지를 바꿨을 때 이 성질이 사라지면 여기서 먼저 드러난다.
        assertThat(stat.checksum()).contains(stored.hash());
    }

    @Test
    @DisplayName("없는 키를 읽으면 깨진 참조로 구분되는 예외가 난다")
    void 없는_키는_ObjectNotFound() {
        String key = StorageKeys.original(ContentHash.of("없다".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> store.hashOf(key))
                .isInstanceOf(ObjectNotFoundException.class);
        assertThat(storage.stat(key)).isEmpty();
    }

    @Test
    @DisplayName("없는 키를 지우는 것도 성공이다")
    void 없는_키_삭제는_멱등이다() {
        // 정리 배치는 반드시 두 번 돈다 (design.md §7.2). 두 번째가 예외를 던지면 배치가 멈춘다.
        String key = StorageKeys.original(ContentHash.of("지울_것".getBytes(StandardCharsets.UTF_8)));

        storage.delete(key);
        storage.delete(key);
    }

    private byte[] randomContent(int size) {
        // 랜덤은 시드를 고정한다 (CLAUDE.md 코딩 규칙). 내용마다 다른 시드를 주기 위해
        // 크기를 섞어 쓴다 — 같은 시드로 같은 크기면 같은 바이트가 나온다.
        byte[] content = new byte[size];
        new Random(SEED + size).nextBytes(content);
        return content;
    }

    private Path write(String filename, byte[] content) throws IOException {
        return Files.write(tempDir.resolve(filename), content);
    }

    private byte[] readAll(String key) throws IOException {
        try (InputStream in = storage.open(key)) {
            return in.readAllBytes();
        }
    }
}
