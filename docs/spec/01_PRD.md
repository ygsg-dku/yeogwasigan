# 여과시간 PRD

## 1. 제품 정의

여과시간은 **금융 결제 장애 로그 한 건을 AI에 반출하기 전에 검토하는 업무 흐름**이다. 사용자는 이번 장애와 업무에 필요한 정보만 담은 패키지를 보고, 무엇이 제외·변환됐는지와 그 근거를 확인한 뒤, 승인한 패키지만 등록된 AI로 전송한다.

### 빼기가 아니라 담기

기존 도구의 기본 동작은 **빼기**다. 민감한 값을 탐지해 지우고 나머지를 보낸다. 탐지가 놓치면 그대로 나가고, 업무와 무관한 정보도 함께 나간다.

여과시간은 **담기**를 기본으로 놓는다. 업무마다 보낼 정보의 **목록**을 미리 정하고, 장애가 나면 그 목록에 있는 것만 골라 담아 사람이 확인한 뒤 보낸다. 안 담은 것은 탐지 성공 여부와 무관하게 나가지 않는다.

실패했을 때 넘어지는 방향이 다르다. 빼기는 탐지가 실패하면 **유출**이고, 담기는 목록이 부족하면 **답변 품질 저하**다.

담기에는 대가가 있다. 업무마다 목록을 만들고 관리해야 한다. 아무도 담기를 기본값으로 쓰지 않는 이유가 이 비용일 수 있다. 이 과제의 가정은 **장애 대응은 보는 정보가 반복된다**는 것이고, 그래서 목록 관리 비용이 감당된다는 것이다. 이 가정이 깨지면 담기 자체가 성립하지 않는다.

### 새 기술이 아니다

