# Pilot 실험 프로토콜

> 시나리오를 돌리기 전에 고정한다. 결과를 본 뒤 이 문서의 조건·지표·판정을 바꾸지 않는다. 바꿔야 하면 이유와 함께 변경 이력에 남기고, 바꾸기 전 결과도 지우지 않는다.

## 질문

같은 장애 텔레메트리를 여러 방식으로 AI에 보냈을 때,

1. **담기**(목록에 있는 필드만)로도 원인을 찾는가 — 원문 전체 대비
2. 민감정보가 얼마나 나가는가 — 빼기 대비

## 왜 오픈소스 데모인가

합성 로그를 우리가 쓰면, 목록을 아는 사람이 로그를 쓰는 구조라 순환을 벗어날 수 없다. OpenTelemetry Demo(CNCF)에 실제로 장애를 주입하면 에러 메시지·스택·필드 배치를 **데모가 정한다.** 정답은 **우리가 주입한 장애**다.

## 환경

| 항목 | 값 |
| --- | --- |
| 데모 | `open-telemetry/opentelemetry-demo` 커밋 `898ede2` |
| 구성 | `compose.yaml` + `compose.full.yaml` (관측 UI·LLM 에이전트 제외, 23개 서비스) |
| 실행 | Colima 프로필 `pilot`, aarch64 네이티브(vz), CPU 6 / 메모리 12GB |
| 수집 | collector extras(`collector-extras.yml`)로 file exporter 두 개 추가. 데모 본체 설정은 수정하지 않음 |
| 트래픽 | 데모 기본 load-generator |

## 조건

같은 입력(아래 "사건 추출")에서 갈라진다.

| 조건 | 내용 | 누가 정했나 |
| --- | --- | --- |
| **원문** | redaction 이전 텔레메트리 그대로 | 데모 |
| **빼기-OTel** | 데모 레퍼런스 collector 파이프라인(`transform/redact_sensitive_data` + `redaction`) 통과본 | **데모 메인테이너** — 우리가 설정 안 함 |
| **빼기-Presidio** | 원문의 모든 문자열 값을 Presidio로 탐지·치환. 필드는 유지 | Presidio 기본 설정 |
| **담기** | 원문에서 목록에 있는 필드만 남김 | 우리 (`lists.json`) |

## 순서 — 목록이 먼저

1. 장애 없는 **정상 트래픽**만 수집한다
2. 정상 텔레메트리의 **필드 구조만 보고** 목록(`lists.json`)을 작성하고 **커밋**한다
3. 그다음 장애를 주입한다

목록 커밋이 시나리오 수집보다 앞선다는 것이 git 이력으로 남는다. 장애 로그를 본 적 없는 상태에서 정한 목록이므로 정답에 맞춰질 수 없다.

## 시나리오

모든 시나리오에서 `emitRawPii=on` (민감정보가 있어야 누출을 잰다).

| # | 플래그 | 성격 | 이 시나리오가 보는 것 |
| ---: | --- | --- | --- |
| S1 | `paymentFailure=100%` | 직접 — 에러 메시지에 원인 | 기준선. 모두 맞춰야 정상 |
| S2 | `paymentUnreachable=on` | 직접 — 연결 실패 | 결제 서비스 장애 |
| S3 | `productCatalogLockContention=on` | 간접 — 증상은 지연, 원인은 DB 락 | 원인이 한 줄에 없을 때 |
| S4 | `kafkaQueueProblems=on` | 간접 — 큐 과부하, 소비 지연 | 비동기 연쇄 |
| S5 | `intlShippingSlowdown=10sec` | 간접 — 해외 배송만 느림 | **원인 증거가 주소(국가) 필드에 있을 수 있음. 담기가 질 수 있는 경우** |
| S6 | `productCatalogFailure=on` | 특정 상품에서만 실패 | 원인 증거가 상품 ID 필드에 있음 |

S5는 우리가 설계한 함정이 아니라 데모에 원래 있는 장애다.

## 사건 추출 (조건 공통)

SRE가 AI에 붙여넣을 분량으로 줄인다. 모든 조건이 **같은 레코드 집합**에서 출발한다.

- 수집 창 안의 span 중 status가 ERROR이거나, 정상 트래픽 기준 같은 span 이름의 p95보다 느린 것
- 위 span과 같은 trace의 부모·자식 span
- severity가 WARN 이상인 로그
- 시간순 정렬, 최대 40건

