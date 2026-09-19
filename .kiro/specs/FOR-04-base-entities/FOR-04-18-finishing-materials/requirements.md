# Requirements Document

FOR-04-18: Finishing Materials — one new operational entity `FinishingMaterial` with rich finishing fields, reuse of the shared FOR-04-17 image-storage service, its ABAC + UI, and a large CSV data seed.

## Introduction

FOR-04-18 delivers the finishing-material branch of the FOR-04 material model. It
is the SIBLING of FOR-04-17 (ConstructionMaterial): the same "one concrete offer of
a material at a price" shape and the same GLOBAL admin-resource treatment, but with
the RICH finishing fields that FOR-04-17 deliberately omitted (`category`,
`material`, `type`, `producer` references plus `model`, `sku`, `features`, `link`,
and `photo`).

The spec ships EXACTLY ONE new operational entity **FinishingMaterial** (resource
`MATERIALS_FINISHING`, table `finishing_materials`, path `/api/finishing-materials`,
page `/materials/finishing`). It creates NO new dictionary — every reference points
at an EXISTING dictionary seeded by earlier specs:

- `category` → `MATERIAL_CATEGORIES` (FOR-04-16 re-seed of the `Kategoria` column)
- `material` → `MATERIALS` (FOR-04-16, distinct values of the `Materiał` column)
- `type` → `MATERIAL_TYPES` (FOR-04-16, distinct values of the `Typ` column)
- `producer` → `MATERIAL_PRODUCERS` (FOR-04-16, distinct values of the `Producent` column)
- `packages` → `OFFER_PACKAGES` (FOR-04-10), a many-to-many set parsed from the
  multi-value `Pakiet` cell
- `unit` → `MEASUREMENT_UNITS` (FOR-04-02)

Unlike `ConstructionMaterial`, `FinishingMaterial` has NO localized name
(`nameRU`/`namePL`) and NO `code` — it is identified by its references plus
`model`/`sku`. Where a human-readable label is needed for lists, the label is
DERIVED at read time (e.g. `material` name + `model`) rather than stored as a name
field. Also unlike `ConstructionMaterial`, this branch has NO per-m²/per-unit price
field at all — neither stored nor computed. `FinishingMaterial` keeps exactly three
price fields (`purchasePrice`, `retailGross`, `retailNet`). The CSV's populated
retail column `Cena detal / m2` is used ONLY as a seed fallback source for
`retailNet` (see Requirement 6) and is NOT persisted as its own field.

The entity reuses — WITHOUT reimplementing — the shared `ImageStorage` service and
its `POST /api/images` upload endpoint introduced by FOR-04-17 (backend-mediated
Google Cloud Storage upload, object-key persistence, CDN URL resolved on the DTO,
two-trigger orphan cleanup, periodic reconciliation). The `photo` column of
`finishing_materials` MUST be added to the shared `ImageReferenceLookup`'s set of
known image columns so orphan cleanup and reconciliation cover finishing-material
photos too. Object keys are namespaced under `finishing-materials/`.

The spec delivers the REAL, LARGE data seed parsed from
`docs/Materiały pakiety - Lista.csv` (the finishing branch is the one that HAS a
CSV, unlike FOR-04-17 whose data comes from a later research spec). The seed maps
each CSV row to a `FinishingMaterial`, resolving the `category`/`material`/`type`/
`producer`/`unit` references against the already-seeded FOR-04-16 dictionary rows
and parsing the multi-value `Pakiet` cell into the `packages` set. The seed is
idempotent and inserts nothing for a row whose referenced dictionary value is
absent. CSV prices use a comma decimal separator (e.g. `53,61`) and the `Pakiet`
cell carries dirty tokens (a leading `+ `, a trailing quote artifact, and the
misspelling `Standart` for `Standard`) that MUST be normalized.

Following FOR-04-17, this spec adds a "Отделочные материалы / Finishing Materials"
navigation item under the "Склад / Warehouse" section pointing to
`/materials/finishing`, guarded by an interim `MATERIALS_FINISHING`/`READ` route
guard until FOR-04-15 wires the menu.

Depends on FOR-04-16 (`MATERIAL_CATEGORIES`, `MATERIALS`, `MATERIAL_TYPES`,
`MATERIAL_PRODUCERS`), FOR-04-10 (`OFFER_PACKAGES`), FOR-04-02
(`MEASUREMENT_UNITS`), FOR-04-01 (table reference filter + metadata), FOR-03-08
(permission annotations + startup validation), and reuses FOR-04-17's `ImageStorage`
service. The current highest Liquibase changeset is `059` (FOR-04-17), so new
changesets are numbered `060+`, registered last in `changelog.xml`.

