# Implementation Plan

FOR-04-17: Construction Materials

## Overview

Built IN ONE SPEC with parallel tracks (mirroring FOR-04-16): two NEW plain
dictionaries, one NEW operational entity with a computed price range, one shared
reusable image-storage seam, and one additive column on the existing producer
vertical. Every resource is a GLOBAL admin resource — no service implements
`ProjectScopedService`.

- **Track 1 — ConstructionMaterialType** (`CONSTRUCTION_MATERIAL_TYPES`, table `construction_material_types`, changesets `053`/`054`): NEW backend CRUD vertical + frontend page `/construction-material-types` — a straight FOR-04-16 dictionary mirror.
- **Track 2 — MaterialSeller** (`MATERIAL_SELLERS`, table `material_sellers`, changesets `055`/`056`): NEW backend CRUD vertical (+ `website`) + frontend page `/material-sellers` — a FOR-04-16 dictionary mirror plus one `website` field carried end-to-end.
- **Track 3 — ConstructionMaterial** (`MATERIALS_CONSTRUCTION`, table `construction_materials` + join `construction_material_packages`, changesets `057`/`058`): NEW entity with reference validation, package cascade, reference filters, `PriceRangeResolver` + `GET /price-ranges`, and image support + frontend page `/materials/construction`.
- **Shared — ImageStorage** (`com.foremen.service.image`): backend-mediated GCS upload (`ImageController` `POST /api/images`), object-key persistence + read-time CDN URL resolution, two-trigger orphan cleanup, `@Scheduled` reconciliation job, config-driven `DisabledImageStorage` fallback. Consumed by ConstructionMaterial and MaterialProducer.
- **Reuse — MaterialProducer image** (existing FOR-04-16 vertical, changeset `059`): additive nullable `material_producers.image` column + producer DTO/form image wiring. No producer resource/entity/page recreated.

Changesets are numbered `053+` (current highest registered in `changelog.xml` is
`052`), each registered LAST in order `053`→`059`, idempotent per
`.kiro/steering/entity-creation-rules.md`. The full Polish-market data seed is
delivered separately by `FOR-04-RESEARCH-construction-materials`; this spec seeds
only minimal `sqlCheck`-guarded placeholder rows (Requirement 11).

### Two-repository split (git-repo-structure)

Per `.kiro/steering/git-repo-structure.md`, backend work (entities, DAOs, services,
controllers, `ImageStorage`, Liquibase, backend tests) lives in the **root repo**
(`foremen-backend/`, `.kiro/`, `docker-compose.yml`). Frontend work (three pages,
producer form edit, i18n, nav, UI tests) lives in the **nested `foremen-frontend/`
repo**. Backend tasks (1–8, 15) commit from the root repo; frontend tasks (10–13)
commit from inside `foremen-frontend/`. A change spanning both needs one commit per
repo.

## Tasks

- [x] 1. Track 1 — ConstructionMaterialType backend vertical (root repo)
  - [x] 1.1 `ConstructionMaterialTypeEntity` (`@Table("construction_material_types")`, `code`/`nameRU`/`namePL`/`active`) extending `BaseEntity` + `ConstructionMaterialTypeDao extends AdminDao<…, Long>` + service models (`ConstructionMaterialTypeServiceModel {id, code, name, active}`, `…ServiceExtendedModel {id, code, nameRU, namePL, active}`) + controller model records (`…DtoModel`, `…DtoExtendedModel`, `…CreateRequest {@NotBlank code/nameRU/namePL, Boolean active}`, `…UpdateRequest {@NotBlank nameRU/namePL, Boolean active}` (no `code`), `…CreateResponse`/`…UpdateResponse`)
    - _Requirements: 1.1, 1.7_
  - [x] 1.2 Mappers (`ConstructionMaterialTypeServiceMapper` `getI18nSupportedProperties()=Set.of("name")`, ignore `id`+`code` on update; `ConstructionMaterialTypeControllerMapper` ignore `id`/create, `id`+`code`/update, default `active`→`true`) + `ConstructionMaterialTypeService implements AdminService<…>` (NOT `ProjectScopedService`) + `ConstructionMaterialTypeController` at `/api/construction-material-types`, `@PermissionResource("CONSTRUCTION_MATERIAL_TYPES")`
    - _Requirements: 1.4, 1.5, 1.6, 1.8_
  - [x] 1.3 `053-create-construction-material-types.xml` (table `construction_material_types`: `code` NOT NULL UNIQUE `uk_construction_material_types_code`, `name_ru`/`name_pl` NOT NULL, `active` NOT NULL default `true`, + `BaseEntity` audit columns; idempotent `tableExists`+`MARK_RAN`; register LAST)
    - _Requirements: 1.2, 1.3_
  - [x] 1.4 `054-seed-construction-material-types-resource.xml` (resource `CONSTRUCTION_MATERIAL_TYPES` with non-null `code`/`name_ru`/`name_pl`/`description_ru`/`description_pl`; grants ADMIN CRUD, MANAGER/FOREMAN/WORKER/FINANCIER READ, CLIENT none; a few placeholder type rows e.g. `farba`, `klej_plytki`; every insert guarded `sqlCheck expectedResult="0"`+`MARK_RAN`; register LAST)
    - _Requirements: 1.9, 9.1, 9.2, 9.7, 9.8, 11.1, 11.3_

