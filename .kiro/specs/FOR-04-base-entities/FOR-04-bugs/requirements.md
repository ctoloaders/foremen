# Requirements Document

Bugfix requirements for FOR-04-bugs.

## Introduction

This is a single bugfix spec collecting 11 distinct defects found across the FOR-04 base-entities
work (project, room, and reference-data flows). Each defect is documented as its own bug condition
with observed (current/defective) behavior, expected (correct) behavior, and regression-prevention
(unchanged) behavior.

The bugs are organized into two groups per the explicit grouping decision:

- **Frontend group** — bugs that live entirely in `foremen-frontend` (CSS theme variables, i18n
  catalog, form controls, list filters). Verified through browser-engine test scenarios against the
  frontend at `http://localhost:3000`.
- **Backend / cross-cutting group** — bugs that involve the Spring backend and/or an external
  integration (Google Places address autocomplete, project audit logging). Verified through API
  tests against the Dockerized app at `http://localhost:8080`.

Bugs 1 and 2 share a single root cause (undefined `--popover` CSS custom properties) and are grouped
together in the design. All numbering below is `X.Y` where `X` is the section (1 = Current Behavior,
2 = Expected Behavior, 3 = Unchanged Behavior) and `Y` identifies the specific bug/clause.

Bug index:

| Bug | Group | Short title |
|-----|-------|-------------|
| 1 | Frontend | Form dropdowns render transparent |
| 2 | Frontend | Table-filter dropdowns render transparent |
| 3 | Frontend | Missing i18n keys shown as raw dotted keys |
| 4 | Backend | Google Places address autocomplete broken / legacy API |
| 5 | Frontend | Project form has no date pickers (native date inputs) |
| 6 | Backend | Audit not recorded for project CREATE |
| 7 | Frontend | Room form uses native `<select>` instead of async search combobox |
| 8 | Frontend | Room form decimal separator (dot/comma) not accepted |
| 9 | Frontend | Deleting a wall + manual area entry not persisted |
| 10 | Frontend | Work-type form category dropdown not sorted by order |
| 11 | Frontend | Projects list team/client filters are toolbar panels, not column filters |

---

## Glossary

- **`--popover` / `--color-popover`**: The `bg-popover text-popover-foreground` theme CSS custom
  properties. When `--popover` / `--popover-foreground` are undefined, popover panels render
  transparent (root cause of Bugs 1 and 2).
- **`@theme`**: The Tailwind v4 block in `src/index.css` that maps `--color-*` design tokens.
- **`ReferenceFilter`**: The async infinite-scroll + search combobox component whose
  `useInfiniteQuery` logic is reused for the shared async entity select (Bug 7).
- **`Calendar`**: The react-day-picker component (`src/components/ui/calendar.tsx`) reused for the
  shared project date picker (Bug 5).
- **Places API (New)**: The Google `places.googleapis.com/v1` endpoints (`places:autocomplete`,
  `places/{id}`) authenticated with `X-Goog-Api-Key` and shaped by `X-Goog-FieldMask`, replacing the
  legacy Places Web Service (Bug 4).
- **`saveAudit`**: `AdminService.saveAudit(before, after, operation)`, which writes an
  `AuditLogEntity` snapshot; must be invoked on project CREATE (Bug 6).
- **Bug_Condition (C)**: The input predicate `isBugCondition(X)` identifying inputs that trigger a
  defect.
- **Preservation**: The requirement that for all non-buggy inputs (`¬C`), the fixed behavior equals
  the original behavior.

---

## Requirements

The bug analysis below is the requirements set for this bugfix spec. Section 1 (Current Behavior)
documents the defects, Section 2 (Expected Behavior) the correct behavior each defect must satisfy,
and Section 3 (Unchanged Behavior) the regression-prevention guarantees. All clauses are numbered
`X.Y` as described in the Introduction.

## Bug Analysis

### Current Behavior (Defect)

#### Frontend group

