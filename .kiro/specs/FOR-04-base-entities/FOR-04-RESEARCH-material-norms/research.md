# FOR-04-RESEARCH-material-norms

Research artifact: Polish construction/finishing **material-consumption norms** per
work item. It is the SOURCE for the FOR-04-19 seed (the entity that attaches, to
each work item, a collection of material consumptions per package). This is a
**data research** deliverable, not a code spec — no requirements/design/tasks
documents; the outputs are CSV files plus this methodology note.

> Language: this methodology note is kept bilingual-friendly but the CSV data uses
> Polish material names (PL market) with an RU helper column, mirroring the rest of
> FOR-04. All committed spec/design docs elsewhere stay English; this is a data
> research file.

## Goal / outputs

Two CSV artifacts in this folder:

1. **`construction-materials.csv`** — full rows to seed `construction_materials`
   (FOR-04-17 shape), one row per concrete offer, using a **typical producer** and a
   **typical seller** with a representative PLN net price. These are the concrete
   materials the norms below reference (through their **type**).

2. **`work-material-norms.csv`** — the mapping: for each work item, the materials it
   consumes, the consumption norm expressed as *material-unit per work-unit* (e.g.
   kg of mortar per m² of wall, m² of mesh per m² of ceiling), split into the two
   branches (construction vs finishing) and keyed so the FOR-04-19 estimate can
   compute a price range within a material **type** per package.

## Key model decisions (confirmed with the user)

1. **Work identity = `code` from the Excel `LP`.** FOR-04-19 will add a stable
   `work_items.code` populated from the Excel positional code (`LP`, e.g. `1,01`
   → normalized `1.01`). The norms CSV references a work by that `code`. (Today
   `work_items` has no `code`; adding it is part of FOR-04-19.)

2. **Norm is attached to a material TYPE, not a single offer.** The consumption is
   declared against a `ConstructionMaterialType` (construction branch) or the
   finishing classification (finishing branch) — the "analog group". Within a type,
   different concrete materials (offers) may have **different consumption and even
   different units** (e.g. kg of dry mix vs L of ready paste; a more-covering but
   pricier filler can net the same cost). Therefore each norm row carries the norm
   **per concrete material**, grouped by type, so the estimate shows BOTH:
   - consumption in the material's unit per work unit, AND
   - the money-per-work-unit right next to it (norm × material retailNet).

3. **Package range.** Within a package, the price range for a (work, type) is the
   MIN..MAX of (norm × retailNet) across the concrete materials of that type that
   belong to the package. **If a package has no material of that type, fall back to
   the next cheaper package's material** (never leave a work in a package without a
   material). Each package must contain **≥ 1 and ≤ 10** materials for a given work
   (10 only for genuinely material-heavy works).

4. **Two branches per work.** A work may carry up to TWO norm sets:
   - **construction** materials (mortar, mesh, primer, profiles, screws, …), and
   - **finishing** materials (tiles, panels, paint, …) — from
     `docs/Materiały pakiety - Lista.csv`.
   Research them **separately**; a work may have one, both, or neither branch.

5. **Coverage = ALL 227 works.** The Excel only fills norms for 57 works; the
   remaining 170 are researched from Polish norms (KNR / producer technical sheets /
   market practice). Where the Excel already has a norm, use it as the anchor and
   reconcile.

## Reference data the CSVs MUST map onto

- **Work catalog:** 227 works across 13 categories, extracted by
  `foremen-backend/database_files/generators/research_extract_works.py` into
  `/tmp/for-04-research-works.json` (fields: `lp`, `cat`, `catPL`, `pl`, `ru`, `jm`,
  `materials[]` = any Excel norms). Category codes 1..13 map to the FOR-04 work
  categories (`PRELIMINARY`, `CONSTRUCTIONS_GK`, `PLUMBING_ROUGH`,
  `PLUMBING_FINISH`, `ELECTRICAL_ROUGH`, `ELECTRICAL_FINISH`, `TILING`,
  `PLASTERING`, `PAINTING_DECOR`, `FLOORS`, `CARPENTRY`, `EXTRAS`, `OTHER`).
- **Work units (`work_items.unit`, seeded):** `m2`, `mb`, `szt`, `kpl`, `godz`,
  `proc`, `m3`.