- [x] 2. Track 2 — MaterialSeller backend vertical (root repo)
  - [x] 2.1 `MaterialSellerEntity` (`@Table("material_sellers")`, `code`/`nameRU`/`namePL`/`active` + `@Column(name="website", length=255) String website` nullable) extending `BaseEntity` + `MaterialSellerDao extends AdminDao<…, Long>` + service models carrying `website` (`…ServiceModel {id, code, name, active, website}`, `…ServiceExtendedModel {id, code, nameRU, namePL, active, website}`) + controller records (`…DtoModel`, `…DtoExtendedModel`, `…CreateRequest {@NotBlank code/nameRU/namePL, Boolean active, @Size(max=255) String website}`, `…UpdateRequest {@NotBlank nameRU/namePL, Boolean active, @Size(max=255) String website}` (no `code`), `…CreateResponse`/`…UpdateResponse`)
    - _Requirements: 2.1, 2.7_
  - [x] 2.2 Mappers (`MaterialSellerServiceMapper` `getI18nSupportedProperties()=Set.of("name")`, ignore `id`+`code` on update, carry `website`; `MaterialSellerControllerMapper` ignore `id`/create, `id`+`code`/update, default `active`→`true`) + `MaterialSellerService implements AdminService<…>` (NOT `ProjectScopedService`) + `MaterialSellerController` at `/api/material-sellers`, `@PermissionResource("MATERIAL_SELLERS")` — `website` updatable on update
    - _Requirements: 2.4, 2.5, 2.6, 2.8_
  - [x] 2.3 `055-create-material-sellers.xml` (table `material_sellers`: dictionary columns + nullable `website VARCHAR(255)`, `code` UNIQUE `uk_material_sellers_code`, + `BaseEntity` audit columns; idempotent `tableExists`+`MARK_RAN`; register LAST)
    - _Requirements: 2.2, 2.3_
  - [x] 2.4 `056-seed-material-sellers-resource.xml` (resource `MATERIAL_SELLERS` with non-null description columns; grants ADMIN CRUD, MANAGER/FOREMAN/WORKER/FINANCIER READ, CLIENT none; a few placeholder seller rows e.g. `leroy_merlin` with `website=https://www.leroymerlin.pl`, `castorama`; each insert `sqlCheck`+`MARK_RAN`; register LAST)
    - _Requirements: 2.9, 9.1, 9.2, 9.7, 9.8, 11.1, 11.3_

