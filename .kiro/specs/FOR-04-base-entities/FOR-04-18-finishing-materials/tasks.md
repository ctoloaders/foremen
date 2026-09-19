# Implementation Plan

FOR-04-18: Finishing Materials

## Overview

One NEW operational entity vertical — the sibling of FOR-04-17 but with the RICH
finishing fields, no computed price range, and a REAL large CSV data seed. Every
reference points at an EXISTING dictionary; the photo reuses the shared FOR-04-17
`ImageStorage` seam verbatim. `MATERIALS_FINISHING` is a GLOBAL admin resource — the
service does NOT implement `ProjectScopedService`.

- **Backend entity vertical — FinishingMaterial** (`MATERIALS_FINISHING`, table `finishing_materials` + join `finishing_material_packages`, changesets `060`/`061`): entity + DAO + service models + mappers + service (reference validation, price range NONE, derived label, flat audit snapshot) + controller + reference filters.
- **CSV data seed** (changeset `062`): the generated 581-row seed from `docs/Materiały pakiety - Lista.csv` — column mapping, comma-decimal price parsing, multi-value `Pakiet` normalization, reference resolution by sub-select, blank-category/absent-reference skipping, idempotent.
- **Shared image reuse** (no new service): consume `ImageStorage` / `POST /api/images` for the photo; the ONLY additive change is registering `finishing_materials.photo` in `ImageReferenceLookup.IMAGE_COLUMNS` and appending the `finishing_materials` zero-package cleanup to the existing `OfferPackageService.deleteById` override.
- **Frontend** (foremen-frontend repo): a single `/materials/finishing` admin page (DataTable + FK selects + packages multi-select + reused `ImageUploadControl` + link/model/sku/features/prices + active toggle + delete dialog), lazy route + interim guard, a Warehouse nav item, and `finishingMaterials.*` + `nav.finishingMaterials` i18n at PL+RU parity.

Changesets are numbered `060+` (current highest registered in `changelog.xml` is
`059`), each registered LAST in order `060`→`062`, idempotent per
`.kiro/steering/entity-creation-rules.md`.

### Two-repository split (git-repo-structure)

Per `.kiro/steering/git-repo-structure.md`, backend work (entity, DAO, service,
controller, Liquibase, the `ImageReferenceLookup`/`OfferPackageService` edits,
backend tests) lives in the **root repo** (`foremen-backend/`, `.kiro/`,
`docker-compose.yml`). Frontend work (the finishing-materials page, nav, i18n, UI
tests) lives in the **nested `foremen-frontend/` repo**. Backend tasks (1–5, 9)
commit from the root repo; frontend tasks (6–7) commit from inside
`foremen-frontend/`. A change spanning both needs one commit per repo.

## Tasks

- [x] 1. FinishingMaterial entity, join table & DAO (root repo)
  - [x] 1.1 `FinishingMaterialEntity` (`@Table("finishing_materials")`) extending `BaseEntity`: NO `code`/`name`; mandatory `@ManyToOne category` (`category_id` NOT NULL) + `material` (`material_id` NOT NULL); optional `@ManyToOne type`/`producer`; `@ManyToMany packages` via `finishing_material_packages`; mandatory `@ManyToOne unit`; free-text `model`/`sku` (255) + `features` (text); nullable `purchasePrice`/`retailGross`/`retailNet` (`NUMERIC(12,2)`, no per-unit price field); nullable `link` (1024); nullable `photo` (512, GCS object key); `active` NOT NULL default `true` + `FinishingMaterialDao extends AdminDao<…, Long>`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.11, 1.12_
  - [x] 1.2 `060-create-finishing-materials.xml` (table `finishing_materials` with all columns + `BaseEntity` audit + five entity FKs; join table `finishing_material_packages(finishing_material_id, offer_package_id)` composite PK with both FKs `ON DELETE CASCADE` per the FOR-04-12b/FOR-04-17 pattern; idempotent `tableExists`+`MARK_RAN`; register LAST)
    - _Requirements: 1.13, 1.14, 3.2_

