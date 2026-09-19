#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FOR-04-18 (Requirement 6.8) — CSV-to-SQL seed generator for finishing_materials.

Repeatable generation step: reads docs/Materiały pakiety - Lista.csv (581 data rows)
and emits foremen-backend/database_files/changesets/062-seed-finishing-materials.xml,
a Liquibase changeset that seeds finishing_materials + finishing_material_packages.

Rerun this script whenever the source CSV changes; commit the regenerated changeset.

Generation rules (mirror design.md "CSV seed model"):

  Column mapping:
    Kategoria      -> category   (resolve material_categories.name_pl)
    Materiał       -> material   (resolve materials.name_pl)
    Typ            -> type       (resolve material_types.name_pl, optional)
    Producent      -> producer   (resolve material_producers.name_pl, optional)
    Model          -> model
    SKU            -> sku
    Cechy          -> features
    Cena zakup     -> purchase_price
    Cena detal brutto -> retail_gross
    Cena detal netto  -> retail_net
    Cena detal / m2   -> (no stored field) retail_net fallback only
    Link           -> link
    Pakiet         -> packages   (offer_packages join)
    unit           -> fixed 'm2' measurement unit (the CSV is per-m2 data)

  Prices: comma is the decimal separator ("53,61" -> 53.61); empty cell -> SQL NULL.
  retail_net fallback: when "Cena detal netto" empty, use "Cena detal / m2";
                       when both empty -> NULL.
  Pakiet normalization: split on comma; per token strip whitespace, surrounding
    double-quotes and a leading "+ " marker; map the packages by localized token:
      Budget            -> offer_packages.code = 'budget'
      Standard/Standart -> offer_packages.code = 'norm'   (the seeded mid-tier COMFORT package)
      Lux               -> offer_packages.code = 'lux'
    duplicates collapsed.
  Blank Kategoria rows are skipped (Requirement 6.9).

  Reference resolution (Requirement 6.5, 6.6): each insert is a single INSERT ... SELECT
  whose FROM/WHERE resolves category_id/material_id/type_id/producer_id/unit_id by
  sub-select against the existing FOR-04-16 / FOR-04-02 dictionaries. The required
  references (category, material, unit) are inner joins, so a row whose required value
  is absent inserts NOTHING. Optional type/producer resolve via scalar sub-selects that
  yield NULL when absent/blank. finishing_material_packages rows are inserted by
  resolving the just-inserted finishing_material row (matched on its deterministic
  seed_key marker stored transiently) — implemented by resolving the package id and the
  material row in the same INSERT ... SELECT.

  Idempotency (Requirement 6.7): the whole changeset carries a preConditions
  onFail="MARK_RAN" sqlCheck on SELECT COUNT(*) FROM finishing_materials, so it applies
  exactly once on a fresh DB and is a no-op on re-run.
