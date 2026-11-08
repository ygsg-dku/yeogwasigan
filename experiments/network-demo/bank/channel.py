#!/usr/bin/env python3
"""채널계 (인터넷뱅킹). 화면과 API 를 내고, 가상 손님 트래픽을 만든다. 실제 처리는 계정계(core)에 맡긴다.

  python3 channel.py --rps 3      # 포트는 BANK_CHANNEL_PORT (기본 9000)
화면: http://localhost:9000/  (VM 에서는 노트북에서 ssh -L 9000:localhost:9000 … 으로 연다)

가상 손님은 본인확인 10% · 조회 50% · 당행이체 25% · 타행이체 15% 비율로 움직인다.
BANK_DEBUG=1 이면 계정계 호출이 실패할 때 고객 정보를 통째로 찍는다 (개발자 실수 재현, 장애 ④).
"""
import argparse
import os
import random
import secrets
import threading
import time
import traceback
from collections import deque
from http.server import ThreadingHTTPServer
from pathlib import Path

from common import (BANK_CODE, COUNTERPARTIES, CUSTOMERS, DEBUG, JsonHandler, Log, error_name, new_span,
                    post_json)

PORT = int(os.environ.get("BANK_CHANNEL_PORT", 9000))
CORE_URL = os.environ.get("BANK_CORE_URL", "http://127.0.0.1:9100")
CORE_TIMEOUT_S = 5
MEMOS = ["3월 월세", "모임 회비", "생일 선물", "중고거래 대금", "관리비", "용돈", "식비 정산", "경조사비", None, None]
AGENTS = ["YeogwaBank-iOS/4.2.1", "YeogwaBank-Android/4.2.0", "Mozilla/5.0 (Windows NT 10.0) Chrome/130"]

log = Log("channel-ibk", "4.2.1")
RESULTS = deque(maxlen=5000)   # (시각, 거래 종류, 성공 여부) — 화면의 '최근 1분' 표시용
SESSIONS = {}


def session_of(c):
    return SESSIONS.setdefault(c["account"], secrets.token_hex(12))


def request_attrs(c, route, kind):
    return {"http.route": route, "http.request.method": "POST", "client.ip": c["ip"], "session.id": session_of(c),
            "customer.name": c["name"], "account.number": c["account"], "txn.type": kind,
            "user_agent.original": random.choice(AGENTS)}


def call_core(path, payload, c, route, kind, span, extra):
    """계정계를 부르고 결과를 로그로 남긴다. (상태, 본문)"""
    t0 = time.monotonic()
    attrs = request_attrs(c, route, kind) | extra
    try:
        code, res = post_json(CORE_URL + path, payload, span, CORE_TIMEOUT_S)
    except Exception as e:  # 연결 거부·시간 초과
        reason = getattr(e, "reason", e)
        dump = f" | DEBUG customer={{'name': '{c['name']}', 'rrn': '{c['rrn']}', 'phone': '{c['phone']}'}}" if DEBUG else ""
        log("ERROR", f"{route} 실패 503: 계정계 호출 실패 {error_name(e)}: {reason}{dump}", span, attrs | {
            "http.response.status_code": 503, "peer.service": "core-ledger", "server.address": CORE_URL.split("//")[1],
            "exception.type": error_name(e), "exception.message": str(reason),
            "exception.stacktrace": traceback.format_exc(), "duration_ms": int((time.monotonic() - t0) * 1000)})
        RESULTS.append((time.time(), kind, False))
        return 503, {"error": "일시적인 오류로 처리하지 못했습니다 (계정계 연결 실패)"}
    attrs |= {"http.response.status_code": code, "peer.service": "core-ledger",
              "duration_ms": int((time.monotonic() - t0) * 1000)}
    if code >= 500:
        log("ERROR", f"{route} 실패 {code}: 계정계 오류 {res.get('error')}", span, attrs | {"error.type": res.get("error")})
    elif code >= 400:
        log("WARN", f"{route} 거절 {code}: {res.get('error')}", span, attrs | {"error.type": res.get("error")})
    else:
        log("INFO", f"{route} 성공 200", span, attrs)
    RESULTS.append((time.time(), kind, code < 500))
    return code, res


