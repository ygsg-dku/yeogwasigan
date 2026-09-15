# State Machine and ERD

## Request 상태 전이

~~~mermaid
stateDiagram-v2
  [*] --> RECEIVED
  RECEIVED --> AUTHORIZED: role + input accepted
  AUTHORIZED --> CLASSIFIED: prepare
  CLASSIFIED --> TRANSFORMED: package built
  TRANSFORMED --> POLICY_DECIDED: policy evaluated
  POLICY_DECIDED --> RESIDUAL_PASSED: residual pass
  POLICY_DECIDED --> REVIEW_PENDING: unknown/failure requires review
  POLICY_DECIDED --> DENIED: T0 or policy deny
  RESIDUAL_PASSED --> PRIVATE_READY: T2
  RESIDUAL_PASSED --> EXTERNAL_READY: T1 no approval
  RESIDUAL_PASSED --> REVIEW_PENDING: T1 approval required
  RESIDUAL_PASSED --> DENIED: secret/finding
  REVIEW_PENDING --> EXTERNAL_READY: approval valid
  REVIEW_PENDING --> DENIED: rejected
  PRIVATE_READY --> EXECUTING: execute
  EXTERNAL_READY --> EXECUTING: execute
  EXECUTING --> COMPLETED: response stored
  EXECUTING --> FAILED: pre-send/connect/provider failure
  EXECUTING --> DELIVERY_UNKNOWN: P1 response lost after send
~~~

### API별 허용 전이

| API | 시작 상태 | 종료 상태 |
| --- | --- | --- |
| API-03 create | 없음 | RECEIVED |
| API-04 prepare | RECEIVED | AUTHORIZED→CLASSIFIED→TRANSFORMED→POLICY_DECIDED→결정 상태 |
| API-06 approval | REVIEW_PENDING | EXTERNAL_READY 또는 DENIED |
| API-07 execute | PRIVATE_READY, EXTERNAL_READY | EXECUTING→COMPLETED/FAILED |

허용되지 않은 전이는 409 STATE_TRANSITION_INVALID다. 예: DENIED에서 execute, REVIEW_PENDING에서 요청자가 approve, EXECUTING에서 재실행, COMPLETED에서 prepare는 금지한다. 원본 변경은 update 전이가 아니라 새 DataRequest 생성이다.

## Approval Invalidation

Approval은 다음 값을 결속한다.

~~~text
rawHash | derivedHash | purposeCode/version |
destinationId/destinationVersion | policyVersion
~~~

Execute 시점에 현재 fingerprint가 Approval과 다르면 Approval.status를 INVALIDATED로 바꾸고 409 APPROVAL_INVALIDATED를 반환한다. 새 ACTIVE Purpose version은 기존 Request의 snapshot을 바꾸지 않지만, 정책상 해당 version이 REVOKED이면 실행을 DENY한다.

## ERD

~~~mermaid
erDiagram
  USER ||--o{ DATA_REQUEST : creates
  PURPOSE_TEMPLATE ||--o{ DATA_REQUEST : selected_as_snapshot
  PURPOSE_TEMPLATE ||--o{ POLICY : constrains
  DATA_REQUEST ||--o{ FINDING : has
  DATA_REQUEST ||--|| TRANSFORMATION : active_package
  DATA_REQUEST ||--o{ APPROVAL : receives
  USER ||--o{ APPROVAL : approves
  DATA_REQUEST ||--o{ AI_EXECUTION : executes
  DATA_REQUEST ||--o{ AUDIT_EVENT : records

  USER {
    uuid id PK
    string username UK
    string role
    boolean active
  }
  PURPOSE_TEMPLATE {
    uuid id PK
    string purpose_code
    int version
    string status
    jsonb field_rules
    jsonb relation_rules
    string evaluation_profile
  }
  POLICY {
    uuid id PK
    int version
    uuid purpose_template_id FK
    string destination_trust
    string decision
  }
  DATA_REQUEST {
    uuid id PK
    uuid user_id FK
    uuid purpose_template_id FK
    string raw_hash
    string destination_id
    int destination_version
    string state
    int policy_version
    int row_version
  }
  FINDING {
    uuid id PK
    uuid request_id FK
    string detector
    string type
    string json_path
    string severity
  }
  TRANSFORMATION {
    uuid id PK
    uuid request_id FK
    string derived_hash
    jsonb manifest_json
    string derived_object_ref
  }
  APPROVAL {
    uuid id PK
    uuid request_id FK
    uuid approver_id FK
    string fingerprint
    string status
    timestamp approved_at
  }
  AI_EXECUTION {
    uuid id PK
    uuid request_id FK
    string destination_id
    string request_hash
    string status
    string response_ref
  }
  AUDIT_EVENT {
    uuid id PK
    uuid request_id FK
    int sequence
    string event_type
    string reason_code
    jsonb metadata
    timestamp created_at
  }
~~~

## 제약과 인덱스

| Entity | 제약 | 필요한 조회/인덱스 |
| --- | --- | --- |
| User | username unique, active required | username login lookup |
| PurposeTemplate | (purpose_code, version) unique, ACTIVE 신규 요청만 허용 | purpose_code + status |
| Policy | (purpose_template_id, version, destination_trust) unique | purpose/version/trust 판정 |
| DataRequest | rawHash, destinationId, state, policyVersion, optimistic rowVersion | user_id+createdAt, state+createdAt |
| Finding | requestId, jsonPath, type 중복 방지 | request_id |
| Transformation | requestId당 active transformation 1개 | request_id unique |
| Approval | fingerprint와 requestId 결속; approverId ≠ request.userId | request_id+status |
| AIExecution | P1 구현 시 requestId+fingerprint unique | request_id |
| AuditEvent | (request_id, sequence) unique, append-only | request_id+sequence |

Audit에는 raw payload, Secret, Gold annotation을 저장하지 않는다. state 변경은 DataRequest.rowVersion으로 낙관 잠금을 적용해 P0의 동시 execute 경쟁을 줄인다.

