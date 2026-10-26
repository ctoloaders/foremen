# Implementation Plan: FOR-07-01 Cross-Project Schedule Core

## Overview

Backend-only, read-only cross-project staffing projection. The plan lands the response DTO and the
DB-free projector input records first, then the pure aggregation helpers (with their property tests),
then the service and controller that wire them to `ProjectAccessCache` and existing DAOs, then the
ABAC seed + startup verification. No new mutable entity or table. Each test sub-task sits next to the
code it exercises. Language: Java (Spring Boot), matching the existing backend.

## Tasks

- [x] 1. Response DTO and projector input records
  - [x] 1.1 Create the response DTO records and `BarKind`
    - Add `com.foremen.controller.model.companyschedule`: `CompanyScheduleView`, `MemberRow`,
      `ProjectedBar`, `ProjectLegendEntry`, `ConflictPair`, `BarKind`. No money/man-days/rate/pay-tier
      field anywhere.
    - _Requirements: 1.4, 1.6, 1.7, 8.1, 8.2, 8.3_
    - _Depends on: none_
  - [x] 1.2 Create the query-param record `CompanyScheduleQuery`
    - Normalized, nullable `roles`, `projectIds`, `memberIds`, `from`, `to`.
    - _Requirements: 7.2, 7.3, 7.4, 7.5_
    - _Depends on: none_
  - [x] 1.3 Create the projector input records
    - Add `com.foremen.service.companyschedule`: `ProjectInput`, `WorkerBarInput`, `ForemanInput`
      (DB-free plain records).
    - _Requirements: 1.2, 1.3, 2.1_
    - _Depends on: none_

- [x] 2. Pure projector helpers
  - [x] 2.1 Implement deterministic per-project color assignment
    - `assignColors(List<Long> projectIds)` — stable palette index by ascending project id.
    - _Requirements: 3.1, 3.2, 3.3_
    - _Depends on: 1.1_
  - [x] 2.2 Property test — color stability
    - **Property 4: Deterministic color by project id**
    - **Validates: Requirements 3.1, 3.2, 3.3**
    - _Depends on: 2.1_
  - [x] 2.3 Implement absolute-date projection helpers
    - `workerBarDates(...)` reusing `ScheduleCalculator.calendarDayOfHalfDay`; `foremanSpanDates(...)`;
      undatable handling for unanchored / partial-date bars.
    - _Requirements: 4.1, 4.2, 4.3, 4.4_
    - _Depends on: 1.1_
  - [x] 2.4 Property tests — date projection
    - **Property 5: Absolute-date projection round-trips the calculator**
    - **Property 6: Foreman span datable only when both project dates present**
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.4**
    - _Depends on: 2.3_
  - [x] 2.5 Implement overlap/conflict detection
    - `intervalsOverlap(...)` closed-interval; `detectConflicts(...)` over a member's datable worker
      bars only, per member.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_
    - _Depends on: 1.1_
  - [x] 2.6 Property test — conflict detection
    - **Property 7: Conflict detection is symmetric closed-interval overlap on worker bars only**
    - **Validates: Requirements 5.1, 5.2, 5.4, 5.5, 5.6**
    - _Depends on: 2.5_
  - [x] 2.7 Implement the `project(...)` orchestration (grouping, foreman precedence, uncovered
        exclusion, filters, Date_Window, ordering)
    - Group by user; drop a foreman-also-worker user's worker bars for that project; exclude
      null-member bars upstream; apply Role/Project/Member/Date_Window conjunctively; omit
      empty-after-filter members; deterministic ordering of rows/bars/legend.
    - _Requirements: 1.1, 1.2, 1.3, 1.5, 1.8, 2.1, 2.2, 2.3, 2.4, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 10.3, 10.4, 11.2_
    - _Depends on: 1.1, 1.2, 1.3, 2.1, 2.3, 2.5_
  - [x] 2.8 Property tests — grouping, uncovered, foreman precedence, filters, ordering, no-cost
    - **Property 1: One row per contributing user**
    - **Property 2: Uncovered bars never appear**
    - **Property 3: Foreman precedence over own worker bars**
    - **Property 8: Filters are conjunctive and never widen scope**
    - **Property 9: Date_Window excludes undatable bars and keeps intersecting bars**
    - **Property 10: Empty filtered members are omitted**
    - **Property 11: Deterministic ordering**
    - **Property 12: No cost-bearing field is ever emitted**
    - **Validates: Requirements 1.1, 1.3, 1.5, 1.8, 2.1-2.4, 4.5, 7.2-7.7, 8.1-8.3, 10.3, 10.4, 11.2**
    - _Depends on: 2.7_

