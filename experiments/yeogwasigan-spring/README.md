# 여과시간 (yeogwasigan) — Spring Boot 판

망분리 환경에서 내부 장애 로그를 외부 AI로 보낼 때, 민감정보를 어떻게 걸러낼지 세 가지 방식을 비교하는 **실험 플랫폼 + 데모 웹서비스**입니다.
Spring Boot 3.5 / Java 17 / Gradle 기반입니다.

| 조건 | 이름 | 동작 |
|---|---|---|
| **A** | 그대로 (`PASSTHROUGH`) | 아무 처리 없이 전송 — 기준선 |
| **B** | 빼기 (`DENYLIST`) | Microsoft Presidio(공식 Docker 이미지, 기본 설정)로 민감정보를 탐지·마스킹한 뒤 전송 |
| **C** | 담기 (`ALLOWLIST`) | Purpose Template에 적힌 필드만 골라서 전송 (+ 담은 필드 내부 재검사) |

**검증할 가설**

1. 담기로 필요한 필드만 보내도 AI가 장애 원인을 제대로 찾는가? → `rca_score` (블라인드 채점)
2. 담기가 빼기보다 민감정보 잔존을 더 줄이는가? → `residual_pii_count` (단순 문자열 검색)

외부 AI 호출 목적은 **장애 근본원인 분석(INCIDENT_ANALYSIS)** 하나로 고정하고, 세 조건 모두 아래 질문을 똑같이 사용합니다. (`ExperimentConstants.FIXED_QUESTION`)

```
다음 로그를 분석해서, 이 장애의 근본 원인이 무엇인지와 그렇게 판단한 근거를 설명해주세요.
```

---

## 폴더 구조

```
yeogwasigan/
├── build.gradle · settings.gradle · gradlew
├── Dockerfile                        # 멀티 스테이지 (컨테이너 안에서 빌드 → JRE 로 실행)
├── docker-compose.yml                # 앱 + Presidio analyzer / anonymizer
├── README.md
│
├── src/main/java/com/capstone/yeogwasigan/
│   ├── YeogwasiganApplication.java
│   ├── core/                         # 공용 로직 — 웹·실험 둘 다 여기만 호출
│   │   ├── filter/
│   │   │   ├── LogFilter.java              # 공통 인터페이스
│   │   │   ├── FilterResult.java           # 공통 출력 (필드 수 계산도 여기 한 곳에서)
│   │   │   ├── FilterRegistry.java         # ★ 웹·실험이 필터를 꺼내 쓰는 유일한 통로
│   │   │   ├── PassthroughFilter.java      # [A]
│   │   │   ├── DenylistFilter.java         # [B] Presidio 호출
│   │   │   └── AllowlistFilter.java        # [C] Purpose Template 기반
│   │   ├── template/ PurposeTemplate.java · TemplateLoader.java
│   │   ├── tokenize/ CaseScopedTokenizer.java   # HMAC 기반 사건 단위 토큰화
│   │   ├── ai/       AiClient.java · AiAnswer.java   # 외부 AI 호출 (mock 모드 포함)
│   │   ├── scoring/  ResidualScorer.java · BlindSetBuilder.java
│   │   ├── presidio/ PresidioClient.java           # ★ Presidio REST 호출 (RestClient)
│   │   ├── log/      LogFormat.java                # ★ JSONL/OTLP 파싱·평탄화·직렬화
│   │   ├── scenario/ ScenarioRepository.java ...   # ★ scenarios/ 읽기
│   │   └── config/   ExperimentConstants.java · AppProperties.java   # ★ 고정 질문·설정
│   ├── web/
│   │   ├── AnalyzeController.java · ApiExceptionHandler.java
│   │   └── dto/
│   └── experiment/
│       ├── ExperimentRunner.java     # experiment 프로파일 — 실험 일괄 실행
│       └── BlindRunner.java          # ★ blind 프로파일 — 블라인드 채점 파일 생성/병합
│
├── src/main/resources/
│   ├── application.yml (+ application-experiment.yml, application-blind.yml)
│   ├── templates/incident_analysis.yaml    # Purpose Template
│   └── static/index.html                   # 데모 UI (단일 HTML, 프레임워크 없음)
├── src/test/java/...                        # Presidio·AI 없이 도는 단위 테스트 18개
│
├── scenarios/s01_payment_fail/
│   ├── raw.jsonl · truth.yaml · injected_pii.yaml
└── results/                                 # 실험 결과가 쌓이는 곳 (git 제외)
```

