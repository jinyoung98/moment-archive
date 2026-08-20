package dev.jinyoung.archive.auth;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * 인증된 주체. 구글 OIDC 사용자에 **내부 users.id** 를 얹은 것.
 *
 * 컨트롤러가 필요로 하는 owner_id 는 구글의 sub 가 아니라 우리 {@code users.id} 다. 로그인 시
 * find-or-create 로 확정한 그 id 를 여기 담아 세션 동안 들고 다니면, 요청마다 조회할 필요가 없다
 * ({@link CurrentUser} 가 여기서 꺼낸다).
 *
 * 나머지 OidcUser 계약은 전부 위임. Kakao(비 OIDC)를 붙일 땐 OAuth2User 판 별도 주체가 필요.
 */
public final class ArchiveUser implements OidcUser {

    private final OidcUser delegate;
    private final UUID userId;

    public ArchiveUser(OidcUser delegate, UUID userId) {
        this.delegate = delegate;
        this.userId = userId;
    }

    public UUID userId() {
        return userId;
    }

    @Override
    public Map<String, Object> getClaims() {
        return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return delegate.getAuthorities();
    }

    @Override
    public String getName() {
        return delegate.getName();      // sub
    }
}