## Glossary

- **FinishingMaterial**: NEW operational entity (resource `MATERIALS_FINISHING`, table `finishing_materials`, path `/api/finishing-materials`) — one concrete finishing-material offer with references (`category`, `material`, `type`, `producer`, `unit`, `packages`), free-text `model`/`sku`/`features`, three nullable prices, a `link`, and an optional `photo` (a GCS object key resolved to a CDN URL on the DTO). It has no `code` and no localized `name`.
- **MaterialCategory**: EXISTING dictionary entity (FOR-04-16, resource `MATERIAL_CATEGORIES`) referenced by `FinishingMaterial.category`; not recreated.
- **Material**: EXISTING dictionary entity (FOR-04-16, resource `MATERIALS`) referenced by `FinishingMaterial.material`; not recreated.
- **MaterialType**: EXISTING dictionary entity (FOR-04-16, resource `MATERIAL_TYPES`) referenced by `FinishingMaterial.type`; not recreated.
- **MaterialProducer**: EXISTING dictionary entity (FOR-04-16, resource `MATERIAL_PRODUCERS`) referenced by `FinishingMaterial.producer`; not recreated. (This spec adds no producer column; the additive `material_producers.image` column already arrived in FOR-04-17.)
- **OfferPackage**: The FOR-04-10 dictionary entity (`OFFER_PACKAGES`) referenced many-to-many by `FinishingMaterial.packages`, parsed from the multi-value `Pakiet` CSV cell.
- **MeasurementUnit**: The FOR-04-02 dictionary entity (`MEASUREMENT_UNITS`) referenced by `FinishingMaterial.unit`.
- **ImageStorage**: The SHARED image-storage service introduced by FOR-04-17 (`com.foremen.service.image`) — backend-mediated GCS upload, nullable object-key persistence, read-time CDN URL resolution, orphan cleanup on replace/detach/delete, periodic reconciliation. REUSED here for `FinishingMaterial.photo`; not reimplemented.
- **ImageReferenceLookup**: The FOR-04-17 component that answers "is this object key still referenced?" across the UNION of image columns. This spec adds `finishing_materials.image` (the `photo`) to its known image-column set.
- **GCS object key**: The bucket-relative key of a stored image object, namespaced per entity kind (`finishing-materials/…`), persisted in a nullable column instead of the full URL.
- **CDN URL**: The public image URL constructed at read time from a stored GCS object key plus a configured CDN base URL, exposed on read/list DTOs as `photoUrl`.
- **Derived label**: The human-readable list label for a `FinishingMaterial`, computed at read time from the `material` reference name plus `model` (e.g. `"Podłoga — Dąb North piaskowy EL2157 AC4 8 mm"`); never stored as a `name` column.
- **Multi-value `Pakiet` cell**: The CSV `Pakiet` column, which may hold several comma-separated package tokens (e.g. `"Budget, Standard"`, `"Budget, Lux, Standard"`), possibly dirty (`+ Budget`, a trailing quote, or the misspelling `Standart`). Parsed into the `packages` set.
- **MATERIALS_FINISHING**: The ABAC resource code guarding `FinishingMaterialController`.
- **System**: the Foremen backend + frontend under this spec.
- **Reference filter**: the FOR-04-01 list-query grammar for filtering by a reference path (`category.id`, `material.id`, `type.id`, `producer.id`, `unit.id`, `packages.id`).

## Requirements

### Requirement 1: FinishingMaterial entity & table

**User Story:** As a developer, I want a FinishingMaterial entity, so that each finishing-material offer records its category, material, type, producer, packages, unit, model, sku, features, prices, link, and photo.

#### Acceptance Criteria

