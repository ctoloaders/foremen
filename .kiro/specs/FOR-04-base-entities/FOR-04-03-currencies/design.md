# Design — FOR-04-03: Currencies dictionary

## Overview

`Currency` is implemented as a generic full-CRUD managed resource mirroring the
FOR-04-02 `MeasurementUnit` stack (which itself mirrors `ROLES`), with one extra
scalar field `symbol` (non-i18n). No new framework code — it plugs into the
existing `AdminController` / `AdminService` / MapStruct contracts (FOR-01) and the
`@PermissionResource` guard (FOR-03-08). i18n only on `name` (`nameRU`/`namePL`).
Both a backend CRUD vertical and a frontend admin CRUD page are delivered; the
main-menu entry is FOR-04-15.

## Backend layers (all under `com.foremen`, source `foremen-backend/src/main/java`)

### 1. Entity — `dao/model/CurrencyEntity`
```java
@Entity @Table(name = "currencies")
@Getter @Setter @NoArgsConstructor
public class CurrencyEntity extends BaseEntity {
    @Column(nullable = false, unique = true) private String code;
    @Column(nullable = false) private String symbol;
    @Column(name = "name_ru", nullable = false) private String nameRU;
    @Column(name = "name_pl", nullable = false) private String namePL;
    @Column(nullable = false) private boolean active = true;
}
```

### 2. DAO — `dao/CurrencyDao`
```java
@Repository
public interface CurrencyDao extends AdminDao<CurrencyEntity, Long> {}
```

### 3. Service models — `service/model`
- `CurrencyServiceModel` (localized list model, `@Data`): `{Long id, String code, String symbol, String name, boolean active}`
- `CurrencyServiceExtendedModel` (record, all locales): `{Long id, String code, String symbol, String nameRU, String namePL, boolean active}`

### 4. Service mapper — `service/model/mapper/CurrencyServiceMapper`
`extends ServiceToDaoMapper<CurrencyEntity, CurrencyServiceModel, CurrencyServiceExtendedModel>`,
`@Mapper(config = ForemenMapperConfig.class)`; `getI18nSupportedProperties()=Set.of("name")`;
`updateFields` ignores `id` + `code` (code immutable); `toCreateDaoModel` ignores `id`.

### 5. Service — `service/CurrencyService`
`@Service @RequiredArgsConstructor @Getter implements AdminService<CurrencyServiceModel,
CurrencyServiceExtendedModel, CurrencyEntity, Long>`; fields `dao, mapper, auditLogDao,
entityManager, daoModelClass = CurrencyEntity.class`. No overrides.

### 6. Controller models — `controller/model` (records)
- `CurrencyDtoModel`: `{Long id, String code, String symbol, String name, boolean active}`
- `CurrencyDtoExtendedModel`: `{Long id, String code, String symbol, String nameRU, String namePL, boolean active}`
- `CurrencyCreateRequest`: `{@NotBlank code, @NotBlank symbol, @NotBlank nameRU, @NotBlank namePL, Boolean active}`
- `CurrencyUpdateRequest`: `{@NotBlank symbol, @NotBlank nameRU, @NotBlank namePL, Boolean active}` (no `code`)
- `CurrencyCreateResponse` / `CurrencyUpdateResponse`: `{Long id, String code, String symbol, String nameRU, String namePL, boolean active}`

