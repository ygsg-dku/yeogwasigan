# 여과시간 API 명세

공통: 인증은 Bearer token을 사용한다. 모든 응답은 requestId와 traceId를 포함한다. API는 purposeCode와 destinationId만 받고 자유문장 Purpose나 URL을 받지 않는다.

| ID | Method / URL | 권한 | Request | 성공 Response / 상태 | 오류 | Idempotency |
| --- | --- | --- | --- | --- | --- | --- |
| API-01 | GET /api/purposes | 로그인 | 없음 | ACTIVE·역할 허용 Purpose 목록 | 401 | 해당 없음 |
| API-02 | GET /api/destinations | 로그인 | 없음 | 등록 T2/T1 목록과 Trust, version | 401 | 해당 없음 |
| API-03 | POST /api/requests | Purpose 권한 | input, purposeCode, destinationId | 201, requestId, RECEIVED | 400 INPUT_INVALID/DESTINATION_INVALID, 403 PURPOSE_FORBIDDEN | clientRequestId P0 권장 |
| API-04 | POST /api/requests/{id}/prepare | 요청 소유자 | 없음 | 200, state, package summary, decision | 403, 409 STATE_TRANSITION_INVALID, 422 PARSE_FAILED | 해당 없음 |
| API-05 | GET /api/requests/{id} | 소유자·reviewer | 없음 | request, findings, package, explain, approval | 403, 404 | 해당 없음 |
| API-06 | POST /api/requests/{id}/approvals | SECURITY_REVIEWER | fingerprint, decision, comment | 201, EXTERNAL_READY/DENIED | 403 SELF_APPROVAL_FORBIDDEN, 409 APPROVAL_STALE | 해당 없음 |
| API-07 | POST /api/requests/{id}/execute | 요청 소유자 | 없음 | 202, EXECUTING, executionId | 409 APPROVAL_INVALIDATED/STATE_TRANSITION_INVALID | P0 상태 잠금, P1 key |
| API-08 | GET /api/requests/{id}/result | 소유자·reviewer | 없음 | execution status, result/ref, failure reason | 404, 409 RESULT_NOT_READY | 해당 없음 |
| API-09 | GET /api/requests/{id}/audit | 소유자·reviewer·ADMIN | cursor | ordered events | 403, 404 | 해당 없음 |
| API-10 | GET /api/reviews | SECURITY_REVIEWER | state, cursor | REVIEW_PENDING 목록 | 403 | 해당 없음 |

## 핵심 예시

### API-03 요청 생성

~~~json
{
  "purposeCode": "INCIDENT_ANALYSIS",
  "destinationId": "t1-enterprise-v1",
  "input": {
    "schema": "PAYMENT_INCIDENT_JSON",
    "caseId": "inc-2026-001",
    "events": []
  }
}
~~~

~~~json
{
  "requestId": "req_123",
  "state": "RECEIVED",
  "purpose": {"code": "INCIDENT_ANALYSIS", "version": 1},
  "destination": {"id": "t1-enterprise-v1", "trust": "T1", "version": 1}
}
~~~

### API-04 Prepare 결과

~~~json
{
  "requestId": "req_123",
  "state": "REVIEW_PENDING",
  "derivedHash": "sha256:...",
  "decision": "HUMAN_REVIEW",
  "residualScan": {"status": "PASS", "secretCount": 0},
  "explain": ["T1 destination requires independent approval"],
  "package": {"service": "payment-api", "transaction": "tok_..."}
}
~~~

### API-06 승인

~~~json
{"fingerprint": "sha256:...", "decision": "APPROVE", "comment": "T1 transfer allowed"}
~~~

성공 시 201과 EXTERNAL_READY를 반환한다. 요청자와 reviewer가 같으면 403 SELF_APPROVAL_FORBIDDEN이다.

### 오류 형식

~~~json
{
  "code": "APPROVAL_INVALIDATED",
  "message": "Current package or policy differs from approved fingerprint",
  "requestId": "req_123",
  "traceId": "trace_456"
}
~~~

Error Code: INPUT_INVALID, PURPOSE_FORBIDDEN, DESTINATION_INVALID, PARSE_FAILED, DETECTOR_FAILED, RESIDUAL_SECRET_FOUND, POLICY_DENIED, STATE_TRANSITION_INVALID, APPROVAL_STALE, APPROVAL_INVALIDATED, SELF_APPROVAL_FORBIDDEN, EXECUTION_FAILED, RESULT_NOT_READY.

Benchmark 실행은 API가 아니다. Gold가 있는 별도 benchmark runner가 frozen config만 받아 실행한다.

