# Design Document — FOR-04-01: Table Reference-Entity Filtering

## Overview

This feature makes reference (association) columns filterable across every admin table by driving the UI from table metadata. The backend enriches `GET /api/{resource}/metadata` so each `@ManyToOne`/`@OneToOne` field carries a **reference descriptor** (target resource, options endpoint, label field, id filter path). The frontend adds one reusable **ReferenceFilter** control to the shared DataTable that lazy-loads the target entity (infinite scroll, alphabetical by locale name, backend search) and emits the existing query grammar: `role.id==5` (single) or `role.id=in=(5,7)` (multi).

No new query engine is needed — the backend already parses `==`, `=in=`, dotted nested paths (`role.id`), and AND composition (`QueryParser`/`QueryTokenizer`, FOR-01/02). The work is: (1) a metadata enrichment on the existing `EntityMetadataResolver`, and (2) a frontend component + DataTable integration.

## Architecture

```
┌──────────────────────────── Frontend (React) ─────────────────────────────┐
│  DataTable                                                                 │
│    └─ for each column: pick filter control by metadata                     │
│         ├─ STRING/NUMBER/DATE/ENUM/BOOLEAN → existing filters              │
│         └─ reference descriptor present     → <ReferenceFilter/>           │
│                                                                            │
│  ReferenceFilter (reusable)                                                │
│    - useInfiniteQuery(optionsEndpoint, {search, page})  ← backend search   │
│    - alphabetical by locale name (server sort)                             │
│    - single mode  → emits `${idPath}==${id}`                               │
│    - multi mode   → emits `${idPath}=in=(${ids.join(',')})`                │
│    - 1 selected in multi → collapses to `==`                               │
└────────────────────────────────────────────────────────────────────────────┘
                        │ GET /api/{target}?page&size&sort=name,asc&query=name=ct=term
                        ▼
┌──────────────────────────── Backend (Spring) ─────────────────────────────┐
│  {resource}/metadata → EntityMetadataResolver                              │
│     FieldInfo now carries optional ReferenceInfo(targetResource,           │
│        optionsPath, labelField, labelI18n, idPath)                         │
│                                                                            │
│  Options listing = target resource's EXISTING list endpoint                │
│     - sort=name,asc (locale-resolved via existing i18n sort resolution)    │
│     - query=name=ct=term (existing contains operator, i18n-aware)          │
│     - guarded by target resource READ (PermissionInterceptor, FOR-03-08)   │
└────────────────────────────────────────────────────────────────────────────┘
```

## Components and Interfaces

### 1. Backend — metadata enrichment (`ReferenceInfo`)

Extend `MetadataResponse.FieldInfo` with an optional `reference` descriptor. Keep all existing fields (`name`, `dataType`, `i18n`, `nested`) so the change is additive and backward compatible.

```java
public record MetadataResponse(List<FieldInfo> fields) {
    public record FieldInfo(
        String name,
        DataType dataType,
        boolean i18n,
        List<FieldInfo> nested,
        ReferenceInfo reference   // null for non-reference fields
    ) {}

    public record ReferenceInfo(
        String targetResource,   // e.g. "roles"  (API path segment / resource code)
        String optionsPath,      // e.g. "/api/roles"
        String labelField,       // e.g. "name"  (base i18n field; resolves nameRU/namePL)
        boolean labelI18n,       // true when labelField is localized
        String idPath            // e.g. "role.id"  (filter path composed with query grammar)
    ) {}

    public enum DataType { STRING, NUMBER, DATE, BOOLEAN, ENUM }
}
```

`EntityMetadataResolver` changes (in `isNestedEntity` branch): when a field is `@ManyToOne`/`@OneToOne`, in addition to resolving `nested`, build a `ReferenceInfo`:

- `idPath = fieldName + ".id"`.
- `targetResource` / `optionsPath`: derived from the target entity type via a small mapping. Two viable strategies (choose in implementation):
  - **(a) Annotation-driven (preferred):** read the target controller's `@PermissionResource("ROLES")` value + its `@RequestMapping` path. Requires a registry from entity type → (resource code, base path). A `ReferenceResourceRegistry` built at startup by scanning `RequestMappingHandlerMapping` for `AdminController`/`AdminReadOnlyController` beans and their `@PermissionResource` + entity generic type. This keeps metadata correct without hardcoding.
  - **(b) Convention/config fallback:** a declarative map (entity class → resource path) for cases the registry cannot resolve.
- `labelField`: the target entity's display name base field. Convention: `"name"` when the target has `nameRU`/`namePL` (label i18n = true); otherwise the first STRING non-audit field, or `"id"` as last resort. Detected by reusing `EntityMetadataResolver` on the target (already computed for `nested`).
- Circular-reference safety: the existing `IN_PROGRESS` ThreadLocal guard already prevents infinite recursion; `ReferenceInfo` itself is shallow (no nested target metadata), so it does not deepen recursion.

Caching: `EntityMetadataResolver` already caches per entity class; `ReferenceInfo` is computed once and cached with the rest.

### 2. Backend — options listing

No new endpoint. The dropdown calls the **target resource's existing list endpoint** (`GET /api/roles`) with:

- `sort=name,asc` — the existing `ReadOnlyAdminService` sort path already resolves an i18n `name` sort to the locale column (`resolveI18nFilterField` / i18n sort resolution), so alphabetical-by-locale-name works out of the box.
- `query=name=ct=term` — the existing case-insensitive contains operator (`CONTAINS`), i18n-aware, for backend search.
- Pagination (`page`, `size`) — standard Spring Data page; `last`/`totalPages` drive infinite scroll.
- Authorization — the endpoint already resolves to `<TARGET>`/`READ` via `@PermissionResource` + `@PermissionOperation` (FOR-03-08); no extra guard needed.

Design decision (Req 2.6): reuse the list endpoint. A dedicated `/options` projection is only introduced if profiling shows the full DTO is too heavy; not required for FOR-04-01.

### 3. Frontend — `ReferenceFilter` component

A single reusable component under the data-table feature, consumed by the DataTable when a column's metadata has a `reference` descriptor.

Props (conceptual):
```ts
interface ReferenceFilterProps {
  reference: ReferenceInfo      // from column metadata
  mode: 'single' | 'multi'
  value: number[]               // selected ids
  onChange: (ids: number[]) => void
  onToggleMode: (mode: 'single' | 'multi') => void
}
```

Behavior:
- **Data loading:** `useInfiniteQuery` keyed by `[reference.targetResource, search]`, fetching `optionsPath?page=N&size=PAGE&sort=name,asc&query=name=ct=<search>` via the shared `apiRequest` client (Bearer + 401 handling, FOR-03-06). Next page fetched on scroll-end (IntersectionObserver or scroll handler). `hasNextPage` derived from `last`/`number`/`totalPages`.
- **Search:** debounced (reuse existing `useDebounce`); on term change, reset to page 0.
- **Ordering:** server returns alphabetical by locale name; the component renders in received order.
- **Single mode:** clicking an option selects it (replaces), closes the dropdown; emits `value = [id]`.
- **Multi mode:** each row has a checkbox; selecting toggles membership; a mode toggle in the dropdown header switches single/multi and preserves compatible selection.
- **Option label:** rendered via the option's locale-resolved name (the list DTO already carries the localized `name` from the backend i18n mapping).
- **Clear:** clears selection → `onChange([])`.
- **Permission degradation (Req 5.5):** if the options request returns 403, render the control disabled with a localized "no access" hint instead of surfacing an error.

### 4. Frontend — DataTable integration and filter emission

The DataTable's filter layer maps each active column filter to a query fragment and composes them with AND (existing behavior). Add reference handling:

- Column filter selection: if `column.reference` is present, render `<ReferenceFilter/>`; the DataTable owns the selected-ids state per column.
- Emission (single source of truth), given `idPath` from the descriptor and selected `ids`:
  - `ids.length === 0` → no fragment (filter inactive).
  - `ids.length === 1` → `${idPath}==${ids[0]}`.
  - `ids.length > 1` → `${idPath}=in=(${ids.join(',')})`.
