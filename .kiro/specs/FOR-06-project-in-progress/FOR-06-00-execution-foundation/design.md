# Design Document: FOR-06-00 — Execution foundation

## 1. Overview

FOR-06-00 is the foundation layer for the execution stage. It delivers three things and nothing
more:

1. **Realign the execution-stage `WORKSPACE_TABS` entries** to the FOR-06 child structure, reusing
   the FOR-05-01 resolver (`resolveWorkspaceTabs`) and `stageOf` unchanged.
2. **Add the `UnplannedExpenseCategory` global dictionary** — a standard FOR-04-style managed
   dictionary (entity → changeset → service → `@PermissionResource` controller → DTOs/mappers →
   ABAC seed + grants → frontend CRUD page + nav + i18n).
3. **Document the ABAC scaffold boundary**: 00 seeds only `UNPLANNED_EXPENSE_CATEGORIES`; every
   per-feature execution resource is seeded by its owning child spec.

This spec introduces **one** new managed entity (`UnplannedExpenseCategory`) and therefore follows
the `.kiro/steering/entity-creation-rules.md` checklist in full for that entity (it is global, so
step 4 — `ProjectScopedService` — is intentionally skipped). The tab realignment is a frontend-only
change to an existing contract.

### Spans two git repos

Per `.kiro/steering/git-repo-structure.md`:

- **Root repo** — the Liquibase changesets, the `UnplannedExpenseCategoryEntity` + service +
  controller + DTOs/mappers, the ABAC seed, and this spec. Committed from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the `WORKSPACE_TABS` realignment, the admin CRUD
  feature folder, the nav entry, and the `pl.json`/`ru.json` keys. Committed from inside
  `foremen-frontend/`.

A full implementation of 00 therefore needs **two commits — one per repo**.

## 2. Reused mechanisms (not re-implemented)

| Mechanism | Location | Reuse |
|-----------|----------|-------|
| `stageOf(status)` | `foremen-frontend/.../project-workspace/state/stageOf.ts` | Unchanged. `ACTIVE`/`COMPLETED` → `execution`; everything else → `design`. |
| `resolveWorkspaceTabs` | `.../project-workspace/state/resolveWorkspaceTabs.ts` | Unchanged. `inStageTabs(stage) ∩ permittedTabs(user)` in declaration order. |
| `PlaceholderTab` | `.../project-workspace/components/tabs/PlaceholderTab.tsx` | Unchanged. Generic "coming soon" panel keyed by `:tab`. |
| `WorkspaceTab` contract | `.../project-workspace/types.ts` | Shape unchanged; `WorkspaceTabKey` union + count comment are edited to match the realigned keys. |
| DRAFT→ACTIVE activation | `foremen-backend/.../service/document/ProjectActivationSignal.java` | Unchanged. Contract signing is the stage boundary (FOR-05-08). |
| `AdminController` / `AdminService` | `foremen-backend/.../controller/AdminController.java` | The dictionary implements the generic CRUD contract. |
| ABAC seed pattern | `foremen-backend/database_files/changesets/029-seed-delivery-categories-resource.xml` | Template for the resource row + per-role grants. |

## 3. Execution-stage tab realignment

### 3.1 Target execution tab set

The execution-stage entries of `WORKSPACE_TABS` become the following, in declaration (render) order.
Every entry is `stage: 'execution'`, `owner: 'FOR-06'`, `lazy: PlaceholderTab`. The
`requiredPermission` resource is the resource the owning child spec will seed; until seeded, the tab
stays hidden (R1.5).

