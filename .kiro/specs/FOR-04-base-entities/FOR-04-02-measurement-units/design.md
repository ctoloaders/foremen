# Design — FOR-04-02: Measurement Units dictionary

## Overview

`MeasurementUnit` is implemented as a generic full-CRUD managed resource mirroring
the `ROLES` stack (RoleEntity/RoleService/RoleController), minus the permission-matrix
extras and `system` flag, plus a `code`/`active` field set with i18n only on `name`.
No new framework code — the entity plugs into the existing `AdminController` /
`AdminService` / MapStruct mapper contracts (FOR-01) and the `@PermissionResource`
guard (FOR-03-08). Backend only; UI/menu are FOR-04-15.

## Layers (all under `com.foremen`, source `foremen-backend/src/main/java`)

### 1. Entity — `dao/model/MeasurementUnitEntity`
```java
@Entity @Table(name = "measurement_units")
@Getter @Setter @NoArgsConstructor
public class MeasurementUnitEntity extends BaseEntity {
    @Column(nullable = false, unique = true) private String code;
    @Column(name = "name_ru", nullable = false) private String nameRU;
    @Column(name = "name_pl", nullable = false) private String namePL;
    @Column(nullable = false) private boolean active = true;
}
```
`BaseEntity` supplies `id` + audit columns.

### 2. DAO — `dao/MeasurementUnitDao`
```java
@Repository
public interface MeasurementUnitDao extends AdminDao<MeasurementUnitEntity, Long> {}
```

### 3. Service models — `service/model`
- `MeasurementUnitServiceModel` (localized list model, `@Data`): `{Long id, String code, String name, boolean active}`
- `MeasurementUnitServiceExtendedModel` (record, all locales): `{Long id, String code, String nameRU, String namePL, boolean active}`

### 4. Service mapper — `service/model/mapper/MeasurementUnitServiceMapper`
```java
@Mapper(config = ForemenMapperConfig.class)
public interface MeasurementUnitServiceMapper extends ServiceToDaoMapper<
        MeasurementUnitEntity, MeasurementUnitServiceModel, MeasurementUnitServiceExtendedModel> {
    @Override default Set<String> getI18nSupportedProperties() { return Set.of("name"); }
    @Override @Mapping(target = "id", ignore = true) @Mapping(target = "code", ignore = true)
    void updateFields(MeasurementUnitServiceExtendedModel source, @MappingTarget MeasurementUnitEntity target);
}
```
`getI18nSupportedProperties()=Set.of("name")` drives i18n collapse on read and
locale resolution on sort/filter. `code` ignored on update → immutable.

### 5. Service — `service/MeasurementUnitService`
```java
@Service @RequiredArgsConstructor @Getter
public class MeasurementUnitService implements AdminService<
        MeasurementUnitServiceModel, MeasurementUnitServiceExtendedModel, MeasurementUnitEntity, Long> {
    private final MeasurementUnitDao dao;
    private final MeasurementUnitServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<MeasurementUnitEntity> daoModelClass = MeasurementUnitEntity.class;
}
```
Lombok getters satisfy all abstract `AdminService`/`ReadOnlyAdminService` methods.

### 6. Controller models — `controller/model` (records)
- `MeasurementUnitDtoModel`: `{Long id, String code, String name, boolean active}`
- `MeasurementUnitDtoExtendedModel`: `{Long id, String code, String nameRU, String namePL, boolean active}`
- `MeasurementUnitCreateRequest`: `{@NotBlank code, @NotBlank nameRU, @NotBlank namePL, Boolean active}`
- `MeasurementUnitUpdateRequest`: `{@NotBlank nameRU, @NotBlank namePL, Boolean active}` (no `code`)
- `MeasurementUnitCreateResponse` / `MeasurementUnitUpdateResponse`: `{Long id, String code, String nameRU, String namePL, boolean active}`

### 7. Controller mapper — `controller/model/mapper/MeasurementUnitControllerMapper`
```java
@Mapper(config = ForemenMapperConfig.class)
public interface MeasurementUnitControllerMapper extends ControllerToServiceMapper<
    MeasurementUnitServiceModel, MeasurementUnitServiceExtendedModel,
    MeasurementUnitDtoModel, MeasurementUnitDtoExtendedModel,
    MeasurementUnitCreateRequest, MeasurementUnitCreateResponse,
    MeasurementUnitUpdateRequest, MeasurementUnitUpdateResponse> {}
```
Ignore `id` on `toServiceExtendedModel(create)`; default `active` to true when the
create request omits it (map in the mapper or normalize in the entity default).

