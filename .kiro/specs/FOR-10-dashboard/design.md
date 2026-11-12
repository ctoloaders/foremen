# Design Document: FOR-10 — Configurable user dashboard

## Overview

The Panel page becomes a per-user grid of widgets. A widget is a row in a shared library
(`dashboard_widgets`) holding a declarative, versioned **Widget_Config**. The server owns all data
access: the browser never sends queries, only "give me the data of placement N for this date range".
The server resolves the placement, checks the widget and the ABAC resources of its dataset, applies
project/row scoping from the token, runs a whitelisted, parameterized query and returns a normalized
result that generic renderers draw.

Key decisions:

1. **Declarative config over code.** New widgets are data, not releases. A code-defined **Dataset
   registry** is the only bridge to entities, so the config can never name arbitrary tables/fields.
2. **One access function.** `DashboardAccessService.evaluate(user, widget, placement)` produces the
   state used by the library list, the layout read and the data fetch. List and fetch cannot diverge.
3. **Position = cell index.** Only `cell_index` is stored. Spans come from the widget. Tablet and
   mobile reflow the same ordered list.
4. **Project binding on the placement**, not in the config. One template, many placements.
5. **Seeding by state row.** `user_dashboard_state` marks "defaults applied": the Liquibase backfill,
   the user-created event and the lazy safety net are all idempotent against it.
6. **AI is a drafting aid only.** It produces a config that must pass the same validator as a manual
   one and is never saved automatically.

## Architecture

```
Browser (Panel)                       Backend
 DashboardPage ── GET /api/dashboard ───────────► DashboardController (self-service)
  ├ DateFilterBar (sessionStorage)                   ├ DashboardLayoutService   (placements, grid rules)
  ├ DashboardGrid ── GET .../items/{id}/data ──►     ├ DashboardAccessService   (Has_Access, states)
  │   └ WidgetTile → Renderer                        ├ DashboardDataService     (fetch pipeline)
  └ WidgetPicker ── GET /api/dashboard/library ─►    │    └ DatasetRegistry → Dataset → QueryEngine
                                                     ├ DashboardDefaultsService (seed / backfill)
Admin /dashboard-widgets                             └ NotificationService (emit only)
  ├ WidgetList / WidgetForm / Builder
  ├ Preview ─── POST /api/dashboard-widgets/preview ► DashboardWidgetController (@PermissionResource)
  └ AI panel ── POST .../ai-draft ──────────────────►    ├ DashboardWidgetService (CRUD, validate)
                                                         ├ WidgetConfigValidator
                                                         └ DashboardAiService → GeminiWidgetDraftClient
```

### Backend package layout (`com.foremen`)

- `controller/DashboardController` (`/api/dashboard`), `controller/DashboardWidgetController`
  (`/api/dashboard-widgets`, `@PermissionResource("DASHBOARD_WIDGETS")`).
- `dao/model/DashboardWidgetEntity`, `UserDashboardWidgetEntity`, `RoleDefaultWidgetEntity`,
  `UserDashboardStateEntity`; matching DAOs.
- `service/dashboard/`:
  - `config/` — `WidgetConfig` (records), `WidgetConfigValidator`, `RouteWhitelist`.
  - `dataset/` — `Dataset` (interface), `DatasetRegistry`, `FieldDescriptor`, one class per dataset
    (38 classes, one per dataset of the catalog below, grouped in sub-packages `projects`, `estimate`, `catalog`, `execution`, `money`, `system`).
  - `query/` — `DashboardQueryEngine` (JPA Criteria), `QueryContext`, `WidgetResult`.
  - `DashboardAccessService`, `DashboardLayoutService`, `DashboardDataService`,
    `DashboardWidgetService`, `DashboardDefaultsService`, `UserCreatedDashboardListener`,
    `DashboardNotificationEmitter`.
  - `ai/` — `WidgetDraftClient` (interface), `GeminiWidgetDraftClient`, `StubWidgetDraftClient`,
    `DisabledWidgetDraftClient`, `WidgetDraftPrompts`, `WidgetDraftSchemas`, `DashboardAiService`.
  - `appearance/` — `WidgetStyleCatalog` (visualization → allowed style codes, shared with the
    validator), `DashboardAppearanceService`, `PaletteValidator`.
- `config/dashboard/DashboardAiProperties` (`foremen.dashboard.ai.*`).

## Components and Interfaces

Index of the components; details live in the sections named below.

- Backend: controllers, services, dataset registry and query engine, access evaluation: see
  "Architecture" (Backend package layout), "Dataset registry and query engine", "Access evaluation".
- Layout and defaults: see "Layout service" and "Defaults service".
- Data fetching: see "Fetch pipeline".
- Interfaces (REST): see "REST API".
- AI assistant, appearance, notifications: see "AI assistant", "Appearance (palette and styles)",
  "Notifications".
- Frontend components and hooks: see "Frontend" and "Amendment A1" (A1.2 backend, A1.4 frontend).

## Data Models

Index: the persistent model is defined in "Data model (Liquibase, registered LAST after 218)" below;
the config schema in "Widget_Config schema (v1)"; seed data in "Seed configs (summary)".

## Data model (Liquibase, registered LAST after 218)

Changesets numbered from `219` (confirm the current tail at implementation time).

### `219-create-dashboard-tables.xml`

`dashboard_widgets`

| Column | Type | Notes |
|---|---|---|
| id | bigint PK | per `BaseEntity` conventions (audit columns as in sibling tables) |
| code | varchar(64) | unique, immutable, `^[A-Z][A-Z0-9_]{2,63}$` |
| name_ru / name_pl | varchar(255) | NOT NULL |
| description_ru / description_pl | varchar(1000) | nullable |
| config | jsonb | NOT NULL, max 64 KB |
| config_version | int | NOT NULL, = `config.schemaVersion` |
| col_span | smallint | 1..4 |
| row_span | smallint | 1..9 |
| project_scope | varchar(16) | `NONE` / `SINGLE_PROJECT` |
| active | boolean | default true |
| system_seed | boolean | default false |

CHECK constraints on spans and project_scope.

`user_dashboard_appearance` (per-user color configuration, one row per user)

| Column | Type | Notes |
|---|---|---|
| user_id | bigint PK/FK users | ON DELETE CASCADE |
| palette | jsonb, nullable | ordered array of 1..8 `#RRGGBB` strings; NULL = follow theme |
| updated_at | timestamptz | |

`user_dashboard_widget_style` (per-user, per-placement style override)

| Column | Type | Notes |
|---|---|---|
| placement_id | bigint PK/FK user_dashboard_widgets | ON DELETE CASCADE |
| style | varchar(32) | must belong to the visualization's style catalog |

These tables hold appearance only; position data stays exclusively in `user_dashboard_widgets`.

`user_dashboard_widgets`

| Column | Type | Notes |
|---|---|---|
| id | bigint PK | |
| user_id | bigint FK users | ON DELETE CASCADE |
| widget_id | bigint FK dashboard_widgets | ON DELETE RESTRICT |
| cell_index | smallint | CHECK 0..35 |
| project_id | bigint FK projects, nullable | ON DELETE CASCADE |

Indexes: `UNIQUE (user_id, cell_index)`;
`UNIQUE (user_id, widget_id, COALESCE(project_id, 0))`; `INDEX (widget_id)` (in-use check,
deactivation fan-out). Overlap of multi-cell footprints is checked in the service (not expressible as
a simple constraint) inside a transaction holding a per-user advisory lock
(`pg_advisory_xact_lock(user_id)`), which also makes concurrent adds race-safe.

`role_default_widgets (role_id FK, widget_id FK, cell_index)`, PK `(role_id, cell_index)`.

`user_dashboard_state (user_id PK/FK users ON DELETE CASCADE, initialized_at timestamptz)`.

### `220-seed-dashboard-widgets-resource.xml`

