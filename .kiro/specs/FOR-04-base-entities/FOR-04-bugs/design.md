# FOR-04-bugs Bugfix Design

## Overview

This design fixes 11 defects collected into a single bugfix spec, organized into a **Frontend group**
and a **Backend / cross-cutting group**. Each fix is targeted and minimal, and every change is paired
with a preservation goal so existing behavior is not regressed.

Two fixes share a root cause and reuse existing components, which is the main design driver:

- **Bugs 1 + 2 share one root cause**: the `--popover` / `--popover-foreground` CSS custom
  properties are never defined, so every `bg-popover text-popover-foreground` surface renders
  transparent. A single CSS change fixes both.
- **Reuse over new abstractions**: date pickers reuse `src/components/ui/calendar.tsx`
  (react-day-picker) wrapped in a Popover; async entity selects reuse the `ReferenceFilter`
  `useInfiniteQuery` logic (extracted into a shared `AsyncEntitySelect`); decimal handling is
  centralized in a shared numeric input.

## Glossary

- **Bug_Condition (C)**: The input predicate that triggers a specific defect (see per-bug
  `isBugCondition`).
- **Property (P)**: The desired behavior for inputs satisfying C.
- **Preservation**: Behavior for `¬C` inputs that must remain byte-for-byte identical after the fix.
- **`--popover` / `--popover-foreground`**: Theme CSS variables consumed by `bg-popover` /
  `text-popover-foreground` utilities. Currently undefined in `src/index.css`.
- **`@theme`**: Tailwind v4 block in `src/index.css` mapping `--color-*` tokens; currently maps
  `--color-card*` but not `--color-popover*`.
- **`ReferenceFilter`**: `src/components/data-table/ReferenceFilter.tsx` — an async infinite-scroll +
  search combobox (`useInfiniteQuery` keyed by `[targetResource, debouncedSearch]`, `buildOptionsUrl`
  with `query=name~ct~<term>`, IntersectionObserver sentinel).
- **`Calendar`**: `src/components/ui/calendar.tsx` — react-day-picker component already used by the
  data-table `DateFilter`.
- **Places API (New)**: Google endpoints `places.googleapis.com/v1/places:autocomplete` and
  `/v1/places/{id}`, authenticated by the `X-Goog-Api-Key` header and shaped by `X-Goog-FieldMask`.
- **`saveAudit`**: `AdminService.saveAudit(before, after, operation)` — writes an `AuditLogEntity`
  (entityClass = simple class name, entityId, performedBy, snapshot before/after).

---

## Bug Details

### Bug Condition (per bug)

**Bugs 1 + 2 — transparent popovers (shared root cause).**

```
FUNCTION isBugCondition(input)
  INPUT: input = a rendered panel using the bg-popover utility
  OUTPUT: boolean
  RETURN input.className CONTAINS 'bg-popover'
         AND cssVar('--popover') is undefined
END FUNCTION
```
Affected surfaces: `SelectContent` (`select.tsx`), `PopoverContent` (`popover.tsx`),
`GoogleAddressAutocomplete`, `TeamMemberSelect`, `ClientBlock`, and the `DateFilter` popover (no
explicit bg). Not affected: `DataTableHeader`, `FilterPopover` (hardcode `bg-background`).

**Bug 3 — missing i18n keys.**
```
FUNCTION isBugCondition(input)
  RETURN input.tKey is used in src via t('literal')
         AND input.tKey NOT IN (keys(pl.json) ∪ keys(ru.json))
END FUNCTION
```
Confirmed-missing keys (at least): `projects.form.address`, `projects.form.team.label`,
`projects.form.team.hint`, `projects.form.team.empty`, `projects.form.team.placeholder`,
`projects.form.team.search`, `projects.form.team.selectedCount`, `projects.toast.createError`,
`projects.toast.updateError`, `projects.toast.deleteError`, `dataTable.filters.summaryFilters`,
`dataTable.filters.summarySorts`.

