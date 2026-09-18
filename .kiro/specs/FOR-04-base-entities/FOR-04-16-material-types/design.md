# Design — FOR-04-16: Material Dictionaries

## Overview

FOR-04-16 delivers ALL material reference dictionaries derived from
`docs/Materiały pakiety - Lista.csv`, built in PARALLEL and merged into one spec.
Three of them are NEW full CRUD verticals — each a straight mirror of the
FOR-04-09 `MaterialCategory` stack (plain dict: `code`, `nameRU`, `namePL`,
`active`; i18n only on `name`; ABAC with FINANCIER READ, CLIENT none). The fourth
(`MATERIAL_CATEGORIES`) already exists (FOR-04-09) and is only RE-SEEDED.

| # | Dictionary | Resource | Table | Path | CSV column | Status |
|---|------------|----------|-------|------|-----------|--------|
| 1 | MaterialType | `MATERIAL_TYPES` | `material_types` | `/api/material-types` | `Typ` | NEW vertical |
| 2 | MaterialProducer | `MATERIAL_PRODUCERS` | `material_producers` | `/api/material-producers` | `Producent` | NEW vertical |
| 3 | Material | `MATERIALS` | `materials` | `/api/materials` | `Materiał` | NEW vertical |
| 4 | MaterialCategory | `MATERIAL_CATEGORIES` | `material_categories` | `/api/material-categories` | `Kategoria` | RE-SEED only (reuse FOR-04-09) |

All four are GLOBAL admin resources and are NOT project-scoped, so their services
do NOT implement `ProjectScopedService`. Changesets are numbered `046+` (current
highest is `045`). Each NEW dictionary adds a backend CRUD vertical + a frontend
admin page; the menu entries are delivered by FOR-04-15. `MATERIAL_CATEGORIES`
reuses its existing entity/controller/service/UI unchanged.

`Materiał` (this `MATERIALS` dictionary) is a flat named-material dictionary here.
In the later finishing-materials spec (FOR-04-18), `FinishingMaterial.material` is
an FK to this `MATERIALS` dictionary and `FinishingMaterial.category` is an FK to
the existing `MATERIAL_CATEGORIES` dictionary — but `MATERIALS` itself carries no
FK to categories in this spec.

## Architecture

Each new dictionary is an identical vertical (shown for MaterialType; producers and
materials are structurally identical with their own names/resources):

```
Frontend (foremen-frontend/src/features/<feature>)
  <Xxx>Page ─ DataTable(entityKey="<kebab>", resource="<RESOURCE>")
        │ buildFetchQuery
        ▼
Backend  GET/POST/PUT/DELETE /api/<kebab>
  <Xxx>Controller  @PermissionResource("<RESOURCE>")   (implements AdminController)
        ▼
  <Xxx>Service (implements AdminService, NOT ProjectScopedService)
        ▼
  <Xxx>Dao (extends AdminDao) ─▶ <table> (Liquibase 046+)
```

| feature | kebab / entityKey | resource | table |
|---------|-------------------|----------|-------|
| `material-types` | `material-types` | `MATERIAL_TYPES` | `material_types` |
| `material-producers` | `material-producers` | `MATERIAL_PRODUCERS` | `material_producers` |
| `materials` | `materials` | `MATERIALS` | `materials` |

The `PermissionInterceptor` enforces the `(RESOURCE, operation)` pair from the
class-level `@PermissionResource` + the `@PermissionOperation` already on the
inherited `AdminController` CRUD default methods (per `entity-creation-rules.md`).
`MATERIAL_CATEGORIES` needs no controller change — only a data re-seed migration.

## Components and Interfaces

The three new verticals share the identical shape below. Replace `MaterialType` /
`material_types` / `MATERIAL_TYPES` / `/api/material-types` with the corresponding
names for `MaterialProducer` / `material_producers` / `MATERIAL_PRODUCERS` /
`/api/material-producers` and `Material` / `materials` / `MATERIALS` / `/api/materials`.

### Backend (under `com.foremen`) — per new dictionary

