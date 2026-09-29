# Requirements Document

## Introduction

This spec (**FOR-05-05b-list-of-materials**) adds a new **Materials** tab to the project workspace
(`/projects/:projectId`). It presents the project's **concrete materials** — the products actually
chosen in the kosztorys (Estimate tab, FOR-05-05) — as a **materials × rooms matrix**: one row per
Concrete_Material, one column per project room, a trailing **row-total** column, and a per-branch
**dashboard summary** above the table. Each Room_Cell shows the material's total quantity (with unit)
and its brutto price for that room; the row total shows the summed quantity **as-is** plus a
**roundup-to-ceiling** whole-unit figure, with the brutto price recomputed from the final quantity.

The tab lets the user add a **reserve percentage per material**, persisted at project level. Both the
reserve and the roundup **increase the material quantity**, and the price is recomputed from the
increased quantity. Materials consumed as an **absolute per-room count** (the FOR-05-05 `PER_ROOM`
consumption basis — e.g. 1 WC per bathroom) are **excluded** from BOTH reserve and roundup: their
quantity stays the exact integer count.

This is a **read-and-annotate** view over the kosztorys: it does not change work assignments, volumes,
chosen products, or catalog/estimate prices. The only user inputs are the per-material reserve
percentages (and their persisted computed totals). It builds on FOR-05-05 (the works×rooms estimate
matrix, Concrete_Material selection, per-cell/per-room material aggregation, the `PER_ROOM`/`PER_UNIT`
consumption basis, and the materials-fulfilment fill indicator).

### Relationship to the kosztorys (source of truth)

- The Materials tab is **derived** from the same estimate graph the Estimate tab reads. Only material
  lines that have a chosen **Concrete_Material** appear here (Placeholders are excluded).
- The **materials-fulfilment percentage** shown here is the SAME concrete-vs-total material-line ratio
  the kosztorys already computes (FOR-05-05 fill indicator); this spec does not redefine it.
- Physical quantities and the net→brutto price derivation reuse the kosztorys values; the stored net
  price is used verbatim (never rescaled). Reserve and roundup are applied ON TOP of these values.

## Glossary

- **Materials_Tab**: the new frontend project-workspace tab at `/projects/:projectId` (tab
  "Материалы / Materiały"), rendering the materials×rooms matrix and its dashboard.
- **Concrete_Material**: a specific chosen `ConstructionMaterial` / `FinishingMaterial` product on an
  estimate material line (FOR-05-05). Only lines with a chosen product are listed here.
- **Materials_Matrix**: the grid — Concrete_Materials in rows, project rooms in columns, one quantity
  cell at each intersection, a trailing Row_Total column.
- **Room_Cell**: a matrix cell = the total quantity of the row's Concrete_Material consumed in that
  room (with its unit), plus its brutto price for that room.
- **Physical_Quantity**: the kosztorys per-line resolved quantity — `norm × Volume` for a `PER_UNIT`
  line, the absolute `norm` for a `PER_ROOM` line, or the manual override when set (FOR-05-05). This
  spec never recomputes it; it only aggregates and (except for `PER_ROOM`) inflates/rounds it.
- **Row_Total**: the trailing column for a material row = the across-rooms sum, shown as TWO numbers —
  the **as-is** sum and the **effective** (reserve-then-ceiling) quantity — plus the row brutto price
  computed from the effective quantity.
- **Reserve_Percent**: a per-material percentage added on top of the as-is required quantity to cover
  waste/spare (e.g. 200 kg + 5% = 210 kg). Stored per material at project level. Ignored for
  `PER_ROOM` materials.
- **Absolute_Per_Room_Material**: a Concrete_Material whose estimate line uses the FOR-05-05 `PER_ROOM`
  consumption basis (a fixed count per assigned room, e.g. szt/kpl). Excluded from reserve and roundup.
- **Effective_Quantity**: the quantity actually to purchase for a material = `ceil(asIsQuantity ×
  (1 + Reserve_Percent/100))` for a normal (`PER_UNIT`) material; = `asIsQuantity` unchanged for an
  Absolute_Per_Room_Material.
- **Branch**: construction or finishing (the same split as the kosztorys / FOR-05-05).
- **Materials_Reserve_Map**: a project-level persisted JSONB map keyed by `materialId`, storing each
  material's reserve percent and its computed totals (see Requirement 4).
