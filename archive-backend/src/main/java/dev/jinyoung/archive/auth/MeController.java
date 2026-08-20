package dev.jinyoung.archive.auth;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 로그인 사용자 조회. 프론트가 "로그인 상태인가"를 확인하는 자리.
 *
 * 보호 경로라 미인증이면 컨트롤러 전에 401. 그래서 principal 은 항상 non-null.
 */
@RestController
public class MeController {

    @GetMapping("/api/me")
    public Me me(@AuthenticationPrincipal ArchiveUser user) {
        return new Me(user.userId(), user.getEmail(), user.getFullName());
    }

    public record Me(UUID id, String email, String displayName) {
    }
}
