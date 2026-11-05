# Bug report: FOR-04-11-work-catalog

Screen URL: `http://localhost:3000/work-catalog` (works catalog list).

## Round 1 (2026-11-04)

Source: meeting notes (RU). Reported bug (one line): "каталог работ, сделать не в алфавитном
порядке, а по категориям, проще искать и вносить изменения — дефолтная сортировка: категория (по
ордеру — сейчас этот столбец вообще без сортировки, добавить правильно через метаданные для этого
контроллера), затем работа". The works catalog defaults to alphabetical (by work name); the user
wants the default sort to be **by work category order, then by work name**, driven by
controller/entity metadata rather than a client-only default.

Background (grounded in code):
- Frontend: `features/work-catalog/components/WorkCatalogList.tsx` sets a **client-only** default
  `const defaultSort = [{ field: 'name', direction: 'asc', priority: 0 }]` passed to `<DataTable
  defaultSort=...>`; the `workCategory` column is `sortable: false`. The sort string is sent to the
  backend `GET /api/work-items?sort=...` via `api/work-catalog-api.ts#fetchWorkItems`.
- Backend: `WorkItemController` extends the generic `AdminController`; the paginated read flows
  `AdminController.find` → `WorkItemService.find` → `AdminService.super.find` →
  `ReadOnlyAdminService.find(Pageable, rawQuery)`. There, `processSort(pageable.getSort())`
  short-circuits with `if (sort.isUnsorted()) return sort;` — i.e. **no server default sort exists**;
  when the client sends no sort the result is DB order. `processSort` otherwise only i18n-resolves
  property names (`name` → `nameRU`/`namePL` by locale, from
  `WorkItemServiceMapper.getI18nSupportedProperties() = {"name"}`).
- Entities: `WorkItemEntity` has `@ManyToOne WorkCategoryEntity workCategory`;
  `WorkCategoryEntity` has `@Column(name="order_no") Integer orderNo` — the category order the user
  means. Desired default sort: `workCategory.orderNo ASC, name(RU/PL) ASC` (with `id ASC` as a
  deterministic tiebreaker). Category-order ordering already exists elsewhere
  (`EstimateMatrixAssembler.categoryOrder()`, `ScheduleRowDerivation`), confirming `orderNo` is the
  canonical key.

- [x] 1. Add a metadata-driven default-sort hook to the generic read service and apply it when the request is unsorted
  - Triage: observed — `ReadOnlyAdminService.find`/`findExtended` apply no default sort; an empty
    client sort yields DB order. Expected — a per-controller default sort applied when
    `pageable.getSort().isUnsorted()`. Layer: **backend**.
    - Add `default Sort getDefaultSort() { return Sort.unsorted(); }` to `ReadOnlyAdminService`.
    - In `find(Pageable, rawQuery)` and `findExtended(...)`, when the incoming sort is unsorted,
      build the `PageRequest` with `processSort(getDefaultSort())` instead of the empty sort (so the
      existing i18n resolution still maps `name` → `nameRU`/`namePL`). When a sort IS supplied,
      behaviour is unchanged.
  - _Requirements: (work-catalog list read / FOR-04-11)_
  - _Depends on: none_
- [x] 2. Override `getDefaultSort()` for work items: category order, then work name
  - Triage: expected default for the works catalog = `workCategory.orderNo ASC, name ASC` (`name`
    i18n-resolved by `processSort`; add `id ASC` as a tiebreaker). Layer: **backend**.
    - Override `getDefaultSort()` in `WorkItemService` to return
      `Sort.by(Order.asc("workCategory.orderNo"), Order.asc("name"), Order.asc("id"))`. Verify the
      `workCategory.orderNo` association path resolves in the generic JPA `Specification` sort (it is
      a plain, non-i18n path and passes through `processSort` untouched).
  - _Requirements: (work-catalog list read / FOR-04-11)_
  - _Depends on: 1_
- [x] 3. Align the frontend so the backend default drives the initial order; make the category column sortable
  - Triage: observed — the client forces `{name, asc}` as the default, overriding any server
    default, and `workCategory` is not sortable. Expected — on first load the list comes back
    category-order then name (server default); the `workCategory` column is sortable. Layer:
    **frontend**.
    - In `WorkCatalogList.tsx`, make the `workCategory` column correctly sortable and set it as the
      **initial sort** (`defaultSort = [{ field: '<category sort key>', direction: 'asc' }]`), and
      ensure the sort state is persisted in the browser session the same way the other CRUD tables
      do (DataTable's standard per-table sessionStorage persistence). Confirm the DataTable
      serializes the sortable reference column to a backend-understood sort key (e.g.
      `workCategory.orderNo`); the backend default from task 2 is the safety net when no sort is
      persisted.
  - _Requirements: (work-catalog list read / FOR-04-11)_
  - _Depends on: 2_
- [x] V1. Validate Round 1 in browser
  - Bring up the local stack, open `/work-catalog`, confirm the list loads ordered by category
    order (categories in `orderNo` sequence) then by work name within each category, and that
    explicit column sorting still works (clicking a sortable header overrides the default).
  - _Requirements: (work-catalog list read / FOR-04-11)_
  - _Depends on: 1, 2, 3_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1 — the generic `getDefaultSort()` hook + its use in `find`/`findExtended` (foundation).
- **Wave 2:** 2 — the work-item override (depends on the hook from 1).
- **Wave 3:** 3 — the frontend alignment (depends on the server default from 2).
- **Wave 4:** V1 — browser validation; depends on 1, 2, 3.

## Round 1 — validation results (2026-11-04)

Stack: local Docker (`backend` :8080, `frontend` :3000, admin `cto+1@loaders.dev`). Screen:
`http://localhost:3000/catalog/works`. Decision applied: the category column is correctly sortable
and set as the **initial sort**; the chosen sort persists via the DataTable's standard per-table
browser persistence (keyed by `entityKey`), like every other CRUD table.

- **#1 generic `getDefaultSort()` hook + #2 work-item override — ✅ FIXED.** Backend compiles
  (`compileJava` green); `I18nSortResolutionPropertyTest` 8/8 green (`processSort` untouched).
  Verified live on the Dockerized API with **no sort param** — results come back ordered by category
  `orderNo`, then name: the distinct categories appear in exact `order_no` sequence (1 PRELIMINARY,
  2 CONSTRUCTIONS_GK, 3 PLUMBING_ROUGH, 4 PLUMBING_FINISH, 5 ELECTRICAL_ROUGH, 6 ELECTRICAL_FINISH,
  7 TILING, 8 PLASTERING …), proving the backend default sort applies.
- **#3 frontend sortable category column + initial sort — ✅ FIXED.** On `/catalog/works` the first
  page is entirely the first category ("Подготовительные работы, демонтажи" / PRELIMINARY,
  `order_no=1`) with works alphabetical within ("Вентиляция…" → "Демонтаж…" → "Заделка…" →
  "Защита…"). The two sorted headers carry multi-sort priority badges — "Категория работ**0**"
  (primary, `workCategory.orderNo`) and "Название**1**" (secondary, `name`) — confirming the
  two-key default is active and the category column is now sortable. Unit tests: `work-catalog` +
  `data-table` suites 186/186 green.

Result: all tasks (1, 2, 3) fixed / 0 failed. All Round 1 bugs closed.
