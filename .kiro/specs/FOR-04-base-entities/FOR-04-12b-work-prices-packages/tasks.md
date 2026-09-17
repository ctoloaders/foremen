# Implementation Plan — FOR-04-12b: Package-based Work Prices (pivot catalog)

## Overview

Convert the FOR-04-12b design into incremental, test-driven coding steps. Order:
backend model re-shape (aggregator + join entity) → effective-price resolver →
Liquibase migration + re-seed → Custom_Predicate_Flow (unified filter/sort
resolver + registry + SpecificationBuilder branch + service sort wiring) →
DTOs/mapper/prices map → controller metadata pivot descriptors → backend
integration tests → frontend pivot table → UI tests → test-cases.md. Each step
builds on the previous ones and wires into the running application with no
orphaned code.

Backend is Java (Spring Boot / JPA / Liquibase / jqwik / Testcontainers); frontend
is TypeScript/React (Vitest + RTL). This spec spans **two git repos**: backend,
migration, seed, and spec docs live in the **root repo**; the frontend feature
lives in the nested **`foremen-frontend/`** repo — a feature touching both needs a
commit in each. The `WORK_PRICES` ABAC resource and grants are **reused unchanged**
(FOR-04-12 / `039`); no new resource is seeded, and `WorkPackagePrice` is guarded as
a child of the aggregator through the same resource. Per the workspace
test-execution standard, run only the affected test classes with `--tests` and read
the JUnit XML for pass/fail; never run the full suite unless explicitly requested.

## Tasks

- [x] 1. Backend model: aggregator re-shape + WorkPackagePrice join entity
  - [x] 1.1 Create `WorkPackagePriceEntity` (`dao/model`, `@Table("work_package_prices")` extends `BaseEntity`)
    - Map `@ManyToOne(LAZY) workPrice` (`work_price_id`, NOT NULL), `@ManyToOne(LAZY) offerPackage` (`offer_package_id`, NOT NULL), `@ManyToOne(LAZY) currency` (`currency_id`, NOT NULL), and `netPrice` (BigDecimal, `net_price`, NOT NULL)
    - _Requirements: 1.4, 1.5_
  - [x] 1.2 Re-shape `WorkPriceEntity` into the per-work-item aggregator
    - Remove `currency`, `netPrice`, `validFrom`, `validTo`; make `workItem` unique (`@JoinColumn(name="work_item_id", nullable=false, unique=true)`); add `@OneToMany(mappedBy="workPrice", cascade=ALL, orphanRemoval=true) List<WorkPackagePriceEntity> packagePrices`
    - _Requirements: 1.1, 1.2, 1.3_

- [x] 2. Checkpoint — backend compiles
  - Run `./gradlew compileJava compileTestJava` (redirect to temp log); ensure the new entity and re-shaped aggregator compile. Use `getDiagnostics` first for fast per-file checks. Ask the user if questions arise.
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [x] 3. Effective price resolver (pure max-fallback function)
  - [x] 3.1 Create `EffectivePriceResolver` (`service/pricing/EffectivePriceResolver`)
    - Stateless `@Component`; `Optional<BigDecimal> resolve(Collection<WorkPackagePriceEntity> packagePrices, String packageCode)`: (1) member whose `offerPackage.code == packageCode` → its `netPrice`; (2) non-empty collection → `max(netPrice)`; (3) empty → `Optional.empty()`; pure, total, deterministic, no I/O
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_
  - [x] 3.2 Write property test P1 — resolver case-correctness
    - **Property 1: Resolver case-correctness (exact / max-fallback / unpriced)**
    - jqwik `@Property(tries = 100)`; class `EffectivePriceResolverPropertyTest`; tag `Feature: FOR-04-12b-work-prices-packages, Property 1: Resolver case-correctness (exact / max-fallback / unpriced)`
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4**
  - [x] 3.3 Write property test P2 — single-member uniform
    - **Property 2: Single-member collection is uniform across packages**
    - jqwik `@Property(tries = 100)`; same test class or a sibling; tag `Feature: FOR-04-12b-work-prices-packages, Property 2`
    - **Validates: Requirements 3.6**
  - [x] 3.4 Write property test P3 — never exceeds max
    - **Property 3: Effective price never exceeds the collection maximum**
    - jqwik `@Property(tries = 100)`; tag `Feature: FOR-04-12b-work-prices-packages, Property 3`
    - **Validates: Requirements 3.7**
  - [x] 3.5 Write property test P4 — determinism
    - **Property 4: Resolution is deterministic**
    - jqwik `@Property(tries = 100)`; tag `Feature: FOR-04-12b-work-prices-packages, Property 4`
    - **Validates: Requirements 3.5**

