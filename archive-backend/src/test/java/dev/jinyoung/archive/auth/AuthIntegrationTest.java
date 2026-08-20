package dev.jinyoung.archive.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static dev.jinyoung.archive.support.AuthTestSupport.archiveUser;
import static dev.jinyoung.archive.support.AuthTestSupport.asUser;
import static dev.jinyoung.archive.support.AuthTestSupport.oidcUser;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.servlet.MockMvc;

import dev.jinyoung.archive.support.IntegrationTest;

/**
 * 인증 배선 검증. 실제 구글 왕복은 없다 — 로그인 후처리(process)에 합성 OIDC 사용자를 넣고,
 * 보호 경로는 인증된 주체를 주입하거나(200) 주입하지 않아(401) 확인한다.
 */
@AutoConfigureMockMvc
class AuthIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserAccountService userAccounts;
    @Autowired
    private UserRepository users;

    @Test
    @DisplayName("허용 이메일로 로그인하면 users 행이 생기고 내부 id 를 실은 주체가 된다")
    void 허용_로그인은_계정_생성() {
        var service = new ArchiveOidcUserService(userAccounts, new EmailAllowlist(
                new AuthProperties(List.of("allowed@example.com"))));
        OidcUser raw = oidcUser("google-sub-1", "allowed@example.com", "허용된 사람");

        OidcUser result = service.process("GOOGLE", raw);

        assertThat(result).isInstanceOf(ArchiveUser.class);
        var user = users.findByProviderAndProviderUid("GOOGLE", "google-sub-1").orElseThrow();
        assertThat(((ArchiveUser) result).userId()).isEqualTo(user.id());
        assertThat(user.email()).isEqualTo("allowed@example.com");
    }

    @Test
    @DisplayName("허용목록 밖 이메일은 로그인 거부 (OAuth 인증은 성공했어도)")
    void 허용목록_밖은_거부() {
        var service = new ArchiveOidcUserService(userAccounts, new EmailAllowlist(
                new AuthProperties(List.of("allowed@example.com"))));
        OidcUser raw = oidcUser("google-sub-2", "stranger@example.com", "낯선 사람");

        assertThatThrownBy(() -> service.process("GOOGLE", raw))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(users.findByProviderAndProviderUid("GOOGLE", "google-sub-2")).isEmpty();
    }

    @Test
    @DisplayName("미인증 요청은 401 — GET /api/me")
    void 미인증_me_는_401() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("미인증 업로드는 401 (CSRF 통과시켜 인증이 관문임을 드러냄)")
    void 미인증_업로드는_401() throws Exception {
        var file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1, 2, 3});

        mockMvc.perform(multipart(HttpMethod.PUT, "/media").file(file).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증되면 /api/me 가 내 이메일을 돌려준다")
    void 인증된_me() throws Exception {
        var user = userAccounts.findOrCreate("GOOGLE", "google-sub-me", "me@example.com", "나");

        mockMvc.perform(get("/api/me").with(asUser(archiveUser(user.id(), user.email()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("me@example.com"))
                .andExpect(jsonPath("$.id").value(user.id().toString()));
    }
}
