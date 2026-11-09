# Requirements Document

## Introduction

FOR-10 turns the empty home page ("Panel", route `/`, currently a stub `DashboardPage`) into a
per-user, configurable **dashboard**: a grid of **widgets**. Each widget is a declarative
configuration (dataset, filters, grouping, aggregations, visualization, links) stored in a shared
**Widget_Library** managed by administrators in the System section. Every user composes their own
dashboard by placing library widgets into grid cells. The placement (cell index + widget id +
optional project id) is stored in the database.

All widget data is fetched server-side through a whitelisted dataset registry. Every fetch is
filtered by the caller's token (user, role, accessible projects) and gated by the ABAC resources the
dataset reads. A user can never obtain data through a widget that they could not obtain through the
corresponding regular screen.

### Relationship to existing specs

- **FOR-03-08 (ABAC)**: reuses `ForemenPermissionEvaluator` / `PermissionSet`, `ProjectAccessCache`
  and `ProjectScopedService` semantics. The new managed entity follows the `entity-creation-rules`
  checklist (resource seed, ADMIN grant, `@PermissionResource`).
- **FOR-05-02 (rooms-autoimport)**: the AI assistant reuses its Gemini stack
  (`GeminiClientFactory`, structured JSON output, bounded retry, stub client, env-var pattern).
- **Generic notification service** (`NotificationService.create`, in-app bell): this spec only
  EMITS notifications; it does not implement delivery.
- **FOR-06 / FOR-07 specs** (work reports, payments, payroll, purchases, bank ledger): their
  entities are read as datasets. No change to their behaviour.
- **FOR-02 (admin panel)**: the library admin screens live in the System navigation section.

### Scope boundaries

In scope:
- User dashboard page, global date filter, grid layout (desktop 4x9, tablet 3x12, mobile 1x36),
  multi-cell widgets, add/remove widget, clickable widget elements.
- Widget_Library entity, admin CRUD, manual builder with live preview, AI draft assistant.
- Generic widget configuration schema and dataset registry (datasets for every non-reference resource, Requirement 6), per-user appearance (palette, styles).
- Single-project widgets bound to a concrete project; the same template can be placed several times,
  each for a different project.
- Access-aware reading (disabled-with-warning widgets) of the library and of the user's layout.
- Seed of default library widgets, default widgets per system role, default widgets for new users.
- Two notifications (Requirement 17).

Out of scope (explicit):
- Drag-and-drop re-arranging and resizing of placed widgets (v1 supports add into a free area and
  delete only).
- Sharing dashboards between users, per-role forced dashboards, dashboard templates.
- Custom (non-system) roles: defaults are defined for system roles only.
- Email / SMS / Telegram delivery of notifications.
- Export of widget data, scheduled reports, alerting thresholds.
- Computed project progress percentages (not available as a stable dataset field in v1).

## Glossary

- **Dashboard**: the per-user grid on the Panel page.
- **Grid**: 36 cells. Desktop 4 columns x 9 rows; tablet 3 x 12; mobile 1 x 36.
- **Cell_Index**: integer 0..35, `row * 4 + column` in the desktop grid (row-major, 0-based). It is the
  canonical stored position (top-left cell of the widget footprint).
- **Widget**: a Widget_Library entry: code, localized name/description, Widget_Config, default span,
  active flag.
- **Widget_Config**: versioned JSON document describing what and how to render (Requirement 5).
- **Placement**: a row of `user_dashboard_widgets` (user, widget, Cell_Index, optional project).
- **Span**: widget footprint in cells (`colSpan` 1..4, `rowSpan` 1..9). A 1x1 widget occupies one cell.
- **Dataset**: a server-side whitelisted data source (code, fields, required resources, scoping rules).
- **Required_Resources**: the set of ABAC resource codes a Dataset reads.
- **Has_Access**: the caller's role holds at least ONE operation (CREATE, READ, UPDATE or DELETE) on
  EACH resource in Required_Resources. ADMIN always has access.
- **Project_Widget**: a Widget with `projectScope = SINGLE_PROJECT`; each Placement binds a project.
- **Global_Date_Filter**: the date range at the top of the Dashboard applied to all widgets.
- **System_Role**: a role with `system = true` (ADMIN, MANAGER, FOREMAN, WORKER, FINANCIER, CLIENT,
  ESTIMATOR, SALESMAN).
- **AI_Assistant**: the admin tool that drafts a Widget_Config from a text description.

## Requirements

### Requirement 1: Dashboard page and grid layout

**User Story:** As a user, I want a home page that shows my own widgets in a grid, so that I see the
information relevant to me at a glance.

#### Acceptance Criteria

1. THE Panel page (`/`) SHALL render the user's Dashboard instead of the current stub, for every
   authenticated user.
2. THE Dashboard SHALL have at most 36 cells: 4 columns x 9 rows on desktop, 3 x 12 on tablet and
   1 x 36 on mobile. Breakpoints SHALL reuse the existing responsive breakpoints of the app shell.
3. THE system SHALL persist only Cell_Index and widget id (and project id for Project_Widgets).
   Tablet and mobile layouts SHALL be derived by ordering Placements by Cell_Index and flowing them
   into the narrower grid; no per-breakpoint positions are stored.
4. ON tablet and mobile a widget's `colSpan` SHALL be clamped to the available column count; `rowSpan`
   SHALL be preserved.
