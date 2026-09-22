"""여과시간 2차발표(선행기술조사) v4 — 4장, 흑백.

v3 대비: 기여 주장으로 슬라이드 4 재작성, 슬라이드 3에 담기의 대가 추가, 전면 흑백.
색으로 하던 구분은 명도와 패턴으로 옮긴다.
"""
from pptx import Presentation
from pptx.util import Inches as I, Pt
from pptx.dml.color import RGBColor as C
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.dml import MSO_PATTERN
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR

BLACK = C(0x0A, 0x0A, 0x0A)
INK   = C(0x1C, 0x1C, 0x1C)
BODY  = C(0x33, 0x33, 0x33)
MUTE  = C(0x70, 0x70, 0x70)
PALE  = C(0x9B, 0x9B, 0x9B)
MID   = C(0xA8, 0xA8, 0xA8)
LIGHT = C(0xE2, 0xE2, 0xE2)
CARD  = C(0xF2, 0xF2, 0xF2)
PAPER = C(0xFF, 0xFF, 0xFF)
WHITE = C(0xFF, 0xFF, 0xFF)
F = "Arial Unicode MS"
W, H = 13.333, 7.5

prs = Presentation()
prs.slide_width, prs.slide_height = I(W), I(H)
BLANK = prs.slide_layouts[6]


def slide(dark=False):
    s = prs.slides.add_slide(BLANK)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BLACK if dark else PAPER
    return s


def box(s, x, y, w, h, fill=None, line=None, lw=1.0, shape=MSO_SHAPE.ROUNDED_RECTANGLE,
        hatch=False):
    sh = s.shapes.add_shape(shape, I(x), I(y), I(w), I(h))
    sh.shadow.inherit = False
    if hatch:
        sh.fill.patterned()
        sh.fill.pattern = MSO_PATTERN.DARK_UPWARD_DIAGONAL
        sh.fill.fore_color.rgb = BLACK
        sh.fill.back_color.rgb = WHITE
    elif fill is None:
        sh.fill.background()
    else:
        sh.fill.solid(); sh.fill.fore_color.rgb = fill
    if line is None:
        sh.line.fill.background()
    else:
        sh.line.color.rgb = line; sh.line.width = Pt(lw)
    sh.text_frame.text = ""
    return sh


def text(s, x, y, w, h, runs, size=14, color=BODY, bold=False, align=PP_ALIGN.LEFT,
         anchor=MSO_ANCHOR.TOP):
    tb = s.shapes.add_textbox(I(x), I(y), I(w), I(h))
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    tf.vertical_anchor = anchor
    lines = runs if isinstance(runs, list) else [runs]
    for i, ln in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        if isinstance(ln, str):
            ln = [(ln, {})]
        for t, opt in ln:
            r = p.add_run(); r.text = t
            r.font.name = F
            r.font.size = Pt(opt.get("size", size))
            r.font.bold = opt.get("bold", bold)
            r.font.color.rgb = opt.get("color", color)
    return tb


def header(s, n, title, sub=None):
    text(s, 0.7, 0.52, 10.6, 0.6, title, size=30, bold=True, color=BLACK,
         anchor=MSO_ANCHOR.MIDDLE)
    if sub:
        text(s, 0.7, 1.16, 11.9, 0.34, sub, size=13, color=MUTE, anchor=MSO_ANCHOR.MIDDLE)
    text(s, 11.6, 0.52, 1.03, 0.6, f"{n:02d}", size=13, bold=True, color=PALE,
         align=PP_ALIGN.RIGHT, anchor=MSO_ANCHOR.MIDDLE)


def note(s, t):
    s.notes_slide.notes_text_frame.text = t


# ───────────────────────── 1. 표지 ─────────────────────────
s = slide(dark=True)
text(s, 0.95, 2.05, 11.4, 0.95, "여과시간", size=46, bold=True, color=WHITE)
text(s, 0.95, 3.08, 11.4, 0.5, "기존 기술을 조사한 뒤, 무엇을 더 해야 하는가",
     size=19, color=PALE)

box(s, 0.95, 4.0, 5.3, 0.62, fill=None, line=MUTE, lw=1.0)
text(s, 1.2, 4.0, 5.05, 0.62, [[("기존   ", {"color": PALE, "size": 13}),
                                ("위험한 걸 빼고 보낸다", {"color": WHITE, "size": 15, "bold": True})]],
     anchor=MSO_ANCHOR.MIDDLE)
