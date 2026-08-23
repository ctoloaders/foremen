# Implementation Plan: Audit Comparison View

## Overview

Replace the raw JSON expander in the audit UI with a structured comparison table showing field-level diffs. Implementation is frontend-only (TypeScript/React) and consists of pure utility functions, a React component, column config update, and i18n additions.

## Tasks

- [x] 1. Implement pure utility functions
  - [x] 1.1 Create `computeDiff`, `formatValue`, and `isErrorSnapshot` utilities
    - Create file `src/features/audit/utils/compute-diff.ts`
    - Implement `DiffStatus` type and `DiffEntry` interface
    - Implement `computeDiff(before, after)` — computes field-level diff between two nullable snapshot maps; treats null as empty map; classifies each field as `changed`, `added`, `deleted`, or `unchanged` using `JSON.stringify` deep equality; returns entries sorted alphabetically by field name
    - Implement `formatValue(value)` — formats null/undefined as `"—"`, object/array as `JSON.stringify(value)`, primitives as `String(value)`
    - Implement `isErrorSnapshot(snapshot)` — returns `true` only when snapshot has exactly two keys: `"class"` and `"error"`
    - Export a helper `getRowBackground(status: DiffStatus): string | undefined` returning rgba values for changed/added/deleted and undefined for unchanged
    - _Requirements: 1.2, 1.3, 1.4, 9.1, 9.5_

- [x] 2. Implement ComparisonTable component
  - [x] 2.1 Create `ComparisonTable` React component
    - Create file `src/features/audit/components/ComparisonTable.tsx`
    - Accept props: `snapshotBefore`, `snapshotAfter`, `operation`
    - For `CREATE` operation: render green badge with i18n key `audit.comparison.badgeNew`
    - For `DELETE` operation: render red badge with i18n key `audit.comparison.badgeDeleted`
    - If either snapshot is an error snapshot: render red badge with i18n key `audit.comparison.badgeError` + error message
    - For `UPDATE` (and other operations): call `computeDiff`, apply default filter (hide unchanged), render three-column table (Field | Value Before | Value After)
    - Implement `showAll` toggle using i18n keys `audit.comparison.showAll` / `audit.comparison.showChanges`
    - When all fields are unchanged display "no changes" message via i18n key `audit.comparison.noChanges`
    - Apply row background colors via `getRowBackground` for each entry's status
    - Use existing design tokens: `bg-muted` for header, `border-border` for borders, `text-foreground` for text
    - Badges use `text-green-500`/`text-red-500` with subtle borders per Requirement 8
    - _Requirements: 1.1, 1.5, 1.6, 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 5.1, 5.2, 5.3, 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 9.2, 9.3, 9.4, 9.5_

- [x] 3. Update column configuration and integrate
  - [x] 3.1 Replace snapshot columns with single "Changes" column
    - Modify `src/features/audit/config/audit-columns.ts`
    - Remove the two existing columns `snapshotBefore` and `snapshotAfter`
    - Add a new column with `field: 'changes'`, `headerKey: 'audit.column.changes'`, `sortable: false`, `filterable: false`, `searchable: false`
    - The `render` function receives `(_value, row)` and creates `ComparisonTable` element with `snapshotBefore`, `snapshotAfter`, `operation` from the row
    - Ensure `auditModalColumns` derived array also includes the new "Changes" column (it inherits from `auditFullColumns` minus entityClass/entityId)
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [x] 4. Add i18n translations
  - [x] 4.1 Add RU and PL locale keys for comparison view
    - Update `src/locales/ru.json` — add `audit.comparison` object with keys: `fieldHeader` ("Поле"), `valueBefore` ("Было"), `valueAfter` ("Стало"), `badgeNew` ("Новый"), `badgeDeleted` ("Удалён"), `badgeError` ("Ошибка"), `showAll` ("Все поля"), `showChanges` ("Только изменения"), `noChanges` ("Нет изменений"); add `audit.column.changes` ("Изменения")
    - Update `src/locales/pl.json` — add `audit.comparison` object with keys: `fieldHeader` ("Pole"), `valueBefore` ("Było"), `valueAfter` ("Stało się"), `badgeNew` ("Nowy"), `badgeDeleted` ("Usunięty"), `badgeError` ("Błąd"), `showAll` ("Wszystkie pola"), `showChanges` ("Tylko zmiany"), `noChanges` ("Brak zmian"); add `audit.column.changes` ("Zmiany")
    - _Requirements: 7.1, 7.2, 7.3_

