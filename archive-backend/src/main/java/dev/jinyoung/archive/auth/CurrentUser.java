package dev.jinyoung.archive.auth;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 현재 요청의 owner_id 출처. {@link org.springframework.security.core.context.SecurityContext}
 * 에서 {@link ArchiveUser} 를 꺼내 내부 users.id 를 돌려준다.
 *
 * DevUserProvider(고정 사용자)를 대체 — 그 클래스 주석이 예고한 "컨트롤러가 SecurityContext
 * 에서 owner_id 를 꺼내는 한 줄". 엔드포인트가 인증을 요구하므로 미인증 요청은 여기 오기 전에
 * 401 로 걸린다.
 */
@Component
public class CurrentUser {

    public UUID requireUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ArchiveUser user) {
            return user.userId();
        }
        // 보안 설정이 정상이면 도달 불가 — 방어적 가드.
        throw new IllegalStateException("인증된 ArchiveUser 가 없다");
    }
}
