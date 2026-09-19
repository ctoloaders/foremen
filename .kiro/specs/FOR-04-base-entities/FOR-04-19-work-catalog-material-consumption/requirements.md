# Requirements Document

FOR-04-19: Work-catalog material consumption — a new operational entity that binds each work item to its material-consumption norms per offer package, plus a computed money price-range (вилка) per package aggregated onto the work-catalog list.

## Introduction

FOR-04-19 delivers the material-consumption layer of the FOR-04 model: for every
work item (FOR-04-11) it records, per offer package (FOR-04-10), the list of
materials consumed and how much of each is used per unit of work. The data source
is the completed `FOR-04-RESEARCH-material-norms` artifact
(`work-material-norms.csv`: 615 norm rows over 205 works, plus
`construction-materials.csv` and `new-measurement-units.csv`).

The spec ships EXACTLY ONE new operational entity **WorkMaterialConsumption**
(resource `WORK_MATERIAL_CONSUMPTION`, table `work_material_consumptions`, path
`/api/work-material-consumptions`). It is a GLOBAL admin resource (not
project-scoped), managed through its own admin catalog page — like the sibling
`WorkPackagePrice` (FOR-04-12b), it is a per-work-item child row managed on its
own, and it surfaces on the work-catalog list only through AGGREGATION.

Each `WorkMaterialConsumption` row is one (work item, offer package, material
TYPE) norm: it references a `WorkItem`, an `OfferPackage`, a **branch**
(`construction` | `finishing`), EXACTLY ONE **material type** (a
`ConstructionMaterialType` for the construction branch XOR a finishing material
type for the finishing branch — the analog GROUP, never a single concrete
material), a **material unit** (the numerator unit — kg/l/m²/m³/szt), the
**consumption norm** `normQty` (material-unit per one work-unit), and a bilingual
**justification** (`justificationRU`/`justificationPL`) plus a normative
**citation** (`sourceType`/`sourceDoc`/`sourceUrl`/`sourceRef`) carried straight
from the research CSV. The **price range** is formed at read time by pulling ALL
materials of that type present in the row's package and taking MIN..MAX of
`normQty × retailNet` (no materials of the type in the package → explicit `0`; a
single material → range collapses to one value). The row's localized names (type,
unit, package) come from the referenced dictionaries; the ONLY i18n text OWNED by
this entity is the `justification` pair.

**Grouping model (confirmed):** consumption is grouped **by offer package**, and
within a package **by material type** (the analog batch). Each row carries a
`normQty` against a material TYPE; the batch is formed at read time by pulling ALL
materials of that type that belong to the package (e.g. type `SILIKON_SANITARNY`
pulls every silicone offered in that package) and taking `normQty × [MIN..MAX
retailNet]` — a real band when several analogs exist, one value when a single
material exists, and an explicit `0` when NO material of the type is in the package
(so a missing item in the catalog is visible). Summing those type-level money bands
across all types of a (work, package, branch) yields the branch's money range; the
two branch ranges are exposed separately.

