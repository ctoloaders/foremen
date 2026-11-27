import sys
from pptx import Presentation
from pptx.util import Emu
from PIL import ImageFont

F = "/System/Library/Fonts/Supplemental/Arial.ttf"
FB = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
cache = {}


def font(sz, b):
    k = (round(sz * 4), b)
    if k not in cache:
        cache[k] = ImageFont.truetype(FB if b else F, int(sz * 4))
    return cache[k]


def width_pt(t, sz, b):
    return font(sz, b).getlength(t) / 4.0


def lines_needed(text, sz, b, wpt):
    n = 0
    for para in text.split("\n"):
        words = para.split(" ")
        cur = ""
        cnt = 1
        for w in words:
            t = (cur + " " + w).strip()
            if width_pt(t, sz, b) <= wpt:
                cur = t
            else:
                cnt += 1
                cur = w
        n += cnt
    return n


prs = Presentation(sys.argv[1])
bad = 0
for i, sl in enumerate(prs.slides, 1):
    for sh in sl.shapes:
        if not sh.has_text_frame or not sh.text_frame.text.strip():
            continue
        tf = sh.text_frame
        wpt = Emu(sh.width).pt - Emu(tf.margin_left or 91440).pt - Emu(tf.margin_right or 91440).pt
        total = 0
        for p in tf.paragraphs:
            if not p.runs:
                continue
            sz = p.runs[0].font.size.pt if p.runs[0].font.size else 18
            b = bool(p.runs[0].font.bold)
            txt = "".join(r.text for r in p.runs)
            ln = lines_needed(txt, sz, b, wpt)
            sa = p.space_after.pt if p.space_after else 0
            total += ln * sz * 1.2 + sa
        hpt = Emu(sh.height).pt
        if total > hpt * 1.02 + 2:
            bad += 1
            print(f"slide {i}: overflow {total:.0f}pt > {hpt:.0f}pt :: {tf.text[:60]!r}")
print("checked; overflows:", bad)