1. THE System SHALL map a `FinishingMaterialEntity` extending `BaseEntity` with NO `code` field and NO localized `nameRU`/`namePL` fields.
2. THE `FinishingMaterialEntity` SHALL define a mandatory `@ManyToOne category` association to `MaterialCategory` (column `category_id`, NOT NULL, foreign key to `material_categories`).
3. THE `FinishingMaterialEntity` SHALL define a mandatory `@ManyToOne material` association to `Material` (column `material_id`, NOT NULL, foreign key to `materials`).
4. THE `FinishingMaterialEntity` SHALL define an optional `@ManyToOne type` association to `MaterialType` (column `type_id`, nullable, foreign key to `material_types`).
5. THE `FinishingMaterialEntity` SHALL define an optional `@ManyToOne producer` association to `MaterialProducer` (column `producer_id`, nullable, foreign key to `material_producers`).
6. THE `FinishingMaterialEntity` SHALL define a `@ManyToMany packages` association to `OfferPackage` via the join table `finishing_material_packages(finishing_material_id, offer_package_id)`.
7. THE `FinishingMaterialEntity` SHALL define a mandatory `@ManyToOne unit` association to `MeasurementUnit` (column `unit_id`, NOT NULL, foreign key to `measurement_units`).
8. THE `FinishingMaterialEntity` SHALL define the free-text fields `model`, `sku`, and `features`, each String and nullable (`model`/`sku` maximum length 255 characters; `features` a longer text column).
9. THE `FinishingMaterialEntity` SHALL define exactly the three price fields `purchasePrice`, `retailGross`, and `retailNet`, each `NUMERIC(12,2)` and nullable, and SHALL define NO per-m²/per-unit price field (neither stored nor computed).
10. THE `FinishingMaterialEntity` SHALL define an optional `link` field (String, nullable, maximum length 1024 characters) holding the product URL.
11. THE `FinishingMaterialEntity` SHALL define an optional `photo` field storing the ImageStorage GCS object key (String, nullable, column `photo`), and SHALL NOT persist the resolved CDN URL.
12. THE `FinishingMaterialEntity` SHALL define an `active` field (boolean, `NOT NULL`, default `true`).
13. WHEN the table-creation changeset runs against a database without the `finishing_materials` table, THE System SHALL create `finishing_materials` with the columns above plus the four `BaseEntity` audit columns, and SHALL create the `finishing_material_packages` join table with foreign keys to `finishing_materials(id)` and `offer_packages(id)`.
14. IF the table-creation changeset runs against a database that already contains the `finishing_materials` table, THEN THE System SHALL mark the changeset as run (`MARK_RAN` via `tableExists`) and SHALL make no schema changes.

### Requirement 2: FinishingMaterial CRUD, validation & references

**User Story:** As an API consumer, I want standard CRUD for finishing materials with reference validation, so that offers are managed with the shared framework and invalid references are rejected.

#### Acceptance Criteria

1. THE System SHALL expose the generic `AdminController` create, list, read, update, delete, count, metadata, and i18n operations for finishing materials at `/api/finishing-materials`, annotated `@PermissionResource("MATERIALS_FINISHING")`.
2. WHEN a create or update request is received, THE System SHALL require a `category`, a `material`, and a `unit` reference, and SHALL require at least one `OfferPackage` in `packages`.
3. IF a create or update request supplies no `category`, no `material`, no `unit`, or an empty `packages` set, THEN THE System SHALL reject the request with a client-error response identifying the missing field, and persist no changes.
4. IF a create or update references a `categoryId`, `materialId`, `typeId`, `producerId`, `unitId`, or any `offerPackageId` that does not reference an existing row, THEN THE System SHALL reject the request with a client-error response identifying the offending field, and persist no changes.
5. IF a create or update supplies a `purchasePrice`, `retailGross`, or `retailNet` outside the inclusive range 0.00 to 9,999,999,999.99, THEN THE System SHALL reject the request with a client-error response identifying the invalid field, and persist no changes.
6. WHEN a create or update supplies `model` or `sku`, THE System SHALL accept each when it is at most 255 characters, and IF either exceeds 255 characters, THEN THE System SHALL reject the request with a client-error response identifying the offending field, and persist no changes.
7. WHEN a create or update supplies a `link`, THE System SHALL accept it when it is at most 1024 characters, and IF it exceeds 1024 characters, THEN THE System SHALL reject the request with a client-error response identifying the `link` field, and persist no changes.
8. THE System SHALL accept an optional `photo` on create and update as an ImageStorage object key, SHALL persist that object key, and WHEN reading or listing THE System SHALL expose the resolved CDN photo URL on the finishing-material DTO.
9. WHEN reading or listing, THE System SHALL expose a derived label for each finishing material computed from the `material` reference name plus `model`, without persisting that label.
10. WHEN the caller lists `/api/finishing-materials` with a reference filter on `category.id`, `material.id`, `type.id`, `producer.id`, `unit.id`, or `packages.id`, THE System SHALL return only finishing materials matching the supplied reference value(s).
11. THE `FinishingMaterialService` SHALL NOT implement `ProjectScopedService` (global admin resource).

