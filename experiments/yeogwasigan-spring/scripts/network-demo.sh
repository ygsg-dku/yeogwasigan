#!/usr/bin/env bash
# 네트워크 분리 시연: docker compose -f docker-compose.gateway.yml up -d --build 뒤에 실행한다.
#   ① 내부 서버 → 외부 AI 직접 호출      : 실패해야 한다
#   ② 내부 서버 → 게이트웨이              : 성공해야 한다
#   ③ 게이트웨이 → 외부 AI 주소 연결      : 성공해야 한다 (키 없이 접속만 확인, 401 이 정상)
set -u
cd "$(dirname "$0")/.."
DC="docker compose -f docker-compose.gateway.yml"
AI=https://api.openai.com/v1/models
pass=0

check() {   # check <설명> <기대: ok|fail> <명령...>
  local desc=$1 want=$2; shift 2
  local out code
  out=$("$@" 2>&1); code=$?
  local got=fail; [ $code -eq 0 ] && got=ok
  if [ "$got" = "$want" ]; then echo "[통과] $desc"; pass=$((pass + 1)); else echo "[실패] $desc"; fi
  echo "       → ${out:0:160}"
}

check "① 내부 서버가 외부 AI 를 직접 부르면 막힌다" fail \
  $DC exec -T internal-client curl -sS -m 8 -o /dev/null -w "%{http_code}" $AI
check "② 내부 서버에서 게이트웨이로는 닿는다" ok \
  $DC exec -T internal-client curl -sS -m 8 -f http://gateway:8080/api/gateway/meta
check "③ 게이트웨이는 외부 AI 주소에 닿는다 (401 = 키 없이 접속만 확인)" ok \
  $DC exec -T gateway curl -sS -m 8 -o /dev/null -w "%{http_code}" $AI

echo "결과: $pass/3 통과"
[ $pass -eq 3 ]
