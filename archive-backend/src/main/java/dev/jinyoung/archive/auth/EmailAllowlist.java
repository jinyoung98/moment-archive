package dev.jinyoung.archive.auth;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 로그인 이메일 허용 판정. 규칙은 {@link AuthProperties} 참조. */
@Component
public class EmailAllowlist {

    private static final Logger log = LoggerFactory.getLogger(EmailAllowlist.class);

    private final AuthProperties props;

    public EmailAllowlist(AuthProperties props) {
        this.props = props;
        if (props.allowedEmails().isEmpty()) {
            // 배포에서 이 상태면 누구나 계정을 만든다. 조용히 넘어가지 않게 시작 시 경고.
            log.warn("archive.auth.allowed-emails 가 비어 있음 — 모든 이메일 로그인 허용 (배포 전 설정할 것)");
        }
    }

    public boolean isAllowed(String email) {
        if (props.allowedEmails().isEmpty()) {
            return true;        // 미설정 = 개발용 전체 허용
        }
        if (email == null) {
            return false;
        }
        return props.allowedEmails().contains(email.trim().toLowerCase(Locale.ROOT));
    }
}