"""
import csv
import os
import sys
from xml.sax.saxutils import escape

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
CSV_PATH = os.path.join(REPO_ROOT, "docs", "Materiały pakiety - Lista.csv")
OUT_PATH = os.path.abspath(
    os.path.join(HERE, "..", "changesets", "062-seed-finishing-materials.xml")
)

# CSV Pakiet token -> offer_packages.code
PACKAGE_CODE = {
    "budget": "budget",
    "standard": "norm",
    "standart": "norm",
    "lux": "lux",
}


def parse_price(cell):
    """Comma-decimal price -> Python string decimal, or None when empty."""
    if cell is None:
        return None
    v = cell.strip().strip('"').strip()
    if v == "":
        return None
    v = v.replace(" ", "").replace("\u00a0", "")
    v = v.replace(",", ".")
    try:
        f = float(v)
    except ValueError:
        return None
    return f"{f:.2f}"


def parse_packages(cell):
    """Normalize the multi-value Pakiet cell into a set of offer_packages codes."""
    if not cell:
        return []
    codes = []
    seen = set()
    for raw in cell.split(","):
        tok = raw.strip().strip('"').strip()
        if tok.startswith("+ "):
            tok = tok[2:].strip()
        elif tok.startswith("+"):
            tok = tok[1:].strip()
        tok = tok.strip('"').strip()
        if not tok:
            continue
        code = PACKAGE_CODE.get(tok.lower())
        if code is None:
            # Unknown token: skip (the seed resolves only known packages).
            continue
        if code not in seen:
            seen.add(code)
            codes.append(code)
    return codes


def sql_str(value):
    """SQL string literal or NULL. Doubles single quotes for SQL escaping."""
    if value is None:
        return "NULL"
    v = value.strip()
    if v == "":
        return "NULL"
    return "'" + v.replace("'", "''") + "'"


def sql_num(value):
    return value if value is not None else "NULL"


def xml_text(s):
    return escape(s)


def main():
    with open(CSV_PATH, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)

    stats = {
        "data_rows": 0,
        "blank_category": 0,
        "emitted": 0,
        "package_rows": 0,
        "no_packages": 0,
    }

    material_inserts = []  # (seq, sql)
    package_inserts = []

    for row in rows:
        stats["data_rows"] += 1
        category = (row.get("Kategoria") or "").strip()
        if category == "":
            stats["blank_category"] += 1
            continue

        material = (row.get("Materiał") or "").strip()
        typ = (row.get("Typ") or "").strip()
        producer = (row.get("Producent") or "").strip()
        model = (row.get("Model") or "").strip()
        sku = (row.get("SKU") or "").strip()
        features = (row.get("Cechy") or "").strip()
        link = (row.get("Link") or "").strip()

        purchase_price = parse_price(row.get("Cena zakup"))
        retail_gross = parse_price(row.get("Cena detal brutto"))
        retail_net = parse_price(row.get("Cena detal netto"))
        if retail_net is None:
            retail_net = parse_price(row.get("Cena detal / m2"))

        packages = parse_packages(row.get("Pakiet"))
        if not packages:
            stats["no_packages"] += 1

        seq = stats["emitted"] + 1
        stats["emitted"] += 1

        # A deterministic marker to correlate the finishing_materials row with its
        # package join rows within this single generated seed. Stored transiently in
        # the "features" is NOT viable; instead we correlate the package insert by
        # re-resolving the same reference tuple + model/sku + seq via a unique
        # generated sku-less match. To keep correlation robust, we use the ordered
        # row identity: the (category, material, model, link) tuple is not guaranteed
        # unique, so we resolve the package join by matching on the row's natural
        # columns AND the created_by seed tag with the row's seq embedded in a
        # sentinel comment is not possible in SQL. We therefore correlate on the
        # full column tuple, which is unique enough for the seed; ties simply attach
        # the package to all matching rows (idempotent set semantics).

        # Optional type/producer: emit a scalar sub-select when the CSV value is
        # present, or a literal NULL when it is blank (avoids a `name_pl = NULL`
        # comparison, which would always yield NULL anyway).
        type_expr = (
            f"(SELECT id FROM material_types WHERE name_pl = {sql_str(typ)} LIMIT 1)"
            if typ.strip() else "NULL"
        )
        producer_expr = (
            f"(SELECT id FROM material_producers WHERE name_pl = {sql_str(producer)} LIMIT 1)"
            if producer.strip() else "NULL"
        )

        # Build the material INSERT ... SELECT with required inner joins.
        select = f"""            SELECT
                cat.id, mat.id,
                {type_expr},
                {producer_expr},
                u.id,
                {sql_str(model)}, {sql_str(sku)}, {sql_str(features)},
                {sql_num(purchase_price)}, {sql_num(retail_gross)}, {sql_num(retail_net)},
                {sql_str(link)}, TRUE, NOW(), 'seed:for-04-18'
            FROM material_categories cat, materials mat, measurement_units u
            WHERE cat.name_pl = {sql_str(category)}
              AND mat.name_pl = {sql_str(material)}
              AND u.code = 'm2'
              AND NOT EXISTS (
                  SELECT 1 FROM finishing_materials fm
                  WHERE fm.category_id = cat.id
                    AND fm.material_id = mat.id
                    AND fm.model IS NOT DISTINCT FROM {sql_str(model)}
                    AND fm.link IS NOT DISTINCT FROM {sql_str(link)}
                    AND fm.retail_net IS NOT DISTINCT FROM {sql_num(retail_net)}
              );"""
        insert_sql = (
            "            INSERT INTO finishing_materials (\n"
            "                category_id, material_id, type_id, producer_id, unit_id,\n"
            "                model, sku, features,\n"
            "                purchase_price, retail_gross, retail_net,\n"
            "                link, active, created_date, created_by)\n"
            + select
        )
        material_inserts.append(insert_sql)

        for code in packages:
            stats["package_rows"] += 1
            # Attach the package to the finishing_materials row(s) matching this
            # row's natural key that do not yet have the join row. Set semantics
            # (composite PK) + NOT EXISTS make this idempotent.
            pkg_sql = f"""            INSERT INTO finishing_material_packages (finishing_material_id, offer_package_id)
            SELECT fm.id, op.id
            FROM finishing_materials fm
            JOIN material_categories cat ON cat.id = fm.category_id
            JOIN materials mat ON mat.id = fm.material_id
            JOIN offer_packages op ON op.code = '{code}'
            WHERE cat.name_pl = {sql_str(category)}
              AND mat.name_pl = {sql_str(material)}
              AND fm.model IS NOT DISTINCT FROM {sql_str(model)}
              AND fm.link IS NOT DISTINCT FROM {sql_str(link)}
              AND fm.retail_net IS NOT DISTINCT FROM {sql_num(retail_net)}
              AND NOT EXISTS (
                  SELECT 1 FROM finishing_material_packages x
                  WHERE x.finishing_material_id = fm.id AND x.offer_package_id = op.id
              );"""
            package_inserts.append(pkg_sql)

    # Emit the changeset XML.
    header = f"""<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <!--
        FOR-04-18 (Requirement 6, esp. 6.8): GENERATED finishing-material data seed.

        This file is NOT hand-written. It is generated by the repeatable step
        foremen-backend/database_files/generators/gen_finishing_materials_seed.py
        from docs/Materiały pakiety - Lista.csv. Rerun that script and commit the
        regenerated file whenever the source CSV changes.

        Generation summary (see the generator header for the full rules):
          - Column mapping: Kategoria->category, Materiał->material, Typ->type,
            Producent->producer, Model->model, SKU->sku, Cechy->features,
            Cena zakup->purchase_price, Cena detal brutto->retail_gross,
            Cena detal netto->retail_net, Link->link, Pakiet->packages. The
            "Cena detal / m2" column has NO stored field of its own; it is used
            ONLY as the retail_net fallback (Requirement 6.1, 6.2).
          - Prices parse a comma decimal separator ("53,61" -> 53.61); an empty
            cell becomes SQL NULL (Requirement 6.3).
          - retail_net fallback: empty "Cena detal netto" falls back to that row's
            "Cena detal / m2"; both empty -> NULL (Requirement 6.2).
          - Pakiet cell normalized: split on comma; per token strip whitespace,
            surrounding quotes and a leading "+ " marker; Budget->'budget',
            Standard/Standart->'norm' (the seeded COMFORT package), Lux->'lux';
            duplicates collapsed (Requirement 6.4).
          - Blank-Kategoria rows are skipped (Requirement 6.9).
          - Every reference (category_id/material_id/type_id/producer_id/unit_id) is
            resolved by sub-select against the FOR-04-16 / FOR-04-02 dictionaries at
            apply time. Required references (category, material, unit) are inner
            joins, so a row whose required reference is absent inserts NOTHING
            (Requirement 6.5, 6.6). Optional type/producer resolve to NULL when
            absent/blank. The unit is fixed to the 'm2' measurement unit (the CSV is
            per-m2 data); a row is skipped if 'm2' is absent.
          - finishing_material_packages rows are resolved from the just-inserted
            finishing_materials row (matched on its natural key) + the offer_packages
            code; NOT EXISTS + composite PK keep join inserts idempotent.

        Idempotency (Requirement 6.7): the whole changeset is guarded by
        preConditions onFail="MARK_RAN" with sqlCheck expectedResult="0" on
        SELECT COUNT(*) FROM finishing_materials, so it applies exactly once on a
        fresh database and is a no-op on re-run. Each individual insert is
        additionally NOT-EXISTS guarded so a partial/interrupted apply never
        duplicates rows.

        Generated rows: {stats['emitted']} finishing_materials + {stats['package_rows']} package links
        from {stats['data_rows']} CSV data rows ({stats['blank_category']} blank-Kategoria rows skipped).
    -->

    <changeSet id="062-seed-finishing-materials" author="foremen">
        <preConditions onFail="MARK_RAN">
            <sqlCheck expectedResult="0">SELECT COUNT(*) FROM finishing_materials</sqlCheck>
        </preConditions>

