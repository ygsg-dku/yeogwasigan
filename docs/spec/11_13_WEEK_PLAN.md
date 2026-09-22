# 여과시간 13주 실행 계획

가용 시간은 4명 × 주 10시간 × 13주 = 520시간이다. 현재 Backlog의 최하위 Task 추정은 336시간(P0 308, P1/P2 28)이다. 390시간을 계획 상한으로 두고, 최소 130시간은 리뷰·통합·버그·실패 대응에 남긴다. 나머지 54시간도 필요할 때만 사용한다. Week 8 E2E가 핵심 checkpoint이며 Week 9 이전이라는 요구를 충족한다.

| Week | 목표 | 산출물 | 주 담당 | 선행조건 | 완료 기준 |
| ---: | --- | --- | --- | --- | --- |
| 1 | 요구사항·Task Brief·Pilot 설계 | 두 업무 목록 초안, generator, Pilot 6, 정답(원인) 작성 | B/D/C | v1 기준 | 두 목록과 비교 조건 합의 |
| 2 | Pilot Gate와 평가 동결 | Pilot report, interview evidence-gap memo, rubric, Locked seed/input/Gold hash | A/B/C/D | W1 fixture | 반박 사례와 범위 수정 여부 기록, Gold runtime 분리 확인 |
| 3 | Backend skeleton·권한·DB | API-01~03, schema migration, role tests | A/B/D | W2 contract | 요청 생성과 권한 테스트 통과 |
| 4 | Classification·Package | finding, HMAC token, two package golden test | B/D/A | W3 request | 두 Purpose deterministic package 통과 |
| 5 | Policy·Residual Scan | decision/explain, fail-closed integration tests | B/A/C | W4 package | T0/Secret/parse failure T1 차단 |
| 6 | Docker network·adapter | compose topology, T2/T1 mock, receiver evidence | C/A | W5 ready state | direct fail, 여과시간→T1 pass |
| 7 | Approval·State·Audit | fingerprint approval, transition guard, audit timeline | A/B/D | W6 execution path | 변경 후 approval invalidation 통과 |
| 8 | Workspace E2E | SCR-01~05, 등록 T1 외부 AI 결과, demo path | D/A/B/C | W7 | 입력→패키지→승인→등록 T1 AI→Audit 시연 |
| 9 | 실패 흐름·E2E 고정 | e2e/security/network suite, UI 오류 상태 | A/B/C/D | W8 | P0 regression pass, P1 착수 판단 |
| 10 | Dev tuning·실행 동결 | B3/P config, runner, model/prompt/scorer hash | A/B/C/D | W9 | Locked를 읽지 않고 artifact freeze |
| 11 | Locked A/B/C 실행 | raw outputs, blind scoring, paired result | C/D/A/B | W10 manifest | Locked 최초 실행·실패 보존 |
| 12 | 결과·발표 (인터뷰는 기회 될 때) | report, 60초 demo, Q&A, evidence package | C/D/A/B | W11 result | 결과/한계/실패 설명 가능 |
| 13 | 최종 재현·리허설 | clean-run guide, final DoD, recording | A/B/C/D | W12 | P0 Evidence 완비, 기능 추가 금지 |

## Gate와 축소 규칙

- Week 2: Pilot 6건을 **다 보낸 경우 / 목록대로만 보낸 경우**로 나눠 같은 모델·질문·설정에서 실행한다. 목록대로만 보냈을 때 원인이 잡히지 않거나 hard Secret이 남으면 **Week 3 이후를 시작하지 않는다.** 목록을 고쳐 재실행하고, 고쳐도 안 잡히면 담기가 이 업무에 맞지 않는다는 결론을 받아들이고 대상 문제 또는 주제를 재선정한다.
- Pilot은 Spring Boot·DB·Docker 없이 스크립트로 실행한다. 시스템을 만들어 가설을 검증하지 않는다.
- 정답(원인)은 목록을 만들지 않은 담당이 로그만 보고 작성한다. 목록 작성자가 정답도 쓰면 채점이 순환한다.
- Week 6: Network evidence가 불안정하면 Linux VM에서 Compose 환경을 고정한다.
- Week 7: Approval/Audit가 미완성이면 actual AI 고도화와 UI 장식을 삭제한다.
- Week 8: E2E가 안 되면 P1 Idempotency, monitoring, 고급 UI를 제거하고 P0만 통합한다.
- Week 10: Locked 결과 전 마지막 config 동결이다. 결과를 본 뒤 tuning하지 않는다.

## 60초 데모 배치

| 시간 | 장면 | 증명 |
| ---: | --- | --- |
| 0~10초 | 내부 장애 원문과 AI 사용 순간 | 문제 |
| 10~30초 | 목록대로 담은 패키지와 KEEP/TOKENIZE/DROP | 빼기가 아니라 담기 |
| 30~45초 | T2/T1/T0 policy 결과 | 통제 경계 |
| 45~60초 | 다 보낸 경우 대 목록대로만 보낸 경우의 paired result | 목록으로도 원인이 잡히는가 |

direct curl, approval invalidation, state transition은 Q&A fixture로 둔다.
