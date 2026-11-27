# -*- coding: utf-8 -*-
"""Презентация: инструкция по фиче «Управление доставками» (Foremen). Скриншоты — из shots/."""
import os
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE, MSO_CONNECTOR
from pptx.oxml.ns import qn
from lxml import etree
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, "shots")
CACHE = os.path.join(HERE, "cache")
os.makedirs(CACHE, exist_ok=True)
OUT = os.path.join(HERE, "..", "foremen-delivery-management-v2.pptx")

INK = RGBColor(0x0F, 0x17, 0x2A)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
BRAND = RGBColor(0x25, 0x63, 0xEB)
BRAND_DK = RGBColor(0x1D, 0x4E, 0xD8)
MUTED = RGBColor(0x64, 0x74, 0x8B)
SOFT = RGBColor(0xF1, 0xF5, 0xF9)
SLATE = RGBColor(0x1E, 0x29, 0x3B)
LIGHTBLUE = RGBColor(0xBA, 0xE6, 0xFD)
NOTE = RGBColor(0x33, 0x41, 0x55)
LINE = RGBColor(0xCB, 0xD5, 0xE1)
GREEN = RGBColor(0x16, 0xA3, 0x4A)
AMBER = RGBColor(0xD9, 0x77, 0x06)
RED = RGBColor(0xDC, 0x26, 0x26)
ROLE_COL = {
    "Менеджер": RGBColor(0x25, 0x63, 0xEB),
    "Клиент": RGBColor(0x0E, 0x9F, 0x6E),
    "Финансист": RGBColor(0x7C, 0x3A, 0xED),
    "Байер": RGBColor(0xD9, 0x77, 0x06),
    "Бригадир": RGBColor(0xDC, 0x26, 0x26),
    "Система": RGBColor(0x47, 0x55, 0x69),
}

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
BLANK = prs.slide_layouts[6]
SW, SH = 13.333, 7.5
page_no = [0]


def slide(bg=WHITE):
    s = prs.slides.add_slide(BLANK)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = bg
    page_no[0] += 1
    return s


def rect(s, x, y, w, h, color, shape=MSO_SHAPE.RECTANGLE, line=None):
    sh = s.shapes.add_shape(shape, Inches(x), Inches(y), Inches(w), Inches(h))
    sh.fill.solid()
    sh.fill.fore_color.rgb = color
    if line is None:
        sh.line.fill.background()
    else:
        sh.line.color.rgb = line
        sh.line.width = Pt(1)
    sh.shadow.inherit = False
    return sh


def text(s, x, y, w, h, runs, size=14, color=INK, bold=False, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, font=None, spacing=None):
    """runs: str или список абзацев; абзац = str или (str, dict(size,color,bold))."""
    tb = s.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = tb.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = tf.margin_right = Inches(0.04)
    tf.margin_top = tf.margin_bottom = Inches(0.02)
    paras = runs if isinstance(runs, list) else [runs]
    for i, p in enumerate(paras):
        opts = {}
        if isinstance(p, tuple):
            p, opts = p
        para = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        para.alignment = align
        if spacing:
            para.space_after = Pt(spacing)
        r = para.add_run()
        r.text = p
        r.font.size = Pt(opts.get("size", size))
        r.font.bold = opts.get("bold", bold)
        r.font.color.rgb = opts.get("color", color)
        if font:
            r.font.name = font
    return tb


def footer(s, dark=False):
    c = LIGHTBLUE if dark else MUTED
    text(s, 0.45, 7.08, 8, 0.3, "Foremen · Управление доставками · инструкция пользователя", size=9, color=c)
    text(s, 12.0, 7.08, 0.9, 0.3, str(page_no[0]), size=9, color=c, align=PP_ALIGN.RIGHT)


def header(s, title, sub=None, tag=None, tag_color=BRAND):
    rect(s, 0, 0, SW, 0.95, WHITE)
    rect(s, 0, 0.95, SW, 0.03, BRAND)
    text(s, 0.45, 0.14, 10.5, 0.55, title, size=26, bold=True, color=INK, anchor=MSO_ANCHOR.MIDDLE)
    if sub:
        text(s, 0.47, 0.6, 11, 0.32, sub, size=12.5, color=MUTED)
    if tag:
        b = rect(s, 11.0, 0.27, 1.9, 0.42, tag_color, MSO_SHAPE.ROUNDED_RECTANGLE)
        tf = b.text_frame
        tf.text = tag
        p = tf.paragraphs[0]
        p.alignment = PP_ALIGN.CENTER
        p.runs[0].font.size = Pt(13)
        p.runs[0].font.bold = True
        p.runs[0].font.color.rgb = WHITE


def bullet_box(s, x, y, w, h, title, items, color=BRAND, size=13.5):
    rect(s, x, y, w, h, SOFT, MSO_SHAPE.ROUNDED_RECTANGLE).adjustments[0] = 0.04
    rect(s, x, y + 0.12, 0.07, h - 0.24, color)
    paras = []
    if title:
        paras.append((title, dict(size=size + 1.5, bold=True, color=INK)))
    for it in items:
        paras.append(("• " + it, dict(size=size, color=NOTE)))
    text(s, x + 0.2, y + 0.1, w - 0.35, h - 0.2, paras, spacing=5)


def fit_image(path, max_w, max_h):
    im = Image.open(path)
    w, h = im.size
    k = min(max_w / w, max_h / h)
    return w * k, h * k


def prep(name, crop_h=None):
    """Возвращает путь к PNG (при необходимости обрезанному сверху)."""
    src = os.path.join(SHOTS, name + ".png")
    if not crop_h:
        return src
    dst = os.path.join(CACHE, f"{name}-c{crop_h}.png")
    im = Image.open(src)
    im.crop((0, 0, im.size[0], min(crop_h, im.size[1]))).save(dst)
    return dst


def picture(s, path, x, y, max_w, max_h, border=True):
    w, h = fit_image(path, max_w, max_h)
    if border:
        rect(s, x - 0.03, y - 0.03, w + 0.06, h + 0.06, LINE)
    s.shapes.add_picture(path, Inches(x), Inches(y), Inches(w), Inches(h))
    return w, h


def steps_panel(s, x, y, w, h, steps, result=None, color=BRAND):
    rect(s, x, y, w, h, SOFT, MSO_SHAPE.ROUNDED_RECTANGLE).adjustments[0] = 0.03
    cy = y + 0.15
    for i, st in enumerate(steps, 1):
        c = rect(s, x + 0.15, cy + 0.02, 0.32, 0.32, color, MSO_SHAPE.OVAL)
        tf = c.text_frame
        tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
        tf.text = str(i)
        p = tf.paragraphs[0]
        p.alignment = PP_ALIGN.CENTER
        p.runs[0].font.size = Pt(11)
        p.runs[0].font.bold = True
        p.runs[0].font.color.rgb = WHITE
        lines = max(1, int(len(st) / ((w - 0.8) * 7.4)) + 1)
        hh = 0.2 * lines + 0.14
        text(s, x + 0.55, cy, w - 0.7, hh, st, size=12.5, color=INK)
        cy += hh + 0.06
    if result:
        ry = y + h - 1.0
        rect(s, x + 0.12, ry - 0.04, w - 0.24, 0.015, LINE)
        text(s, x + 0.15, ry, w - 0.3, 0.95, [("Результат", dict(size=11, bold=True, color=GREEN)), (result, dict(size=12, color=NOTE))])


