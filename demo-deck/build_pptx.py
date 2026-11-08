# -*- coding: utf-8 -*-
"""Генерирует PPTX из slides.py (16:9), встраивая реальные скриншоты."""
import os
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from PIL import Image

from slides import SLIDES, DECK_TITLE, DECK_SUBTITLE

HERE = os.path.dirname(os.path.abspath(__file__))
SCREENS = os.path.join(HERE, "screens")
OUT = os.path.join(HERE, "foremen-demo.pptx")

# 16:9
SW = Inches(13.333)
SH = Inches(7.5)

# Palette
INK = RGBColor(0x0F, 0x17, 0x2A)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
BRAND = RGBColor(0x25, 0x63, 0xEB)
BRAND_DK = RGBColor(0x1D, 0x4E, 0xD8)
MUTED = RGBColor(0x64, 0x74, 0x8B)
SOFT = RGBColor(0xF1, 0xF5, 0xF9)
SLATE = RGBColor(0x1E, 0x29, 0x3B)
LIGHTBLUE = RGBColor(0xBA, 0xE6, 0xFD)
NOTE_INK = RGBColor(0x33, 0x41, 0x55)
PANEL_BG = RGBColor(0xE9, 0xEE, 0xF5)

prs = Presentation()
prs.slide_width = SW
prs.slide_height = SH
BLANK = prs.slide_layouts[6]


def add_slide():
    return prs.slides.add_slide(BLANK)


def fill_bg(slide, color):
    slide.background.fill.solid()
    slide.background.fill.fore_color.rgb = color


def rect(slide, x, y, w, h, color):
    from pptx.enum.shapes import MSO_SHAPE
    sh = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, x, y, w, h)
    sh.fill.solid()
    sh.fill.fore_color.rgb = color
    sh.line.fill.background()
    sh.shadow.inherit = False
    return sh


def textbox(slide, x, y, w, h, anchor=MSO_ANCHOR.TOP):
    tb = slide.shapes.add_textbox(x, y, w, h)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    return tb, tf


def set_para(p, text, size, color, bold=False, space_after=6, align=PP_ALIGN.LEFT, line=1.1):
    p.text = text
    p.alignment = align
    p.space_after = Pt(space_after)
    p.line_spacing = line
    for r in p.runs:
        r.font.size = Pt(size)
        r.font.bold = bold
        r.font.color.rgb = color
        r.font.name = "Arial"
    return p


def add_pageno(slide, idx, total, dark=False):
    tb, tf = textbox(slide, SW - Inches(1.6), SH - Inches(0.5), Inches(1.4), Inches(0.35))
    p = tf.paragraphs[0]
    set_para(p, f"{idx} / {total}", 11, (RGBColor(0xCB,0xD5,0xE1) if dark else MUTED), align=PP_ALIGN.RIGHT)


def img_fit(path, max_w, max_h):
    """Return (w, h) EMU fitting image into max box, preserving aspect."""
    with Image.open(path) as im:
        iw, ih = im.size
    ar = iw / ih
    box_ar = max_w / max_h
    if ar > box_ar:
        w = max_w
        h = int(max_w / ar)
    else:
        h = max_h
        w = int(max_h * ar)
    return w, h


# ───────────────────────── Cover ─────────────────────────

def build_cover(slide, s, idx, total):
    fill_bg(slide, SLATE)
    # brand band accent
    rect(slide, 0, 0, SW, Inches(0.12), BRAND)

    # logo square
    logo = rect(slide, Inches(0.9), Inches(1.5), Inches(0.6), Inches(0.6), BRAND)
    lf = logo.text_frame
    set_para(lf.paragraphs[0], "F", 24, WHITE, bold=True, align=PP_ALIGN.CENTER, space_after=0)
    lf.vertical_anchor = MSO_ANCHOR.MIDDLE

    tb, tf = textbox(slide, Inches(1.65), Inches(1.5), Inches(5), Inches(0.6), MSO_ANCHOR.MIDDLE)
    set_para(tf.paragraphs[0], "Foremen", 22, WHITE, bold=True, space_after=0)

    tb, tf = textbox(slide, Inches(0.9), Inches(2.5), Inches(11.2), Inches(2.6))
    set_para(tf.paragraphs[0], s["title"], 40, WHITE, bold=True, space_after=10, line=1.08)
    p = tf.add_paragraph()
    set_para(p, s.get("subtitle", ""), 19, RGBColor(0xDB,0xE4,0xF5), space_after=16, line=1.2)
    for b in s.get("bullets", []):
        bp = tf.add_paragraph()
        set_para(bp, "→  " + b, 16, RGBColor(0xEA,0xF2,0xFF), space_after=6, line=1.25)

    add_pageno(slide, idx, total, dark=True)