**Bug 4 — Places autocomplete.**
```
FUNCTION isBugCondition(input)
  RETURN input.action IN {autocomplete, details}
         AND (client targets legacy maps.googleapis.com/maps/api/place/*
              OR (feature disabled AND Google is still called))
END FUNCTION
```

**Bug 5 — project date pickers.**
```
FUNCTION isBugCondition(input)
  RETURN input.field IN {project.startDate, project.endDate}
         AND control is <input type="date">
END FUNCTION
```

**Bug 6 — project CREATE audit.**
```
FUNCTION isBugCondition(input)
  RETURN input.action = 'POST /api/projects' AND input.succeeds
         AND no AuditLogEntity(entityClass=ProjectEntity, operation=CREATE) written
END FUNCTION
```

**Bug 7 — async entity selects.**
```
FUNCTION isBugCondition(input)
  RETURN input.field IN {room.projectId, room.roomTypeId, workItem.workCategoryId, workItem.unitId}
         AND control is native <select> from one-shot useReferenceOptions
END FUNCTION
```

**Bug 8 — decimal separator.**
```
FUNCTION isBugCondition(input)
  RETURN input.field is numeric (RoomFormSheet / RoomOpeningsEditor)
         AND input.text uses ',' as decimal separator
END FUNCTION
```

**Bug 9 — wall delete + manual area.**
```
FUNCTION isBugCondition(input)
  RETURN input.walls.length > 0
         AND input.userEnteredManualAreas = true
END FUNCTION
```

**Bug 10 — category order.**
```
FUNCTION isBugCondition(input)
  RETURN input.dropdown = 'workCategory'
         AND options fetched with sort=name,asc
END FUNCTION
```

**Bug 11 — project list filters.**
```
FUNCTION isBugCondition(input)
  RETURN input.column IN {members, client} on ProjectsList
         AND column.filterable = false AND filter rendered as toolbar panel
END FUNCTION
```

### Examples

- Open the room-type dropdown in the project form (dark theme): panel is see-through, options overlap
  page content (Bugs 1/2). After fix: solid panel.
- Render the project form: label reads `projects.form.team.label` literally (Bug 3). After fix:
  localized RU/PL string.
- Type an address with the feature enabled: no predictions because the legacy endpoint path is used
  (Bug 4). After fix: predictions from Places API (New).
- `POST /api/projects` returns 201, but `GET /api/audit` shows no CREATE row for the new project
  (Bug 6). After fix: one CREATE row for `ProjectEntity`.
- In a room with 3 walls, delete one wall then type floor area `12,5`: value is dropped twice — once
  by the comma (Bug 8) and once by the wall-present guard (Bug 9). After fix: `12.5` persists.

---

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Popovers that hardcode `bg-background` (`DataTableHeader`, `FilterPopover`) remain opaque.
- Existing translation keys keep their values; `pl`/`ru` parity (~805 keys) holds.
- Geometry mode (walls only, no manual override) keeps computing/persisting derived metrics with
  manual metrics null.
- Existing column/nested-entity filters (e.g. `WorkCatalogList` reference column) keep their query
  semantics.
- Existing audit rows (generic entities, rooms, project UPDATE/DELETE) keep being written by
  `AdminService`.
- Server-side place resolution in `ProjectService.createProject` (when enabled + placeId) is
  unchanged.

**Scope:** All inputs that do NOT satisfy any `isBugCondition` above must be completely unaffected:
mouse clicks on unchanged controls, keyboard navigation, other keys, existing keys, geometry-only
rooms, other filters, and all non-project or non-create backend audit paths.

---

## Hypothesized Root Cause

1. **Undefined theme variables (Bugs 1, 2)**: `src/index.css` defines `--card`/`--color-card*` but no
   `--popover`/`--popover-foreground` in `:root`/`.dark` nor `--color-popover*` in `@theme`. Utility
   `bg-popover` therefore resolves to an undefined color → transparent.

2. **Permissive missing-key handler + code/catalog drift (Bug 3)**: `parseMissingKeyHandler:
   (key) => key` masks drift; some code-referenced keys were never added to the catalogs.

