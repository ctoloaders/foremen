# Requirements Document

## Introduction

FOR-02-06t — Table Template Enhancements: extension of the reusable DataTable component for the Foremen admin panel. The component provides: global search, column sorting with priority, contextual filters by data type (numbers, dates, strings), table state persistence in localStorage, and a button to reset all filters. The backend is extended with an entity metadata endpoint (field types, names, i18n fields).

The DataTable component is shared across all admin panel pages (roles, users, resources, etc.) and integrates with the backend Query DSL via the `query` parameter.

## Glossary

- **DataTable**: A reusable React table component that accepts column and entity configuration, providing data display with pagination, sorting, filtering, and global search
- **Display_Field**: An entity field displayed in the table. For i18n fields, the locale-resolved name is used (e.g., `name`, not `namePL`/`nameRU`). Filtering and sorting operate on display fields
- **Extended_Model**: An extended entity model that includes i18n fields and nested objects; used only for create/edit operations, not for table display
- **Column_Config**: Configuration of a single table column, including: field identifier (field path), header title, data type (string/number/date), and sortable/filterable flags
- **Global_Search**: An input field above the table that searches across all "meaningful" entity fields using the `~ct~` (contains) operator combined with OR
- **Column_Sort**: A sorting mechanism triggered by clicking the column header, with direction indicator (asc/desc) and multi-sort support with priority
- **Column_Filter**: A contextual per-column filter that opens on clicking the filter icon in the header; filter type is determined by the column's data type
- **Filter_Popup**: A popup component appearing near the filter icon in the column header, containing filter input elements depending on the data type
- **Table_State**: The aggregate table state: current page, page size, active sorts, active column filters, and global search text
- **Metadata_Endpoint**: A backend endpoint returning entity metadata: list of fields with data types (string, number, date), nested fields, and i18n fields
- **Query_DSL**: The backend query language for filtering: `field==value`, `field~ct~value`, `field=gt=5`, `field=lt=10`, combined with AND and OR
- **AdminController**: The base backend controller providing CRUD endpoints: GET `/` (pagination + query DSL), GET `/extended`, GET `/{id}`, POST, PUT `/{id}`, DELETE `/{id}`, GET `/i18n`
- **Clear_All_Button**: A button to reset all active filters and global search, positioned to the left of the first column header

## Requirements

### Requirement 1: DataTable Column Configuration

**User Story:** As a developer, I want to configure DataTable columns declaratively so that I can reuse the component across all admin pages with different entities.

#### Acceptance Criteria

1. THE DataTable SHALL accept a generic column configuration array where each column specifies: field identifier (dot-notation path for nested fields), display header (i18n key), data type (string, number, date), and flags sortable/filterable
2. THE DataTable SHALL render one table column per Column_Config entry, displaying the resolved value of the Display_Field for each row
3. WHEN a Column_Config references a nested field (e.g., `role.name`), THE DataTable SHALL resolve the value through dot-notation traversal of the row data object
4. THE DataTable SHALL use Display_Fields for rendering, filtering, and sorting — not i18n-suffixed fields (backend resolves locale via `foremen-language` header)
5. THE DataTable SHALL accept an `entityKey` prop used for localStorage state persistence and metadata endpoint resolution

---

### Requirement 2: Global Search

**User Story:** As an admin, I want to search across all meaningful fields of an entity in a single input so that I can quickly find records without knowing which column to filter.

#### Acceptance Criteria

1. THE DataTable SHALL render a search input above the table with a placeholder from i18n key `dataTable.search.placeholder`
2. WHEN the user types in the Global_Search input, THE DataTable SHALL debounce the input with a 300ms delay before triggering a query
3. WHEN the debounced search value is non-empty, THE DataTable SHALL construct a query string combining all searchable display fields with the `~ct~` operator joined by OR logic (e.g., `name~ct~value OR code~ct~value OR description~ct~value`)
4. THE DataTable SHALL determine searchable fields from Column_Config entries where data type is `string` and the field is marked as searchable (default: true for string fields)
5. WHEN the Global_Search has active text AND column filters are also active, THE DataTable SHALL combine Global_Search query with column filter queries using AND logic: `(global_search_OR_group) AND (column_filter_1) AND (column_filter_2)`
6. WHEN the user clears the Global_Search input, THE DataTable SHALL remove the search condition from the query and refetch data
7. THE DataTable SHALL reset pagination to page 1 when the Global_Search value changes