- **Materials_Dashboard**: the summary above the matrix — per-branch material consumption totals and
  total prices, including applied reserve/roundup, plus the Materials_Fulfilment percentage.
- **Materials_Fulfilment**: the concrete-vs-total material-line ratio, identical to the kosztorys fill
  indicator (FOR-05-05).

## Requirements

### Requirement 1: Materials tab lists concrete materials from the kosztorys

**User Story:** As a project user, I want a Materials tab that lists the concrete materials chosen in
my kosztorys, so that I can see the whole project's material list in one place.

#### Acceptance Criteria

1. WHEN a user opens a project THEN the system SHALL show a **Materials** tab in the project workspace
   alongside the existing tabs.
2. WHEN the Materials_Tab loads THEN the system SHALL render one row per distinct Concrete_Material
   chosen on at least one estimate material line of the project's kosztorys.
3. WHERE a material line is a Placeholder (no Concrete_Material chosen) THE system SHALL exclude it
   from the Materials_Matrix.
4. WHEN the project has no chosen concrete materials THEN the system SHALL render an empty-state
   message and no matrix rows.
5. THE system SHALL group rows by Branch (construction, finishing), matching the kosztorys split.
6. THE Materials_Tab SHALL be a read-only projection of the kosztorys for everything except the
   per-material Reserve_Percent inputs (Requirement 4); it SHALL NOT modify assignments, volumes,
   chosen products, or catalog/estimate prices.

### Requirement 2: Materials × rooms matrix with per-room quantity and price

**User Story:** As a project user, I want each material's quantity and price broken down per room, so
that I can see where each material is used and what it costs.

#### Acceptance Criteria

1. THE Materials_Matrix SHALL have one column per project room, in a stable deterministic order.
2. WHEN a Concrete_Material is consumed in a room THEN the corresponding Room_Cell SHALL show the
   total **quantity with its unit** for that room (aggregated across every kosztorys cell in that room
   that uses the material) AND its **brutto price** for that room.
3. WHERE a Concrete_Material is not consumed in a given room THE Room_Cell SHALL render an empty
   placeholder (no quantity, no price).
4. THE Room_Cell quantity SHALL be the aggregated kosztorys Physical_Quantity for that (material,
   room); reserve and roundup are NOT applied at the cell level (Requirement 4).
5. THE Room_Cell brutto price SHALL be the material's net price with VAT applied, the net price used
   verbatim from the estimate line (never rescaled).
6. WHEN a user opens a material's detail popup THEN the system SHALL show all available data for that
   Concrete_Material (full catalog + estimate detail — e.g. name/label, branch, type, unit, net and
   brutto price, per-room breakdown, as-is total, reserve %, effective total, and totals price); the
   inline table row SHALL remain the compact quantity+unit / brutto view.

### Requirement 3: Row total with as-is sum and roundup-to-ceiling

**User Story:** As a project user, I want a total column per material showing both the exact sum and
the rounded-up amount to purchase, so that I know both the theoretical need and what I must buy.

#### Acceptance Criteria

1. THE Materials_Matrix SHALL have a trailing Row_Total column.
2. THE Row_Total SHALL show TWO quantities: (a) the **as-is** summed Physical_Quantity across all
   rooms, and (b) the **Effective_Quantity** (after reserve then ceiling, Requirement 4).
3. FOR a normal (`PER_UNIT`) material THE roundup SHALL round the reserve-inflated quantity UP to the
   next whole unit (integer ceiling), and THE Row_Total brutto price SHALL be recomputed from the
   Effective_Quantity (`Effective_Quantity × brutto unit price`).
4. FOR an Absolute_Per_Room_Material (Requirement 5) THE as-is and effective quantities SHALL be equal
   (the exact per-room count summed across rooms), with NO reserve and NO roundup applied.

### Requirement 4: Per-material reserve percentage, persisted at project level

**User Story:** As a project user, I want to set a reserve percentage per material (and mass-apply it),
persisted with the project, so that my reserve choices are saved and shared.

#### Acceptance Criteria

1. THE system SHALL let the user set a Reserve_Percent for a single material row.
2. THE system SHALL persist the reserve data at **project level** as a JSONB Materials_Reserve_Map
   keyed by `materialId`, each entry holding the material's reserve percent AND its computed totals
   (as-is quantity, effective quantity, and the corresponding brutto price total) so the tab can show
   and re-use them.
