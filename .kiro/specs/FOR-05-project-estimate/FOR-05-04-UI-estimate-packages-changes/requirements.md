# Requirements Document

## Introduction

This spec (**FOR-05-04-UI-estimate-packages-changes**) is the **UI/alignment follow-up** to the
backend-only spec **FOR-05-04-estimate-packages-changes**. The backend spec changed the estimate
model: it retired per-package `WorkPrice` in favor of a single work price, added a room-dimension
formula engine (default volume formula per work item) plus per-package override formulas, and
introduced a zł/m² package assortment (`AssortmentGroup` + `AssortmentLineItem` + a compute
endpoint). That backend change updated the API contracts but **left the frontend screens stale or
broken** and **did not collapse the package dimension out of the material side** (two material
entities still carry the package binding on the backend).

This spec finishes the change. It has two jobs:

1. **Frontend alignment** — re-align the catalog/works, catalog/prices, materials/construction, and
   offer-packages screens to the new contracts, and add the missing UI for the assortment, the work
   volume formula, and the per-package override.
2. **Deferred backend material-side collapse** — genuinely remove the package dimension from
   `ConstructionMaterial` and `WorkMaterialConsumption` on the backend, mirroring the exact
   archive-before-drop + MAX-collapse migration pattern FOR-05-04 used for
   `WorkPrice` / `work_material_consumptions`.

This makes the spec **full-stack**: mostly frontend, plus a small backend migration and resolver
recompute for Requirement 5.

The concrete driver is **7 user-reported issues** (Requirements 1–7). Requirement 8 captures
cross-cutting non-functional constraints.

## Glossary

- **FOR-05-04 (backend)**: The prior backend-only spec `FOR-05-04-estimate-packages-changes` that
  changed the estimate model and API contracts but did not update the UI or collapse the material
  side. This spec is its UI/alignment follow-up.
- **Work_Catalog_Screen**: The frontend list/edit feature at route `/catalog/works` (ABAC resource
  `WORK_CATALOG`).
- **Work_Prices_Screen**: The frontend list/edit feature at route `/catalog/prices` (ABAC resource
  `WORK_PRICES`).
- **Construction_Materials_Screen**: The frontend list/edit feature at route
  `/materials/construction` (ABAC resource `MATERIALS_CONSTRUCTION`).
- **Offer_Packages_Screen**: The frontend list/edit feature at route `/offer-packages` (ABAC resource
  `OFFER_PACKAGES`).
- **Assortment_UI**: New frontend for `AssortmentGroup` and `AssortmentLineItem` management and the
  package zł/m² readout (ABAC resource `PACKAGE_ASSORTMENT`).
- **Formula_UI**: New frontend, embedded in the work-item edit flow, for the work's default volume
  formula (`WorkVolumeFormula`) and its per-package membership and override formula
  (`WorkPackageOverride`) (ABAC resource `WORK_CATALOG`).
- **Cost_Cell**: The `WorkCostCellDto` shape returned per work item: `{ labourPrice: BigDecimal|null,
  construction: MoneyRangeDto, finishing: MoneyRangeDto }`.
- **Money_Range**: `MoneyRangeDto(min, max)`; never null; `0..0` when the branch is empty.
- **Volume_Formula**: A `WorkVolumeFormula` — one default formula per work item, expressed over the
  14 room dimensions and cross-work references; server derives and validates the AST.
- **Package_Override**: A `WorkPackageOverride` — a per-package membership flag plus an optional
  override formula for a work item; carries no price; a present override implies membership.
- **Assortment_Group**: An `AssortmentGroup` — a named, ordered grouping of assortment line items.
- **Assortment_Line_Item**: An `AssortmentLineItem` — a per-package priced entry within an assortment
  group, with `minPrice / avgPrice / maxPrice`, a `qtyRef50` reference quantity, and an optional
  provenance link to a typical product that never drives the computed value.
- **Package_Zl_M2**: The computed zł/m² value for an offer package, returned by
  `GET /api/assortment-line-items/package-zl-m2?packageCode={code}` as `PackageZlM2Response(value)`;
  recomputed from current data, never cached, and not multiplied by floor area.
- **AsyncEntitySelect**: The reusable frontend async-search + infinite-scroll select that accepts a
  `selectedLabel` prop to render the current value's localized name.
- **Frontend_Repo**: The nested git repository at `foremen-frontend/` (ignored by the root repo's
  `.gitignore`); all frontend source commits from inside it.
- **Root_Repo**: The workspace-root git repository that tracks specs, backend, and infrastructure.