- [x] 3. Shared — ImageStorage service, controller & reconciliation job (root repo)
  - [x] 3.1 `com.foremen.service.image.ImageStorage` interface (`store(MultipartFile, keyNamespace)→objectKey`, `toCdnUrl(objectKey)→url` null-safe pure, `deleteIfOrphan(objectKey)`, `isConfigured()`) + `foremen.image-storage.*` config binding (bucket, cdnBase, credentials, maxUploadSize, reconciliation schedule) read from `application.yml`/env, never hardcoded
    - _Requirements: 7.1, 7.11_
  - [x] 3.2 `GcsImageStorage` implementation (conditional on config): `store(...)` validates content type in `{image/png,image/jpeg,image/webp}` and size ≤ configured max (else client-error `400`, nothing stored), namespaces keys per kind (`producers/{uuid}.{ext}`, `construction-materials/{uuid}.{ext}`), uploads to the GCS bucket; `toCdnUrl(key)=cdnBase + "/" + key` (null key → null); `deleteIfOrphan(key)` re-checks the DB (union across `construction_materials.image` + `material_producers.image`) before deleting the bucket object
    - _Requirements: 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9_
  - [x] 3.3 `DisabledImageStorage` fallback bean (active when not configured): `store(...)` rejects gracefully with a client-error `400`, `toCdnUrl(...)` returns null, `isConfigured()` false — so entities with null images keep reading/listing
    - _Requirements: 7.12_
  - [x] 3.4 `ImageController` `POST /api/images` (`multipart/form-data`, param `entityKind`/target `resource`) delegating to `ImageStorage.store(...)`, returning `{ objectKey, imageUrl }`; method-level `@RequiresPermission` bound to the target resource (`MATERIALS_CONSTRUCTION`/`MATERIAL_PRODUCERS` `CREATE`/`UPDATE`), the resource passed as a request param and validated against seeded resources — no direct-from-frontend signed-URL upload
    - _Requirements: 7.2, 9.5, 9.6_
  - [x] 3.5 `ImageReconciliationJob` (`@Scheduled`, cron/interval from `foremen.image-storage.*`): list bucket objects, subtract the union of DB-referenced object keys across all image columns, delete every bucket object no row references
    - _Requirements: 7.10_
  - [x] 3.6 Write property test for CDN URL resolution
    - **Property 3: Image CDN URL is a null-safe round-trip of the stored object key** (`ImageUrlResolutionPropertyTest`): generate object keys + CDN bases; assert `toCdnUrl` equals `cdnBase + "/" + key`, null key → null URL, and `store` output begins with the requested per-entity-kind namespace prefix (mocked bucket, ≥ 100 iterations)
    - **Validates: Requirements 4.7, 7.3, 7.4, 7.5, 8.4**

- [x] 4. Track 3 — ConstructionMaterial entity, join table & DAO (root repo)
  - [x] 4.1 `ConstructionMaterialEntity` (`@Table("construction_materials")`) extending `BaseEntity`: `nameRU`/`namePL` (no `code`), mandatory `@ManyToOne type` (`type_id` NOT NULL), optional `@ManyToOne producer`/`seller`, `@ManyToMany packages` via `construction_material_packages`, mandatory `@ManyToOne unit`/`currency`, nullable `purchasePrice`/`retailGross`/`retailNet` (`NUMERIC(12,2)`), nullable `website` (255), nullable `image` (512, GCS object key), `active` NOT NULL default `true` + `ConstructionMaterialDao extends AdminDao<…, Long>`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10_
  - [x] 4.2 `057-create-construction-materials.xml` (table `construction_materials` with all columns + `BaseEntity` audit + six entity FKs; join table `construction_material_packages(construction_material_id, offer_package_id)` composite PK with both FKs `ON DELETE CASCADE` per the FOR-04-12b pattern; idempotent `tableExists`+`MARK_RAN`; register LAST)
    - _Requirements: 3.11, 3.12, 5.2_

