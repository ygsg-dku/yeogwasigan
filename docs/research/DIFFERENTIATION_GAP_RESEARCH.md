# 여과시간 차별화 후보: 확인된 제품 한계와 남은 공백

> 조사일: 2026-09-22. 근거는 제조사 공식 문서와 원 논문만 사용했다. **제품 전체 시장에 없는 기능을 발견했다는 문서가 아니다.** 특정 비교 대상의 공개 문서에서 확인된 기능·제약, 그리고 아직 확인되지 않은 부분을 분리한다. 현재 여과시간은 설계 문서 단계이므로 아래 후보의 효과·사용자 수요는 입증되지 않았다.

## 먼저 버려야 할 차별성 주장

| 주장 | 반례/근거 | 판단 |
| --- | --- | --- |
| 목적별 마스킹·토큰화 자체가 새롭다 | [Google Sensitive Data Protection](https://docs.cloud.google.com/sensitive-data-protection/docs/transformations-reference)은 비식별화 방식의 선택이 목적에 달린다고 설명하고, [조건부 필드 변환·레코드 억제](https://docs.cloud.google.com/sensitive-data-protection/docs/deidentify-sensitive-data)를 지원한다. | 신규성 주장 불가. |
| AI 호출을 중앙 게이트웨이로 보내고 DLP·감사를 붙이는 것이 새롭다 | [Cloudflare AI Gateway DLP](https://developers.cloudflare.com/ai-gateway/features/dlp/)는 요청/응답을 검사하고 차단하며, [Kong AI Gateway](https://developer.konghq.com/ai-gateway/ai-data-gov/)는 PII sanitizer·guardrail·audit을 제공한다. | 신규성 주장 불가. |
| 승인·상태 변경 시 재검사·반출 경로 통제가 독자적이다 | [Databricks Unity Gateway](https://docs.databricks.com/aws/en/ai-gateway/ai-governance)는 요청별 allow/deny/approval과 외부 모델 라우팅을 제공한다. [Gamut Gateway](https://gamut.titai.org/gamut-gateway)는 승인 목적·정확한 파라미터·현재 상태에 결속된 실행 권한과 실패 시 차단을 명시한다. | 중요한 안전 요건이지만 단독 차별성 아님. Gamut은 공급자 주장 문서이므로 배포 범위/성능까지 확인된 것은 아니다. |
| 기존 장애 AI는 근거/관계를 보존하지 못한다 | [Datadog Bits Investigation](https://docs.datadoghq.com/bits_ai/bits_investigation/investigate_issues/?lang_pref=en)은 로그·trace 등 다중 근거와 조사 단계·가설 트리를 보여 주고, [Azure Copilot Observability Agent](https://learn.microsoft.com/azure/azure-monitor/aiops/observability-agent-deep-investigations)는 근거와 추론 단계를 제시한다. | 틀린 일반화. |

## 후보 1 — 가장 방어 가능: 모델 전송 직전의 *사건 단위 공개 계약*

**비교 대상과 확인된 한계.** Azure Copilot Observability Agent의 공식 [데이터·거버넌스 FAQ](https://learn.microsoft.com/en-us/azure/azure-monitor/aiops/observability-agent-governance-faq)는 LLM에 공유되는 데이터를 scope·RBAC로 제어하며, 이미 범위에 들어온 리소스에서 **개별 telemetry table/field를 선택적으로 제외할 수 없다고 명시**한다. 이것은 Azure 제품의 현재 명시된 제약이지 모든 관측·AI 제품의 제약이 아니다. [Datadog Sensitive Data Scanner](https://docs.datadoghq.com/security/sensitive_data_scanner/)는 로그의 민감값을 redact/hash/mask할 수 있고 조직 내 Observability Pipeline에서 외부 반출 전에 처리할 수도 있으므로, “기존 제품은 로그를 보내기 전 가릴 수 없다”는 주장은 금지한다.

**여과시간의 좁은 개선안.** 합성 결제 장애 JSON에서 `INCIDENT_ANALYSIS`/`INCIDENT_REPORT_DRAFT`가 요구하는 **사건 단위 field·event·relation의 허용 목록**을 컴파일하고, 그 결과만 외부 AI에 전달한다. 단순히 `민감정보 패턴을 찾으면 가린다`가 아니라 `업무가 필요로 하는 사건 근거를 명시해, 그 밖의 정보는 기본적으로 보내지 않는다`가 핵심이다. 기존 로그 관측 시스템을 대체하거나 Azure 내부 동작을 수정하는 제품으로 설명하지 않는다. **선택한 AI로 로그를 넘기는 조직 자체 워크플로**의 전송 직전 경계를 구현하는 것이다.

**발표 가능한 문장.** “Azure의 장애 조사 AI는 공식 문서상 권한과 리소스 범위로 모델 입력을 제한하지만, 범위 안의 개별 로그 필드 제외는 지원하지 않습니다. 여과시간은 별도 AI 전달 경로에서 결제 장애 사건의 필드·이벤트·관계를 업무 계약별로 선택하고, 실제 전송 패키지를 보여 주겠습니다.” Azure가 할 수 없는 것을 여과시간이 모든 면에서 해결한다고 주장하지 않는다.

**반증 조건.** 현업 사용자에게 사건별 선택·승인 수요가 없거나 기존 pipeline/쿼리로 같은 일을 충분히 쉽게 하면 제품 차별성은 약하다. 승인된 패키지가 실제 AI 답변 근거를 잃으면 기술적 효용도 없다. 현재 [실험 계획](../spec/10_EXPERIMENT_PLAN.md)은 `INCIDENT_ANALYSIS` 품질 중심이므로 두 업무 모두의 우월성을 주장하려면 `REPORT_DRAFT` paired Gold도 필요하다.

## 후보 2 — 기술 데모로 강화 가능: *무엇을 뺐고 어떤 근거가 남았는지* 검증하는 패키지

**기존과 겹치는 부분.** [Google SDP 변환 상세 기록](https://docs.cloud.google.com/sensitive-data-protection/docs/transformation-details)은 변환 방식·조건·대상 필드·성공/오류·변환 바이트를 기록한다. [Datadog Bits](https://docs.datadoghq.com/bits_ai/bits_investigation/investigate_issues/?lang_pref=en)와 [Azure Agent](https://learn.microsoft.com/azure/azure-monitor/aiops/observability-agent-deep-investigations)는 조사 근거와 추론 경로를 보여 준다. [HG-InsightLog](https://aclanthology.org/2025.findings-acl.1214/) 등 원 논문도 장애 로그의 시간 순서·관계 보존을 다룬다. 따라서 `관계 보존`, `변환 기록`, `근거 설명` 각각은 새로운 아이디어가 아니다.

**남은 제품화 질문.** 하나의 반출 요청에서 `원본 사건 atom → 선택/변환 이유 → 남은 event/relation → 실제 전송 payload`를 연결하고, 선택된 AI에 보내기 **전에** 검토자가 업무 근거 손실과 공개 범위를 함께 확인할 수 있게 할 수 있는가? 이는 조사 AI의 사후 근거 제시나 DLP의 탐지 로그와 다른 사용자 시점이다. 다만 조사한 문서에서 이 **조합을 확인하지 못했다**는 뜻일 뿐, 모든 기존 제품에 없다는 뜻은 아니다.

**구현·검증 단위.** 패키지 manifest의 각 atom에 source ID, purpose rule version, action, reason, destination ceiling, transmitted 여부를 남긴다. **런타임에서는 Purpose 계약에 필수로 명시된** edge가 DROP되면 전송 전 경고 또는 차단한다. 별도 오프라인 평가에서만 합성 Gold에 대해 필수 event/edge 보존율과 금지/불필요 공개율을 측정한다. Gold를 런타임에서 읽지 않는다. 자동으로 `인과관계`를 추론했다고 주장하지 않고 원본의 관찰 관계만 전달한다. 새 관계 추출 알고리즘을 추가하는 것이 아니라 기존 Purpose 계약을 **검사 가능한 산출물**로 만드는 범위다.

**반증 조건.** manifest가 단순 UI 장식이거나 필수 근거 보존 검사가 사후 Gold를 몰래 참조하면 성립하지 않는다. 기존 도구로 몇 줄의 설정만으로 동등한 사전 검토를 제공할 수 있다면 통합 편의 이상의 차별성은 없다.

## 후보 3 — 단독 차별성은 약함: 원문 재저장 없이 승인·전송 증거 연결

**문서상 관찰.** [Cloudflare AI Gateway 로그](https://developers.cloudflare.com/ai-gateway/observability/logging/)는 prompt·response, model, provider 및 DLP action/매칭 ID를 기록한다. [Kong AI Gateway 감사 로그 문서](https://developer.konghq.com/ai-gateway/ai-audit-log-reference/)는 PII sanitizer의 `sanitized_items`에 원래 텍스트와 치환 텍스트가 포함된다고 설명한다. 이런 내용은 보안 검토를 위한 로그 자체가 민감해질 수 있다는 **설계 위험의 사례**다. Cloudflare/Kong의 모든 배포가 원문을 보존한다거나 보존을 끌 수 없다고 말하지 않는다. [Databricks Unity Gateway](https://docs.databricks.com/aws/en/ai-gateway/ai-governance)도 요청/응답 payload를 inference table에서 볼 수 있지만 설정·권한을 조직이 제어한다.

**여과시간에서 할 일.** 감사 화면은 원문 값을 복제하지 않고 원본·패키지 digest, purpose/policy/destination version, atom action, 승인자·결정·수신 ID를 결속한다. 원본을 볼 권한이 있는 내부 검토자는 별도 보호 저장소에서 필요 시 대조한다. 이는 `감사 로그까지 최소 공개`하는 구체적 안전 선택이며, 현재 [정책 명세](../spec/05_POLICY_SPEC.md)의 fingerprint/manifest 설계와 맞는다. **암호학적 불변 원장**이나 외부 공급자의 실제 수신 내용을 증명하는 것처럼 과장하지 않는다.

**판정.** 안전 데모와 설계 품질에는 좋지만, Databricks/Gamut 같은 승인·감사·payload 결속 사례 때문에 단독 핵심 차별점으로는 약하다. 후보 1의 실행 증거로만 묶는 편이 낫다.

## 권장 포지셔닝과 필요한 결정

**가장 정직하고 선명한 포지션은 후보 1+2:** “기존 DLP/Gateway/장애 AI를 새로 발명하지 않는다. 특정 결제 장애 사건을 외부 AI에 보낼 때 **업무별 사건 근거 계약을 전송 직전에 적용하고, 보내는 정보와 잃는 근거를 함께 검토 가능한 패키지로 만든다.**” 비교 대상은 `모든 기존 기술`이 아니라 (1) Azure의 문서화된 scope/RBAC 제약, (2) 강한 공통 패키지, (3) 팀이 직접 설정한 Google SDP/AI Gateway 조합 중 실현 가능한 구성이다. (3)을 실제로 구현·검증하지 않았다면 성능 비교를 주장하지 않는다.

이 포지션도 **시장 독점적 기능의 확인**은 아니다. 상용 도구를 설정·연동하면 유사하게 만들 수 있고, 목적별 최소화·관계 보존은 선행연구가 있다. 여과시간의 캡스톤 기여는 한정된 금융 장애 형식에서 `업무 계약 → 사건 근거 패키지 → 승인된 실제 전송 → 재현 가능한 공개/근거 평가`를 한 흐름으로 구현하고 그 비용/효과를 확인하는 데 있다. 발표 전 금융 IT 운영자와 보안 검토자에게 “현재 어느 단계에서 누가 AI 입력을 선택·승인하는가, 무엇이 귀찮거나 불가능한가”를 확인해야 한다. 그 수요가 확인되지 않으면 **서비스 차별성은 아직 없다**고 표시한다.
