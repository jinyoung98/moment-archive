package dev.jinyoung.archive.auth;

import java.time.Clock;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Component;

/**
 * 소셜 로그인 이전 owner_id 대체.
 *
 * provider+provider_uid find-or-create 로직은 실제 소셜 로그인 도입 후에도 그대로 유지 —
 * 바뀌는 건 신원 출처(OAuth 토큰)뿐. 컨트롤러가 SecurityContext 에서 owner_id 를 꺼내는
 * 순간 이 클래스는 삭제 대상.
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
            // 동시 요청 둘이 동시에 생성 시도한 경우. UNIQUE(provider, provider_uid) 충돌은
            // 에러가 아닌 정상 경로 (pipeline.md §10.1 과 동일 패턴).
            return users.findByProviderAndProviderUid(DEV_PROVIDER, DEV_PROVIDER_UID)
                    .map(User::id)
                    .orElseThrow(() -> e);
        }
    }
}
