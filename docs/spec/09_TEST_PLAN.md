# 여과시간 Test Plan

각 테스트는 합성 fixture만 사용한다. 외부 receiver는 수신 횟수와 canary 여부를 기록한다.

| ID | 분류 | 선행조건·입력 | 절차 | Expected Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| T-UNIT-01 | Unit | 동일 caseId/value | token 생성 두 번 | 같은 token | HMAC test vector |
| T-UNIT-02 | Unit | 다른 caseId/동일 value | token 생성 | 다른 token | test output |
| T-UNIT-03 | Unit | ANALYSIS/REPORT fixture | Package Builder | 계약 action만 적용, package 다름 | golden manifest |
| T-UNIT-04 | Unit | T0 destination | Policy Engine | DENIED | decision fixture |
| T-UNIT-05 | Unit | ACTIVE/DEPRECATED template | 신규 요청 생성 | ACTIVE만 허용 | lifecycle test |
| T-INT-01 | Integration | SRE, T1, 정상 로그 | create→prepare | REVIEW_PENDING 또는 EXTERNAL_READY | DB state/Audit |
| T-INT-02 | Integration | Secret 포함 package | residual scan | DENIED, T1 receiver 0 | finding/Audit/receiver log |
| T-INT-03 | Integration | parser exception | prepare | T1 fail-closed, receiver 0 | reasonCode |
| T-INT-04 | Integration | detector/scanner exception | prepare | REVIEW_PENDING 또는 DENIED | failure injection |
| T-SEC-01 | Security | T0 destination | prepare/execute | DENIED, adapter 호출 0 | execution count |
| T-SEC-02 | Security | self reviewer | approve | 403 SELF_APPROVAL_FORBIDDEN | API response/Audit |
| T-SEC-03 | Security | 승인 후 rawHash 변경 | execute | 409 APPROVAL_INVALIDATED | Approval row/Audit |
| T-SEC-04 | Security | 승인 후 Purpose 변경 | execute | 409 APPROVAL_INVALIDATED | parameterized test |
| T-SEC-05 | Security | 승인 후 Destination/Policy 변경 | execute | 409 APPROVAL_INVALIDATED | parameterized test |
| T-NET-01 | Network | internal-client container | 직접 T1/T0 curl | 연결 실패, receiver 0 | command output |
| T-NET-02 | Network | 정상 T1 승인 request | 여과시간 execute | receiver 1회 수신, result stored | receiver log/Audit |
| T-NET-03 | Network | spoofed URL/header | create/execute | URL 무시 또는 400/403 | API test |
| T-NET-04 | Network | controlled receiver 통과, 등록 T1 자격증명 | Dispatcher로 합성 package 실행 | allowlist T1에서만 2xx와 result, 자격증명·payload 원문은 log 0 | request ID·response digest·Audit |
| T-CON-01 | Concurrency | EXTERNAL_READY request | 동시에 execute 2회 | 한 요청만 EXECUTING, 다른 요청 409 | DB/Audit |
| T-CON-02 | Concurrency P1 | idempotency 구현 시 | 동일 key 재시도 | receiver 처리 1회 | execution count |
| T-E2E-01 | E2E | SRE, ANALYSIS, T1 | 생성→prepare→review→execute→result→audit | 전체 흐름 COMPLETED | demo capture |
| T-E2E-02 | E2E | SRE, REPORT, T2 | 생성→prepare→execute | approval 없이 private result | result/Audit |
| T-E2E-03 | E2E | Gold path 검사 | runtime image/volume/DB/API 검색 | Gold file/table/endpoint 없음 | CI check |
| T-EXP-01 | Experiment guard | frozen B3/P config | canonical config을 field별 diff | selectionRule 외 detector, Secret DROP, tokenizer, residual, model, prompt, decoding, network diff 0 | config diff report/hash |
| T-EXP-02 | Experiment guard | Week 11 이전 runner | Pilot/Dev/Locked 접근 log 검사 | Locked input/Gold read 0 | access log/CI report |
| T-EXP-03 | Experiment guard | Locked run report | 모든 18건 outcome·runner failure를 validator에 입력 | failure가 분모에서 제외되지 않고 joint fail | validation report |

## 실험 보호 테스트

T-EXP-01~03은 Week 2 동결과 Week 10 manifest 검토, Week 11 Locked report 검토에 각각 적용한다. parser/model/runner 실패는 locked 분모에서 사후 제외하지 않는다.

## 완료 기준

P0 release 전 T-UNIT, T-INT, T-SEC, T-NET-01~04, T-CON-01, T-E2E-01/02/03은 모두 통과해야 한다. T-CON-02는 P1 기능을 실제 구현한 경우에만 필수다.