- [x] 4. Liquibase migration + re-seed (registered LAST in `changelog.xml`, after `043`)
  - [x] 4.1 Create `044-migrate-work-prices-to-packages.xml` and register it in `changelog.xml`
    - `044a` de-duplicate to one aggregator per work item (delete `work_prices` rows where `valid_to IS NOT NULL`, guarded `columnExists valid_to` + `MARK_RAN`); `044b` create `work_package_prices` with the three FKs — the two owner FKs (`fk_work_package_prices_work_price → work_prices(id)`, `fk_work_package_prices_offer_package → offer_packages(id)`) declared `onDelete="CASCADE"`, `fk_work_package_prices_currency → currencies(id)` default RESTRICT (guard `not tableExists` + `MARK_RAN`); `044c` fan-out via `CROSS JOIN offer_packages` (unfiltered, no hard-coded codes) with `NOT EXISTS` guard, copying `net_price`/`currency_id`; `044d` add UNIQUE `uk_work_package_prices_work_offer (work_price_id, offer_package_id)`; `044e` add UNIQUE `uk_work_prices_work_item (work_item_id)`; `044f` drop `valid_from`/`valid_to`/`net_price`/`currency_id` from `work_prices`; `044g` drop+recreate `fk_work_prices_work_item → work_items(id)` as `onDelete="CASCADE"`; every sub-changeset idempotent (`preConditions` + `MARK_RAN`); register last, after `043`
    - _Requirements: 1.5, 1.6, 1.7, 1.9, 1.10, 1.11, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_
  - [x] 4.2 Create `045-seed-work-package-prices.xml` and register it after `044`
    - Regenerate per-package seed from the Excel `PAKIET START`/`PAKIET COMFORT`/`PAKIET PRESTIGE` columns of `docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv`: ensure one aggregator per work item (insert `work_prices(work_item_id)` when absent); for each package column with a parseable positive price insert one `work_package_prices` row resolving `work_item_id` via `name_pl` + `work_category_id`, `offer_package_id` via `offer_packages.code`, `currency_id` via `currencies.code='PLN'`; blank/zero/non-parseable → no row; legacy-only `CENA` rows fan out into **every existing offer package** (discovered from `offer_packages`, no hard-coded codes); idempotent `preConditions onFail="MARK_RAN"` with `sqlCheck` on both aggregator and package-price re-insertion; register after `044`. Generate the inserts with a throwaway Python script that parses the CSV (skip category-header/blank/no-ZAKRES rows, escape `''`, de-dup `name_pl` per category) then delete the script; document parsed/skipped counts in the changeset comment
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8_

