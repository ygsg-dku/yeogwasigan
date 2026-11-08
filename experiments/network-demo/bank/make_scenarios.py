#!/usr/bin/env python3
"""장애 폴더 만들기: 장애마다 시뮬레이터를 새로 띄워 정상 구간 → 장애 구간 로그를 받아 필드 선택 알고리즘 입력 형식으로 저장한다.

  python3 make_scenarios.py                  # scenarios/BANK_* 5개 (약 4분)
  python3 make_scenarios.py --only BANK_F2_ledgerLock --warmup 10 --fault 20

폴더마다 raw.jsonl · truth.yaml(정답 원인 + 단서 키워드) · injected_pii.yaml(로그에 실제로 나온 가짜 민감값)을 쓴다.
시연용 시뮬레이터와 같은 포트를 쓰므로 ./bank.sh stop 뒤에 돌린다. 원장은 장애마다 새로 만든다.
"""
import argparse
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unicodedata
from pathlib import Path

from common import COUNTERPARTIES, CUSTOMERS

HERE = Path(__file__).parent
VM_DIR = "/home/ubuntu/yeogwasigan/experiments/network-demo/bank"

SCENARIOS = {
    "BANK_S0_normal": {"fault": None},
    "BANK_F1_kftcTimeout": {
        "fault": "kftc_freeze",
        "title": "타행이체 실패 (대외기관 응답 없음)",
        "summary": "대외기관(타행이체 중계) 프로세스가 멈춰 계정계의 타행이체 요청이 3초 시간 초과로 실패했다. 조회·당행이체는 정상",
        "keywords": ["대외기관", "타행", "응답 없음", "시간 초과", "timeout", "TimeoutError", "kftc", "interbank"],
    },
    "BANK_F2_ledgerLock": {
        "fault": "batch",
        "title": "이체 실패 (원장 잠금)",
        "summary": "일 마감 이자 계산 배치가 원장 쓰기 잠금을 한 트랜잭션으로 오래 잡아 이체가 busy_timeout 뒤 'database is locked' 로 실패했다. 조회는 정상",
        "keywords": ["잠금", "배치", "이자", "locked", "lock", "busy_timeout", "OperationalError", "batch"],
    },
    "BANK_F3_badDeploy": {
        "fault": "bad_deploy",
        "title": "전체 거래 실패 (계정계 포트 설정 오류 배포)",
        "summary": "계정계가 설정 오류로 9101 포트로 재배포되어 9100 으로 부르는 채널계가 연결 거부를 받았다. 조회·이체 모두 실패",
        "keywords": ["연결 거부", "포트", "배포", "9101", "Connection refused", "ConnectionRefusedError", "port"],
    },
    "BANK_F4_kftcTimeoutDebug": {
        "fault": "kftc_freeze",
        "debug": True,
        "title": "타행이체 실패 (대외기관 응답 없음, 디버그 로그 켜짐)",
        # 채점 AI 는 이 문장만 보고 답과 비교하므로 원인을 직접 적는다 ('F1 과 같음' 이라고 쓰면 0점이 된다)
        "summary": "대외기관(타행이체 중계) 프로세스가 멈춰 계정계의 타행이체 요청이 3초 시간 초과로 실패했다. 조회·당행이체는 정상. "
                   "디버그 로그가 켜져 있어 오류 메시지에 고객 실명·주민번호·계좌·전화가 찍혔다",
        "keywords": ["대외기관", "타행", "응답 없음", "시간 초과", "timeout", "TimeoutError", "kftc", "interbank"],
    },
}


def hide_local_path(raw):
    """스택트레이스 속 이 컴퓨터의 폴더 경로(사용자 이름이 들어 있음)를 학교 VM 경로로 바꾼다. 장애 폴더는 공개 저장소에 올라간다."""
    for form in ("NFC", "NFD"):   # macOS 는 한글 경로를 자모 분리(NFD)로 줄 때가 있다
        raw = raw.replace(unicodedata.normalize(form, str(HERE.resolve())), VM_DIR)
    return raw


def port_busy(port):
    with socket.socket() as s:
        return s.connect_ex(("127.0.0.1", port)) == 0


