"""RCAEval RE3-TT(Train Ticket) 장애 사례를 장애 폴더 형식으로 바꾼다 (필드 선택 알고리즘을 새 시스템에 돌리기 위해).

입력 : data/rcaeval/<사례>/logs.parquet (timestamp, container_name, message) + inject_time.txt
출력 : <out>/<사례>/raw.jsonl · injected_pii.yaml · truth.yaml,  <out>/normal/raw.jsonl (장애 전 구간)

1) 로그 한 줄을 칸으로 쪼갠다 (RCAEval 로그는 문장 한 줄뿐이라 칸이 3개다)
   - 자바(Spring) 로그: 시각 심각도 pid --- [스레드] 클래스 : 메시지  → severityText, thread.name, code.namespace, body
     메시지 안의 예외 → exception.type / exception.message / exception.stacktrace, "키=값"·"키: 값" → attributes.<키>
   - nginx 접속 로그(ts-ui-dashboard) → http.request.method, url.path, http.response.status_code, client.address 등
2) 장애 주입 시각 기준 [-60초, +120초] 만 자르고, 같은 모양의 줄은 앞 몇 개 + 마지막 1개만 남긴다 (OTel 전처리 ② 와 같은 생각)
3) 민감값: 원본에 이미 있는 값(JWT 토큰, 기본 보안 비밀번호) = 자연 발생. 그 밖에 가짜 이메일·전화·카드번호를
   줄 몇 개에 본문/속성 번갈아 넣는다. 모두 injected_pii.yaml 에 적는다
4) 정답: RCAEval 은 원인 서비스와 결함 종류(f1~f4)만 준다 → truth.yaml 에 그대로 적는다

  pip install pandas pyarrow
  python3 scripts/rcaeval_to_scenarios.py data/rcaeval data/rcaeval/incidents
"""
import json
import random
import re
import sys
from collections import defaultdict
from pathlib import Path

import pandas as pd

SPRING = re.compile(r"^\s*\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+(\w+)\s+(\d+) --- \[\s*(.*?)\]\s+(\S+)\s*:\s?(.*)$", re.S)
NGINX = re.compile(r'^(\S+) - (\S+) \[[^\]]+\] "(\S+) (\S+) [^"]*" (\d{3}) (\d+) "([^"]*)" "([^"]*)"')
EXC = re.compile(r"([\w.$]+(?:Exception|Error))(?::\s*([^\n]*))?")
KV = re.compile(r"\b([A-Za-z][\w.]{1,30})\s*[=:：]\s*([^\s,;\]\)\}'\"]{1,80})")
SHAPE = re.compile(r"[0-9a-fA-F-]{8,}|\d+|eyJ[\w.-]+")
NATURAL = [("jwt", re.compile(r"eyJ[\w-]+\.[\w-]+\.[\w-]+")),
           ("password", re.compile(r"security password: ([\w-]{6,})"))]
# 장애 주입이 서비스를 재배포해서 생긴 기동·종료 INFO 로그 (OTel 의 '실험 장비 제거'와 같은 처리, 10/30 결정)
# Spring 은 로거 이름을 40자에 맞춰 줄인다: 'o.s.b...'(한 글자 묶음) · 'org.' · 앞이 잘린 이름(39자 이상)은 프레임워크 로거
FRAMEWORK = re.compile(r"^([a-z]\.|org\.|\.)")
LIFECYCLE = re.compile(r"^(Started|Starting|No active profile|Initializing|Stopping|Closing)\b")
WINDOW = (-60, 120)
KEEP_INFO, KEEP_WARN = 3, 5   # ponytail: 모양별 앞 N + 마지막 1. 장애 단서가 중간에만 있으면 놓친다, 그때 '가장 느린 N' 같은 규칙 추가


def parse(svc, ts, msg):
    attrs, sev, body = {}, "INFO", msg.strip()
    m = SPRING.match(msg)
    if m:
        sev, pid, thread, logger, body = m.groups()
        attrs.update({"thread.name": thread, "code.namespace": logger, "process.pid": pid})
        e = EXC.search(body)
        if e:
            attrs["exception.type"] = e.group(1)
            if e.group(2):
                attrs["exception.message"] = e.group(2).strip()
            trace = "\n".join(l for l in body.splitlines() if l.strip().startswith("at "))
            if trace:
                attrs["exception.stacktrace"] = trace
        for k, v in KV.findall(body.split("\n")[0]):
            if not v.startswith("//") and k not in attrs:
                attrs[k] = v
    else:
        m = NGINX.match(msg)
        if m:
            ip, user, method, path, status, size, ref, ua = m.groups()
            attrs.update({"client.address": ip, "http.request.method": method, "url.path": path,
                          "http.response.status_code": int(status), "http.response.body.size": int(size),
                          "http.request.header.referer": ref, "user_agent.original": ua})
            if user != "-":
                attrs["user.name"] = user
            sev = "ERROR" if status >= "500" else "WARN" if status >= "400" else "INFO"
    rec = {"timeUnixNano": str(int(ts) * 1_000_000_000), "severityText": sev, "body": body}
    if attrs:
        rec["attributes"] = attrs
    return {"resource": {"service.name": svc}, "logRecord": rec}