- [x] 2. FinishingMaterial service, mappers, controller & CRUD (root repo)
  - [x] 2.1 Service/controller model records: `FinishingMaterialServiceModel`/`…ServiceExtendedModel`; `…CreateRequest`/`…UpdateRequest` (`@NotNull categoryId`/`materialId`, optional `typeId`/`producerId`, `@NotEmpty Set<Long> offerPackageIds`, `@NotNull unitId`, `@Size(max=255) model`/`sku`, `String features`, three `@DecimalMin("0.00")@DecimalMax("9999999999.99")` nullable prices (`purchasePrice`/`retailGross`/`retailNet`), `@Size(max=1024) link`, `@Size(max=512) photo`, `Boolean active`); `…DtoModel` (list row: derived `label`, `RefDto` for `category`/`material`/`type`/`producer`/`unit`, `List<RefDto> packages`, `model`/`sku`/`features`, three prices, `link`, resolved `photoUrl`, `active`); `…DtoExtendedModel` (+ raw `categoryId`/`materialId`/`typeId`/`producerId`/`offerPackageIds`/`unitId`, `photoUrl`); create/update responses — no `code`, no `name`
    - _Requirements: 2.1, 2.8, 2.9_
  - [x] 2.2 Mappers (`FinishingMaterialServiceMapper`; `FinishingMaterialControllerMapper` maps refs → localized `RefDto`, builds derived `label = material + " — " + model`, resolves `photoUrl` via `ImageStorage.toCdnUrl(...)`, default `active`→`true`) + `FinishingMaterialService implements AdminService<…>` (NOT `ProjectScopedService`) with a `normalize(...)` write path (mirroring `ConstructionMaterialService.resolveReferences`): resolve-and-load every reference (`categoryId`/`materialId`/`typeId`/`producerId`/`unitId`/each `offerPackageId`), reject missing required `category`/`material`/`unit` and empty `packages`, range-check the three prices, `@Size` `model`/`sku`/`link`; on photo replace/detach/delete call `ImageStorage.deleteIfOrphan(previousKey)`; flat custom audit snapshot serializing nested references as a simple name (like `ConstructionMaterialService.serializeEntity`); nothing persisted on any validation failure
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.11, 3.1, 4.6_
  - [x] 2.3 `FinishingMaterialController` at `/api/finishing-materials`, `@PermissionResource("MATERIALS_FINISHING")` implementing `AdminController<…>` (create/list/read/update/delete/count/metadata/i18n, inherited `@PermissionOperation`; no custom endpoints); reference filters `category.id`/`material.id`/`type.id`/`producer.id`/`unit.id`/`packages.id` ride the existing FOR-04-01 `SpecificationBuilder` grammar (collection path → JOIN + distinct)
    - _Requirements: 2.1, 2.10, 5.4, 5.5_
  - [x] 2.4 Write property test for write-path validation
    - **Property 1: Write validation rejects missing required fields, dangling references, and out-of-range prices** (`FinishingMaterialWriteValidationPropertyTest`): generate requests omitting a required reference, with an empty `packages` set, with exactly one dangling reference id across all six reference fields, or with an out-of-range value in each of the three price fields; assert rejection naming the offending field with no persistence, and that an all-valid request is accepted (≥ 100 iterations)
    - **Validates: Requirements 2.2, 2.3, 2.4, 2.5**

- [x] 3. Package-deletion cascade cleanup (root repo)
  - [x] 3.1 Extend the existing `OfferPackageService.deleteById` override (already carries the FOR-04-17 `construction_materials` cleanup): after the DB `ON DELETE CASCADE` removes join rows, in the SAME transaction ALSO delete every `finishing_materials` row now holding zero packages (`… WHERE id NOT IN (SELECT finishing_material_id FROM finishing_material_packages)` scoped to rows that had referenced the deleted package); leave multi-package materials untouched
    - _Requirements: 3.2, 3.3, 3.4_
  - [x] 3.2 Write property test for the package-deletion invariant
    - **Property 2: Deleting an offer package preserves the "≥ 1 package" invariant** (`FinishingMaterialPackageCascadePropertyTest`, Testcontainers): generate materials with random memberships, delete a random package, assert no join row references it, surviving materials keep exactly their remaining packages, and no surviving material has zero packages (≥ 100 iterations)
    - **Validates: Requirements 3.2, 3.3, 3.4**

