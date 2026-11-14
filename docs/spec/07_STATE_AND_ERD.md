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


---

## 구현 현황 (2026-11-14 기준)

위 상태 전이와 ERD는 처음 계획한 전체 설계다. 현재 구현은 이보다 작다. 아래는 코드(`GatewayRequest.java`, `AuditEvent.java`)에서 확인한 현재 상태다.

### 구현 ERD

실제 DB 테이블은 2개다. 스키마는 JPA(`ddl-auto: update`)가 만든다. 두 테이블은 외래키 제약이 아니라 `requestId` 문자열(인덱스)로 연결된다.

~~~mermaid
erDiagram
  gateway_request ||--o{ audit_event : "requestId (논리 연결, FK 제약 없음)"

  gateway_request {
    string id PK "REQ-0001 형식"
    string requester
    string purpose
    string endpointId
    string status "PREPARED 등 Enum"
    boolean approvalRequired
    string inputSha256 "원문 해시만 저장"
    int inputRecords
    string listVersion
    text payloadJson "보관 기간 후 삭제"
    string packageSha256
    int fieldsBefore
    int fieldsAfter
    text droppedFieldsJson
    string warningsJson "길이 1000, 종류별 건수"
    instant createdAt
    instant payloadExpiresAt
    string approver
    text comment "승인 메모, 실패 사유로 덮어써짐"
    string fingerprint
    text answer "AI 응답"
    string answerMode
    string model
  }

  audit_event {
    long id PK "자동 증가"
    string requestId "인덱스만, FK 아님"
    instant at
    string actor
    string type "CREATED, APPROVED, SENT 등"
    string detail "길이 4000"
  }
~~~

### 설계와 구현의 차이

| 설계 | 구현 |
| --- | --- |
| 엔티티 9개 (USER, POLICY, FINDING, APPROVAL 등) | 테이블 2개. 나머지는 `gateway_request` 컬럼으로 흡수 |
| PK가 uuid | 요청 `id`는 `REQ-0001` 문자열, 감사 `id`는 Long |
| `audit_event.request_id`가 FK | 문자열 + 인덱스 (FK 제약 없음) |
| `audit_event (request_id, sequence)` unique | `sequence` 컬럼 없음. `id` 순서로 정렬 |
| `reason_code`, `metadata(jsonb)` | `detail` 문자열 하나 (최대 4000자) |
| `DATA_REQUEST.row_version` 낙관적 잠금 | 없음. 서비스 메서드 `synchronized` (단일 서버 전제) |
| FINDING의 `json_path` | 없음. `warningsJson`에 종류별 건수만 |
| `Approval.INVALIDATED` 상태 | 없음. 지문이 다르면 `FINGERPRINT_MISMATCH` 기록 후 `SEND_FAILED` |
| USER, PURPOSE_TEMPLATE, POLICY | DB에 없음. 설정 파일의 계정·역할, 목적별 필드 목록 yaml. `listVersion` 문자열만 저장 |

### 설계 엔티티와 구현 컬럼의 대응

| 설계의 엔티티 | 구현 |
| --- | --- |
| DATA_REQUEST | `gateway_request` |
| AUDIT_EVENT | `audit_event` (추가만 가능, 수정·삭제 API 없음) |
| APPROVAL | `approver`, `fingerprint`, `comment` |
| TRANSFORMATION | `payloadJson`, `packageSha256`, `fieldsBefore`, `fieldsAfter`, `droppedFieldsJson` |
| AI_EXECUTION | `answer`, `answerMode`, `model` |
| FINDING | `warningsJson` (종류별 건수만) |

### 상태

| 설계 | 구현 |
| --- | --- |
| REVIEW_PENDING | `PREPARED` (승인 대기) |
| EXTERNAL_READY | `APPROVED` |
| COMPLETED | `SENT` |
| DENIED | `REJECTED` |
| FAILED | `SEND_FAILED` |
| RECEIVED ~ POLICY_DECIDED, EXECUTING, DELIVERY_UNKNOWN | 별도 상태로 저장하지 않음. 요청 생성(`create`) 안에서 한 번에 처리 |

`approvalRequired`(boolean)는 요청 생성 시점에 저장된다. 조건부 모드에서 높음 경고가 0건이면 `false`이고, 요청자가 승인 없이 전송한다.

### 감사 기록 종류 (`audit_event.type`)

| 종류 | 내용 |
| --- | --- |
| CREATED | 레코드·필드 수, 대상, 목록 버전, 높음 경고 수, 승인 필요 여부 |
| APPROVED | 승인 지문 앞 12자, 메모, 대기 시간 |
| APPROVAL_SKIPPED | 조건부 모드, 높음 경고 없음, 승인 없이 전송 |
| REJECTED | 반려 사유, 대기 시간 |
| SENT | 대상·모델·방식, 프롬프트 글자 수, 보낸 내용 sha256 |
| SEND_FAILED | AI 호출 실패 사유 |
| WARNING_RECHECK_FAILED | 전송 직전 재검사에서 높음 경고가 나와 보내지 않음 |
| FINGERPRINT_MISMATCH | 승인 뒤 파생본·목록·전송 대상이 바뀌어 보내지 않음 |
| ENDPOINT_REJECTED | 등록되지 않은 전송 대상이라 보내지 않음 |
| PAYLOAD_PURGED | 보관 기간(30일) 경과로 파생본 삭제 |

`SEND_FAILED`, `WARNING_RECHECK_FAILED`, `FINGERPRINT_MISMATCH`, `ENDPOINT_REJECTED`는 모두 요청 상태를 `SEND_FAILED`로 바꾼다. 실패 이유는 `gateway_request.comment`에도 쓰이므로 승인 메모는 `comment`에서 덮어써지고, 원래 메모는 `APPROVED` 감사 기록에 남는다.

### 저장과 삭제

- 원문 로그는 DB에 저장하지 않는다. 입력 해시(`inputSha256`)와 레코드 수(`inputRecords`)만 남는다. (`RawLogNotStoredTest`)
- 파생본(`payloadJson`)은 `payloadExpiresAt`(생성 후 30일)이 지나면 매일 03:00 스케줄이 지우고, 해시와 감사 기록은 남는다.
- AI 응답(`answer`)과 승인 메모(`comment`)는 저장된다.

### 알려진 한계

- 요청 상태와 감사 기록이 한 트랜잭션이 아니었다(둘 사이에 실패하면 요청은 있는데 감사 기록이 없는 상태 가능). 한 묶음으로 저장하는 수정은 `fix/atomic-state-and-audit` 브랜치에 있다(`GatewayAtomicSaveTest`). 머지되면 이 항목을 해결됨으로 고친다.
- 요청 번호 생성은 `synchronized`로 단일 서버에서만 안전하다.
- 서버가 03:00에 꺼져 있으면 그날의 삭제는 다음 03:00까지 밀린다.
- `audit_event.requestId`는 FK가 아니어서 DB가 연결을 강제하지 않는다.
