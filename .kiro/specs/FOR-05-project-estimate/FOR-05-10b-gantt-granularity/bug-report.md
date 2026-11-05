# Bug report: FOR-05-10b-gantt-granularity

Screen URL: `http://localhost:3000/projects/{id}/scheduleDesign` (project schedule design / Gantt).

## Round 1 (2026-11-05)

Source: meeting notes. Reported bug: "workers are shown with the same colors after re-creation of
the schedule" — after the schedule is re-created (team rebuilt / schedule regenerated), different
workers collide on the same color, and a given worker's color is not stable.

Background (grounded in code): the per-worker Gantt color is computed client-side in
`foremen-frontend/src/features/project-schedule/lib/workerColor.ts`:
`workerColor(projectMemberId) = WORKER_COLOR_PALETTE[ FNV1a(String(projectMemberId)) % 12 ]`
(12-color palette; `UNASSIGNED_COLOR` gray for the null-member virtual bar). The backend
(`ProjectScheduleService.getView`) keys every `ScheduleBarView`/`ScheduleWorkerView` by the
**`ProjectMemberEntity.id`** (the project-membership row id), and `ScheduleWorkerView.color` is
always `null` (color is purely client-side). Consumers: `GanttRow.tsx`, `GanttBar.tsx`,
`WorkerLegend.tsx`.

Two compounding root causes:
1. **The color key is the volatile project-membership row id, not the stable user id.** When a
   worker is removed and re-added to the team (FOR-05-09), their old `project_members` row is deleted
   and a new row with a new id is inserted for the same user — so the same human worker hashes to a
   different palette slot and changes color after a "re-creation".
2. **No collision avoidance.** `hash % 12` maps an unbounded id space onto 12 buckets with no dedup
   against colors already used in the current worker set, so after ids shift two co-existing workers
   routinely land on the same color.

- [x] 1. Key the worker color by the stable user id and dedup within the schedule's worker set
  - Triage: observed — color = `FNV1a(projectMemberId) % 12`, keyed by the churning membership row
    id, with no dedup → duplicate/shifted colors after re-creation. Expected — a given human worker
    keeps a stable color across schedule/team re-creation, and no two co-existing workers in one
    schedule share a color (until the palette is exhausted). Layer: **both** (backend exposes the
    stable key; frontend changes the algorithm).
    - **Backend:** add the stable `userId` to `ScheduleWorkerView` and `ScheduleBarView` in
      `ProjectScheduleService.getView` (the member's `getUser().getId()` is already reachable in the
      read transaction). Keep `projectMemberId` for existing wiring.
    - **Frontend:** change `workerColor` to key by `userId`; and/or assign palette entries by the
      worker's **position within the current `view.workers` set with explicit dedup** so co-existing
      workers never collide (stable per schedule, order from the id-sorted worker legend). Thread the
      chosen key through `GanttRow`/`GanttBar`/`WorkerLegend` so bars and the legend agree.
    - Update `lib/__tests__/workerColor.test.ts`: assert stability across a simulated membership-id
      change for the same user, and no-collision across a worker set up to the palette size.
  - _Requirements: (reference the FOR-05-10b R7.1 / R7.3 worker-color requirement)_
  - _Depends on: none_
- [x] V1. Validate Round 1 in browser
  - Rebuild the stack; on `/projects/{id}/scheduleDesign` confirm each worker has a distinct color
    and the legend matches the bars. Re-create the schedule / remove-and-re-add a worker and confirm
    that worker keeps their color and no two workers share one.
  - _Requirements: (same)_
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1 — the backend stable-key exposure + the frontend color algorithm change are one
  cohesive change (serialized: FE depends on the new `userId` field).
- **Wave 2:** V1 — browser validation; depends on 1.

## Round 1 — validation results (2026-11-05)

Stack: local Docker rebuilt. Screen: `http://localhost:3000/projects/2/scheduleDesign` as ADMIN.

- **Worker colors keyed by stable user id — ✅ FIXED.** The schedule view API
  (`GET /api/project-schedules?projectId=2`) now returns `userId` on both the worker legend and the
  bars: worker legend `[{projectMemberId:1093, userId:1248}, {projectMemberId:3741, userId:1249}]`,
  and a sample bar carries `userId:1248`. The frontend colors by `userId` (`workerColorFor`), so the
  two workers render two distinct swatches (teal `#0d9488`, orange `#ea580c`) with no collision.
  Because the color key is the stable user id (not the churning `projectMemberId`), removing and
  re-adding a worker — which assigns a new `projectMemberId` — no longer changes that worker's color
  or causes collisions. A property test asserts stability across a simulated membership-id change
  for the same user and no collision across a worker set up to the palette size (all `workerColor`
  tests green).

Result: 1 fixed / 0 failed.