- [x] 5. Custom_Predicate_Flow — unified pivot filter/sort resolver
  - [x] 5.1 Create `SeededOfferPackages` provider (id-keyed, cached)
    - Small Spring bean loading seeded `offer_packages` rows once as `(id, code, nameRU, namePL)`, cached; used for Pivot_Key id validation, `/metadata` pivot descriptors, and the mapper's per-package iteration
    - _Requirements: 4.6, 4.7_
  - [x] 5.2 Define `CustomQueryResolver<T>` interface + tiny registry keyed by `(entityClass, leadingSegment)`
    - Interface exposes `property()`, `toFilter(QueryToken.Filter)`, `toSort(Sort.Order)`; registry consulted by `SpecificationBuilder` (filter half) and `WorkPriceService` (sort half) so both resolve to the same instance
    - _Requirements: 4.1, 4.2, 4.4_
  - [x] 5.3 Implement the unified `prices` `CustomQueryResolver` for `WorkPriceEntity` and register `(WorkPriceEntity, "prices")`
    - One private shared parse/validate/join/discriminator helper: split `prices.{packageId}.{field}`, validate `packageId` against `SeededOfferPackages` (else `ForemenApiException BAD_REQUEST error.workprices.unknown.package`) and `field` against filterable/sortable `WorkPackagePrice` fields (else `error.query.invalid.field.path`), `getOrCreateJoin(root, "packagePrices")` (collection → JOIN, reused), `query.distinct(true)`, `offerPackage.id == {packageId}` discriminator. `toFilter` ANDs the value predicate (via existing `buildCriteriaPredicate`/`convertValue`); `toSort` applies `query.orderBy(cb.asc/desc(join.get(field)))` and returns the discriminator predicate
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.6_
  - [x] 5.4 Add the custom-predicate branch to `SpecificationBuilder.buildPredicate`
    - Before `resolvePath`, if `(entityClass, first-path-segment)` is registered, delegate to `resolver.toFilter(filter)` instead of default single-column resolution; keep `SpecificationBuilder` generic (no hard-coded `WorkPrice`/`prices`), reuse `QueryParser`/boolean composition/`?query=` contract unchanged
    - _Requirements: 4.2, 4.3_
  - [x] 5.5 Override `find`/`findExtended` on `WorkPriceService` for synthetic-sort wiring only
    - Detect orders whose first segment is a registered custom-query property (via registry), partition them out of the `Pageable` so nothing synthetic hits the metamodel, delegate each to `resolver.toSort(order)`, AND each returned discriminator onto `buildFinalSpecification(rawQuery)`, then delegate to the inherited read (`findAll(spec, cleanedPageable)` + map). Wiring only — no parse/join/orderBy in the service
    - _Requirements: 4.4, 4.5, 5.8_
  - [x] 5.6 Write `WorkPricePivotQueryIntegrationTest`
    - Seed work items with differing package prices; assert filter `prices.{id}.netPrice <op> v` → `offerPackage.id == id AND netPrice <op> v` returning only matching WorkPrice rows; sort `prices.{id}.netPrice,dir` orders by that package via the same discriminated join; pagination over WorkPrice rows (more rows than page size → no split/dup); unknown `offerPackage.id` → `error.workprices.unknown.package`; unknown segment-3 field (`prices.{id}.bogus`) → `error.query.invalid.field.path`; **identical** errors whether supplied as filter or as sort (same unified resolver)
    - _Requirements: 8.3, 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 6. DTOs, service model, mapper (prices map)
  - [x] 6.1 Update `WorkPriceServiceModel` + controller DTOs
    - Service model: remove `currencyId`/`currencyCode`/`netPrice`/`validFrom`/`validTo`/`current`; keep `id`/`workItemId`/`workItemName`; add `Map<String, PackagePrice> prices` (keyed by package `code`, each entry `{Long offerPackageId; String currencyCode; BigDecimal netPrice}`). `WorkPriceDtoModel` → `{id, workItemId, workItemName, Map<String,PackagePriceDto> prices}`; `WorkPriceDtoExtendedModel` + `WorkPriceCreateRequest`/`WorkPriceUpdateRequest` → `{@NotNull workItemId, @NotEmpty List<@Valid PackagePriceUpsert> packagePrices}` with `PackagePriceUpsert {@NotNull offerPackageId, @NotNull currencyId, @NotNull @Positive netPrice}`
    - _Requirements: 5.1, 5.6_
  - [x] 6.2 Update `WorkPriceServiceMapper` to build the prices map
    - Drop currency/date/`current` mappings; keep `workItemId` + `@AfterMapping resolveReferencedNames` for `workItemName`; add `@AfterMapping` that iterates `SeededOfferPackages`, calls `EffectivePriceResolver.resolve(source.getPackagePrices(), pkg.getCode())`, and puts a `PackagePrice` (with `offerPackageId`, matching/max member `currencyCode`, `netPrice`) into `target.prices` only when present (unpriced packages omit the key)
    - _Requirements: 5.1, 5.6_

- [x] 7. Controller metadata pivot descriptors
  - [x] 7.1 Override `getMetadata()` on `WorkPriceController` to append id-keyed per-package pivot descriptors
    - Keep `@PermissionResource("WORK_PRICES")` and the generic `AdminController` wiring; call `EntityMetadataResolver.resolve(WorkPriceEntity.class)`, then append one synthetic pivot field per seeded package from `SeededOfferPackages`: `name = "prices.{id}.netPrice"`, `dataType = NUMBER`, `sortable`/`filterable` true, carrying the package's numeric `id` and localized `nameRU`/`namePL` label. No `addCustomQueryCondition` rewrite (filter flows through `?query=`); inherited `find`/`findExtended` unchanged (sort wiring is on the service)
    - _Requirements: 4.7, 7.1_

- [x] 8. Checkpoint — backend compiles + startup annotations
  - Run `./gradlew compileJava compileTestJava` (temp log). Confirm `WorkPriceController` stays fully annotated (`@PermissionResource("WORK_PRICES")` + inherited `@PermissionOperation`) so `PermissionAnnotationValidator` is satisfied and the reused `WORK_PRICES` resource needs no new seed. Ask the user if questions arise.
  - _Requirements: 7.1, 7.2, 7.3_