### 8. Controller — `controller/MeasurementUnitController`
```java
@RestController
@RequestMapping("/api/measurement-units")
@RequiredArgsConstructor
@PermissionResource("MEASUREMENT_UNITS")
public class MeasurementUnitController implements AdminController<
        MeasurementUnitServiceModel, MeasurementUnitServiceExtendedModel,
        MeasurementUnitDtoModel, MeasurementUnitDtoExtendedModel,
        MeasurementUnitEntity, Long,
        MeasurementUnitCreateRequest, MeasurementUnitCreateResponse,
        MeasurementUnitUpdateRequest, MeasurementUnitUpdateResponse> {
    private final MeasurementUnitService service;
    private final MeasurementUnitControllerMapper controllerMapper;
    @Override public ControllerToServiceMapper<...> getMapper() { return controllerMapper; }
    @Override public AdminService<...> getService() { return service; }
}
```
No CRUD method is overridden; `@PermissionResource` + inherited `@PermissionOperation`
form the guarded pairs, validated at startup by `PermissionAnnotationValidator`.

## Liquibase

New changesets, registered LAST in `changelog.xml` after `017`:

- `018-create-measurement-units.xml` — create table `measurement_units` (`id BIGSERIAL PK`,
  `code VARCHAR NOT NULL UNIQUE`, `name_ru`/`name_pl VARCHAR NOT NULL`,
  `active BOOLEAN NOT NULL DEFAULT true`, `created_date NOT NULL DEFAULT NOW()`,
  `created_by`, `updated_date`, `updated_by`). Guard: `<not><tableExists .../></not>` + `MARK_RAN`.
- `019-seed-measurement-units-resource.xml` — contains:
  - resource insert (`MEASUREMENT_UNITS`, RU/PL name+description), idempotent `sqlCheck` on code.
  - per-role grant changesets: ADMIN `CREATE,READ,UPDATE,DELETE`; MANAGER/FOREMAN/WORKER/FINANCIER `READ`; CLIENT omitted. Each idempotent (`sqlCheck expectedResult="0"` scoped by role+resource code), following the `015` multi-role pattern.
  - default-units seed changeset: insert `m2, mb, szt, kpl, godz, proc, m3` into `measurement_units` with RU/PL names + `active=true`, guarded `sqlCheck expectedResult="0" SELECT COUNT(*) FROM measurement_units`.

### Seed unit names (RU / PL)
| code | nameRU | namePL |
|------|--------|--------|
| m2   | м²     | m²     |
| mb   | пог. м | mb     |
| szt  | шт.    | szt.   |
| kpl  | компл. | kpl.   |
| godz | час    | godz.  |
| proc | %      | %      |
| m3   | м³     | m³     |

## Testing Strategy

