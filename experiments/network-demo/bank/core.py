#!/usr/bin/env python3
"""계정계 (원장). 잔액 조회, 당행이체, 타행이체를 sqlite 원장에 기록한다.

  python3 core.py                 # 포트는 BANK_CORE_PORT (기본 9100), 원장은 BANK_DB (기본 data/ledger.db)

진짜로 나는 장애:
  ① 대외기관이 멈추면 타행이체가 3초 뒤 시간 초과 → 출금을 되돌리고 504
  ② 배치가 원장 쓰기 잠금을 오래 잡으면 이체가 busy_timeout(2초) 뒤 'database is locked' → 500. 조회는 WAL 이라 계속 된다
  ③ 잘못된 포트로 배포하면 채널계가 이쪽에 연결을 못 한다
BANK_DEBUG=1 이면 오류 메시지에 요청과 고객 정보를 통째로 찍는다 (개발자 실수 재현, 장애 ④).
"""
import os
import sqlite3
import time
import traceback
from http.server import ThreadingHTTPServer

from common import BANK_CODE, BANKS, CUSTOMERS, DEBUG, JsonHandler, Log, error_name, new_span, post_json

PORT = int(os.environ.get("BANK_CORE_PORT", 9100))
DB = os.environ.get("BANK_DB", "data/ledger.db")
KFTC_URL = os.environ.get("BANK_KFTC_URL", "http://127.0.0.1:9200")
BUSY_TIMEOUT_MS = 2000
KFTC_TIMEOUT_S = 3

log = Log("core-ledger", "5.12.0")


def connect():
    return sqlite3.connect(DB, timeout=BUSY_TIMEOUT_MS / 1000, isolation_level=None)


def init_db():
    os.makedirs(os.path.dirname(os.path.abspath(DB)), exist_ok=True)
    con = connect()
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("CREATE TABLE IF NOT EXISTS accounts (account TEXT PRIMARY KEY, name TEXT, rrn TEXT, phone TEXT,"
                " balance INTEGER NOT NULL)")
    con.execute("CREATE TABLE IF NOT EXISTS transactions (id INTEGER PRIMARY KEY, ts INTEGER, kind TEXT,"
                " from_acc TEXT, to_acc TEXT, to_bank TEXT, amount INTEGER, status TEXT, memo TEXT)")
    if not con.execute("SELECT 1 FROM accounts LIMIT 1").fetchone():
        con.executemany("INSERT INTO accounts VALUES (?,?,?,?,?)",
                        [(c["account"], c["name"], c["rrn"], c["phone"], c["balance"]) for c in CUSTOMERS])
    con.close()


def debug_dump(req):
    """개발자가 디버깅하려고 넣은 코드: 요청과 고객 정보를 그대로 문자열로 만든다."""
    if not DEBUG:
        return ""
    try:
        row = connect().execute("SELECT name, rrn, phone FROM accounts WHERE account=?",
                                (req.get("from_account"),)).fetchone()
    except sqlite3.Error:
        row = None
    cust = {"name": row[0], "rrn": row[1], "phone": row[2]} if row else {}
    return f" | DEBUG req={req} customer={cust}"


def base_attrs(req, kind, t0):
    return {"txn.type": kind, "account.number": req.get("from_account"), "transfer.amount": req.get("amount"),
            "db.system": "sqlite", "duration_ms": int((time.monotonic() - t0) * 1000)}


def inquiry(req, span):
    t0 = time.monotonic()
    row = connect().execute("SELECT balance FROM accounts WHERE account=?", (req.get("account"),)).fetchone()
    attrs = {"txn.type": "조회", "account.number": req.get("account"), "db.system": "sqlite",
             "db.operation": "SELECT", "duration_ms": int((time.monotonic() - t0) * 1000)}
    if not row:
        log("WARN", "잔액 조회 실패: 없는 계좌", span, attrs)
        return 404, {"error": "없는 계좌"}
    log("INFO", "잔액 조회", span, attrs | {"account.balance": row[0]})
    return 200, {"balance": row[0]}