- [x] 9. Backend integration tests (Testcontainers)
  - [x] 9.1 Write `WorkPriceControllerIntegrationTest`
    - Persist `WorkItem` + `Currency` + `OfferPackage`s and a `WorkPrice` with `packagePrices`; assert the list DTO exposes the `prices` map keyed by package `code` (each entry carrying `offerPackageId`); assert the `(work_price_id, offer_package_id)` unique constraint rejects a duplicate package price for the same aggregator
    - _Requirements: 8.2, 5.1_
  - [x] 9.2 Write `WorkPricePackagesMigrationIntegrationTest`
    - Assert `work_package_prices` exists with its three FKs; the `ON DELETE CASCADE` FKs on `work_price_id`, `offer_package_id`, and `work_prices.work_item_id` are present while `currency_id` is RESTRICT (read the DB catalog `delete_rule`/`confdeltype`); fan-out created one WorkPackagePrice per seeded package per pre-existing current price; `valid_from`/`valid_to`/moved `net_price`/`currency_id` absent from `work_prices`; `(work_price_id, offer_package_id)` unique constraint exists; package prices seeded (COUNT > 0, currency PLN); re-running the changelog is a no-op
    - _Requirements: 8.4, 2.1, 2.3, 2.4, 6.7_
  - [x] 9.3 Write `WorkPriceCascadeDeleteIntegrationTest`
    - With no application-level pre-deletion: deleting an `OfferPackage` removes exactly its `WorkPackagePrice` rows across all aggregators (others untouched); deleting a `WorkItem` removes its `WorkPrice` aggregator + all its `WorkPackagePrice` rows (others untouched); negative control — deleting a `Currency` still referenced fails (FK RESTRICT)
    - _Requirements: 8.5, 1.9, 1.10, 1.11_

- [x] 10. Checkpoint — run only the affected backend test classes
  - Run the property/integration classes added above with `--tests` filters (redirect to temp log; read JUnit XML). Do NOT run the full suite. Ask the user if questions arise.
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [x] 11. Frontend pivot table (`foremen-frontend/src/features/work-prices/`)
  - [x] 11.1 Update types + API for the collection model
    - Replace scalar price fields on `WorkPriceDto` with `prices: Record<string, { offerPackageId: number; currencyCode: string; netPrice: number }>` (keyed by package `code`); extended/create/update carry `packagePrices: PackagePriceUpsert[]`; update the work-prices API module accordingly
    - _Requirements: 5.1_
  - [x] 11.2 Rebuild `WorkPricesList` with dynamic pivot columns
    - Static `workItem` reference column plus one pivot column per seeded package from the `/metadata` pivot descriptors: render key = package `code` (`row.prices[code]?.netPrice ?? placeholder`), filter/sort key = id-keyed `field: 'prices.{id}.netPrice'` (`dataType:'number'`, `sortable`/`filterable` true), header `Cena {label}` from the metadata descriptor's active-language label; pagination/sort/search remain over WorkPrice rows; a pivot filter/sort issues the request keyed by `prices.{id}.netPrice`
    - _Requirements: 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 10.1, 10.3_
  - [x] 11.3 Rebuild `WorkPriceFormSheet` for per-package upserts
    - One row per seeded package (fixed offerPackage label + currency select + netPrice input); submit sends the `packagePrices` array
    - _Requirements: 5.1_
  - [x] 11.4 Update i18n (`workPrices.*`, PL+RU parity)
    - Compose the pivot header as `t('workPrices.table.priceColumn') + ' ' + packageLabel` (localized `Cena` prefix + package active-language label); add/reuse an empty-value placeholder; remove obsolete `netPrice/validFrom/validTo/current/badge.*` keys; keep `pl.json`/`ru.json` at parity with no raw key rendered
    - _Requirements: 10.1, 10.2, 10.3, 9.3_

- [x] 12. Frontend tests (Vitest + RTL, run with `--run`)
  - [x] 12.1 Write `WorkPricesList.test.tsx`
    - Renders one pivot column per seeded package from the row DTO `prices` map, each headed `Cena {label}` in the active language; shows the empty-value placeholder for an unpriced row; asserts pagination/sort/search operate on WorkPrice rows and that filtering/sorting a pivot column issues the request with the column's id-keyed `prices.{id}.netPrice` Pivot_Key while the cell renders from `row.prices[code]`
    - _Requirements: 9.1, 9.2_
  - [x] 12.2 Write `workPrices.i18n.test.ts`
    - `workPrices.*` key sets identical in `pl.json` and `ru.json`, all non-empty, no raw key rendered
    - _Requirements: 9.3, 10.2_

