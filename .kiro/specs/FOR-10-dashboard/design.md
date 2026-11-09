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
- **"+" tile**: `grid.ts#firstFreeCell` (same algorithm as the backend `firstFit` with 1x1) computes the
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
