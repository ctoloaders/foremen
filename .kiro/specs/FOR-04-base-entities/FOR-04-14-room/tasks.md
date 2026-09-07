# Implementation Plan — FOR-04-14: Room entity + geometry calculation engine + project-scoped CRUD + admin UI

## Overview

Convert the FOR-04-14 design into incremental, test-driven coding steps. Order:
backend foundation (enum/geometry model/entity/table) → calculation engine →
DAO/service normalization/controller → metadata & reference filtering → ABAC seed
→ integration tests → frontend feature → UI tests → test-cases.md. Each step builds
on the previous ones and wires into the running application with no orphaned code.
Backend is Java (Spring Boot / JPA / Liquibase / jqwik / Testcontainers); frontend
is TypeScript/React (Vitest + RTL). Per the workspace test-execution standard, run
only the affected test classes with `--tests` and read the JUnit XML for pass/fail;
never run the full suite unless explicitly requested.

## Tasks

- [x] 1. Backend foundation: enum, geometry model, entity, table changeset
  - [x] 1.1 Create `MeasureSource` enum
    - Define exactly `CALCULATED, MANUAL` in that declaration order and no other values
    - _Requirements: 1.6_
  - [x] 1.2 Write `MeasureSourceEnumTest`
    - Assert `values()` equals the two values in declaration order
    - _Requirements: 1.6_
  - [x] 1.3 Create the `RoomGeometry` JSON-bound model
    - POJO `RoomGeometry { List<Vertex> vertices; List<Wall> walls }`, `Vertex { double x, y }`, `Wall { Double wallGap; Double finishGap; List<Opening> openings }`, `Opening { OpeningType type; int count; double height; double width }`, `OpeningType { DOOR, WINDOW }`; Jackson-serializable
    - _Requirements: 2.1, 2.2_
  - [x] 1.4 Create `RoomEntity extends BaseEntity`
    - Map `rooms` with `@ManyToOne(optional=false) project` (`project_id`), `@ManyToOne(optional=false) roomType` (`room_type_id`), `label` (255, nullable), `geometry` (`@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb")` over `RoomGeometry`), numeric metrics (`ceilingHeight`, `internalCorners`, `doorCount`, `windowCount`, `doorHeight`, `doorWidth`, `windowHeight`, `windowWidth`, `wallGap`, `finishGap`, `floorArea`, `wallArea`, `perimeter`, `doorArea`, `windowArea`), and the five `*_source` `@Enumerated(EnumType.STRING)` `MeasureSource` columns
    - No `nameRU`/`namePL`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_
  - [x] 1.5 Create Liquibase `042-create-rooms.xml` and register it in `changelog.xml`
    - Create `rooms` with all columns from Data Models (incl. `geometry jsonb`, the five `*_source VARCHAR(20)`, the four audit columns), `project_id BIGINT NOT NULL` FK `fk_rooms_project → projects(id)`, `room_type_id BIGINT NOT NULL` FK `fk_rooms_room_type → room_types(id)`
    - Idempotent: `<preConditions onFail="MARK_RAN"><not><tableExists tableName="rooms"/></not></preConditions>`
    - Register the file last, after `041-seed-projects-resource.xml`
    - _Requirements: 1.7, 1.8, 6.7_

- [x] 2. Checkpoint — backend compiles
  - Run `./gradlew compileJava compileTestJava` (redirect to temp log); ensure the enum, geometry model, entity, and changeset compile. Use `getDiagnostics` first for fast per-file checks. Ask the user if questions arise.
  - _Requirements: 1.1, 1.6_

