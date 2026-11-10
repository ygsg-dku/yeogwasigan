#!/usr/bin/env bash
# 여과은행 시뮬레이터 켜고 끄기 + 장애 일으키기. 장애는 실제로 실행하는 명령을 '+' 로 찍어서 보여 준다.
#
#   ./bank.sh start [rps]     대외기관 → 계정계 → 채널계 → 운영 모니터 순서로 띄운다 (기본 rps 3)
#                             인터넷뱅킹 http://localhost:9000/ · 운영 모니터 http://localhost:9300/
#                             모니터에서 여과시간으로 보내려면 YG_DEMO_PASSWORD=… ./bank.sh start
#   ./bank.sh status          프로세스 상태 (T = kill -STOP 으로 멈춘 상태)
#   ./bank.sh stop            모두 끈다
#   ./bank.sh reset           끄고 로그·원장까지 지운다
#   ./bank.sh watch           경고·에러 로그만 실시간으로 보여 준다 (Ctrl+C 로 끝)
#
#   ./bank.sh kftc-freeze     장애 ① 대외기관 프로세스를 멈춘다 (kill -STOP) → 타행이체만 시간 초과
#   ./bank.sh kftc-resume              되돌리기 (kill -CONT)
#   ./bank.sh batch [초]      장애 ② 이자 배치가 원장 잠금을 잡는다 (기본 60초) → 이체 전부 실패, 조회는 됨
#   ./bank.sh deploy-bad      장애 ③ 계정계를 잘못된 포트(9101)로 다시 배포 → 채널계가 연결 못 함
#   ./bank.sh deploy-fix               되돌리기 (9100 으로 다시 배포)
#   ./bank.sh debug-on        장애 ④ 디버그 로그를 켜고 다시 배포 → 오류 메시지에 실명·주민번호·계좌가 찍힘
#   ./bank.sh debug-off                되돌리기
#   ./bank.sh recover         켜진 장애를 모두 되돌린다 (운영 모니터의 '모두 복구')
#
# 로그 logs/bank.jsonl, 원장 data/ledger.db, PID run/*.pid, 각 프로세스 출력 run/*.out
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p run logs data

say() { echo "+ $*"; }
pid() { cat "run/$1.pid" 2>/dev/null || true; }
alive() { local p; p=$(pid "$1"); [ -n "$p" ] && kill -0 "$p" 2>/dev/null; }
setting() { cat "run/$1" 2>/dev/null || echo "$2"; }

start_one() {  # 이름, 그 뒤는 실행할 명령
  local name=$1; shift
  nohup "$@" > "run/$name.out" 2>&1 &
  echo $! > "run/$name.pid"
}

stop_one() {
  if alive "$1"; then
    kill -CONT "$(pid "$1")" 2>/dev/null || true   # 멈춰 있으면 먼저 깨워야 종료 신호를 받는다
    kill "$(pid "$1")" 2>/dev/null || true
  fi
  rm -f "run/$1.pid"
}

status() {
  for n in channel core kftc batch monitor; do
    if alive "$n"; then
      printf "%-8s pid %-6s 상태 %s\n" "$n" "$(pid "$n")" "$(ps -o stat= -p "$(pid "$n")" | tr -d ' ')"
    else
      printf "%-8s 꺼짐\n" "$n"
    fi
  done
  echo "설정: rps=$(setting rps 3) core_port=$(setting core_port 9100) debug=$(setting debug 0)"
}

stop_all() { for n in monitor channel core kftc batch; do stop_one "$n"; done; }

start_kftc() { start_one kftc python3 kftc_sim.py; }
start_core() {
  start_one core env BANK_CORE_PORT="$(setting core_port 9100)" BANK_DEBUG="$(setting debug 0)" python3 core.py
}
start_channel() {
  start_one channel env BANK_DEBUG="$(setting debug 0)" python3 channel.py --rps "$(setting rps 3)"
}
start_monitor() { start_one monitor python3 ops_monitor.py; }   # YG_DEMO_PASSWORD · YG_GATEWAY_URL 은 셸에서 그대로 넘어간다

case "${1:-}" in
  start)
    echo "${2:-3}" > run/rps
    alive kftc || start_kftc
    alive core || start_core
    sleep 0.5
    alive channel || start_channel
    alive monitor || start_monitor
    sleep 0.5
    status
    ;;
  stop)
    stop_all
    echo "모두 껐습니다"
    ;;
  reset)
    stop_all
    rm -rf run logs data
    echo "로그·원장을 지웠습니다"
    ;;
  status)
    status
    ;;
  watch)
    touch logs/bank.jsonl
    tail -n 0 -f logs/bank.jsonl | python3 -u -c '
import json, sys, time
for line in sys.stdin:
    r = json.loads(line); lr = r["logRecord"]
    if lr["severityText"] != "INFO" or "배치" in lr["body"] or "시작 port" in lr["body"]:
        t = time.strftime("%H:%M:%S", time.localtime(int(lr["timeUnixNano"]) / 1e9))
        print(t, lr["severityText"].ljust(5), r["resource"]["service.name"].ljust(14), lr["body"][:150])
'
    ;;
  kftc-freeze)
    say kill -STOP "$(pid kftc)"; kill -STOP "$(pid kftc)"
    ;;
  kftc-resume)
    say kill -CONT "$(pid kftc)"; kill -CONT "$(pid kftc)"
    ;;
  batch)
    say python3 batch_job.py --hold "${2:-60}" "&"
    start_one batch python3 batch_job.py --hold "${2:-60}"
    ;;
  deploy-bad|deploy-fix)
    port=$([ "$1" = deploy-bad ] && echo 9101 || echo 9100)
    echo "$port" > run/core_port
    say "계정계 재배포 BANK_CORE_PORT=$port"
    stop_one core; start_core; sleep 0.5; status
    ;;
  debug-on|debug-off)
    [ "$1" = debug-on ] && echo 1 > run/debug || echo 0 > run/debug
    say "계정계·채널계 재배포 BANK_DEBUG=$(setting debug 0)"
    stop_one core; stop_one channel; start_core; sleep 0.5; start_channel; sleep 0.5; status
    ;;
  recover)
    if alive kftc && [ "$(ps -o stat= -p "$(pid kftc)" | tr -d ' ' | cut -c1)" = T ]; then
      say kill -CONT "$(pid kftc)"; kill -CONT "$(pid kftc)"
    fi
    if alive batch; then
      say kill "$(pid batch)" "(이자 배치 중단, 트랜잭션은 되돌려짐)"; stop_one batch
    fi
    if [ "$(setting core_port 9100)" != 9100 ] || [ "$(setting debug 0)" != 0 ]; then
      echo 9100 > run/core_port; echo 0 > run/debug
      say "계정계·채널계 재배포 BANK_CORE_PORT=9100 BANK_DEBUG=0"
      stop_one core; stop_one channel; start_core; sleep 0.5; start_channel; sleep 0.5
    fi
    status
    ;;
  *)
    sed -n '2,21p' "$(basename "$0")"; exit 1
    ;;
esac
