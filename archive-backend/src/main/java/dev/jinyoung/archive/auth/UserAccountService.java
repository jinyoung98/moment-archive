package dev.jinyoung.archive.auth;

import java.time.Clock;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Service;

/**
 * 소셜 신원 → 내부 사용자 행. find-or-create.
 *
 * DevUserProvider 에 있던 로직을 provider 무관하게 일반화한 것 — 그 주석의 예고대로,
 * 바뀌는 건 신원 출처(고정값 → OAuth)뿐이고 로직은 그대로다. `(provider, provider_uid)` 가
 * 정체성이라 Google·Kakao 가 같은 표에 공존한다.
 */
@Service
public class UserAccountService {

    private final UserRepository users;
    private final JdbcAggregateOperations jdbcOps;
    private final Clock clock;

    public UserAccountService(UserRepository users, JdbcAggregateOperations jdbcOps, Clock clock) {
        this.users = users;
        this.jdbcOps = jdbcOps;
        this.clock = clock;
    }

    public User findOrCreate(String provider, String providerUid, String email, String displayName) {
        return users.findByProviderAndProviderUid(provider, providerUid)
                .orElseGet(() -> create(provider, providerUid, email, displayName));
    }

    private User create(String provider, String providerUid, String email, String displayName) {
        User candidate = new User(UUID.randomUUID(), provider, providerUid, email, displayName, clock.instant());
        try {
            return jdbcOps.insert(candidate);
        } catch (DuplicateKeyException e) {
            // 동시 로그인 둘이 같은 신원으로 생성 시도. UNIQUE(provider, provider_uid) 충돌은
            // 에러가 아닌 정상 경로 (pipeline.md §10.1 과 동일 패턴).
            return users.findByProviderAndProviderUid(provider, providerUid).orElseThrow(() -> e);
        }
    }
}
