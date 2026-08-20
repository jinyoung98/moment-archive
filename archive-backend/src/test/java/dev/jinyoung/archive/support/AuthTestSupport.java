package dev.jinyoung.archive.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import dev.jinyoung.archive.auth.ArchiveUser;

/**
 * 실제 구글 왕복 없이 인증된 {@link ArchiveUser} 를 만든다. 로그인 후처리
 * (ArchiveOidcUserService)가 세션에 담는 것과 같은 모양의 주체를 테스트가 직접 주입한다.
 */
public final class AuthTestSupport {

    private AuthTestSupport() {
    }

    /** 합성 OIDC 사용자. sub·email·name 클레임만 채운다. */
    public static OidcUser oidcUser(String sub, String email, String name) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .subject(sub)
                .claim("email", email)
                .claim("name", name)
                .build();
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken, "sub");
    }

    public static ArchiveUser archiveUser(UUID userId, String email) {
        return new ArchiveUser(oidcUser(userId.toString(), email, "테스트 사용자"), userId);
    }

    /** MockMvc 요청을 이 사용자로 인증시킨다 (oauth2Login 이 만드는 토큰과 같은 타입). */
    public static RequestPostProcessor asUser(ArchiveUser user) {
        var token = new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
        return authentication(token);
    }
}
