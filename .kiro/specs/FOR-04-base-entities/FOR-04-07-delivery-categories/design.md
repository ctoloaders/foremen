# Design — FOR-04-07: Delivery Categories dictionary

## Overview

`DeliveryCategory` is a straight mirror of the FOR-04-05 `RoomType` stack (fields
`code`, `nameRU`, `namePL`, `active`; i18n only on `name`; identical ABAC grant
set — FINANCIER and CLIENT get NO grant). Backend CRUD vertical + frontend admin
page; menu entry is FOR-04-15.

## Backend (under `com.foremen`)
- `dao/model/DeliveryCategoryEntity` @Table("delivery_categories") extends BaseEntity: `code`(unique NOT NULL), `nameRU`(name_ru), `namePL`(name_pl), `boolean active=true`.
- `dao/DeliveryCategoryDao extends AdminDao<DeliveryCategoryEntity, Long>`.
- `service/model/DeliveryCategoryServiceModel` (@Data) `{id, code, name, active}`; `DeliveryCategoryServiceExtendedModel` (record) `{id, code, nameRU, namePL, active}`.
- `service/model/mapper/DeliveryCategoryServiceMapper`: `getI18nSupportedProperties()=Set.of("name")`; `updateFields` ignore id+code.
- `service/DeliveryCategoryService implements AdminService<...>`.
- controller model records: `DeliveryCategoryDtoModel {id, code, name, active}`; `DeliveryCategoryDtoExtendedModel {id, code, nameRU, namePL, active}`; `DeliveryCategoryCreateRequest {@NotBlank code, @NotBlank nameRU, @NotBlank namePL, Boolean active}`; `DeliveryCategoryUpdateRequest {@NotBlank nameRU, @NotBlank namePL, Boolean active}` (no code); `DeliveryCategoryCreateResponse`/`DeliveryCategoryUpdateResponse {id, code, nameRU, namePL, active}`.
- `controller/model/mapper/DeliveryCategoryControllerMapper`: ignore id/create, id+code/update, default active→true.
- `controller/DeliveryCategoryController` @RequestMapping("/api/delivery-categories") @PermissionResource("DELIVERY_CATEGORIES").

## Liquibase (registered LAST after 027)
- `028-create-delivery-categories.xml` — table `delivery_categories` (id BIGSERIAL PK; code VARCHAR(100) NOT NULL UNIQUE uk_delivery_categories_code; name_ru/name_pl VARCHAR(255) NOT NULL; active BOOLEAN NOT NULL default true; 4 audit columns). `tableExists` + `MARK_RAN`.
- `029-seed-delivery-categories-resource.xml` — resource `DELIVERY_CATEGORIES` (RU "Категории доставок" / PL "Kategorie dostaw"); grants ADMIN CRUD + MANAGER/FOREMAN/WORKER READ (idempotent per role); **FINANCIER and CLIENT omitted** (comment noting deny-by-default); defaults seed guarded by `SELECT COUNT(*) FROM delivery_categories`:

| code | nameRU | namePL |
|------|--------|--------|
| bathroom_equipment | Оборудование ванной | Wyposażenie łazienki |
| accessories | Аксессуары | Akcesoria |
| decor | Декор | Dekor |
| tiles | Плитка | Płytki |
| paints | Краски | Farby |
| lighting | Освещение | Oświetlenie |
| flooring | Пол | Podłoga |
| doors | Двери | Drzwi |
| windows | Окна | Okna |

## Frontend — `foremen-frontend/src/features/delivery-categories/`
Mirror the Room Types page exactly (no extra fields):
- types (`DeliveryCategoryDto {id, code, name, active}`, `DeliveryCategoryExtendedDto {id, code, nameRU, namePL, active}`, create/update, FormMode, PaginatedResponse), api (`delivery-categories-api.ts` via shared buildFetchQuery → `/api/delivery-categories`), query/mutation hooks (`deliveryCategoryKeys`), zod schema (code non-blank + pattern, nameRU/namePL min/max; update omits code).
- `DeliveryCategoriesPage` + `DeliveryCategoriesList` (columns code/name/active-badge; `usePermission('DELIVERY_CATEGORIES', …)`; fetchFn adapter; DataTable `entityKey="delivery-categories" resource="DELIVERY_CATEGORIES"`) + `DeliveryCategoryFormSheet` (code read-only on edit) + `DeleteDeliveryCategoryDialog` + local `ActiveBadge`.
- routing: lazy `DeliveryCategoriesPage` + route `{ path: 'delivery-categories', ... }`; interim `extra['/delivery-categories'] = { resource:'DELIVERY_CATEGORIES', operation:'READ' }`.
- i18n `deliveryCategories.*` (PL+RU parity): pageTitle; search.placeholder; actions.{create,retry}; list.empty; table.{code,name,active,actions}; badge.{active,inactive}; form.{titleCreate,titleEdit,descriptionCreate,descriptionEdit,code,nameRU,namePL,active,submitCreate,submitEdit}; delete.{title,description}; toast.{createSuccess,updateSuccess,deleteSuccess}; errors.{loadFailed,network}; validation.{codeRequired,codePattern,nameMin,nameMax}. Plus `nav.deliveryCategories`.

## Testing Strategy
PBT n/a. Backend: `DeliveryCategoryControllerIntegrationTest` (CRUD, i18n, code immutable) + `DeliveryCategoriesResourceSeedIntegrationTest` (resource; grants ADMIN CRUD / MANAGER,FOREMAN,WORKER READ / FINANCIER,CLIENT none; nine default codes; idempotency). Frontend: `DeliveryCategoriesList.test.tsx` + i18n parity. Run only affected classes/files.
