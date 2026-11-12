package com.capstone.yeogwasigan.web;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.capstone.yeogwasigan.gateway.GatewayProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 시연 전용 사용자 전환 (발표에서 로그인 화면을 건너뛴다).
 *
 * <ul>
 *   <li>{@code demo} 프로파일에서만 켜진다. 기본 실행·운영에는 이 경로가 없다(404).</li>
 *   <li>같은 컴퓨터(루프백)에서 온 요청만 받는다. 다른 컴퓨터에서 오면 403.</li>
 *   <li>전환해도 계정과 역할은 {@code yeogwasigan.gateway.users} 그대로라 "요청자≠승인자" 규칙은 그대로 적용된다.</li>
 * </ul>
 * ponytail: 상태를 바꾸는 GET 이라 CSRF 보호가 없다. 루프백 + demo 프로파일로만 막는다. 발표 밖에서 쓸 일이 생기면 POST + CSRF 로.
 */
@RestController
@Profile("demo")
public class DemoSwitchController {

    private final UserDetailsService users;
    private final GatewayProperties props;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public DemoSwitchController(UserDetailsService users, GatewayProperties props) {
        this.users = users;
        this.props = props;
    }

    /** 전환할 수 있는 시연 계정. 화면은 이 경로가 있으면(=demo 프로파일) 전환 버튼을 그린다. */
    @GetMapping("/demo/users")
    public List<String> list() {
        return props.users().keySet().stream().sorted().toList();
    }

    /** 발표 시작 주소: 첫 요청자 계정으로 바로 게이트웨이 화면을 연다. */
    @GetMapping("/demo")
    public void start(@RequestParam(defaultValue = "/gateway.html") String to,
                      HttpServletRequest req, HttpServletResponse res) throws IOException {
        // 역할이 요청자 하나뿐인 계정을 먼저 (두 역할을 다 가진 계정은 403 시연용)
        String first = list().stream().filter(u -> props.users().get(u).contains(GatewayProperties.Role.REQUESTER))
                .min(java.util.Comparator.comparingInt(u -> props.users().get(u).size()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "요청자 계정이 없습니다"));
        switchTo(first, to, req, res);
    }

    @GetMapping("/demo/switch")
    public void switchTo(@RequestParam String user, @RequestParam(defaultValue = "/gateway.html") String to,
                         HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (!InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "시연 전환은 같은 컴퓨터에서만 됩니다");
        }
        if (!props.users().containsKey(user)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 시연 계정: " + user);
        }
        UserDetails u = users.loadUserByUsername(user);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(u, null, u.getAuthorities()));
        SecurityContextHolder.setContext(ctx);
        contexts.saveContext(ctx, req, res);
        // 돌아갈 곳은 시연 화면 두 개만 (다른 주소로 보내는 열린 리다이렉트 방지)
        res.sendRedirect(to.matches("/(gateway|proposals)\\.html(\\?(id=[A-Z0-9-]+|purpose=[A-Z0-9_]+))?") ? to : "/gateway.html");
    }
}
