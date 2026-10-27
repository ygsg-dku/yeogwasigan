# 16. Policy Evaluator — 대상 등급별 허용/거부 규칙

> 작성 2026-10-27 · 담당 서준영(B) · 구현 `experiments/yeogwasigan-spring` 의 `policy/PolicyEvaluator.java`
> 근거: 정책 명세 POLICY-03(Trust Tier), POLICY-04(평가 순서), TASK-03-01, T-UNIT-04
> 규칙 버전: `policy-v1` (규칙을 바꾸면 버전을 올린다. 승인 지문에 들어갈 값이다)

## 1. 하는 일

요청의 **전송 대상 등급(T2/T1/T0)** 과 앞 단계 검사 결과(Residual Scan, 파서·탐지기, 높음 경고, 승인 모드)를 받아 다음 상태 하나와 사유 코드 하나를 정한다.

| 결과 | 뜻 |
| --- | --- |
| `PRIVATE_READY` | T2 로 바로 실행 가능 |
| `EXTERNAL_READY` | T1 로 승인 없이 실행 가능 |
| `REVIEW_PENDING` | T1 이지만 다른 사람의 승인이 필요 |
| `DENIED` | 보내지 않음 |

원문과 파생본 값은 입력으로 받지 않는다. 결과에는 사유 코드만 남아 감사 기록·화면에 값이 새지 않는다.

## 2. 등급

| 등급 | 대상 | 정하는 방법 |
| --- | --- | --- |
| T2 Organization Controlled | 조직 내부에 등록한 AI | 등록부에 T2 로 적힌 대상 ID |
| T1 Approved Managed External | 승인한 외부 AI (allowlist) | 등록부에 T1 로 적힌 대상 ID |
| T0 Unmanaged | 미등록 주소, 개인 계정, 임의 URL | **등록부에 없는 모든 ID** (빈 값, 대소문자·공백만 다른 ID 포함) |

- 등급은 요청에서 받지 않고 **등록부에서만** 읽는다. 요청자가 "이 대상은 T1" 이라고 우겨도 소용없다.
- 등록부에 T0 를 적으면 시작부터 실패한다. T0 는 "등록 안 됨"의 다른 이름이기 때문이다.

## 3. 허용/거부 규칙표

위에서부터 차례로 보고 **처음 걸린 줄**이 결과다. 아래 줄은 위 줄의 거부를 풀 수 없다 (POLICY-04 우선순위: 비밀값 > T0 > 역할·목적 > 검사 실패 > 승인 요구).

| 순서 | 조건 | T2 | T1 | T0 | 사유 코드 |
| ---: | --- | --- | --- | --- | --- |
| 0 | 입력이 없거나 평가 중 예외 | DENIED | DENIED | DENIED | `POLICY_FAILED` |
| 1 | Residual Scan 이 비밀값을 찾음 | DENIED | DENIED | DENIED | `RESIDUAL_SECRET_FOUND` |
| 1 | Residual Scan 이 금지 atom 을 찾음 | DENIED | DENIED | DENIED | `RESIDUAL_PROHIBITED_ATOM_FOUND` |
| 1 | Residual Scan 자체가 실패 | DENIED | DENIED | DENIED | `RESIDUAL_SCAN_FAILED` |
| 2 | 대상이 T0 | — | — | **DENIED** (항상) | `T0_DESTINATION` |
| 3 | 요청자 역할이 이 목적에 허용 안 됨 | DENIED | DENIED | — | `ROLE_NOT_ALLOWED` |
| 3 | 목적 템플릿이 ACTIVE 가 아님 | DENIED | DENIED | — | `PURPOSE_NOT_ACTIVE` |
| 4 | 파서·탐지기 실패 | DENIED | DENIED | — | `PIPELINE_FAILED` |
| 5 | 여기까지 통과한 T2 | **PRIVATE_READY** | — | — | `T2_READY` |
| 6 | 분류 못 한 칸이 있음 | — | REVIEW_PENDING | — | `T1_CLASSIFICATION_UNKNOWN` |
| 6 | 높음 경고 1건 이상 | — | REVIEW_PENDING | — | `T1_HIGH_WARNING` |
| 6 | 승인 모드 ALWAYS | — | REVIEW_PENDING | — | `T1_APPROVAL_ALWAYS` |
| 7 | 여기까지 통과한 T1 | — | **EXTERNAL_READY** | — | `T1_READY` |

한 줄 요약:

- **T0**: 무조건 거부.
- **T1**: 비밀값·검사 실패·역할·목적 중 하나라도 걸리면 거부. 통과해도 분류 불명·높음 경고·ALWAYS 모드면 승인 대기. 셋 다 아니면 바로 실행.
- **T2**: 비밀값·검사 실패·역할·목적만 보고, 통과하면 바로 실행(높음 경고·승인 모드는 보지 않는다).

## 4. 테스트 결과

`experiments/yeogwasigan-spring` 에서 `./gradlew test --tests '*PolicyEvaluatorTest'` · 2026-10-27 실행 · **28개 중 28개 통과**.

### 4.1 결정 fixture (`src/test/resources/policy/decision_fixture.json`, 21건)

