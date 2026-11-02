"""여과시간 주간 발표 (11/4 수, 5분) — 10/29 이후 한 일 + 시연.

지난 발표 덱(여과시간_향후개발방향_v4.pptx)의 마스터·레이아웃·색을 그대로 쓴다.
  ~/.claude/tools/venv/bin/python docs/presentations/build_weekly_1104.py [지난덱.pptx]
수치 출처: experiments/yeogwasigan-spring/results/fieldsearch_20261030_181652 (OTel, gpt-4.1-mini)
          results/fieldsearch_20261030_161934 (TrainTicket, gpt-4.1-mini)
"""
import sys
from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor as C
from pptx.enum.dml import MSO_LINE_DASH_STYLE
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.util import Inches as I, Pt

BASE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "Downloads/여과시간_향후개발방향_v4.pptx"
OUT = Path(__file__).with_name("여과시간_주간_1104.pptx")

INK, GREEN, RED, AMBER, GREY = C(0x1A, 0x1C, 0x1E), C(0x1F, 0x6F, 0x5C), C(0xB2, 0x3A, 0x2E), C(0x9A, 0x64, 0x00), C(0x6B, 0x70, 0x75)
PALEG, PALER, BEIGE, PANEL, WHITE = C(0xDC, 0xE9, 0xE4), C(0xF4, 0xE1, 0xDE), C(0xF5, 0xEB, 0xD6), C(0xE7, 0xE9, 0xEA), C(0xFF, 0xFF, 0xFF)
DARKCARD, LIGHT = C(0x2A, 0x2F, 0x33), C(0xC9, 0xCE, 0xD2)

prs = Presentation(BASE)
# 지난 덱 슬라이드는 지우고 마스터·레이아웃만 쓴다
for sid in list(prs.slides._sldIdLst):
    prs.part.drop_rel(sid.rId)
    prs.slides._sldIdLst.remove(sid)
L_TITLE, L_CONTENT = prs.slide_layouts[1], prs.slide_layouts[2]
page = 0


def new(layout, kicker, title, sub=None):
    global page
    page += 1
    s = prs.slides.add_slide(layout)
    s.placeholders[100].text = kicker
    s.placeholders[101].text = title
    if sub is not None:
        s.placeholders[102].text = sub
    if layout is L_CONTENT:
        text(s, 9.0, 5.22, 0.4, 0.25, str(page), 10, GREY, align=PP_ALIGN.RIGHT)
    return s


def box(s, x, y, w, h, fill=WHITE, line=None, shape=MSO_SHAPE.ROUNDED_RECTANGLE, dash=False):
    sh = s.shapes.add_shape(shape, I(x), I(y), I(w), I(h))
    sh.shadow.inherit = False
    if shape == MSO_SHAPE.ROUNDED_RECTANGLE:
        sh.adjustments[0] = 0.08
    if fill is None:
        sh.fill.background()
    else:
        sh.fill.solid()
        sh.fill.fore_color.rgb = fill
    if line is None:
        sh.line.fill.background()
    else:
        sh.line.color.rgb = line
        sh.line.width = Pt(1.25)
        if dash:
            sh.line.dash_style = MSO_LINE_DASH_STYLE.DASH
    return sh


def text(s, x, y, w, h, lines, size=11.5, color=INK, bold=False, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, space=0):
    """lines: 문자열 하나, 또는 줄 목록. 줄은 문자열이거나 (글자, {size,color,bold}) 조각 목록."""
    tf = s.shapes.add_textbox(I(x), I(y), I(w), I(h)).text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    tf.vertical_anchor = anchor
    for i, ln in enumerate(lines if isinstance(lines, list) else [lines]):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.space_after = Pt(space)
        for t, o in ([(ln, {})] if isinstance(ln, str) else ln):
            r = p.add_run()
            r.text = t
            r.font.size = Pt(o.get("size", size))
            r.font.bold = o.get("bold", bold)
            r.font.color.rgb = o.get("color", color)
    return tf


def arrow(s, x1, y1, x2, y2, color=GREY, dash=False):
    c = s.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, I(x1), I(y1), I(x2), I(y2))
    c.line.color.rgb = color
    c.line.width = Pt(1.5)
    if dash:
        c.line.dash_style = MSO_LINE_DASH_STYLE.DASH
    c.line._get_or_add_ln().append(c.line._ln.makeelement(
        "{http://schemas.openxmlformats.org/drawingml/2006/main}tailEnd", {"type": "triangle"}))
    return c


SCRIPT = []


