# Requirements Document

## Introduction

This spec (**FOR-05-06-packages-margins**) introduces **cost (self-cost / себестоимость)** into the
project-design stage and a new **Margins** tab in the project workspace (`/projects/:projectId`) that
shows, per work type, the offer price against every cost tier and the resulting **profitability
(margin)** for labour and for each material branch.

The feature has three parts:

1. **Worker-type cost dictionary (WorkerType).** A new fully CRUD- and ABAC-managed reference
   dictionary of hiring types, each with an i18n name and a **tier percentage of the offer price**.
   The **base** tier is `0.40` (40% of the offer price); the other tiers are computed **on top of the
   base** by a per-type uplift (from the client's Excel `Pracownicy price` sheet: hired worker without
   own tools `+10%`, hired sole-trader (ИП) with all tools `+26.5%`, firm — 66% of the offer, i.e.
   `+65%` on base). Seeded from that sheet.

2. **Material self-cost.** Both material catalogs (`ConstructionMaterial`, `FinishingMaterial`) gain a
   **materialized `cost_net`** column: computed **once at seed time** as `retailNet − 10%`, then
   editable through the material admin CRUD (new list column + form field). It is a stored value, not
   recomputed.

3. **Margins tab + dashboard.** A new project-workspace tab (design/DRAFT stage) that renders a matrix
   grouped **by work type** — analogous to the Materials tab (FOR-05-05b) but grouped by works — with
   columns: the **offer service price**, the **service cost per worker-type tier**, the
   **construction-material cost**, the **finishing-material cost**, and a final **min/avg/max
   profitability per service**. A dashboard above the matrix surfaces all cost-related figures across
   every worker-type tier (the project is in DRAFT, so all hiring variants are shown).