5. THE footprint of a Placement (Cell_Index, colSpan, rowSpan) SHALL fit the desktop grid: the column
   of Cell_Index plus `colSpan` SHALL NOT exceed 4 and the row plus `rowSpan` SHALL NOT exceed 9.
6. TWO Placements of the same user SHALL NOT overlap. THE backend SHALL reject an overlapping or
   out-of-range Placement with HTTP 409 `error.dashboard.cell.occupied` / HTTP 400
   `error.dashboard.cell.invalid`.
7. WHEN the user has no Placements, THE Dashboard SHALL show an empty state with a "+" tile in the
   first cell.
8. EACH widget SHALL render its own loading, error, no-data and no-access states independently; a
   failure of one widget SHALL NOT affect the others.

### Requirement 2: Global date filter

**User Story:** As a user, I want one date filter for the whole dashboard, so that all widgets show
the same period.

#### Acceptance Criteria

1. THE top of the Dashboard SHALL show a "from" and a "to" date picker and a quick-select control.
2. THE quick-select SHALL offer a period selector (before-previous, previous, current) and a unit
   selector (day, week, month, year). Selecting a pair SHALL set the range accordingly (calendar
   boundaries; weeks start on Monday).
3. THE default SHALL be the current month.
4. THE selected range SHALL be remembered for the browser session (`sessionStorage`) and restored on
   reload within the same session; a new session SHALL start with the current month.
5. THE range SHALL be validated: `from <= to`, both required, maximum span 5 years. An invalid manual
   input SHALL NOT trigger a fetch and SHALL show a validation message.
6. WHEN the range changes, ALL date-aware widgets SHALL refetch with `dateFrom` / `dateTo` (inclusive,
   ISO-8601 dates).
7. A Widget_Config MAY declare a `dateField`; WHEN absent, the widget ignores the Global_Date_Filter
   and SHALL display a small "not affected by date filter" indicator.

### Requirement 3: Adding and removing widgets

**User Story:** As a user, I want to add widgets from the library and remove the ones I do not need.

#### Acceptance Criteria

1. THE first free area large enough for at least a 1x1 widget (scanning in Cell_Index order) SHALL show
   a "+" tile. Pressing it SHALL open the library picker.
2. THE picker SHALL list active library widgets with name, description, visualization type and span,
   with a text search. Widgets the user has no access to SHALL be shown DISABLED with a warning
   (Requirement 9) and SHALL NOT be addable.
3. WHEN a widget is chosen, THE system SHALL place it at the "+" cell if its footprint fits there;
   otherwise at the first free position where it fits. IF no position fits, THE picker SHALL show a
   "no space" message and the backend SHALL return HTTP 409 `error.dashboard.grid.full`.
4. FOR a Project_Widget, THE picker SHALL ask for a project (Requirement 12) before placing.
5. EACH placed widget SHALL show a remove action. Removal SHALL require a confirmation dialog with a
   warning that the widget will disappear from the dashboard.
6. THE same non-project widget SHALL NOT be placed twice by one user (HTTP 409
   `error.dashboard.widget.duplicate`). THE same Project_Widget MAY be placed several times for
   different projects, but not twice for the same project.
7. Only the owner can read, add or delete their Placements. Operations on a foreign Placement SHALL
   return HTTP 403 `error.dashboard.forbidden` (without revealing existence).

### Requirement 4: Widget library entity and admin management

**User Story:** As an administrator, I want to manage the widget library in the System section.

#### Acceptance Criteria

1. THE system SHALL store widgets in table `dashboard_widgets` with: `code` (unique, immutable),
   `name_ru`, `name_pl`, `description_ru`, `description_pl`, `config` (JSON), `config_version`,
   `col_span`, `row_span`, `project_scope`, `active`, `system_seed`, audit columns.
2. THE system SHALL seed ABAC resource `DASHBOARD_WIDGETS` (ADMIN grant for CREATE, READ, UPDATE,
   DELETE) via a Liquibase changeset registered last in `changelog.xml`, idempotent through
   `onFail="MARK_RAN"` preconditions, and annotate the controller with
   `@PermissionResource("DASHBOARD_WIDGETS")`.
3. THE System navigation section SHALL contain a "Dashboard widgets" item gated by
   `DASHBOARD_WIDGETS/READ`, route `/dashboard-widgets`, registered in `route-permissions.ts`.
4. THE admin list SHALL show code, localized name, visualization, dataset, span, project scope,
   active flag, and the roles that would see the widget (computed from the permission matrix),
   with search and an active filter.
5. CREATE and UPDATE SHALL validate the Widget_Config (Requirement 5.6). `code` SHALL match
   `^[A-Z][A-Z0-9_]{2,63}$` and be unique (HTTP 409 `error.dashboard.widget.code.exists`).
6. DEACTIVATE SHALL hide the widget from the picker and render it as unavailable on dashboards where
   it is placed (Requirement 9.3); the Placements are kept. ACTIVATE reverses it.
7. DELETE SHALL be allowed only for widgets without Placements; otherwise HTTP 409
   `error.dashboard.widget.in.use` (the admin is advised to deactivate).
8. Seeded widgets (`system_seed = true`) SHALL be editable but not deletable.
9. Editing a widget's config SHALL take effect for all Placements immediately (the config is read at
   fetch time).

### Requirement 5: Generic widget configuration

**User Story:** As an administrator, I want one generic configuration that describes any widget, so
that new widgets need no code.

#### Acceptance Criteria

