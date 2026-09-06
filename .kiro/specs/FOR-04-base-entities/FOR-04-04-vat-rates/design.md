# Design — FOR-04-04: VAT Rates dictionary

## Overview

`VatRate` mirrors the FOR-04-03 `Currency` stack, replacing `symbol` with two
extra non-i18n fields: `rate` (BigDecimal percent) and `isDefault` (boolean).
i18n only on `name`. No new framework code — plugs into `AdminController` /
`AdminService` / MapStruct + `@PermissionResource`. Backend CRUD vertical +
frontend admin page; menu entry is FOR-04-15.

## Backend (under `com.foremen`, `foremen-backend/src/main/java`)

### Entity — `dao/model/VatRateEntity`
```java
@Entity @Table(name = "vat_rates")
@Getter @Setter @NoArgsConstructor
public class VatRateEntity extends BaseEntity {
    @Column(nullable = false, unique = true) private String code;
    @Column(nullable = false, precision = 5, scale = 2) private BigDecimal rate;
    @Column(name = "name_ru", nullable = false) private String nameRU;
    @Column(name = "name_pl", nullable = false) private String namePL;
    @Column(name = "is_default", nullable = false) private boolean isDefault = false;
    @Column(nullable = false) private boolean active = true;
}
```

### DAO — `dao/VatRateDao extends AdminDao<VatRateEntity, Long>`

### Service models — `service/model`
- `VatRateServiceModel` (`@Data`): `{Long id, String code, BigDecimal rate, String name, boolean isDefault, boolean active}`
- `VatRateServiceExtendedModel` (record): `{Long id, String code, BigDecimal rate, String nameRU, String namePL, boolean isDefault, boolean active}`

### Service mapper — `VatRateServiceMapper`
`getI18nSupportedProperties()=Set.of("name")`; `updateFields` ignores `id`+`code`.

### Service — `VatRateService implements AdminService<...>` (Lombok getters).

### Controller models — `controller/model` (records)
- `VatRateDtoModel`: `{Long id, String code, BigDecimal rate, String name, boolean isDefault, boolean active}`
- `VatRateDtoExtendedModel`: `{Long id, String code, BigDecimal rate, String nameRU, String namePL, boolean isDefault, boolean active}`
- `VatRateCreateRequest`: `{@NotBlank code, @NotNull rate, @NotBlank nameRU, @NotBlank namePL, Boolean isDefault, Boolean active}`
- `VatRateUpdateRequest`: `{@NotNull rate, @NotBlank nameRU, @NotBlank namePL, Boolean isDefault, Boolean active}` (no `code`)
- `VatRateCreateResponse` / `VatRateUpdateResponse`: `{Long id, String code, BigDecimal rate, String nameRU, String namePL, boolean isDefault, boolean active}`

### Controller mapper — `VatRateControllerMapper`
Ignore `id` on create, `id`+`code` on update; default `active`→true and `isDefault`→false
when the request Boolean is null (MapStruct expression, mirror Currency's `active`).

### Controller — `VatRateController` @RequestMapping("/api/vat-rates") @PermissionResource("VAT_RATES").

## Liquibase (registered LAST after 021)
- `022-create-vat-rates.xml` — table `vat_rates` (id BIGSERIAL PK; code VARCHAR NOT NULL UNIQUE uk_vat_rates_code; rate NUMERIC(5,2) NOT NULL; name_ru/name_pl VARCHAR NOT NULL; is_default BOOLEAN NOT NULL default false; active BOOLEAN NOT NULL default true; 4 audit columns). Guard `tableExists` + `MARK_RAN`.
- `023-seed-vat-rates-resource.xml` — resource `VAT_RATES` (RU "Ставки НДС" / PL "Stawki VAT"); ADMIN CRUD + MANAGER/FOREMAN/WORKER/FINANCIER READ + CLIENT omitted; defaults seed (guarded by `SELECT COUNT(*) FROM vat_rates`):

| code | rate | nameRU | namePL | is_default |
|------|------|--------|--------|:----------:|
| 23   | 23.00| 23%    | 23%    | true |
| 8    | 8.00 | 8%     | 8%     | false |
| 5    | 5.00 | 5%     | 5%     | false |
| 0    | 0.00 | 0%     | 0%     | false |

## Frontend — `foremen-frontend/src/features/vat-rates/`

Mirror the Currency page; `symbol` → `rate` (number) + `isDefault` (boolean badge).
- types: `VatRateDto {id, code, rate, name, isDefault, active}`; `VatRateExtendedDto {id, code, rate, nameRU, namePL, isDefault, active}`; create `{code, rate, nameRU, namePL, isDefault?, active?}`; update `{rate, nameRU, namePL, isDefault?, active?}`; FormMode; PaginatedResponse.
- api `vat-rates-api.ts` (shared `buildFetchQuery` → `/api/vat-rates`); query/mutation hooks (`vatRateKeys`); zod schema (`code` non-blank + pattern, `rate` number ≥ 0, `nameRU`/`namePL` min/max), update omits `code`.
- `VatRatesPage` + `VatRatesList` (columns code, rate, name, isDefault-badge, active-badge; `usePermission('VAT_RATES', …)`; fetchFn adapter; DataTable `entityKey="vat-rates" resource="VAT_RATES"`) + `VatRateFormSheet` (code read-only on edit; rate numeric input; isDefault + active checkboxes) + `DeleteVatRateDialog` + local `ActiveBadge`.
- routing: lazy `VatRatesPage` + route `{ path: 'vat-rates', ... }`; interim `extra['/vat-rates'] = { resource:'VAT_RATES', operation:'READ' }`.
- i18n `vatRates.*` (PL+RU parity): pageTitle; search.placeholder; actions.{create,retry}; list.empty; table.{code,rate,name,isDefault,active,actions}; badge.{active,inactive,default,notDefault}; form.{titleCreate,titleEdit,descriptionCreate,descriptionEdit,code,rate,nameRU,namePL,isDefault,active,submitCreate,submitEdit}; delete.{title,description}; toast.{createSuccess,updateSuccess,deleteSuccess}; errors.{loadFailed,network}; validation.{codeRequired,codePattern,rateRequired,nameMin,nameMax}. Plus `nav.vatRates`.

## Testing Strategy
PBT n/a. Backend: `VatRateControllerIntegrationTest` (mirror Currency test; assert rate + isDefault on create/update, code immutable) + `VatRatesResourceSeedIntegrationTest` (resource, grants, four default codes incl. the 23% default flag, idempotency). Frontend: `VatRatesList.test.tsx` (rows render code/rate/name/isDefault/active badges; gated controls; empty state) + i18n parity. Run only affected classes/files.
