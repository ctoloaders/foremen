# Requirements Document

FOR-04-17: Construction Materials — two new dictionaries, a construction-material entity with a per-(package,type) computed price range, a shared image-storage service (GCS + CDN), and their ABAC + UI

## Introduction

FOR-04-17 delivers the construction-material branch of the FOR-04 material model,
built IN ONE SPEC with parallel tracks (mirroring FOR-04-16). It ships two NEW
plain dictionaries and one NEW operational entity, reuses an existing dictionary,
and exposes a computed "price range" derived from the entity rows. All of these
are GLOBAL admin resources, NOT project-scoped.

The two NEW dictionaries follow FOR-04-16's `MaterialType` shape EXACTLY — fields
`code`, `nameRU`, `namePL`, `active`; i18n only on `name` (PL fallback); `code`
immutable on update; create validates `code`/`nameRU`/`namePL` non-blank:

1. **ConstructionMaterialType** (`CONSTRUCTION_MATERIAL_TYPES`, "вид строительного
   материала") — NEW. Table `construction_material_types`, path
   `/api/construction-material-types`. This classifier is the grouping key for
   "analogs" (see the price range below) and the anchor to which FOR-04-19 will
   later attach material-consumption norms.
2. **MaterialSeller** (`MATERIAL_SELLERS`, "продавцы: оптовики / сети / магазины")
   — NEW. Table `material_sellers`, path `/api/material-sellers`. Carries one
   extra field beyond the standard dictionary shape: an optional `website`
   (String, nullable, maximum length 255 characters).

The spec REUSES the existing `MATERIAL_PRODUCERS` dictionary (from FOR-04-16) as
the producer of a construction material. No new producer resource, entity, or page
is created; additional construction-brand seed rows MAY be added but the existing
resource is not recreated. This spec DOES add ONE additive change to the existing
producer vertical: an optional, nullable `image` column (a GCS object key served
through the shared ImageStorage service) is added to the existing
`material_producers` table via an idempotent additive Liquibase changeset
(`addColumn` guarded by a `columnExists` / `MARK_RAN` precondition) and exposed
through the existing producer resource, entity, DTO, and page. Only that nullable
`image` column is added — the producer resource, entity, and page are otherwise
not recreated. The producer image is uploaded, served, and cleaned up via the SAME
shared ImageStorage service used by construction-material images.

The NEW entity **ConstructionMaterial** (`MATERIALS_CONSTRUCTION`, table
`construction_materials`, path `/api/construction-materials`) extends `BaseEntity`
and models one concrete "this material, at this seller, at this price" offer. It
carries a localized `name` (`nameRU`/`namePL`, PL fallback), a required
`ConstructionMaterialType`, an optional `MaterialProducer`, an optional
`MaterialSeller` (ONE seller per row — a FK, not a many-to-many), a REQUIRED
many-to-many set of offer packages (at least one), a required measurement unit, a
required currency, three nullable prices (`purchasePrice`, `retailGross`,
`retailNet`), an OPTIONAL `website` link to the product/offer page (String,
nullable, maximum length 255 characters), and an OPTIONAL `image` (an object
stored via the shared ImageStorage service; the entity persists the nullable GCS
object key, and the resolved CDN URL is exposed on the DTO). Analogs / duplicate
offers of the same material at different sellers are SEPARATE rows grouped by their
shared `type`. This branch deliberately carries NONE of the rich finishing fields
(model, sku, features, etc.) — those belong to FOR-04-18. There is NO per-unit
(per-m²) retail derivation on this entity.

The **price range (вилка цен)** is COMPUTED, never stored. Analogs are defined by
a shared `type` (there is no separate analog-group entity). The system exposes a
computed range = MIN..MAX of `retailNet` keyed by the PAIR
(`OfferPackage`, `ConstructionMaterialType`): for a pair (package P, type T) the
range is the MIN..MAX of `retailNet` across all active construction materials whose
`type = T` AND whose `packages` set contains P. Because a construction material
belongs to several packages (many-to-many), the SAME material contributes to the
price range of EACH of its packages. Its purpose is estimating work cost and
knowing which sellers to buy from at what price. This keying ties forward to
FOR-04-19: material-consumption on a work item is per package, so the estimate
takes the price range of a type within a given package.

This spec introduces a NEW, SHARED, reusable **ImageStorage** service — used by
`ConstructionMaterial` images, `MaterialProducer` images, and later by FOR-04-18
finishing-material photos. The upload model is backend-mediated (model B): the
frontend uploads the file to a Foremen BACKEND endpoint, which stores the object in
a Google Cloud Storage (GCS) bucket in the project's Google Cloud account and
returns a reference; there is NO direct-from-frontend signed-URL upload. The
storage model is object-key-based (model C): the entity persists the nullable GCS
OBJECT KEY (not the full URL), and the public CDN URL is CONSTRUCTED at read time
from the object key plus a configured CDN base, so the read/list DTO exposes the
resolved CDN image URL. Orphan cleanup has TWO triggers (model D): when an entity's
image is replaced or detached (set to null, or the entity deleted) the now-
unreferenced object is deleted from the bucket; and a periodic reconciliation job
compares bucket objects against DB-referenced object keys and deletes objects that
no DB row references. Object keys are namespaced per entity kind (e.g.
`producers/…`, `construction-materials/…`). Uploads validate image content type
(png/jpeg/webp) and a maximum size, rejecting anything else with a client error.
Bucket name, CDN base URL, and credentials come from configuration/environment (no
hardcoding); when image storage is not configured, uploads are rejected gracefully
and existing entities keep working without images. `MaterialSeller` keeps its
optional `website` and does NOT get an image.

Seeding is split. The two new dictionaries plus the `MATERIALS_CONSTRUCTION`
resource and its grants — and the additive `material_producers.image` column — are
seeded/migrated via idempotent Liquibase changesets (the current highest changeset
is `052`, so new files are numbered `053+`, registered last in `changelog.xml`). The full DATA rows for construction material types,
sellers, and construction materials themselves are NOT delivered here — they come
from a SEPARATE research spec, `FOR-04-RESEARCH-construction-materials` (Polish
market research → CSV → parsed into SQL seed after user approval). Therefore in
THIS spec the data seed is a MINIMAL PLACEHOLDER (a few example rows); the full
seed is delivered later by the research spec.

Unlike the other FOR-04 dictionaries, the user CONFIRMED there WILL be a dedicated
"Стройматериалы / Construction Materials" menu item under the "Склад / Warehouse"
section pointing to `/materials/construction`; this spec adds that nav item
directly. The two new dictionary pages still use interim `RESOURCE`/`READ` route
guards until FOR-04-15 wires them into the Dictionaries menu.

Depends on FOR-04-10 (`OFFER_PACKAGES`), FOR-04-02 (`MEASUREMENT_UNITS`),
FOR-04-03 (`CURRENCIES`), FOR-04-16 (`MATERIAL_PRODUCERS` reuse), FOR-04-01 (table
reference filter + metadata), and FOR-03-08 (permission annotations + startup
validation). Forward references: FOR-04-19 attaches consumption norms to
`CONSTRUCTION_MATERIAL_TYPES`, and `FOR-04-RESEARCH-construction-materials`
delivers the full data seed.

## Glossary

- **ConstructionMaterialType**: NEW dictionary entity for the kind of construction material ("вид строительного материала"); resource code `CONSTRUCTION_MATERIAL_TYPES`. It is the grouping key for analogs and the anchor for FOR-04-19 consumption norms.
- **MaterialSeller**: NEW dictionary entity for a seller (wholesaler / chain / shop, "продавцы"); resource code `MATERIAL_SELLERS`. Carries an optional `website` beyond the standard dictionary fields.
- **MaterialProducer**: EXISTING dictionary entity (FOR-04-16, resource `MATERIAL_PRODUCERS`) reused here as the producer of a construction material; not recreated. THIS spec adds only one additive nullable `image` column (a GCS object key served via ImageStorage) to the existing `material_producers` table and its vertical.
- **ConstructionMaterial**: NEW operational entity (resource `MATERIALS_CONSTRUCTION`, table `construction_materials`) — one concrete offer of a material at a seller at a price, with a localized `name`, a required type, optional producer/seller, required packages, unit, currency, prices, an optional `website`, and an optional `image` (GCS object key resolved to a CDN URL on the DTO).
- **OfferPackage**: The FOR-04-10 dictionary entity (`OFFER_PACKAGES`) referenced many-to-many by `ConstructionMaterial`.
- **MeasurementUnit**: The FOR-04-02 dictionary entity (`MEASUREMENT_UNITS`) referenced by `ConstructionMaterial.unit`.
- **Currency**: The FOR-04-03 dictionary entity (`CURRENCIES`) referenced by `ConstructionMaterial.currency`.
- **Analogs**: Construction materials sharing the same `ConstructionMaterialType`; separate rows (e.g. the same material at different sellers/prices) grouped only by their shared `type`, with no dedicated analog-group entity.
- **Price range (вилка цен)**: The COMPUTED, never-persisted MIN..MAX of `retailNet`, keyed by the PAIR (`OfferPackage`, `ConstructionMaterialType`) — i.e. across all active construction materials whose `type` equals the given type AND whose `packages` set contains the given package. The same material contributes to the range of each package it belongs to. Ties forward to FOR-04-19 (per-package material consumption).
- **ImageStorage**: NEW shared, reusable service introduced by this spec for entity images. Backend-mediated upload to a Google Cloud Storage bucket; the entity persists the nullable GCS OBJECT KEY and the DTO exposes a CDN URL resolved from the object key plus a configured CDN base. Handles content-type/size validation, per-entity-kind key namespacing, and orphan cleanup on replace/detach/delete plus a periodic reconciliation job. Used by `ConstructionMaterial`, `MaterialProducer`, and later FOR-04-18.
- **GCS object key**: The bucket-relative key of a stored image object, namespaced per entity kind (e.g. `producers/…`, `construction-materials/…`), persisted in a nullable column instead of the full URL.
- **CDN URL**: The public image URL constructed at read time from a stored GCS object key plus a configured CDN base URL, exposed on read/list DTOs.
- **Orphan object**: A GCS object no longer referenced by any DB row; deleted eagerly when an image is replaced/detached and reclaimed by the periodic reconciliation job.
- **CONSTRUCTION_MATERIAL_TYPES / MATERIAL_SELLERS / MATERIALS_CONSTRUCTION / MATERIAL_PRODUCERS**: the ABAC resource codes guarding the respective controllers.
- **System**: the Foremen backend + frontend under this spec.
- **PL fallback**: localized `name` resolves to `nameRU` for `ru`, else `namePL`.
- **Dictionary vertical**: the full stack for one dictionary — entity + DAO + service + controller + Liquibase table + seed + ABAC + frontend admin page + i18n + tests.
- **Research spec**: `FOR-04-RESEARCH-construction-materials`, the separate Polish-market research spec that produces a CSV which, after user approval, becomes the full SQL seed for types, sellers, and construction materials.

## Requirements

### Requirement 1: ConstructionMaterialType dictionary, table & placeholder seed

**User Story:** As a developer, I want a ConstructionMaterialType dictionary, so that construction materials are classified and analogs can be grouped by a shared type.

#### Acceptance Criteria

1. THE System SHALL map a `ConstructionMaterialTypeEntity` extending `BaseEntity` with `code`, `nameRU`, `namePL`, `active`.
2. THE `construction_material_types` table SHALL have `code` `NOT NULL UNIQUE`, `name_ru`/`name_pl` `NOT NULL`, `active` `NOT NULL` default `true`, plus the four `BaseEntity` audit columns.
3. THE table-creation changeset SHALL be idempotent (`tableExists` precondition + `MARK_RAN`).
4. THE System SHALL expose `ConstructionMaterialTypeController` implementing `AdminController` at `/api/construction-material-types`, annotated `@PermissionResource("CONSTRUCTION_MATERIAL_TYPES")`.
5. WHEN the request locale is `ru`, THE System SHALL resolve localized `name` to `nameRU`; otherwise THE System SHALL resolve `name` to `namePL` (PL fallback), including when sorting or filtering by `name`.
6. WHEN an update request is received, THE System SHALL keep `code` unchanged and SHALL update `nameRU`, `namePL`, `active`.
7. WHEN a create request is received, THE System SHALL validate `code`, `nameRU`, `namePL` as non-blank.
8. THE `ConstructionMaterialTypeService` SHALL NOT implement `ProjectScopedService` (global admin resource).
9. THE seed SHALL insert only a MINIMAL PLACEHOLDER set of example rows, each guarded by an `sqlCheck` on `code` (`expectedResult="0"` + `MARK_RAN`); THE full type data SHALL be delivered later by the research spec (see Requirement 11).

### Requirement 2: MaterialSeller dictionary, table & placeholder seed

**User Story:** As a developer, I want a MaterialSeller dictionary with an optional website, so that each construction-material offer records where it can be purchased.

#### Acceptance Criteria

1. THE System SHALL map a `MaterialSellerEntity` extending `BaseEntity` with `code`, `nameRU`, `namePL`, `active`, and an optional `website` field (String, nullable, maximum length 255 characters).
2. THE `material_sellers` table SHALL have `code` `NOT NULL UNIQUE`, `name_ru`/`name_pl` `NOT NULL`, `active` `NOT NULL` default `true`, a nullable `website` column, plus the four `BaseEntity` audit columns.
3. THE table-creation changeset SHALL be idempotent (`tableExists` precondition + `MARK_RAN`).
4. THE System SHALL expose `MaterialSellerController` implementing `AdminController` at `/api/material-sellers`, annotated `@PermissionResource("MATERIAL_SELLERS")`.
5. WHEN the request locale is `ru`, THE System SHALL resolve localized `name` to `nameRU`; otherwise THE System SHALL resolve `name` to `namePL` (PL fallback), including when sorting or filtering by `name`.
6. WHEN an update request is received, THE System SHALL keep `code` unchanged and SHALL update `nameRU`, `namePL`, `website`, `active`.
7. WHEN a create request is received, THE System SHALL validate `code`, `nameRU`, `namePL` as non-blank.
8. THE `MaterialSellerService` SHALL NOT implement `ProjectScopedService` (global admin resource).
9. THE seed SHALL insert only a MINIMAL PLACEHOLDER set of example rows, each guarded by an `sqlCheck` on `code`; THE full seller data SHALL be delivered later by the research spec (see Requirement 11).

### Requirement 3: ConstructionMaterial entity & table

**User Story:** As a developer, I want a ConstructionMaterial entity, so that each construction-material offer records its type, producer, seller, packages, unit, currency, and prices.

#### Acceptance Criteria

1. THE System SHALL map a `ConstructionMaterialEntity` extending `BaseEntity` with `nameRU` and `namePL` (localized `name`, PL fallback) and no `code` field.
2. THE `ConstructionMaterialEntity` SHALL define a mandatory `@ManyToOne type` association to `ConstructionMaterialType` (column `type_id`, NOT NULL, foreign key to `construction_material_types`).
3. THE `ConstructionMaterialEntity` SHALL define an optional `@ManyToOne producer` association to `MaterialProducer` (column `producer_id`, nullable, foreign key to `material_producers`).
4. THE `ConstructionMaterialEntity` SHALL define an optional `@ManyToOne seller` association to `MaterialSeller` (column `seller_id`, nullable, foreign key to `material_sellers`), representing exactly one seller per row.
5. THE `ConstructionMaterialEntity` SHALL define a `@ManyToMany packages` association to `OfferPackage` via the join table `construction_material_packages(construction_material_id, offer_package_id)`.
6. THE `ConstructionMaterialEntity` SHALL define a mandatory `@ManyToOne unit` association to `MeasurementUnit` (column `unit_id`, NOT NULL, foreign key to `measurement_units`) and a mandatory `@ManyToOne currency` association to `Currency` (column `currency_id`, NOT NULL, foreign key to `currencies`).
7. THE `ConstructionMaterialEntity` SHALL define the price fields `purchasePrice`, `retailGross`, and `retailNet`, each `NUMERIC(12,2)` and nullable, and SHALL define no per-unit (per-m²) retail field.
8. THE `ConstructionMaterialEntity` SHALL define an `active` field (boolean, `NOT NULL`, default `true`).
9. THE `ConstructionMaterialEntity` SHALL define an optional `website` field (String, nullable, maximum length 255 characters, column `website`) linking to the product/offer page.
10. THE `ConstructionMaterialEntity` SHALL define an optional `image` field storing the ImageStorage GCS object key (String, nullable, column `image`), and SHALL NOT persist the resolved CDN URL.
11. WHEN the table-creation changeset runs against a database without the `construction_materials` table, THE System SHALL create `construction_materials` with the columns above (including nullable `website` and `image`) plus the four `BaseEntity` audit columns, and SHALL create the `construction_material_packages` join table with foreign keys to `construction_materials(id)` and `offer_packages(id)`.
12. IF the table-creation changeset runs against a database that already contains the `construction_materials` table, THEN THE System SHALL mark the changeset as run (`MARK_RAN` via `tableExists`) and SHALL make no schema changes.

### Requirement 4: ConstructionMaterial CRUD, validation & references

**User Story:** As an API consumer, I want standard CRUD for construction materials with reference validation, so that offers are managed with the shared framework and invalid references are rejected.

#### Acceptance Criteria

1. THE System SHALL expose the generic `AdminController` create, list, read, update, delete, count, metadata, and i18n operations for construction materials at `/api/construction-materials`, annotated `@PermissionResource("MATERIALS_CONSTRUCTION")`.
2. WHEN a create or update request is received, THE System SHALL require a `type` reference and SHALL require at least one `OfferPackage` in `packages`.
3. IF a create or update request supplies no `type`, no `unit`, no `currency`, or an empty `packages` set, THEN THE System SHALL reject the request with a client-error response identifying the missing field, and persist no changes.
4. IF a create or update references a `typeId`, `producerId`, `sellerId`, `unitId`, `currencyId`, or any `offerPackageId` that does not reference an existing row, THEN THE System SHALL reject the request with a client-error response identifying the offending field, and persist no changes.
5. IF a create or update supplies a `purchasePrice`, `retailGross`, or `retailNet` outside the range 0.00 to 9,999,999,999.99, THEN THE System SHALL reject the request with a client-error response identifying the invalid field, and persist no changes.
6. WHEN a create or update supplies a `website`, THE System SHALL accept it when it is at most 255 characters, and IF it exceeds 255 characters, THEN THE System SHALL reject the request with a client-error response identifying the `website` field, and persist no changes.
7. THE System SHALL accept an optional `image` on create and update as an ImageStorage object key, SHALL persist that object key, and WHEN reading or listing THE System SHALL expose the resolved CDN image URL on the construction-material DTO.
8. WHEN the request locale is `ru`, THE System SHALL resolve the construction material's localized `name` to `nameRU`; otherwise THE System SHALL resolve `name` to `namePL` (PL fallback), including when sorting or filtering by `name`.
9. WHEN the caller lists `/api/construction-materials` with a reference filter on `type.id`, `producer.id`, `seller.id`, `unit.id`, `currency.id`, or `packages.id`, THE System SHALL return only construction materials matching the supplied reference value(s).
10. THE `ConstructionMaterialService` SHALL NOT implement `ProjectScopedService` (global resource).

### Requirement 5: Package membership cascade

**User Story:** As a data owner, I want package membership to stay consistent when an offer package is deleted, so that no construction material is left without a package.

#### Acceptance Criteria

1. THE System SHALL treat `packages` as mandatory on every construction material, with at least one `OfferPackage`, and SHALL allow several packages per material.
2. WHEN an `OfferPackage` is deleted, THE System SHALL remove every `construction_material_packages` join row that references the deleted package.
3. WHEN removing join rows for a deleted `OfferPackage` leaves a construction material with zero packages, THE System SHALL delete that construction material row.
4. WHEN an `OfferPackage` is deleted, THE System SHALL leave unchanged every construction material that still has at least one remaining package.

### Requirement 6: Computed price range (вилка цен)

**User Story:** As an estimator, I want a computed MIN..MAX retail-net price range per pair of offer package and construction-material type, so that I can estimate work cost per package and know which sellers to buy from at what price.

#### Acceptance Criteria

1. THE System SHALL expose, for each pair (`OfferPackage`, `ConstructionMaterialType`), a computed price range consisting of a minimum and a maximum `retailNet`.
2. THE System SHALL compute the price range for a pair (package P, type T) as the MIN and MAX of `retailNet` across all active construction materials whose `type` equals T AND whose `packages` set contains P.
3. WHERE a construction material belongs to several offer packages, THE System SHALL include that construction material in the price-range computation of EACH package it belongs to (for its `type`).
4. THE System SHALL derive the price range at read time and SHALL NOT persist it in any table or column.
5. WHERE a construction material has a null `retailNet`, THE System SHALL exclude that material from the price-range computation for every pair it would otherwise contribute to.
6. WHERE a pair (`OfferPackage`, `ConstructionMaterialType`) has no active construction material with a non-null `retailNet`, THE System SHALL return an empty price range for that pair (no minimum and no maximum).
7. THE price-range computation SHALL be deterministic: for the same set of construction materials it SHALL always produce the same minimum and maximum for each pair.

### Requirement 7: Image storage (Google Cloud Storage + CDN)

**User Story:** As a developer, I want a shared image-storage service backed by Google Cloud Storage with CDN delivery and orphan cleanup, so that construction materials, producers, and later finishing materials can attach images consistently without leaking storage.

#### Acceptance Criteria

1. THE System SHALL provide a SHARED, reusable `ImageStorage` service, introduced by this spec, used by `ConstructionMaterial` images, `MaterialProducer` images, and available for later reuse by FOR-04-18 finishing-material photos.
2. WHEN a client uploads an image, THE System SHALL upload the file to a Foremen backend endpoint, and THE backend SHALL store the object in a Google Cloud Storage bucket and return a reference; THE System SHALL NOT use direct-from-frontend signed-URL upload.
3. THE System SHALL persist the Google Cloud Storage OBJECT KEY (not the full URL) in the referencing entity's nullable image column.
4. WHEN reading or listing an entity that has an image, THE System SHALL construct the public CDN URL at read time from the stored object key plus a configured CDN base URL, and SHALL expose the resolved CDN URL on the read/list DTO.
5. THE System SHALL namespace object keys per entity kind (for example `producers/…` for producer images and `construction-materials/…` for construction-material images).
6. WHEN an upload request supplies content whose type is not an allowed image type (png, jpeg, or webp), THE System SHALL reject the request with a client-error response and store no object.
7. WHEN an upload request supplies content larger than the configured maximum upload size, THE System SHALL reject the request with a client-error response and store no object.
8. WHEN an entity's image is replaced with a different object, THE System SHALL delete the previously referenced object from the bucket.
9. WHEN an entity's image is detached (set to null) or the entity is deleted, THE System SHALL delete the now-unreferenced object from the bucket.
10. THE System SHALL run a periodic reconciliation job that compares the objects in the bucket against the set of DB-referenced object keys and deletes every bucket object that no database row references.
11. THE System SHALL read the bucket name, CDN base URL, and Google Cloud credentials from configuration/environment and SHALL NOT hardcode them.
12. IF image storage is not configured, THEN THE System SHALL reject upload requests gracefully with a client-error response, and existing entities SHALL continue to function without images.

### Requirement 8: MaterialProducer image column (additive reuse)

**User Story:** As a developer, I want the existing MaterialProducer dictionary to gain an optional image, so that producers can carry a logo/photo through the shared image-storage service without recreating the producer vertical.

#### Acceptance Criteria

1. THE System SHALL add an optional, nullable `image` column (Google Cloud Storage object key, String) to the existing `material_producers` table via an additive Liquibase changeset, and SHALL NOT recreate the `MATERIAL_PRODUCERS` resource, entity, or page.
2. THE additive changeset SHALL be idempotent, guarding the `addColumn` with a `columnExists` precondition and `onFail="MARK_RAN"` so a re-run against an already-migrated database makes no schema changes.
3. THE additive changeset SHALL be numbered `053+` and registered last in `changelog.xml`, in sequence with the other new changesets.
4. THE System SHALL expose the producer `image` through the existing producer entity, DTO, and page, accepting the object key on create/update and exposing the resolved CDN image URL on read/list, served by the shared `ImageStorage` service.
5. THE `MaterialSeller` dictionary SHALL keep its optional `website` and SHALL NOT gain an image field.

### Requirement 9: ABAC resources & grants

**User Story:** As a security admin, I want CONSTRUCTION_MATERIAL_TYPES, MATERIAL_SELLERS, and MATERIALS_CONSTRUCTION guarded by ABAC per the FOR-04 matrix, so that only authorized roles modify them.

#### Acceptance Criteria

1. FOR EACH new resource (`CONSTRUCTION_MATERIAL_TYPES`, `MATERIAL_SELLERS`, `MATERIALS_CONSTRUCTION`), THE seed SHALL insert the resource row populated with non-null `code`, `name_ru`, `name_pl`, `description_ru`, and `description_pl`.
2. FOR the plain dictionaries `CONSTRUCTION_MATERIAL_TYPES` and `MATERIAL_SELLERS`, THE seed SHALL grant ADMIN CREATE/READ/UPDATE/DELETE and MANAGER/FOREMAN/WORKER/FINANCIER READ; CLIENT SHALL receive no grant (deny-by-default).
3. FOR the entity resource `MATERIALS_CONSTRUCTION`, THE seed SHALL grant ADMIN CREATE/READ/UPDATE/DELETE, MANAGER CREATE/READ/UPDATE (no DELETE), and FOREMAN/WORKER/FINANCIER READ; CLIENT SHALL receive no grant (deny-by-default).
4. THE seed SHALL grant DELETE on `MATERIALS_CONSTRUCTION` to ADMIN only, and to no other role.
5. EACH controller SHALL be annotated with `@PermissionResource` whose value exactly matches the seeded resource `code`, and each inherited CRUD handler SHALL carry its `@PermissionOperation`.
6. IF a controller is annotated with `@PermissionResource` but has any in-scope guarded handler lacking a matching `@PermissionOperation` or `@RequiresPermission` (or the reverse), THEN THE application SHALL fail to start during `PermissionAnnotationValidator` validation with an error indicating the incomplete annotation.
7. IF a seed changeset re-runs against a database where its target rows already exist, THEN THE changeset SHALL insert zero additional rows, guarded by `<preConditions onFail="MARK_RAN">` with an `<sqlCheck expectedResult="0">`.
8. THE new changeset files SHALL be registered last in `changelog.xml`, after the current highest (`052`), numbered `053+`, including the additive `material_producers.image` column changeset (see Requirement 8) among them.

### Requirement 10: Admin CRUD UI

**User Story:** As an admin, I want construction-material-types, material-sellers, and construction-materials admin pages plus a Warehouse menu item, so that I can manage the construction branch without using the API directly.

#### Acceptance Criteria

1. THE System SHALL provide lazy-loaded pages `ConstructionMaterialTypesPage` at `/construction-material-types`, `MaterialSellersPage` at `/material-sellers`, and `ConstructionMaterialsPage` at `/materials/construction`, each using the shared `DataTable` with server search/sort/filter/pagination.
2. THE two dictionary pages SHALL render columns `code`, `name`, `active`, with `active` as a localized badge; `MaterialSellersPage` SHALL additionally render `website`.
3. THE `ConstructionMaterialsPage` SHALL render columns `name`, `type`, `producer`, `seller`, `packages`, `unit`, `currency`, and `retailNet`, and SHALL display the computed price range per (`package`, `type`) pair; an image thumbnail column is OPTIONAL, but the resolved CDN image URL SHALL be exposed on the DTO.
4. THE two dictionary pages SHALL provide a create/edit form (`code`, `nameRU`, `namePL`, `active`, plus `website` for sellers) with validation; `code` SHALL be read-only on edit; delete SHALL be via a confirmation dialog.
5. THE `ConstructionMaterialsPage` create/edit form SHALL provide a `name` input, a REQUIRED `type` selector (reference to `CONSTRUCTION_MATERIAL_TYPES`), an OPTIONAL `producer` selector (reference to `MATERIAL_PRODUCERS`), an OPTIONAL `seller` selector (reference to `MATERIAL_SELLERS`), a REQUIRED multi-select `packages` (reference to `OFFER_PACKAGES`, at least one), a REQUIRED `unit` selector, a REQUIRED `currency` selector, `purchasePrice`/`retailGross`/`retailNet` inputs, an OPTIONAL `website` input, an OPTIONAL `image` upload control (uploading through the ImageStorage backend endpoint and, when an image is present, rendering the resolved CDN image), and an `active` toggle; the `ConstructionMaterial` form SHALL have no `code` field.
6. IF a form field fails validation on submit (missing type, empty packages, missing unit or currency, out-of-range price, `website` over 255 characters, or a rejected image upload), THEN THE System SHALL block submission, retain all entered values, and display a localized validation message identifying each invalid field.
7. THE `MaterialProducer` create/edit form SHALL provide an OPTIONAL `image` upload control (uploading through the shared ImageStorage backend endpoint) and, when a producer image is present, SHALL render the resolved CDN image.
8. WHEN a user selects an image on the construction-material or producer form, THE System SHALL upload it through the ImageStorage backend endpoint and, on success, SHALL associate the returned object key with the entity on save.
9. THE list fetch adapters SHALL use the shared `buildFetchQuery`; reference filters SHALL follow FOR-04-01; mutations SHALL invalidate the corresponding list query and SHALL show a toast.
10. WHERE the current user lacks the CREATE, UPDATE, or DELETE permission on a page's resource, THE System SHALL hide the corresponding create, edit, or delete control, as resolved by `usePermission`.
11. THE System SHALL add a "Стройматериалы / Construction Materials" navigation item under the "Склад / Warehouse" section pointing to `/materials/construction`, with i18n key `nav.constructionMaterials` at PL+RU parity, ABAC-gated on `MATERIALS_CONSTRUCTION` READ.
12. WHILE FOR-04-15 has not wired the Dictionaries menu, THE System SHALL guard `/construction-material-types` with an interim `CONSTRUCTION_MATERIAL_TYPES`/`READ` guard, `/material-sellers` with an interim `MATERIAL_SELLERS`/`READ` guard, and `/materials/construction` with an interim `MATERIALS_CONSTRUCTION`/`READ` guard.
13. THE System SHALL render all UI text from keys under `constructionMaterialTypes.*`, `materialSellers.*`, and `constructionMaterials.*` defined at parity in BOTH `pl.json` and `ru.json`, and SHALL NOT render any raw i18n key.

### Requirement 11: Placeholder seed now, research CSV later

**User Story:** As a delivery lead, I want the full type/seller/material data delivered by the research spec after CSV approval, so that this spec ships the schema and a minimal placeholder while the real Polish-market data is researched separately.

#### Acceptance Criteria

1. THE data seed in THIS spec SHALL be a MINIMAL PLACEHOLDER — a few example rows for `construction_material_types`, `material_sellers`, and `construction_materials` — sufficient to exercise the schema and tests.
2. THE full data seed for construction material types, sellers, and construction materials SHALL be delivered by the separate research spec `FOR-04-RESEARCH-construction-materials`, after the researched CSV is approved by the user and parsed into SQL.
3. THE placeholder seed changesets SHALL be idempotent (`MARK_RAN` + `sqlCheck` on `code`) so the later research seed can add rows without conflict.
4. MANY additional construction-brand rows MAY be added to the existing `MATERIAL_PRODUCERS` dictionary, but THIS spec SHALL NOT recreate the `MATERIAL_PRODUCERS` resource, entity, or page.

### Requirement 12: Backend tests

**User Story:** As a maintainer, I want integration and property tests, so that CRUD, i18n, immutability, the package cascade, the price range, grants, and idempotency stay verified.

#### Acceptance Criteria

1. FOR EACH of the two new dictionaries, THE System SHALL have a Testcontainers CRUD integration test (create/list/read/update/delete, i18n `name` resolution ru/pl/fallback, `code` immutability on update, create validation `400`).
2. THE System SHALL have a `ConstructionMaterial` CRUD integration test covering a required `type`, an optional `producer`, an optional `seller`, and a required `packages` set of at least one package, including rejection when `packages` is empty.
3. THE System SHALL have an integration test for the package cascade: deleting an `OfferPackage` removes its `construction_material_packages` join rows and deletes any construction material left with zero packages, while leaving materials that still have a package unchanged.
4. THE System SHALL have a price-range test asserting the computed range for a (`OfferPackage`, `ConstructionMaterialType`) pair equals the MIN and MAX of `retailNet` across active construction materials of that type whose `packages` contain that package, that a material belonging to several packages contributes to each package's range, that null `retailNet` is excluded, and that a pair with no priced active material returns an empty range.
5. THE price-range MIN..MAX-by-(package,type) computation SHALL be covered by a property-based test over generated sets of construction materials, package memberships, and retail-net values.
6. FOR EACH of the three resources (`CONSTRUCTION_MATERIAL_TYPES`, `MATERIAL_SELLERS`, `MATERIALS_CONSTRUCTION`), THE System SHALL have a Liquibase seed integration test asserting the resource is present, the exact grant matrix of Requirement 9 (including CLIENT none and DELETE on `MATERIALS_CONSTRUCTION` for ADMIN only), and re-run idempotency.
7. THE System SHALL have an ImageStorage upload happy-path test asserting a valid image upload stores an object under the correct per-entity-kind key namespace and the entity's DTO resolves the CDN URL from the stored object key.
8. THE System SHALL have an ImageStorage rejection test asserting a non-image content type and an over-max-size upload are each rejected with a client error and store no object.
9. THE System SHALL have an ImageStorage orphan-cleanup test asserting that replacing an entity's image deletes the previous object, that detaching the image or deleting the entity deletes its object, and that the reconciliation job deletes a bucket object no DB row references.
10. THE System SHALL have an integration test for the additive `material_producers.image` column asserting the column is present, the changeset re-runs idempotently, and a producer round-trips an image object key resolved to a CDN URL.
11. THE System SHALL have a clean-startup smoke test confirming the fully annotated controllers start cleanly under `PermissionAnnotationValidator`.

### Requirement 13: UI tests

**User Story:** As a maintainer, I want component and localization tests for the three pages, so that list rendering, permission gating, and translations stay verified.

#### Acceptance Criteria

1. FOR EACH page, THE System SHALL have a component test asserting the listed columns render (dictionaries: `code`/`name`/`active`, plus `website` for sellers; construction materials: `name`/`type`/`producer`/`seller`/`packages`/`unit`/`currency`/`retailNet` with the per-(package,type) price range), that permission-gated controls appear only with the permission, and that the empty state renders a localized message.
2. THE System SHALL have a UI test asserting the image upload control appears on the construction-material form and on the producer form, and that a construction material or producer with an image renders its resolved image.
3. FOR EACH namespace (`constructionMaterialTypes.*`, `materialSellers.*`, `constructionMaterials.*`), THE System SHALL have a key-parity test across `pl.json` and `ru.json`, all keys non-empty, with no raw key rendered.
4. THE System SHALL have a localization test asserting the `nav.constructionMaterials` key is present at parity in `pl.json` and `ru.json`.

### Requirement 14: Parallel delivery

**User Story:** As a delivery lead, I want the two dictionaries and the entity built as parallel tracks, so that they can progress concurrently without file conflicts.

#### Acceptance Criteria

1. THE tasks plan SHALL schedule ConstructionMaterialType, MaterialSeller, and ConstructionMaterial as tracks in a wave-based Task Dependency Graph, with the two dictionary tracks preceding the `ConstructionMaterial` track that references them.
2. EACH track SHALL be its own backend vertical + changeset(s) + frontend page + i18n + tests.
3. THE `ConstructionMaterial` track SHALL depend on the two new dictionary tracks (for `type` and `seller` references) and on the existing `MATERIAL_PRODUCERS`, `OFFER_PACKAGES`, `MEASUREMENT_UNITS`, and `CURRENCIES` dictionaries.