- [x] 3. Calculation engine
  - [x] 3.1 Create `RoomCalculationService` + `RoomMetrics` result
    - `calculate(RoomGeometry, BigDecimal ceilingHeight) → RoomMetrics`: `floorArea` = |shoelace(vertices)|; `perimeter` = Σ edge lengths; `doorArea`/`windowArea` = Σ `count×height×width` per opening type; `doorCount`/`windowCount` = Σ counts; `wallGap`/`finishGap` = Σ per-wall gaps; `wallArea` = `max(0, perimeter×height − doorArea − windowArea − wallGap×height)`; round to 2 decimals HALF_UP; deterministic & side-effect free
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 2.5_
  - [x] 3.2 Write property test P1 — shoelace floor area
    - **Property 1: floorArea equals absolute shoelace area, flagged CALCULATED**
    - jqwik, min 100 iterations; class `RoomFloorAreaPropertyTest`; tag `Feature: FOR-04-14-room, Property 1`
    - **Validates: Requirements 3.1, 7.1**
  - [x] 3.3 Write property test P2 — perimeter
    - **Property 2: perimeter equals summed Euclidean edge lengths, flagged CALCULATED**
    - jqwik, min 100 iterations; class `RoomPerimeterPropertyTest`
    - **Validates: Requirements 3.2, 7.1**
  - [x] 3.4 Write property test P3 — opening totals
    - **Property 3: doorArea/windowArea = Σ count×height×width per type; doorCount/windowCount = Σ counts**
    - jqwik, min 100 iterations; class `RoomOpeningTotalsPropertyTest`
    - **Validates: Requirements 3.4, 7.1**
  - [x] 3.5 Write property test P4 — wall area formula
    - **Property 4: wallArea = max(0, perimeter×height − doorArea − windowArea − wallGap×height)**
    - jqwik, min 100 iterations; class `RoomWallAreaPropertyTest`
    - **Validates: Requirements 3.3**
  - [x] 3.6 Write property test P7 — determinism
    - **Property 7: two calculations over the same geometry produce identical metrics**
    - jqwik, min 100 iterations; class `RoomCalculationDeterminismPropertyTest`
    - **Validates: Requirements 3.5**

- [x] 4. DAO, models, service normalization
  - [x] 4.1 Create `RoomDao extends AdminDao<RoomEntity, Long>`
    - Standard JPA/Specification DAO
    - _Requirements: 5.1_
  - [x] 4.2 Define request/response/service/DTO models
    - `RoomCreateRequest`/`RoomUpdateRequest` (`@NotNull projectId`, `@NotNull roomTypeId`, `label?`, `ceilingHeight?`, `internalCorners?`, `geometry?`, manual `floorArea?/wallArea?/perimeter?/doorArea?/windowArea?`); `RoomServiceModel`/`RoomServiceExtendedModel`; `RoomDtoModel`/`RoomDtoExtendedModel` exposing `projectId/projectName`, `roomTypeId/roomTypeName` (localized), `label`, base numerics, `geometry`, and five `MeasureValueDto{value, source}`; MapStruct mapper(s)
    - _Requirements: 4.3, 5.1_
  - [x] 4.3 Create `RoomService implements ProjectScopedService<...>`
    - Supply `getDao`/`getMapper`/`getEntityManager`/`getDaoModelClass`/`getAuditLogDao`, `allowedProjectIds(userId) → projectAccessCache.get(userId)`, and `getProjectIdPath() → "project.id"`
    - _Requirements: 5.2, 5.3, 5.4_
  - [x] 4.4 Implement pre-persist normalization (create + update)
    - Resolve `project`/`roomType` (404 field-identifying when missing); validate geometry when present (≥ 3 vertices, each opening `type∈{DOOR,WINDOW}`, `count>0`, `height>0`, `width>0`) else 400; if geometry present → run `RoomCalculationService`, write the five metrics + counts + gaps and stamp the five sources `CALCULATED` (geometry authoritative); if geometry absent → keep supplied manual metrics and stamp their sources `MANUAL`; validate numerics in `[0, 9999999999.99]` and counts non-negative; reject invalid `MeasureSource`
    - _Requirements: 2.3, 2.4, 4.1, 4.2, 4.4, 4.5, 5.6_
  - [x] 4.5 Write property test P5 — geometry authoritative
    - **Property 5: with geometry present, supplied manual values for the five metrics are overwritten with CALCULATED results**
    - jqwik, min 100 iterations; class `RoomGeometryAuthoritativePropertyTest`
    - **Validates: Requirements 4.2**
  - [x] 4.6 Write property test P6 — manual source stamping
    - **Property 6: with geometry absent, each supplied metric is persisted with source MANUAL**
    - jqwik, min 100 iterations; class `RoomManualSourcePropertyTest`
    - **Validates: Requirements 4.1**

