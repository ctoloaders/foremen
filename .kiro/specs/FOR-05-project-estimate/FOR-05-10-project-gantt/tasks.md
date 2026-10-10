# Implementation Plan: FOR-05-10 — Planning Gantt (Harmonogram)

## Overview

This plan implements the design in incremental steps: schema and ABAC seed → entities and config →
pure calculators with property tests → service rules (read, save, auto-create, lock,
version, audit, readiness) → controller → backend i18n → frontend libs (holidays, timeline, drag)
→ the Harmonogram tab → readiness chip → frontend i18n → test-cases.md. Every correctness property
from the design (Properties 1–10) becomes a property-based test next to the code it validates.

**Entity-creation checklist** (`.kiro/steering/entity-creation-rules.md`): this spec introduces a
new project-scoped managed entity (`ProjectSchedule` + bars), so all four steps apply: the
`WORK_SCHEDULE` resource seed (step 1), the ADMIN grant plus the other role grants (step 2),
`@PermissionResource("WORK_SCHEDULE")` on `ProjectScheduleController` (step 3), and
`getProjectIdPath()` = `"project.id"` on `ProjectScheduleService` (step 4).

**Two git repos** (`.kiro/steering/git-repo-structure.md`): changesets, backend, spec, and parent
OVERVIEW / PROGRESS / FOR-06 OVERVIEW updates are committed from the **root repo**; everything under
`foremen-frontend/src/**` is committed from **inside `foremen-frontend/`**. Two commits in total.

**Tests** (`.kiro/steering/test-execution-rules.md`): never run the full suite; run only the
affected classes with `--tests`, redirect to `/tmp/for-05-10-test.log`, and read the JUnit XML
under `foremen-backend/build/test-results/test/`. Use `./gradlew compileJava` /
`compileTestJava` for fast checks. Frontend: `npx vitest --run <files>`.

## Tasks

- [x] 1. Database schema and ABAC seed (changesets 152–153)
  - [x] 1.1 Create the schedule tables (changeset 152)
    - Create `foremen-backend/database_files/changesets/152-create-project-schedules.xml` with
      `project_schedules` (`project_id` FK → `projects` ON DELETE CASCADE, UNIQUE; `version`
      BIGINT NOT NULL DEFAULT 0; BaseEntity columns; no rate column)
      and `project_schedule_bars` (`schedule_id` FK → `project_schedules` ON DELETE CASCADE;
      `work_category_id` FK → `work_categories`; `start_day` / `duration_days` INT NOT NULL with
      CHECK ≥ 1; UNIQUE (`schedule_id`, `work_category_id`); index on `schedule_id`; BaseEntity
      columns). Guard each table with `NOT tableExists` + `onFail="MARK_RAN"`.
    - Register it **last** in `changelog.xml`.
    - _Requirements: 6.1, 6.2_

  - [x] 1.2 Seed the `WORK_SCHEDULE` resource and grants (changeset 153)
    - Create `153-seed-work-schedule-resource.xml`: resource row (names/descriptions per design),
      guarded by `COUNT(*) ... code = 'WORK_SCHEDULE'` = 0; ADMIN + MANAGER CRUD; FOREMAN +
      ESTIMATOR READ + UPDATE; WORKER, FINANCIER, CLIENT READ. Every grant is an
      `INSERT ... SELECT ... WHERE NOT EXISTS` block (the `136` pattern).
    - Register it **last** in `changelog.xml`, after `152`.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

  - [x] 1.3 Write a migration idempotence integration test
    - Verify both changesets apply, create exactly the described tables/constraints and grants, and
      change nothing on a second run; verify the CHECK and UNIQUE constraints reject bad rows and
      that deleting a project cascades to its schedule and bars.
    - _Requirements: 1.5, 6.1, 6.2_

