# Implementation Plan: FOR-06-08 — Amendments (aneks authoring)

## Overview

This plan delivers amendment authoring as a full vertical slice: two backend entities
(`EstimateAmendment`, `AmendmentLine`) with their schema → entities/enums → a **pure merge
collaborator** (`AmendmentMergeResolver`, jqwik property-tested) → the `AmendmentService`
(`ProjectScopedService`) → the `@PermissionResource("AMENDMENTS")` controller → DTOs/mappers → the
`AMENDMENTS` ABAC seed → the one gated production touch-point
(`WorkReportService.resolveClientUnitPrice`) → the frontend Aneksy panel (list + wizard + merge
chooser + delete dialogs) wired into the already-reserved `amendments` execution tab.

**Overlay, never mutate.** No task writes to the estimate, schedule, or any work report. The only
production code changed outside the new module is `resolveClientUnitPrice`, gated to contribute zero
until an amendment is `EFFECTIVE` (impossible without FOR-06-09) — so the build is behaviour-preserving.

**Pure-collaborator-first.** The subject-identity + merge arithmetic lives in the persistence-free
`AmendmentMergeResolver` so the merge rules (Requirement 5) are property-testable in isolation; the
service is a thin apply-layer.

**Reused, not re-implemented.** `ProjectScopedService` + ABAC (FOR-03), the estimate/schedule read
models (FOR-05-05/10/10b), and the `AdminController` CRUD scaffold are consumed as-is.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — changesets `177`–`179`, the entities / enums / pure resolver / service / controller /
  DTOs / mappers, the `AMENDMENTS` ABAC seed, the `resolveClientUnitPrice` seam change, the backend
  PL+RU message bundle, and this spec. Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the `features/project-amendments/` module, the `amendments`
  tab `lazy` swap, the api client/hooks, and the `pl.json`/`ru.json` keys. Commit from inside
  `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Migrations.** New changesets begin at `177` (current changelog tail is `176`; if a sibling spec lands
further changesets first, use the next free `NNN`). Each follows the `NOT tableExists` /
`SELECT COUNT(*)` / `onFail="MARK_RAN"` idempotency convention and is appended **last** in
`changelog.xml`. The `179-seed-amendments-resource` changeset follows the
`165-seed-work-reports-resource.xml` two-changeSet resource+grant pattern.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected test classes with `--tests`, redirect to
`/tmp/for-06-08-test.log`, and read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks.

## Parallel execution plan (waves)

- **Wave 0 (parallel):** 1.1, 1.2, 2.1, 3.1 — the two table changesets are distinct files; the
  enum pair and the pure `AmendmentMergeResolver` are persistence-free; all mutually independent.
- **Wave 1 (parallel):** 3.2 — the `AmendmentMergeResolver` property test (needs only 3.1).
- **Wave 2:** 4.1 — JPA entities (need the two table changesets 1.1/1.2 and the enums 2.1).
- **Wave 3:** 4.2 — DTOs + mappers (need the entities).
- **Wave 4:** 5.1 — `AmendmentService` (needs entities + DTOs + the merge resolver).
- **Wave 5 (parallel):** 6.1 (controller), 7.1 (the `resolveClientUnitPrice` seam + DAO sum query) —
  distinct files; both depend on the service/entities but not on each other.
- **Wave 6 (parallel):** 8.1 (ABAC seed), 5.2 (service tests), 7.2 (seam test) — independent of each
  other (seed is a changeset; the two test classes exercise already-built code).
- **Wave 7 (parallel):** 9.1 (frontend panel + tab swap scaffold), 8.2 (ABAC matrix property test) —
  independent (frontend module vs backend test).
- **Wave 8:** 9.2 — the wizard + merge chooser + delete dialogs + api wiring (needs the panel scaffold
  and the controller endpoints).
- **Wave 9:** 10.1 — i18n keys (PL+RU) + locale-parity (needs the frontend module and the seed labels).
- **Wave 10:** 11 — Author test-cases.md (depends on the full feature).
- **Wave 11:** 12 — Final verification + checkpoint (depends on everything).

## Tasks

- [x] 1. Backend: schema
  - [x] 1.1 Create the `estimate_amendments` table changeset
    - Add `177-create-estimate-amendments.xml` (id BIGSERIAL PK; `project_id` FK NOT NULL; `seq_no` INT
      NOT NULL; `status` VARCHAR(32) NOT NULL DEFAULT 'OPEN'; `effective_date` DATE NULL;
      `signable_document_id` BIGINT NULL FK; `note` VARCHAR(1024) NULL; audit cols). Add
      `UNIQUE (project_id, seq_no)` and the **partial unique index**
      `ux_amendment_open_per_project ON estimate_amendments(project_id) WHERE status='OPEN'`. Guard
      `NOT tableExists` + `MARK_RAN`; register last in `changelog.xml`.
    - _Requirements: 2.1, 7.4_
    - _Depends on: none_
  - [x] 1.2 Create the `amendment_lines` table changeset
    - Add `178-create-amendment-lines.xml` (id PK; `amendment_id` FK NOT NULL ON DELETE CASCADE;
      `change_type` VARCHAR(32) NOT NULL; `work_item_id`/`room_id`/`work_category_id` BIGINT NULL FKs;
      `delta_quantity` NUMERIC(12,4) NULL; `extra_unit_price` NUMERIC(12,2) NULL;
      `schedule_delta_half_days` INT NULL; `description` VARCHAR(1024) NULL; audit cols). Guard
      `NOT tableExists` + `MARK_RAN`; register last in `changelog.xml`.
    - _Requirements: 7.1, 7.2_
    - _Depends on: none_

- [x] 2. Backend: enums
  - [x] 2.1 Add `AmendmentStatus` + `ChangeType` enums
    - `AmendmentStatus{OPEN, SENT_FOR_SIGNING, SIGNED, EFFECTIVE, DELETED}` and
      `ChangeType{SCHEDULE_CHANGE, ADDITIONAL_WORK, VOLUME_CHANGE}` (no backend i18n; labeled on the
      frontend, mirroring `DocumentStatus`).
    - _Requirements: 11.1_
    - _Depends on: none_

- [x] 3. Backend: pure merge resolver
  - [x] 3.1 Implement `AmendmentMergeResolver` (persistence-free)
    - `subjectOf`, `conflicts`, `resolve(existing, incoming, MergeResolution)`
      (REPLACE/KEEP_EXISTING/ADD_TO with per-type arithmetic: schedule sums half-days; volume/
      additional-work sums `deltaQuantity`, takes new `extraUnitPrice`, rejects resulting negative
      volume), and `offered(changeType)`.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_
    - _Depends on: none_
  - [x] 3.2 Property-test the merge resolver (jqwik)
    - single-line-per-subject after any resolution; `ADD_TO` schedule commutativity/associativity;
      `KEEP_EXISTING` idempotence; `ADD_TO` volume reject-on-negative; subject identity per type.
    - _Requirements: 5.1, 5.2, 5.3, 5.4_
    - _Depends on: 3.1_

- [x] 4. Backend: entities + DTOs
  - [x] 4.1 Add the JPA entities
    - `EstimateAmendmentEntity extends BaseEntity` (`project`, `seqNo`, `status`, `effectiveDate`,
      `signableDocument` nullable, `note`, `@OneToMany(cascade=ALL, orphanRemoval=true) lines`) and
      `AmendmentLineEntity extends BaseEntity` (`amendment`, `changeType`, `workItem`, `room`,
      `workCategory`, `deltaQuantity`, `extraUnitPrice`, `scheduleDeltaHalfDays`, `description`).
    - _Requirements: 7.1, 7.2, 7.3_
    - _Depends on: 1.1, 1.2, 2.1_
  - [x] 4.2 Add DTOs + mappers
    - Service models + controller DTOs (`AmendmentDto/DtoExtended`, `AmendmentLineDto`,
      `AddLineRequest` with optional `mergeResolution`, create/response DTOs) and the service +
      controller mappers, per the project convention.
    - _Requirements: 1.1, 1.4, 3.1_
    - _Depends on: 4.1_

- [x] 5. Backend: service
  - [x] 5.1 Implement `AmendmentService` (`ProjectScopedService`)
    - `getProjectIdPath()` → `"project.id"`; `create` (eligibility + open-exists guard), `getOpenOrNull`,
      `list`, `get`, `addLine` (per-type validation, read-only baseline resolution, merge-conflict
      detection via `AmendmentMergeResolver`, 409 `subjectConflict` carrying the existing line when no
      resolution supplied, else append/merge-in-place), `deleteLine`, `deleteAmendment`; all line
      mutations assert `status == OPEN`.
    - _Requirements: 1.3, 2.2, 2.3, 2.4, 2.5, 3.5, 3.6, 4.1, 4.2, 5.6, 6.1, 6.2, 6.3, 6.4, 6.5, 7.3_
    - _Depends on: 4.2, 3.1_
  - [x] 5.2 Service tests
    - open-amendment invariant (second create rejected), add-line per type, 409 subject-conflict
      without resolution, merge apply-in-place, delete line / delete amendment, OPEN guard on a
      non-OPEN mutation, eligibility guard. Run only these classes with `--tests` →
      `/tmp/for-06-08-test.log`; verify via JUnit XML.
    - _Requirements: 2.1, 2.3, 2.4, 3.6, 5.6, 6.1, 6.2, 6.4_
    - _Depends on: 5.1_

- [x] 6. Backend: controller
  - [x] 6.1 Add `AmendmentController`
    - `@RestController @RequestMapping("/api/amendments") @PermissionResource("AMENDMENTS")` over the
      generic CRUD scaffold + bespoke `@RequiresPermission` handlers (`/open`, `/{id}/lines`,
      `DELETE /{id}/lines/{lineId}`, `DELETE /{id}`). Verify the app starts (no
      `PermissionAnnotationValidator` failure).
    - _Requirements: 1.5, 3.7, 9.1, 9.4, 10.3_
    - _Depends on: 5.1_

- [x] 7. Backend: dormant revenue seam
  - [x] 7.1 Wire `resolveClientUnitPrice` + DAO sum query
    - Add `AmendmentLineDao.sumExtraUnitPriceForEffectiveWorkItem(workItemId, reportDate)` (sum over
      lines whose amendment is `EFFECTIVE` and `effective_date <= reportDate`) and change
      `WorkReportService.resolveClientUnitPrice` from the hard-coded zero to
      `line.unitPrice + sum(...)`. No `EFFECTIVE` amendment can exist in this build ⇒ sum is zero ⇒
      behaviour unchanged.
    - _Requirements: 8.1, 8.2_
    - _Depends on: 5.1_
  - [x] 7.2 Seam test
    - `resolveClientUnitPrice` returns `line.unitPrice` with no `EFFECTIVE` amendment (zero delta); with
      a test-only `EFFECTIVE` fixture the sum is applied (the FOR-06-09 contract). Run only this class
      with `--tests` → `/tmp/for-06-08-test.log`.
    - _Requirements: 8.2, 8.3_
    - _Depends on: 7.1_

- [x] 8. Backend: ABAC seed + matrix test
  - [x] 8.1 Seed the `AMENDMENTS` resource + grants
    - Add `179-seed-amendments-resource.xml` (two-changeSet pattern): resource `AMENDMENTS` (+ PL/RU
      name/description); ADMIN CRUD; MANAGER CRUD; FINANCIER CRUD; FOREMAN READ; CLIENT/WORKER omitted
      (commented). Each changeSet idempotent; register last in `changelog.xml`.
    - _Requirements: 9.1, 9.2, 9.3_
    - _Depends on: 1.1_
  - [x] 8.2 ABAC matrix property test
    - ADMIN/MANAGER/FINANCIER CRUD; FOREMAN READ; WORKER/CLIENT none; project-scope enforced; confirm
      seed idempotency through the Testcontainers migration run. Run only this class with `--tests` →
      `/tmp/for-06-08-test.log`; verify via JUnit XML.
    - _Requirements: 9.2, 9.3, 9.4_
    - _Depends on: 8.1_

- [x] 9. Frontend: Aneksy panel
  - [x] 9.1 Panel + tab swap scaffold
    - `features/project-amendments/AmendmentsPanel.tsx` + `AmendmentsList.tsx` (standard table); swap the
      `amendments` tab `lazy` target in `workspaceTabs.ts` from the `PlaceholderTab` to the panel; api
      client/hooks + types + zod schemas scaffold. Read path only in this task.
    - _Requirements: 1.1, 1.2, 1.4, 10.1, 10.2_
    - _Depends on: 6.1_
  - [x] 9.2 Wizard + merge chooser + delete dialogs
    - `CreateAmendmentWizard.tsx` (three icon buttons) + `ScheduleChangeForm`/`AdditionalWorkForm`/
      `VolumeChangeForm` (zod-validated, full data for additional-work/volume) +
      `MergeConflictChooser.tsx` (shown on 409, existing vs new, offered resolutions) +
      `DeleteLineDialog`/`DeleteAmendmentDialog`; write actions permission-gated on
      CREATE/UPDATE/DELETE; FOREMAN read-only.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 5.1, 5.2, 5.3, 6.1, 6.2, 10.2, 10.3_
    - _Depends on: 9.1_

- [x] 10. i18n
  - [x] 10.1 Add `amendments.*` + `error.amendment.*` keys (PL + RU)
    - Add the `amendments.*` namespace (tab, list headers, wizard title + three buttons, changeType +
      status enum labels, merge chooser options, form labels/placeholders, delete confirmations) and
      the `error.amendment.*` keys to both `pl.json` and `ru.json` (PL primary, RU secondary); add the
      backend validation messages to the PL+RU bundle. Run the locale-parity test.
    - _Requirements: 11.1, 11.2_
    - _Depends on: 9.2, 8.1_

- [x] 11. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown,
    regression group, MD report template). The backend amendment CRUD / merge / single-open invariant
    scenarios are API tests against the Dockerized app (`http://localhost:8080`, `/api/amendments`);
    the wizard / merge chooser / delete scenarios are browser-engine checks on the Aneksy tab of an
    `ACTIVE` project. Result artifacts are MD reports with tables.
  - _Requirements: 1.1, 2.1, 2.3, 3.1, 5.1, 6.1, 6.2, 9.2_
  - _Depends on: 5.1, 6.1, 7.1, 8.1, 9.2, 10.1_

- [x] 12. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava compileTestJava` → temp log) and run only the affected test
    classes; type-check + unit-test the frontend; confirm the locale-parity test passes and the app
    starts. Confirm an unsigned amendment leaves work-reporting revenue unchanged (seam delta zero).
    Then stage and commit per repo (two commits) — only if the user asks.
  - _Requirements: all_
  - _Depends on: 5.2, 7.2, 8.2, 9.2, 10.1, 11_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "2.1", "3.1"] },
    { "id": 1, "tasks": ["3.2"] },
    { "id": 2, "tasks": ["4.1"] },
    { "id": 3, "tasks": ["4.2"] },
    { "id": 4, "tasks": ["5.1"] },
    { "id": 5, "tasks": ["6.1", "7.1"] },
    { "id": 6, "tasks": ["8.1", "5.2", "7.2"] },
    { "id": 7, "tasks": ["9.1", "8.2"] },
    { "id": 8, "tasks": ["9.2"] },
    { "id": 9, "tasks": ["10.1"] },
    { "id": 10, "tasks": ["11"] },
    { "id": 11, "tasks": ["12"] }
  ]
}
```
