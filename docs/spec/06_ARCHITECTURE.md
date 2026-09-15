# 여과시간 Architecture

## 구조

~~~mermaid
flowchart LR
  U[Internal Client / React] --> API[Spring Boot API]
  API --> SEC[Spring Security]
  API --> REG[Purpose Registry]
  API --> CLS[Classification]
  API --> PKG[Package Builder]
  API --> POL[Policy Engine]
  API --> RES[Residual Scanner]
  API --> APR[Approval]
  API --> AUD[Audit]
  API --> DB[(PostgreSQL)]
  API --> T2[T2 Private AI / Mock]
  API --> DIS[Dispatcher]
  DIS --> T1[T1 Controlled Receiver / Approved AI]
  BENCH[Benchmark Runner + Gold] -. no runtime access .-> API
~~~

## 컴포넌트 책임과 경계

| 구성요소 | 책임 | 왜 분리하는가 | 입력/출력 |
| --- | --- | --- | --- |
| React Workspace | 요청·비교·검토·결과·감사 UI | 보안 규칙을 UI에 두지 않고 서버 판정을 표시하기 위해 | API DTO / 사용자 Action |
| Spring Boot API/Orchestrator | 요청 lifecycle과 상태 전이 조정 | 흐름 순서와 transaction 경계를 한 곳에서 강제하기 위해 | requestId / state·DTO |
| Spring Security | 인증·역할·자기승인 금지 | Purpose 권한과 reviewer 권한을 업무 로직에서 분리하기 위해 | principal / 401·403 |
| Purpose Registry | versioned Purpose 계약 제공 | 자유문장 추론 대신 승인된 계약만 쓰기 위해 | purposeCode / field rules |
| Classification | parse·finding·관계 후보 생성 | 탐지 결과를 변환·정책과 독립적으로 검증하기 위해 | raw JSON / Finding |
| Package Builder | 최소 패키지·manifest·hash 생성 | 목적별 선택과 변환을 결정적으로 재현하기 위해 | findings+template / package+manifest |
| Policy Engine | role·Purpose·Trust·dataClass 판정 | 정책 설명과 allow/deny를 변환 코드와 분리하기 위해 | context / decision+explain |
| Residual Scanner | 변환 후 금지값 재검사 | 최초 detector miss가 외부 전송으로 이어지지 않게 하기 위해 | package / pass·finding |
| Approval | fingerprint 기반 승인·무효화 | 검토 결정이 정확한 패키지에만 적용되게 하기 위해 | fingerprint / approval status |
| AI Adapter | T2/T1 공통 호출·결과 변환 | provider 차이를 흐름 코드에서 격리하기 위해 | package / execution result |
| Egress Dispatcher | 등록 T1 목적지만 외부 연결 | 원본 처리 API에 외부 egress 권한을 주지 않기 위해 | destinationId+package / receiver response |
| Audit | append-only lifecycle 증거 | 원문 없이 결정·실행을 설명하기 위해 | event / ordered history |
| PostgreSQL | 상태·version·approval·audit 관계 저장 | transaction·조회·제약을 일관되게 유지하기 위해 | entities / durable records |
| Benchmark Runner | B3/P 실행·Gold scoring | Gold가 운영 runtime으로 새지 않게 하기 위해 | frozen config / report |

Purpose Registry, Classification, Package Builder, Policy Engine, Residual Scanner, Approval, Audit은 MVP에서 별도 컨테이너가 아닌 Spring Boot 내부 모듈이다. Dispatcher만 외부 egress 경계를 위해 별도 배포 단위로 둔다.

## Docker Network

~~~mermaid
flowchart LR
  C[internal-client] --- N1[internal_net: internal]
  API[yeogwasigan-api] --- N1
  API --- N2[data_net: internal]
  DB[(postgres)] --- N2
  T2[t2-mock] --- N2
  API --- N3[dispatch_net: internal]
  D[egress-dispatcher] --- N3
  D --- N4[egress_net]
  T1[t1-receiver or approved AI] --- N4
  C -. direct blocked .-> T1
~~~

- internal_net, data_net, dispatch_net은 Docker Compose internal network다.
- internal-client는 internal_net만 연결한다.
- yeogwasigan-api는 원본·패키지·정책을 처리하지만 egress_net에는 연결하지 않는다.
- egress-dispatcher만 egress_net에 연결한다. Dispatcher는 destinationId를 allowlist에서 해석하고 사용자 입력 URL을 받지 않는다.
- DB, T2, Dispatcher 포트는 host에 publish하지 않는다. 데모 UI만 loopback 포트를 publish한다.
- Docker network만으로 Dispatcher가 침해된 뒤의 전체 인터넷 egress를 완전 차단한다고 주장하지 않는다. 목적지 allowlist는 Dispatcher 정책으로 보완한다.

## 데이터 흐름과 실패 닫힘

1. API는 raw payload를 Object Store 또는 암호화된 개발 저장소 참조로 두고 rawHash만 DB/Audit에 저장한다.
2. Classification과 Package Builder가 manifest, derivedHash, derivedObjectRef를 만든다.
3. Policy와 Residual Scan이 통과해야 Dispatcher에 패키지를 건넨다.
4. Audit intent를 저장하지 못하면 외부 실행하지 않는다.
5. parser/detector/policy/scanner failure는 T1에서 REVIEW_PENDING 또는 DENIED다.
6. Dispatcher는 패키지와 등록 목적지만 받고 raw 원문 저장소와 DB 권한을 갖지 않는다.
7. Benchmark Runner는 별도 계정·이미지·volume에서 Gold를 읽는다. runtime API, database, container에는 Gold mount 또는 endpoint가 없다.

## 기술 선택

| 기술 | MVP 사용 | 이유 |
| --- | --- | --- |
| React | 5개 Workspace 화면 | package diff와 상태를 명확히 보여줌 |
| Spring Boot + Spring Security | workflow, API, role | 검증 가능한 상태·권한 구현 |
| JPA + PostgreSQL | 9개 핵심 entity | version·approval·audit 관계 |
| Docker Compose | 내부·egress 경계 | 재현 가능한 network evidence |
| 정규식/schema detector 또는 Presidio | Classification | 자체 탐지 모델 개발을 범위에서 제외 |
| T2 mock/local, T1 receiver/실제 AI | adapter 검증 | Trust별 실행 결과 |

OPA, Prometheus/Grafana, nftables/tcpdump 고도화는 P2다.
