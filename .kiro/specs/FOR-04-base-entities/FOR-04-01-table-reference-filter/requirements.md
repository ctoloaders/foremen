# Requirements Document — FOR-04-01: Table Reference-Entity Filtering

## Introduction

Every admin data table in Foremen exposes column metadata via `GET /api/{resource}/metadata` (field names, data types, i18n flags, and — for `@ManyToOne`/`@OneToOne` associations — nested field info). Today, filtering a table by a related entity (e.g. filtering Users by their Role) is not supported: reference columns have no usable filter control.

This feature adds a default, reusable **reference filter** to the shared DataTable: when a column is a reference to another entity, the filter UI renders a compact dropdown with an infinite-scroll list of that target entity, ordered alphabetically by name, searched on the backend. Selecting one value filters via `reference.id==<id>`; enabling multi-select (checkboxes) filters via `reference.id=in=(<id1>,<id2>,...)`. The behavior is driven entirely by metadata so it applies automatically to all current and future tables (Users, Roles, and every FOR-04 dictionary/entity) with no per-table code.

The backend query grammar already supports equality (`==`), the `=in=` operator, dotted nested paths, and AND composition (FOR-01/02). This feature primarily (a) enriches metadata so the frontend can identify a reference column and know its target resource + label field, and (b) adds a reusable frontend filter component wired into the DataTable.

## Glossary

- **Reference field**: an entity field mapped as `@ManyToOne`/`@OneToOne` to another managed entity (e.g. `UserEntity.role → RoleEntity`).
- **Target resource**: the API resource of the referenced entity (e.g. the `role` field targets the `ROLES` resource at `/api/roles`).
- **Label field**: the display field of the target entity used for listing/ordering (a name field; i18n-aware — `nameRU`/`namePL` resolved by locale).
- **Reference filter**: the DataTable filter control for a reference column — an infinite-scroll, backend-searched dropdown of target-entity options.
- **Reference descriptor**: the metadata that marks a field as a reference and carries its target resource, label field, and id path.
- **Options endpoint**: the paginated, name-ordered, backend-searchable listing of the target resource used to populate the dropdown.

## Requirements

### Requirement 1: Reference descriptor in table metadata

**User Story:** As a frontend table, I want metadata to tell me which columns are references and how to resolve their options, so that I can render a reference filter without per-table configuration.

#### Acceptance Criteria

1. WHEN `GET /api/{resource}/metadata` is requested for an entity that has a `@ManyToOne` or `@OneToOne` field, THE System SHALL include, for that field, a reference descriptor exposing: the reference flag (true), the target resource code, the target options endpoint path, the label field name, and the id filter path.
2. THE System SHALL set the id filter path of a reference field to `<fieldName>.id` (e.g. `role.id`) so it composes with the existing query grammar.
3. THE System SHALL set the label field of a reference descriptor to the target entity's primary display name field, and SHALL mark it i18n when that name field is localized (`nameRU`/`namePL`).
4. WHERE a field is not a `@ManyToOne`/`@OneToOne` association, THE System SHALL NOT emit a reference descriptor for it, and the field metadata SHALL remain unchanged (backward compatible).
5. THE System SHALL keep existing metadata fields (`name`, `dataType`, `i18n`, `nested`) present and unchanged for all fields, adding the reference descriptor as additional information only.

### Requirement 2: Backend options endpoint (name-ordered, searchable, paginated)

**User Story:** As a reference filter dropdown, I want a paginated, alphabetically ordered, backend-searched list of a target entity, so that I can lazy-load options and let the user search by name.

#### Acceptance Criteria

1. WHEN the options for a target resource are requested with pagination parameters, THE System SHALL return a paginated page of that resource ordered ascending by its locale-resolved name field.
2. WHEN a search term is supplied, THE System SHALL filter the options to those whose locale-resolved name contains the term (case-insensitive), preserving the ascending name ordering.
3. THE System SHALL resolve the name field of each option in the caller's locale (`Accept-Language`), falling back to Polish per the existing i18n convention.
4. THE System SHALL page the options so the dropdown can request subsequent pages (infinite scroll) and SHALL report whether more pages exist.
5. THE System SHALL enforce the target resource's existing READ permission on the options endpoint (a caller without READ on the target resource receives 403).
6. THE options listing SHALL reuse the target resource's existing list endpoint and query grammar (name ordering via `sort`, name search via the existing contains operator) rather than introducing a parallel endpoint, unless a dedicated lightweight options projection is justified in design.

