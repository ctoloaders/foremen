# Implementation Plan: Table Template Enhancements (FOR-02-06t)

## Overview

Implement the reusable DataTable component at `src/components/data-table/` for the Foremen admin panel. Covers: global search, multi-sort with priority, contextual column filters (string/number/date), localStorage state persistence, responsive layout (card-based mobile), pagination, and loading/error/empty states. Backend extends `AdminController` with a `/metadata` endpoint via reflection. Frontend uses TypeScript, React 19, TanStack Query 5, shadcn/ui, fast-check for PBT. Backend uses Java 21, Spring Boot 3, jqwik for PBT.

## Tasks

- [x] 1. Install shadcn/ui dependencies and create type definitions
  - [x] 1.1 Install required shadcn/ui components
    - Run `npx shadcn@latest add button input popover calendar select tooltip skeleton` in `foremen-frontend/`
    - Verify components are generated in `src/components/ui/`
    - _Requirements: 16.1, 16.2, 16.3, 16.4, 16.5, 16.6, 16.7, 16.8_

  - [x] 1.2 Create DataTable TypeScript interfaces and types
    - Create `src/components/data-table/types.ts` with all interfaces: `ColumnDataType`, `SortDirection`, `ColumnConfig<T>`, `SortState`, `StringFilterState`, `NumberFilterState`, `DateFilterState`, `ColumnFilterState` (union), `TableState`, `DataTableProps<T>`, `FetchParams`, `PaginatedResponse<T>`, `EntityMetadata`, `FieldMetadata`
    - Match types exactly as defined in design document Data Models section
    - Create `src/components/data-table/index.ts` barrel export
    - _Requirements: 1.1, 1.5_

- [x] 2. Implement pure utility functions
  - [x] 2.1 Implement `resolveFieldValue` utility
    - Create `src/components/data-table/utils/resolveFieldValue.ts`
    - Implement dot-notation traversal: splits path by `.`, iterates through keys, returns `undefined` if any intermediate is null/undefined
    - _Requirements: 1.3_

  - [x] 2.2 Implement `buildQueryString` utility
    - Create `src/components/data-table/utils/buildQueryString.ts`
    - Implement global search: collect searchable string columns, build OR-joined `field~ct~value` conditions wrapped in parentheses
    - Implement filter conditions: string → `field~ct~value`, number → `field=gte=from AND field=lte=to`, date → `field=gte=isoDate AND field=lte=isoDate`
    - Combine all parts with AND: `(search_OR_group) AND (filter_1) AND (filter_2)`
    - _Requirements: 2.3, 2.4, 2.5, 4.3, 5.3, 5.4, 5.5, 6.3, 6.4, 6.5, 7.1, 7.2, 7.3_

  - [x] 2.3 Implement `buildSortParams` utility
    - Create `src/components/data-table/utils/buildSortParams.ts`
    - Sort by priority (ascending), map to `["field,direction", ...]` format
    - _Requirements: 3.6_

  - [x] 2.4 Write property tests for `buildQueryString`
    - **Property 1: Query string round-trip consistency**
    - **Property 2: Filter combination produces AND-joined conditions**
    - **Property 3: Global search produces OR-joined conditions over searchable fields**
    - **Validates: Requirements 2.3, 2.4, 4.3, 5.3–5.5, 6.3–6.5, 7.1, 7.2, 7.3**

  - [x] 2.5 Write property tests for `buildSortParams` and `resolveFieldValue`
    - **Property 4: Sort priority is a valid permutation** (test output ordering)
    - **Property 8: Dot-notation field resolution**
    - **Validates: Requirements 1.3, 3.4, 3.5, 3.6**

