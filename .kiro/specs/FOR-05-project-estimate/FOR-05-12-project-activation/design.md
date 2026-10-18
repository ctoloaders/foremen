# FOR-05-12 — Project activation (hand over to work) — Design

## Overview

This feature adds an explicit, readiness-gated **activation** of a project (`pre-activation →
ACTIVE`). It reuses the FOR-05-11 readiness stack (`ProjectReadinessService`, the
`ProjectReadinessContributor` beans, `ReadinessMath`) as the source of truth for "fully ready", and
the existing project update/permission/access infrastructure. It introduces **no** new entity and
**no** schema change.

Two entry points converge on one server-side gate:

1. **Readiness widget → "Hand over to work"** (design stage), shown only at 100% + UPDATE.
2. **Project edit form → status = ACTIVE** (ADMIN / MANAGER), which the backend validates and, when
   not ready, answers with a 409 not-ready breakdown; the client then offers **"Hand over to work
   anyway"** (force).

The authoritative rule lives on the server: a `pre-activation → ACTIVE` transition goes through the
readiness gate on **every** path (the dedicated endpoint and the generic `PUT`), computing readiness
over **all** areas (not the caller-scoped aggregate), bypassable only by an ADMIN/MANAGER force.

## Architecture

```
Frontend                                   Backend
────────                                   ───────
ReadinessWidget ("Hand over to work")  ─┐
                                        ├─> POST /api/projects/{id}/activate {force?}
ProjectFormSheet (status=ACTIVE)  ──────┘        │        (ProjectController.activate)
   └─ 409 → "Hand over to work anyway" ──────────┘
                                                  │
                                                  ▼
                                   ProjectActivationService.activate(id, force)
                                     1. load Accessible_Project (404 gate)       [reuse]
                                     2. assert PROJECTS UPDATE (403 gate)        [reuse]
                                     3. if not pre-activation → handle (R1.2)
                                     4. compute FULL readiness over ALL areas    [new helper]
                                     5. if <100% and not (force && ADMIN/MANAGER)
                                          → throw ProjectNotReadyException(areas) → 409
                                     6. set status = ACTIVE, save
                                                  │
generic PUT /api/projects/{id} (status=ACTIVE) ──┘  (ProjectService.validateUpdate delegates
                                                     the pre-activation→ACTIVE case to the same gate)
```

### Key decisions

- **D1 — Dedicated activation endpoint, single gate.** A new `POST /api/projects/{id}/activate`
  carries the activation intent and the optional `force` flag (Requirement 1.5). The generic `PUT`
  does **not** grow a `force` field; instead `ProjectService`'s update guard detects a
  `pre-activation → ACTIVE` change and routes it through the **same** `ProjectActivationService`
  gate (Requirement 7.2), so both paths share one implementation and one 409 outcome.
- **D2 — Full, caller-independent readiness for the gate.** `GET /api/projects/{id}/readiness`
  scopes areas to the caller's READ permission, so its percentage is a per-caller view, not a
  project fact. The gate therefore computes readiness over **all** contributors
  (`ProjectReadinessService.getReadinessAllAreas(projectId)` — a new permission-bypassing variant)
  so "100%" is authoritative (Requirement 2.3). The access gate (404) and the UPDATE gate (403)
  still apply to the activation caller; only the per-area READ scoping is bypassed for the math.
- **D3 — Reuse the readiness area/item shape for the 409 body.** The not-ready breakdown is the list
  of `AreaContribution`s whose `state != DONE`, mapped to the same `AreaView`/`OutstandingItemView`
  shape the readiness widget already renders (Requirement 4.2), so the client reuses its existing
  `describeOutstandingItem` rendering.
- **D4 — Structured 409 via a dedicated exception + advice handler.** Mirrors the
  `WorkReportBatchItemException` precedent: a new `ProjectNotReadyException` carrying the not-ready
  areas, handled by a new `@ExceptionHandler` in `ForemenControllerAdvice` that returns HTTP 409 with
  a custom body (top-level localized message + the areas), resolving message codes to the request
  locale there (Requirements 4.1, 4.4, 8.2).
- **D5 — Force is role-gated, readiness-only.** `force = true` is honored **only** for ADMIN /
  MANAGER and bypasses **only** the readiness gate; the access + UPDATE gates always apply
  (Requirement 3). A non-ADMIN/MANAGER `force` is ignored (treated as a normal gated activation,
  Requirement 3.2).
- **D6 — No notifications, no schema, no new status.** Activation is a synchronous status write;
  nothing is persisted beyond `projects.status`, and no notification is emitted (Requirement 9).

## Backend design

### New components

