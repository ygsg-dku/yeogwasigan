"""필드 선택 알고리즘 (AI 호출 없음).

사람이 파일럿에서 칸을 넣고 빼며 고르던 과정을 규칙으로 옮긴 것이다. 시스템이 달라도 아래 폴더 형식만 맞추면 그대로 돌린다.
입력 : <장애 폴더 루트>/<장애 ID>/  (ScenarioRepository 와 같은 형식)
         raw.jsonl          로그 (OTel 로그 JSON: resource / logRecord / logRecord.attributes)
         injected_pii.yaml  넣어 둔 가짜 민감값 (items[].value)
         truth.yaml         정답 원인 (root_cause.summary) + 단서 키워드 (cause_keywords, 한 줄 큰따옴표 목록)
       <장애 폴더 루트>/<정상 ID>/raw.jsonl  (정상 로그, 기준선)
출력 : 칸마다 단서·누출·연결 점수와 판정(담기 / 조건부 / 토큰화 / 버림). --hand 를 주면 손으로 고른 목록과 비교
       --json 이면 2·3단계(FieldSearchRunner)가 읽을 후보 파일. 장애 폴더·고르는/검증 장애 목록도 같이 적는다

점수 정의
  단서(clue)  : 장애 S 에서 이 칸 값에 S 의 원인 키워드가 나오는데, 정상 로그의 같은 칸에는 그 키워드가 없으면 1. S1~S6 합(0~6)
  새 값(novel): 값 종류가 적은 칸(정상 로그에서 20종 이하)에 정상 로그에 없던 값이 장애에서 나오면 1 (예: 상태 코드 500). 키워드가 없는 단서
  누출(leak)  : 이 칸 값에 들어 있는 민감값 수 (S1~S6 합). 실험의 누출 세기와 같은 문자열 검색
  중요 기록 누출(leak_imp) : WARN 이상·5xx 기록에서만 센 누출
  연결(link)  : 값 하나가 서로 다른 서비스의 기록 2개 이상에 나오는 비율 (요청을 잇는 ID 인가)

판정 규칙 (위에서부터)
  1) 문맥 칸(서비스·시각·심각도)은 항상 담는다 — 원인 답의 '어디·언제·얼마나'
  2) 연결 ≥ 0.3 이고 값 종류가 많으면(ID) → 토큰화 (연결은 남기고 진짜 값은 숨김)
  3) (단서 + 새 값) ≥ 1 이고 누출 = 0 → 담기
  4) 단서 ≥ 1 이고 누출 > 0 이지만 중요 기록만 봐도 단서가 남고 누출은 0 → 조건부 (WARN 이상·5xx 에서만)
     (10/30: 예전 기준 '절반 이하'는 2단계의 누출 금지와 맞지 않아 0 으로 바꿈)
  5) 그 밖 → 버림
"""
import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# 기본값은 OTel 데모. 다른 시스템은 --dir / --normal / --select / --validate / --context 로 바꾼다
OTEL_CONTEXT = "resource.service.name,logRecord.timeUnixNano,logRecord.severityText"
OTEL_HAND = OTEL_CONTEXT + (",logRecord.body,logRecord.traceId,logRecord.spanId,logRecord.parentSpanId,"
                            "logRecord.attributes.exception.message,logRecord.attributes.exception.type,"
                            "logRecord.attributes.http.response.status_code")
SCEN = ROOT / "scenarios"


def flatten(rec):
    out = {}
    for k, v in (rec.get("resource") or {}).items():
        out["resource." + k] = v
    lr = rec.get("logRecord") or {}
    for k, v in lr.items():
        if k == "attributes" and isinstance(v, dict):
            for ak, av in v.items():
                out["logRecord.attributes." + ak] = av
        else:
            out["logRecord." + k] = v
    return {k: ("" if v is None else str(v)) for k, v in out.items()}


def load(name):
    return [flatten(json.loads(l)) for l in (SCEN / name / "raw.jsonl").read_text().splitlines() if l.strip()]


def important(r):
    sev = r.get("logRecord.severityText", "").upper()
    num = r.get("logRecord.severityNumber", "")
    code = r.get("logRecord.attributes.http.response.status_code", "")
    return sev in ("WARN", "WARNING", "ERROR", "FATAL") or (num.isdigit() and int(num) >= 13) or code.startswith("5")