1. THE Widget_Config SHALL be a versioned JSON document (`schemaVersion`, initially 1) with sections:
   - `dataset`: code of a registered Dataset;
   - `visualization`: one of `TABLE`, `BAR`, `LINE`, `AREA`, `PIE`, `DONUT`, `KPI`, `LIST`,
     `PROJECT_CARD`;
   - `title`: `{ru, pl}` heading (falls back to the widget name);
   - `columns` (for TABLE/LIST/PROJECT_CARD): ordered Dataset fields with `{ru, pl}` labels, optional
     format (`TEXT`, `NUMBER`, `CURRENCY`, `PERCENT`, `DATE`, `STATUS`);
   - `dimensions` (grouping): Dataset fields, with optional date bucket (`DAY`, `WEEK`, `MONTH`,
     `YEAR`);
   - `measures`: aggregations `COUNT`, `COUNT_DISTINCT`, `SUM`, `AVG`, `MIN`, `MAX` over Dataset
     fields, with alias and `{ru, pl}` label;
   - `filters`: static predicates (`EQ`, `NE`, `IN`, `GT`, `GTE`, `LT`, `LTE`, `BETWEEN`, `IS_NULL`,
     `IS_NOT_NULL`) over filterable Dataset fields;
   - `dateField`: the Dataset date field the Global_Date_Filter applies to (optional);
   - `rowScope`: `PROJECTS` (default, rows of the caller's accessible projects) or `OWN` (rows owned
     by the caller, Requirement 8.4);
   - `sort`, `limit` (1..200, default 50);
   - `links`: clickable element definitions (Requirement 10);
   - `presentation`: visualization options (default `style`, default `colors`, legend, stacked,
     empty text); user appearance preferences override the appearance defaults (criteria 7–11).
2. THE configuration SHALL reference only Dataset fields that the Dataset marks as selectable /
   filterable / groupable / aggregatable respectively. Free SQL, expressions and arbitrary entity
   paths SHALL NOT be accepted.
3. EVERY user-visible string inside the config (titles, labels, empty text) SHALL be stored as
   `{ru, pl}`.
4. THE project binding SHALL NOT be part of `filters`; it is expressed by `projectScope`
   (Requirement 12), set on the Widget.
5. THE schema SHALL define a stable `GET /api/dashboard-widgets/datasets` catalog (fields, types,
   capabilities, Required_Resources) that drives the manual builder and the AI_Assistant.
6. VALIDATION SHALL reject (HTTP 400 `error.dashboard.config.invalid` with a list of violations):
   unknown dataset/field, capability mismatch (e.g. SUM over a text field), visualization/shape
   mismatch (e.g. KPI with more than one measure and no dimension, PIE without exactly one dimension
   and one measure), missing localized strings, `limit` out of range, unsupported `schemaVersion`,
   link targets outside the allowed route whitelist, `presentation.style` not in the visualization's
   style catalog, malformed colors.
7. WHEN a widget is rendered, THE frontend SHALL by default derive its main color palette from the
   user's current appearance settings (theme and accent color of `/settings/appearance`); WHEN the
   user changes those settings, THEN all widgets SHALL re-render with the new palette without a
   reload.
8. THE user SHALL be able to define a custom color configuration for their dashboard: an ordered
   palette of 1..8 colors (`#RRGGBB`) and a reset-to-default action. THE backend SHALL validate the
   format and SHALL store it per user, separate from Placement position data. WHEN a custom palette
   is set, THEN it SHALL take precedence over the theme-derived palette on all of the user's widgets.
9. EACH visualization SHALL define a catalog of at least three appearance styles (for example TABLE:
   `STANDARD`, `COMPACT`, `STRIPED`; BAR/LINE/AREA: `SOLID`, `OUTLINED`, `MINIMAL`; PIE/DONUT:
   `STANDARD`, `THIN_RING`, `WITH_LABELS`; KPI: `PLAIN`, `CARD`, `WITH_TREND`; LIST: `STANDARD`,
   `COMPACT`, `DIVIDED`; PROJECT_CARD: `DETAILED`, `COMPACT`, `MINIMAL`). THE administrator SHALL
   choose the default style in `presentation.style` (builder and preview show all styles).
10. THE user SHALL be able to select, per placed widget, one of the styles available for that
    widget's visualization. THE choice SHALL be stored per user and Placement in the user's
    appearance settings (not in the Widget_Config and not as position data), SHALL apply only to
    that user, and SHALL fall back to the admin default when absent or no longer in the catalog.
11. THE system SHALL expose self-service endpoints `GET` / `PUT /api/dashboard/appearance` limited to
    the caller's own settings. Appearance settings SHALL NOT affect which data is fetched.
    Semantic colors (status, positive/negative) SHALL stay fixed and information SHALL NOT rely on
    color alone (Requirement 7.5); IF a custom palette color fails the minimum contrast check
    against the widget background, THEN the renderer SHALL fall back to the nearest accessible
    shade.

### Requirement 6: Dataset registry and secured data fetch

**User Story:** As a security-conscious owner, I want every widget fetch filtered by the user's token
and permissions, so that widgets never leak data.

#### Acceptance Criteria

1. THE backend SHALL expose datasets only through a code-defined registry. Each Dataset SHALL declare:
   code, backing entity, Required_Resources, field catalog, project path (or "company-wide"), optional
   owner field, optional date fields.
