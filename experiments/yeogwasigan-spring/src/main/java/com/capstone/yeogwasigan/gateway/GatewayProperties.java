package com.capstone.yeogwasigan.gateway;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 게이트웨이 설정 ({@code yeogwasigan.gateway.*}).
 *
 * @param users     사용자 → 역할(REQUESTER, APPROVER). 로그인 계정이 된다({@code SecurityConfig}).
 * @param endpoints 등록된 전송 대상 ID. 여기 없는 대상으로는 요청을 만들 수 없다(D6).
 * @param demoPassword 시연 계정 공통 비밀번호. 환경변수 YG_DEMO_PASSWORD 로만 넣는다. 비우면 실행마다 무작위
 * @param payloadRetentionDays 파생본 보관 일수. 지나면 파생본을 지우고 해시·감사 기록만 남긴다(D12). 기본 30
 */
@ConfigurationProperties(prefix = "yeogwasigan.gateway")
public record GatewayProperties(Map<String, List<Role>> users, List<String> endpoints, int payloadRetentionDays,
                                String demoPassword) {

    public enum Role { REQUESTER, APPROVER }

    public GatewayProperties {
        users = users != null ? Map.copyOf(users) : Map.of();
        endpoints = endpoints != null ? List.copyOf(endpoints) : List.of();
        payloadRetentionDays = payloadRetentionDays > 0 ? payloadRetentionDays : 30;
    }
}
