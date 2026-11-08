# -*- coding: utf-8 -*-
"""Генерирует самодостаточный HTML-деск из slides.py."""
import html
import os
from slides import SLIDES, DECK_TITLE, DECK_SUBTITLE

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "foremen-demo.html")


def esc(s: str) -> str:
    return html.escape(s or "")


def bullets_html(bullets):
    if not bullets:
        return ""
    items = "\n".join(f"      <li>{esc(b)}</li>" for b in bullets)
    return f"    <ul class='bullets'>\n{items}\n    </ul>"


def slide_html(idx: int, total: int, s: dict) -> str:
    kind = s["kind"]
    num = f"<div class='pageno'>{idx} / {total}</div>"

    if kind == "cover":
        return f"""<section class="slide cover" data-idx="{idx}">
  <div class="cover-inner">
    <div class="brand"><span class="logo">F</span> Foremen</div>
    <h1>{esc(s['title'])}</h1>
    <p class="subtitle">{esc(s.get('subtitle',''))}</p>
    {bullets_html(s.get('bullets'))}
  </div>
  {num}
</section>"""

    if kind == "chapter":
        return f"""<section class="slide chapter" data-idx="{idx}">
  <div class="chapter-inner">
    <div class="chapter-kicker">Foremen · демонстрация</div>
    <h1>{esc(s['title'])}</h1>
    <p class="subtitle">{esc(s.get('subtitle',''))}</p>
  </div>
  {num}
</section>"""

    # feature
    img = ""
    if s.get("image"):
        src = f"screens/{s['image']}"
        img = f"<div class='shot'><img loading='lazy' src='{src}' alt='{esc(s['title'])}'></div>"
    note = f"<p class='note'>{esc(s['note'])}</p>" if s.get("note") else ""
    return f"""<section class="slide feature" data-idx="{idx}">
  <div class="feature-grid">
    <div class="feature-text">
      <h2>{esc(s['title'])}</h2>
      {bullets_html(s.get('bullets'))}
      {note}
    </div>
    {img}
  </div>
  {num}
</section>"""


def build():
    total = len(SLIDES)
    slides = "\n".join(slide_html(i + 1, total, s) for i, s in enumerate(SLIDES))
    doc = f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(DECK_TITLE)}</title>