Two changesets per the entity-creation checklist: (1) resource `DASHBOARD_WIDGETS`
(`name_ru` «Виджеты панели», `name_pl` „Widżety panelu", descriptions RU/PL) guarded by
`SELECT COUNT(*) FROM resources WHERE code='DASHBOARD_WIDGETS'`; (2) ADMIN `role_resources` +
`role_resource_operations` CREATE/READ/UPDATE/DELETE guarded by the ADMIN/resource count check.
No grants for other roles.

### `221-seed-dashboard-widget-library.xml`

Inserts the 20 widgets of Requirement 14, each guarded by `SELECT COUNT(*) FROM dashboard_widgets
WHERE code=...`. Configs are authored as JSON literals (see "Seed configs" below).

### `222-seed-dashboard-role-defaults.xml`

Fills `role_default_widgets` for System_Roles, then backfills existing users (SQL, idempotent):

```sql
INSERT INTO user_dashboard_widgets (user_id, widget_id, cell_index, project_id, ...audit)
SELECT u.id, rdw.widget_id, rdw.cell_index, NULL, ...
FROM users u
JOIN roles r ON r.id = u.role_id            -- actual FK name confirmed in implementation
JOIN role_default_widgets rdw ON rdw.role_id = r.id
WHERE NOT EXISTS (SELECT 1 FROM user_dashboard_state s WHERE s.user_id = u.id);

INSERT INTO user_dashboard_state (user_id, initialized_at)
SELECT u.id, now() FROM users u
WHERE NOT EXISTS (SELECT 1 FROM user_dashboard_state s WHERE s.user_id = u.id);
```

Order matters: placements first, then state rows. Both statements are in one changeset (one
transaction). `onFail="MARK_RAN"` is not needed because the NOT EXISTS guards make re-runs no-ops.

### Role default placements (final)

Grid is 4 columns; `cell = row*4 + col`. Footprints verified to not overlap.

| Role | Placements (code@cell) |
|---|---|
| ADMIN | `ACTIVE_PROJECTS`@0 (rows 0-2), `USERS_BY_ROLE`@12 (1x2), `PROJECTS_BY_STATUS`@13 (2x2), `BANK_PENDING`@15, `WORK_REPORTS_BY_STATUS`@20 (2x2) |
| MANAGER | `ACTIVE_PROJECTS`@0, `PAYMENTS_RECEIVED`@12, `WORK_REPORTS_PENDING`@14 (2x3), `PAYMENTS_BY_PROJECT`@16 (2x2), `OFFERS_BY_STATUS`@24 (2x2) |
| FINANCIER | `PAYMENTS_RECEIVED`@0, `PAYROLL_TOTAL`@1, `BANK_PENDING`@2, `BANK_FLOW`@4 (2x2), `PAYMENTS_BY_PROJECT`@6 (2x2), `RECENT_PURCHASES`@12 (2x3) |
| ESTIMATOR | `ACTIVE_PROJECTS`@0, `OFFERS_BY_STATUS`@12 (2x2), `PROJECTS_BY_STATUS`@14 (2x2) |
| SALESMAN | same as ESTIMATOR |
| FOREMAN | `ACTIVE_PROJECTS`@0, `WORK_REPORTS_PENDING`@12 (2x3), `WORK_REPORTS_TREND`@14 (2x2), `RECENT_PURCHASES`@24 (2x3) |
| WORKER | `MY_WORK_REPORTS`@0 (2x2), `ACTIVE_PROJECTS`@8 (4x3) |
| CLIENT | `ACTIVE_PROJECTS`@0, `PAYMENTS_RECEIVED`@12 |

A seed-time test computes, for each row, `Has_Access(role, widget)` against the seeded
`role_resource_operations`; rows failing it are removed from the changeset (Requirement 15.1) and the
removal is logged in the PR.

## Widget_Config schema (v1)

```json
{
  "schemaVersion": 1,
  "dataset": "WORK_REPORTS",
  "visualization": "BAR",
  "title": { "ru": "Отчёты по статусам", "pl": "Raporty wg statusu" },
  "rowScope": "PROJECTS",
  "dateField": "performedFrom",
  "dimensions": [ { "field": "status" } ],
  "measures": [
    { "field": "id", "agg": "COUNT", "alias": "cnt",
      "label": { "ru": "Кол-во", "pl": "Liczba" } }
  ],
  "filters": [ { "field": "status", "op": "IN", "value": ["SUBMITTED", "APPROVED"] } ],
  "sort": [ { "by": "cnt", "dir": "DESC" } ],
  "limit": 20,
  "links": [
    { "element": "CHART_SEGMENT", "route": "/projects/{projectId}/{tab}",
      "params": { "projectId": "$row.project.id", "tab": "work-reports" } }
  ],
  "presentation": { "style": "SOLID", "colors": ["#2563EB", "#16A34A"],
                    "stacked": false, "legend": true,
                    "emptyText": { "ru": "Нет данных", "pl": "Brak danych" } }
}
```

Shape rules enforced by `WidgetConfigValidator` (all violations collected, not first-fail):

| Visualization | Required shape |
|---|---|
| TABLE / LIST | `columns` (1..12) selectable; no measures, or measures + dimensions (grouped table) |
| BAR / LINE / AREA | exactly 1 dimension (date bucket allowed) + 1..3 measures |
| PIE / DONUT | exactly 1 dimension + exactly 1 measure |
| KPI | exactly 1 measure, 0 dimensions; optional `presentation.compareToPrevious` requires `dateField` |
| PROJECT_CARD | dataset `PROJECTS`; `columns` from the card field set; `chips` via CARD_CHIP links |

Other rules: every `$row.<path>` in link `params` must be a selected column/dimension of the config
(so the value is present in the result); `route` must match the `RouteWhitelist` template exactly and
every placeholder must be bound; `SINGLE_PROJECT` requires the dataset to declare a project path and
the config to expose no link param that contradicts it (`projectId` param in a Project_Widget may use
`$project`).

### Route whitelist

`RouteWhitelist` is a constant set of route templates, kept in sync with the frontend by a contract
test that reads the frontend `route-permissions.ts` route keys (a JSON export generated at frontend
build, checked in as `route-whitelist.json`). Initial entries: `/projects/{projectId}`,
`/projects/{projectId}/{tab}`, `/company/payroll`, `/company/bank-transactions`,
`/company/procurement`, `/company/staffing-gantt`, `/users`. `{tab}` accepts only the known project
workspace tab slugs (enumerated).

## Dataset registry and query engine

```java
public interface Dataset {
    String code();
    Set<String> requiredResources();               // Has_Access = ANY op on EACH
    List<FieldDescriptor> fields();                // name, type, selectable/filterable/groupable/aggregatable
    Optional<String> projectPath();                // JPA path to project id, empty = company-wide
    Optional<String> ownerPath();                  // JPA path to owner user id
    Set<String> ownerRestrictedRoles();            // e.g. WORKER
    Optional<String> defaultDateField();
    Class<?> rootEntity();
    Predicate extraPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root); // dataset-specific (e.g. null-project rule)
}
```

`FieldDescriptor`: `{name, jpaPath, type (STRING|NUMBER|MONEY|DATE|DATETIME|ENUM|BOOLEAN|ID),
enumValues?, labelKey, flags}`. `jpaPath` may traverse to-one associations only; a joined
`label` (e.g. project name, worker name) is exposed as its own field.

`QueryContext`: `userId`, `roleCode`, `allowedProjectIds` (from `ProjectAccessCache`),
`isAdmin`, `dateFrom`, `dateTo`, `boundProjectId?`, `locale`.

`DashboardQueryEngine.execute(Dataset, WidgetConfig, QueryContext)`:

1. Build `CriteriaQuery<Tuple>` on `rootEntity`.
2. Select: dimensions (with date bucket via `function('date_trunc', ...)`/`cb.function`), measures
   (`cb.count`, `countDistinct`, `sum`, `avg`, `min`, `max`), or plain columns.
3. Predicates (ANDed, all parameter-bound): project scope (`projectPath IN allowedProjectIds`, skipped
   for ADMIN exactly as `ProjectScopedService` does; null-project rule via `extraPredicate`),
   `boundProjectId` equality, row scope (`ownerPath = userId` if `rowScope=OWN` or role restricted),
   config filters, date range on `dateField` (inclusive; datetimes compare `>= from 00:00` and
   `< to+1 00:00` in the app time zone).
4. Group by dimensions, order by `sort` (only selected aliases), `setMaxResults(min(limit,200)+1)` to
   detect truncation, query hint statement timeout (`jakarta.persistence.query.timeout` 3000 ms).
5. For KPI + `compareToPrevious`, run a second query on the previous equal-length period and attach
   `previous`.
6. Map to `WidgetResult { columns[{key,type,label}], rows[], totals?, previous?, truncated, generatedAt }`.
   Money = `{ "amount": "123.45", "currency": "PLN" }` (currency from the entity or the company
   default currency when the entity has none).

The engine uses no native SQL. Datasets that cannot be expressed through Criteria are out of v1.

### Dataset catalog (v1)

Notation: `P(path)` project-scoped through the JPA path; `C` company-wide; `O{roles}` owner-restricted
roles; `OWN` always own rows. Field names are the config-facing names; `a.b` means a to-one join.
Reference-data joins (unit, category, currency, type, producer, seller) are exposed only as label
fields (`unit.name`), never as separate roots. Money fields carry the row's currency (or the company
default). Fields marked (L) are LOOKUP fields: scalar correlated subqueries declared by the dataset
(needed where the entity keeps a plain id, e.g. `ProjectMemberEntity.projectId`).

Coverage rule: every resource row in `resources` is either covered below or listed in the exclusion
set (reference data, `RESOURCES`, `OPERATIONS`, `EXPENSE_UPLOAD`); `DatasetCoverageTest` enforces it.

**Projects and team**

| Dataset | Resource | Scope | Date field | Selectable fields |
|---|---|---|---|---|
| `PROJECTS` | PROJECTS | P(`id`) | `startDate` | `id`, `name`, `address`, `area`, `status`, `startDate`, `endDate`, `managerName` (L: member with project role MANAGER) |
| `PROJECT_MEMBERS` | PROJECT_MEMBERS | P(`projectId`) | `createdDate` | `id`, `projectId`, `projectName` (L), `user.name`, `projectRole.code`, `workerType.name`, `assignmentStatus` |
| `ROOMS` | ROOMS | P(`project.id`) | `createdDate` | `id`, `project.id`, `project.name`, `label`, `roomType.name`, `floorArea`, `wallArea`, `perimeter`, `ceilingHeight`, `doorCount`, `windowCount`, `aiImported` |
| `SCHEDULE_BARS` | WORK_SCHEDULE | P(`schedule.project.id`) | - | `id`, `schedule.project.id/name`, `workCategory.name`, `projectMember.user.name`, `startHalfDays`, `durationHalfDays` |
| `COMPANY_SCHEDULE_BARS` | COMPANY_SCHEDULE | P(`schedule.project.id`) | - | same fields as `SCHEDULE_BARS` (cross-project view for the staffing Gantt audience) |

Half-day offsets have no absolute calendar date in the bar entity; the schedule's anchor date is
used if `ProjectScheduleService` exposes it, otherwise these two datasets are limited to numeric
aggregations (no `dateField`). Decided in implementation.

**Estimate, offers, amendments, documents**

| Dataset | Resource | Scope | Date field | Selectable fields |
|---|---|---|---|---|
| `ESTIMATES` | ESTIMATE | P(`project.id`) | `createdDate` | `id`, `project.id/name`, `status`, `currency.code`, `totalNet`, `totalVat`, `totalGross`, `appliedPackageCode` |
| `ESTIMATE_LINES` | ESTIMATE | P(`estimate.project.id`) | `createdDate` | `id`, `estimate.project.id/name`, `lineNo`, `workItem.code`, `workItem.name`, `unit.name`, `quantity`, `unitPrice`, `unitPriceOverride`, `valueNet` |
| `OFFERS` | OFFERS | P(`project.id`) | `createdDate` | `id`, `project.id/name`, `status`, `revision`, `approvedRevision`, `selectedPackage.name`, `totalNet`, `totalVat`, `totalGross` |
| `OFFER_NEGOTIATION_ROUNDS` | OFFERS | P(`offer.project.id`) | `createdDate` | `id`, `offer.project.id/name`, `offerRevision`, `roundNo`, `initiatorRole`, `kind`, `scope`, `valueKind`, `value`, `status`, `adminApproved` (no free-text justification / comments) |
| `AMENDMENTS` | AMENDMENTS | P(`project.id`) | `effectiveDate` | `id`, `project.id/name`, `seqNo`, `status`, `effectiveDate` (no note) |
| `AMENDMENT_LINES` | AMENDMENTS | P(`amendment.project.id`) | `amendment.effectiveDate` | `id`, `amendment.project.id/name`, `amendment.seqNo`, `changeType`, `workItem.name`, `room.label`, `deltaQuantity`, `extraUnitPrice`, `scheduleDeltaHalfDays` |
| `SIGNABLE_DOCUMENTS` | SIGNABLE_DOCUMENTS | P(`project.id`); null-project rows: creator/ADMIN | `createdDate` | `id`, `project.id/name`, `documentType.code`, `documentType.name`, `status`, `title`, `signatureLevel`, `templateLocale`, `createdByUser.name` (no URI, hash, source ref) |
| `SIGNABLE_DOCUMENT_TYPES` | SIGNABLE_DOCUMENT_TYPES | C | - | `id`, `code`, `nameRU`, `namePL`, `active`, `defaultSignatureLevel` |
| `DOCUMENT_TEMPLATES` | DOCUMENT_TEMPLATES | C | `createdDate` | `id`, `documentType.code/name`, `name`, `locale`, `version`, `active`, `uploadedBy.name` (no storage URI) |
| `COMPANY_PROFILE` | COMPANY_PROFILE | C (single row) | - | `fullName`, `registeredAddress`, `representativeName`, `representativeRole` (no e-mail, NIP, REGON) |