- [x] 3. Implement state management hooks
  - [x] 3.1 Implement `useTableState` reducer hook
    - Create `src/components/data-table/hooks/useTableState.ts`
    - Implement reducer with actions: `SET_SEARCH` (update search, reset page), `TOGGLE_SORT` (cycle asc→desc→unsorted, recalculate priorities), `SET_FILTER` (add/update filter, reset page), `CLEAR_FILTER` (remove filter, reset page), `CLEAR_ALL` (reset to defaults), `SET_PAGE`, `SET_PAGE_SIZE` (reset page), `RESTORE_STATE`
    - Sort cycle: unsorted → ascending → descending → unsorted; priority re-indexed on removal
    - Accept `defaultPageSize` parameter
    - _Requirements: 2.7, 3.2, 3.4, 3.5, 3.7, 3.8, 7.4, 8.4, 11.4_

  - [x] 3.2 Implement `useLocalStoragePersistence` hook
    - Create `src/components/data-table/hooks/useLocalStoragePersistence.ts`
    - On mount: read `foremen:table:{entityKey}` from localStorage, dispatch `RESTORE_STATE` if found
    - On state change: debounced write (500ms) to localStorage
    - On clear all: remove key from localStorage
    - Graceful degradation: catch localStorage errors silently
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7_

  - [x] 3.3 Write property tests for state reducer
    - **Property 4: Sort priority is a valid permutation** (priorities contiguous 1..N, no duplicates)
    - **Property 5: Sort cycle is idempotent after three clicks**
    - **Property 9: Clear all resets to default state**
    - **Property 10: localStorage persistence round-trip**
    - **Property 11: Pagination reset on state change**
    - **Validates: Requirements 2.7, 3.2, 3.4, 3.5, 3.7, 3.8, 7.4, 8.4, 9.1, 9.2, 9.3, 9.6**

- [x] 4. Implement `useDataTable` orchestrator hook
  - [x] 4.1 Create `useDataTable` main hook
    - Create `src/components/data-table/hooks/useDataTable.ts`
    - Compose `useTableState`, `useLocalStoragePersistence`, `buildQueryString`, `buildSortParams`
    - Integrate TanStack Query: query key = `[entityKey, 'list', page, size, sortParams, queryString]`, staleTime 30s
    - Return `{ state, dispatch, query, queryString }`
    - _Requirements: 1.1, 1.5, 2.2, 11.1_

