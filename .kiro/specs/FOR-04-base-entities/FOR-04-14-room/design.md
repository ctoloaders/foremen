# Design Document

FOR-04-14: Room entity + project-scoped dimensions, polygon geometry, per-wall openings, and calculated-vs-manual metrics

## Overview

FOR-04-14 introduces the `Room` — a **project-scoped child** of the FOR-04-13
`Project` — as a real `rooms` table plus its full backend/frontend surface:
entity + `MeasureSource` enum, an idempotent Liquibase table-creation changeset,
generic project-scoped CRUD, a deterministic geometry **calculation engine**, ABAC
`ROOMS` resource + role grants, and a first admin page on the shared `DataTable`
with a read-only SVG plan preview.

A room belongs to exactly one project (`project_id` FK, NOT NULL) and references
one room type (`room_type_id` FK to the FOR-04-05 `room_types` dictionary). The
localized room-type name is shown via the referenced `RoomType`, so `Room` is an
**operational** entity: it carries **no** `nameRU`/`namePL` pair, only an optional
non-localized `label` to distinguish rooms of the same type.

The room's measurement data can be produced two ways, and every derived metric
records which way it came from:

1. **Calculated from geometry.** The room's shape is a polygon: an ordered list of
   `vertices`, `walls` (edges), and per-wall `openings` (doors/windows with a
   count and per-unit height/width). The backend computes `floorArea` (shoelace),
   `perimeter` (summed edge lengths), `wallArea` (`perimeter × ceilingHeight −
   doorArea − windowArea − wallGap × ceilingHeight`, floored at 0), `doorArea`,
   and `windowArea` (summed `count × height × width`), stamping each result with
   source `CALCULATED`.
2. **Manual.** With no geometry drawn, areas/perimeter can be typed directly and
   are stamped `MANUAL`.

Each of `floorArea`, `wallArea`, `perimeter`, `doorArea`, `windowArea` is a
**MeasureValue** — the numeric column plus a companion `*_source` enum column
(`CALCULATED` | `MANUAL`). Geometry is authoritative: when present, it overwrites
those five metrics with calculated values; when absent, supplied values are kept
as `MANUAL`.

The `geometry` is stored as **structured JSON** (`jsonb`) and rendered as SVG by
the frontend. This model is deliberately reusable by a later floor-plan
recognition feature, which will emit the same JSON shape.

**Out of scope** (deferred to FOR-05): an interactive SVG polygon drawing/annotation
editor. This spec ships the complete backend model + calculation engine + source
flags, a **basic** structured create/edit form (numeric + per-wall opening entry
plus manual mode), and a **read-only** SVG plan preview. The `/rooms` menu entry
arrives in FOR-04-15; until then an interim `ROOMS`/`READ` route guard protects
`/rooms`.

### Research summary

Findings from reading the existing codebase that inform this design:

- **CRUD contracts (FOR-01).** `AdminController<...>` (interface with default REST
  methods) is implemented by a thin `@RestController` (e.g. `WorkPriceController`)
  supplying `getMapper()` + `getService()`. Each default method carries a
  method-level `@PermissionOperation("CREATE"|"READ"|"UPDATE"|"DELETE")`, combined
  with a class-level `@PermissionResource(...)`. Rooms use this generic path with
  no custom create (unlike FOR-04-13 Project).
- **Project scoping (FOR-03-04 / FOR-03-04a).** `ProjectScopedService<...> extends
  AdminService<...>` supplies the LIST filter (`addRequiredQuery()`) and by-id
  access assertions as `default` methods; the sole mandatory per-entity override
  is `getProjectIdPath()`. For a child of `projects`, the path is the dotted
  association `"project.id"`. ADMIN bypasses both filter and assertion.
  `allowedProjectIds(userId)` wires to `ProjectAccessCache.get(userId)`.
- **JSON columns.** `AuditLogEntity` maps a JSON column with
  `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "...", columnDefinition = "jsonb")`
  over a `String` field, serializing/deserializing via Jackson in the mapper
  (`AuditServiceMapper.jsonStringToMap`). This design maps `geometry` the same way
  but binds it to a typed `RoomGeometry` POJO via `@JdbcTypeCode(SqlTypes.JSON)`.