def note(s, t):
    # 지난 덱의 노트 마스터에 본문 칸이 없어 슬라이드 노트 대신 대본 파일로 남긴다
    SCRIPT.append(f"## {page}. {s.placeholders[101].text}\n\n{t}\n")


def card(s, x, y, w, h, head, body, accent=GREEN, fill=WHITE, head_size=14):
    box(s, x, y, w, h, fill)
    box(s, x, y, 0.06, h, accent, shape=MSO_SHAPE.RECTANGLE)
    text(s, x + 0.22, y + 0.14, w - 0.36, 0.34, head, head_size, accent, True)
    text(s, x + 0.22, y + 0.56, w - 0.36, h - 0.66, body, 11.5, INK, space=4)


# ───────────────────────── 1. 표지 ─────────────────────────
s = new(L_TITLE, "캡스톤 주간 진행 · 11/4", "받는 길을 점검하고, 칸 고르기를 시험했다",
        "지난 발표 이후(10/29~) 한 일과 게이트웨이 시연")
for i, (h, b) in enumerate([("피드백", "AI 답이 들어오는 길 점검"),
                            ("알고리즘", "누출 금지 규칙 · 문맥 칸 자동 감지"),
                            ("결과", "누출 147 → 0, 새 시스템은 한계 확인")]):
    box(s, 0.60 + 2.95 * i, 3.85, 2.75, 1.0, DARKCARD)
    text(s, 0.80 + 2.95 * i, 3.98, 2.4, 0.4, h, 18, WHITE, True)
    text(s, 0.80 + 2.95 * i, 4.42, 2.4, 0.3, b, 11.5, LIGHT)
note(s, "약 20초. 지난 발표 이후 한 일을 피드백 대응, 알고리즘, 결과 순서로 말씀드리고 게이트웨이를 시연하겠습니다.")

# ───────────────────── 2. 받은 피드백: 받는 길 ─────────────────────
s = new(L_CONTENT, "받은 피드백", "AI 답이 들어오는 길도 따져야 한다")
card(s, 0.6, 1.4, 2.7, 3.55, "교수님 질문 (요약)",
     ["결과를 받아올 때 보안 문제는 없는가?",
      [("지금  ", {"bold": True, "color": GREEN}), ("들어오는 연결 없음 · 답은 글자로만 표시, 실행 안 함", {})],
      [("남은 위험  ", {"bold": True, "color": RED}), ("로그에 심은 지시(프롬프트 주입)", {})],
      [("계획  ", {"bold": True, "color": AMBER}), ("응답 검사 — 실행 명령·외부 링크에 경고", {})]],
     AMBER, BEIGE)
box(s, 3.5, 1.4, 5.9, 3.55, PANEL)
text(s, 3.65, 1.48, 3.0, 0.26, "내부망", 10.5, GREY, True)
box(s, 8.15, 1.4, 1.25, 3.55, PALEG)
text(s, 8.25, 1.48, 1.1, 0.26, "외부망", 10.5, GREEN, True)
# 나가는 길 (위)
text(s, 3.65, 1.85, 2.0, 0.26, "나가는 길", 11, GREEN, True)
xs = [3.65, 4.75, 5.85, 6.95, 8.3]
labels = ["담기 필터", "경고·승인", "전송", "출구 프록시", "외부 AI"]
for x, t in zip(xs, labels):
    box(s, x, 2.15, 0.95, 0.6, WHITE if t == "외부 AI" else PALEG)
    text(s, x, 2.15, 0.95, 0.6, t, 11, INK, True, PP_ALIGN.CENTER, MSO_ANCHOR.MIDDLE)
for a, b in zip(xs, xs[1:]):
    arrow(s, a + 0.95, 2.45, b, 2.45, GREEN)
# 받는 길 (아래, 점선)
text(s, 3.65, 3.1, 2.4, 0.26, "받는 길 (이번에 추가)", 11, AMBER, True)
rx = [8.3, 6.95, 5.85, 4.75, 3.65]
rlabels = ["외부 AI", "출구 프록시", "전송", "응답 검사", "운영자 화면"]
for x, t in zip(rx, rlabels):
    plan = t == "응답 검사"
    box(s, x, 3.4, 0.95, 0.6, BEIGE if plan else (WHITE if t in ("외부 AI", "운영자 화면") else PALEG),
        AMBER if plan else None, dash=plan)
    text(s, x, 3.4, 0.95, 0.6, [t, ("계획" if plan else "")], 11, INK, True, PP_ALIGN.CENTER, MSO_ANCHOR.MIDDLE)
