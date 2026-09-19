#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FOR-04-RESEARCH-material-norms — merge + validate (step 3).

Merges the 13 per-category CSV fragments produced by the research agents into
three final artifacts and validates cross-file integrity:

  work-material-norms.csv        (all norm rows, all categories)
  construction-materials.csv     (deduped construction materials)
  new-measurement-units.csv       (deduped new units)

Validation performed:
  - every norms row has non-empty work_code, material_ref, material_unit,
    norm_qty (float, dot decimal), justification_pl/ru, source_type, source_doc,
    source_ref;
  - source_url present for producer_tds/knr/retail, may be empty for excel/expert;
  - every construction-branch material_ref resolves to a row in the merged
    construction materials file;
  - every (work_code, branch, package) has 1..10 materials;
  - every work_code exists in the extracted work catalog
    (/tmp/for-04-research-works.json);
  - reports coverage: works with norms, works intentionally without.
"""
import csv
import glob
import json
import os
import re
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC_DIR = os.path.abspath(os.path.join(
    HERE, "..", "..", "..", ".kiro", "specs", "FOR-04-base-entities",
    "FOR-04-RESEARCH-material-norms"))
WORKS_JSON = "/tmp/for-04-research-works.json"

NORMS_COLS = ["work_code", "work_pl", "work_unit", "branch", "material_type",
              "material_ref", "material_unit", "norm_qty", "packages",
              "waste_pct", "justification_pl", "justification_ru",
              "source_type", "source_doc", "source_url", "source_ref"]
MAT_COLS = ["name_pl", "name_ru", "type", "producer", "seller", "packages",
            "unit", "currency", "purchase_price", "retail_gross", "retail_net",
            "website", "price_source_ref", "justification_pl", "justification_ru"]
UNIT_COLS = ["code", "name_pl", "name_ru", "note"]

PACKAGES = {"budget", "norm", "lux"}
URL_REQUIRED = {"producer_tds", "knr", "retail"}


def read_fragments(pattern):
    rows = []
    for path in sorted(glob.glob(os.path.join(SPEC_DIR, pattern))):
        with open(path, newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                row["_src"] = os.path.basename(path)
                rows.append(row)
    return rows


def write_csv(path, cols, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow(r)


def main():
    problems = []

    norms = read_fragments("work-material-norms-cat*.csv")
    mats = read_fragments("construction-materials-cat*.csv")
    units = read_fragments("new-measurement-units-cat*.csv")

    # --- dedup construction materials by (name_pl, type, seller) ---
    seen_mat = {}
    merged_mats = []
    for m in mats:
        key = (m["name_pl"].strip(), m["type"].strip(), m["seller"].strip())
        if key not in seen_mat:
            seen_mat[key] = m
            merged_mats.append(m)
    mat_names = {m["name_pl"].strip() for m in merged_mats}

    # --- dedup units by code ---
    seen_unit = {}
    for u in units:
        code = u["code"].strip()
        if code and code not in seen_unit:
            seen_unit[code] = u
    merged_units = list(seen_unit.values())

    # --- validate norms ---
    combo = defaultdict(int)  # (work, branch, package) -> material count
    works_with_norms = set()
    for r in norms:
        wc = r["work_code"].strip()
        works_with_norms.add(wc)
        for col in ("work_code", "material_ref", "material_unit", "norm_qty",
                    "justification_pl", "justification_ru", "source_type",
                    "source_doc", "source_ref"):
            if not (r.get(col) or "").strip():
                problems.append(f"{r['_src']} {wc}: empty {col}")
        try:
            float(r["norm_qty"])
        except ValueError:
            problems.append(f"{r['_src']} {wc}: norm_qty not float: {r['norm_qty']!r}")
        st = r["source_type"].strip()
        if st in URL_REQUIRED and not r["source_url"].strip():
            problems.append(f"{r['_src']} {wc}: {st} row missing source_url")
        # construction materials must resolve; finishing refs come from the
        # Materiały pakiety CSV (out of scope for this file) so skip those.
        if r["branch"].strip() == "construction" and r["material_ref"].strip() not in mat_names:
            problems.append(f"{r['_src']} {wc}: construction material_ref not in materials file: {r['material_ref']!r}")
        for pkg in (p.strip() for p in r["packages"].split(",") if p.strip()):
            if pkg not in PACKAGES:
                problems.append(f"{r['_src']} {wc}: bad package {pkg!r}")
            combo[(wc, r["branch"].strip(), pkg)] += 1

    for (wc, br, pkg), n in combo.items():
        if n > 10:
            problems.append(f"{wc}/{br}/{pkg}: {n} materials (>10)")

    # --- coverage vs catalog ---
    catalog = {w["lp"].replace(",", ".") for w in json.load(open(WORKS_JSON, encoding="utf-8"))}
    unknown = works_with_norms - catalog
    for wc in sorted(unknown):
        problems.append(f"norm references unknown work_code: {wc}")
    no_norm = sorted(catalog - works_with_norms, key=lambda x: [int(p) for p in x.split(".")])

    # --- write merged outputs ---
    write_csv(os.path.join(SPEC_DIR, "work-material-norms.csv"), NORMS_COLS, norms)
    write_csv(os.path.join(SPEC_DIR, "construction-materials.csv"), MAT_COLS, merged_mats)
    write_csv(os.path.join(SPEC_DIR, "new-measurement-units.csv"), UNIT_COLS, merged_units)

    print(f"norm rows           : {len(norms)}")
    print(f"materials (deduped) : {len(merged_mats)} (from {len(mats)} fragment rows)")
    print(f"new units (deduped) : {len(merged_units)} -> {sorted(seen_unit)}")
    print(f"works with norms    : {len(works_with_norms)} / {len(catalog)} catalog works")
    print(f"works WITHOUT norms : {len(no_norm)} -> {no_norm}")
    print(f"validation problems : {len(problems)}")
    for p in problems[:50]:
        print("  !", p)


if __name__ == "__main__":
    main()