**Catalogs and materials** (the priced catalogs shown in the "Resources" navigation section)

| Dataset | Resource | Scope | Date field | Selectable fields |
|---|---|---|---|---|
| `WORK_CATALOG` | WORK_CATALOG | C | `createdDate` | `id`, `code`, `nameRU`, `namePL`, `workCategory.name`, `unit.name`, `active` |
| `WORK_PRICES` | WORK_PRICES | C | `createdDate` | `id`, `workItem.code`, `workItem.nameRU/namePL`, `currency.code`, `netPrice` |
| `WORK_MATERIAL_CONSUMPTION` | WORK_MATERIAL_CONSUMPTION | C | - | `id`, `workItem.code/name`, `branch`, `consumptionBasis`, `normQty`, `wastePct`, `materialUnit.name`, `constructionMaterialType.name`, `finishingMaterialType.name`, `sourceType` |
| `CONSTRUCTION_MATERIALS` | MATERIALS_CONSTRUCTION | C | `createdDate` | `id`, `nameRU`, `namePL`, `type.name`, `producer.name`, `seller.name`, `unit.name`, `currency.code`, `purchasePrice`, `retailNet`, `retailGross`, `costNet`, `active` |
| `FINISHING_MATERIALS` | MATERIALS_FINISHING | C | `createdDate` | `id`, `category.name`, `material.name`, `type.name`, `producer.name`, `model`, `sku`, `unit.name`, `purchasePrice`, `retailNet`, `retailGross`, `costNet`, `active` |

**Execution and procurement**

| Dataset | Resource | Scope | Date field | Selectable fields |
|---|---|---|---|---|
| `WORK_REPORTS` | WORK_REPORTS | P(`project.id`), O{WORKER}=`worker.id` | `performedFrom` | `id`, `project.id/name`, `workItem.name`, `worker.id/name`, `author.name`, `performedFrom`, `performedTo`, `status`, `approvedAt`, `approvedBy.name` (no note / rejection text) |
| `PURCHASES` | PURCHASES | P(`project.id`); null project: uploader/ADMIN | `documentDate` (coalesce of `invoiceDate`, `uploadedAt`) | `id`, `project.id/name`, `supplierName`, `documentTotal`, `invoiceDate`, `status`, `uploadedAt`, `uploadedBy.name`, `expenseCategory.name` (no object keys, NIP, invoice number) |
| `PURCHASE_LINES` | PURCHASES | P(`document.project.id`), same null rule | `document.documentDate` | `id`, `document.project.id/name`, `document.supplierName`, `lineNo`, `rawName`, `quantity`, `unitPrice`, `lineTotal`, `matchStatus`, `unplannedCategory.name` |
| `COMPANY_PURCHASES` | COMPANY_PURCHASES | C | `documentDate` | same fields as `PURCHASES` |
| `COMPANY_PURCHASE_LINES` | COMPANY_PURCHASES | C | `document.documentDate` | same fields as `PURCHASE_LINES` |
| `CLIENT_MESSAGES` | CLIENT_MESSAGES | P(`project.id`) | `createdDate` | `id`, `project.id/name`, `author.name`, `authorSide`, `readAt`, computed `unread` (`readAt IS NULL`); no message text, translations or media |

**Money**

| Dataset | Resource | Scope | Date field | Selectable fields |
|---|---|---|---|---|
| `PAYMENTS` | PAYMENTS | P(`schedule.project.id`); null schedule: ADMIN only | `receivedAt` | `id`, `schedule.project.id/name`, `amountGross`, `fee`, `status`, `channel`, `receivedAt` (no external ref, note) |
| `PAYMENT_SCHEDULES` | PAYMENTS | P(`project.id`) | `createdDate` | `id`, `project.id/name`, `currency`, `basisGross`, `unallocatedCredit`, `source`, `status` |
| `PAYMENT_TRANCHES` | PAYMENTS | P(`schedule.project.id`) | `dueDate` | `id`, `schedule.project.id/name`, `seqNo`, `dueDate`, `amountGross`, `paidGross`, computed `outstanding` (`amountGross-paidGross`), `status` |
| `PAYROLL` | PAYROLL | C, O{WORKER}=`worker.id` | `month` | `id`, `worker.id/name`, `month`, `name`, `amount` (no note) |
| `WORKER_PAYOUTS` | PAYROLL | C, O{WORKER}=`worker.id` | `payoutDate` | `id`, `worker.id/name`, `payoutDate`, `periodFrom`, `periodTo`, `amount`, `suggestedAmount`, `advanceApplied`, `paymentChannel`, `status`, `isAdvance`, `paidAt` (no note) |
| `BANK_CONNECTIONS` | BANK_CONNECTIONS | C | `lastSyncAt` | `id`, `bankName`, `displayName`, `provider`, `status`, `consentExpiresAt`, `lastSyncAt`, `lastSuccessfulSyncAt`, `lastErrorCode` (no external ids, consent ids, error messages, logo url) |
| `BANK_ACCOUNTS` | BANK_LEDGER | C | `balanceUpdatedAt` | `id`, `name`, `type`, `nature`, `currency`, `balance`, `availableBalance`, `openingBalance`, `archived`, `includedInLedger`, `hasBalanceGap`, `connection.bankName` (no IBAN, account number, holder data) |
| `BANK_LEDGER` | BANK_LEDGER | C; excludes ignored rows | `operationDate` | `id`, `account.name`, `amount`, `currency`, `operationDate`, `bookingDate`, `status`, `channel`, `origin`, `counterpartyName`, `title`, computed `direction` (`amount>0` IN / `<0` OUT); no IBAN, counterparty key, raw payload |

Resource decision to verify: `BANK_ACCOUNTS` is gated by `BANK_LEDGER` on the assumption that the
ledger screen exposes balances; if balances are exposed by an endpoint guarded by `BANK_CONNECTIONS`,
the Required_Resources follow whichever resource the existing endpoint requires.

**System**

| Dataset | Resource | Scope | Date field | Selectable fields |
|---|---|---|---|---|
| `USERS` | USERS | C | `createdDate` | `id`, `role.code`, `status`, `active`, `locale`, `workerType.name`, `workerKind` (no name, e-mail, phone, NIP; counts/groupings only) |
| `ROLES` | ROLES | C | - | `id`, `code`, `nameRU`, `namePL`, `system` |
| `AUDIT_LOG` | AUDIT | C | `performedAt` | `id`, `entityClass`, `entityId`, `operation`, `performedBy`, `performedAt` (no snapshots) |
| `NOTIFICATIONS` | NOTIFICATIONS | C, OWN=`recipient.id` | `createdDate` | `id`, `type`, `read`, `createdDate` (no body, no deep link) |

Where an entity path above differs from the real model, implementation adjusts the path only; a
dataset's project path must resolve, otherwise the dataset is not registered (startup failure).
Entities that keep a plain project id (`ProjectMemberEntity`) use `projectPath` on that id and LOOKUP
fields for labels.

### Dataset SPI additions

```java
public interface Dataset {
    ...
    boolean alwaysOwn();                          // NOTIFICATIONS: owner filter even for ADMIN
    default List<LookupField> lookups() { return List.of(); }
}
```

`SensitiveFieldGuardTest` asserts that no dataset's catalog exposes a field name from a deny-list
(`passwordHash`, `iban`, `accountNumber`, `externalId`, `externalConnectionId`, `externalConsentId`,
`email`, `phone`, `nip`, `snapshotBefore`, `snapshotAfter`, `body`, `deepLink`, `originalText`,
`documentUri`, `contentHash`, `rawObjectKey`, `processedObjectKey`, `storageUri`) at any join depth.
`DatasetCoverageTest` enforces the resource coverage rule above.

## Access evaluation

```java
public enum WidgetState { AVAILABLE, NO_ACCESS, INACTIVE, PROJECT_UNAVAILABLE }

DashboardAccessService:
  Set<String> missingResources(String roleCode, Set<String> required)
  WidgetState evaluate(AuthenticatedUser u, DashboardWidgetEntity w, Long boundProjectId)
```

- `missingResources`: loads the `PermissionSet` once through `ForemenPermissionEvaluator
  .loadPermissionSet` / `PermissionCache`, and for each required resource checks
  `allows(resource, op)` for the four operations (new helper `PermissionSet.hasAnyOperation(resource)`
  that scans grant keys by prefix; unit-tested). ADMIN returns an empty set.
- `evaluate` order: widget inactive → `INACTIVE`; missing resources → `NO_ACCESS`; Project_Widget
  with `boundProjectId` not in `allowedProjectIds` (non-admin) or project missing → `PROJECT_UNAVAILABLE`;
  else `AVAILABLE`.
- Because the evaluator and cache are the ones used by `PermissionInterceptor`, an admin matrix change
  takes effect with the existing cache invalidation.

## Layout service

Grid math (`GridMath`, pure, unit/property tested):

- `row(cell) = cell / 4`, `col(cell) = cell % 4`.
- `fits(cell, cs, rs) = col + cs <= 4 && row + rs <= 9`.
- `footprint(cell, cs, rs) = {(r,c) | r in [row,row+rs), c in [col,col+cs)}` as a bitmask of 36 bits.
- `occupied(user) = OR of footprints of the user's placements`.
- `firstFit(occupied, cs, rs, startAt)` scans cells `startAt..35`, then `0..startAt-1`, returning the
  first cell whose footprint fits and does not intersect `occupied`.

`addPlacement(user, widgetId, cellIndex?, projectId?)` in one transaction under
`pg_advisory_xact_lock(userId)`:
1. Load widget; reject inactive (`409 error.dashboard.widget.inactive`) or no access
   (`403 error.dashboard.widget.disabled`).
2. Project rules: Project_Widget ⇒ `projectId` required and in the user's accessible set (non-admin);
   non-project ⇒ `projectId` must be null.
3. Duplicate rules (Requirement 3.6).
4. Position: if `cellIndex` given, require `fits` and no intersection (`409 occupied` /
   `400 invalid`); else `firstFit`. None ⇒ `409 error.dashboard.grid.full`.
   (The backend never falls back to `firstFit` when `cellIndex` is given; the picker no longer relies on it
   either, see Amendment A1, Req 21.9.)
5. Save; return the placement view.

Sorting by `cell_index` is the canonical order for tablet/mobile.

## Fetch pipeline (`DashboardDataService.fetch(userId, placementId, from, to)`)

Steps exactly as Requirement 6.3. Errors: 404 for unknown placement id *and* foreign ownership is
mapped to 403 `error.dashboard.forbidden` for foreign, 404 for missing (same as notification
service semantics). Date validation errors return 400 `error.dashboard.date.invalid`.

Admin preview uses the same pipeline minus placement: `previewFetch(adminUser, config, from, to,
projectId?)`, executed with the admin's own `QueryContext` (so a preview of a payroll widget for an
admin works; for an admin without a data access it is still evaluated through Has_Access, ADMIN passes).