for a, b in zip(rx, rx[1:]):
    arrow(s, a, 3.7, b + 0.95, 3.7, AMBER, dash=True)
text(s, 3.65, 4.3, 5.6, 0.5, "답은 게이트웨이가 연 연결의 응답으로만 들어온다. 밖에서 먼저 들어오는 연결은 없다.", 11, GREY)
note(s, "약 50초. [교수님 질문은 녹음 확인 전이라 요약으로 씀] 지난 발표 뒤 교수님께서 결과를 받아올 때 보안 문제를 물으셨습니다. "
        "구조도를 다시 보니 나가는 길만 그려져 있었습니다. 답은 나간 길을 거꾸로 타고 들어옵니다. "
        "지금도 밖에서 먼저 들어오는 연결은 없고, 답은 글자로만 보여 주며 실행하지 않습니다. "
        "남은 위험은 로그에 심은 지시로 AI가 위험한 명령을 권하는 경우라, 받는 길에 응답 검사를 넣겠습니다.")

# ───────────────────── 4. 알고리즘 깔때기 ─────────────────────
s = new(L_CONTENT, "필드 선택 알고리즘", "로그 칸 102개에서, 원인에 꼭 필요한 칸만 남긴다")
steps = [("102", "로그의 모든 칸", "", GREY),
         ("35", "판정 통과", "① 점수 · AI 없음", GREY),
         ("4", "AI로 확인할 후보", "단서가 있는 칸만", GREY),
         ("2", "넣어 보니 더 맞힘", "② 하나씩 넣기 · AI", GREEN),
         ("1", "최종으로 추가", "③ 하나씩 빼기 · AI", GREEN)]
for i, (n, label, how, col) in enumerate(steps):
    x = 0.6 + i * 1.78
    h = 2.3 - i * 0.32
    box(s, x, 1.55 + (2.3 - h) / 2, 1.55, h, PALEG if col == GREEN else PANEL)
    text(s, x, 1.55 + (2.3 - h) / 2, 1.55, h, [[(n, {"size": 30, "bold": True, "color": col})], label],
         11, INK, False, PP_ALIGN.CENTER, MSO_ANCHOR.MIDDLE)
    text(s, x, 3.95, 1.55, 0.3, how, 10.5, GREY, align=PP_ALIGN.CENTER)
    if i:
        arrow(s, x - 0.23, 2.7, x - 0.02, 2.7)
text(s, 0.6, 4.4, 8.8, 0.34, [[("최종 목록  ", {"bold": True, "color": GREEN}),
                               ("서비스 · 시각 · 심각도 + 요청 ID 2개(가짜 번호로 바꿈) + 오류 스택", {})]], 12)
text(s, 0.6, 4.8, 8.8, 0.3, "OTel 데모 장애 6개로 고름 · 분석·판정 gpt-4.1-mini · 10/30 실행", 10, GREY)
note(s, "약 40초. 칸 102개를 AI 없이 점수로 35개, 단서가 있는 4개로 줄이고, AI로 하나씩 넣어 보고 빼 봐서 최종 1칸을 더했습니다. "
        "AI를 비싼 단계에만 쓰는 깔때기입니다.")

# ───────────────────── 5. 이번 주 바꾼 규칙 ─────────────────────
s = new(L_CONTENT, "이번 주 바꾼 것", "새는 칸은 점수가 올라도 넣지 않는다")
rules = [("누출 금지", "전: 점수만 보고 넣음", "후: 민감값이 늘면 넣지 않음"),
         ("조건부 기준", "전: 오류 줄 누출이 절반 이하", "후: 오류 줄 누출 0"),
         ("문맥 칸 자동 감지", "전: 사람이 칸 이름 지정", "후: 값 모양으로 찾음 · 두 시스템 모두 일치"),
         ("비교 기준 추가", "전: 손으로 고른 목록과만", "후: '다 보냄'·'문맥만'과도 비교")]
