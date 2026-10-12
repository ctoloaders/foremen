# Implementation Plan — FOR-05-10b Gantt Granularity

Tasks are ordered so the backend data model and pure helpers land before the service rewrite, and
the service before the frontend. Each task references the requirements it satisfies. Run only the
affected test classes per the workspace test-execution standard (never the full suite).

- [x] 1. Data model — join tables and bar-table change
  - [x] 1.1 Changeset `154-create-user-work-categories.xml`: create `user_work_categories`
    (`user_id` FK CASCADE, `work_category_id` FK RESTRICT, `order_no`), PK `(user_id, order_no)`,
    unique `(user_id, work_category_id)`, index on `work_category_id`; idempotent precondition;
    register last in `changelog.xml`.
    - _Requirements: 1.1_
  - [x] 1.2 Changeset `155-create-project-member-work-categories.xml`: analogous table for
    `project_member_id`; idempotent; registered after 154.
    - _Requirements: 2.1_
  - [x] 1.3 Changeset `156-alter-schedule-bars-per-worker.xml`: add nullable `project_member_id`
    FK (CASCADE); convert `start_day`/`duration_days` → `start_half_days`/`duration_half_days`
    (`×2`) with CHECK ≥ 1; drop old unique, add unique
    `(schedule_id, project_member_id, work_category_id)` + partial unique index
    `(schedule_id, work_category_id) WHERE project_member_id IS NULL`; idempotent; registered after
    155.
    - _Requirements: 3.1, 3.2, 3.4_
  - [x] 1.4 JPA: add ordered `@ManyToMany` `workCategories` to `UserEntity` and
    `ProjectMemberEntity`; add nullable `projectMember` + `startHalfDays`/`durationHalfDays` to
    `ProjectScheduleBarEntity`. `getDiagnostics` + `compileJava`.
    - _Requirements: 1.1, 2.1, 3.1_

- [x] 2. Pure helpers (half-day math, coverage, layout)
  - [x] 2.1 Extend `ScheduleCalculator` with half-day conversions (`startDay`/`durationDays`/
    `finishDay` from half-days), `validateBar(startHalfDays, durationHalfDays)`, and the 7300
    horizon check; unit + property tests.
    - _Requirements: 3.3, 3.4, 3.5_
  - [x] 2.2 `CoverageResolver` (pure): covering workers per category + uncovered set from active
    worker members' categories; unit + property tests.
    - _Requirements: 4.1, 4.2, 8.2_
  - [x] 2.3 `WorkerScheduleLayout.autoLayout` (pure): equal parallel split (D-split), per-worker
    sequential layout, same-worker back-to-back, 1-day disjoint-neighbor overlap, uncovered virtual
    bar, horizon reject; property tests for each rule.
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [x] 3. User & member work-categories (backend)
  - [x] 3.1 User worker-create/edit: accept ordered distinct `workCategoryIds` for WORKER users;
    validate (`error.user.workCategories.duplicate` / `.invalid`), persist, ignore for non-WORKER;
    user read returns ordered `workCategories`. Integration tests + ABAC on `USERS`.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 9.1_
  - [x] 3.2 `ProjectMemberService`: copy user categories into Member_Work_Categories on WORKER
    membership create; `PATCH` replaces the member list (validate as 3.1 with `error.member.*`);
    independence from user default; cascade on member remove; Team_Member_View returns
    `workCategories`. Integration tests + ABAC on `PROJECT_MEMBERS`.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 9.1_

- [x] 4. Schedule service rewrite (per-worker)
  - [x] 4.1 `getView`: map rows with a `bars` array (per `(member, category)` incl. virtual
    uncovered bar), `covered` flag, half-day→day exposure, and the `workers` list. Integration test.
    - _Requirements: 3.1, 3.2, 3.3, 7.2, 7.3_
  - [x] 4.2 `saveBars`: new `BarEntry` with `projectMemberId`; validate current-row category, valid
    `(member, category)` pairing (`error.schedule.assignment.invalid`) or null virtual, grid/horizon;
    clear on both-null; one version bump + one audit row. Integration test.
    - _Requirements: 3.4, 3.5, 6.3, 9.2_
  - [x] 4.3 `autoCreate`: use `CoverageResolver` + `WorkerScheduleLayout`; return
    `AutoCreateResult { schedule, alert { crewEmpty, uncoveredCategories } }`; no empty-crew 409
    (R4.6); one version bump + one audit row. Integration test incl. multi-worker split, uncovered
    alert, empty-crew no-fail.
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7_

