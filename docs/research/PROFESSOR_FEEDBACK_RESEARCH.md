# 여과시간 선행 기술·애플리케이션 조사와 발표 방향

> **현재 포지셔닝:** 최근 논의를 반영한 한 페이지 결론은 [최종 차별성 정의](./FINAL_DIFFERENTIATION.md)를 따른다. 이 문서는 그 결론의 상세 근거다.

> 조사일: 2026-09-22. 범위: 합성 금융 결제 장애 로그를 AI에 보내기 전의 탐지·변환·정책·전송, 그리고 AI 장애 분석. 출처는 표준/정부 문서, 원 논문, 제조사 공식 문서로 제한했다. 제품 기능은 문서 확인 시점의 설명이며, `이번 문서에서 확인 못함`은 `제품에 없음`이 아니다. 아래 장단점 중 `적용 판단`은 출처의 사실을 여과시간의 범위에 대입한 **우리의 해석**이다.

## 1. 교수님 피드백에 대한 결론

**여과시간은 새 마스킹 알고리즘이나 최초의 목적별 보안 기술이 아니다.** [OASIS XACML Privacy Profile](https://docs.oasis-open.org/xacml/3.0/xacml-3.0-privacy-v1-spec-cs-01-en.html)은 데이터 수집 목적과 접근 목적을 비교하는 정책을 2010년에 규정했다. [PII-Bench (Shen et al., ACL 2026)](https://aclanthology.org/2026.acl-long.227/)는 질의와 무관한 PII를 가리는 평가를 제안했고, [Zhou·Mireshghallah·Li, ICLR 2026](https://arxiv.org/abs/2510.03662)은 답변 효용을 유지하면서 공개를 최소화하는 문제를 직접 연구했다. [ProSan (IEEE TIFS 2026)](https://ieeexplore.ieee.org/document/11352994/)도 작업에 무관한 민감정보를 줄이는 prompt sanitizer다. 따라서 `기존 기술은 모두 고정 마스킹`, `목적별 마스킹은 우리가 최초`는 모두 철회해야 한다.

여과시간의 가장 가까운 위치는 **AI Gateway 앞 또는 내부에 들어가는, 결제 장애 로그용 작업별 데이터 최소화·정책 집행 애플리케이션**이다. 기여 후보는 이미 알려진 원리를 좁은 업무에 구체화하는 데 있다. 승인된 두 Purpose가 필요한 *field·event·relation*을 명시하고, 공통 탐지/Secret 제거/토큰화를 거쳐 패키지를 만든 뒤, 목적지 허용 상한·승인 결속·실제 전송 경로까지 검증한다. 이 설계가 좋은지는 아직 결과가 없으며, 강한 고정 정책과 동등 조건의 실험으로 판단한다. 선행 연구의 수학적 최적해, 기존 상용 제품 대비 우월성, 실제 금융기관 도입 가능성을 주장하지 않는다.

## 2. 기술별 장단점과 여과시간의 위치

| 기존 기술/담당 | 공식 근거로 확인한 강점 | 한계·도입 조건 (`적용 판단` 별도 표시) | 여과시간과의 관계 |
| --- | --- | --- | --- |
| **Microsoft Presidio**: 탐지·익명화 SDK | PII recognizer와 redact/replace/hash/mask/encrypt operator, 사용자 정의 recognizer/operator를 제공한다. 자체 환경에서 탐지·변환 엔진을 구성하기 좋다. [공식 저장소](https://github.com/microsoft/presidio), [Anonymizer](https://microsoft.github.io/presidio/anonymizer/) | 자동 탐지가 모든 PII를 찾는다는 보장이 없다(공식 경고). **적용 판단:** 로그의 업무 필요 event/relation 선택, 승인/목적지 통제는 별도 애플리케이션이 조합해야 한다. | 대체 대상보다 공통 detector·transform 후보. 여과시간이 이를 사용해도 차별성은 detector에 있지 않다. |
| **Google Sensitive Data Protection**: 관리형 DLP·비식별화 | structured data의 field/record 조건부 transformation, masking·redaction·hash·deterministic encryption, 재사용 template을 제공한다. [Transformation reference](https://docs.cloud.google.com/sensitive-data-protection/docs/transformations-reference), [Templates](https://docs.cloud.google.com/sensitive-data-protection/docs/creating-templates-deid) | Cloud 서비스를 사용할 수 있어야 하고, 조직이 정책·키·비용을 관리해야 한다. **적용 판단:** Purpose별 template 선택은 충분히 구현 가능하므로 `목적별 변환 불가`라고 말할 수 없다. 장애 분석용 event/relation 계약과 AI 실행 워크플로는 호출 애플리케이션의 설계 문제다. | 가장 강한 configurable transformation 비교 대상. 여과시간은 새 변환보다 특정 장애 목적의 패키지·실행 계약을 실험한다. |
| **Microsoft Purview DLP**: 사용자 행위·데이터 유출 제어 | 민감정보 유형·라벨·사용자·위치 조건으로 경고/차단/감사하고, 지원 환경에서는 생성형 AI 사이트로의 paste/upload도 통제한다. [DLP 개요](https://learn.microsoft.com/en-us/purview/dlp-learn-about-dlp), [AI 보호](https://learn.microsoft.com/en-us/purview/ai-microsoft-purview) | 지원 조건·동작은 Microsoft 365, endpoint, browser 등 workload마다 다르다. **적용 판단:** 전사 DLP가 금융 JSON 장애 로그의 분석용 관계 보존 패키지와 답변 효용을 자동 설계해 주지는 않는다. | 조직 차원의 유출 방지와 겹치지만, 여과시간은 한 건의 AI 업무 입력을 생성·검토하는 애플리케이션이다. 병행 가능하다. |
| **Cloudflare AI Gateway DLP**: AI proxy·검사 | 여러 AI 제공자 앞에 정책을 두고 request/response를 검사하며 DLP match를 flag/block할 수 있다. [공식 DLP 문서](https://developers.cloudflare.com/ai-gateway/features/dlp/) | 공식 문서상 binary/base64 일부는 검사 대상 밖이며 response streaming 검사에는 buffering이 따른다. **적용 판단:** flag/block이 주 동작이므로 event/relation이 남는 분석용 package는 호출 앱에서 준비해야 한다. | 중앙 AI 전송 경계라는 구조가 가깝다. 여과시간은 목적 계약에 따른 입력 재구성과 승인 결속을 붙인다. |
| **Kong AI Gateway + AI PII Sanitizer**: AI proxy·sanitization | 인증/라우팅/정책, 요청·응답 PII placeholder·synthetic replacement, 설정에 따라 복원 등을 제공한다. [Sanitizer](https://developer.konghq.com/plugins/ai-sanitizer/), [AI Gateway](https://developer.konghq.com/ai-gateway/) | Sanitizer는 AI 라이선스 및 외부 PII 서비스가 필요한 구성이다. **적용 판단:** custom policy와 애플리케이션 코드를 결합하면 여과시간의 상당 부분을 만들 수 있어 `Kong으로 불가능한 기능` 주장은 부정확하다. | **제품군으로는 가장 가까운 Gateway 상대.** 여과시간은 gateway 자체보다 결제 장애용 package contract와 평가에 초점을 둔다. |
| **Private Endpoint / PrivateLink**: 네트워크 경계 | 승인된 사설 경로로 endpoint에 접근하게 한다. [AWS Bedrock PrivateLink](https://docs.aws.amazon.com/bedrock/latest/userguide/usingVPC.html), [Azure Private Link](https://learn.microsoft.com/en-us/azure/private-link/private-endpoint-overview) | **적용 판단:** 네트워크 경로가 사설이어도 목적 외 데이터를 보낼 수 있다. 반대로 최소 패키지만 만들고 직접 외부 경로가 열려 있으면 우회가 가능하다. | 데이터 최소화와 별개인 보완 통제. Docker 격리는 상용 PrivateLink와 동급이 아닌 PoC 경로 테스트다. |

**애플리케이션에 따라 달라지는 점:** Microsoft 365 문서 유출을 막는 상황에는 Purview가 자연스럽고, 텍스트 PII만 바꿔 AI API를 호출한다면 Kong/Presidio 조합으로 충분할 수 있다. 이미 Datadog 또는 Azure에 모니터링 데이터와 접근권한이 모여 있으면 아래의 장애 분석 AI가 더 넓은 진단 문맥을 얻는다. 여과시간이 다루는 상황은 *금융 결제 장애 로그 일부를 등록된 AI로 보내야 하지만 원문 반출을 허용할 수 없는 경우*로 좁다. 외부 AI 전송 자체가 금지된 조직이라면 여과시간의 T1 경로는 쓸 수 없고 조직 통제 T2만 검토해야 한다. 이는 제품 배치에 관한 해석이며 현업 인터뷰로 확인해야 한다.

## 3. 가장 가까운 논문과 실제 장애 AI

| 선행 사례 | 누가, 어디서, 무엇을 했나 | 여과시간에 주는 교훈·한계 |
| --- | --- | --- |
| [XACML Privacy Profile](https://docs.oasis-open.org/xacml/3.0/xacml-3.0-privacy-v1-spec-cs-01-en.html), OASIS, 2010 | 수집 목적과 접근 목적을 속성으로 표시하고 목적이 맞지 않으면 접근 거부하는 정책 프로필. | 목적 기반 정책은 기존 기술이다. 여과시간의 차이는 허용/거부만이 아니라 허용된 목적의 *AI 입력 내용*을 만드는 구현·검증에 둔다. |
| [NIST SP 800-188](https://csrc.nist.gov/pubs/sp/800/188/final), NIST, 2023 | 비식별화의 공개 위험과 데이터 유용성을 함께 평가하고 거버넌스를 요구한다. | 마스킹 자체가 안전 보증은 아니다. 여과시간은 개인정보 재식별 불가를 주장할 수 없다. |
| [RCACopilot](https://jun-zeng.github.io/file/owl_paper.pdf), Chen et al., EuroSys 2024 | Microsoft 클라우드 incident의 진단 자료를 모으고 LLM으로 원인 범주와 설명을 생성했다. 로그/stack trace만으로는 부족하고 다른 진단 자료가 필요할 수 있으며 관련 정보만 모으는 handler도 사용한다. | **`장애 분석에 필요한 자료를 골라 모은다`도 새 발상이 아니다.** 관련 event/relation을 지나치게 지우면 원인 분석이 실패할 수 있다. 이 논문은 여과시간의 외부 반출 안전성을 검증하지 않는다. |
| [PII-Bench](https://aclanthology.org/2026.acl-long.227/), Shen et al., ACL 2026 | 질의와 무관한 PII masking을 평가하는 benchmark. 2,842개 예시와 7개 PII 유형을 포함하고, 모델이 질의 관련성을 판단하는 일이 어렵다고 보고한다. | `질의 관련성에 따른 마스킹`은 선행연구가 있다. 여과시간은 범용 자연어 질의보다 승인·버전 고정된 두 Purpose를 사용해 정책의 재현성을 우선한다. PII-Bench 결과를 금융 장애 로그 성능의 증거로 옮기면 안 된다. |
| [Operationalizing Data Minimization for Privacy-Preserving LLM Prompting](https://arxiv.org/abs/2510.03662), Zhou·Mireshghallah·Li, ICLR 2026 공개본 | prompt와 답변 모델에 대해 효용을 유지하는 낮은 공개 수준을 탐색한다. 공개본은 대화·법률·의료 QA 데이터와 9개 LLM을 평가한다. | `적은 정보, 같은 품질`은 이미 연구 질문이다. 여과시간은 이 논문의 최소 공개 *최적해*를 달성한다고 주장하지 않는다. 고정된 구조화 로그 계약의 보안 경계와 실험이 범위다. 모델·도메인마다 허용 가능한 축약량이 다를 수 있다. |
| [ProSan](https://ieeexplore.ieee.org/document/11352994/), IEEE TIFS 2026 | 작업과 무관한 민감정보를 줄이면서 prompt utility/readability를 보존하는 sanitizer를 제안·평가한다. | 작업별 보호도 기존 방법이다. 여과시간은 자유형 prompt sanitization보다 구조화 incident의 필드·관찰된 사건 관계와 승인된 전송 경로에 초점을 둔다. |
| [Datadog Bits Investigation](https://docs.datadoghq.com/bits_ai/bits_investigation/investigate_issues/?lang_pref=en), Datadog 제품 | logs·traces·metrics 등 여러 telemetry를 조회하며 장애 가설을 검증하고 근거 있는 결론 또는 inconclusive를 표시한다. [Incident AI](https://docs.datadoghq.com/incident_response/incident_management/investigate/incident_ai/)는 타임라인과 연결 telemetry도 쓴다. | **AI 장애 분석 자체는 이미 제품화됐다.** 여과시간은 RCA 엔진의 우수성을 주장하지 않고, 내부 로그를 선택된 AI endpoint로 넘기기 전 공개 범위를 통제하는 단계다. Datadog telemetry/권한이 이미 있는 조직에서는 Bits가 더 적절할 수 있다. |
| [Azure Copilot Observability Agent](https://learn.microsoft.com/en-us/azure/azure-monitor/aiops/observability-agent-deep-investigations), Microsoft 제품 | Azure alert·log·application/infrastructure 신호를 묶어 심층 조사 보고서를 만든다. 문서는 데이터 보관과 접근 범위도 설명한다. | 기존 플랫폼 내부에서 관측 자료를 활용하는 접근. 여과시간의 대상은 특정 플랫폼 진단 기능의 대체가 아니라 외부/관리형 AI에 입력을 전달할 때의 통제다. 플랫폼 권한·데이터 위치에 따라 적합성이 달라진다. |

논문과 제품의 ‘한계’는 서로 다르다. 논문의 benchmark는 금융 결제 장애에 그대로 일반화되지 않는다. 제품 문서에서 특정 조합이 설명되지 않았다고 실제 구현이 불가능한 것도 아니다. 반대로 여과시간의 설계 문서만으로 실제 효용이나 보안 우월성을 입증한 것도 아니다.

## 4. 발표에서 사용할 위치·차별점

**보안 이득의 전제:** `업무에 불필요하다`와 `공개하면 위험하다`는 다른 판단이다. 이미 공개된 오류 코드나 무해한 상태값을 더 가려도 기밀성 이득은 거의 없고, 답변 근거만 잃을 수 있다. 반면 직접 식별자는 아니어도 내부 호스트명·IP, 시스템 구성, 다른 거래와의 연결 관계처럼 조직이 외부 공개를 제한하는 정보라면 목적에 필요 없는 부분을 빼는 것이 실제 공개 범위를 줄인다. 따라서 *삭제한 총량*을 보안성으로 부르지 않고, 사전에 분류한 민감·내부·추론 가능한 정보의 잔존량과 업무 효용을 함께 본다. 이 구분은 [NIST의 위험·유용성 평가 지침](https://csrc.nist.gov/pubs/sp/800/188/final)과 목적에 충분하지만 필요한 범위로 제한한다는 [ICO의 데이터 최소화 설명](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/data-protection-principles/a-guide-to-the-data-protection-principles/data-minimisation/)에 부합한다. 후자는 개인정보에 대한 지침이므로 모든 일반 로그 필드에 법적 의무가 적용된다는 뜻은 아니다.

> **위치:** 기존 DLP·비식별화 기술을 활용하고 AI Gateway의 통제 경로와 맞물리는, 금융 결제 장애 로그용 Purpose 계약 기반 데이터 최소화 애플리케이션.
>
> **개선 가설:** 동일한 탐지·Secret 제거·토큰화·잔존 검사·모델·프롬프트·네트워크를 쓰더라도, 승인된 `INCIDENT_ANALYSIS`와 `INCIDENT_REPORT_DRAFT`가 각각 필요한 field·event·relation만 남기면 하나의 강한 고정 안전 패키지보다 목적 외 공개를 줄이면서 필요한 장애 분석 근거를 유지할 수 있는가?

기술적 초점은 다음 네 가지다. 모두 **구현 목표/검증 가설**이며 성능 결과가 아니다.

1. **명시적 계약:** Purpose version마다 허용 field·event·관찰된 relation을 정의하고 패키지와 transformation manifest를 재현한다. `시간상 먼저 발생`을 자동으로 `인과관계`라고 부르지 않는다.
2. **전송 전 집행:** Trust Tier는 목적이 요구한 데이터의 허용 상한을 정하고, hard Secret 금지·residual scan·승인 fingerprint·상태 전이를 통과해야 실행한다. T2도 목적 최소화를 적용한다.
3. **경로 증거:** 내부 Client의 직접 외부 연결 실패와 승인된 Dispatcher 경로 성공을 실제 receiver 로그로 보인다. 이는 Docker PoC의 제한된 보안 주장이다.
4. **공정한 paired 평가:** 같은 사건·같은 AI 조건에서 강한 고정 정책과 Purpose 계약의 *선택 규칙*만 다르게 한다. 공개량과 답변 근거를 함께 보고 실패 건도 포함한다.

**중요한 비교 한계:** Strong Fixed Policy(B3)는 연구를 위한 **구성된 통제군**이다. Presidio·Google·Kong·Datadog 전체 또는 ‘현재 산업의 기본 수준’을 대표하지 않는다. B3보다 좋아도 기존의 모든 조건부 정책이나 최신 task-aware sanitizer보다 낫다는 결론은 낼 수 없다. 발표에는 `강한 고정 정책 대비 해당 조건에서의 차이`라고 적는다.

**관찰된 흐름과 미래 가설:** Datadog과 Azure 공식 문서는 이미 AI가 장애 조사에서 logs/metrics/trace를 사용하는 제품 사례를 보여 준다. 이런 통합이 늘수록 입력 데이터의 사용 목적·전송 경계를 설명해야 한다는 것이 **우리의 설계 가설**이다. ‘앞으로 모든 AI가 외부로 로그를 보낸다’ 또는 ‘규제가 반드시 강화된다’는 예측은 하지 않는다.

### 발표 45초 스토리 초안

> “민감정보 탐지와 비식별화는 Presidio와 Google이 이미 잘하고, Kong 같은 AI Gateway는 AI 호출 정책과 경로를 통제합니다. Datadog과 Azure는 AI로 장애도 조사합니다. 또 최근 논문은 작업에 필요한 정보만 남기는 문제까지 다루므로, 저희는 목적별 마스킹 자체가 최초라고 말하지 않겠습니다. 여과시간은 금융 결제 장애라는 좁은 상황에서 두 승인된 업무 목적이 필요한 필드·이벤트·관계를 패키지로 만들고, 그 패키지에 정책·승인·전송 경로를 묶습니다. 같은 보안 처리와 AI 조건을 쓰는 강한 고정 정책과 비교해 불필요한 공개가 줄고 분석 근거가 유지되는지 검증하겠습니다. 결과가 나오기 전에는 개선이라고 단정하지 않겠습니다.”

## 5. 검증 설계에 바로 반영할 것

| 질문 | 사전 고정할 방법 | 반증/한계 |
| --- | --- | --- |
| 무엇을 덜 보냈나? | 합성 원본의 field/event/relation atom과 금지·불필요 공개 atom을 독립 Gold로 기록. B3와 Purpose package의 per-case 공개 atom 수, 비율, 차이 모두 제시. 정보 유형별 공개 위험 분류도 미리 고정한다. | token 수나 무해한 필드만 줄면 보안 개선이라고 볼 수 없다. IP·계정·토폴로지 등 정보 유형별 잔존도 분리한다. |
| 답변 품질이 유지됐나? | 같은 incident, model snapshot, prompt, decoding, 반복 수를 적용. root-cause code, 필수 event/relation 근거, 근거 없는 주장률을 condition-blind로 채점. | 합성 incident의 Gold와 두 Purpose가 팀 설계이므로 내부 블라인드여도 완전한 독립 검증은 아니다. |
| 공정한 상대인가? | 공통 parser/detector/Secret 제거/tokenization/residual scan/network. B3는 모든 incident 업무에 쓰는 하나의 *강한* 패키지로 Week 2에 동결. | B3 승리는 상용 DLP/Gateway 전체에 대한 승리가 아니다. 두 Purpose를 함께 평가해야 특정 Purpose에 유리하게 B3를 맞추지 않는다. |
| 정책이 실제 전송을 막나? | T0·Secret 잔존·검사 실패·승인 변경·직접 egress의 receiver 수신 0을 기록. | Docker PoC 밖의 실제 금융망 통제, 모델 사업자의 보관·학습 정책, 재식별 위험을 입증하지 못한다. |

현 [Experiment Plan](../spec/10_EXPERIMENT_PLAN.md)의 Experiment B는 `INCIDENT_ANALYSIS` 중심이다. 발표에서 **두 Purpose 모두의 효용 개선**을 주장하려면 `INCIDENT_REPORT_DRAFT`도 Gold와 paired 결과를 보고해야 한다. 일정상 못하면 개선 주장도 분석 목적 하나로 한정하고, 다른 목적은 계약대로 패키지가 다른지(Experiment A)만 시연한다. 이 범위 구분은 연구 해석을 위한 권고이며 기존 실험 결과를 바꾼 것이 아니다.

## 6. 근거 원장과 남은 증거 공백

등급: A=표준/정부, B=학회·심사 논문, C=제조사 공식 문서. `논문 결과`를 `여과시간 효과`의 직접 근거로 사용하지 않는다.

| ID | 발표에서 쓰는 주장 | 왜 필요한가 | 근거·연도/대상 | 등급 | 정확한 한계 | 사용할 위치 |
| --- | --- | --- | --- | :---: | --- | --- |
| E-01 | 목적 기반 접근 정책은 기존 기술 | 최초성 과장 방지 | [OASIS XACML Privacy Profile](https://docs.oasis-open.org/xacml/3.0/xacml-3.0-privacy-v1-spec-cs-01-en.html), 2010, 정책 표준 | A | AI prompt package는 다루지 않음 | 선행기술 슬라이드 |
| E-02 | 비식별화는 위험과 유용성을 함께 봐야 함 | 지표 선택 근거 | [NIST SP 800-188](https://csrc.nist.gov/pubs/sp/800/188/final), 2023, 정부 데이터 | A | 금융 로그·LLM을 직접 평가하지 않음 | 문제/평가 슬라이드 |
| E-03 | 질의 관련 PII 구별이 연구 주제 | 연구 신규성 경계 | [PII-Bench](https://aclanthology.org/2026.acl-long.227/), ACL 2026, 2,842 예시 | B | 일반 질의/PII 중심, 장애 관계 지표가 아님 | 논문 비교 슬라이드 |
| E-04 | 효용을 보존하는 최소 공개도 선행연구 | 가설의 위치 | [Zhou et al.](https://arxiv.org/abs/2510.03662), ICLR 2026 공개본, 4종 데이터/9개 모델 | B | 금융 장애 로그나 전송 정책을 직접 평가하지 않음 | 논문 비교 슬라이드 |
| E-05 | 작업별 prompt sanitizer도 존재 | 유사연구 인정 | [ProSan](https://ieeexplore.ieee.org/document/11352994/), IEEE TIFS 2026, QA/요약/코드 | B | 구조화 incident package/망 경계와 대상이 다름 | 논문 비교 슬라이드 |
| E-06 | AI RCA는 이미 연구·제품화 | 앱 비교 필수 | [RCACopilot](https://jun-zeng.github.io/file/owl_paper.pdf), EuroSys 2024; [Datadog Bits](https://docs.datadoghq.com/bits_ai/bits_investigation/investigate_issues/?lang_pref=en), 제품 문서 | B/C | 조직·telemetry 환경마다 적용성 다름 | 기존 앱 슬라이드 |
| E-07 | DLP·AI Gateway에 이미 강한 기능이 있음 | 허수아비 비교 방지 | [Google SDP](https://docs.cloud.google.com/sensitive-data-protection/docs/transformations-reference), [Kong Sanitizer](https://developer.konghq.com/plugins/ai-sanitizer/), 공식 문서 | C | 전체 기능·구성을 조사한 시장 전수조사가 아님 | 기술 비교표 |
| E-08 | 여과시간이 B3보다 나은가 | 프로젝트 핵심 주장 | **아직 증거 없음**. [사전 실험 계획](../spec/10_EXPERIMENT_PLAN.md)에 따라 Locked paired 결과 필요 | — | 합성 18건은 탐색적 PoC, 일반화·유의성 주장 불가 | 결과 슬라이드, 실행 뒤만 |
| E-09 | 사용자가 실제 수동 정리·포기를 겪는가 | 문제 정의 검증 | **현업 인터뷰 필요**: 금융 IT 개발/운영·보안 검토 담당자 | — | 인터뷰 전에는 사용자 pain을 사실처럼 말하지 않음 | 문제 슬라이드 |

**인터뷰 질문:** 실제 AI 투입 전 누가 무엇을 지우는지; AI 사용을 포기한 사건이 있는지; Private/Enterprise AI 하나로 충분한지; 업무별 허용 필드·관계 계약을 누가 관리할 수 있는지; 고정 정책 때문에 장애 근거를 잃은 사례가 있는지. 반박 사례가 나오면 대상 조직과 주장 범위를 줄인다.

**발표 금지 표현:** `업계 최초 목적별 마스킹`, `기존 기술은 위험정보만 고정 마스킹`, `기존 제품은 관계를 보존할 수 없음`, `AI 답변 품질 향상 입증`, `금융망 보안 문제 해결`. 대신 선행 기술을 인정하고, 범위를 제한한 실험 질문과 실제 결과만 말한다.
