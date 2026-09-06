# Design — FOR-04-11: Work Catalog (WorkItem) entity

## Overview

`WorkItem` is the first entity to combine JPA `@ManyToOne` foreign keys with the
generic AdminController CRUD stack. It references `WorkCategory` (FOR-04-06) and
`MeasurementUnit` (FOR-04-02). The flat-dictionary stack (WorkCategory) is the
structural base; the novelty is FK handling in the mapper layer. Persistence is
JPA/Hibernate (jakarta.persistence). Reference metadata + filtering are automatic
(FOR-04-01: `EntityMetadataResolver` emits `ReferenceInfo` for `@ManyToOne`,
`SpecificationBuilder` joins dotted paths). NOT project-scoped — global catalog,
so it does NOT implement `ProjectScopedService`.

## Backend (under `com.foremen`)

### Entity — `dao/model/WorkItemEntity` @Table("work_items") extends BaseEntity
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "work_category_id", nullable = false)
private WorkCategoryEntity workCategory;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "unit_id", nullable = false)
private MeasurementUnitEntity unit;

@Column(name = "name_ru", nullable = false) private String nameRU;
@Column(name = "name_pl", nullable = false) private String namePL;
@Column(nullable = false) private boolean active = true;
```

### DAO — `dao/WorkItemDao extends AdminDao<WorkItemEntity, Long>`

### Service models
- `service/model/WorkItemServiceModel` (@Data, list/localized): `{id, workCategoryId, workCategoryName, unitId, unitName, name, active}` — `name` is the collapsed i18n name; `workCategoryName`/`unitName` are the referenced entities' localized names for display.
- `service/model/WorkItemServiceExtendedModel` (record, extended/RU+PL): `{id, workCategoryId, unitId, nameRU, namePL, active}`.

### ServiceMapper — `service/model/mapper/WorkItemServiceMapper` (@Mapper(config=ForemenMapperConfig)) extends `ServiceToDaoMapper<WorkItemEntity, WorkItemServiceModel, WorkItemServiceExtendedModel>`
- `getI18nSupportedProperties() = Set.of("name")`.
- FK RESOLUTION (the novel piece): the mapper is an abstract class (or interface with a `@Context`/default) that has access to `EntityManager` to turn flat ids into JPA references. Recommended: declare `WorkItemServiceMapper` as an **abstract class** with an injected `@Autowired protected EntityManager entityManager;` and helper methods:
  ```java
  protected WorkCategoryEntity workCategoryRef(Long id) { return id == null ? null : entityManager.getReference(WorkCategoryEntity.class, id); }
  protected MeasurementUnitEntity unitRef(Long id) { return id == null ? null : entityManager.getReference(MeasurementUnitEntity.class, id); }
  ```
  Map create: `@Mapping(target="id", ignore=true) @Mapping(target="workCategory", expression="java(workCategoryRef(source.workCategoryId()))") @Mapping(target="unit", expression="java(unitRef(source.unitId()))") abstract WorkItemEntity toCreateDaoModel(WorkItemServiceExtendedModel source);`
  Update: same two expression mappings + `@Mapping(target="id", ignore=true)` on `updateFields(...)` (partial update keeps IGNORE strategy; FK reassignment allowed).
- Read mappings (entity → service models): `toServiceModel` maps `workCategory.id → workCategoryId`, `unit.id → unitId`, and the referenced localized names into `workCategoryName`/`unitName`. The referenced localized name uses the same PL-fallback logic; simplest is to resolve in an `@AfterMapping` using the locale-aware helper already used for `name`, OR map `workCategory.namePL`/`unit.namePL` for display (PL authoritative) — **use the i18n resolver for consistency**: expose `workCategoryName`/`unitName` as computed from the referenced entity's `nameRU`/`namePL` via the same `I18nPropertiesMapper` locale that resolves `name`. Keep it simple and correct: since `name` collapse already runs per request locale, resolve `workCategoryName`/`unitName` the same way in `toServiceModel`.
- `toServiceModel`/`toServiceExtendedModel`: `@Mapping(target="workCategoryId", source="workCategory.id")`, `@Mapping(target="unitId", source="unit.id")`.

> Note: if the abstract-class + injected EntityManager approach conflicts with the existing MapStruct config, an equivalent is a plain `@Component WorkItemFkResolver` referenced via `uses = WorkItemFkResolver.class` on the `@Mapper`. Pick whichever compiles cleanly against ForemenMapperConfig; the abstract-class form is simplest.

### Controller models (`controller/model/`)
- `WorkItemDtoModel {id, workCategoryId, workCategoryName, unitId, unitName, name, active}` (list).
- `WorkItemDtoExtendedModel {id, workCategoryId, unitId, nameRU, namePL, active}` (extended read for edit prefill).
- `WorkItemCreateRequest {@NotNull Long workCategoryId, @NotNull Long unitId, @NotBlank String nameRU, @NotBlank String namePL, Boolean active}`.
- `WorkItemUpdateRequest {@NotNull Long workCategoryId, @NotNull Long unitId, @NotBlank String nameRU, @NotBlank String namePL, Boolean active}` (FKs updatable; there is no immutable `code` here).
- `WorkItemCreateResponse`/`WorkItemUpdateResponse {id, workCategoryId, unitId, nameRU, namePL, active}`.

### ControllerMapper — `controller/model/mapper/WorkItemControllerMapper` extends `ControllerToServiceMapper<...>`
- `toServiceExtendedModel(create)`: `@Mapping(target="id", ignore=true)`, `@Mapping(target="active", expression="java(source.active()==null||source.active())")`.
- `toUpdateServiceExtendedModel(update)`: `@Mapping(target="id", ignore=true)`, active default expression (NO code to ignore).

### Service — `service/WorkItemService` @Service @RequiredArgsConstructor @Getter implements `AdminService<WorkItemServiceModel, WorkItemServiceExtendedModel, WorkItemEntity, Long>` with final `dao`, `mapper`, `auditLogDao`, `entityManager`, `daoModelClass = WorkItemEntity.class`. NOT `ProjectScopedService`.

### Controller — `controller/WorkItemController` @RestController @RequestMapping("/api/work-items") @PermissionResource("WORK_CATALOG"); implements the 10-generic `AdminController`, returns `getMapper()`/`getService()`.

## Liquibase (registered LAST after 035)
- `036-create-work-items.xml` — table `work_items` (id BIGSERIAL PK; work_category_id BIGINT NOT NULL FK fk_work_items_category → work_categories(id); unit_id BIGINT NOT NULL FK fk_work_items_unit → measurement_units(id); name_ru/name_pl VARCHAR(255) NOT NULL; active BOOLEAN NOT NULL default true; 4 audit columns). FK constraints declared inline (`references="work_categories(id)"` etc.). `tableExists` + `MARK_RAN`.
- `037-seed-work-catalog-resource.xml` — resource `WORK_CATALOG` (RU "Каталог работ" / PL "Katalog prac", description RU "Каталог работ (позиции)" / PL "Katalog prac (pozycje)"); grants (separate idempotent changesets per role):
  - ADMIN: CREATE, READ, UPDATE, DELETE
  - MANAGER: CREATE, READ, UPDATE (NO DELETE)
  - FOREMAN / WORKER / FINANCIER: READ
  - CLIENT: omitted (deny-by-default comment)
  No default work-item rows (seeded in FOR-04-12).

## Frontend — `foremen-frontend/src/features/work-catalog/`
- types: `WorkItemDto {id, workCategoryId, workCategoryName, unitId, unitName, name, active}`, `WorkItemExtendedDto {id, workCategoryId, unitId, nameRU, namePL, active}`, create/update `{workCategoryId, unitId, nameRU, namePL, active?}`, FormMode, PaginatedResponse.
- api (`work-catalog-api.ts` via shared `buildFetchQuery` → `/api/work-items`), query/mutation hooks (`workItemKeys`), zod schema (workCategoryId/unitId required numbers, nameRU/namePL min/max).
- `WorkCatalogPage` + `WorkCatalogList`: DataTable `entityKey="work-items" resource="WORK_CATALOG"`, columns:
  - `name` (string, i18n localized, sortable/searchable)
  - `workCategory` — reference column: `field="workCategory"`, `dataType='reference'`, `reference` descriptor from metadata (target `/api/work-categories`); render shows `row.workCategoryName`.
  - `unit` — reference column similarly (target `/api/measurement-units`); render shows `row.unitName`.
  - `active` (badge).
  Fetch reference metadata via the existing `/api/work-items/metadata` (the DataTable already consumes column metadata for reference filters); set `reference` on the two columns.
- `WorkItemFormSheet`: two `<select>`/combobox fields for `workCategoryId` (options from `/api/work-categories`) and `unitId` (options from `/api/measurement-units`), plus `nameRU`, `namePL`, `active`. Reuse the same options-fetch approach the ReferenceFilter uses (or a simple react-query list fetch). `DeleteWorkItemDialog` + local `ActiveBadge`.
- routing: lazy `WorkCatalogPage` + route `{ path: 'catalog/works', ... }`; interim `extra['/catalog/works'] = { resource:'WORK_CATALOG', operation:'READ' }`.
- i18n `workCatalog.*` (PL+RU parity): pageTitle; search.placeholder; actions.{create,retry}; list.empty; table.{name,workCategory,unit,active,actions}; badge.{active,inactive}; form.{titleCreate,titleEdit,descriptionCreate,descriptionEdit,workCategory,unit,nameRU,namePL,active,submitCreate,submitEdit,selectWorkCategory,selectUnit}; delete.{title,description}; toast.{createSuccess,updateSuccess,deleteSuccess}; errors.{loadFailed,network}; validation.{workCategoryRequired,unitRequired,nameMin,nameMax}. Plus `nav.workCatalog`.

## Testing Strategy
PBT n/a. Backend: `WorkItemControllerIntegrationTest` — seeds a WorkCategory + MeasurementUnit in-test (or uses existing seeded ones), then CRUD with FK ids, verifies rows expose FK ids + referenced names, i18n on `name`, filter by `workCategory.id`; `WorkCatalogResourceSeedIntegrationTest` — resource exists; grants ADMIN CRUD / MANAGER CREATE,READ,UPDATE / FOREMAN,WORKER,FINANCIER READ / CLIENT none; idempotency. Frontend: `WorkCatalogList.test.tsx` (rows render name/category/unit/active) + i18n parity. Run only affected classes/files.
