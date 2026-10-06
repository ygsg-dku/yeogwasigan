package com.capstone.yeogwasigan.web;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import com.capstone.yeogwasigan.gateway.GatewayProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 게이트웨이 로그인과 역할.
 *
 * <ul>
 *   <li>게이트웨이 화면·API 만 로그인이 필요하다. 실험 데모 화면(/, /api/analyze 등)은 그대로 열어 둔다.</li>
 *   <li>계정은 {@code yeogwasigan.gateway.users} 의 사용자·역할(REQUESTER, APPROVER). 비밀번호는 YG_DEMO_PASSWORD.</li>
 *   <li>요청을 만들거나 승인하는 요청에는 CSRF 토큰(XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더)이 필요하다.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/gateway.html", "/api/requests/**", "/api/gateway/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(f -> f.defaultSuccessUrl("/gateway.html", true))
                .logout(l -> l.logoutSuccessUrl("/login?logout"))
                .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(r -> !r.getRequestURI().startsWith("/api/requests")
                                && !r.getRequestURI().equals("/logout")))
                // API 는 로그인 화면으로 돌리지 않고 401 로 답한다
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), r -> r.getRequestURI().startsWith("/api/")))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    // ponytail: 시연 계정은 공통 비밀번호 하나다. 실제 운영은 사내 계정(SSO·LDAP)에 붙일 것.
    @Bean
    UserDetailsService users(GatewayProperties props, PasswordEncoder encoder) {
        String password = props.demoPassword();
        if (password == null || password.isBlank()) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            password = HexFormat.of().formatHex(b);
            log.warn("YG_DEMO_PASSWORD 가 없어 이번 실행용 시연 비밀번호를 만들었습니다: {}", password);
        }
        String encoded = encoder.encode(password);
        List<UserDetails> list = props.users().entrySet().stream()
                .map(e -> User.withUsername(e.getKey()).password(encoded)
                        .roles(e.getValue().stream().map(Enum::name).toArray(String[]::new)).build())
                .toList();
        return new InMemoryUserDetailsManager(list);
    }

    /** 첫 화면에서 XSRF-TOKEN 쿠키가 바로 만들어지도록 토큰을 한 번 읽는다(Spring Security 6 은 토큰을 늦게 만든다). */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            if (req.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(req, res);
        }
    }
}