## Requirements

### Requirement 1: Works catalog list shows the single cost cell

**User Story:** As a catalog manager, I want the works list to show each work item's cost data, so
that I can review labour price and material ranges without opening each row.

**Context:** The backend now returns a single `costCell: { labourPrice, construction:{min,max},
finishing:{min,max} }` on each `WorkItemDtoModel` and no longer returns any `packagePivot` or
per-package metadata pivots. The list currently shows only Name / Work category / Unit / Active, so
the price and material-norm information is missing.

#### Acceptance Criteria

1. WHEN the Work_Catalog_Screen renders the works list, THE Work_Catalog_Screen SHALL display, for
   each work item, the single labour price, the construction Money_Range, and the finishing
   Money_Range sourced from the work item's Cost_Cell.
2. THE Work_Catalog_Screen SHALL NOT render any per-package pivot columns for price or material
   norms.
3. WHEN a work item's Cost_Cell `labourPrice` is null, THE Work_Catalog_Screen SHALL display an empty
   placeholder in the labour-price position for that work item.
4. WHEN a work item's construction or finishing Money_Range has `min` equal to 0 and `max` equal to
   0, THE Work_Catalog_Screen SHALL display that range as `0..0` for that work item.
5. WHEN the works list response is received, THE Work_Catalog_Screen SHALL render the labour price and
   both Money_Range values as columns in the same list row as the work item.

### Requirement 2: Work item edit dropdowns show names, not ids

**User Story:** As a catalog manager, I want the work-category and unit dropdowns in the work-item
edit form to show the current selection by name, so that I can recognize the selected value.

**Context:** `WorkItemDtoExtendedModel(id, workCategoryId, unitId, nameRU, namePL, active)` is flat FK
ids with no names, and no `selectedLabel` is passed to `AsyncEntitySelect`, so the selects render
`#<id>`. The requirement is to show the name; the exact mechanism (adding
`workCategoryName`/`unitName` to the extended DTO or seeding `selectedLabel` from the list row) is
left to design.

#### Acceptance Criteria

1. WHEN the Work_Catalog_Screen opens a work item for editing, THE Work_Catalog_Screen SHALL display
   the localized name of the currently-selected work category in the work-category select.
2. WHEN the Work_Catalog_Screen opens a work item for editing, THE Work_Catalog_Screen SHALL display
   the localized name of the currently-selected unit of measure in the unit select.
3. WHILE the options popover of the work-category or unit select is closed, THE Work_Catalog_Screen
   SHALL display the current selection as its localized name rather than as a numeric id.

### Requirement 3: Prices list shows work item, currency, and net price

**User Story:** As a pricing manager, I want the prices list to show the work item, currency, and net
price, so that I can review prices at a glance.

**Context:** `WorkPriceDtoModel` is now the collapsed single-price shape `{ id, workItemId,
workItemName, currencyId, currencyCode, netPrice }` with no `prices` map and no pivots. The list still
builds per-package pivot columns, so only the work-item column shows.

#### Acceptance Criteria

1. WHEN the Work_Prices_Screen renders the prices list, THE Work_Prices_Screen SHALL display the work
   item name as a column.
2. WHEN the Work_Prices_Screen renders the prices list, THE Work_Prices_Screen SHALL display the
   currency as a column.
3. WHEN the Work_Prices_Screen renders the prices list, THE Work_Prices_Screen SHALL display the net
   price as a column.
4. THE Work_Prices_Screen SHALL NOT render any per-package pivot columns in the prices list.

### Requirement 4: Prices create/edit is a single-price form that loads without hanging

**User Story:** As a pricing manager, I want the price create/edit form to load and let me set a
single price, so that I can create and update prices.

**Context:** The edit form's `isLoadingForm` gate waits on `useSeededPackages` (derived from metadata
pivots that no longer exist), so it never resolves and shows a skeleton forever; the form shape
(`packagePrices` field-array, per-package rows, `WorkPriceExtendedDto.packagePrices`) mismatches the
flat `WorkPriceCreateRequest / WorkPriceUpdateRequest(workItemId, currencyId, netPrice)` contract.

#### Acceptance Criteria

1. WHEN the Work_Prices_Screen opens the price create or edit form, THE Work_Prices_Screen SHALL
   present a single-price form with a work item field, a currency field, and a net price field.
2. WHEN the Work_Prices_Screen opens the price edit form for an existing price, THE Work_Prices_Screen
   SHALL prefill the work item, currency, and net price from the loaded price and finish loading
   without remaining in a skeleton state.
