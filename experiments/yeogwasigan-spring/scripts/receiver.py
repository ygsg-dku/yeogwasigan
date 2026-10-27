"""수신 기록기 (receiver). 외부 AI 자리에 두고 '실제로 받은 것'을 기록한다.

OpenAI chat completions 와 같은 주소(/v1/chat/completions)로 요청을 받아
messages[0].content 의 sha256 과 길이를 /data/received.jsonl 에 한 줄씩 남기고, 고정 답을 돌려준다.
게이트웨이 감사 기록의 '보낸 내용 sha256' 과 이 파일의 sha256 이 같으면 승인한 사본이 그대로 도착한 것이다.
받은 본문은 저장하지 않는다(해시와 길이만).
"""
import hashlib
import json
import os
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = os.environ.get("RECEIVER_LOG", "/data/received.jsonl")


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        try:
            content = json.loads(body)["messages"][0]["content"]
        except (ValueError, KeyError, IndexError, TypeError):
            self.send_error(400, "messages[0].content 가 없습니다")
            return
        data = content.encode("utf-8")
        record = {
            "at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "path": self.path,
            "sha256": hashlib.sha256(data).hexdigest(),
            "chars": len(content),
        }
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
        answer = {"choices": [{"message": {"role": "assistant",
                                           "content": "[receiver] 받음 sha256=" + record["sha256"]}}]}
        out = json.dumps(answer, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
