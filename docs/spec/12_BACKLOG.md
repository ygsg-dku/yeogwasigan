# 여과시간 개발 Backlog

## 사용 규칙

- 1인일은 8시간이다. 최하위 Task는 4~16시간(0.5~2일)이다.
- S/M/L은 복잡도, 시간은 예상 투입이다.
- P0는 MVP와 Week 8 E2E에 필요하다. P1/P2는 P0 완료 뒤에만 시작한다.
- 각 Task의 Done Evidence는 PR, 테스트 결과, 캡처, hash, 문서 중 검토 가능한 증거다.

## EPIC-01 평가 계약과 요청 기반

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EPIC-01 | 평가 계약과 요청 기반 | Purpose·dataset contract와 로그인 요청의 출발점을 만든다. | 이후 모든 정책과 실험의 기준이다. | Pilot contract와 권한 요청이 재현된다. | 없음 | B 주, A/D 리뷰 | L | P0 | W1-3 | frozen contract, API tests |

### STORY-01-01 승인된 역할로 요청을 생성한다

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| STORY-01-01 | 권한 기반 Request 생성 | SRE가 승인 Purpose와 등록 Destination으로 immutable request를 만든다. | 수직 흐름의 시작이다. | 비허용 role/URL/Purpose는 거부되고 request는 RECEIVED다. | EPIC-01 | A 주, B 리뷰 | M | P0 | W3 | API-03 integration test |

| Task ID | 제목 | 설명·왜 필요한가 | Acceptance Criteria | Dependency | 역할/리뷰 | 크기·시간 | 우선순위 | 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TASK-01-01 | Purpose/Destination seed | 두 ACTIVE Purpose와 T2/T1/T0 등록 목적지를 seed로 등록한다. 서버 allowlist의 출발점이다. | role, trust, version이 fixture로 조회된다. | 없음 | B / D | S·4h | P0 | W1 | seed migration, API-01/02 test |
| TASK-01-02 | 역할 모델과 Security 설정 | SRE, PAYMENT_DEVELOPER, SECURITY_REVIEWER, ADMIN 권한을 설정한다. | 사용자와 reviewer 경계를 강제한다. | unauthenticated 401, forbidden 403. | TASK-01-01 | B / A | M·8h | P0 | W3 | security tests |
| TASK-01-03 | DataRequest migration | DataRequest와 Purpose snapshot, destination version, rowVersion을 만든다. | immutable 요청·상태 전이가 필요하다. | 필수 컬럼/제약과 createdAt 조회가 된다. | TASK-01-01 | D / A | M·8h | P0 | W3 | migration, repository test |
| TASK-01-04 | Request 생성 API | API-03 validation과 rawHash 생성을 구현한다. | 자유 URL·원본 수정 없는 요청 경계를 만든다. | 201 RECEIVED; URL/자유 Purpose 400. | TASK-01-02, TASK-01-03 | A / B | M·8h | P0 | W3 | controller/integration test |
| TASK-01-05 | Pilot Task Brief·fixture | 두 업무의 결과 설명과 Pilot 6 사건 fixture를 확정한다. | contract와 Gold가 같은 답을 복사하지 않게 한다. | raw incident와 Task Brief hash가 기록된다. | 없음 | D / C | M·8h | P0 | W1 | dataset manifest |
| TASK-01-06 | 현업 evidence-gap 인터뷰 | 개발·운영·보안 역할별 4개 가설 질문으로 익명 인터뷰와 반박 사례를 기록한다. | 문제 정의와 Purpose 운영 가능성을 구현 전에 검증해야 한다. | raw 개인식별정보 없이 memo, 반박 여부, Week 2 범위 수정 결정이 남는다. | 없음 | B/C / D | M·8h | P0 | W1-2 | interview memo, Gate record |

## EPIC-02 Classification과 최소 업무 패키지

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EPIC-02 | Purpose Package | finding과 Purpose 계약으로 결정적 최소 패키지를 생성한다. | 여과시간의 핵심 차별 기능이다. | 두 Purpose가 같은 원문에서 다른 contract-minimal package를 만든다. | EPIC-01 | B/D 주, A 리뷰 | L | P0 | W4 | golden package tests |

### STORY-02-01 Purpose별 패키지를 생성한다

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| STORY-02-01 | Classification과 변환 | 로그의 민감정보·관계를 찾고 계약 action을 적용한다. | 원문을 외부 AI에 보내지 않는 핵심이다. | manifest와 derivedHash가 생성되고 재실행 결과가 같다. | STORY-01-01 | B 주, D/A 리뷰 | L | P0 | W4 | FUNC-03/04 tests |

