# Requirements Document

## Introduction

Audit Comparison View — replaces the raw JSON expander (`JsonExpander`) in the audit UI with a structured comparison/diff table that shows field-level differences between `snapshotBefore` and `snapshotAfter`. Instead of pretty-printed JSON, the user sees a three-column table (Field | Value Before | Value After) with color-coded change indicators. The component also handles CREATE and DELETE operations gracefully with badge-based indicators.

This feature enhances the existing audit system (FOR-02-06a-audit) without changing the backend API — the `snapshotBefore` and `snapshotAfter` fields already provide `Map<String, Object>` data. The change is purely frontend, affecting the `JsonExpander` rendering in both the Audit_Page and Audit_Modal.

## Glossary

- **Comparison_Table**: A three-column table (Field | Value Before | Value After) that renders field-level differences between two snapshot objects
- **Snapshot_Diff**: The computed difference between `snapshotBefore` and `snapshotAfter`, categorized into changed, added, deleted, and unchanged fields
- **Changed_Field**: A field present in both snapshots where the value differs between before and after
- **Added_Field**: A field present in `snapshotAfter` but absent or null in `snapshotBefore`
- **Deleted_Field**: A field present in `snapshotBefore` but absent or null in `snapshotAfter`
- **Unchanged_Field**: A field present in both snapshots with identical values
- **Diff_Filter_Toggle**: A UI control that switches between showing only changed/added/deleted fields (default) and showing all fields including unchanged
- **Operation_Badge**: A visual indicator ("New" or "Deleted") displayed instead of the Comparison_Table for CREATE and DELETE operations respectively
- **Error_Snapshot**: A snapshot map containing exactly two fields (`class` and `error`) indicating that the operation failed and no meaningful diff data is available
- **AuditRecord**: The frontend interface representing an audit log entry with fields: id, entityClass, entityId, operation, performedBy, performedAt, snapshotBefore, snapshotAfter
- **Audit_Page**: The full-page audit log viewer at route `/audit`
- **Audit_Modal**: The dialog overlay showing audit records for a specific entity

## Requirements

### Requirement 1: Comparison Table Rendering

**User Story:** As an administrator, I want to see snapshot differences in a structured table format so that I can quickly understand what changed in each audit entry.

#### Acceptance Criteria

1. THE Comparison_Table SHALL render three columns with headers: Field, Value Before, Value After
2. THE Comparison_Table SHALL extract all unique field keys from both `snapshotBefore` and `snapshotAfter` and display one row per field
3. WHEN a field value is an object or array, THE Comparison_Table SHALL display the value as a JSON-serialized string
4. WHEN a field value is null or the field is absent from a snapshot, THE Comparison_Table SHALL display a dash character (`—`) in the corresponding cell
5. THE Comparison_Table SHALL replace the existing `JsonExpander` component in both the Audit_Page and Audit_Modal column configurations
6. THE Comparison_Table SHALL accept a single audit record (with snapshotBefore, snapshotAfter, and operation) as input props

---

### Requirement 2: Diff Filtering

**User Story:** As an administrator, I want to see only the changed fields by default so that I can focus on what actually changed without visual noise from unchanged fields.

#### Acceptance Criteria

1. THE Comparison_Table SHALL display only Changed_Fields, Added_Fields, and Deleted_Fields by default (hiding Unchanged_Fields)
2. THE Comparison_Table SHALL render a Diff_Filter_Toggle control that allows the user to switch between "changes only" and "all fields" views
3. WHEN the user activates the Diff_Filter_Toggle to "all fields", THE Comparison_Table SHALL display all fields including Unchanged_Fields
4. WHEN the user deactivates the Diff_Filter_Toggle back to "changes only", THE Comparison_Table SHALL hide Unchanged_Fields again
5. IF all fields are unchanged (snapshotBefore equals snapshotAfter), THEN THE Comparison_Table SHALL display a message indicating no changes were detected

---

### Requirement 3: Color-Coded Change Indicators

**User Story:** As an administrator, I want visual color cues for different types of changes so that I can quickly scan and identify additions, deletions, and modifications.

#### Acceptance Criteria

1. THE Comparison_Table SHALL highlight Changed_Field rows with a pale yellow background accent color
2. THE Comparison_Table SHALL highlight Added_Field rows with a green background accent color
3. THE Comparison_Table SHALL highlight Deleted_Field rows with a red background accent color
4. THE Comparison_Table SHALL NOT apply any highlight color to Unchanged_Field rows
5. THE color accents SHALL use low opacity values compatible with the dark theme to maintain text readability (foreground text remains `#fafafa`)

---

### Requirement 4: CREATE Operation Handling

**User Story:** As an administrator, I want a clear indicator for entity creation events so that I do not see a meaningless empty "before" comparison.

#### Acceptance Criteria

