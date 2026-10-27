"""필드 선택 알고리즘 (AI 호출 없음).

사람이 파일럿에서 칸을 넣고 빼며 고르던 과정을 규칙으로 옮긴 것이다.
입력 : scenarios/<S1..S6>/raw.jsonl (장애 로그), injected_pii.yaml (민감값), scoring/scenarios.json (정답 원인 키워드),
       scenarios/S0_normal/raw.jsonl (정상 로그, 기준선)
출력 : 칸마다 단서·누출·연결 점수와 판정(담기 / 조건부 / 토큰화 / 버림), 손으로 고른 10칸과의 비교

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
  4) 단서 ≥ 1 이고 누출 > 0 이지만 중요 기록만 봐도 단서가 남고 누출은 절반 이하 → 조건부 (WARN 이상·5xx 에서만)
  5) 그 밖 → 버림
"""
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCEN = ROOT / "scenarios"
CONTEXT = ["resource.service.name", "logRecord.timeUnixNano", "logRecord.severityText"]
HAND = CONTEXT + ["logRecord.body", "logRecord.traceId", "logRecord.spanId", "logRecord.parentSpanId",
                  "logRecord.attributes.exception.message", "logRecord.attributes.exception.type",
                  "logRecord.attributes.http.response.status_code"]


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


def pii_values(name):
    vals = re.findall(r"^\s*value:\s*'?(.*?)'?\s*$", (SCEN / name / "injected_pii.yaml").read_text(), re.M)
    return [v for v in vals if len(v) >= 6]


def main():
    keys = json.loads((ROOT / "scoring" / "scenarios.json").read_text())
    by_prefix = {k.split("_")[0]: v for k, v in keys.items() if not k.startswith("_")}
    scen = sorted(p.name for p in SCEN.iterdir() if re.match(r"S[1-6]_", p.name))
    normal = load("S0_normal")
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
        kw = by_prefix[s.split("_")[0]]["cause_hit_keywords"]
        words = [w.lower() for w in kw["ko"] + kw["en"]]
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
        elif clue[f] >= 1 and clue_imp[f] >= 1 and leak_imp[f] <= leak[f] / 2:
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
    print("\n손으로 고른 10칸과 비교")
    print("  둘 다      :", sorted(picked & set(HAND)))
    print("  손으로만   :", sorted(set(HAND) - picked))
    print("  알고리즘만 :", sorted(picked - set(HAND)))

    # AI 확인 단계(FieldSearchRunner)에 넘길 후보: 점수 높은 순 (단서 → 새 값 → 누출 적은 순)
    if "--json" in sys.argv:
        out = Path(sys.argv[sys.argv.index("--json") + 1])
        cand = [{"field": r[0], "verdict": r[8], "clue": r[1], "novel": r[3], "leak": r[4], "link": r[6]}
                for r in sorted(keep, key=lambda r: (-r[1], -r[3], r[4]))]
        out.write_text(json.dumps({"context": CONTEXT, "hand": HAND, "candidates": cand}, ensure_ascii=False, indent=1))
        print("\n후보", len(cand), "개 →", out)


if __name__ == "__main__":
    sys.exit(main())