Two profitability notions are kept **separate** (Requirement decision #4): labour margin and material
margin are computed and displayed **independently**, and material margin is split **per branch**
(construction, finishing). The Margins tab is **read-only** over the estimate/costs; the only writable
surfaces are the WorkerType dictionary CRUD and the per-material `cost_net` edit (both outside the tab
itself).

### Relationship to existing specs (source of truth)

- **Offer / service price** — the work catalog price `WorkPrice.netPrice` (single price per work,
  FOR-05-04) and, for a project, the resolved client price. The **service base cost is computed on the
  fly** from the offer price: `baseCost = round(0.40 × offerPrice, 0.5 zł)`; a tier's cost is
  `baseCost × (1 + upliftOnBasePct/100)`, where the base tier's uplift is 0.
- **Estimate graph** — the works × rooms matrix (FOR-05-05) is the set of works and quantities the
  Margins tab groups and prices; materials come from the same estimate lines' chosen products.
- **Materials** — `ConstructionMaterial` / `FinishingMaterial` retail net prices already exist
  (FOR-04-19/20); this spec adds their self-cost.
- **DRAFT stage** — the Margins tab belongs to the project-design stage (FOR-05), so it presents all
  worker-type variants rather than a single chosen hiring per work.

## Glossary

- **WorkerType**: a new managed dictionary row = a hiring type (e.g. base / hired-no-tools /
  hired-sole-trader / firm) with an i18n name (`nameRU` / `namePL`), a `code`, and a **tier percentage
  of the offer price**. The base type carries `0.40`; the others are computed on top of the base by a
  per-type uplift. Fully CRUD + ABAC managed (new resource `WORKER_TYPES`).
- **Base_Cost**: the service base cost, computed on the fly = `round(0.40 × offerPrice to 0.5 zł)`.
- **Tier_Cost**: a service cost for a given WorkerType = `Base_Cost × (1 + upliftOnBasePct/100)`
  (base tier uplift = 0). Verified against the Excel sheet: `+10%`, `+26.5%`, `+65%` for
  hired-no-tools / sole-trader / firm respectively.
- **Material_Cost**: a material's stored `cost_net` (seeded `retailNet − 10%`, editable).
- **Offer_Service_Price**: the work's offer net price used verbatim (the price the client is charged).
- **Labour_Margin**: profitability on labour for a work = `Offer_Service_Price − Tier_Cost`
  (per tier), expressed absolute and/or as a percentage.
- **Material_Margin**: profitability on materials, computed and shown **per branch** (construction,
  finishing) = `material retail net − material cost_net` (aggregated per work). Kept separate from
  Labour_Margin (decision #4).
- **Min/Avg/Max_Profitability**: for a service, the min / average / max **labour** margin across the
  worker-type tiers (min at the most expensive tier — firm; max at the base tier).
- **Margins_Tab**: the new project-workspace tab ("Маржа / Marża") rendering the works × cost matrix
  and the cost dashboard. Design/DRAFT stage only.
- **Branch**: construction or finishing (the same split as the kosztorys / FOR-05-05).

## Requirements

### Requirement 1 — WorkerType dictionary (CRUD + ABAC)

**User Story:** As an admin, I want to manage a dictionary of worker hiring types with their cost tier
percentages, so the system can compute service cost per hiring option.

#### Acceptance Criteria

1. WHEN an admin opens the WorkerType admin list THEN the system SHALL display all worker types with
   their `code`, localized name (RU/PL per the active locale), and tier percentage.
2. The system SHALL support full CRUD (create / read / update / delete) on WorkerType, guarded by a
   new ABAC resource `WORKER_TYPES` (ADMIN full; read for the roles that can view margins).
3. Each WorkerType SHALL carry: `code` (unique), `nameRU`, `namePL`, and a **tier percentage** field.
   The base type stores `0.40` (40% of offer); non-base types store their **uplift on base** (e.g.
   `0.10`, `0.265`, `0.65`). Exactly one type SHALL be flagged as the **base** tier.
4. WHEN the dictionary is seeded THEN it SHALL contain the four tiers from the Excel `Pracownicy price`
   sheet: base (0.40 of offer), hired-without-own-tools (+10% on base), hired-sole-trader-with-tools
   (+26.5% on base), firm (+65% on base). Seed is idempotent.
5. WHEN a tier percentage is invalid (negative, or a base share outside a sane range) THEN the system
   SHALL reject the write with a localized validation message.
6. The dictionary rows SHALL carry `active` and an `orderNo` for stable display ordering, consistent
   with the other reference dictionaries.

### Requirement 2 — Service cost computed on the fly

**User Story:** As a foreman, I want the service cost per hiring tier derived from the current offer
price, so cost tracks price changes automatically.

#### Acceptance Criteria

1. The system SHALL compute `Base_Cost = round(0.40 × Offer_Service_Price to 0.5 zł)` on the fly from
   the current offer price; it SHALL NOT store the base cost.
2. The system SHALL compute each tier's `Tier_Cost = Base_Cost × (1 + upliftOnBasePct/100)`, where the
   base tier's uplift is 0 (so `Tier_Cost(base) = Base_Cost`).
3. WHEN the offer price changes THEN the computed base cost and tier costs SHALL change accordingly on
   the next read (no persisted cost to become stale).
4. WHEN the offer price is null/zero THEN the base cost and all tier costs SHALL be zero (no fabricated
   value), and the margin SHALL be rendered as unavailable rather than a spurious figure.
5. The rounding rule SHALL be exactly "round to the nearest 0.5 zł" (half-up), matching the sheet.

### Requirement 3 — Material self-cost (materialized column)

**User Story:** As an admin, I want each material to carry a self-cost, so material profitability can
be computed.

#### Acceptance Criteria

1. Both `construction_materials` and `finishing_materials` SHALL gain a `cost_net` numeric column.
2. WHEN the column is added by migration THEN each existing row SHALL be back-filled once with
   `cost_net = retailNet − 10%` (i.e. `retailNet × 0.90`), rounded to 2 decimals.
3. WHEN `retailNet` is null for an existing row THEN `cost_net` SHALL be back-filled with `0` (or a
   documented sentinel) rather than null, so the column can be NOT NULL after back-fill; the exact
   nullability is fixed in design (see design "Data Models").
4. The material admin **list** SHALL show a `cost_net` column, and the material **form** SHALL let an
   admin edit `cost_net`. `cost_net` is a stored value; it is NOT recomputed from `retailNet` after
   the initial seed.
5. Editing `retailNet` SHALL NOT silently overwrite an edited `cost_net` (they are independent after
   seed); design MAY offer a convenience "recompute from retail" action but MUST NOT auto-overwrite.

### Requirement 4 — Margins tab (works × cost matrix), separate labour/material margins

**User Story:** As a foreman/manager, I want a Margins tab that shows, per work type, the offer price
against each cost tier and the resulting profitability, so I can judge pricing before signing.

#### Acceptance Criteria

1. The system SHALL add a new **Margins** tab to the project workspace (design/DRAFT stage), guarded by
   an ABAC read permission; it is **read-only** over the estimate/costs.
2. The tab SHALL render a matrix **grouped by work type** (analogous to the Materials tab layout but
   grouped by works), with one row per work (or per work type group) present in the project estimate.
3. Each row SHALL show columns in this order: (a) **Offer_Service_Price**; (b) one **Tier_Cost** column
   per WorkerType tier (in dictionary order); (c) **construction-material cost** for the work;
   (d) **finishing-material cost** for the work; (e) a final **min / avg / max profitability** for the
   service.
4. **Labour margin and material margin SHALL be computed and shown separately** (decision #4). The
   min/avg/max profitability column reflects the **labour** margin across tiers. **Material margin SHALL
   be shown per branch** (construction vs finishing), not merged into the labour margin.
5. WHEN a work has no offer price THEN its cost/margin cells SHALL render as unavailable (`—`), not as
   zero-derived margins, and SHALL be excluded from aggregate margin figures.
6. The min/avg/max labour profitability SHALL be computed across the worker-type tiers: **min** margin
   at the most expensive tier (firm), **max** at the base tier, **avg** the mean across tiers.
7. The matrix rows SHALL group by work type consistent with the estimate's grouping (same category
   ordering as the kosztorys), and the tab SHALL support the existing empty/no-data states.

### Requirement 5 — Cost dashboard

**User Story:** As a foreman/manager, I want a dashboard of all cost-related figures across hiring
tiers, so I can see the full cost/margin picture while the project is in DRAFT.

#### Acceptance Criteria

1. The Margins tab SHALL render a dashboard above the matrix summarizing all cost-related figures.
2. Because the project is in DRAFT, the dashboard SHALL present **every worker-type tier variant**
   (total labour cost per tier, total labour margin per tier, etc.) rather than a single chosen hiring.
3. The dashboard SHALL show, per branch and project-wide: total offer (revenue) for labour, total
   labour cost per tier, total labour margin per tier (absolute and %); total material retail, total
   material cost, and total material margin **per branch** (construction, finishing).
4. Rows/materials with no price/cost SHALL be excluded from the dashboard totals (no fabricated value).
5. All dashboard figures SHALL be derived live from the estimate + WorkerType dictionary + material
   costs; nothing is persisted by the tab.

### Requirement 6 — Seed from the Excel `Pracownicy price` sheet

**User Story:** As the system owner, I want the worker-type tiers seeded from the client's Excel, so the
initial cost model matches the client's spreadsheet.

#### Acceptance Criteria

1. The WorkerType dictionary SHALL be seeded (idempotent Liquibase changeset) with the four tiers whose
   percentages are taken from the `Pracownicy price` sheet: base = 0.40 (of offer);
   hired-without-own-tools = +0.10 on base; hired-sole-trader = +0.265 on base; firm = +0.65 on base
   (firm = 0.66 of offer).
2. The seed SHALL set localized names (RU/PL) for each tier and mark the base tier.
3. The seed SHALL be guarded so re-running against an already-seeded database inserts nothing.

### Requirement 7 — i18n (PL/RU parity)

**User Story:** As a bilingual user, I want all new UI text and dictionary names available in Polish
and Russian.

#### Acceptance Criteria

1. The `WorkerType` entity SHALL carry `nameRU` / `namePL` (the standard dictionary i18n pattern); no
   other new entity field requires i18n (percentages and `cost_net` are numeric).
2. All new frontend text (the Margins tab title `workspace.tab.margins`, column headers, tier labels,
   margin min/avg/max labels, dashboard metric labels, the WorkerType admin CRUD strings, and the new
   material `cost_net` list/form labels) SHALL be added to BOTH `pl.json` and `ru.json` at strict key
   parity with non-empty values.
3. No raw i18n key SHALL be surfaced on any new screen.

### Requirement 8 — (Later, not in this spec) WorkerType in team selection

**User Story (deferred):** As a foreman, I want to assign a WorkerType per project member during team
selection (a user + hiring type, or a hiring type without a user for external access).

#### Acceptance Criteria

1. This spec SHALL model `WorkerType` so it can later be referenced by a project member (`{ user?,
   workerType }`, user nullable for external), but SHALL NOT build the team-selection wiring here.
2. No `ProjectMember` schema change is required by this spec; the reference is a future FOR-05-10 /
   FOR-06 concern noted for forward compatibility.

## Non-Goals

- Building the team-selection wiring that consumes WorkerType (deferred, Requirement 8).
- Changing offer prices, estimate volumes, chosen products, or the kosztorys itself (read-only tab).
- Persisting any per-project cost snapshot (costs are computed on the fly; only material `cost_net`
  and the WorkerType dictionary are stored).
- Payroll / actual worker payout (owned by FOR-10/FOR-11); this spec is design-stage cost modelling.