- [x] 13. Checkpoint — frontend tsc + new UI test files
  - Run frontend type-check and the new Vitest files with `--run`. Ask the user if questions arise.
  - _Requirements: 9.1, 9.2, 9.3_

- [x] 14. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD report template). This is an API+UI spec: include API tests against the Dockerized app (base URL `http://localhost:8080`, auth under `/api/auth`, first ADMIN via `FOREMEN_ADMIN_*`) for the backend — `GET/POST/PUT/DELETE /api/work-prices` with the `packagePrices` upsert body and the `prices` map response, the `(work_price_id, offer_package_id)` uniqueness rejection, the Custom_Predicate_Flow pivot filter/sort via `prices.{id}.netPrice` (including unknown-id and unknown-field client errors, identical for filter and sort), `/api/work-prices/metadata` pivot descriptors, cascade deletes of `OfferPackage`/`WorkItem` vs `Currency` RESTRICT, and unchanged `WORK_PRICES` ABAC grants — and browser-engine scenarios for the admin work-prices UI (dynamic `Cena {label}` pivot columns, empty placeholder for unpriced rows, per-package form upsert, pivot filter/sort keyed by `prices.{id}.netPrice`, i18n PL/RU). Result artifacts are MD reports with tables. Write test-cases.md in Russian per the standard
  - _Requirements: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10_

- [x] 15. Final checkpoint — affected backend classes + frontend tsc/UI tests
  - Run the affected backend test classes with `--tests` (temp log + JUnit XML) and the frontend `--run` tests; confirm all pass. Remember the two-repo split when committing (backend/migration/seed/spec → root repo; frontend → `foremen-frontend/` repo). Do NOT run the full suite unless explicitly requested. Ask the user if questions arise.
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 9.1, 9.2, 9.3_

## Notes

- Tasks marked with `*` are optional (unit/property/integration tests) and can be skipped for a faster MVP; core implementation tasks are never optional.
- Tasks are ordered so each builds on the previous and wires into the running application with no orphaned code.
- The design has a Correctness Properties section, so property-test sub-tasks (P1–P4) are included alongside integration tests. Each property is its own sub-task with a `Feature: FOR-04-12b-work-prices-packages, Property {n}` tag comment; `EffectivePriceResolver` is the only property-based-testing target (schema/migration/seed/pivot query/UI are covered by integration and example tests).
- The `WORK_PRICES` ABAC resource and grants are **reused unchanged** (FOR-04-12 / `039`) — no new resource changeset. `WorkPackagePrice` is guarded as a child of the aggregator through the same resource; `WorkPrice` is a global catalog aggregator (not project-scoped), so neither entity implements `ProjectScopedService`.
- The `id` vs `code` split is intentional: the row DTO `prices` map is keyed by package `code` (the render key), while the Pivot_Key used for filter/sort is keyed by the numeric `offerPackage.id` (survives code renames).
- Liquibase changesets `044` (migration) and `045` (seed) are registered **last** in `changelog.xml`, after `043`; every sub-changeset is idempotent (`preConditions` + `MARK_RAN`).
- This feature spans two git repos: backend/migration/seed/spec commit from the **root repo**, the frontend feature commits from the nested **`foremen-frontend/`** repo.
- Per the workspace test-execution standard, run only the affected test classes with `--tests`, redirect to a temp log, and read the JUnit XML for pass/fail; the full `./gradlew build` runs only on explicit request (~20 min).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "5.1"] },
    { "id": 1, "tasks": ["1.2", "3.1", "5.2"] },
    { "id": 2, "tasks": ["3.2", "3.3", "3.4", "3.5", "4.1", "5.3"] },
    { "id": 3, "tasks": ["4.2", "5.4", "5.5", "6.1"] },
    { "id": 4, "tasks": ["5.6", "6.2", "7.1"] },
    { "id": 5, "tasks": ["9.1", "9.2", "9.3"] },
    { "id": 6, "tasks": ["11.1", "11.3", "11.4"] },
    { "id": 7, "tasks": ["11.2"] },
    { "id": 8, "tasks": ["12.1", "12.2"] }
  ]
}
```