def cause_keywords(name):
    # ponytail: 표준 라이브러리에 YAML 이 없어 cause_keywords 한 줄만 JSON 으로 읽는다. 여러 줄 목록이 필요하면 PyYAML
    m = re.search(r"^cause_keywords:\s*(\[.*\])\s*$", (SCEN / name / "truth.yaml").read_text(), re.M)
    if not m:
        sys.exit(f"{name}/truth.yaml 에 cause_keywords 가 없습니다 (고르는 장애에는 필수)")
    return [w.lower() for w in json.loads(m.group(1))]


def pii_values(name):
    vals = re.findall(r"^\s*value:\s*'?(.*?)'?\s*$", (SCEN / name / "injected_pii.yaml").read_text(), re.M)
    return [v for v in vals if len(v) >= 6]


SEVERITY_WORDS = {"TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "FATAL", "CRITICAL", "INFORMATION"}
TIME_VALUE = re.compile(r"^\d{10,19}$|^\d{4}-\d\d-\d\dT")
HEXISH = re.compile(r"[0-9a-fA-F-]{8,}")


def detect_context(rows):
    """값 모양으로 문맥 칸(시각·심각도·서비스)을 찾는다. 칸 이름은 보지 않는다.
    ponytail: 서비스 = '거의 모든 줄에 있고 값 2~50종, 숫자·ID 모양 아님' 중 값 종류가 가장 많은 칸.
    파드·호스트 이름 칸이 있으면 그쪽을 고를 수 있다 — 그래서 자동 선택이 아니라 확인·경고용"""
    n = len(rows)
    vals = defaultdict(list)
    for r in rows:
        for f, v in r.items():
            if v:
                vals[f].append(v)
    full = {f: v for f, v in vals.items() if len(v) >= 0.9 * n}   # OTel 정상 로그도 시각·심각도가 빠진 줄이 8% 있다
    found = {}
    times = [f for f, v in full.items() if sum(bool(TIME_VALUE.match(x)) for x in v) >= 0.95 * len(v) and len(set(v)) >= 20]
    sevs = [f for f, v in full.items() if len(set(v)) <= 10 and sum(x.upper() in SEVERITY_WORDS for x in v) >= 0.9 * len(v)]
    svcs = [f for f, v in full.items() if 2 <= len(set(v)) <= 50 and f not in sevs
            and not any(x.isdigit() or HEXISH.search(x) for x in set(v))]
    if times:
        found["시각"] = times[0]
    if sevs:
        found["심각도"] = sevs[0]
    if svcs:
        found["서비스"] = max(svcs, key=lambda f: (len(set(full[f])), f))
    return found


def ids(arg):
    return [x for x in arg.split(",") if x]


def main():
    global SCEN
    ap = argparse.ArgumentParser(description="필드 선택 알고리즘 1단계 (AI 없음)")
    ap.add_argument("--dir", default=str(SCEN), help="장애 폴더 루트")
    ap.add_argument("--normal", default="S0_normal", help="정상 로그 ID")
    ap.add_argument("--select", default="", help="고르는 장애 ID (쉼표). 비우면 cause_keywords 가 있는 장애 전부")
    ap.add_argument("--validate", default="", help="검증 장애 ID (쉼표). 고를 때 쓰지 않는다")
    ap.add_argument("--context", default=OTEL_CONTEXT, help="항상 담는 문맥 칸 (서비스·시각·심각도). auto 면 값 모양으로 찾는다")
    ap.add_argument("--hand", default="", help="비교할 손으로 고른 목록 (쉼표). otel 이면 OTel 손 목록")
    ap.add_argument("--json", help="2·3단계용 후보 파일")
    a = ap.parse_args()
    SCEN = Path(a.dir).resolve()
    CONTEXT = ids(a.context)
    HAND = ids(OTEL_HAND if a.hand == "otel" else a.hand)
    validate = ids(a.validate)
    scen = ids(a.select) or sorted(p.name for p in SCEN.iterdir() if p.name not in [a.normal, *validate]
                                   and (p / "truth.yaml").is_file() and "cause_keywords:" in (p / "truth.yaml").read_text())
    if set(scen) & set(validate):
        sys.exit(f"고르는 장애와 검증 장애가 겹칩니다: {sorted(set(scen) & set(validate))}")
    normal = load(a.normal)
    # 문맥 칸 확인: 값 모양으로 찾은 칸과 --context 가 다르면 경고. --context auto 면 찾은 칸을 쓴다
    found = detect_context(normal)
    if a.context == "auto":
        CONTEXT = list(found.values())
    print("문맥 칸 자동 감지:", ", ".join(f"{k}={v}" for k, v in found.items()) or "없음")
    for role in ("시각", "심각도", "서비스"):
        if role not in found:
            print(f"  ⚠ {role} 칸을 값 모양으로 찾지 못함 — --context 를 사람이 확인")
        elif found[role] not in CONTEXT:
            print(f"  ⚠ {role} 로 보이는 칸 {found[role]} 이 --context 에 없음")
    normal_vals = defaultdict(str)
    for r in normal:
        for f, v in r.items():
            normal_vals[f] += " " + v.lower()

    clue, leak, leak_imp, seen = defaultdict(int), defaultdict(int), defaultdict(int), defaultdict(int)
    clue_imp, novel = defaultdict(int), defaultdict(int)
    normal_set = defaultdict(set)
    for r in normal:
        for f, v in r.items():
            normal_set[f].add(v)
    link_vals = defaultdict(lambda: defaultdict(set))   # 칸 → 값 → 서비스들
    total = 0
    for s in scen:
        recs = load(s)
        total += len(recs)
        words = cause_keywords(s)
        pii = pii_values(s)
        text, text_imp = defaultdict(str), defaultdict(str)
        for r in recs:
            imp = important(r)
            svc = r.get("resource.service.name", "")
            for f, v in r.items():
                if not v:
                    continue
                seen[f] += 1
                text[f] += " " + v.lower()
                if imp:
                    text_imp[f] += " " + v.lower()
                n = sum(v.count(p) for p in pii)
                leak[f] += n
                if imp:
                    leak_imp[f] += n
                if len(v) >= 8:
                    link_vals[f][v].add(svc)
        for f in text:
            if any(w in text[f] and w not in normal_vals[f] for w in words):
                clue[f] += 1
            if any(w in text_imp[f] and w not in normal_vals[f] for w in words):
                clue_imp[f] += 1
        for f in text:
            ns = normal_set.get(f, set())
            if 0 < len(ns) <= 20 and any(r.get(f) and r.get(f) not in ns for r in recs):
                novel[f] += 1

    rows = []
    for f in sorted(seen):
        vals = link_vals[f]
        link = (sum(1 for svcs in vals.values() if len(svcs) >= 2) / len(vals)) if vals else 0.0
        many = len(vals) >= 20
        if f in CONTEXT:
            verdict = "담기(문맥)"
        elif link >= 0.3 and many:
            verdict = "토큰화"
        elif clue[f] + novel[f] >= 1 and leak[f] == 0:
            verdict = "담기"
        elif clue[f] >= 1 and clue_imp[f] >= 1 and leak_imp[f] == 0:
            verdict = "조건부"
        else:
            verdict = "버림"
        rows.append((f, clue[f], clue_imp[f], novel[f], leak[f], leak_imp[f], round(link, 2), seen[f], verdict))

    keep = [r for r in rows if r[-1] != "버림"]
    print(f"장애 {len(scen)}개 · 기록 {total}건 · 후보 칸 {len(rows)}개\n")
    print(f"{'칸':52} 단서 단서(중요) 새값 누출 누출(중요) 연결  기록수  판정")
    for r in sorted(rows, key=lambda r: (r[-1] == '버림', -r[1], r[4])):
        if r[-1] != "버림" or r[1] > 0 or r[4] > 20 or r[0] in HAND:
            print(f"{r[0]:52} {r[1]:>3} {r[2]:>7} {r[3]:>4} {r[4]:>5} {r[5]:>8} {r[6]:>5} {r[7]:>6}  {r[8]}")
    picked = {r[0] for r in keep}
    if HAND:
        print(f"\n손으로 고른 {len(HAND)}칸과 비교")
        print("  둘 다      :", sorted(picked & set(HAND)))
        print("  손으로만   :", sorted(set(HAND) - picked))
        print("  알고리즘만 :", sorted(picked - set(HAND)))

    # AI 확인 단계(FieldSearchRunner)에 넘길 후보: 점수 높은 순 (단서 → 새 값 → 누출 적은 순)
    if a.json:
        out = Path(a.json)
        cand = [{"field": r[0], "verdict": r[8], "clue": r[1], "novel": r[3], "leak": r[4], "link": r[6]}
                for r in sorted(keep, key=lambda r: (-r[1], -r[3], r[4]))]
        out.write_text(json.dumps({"dir": str(SCEN), "select": scen, "validate": validate, "context": CONTEXT,
                                   "hand": HAND, "candidates": cand}, ensure_ascii=False, indent=1))
        print("\n후보", len(cand), "개 →", out)


if __name__ == "__main__":
    sys.exit(main())
