# Design — FOR-04-12: Work Prices (WorkPrice) entity + Excel price-list seed

## Overview

`WorkPrice` is the price-history entity for catalog work items. It follows the
FOR-04-11 `WorkItem` FK pattern (JPA `@ManyToOne` + abstract-class ServiceMapper
resolving flat ids via `entityManager.getReference`). It adds `netPrice`
(BigDecimal), `validFrom`/`validTo` dates, and a derived `current` flag
(`validTo == null`). The seed parses the Excel price list to populate BOTH the
`work_items` catalog (not seeded in FOR-04-11) AND the current `work_prices`.
Global catalog — NOT project-scoped, so it does NOT implement `ProjectScopedService`.

## Backend (under `com.foremen`)

### Entity — `dao/model/WorkPriceEntity` @Table("work_prices") extends BaseEntity
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "work_item_id", nullable = false)
private WorkItemEntity workItem;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "currency_id", nullable = false)
private CurrencyEntity currency;

@Column(name = "net_price", nullable = false) private BigDecimal netPrice;
@Column(name = "valid_from", nullable = false) private LocalDate validFrom;
@Column(name = "valid_to") private LocalDate validTo;   // null = current
```

### DAO — `dao/WorkPriceDao extends AdminDao<WorkPriceEntity, Long>`

### Service models
- `service/model/WorkPriceServiceModel` (@Data, list): `{id, workItemId, workItemName, currencyId, currencyCode, netPrice, validFrom, validTo, current}` — `workItemName` is the referenced item's localized name (locale-aware, like WorkItem's referenced-name resolution), `currencyCode` is the currency code, `current = (validTo == null)`.
- `service/model/WorkPriceServiceExtendedModel` (record): `{id, workItemId, currencyId, netPrice, validFrom, validTo}`.

### ServiceMapper — `service/model/mapper/WorkPriceServiceMapper` — abstract class @Mapper(config=ForemenMapperConfig) extends ServiceToDaoMapper (SAME pattern as WorkItemServiceMapper)
- `@Autowired protected EntityManager entityManager;` + helpers `workItemRef(Long)`, `currencyRef(Long)` via `entityManager.getReference(...)`.
- `getI18nSupportedProperties()` — WorkPrice has no own i18n `name`, so return `Collections.emptySet()` (or omit). The referenced `workItemName` is resolved in an `@AfterMapping` reading `source.getWorkItem().getNameRU()/getNamePL()` via `LocaleContextHolder` (same helper approach as WorkItem).
- `toCreateDaoModel`: `@Mapping(target="id", ignore=true)`, `@Mapping(target="workItem", expression="java(workItemRef(source.workItemId()))")`, `@Mapping(target="currency", expression="java(currencyRef(source.currencyId()))")`.
- `updateFields`: same two expression mappings + `@Mapping(target="id", ignore=true)` (partial-update IGNORE from parent).
- `toServiceModel`: `@Mapping(target="workItemId", source="workItem.id")`, `@Mapping(target="currencyId", source="currency.id")`, `@Mapping(target="currencyCode", source="currency.code")`, `@Mapping(target="current", expression="java(source.getValidTo() == null)")`; `workItemName` set in `@AfterMapping`.
- `toServiceExtendedModel`: map `workItem.id`/`currency.id` → ids.

### Controller models
- `WorkPriceDtoModel {id, workItemId, workItemName, currencyId, currencyCode, netPrice, validFrom, validTo, current}`.
- `WorkPriceDtoExtendedModel {id, workItemId, currencyId, netPrice, validFrom, validTo}`.
- `WorkPriceCreateRequest {@NotNull Long workItemId, @NotNull Long currencyId, @NotNull @Positive BigDecimal netPrice, @NotNull LocalDate validFrom, LocalDate validTo}`.
- `WorkPriceUpdateRequest {@NotNull Long workItemId, @NotNull Long currencyId, @NotNull @Positive BigDecimal netPrice, @NotNull LocalDate validFrom, LocalDate validTo}`.
- `WorkPriceCreateResponse`/`WorkPriceUpdateResponse {id, workItemId, currencyId, netPrice, validFrom, validTo}`.

### ControllerMapper — extends ControllerToServiceMapper; create/update: `@Mapping(target="id", ignore=true)` (no `active` field, no default expression needed).

### Service — `service/WorkPriceService` @Service @RequiredArgsConstructor @Getter implements `AdminService<...>` with final dao/mapper/auditLogDao/entityManager/daoModelClass. NOT ProjectScopedService.

### Controller — `controller/WorkPriceController` @RestController @RequestMapping("/api/work-prices") @PermissionResource("WORK_PRICES"); implements the 10-generic AdminController.

## Liquibase (registered LAST after 037)
- `038-create-work-prices.xml` — table `work_prices` (id BIGSERIAL PK; work_item_id BIGINT NOT NULL FK fk_work_prices_work_item → work_items(id); currency_id BIGINT NOT NULL FK fk_work_prices_currency → currencies(id); net_price NUMERIC(12,2) NOT NULL; valid_from DATE NOT NULL; valid_to DATE NULL; 4 audit columns). Inline FK constraints. `tableExists` + `MARK_RAN`.
- `039-seed-work-prices-resource.xml` — combines:
  1. Resource `WORK_PRICES` (RU "Цены работ" / PL "Ceny prac", description RU "История цен работ" / PL "Historia cen prac").
  2. Grants (separate idempotent changesets per role): ADMIN CREATE/READ/UPDATE/DELETE; MANAGER CREATE/READ/UPDATE (NO DELETE); FOREMAN/WORKER/FINANCIER READ; CLIENT omitted (deny-by-default comment).
  3. WorkItem rows (`039-seed-work-items-from-oferta`, guarded `SELECT COUNT(*) FROM work_items = 0`): one `<insert>` per catalog row, `work_category_id`/`unit_id` resolved via `valueComputed="(SELECT id FROM work_categories WHERE code='...')"` / `"(SELECT id FROM measurement_units WHERE code='...')"`, `name_ru`/`name_pl`, `active=true`.
  4. WorkPrice rows (`039-seed-work-prices-from-oferta`, guarded `SELECT COUNT(*) FROM work_prices = 0`): one `<insert>` per priced row, `work_item_id` via `valueComputed="(SELECT wi.id FROM work_items wi JOIN work_categories wc ON wi.work_category_id=wc.id WHERE wi.name_pl='<escaped>' AND wc.code='<CODE>')"`, `currency_id` via `(SELECT id FROM currencies WHERE code='PLN')`, `net_price` numeric, `valid_from valueDate="<seed date>"`, `valid_to` omitted (NULL).

**Seed generation:** parse the CSV with a throwaway script (python) that emits the two changesets' `<insert>` blocks, then delete the script. Skip category-header rows (integer LP), blank rows, and rows without a real ZAKRES/JM/positive CENA. Escape single quotes in name_pl/name_ru for SQL (`''`). Document the parsed/skipped counts in the changeset comment.

> Duplicate-namePL caveat: if the same `name_pl` appears twice within one category, the WorkPrice `work_item_id` subselect would be ambiguous. The script MUST detect and de-duplicate (append a disambiguating suffix or skip the later duplicate), and the WorkItem+WorkPrice pair MUST stay consistent. Report any such collisions.

## Frontend — `foremen-frontend/src/features/work-prices/`
- types: `WorkPriceDto {id, workItemId, workItemName, currencyId, currencyCode, netPrice, validFrom, validTo, current}`, `WorkPriceExtendedDto {id, workItemId, currencyId, netPrice, validFrom, validTo}`, create/update, FormMode, PaginatedResponse.
- api (`work-prices-api.ts` via shared `buildFetchQuery` → `/api/work-prices`), query/mutation hooks (`workPriceKeys`), zod schema (workItemId/currencyId required numbers, netPrice positive number, validFrom required date, validTo optional date).
- `WorkPricesPage` + `WorkPricesList`: DataTable `entityKey="work-prices" resource="WORK_PRICES"`, columns:
  - `workItem` — reference (target `/api/work-items`), render `row.workItemName`.
  - `currency` — reference (target `/api/currencies`), render `row.currencyCode`.
  - `netPrice` (number), `validFrom` (date), `validTo` (date, empty = —), `current` (badge Current/Historic from `row.current`).
  Reference descriptors set inline (as done for work-catalog).
- `WorkPriceFormSheet`: `workItemId` select (options from `/api/work-items`, showing item name), `currencyId` select (options from `/api/currencies`), `netPrice` numeric, `validFrom` date, `validTo` optional date. `DeleteWorkPriceDialog` + `CurrentBadge`.
- routing: lazy `WorkPricesPage` + route `{ path: 'catalog/prices', ... }`; interim `extra['/catalog/prices'] = { resource:'WORK_PRICES', operation:'READ' }`.
- i18n `workPrices.*` (PL+RU parity): pageTitle; search.placeholder; actions.{create,retry}; list.empty; table.{workItem,currency,netPrice,validFrom,validTo,current,actions}; badge.{current,historic}; form.{titleCreate,titleEdit,descriptionCreate,descriptionEdit,workItem,currency,netPrice,validFrom,validTo,submitCreate,submitEdit,selectWorkItem,selectCurrency}; delete.{title,description}; toast.{createSuccess,updateSuccess,deleteSuccess}; errors.{loadFailed,network}; validation.{workItemRequired,currencyRequired,netPriceRequired,netPricePositive,validFromRequired}. Plus `nav.workPrices`.

## Testing Strategy
PBT n/a. Backend: `WorkPriceControllerIntegrationTest` — seed/persist a WorkItem + Currency, then CRUD with FK ids + price + dates, verify rows expose FK ids + referenced values + `current` (validTo null → current true), filter by workItem.id; `WorkPricesResourceSeedIntegrationTest` — resource; grants ADMIN CRUD / MANAGER CREATE,READ,UPDATE / FOREMAN,WORKER,FINANCIER READ / CLIENT none; work_items & work_prices seeded (COUNT > 0; current prices have valid_to NULL + PLN); idempotency. Frontend: `WorkPricesList.test.tsx` (rows render workItem/currency/netPrice/validFrom/current) + i18n parity. Run only affected classes/files.
