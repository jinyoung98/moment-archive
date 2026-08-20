package dev.jinyoung.archive.auth;

import java.time.Clock;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Component;

/**
 * 소셜 로그인이 아직 없는 동안 owner_id 를 대신 채운다.
 *
 * provider+provider_uid 로 find-or-create 하는 로직은 실제 소셜 로그인이 붙어도 그대로
 * 쓴다 — 바뀌는 것은 신원의 출처(OAuth 토큰)뿐이다. 컨트롤러가 SecurityContext 에서
 * owner_id 를 꺼내도록 바뀌는 순간 이 클래스는 삭제 대상이다.
 */
@Component
public class DevUserProvider {

    private static final String DEV_PROVIDER = "DEV";
    private static final String DEV_PROVIDER_UID = "local-dev";

    private final UserRepository users;
    private final JdbcAggregateOperations jdbcOps;
    private final Clock clock;

    public DevUserProvider(UserRepository users, JdbcAggregateOperations jdbcOps, Clock clock) {
        this.users = users;
        this.jdbcOps = jdbcOps;
        this.clock = clock;
    }

    public UUID currentUserId() {
        return users.findByProviderAndProviderUid(DEV_PROVIDER, DEV_PROVIDER_UID)
                .map(User::id)
                .orElseGet(this::createDevUser);
    }

    private UUID createDevUser() {
        User candidate = new User(UUID.randomUUID(), DEV_PROVIDER, DEV_PROVIDER_UID, null, "개발용 사용자", clock.instant());
        try {
            return jdbcOps.insert(candidate).id();
        } catch (DuplicateKeyException e) {
            // 동시 요청 두 개가 동시에 만들려 한 경우. UNIQUE(provider, provider_uid) 충돌은
            // 에러가 아니라 정상 경로다 (pipeline.md §10.1과 같은 패턴).
            return users.findByProviderAndProviderUid(DEV_PROVIDER, DEV_PROVIDER_UID)
                    .map(User::id)
                    .orElseThrow(() -> e);
        }
    }
}