text(s, 6.3, 4.0, 0.52, 0.62, "→", size=19, color=WHITE,
     align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
box(s, 6.85, 4.0, 5.48, 0.62, fill=WHITE)
text(s, 7.1, 4.0, 4.98, 0.62, [[("여과시간   ", {"color": MUTE, "size": 13}),
                                ("필요한 것만 담아 보낸다", {"color": BLACK, "size": 15, "bold": True})]],
     anchor=MSO_ANCHOR.MIDDLE)

text(s, 0.95, 5.6, 11.4, 0.36, "선행기술 비교   ·   적용 범위   ·   남은 질문",
     size=13, color=MUTE)
text(s, 0.95, 6.35, 11.4, 0.36, "조사 결과와 검증 계획을 공유합니다.",
     size=12, color=PALE)
note(s, "약 30초. 1차 발표 후 교수님께서 기존 기술과 논문을 조사하고, 각 기술의 장단점과 우리 위치를 "
        "설명하라고 피드백을 주셨습니다. 조사 결과를 먼저 보여드리고, 앞으로의 계획을 "
        "말씀드리겠습니다. 오늘 핵심은 한 줄입니다 — 기존은 빼기, 저희는 담기입니다.")

# ───────────────────── 2. 기존 기술 ─────────────────────
s = slide()
header(s, 2, "기존 기술 — 담을 것을 정해주는 곳은 없다", "각 기술이 하는 일과, 이 사용사례에서 남는 한계")

cols = [0.7, 2.68, 6.35, 10.35]
wids = [1.9, 3.6, 3.9, 2.28]
for cx, cw, lab, al in ((cols[0], wids[0], "기술", PP_ALIGN.LEFT),
                        (cols[1], wids[1], "하는 일", PP_ALIGN.LEFT),
                        (cols[2], wids[2], "이 사용사례의 한계", PP_ALIGN.LEFT),
                        (cols[3], wids[3], "방식", PP_ALIGN.CENTER)):
    text(s, cx, 1.72, cw, 0.3, lab, size=12, bold=True, color=MUTE, align=al)

rows = [
    ("Presidio", "민감값 탐지·치환", "무엇이 민감한지만 안다", "빼기", ""),
    ("Google SDP", "필드별·조건별 변환", "무엇을 남길지는 호출자 몫 (공식 문서 명시)",
     "담기", "사용자가 직접 정해야 함"),
    ("Kong AI Sanitizer", "AI 요청 전 PII 치환", "업무별 구분 없음", "빼기", ""),
]
for i, (a, b, c, badge, sub_) in enumerate(rows):
    y = 2.12 + i * 0.85
    box(s, 0.7, y, 11.93, 0.72, fill=CARD if i % 2 == 0 else None)
    text(s, cols[0] + 0.18, y, wids[0], 0.72, a, size=13, bold=True, color=BLACK,
         anchor=MSO_ANCHOR.MIDDLE)
    text(s, cols[1], y, wids[1], 0.72, b, size=13, color=BODY, anchor=MSO_ANCHOR.MIDDLE)
    text(s, cols[2], y, wids[2], 0.72, c, size=13, color=BODY, anchor=MSO_ANCHOR.MIDDLE)
    by = y + (0.13 if sub_ else 0.21)
    bx = cols[3] + (wids[3] - 0.98) / 2
    box(s, bx, by, 0.98, 0.3, fill=BLACK if badge == "담기" else PAPER,
        line=BLACK, lw=1.0)
    text(s, bx, by, 0.98, 0.3, badge, size=11, bold=True,
         color=WHITE if badge == "담기" else BLACK,
         align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    if sub_:
        text(s, cols[3], by + 0.32, wids[3], 0.22, sub_, size=8.5, color=MUTE,
             align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)

box(s, 0.7, 4.92, 11.93, 0.9, fill=BLACK)
text(s, 1.05, 4.92, 11.2, 0.9,
     [[("담기는 이미 가능하다. 다만 ", {"color": PALE, "size": 17}),
       ("이 업무에 무엇을 담아야 하는지는 아무도 정해주지 않는다.", {"color": WHITE, "size": 17, "bold": True})]],
     anchor=MSO_ANCHOR.MIDDLE)

text(s, 0.7, 6.1, 11.93, 0.34,
     "무엇을 남길지 고르는 문제는 이미 연구 주제다 — PII-Bench (Shen et al., ACL 2026), Zhou et al. (2025)",
     size=11.5, color=MUTE)
text(s, 0.7, 6.48, 11.93, 0.3,
     "출처: Presidio README · Google Cloud SDP 문서 · Kong 문서 · ACL Anthology · arXiv",
     size=10, color=PALE)
note(s, "약 60초. 조사한 기존 기술입니다. Presidio와 Kong은 민감정보를 빼고 AI에 보내는 방식입니다. "
        "탐지가 놓치면 그대로 나갑니다. Google SDP는 원하는 정보만 담을 수도 있습니다. 다만 무엇을 담을지는 "
        "사용자가 직접 정해야 한다고 공식 문서가 명시합니다. 저희는 여기서, 원인 분석과 보고서 초안 각각에 "
        "어떤 정보를 담아야 하는지를 정하려고 합니다. 참고로 목적별로 정보를 줄이는 문제는 이미 연구 "
        "주제입니다. 저희가 새 개념을 주장하지는 않습니다.")

# ───────────────────── 3. 담기로 바꾼다 ─────────────────────
s = slide()
header(s, 3, "여과시간 — 담기를 기본으로 놓는다", "같은 장애 로그 12개 필드로 본 차이 (예시)")

# 원문 12필드 고정 배치: 업무필요 2,3,8,9 / 민감값 5,10,11 / 업무무관 0,1,4,6,7
CW, GAP, X0 = 0.6, 0.1, 2.62


def cell(x, y, kind):
    if kind == "k":                                   # 업무에 필요
        box(s, x, y, CW, 0.42, fill=BLACK)
    elif kind == "a":                                 # 못 찾은 민감값
        box(s, x, y, CW, 0.42, line=BLACK, lw=1.0, hatch=True)
    elif kind == "s":                                 # 업무와 무관
        box(s, x, y, CW, 0.42, fill=MID)
    elif kind == "g":                                 # 지워짐
        box(s, x, y, CW, 0.42, fill=LIGHT)
    else:                                             # 안 담음
        box(s, x, y, CW, 0.42, fill=PAPER, line=MID, lw=0.75)


def cells(y, spec):
    assert len(spec) == 12, f"칸 수 {len(spec)}"
    for i, kind in enumerate(spec):
        cell(X0 + i * (CW + GAP), y, kind)


y1 = 1.82
box(s, 0.7, y1 - 0.08, 1.8, 0.58, fill=CARD)
text(s, 0.7, y1 - 0.08, 1.8, 0.58, "기존 · 빼기", size=13, bold=True, color=BLACK,
     align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
cells(y1, list("sskksgsskkga"))
text(s, 11.05, y1 - 0.08, 2.0, 0.58,
     [[("10칸 나감", {"color": BLACK, "size": 15, "bold": True})],
      [("민감값 1칸 포함", {"color": MUTE, "size": 10.5})]],
     anchor=MSO_ANCHOR.MIDDLE)
text(s, 0.7, y1 + 0.56, 10.2, 0.3,
     "업무와 무관한 필드도 함께 나가고, 탐지가 놓친 민감값도 그대로 포함된다",
     size=11.5, color=MUTE)

y2 = 2.92
box(s, 0.7, y2 - 0.08, 1.8, 0.58, fill=BLACK)
text(s, 0.7, y2 - 0.08, 1.8, 0.58, "여과시간 · 담기", size=13, bold=True, color=WHITE,
     align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
cells(y2, list("ookkooookkoo"))
text(s, 11.05, y2 - 0.08, 2.0, 0.58,
     [[("4칸 나감", {"color": BLACK, "size": 15, "bold": True})],
      [("민감값 0칸", {"color": MUTE, "size": 10.5})]],
     anchor=MSO_ANCHOR.MIDDLE)
text(s, 0.7, y2 + 0.56, 10.2, 0.3,
     "담기로 고른 것만 나간다. 안 담은 건 탐지와 무관하게 나가지 않는다",
     size=11.5, color=MUTE)

lg = [("k", "업무에 필요"), ("a", "못 찾은 민감값"), ("s", "업무와 무관"),
      ("g", "지워짐"), ("o", "안 담음")]
for i, (kind, lab) in enumerate(lg):
    x = 2.62 + i * 1.86
    if kind == "k":
        box(s, x, 3.86, 0.2, 0.2, fill=BLACK, shape=MSO_SHAPE.RECTANGLE)
    elif kind == "a":
        box(s, x, 3.86, 0.2, 0.2, line=BLACK, lw=0.75, shape=MSO_SHAPE.RECTANGLE, hatch=True)
    elif kind == "s":
        box(s, x, 3.86, 0.2, 0.2, fill=MID, shape=MSO_SHAPE.RECTANGLE)
    elif kind == "g":
        box(s, x, 3.86, 0.2, 0.2, fill=LIGHT, shape=MSO_SHAPE.RECTANGLE)
    else:
        box(s, x, 3.86, 0.2, 0.2, fill=PAPER, line=MID, lw=0.75, shape=MSO_SHAPE.RECTANGLE)
    text(s, x + 0.3, 3.82, 1.5, 0.28, lab, size=10.5, color=MUTE, anchor=MSO_ANCHOR.MIDDLE)

box(s, 0.7, 4.38, 6.05, 2.5, fill=CARD)
text(s, 1.0, 4.58, 5.45, 0.3, "적용 범위 — 보내기 전 네 단계", size=13, bold=True, color=BLACK)
for i, t in enumerate(["업무 목적을 선택한다", "목록에 있는 정보만 골라 담는다",
                       "제외·변환한 내용을 검토한다", "승인된 목적지로만 전달한다"]):
    y = 5.02 + i * 0.4
    box(s, 1.0, y + 0.03, 0.26, 0.26, fill=BLACK, shape=MSO_SHAPE.OVAL)
    text(s, 1.0, y + 0.03, 0.26, 0.26, str(i + 1), size=9.5, bold=True, color=WHITE,
         align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    text(s, 1.42, y, 5.1, 0.32, t, size=12.5, color=BODY, anchor=MSO_ANCHOR.MIDDLE)

box(s, 7.05, 4.38, 5.58, 2.5, fill=None, line=BLACK, lw=1.25)
text(s, 7.35, 4.58, 5.0, 0.3, "실패했을 때 어디로 넘어지나", size=13, bold=True, color=BLACK)
for i, (a, b, c, filled) in enumerate([("빼기", "탐지가 실패하면", "유출", True),
                                       ("담기", "정의가 실패하면", "답변 품질 저하", False)]):
    y = 5.02 + i * 0.6
    if filled:
        box(s, 7.35, y + 0.08, 0.86, 0.3, fill=BLACK)
        text(s, 7.35, y + 0.08, 0.86, 0.3, a, size=11, bold=True, color=WHITE,
             align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    else:
        box(s, 7.35, y + 0.08, 0.86, 0.3, fill=PAPER, line=BLACK, lw=1.0)
        text(s, 7.35, y + 0.08, 0.86, 0.3, a, size=11, bold=True, color=BLACK,
             align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    text(s, 8.38, y, 2.3, 0.46, b, size=12, color=BODY, anchor=MSO_ANCHOR.MIDDLE)
    text(s, 10.72, y, 1.9, 0.46, c, size=12.5, bold=True, color=BLACK,
         anchor=MSO_ANCHOR.MIDDLE)

text(s, 7.35, 6.16, 5.0, 0.58,
     [[("담기의 대가 — ", {"color": BLACK, "size": 11.5, "bold": True}),
       ("업무마다 목록을 만들고 관리해야 한다.", {"color": BODY, "size": 11.5})],
      [("장애마다 보는 정보가 비슷한지가 관건이다.", {"color": BODY, "size": 11.5})]],
     anchor=MSO_ANCHOR.TOP)

note(s, "약 90초. 왼쪽 그림이 오늘의 핵심입니다. 같은 로그 12개 필드를 예로 들면, 빼기 방식은 탐지한 것을 "
        "지우고 나머지를 보냅니다. 업무와 무관한 필드도 함께 나가고, 못 찾은 민감값도 따라갑니다. 담기 "
        "방식은 이번 업무에 필요한 것만 골라 담고, 사람이 그 내용을 확인한 뒤 보냅니다. 안 담은 것은 탐지 "
        "성공 여부와 무관하게 나가지 않습니다. 오른쪽이 중요합니다. 빼기는 실패하면 유출이고, 담기는 "
        "실패해도 답변이 부실해질 뿐입니다. 실패가 안전한 쪽으로 넘어집니다. 다만 담기에는 대가가 있습니다. "
        "업무마다 목록을 만들고 관리해야 합니다. 아무도 담기를 기본값으로 쓰지 않는 이유가 이 비용일 수 "
        "있습니다. 저희 베팅은 장애마다 보는 정보가 비슷하다는 것이고, 그래서 목록 관리 비용이 감당된다는 "
        "것입니다. 칸 수는 설명용 예시이며 측정값이 아닙니다.")

# ───────────────────── 4. 기여와 검증 ─────────────────────
s = slide(dark=True)
text(s, 0.7, 0.52, 10.6, 0.6, "앞으로의 계획", size=30, bold=True, color=WHITE,
     anchor=MSO_ANCHOR.MIDDLE)
text(s, 11.6, 0.52, 1.03, 0.6, "04", size=13, bold=True, color=MUTE,
     align=PP_ALIGN.RIGHT, anchor=MSO_ANCHOR.MIDDLE)

text(s, 0.7, 1.5, 11.93, 1.4,
     [[("업무마다 어떤 정보를 보낼지 목록을 정하고,", {"color": WHITE, "size": 24, "bold": True})],
      [("그 목록대로만 보내도 원인을 제대로 찾는지 확인해볼 예정입니다.",
        {"color": WHITE, "size": 24, "bold": True})]],
     anchor=MSO_ANCHOR.TOP)
text(s, 0.7, 2.98, 11.93, 0.34,
     "목록을 정하는 곳은 많습니다. 그런데 그 목록으로 충분한지 재본 곳은 찾지 못했습니다.",
     size=13, color=PALE)

text(s, 0.7, 3.62, 11.93, 0.3, "어떻게 확인하는가", size=13, bold=True, color=PALE)
steps = [("같은 장애 로그를 둘로 나눈다", "다 보낸 것  /  목록대로만 보낸 것"),
         ("둘 다 같은 AI에 넣는다", "모델·질문·설정 모두 동일"),
         ("원인을 맞게 찾는지 비교한다", "목록대로만 보내도 찾으면 줄여도 된다는 뜻")]
for i, (a, b) in enumerate(steps):
    y = 4.0 + i * 0.62
    box(s, 0.7, y, 11.93, 0.52, fill=INK)
    box(s, 0.98, y + 0.11, 0.3, 0.3, fill=WHITE, shape=MSO_SHAPE.OVAL)
    text(s, 0.98, y + 0.11, 0.3, 0.3, str(i + 1), size=11, bold=True, color=BLACK,
         align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    text(s, 1.5, y, 5.0, 0.52, a, size=14.5, bold=True, color=WHITE,
         anchor=MSO_ANCHOR.MIDDLE)
    text(s, 6.6, y, 5.8, 0.52, b, size=12, color=MUTE, anchor=MSO_ANCHOR.MIDDLE)

box(s, 0.7, 6.06, 11.93, 0.74, fill=None, line=WHITE, lw=1.25)
text(s, 1.05, 6.06, 11.2, 0.74,
     [[("판정   ", {"color": PALE, "size": 13, "bold": True}),
       ("목록대로만 보내도 원인이 잡히면 담기가 성립합니다. 안 잡히면 담기를 쓸 이유가 없습니다.",
        {"color": WHITE, "size": 15})]],
     anchor=MSO_ANCHOR.MIDDLE)
note(s, "약 60초. 저희가 할 일은 두 가지입니다. 하나, 원인 분석과 보고서 초안 각각에 어떤 정보를 "
        "보낼지 목록을 정합니다. 둘, 그 목록에 있는 것만 AI에 넣어도 원인을 제대로 찾는지 재봅니다. "
        "방법은 단순합니다. 같은 장애 로그를 둘로 나눠서, 하나는 다 보내고 하나는 목록대로만 보냅니다. "
        "모델도 질문도 설정도 똑같이 두고 원인을 맞게 찾는지 비교합니다. 목록대로만 보내도 원인이 잡히면 "
        "줄여도 된다는 뜻이고, 안 잡히면 담기를 쓸 이유가 없습니다. 그 결과를 그대로 보고하겠습니다. "
        "첫 목록은 공개된 장애 보고서와 저희 합성 로그를 근거로 정하고, 실무자 의견을 들을 기회가 생기면 "
        "그때 보강하겠습니다.")

prs.save("여과시간_2차발표_선행기술조사_5분_v4.pptx")
print("saved v4 / slides:", len(prs.slides._sldIdLst))