- [x] 5. Checkpoint - Verify hooks and utilities compile, property tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement DataTable sub-components
  - [x] 6.1 Implement `DataTableToolbar`
    - Create `src/components/data-table/DataTableToolbar.tsx`
    - Render Global_Search input (shadcn Input + Lucide `Search` icon prefix) with 300ms debounce
    - Render active filter count badge from i18n key `dataTable.filters.activeCount`
    - Render Clear_All_Button (shadcn Button ghost + Lucide `FilterX` icon + Tooltip) — visible/enabled only when filters or search active
    - _Requirements: 2.1, 2.2, 2.6, 7.5, 8.1, 8.2, 8.3, 8.4, 8.5, 16.5, 16.6, 16.8_

  - [x] 6.2 Implement `DataTableHeader` with sort and filter controls
    - Create `src/components/data-table/DataTableHeader.tsx`
    - Render one TableHead per column from config
    - For sortable columns: clickable header, sort direction arrow (up/down), numeric priority badge for multi-sort
    - For filterable columns: Lucide `Filter` icon button to the right of header text; highlighted (primary color) when filter active, with clear (`X`) icon next to it
    - _Requirements: 1.2, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.4, 4.5, 4.6, 5.1, 5.7, 6.1, 6.7, 15.2, 15.3_

  - [x] 6.3 Implement filter popover components
    - Create `src/components/data-table/filters/FilterPopover.tsx` — generic shadcn Popover wrapper
    - Create `src/components/data-table/filters/StringFilter.tsx` — text input with placeholder `dataTable.filter.string.placeholder`, Apply button, pre-populated when editing active filter
    - Create `src/components/data-table/filters/NumberFilter.tsx` — two numeric inputs "От"/"До" with labels from i18n, range validation (to > from), error message from `dataTable.filter.number.rangeError`
    - Create `src/components/data-table/filters/DateFilter.tsx` — two date picker inputs (Popover + Calendar) with labels from i18n, range validation, error message from `dataTable.filter.date.rangeError`, locale-aware formatting
    - _Requirements: 4.2, 4.3, 4.6, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 15.4, 16.2, 16.3_

  - [x] 6.4 Implement `DataTableBody` and `DataTableCards`
    - Create `src/components/data-table/DataTableBody.tsx` — desktop table body using `resolveFieldValue` to render each cell, custom `render` fn from ColumnConfig if provided
    - Create `src/components/data-table/DataTableCards.tsx` — mobile card layout (viewport < 768px via `useBreakpoint`), one card per record with key fields stacked vertically
    - _Requirements: 1.2, 1.3, 13.1, 13.2, 13.4_

  - [x] 6.5 Implement `DataTablePagination`
    - Create `src/components/data-table/DataTablePagination.tsx`
    - Desktop: prev/next buttons, page numbers, total count (`dataTable.pagination.showing`), page size selector (Select: 10, 25, 50)
    - Mobile: compact — prev/next + current page only
    - Disable prev on first page, next on last page
    - Reset to page 1 on page size change
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 13.6, 16.4, 16.7_

  - [x] 6.6 Implement `DataTableSkeleton` and `DataTableEmpty`
    - Create `src/components/data-table/DataTableSkeleton.tsx` — 5 skeleton rows with shimmer/pulse using muted (#27272a) color, matching column count
    - Create `src/components/data-table/DataTableEmpty.tsx` — empty state message from `dataTable.empty.filtered` with suggestion to clear filters; error state with `dataTable.error.retry` button; subtle loading indicator (opacity reduction) during refetch
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 15.5_

- [x] 7. Implement main `DataTable` orchestrator component
  - [x] 7.1 Create `DataTable.tsx` main component
    - Create `src/components/data-table/DataTable.tsx`
    - Compose all sub-components: Toolbar, Header, Body (desktop) / Cards (mobile via `useBreakpoint`), Pagination, Skeleton, Empty
    - Use `useDataTable` hook for state management and data fetching
    - Conditionally render: Skeleton during initial load, Empty when no results, Error on fetch failure, Body/Cards with data
    - Desktop (>1024px): full table with inline controls
    - Tablet (768–1024px): table with horizontal scroll, sticky first column
    - Mobile (<768px): cards + collapsible filter toolbar with "Filters" button
    - _Requirements: 1.1, 1.2, 1.4, 12.1, 12.2, 12.3, 12.4, 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 15.1, 16.1_

- [x] 8. Checkpoint - Verify DataTable renders in isolation with mock data
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Backend: Metadata endpoint
  - [x] 9.1 Implement `EntityMetadataResolver` utility class
    - Create `src/main/java/com/foremen/util/EntityMetadataResolver.java`
    - Implement `resolve(Class<?>)` with `ConcurrentHashMap` caching (compute-if-absent)
    - First pass: identify i18n base fields by locale suffixes (RU, PL)
    - Second pass: build `FieldInfo` list — skip suffixed i18n fields, skip JPA internals
    - Map Java types: String→STRING, Integer/Long/Double/BigDecimal→NUMBER, LocalDate/LocalDateTime→DATE, Boolean→BOOLEAN, Enum→ENUM
    - Recursively resolve nested entities (`@Embedded`, `@ManyToOne`, `@OneToOne`)
    - _Requirements: 10.2, 10.3, 10.4, 10.5, 10.8_

  - [x] 9.2 Create `MetadataResponse` DTO and add default endpoint to `AdminController`
    - Create `src/main/java/com/foremen/controller/model/MetadataResponse.java` — record with `List<FieldInfo> fields`, nested record `FieldInfo(String name, DataType dataType, boolean i18n, List<FieldInfo> nested)`, enum `DataType { STRING, NUMBER, DATE, BOOLEAN, ENUM }`
    - Add `@GetMapping("/metadata")` default method to `AdminController` interface that calls `EntityMetadataResolver.resolve(getService().getDaoModelClass())` and returns with `Cache-Control: max-age=86400`
    - _Requirements: 10.1, 10.6, 10.7_

  - [x] 9.3 Write backend property tests for `EntityMetadataResolver` (jqwik)
    - Test correct Java type → DataType mapping for all type combinations
    - Test i18n field identification (nameRU/namePL → name.i18n=true)
    - Test nested field traversal for @ManyToOne relationships
    - Test cache returns same instance on repeated calls
    - _Requirements: 10.2, 10.3, 10.4, 10.5, 10.8_

  - [x] 9.4 Write backend integration test for metadata endpoint (MockMvc)
    - Test GET `/api/roles/metadata` returns valid JSON with field list
    - Test response includes `Cache-Control: max-age=86400` header
    - Test i18n fields are correctly identified for RoleEntity
    - Test nested fields present for entities with @ManyToOne
    - _Requirements: 10.1, 10.6, 10.7_

- [x] 10. i18n translations
  - [x] 10.1 Add `dataTable` namespace translations (PL and RU)
    - Add `dataTable` key to `src/locales/pl.json` with all translation keys: `search.placeholder` ("Szukaj..."), `filter.string.placeholder` ("Zawiera..."), `filter.number.from` ("Od"), `filter.number.to` ("Do"), `filter.date.from` ("Od"), `filter.date.to` ("Do"), `filter.number.rangeError`, `filter.date.rangeError`, `filters.clearAll` ("Wyczyść filtry"), `filters.activeCount` ("Aktywne filtry: {{count}}"), `empty.filtered`, `error.retry` ("Ponów"), `pagination.showing`
    - Add equivalent `dataTable` key to `src/locales/ru.json` with Russian translations as specified in Requirements 14.5
    - _Requirements: 14.1, 14.2, 14.3, 14.5_

- [x] 11. Checkpoint - Full integration test: DataTable with live backend
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. Write component tests
  - [x] 12.1 Write component tests for DataTable rendering
    - Test renders correct number of columns from config
    - Test skeleton renders during loading state
    - Test empty state shown when content array is empty
    - Test error state with retry button on fetch failure
    - Test mobile card layout renders when breakpoint = 'mobile'
    - _Requirements: 1.2, 12.1, 12.2, 12.4, 13.1_

  - [x] 12.2 Write component tests for sort and filter interactions
    - Test sort indicator changes on header click (unsorted → asc → desc → unsorted)
    - Test multi-sort priority badge displays correct numbers
    - Test filter popover opens/closes on icon click
    - Test string filter applies on Enter/Apply
    - Test number filter range validation error
    - Test date filter range validation error
    - Test filter icon highlights when filter active
    - Test clear icon removes individual filter
    - _Requirements: 3.1, 3.2, 3.3, 3.5, 4.2, 4.4, 4.5, 5.2, 5.6, 6.2, 6.6_

  - [x] 12.3 Write component tests for toolbar and pagination
    - Test Clear_All button hidden when no filters active
    - Test Clear_All button visible and functional when filters active
    - Test pagination buttons disabled at boundaries
    - Test page size selector changes trigger refetch
    - Test Global_Search debounce (300ms) behavior
    - _Requirements: 2.2, 8.2, 8.3, 8.4, 11.5, 11.6_

  - [x] 12.4 Write property tests for number/date filter validation
    - **Property 6: Number filter range validation** (to > from = valid, to ≤ from = error)
    - **Property 7: Date filter range validation** (to after from = valid, to ≤ from = error)
    - **Validates: Requirements 5.5, 5.6, 6.5, 6.6**

## Notes

- Each task references specific requirements for traceability
- Tasks marked with `*` are optional and can be skipped for faster MVP
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit/component tests validate specific examples and edge cases
- The `data-table/` directory is shared (not inside a feature module) since DataTable is used across all admin pages
- The backend metadata endpoint is a default method in `AdminController` — no per-entity implementation needed
- `fast-check` is not yet installed — task 2.4 or 3.3 should add it as devDependency if missing
- Existing patterns to follow: `getHeaders()` + `handleResponse()` from roles-api, `useBreakpoint()` hook, `useUIStore` for locale, sonner for toasts
- shadcn/ui Table component already exists in the project; Button, Input, Popover, Calendar, Select, Tooltip, Skeleton need to be added (task 1.1)

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["2.1", "2.2", "2.3"] },
    { "id": 2, "tasks": ["2.4", "2.5", "3.1"] },
    { "id": 3, "tasks": ["3.2", "3.3"] },
    { "id": 4, "tasks": ["4.1"] },
    { "id": 5, "tasks": ["6.1", "6.2", "6.4", "6.5", "6.6", "9.1"] },
    { "id": 6, "tasks": ["6.3", "9.2"] },
    { "id": 7, "tasks": ["7.1", "9.3", "9.4", "10.1"] },
    { "id": 8, "tasks": ["12.1", "12.2", "12.3", "12.4"] }
  ]
}
```