3. THE Work_Prices_Screen SHALL NOT gate form readiness on package-derived data.
4. WHEN the user submits the price create or edit form, THE Work_Prices_Screen SHALL send a flat
   payload containing `workItemId`, `currencyId`, and `netPrice`.
5. THE Work_Prices_Screen SHALL NOT include any per-package price fields in the price form or its
   submitted payload.

### Requirement 5: Remove the package dimension from construction materials and consumption norms (full-stack)

**User Story:** As a catalog manager, I want construction materials and material-consumption norms to
no longer be bound to packages, so that material data reflects the collapsed model.

**Context (frontend):** `ConstructionMaterialDtoModel.packages: List<RefDto>` and the package
multi-select in the form must be removed. The computed price-range column stays but is keyed by
material TYPE only. **Context (backend):** `ConstructionMaterial` still carries an `offer_packages`
M:N binding and `priceRanges` keyed by `offerPackageId`; `WorkMaterialConsumption` still carries an
`offer_package_id` FK and a `@NotNull offerPackageId` create field. These were NOT collapsed by
FOR-05-04. This requirement collapses them, mirroring FOR-05-04's archive-before-drop + MAX-collapse
pattern.

#### Acceptance Criteria

1. THE Construction_Materials_Screen SHALL NOT display a packages column in the construction-materials
   list.
2. THE Construction_Materials_Screen SHALL NOT present a package multi-select in the
   construction-material create or edit form.
3. WHEN the Construction_Materials_Screen renders the computed price-range column, THE
   Construction_Materials_Screen SHALL key that range by construction material type only.
4. WHEN the construction-material collapse migration runs, THE backend SHALL archive the affected rows
   before dropping the `offer_packages` M:N binding on `ConstructionMaterial`.
5. WHEN the consumption-norm collapse migration runs, THE backend SHALL archive the affected rows
   before dropping the `offer_package_id` foreign key on `WorkMaterialConsumption`.
6. WHEN the consumption-norm collapse migration runs, THE backend SHALL MAX-collapse duplicate
   consumption-norm rows to one row per `(work_item, material_type, branch)`.
7. WHEN the construction-material price range is resolved after the collapse, THE backend SHALL
   compute the range per construction material type only, with no fabricated fallback value.
8. WHEN the work-item Cost_Cell construction and finishing ranges are resolved after the collapse, THE
   backend SHALL compute each range per work item only.
9. IF the collapse migration is run again against an already-collapsed database, THEN THE backend
   SHALL make no further schema or data changes for the collapse.

### Requirement 6: Offer-package assortment and zł/m² configuration UI

**User Story:** As a package manager, I want to manage the assortment groups and line items for an
offer package and see its computed zł/m², so that I can configure package pricing inputs.

**Context:** The backend has `AssortmentGroup` (CRUD, `/api/assortment-groups`), `AssortmentLineItem`
(CRUD, `/api/assortment-line-items`), and a compute endpoint
`GET /api/assortment-line-items/package-zl-m2?packageCode={code}` returning `{ value }`, all under
resource `PACKAGE_ASSORTMENT`. No frontend exists for any of it.

#### Acceptance Criteria

1. THE Assortment_UI SHALL provide create, read, update, and delete operations for Assortment_Group
   entries with `nameRU`, `namePL`, and `sortOrder`.
2. THE Assortment_UI SHALL provide create, read, update, and delete operations for
   Assortment_Line_Item entries with `nameRU`, `namePL`, `minPrice`, `avgPrice`, `maxPrice`, a
   required `qtyRef50`, and an optional `typicalProductId`, each associated with an Assortment_Group
   and an offer package.
3. THE Assortment_UI SHALL present Assortment_Line_Item entries grouped by their Assortment_Group.
4. WHERE an Assortment_Line_Item has a typical product provenance link, THE Assortment_UI SHALL
   display that link as provenance only and SHALL NOT use it to drive the computed value.
5. WHEN the user views an offer package's assortment, THE Assortment_UI SHALL display the computed
   Package_Zl_M2 for that offer package obtained from
   `GET /api/assortment-line-items/package-zl-m2?packageCode={code}`.
6. WHEN the underlying assortment data changes and the Package_Zl_M2 is requested again, THE
   Assortment_UI SHALL display the recomputed value from current data rather than a cached value.
7. THE Assortment_UI SHALL be reachable from the offer-package edit context.
8. THE Assortment_UI SHALL NOT multiply the displayed Package_Zl_M2 by floor area.

### Requirement 7: Work volume formula and per-package override UI