- [x] 3. Checkpoint — projector tests pass
  - Ensure all projector unit and property tests pass, ask the user if questions arise.
  - _Depends on: 2.2, 2.4, 2.6, 2.8_

- [x] 4. Service layer
  - [x] 4.1 Add batched schedule lookup to `ProjectScheduleDao`
    - Add `findByProjectIdIn(Collection<Long>)` (or equivalent) so the service loads accessible
      schedules in one query.
    - _Requirements: 6.1, 10.2_
    - _Depends on: none_
  - [x] 4.2 Implement `CrossProjectScheduleService`
    - `@Transactional(readOnly = true)`; resolve scope (exact-authority ADMIN → null/all; non-ADMIN →
      `ProjectAccessCache.get(userId)`; empty → empty projection); intersect Project_Filter with the
      allowed set; load schedules/members/projects; build `ProjectInput`s (worker bars = non-null
      member only; foremen = ACTIVE FOREMAN members); call `CrossProjectScheduleProjector.project`.
    - _Requirements: 1.2, 1.3, 2.1, 6.1, 6.2, 6.3, 6.4, 6.5, 7.9, 9.5, 10.1, 10.2_
    - _Depends on: 1.1, 1.2, 1.3, 2.7, 4.1_
  - [x] 4.3 Unit tests for the service (mocked DAOs + cache)
    - ADMIN sees all; non-ADMIN restricted to the cache set; empty set → empty projection;
      schedule-less accessible project still yields foreman spans; read-only (no writes/version bump).
    - _Requirements: 6.1, 6.2, 6.3, 7.9, 10.1, 10.2_
    - _Depends on: 4.2_

- [x] 5. Controller
  - [x] 5.1 Implement `CrossProjectScheduleController`
    - Single `GET /api/company-schedule`; class `@PermissionResource("COMPANY_SCHEDULE")`, handler
      `@PermissionOperation("READ")`; query params `roles`/`projectIds`/`memberIds`/`from`/`to`;
      `from > to` → 400 `error.company.schedule.window.invalid` before any read. The complete query
      surface is exactly `roles`/`projectIds`/`memberIds`/`from`/`to` — no `page`/`size`/`sort`/`query`
      (Gantt, not a paginated DataTable).
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.8, 9.4, 11.1, 11.3, 11.4_
    - _Depends on: 1.1, 1.2, 4.2_
  - [x] 5.2 Integration tests for the controller
    - 200 projection; `from > to` → 400 with no data change; caller without the grant → 403;
      same filter set replayed twice yields an identical projection (Persisted_View_State replay).
    - _Requirements: 7.1, 7.8, 9.4, 11.1, 11.2, 11.4_
    - _Depends on: 5.1_

- [x] 6. ABAC seed and localization
  - [x] 6.1 Author the `COMPANY_SCHEDULE` seed changeset and register it last
    - Create `187-seed-company-schedule-resource.xml` following the
      `153-seed-work-schedule-resource.xml` pattern: resource insert guarded `onFail="MARK_RAN"`
      `COUNT(*)=0`; grant blocks `INSERT ... SELECT ... WHERE NOT EXISTS`. Grant **ADMIN + MANAGER
      READ only** (no CREATE/UPDATE/DELETE). Seed `name_ru`/`name_pl`/`description_ru`/`description_pl`.
      Register `<include>` **last** in `changelog.xml` (after 186).
    - _Requirements: 9.1, 9.2, 9.3, 9.5_
    - _Depends on: none_
  - [x] 6.2 Add the new message codes
    - `error.company.schedule.window.invalid` in `messages.properties` and `messages_ru.properties`.
    - _Requirements: 7.8_
    - _Depends on: none_