★ 표시는 원래 설계도에 없던 파일입니다. Presidio 호출, 로그 형식 처리, 설정값이 여러 곳에 흩어지지 않고 한 군데에만 있게 하려고 나눴습니다.
블라인드 채점도 `bootRun` 한 가지 방식으로 실행되도록 `BlindRunner`를 추가했습니다.

---

## 실행 순서

모든 명령은 **프로젝트 루트**에서 실행합니다.

### 1단계. 환경설정

필요한 것:

- **Docker Desktop** — Presidio 컨테이너용 (앱까지 컨테이너로 돌리면 JDK 없이도 됩니다)
- **JDK 17 이상** — `./gradlew bootRun` 으로 돌릴 때. `java -version` 으로 확인하세요. (Spring Boot 3의 Gradle 플러그인이 JDK 17을 요구합니다)

외부 AI를 실제로 호출하려면 API 키를 넣습니다. **키가 없으면 자동으로 mock 모드**로 동작합니다.
셸 환경변수로 넣거나, 프로젝트 루트에 `.env` 파일을 만들면 docker compose 가 읽습니다. (`.env` 는 git 에서 제외되어 있습니다)

```bash
# Anthropic (기본값)
export ANTHROPIC_API_KEY=sk-ant-...
export YG_AI_MODEL=claude-sonnet-4-5      # 실험 기간 동안 절대 바꾸지 말 것

# 또는 OpenAI
export YG_AI_PROVIDER=openai
export OPENAI_API_KEY=sk-...
export YG_AI_MODEL=gpt-5.1-2025-11-13   # reasoning_effort=none 으로 고정 전송 (temperature 0 허용 조건)
```

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `YG_AI_PROVIDER` | `anthropic` | `anthropic` 또는 `openai` |
| `YG_AI_MODEL` | `claude-sonnet-4-5` / `gpt-5.1-2025-11-13` | 모델명. 계정에서 쓸 수 있는 이름인지 먼저 확인 |
| `YG_AI_MAX_TOKENS` | `1024` | 응답 최대 길이 |
| `YG_AI_MOCK` | `false` | `true` 면 키가 있어도 mock 모드 |
| `PRESIDIO_ANALYZER_URL` | `http://localhost:5002` | compose 로 앱을 띄우면 자동으로 `http://presidio-analyzer:3000` |
| `PRESIDIO_ANONYMIZER_URL` | `http://localhost:5001` | 〃 `http://presidio-anonymizer:3000` |

`temperature` 는 설정으로 바꿀 수 없고 코드에서 0으로 고정되어 있습니다.

### 2단계. docker-compose 기동

두 가지 방법 중 하나를 고릅니다.

**(가) 전부 컨테이너로** — JDK 가 없어도 됩니다.

```bash
docker compose up --build
```

**(나) Presidio 만 컨테이너로, 앱은 로컬에서** — 코드를 고치면서 개발할 때 편합니다.

```bash
docker compose up -d presidio-analyzer presidio-anonymizer
./gradlew bootRun
```

Presidio analyzer 는 시작할 때 spaCy 모델을 불러오느라 **30초~1분** 걸립니다. 준비됐는지는 이렇게 확인합니다.

```bash
curl localhost:5002/health     # Presidio Analyzer service is up
curl localhost:5001/health     # Presidio Anonymizer service is up
```

> Apple Silicon 맥에서 `no matching manifest for linux/arm64` 오류가 나면 `docker-compose.yml` 의 `platform: linux/amd64` 주석을 풉니다.

### 3단계. 웹 데모 접속

브라우저에서 **http://localhost:8080** 을 엽니다.