- [x] 5. Readiness
  - [x] 5.1 `TeamReadinessContributor`: BLOCKED + `readiness.team.noWorkers` when no ACTIVE WORKER;
    keep existing no-FOREMAN logic. Unit/integration test.
    - _Requirements: 8.1, 8.3, 8.4_
  - [x] 5.2 `ScheduleReadinessContributor` + `ScheduleReadiness.uncoveredCategoryIds`: one
    `readiness.schedule.noWorkerForCategory {category}` item per Uncovered_Category; BLOCKED when any
    uncovered. Unit/integration test.
    - _Requirements: 8.2, 8.3, 8.4_

- [x] 6. Frontend — work-category multiselect & team/invite
  - [x] 6.1 `WorkCategoryMultiSelect` (ordered, worker-only) + catalog query hook; component test.
    - _Requirements: 1.1, 2.2, 9.3_
  - [x] 6.2 Embed in the Team member editor (`PATCH` workCategoryIds) and the worker invite dialog
    (worker create); show only for WORKER. Component tests.
    - _Requirements: 1.1, 2.2_

- [x] 7. Frontend — per-worker Gantt
  - [x] 7.1 `lib/halfDay.ts` + `lib/workerColor.ts` + types (`ScheduleRowView.bars`, `workers`,
    `AutoCreateResult.alert`); unit tests.
    - _Requirements: 3.3, 7.1_
  - [x] 7.2 `GanttRow`/`GanttBar`/`WorkerLegend`: N bars per category, per-worker color, unassigned
    style, legend; half-day grid in day zoom. Component tests.
    - _Requirements: 7.1, 7.2, 7.3_
  - [x] 7.3 `lib/dragCascade.ts` + wire drag/resize to cascade a worker's later bars; snap 0.5,
    clamp `[0.5, 3650]`; unit + component tests.
    - _Requirements: 6.1, 6.2, 6.4, 5.5_
  - [x] 7.4 `ManualBarDialog` on empty-cell click: worker picker (covering workers), pre-filled
    suggested duration, free 0.5 entry, uncovered state. Component test.
    - _Requirements: 5.1, 5.2, 5.3, 5.4_
  - [x] 7.5 Auto-create alert UI (crew empty / uncovered categories) from `AutoCreateResult.alert`.
    Component test.
    - _Requirements: 4.5_

- [x] 8. Localization & regression
  - [x] 8.1 Add all new keys to `pl.json` + `ru.json` (identical sets) and the backend message
    bundles; locale-parity test passes.
    - _Requirements: 9.3_
  - [x] 8.2 Regression: run the affected FOR-05-10 / FOR-05-09 / FOR-05-11 test classes
    (`--tests` filters only) and confirm green.
    - _Requirements: 9.4_

- [x] 9. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown,
    regression group, MD report template). API-only scenarios are API tests against the Dockerized
    app; the Gantt interactions are browser-engine scenarios. Result artifacts are MD reports with
    tables.
  - _Requirements: 1.1–9.4 (the test cases cover the user/member category CRUD, auto-create split +
    uncovered alert, per-worker rendering, drag cascade, manual bar creation, and readiness
    blockers)_

- [x] 10. Final checkpoint
  - Compile backend (`compileJava compileTestJava`) and frontend (`tsc -b`); run only the affected
    test classes per the test-execution standard and confirm green; confirm no new ABAC resource and
    all idempotent changesets registered in order.
    - _Requirements: 9.1, 9.2, 9.4_
