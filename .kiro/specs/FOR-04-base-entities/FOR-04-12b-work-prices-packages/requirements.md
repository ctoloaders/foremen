# Requirements Document

FOR-04-12b: Package-based Work Prices (pivot catalog)

## Introduction

FOR-04-12b is an **evolution** of the already-implemented FOR-04-12 `WorkPrice`
entity. FOR-04-12 modelled a work price as
`(workItem, currency, netPrice, validFrom, validTo)` — a single price row per
work item with a time interval, where the "current" price was the row with
`validTo IS NULL`.

This spec replaces that with a **package-based collection model**. A `WorkPrice`
is no longer a single price row; it is a **per-work-item aggregator**. Each
`WorkPrice` has a unique association to one `WorkItem` (at most one `WorkPrice`
per work item) and **owns a collection** `packagePrices` of `WorkPackagePrice`.
The `netPrice`/`currency`/`validFrom`/`validTo` columns that carried the price in
the old per-row model are **removed** from `work_prices`; the price is now
carried by each `WorkPackagePrice`. The existing `work_prices` table is reused as
this aggregator.

`WorkPackagePrice` is a **new join entity** backed by a new `work_package_prices`
table. Each `WorkPackagePrice` links a `WorkPrice` (aggregator) to one
`OfferPackage` (FOR-04-10: `budget`/START, `norm`/COMFORT, `lux`/PRESTIGE) and
carries a `currency` and a `netPrice`. A unique constraint on
`(work_price_id, offer_package_id)` guarantees at most one price per package per
work. Price history is no longer kept as intervals; it is captured through the
audit log and the FOR-05 `PRICE_HISTORY` view.

Because a work item may not have a price defined for every package, the spec
keeps a **max-fallback effective-price resolver**, now expressed over a
`WorkPrice`'s `WorkPackagePrice` collection: given a work item and a package, the
effective price is that package's `WorkPackagePrice.netPrice` when present,
otherwise the **maximum** `netPrice` across the work item's `WorkPackagePrice`
collection; a work item whose collection is empty is unpriced. This resolver is a
deterministic, total function and is the primary target for property-based
testing.

**Why this preserves pagination.** The catalog **table row is the `WorkPrice`**
(i.e. the work item), not a single package price. Server-side
pagination/sort/search continue to operate over `WorkPrice` rows exactly as
before. The per-package prices are exposed as a **flattened map on the row DTO**:
`prices[packageCode] -> effective price` (e.g. `prices.START.netPrice`). The
frontend renders one pivot column per package from that map. Filtering and
sorting by `prices.{packageId}.netPrice` are implemented server-side through a
**custom-predicate query flow** (modelled on the *relivent (tickets)* reference
project): a path whose first segment is `prices` is resolved not as a plain
column path but by a dedicated flow that JOINs `WorkPrice` onto its
`packagePrices` collection with a discriminator `offerPackage.id == {packageId}`,
then applies the third-segment field (e.g. `net_price`). The package is keyed by
its numeric `offerPackage.id` (not `code`, which may change over time). Because
the grouping unit is the `WorkPrice` row and the grouping is done in the query
(not client-side), pagination is unaffected.

The data migration re-shapes existing single-price `WorkPrice` rows into the
collection model by **fanning out** each current price into one
`WorkPackagePrice` per existing offer package (all rows in `offer_packages`,
discovered dynamically — no hard-coded package codes), and the seed is
regenerated from the Excel package columns (`PAKIET START` / `PAKIET COMFORT` /
`PAKIET PRESTIGE` in `docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv`). The
`WORK_PRICES` ABAC resource and its existing role grants are unchanged;
`WorkPackagePrice` is guarded as a child of the aggregator through the same
`WORK_PRICES` resource.

**Catalog is a reference (consumers copy a snapshot).** The work-prices catalog
is a **reference price book**: it is the source of truth for *catalog* prices,
but downstream consumers do **not** bind to a `WorkPackagePrice` row. In
particular, a project estimate (FOR-05) forms its own **fixed** price by taking
the value from the catalog as a **copy (snapshot)** at the moment it is added —
never a foreign key to `WorkPackagePrice`. Consequently, editing or deleting a
catalog price never mutates a price already captured in a project, and the
catalog is free to change/delete package prices independently. FOR-04-12b only
guarantees the reference-and-copy contract on the catalog side; the snapshot
entity/columns themselves belong to FOR-05.

