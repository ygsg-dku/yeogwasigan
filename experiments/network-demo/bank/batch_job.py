#!/usr/bin/env python3
"""일 마감 이자 계산 배치 (장애 ② 재현).

원장 쓰기 잠금(BEGIN IMMEDIATE)을 잡은 채 계좌마다 이자를 천천히 더하고, 끝에 한 번만 커밋한다.
계좌 몇 개씩 끊어 커밋해야 하는데 전체를 한 트랜잭션으로 묶은 실수다.
잡고 있는 동안 계정계의 이체는 busy_timeout(2초) 뒤 'database is locked' 로 실패한다. 조회는 WAL 이라 계속 된다.

  python3 batch_job.py --hold 60
"""
import argparse
import os
import sqlite3
import time

from common import Log

DB = os.environ.get("BANK_DB", "data/ledger.db")
log = Log("batch-interest", "1.0.3")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hold", type=float, default=60, help="잠금을 잡고 있을 초")
    a = ap.parse_args()
    con = sqlite3.connect(DB, timeout=10, isolation_level=None)
    accounts = [r[0] for r in con.execute("SELECT account FROM accounts")]
    t0 = time.monotonic()
    con.execute("BEGIN IMMEDIATE")
    log("INFO", f"일 마감 이자 계산 배치 시작 대상={len(accounts)}계좌 트랜잭션=1개(전체) lock=BEGIN IMMEDIATE",
        attrs={"batch.job": "daily-interest", "batch.size": len(accounts), "db.system": "sqlite",
               "db.operation": "BEGIN IMMEDIATE"})
    print(f"[batch] 원장 잠금 시작, {a.hold:.0f}초 동안 {len(accounts)}계좌 처리", flush=True)
    for acc in accounts:
        con.execute("UPDATE accounts SET balance = balance + balance / 36500 WHERE account=?", (acc,))
        time.sleep(a.hold / len(accounts))
    con.execute("COMMIT")
    took = time.monotonic() - t0
    log("INFO", f"일 마감 이자 계산 배치 완료 처리={len(accounts)}계좌 소요={took:.1f}s",
        attrs={"batch.job": "daily-interest", "batch.size": len(accounts), "duration_ms": int(took * 1000)})
    print(f"[batch] 완료, 잠금 해제 ({took:.1f}초)", flush=True)


if __name__ == "__main__":
    main()