def cut(df, start, end):
    """[start, end) 구간을 칸으로 쪼개고 같은 모양의 줄을 줄인다."""
    part = df[(df.timestamp >= start) & (df.timestamp < end)].sort_values("timestamp", kind="stable")
    groups = defaultdict(list)
    for r in part.itertuples():
        rec = parse(r.container_name, r.timestamp, r.message)
        lr = rec["logRecord"]
        ns = lr.get("attributes", {}).get("code.namespace", "")
        if lr["severityText"] == "INFO" and ns and (FRAMEWORK.match(ns) or len(ns) >= 39 or LIFECYCLE.match(lr["body"])):
            continue
        key =(r.container_name, lr["severityText"], SHAPE.sub("#", lr["body"].split("\n")[0])[:120],
               lr.get("attributes", {}).get("http.response.status_code"))
        groups[key].append(rec)
    out = []
    for (svc, sev, _, _), recs in groups.items():
        n = KEEP_WARN if sev in ("WARN", "ERROR", "FATAL") else KEEP_INFO
        out += recs if len(recs) <= n + 1 else recs[:n] + recs[-1:]
    return sorted(out, key=lambda r: r["logRecord"]["timeUnixNano"])


def fake_pii(rng, i):
    return [("user.email", f"tt.user{rng.randrange(1000, 9999)}@example.com"),
            ("user.phone", f"010-{rng.randrange(1000, 9999)}-{rng.randrange(1000, 9999)}"),
            ("payment.card", "4111-1111-1111-" + f"{rng.randrange(1000, 9999)}")][i % 3]


def add_pii(recs, seed, n=12):
    """자연 발생 민감값을 모으고, 가짜 민감값 n 개를 본문/속성 번갈아 넣는다."""
    items, seen = [], set()

    def item(kind, value, desc):
        if value not in seen and len(value) >= 6:
            seen.add(value)
            items.append({"id": f"PII-{len(items) + 1:03d}", "type": kind, "value": value, "description": desc})

    for r in recs:
        text = json.dumps(r, ensure_ascii=False)
        for kind, p in NATURAL:
            for m in p.finditer(text):
                item(kind, m.group(m.lastindex or 0), "자연 발생: 원본 로그에 이미 있던 값")
    rng = random.Random(seed)
    for i, r in enumerate(rng.sample(recs, min(n, len(recs)))):
        kind, value = fake_pii(rng, i)
        lr = r["logRecord"]
        if i % 2 == 0:
            lr["body"] += f" ({kind}={value})"
        else:
            lr.setdefault("attributes", {})[kind] = value
        item(kind, value, "주입: 가짜 값 (" + ("본문" if i % 2 == 0 else "속성") + ")")
    return items


def write(dir_, recs, items=None, truth=None):
    dir_.mkdir(parents=True, exist_ok=True)
    (dir_ / "raw.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in recs))
    if items is not None:
        y = ["# RCAEval → 장애 폴더 변환이 만든 민감값 목록 (자동 생성)", "items:"]
        for it in items:
            y += [f"- id: {it['id']}", f"  type: {it['type']}", f"  value: '{it['value']}'", f"  description: '{it['description']}'"]
        (dir_ / "injected_pii.yaml").write_text("\n".join(y) + "\n")
    if truth:
        (dir_ / "truth.yaml").write_text(truth)


def main():
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    cases = sorted(p for p in src.glob("re3tt_*") if (p / "logs.parquet").is_file())
    for i, case in enumerate(cases):
        df = pd.read_parquet(case / "logs.parquet")
        t = int((case / "inject_time.txt").read_text().strip())
        svc, fault = re.match(r"re3tt_(.+)_(f\d)_\d+$", case.name).groups()
        recs = cut(df, t + WINDOW[0], t + WINDOW[1])
        items = add_pii(recs, case.name)
        short = svc.removeprefix("ts-").removesuffix("-service")
        truth = ("# 정답지 — AI 에는 보내지 않는다. RCAEval 라벨은 원인 서비스와 결함 종류뿐 (세부 원인 설명 없음)\n"
                 f"title: {svc} 코드 결함 ({fault})\n"
                 f"root_cause:\n  summary: '{svc} 서비스의 코드 결함 (RCAEval RE3 {fault}, 세부 원인 라벨 없음)'\n"
                 "# 1단계 단서 키워드: 원인 서비스 이름 + 오류 낱말. 한 줄 · 큰따옴표 목록\n"
                 "cause_keywords: " + json.dumps([svc, short, "exception", "error", "failed"]) + "\n")
        write(out / case.name, recs, items, truth)
        chars = sum(len(json.dumps(r, ensure_ascii=False)) for r in recs)
        print(f"{case.name}: {len(df)}줄 → {len(recs)}줄, {chars:,}자, 민감값 {len(items)}개")
        if i == 0:   # 정상 = 첫 사례의 장애 10분 전 같은 길이 구간
            normal = cut(df, t - 600 + WINDOW[0], t - 600 + WINDOW[1])
            write(out / "normal", normal)
            print(f"normal: {len(normal)}줄")


if __name__ == "__main__":
    main()
