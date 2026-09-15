# 여과시간 기능 명세

## FUNC-01 인증과 Purpose 권한

- 목적: 승인된 역할만 Purpose를 사용하게 한다.
- Actor: 개발자/SRE, 보안 검토 담당자.
- 입력: access token, purposeCode.
- 정상 Flow: token 검증 → role 조회 → ACTIVE Purpose의 allowedRoles 확인 → 허용 목록 반환 또는 요청 생성 허용.
- 실패/예외: 미인증 401, 비허용 역할 403, DEPRECATED Purpose 409.
- 권한: SRE/PAYMENT_DEVELOPER는 두 Purpose 요청 가능, SECURITY_REVIEWER는 승인 가능, ADMIN은 seed 관리만 한다.
- 상태 변화: 없음 또는 RECEIVED→AUTHORIZED.
- 관련 API: API-01, API-03.
- Acceptance Criteria: 비허용 사용자는 Purpose 목록·요청 생성 모두 거부되고 Audit에 reasonCode가 남는다.

## FUNC-02 요청 생성

- 목적: 원문, Purpose, 등록 Destination을 immutable request로 기록한다.
- Actor: 개발자/SRE.
- 입력: input JSON, purposeCode, destinationId.
- 정상 Flow: schema type 확인 → rawHash 생성 → DataRequest 생성 → RECEIVED.
- 실패/예외: 임의 URL, 자유문장 Purpose, 지원하지 않는 inputType은 400; destination 미등록은 400.
- 권한: Purpose 권한 보유자.
- 상태 변화: 없음→RECEIVED.
- 관련 API: API-03.
- Acceptance Criteria: 원본 수정 API는 없고 수정하려면 새 requestId가 생성된다.

## FUNC-03 Classification

- 목적: 지원 JSON에서 PII, 계좌, 거래 ID, 내부 IP, Secret과 관계 후보를 찾는다.
- Actor: 시스템.
- 입력: rawObjectRef, inputSchema version.
- 정상 Flow: parse → detector finding 생성 → CLASSIFIED.
- 실패/예외: parse/detector 오류는 T1 fail-closed.
- 권한: 내부 시스템만 호출.
- 상태 변화: AUTHORIZED→CLASSIFIED.
- 관련 API: API-04.
- Acceptance Criteria: finding에는 jsonPath와 type만 저장하고 raw Secret을 Audit에 복사하지 않는다.

## FUNC-04 Purpose Template와 Package Builder

- 목적: Purpose 계약에 따라 필드·event·relation을 선택하고 변환 manifest와 패키지를 만든다.
- Actor: 시스템, 개발자/SRE는 결과 확인.
- 입력: findings, purposeId/version, caseId.
- 정상 Flow: field action 해석 → HMAC 사건 범위 token → derivedHash/manifest 생성 → TRANSFORMED.
- 실패/예외: 미지원 field/action, template version 없음은 DENY.
- 권한: ACTIVE template만 신규 요청에 사용.
- 상태 변화: CLASSIFIED→TRANSFORMED.
- 관련 API: API-04, API-05.
- Acceptance Criteria: 같은 caseId+정규화 값은 같은 token, 다른 caseId는 다른 token이고 두 Purpose의 차이는 계약에 있는 atom으로 제한된다.

## FUNC-05 Policy Decision과 Residual Scan

- 목적: user×purpose×dataClass×destinationTrust를 판정하고 변환 후 금지값 잔존을 막는다.
- Actor: 시스템.
- 입력: package, manifest, destinationId, policy version.
- 정상 Flow: policy explain 생성 → POLICY_DECIDED → residual scan → RESIDUAL_PASSED → READY/REVIEW/DENIED.
- 실패/예외: policy/scanner 오류 또는 Secret 잔존 시 T1 DENY; unknown은 REVIEW 또는 DENY.
- 권한: 내부 시스템만 호출.
- 상태 변화: TRANSFORMED→POLICY_DECIDED→RESIDUAL_PASSED→PRIVATE_READY/EXTERNAL_READY/REVIEW_PENDING/DENIED.
- 관련 API: API-04, API-05.
- Acceptance Criteria: T0는 내용과 무관하게 DENY이고 T1 수신자는 Secret-containing payload를 한 건도 받지 않는다.