- `dao/model/<Xxx>Entity` `@Table("<table>")` extends `BaseEntity`: `code` (unique NOT NULL), `nameRU` (`name_ru`), `namePL` (`name_pl`), `boolean active = true`.
- `dao/<Xxx>Dao extends AdminDao<<Xxx>Entity, Long>`.
- `service/model/<Xxx>ServiceModel` (@Data) `{id, code, name, active}`; `<Xxx>ServiceExtendedModel` (record) `{id, code, nameRU, namePL, active}`.
- `service/model/mapper/<Xxx>ServiceMapper`: `getI18nSupportedProperties() = Set.of("name")`; `updateFields` ignore `id` + `code`.
- `service/<Xxx>Service implements AdminService<...>` (does NOT implement `ProjectScopedService`).
- controller model records: `<Xxx>DtoModel {id, code, name, active}`; `<Xxx>DtoExtendedModel {id, code, nameRU, namePL, active}`; `<Xxx>CreateRequest {@NotBlank code, @NotBlank nameRU, @NotBlank namePL, Boolean active}`; `<Xxx>UpdateRequest {@NotBlank nameRU, @NotBlank namePL, Boolean active}` (no `code`); `<Xxx>CreateResponse`/`<Xxx>UpdateResponse {id, code, nameRU, namePL, active}`.
- `controller/model/mapper/<Xxx>ControllerMapper`: ignore `id`/create, `id`+`code`/update, default `active` → `true`.
- `controller/<Xxx>Controller` `@RequestMapping("/api/<kebab>")` `@PermissionResource("<RESOURCE>")` implements `AdminController<...>`.

Concrete class names:
- `MaterialTypeEntity` / `MaterialTypeDao` / `MaterialTypeService` / `MaterialTypeController` / `MaterialTypeServiceMapper` / `MaterialTypeControllerMapper` / model records `MaterialType*`.
- `MaterialProducerEntity` / `MaterialProducerDao` / `MaterialProducerService` / `MaterialProducerController` / `MaterialProducerServiceMapper` / `MaterialProducerControllerMapper` / model records `MaterialProducer*`.
- `MaterialEntity` / `MaterialDao` / `MaterialService` / `MaterialController` / `MaterialServiceMapper` / `MaterialControllerMapper` / model records `Material*`.

### MaterialCategory (reuse — no backend classes added)

No new backend classes. FOR-04-09 already provides `MaterialCategoryEntity`,
`MaterialCategoryDao`, `MaterialCategoryService`, `MaterialCategoryController`
(`@PermissionResource("MATERIAL_CATEGORIES")` at `/api/material-categories`) and
the `material_categories` table + `MATERIAL_CATEGORIES` resource/grants (changesets
`032`/`033`). This spec only adds a data re-seed changeset.

## Data Models

All three new tables share the identical schema (name differs per table):

| Column | Type | Constraints |
|--------|------|-------------|
| id | BIGSERIAL | PK |
| code | VARCHAR(100) | NOT NULL, UNIQUE (`uk_<table>_code`) |
| name_ru | VARCHAR(255) | NOT NULL |
| name_pl | VARCHAR(255) | NOT NULL |
| active | BOOLEAN | NOT NULL default `true` |
| created_at / created_by / updated_at / updated_by | audit | per `BaseEntity` |

Unique constraint names: `uk_material_types_code`, `uk_material_producers_code`,
`uk_materials_code`. Localized `name` is derived (not stored): `ru` → `name_ru`,
otherwise `name_pl`.

`material_categories` already exists (FOR-04-09) with the same schema
(`uk_material_categories_code`); this spec does not alter its structure.

## Liquibase (registered LAST after `045`)

Changeset numbering plan (all registered last, in this order, in
`foremen-backend/database_files/changelog.xml`):

| # | File | Purpose |
|---|------|---------|
| 046 | `046-create-material-types.xml` | table `material_types` |
| 047 | `047-seed-material-types-resource.xml` | `MATERIAL_TYPES` resource + grants + 39 `Typ` rows |
| 048 | `048-create-material-producers.xml` | table `material_producers` |
| 049 | `049-seed-material-producers-resource.xml` | `MATERIAL_PRODUCERS` resource + grants + 32 `Producent` rows |
| 050 | `050-create-materials.xml` | table `materials` |
| 051 | `051-seed-materials-resource.xml` | `MATERIALS` resource + grants + 11 `Materiał` rows |
| 052 | `052-reseed-material-categories.xml` | delete `construction`/`finishing` + insert 5 `Kategoria` rows |