3. **Legacy Places integration + no enabled-guard (Bug 4)**: `RestClientGooglePlacesClient` targets
   the legacy Web Service and the service calls Google regardless of the `enabled` flag.

4. **Native controls instead of shared components (Bugs 5, 7, 8)**: forms use `<input type="date">`,
   native `<select>`, and `<input type="number">` rather than the app's Calendar/async-combobox and a
   locale-tolerant numeric input.

5. **Geometry presence coupled to wall count (Bug 9)**: `hasGeometry = walls.length > 0` and
   `onSubmit` forcing `floorArea: geometry ? null : ...` discards manual values whenever a wall
   remains.

6. **Wrong sort field (Bug 10)**: dropdown fetch uses `sort=name,asc`; the intended field is the
   entity's `orderNo` (`order_no` column).

7. **Bespoke toolbar filters instead of column filters (Bug 11)**: members/client filters are
   hand-rolled toolbar panels with manual query fragments + `invalidateQueries`, bypassing the
   built-in `ColumnConfig.reference` nested-entity filter.

8. **Missing audit call in custom create (Bug 6)**: `ProjectService.createProject` persists via
   `projectDao.save` and never calls `saveAudit`, unlike `AdminService.create`.

---

## Correctness Properties

Property 1: Bug Condition — Transparent popovers (Bugs 1, 2)

_For any_ panel rendered with the `bg-popover` utility where the bug condition holds (`--popover`
undefined), the fixed CSS SHALL cause the panel to render fully opaque (defined background and
foreground) in both light and dark themes.

**Validates: Requirements 2.1, 2 (correct).1**

Property 2: Preservation — Unchanged surfaces and behaviors

_For any_ input where no `isBugCondition` holds (opaque-by-default popovers, existing i18n keys,
geometry-only rooms, other list filters, existing audit paths, server-side place resolution), the
fixed system SHALL produce the same result as the original system.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7**

Property 3: Bug Condition — No raw i18n key (Bug 3)

_For any_ statically-referenced `t('literal')` in `src`, the key SHALL exist in both `pl.json` and
`ru.json` with a non-empty value, and the two catalogs SHALL have identical key sets.

**Validates: Requirements 2.3**

Property 4: Bug Condition — Places API (New) + enabled guard (Bug 4)

_For any_ autocomplete/details request while the feature is enabled, the request SHALL target
`places.googleapis.com/v1` with `X-Goog-Api-Key` + `X-Goog-FieldMask`; _for any_ request while the
feature is disabled, no Google call SHALL be made and a handled empty result SHALL be returned.

**Validates: Requirements 2.4**

Property 5: Bug Condition — Project CREATE audited (Bug 6)

_For any_ successful `POST /api/projects`, exactly one `AuditLogEntity` with `entityClass =
ProjectEntity` and `operation = CREATE` SHALL be written within the create transaction.

**Validates: Requirements 2.6**

Property 6: Bug Condition — Decimal separator (Bug 8)

_For any_ numeric form input where the user types `,` as the decimal separator, the value SHALL be
normalized to `.` and coerced to the intended number.

**Validates: Requirements 2.8**

Property 7: Bug Condition — Wall delete + manual area persisted (Bug 9)

_For any_ room submission where one or more walls remain AND the user entered manual areas, the
submitted payload SHALL carry the manual areas rather than null.

**Validates: Requirements 2.9**

Property 8: Bug Condition — Form control replacements (Bugs 5, 7, 10, 11)

_For any_ affected form field/list column, the control SHALL be the intended shared component
(Calendar date picker; async search+infinite-scroll combobox; category options sorted by `orderNo`
asc; members/client as `ColumnConfig.reference` column filters), preserving the same submit/query
semantics.

**Validates: Requirements 2.5, 2.7, 2.10, 2.11**

---

## Fix Implementation

### Frontend group

**Change F1 — Define popover theme variables (Bugs 1, 2).**
File: `src/index.css`. Add `--popover` / `--popover-foreground` to `:root` and `.dark` (mirroring the
existing `--card` values) and `--color-popover` / `--color-popover-foreground` in `@theme`. Also give
the `DateFilter` popover an explicit background if any residual transparency remains.