**1.1 Form dropdowns transparent.** WHEN a user opens any form dropdown built on `SelectContent`
(`src/components/ui/select.tsx`), `PopoverContent` (`src/components/ui/popover.tsx`),
`GoogleAddressAutocomplete`, `TeamMemberSelect`, or `ClientBlock` in light or dark theme THEN the
dropdown panel renders transparent because `bg-popover text-popover-foreground` resolves to
undefined color values.

**2 (defect).1 Table-filter dropdowns transparent.** WHEN a user opens a table-filter popover that
relies on `bg-popover` (e.g. the `DateFilter` popover, which has no explicit background) THEN the
filter panel renders transparent for the same undefined-`--popover` reason. (Note: `DataTableHeader`
and `FilterPopover` hardcode `bg-background` and are already opaque.)

**1.3 Missing i18n keys.** WHEN the UI renders text via a translation key that exists in code but is
absent from `pl.json`/`ru.json` THEN `parseMissingKeyHandler: (key) => key` in `src/lib/i18n.ts`
causes the raw dotted key (e.g. `projects.form.team.label`) to be displayed to the user.

**1.5 No project-form date pickers.** WHEN a user edits a project's start or end date in
`ProjectFormSheet.tsx` THEN the form shows a native `<input type="date">` instead of the app's
`Calendar`-based date picker, giving inconsistent, browser-dependent behavior.

**1.7 Room-form native entity selects.** WHEN a user picks a project or room type in
`RoomFormSheet.tsx` THEN the field is a native `<select>` populated by a one-shot
`useReferenceOptions(size=200)`, so it cannot search or infinite-scroll and silently truncates large
lists. The same applies to `WorkItemFormSheet` (`workCategoryId`, `unitId`).

**1.8 Decimal separator dropped.** WHEN a user types a comma (or, depending on locale, a dot) as the
decimal separator in a numeric field of `RoomFormSheet.tsx` / `RoomOpeningsEditor.tsx` THEN the
native `<input type="number">` and `Number()`/`z.coerce.number()` drop the "wrong" separator and the
value is lost or misread.

**1.9 Wall-delete + manual area not persisted.** WHEN a user deletes a wall (leaving one or more
walls) and then enters floor/wall areas manually THEN `RoomFormSheet.tsx` (where
`hasGeometry = walls.length > 0` and `onSubmit` forces `floorArea: geometry ? null : ...`) discards
the manual values because geometry is still considered present, and the manual inputs only render
when all walls are deleted.

**1.10 Category dropdown wrong order.** WHEN a user opens the work-category dropdown in
`WorkItemFormSheet.tsx` THEN options are fetched with `sort=name,asc` and shown alphabetically
instead of by the entity's `orderNo` ascending.

**1.11 Team/client filters are toolbar panels.** WHEN a user filters the projects list by team
member or client THEN `ProjectsList.tsx` renders `ProjectMembersFilter`/`ProjectClientFilter` as
always-visible toolbar panels (with manual `composeQuery` fragments +
`queryClient.invalidateQueries`) while the members/client columns are `filterable:false`, so these
filters are inconsistent with every other nested-entity column filter opened from the column filter
icon.

#### Backend / cross-cutting group

**1.4 Address autocomplete broken.** WHEN a user types an address in `GoogleAddressAutocomplete`
THEN the backend `RestClientGooglePlacesClient` calls the **legacy** Places Web Service
(`maps.googleapis.com/maps/api/place/autocomplete/json` and `.../details/json`), and when the
feature is disabled the service still attempts to call Google, so autocomplete does not work
reliably.

**1.6 Project CREATE not audited.** WHEN a project is created via `POST /api/projects` THEN
`ProjectService.createProject(...)` persists via `projectDao.save(...)` and never calls
`saveAudit(...)`, so no `CREATE` row is written to the audit log (unlike generic entities and rooms,
which are audited by `AdminService`).

### Expected Behavior (Correct)

#### Frontend group