### Requirement 3: Package membership cascade

**User Story:** As a data owner, I want package membership to stay consistent when an offer package is deleted, so that no finishing material is left without a package.

#### Acceptance Criteria

1. THE System SHALL treat `packages` as mandatory on every finishing material, with at least one `OfferPackage`, and SHALL allow several packages per material.
2. WHEN an `OfferPackage` is deleted, THE System SHALL remove every `finishing_material_packages` join row that references the deleted package.
3. WHEN removing join rows for a deleted `OfferPackage` leaves a finishing material with zero packages, THE System SHALL delete that finishing material row.
4. WHEN an `OfferPackage` is deleted, THE System SHALL leave unchanged every finishing material that still has at least one remaining package.

### Requirement 4: Photo storage via the shared ImageStorage service

**User Story:** As a developer, I want finishing-material photos handled by the existing shared image-storage service, so that photos are uploaded, served, and cleaned up consistently without reimplementing storage.

#### Acceptance Criteria

1. THE System SHALL store, resolve, and clean up finishing-material photos through the EXISTING shared `ImageStorage` service and its `POST /api/images` upload endpoint introduced by FOR-04-17, and SHALL NOT introduce a second image-storage implementation.
2. WHEN a client uploads a finishing-material photo, THE System SHALL upload it through the `POST /api/images` backend endpoint with entity kind `finishing-materials`, and THE backend SHALL store the object under the `finishing-materials/` key namespace and return a reference.
3. THE System SHALL persist the returned Google Cloud Storage OBJECT KEY (not the full URL) in the nullable `finishing_materials.photo` column.
4. WHEN reading or listing a finishing material that has a photo, THE System SHALL construct the public CDN URL at read time from the stored object key plus the configured CDN base and SHALL expose the resolved URL on the read/list DTO.
5. THE System SHALL add the `finishing_materials.photo` column to the shared `ImageReferenceLookup`'s set of known image columns so both eager orphan cleanup and the periodic reconciliation job count finishing-material photo references.
6. WHEN a finishing material's photo is replaced with a different object, is detached (set to null), or the finishing material is deleted, THE System SHALL delete the now-unreferenced object from the bucket via the shared `ImageStorage.deleteIfOrphan(...)` trigger.
7. WHEN an upload request supplies content whose type is not an allowed image type (png, jpeg, or webp) or is larger than the configured maximum size, THE System SHALL reject the request with a client-error response and store no object, per the shared service's existing validation.

### Requirement 5: ABAC resource & grants

**User Story:** As a security admin, I want MATERIALS_FINISHING guarded by ABAC per the FOR-04 matrix, so that only authorized roles modify finishing materials.

#### Acceptance Criteria

1. THE seed SHALL insert the `MATERIALS_FINISHING` resource row populated with non-null `code`, `name_ru`, `name_pl`, `description_ru`, and `description_pl`.
2. THE seed SHALL grant ADMIN CREATE/READ/UPDATE/DELETE, MANAGER CREATE/READ/UPDATE (no DELETE), and FOREMAN/WORKER/FINANCIER READ on `MATERIALS_FINISHING`; CLIENT SHALL receive no grant (deny-by-default).
3. THE seed SHALL grant DELETE on `MATERIALS_FINISHING` to ADMIN only, and to no other role.
4. THE `FinishingMaterialController` SHALL be annotated with `@PermissionResource("MATERIALS_FINISHING")` whose value exactly matches the seeded resource `code`, and each inherited CRUD handler SHALL carry its `@PermissionOperation`.
5. IF the `FinishingMaterialController` is annotated with `@PermissionResource` but has any in-scope guarded handler lacking a matching `@PermissionOperation` or `@RequiresPermission` (or the reverse), THEN THE application SHALL fail to start during `PermissionAnnotationValidator` validation with an error indicating the incomplete annotation.
6. IF a seed changeset re-runs against a database where its target rows already exist, THEN THE changeset SHALL insert zero additional rows, guarded by `<preConditions onFail="MARK_RAN">` with an `<sqlCheck expectedResult="0">`.
7. THE new changeset files SHALL be registered last in `changelog.xml`, after the current highest (`059`), numbered `060+`.
8. THE seed SHALL be idempotent per `.kiro/steering/entity-creation-rules.md`, guarding the resource-row and each grant insert with an `sqlCheck`/`MARK_RAN` precondition so a re-run makes no duplicate insertions.