- `com.foremen.service.ProjectActivationService` (`@Service`, `@Transactional`)
  - `ProjectServiceExtendedModel activate(Long projectId, boolean force)` — the single gate:
    1. `loadAccessibleProject(projectId)` (reuse the ADMIN-bypass access rule used by
       `ProjectReadinessService`/`ProjectScheduleService`; 404 on missing/out-of-scope).
    2. assert the caller holds `PROJECTS` UPDATE on the project (403 otherwise) — reuse the existing
       permission evaluation already applied to the project update path.
    3. if the project is **not** in a pre-activation status: already `ACTIVE` → idempotent success
       (return the project unchanged); any later/terminal status → reject (Requirement 1.2) with a
       suitable illegal-transition `ForemenApiException` (409) — no readiness computed.
    4. compute `readyFully = ProjectReadinessService.getReadinessAllAreas(projectId).overallPercentage() == 100`.
    5. if `!readyFully` and **not** (`force` && callerIsAdminOrManager) → throw
       `ProjectNotReadyException(notReadyAreas)` (areas with `state != DONE`).
    6. set `status = ACTIVE`, `projectDao.save(...)`, return the mapped model.
  - `callerIsAdminOrManager()` — read the caller's role code from the `ROLE_<code>` authority
    (ADMIN bypass already covers ADMIN; MANAGER checked explicitly), mirroring the role-code
    resolution in `ProjectReadinessService`.
- `com.foremen.service.readiness.ProjectReadinessService#getReadinessAllAreas(Long projectId)`
  (new method) — identical to `getReadiness` but **skips** the per-area READ filter (includes every
  contributor), used only by the activation gate (D2). The existing `getReadiness` is unchanged.
- `com.foremen.exception.ProjectNotReadyException` (`extends RuntimeException`) — carries
  `List<AreaContribution> notReadyAreas` (the `state != DONE` areas). Mirrors
  `WorkReportBatchItemException` as a structured, advice-handled error.
- `com.foremen.exception.dto.ProjectNotReadyError` (record) — the 409 body:
  `(String message, List<NotReadyArea> areas)` where
  `NotReadyArea(String key, String name, String state, int percentage, List<String> items)` carries
  **already-localized** strings (name + item messages resolved in the advice).
- `ForemenControllerAdvice#handleProjectNotReady(...)` — new `@ExceptionHandler` returning
  `ResponseEntity.status(CONFLICT).body(ProjectNotReadyError)`; resolves the top-level message code
  (`error.project.not.ready`) and each area name / item message to the request locale (D4).

### Changed components

- `com.foremen.controller.ProjectController` — add
  `@PostMapping("/{id}/activate")` → `activate(@PathVariable Long id, @RequestBody(required=false)
  ActivateProjectRequest request)` returning the updated project DTO. Guarded `@RequiresPermission
  (PROJECTS, UPDATE)` (method-level), consistent with the controller's ABAC. `ActivateProjectRequest`
  is a small record `(boolean force)` (defaults false when body omitted).
- `com.foremen.service.ProjectService` — in the update guard (`validateUpdate` / the status-mapping
  point), detect a `pre-activation → ACTIVE` change and delegate to `ProjectActivationService`'s gate
  (compute full readiness; throw `ProjectNotReadyException` when not ready). The generic `PUT` has no
  `force`, so this path is always the gated (non-force) outcome — an ADMIN/MANAGER who wants to force
  uses the dedicated endpoint (the edit-form client does exactly that on the force action). Every
  other status change is untouched (Requirements 6.3, 7.2).
- `ProjectActivationSignal.onContractSigned(...)` — unchanged (Requirement 7.3).

### Pre-activation statuses

`DRAFT, READY_TO_OFFER, OFFERED, APPROVED` (reuse `ProjectActivationSignal.isEligibleForActivation`
semantics; factor the set into a shared constant so the signal and the new gate agree).

### Backend i18n (new message codes, PL + RU)

| Code | EN meaning |
|------|-----------|
| `error.project.not.ready` | "The project is not ready to be handed over to work." (top-level 409 message) |
| `error.project.status.illegalTransition` | reuse if present, else add: activation from a non-pre-activation, non-ACTIVE status |

Area names and outstanding-item messages reuse the existing FOR-05-11 codes (`workspace.tab.*`,
`readiness.*`) — the advice resolves them (Requirement 8.3). No new entity i18n fields are
introduced (the feature adds no entity/column).

## Frontend design

### API client + types (`src/features/projects`)

- `activateProject(id, force)` → `POST /api/projects/{id}/activate` with body `{ force }`, returning
  `ProjectReadDto`. Added to `projects-api.ts`.
- `useActivateProject()` mutation hook (in `mutation-hooks.ts`) — invalidates `projectKeys` and
  `readinessKeys.view(projectId)` on success (so the widget/stage reflect the new status).
- `ProjectNotReadyError` type mirroring the backend body; `ApiError` (in `src/lib/api-client.ts`) is
  extended to expose the parsed 409 structured body (`notReady?: ProjectNotReadyError`) so the call
  sites can render the breakdown (today `ApiError` carries only `message`/`code`).

