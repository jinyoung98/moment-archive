package dev.jinyoung.archive.auth;

import java.io.IOException;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 보안 설정. 소셜 로그인 + httpOnly 쿠키 세션 (JWT·Redis 미사용, ADR A20).
 *
 * 결정 몇 가지:
 * - **미인증 응답은 302 리다이렉트가 아니라 401.** SPA·테스트가 소비하는 API 라, 보호 자원에
 *   접근하면 로그인 페이지로 튕기는 대신 상태코드를 준다. 로그인은 클라이언트가 명시적으로
 *   {@code /oauth2/authorization/google} 로 이동해 시작.
 * - **CSRF 유지.** 쿠키 세션이라 CSRF 가 실재하는 위협이다. 더블서밋 쿠키 방식으로,
 *   JS 가 읽어 되보낼 수 있게 {@code XSRF-TOKEN} 은 httpOnly 를 끈다. 끄지 않는 편이 쉬웠지만
 *   포트폴리오에서 "쿠키 세션인데 CSRF 는?" 에 답할 수 있어야 한다.
 *   - Spring Security 6 은 CSRF 토큰 **지연 로딩** — 실제로 읽는 요청에서만 쿠키 발행. SPA 는 첫
 *     GET 응답에 쿠키가 있어야 이후 변경 요청(PUT /media)에 헤더 탑재 가능 → {@link CsrfCookieFilter}
 *     로 매 요청 토큰 렌더, 쿠키 보장.
 *   - 기본 {@code XorCsrfTokenRequestAttributeHandler} 는 요청마다 XOR 인코딩(BREACH 완화) → 쿠키
 *     원문 ≠ 헤더 값. SPA 는 쿠키를 그대로 되보냄 → {@link CsrfTokenRequestAttributeHandler}(원문)로
 *     교체. 문서화된 SPA 패턴.
 * - 세션 저장은 인메모리(서블릿 기본). 재시작 시 재로그인 — 개인 서비스 규모라 감수. 유지가
 *   필요해지면 Spring Session JDBC(Postgres)로 승격, 그 전엔 인프라를 늘리지 않는다.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ArchiveOidcUserService oidcUserService)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 로그인 진입·콜백·에러는 열고, 나머지는 인증 요구.
                        .requestMatchers("/", "/error", "/login/**", "/oauth2/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth -> oauth
                        .userInfoEndpoint(info -> info.oidcUserService(oidcUserService)))
                // SavedRequest 재생 끔. SPA 첫 로드의 /api/me(보호 자원) XHR 이 "로그인 후 돌아갈 곳"
                // 으로 저장 → OAuth 성공 후 그 JSON 으로 튕김(/api/me?continue). 서버 보호 자원은 전부
                // JSON API, 되돌릴 이유 없음. 로그인 성공은 항상 '/'(SPA)로, 클라이언트가 /api/me 재확인.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .logout(logout -> logout.logoutSuccessUrl("/").permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 매 요청 {@link CsrfToken#getToken()} 호출 → 지연 로딩 강제 → 쿠키 저장소가 {@code XSRF-TOKEN}
     * 을 응답에 실음. 없으면 SPA 첫 로드에 쿠키 부재 → 첫 변경 요청 403.
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();   // 지연 로딩 트리거 — 쿠키 렌더 유발
            }
            chain.doFilter(request, response);
        }
    }
}