### Requirement 3: Reference filter — single select

**User Story:** As a user filtering a table, I want to pick one related entity from a searchable dropdown, so that the table shows only rows referencing that entity.

#### Acceptance Criteria

1. WHEN a table column is a reference field (per its metadata descriptor), THE System SHALL render that column's filter control as a reference dropdown instead of a plain text/enum filter.
2. WHEN the user opens the reference dropdown, THE System SHALL load the first page of target-entity options ordered alphabetically by the locale-resolved name and SHALL load subsequent pages on scroll to the end of the list (infinite scroll).
3. WHEN the user types in the reference dropdown's search box, THE System SHALL query the options endpoint with the search term on the backend (debounced) and SHALL display the matching options, resetting to the first page.
4. WHEN the user selects a single option, THE System SHALL apply a table filter of the form `<idPath>==<selectedId>` (e.g. `role.id==5`) and SHALL refresh the table results.
5. WHEN the user clears the selection, THE System SHALL remove the reference filter for that column and SHALL refresh the table to the unfiltered (by that column) state.
6. THE System SHALL display the selected option using its locale-resolved name.

### Requirement 4: Reference filter — multi select

**User Story:** As a user filtering a table, I want to select several related entities at once, so that the table shows rows referencing any of them.

#### Acceptance Criteria

1. WHEN the reference dropdown is in multi-select mode, THE System SHALL render a checkbox beside each option's name and SHALL allow selecting more than one option.
2. WHEN two or more options are selected, THE System SHALL apply a table filter of the form `<idPath>=in=(<id1>,<id2>,...)` (e.g. `role.id=in=(5,7)`) and SHALL refresh the table results.
3. WHEN exactly one option is selected in multi-select mode, THE System SHALL apply the single-value equality filter (`<idPath>==<id>`) rather than a one-element `=in=` list.
4. WHEN all selections are cleared in multi-select mode, THE System SHALL remove the reference filter for that column.
5. THE System SHALL allow the user to toggle a column's reference filter between single-select and multi-select, and WHEN toggled, THE System SHALL preserve already-selected option(s) where compatible and re-emit the correct filter expression.
6. THE System SHALL keep the infinite-scroll, alphabetical ordering, and backend search behavior identical in multi-select and single-select modes.

### Requirement 5: Default behavior for all tables and composition

**User Story:** As the product, I want reference filtering to work on every table by default, so that new dictionaries and entities get it for free.

#### Acceptance Criteria

1. THE System SHALL apply reference filtering to any DataTable whose metadata declares one or more reference fields, without per-table implementation.
2. WHEN a reference filter is combined with other column filters (text, enum, number, date, or another reference), THE System SHALL compose all active filters with AND into a single query, consistent with the existing filter grammar.
3. WHEN there are no reference fields in a table's metadata, THE System SHALL render the table exactly as before this feature (no behavioral change).
4. THE System SHALL apply reference filtering to the existing Users table (filter by Role) and Roles-related tables as the reference implementations, and SHALL make it available to all FOR-04 dictionary/entity tables.
5. WHERE the caller lacks READ permission on a reference column's target resource, THE System SHALL degrade gracefully: the column renders without a working options dropdown (empty/disabled) rather than erroring the whole table.

### Requirement 6: Localization and UX consistency

**User Story:** As a bilingual user, I want the reference filter labels and option names in my language, so that filtering is consistent with the rest of the UI.

#### Acceptance Criteria

1. THE System SHALL render option names and the selected value in the user's active locale (RU/PL), consistent with the app-wide i18n and the PL fallback.
2. THE System SHALL localize the reference filter's UI chrome (search placeholder, empty-state, loading, "select"/"clear" affordances, single/multi toggle) via i18n keys present in both `pl.json` and `ru.json`.
3. THE System SHALL order options alphabetically by the locale-resolved name so ordering matches what the user reads.
4. WHILE options are loading, THE System SHALL show a loading indication; WHEN no options match a search, THE System SHALL show a localized empty-state.