**Change F2 — i18n catalog + guard test (Bug 3).**
Files: `src/locales/pl.json`, `src/locales/ru.json`, and a new test (e.g.
`src/lib/i18n.keys.test.ts`). Add every confirmed-missing key (and any others surfaced by the scan)
to both catalogs. Add a test that (a) asserts `keys(pl) === keys(ru)` with non-empty values, and
(b) scans `src` for static `t('literal')` usages and asserts each exists in the catalog. Consider
switching `parseMissingKeyHandler` to a dev-time throw/warn so drift is caught early (behavior
preserved in prod).

**Change F3 — Shared DatePicker (Bug 5).**
Files: new `src/components/ui/date-picker.tsx` (Popover + `Calendar`), used by
`ProjectFormSheet.tsx` for `startDate`/`endDate`. Audit note: room form has no dates and
work-catalog has none, so no other form needs it.

**Change F4 — Shared AsyncEntitySelect (Bug 7).**
Files: extract the `ReferenceFilter` `useInfiniteQuery` logic into a shared
`src/components/ui/async-entity-select.tsx` (form control variant), used for `RoomFormSheet`
(`projectId`, `roomTypeId`) and `WorkItemFormSheet` (`workCategoryId`, `unitId`). Category select
must sort by `orderNo,asc` (coordinate with Change F6).

**Change F5 — Shared decimal NumberInput (Bug 8).**
Files: new `src/components/ui/number-input.tsx` (`type="text" inputMode="decimal"`, accepts `.` and
`,`, normalizes `,`→`.` before emitting). Use in `RoomFormSheet.tsx`, `RoomOpeningsEditor.tsx`;
normalize before `Number()`/zod in `room-schema.ts`.

**Change F6 — Manual-override mode + category order (Bugs 9, 10).**
Files: `RoomFormSheet.tsx`, `WorkItemFormSheet.tsx`. Introduce an explicit manual-override flag
decoupled from `walls.length`; when the user enters manual areas, submit those values (do not force
null while a wall remains). Change the work-category fetch/sort to `orderNo,asc`. Confirm backend
`Pageable` accepts `orderNo` (real JPA property on `WorkCategoryEntity`).

**Change F7 — Project list column filters (Bug 11).**
Files: `ProjectsList.tsx`. Set members/client columns `filterable: true` with a
`ColumnConfig.reference` descriptor (members → `members.user.id`; client → the CLIENT-scoped compound
path used by `ProjectClientFilter`) so they open from the column filter Popover like other nested
filters. Remove the toolbar `ProjectMembersFilter`/`ProjectClientFilter` panels and the manual
`composeQuery` fragment + `invalidateQueries` plumbing, preserving identical query semantics.

### Backend / cross-cutting group

**Change B1 — Places API (New) migration + enabled guard (Bug 4).**
Files: `RestClientGooglePlacesClient.java` (or a new client impl), `GooglePlacesService.java`,
`docker-compose.yml`, and a new local (gitignored) compose override.
- Migrate to `POST places.googleapis.com/v1/places:autocomplete` and
  `GET places.googleapis.com/v1/places/{id}` using `X-Goog-Api-Key: <apiKey>` and an appropriate
  `X-Goog-FieldMask` (e.g. `suggestions.placePrediction.text,...placeId` for autocomplete;
  `formattedAddress,location,addressComponents` for details). Rebind the response records to the new
  JSON shapes.
- Add an `enabled` guard in the service/controller: when `enabled=false`, return an empty/handled
  result and do not call Google.
- Add a local override `docker-compose.override.yml` (gitignored) documenting
  `GOOGLE_PLACES_ENABLED=true` and `GOOGLE_PLACES_API_KEY=<key>` on the `backend` service. Never
  commit a real key. Config already reads `foremen.google-places.api-key: ${GOOGLE_PLACES_API_KEY:}`
  and `enabled: ${GOOGLE_PLACES_ENABLED:false}`.
