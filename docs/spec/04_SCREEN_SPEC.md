# 여과시간 화면 명세와 Low-fi Wireframe

## SCR-01 Request 생성

- 목적: 승인된 Purpose와 Destination으로 JSON 요청을 생성한다.
- 진입조건: 로그인 완료.
- UI 요소: 로그 입력/fixture 선택, Purpose 카드, Destination 카드, 역할 표시, Create 버튼.
- Action/API: Purpose 조회(API-01), Destination 조회(API-02), 요청 생성(API-03).
- 성공/실패: 성공 시 SCR-02로 이동; 권한·입력 오류는 필드 옆 reasonCode 표시.
- 다음 화면: SCR-02.

~~~
+--------------------------------------------------------------+
| 여과시간 / Request                              SRE: kim      |
| Purpose [INCIDENT_ANALYSIS v1 ▾]  Destination [T1-ENT ▾]     |
| ------------------------------------------------------------ |
| PAYMENT_INCIDENT_JSON                                        |
| [ fixture 선택 ]  [ JSON 붙여넣기                         ]  |
|                                                              |
|           [ 요청 생성 ]   URL 직접 입력은 제공하지 않음      |
+--------------------------------------------------------------+
~~~

Create는 JSON 비어 있음, Purpose 미선택, Destination 미선택, 권한 없음일 때 비활성이다. 로딩 중 중복 제출을 막는다.

## SCR-02 Package Preview

- 목적: 원본과 최소 패키지의 차이와 변환 근거를 확인한다.
- 진입조건: API-04 prepare 완료 또는 재조회.
- UI 요소: 상태 chip, 원본, package, manifest action legend, findings 요약, Policy Explain, Prepare/다시 생성 버튼.
- Action/API: Prepare(API-04), request 조회(API-05).
- 성공/실패: 준비 완료면 SCR-03으로 진행; scanner 실패·DENY는 차단 사유와 SCR-05 링크 표시.
- 다음 화면: SCR-03 또는 SCR-05.

~~~
+---------------- Original ----------------+---------------- Package -----------------+
| customer_name: Han                       | service: payment-api                    |
| account: 123-...                         | error_code: DB_TIMEOUT                  |
| tx: TX-829                               | tx: tok_R7... [TOKENIZE]                |
| secret: sk_live...                       | event_order: kept [KEEP]                |
|                                           | secret: removed [DROP]                  |
+-------------------------------------------+------------------------------------------+
| Findings 5 | Policy evaluating... | [Prepare] [Policy/Approval로]             |
+-------------------------------------------------------------------------+
~~~

원본은 요청 소유자와 검토자만 본다. package가 없으면 diff 대신 준비 상태와 재시도 조건을 표시한다.

## SCR-03 Policy / Approval

- 목적: T2/T1/T0 판정과 승인 필요 여부를 명확히 보여준다.
- 진입조건: API-04 완료.
- UI 요소: selected Purpose/Destination, Trust Tier, allowed ceiling, decision, residual 결과, fingerprint, 승인/거절/실행 버튼.
- Action/API: 승인(API-06), 실행(API-07), request 조회(API-05).
- 성공/실패: T2/T1 ready는 Execute 활성화, REVIEW_PENDING은 reviewer만 승인 가능, T0·DENIED는 실행 비활성.
- 다음 화면: SCR-04 또는 SCR-05.

~~~
+---------------------------------------------------------------+
| Policy Decision: REVIEW_PENDING  | T1 Approved Managed External|
| Purpose: INCIDENT_ANALYSIS v1    | Residual: PASS               |
| Fingerprint: 7b3e...             | Reviewer: not assigned       |
| [Approve] [Reject]  [Execute disabled until approved]         |
| Reason: External transfer requires independent review         |
+---------------------------------------------------------------+
~~~

요청자는 Approve 버튼을 볼 수 없고, fingerprint가 바뀌면 승인 정보와 Execute가 즉시 무효 상태로 바뀐다.

## SCR-04 AI Result

- 목적: 실행 상태, AI 결과, 사용된 package 버전과 다음 행동을 제공한다.
- 진입조건: EXECUTING, COMPLETED 또는 FAILED.
- UI 요소: execution status, destination, model label, result, error reason, Audit 링크.
- Action/API: 실행(API-07), 결과(API-08).
- 성공/실패: COMPLETED 결과 표시; FAILED는 재시도 대신 새 Request 생성 또는 Audit 확인 유도.
- 다음 화면: SCR-05.

~~~
+---------------------------------------------------------------+
| Execution COMPLETED | T1-ENT | package 4c2...                 |
| Root cause: connection pool exhaustion                         |
| Evidence: event 17 → event 21, DB_TIMEOUT                      |
| [Audit / Explain 보기]                                         |
+---------------------------------------------------------------+
~~~

EXECUTING은 polling으로 갱신하며 Execute 버튼은 비활성이다. actual AI 결과와 controlled fixture 결과는 label로 구분한다.

## SCR-05 Audit / Explain

- 목적: 판단과 반출의 증거를 시간순으로 보여준다.
- 진입조건: requestId 접근 권한.
- UI 요소: timeline, actor, state, reasonCode, hash/version, receiver summary, filter.
- Action/API: audit 조회(API-09), request 조회(API-05).
- 성공/실패: event가 없으면 빈 상태; 권한 없으면 403.
- 다음 화면: SCR-02 또는 SCR-04의 해당 상태 링크.

~~~
+---------------------------------------------------------------+
| Audit / Explain                                                |
| 10:01 AUTHORIZED     role=SRE                                  |
| 10:02 TRANSFORMED    purpose=v1 derivedHash=4c2...             |
| 10:03 REVIEW_PENDING T1 requires approval                      |
| 10:05 APPROVED       reviewer=lee fingerprint=7b3...           |
| 10:06 COMPLETED      destination=T1-ENT responseRef=...        |
+---------------------------------------------------------------+
~~~

