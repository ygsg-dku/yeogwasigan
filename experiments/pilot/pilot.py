"""pilot 실험 러너. 절차는 PROTOCOL.md.

python pilot.py flag <이름> <variant>    플래그 바꾸고 15초 대기(flagd 재시작 안 함)
python pilot.py reset                   플래그 전부 원복
python pilot.py capture <시나리오> <초>  지금부터 n초치 텔레메트리 잘라서 저장
python pilot.py fields <시나리오>        그 시나리오의 필드 이름 목록(fields.md). 값은 안 적음
"""
import json, subprocess, sys, time
from pathlib import Path

HERE = Path(__file__).parent
DEMO = HERE / "otel-demo"
FLAGS = DEMO / "src/flagd/demo.flagd.json"
TELE = HERE / "telemetry"
OUT = HERE / "scenarios"


def sh(*cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def settle():
    # flagd는 재시작하지 않는다. 재시작하면 Go 서비스(checkout, product-catalog)가 옛 값을 계속 쓴다.
    # flagd가 파일 변경을 스스로 감지해 서비스에 알린다(9/23 확인: checkout이 1분 안에 새 값 사용)
    time.sleep(15)


def flag(name, variant):
    d = json.loads(FLAGS.read_text())
    if variant not in d["flags"][name]["variants"]:
        sys.exit(f"{name}: 없는 variant {variant}. 가능: {list(d['flags'][name]['variants'])}")
    d["flags"][name]["defaultVariant"] = variant
    # productCatalogFailure는 targeting이 defaultVariant를 덮는다("특정 상품이면 A, 아니면 off"). A를 바꿔야 켜진다
    if "if" in d["flags"][name].get("targeting", {}):
        d["flags"][name]["targeting"]["if"][1] = variant
    FLAGS.write_text(json.dumps(d, indent=2))
    settle()
    print(f"{name}={variant}")


def reset():
    sh("git", "-C", str(DEMO), "checkout", "--", "src/flagd/demo.flagd.json")
    settle()
    print("플래그 원복")


def lines(p):
    # 쓰는 중인 마지막 줄은 빼고 셈
    return sum(1 for ln in p.open("rb") if ln.endswith(b"\n")) if p.exists() else 0


def capture(scenario, seconds):
    # 파일을 자르지 않고 줄 번호로 구간을 뗀다. collector가 파일을 열고 있어서 truncate하면 구멍이 생긴다
    raw, red = TELE / "raw.jsonl", TELE / "redacted.jsonl"
    r0, d0 = lines(raw), lines(red)
    t0 = time.time()
    time.sleep(seconds)
    time.sleep(3)  # flush_interval 1s 여유
    r1, d1 = lines(raw), lines(red)
    dst = OUT / scenario
    dst.mkdir(parents=True, exist_ok=True)
    for src, a, b, name in ((raw, r0, r1, "raw.jsonl"), (red, d0, d1, "redacted.jsonl")):
        with src.open("rb") as f, (dst / name).open("wb") as g:
            for i, ln in enumerate(f):
                if i >= b:
                    break
                if i >= a:
                    g.write(ln)
    (dst / "window.json").write_text(json.dumps(
        {"start_unix": t0, "seconds": seconds, "raw_lines": [r0, r1], "redacted_lines": [d0, d1]}, indent=2))
    print(f"{scenario}: raw {r1 - r0}줄, redacted {d1 - d0}줄")


ORDER = ["리소스", "span 기본", "span 속성", "span 이벤트 이름", "span 이벤트 속성", "log 기본", "log 속성"]


def fields(scenario):
    # B에게 줄 필드 이름 목록. 값은 적지 않는다.
    # 주입 흔적(flagd 서비스, feature_flag.* 키)은 모든 조건의 입력에서 빠지므로(PROTOCOL v1.1) 여기서도 뺀다
    from collections import Counter, defaultdict
    seen = defaultdict(Counter)  # (위치, 필드) -> 서비스별 레코드 수
    per_svc = defaultdict(Counter)

    def add(where, keys, svc):
        for k in keys:
            if not k.startswith(("feature_flag.", "demo.feature_flag.")):
                seen[where, k][svc] += 1

    for ln in (OUT / scenario / "raw.jsonl").open():
        d = json.loads(ln)
        for r in d.get("resourceSpans", []) + d.get("resourceLogs", []):
            attrs = r.get("resource", {}).get("attributes", [])
            res = [a["key"] for a in attrs]
            svc = next((a["value"].get("stringValue") for a in attrs if a["key"] == "service.name"), "?")
            if svc in ("flagd", "flagd-ui"):
                continue
            for s in (s for sc in r.get("scopeSpans", []) for s in sc.get("spans", [])):
                per_svc[svc]["span"] += 1
                add("리소스", res, svc)
                add("span 기본", [k for k in ("traceId", "spanId", "parentSpanId", "name", "kind",
                                              "startTimeUnixNano", "endTimeUnixNano") if s.get(k)]
                    + ["status." + k for k in s.get("status", {})], svc)
                add("span 속성", [a["key"] for a in s.get("attributes", [])], svc)
                for e in s.get("events", []):
                    add("span 이벤트 이름", [e.get("name", "?")], svc)
                    add("span 이벤트 속성", [a["key"] for a in e.get("attributes", [])], svc)
            for rec in (x for sc in r.get("scopeLogs", []) for x in sc.get("logRecords", [])):
                per_svc[svc]["log"] += 1
                add("리소스", res, svc)
                add("log 기본", [k for k in ("timeUnixNano", "severityText", "severityNumber", "body",
                                             "traceId", "spanId") if rec.get(k)], svc)
                add("log 속성", [a["key"] for a in rec.get("attributes", [])], svc)

    out = [f"# 필드 이름 목록 — {scenario}", "",
           "정상 트래픽에서 나온 필드 이름만 적었다. 값은 없다. 횟수는 그 필드가 붙은 레코드(span 또는 log) 수다.",
           "장애 스위치 흔적(flagd·flagd-ui 서비스, `feature_flag.*` 키)은 모든 조건에서 빠지므로 여기서도 뺐다.", "",
           "## 서비스별 레코드 수", "", "| 서비스 | span | log |", "| --- | ---: | ---: |"]
    out += [f"| {s} | {c['span']} | {c['log']} |" for s, c in sorted(per_svc.items())]
    out += ["", "## 필드", "", "| 위치 | 필드 | 횟수 | 나온 서비스 |", "| --- | --- | ---: | --- |"]
    for (where, k), c in sorted(seen.items(), key=lambda kv: (ORDER.index(kv[0][0]), kv[0][1])):
        out.append(f"| {where} | `{k}` | {sum(c.values())} | {', '.join(sorted(c))} |")
    (OUT / scenario / "fields.md").write_text("\n".join(out) + "\n")
    print(f"{scenario}: 필드 {len(seen)}개")


if __name__ == "__main__":
    cmd, *args = sys.argv[1:]
    {"flag": lambda: flag(*args),
     "reset": reset,
     "capture": lambda: capture(args[0], int(args[1])),
     "fields": lambda: fields(args[0])}[cmd]()
