# 여과시간

여과시간은 내부 장애 로그에서 승인된 Purpose에 필요한 field·event·relation만 남긴 최소 업무 패키지를 만들고, 등록된 AI 경로로만 실행하는 Trust Gateway다.

## 문서

개발 착수 명세는 [`docs/spec/`](docs/spec)에 있다.

- `00_REPOSITORY_DESIGN.md`: monorepo 구조, Gold 격리, Docker·브랜치 규칙
- `01_PRD.md` ~ `13_TEAM_ALLOCATION.md`: 제품·설계·검증·실행 명세

## 보안 원칙

실제 T1 API 키, `.env`, HMAC secret, Locked input, Gold annotation은 이 저장소에 commit하지 않는다.
