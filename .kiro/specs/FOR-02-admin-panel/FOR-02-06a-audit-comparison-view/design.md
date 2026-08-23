# Design Document — Audit Comparison View

## Overview

This feature replaces the raw JSON expander (`JsonExpander`) in the audit UI with a structured comparison table that shows field-level differences between `snapshotBefore` and `snapshotAfter`. The change is frontend-only — no backend modifications are needed since the backend already provides both snapshots as `Map<String, Object>`.

The implementation consists of:
1. A **pure utility function** (`computeDiff`) that compares two snapshot maps and classifies each field as changed, added, deleted, or unchanged.
2. A **ComparisonTable** React component that renders the diff output as a three-column table with color-coded rows.
3. Updated **column configuration** that merges the two snapshot columns into a single "Changes" column.
4. **i18n additions** for both RU and PL locales.

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Pure diff utility separated from rendering | Enables property-based testing of the core logic independently from React rendering |
| Single "Changes" column replaces two snapshot columns | Reduces visual clutter; the diff table provides a more informative view than two separate JSON blobs |
| Default to "changes only" filter | Most audit reviews focus on what changed, not what stayed the same |
| Badge-based display for CREATE/DELETE/Error | These operations don't have meaningful two-sided comparisons |

---

## Architecture

```mermaid
graph TD
    A[AuditRecord] --> B[ComparisonTable]
    B --> C{operation type?}
    C -->|CREATE| D[Badge: New]
    C -->|DELETE| E[Badge: Deleted]
    C -->|UPDATE| F{Error snapshot?}
    F -->|Yes| G[Badge: Error + message]
    F -->|No| H[computeDiff]
    H --> I[DiffEntry[]]
    I --> J{showAll toggle?}
    J -->|false| K[Filter: only changed/added/deleted]
    J -->|true| L[All entries]
    K --> M[Render Table Rows]
    L --> M
```

The ComparisonTable component acts as a controller that decides which rendering path to take based on the operation type and snapshot content. The `computeDiff` utility is a pure function that takes two nullable maps and returns a classified list of diff entries.

---

## Components and Interfaces

### 1. `computeDiff` — Pure Utility

**Location:** `src/features/audit/utils/compute-diff.ts`

```typescript
export type DiffStatus = 'changed' | 'added' | 'deleted' | 'unchanged'

export interface DiffEntry {
  field: string
  valueBefore: unknown
  valueAfter: unknown
  status: DiffStatus
}

/**
 * Computes field-level diff between two snapshot maps.
 * Returns one DiffEntry per unique field across both maps.
 */
export function computeDiff(
  before: Record<string, unknown> | null,
  after: Record<string, unknown> | null,
): DiffEntry[]
```

**Classification rules:**
- Field in `after` only (or `before[field]` is undefined) → `added`
- Field in `before` only (or `after[field]` is undefined) → `deleted`
- Field in both, values differ (deep equality via `JSON.stringify`) → `changed`
- Field in both, values equal → `unchanged`

**Ordering:** Entries are sorted alphabetically by field name for deterministic output.

### 2. `formatValue` — Value Formatter

**Location:** `src/features/audit/utils/compute-diff.ts` (co-located)

```typescript
/**
 * Formats a snapshot field value for display.
 * - null/undefined → "—"
 * - object/array → JSON.stringify(value)
 * - primitives → String(value)
 */
export function formatValue(value: unknown): string
```

### 3. `isErrorSnapshot` — Error Detection

**Location:** `src/features/audit/utils/compute-diff.ts` (co-located)

```typescript
/**
 * Detects if a snapshot is an error snapshot.
 * An error snapshot has exactly two fields: "class" and "error".
 */
export function isErrorSnapshot(snapshot: Record<string, unknown> | null): boolean
```

### 4. `ComparisonTable` — React Component

**Location:** `src/features/audit/components/ComparisonTable.tsx`

```typescript
interface ComparisonTableProps {
  snapshotBefore: Record<string, unknown> | null
  snapshotAfter: Record<string, unknown> | null
  operation: string
}

export function ComparisonTable(props: ComparisonTableProps): React.ReactElement
```

**Rendering logic:**
1. If `operation === 'CREATE'` → render green "New" badge
2. If `operation === 'DELETE'` → render red "Deleted" badge
3. If either snapshot is an error snapshot (`isErrorSnapshot`) → render red "Error" badge + error message
4. Otherwise → call `computeDiff`, apply filter, render table

**Internal state:**
- `showAll: boolean` (default: `false`) — controls the diff filter toggle

### 5. Updated Column Configuration

**Location:** `src/features/audit/config/audit-columns.ts` (modified)

The two columns (`snapshotBefore`, `snapshotAfter`) are replaced by a single column:

```typescript
{
  field: 'changes',
  headerKey: 'audit.column.changes',
  dataType: 'string',
  sortable: false,
  filterable: false,
  searchable: false,
  render: (_value, row) => React.createElement(ComparisonTable, {
    snapshotBefore: row.snapshotBefore,
    snapshotAfter: row.snapshotAfter,
    operation: row.operation,
  }),
}
```

---

## Data Models

### DiffEntry

| Field | Type | Description |
|-------|------|-------------|
| `field` | `string` | The field key from the snapshot map |
| `valueBefore` | `unknown` | Value from `snapshotBefore` (undefined if field absent) |
| `valueAfter` | `unknown` | Value from `snapshotAfter` (undefined if field absent) |
| `status` | `DiffStatus` | Classification: `'changed' \| 'added' \| 'deleted' \| 'unchanged'` |

