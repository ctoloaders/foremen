# Design — FOR-04-09: Material Categories dictionary

## Overview

`MaterialCategory` is a straight mirror of the FOR-04-07 `DeliveryCategory` stack
(plain dict: `code`, `nameRU`, `namePL`, `active`; i18n only on `name`). The ONLY
difference is the ABAC grant set: FINANCIER GETS a READ grant here (matching
MEASUREMENT_UNITS/WORK_CATEGORIES); CLIENT still gets none. Backend CRUD vertical
+ frontend admin page; menu entry is FOR-04-15.

## Backend (under `com.foremen`)
- `dao/model/MaterialCategoryEntity` @Table("material_categories") extends BaseEntity: `code`(unique NOT NULL), `nameRU`(name_ru), `namePL`(name_pl), `boolean active=true`.
- `dao/MaterialCategoryDao extends AdminDao<MaterialCategoryEntity, Long>`.
- `service/model/MaterialCategoryServiceModel` (@Data) `{id, code, name, active}`; `MaterialCategoryServiceExtendedModel` (record) `{id, code, nameRU, namePL, active}`.
- `service/model/mapper/MaterialCategoryServiceMapper`: `getI18nSupportedProperties()=Set.of("name")`; `updateFields` ignore id+code.
- `service/MaterialCategoryService implements AdminService<...>`.
- controller model records: `MaterialCategoryDtoModel {id, code, name, active}`; `MaterialCategoryDtoExtendedModel {id, code, nameRU, namePL, active}`; `MaterialCategoryCreateRequest {@NotBlank code, @NotBlank nameRU, @NotBlank namePL, Boolean active}`; `MaterialCategoryUpdateRequest {@NotBlank nameRU, @NotBlank namePL, Boolean active}` (no code); `MaterialCategoryCreateResponse`/`MaterialCategoryUpdateResponse {id, code, nameRU, namePL, active}`.
- `controller/model/mapper/MaterialCategoryControllerMapper`: ignore id/create, id+code/update, default active→true.
- `controller/MaterialCategoryController` @RequestMapping("/api/material-categories") @PermissionResource("MATERIAL_CATEGORIES").

## Liquibase (registered LAST after 031)
- `032-create-material-categories.xml` — table `material_categories` (id BIGSERIAL PK; code VARCHAR(100) NOT NULL UNIQUE uk_material_categories_code; name_ru/name_pl VARCHAR(255) NOT NULL; active BOOLEAN NOT NULL default true; 4 audit columns). `tableExists` + `MARK_RAN`.
- `033-seed-material-categories-resource.xml` — resource `MATERIAL_CATEGORIES` (RU "Категории материалов" / PL "Kategorie materiałów", description RU "Справочник категорий материалов" / PL "Katalog kategorii materiałów"); grants ADMIN CRUD + MANAGER/FOREMAN/WORKER/**FINANCIER** READ (idempotent per role); **CLIENT omitted** (comment noting deny-by-default); defaults seed guarded by `SELECT COUNT(*) FROM material_categories`:

| code | nameRU | namePL |
|------|--------|--------|
| construction | Строительные | Construction |
| finishing | Отделочные | Finishing |

## Frontend — `foremen-frontend/src/features/material-categories/`
Mirror the Delivery Categories page exactly (no extra fields):
- types (`MaterialCategoryDto {id, code, name, active}`, `MaterialCategoryExtendedDto {id, code, nameRU, namePL, active}`, create/update, FormMode, PaginatedResponse), api (`material-categories-api.ts` via shared buildFetchQuery → `/api/material-categories`), query/mutation hooks (`materialCategoryKeys`), zod schema (code non-blank + lowercase pattern `^[a-z0-9_]{1,50}$`, nameRU/namePL min/max; update omits code).
- `MaterialCategoriesPage` + `MaterialCategoriesList` (columns code/name/active-badge; `usePermission('MATERIAL_CATEGORIES', …)`; fetchFn adapter; DataTable `entityKey="material-categories" resource="MATERIAL_CATEGORIES"`) + `MaterialCategoryFormSheet` (code read-only on edit) + `DeleteMaterialCategoryDialog` + local `ActiveBadge`.
- routing: lazy `MaterialCategoriesPage` + route `{ path: 'material-categories', ... }`; interim `extra['/material-categories'] = { resource:'MATERIAL_CATEGORIES', operation:'READ' }`.
- i18n `materialCategories.*` (PL+RU parity): pageTitle; search.placeholder; actions.{create,retry}; list.empty; table.{code,name,active,actions}; badge.{active,inactive}; form.{titleCreate,titleEdit,descriptionCreate,descriptionEdit,code,nameRU,namePL,active,submitCreate,submitEdit}; delete.{title,description}; toast.{createSuccess,updateSuccess,deleteSuccess}; errors.{loadFailed,network}; validation.{codeRequired,codePattern,nameMin,nameMax}. Plus `nav.materialCategories`.

## Testing Strategy
PBT n/a. Backend: `MaterialCategoryControllerIntegrationTest` (CRUD, i18n, code immutable) + `MaterialCategoriesResourceSeedIntegrationTest` (resource; grants ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none; two default codes; idempotency). Frontend: `MaterialCategoriesList.test.tsx` + i18n parity. Run only affected classes/files.