- [x] 2. Entities, DAOs, and configuration
  - [x] 2.1 Map `ProjectScheduleEntity` and `ProjectScheduleBarEntity`
    - `ProjectScheduleEntity extends BaseEntity`: `@OneToOne`/`@ManyToOne` project (unique),
      `@Version Long version`, `@OneToMany(mappedBy,
      cascade = ALL, orphanRemoval = true)` bars. `ProjectScheduleBarEntity extends BaseEntity`:
      schedule, workCategory (LAZY), `startDay`, `durationDays`.
    - Add `ProjectScheduleDao.findByProjectId(Long)` and the bar DAO if needed.
    - _Requirements: 6.1, 6.2, 6.3_

  - [x] 2.2 Add `ScheduleProperties` and the config key
    - `@ConfigurationProperties("foremen.schedule") @Validated` with
      `dailyOutputPerWorker` (`@NotNull @DecimalMin(value = "0", inclusive = false)`); add
      `foremen.schedule.daily-output-per-worker: ${FOREMEN_SCHEDULE_DAILY_OUTPUT_PER_WORKER:2000}`
      to `application.yml` with a FOR-05-10 comment (internal, never exposed); add the variable
      to `.env.example`. No per-project override.
    - _Requirements: 9.1, 9.2_

  - [x] 2.3 Define request/response models
    - `ScheduleView`, `ScheduleRowView`, `ScheduleLineView`, `ScheduleReadiness`,
      `SaveBarsRequest`, `BarEntry`, `AutoCreateRequest` (`version` only) per the design JSON.
      Money fields (`currency`, `categoryValue`) are `@JsonInclude(NON_NULL)` so they are absent
      for non-Money_Viewers. No model has a man-days or rate field.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 9.3, 12.2_

- [x] 3. Pure calculators
  - [x] 3.1 Implement `ScheduleRowDerivation`
    - Group estimate lines by work category; compute `lineCount`, `categoryValue` (null value → 0),
      sorted lines; order rows by `(orderNo ASC NULLS LAST, id ASC)`; keep inactive categories.
    - _Requirements: 4.1, 4.2, 4.3, 4.7_

  - [x] 3.2 Write property test for row derivation (Property 1)
    - jqwik: arbitrary lines over arbitrary categories ⇒ one row per distinct category, ordering,
      line-count and value sums preserved.
    - _Requirements: 4.1, 4.2_

  - [x] 3.3 Implement `ScheduleCalculator`
    - `duration` (exact `BigDecimal` ceiling division, min 1), `suggested` (null for crew 0),
      `autoLayout` over calendar days (no weekend/holiday gaps),
      `finishDay`, `scheduleFinish`, `finishDate`, `exceedsProjectEnd`, `validateBar`, and the
      3650-day limit.
    - _Requirements: 5.4, 5.5, 7.2, 7.3, 8.2, 8.3, 8.7_

  - [x] 3.4 Write property tests for auto-create (Properties 2, 3, 4)
    - Capacity inequality for `v > 0`, `d = 1` for `v = 0`, contiguity from day 1, finish = Σ d,
      determinism.
    - _Requirements: 8.2, 8.3, 8.4, 8.8_

  - [x] 3.5 Write property test for bar validation bounds (Property 5)
    - Accepted iff both null or both in `[1, 3650]` with finish ≤ 3650.
    - _Requirements: 7.2, 7.3_

  - [x] 3.6 Implement `ScheduleReadinessState` and its property test (Property 6)
    - `DONE` / `PARTIAL` / `BLOCKED` from `(rowCount, scheduledCount)`; jqwik consistency check.
    - _Requirements: 12.1, 12.3_

- [x] 4. Checkpoint — Ensure all tests pass
  - Run the migration test and tasks 3.x property tests only (see Overview). Ask the user if
    questions arise.

