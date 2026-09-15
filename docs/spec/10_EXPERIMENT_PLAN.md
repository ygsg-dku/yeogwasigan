# 여과시간 Experiment Plan

## 목적과 사전 등록

실험은 탐지·마스킹·라우팅 자체의 신규성을 주장하지 않는다. 검증 질문은 다음 하나다.

> 동일한 보안·실행 조건에서 Purpose별 field/event/relation 선택이 Strong Fixed Policy보다 불필요한 공개를 줄이면서 AI 업무 근거를 유지하는가?

Week 2 종료 전에 Purpose contract, B3 허용 feature, generator version, dataset split, scoring rubric, model/prompt/decoding, acceptance rule을 동결한다. 동결 뒤 Locked 결과를 보고 B3·Purpose·rubric·scorer를 유리하게 바꾸지 않는다.

## 역할 분리와 Gold 격리

| 역할 | 담당 | 접근 규칙 |
| --- | --- | --- |
| Purpose/Policy | B | Task Brief로 contract 작성; Locked Gold와 결과 접근 금지 |
| Incident generator/split | D | generator seed와 locked input 봉인; 구현 결과로 사건 수정 금지 |
| Gold Annotation | C | raw incident와 Task Brief만 읽음; P/B3/residual scanner 코드 수정 금지 |
| B3/runner/scorer | A | Dev fixture로 구현; condition blind output scoring |

Task Brief에는 업무 결과와 성공 예시만 넣고 required/prohibited atom 정답을 넣지 않는다. Gold는 별도 benchmark 계정·repository·image·volume에만 존재한다. Runtime image, Docker volume, PostgreSQL, 운영 API, React build에서 Gold 파일명·내용·endpoint가 없어야 한다.

## Dataset

| Split | 사건 수 | 용도 | 접근 |
| --- | ---: | --- | --- |
| Pilot | 6 | Week 2 gate, 구조·가설 조기 확인 | 전원 |
| Dev | 10 | 구현·tuning·회귀시험 | 전원 |
| Locked | 18 | Week 11 최초 평가 | C/D 봉인, 실행 시 공개 |

모든 데이터는 합성 PAYMENT_INCIDENT_JSON이다. 원인군은 timeout, connection pool exhaustion, authentication failure, retry storm 등 최소 4종을 포함한다. 각 사건에는 고객명, 계좌, 거래 ID, 내부 IP, Secret canary와 event/relation을 넣는다. 사건 단위가 독립 표본이며 같은 사건의 값만 바꾼 복제본은 split을 넘지 않는다.

Gold annotation은 sensitive span/field, event node, relation edge, root cause, Purpose별 required/prohibited atom을 가진다. 이는 탐색적 capstone PoC이며 모집단 우월성 또는 통계적 유의성을 주장하지 않는다.

## 공정한 비교 조건

~~~text
same schema parse + normalize
→ same detector/findings
→ same Secret DROP
→ same case-scoped tokenization
→ B3 fixed selection OR 여과시간 purpose selection
→ same residual scan
→ same destination/network
→ same model/prompt/decoding
→ same scorer
~~~

| 조건 | 선택 규칙 |
| --- | --- |
| B0 Original | 합성 benchmark에서만 원본 효용 상한 확인 |
| B1 Full Redaction | 민감·내부 atom 전부 제거 |
| B3 Strong Fixed Policy | 모든 Incident 업무에 INCIDENT_SAFE_FIXED_V1 하나의 field/event/relation package |
| P 여과시간 | B3와 공유 파이프라인을 쓰고 Purpose별 selection만 다름 |

B3에 더 약한 detector를 쓰거나, 여과시간에만 residual scan을 적용하거나, B3의 관계 token을 깨거나, 조건별 model/prompt/network를 바꾸면 비교는 무효다.

## Experiment A: Purpose 차이

- 질문: 동일 원문·동일 T1 destination에서 Purpose만 바꾸면 계약대로 다른 패키지가 생성되는가?
- 조건: INCIDENT_ANALYSIS vs INCIDENT_REPORT_DRAFT.
- 고정: 사용자 role, raw incident, detector, Destination, tokenization.
- 측정: package schema conformance, action 일치, event/relation 차이, deterministic rerun.
- 성공: Locked 18건에서 hard Secret 0, manifest action 100% contract 일치, Purpose 차이가 contract가 허용한 atom에만 존재.
- 실패: 두 Purpose package가 같음, contract 밖 atom 존재, 동일 입력 재실행 결과 불일치.

## Experiment B: Privacy × Utility

- 질문: INCIDENT_ANALYSIS에서 P가 B3보다 불필요 노출을 줄이면서 RCA evidence를 유지하는가?
- Privacy 지표: hard Secret residual count, prohibited atom residual rate, unnecessary disclosure rate.
- Utility 지표: root-cause code exact match, required event recall, required relation recall, 근거 없는 답변 비율.
- 모델 통제: model snapshot, prompt, decoding, response schema, 사건당 반복 3회는 조건별로 동일하게 동결한다.
- 채점: C/D가 condition ID를 모르는 상태에서 독립 채점하고 불일치는 합의 기록을 남긴다.

사건별 joint pass는 P의 prohibited/unnecessary disclosure가 B3 이하이고 root cause, required event recall, required relation recall이 각각 B3 이상인 경우다.

사전 acceptance:

- hard Secret residual은 0.
- Locked 18건 중 joint pass 15건 이상(80% 이상).
- 불필요 공개가 B3보다 엄격히 감소한 사건 11건 이상(60% 이상).
- 세 utility 지표 중 하나라도 B3보다 낮은 사건은 3건 이하(20% 이하).

parser, model, runner 실패는 사후 제외하지 않고 joint fail이다. Gold 자체 결함이 확인되면 한 사건만 빼지 않고 해당 locked run 전체를 무효화하고 새 seed·Gold·hash로 재봉인한다. 실패와 win/tie/loss를 모두 공개한다.

## Experiment C: Policy와 Network Enforcement

- 질문: Purpose를 고정했을 때 Trust·데이터 상태에 따른 정책 결과와 실제 경로가 일치하는가?
- 사전 case: 역할 허용/거부, T2/T1/T0, Secret 잔존, parser/scanner failure, destination spoof, policy change, approval reuse, direct-connect.
- 측정: policy decision accuracy, expected manifest, actual receiver, canary receipt, direct-connect exit code.
- 성공: 핵심 case 18/18 expected result, T0 receiver 0, Internal direct T1/T0 성공 0, Secret-containing T1 receiver 0.

## 실행 절차와 Evidence

1. Week 1에 Pilot/Dev/Locked seed와 Task Brief 초안을 만든다.
2. Week 2에 Pilot을 실행하고 gate 기록을 남긴다. contract, rubric, input/Gold hash를 봉인한다.
3. Week 3~9에는 Pilot/Dev만으로 구현한다.
4. Week 10에 B3/P build, model/prompt, scorer, generator hash를 locked_manifest에 기록한다.
5. Week 11에 Locked를 최초 실행하고 raw output, receiver log, blind score, paired table을 수정 없이 보존한다.

locked_manifest에는 Task Brief hash, Purpose version, B3/P implementation hash, generator/seed hash, input/gold hash, rubric/scorer hash, model/prompt/decoding hash, network topology hash를 기록한다.