- [x] 5. Track 3 — ConstructionMaterial service, mappers, controller & CRUD (root repo)
  - [x] 5.1 Service/controller model records: `ConstructionMaterialServiceModel`/`…ServiceExtendedModel`; `…CreateRequest`/`…UpdateRequest` (`@NotBlank nameRU/namePL`, `@NotNull typeId`, optional `producerId`/`sellerId`, `@NotEmpty Set<Long> offerPackageIds`, `@NotNull unitId`/`currencyId`, three `@DecimalMin("0.00")@DecimalMax("9999999999.99")` nullable prices, `@Size(max=255) website`, `@Size(max=512) image`, `Boolean active`); `…DtoModel` (list row: `RefDto` for `type`/`producer`/`seller`/`unit`/`currency`, `List<RefDto> packages`, prices, `website`, resolved `imageUrl`, `active`, per-(package,type) price-range payload); `…DtoExtendedModel` (+ raw `typeId`/`producerId`/`sellerId`/`offerPackageIds`/`unitId`/`currencyId`, `imageUrl`); create/update responses
    - _Requirements: 4.1, 4.7_
  - [x] 5.2 Mappers (`ConstructionMaterialServiceMapper` `getI18nSupportedProperties()=Set.of("name")`; `ConstructionMaterialControllerMapper` maps refs → `RefDto` localized, resolves `imageUrl` via `ImageStorage.toCdnUrl(...)`, default `active`→`true`) + `ConstructionMaterialService implements AdminService<…>` (NOT `ProjectScopedService`) with a `normalize(...)` write path (mirroring `RoomService.resolveReferences`): resolve-and-load every reference (`typeId`/`producerId`/`sellerId`/`unitId`/`currencyId`/each `offerPackageId`), reject empty `packages`, range-check the three prices, `@Size` `website`; on image replace/detach/delete call `ImageStorage.deleteIfOrphan(previousKey)`; nothing persisted on any validation failure
    - _Requirements: 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.10, 5.1, 7.8, 7.9_
  - [x] 5.3 `ConstructionMaterialController` at `/api/construction-materials`, `@PermissionResource("MATERIALS_CONSTRUCTION")` implementing `AdminController<…>` (create/list/read/update/delete/count/metadata/i18n); reference filters `type.id`/`producer.id`/`seller.id`/`unit.id`/`currency.id`/`packages.id` ride the existing FOR-04-01 `SpecificationBuilder` grammar (collection path → JOIN + distinct)
    - _Requirements: 4.1, 4.9, 9.5, 9.6_
  - [x] 5.4 Write property test for write-path validation
    - **Property 4: Write validation rejects dangling references and out-of-range prices** (`ConstructionMaterialWriteValidationPropertyTest`): generate dangling reference ids across all reference fields and out-of-range prices; assert rejection naming the offending field with no persistence, and that an all-valid request (references exist, prices in range, required fields present) is accepted (≥ 100 iterations)
    - **Validates: Requirements 4.3, 4.4, 4.5**

- [x] 6. Track 3 — Package-deletion cascade cleanup (root repo)
  - [x] 6.1 Override `OfferPackageService.deleteById` (reuse of the existing offer-package vertical): after the DB `ON DELETE CASCADE` removes join rows, in the SAME transaction delete every `construction_materials` row now holding zero packages (`… WHERE id NOT IN (SELECT construction_material_id FROM construction_material_packages)` scoped to rows that had referenced the deleted package); leave multi-package materials untouched
    - _Requirements: 5.2, 5.3, 5.4_
  - [x] 6.2 Write property test for the package-deletion invariant
    - **Property 2: Deleting an offer package preserves the "≥ 1 package" invariant** (`ConstructionMaterialPackageCascadePropertyTest`, Testcontainers): generate materials with random memberships, delete a random package, assert no join row references it, surviving materials keep exactly their remaining packages, and no surviving material has zero packages (≥ 100 iterations)
    - **Validates: Requirements 5.2, 5.3, 5.4**

- [x] 7. Track 3 — PriceRangeResolver & GET /price-ranges (root repo)
  - [x] 7.1 `PriceRangeResolver` `@Component`: records `PriceRangeKey(offerPackageId, constructionMaterialTypeId)` + `PriceRange(min, max)`; `compute(Collection<ConstructionMaterialEntity>)→Map<PriceRangeKey, PriceRange>` and `rangeFor(materials, packageId, typeId)→PriceRange` — a pure, deterministic, total function: consider only `active` materials with non-null `retailNet`, fan each out to `(p.id, type.id)` for every `p` in its packages, `min`/`max` per bucket via `BigDecimal.min`/`max`, empty bucket → `PriceRange(null, null)`; never persists
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_
  - [x] 7.2 `GET /api/construction-materials/price-ranges` (optional `?packageId=&typeId=`) with method-level `@RequiresPermission("MATERIALS_CONSTRUCTION", "READ")`: load active priced materials via a targeted DAO query, return either the full map (as `{offerPackageId, typeId, min, max}` rows) or a single `PriceRange`; also populate each list-DTO row's per-(package,type) range payload so the UI needs no second round-trip
    - _Requirements: 6.1, 6.4, 9.5, 9.6_
  - [x] 7.3 Write property test for the price-range computation (PRIMARY PBT target)
    - **Property 1: Price range is the MIN..MAX of qualifying retail-net** (`PriceRangeResolverPropertyTest`): generate sets of materials with random `type`/`active`/`retailNet` (incl. nulls) and random package memberships; assert per-`(package,type)` `min`/`max` equal the min/max over the qualifying subset, fan-out across packages holds, null `retailNet` excluded, empty pairs give an empty range, `min ≤ max`, and a repeated computation is identical (in-memory, ≥ 100 iterations)
    - **Validates: Requirements 6.1, 6.2, 6.3, 6.5, 6.6, 6.7**