| Task ID | 제목 | 설명·왜 필요한가 | Acceptance Criteria | Dependency | 역할/리뷰 | 크기·시간 | 우선순위 | 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TASK-02-01 | Payment JSON parser | 지원 schema의 event, field, relation을 parse한다. | 지원하지 않는 입력을 안전하게 닫는다. | malformed JSON은 PARSE_FAILED다. | TASK-01-03 | D / B | M·8h | P0 | W4 | parser unit tests |
| TASK-02-02 | Finding entity/repository | detector type, jsonPath, severity를 저장한다. | 변환과 Audit에 원문 없는 근거를 준다. | request별 finding 조회가 된다. | TASK-01-03 | D / A | S·4h | P0 | W4 | migration/repository test |
| TASK-02-03 | Detector fixture | PII, account, Secret, internal IP, transaction fixture detector를 만든다. | 탐지 모델 개발 없이 실험 조건을 통제한다. | 각 canary가 expected jsonPath로 탐지된다. | TASK-02-01 | B / C | M·8h | P0 | W4 | detector test matrix |
| TASK-02-04 | HMAC token service | type normalization과 case-scoped HMAC token을 만든다. | 사건 안 관계를 보존하고 사건 간 연결을 줄인다. | same case same value equal; cross case unequal. | TASK-02-01 | B / D | M·8h | P0 | W4 | T-UNIT-01/02 |
| TASK-02-05 | Field action resolver | KEEP/DROP/TOKENIZE/PSEUDONYMIZE/GENERALIZE를 contract로 해석한다. | Purpose별 선택을 결정적으로 만든다. | 각 action fixture가 기대 JSON을 만든다. | TASK-01-01, TASK-02-03/04 | B / A | M·8h | P0 | W4 | resolver tests |
| TASK-02-06 | Package/manifest builder | selected field/event/relation으로 package, manifest, derivedHash를 만든다. | 비교·승인·Audit의 공통 산출물이다. | 동일 입력 rerun hash 동일, 두 Purpose 차이 확인. | TASK-02-05 | B / D | L·12h | P0 | W4 | golden package test |
| TASK-02-07 | Prepare API orchestration | parse→classify→transform까지 API-04에 연결한다. | UI와 후속 policy가 같은 흐름을 쓴다. | AUTHORIZED부터 TRANSFORMED까지 Audit 포함. | TASK-02-02/06 | A / B | M·8h | P0 | W4 | integration test |

## EPIC-03 Trust Policy, Residual Scan, Approval, Audit

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EPIC-03 | 안전한 실행 판정 | Trust ceiling, fail-closed, 승인, 상태, 감사를 하나의 실행 통제로 연결한다. | 안전한 패키지가 실제 반출 통제로 이어져야 한다. | T0/Secret/failure가 실행 전 차단되고 승인 변경이 무효화된다. | EPIC-02 | B/A 주, C/D 리뷰 | L | P0 | W5-7 | policy/approval/audit suite |

### STORY-03-01 정책과 승인으로 T1 반출을 통제한다

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| STORY-03-01 | Policy-approval workflow | policy 결과와 current fingerprint로 T1 실행을 허용한다. | 검토가 다른 데이터에 재사용되지 않게 한다. | self approval과 stale approval은 거부된다. | STORY-02-01 | B 주, A/C 리뷰 | L | P0 | W5-7 | T-SEC-01~05 |

| Task ID | 제목 | 설명·왜 필요한가 | Acceptance Criteria | Dependency | 역할/리뷰 | 크기·시간 | 우선순위 | 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TASK-03-01 | Policy fixture와 Trust evaluator | T2/T1/T0, role, data class의 결정표를 구현한다. | Trust가 공개량이 아닌 ceiling으로 작동해야 한다. | T0 DENY, T2/T1 expected decision fixture 통과. | TASK-02-06 | B / C | M·8h | P0 | W5 | policy unit tests |
| TASK-03-02 | Residual scanner | 패키지에서 Secret과 prohibited atom을 독립 규칙으로 재검사한다. | detector miss를 외부 전송 전에 막는다. | Secret 삽입 fixture는 DENIED다. | TASK-02-06 | B / C | M·8h | P0 | W5 | T-INT-02 |
| TASK-03-03 | State transition guard | allowed transition table와 rowVersion 경쟁 처리를 구현한다. | execute 등 잘못된 순서를 막는다. | invalid transition 409; concurrent execute one winner. | TASK-02-07 | A / B | M·8h | P0 | W5 | T-CON-01 |
| TASK-03-04 | Approval migration/service | reviewer, fingerprint, status, self approval 검사를 구현한다. | 검토 결정을 현재 패키지에 결속한다. | 요청자 승인 403, valid approval EXTERNAL_READY. | TASK-03-01/03 | B / A | M·8h | P0 | W7 | approval tests |
| TASK-03-05 | Invalidation hook | raw/derived/Purpose/Destination/Policy 변경 시 approval을 무효화한다. | 승인 재사용을 차단한다. | 5개 변경 parameterized test 통과. | TASK-03-04 | A / B | M·8h | P0 | W7 | T-SEC-03~05 |
| TASK-03-06 | Audit event writer | 상태·actor·reason·version·hash를 sequence로 기록한다. | Explain과 발표 evidence의 기반이다. | raw/Secret 없이 ordered event 생성. | TASK-03-03 | D / A | M·8h | P0 | W7 | audit repository test |