---

### Requirement 3: Column Sorting

**User Story:** As an admin, I want to sort table data by clicking column headers so that I can organize records in the desired order.

#### Acceptance Criteria

1. WHEN a Column_Config has `sortable: true`, THE DataTable SHALL render the column header as clickable with a cursor pointer and sort indicator area
2. WHEN the user clicks a sortable column header, THE DataTable SHALL cycle through sort states: unsorted → ascending → descending → unsorted
3. THE DataTable SHALL display a sort direction indicator (arrow up for ascending, arrow down for descending) next to the active sort column header
4. WHEN multiple sortable columns are clicked sequentially, THE DataTable SHALL maintain a sort priority list where the most recently clicked column has the highest priority
5. THE DataTable SHALL display a numeric badge (1, 2, 3...) next to the sort indicator when multiple sorts are active, indicating the priority order
6. THE DataTable SHALL pass sort parameters to the backend API using Spring Data `sort` parameter format: `sort=field1,asc&sort=field2,desc`
7. WHEN a column is sorted and the user clicks it again to reach the "unsorted" state, THE DataTable SHALL remove that column from the sort priority list and re-index remaining priorities
8. THE DataTable SHALL reset pagination to page 1 when sort configuration changes

---

### Requirement 4: Column Filters — Strings

**User Story:** As an admin, I want to filter text columns using "contains" search so that I can narrow down records by partial text match.

#### Acceptance Criteria

1. WHEN a Column_Config has `filterable: true` and data type `string`, THE DataTable SHALL render a filter icon (Lucide `Filter`) to the right of the column header text
2. WHEN the user clicks the filter icon on a string column, THE DataTable SHALL display a Filter_Popup positioned near the icon containing a text input with placeholder from i18n key `dataTable.filter.string.placeholder`
3. WHEN the user enters text in the string filter input and confirms (Enter key or Apply button), THE DataTable SHALL add a condition `field~ct~value` to the query
4. WHILE a string column has an active filter, THE DataTable SHALL highlight the filter icon (primary color instead of muted-foreground) AND display a clear icon (Lucide `X` or brush icon) next to the filter icon
5. WHEN the user clicks the clear icon on an active string filter, THE DataTable SHALL remove that column's filter condition from the query and refetch data
6. WHEN the user clicks the highlighted filter icon on a column with an active filter, THE DataTable SHALL open the Filter_Popup pre-populated with the current filter value for editing

---

### Requirement 5: Column Filters — Numbers

**User Story:** As an admin, I want to filter numeric columns using range operations (greater than, less than, between) so that I can find records within specific numeric boundaries.

#### Acceptance Criteria

1. WHEN a Column_Config has `filterable: true` and data type `number`, THE DataTable SHALL render a filter icon (Lucide `Filter`) to the right of the column header text
2. WHEN the user clicks the filter icon on a number column, THE DataTable SHALL display a Filter_Popup containing two numeric inputs: "From" and "To" with labels from i18n keys `dataTable.filter.number.from` and `dataTable.filter.number.to`
3. WHEN only the "From" input is filled, THE DataTable SHALL add a condition `field=gte=value` to the query (greater than or equal)
4. WHEN only the "To" input is filled, THE DataTable SHALL add a condition `field=lte=value` to the query (less than or equal)
5. WHEN both inputs are filled AND the "to" value is greater than the "from" value, THE DataTable SHALL add conditions `field=gte=fromValue AND field=lte=toValue` to the query
6. IF both inputs are filled AND the "to" value is less than or equal to the "from" value, THEN THE Filter_Popup SHALL display a validation error from i18n key `dataTable.filter.number.rangeError` and prevent filter application
7. WHILE a number column has an active filter, THE DataTable SHALL highlight the filter icon and display a clear icon next to it
8. WHEN the user clicks the clear icon on an active number filter, THE DataTable SHALL remove that column's filter conditions from the query and refetch data

---

### Requirement 6: Column Filters — Dates

**User Story:** As an admin, I want to filter date columns using date range selection so that I can find records within specific time periods.

#### Acceptance Criteria

