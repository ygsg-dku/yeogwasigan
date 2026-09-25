# 여과시간 (yeogwasigan) — Claude Code 작업 메모

캡스톤 프로젝트. 망분리 환경의 장애 로그를 외부 AI로 보낼 때 민감정보 여과 방식 3가지를 비교하는 실험 플랫폼 + 데모 웹.
Spring Boot 3.5.6 / Java 17 / Gradle 8.14.3. 주석·README·커밋 메시지는 한국어로 작성한다.
자세한 실행 방법과 설계 원칙은 README.md 참고.

## 꼭 지켜야 할 실험 규칙 (바꾸면 실험이 무효가 된다)
- 세 필터(PASSTHROUGH / DENYLIST / ALLOWLIST)는 `LogFilter` 인터페이스로 입출력을 통일한다. 필드 수 계산은 `FilterResult.of()` 한 곳에서만.
- 웹(`AnalyzeController`)과 실험(`ExperimentRunner`)은 반드시 `FilterRegistry`로 같은 필터 구현체를 쓴다.
- DENYLIST는 Presidio 공식 Docker 이미지를 기본 설정 그대로 호출한다(`{text, language:"en"}`만 전송). 커스텀 인식기 추가 금지. 못 잡는 건 못 잡는 대로 둔다.
- Presidio에 연결 안 되면 DENYLIST는 예외를 던진다. 절대 원본으로 대체하지 않는다.
- AI 질문은 `ExperimentConstants.FIXED_QUESTION` 하나로 고정, temperature 0 고정.
- 담기(ALLOWLIST)가 무조건 이기도록 만들지 않는다. 담기가 지는 함정 케이스도 측정 대상이다.

## 현재 상태 (2026-09-25)
- 코드 작성 완료. 단위 테스트 20개. 샘플 `scenarios/s01_payment_fail` 기준 실측: 그대로 49→49 잔존 22 / 빼기 49→49 잔존 3 / 담기 49→9 잔존 1.
- 2026-09-25 로컬(macOS, JDK 25)에서 `./gradlew test` 통과(20/20), `bootJar` 실행 시 8080 기동 확인.
- AI: `.env` = OpenAI / `gpt-5.1-2025-11-13`, `reasoning_effort=none` 고정(`ExperimentConstants.REASONING_EFFORT`; none 일 때만 temperature 0 허용).
  요청은 `max_completion_tokens` 사용(gpt-5.x는 max_tokens 거부). 이 계정 TPM: gpt-5.1 50만 / gpt-4o·gpt-4.1 3만.
  `application.yml`의 `spring.config.import`로 bootRun·java -jar도 `.env`를 읽는다. s01 + ALLOWLIST live 호출 성공 확인.
  → 남은 확인: `docker compose up --build` (Presidio 연동 포함). Docker 데몬이 꺼져 있어 아직 못 함.
- 파이썬 판(`../yeogwasigan`)이 먼저 있었고 이 프로젝트는 그 포팅판. 파이썬 판의 `truth.yaml`은 rca_rubric 값에 `": "`가 있어 YAML 파싱 오류가 난다(이쪽 판은 따옴표로 수정함).

## 실제 수집 로그: `../../scenarios/` (캡스톤/scenarios)
OpenTelemetry Demo에서 수집. 시나리오 S0_normal, S0_baseline, S1_paymentFailure, S2_paymentUnreachable,
S3_productCatalogLockContention, S4_kafkaQueueProblems, S5_intlShippingSlowdown, S6_productCatalogFailure
(+ 이름에 `.실패_...`가 붙은 폴더는 수집 실패분 — 쓰지 않음). 폴더마다 raw.jsonl, redacted.jsonl, window.json.
원본 파일은 수정하지 말고, 전처리 결과는 새 파일로 만든다.

분석 결과:
1. 한 줄에 `resourceLogs` 또는 `resourceSpans`가 섞여 있다(OTel Collector file exporter). → `LogFormat.parse`가 span·metric 줄을 건너뛰도록 수정함. 7개 시나리오 전부 파싱 확인.
2. 크기가 너무 크다. 로그만 2,183~11,720건, 270만~1,490만 자. AI 컨텍스트 한도를 훨씬 넘으므로 장애 구간 자르기 등 공통 전처리가 필요하다(세 조건에 똑같이 적용).
3. 정답 노출: S3 body "lock contention scenario active...", S4 body "FeatureFlag 'kafkaQueueProblems' is enabled", S5 body "...due to intlShippingSlowdown feature flag".
   body는 담기도 보내므로 이대로면 세 방식이 똑같이 정답을 맞힌다. S1·S2는 노출 없음.
   S6은 `exception.message` 속성에만 "Product Catalog Fail Feature Flag Enabled" → PROTOCOL v1.3 에 따라 문구만 지움("Product Catalog Fail").
   `S0_normal/fields.md`에 "장애 스위치 흔적(flagd·flagd-ui, feature_flag.*)은 모든 조건에서 뺀다"고 적혀 있으나 아직 적용 안 됨.