- Composition: reference fragments join the other column fragments with the existing AND joiner, producing a single `query` param the backend already understands.
- Persistence: reference filters participate in the table's existing filter state (URL/query-state) like other filters.

## Data Models

No new persisted entities. Additions are:
- `MetadataResponse.FieldInfo.reference: ReferenceInfo` (transport only).
- Frontend `ReferenceInfo` type mirroring the backend record; `ColumnConfig` gains an optional `reference` populated from metadata.

## Error Handling

- Options request 403 → disabled control + localized hint (Req 5.5); table itself still loads.
- Options request network/5xx → localized error row inside the dropdown with retry; does not break the table.
- Empty search result → localized empty-state (Req 6.4).
- Metadata without `reference` → columns behave exactly as today (Req 5.3).
- Malformed/absent `idPath` (should not happen) → the reference filter is not rendered for that column (fail safe).

## Testing Strategy

### PBT assessment
Property-based testing **applies** to the pure filter-emission function (selected ids → query fragment): it has a clear universal property across generated id sets.

- **Property — filter fragment emission:** For any non-empty set of positive ids and any non-empty `idPath`, the emitted fragment is `idPath==id` when the set has one element and `idPath=in=(id1,...,idN)` (comma-joined, order-stable) when it has more; an empty set emits no fragment. (Frontend unit PBT with fast-check, or a backend PBT if the emission is mirrored server-side for validation.)

### Backend
- Unit: `EntityMetadataResolver` emits a `ReferenceInfo` for a `@ManyToOne` field (correct `idPath`, `targetResource`, `labelField`, `labelI18n`) and emits `null` for scalar fields; existing `FieldInfo` fields unchanged (backward-compat assertion).
- Unit: `ReferenceResourceRegistry` maps entity type → (resource code, base path) from `@PermissionResource` + `@RequestMapping`.
- Integration (`@SpringBootTest` + MockMvc, Testcontainers): `GET /api/users/metadata` returns a reference descriptor for `role` targeting `roles`; `GET /api/roles?sort=name,asc&query=name=ct=<t>` returns name-ordered, filtered, paginated options and is guarded by `ROLES`/`READ` (401 without token, 403 without grant, 200 with grant/ADMIN).
- Integration: end-to-end filter — `GET /api/users?query=role.id==<id>` and `role.id=in=(<id1>,<id2>)` return the expected filtered users.

### Frontend
- Component tests for `ReferenceFilter`: infinite-scroll loads next page; debounced search hits the options endpoint with `name=ct=`; single select emits `[id]`; multi select toggles; mode toggle preserves selection; 403 → disabled state; empty-state renders.
- DataTable integration test: a reference column renders `ReferenceFilter`; combining a reference filter with a text filter composes with AND; a table without reference fields is unchanged.
- PBT: the filter-emission function property above.

Follow the repo test-execution rules: run only the affected test classes/files and read results from a temp log / JUnit XML; do not run the full suite.

## Mobile & Table UX fixes (Requirement 7)

These fixes harden the shared DataTable so the reference filter (and all filters/sorts) are usable on mobile. They are layout/interaction changes to the existing DataTable, not new backend surface.

### 5. Layout — sticky controls, scroll container

Restructure the DataTable into three regions inside a fixed-height flex column so the body scrolls independently:

```
DataTable (flex column, height: 100% of its viewport slot)
├─ Header region  (flex-shrink:0, sticky top)   → search input + filters toggle + applied summary
├─ Body region    (flex:1, overflow-y:auto)      → rows (desktop) / entity cards (mobile)
└─ Footer region  (flex-shrink:0, sticky bottom) → pagination
```

- Header and footer are `flex-shrink: 0`; the body is the only scroll container (`overflow-y:auto`, `min-height:0` so flex children can shrink). This keeps search/filters visible at the top and pagination at the bottom from any scroll position (Req 7.5), replacing today's single-page scroll where mid-list hides both.
- On mobile the same three-region structure applies within the mobile card layout.
- Desktop layout must not regress: the same structure degrades to the existing table with a sticky header row and a fixed footer (Req 7.8).