def shot_slide(title, sub, role, img, steps, result=None, crop_h=None):
    s = slide()
    header(s, title, sub, tag=role, tag_color=ROLE_COL[role])
    path = prep(img, crop_h)
    picture(s, path, 0.45, 1.25, 8.75, 5.65)
    steps_panel(s, 9.5, 1.25, 3.4, 5.65, steps, result, color=ROLE_COL[role])
    footer(s)
    return s


def wide_slide(title, sub, role, img, caption_steps):
    s = slide()
    header(s, title, sub, tag=role, tag_color=ROLE_COL[role])
    path = prep(img)
    w, h = picture(s, path, 0.45, 1.2, 12.4, 4.7)
    y = 1.2 + h + 0.12
    text(s, 0.45, y, 12.4, 7.0 - y, caption_steps, size=12.5, color=NOTE, spacing=3)
    footer(s)
    return s


def pair_slide(title, sub, role, img1, cap1, img2, cap2, steps, crop=None):
    s = slide()
    header(s, title, sub, tag=role, tag_color=ROLE_COL[role])
    p1, p2 = prep(img1), prep(img2)
    picture(s, p1, 0.45, 1.2, 6.15, 3.5)
    picture(s, p2, 6.75, 1.2, 6.15, 3.5)
    text(s, 0.45, 4.78, 6.15, 0.4, cap1, size=11.5, color=MUTED, bold=True)
    text(s, 6.75, 4.78, 6.15, 0.4, cap2, size=11.5, color=MUTED, bold=True)
    paras = [(f"{i}. {t}", dict(size=12.5, color=INK)) for i, t in enumerate(steps, 1)]
    rect(s, 0.45, 5.2, 12.45, 1.75, SOFT, MSO_SHAPE.ROUNDED_RECTANGLE).adjustments[0] = 0.05
    text(s, 0.65, 5.27, 12.1, 1.65, paras, spacing=3)
    footer(s)
    return s


def phone_slide(title, sub, role, imgs, steps, result=None, crop_h=1688):
    s = slide()
    header(s, title, sub, tag=role, tag_color=ROLE_COL[role])
    x = 0.55
    for name, cap in imgs:
        p = prep(name, crop_h)
        w, h = picture(s, p, x, 1.2, 2.6, 5.35)
        text(s, x - 0.1, 6.6, w + 0.2, 0.4, cap, size=10.5, color=MUTED, align=PP_ALIGN.CENTER, bold=True)
        x += w + 0.3
    px = max(x + 0.1, 6.4)
    steps_panel(s, px, 1.25, 12.9 - px, 5.65, steps, result, color=ROLE_COL[role])
    footer(s)
    return s


def section_slide(role, name_line, who, goal, steps):
    s = slide(SLATE)
    rect(s, 0, 0, 0.35, SH, ROLE_COL[role])
    text(s, 0.9, 0.8, 11, 0.5, "ГЛАВА · глазами роли", size=14, color=LIGHTBLUE, bold=True)
    text(s, 0.9, 1.3, 11.5, 1.0, role, size=48, color=WHITE, bold=True)
    text(s, 0.9, 2.35, 11.5, 0.5, name_line, size=18, color=LIGHTBLUE)
    text(s, 0.9, 3.0, 5.2, 3.6, [("Кто это", dict(size=13, bold=True, color=LIGHTBLUE)), (who, dict(size=15, color=WHITE)),
                                 ("", dict(size=8)), ("Зачем", dict(size=13, bold=True, color=LIGHTBLUE)), (goal, dict(size=15, color=WHITE))], spacing=4)
    rect(s, 6.6, 3.0, 6.0, 3.8, RGBColor(0x2B, 0x38, 0x4D), MSO_SHAPE.ROUNDED_RECTANGLE).adjustments[0] = 0.04
    text(s, 6.85, 3.1, 5.6, 0.4, "Путь по шагам", size=13, bold=True, color=LIGHTBLUE)
    text(s, 6.85, 3.5, 5.6, 3.3, [(f"{i}. {t}", dict(size=14, color=WHITE)) for i, t in enumerate(steps, 1)], spacing=5)
    footer(s, dark=True)
    return s


# ---------------------------------------------------------------- диаграмма последовательности
def add_arrow_end(conn, dash=False):
    ln = conn.line._get_or_add_ln()
    tail = etree.SubElement(ln, qn("a:tailEnd"))
    tail.set("type", "triangle")
    tail.set("w", "med")
    tail.set("len", "med")


def seq_diagram(title, sub, lanes, msgs, tag_text, notes=None, row_h=0.5):
    """lanes: [(имя, роль-ключ)], msgs: (номер, от, к, текст[, стиль]) — стиль 'self' | 'dash'."""
    s = slide()
    header(s, title, sub, tag=tag_text, tag_color=BRAND)
    n = len(lanes)
    left, right = 0.5, 12.85
    colw = (right - left) / n
    cx = {name: left + colw * (i + 0.5) for i, (name, _) in enumerate(lanes)}
    top = 1.15
    for name, key in lanes:
        b = rect(s, cx[name] - colw / 2 + 0.07, top, colw - 0.14, 0.5, ROLE_COL[key], MSO_SHAPE.ROUNDED_RECTANGLE)
        tf = b.text_frame
        tf.margin_left = tf.margin_right = Inches(0.03)
        tf.text = name
        tf.word_wrap = True
        p = tf.paragraphs[0]
        p.alignment = PP_ALIGN.CENTER
        p.runs[0].font.size = Pt(12)
        p.runs[0].font.bold = True
        p.runs[0].font.color.rgb = WHITE
    bottom = top + 0.55 + row_h * len(msgs) + 0.25
    for name, _ in lanes:
        c = s.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(cx[name]), Inches(top + 0.5), Inches(cx[name]), Inches(bottom))
        c.line.color.rgb = LINE
        c.line.width = Pt(1.25)
        c.line.dash_style = 4  # dash
    y = top + 0.78
    for m in msgs:
        num, a, b_, label = m[0], m[1], m[2], m[3]
        style = m[4] if len(m) > 4 else ""
        xa, xb = cx[a], cx[b_]
        badge = rect(s, min(xa, xb) - 0.0 - 0.0, y - 0.0, 0.0, 0.0, WHITE)  # placeholder (не видим)
        badge._element.getparent().remove(badge._element)
        if style == "self" or a == b_:
            box = rect(s, xa - colw * 0.46, y - 0.2, colw * 0.92, 0.42, RGBColor(0xE0, 0xE7, 0xFF), MSO_SHAPE.ROUNDED_RECTANGLE, line=BRAND)
            tf = box.text_frame
            tf.word_wrap = True
            tf.margin_left = tf.margin_right = Inches(0.04)
            tf.margin_top = tf.margin_bottom = 0
            tf.vertical_anchor = MSO_ANCHOR.MIDDLE
            tf.text = f"{num}. {label}"
            p = tf.paragraphs[0]
            p.alignment = PP_ALIGN.CENTER
            p.runs[0].font.size = Pt(9.5)
            p.runs[0].font.color.rgb = INK
        else:
            c = s.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(xa), Inches(y + 0.1), Inches(xb), Inches(y + 0.1))
            c.line.color.rgb = BRAND_DK if style != "dash" else MUTED
            c.line.width = Pt(1.75)
            if style == "dash":
                c.line.dash_style = 4
            add_arrow_end(c)
            lx = min(xa, xb) + 0.08
            lw = abs(xb - xa) - 0.16
            text(s, lx, y - 0.27, max(lw, 1.2), 0.36, f"{num}. {label}", size=10, color=INK, align=PP_ALIGN.CENTER if lw > 1.4 else PP_ALIGN.LEFT)
        y += row_h
    if notes:
        text(s, 0.5, 6.78, 12.3, 0.3, notes, size=10.5, color=MUTED)
    footer(s)
    return s