- **Reference dictionaries (FOR-04-05).** `RoomTypeEntity @Table("room_types")`
  extends `BaseEntity` with `code`, `nameRU`, `namePL`, `active`; resource
  `ROOM_TYPES`. Rooms reference it by `@ManyToOne` and advertise a reference
  descriptor so the FOR-04-01 filter dropdown works.
- **Filter grammar & metadata (FOR-04-01).** `SpecificationBuilder.resolvePath`
  navigates dot paths and joins for `@ManyToOne`; `EntityMetadataResolver` emits a
  `ReferenceInfo(targetResource, optionsPath, labelField, labelI18n, idPath)` for
  `@ManyToOne`/`@OneToOne` fields with `idPath = fieldName + ".id"`. Rooms get
  descriptors for `project.id` (target `projects`) and `roomType.id` (target
  `room_types`).
- **Projects table (FOR-04-13).** `040-create-projects.xml` created `projects`;
  `041-seed-projects-resource.xml` is the current changelog tail. Rooms add a FK
  to `projects(id)`.
- **BaseEntity audit columns.** Physical columns are `created_date`, `created_by`,
  `updated_date`, `updated_by` (see `038-create-work-prices.xml`).
- **Liquibase idempotency.** Create-table changesets guard with
  `<preConditions onFail="MARK_RAN"><not><tableExists.../></not></preConditions>`;
  seed changesets guard with `<sqlCheck expectedResult="0">SELECT COUNT(*)...`.
  New files register **last** in `changelog.xml`; the tail is
  `041-seed-projects-resource.xml`, so new files use `042+`.
- **Frontend.** A feature folder (`features/work-prices/`) exposes a `*Page`
  composing a DataTable-backed `*List` (columns + `usePermission('RESOURCE','OP')`
  gating + `fetchFn` adapter), a form sheet, and a delete dialog. The API module
  serializes list params through the shared `buildFetchQuery`. Routes register in
  `app/router.tsx`; the interim guard is a `route-permissions.ts` entry consumed
  by `PermissionGuard`.

## Architecture

```mermaid
flowchart TB
  subgraph FE[Frontend]
    RP[RoomsPage]
    RL[RoomsList - DataTable]
    RF[RoomFormSheet - create/edit]
    OE[Per-wall openings editor]
    MM[Manual metrics mode]
    SP[RoomPlanPreview - read-only SVG]
    RG[PermissionGuard + route-permissions]
    RP --> RL
    RP --> RF
    RF --> OE
    RF --> MM
    RL --> SP
  end

  subgraph BE[Backend]
    RC[RoomController @PermissionResource ROOMS]
    RS[RoomService implements ProjectScopedService]
    RCE[RoomCalculationService - geometry -> metrics]
    SB[SpecificationBuilder - ManyToOne joins]
    PInt[PermissionInterceptor + ForemenPermissionEvaluator]
    PAC[ProjectAccessCache FOR-03-04]
  end

  subgraph DB[(PostgreSQL)]
    RT[(rooms)]
    PT[(projects)]
    RTT[(room_types)]
    PMT[(project_members)]
    RES[(resources / role_resources / role_resource_operations)]
  end

  RL -- GET /api/rooms search/sort/filter --> PInt --> RC --> RS --> SB --> RT
  RS -- allowedProjectIds --> PAC --> PMT
  RF -- POST/PUT /api/rooms --> PInt --> RC --> RS --> RCE
  RS --> RT
  RT -- project_id FK --> PT
  RT -- room_type_id FK --> RTT
  RC -. reads .-> RES
```

### Request routing and guarding

| Endpoint | Handler | Resolves to | Notes |
|---|---|---|---|
| `POST /api/rooms` | inherited `create` | `ROOMS`/`CREATE` | project-scoped; runs calculation engine before persist |
| `GET /api/rooms` | inherited `find` | `ROOMS`/`READ` | membership-filtered for non-ADMIN via `project.id` |
| `GET /api/rooms/{id}` | inherited `findById` | `ROOMS`/`READ` | `assertProjectAccess` |
| `PUT /api/rooms/{id}` | inherited `update` | `ROOMS`/`UPDATE` | `assertProjectAccess`; re-runs calculation engine |
| `DELETE /api/rooms/{id}` | inherited `deleteById` | `ROOMS`/`DELETE` | `assertProjectAccess`; ADMIN/MANAGER only grant |
| `GET /api/rooms/count` | inherited `count` | `ROOMS`/`READ` | membership-filtered |
| `GET /api/rooms/metadata` | inherited `metadata` | `ROOMS`/`READ` | emits reference descriptors for `project.id`, `roomType.id` |