**Whole-package and whole-work-item deletion cascade through the catalog.**
Because catalog prices are reference-only (no downstream FK binds them), deleting
a whole `OfferPackage` or a whole `WorkItem` must clean the catalog up without
manual pre-deletion: deleting an `OfferPackage` removes every `WorkPackagePrice`
that references it across the entire catalog, and deleting a `WorkItem` removes
its `WorkPrice` aggregator together with all of that aggregator's
`WorkPackagePrice` rows. This is enforced at the database level via
`ON DELETE CASCADE` on the `work_package_prices` foreign keys and the
`work_prices → work_items` foreign key (see Requirement 1).

Scope: full-stack — backend model change (aggregator + join entity) + Liquibase
migration + effective-price resolver + re-seed; frontend pivot table + nested
pivot filter/sort. The `WORK_PRICES` ABAC resource and grants are reused as-is
(no new resource).

## Glossary

- **WorkPrice**: The per-work-item price **aggregator** entity. It has a unique association to one `WorkItem` (at most one `WorkPrice` per work item) and owns a `@OneToMany` collection `packagePrices` of `WorkPackagePrice`. It no longer carries `netPrice`, `currency`, `validFrom`, or `validTo`.
- **WorkPackagePrice**: The new join entity (table `work_package_prices`) linking a `WorkPrice` (aggregator) to one `OfferPackage`, carrying a `currency` and a `netPrice`; unique per `(work_price_id, offer_package_id)`.
- **WorkItem**: A catalog work item (FOR-04-11), referenced by `WorkPrice.workItem`.
- **OfferPackage**: An offer-package dictionary row (FOR-04-10), referenced by `WorkPackagePrice.offerPackage`; the three seeded packages are `budget` (START), `norm` (COMFORT), `lux` (PRESTIGE).
- **Package_Price**: The `netPrice` of the `WorkPackagePrice` for a specific `(work item, offer package)` pair, when such a member exists in the work item's `WorkPrice.packagePrices` collection.
- **Effective_Price_Resolver**: The backend function that, given a work item and an offer package, returns the effective price over the work item's `WorkPackagePrice` collection: the `Package_Price` when present for that package, otherwise the maximum `Package_Price` in the collection.
- **Unpriced_Work_Item**: A work item whose `WorkPrice` aggregator has an empty `packagePrices` collection (or has no `WorkPrice` at all).
- **Pivot_Row**: The catalog table row unit, which is a `WorkPrice` (work item). Pagination, sort, and search operate on Pivot_Rows.
- **Pivot_Column**: A work-prices catalog table column representing one offer package, headed `Cena {package}` and showing that package's effective price per Pivot_Row.
- **Pivot_Key**: The nested field descriptor identifying a package's price for filter/sort, of the form `prices.{packageId}.{field}` — first segment literal `prices`, second segment the offer package's numeric `offerPackage.id`, third segment a WorkPackagePrice field (e.g. `netPrice`). Resolved server-side by the Custom_Predicate_Flow: a JOIN from `WorkPrice` to `packagePrices` discriminated by `offerPackage.id == {packageId}`, then the third-segment field. Keyed by `id` (not `code`) so it survives package code renames.
- **Custom_Predicate_Flow**: The dedicated backend filter/sort resolution branch (modelled on the *relivent (tickets)* reference project) taken when a query path's first segment is a synthetic non-column property (here `prices`). Instead of the default `SpecificationBuilder` single-column path resolution, it parses the remaining path segments (here: package id, then WorkPackagePrice field) and builds a compound predicate `offerPackage.id == {packageId} AND {field} <op> {value}`.
- **Prices_Map**: The flattened map on the row DTO, keyed by offer package `code` → effective price (e.g. `prices.START.netPrice`), from which the frontend builds Pivot_Columns.
- **DataTable**: The shared frontend table component (`src/components/data-table`) with server-side search/sort/filter/pagination.
- **SpecificationBuilder**: The backend query-grammar-to-JPA-`Specification` translator (`com.foremen.service.query.SpecificationBuilder`).
- **EntityMetadataResolver**: The backend reflective metadata builder that emits `GET /{resource}/metadata` field/reference descriptors.
- **WORK_PRICES**: The ABAC resource code guarding the work-prices controller (unchanged from FOR-04-12); it also guards `WorkPackagePrice` as a child of the aggregator.

