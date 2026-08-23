# Requirements Document

## Introduction

FOR-02-06a — Audit Log UI: adds a full-page audit log viewer to the Foremen admin panel AND an "Audit" action button to the generic DataTable component that opens a filtered audit modal for any entity row. The backend is extended with a read-only audit controller at `/api/audit` following the `AdminReadOnlyController` pattern. A new ABAC resource `AUDIT` with `READ` permission for ADMIN is seeded via Liquibase. The audit entity is immutable — no create, update, or delete operations are exposed.

## Glossary

- **Audit_Page**: The top-level page component at route `/audit` displaying the full audit log table
- **Audit_Modal**: A dialog overlay displaying audit records for a specific entity row, fetched via the existing `GET /api/{entityKey}/audit/{id}` endpoint on AdminController
- **Audit_Button**: An action button (Lucide `ScrollText` icon) rendered in the DataTable row actions area that opens the Audit_Modal
- **Entity_Audit_Endpoint**: The existing `GET /api/{entity}/audit/{id}` default method on `AdminController` that returns `List<AuditLogEntity>` for a specific entity record, ordered by performedAt ascending
- **AuditLogEntity**: The JPA entity mapping to the `audit_log` table, extending BaseEntity. Fields: entityClass, entityId, operation, performedBy, performedAt, snapshotBefore (JSONB), snapshotAfter (JSONB)
- **AuditServiceModel**: The service-layer record for audit: id, entityClass, entityId, operation, performedBy, performedAt, snapshotBefore (Map), snapshotAfter (Map)
- **Audit_Controller**: A read-only REST controller at `/api/audit` implementing `AdminReadOnlyController`. Supports pagination, sorting, and Query DSL filtering
- **DataTable**: The shared reusable table component at `src/components/data-table/` that accepts column configuration, fetch function, and row actions
- **ABAC_Resource**: A row in the `resources` table representing a protected system module. AUDIT is a new resource with READ-only access for ADMIN
- **JSON_Expander**: A UI element within a table cell that displays a "Show" button; on click, expands to reveal formatted JSON content of snapshot_before or snapshot_after fields
- **Nav_Item**: A navigation element in the App Shell sidebar: icon + text label + route path
- **Query_DSL**: The backend query language for filtering: `field==value`, `field~ct~value`, `field=gte=value`, combined with AND and OR

## Requirements

### Requirement 1: ABAC Permission for Audit

**User Story:** As an administrator, I want audit log access controlled by the ABAC permission model so that only authorized users can view audit records.

#### Acceptance Criteria

1. THE ABAC seed SHALL include a resource entry with code `AUDIT`, nameRU `Аудит`, namePL `Audyt`, descriptionRU `Журнал аудита`, descriptionPL `Dziennik audytu`
2. THE ABAC seed SHALL grant the ADMIN role READ operation on the AUDIT resource
3. THE ABAC seed SHALL NOT grant CREATE, UPDATE, or DELETE operations on the AUDIT resource to any role
4. THE Liquibase changeset SHALL use preconditions to prevent duplicate insertion of the AUDIT resource
5. THE Liquibase changeset SHALL be registered in changelog.xml after the existing seed changesets

---

### Requirement 2: Backend Read-Only Audit Controller

**User Story:** As a frontend developer, I want a paginated, sortable, filterable API endpoint for the audit log so that I can display audit records in the DataTable.

#### Acceptance Criteria

1. THE Audit_Controller SHALL expose GET `/api/audit` returning a paginated response of AuditServiceModel records with support for pagination (`page`, `size`), sorting (`sort`), and Query DSL filtering (`query`)
2. THE Audit_Controller SHALL implement the `AdminReadOnlyController` pattern with no create, update, or delete endpoints
3. THE Audit_Controller SHALL expose GET `/api/audit/metadata` returning field metadata for the audit entity (field names, data types) for DataTable auto-configuration
4. THE AuditServiceModel SHALL contain fields: id (Long), entityClass (String), entityId (Long), operation (String), performedBy (String), performedAt (LocalDateTime), snapshotBefore (Map<String, Object>), snapshotAfter (Map<String, Object>)
5. THE audit service mapper SHALL NOT declare any i18n-supported properties (audit is system-generated data with no locale-specific fields)
6. THE `/api/audit/metadata` response SHALL be cacheable with HTTP Cache-Control max-age of 86400 seconds

---

### Requirement 3: Audit Page with DataTable