- [x] 5. `ProjectScheduleService`: access, read, and lock
  - [x] 5.1 Make the service project-scoped
    - Implement `ProjectScopedService`: `getProjectIdPath()` → `"project.id"`,
      `allowedProjectIds` → `ProjectAccessCache.get`. Implement `loadAccessibleProject` with
      identical 404 bodies for missing and non-accessible projects.
    - _Requirements: 3.1, 3.2, 3.3_

  - [x] 5.2 Implement `getView`
    - Load project, estimate lines (fetch-joined work item → category, unit), crew size (ACTIVE
      WORKER members), configured rate (internal only), stored bars; build rows via
      `ScheduleRowDerivation`; skip orphan bars; compute Suggested_Duration, finish, `finishDate`,
      `exceedsProjectEnd`, `editable`;
      include money only for Money_Viewers; localized names with ru → pl → code fallback; no row
      created when the schedule does not exist (version 0).
    - _Requirements: 4.3, 4.4, 4.5, 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8_

  - [x] 5.3 Implement the lifecycle lock and version checks
    - `assertEditable` (409 `error.schedule.locked` outside DRAFT / READY_TO_OFFER / OFFERED /
      APPROVED / ON_HOLD), `assertVersion` (409 `error.schedule.conflict`), and mapping of
      `ObjectOptimisticLockingFailureException` and the `project_id` unique violation to
      `error.schedule.conflict`. Enforce the R3.5 check order.
    - _Requirements: 3.5, 6.4, 10.1, 10.2, 10.3, 11.1, 11.2, 11.3_

  - [x] 5.4 Write unit tests for read, masking, lock, and check order
    - Money_Viewer vs non-viewer payloads, no-schedule read, orphan exclusion, locale fallback,
      locked-status writes rejected for ADMIN too, locked reads allowed, check-order precedence.
    - _Requirements: 3.5, 4.5, 5.3, 5.6, 5.7, 10.2, 10.3_

- [x] 6. `ProjectScheduleService`: writes, audit, readiness
  - [x] 6.1 Implement `saveBars`
    - Validate entries (half-null, bounds, finish ≤ 3650 → `error.schedule.bar.invalid` with the
      category id; unknown/duplicate category → `error.schedule.category.invalid`); upsert / clear
      listed bars; leave unlisted bars; purge orphans; detect no-op (no version bump, no audit);
      force exactly one version increment on change; return the fresh view.
    - _Requirements: 4.6, 6.3, 6.4, 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [x] 6.2 Implement `autoCreate`
    - Crew ≥ 1 (`error.schedule.crew.empty`), rows ≥ 1 (`error.schedule.rows.empty`),
      `autoLayout` with the configured rate (`error.schedule.too.long`), replace all bars, purge
      orphans, one version increment.
    - _Requirements: 8.1, 8.2, 8.3, 8.5, 8.6, 8.7, 9.2_

  - [x] 6.3 Write the audit snapshot per committed write
    - One `UPDATE` audit row per changing write with kind `SAVE_BARS` / `AUTO_CREATE`,
      before/after `{bars[{workCategoryCode, startDay, durationDays}]}`, empty before-snapshot on
      first write, no money / man-days / rate, none for rejected or no-op writes.
    - _Requirements: 13.1, 13.2, 13.3, 13.4_

  - [x] 6.4 Implement `readiness`
    - Count current rows and scheduled current rows; state via `ScheduleReadinessState`.
    - _Requirements: 12.1, 12.2_

  - [x] 6.5 Write unit tests for writes (Properties 7, 8)
    - Save/clear/unlisted-unchanged, no-op save, orphan purge on every write and not on locked
      projects, version monotonicity, auto-create spanning weekends without gaps, no man-days or
      rate in any response or audit snapshot, audit row content.
    - _Requirements: 4.6, 7.1, 7.5, 8.3, 9.3, 10.5, 13.1, 13.3, 13.4_

