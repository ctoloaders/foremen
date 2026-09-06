# Design — FOR-04-06: Work Categories dictionary

## Overview

`WorkCategory` mirrors the FOR-04-02 `MeasurementUnit` stack with one extra
non-i18n field `orderNo` (Integer). i18n only on `name`. Backend CRUD vertical +
frontend admin page; menu entry is FOR-04-15. The 13-category seed (Polish names
authoritative) feeds the work catalog (FOR-04-11).

## Backend (under `com.foremen`)
- `dao/model/WorkCategoryEntity` @Table("work_categories") extends BaseEntity: `code`(unique NOT NULL), `orderNo`(Integer, `@Column(name="order_no", nullable=false)`), `nameRU`(name_ru), `namePL`(name_pl), `boolean active=true`.
- `dao/WorkCategoryDao extends AdminDao<WorkCategoryEntity, Long>`.
- service models: `WorkCategoryServiceModel` (@Data) `{id, code, orderNo, name, active}`; `WorkCategoryServiceExtendedModel` (record) `{id, code, orderNo, nameRU, namePL, active}`.
- `WorkCategoryServiceMapper`: `getI18nSupportedProperties()=Set.of("name")`; updateFields ignore id+code.
- `WorkCategoryService implements AdminService<...>`.
- controller model records: `WorkCategoryDtoModel {id, code, orderNo, name, active}`; `WorkCategoryDtoExtendedModel {id, code, orderNo, nameRU, namePL, active}`; `WorkCategoryCreateRequest {@NotBlank code, @NotNull Integer orderNo, @NotBlank nameRU, @NotBlank namePL, Boolean active}`; `WorkCategoryUpdateRequest {@NotNull orderNo, @NotBlank nameRU, @NotBlank namePL, Boolean active}` (no code); `WorkCategoryCreateResponse`/`WorkCategoryUpdateResponse {id, code, orderNo, nameRU, namePL, active}`.
- `WorkCategoryControllerMapper`: ignore id/create, id+code/update, default active→true.
- `WorkCategoryController` @RequestMapping("/api/work-categories") @PermissionResource("WORK_CATEGORIES").

## Liquibase (registered LAST after 025)
- `026-create-work-categories.xml` — table `work_categories` (id BIGSERIAL PK; code VARCHAR(100) NOT NULL UNIQUE uk_work_categories_code; order_no INT NOT NULL; name_ru/name_pl VARCHAR(255) NOT NULL; active BOOLEAN NOT NULL default true; 4 audit columns). `tableExists` + `MARK_RAN`.
- `027-seed-work-categories-resource.xml` — resource `WORK_CATEGORIES` (RU "Категории работ" / PL "Kategorie prac"); ADMIN CRUD + MANAGER/FOREMAN/WORKER/FINANCIER READ (idempotent per role) + CLIENT omitted; defaults seed guarded by `SELECT COUNT(*) FROM work_categories` — the 13 rows from requirements.md (orderNo 1..13, codes PRELIMINARY…OTHER, Polish names authoritative), active=true.

## Frontend — `foremen-frontend/src/features/work-categories/`
Mirror the Measurement Units page; add `orderNo` (number) column + form field.
- types (`WorkCategoryDto {id, code, orderNo, name, active}`, `WorkCategoryExtendedDto {id, code, orderNo, nameRU, namePL, active}`, create `{code, orderNo, nameRU, namePL, active?}`, update `{orderNo, nameRU, namePL, active?}`, FormMode, PaginatedResponse), api (`work-categories-api.ts` via shared buildFetchQuery → `/api/work-categories`), query/mutation hooks (`workCategoryKeys`), zod schema (code non-blank + pattern, orderNo number ≥ 0, nameRU/namePL min/max; update omits code).
- `WorkCategoriesPage` + `WorkCategoriesList` (columns orderNo(number,80px), code(string,140px), name(string,240px), active(badge,100px); `usePermission('WORK_CATEGORIES', …)`; fetchFn adapter; DataTable `entityKey="work-categories" resource="WORK_CATEGORIES"` defaultSort by orderNo asc) + `WorkCategoryFormSheet` (code read-only on edit; orderNo numeric input) + `DeleteWorkCategoryDialog` + local `ActiveBadge`.
- routing: lazy `WorkCategoriesPage` + route `{ path: 'work-categories', ... }`; interim `extra['/work-categories'] = { resource:'WORK_CATEGORIES', operation:'READ' }`.
- i18n `workCategories.*` (PL+RU parity): pageTitle; search.placeholder; actions.{create,retry}; list.empty; table.{orderNo,code,name,active,actions}; badge.{active,inactive}; form.{titleCreate,titleEdit,descriptionCreate,descriptionEdit,code,orderNo,nameRU,namePL,active,submitCreate,submitEdit}; delete.{title,description}; toast.{createSuccess,updateSuccess,deleteSuccess}; errors.{loadFailed,network}; validation.{codeRequired,codePattern,orderNoRequired,nameMin,nameMax}. Plus `nav.workCategories`.

## Testing Strategy
PBT n/a. Backend: `WorkCategoryControllerIntegrationTest` (CRUD; i18n; code immutable; orderNo update) + `WorkCategoriesResourceSeedIntegrationTest` (resource; grants ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none; 13 default codes present, COUNT==13, orderNo 1..13; idempotency). Frontend: `WorkCategoriesList.test.tsx` (rows render orderNo/code/name/active) + i18n parity. Run only affected classes/files.
