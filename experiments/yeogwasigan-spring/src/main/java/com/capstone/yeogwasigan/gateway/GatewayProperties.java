package com.capstone.yeogwasigan.gateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import com.capstone.yeogwasigan.policy.PolicyEvaluator.Tier;

/**
 * 게이트웨이 설정 ({@code yeogwasigan.gateway.*}).
 *
 * @param users     사용자 → 역할(REQUESTER, APPROVER). 로그인 계정이 된다({@code SecurityConfig}).
 * @param endpoints 등록된 전송 대상 ID. 여기 없는 대상으로는 요청을 만들 수 없다(D6).
 * @param demoPassword 시연 계정 공통 비밀번호. 환경변수 YG_DEMO_PASSWORD 로만 넣는다. 비우면 실행마다 무작위
 * @param payloadRetentionDays 파생본 보관 일수. 지나면 파생본을 지우고 해시·감사 기록만 남긴다(D12). 기본 30
 * @param approvalMode 승인 정책. ALWAYS = 모든 요청에 다른 사람의 승인, CONDITIONAL = 높음 경고가 있을 때만 승인
 *                     (docs/spec/14_APPROVAL_AND_WARNING.md). 기본 ALWAYS
 * @param tiers     전송 대상 ID → 신뢰 등급(T2 내부, T1 승인된 외부). endpoints 에 있는데 여기 없으면 T1.
 *                  endpoints 에 없는 대상은 T0 라서 적을 수 없다(docs/spec/16_POLICY_EVALUATOR.md)
 */
@ConfigurationProperties(prefix = "yeogwasigan.gateway")
public record GatewayProperties(Map<String, List<Role>> users, List<String> endpoints, int payloadRetentionDays,
                                String demoPassword, ApprovalMode approvalMode, Map<String, Tier> tiers) {

    public enum Role { REQUESTER, APPROVER }

    public enum ApprovalMode { ALWAYS, CONDITIONAL }

    @ConstructorBinding
    public GatewayProperties {
        users = users != null ? Map.copyOf(users) : Map.of();
        endpoints = endpoints != null ? List.copyOf(endpoints) : List.of();
        payloadRetentionDays = payloadRetentionDays > 0 ? payloadRetentionDays : 30;
        approvalMode = approvalMode != null ? approvalMode : ApprovalMode.ALWAYS;
        tiers = tiers != null ? Map.copyOf(tiers) : Map.of();
        if (!endpoints.containsAll(tiers.keySet())) {
            throw new IllegalArgumentException("등급을 적은 대상이 endpoints 에 없습니다: " + tiers.keySet() + " (등록: " + endpoints + ")");
        }
    }

    /** 등급 설정 없이(모든 등록 대상 T1) 만든다. */
    public GatewayProperties(Map<String, List<Role>> users, List<String> endpoints, int payloadRetentionDays,
                             String demoPassword, ApprovalMode approvalMode) {
        this(users, endpoints, payloadRetentionDays, demoPassword, approvalMode, null);
    }

    /** 등록된 전송 대상 → 등급. 정책 평가기의 등록부가 된다. */
    public Map<String, Tier> registry() {
        Map<String, Tier> out = new LinkedHashMap<>();
        endpoints.forEach(e -> out.put(e, tiers.getOrDefault(e, Tier.T1)));
        return out;
    }
}