**2.1 Form dropdowns opaque.** WHEN a user opens any form dropdown listed in 1.1 in light or dark
theme THEN the system SHALL render the panel fully opaque with correct foreground/background colors.

**2 (correct).1 Table-filter dropdowns opaque.** WHEN a user opens any table-filter dropdown
(including `DateFilter`) THEN the system SHALL render the filter panel fully opaque in light and dark
theme.

**2.3 No raw i18n keys.** WHEN the UI renders any statically-referenced translation key THEN the
system SHALL resolve it to a real localized string; no raw dotted key SHALL ever be shown. A guard
test SHALL scan `src` for static `t('literal')` usages and assert each exists in both catalogs, and
SHALL assert `pl.json` and `ru.json` have identical key sets with non-empty values.

**2.5 Project-form date pickers.** WHEN a user edits a project's start/end date THEN the system SHALL
present a shared `Calendar`-based date picker (Popover + `Calendar`), consistent with the data-table
`DateFilter`.

**2.7 Room-form async combobox.** WHEN a user picks a project, room type, work category, or unit in a
form THEN the system SHALL present an async combobox with server-side search and infinite scroll
(reusing the `ReferenceFilter` logic), not a truncated native `<select>`.

**2.8 Decimal separator accepted.** WHEN a user types either `.` or `,` as the decimal separator in a
numeric form field THEN the system SHALL accept both, normalize `,` → `.` before coercion, and
persist the intended numeric value.

**2.9 Wall-delete + manual area persisted.** WHEN a user deletes a wall and then enters areas
manually THEN the system SHALL persist the manually entered areas (an explicit manual-override mode
decoupled from wall count), rather than discarding them because a wall remains.

**2.10 Category dropdown ordered.** WHEN a user opens the work-category dropdown THEN the system
SHALL fetch and render options sorted by `orderNo` ascending.

**2.11 Team/client column filters.** WHEN a user filters the projects list by team member or client
THEN the system SHALL expose these as nested-entity column filters opened from the column filter icon
(via `ColumnConfig.reference`), preserving the exact same query semantics, and SHALL remove the
standalone toolbar panels and manual fragment/refetch plumbing.

#### Backend / cross-cutting group

**2.4 Address autocomplete via Places API (New).** WHEN a user types an address and the feature is
enabled THEN the system SHALL call the Places API (New) endpoints
(`places.googleapis.com/v1/places:autocomplete` and `/v1/places/{id}`) using the `X-Goog-Api-Key`
header and `X-Goog-FieldMask`, returning predictions and details end to end; WHEN the feature is
disabled (`GOOGLE_PLACES_ENABLED=false`) THEN the system SHALL degrade gracefully (empty/handled
result) without calling Google. The API key SHALL be env-driven and never committed.

**2.6 Project CREATE audited.** WHEN a project is created via `POST /api/projects` THEN the system
SHALL write a `CREATE` audit row within the same transaction via
`saveAudit(null, saved, "CREATE")`, consistent with the `AdminService` audit contract
(entityClass/entityId/performedBy/snapshotAfter).

### Unchanged Behavior (Regression Prevention)

#### Frontend group

**3.1 Opaque-by-default popovers unchanged.** WHEN a popover already hardcodes `bg-background`
(`DataTableHeader`, `FilterPopover`) THEN the system SHALL CONTINUE TO render it opaque exactly as
before.

**3.2 Existing translations unchanged.** WHEN a translation key already exists in both catalogs THEN
the system SHALL CONTINUE TO render its existing localized value, and `pl`/`ru` parity (~805 keys)
SHALL CONTINUE TO hold.

**3.3 Mouse/keyboard control interactions unchanged.** WHEN a user interacts with any existing form
control via mouse or keyboard that is not one of the changed controls THEN the system SHALL CONTINUE
TO behave exactly as before.

**3.4 Room geometry mode unchanged.** WHEN a user builds a room from walls (geometry mode) without
switching to manual override THEN the system SHALL CONTINUE TO compute and persist geometry-derived
metrics as before (manual metrics remain null).