- **Offer packages (seeded):** `budget` (START), `norm` (COMFORT), `lux` (PRESTIGE).
- **Material units:** construction materials often need units NOT yet in
  `MEASUREMENT_UNITS`. Use ONLY concrete physical units — `kg`, `l`, `m2`, `m3`,
  and `szt` (piece). Do **NOT** use abstract pack/bundle units like `opak`
  (opakowanie), `worek` (bag), `rolka` (roll): normalize to the physical content
  instead (e.g. sandpaper sheets → `szt`, mesh by area → `m2`, mortar → `kg`,
  primer → `l`). List every NEW unit (i.e. `kg`/`l`/`m2`/`m3` beyond the seeded work
  units) in `new-measurement-units.csv` so the FOR-04-19 seed adds them
  idempotently. Do NOT silently reuse a work unit for a material.
- **Currency:** `PLN`, prices are **net** (netto).

## CSV schema — `work-material-norms.csv`

One row per (work, branch, material-type, package-tier grouping). Columns:

| column | meaning |
|--------|---------|
| `work_code` | Excel `LP` normalized to `N.MM` (e.g. `2.10`) |
| `work_pl` | work PL name (for human sanity-check; not a key) |
| `work_unit` | work unit code (`m2`/`mb`/`szt`/`kpl`/…) — the denominator |
| `branch` | `construction` or `finishing` |
| `material_type` | the analog-group type name (construction: a `CONSTRUCTION_MATERIAL_TYPES` value; finishing: the finishing type/category) |
| `material_ref` | concrete material key: for construction → the `construction-materials.csv` `name`; for finishing → the `Materiały pakiety - Lista.csv` model/material |
| `material_unit` | material unit code (`kg`/`l`/`m2`/`szt`/`rolka`/…) — the numerator |
| `norm_qty` | consumption = material_unit per 1 work_unit (decimal, dot separator) |
| `packages` | comma-separated subset of `budget,norm,lux` this material applies to |
| `waste_pct` | optional waste/overlap allowance already folded into `norm_qty` (document it; e.g. tiling +10%) |
| `justification_pl` | **REQUIRED.** Short PL sentence justifying the norm — this text goes into the real FOR-04-19 table row. E.g. "Zużycie kleju ~5 kg/m² dla gresu 60×60 wg karty technicznej Atlas Plus." |
| `justification_ru` | **REQUIRED.** RU translation of `justification_pl` (goes into the table too). |
| `source_type` | one of `knr` \| `producer_tds` \| `retail` \| `excel` \| `expert` |
| `source_doc` | **REQUIRED.** Concrete normative document name/id — e.g. `KNR 0-12 0803-01`, `Atlas Plus — Karta techniczna (2023)`, `Excel Matrix v3.0 Oferta`. |
| `source_url` | **REQUIRED where a public document exists.** Deep link, ideally with an anchor to the exact paragraph/section (e.g. `https://…/karta-techniczna#zuzycie`). For `expert`/`excel` with no public URL, leave empty and rely on `source_doc` + `justification`. |
| `source_ref` | **REQUIRED.** The exact locator inside the document: paragraph/section/table/row — e.g. `§ Zużycie, akapit 2`, `tab. 3 wiersz 4`, `KNR 0-12 tab. 0803 kol. 3`. Must let a human find the figure. |

Repeatability/normalization rules:
- decimal dot, not comma; empty norm is NOT allowed (every emitted row has a norm).
- `justification_pl`, `justification_ru`, `source_type`, `source_doc`, `source_ref`
  are MANDATORY on EVERY row; `source_url` is mandatory whenever the source is a
  public document (KNR portal, producer TDS, retail page) and only omitted for
  `expert`/`excel` sources that have no citable URL.
- prefer a real normative anchor: KNR catalogue table/column, or the producer
  technical data sheet's "Zużycie" (consumption) section. A bare homepage URL is NOT
  acceptable — link the specific document/page and give the paragraph in `source_ref`.
- a (work, branch, type) may span multiple rows (different concrete materials /
  packages) — that is how the within-type range is formed.
- every package (`budget`/`norm`/`lux`) that a work supports MUST end up with ≥ 1
  material row for that work in each declared branch (apply the cheaper-package
  fallback rather than leaving a gap), and ≤ 10 materials.

## CSV schema — `construction-materials.csv`

Mirrors the FOR-04-17 `construction_materials` write model (no `code`, localized
`name`, one typical producer + one typical seller + representative net price):

| column | meaning |
|--------|---------|
| `name_pl` / `name_ru` | localized material name |
| `type` | `CONSTRUCTION_MATERIAL_TYPES` value (the analog group) |
| `producer` | typical producer (a `MATERIAL_PRODUCERS` value; add if new) |
| `seller` | typical seller (a `MATERIAL_SELLERS` value; add if new) |
| `packages` | subset of `budget,norm,lux` |
| `unit` | material unit code |
| `currency` | `PLN` |
| `purchase_price` / `retail_gross` / `retail_net` | PLN, decimal dot; `retail_net` is the one used for the range (approximate/representative is fine) |
| `website` | **REQUIRED where public.** Product/offer page the price came from (deep link, not a homepage). |
| `price_source_ref` | **REQUIRED.** Where on that page the price/pack size was read — e.g. `cena za opak. 20 kg`, `karta produktu, cena netto`. |
| `justification_pl` / `justification_ru` | **REQUIRED.** Short bilingual note describing what this material is and why it is the typical pick for its package tier (goes into the catalog row). |