3. WHEN a Reserve_Percent is set/changed for a material THEN the system SHALL inflate that material's
   as-is quantity by the percentage (e.g. 200 units + 5% = 210) BEFORE the ceiling roundup, and THEN
   apply the whole-unit ceiling; the material's price SHALL be recomputed from the resulting
   Effective_Quantity.
4. THE system SHALL provide a UI convenience to mass-set the same Reserve_Percent across a whole Branch
   or the whole project; this convenience SHALL write the same percent into each affected material's
   Materials_Reserve_Map entry (there is NO separate branch-level stored reserve value — the map is
   per-material only).
5. WHEN a Reserve_Percent is 0 or unset for a material THEN the system SHALL apply no reserve (identity)
   for that material.
6. THE Reserve_Percent SHALL NOT apply to an Absolute_Per_Room_Material; the system SHALL ignore (or
   disable in the UI) any reserve for such a material and keep its quantity exact.
7. THE reserve applies **per material** (across the whole project), never per Room_Cell.

### Requirement 5: Absolute per-room materials are neither reserved nor rounded

**User Story:** As a project user, I want fixed per-room-count materials (like one WC per bathroom) to
stay at their exact count, so that whole-item materials are never inflated or rounded up spuriously.

#### Acceptance Criteria

1. THE system SHALL classify a Concrete_Material as an Absolute_Per_Room_Material WHEN its kosztorys
   material line uses the FOR-05-05 `PER_ROOM` consumption basis.
2. FOR an Absolute_Per_Room_Material THE system SHALL NOT apply Reserve_Percent and SHALL NOT apply the
   ceiling roundup; its Effective_Quantity SHALL equal its as-is quantity.
3. THE system SHALL still show such a material's per-room quantities, row total, and brutto price like
   any other row (only the reserve/roundup transforms are suppressed).

### Requirement 6: Per-branch dashboard summary

**User Story:** As a project user, I want a per-branch summary of consumption and totals including
reserves, so that I can see the project's material spend at a glance.

#### Acceptance Criteria

1. THE Materials_Tab SHALL render a Materials_Dashboard above the matrix.
2. THE Materials_Dashboard SHALL show, per Branch, a fully descriptive summary: the material
   consumption totals and the total price, presented for BOTH the as-is figures and the
   reserve/roundup-applied (effective) figures, in NET and BRUTTO.
3. THE Materials_Dashboard SHALL also show a project-wide grand total (per the same as-is vs effective,
   net vs brutto breakdown) in addition to the per-branch summaries.
4. THE effective totals SHALL reflect the persisted per-material reserve and the roundup, excluding
   Absolute_Per_Room_Materials from those transforms (Requirement 5).
5. THE Materials_Dashboard SHALL show the Materials_Fulfilment percentage, identical to the kosztorys
   fill indicator (FOR-05-05); this spec SHALL NOT recompute or redefine it.

### Requirement 7: Quantity/money computation reuses the kosztorys values

**User Story:** As a maintainer, I want the Materials tab to reuse the kosztorys quantities and prices,
so that its numbers always agree with the estimate and there is one source of truth.

#### Acceptance Criteria

1. THE per-room quantity aggregation SHALL reuse the FOR-05-05 resolved Physical_Quantity (override-
   and basis-aware); it SHALL NOT introduce a second, divergent quantity formula.
2. THE net price SHALL be the estimate line's chosen-product net price used verbatim; the brutto price
   SHALL be that net price with the applicable VAT applied; no price value SHALL be rescaled.
3. WHEN the same Concrete_Material is consumed by multiple works/cells in one room THEN the system
   SHALL sum its Physical_Quantities for that room before computing the Room_Cell price.
4. THE computation order for a material's Effective_Quantity SHALL be: (1) aggregate the as-is
   Physical_Quantity across all rooms; (2) apply Reserve_Percent (`× (1 + pct/100)`); (3) ceiling to
   the next whole unit; with steps (2)–(3) SKIPPED entirely for an Absolute_Per_Room_Material. The
   effective brutto price SHALL then be `Effective_Quantity × brutto unit price`.

### Requirement 8: Access control (ABAC)

**User Story:** As a security-conscious operator, I want the Materials tab and its reserve edits gated
by the same permissions as the estimate, so that only authorized users can view or change them.

#### Acceptance Criteria

