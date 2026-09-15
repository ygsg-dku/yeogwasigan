# 여과시간 정책 명세

## POLICY-01 정책 원칙

1. Purpose가 먼저 필요한 필드·event·relation과 변환을 결정한다.
2. Trust Tier는 Purpose가 요구한 정보 중 허용 가능한 상한만 제한한다.
3. T2도 Purpose 최소화를 적용하고 T0는 항상 DENY한다.
4. Secret은 모든 외부 경로에서 DROP하며 Residual Scan에 한 건이라도 남으면 T1 전송을 막는다.
5. parser, detector, policy, residual scanner의 실패는 T1에서 fail-closed다.

## POLICY-02 Purpose 계약

| atom | INCIDENT_ANALYSIS | INCIDENT_REPORT_DRAFT |
| --- | --- | --- |
| service, error_code, version | KEEP | KEEP |
| timestamp | KEEP_RELATIVE_TIME | GENERALIZE_TO_WINDOW |
| event order | KEEP | milestone만 KEEP |
| relevant stack frame | KEEP | DROP |
| caller_to_callee relation | KEEP | DROP |
| same_transaction relation | TOKENIZE_CASE_SCOPED 후 KEEP | DROP |
| transaction_id | TOKENIZE_CASE_SCOPED | DROP |
| internal_ip | PSEUDONYMIZE_CASE_SCOPED | DROP |
| customer_name, account_number | DROP | DROP |
| api_secret | DROP | DROP |

Action 정의:

- KEEP: 원래 의미를 유지한다.
- DROP: 필드와 값을 패키지에서 제거한다.
- TOKENIZE_CASE_SCOPED: 사건 안의 동일 식별자를 같은 토큰으로 바꾼다.
- PSEUDONYMIZE_CASE_SCOPED: 내부 host 같은 값에 읽기 가능한 사건 범위 가명을 부여한다.
- GENERALIZE: 정밀도를 줄인다. 예: timestamp를 상대 시간 또는 시간 구간으로 바꾼다.

## POLICY-03 Trust Tier

| Tier | 판정 기준 | Package 상한 | 결과 |
| --- | --- | --- | --- |
| T2 Organization Controlled | 조직 compute/data plane, 조직 IAM, 내부 등록 endpoint | Purpose 계약이 허용한 관련 stack frame·pseudonymized host 가능 | PRIVATE_READY |
| T1 Approved Managed External | 승인 외부 endpoint, Enterprise 관리 설정, allowlist 등록 | Purpose 계약 + T1 ceiling. host와 과도한 stack frame은 제한 가능 | EXTERNAL_READY 또는 REVIEW_PENDING |
| T0 Unmanaged | 미등록 endpoint, 개인 계정, 임의 URL | 없음 | DENIED |

## POLICY-04 정책 평가 순서

1. 사용자 인증·Purpose role·input schema를 확인한다.
2. ACTIVE Purpose version의 field/relation 규칙을 읽는다.
3. Classification 결과가 unknown이거나 parser가 실패하면 T1은 REVIEW_PENDING 또는 DENIED다.
4. Package Builder가 공통 Secret DROP과 Purpose action을 적용한다.
5. Destination의 Trust ceiling을 적용한다.
6. Residual Scan이 Secret·금지 atom을 검사한다.
7. Policy가 REVIEW를 요구하면 approval fingerprint를 만들고, 아니면 READY/DENIED를 확정한다.

정책 우선순위는 hard Secret DROP/외부 차단 > T0 DENY > schema·role 검증 > Purpose action > Trust ceiling > 승인 요구다. 하위 규칙은 상위 차단을 해제할 수 없다.

## POLICY-05 Approval Invalidation

fingerprint = SHA-256(rawHash | derivedHash | purposeCode/version | destinationId/version | policyVersion)

위 구성요소가 달라지거나 active policy가 보안상 사용 중지되면 Approval은 INVALIDATED다. 새 Purpose version이 활성화됐다는 이유만으로 과거 요청의 version을 바꾸지는 않는다.

## POLICY-06 Purpose Lifecycle

~~~text
DRAFT → SECURITY_REVIEW → ACTIVE → DEPRECATED
~~~

서비스 담당자는 field/event/relation 초안을 만들고 보안 담당자는 금지 atom, allowedRoles, Trust ceiling을 검토한다. ACTIVE만 신규 요청에 사용된다. DEPRECATED는 과거 Audit 재현을 위해 남지만 신규 요청에는 사용하지 않는다. MVP에서는 이 lifecycle을 seed migration과 테스트로만 관리한다.

## POLICY-07 CASE_SCOPED_TOKENIZE

~~~text
normalized = normalizeByType(originalValue)
token = base64url(HMAC-SHA256(serverSecret,
  tokenizationVersion || 0x00 || caseId || 0x00 || type || 0x00 || normalized))
~~~

같은 caseId·type·normalized value는 같은 token, 다른 caseId는 다른 token이다. MVP는 합성 데이터와 개발용 secret만 쓴다. KMS/HSM 보관·rotation은 운영 한계로 기록하며 HMAC이 익명화나 재식별 불가능을 보장한다고 주장하지 않는다.

## POLICY-08 Strong Fixed Policy Baseline

B3는 단순 정규식 마스킹이 아니다. B3와 여과시간은 같은 schema parse, normalize, detector, Secret DROP, tokenization, residual scan, model, prompt, decoding, network를 사용한다. B3는 모든 Incident 업무에 하나의 INCIDENT_SAFE_FIXED_V1 field/event/relation 규칙을 적용하며 여과시간만 Purpose별 선택 규칙을 적용한다.
