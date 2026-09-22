# 여과시간 기존 기술 비교 조사

> **후속 조사:** 논문과 실제 장애 분석 애플리케이션까지 포함한 [교수님 피드백 대응 조사](./PROFESSOR_FEEDBACK_RESEARCH.md)를 함께 읽는다. 특히 `목적별 최소화` 자체의 선행 연구, Datadog/Azure 장애 AI, Strong Fixed Policy의 비교 범위에 관한 판단은 후속 조사가 우선한다.

> 조사 기준일: 2026-09-16  
> 범위: 민감정보 탐지·비식별화, AI Gateway, 사설/관리형 AI 네트워크 경계  
> 방법: 공식 제품 문서와 공식 오픈소스 저장소에서 확인되는 기능만 사실로 기록하고, 여과시간과의 비교는 별도 `비교 해석`으로 표시한다.

## 1. 먼저 내릴 결론

여과시간을 `PII 탐지`, `마스킹`, `AI 라우팅`, `Private Endpoint` 자체가 새로운 기술이라고 설명하면 안 된다. Microsoft Presidio와 Google Cloud Sensitive Data Protection은 탐지·마스킹·가명처리 기능을 제공하고, Microsoft Purview DLP는 사용자·앱·장치·위치를 고려한 DLP 정책을 제공한다. Cloudflare와 Kong의 AI Gateway는 이미 AI 요청의 DLP, 정책 집행, 인증, 라우팅, 관찰성을 제공한다. Azure OpenAI Private Endpoint는 인터넷에 노출되지 않는 전용 네트워크 경로를 구성할 수 있다.

여과시간이 검증해야 할 차이는 다음 한 문장이다.

> 동일한 탐지·Secret 제거·tokenization·residual scan·모델·프롬프트·네트워크 조건에서, 승인된 업무 목적이 필요한 field·event·relation을 선택해 만든 패키지가 하나의 강한 고정 정책보다 불필요한 공개를 줄이면서 장애 분석 근거를 보존하는가?

이 문장은 시장 전체에 존재하지 않는 기능이라고 단정하는 `제품 신규성 주장`이 아니다. 이번에 확인한 공식 문서에서 직접 입증되지 않은 조합을 여과시간의 **실험 가설**로 좁힌 것이다.

## 2. 비교 층위

| 층위 | 기존 기술이 이미 잘하는 일 | 여과시간이 추가로 검증할 일 |
| --- | --- | --- |
| 탐지·비식별화 | PII·Secret 탐지, redact/mask/replace/encrypt/tokenize | 장애 업무별 field뿐 아니라 event·relation을 포함한 최소 패키지 구성 |
| AI Gateway | 인증, provider proxy, DLP/guardrail, rate limit, logging, routing | Purpose version과 package manifest를 정책·승인·감사에 결속 |
| 네트워크 경계 | Private Link/VNet 또는 gateway data plane으로 승인 경로 구성 | Docker PoC에서 내부 Client의 직접 외부 연결 실패와 승인 경로 성공을 실제 수신 로그로 증명 |
| 평가 | 개별 제품 기능 및 운영 지표 | Strong Fixed Policy와 Purpose별 selection만 다르게 둔 paired evaluation |

## 3. PII/DLP 및 비식별화 기술

### 3.1 Microsoft Presidio

#### 공식 문서에서 확인한 기능