등록부: `t2-internal` = T2, `t1-enterprise` = T1. 나머지는 T0.

| ID | 상황 | 기대 결과 | 사유 코드 | 결과 |
| --- | --- | --- | --- | --- |
| P-01 | T2 정상 | PRIVATE_READY | T2_READY | 통과 |
| P-02 | T2, 높음 경고 2건 | PRIVATE_READY | T2_READY | 통과 |
| P-03 | T2, 분류 못 한 칸 있음 | PRIVATE_READY | T2_READY | 통과 |
| P-04 | T2, 비밀값 잔존 | DENIED | RESIDUAL_SECRET_FOUND | 통과 |
| P-05 | T2, 파서 실패 | DENIED | PIPELINE_FAILED | 통과 |
| P-06 | T1, 경고 없음 + 조건부 승인 | EXTERNAL_READY | T1_READY | 통과 |
| P-07 | T1, 경고 없음 + 항상 승인 | REVIEW_PENDING | T1_APPROVAL_ALWAYS | 통과 |
| P-08 | T1, 높음 경고 1건 | REVIEW_PENDING | T1_HIGH_WARNING | 통과 |
| P-09 | T1, 분류 못 한 칸 있음 | REVIEW_PENDING | T1_CLASSIFICATION_UNKNOWN | 통과 |
| P-10 | T1, 비밀값 잔존 | DENIED | RESIDUAL_SECRET_FOUND | 통과 |
| P-11 | T1, 금지 atom 잔존 | DENIED | RESIDUAL_PROHIBITED_ATOM_FOUND | 통과 |
| P-12 | T1, Residual Scan 실패 | DENIED | RESIDUAL_SCAN_FAILED | 통과 |
| P-13 | T1, 파서 실패 | DENIED | PIPELINE_FAILED | 통과 |
| P-14 | T1, 역할 불허 | DENIED | ROLE_NOT_ALLOWED | 통과 |
| P-15 | T1, 목적 비활성(DEPRECATED) | DENIED | PURPOSE_NOT_ACTIVE | 통과 |
| P-16 | T0 미등록 URL, 나머지 모두 정상 | DENIED | T0_DESTINATION | 통과 |
| P-17 | T0 개인 계정 | DENIED | T0_DESTINATION | 통과 |
| P-18 | T0 대상 ID 비어 있음 | DENIED | T0_DESTINATION | 통과 |
| P-19 | T0 + 역할 불허 + 파서 실패 (T0 가 먼저) | DENIED | T0_DESTINATION | 통과 |
| P-20 | T0 + 비밀값 (비밀값이 먼저) | DENIED | RESIDUAL_SECRET_FOUND | 통과 |
| P-21 | 대소문자만 다른 ID (`T1-Enterprise`) | DENIED | T0_DESTINATION | 통과 |

### 4.2 그 밖의 테스트 (7건)

| 테스트 | 확인하는 것 | 결과 |
| --- | --- | --- |
| T0 는 나머지 입력을 어떻게 바꿔도 DENIED | 역할·목적·검사·경고·승인 모드 48가지 조합 모두 T0_DESTINATION | 통과 |
| 등급은 등록부에서만 읽고 없으면 T0 | 등록 ID 는 T2/T1, 뒤 공백·빈 값·null 은 T0 | 통과 |
| T0 를 등록부에 넣으면 만들 수 없다 | 등록부에 T0 를 적으면 예외 | 통과 |
| 평가가 실패하면 통과가 아니라 막는다 | Residual 결과 누락·입력 null → POLICY_FAILED 로 거부 | 통과 |
| 실제 파생본 Secret 삽입 사본 | s01 시나리오 파생본에 Stripe 키를 넣으면 실제 ResidualScanner → T2·T1 모두 DENIED. 원본 파생본은 T2 PRIVATE_READY, T1 EXTERNAL_READY, 미등록 대상 DENIED | 통과 |
| 결과에는 사유 코드만 있고 값은 없다 | 결과 문자열에 비밀값 조각이 없음 | 통과 |
| fixture 등록부가 테스트 등록부와 같다 | fixture 파일과 테스트 코드의 등록부가 어긋나지 않음 | 통과 |

## 5. 아직 하지 않은 것

- **게이트웨이 연결**: 지금 `GatewayService` 는 등록 대상을 문자열 목록(`yeogwasigan.gateway.endpoints`)으로만 본다. 대상마다 등급을 붙이는 설정과 `create()` 에서 이 평가기를 부르는 일은 다음 단계다. 대운의 policy fixture(T0 대상 거부)가 나오면 같은 입력으로 맞춘다.
- **T1 ceiling**: 명세의 "T1 은 host·과도한 stack frame 을 더 줄일 수 있다"는 패키지 내용을 바꾸는 일이라 Package Builder 쪽에서 한다. 이 평가기는 상태만 정한다.
- **팀 확인 필요**: T2 에서 높음 경고가 있어도 바로 실행(P-02)하는 것이 맞는지. 명세상 T2 는 PRIVATE_READY 이지만, 준수의 승인 기준(14번 문서)은 등급 구분 없이 쓰여 있다.