1. WHEN a Column_Config has `filterable: true` and data type `date`, THE DataTable SHALL render a filter icon (Lucide `Filter`) to the right of the column header text
2. WHEN the user clicks the filter icon on a date column, THE DataTable SHALL display a Filter_Popup containing two date picker inputs: "From" and "To" with labels from i18n keys `dataTable.filter.date.from` and `dataTable.filter.date.to`
3. WHEN only the "From" date input is filled, THE DataTable SHALL add a condition `field=gte=dateValue` to the query (ISO date format)
4. WHEN only the "To" date input is filled, THE DataTable SHALL add a condition `field=lte=dateValue` to the query (ISO date format)
5. WHEN both date inputs are filled AND the "to" date is after the "from" date, THE DataTable SHALL add conditions `field=gte=fromDate AND field=lte=toDate` to the query
6. IF both date inputs are filled AND the "to" date is before or equal to the "from" date, THEN THE Filter_Popup SHALL display a validation error from i18n key `dataTable.filter.date.rangeError` and prevent filter application
7. WHILE a date column has an active filter, THE DataTable SHALL highlight the filter icon and display a clear icon next to it
8. WHEN the user clicks the clear icon on an active date filter, THE DataTable SHALL remove that column's filter conditions from the query and refetch data
9. THE date picker inputs SHALL use the shadcn/ui Calendar component (Popover + Calendar) with locale-aware date formatting (PL/RU)

---

### Requirement 7: Filter Combination

**User Story:** As an admin, I want filters on different columns to combine with AND logic so that I can progressively narrow down results with multiple criteria.

#### Acceptance Criteria

1. WHEN multiple columns have active filters, THE DataTable SHALL combine all column filter conditions using AND logic in the query string
2. WHEN Global_Search is active simultaneously with column filters, THE DataTable SHALL construct the final query as: `(field1~ct~search OR field2~ct~search OR ...) AND (column_filter_1) AND (column_filter_2) AND ...`
3. THE DataTable SHALL construct a single query string and pass it as the `query` parameter to the backend GET endpoint
4. WHEN any filter or search changes, THE DataTable SHALL reset pagination to page 1 and refetch data
5. THE DataTable SHALL display a summary count of active filters near the Clear_All_Button from i18n key `dataTable.filters.activeCount`

---

### Requirement 8: Clear All Filters Button

**User Story:** As an admin, I want a single button to clear all active filters and search so that I can quickly reset the table to its unfiltered state.

#### Acceptance Criteria

1. THE DataTable SHALL render the Clear_All_Button to the left of the first column header in the table header row
2. WHILE at least one column filter OR Global_Search is active, THE Clear_All_Button SHALL be visible and enabled
3. WHILE no filters and no Global_Search are active, THE Clear_All_Button SHALL be hidden or visually disabled (ghost style, muted)
4. WHEN the user clicks the Clear_All_Button, THE DataTable SHALL clear all column filters, clear the Global_Search input, reset pagination to page 1, and refetch data
5. THE Clear_All_Button SHALL use the Lucide `FilterX` icon and tooltip from i18n key `dataTable.filters.clearAll`

---

### Requirement 9: Table State Persistence in localStorage

**User Story:** As an admin, I want the table state to persist across navigation so that I don't lose my filter/sort configuration when navigating to other pages and returning.

#### Acceptance Criteria

1. THE DataTable SHALL persist the complete Table_State to localStorage under key `foremen:table:{entityKey}` whenever the state changes
2. THE Table_State persisted SHALL include: current page number, page size, active sort configuration (fields + directions + priorities), active column filters (field + operator + values), and Global_Search text
3. WHEN the DataTable mounts and a persisted Table_State exists for the current entityKey, THE DataTable SHALL restore the state and apply it to the initial data fetch
4. WHEN the DataTable mounts and no persisted state exists, THE DataTable SHALL use default state: page 1, default page size, no sorts, no filters, empty search
5. THE DataTable SHALL update the persisted state debounced (500ms) to avoid excessive localStorage writes
6. WHEN the user clicks Clear_All_Button, THE DataTable SHALL also clear the persisted state in localStorage (reset to defaults)
7. THE localStorage state SHALL be scoped per entityKey so different table pages maintain independent states

---

### Requirement 10: Backend — Entity Metadata Endpoint