1. WHEN the audit record operation is `CREATE`, THE Comparison_Table SHALL NOT render the three-column diff table
2. WHEN the audit record operation is `CREATE`, THE Comparison_Table SHALL display an Operation_Badge with the text "New" (localized via i18n key `audit.comparison.badgeNew`)
3. THE "New" Operation_Badge SHALL use a green-styled badge consistent with the Added_Field color scheme

---

### Requirement 5: DELETE Operation Handling

**User Story:** As an administrator, I want a clear indicator for entity deletion events so that I do not see a meaningless empty "after" comparison.

#### Acceptance Criteria

1. WHEN the audit record operation is `DELETE`, THE Comparison_Table SHALL NOT render the three-column diff table
2. WHEN the audit record operation is `DELETE`, THE Comparison_Table SHALL display an Operation_Badge with the text "Deleted" (localized via i18n key `audit.comparison.badgeDeleted`)
3. THE "Deleted" Operation_Badge SHALL use a red-styled badge consistent with the Deleted_Field color scheme

---

### Requirement 6: Integration with Existing Audit UI

**User Story:** As a developer, I want the comparison view to integrate seamlessly into the existing audit column configuration so that both Audit_Page and Audit_Modal benefit from the improvement.

#### Acceptance Criteria

1. THE audit column configuration SHALL replace the two separate `snapshotBefore` and `snapshotAfter` columns with a single "Changes" column that renders the Comparison_Table
2. THE new "Changes" column SHALL use i18n key `audit.column.changes` for its header (RU: "Изменения", PL: "Zmiany")
3. THE new "Changes" column SHALL NOT be sortable, filterable, or searchable
4. THE Comparison_Table SHALL be rendered within the table cell and support horizontal scrolling for wide content
5. THE Audit_Modal SHALL display the Comparison_Table using the same rendering logic as the Audit_Page

---

### Requirement 7: Internationalization

**User Story:** As a user, I want all comparison view labels in my chosen language so that the new UI is consistent with the rest of the application.

#### Acceptance Criteria

1. THE Comparison_Table SHALL render all labels via i18next keys in the "audit" namespace
2. THE i18n namespace SHALL include the following keys: `audit.comparison.fieldHeader` (RU: "Поле", PL: "Pole"), `audit.comparison.valueBefore` (RU: "Было", PL: "Było"), `audit.comparison.valueAfter` (RU: "Стало", PL: "Stało się"), `audit.comparison.badgeNew` (RU: "Новый", PL: "Nowy"), `audit.comparison.badgeDeleted` (RU: "Удалён", PL: "Usunięty"), `audit.comparison.badgeError` (RU: "Ошибка", PL: "Błąd"), `audit.comparison.showAll` (RU: "Все поля", PL: "Wszystkie pola"), `audit.comparison.showChanges` (RU: "Только изменения", PL: "Tylko zmiany"), `audit.comparison.noChanges` (RU: "Нет изменений", PL: "Brak zmian"), `audit.column.changes` (RU: "Изменения", PL: "Zmiany")
3. WHEN the user switches locale, THE Comparison_Table SHALL update all text labels without a page reload

---

### Requirement 8: Dark Theme Consistency

**User Story:** As a user, I want the comparison view styled consistently with the dark-themed application.

#### Acceptance Criteria

1. THE Comparison_Table SHALL use design tokens from the existing theme: background (`bg-background`), foreground (`text-foreground`), border (`border-border`), muted (`bg-muted`)
2. THE Changed_Field highlight SHALL use `rgba(234, 179, 8, 0.15)` (yellow with 15% opacity) for the row background
3. THE Added_Field highlight SHALL use `rgba(34, 197, 94, 0.15)` (green with 15% opacity) for the row background
4. THE Deleted_Field highlight SHALL use `rgba(239, 68, 68, 0.15)` (red with 15% opacity) for the row background
5. THE Operation_Badge "New" SHALL use green text (`text-green-500`) with a subtle green border
6. THE Operation_Badge "Deleted" SHALL use red text (`text-red-500`) with a subtle red border
7. THE Comparison_Table header row SHALL use `bg-muted` background consistent with other table headers in the application

---

### Requirement 9: Error Snapshot Handling

**User Story:** As an administrator, I want a clear indicator when an update operation failed due to a serialization or processing error, so that I understand why no diff data is available for that audit entry.

#### Acceptance Criteria

1. WHEN the snapshot map contains exactly two fields (`class` and `error`), THE Comparison_Table SHALL detect it as an Error_Snapshot
2. WHEN an Error_Snapshot is detected, THE Comparison_Table SHALL NOT render the three-column diff table
3. WHEN an Error_Snapshot is detected, THE Comparison_Table SHALL display an Operation_Badge with the text "Error" (localized via i18n key `audit.comparison.badgeError`) followed by the value of the `error` field (e.g. "serialization_failed")
4. THE "Error" Operation_Badge SHALL use a red/orange-styled badge consistent with the Deleted_Field color scheme (`text-red-500` with a subtle red border)
5. THE Error_Snapshot detection SHALL apply regardless of the operation type (CREATE, UPDATE, DELETE)