## Defaults service

`DashboardDefaultsService.seedFor(userId)` (idempotent, transactional, lock per user):
if a state row exists → return; load user's role code; copy `role_default_widgets` rows to
placements (skipping footprints that no longer fit, which cannot happen with the seed); insert state
row (also when the role has no defaults, so a custom-role user is "initialized" with an empty
dashboard).

Trigger points:
- `UserCreatedEvent(userId)` published by `UserService` at the end of `create`, `createClient`,
  `createWorkerRecord`, and by `ClientRegistrationService`, `InviteService`,
  `WorkerInvitationService`, `AdminBootstrap` when they persist users (if any of these delegate to
  `UserService`, the single publish point suffices — verified in implementation). A
  `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` listener calls `seedFor` in
  `REQUIRES_NEW`, wrapped in try/catch (best-effort).
- Lazy: `DashboardLayoutService.read` calls `seedFor` when no state row exists.

## REST API

User (self-service, `/api/dashboard`, no `@PermissionResource`):

| Method | Path | Description |
|---|---|---|
| GET | `/api/dashboard` | `{ items: [ {id, widgetId, code, nameRu, namePl, cellIndex, colSpan, rowSpan, visualization, projectId?, projectName?, dateAware, state, missingResources[]} ] }` ordered by cell index |
| GET | `/api/dashboard/library` | `[ {id, code, nameRu, namePl, descriptionRu, descriptionPl, visualization, colSpan, rowSpan, projectScope, available, unavailableReason?, missingResources[]} ]` active widgets only |
| POST | `/api/dashboard/items` | body `{widgetId, cellIndex?, projectId?}` → created item |
| DELETE | `/api/dashboard/items/{id}` | removes own placement |
| GET | `/api/dashboard/items/{id}/data?dateFrom&dateTo` | `WidgetResult` |
| GET | `/api/dashboard/appearance` | own appearance: `{ palette: string[]\|null, styles: { [placementId]: styleCode } }` |
| PUT | `/api/dashboard/appearance` | body `{ palette?: string[]\|null, styles?: { [placementId]: styleCode\|null } }`; partial update, `null` resets; validates palette format and style catalog; placement ids must belong to the caller |
| GET | `/api/dashboard/projects?q=` | thin read over the existing project listing: projects accessible to the caller with status ACTIVE (+`all=true`) for the picker (`id`, `name`, `address`) |

Admin (`/api/dashboard-widgets`, `@PermissionResource("DASHBOARD_WIDGETS")`,
`@PermissionOperation` or `@RequiresPermission` per handler):

| Method | Path | Operation |
|---|---|---|
| GET | `/api/dashboard-widgets` | READ — paged list with search/active filter, `visibleToRoles[]` |
| GET | `/api/dashboard-widgets/{id}` | READ |
| POST | `/api/dashboard-widgets` | CREATE |
| PUT | `/api/dashboard-widgets/{id}` | UPDATE (`code` immutable) |
| POST | `/api/dashboard-widgets/{id}/activate` · `/deactivate` | UPDATE (deactivate emits notifications) |
| DELETE | `/api/dashboard-widgets/{id}` | DELETE (409 if in use or `system_seed`) |
| GET | `/api/dashboard-widgets/datasets` | READ — dataset catalog |
| POST | `/api/dashboard-widgets/preview` | READ — `{config, colSpan, rowSpan, projectScope, projectId?, dateFrom, dateTo}` |
| POST | `/api/dashboard-widgets/validate` | READ — returns violations without saving |
| POST | `/api/dashboard-widgets/ai-draft` | CREATE — `{prompt, locale}` → `{draft, explanation, warnings[]}` |

`visibleToRoles` = system+custom roles for which `missingResources` is empty (computed over all
roles with one query on the matrix).

Error codes (RU/PL messages added to the backend message bundles):
`error.dashboard.cell.invalid`, `.cell.occupied`, `.grid.full`, `.widget.duplicate`,
`.widget.inactive`, `.widget.disabled`, `.widget.code.exists`, `.widget.in.use`, `.config.invalid`,
`.project.forbidden`, `.forbidden`, `.date.invalid`, `.ai.failed`, `.ai.disabled`,
`.ai.rate.limited`, `.appearance.palette.invalid`, `.appearance.style.invalid`.

## Appearance (palette and styles)

Appearance is purely presentational and never touches the data pipeline.

- **Style catalog** (`WidgetStyleCatalog`, code constant mirrored by the frontend type): TABLE
  `STANDARD|COMPACT|STRIPED`; LIST `STANDARD|COMPACT|DIVIDED`; BAR/LINE/AREA
  `SOLID|OUTLINED|MINIMAL`; PIE/DONUT `STANDARD|THIN_RING|WITH_LABELS`; KPI
  `PLAIN|CARD|WITH_TREND`; PROJECT_CARD `DETAILED|COMPACT|MINIMAL`. The first entry is the fallback
  when neither the config nor the user selects one. `WidgetConfigValidator` rejects
  `presentation.style` outside the visualization's list and malformed `presentation.colors`
  (`#RRGGBB`, up to 8).
- **Effective style** for a tile = user override (`user_dashboard_widget_style`) → config
  `presentation.style` → first catalog entry. A stored override that is no longer in the catalog is
  ignored (and cleaned on next PUT).
- **Effective palette** = user custom palette (`user_dashboard_appearance.palette`) → palette derived
  from the user's appearance settings (theme + accent) → config `presentation.colors` → app default.
  The derived palette is generated on the client from the existing appearance/theme state (hue
  rotation around the accent in the active theme), so a theme change re-renders widgets without a
  request. Semantic colors (status, positive/negative) are fixed tokens, not part of the palette.
- **Server side**: `DashboardAppearanceService.get/update(userId, ...)` always works on the
  authenticated user; `PaletteValidator` checks `^#[0-9A-Fa-f]{6}$`, size 1..8, no duplicates;
  `GET /api/dashboard` also returns per-item `style` (effective), `defaultStyle` and
  `availableStyles` so the tile menu needs no extra request; placement deletion cascades to the style
  row.
- **Contrast**: the client `lib/color.ts#ensureContrast(color, background, 3:1)` adjusts lightness
  toward the nearest accessible shade; applied to chart series and text on colored fills. Pure
  function, property-tested.
- No effect on caching or authorization; appearance endpoints are self-service (unguarded like the
  other `/api/dashboard` endpoints).

## AI assistant

- `WidgetDraftClient.draft(DraftRequest{prompt, locale, catalog, routes}) → DraftResponse{names,
  descriptions, colSpan, rowSpan, projectScope, config, explanation{ru,pl}}`.
- `GeminiWidgetDraftClient` mirrors `GeminiRoomsVisionClient`: uses `GeminiClientFactory`, a
  `GeminiJsonTransport` implementation (the rooms-import `GenAiJsonTransport` is parametrized or a
  small text-only twin is added; no images), structured output with `WidgetDraftSchemas.draft()`
  (JSON schema generated from the config records), `MAX_ATTEMPTS = 2`, retry suffix listing validator
  violations, timeout 90 s, `stripFence` tolerance, INFO logs with sizes/durations only.
- Active when `foremen.dashboard.ai.enabled && !llm.stub`; `StubWidgetDraftClient` when
  `llm.stub` (deterministic canned draft for tests/e2e); `DisabledWidgetDraftClient` otherwise
  (503 `error.dashboard.ai.disabled`).
- `DashboardAiService`: rate limit (Caffeine per-user counter, 10/min), call the client, validate the
  draft with `WidgetConfigValidator` (a draft failing validation after the in-client retry is treated
  as failure), return warnings, emit the failure notification on failure.
- Prompt = system instruction (role, config schema documentation, rules: use only catalog fields,
  bilingual strings, choose the simplest visualization, no free SQL) + user text + serialized
  catalog (no data). Output language of names/labels: both RU and PL always.
- Properties/env:

```yaml
foremen:
  dashboard:
    ai:
      enabled: ${FOREMEN_DASHBOARD_AI_ENABLED:false}
      rate-limit-per-minute: 10
      llm:
        stub: ${FOREMEN_DASHBOARD_AI_LLM_STUB:${FOREMEN_ROOMS_IMPORT_LLM_STUB:false}}
        model: ${FOREMEN_DASHBOARD_AI_LLM_MODEL:${FOREMEN_ROOMS_IMPORT_LLM_MODEL:${FOREMEN_OCR_LLM_MODEL:gemini-3.8-flash}}}
        project: ${FOREMEN_DASHBOARD_AI_LLM_PROJECT:${FOREMEN_ROOMS_IMPORT_LLM_PROJECT:}}
        location: ${FOREMEN_DASHBOARD_AI_LLM_LOCATION:${FOREMEN_ROOMS_IMPORT_LLM_LOCATION:}}
        api-key: ${FOREMEN_DASHBOARD_AI_LLM_API_KEY:${FOREMEN_ROOMS_IMPORT_LLM_API_KEY:}}
        credentials:
          path: ${FOREMEN_DASHBOARD_AI_LLM_CREDENTIALS_PATH:${FOREMEN_ROOMS_IMPORT_LLM_CREDENTIALS_PATH:}}
          location: ${FOREMEN_DASHBOARD_AI_LLM_CREDENTIALS_LOCATION:${FOREMEN_ROOMS_IMPORT_LLM_CREDENTIALS_LOCATION:}}
```

Env vars are added to `.env.example` and `docker-compose.yml`, mirroring the rooms-import block.

## Notifications

Emitter `DashboardNotificationEmitter` follows `RoomsImportNotificationEmitter` (best-effort,
try/catch, `NotificationService.create`).

| Type | Recipient | Trigger | Body | Deep link |
|---|---|---|---|---|
| `notification.dashboard.ai.failed` | invoking admin | `DashboardAiService` failure | reason code | `/dashboard-widgets` |
| `notification.dashboard.widget.deactivated` | each distinct user with a placement of the widget | `DashboardWidgetService.deactivate` publishes `DashboardWidgetDeactivatedEvent(widgetId)`; listener `AFTER_COMMIT` | widget name (`name_ru`; the frontend renders `t(type, {name: body})` and the existing bell behaviour is confirmed in implementation) | `/` |

The fan-out query is `SELECT DISTINCT user_id FROM user_dashboard_widgets WHERE widget_id=?`, executed
in the listener, chunked by 200 users. RU/PL texts are in "Localization".

## Frontend

Feature folder `foremen-frontend/src/features/dashboard/`:

