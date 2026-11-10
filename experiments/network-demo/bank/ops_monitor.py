#!/usr/bin/env python3
"""여과은행 운영 모니터 (회사 VM 안에서만 쓰는 개발자·운영자 화면).

실무에서 Kibana · Grafana 같은 모니터링 화면이 하는 일을 작게 흉내 낸다.
  - 서비스 상태와 최근 5분 에러 그래프, 실시간 로그 (원문 그대로 — 회사 안이므로)
  - traceId 를 누르면 채널계 → 계정계 → 대외기관 기록을 이어서 보여 준다
  - 장애 만들기 (시연용): bank.sh 의 장애 명령을 버튼으로 실행한다. 장애는 똑같이 진짜로 난다
  - [여과시간에 분석 요청]: 최근 N초 원문을 게이트웨이로 보낸다 (shipper.py 와 같은 방식, 요청자 ops-kim)

  python3 ops_monitor.py           # 포트 BANK_MONITOR_PORT (기본 9300). 화면 http://localhost:9300/
여과시간으로 보내려면 켤 때 YG_DEMO_PASSWORD 를 넣는다. 게이트웨이 주소는 YG_GATEWAY_URL (기본 http://localhost:8080),
화면 링크용 주소는 YG_GATEWAY_SCREEN (학교 VM 에서는 http://10.0.1.37:8080).
"""
import json
import os
import subprocess
import sys
import threading
import time
from collections import Counter, deque
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from common import HOST, LOG_PATH, JsonHandler

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE.parent / "company"))   # shipper.py 의 게이트웨이 로그인·전송 코드를 그대로 쓴다

PORT = int(os.environ.get("BANK_MONITOR_PORT", 9300))
GATEWAY = os.environ.get("YG_GATEWAY_URL", "http://localhost:8080")
GATEWAY_SCREEN = os.environ.get("YG_GATEWAY_SCREEN", GATEWAY)
INCIDENT_WINDOW, INCIDENT_ERRORS = 30, 8          # shipper.py 기본값과 같은 장애 판정
MAX_SEND = 300                                    # shipper.py --max-records 기본값

RECORDS = deque(maxlen=20000)
LOCK = threading.Lock()

# 버튼 → bank.sh 인자
FAULTS = {
    "kftc-freeze": ["kftc-freeze"], "kftc-resume": ["kftc-resume"],
    "batch": ["batch", "30"],
    "deploy-bad": ["deploy-bad"], "deploy-fix": ["deploy-fix"],
    "debug-on": ["debug-on"], "debug-off": ["debug-off"],
    "recover": ["recover"],
}


def tail_loop():
    """로그 파일을 끝까지 따라 읽는다. reset 으로 파일이 새로 생기면 처음부터 다시 읽는다."""
    path = (HERE / LOG_PATH) if not os.path.isabs(LOG_PATH) else Path(LOG_PATH)
    offset = 0
    while True:
        try:
            size = path.stat().st_size
        except FileNotFoundError:
            time.sleep(0.5)
            continue
        if size < offset:
            offset = 0
            with LOCK:
                RECORDS.clear()
        if size > offset:
            with path.open("rb") as f:
                f.seek(offset)
                chunk = f.read(size - offset)
            end = chunk.rfind(b"\n") + 1        # 덜 쓰인 마지막 줄은 다음에 읽는다
            offset += end
            items = []
            for line in chunk[:end].decode("utf-8", "replace").splitlines():
                try:
                    r = json.loads(line)
                except ValueError:
                    continue
                lr = r.get("logRecord", {})
                items.append({"t": int(lr.get("timeUnixNano", 0)) / 1e9, "sev": lr.get("severityText", ""),
                              "svc": r.get("resource", {}).get("service.name", ""), "body": lr.get("body", ""),
                              "trace": lr.get("traceId"), "span": lr.get("spanId"), "parent": lr.get("parentSpanId"),
                              "attrs": lr.get("attributes", {}), "raw": line})
            with LOCK:
                RECORDS.extend(items)
        time.sleep(0.5)


def setting(name, default):
    p = HERE / "run" / name
    return p.read_text().strip() if p.exists() else default


def proc_state():
    out = {}
    for n in ("channel", "core", "kftc", "batch"):
        try:
            pid = int((HERE / "run" / f"{n}.pid").read_text())
            stat = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
        except (OSError, ValueError):
            stat = ""
        out[n] = "멈춤" if stat.startswith("T") else ("실행 중" if stat else "꺼짐")
    return out


