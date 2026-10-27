package com.capstone.yeogwasigan.policy;

import java.util.Map;

/**
 * Policy Evaluator: 전송 대상의 신뢰 등급(T2/T1/T0)과 앞 단계 검사 결과로 요청의 다음 상태를 정한다.
 * (정책 명세 POLICY-03 Trust Tier, POLICY-04 평가 순서, TASK-03-01, T-UNIT-04)
 *
 * <ul>
 *   <li><b>등급</b>: T2 = 조직 내부 등록 AI, T1 = 승인된 외부 AI(allowlist 등록), T0 = 그 밖의 모든 대상.
 *       등록부에 없는 대상 ID 는 T0 로 본다. 등급을 요청에서 받지 않고 등록부에서만 읽는다.</li>
 *   <li><b>우선순위</b>: 비밀값 잔존 &gt; T0 거부 &gt; 역할·목적 검증 &gt; 검사 실패 &gt; 승인 요구.
 *       아래 규칙은 위 규칙의 거부를 풀 수 없다.</li>
 *   <li><b>fail-closed</b>: 입력이 비었거나 평가 중 예외가 나면 {@code POLICY_FAILED} 로 거부한다.</li>
 * </ul>
 *
 * 규칙표는 docs/spec/16_POLICY_EVALUATOR.md 에 있다. 규칙을 바꾸면 {@link #POLICY_VERSION} 도 올린다(승인 지문에 들어갈 값).
 */
public final class PolicyEvaluator {

    public static final String POLICY_VERSION = "policy-v1";

    public static final String REASON_T0_DESTINATION = "T0_DESTINATION";
    public static final String REASON_ROLE_NOT_ALLOWED = "ROLE_NOT_ALLOWED";
    public static final String REASON_PURPOSE_NOT_ACTIVE = "PURPOSE_NOT_ACTIVE";
    public static final String REASON_PIPELINE_FAILED = "PIPELINE_FAILED";
    public static final String REASON_POLICY_FAILED = "POLICY_FAILED";
    public static final String REASON_T2_READY = "T2_READY";
    public static final String REASON_T1_READY = "T1_READY";
    public static final String REASON_HIGH_WARNING = "T1_HIGH_WARNING";
    public static final String REASON_APPROVAL_ALWAYS = "T1_APPROVAL_ALWAYS";
    public static final String REASON_CLASSIFICATION_UNKNOWN = "T1_CLASSIFICATION_UNKNOWN";

    public enum Tier { T2, T1, T0 }

    public enum Decision { PRIVATE_READY, EXTERNAL_READY, REVIEW_PENDING, DENIED }

    /** 파서·탐지기 결과. UNKNOWN = 돌았지만 분류하지 못한 칸이 있음, FAILED = 예외로 끝까지 못 돎. */
    public enum Check { OK, UNKNOWN, FAILED }

    /**
     * 평가 입력. 원문과 파생본 값은 받지 않는다(결과·감사 기록에 값이 새지 않게).
     *
     * @param destinationId  요청이 고른 전송 대상 ID
     * @param roleAllowed    요청자 역할이 이 목적(Purpose)에 허용되는지
     * @param purposeActive  목적 템플릿이 ACTIVE 인지(DEPRECATED·DRAFT 는 신규 요청 불가)
     * @param pipeline       파서·탐지기 결과
     * @param residual       Residual Scan 결과({@link ResidualScanner})
     * @param highWarnings   높음 참고 경고 건수({@code WarningRules})
     * @param approvalAlways 승인 모드가 ALWAYS 인지(모든 T1 요청에 승인)
     */
    public record Input(String destinationId, boolean roleAllowed, boolean purposeActive, Check pipeline,
                        ResidualScanner.Result residual, int highWarnings, boolean approvalAlways) {
    }

    /**
     * @param decision   다음 상태
     * @param tier       등록부에서 읽은 등급(평가 실패면 T0)
     * @param reasonCode 결정을 만든 규칙
     * @param policyVersion 평가에 쓴 규칙 버전
     */
    public record Result(Decision decision, Tier tier, String reasonCode, String policyVersion) {

        public boolean denied() {
            return decision == Decision.DENIED;
        }

        /** 지금 바로 보낼 수 있는지(승인 대기·거부가 아님). */
        public boolean ready() {
            return decision == Decision.PRIVATE_READY || decision == Decision.EXTERNAL_READY;
        }
    }

    private final Map<String, Tier> registry;

    /** @param registry 등록된 전송 대상 ID → 등급. T0 로 등록할 이유가 없으므로 T0 값은 받지 않는다. */
    public PolicyEvaluator(Map<String, Tier> registry) {
        if (registry.containsValue(Tier.T0)) {
            throw new IllegalArgumentException("T0 는 등록하지 않는다. 등록부에 없으면 T0 다: " + registry);
        }
        this.registry = Map.copyOf(registry);
    }

    /** 등록부에 없거나 비어 있는 ID 는 T0. */
    public Tier tierOf(String destinationId) {
        return destinationId == null ? Tier.T0 : registry.getOrDefault(destinationId, Tier.T0);
    }

    public Result evaluate(Input in) {
        try {
            return decide(in);
        } catch (RuntimeException e) {
            return new Result(Decision.DENIED, Tier.T0, REASON_POLICY_FAILED, POLICY_VERSION);
        }
    }

    private Result decide(Input in) {
        Tier tier = tierOf(in.destinationId());
        // 1. 비밀값·금지 atom 이 남았거나 스캔이 실패했으면 어느 등급이든 거부
        if (!in.residual().passed()) {
            return deny(tier, in.residual().reasonCode());
        }
        // 2. T0 는 내용과 무관하게 거부
        if (tier == Tier.T0) {
            return deny(tier, REASON_T0_DESTINATION);
        }
        // 3. 역할·목적 검증
        if (!in.roleAllowed()) {
            return deny(tier, REASON_ROLE_NOT_ALLOWED);
        }
        if (!in.purposeActive()) {
            return deny(tier, REASON_PURPOSE_NOT_ACTIVE);
        }
        // 4. 파서·탐지기가 끝까지 못 돌았으면 거부(fail-closed)
        if (in.pipeline() == Check.FAILED) {
            return deny(tier, REASON_PIPELINE_FAILED);
        }
        // 5. T2 는 조직 안이라 바로 실행 가능
        if (tier == Tier.T2) {
            return new Result(Decision.PRIVATE_READY, tier, REASON_T2_READY, POLICY_VERSION);
        }
        // 6. T1 은 승인이 필요한지 본다
        if (in.pipeline() == Check.UNKNOWN) {
            return review(REASON_CLASSIFICATION_UNKNOWN);
        }
        if (in.highWarnings() > 0) {
            return review(REASON_HIGH_WARNING);
        }
        if (in.approvalAlways()) {
            return review(REASON_APPROVAL_ALWAYS);
        }
        return new Result(Decision.EXTERNAL_READY, tier, REASON_T1_READY, POLICY_VERSION);
    }

    private static Result deny(Tier tier, String reason) {
        return new Result(Decision.DENIED, tier, reason, POLICY_VERSION);
    }

    private static Result review(String reason) {
        return new Result(Decision.REVIEW_PENDING, Tier.T1, reason, POLICY_VERSION);
    }
}