### Requirement 6: Large CSV data seed

**User Story:** As a delivery lead, I want the real finishing-material data seeded from the packages CSV, so that the catalog is populated with the actual offer data rather than a placeholder.

#### Acceptance Criteria

1. THE System SHALL seed `finishing_materials` rows parsed from `docs/Materiały pakiety - Lista.csv`, mapping each CSV column to the corresponding field (`Kategoria`→`category`, `Materiał`→`material`, `Typ`→`type`, `Producent`→`producer`, `Model`→`model`, `SKU`→`sku`, `Cechy`→`features`, `Cena zakup`→`purchasePrice`, `Cena detal brutto`→`retailGross`, `Cena detal netto`→`retailNet`, `Link`→`link`, `Pakiet`→`packages`); the `Cena detal / m2` column SHALL NOT map to a stored field of its own and SHALL be used only as the `retailNet` fallback source of criterion 2.
2. WHEN a CSV row's `Cena detal netto` is empty, THE System SHALL use the row's `Cena detal / m2` value as `retailNet` (comma-decimal parsed); WHEN both `Cena detal netto` and `Cena detal / m2` are empty, `retailNet` SHALL be null.
3. WHEN parsing a CSV price cell, THE System SHALL interpret a comma as the decimal separator (e.g. `53,61` becomes `53.61`) and SHALL leave the price null when the cell is empty.
4. WHEN parsing the `Pakiet` cell, THE System SHALL split it on commas into individual package tokens, SHALL trim surrounding whitespace and quote artifacts, SHALL strip a leading `+ ` marker, and SHALL map the misspelling `Standart` to the `Standard` package.
5. WHEN resolving a CSV row's `category`/`material`/`type`/`producer`/`unit` references, THE System SHALL match them against the existing FOR-04-16 (and FOR-04-02) dictionary rows seeded from the same distinct CSV values.
6. IF a CSV row references a `category`, `material`, or `unit` dictionary value that is absent, THEN THE System SHALL insert no finishing-material row for that CSV row.
7. THE data seed SHALL be idempotent so that a re-run inserts no duplicate finishing-material rows.
8. WHERE generating the full inline seed changeset from the 581-row CSV is impractical, THE System MAY stage the seed as a changeset GENERATED from the CSV by a build/seed step, provided the generated seed still satisfies the mapping, normalization, reference-resolution, and idempotency criteria above.
9. WHEN a CSV row has a blank `category`, THE System SHALL skip that row (the CSV contains a small number of blank-category rows).

### Requirement 7: Admin CRUD UI

**User Story:** As an admin, I want a finishing-materials admin page plus a Warehouse menu item, so that I can manage the finishing branch without using the API directly.

#### Acceptance Criteria

1. THE System SHALL provide a lazy-loaded page `FinishingMaterialsPage` at `/materials/finishing` using the shared `DataTable` with server search/sort/filter/pagination.
2. THE `FinishingMaterialsPage` SHALL render columns for the key fields: `category`, `material`, `type`, `producer`, `model`, `sku`, `retailNet`, and `link`; an image thumbnail column for `photo` is OPTIONAL, but the resolved CDN photo URL SHALL be exposed on the DTO.
3. THE `FinishingMaterialsPage` create/edit form SHALL provide a REQUIRED `category` selector, a REQUIRED `material` selector, an OPTIONAL `type` selector, an OPTIONAL `producer` selector, a REQUIRED multi-select `packages` (at least one), a REQUIRED `unit` selector, `model`/`sku`/`features` inputs, `purchasePrice`/`retailGross`/`retailNet` inputs, a `link` input, an OPTIONAL `photo` upload control (reusing the shared `ImageUploadControl` with entity kind `finishing-materials` and resource `MATERIALS_FINISHING`), and an `active` toggle; the form SHALL have no `code` and no `name` field.
4. IF a form field fails validation on submit (missing category, material, unit, empty packages, out-of-range price, `model`/`sku` over 255 characters, `link` over 1024 characters, or a rejected photo upload), THEN THE System SHALL block submission, retain all entered values, and display a localized validation message identifying each invalid field.
5. WHEN a user selects a photo on the finishing-material form, THE System SHALL upload it through the shared `POST /api/images` endpoint and, on success, SHALL associate the returned object key with the finishing material on save.
6. THE list fetch adapter SHALL use the shared `buildFetchQuery`; reference filters SHALL follow FOR-04-01; mutations SHALL invalidate the finishing-material list query and SHALL show a toast.
7. WHERE the current user lacks the CREATE, UPDATE, or DELETE permission on `MATERIALS_FINISHING`, THE System SHALL hide the corresponding create, edit, or delete control, as resolved by `usePermission`.
8. THE System SHALL provide a delete confirmation dialog for finishing materials.
9. THE System SHALL add a "Отделочные материалы / Finishing Materials" navigation item under the "Склад / Warehouse" section pointing to `/materials/finishing`, with i18n key `nav.finishingMaterials` at PL+RU parity, ABAC-gated on `MATERIALS_FINISHING` READ.
10. WHILE FOR-04-15 has not wired the menu, THE System SHALL guard `/materials/finishing` with an interim `MATERIALS_FINISHING`/`READ` route guard.
11. THE System SHALL render all UI text from keys under `finishingMaterials.*` plus `nav.finishingMaterials`, defined at parity in BOTH `pl.json` and `ru.json`, and SHALL NOT render any raw i18n key.