2. v1 datasets. Every ABAC resource seeded in the system gets at least one Dataset, EXCEPT the
   reference-data resources listed in 2a and the action-only resource in 2b. A Dataset may combine
   several tables only through to-one associations; each Dataset belongs to one primary resource
   (its Required_Resources). Scope: `P` = project-scoped (project path given), `C` = company-wide
   (access by Has_Access only), `O` = owner-restricted (Requirement 8.4), `OWN` = always own rows.

   | Dataset | Reads | Required_Resources | Scope / project path | Owner field |
   |---|---|---|---|---|
   | `PROJECTS` | projects | `PROJECTS` | P `id` | - |
   | `PROJECT_MEMBERS` | project members | `PROJECT_MEMBERS` | P `projectId` | - |
   | `ROOMS` | rooms | `ROOMS` | P `project.id` | - |
   | `ESTIMATES` | estimate headers | `ESTIMATE` | P `project.id` | - |
   | `ESTIMATE_LINES` | estimate lines | `ESTIMATE` | P `estimate.project.id` | - |
   | `OFFERS` | offers | `OFFERS` | P `project.id` | - |
   | `OFFER_NEGOTIATION_ROUNDS` | offer negotiation rounds | `OFFERS` | P `offer.project.id` | - |
   | `SIGNABLE_DOCUMENTS` | signable documents | `SIGNABLE_DOCUMENTS` | P `project.id` | `createdByUser.id` (null project rows) |
   | `SIGNABLE_DOCUMENT_TYPES` | document types | `SIGNABLE_DOCUMENT_TYPES` | C | - |
   | `DOCUMENT_TEMPLATES` | document templates | `DOCUMENT_TEMPLATES` | C | - |
   | `COMPANY_PROFILE` | company profile (single row) | `COMPANY_PROFILE` | C | - |
   | `SCHEDULE_BARS` | project schedule bars | `WORK_SCHEDULE` | P `schedule.project.id` | - |
   | `COMPANY_SCHEDULE_BARS` | cross-project schedule bars | `COMPANY_SCHEDULE` | P `schedule.project.id` | - |
   | `WORK_CATALOG` | work items | `WORK_CATALOG` | C | - |
   | `WORK_PRICES` | work prices | `WORK_PRICES` | C | - |
   | `WORK_MATERIAL_CONSUMPTION` | material consumption norms | `WORK_MATERIAL_CONSUMPTION` | C | - |
   | `CONSTRUCTION_MATERIALS` | construction materials | `MATERIALS_CONSTRUCTION` | C | - |
   | `FINISHING_MATERIALS` | finishing materials | `MATERIALS_FINISHING` | C | - |
   | `WORK_REPORTS` | work reports | `WORK_REPORTS` | P `project.id`, O | `worker.id` |
   | `PURCHASES` | procurement documents (project-level) | `PURCHASES` | P `project.id` (nullable) | `uploadedBy.id` |
   | `PURCHASE_LINES` | procurement lines | `PURCHASES` | P `document.project.id` (nullable) | `document.uploadedBy.id` |
   | `COMPANY_PURCHASES` | procurement documents (all) | `COMPANY_PURCHASES` | C | - |
   | `COMPANY_PURCHASE_LINES` | procurement lines (all) | `COMPANY_PURCHASES` | C | - |
   | `PAYMENTS` | payment transactions | `PAYMENTS` | P `schedule.project.id` | - |
   | `PAYMENT_SCHEDULES` | payment schedules | `PAYMENTS` | P `project.id` | - |
   | `PAYMENT_TRANCHES` | payment tranches | `PAYMENTS` | P `schedule.project.id` | - |
   | `AMENDMENTS` | estimate amendments | `AMENDMENTS` | P `project.id` | - |
   | `AMENDMENT_LINES` | amendment lines | `AMENDMENTS` | P `amendment.project.id` | - |
   | `PAYROLL` | worker payroll lines | `PAYROLL` | C, O | `worker.id` |
   | `WORKER_PAYOUTS` | worker payouts | `PAYROLL` | C, O | `worker.id` |
   | `CLIENT_MESSAGES` | client message metadata | `CLIENT_MESSAGES` | P `project.id` | - |
   | `BANK_CONNECTIONS` | bank connections | `BANK_CONNECTIONS` | C | - |
   | `BANK_ACCOUNTS` | bank / cash accounts | `BANK_LEDGER` | C | - |
   | `BANK_LEDGER` | bank transactions | `BANK_LEDGER` | C | - |
   | `NOTIFICATIONS` | notifications | `NOTIFICATIONS` | C, OWN | `recipient.id` |
   | `USERS` | users | `USERS` | C | - |
   | `ROLES` | roles | `ROLES` | C | - |
   | `AUDIT_LOG` | audit log | `AUDIT` | C | - |

   2a. **Excluded as reference data (dictionaries)**: `MEASUREMENT_UNITS`, `CURRENCIES`, `VAT_RATES`,
   `ROOM_TYPES`, `WORKER_TYPES`, `WORK_CATEGORIES`, `DELIVERY_CATEGORIES`, `DELIVERY_STATUSES`,
   `UNPLANNED_EXPENSE_CATEGORIES`, `MATERIAL_CATEGORIES`, `MATERIAL_TYPES`, `MATERIALS`,
   `MATERIAL_PRODUCERS`, `MATERIAL_SELLERS`, `CONSTRUCTION_MATERIAL_TYPES`, `OFFER_PACKAGES`,
   `PACKAGE_ASSORTMENT`, and the technical permission catalogs `RESOURCES` and `OPERATIONS`.
   Reference data MAY appear inside other datasets as joined label fields, but is not a root.

   2b. **Action-only resource**: `EXPENSE_UPLOAD` (uploaded documents are read through `PURCHASES` /
   `COMPANY_PURCHASES`).

   2c. **Coverage guard**: a startup/CI test SHALL list all resources from the `resources` table and
   fail when a resource is neither covered by a Dataset nor explicitly listed in 2a/2b.

   2d. **Sensitive fields**: secrets and personal data SHALL never be selectable: password hashes,
   tokens, IBAN / account numbers / external bank and consent ids, user e-mail / phone / NIP, audit
   snapshots, notification body and deep link, client message text and media, document URIs and
   storage keys. Each Dataset's field catalog SHALL be reviewed against this rule.

   2e. **Steering rule for new entities**: THIS spec SHALL deliver an update of the project steering
   `.kiro/steering/entity-creation-rules.md` adding a mandatory checklist step "Provide the entity's
   Dataset to the widget registry". WHEN a new managed entity or resource is added, THEN the author
   SHALL (a) decide whether it is reference data, action-only, or data worth a dashboard, and
   (b) either implement a Dataset (field catalog reviewed against 2d, Required_Resources, project /
   owner paths, date field, registered in the `DatasetRegistry`) or add the resource to the explicit
   exclusion list of 2a/2b with a one-line justification. A change that does neither SHALL fail the
   coverage guard of 2c.

   Exact entity paths are confirmed in design; a dataset whose project path cannot be resolved
   SHALL NOT be shipped.