**User Story:** As a frontend developer, I want an endpoint that returns entity metadata (field names, data types, i18n fields) so that the DataTable can auto-configure columns when explicit configuration is not provided.

#### Acceptance Criteria

1. THE AdminController SHALL expose a GET `/metadata` endpoint that returns entity metadata in JSON format
2. THE metadata response SHALL include for each field: field name (String), data type (enum: STRING, NUMBER, DATE, BOOLEAN, ENUM), whether it is an i18n field (boolean), and nested field metadata (recursive)
3. THE Metadata_Endpoint SHALL derive field information reflectively from the DAO model class, inspecting each property's Java type and mapping it to the data type enum (String→STRING, Integer/Long/Double/BigDecimal→NUMBER, LocalDate/LocalDateTime→DATE, Boolean→BOOLEAN, Enum→ENUM)
4. THE Metadata_Endpoint SHALL recursively traverse nested objects (non-primitive, non-collection fields with `@Embedded` or `@ManyToOne`/`@OneToOne`) to provide metadata for nested field paths
5. THE Metadata_Endpoint SHALL identify i18n fields by checking if a field name ends with a locale suffix (RU, PL) and has a corresponding base field — then marking the base field as i18n-supported
6. THE AdminController SHALL provide the `/metadata` endpoint as a default method, requiring no implementation in concrete controllers
7. THE Metadata_Endpoint response SHALL be cacheable (HTTP Cache-Control: max-age=86400) since metadata does not change until application restart
8. THE AdminController SHALL cache the metadata computation result in-memory (application-level cache) since reflection is expensive and entity structure is immutable at runtime

---

### Requirement 11: Pagination

**User Story:** As an admin, I want paginated table data with navigation controls so that I can browse large datasets efficiently.

#### Acceptance Criteria

1. THE DataTable SHALL support server-side pagination by passing `page` and `size` parameters to the backend API
2. THE DataTable SHALL render pagination controls below the table: previous/next buttons, current page indicator, total pages, and optional page size selector
3. THE DataTable SHALL support configurable default page sizes: 10, 25, 50 (selectable via dropdown)
4. WHEN the user changes the page size, THE DataTable SHALL reset to page 1 and refetch data
5. WHILE the DataTable is on the first page, THE "previous" button SHALL be disabled
6. WHILE the DataTable is on the last page, THE "next" button SHALL be disabled
7. THE DataTable SHALL display total record count from the API response `totalElements` value

---

### Requirement 12: Loading and Error States

**User Story:** As a user, I want visual feedback during loading and clear error messages so that I understand the table's current state.

#### Acceptance Criteria

1. WHILE data is being fetched, THE DataTable SHALL display skeleton row placeholders (5 rows) with shimmer animation matching the column count
2. WHEN a data fetch fails after retries, THE DataTable SHALL display an inline error message with a "Retry" button from i18n key `dataTable.error.retry`
3. WHILE a filter or search is being applied (refetch in progress), THE DataTable SHALL display a subtle loading indicator (top border progress bar or opacity reduction on table body) without replacing existing data
4. WHEN no data matches the current filters/search, THE DataTable SHALL display an empty state message from i18n key `dataTable.empty.filtered` with a suggestion to clear filters

---

### Requirement 13: Responsive Design (Mobile-First)

**User Story:** As a user on any device, I want the DataTable to be usable on mobile, tablet, and desktop so that I can manage data from anywhere.

#### Acceptance Criteria

1. WHILE viewport width is less than 768px, THE DataTable SHALL render as a card-based layout (one record per card, stacked vertically) with filter/sort controls in a collapsible toolbar
2. WHILE viewport width is less than 768px, THE DataTable SHALL provide a "Filters" button that opens a bottom sheet or drawer containing all filter controls in vertical layout
3. WHILE viewport width is between 768px and 1024px, THE DataTable SHALL enable horizontal scrolling with sticky first column (entity identifier)
4. WHILE viewport width is greater than 1024px, THE DataTable SHALL display the full table layout with inline column headers, sort indicators, and filter icons
5. THE Global_Search input SHALL be visible at all viewport widths, positioned above the table/cards
6. THE pagination controls SHALL adapt to mobile (compact: prev/next + current page only) and desktop (full: page numbers, size selector, total count)

---

### Requirement 14: Internationalization (i18n)