# ================================================================== СЛАЙДЫ
# 1. Обложка
s = slide(SLATE)
rect(s, 0, 0, SW, 0.12, BRAND)
text(s, 0.9, 1.7, 11.5, 0.5, "FOREMEN · ИНСТРУКЦИЯ ПОЛЬЗОВАТЕЛЯ", size=16, color=LIGHTBLUE, bold=True)
text(s, 0.9, 2.3, 11.8, 1.6, "Управление доставками", size=60, color=WHITE, bold=True)
text(s, 0.9, 3.7, 11.5, 1.2, "От материала в смете до приёмки на объекте: закупки, согласование с клиентом, проформы, заказы поставщикам, отслеживание и возвраты", size=20, color=LIGHTBLUE)
text(s, 0.9, 5.7, 11.5, 0.9, [("Демо-проект: «Remont mieszkania — Jana Kazimierza 64a», Варшава", dict(size=14, color=WHITE)),
                               ("Все скриншоты сняты на локальной сборке под реальными логинами ролей (польский интерфейс)", dict(size=13, color=LIGHTBLUE))], spacing=3)
footer(s, dark=True)

# 2. Вступление
s = slide()
header(s, "Что такое «Управление доставками»", "Единый процесс закупки материалов на проект: от плана до подписанной приёмки")
bullet_box(s, 0.45, 1.25, 6.1, 2.55, "Зачем это нужно", [
    "Заменяет Google-таблицу и карточки Bitrix: вся цепочка закупки в одной системе",
    "Видно, что нужно купить, кто согласовал, что оплачено и где груз",
    "Позиции привязаны к срокам работ: материал не должен блокировать бригаду",
    "Цены и маржа считаются автоматически, закупочная цена клиенту не показывается",
])
bullet_box(s, 6.75, 1.25, 6.1, 2.55, "Главное правило", [
    "Пока клиент не оплатил проформу полностью, закупка не уходит байеру",
    "Заказ поставщику оплачивает финансист, не байер",
    "Доставка идёт прямо на объект, без склада",
    "Приёмку бригадир делает поштучно: принято / нет / возврат",
], color=RED)
text(s, 0.45, 3.95, 12.4, 0.35, "Три пути закупки позиции", size=15, bold=True)
paths = [("Согласовано с клиентом", "AGREED", "Клиент видит только итоговую цену. Полный цикл: согласование → проформа → оплата → байер.", BRAND),
         ("Из пакета оферты", "PACKAGE", "Клиент выбрал материал из пакета. Цены клиенту не показываются, проформа не нужна: сразу к байеру.", GREEN),
         ("Клиент покупает сам", "CLIENT_OWN", "Мы только планируем и отслеживаем доставку и приёмку. Проформа не нужна.", AMBER)]
for i, (t, k, d, c) in enumerate(paths):
    x = 0.45 + i * 4.18
    rect(s, x, 4.35, 4.05, 2.5, SOFT, MSO_SHAPE.ROUNDED_RECTANGLE).adjustments[0] = 0.04
    rect(s, x, 4.35, 4.05, 0.08, c)
    text(s, x + 0.15, 4.5, 3.8, 0.4, t, size=16, bold=True)
    text(s, x + 0.15, 4.92, 3.8, 0.3, f"путь: {k}", size=10.5, color=MUTED)
    text(s, x + 0.15, 5.3, 3.8, 1.5, d, size=13, color=NOTE)
footer(s)