```
api/            dashboard-api.ts, query-hooks.ts, mutation-hooks.ts
types/          dashboard.ts (WidgetConfig, WidgetResult, PlacementView, LibraryItem)
lib/            grid.ts (mirror of GridMath), date-range.ts, format.ts, link-resolver.ts
state/          useDateFilter.ts (sessionStorage), useChartPalette.ts (theme-derived + custom palette)
lib/color.ts    palette derivation, hex validation, ensureContrast
style/          style-catalog.ts (mirror of WidgetStyleCatalog)
components/
  DashboardGrid.tsx, WidgetTile.tsx, EmptyCellTile.tsx, DisabledWidgetTile.tsx
  DateFilterBar.tsx, QuickRangeSelect.tsx
  WidgetPicker.tsx, ProjectPicker.tsx, RemoveWidgetDialog.tsx
  renderers/ TableRenderer, ListRenderer, BarRenderer, LineRenderer, AreaRenderer,
             PieRenderer, KpiRenderer, ProjectCardRenderer, RendererRegistry.ts
pages/          (DashboardPage in app/pages re-exports)
features/dashboard-widgets/   (admin)
  pages/ DashboardWidgetsPage.tsx, DashboardWidgetFormPage.tsx
  components/ WidgetTable, WidgetBuilder (DatasetPicker, FieldPicker, FilterRows, MeasureRows,
              DimensionRows, LinkRows, PresentationForm), WidgetPreview, AiAssistantPanel,
              RolesVisibilityBadge
```

- **Charts**: add `recharts` pinned to an exact version (no chart library exists today; this is the
  only new dependency; flagged for approval at implementation start). Renderers lazy-loaded per
  visualization to keep the main bundle small.
- **Grid**: CSS Grid with `grid-template-columns: repeat(4|3|1, 1fr)`, `grid-auto-rows: <row height>`,
  tiles placed with `gridColumn: span min(cs, cols)` / `gridRow: span rs` in cell-index order using
  `grid-auto-flow: dense` disabled (sparse flow) on desktop where explicit `gridColumn/gridRow`
  start positions come from `cell_index`; on tablet/mobile start positions are omitted so items
  auto-flow in order. Breakpoint via the app's existing media/breakpoint hook.
- **"+" tile** *(desktop superseded by Amendment A1: one Add_Slot per Free_Cell; tablet/mobile unchanged)*: `grid.ts#firstFreeCell` (same algorithm as the backend `firstFit` with 1x1) computes the
  cell on desktop; on tablet/mobile the "+" is rendered after the last tile.
- **Date filter**: `date-fns` for boundaries (`startOfWeek({weekStartsOn:1})`, etc.). Quick select
  computes `[from,to]`: unit × offset (`0` current, `-1` previous, `-2` before previous). Stored in
  `sessionStorage['foremen.dashboard.dateFilter']` as `{from,to}`; invalid stored values fall back to
  the current month. Uses the existing `DatePicker`.
- **Data fetching**: `useQuery(['dashboard','data',itemId,from,to])` per tile with
  `staleTime` 60 s; layout `useQuery(['dashboard','layout'])`. Mutations invalidate the layout.
- **Links**: `link-resolver.ts` substitutes `$row.*` / `$project` into the route template, checks the
  route against `route-permissions.ts` through `usePermission`, and returns either `{to}` or `null`
  (plain text).
- **Disabled tile**: greyed, warning icon, reason text by `state`, and a remove button.
- **Picker**: groups available first, then disabled (with warning + tooltip listing missing resource
  names resolved from the resources dictionary labels).
- **Admin**: list built on the existing data-table pattern used by document types; route
  `/dashboard-widgets` and `/dashboard-widgets/new|:id` registered in `router.tsx`,
  `route-permissions.ts` (`DASHBOARD_WIDGETS/READ`; create/edit require CREATE/UPDATE), and
  `NAV_CONFIG` System section (`nav.dashboardWidgets`, icon `layout-grid`).
- **TopBar title**: unchanged (`nav.dashboard`).

### Localization keys (PL / RU)

Added to `src/locales/ru.json` and `pl.json`. Key → (RU / PL):

- `nav.dashboardWidgets` → Виджеты панели / Widżety panelu
- `dashboard.title` → Панель / Panel
- `dashboard.empty` → На панели пока нет виджетов / Na panelu nie ma jeszcze widżetów
- `dashboard.add` → Добавить виджет / Dodaj widżet
- `dashboard.addTile.aria` → Добавить виджет в свободную ячейку / Dodaj widżet w wolnej komórce
- `dashboard.remove.action` → Удалить / Usuń
- `dashboard.remove.title` → Удалить виджет? / Usunąć widżet?
- `dashboard.remove.warning` → Виджет «{{name}}» исчезнет с вашей панели. Его можно добавить снова из библиотеки. / Widżet „{{name}}" zniknie z Twojego panelu. Możesz dodać go ponownie z biblioteki.
- `dashboard.remove.confirm` / `dashboard.remove.cancel` → Удалить / Отмена ; Usuń / Anuluj
- `dashboard.dateFilter.from|to` → С / От ; Od / Do
- `dashboard.dateFilter.period.current|previous|beforePrevious` → Текущий / Прошлый / Позапрошлый ; Bieżący / Poprzedni / Przedostatni
- `dashboard.dateFilter.unit.day|week|month|year` → День / Неделя / Месяц / Год ; Dzień / Tydzień / Miesiąc / Rok
- `dashboard.dateFilter.invalid` → Неверный период / Nieprawidłowy zakres dat
- `dashboard.dateFilter.tooLong` → Период не может превышать 5 лет / Zakres nie może przekraczać 5 lat
- `dashboard.picker.title|search|empty|noSpace` → Библиотека виджетов / Поиск / Ничего не найдено / Нет свободного места ; Biblioteka widżetów / Szukaj / Nic nie znaleziono / Brak wolnego miejsca
- `dashboard.picker.unavailable` → Нет доступа к данным: {{resources}} / Brak dostępu do danych: {{resources}}
- `dashboard.picker.selectProject` → Выберите проект / Wybierz projekt
- `dashboard.picker.projectActiveOnly` → Только активные / Tylko aktywne
- `dashboard.widget.loading|error|noData|retry` → Загрузка… / Не удалось загрузить данные / Нет данных / Повторить ; Ładowanie… / Nie udało się załadować danych / Brak danych / Ponów
- `dashboard.widget.notDateAware` → Не зависит от фильтра дат / Nie zależy od filtra dat
- `dashboard.widget.state.NO_ACCESS|INACTIVE|PROJECT_UNAVAILABLE` → Нет доступа к данным виджета / Виджет отключён администратором / Проект недоступен ; Brak dostępu do danych widżetu / Widżet wyłączony przez administratora / Projekt niedostępny
- `dashboard.widget.truncated` → Показаны не все данные / Wyświetlono niepełne dane
- `dashboard.widget.compare` → к предыдущему периоду / względem poprzedniego okresu
- `dashboard.errors.*` for each backend error code (cell.occupied, grid.full, widget.duplicate, forbidden, project.forbidden …) in both locales
- `admin.dashboardWidgets.title|new|edit|code|nameRu|namePl|descriptionRu|descriptionPl|dataset|visualization|colSpan|rowSpan|projectScope|active|systemSeed|visibleToRoles|save|delete|activate|deactivate|inUse|preview|previewAsAdmin|advancedJson|requiredResources`
- `dashboard.appearance.title|open|palette|paletteHint|addColor|removeColor|reset|useTheme|save|invalidColor|lowContrast` → Оформление панели / Настроить оформление / Палитра / До 8 цветов в формате #RRGGBB / Добавить цвет / Удалить цвет / Сбросить / Как в теме / Сохранить / Неверный цвет / Низкий контраст, цвет скорректирован ; Wygląd panelu / Dostosuj wygląd / Paleta / Do 8 kolorów w formacie #RRGGBB / Dodaj kolor / Usuń kolor / Resetuj / Jak w motywie / Zapisz / Nieprawidłowy kolor / Niski kontrast, kolor skorygowany
- `dashboard.style.label` → Стиль / Styl; `dashboard.style.default` → По умолчанию / Domyślny
- `dashboard.style.<VISUALIZATION>.<STYLE>` for every catalog entry (e.g. `TABLE.COMPACT` → Компактный / Kompaktowy, `TABLE.STRIPED` → С полосами / W paski, `BAR.OUTLINED` → Контурный / Konturowy, `KPI.CARD` → Карточка / Karta, `PROJECT_CARD.DETAILED` → Подробная / Szczegółowa) in both locales
- `admin.dashboardWidgets.builder.defaultStyle|defaultColors|previewAllStyles` → Стиль по умолчанию / Цвета по умолчанию / Показать все стили ; Domyślny styl / Domyślne kolory / Pokaż wszystkie style
- `admin.dashboardWidgets.builder.*` — dimensions, measures, aggregation names, filters, operators, sort, limit, links (element, route, params), presentation, empty text
- `admin.dashboardWidgets.ai.title|prompt|placeholder|generate|generating|disabled|failed|explanation|warnings|applyDraft`
- `notification.dashboard.ai.failed` → Не удалось создать черновик виджета с помощью ИИ / Nie udało się utworzyć szkicu widżetu za pomocą AI
- `notification.dashboard.widget.deactivated` → Виджет «{{name}}» отключён администратором и скрыт с вашей панели / Widżet „{{name}}" został wyłączony przez administratora i jest niedostępny na Twoim panelu
- Enum labels for visualization types, aggregations, filter operators, and widget states.

All seeded widget names/descriptions are stored RU/PL in the database (seed changeset).

## Seed configs (summary)

Each seeded widget's config follows the schema; examples:

- `ACTIVE_PROJECTS`: dataset `PROJECTS`, `PROJECT_CARD`, filter `status IN [ACTIVE]`, columns
  `name, managerName, address, area, status, startDate, endDate`, links: `CARD_TITLE` →
  `/projects/{projectId}`, chips Estimate / Gantt / Payments / Documents →
  `/projects/{projectId}/{tab}`; no `dateField`.
- `PROJECT_CARD` (SINGLE_PROJECT): same columns, same chips, `$project` binds `projectId`.
- `WORK_REPORTS_PENDING`: dataset `WORK_REPORTS`, `TABLE`, filter `status EQ SUBMITTED`, columns
  `project.name, workItem.name, worker.name, performedFrom`, row link → project work-reports tab,
  `dateField performedFrom`, sort `performedFrom DESC`, limit 20.
- `PAYMENTS_RECEIVED`: `KPI`, `SUM(amountGross)`, filter `status EQ CONFIRMED`, `dateField receivedAt`,
  `compareToPrevious true`, no link (company-level figure).
- `PAYROLL_TOTAL`: `KPI`, `SUM(amount)`, `dateField month`, link → `/company/payroll`.
- `BANK_FLOW`: `AREA`, dimension `operationDate` bucket `DAY`, one measure `SUM(amount)` (signed:
  inflow positive, outflow negative; `presentation.signedColors = true`), `dateField operationDate`,
  link → `/company/bank-transactions`. Per-measure filters are not supported in v1.
- `BANK_PENDING`: `KPI`, `COUNT(id)`, filter `status EQ PENDING`, no `dateField`, link → bank page.
- `USERS_BY_ROLE`: `DONUT`, dimension `role.code`, `COUNT(id)`, no date.