- [x] 7. ABAC / startup verification
  - [x] 7.1 Startup + seed test
    - Assert `PermissionAnnotationValidator` passes for the annotated controller at startup, and that
      the `COMPANY_SCHEDULE` resource + ADMIN/MANAGER READ grants exist with no CREATE/UPDATE/DELETE.
    - _Requirements: 9.1, 9.2, 9.4, 9.5_
    - _Depends on: 5.1, 6.1_

- [x] 8. Author test-cases.md
  - Create `test-cases.md` in the spec folder following `.kiro/steering/test-cases.md`: Russian,
    **API tests against the Dockerized app at `http://localhost:8080`** (API-only backend spec),
    feature grouping, repeatability via a run-id generator / teardown, a regression group, and the MD
    report template. Cover the projection shape, filters, scope, conflict/foreman semantics, and the
    `from > to` 400.
  - _Requirements: 1.1, 2.1, 3.1, 4.1, 5.1, 6.1, 7.1, 7.8, 8.1, 9.1, 10.1_
  - _Depends on: 5.1, 6.1, 6.2_

- [x] 9. Final checkpoint — verification
  - Run only the affected `--tests` classes (projector, service, controller, ABAC/startup) redirected
    to a temp log; confirm green. Ask the user if questions arise.
  - _Depends on: 2.2, 2.4, 2.6, 2.8, 4.3, 5.2, 7.1, 8_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 1.2, 1.3, 4.1, 6.1, 6.2 — independent leaf nodes: DTO/query/input
  records, the DAO method, and the seed changeset + message codes touch disjoint files.
- **Wave 2 (parallel):** 2.1, 2.3, 2.5 — pure helpers, each depends only on the DTO (1.1) and touches
  a distinct helper file.
- **Wave 3 (parallel):** 2.2, 2.4, 2.6 — property tests for the three helpers (independent test
  files).
- **Wave 4:** 2.7 — the `project(...)` orchestration; depends on all helpers (2.1, 2.3, 2.5) and the
  records (1.1–1.3).
- **Wave 5 (parallel):** 2.8, 4.2 — the orchestration property tests and the service (service depends
  on 2.7 + 4.1 + records; its test file is separate from 2.8's).
- **Wave 6 (parallel):** 4.3, 5.1 — service unit tests and the controller (distinct files; controller
  depends on 4.2).
- **Wave 7 (parallel):** 5.2, 7.1, 8 — controller integration tests, ABAC/startup test, and
  `test-cases.md`; all depend on 5.1 (and 6.1), touch disjoint files.
- **Wave 8:** 9 — final checkpoint/verification; depends on every test + the test-cases doc.

Checkpoint task 3 is a logical gate after Wave 3/4; it is not scheduled as a leaf in the graph.

## Notes

- Tasks marked with `*` are optional (tests) and can be skipped for a faster MVP; core implementation
  tasks are never optional.
- **Test-execution standard** (`.kiro/steering/test-execution-rules.md`): run ONLY the affected
  `--tests` classes redirected to a temp log (e.g.
  `./gradlew test --tests "com.foremen.service.companyschedule.*" > /tmp/for-07-01-test.log 2>&1; echo "EXIT=$?"`);
  never the full ~20-minute suite. Verify pass/fail from the JUnit XML under
  `foremen-backend/build/test-results/test/`.
- The seed changeset MUST be registered **last** in `changelog.xml` (the current last include is
  `186-seed-client-messages-resource.xml`, so use id/filename `187`) and MUST be idempotent
  (`onFail="MARK_RAN"` + `NOT EXISTS`).
- No new mutable entity or table — entity-creation-rules steps 1 (resource seed) + 2 (grant) + 3
  (controller annotation) apply; step 4 (`getProjectIdPath()`) does **not** (bespoke read, not a
  `ProjectScopedService` CRUD entity).
- Each property test is tagged
  `// Feature: FOR-07-01-cross-project-schedule-core, Property N: <text>` and runs ≥ 100 iterations.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "4.1", "6.1", "6.2"] },
    { "id": 1, "tasks": ["2.1", "2.3", "2.5"] },
    { "id": 2, "tasks": ["2.2", "2.4", "2.6"] },
    { "id": 3, "tasks": ["2.7"] },
    { "id": 4, "tasks": ["2.8", "4.2"] },
    { "id": 5, "tasks": ["4.3", "5.1"] },
    { "id": 6, "tasks": ["5.2", "7.1", "8"] }
  ]
}
```
