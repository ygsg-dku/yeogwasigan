#!/usr/bin/env python3
"""로그 전송기 (회사 서버 → 게이트웨이).

payment_service.py 가 쓰는 로그 파일을 계속 읽다가, 에러가 몰리면(장애) 최근 구간을 잘라
네트워크로 게이트웨이(yeogwa-gw)의 POST /api/requests 에 보낸다. 원문은 회사 밖으로 나가지 않고
게이트웨이까지만 간다. 외부 AI 로 가는 건 게이트웨이가 걸러 만든 파생본뿐이다.

실행:
  export YG_DEMO_PASSWORD=...        # 게이트웨이와 같은 시연 비밀번호 (파일에 적지 말 것)
  python3 shipper.py --gateway http://192.168.0.138:8080 --log logs/payment.jsonl
  python3 shipper.py ... --once      # 장애 감지 없이 지금 구간을 바로 한 번 보낸다

표준 라이브러리만 쓴다.
"""
import argparse
import http.cookiejar
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import deque


class Gateway:
    def __init__(self, base, user, password):
        self.base = base.rstrip("/")
        self.user, self.password = user, password
        self.jar = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar),
                                              urllib.request.ProxyHandler({}))

    def _xsrf(self):
        return next((c.value for c in self.jar if c.name == "XSRF-TOKEN"), None)

    def login(self):
        page = self.op.open(self.base + "/login", timeout=10).read().decode()
        m = re.search(r'name="_csrf"[^>]*value="([^"]+)"', page)
        token = m.group(1) if m else self._xsrf()
        data = urllib.parse.urlencode({"username": self.user, "password": self.password, "_csrf": token}).encode()
        res = self.op.open(self.base + "/login", data=data, timeout=10)
        if "error" in res.geturl():
            raise SystemExit("게이트웨이 로그인 실패: 사용자/비밀번호(YG_DEMO_PASSWORD)를 확인하세요")
        # 로그인하면 CSRF 토큰이 새로 바뀐다. 한 번 읽어 새 쿠키를 받는다
        self.op.open(self.base + "/api/gateway/me", timeout=10).read()

    def create(self, log_text, purpose):
        body = json.dumps({"log": log_text, "purpose": purpose}).encode()
        for attempt in (1, 2):
            req = urllib.request.Request(self.base + "/api/requests", data=body, method="POST", headers={
                "Content-Type": "application/json", "X-XSRF-TOKEN": self._xsrf() or ""})
            try:
                return json.loads(self.op.open(req, timeout=60).read())
            except urllib.error.HTTPError as e:
                if e.code in (401, 403) and attempt == 1:
                    self.login()
                    continue
                raise SystemExit(f"전송 실패 {e.code}: {e.read().decode(errors='replace')[:500]}")


def follow(path):
    """tail -f: 파일 끝에서부터 새 줄을 계속 내준다. 새 줄이 없으면 None."""
    while not os.path.exists(path):
        time.sleep(0.5)
    with open(path, encoding="utf-8") as f:
        f.seek(0, os.SEEK_END)
        while True:
            line = f.readline()
            yield line if line else None
            if not line:
                time.sleep(0.2)


def ts(rec):
    return int(rec["logRecord"]["timeUnixNano"]) / 1e9


def send(gw, a, records):
    records = records[-a.max_records:]
    text = "\n".join(json.dumps(r, ensure_ascii=False) for r in records)
    print(f"[shipper] 레코드 {len(records)}건, {len(text.encode()):,} 바이트를 게이트웨이로 전송")
    res = gw.create(text, a.purpose)
    print(f"[shipper] 게이트웨이 응답: id={res.get('id')} 상태={res.get('status')} "
          f"필드 {res.get('fieldsBefore')}→{res.get('fieldsAfter')} 높음경고={res.get('highWarnings')} "
          f"등급={res.get('tier')} 사유={res.get('policyReason')}")
    print(f"[shipper] 승인 화면: {a.gateway}/gateway.html")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gateway", required=True, help="예: http://192.168.0.138:8080")
    ap.add_argument("--user", default="ops-kim", help="요청자 계정 (REQUESTER)")
    ap.add_argument("--log", default="logs/payment.jsonl")
    ap.add_argument("--purpose", default="INCIDENT_ANALYSIS")
    ap.add_argument("--window", type=int, default=30, help="보낼 구간 길이(초)")
    ap.add_argument("--errors", type=int, default=8, help="window 안에 ERROR 가 이만큼이면 장애로 본다")
    ap.add_argument("--cooldown", type=int, default=120, help="한 번 보낸 뒤 다시 보내기까지 기다릴 초")
    ap.add_argument("--max-records", type=int, default=300)
    ap.add_argument("--once", action="store_true")
    a = ap.parse_args()

    password = os.environ.get("YG_DEMO_PASSWORD")
    if not password:
        sys.exit("YG_DEMO_PASSWORD 환경변수를 먼저 설정하세요")
    gw = Gateway(a.gateway, a.user, password)
    gw.login()
    print(f"[shipper] 게이트웨이 {a.gateway} 에 {a.user} 로 로그인. 로그 {a.log} 감시 중")

    if a.once:
        # 파일에 이미 있는 마지막 window 초를 바로 보낸다
        recs = []
        with open(a.log, encoding="utf-8") as f:
            for line in f:
                try:
                    recs.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
        if not recs:
            sys.exit("로그가 비어 있습니다")
        end = ts(recs[-1])
        send(gw, a, [r for r in recs if ts(r) >= end - a.window])
        return

    buf = deque()
    last_sent = 0.0
    for line in follow(a.log):
        now = time.time()
        if line:
            try:
                buf.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        while buf and ts(buf[0]) < now - a.window:
            buf.popleft()
        errors = sum(1 for r in buf if r["logRecord"].get("severityText") == "ERROR")
        if errors < a.errors or now - last_sent <= a.cooldown or not buf:
            continue
        print(f"[shipper] 장애 감지: 최근 {a.window}초 ERROR {errors}건")
        send(gw, a, list(buf))
        last_sent = now


if __name__ == "__main__":
    main()