- [x] 4. Shared image reuse — photo wiring & reference-lookup coverage (root repo)
  - [x] 4.1 Register `finishing_materials.photo` in the shared `ImageReferenceLookup.IMAGE_COLUMNS` (add `new ImageColumn("finishing_materials", "photo")`) so both the eager `deleteIfOrphan` trigger and the periodic `ImageReconciliationJob` count finishing-material photo references; the lookup's `information_schema.columns` existence guard keeps it graceful before changeset `060` runs — no other change to `com.foremen.service.image`
    - _Requirements: 4.1, 4.5_
  - [x] 4.2 Write property test for photo CDN URL resolution reuse
    - **Property 3: Photo CDN URL is a null-safe round-trip of the stored object key** (`FinishingMaterialPhotoUrlResolutionPropertyTest`): generate object keys + CDN bases; assert `toCdnUrl` equals `cdnBase + "/" + key`, null key → null URL, and a finishing-material upload object key begins with the `finishing-materials/` namespace prefix (mocked bucket, ≥ 100 iterations)
    - **Validates: Requirements 2.8, 4.2, 4.3, 4.4**

- [x] 5. ABAC resource/grants seed + generated CSV data seed (root repo)
  - [x] 5.1 `061-seed-finishing-materials-resource.xml` (resource `MATERIALS_FINISHING` with non-null `code`/`name_ru`/`name_pl`/`description_ru`/`description_pl`; `role_resources` + `role_resource_operations` grants: ADMIN CRUD, MANAGER CREATE/READ/UPDATE (NO DELETE), FOREMAN/WORKER/FINANCIER READ, CLIENT none, DELETE ADMIN-only; each insert guarded `sqlCheck expectedResult="0"`+`MARK_RAN`; register LAST) per `.kiro/steering/entity-creation-rules.md`
    - _Requirements: 5.1, 5.2, 5.3, 5.6, 5.7, 5.8_
  - [x] 5.2 CSV-to-SQL seed generator + `062-seed-finishing-materials.xml`: a repeatable generation step reads `docs/Materiały pakiety - Lista.csv`, maps each column to a field (`Cena detal / m2` has NO stored field — it is used only as the `retail_net` fallback below), parses comma-decimal prices (empty → NULL), applies the `retail_net` fallback (when a row's `Cena detal netto` is empty, use that row's `Cena detal / m2` value for `retail_net`; when both are empty, `retail_net` is NULL), normalizes the multi-value `Pakiet` cell (split on comma; strip whitespace/quotes/leading `+ `; map `Standart`→`Standard`; collapse duplicates), skips blank-`Kategoria` rows, and emits guarded inserts that resolve `category_id`/`material_id`/`type_id`/`producer_id`/`unit_id` by sub-select (inserting nothing when a required reference is absent) plus `finishing_material_packages` rows; the whole changeset is idempotent (`sqlCheck` on `finishing_materials`+`MARK_RAN`); register LAST. Document the generation approach in the changeset header (Requirement 6.8)
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9_
  - [x] 5.3 Write property test for the Pakiet parsing normalization
    - **Property 4: Pakiet cell normalization yields the correct package-token set** (`PakietParserPropertyTest`): generate `Pakiet` cells from `{Budget, Standard, Lux, Standart}` with random whitespace/quotes/`+ ` markers/repetition; assert the parsed set equals the normalized expected set (`Standart`→`Standard`, no empties, duplicate/order independent) (in-memory, ≥ 100 iterations)
    - **Validates: Requirements 6.4**

- [x] 6. Checkpoint — backend compiles, changelog registers 060–062 in order, controller passes PermissionAnnotationValidator
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 5.5, 5.7_

