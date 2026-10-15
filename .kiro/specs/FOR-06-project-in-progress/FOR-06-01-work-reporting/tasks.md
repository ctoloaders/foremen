# Implementation Plan: FOR-06-01 — Work reporting

## Overview

This plan delivers work reporting as a full vertical slice: the backend `WorkReport` +
`WorkReportRoom` entities (schema → entities → service with the status machine + revenue valuation →
`@PermissionResource` controller → DTOs/mappers → ABAC seed), the notification emitter, and the
frontend Work Reporting panel (list + builder + review) wired into the already-reserved
`workReporting` execution tab. It introduces **two** new persisted entities and **one** new
project-scoped ABAC resource `WORK_REPORTS`, so it follows `.kiro/steering/entity-creation-rules.md`
in full (the entities ARE project-scoped, so step 4 — `ProjectScopedService` with `getProjectIdPath()`
— applies).

**Ownership boundary.** This spec owns the **revenue** side of a report only (`clientUnitPrice`,
`revenueValue`). The cost side (`workerUnitPrice`, `costValue`, `margin`) is FOR-06-10/11/06 and is
NOT implemented here (the read model may expose explicit nulls).

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — changesets `163`–`165` (+ optional `166` for media), the entities / service /
  controller / DTOs / mappers, the ABAC seed, the notification emitter, and this spec. Commit from
  the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the `work-reporting` feature folder, the `workReporting`
  tab `lazy` swap, the API client, and the `pl.json`/`ru.json` keys. Commit from inside
  `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Migrations.** New changesets begin at `163` (current changelog tail is `162`; if a sibling spec
lands further changesets first, use the next free `NNN`). Each follows the `NOT tableExists` /
`SELECT COUNT(*)` / `onFail="MARK_RAN"` idempotency convention and is appended **last** in
`changelog.xml`.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected test classes with `--tests`, redirect
to `/tmp/for-06-01-test.log`, and read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks.

## Parallel execution plan (waves)

- **Wave 0 (parallel):** 1.1, 1.2 — the two table changesets (independent files).
- **Wave 1 (parallel):** 2.1 (entities), 6.1 (frontend tab `lazy` swap scaffold) — independent of
  each other and of the DTO work.
- **Wave 2:** 2.2 (DTOs/mappers) — needs the entities.
- **Wave 3:** 3.1 (service + status machine + valuation) — needs entities + DTOs.
- **Wave 4 (parallel):** 3.2 (controller), 4.1 (ABAC seed) — the controller needs the service; the
  seed needs the tables; both independent of each other.
- **Wave 5:** 5.1 (notification emitter) — needs the service transitions + controller endpoints.
- **Wave 6:** 6.2 (frontend panel + builder + review + api) — needs the controller + seed live.
- **Wave 7 (parallel):** 7.1 (i18n), 7.2 (backend tests), 7.3 (frontend tests) — independent.
- **Wave 8:** 8 (Author test-cases.md) — needs the full slice.
- **Wave 9:** 9 (final verification + checkpoint) — depends on everything.

## Tasks

- [x] 1. Backend: table changesets
  - [x] 1.1 Create the `work_reports` table changeset
    - Add `163-create-work-reports.xml` (id BIGSERIAL PK; `project_id`, `work_item_id`, `author_id`,
      `worker_id` BIGINT NOT NULL FKs; `performed_from`/`performed_to` DATE NOT NULL; `status`
      VARCHAR(32) NOT NULL; `approved_by_id` BIGINT NULL FK; `approved_at` TIMESTAMPTZ NULL;
      `rejection_reason` TEXT NULL; `client_unit_price` NUMERIC(19,2) NOT NULL DEFAULT 0;
      `total_quantity` NUMERIC(19,3) NOT NULL DEFAULT 0; `revenue_value` NUMERIC(19,2) NOT NULL
      DEFAULT 0; `note` TEXT NULL; audit cols). Guard `NOT tableExists` + `MARK_RAN`; register last
      in `changelog.xml`.
    - _Requirements: 1.1, 1.3_
    - _Depends on: none_
  - [x] 1.2 Create the `work_report_rooms` table changeset
    - Add `164-create-work-report-rooms.xml` (id PK; `report_id` BIGINT NOT NULL FK `ON DELETE
      CASCADE`; `room_id` BIGINT NOT NULL FK; `quantity` NUMERIC(19,3) NOT NULL; `plan_quantity`
      NUMERIC(19,3) NULL; `input_mode` VARCHAR(16) NOT NULL; UNIQUE(`report_id`, `room_id`); audit
      cols). Guard `NOT tableExists` + `MARK_RAN`; register last in `changelog.xml`.
    - _Requirements: 1.2, 1.3, 1.6_
    - _Depends on: none_

- [x] 2. Backend: entities + DTOs
  - [x] 2.1 Add the JPA entities + enums
    - `WorkReportEntity extends BaseEntity` and `WorkReportRoomEntity extends BaseEntity` per design
      §3.1/§3.2 (cascade + orphanRemoval on rooms; unique `(report, room)`); enums
      `WorkReportStatus` (`SUBMITTED`/`APPROVED`/`REJECTED`/`CHANGES_REQUESTED`) and
      `WorkReportInputMode` (`ABSOLUTE`/`PERCENT`).
    - _Requirements: 1.1, 1.2, 4.1_
    - _Depends on: 1.1, 1.2_
  - [x] 2.2 Add the DTO + mapper classes
    - `WorkReport{ServiceModel, ServiceExtendedModel}`, the controller DTOs (`Dto, DtoExtended,
      CreateRequest, CreateResponse, UpdateRequest, UpdateResponse`), the `WorkReportRoom{Model}`
      sub-DTO, and the service + controller mappers. Read DTO carries `clientUnitPrice`,
      `totalQuantity`, `revenueValue`, localized work-item/room/worker names (ru→pl→code), status,
      media refs, and explicit null `costValue`/`margin` (forward compat).
    - _Requirements: 6.5_
    - _Depends on: 2.1_

- [x] 3. Backend: service (status machine + valuation + matching)
  - [x] 3.1 Add `WorkReportService`
    - `implements ProjectScopedService<...>`; `getProjectIdPath()` → `"project.id"`;
      `allowedProjectIds` → `ProjectAccessCache`. Validation hooks: work-item-on-estimate (R3.4),
      Covering_Work check via `project_member_work_categories` (R3.2), duplicate rooms (R2.4),
      quantity > 0 (R2.5), PERCENT-with-no-plan (R2.3), PERCENT→absolute resolution + `planQuantity`
      snapshot (R2.2/R2.6). Derivations: `totalQuantity`, `clientUnitPrice` (frozen
      `EstimateLine.unitPrice` + approved amendment deltas if FOR-06-08 present, else 0),
      `revenueValue` (R1.4/R1.5/R6). Status machine (`submit`/`approve`/`reject`/`requestChanges`/
      `resubmit`) with source-status + actor-role guards (R4). FOREMAN fast path → direct `APPROVED`,
      no notification (R5). `APPROVED` immutability (R6.4). WORKER row-level own-records scope on
      READ/CREATE/UPDATE/DELETE (R10.5: list filter `worker=caller`; create only self —
      `error.workReport.worker.notSelf`; update/delete only own — `error.workReport.access.notOwner`).
      MANAGER has no CREATE (RUD only); only FOREMAN/ADMIN run the review transitions.
    - _Requirements: 1.4, 1.5, 2.2, 2.3, 2.4, 2.5, 2.6, 3.2, 3.4, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 5.1, 5.2, 5.3, 5.4, 6.1, 6.2, 6.3, 6.4, 10.5_
    - _Depends on: 2.2_

- [x] 4. Backend: controller + ABAC seed
  - [x] 4.1 Seed the `WORK_REPORTS` resource + grants
    - Add `165-seed-work-reports-resource.xml`: resource row `WORK_REPORTS` (+ PL/RU
      name/description); ADMIN CRUD; FOREMAN CREATE+READ+UPDATE+DELETE (all workers); WORKER
      CREATE+READ+UPDATE+DELETE (row-scoped to own records in the service); MANAGER
      READ+UPDATE+DELETE (no CREATE); READ for the other project admin-staff roles
      (ESTIMATOR, FINANCIER); CLIENT omitted (commented, deny-by-default). Each changeSet idempotent
      via the standard preconditions; register last in `changelog.xml`.
    - _Requirements: 10.2, 10.4_
    - _Depends on: 1.1, 1.2_
  - [x] 4.2 Add `WorkReportController`
    - `@RestController @RequestMapping("/api/work-reports") @PermissionResource("WORK_REPORTS")`
      implementing the generic CRUD contract + review endpoints (`/approve`, `/reject`,
      `/request-changes`), each with a matching `@PermissionOperation`/`@RequiresPermission` (review
      actions → UPDATE). Verify the app starts (no `PermissionAnnotationValidator` failure).
    - _Requirements: 10.1, 10.3, 10.6_
    - _Depends on: 3.1_

- [x] 5. Backend: notifications
  - [x] 5.1 Add `WorkReportNotificationEmitter`
    - Consume the generic `Notification_Service` (FOR-05-07 R13). Emit
      `notification.workReport.submitted` to every ACTIVE FOREMAN on non-FOREMAN submit;
      `notification.workReport.approved` / `.rejected` (+reason) / `.changesRequested` (+reason) to
      the author on the respective transitions; emit nothing on the fast path. Deep-link
      `/projects/:projectId/workReporting?report=:id`.
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6_
    - _Depends on: 3.1, 4.2_

- [x] 6. Frontend: tab wiring + reporting panel
  - [x] 6.1 Swap the `workReporting` tab panel
    - In the workspace tab registry, change the `workReporting` entry's `lazy` from `PlaceholderTab`
      to the new `WorkReportingPanel`. Do not touch the `WORKSPACE_TABS` contract shape,
      `resolveWorkspaceTabs`, or `stageOf`.
    - _Requirements: 10.6_
    - _Depends on: none_
  - [x] 6.2 Build the `work-reporting` feature folder
    - `features/project-execution/work-reporting/` with `WorkReportingPanel.tsx` (filterable list;
      new-report action; approver actions on SUBMITTED/CHANGES_REQUESTED; WORKER sees own only),
      `components/WorkReportBuilder.tsx` (worker picker → R3-limited work items, per-room rows with
      absolute/percent toggle, date/period, media upload, note, localStorage draft with
      continue/discard — R7), `components/WorkReportReview.tsx` (approve/reject/request-changes +
      reason), `api/work-reports-api.ts`, `schemas/` (zod), `types/`, `__tests__/`.
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 8.1, 8.2, 8.3, 11.1, 11.2, 11.3, 11.4, 11.5_
    - _Depends on: 4.1, 4.2, 6.1_

- [x] 7. i18n + tests
  - [x] 7.1 Add i18n keys (PL + RU, mirrored) + backend messages
    - Add the `workReporting.*` feature block, status badges, builder/review labels, toasts, and the
      four `notification.workReport.*` titles to both `pl.json` and `ru.json` (design §5.1); add
      every `error.workReport.*` code to the backend PL + RU message bundle. Run the locale-parity
      test. (`workspace.tab.workReporting` already seeded by FOR-06-00 — reuse unchanged.)
    - _Requirements: 12.1, 12.2, 12.3, 12.4_
    - _Depends on: 3.1, 5.1, 6.2_
  - [x] 7.2 Backend unit/property tests
    - `WorkReportService`: derivations (P1/P2), status machine (P3), fast path (P4), work matching
      (P5), revenue freeze (P6); ABAC matrix property test (P7); seed idempotency (P10) via the
      Testcontainers migration run. Run only these classes with `--tests` → `/tmp/for-06-01-test.log`;
      verify via JUnit XML.
    - _Requirements: 1.4, 1.5, 2.2, 3.2, 4.6, 5.1, 6.4, 10.4, 10.5, 13.1_
    - _Depends on: 3.1, 4.1, 4.2, 5.1_
  - [x] 7.3 Frontend unit tests
    - Builder render + percent/absolute resolution (P2), draft persistence (R7), resolver fixtures
      (`workReporting` present on ACTIVE / absent on DRAFT, P8), the zod schema, the locale-parity
      test (P11).
    - _Requirements: 2.2, 7.1, 7.2, 10.1, 11.1, 12.2_
    - _Depends on: 6.2, 7.1_

- [x] 8. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown,
    regression group, MD report template). The backend reporting flow is API-tested against the
    Dockerized app (`http://localhost:8080`, `/api/work-reports` + review endpoints); the panel
    flow (create → submit → approve/reject/request-changes, worker own-reports, fast path) is a
    browser-engine scenario on an `ACTIVE` project. Result artifacts are MD reports with tables.
  - _Requirements: 2.2, 3.2, 4.1, 4.2, 4.3, 4.4, 5.1, 6.1, 9.1, 10.4, 11.3_
  - _Depends on: 4.2, 5.1, 6.2, 7.1, 7.2, 7.3_

- [x] 9. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava compileTestJava` → temp log) and run only the affected
    test classes; type-check + unit-test the frontend; confirm the locale-parity test passes and the
    app starts (no `PermissionAnnotationValidator` failure). Confirm the `workReporting` tab appears
    for a permitted caller on an `ACTIVE` project and the create → review cycle + fast path work
    end-to-end. Then stage and commit per repo (two commits) — only if the user asks.
  - _Requirements: all_
  - _Depends on: 1.1, 1.2, 2.1, 2.2, 3.1, 4.1, 4.2, 5.1, 6.1, 6.2, 7.1, 7.2, 7.3, 8_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["2.1", "6.1"] },
    { "id": 2, "tasks": ["2.2"] },
    { "id": 3, "tasks": ["3.1"] },
    { "id": 4, "tasks": ["4.1", "4.2"] },
    { "id": 5, "tasks": ["5.1"] },
    { "id": 6, "tasks": ["6.2"] },
    { "id": 7, "tasks": ["7.1", "7.2", "7.3"] },
    { "id": 8, "tasks": ["8"] },
    { "id": 9, "tasks": ["9"] }
  ]
}
```
