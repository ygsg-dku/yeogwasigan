"""여과은행 시뮬레이터 공통 부분: 로그 쓰기, 추적 ID 넘기기, 가짜 고객, 작은 HTTP 도우미.

채널계·계정계·대외기관·배치가 같은 파일에 OpenTelemetry 3층 구조(resource / scope / logRecord) JSON 을 한 줄씩 쓴다.
여과은행과 상대 은행(가람·누리·다솜)은 모두 가상의 은행이다.

민감값(실명·주민번호·계좌번호·전화·접속 IP)은 전부 가짜다. 고객은 고정 seed 로 만들어서 어떤 값이 로그에 나올지 미리 안다.
주민번호는 형식만 맞추고 검증 숫자를 일부러 틀리게 만들어 실제로 있는 번호와 겹치지 않는다.
표준 라이브러리만 쓴다(pip 설치 필요 없음).
"""
import json
import os
import random
import secrets
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler

# 실제 컴퓨터 이름은 개인 이름이 들어 있을 수 있어 로그에 쓰지 않는다 (장애 폴더가 공개 저장소에 올라간다)
HOST = os.environ.get("BANK_HOST", "yeogwa-company")
LOG_PATH = os.environ.get("BANK_LOG", "logs/bank.jsonl")
DEBUG = os.environ.get("BANK_DEBUG") == "1"   # 개발자 디버그 로그 실수 재현 (장애 ④)
SEED = 20261108

BANK_CODE = "777"
BANKS = {"777": "여과은행", "301": "가람은행", "302": "누리은행", "303": "다솜은행"}

SURNAMES = "김이박최정강조윤장임한오서신권황안송류홍"
GIVEN = ["민준", "서연", "도윤", "하은", "시우", "지민", "예준", "수아", "주원", "지우",
         "하준", "서윤", "지호", "채원", "건우", "유진", "우진", "다은", "현우", "소율"]


class Log:
    """서비스 하나의 로그 기록기. 여러 프로세스가 같은 파일에 써도 줄이 섞이지 않게 O_APPEND 로 한 번에 쓴다."""

    def __init__(self, service, version, path=LOG_PATH):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        self.fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o644)
        self.resource = {"service.name": service, "service.version": version,
                         "deployment.environment": "prod-sim", "host.name": HOST}
        self.scope = {"name": "bank.yeogwa." + service.replace("-", ".")}

    def __call__(self, severity, body, span=None, attrs=None):
        rec = {"timeUnixNano": str(time.time_ns()), "severityText": severity, "body": body}
        if span:
            rec["traceId"], rec["spanId"] = span[0], span[1]
            if span[2]:
                rec["parentSpanId"] = span[2]
        rec["attributes"] = {k: v for k, v in (attrs or {}).items() if v is not None}
        line = json.dumps({"resource": self.resource, "scope": self.scope, "logRecord": rec}, ensure_ascii=False)
        os.write(self.fd, (line + "\n").encode())


def new_span(traceparent=None):
    """(traceId, spanId, parentSpanId). traceparent 헤더가 오면 같은 추적을 이어 간다."""
    if traceparent:
        parts = traceparent.split("-")
        if len(parts) == 4:
            return parts[1], secrets.token_hex(8), parts[2]
    return secrets.token_hex(16), secrets.token_hex(8), None


def traceparent(span):
    return f"00-{span[0]}-{span[1]}-01"


def fake_rrn(rng):
    """형식은 맞지만 검증 숫자가 틀린 가짜 주민등록번호."""
    body = f"{rng.randint(60, 99):02d}{rng.randint(1, 12):02d}{rng.randint(1, 28):02d}{rng.choice('12')}"
    body += "".join(str(rng.randint(0, 9)) for _ in range(5))
    check = (11 - sum(int(c) * w for c, w in zip(body, [2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5])) % 11) % 10
    return f"{body[:6]}-{body[6:]}{(check + 1) % 10}"


def _people():
    rng = random.Random(SEED)
    names = rng.sample([s + g for s in SURNAMES for g in GIVEN], 80)
    customers = [{
        "name": names[i],
        "rrn": fake_rrn(rng),
        "phone": f"010-{rng.randint(2000, 9999)}-{rng.randint(1000, 9999)}",
        "account": f"{BANK_CODE}-{rng.randint(10, 99)}-{rng.randint(100000, 999999)}",
        "ip": f"{rng.choice([211, 175, 121, 112])}.{rng.randint(10, 250)}.{rng.randint(0, 255)}.{rng.randint(1, 254)}",
        "balance": rng.randint(20, 300) * 100_000,
    } for i in range(50)]
    others = [{
        "name": names[50 + i],
        "bank": code,
        "account": f"{code}-{rng.randint(1000, 9999)}-{rng.randint(100000, 999999)}",
    } for i, code in enumerate(rng.choice(["301", "302", "303"]) for _ in range(30))]
    return customers, others


CUSTOMERS, COUNTERPARTIES = _people()


def post_json(url, payload, span, timeout):
    """JSON POST. 4xx·5xx 도 (상태, 본문) 으로 돌려준다. 연결 거부·시간 초과는 예외 그대로 올린다."""
    req = urllib.request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "traceparent": traceparent(span)})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except ValueError:
            return e.code, {}


def error_name(e):
    """urllib 이 감싼 원래 예외 이름 (ConnectionRefusedError, TimeoutError …)."""
    inner = getattr(e, "reason", None)
    return type(inner if isinstance(inner, BaseException) else e).__name__


class JsonHandler(BaseHTTPRequestHandler):
    def read_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass
