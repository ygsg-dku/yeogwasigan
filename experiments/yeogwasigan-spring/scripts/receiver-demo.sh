#!/usr/bin/env bash
# 수신 증거 시연: 요청 → 승인 → 전송 한 번을 실제로 돌리고,
# 게이트웨이 감사 기록의 '보낸 내용 sha256' 과 receiver 가 기록한 '받은 내용 sha256' 을 비교한다.
#
#   export YG_DEMO_PASSWORD=...   # 시연 계정 비밀번호 (화면에 찍지 않는다)
#   docker compose -f docker-compose.gateway.yml -f docker-compose.receiver.yml up -d --build
#   ./scripts/receiver-demo.sh [시나리오 폴더, 기본 s01_payment_fail]
set -euo pipefail
cd "$(dirname "$0")/.."
: "${YG_DEMO_PASSWORD:?YG_DEMO_PASSWORD 를 compose 를 띄울 때와 같은 값으로 export 하세요}"
BASE=${GATEWAY_URL:-http://localhost:8090}
LOG_FILE=scenarios/${1:-s01_payment_fail}/raw.jsonl
RECEIVED=data/receiver/received.jsonl
TMP=$(mktemp -d); trap 'rm -r "$TMP"' EXIT

login() {   # login <사용자> → 쿠키 파일
  curl -sf -c "$TMP/$1" -o /dev/null --data-urlencode "username=$1" --data-urlencode "password=$YG_DEMO_PASSWORD" "$BASE/login"
  curl -sf -b "$TMP/$1" -c "$TMP/$1" -o /dev/null "$BASE/api/gateway/me"   # XSRF-TOKEN 쿠키 받기
}
xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$TMP/$1"; }
post() {    # post <사용자> <경로> <JSON 파일> : 실패하면 서버가 준 이유를 보여 주고 멈춘다
  local code
  code=$(curl -s -o "$TMP/out" -w "%{http_code}" -b "$TMP/$1" -c "$TMP/$1" -H "X-XSRF-TOKEN: $(xsrf "$1")" \
       -H 'Content-Type: application/json' --data-binary @"$3" "$BASE$2")
  if [ "${code:0:1}" != 2 ]; then echo "   ✕ $2 → HTTP $code $(head -c 300 "$TMP/out")" >&2; exit 1; fi
  cat "$TMP/out"
}
json() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }
lines() { [ -f "$RECEIVED" ] && wc -l < "$RECEIVED" || echo 0; }

before=$(lines)

echo "① 요청자(ops-kim)가 장애 로그를 올린다: $LOG_FILE"
login ops-kim
python3 -c "import json,sys; print(json.dumps({'log': open(sys.argv[1]).read(), 'purpose': 'INCIDENT_ANALYSIS'}))" "$LOG_FILE" > "$TMP/create.json"
post ops-kim /api/requests "$TMP/create.json" > "$TMP/req.json"
ID=$(json "['id']" < "$TMP/req.json")
PKG=$(json "['packageSha256']" < "$TMP/req.json")
echo "   → $ID, 사본 $(json "['records']" < "$TMP/req.json")건, 칸 $(json "['fieldsBefore']" < "$TMP/req.json") → $(json "['fieldsAfter']" < "$TMP/req.json")"

echo "② 승인자(sec-park)가 사본을 보고 승인한다"
login sec-park
printf '{"approve":true,"packageSha256":"%s"}' "$PKG" > "$TMP/decide.json"
STATUS=$(post sec-park "/api/requests/$ID/decision" "$TMP/decide.json" | json "['status']")
echo "   → 상태 $STATUS"

echo "③ 양쪽 기록을 비교한다"
curl -sf -b "$TMP/sec-park" "$BASE/api/requests/$ID/audit" > "$TMP/audit.json"
SENT=$(python3 -c "import json,sys,re; e=[a for a in json.load(sys.stdin) if a['type']=='SENT']; print(re.search(r'sha256=([0-9a-f]{64})', e[-1]['detail']).group(1) if e else '')" < "$TMP/audit.json")
for _ in $(seq 1 10); do [ "$(lines)" -gt "$before" ] && break; sleep 1; done
GOT=""; [ "$(lines)" -gt "$before" ] && GOT=$(tail -n 1 "$RECEIVED" | json "['sha256']")
echo "   게이트웨이가 보낸 내용  sha256 = ${SENT:-(없음)}"
echo "   receiver 가 받은 내용   sha256 = ${GOT:-(없음)}"
if [ -n "$SENT" ] && [ "$SENT" = "$GOT" ]; then
  echo "결과: 일치 — 승인한 사본이 바뀌지 않고 그대로 도착했다"
else
  echo "결과: 불일치"; exit 1
fi