The calculation engine hook lives at the **service** layer (see below), so both
generic create and update produce consistent, geometry-authoritative metrics.

## Components and Interfaces

### Backend

**`MeasureSource`** (enum): `CALCULATED, MANUAL` in exactly that order.

**`RoomGeometry`** (JSON-bound POJO, stored in `rooms.geometry jsonb`):

```
RoomGeometry {
  vertices: [ { x: double, y: double }, ... ]     // ordered, >= 3 for a valid polygon
  walls: [ Wall, ... ]                             // wall i connects vertex i -> (i+1) mod n
}
Wall {
  wallGap: double?      // mb, "missing wall" length on this edge
  finishGap: double?    // mb, "missing finish" length on this edge
  openings: [ Opening, ... ]
}
Opening {
  type: "DOOR" | "WINDOW"
  count: int   // szt, > 0
  height: double  // mb, > 0
  width: double   // mb, > 0
}
```

**`RoomEntity extends BaseEntity`** — mapped to `rooms` (see Data Models):

- `@ManyToOne(optional = false) project` → `project_id` (FK `projects`), `@ManyToOne(optional = false) roomType` → `room_type_id` (FK `room_types`).
- `label` (String, nullable, 255). No `nameRU`/`namePL`.
- `geometry` — `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "geometry", columnDefinition = "jsonb")` over a `RoomGeometry` field.
- Metric numeric columns and companion `*_source` (`@Enumerated(EnumType.STRING)`) columns for the five MeasureValues.

**`RoomDao extends AdminDao<RoomEntity, Long>`** — standard JPA/Specification DAO.