**User Story:** As an administrator, I want a full-page audit log viewer so that I can browse, sort, and filter all system audit events.

#### Acceptance Criteria

1. WHEN the user navigates to `/audit`, THE Audit_Page SHALL render a DataTable populated from GET `/api/audit` with pagination, sorting, and filtering
2. THE Audit_Page DataTable SHALL display columns: id (NUMBER, sortable, filterable), entityClass (STRING, sortable, filterable with contains), entityId (NUMBER, sortable, filterable), operation (STRING, sortable, filterable with contains), performedBy (STRING, sortable, filterable with contains), performedAt (DATE, sortable, filterable with date range), snapshotBefore (not sortable, not filterable, rendered as JSON_Expander), snapshotAfter (not sortable, not filterable, rendered as JSON_Expander)
3. THE Audit_Page SHALL display the page title from i18n key `audit.pageTitle` in the Top_Bar
4. THE Audit_Page SHALL translate operation values (CREATE, UPDATE, DELETE, UPDATE_PERMISSIONS) to localized labels using i18n keys `audit.operation.CREATE`, `audit.operation.UPDATE`, `audit.operation.DELETE`, `audit.operation.UPDATE_PERMISSIONS`
5. THE Audit_Page DataTable SHALL NOT render edit, delete, or audit action buttons in row actions (audit is read-only and self-referential audit is excluded)
6. WHILE the audit data is loading, THE Audit_Page SHALL display skeleton row placeholders with shimmer animation
7. THE Audit_Page DataTable SHALL apply a default sort of `performedAt,desc` (most recent first) when no user-defined sort is active

---

### Requirement 4: JSON Snapshot Display

**User Story:** As an administrator, I want to view the before/after JSON snapshots of audit entries so that I can understand what changed in each operation.

#### Acceptance Criteria

1. THE Audit_Page SHALL render snapshotBefore and snapshotAfter columns as a JSON_Expander element: a compact button labeled "Show" (or equivalent localized text) in the table cell
2. WHEN the user clicks the JSON_Expander button, THE cell SHALL expand to display the JSON content formatted with indentation (pretty-printed) in a monospace font
3. WHEN the JSON_Expander is expanded and the user clicks it again, THE cell SHALL collapse back to the compact button state
4. IF the snapshot value is null, THEN THE cell SHALL display a dash character (`—`) instead of the JSON_Expander button
5. THE expanded JSON content SHALL use a `pre` element with horizontal scrolling for wide JSON objects, styled with muted background and border consistent with the dark theme

---

### Requirement 5: Audit Button in DataTable Template

**User Story:** As an administrator, I want an "Audit" button on each entity row in the DataTable so that I can quickly view the change history for any specific record.

#### Acceptance Criteria

1. THE DataTable component SHALL accept an optional prop `showAuditButton` (boolean, default: true) controlling whether the Audit_Button is rendered in row actions
2. WHEN `showAuditButton` is true, THE DataTable SHALL render an Audit_Button (Lucide `ScrollText` icon with tooltip from i18n key `audit.button.viewAudit`) in the row actions area of each row
3. WHEN the user clicks the Audit_Button on a row, THE DataTable SHALL open the Audit_Modal displaying audit records for that entity, fetched via the existing `GET /api/{entityKey}/audit/{id}` endpoint
4. THE Audit_Page DataTable SHALL set `showAuditButton` to false (audit entries do not have nested audit trails)
5. THE Audit_Button SHALL be positioned alongside existing row action buttons (edit, delete) using the same styling conventions (ghost variant, icon size)

---

### Requirement 6: Audit Modal

**User Story:** As an administrator, I want a modal overlay displaying filtered audit history for a specific entity so that I can see all changes without leaving the current page.

#### Acceptance Criteria

1. THE Audit_Modal SHALL use the shadcn/ui Dialog component, rendering as a centered overlay with backdrop
2. THE Audit_Modal title SHALL display the text from i18n key `audit.modal.title` followed by the entity class name and entity ID (e.g., "История изменений — RoleEntity #5")
3. THE Audit_Modal SHALL fetch audit records from the existing `GET /api/{entityKey}/audit/{id}` endpoint, which returns `List<AuditLogEntity>` ordered by performedAt ascending
4. THE Audit_Modal SHALL display the fetched records in a table using the same column configuration as the Audit_Page (including JSON_Expander for snapshots) but omitting the entityClass and entityId columns (since they are constant in the modal context)
5. WHEN the user closes the Audit_Modal (via close button, Escape key, or clicking the backdrop), THE modal SHALL close and return focus to the triggering Audit_Button
6. THE Audit_Modal SHALL have a maximum width of 90vw and maximum height of 80vh with internal scrolling for the DataTable content
7. THE Audit_Modal table SHALL display records sorted by performedAt descending (most recent first) by default

