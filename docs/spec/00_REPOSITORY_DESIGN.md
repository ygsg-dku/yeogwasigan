# 여과시간 Repository Design

## 1. 결정

여과시간은 **단일 GitHub monorepo + Spring modular monolith + React app**으로 시작한다. 4인 팀과 13주 일정에서는 API, Policy, Dispatcher를 각각 독립 배포 서비스로 쪼개지 않는다. 코드 모듈은 분리하되 배포는 `api`, `web`, `internal-client`, `t1-receiver` 네 컨테이너만 둔다.

이 선택은 Purpose Package → Policy/Approval → AI Dispatch → Audit이라는 P0 흐름을 한 저장소와 한 Pull Request에서 검증하기 위해서다.

## 2. 최상위 구조

~~~text
yeogwasigan/
├── apps/
│   ├── web/                         # React + TypeScript, 5개 화면
│   └── api/                         # Spring Boot modular monolith
├── contracts/
│   └── openapi/                     # FE/BE 공유 API 계약
├── infra/
│   ├── compose/                     # Docker Compose, network·env 예시
│   └── docker/                      # 각 컨테이너 Dockerfile
├── fixtures/
│   ├── payment-incidents/           # Pilot/Dev 합성 JSON fixture
│   └── policy/                      # detector·package golden fixture
├── benchmark/
│   ├── runner/                      # B0/B1/B3/P shared pipeline
│   ├── task-briefs/                 # Gold가 아닌 업무 설명
│   └── manifests/                   # frozen config, result hash
├── docs/
│   ├── spec/                        # 00~13 Markdown 명세
│   ├── adr/                         # 선택·변경 이유
│   └── evidence/                    # receiver log, test, demo, report index
├── scripts/                         # 검증·fixture·clean-run 스크립트
├── .github/
│   ├── workflows/                   # CI
│   ├── pull_request_template.md
│   └── CODEOWNERS
├── compose.yaml
├── .env.example
├── .gitignore
├── README.md
└── SECURITY.md
~~~

## 3. API 내부 모듈

~~~text
apps/api/src/main/java/.../yeogwasigan/
├── auth/            # Spring Security, role
├── purpose/         # PurposeTemplate lifecycle·version
├── request/         # DataRequest, state transition
├── classify/        # parser, detector, Finding
├── packagebuild/    # field action, HMAC token, manifest
├── policy/          # Trust ceiling, residual scan, decision
├── approval/        # fingerprint, reviewer, invalidation
├── execution/       # adapter, Dispatcher, result
├── audit/           # event writer, explain query
└── shared/          # error, hash, clock, test fixture
~~~

`execution`은 별도 Git repository나 별도 backend가 아니다. 다만 Docker Compose에서 Dispatcher 경계가 필요한 경우 `api` image의 dispatcher profile 또는 작은 dispatcher container로 분리한다. 이 경계만 egress network에 붙는다.

## 4. Gold와 민감 설정의 분리

다음은 **운영 monorepo에 넣지 않는다**.

- Locked raw input, Gold annotation, condition-key, 실제 T1 API credential
- `.env`, keystore, HMAC serverSecret, model token

`benchmark/`에는 Pilot/Dev fixture, runner code, Task Brief, frozen manifest 형식만 둔다. Locked input·Gold는 C/D만 접근하는 별도 private `yeogwasigan-eval-sealed` repository 또는 암호화된 학교 공유 저장소에 보관한다. 운영 runtime Docker image, volume, DB, API에는 Gold mount·endpoint가 없어야 한다.

## 5. Docker와 환경 파일

~~~text
infra/compose/
├── compose.dev.yaml                 # local 개발 전체 흐름
├── compose.network-test.yaml        # internal-client direct fail 검증
├── env/
│   └── t1.example.env               # 값 없는 변수 이름만
└── README.md                        # 실행·검증 순서
~~~

네트워크 이름은 `internal_net`, `data_net`, `dispatch_net`, `egress_net`으로 고정한다. `internal-client`는 `internal_net`만, API는 egress에 연결하지 않으며 Dispatcher만 `egress_net`에 연결한다. 실제 자격증명은 local secret 또는 CI secret으로 주입하고 commit하지 않는다.

## 6. 문서와 계약의 기준 위치

| 대상 | 기준 위치 | 이유 |
| --- | --- | --- |
| 제품·정책·평가 명세 | `docs/spec/` | 코드와 같은 PR에서 변경 이력 관리 |
| FE/BE API 계약 | `contracts/openapi/openapi.yaml` | 화면과 backend 병렬 개발 |
| 아키텍처 선택 변경 | `docs/adr/ADR-XXX.md` | v1 충돌·변경 이유 보존 |
| 검증 결과 | `docs/evidence/` | 발표 시 재현 가능한 증거 인덱스 |
| 회의·이번 주 상태 | Notion | 팀 운영과 교수님 피드백 관리 |

Notion은 문서 원본을 첨부해 보여주는 운영 허브이고, merge된 GitHub `docs/spec/`가 개발 명세의 기준본이다.

## 7. Branch와 Pull Request

~~~text
main                         # 항상 실행·시연 가능한 기준
docs/spec-v1.1               # 최초 명세 import PR
feat/request-flow            # Request·auth·schema
feat/package-builder         # classification·transform
feat/policy-approval         # policy·scan·approval·audit
feat/ai-dispatch             # Compose·Dispatcher·adapter
feat/workspace-ui            # React 5 화면
feat/evaluation              # runner·fixture·frozen manifest
fix/<short-description>      # main regression 수정
~~~

- `main` 직접 push 금지. 최소 1명 review 후 merge한다.
- 한 PR은 하나의 사용 가능한 결과만 담는다. 예: `Purpose Package 생성`, `T1 direct path 차단`.
- API 계약 변경은 A와 D, Policy/Purpose 변경은 B와 C가 review한다.
- `main`에는 CI가 통과한 코드만 merge한다. P0 CI는 API test, web typecheck, compose config, Gold path 검사다.

## 8. 초기 커밋 순서

1. `chore: initialize yeogwasigan monorepo` — 폴더, `.gitignore`, README, `.env.example`, Compose skeleton.
2. `docs: add 여과시간 v1.1 specifications` — `docs/spec/00~13`과 PR template.
3. `chore: add CI and CODEOWNERS` — build/test placeholder, review rule.
4. 이후 Week 1~2 P0 Task를 branch별로 시작한다.

## 9. README에 반드시 둘 것

- 프로젝트 한 줄 정의와 60초 demo 흐름
- 로컬 실행 전제: Docker, JDK, Node
- `compose.dev` 실행 명령과 종료 명령
- test/fixture 실행 명령
- Secret·Gold를 commit하지 않는 규칙
- 문서·Notion·GitHub 역할 분리

## 10. P0 완료 기준

P0는 `feat/request-flow` → `feat/package-builder` → `feat/policy-approval` → `feat/ai-dispatch` → `feat/workspace-ui` 순서로 합쳐진다. `feat/evaluation`은 Week 2 Pilot 계약과 Week 10 frozen manifest를 만들지만, Gold를 runtime repository로 가져오지 않는다.

이 구조는 P1 Idempotency·Monitoring이 없어도 Week 8 E2E를 막지 않는다.