## EPIC-04 Network와 AI 실행

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EPIC-04 | 승인 경로 AI 실행 | Docker 경계와 T2/T1 adapter로 등록 경로만 실행한다. | UI 차단이 아닌 실제 경로 통제를 보여준다. | direct fail, 여과시간→T1 pass, T0 receiver 0. | EPIC-03 | C/A 주, B 리뷰 | L | P0 | W6-8 | compose, receiver logs |

### STORY-04-01 등록 목적지로만 AI를 실행한다

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| STORY-04-01 | Network-enforced execution | Internal Client는 직접 외부에 못 가고 Dispatcher만 등록 T1을 호출한다. | Network Isolation 요구사항을 만족한다. | 요청자 URL 입력이 없고 receiver evidence가 남는다. | STORY-03-01 | C 주, A/B 리뷰 | L | P0 | W6 | network tests |

| Task ID | 제목 | 설명·왜 필요한가 | Acceptance Criteria | Dependency | 역할/리뷰 | 크기·시간 | 우선순위 | 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TASK-04-01 | Compose network topology | internal/data/dispatch/egress network와 서비스 연결을 정의한다. | Internal Client와 egress를 분리한다. | client는 egress_net에 없고 API는 egress_net에 없다. | 없음 | C / A | M·8h | P0 | W6 | compose inspect output |
| TASK-04-02 | Controlled T1 receiver | 수신 payload hash·canary·횟수를 기록하는 mock을 만든다. | 전송 여부를 독립 증거로 남긴다. | health endpoint와 receipt log 제공. | TASK-04-01 | C / D | S·4h | P0 | W6 | receiver test/log |
| TASK-04-03 | T2/T1 adapter interface | T2 mock과 T1 Dispatcher 호출을 같은 interface로 만든다. | provider 차이를 workflow에서 제거한다. | T2/T1 fixture response 표준화. | TASK-03-03 | A / C | M·8h | P0 | W6 | adapter unit test |
| TASK-04-04 | Destination allowlist dispatcher | destinationId만 받아 endpoint/version을 서버 설정에서 해석한다. | SSRF·임의 URL을 막는다. | unknown/spoof destination 호출 0. | TASK-04-01/02 | C / B | M·8h | P0 | W6 | T-NET-03 |
| TASK-04-05 | Execute API | state, approval, audit intent를 재검사하고 adapter를 실행한다. | 정책이 실제 호출까지 유지된다. | only READY executes; failure is FAILED. | TASK-03-04/06, TASK-04-03/04 | A / B | L·12h | P0 | W7 | T-INT/T-E2E execution |
| TASK-04-06 | Direct path verification | internal-client에서 T1/T0 curl과 여과시간 경유 T1을 검사한다. | 발표 가능한 network evidence가 필요하다. | direct success 0; approved receiver 1. | TASK-04-01/05 | C / D | M·8h | P0 | W6-7 | command output/receiver log |
| TASK-04-07 | 등록 T1 외부 AI 연결 | controlled receiver 검증 뒤 서버 secret으로 한 개 allowlisted Enterprise AI adapter를 연결한다. | 수직 흐름과 B3/P 비교가 실제 AI 조건에서 실행돼야 한다. | 합성 package만 전송, response digest/Audit 저장, credential·raw payload log 0, T-NET-04 통과. | TASK-04-02~05 | A/C / B | M·8h | P0 | W6-7 | T-NET-04, AI result capture, Audit |

## EPIC-05 Workspace UI와 Week 8 E2E

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EPIC-05 | Workspace 수직 흐름 | 5개 화면으로 요청부터 Audit까지 사용자가 이해하게 만든다. | 핵심 기술이 실제 사용 흐름으로 보여야 한다. | Week 8 E2E가 화면에서 재현된다. | EPIC-01~04 | D/A 주, B/C 리뷰 | L | P0 | W3-8 | demo capture |