# 3. Разделы
s = slide()
header(s, "Из чего состоит функция", "Шесть рабочих областей в разделе «Dostawy» и вкладках проекта")
blocks = [
    ("Planer zakupów", "Общий план закупок проекта: даты, этапы, цена продажи/закупки, маржа, группировка по заказам и проформам.", "Менеджер, байер"),
    ("Zatwierdzanie pozycji", "Согласование позиций с клиентом: принять, отклонить, аналог, «куплю сам», своя ссылка.", "Менеджер, клиент"),
    ("Proformy", "Проформа клиенту из принятых позиций; оплата определяется по банковской выписке.", "Менеджер, клиент, финансист"),
    ("Zamówienia zewnętrzne", "Заказы байера у поставщиков, очередь «К оплате» у финансиста.", "Байер, финансист"),
    ("Przesyłki", "Отправки: перевозчик, номер, ETA, водитель, история событий.", "Байер, менеджер"),
    ("Odbiór dostaw", "Мобильная приёмка на объекте, возвраты и рекламации, ближайшие поставки.", "Бригадир, менеджер"),
]
for i, (t, d, who) in enumerate(blocks):
    x = 0.45 + (i % 3) * 4.18
    y = 1.25 + (i // 3) * 2.85
    rect(s, x, y, 4.05, 2.7, SOFT, MSO_SHAPE.ROUNDED_RECTANGLE).adjustments[0] = 0.04
    c = rect(s, x + 0.15, y + 0.18, 0.5, 0.5, BRAND, MSO_SHAPE.OVAL)
    c.text_frame.text = str(i + 1)
    pp = c.text_frame.paragraphs[0]
    pp.alignment = PP_ALIGN.CENTER
    pp.runs[0].font.size = Pt(16)
    pp.runs[0].font.bold = True
    pp.runs[0].font.color.rgb = WHITE
    text(s, x + 0.75, y + 0.2, 3.2, 0.5, t, size=16, bold=True, anchor=MSO_ANCHOR.MIDDLE)
    text(s, x + 0.2, y + 0.85, 3.7, 1.3, d, size=13, color=NOTE)
    text(s, x + 0.2, y + 2.2, 3.7, 0.4, "Кто работает: " + who, size=11.5, color=MUTED, bold=True)
text(s, 0.45, 6.78, 12.4, 0.3, "Бригадир и менеджер также видят «Nadchodzące dostawy» (ближайшие поставки) и «Odbiór dostaw» в мобильной нижней панели.", size=11, color=MUTED)
footer(s)

# 4. Роли и возможности
s = slide()
header(s, "Роли и их возможности", "Кто что видит и что может делать в процессе доставок")
rows = [
    ("Роль", "Видит", "Может делать", "Не видит / не может"),
    ("Менеджер\nMarek Nowak", "Все закупки своих проектов, цены закупки и продажи, маржу", "Добавлять позиции, отправлять на согласование, решать за клиента, создавать и выставлять проформы, смотреть заказы и отправки", "Не оплачивает заказы поставщикам"),
    ("Клиент\nOleg Goncharenko", "Только свои позиции и проформы в проекте", "Принять/отклонить, предложить свою ссылку, «куплю сам», оплатить проформу", "Закупочные цены, планер, заказы поставщикам"),
    ("Финансист\nPiotr Zieliński", "Банковскую выписку, проформы, заказы к оплате", "Подтверждать платёж проформы, отмечать оплату заказов поставщикам, возвращать заказ байеру", "Позиции закупки и приёмку"),
    ("Байер\nJakub Mazur", "Все закупки всех проектов и список проектов (только чтение, без состава команды), без членства в проекте", "Доска закупок, внешние заказы, отправки; покупает не дороже цены продажи", "Не правит документы клиента, приёмка только чтение"),
    ("Бригадир\nTomasz Lewandowski", "Поставки на свой объект, статус и ETA", "Принять, отметить «нет», оформить возврат, приложить фото", "Любые цены и суммы"),
]
tbl = s.shapes.add_table(len(rows), 4, Inches(0.45), Inches(1.2), Inches(12.4), Inches(5.6)).table
cw = [2.1, 3.1, 4.4, 2.8]
for i, w in enumerate(cw):
    tbl.columns[i].width = Inches(w)
for r, row in enumerate(rows):
    for c, val in enumerate(row):
        cell = tbl.cell(r, c)
        cell.text = val
        cell.margin_left = cell.margin_right = Inches(0.08)
        cell.vertical_anchor = MSO_ANCHOR.MIDDLE
        for para in cell.text_frame.paragraphs:
            for run in para.runs:
                run.font.size = Pt(11.5 if r else 12.5)
                run.font.bold = (r == 0 or c == 0)
                run.font.color.rgb = WHITE if r == 0 else INK
        cell.fill.solid()
        if r == 0:
            cell.fill.fore_color.rgb = SLATE
        elif c == 0:
            key = ["", "Менеджер", "Клиент", "Финансист", "Байер", "Бригадир"][r]
            cell.fill.fore_color.rgb = ROLE_COL[key]
            for para in cell.text_frame.paragraphs:
                for run in para.runs:
                    run.font.color.rgb = WHITE
        else:
            cell.fill.fore_color.rgb = SOFT if r % 2 else WHITE
footer(s)

# 5. Статусы позиции
s = slide()
header(s, "Жизненный цикл позиции: статусы", "Каждая позиция проходит цепочку статусов, большинство переходов система делает сама")
chain = [
    ("Nowe", "Запланирована", MUTED), ("Zaproponowane", "Отправлена клиенту", BRAND), ("Zaakceptowane", "Клиент принял", BRAND),
    ("W proformie", "Есть проформа", BRAND), ("Opłacone przez klienta", "Проформа оплачена", GREEN), ("U zakupowca", "Передана байеру", AMBER),
    ("Zamówione", "Заказана у поставщика", AMBER), ("Opłacone dostawcy", "Поставщик оплачен", AMBER), ("W dostawie", "Отправка в пути", RED),
    ("Częściowo odebrane", "Есть часть", RED), ("Odebrane", "Принята полностью", GREEN),
]
per_row = 4
for i, (pl, ru, c) in enumerate(chain):
    row, col = divmod(i, per_row)
    x = 0.45 + col * 3.15
    y = 1.35 + row * 1.55
    sh = s.shapes.add_shape(MSO_SHAPE.PENTAGON, Inches(x), Inches(y), Inches(3.0), Inches(1.15))
    sh.fill.solid()
    sh.fill.fore_color.rgb = c
    sh.line.fill.background()
    sh.shadow.inherit = False
    tf = sh.text_frame
    tf.word_wrap = True
    tf.margin_left = Inches(0.15)
    tf.text = f"{i + 1}. {pl}"
    tf.paragraphs[0].runs[0].font.size = Pt(15)
    tf.paragraphs[0].runs[0].font.bold = True
    tf.paragraphs[0].runs[0].font.color.rgb = WHITE
    p2 = tf.add_paragraph()
    r = p2.add_run()
    r.text = ru
    r.font.size = Pt(12)
    r.font.color.rgb = WHITE
bullet_box(s, 0.45, 5.55, 6.1, 1.4, None, [
    "Пути «Пакет» и «Клиент сам» пропускают шаги 3–5: сразу к байеру или в отслеживание",
    "Возврат/рекламация возвращает позицию из «Odebrane» в «W dostawie» (доп. поставка)"], color=GREEN, size=12)
bullet_box(s, 6.75, 5.55, 6.1, 1.4, None, [
    "Жёсткий гейт: статус «U zakupowca» наступает только после полной оплаты проформы",
    "В канбане байера статусы сгруппированы в 6 колонок"], color=RED, size=12)
footer(s)

# 6–7. Диаграмма последовательности
seq_diagram(
    "Процесс жизненного цикла доставки: часть 1",
    "От материала в смете до оплаченной проформы и передачи байеру",
    [("Менеджер", "Менеджер"), ("Клиент", "Клиент"), ("Система", "Система"), ("Банк / Финансист", "Финансист"), ("Байер", "Байер")],
    [
        (1, "Система", "Система", "Тип материала попал в смету (справочно)", "self"),
        (2, "Менеджер", "Система", "Добавляет позицию, видит «Start prac» работы и «Zamów do»"),
        (3, "Система", "Система", "Сверка «Potrzebne do» со стартом работы, ⚠ если позже", "self"),
        (4, "Менеджер", "Клиент", "Отправляет блок на решение (срок)"),
        (5, "Клиент", "Система", "Принять (материал + цена) / Отклонить / Аналог"),
        (6, "Система", "Менеджер", "Уведомление: клиент принял / отклонил", "dash"),
        (7, "Менеджер", "Система", "Отмечает принятые → черновик проформы → «Wystaw»"),
        (8, "Система", "Клиент", "Проформа PF/2026/…: сумма, реквизиты, срок", "dash"),
        (9, "Клиент", "Банк / Финансист", "Платит на счёт компании"),
        (10, "Банк / Финансист", "Система", "Строка выписки сопоставлена, финансист «Potwierdź»"),
        (11, "Система", "Байер", "Проформа оплачена → позиции «U zakupowca», уведомление", "dash"),
    ],
    "Часть 1 из 2",
    notes="Жёсткое правило: без полной оплаты проформы шаг 11 не наступает. Пути «Пакет» и «Клиент сам» пропускают шаги 4–10.", row_h=0.48)

seq_diagram(
    "Процесс жизненного цикла доставки: часть 2",
    "От задания байеру до приёмки на объекте, возвратов и рекламаций",
    [("Байер", "Байер"), ("Система", "Система"), ("Финансист", "Финансист"), ("Поставщик / перевозчик", "Система"), ("Бригадир", "Бригадир"), ("Менеджер", "Менеджер")],
    [
        (12, "Байер", "Система", "Создаёт заказы (цена ≤ продажи)"),
        (13, "Байер", "Поставщик / перевозчик", "Размещает заявку у поставщика, прикладывает счёт"),
        (14, "Система", "Финансист", "«Wyślij do płatności» → очередь «К оплате»", "dash"),
        (15, "Финансист", "Поставщик / перевозчик", "Платит поставщику; «Oznacz jako opłacone»"),
        (16, "Байер", "Система", "Создаёт отправку, ссылка отслеживания авто"),
        (17, "Система", "Бригадир", "Уведомление: «Dostawa w drodze» / ETA сдвинулся", "dash"),
        (18, "Поставщик / перевозчик", "Бригадир", "Привозит на объект"),
        (19, "Бригадир", "Система", "Приёмка: принято / нет / возврат (+ фото)"),
        (20, "Система", "Система", "Расхождение → доп. поставка", "self"),
        (21, "Система", "Менеджер", "Уведомление о расхождении; итог «Odebrane» → маржа", "dash"),
    ],
    "Часть 2 из 2",
    notes="Автоматический трекинг перевозчиков на этой сборке выключен: статус и ETA вносятся вручную. Приёмку можно аннулировать (void) в течение 24 часов.", row_h=0.5)

# 8. Количества: приёмка и возвраты
s = slide()
header(s, "Приёмка, возвраты и рекламации: количества", "Пример из демо-данных: плитка Tubądzin Cielo e Terra 119,8×119,8 см, заказано 28 м²")
steps_q = [("Заказано / отправлено", "28 м²", MUTED), ("Принято бригадиром", "26 м²", GREEN), ("Возврат (рекламация)", "2 м²", RED), ("Осталось получить", "2 м²", AMBER)]
for i, (t, v, c) in enumerate(steps_q):
    x = 0.45 + i * 3.15
    rect(s, x, 1.3, 3.0, 1.6, SOFT, MSO_SHAPE.ROUNDED_RECTANGLE).adjustments[0] = 0.06
    rect(s, x, 1.3, 3.0, 0.08, c)
    text(s, x + 0.1, 1.5, 2.8, 0.4, t, size=13, color=NOTE, bold=True, align=PP_ALIGN.CENTER)
    text(s, x + 0.1, 1.95, 2.8, 0.8, v, size=34, color=c, bold=True, align=PP_ALIGN.CENTER)
bullet_box(s, 0.45, 3.15, 6.1, 3.7, "Что делает бригадир", [
    "«Przyjęto» — сколько принято по факту",
    "«Brak» — сколько не довезли",
    "«Zwrot» — возврат; комментарий обязателен, можно приложить фото",
    "«Z już przyjętych» — возврат того, что уже принято ранее",
    "«Przyjmij wszystko» — принять всё одним касанием",
])
bullet_box(s, 6.75, 3.15, 6.1, 3.7, "Что делает система", [
    "Записывает события без правки старых (append-only), ведёт историю по позиции",
    "Для недостающих и возвращённых единиц создаёт доп. поставку (toast «Utworzono dostawę uzupełniającą»)",
    "Позиция становится «Częściowo odebrane», пока принято меньше заказанного",
    "Закрывает отправку (Doręczona), когда все строки разрешены",
    "Перепоставка сверх заказа принимается и помечается как излишек, цена и маржа не меняются",
], color=GREEN)
footer(s)

# ------------------------------------------------------------ МЕНЕДЖЕР
section_slide("Менеджер", "Marek Nowak · cto@loaders.dev", "Ведёт проект: планирует закупки, согласует с клиентом, выставляет проформы, следит за поставками.",
              "Чтобы нужный материал был на объекте к началу работ, а клиент заранее оплатил.",
              ["Проверить материалы в смете", "Собрать план закупок и сверить со сроками работ", "Отправить позиции клиенту", "Создать проформу из принятых позиций", "Контролировать оплату, заказы и отправки"])
shot_slide("Шаг 1. Материалы в смете", "Вкладка «Materiały» проекта: что нужно по сметe", "Менеджер", "mgr-20-estimate-materials",
           ["Откройте проект и вкладку «Materiały»", "Проверьте, какие материалы и в каком объёме нужны по комнатам", "Помните: смета только справочная, в закупку можно добавить и материал, которого в смете нет"],
           "Типы материалов из сметы видны менеджеру; закупочные позиции создаются отдельно.")
wide_slide("Шаг 2. План закупок и сверка со сроками работ", "Dostawy → Planer zakupów (вид «Bez grupowania»)", "Менеджер", "mgr-14-planner-wide",
           ["① Колонки «Dostawa na budowę» (когда реально привезут), «Potrzebne do» (когда нужно), «Zamów do» (крайний срок заказа), «Start prac» (старт связанной работы).",
            "② Значок ⚠ у «Start prac» означает, что доставка позже старта работы: бригада может встать. Сортируйте по дате и берите самое срочное.",
            "③ Справа видны цена продажи, закупки и маржа по позиции (доставка распределяется пропорционально стоимости)."])
shot_slide("Шаг 2б. График работ как опора для сроков", "Вкладка «Plan/wykonanie» (Gantt) в проекте", "Менеджер", "mgr-21-schedule",
           ["Откройте график работ проекта", "Найдите работу, для которой нужен материал (например, «Hydraulika biały montaż»)", "Сравните её старт с датой «Potrzebne do» в планере"],
           "Дата необходимости материала согласована с началом работы.")
shot_slide("Шаг 3. Добавить позицию закупки", "Dostawy → «Dodaj pozycję»", "Менеджер", "mgr-03-add-position",
           ["Нажмите «Dodaj pozycję»", "Выберите продавца и материал, укажите количество", "Свяжите с категорией работ (график) или укажите «Potrzebne do» вручную", "Задайте цену закупки (план) и продажи, ставки VAT", "Сохраните: позиция получает статус «Nowe»"],
           "Позиция появилась в планере, в том числе для материала вне сметы.")
shot_slide("Шаг 4. Отправить блок клиенту на решение", "Вкладка «Zatwierdzanie pozycji» → «Wyślij blok do decyzji»", "Менеджер", "mgr-06-approvals-staff",
           ["Отметьте позиции со статусом «Nowe»", "Нажмите «Wyślij blok do decyzji», задайте срок решения и заметку", "Следите за статусом раунда «Otwarta» и сроком", "При необходимости: «Zaproponuj analog», «Zmień termin», «Zapisz decyzję klienta»"],
           "Позиции перешли в «Zaproponowane», клиент получил уведомление.")
pair_slide("Шаг 5. Создать проформу из принятых позиций", "Planer zakupów: выбор позиций → «Utwórz proformę z zaznaczonych»", "Менеджер",
           "mgr-05-proforma-dialog", "Черновик проформы: позиции, итог нетто/VAT/брутто", "mgr-05b-proforma-issued", "После «Wystaw»: номер PF/2026/… и кнопка «Pobierz PDF»",
           ["Отметьте позиции со статусом «Zaakceptowane» и нажмите «Utwórz proformę z zaznaczonych»: откроется черновик с суммами", "Укажите срок платежа и примечание, затем «Wystaw»: система присвоит номер PF/гггг/№ и сформирует PDF", "Проформа получила статус «Wystawiona»; клиент видит её во вкладке «Proformy» и может скачать PDF"])
shot_slide("Шаг 5б. PDF проформы для клиента", "Кнопка «Pobierz PDF»: документ формируется на польском", "Менеджер", "mgr-05c-proforma-pdf",
           ["Нажмите «Pobierz PDF» в карточке проформы", "В PDF: продавец и покупатель, реквизиты счёта компании, «Tytuł przelewu», позиции, суммы по ставкам VAT", "Польские буквы (ł, ś, ć, ą, ż) печатаются корректно: в PDF встроен шрифт Liberation Sans"],
           "Клиент получает тот же PDF и оплачивает по указанному «Tytuł przelewu».")
pair_slide("Шаг 6. Проформы и контроль оплаты", "Dostawy → Proformy", "Менеджер",
           "mgr-07-proformas", "Список: оплаченная, ожидающие оплаты и вновь выставленная проформа", "mgr-08-proforma-detail", "Карточка оплаченной проформы: позиции, VAT 8%, связанные платежи",
           ["Статус «Opłacona» появляется только после полной оплаты по банковской выписке", "В карточке видны привязанные банковские платежи и остаток «Do zapłaty»", "Проформу без оплаты можно аннулировать значком «⊘»"])
shot_slide("Шаг 7. Группировка плана по проформам", "Planer zakupów → «Wg proform»", "Менеджер", "mgr-02-planner-by-proforma",
           ["Выберите группировку «Wg proform»", "Раскройте группу проформы, чтобы увидеть позиции, статус («Opłacona»/«Wystawiona») и итог нетто/брутто", "Группа «Bez proformy» показывает, что ещё не выставлено клиенту"],
           "Видно, какие позиции уже в проформе и оплачены.")
pair_slide("Шаг 8. Заказы поставщикам и отправки", "Dostawy → Zamówienia zewnętrzne, Przesyłki", "Менеджер",
           "mgr-09-external-orders", "Заказы байера: Leroy Merlin оплачен, Castorama ждёт оплаты", "mgr-10-shipments", "Отправки: DPD, магазин, InPost, GLS",
           ["В «Zamówienia zewnętrzne» видно, у кого заказано, сколько позиций и сумму к оплате", "В «Przesyłki» следите за статусом, перевозчиком, номером и планируемой датой", "Фильтр «Tylko opóźnione» показывает просроченные отправки"])
shot_slide("Шаг 9. Карточка отправки и история", "Przesyłki → значок «глаз»", "Менеджер", "mgr-11-shipment-detail",
           ["Откройте отправку из списка", "Проверьте метод доставки, номер, окно времени, источник даты (вручную)", "В «Historia zdarzeń» видны события: создана, доручена (закрыта приёмкой на объекте)"],
           "Статус «Doręczona» выставила система после приёмки бригадиром.")
wide_slide("Шаг 10. Уведомления менеджера", "Колокольчик в шапке", "Менеджер", "mgr-13-notifications",
           ["Менеджер получает: «Klient zaakceptował/odrzucił pozycję», «Proforma opłacona», «Dostawa w drodze», «Rozbieżność przy odbiorze dostawy».",
            "Клик по уведомлению ведёт на нужную позицию или отправку."])

# ------------------------------------------------------------ КЛИЕНТ
section_slide("Клиент", "Oleg Goncharenko · oleg@loaders.dev", "Заказчик ремонта. Видит только свой проект, без закупочных цен.",
              "Принять или отклонить предложенные материалы, оплатить проформу, при желании купить самому.",
              ["Открыть «Zatwierdzanie pozycji»", "Принять или отклонить позицию", "При желании: «куплю сам» или своя ссылка", "Оплатить проформу по реквизитам", "Следить за уведомлениями"])
shot_slide("Шаг 1. Позиции к решению", "Проект → «Zatwierdzanie pozycji» → «Do decyzji»", "Клиент", "cli-01-approvals",
           ["Зайдите в проект, вкладка «Zatwierdzanie pozycji» (красный бейдж = число ожидающих решений)", "Каждая карточка: материал, продавец, ссылка в магазин, количество", "Цена: нетто, VAT 8%, брутто за ед. и итог", "Срок «Decyzja do» и заметка менеджера"],
           "Клиент видит только итоговую цену, без закупочной.")
pair_slide("Шаг 2. Принять позицию", "Кнопка «Akceptuję»", "Клиент",
           "cli-02-accept-dialog", "Подтверждение: цена фиксируется", "cli-03-after-accept", "После принятия: тост «Zaakceptowano», счётчик уменьшился",
           ["Нажмите «Akceptuję»", "Подтвердите: цена будет зафиксирована, менеджер получит уведомление", "Позиция перейдёт в «Zaakceptowane» и пойдёт в проформу"])
pair_slide("Шаг 3. «Куплю сам» или отклонить", "Альтернативы принятию", "Клиент",
           "cli-04-buy-myself-dialog", "«Kupię samodzielnie»: клиент закупает сам", "cli-05-reject-dialog", "«Odrzucam»: причина обязательна",
           ["«Kupię samodzielnie» переводит позицию в путь «Klient kupuje sam»: доставку всё равно отслеживаем", "«Odrzucam» требует комментарий, менеджер предложит аналог", "«Zaproponuj własny link» — предложить свой вариант; «Omów na czacie» — обсудить в чате"])
shot_slide("Шаг 4. Принятые позиции", "Фильтр «Zaakceptowane»", "Клиент", "cli-07-accepted-tab",
           ["Переключитесь на «Zaakceptowane»", "Проверьте список принятых позиций и итоговые цены", "Ожидайте проформу по этим позициям"],
           "Решение сохранено; изменить цену или материал можно только через менеджера.")
shot_slide("Шаг 5. Оплатить проформу", "Вкладка «Proformy»", "Клиент", "cli-08-proformas-tab",
           ["Откройте «Proformy»: неоплаченные имеют статус «Wystawiona»", "Скопируйте «Tytuł przelewu» (кнопка «Kopiuj tytuł przelewu»)", "Скачайте PDF («Pobierz PDF») и оплатите на реквизиты компании", "После поступления денег статус сменится на «Opłacona»"],
           "Оплаченная проформа показывает 100% и «Do zapłaty 0,00».")
wide_slide("Шаг 6. Уведомления клиента", "Колокольчик в шапке", "Клиент", "cli-09-notifications",
           ["Клиент получает уведомления о запросе решения, о выставленной проформе и о ходе доставки по своим позициям."])

# ------------------------------------------------------------ ФИНАНСИСТ
section_slide("Финансист", "Piotr Zieliński · piotr@loaders.dev", "Отвечает за деньги: приход от клиента и оплаты поставщикам.",
              "Подтвердить платёж клиента по проформе и оплатить заказы поставщикам.",
              ["Открыть «Operacje bankowe»", "Подтвердить связь платежа с проформой", "Открыть очередь «К оплате»", "Отметить заказ оплаченным или вернуть байеру", "Проверить уведомления"])
shot_slide("Шаг 1. Банковские операции", "Firma → Operacje bankowe", "Финансист", "fin-01-bank-ledger",
           ["Откройте «Operacje bankowe»", "Ищите входящий платёж от клиента (Wpływ)", "Колонка «Wykrycie» показывает уверенность автосопоставления (70%)", "Фильтр «Do sprawdzenia» оставляет строки, требующие решения"],
           "Платёж по PF/2026/0042 ждёт подтверждения.")
pair_slide("Шаг 2. Подтвердить связь платежа с проформой", "Панель «Szczegóły operacji»", "Финансист",
           "fin-02-transaction-detail", "Предложение системы: «Płatność za proformę»", "fin-03-match-confirmed", "После «Potwierdź»: связь зафиксирована",
           ["Откройте строку: система предлагает PF/2026/0042 на 2665,44 zł по номеру и сумме", "Нажмите «Potwierdź» (или «Odrzuć»/«Powiąż ręcznie»)", "Проформа становится «Opłacona», позиции уходят к байеру, байер получает уведомление"])
pair_slide("Шаг 3. Очередь «К оплате»", "Dostawy → Zamówienia zewnętrzne", "Финансист",
           "fin-04-orders-queue", "Заказ CAS-W-77120 (Castorama) ждёт оплаты", "fin-05-order-details", "«Pokaż szczegóły»: позиции, доставка, ссылка на заказ",
           ["Откройте очередь «Wysłane do płatności»: номер заказа, продавец, закупщик, сумма брутто", "Раскройте детали: позиции, стоимость доставки, ссылка и документы поставщика", "Решение: «Oznacz jako opłacone» или «Zwróć zakupowcowi»"])
pair_slide("Шаг 4. Отметить заказ оплаченным", "Кнопка «Oznacz jako opłacone»", "Финансист",
           "fin-06-mark-paid-dialog", "Дата платежа и заметка", "fin-08-paid-tab", "Вкладка «Opłacone»: заказ в истории",
           ["Укажите дату платежа и заметку (например, ссылка на подтверждение)", "Подтвердите: позиции переходят в «Opłacone dostawcy»", "Если банковская строка придёт позже, система свяжет её сама, ручная отметка не конфликтует"])
wide_slide("Шаг 5. Уведомления финансиста", "Колокольчик в шапке", "Финансист", "fin-09-notifications",
           ["«Zamówienie do opłacenia» — байер отправил заказ, он в очереди «К оплате». «Propozycja dopasowania płatności» — система нашла платёж клиента и ждёт подтверждения."])

# ------------------------------------------------------------ БАЙЕР
section_slide("Байер", "Jakub Mazur · buyer@loaders.dev", "Закупщик компании. Видит закупки и список всех проектов, даже без членства (команду проекта не видит).",
              "Купить оплаченные клиентом позиции у поставщиков не дороже цены продажи и организовать доставку.",
              ["Открыть «Tablica zakupowca»", "Создать внешний заказ у поставщика", "Отправить заказ финансисту на оплату", "Создать отправку и внести ETA", "Получать уведомления"])
shot_slide("Шаг 1. Доска закупок", "Dostawy → Tablica zakupowca → фильтр «Wszyscy»", "Байер", "buy-01-kanban",
           ["Выберите «Wszyscy» (все закупки) или «Moje»/«Nieprzypisane»", "Колонки: «Do zamówienia» → «Zamówione» → «Opłacone dostawcy» → «W dostawie» → «Częściowo odebrane» → «Odebrane»", "В карточке: проект, материал, продавец, количество, дата на объекте, цены", "«Do zamówienia» — оплаченные клиентом позиции, готовые к заказу"],
           "Байер видит, что и когда нужно купить.")
pair_slide("Шаг 2. Новый внешний заказ", "Dostawy → Zamówienia zewnętrzne → «Nowe zamówienie»", "Байер",
           "buy-02b-project-picker", "Проект выбирается из списка (есть поиск)", "buy-03-new-order-filled", "Форма: продавец, номер, ссылка, доставка, позиции",
           ["Нажмите «Nowe zamówienie» и выберите проект из списка (поиск «Szukaj…»): байеру доступны все проекты", "Выберите продавца (Leroy Merlin Polska Sp. z o.o.), укажите номер заказа, ссылку и стоимость доставки", "Добавьте позиции, оплаченные клиентом, и цены закупки (не выше цены продажи)"])
pair_slide("Шаг 3. Сохранить и отправить на оплату", "«Zapisz» → «Wyślij do płatności»", "Байер",
           "buy-04-order-saved", "Заказ сохранён как «Wersja robocza» с проформой клиента", "buy-05-send-confirm", "Подтверждение отправки финансисту",
           ["После сохранения видна связанная проформа клиента со статусом «Opłacona»", "Прикрепите документы поставщика («Dołącz dokument» / «Prześlij dokument»)", "«Wyślij do płatności»: после отправки менять продавца нельзя; финансист получает уведомление"])
pair_slide("Шаг 4. Создать отправку", "Dostawy → Przesyłki → «Dodaj przesyłkę»", "Байер",
           "buy-07-new-shipment-form", "Форма: проект, заказ, позиции, перевозчик, номер, окно времени", "buy-06-shipments", "Список отправок проекта после создания",
           ["Нажмите «Dodaj przesyłkę», выберите проект из списка и внешний заказ (оплаченный финансистом): позиции подтянутся с остатком к отправке", "Выберите перевозчика и номер: «Link do śledzenia» формируется автоматически (поле вручную нужно только для «Inny»)", "Укажите дату и окно доставки; ETA вносится вручную (автотрекинг выключен), при сдвиге ETA менеджер и бригадир получают уведомление"])
shot_slide("Шаг 4б. Отправка без заказа: клиент закупает сам", "«Bez zamówienia» + перевозчик InPost", "Байер", "buy-07d-client-own-shipment",
           ["Для позиции пути «Klient kupuje sam» заказ у поставщика не нужен: оставьте «Bez zamówienia»", "Выберите позицию (Porta Ościeżnica Elegance), перевозчика InPost и номер из письма клиента", "Укажите ожидаемую дату: бригадир увидит поставку в «Nadchodzące dostawy»"],
           "Доставка отслеживается так же, как заказанная нами.")
shot_slide("Шаг 4в. Ссылка на сайт перевозчика", "Карточка отправки → «Otwórz stronę przewoźnika»", "Байер", "buy-09-shipment-drawer-link",
           ["Откройте отправку значком «глаз» в списке", "Ссылка «Otwórz stronę przewoźnika» формируется автоматически по перевозчику и номеру", "Статус и ETA вносятся вручную («Wpisz ręcznie»), «Odśwież teraz» доступно для автоматических перевозчиков"],
           "Один клик ведёт на страницу отслеживания нужного перевозчика.")
s_links = slide()
header(s_links, "Как формируются ссылки отслеживания", "Автоотслеживание выключено: система строит ссылку для перехода на сайт перевозчика", tag="Справка", tag_color=ROLE_COL["Система"])
rows_l = [
    ("Перевозчик", "Шаблон ссылки", "Пример номера"),
    ("DPD", "tracktrace.dpd.com.pl/parcelDetails?typ=1&p1={номер}", "1000345190721"),
    ("GLS", "gls-group.com/PL/pl/sledzenie-paczek/?match={номер}", "PL9021188740"),
    ("InPost", "inpost.pl/sledzenie-przesylek?number={номер}", "620451118830442700012345"),
    ("Poczta Polska", "emonitoring.poczta-polska.pl/?numer={номер}", "20 цифр / RR123456789PL"),
    ("Orlen Paczka", "orlenpaczka.pl/sledz-paczke/?numer={номер}", "10 или 13 цифр"),
    ("DHL / UPS / FedEx", "dhl.com/pl-pl/…tracking-id={номер}, ups.com/track?tracknum={номер}, fedex.com/fedextrack/?trknbr={номер}", "по формату перевозчика"),
    ("Другой перевозчик", "ссылку вводит байер вручную в поле «Link do śledzenia»", "любой"),
]
tbl_l = s_links.shapes.add_table(len(rows_l), 3, Inches(0.45), Inches(1.25), Inches(12.4), Inches(4.6)).table
for i, w in enumerate([2.3, 7.1, 3.0]):
    tbl_l.columns[i].width = Inches(w)
for r, row in enumerate(rows_l):
    for c, val in enumerate(row):
        cell = tbl_l.cell(r, c)
        cell.text = val
        cell.vertical_anchor = MSO_ANCHOR.MIDDLE
        for para in cell.text_frame.paragraphs:
            for run in para.runs:
                run.font.size = Pt(12.5 if r else 13)
                run.font.bold = (r == 0 or c == 0)
                run.font.color.rgb = WHITE if r == 0 else INK
        cell.fill.solid()
        cell.fill.fore_color.rgb = SLATE if r == 0 else (SOFT if r % 2 else WHITE)
text(s_links, 0.45, 6.05, 12.4, 0.9, ["Если номер не подходит по формату перевозчика, отправка помечается как подозрительная, но сохраняется.",
                                      "Ссылка строится на сервере и приходит в карточке отправки и в «Nadchodzące dostawy» (кнопка «Otwórz stronę śledzenia»)."], size=12, color=NOTE, spacing=3)
footer(s_links)
wide_slide("Шаг 5. Уведомления байера", "Колокольчик в шапке", "Байер", "buy-08-notifications",
           ["«Zamówienie przekazane zakupowcowi» — проформа оплачена, позиции готовы к заказу. «Zamówienie opłacone, uzupełnij dostawę» — финансист оплатил заказ, внесите данные отправки."])

# ------------------------------------------------------------ БРИГАДИР
section_slide("Бригадир", "Tomasz Lewandowski · tomasz@loaders.dev", "Руководит бригадой на объекте. Работает с телефона, цены не видит.",
              "Знать, что привезут сегодня, и принять каждую позицию поштучно.",
              ["Открыть «Nadchodzące dostawy»", "Принять доставку: «Przyjmij»", "При браке: «Zwrot» с комментарием", "«Przyjmij wszystko» для чистой поставки", "Проверить «Historia»"])
phone_slide("Шаг 1. Ближайшие поставки и приёмка", "Нижняя панель: «Nadchodzące» и «Odbiór»", "Бригадир",
            [("for-01-upcoming", "Nadchodzące dostawy"), ("for-02-to-receive", "Odbiór dostaw")],
            ["«Nadchodzące dostawy»: поставки на 7/14/30 дней, окно времени, перевозчик, «Otwórz stronę śledzenia»", "Для доставки магазином: «Zadzwoń do kierowcy»", "«Odbiór dostaw» → «Do odbioru»: статус, принято/в пути/осталось", "У каждой отправки «Przyjmij wszystko», у позиции «Przyjmij»"],
            "Бригадир видит, что и когда привезут.")
phone_slide("Шаг 2. Частичная приёмка", "Кнопка «Przyjmij» → «Przyjęto»", "Бригадир",
            [("for-03-accept-partial", "Принято 26 из 28 м²"), ("for-04-after-accept", "Позиция «Częściowo odebrane»")],
            ["Нажмите «Przyjmij», выберите «Przyjęto»", "Укажите количество (26 из 28) и комментарий", "Нажмите «Zapisz»", "Зелёный индикатор показывает принятое, остаток 2 м² остаётся в пути"],
            "Принято 26 м², осталось получить 2 м².")
phone_slide("Шаг 3. Рекламация: возврат", "«Zwrot» с обязательным комментарием", "Бригадир",
            [("for-05-return-claim", "Возврат 2 м² с причиной"), ("for-06-after-return", "Toast: доп. поставка создана")],
            ["Снова «Przyjmij», выберите «Zwrot»", "Количество 2, комментарий обязателен: причина брака", "При необходимости добавьте фото («Dodaj zdjęcie»)", "После «Zapisz» система создаёт дополнительную доставку (toast «Utworzono dostawę uzupełniającą»)"],
            "Менеджер получает уведомление «Rozbieżność przy odbiorze dostawy».", crop_h=1688)
phone_slide("Шаг 4. «Przyjmij wszystko»", "Быстрая приёмка чистой поставки", "Бригадир",
            [("for-07-accept-all-confirm", "Подтверждение"), ("for-08-after-accept-all", "Поставка принята")],
            ["Нажмите «Przyjmij wszystko» у отправки (Roca Gap Square, 2 шт.)", "Подтвердите диалог «Przyjąć wszystko?»", "Все нерешённые строки отправки принимаются полностью, позиция «Odebrane»"],
            "Отправка закрывается автоматически статусом «Doręczona».")
phone_slide("Шаг 5. История приёмки", "Вкладка «Historia»", "Бригадир",
            [("for-09-history", "Записи приёмки")],
            ["«Historia» показывает все записи: принято, возврат, дата, автор, комментарий", "«Anuluj wpis» (void) доступно автору 24 часа, менеджеру всегда", "Записи не редактируются: исправление делается новым событием"],
            "Все количества можно проверить и проследить до человека.", crop_h=2098)

# ------------------------------------------------------------ Итог
s = slide()
header(s, "Итог и памятка по ролям", "Один процесс, пять ролей, каждый видит только своё")
cards = [
    ("Менеджер", "Планирует, согласует, выставляет проформу, контролирует сроки и маржу"),
    ("Клиент", "Принимает цену и материал, платит проформу, при желании покупает сам"),
    ("Финансист", "Подтверждает платёж клиента, оплачивает поставщиков"),
    ("Байер", "Заказывает у поставщиков не дороже цены продажи, ведёт отправки"),
    ("Бригадир", "Принимает поштучно, оформляет возврат, ничего не знает про цены"),
]
for i, (r, d) in enumerate(cards):
    x = 0.45 + i * 2.5
    rect(s, x, 1.3, 2.4, 2.6, SOFT, MSO_SHAPE.ROUNDED_RECTANGLE).adjustments[0] = 0.05
    rect(s, x, 1.3, 2.4, 0.1, ROLE_COL[r])
    text(s, x + 0.1, 1.5, 2.2, 0.5, r, size=17, bold=True, color=ROLE_COL[r], align=PP_ALIGN.CENTER)
    text(s, x + 0.12, 2.1, 2.16, 1.7, d, size=12.5, color=NOTE, align=PP_ALIGN.CENTER)
bullet_box(s, 0.45, 4.15, 6.1, 2.75, "Ключевые правила", [
    "Нет полной оплаты проформы — позиция не уходит к байеру",
    "Закупочная цена и маржа скрыты от клиента и бригадира",
    "Приёмка поштучно, записи неизменяемы, void 24 часа",
    "Возврат создаёт доп. поставку, а не правит старую",
])
bullet_box(s, 6.75, 4.15, 6.1, 2.75, "Особенности тестовой сборки", [
    "Автотрекинг перевозчиков выключен: статус и ETA вносятся вручную, ссылка на сайт перевозчика формируется автоматически",
    "Список проектов для байера открыт только на чтение, без состава команды",
    "PDF проформы формируется и скачивается, польские буквы печатаются корректно",
    "Данные демо: проект «Jana Kazimierza 64a», пароль у всех alejano123",
], color=AMBER, size=12.5)
footer(s)

prs.save(OUT)
print("saved", os.path.abspath(OUT), "slides:", len(prs.slides))
