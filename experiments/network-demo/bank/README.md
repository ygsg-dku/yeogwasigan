# 여과은행 시뮬레이터 (회사 서버 역할, 가상 은행)

교수님 피드백 "은행 시뮬레이션처럼 시스템을 학교 서버에 두고 에러 로그를 일으켜서 시연"을 위한 가상 은행이다.
[`../company/payment_service.py`](../company/payment_service.py)(카드결제 한 종류, 스위치로 에러 줄을 찍음)를 대신한다.
전송기 [`../company/shipper.py`](../company/shipper.py)는 고치지 않고 그대로 쓴다.

설계 근거 · 로그 칸 46개 전체 · 테스트용으로서의 한계는 [docs/여과은행_시뮬레이터_설계.md](docs/여과은행_시뮬레이터_설계.md).

국내 은행의 **채널계 → 계정계 → 대외계** 구분을 축소했다. 세 프로세스가 실제로 HTTP 로 서로 부르고, 같은 traceId 를 넘긴다.
장애는 스위치가 아니라 **진짜 원인**(프로세스 멈춤 · DB 잠금 · 잘못된 배포)으로 일으킨다.

```
 가상 손님 + 시연자 브라우저
        │
 ┌──────▼───────────┐  HTTP   ┌──────────────────┐  HTTP   ┌──────────────────┐
 │ 채널계 :9000      │ ──────▶ │ 계정계 :9100      │ ──────▶ │ 대외기관 :9200    │
 │ channel.py       │         │ core.py (sqlite) │  타행만  │ kftc_sim.py      │
 └──────┬───────────┘         └──────┬───────────┘         └──────┬───────────┘
        └──────────── 같은 traceId 로 logs/bank.jsonl 에 기록 ───────┘
                                   │  shipper.py --log bank/logs/bank.jsonl
                                   ▼
                         게이트웨이 → 외부 AI
```

- 은행 이름(여과·가람·누리·다솜)은 모두 가상이다. 고객 50명은 고정 seed 로 만든 가짜이고, 주민번호는 형식만 맞고 검증 숫자가 틀린 번호다.
- 로그는 기존과 같은 OpenTelemetry 3층 JSON(resource / scope / logRecord). 칸은 약 40개.
- 파이썬 표준 라이브러리만 쓴다 (sqlite 포함). Ubuntu 24.04 기본 python3 로 바로 돈다.

## 파일

| 파일 | 하는 일 |
|---|---|
| `common.py` | 로그 기록기, traceparent, 가짜 고객·상대 계좌, HTTP 도우미 |
| `channel.py` | 채널계: 인터넷뱅킹 화면(`static/index.html`) · API · 가상 손님 (본인확인 10 · 조회 50 · 당행 25 · 타행 15%) |
| `core.py` | 계정계: 원장 `data/ledger.db` (WAL), 조회 · 당행이체 · 타행이체 (대외기관 실패 시 출금 취소) |
| `kftc_sim.py` | 대외기관 흉내: 타행이체 중계 |
| `batch_job.py` | 일 마감 이자 배치: 원장 잠금을 한 트랜잭션으로 오래 잡는 실수 (장애 ②) |
| `bank.sh` | 켜고 끄기 + 장애 일으키기 (실행하는 실제 명령을 `+` 로 찍음) |
| `make_scenarios.py` | 장애마다 새로 돌려 필드 선택 알고리즘 입력 폴더를 만든다 → `scenarios/BANK_*` |
| `ops_monitor.py` | 운영 모니터 (회사 VM 안의 개발자·운영자 화면, `static/monitor.html`): 서비스 상태 · 5분 에러 그래프 · 실시간 로그 · traceId 추적 · 장애 버튼 · [여과시간에 분석 요청] |

## 실행

```bash
cd experiments/network-demo/bank
YG_DEMO_PASSWORD='게이트웨이와 같은 비밀번호' ./bank.sh start 3
#   인터넷뱅킹 http://localhost:9000/ · 운영 모니터 http://localhost:9300/
#   VM 에서는 노트북에서 ssh -L 9000:localhost:9000 -L 9300:localhost:9300 -p 2202 ubuntu@10.0.1.37
./bank.sh status
./bank.sh stop           # reset 은 로그·원장까지 지운다
```

