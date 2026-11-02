# 단계별 처리 시간 표 (초안)

- 측정일: 2026-11-02
- 담당: 소희 (DB·평가·화면)
- 상태: **초안**. AI 응답은 mock이라 미측정, 게이트웨이 앞 구간도 미측정.

## 1. 무엇을 쟀나

요청 한 건이 거치는 단계별 시간을 DB의 감사 기록(`audit_event`)의 시각(`at`)으로 쟀다.

| 단계 | 쟀나 | 방법 |
|---|---|---|
| 게이트웨이 앞 구간 (필터·경고 검사·해시) | 미측정 | 감사 기록의 첫 줄(`CREATED`)이 처리 후에 찍혀서 DB에는 없음. 브라우저 개발자 도구로 따로 재야 함 |
| 승인 대기 (사람) | 측정 불가 | 직접 클릭한 시간이라 실제 승인자의 소요를 대표하지 않음 |
| 결정 → 전송 완료 | **측정함** | `APPROVED`(또는 `APPROVAL_SKIPPED`) → `SENT` 시각 차 |
| AI 응답 | 미측정 | mock 모드라 의미 없음. 실제 AI 키로 재측정 필요 |

## 2. 측정 환경

- DB: H2 파일 DB (`data/gateway`), `AUTO_SERVER=TRUE`
- AI 호출: mock (`YG_AI_MOCK=true`). 응답 방식 `answer_mode = mock`
- 모드: ALWAYS(기본) / CONDITIONAL (`YG_APPROVAL_MODE`)
- 입력: 시나리오 샘플 (S3 311건 · 높음 경고 0 / S1 313건 · 높음 경고 3)

## 3. 결과: 결정 → 전송 완료

| 모드 | 요청 | 샘플 | 시간 (ms) |
|---|---|---|---|
| ALWAYS | REQ-0008 | S1 (313건, 높음 3) | 20 |
| ALWAYS | REQ-0009 | S3 (311건, 높음 0) | 5 |
| ALWAYS | REQ-0010 | S3 | 8 |
| ALWAYS | REQ-0011 | S3 | 7 |
| CONDITIONAL | REQ-0005 | S3 | 20 |
| CONDITIONAL | REQ-0006 | S3 | 6 |

- ALWAYS: 5~20 ms (4건), CONDITIONAL: 6~20 ms (2건).
- 20 ms가 나온 두 건(REQ-0008, REQ-0005)이 느린 이유는 확인하지 못했다. 서버를 켠 뒤 첫 전송이라서일 수 있다는 것은 추측이다.
- 표본이 적어 범위로만 쓰고 평균은 내지 않는다.

## 4. 해석

- 결정 이후 전송 처리 시간은 두 모드 모두 수 ms ~ 20 ms로, 모드별 차이는 보이지 않는다.
- 두 모드의 차이는 처리 속도가 아니라 **승인 단계에서 사람이 개입하는지**이다. 조건부 모드는 높음 경고가 0건인 요청에서 이 단계를 없앤다 (감사 기록 `APPROVAL_SKIPPED`).
- 사람 승인 대기 시간은 시스템으로 측정할 수 없다.

## 5. 제외한 기록

| 요청 | 이유 |
|---|---|
| REQ-0007 | `SEND_FAILED`. AI 호출이 401(인증 실패)로 거절됨. API 키 설정 문제 |
| REQ-0008의 요청→승인 642,256 ms | 직접 클릭 시간이라 측정값으로 쓰지 않음 |

## 6. 남은 일

1. 게이트웨이 앞 구간: 브라우저 개발자 도구(Network)로 "파생본 만들기" 요청 소요 시간을 3회 이상 측정
2. AI 응답: 실제 키 설정 후 ALWAYS·CONDITIONAL 각각 재측정 (mock 해제)
3. 표본 늘리기 (각 모드 5건 이상)

## 7. 재현 방법

서버를 켠 뒤 요청을 만들고 승인(또는 조건부 전송)한 다음, H2 쉘에서 아래 쿼리를 실행한다.

```sql
SELECT request_id,
 DATEDIFF('MILLISECOND',
   MIN(CASE WHEN type IN ('APPROVED','APPROVAL_SKIPPED') THEN at END),
   MIN(CASE WHEN type='SENT' THEN at END)) AS decide_to_sent_ms
FROM audit_event
GROUP BY request_id ORDER BY request_id;
```
