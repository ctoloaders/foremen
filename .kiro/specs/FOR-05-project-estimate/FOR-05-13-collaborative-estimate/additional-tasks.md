# Additional tasks: FOR-05-13-collaborative-estimate (real-time collaborative estimate editing)

Screen URL: http://localhost:3000/projects/{id}/estimate

> **Status (2026-10-29):** IMPLEMENTED and validated end-to-end with two real users (tasks 1–9, V).
> Only task 10 (`test-cases.md`) remains.
>
> **Implementation note (deviation from the sketch below, simpler and equivalent):** the shared
> draft is keyed by the client `stagedId` (not a computed cell key) and ordered by a server `seq`.
> Replaying the list in `seq` order — what preview/Save already do — yields "distinct cells merge,
> same cell → last write wins" without a separate cell-key fold. Undo/redo stay per-user (remote
> changes never enter your history); Discard reverts only your own edits; Save sends the committed
> `stagedIds` so edits staged by others during the Save survive.
>
> **Deployment note:** any environment running the backend needs Redis (`REDIS_HOST`/`REDIS_PORT`)
> for collaboration. Without it the estimate degrades to single-user mode (health check unaffected,
> Saves still work); set `FOREMEN_COLLAB_ENABLED=false` to turn the feature off explicitly.

Enhancement batch (2026-10-29). Approved by the user (design decisions below). English per the
spec-doc standard; `test-cases.md` scenarios will be in Russian per the test-cases standard.

## Goal (user intent)

When a user edits the estimate matrix, they must see **who else is editing at the same time**, the
currently **staged-but-unsaved changes must live in distributed storage** shared across all editors,
and when **someone saves, the others see the change at once**. Modeled on how Google Sheets does
collaborative editing, right-sized to this app's coarse, cell-keyed staged-edit model and its
explicit Save step.

## How Google Sheets does it (reference model)

- **Presence:** each client holds a persistent server channel; the server tracks who is connected to
  a document and broadcasts a presence roster (name, color, and the cell/range each person has
  selected — the colored cursors).
- **Shared edits:** each edit is sent to the server as a small **operation**; the server holds the
  authoritative document and merges concurrent ops (Operational Transformation) so all clients
  converge; edits are applied optimistically and reconciled when the server's ops arrive.
- **Propagation:** the server pushes merged ops to all other connected clients in near-real-time
  (continuous autosave; no explicit Save).

**Right-sized adaptation for this app** (design decisions, user-approved):

- The estimate's edits are already coarse, **cell-keyed staged operations** (`ASSIGN`, `UNASSIGN`,
  `SET_QUANTITY`, `CLEAR_QUANTITY`, `CHOOSE_CONCRETE`, `ADD_MATERIAL`, `REMOVE_MATERIAL`,
  `SET_MATERIAL_QUANTITY`, `APPLY_PACKAGE`, `APPLY_CHEAPEST`, …) keyed by
  `(workItemId, roomId[, branch, typeId])`. This replaces full OT with an **OT-lite** merge: ops on
  **different keys merge** automatically; two ops on the **same key** resolve **last-write-wins** by
  a server-assigned monotonic sequence number (user decision 3: merge + last-wins).
- The app keeps an explicit **Save** (not continuous autosave): staged ops are shared live, and a
  Save is broadcast so everyone's matrix refreshes at once (user decision 2: presence + shared
  unsaved staged edits + on-save push, all three).
- **Transport (decision 1):** Spring **WebSocket + STOMP** — the closest analog to Sheets' persistent
  channel on this stack; carries presence, per-cell cursors, staged ops, and save events. No new
  container for the socket itself (built into Spring).
- **Distributed store (decision 1):** **Redis** holds the shared presence roster + the shared staged
  op set per estimate (server-authoritative, survives multiple backend instances, natural pub/sub for
  fan-out). Adds a `redis` container to `docker-compose.yml`. Chosen over Postgres-table + in-memory
  specifically because the user asked for genuinely distributed storage, Sheets-style.
