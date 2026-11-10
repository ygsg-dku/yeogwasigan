#!/usr/bin/env python3
"""가짜 카드결제 서비스 (회사 서버 역할).

학교 VM(yeogwa-company)에서 돌린다. 실제 결제는 하지 않는다. 손님 요청을 스스로 만들어
checkout-api → payment-service → pg-gateway(외부 PG사 흉내) 순서로 처리하는 척하면서,
각 단계의 로그를 OpenTelemetry 3층 구조(resource / scope / logRecord) JSON 한 줄씩 파일에 쓴다.

일부러 민감한 칸이 많다 (회원 ID, 이메일, 전화번호, 카드번호, 접속 IP). 게이트웨이가 이걸 걸러내는지 보는 것이 목적이다.
카드번호는 Luhn 검사만 통과하는 가짜 번호다.

장애 스위치 (실행 중 바꿀 수 있다):
  curl -X POST localhost:9000/fault -d 'mode=pg_timeout'        PG 응답 지연. 에러 메시지에 민감값 없음
  curl -X POST localhost:9000/fault -d 'mode=pg_timeout_leaky'  같은 장애인데 개발자가 메시지에 카드번호를 찍는 실수
  curl -X POST localhost:9000/fault -d 'mode=none'              정상으로 되돌리기

실행:
  python3 payment_service.py --rps 3 --log logs/payment.jsonl
표준 라이브러리만 쓴다(pip 설치 필요 없음).
"""
import argparse
import json
import os
import random
import secrets
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

HOST = socket.gethostname()
LOCK = threading.Lock()
STATE = {"mode": "none", "since": time.time()}

LAST_NAMES = ["kim", "lee", "park", "choi", "jung", "kang", "cho", "yoon"]
MERCHANTS = ["M-COFFEE-001", "M-BOOK-014", "M-FOOD-203", "M-MART-077"]


def luhn_card(prefix="4"):
    """Luhn 검사를 통과하는 16자리 가짜 카드번호."""
    digits = [int(c) for c in prefix] + [random.randint(0, 9) for _ in range(15 - len(prefix))]
    total = 0
    for i, d in enumerate(reversed(digits)):
        if i % 2 == 0:
            d *= 2
            if d > 9:
                d -= 9
        total += d
    digits.append((10 - total % 10) % 10)
    s = "".join(map(str, digits))
    return f"{s[0:4]}-{s[4:8]}-{s[8:12]}-{s[12:16]}"


def make_customers(n=40):
    out = []
    for i in range(n):
        name = random.choice(LAST_NAMES)
        uid = f"u{random.randint(100000, 999999)}"
        out.append({
            "user.id": uid,
            "user.email": f"{name}{i}@example.com",
            "user.phone": f"010-{random.randint(1000, 9999)}-{random.randint(1000, 9999)}",
            "card.number": luhn_card(random.choice(["4", "5"])),
            "client.ip": f"211.{random.randint(30, 250)}.{random.randint(0, 255)}.{random.randint(1, 254)}",
        })
    return out


CUSTOMERS = make_customers()


class LogWriter:
    def __init__(self, path):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        self.f = open(path, "a", encoding="utf-8")

    def write(self, service, severity, body, trace_id, span_id, parent_span_id=None, **attrs):
        rec = {
            "resource": {
                "service.name": service,
                "service.version": "2.3.1",
                "deployment.environment": "prod-sim",
                "host.name": HOST,
            },
            "scope": {"name": f"com.example.{service.replace('-', '.')}"},
            "logRecord": {
                "timeUnixNano": str(time.time_ns()),
                "severityText": severity,
                "body": body,
                "traceId": trace_id,
                "spanId": span_id,
                "attributes": {k.replace("__", "."): v for k, v in attrs.items()},
            },
        }
        if parent_span_id:
            rec["logRecord"]["parentSpanId"] = parent_span_id
        line = json.dumps(rec, ensure_ascii=False)
        with LOCK:
            self.f.write(line + "\n")
            self.f.flush()


