# 여과시간 User Flow

## 정상 흐름: T1 승인 실행

~~~mermaid
flowchart TD
  A[로그 업로드] --> B[로그인·역할 확인]
  B --> C[승인된 Purpose 선택]
  C --> D[등록 Destination 선택]
  D --> E[Classification]
  E --> F[최소 업무 패키지 생성]
  F --> G[원본·패키지 비교]
  G --> H[Policy Decision]
  H --> I[Residual Scan]
  I --> J{결과}
  J -->|T2 READY| L[AI 실행]
  J -->|T1 REVIEW| K[보안 검토 승인]
  K --> L
  J -->|T0·실패 DENY| X[차단 사유·Audit]
  L --> M[AI 결과]
  M --> N[Audit·Explain]
~~~

1. 개발자/SRE는 SCR-01에서 합성 JSON 로그, Purpose, 등록 Destination을 선택한다.
2. 서버는 로그인 사용자 역할이 Purpose를 사용할 수 있는지 확인하고 요청을 RECEIVED에서 AUTHORIZED로 전이한다.
3. 사용자는 Prepare를 실행한다. Classification, Package Builder, Policy, Residual Scan이 순서대로 실행된다.
4. SCR-02에서 원본과 패키지, KEEP/DROP/TOKENIZE 이유, Trust 결과를 비교한다.
5. T2는 PRIVATE_READY가 되며 실행 가능하다. T1은 EXTERNAL_READY 또는 REVIEW_PENDING이 된다. REVIEW_PENDING은 보안 검토 담당자의 승인이 필요하다.
6. 실행 직전에도 fingerprint와 정책 version을 다시 확인한다. 통과 시 AI 결과와 Audit을 저장하고 SCR-04, SCR-05에서 확인한다.

## 주요 실패 흐름

| 흐름 ID | 조건 | 시스템 결과 | 사용자 다음 행동 |
| --- | --- | --- | --- |
| FL-01 | 로그인 역할이 Purpose에 없음 | 403, 요청 생성 안 함 | 허용 Purpose 선택 또는 권한 요청 |
| FL-02 | parser 또는 detector 실패 | T1은 REVIEW_PENDING 또는 DENIED, 외부 전송 0 | 입력 형식 수정 후 새 요청 생성 |
| FL-03 | Residual Scan에서 Secret 발견 | DENIED, 발견 위치와 reason 표시 | 원본 수정 또는 T2 내부 검토 |
| FL-04 | T0 목적지 선택 | DENIED, 수신자 호출 없음 | T1/T2 등록 목적지 선택 |
| FL-05 | 승인 거절 | DENIED 또는 REVIEW_PENDING 유지 | 사유 확인 후 새 패키지 생성 |
| FL-06 | 승인 뒤 hash·Purpose·Destination·Policy 변경 | APPROVAL_INVALIDATED, execute 409 | 새 패키지를 다시 검토·승인 |
| FL-07 | AI 연결 실패 | FAILED, audit 기록 | 새 Request에서 재실행; 자동 Trust fallback 없음 |
| FL-08 | 실행 중복 요청 | P0에서는 상태 잠금으로 409 | 실행 결과 조회; P1 idempotency 구현 시 동일 실행 반환 |

## 역할별 흐름

- 개발자/SRE: 요청 생성, 패키지 확인, 실행, 결과·감사 조회.
- 보안 검토 담당자: 자신이 만든 요청은 승인할 수 없고, REVIEW_PENDING 요청만 승인 또는 거절한다.
- 플랫폼 운영자: 등록 Destination·Purpose seed와 정책 version을 관리한다. MVP에는 편집 화면을 만들지 않는다.

## 상태와 화면 연결

| 상태 | 사용자에게 보이는 화면/행동 |
| --- | --- |
| RECEIVED, AUTHORIZED | SCR-01 입력 중 또는 요청 생성 완료 |
| CLASSIFIED, TRANSFORMED, POLICY_DECIDED | SCR-02 준비 중 |
| REVIEW_PENDING | SCR-03에서 검토 대기 |
| EXTERNAL_READY, PRIVATE_READY | SCR-03에서 실행 가능 |
| DENIED | SCR-03에 차단 사유와 Audit 링크 |
| EXECUTING | SCR-04 로딩, 중복 실행 비활성 |
| COMPLETED, FAILED | SCR-04 결과 또는 실패 사유 |