3. ON every data fetch THE server SHALL, in this order: (1) load the Placement and verify ownership;
   (2) verify the widget is active; (3) verify Has_Access to every Required_Resource using the caller's
   current role permissions (never a client-provided claim); (4) apply project scoping and row scoping
   (Requirement 8); (5) apply config filters, date range, grouping, aggregation, sort and limit;
   (6) return the result. A failure at (2)/(3) SHALL return HTTP 403 with `error.dashboard.widget.disabled` and a
   `reason` (`INACTIVE`, `NO_ACCESS`, `PROJECT_UNAVAILABLE`) and SHALL NOT execute the query.
4. THE query engine SHALL be parameterized (JPA Criteria / bound parameters); field names come only
   from the Dataset whitelist.
5. THE engine SHALL cap result size (`limit`, hard maximum 200 rows; at most 500 groups) and apply a
   statement timeout; exceeding it SHALL return truncated data with a `truncated: true` flag.
6. THE response SHALL be a normalized shape: `columns[]`, `rows[]`, `totals` (optional), `truncated`,
   `generatedAt`. Money values SHALL be returned as decimal strings with currency code.
7. THE endpoint SHALL NOT accept a project or user id parameter that widens access: the project of a
   Project_Widget comes from the stored Placement.

### Requirement 7: Visualizations

**User Story:** As a user, I want data rendered as the right kind of component.

#### Acceptance Criteria

1. THE frontend SHALL implement renderers for `TABLE`, `LIST`, `BAR`, `LINE`, `AREA`, `PIE`, `DONUT`,
   `KPI` and `PROJECT_CARD`, driven only by the normalized response and `presentation`. The charting
   library SHALL be the one already used by the frontend if present; otherwise a single new library
   is chosen in design.
2. `KPI` SHALL show one measure value with label and optional comparison to the previous equal-length
   period (the backend computes it when `presentation.compareToPrevious` is true).
3. `PROJECT_CARD` SHALL render one card per row, showing the configured columns (name, project
   manager, address, area, status, start and end dates, plus link chips), in the visual style of the
   "projects in progress" reference screenshot. The widget MAY return several rows (list of
   projects) or exactly one (Project_Widget).
4. Number, currency, percent, date and status formatting SHALL follow the active UI locale (PL/RU).
5. Charts and tables SHALL be keyboard-accessible, have text alternatives (aria-label with title and
   summary), and not rely on color alone.

### Requirement 8: Authorization and data scoping

**User Story:** As a company owner, I want a worker not to see payroll and a client not to see bank
transactions through widgets.

#### Acceptance Criteria

1. Has_Access SHALL be the single rule for visibility: a role has access to a widget if it holds ANY
   operation on EACH Required_Resource. A resource-level grant of any operation is sufficient;
   ADMIN always passes.
2. A role whose permission matrix changes SHALL see the effect on the next read; the permission cache
   invalidation already used by ABAC SHALL apply (no separate widget cache of access decisions).
3. Project scoping: for project-path datasets, rows SHALL be restricted to the projects returned by
   `ProjectAccessCache.get(userId)` (same rule and same ADMIN handling as `ProjectScopedService`).
   Rows with a null project (e.g. company-level procurement documents) SHALL be returned only to
   callers who may see company-wide data for that resource (ADMIN, or the owner of the row).
4. Row scoping: WHEN `rowScope = OWN`, OR the caller's role is in the Dataset's owner-restricted roles
   (WORKER for `WORK_REPORTS`, `PAYROLL`, `WORKER_PAYOUTS`), the engine SHALL add
   `ownerField = callerId`. Datasets flagged `OWN` (`NOTIFICATIONS`) are ALWAYS restricted to the
   caller's own rows, including for ADMIN.
5. Company-wide datasets (all `C` datasets of Requirement 6.2, e.g. `PAYROLL`, `BANK_LEDGER`,
   `AUDIT_LOG`, `USERS`) SHALL rely solely on Has_Access (and
   owner restriction where defined). Their visibility therefore follows the matrix: for example the
   payroll widget is invisible to WORKER and CLIENT, the bank widgets are invisible to CLIENT.