- [x] 7. Controller and backend i18n
  - [x] 7.1 Implement `ProjectScheduleController`
    - `@RestController @RequestMapping("/api/project-schedules") @PermissionResource("WORK_SCHEDULE")`
      with GET `/` (READ), PUT `/bars` (UPDATE), POST `/auto-create` (UPDATE), GET `/readiness`
      (READ); `@RequestParam Long projectId` on each.
    - _Requirements: 2.1, 2.2, 3.4_

  - [x] 7.2 Add backend message codes
    - Add the seven `error.schedule.*` codes to `messages.properties` (pl) and
      `messages_ru.properties` (ru) with the design texts.
    - _Requirements: 17.2, 17.3_

  - [x] 7.3 Write ABAC per-role × per-endpoint tests
    - 401 unauthenticated; ADMIN / MANAGER all endpoints; FOREMAN / ESTIMATOR read + write;
      WORKER / FINANCIER / CLIENT read only, 403 `error.access.denied` on writes; 404 for
      non-member and non-existent project with identical bodies; startup validator accepts the
      controller.
    - _Requirements: 2.1, 2.3, 2.4, 2.5, 3.2, 3.3_

  - [x] 7.4 Write an end-to-end integration test (Testcontainers)
    - Project with estimate lines in three categories and two ACTIVE workers: read (unscheduled),
      auto-create, drag-like save, stale-version 409, concurrent first write, readiness
      BLOCKED → DONE → PARTIAL after adding a category, project → ACTIVE locks writes.
    - _Requirements: 4.4, 6.4, 8.1, 10.2, 11.3, 12.1_

  - [x] 7.5 Update the FOR-03-08 enumeration tests
    - Add `ProjectScheduleController` → `WORK_SCHEDULE` to the guarded-controller list and the
      `WORK_SCHEDULE` grants to the seeded-resource assertions.
    - _Requirements: 18.3_

- [x] 8. Checkpoint — Ensure all tests pass
  - Run tasks 1.3, 3.x, 5.4, 6.5, 7.3, 7.4, 7.5 test classes only. Ask the user if questions arise.

- [x] 9. Frontend libraries
  - [x] 9.1 Implement `polishHolidays.ts` and its tests (Property 9)
    - `easterSunday(year)`, `polishHolidays(year)` → `Map<isoDate, holidayKey>` with 24 Dec from
      2025; fast-check property plus fixed 2026 / 2027 calendars.
    - _Requirements: 15.5, 15.12_

  - [x] 9.2 Implement `timeline.ts` and `dragMath.ts` with tests (Property 10)
    - `dayToDate`, `dateToDay`, ISO-week and relative-week columns, day columns, range
      (`max(finish, endDay, 28) + 7`, padded to weeks); `pxToDays`, `applyMove`,
      `applyResizeStart`, `applyResizeEnd` with snapping and clamping; fast-check round-trip and
      clamping properties.
    - _Requirements: 15.4, 15.6, 15.8, 16.1_

  - [x] 9.3 Implement the API client, types, query keys, and error-key map
    - `scheduleApi.ts`, `types.ts`, `queryKeys.ts`, `errorKeys.ts` (code → `projectSchedule.error.*`).
    - _Requirements: 17.3_