## FUNC-06 승인과 무효화

- 목적: 검토 대상 반출을 현재 패키지 fingerprint에만 결속한다.
- Actor: 보안 검토 담당자.
- 입력: requestId, fingerprint, approve/reject.
- 정상 Flow: reviewer가 요청자와 다른지 확인 → fingerprint 일치 확인 → APPROVED 또는 REJECTED 기록.
- 실패/예외: self approval 403, stale fingerprint 409, DENIED request 승인 409.
- 권한: SECURITY_REVIEWER.
- 상태 변화: REVIEW_PENDING→EXTERNAL_READY 또는 DENIED.
- 관련 API: API-06.
- Acceptance Criteria: raw/derived hash, Purpose, Destination, Policy version 중 하나라도 바뀌면 승인 상태는 INVALIDATED이고 execute가 거부된다.

## FUNC-07 AI 실행과 결과

- 목적: 등록된 T2/T1 adapter만 호출하고 결과를 저장한다.
- Actor: 개발자/SRE.
- 입력: requestId.
- 정상 Flow: state·approval 재검사 → audit intent → EXECUTING → adapter 호출 → COMPLETED.
- 실패/예외: T1 전송 전 연결 실패는 FAILED; 전송 후 응답 유실은 P1 DELIVERY_UNKNOWN.
- 권한: request owner, 허용 상태만 실행.
- 상태 변화: PRIVATE_READY/EXTERNAL_READY→EXECUTING→COMPLETED/FAILED.
- 관련 API: API-07, API-08.
- Acceptance Criteria: 임의 URL 호출이 없고 T0 adapter는 존재하지 않는다.

## FUNC-08 Audit과 Explain

- 목적: 누가 어떤 목적·정책·패키지·목적지로 실행했는지 재현 가능한 기록을 남긴다.
- Actor: 모든 조회 권한 보유자.
- 입력: 상태 변화, reasonCode, version, hash, metadata.
- 정상 Flow: 각 상태 전이 전후 append-only AuditEvent 저장.
- 실패/예외: 외부 실행 전 audit intent 저장 실패 시 실행 중단.
- 권한: request owner와 SECURITY_REVIEWER 조회, ADMIN 전역 조회.
- 상태 변화: 없음.
- 관련 API: API-09.
- Acceptance Criteria: Audit에 raw payload/Secret은 없고 sequence가 request별로 중복되지 않는다.

## FUNC-09 네트워크 경계와 Destination Allowlist

- 목적: Internal Client의 직접 외부 접속을 막고 Dispatcher만 등록 T1을 호출하게 한다.
- Actor: 시스템, Network/Security 담당.
- 입력: destinationId, Docker network topology.
- 정상 Flow: client는 internal_net만 연결 → API/Dispatcher가 allowlist destination 해석 → egress 경유 호출.
- 실패/예외: 직접 curl은 연결 실패, spoofed URL은 API 400/403.
- 권한: 사용자에게 URL 입력 권한 없음.
- 상태 변화: 없음.
- 관련 API: API-02, API-03, API-07.
- Acceptance Criteria: Internal→T1/T0 성공 0, 여과시간→등록 T1 성공과 receiver log가 남는다.

## FUNC-10 Benchmark 실행

- 목적: 운영 runtime과 분리된 환경에서 A/B/C 실험을 재현한다.
- Actor: 평가 담당자/ADMIN.
- 입력: frozen config, conditionId, dataset split.
- 정상 Flow: Pilot/Dev로 tuning → artifact hash freeze → Locked 최초 실행 → blind scoring.
- 실패/예외: locked 실패는 제외하지 않고 joint fail; Gold가 runtime에 mount되면 실행 중단.
- 권한: benchmark 계정만 Gold 접근.
- 상태 변화: 운영 DataRequest 상태와 분리.
- 관련 API: 운영 API 없음; 별도 runner command.
- Acceptance Criteria: B3/P shared config hash가 일치하고 Gold 파일은 runtime image, volume, DB, API에 없다.