- [x] 7. FinishingMaterial admin page (foremen-frontend repo)
  - [x] 7.1 Scaffolding under `foremen-frontend/src/features/finishing-materials/` (types incl. `label`/`photoUrl`/reference ids, `finishing-materials-api.ts` via `buildFetchQuery` with FOR-04-01 reference filters, query/mutation hooks `finishingMaterialKeys` that invalidate + toast, zod schema: required `category`/`material`/`unit`, ≥ 1 package, `model`/`sku` ≤ 255, `link` ≤ 1024, prices in range)
    - _Requirements: 7.1, 7.6_
  - [x] 7.2 `FinishingMaterialsList` + `FinishingMaterialsPage` (`DataTable entityKey="finishing-materials" resource="MATERIALS_FINISHING"`, columns `category`/`material`/`type`/`producer`/`model`/`sku`/`retailNet`/`link` + optional `photo` thumbnail) + `FinishingMaterialFormSheet` (required `category`/`material` selectors, optional `type`/`producer` selectors, required multi-select `packages` ≥ 1, required `unit` selector, `model`/`sku`/`features` inputs, three price inputs, `link` input, optional reused `ImageUploadControl` `entityKind="finishing-materials"` `resource="MATERIALS_FINISHING"`, `active` toggle; no `code`/`name`) + `DeleteFinishingMaterialDialog` + lazy route + interim guard (`/materials/finishing` → `MATERIALS_FINISHING`/`READ`) + Warehouse nav item `{ path:'/materials/finishing', labelKey:'nav.finishingMaterials', requiredPermission:{ resource:'MATERIALS_FINISHING', operation:'READ' } }` in `navigation.ts` + i18n `finishingMaterials.*` + `nav.finishingMaterials` at PL+RU parity (no raw key); permission-gated create/edit/delete via `usePermission`
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9, 7.10, 7.11_
  - [x] 7.3 `FinishingMaterialsList.test.tsx` (columns render, permission gating, localized empty state) + photo-control UI test (reused `ImageUploadControl` appears on the form; a material with a photo renders its resolved image) + `finishingMaterials.*` and `nav.finishingMaterials` i18n parity tests (pl/ru, all non-empty)
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

- [x] 8. Checkpoint — frontend tsc + the new UI test files
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 9.1, 9.3_

- [x] 9. Backend example / integration / seed / smoke tests (root repo)
  - [x] 9.1 `FinishingMaterialControllerIntegrationTest` (Testcontainers CRUD with required `category`/`material`/`unit`, optional `type`/`producer`, required `packages` ≥ 1 incl. empty-set rejection; `model`/`sku` 255/256 and `link` 1024/1025 boundaries; derived `label` on read; reference filters `category.id`/`material.id`/`type.id`/`producer.id`/`unit.id`/`packages.id`)
    - _Requirements: 8.1, 8.2, 2.6, 2.7, 2.9, 2.10_
  - [x] 9.2 `FinishingMaterialPackageCascadeIntegrationTest` (example alongside Property 2: deleting a package removes join rows, deletes a zero-package material, leaves a multi-package material)
    - _Requirements: 8.3_
  - [x] 9.3 `FinishingMaterialPhotoIntegrationTest` + `FinishingMaterialImageReferenceLookupIntegrationTest` (photo object-key round-trip → resolved `photoUrl`; upload rejection reuse for non-image/over-size; `ImageReferenceLookup` counts a `finishing_materials.photo` reference so a referenced photo is not reclaimed and a detached one is)
    - _Requirements: 8.6, 8.9, 4.4, 4.5, 4.7_
  - [x] 9.4 `MaterialsFinishingResourceSeedIntegrationTest` (resource present; exact grant matrix incl. CLIENT none, MANAGER no-DELETE, DELETE ADMIN-only; re-run idempotency)
    - _Requirements: 8.7, 5.2, 5.3_
  - [x] 9.5 `FinishingMaterialsCsvSeedIntegrationTest` (generated seed populated `finishing_materials` with resolved references + parsed multi-value packages for representative rows; comma-decimal prices parsed; blank-category rows skipped; re-run inserts no duplicates)
    - _Requirements: 8.8, 6.1, 6.3, 6.5, 6.6, 6.7, 6.9_
  - [x] 9.6 `FinishingMaterialsAnnotationCoverageSmokeIT` (the fully annotated `FinishingMaterialController` starts cleanly under `PermissionAnnotationValidator`)
    - _Requirements: 8.10, 5.5_