- [x] 5. `RoomController` wiring
  - [x] 5.1 Create `RoomController implements AdminController<...>` at `/api/rooms`
    - `@PermissionResource("ROOMS")`; inherit generic create/list/read/update/delete/count/metadata/i18n (each keeping its `@PermissionOperation`); supply `getMapper()`/`getService()`
    - _Requirements: 5.1, 6.1_

- [x] 6. Checkpoint — backend compiles + startup annotations
  - Run `./gradlew compileJava compileTestJava` (temp log). Confirm `RoomController` is fully annotated so `PermissionAnnotationValidator` is satisfied. Ask the user if questions arise.
  - _Requirements: 6.2_

- [x] 7. Metadata reference descriptors
  - [x] 7.1 Confirm/emit reference descriptors for `project.id` and `roomType.id`
    - Verify `EntityMetadataResolver` emits `ReferenceInfo` for the `project` (`@ManyToOne`, target `projects`, options `/api/projects`) and `roomType` (`@ManyToOne`, target `room_types`, options `/api/room-types`, localized label) fields; add coverage only if the resolver does not already handle these `@ManyToOne` fields
    - _Requirements: 5.7, 8.2_

- [x] 8. ABAC seed
  - [x] 8.1 Create Liquibase `043-seed-rooms-resource.xml` and register it last in `changelog.xml`
    - Resource row `code='ROOMS'` with non-null `name_ru`/`name_pl`/`description_ru`/`description_pl`; grants ADMIN=CREATE,READ,UPDATE,DELETE; MANAGER=CREATE,READ,UPDATE,DELETE; FOREMAN=READ,UPDATE; WORKER/FINANCIER/CLIENT=READ; CREATE and DELETE for ADMIN and MANAGER only
    - Idempotent per role/resource via `<preConditions onFail="MARK_RAN"><sqlCheck expectedResult="0">...`; register after `042-create-rooms.xml`
    - _Requirements: 6.3, 6.4, 6.5, 6.6, 6.7_

- [x] 9. Backend integration + smoke tests (Testcontainers)
  - [x] 9.1 Write `RoomGeometryIT`
    - Create with geometry → calculated metrics persisted with `CALCULATED` sources (7.1 in-DB); geometry with < 3 vertices rejected; invalid opening rejected; nothing persisted on rejection
    - _Requirements: 7.2, 2.3, 2.4, 3.1_
  - [x] 9.2 Write `RoomManualMetricsIT`
    - Create with null geometry + supplied areas/perimeter → persisted with `MANUAL` sources; create with both geometry and manual values → geometry-derived values with `CALCULATED`, manual values ignored for those metrics
    - _Requirements: 7.3, 7.4, 4.1, 4.2_
  - [x] 9.3 Write `RoomScopingIT`
    - Non-ADMIN LIST membership-filtered; ADMIN bypass; non-member by-id read/update/delete denial
    - _Requirements: 7.5, 5.3, 5.4, 5.5_
  - [x] 9.4 Write `RoomCrudIT`
    - Read/update/delete lifecycle; reference filter on `project.id`; reference filter on `roomType.id`
    - _Requirements: 7.6, 7.7, 5.7_
  - [x] 9.5 Write property test P8 — membership list filter
    - **Property 8: non-ADMIN list returns exactly the rooms whose owning project the caller is a member of** (generated membership graphs)
    - jqwik, min 100 iterations; class `RoomMembershipListFilterPropertyTest`
    - **Validates: Requirements 5.3, 7.5**
  - [x] 9.6 Write `RoomsSeedIT`
    - Exactly one `ROOMS` resource; exact grant matrix (CREATE/DELETE = ADMIN and MANAGER only); idempotent re-apply leaves counts unchanged; plus create-table migration presence/shape
    - _Requirements: 7.8, 6.4, 6.5, 1.7, 1.8_
  - [x] 9.7 Write clean-startup annotation-coverage smoke test
    - Confirm fully annotated `RoomController` starts cleanly under `PermissionAnnotationValidator`
    - _Requirements: 6.2, 7.9_

