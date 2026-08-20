package dev.jinyoung.archive.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 허용 판정은 순수 로직이라 컨테이너 없이 고정한다. */
class EmailAllowlistTest {

    @Test
    @DisplayName("목록이 비면 전체 허용 (개발용 기본값)")
    void 빈_목록은_전체_허용() {
        EmailAllowlist allowlist = new EmailAllowlist(new AuthProperties(List.of()));

        assertThat(allowlist.isAllowed("anyone@example.com")).isTrue();
    }

    @Test
    @DisplayName("목록이 있으면 그 안의 이메일만 허용, 대소문자 무시")
    void 목록_안만_허용() {
        EmailAllowlist allowlist = new EmailAllowlist(new AuthProperties(List.of("Me@Example.com")));

        assertThat(allowlist.isAllowed("me@example.com")).isTrue();
        assertThat(allowlist.isAllowed("ME@EXAMPLE.COM")).isTrue();
        assertThat(allowlist.isAllowed("other@example.com")).isFalse();
        assertThat(allowlist.isAllowed(null)).isFalse();
    }

    @Test
    @DisplayName("여러 명을 넣으면 모두 허용 (가족·지인)")
    void 여러_명_허용() {
        EmailAllowlist allowlist = new EmailAllowlist(
                new AuthProperties(List.of("a@x.com", "b@y.com")));

        assertThat(allowlist.isAllowed("a@x.com")).isTrue();
        assertThat(allowlist.isAllowed("b@y.com")).isTrue();
        assertThat(allowlist.isAllowed("c@z.com")).isFalse();
    }
}
