# Requirements Document

## Introduction

This spec (**FOR-06-00-execution-foundation**) is the **foundation child** of the FOR-06
"project in progress" (execution stage) parent. It delivers the shared scaffolding every other
FOR-06 child builds on, without delivering any of their feature domains. It has three parts:

1. **Execution-stage workspace tab realignment (frontend).** Realign the already-declared
   execution-stage entries in the FOR-05-01 `WORKSPACE_TABS` contract so they match the new FOR-06
   child structure (work reporting, plan/actual Gantt, procurement, purchases, materials matrix,
   margin matrix, client payments, amendments, payroll, client communication). Each execution tab
   keeps pointing at the generic `PlaceholderTab` and is permission-gated on a resource; the real
   panel is swapped in later by the owning child spec **without** touching this contract. The
   stage-aware resolver (`resolveWorkspaceTabs`) and the `stageOf` mapping already exist in
   FOR-05-01 and are **reused unchanged** — this spec does not re-implement stage gating.

2. **Unplanned-expense category mini-dictionary (full-stack).** A new global managed dictionary
   entity `UnplannedExpenseCategory` (`code`, `nameRU`, `namePL`, `active`), with CRUD for ADMIN and
   READ for the rest of the admin staff (MANAGER, FOREMAN, FINANCIER), seeded with the five default
   categories (reclamation, unforeseen expense, materials, fuel, other). It is the dictionary the
   FOR-06-04 purchases-list child references when a purchase is left unlinked from the plan.

3. **Execution-stage ABAC scaffold note.** This spec seeds **only its own** resource
   (`UNPLANNED_EXPENSE_CATEGORIES`). Every per-feature execution resource (e.g. `WORK_REPORTS`,
   `AMENDMENTS`, `PAYROLL`, procurement/materials/payments resources) is seeded by the child spec
   that owns it. The tab realignment therefore references those resources by name but does **not**
   seed them; an execution tab whose resource is not yet seeded simply stays hidden until its owning
   child spec seeds the resource — exactly the existing behaviour for unseeded tab resources.

### Terminology

- **Execution stage** — a project whose `Project.status` is `ACTIVE` or `COMPLETED`, as mapped by
  the existing `stageOf` (FOR-05-01). The design→execution boundary is contract signing
  (`ProjectActivationSignal.onContractSigned`), owned by FOR-05-08; this spec does not change it.
- **Admin staff** — the non-client, non-worker office roles: MANAGER, FOREMAN, FINANCIER (plus
  ESTIMATOR where it exists). "READ for the rest of the admin staff" in this spec means MANAGER,
  FOREMAN, FINANCIER.
- **Execution tab** — a `WORKSPACE_TABS` entry with `stage: 'execution'`.

### Scope boundaries

- **In scope:**
  - Realigning the `WORKSPACE_TABS` execution-stage entries to the FOR-06 child set: correct `key`,
    `owner: 'FOR-06'`, `requiredPermission`, `labelKey`, each still pointing at `PlaceholderTab`.
  - The `workspace.tab.*` i18n keys for the realigned execution tabs (PL + RU), and the
    `workspace.placeholder` caption (already present) reused unchanged.
  - A new global managed entity `UnplannedExpenseCategory` with the full vertical slice: table +
    seed changeset, JPA entity, service, `@PermissionResource`-annotated controller, DTOs/mappers,
    ABAC resource seed + role grants, and the frontend admin CRUD page + nav entry + i18n (PL + RU).
  - Seeding the five default unplanned-expense categories.
