package dev.jinyoung.archive.auth;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 설정.
 *
 * @param allowedEmails 로그인 허용 이메일. 공개 배포되는 개인 서비스라 아무 구글 계정이 계정을
 *                      만드는 것을 막는다. **비면 전체 허용**(개발용) — 배포 시 반드시 채운다.
 *                      여러 명(가족·지인) 추가 가능. 비교는 소문자·trim 정규화.
 */
@ConfigurationProperties("archive.auth")
public record AuthProperties(List<String> allowedEmails) {

    public AuthProperties {
        allowedEmails = allowedEmails == null
                ? List.of()
                : allowedEmails.stream()
                        .filter(e -> e != null && !e.isBlank())
                        .map(e -> e.trim().toLowerCase(Locale.ROOT))
                        .toList();
    }
}