- 오른쪽 위에 **Presidio 연결 상태**와 **AI 모드(mock/live)**가 표시됩니다. 점선 테두리는 "꺼져 있음"입니다.
- 처음 열면 샘플 로그가 자동으로 들어갑니다. [파일 업로드]로 `.jsonl` 을 올리거나 textarea 를 직접 고칠 수도 있습니다. 고치면 0.5초 뒤 결과가 다시 계산됩니다.
- **방식(그대로/빼기/담기)을 바꾸면 AI 호출 없이 "실제로 나간 것" 패널이 바로 다시 계산됩니다.** 필드 수(`49개 → 9개`)와 민감값 잔존 건수가 크게 표시됩니다. 세 방식 비교 줄을 눌러도 방식이 바뀝니다.
- **검정 반전**은 원본 민감값이 그대로 나간 부분이고, **점선 테두리**는 토큰이나 마스킹으로 바뀐 값입니다. 아래에 버려진 필드 목록(취소선)과 남은 민감값 목록이 나옵니다.
- [분석 요청]을 누르면 그 순간 화면에 보이는 "실제로 나간 것"만 AI로 전송됩니다.
- Presidio 가 준비되지 않았을 때 빼기를 고르면 원본을 대신 보여주지 않고 오류만 표시합니다.

API:

```
POST /api/analyze   body: { log, purpose, filter, caseId? }
  → { filter, filterLabel, output, fieldsBefore, fieldsAfter, droppedFields,
      residualCount, residualDetail, notes, aiAnswer, aiMode, aiModel }
POST /api/filter    (같은 입력, AI 호출 없이 필터 결과만)
POST /api/compare   (세 방식의 필드 수·잔존 수 요약)
GET  /api/meta
GET  /api/sample/{scenarioId}
```

`filter` 는 `PASSTHROUGH | DENYLIST | ALLOWLIST` (대소문자 무관). 오류는 `{ "detail": "..." }` 로 돌려주며, 로그 형식 오류는 400, Presidio 연결 불가는 503 입니다.

### 4단계. 실험 일괄 실행

먼저 API 비용 없이 mock 모드로 한 번 돌려서 파이프라인을 점검합니다. (Presidio 는 떠 있어야 합니다)

```bash
YG_AI_MOCK=true ./gradlew bootRun --args='--spring.profiles.active=experiment --yeogwasigan.experiment.runs=1'
```

예상 출력 (샘플 시나리오, Presidio 2.2.364 기본 설정으로 실제로 돌려서 확인한 값):

```
[1/3] s01_payment_fail PASSTHROUGH run1  필드 49→49  잔존 22  AI=mock
[2/3] s01_payment_fail DENYLIST    run1  필드 49→49  잔존 3   AI=mock
[3/3] s01_payment_fail ALLOWLIST   run1  필드 49→9   잔존 1   AI=mock
```

본 실험:

```bash
./gradlew bootRun --args='--spring.profiles.active=experiment'
# 전체 시나리오 × 3방식 × 3회

./gradlew bootRun --args='--spring.profiles.active=experiment --yeogwasigan.experiment.scenarios=s01_payment_fail --yeogwasigan.experiment.runs=3'

# 컨테이너 안에서 돌릴 때 (결과는 호스트의 ./results 에 남습니다)
docker compose run --rm app --spring.profiles.active=experiment
```

실행 전에 Presidio 가 준비될 때까지 최대 120초 기다리고, 그래도 안 되면 **AI 를 한 번도 호출하지 않고** 실패합니다.

`results/<실행시각>/` 폴더에 다음이 생깁니다.

| 파일 | 내용 |
|---|---|
| `results.csv` | `scenario_id, filter, run_no, fields_before, fields_after, residual_pii_count, ai_answer, rca_score` (UTF-8 BOM, 엑셀에서 바로 열림) |
| `payloads/*.jsonl` | 조건마다 AI 로 **실제로 나간** 로그. 재현하거나 검증할 때 씁니다 |
| `run_meta.json` | 질문, 모델, temperature, AI 모드(live/mock), 템플릿 전체, Presidio 주소·지원 엔티티 목록 |

> `run_meta.json` 의 `aiMode` 가 `mock` 이면 그 결과는 실험 결과로 쓰면 안 됩니다.

### 5단계. 블라인드 채점