**User Story:** As a user, I want all DataTable UI elements in my chosen language (PL or RU) so that I can work comfortably.

#### Acceptance Criteria

1. THE DataTable SHALL render all user-facing text via i18next keys in the "dataTable" namespace
2. THE DataTable SHALL provide translations for both "pl" and "ru" locales covering: search placeholder, filter labels, filter error messages, pagination labels, empty states, error messages, clear button tooltips, sort tooltips
3. WHEN the user switches locale via the Top_Bar language switcher, THE DataTable SHALL update all text labels without a page reload
4. THE DataTable SHALL pass the current locale to the backend via `foremen-language` header so that locale-specific fields (e.g., `name` resolved to `nameRU` or `namePL`) are handled server-side
5. THE i18n namespace SHALL include keys: `dataTable.search.placeholder` (PL: "Szukaj...", RU: "Поиск..."), `dataTable.filter.string.placeholder` (PL: "Zawiera...", RU: "Содержит..."), `dataTable.filter.number.from` (PL: "Od", RU: "От"), `dataTable.filter.number.to` (PL: "Do", RU: "До"), `dataTable.filter.date.from` (PL: "Od", RU: "С"), `dataTable.filter.date.to` (PL: "Do", RU: "По"), `dataTable.filter.number.rangeError` (PL: "Wartość 'Do' musi być większa niż 'Od'", RU: "Значение 'До' должно быть больше 'От'"), `dataTable.filter.date.rangeError` (PL: "Data 'Do' musi być późniejsza niż 'Od'", RU: "Дата 'По' должна быть позже 'С'"), `dataTable.filters.clearAll` (PL: "Wyczyść filtry", RU: "Сбросить фильтры"), `dataTable.filters.activeCount` (PL: "Aktywne filtry: {{count}}", RU: "Активных фильтров: {{count}}"), `dataTable.empty.filtered` (PL: "Brak wyników. Spróbuj zmienić filtry.", RU: "Нет результатов. Попробуйте изменить фильтры."), `dataTable.error.retry` (PL: "Ponów", RU: "Повторить"), `dataTable.pagination.showing` (PL: "Wyświetlanie {{from}}-{{to}} z {{total}}", RU: "Показано {{from}}-{{to}} из {{total}}")

---

### Requirement 15: Dark Theme Consistency

**User Story:** As a user, I want the DataTable to be visually consistent with the dark-themed application.

#### Acceptance Criteria

1. THE DataTable SHALL use design tokens from the Design_Token_System: background (#09090b), foreground (#fafafa), border (#27272a), muted (#27272a), muted-foreground (#a1a1aa)
2. THE active filter icon SHALL use the primary color for highlighting, and the inactive filter icon SHALL use muted-foreground (#a1a1aa)
3. THE sort direction indicators SHALL use foreground (#fafafa) for active sort and muted-foreground (#a1a1aa) for inactive/hover state
4. THE Filter_Popup SHALL use background with border styling consistent with shadcn/ui Popover (bg-popover, border, rounded-md, shadow-md)
5. THE skeleton loading rows SHALL use the muted (#27272a) color with a pulse animation

---

### Requirement 16: UI Components (shadcn/ui)

**User Story:** As a developer, I want to use shadcn/ui components within DataTable so that the UI is accessible, consistent, and maintainable.

#### Acceptance Criteria

1. THE DataTable SHALL use the shadcn/ui Table component (Table, TableHeader, TableBody, TableRow, TableHead, TableCell) for the desktop table layout
2. THE Filter_Popup SHALL use the shadcn/ui Popover component (Popover, PopoverTrigger, PopoverContent) for positioning
3. THE date filter SHALL use the shadcn/ui Calendar component inside a Popover for date selection
4. THE pagination controls SHALL use the shadcn/ui Button component with appropriate variants (outline for page nav, ghost for disabled states)
5. THE Global_Search input SHALL use the shadcn/ui Input component with a Lucide `Search` icon prefix
6. THE Clear_All_Button SHALL use the shadcn/ui Button component with variant `ghost` and size `icon`
7. THE page size selector SHALL use the shadcn/ui Select component (Select, SelectTrigger, SelectContent, SelectItem)
8. THE DataTable SHALL use shadcn/ui Tooltip for icon-only buttons (sort indicators, filter icons, clear icons)