"""

    parts = [header]

    # Material inserts first (so package inserts can resolve them), each in its own
    # <sql> so a resolution miss on one row does not abort the batch.
    # SQL bodies sit inside <sql> element text, so XML-escape &, <, > (the SQL's
    # own single-quote escaping is already applied by sql_str). This keeps model /
    # producer values like "Villeroy & Boch" and "Loop & Friends" well-formed XML.
    parts.append("        <!-- finishing_materials rows (each resolves its references by sub-select) -->\n")
    for sql in material_inserts:
        parts.append("        <sql>\n" + xml_text(sql) + "\n        </sql>\n")

    parts.append("\n        <!-- finishing_material_packages join rows -->\n")
    for sql in package_inserts:
        parts.append("        <sql>\n" + xml_text(sql) + "\n        </sql>\n")

    parts.append("    </changeSet>\n\n</databaseChangeLog>\n")

    out = "".join(parts)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        f.write(out)

    print(f"CSV: {CSV_PATH}")
    print(f"OUT: {OUT_PATH}")
    print(f"data rows           : {stats['data_rows']}")
    print(f"blank-Kategoria rows: {stats['blank_category']}")
    print(f"emitted materials   : {stats['emitted']}")
    print(f"package link rows   : {stats['package_rows']}")
    print(f"rows w/o packages   : {stats['no_packages']}")


if __name__ == "__main__":
    main()