**Computed price range (вилка), money-only.** Nothing is stored. For each
(work item, offer package) the system computes TWO money ranges per work-unit:
a **construction** range and a **finishing** range, each a MIN..MAX in PLN. The
range for a branch is the sum, over that branch's material types in the package,
of the type-level `normQty × materialUnitPrice` batch min and max (the type-level
min uses the analog batch's min consumption × min price; the max uses max × max).
A branch with NO consumption rows for a (work, package) yields an EXPLICIT `0`
(not blank) so a missing/absent norm is visible. Consumption quantities in
physical units are NOT summed across types (kg + szt + m² do not add) — only the
money ranges sum; physical per-material consumption is shown only in the drill-in
detail.

**Work-catalog aggregation & drill-in.** The work-catalog list (rows = `WorkItem`)
gains synthetic, computed pivot columns per seeded package (mirroring the
FOR-04-12b price pivot). Each package column exposes **THREE** prices for the work
in that package: (1) the **work labour price** (the existing FOR-04-12b
`WorkPackagePrice` net price for that work+package), (2) the **construction
material range** (вилка), and (3) the **finishing material range** (вилка). The two
material ranges are the branch money ranges defined above; the labour price is the
already-existing per-package work price surfaced alongside them for a complete
per-package cost picture. Reading the table shows ONLY these synthetic computed
fields. Clicking a work's package cell drills in and
loads the exhaustive material list for that (work, package): grouped by branch and
then by material type (analog batch), each material showing its `normQty` in the
material unit per work-unit AND the money cost per work-unit (`normQty ×
materialUnitPrice`), the analog batch's min/max, the material characteristics
(name, producer, seller, unit, price), and the justification + citation.

The data seed is generated from the research CSV by a build/seed step (mirroring
FOR-04-18's generated seed): it resolves each row's `work_code` to a `WorkItem`
(via a NEW stable `work_items.code` populated from the Excel `LP`), its package to
an `OfferPackage`, its material type to the analog-group dictionary, and its
material unit to `MEASUREMENT_UNITS` (adding kg/l if absent), inserting nothing for
an unresolved required reference and carrying the `justification`/citation columns
onto the row. This research artifact also supersedes the FOR-04-17 placeholder
construction-materials seed.

This spec follows FOR-04-15's deferred menu wiring: it adds a "Расход материалов /
Material Consumption" navigation item under the "Каталог / Catalog" section
pointing to `/catalog/material-consumption`, guarded by an interim
`WORK_MATERIAL_CONSUMPTION`/`READ` route guard.

Depends on FOR-04-11 (`WORK_CATALOG`/`WorkItem`), FOR-04-12b
(`WORK_PRICES`/pivot-column pattern), FOR-04-10 (`OFFER_PACKAGES`), FOR-04-02
(`MEASUREMENT_UNITS`), FOR-04-16/17 (`CONSTRUCTION_MATERIAL_TYPES`,
`MATERIALS_CONSTRUCTION`), FOR-04-18 (`MATERIALS_FINISHING`), FOR-04-01 (reference
filter + metadata), FOR-03-08 (permission annotations + startup validation), and
consumes the `FOR-04-RESEARCH-material-norms` artifact. Forward reference: FOR-05
project estimate copies these computed ranges as a snapshot at add-time (never
binds by FK), exactly as it treats catalog prices.

## Glossary

- **WorkMaterialConsumption**: NEW operational entity (resource `WORK_MATERIAL_CONSUMPTION`, table `work_material_consumptions`, path `/api/work-material-consumptions`) — one (work item, offer package, material TYPE) consumption norm with a branch, exactly one analog-group material type (construction XOR finishing), material unit, `normQty`, bilingual `justification`, and a normative citation. The price range is pulled from the type's materials in the package. Managed on its own admin page; aggregated onto the work catalog.
- **WorkItem**: EXISTING work-catalog entity (FOR-04-11, `WORK_CATALOG`) referenced by FK; gains a new stable `code` (from the Excel `LP`) in this spec. Not otherwise recreated.
- **OfferPackage**: The FOR-04-10 dictionary (`OFFER_PACKAGES`, `budget`/START, `norm`/COMFORT, `lux`/PRESTIGE) grouping consumption per package.
- **Branch**: fixed enum `construction` | `finishing` — a work may have consumption rows in one, both, or neither branch.
- **Material type (analog group)**: the classifier grouping interchangeable materials, referenced DIRECTLY by the consumption row (exactly one per row, matching branch) — `ConstructionMaterialType` (FOR-04-17 `CONSTRUCTION_MATERIAL_TYPES`) for construction, the finishing material type (FOR-04-16 `MATERIAL_TYPES`) for finishing. The batch = all materials of the type present in the row's package; its min/max `retailNet` drive the range.
- **Analog batch**: the set of concrete materials (`construction_materials` / `finishing_materials`) of the row's material type whose `packages` contains the row's `offerPackage`. Pulled at read time to compute `normQty × [MIN..MAX retailNet]`. Empty batch → `0`; single material → collapsed range.
- **Material unit**: the numerator `MeasurementUnit` of the consumption (kg/l/m²/m³/szt), distinct from the work item's own unit (the denominator).
- **normQty**: consumption of the material unit per ONE work unit (e.g. 5 kg glue per m² of tiling). Non-negative decimal.
- **Consumption money cost (per work-unit)**: `normQty × materialUnitPrice` (the material's `retailNet`) in PLN, per one work unit.
- **Type-level batch range**: within a (work, package, type) analog batch, MIN..MAX of the money cost — min = min(normQty)×min(retailNet), max = max(normQty)×max(retailNet) across the batch's materials.
- **Branch money range (вилка)**: per (work, package, branch), the SUM over that branch's types of the type-level batch min (range min) and max (range max), in PLN per work-unit. Money-only; `0` when the branch has no rows.
- **Justification**: bilingual `justificationRU`/`justificationPL` free text carried from the research CSV into the row — the ONLY i18n text owned by this entity; rendered in the drill-in detail.
- **Citation**: `sourceType` (`producer_tds`|`knr`|`retail`|`excel`|`expert`), `sourceDoc`, `sourceUrl`, `sourceRef` — the normative provenance of the norm, carried from the research CSV.
- **WORK_MATERIAL_CONSUMPTION**: the ABAC resource guarding `WorkMaterialConsumptionController`.
- **System**: the Foremen backend + frontend under this spec.
- **PL fallback**: localized text resolves to the `ru` variant for the `ru` locale, else the `pl` variant.

## Requirements

### Requirement 1: WorkItem stable code

**User Story:** As a developer, I want each work item to carry a stable code, so that the research consumption seed can reference a work by a durable key.

#### Acceptance Criteria

1. THE System SHALL add a `code` field to `WorkItem` (String, column `code`), populated from the Excel positional `LP` normalized to `N.MM` (e.g. `1.01`, `2.10`).
2. THE `work_items.code` column SHALL be added by an idempotent additive Liquibase changeset (`addColumn` guarded by `columnExists` + `MARK_RAN`), and a data changeset SHALL backfill `code` for the existing seeded work items by matching the Excel row order/name, guarded so a re-run changes nothing.
3. WHERE `work_items.code` is populated, THE System SHALL enforce uniqueness of non-null `code` values (unique index allowing nulls), and SHALL expose `code` on the work-catalog DTO.
4. IF a work item has no derivable `LP` code, THEN THE System SHALL leave its `code` null and SHALL NOT fail the migration.

### Requirement 2: WorkMaterialConsumption entity & table

**User Story:** As a developer, I want a WorkMaterialConsumption entity, so that each work item records its per-package material-consumption norms with provenance.

#### Acceptance Criteria

1. THE System SHALL map a `WorkMaterialConsumptionEntity` extending `BaseEntity` with NO localized `name`/`code`; its only i18n fields SHALL be `justificationRU` and `justificationPL` (both text, nullable).
2. THE entity SHALL define a mandatory `@ManyToOne workItem` (`work_item_id` NOT NULL, FK to `work_items`), a mandatory `@ManyToOne offerPackage` (`offer_package_id` NOT NULL, FK to `offer_packages`), and a mandatory `@ManyToOne materialUnit` (`material_unit_id` NOT NULL, FK to `measurement_units`). The material unit is the numerator of the norm and comes from the research CSV `material_unit`; it SHOULD match the unit of the type's materials in the package.
3. THE entity SHALL define a mandatory `branch` field (enum `construction`|`finishing`, `NOT NULL`, stored as a string/varchar).
4. THE entity SHALL reference a MATERIAL TYPE (the analog group), NOT a concrete material — a nullable `@ManyToOne construction_material_type_id` (FK to `construction_material_types`, FOR-04-17) and a nullable `@ManyToOne finishing_material_type_id` (FK to `material_types`, FOR-04-16) — of which EXACTLY ONE SHALL be non-null, matching `branch` (`construction` → `construction_material_type_id`, `finishing` → `finishing_material_type_id`). The price range is formed at read time by pulling ALL materials of that type that belong to the row's `offerPackage` and taking MIN..MAX of `normQty × retailNet`; a type with several package materials yields a real range, a single material collapses it to one value, and a type with NO materials in that package yields an explicit `0`.
5. THE entity SHALL define a mandatory `normQty` (`NUMERIC(12,4)`, `NOT NULL`, `>= 0`) — material-unit per one work-unit.
6. THE entity SHALL define an optional `wastePct` (`NUMERIC(5,2)`, nullable) and the citation fields `sourceType` (string, NOT NULL), `sourceDoc` (string, NOT NULL), `sourceUrl` (string ≤ 1024, nullable), `sourceRef` (string, NOT NULL).
7. WHEN the table-creation changeset runs against a database without `work_material_consumptions`, THE System SHALL create it with the columns above plus the four `BaseEntity` audit columns and the FKs, and IF the table already exists THEN THE System SHALL `MARK_RAN` via `tableExists` and make no schema change.
8. THE `WorkMaterialConsumptionService` SHALL NOT implement `ProjectScopedService` (global admin resource).

### Requirement 3: WorkMaterialConsumption CRUD, validation & references

**User Story:** As an API consumer, I want standard CRUD for consumption norms with reference validation, so that norms are managed with the shared framework and invalid references are rejected.

#### Acceptance Criteria

1. THE System SHALL expose the generic `AdminController` create, list, read, update, delete, count, metadata, and i18n operations at `/api/work-material-consumptions`, annotated `@PermissionResource("WORK_MATERIAL_CONSUMPTION")`.
2. WHEN a create or update request is received, THE System SHALL require `workItemId`, `offerPackageId`, `branch`, `materialUnitId`, `normQty`, and EXACTLY ONE material-type id (`constructionMaterialTypeId` XOR `finishingMaterialTypeId`).
3. IF a create or update omits any required field, THEN THE System SHALL reject the request with a client error identifying the missing field, and persist no changes.
4. IF a create or update references a `workItemId`, `offerPackageId`, `materialUnitId`, `constructionMaterialTypeId`, or `finishingMaterialTypeId` that does not exist, THEN THE System SHALL reject with a client error identifying the offending field, and persist no changes.
5. IF a create or update supplies `normQty` that is negative or outside `[0, 99999999.9999]`, THEN THE System SHALL reject with a client error identifying `normQty`, and persist no changes.
6. IF NEITHER or BOTH material-type ids are set, OR the set type does not match `branch` (`construction` requires `constructionMaterialTypeId`, `finishing` requires `finishingMaterialTypeId`), THEN THE System SHALL reject with a client error, and persist no changes.
7. WHEN the caller lists `/api/work-material-consumptions` with a reference filter on `workItem.id`, `offerPackage.id`, `materialUnit.id`, `constructionMaterialType.id`, `finishingMaterialType.id`, or a `branch` filter, THE System SHALL return only matching rows (FOR-04-01 grammar).
8. THE `justificationRU`/`justificationPL` SHALL resolve on read via PL fallback (the `ru` variant for `ru`, else the `pl` variant) when a single localized `justification` is requested; both raw variants SHALL be exposed on the extended DTO for editing.

### Requirement 4: Computed money price-range (вилка) per package & branch

**User Story:** As an estimator, I want a computed money range of material cost per package, split into construction and finishing, so that I can see the material cost band for each work without opening every material.

#### Acceptance Criteria

1. THE System SHALL compute, for each (work item, offer package), a **construction** money range and a **finishing** money range, each a `{min, max}` in PLN per one work-unit, derived at read time and never persisted.
2. THE System SHALL compute a type-level batch range for each consumption row (work item, offer package, branch, material type, `normQty`) as `min = normQty × MIN(retailNet)` and `max = normQty × MAX(retailNet)`, where MIN/MAX `retailNet` are taken over ALL active materials of that type (construction: `construction_materials` of that `ConstructionMaterialType`; finishing: `finishing_materials` of that finishing type) whose `packages` set contains the row's `offerPackage`. A type with several package materials yields a real min..max; a single material collapses min = max; a type with NO materials in the package yields batch `0..0` (the norm still shows in the drill-in with a `0`/`—` cost). WHERE two consumption rows in the same (work, package, branch) reference the same type, the batch is computed once per distinct type.
3. THE System SHALL compute a branch money range as the SUM over that branch's material types of their type-level batch `min` (range min) and `max` (range max).
4. WHERE a (work item, offer package) has NO consumption rows for a branch, THE System SHALL return an EXPLICIT `0` (both min and max) for that branch range, distinguishable from an absent computation.
5. THE System SHALL NOT sum consumption quantities across different material units (physical quantities are not additive); only money cost is summed.
6. WHERE a material of the type in that package has a null `retailNet`, THE System SHALL exclude it from the type's MIN/MAX price; WHERE the type has NO priced material in the package, its batch contributes `0`.
7. THE range computation SHALL be deterministic: the same set of consumption rows and material prices SHALL always produce the same min and max.

### Requirement 5: Work-catalog aggregation (pivot columns) & drill-in

**User Story:** As an estimator, I want the work-catalog list to show, per package, three prices — the work labour price, the construction material range, and the finishing material range — and to drill into the full material list on click, so that I read synthetic per-package totals but can inspect every consumable.

#### Acceptance Criteria

1. THE System SHALL expose, on the work-catalog list row (row = `WorkItem`), a synthetic computed pivot field per seeded offer package carrying THREE prices for that (work, package), keyed by `offerPackage.id` (mirroring the FOR-04-12b pivot pattern): (a) the **work labour price** — the existing FOR-04-12b `WorkPackagePrice` net price for that work+package (surfaced, not recomputed); (b) the **construction material range** `{min,max}`; and (c) the **finishing material range** `{min,max}`. The two material ranges are the branch money ranges of Requirement 4.
2. WHEN a branch has no consumption for a (work, package), THE System SHALL render its sub-value as an explicit `0` (min and max both `0`), so a missing norm is visible rather than blank.
3. THE work-catalog list read SHALL remain paginated over distinct `WorkItem` rows; the synthetic pivot fields SHALL NOT multiply or split rows.
4. WHEN the caller requests the drill-in for a (work item, offer package), THE System SHALL return the exhaustive consumption list, each row carrying: its `branch`, its material `type` (the analog group), its `normQty` in the material unit per work-unit, the type-level money band per work-unit (`normQty × [MIN..MAX retailNet]` over the type's materials in the package), the LIST of analog materials of that type in the package (each with name, producer, seller, unit, `retailNet`, and per-material money cost `normQty × retailNet`), and the `justification` + citation. The frontend groups the rows by branch then by `type` (analog batch) and renders the band and the expandable analog list.
5. THE drill-in SHALL reuse the standard list endpoint `GET /api/work-material-consumptions` via the FOR-04-01 query DSL — filtering by `workItem.id==` AND `offerPackage.id==` with a deliberately large `size` (render all rows without pagination) — rather than a bespoke endpoint; it is permission-gated by the same `WORK_MATERIAL_CONSUMPTION`/`READ` as the list.
6. THE work-catalog `/metadata` SHALL advertise one synthetic pivot descriptor per seeded package (field name embedding `offerPackage.id`, dataType carrying the three prices — labour price, construction range, finishing range — with the package's localized label), so the frontend can render the per-package columns.

### Requirement 6: ABAC resource & grants

**User Story:** As a security admin, I want WORK_MATERIAL_CONSUMPTION guarded by ABAC per the FOR-04 matrix, so that only authorized roles modify consumption norms.

#### Acceptance Criteria

1. THE seed SHALL insert the `WORK_MATERIAL_CONSUMPTION` resource row with non-null `code`, `name_ru`, `name_pl`, `description_ru`, `description_pl`.
2. THE seed SHALL grant ADMIN CREATE/READ/UPDATE/DELETE, MANAGER CREATE/READ/UPDATE (no DELETE), and FOREMAN/WORKER/FINANCIER READ; CLIENT SHALL receive no grant (deny-by-default).
3. THE seed SHALL grant DELETE on `WORK_MATERIAL_CONSUMPTION` to ADMIN only, and to no other role.
4. THE `WorkMaterialConsumptionController` SHALL be annotated `@PermissionResource("WORK_MATERIAL_CONSUMPTION")` whose value matches the seeded resource `code`, and each inherited CRUD handler SHALL carry its `@PermissionOperation` (no custom endpoints; the drill-in rides the generic list handler).
5. IF the controller has any in-scope guarded handler lacking a matching permission annotation (or the reverse), THEN THE application SHALL fail to start during `PermissionAnnotationValidator` validation.
6. IF a seed changeset re-runs where its rows already exist, THEN THE changeset SHALL insert zero additional rows, guarded by `<preConditions onFail="MARK_RAN">` with an `<sqlCheck expectedResult="0">`.
7. THE new changeset files SHALL be registered last in `changelog.xml`, after the current highest, following `.kiro/steering/entity-creation-rules.md`.

### Requirement 7: Data seed from the research artifact (materials FIRST, then consumption)

**User Story:** As a delivery lead, I want the real construction materials seeded first and then the consumption norms (by type) added, so that each norm's analog batch resolves to actual materials and the price range forms from them.

#### Acceptance Criteria

1. THE System SHALL FIRST seed the real construction materials from `FOR-04-RESEARCH-material-norms/construction-materials.csv`, REPLACING the FOR-04-17 placeholder construction-materials seed, via a repeatable generation step; the seed SHALL resolve each material's `type` to `CONSTRUCTION_MATERIAL_TYPES` (seeding any missing type), its `producer`/`seller` to `MATERIAL_PRODUCERS`/`MATERIAL_SELLERS` (seeding any missing), its `unit` to `MEASUREMENT_UNITS`, its `packages` to `OFFER_PACKAGES`, and its `currency` to `CURRENCIES`, and SHALL be idempotent (`sqlCheck` + `MARK_RAN`). This seed MUST run before the consumption seed so the analog batch (all materials of a type in a package) exists for the range to pull.
2. THE System SHALL THEN seed `work_material_consumptions` from `work-material-norms.csv` via a repeatable generation step (mirroring the FOR-04-18 CSV-to-SQL generator), documenting the generation approach in the changeset header.
3. WHEN seeding a consumption row, THE System SHALL resolve `work_code` to a `WorkItem` by its new `code`, `packages` to `OfferPackage`(s) (fanning one CSV row with multiple packages into one consumption row per package), and the CSV `material_type` to the analog-group TYPE dictionary matching `branch` — a construction row resolves the type to `CONSTRUCTION_MATERIAL_TYPES`, a finishing row to the FOR-04-16 finishing type dictionary — copying `material_unit`, `norm_qty`, `waste_pct`, `branch`, `justification_pl`/`justification_ru`, and the citation columns. It does NOT reference a concrete material.
4. IF a consumption row's `work_code`, offer package, or `material_type` does not resolve to a seeded type, THEN THE System SHALL insert no row for that (row, package), and the generation step SHALL record it in the generator output for review (never fabricate a type).
5. WHERE the CSV introduces a material unit absent from `MEASUREMENT_UNITS` (e.g. `kg`, `l`), THE System SHALL add it via an idempotent seed of `new-measurement-units.csv` before the material seed.
6. ALL seeds SHALL be idempotent (`sqlCheck` + `MARK_RAN`), so a re-run inserts no duplicates, and SHALL be registered last in `changelog.xml` in dependency order (units → construction material types → construction materials → consumption).
7. THE consumption seed SHALL preserve the `branch` value from the CSV on each row.
8. WHERE the research CSV carries a `material_type` that does not exist as a seeded analog-group type (construction or finishing), THE generation step SHALL FIRST seed that type (from the distinct `material_type` values in the CSV) so every consumption row resolves; a construction type is seeded into `CONSTRUCTION_MATERIAL_TYPES`, a finishing type into the FOR-04-16 finishing type dictionary.

### Requirement 8: Admin CRUD UI + work-catalog integration

**User Story:** As an admin, I want a material-consumption admin page and per-package material columns on the work catalog with drill-in, so that I can manage norms and read material cost bands without the API.

#### Acceptance Criteria

1. THE System SHALL provide a lazy-loaded page `WorkMaterialConsumptionsPage` at `/catalog/material-consumption` using the shared `DataTable` with server search/sort/filter/pagination (rows = `WorkMaterialConsumption`), with columns `workItem`, `offerPackage`, `branch`, `materialType`, `material`, `materialUnit`, `normQty`.
2. THE page create/edit form SHALL provide a required `workItem` selector, required `offerPackage` selector, required `branch` toggle, required `materialType` selector, optional concrete `material` selector (filtered by branch), required `materialUnit` selector, required `normQty` input, optional `wastePct`, `justificationRU`/`justificationPL` inputs, and citation inputs (`sourceType`, `sourceDoc`, `sourceUrl`, `sourceRef`); it SHALL have no `code`/`name` field.
3. IF a form field fails validation on submit (missing required reference, `normQty` out of range, branch/material mismatch), THEN THE System SHALL block submission, retain entered values, and display a localized message per invalid field.
4. THE work-catalog list (FOR-04-11/12b page) SHALL render, per seeded package, a computed column showing THREE prices for the work in that package: the work labour price (FOR-04-12b), the construction material range, and the finishing material range (rendering `0` where a branch has no norm).
5. WHEN a user clicks a work's package material cell, THE System SHALL open a drill-in panel loading the exhaustive material list grouped by branch → material type (analog batch), each material showing `normQty` in the material unit per work-unit AND the money cost per work-unit, plus the batch min/max and the material characteristics and the justification + citation.
6. THE list fetch SHALL use the shared `buildFetchQuery`; reference filters SHALL follow FOR-04-01; mutations SHALL invalidate the consumption list query (and the work-catalog aggregation query) and show a toast.
7. WHERE the current user lacks CREATE/UPDATE/DELETE on `WORK_MATERIAL_CONSUMPTION`, THE System SHALL hide the corresponding control (`usePermission`).
8. THE System SHALL provide a delete confirmation dialog.
9. THE System SHALL add a "Расход материалов / Material Consumption" navigation item under the "Каталог / Catalog" section pointing to `/catalog/material-consumption`, ABAC-gated on `WORK_MATERIAL_CONSUMPTION` READ, with an interim route guard until menu wiring.
10. THE System SHALL render all UI text from keys under `workMaterialConsumption.*` plus `nav.materialConsumption`, defined at parity in BOTH `pl.json` and `ru.json`, and SHALL NOT render any raw i18n key.

### Requirement 9: i18n keys & values (PL + RU)

**User Story:** As a maintainer, I want the exact PL/RU keys and values specified, so that localization ships complete and at parity.

#### Acceptance Criteria

1. THE System SHALL define, at parity in `pl.json` and `ru.json`, the `workMaterialConsumption.*` namespace and `nav.materialConsumption`, with non-empty values in both locales, per the table in the design document (page title, table headers, branch labels `construction`/`finishing`, form labels, drill-in labels — consumption-per-unit, cost-per-unit, analog batch, min/max, source/justification —, empty state, toasts, validation messages).
2. THE branch enum SHALL render via localized labels (`workMaterialConsumption.branch.construction` / `.finishing`), never the raw enum value.
3. THE per-package pivot column SHALL render the package's localized name as its header and localized sub-labels for the three prices — work labour price (`workMaterialConsumption.price.labour`), construction material range (`workMaterialConsumption.price.construction`), finishing material range (`workMaterialConsumption.price.finishing`) — in both PL and RU.
4. THE `justification` shown in the drill-in SHALL use the localized variant (PL fallback); the citation `sourceType` SHALL render via localized labels (`workMaterialConsumption.sourceType.*`).

### Requirement 10: Backend tests

**User Story:** As a maintainer, I want integration and property tests, so that CRUD, validation, the range computation, aggregation, the grant matrix, and the seed stay verified.

#### Acceptance Criteria

1. THE System SHALL have a CRUD integration test (Testcontainers) covering create/read/update/delete with required references, empty-set/missing-field rejection, branch/material-mismatch rejection, and `normQty` range boundaries.
2. THE System SHALL have an integration test asserting reference filters (`workItem.id`, `offerPackage.id`, `materialType.id`, `materialUnit.id`, `branch`) return only matching rows.
3. THE System SHALL have a property-based test for the money-range computation: for any set of consumption rows and material prices, the branch range equals the sum of type-level batch min/max, a branch with no rows yields explicit `0`, null-`retailNet` materials are excluded, and physical quantities are never summed across units.
4. THE System SHALL have an integration test for the work-catalog aggregation: the pivot field per package carries the three prices (work labour price, construction range, finishing range), `0` where a material branch is absent, and the list stays paginated over distinct work items.
5. THE System SHALL have an integration test for the drill-in read: grouping by branch → type, per-material consumption + money cost, batch min/max, and justification/citation presence.
6. THE System SHALL have a Liquibase seed integration test asserting the `WORK_MATERIAL_CONSUMPTION` resource and the exact grant matrix (CLIENT none, MANAGER no-DELETE, DELETE ADMIN-only) and re-run idempotency.
7. THE System SHALL have a seed integration test asserting the CSV seed populated `work_material_consumptions` with resolved references and preserved branch/justification/citation for representative rows, that multi-package CSV rows fanned into one row per package, and that a re-run inserts no duplicates.
8. THE System SHALL have a clean-startup smoke test confirming the fully annotated `WorkMaterialConsumptionController` starts under `PermissionAnnotationValidator`.
9. THE System SHALL have an integration test for the `work_items.code` backfill: codes are populated and unique for representative rows, nulls allowed, and re-run idempotent.

### Requirement 11: UI tests

**User Story:** As a maintainer, I want component and localization tests, so that list rendering, the pivot columns, drill-in, permission gating, and translations stay verified.

#### Acceptance Criteria

1. THE System SHALL have a component test for `WorkMaterialConsumptionsList` asserting the listed columns render, permission-gated controls appear only with the permission, and a localized empty state renders.
2. THE System SHALL have a UI test asserting the work-catalog per-package column renders all three prices (work labour price, construction range, finishing range), including explicit `0` for a branch with no norm.
3. THE System SHALL have a UI test asserting the drill-in panel loads and renders the material list grouped by branch → type with per-material consumption + money cost and the justification/citation.
4. THE System SHALL have a key-parity test for `workMaterialConsumption.*` and `nav.materialConsumption` across `pl.json`/`ru.json`, all non-empty, no raw key rendered.