6. The dashboard endpoints SHALL be self-service (not annotated with `@PermissionResource`), like
   `DisplayPreferencesController`. All authorization is implemented in the services above and covered
   by tests. Admin endpoints SHALL be guarded by `DASHBOARD_WIDGETS`.
7. A widget preview in the admin builder SHALL run with the administrator's own access, and SHALL be
   labelled as such.
8. NO data, field name or config of a dataset the caller cannot access SHALL be returned by the
   library endpoint except the widget's public name, description and the `missingResources` codes
   used for the warning (Requirement 9).

### Requirement 9: Access-aware reading of the library and layout

**User Story:** As a user, I want to see widgets I can no longer open as disabled with a warning,
because an admin may have changed the access matrix.

#### Acceptance Criteria

1. `GET /api/dashboard/library` SHALL return every active widget with `available` (boolean) and
   `unavailableReason` (`NO_ACCESS`) plus `missingResources[]` (the Required_Resources on which the
   caller holds no operation).
2. THE picker SHALL render unavailable widgets visually disabled, non-selectable, with a warning
   icon and tooltip text listing the missing resources (localized resource names), instead of hiding
   them.
3. `GET /api/dashboard` SHALL return the user's Placements with an `state`: `AVAILABLE`,
   `NO_ACCESS` (Has_Access lost), `INACTIVE` (widget deactivated by admin), `PROJECT_UNAVAILABLE`
   (project removed from the user's access or project inactive for a Project_Widget).
4. A Placement in a non-available state SHALL be rendered as a disabled tile with the widget name, a
   warning and a reason message; it SHALL NOT fetch data and SHALL keep its cell. The user can still
   remove it. If access is restored, it becomes available again without re-adding.
5. THE state SHALL be computed from the same Has_Access function used by the data endpoint, so list and
   fetch never disagree.

### Requirement 10: Clickable widget elements

**User Story:** As a user, I want to click elements inside widgets (e.g. open the project's
estimate from a project card).

#### Acceptance Criteria

1. THE Widget_Config `links` SHALL bind a target to an element: `{element, route, params}` where
   `element` is one of `ROW`, `CELL(column)`, `CHART_SEGMENT`, `KPI`, `CARD_TITLE`, `CARD_CHIP(label)`.
2. `route` SHALL be an internal route template from a server-side whitelist that mirrors the frontend
   `route-permissions.ts` (e.g. `/projects/{projectId}`, `/projects/{projectId}/{tab}`,
   `/company/payroll`, `/company/bank-transactions`). External URLs and `javascript:` schemes SHALL
   be rejected at validation.
3. `params` SHALL map template placeholders to a Dataset column of the row, or (for Project_Widgets)
   to the bound project id.
4. THE frontend SHALL render a linked element as an accessible link/button. IF the user lacks the
   route's `requiredPermission` (per `route-permissions.ts`), THE element SHALL render as plain,
   non-clickable text.
5. Navigation SHALL use the client router (no full reload). Charts SHALL support click on a bar/segment
   to navigate when a `CHART_SEGMENT` link is configured.
6. A `PROJECT_CARD` MAY define several `CARD_CHIP` links (e.g. Estimate, Gantt, Payments, Documents),
   each with its own route and label.

### Requirement 11: Admin builder with live preview

**User Story:** As an administrator, I want to build any widget by hand and preview it.

#### Acceptance Criteria

1. THE admin form SHALL edit: code, name/description in RU and PL, span (colSpan, rowSpan), project
   scope, and the Widget_Config through a structured builder (dataset picker, field pickers filtered
   by capability, filter rows, grouping, measures, sort, limit, visualization, links, presentation)
   with an advanced read-only JSON view.
2. THE builder SHALL show a live preview rendered by the same renderers as the Dashboard, scaled to
   the widget's span, using `POST /api/dashboard-widgets/preview` (config + date range + optional
   project id for Project_Widgets). Preview SHALL NOT persist anything.
3. THE preview SHALL surface validation violations inline and SHALL be debounced.
4. THE builder SHALL show the computed Required_Resources and the roles that would see the widget.

### Requirement 12: Single-project widgets

**User Story:** As a user, I want a widget dedicated to one active project, and several copies for
different projects.

#### Acceptance Criteria

1. A Widget with `projectScope = SINGLE_PROJECT` is a template; each Placement SHALL carry a
   non-null `project_id`. A non-project widget SHALL have a null `project_id`.