1. WHERE a user lacks `ESTIMATE` READ THE system SHALL gate the Materials_Tab from that user (no tab,
   no read access to the materials read model).
2. WHERE a user has `ESTIMATE` READ but not `ESTIMATE` UPDATE THE system SHALL render the Materials_Tab
   read-only: the matrix, dashboard, and detail popup are visible, but the Reserve_Percent inputs and
   the mass-apply convenience are disabled.
3. WHEN a user without `ESTIMATE` UPDATE attempts to persist a Reserve_Percent change THEN the system
   SHALL reject the write (server-enforced, not only UI-disabled).
4. THE Materials_Tab SHALL reuse the existing `ESTIMATE` ABAC resource; it SHALL NOT introduce a new
   resource, operation, or role grant.

### Requirement 9: Reserve input validation

**User Story:** As a project user, I want the reserve percentage input validated, so that I can't save
a nonsensical value and totals stay trustworthy.

#### Acceptance Criteria

1. THE system SHALL accept a Reserve_Percent that is a non-negative number within a bounded range
   [OPEN: confirm upper bound — proposed 0..100 inclusive], with up to [OPEN: proposed 2] decimal
   places.
2. WHEN a user enters a value below 0, above the allowed maximum, or non-numeric THEN the system SHALL
   reject it with a localized validation message and SHALL NOT persist it.
3. WHEN a Reserve_Percent is cleared/empty THEN the system SHALL treat it as unset (identity, no
   reserve) rather than an error.
4. THE server SHALL validate the persisted Materials_Reserve_Map on write (same bounds) and reject an
   out-of-range or malformed entry, so an invalid value cannot enter the project via the API.

### Requirement 10: Reserve edits require a writable (DRAFT) estimate

**User Story:** As a project user, I want reserve edits allowed only while the estimate is still a
draft, so that a signed/approved estimate's material plan is not altered after the fact.

#### Acceptance Criteria

1. WHERE the project's estimate is in a writable DRAFT state AND the user has `ESTIMATE` UPDATE THE
   system SHALL permit setting/changing Reserve_Percent (per-material and via mass-apply).
2. WHERE the estimate is not in a writable state (e.g. APPROVED/SIGNED, per the FOR-05 estimate
   lifecycle) THE system SHALL render the reserve inputs read-only and reject reserve writes
   server-side, consistent with the kosztorys draft gate.
3. THE Materials_Tab SHALL remain fully viewable (matrix, dashboard, popup, persisted reserve values)
   regardless of the estimate's writable state; only the reserve edits are gated by it.

### Requirement 11: Internationalization (PL/RU) at parity

**User Story:** As a bilingual user, I want the Materials tab fully localized, so that every label is
in my language with no raw keys.

#### Acceptance Criteria

1. THE Materials_Tab SHALL localize all user-facing text — tab title, column headers (rooms, total),
   the two Row_Total figures, dashboard labels, detail-popup labels, reserve controls, validation
   messages, and the empty state — via i18n keys.
2. THE new i18n keys SHALL exist in BOTH `pl.json` and `ru.json` at strict key parity (identical key
   sets), with non-empty values.
3. THE Materials_Tab SHALL never surface a raw i18n key to the user.

### Requirement 12: Empty and degenerate cases

**User Story:** As a project user, I want the Materials tab to behave sensibly when data is sparse, so
that it never errors or shows misleading numbers.

#### Acceptance Criteria

1. WHEN the project has no rooms THEN the Materials_Tab SHALL render its structure (dashboard + header)
   with no room columns and an appropriate empty state, without error.
2. WHEN the project has rooms but no chosen Concrete_Materials THEN the Materials_Tab SHALL render the
   empty-state message (per Requirement 1) and no matrix rows.
3. WHEN a Concrete_Material's aggregated as-is quantity is zero THEN the system SHALL show zero
   quantities and a zero total without applying a spurious ceiling to a non-zero whole unit.
4. WHERE a chosen product has no net price (null) THE system SHALL show the quantity but render the
   price as an empty/`—` placeholder rather than fabricating a value, and SHALL exclude it from money
   totals [OPEN: confirm — exclude from totals vs treat as 0].
5. WHEN a persisted Materials_Reserve_Map references a `materialId` no longer present in the kosztorys
   (the product was removed/changed) THEN the system SHALL ignore that stale entry for display/totals
   and SHALL NOT error [OPEN: confirm whether stale entries are pruned on next save].