| Order | `key` | `labelKey` | `requiredPermission` resource / op | Owning child | Resource seeded by |
|-------|-------|------------|-------------------------------------|--------------|--------------------|
| 1 | `workReporting` | `workspace.tab.workReporting` | `WORK_REPORTS` / READ | FOR-06-01 | FOR-06-01 |
| 2 | `planActual` | `workspace.tab.planActual` | `WORK_SCHEDULE` / READ | FOR-06-02 | FOR-05-10 (already seeded, changeset 153) |
| 3 | `procurement` | `workspace.tab.procurement` | `PURCHASES` / READ | FOR-06-03 | FOR-06-03 |
| 4 | `purchases` | `workspace.tab.purchases` | `PURCHASES` / READ | FOR-06-04 | FOR-06-04 |
| 5 | `materialsMatrix` | `workspace.tab.materialsMatrix` | `MATERIALS_MATRIX` / READ | FOR-06-05 | FOR-06-05 |
| 6 | `marginMatrix` | `workspace.tab.marginMatrix` | `MARGIN_MATRIX` / READ | FOR-06-06 | FOR-06-06 |
| 7 | `clientPayments` | `workspace.tab.clientPayments` | `CLIENT_PAYMENTS` / READ | FOR-06-07 | FOR-06-07 |
| 8 | `amendments` | `workspace.tab.amendments` | `AMENDMENTS` / READ | FOR-06-08/09 | FOR-06-08 |
| 9 | `payroll` | `workspace.tab.payroll` | `PAYROLL` / READ | FOR-06-11 | FOR-06-11 |
| 10 | `clientCommunication` | `workspace.tab.clientCommunication` | `CLIENT_MESSAGES` / READ | FOR-06-12 | FOR-06-12 |

Notes:
- `planActual` keeps its key but moves to `owner: 'FOR-06'` and is re-gated on the **already-seeded**
  `WORK_SCHEDULE` resource (changeset 153), so this is the one execution tab that can appear
  immediately once a project is `ACTIVE` and the panel ships (FOR-06-02). (The former
  `WORK_REPORTS`-gated `planActual` is split: reporting gets its own `workReporting` tab on
  `WORK_REPORTS`.)
- `procurement` and `purchases` intentionally share the `PURCHASES` resource (OCR upload vs. the
  reconciled list are two tabs over one procurement domain); FOR-06-03 seeds `PURCHASES`.
- Legacy entries removed/renamed: `documents` (FOR-10, `DOCUMENTS`), `priceHistory` (FOR-05,
  `PRICE_HISTORY`). `DOCUMENTS` primary-document handling folds into procurement (FOR-06-03/04);
  `priceHistory` is not a FOR-06 execution surface (price history is an offer/estimate concern).
- Resource names for not-yet-built children (`PURCHASES`, `MATERIALS_MATRIX`, `MARGIN_MATRIX`,
  `CLIENT_PAYMENTS`, `CLIENT_MESSAGES`) are **declared here only as the tab's `requiredPermission`
  string**. They are **not** seeded by 00. The owning child spec seeds its resource and may rename
  it; if it renames, it updates its own tab entry's `requiredPermission` in the same change.

### 3.2 `WorkspaceTabKey` reconciliation

`types.ts` currently lists 15 keys (`overview`, 8 design, 6 execution). The realignment:

- **Keeps** design keys unchanged.
- **Replaces** the execution keys `procurement, documents, planActual, payroll, amendments,
  priceHistory` with `workReporting, planActual, procurement, purchases, materialsMatrix,
  marginMatrix, clientPayments, amendments, payroll, clientCommunication`.
- Updates the key-count doc comment accordingly.
- Narrows `WorkspaceTab.owner` to the owners that actually remain (`'FOR-05' | 'FOR-06'`), since the
  FOR-07/FOR-10/FOR-11 owners no longer appear after the fold-in.

Because `resolveWorkspaceTabs` filters by `stage` and `requiredPermission` only (never by `key`),
changing the execution key set has no effect on the resolver and needs no resolver test changes
beyond the fixtures that assert the execution tab list.

### 3.3 Rendering

Each realigned tab points at `PlaceholderTab`, which already renders the localized `labelKey` as
its heading and `workspace.placeholder` as the caption, keyed by the active `:tab` slug (R1.6).
No new placeholder component is introduced.

## 4. UnplannedExpenseCategory dictionary (backend)

### 4.1 Entity

```java
@Entity
@Table(name = "unplanned_expense_categories")
@Getter @Setter @NoArgsConstructor
public class UnplannedExpenseCategoryEntity extends BaseEntity {
    @Column(nullable = false, unique = true)
    private String code;
    @Column(name = "name_ru", nullable = false)
    private String nameRU;
    @Column(name = "name_pl", nullable = false)
    private String namePL;
    @Column(nullable = false)
    private boolean active = true;
}
```

Identical in shape to `DeliveryCategoryEntity` (the canonical global i18n dictionary). `BaseEntity`
supplies id + audit columns.

### 4.2 Service, controller, DTOs

- `UnplannedExpenseCategoryService` — a plain `@Service implements AdminService<...>` (NOT
  `ProjectScopedService`), mirroring `DeliveryCategoryService`.