- **Precise presence (decision 4):** each client publishes the cell it is focused on; others render
  colored **per-cell** cursor/edit indicators (Sheets' colored cursors).

## Architecture summary

```
Browser A ──WS/STOMP──┐                         ┌── Redis ──┐
Browser B ──WS/STOMP──┤── Spring STOMP broker ──┤  presence  │  (shared, distributed)
Browser C ──WS/STOMP──┘   (relay + app handlers)│  stagedOps │
                                                 └───────────┘
   subscribe: /topic/estimate/{projectId}
   send:      /app/estimate/{projectId}/presence   (focus cell, heartbeat)
              /app/estimate/{projectId}/op          (a staged op; server assigns seq, stores, fans out)
              (Save stays the existing REST POST /project/{id}/assignments; its success publishes a
               save event on the topic so all clients refetch/reconcile)
```

- **Estimate session key:** `estimate:{projectId}` (one collaborative room per project estimate).
- **Shared staged op log:** a Redis structure (e.g. a sorted set / stream keyed by the estimate)
  holding ops with `seq`, `authorUserId`, `kind`, cell key, payload. The authoritative merged staged
  set = fold of the op log with same-key last-wins. TTL'd so an abandoned draft expires.
- **Presence roster:** a Redis hash `presence:estimate:{projectId}` → per-user `{name, color,
  focusedCell, lastHeartbeat}`; entries expire on missed heartbeats / disconnect.
- **Save reconciliation:** on a successful Save (existing `EstimateAssignmentService.saveAndAssemble`),
  publish a `SAVED` event on the topic with the new server version; clients refetch the matrix and
  clear the now-committed ops from the shared staged log.

### i18n (decision 5: PL + RU, no new backend entity i18n fields)

Frontend strings (both `pl.json` and `ru.json` at parity):
- `estimate.collab.presence.editingNow` ("{{count}} people editing"), `...presence.alsoEditing`
  ("Also editing: {{names}}"), `...presence.you`.
- `estimate.collab.cellBusy` (per-cell cursor tooltip "{{name}} is editing this cell").
- `estimate.collab.savedByOther` ("{{name}} saved changes — refreshed").
- `estimate.collab.stagedByOther` (a cell's shared-unsaved indicator tooltip "{{name}} staged a
  change here").
- `estimate.collab.connectionLost` / `...reconnecting` (socket status).

No new backend i18n entity fields (presence names come from the existing user record; colors are
client-derived).

### Notifications (decision 6: none)

No bell notifications. All collaboration cues are **in-session, real-time** UI affordances only:
presence roster, per-cell colored cursors, shared-staged-cell indicators, and the on-save refresh.
Justification: these are transient presence/liveness signals, not persisted per-recipient domain
notifications; persisting them in the bell would be noise. (The user explicitly asked for "only
changes in staged cells", i.e. live in-matrix indicators, not notifications.)

## Tasks

- [x] 1. Infra: add Redis to the stack
  - Add a `redis` service to `docker-compose.yml` (`redis:7-alpine`, healthcheck). Add Spring Data
    Redis + Lettuce to `foremen-backend` and the connection config (host `redis`, profile `docker`;
    localhost for local). No domain data in Redis — only ephemeral collaboration state.
  - _Requirements: distributed store (decision 1)_
  - _Depends on: none_

- [x] 2. Backend: Spring WebSocket + STOMP endpoint
  - Enable STOMP over WebSocket (`/ws` endpoint, SockJS fallback), a simple broker relay on
    `/topic/**`, app destinations `/app/estimate/{projectId}/**`. Authenticate the socket handshake
    with the existing JWT (reuse the HTTP auth filter / a handshake interceptor) so the connected
    principal = the acting user. Enforce the `ESTIMATE READ` permission + project scope on subscribe
    (a caller may only join an estimate room for a project they can access).
  - _Requirements: transport (decision 1); access parity with the REST estimate endpoints_
  - _Depends on: 1_

- [x] 3. Backend: presence service (Redis-backed roster)
  - A `EstimatePresenceService` maintaining `presence:estimate:{projectId}` in Redis: join on
    subscribe, heartbeat refresh, leave on disconnect / TTL expiry. Assign each user a stable color.
    Broadcast the roster (name, color, focusedCell) to `/topic/estimate/{projectId}` on any change.
  - _Requirements: presence (decision 2-i), precise focus cell (decision 4)_
  - _Depends on: 2_

- [x] 4. Backend: shared staged-op log (Redis) with sequencing + merge
  - A `EstimateDraftOpService` holding the shared staged op set per estimate in Redis (stream/sorted
    set): accept an incoming op on `/app/estimate/{projectId}/op`, assign a monotonic server `seq`,
    store it, and fan it out on the topic. The authoritative staged set = fold of the log with
    **same-cell-key last-wins** and **distinct keys merged** (OT-lite, decision 3). Expose a snapshot
    (current folded staged set + last seq) a newly-joining client fetches to catch up. TTL the log so
    an abandoned draft expires. Validate each op's shape against the existing `StagedEdit` kinds.
  - _Requirements: shared unsaved staged edits (decision 2-ii), merge + last-wins (decision 3)_
  - _Depends on: 2_

- [x] 5. Backend: broadcast Save events + reconcile
  - On a successful estimate Save (hook `EstimateAssignmentService.saveAndAssemble` /
    `applyAssignments`), publish a `SAVED` event (new server version + the committed op seqs) on
    `/topic/estimate/{projectId}`. The draft-op service prunes the committed ops from the shared log
    so post-save the shared staged set reflects only still-uncommitted ops. (Save stays the existing
    REST call; only the broadcast is new.)
  - _Requirements: on-save push (decision 2-iii)_
  - _Depends on: 4_

- [x] 6. Frontend: STOMP client + estimate collaboration channel
  - Add a STOMP/WebSocket client (`@stomp/stompjs` + SockJS) that connects with the JWT, subscribes to
    `/topic/estimate/{projectId}` on entering the Estimate tab, and disconnects on leave. A
    `useEstimateCollaboration(projectId)` hook exposes: presence roster, the shared staged op stream,
    and save events; it sends presence/focus + local ops.
  - _Requirements: transport (decision 1)_
  - _Depends on: 2, 3, 4, 5_

- [x] 7. Frontend: sync the staged-edit store to the shared log
  - Rework the estimate staged-edit store (today per-user zustand + localStorage) so a local op is
    (a) applied optimistically, (b) sent over the socket, and (c) reconciled when the server echoes it
    with a `seq`; incoming remote ops from others are folded in with the same merge + last-wins rule
    as the backend. A newly-joined client seeds from the server snapshot (task 4). On a `SAVED` event,
    refetch the matrix and drop committed ops. Keep localStorage only as an offline/degraded fallback
    when the socket is down.
  - _Requirements: shared unsaved staged edits + on-save refresh (decision 2), merge + last-wins (3)_
  - _Depends on: 6_

- [x] 8. Frontend: presence UI — roster banner + per-cell colored cursors
  - A presence banner on the Estimate tab ("{{count}} editing" + colored name chips, "you" marked).
    Per-cell: render a colored cursor/outline on the cell another user is focused on
    (`estimate.collab.cellBusy` tooltip), and a shared-unsaved indicator on a cell another user has
    staged but not saved (`estimate.collab.stagedByOther`). Each user's color is consistent across
    the banner and their cursors. Socket status cue on disconnect/reconnect.
  - _Requirements: presence (decision 2-i), precise per-cell (decision 4), staged-cell cues (decision 6)_
  - _Depends on: 6, 7_

- [x] 9. i18n keys (PL + RU)
  - Add all `estimate.collab.*` keys to `pl.json` and `ru.json` at parity. IMPORTANT: merge into any
    existing `estimate` object; never add a duplicate top-level key (a duplicate JSON key is silently
    dropped by the bundler — see the FOR-05-09 note).
  - _Requirements: decision 5_
  - _Depends on: none_

- [ ] 10. Author test-cases.md
  - Create `test-cases.md` per `.kiro/steering/test-cases.md`. Two-client scenarios driven by the
    browser engine (two sessions): presence roster shows both users + colors; focusing a cell shows
    the other user's colored cursor; a staged (unsaved) op by client A appears in client B's matrix as
    a shared-staged indicator; concurrent same-cell ops resolve last-wins; distinct-cell ops merge; a
    Save by A refreshes B at once and clears committed staged indicators; disconnect/reconnect
    behavior. Backend: API/socket tests for op sequencing, merge fold, presence TTL, and save-event
    pruning against the Dockerized stack (incl. Redis). Repeatability via a generated draft project +
    run-id + teardown; regression group for the existing single-user staged-edit Save/undo path.
  - _Requirements: all above_
  - _Depends on: 1, 2, 3, 4, 5, 6, 7, 8, 9_

- [x] V. Validate end-to-end in the browser (two clients)
  - Bring up the stack incl. Redis. Open the estimate as two different users (two browser contexts):
    confirm presence, per-cell cursors, live shared staged edits, concurrent-edit merge + last-wins,
    and instant refresh on the other's Save.
  - _Requirements: as all tasks_
  - _Depends on: 1, 2, 3, 4, 5, 6, 7, 8, 9_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1 (Redis infra), 9 (i18n keys) — independent.
- **Wave 2:** 2 (STOMP endpoint) — depends on 1.
- **Wave 3 (parallel):** 3 (presence service), 4 (draft-op log) — both depend on 2, touch different
  Redis structures / handlers.
- **Wave 4:** 5 (save broadcast + reconcile) — depends on 4.
- **Wave 5:** 6 (FE STOMP client/hook) — depends on 2, 3, 4, 5.
- **Wave 6 (parallel):** 7 (store sync), 8 (presence UI) — both depend on 6 (serialize if they edit
  the same store files; otherwise parallel: 7 is the store, 8 is presentation).
- **Wave 7:** 10 — test-cases.md depends on all implementation.
- **Wave 8:** V — two-client browser validation depends on all implementation.