- [x] 10. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping — FinishingMaterial CRUD + reference validation, reference filters, package cascade, photo upload/resolution, CSV seed verification, ABAC grant matrix; step-by-step scenarios; repeatability via a run-id generator + explicit teardown; a Регрессия group; an MD report template). This is an API-heavy spec plus one UI page: backend scenarios are API tests against the Dockerized app (`http://localhost:8080`, auth `/api/auth`, first ADMIN via `FOREMEN_ADMIN_CREATE`/`_EMAIL`/`_PASSWORD`) covering FinishingMaterial CRUD + reference validation + reference filters, `POST /api/images` upload + rejection with entityKind `finishing-materials`, the CSV-seeded catalog, and the ABAC grant matrix; the UI scenario is a browser-engine flow against `http://localhost:3000` for the `/materials/finishing` page + the photo upload control. Result artifacts are MD reports with tables. Document in Russian.
  - _Requirements: 1, 2, 3, 4, 5, 6, 7_

- [x] 11. Final checkpoint — affected backend classes + frontend tsc/UI tests
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 8.1, 8.7, 9.1_

## Notes

- Tasks marked with `*` are optional (tests) and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirements (granular sub-requirement clauses) for traceability.
- Changesets are numbered `060+` (current highest is `059`); all files registered LAST in `changelog.xml` in order `060`→`062`, each idempotent per `.kiro/steering/entity-creation-rules.md`.
- New Managed Entity Checklist (entity-creation-rules): `MATERIALS_FINISHING` is seeded via changeset `061` (resource row + `role_resources` + `role_resource_operations`), the ADMIN + role grants are seeded per the ABAC matrix, the concrete `FinishingMaterialController` carries `@PermissionResource("MATERIALS_FINISHING")` matching the seeded `code` with `@PermissionOperation` on inherited CRUD handlers, and the service does NOT implement `getProjectIdPath()` — `MATERIALS_FINISHING` is a GLOBAL admin resource (not project-scoped).
- Image reuse: no new image-storage service — the photo consumes the shared FOR-04-17 `ImageStorage` / `POST /api/images` seam; the only additive change is registering `finishing_materials.photo` in `ImageReferenceLookup.IMAGE_COLUMNS` (task 4.1) so orphan cleanup + reconciliation cover it.
- No per-unit price field and no computed price: this branch keeps only the three stored prices (`purchasePrice`/`retailGross`/`retailNet`); there is no per-m²/per-unit price field, no `PriceRangeResolver`, and no `/price-ranges` endpoint (this differs from FOR-04-17).
- PBT applies to four correctness properties: Property 1 (write validation, task 2.4), Property 2 (package cascade, 3.2), Property 3 (photo CDN URL, 4.2), Property 4 (Pakiet parsing, 5.3). Each property is its own sub-task placed close to its implementation and annotated with its property number + validated requirement clauses.
- Two-repo git structure: backend/spec/infra tasks (1–6, 9–11) commit from the ROOT repo; frontend tasks (7–8) commit from inside `foremen-frontend/`. A change spanning both needs one commit per repo — verify `git status` in both.
- Run ONLY the affected classes/files per the workspace test standard (`--tests` filters for backend, specific files for frontend); never the full suite unless the user explicitly asks (~20 min).
- The full Polish-market data seed IS delivered here (581 rows), GENERATED from the CSV (Requirement 6.8) — this is the branch that HAS a CSV, unlike FOR-04-17.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "4.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["2.2", "3.1"] },
    { "id": 3, "tasks": ["2.3", "4.2", "5.1"] },
    { "id": 4, "tasks": ["2.4", "3.2", "5.2", "7.1"] },
    { "id": 5, "tasks": ["5.3", "7.2"] },
    { "id": 6, "tasks": ["7.3", "9.1", "9.2", "9.3", "9.4", "9.5", "9.6"] }
  ]
}
```