- [x] 8. Reuse — MaterialProducer additive image + resource/grants for MATERIALS_CONSTRUCTION (root repo)
  - [x] 8.1 Add `@Column(name="image", length=512) String image` (nullable GCS object key) to the existing `MaterialProducerEntity`; carry it through the producer service models and controller DTOs (create/update accept the object key `@Size(max=512)`, read/list expose `imageUrl` via `ImageStorage.toCdnUrl(...)`); producer service write path calls `ImageStorage.deleteIfOrphan(previousKey)` on image replace/detach/delete — no producer resource/entity/page recreated
    - _Requirements: 8.1, 8.4, 8.5, 11.4_
  - [x] 8.2 `059-add-material-producers-image.xml` (additive nullable `material_producers.image VARCHAR(512)`; `addColumn` guarded by `columnExists` precondition + `onFail="MARK_RAN"`; register LAST)
    - _Requirements: 8.2, 8.3, 9.8_
  - [x] 8.3 `058-seed-construction-materials-resource.xml` (resource `MATERIALS_CONSTRUCTION` with non-null description columns; grants ADMIN CRUD, MANAGER CREATE/READ/UPDATE (NO DELETE), FOREMAN/WORKER/FINANCIER READ, CLIENT none, DELETE ADMIN-only; 1–2 placeholder material rows resolving placeholder `type_id`/`unit_id`/`currency_id`=`PLN`/≥1 existing package by sub-select, guarded by `sqlCheck` on `name_pl`, inserting nothing when a reference is absent; each insert `sqlCheck`+`MARK_RAN`; register LAST)
    - _Requirements: 9.1, 9.3, 9.4, 9.7, 9.8, 11.1, 11.3_

- [x] 9. Checkpoint — backend compiles, changelog registers 053–059 in order, controllers pass PermissionAnnotationValidator
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 9.6, 9.8_

- [x] 10. Track 1 — ConstructionMaterialType admin page (foremen-frontend repo)
  - [x] 10.1 Scaffolding under `foremen-frontend/src/features/construction-material-types/` (types, `construction-material-types-api.ts` via `buildFetchQuery`, query/mutation hooks `constructionMaterialTypeKeys` that invalidate + toast, zod schema)
    - _Requirements: 10.1, 10.9_
  - [x] 10.2 `ConstructionMaterialTypesList` + `ConstructionMaterialTypesPage` + `ConstructionMaterialTypeFormSheet` + `DeleteConstructionMaterialTypeDialog` (columns `code`/`name`/`active` localized badge; `code` read-only on edit; permission-gated create/edit/delete via `usePermission`) + lazy route + interim guard (`/construction-material-types` → `CONSTRUCTION_MATERIAL_TYPES`/`READ`) + i18n `constructionMaterialTypes.*` at PL+RU parity (no raw key)
    - _Requirements: 10.1, 10.2, 10.4, 10.6, 10.10, 10.12, 10.13_
  - [x] 10.3 `ConstructionMaterialTypesList.test.tsx` (columns render, permission gating, localized empty state) + `constructionMaterialTypes.*` i18n key-parity test (pl/ru, all non-empty)
    - _Requirements: 13.1, 13.3_

- [x] 11. Track 2 — MaterialSeller admin page (foremen-frontend repo)
  - [x] 11.1 Scaffolding under `foremen-frontend/src/features/material-sellers/` (types incl. `website`, `material-sellers-api.ts` via `buildFetchQuery`, hooks `materialSellerKeys`, zod schema with `website` ≤ 255)
    - _Requirements: 10.1, 10.9_
  - [x] 11.2 `MaterialSellersList` + `MaterialSellersPage` + `MaterialSellerFormSheet` (+ `website` column and form field) + `DeleteMaterialSellerDialog` (columns `code`/`name`/`active` + `website`; `code` read-only on edit; permission-gated controls) + lazy route + interim guard (`/material-sellers` → `MATERIAL_SELLERS`/`READ`) + i18n `materialSellers.*` at PL+RU parity
    - _Requirements: 10.1, 10.2, 10.4, 10.6, 10.10, 10.12, 10.13_
  - [x] 11.3 `MaterialSellersList.test.tsx` (columns incl. `website`, permission gating, empty state) + `materialSellers.*` i18n key-parity test
    - _Requirements: 13.1, 13.3_

