# Bug report: FOR-05-10-project-gantt

Screen URL: http://localhost:3000/projects/1/scheduleDesign

Owning spec: `.kiro/specs/FOR-05-project-estimate/FOR-05-10-project-gantt` (the planning Gantt /
Harmonogram, frontend `foremen-frontend/src/features/project-schedule/**`, panel `ScheduleTab.tsx`;
backend `ProjectScheduleService` + `GET/PUT /api/project-schedules`). Touches FOR-05-09 (team member
read model as the worker dropdown source) and FOR-02-07 (admin user create/edit form).

Notifications: OUT OF SCOPE — the schedule write path (`saveBars`) is a draft/save with an audit log
and emits no in-app notifications today; the user requested none for this change, and reassigning a
bar's worker is a planning edit, not a member lifecycle event (FOR-05-09 owns member-assignment
notifications). No new notification types are added.

Terminology (confirmed with user): "work types" = **Work_Categories** (the worker's skills:
`ProjectMember.workCategories` / `UserEntity.workCategories`), NOT the FOR-05-06 cost-tier
`WorkerType`. The "match" is `member.workCategories CONTAINS bar.workCategory`.

## Round 1 (2026-10-28)

- [x] 1. Backend: relax the schedule bar (member, category) hard rule to a soft, allowed mismatch + expose a mismatch flag
  - Request (user, confirmed): assigning a worker whose Work_Categories don't cover the bar's
    category must be ALLOWED (with a warning + explicit approval in the UI), not hard-rejected.
  - Today: `ProjectScheduleService.saveBars` (validation around the `error.schedule.assignment.invalid`
    guard, FOR-05-10b R3.5) HARD-rejects a `(projectMemberId, workCategoryId)` entry whose member's
    `workCategories` do not contain the category. Fix: relax that check so a non-null member need only
    be an ACTIVE WORKER of the project (keep that hard guard); a category MISMATCH is permitted. Add a
    computed `categoryMismatch` boolean to `ScheduleBarView` (true iff the bar's member is non-null
    and `memberCategoryIds(member)` does NOT contain the bar's `workCategoryId`) so the UI can mark it.
    Update the FOR-05-10b property/unit tests that currently assert the hard rejection (flip them to
    assert acceptance + the flag). The ABAC/editable/version/grid guards are unchanged.
  - Suspect: `foremen-backend/.../service/ProjectScheduleService.java` (saveBars validation +
    `memberCategoryIds`), `foremen-backend/.../controller/model/schedule/ScheduleBarView.java`
    (+`categoryMismatch`), the ScheduleView assembler that builds bars, and the FOR-05-10b schedule
    tests asserting `error.schedule.assignment.invalid`.
  - _Requirements: FOR-05-10b R3.2, R3.5 (relaxed to soft-match)_
  - _Depends on: none_

- [x] 2. Frontend: bar-click popup with details + team-scoped worker add/change (dropdown), mismatch sign + explicit approval
  - Request (confirmed): clicking a time-bar opens a popup showing bar details and letting the user
    ADD or CHANGE the assigned worker. The worker picker is a DROPDOWN listing ONLY the project team
    (ACTIVE WORKERS). A worker whose Work_Categories don't match the bar's category is marked (a sign)
    in the dropdown; selecting a non-matching worker is allowed but requires EXPLICIT approval (a
    confirm step) before it is applied. Do NOT add date editing to the popup (dates stay drag-edited).
  - Today: `GanttBar` has an `onOpenEdit` seam wired to a no-op ("task 7.4"); the only worker
    `<select>` is in `ManualBarDialog` and lists only the row's current covering workers, not the full
    team. Fix: implement the popup opened from `GanttBar.onOpenEdit` (via `GanttRow`/`GanttGrid`):
    show category name, worker, start/duration/finish (read-only); a worker dropdown sourced from the
    Team_API (`fetchTeamMembers`, filter `block==='WORKERS' && assignmentStatus==='ACTIVE'`), each
    option flagged when `!member.workCategories?.some(c => c.id === row.workCategoryId)`; allow clear
    (→ virtual uncovered). On picking a NON-matching worker, require an explicit confirm before
    staging. Commit via the existing `saveBars` draft write targeting the `(projectMemberId,
    workCategoryId)` pair (reassign = clear old member's bar + set new member's bar for that category,
    preserving dates). This is the planner (internal staff) view, so `TeamMemberView.workCategories`
    is unmasked (D12).
  - Suspect: `foremen-frontend/.../project-schedule/components/GanttBar.tsx` (onOpenEdit),
    `GanttRow.tsx`/`GanttGrid.tsx` (wire + own the popup), a new `BarWorkerDialog.tsx`,
    `project-schedule/types.ts` (+`categoryMismatch` on `ScheduleBarView`), `scheduleApi.ts`
    (reassign via saveBars), reuse `project-team` `fetchTeamMembers`. i18n: new
    `projectSchedule.barWorker.*` keys (PL+RU) — title, worker label, "no matching work category"
    sign + tooltip, the mismatch-confirm text, assign/clear/cancel.
  - _Requirements: FOR-05-10 R5/R7 (bar edit), FOR-05-10b R3.2/R3.5, FOR-05-09 R11 (team source)_
  - _Depends on: 1_

- [x] 3. Frontend: mark a bar with an exclamation sign + hover info when its worker mismatches the bar's category
  - Request (confirmed): a bar whose assigned worker does not have the bar's work category is marked
    with an exclamation mark; hovering shows an info tooltip.
  - Fix: in `GanttBar`, when the bar's `categoryMismatch` (from task 1) is true, render a small
    exclamation/warning badge on the bar with a `title`/tooltip explaining the worker lacks this work
    category (localized). Purely presentational; driven by the new read-model flag.
  - Suspect: `foremen-frontend/.../project-schedule/components/GanttBar.tsx`; i18n
    `projectSchedule.barWorker.mismatchBadge` / `.mismatchTooltip` (PL+RU).
  - _Requirements: FOR-05-10b R3.5 (soft-match surfacing)_
  - _Depends on: 1_

- [x] 4. Frontend: admin user create/edit — add a Work_Categories multiselect for the WORKER role
  - Request (confirmed): in the user create/edit form, for the worker role add assignment of
    work categories; these become the default selection when the worker is assigned to a project.
  - Today: the backend `UserService` already supports a user-level `workCategoryIds` (FOR-05-10b R1,
    worker-only, distinct, active) that is copied onto the member on assignment (R2.1) and used as the
    category-match set; but the ADMIN user form (`UserFormSheet`) exposes only the FOR-02-07 cost-tier
    `workerTypeId` — NO work-category multiselect. Fix: add a Work_Categories multiselect to
    `UserFormSheet`, shown only when the selected role is WORKER (next to the worker-type selector);
    submit `workCategoryIds` in the create/update payload (already accepted by the backend DTOs); add
    `workCategories` (read refs) to the user detail type for edit prefill. Backend: `UserCreateRequest`
    / `UserUpdateRequest` already carry `workCategoryIds`; ensure `UserDtoExtendedModel` /
    `UserServiceExtendedModel` expose `workCategories` refs for prefill (service mapper already has
    `toWorkCategoryRefs`). A searchable multiselect sourced from `/api/work-categories`.
  - Suspect: `foremen-frontend/.../users/components/UserFormSheet.tsx` (+ a `WorkCategoryMultiSelect`,
    possibly reusing the team `WorkCategoryMultiSelect`), `users/schemas/user-schema.ts`
    (`workCategoryIds`), `users/types/index.ts` (request `workCategoryIds`, response `workCategories`);
    backend `UserDtoExtendedModel` (+`workCategories` already present — verify) and the user read
    mapper. i18n `users.form.workCategories*` (PL+RU).
  - _Requirements: FOR-05-10b R1 (user default categories), FOR-02-07 (user form)_
  - _Depends on: none_

- [x] V1. Validate Round 1 in the browser
  - Bug 4: create/edit a WORKER user, assign work categories, confirm they persist + prefill on edit.
  - Bugs 1–3: on `/projects/1/scheduleDesign`, click a bar → popup shows details + a team-scoped
    worker dropdown; a worker lacking the bar's category is flagged in the dropdown; selecting them
    requires explicit confirm; after assigning, the bar shows an exclamation badge with a hover
    tooltip; a matching worker shows no badge. Confirm the member's default categories (from bug 4)
    drive which workers are flagged. Mark `[x]` only on a confirmed browser pass.
  - Result (2026-10-28, PASS):
    - **Implementation.** Backend: `ScheduleBarView.categoryMismatch` (computed in
      `ProjectScheduleService.getView` from the member's Work_Categories vs the bar's category);
      `saveBars` relaxed so a non-covering ACTIVE WORKER assignment is ALLOWED (only a non-worker /
      unknown / inactive member still 400s `error.schedule.assignment.invalid`). Updated the
      per-worker save-bars integration test (invalid-pairing → now asserts acceptance + the flag) and
      `PlanActualServiceTest` fixtures. Frontend: `ScheduleBarView.categoryMismatch`; `GanttBar`
      renders an amber "!" badge + hover tooltip on a mismatch and opens the popup on a click that
      did not drag (or Enter); new `BarWorkerDialog` (read-only details + team-scoped ACTIVE-WORKER
      dropdown with per-option mismatch sign + explicit-approval checkbox) sourced from
      `useTeamMembers`; reassign stages `clearBar(old)` + `setBar(new)` in the draft;
      `useScheduleDraft.resolveRowBars` now also renders draft-only (newly re-assigned) bars so the
      reassign is visible before save. User form: a Work_Categories multiselect (reused
      `WorkCategoryMultiSelect`) shown for the WORKER role, submitting `workCategoryIds` (backend
      already supports it).
    - **Backend verify.** `compileJava`/`compileTestJava` clean; `ProjectSchedulePerWorkerSaveBarsIntegrationTest`
      (1), `ProjectScheduleServiceTest` (2), `PlanActualServiceTest` (10), `ScheduleGranularityMessagesParityTest`
      (11) pass. Frontend `npx tsc -b` clean; `project-schedule` 136, `project-team` 146 pass;
      `users` only the 3 PRE-EXISTING RoleSelect flakes (+ pre-existing `UserFormSheet.test.tsx`
      collection error), confirmed unrelated on a clean tree.
    - **Browser.** Assigned a non-covering worker (no categories) to the category-1 bar via the API →
      accepted (was a 400 before) with `categoryMismatch:true`. The bar rendered the "!" badge with
      the PL tooltip; clicking it opened the popup showing details + the dropdown with "Foremen Biuro
      ⚠ (brak pasującej kategorii)" and "Valera" (no flag); the mismatched current worker showed the
      warning block with Apply disabled until approved; selecting the matching "Valera" cleared the
      warning and enabled Apply; applying staged + rendered the Valera bar immediately; Save persisted
      it (`categoryMismatch:false`, version 3). User form: selecting WORKER revealed both the
      worker-type selector and the Work_Categories multiselect. Test data cleaned up (member removed;
      schedule left with Valera).
  - _Requirements: all of the above_
  - _Depends on: 1, 2, 3, 4_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1 (backend relax + flag) and 4 (admin user form categories) — independent
  (different files/layers; 4's backend support largely exists).
- **Wave 2 (parallel):** 2 (bar popup + dropdown) and 3 (mismatch badge) — both depend on 1's
  `categoryMismatch` flag; 2 and 3 both edit `GanttBar.tsx`, so serialize 2 → 3 (shared file).
- **Wave 3:** V1 — browser validation; depends on 1, 2, 3, 4.