- [x] 10. Checkpoint — run only the affected backend test classes
  - Run the property/unit/IT/smoke classes added above with `--tests` filters (redirect to temp log; read JUnit XML). Do NOT run the full suite. Ask the user if questions arise.
  - _Requirements: 7.1, 7.8_

- [x] 11. Frontend feature scaffolding (`features/rooms/`)
  - [x] 11.1 Create types, zod schemas, and API module
    - DTO/types incl. `RoomGeometry`, `Opening`, `MeasureValue`; zod schema (project + room type required; openings positive; geometry vertices ≥ 3 when present; manual metrics in range); `api/rooms-api.ts` (`fetchRooms` via `buildFetchQuery`, `fetchRoom`, `createRoom`, `updateRoom`, `deleteRoom`)
    - _Requirements: 8.10, 8.7_
  - [x] 11.2 Create `RoomSourceBadge` and `MeasureValueCell`
    - `RoomSourceBadge` (localized "Calculated"/"Manual"); `MeasureValueCell` renders `{value, source}` = number + source badge
    - _Requirements: 8.1, 8.5_
  - [x] 11.3 Create `RoomPlanPreview` (read-only SVG)
    - Render polygon path from `geometry.vertices`; mark openings on wall edges; no editing
    - _Requirements: 8.6_
  - [x] 11.4 Create `RoomOpeningsEditor` and `RoomFormSheet`
    - `RoomOpeningsEditor`: per-wall openings with `type`/`count`/`height`/`width` quick entry; `RoomFormSheet`: project + room-type selectors (reference), `label`, `ceilingHeight`, `internalCorners`, openings editor, manual-mode toggle (direct area/perimeter inputs, disabled when geometry present where derived values show read-only "Calculated"); block submit + retain values + localized validation on invalid field
    - _Requirements: 8.4, 8.5, 8.7_
  - [x] 11.5 Create `RoomsList`, `DeleteRoomDialog`, and `RoomsPage`
    - `DataTable<RoomDto>` `resource="ROOMS"` `entityKey="rooms"`, columns `project`, `roomType`, `label`, `floorArea`/`wallArea`/`perimeter` (via `MeasureValueCell`), `ceilingHeight`; server-side search/sort/filter/pagination; reference filters `project.id` + `roomType.id`; empty-state; gate create/edit/delete via `usePermission('ROOMS', ...)`; `DeleteRoomDialog` confirm + delete; `RoomsPage` composes list/form/delete; invalidate query + success/error toasts on mutations
    - _Requirements: 8.1, 8.2, 8.3, 8.8, 8.9, 8.10, 8.11_
  - [x] 11.6 Register `/rooms` route + interim guard + i18n
    - Register `path: 'rooms'` in `app/router.tsx`; add `'/rooms': { resource: 'ROOMS', operation: 'READ' }` to `route-permissions.ts`; add `rooms.*` keys at parity in `pl.json` and `ru.json` (all metric labels with units, source-flag labels, opening type labels, empty state, toasts, validation), no raw key rendered
    - _Requirements: 8.12, 8.13_