Full JSON of each is in the changeset and covered by the seed validation test.

## Security review notes

- No endpoint accepts a query, field list or SQL from non-admin users. Preview/AI take configs from
  admins only (`DASHBOARD_WIDGETS`) and run through the same validator.
- Dataset whitelist + Criteria binding prevents injection through field names or filter values.
- Ownership is checked before access, and 403 is returned for foreign placements.
- The AI prompt contains no business data. Draft output is untrusted and validated before use.
- `USERS` dataset omits PII fields.
- Config links are internal routes only, validated server-side and re-checked client-side against
  route permissions; the real authorization remains on the API.

## Correctness Properties

Index: properties 1-14 are listed in the next section; properties 15-23 are in "A1.6 Correctness
properties (additions)". Numbering is unchanged.

## Error Handling

Index: error codes and statuses are described in "Fetch pipeline" and "REST API"; Amendment A1
errors are in "A1.7 Error handling".

## Correctness properties (for property-based tests)

1. **Grid safety**: for any sequence of add/delete operations, no two placements of a user overlap and
   every footprint is within 4x9; total occupied cells ≤ 36.
2. **First-fit determinism**: `firstFit` returns the lowest-index cell (from `startAt` cyclically) whose
   footprint fits; if it returns none, no cell fits.
3. **Reflow stability**: tablet/mobile ordering equals ascending `cell_index`; clamping `colSpan` never
   increases it.
4. **Access monotonicity**: adding an operation grant to a role never turns an `AVAILABLE` widget
   into `NO_ACCESS`; removing all operations of a Required_Resource makes it `NO_ACCESS`.
5. **List/fetch agreement**: for any user and placement, `state != AVAILABLE` ⇔ data fetch returns 403
   `error.dashboard.widget.disabled`.
6. **Scope containment**: for any config and caller, every returned row's project (when
   project-scoped) ∈ caller's accessible projects; for Project_Widgets the project equals the
   placement's project.
7. **Owner restriction**: for a WORKER, `WORK_REPORTS` rows all have `worker.id = caller`.
8. **Config validation**: any config containing a field outside the dataset whitelist or a route
   outside the whitelist is rejected; every accepted config executes without a persistence error on a
   seeded test DB.
9. **Seed idempotency**: re-running the backfill does not change the placement set; a user with a
   state row and zero placements is never reseeded.
10. **Date range**: quick-select output always satisfies `from <= to` and calendar boundaries; the
    persisted session value round-trips.
11. **Palette precedence**: for any combination of present/absent custom palette, theme palette and
    config colors, the effective palette is the first present in that order; resetting the custom
    palette restores the theme-derived one.
12. **Style fallback**: the effective style is always a member of the visualization's catalog; an
    override outside the catalog never reaches the renderer.
13. **Contrast**: `ensureContrast` output always meets the 3:1 ratio against the given background
    and equals the input when the input already meets it.
14. **Appearance isolation**: one user's PUT never changes another user's appearance or any
    Placement position.

## Testing strategy

- Backend unit/property (jqwik, already used): `PaletteValidator`, `WidgetStyleCatalog`, `GridMath`, `WidgetConfigValidator`,
  `PermissionSet.hasAnyOperation`, access state machine, date bucket logic.
- Backend integration (Testcontainers): query engine per dataset (scoping, owner restriction, date
  range), placement concurrency, defaults seeding/backfill, deactivation fan-out, admin CRUD,
  seed-config validation, AI client with the stub and a fake transport (retry/invalid paths).
- Frontend (vitest + Testing Library): date range computation and session storage, grid layout at
  three breakpoints, picker disabled state, delete confirmation, link resolution with/without
  permission, renderers with fixture results.
- API/UI e2e per `test-cases.md` (Dockerized stack; reports as MD tables).
- Per the project test-execution rules, only affected test classes are run during implementation and
  results are read from `/tmp/*.log` and the JUnit XML.

## Open points resolved during implementation (tracked in tasks)

- Exact FK/column names of `users.role` and `payment` project path.
- Whether `UserService` is the single user-creation path (publish point for `UserCreatedEvent`).
- Whether the bell passes `body` as the interpolation value for `{{name}}`; adjust the emitter to the
  existing convention otherwise.
- Approval of the `recharts` dependency.

---

## Amendment A1: Layout UX (Requirements 19-22)

Adds drag-and-drop moving (with keyboard and tablet/mobile alternatives), an Add_Slot on every free
desktop cell, Fit_Size with a fit-aware picker, and picker thumbnails. Requirements 1-18 stay as
implemented. Superseded statements above carry a pointer to this section. The first-fit fallback is
already absent in `DashboardLayoutService.addPlacement` (explicit `cellIndex` gives 400/409); what
changes is the frontend, whose picker used `firstFit(..., cellIndex)` as its "fits" test (Req 21.9).

### A1.1 Decisions
| Topic | Decision | Rationale |
|---|---|---|
| Move API | `PUT /api/dashboard/items/{id}/move` body `{cellIndex}` → `PlacementView` | Idempotent verb, one resource |
| Swap API | `POST /api/dashboard/items/swap` body `{firstId, secondId}` → `{items: [PlacementView, PlacementView]}` | Single atomic call (Req 19.13); a batch move would let clients send partial states |
| Locking | `lockUser(userId)` (`pg_advisory_xact_lock`) first, then read the user's placements | Same as `addPlacement`; serializes concurrent move/swap/add (Req 19.18) |
| One decision function | `GridMath.classify(occupied, cell, cs, rs)` → `OK \| INVALID \| OCCUPIED`, used by add, move, swap, FE drop validation and FE picker filter | Req 21.11 holds by construction; shared vectors prove FE parity |
| DnD library | `@dnd-kit/core` pinned to an exact version (6.3.1, confirm at install; new dependency, flagged for approval like `recharts`). No dnd library exists in `package.json`; `@dnd-kit/sortable` and `utilities` are not used | Pointer/touch sensors, activator handle, Escape cancel and collision hooks are built in; a hand-rolled pointer-events implementation would re-implement capture, cancel and touch handling. Sortable presets do not fit free-cell grid drops, so only `core` is used |
| Keyboard move | Custom move mode, not dnd-kit's `KeyboardSensor` | Req 19.11 needs one-cell arrow steps with a validity announcement, which the sensor does not provide |
| Optimistic update | react-query `onMutate`/`onError` on the `dashboardKeys.layout()` cache | Req 19.4, 19.6 |
| Shared vectors | `foremen-backend/src/test/resources/dashboard/grid-vectors.json` (tracked in the root repo). JUnit loads it from the classpath. The vitest test locates it by walking up from `process.cwd()` (the mirror image of `RouteWhitelistContractTest`) and uses `describe.skipIf` when absent | The backend is the source of truth for geometry. The skip keeps a frontend-only checkout green |

### A1.2 Backend

**`GridMath` additions (pure)**
```java
public enum Placement { OK, INVALID, OCCUPIED }
public record Fit(int cols, int rows) { int area() { return cols * rows; } }
static boolean isFree(long occupied, int cell);                     // in range and bit not set
static List<Integer> freeCells(long occupied);                      // ascending
static Placement classify(long occupied, int cell, int cs, int rs); // !fits -> INVALID; overlap -> OCCUPIED
static List<Fit> maximalFits(long occupied, int cell);              // [] when cell is not free
static Optional<Fit> fitSize(long occupied, int cell);              // largest area, tie -> wider
static boolean fitsInAny(List<Fit> maximal, int cs, int rs);
static int lastOccupiedRow(Collection<Rect> placements);            // -1 when empty
static int visibleRows(Collection<Rect> placements);                // min(9, lastOccupiedRow + 2); empty -> 1
```
- `maximalFits(c)`: `w` runs `1..4-col(c)` while the cell `c+w-1` is free in `row(c)`. For each `w`,
  `h(w)` is the largest `h` in `1..9-row(c)` with every cell of the `w x h` rectangle free.
  `h(w)` is non-increasing in `w`; keep `(w, h(w))` where `h(w) > h(w+1)` (with `h(wMax+1) = 0`).
  The result is ordered by `w`. `fitSize` picks the max by `cols*rows`, ties by larger `cols`.
- `visibleRows`: rows `0..min(8, lastOccupiedRow+1)`, so the count is `min(9, lastOccupiedRow + 2)`,
  and `1` for an empty grid (`lastOccupiedRow = -1`).
- `classify` keeps the current rules: `!fits` ⇒ `INVALID` (covers a cell outside 0..35 and spans < 1),
  `footprint & occupied != 0` ⇒ `OCCUPIED`. `addPlacement` is refactored to call it; behavior and
  error codes are unchanged.

**`DashboardLayoutService`**
```java
@Transactional PlacementView movePlacement(Long userId, Long placementId, int cellIndex);
@Transactional List<PlacementView> swapPlacements(Long userId, Long firstId, Long secondId);
```
`movePlacement`:
1. `lockUser(userId)`.
2. `placementDao.findById(id)`; missing **or** owned by someone else ⇒ `403 error.dashboard.forbidden`
   (identical response; unlike `removePlacement`, which returns 404 for missing).
3. `cellIndex == p.cell_index` ⇒ return the view, no write (idempotent, Req 19.9).
4. `occupied = GridMath.occupied(all user placements except p)`; `classify(occupied, cell, cs, rs)`:
   `INVALID` ⇒ `400 error.dashboard.cell.invalid`, `OCCUPIED` ⇒ `409 error.dashboard.cell.occupied`.
5. `p.setCellIndex(cell)`; save; return `toView`. Widget id and project id are untouched.
- No `Has_Access` check and no widget-active check (Req 19.10), so `NO_ACCESS`, `INACTIVE` and
  `PROJECT_UNAVAILABLE` placements stay movable. `toView` already computes their state.

`swapPlacements(first, second)`:
1. `lockUser`; load both. Any missing or foreign ⇒ `403 error.dashboard.forbidden` (checked before
   geometry, so existence is not revealed). `firstId == secondId` ⇒ `400 error.dashboard.cell.invalid`.
2. `others = occupied(all placements except the two)`.
3. `a' = (second.cell, a.cs, a.rs)`, `b' = (first.cell, b.cs, b.rs)`. Each is classified against
   `others`; in addition `footprint(a') & footprint(b')` must be 0 (the two moved tiles must not
   overlap each other). Precedence: any `INVALID` ⇒ 400, else any overlap ⇒ 409.
4. Both `setCellIndex` calls and saves run in the same transaction; an exception rolls back both
   (either both or neither). Returns the two updated views, first then second.

**`DashboardController`** (self-service, no `@PermissionResource`, like the other `/api/dashboard` handlers)
```java
public record MoveItemRequest(Integer cellIndex) {}
public record SwapItemsRequest(Long firstId, Long secondId) {}
public record SwapResponse(List<PlacementView> items) {}
@PutMapping("/items/{id}/move")  // null cellIndex -> 400 error.dashboard.cell.invalid
@PostMapping("/items/swap")      // null ids -> 400 error.dashboard.cell.invalid
```
`PermissionAnnotationValidator` is unaffected because none of the three permission annotations is used.