### 운영 모니터

실무에서 Kibana · Grafana 로 에러를 보는 것을 작게 흉내 낸 화면이다. 회사 VM 안에서 돌고, 원문 로그를 그대로 보여 준다.

- **장애 만들기 (시연용)**: 버튼이 `bank.sh` 의 장애 명령을 그대로 실행하고, 실행한 명령(`+ kill -STOP 12345` 등)을 화면에 찍는다. `↺ 모두 복구` 는 `./bank.sh recover`
- **장애 감지**: 30초 안 ERROR 8건 이상이면 빨간 알림 (전송기와 같은 기준). 장애를 켰지만 아직 기준 아래면 노란 '주의'
- **여과시간에 분석 요청**: 최근 30·60초 원문을 게이트웨이로 보낸다 (전송기와 같은 방식, 요청자 `ops-kim`). 목록은 기존 장애 원인 분석 / 은행 목록 중 고른다.
  게이트웨이 주소는 `YG_GATEWAY_URL` (기본 `http://localhost:8080`, VM 에서는 `http://192.168.0.138:8080`), 화면 링크는 `YG_GATEWAY_SCREEN` (VM 에서는 `http://10.0.1.37:8080`)
- 모니터로 보낼 때는 전송기(`shipper.py`)를 같이 켜지 않는다 (같은 장애가 두 번 들어간다)

로컬 확인 (11/9): 장애 4종 버튼 · 모두 복구 동작, 장애 ① 보내기 → 칸 40→10 · 높음 경고 0, 장애 ④ 보내기 → 칸 43→10 · 높음 경고 32.

## 장애 4가지

| 장애 | 일으키기 / 되돌리기 | 실제 원인 | 조회 | 당행이체 | 타행이체 |
|---|---|---|---|---|---|
| ① 대외기관 응답 없음 | `./bank.sh kftc-freeze`<br>되돌리기 `./bank.sh kftc-resume` | `kill -STOP` 으로 대외기관 프로세스가 멈춤 | 정상 | 정상 | 3초 `TimeoutError` → 504 |
| ② 원장 잠금 | `./bank.sh batch 60`<br>(60초 뒤 저절로 풀림) | 이자 배치가 `BEGIN IMMEDIATE` 를 잡고 놓지 않음 | 정상 (WAL) | `database is locked` → 500 | 같음 |
| ③ 잘못된 배포 | `./bank.sh deploy-bad`<br>되돌리기 `./bank.sh deploy-fix` | 계정계가 9101 포트로 재배포됨 (시작 로그에 `port=9101`) | `ConnectionRefusedError` → 503 | 같음 | 같음 |
| ④ 디버그 로그 실수 | `./bank.sh debug-on` 뒤 ①<br>되돌리기 `./bank.sh debug-off` | 오류 메시지에 요청·고객 정보를 통째로 찍음 | ①과 같음 | | 메시지에 실명·주민번호·계좌·전화 |

장애마다 '되는 것과 안 되는 것'이 다르게 설계했다. AI 가 로그 패턴으로 원인을 구별해야 한다.

## 로컬 실측 (2026-11-08, 게이트웨이 AI mock, `INCIDENT_ANALYSIS` 목록)

| 보낸 로그 | 레코드 | 칸 | 높음 경고 | 결과 |
|---|---|---|---|---|
| 장애 ① 15초 | 151건 | 40 → 10 | 0 | 조건부 모드: 승인 없이 전송 가능 |
| 장애 ④ 15초 | 193건 | 40 → 10 | 60 (계좌 30 · 주민번호 15 · 전화 15) | 승인 필요 |

- OTel 용 `INCIDENT_ANALYSIS` 목록은 민감한 칸을 모두 버리지만, 은행 원인 판별에 필요한 `txn.type`(조회/당행/타행) · `peer.service` · `interbank.wait_ms` · `duration_ms` 도 같이 버린다 → 은행용 목록이 필요하다.
- 메시지 안의 **실명**은 경고 규칙에 없어서 잡히지 않는다 (④에서는 주민번호·계좌 경고 때문에 어차피 승인으로 간다).

## 은행용 목록 만들기 (필드 선택 알고리즘)

