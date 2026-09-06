# Design — FOR-04-10: Offer Packages dictionary

## Overview

`OfferPackage` mirrors the FOR-04-08 `DeliveryStatus` stack (fields `code`,
`orderNo` Integer, `nameRU`, `namePL`, `active`; i18n only on `name`). The ONLY
difference from DeliveryStatus is the ABAC grant set: FINANCIER GETS a READ grant
here (matching WORK_CATEGORIES/MATERIAL_CATEGORIES); CLIENT still gets none.
Backend CRUD vertical + frontend admin page; menu entry is FOR-04-15.

## Backend (under `com.foremen`)
- `dao/model/OfferPackageEntity` @Table("offer_packages") extends BaseEntity: `code`(unique NOT NULL), `orderNo`(Integer, `@Column(name="order_no", nullable=false)`), `nameRU`(name_ru), `namePL`(name_pl), `boolean active=true`.
- `dao/OfferPackageDao extends AdminDao<OfferPackageEntity, Long>`.
- service models: `OfferPackageServiceModel` (@Data) `{id, code, orderNo, name, active}`; `OfferPackageServiceExtendedModel` (record) `{id, code, orderNo, nameRU, namePL, active}`.
- `OfferPackageServiceMapper`: `getI18nSupportedProperties()=Set.of("name")`; updateFields ignore id+code.
- `OfferPackageService implements AdminService<...>`.
- controller model records: `OfferPackageDtoModel {id, code, orderNo, name, active}`; `OfferPackageDtoExtendedModel {id, code, orderNo, nameRU, namePL, active}`; `OfferPackageCreateRequest {@NotBlank code, @NotNull Integer orderNo, @NotBlank nameRU, @NotBlank namePL, Boolean active}`; `OfferPackageUpdateRequest {@NotNull orderNo, @NotBlank nameRU, @NotBlank namePL, Boolean active}` (no code); `OfferPackageCreateResponse`/`OfferPackageUpdateResponse {id, code, orderNo, nameRU, namePL, active}`.
- `OfferPackageControllerMapper`: ignore id/create, id+code/update, default active→true.
- `OfferPackageController` @RequestMapping("/api/offer-packages") @PermissionResource("OFFER_PACKAGES").

## Liquibase (registered LAST after 033)
- `034-create-offer-packages.xml` — table `offer_packages` (id BIGSERIAL PK; code VARCHAR(100) NOT NULL UNIQUE uk_offer_packages_code; order_no INT NOT NULL; name_ru/name_pl VARCHAR(255) NOT NULL; active BOOLEAN NOT NULL default true; 4 audit columns). `tableExists` + `MARK_RAN`.
- `035-seed-offer-packages-resource.xml` — resource `OFFER_PACKAGES` (RU "Пакеты предложений" / PL "Pakiety ofert", description RU "Справочник пакетов предложений" / PL "Katalog pakietów ofert"); grants ADMIN CRUD + MANAGER/FOREMAN/WORKER/**FINANCIER** READ (idempotent per role); **CLIENT omitted** (comment noting deny-by-default); defaults seed guarded by `SELECT COUNT(*) FROM offer_packages`:

| orderNo | code | nameRU | namePL |
|---------|------|--------|--------|
| 1 | budget | Бюджет (START) | Budżet (START) |
| 2 | norm | Норма (COMFORT) | Norma (COMFORT) |
| 3 | lux | Люкс (PRESTIGE) | Lux (PRESTIGE) |

## Frontend — `foremen-frontend/src/features/offer-packages/`
Mirror the Delivery Statuses page exactly (with the `orderNo` column + form field):
- types (`OfferPackageDto {id, code, orderNo, name, active}`, `OfferPackageExtendedDto {id, code, orderNo, nameRU, namePL, active}`, create `{code, orderNo, nameRU, namePL, active?}`, update `{orderNo, nameRU, namePL, active?}`, FormMode, PaginatedResponse), api (`offer-packages-api.ts` via shared buildFetchQuery → `/api/offer-packages`), query/mutation hooks (`offerPackageKeys`), zod schema (code non-blank + lowercase pattern `^[a-z0-9_]{1,50}$`, orderNo number ≥ 0, nameRU/namePL min/max; update omits code).
- `OfferPackagesPage` + `OfferPackagesList` (columns orderNo(number,80px), code(string,140px), name(string,240px), active(badge,100px); `usePermission('OFFER_PACKAGES', …)`; fetchFn adapter; DataTable `entityKey="offer-packages" resource="OFFER_PACKAGES"` defaultSort by orderNo asc) + `OfferPackageFormSheet` (code read-only on edit; orderNo numeric input) + `DeleteOfferPackageDialog` + local `ActiveBadge`.
- routing: lazy `OfferPackagesPage` + route `{ path: 'offer-packages', ... }`; interim `extra['/offer-packages'] = { resource:'OFFER_PACKAGES', operation:'READ' }`.
- i18n `offerPackages.*` (PL+RU parity): pageTitle; search.placeholder; actions.{create,retry}; list.empty; table.{orderNo,code,name,active,actions}; badge.{active,inactive}; form.{titleCreate,titleEdit,descriptionCreate,descriptionEdit,code,orderNo,nameRU,namePL,active,submitCreate,submitEdit}; delete.{title,description}; toast.{createSuccess,updateSuccess,deleteSuccess}; errors.{loadFailed,network}; validation.{codeRequired,codePattern,orderNoRequired,nameMin,nameMax}. Plus `nav.offerPackages`.

## Testing Strategy
PBT n/a. Backend: `OfferPackageControllerIntegrationTest` (CRUD; i18n; code immutable; orderNo update) + `OfferPackagesResourceSeedIntegrationTest` (resource; grants ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none; three default codes present, COUNT==3, orderNo 1..3; idempotency). Frontend: `OfferPackagesList.test.tsx` (rows render orderNo/code/name/active) + i18n parity. Run only affected classes/files.