def summary():
    now = time.time()
    with LOCK:
        recent = [r for r in RECORDS if r["t"] >= now - 300]
    err, warn, total = [0] * 60, [0] * 60, [0] * 60   # 5초 × 60 = 최근 5분
    for r in recent:
        b = 59 - min(59, int((now - r["t"]) // 5))
        total[b] += 1
        if r["sev"] == "ERROR":
            err[b] += 1
        elif r["sev"] == "WARN":
            warn[b] += 1
    last30 = sum(1 for r in recent if r["sev"] == "ERROR" and r["t"] >= now - INCIDENT_WINDOW)
    svc_err = Counter(r["svc"] for r in recent if r["sev"] == "ERROR" and r["t"] >= now - 60)
    procs = proc_state()
    active = []
    if procs["kftc"] == "멈춤":
        active.append("대외기관 멈춤")
    if procs["batch"] == "실행 중":
        active.append("이자 배치가 원장 잠금")
    if setting("core_port", "9100") != "9100":
        active.append(f"계정계가 {setting('core_port', '9100')} 포트로 배포됨")
    if setting("debug", "0") == "1":
        active.append("디버그 로그 켜짐")
    return {"now": now, "host": HOST, "procs": procs, "svcErrors1m": dict(svc_err), "errors30s": last30,
            "incident": last30 >= INCIDENT_ERRORS, "rule": f"{INCIDENT_WINDOW}초 안 ERROR {INCIDENT_ERRORS}건 이상",
            "buckets": {"error": err, "warn": warn, "total": total}, "activeFaults": active,
            "gatewayScreen": GATEWAY_SCREEN, "canSend": bool(os.environ.get("YG_DEMO_PASSWORD"))}


def public(r):
    return {k: r[k] for k in ("t", "sev", "svc", "body", "trace", "span", "parent", "attrs")}


def logs(q):
    level = q.get("level", ["problem"])[0]
    svc = q.get("svc", [""])[0]
    text = q.get("q", [""])[0]
    limit = min(int(q.get("limit", ["150"])[0]), 500)
    with LOCK:
        recs = list(RECORDS)
    out = []
    for r in reversed(recs):                     # 최신이 위
        if level == "problem" and r["sev"] not in ("ERROR", "WARN"):
            continue
        if svc and r["svc"] != svc:
            continue
        if text and text not in r["raw"]:
            continue
        out.append(public(r))
        if len(out) >= limit:
            break
    return out


def trace(tid):
    with LOCK:
        recs = [public(r) for r in RECORDS if r["trace"] == tid]
    return sorted(recs, key=lambda r: r["t"])


def run_fault(action):
    args = FAULTS.get(action)
    if not args:
        return 400, {"error": "모르는 장애"}
    r = subprocess.run([str(HERE / "bank.sh"), *args], cwd=HERE, capture_output=True, text=True, timeout=30)
    lines = [l for l in (r.stdout + r.stderr).splitlines() if l.startswith("+")]
    return (200 if r.returncode == 0 else 500), {"commands": lines or ["(실행할 것이 없음)"]}


def send(body):
    password = os.environ.get("YG_DEMO_PASSWORD")
    if not password:
        return 400, {"error": "YG_DEMO_PASSWORD 를 넣고 모니터를 다시 켜세요 (YG_DEMO_PASSWORD=… ./bank.sh start)"}
    window = int(body.get("window") or 30)
    purpose = body.get("purpose") or "INCIDENT_ANALYSIS"
    now = time.time()
    with LOCK:
        recs = [r for r in RECORDS if r["t"] >= now - window][-MAX_SEND:]
    if not recs:
        return 400, {"error": f"최근 {window}초 로그가 없습니다"}
    from shipper import Gateway
    try:
        gw = Gateway(GATEWAY, "ops-kim", password)
        gw.login()
        res = gw.create("\n".join(r["raw"] for r in recs), purpose)
    except SystemExit as e:                     # shipper 는 실패를 SystemExit 로 알린다
        return 502, {"error": str(e)}
    except Exception as e:  # 게이트웨이가 꺼져 있을 때 등
        return 502, {"error": f"게이트웨이 연결 실패 ({GATEWAY}): {type(e).__name__}"}
    return 200, {"id": res.get("id"), "records": len(recs), "fieldsBefore": res.get("fieldsBefore"),
                 "fieldsAfter": res.get("fieldsAfter"), "highWarnings": res.get("highWarnings"),
                 "status": res.get("status"), "purpose": purpose, "gatewayScreen": GATEWAY_SCREEN + "/gateway.html"}


class H(JsonHandler):
    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if u.path in ("/", "/index.html"):
            body = (HERE / "static" / "monitor.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return self.wfile.write(body)
        if u.path == "/api/summary":
            return self.send_json(200, summary())
        if u.path == "/api/logs":
            return self.send_json(200, logs(q))
        if u.path == "/api/trace":
            return self.send_json(200, trace(q.get("id", [""])[0]))
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        u = urlparse(self.path)
        body = self.read_json()
        if u.path == "/api/fault":
            return self.send_json(*run_fault(body.get("action")))
        if u.path == "/api/send":
            return self.send_json(*send(body))
        self.send_json(404, {"error": "not found"})


def main():
    threading.Thread(target=tail_loop, daemon=True).start()
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), H)
    srv.daemon_threads = True
    print(f"ops monitor on :{PORT} — 화면 http://localhost:{PORT}/ (게이트웨이 {GATEWAY})", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