### 7. Controller mapper — `controller/model/mapper/CurrencyControllerMapper`
`extends ControllerToServiceMapper<...>`, `@Mapper(config = ForemenMapperConfig.class)`;
ignore `id` on create, `id`+`code` on update; default `active` to true when omitted via a
MapStruct expression (mirror the MeasurementUnit mapper's `active` handling).

### 8. Controller — `controller/CurrencyController`
`@RestController @RequestMapping("/api/currencies") @RequiredArgsConstructor
@PermissionResource("CURRENCIES") implements AdminController<...10 params...>`; delegates
`getMapper()`/`getService()`; no CRUD method overrides. Startup-validated by
`PermissionAnnotationValidator`.

## Liquibase

New changesets, registered LAST in `changelog.xml` after `019`:

- `020-create-currencies.xml` — create table `currencies` (`id BIGSERIAL PK`,
  `code VARCHAR NOT NULL UNIQUE` (uk_currencies_code), `symbol VARCHAR NOT NULL`,
  `name_ru`/`name_pl VARCHAR NOT NULL`, `active BOOLEAN NOT NULL DEFAULT true`,
  4 audit columns). Guard: `<not><tableExists .../></not>` + `MARK_RAN`.
- `021-seed-currencies-resource.xml` — contains:
  - resource insert (`CURRENCIES`, RU/PL name+description), idempotent `sqlCheck` on code.
  - per-role grant changesets: ADMIN `CREATE,READ,UPDATE,DELETE`; MANAGER/FOREMAN/WORKER/FINANCIER `READ`; CLIENT omitted. Each idempotent (`sqlCheck expectedResult="0"` scoped by role+resource code), following the FOR-04-02 / `015` pattern.
  - default-currencies seed changeset: insert `PLN, EUR, USD` into `currencies` with symbol + RU/PL names + `active=true`, guarded `sqlCheck expectedResult="0" SELECT COUNT(*) FROM currencies`.

### Seed currencies (code / symbol / nameRU / namePL)
| code | symbol | nameRU        | namePL         |
|------|--------|---------------|----------------|
| PLN  | zł     | Злотый        | Złoty          |
| EUR  | €      | Евро          | Euro           |
| USD  | $      | Доллар США    | Dolar amerykański |

## Frontend — Admin CRUD page

Mirrors the FOR-04-02 Measurement Units page. New feature folder
`foremen-frontend/src/features/currencies/`:

### Types — `types/index.ts`
- `CurrencyDto` (list): `{ id, code, symbol, name, active }`
- `CurrencyExtendedDto` (edit): `{ id, code, symbol, nameRU, namePL, active }`
- `CurrencyCreateRequest`: `{ code, symbol, nameRU, namePL, active? }`
- `CurrencyUpdateRequest`: `{ symbol, nameRU, namePL, active? }` (no `code`)
- `CurrencyFormMode = 'create' | 'edit'`; `PaginatedResponse<T>`

### API — `api/currencies-api.ts`
Over the shared `apiRequest`, `BASE_URL='/api'`: `fetchCurrencies({page,size,query?,sort?})`
via the shared `buildFetchQuery` → `GET /api/currencies?...`; `fetchCurrency(id)`;
`createCurrency`; `updateCurrency(id, body)`; `deleteCurrency(id)`.

### Hooks / schema
`api/query-hooks.ts` (`currencyKeys`, `useCurrency(id)`), `api/mutation-hooks.ts`
(`useCreate|Update|DeleteCurrency`, invalidate `currencyKeys.lists()` on success).
`schemas/currency-schema.ts`: zod create (`code` non-blank + ISO-ish pattern e.g.
`/^[A-Za-z]{2,10}$/`, `symbol` non-blank, `nameRU`/`namePL` min/max) and
`currencyUpdateSchema = createSchema.omit({ code: true })`. Messages are i18n keys.

### Components
- `CurrenciesPage.tsx` (default export): title `t('currencies.pageTitle')`; owns form-sheet +
  delete-dialog state; renders list + `<CurrencyFormSheet>` + `<DeleteCurrencyDialog>`;
  success toasts by mode.
- `components/CurrenciesList.tsx`: `ColumnConfig<CurrencyDto>[]` = code (string, sortable/
  filterable/searchable), symbol (string, sortable/filterable, not searchable), name (string,
  sortable/filterable/searchable), active (boolean, render `ActiveBadge`); `usePermission('CURRENCIES', …)`;
  memoized `fetchFn` adapter (adds `first`/`last`); gated Create + `rowActions`;
  `<DataTable entityKey="currencies" resource="CURRENCIES" defaultPageSize={10} pageSizeOptions={[10,25,50]} .../>`.
- `components/CurrencyFormSheet.tsx`: react-hook-form + zodResolver; create=all fields,
  edit=prefill from `useCurrency`, `code` read-only; fields code, symbol, nameRU, namePL, active.
- `components/DeleteCurrencyDialog.tsx`: AlertDialog confirm → `useDeleteCurrency`.
- `components/ActiveBadge.tsx`: localized Active/Inactive badge (mirror the MU one, keys under `currencies.badge.*`).

### Routing & guard
- `src/app/router.tsx`: lazy `CurrenciesPage` + child route `{ path: 'currencies', element: <SuspenseWrapper><CurrenciesPage/></SuspenseWrapper> }`.
- `src/config/route-permissions.ts`: interim `extra['/currencies'] = { resource: 'CURRENCIES', operation: 'READ' }`.

### i18n — `currencies.*` in `pl.json` + `ru.json` (parity, PL+RU)
Keys: `pageTitle`; `search.placeholder`; `actions.{create,retry}`; `list.empty`;
`table.{code,symbol,name,active,actions}`; `badge.{active,inactive}`;
`form.{titleCreate,titleEdit,descriptionCreate,descriptionEdit,code,symbol,nameRU,namePL,active,submitCreate,submitEdit}`;
`delete.{title,description}`; `toast.{createSuccess,updateSuccess,deleteSuccess}`;
`errors.{loadFailed,network}`; `validation.{codeRequired,codePattern,symbolRequired,nameMin,nameMax}`.
Also add `nav.currencies`. No new backend i18n fields — the only i18n field is `name`.

## Testing Strategy

PBT does not apply (standard CRUD + declarative seed). Backend:
- `CurrencyControllerIntegrationTest` (Testcontainers + MockMvc, mirror
  `MeasurementUnitControllerIntegrationTest`): create → list → read → update → delete;
  i18n `name` RU/PL; `code` immutable on update; `symbol` updatable.
- `CurrenciesResourceSeedIntegrationTest` (mirror `MeasurementUnitsResourceSeedIntegrationTest`):
  real changelog run; `CURRENCIES` resource; exact per-role grants; the three default codes
  (`PLN`, `EUR`, `USD`); idempotency on re-run.

Frontend:
- `__tests__/CurrenciesList.test.tsx` mirroring `MeasurementUnitsList.test.tsx` (rows render
  code + symbol + resolved name + active badge; permission-gated controls; empty state).
- i18n parity check for `currencies.*` across `pl.json`/`ru.json`.

Per the workspace rule, run only the affected classes/files (temp log, JUnit XML / vitest);
never the full suites.