### Readiness widget (`ReadinessWidget.tsx`)

- When `view.overallPercentage === 100` **and** `hasPermission('PROJECTS','UPDATE')`, render a
  **"Hand over to work"** button (`data-testid=readiness-activate`). Hidden below 100% or without
  UPDATE (Requirement 5.1–5.3).
- On click → `useActivateProject().mutate({ id, force:false })`; on success show a localized success
  toast (Requirement 5.5). The widget is design-stage only, so after activation the workspace
  re-renders into the execution stage (the widget unmounts) — no in-widget "now active" state needed.
- A 100%-ready activation never returns 409, so the widget action needs no force path.

### Project edit form (`ProjectFormSheet.tsx`)

- The existing status select + `useUpdateProject` PUT stays. On a 409 whose body is a
  `ProjectNotReadyError`, instead of a bare toast, show a **not-ready dialog/section** listing the
  areas + outstanding items (reuse the readiness item rendering) and a **"Hand over to work anyway"**
  button (Requirement 6.4), shown for ADMIN / MANAGER.
- "Hand over to work anyway" → `useActivateProject().mutate({ id, force:true })`; on success toast +
  close/refresh (Requirements 6.5, 6.6).
- Other update errors keep the current toast behavior.

### Frontend i18n (new keys, PL + RU at parity)

| Key | RU | PL |
|-----|-----|-----|
| `readiness.activate.action` | «Отдать в работу» | «Przekaż do realizacji» |
| `projects.activate.anyway` | «Всё равно отдать в работу» | «Mimo to przekaż do realizacji» |
| `projects.activate.success` | «Проект отдан в работу» | «Projekt przekazany do realizacji» |
| `projects.activate.notReadyTitle` | «Проект не готов к работе» | «Projekt nie jest gotowy do realizacji» |
| `projects.activate.notReadyHint` | «Не готовы следующие разделы:» | «Niegotowe obszary:» |

(Exact wording finalized at implementation; keys added to both `ru.json` and `pl.json`, parity
enforced by the existing i18n tests. Area names / item messages reuse existing `workspace.tab.*` /
`readiness.*` keys.)

## Error handling

| Case | Status | Body |
|------|--------|------|
| Not a member / out of scope | 404 | `error.entity.not.found` (byte-identical missing vs out-of-scope) |
| No `PROJECTS` UPDATE | 403 | `error.access.denied` |
| Project not pre-activation and not ACTIVE (e.g. COMPLETED/CANCELLED) | 409 | `error.project.status.illegalTransition` |
| Readiness < 100%, no valid force | 409 | `ProjectNotReadyError` (message `error.project.not.ready` + not-ready areas) |
| Already ACTIVE | 200 | project unchanged (idempotent) |
| Activated (gated or forced) | 200 | updated project DTO |

## Testing strategy

- **Backend unit** — `ProjectActivationServiceTest`: pre-activation + 100% → ACTIVE; pre-activation +
  <100% no force → `ProjectNotReadyException` with the correct not-ready areas (DONE areas excluded);
  <100% + force by ADMIN/MANAGER → ACTIVE; <100% + force by non-ADMIN/MANAGER → still rejected;
  already ACTIVE → idempotent; terminal status → illegal transition; the gate computes over ALL
  areas (a caller with no READ on an incomplete area is still blocked). Mock the readiness service
  and project DAO.
- **Backend readiness** — a focused test that `getReadinessAllAreas` ignores per-area READ scoping
  (includes every contributor) while `getReadiness` still scopes.
- **Backend advice** — `ForemenControllerAdvice` test: `ProjectNotReadyException` → 409 +
  `ProjectNotReadyError` with localized message/areas.
- **Frontend unit** — `useActivateProject` posts `{force}`; `ApiError` parses the 409 `notReady`
  body; `ReadinessWidget` shows the action only at 100% + UPDATE; `ProjectFormSheet` renders the
  not-ready breakdown + "anyway" on a 409 and re-sends with `force:true`.
- **QA-AUTO (FOR-05-11/-12 slice)** — against the live stack: activate at 100% via the widget;
  edit-form status→ACTIVE at <100% → 409 breakdown → "anyway" (ADMIN) → ACTIVE; non-ADMIN/MANAGER
  blocked at <100% even with a crafted force. Follow the `test-cases.md` + `foremen-qa-auto`
  standards.

Per `.kiro/steering/test-execution-rules.md`, run only the affected backend test classes with
`--tests` (never the full suite).

## Impact / compatibility

- Additive on the backend (new service/exception/endpoint + one guarded branch in `ProjectService`
  update). The only behavior change to an existing path is that `PUT /api/projects/{id}` with
  `status = ACTIVE` from a pre-activation status is now readiness-gated (previously unguarded) —
  intended by Requirement 7.
- Additive on the frontend (new action + mutation + 409 handling); no change to unrelated flows.
- No DB migration, no new entity, no notification.