def start(script, env, *args):
    return subprocess.Popen([sys.executable, script, *args], cwd=HERE, env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def wait_port(port, timeout=5):
    end = time.time() + timeout
    while time.time() < end and not port_busy(port):
        time.sleep(0.1)


def stop(procs):
    for p in procs:
        if p.poll() is None:
            p.send_signal(signal.SIGCONT)
            p.terminate()
    for p in procs:
        p.wait(timeout=5)


def run_one(sid, spec, a, work):
    env = os.environ | {"BANK_LOG": str(work / "bank.jsonl"), "BANK_DB": str(work / "ledger.db"),
                        "BANK_DEBUG": "1" if spec.get("debug") else "0", "BANK_RPS": str(a.rps)}
    kftc = start("kftc_sim.py", env)
    core = start("core.py", env)
    wait_port(9100)
    channel = start("channel.py", env)
    procs = [kftc, core, channel]
    time.sleep(a.warmup)
    fault = spec["fault"]
    if fault == "kftc_freeze":
        kftc.send_signal(signal.SIGSTOP)
    elif fault == "batch":
        procs.append(start("batch_job.py", env, "--hold", str(a.fault)))
    elif fault == "bad_deploy":
        core.terminate()
        core.wait()
        procs.append(start("core.py", env | {"BANK_CORE_PORT": "9101"}))
    time.sleep(a.fault)
    stop(procs)


def yaml_str(s):
    """YAML 작은따옴표 문자열. 안의 작은따옴표는 두 번 쓴다."""
    return "'" + s.replace("'", "''") + "'"


def write_truth(d, spec):
    if not spec["fault"]:
        return
    (d / "truth.yaml").write_text(
        "# 정답지 — AI 에는 보내지 않는다. 필드 선택 알고리즘의 판정·단서 기준 (make_scenarios.py 가 만듦)\n"
        f"title: {yaml_str(spec['title'])}\n"
        "root_cause:\n"
        f"  summary: {yaml_str(spec['summary'])}\n"
        "# 1단계 단서 키워드. 한 줄 · 큰따옴표 목록으로 적는다\n"
        f"cause_keywords: {json.dumps(spec['keywords'], ensure_ascii=False)}\n", encoding="utf-8")


def write_pii(d, raw):
    """로그에 실제로 나온 가짜 민감값만 적는다. 결과물에서 문자열 검색으로 누출을 센다."""
    known = [(t, c[k]) for c in CUSTOMERS for t, k in
             [("customer.name", "name"), ("customer.rrn", "rrn"), ("customer.phone", "phone"),
              ("account.number", "account"), ("client.ip", "ip")]]
    known += [(t, c[k]) for c in COUNTERPARTIES for t, k in [("counterparty.name", "name"), ("counterparty.account", "account")]]
    sessions = {json.loads(l)["logRecord"]["attributes"].get("session.id") for l in raw.splitlines() if l.strip()}
    known += [("session.id", s) for s in sorted(s for s in sessions if s)]
    items = [(t, v) for t, v in known if v in raw]
    lines = ["# 로그에 실제로 나온 가짜 민감값 (make_scenarios.py 가 자동 생성, 직접 고치지 말 것)",
             "# 전부 시뮬레이터가 고정 seed 로 만든 가짜 값이다. 주민번호는 검증 숫자가 틀린 번호다.", "items:"]
    for i, (t, v) in enumerate(items, 1):
        lines += [f"- id: PII-{i:03d}", f"  type: {t}", f"  value: '{v}'", "  description: '자연 발생: 원본 로그에 이미 있던 값'"]
    (d / "injected_pii.yaml").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(items)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(HERE / "scenarios"))
    ap.add_argument("--only", default="", help="만들 장애 ID (쉼표). 비우면 전부")
    ap.add_argument("--rps", type=float, default=3)
    ap.add_argument("--warmup", type=float, default=15, help="장애 전 정상 구간 (초)")
    ap.add_argument("--fault", type=float, default=25, help="장애 구간 (초)")
    a = ap.parse_args()
    busy = [p for p in (9000, 9100, 9101, 9200) if port_busy(p)]
    if busy:
        sys.exit(f"포트 {busy} 가 사용 중입니다. ./bank.sh stop 뒤에 다시 돌리세요")
    only = [s for s in a.only.split(",") if s]
    for sid, spec in SCENARIOS.items():
        if only and sid not in only:
            continue
        work = Path(tempfile.mkdtemp(prefix="bank-"))
        print(f"[{sid}] 정상 {a.warmup:.0f}초 → {spec['fault'] or '장애 없음'} {a.fault:.0f}초 …", flush=True)
        run_one(sid, spec, a, work)
        d = Path(a.out) / sid
        d.mkdir(parents=True, exist_ok=True)
        raw = hide_local_path((work / "bank.jsonl").read_text(encoding="utf-8"))
        (d / "raw.jsonl").write_text(raw, encoding="utf-8")
        write_truth(d, spec)
        n = write_pii(d, raw)
        shutil.rmtree(work)
        errors = sum('"severityText": "ERROR"' in l for l in raw.splitlines())
        print(f"  → {d}  레코드 {len(raw.splitlines())}건, ERROR {errors}건, 민감값 {n}개", flush=True)


if __name__ == "__main__":
    main()
