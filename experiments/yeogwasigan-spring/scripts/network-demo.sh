#!/usr/bin/env bash
# 네트워크 분리 시연: docker compose -f docker-compose.gateway.yml up -d --build 뒤에 실행한다.
#   열려 있어야 하는 길 (게이트웨이 경유)
#     ② 내부 서버 → 게이트웨이              : 성공해야 한다
#     ③ 게이트웨이 → 외부 AI 주소 연결      : 성공해야 한다 (키 없이 접속만 확인, 401 이 정상)
#   막혀 있어야 하는 우회로 (내부 서버에서 직접)
#     ① 외부 AI 를 이름으로 직접 호출
#     ④ 외부 IP 로 직접 (DNS 를 건너뜀)
#     ⑤ 외부 DNS 서버에 직접 연결
#     ⑥ 게이트웨이를 HTTPS 프록시로 악용 (CONNECT)
#     ⑦ 일반 웹사이트 (평문 HTTP)
#     ⑧ 수신 기록기(receiver)에 직접 — docker-compose.receiver.yml 로 띄웠을 때만
set -u
cd "$(dirname "$0")/.."
DC="docker compose -f docker-compose.gateway.yml"
[ -f docker-compose.receiver.yml ] && $DC -f docker-compose.receiver.yml ps --services --status running 2>/dev/null | grep -qx receiver \
  && DC="$DC -f docker-compose.receiver.yml" && RECEIVER=1
AI=https://api.openai.com/v1/models
pass=0 total=0

check() {   # check <설명> <기대: ok|fail> <명령...>
  local desc=$1 want=$2; shift 2
  local out code
  out=$("$@" 2>&1); code=$?
  local got=fail; [ $code -eq 0 ] && got=ok
  total=$((total + 1))
  if [ "$got" = "$want" ]; then echo "[통과] $desc"; pass=$((pass + 1)); else echo "[실패] $desc"; fi
  echo "       → ${out:0:160}"
}

blocked() { # blocked <설명> <curl 인자...> : 연결 자체가 안 되어야 통과 (6 이름 못 찾음, 7 연결 불가, 56 프록시 거절)
  local desc=$1; shift
  local out code
  out=$($DC exec -T internal-client curl -sS -m 8 --connect-timeout 5 -o /dev/null "$@" 2>&1); code=$?
  total=$((total + 1))
  case $code in
    6|7|56) echo "[통과] $desc"; pass=$((pass + 1)) ;;
    *) echo "[실패] $desc (curl 종료 코드 $code)" ;;
  esac
  echo "       → ${out:0:160}"
}

echo "── 열려 있어야 하는 길"
check "② 내부 서버에서 게이트웨이로는 닿는다" ok \
  $DC exec -T internal-client curl -sS -m 8 -f -o /dev/null -w "%{http_code}" http://gateway:8080/login
check "③ 게이트웨이는 외부 AI 주소에 닿는다 (401 = 키 없이 접속만 확인)" ok \
  $DC exec -T gateway curl -sS -m 8 -o /dev/null -w "%{http_code}" $AI

echo "── 막혀 있어야 하는 우회로 (내부 서버에서 직접)"
blocked "① 외부 AI 를 이름으로 직접 호출" $AI
blocked "④ 외부 IP 로 직접 (DNS 를 건너뜀)" https://1.1.1.1
blocked "⑤ 외부 DNS 서버에 직접 연결" http://8.8.8.8:53
blocked "⑥ 게이트웨이를 HTTPS 프록시로 악용" -x http://gateway:8080 $AI
blocked "⑦ 일반 웹사이트 (평문 HTTP)" http://example.com
[ -n "${RECEIVER:-}" ] && blocked "⑧ 수신 기록기(receiver)에 직접" http://receiver:8000/v1/chat/completions

echo "결과: $pass/$total 통과"
[ $pass -eq $total ]