- [x] 10. Harmonogram tab
  - [x] 10.1 Wire the tab into `WORKSPACE_TABS`
    - Change `scheduleDesign` to `owner: 'FOR-05'` and `lazy` → `ScheduleTab`; keep key, stage,
      gate, label, and position; update the header comment (drop `WORK_SCHEDULE` from the unseeded
      list). Existing resolver tests must pass unchanged.
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 18.2_

  - [x] 10.2 Implement `ScheduleTab`, `GanttGrid`, `TimelineHeader`, `GanttRow`, `RowLinesPanel`, `ScheduleSummary`, `CalendarLegend`
    - Loading / error + retry / empty state; label column with name, line count, planned duration,
      money for Money_Viewers (never man-days or rate); expandable lines; Week_View default and Day_View; ISO-week or relative labels;
      weekend and holiday shading with names on hover/focus; project-end marker; summary and
      exceeds-end warning; no-start hint; sticky label column with horizontal scroll; mobile
      layout below 768px with overflow toolbar.
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9, 15.10, 15.11_

  - [x] 10.3 Implement `useScheduleDraft`, `GanttBar` drag and keyboard, `BarEditDialog`, schedule/clear actions
    - Pointer drag move / resize with live preview and snapping; Left/Right move, Shift+Left/Right
      resize, Enter opens the dialog; dialog with date picker or day number and validation;
      "Schedule" prefill (`suggestedDurationDays ?? 1`); "Remove from schedule"; changed-row marks; overlaps allowed.
    - _Requirements: 16.1, 16.2, 16.3, 16.4, 16.5, 16.13_

  - [x] 10.4 Implement `ScheduleToolbar`, save / discard, `AutoCreateDialog`, read-only mode, and the unsaved guard
    - Save sends changed rows with the version; discard drops the draft; conflict shows reload;
      auto-create disabled with hint when crew is 0, dialog with the explanation in words, crew
      size, replace and unsaved warnings (no rate, sends `{ version }` only); pending-request
      disabling; read-only banner when locked; leave-page confirmation.
    - _Requirements: 16.5, 16.6, 16.7, 16.8, 16.9, 16.10, 16.11, 16.12_

  - [x] 10.5 Write component tests for the tab
    - States, money masking, read-only, no-start vs dated anchor, exceeds-end warning, save and
      discard, conflict, auto-create dialog sends only `{ version }`, no man-days or rate rendered
      anywhere, keyboard editing,
      accessible names, language switch keeps the draft.
    - _Requirements: 15.1, 15.6, 15.7, 16.4, 16.6, 16.9, 16.10, 17.5_

- [x] 11. Readiness chip
  - [x] 11.1 Add the `schedule` chip to `ReadinessWidget`
    - Gated on `WORK_SCHEDULE` READ; `useScheduleReadiness`; three state styles; counted in the
      headline percentage; omitted with a load-error note on failure; invalidated after a
      successful save or auto-create.
    - _Requirements: 12.4, 12.5, 12.6, 12.7, 12.8_

  - [x] 11.2 Write ReadinessWidget tests for the chip
    - Visible / hidden by permission, each state, percentage with PARTIAL, failure path.
    - _Requirements: 12.4, 12.5, 12.6, 12.8_

- [x] 12. Frontend localization
  - [x] 12.1 Add all `projectSchedule.*` keys to `pl.json` and `ru.json`
    - Exactly the design key table; same key set and placeholders in both files; date-fns `pl` /
      `ru` locales for dates and weekdays; `Intl.NumberFormat` for numbers. Locale parity tests must
      pass.
    - _Requirements: 17.1, 17.4, 17.5_

- [x] 13. Parent documentation updates
  - [x] 13.1 Update OVERVIEW, PROGRESS, and FOR-06 OVERVIEW
    - `FOR-05-project-estimate/OVERVIEW.md`: row `10 | FOR-05-10-project-gantt | full-stack`,
      remove row 15, update the dependency graph and the FOR-05 → FOR-06 transition table, add the
      renumbering note. `PROGRESS.md`: the FOR-05 child row. `FOR-06-work-schedule/OVERVIEW.md`:
      replace the planning-Gantt references `FOR-05-16` with `FOR-05-10`.
    - _Requirements: 18.1_

- [x] 14. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md`
    standard (feature grouping, step-by-step scenarios, repeatability via generator/clean-up,
    regression group, MD report template). This is a UI + API spec: API scenarios against the
    Dockerized app (`http://localhost:8080`) for the Schedule_API and browser-engine scenarios for
    the Harmonogram tab and the readiness chip. Result artifacts are MD reports with tables.
    Include at least one case for each of Requirements 1–17.
  - _Requirements: 18.5, 18.6_

- [x] 15. Final checkpoint — Ensure all tests pass
  - Run only the test classes and Vitest files added or changed by this spec. Ask the user if
    questions arise.
