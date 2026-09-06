# Design — FOR-04-08: Delivery Statuses dictionary

## Overview

`DeliveryStatus` mirrors the FOR-04-06 `WorkCategory` stack (fields `code`,
`orderNo` Integer, `nameRU`, `namePL`, `active`; i18n only on `name`). The ONLY
difference from WorkCategory is the ABAC grant set: FINANCIER gets NO grant here
(matching DELIVERY_CATEGORIES). Backend CRUD vertical + frontend admin page;
menu entry is FOR-04-15.

## Backend (under `com.foremen`)
- `dao/model/DeliveryStatusEntity` @Table("delivery_statuses") extends BaseEntity: `code`(unique NOT NULL), `orderNo`(Integer, `@Column(name="order_no", nullable=false)`), `nameRU`(name_ru), `namePL`(name_pl), `boolean active=true`.
- `dao/DeliveryStatusDao extends AdminDao<DeliveryStatusEntity, Long>`.
- service models: `DeliveryStatusServiceModel` (@Data) `{id, code, orderNo, name, active}`; `DeliveryStatusServiceExtendedModel` (record) `{id, code, orderNo, nameRU, namePL, active}`.
- `DeliveryStatusServiceMapper`: `getI18nSupportedProperties()=Set.of("name")`; updateFields ignore id+code.
- `DeliveryStatusService implements AdminService<...>`.
- controller model records: `DeliveryStatusDtoModel {id, code, orderNo, name, active}`; `DeliveryStatusDtoExtendedModel {id, code, orderNo, nameRU, namePL, active}`; `DeliveryStatusCreateRequest {@NotBlank code, @NotNull Integer orderNo, @NotBlank nameRU, @NotBlank namePL, Boolean active}`; `DeliveryStatusUpdateRequest {@NotNull orderNo, @NotBlank nameRU, @NotBlank namePL, Boolean active}` (no code); `DeliveryStatusCreateResponse`/`DeliveryStatusUpdateResponse {id, code, orderNo, nameRU, namePL, active}`.
- `DeliveryStatusControllerMapper`: ignore id/create, id+code/update, default active→true.
- `DeliveryStatusController` @RequestMapping("/api/delivery-statuses") @PermissionResource("DELIVERY_STATUSES").

## Liquibase (registered LAST after 029)
- `030-create-delivery-statuses.xml` — table `delivery_statuses` (id BIGSERIAL PK; code VARCHAR(100) NOT NULL UNIQUE uk_delivery_statuses_code; order_no INT NOT NULL; name_ru/name_pl VARCHAR(255) NOT NULL; active BOOLEAN NOT NULL default true; 4 audit columns). `tableExists` + `MARK_RAN`.
- `031-seed-delivery-statuses-resource.xml` — resource `DELIVERY_STATUSES` (RU "Статусы доставок" / PL "Statusy dostaw", description RU "Справочник статусов доставок" / PL "Katalog statusów dostaw"); grants ADMIN CRUD + MANAGER/FOREMAN/WORKER READ (idempotent per role); **FINANCIER and CLIENT omitted** (comment noting deny-by-default); defaults seed guarded by `SELECT COUNT(*) FROM delivery_statuses`:

| orderNo | code | nameRU | namePL |
|---------|------|--------|--------|
| 1 | new | Новый | Nowe |
| 2 | ordered | Заказан | Zamówione |
| 3 | delivered | Доставлен | Dostarczone |
| 4 | cancelled | Отменён | Anulowane |

## Frontend — `foremen-frontend/src/features/delivery-statuses/`
Mirror the Work Categories page exactly (with the `orderNo` column + form field):
- types (`DeliveryStatusDto {id, code, orderNo, name, active}`, `DeliveryStatusExtendedDto {id, code, orderNo, nameRU, namePL, active}`, create `{code, orderNo, nameRU, namePL, active?}`, update `{orderNo, nameRU, namePL, active?}`, FormMode, PaginatedResponse), api (`delivery-statuses-api.ts` via shared buildFetchQuery → `/api/delivery-statuses`), query/mutation hooks (`deliveryStatusKeys`), zod schema (code non-blank + pattern, orderNo number ≥ 0, nameRU/namePL min/max; update omits code).
- `DeliveryStatusesPage` + `DeliveryStatusesList` (columns orderNo(number,80px), code(string,140px), name(string,240px), active(badge,100px); `usePermission('DELIVERY_STATUSES', …)`; fetchFn adapter; DataTable `entityKey="delivery-statuses" resource="DELIVERY_STATUSES"` defaultSort by orderNo asc) + `DeliveryStatusFormSheet` (code read-only on edit; orderNo numeric input) + `DeleteDeliveryStatusDialog` + local `ActiveBadge`.
- routing: lazy `DeliveryStatusesPage` + route `{ path: 'delivery-statuses', ... }`; interim `extra['/delivery-statuses'] = { resource:'DELIVERY_STATUSES', operation:'READ' }`.
- i18n `deliveryStatuses.*` (PL+RU parity): pageTitle; search.placeholder; actions.{create,retry}; list.empty; table.{orderNo,code,name,active,actions}; badge.{active,inactive}; form.{titleCreate,titleEdit,descriptionCreate,descriptionEdit,code,orderNo,nameRU,namePL,active,submitCreate,submitEdit}; delete.{title,description}; toast.{createSuccess,updateSuccess,deleteSuccess}; errors.{loadFailed,network}; validation.{codeRequired,codePattern,orderNoRequired,nameMin,nameMax}. Plus `nav.deliveryStatuses`.

## Testing Strategy
PBT n/a. Backend: `DeliveryStatusControllerIntegrationTest` (CRUD; i18n; code immutable; orderNo update) + `DeliveryStatusesResourceSeedIntegrationTest` (resource; grants ADMIN CRUD / MANAGER,FOREMAN,WORKER READ / FINANCIER,CLIENT none; four default codes present, COUNT==4, orderNo 1..4; idempotency). Frontend: `DeliveryStatusesList.test.tsx` (rows render orderNo/code/name/active) + i18n parity. Run only affected classes/files.