---

### Requirement 7: Navigation Integration

**User Story:** As a user, I want the Audit page accessible from the sidebar navigation so that I can find it easily.

#### Acceptance Criteria

1. THE App Shell navigation configuration SHALL include a Nav_Item for Audit with route `/audit`, i18n key `nav.audit`, and Lucide icon `ScrollText`
2. THE Nav_Item for Audit SHALL be placed in the "System" navigation section
3. WHEN the user is on the `/audit` route, THE Top_Bar SHALL display the page title from i18n key `audit.pageTitle`

---

### Requirement 8: Internationalization (i18n)

**User Story:** As a user, I want all audit UI text in my chosen language (PL or RU) so that I can work comfortably.

#### Acceptance Criteria

1. THE Audit_Page SHALL render all user-facing text via i18next keys in the "audit" namespace
2. THE Audit_Page SHALL provide translations for both "pl" and "ru" locales covering: page title, column headers, modal title, button tooltips, and operation labels
3. WHEN the user switches locale via the Top_Bar language switcher, THE Audit_Page SHALL update all text labels without a page reload
4. THE i18n namespace SHALL include keys: `nav.audit` (RU: "Аудит", PL: "Audyt"), `audit.pageTitle` (RU: "Аудит", PL: "Audyt"), `audit.column.id` (RU: "ID", PL: "ID"), `audit.column.entityClass` (RU: "Сущность", PL: "Encja"), `audit.column.entityId` (RU: "ID сущности", PL: "ID encji"), `audit.column.operation` (RU: "Действие", PL: "Akcja"), `audit.column.performedBy` (RU: "Выполнил", PL: "Wykonał"), `audit.column.performedAt` (RU: "Дата", PL: "Data"), `audit.column.snapshotBefore` (RU: "До", PL: "Przed"), `audit.column.snapshotAfter` (RU: "После", PL: "Po"), `audit.modal.title` (RU: "История изменений", PL: "Historia zmian"), `audit.button.viewAudit` (RU: "Аудит", PL: "Audyt"), `audit.operation.CREATE` (RU: "Создание", PL: "Tworzenie"), `audit.operation.UPDATE` (RU: "Изменение", PL: "Edycja"), `audit.operation.DELETE` (RU: "Удаление", PL: "Usuwanie"), `audit.operation.UPDATE_PERMISSIONS` (RU: "Изменение прав", PL: "Zmiana uprawnień")

---

### Requirement 9: Dark Theme Consistency

**User Story:** As a user, I want the audit page and modal to be visually consistent with the dark-themed application.

#### Acceptance Criteria

1. THE Audit_Page SHALL use design tokens from the Design_Token_System: background (#09090b), foreground (#fafafa), border (#27272a), muted (#27272a), muted-foreground (#a1a1aa)
2. THE JSON_Expander expanded content SHALL use muted background (#27272a) with border (#27272a) and foreground (#fafafa) monospace text
3. THE Audit_Modal SHALL use the shadcn/ui Dialog styling (bg-background, border, shadow-lg) consistent with the dark theme
4. THE Audit_Button SHALL use ghost variant styling with muted-foreground color for the icon, transitioning to foreground on hover

---

### Requirement 10: Responsive Design

**User Story:** As a user on any device, I want the audit page and modal to be usable on mobile, tablet, and desktop.

#### Acceptance Criteria

1. WHILE viewport width is less than 768px, THE Audit_Page DataTable SHALL render as a card-based layout following the DataTable mobile pattern
2. WHILE viewport width is less than 768px, THE Audit_Modal SHALL occupy 100% viewport width and 90% viewport height
3. WHILE viewport width is between 768px and 1024px, THE Audit_Page DataTable SHALL enable horizontal scrolling with sticky first column
4. WHILE viewport width is greater than 1024px, THE Audit_Page DataTable SHALL display the full table layout with all columns visible
5. THE JSON_Expander expanded content SHALL support horizontal scrolling for wide JSON on all viewport widths
