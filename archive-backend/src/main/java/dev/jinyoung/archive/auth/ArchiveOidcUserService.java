package dev.jinyoung.archive.auth;

import java.util.Locale;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * 구글 로그인 성공 시 후처리 — 허용목록 검사 + find-or-create + 주체 확장.
 *
 * {@link #loadUser}(네트워크: userinfo/id_token 취득)와 {@link #process}(순수 로직)를 분리했다.
 * 검증 전략상 중요 — 실제 구글 왕복 없이 {@code process} 에 합성 OidcUser 를 넣어 허용목록·
 * 계정 생성 판단을 단위 테스트로 고정한다. libvips 를 CLI seam 으로 뺀 것과 같은 결.
 */
@Service
public class ArchiveOidcUserService extends OidcUserService {

    private final UserAccountService accounts;
    private final EmailAllowlist allowlist;

    public ArchiveOidcUserService(UserAccountService accounts, EmailAllowlist allowlist) {
        this.accounts = accounts;
        this.allowlist = allowlist;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser raw = super.loadUser(userRequest);
        String provider = userRequest.getClientRegistration().getRegistrationId().toUpperCase(Locale.ROOT);
        return process(provider, raw);
    }

    /** 네트워크 없는 후처리. 테스트가 여기를 직접 부른다. */
    OidcUser process(String provider, OidcUser raw) {
        String email = raw.getEmail();
        if (!allowlist.isAllowed(email)) {
            // 허용목록 밖 = 로그인 거부. OAuth 인증은 성공했으나 우리 정책이 막는다.
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied", "허용되지 않은 이메일입니다", null));
        }
        User user = accounts.findOrCreate(provider, raw.getSubject(), email, raw.getFullName());
        return new ArchiveUser(raw, user.id());
    }
}