- [x] 12. Track 3 — ConstructionMaterial admin page + shared image control (foremen-frontend repo)
  - [x] 12.1 Scaffolding under `foremen-frontend/src/features/construction-materials/` (types incl. price-range payload + `imageUrl`, `construction-materials-api.ts` via `buildFetchQuery` with FOR-04-01 reference filters, hooks `constructionMaterialKeys` invalidate + toast, zod schema) + a shared `ImageUploadControl` component posting to `POST /api/images` and rendering the resolved CDN image
    - _Requirements: 10.1, 10.8, 10.9_
  - [x] 12.2 `ConstructionMaterialsList` + `ConstructionMaterialsPage` (`DataTable entityKey="construction-materials" resource="MATERIALS_CONSTRUCTION"`, columns `name`/`type`/`producer`/`seller`/`packages`/`unit`/`currency`/`retailNet` + rendered per-(package,type) price range, optional image thumbnail) + `ConstructionMaterialFormSheet` (`name`, required `type`, optional `producer`/`seller`, required multi-select `packages` ≥ 1, required `unit`/`currency`, three price inputs, optional `website`, optional `ImageUploadControl`, `active` toggle; no `code`) + `DeleteConstructionMaterialDialog` + lazy route + interim guard (`/materials/construction` → `MATERIALS_CONSTRUCTION`/`READ`) + Warehouse nav item `{ path:'/materials/construction', labelKey:'nav.constructionMaterials', requiredPermission:{ resource:'MATERIALS_CONSTRUCTION', operation:'READ' } }` in `navigation.ts` + i18n `constructionMaterials.*` + `nav.constructionMaterials` at PL+RU parity
    - _Requirements: 10.1, 10.3, 10.5, 10.6, 10.8, 10.9, 10.10, 10.11, 10.12, 10.13_
  - [x] 12.3 `ConstructionMaterialsList.test.tsx` (columns + price-range render, permission gating, empty state) + image-control UI test (upload control on the construction-material form; a material with an image renders its resolved image) + `constructionMaterials.*` and `nav.constructionMaterials` i18n parity tests
    - _Requirements: 13.1, 13.2, 13.3, 13.4_

- [x] 13. Reuse — MaterialProducer form image control (foremen-frontend repo)
  - [x] 13.1 Add the shared `ImageUploadControl` to the existing `MaterialProducerFormSheet` (upload via `POST /api/images`, render the resolved CDN image when present, associate the returned object key on save); nothing else on the producer page changes
    - _Requirements: 10.7, 10.8_
  - [x] 13.2 Extend the producer form/list UI test to assert the image upload control appears and a producer with an image renders its resolved image
    - _Requirements: 13.2_

- [x] 14. Checkpoint — frontend tsc + the new/changed UI test files
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 13.1, 13.3_

- [x] 15. Backend example / integration / seed / smoke tests (root repo)
  - [x] 15.1 `ConstructionMaterialTypeControllerIntegrationTest` + `MaterialSellerControllerIntegrationTest` (Testcontainers CRUD; i18n `name` ru/pl/fallback; `code` immutable on update; create validation `400`; sellers: `website` null / 255 / 256 boundary)
    - _Requirements: 12.1, 1.5, 1.6, 1.7, 2.5, 2.6, 2.7_
  - [x] 15.2 `ConstructionMaterialControllerIntegrationTest` (CRUD with required `type`, optional `producer`/`seller`, required `packages` ≥ 1 incl. empty-set rejection; reference filters `type.id`/`packages.id`; i18n `name`)
    - _Requirements: 12.2, 4.9_
  - [x] 15.3 `ConstructionMaterialPackageCascadeIntegrationTest` (example alongside Property 2: deleting a package removes join rows, deletes a zero-package material, leaves a multi-package material)
    - _Requirements: 12.3_
  - [x] 15.4 `ConstructionMaterialPriceRangeIntegrationTest` (example alongside Property 1: MIN/MAX per pair, multi-package contribution, null `retailNet` excluded, empty range for an unpriced pair)
    - _Requirements: 12.4_
  - [x] 15.5 `ImageStorageUploadIntegrationTest` + `ImageStorageRejectionTest` (happy path stores under the correct namespace and DTO resolves the CDN URL; non-image type and over-size each → `400`, nothing stored)
    - _Requirements: 12.7, 12.8_
  - [x] 15.6 `ImageStorageOrphanCleanupTest` (replace deletes previous object; detach/delete deletes object; reconciliation job deletes an unreferenced bucket object)
    - _Requirements: 12.9_
  - [x] 15.7 `MaterialProducerImageColumnIntegrationTest` (additive `image` column present; changeset idempotent; producer round-trips an object key → resolved CDN URL)
    - _Requirements: 12.10_
  - [x] 15.8 `ConstructionMaterialTypesResourceSeedIntegrationTest` + `MaterialSellersResourceSeedIntegrationTest` + `MaterialsConstructionResourceSeedIntegrationTest` (resource present; exact grant matrix incl. CLIENT none, MANAGER no-DELETE, DELETE ADMIN-only on `MATERIALS_CONSTRUCTION`; re-run idempotency)
    - _Requirements: 12.6, 9.2, 9.3, 9.4_
  - [x] 15.9 `ConstructionMaterialsAnnotationCoverageSmokeIT` (fully annotated controllers incl. `/price-ranges` and `/api/images` start cleanly under `PermissionAnnotationValidator`)
    - _Requirements: 12.11, 9.6_