- `UnplannedExpenseCategoryController` — `@RestController
  @RequestMapping("/api/unplanned-expense-categories") @PermissionResource("UNPLANNED_EXPENSE_CATEGORIES")`
  implementing the generic `AdminController`; overrides only `getMapper()/getService()/
  getAuditServiceMapper()`. CRUD endpoints + their `@PermissionOperation` come from the inherited
  defaults.
- DTO/mapper classes per convention: `UnplannedExpenseCategory{ServiceModel, ServiceExtendedModel}`,
  `UnplannedExpenseCategory{Dto, DtoExtended, CreateRequest, CreateResponse, UpdateRequest,
  UpdateResponse}Model`, plus the service and controller mappers.

### 4.3 Changesets

Current changelog tail is `156`; new changesets begin at **157** (if a sibling lands first, use the
next free `NNN`), registered **last** in `changelog.xml`.

| # | File | Purpose |
|---|------|---------|
| 157 | `157-create-unplanned-expense-categories.xml` | `createTable unplanned_expense_categories` (id BIGSERIAL PK, code VARCHAR(100) UNIQUE, name_ru/name_pl VARCHAR(255) NOT NULL, active BOOLEAN DEFAULT true, audit cols). Guard: `NOT tableExists` + `MARK_RAN`. |
| 158 | `158-seed-unplanned-expense-categories-resource.xml` | ChangeSet A: insert `UNPLANNED_EXPENSE_CATEGORIES` resource row (guard: `SELECT COUNT(*) FROM resources WHERE code=... = 0`). ChangeSet B: ADMIN CRUD grant. ChangeSets C–E: MANAGER/FOREMAN/FINANCIER READ grants. ChangeSet F: seed five default rows (guard: `SELECT COUNT(*) FROM unplanned_expense_categories = 0`). WORKER/CLIENT omitted with an explanatory comment. |

Default seed rows:

| `code` | `name_pl` | `name_ru` |
|--------|-----------|-----------|
| `reclamation` | `Reklamacja` | `Рекламация` |
| `unforeseen` | `Wydatek nieprzewidziany` | `Непредвиденный расход` |
| `materials` | `Materiały` | `Материалы` |
| `fuel` | `Paliwo` | `Топливо` |
| `other` | `Inne` | `Иное` |

### 4.4 ABAC matrix

| Role | Grant |
|------|-------|
| ADMIN | CREATE, READ, UPDATE, DELETE |
| MANAGER | READ |
| FOREMAN | READ |
| FINANCIER | READ |
| WORKER | — (deny-by-default) |
| CLIENT | — (deny-by-default) |

`ForemenPermissionEvaluator` bypasses the matrix for the literal `ADMIN` role, so the ADMIN grant is
a completeness measure but MUST still be seeded (per the entity-creation checklist).

## 5. UnplannedExpenseCategory dictionary (frontend)

Feature folder `foremen-frontend/src/features/unplanned-expense-categories/`, mirroring
`features/delivery-categories/`:

- `UnplannedExpenseCategoriesPage.tsx` — list + create/edit `FormSheet` + delete `Dialog`, toasts
  via `sonner`, labels via `t('unplannedExpenseCategories.*')`.
- `api/unplanned-expense-categories-api.ts` — fetch/fetchOne/create/update/delete against
  `/api/unplanned-expense-categories` via the shared `apiRequest` + `buildFetchQuery`.
- `components/` — `UnplannedExpenseCategoriesList`, `UnplannedExpenseCategoryFormSheet`,
  `DeleteUnplannedExpenseCategoryDialog`.
- `schemas/` — zod form schema (code required + immutable on edit; nameRU/namePL required; active
  boolean).
- `types/` — the DTO types.
- `__tests__/` — page + schema tests.

Routing + nav: register the page route and add a nav entry gated by `UNPLANNED_EXPENSE_CATEGORIES`/
`READ`, placed in the existing dictionaries menu group (consistent with FOR-04-15 grouping if
present, else alongside the delivery-categories entry).

### 5.1 i18n keys (PL + RU, mirrored)

- Nav label: `nav.unplannedExpenseCategories`.
- Feature block `unplannedExpenseCategories`: `pageTitle`, `search.placeholder`, column headers
  (`column.name`, `column.code`, `column.active`), actions (`action.create`, `action.edit`,
  `action.delete`), form (`form.code`, `form.nameRu`, `form.namePl`, `form.active`,
  `form.required`), and `toast.{created,updated,deleted,error}`.
