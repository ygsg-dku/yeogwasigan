"""pilot 실험 러너. 절차는 PROTOCOL.md.

python pilot.py flag <이름> <variant>    플래그 바꾸고 flagd 재시작
python pilot.py reset                   플래그 전부 원복
python pilot.py capture <시나리오> <초>  지금부터 n초치 텔레메트리 잘라서 저장
"""
import json, subprocess, sys, time
from pathlib import Path

HERE = Path(__file__).parent
DEMO = HERE / "otel-demo"
FLAGS = DEMO / "src/flagd/demo.flagd.json"
TELE = HERE / "telemetry"
OUT = HERE / "scenarios"


def sh(*cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def restart_flagd():
    sh("docker", "restart", "flagd")
    time.sleep(8)  # 클라이언트 재연결 여유


def flag(name, variant):
    d = json.loads(FLAGS.read_text())
    if variant not in d["flags"][name]["variants"]:
        sys.exit(f"{name}: 없는 variant {variant}. 가능: {list(d['flags'][name]['variants'])}")
    d["flags"][name]["defaultVariant"] = variant
    FLAGS.write_text(json.dumps(d, indent=2))
    restart_flagd()
    print(f"{name}={variant}")


def reset():
    sh("git", "-C", str(DEMO), "checkout", "--", "src/flagd/demo.flagd.json")
    restart_flagd()
    print("플래그 원복")


def lines(p):
    # 쓰는 중인 마지막 줄은 빼고 셈
    return sum(1 for ln in p.open("rb") if ln.endswith(b"\n")) if p.exists() else 0


def capture(scenario, seconds):
    # 파일을 자르지 않고 줄 번호로 구간을 뗀다. collector가 파일을 열고 있어서 truncate하면 구멍이 생긴다
    raw, red = TELE / "raw.jsonl", TELE / "redacted.jsonl"
    r0, d0 = lines(raw), lines(red)
    t0 = time.time()
    time.sleep(seconds)
    time.sleep(3)  # flush_interval 1s 여유
    r1, d1 = lines(raw), lines(red)
    dst = OUT / scenario
    dst.mkdir(parents=True, exist_ok=True)
    for src, a, b, name in ((raw, r0, r1, "raw.jsonl"), (red, d0, d1, "redacted.jsonl")):
        with src.open("rb") as f, (dst / name).open("wb") as g:
            for i, ln in enumerate(f):
                if i >= b:
                    break
                if i >= a:
                    g.write(ln)
    (dst / "window.json").write_text(json.dumps(
        {"start_unix": t0, "seconds": seconds, "raw_lines": [r0, r1], "redacted_lines": [d0, d1]}, indent=2))
    print(f"{scenario}: raw {r1 - r0}줄, redacted {d1 - d0}줄")


if __name__ == "__main__":
    cmd, *args = sys.argv[1:]
    {"flag": lambda: flag(*args),
     "reset": reset,
     "capture": lambda: capture(args[0], int(args[1]))}[cmd]()