PBT does not apply (no pure algorithmic function with a universal property here;
it's a standard CRUD resource + declarative seed). Two integration tests:

- `MeasurementUnitControllerIntegrationTest` (`controller/integration`, Testcontainers +
  MockMvc, mirror `RoleControllerIntegrationTest`): create → list → read → update → delete;
  i18n `name` resolves to RU with `Accept-Language: ru` and PL otherwise; update ignores `code`.
- `MeasurementUnitsResourceSeedIntegrationTest` (`dao/integration`, mirror
  `ProjectMembersResourceSeedIntegrationTest`): runs the real changelog, asserts the
  `MEASUREMENT_UNITS` resource row, exact per-role grants (ADMIN CRUD; MANAGER/FOREMAN/WORKER/FINANCIER READ; CLIENT none), the seven default unit codes, and idempotency on re-run.

Per the workspace rule, run only these classes with `--tests`, redirect to a temp log,
and read pass/fail from the JUnit XML; never the full suite.

## Frontend — Admin CRUD page

Mirrors the existing Roles admin page pattern, minus the permission-matrix tab
(units are a plain dictionary), plus the Users `active` badge. New feature folder
`foremen-frontend/src/features/measurement-units/`:

### Types — `types/index.ts`
- `MeasurementUnitDto` (list): `{ id: number; code: string; name: string; active: boolean }`
- `MeasurementUnitExtendedDto` (edit): `{ id: number; code: string; nameRU: string; namePL: string; active: boolean }`
- `MeasurementUnitCreateRequest`: `{ code: string; nameRU: string; namePL: string; active?: boolean }`
- `MeasurementUnitUpdateRequest`: `{ nameRU: string; namePL: string; active?: boolean }` (no `code`)
- `MeasurementUnitFormMode = 'create' | 'edit'`; `PaginatedResponse<T>` (reuse shared shape)

### API — `api/measurement-units-api.ts`
Thin functions over the shared `apiRequest` (`@/lib/api-client`), `BASE_URL='/api'`:
- `fetchMeasurementUnits({page,size,query?,sort?})` → builds the URL via the shared
  `buildFetchQuery` (from `@/components/data-table`) → `GET /api/measurement-units?...`
- `fetchMeasurementUnit(id)` → `GET /api/measurement-units/{id}` (extended DTO)
- `createMeasurementUnit(body)` → `POST`; `updateMeasurementUnit(id, body)` → `PUT`;
  `deleteMeasurementUnit(id)` → `DELETE`

### Query/mutation hooks — `api/query-hooks.ts`, `api/mutation-hooks.ts`
`measurementUnitKeys` factory (`all`/`lists`/`list`/`details`/`detail`); `useMeasurementUnit(id)`
(`enabled: id != null`); `useCreate|Update|DeleteMeasurementUnit` invalidating
`measurementUnitKeys.lists()` on success (update also invalidates `detail(id)`).

### Schema — `schemas/measurement-unit-schema.ts`
zod: `measurementUnitCreateSchema` (`code` non-blank + pattern, `nameRU`/`namePL` min/max),
`measurementUnitUpdateSchema = createSchema.omit({ code: true })`. Messages are i18n keys.

### Components
- `MeasurementUnitsPage.tsx` (default export): title `t('measurementUnits.pageTitle')`;
  owns form-sheet + delete-dialog state; renders the list + `<MeasurementUnitFormSheet>` +
  `<DeleteMeasurementUnitDialog>`; success toasts by mode. No tabs.
- `components/MeasurementUnitsList.tsx`: `ColumnConfig<MeasurementUnitDto>[]` = code (string,
  sortable/filterable/searchable), name (string, sortable/filterable/searchable),
  active (boolean, render via a localized `ActiveBadge`); `usePermission('MEASUREMENT_UNITS', …)`
  for `canCreate/canUpdate/canDelete`; memoized `fetchFn` adapter (adds `first`/`last`);
  gated Create button + `rowActions` (edit/delete); `<DataTable entityKey="measurement-units"
  resource="MEASUREMENT_UNITS" defaultPageSize={10} pageSizeOptions={[10,25,50]} .../>`.
- `components/MeasurementUnitFormSheet.tsx`: react-hook-form + zodResolver; create=all fields,
  edit=prefill from `useMeasurementUnit`, `code` read-only; maps to create/update bodies;
  inline localized errors; delegates success to the page.
- `components/DeleteMeasurementUnitDialog.tsx`: AlertDialog confirm → `useDeleteMeasurementUnit`;
  success toast; error surfaced.
- Reuse or mirror `ActiveBadge` (Users) rendering `active` → localized Active/Inactive.

### Routing & guard
- `src/app/router.tsx`: add `const MeasurementUnitsPage = React.lazy(() => import('@/features/measurement-units/MeasurementUnitsPage'))` and a child route `{ path: 'measurement-units', element: <SuspenseWrapper><MeasurementUnitsPage/></SuspenseWrapper> }` in the guarded children block.
- `src/config/route-permissions.ts`: add interim `extra['/measurement-units'] = { resource: 'MEASUREMENT_UNITS', operation: 'READ' }` (superseded by the FOR-04-15 nav entry later).

### i18n — `measurementUnits.*` in `pl.json` + `ru.json` (parity, PL+RU)
Keys: `pageTitle`; `search.placeholder`; `actions.{create,retry}`; `list.empty`;
`table.{code,name,active,actions}`; `badge.{active,inactive}`;
`form.{titleCreate,titleEdit,descriptionCreate,descriptionEdit,code,nameRU,namePL,active,submitCreate,submitEdit}`;
`delete.{title,description}`; `toast.{createSuccess,updateSuccess,deleteSuccess}`;
`errors.{loadFailed,network}`; `validation.{codeRequired,codePattern,nameMin,nameMax}`.
Also add `nav.measurementUnits` label (used later by FOR-04-15). No new backend i18n
fields — the entity's only i18n field is `name` (`nameRU`/`namePL`), already covered.

### Frontend testing
- `__tests__/MeasurementUnitsList.test.tsx`: mirror `RolesListTab.test.tsx` harness (mock
  react-i18next `t`, mock `useBreakpoint`, `QueryClientProvider` retry:false, seed auth store
  permissions, mock `fetch` with a Spring page). Assert rows render code + resolved name + the
  `active` badge (Active/Inactive), permission-gated create/edit/delete, and the empty state.
- i18n parity check for the `measurementUnits.*` namespace across `pl.json`/`ru.json`.
- Verify with `npx tsc -b --noEmit` and run only the new test file(s) with vitest (temp log);
  do not run the full frontend suite.