### STORY-05-01 개발자와 reviewer가 흐름을 완료한다

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| STORY-05-01 | 5-screen Workspace | request, package, policy/approval, result, audit 화면을 만든다. | Purpose 패키지와 통제 근거를 사람이 검토한다. | 권한별 버튼·로딩·오류·다음 화면이 명세와 일치한다. | STORY-01-01, STORY-02-01, STORY-03-01, STORY-04-01 | D 주, A/B/C 리뷰 | L | P0 | W3-8 | UI E2E |

| Task ID | 제목 | 설명·왜 필요한가 | Acceptance Criteria | Dependency | 역할/리뷰 | 크기·시간 | 우선순위 | 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TASK-05-01 | API client/types | API DTO와 error/status type을 만든다. | FE/BE 병렬 개발 기반이다. | API-01~09 type contract compile. | API contract W3 | D / A | S·4h | P0 | W3 | type check |
| TASK-05-02 | SCR-01 Request 화면 | Purpose/Destination/JSON 입력과 validation을 만든다. | 흐름 시작이다. | 미선택·권한 없음 Create 비활성. | TASK-05-01, API-03 | D / B | M·8h | P0 | W3-4 | UI test/capture |
| TASK-05-03 | SCR-02 Package diff | original/package/manifest/finding/policy 준비 상태를 표시한다. | 최소 공개를 보이게 한다. | KEEP/DROP/TOKENIZE reason 표시. | TASK-02-07, API-04/05 | D / B | M·8h | P0 | W4-5 | UI test/capture |
| TASK-05-04 | SCR-03 Policy/Approval | decision, fingerprint, reviewer actions, execute gating을 표시한다. | 반출 통제를 이해시킨다. | self approve 불가, stale approval 표시. | TASK-03-04/05, API-06/07 | D / B | M·8h | P0 | W7 | UI test |
| TASK-05-05 | SCR-04 Result 화면 | execution polling, result/failure, AI label을 만든다. | 실행 결과를 제공한다. | EXECUTING 중 중복 execute 금지. | TASK-04-05, API-08 | D / A | M·8h | P0 | W7-8 | UI E2E |
| TASK-05-06 | SCR-05 Audit 화면 | ordered timeline와 reason/version/hashes를 표시한다. | 판단을 설명한다. | raw Secret 없이 event order 표시. | TASK-03-06, API-09 | D / C | M·8h | P0 | W7-8 | UI capture |

## EPIC-06 Strong B3와 Locked 평가

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EPIC-06 | 공정한 차별성 평가 | B3/P 공통 조건·Gold 격리·A/B/C 실행을 재현한다. | Purpose 차별성을 심사에서 방어한다. | Locked 결과와 실패를 사전 규칙으로 보존한다. | EPIC-01~05 | A/C/D 주, B 리뷰 | L | P0 | W1-11 | locked manifest/report |

### STORY-06-01 B3와 여과시간을 공정하게 비교한다

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| STORY-06-01 | Frozen benchmark | B3와 P를 shared pipeline으로 실행하고 blind scoring한다. | 비교군을 일부러 약하게 했다는 공격을 막는다. | shared config hash와 condition-blind score가 있다. | STORY-02-01, STORY-04-01 | A 주, C/D/B 리뷰 | L | P0 | W1-11 | Experiment A/B/C report |