- **`.gitignore`**: the current `.gitignore` does NOT cover a compose override, so add
  `docker-compose.override.yml` (and/or `docker-compose.*.local.yml`) to `.gitignore`.

**Change B2 — Audit project CREATE (Bug 6).**
File: `ProjectService.java`. In `createProject(...)`, after `ProjectEntity saved =
projectDao.save(project)` (and within the existing `@Transactional`), call
`saveAudit(null, saved, "CREATE")` so a CREATE row is written with the standard contract. Verify (do
not blindly change) that project UPDATE still routes through the audited `AdminService.update`, and
that room create/update audit still works.

---

## Testing Strategy

Per the workspace test-execution rules: do NOT run the full backend gradle suite. Frontend uses
`vitest` (`--run`) and `tsc`. Backend runs only affected test classes via `--tests` filters,
redirecting to a temp log and reading the JUnit XML for pass/fail.

### Validation Approach

Two-phase: (1) surface counterexamples that demonstrate each bug on UNFIXED code; (2) verify the fix
works and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples before implementing fixes.

**Test Cases**:
1. **Popover opacity** (Bugs 1/2): render `SelectContent`/`PopoverContent`, assert computed
   background is not transparent — FAILS on unfixed code.
2. **i18n existence guard** (Bug 3): scan `src` for `t('literal')`, assert each key exists — FAILS on
   unfixed code (missing keys listed above).
3. **Places API (New)** (Bug 4): assert the client targets `places.googleapis.com/v1` with the
   header/field-mask; assert disabled → no call — FAILS on unfixed code (legacy path, always calls).
4. **Project CREATE audit** (Bug 6): `POST /api/projects`, then assert one `ProjectEntity`/`CREATE`
   audit row — FAILS on unfixed code (no row).
5. **Decimal separator** (Bug 8): enter `12,5`, assert normalized `12.5` — FAILS on unfixed code.
6. **Wall delete + manual area** (Bug 9): delete a wall, enter areas, assert payload carries manual
   areas — FAILS on unfixed code (forced null).
7. **Control replacements** (Bugs 5, 7, 10, 11): assert Calendar picker / async combobox / `orderNo`
   order / column-filter descriptor present — FAIL on unfixed code.

### Fix Checking

```
FOR ALL input WHERE isBugCondition(input) DO
  result := fixedBehavior(input)
  ASSERT expectedBehavior(result)
END FOR
```

### Preservation Checking

```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT originalBehavior(input) = fixedBehavior(input)
END FOR
```

Property-based testing is recommended for preservation (i18n parity, decimal normalization over the
numeric domain, audit for non-project entities), because it generates many cases and catches edge
cases.

**Test Cases (preservation)**:
1. Opaque-by-default popovers remain opaque (Bugs 1/2 preservation).
2. Existing i18n keys keep values; `pl`/`ru` parity holds (Bug 3 preservation).
3. Geometry-only room persists derived metrics unchanged (Bug 9 preservation).
4. `WorkCatalogList` reference filter query unchanged (Bug 11 preservation).
5. Generic/room audit + project UPDATE audit unchanged (Bug 6 preservation).
6. Server-side place resolution on create (enabled) unchanged (Bug 4 preservation).

### Unit Tests

- Frontend: popover opacity, DatePicker render, AsyncEntitySelect search/scroll, NumberInput
  normalization, manual-override submit payload, category order, project column-filter descriptors.
- Backend: `RestClientGooglePlacesClient` targets new endpoints; enabled-guard; `ProjectService`
  create writes audit.

### Property-Based Tests

- i18n: `keys(pl) === keys(ru)`, non-empty values; used-key existence over the scanned `src`.
- Decimal: for any decimal with `,`, normalization yields the same number as its `.` form.
- Audit preservation: for any non-project audited entity, audit rows unchanged.

### Integration Tests

- Backend API (Dockerized): autocomplete → details → project create with placeId (enabled), and
  disabled → graceful; project create → audit row present.
- Frontend (browser engine): open each fixed dropdown/date picker/combobox; project list column
  filters; room form decimal + wall-delete manual-area flow.