<style>
  :root {{
    --bg:#0f172a; --panel:#ffffff; --ink:#0f172a; --muted:#64748b;
    --brand:#2563eb; --brand2:#1d4ed8; --line:#e2e8f0; --soft:#f1f5f9;
    --accent:#0ea5e9;
  }}
  * {{ box-sizing:border-box; }}
  html,body {{ margin:0; padding:0; background:#0b1220; color:var(--ink);
    font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif; }}
  .deck {{ }}
  .slide {{ position:relative; width:100%; max-width:1280px; margin:28px auto; aspect-ratio:16/9;
    background:var(--panel); border-radius:18px; overflow:hidden;
    box-shadow:0 20px 60px rgba(0,0,0,.45); scroll-snap-align:center; }}
  .pageno {{ position:absolute; right:22px; bottom:16px; color:var(--muted);
    font-size:13px; letter-spacing:.03em; }}

  /* Cover */
  .cover {{ background:linear-gradient(135deg,#1e293b 0%, #2563eb 120%); color:#fff; }}
  .cover-inner {{ padding:72px 72px; height:100%; display:flex; flex-direction:column; justify-content:center; }}
  .brand {{ font-size:22px; font-weight:700; letter-spacing:.02em; opacity:.95; display:flex; align-items:center; gap:12px; }}
  .logo {{ display:inline-grid; place-items:center; width:40px; height:40px; border-radius:11px;
    background:rgba(255,255,255,.16); font-weight:800; }}
  .cover h1 {{ font-size:48px; line-height:1.1; margin:26px 0 12px; max-width:20ch; }}
  .cover .subtitle {{ font-size:21px; opacity:.9; margin:0 0 26px; max-width:46ch; }}
  .cover .bullets {{ list-style:none; padding:0; margin:0; display:flex; flex-direction:column; gap:10px; }}
  .cover .bullets li {{ font-size:18px; color:#eaf2ff; padding-left:26px; position:relative; }}
  .cover .bullets li::before {{ content:"→"; position:absolute; left:0; top:0; width:auto; height:auto;
    background:none; border-radius:0; color:#bae6fd; }}

  /* Chapter */
  .chapter {{ background:radial-gradient(1200px 600px at 10% -10%, #1d4ed8 0%, #0f172a 60%); color:#fff; }}
  .chapter-inner {{ padding:0 80px; height:100%; display:flex; flex-direction:column; justify-content:center; }}
  .chapter-kicker {{ text-transform:uppercase; letter-spacing:.22em; font-size:13px; color:#93c5fd; margin-bottom:18px; }}
  .chapter h1 {{ font-size:42px; line-height:1.15; margin:0 0 16px; max-width:24ch; }}
  .chapter .subtitle {{ font-size:20px; color:#cbd5e1; margin:0; max-width:60ch; }}

  /* Feature */
  .feature-grid {{ display:grid; grid-template-columns:0.9fr 1.4fr; height:100%; }}
  .feature-text {{ padding:52px 44px; display:flex; flex-direction:column; justify-content:center; background:var(--panel); }}
  .feature-text h2 {{ font-size:30px; line-height:1.15; margin:0 0 20px; color:var(--ink); }}
  .bullets {{ margin:0; padding:0 0 0 2px; list-style:none; display:flex; flex-direction:column; gap:13px; }}
  .bullets li {{ font-size:17px; line-height:1.45; color:#1e293b; padding-left:26px; position:relative; }}
  .bullets li::before {{ content:""; position:absolute; left:2px; top:9px; width:9px; height:9px;
    border-radius:3px; background:var(--brand); }}
  .note {{ margin-top:22px; padding:12px 16px; background:var(--soft); border-left:3px solid var(--accent);
    border-radius:8px; color:#334155; font-size:14px; line-height:1.4; }}
  .shot {{ background:#e9eef5; display:flex; align-items:center; justify-content:center; padding:22px;
    border-left:1px solid var(--line); overflow:auto; }}
  .shot img {{ max-width:100%; max-height:100%; border-radius:10px; box-shadow:0 10px 30px rgba(15,23,42,.22);
    border:1px solid #cbd5e1; }}

  /* Toolbar */
  .toolbar {{ position:fixed; top:14px; left:50%; transform:translateX(-50%); z-index:50;
    background:rgba(15,23,42,.85); color:#e2e8f0; border:1px solid #334155; border-radius:999px;
    padding:7px 14px; display:flex; gap:14px; align-items:center; font-size:13px; backdrop-filter:blur(6px); }}
  .toolbar b {{ color:#fff; }}
  .toolbar .hint {{ color:#94a3b8; }}

  @media print {{
    html,body {{ background:#fff; }}
    .toolbar {{ display:none; }}
    .slide {{ box-shadow:none; margin:0; border-radius:0; max-width:none;
      page-break-after:always; aspect-ratio:auto; height:100vh; }}
  }}
</style>
</head>
<body>
  <div class="toolbar">
    <span><b>Foremen</b> демо</span>
    <span class="hint">← / → листать · F — полноэкранный</span>
  </div>
  <div class="deck" id="deck">
{slides}
  </div>
<script>
  const slides = Array.from(document.querySelectorAll('.slide'));
  let cur = 0;
  function go(i) {{
    cur = Math.max(0, Math.min(slides.length - 1, i));
    slides[cur].scrollIntoView({{behavior:'smooth', block:'center'}});
  }}
  document.addEventListener('keydown', (e) => {{
    if (e.key === 'ArrowRight' || e.key === 'PageDown' || e.key === ' ') {{ e.preventDefault(); go(cur+1); }}
    else if (e.key === 'ArrowLeft' || e.key === 'PageUp') {{ e.preventDefault(); go(cur-1); }}
    else if (e.key === 'Home') {{ go(0); }}
    else if (e.key === 'End') {{ go(slides.length-1); }}
    else if (e.key.toLowerCase() === 'f') {{
      if (!document.fullscreenElement) document.documentElement.requestFullscreen();
      else document.exitFullscreen();
    }}
  }});
  // Track current slide on manual scroll
  const io = new IntersectionObserver((entries) => {{
    entries.forEach(en => {{ if (en.isIntersecting) cur = slides.indexOf(en.target); }});
  }}, {{threshold:0.6}});
  slides.forEach(s => io.observe(s));
</script>
</body>
</html>"""
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(doc)
    print("wrote", OUT, f"({len(SLIDES)} slides)")


if __name__ == "__main__":
    build()