| Task ID | 제목 | 설명·왜 필요한가 | Acceptance Criteria | Dependency | 역할/리뷰 | 크기·시간 | 우선순위 | 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TASK-06-01 | Strong B3 selection rule | INCIDENT_SAFE_FIXED_V1 field/event/relation 규칙을 문서·fixture로 고정한다. | B3가 구조화된 강한 비교군이어야 한다. | B3는 공통 token/residual을 사용한다. | TASK-01-05 | A / B | M·8h | P0 | W1-2 | B3 spec/fixture |
| TASK-06-02 | Gold isolation layout | benchmark-only Gold 저장소·계정·mount 규칙을 만든다. | Gold runtime 접근을 막는다. | runtime image/DB/API search에서 Gold 0. | TASK-01-05 | C / D | M·8h | P0 | W2 | access test |
| TASK-06-03 | Dataset split/manifest | Pilot 6, Dev 10, Locked 18 seed, provenance, hashes를 만든다. | tuning leakage를 방지한다. | split overlap 0, hashes recorded. | TASK-01-05 | D / C | M·8h | P0 | W1-2 | dataset manifest |
| TASK-06-04 | Pilot runner | frozen selection fixture를 쓰는 독립 reference runner로 B3/P Pilot을 실행한다. | Week 2 early gate는 runtime Package Builder보다 먼저 가설·계약을 점검해야 한다. | per-incident disclosure/utility output과 fixture hash를 남긴다. | TASK-06-01, TASK-06-03 | A / D | M·8h | P0 | W2 | pilot report |
| TASK-06-05 | Scoring rubric/validator | privacy·utility·joint pass와 failure-as-fail을 계산한다. | 사후 기준 변경을 막는다. | 18건 분모, acceptance rule test. | TASK-06-03 | A / C | M·8h | P0 | W2 | rubric hash/tests |
| TASK-06-06 | Frozen config manifest | model/prompt/decoder, detector, tokenizer, residual, network, B3/P hash를 기록한다. | 조건 공통성을 증명한다. | selectionRule 외 diff가 없음을 검사. | TASK-04-05, TASK-06-05 | A / B | M·8h | P0 | W10 | locked_manifest |
| TASK-06-07 | Locked run/blind report | Locked 최초 실행, blind score, raw output, failure, paired table을 보존한다. | 검증을 재현 가능하게 만든다. | acceptance/failure report 수정 없이 보존. | TASK-06-06 | C / A/D/B | L·12h | P0 | W11 | raw report, score sheet |

## EPIC-07 선택 기능

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EPIC-07 | P1/P2 운영성 | P0 완료 뒤 중복 실행·관측을 보강한다. | 핵심 가설을 지연시키지 않는 범위의 품질 향상이다. | P0 DoD 통과 후에만 착수한다. | EPIC-01~06 | A/C 주, D/B 리뷰 | M | P1/P2 | W9-13 | optional tests |

### STORY-07-01 실행 중복과 관측을 보강한다

| ID | 제목 | 설명 | 왜 필요한가 | Acceptance Criteria | Dependency | 담당 역할 | S/M/L | 우선순위 | 목표 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| STORY-07-01 | Idempotency와 Monitoring | dispatch 이후 오류의 의미와 단계별 측정을 보강한다. | 운영 이해를 높이지만 MVP 핵심은 아니다. | P0 E2E 이후 독립적으로 적용된다. | EPIC-07 | A/C 주, B/D 리뷰 | M | P1 | W9-13 | optional report |

| Task ID | 제목 | 설명·왜 필요한가 | Acceptance Criteria | Dependency | 역할/리뷰 | 크기·시간 | 우선순위 | 주차 | Done Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TASK-07-01 | Execution idempotency key | requestId+approvedFingerprint unique key를 추가한다. | dispatch 전 중복 호출을 억제한다. | concurrent same key receiver 1회. | P0 E2E | A / D | M·8h | P1 | W9 | T-CON-02 |
| TASK-07-02 | DELIVERY_UNKNOWN | 전송 후 응답 유실을 별도 상태로 기록한다. | exactly-once를 과장하지 않는다. | 자동 재전송 없이 Audit reason 저장. | TASK-07-01 | A / B | M·8h | P1 | W9 | failure test |
| TASK-07-03 | Micrometer metrics | classification/policy/residual/adapter latency와 failure count를 낸다. | 성능 결과를 실제 측정으로 제한한다. | metric endpoint에서 count/latency 확인. | P0 E2E | C / A | S·4h | P2 | W12 | metric capture |
| TASK-07-04 | tcpdump/nftables evidence | Linux VM에서 connection tuple 차단을 보강한다. | Q&A용 network 증거를 강화한다. | direct SYN/connection failure와 allowed path capture. | TASK-04-06 | C / D | M·8h | P2 | W12 | capture/runbook |

## P0 MVP 검증

P0 기능 수직 경로는 TASK-01-01~05 → TASK-02-01~07 → TASK-03-01~06 → TASK-04-01~07 → TASK-05-01~06 → TASK-06-01~07이다. TASK-01-06은 이 경로와 독립적인 Week 2 현업 evidence gate다. 두 흐름 모두 P1/P2 의존성이 없다.

Week 8 E2E에는 TASK-04-07과 TASK-05-06이 모두 필요하며, Locked benchmark는 Week 11 P0 증거로 별도 이어진다. P0 최하위 Task 합계는 308시간이고 P1/P2는 28시간이다. 전체 336시간은 390시간 계획 상한 아래이며, 최소 130시간의 리뷰·통합·버그 대응 buffer를 남긴다.