### Row Color Mapping

| Status | Background Style | CSS Value |
|--------|-----------------|-----------|
| `changed` | Yellow accent | `rgba(234, 179, 8, 0.15)` |
| `added` | Green accent | `rgba(34, 197, 94, 0.15)` |
| `deleted` | Red accent | `rgba(239, 68, 68, 0.15)` |
| `unchanged` | None (transparent) | — |

### i18n Keys (audit namespace)

| Key | RU | PL |
|-----|----|----|
| `audit.comparison.fieldHeader` | Поле | Pole |
| `audit.comparison.valueBefore` | Было | Było |
| `audit.comparison.valueAfter` | Стало | Stało się |
| `audit.comparison.badgeNew` | Новый | Nowy |
| `audit.comparison.badgeDeleted` | Удалён | Usunięty |
| `audit.comparison.badgeError` | Ошибка | Błąd |
| `audit.comparison.showAll` | Все поля | Wszystkie pola |
| `audit.comparison.showChanges` | Только изменения | Tylko zmiany |
| `audit.comparison.noChanges` | Нет изменений | Brak zmian |
| `audit.column.changes` | Изменения | Zmiany |

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Key Completeness

*For any* two nullable snapshot maps (before, after), the set of `field` values returned by `computeDiff` SHALL equal the union of keys from both maps.

**Validates: Requirements 1.2**

### Property 2: Value Formatting Consistency

*For any* value that is an object or array, `formatValue(value)` SHALL return exactly `JSON.stringify(value)`. *For any* null or undefined value, `formatValue(value)` SHALL return `"—"`. *For any* primitive value, `formatValue(value)` SHALL return `String(value)`.

**Validates: Requirements 1.3, 1.4**

### Property 3: Default Filter Correctness

*For any* two non-null snapshot maps, the entries returned by `computeDiff` filtered to exclude `'unchanged'` status SHALL contain only entries where `valueBefore` differs from `valueAfter` (by deep equality), or where the field is absent in one of the maps.

**Validates: Requirements 2.1**

### Property 4: Classification-to-Color Mapping Completeness

*For any* `DiffEntry`, the row background color applied SHALL be: yellow for `'changed'`, green for `'added'`, red for `'deleted'`, and none for `'unchanged'`. No other mapping exists.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4**

### Property 5: Error Snapshot Detection

*For any* snapshot map and *for any* operation type, if the map has exactly two keys (`"class"` and `"error"`), then `isErrorSnapshot` SHALL return `true`. For any map with a different key set, it SHALL return `false`.

**Validates: Requirements 9.1, 9.5**

---

## Error Handling

| Scenario | Handling |
|----------|----------|
| `snapshotBefore` is `null` | Treated as empty map `{}` in `computeDiff` — all fields from `after` become `added` |
| `snapshotAfter` is `null` | Treated as empty map `{}` in `computeDiff` — all fields from `before` become `deleted` |
| Both snapshots are `null` | `computeDiff` returns empty array; ComparisonTable renders "no changes" message |
| Field value is deeply nested object | Serialized to JSON string via `JSON.stringify` for display |
| Error snapshot detected | Badge rendering with error message; diff table is not rendered |
| Unknown operation type (not CREATE/UPDATE/DELETE) | Falls through to UPDATE logic (compute diff normally) |

---

## Testing Strategy

### Unit Tests (Vitest + React Testing Library)

**Component tests (`ComparisonTable.test.tsx`):**
- Renders three-column header (Field, Value Before, Value After)
- CREATE operation shows "New" badge, no table
- DELETE operation shows "Deleted" badge, no table
- Error snapshot shows "Error" badge with error message
- Toggle between "changes only" and "all fields"
- "No changes" message when snapshots are identical
- Column config has single "Changes" column (no snapshotBefore/snapshotAfter)

### Property-Based Tests (Vitest + fast-check)

The `computeDiff`, `formatValue`, and `isErrorSnapshot` utility functions are pure functions with clear input/output behavior and a wide input space — ideal for PBT.

**Library:** `fast-check` (already in devDependencies)

**Configuration:**
- Minimum 100 iterations per property test
- Each test tagged with: `Feature: FOR-02-06a-audit-comparison-view, Property {N}: {title}`

**Test file:** `src/features/audit/__tests__/compute-diff.property.test.ts`

**Properties to implement:**
1. **Key Completeness** — generate two arbitrary `Record<string, unknown>` maps, verify output field set equals union of input key sets
2. **Value Formatting** — generate arbitrary values, verify `formatValue` output matches expected formatting rules
3. **Default Filter Correctness** — generate two maps with known overlapping/disjoint keys, verify filtered entries contain no `'unchanged'` entries and all non-unchanged entries are correctly classified
4. **Classification-to-Color Mapping** — generate diff entries with random statuses, verify the `getRowBackground` helper returns the correct rgba value or undefined
5. **Error Snapshot Detection** — generate maps with varying key sets, verify detection is `true` only when keys are exactly `["class", "error"]`

### Test Coverage Goals

| Area | Strategy | Coverage |
|------|----------|----------|
| `computeDiff` | Property tests (100+ inputs) | High — core logic |
| `formatValue` | Property tests | High — formatting edge cases |
| `isErrorSnapshot` | Property tests | High — detection boundary |
| `ComparisonTable` rendering | Example-based RTL tests | Medium — key scenarios |
| Column config integration | Example-based assertion | Low — wiring check |