```bash
# ① A/B/C 라벨을 지우고 무작위로 섞어서 채점용 파일을 만듭니다
./gradlew bootRun --args='--spring.profiles.active=blind --yeogwasigan.blind.action=prepare --yeogwasigan.blind.results=results/<실행시각>/results.csv'

#   → blind/blind_answers.csv : 채점자에게는 이 파일과 rubric.md 만 전달합니다
#   → blind/rubric.md         : 시나리오별 정답 요약 + 0~3점 채점 기준 (함정·조건 정보는 뺌)
#   → blind/blind_key.csv     : 매핑표. 채점이 끝날 때까지 열지 않습니다

# ② 채점자가 rubric.md 를 보고 blind_answers.csv 의 rca_score 칸을 채웁니다

# ③ 점수를 결과에 합치고 방식별 평균을 출력합니다
./gradlew bootRun --args='--spring.profiles.active=blind --yeogwasigan.blind.action=merge --yeogwasigan.blind.results=results/<실행시각>/results.csv'
#   → results_scored.csv
```

`prepare` 는 기본적으로 응답 안의 `TX_1A2B3C4D`(담기), `<EMAIL_ADDRESS>`(빼기), 원문 traceId, 주입 민감값처럼 **조건을 짐작하게 하는 흔적을 모두 `[가림]`으로** 바꿉니다. 끄려면 `--yeogwasigan.blind.normalize=false`, 섞는 순서를 재현하려면 `--yeogwasigan.blind.seed=42` 를 붙입니다.

### 테스트

```bash
./gradlew test
```

Presidio·AI 없이 도는 단위 테스트 18개입니다. 토큰이 파이썬 `hmac` 구현과 같은 값을 내는지, 샘플에서 그대로 49→49·잔존 22 / 담기 49→9·잔존 1 이 나오는지, 담기 함정(`paymentFailure` 가 안 나감), trace 토큰 연결, Presidio 가 죽었을 때 빼기가 원본을 내보내지 않는지 등을 확인합니다.

---

## 설계 원칙

### 웹과 실험이 같은 코드를 탄다

`AnalyzeController` 와 `ExperimentRunner` 는 둘 다 `FilterRegistry` 에서 필터를 꺼내고, 같은 `ResidualScorer`·`AiClient`·`LogFormat.serialize` 를 씁니다. 데모 화면의 숫자와 실험 CSV 의 숫자는 같은 코드 경로에서 나옵니다.

### 세 필터의 인터페이스 통일

```java
public interface LogFilter {
    FilterResult apply(List<Map<String, Object>> logs, String caseId);
}

public record FilterResult(
    List<Map<String, Object>> output,
    int fieldsBefore,
    int fieldsAfter,
    List<String> droppedFields,
    Map<String, String> transformed,   // 원본값 → 변환값 (원본 민감값이 있어 웹 응답에는 안 실음)
    Map<String, Object> notes          // 방식별 부가정보 (Presidio 탐지 유형 수, innerScan 적중 수)
) {}
```

- 필드 수·버려진 필드는 `FilterResult.of(...)` 한 곳에서 계산합니다. 필터마다 세는 방법이 달라지지 않게 하려는 것입니다.
- 목적(Purpose)을 쓰는 필터는 담기뿐이라 `apply(logs, caseId, purposeId)` 는 기본 메서드로 두었습니다.
- 필드 경로는 Purpose Template 표기와 똑같이 씁니다: `resource.service.name`, `logRecord.body`, `logRecord.attributes.user.email`.
- 필터는 입력 레코드를 절대 수정하지 않습니다.

### [B] 빼기: Presidio 공식 이미지, 기본 설정 그대로

`PresidioClient` 는 analyzer 에 `{text, language:"en"}` **만** 보내고, 받은 탐지 결과를 가공 없이 anonymizer 에 넘깁니다. `entities`, `score_threshold`, `ad_hoc_recognizers`, `allow_list`, 연산자 설정을 전혀 지정하지 않으므로 기본 인식기 전체 + 기본 임계값 + 기본 `replace` 연산자가 그대로 쓰입니다. 한국어 인식기는 추가하지 않았습니다. 모든 필드의 문자열 값을 검사하고, 필드는 하나도 버리지 않습니다.

- 같은 문자열은 한 번의 `apply` 안에서 한 번만 Presidio 에 보냅니다(결과는 같고 호출 수만 줄어듭니다). 샘플 기준 서로 다른 문자열 105개, 약 2초.
- Presidio 에 연결할 수 없으면 `PresidioUnavailableException` 을 던집니다. **원본으로 대체하지 않습니다.**