**User Story:** As a catalog manager, I want to define a work item's default volume formula and its
per-package membership and override formulas, so that estimate volumes are computed correctly per
package.

**Context:** The backend has `WorkVolumeFormula` (CRUD, `/api/work-volume-formulas`, resource
`WORK_CATALOG`, one default formula per work item, `{ workItemId, sourceText }`; the server derives
and validates the AST and rejects unknown variable, unknown work reference, illegal operator, cycle,
and division by zero with i18n error keys) and `WorkPackageOverride` (CRUD,
`/api/work-package-overrides`, resource `WORK_CATALOG`, `{ workItemId, offerPackageId, member,
overrideSourceText }`, carries no price, present override implies member). No frontend exists.

#### Acceptance Criteria

1. WHEN the user is in the work-item edit flow, THE Formula_UI SHALL let the user view, create, and
   edit the work item's default Volume_Formula expressed over the 14 room dimensions and cross-work
   references.
2. WHEN the user is in the work-item edit flow, THE Formula_UI SHALL let the user view and edit the
   work item's per-package membership and override formula.
3. WHERE a Package_Override has an override formula present, THE Formula_UI SHALL treat that package as
   a member.
4. THE Formula_UI SHALL NOT present any price field on a Package_Override.
5. IF the backend rejects a submitted Volume_Formula or override formula with an unknown-variable
   error, THEN THE Formula_UI SHALL display the localized message for `error.formula.unknown.variable`.
6. IF the backend rejects a submitted formula with an unknown-work-reference error, THEN THE Formula_UI
   SHALL display the localized message for `error.formula.unknown.work.reference`.
7. IF the backend rejects a submitted formula with an illegal-operator error, THEN THE Formula_UI SHALL
   display the localized message for `error.formula.illegal.operator`.
8. IF the backend rejects a submitted formula with a cycle error, THEN THE Formula_UI SHALL display the
   localized message for `error.formula.cycle`.
9. IF the backend rejects a submitted formula with a division-by-zero error, THEN THE Formula_UI SHALL
   display the localized message for `error.formula.division.by.zero`.
10. THE Formula_UI SHALL NOT surface any raw formula error key to the user.

### Requirement 8: Cross-cutting constraints (access control, i18n, migration safety, repo layout)

**User Story:** As a maintainer, I want the aligned screens and the material collapse to respect
access control, localization parity, migration safety, and the two-repo layout, so that the change is
consistent and safe.

**Context:** Existing routes gate by ABAC resource; the app uses flat `pl.json` / `ru.json` locale
files with per-feature camelCase namespaces; the material collapse must be safe and idempotent; and
the frontend lives in a nested git repository distinct from the root repository.

#### Acceptance Criteria

1. WHERE a user lacks READ on the resource `WORK_CATALOG`, THE Work_Catalog_Screen SHALL be gated from
   that user.
2. WHERE a user lacks READ on the resource `WORK_PRICES`, THE Work_Prices_Screen SHALL be gated from
   that user.
3. WHERE a user lacks READ on the resource `MATERIALS_CONSTRUCTION`, THE Construction_Materials_Screen
   SHALL be gated from that user.
4. WHERE a user lacks READ on the resource `OFFER_PACKAGES`, THE Offer_Packages_Screen SHALL be gated
   from that user.
5. WHERE a user lacks READ on the resource `PACKAGE_ASSORTMENT`, THE Assortment_UI SHALL be gated from
   that user.
6. THE frontend SHALL provide every new or changed user-facing string in both the Polish and Russian
   locale files.
7. THE frontend SHALL NOT surface a raw localization key to the user for any new or changed screen.
8. WHEN the material collapse migration runs, THE backend SHALL archive the affected rows before
   dropping any binding or foreign key.
9. IF the material collapse migration is run again against an already-collapsed database, THEN THE
   backend SHALL make no further schema or data changes.
10. THE frontend source changes SHALL be committed from within the `foremen-frontend/` repository, and
    the backend and spec changes SHALL be committed from within the root repository.

## Subsequently Affected / Out of Scope

- This spec does **not** implement **FOR-05-06** (the packages-margins consumer of the package zł/m²
  value). It only exposes the assortment CRUD and the Package_Zl_M2 compute readout; consuming that
  value in a margins calculation is deferred to FOR-05-06.
- This spec covers only the 7 reported issues and the deferred material-side collapse from FOR-05-04.
  It does not introduce new estimate-model concepts beyond aligning the frontend to the contracts
  FOR-05-04 already shipped.