**`RoomCalculationService`** — the deterministic, side-effect-free engine. Given a
`RoomGeometry` (and the room's `ceilingHeight`) it returns a `RoomMetrics` result:

```java
RoomMetrics calculate(RoomGeometry geometry, BigDecimal ceilingHeight);
// floorArea  = |shoelace(vertices)|                         (m2)
// perimeter  = Σ edgeLength(vertex_i, vertex_{i+1})          (mb)
// doorArea   = Σ over DOOR openings   count * height * width (m2)
// windowArea = Σ over WINDOW openings count * height * width (m2)
// doorCount  = Σ DOOR counts,  windowCount = Σ WINDOW counts (szt)
// wallGap    = Σ wall.wallGap,  finishGap  = Σ wall.finishGap(mb)
// wallArea   = max(0, perimeter*ceilingHeight - doorArea - windowArea - wallGap*ceilingHeight) (m2)
```

Rounding: metrics rounded to 2 decimals (`NUMERIC(12,2)`) with `HALF_UP`.

**`RoomService implements ProjectScopedService<RoomServiceModel,
RoomServiceExtendedModel, RoomEntity, Long>`** — supplies CRUD plumbing
(`getDao`, `getMapper`, `getEntityManager`, `getDaoModelClass`, `getAuditLogDao`),
`allowedProjectIds(userId) → projectAccessCache.get(userId)`, and the anchor
override `getProjectIdPath() → "project.id"`. It adds a **pre-persist normalization
step** applied in both create and update:

1. Resolve `project`/`roomType` references (404 with a field-identifying error when missing).
2. Validate geometry when present: polygon has ≥ 3 vertices; every opening has `type ∈ {DOOR, WINDOW}`, `count > 0`, `height > 0`, `width > 0`. Reject (BAD_REQUEST) with a field-identifying code otherwise.
3. **If geometry present** → call `RoomCalculationService.calculate(...)`, write `floorArea/perimeter/wallArea/doorArea/windowArea` from the result, set each of the five sources to `CALCULATED`, and set `doorCount/windowCount/wallGap/finishGap` from the result (geometry authoritative — Req 4.2).
4. **If geometry absent** → keep any directly supplied `floorArea/wallArea/perimeter/doorArea/windowArea` and set each supplied one's source to `MANUAL` (Req 4.1); validate each numeric within `[0, 9999999999.99]` and counts non-negative.

**`RoomController` (`@RestController @RequestMapping("/api/rooms")
@PermissionResource("ROOMS")`)** implements `AdminController<...>` (inherits
list/read/create/update/delete/count/metadata/i18n, each keeping its
`@PermissionOperation`) and supplies `getMapper()` + `getService()`. No custom
endpoints.

**`EntityMetadataResolver`** already emits `ReferenceInfo` for `@ManyToOne` fields,
so `project` and `roomType` automatically advertise `project.id` (target
`projects`, options `/api/projects`) and `roomType.id` (target `room_types`,
options `/api/room-types`, label = localized name). No resolver change is required
beyond confirming both fields are mapped as `@ManyToOne`.

**DTO / request / response models** (records under `controller/model`, mirroring
the FOR-04 pattern):

- `RoomCreateRequest { @NotNull projectId, @NotNull roomTypeId, label?, ceilingHeight?, internalCorners?, geometry?: RoomGeometry, floorArea?, wallArea?, perimeter?, doorArea?, windowArea? }` (manual metrics only honored when geometry is absent).
- `RoomUpdateRequest` — same shape as create.
- `RoomDtoModel` / `RoomDtoExtendedModel` (list/read) expose `id`, `projectId`, `projectName`, `roomTypeId`, `roomTypeName` (localized), `label`, `ceilingHeight`, `internalCorners`, `doorCount`, `windowCount`, `wallGap`, `finishGap`, `geometry`, and each of the five MeasureValues as `{ value, source }`.
- `MeasureValueDto { value: BigDecimal, source: MeasureSource }`.

### Frontend

Feature folder `features/rooms/`:

- `RoomsPage.tsx` — composes `RoomsList`, `RoomFormSheet` (create/edit), `DeleteRoomDialog` (mirrors `WorkPricesPage`).
- `components/RoomsList.tsx` — `DataTable<RoomDto>` `resource="ROOMS"`, `entityKey="rooms"`, columns `project`, `roomType`, `label`, `floorArea` (+ source badge), `wallArea` (+ source badge), `perimeter` (+ source badge), `ceilingHeight`; server-side search/sort/filter/pagination; reference filters for `project.id` and `roomType.id` (FOR-04-01); gates create/edit/delete via `usePermission('ROOMS', ...)`; `fetchFn` adapter → `fetchRooms`.
- `components/MeasureValueCell.tsx` — renders a `{ value, source }` metric: the number plus a small localized "Calculated"/"Manual" badge.
- `components/RoomFormSheet.tsx` — create/edit form: project selector (reference to `PROJECTS`), room-type selector (reference to `ROOM_TYPES`), optional `label`, `ceilingHeight`, `internalCorners`, a per-wall **openings editor** (add openings with `type`/`count`/`height`/`width`), and a **manual mode** toggle exposing direct `floorArea`/`wallArea`/`perimeter`/`doorArea`/`windowArea` inputs (disabled when geometry present, where those show read-only "Calculated" values).
- `components/RoomOpeningsEditor.tsx` — per-wall list of openings with quick count entry and per-unit dimensions.
- `components/RoomPlanPreview.tsx` — **read-only** SVG: renders the polygon path from `geometry.vertices` and marks openings on wall edges. No editing in this spec.
- `components/RoomSourceBadge.tsx` — localized `CALCULATED` / `MANUAL` badge.
- `components/DeleteRoomDialog.tsx` — confirm + `useDeleteRoom`.
- `api/rooms-api.ts` — `fetchRooms` (via `buildFetchQuery`), `fetchRoom`, `createRoom`, `updateRoom`, `deleteRoom`.
- `schemas/` + `types/` — zod schema (project + room type required; openings positive; geometry vertices ≥ 3 when present; manual metrics in range) and DTO types incl. `RoomGeometry` and `MeasureValue`.

Routing: register `path: 'rooms'` in `app/router.tsx`; add interim
`'/rooms': { resource: 'ROOMS', operation: 'READ' }` to `route-permissions.ts`
(Req 8.13).

## Data Models

### `rooms` table (Liquibase `042-create-rooms.xml`)

| Column | Type | Constraints |
|---|---|---|
| `id` | `BIGSERIAL` | PK, not null |
| `project_id` | `BIGINT` | not null, FK → `projects(id)` |
| `room_type_id` | `BIGINT` | not null, FK → `room_types(id)` |
| `label` | `VARCHAR(255)` | null |
| `geometry` | `jsonb` | null |
| `ceiling_height` | `NUMERIC(12,2)` | null |
| `internal_corners` | `INTEGER` | null |
| `door_count` | `INTEGER` | null |
| `window_count` | `INTEGER` | null |
| `door_height` | `NUMERIC(12,2)` | null |
| `door_width` | `NUMERIC(12,2)` | null |
| `window_height` | `NUMERIC(12,2)` | null |
| `window_width` | `NUMERIC(12,2)` | null |
| `wall_gap` | `NUMERIC(12,2)` | null |
| `finish_gap` | `NUMERIC(12,2)` | null |
| `floor_area` | `NUMERIC(12,2)` | null |
| `floor_area_source` | `VARCHAR(20)` | null |
| `wall_area` | `NUMERIC(12,2)` | null |
| `wall_area_source` | `VARCHAR(20)` | null |
| `perimeter` | `NUMERIC(12,2)` | null |
| `perimeter_source` | `VARCHAR(20)` | null |
| `door_area` | `NUMERIC(12,2)` | null |
| `door_area_source` | `VARCHAR(20)` | null |
| `window_area` | `NUMERIC(12,2)` | null |
| `window_area_source` | `VARCHAR(20)` | null |
| `created_date` | `TIMESTAMP` | not null, default `NOW()` |
| `created_by` | `VARCHAR(255)` | null |
| `updated_date` | `TIMESTAMP` | null |
| `updated_by` | `VARCHAR(255)` | null |

Foreign keys `fk_rooms_project` (`project_id` → `projects.id`) and
`fk_rooms_room_type` (`room_type_id` → `room_types.id`). Guarded by
`<preConditions onFail="MARK_RAN"><not><tableExists tableName="rooms"/></not></preConditions>`
(Req 1.7, 1.8). Units are documented in i18n labels (m² for `*_area`; mb for
`perimeter`/gaps/heights/widths; szt for the integer counts).

### `RoomEntity` field mapping (sketch)

```java
@Entity @Table(name = "rooms")
public class RoomEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomTypeEntity roomType;

    @Column(length = 255) private String label;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "geometry", columnDefinition = "jsonb")
    private RoomGeometry geometry;

    @Column(name = "ceiling_height", precision = 12, scale = 2) private BigDecimal ceilingHeight;
    @Column(name = "internal_corners") private Integer internalCorners;
    @Column(name = "door_count") private Integer doorCount;
    @Column(name = "window_count") private Integer windowCount;
    @Column(name = "door_height", precision = 12, scale = 2) private BigDecimal doorHeight;
    @Column(name = "door_width", precision = 12, scale = 2) private BigDecimal doorWidth;
    @Column(name = "window_height", precision = 12, scale = 2) private BigDecimal windowHeight;
    @Column(name = "window_width", precision = 12, scale = 2) private BigDecimal windowWidth;
    @Column(name = "wall_gap", precision = 12, scale = 2) private BigDecimal wallGap;
    @Column(name = "finish_gap", precision = 12, scale = 2) private BigDecimal finishGap;

    @Column(name = "floor_area", precision = 12, scale = 2) private BigDecimal floorArea;
    @Enumerated(EnumType.STRING) @Column(name = "floor_area_source", length = 20) private MeasureSource floorAreaSource;
    @Column(name = "wall_area", precision = 12, scale = 2) private BigDecimal wallArea;
    @Enumerated(EnumType.STRING) @Column(name = "wall_area_source", length = 20) private MeasureSource wallAreaSource;
    @Column(precision = 12, scale = 2) private BigDecimal perimeter;
    @Enumerated(EnumType.STRING) @Column(name = "perimeter_source", length = 20) private MeasureSource perimeterSource;
    @Column(name = "door_area", precision = 12, scale = 2) private BigDecimal doorArea;
    @Enumerated(EnumType.STRING) @Column(name = "door_area_source", length = 20) private MeasureSource doorAreaSource;
    @Column(name = "window_area", precision = 12, scale = 2) private BigDecimal windowArea;
    @Enumerated(EnumType.STRING) @Column(name = "window_area_source", length = 20) private MeasureSource windowAreaSource;
}
```

### `ROOMS` ABAC seed (Liquibase `043-seed-rooms-resource.xml`)

Resource row `code='ROOMS'` with non-null `name_ru` ("Помещения") / `name_pl`
("Pomieszczenia") / `description_ru` / `description_pl`. Grants:

| Role | Operations |
|---|---|
| ADMIN | CREATE, READ, UPDATE, DELETE |
| MANAGER | CREATE, READ, UPDATE, DELETE |
| FOREMAN | READ, UPDATE |
| WORKER | READ |
| FINANCIER | READ |
| CLIENT | READ |

CREATE and DELETE are granted to ADMIN and MANAGER only (Req 6.5). Each grant is
idempotent via `<preConditions onFail="MARK_RAN"><sqlCheck expectedResult="0">`
counting the existing `role_resources`/`role_resource_operations` row. Registered
last in `changelog.xml` after `041-seed-projects-resource.xml` (Req 6.7).

## Error Handling

| Condition | Status | Message code |
|---|---|---|
| Missing `projectId` reference | 404 | `error.entity.not.found` |
| Missing `roomTypeId` reference | 404 | `error.entity.not.found` |
| Geometry polygon with < 3 vertices | 400 | `error.room.geometry.invalid` |
| Opening with bad type/count/height/width | 400 | `error.room.opening.invalid` |
| Metric numeric out of `[0, 9999999999.99]` | 400 | `error.room.metric.range` |
| Negative count (`doorCount`/`windowCount`/`internalCorners`) | 400 | `error.room.count.negative` |
| Invalid `MeasureSource` value | 400 | `error.room.source.invalid` |
| Non-member by-id read/update/delete (non-ADMIN) | 403/404 | per `ProjectScopedService.assertProjectAccess` |

All validation failures persist nothing (single transaction rolls back).

## Correctness Properties

Property tests (jqwik, ≥ 100 iterations, one class per property, tag comment
`Feature: FOR-04-14-room, Property {n}`):

- **Property 1: Shoelace floor area.** For a generated simple polygon, `floorArea` equals the absolute shoelace area (within rounding tolerance) and is flagged `CALCULATED`. *(Req 3.1, 7.1)*
- **Property 2: Perimeter is summed edge lengths.** For a generated polygon, `perimeter` equals the sum of Euclidean edge lengths, flagged `CALCULATED`. *(Req 3.2, 7.1)*
- **Property 3: Opening totals.** For generated openings, `doorArea`/`windowArea` equal the summed `count × height × width` per type, and `doorCount`/`windowCount` equal the summed counts. *(Req 3.4, 7.1)*
- **Property 4: Wall area is non-negative and formula-consistent.** `wallArea = max(0, perimeter × height − doorArea − windowArea − wallGap × height)`. *(Req 3.3)*
- **Property 5: Geometry authoritative over manual.** When geometry is present, any supplied manual value for the five metrics is overwritten by the calculated value with source `CALCULATED`. *(Req 4.2)*
- **Property 6: Manual source stamping.** When geometry is absent, each supplied metric is persisted with source `MANUAL`. *(Req 4.1)*
- **Property 7: Determinism.** Two calculations over the same geometry produce identical metrics. *(Req 3.5)*
- **Property 8: Membership list filter.** For generated membership graphs, a non-ADMIN LIST returns exactly the rooms whose owning project the caller is a member of. *(Req 5.3, 7.5)*

## Testing Strategy

- **Unit / property (jqwik):** `RoomCalculationService` (Properties 1–7); `RoomService` normalization (source stamping, geometry-authoritative overwrite, validation rejections); `MeasureSourceEnumTest`.
- **Integration (Testcontainers):** `RoomCrudIT` (read/update/delete lifecycle, reference filters on `project.id`/`roomType.id`), `RoomGeometryIT` (calculated metrics persisted with `CALCULATED`; invalid geometry/opening rejected), `RoomManualMetricsIT` (manual sources; geometry-authoritative override), `RoomScopingIT` (non-ADMIN membership LIST filter, ADMIN bypass, non-member by-id denial — Property 8), `RoomsSeedIT` (exactly one `ROOMS` resource, exact grant matrix, idempotent re-apply), and a clean-startup annotation smoke test (`RoomController` starts under `PermissionAnnotationValidator`).
- **Frontend (Vitest + RTL):** `RoomsList.test.tsx` (columns incl. source badges; row count == records; empty state; permission gating), `RoomFormSheet.test.tsx` (per-wall opening entry updates state; manual mode direct input; geometry mode read-only derived values), `RoomPlanPreview.test.tsx` (renders polygon path from geometry JSON), `rooms.i18n.test.ts` (PL/RU key parity incl. metric labels + units, source-flag labels, opening types).
- Per the workspace test-execution standard, run only the affected test classes with `--tests`, redirect to a temp log, and read the JUnit XML for pass/fail; never run the full suite unless explicitly requested.