### [C] 담기: 템플릿이 전부

- 담는 필드는 `requiredFields` 와 `fieldActions` 에 적힌 필드뿐입니다. 목록에 없는 필드는 처음 보는 필드여도 버립니다(`onUnknownField: DROP`).
- `traceId`, `spanId`, `parentSpanId` 는 사건 단위 토큰(`TX_`)으로, `host.name` 과 `container.id` 는 가명(`PS_`)으로 바꿉니다.
- 원문 그대로 담은 필드(특히 `body`)는 정규식으로 한 번 더 스캔해서 민감값이 있는 부분만 토큰으로 바꿉니다(`innerScanOnKeptFields`). 대상은 이메일, 주민번호, 카드번호, 휴대폰번호, 계좌번호, IPv4 입니다.
- 템플릿 YAML 에 모르는 키가 있거나 fieldAction 이름이 틀리면 서버 시작 시점에 바로 실패합니다.

### 토큰화 (`CaseScopedTokenizer`)

```
token = "TX_" + HMAC-SHA256(원본값, key=caseId) 앞 8자리 대문자
```

같은 사건 안에서는 같은 값이 항상 같은 토큰이 되므로 AI 가 trace 관계를 추적할 수 있습니다. 다른 사건에서는 같은 값이라도 다른 토큰이 되므로 사건끼리 맞춰 보는 재식별이 막힙니다. 파이썬 판과 같은 입력에 같은 토큰을 냅니다(테스트로 확인).

### 잔존 채점 (`ResidualScorer`)

`injected_pii.yaml` 에 등록한 값을 **AI 프롬프트에 들어가는 것과 똑같은 텍스트**(`LogFormat.serialize`)에서 단순 문자열 검색으로 셉니다. 탐지기의 판단을 쓰지 않으므로 어느 방식에도 유리하지 않습니다.

---

## 샘플 시나리오 `s01_payment_fail`

OpenTelemetry Demo 의 `paymentFailure` 기능 플래그 장애를 본떠 만든 합성 로그 11줄입니다. (resource / scope / logRecord 3층 구조)

- **흐름**: flagd 가 플래그 설정을 갱신 → payment 가 플래그를 평가하고 "Payment request failed. Invalid token."으로 실패 → 같은 traceId 로 checkout, frontend 까지 오류가 전파
- **방해 요소**: 다른 trace 의 shipping 지연 WARN, cart INFO
- **주입 민감정보**: 계좌번호, 고객 이메일, 고객명(한글), 내부 IP 7개. 계좌·이메일·이름·IP 일부는 `body` 자유 텍스트 안에 섞여 있습니다.
- **함정 (담기가 지는 케이스)**: 결정적 단서인 플래그 이름 `paymentFailure` 가 **INFO 로그의 `attributes` 에만** 있습니다. body 에는 "Feature flag evaluated", "Flag configuration reloaded" 라는 막연한 문장만 있습니다. 담기로 보내면 AI 는 "payment 에서 결제 실패"까지는 찾아도 플래그라는 진짜 원인은 보기 어렵습니다. `truth.yaml` 채점 기준에서 3점과 2점이 갈리는 지점입니다.

### 샘플에서 실제로 측정된 필터 동작 (Presidio 2.2.364 기본 설정)

| 방식 | 필드 | 잔존 | 남은 값 | 참고 |
|---|---|---|---|---|
| 그대로 | 49 → 49 | 22 | 전부 | |
| 빼기 | 49 → 49 | 3 | 박민지 ×3 | 이메일·IP 는 잡힘. 한글 이름은 못 잡음. 계좌는 `110-<US_SSN>` 으로 일부만 가려짐 |
| 담기 | 49 → 9 | 1 | 박민지 ×1 (body 안) | 정규식 innerScan 도 한글 이름은 못 잡음 |
| 담기 (innerScan=presidio) | 49 → 9 | 1 | 박민지 ×1 | 내부 재검사를 Presidio 로 바꿔도 같은 결과 |

---

## 실험 타당성 메모 (발표·보고서에서 방어해야 할 지점)