## Process

1. **Extract** the work skeleton (done): `research_extract_works.py` →
   `/tmp/for-04-research-works.json`.
2. **Pilot** ONE category end-to-end for user review of the format and depth
   (category **8 — PLASTERING / GŁADZIE**, since it has the most Excel norms to
   reconcile against). Produce the two CSV fragments for cat 8 only.
3. After the user validates the pilot, **run all 13 categories in parallel** (one
   sub-agent per category), each emitting the same CSV columns for its work range.
4. **Merge** the per-category fragments into the two final CSVs in this folder,
   de-duplicating construction materials by (name, type, seller) and collapsing
   full analogs.
5. **Validate:** every `work_code` exists in the extracted catalog; every
   `material_type` resolves (or is listed as new); every `material_unit`/work unit
   resolves (or is listed in `new-measurement-units.csv`); each package has 1..10
   materials per work per declared branch.

## Locked conventions (confirmed on the pilot)

- **Sanding / prep works consume abrasive as a material** (papier ścierny / siatka
  ścierna), normed in `szt` (sheets/mesh pieces) per work unit — not the filler the
  Excel copy-artifact shows.
- **Prices are approximate/representative net bands** (no live per-SKU scraping).
  The catalog is refined later; the (norm × retailNet) range recomputes then.
- **Units: physical only** — `kg`, `l`, `m2`, `m3`, `szt`. No `opak`/`worek`/`rolka`.
- **glify (mb) norms are approximate** flat values (narrow reveal strip); no need to
  scale strictly by layer count.
- **Producer/seller vocabulary is canonical**: the producer/seller strings emitted
  become the seeded `MATERIAL_PRODUCERS` / `MATERIAL_SELLERS` values, so reuse the
  same spelling across categories for clean dedup. Baseline vocabulary:
  producers — Atlas, Ceresit, Knauf, Rigips, Mapei, Śnieżka, Baumit, Kreisel,
  Soudal, Dolina Nidy, Erbauer; sellers — Leroy Merlin, Castorama, OBI, PSB Mrówka.
  Add new ones only when genuinely needed and reuse exact spelling.

## Sources (Polish market) — citation policy

Use, in this order of preference, THREE source families for each norm; record which
one you used in `source_type` + `source_doc` + `source_url` + `source_ref`:

1. **`producer_tds`** — producer technical data sheet ("Karta techniczna" /
   "Zużycie" / "Wydajność"). **Prefer the Polish producer domain** (`atlas.com.pl`,
   `ceresit.pl`, `knauf.pl`, `rigips.pl`, `sniezka.pl` / `acrylputz.pl`,
   `mapei.pl`, `baumit.pl`, `kreisel.pl`, `soudal.pl`). Use a foreign/CDN mirror
   only if no Polish page exists, and say so in `source_ref`.
2. **`knr`** — KNR norms surfaced in **open Polish public-tender documents**
   (przedmiar robót / kosztorys / SIWZ / SWZ on `bip.gov.pl`, `ezamowienia.gov.pl`,
   `platformazakupowa.pl`, `logintrade.net`, etc.) that quote a KNR table for the
   work. Cite the specific KNR catalogue+table in `source_doc` (e.g.
   `KNR 0-12 tab. 0803`) and deep-link the tender doc in `source_url`.
3. **`knr` (pure)** — a direct KNR catalogue reference if a citable open copy is
   found. If none, fall back to family 1 or 2.

Rules:
- `retail` `source_url` (product page for the price) is **always kept even if it
  may go stale or 403 to bots** — a human can still open it. Best-effort; never
  block a row on a dead retail link.
- `expert` is the last resort (workmanship consumables like sandpaper, extrapolated
  extra coats, one-off patching): allowed with empty `source_url` but a clear
  `justification_*` and `source_ref` describing the assumption.
- Prefer a real consumption anchor (`Zużycie`/`Wydajność` on a TDS, or a KNR table
  column). A bare homepage is not acceptable.

All external figures are paraphrased/normalized; avoid verbatim copying beyond a few
words and always cite the source domain. Norms are representative typical values for
estimation, not a substitute for per-project engineering calculation.
