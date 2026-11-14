# Implementation Plan: FOR-11-02 — Client position approval

## Overview

This plan delivers the client agreement workflow of FOR-11 on top of FOR-11-00: approval **rounds**
that drive the SYSTEM-only edges `PLANNED -> PROPOSED -> ACCEPTED`, a **client-safe read model** with no
internal price field, six emit-only notifications, and one workspace tab that renders a **client view**
(decide, buy myself, own link, package choice without prices) or a **staff view** (send block, propose
analog, record a decision on behalf). The backend adds two tables, one resource seed, one orchestrating
service and two controllers; the frontend adds one feature folder and one tab.

**Reused, not re-implemented.** `PositionLifecycleService.transition(..., SYSTEM)`,
`PositionVariantService.replace(..., SYSTEM)`, `PositionPriceVisibility`, `PositionAudienceResolver`,
`ProcurementProjectAccess`, `NotificationService` and the `OfferNotificationEmitter` pattern, the
workspace tab contract, the bell deep-link handler, `DataTable`, `usePermission`, the PLN and date
formatters; the FOR-06-12 thread is reached by navigation only.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):
- **Root repo**: changesets 234/235, backend, messages, spec, `test-cases.md`. Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`): `features/position-approval/`, the tab entry, locale keys,
  frontend tests. Commit from inside `foremen-frontend/`.
A full implementation needs **two commits, one per repo**, made only when the user asks.

**Migrations.** Changesets `234-create-position-approval-tables.xml` and
`235-seed-position-approvals-resource.xml` (current tail is `232`; `233` of FOR-11-01 is on disk and
unregistered). If a sibling spec lands first, use the next free `NNN`. Both are idempotent and appended
**last** in `changelog.xml` by task 1.3 only.

**Test-run policy (backend).** Do NOT run the full Gradle suite (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected classes with `--tests`, redirect to
`/tmp/for-11-02-test.log`, read the log and the JUnit XML under `foremen-backend/build/test-results/test/`.
Use `getDiagnostics` for per-file checks and `./gradlew compileJava compileTestJava` (redirected) for
compile-only checks. Frontend: `vitest --run` on the affected files only.

**Shared-code change to keep in mind.** `PositionPriceVisibility` gains `PROPOSED` in the CLIENT set
(task 2.3); the FOR-11-00 tests that asserted "hidden at PROPOSED" are updated in the same task.

## Tasks

- [x] 1. Database
  - [x] 1.1 Create `234-create-position-approval-tables.xml`
    - Tables `position_approval_rounds` and `position_approval_options` with the columns of the design,
      `UNIQUE (position_id, round_no)`, the partial unique index `uq_par_open_per_position`
      (`WHERE status = 'OPEN' AND kind <> 'CLIENT_SUGGESTION'`), the `chosen_option_id` FK added after the
      options table, indexes on `position_id`, `status`, `round_id`; each object its own idempotent
      changeSet (`tableExists` / `columnExists` / `indexExists` / `foreignKeyConstraintExists`, `MARK_RAN`).
    - _Requirements: 2.1, 2.2, 2.5, 2.8_
  - [x] 1.2 Create `235-seed-position-approvals-resource.xml`
    - Resource `POSITION_APPROVALS` (RU/PL name and description) guarded by `SELECT COUNT(*) FROM resources
      WHERE code = 'POSITION_APPROVALS'`; grants ADMIN CRUDA, MANAGER CRUDA, BUYER R, CLIENT R+APPROVE by
      the 229 `NOT EXISTS` pattern for `role_resources` and `role_resource_operations` (operation
      `APPROVE` already exists from changeset 133).
    - _Requirements: 1.1, 1.2, 1.3, 1.4_
  - [x] 1.3 Register changesets 234 and 235 in `changelog.xml`
    - Append both includes **last**, 234 then 235.
    - _Requirements: 1.1, 2.8_
    - _Depends on: 1.1, 1.2_

- [x] 2. Pure rules (backend, package `com.foremen.service.procurement.approval`)
  - [x] 2.1 Enums, `ApprovalRoundStatusMachine`, `ApprovalStageGuard` with tests
    - `ApprovalRoundKind`, `ApprovalRoundStatus`, `DecidedSide`, `ApprovalOperation`; the status machine
      (exits per kind, terminal states) and the stage guard matrix (everything from `IN_PROFORMA` on is
      `STAGE_NOT_ALLOWED`). Unit tests for every pair plus a jqwik property (terminal states have no exit).
    - _Requirements: 2.3, 2.4, 14.4_
  - [x] 2.2 `ApprovalDeadlinePolicy`, `ClientStatusMapper`, `ClientMoneyCalculator` with tests
    - Deadline validation and derived overdue in `Europe/Warsaw` (boundary at midnight); the client status
      mapping of Requirement 4.3; per-line `HALF_UP` scale-2 money (unit net, VAT %, unit gross, line net,
      line gross). Unit tests and properties (gross >= net, line = qty x unit within one cent).
    - _Requirements: 4.3, 4.4, 6.4, 13.2, 14.1_
    - _Depends on: 2.1_
  - [x] 2.3 Extend `PositionPriceVisibility` for the CLIENT audience
    - Add `PROPOSED` to `CLIENT_VISIBLE` (AGREED only; PACKAGE and CLIENT_OWN still `NONE`); update the
      FOR-11-00 unit and property tests that asserted the old behavior; add cases for the new rule.
    - _Requirements: 3.2, 23.1, 23.4_
  - [x] 2.4 `ApprovalActionRules` with tests
    - `allowedActions` for the client (`ACCEPT`, `REJECT`, `CLIENT_OWN`, `SUGGEST_LINK`) and staff
      (`SEND`, `SEND_CHOICE`, `PROPOSE_ANALOG`, `WITHDRAW`, `CHANGE_DEADLINE`, `DECIDE_ON_BEHALF`,
      `RESOLVE_SUGGESTION`) from audience, role, position stage/path and open round; the stage guard is the
      single source of eligibility.
    - _Requirements: 4.7, 14.4_
    - _Depends on: 2.1_

- [x] 3. Domain and service (backend)
  - [x] 3.1 Entities and DAOs
    - `PositionApprovalRoundEntity`, `PositionApprovalOptionEntity` (field `internalUnitPurchasePrice`
      only on the option), `PositionApprovalRoundDao` (open round by position, rounds by position ids,
      `findByIdForUpdate` with `PESSIMISTIC_WRITE`), `PositionApprovalOptionDao`; enum mapping as `VARCHAR`.
    - _Requirements: 2.1, 2.2, 2.3, 2.7_
    - _Depends on: 2.1_
  - [x] 3.2 `PositionApprovalService`: send and send-choice
    - Implements `ProjectScopedService` (`getProjectIdPath() = "position.project.id"`); all-or-nothing
      `send` (eligibility, rounds, options, mirrored deadline, SYSTEM `PLANNED -> PROPOSED`), `sendChoice`
      for `PACKAGE` (2..5 distinct price-less options); publishes `PositionsSentForDecisionEvent`.
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 21.2_
    - _Depends on: 2.2, 2.3, 3.1, 6.1_
  - [x] 3.3 `PositionApprovalService`: client decisions (shared with on-behalf)
    - `accept` (round lock, stage guard, fixed-price check, stale check, `replace` for ANALOG and
      PACKAGE_CHOICE, SYSTEM `PROPOSED -> ACCEPTED`), `reject`, `suggest`, `declareClientOwn` (supersede,
      `DECLARED` round, SYSTEM transitions to `PLANNED`, audited `sourcingPath = CLIENT_OWN`), the `Decider`
      record, client-membership assertion; publishes `PositionDecisionEvent` / `PositionSuggestionEvent`.
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 8.1, 8.2, 8.3, 8.4, 10.1, 10.2, 10.5, 11.1, 11.2,
      11.3, 11.4, 11.5, 12.1, 12.2_
    - _Depends on: 3.2_
  - [x] 3.4 `PositionApprovalService`: analog, suggestion decline, withdraw, deadline
    - `proposeAnalog` (from `PROPOSED` with SUPERSEDED, from `ACCEPTED` via SYSTEM `ACCEPTED -> PROPOSED`,
      same-as-current check, `sourceSuggestionId` resolves the suggestion), `declineSuggestion`,
      `withdraw` (back to `PLANNED` or `ACCEPTED` by `previousStatus`), `changeDeadline`; publishes
      `PositionAnalogProposedEvent`.
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 10.3, 10.4, 13.1, 13.2, 13.3_
    - _Depends on: 3.3_

- [x] 4. Read side (backend)
  - [x] 4.1 `ApprovalQueryService` and narrowed `ClientPositionSource` projection
    - One query per project (positions with at least one round, rounds, options, materials, producers,
      units) without N+1; the client projection selects **only sale-side columns**; filters `status`,
      `onlyPending`, `blockNo`, `overdue`, `sourcingPath` applied in the query; summary counts.
    - _Requirements: 3.1, 5.1, 5.2, 5.4, 5.5, 14.2_
    - _Depends on: 2.2, 3.1_
  - [x] 4.2 Client DTOs, `ClientViewAssembler` and the leak guard
    - `ClientApprovalPositionDto`, `ClientItemDto`, `ClientRoundDto`, `ClientOptionDto`, `ClientMoneyDto`
      with no staff type inside; the assembler is the only builder; `ClientDtoLeakGuard` reflection test
      (deny-list tokens, no staff types, generics and nested records walked).
    - _Requirements: 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4, 4.6, 4.8_
    - _Depends on: 2.2, 2.3, 2.4, 4.1_
  - [x] 4.3 Staff DTOs and `StaffViewAssembler`
    - `StaffApprovalPositionDto`, `StaffRoundDto`, `StaffOptionDto` with internal purchase price, margin
      per unit and line, seller, dates, request reference, `onBehalf`; the assembler asserts audience
      `STAFF_FULL` (403 `error.positionApproval.staff.forbidden`).
    - _Requirements: 3.6, 4.5, 4.7_
    - _Depends on: 2.4, 4.1_

- [x] 5. Controllers (backend)
  - [x] 5.1 `PositionApprovalClientController`
    - `GET` list, summary, single; `POST` accept, reject, suggest, client-own; every handler has an
      explicit `@RequiresPermission(POSITION_APPROVALS, READ|APPROVE)`, no class-level
      `@PermissionResource`; request records with bean validation; the list endpoint always returns the
      Client_View.
    - _Requirements: 1.5, 5.1, 5.3, 5.4, 5.5, 7.1, 8.1, 10.1, 11.1_
    - _Depends on: 3.3, 4.2_
  - [x] 5.2 `PositionApprovalStaffController`
    - `GET` staff list and round history; `POST` send, send-choice, propose-analog, withdraw, deadline,
      suggestion decline, decide-on-behalf, client-own-on-behalf with the `MANAGER`/`ADMIN` role check;
      explicit `@RequiresPermission` on every handler.
    - _Requirements: 1.5, 4.5, 6.1, 6.7, 9.1, 10.3, 12.1, 13.1, 13.2, 14.2_
    - _Depends on: 3.4, 4.3_

- [x] 6. Notifications (backend)
  - [x] 6.1 Events and `PositionApprovalNotificationEmitter`
    - The four event records; the emitter with `@TransactionalEventListener(AFTER_COMMIT,
      fallbackExecution = true)`, recipient resolution from ACTIVE `ProjectMember` rows by role, buyer
      resolution for `clientOwn`, per-event de-duplication, actor and on-behalf exclusion, every call
      wrapped in try/catch; the six types and deep links of the design.
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5_

- [x] 7. Backend i18n and coverage
  - [x] 7.1 Message keys and the parity test
    - All `error.positionApproval.*` keys of the design in `messages.properties` (PL) and
      `messages_ru.properties` (RU); a test scanning the code constants for the prefix and asserting both
      bundles contain each key with a non-identical-to-key value.
    - _Requirements: 16.1, 16.3_
  - [x] 7.2 `DatasetCoverage` exclusion for `POSITION_APPROVALS`
    - Add the exclusion with the one-line justification; run `DatasetCoverageTest` and
      `SensitiveFieldGuardTest`.
    - _Requirements: 21.3_
    - _Depends on: 1.3_

- [x] 8. Backend integration tests (Testcontainers)
  - [x] 8.1 Service flows
    - Send (eligible and not), accept for PROPOSAL / ANALOG / PACKAGE_CHOICE, stale and priceChanged,
      reject, analog from `PROPOSED` and from `ACCEPTED`, suggestion then analog with `sourceSuggestionId`,
      client-own (path, stage, audit), on-behalf, withdraw, deadline change, one-open-round race,
      `IN_PROFORMA` rejection, changeset idempotency (run twice).
    - _Requirements: 2.4, 2.5, 6.1, 6.2, 7.1, 7.2, 7.3, 7.4, 8.2, 9.2, 9.4, 11.2, 12.2, 13.1, 14.4, 22.2_
    - _Depends on: 3.4_
  - [x] 8.2 Controllers, ABAC and confidentiality
    - A CLIENT cannot call any staff write or the staff view and cannot reach `/api/purchase-positions`;
      BUYER is read-only; cross-project isolation; the **sentinel JSON test** on every client endpoint
      (purchase price, delivery cost, seller name, internal price never appear); unsent positions are
      invisible; client types contain no staff field.
    - _Requirements: 1.2, 1.4, 3.1, 3.5, 3.6, 4.5, 4.8, 5.3, 22.2_
    - _Depends on: 1.3, 5.1, 5.2_
  - [x] 8.3 Notification integration
    - Recipients and deep links for the six types, one `decisionRequested` per client per send operation,
      de-duplication, actor and on-behalf exclusion, buyer fallback for `clientOwn`, failure isolation.
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 22.2_
    - _Depends on: 3.4, 6.1_

- [x] 9. Frontend (inner repo `foremen-frontend/`)
  - [x] 9.1 Types, API client and hooks
    - `features/position-approval/types.ts` (client and staff types in separate files),
      `api/positionApprovalApi.ts` (only `/api/position-approvals/client/` for the client hooks),
      query keys and hooks with list and summary invalidation.
    - _Requirements: 17.4, 18.9_
  - [x] 9.2 Tab contract, panel and shared components
    - Add `positionApprovals` to `WORKSPACE_TABS` and `'FOR-11'` to the `owner` union;
      `PositionApprovalsPanel` branching on role code; `DeadlineBadge`, `RoundStatusChip`,
      `ClientStatusChip`, `MoneyBlock`, `useDeepLinkHighlight`; tab badge from the summary endpoint.
    - _Requirements: 17.1, 17.2, 17.3, 17.4, 17.5, 17.6_
    - _Depends on: 9.1_
  - [x] 9.3 Client view
    - `ClientApprovalsView`, `ClientPositionCard`, `ClientOptionCompare`, `PackageChoiceList`,
      `AcceptDialog` (posts the displayed price; handles `priceChanged` and `stale`), `RejectDialog`,
      `ClientOwnDialog`, `SuggestLinkDialog`, history block, Discuss button to `clientCommunication`;
      360 px layout; empty state.
    - _Requirements: 18.1, 18.2, 18.3, 18.4, 18.5, 18.6, 18.7, 18.8_
    - _Depends on: 9.2_
  - [x] 9.4 Staff view
    - `StaffApprovalsView`, `SendBlockDialog`, `ProposeAnalogDialog` (with client preview),
      `SendChoiceDialog`, `DecideOnBehalfDialog`, withdraw and deadline dialogs, `SuggestionsInbox`,
      `RoundHistoryDrawer`; buttons gated by `allowedActions`.
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.6, 19.7, 19.8_
    - _Depends on: 9.2_
  - [x] 9.5 Locale keys
    - Add every key of the design table to `pl.json` and `ru.json` (groups `positionApproval.*`,
      `workspace.tab.positionApprovals`, six `notification.position.*`); no hard-coded strings.
    - _Requirements: 15.6, 20.1, 20.2, 20.3_
  - [x] 9.6 Frontend tests
    - Client view renders no internal label even for a payload with extra fields, call allow-list, accept
      posts the displayed price, `priceChanged` handling, package choice shows no money, tab gating per
      role, deep-link highlight, staff dialogs and `allowedActions` gating, `i18n.keys.test.ts` parity.
    - _Requirements: 18.9, 20.1, 22.3_
    - _Depends on: 9.3, 9.4, 9.5_

- [x] 10. Checkpoint: backend and frontend verified
  - Run the affected backend classes with `--tests` (log to `/tmp/for-11-02-test.log`, read the JUnit
    XML) and the affected frontend tests with `vitest --run`. Ensure all pass, ask the user if questions
    arise.
  - _Requirements: 22.1, 22.2, 22.3, 23.1, 23.2, 23.3, 23.4_
  - _Depends on: 7.1, 7.2, 8.1, 8.2, 8.3, 9.6_

- [x] 11. Update the FOR-11 OVERVIEW row for child 02
  - Mark child 02 "Spec ready", record the decisions (client UI inside the workspace, rounds, internal
    field naming, sixth notification, `PositionPriceVisibility` change), and refresh the notification
    candidate table.
  - _Requirements: 15.1_

- [x] 12. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via generator and clean-up, regression
    group, MD report template). Part A: API scenarios against the Dockerized app; Part B: browser-engine
    scenarios for the client and staff views. Result artifacts are MD reports with tables. Written in
    Russian.
  - _Requirements: 22.4_
  - _Depends on: 10_

- [x] 13. Final checkpoint
  - Ensure the backend and frontend suites touched by this spec pass, `git status` in **both** repos
    shows nothing intended left behind, and ask the user if questions arise.
  - _Requirements: 22.1, 22.2, 22.3, 22.4, 23.1_
  - _Depends on: 11, 12_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "2.1", "2.3", "6.1", "7.1", "9.1", "9.5", "11"] },
    { "id": 1, "tasks": ["1.3", "2.2", "2.4", "3.1", "9.2"] },
    { "id": 2, "tasks": ["3.2", "4.1", "7.2", "9.3", "9.4"] },
    { "id": 3, "tasks": ["3.3", "4.2", "4.3", "9.6"] },
    { "id": 4, "tasks": ["3.4", "5.1"] },
    { "id": 5, "tasks": ["5.2", "8.1", "8.3"] },
    { "id": 6, "tasks": ["8.2"] },
    { "id": 7, "tasks": ["10"] },
    { "id": 8, "tasks": ["12"] },
    { "id": 9, "tasks": ["13"] }
  ]
}
```