1. **Presidio 과잉 마스킹.** 기본 설정은 일부 `timeUnixNano`(4건)·`observedTimeUnixNano`(2건)와 버전 문자열 `2.0.2` 를 `<DATE_TIME>` 으로, `v0.11.1` 을 `<US_DRIVER_LICENSE>.11.1` 로, 호스트명 `otel-demo-node-02` 를 `otel-demo-<DATE_TIME>` 으로, spanId 하나를 `<NRP>` 로 바꿉니다. 시간 순서나 trace 관계가 깨질 수 있으므로 빼기의 RCA 점수에 영향을 줍니다. 이것도 빼기 방식의 실제 특성이라 기본값으로 둡니다. 구조 필드를 검사에서 빼기로 정한다면 `application.yml` 의 `yeogwasigan.presidio.skip-fields` 에 **실험 시작 전에** 적고 끝까지 유지하세요. (`run_meta.json` 에 기록됩니다)
2. **잔존은 "완전한 원문 문자열"만 셉니다.** 계좌번호가 `110-<US_SSN>` 처럼 일부만 남으면 0건으로 셉니다. 부분 노출까지 보려면 채점 규칙을 따로 정해야 합니다.
3. **innerScan 의 비대칭.** 담기의 정규식은 국내 계좌 형식을 알고 있고, Presidio 기본 설정은 모릅니다. "담기의 이득이 필드 선택 덕분인가, 정규식 덕분인가"라는 반박에 대비해 템플릿의 `innerScanEngine: presidio` 로도 돌려 두었습니다(위 표 마지막 줄 — 결과 같음). 보고서에 두 결과를 함께 싣는 것을 권합니다.
4. **`parentSpanId` 토큰화를 추가했습니다.** 원안의 `fieldActions` 에는 spanId 만 토큰화되어 있었습니다. 그러면 parentSpanId 는 원문으로 나가서 부모-자식 연결이 끊깁니다.
5. **`fieldActions` 에 적힌 필드는 담깁니다.** `container.id` 와 `host.name` 은 `requiredFields` 에는 없지만 가명화해서 내보냅니다(그래서 7개가 아니라 9개 필드). 빼고 싶으면 템플릿에서 해당 줄을 지우면 됩니다.
6. **실제 OTLP 로그에는 `parentSpanId` 가 없습니다.** span 데이터에만 있습니다. 실제 로그로 바꾸면 이 필드는 비어 있게 되니, 필요하면 trace 데이터와 조인하는 단계를 따로 두세요.
7. **Presidio 이미지 버전 고정.** compose 는 `:latest` 를 씁니다. 본 실험 전에 `docker images --digests | grep presidio` 로 digest 를 기록하거나 태그를 고정하세요. `run_meta.json` 에는 analyzer 가 지원하는 엔티티 목록이 남습니다.
8. **블라인드의 한계.** 흔적을 `[가림]` 으로 바꿔도 응답 문체나 언급한 단서로 조건을 짐작할 여지는 남습니다. 채점자 2명 이상이 따로 채점하고 일치도를 함께 보고하는 것을 권합니다.
9. **mock 응답은 실험 결과가 아닙니다.** 받은 로그를 규칙대로 요약하는 개발용 응답입니다.

---

## 실제 로그로 교체하기

1. `scenarios/<새_시나리오_ID>/` 폴더를 만듭니다. (폴더 이름이 곧 토큰화 salt 입니다)
2. `raw.jsonl` 을 넣습니다. 두 가지 형식을 모두 읽습니다.
   - 한 줄에 레코드 하나: `{"resource": {...}, "scope": {...}, "logRecord": {...}}`
   - OTel Collector `file` exporter 출력(`{"resourceLogs": [...]}`): 자동으로 펼칩니다.
3. `injected_pii.yaml` 에 민감값을 `items: [{id, type, value, locations}]` 형식으로 적습니다. `value` 만 채점에 쓰입니다.
4. `truth.yaml` 에 근본 원인(`root_cause.summary`), 필요 근거, 채점 기준(`rca_rubric`)을 적습니다. 값에 `: ` 가 들어가면 따옴표로 감싸야 YAML 로 읽힙니다.
5. 실험을 실행하면 새 시나리오도 자동으로 포함됩니다. 웹 데모에도 샘플 목록에 바로 나타납니다(서버 재시작 불필요).