```bash
./bank.sh stop                         # 같은 포트를 쓴다
python3 make_scenarios.py              # BANK_S0_normal + F1~F4, 약 4분
# 1단계 (AI 없음). 이 브랜치의 field_select.py 는 옛 버전이라 feat/spring-experiment 의 새 버전을 꺼내 쓴다 (브랜치를 합치면 필요 없음)
git show feat/spring-experiment:experiments/yeogwasigan-spring/scripts/field_select.py > /tmp/field_select.py
python3 /tmp/field_select.py --dir scenarios --normal BANK_S0_normal \
  --select BANK_F1_kftcTimeout,BANK_F2_ledgerLock --validate BANK_F3_badDeploy,BANK_F4_kftcTimeoutDebug
```

고르는 장애(F1·F2)와 검증 장애(F3·F4)를 나눈다. OTel 실험에서 E2a 를 빼 두고 검증한 것과 같은 방식이다.

1단계 실측 (11/08): 칸 45개 → 후보 16개

| 판정 | 칸 |
|---|---|
| 담기 13 | body · error.type · exception.type/message/stacktrace · http.response.status_code · interbank.wait_ms · interbank.relay_id · db.operation · service.version + 문맥(service.name · 시각 · 심각도) |
| 토큰화 3 | traceId · account.number · counterparty.account |
| 버림 29 | 실명 · 주민번호 · 전화 · IP · session.id · 메모 · 상대 이름 · 금액 · 잔액 · txn.type · peer.service … |

2·3단계(AI 확인 · 되돌려 빼기) 실측 (11/08, gpt-5.1, 기록 `fieldsearch_results/`): 후보 16 → **7칸** → [`bank_incident_analysis.yaml`](bank_incident_analysis.yaml) (검토 전 초안)

| 고를 때 안 쓴 장애 F3·F4 로 검증 (4점 만점) | 칸 | 점수 | 누출 |
|---|---|---|---|
| 문맥·토큰만 | 6 | 0 | 0 |
| **알고리즘 목록** | 7 | 2 (F3 2 · F4 0) | 0 |
| 전부 보냄 | 42 | 4 | 1482 |

- 남은 칸: 서비스 · 시각 · 심각도 · traceId(토큰) · 계좌 2칸(토큰) · `exception.message`.
- F4 는 `exception.message` 가 'timed out' 뿐이라 대외기관을 못 짚었다. 되돌려 빼기에서 `body` 가 `exception.message` 와 점수가 같아 빠졌기 때문 (AI 채점 1회씩이라 흔들림 있음).
  `body` 를 넣으면 원인은 더 잘 보이겠지만 디버그 로그 사고(F4) 때 실명·주민번호가 같이 나간다 → 넣을지는 검토 때 정한다.
- `fieldsearch_20261108_181049` 의 검증 수치는 F4 정답지 오류(원인을 안 적음)로 무효. `…181713` 이 정답지를 고치고 되돌려 빼기부터 다시 잰 것.
- 주의: 실행 기록 폴더를 `yeogwasigan-spring/results/` 에 두면 목록 제안 화면(`/proposals.html`)이 가장 최근 live 실행인 이 기록을 읽어 OTel 데모가 은행으로 바뀐다. 그래서 여기에 둔다.

다시 돌리기 (`feat/spring-experiment` 체크아웃의 `experiments/yeogwasigan-spring` 에서, `.env` 키 사용):

```bash
DIR=<이 폴더>/scenarios NORMAL=BANK_S0_normal SELECT=BANK_F1_kftcTimeout,BANK_F2_ledgerLock \
  VALIDATE=BANK_F3_badDeploy,BANK_F4_kftcTimeoutDebug HAND= CAND=/tmp/bank_candidates.json scripts/fieldsearch.sh
# 끝나면 results/fieldsearch_<시각> 을 이 폴더 fieldsearch_results/ 로 옮긴다
```

## 학교 VM 에 올릴 때

회사 VM 에서 `payment_service.py` 대신 이걸 띄우고, 전송기만 로그 경로를 바꾼다.

```bash
cd yeogwasigan/experiments/network-demo/bank && ./bank.sh start 3
cd ../company && YG_DEMO_PASSWORD='…' python3 shipper.py --gateway http://192.168.0.138:8080 --log ../bank/logs/bank.jsonl
```