### 6. Filters panel — interactivity, collapse, applied summary

- **Interactivity fix (Req 7.2):** the expanded filters panel must be a normal in-flow interactive region (correct stacking context, no `pointer-events:none`, no covering overlay, no `z-index` trap). Root-cause the current "nothing clickable" defect (likely an overlay/portal/z-index or a transform-created stacking context on the collapsible) and fix it so inputs, dropdowns, checkboxes, the reference filter, and apply/clear all receive clicks.
- **Toggle label (Req 7.1):** add the missing i18n key for the filters toggle (`dataTable.filters.toggle`) and audit all DataTable control labels for unresolved keys; every label resolves in PL and RU.
- **Apply / collapse / summary (Req 7.6, 7.7):**
  - The panel has an explicit "Apply" affordance (or applies on change, then collapses). On apply, the panel collapses (toggle returns to closed) and a **summary chip/row** renders in the header showing counts: `{filtersCount} filters · {sortsCount} sorts` (localized, with proper plural handling in PL/RU).
  - The summary is itself the reopen control (click → expand panel to review/edit).
  - A "Clear all" action is available from the collapsed summary state, resetting all column filters and sorts and refreshing the table.
  - `filtersCount` = number of columns with an active filter (a reference filter with ≥1 selected id counts as one); `sortsCount` = number of active sort clauses.

### 7. Sorting UX (Req 7.3)

- Provide an explicit sort control: on desktop, sortable column headers expose an ascending/descending toggle with a visible active-direction indicator; on mobile (cards), a "Sort" section inside the filters panel lets the user pick the sort field and direction.
- Chosen sort maps to the existing Spring `sort=field,(asc|desc)` param (multi-sort appends multiple `sort` params, consistent with current backend). The active sort is reflected in the header indicator and counted in the applied summary.

### 8. Entity cards — uniform height & alignment (Req 7.4)

- The mobile entity card is a fixed template: a consistent set of label→value rows with uniform vertical rhythm. Use a consistent min-height and a label/value grid (`grid-template-columns: auto 1fr` or a two-line stacked layout) so labels left-align and values align consistently across all cards.
- Missing/empty values render a consistent placeholder (e.g. "—") so a card's height and alignment do not depend on which fields are populated.
- Cards in a list therefore share the same height and value alignment regardless of content length (long values truncate/ellipsize within the fixed cell).

### i18n keys (PL + RU) added by Requirement 7

`dataTable.filters.toggle`, `dataTable.filters.apply`, `dataTable.filters.clearAll`, `dataTable.filters.summary` (with count interpolation / plural forms), `dataTable.sort.label`, `dataTable.sort.asc`, `dataTable.sort.desc`, plus any other currently-unresolved DataTable control keys surfaced by the audit. All added to both `pl.json` and `ru.json`.

### Testing additions for Requirement 7

- Component tests (jsdom): expanded panel controls are clickable and update filter state; apply collapses the panel and renders the summary with correct `{filters, sorts}` counts; the summary reopens the panel; clear-all resets filters/sorts; the filters toggle renders a resolved label (no raw key).
- Sorting: choosing a column sort emits `sort=field,dir` and reflects the active direction; multi-sort appends params; summary counts sorts.
- Cards: given rows with varying field population, all rendered cards report equal height and consistent label/value alignment (assert consistent structure/classes; empty values show the placeholder).
- Sticky layout: header and footer regions are outside the scrollable body container (assert the DOM structure / overflow container), so they remain visible while the body scrolls. (jsdom cannot measure real scroll pinning; the browser-engine UI test-cases cover visual pinning.)
- No desktop regression: existing DataTable desktop tests continue to pass.
- PBT (optional): the applied-summary counter is a pure function of active-filters/active-sorts state — a small property test (counts equal the number of active entries) may be added.
