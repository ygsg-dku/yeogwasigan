# 여과시간 PRD

## 1. 제품 정의

여과시간은 내부 장애 로그를 원문 그대로 AI에 보내지 않고, 승인된 업무 목적(Purpose)에 필요한 필드와 관계만 담은 최소 업무 패키지를 만들어 허용된 AI 경로로 실행하는 Trust Gateway다.

핵심 가설은 Purpose-Aware Minimum Disclosure다. 같은 로그라도 장애 원인 분석과 장애 보고서 초안에는 필요한 정보가 다르므로, 하나의 고정 마스킹보다 목적별 패키지가 불필요한 공개를 줄이면서 업무 효용을 유지하는지 검증한다.

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

1. 동일 원문에서 두 Purpose가 계약에 맞게 다른 패키지를 만든다.
2. T0는 항상 DENY되고, Secret이 남거나 검사 실패한 T1 패키지는 외부 수신 0건이다.
3. Internal Client의 T1/T0 직접 연결은 실패하고 여과시간 경유 등록 T1은 성공한다.
4. 데이터·Purpose·Destination·Policy version 변경 뒤 기존 승인은 실행에 재사용되지 않는다.
5. B3와 여과시간은 detector, tokenization, residual scan, model, prompt, network 조건을 공유하고, Purpose별 선택만 다르게 비교한다.
6. P0만으로 Week 8까지 입력→패키지→승인→AI 결과→Audit 수직 흐름을 시연한다.

## 6. 비목표와 제한

여과시간의 최소 패키지는 절대적 최소가 아니라 승인된 Purpose 계약 안의 contract-minimal 표현이다. T2도 목적 최소화를 적용하며 Trust Tier는 공개량을 자동으로 정하지 않고 Purpose가 요구한 데이터의 허용 상한을 정한다. Gold Annotation은 benchmark 전용이고 runtime에는 접근 경로가 없다. controlled receiver는 실제 T1 연결 전에만 네트워크·정책을 검증하는 단계이며, Week 8 E2E와 실험에는 등록된 외부 AI 한 곳을 사용한다. 해당 endpoint의 자격증명은 repository·Audit·화면에 저장하지 않는다.

## 7. 현업 근거의 빈칸

문제 정의는 아직 현업 인터뷰로 확인되지 않은 가설이다. Week 1~2에 금융 IT 개발자·운영자·보안 검토 담당자를 대상으로 아래를 확인하고, 익명 요약과 반박 사례를 남긴다.

1. AI 사용 전 수동 sanitization을 하는가, 또는 사용을 포기한 적이 있는가?
2. 한 개의 Enterprise/Private AI만으로 업무가 충분한가?
3. Purpose별 policy는 누가 어떤 주기로 관리할 수 있는가?
4. 고정 masking 때문에 원인 분석의 event/relation 맥락을 잃은 사례가 있는가?

인터뷰가 위 문제를 지지하지 않으면 기능을 추가하는 대신 Target User, Problem, Purpose contract 또는 비교 가설을 Week 2 Gate 기록에 맞춰 축소·수정한다. 인원 수가 적으면 정량적 사용자 연구라고 주장하지 않고 capstone의 evidence gap으로 발표한다.

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