# ───────────────────────── Chapter ─────────────────────────

def build_chapter(slide, s, idx, total):
    fill_bg(slide, INK)
    rect(slide, 0, 0, Inches(0.22), SH, BRAND)

    tb, tf = textbox(slide, Inches(1.0), Inches(0.9), Inches(11.3), Inches(5.7), MSO_ANCHOR.MIDDLE)
    set_para(tf.paragraphs[0], "FOREMEN · ДЕМОНСТРАЦИЯ", 12, RGBColor(0x93,0xC5,0xFD), space_after=16)
    p = tf.add_paragraph()
    set_para(p, s["title"], 34, WHITE, bold=True, space_after=14, line=1.12)
    if s.get("subtitle"):
        p2 = tf.add_paragraph()
        set_para(p2, s["subtitle"], 18, RGBColor(0xCB,0xD5,0xE1), line=1.3)

    add_pageno(slide, idx, total, dark=True)


# ───────────────────────── Feature ─────────────────────────

def build_feature(slide, s, idx, total):
    fill_bg(slide, WHITE)
    # right panel background
    panel_x = Inches(5.2)
    rect(slide, panel_x, 0, SW - panel_x, SH, PANEL_BG)

    # left text
    tb, tf = textbox(slide, Inches(0.55), Inches(0.6), Inches(4.5), Inches(6.3), MSO_ANCHOR.MIDDLE)
    set_para(tf.paragraphs[0], s["title"], 24, INK, bold=True, space_after=14, line=1.12)
    for b in s.get("bullets", []):
        bp = tf.add_paragraph()
        set_para(bp, "•  " + b, 14, SLATE, space_after=9, line=1.3)

    # note box
    if s.get("note"):
        ny = Inches(5.75)
        note = rect(slide, Inches(0.55), ny, Inches(4.4), Inches(1.1), SOFT)
        # accent bar
        rect(slide, Inches(0.55), ny, Inches(0.06), Inches(1.1), RGBColor(0x0E,0xA5,0xE9))
        nf = note.text_frame
        nf.word_wrap = True
        nf.margin_left = Inches(0.18)
        nf.margin_right = Inches(0.12)
        nf.vertical_anchor = MSO_ANCHOR.MIDDLE
        set_para(nf.paragraphs[0], s["note"], 11.5, NOTE_INK, line=1.25, space_after=0)

    # screenshot
    if s.get("image"):
        path = os.path.join(SCREENS, s["image"])
        if os.path.exists(path):
            max_w = SW - panel_x - Inches(0.7)
            max_h = SH - Inches(0.9)
            w, h = img_fit(path, int(max_w), int(max_h))
            x = panel_x + ((SW - panel_x) - w) // 2
            y = (SH - h) // 2
            slide.shapes.add_picture(path, Emu(int(x)), Emu(int(y)), width=Emu(int(w)), height=Emu(int(h)))

    add_pageno(slide, idx, total)


def build():
    total = len(SLIDES)
    for i, s in enumerate(SLIDES):
        slide = add_slide()
        kind = s["kind"]
        if kind == "cover":
            build_cover(slide, s, i + 1, total)
        elif kind == "chapter":
            build_chapter(slide, s, i + 1, total)
        else:
            build_feature(slide, s, i + 1, total)
    prs.save(OUT)
    print("wrote", OUT, f"({total} slides)")


if __name__ == "__main__":
    build()
