package dev.jinyoung.archive.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

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
                .logout(logout -> logout.logoutSuccessUrl("/").permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
        return http.build();
    }
}