2. THE picker SHALL list projects the user can access with status `ACTIVE` (default filter; "show
   all statuses" toggle optional) and a search. A project outside the user's `ProjectAccessCache`
   SHALL be rejected with HTTP 403 `error.dashboard.project.forbidden`.
3. THE same template MAY be placed several times for different projects. Each tile SHALL show the
   project name in its header.
4. THE engine SHALL add `projectId = placement.projectId` to the query in addition to project access
   scoping (the stored project must still be in the caller's accessible set at fetch time).
5. IF the project becomes unavailable (membership removed, project deleted) THE Placement state is
   `PROJECT_UNAVAILABLE` (Requirement 9.3).
6. Project_Widgets are only valid for datasets that declare a project path; validation SHALL reject
   `SINGLE_PROJECT` on company-wide datasets.
7. The seeded library SHALL include a generic single-project `PROJECT_CARD` widget (reference
   screenshot) and a few single-project KPI/charts (Requirement 14).

### Requirement 13: AI assistant for widget creation

**User Story:** As an administrator, I want an AI helper that drafts a widget from a description.

#### Acceptance Criteria

1. THE admin builder SHALL provide an "AI assistant" panel: free-text request (and optional UI
   language) producing a DRAFT form state (names, description, span, Widget_Config). The assistant
   SHALL NOT save anything; the administrator reviews, edits and saves.
2. THE backend SHALL expose `POST /api/dashboard-widgets/ai-draft`, guarded by
   `DASHBOARD_WIDGETS/CREATE`.
3. THE implementation SHALL reuse the FOR-05-02 Gemini stack: `GeminiClientFactory`, a JSON
   transport with `responseMimeType=application/json` and a response JSON schema derived from the
   Widget_Config schema, a bounded retry (one corrective retry) driven by the same config validator
   (Requirement 5.6), a call timeout (default 90 s), and a stub client for tests/dev.
4. THE configuration SHALL live under `foremen.dashboard.ai.*` (`enabled`, `llm.stub`, `llm.model`,
   `llm.project`, `llm.location`, `llm.api-key`, `llm.credentials.*`), defaulting to the
   `foremen.rooms-import.llm.*` / `foremen.ocr.llm.*` values; env vars `FOREMEN_DASHBOARD_AI_*`
   documented in `.env.example` and wired in `docker-compose.yml`.
5. THE prompt SHALL contain only the administrator's text and the dataset catalog (field names, types,
   capabilities, route whitelist). It SHALL NOT include any business data or user data. INFO logs
   SHALL contain only sizes and durations.
6. IF the model output stays invalid after the retry, or the provider fails or times out, THE endpoint
   SHALL return HTTP 502 `error.dashboard.ai.failed` / 503 `error.dashboard.ai.disabled` (when
   disabled) and the failure notification of Requirement 17.1 SHALL be emitted.
7. THE returned draft SHALL include a short localized explanation of the choices and the validator's
   warnings, if any.
8. The AI endpoint SHALL be rate-limited per administrator (default 10 calls per minute) to bound cost.

### Requirement 14: Default library seed

**User Story:** As an administrator, I want a useful library out of the box.

#### Acceptance Criteria

1. A Liquibase seed SHALL insert the following widgets (`system_seed = true`, active, idempotent by
   `code`, RU and PL names and descriptions):

   | Code | Visualization | Dataset | Span (c x r) | Scope |
   |---|---|---|---|---|
   | `ACTIVE_PROJECTS` | PROJECT_CARD (list) | PROJECTS | 4x3 | all accessible, status ACTIVE |
   | `PROJECTS_BY_STATUS` | DONUT | PROJECTS | 2x2 | - |
   | `PROJECT_CARD` | PROJECT_CARD | PROJECTS | 2x2 | SINGLE_PROJECT |
   | `WORK_REPORTS_BY_STATUS` | BAR | WORK_REPORTS | 2x2 | - |
   | `WORK_REPORTS_PENDING` | TABLE | WORK_REPORTS | 2x3 | status SUBMITTED |
   | `WORK_REPORTS_TREND` | LINE | WORK_REPORTS | 2x2 | count per week |
   | `MY_WORK_REPORTS` | LIST | WORK_REPORTS | 2x2 | rowScope OWN |
   | `PAYMENTS_RECEIVED` | KPI | PAYMENTS | 1x1 | CONFIRMED, sum, compare to previous |
   | `PAYMENTS_BY_PROJECT` | BAR | PAYMENTS | 2x2 | sum per project |
   | `PAYROLL_TOTAL` | KPI | PAYROLL | 1x1 | sum |
   | `PAYROLL_BY_WORKER` | TABLE | PAYROLL | 2x3 | sum per worker |
   | `PURCHASES_BY_PROJECT` | BAR | PURCHASES | 2x2 | sum documentTotal |
   | `RECENT_PURCHASES` | TABLE | PURCHASES | 2x3 | latest 10 |
   | `BANK_FLOW` | AREA | BANK_LEDGER | 2x2 | sum per day, in vs out |
   | `BANK_PENDING` | KPI | BANK_LEDGER | 1x1 | count status PENDING |
   | `OFFERS_BY_STATUS` | DONUT | OFFERS | 2x2 | - |
   | `USERS_BY_ROLE` | DONUT | USERS | 1x2 | - |
   | `PROJECT_PAYMENTS` | KPI | PAYMENTS | 1x1 | SINGLE_PROJECT |
   | `PROJECT_WORK_REPORTS` | LINE | WORK_REPORTS | 2x2 | SINGLE_PROJECT |
   | `PROJECT_PURCHASES` | TABLE | PURCHASES | 2x3 | SINGLE_PROJECT |

2. Every seeded widget with navigation value SHALL include `links` (e.g. project name to
   `/projects/{projectId}`, pending report row to the project work-reports tab, payroll KPI to
   `/company/payroll`).
3. THE seed SHALL be verified at startup by a test that validates every seeded config with the
   production validator.

### Requirement 15: Default widgets per system role

**User Story:** As a new or existing user, I want a sensible starting dashboard for my role.

#### Acceptance Criteria

1. THE system SHALL store role defaults in `role_default_widgets (role_id, widget_id, cell_index)`
   seeded for System_Roles (non-project widgets only; Project_Widgets need a project and are never
   seeded). Widgets are listed in placement order; final cell indices are fixed in design:

   | Role | Default widgets (code @ cell) |
   |---|---|
   | ADMIN | `ACTIVE_PROJECTS`, `USERS_BY_ROLE`, `PROJECTS_BY_STATUS`, `WORK_REPORTS_BY_STATUS`, `BANK_PENDING` |
   | MANAGER | `ACTIVE_PROJECTS`, `WORK_REPORTS_PENDING`, `PAYMENTS_RECEIVED`, `PAYMENTS_BY_PROJECT`, `OFFERS_BY_STATUS` |
   | FINANCIER | `PAYMENTS_RECEIVED`, `PAYROLL_TOTAL`, `BANK_PENDING`, `BANK_FLOW`, `PAYMENTS_BY_PROJECT`, `RECENT_PURCHASES` |
   | ESTIMATOR | `ACTIVE_PROJECTS`, `OFFERS_BY_STATUS`, `PROJECTS_BY_STATUS` |
   | SALESMAN | `ACTIVE_PROJECTS`, `OFFERS_BY_STATUS`, `PROJECTS_BY_STATUS` |
   | FOREMAN | `ACTIVE_PROJECTS`, `WORK_REPORTS_PENDING`, `WORK_REPORTS_TREND`, `RECENT_PURCHASES` |
   | WORKER | `MY_WORK_REPORTS`, `ACTIVE_PROJECTS` |
   | CLIENT | `ACTIVE_PROJECTS`, `PAYMENTS_RECEIVED` |

   Final cell indices and a check that each default is accessible to its role under the current
   permission matrix are fixed in design; any default not accessible to its role SHALL be dropped
   from the seed rather than shown disabled.
2. THE seed SHALL backfill ALL existing users who have no `user_dashboard_state` row: copy the
   defaults of the user's role into `user_dashboard_widgets` and create the state row, in one
   idempotent changeset.
3. WHEN a new user is created through ANY path (admin create, client self-registration or invite,
   worker invitation, bootstrap admin), THE system SHALL seed that user's defaults and create the
   state row. A seeding failure SHALL NOT fail user creation (best-effort, logged).
4. A lazy safety net SHALL seed on first `GET /api/dashboard` when the user has no state row; users
   who deleted all their widgets (state row exists) SHALL NOT be re-seeded.
5. A role change SHALL NOT alter the existing dashboard.
6. A user with a custom (non-system) role and no defaults SHALL get an empty dashboard.

### Requirement 16: i18n and accessibility

#### Acceptance Criteria

1. ALL user-facing UI text SHALL be defined in `ru.json` and `pl.json` under `dashboard.*` and
   `admin.dashboardWidgets.*` (full key list in design).
2. Backend error codes (`error.dashboard.*`) SHALL have RU/PL messages in the existing error message
   bundles.
3. Widget name/description and all config strings are stored bilingually (RU/PL); the UI shows the
   active locale and falls back to the other language if one is empty.
4. The dashboard SHALL be usable by keyboard; the "+" tile, delete action, date controls and linked
   elements SHALL be focusable with visible focus and accessible names.
5. Interactions SHALL meet touch-target size on tablet/mobile.

### Requirement 17: Notifications

**User Story:** As an administrator or a dashboard owner, I want to be told about relevant events.

#### Acceptance Criteria

This spec only EMITS through the generic `NotificationService.create(recipientId, type, body,
deepLink)` after commit, best-effort (try/catch, failures logged, never block the triggering
operation), following the existing emitter pattern (`@TransactionalEventListener(AFTER_COMMIT)`).
Delivery channels beyond the in-app bell are future work.

1. **AI generation failed**
   - Recipient: the administrator who invoked `ai-draft`.
   - Trigger: provider failure, timeout or invalid output after retry.
   - Type: `notification.dashboard.ai.failed`.
   - Message: RU "Не удалось создать черновик виджета с помощью ИИ" / PL "Nie udało się utworzyć
     szkicu widżetu za pomocą AI". Body = failure reason code.
   - Deep link: `/dashboard-widgets` (opens the widget list). Clickable.
2. **Widget deactivated**
   - Recipient: every user having at least one Placement of that widget.
   - Trigger: administrator deactivates the widget (`active` true to false); one notification per
     user per deactivation, not per Placement.
   - Type: `notification.dashboard.widget.deactivated`.
   - Message: RU "Виджет «{{name}}» отключён администратором и скрыт с вашей панели" / PL "Widżet
     „{{name}}" został wyłączony przez administratora i jest niedostępny na Twoim panelu". Body =
     widget name (the active-locale name is resolved by the frontend from the body payload as per
     the existing emitter pattern).
   - Deep link: `/` (the Dashboard). Clickable.
3. The bell SHALL show these types with the i18n texts above in both locales.

### Requirement 18: Non-functional

#### Acceptance Criteria

1. THE dashboard load SHALL issue one layout request and one data request per widget in parallel (no
   server-side N+1 across widgets); widgets below the fold MAY lazy-load.
2. A data fetch for a typical widget SHALL complete within 1.5 s at p95 on the reference data volume;
   the engine SHALL use existing indexes and the design SHALL add indexes where required by the seeded
   widgets.
3. Backend concurrency: Placement creation SHALL be race-safe (unique `(user_id, cell_index)` and
   unique `(user_id, widget_id, COALESCE(project_id, 0))`); violations map to the 409 codes above.
4. Audit: administrator create/update/activate/deactivate/delete of widgets SHALL be written to the
   existing audit log.
5. Config JSON size SHALL be capped (64 KB).
