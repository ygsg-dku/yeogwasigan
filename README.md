# 여과시간

여과시간은 금융 결제 장애 로그 한 건을 AI에 반출하기 전에 검토하는 업무 흐름이다. 이번 장애와 업무에 필요한 field·event·relation으로 만든 패키지와 제외·변환 근거를 사용자가 확인하고, 승인한 패키지만 등록된 AI 경로로 전송한다.

기존 도구의 기본 동작은 민감한 값을 지우고 나머지를 보내는 **빼기**다. 여과시간은 업무마다 보낼 정보의 **목록**을 정하고 그 목록에 있는 것만 담는 **담기**를 기본으로 놓는다. 담기 자체는 새 기술이 아니다 — Google SDP로도 구성할 수 있고, 무엇을 담을지는 호출자가 정해야 한다고 공식 문서가 명시한다. 비어 있는 것은 담기 능력이 아니라 그 목록을 정하는 정책이다.

내놓을 것은 두 업무의 목록과, 그 목록대로만 보내도 원인이 잡히는지에 대한 동일 조건 비교 결과다. 측정 결과는 아직 없다. 포지셔닝의 근거·한계·철회 조건은 [`docs/research/FINAL_DIFFERENTIATION.md`](docs/research/FINAL_DIFFERENTIATION.md)를 따른다.

## 문서

개발 착수 명세는 [`docs/spec/`](docs/spec)에 있다.

- `00_REPOSITORY_DESIGN.md`: monorepo 구조, Gold 격리, Docker·브랜치 규칙
- `01_PRD.md` ~ `13_TEAM_ALLOCATION.md`: 제품·설계·검증·실행 명세

## 보안 원칙

실제 T1 API 키, `.env`, HMAC secret, Locked input, Gold annotation은 이 저장소에 commit하지 않는다.
