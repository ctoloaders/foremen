# Requirements Document

FOR-05-04: Estimate packages & pricing model change — retire per-package pricing on `WorkPrice`
and `WorkMaterialConsumption` in favor of a single price/norm per work item, a room-dimension
formula engine for work volumes, and a package zł/m² pricing model with material-pick assistance

## Introduction

FOR-05-04 is a **migration/deprecation spec**: it changes the pricing substrate that FOR-04-12b,
FOR-04-12c, FOR-05-03, and FOR-04-19 already built and shipped. This is not a green-field
addition next to unused code — those specs are **fully implemented** (real Liquibase tables,
JPA entities, DAOs, services, CRUD controllers, and passing property tests), and this spec's job
is to retire/migrate that implementation to a different model, based on gaps found once the
client's live Excel workbook was analyzed in full.

**What is being retired:**

- **FOR-04-12b/12c** (`WorkPrice` aggregator + `WorkPackagePrice` join table +
  `EffectivePriceResolver` MAX-fallback): a work's catalog price is currently
  `(workItem, offerPackage, currency, netPrice)` — one price **per package**. This is replaced
  by a single price **per work item**, matching the original FOR-04-12 shape (before FOR-04-12b
  introduced packages) but without reintroducing the `validFrom`/`validTo` interval columns
  FOR-04-12 originally had (temporal pricing already lives on `ProjectServicePrice`,
  FOR-05-14, and stays there).
- **FOR-05-03**'s `EstimateLinePackagePrice` / `EstimateLinePackagePriceHistory` vertical (the
  per-line, per-package project price snapshot copied from `WorkPackagePrice` at add-time,
  its discount placeholder, and its history skeleton): retired because its source
  (`WorkPackagePrice`) no longer exists as a per-package price. `EstimateLine.unitPrice` becomes
  the line's only price (already a single denormalized snapshot field on `EstimateLine` —
  Requirement 4 defines what replaces the per-package rows it fed).
- **FOR-04-19**'s package dimension only (`WorkMaterialConsumption`'s `offerPackage` FK): a
  material consumption norm row is currently `(workItem, offerPackage, materialType, branch,
  normQty)` — one norm **per package**. This spec collapses that to a single norm row per
  `(workItem, materialType, branch)`, with no package dimension, using the same MAX-collapse
  rule as Requirement 1's single work price. The norm-based computation itself
  (`MaterialRangeResolver`, `WorkCatalogAggregationResolver`'s construction/finishing branch
  computation) is **kept**, not retired — it remains the ongoing, primary model for
  construction-material and finishing-material cost, now operating on package-less norm rows
  (Requirement 7).

**What is added:**

- **A single work price** per `WorkItem` (Requirement 1).
- **A formula engine** (Requirement 2, 3) that expresses a work's volume for a room as a
  human-readable formula over the 14 room dimensions (FOR-05-02) as named variables, with
  arithmetic, parentheses, simple conditionals, numeric constants, and references to another
  work's computed volume/presence in the same room (the cross-work dependency the client's
  `Oferta` sheet relies on, e.g. tiled wall area subtracted from painted wall area). Package
  membership and package-specific formula overrides (Requirement 4) replace the retired
  per-package price/consumption rows as the mechanism for "this work behaves differently in
  package X."
- **A package zł/m² pricing model** (Requirement 6) that computes an average price-per-m² per
  offer package from a curated, twice-yearly-reviewed assortment of finishing/fixture line
  items (min/avg/max price × quantity against a 50 m² reference apartment), mirroring the
  `Zestawienie` workbook, with an assistive "typical product" reference link that never drives
  the computed value (the same provenance-FK-never-drives-value pattern FOR-05-03 already
  established for catalog snapshots).

**Source evidence.** The domain model in this document is grounded in two client-provided Excel
workbooks (not re-derived from scratch):