def do_login(c):
    span = new_span()
    SESSIONS[c["account"]] = secrets.token_hex(12)
    log("INFO", "본인확인 완료 (간편인증)", span, request_attrs(c, "/api/login", "본인확인") | {
        "customer.rrn": c["rrn"], "customer.phone": c["phone"], "auth.method": "simple-auth",
        "http.response.status_code": 200})
    return 200, {"name": c["name"], "account": c["account"]}


def do_inquiry(c):
    return call_core("/inquiry", {"account": c["account"]}, c, "/api/inquiry", "조회", new_span(), {})


def do_transfer(c, to_bank, to_account, to_name, amount, memo):
    kind = "당행" if to_bank == BANK_CODE else "타행"
    payload = {"from_account": c["account"], "to_bank": to_bank, "to_account": to_account, "to_name": to_name,
               "amount": amount, "memo": memo}
    extra = {"transfer.amount": amount, "transfer.memo": memo, "counterparty.name": to_name,
             "counterparty.account": to_account, "interbank.bank_code": to_bank if kind == "타행" else None}
    return call_core("/transfer", payload, c, "/api/transfer", kind, new_span(), extra)


def random_action():
    c = random.choice(CUSTOMERS)
    r = random.random()
    amount = random.choice([10_000, 30_000, 50_000, 120_000, 300_000, 1_000_000])
    if r < 0.10:
        do_login(c)
    elif r < 0.60:
        do_inquiry(c)
    elif r < 0.85:
        to = random.choice([x for x in CUSTOMERS if x is not c])
        do_transfer(c, BANK_CODE, to["account"], to["name"], amount, random.choice(MEMOS))
    else:
        to = random.choice(COUNTERPARTIES)
        do_transfer(c, to["bank"], to["account"], to["name"], amount, random.choice(MEMOS))


def traffic_loop(rps):
    while True:
        # 한 건이 시간 초과로 묶여도 다른 손님은 계속 오게 손님마다 스레드를 띄운다
        threading.Thread(target=random_action, daemon=True).start()
        time.sleep(random.expovariate(rps))


def stats():
    now = time.time()
    out = {k: {"ok": 0, "fail": 0} for k in ("조회", "당행", "타행")}
    for t, kind, ok in list(RESULTS):
        if now - t <= 60 and kind in out:
            out[kind]["ok" if ok else "fail"] += 1
    return out


class H(JsonHandler):
    def do_GET(self):
        if self.path in ("/", "/index.html"):
            body = (Path(__file__).parent / "static" / "index.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return self.wfile.write(body)
        if self.path.startswith("/api/customers"):
            return self.send_json(200, [{"idx": i, "name": c["name"], "account": c["account"]}
                                        for i, c in enumerate(CUSTOMERS[:10])])
        if self.path.startswith("/api/stats"):
            return self.send_json(200, stats())
        if self.path.startswith("/health"):
            return self.send_json(200, {"ok": True})
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        req = self.read_json()
        try:
            c = CUSTOMERS[int(req.get("idx", -1))]
        except (ValueError, IndexError):
            return self.send_json(400, {"error": "고객을 고르세요"})
        if self.path == "/api/login":
            return self.send_json(*do_login(c))
        if self.path == "/api/inquiry":
            return self.send_json(*do_inquiry(c))
        if self.path == "/api/transfer":
            try:
                amount = int(req.get("amount"))
            except (TypeError, ValueError):
                return self.send_json(400, {"error": "금액을 숫자로 넣으세요"})
            return self.send_json(*do_transfer(c, req.get("to_bank", BANK_CODE), req.get("to_account", ""),
                                               req.get("to_name", ""), amount, req.get("memo") or None))
        self.send_json(404, {"error": "not found"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rps", type=float, default=float(os.environ.get("BANK_RPS", 3)), help="초당 가상 손님 수. 0 이면 끔")
    a = ap.parse_args()
    if a.rps > 0:
        threading.Thread(target=traffic_loop, args=(a.rps,), daemon=True).start()
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), H)
    srv.daemon_threads = True
    log("INFO", f"채널계 시작 port={PORT} core={CORE_URL} debug={DEBUG}", attrs={"server.port": PORT})
    print(f"channel-ibk on :{PORT} (core {CORE_URL}, rps={a.rps}, debug={DEBUG}) — 화면 http://localhost:{PORT}/",
          flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
