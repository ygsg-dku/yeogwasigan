# 15. 패키지 JSON 형식과 재현성

7주차 B → A·C·D 핸드오프. 담기를 거쳐 외부로 나가는 **패키지(파생본)** 의 형식을 확정하고, 같은 로그는 같은 해시가 나옴을 golden fixture로 고정한다.

## 1. 형식

- **JSON Lines**. UTF-8, 한 줄에 레코드 하나, 줄 구분은 `\n`.
- 레코드는 OpenTelemetry 3층 구조 `{ "resource": {...}, "scope": {...}, "logRecord": {...} }`.
- 층 안의 키는 점(.)이 들어간 평탄 키 그대로 쓴다. 예: `"resource":{"service.name":"shipping"}`.
- 레코드와 칸의 순서는 원본 순서를 유지한다.
- 업무 목록에 없는 칸은 나가지 않는다(`onUnknownField: DROP`). 비어 있는 층은 `{}`로 남는다.

값 변환은 세 가지뿐이다.

| 변환 | 결과 | 예 |
|---|---|---|
| `TOKENIZE_CASE_SCOPED` | `TX_` + HMAC 앞 8자리(대문자) | `TX_0226A928` |
| `PSEUDONYMIZE_CASE_SCOPED` | `PS_` + HMAC 앞 8자리(대문자) | `PS_9C1B07AA` |
| `GENERALIZE_TO_WINDOW` | 5분 구간의 시작 시각 + `/PT5M` (ISO 8601). 읽을 수 없는 값은 칸째 버림 | `2025-09-15T05:10:00Z/PT5M` |

## 2. 해시

```
packageSha256 = SHA-256( UTF-8( 레코드 JSON을 "\n"으로 이어 붙인 문자열, 끝 개행 없음 ) )
```

구현: `GatewayService.packageSha`. 승인 지문은 `SHA-256(packageSha256 | listVersion | endpointId)`다.

## 3. 같은 로그는 같은 해시가 나온다 — 조건

| 조건 | 내용 |
|---|---|
| 사건 ID | `"CASE-" + SHA-256(입력 원문)` 앞 12자리. 요청 번호가 아니다 (`GatewayService.caseId`) |
| 토큰화 키 | 서버 비밀키 `YG_TOKENIZE_SECRET`. 테스트는 고정 키를 쓴다 |
| 업무 목록 | 같은 목록(`listVersion`이 같음) |

**주의**: 운영에서 `YG_TOKENIZE_SECRET`을 비워 두면 서버를 시작할 때마다 무작위 키가 만들어져, 서버를 다시 띄우기 전과 후의 해시가 달라진다. 해시 재현이 필요한 환경에서는 키를 고정해야 한다. 토큰이 사건 ID만으로 되짚어지지 않도록 키는 비밀로 유지한다.

## 4. 두 업무 목록 (POLICY-02)

같은 로그(s01_payment_fail, 11건)에서 만든 패키지:

| 업무 목록 | 담는 칸 | 필드 수 (원본 49) |
|---|---|---|
| `INCIDENT_ANALYSIS` | 서비스, 시각, 심각도, 본문(WARN 이상·5xx만), traceId·spanId·parentSpanId(토큰), 오류 메시지·종류, 응답 코드 | 10 |
| `INCIDENT_REPORT_DRAFT` | 서비스, 버전, 시각(5분 구간), 심각도, 응답 코드 | 5 |

`INCIDENT_REPORT_DRAFT`의 `logRecord.severityText`는 정책 명세 표에 없는 칸이며 보고서에 심각도가 필요해서 추가했다(2026-10-12).

## 5. golden fixture

위치: `experiments/yeogwasigan-spring/src/test/resources/golden/s01_payment_fail/`

| 파일 | 내용 |
|---|---|
| `INCIDENT_ANALYSIS.package.jsonl` | 분석 목록으로 만든 패키지 |
| `INCIDENT_REPORT_DRAFT.package.jsonl` | 보고서 초안 목록으로 만든 패키지 |
| `manifest.json` | 입력 해시, 건수, 필드 수, 목록 버전, 패키지 해시 |

테스트: `PackageGoldenTest`(golden 일치, 재실행 해시 동일, 두 목록은 다른 패키지), `GeneralizeWindowTest`(시각 구간화).

**형식이나 업무 목록을 바꾸면 이 테스트가 깨진다.** 의도한 변경이면 `GOLDEN_UPDATE=1`로 `PackageGoldenTest`를 돌려 파일을 다시 만들고, 변경분을 검토한 뒤 커밋한다. 형식 변경은 A·C·D에게 알린다.

## 6. 알려진 차이 (결정 필요)

- `INCIDENT_ANALYSIS`의 timestamp는 명세가 `KEEP_RELATIVE_TIME`이지만 지금은 절대 시각(나노초)을 그대로 담는다.
- `INCIDENT_REPORT_DRAFT`의 "event order: milestone만 KEEP"은 milestone을 고르는 규칙이 없어, 기록 순서만 유지한다.