- `EBRD copy Matrix Foremen v3.0.xlsx`, sheet `Oferta`: column `E` (unit), column `F` (price/norm
  per unit), columns `X:AJ` (one column per room, 1:1 with sheet `Wymiary` row-1 headers) holding
  a direct dimension reference, an arithmetic formula over dimensions, or a cross-reference to
  another work's row in the same column, and columns `AL`/`AM`/`AN` (`PAKIET START` / `PAKIET
  COMFORT` / `PAKIET PRESTIGE`) whose non-empty formula both marks package membership and gives
  the package-specific quantity override.
- `docs/Materiały pakiety.xlsx`, sheet `Zestawienie`: grouped line items with min/avg/max price
  per package, a quantity against a 50 m² reference apartment, and a `zł/m²` contribution per
  group that sums to a package `TOTAL zł/m²` (sample data: Budget/Standard/Lux ≈ 332/543/1087
  zł/m²).

## Glossary

- **WorkItem**: The FOR-04-11 catalog work (a unit of billable labour), the anchor for both the
  retired per-package price and the new single price.
- **Single work price**: The FOR-04-12-shaped `(workItem, currency, netPrice)` catalog price
  this spec reinstates — one row per work item, no `offerPackage` dimension, no `validFrom`/
  `validTo` interval (temporal resolution stays owned by `ProjectServicePrice`, FOR-05-14).
- **OfferPackage**: A commercial package, seeded by FOR-04-10 with codes `budget`/`norm`/`lux`
  and canonical display pairs **Budget/START**, **Norm/COMFORT**, **Lux/PRESTIGE** (confirmed
  from the FOR-04-10 seed requirement; the `Zestawienie` workbook's "Lux" and other docs'
  "Prestige" label the *same* package — there is no third naming scheme to reconcile).
- **Room dimension**: One of the 14 named per-room metrics already modeled by FOR-05-02
  (`Rooms & dimensions`): floor area (`Pow podloga`), wall area (`Pow sciany`), perimeter
  (`Obwód/obrys`), door count/height/width/area (`Drzwi ilość`/`wysokość`/`szerokość`/
  `powerzchnia`), no-wall length (`Brak ściany`), no-finish length (`Brak wykończenia`), window
  height/width/area (`Okno wysokość`/`szerokość`/`powerzchnia`), internal corner count (`Kąty
  wewnętrzne`), and ceiling height (`Wysokość`).
- **Volume formula**: A per-`WorkItem` expression over room-dimension variables (and,
  optionally, other works' resolved volumes in the same room) that computes the work's quantity
  for a given room. Replaces hand-entered per-room quantities for works whose formula is
  defined; works without a formula keep today's manually-entered `EstimateLineRoomQty`.
  Sourced from `Oferta!X:AJ` for seeding.
  - **Package override formula**: An optional, package-specific volume formula on a
    `(WorkItem, OfferPackage)` pair that both marks the work as a **member** of that package
    and overrides the default volume formula when computing quantity for that package. Sourced
    from `Oferta!AL:AN`.
- **Cross-work reference**: A formula operand that reads another work's already-computed volume
  (or its presence/absence, i.e. quantity > 0) for the **same room**, e.g. `=X28*1+X42` (sum of
  two other works' room volumes) or the wall-painting formula subtracting the tiled area's
  work-row sum from the paintable perimeter × height.
- **Formula evaluation order**: The deterministic order in which a room's work formulas are
  resolved so that a formula referencing another work's volume always sees that work's
  already-computed value for the same room (no forward reference to an unresolved work; a
  circular reference is an error, Requirement 3).
- **Package zł/m² price**: The computed, package-level average price per m² of total project
  floor area, derived by summing each assortment group's `zł/m²` contribution
  (`group total price ÷ 50` against the `Zestawienie` reference apartment) across all groups in
  the package (Requirement 6).
- **Assortment line item**: One curated catalog row within a `Zestawienie` group (e.g. `Miska
  WC` under `Sanitariat/łazienka`), carrying min/avg/max price **per package** and a quantity
  assumed against the 50 m² reference apartment.
- **Typical product reference**: An optional FK from an assortment line item to a concrete
  product/material catalog entry, shown to assist a user picking a real product — it is
  **provenance/assistive only** and never drives the stored min/avg/max price, mirroring the
  FOR-05-03 "provenance FK never drives value" pattern.
- **Single material consumption norm**: The `WorkMaterialConsumption` shape this spec produces
  — `(workItem, materialType, branch, normQty)`, no `offerPackage` dimension — mirroring the
  "Single work price" entry above. The norm-based construction/finishing material cost
  computation that reads this row (`MaterialRangeResolver`, `WorkCatalogAggregationResolver`)
  is retained as-is; only the package dimension on the norm row is collapsed.
- **Retirement**: Removing or replacing the schema, entities, services, and controllers of an
  already-shipped vertical (as opposed to leaving it in place and adding a parallel model) — the
  explicit choice made for `WorkPackagePrice`/`EstimateLinePackagePrice` in this spec. This does
  **not** apply to `WorkMaterialConsumption`'s norm-based computation, which is kept; only its
  `offerPackage` dimension is collapsed, the same treatment `WorkPrice` receives (see Requirement
  8 for the full list of what is retired vs. kept).

## Requirement Groups

Requirements are grouped for traceability and to make a future split (packages/pricing vs.
formula engine) easy if ever needed:

- **Group A — Single work price & material-norm package-collapse** (Requirements 1, 7): retires
  the per-package dimension on both `WorkPrice` and `WorkMaterialConsumption`, using the same
  MAX-collapse rule for each; the norm-based material cost computation itself is kept, not
  retired.
- **Group B — Formula engine** (Requirements 2, 3, 4): volume formulas, evaluation, and package
  overrides.
- **Group C — Estimate line integration** (Requirement 5): how `EstimateLine`/`EstimateLineRoomQty`
  consume the single price and the formula engine.
- **Group D — Package zł/m² pricing** (Requirement 6): the `Zestawienie`-style package price
  model, a separate and additive figure for the curated finishing/fixture assortment — it does
  not replace, feed, or interact with the per-work material consumption norms (Group A).
- **Group E — Migration & retirement** (Requirement 8): explicit list of what is removed vs.
  migrated vs. kept, and the data migration path.
- **Group F — Boundaries & out of scope** (Requirement 9): margin/offer/e-signature and
  real cost-of-goods stay with their owning specs.

## Requirements

### Requirement 1: Single work price (retire per-package catalog pricing)

**User Story:** As a pricing administrator, I want each work item to have exactly one catalog
price instead of one price per commercial package, so that maintaining prices does not require
me to fill in three package variants for every work.

#### Acceptance Criteria

1. THE system SHALL define a work item's catalog price as `(workItem, currency, netPrice)` — a
   single price per `WorkItem`, with no `offerPackage` dimension.
2. THE system SHALL NOT reintroduce `validFrom`/`validTo` interval columns on the catalog work
   price; temporal price resolution remains owned by `ProjectServicePrice` (FOR-05-14).
3. WHEN a work item's price is read, THE system SHALL return that single price directly, without
   an effective-price / MAX-fallback resolution step across packages.
4. THE FOR-04-12b `WorkPackagePrice` join entity and its MAX-fallback `EffectivePriceResolver`
   SHALL be removed as the source of a work's catalog price (Requirement 8 defines the removal
   mechanics and data migration).
5. WHERE a work item currently has package prices that differ across packages, THE data
   migration SHALL resolve them to a single `netPrice` equal to the **MAX** `netPrice` across
   that work item's existing package prices (reusing the FOR-04-12b MAX-fallback value as the
   migration's resolution rule, per Requirement 8.3/8.5), and SHALL NOT silently discard price
   information without recording the resolution rule used.

### Requirement 2: Volume formula on a work item

**User Story:** As a pricing/estimation administrator, I want a work item's volume for a room to
be computed from a formula over that room's dimensions, so that adding a work to a room does not
require hand-entering a quantity that a rule already determines.

#### Acceptance Criteria

1. THE system SHALL allow a `WorkItem` to carry an optional **default volume formula**: a
   human-readable expression over named room-dimension variables (Requirement 2.2), constants,
   arithmetic operators, parentheses, and simple conditionals.
2. THE formula language SHALL expose the 14 room dimensions as named variables corresponding
   1:1 to the FOR-05-02 room metrics (floor area, wall area, perimeter, door count/height/width/
   area, no-wall length, no-finish length, window height/width/area, internal corner count,
   ceiling height).
3. THE formula language SHALL support addition, subtraction, multiplication, division, and
   parenthesized grouping, matching the arithmetic found in the `Oferta!X:AJ` formula examples
   (e.g. `=X26*2`, `=Wymiary!C11*2+Wymiary!C12`).
4. THE formula language SHALL support simple conditional lookups equivalent to the `Oferta`
   examples `=IFS(...)` and `=COUNTIF(...)` (a value selected by comparing a variable or another
   work's computed value against constants or ranges).
5. WHERE a `WorkItem` has no default volume formula, THE system SHALL fall back to the existing
   manually-entered `EstimateLineRoomQty` behavior (Requirement 5.4) rather than requiring every
   work to define a formula.
6. THE system SHALL persist the volume formula as source text (for editing/audit) alongside a
   validated/parsed form sufficient for repeated evaluation without re-parsing on every use.

### Requirement 3: Cross-work references and deterministic evaluation

**User Story:** As a pricing/estimation administrator, I want a work's formula to be able to
reference another work's computed volume in the same room, so that I can express rules like
"painted wall area excludes the area already covered by tiling" without manual adjustment.

#### Acceptance Criteria

1. THE formula language SHALL support an operand that references another `WorkItem`'s computed
   volume (or presence, i.e. quantity greater than zero) **for the same room**, matching the
   `Oferta` cross-reference pattern (e.g. `=X28*1+X42`, and the wall-painting formula that
   subtracts other works' row sums from a perimeter-based area).
2. WHEN evaluating a room's work formulas, THE system SHALL resolve them in a **deterministic
   order** such that a formula referencing another work's volume observes that other work's
   already-computed value for the same room.
3. IF a set of works' formulas for a room form a circular reference (directly or transitively),
   THEN THE system SHALL detect the cycle and **reject the save/edit operation that would
   introduce it** — a circular formula SHALL NOT be persisted — reporting an error that
   identifies the works involved in the cycle, rather than persisting it and only surfacing the
   problem later at evaluation time.
4. IF a formula references a work that is not present (not added) in the room, THEN THE system
   SHALL treat that work's volume as zero for the reference (matching "no such row in this room"
   in the source spreadsheet) rather than raising an error, UNLESS the referenced work is part of
   a detected cycle (Requirement 3.3).
5. FOR ALL rooms and FOR ALL valid (acyclic) sets of work formulas in a room, evaluating the
   room's formulas twice with the same inputs SHALL produce identical results (determinism).

### Requirement 4: Package membership and package-specific formula overrides

**User Story:** As a pricing/estimation administrator, I want to mark a work as belonging to a
commercial package and optionally give it a different volume formula for that package, so that
package-specific inclusion/quantity rules (like the client's `PAKIET START/COMFORT/PRESTIGE`
columns) are captured without a separate per-package price.

#### Acceptance Criteria

1. THE system SHALL allow a `(WorkItem, OfferPackage)` pair to carry a **package membership
   flag** and an optional **package override formula**, matching the semantics of a non-empty
   `Oferta!AL`/`AM`/`AN` cell for that work's row.
2. WHEN a package override formula is present for a `(WorkItem, OfferPackage)` pair, THE system
   SHALL treat that work as a **member** of that package, and SHALL use the override formula —
   not the default volume formula (Requirement 2.1) — when computing the work's volume for that
   package's context.
3. WHERE no package override formula is present for a `(WorkItem, OfferPackage)` pair, THE
   system SHALL treat package membership for that pair as **not established by this mechanism**
   (a work may still be added to an estimate without being tied to a specific package via this
   flag).
4. THE package override formula SHALL support the same operand set as the default formula
   (Requirement 2, 3), including referencing another package override's resolved value on the
   same work or a different work (matching `Oferta` examples `=AL48*1.5`, `=AL48+1`, and
   conditional lookups into another package's cell such as `=IFS(AL33=1,3,AL33=2,6,AL33=3,9)`).
5. THE package membership flag and override formula SHALL NOT carry a price; Requirement 1
   defines the single work price independently of package membership.

### Requirement 5: Estimate line integration

**User Story:** As a designer building a project's kosztorys, I want an estimate line's price and
(where a formula exists) quantity to be derived from the new single-price and formula model, so
that the estimate stays consistent with the retired per-package model's removal.

#### Acceptance Criteria

1. WHEN an `EstimateLine` is created for a `WorkItem`, THE system SHALL set the line's
   denormalized `unitPrice` snapshot by copying the work item's **single work price**
   (Requirement 1), retaining the existing provenance-FK-only contract (the FK is for lineage;
   the copied value is the source of truth).
2. WHERE a `WorkItem` has no applicable package override formula (Requirement 4) for the
   estimate's active package context, THE system SHALL NOT create per-package price rows
   (`EstimateLinePackagePrice`) when an `EstimateLine` is added; Requirement 8 defines the
   retirement of that vertical. Formula-driven package cases are governed by Requirement 4's
   membership/override mechanism instead of a per-package price row.
3. WHERE a `WorkItem` has a default volume formula (Requirement 2) or a package override formula
   applicable to the estimate's active package context (Requirement 4), THE system SHALL compute
   the line's per-room quantity for a room by evaluating the applicable formula against that
   room's dimensions (and any cross-work references, Requirement 3) rather than requiring a
   hand-entered `EstimateLineRoomQty` value.
4. WHERE a `WorkItem` has no applicable formula, THE system SHALL preserve today's behavior:
   `EstimateLineRoomQty` is hand-entered and the line's `quantity` is the sum of its room
   quantities (FOR-05-03 Requirement 2.6, unchanged).
5. WHEN a formula-derived room quantity is computed, THE system SHALL make the derivation
   traceable (the formula used and its resolved inputs are retrievable), so a user reviewing the
   estimate can see why a quantity has a given value.

### Requirement 6: Package zł/m² pricing model

**User Story:** As a pricing administrator, I want to maintain a curated assortment of
finishing/fixture line items per commercial package and get an average zł/m² price per package
computed from it, so that packages can be priced and re-reviewed the way the `Zestawienie`
workbook already does twice a year.

#### Acceptance Criteria

1. THE system SHALL define an **assortment group** (e.g. `Sanitariat/łazienka`, `Podłoga`,
   `Płytki - Gres`) containing one or more **assortment line items** (e.g. `Miska WC`, `Stelaż`).
2. THE system SHALL allow each assortment line item to carry, **per `OfferPackage`**, a minimum,
   average, and maximum price, and a quantity assumed against the fixed **50 m² reference
   apartment**, matching the `Zestawienie` sheet's columns.
3. THE system SHALL compute, for each assortment group and package, a **zł/m² contribution** as
   `(sum of that group's line-item totals for the package) ÷ 50`.
4. THE system SHALL compute, for each `OfferPackage`, a **package zł/m² price** as the sum of
   all assortment groups' zł/m² contributions for that package.
5. THE system SHALL allow the assortment data (prices, quantities) to be updated to reflect a
   periodic (e.g. twice-yearly) market review, and SHALL recompute the package zł/m² price from
   the current assortment data rather than caching a stale total indefinitely.
6. WHEN an assortment line item references a **typical product** (Requirement 6.7), THE
   system SHALL treat that reference as assistive/informational only; it SHALL NOT be read as
   the source of the item's min/avg/max price — the min/avg/max price fields (Requirement 6.2)
   are the source of truth for the zł/m² contribution (Requirement 6.3) regardless of whether a
   typical-product link exists (see Open Question 5 for a noted disagreement in the
   requirements-analysis pass on this point).
7. WHERE a user is picking a real product for an assortment line item, THE system SHALL support
   linking the line item to a catalog product/material entry to assist that choice ("help pick
   typical materials"), and THE existence or absence of that link SHALL NOT block or gate the
   zł/m² contribution computation (Requirement 6.3), which reads only the min/avg/max/quantity
   fields (Requirement 6.6).
8. THE system SHALL expose the computed package zł/m² price so a downstream consumer (e.g. an
   estimate's package-value computation, owned by FOR-05-06) can apply it as
   `package_zł_per_m2 × project_total_floor_area`, without FOR-05-04 itself implementing that
   downstream application.

### Requirement 7: Package-dimension collapse on material consumption norms (kept, not retired)

**User Story:** As a pricing administrator, I want construction/finishing material consumption
norms to drop their per-package dimension (the same treatment as work prices) while keeping the
norm-based computation itself, so that material cost stays accurate without requiring three
package variants per norm row.

#### Acceptance Criteria

1. THE system SHALL retain `WorkMaterialConsumption`'s norm-based construction/finishing
   material cost computation (via `MaterialRangeResolver`/`WorkCatalogAggregationResolver`, or
   their post-migration equivalents) as the ongoing, primary material-cost model — it is
   explicitly **not** retired.
2. THE system SHALL remove the `offerPackage` dimension from `WorkMaterialConsumption`, so a
   norm row becomes `(workItem, materialType, branch, normQty)` with no package axis, mirroring
   Requirement 1's single-work-price collapse.
3. WHERE a work item currently has different norm quantities across packages for the same
   `(materialType, branch)`, THE data migration SHALL resolve them to a single `normQty` equal
   to the **MAX** across that work item's existing per-package norm rows (the same rule and
   rationale as Requirement 1.5), archived per Requirement 8's migration mechanics.
4. THE system SHALL compute a work item's total estimate cost as the sum of exactly three
   parts: the single work price (Requirement 1), the norm-based construction-material cost, and
   the norm-based finishing-material cost.
5. THE system SHALL NOT introduce a 20%-of-work-cost (or any other) fallback heuristic for
   material cost; a work either has norm-based coverage for a material branch or is explicitly
   unpriced for that branch (no fabricated placeholder value), mirroring the "never fabricate a
   value" principle already used elsewhere in the FOR-05 series (e.g. FOR-05-03's
   `unpriced=true` pattern).
6. THE package zł/m² model (Requirement 6) SHALL remain unrelated and additive to this
   requirement; it SHALL NOT supply, override, or otherwise interact with any work's norm-based
   material cost.

### Requirement 8: Migration and retirement mechanics

**User Story:** As a maintainer, I want the retirement of the per-package price and material
consumption verticals to be an explicit, reviewable migration rather than a silent code deletion,
so that existing data is not lost and the change is auditable.

#### Acceptance Criteria

1. THE system SHALL provide a data migration that runs before the schema changes described in
   Requirements 1 and 7 take effect, covering: `work_prices`/`work_package_prices`
   (FOR-04-12b), `estimate_line_package_prices`/`estimate_line_package_price_history`
   (FOR-05-03), and `work_material_consumptions` (FOR-04-19, collapsing its package dimension).
2. THE migration SHALL remove the `WorkPackagePrice` entity/table and the
   `EstimateLinePackagePrice`/`EstimateLinePackagePriceHistory` entities/tables and their CRUD
   controllers/services/DAOs, as concrete removal targets. For `WorkMaterialConsumption`, THE
   migration SHALL only drop the `offer_package_id` FK/column and collapse the norm rows via
   the MAX rule (Requirement 7.3); the `WorkMaterialConsumption` entity/table and its resolvers
   (`MaterialRangeResolver`, `WorkCatalogAggregationResolver`) SHALL be **kept**, adjusted to
   the new package-less shape, not removed.
3. WHEN resolving a work item's single price from its existing per-package prices during
   migration, THE system SHALL apply the **MAX-fallback rule** stated in Requirement 1.5 (the
   MAX `netPrice` across that work item's existing package prices) as the documented resolution
   rule, rather than an undocumented or silently arbitrary choice. THE same MAX rule SHALL be
   applied to collapse `WorkMaterialConsumption`'s per-package norm rows per Requirement 7.3.
4. THE migration SHALL preserve the removed data's values in an auditable form (e.g. an archive
   table, an audit-log snapshot, or an export) rather than deleting rows with no trace, so a
   resolution dispute can be checked against the original per-package figures.
5. THE `EffectivePriceResolver`'s **MAX-fallback rule** as a pure/tested function MAY be
   reused for the migration's resolution rule (Requirement 8.3) but SHALL NOT remain as the
   read-path effective-price resolution mechanism for the single work price (Requirement 1.3).
6. THE system SHALL update or remove the `ESTIMATE`-resource-scoped CRUD endpoint for
   `EstimateLinePackagePrice` (`/api/estimate-line-package-prices`, including its
   `/{id}/history` endpoint) consistently with the entity's retirement, rather than leaving an
   orphaned endpoint pointing at a removed table.
7. THE system SHALL flag, rather than silently resolve, whether **FOR-05-06**
   (`EstimateLinePackage`/`EstimateLineMargin`, package VALUE/margin views) is superseded,
   absorbed into this spec, or remains a separate downstream consumer that is adjusted to read
   the new single-price + package zł/m² model — this is recorded as an open question (see "Open
   Questions" below). IF FOR-05-06 work is attempted before this ambiguity is resolved, THEN THE
   system/process SHALL **block that work** (not merely warn) until the ambiguity is manually
   resolved, since FOR-05-06's data source is directly affected by this spec's retirement.

### Requirement 9: Boundaries and out of scope

**User Story:** As a maintainer coordinating the FOR-05 series, I want FOR-05-04's boundaries
with neighboring specs written down explicitly, so that margin, offer, e-signature, and real
cost-of-goods work are not duplicated or accidentally pulled into this spec.

#### Acceptance Criteria

1. THE **margin computation and package VALUE presentation** (`EstimateLinePackage`,
   `EstimateLineMargin`) SHALL remain owned by **FOR-05-06**, subject to the reconciliation
   flagged in Requirement 8.7; FOR-05-04 SHALL NOT implement margin or package-value display.
2. THE **offer/approval flow** (`Offer`, `OfferDiscount`) SHALL remain owned by **FOR-05-07**;
   FOR-05-04 SHALL NOT implement offer negotiation or approval.
3. THE **contract and e-signature flow** SHALL remain owned by **FOR-05-09**; FOR-05-04 SHALL
   NOT implement contract or signature handling.
4. THE **real cost-of-goods** for works, materials, finishing items, and products (actual
   purchase cost tracking, as opposed to the catalog/package price model this spec defines)
   SHALL be explicitly OUT OF SCOPE for FOR-05-04 and is called out here as deferred to a future
   spec.
5. THE **temporal client-price resolution** (`resolveClientPrice`, `ProjectServicePrice`
   intervals, the offer price freeze) SHALL remain owned by **FOR-05-14**; FOR-05-04 changes
   only the catalog-level starting point (`OFFER_BASE`) that FOR-05-14 reads, per Requirement
   1.2, and SHALL NOT implement temporal resolution itself.

## Open Questions

The following are flagged for explicit user follow-up rather than silently resolved:

1. **FOR-05-06 reconciliation (Requirement 8.7).** `FOR-05-06-packages-margins` is currently
   scoped (in the FOR-05 parent `OVERVIEW.md`/`design.md`) around `EstimateLinePackage`/
   `EstimateLineMargin` reading a **per-package project price** that this spec retires. Options:
   (a) FOR-05-06 is adjusted to read the new single price + package zł/m² figure instead of the
   retired per-package price, staying a separate spec; (b) part of FOR-05-06's scope (the
   package-value computation) is absorbed into FOR-05-04 since it now depends on the zł/m²
   model defined here; (c) some hybrid. This document assumes **(a)** for planning purposes (see
   the OVERVIEW.md dependency edit: FOR-05-06 now depends on FOR-05-04) but does not treat that
   as decided — confirm before FOR-05-06's own requirements phase starts.
2. **Migration resolution rule (resolved, not open).** Requirement 1.5/8.3 fix the rule: the
   surviving single price is the MAX `netPrice` across the work item's existing package prices
   (reusing the FOR-04-12b MAX-fallback value). This has real financial visibility (a work
   priced differently across packages will surface at its highest package price) — confirm this
   is acceptable with the pricing owner before implementation, since it was resolved by an
   automated analysis pass rather than a direct conversation.
3. **Formula authoring surface.** This spec defines the formula **language/engine** and its
   seeding from `Oferta!X:AJ`/`AL:AN`. Whether administrators author/edit formulas through a
   dedicated UI in this spec or a later one is left open for the design phase; the requirements
   above are UI-agnostic (they describe the engine's behavior, not an editor).
4. **Package naming (resolved, not open).** Confirmed from FOR-04-10's seed requirement: the
   canonical codes/labels are `budget`/Budget/START, `norm`/Norm/COMFORT, `lux`/Lux/PRESTIGE.
   The `Zestawienie` workbook's "Lux" and other documents' "Prestige" refer to the same seeded
   package (`lux`) — there is no unresolved third naming scheme. Recorded here for traceability
   since the task brief asked it to be checked.
5. **Typical-product gating (flagged disagreement).** An automated requirements-analysis pass
   suggested that the typical-product default should be a **mandatory placeholder** — i.e. the
   zł/m² contribution could not be computed until a real product is selected. That reading
   contradicts the task's originating brief, which described the typical product as "only a
   reference link" that must never drive or gate the computed value (mirroring FOR-05-03's
   provenance-FK-never-drives-value pattern). Requirements 6.6/6.7 above follow the brief (link
   is assistive-only, never gates computation). Flagging this explicitly in case the intent was
   actually closer to the analysis pass's reading — confirm which behavior is wanted before
   design.