- [x] 12. Frontend tests (Vitest + RTL, run with `--run`)
  - [x] 12.1 Write `RoomsList.test.tsx`
    - Non-empty renders `project`/`roomType`/`label`/`floorArea`/`wallArea`/`perimeter`/`ceilingHeight` with row count == record count; each area/perimeter cell shows its source indicator; gated controls appear/hide with permission; empty result shows empty state with zero data rows
    - _Requirements: 9.1, 9.4_
  - [x] 12.2 Write `RoomFormSheet.test.tsx`
    - Adding a per-wall opening updates form state; manual mode accepts direct area/perimeter input; geometry mode shows derived values read-only
    - _Requirements: 9.2_
  - [x] 12.3 Write `RoomPlanPreview.test.tsx`
    - Read-only SVG renders a polygon path from stored `geometry` JSON
    - _Requirements: 9.3_
  - [x] 12.4 Write `rooms.i18n.test.ts`
    - `rooms.*` key sets identical in `pl.json` and `ru.json` (metric labels + units, source-flag labels, opening type labels), all non-empty, no raw key rendered
    - _Requirements: 8.12, 9.5_

- [x] 13. Checkpoint — frontend tsc + new UI test files
  - Run frontend type-check and the new Vitest files with `--run`. Ask the user if questions arise.
  - _Requirements: 9.1, 9.2_

- [x] 14. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD report template). This is an API+UI spec: include API tests against the Dockerized app for backend endpoints (`POST/GET/PUT/DELETE /api/rooms`, geometry-calculated vs manual metrics + source flags, `project.id`/`roomType.id` reference filters, project-scoping/ownership, ABAC seed/grants) and browser-engine scenarios for the admin `/rooms` UI (list with source badges, create/edit form with per-wall openings + manual mode, read-only SVG plan preview, delete, permission gating, i18n). Result artifacts are MD reports with tables. Write test-cases.md in Russian per the standard.
  - _Requirements: 1, 2, 3, 4, 5, 6, 8_

- [x] 15. Final checkpoint — affected backend classes + frontend tsc/UI tests
  - Run the affected backend test classes with `--tests` (temp log + JUnit XML) and the frontend `--run` tests; confirm all pass. Do NOT run the full suite unless explicitly requested. Ask the user if questions arise.
  - _Requirements: 7.1, 7.8, 9.1, 9.2_

## Notes

- Tasks are ordered so each builds on the previous and wires into the running application with no orphaned code.
- Property tests P1–P8 use jqwik with a minimum of 100 iterations and a `Feature: FOR-04-14-room, Property {n}` tag comment, one test class per property.
- The design has a Correctness Properties section, so property-test sub-tasks are included alongside unit/integration/smoke tests.
- Per the workspace test-execution standard, run only the affected test classes with `--tests`, redirect to a temp log, and read the JUnit XML for pass/fail; the full `./gradlew build` runs only on explicit request (~20 min).
- The interactive SVG drawing/annotation editor is deferred to FOR-05; this spec ships a read-only SVG preview plus structured (numeric + per-wall opening) input.
- The `/rooms` menu entry arrives in FOR-04-15; until then the interim `ROOMS`/`READ` route guard protects `/rooms`.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.3", "1.4", "1.5"] },
    { "id": 1, "tasks": ["1.2", "3.1", "4.1", "4.2"] },
    { "id": 2, "tasks": ["3.2", "3.3", "3.4", "3.5", "3.6", "4.3"] },
    { "id": 3, "tasks": ["4.4", "5.1"] },
    { "id": 4, "tasks": ["4.5", "4.6", "7.1", "8.1"] },
    { "id": 5, "tasks": ["9.1", "9.2", "9.3", "9.4", "9.5", "9.6", "9.7"] },
    { "id": 6, "tasks": ["11.1", "11.2", "11.3", "11.4"] },
    { "id": 7, "tasks": ["11.5", "11.6"] },
    { "id": 8, "tasks": ["12.1", "12.2", "12.3", "12.4"] }
  ]
}
```