- **Out of scope (Non-Goals):**
  - **The execution feature domains themselves** — work reporting, plan/actual Gantt, procurement
    OCR, purchases list, materials matrix, margin matrix, client payments, amendments + signing,
    payroll, client communication. Each is a later FOR-06 child; this spec only reserves their tab
    slots and (for 00's own dictionary) the one resource.
  - **Seeding any resource other than `UNPLANNED_EXPENSE_CATEGORIES`** — the per-feature execution
    resources are each owned and seeded by their child spec.
  - **Changing `stageOf`, `resolveWorkspaceTabs`, the `ProjectStatus` enum, or the DRAFT→ACTIVE
    activation** — all reused unchanged.
  - **Swapping any execution tab's `lazy` to a real panel** — each real panel is delivered by the
    owning child spec.

## Requirements

### Requirement 1 — Execution-stage tab contract realignment

**User Story:** As a developer building FOR-06 children, I want the execution-stage tab contract to
reflect the FOR-06 child structure, so each child can swap in its real panel by changing a single
`lazy` import without re-shaping the contract.

#### Acceptance Criteria

1. WHEN the `WORKSPACE_TABS` contract is read THEN it SHALL contain exactly one execution-stage
   entry per externally-visible FOR-06 execution surface: work reporting, plan/actual Gantt,
   procurement, purchases, materials matrix, margin matrix, client payments, amendments, payroll,
   and client communication.
2. WHERE an execution tab is declared THE SYSTEM SHALL set `stage: 'execution'`, `owner: 'FOR-06'`,
   a mandatory `requiredPermission` of the owning feature's resource at `READ`, a `labelKey` of the
   form `workspace.tab.<key>`, and `lazy: PlaceholderTab`.
3. WHEN `resolveWorkspaceTabs(project, hasPermission)` runs for an `ACTIVE` or `COMPLETED` project
   THEN it SHALL return the execution tabs (plus any `common` tab) intersected with the tabs the
   caller is permitted to see, in declaration order, using the **existing** resolver unchanged.
4. WHEN `resolveWorkspaceTabs` runs for a `DRAFT` (or any non-execution) project THEN it SHALL
   return no execution tab.
5. IF an execution tab's `requiredPermission.resource` is not yet seeded THEN that tab SHALL stay
   hidden for every user (the resolver's ABAC filter can never grant it), and the workspace SHALL
   render without error.
6. WHEN a user selects an execution tab whose owning child spec has not yet shipped its panel THEN
   the workspace SHALL render the generic `PlaceholderTab` keyed by the tab's `:tab` slug, showing
   the tab's localized label as the heading and the `workspace.placeholder` caption.
7. WHERE the realignment removes or renames a legacy execution placeholder tab (`procurement`
   on `DELIVERIES`, `documents` on `DOCUMENTS`, `planActual` on `WORK_REPORTS`, `payroll` on
   `PAYROLL`, `amendments` on `AMENDMENTS`, `priceHistory` on `PRICE_HISTORY`, owners
   FOR-05/07/10/11) THE SYSTEM SHALL reconcile every `WorkspaceTabKey` type member and the tab-key
   count comment so the type and the array stay consistent and the project still type-checks.

### Requirement 2 — Unplanned-expense category entity and persistence

**User Story:** As an administrator, I want a maintainable dictionary of unplanned-expense
categories, so purchases that do not map to the plan can be classified consistently.

#### Acceptance Criteria

1. THE SYSTEM SHALL define a managed entity `UnplannedExpenseCategory` extending `BaseEntity` with
   fields `code` (unique, not null), `nameRU` (`name_ru`, not null), `namePL` (`name_pl`, not null),
   and `active` (boolean, not null, default true).
2. THE SYSTEM SHALL create the `unplanned_expense_categories` table via a Liquibase changeset
   following the `name_ru`/`name_pl` + `code` UNIQUE + audit-column convention, idempotent via
   `NOT tableExists` + `onFail="MARK_RAN"`, registered last in `changelog.xml`.
3. THE SYSTEM SHALL seed exactly five default rows — `reclamation`, `unforeseen`, `materials`,
   `fuel`, `other` — each with PL + RU names, idempotent via a `SELECT COUNT(*)` precondition so
   re-runs insert nothing.
4. THE `code` field SHALL be unique and immutable after creation; `nameRU`, `namePL`, and `active`
   SHALL be editable by an authorized caller.

### Requirement 3 — Unplanned-expense category API and ABAC

**User Story:** As an administrator, I want full CRUD over the dictionary while other admin staff
can only read it, so the taxonomy is centrally governed but broadly consumable.

#### Acceptance Criteria

1. THE SYSTEM SHALL expose the dictionary through a REST controller at `/api/unplanned-expense-categories`
   implementing the generic `AdminController` CRUD contract.
2. THE controller SHALL be annotated `@PermissionResource("UNPLANNED_EXPENSE_CATEGORIES")`, and
   every in-scope handler SHALL carry its inherited `@PermissionOperation` so the application starts
   without a `PermissionAnnotationValidator` failure.
3. THE SYSTEM SHALL seed the `UNPLANNED_EXPENSE_CATEGORIES` resource row (`code`, `name_ru`,
   `name_pl`, `description_ru`, `description_pl`) idempotently.
4. THE SYSTEM SHALL grant ADMIN `CREATE, READ, UPDATE, DELETE` and grant MANAGER, FOREMAN, and
   FINANCIER `READ` only, each grant idempotent via the standard `role_resources` precondition.
5. THE SYSTEM SHALL omit WORKER and CLIENT from the grants (deny-by-default), documented by an XML
   comment, mirroring the delivery-categories precedent.
6. WHEN a non-ADMIN admin-staff caller (MANAGER/FOREMAN/FINANCIER) issues a write (CREATE/UPDATE/
   DELETE) THEN the request SHALL be rejected by the permission interceptor; a READ SHALL succeed.
7. THE dictionary SHALL be global (not project-scoped); the service SHALL be a plain `AdminService`
   and SHALL NOT implement `ProjectScopedService`.

### Requirement 4 — Unplanned-expense category admin UI

**User Story:** As an administrator, I want an admin page to manage unplanned-expense categories,
so I can add, edit, deactivate, and remove them.

#### Acceptance Criteria

1. THE SYSTEM SHALL provide a frontend admin page under its own feature folder, listing the
   categories with their localized name, code, and active state.
2. WHERE the caller holds `UNPLANNED_EXPENSE_CATEGORIES` write operations THE page SHALL expose
   create, edit, and delete actions; WHERE the caller holds only READ THE page SHALL render
   read-only.
3. THE page SHALL be reachable from a navigation entry gated by `UNPLANNED_EXPENSE_CATEGORIES`/`READ`
   consistent with the existing dictionary menu grouping.
4. THE SYSTEM SHALL localize every user-facing string (page title, search, column headers, create/
   edit/delete labels, form field labels and validation, success/error toasts, and the nav label)
   in both PL and RU, with the two locale files mirrored key-for-key.

### Requirement 5 — No new notifications

#### Acceptance Criteria

1. THE SYSTEM SHALL emit **no** user-facing notifications in this spec. It is pure foundation (a
   static tab contract realignment, an ABAC scaffold, and a static admin dictionary); none of its
   surface is a domain state change that notifies a project member. Notification-emitting behaviour
   (work-report submission/approval/rejection, amendment signing requests, client communication,
   payment events) belongs to the respective FOR-06 feature children, each of which will emit via
   the generic per-recipient notification service rather than re-implementing delivery.

### Requirement 6 — Internationalization completeness

#### Acceptance Criteria

1. THE backend entity SHALL carry i18n via `nameRU`/`namePL` (the only i18n fields in this spec);
   `code` and `active` are non-i18n.
2. THE frontend SHALL add every new key to **both** `src/locales/pl.json` and `src/locales/ru.json`
   with real PL and RU values (no English fallback), and the locale-parity test SHALL pass.
3. THE realigned `workspace.tab.*` keys SHALL have PL + RU values for every execution tab key the
   realignment introduces or renames.