def transfer(req, span):
    t0 = time.monotonic()
    interbank = req.get("to_bank") != BANK_CODE
    kind = "타행" if interbank else "당행"
    src, dst, amount = req.get("from_account"), req.get("to_account"), int(req.get("amount") or 0)
    con = connect()
    try:
        con.execute("BEGIN IMMEDIATE")
        row = con.execute("SELECT balance FROM accounts WHERE account=?", (src,)).fetchone()
        if not row or row[0] < amount:
            con.execute("ROLLBACK")
            log("WARN", f"{kind}이체 거절: 잔액 부족", span, base_attrs(req, kind, t0) | {"error.type": "INSUFFICIENT_FUNDS"})
            return 422, {"error": "잔액 부족"}
        con.execute("UPDATE accounts SET balance = balance - ? WHERE account=?", (amount, src))
        if not interbank:
            con.execute("UPDATE accounts SET balance = balance + ? WHERE account=?", (amount, dst))
        txn = con.execute("INSERT INTO transactions (ts, kind, from_acc, to_acc, to_bank, amount, status, memo)"
                          " VALUES (?,?,?,?,?,?,?,?)",
                          (time.time_ns(), kind, src, dst, req.get("to_bank"), amount,
                           "PENDING" if interbank else "DONE", req.get("memo"))).lastrowid
        con.execute("COMMIT")
    except sqlite3.OperationalError as e:
        if con.in_transaction:
            con.execute("ROLLBACK")
        msg = f"{kind}이체 원장 기록 실패: {e} (busy_timeout={BUSY_TIMEOUT_MS}ms)"
        log("ERROR", msg + debug_dump(req), span, base_attrs(req, kind, t0) | {
            "db.operation": "UPDATE", "db.busy_timeout_ms": BUSY_TIMEOUT_MS,
            "exception.type": f"sqlite3.{type(e).__name__}", "exception.message": str(e),
            "exception.stacktrace": traceback.format_exc()})
        return 500, {"error": f"원장 기록 실패: {e}"}

    if not interbank:
        log("INFO", "당행이체 완료", span, base_attrs(req, kind, t0) | {"db.operation": "UPDATE", "txn.id": txn,
                                                                     "counterparty.account": dst})
        return 200, {"txn_id": txn}

    # 타행: 출금은 이미 기록했다. 대외기관이 승인하면 완료, 못 하면 출금을 되돌린다
    t1 = time.monotonic()
    try:
        code, res = post_json(KFTC_URL + "/interbank", {"to_bank": req.get("to_bank"), "to_account": dst,
                                                        "to_name": req.get("to_name"), "amount": amount},
                              span, KFTC_TIMEOUT_S)
        if code != 200:
            raise RuntimeError(f"대외기관 응답 코드 {code}")
    except Exception as e:  # 시간 초과·연결 거부·이상 응답
        con.execute("BEGIN IMMEDIATE")
        con.execute("UPDATE accounts SET balance = balance + ? WHERE account=?", (amount, src))
        con.execute("UPDATE transactions SET status='FAILED' WHERE id=?", (txn,))
        con.execute("COMMIT")
        waited = int((time.monotonic() - t1) * 1000)
        timed_out = error_name(e) == "TimeoutError"
        short = "대외기관 응답 없음" if timed_out else "대외기관 연결 실패"
        detail = f"{waited}ms (timeout={KFTC_TIMEOUT_S * 1000}ms)" if timed_out else error_name(e)
        msg = f"타행이체 실패: {short} ({BANKS.get(req.get('to_bank'), '?')}) {detail}, 출금 취소 txn={txn}"
        log("ERROR", msg + debug_dump(req), span, base_attrs(req, kind, t0) | {
            "txn.id": txn, "peer.service": "kftc-relay", "server.address": KFTC_URL.split("//")[1],
            "interbank.bank_code": req.get("to_bank"), "interbank.wait_ms": waited,
            "exception.type": error_name(e), "exception.message": str(e),
            "exception.stacktrace": traceback.format_exc()})
        return 504, {"error": short}

    con.execute("UPDATE transactions SET status='DONE' WHERE id=?", (txn,))
    log("INFO", "타행이체 완료", span, base_attrs(req, kind, t0) | {
        "txn.id": txn, "peer.service": "kftc-relay", "interbank.bank_code": req.get("to_bank"),
        "interbank.relay_id": res.get("relay_id"), "interbank.wait_ms": int((time.monotonic() - t1) * 1000),
        "counterparty.account": dst})
    return 200, {"txn_id": txn, "relay_id": res.get("relay_id")}


class H(JsonHandler):
    def do_GET(self):
        if self.path.startswith("/health"):
            return self.send_json(200, {"ok": True, "port": PORT})
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        span = new_span(self.headers.get("traceparent"))
        routes = {"/inquiry": inquiry, "/transfer": transfer}
        fn = routes.get(self.path)
        if not fn:
            return self.send_json(404, {"error": "not found"})
        self.send_json(*fn(self.read_json(), span))


def main():
    init_db()
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
    srv.daemon_threads = True
    log("INFO", f"계정계 시작 port={PORT} db={DB} journal=wal busy_timeout={BUSY_TIMEOUT_MS}ms debug={DEBUG}",
        attrs={"server.port": PORT})
    print(f"core-ledger on :{PORT} (db {DB}, debug={DEBUG})", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