원문·빼기-OTel은 spanId로 같은 레코드를 raw/redacted 파일에서 각각 가져온다.

## 피실험 AI

- `claude -p` 새 세션. 이 대화의 맥락이 없다
- **격리된 빈 폴더에서 실행, 도구 전부 끔**(`--tools ""`). 시나리오 파일(정답 포함)을 읽을 수 없다
- 모델 고정: 실행 시 기록
- 모든 조건에서 같은 질문 문장

질문: *"다음은 서비스 장애 발생 시점의 텔레메트리다. 장애의 근본 원인을 한 문장으로 답하고, 근거가 된 항목을 들어라. JSON으로 답하라: {\"root_cause\": ..., \"evidence\": [...]}"*

## 지표

| 지표 | 측정 |
| --- | --- |
| 원인 적중 | 답변이 주입한 장애를 가리키는가. 시나리오별 판정 키워드를 **수집 전에** `scenarios.json`에 적어둔다 |
| 민감정보 누출 | 원문에 있던 민감 값(이메일, 카드번호, CVV, 주소)이 패키지에 **값 그대로** 남아 있는 개수 |
| 패키지 크기 | 필드 수, 바이트 |

## 판정

- 담기가 원문만큼 원인을 맞추면 → 줄여도 된다
- 담기가 S5·S6에서 지면 → 목록의 한계. 결과 그대로 보고
- 빼기-OTel이 데모 자신의 PII 키를 다 잡는 것은 **예상된 결과**다(그 키에 맞춰 짠 설정이므로). 빼기의 약점은 **모르는 키**에서만 드러난다

## 한계

- 데모는 이커머스이고 영어 데이터다. 한국 금융 결제 시스템이 아니다
- 목록은 여전히 우리가 쓴다. 장애 로그를 보기 전에 쓴다는 순서로만 독립성을 확보한다
- 시나리오 6개, 반복 횟수 제한. 통계적 주장을 하지 않는다

## 수집 중 확인한 사실

- 데모 collector 설정 주석: *"Collector-side redaction is a fallback — prefer not collecting sensitive data in the first place."* (`src/otel-collector/otelcol-config.yml`)
- `redaction` 프로세서는 `allowed_keys`로 허용 목록(담기) 설정이 가능하지만, 레퍼런스 설정은 `allow_all_keys: true`(빼기)를 쓴다
- 데모의 빼기는 **trace에만** 걸려 있고 log 파이프라인에는 없다
- `emitRawPii`가 켜지면 payment가 `demo.payment.card_number`, `demo.payment.card_cvv`를, checkout이 `user.email`을 span 속성으로 보낸다. 데모 redaction은 이 세 키를 정확히 알고 처리한다

## 변경 이력

**v1.1 (정상 트래픽 수집 후, 장애 주입 전)**
- **주입 흔적 제거**: `flagd`·`flagd-ui` 서비스의 span·로그, 그리고 키가 `feature_flag.` 또는 `demo.feature_flag.`로 시작하는 속성을 **모든 조건의 입력에서 똑같이** 뺀다. 데모는 매 요청마다 어떤 장애 플래그가 켜졌는지 span으로 남기는데, 실제 장애에는 없는 흔적이라 남기면 AI가 로그를 분석하지 않고 스위치 이름만 읽게 된다
- **민감 키 정의**(누출 측정용): 카드(`demo.payment.card_number`, `demo.payment.card_cvv`, `lastFourDigits`), 식별자(`user.id`, `session.id`, `user.email`, `demo.order.id`, `demo.shipping.tracking.id`, `transactionId`, `transaction_id`, `tracking_id`). 원문 추출본에서 이 키들의 값(6자 이상)을 모아, 각 패키지 문자열에 그대로 들어 있는 개수를 센다
- **채점 2단계**: 서비스 적중(어느 부품이 문제인지) / 원인 적중(무엇 때문인지). 정규식은 `scenarios.json`
- 목록(`lists.json`)과 판정 키워드(`scenarios.json`)를 **같은 작성자**가 썼다. 독립성은 "장애 로그를 보기 전에 커밋"이라는 순서로만 확보한다. 판정 키워드는 로그가 아니라 데모 플래그 설명과 소스에서 뽑았다