for i, (h, before, after) in enumerate(rules):
    x, y = 0.6 + (i % 2) * 4.45, 1.4 + (i // 2) * 1.5
    box(s, x, y, 4.3, 1.35)
    text(s, x + 0.22, y + 0.14, 3.9, 0.32, h, 14, GREEN, True)
    text(s, x + 0.22, y + 0.55, 3.9, 0.7, [[(before, {"color": GREY})], [(after, {"bold": True})]], 11.5, space=3)
box(s, 0.6, 4.5, 8.75, 0.5, BEIGE)
text(s, 0.82, 4.5, 8.4, 0.5, [[("비용  ", {"bold": True, "color": AMBER}),
                               ("로컬 모델(Ollama gemma3 12B): 8시간 넘게 걸리고 판정이 흔들림  →  gpt-4.1-mini: 한 번에 약 $0.5", {})]],
     11.5, anchor=MSO_ANCHOR.MIDDLE)
note(s, "약 40초. 넷을 바꿨습니다. 점수가 올라도 민감값이 늘면 넣지 않고, 문맥 칸은 사람이 아니라 값 모양으로 찾습니다. "
        "비용은 로컬 모델을 시험했지만 느리고 판정이 흔들려, gpt-4.1-mini로 한 번 약 0.5달러로 줄였습니다.")

# ───────────────────── 6. 결과 ① OTel ─────────────────────
s = new(L_CONTENT, "결과 ① OTel 데모 · 처음 보는 장애 2개", "다 보낸 것만큼 맞히고, 누출은 147 → 0")
rows = [("다 보냄 (그대로)", 4, 147, RED), ("손으로 고른 목록", 4, 1, GREY), ("알고리즘 목록", 4, 0, GREEN)]
text(s, 3.0, 1.45, 1.2, 0.3, "원인 점수 (4점 만점)", 10, GREY)
text(s, 4.7, 1.45, 3.0, 0.3, "섞여 나간 민감값 (개)", 10, GREY)
for i, (name, score, leak, col) in enumerate(rows):
    y = 1.85 + i * 0.75
    text(s, 0.6, y, 2.3, 0.55, name, 13, INK, col == GREEN, anchor=MSO_ANCHOR.MIDDLE)
    text(s, 3.0, y, 1.2, 0.55, f"{score} / 4", 16, INK, True, anchor=MSO_ANCHOR.MIDDLE)
    w = max(0.05, 3.6 * leak / 147)
    box(s, 4.7, y + 0.1, w, 0.35, col, shape=MSO_SHAPE.RECTANGLE)
    text(s, 4.8 + w, y, 1.0, 0.55, str(leak), 16, col, True, anchor=MSO_ANCHOR.MIDDLE)
card(s, 0.6, 4.1, 8.8, 0.95, "아직 증명 못 한 것",
     "'문맥 칸만' 보내도 4/4 — 검증 장애가 쉬워 칸 선택의 효과를 못 가린다. 같은 입력에도 판정이 ±2점 흔들린다.",
     AMBER, BEIGE, 12)
note(s, "약 40초. 고를 때 안 쓴 장애 2개에서 다 보낸 것과 똑같이 4점을 맞히면서 섞여 나간 민감값은 147개에서 0개가 됐습니다. "
        "다만 서비스와 시각만 보내도 4점이라 이번 검증은 너무 쉬웠고, 판정도 흔들립니다. 다음 주에 더 어려운 장애로 다시 봅니다.")

# ───────────────────── 7. 결과 ② TrainTicket ─────────────────────
s = new(L_CONTENT, "결과 ② 새 시스템 · TrainTicket (공개 데이터 RCAEval)", "로그에 원인이 없으면, 칸을 잘 골라도 못 맞힌다")
box(s, 0.6, 1.4, 3.6, 2.6, PALER)
text(s, 0.6, 1.55, 3.6, 2.3, [[("0 / 8", {"size": 40, "bold": True, "color": RED})],
                              "장애 8개 중, 원인 서비스가",
                              "경고·오류 로그를 남긴 장애"], 12, INK, False, PP_ALIGN.CENTER, MSO_ANCHOR.MIDDLE)
card(s, 4.4, 1.4, 5.0, 1.2, "그래서",
     ["다 보내도 0 / 4점. AI는 오류가 찍힌 다른 서비스를 원인으로 지목했다."], RED)
card(s, 4.4, 2.8, 5.0, 1.2, "배운 것",
     ["알고리즘에 넣기 전에 '원인 서비스의 오류 로그가 있는가'를 먼저 확인한다."], GREEN)
text(s, 0.6, 4.25, 8.8, 0.6, ["• 같은 형식(장애 폴더)만 맞추면 명령 하나로 새 시스템에 돌아간다 — 형식 일반화는 확인",
                              "• 장애 주입이 서비스를 재시작해 생긴 기동 로그는 지웠다 (실험이 만든 흔적)"], 11, GREY, space=3)
note(s, "약 30초. 다른 시스템에도 그대로 돌려 봤습니다. 결과는 실패였는데, 장애 8개 모두 원인 서비스가 오류 로그를 하나도 남기지 않았습니다. "
        "그래서 다 보내도 못 맞혔습니다. 로그에 원인이 있는지 먼저 확인하는 단계를 넣겠습니다.")

# ───────────────────── 8. 시연 ─────────────────────
s = new(L_CONTENT, "시연", "알고리즘이 고른 목록으로, 실제 장애를 분석한다")
demo = [("①", "목록 제안 받기", "알고리즘 실행 · 칸 102개 → 6개", "목록 제안"),
        ("②", "보안팀 승인", "승인하면 게이트웨이 목록에 등록", "목록 제안"),
        ("③", "그 목록으로 요청", "광고 장애 로그 · 칸 107 → 6, 경고 0", "게이트웨이"),
        ("④", "다른 사람이 승인", "요청자 ≠ 승인자, 승인해야 전송", "게이트웨이"),
        ("⑤", "AI 답", "글자로만 표시, 실행하지 않음", "게이트웨이")]
for i, (n, h, b, where) in enumerate(demo):
    x = 0.6 + i * 1.78
    box(s, x, 1.55, 1.6, 2.55, PALEG if where == "목록 제안" else WHITE)
    text(s, x + 0.15, 1.65, 1.3, 0.25, where, 9.5, GREY, True)
    text(s, x + 0.15, 1.9, 1.3, 0.45, n, 20, GREEN, True)
    text(s, x + 0.15, 2.4, 1.35, 0.5, h, 13, INK, True)
    text(s, x + 0.15, 2.95, 1.35, 1.1, b, 11, GREY)
    if i:
        arrow(s, x - 0.17, 2.8, x - 0.01, 2.8, GREEN)
text(s, 0.6, 4.3, 8.8, 0.6, ["가짜 민감값을 넣은 OTel 장애 로그로 시연합니다.",
                             "알고리즘 2~3단계(AI 분석·판정)는 비용 때문에 같은 입력의 지난 실제 실행 기록을 씁니다 (데모 모드)."], 11, GREY, space=2)
note(s, "시연 약 2분. 서버는 demo 프로파일로 미리 띄워 두기(재시작하지 말 것). "
        "① /proposals.html 에서 [목록 제안 받기] — 1단계는 실제로 계산, 넣어 보기 카드와 검증 표를 짚는다. "
        "② 시연 전환 sec-park → [승인하고 게이트웨이에 등록]. "
        "③ [이 목록으로 장애 분석 요청하기] → ops-kim 으로 게이트웨이, 목록이 미리 골라져 있음 → 샘플 E2a → [파생본 만들기] (107 → 6, 경고 0). "
        "④ sec-park → [승인하고 전송]. ⑤ AI 답 'ad 서비스 UNAVAILABLE' + 받는 쪽 보안 한 줄. 라이브가 안 되면 녹화 영상.")

# ───────────────────── 9. 다음 주 ─────────────────────
s = new(L_CONTENT, "다음 주", "받는 길을 막고, 더 어려운 장애로 다시 잰다")
todo = [("응답 검사", "AI 답의 실행 명령·DB 명령·외부 링크 경고, 받은 답 지문 기록", AMBER),
        ("어려운 검증 장애", "단서가 오류 메시지에만 있는 장애로 다시 검증", GREEN),
        ("판정 흔들림 줄이기", "같은 목록을 3번 재서 평균", GREEN),
        ("입구 검사", "원인 서비스의 오류 로그가 없는 장애는 걸러냄", GREEN)]
for i, (h, b, col) in enumerate(todo):
    y = 1.4 + i * 0.85
    box(s, 0.6, y, 8.8, 0.72)
    box(s, 0.6, y, 0.06, 0.72, col, shape=MSO_SHAPE.RECTANGLE)
    text(s, 0.85, y, 2.4, 0.72, h, 14, col, True, anchor=MSO_ANCHOR.MIDDLE)
    text(s, 3.3, y, 6.0, 0.72, b, 12, INK, anchor=MSO_ANCHOR.MIDDLE)
note(s, "약 20초. 다음 주에는 응답 검사를 만들고, 더 어려운 장애와 반복 측정으로 알고리즘 효과를 다시 확인하겠습니다.")

prs.save(OUT)
OUT.with_name(OUT.stem + "_대본.md").write_text("# 여과시간 주간 발표 대본 (11/4, 5분)\n\n" + "\n".join(SCRIPT))
print("저장:", OUT, "·", page, "장")
