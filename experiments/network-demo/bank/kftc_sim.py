#!/usr/bin/env python3
"""대외기관 흉내 (타행이체 중계). 실제 기관과 관계없는 가짜 서비스다.

계정계가 타행이체를 보내면 50~200ms 뒤 승인 코드 0000 을 돌려준다.
장애 ①: 이 프로세스를 kill -STOP 으로 멈추면 운영체제가 연결은 받아 주지만 답을 못 해서, 계정계 쪽에서 진짜 시간 초과가 난다.

  python3 kftc_sim.py --port 9200
"""
import argparse
import os
import random
import secrets
import time
from http.server import ThreadingHTTPServer

from common import BANKS, JsonHandler, Log, new_span

log = Log("kftc-relay", "3.0.8")


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 128   # 멈췄을 때 연결이 거부되지 않고 쌓여서 계정계가 '응답 없음'(시간 초과)을 보게 한다


class H(JsonHandler):
    def do_GET(self):
        if self.path.startswith("/health"):
            return self.send_json(200, {"ok": True})
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        if not self.path.startswith("/interbank"):
            return self.send_json(404, {"error": "not found"})
        span = new_span(self.headers.get("traceparent"))
        req = self.read_json()
        t0 = time.monotonic()
        time.sleep(random.uniform(0.05, 0.2))
        relay = "R" + secrets.token_hex(6).upper()
        log("INFO", f"타행이체 중계 완료 to={BANKS.get(req.get('to_bank'), '?')} code=0000", span, {
            "interbank.bank_code": req.get("to_bank"),
            "interbank.relay_id": relay,
            "interbank.response_code": "0000",
            "counterparty.name": req.get("to_name"),
            "counterparty.account": req.get("to_account"),
            "transfer.amount": req.get("amount"),
            "duration_ms": int((time.monotonic() - t0) * 1000),
        })
        self.send_json(200, {"code": "0000", "relay_id": relay})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=int(os.environ.get("BANK_KFTC_PORT", 9200)))
    a = ap.parse_args()
    srv = Server(("127.0.0.1", a.port), H)
    log("INFO", f"대외기관 중계 시작 port={a.port}", attrs={"server.port": a.port})
    print(f"kftc-relay sim on :{a.port}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