- Presidio는 텍스트·이미지·구조화 데이터를 대상으로 PII를 탐지하고 익명화하는 오픈소스 SDK다. Analyzer는 predefined/custom recognizer와 NLP를 사용하고, Anonymizer는 `redact`, `replace`, `hash`, `mask`, `encrypt` 같은 연산자를 적용할 수 있다. 공식 저장소는 결과가 자동으로 안전하다고 보장하지 않으며 추가 보호가 필요하다고 경고한다. ([Presidio 공식 저장소](https://github.com/microsoft/presidio), [Anonymizer 문서](https://microsoft.github.io/presidio/anonymizer/))
- 동일 원문을 가명으로 바꾸고 나중에 복원하는 deanonymization 예제와 사용자 정의 연산자 확장 지점이 있다. ([Deanonymization 예제](https://microsoft.github.io/presidio/samples/python/pseudonymization/), [Custom operators](https://microsoft.github.io/presidio/anonymizer/adding_operators/))
- hash operator는 caller가 salt를 관리하여 호출·레코드 간 같은 값을 같은 hash로 유지할 수 있다. Presidio는 stateful session을 유지하지 않으므로 referential integrity에 필요한 상태·salt 관리는 사용자의 책임이다. ([Anonymizer 문서](https://microsoft.github.io/presidio/anonymizer/))

#### 장점

- 자체 환경에서 실행하고 recognizer·operator를 확장할 수 있어 합성 금융 로그 PoC의 detector와 transformation prototype에 적합하다.
- 탐지와 변환이 분리되어 있어 동일 detector 결과를 B3와 여과시간에 공유하기 쉽다.

#### 한계

- **공식 경고:** 자동 탐지가 모든 민감정보를 찾아낸다고 보장하지 않으며, Presidio 저장소는 추가 시스템과 보호 수단을 함께 사용하라고 명시한다. 따라서 residual scan과 fail-closed가 필요하다. ([Presidio 공식 저장소](https://github.com/microsoft/presidio))
- **공식 주의:** 일관된 hash를 위한 salt는 최소 128-bit를 권고하며 출력에 포함하면 안 된다. salt 보관은 referential integrity를 주는 대신 재식별 위험을 만들 수 있다. ([Anonymizer 문서](https://microsoft.github.io/presidio/anonymizer/))
- **비교 해석:** 확인한 개요·익명화 문서는 PII entity와 operator 중심이다. 승인된 Incident Purpose가 field·event·relation 계약을 선택하고 Trust ceiling과 결합하는 workflow는 이 문서들에서 확인하지 못했다. 이것은 Presidio 전체에 해당 기능이 없다는 단정이 아니다.

#### 여과시간과 겹치는 기능

PII/Secret classification, DROP·MASK·HASH·ENCRYPT 계열 변환, custom recognizer/operator가 겹친다. 따라서 여과시간은 Presidio와 탐지 성능 경쟁을 핵심 차별점으로 삼아서는 안 된다.

### 3.2 Google Cloud Sensitive Data Protection

#### 공식 문서에서 확인한 기능

- Sensitive Data Protection은 텍스트·구조화 데이터·이미지에서 sensitive data를 검사하는 `infoType` detector와 de-identification transformation을 제공한다. masking, redaction, replacement, bucketing, date shifting, deterministic encryption 및 cryptographic hashing 등을 구성할 수 있다. ([De-identification 개요](https://cloud.google.com/sensitive-data-protection/docs/deidentify-sensitive-data), [Transformation reference](https://cloud.google.com/sensitive-data-protection/docs/transformations-reference))
- 저장소를 만들지 않고 content method로 payload를 검사하거나 변환할 수 있고, inspection template과 de-identification template로 설정을 재사용할 수 있다. structured table은 특정 field transformation, 다른 column 값에 따른 `RecordCondition`, record suppression을 지원한다. ([Inspecting content](https://cloud.google.com/sensitive-data-protection/docs/inspecting-text), [De-identification templates](https://docs.cloud.google.com/sensitive-data-protection/docs/creating-templates-deid), [Transformation reference](https://docs.cloud.google.com/sensitive-data-protection/docs/transformations-reference))

#### 장점

- 다수의 사전 정의 detector와 field별·조건부 구조화 데이터 변환을 관리형 서비스로 제공해 직접 detector를 유지하는 부담을 줄일 수 있다.
- reversible 또는 deterministic transformation을 활용하면 동일 값의 관계를 일정 범위에서 유지하는 설계가 가능하다.

#### 한계

- **공식 주의:** de-identification transformation 선택은 데이터 종류와 비식별화 목적에 따라 달라지며, 암호화 변환은 Cloud KMS 키 구성과 권한 관리가 필요하다. 즉 `목적에 따라 변환을 다르게 한다`는 일반 개념은 Google 문서에도 명시되어 있다. ([De-identification 개요](https://cloud.google.com/sensitive-data-protection/docs/deidentify-sensitive-data), [Transformation reference](https://cloud.google.com/sensitive-data-protection/docs/transformations-reference))
- **공식 제한:** direct content inspection/de-identification request는 payload와 finding 수 등에 제한이 있다. inspect 요청은 최대 0.5 MB, findings는 요청당 최대 3,000이며 초과 시 임의 subset이 반환될 수 있어 작은 batch를 권고한다. detector를 명시하지 않으면 기본 infoType 목록이 시간에 따라 바뀔 수 있으므로 재현 가능한 실험은 detector 목록을 고정해야 한다. ([Limits](https://docs.cloud.google.com/sensitive-data-protection/limits), [InspectConfig](https://docs.cloud.google.com/sensitive-data-protection/docs/reference/rest/v2/InspectConfig))
- **비교 해석:** 확인한 문서는 detector, infoType, field transformation과 template을 설명한다. Incident Purpose별 event graph/relation 선택, 승인 fingerprint, AI 실행 상태 전이는 확인 범위의 중심 기능이 아니다.

#### 여과시간과 겹치는 기능

분류, masking/redaction/replacement, generalization 계열 변환, deterministic 관계 보존, field별·조건부 template이 겹친다. 호출자가 Purpose별 template을 선택할 수 있으므로 `Google SDP는 목적별 처리를 할 수 없다`고 말해서는 안 된다. CASE_SCOPED_TOKENIZE는 관리형 암호화 변환보다 작은 PoC 구현이며, 더 강한 익명화 기술이라고 주장할 수 없다.

### 3.3 Microsoft Purview Data Loss Prevention

#### 공식 문서에서 확인한 기능

- Purview DLP는 sensitive information type, sensitivity label, retention label 등의 조건을 사용해 Exchange, SharePoint, OneDrive, Teams, endpoint device와 일부 cloud app 위치에 정책을 적용한다. 정책은 제한, 차단, 경고, 사용자 override, alert와 incident report 같은 action을 구성할 수 있다. ([Purview DLP 개요](https://learn.microsoft.com/en-us/purview/dlp-learn-about-dlp), [DLP 정책 설계](https://learn.microsoft.com/en-us/purview/dlp-policy-design))
- Adaptive Protection은 Insider Risk Management의 위험 수준을 DLP 정책과 결합하여 사용자 위험 수준에 따라 제어를 조정한다. ([Adaptive Protection](https://learn.microsoft.com/en-us/purview/insider-risk-management-adaptive-protection))
- Endpoint DLP와 browser 보호 흐름은 third-party generative AI 사이트로 민감정보를 paste/upload하는 행동을 audit, warn 또는 block하도록 구성할 수 있다. ([Purview에서 AI 상호작용 보호](https://learn.microsoft.com/en-us/purview/ai-microsoft-purview), [Shadow AI 데이터 유출 방지](https://learn.microsoft.com/en-us/purview/deploymentmodels/depmod-data-leak-shadow-ai-step3))

#### 장점

- Microsoft 365와 endpoint 전반에서 실제 사용자 행위와 조직 정책을 연결하고, 알림·감사·예외 흐름까지 운영할 수 있다.
- `누가`, `어디서`, `어떤 데이터에`, `무슨 행동을 하는가`에 따른 조건부 정책은 단순 정규식 마스킹보다 강한 비교 대상이다.

#### 한계

- **공식 운영 특성:** workload와 location에 따라 지원 condition/action 및 policy tip 범위가 다르다. 정책은 배포 전에 simulation mode로 영향을 검토할 수 있지만 simulation에서는 enforcement action이 적용되지 않는다. 정책 활성화 뒤 적용에는 일반적으로 시간이 걸릴 수 있다. ([DLP 정책 설계](https://learn.microsoft.com/en-us/purview/dlp-policy-design), [DLP 시뮬레이션](https://learn.microsoft.com/en-us/purview/dlp-test-dlp-policies), [Policy tips support](https://learn.microsoft.com/en-us/purview/dlp-policy-tips-reference))
- **비교 해석:** 확인한 문서는 조직 전반의 데이터 유출 방지와 사용자 행동 제어가 중심이다. 하나의 장애 로그를 두 Purpose용 field·event·relation package로 재구성하여 AI utility를 비교하는 기능은 확인하지 못했다.

#### 여과시간과 겹치는 기능

민감정보 분류, 사용자/역할/위치/목적지 조건, AI 사이트 upload/paste block, review·alert, audit가 겹친다. 따라서 `정책 기반 DLP`, `상황에 따라 다른 통제`, `미승인 AI 차단`만으로는 차별화되지 않는다.

## 4. AI Gateway 기술

### 4.1 Cloudflare AI Gateway

#### 공식 문서에서 확인한 기능

- AI Gateway는 여러 AI provider에 대한 proxy로 logging, analytics, caching, rate limiting, retries/fallback, dynamic routing, authentication, BYOK를 제공한다. Dynamic Routing은 사용자·지역·content analysis·A/B test 조건으로 흐름을 구성할 수 있다. ([AI Gateway 개요](https://developers.cloudflare.com/ai-gateway/), [기능 목록](https://developers.cloudflare.com/ai-gateway/features/))
- AI Gateway DLP는 prompt와 model response의 텍스트를 검사하고 profile match에 대해 `flag` 또는 `block`할 수 있다. 사전 정의 및 custom profile을 사용하며, AI Gateway가 지원하는 provider에 일관된 정책을 적용한다. ([AI Gateway DLP](https://developers.cloudflare.com/ai-gateway/features/dlp/), [DLP 설정](https://developers.cloudflare.com/ai-gateway/features/dlp/set-up-dlp/))
- Guardrails는 prompt와 response를 실시간 검사해 unsafe category를 flag/block한다. ([Guardrails](https://developers.cloudflare.com/ai-gateway/features/guardrails/))

#### 장점

- 애플리케이션 코드를 크게 바꾸지 않고 여러 provider에 공통 보안·비용·관찰 정책을 적용할 수 있다.
- DLP가 request와 response 모두에 적용되고, 로그·analytics와 연결되어 운영성이 높다.

#### 한계

- **공식 제한:** DLP는 body의 text를 검사하지만 base64 image와 외부 URL을 따라가거나 decode하지 않는다. multipart의 binary도 검사하지 않는다. response DLP가 켜진 SSE streaming은 전체 응답을 buffer한 뒤 검사하므로 time-to-first-token이 증가한다. ([AI Gateway DLP inspection scope](https://developers.cloudflare.com/ai-gateway/features/dlp/))
- **공식 제한:** AI Gateway DLP action은 확인한 설정 문서에서 flag 또는 block이다. 변환된 업무 패키지 생성은 이 문서에서 설명하지 않는다. ([DLP 설정](https://developers.cloudflare.com/ai-gateway/features/dlp/set-up-dlp/))
- **비교 해석:** Dynamic Routing의 content-aware 조건은 Purpose와 유사한 문맥 라우팅을 만들 수 있다. 다만 확인한 문서는 versioned Purpose contract가 field·event·relation을 선택하고 package manifest·approval fingerprint를 만드는 흐름을 입증하지 않는다.

#### 여과시간과 겹치는 기능

AI proxy, destination/provider routing, DLP, block, 인증, 중앙 로그, fallback이 크게 겹친다. `AI Gateway라서 새롭다`, `여러 AI를 안전하게 연결해서 새롭다`는 주장은 사용할 수 없다.

### 4.2 Kong AI Gateway

#### 공식 문서에서 확인한 기능

- Kong AI Gateway는 provider-agnostic API, 중앙 credential 관리, authentication/ACL, provider routing·load balancing·failover, rate limiting과 usage/cost/latency 관찰성을 제공한다. load-balancing에는 latency, usage, priority뿐 아니라 prompt similarity 기반 semantic routing도 포함된다. ([AI Gateway 개요](https://developer.konghq.com/ai-gateway/), [Architecture](https://developer.konghq.com/ai-gateway/architecture/))
- AI Prompt Guard는 regex allow/deny rule로 prompt를 차단하고, semantic guard는 자연어 의미 기반 정책을 적용할 수 있다. ([AI Prompt Guard](https://developer.konghq.com/plugins/ai-prompt-guard/), [Data governance](https://developer.konghq.com/ai-gateway/ai-data-gov/))
- AI PII Sanitizer는 외부 PII service를 사용해 request/response의 민감정보를 placeholder 또는 synthetic replacement로 바꿀 수 있고, input의 원래 값을 response에 복원하는 선택지도 제공한다. ([AI PII Sanitizer](https://developer.konghq.com/plugins/ai-sanitizer/))

#### 장점

- 단일 gateway에서 AI provider 호환, route, auth, sanitization, guardrail, observability를 결합한다.
- data plane을 자체 환경에 둘 수 있고, AI Gateway 2.0 hybrid architecture에서 control plane은 기본적으로 payload data path 밖에 있다. ([Architecture](https://developer.konghq.com/ai-gateway/architecture/))

#### 한계

- **공식 제한/요건:** AI PII Sanitizer는 AI Gateway Enterprise 기능이며 AI Proxy/Advanced와 외부 AI PII Anonymizer Service가 필요하다. ([AI PII Sanitizer](https://developer.konghq.com/plugins/ai-sanitizer/))
- **공식 동작 범위:** regex Prompt Guard의 deny가 allow보다 우선하지만 pattern rule은 표현식에 매칭되는 user message를 통제하는 기능이다. 구조화 로그의 업무 relation을 보존하는 기능과는 목적이 다르다. ([AI Prompt Guard](https://developer.konghq.com/plugins/ai-prompt-guard/))
- **비교 해석:** Kong은 semantic routing과 정책 attachment를 제공하므로 `목적별 라우팅`만으로는 여과시간의 차별점이 아니다. 확인한 문서에서 입증되지 않은 부분은 승인된 Incident Purpose 계약으로 출력 package의 field·event·relation을 선택하고 Strong Fixed Policy와 paired 평가하는 흐름이다.

#### 여과시간과 겹치는 기능

PII sanitization, custom pattern, request/response 검사, provider routing, semantic policy, 인증, logging, self-managed data plane까지 겹친다. 여과시간의 기술 비교에서 가장 강한 AI Gateway 상대다.

## 5. 사설/관리형 AI 경계

### 5.1 Azure OpenAI Private Endpoint

#### 공식 문서에서 확인한 기능

- Azure OpenAI resource에 Private Endpoint와 Private DNS zone을 구성하고 public network access를 `Disabled`로 설정하면 private endpoint connection만 접근 경로가 된다. 온프레미스 client는 VPN 또는 ExpressRoute와 private DNS 구성이 필요하다. ([Azure OpenAI VNet/Private Endpoint](https://learn.microsoft.com/en-us/azure/foundry-classic/openai/how-to/network?view=foundry-classic))
- Microsoft는 Private Link 배포 후 public access를 끄고 Azure Policy, NSG/route, private DNS를 함께 구성하는 방어 심층화를 권고한다. ([Secure Private Link deployment](https://learn.microsoft.com/en-us/azure/private-link/secure-private-link))

#### 장점

- 승인된 VNet과 Microsoft backbone 안의 endpoint로 AI 호출의 network exposure를 줄일 수 있다.
- 조직 IAM·resource policy·private DNS와 결합하면 여과시간의 T2 `Organization Controlled` 목적지에 해당하는 강한 구현 선택지가 된다.

#### 한계

- **공식 구성 부담:** private endpoint, private DNS, VNet 연결, 온프레미스의 VPN/ExpressRoute 등을 함께 구성해야 하며 public access를 별도로 끄지 않으면 private endpoint 생성만으로 exclusive path가 되지 않는다. ([Azure OpenAI VNet/Private Endpoint](https://learn.microsoft.com/en-us/azure/foundry-classic/openai/how-to/network?view=foundry-classic), [Secure Private Link deployment](https://learn.microsoft.com/en-us/azure/private-link/secure-private-link))
- **비교 해석:** Private Endpoint는 network reachability를 통제한다. 전송 payload의 Purpose 최소화, field/relation 변환, residual sensitive atom 판정은 별도 application/DLP layer가 담당해야 한다.

#### 여과시간과 겹치는 기능

등록 endpoint, 조직 통제 경계, public route 차단이 겹친다. 여과시간의 Docker network는 Azure Private Link와 동급 보안 기술이 아니라 **정책 경로를 실제 network failure/success로 검증하는 학부 PoC**라고 표현해야 한다.

## 6. 기능 중복 매트릭스

`●` 공식 문서에서 직접 확인, `△` 확장·조합으로 가능하거나 일부만 확인, `—` 이번 확인 문서에서 근거를 찾지 못함. `—`는 제품 전체에 기능이 없다는 뜻이 아니다.

| 기능 | Presidio | Google SDP | Purview DLP | Cloudflare AI GW | Kong AI GW | Azure Private Endpoint | 여과시간 목표 |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| PII/Secret 탐지 | ● | ● | ● | ● | ● | — | ● |
| redact/mask/pseudonymize | ● | ● | △ | block/flag 중심 | ● | — | ● |
| custom detector/pattern | ● | ● | ● | ● | ● | — | ● |
| AI provider proxy/routing | — | — | — | ● | ● | — | ● |
| 인증·정책·감사 | — | template/Cloud Audit 조합 | ● | ● | ● | Azure 조합 | ● |
| private network path | self-host 조합 | VPC-SC 등 별도 | Microsoft 경계 조합 | Cloudflare 경로 | self-managed DP | ● | Docker PoC |
| 업무 목적별 field selection | custom code 가능 | template 조합 가능 | rule 조합 가능 | policy 조합 가능 | policy 조합 가능 | — | ● |
| event/relation 보존 계약 | — | — | — | — | — | — | ●, 검증 대상 |
| package manifest와 승인 fingerprint | — | — | — | — | policy/log 조합 가능성 | — | ●, 검증 대상 |
| Strong Fixed Policy와 paired evaluation | — | — | — | — | — | — | ●, 연구 설계 |

## 7. 여과시간의 차별점은 이렇게 좁혀야 한다

### 주장 가능한 것

1. **프로젝트 설계 차이:** 여과시간은 장애 로그에서 승인된 Purpose version을 field·event·relation 선택 계약으로 사용하고, 선택 결과를 transformation manifest로 남긴다.
2. **보안과 utility의 공동 검증:** 모든 민감정보를 덜 지운다고 주장하지 않는다. 공통 hard Secret DROP과 Trust ceiling 안에서, 고정 정책 대비 불필요 공개와 RCA evidence를 함께 비교한다.
3. **승인 결속:** raw/package hash, Purpose version, Destination version, Policy version을 fingerprint에 묶어 변경 시 기존 승인을 무효화한다.
4. **경로 검증:** application-level DENY뿐 아니라 Internal Client의 direct external access 실패와 승인 gateway path 성공을 receiver log로 증명한다.
5. **공정한 비교 설계:** B3와 여과시간 사이에서 detector, Secret 제거, tokenization, residual scan, model, prompt, decoding, network를 같게 두고 Purpose selection만 바꾼다.

### 주장하면 안 되는 것

| 금지 주장 | 이유 | 대신 사용할 표현 |
| --- | --- | --- |
| `목적에 따라 마스킹하는 최초 기술` | DLP/AI Gateway는 custom rule, context, routing, policy 조합을 이미 제공한다. 시장 전체 부재를 이번 조사로 증명하지 못했다. | `두 Incident Purpose의 field·event·relation 계약이 고정 정책보다 나은지 검증한다.` |
| `기존 기술은 정규식 마스킹뿐` | Presidio recognizer/NLP, Google detector·crypto transform, Purview context rule, Cloudflare/Kong AI policy가 있다. | `강한 기존 기능을 detector·sanitization 구성 요소로 인정한다.` |
| `기존 AI Gateway는 탐지·라우팅만 한다` | Cloudflare와 Kong은 DLP, guardrail, auth, audit/observability와 request/response 통제를 제공한다. | `여과시간은 장애용 package contract와 approval state를 gateway 앞단 workflow로 검증한다.` |
| `Docker 격리로 물리적으로 안전` | Docker network는 PoC 논리 경계이며 cloud Private Link와 동급이 아니다. | `내부 Client의 직접 egress 차단을 재현 가능한 테스트로 검증한다.` |
| `토큰화하면 익명화된다` | deterministic/pseudonymous value는 연결·재식별 위험이 남는다. | `case 범위 관계 보존과 사건 간 linkability 감소를 목표로 한다.` |
| `T2는 안전하므로 원문 전송 가능` | private path는 payload 최소화를 대체하지 않는다. | `T2에도 Purpose 최소화를 적용하고 Trust는 허용 상한만 정한다.` |
| `목적별 처리는 효율을 위한 기능` | 핵심은 목적 외 공개를 줄이는 보안 원칙이다. | `보안을 위해 공개 범위를 줄이고, 그 범위 안에서 분석 근거를 보존한다.` |

## 8. 남는 검증 가설

| ID | 가설 | 비교 대상 | 지표 | 반증 조건 |
| --- | --- | --- | --- | --- |
| H1 | Purpose package가 B3보다 불필요 공개를 줄인다. | Strong Fixed Policy B3 | unnecessary disclosure rate | Locked에서 감소가 사전 기준 미달 |
| H2 | 공개 감소에도 RCA 근거는 유지된다. | B3와 동일 model/prompt | root-cause exact match, required event/relation recall | utility가 B3보다 낮아짐 |
| H3 | case-scoped token이 같은 사건 관계를 유지하고 사건 간 연결을 줄인다. | fixed/global token | intra-case equality, inter-case token difference | 관계가 깨지거나 사건 간 token 동일 |
| H4 | 승인 후 핵심 입력 변경을 재사용할 수 없다. | fingerprint 없는 승인 | invalidation test | raw/package/purpose/destination/policy 변경 후 실행 성공 |
| H5 | 정책 차단이 실제 network 경로 차단으로 이어진다. | application deny only | receiver count, direct-connect exit | T0/Secret payload 수신 또는 direct egress 성공 |
| H6 | detector/parser/scanner failure에서 외부 전송 0을 유지한다. | fail-open 구성 | controlled receiver count | failure case 수신 1건 이상 |

H1·H2가 여과시간의 핵심 차별성 가설이다. H3~H6은 보안 구현의 신뢰성을 검증하지만 기존 기술 대비 신규성 주장은 아니다.

## 9. 발표용 비교표

| 구분 | 대표 기존 기술 | 잘하는 것 | 남는 공백/여과시간 검증 |
| --- | --- | --- | --- |
| 탐지·비식별화 | Presidio, Google Sensitive Data Protection | PII 탐지, redact/mask/hash/encrypt, custom rule | Incident Purpose가 요구하는 event·relation까지 포함한 최소 package가 고정 정책보다 나은가? |
| 전사 DLP | Microsoft Purview DLP | 사용자·앱·장치·위치별 block/warn/audit | 하나의 장애 로그를 업무 결과별 package로 재구성하고 AI utility까지 측정하는가? |
| AI Gateway | Cloudflare, Kong | multi-provider proxy, DLP/guardrail, auth, routing, rate limit, logging | Purpose version·package manifest·approval fingerprint를 하나의 실행 상태로 묶었을 때 효과가 있는가? |
| 사설 AI 경계 | Azure OpenAI Private Endpoint | public access를 끄고 VNet private path 제공 | private path 안에서도 목적 외 payload를 줄이고 T1/T0까지 일관된 정책을 적용할 수 있는가? |
| 여과시간 | 학부 PoC | 위 기능 일부를 좁은 장애 도메인에서 연결 | 동일 조건의 Strong Fixed Policy와 Locked set으로 차이가 실제로 살아남는지 검증 |

### 발표용 30초 설명

> 기존 DLP는 민감정보를 잘 찾고 가리며, AI Gateway는 인증·라우팅·차단·로깅을 이미 제공합니다. Private Endpoint는 AI 호출을 사설 경로로 제한합니다. 따라서 저희는 이 기능 자체를 새롭다고 주장하지 않습니다. 여과시간의 질문은 더 좁습니다. 같은 강한 탐지와 변환을 쓸 때, 장애 분석과 보고서 작성이라는 승인된 목적별로 field·event·relation을 선택하면 하나의 고정 안전 정책보다 불필요한 공개를 줄이면서 분석 근거를 지킬 수 있는가입니다. 이를 동일 조건의 Strong Fixed Policy와 Locked 평가로 검증하겠습니다.

## 10. 구현에 미치는 판단

1. 자체 NER 모델을 만들지 않는다. Presidio 같은 기존 detector를 활용하거나 동급의 단순 detector를 공통 파이프라인으로 두고 B3/P에 동일 적용한다.
2. AI Gateway 전 기능을 재구현하지 않는다. rate limit, multi-provider fallback, cost dashboard는 차별성과 무관하므로 MVP 밖에 둔다.
3. `Purpose Registry → Package Builder → Policy/Approval → Dispatcher`의 얇은 vertical slice에 집중한다.
4. 차별성 증거는 화면 수나 기능 수가 아니라 B3/P paired artifact, transformation manifest, receiver log, approval invalidation test로 남긴다.
5. 현업 인터뷰에서 Purpose별 계약을 누가 관리할 수 있는지, 고정 masking 때문에 relation이 손실된 실제 사례가 있는지 확인하지 못하면 대상 범위와 주장을 축소한다.

## 11. 주요 공식 자료

- [Microsoft Presidio 공식 저장소](https://github.com/microsoft/presidio)
- [Microsoft Presidio Anonymizer](https://microsoft.github.io/presidio/anonymizer/)
- [Google Cloud Sensitive Data Protection: De-identify sensitive data](https://cloud.google.com/sensitive-data-protection/docs/deidentify-sensitive-data)
- [Google Cloud Sensitive Data Protection: Transformation reference](https://cloud.google.com/sensitive-data-protection/docs/transformations-reference)
- [Microsoft Purview: Learn about data loss prevention](https://learn.microsoft.com/en-us/purview/dlp-learn-about-dlp)
- [Microsoft Purview: Design a DLP policy](https://learn.microsoft.com/en-us/purview/dlp-policy-design)
- [Cloudflare AI Gateway](https://developers.cloudflare.com/ai-gateway/)
- [Cloudflare AI Gateway DLP](https://developers.cloudflare.com/ai-gateway/features/dlp/)
- [Kong AI Gateway](https://developer.konghq.com/ai-gateway/)
- [Kong AI PII Sanitizer](https://developer.konghq.com/plugins/ai-sanitizer/)
- [Azure OpenAI virtual networks and private endpoints](https://learn.microsoft.com/en-us/azure/foundry-classic/openai/how-to/network?view=foundry-classic)
- [Azure Private Link security guidance](https://learn.microsoft.com/en-us/azure/private-link/secure-private-link)