**Library thumbnail shape.** `LibraryItem` gains a last component `ThumbnailShape thumbnail`:
```java
public record ThumbnailShape(String visualization, String style, int columns, int dimensions,
                             int measures, boolean temporal, boolean stacked, boolean compareToPrevious) {}
```
- Built by `ThumbnailShapeFactory.from(DashboardWidgetEntity)` from the stored config only:
  `visualization`; `style` = `DashboardAppearanceService.effectiveStyle(viz, presentation.style, null)`;
  `columns/dimensions/measures` = sizes of the respective arrays (0 when absent);
  `temporal` = any dimension has a `bucket`; `stacked`/`compareToPrevious` = `presentation` flags
  (`Boolean.TRUE.equals`). Malformed or missing config ⇒ zeros/false, with the visualization if known.
- `toItem` calls it for **every** entry, independent of the access evaluation, so the shape has the
  same structure and values for `available = true` and `false`. It contains no strings other than the
  two catalog values (`visualization`, `style`): no names, labels, filters, dataset code or `config`,
  and no dataset lookup is made (Req 8.8, 22.2).
- `PlacementView` is unchanged.

**Messages.** All error codes used by Requirements 19-21 already exist in `messages.properties` (PL)
and `messages_ru.properties` (RU): `error.dashboard.cell.invalid`, `.cell.occupied`, `.forbidden`. No new
backend codes. A test asserts the three keys exist in both bundles (Req 22.13).

**Impact on existing tests.** Backend: `DashboardLayoutServiceTest` and `GridMathPropertyTest` stay
valid (`firstFit` and `addPlacement` semantics are unchanged). New: move/swap service tests, extended
`GridMathPropertyTest`, `GridVectorsContractTest`, `ThumbnailShapeTest`, a move/swap integration test
with a concurrency case. Frontend: `WidgetPicker` tests that expect the `noSpace` warning from
`firstFit` are rewritten for the fit filter; `DashboardComponents.test.tsx` cases that look for the
single `dashboard-add-tile` on desktop are updated to `dashboard-add-slot-{cell}`.

### A1.3 Shared test vectors
`grid-vectors.json` (cells are plain index lists because JS bitwise operators are 32-bit and the grid has 36 cells):
```json
{
  "version": 1,
  "cases": [{
    "name": "1x3 and 2x1 fit at cell 2",
    "placements": [{ "cell": 0, "colSpan": 2, "rowSpan": 1 }, { "cell": 3, "colSpan": 1, "rowSpan": 6 }],
    "occupiedCells": [0, 1, 3, 7, 11, 15, 19, 23],
    "freeCells": ["..."],
    "lastOccupiedRow": 5, "visibleRows": 7,
    "queries": [{ "cell": 2, "maximalFits": [[1, 7], [2, 1]], "fitSize": [1, 7],
      "classify": [{ "colSpan": 2, "rowSpan": 1, "result": "OK" }, { "colSpan": 1, "rowSpan": 9, "result": "INVALID" }] }]
  }]
}
```
The snippet is illustrative. The real file is generated once from the Java reference, then reviewed
by hand. It covers: empty grid, full grid, single tile, holes inside a row, tall thin gaps, edge cells
(3, 32, 35), a cell outside 0..35, oversized spans, and the Req 21.1 example where a 2x1 widget fits
although the displayed Fit_Size is 1xN. `GridVectorsContractTest` (JUnit) and
`grid-vectors.contract.test.ts` (vitest) assert `occupiedCells`, `freeCells`, `lastOccupiedRow`,
`visibleRows`, `maximalFits`, `fitSize` and `classify` against the file.

### A1.4 Frontend

**Dependency.** Add `@dnd-kit/core` at an exact pinned version (no caret), flagged for approval.

**`lib/grid.ts` additions** (mirror of `GridMath`, same names): `isFree`, `freeCells(occupied)`,
`classifyPlacement(occupied, cell, cs, rs)`, `maximalFits(occupied, cell)`, `fitSize(occupied, cell)`,
`fitsInAny`, `lastOccupiedRow`, `visibleRows`, `occupiedExcluding(placements, ids)`,
`dropTarget(placements, movingId, cell)` → `{valid, footprint: number[]}` (footprint of the dragged
widget at `cell`, clipped to the grid for display), `canSwap(placements, a, b)` →
`{ok: true} | {ok: false, reason: 'invalid' | 'occupied'}` (Req 19.13 geometry, including the
mutual-overlap check), `neighbor(placements, id, 'earlier' | 'later')` over `orderByCell`,
`stepTarget(cell, key)` (arrow reducer: no wrap, stays at the edge), and `applyMove`/`applySwap`/
`restoreCells` (pure layout helpers for the optimistic cache). `firstFreeCell` stays for
tablet/mobile and the position-less picker.

**Components** (under `features/dashboard/components/`)
- `DashboardGrid` owns an `interaction` state: `{kind:'idle'} | {kind:'dragging', id, target} |
  {kind:'moving', id, target}`. On desktop it wraps the tiles in `DndContext` (`PointerSensor` with
  `activationConstraint: {distance: 6}`, a custom `collisionDetection` that returns the cell
  droppable under the pointer, `onDragCancel` for Escape). On `onDragEnd` it calls `onMove(id, cell)`
  only when `dropTarget(...).valid` and `cell !== current`; otherwise nothing is sent (Req 19.5).
- **Drop cells.** While `interaction !== idle`, 36 `DropCell`s (`useDroppable({id: 'cell-N'})`) are
  rendered as children of the same CSS grid with explicit `gridColumn/gridRow`, so row heights match
  the tiles. During the interaction the grid uses `gridTemplateRows: repeat(9, minmax(160px, auto))`
  so every cell exists. Free cells show a faint outline; the candidate footprint cells are painted
  valid or invalid from `dropTarget`, derived synchronously from the `target` state (well within
  100 ms). A small label shows the cell index and `colSpan×rowSpan`.
- **`TileActions`** (new; used by `WidgetTile` and `DisabledWidgetTile`, so `NO_ACCESS`/`INACTIVE`/
  `PROJECT_UNAVAILABLE` tiles stay movable). Desktop: a drag-handle button (`setActivatorNodeRef` +
  listeners, `aria-label` `dashboard.move.handle`) and a "Move" button (keyboard move mode). Tablet/
  mobile: "Move earlier" / "Move later" buttons, no drag listeners. Buttons use `min-h-11 min-w-11`
  (44 px) on tablet/mobile. Only the handle carries drag listeners, so a click on a linked widget
  element navigates and never starts a drag (Req 19.16).
- **Keyboard move mode.** "Move" sets `interaction = moving` with `target = current cell` and focuses
  a visually hidden focusable `div` (`role="application"`) that handles `ArrowLeft/Right/Up/Down` via
  `stepTarget`, `Enter` (confirm only if valid and different), and `Escape` (cancel, no call), then
  returns focus to the tile's Move button. A `role="status"` `aria-live="polite"` region renders
  `dashboard.move.announce` (row, column, valid / not possible), updated synchronously on each key.
- **Tablet/mobile swap.** `neighbor` + `canSwap` decide whether each button is disabled. A disabled
  button keeps `aria-disabled="true"` (not the `disabled` attribute, so it stays focusable), points to
  its reason with `aria-describedby`, and shows the same text as a tooltip. Reasons:
  `dashboard.move.noEarlier`, `dashboard.move.noLater`, `dashboard.move.swapInvalid`.
- **`AddSlot`** replaces `EmptyCellTile` on desktop (`EmptyCellTile` stays as the trailing "+" on
  tablet/mobile). For each cell in `freeCells(occupied)` with `rowOf(cell) < visibleRows`, render a
  dashed neutral button at its grid position, in ascending cell order,
  `data-testid="dashboard-add-slot-{cell}"`, with `aria-label` `dashboard.addSlot.aria` (row, column,
  displayed Fit_Size). It only opens the picker with that `cellIndex` and never fetches. If
  `visibleRows < 9`, a "show all free rows" button (`dashboard.addSlot.showAll`) occupies a full-width
  row after the last visible row; activating it sets `showAllRows = true` (local state, not persisted)
  and removes the button. When rows 0..8 have no free cell, no slot or control is rendered and the
  `dashboard.addSlot.gridFull` hint is shown. Slots and the control are hidden while
  `interaction !== idle` (Req 20.9).
- **Optimistic mutations** (`api/mutation-hooks.ts`): `useMoveDashboardItem` and `useSwapDashboardItems`,
  with `moveDashboardItem(id, cellIndex)` and `swapDashboardItems(firstId, secondId)` in `dashboard-api.ts`.
  ```ts
  onMutate: async (vars) => {
    await qc.cancelQueries({ queryKey: dashboardKeys.layout() })
    const prev = qc.getQueryData<DashboardResponse>(dashboardKeys.layout())
    qc.setQueryData(dashboardKeys.layout(), applyMove(prev, vars))   // pure helper in lib/grid.ts
    return { prevCells: cellsOf(prev, affectedIds) }                 // only the affected ids
  },
  onError: (_e, _v, ctx) => qc.setQueryData(layout, (cur) => restoreCells(cur, ctx.prevCells)),
  onSettled: () => qc.invalidateQueries({ queryKey: dashboardKeys.layout() }),
  ```
  Rollback restores only the affected placements, so no other placement changes. The error is shown
  with the existing `dashboardErrorMessage(error, t)` in a `role="alert"` banner above the grid.
  Per-item data queries are keyed by item id, so a move does not refetch widget data.
- **`DashboardPage`**: `pickerCell: number | null` replaces `freeCell`. The header "Add widget"
  button opens the picker with `null` (first-fit path, request without `cellIndex`), an Add_Slot with
  its cell, and the tablet/mobile trailing "+" with `null`.

**Picker (`WidgetPicker`)** gets `cellIndex?: number | null`, `placements`, and a `showAll` toggle
(`useState(false)`, reset on close).
```ts
fitOf(item) = cellIndex != null
  ? classifyPlacement(occupied, cellIndex, item.colSpan, item.rowSpan) === 'OK'
  : firstFit(occupied, item.colSpan, item.rowSpan, 0) !== null
listed(item) = matchesSearch(item) && (fitOf(item) || showAll)          // Req 21.12, logical AND
```
- Header (position set): `dashboard.picker.position` (1-based row and column) and
  `dashboard.picker.fitsUpTo` from `fitSize(occupied, cellIndex)`.
- Entries that do not fit appear only with the toggle on, disabled, with `dashboard.picker.doesNotFit`
  ("needs {{c}}×{{r}}, free {{cols}}×{{rows}}"). An unavailable widget keeps its access warning; if it
  also does not fit and the toggle is on, both lines are shown.