- [x] 5. Checkpoint — Verify integration
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Property-based tests for utility functions
  - [x] 6.1 Write property test: Key Completeness
    - Create file `src/features/audit/__tests__/compute-diff.property.test.ts`
    - Use `fast-check` to generate two arbitrary `Record<string, unknown>` maps
    - Assert: set of `field` values in `computeDiff` output equals union of keys from both input maps
    - Tag: `Feature: FOR-02-06a-audit-comparison-view, Property 1: Key Completeness`
    - Minimum 100 iterations
    - **Property 1: Key Completeness**
    - **Validates: Requirements 1.2**

  - [x] 6.2 Write property test: Value Formatting Consistency
    - In the same test file, add property test for `formatValue`
    - Generate arbitrary values (objects, arrays, primitives, null, undefined)
    - Assert: objects/arrays → `JSON.stringify(value)`, null/undefined → `"—"`, primitives → `String(value)`
    - Tag: `Feature: FOR-02-06a-audit-comparison-view, Property 2: Value Formatting Consistency`
    - **Property 2: Value Formatting Consistency**
    - **Validates: Requirements 1.3, 1.4**

  - [x] 6.3 Write property test: Default Filter Correctness
    - Generate two non-null maps with known overlapping/disjoint keys
    - Filter `computeDiff` output to exclude `'unchanged'` entries
    - Assert: remaining entries have `valueBefore !== valueAfter` (by `JSON.stringify`) or field absent from one map
    - Tag: `Feature: FOR-02-06a-audit-comparison-view, Property 3: Default Filter Correctness`
    - **Property 3: Default Filter Correctness**
    - **Validates: Requirements 2.1**

  - [x] 6.4 Write property test: Classification-to-Color Mapping Completeness
    - Generate diff entries with random statuses
    - Assert: `getRowBackground` returns correct rgba values for each status (yellow for changed, green for added, red for deleted, undefined for unchanged)
    - Tag: `Feature: FOR-02-06a-audit-comparison-view, Property 4: Classification-to-Color Mapping Completeness`
    - **Property 4: Classification-to-Color Mapping Completeness**
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4**

  - [x] 6.5 Write property test: Error Snapshot Detection
    - Generate maps with varying key sets (including exact `["class", "error"]`)
    - Assert: `isErrorSnapshot` returns `true` only when keys are exactly `["class", "error"]`
    - Tag: `Feature: FOR-02-06a-audit-comparison-view, Property 5: Error Snapshot Detection`
    - **Property 5: Error Snapshot Detection**
    - **Validates: Requirements 9.1, 9.5**

- [x] 7. Unit tests for ComparisonTable component
  - [x] 7.1 Write unit tests for ComparisonTable rendering
    - Create file `src/features/audit/__tests__/ComparisonTable.test.tsx`
    - Use Vitest + React Testing Library
    - Test: renders three-column header (Field, Value Before, Value After) for UPDATE
    - Test: CREATE operation shows green "New" badge, no table
    - Test: DELETE operation shows red "Deleted" badge, no table
    - Test: Error snapshot shows "Error" badge with error message text
    - Test: Toggle between "changes only" and "all fields" shows/hides unchanged rows
    - Test: "No changes" message when snapshotBefore equals snapshotAfter
    - Test: Column config (`auditFullColumns`) has single "Changes" column replacing snapshotBefore/snapshotAfter
    - _Requirements: 1.1, 2.1, 2.2, 2.5, 4.1, 4.2, 5.1, 5.2, 9.2, 9.3_

- [x] 8. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific rendering scenarios and edge cases
- The implementation language is TypeScript (React + Vitest + fast-check) as specified in the design
- No backend changes needed — this is frontend-only

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "4.1"] },
    { "id": 1, "tasks": ["2.1"] },
    { "id": 2, "tasks": ["3.1"] },
    { "id": 3, "tasks": ["6.1", "6.2", "6.3", "6.4", "6.5", "7.1"] }
  ]
}
```