- [x] 16. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping per track/dictionary + entity + image storage + price range, step-by-step scenarios, repeatability via a run-id generator or explicit teardown, a Регрессия group, and an MD report template). This is an API-heavy spec plus UI pages: backend scenarios are API tests against the Dockerized app (`http://localhost:8080`, auth `/api/auth`, first ADMIN via `FOREMEN_ADMIN_CREATE`/`_EMAIL`/`_PASSWORD`) covering the two dictionaries CRUD, ConstructionMaterial CRUD + reference validation + reference filters, `GET /price-ranges`, `POST /api/images` upload + rejection, and the ABAC grant matrix; UI scenarios are browser-engine flows against `http://localhost:3000` for the three pages + the producer image control. Result artifacts are MD reports with tables. Document in Russian.
  - _Requirements: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10_

- [x] 17. Final checkpoint — affected backend classes + frontend tsc/UI tests
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 12.1, 12.6, 13.1_

## Notes

- Tasks marked with `*` are optional (tests) and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirements (granular sub-requirement clauses) for traceability.
- Changesets are numbered `053+` (current highest is `052`); all files registered LAST in `changelog.xml` in order `053`→`059`, each idempotent per `.kiro/steering/entity-creation-rules.md`.
- New Managed Entity Checklist (entity-creation-rules): each new resource is seeded via a changeset (`054`/`056`/`058`), the ADMIN + role grants are seeded in the same changeset per the ABAC matrix, each concrete controller carries `@PermissionResource` matching the seeded `code` with `@PermissionOperation` on inherited CRUD handlers (and `@RequiresPermission` on `/price-ranges` + `/api/images`), and NO service implements `getProjectIdPath()` — all three resources are GLOBAL (not project-scoped).
- PBT applies to four correctness properties: Property 1 (price range — the primary target, task 7.3), Property 2 (package cascade, 6.2), Property 3 (CDN URL round-trip, 3.6), Property 4 (write validation, 5.4). Each property is its own sub-task placed close to its implementation and annotated with its property number + validated requirement clauses.
- Two-repo git structure: backend/spec/infra tasks (1–9, 15–17) commit from the ROOT repo; frontend tasks (10–14) commit from inside `foremen-frontend/`. A change spanning both needs one commit per repo — verify `git status` in both.
- Run ONLY the affected classes/files per the workspace test standard (`--tests` filters for backend, specific files for frontend); never the full suite unless the user explicitly asks (~20 min).
- The full Polish-market data seed comes later from `FOR-04-RESEARCH-construction-materials`; this spec seeds only minimal placeholder rows.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "3.1", "4.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "2.2", "2.3", "3.2", "3.3", "3.5", "4.2"] },
    { "id": 2, "tasks": ["1.4", "2.4", "3.4", "3.6", "5.1"] },
    { "id": 3, "tasks": ["5.2", "5.3", "6.1", "7.1", "8.1"] },
    { "id": 4, "tasks": ["5.4", "6.2", "7.2", "8.2", "8.3"] },
    { "id": 5, "tasks": ["7.3", "10.1", "11.1", "12.1", "13.1"] },
    { "id": 6, "tasks": ["10.2", "11.2", "12.2", "13.2"] },
    { "id": 7, "tasks": ["10.3", "11.3", "12.3", "15.1", "15.2", "15.3", "15.4", "15.5", "15.6", "15.7", "15.8", "15.9"] }
  ]
}
```