- If nothing is listed because of the fit filter: `dashboard.picker.noFit` with the Fit_Size and the
  toggle; the position is unchanged. `choose()` sends `cellIndex` whenever set and never picks another
  position.
- Each row shows a thumbnail (below).

**Thumbnail**
- Types: `ThumbnailShape` in `types/dashboard.ts`; `LibraryItem.thumbnail: ThumbnailShape`.
- Sample data: move `buildSampleResult` and its constants from `features/dashboard-widgets/lib/builder.ts`
  to `features/dashboard/lib/sample-data.ts`. It takes a structural `{fields: {name; type}[]}` instead
  of `DatasetCatalogEntry`. `builder.ts` re-exports it, so the builder and `StylePreview` imports keep
  working (`dashboard-widgets` already depends on `dashboard` for `WidgetRenderer`).
- `lib/thumbnail-sample.ts#sampleFromShape(shape, t)` builds a synthetic `WidgetConfig`
  (`dataset: ''`, fields `col1..`, `dim1..` with `bucket: 'MONTH'` when `temporal`, `m1..` with
  `agg: 'SUM'`, `presentation: {style, stacked, compareToPrevious}`, no links, no filters) and its
  `WidgetResult` via `buildSampleResult`. Labels use `dashboard.thumbnail.sample.category`
  ("Category {{letter}}"). Nothing from the user's data or dataset is used.
- `WidgetThumbnail({shape, colSpan, rowSpan, name, disabled})`: outer `div role="img"` with
  `aria-label` = `dashboard.thumbnail.aria` (name, visualization, style, e.g. "Bar chart, solid style")
  and `style={{aspectRatio: colSpan/rowSpan}}` inside a fixed max box (`max-w-[8rem] max-h-24`). Inside:
  `div aria-hidden="true" inert` with `pointer-events-none`, holding the renderer with no links, the
  `useChartPalette()` palette and the default style, wrapped in `ThumbnailErrorBoundary` whose
  fallback is the visualization icon. An unsupported `visualization` (not in `RendererRegistry`) or
  `style` (not in `stylesFor`) renders the icon without trying the renderer. Disabled entries use
  `opacity-60`. If the renderers cannot be silenced, `WidgetRenderer` gets an optional
  `interactive={false}` that disables the recharts tooltip and animation. React 19 supports the
  boolean `inert` prop.
- `LazyThumbnail`: an `IntersectionObserver` (`rootMargin: '100px'`) watches a same-size placeholder;
  the thumbnail mounts in `requestAnimationFrame` after the entry intersects and unmounts when it
  leaves the viewport (Req 22.8). Without `IntersectionObserver` (jsdom) it mounts immediately; tests
  inject a mock. The deferred mount lets the list text paint first (Req 22.9).

### A1.5 Localization (RU / PL)
New keys in `ru.json` and `pl.json`. The existing `dashboard.errors.cell.*` and `.forbidden` keys are reused.
- `dashboard.move.handle|action|earlier|later` → Перетащить виджет / Переместить / Переместить раньше / Переместить позже ; Przeciągnij widżet / Przenieś / Przenieś wcześniej / Przenieś później
- `dashboard.move.announce` → Строка {{row}}, столбец {{col}}: {{validity}} ; Wiersz {{row}}, kolumna {{col}}: {{validity}}
- `dashboard.move.valid|invalid` → можно / нельзя ; można / nie można
- `dashboard.move.hint` → Стрелки меняют ячейку, Enter подтверждает, Escape отменяет ; Strzałki zmieniają komórkę, Enter zatwierdza, Escape anuluje
- `dashboard.move.noEarlier|noLater|swapInvalid` → Это первый виджет / Это последний виджет / Виджеты не помещаются при обмене местами ; To pierwszy widżet / To ostatni widżet / Widżety nie mieszczą się po zamianie miejsc
- `dashboard.addSlot.aria` → Добавить виджет: строка {{row}}, столбец {{col}}, помещается до {{cols}}×{{rows}} ; Dodaj widżet: wiersz {{row}}, kolumna {{col}}, mieści się do {{cols}}×{{rows}}
- `dashboard.addSlot.showAll|gridFull` → Показать все свободные строки / Сетка заполнена ; Pokaż wszystkie wolne wiersze / Siatka jest pełna
- `dashboard.picker.position` → Строка {{row}}, столбец {{col}} ; Wiersz {{row}}, kolumna {{col}}
- `dashboard.picker.fitsUpTo` → Помещается до {{cols}}×{{rows}} ; Mieści się do {{cols}}×{{rows}}
- `dashboard.picker.showAll` → Показать все виджеты ; Pokaż wszystkie widżety
- `dashboard.picker.doesNotFit` → Не помещается: нужно {{c}}×{{r}}, свободно {{cols}}×{{rows}} ; Nie mieści się: potrzeba {{c}}×{{r}}, wolne {{cols}}×{{rows}}
- `dashboard.picker.noFit` → Здесь не помещается ни один виджет ; Żaden widżet nie mieści się w tym miejscu
- `dashboard.thumbnail.aria` → {{name}}: {{visualization}}, стиль «{{style}}» ; {{name}}: {{visualization}}, styl „{{style}}"
- `dashboard.thumbnail.sample.category` → Категория {{letter}} ; Kategoria {{letter}}

`dashboard.picker.noSpace` stays for the position-less path. A parity test checks that `ru.json` and
`pl.json` have the same `dashboard.*` key sets.

### A1.6 Correctness properties (additions)
*A property is a characteristic or behavior that should hold true across all valid executions of a system-essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

Reflection: "no overlap after any sequence", "move round trip" and "swap atomicity" are kept apart
because they fail differently. Drop-target validity is folded into Property 15. Numbering continues
from the 14 properties above.

### Property 15: Placement classification is consistent between backend and frontend

*For any* layout, any cell in -5..40 and any spans 0..5, `classify` returns `INVALID` iff the footprint
is outside the 4x9 grid, `OCCUPIED` iff it fits and intersects an existing footprint, and `OK`
otherwise. The frontend `classifyPlacement` returns the same value as the backend `GridMath.classify`,
and a drop target is valid iff `classifyPlacement` on the layout without the moved placement is `OK`.

**Validates: Requirements 19.3, 21.8, 21.10, 21.11**

### Property 16: Moves and swaps never create overlaps

*For any* valid layout and any sequence of move and swap requests (valid or not), the resulting
placements have no overlapping footprints and every footprint is inside the grid. A rejected request
leaves all placements unchanged, and a swap changes both placements or neither.

**Validates: Requirements 19.7, 19.8, 19.13, 19.14, 19.17**

### Property 17: Move round trip and idempotence

*For any* valid layout, placement and valid target cell, moving the placement to the target and then
back to its original cell restores the original layout exactly. Moving a placement to its current cell
does not change the layout and performs no write.

**Validates: Requirements 19.9, 19.17**

### Property 18: Moves change only the cell

*For any* layout and accepted move or swap, only `cell_index` of the affected placements changes. Widget
id, project id and every other placement stay the same, including placements whose access state is
`NO_ACCESS`, `INACTIVE` or `PROJECT_UNAVAILABLE`.

**Validates: Requirements 19.7, 19.10**

### Property 19: Keyboard targets stay in the grid

*For any* start cell and any sequence of arrow keys, `stepTarget` returns a cell in 0..35, moves by
exactly one column or one row per key, and never wraps past an edge.

**Validates: Requirements 19.11**

### Property 20: Free cells are disjoint from footprints and Add_Slots follow the visible rows

*For any* layout, `freeCells` is the ascending complement of the union of footprints in 0..35 and shares
no cell with any footprint. `visibleRows` is 1 for an empty layout and `min(9, lastOccupiedRow + 2)`
otherwise, and every Add_Slot is a free cell in a visible row.

**Validates: Requirements 20.1, 20.2, 20.5, 20.10**

### Property 21: Fit_Size and maximal fits are correct

*For any* layout and free cell, a widget size `(cs, rs)` has `classify == OK` iff it is contained in at
least one maximal fit. The displayed Fit_Size is the maximal fit with the largest area (ties: the wider
one), every maximal fit lies on free cells only, and no maximal fit can be extended by one column or row.

**Validates: Requirements 21.1, 21.2**

### Property 22: Picker filter matches backend acceptance

*For any* layout, chosen cell, search text, library and `showAll` flag, an entry is listed iff it matches
the search and (the backend add would accept its geometry at that cell, or `showAll` is on). Entries
that do not fit are never selectable.

**Validates: Requirements 21.3, 21.4, 21.11, 21.12**

### Property 23: Thumbnail shape exposes only the whitelisted structure

*For any* stored widget config and any caller, the serialized `ThumbnailShape` has exactly the keys
`visualization, style, columns, dimensions, measures, temporal, stacked, compareToPrevious`. Its string
values are catalog values, no field name, label, filter, dataset code or config value occurs in its
JSON, and the shape is identical for `available = true` and `available = false`.

**Validates: Requirements 22.1, 22.2**

### A1.7 Error handling
- Move/swap errors map to the existing codes: 400 `cell.invalid`, 409 `cell.occupied`, 403 `forbidden`
  (missing and foreign are indistinguishable). A failed mutation rolls back only the affected
  placements and shows the localized message. There is no retry loop.
- A network failure during a move behaves like an error response (rollback, generic message through
  `dashboardErrorMessage`).
- A stale layout (for example another tab) surfaces as 409 on the next move; `onSettled` refetches.
- Thumbnail failures are isolated per entry by the error boundary.

### A1.8 Testing strategy (additions)
- Backend: extend `GridMathPropertyTest` (jqwik, 100+ tries; properties 15, 20, 21);
  `DashboardLayoutServiceTest` move/swap cases and a sequence property (16, 17, 18) using the existing
  DAO fakes; `ThumbnailShapeTest` with a property for 23; `GridVectorsContractTest`; Testcontainers
  integration for move/swap (403 for foreign vs missing, advisory-lock race gives one success and one
  409, swap rollback).
- Frontend (vitest + fast-check, 100+ runs): `grid.test.ts` properties 15, 19, 20, 21, 22 against a
  brute-force reference; `grid-vectors.contract.test.ts`; component example tests for drag cancel with
  no backend call, optimistic update and rollback, keyboard mode with `aria-live`, tablet swap buttons
  with disabled reasons, Add_Slot rendering and the "show all free rows" control, the picker fit
  filter, and `WidgetThumbnail` (lazy mount with a mocked `IntersectionObserver`, fallback icon,
  `aria-label`, `inert`, no data endpoint call). Real pointer dragging is covered in the e2e suite.
- UI rendering and layout use example tests, not property tests.
- Per the project test-execution rules, run only the affected test classes with output in `/tmp/*.log`.

### A1.9 Open points
- Approval of `@dnd-kit/core` and its exact version.
- Whether `WidgetRenderer` already has a non-interactive mode; add `interactive={false}` if not.
- `tasks.md` and `test-cases.md` still need tasks and cases for Requirements 19-22 (outside this design update).