def handle_payment(log, c, amount, merchant):
    """손님 1명의 결제 1건을 세 서비스에 걸쳐 처리하는 척하며 로그를 남긴다."""
    trace = secrets.token_hex(16)
    s_api, s_pay, s_pg = secrets.token_hex(8), secrets.token_hex(8), secrets.token_hex(8)
    mode = STATE["mode"]
    common = {"user.id": c["user.id"], "client.ip": c["client.ip"]}

    log.write("checkout-api", "INFO", f"POST /api/v1/pay user={c['user.id']} amount={amount}", trace, s_api,
              **common, **{"user.email": c["user.email"], "url.path": f"/api/v1/pay?userId={c['user.id']}",
                           "http.request.method": "POST", "merchant.id": merchant})
    log.write("payment-service", "INFO", f"결제 요청 수신 merchant={merchant} amount={amount}", trace, s_pay, s_api,
              **common, **{"card.number": c["card.number"], "payment.amount": amount, "merchant.id": merchant,
                           "user.phone": c["user.phone"]})

    failing = mode in ("pg_timeout", "pg_timeout_leaky") and random.random() < 0.7
    if not failing:
        log.write("pg-gateway", "INFO", "PG 승인 완료 code=0000", trace, s_pg, s_pay,
                  **{"pg.name": "KCP-sim", "pg.response_code": "0000", "pg.latency_ms": random.randint(80, 240),
                     "card.number": c["card.number"]})
        log.write("checkout-api", "INFO", "결제 성공 200", trace, s_api, **common,
                  **{"http.response.status_code": 200})
        return 200

    latency = random.randint(3000, 3200)
    log.write("pg-gateway", "WARN", f"PG 응답 없음 {latency}ms (연결 풀 32/32 사용 중)", trace, s_pg, s_pay,
              **{"pg.name": "KCP-sim", "pg.latency_ms": latency, "pg.pool.active": 32, "pg.pool.max": 32})
    if mode == "pg_timeout_leaky":
        # 개발자 실수: 디버깅하려고 에러 메시지에 카드번호와 회원 정보를 그대로 넣었다
        msg = (f"PG 승인 요청 시간 초과 (pg=KCP-sim, timeout=3000ms) card={c['card.number']} "
               f"user_id={c['user.id']} phone={c['user.phone']}")
    else:
        msg = "PG 승인 요청 시간 초과 (pg=KCP-sim, timeout=3000ms, pool=32/32)"
    log.write("payment-service", "ERROR", msg, trace, s_pay, s_api,
              **common, **{"card.number": c["card.number"], "payment.amount": amount,
                           "exception.type": "com.example.pay.PgTimeoutException",
                           "exception.message": msg,
                           "exception.stacktrace": "com.example.pay.PgTimeoutException: " + msg
                           + "\n\tat com.example.pay.PgClient.approve(PgClient.java:88)"
                           + "\n\tat com.example.pay.PaymentService.pay(PaymentService.java:142)"})
    log.write("checkout-api", "ERROR", "결제 실패 504 Gateway Timeout", trace, s_api, **common,
              **{"http.response.status_code": 504, "user.email": c["user.email"]})
    return 504


def traffic_loop(log, rps):
    while True:
        c = random.choice(CUSTOMERS)
        try:
            handle_payment(log, c, random.choice([4500, 12000, 8900, 32000, 1500]), random.choice(MERCHANTS))
        except Exception as e:  # 시연 중 멈추지 않게
            print("traffic error:", e)
        time.sleep(random.expovariate(rps))


def make_handler(log):
    class H(BaseHTTPRequestHandler):
        def _send(self, code, obj):
            body = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path.startswith("/health"):
                return self._send(200, {"ok": True, "mode": STATE["mode"]})
            self._send(404, {"error": "not found"})

        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            form = parse_qs(self.rfile.read(n).decode())
            if self.path.startswith("/fault"):
                mode = (form.get("mode") or ["none"])[0]
                if mode not in ("none", "pg_timeout", "pg_timeout_leaky"):
                    return self._send(400, {"error": "mode = none | pg_timeout | pg_timeout_leaky"})
                STATE.update(mode=mode, since=time.time())
                print(f"[fault] mode={mode}")
                return self._send(200, {"mode": mode})
            if self.path.startswith("/pay"):
                code = handle_payment(log, random.choice(CUSTOMERS), 10000, MERCHANTS[0])
                return self._send(code, {"status": code})
            self._send(404, {"error": "not found"})

        def log_message(self, *a):
            pass

    return H


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9000)
    ap.add_argument("--rps", type=float, default=3.0, help="초당 가짜 손님 결제 수")
    ap.add_argument("--log", default="logs/payment.jsonl")
    a = ap.parse_args()
    log = LogWriter(a.log)
    threading.Thread(target=traffic_loop, args=(log, a.rps), daemon=True).start()
    print(f"payment-service sim on :{a.port}, log → {a.log}, rps={a.rps}")
    ThreadingHTTPServer(("0.0.0.0", a.port), make_handler(log)).serve_forever()


if __name__ == "__main__":
    main()