Idempotency rules (per `entity-creation-rules.md`):
- **Table changesets** (`046`, `048`, `050`): `tableExists` precondition + `MARK_RAN`; each with `id` BIGSERIAL PK, `code` VARCHAR(100) NOT NULL UNIQUE, `name_ru`/`name_pl` VARCHAR(255) NOT NULL, `active` BOOLEAN NOT NULL default `true`, 4 audit columns.
- **Resource + grant changesets**: resource row + grants ADMIN CRUD + MANAGER/FOREMAN/WORKER/**FINANCIER** READ (idempotent per role); **CLIENT omitted** (comment noting deny-by-default). Each insert guarded by `sqlCheck expectedResult="0"` + `onFail="MARK_RAN"`.
- **Seed rows**: each row guarded by `SELECT COUNT(*) FROM <table> WHERE code = '<code>'` (`expectedResult="0"` + `MARK_RAN`) so re-runs insert nothing.

### 047 — MaterialType seed rows (39 distinct `Typ`)

Resource `MATERIAL_TYPES` (RU "Типы материалов" / PL "Typy materiałów"; description
RU "Справочник типов материалов" / PL "Katalog typów materiałów"). `namePL` = the
CSV `Typ`; `code` = normalized slug; `nameRU` = translation; `active = true`.

| code | namePL | nameRU |
|------|--------|--------|
| laminat | Laminat | Ламинат |
| winyl | Winyl | Винил |
| winyl_prosty | Winyl prosty | Винил прямой |
| winyl_jodelka | Winyl jódełka | Винил ёлочка |
| deska_prosta | Deska prosta | Доска прямая |
| deska_jodelka | Deska jódełka | Доска ёлочка |
| podklad | Podkład | Подложка |
| podklad_klej_grunt | Podkład (klej, grunt) | Подложка (клей, грунт) |
| styrodur | Styrodur | Стиродур (плинтус) |
| listwy_przypodlogowe | Listwy przypodłogowe | Плинтусы напольные |
| brodzik_kabina | Brodzik + kabina | Поддон + кабина |
| brodzik_prysznicowy | Brodzik prysznicowy | Душевой поддон |
| wanna | Wanna | Ванна |
| syfon_od_wanny | Syfon od wanny | Сифон для ванны |
| parawan_nawannowy | Parawan nawannowy | Шторка на ванну |
| sciana_kabina_prysznicowa | Ściana / kabina prysznicowa | Стенка / душевая кабина |
| odplyw_liniowy | Odpływ liniowy | Линейный трап |
| stelaz_podtynkowy | Stelaż podtynkowy | Инсталляция скрытого монтажа |
| miska_wc | Miska WC | Унитаз (чаша) |
| przycisk | Przycisk | Кнопка смыва |
| zestaw_prysznicowy | Zestaw prysznicowy | Душевой набор |
| bateria_wannowo_prysznicowa_z_kompletem | Bateria wannowo-prysznicowa z kompletem natryskowym | Смеситель ванна-душ с душевым комплектом |
| baterie_prysznicowe_wannowe | Baterie prysznicowe / wannowe | Смесители душевые/ванновые |
| bateria_umywalkowa | Bateria umywalkowa | Смеситель для умывальника |
| umywalka_scienna | Umywalka ścienna | Умывальник настенный |
| umywalka_nablatowa | Umywalka nablatowa | Умывальник накладной |
| umywalka_wpuszczana | Umywalka wpuszczana | Умывальник врезной |
| umywalka_podblatowa | Umywalka podblatowa | Умывальник подстольный |
| umywalka_scienna_nablatowa | Umywalka ścienna-nablatowa | Умывальник настенный-накладной |
| przylgowe | Przylgowe | Дверь притворная |
| bezprzylgowe | Bezprzylgowe | Дверь скрытого притвора |
| ukryte | Ukryte | Скрытая (дверь) |
| oscieznica | Ośczieżnica | Дверная коробка |
| klamki_drzwi | Klamki drzwi | Дверные ручки |
| rozety_drzwi | Rozety drzwi | Дверные розетки |
| plytka_scienna | płytka ścienna | Плитка настенная |
| plytka_podlogowa | płytka podłogowa | Плитка напольная |
| plytka_scienno_podlogowa | płytka ścienno - podłogowa | Плитка настенно-напольная |
| akcesoria | Akcesoria | Аксессуары |

### 049 — MaterialProducer seed rows (32 distinct real `Producent`)

Resource `MATERIAL_PRODUCERS` (RU "Производители материалов" / PL "Producenci
materiałów"; description RU "Справочник производителей материалов" / PL "Katalog
producentów materiałów"). These are proper brand names, so `namePL` = the brand
as-is and `nameRU` = the same brand kept in Latin (`nameRU = namePL`); `code` =
normalized slug; `active = true`. The bogus value `Podkład` is EXCLUDED (a
data-entry error where `Producent` was filled with a `Typ`).

| code | namePL | nameRU |
|------|--------|--------|
| afirmax | Afirmax | Afirmax |
| arbiton | Arbiton | Arbiton |
| barlinek | Barlinek | Barlinek |
| cersanit | Cersanit | Cersanit |
| dre | DRE | DRE |
| deante | Deante | Deante |
| domino | Domino | Domino |
| eclisse | Eclisse | Eclisse |
| egger | Egger | Egger |
| excellent | Excellent | Excellent |
| geberit | Geberit | Geberit |
| grohe | Grohe | Grohe |
| hagser | Hagser | Hagser |
| hansgrohe | Hansgrohe | Hansgrohe |
| infinity | Infinity | Infinity |
| marazzi | Marazzi | Marazzi |
| metamorphose | Metamorphose | Metamorphose |
| oltens | Oltens | Oltens |
| omnires | Omnires | Omnires |
| paradyz | Paradyż | Paradyż |
| pol_skone | Pol-Skone | Pol-Skone |
| porcelanosa | Porcelanosa | Porcelanosa |
| porta | Porta | Porta |
| radaway | Radaway | Radaway |
| ravak | Ravak | Ravak |
| roca | Roca | Roca |
| salag | Salag | Salag |
| tubadzin | Tubądzin | Tubądzin |
| tupai | Tupai | Tupai |
| vox | VOX | VOX |
| viega | Viega | Viega |
| villeroy_boch | Villeroy & Boch | Villeroy & Boch |

### 051 — Material seed rows (11 distinct `Materiał`)

Resource `MATERIALS` (RU "Материалы" / PL "Materiały"; description RU "Справочник
именованных материалов" / PL "Katalog nazwanych materiałów"). `namePL` = the CSV
`Materiał`; `code` = normalized slug; `nameRU` = translation; `active = true`.

| code | namePL | nameRU |
|------|--------|--------|
| podloga | Podłoga | Пол |
| listwy_przypodlogowe | Listwy przypodłogowe | Плинтусы напольные |
| wanna_prysznic | Wanna / prysznic | Ванна / душ |
| zestaw_podtynkowy_wc | Zestaw podtnykowy WC | Инсталляция WC (комплект) |
| bateria_wannowa_prysznicowa | Bateria wannowa / prysznicowa | Смеситель ванна/душ |
| bateria_umywalkowa | Bateria umywalkowa | Смеситель для умывальника |
| umywalki | Umywalki | Умывальники |
| drzwi | Drzwi | Двери |
| klamki_rozety | Klamki, rozety | Ручки, розетки |
| plytki | Płytki | Плитка |
| akcesoria_srodki | Akcesoria / środki | Аксессуары / средства |

### 052 — MaterialCategory RE-SEED (delete obsolete + insert 5)

This changeset touches ONLY the `material_categories` dictionary rows. It does NOT
touch the `MATERIAL_CATEGORIES` resource row or its ABAC grants (seeded in `033`).

Two logical steps in one changeset file:

1. **Delete obsolete rows** — `DELETE FROM material_categories WHERE code IN
   ('construction','finishing')`. Guarded so the delete is safe/idempotent: it runs
   only while no dependent FK rows reference categories yet. A comment records that
   no `FinishingMaterial` rows exist (FOR-04-18 not yet delivered), so the delete is
   safe. Idempotency: the `DELETE` is naturally a no-op on re-run (rows already
   gone); optionally guarded by a precondition that the codes still exist
   (`onFail="MARK_RAN"`) to keep the changeset from re-marking.
2. **Insert the 5 CSV categories** — each guarded by `SELECT COUNT(*) FROM
   material_categories WHERE code = '<code>'` (`expectedResult="0"` + `MARK_RAN`) so
   re-runs insert nothing.

| code | namePL | nameRU |
|------|--------|--------|
| drzwi | Drzwi | Двери |
| listwy_przypodlogowe | Listwy przypodłogowe | Плинтусы напольные |
| podloga | Podłoga | Пол |
| plytki | Płytki | Плитка |
| sanitariat | Sanitariat | Сантехника |

## ABAC matrix (three new resources)

Identical to `MATERIAL_CATEGORIES`:

| Resource | ADMIN | MANAGER | FOREMAN | WORKER | FINANCIER | CLIENT |
|----------|:-----:|:-------:|:-------:|:------:|:---------:|:------:|
| MATERIAL_TYPES | CRUD | R | R | R | R | — |
| MATERIAL_PRODUCERS | CRUD | R | R | R | R | — |
| MATERIALS | CRUD | R | R | R | R | — |

`MATERIAL_CATEGORIES` grants are unchanged (already ADMIN CRUD / MANAGER,FOREMAN,
WORKER,FINANCIER READ / CLIENT none from `033`).

## Frontend Components — three new features

`foremen-frontend/src/features/material-types/`,
`foremen-frontend/src/features/material-producers/`,
`foremen-frontend/src/features/materials/`. Each mirrors the Material Categories
page exactly (no extra fields). Shown for MaterialType; the other two are identical
with their own names/resources/namespaces:

- types (`MaterialTypeDto {id, code, name, active}`, `MaterialTypeExtendedDto {id, code, nameRU, namePL, active}`, create/update, `FormMode`, `PaginatedResponse`), api (`material-types-api.ts` via shared `buildFetchQuery` → `/api/material-types`), query/mutation hooks (`materialTypeKeys`), zod schema (`code` non-blank + lowercase pattern `^[a-z0-9_]{1,100}$`, `nameRU`/`namePL` min/max; update omits `code`).
- `MaterialTypesPage` + `MaterialTypesList` (columns `code`/`name`/`active`-badge; `usePermission('MATERIAL_TYPES', …)`; fetchFn adapter; DataTable `entityKey="material-types" resource="MATERIAL_TYPES"`) + `MaterialTypeFormSheet` (`code` read-only on edit) + `DeleteMaterialTypeDialog` + local `ActiveBadge`.
- routing: lazy `MaterialTypesPage` + route `{ path: 'material-types', ... }`; interim `extra['/material-types'] = { resource: 'MATERIAL_TYPES', operation: 'READ' }`.
- i18n `materialTypes.*` (PL+RU parity): `pageTitle`; `search.placeholder`; `actions.{create,retry}`; `list.empty`; `table.{code,name,active,actions}`; `badge.{active,inactive}`; `form.{titleCreate,titleEdit,descriptionCreate,descriptionEdit,code,nameRU,namePL,active,submitCreate,submitEdit}`; `delete.{title,description}`; `toast.{createSuccess,updateSuccess,deleteSuccess}`; `errors.{loadFailed,network}`; `validation.{codeRequired,codePattern,nameMin,nameMax}`. Plus `nav.materialTypes`.

Per feature the namespace / feature-dir / resource / path / entityKey / nav key:

| feature dir | namespace | resource | path / entityKey | nav key |
|-------------|-----------|----------|------------------|---------|
| `material-types` | `materialTypes.*` | `MATERIAL_TYPES` | `material-types` | `nav.materialTypes` |
| `material-producers` | `materialProducers.*` | `MATERIAL_PRODUCERS` | `material-producers` | `nav.materialProducers` |
| `materials` | `materials.*` | `MATERIALS` | `materials` | `nav.materials` |

`MATERIAL_CATEGORIES` reuses its existing `foremen-frontend/src/features/material-categories/` page unchanged.

## Error Handling

- Create with blank `code`/`nameRU`/`namePL` → `400` (bean validation); UI shows inline localized `validation.*` errors and blocks submit.
- Update attempting to change `code` → ignored (mapper ignores `id`+`code`); response keeps original `code`.
- Duplicate `code` on create → DB unique violation surfaces as a `4xx`; UI shows a toast from `errors.*`.
- Unauthenticated request → `401`; authenticated-but-unauthorized (e.g. CLIENT) → `403`.
- List load failure / network error → `errors.loadFailed` / `errors.network` with a retry action.
- MaterialCategory re-seed: if the two obsolete codes are already absent, the delete is a no-op and the changeset stays consistent; if any of the 5 new codes already exist, the `sqlCheck` guard skips the insert (no duplicates).

## Testing Strategy

PBT n/a — these are plain dictionary CRUD verticals plus a data re-seed, with no
universal input→output property (behavior does not vary meaningfully with input;
the logic is generic CRUD + locale column resolution already covered by
example/edge tests). Use example-based integration tests and frontend
component/parity tests.

- Backend, per NEW dictionary:
  - `<Xxx>ControllerIntegrationTest` (Testcontainers CRUD; i18n `name` for `ru`/`pl`/no-header fallback; `code` immutable on update; create validation `400`).
  - `<Xxx>ResourceSeedIntegrationTest` (resource present; grants ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none; seeded codes present — e.g. `laminat`/`plytka_scienna` for types, `egger`/`villeroy_boch` for producers, `podloga`/`plytki` for materials; re-run idempotency — no duplicates).
- Backend, MaterialCategory re-seed: `MaterialCategoriesReseedIntegrationTest` asserting `construction`/`finishing` are ABSENT, the 5 new codes (`drzwi`, `listwy_przypodlogowe`, `podloga`, `plytki`, `sanitariat`) are PRESENT with correct `nameRU`/`namePL`, and the re-seed is idempotent (re-run adds/removes nothing).
- Frontend, per NEW page: `<Xxx>List.test.tsx` (rows render `code`/`name`/`active`; permission-gated controls; empty state) + `<namespace>` i18n parity test across `pl.json`/`ru.json`.
- Per the workspace test standard, run ONLY the affected classes/files (`--tests` filters), never the full suite.