담기 자체는 새 기술이 아니다. [Google Sensitive Data Protection](https://docs.cloud.google.com/sensitive-data-protection/docs/deidentify-sensitive-data)은 필드별·조건별 변환을 제공하므로 필요한 것만 남기도록 구성할 수 있다. 다만 **무엇을 남길지는 호출자가 정해야 한다고 공식 문서가 명시한다.** 목적 기반 정책은 [XACML](https://docs.oasis-open.org/xacml/3.0/xacml-3.0-privacy-v1-spec-cs-01-en.html)에, AI 경로 통제·감사·요청별 승인은 [Kong](https://developer.konghq.com/ai-gateway/ai-audit-log-reference/)과 [Databricks](https://docs.databricks.com/aws/en/ai-gateway/ai-governance) 등에 이미 있다. 기존 제품을 조합해 유사한 흐름을 만들 수 있다.

비어 있는 것은 담기 능력이 아니라 **이 업무에 무엇을 담아야 하는지를 정하는 정책**이다.

### 내놓을 것

1. 결제 장애 두 업무(원인 분석, 보고서 초안) 각각의 **보낼 정보 목록**
2. 그 목록에 있는 것만 보내도 **원인을 제대로 찾는지** 동일 조건 비교 결과

목록을 정하는 조직은 많다. 그 목록으로 충분한지 재서 내놓는 곳은 확인하지 못했다. 이것이 입증된 우위라는 뜻은 아니며, 측정 결과는 아직 없다.

포지셔닝의 근거·한계·철회 조건은 [최종 포지셔닝](../research/FINAL_DIFFERENTIATION.md)을 따른다. Azure Copilot Observability Agent와의 좁은 방식 차이도 그 문서에 기록돼 있으나, 2차 발표 덱에서는 내부 Azure 경로라 상황이 달라 제외했다.

## 2. 문제와 사용자

금융 IT 개발자와 시스템 운영자는 장애 로그를 AI에 분석시키고 싶지만, 로그에는 고객명, 계좌번호, 내부 IP, 거래 식별자, Secret이 함께 있을 수 있다. 원문을 복사해 외부 AI에 전달하면 통제 경계를 벗어나고, 모든 정보를 같은 방식으로 가리면 원인 분석에 필요한 이벤트 순서와 관계까지 사라질 수 있다.

| 사용자 | 목표 | 현재 어려움 | 여과시간이 제공할 결과 |
| --- | --- | --- | --- |
| 개발자/SRE | 장애 원인과 근거를 빠르게 얻기 | 수동 정리 부담, 외부 전달 불안 | 분석에 필요한 구조와 관계를 가진 패키지 |
| 보안 검토 담당자 | 반출이 정책에 맞는지 확인하기 | 원문과 판단 근거 추적이 어려움 | Purpose, 정책, 변환, 승인, 실행 감사기록 |
| AI 플랫폼 운영자 | 승인된 AI 경로만 허용하기 | 임의 URL·개인 AI 우회 위험 | 등록 목적지와 Trust Tier 기반 실행 |

대표 시나리오는 결제 오류가 발생한 상황이다. SRE가 합성 결제 장애 JSON을 업로드하고 INCIDENT_ANALYSIS를 고른다. 여과시간은 Secret·고객명·계좌를 제거하고 거래·호스트 관계는 사건 범위 토큰으로 보존한다. SRE가 승인된 T1 Enterprise AI를 선택하면 검토자가 패키지 fingerprint를 승인한 뒤에만 실행된다. 결과와 반출 근거는 Audit에서 확인한다.

## 3. 사용자 흐름

로그 입력 → 로그인/권한 → Purpose 선택 → Destination 선택 → Classification → Minimum Disclosure Package → 원본/패키지 비교 → Policy Decision → Residual Scan → 필요 시 Approval → AI Execution → Result → Audit

정상 및 실패 분기는 02_USER_FLOW.md, 기능 책임은 03_FUNCTION_SPEC.md를 기준으로 한다.

## 4. 범위

### Must

- 합성 PAYMENT_INCIDENT_JSON 한 형식과 두 Purpose: INCIDENT_ANALYSIS, INCIDENT_REPORT_DRAFT
- 역할 확인, 승인된 Purpose와 등록된 Destination 선택
- PII·계좌·내부 IP·Secret 분류과 변환: KEEP, DROP, TOKENIZE, PSEUDONYMIZE, GENERALIZE
- 최소 업무 패키지와 transformation manifest, 원본/패키지 비교
- T2 Organization Controlled, T1 Approved Managed External, T0 Unmanaged 판정
- Residual Scan과 parser/detector/scanner 실패 시 T1 fail-closed
- Security State Machine, Approval Invalidation, Audit
- Docker 내부 Client의 외부 직접 연결 차단과 여과시간 경유 등록 T1 성공
- controlled receiver 검증 뒤 등록된 T1 외부 AI 한 곳에 합성 패키지를 실행하고 결과를 받기
- Strong Fixed Policy Baseline(B3)과 여과시간의 공정한 비교 실험

### Should

- 요청 전송 전 중복 억제와 DELIVERY_UNKNOWN 기록
- AI 응답의 canary 재노출 검사
- 정책 버전 변경 회귀시험

### Could

- Micrometer, Prometheus/Grafana, OPA, nftables/tcpdump 고도화

### Won't

- 실제 개인정보, HWP/PDF/OCR/ZIP, 전사 웹 트래픽 통제, TLS interception
- 자유문장 Purpose, 임의 URL, 자체 NER/LLM 학습, RAG, Kafka, Kubernetes
- 금융기관 규제 준수, 완전 익명화, 외부 AI의 내부 데이터 보관 여부 증명

## 5. 성공 조건

### 주 조건 — 반출 전 검토 흐름

1. 검토자가 전송될 패키지 내용과 제외·변환 근거를 화면에서 확인하고 승인한 뒤에만 등록 Destination으로 전송된다.
2. P0만으로 11주차까지 입력→패키지→검토·승인→AI 결과→Audit 수직 흐름을 시연한다.
3. T0는 항상 DENY되고, Secret이 남거나 검사 실패한 T1 패키지는 외부 수신 0건이다.
4. Internal Client의 T1/T0 직접 연결은 실패하고 여과시간 경유 등록 T1은 성공한다.
5. 데이터·Purpose·Destination·Policy version 변경 뒤 기존 승인은 실행에 재사용되지 않는다.

### 보조 조건 — 목적별 선택의 효용

6. 동일 원문에서 두 업무가 각자의 목록대로 다른 패키지를 만든다.
7. 다 보낸 경우와 목록대로만 보낸 경우를 같은 모델·질문·설정으로 비교했을 때, 목록대로만 보내도 원인이 잡힌다.

보조 조건은 흐름이 동작한다는 전제 위에서만 의미가 있다. 주 조건 1~5 중 하나라도 미달이면 보조 조건 결과를 차별성 근거로 제시하지 않는다. 7이 실패하면 목록이 부족하다는 뜻이며, 그 결과를 그대로 보고한다.

## 6. 비목표와 제한

`왜 제외했는가` 화면은 **적용한 정책 규칙을 설명**한다. 해당 필드가 실제 업무에 불필요하다는 사실까지 증명하지 않는다.

담기는 **장애 대응은 보는 정보가 반복된다**는 가정 위에 서 있다. 장애마다 필요한 정보가 매번 다르면 목록이 무한히 늘어나 관리 비용이 감당되지 않고, 담기 자체가 성립하지 않는다. 이 경우 목적별 마스킹을 다시 신규 기술처럼 주장하지 않고 대상 문제 또는 주제를 재선정한다.

현업 인터뷰에서 별도 AI 반출·사전 검토 수요가 확인되지 않는 경우도 같다. 다만 인터뷰는 섭외가 불확실하므로 게이트가 아니라 보강 항목으로 둔다. 첫 목록은 공개된 장애 보고서와 합성 로그를 근거로 정한다.

여과시간의 패키지는 절대적 최소가 아니라 **미리 승인한 목록 안에서의 최소**다. 목록이 맞는지는 주장하지 않고 실험으로 확인한다. T2도 목적 최소화를 적용하며 Trust Tier는 공개량을 자동으로 정하지 않고 Purpose가 요구한 데이터의 허용 상한을 정한다. Gold Annotation은 benchmark 전용이고 runtime에는 접근 경로가 없다. controlled receiver는 실제 T1 연결 전에만 네트워크·정책을 검증하는 단계이며, 11주차 E2E와 실험에는 등록된 외부 AI 한 곳을 사용한다. 해당 endpoint의 자격증명은 repository·Audit·화면에 저장하지 않는다.

## 7. 현업 근거의 빈칸

문제 정의는 아직 현업 인터뷰로 확인되지 않은 가설이다. 이 빈칸은 숨기지 않고 발표와 문서에 그대로 남긴다.

첫 목록은 공개된 장애 보고서와 합성 로그를 근거로 정한다. 목록이 부족하면 실험에서 원인이 잡히지 않으므로, 인터뷰 없이도 부족이 일부 드러난다.

섭외 기회가 생기면 아래를 확인하고 익명 요약과 반박 사례를 남긴다. 의견이 아니라 과거 사실만 묻는다. 전체 질문지는 [2차 발표 대본](../presentations/여과시간_2차발표_선행기술조사_5분_대본.md) 부록에 있다.

1. 최근 겪은 결제 장애 세 건과 각각의 원인은 무엇이었는가?
2. 그때 로그에서 실제로 본 정보는 무엇이었는가? **세 건에서 겹치는가?**
3. AI에 넣어본 적이 있다면 무엇을 지우고 넣었으며 얼마나 걸렸는가?
4. 원인 분석할 때와 보고서 쓸 때 보는 것이 다른가?
5. 이 과정을 이미 해결하는 도구나 내부 스크립트가 있는가?

2번이 핵심이다. 겹치면 목록을 만들 수 있고, 매번 다르면 만들 수 없다. 인원 수가 적으면 정량적 사용자 연구라고 주장하지 않고 capstone의 evidence gap으로 발표한다.

## 부록 A. v1 변경 기록

| 변경 ID | v1 위치 | 변경 전 | 변경 후 | 이유 | 영향 문서 |
| --- | --- | --- | --- | --- | --- |
| CHG-01 | 비교 실험 | Fixed Masking | Strong Fixed Policy B3 | 약한 비교군 공격 방지 | 05, 10, 12 |
| CHG-02 | 평가 | Gold와 runtime 경계 불명확 | Gold는 별도 benchmark 전용 | 순환논리·label leakage 방지 | 06, 09, 10 |
| CHG-03 | MVP | Idempotency Must | P1 Should | 핵심 수직 흐름 우선 | 03, 08, 11, 12 |
| CHG-04 | Trust | AI를 믿는 등급처럼 서술 | 조직 데이터 경계 통제 수준 | 판정 기준 객관화 | 05, 06 |
| CHG-05 | Purpose version | 단순 version | DRAFT→SECURITY_REVIEW→ACTIVE→DEPRECATED | 정책 소유·재현성 명확화 | 05, 07, 08 |
| CHG-06 | tokenization | 사건 범위 토큰만 언급 | HMAC-SHA256(caseId, normalized value) | 관계 보존 검증 가능 | 05, 09 |
| CHG-07 | 평가 실행 API | 운영 API로 benchmark 실행 | benchmark runner 분리 | Gold runtime 접근 금지 | 08, 10 |

## 부록 B. 추적 기준

REQ-01 Purpose별 패키지, REQ-02 Trust Policy, REQ-03 Fail Closed, REQ-04 Approval Invalidation, REQ-05 Network Isolation, REQ-06 Audit, REQ-07 공정한 B3 비교를 최상위 요구사항으로 사용한다. 아래 표는 기준 문서와 구현 추적을 고정한다.

| REQ | 기능 | 정책 | 화면/API | 검증 | Backlog |
| --- | --- | --- | --- | --- | --- |
| REQ-01 Purpose별 최소 패키지 | FUNC-03, FUNC-04 | POLICY-01, POLICY-02, POLICY-07 | SCR-01/02, API-03/04/05 | T-UNIT-01~03, T-E2E-01/02 | EPIC-02, TASK-02-01~07 |
| REQ-02 Trust ceiling·목적지 통제 | FUNC-01, FUNC-05, FUNC-09 | POLICY-03, POLICY-04 | SCR-01/03, API-01/02/04/07 | T-UNIT-04, T-SEC-01, T-NET-03 | EPIC-01/03/04 |
| REQ-03 Fail-closed | FUNC-03, FUNC-05 | POLICY-01, POLICY-04 | SCR-02/05, API-04/05/09 | T-INT-02~04 | EPIC-02/03 |
| REQ-04 승인 무효화 | FUNC-06, FUNC-07 | POLICY-05 | SCR-03/05, API-06/07/09 | T-SEC-02~05, T-CON-01 | EPIC-03 |
| REQ-05 Network Isolation | FUNC-07, FUNC-09 | POLICY-03, POLICY-04 | SCR-01/03/04, API-02/03/07 | T-NET-01~04, T-E2E-01 | EPIC-04 |
| REQ-06 Audit/Explain | FUNC-08 | POLICY-05 | SCR-05, API-05/08/09 | T-INT-01, T-E2E-01/02 | EPIC-03, TASK-05-06 |
| REQ-07 Strong B3 평가 | FUNC-10 | POLICY-08 | 결과 보고서(운영 화면 아님) | T-EXP-01~03, T-E2E-03 | EPIC-06 |