## Requirements

### Requirement 1: Package-based WorkPrice aggregator and WorkPackagePrice collection model

**User Story:** As a catalog administrator, I want each work item to hold one aggregator that owns a collection of per-package prices, so that estimates can price work at the START/COMFORT/PRESTIGE level without time intervals.

#### Acceptance Criteria

1. THE WorkPrice SHALL map a `@ManyToOne` association `workItem` (→ `WorkItemEntity`) that is unique, so that at most one WorkPrice exists per work item.
2. THE WorkPrice SHALL own a `@OneToMany` collection `packagePrices` of WorkPackagePrice.
3. THE WorkPrice SHALL NOT declare `netPrice`, `currency`, `validFrom`, or `validTo` fields.
4. THE WorkPackagePrice SHALL map a `@ManyToOne` association `workPrice` (→ `WorkPriceEntity`), a `@ManyToOne` association `offerPackage` (→ `OfferPackageEntity`), a `@ManyToOne` association `currency` (→ `CurrencyEntity`), and a `netPrice` (BigDecimal).
5. THE `work_package_prices` table SHALL have columns `id`, `work_price_id BIGINT NOT NULL` (FK → `work_prices(id)`, named `fk_work_package_prices_work_price`, `ON DELETE CASCADE`), `offer_package_id BIGINT NOT NULL` (FK → `offer_packages(id)`, named `fk_work_package_prices_offer_package`, `ON DELETE CASCADE`), `currency_id BIGINT NOT NULL` (FK → `currencies(id)`, named `fk_work_package_prices_currency`), and `net_price` (NUMERIC, NOT NULL).
9. THE `work_prices.work_item_id` foreign key (→ `work_items(id)`, named `fk_work_prices_work_item`) SHALL be declared `ON DELETE CASCADE`, so deleting a `WorkItem` removes its `WorkPrice` aggregator, which in turn (via criterion 5's `ON DELETE CASCADE` on `work_package_prices.work_price_id`) removes all of that aggregator's `WorkPackagePrice` rows.
10. WHEN an `OfferPackage` is deleted, THE database SHALL remove every `WorkPackagePrice` referencing it across the whole catalog via `ON DELETE CASCADE` on `work_package_prices.offer_package_id` (criterion 5), with no application-level pre-deletion required.
11. THE `currency_id` foreign key SHALL NOT cascade on delete (a currency in use is protected by the default `RESTRICT`), since currencies are reference data, not owners of the price rows.
6. THE `work_prices` table SHALL NOT have `net_price`, `currency_id`, `valid_from`, or `valid_to` columns after migration.
7. THE `work_package_prices` table SHALL enforce a unique constraint on `(work_price_id, offer_package_id)` so that at most one price exists per package per work.
8. THE migration and creation changeset(s) SHALL be idempotent for re-runs on an already-migrated database (`preConditions` guarding table/column/constraint presence with `MARK_RAN`).

### Requirement 2: Migrate existing single-price rows into the collection model (fan-out)

**User Story:** As an operator applying the release, I want each existing current work-price row fanned out into one price per package, so that no priced work item is lost when the aggregator drops its price columns.

#### Acceptance Criteria

1. WHEN the migration runs against a database holding FOR-04-12 `work_prices` rows, THE migration SHALL first ensure exactly one WorkPrice aggregator per work item, retaining the row with `valid_to IS NULL` and discarding any other rows for the same work item.
2. WHEN the migration has one aggregator per work item, THE migration SHALL create the `work_package_prices` table (per Requirement 1) before populating it.
3. WHEN migrating each retained current price row (`valid_to IS NULL`), THE migration SHALL create one WorkPackagePrice for each seeded offer package (`budget`/START, `norm`/COMFORT, `lux`/PRESTIGE) with `net_price` and `currency_id` equal to the existing row's values.
4. WHEN the fan-out completes, THE migration SHALL drop the `valid_from`, `valid_to`, `net_price`, and `currency_id` columns from `work_prices`.
5. WHEN populating `work_package_prices`, THE migration SHALL create at most one WorkPackagePrice per `(work_price_id, offer_package_id)` pair before applying the unique constraint.
6. THE migration SHALL be idempotent (`preConditions` + `MARK_RAN`) so re-running against a migrated database makes no further changes.
7. THE migration changeset(s) SHALL be registered last in `changelog.xml`, after the current highest-numbered changeset.

### Requirement 3: Effective price resolver with max-fallback over the collection

**User Story:** As an estimate builder, I want a work item's price for any package to always resolve when the item has at least one package price, so that a package with no explicit price still returns a usable price.

#### Acceptance Criteria

1. THE Effective_Price_Resolver SHALL accept a work item identifier and an offer package identifier and return an effective price.
2. WHEN a WorkPackagePrice exists in the work item's WorkPrice `packagePrices` collection for the given offer package, THE Effective_Price_Resolver SHALL return that WorkPackagePrice's `netPrice`.
3. IF no WorkPackagePrice exists for the given offer package AND the work item's `packagePrices` collection is non-empty, THEN THE Effective_Price_Resolver SHALL return the maximum `netPrice` in that collection.
4. IF the work item's `packagePrices` collection is empty (or the work item has no WorkPrice), THEN THE Effective_Price_Resolver SHALL report the work item as unpriced.
5. THE Effective_Price_Resolver SHALL return the same effective price for the same inputs on repeated invocations (deterministic).
6. WHERE a work item's `packagePrices` collection holds exactly one WorkPackagePrice, THE Effective_Price_Resolver SHALL return that single price for every offer package.
7. THE Effective_Price_Resolver SHALL return a value less than or equal to the maximum `netPrice` across the work item's `packagePrices` collection.

### Requirement 4: Nested pivot filter and sort via a custom-predicate query flow (backend)

**User Story:** As a catalog user, I want to filter and sort the price table by a specific package's price, so that I can find or order work items by their START/COMFORT/PRESTIGE price server-side.

> **Reference / rationale.** This is a **custom-predicate query flow** modelled on the
> mechanism from the reference project *relivent (tickets)*, which resolved certain fields
> through custom filter/sort logic instead of a plain property path. The `prices` property on
> the WorkPrice row is a **synthetic (non-column) collection pivot**, so a plain
> `SpecificationBuilder` path cannot express it; when the query grammar sees a path whose FIRST
> segment is `prices`, it MUST branch into a dedicated custom-predicate flow (below) rather than
> the default single-column path resolution. Encoding the package discriminator as its numeric
> **`offerPackage.id`** (not its `code`) is deliberate: the package `code` may change over time,
> while the id is stable, so the Pivot_Key stays valid across code renames.

#### Acceptance Criteria

1. THE work-prices read endpoint SHALL accept a filter and a sort expressed against a Pivot_Key of the form `prices.{packageId}.{field}`, where the FIRST segment is the literal `prices`, the SECOND segment `{packageId}` is an offer package's numeric primary key (`offerPackage.id`), and the THIRD segment `{field}` is a filterable/sortable field of WorkPackagePrice (e.g. `netPrice`).
2. WHEN the query grammar encounters a path whose first segment is `prices`, THE backend SHALL route it through a **custom-predicate flow** (not the default `SpecificationBuilder` single-column path resolution): it SHALL take the second segment as the offer package id, take the third segment as the WorkPackagePrice field, JOIN from WorkPrice to its `packagePrices` collection, and build the compound predicate `offerPackage.id == {packageId} AND {field} <op> {value}` (e.g. `prices.5.netPrice > 10` → `offerPackage.id == 5 AND netPrice > 10`).
3. WHEN a Pivot_Key filter is supplied, THE backend SHALL restrict results to WorkPrice rows whose matching-package WorkPackagePrice satisfies the third-segment field predicate under the compound predicate of criterion 2.
4. WHEN a Pivot_Key sort is supplied, THE backend SHALL order the WorkPrice rows by the named package's third-segment field (e.g. `net_price`) via the same collection JOIN discriminated by `offerPackage.id == {packageId}`.
5. WHEN a Pivot_Key filter or sort is applied, THE backend SHALL page over WorkPrice rows (Pivot_Rows) so that pagination remains correct and no work item is split across pages.
6. IF a Pivot_Key's second segment names an `offerPackage.id` that does not exist, OR the third segment names a field that is not a filterable/sortable WorkPackagePrice field, THEN THE backend SHALL return a client-error response with a descriptive error identifier.
7. THE work-prices `/metadata` endpoint SHALL advertise a pivot descriptor for each seeded offer package's Pivot_Key (keyed by `offerPackage.id`) so the frontend can build the pivot filter and sort controls.

### Requirement 5: Pivot columns in the work-prices catalog UI

**User Story:** As a catalog user, I want one price column per offer package instead of a single price column, so that I can compare START/COMFORT/PRESTIGE prices at a glance.

#### Acceptance Criteria

1. THE work-prices catalog table row SHALL be a WorkPrice (work item), and the row DTO SHALL carry a Prices_Map keyed by offer package `code` → effective price.
2. THE work-prices catalog table SHALL render one Pivot_Column per seeded offer package from the Prices_Map instead of a single `netPrice` column.
3. THE work-prices catalog table SHALL head each Pivot_Column with the text `Cena {package}`, where `{package}` is the offer package label in the active language.
4. WHEN a work row has an effective price for a Pivot_Column's package in the Prices_Map, THE Pivot_Column SHALL display that effective price for the row.
5. WHERE a work row's package has no explicit price but the work item has a price for another package, THE Pivot_Column SHALL display the max-fallback effective price for that row.
6. WHERE a work row is an Unpriced_Work_Item, THE Pivot_Column SHALL display an empty-value placeholder for that row.
7. THE work-prices catalog table SHALL allow the user to filter and sort by a Pivot_Column through the DataTable's server-side filter and sort using the column's Pivot_Key.
8. THE work-prices catalog table SHALL perform pagination, sort, and search over WorkPrice rows (work items), not over package price rows, so that paging is not broken by the pivot columns.

### Requirement 6: Re-seed package prices from the Excel price list

**User Story:** As an operator provisioning a fresh environment, I want the work prices seeded per package from the Excel price list, so that the catalog ships with START/COMFORT/PRESTIGE prices.

#### Acceptance Criteria

1. THE seed SHALL parse the offer-package price columns (`PAKIET START`, `PAKIET COMFORT`, `PAKIET PRESTIGE`) from `docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv`.
2. FOR each parsed work item, THE seed SHALL insert exactly one WorkPrice aggregator row for that work item.
3. FOR each offer package column holding a parseable positive price, THE seed SHALL insert one WorkPackagePrice for the work item's aggregator with `offer_package_id` for that package, `currency = PLN`, and `net_price` equal to that column's price.
4. WHERE an offer package column for a work item is blank, zero, or non-parseable, THE seed SHALL insert no WorkPackagePrice for that `(work item, offer package)` pair.
5. WHERE the Excel row provides only the legacy single `CENA` value and no per-package price columns, THE seed SHALL fan that price out into one WorkPackagePrice for every seeded offer package (`budget`/START, `norm`/COMFORT, `lux`/PRESTIGE), consistent with the Requirement 2 migration fan-out.
6. THE seed SHALL resolve `work_item_id` by matching the work item's `name_pl` and `work_category_id`, `offer_package_id` via `SELECT id FROM offer_packages WHERE code = ...`, and `currency_id` via `SELECT id FROM currencies WHERE code = 'PLN'`.
7. THE seed SHALL be idempotent (`preConditions onFail="MARK_RAN"` with a `sqlCheck` guarding against re-insertion of both the aggregator and its package prices).
8. THE seed changeset(s) SHALL be registered last in `changelog.xml`, after the migration changeset(s) from Requirement 2.

### Requirement 7: ABAC resource and grants unchanged

**User Story:** As a security owner, I want the access rules for work prices to stay the same, so that this model change introduces no new permission surface.

#### Acceptance Criteria

1. THE work-prices controller SHALL remain annotated `@PermissionResource("WORK_PRICES")`.
2. THE spec SHALL introduce no new ABAC resource for work prices; WorkPackagePrice SHALL be guarded as a child of the WorkPrice aggregator through the same `WORK_PRICES` resource.
3. THE existing `WORK_PRICES` grants SHALL remain: ADMIN CRUD; MANAGER CREATE/READ/UPDATE; FOREMAN/WORKER/FINANCIER READ; CLIENT no grant.

### Requirement 8: Backend tests

**User Story:** As a maintainer, I want the model change, resolver, and seed covered by tests, so that regressions are caught.

#### Acceptance Criteria

1. THE backend test suite SHALL include a property-based test for the collection-based Effective_Price_Resolver covering: an exact `(work item, package)` price returned as-is; a missing package returning the max of the other package prices in the collection; a single-member collection returning that price for all packages; an empty collection reported as unpriced; and the invariant that the returned effective price is less than or equal to the max `netPrice` in the collection.
2. THE backend test suite SHALL include an integration test for `/api/work-prices` verifying the row/DTO exposes the Prices_Map and that the unique `(work_price_id, offer_package_id)` constraint rejects a duplicate package price for the same aggregator.
3. THE backend test suite SHALL include an integration test for the Custom_Predicate_Flow Pivot_Key filter and sort against `prices.{packageId}.netPrice`, verifying the compound predicate `offerPackage.id == {packageId} AND netPrice <op> {value}` produces filtered/ordered WorkPrice rows, correct pagination over WorkPrice rows, a client-error response for an unknown `offerPackage.id`, and a client-error response for an unknown third-segment WorkPackagePrice field.
4. THE backend test suite SHALL include a Liquibase migration/seed integration test verifying the `work_package_prices` table exists with the FKs (including the `ON DELETE CASCADE` FKs on `work_price_id` and `offer_package_id`, and the `ON DELETE CASCADE` `work_prices.work_item_id` FK), the fan-out created one WorkPackagePrice per existing offer package for each pre-existing current work price, `valid_from`/`valid_to` (and the moved `net_price`/`currency_id`) are absent from `work_prices`, the `(work_price_id, offer_package_id)` unique constraint exists, work prices are seeded (COUNT > 0, currency PLN), and re-run idempotency.
5. THE backend test suite SHALL include an integration test for the cascade deletes: deleting an `OfferPackage` removes every `WorkPackagePrice` that referenced it across the catalog (and no other package's rows); and deleting a `WorkItem` removes its `WorkPrice` aggregator and all of that aggregator's `WorkPackagePrice` rows — both with no application-level pre-deletion.

### Requirement 9: Frontend tests

**User Story:** As a maintainer, I want the pivot table and its i18n covered by tests, so that the UI change is verified.

#### Acceptance Criteria

1. THE frontend test suite SHALL include a component test asserting the work-prices table renders one Pivot_Column per seeded offer package built from the row DTO's Prices_Map, each headed `Cena {package}` with the active-language label, and shows an empty-value placeholder for an Unpriced_Work_Item row.
2. THE frontend test suite SHALL assert that pagination, sort, and search operate on WorkPrice rows (work items), and that filtering and sorting a Pivot_Column issues the server request using the column's Pivot_Key.
3. THE work-prices UI string key sets SHALL be at parity in `pl.json` and `ru.json`, and no raw i18n key SHALL render.

### Requirement 10: Internationalization

**User Story:** As a Polish or Russian user, I want the new pivot column headers localized, so that the table reads correctly in my active language.

#### Acceptance Criteria

1. THE Pivot_Column header SHALL compose the localized `Cena` label with the offer package's active-language label.
2. THE new UI strings SHALL be present at parity in both `pl.json` and `ru.json`.
3. THE UI SHALL render a single active language (no mixed-language labels within one column header).
