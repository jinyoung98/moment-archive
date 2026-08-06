package dev.jinyoung.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import dev.jinyoung.archive.support.FixedClockConfig;
import dev.jinyoung.archive.support.IntegrationTest;

/**
 * W1 하네스가 실제로 서 있는지 확인하는 최초의 통합 테스트.
 *
 * <p>기능 테스트가 아니라 <b>발판 테스트</b>다. 여기서 검증하는 것은 세 가지다.
 * 컨테이너가 뜨는가, 마이그레이션이 그 위에 적용되는가, 시간을 고정할 수 있는가.
 * 이 셋이 성립해야 이후의 모든 불변식 테스트를 쓸 수 있다.
 *
 * <p>컨테이너는 여러 테스트 클래스가 공유하므로 {@link Transactional} 로 각 테스트를 롤백한다.
 * 앞선 테스트가 남긴 행이 뒤의 테스트에 보이면 실행 순서에 따라 결과가 달라지고,
 * 그 순간 로드맵 §6 에이전트 루프의 전제인 결정론이 깨진다.
 */
@Import(FixedClockConfig.class)
@Transactional
class HarnessIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("컨테이너 위에 W1 스키마가 적용된다")
    void 컨테이너_위에_스키마가_적용된다() {
        var tables = jdbc.sql("""
                        SELECT table_name
                          FROM information_schema.tables
                         WHERE table_schema = 'public'
                        """)
                .query(String.class)
                .list();

        assertThat(tables).contains("users", "media_assets", "media_derivatives");
    }

    @Test
    @DisplayName("Flyway V1 이 성공으로 기록된다")
    void flyway_마이그레이션이_성공으로_기록된다() {
        var succeeded = jdbc.sql("""
                        SELECT success
                          FROM flyway_schema_history
                         WHERE version = '1'
                        """)
                .query(Boolean.class)
                .single();

        assertThat(succeeded).isTrue();
    }

    @Test
    @DisplayName("Clock 이 주입되고 고정된 시각을 돌려준다")
    void clock_이_고정된_시각을_돌려준다() {
        Instant first = clock.instant();
        Instant second = clock.instant();

        assertThat(first).isEqualTo(FixedClockConfig.FIXED_INSTANT);
        assertThat(second).isEqualTo(first);
    }

    /**
     * I2 의 최소 형태. 제대로 된 속성 기반 검증은 스토리지 계층이 붙은 뒤에 온다
     * (roadmap.md §4 I2). 지금은 DB 제약이 실제로 살아 있는지만 확인한다.
     */
    @Test
    @DisplayName("I2: 같은 소유자의 같은 해시는 두 번 들어가지 않는다")
    void 같은_소유자의_같은_해시는_중복_저장되지_않는다() {
        UUID ownerId = insertUser();
        String hash = "a".repeat(64);

        insertAsset(ownerId, hash);

        assertThat(catchThrowable(() -> insertAsset(ownerId, hash)))
                .as("UNIQUE (owner_id, content_hash) 가 중복을 막아야 한다")
                .isNotNull();
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO users (id, provider, provider_uid, created_at)
                        VALUES (?, ?, ?, ?)
                        """)
                .params(id, "GOOGLE", id.toString(), Timestamp.from(clock.instant()))
                .update();
        return id;
    }

    private void insertAsset(UUID ownerId, String hash) {
        jdbc.sql("""
                        INSERT INTO media_assets
                            (id, owner_id, content_hash, byte_size, mime_type, kind,
                             storage_key, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), ownerId, hash, 1024L, "image/jpeg", "PHOTO",
                        "originals/aa/aa/" + hash, "INGESTED", Timestamp.from(clock.instant()))
                .update();
    }
}
