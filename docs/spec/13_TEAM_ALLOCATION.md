# 여과시간 팀 배치와 협업 규칙

## 역할

| 담당 | 주담당 | 리뷰 담당 | 공유 책임 |
| --- | --- | --- | --- |
| A Backend Workflow | request/API, State Machine, Execute, B3/runner/scorer | B의 policy, D의 DB | API contract, E2E 통합 |
| B Policy/IAM | Spring Security, Purpose contract, Policy, Residual Scan, Approval | A의 workflow, D의 UI 정책 표시 | Gold 접근 금지 |
| C Network/Security | Docker network, receiver, T2/T1 adapter, threat/network test, Gold Annotation | B scanner, A execution | P/B3/scanner 코드 수정 금지 |
| D DB/Data/Evaluation | schema/migration, generator/provenance, React Workspace, Audit view | A API, C receiver evidence | Locked seed/condition key 봉인 |

특정 도메인을 한 명만 아는 상태를 막기 위해 모든 P0 PR은 주담당 외 한 명이 재현 리뷰한다. A와 D는 API/DB, B와 C는 Policy/Network을 교차 리뷰한다.

## 평가 독립성

| 역할 | 담당 | 금지 |
| --- | --- | --- |
| Purpose/Policy | B | Locked Gold/결과 보기 |
| Generator/split | D | 구현 결과로 Locked 사건 수정 |
| Gold Annotation | C | P/B3/residual scanner 규칙 수정 |
| B3/scorer | A | Locked Gold로 tuning |

외부 평가자가 없다는 한계를 문서에 명시한다. condition ID를 숨긴 결과를 C/D가 채점하고 대응표는 raw score 동결 후 공개한다.

## 병렬 작업과 Blocking Dependency

~~~mermaid
flowchart LR
  W1[W1 Contract/Pilot] --> W2[W2 Gate/Freeze]
  W2 --> API[W3 API+DB]
  W2 --> DATA[W3 Dataset/Gold isolation]
  API --> PKG[W4 Package]
  PKG --> POL[W5 Policy/Residual]
  POL --> NET[W6 Network/Adapter]
  POL --> APR[W7 Approval/Audit]
  NET --> E2E[W8 UI E2E]
  APR --> E2E
  DATA --> FREEZE[W10 Artifact freeze]
  E2E --> FREEZE
  FREEZE --> LOCKED[W11 Locked run]
~~~

| 기간 | A | B | C | D | 병렬 가능 | 막히는 조건 |
| --- | --- | --- | --- | --- | --- | --- |
| W1-2 | B3/rubric | Purpose/Policy | Gold/interview | generator/split | 4개 역할 병렬 | Gate 전 runtime 구현 확장 금지 |
| W3 | API/state skeleton | security/roles | compose spike | DB migration/API types | API·DB·compose 병렬 | Purpose contract freeze |
| W4 | prepare orchestration | resolver/token | detector fixture review | parser/finding/UI request | package 구성 병렬 | schema parser |
| W5-6 | execute flow | policy/residual | receiver/dispatcher | package UI | policy·network·UI 병렬 | derived package |
| W7-8 | state/audit integration | approval | network evidence | five screens/E2E | UI·Audit·network 병렬 | ready state·adapter |
| W9-11 | config/runner | policy regression | blind scoring | provenance/report | dev tuning/문서 병렬 | Week 10 freeze |
| W12-13 | rehearsal | Q&A | evidence runbook | demo/report | 발표 준비 병렬 | Locked report |

## 시간 배분과 대체 규칙

- 각 팀원은 주 10시간 기준으로 P0 작업을 먼저 배정한다.
- 주담당이 1주 이상 지연되면 리뷰담당이 작은 Task를 이어받고 주담당은 통합·테스트를 맡는다.
- API contract 변경은 A와 D의 동시 승인, Policy/Purpose 변경은 B와 C의 동시 승인, dataset/Gold 변경은 C와 D의 기록 승인이 필요하다.
- Locked freeze 뒤에는 bug fix가 결과에 영향을 주면 새 artifact hash를 만들고 기존 Locked 결과를 최종 증거로 쓰지 않는다.

## 최종 확인표

| 요구사항 | 기준 문서 | 주 담당 | 리뷰 담당 | 완료 증거 |
| --- | --- | --- | --- | --- |
| Purpose package | 05_POLICY_SPEC, 03_FUNCTION_SPEC | B | A/D | golden manifest |
| 권한·정책·승인 | 03_FUNCTION_SPEC, 07_STATE_AND_ERD | B | A/C | integration tests |
| Network isolation | 06_ARCHITECTURE, 09_TEST_PLAN | C | D/A | compose + receiver log |
| UI E2E | 04_SCREEN_SPEC, 08_API_SPEC | D | A/B/C | demo capture |
| B3/Locked experiment | 10_EXPERIMENT_PLAN | A/C/D | B | frozen manifest/report |

