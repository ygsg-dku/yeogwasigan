# 로그 보는 법 (여과시간 Pilot)

5주차 Pilot에서 쓰는 OpenTelemetry Demo 로그를 읽는 법이다. 로그는 레포에 압축(`.jsonl.gz`)으로 올라가 있다. 쓰기 전에 풀어서 쓴다:

```bash
gunzip -k experiments/pilot/scenarios/*/*.jsonl.gz
```

## 폴더 구성

`scenarios/` 아래에 시나리오마다 폴더가 하나씩 있다. `S0_normal`은 장애 없는 정상 로그 5분, `S1`~`S6`은 장애 로그다.

| 파일 | 내용 |
| --- | --- |
| `raw.jsonl` | 원문. 데모가 남긴 그대로 |
| `redacted.jsonl` | 데모가 민감정보를 가린 버전(= 빼기-OTel 조건). 데모는 span만 가리고 log는 가리지 않는다 |
| `window.json` | 수집 시각과 길이 |
| `fields.md` | 필드 이름 목록. 값은 없다 (`S0_normal`에만 있음) |

## 기록 세 종류

- **span**: 서비스가 일 하나를 처리한 기록이다. 어느 서비스(`service.name`)가, 무슨 일(`name`)을, 얼마나 걸려서(시작~끝 시간) 했는지, 성공했는지 실패했는지(`status.code`)가 적힌다.
- **trace**: 손님 요청 하나가 여러 서비스를 거치며 남긴 span 묶음이다. 같은 `traceId`를 가진다. span은 부모 span(`parentSpanId`)으로 이어져 나무 모양이 된다.
- **log**: 서비스가 남긴 메시지 한 줄이다. 시간(`timeUnixNano`), 심각도(`severityText`: INFO / WARN / ERROR), 본문(`body`)이 있다. `traceId`가 있으면 어느 요청을 처리하다 남긴 로그인지 알 수 있다.
- **필드(속성)**: 기록에 붙은 칸이다. 예: `http.response.status_code=200`. 전체 목록은 `S0_normal/fields.md`에 있다.

## 정상 주문 하나

`S0_normal`에서 실제로 뽑은 trace다. 가짜 손님과 장애 스위치 관련 기록은 숨겼다.

```
frontend | 주문 API                  | 97ms
  checkout | PlaceOrder(주문 처리)    | 58ms
    checkout | 상품·배송비 준비         | 39ms
      product-catalog | GetProduct  | 14ms
      shipping | get-quote(배송비)   | 2ms
      cart | GetCart(장바구니)       | 0ms
      currency | Convert(환율)       | 0ms
    email | 주문 확인 메일             | 2ms
```

- 들여쓰기는 부른 쪽에서 불려 간 쪽으로 내려간다. 주문 API가 checkout을 부르고, checkout이 상품·배송·장바구니·환율 서비스를 부른다.
- 시간은 아래에 있는 일까지 포함한 값이다.

## 장애일 때 읽는 순서

1. **ERROR인 span을 찾는다.** raw JSON에서는 `status.code`가 `2`인 span이다. 가장 아래(가장 깊은) ERROR까지 내려가서 메시지(`status.message`, `exception.message`)를 읽는다. 위쪽 ERROR는 그 여파다.

   ```
   frontend | 주문 API       | ERROR
     서비스 A | 처리         | ERROR: B 호출 실패
       서비스 B | 처리       | ERROR: 실제 원인 메시지   ← 시작점
   ```

2. **ERROR가 없으면 느린 span을 찾는다.** `S0_normal`에서 이름이 같은 span과 걸린 시간을 비교한다.
3. **같은 trace의 WARN·ERROR 로그 본문을 읽는다.** `severityNumber`가 13 이상이면 WARN, 17 이상이면 ERROR다.
4. **잡음은 무시한다.**
   - `load-generator`: 가짜 손님이다. 우리가 뺀 챗봇 서비스(`agent`)를 부르다 실패한 ERROR가 계속 찍힌다.
   - `flagd`, `flagd-ui`, 그리고 flagd를 부르는 기록: 장애 스위치 장치다.

## 파일 직접 열기

`raw.jsonl`은 한 줄이 아주 긴 JSON 묶음이라 에디터로 열면 읽기 힘들다. 한 줄만 펼쳐 보려면 이렇게 한다.

```bash
head -1 scenarios/S0_normal/raw.jsonl | python3 -m json.tool | less
```

한 줄의 구조는 이렇다.

```
resourceSpans[]
  resource.attributes          ← service.name 같은 서비스 정보
  scopeSpans[].spans[]         ← traceId, spanId, parentSpanId, name,
                                  startTimeUnixNano, endTimeUnixNano, attributes, events, status
resourceLogs[]
  resource.attributes
  scopeLogs[].logRecords[]     ← timeUnixNano, severityText, severityNumber, body, attributes, traceId, spanId
```

시간은 1970년 1월 1일부터 센 나노초다. 걸린 시간은 `endTimeUnixNano - startTimeUnixNano`로 구한다.