- Workspace execution tab labels: `workspace.tab.workReporting`, `workspace.tab.procurement`,
  `workspace.tab.purchases`, `workspace.tab.materialsMatrix`, `workspace.tab.marginMatrix`,
  `workspace.tab.clientPayments`, `workspace.tab.clientCommunication` (new); `workspace.tab.planActual`,
  `workspace.tab.amendments`, `workspace.tab.payroll` (reuse/keep). Removed keys for
  `workspace.tab.documents` and `workspace.tab.priceHistory` are deleted from both locale files.

PL / RU values:

| key | PL | RU |
|-----|----|----|
| `nav.unplannedExpenseCategories` | `Kategorie wydatków nieplanowanych` | `Категории неплановых расходов` |
| `unplannedExpenseCategories.pageTitle` | `Kategorie wydatków nieplanowanych` | `Категории неплановых расходов` |
| `workspace.tab.workReporting` | `Raporty pracy` | `Отчётность` |
| `workspace.tab.planActual` | `Plan/wykonanie` | `План/факт` |
| `workspace.tab.procurement` | `Zakupy` | `Закупки` |
| `workspace.tab.purchases` | `Lista zakupów` | `Список закупок` |
| `workspace.tab.materialsMatrix` | `Materiały` | `Материалы` |
| `workspace.tab.marginMatrix` | `Marża` | `Маржа` |
| `workspace.tab.clientPayments` | `Płatności` | `Оплаты` |
| `workspace.tab.amendments` | `Aneksy` | `Анексы` |
| `workspace.tab.payroll` | `Rozliczenie wynagrodzeń` | `Расчёт ЗП` |
| `workspace.tab.clientCommunication` | `Komunikacja` | `Коммуникация` |

(The remaining feature-block strings get PL/RU values during implementation, mirroring the
delivery-categories block.)

## 6. Correctness properties

- **P1 (stage totality).** For any `Project.status`, `resolveWorkspaceTabs` returns only
  execution tabs when `stageOf(status)` is `execution`, and never an execution tab otherwise.
  (Reuses the existing `stageOf`/resolver; validated by fixtures over the realigned set.)
- **P2 (declaration-order subsequence).** The resolver's output is a subsequence of `WORKSPACE_TABS`
  in declaration order, for both stages.
- **P3 (unseeded ⇒ hidden).** A tab whose `requiredPermission.resource` is unseeded is absent from
  the resolver output for every permission predicate (no grant can exist for an unseeded resource).
- **P4 (ABAC matrix completeness).** For `UNPLANNED_EXPENSE_CATEGORIES`: ADMIN resolves CRUD;
  MANAGER/FOREMAN/FINANCIER resolve READ only; WORKER/CLIENT resolve nothing.
- **P5 (seed idempotency).** Re-running changesets 157/158 against an already-seeded database
  inserts no duplicate table, resource, grant, or default row.
- **P6 (code immutability).** An update that changes `code` is rejected by the form schema /
  service; `nameRU`/`namePL`/`active` updates succeed.
- **P7 (locale parity).** `pl.json` and `ru.json` have identical key sets after the change
  (locale-parity test passes), including the added/removed `workspace.tab.*` keys.
- **P8 (startup validation).** The annotated controller passes `PermissionAnnotationValidator`
  (every in-scope handler has a matching `@PermissionOperation`), so the application starts.

## 7. Testing strategy

- **Backend unit/property.** `UnplannedExpenseCategoryService` CRUD + code-immutability; an ABAC
  matrix property test (P4) analogous to the existing permission-matrix tests; a seed-idempotency
  check (P5) exercised by the Testcontainers migration run.
- **Frontend unit.** Resolver fixtures over the realigned execution set (P1/P2/P3); the admin page
  render + create/edit/delete happy paths; the zod schema (P6); the locale-parity test (P7).
- **Per `.kiro/steering/test-execution-rules.md`:** run only the affected test classes with
  `--tests`, redirect to `/tmp/for-06-00-test.log`, and verify via the JUnit result XML. Do not run
  the full suite.
- **API-level test-cases.md** scenarios run against the Dockerized app (see `test-cases.md`).