**3.5 Other list filters unchanged.** WHEN a user uses any already-working column or nested-entity
filter (e.g. `WorkCatalogList` reference column) THEN the system SHALL CONTINUE TO filter with the
same query semantics.

#### Backend / cross-cutting group

**3.6 Server-side place resolution unchanged.** WHEN a project is created with a `placeId` and the
feature is enabled THEN the system SHALL CONTINUE TO resolve place details server-side in
`ProjectService.createProject` (populating `formattedAddress`/`latitude`/`longitude`).

**3.7 Existing audit unchanged.** WHEN a generic entity or a room is created/updated/deleted, or a
project is updated/deleted THEN the system SHALL CONTINUE TO write the existing audit rows via
`AdminService` exactly as before (project UPDATE goes through the audited `AdminService.update` and
must remain audited; this is verified, not changed).

---

## Deriving the Bug Conditions

Two families of bug conditions cover all 11 bugs. `F` is the current (unfixed) behavior; `F'` is the
fixed behavior.

**Family A — defect-triggering inputs (Fix Checking).** For each bug, `isBugCondition(X)` selects the
inputs that currently misbehave, and the fixed function must satisfy the expected-behavior predicate:

```pascal
FUNCTION isBugCondition_popover(X)          // Bugs 1, 2
  RETURN X.opensPanelUsing = 'bg-popover'
END FUNCTION
// Property: F'(X) renders opaque (computed background alpha = 1, defined foreground)

FUNCTION isBugCondition_i18n(X)             // Bug 3
  RETURN X.tKey used in code AND X.tKey NOT IN catalog(pl) ∪ catalog(ru)
END FUNCTION
// Property: catalog contains X.tKey with non-empty value; no raw key rendered

FUNCTION isBugCondition_places(X)           // Bug 4
  RETURN X.featureEnabled = true AND X.action IN {autocomplete, details}
END FUNCTION
// Property: request goes to places.googleapis.com/v1 with X-Goog-Api-Key + FieldMask;
//           when featureEnabled=false, no Google call and handled empty result

FUNCTION isBugCondition_datepicker(X)       // Bug 5
  RETURN X.field IN {project.startDate, project.endDate}
END FUNCTION
// Property: control is the shared Calendar-based DatePicker

FUNCTION isBugCondition_audit(X)            // Bug 6
  RETURN X.action = 'POST /api/projects' AND X.succeeds
END FUNCTION
// Property: exactly one audit row (entityClass=ProjectEntity, operation=CREATE) after commit

FUNCTION isBugCondition_asyncSelect(X)      // Bug 7
  RETURN X.field IN {room.projectId, room.roomTypeId, workItem.workCategoryId, workItem.unitId}
END FUNCTION
// Property: control is async search + infinite-scroll combobox

FUNCTION isBugCondition_decimal(X)          // Bug 8
  RETURN X.numericInput AND X.text CONTAINS ','
END FUNCTION
// Property: ',' normalized to '.'; Number(normalized) is the intended value

FUNCTION isBugCondition_wallManual(X)       // Bug 9
  RETURN X.walls.length > 0 AND X.userEnteredManualAreas = true
END FUNCTION
// Property: submitted payload carries the manual areas (not null)

FUNCTION isBugCondition_catOrder(X)         // Bug 10
  RETURN X.dropdown = 'workCategory'
END FUNCTION
// Property: options sorted by orderNo ascending

FUNCTION isBugCondition_projFilters(X)      // Bug 11
  RETURN X.column IN {members, client} on ProjectsList
END FUNCTION
// Property: column is filterable via ColumnConfig.reference, opened from filter icon
```

**Family B — Preservation (for all non-buggy inputs `¬isBugCondition`).**

```pascal
FOR ALL X WHERE NOT isBugCondition_any(X) DO
  ASSERT F(X) = F'(X)      // opaque-by-default popovers, existing keys, geometry mode,
                           // other filters, existing audit, server-side place resolution
END FOR
```