4. 로그에는 민감정보가 거의 없다(Docker 내부 IP 172.18.x, userId 정도). 이메일·카드번호·CVV는 span에만 있다 → 민감정보 주입 + injected_pii.yaml 필요.
5. redacted.jsonl = Collector redaction 처리본. 로그는 raw와 동일, span만 다름(카드 마스킹, user.email→user.hash, cvv 삭제).
6. S0_baseline의 raw/redacted 마지막 줄(357)이 잘려 있다. S0_normal을 쓰면 됨.
7. S1의 payment 실패 로그는 ERROR가 아니라 WARN(severityNumber 13).

## 다음 할 일
1. ~~`LogFormat.parse`: `resourceSpans` 줄은 건너뛰고 로그만 읽기~~ (완료)
2. 공통 전처리 (`preprocess` 프로파일, `preprocess/Preprocessor.java`, 설정 `application-preprocess.yml`)
   - 완료: ① 실험 장비 제거(load-generator·otelcol-contrib·flagd, feature_flag* 속성, /flagservice/ 프록시 로그)
     ② 반복 줄이기(서비스·심각도·메시지패턴·상태코드·경로로 묶어 처음 5 + 마지막 1 + 가장 느린 3, WARN+·5xx 전부 보존)
     ⑤ '그대로' 추정 토큰 상한 25만(문자수/4). k=10은 S0·S5·S6 초과 → k=5 로 전 시나리오 통과(12~19만 토큰).
   - 규칙은 application.yml `yeogwasigan.preprocess`(웹과 공유), 대상 목록만 application-preprocess.yml.
     웹 데모: [공통 전처리] 체크 시 같은 Preprocessor 적용(요청 필드 preprocess). 1M자 넘는 업로드는 편집창에 안 넣고 자동 체크.
     /api/analyze 는 추정 토큰이 상한을 넘으면 AI 호출 전에 400. Jackson 문자열 한도 2억 자(JacksonConfig).
   - 결과: `scenarios/<ID>/raw.jsonl` + `preprocess_report.json`. raw.jsonl 은 원본(캡스톤/scenarios)을 이미 180초 창으로 자른 것.
   - ③ 주입 흔적 제거 완료 — 팀 기준 `experiments/pilot/PROTOCOL.md` v1.3 을 따름 (설정 strip-phrases / drop-if-contains, 대소문자 구분):
     S6 `" Feature Flag Enabled"` 문구만 삭제(38건 ×2 속성), S4 FeatureFlag 로그 199+1건·S5 스위치 로그 6건 통째 삭제.
     스위치 속성 키 삭제는 통째 삭제 판정 뒤에(그 전에 지우면 흔적 로그를 놓침).
     ⚠ S3 "lock contention scenario active…"(4건)는 PROTOCOL 규칙에 안 걸려 남아 있음 → 팀 결정 필요.
     S6 은 문구 삭제로 더 이상 담기 함정이 아님. 자연스러운 함정은 S2(`badAddress`가 exception.message 에만).
   - ④ 민감정보 주입 완료 (PROTOCOL v1.1 민감 키, 6자 이상). 적용 순서 ① → ③ → ② → ④ → ⑤.
     원문 span(`SpanPiiExtractor`)·로그의 민감 키 값 중 결과에 이미 있는 값 = 자연 발생, span 에만 있던 값은
     같은 traceId 로그(같은 서비스 우선)에 키마다 속성/본문 번갈아 최대 5개 주입 → `scenarios/<ID>/injected_pii.yaml`.
     시나리오별 민감값 22~70개. 필터만 돌린 잔존(회): S1 그대로 44 / 담기 7, S6 198 / 43.
     담기 잔존은 본문에 들어간 UUID(session.id·user.id·order.id)를 정규식 내부검사가 못 잡아서 — 실제 한계로 측정됨.
     카드번호·이메일은 담기에서 0. CVV·lastFourDigits 는 6자 미만이라 PROTOCOL 기준대로 제외.
   - 남음: truth.yaml 에 "꼭 남아야 할 증거" 적고 전처리 후 자동 검사.
3. 시나리오마다 `truth.yaml`, `injected_pii.yaml` 작성
   - 리허설(2026-09-25, results/20260925_192043, 그대로+담기, 1회, ③④ 없음): S2도 함정 케이스였다 —
     정답 단서 `badAddress`가 exception.message/stacktrace 에만 있어 담기는 Kafka로 오답. S6도 예상대로 담기 오답.
     S5는 정답 노출 문장이 남아 있었는데도 '그대로'(15.7만 토큰)는 "장애 없음"으로 놓침 → 긴 입력에서 단서를 놓치는 효과도 측정 대상.
4. API 키 넣고 mock → live 순서로 실험, 블라인드 채점(채점자 2명 이상)

결정(2026-09-25): 실험 대상은 **로그만**. span은 쓰지 않는다. → 민감정보는 로그에 주입해야 한다(분석 결과 4번).
