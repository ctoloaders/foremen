#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FOR-04-RESEARCH-material-norms — extraction helper (step 1).

Reads the Excel price list export
`docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv` and extracts the full work
catalog: each work's positional LP code (e.g. "1,01"), its category, PL/RU
names, unit of measure (JM), and any material-consumption norms already filled
in the Excel (MATERIAŁ N / NA JM / ILOŚĆ columns).

Output: /tmp/for-04-research-works.json + a coverage summary to stdout. This is
the skeleton the research fills in (works without Excel norms get researched).
"""
import csv
import json
import re
from collections import Counter

CSV_PATH = "docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv"
OUT_PATH = "/tmp/for-04-research-works.json"

CAT_RE = re.compile(r"^\d+$")        # category header LP: "1", "2", ...
WORK_RE = re.compile(r"^\d+,\d+$")   # work LP: "1,01"

# Material-norm triplet start columns (MATERIAŁ N, NA JM, ILOŚĆ).
NORM_BASES = (45, 48, 51, 54, 57)


def norm(s):
    return (s or "").strip()


def main():
    with open(CSV_PATH, newline="", encoding="utf-8") as f:
        rows = list(csv.reader(f))

    works = []
    cur_cat = None

    for r in rows[1:]:
        if len(r) < 60:
            r = r + [""] * (60 - len(r))
        lp = norm(r[0])
        zakres = norm(r[1])
        ru = norm(r[2])
        jm = norm(r[4])

        if CAT_RE.match(lp) and zakres and not WORK_RE.match(lp):
            cur_cat = (lp, zakres, ru)
            continue

        if WORK_RE.match(lp):
            materials = []
            for base in NORM_BASES:
                name = norm(r[base])
                unit = norm(r[base + 1])
                qty = norm(r[base + 2])
                if name:
                    materials.append({"name": name, "unit": unit, "qty": qty})
            works.append({
                "lp": lp,
                "cat": cur_cat[0] if cur_cat else None,
                "catPL": cur_cat[1] if cur_cat else None,
                "catRU": cur_cat[2] if cur_cat else None,
                "pl": zakres,
                "ru": ru,
                "jm": jm,
                "materials": materials,
            })

    with_norms = [w for w in works if w["materials"]]
    by_cat = Counter(w["cat"] for w in works)
    norms_by_cat = Counter(w["cat"] for w in with_norms)

    print("TOTAL WORKS:", len(works))
    print("WORKS WITH >=1 EXCEL NORM:", len(with_norms))
    print("WORKS WITHOUT NORMS (to research):", len(works) - len(with_norms))
    print()
    print("cat | works | withNorms")
    for c in sorted(by_cat, key=lambda x: int(x)):
        print(f"{c:>3} | {by_cat[c]:>5} | {norms_by_cat.get(c, 0):>3}")

    json.dump(works, open(OUT_PATH, "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print("\nsaved", OUT_PATH)


if __name__ == "__main__":
    main()