### Requirement 8: Backend tests

**User Story:** As a maintainer, I want integration and property tests, so that CRUD, reference validation, reference filters, the package cascade, the photo wiring, the grant matrix, and the CSV seed stay verified.

#### Acceptance Criteria

1. THE System SHALL have a `FinishingMaterial` CRUD integration test covering a required `category`/`material`/`unit`, optional `type`/`producer`, and a required `packages` set of at least one package, including rejection when `packages` is empty, and the derived label on read.
2. THE System SHALL have an integration test asserting reference filters on `category.id`, `material.id`, `type.id`, `producer.id`, `unit.id`, and `packages.id` return only matching finishing materials.
3. THE System SHALL have an integration test for the package cascade: deleting an `OfferPackage` removes its `finishing_material_packages` join rows and deletes any finishing material left with zero packages, while leaving materials that still have a package unchanged.
4. THE System SHALL have a property-based test asserting the write path rejects any dangling reference id (`categoryId`/`materialId`/`typeId`/`producerId`/`unitId`/`offerPackageId`) or out-of-range price naming the offending field with no persistence, and accepts an all-valid request.
5. THE System SHALL have a property-based test asserting the multi-value `Pakiet` parsing invariant: for any comma-separated token list with arbitrary whitespace, quote artifacts, a leading `+ ` marker, and `Standart` misspellings, the parser yields the corresponding normalized package-token set (`Standart` mapped to `Standard`, duplicates collapsed).
6. THE System SHALL have a property-based test asserting the photo CDN URL resolution reuse: for any stored object key and configured CDN base, the finishing-material DTO's resolved photo URL equals the shared service's `cdnBase + "/" + key`, a null key resolves to a null URL, and an upload object key begins with the `finishing-materials/` namespace prefix.
7. THE System SHALL have a Liquibase seed integration test asserting the `MATERIALS_FINISHING` resource is present, the exact grant matrix of Requirement 5 (including CLIENT none, MANAGER no-DELETE, and DELETE ADMIN-only), and re-run idempotency.
8. THE System SHALL have an integration test asserting the CSV data seed populated `finishing_materials` with resolved references and parsed multi-value packages for representative rows, that comma-decimal prices were parsed correctly, and that re-running the seed inserts no duplicates.
9. THE System SHALL have an integration test asserting the shared `ImageReferenceLookup` counts a `finishing_materials.photo` reference (so a photo still referenced by a finishing material is NOT reclaimed by reconciliation, and a detached one is).
10. THE System SHALL have a clean-startup smoke test confirming the fully annotated `FinishingMaterialController` starts cleanly under `PermissionAnnotationValidator`.

### Requirement 9: UI tests

**User Story:** As a maintainer, I want component and localization tests for the finishing-materials page, so that list rendering, permission gating, and translations stay verified.

#### Acceptance Criteria

1. THE System SHALL have a component test for `FinishingMaterialsList` asserting the listed columns render (`category`/`material`/`type`/`producer`/`model`/`sku`/`retailNet`/`link`), that permission-gated controls appear only with the permission, and that the empty state renders a localized message.
2. THE System SHALL have a UI test asserting the photo upload control appears on the finishing-material form and that a finishing material with a photo renders its resolved image.
3. THE System SHALL have a key-parity test for `finishingMaterials.*` across `pl.json` and `ru.json`, all keys non-empty, with no raw key rendered.
4. THE System SHALL have a localization test asserting the `nav.finishingMaterials` key is present at parity in `pl.json` and `ru.json`.
