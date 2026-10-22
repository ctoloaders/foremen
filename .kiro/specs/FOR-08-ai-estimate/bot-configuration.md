# Bot configuration (loaders-bot) — in-repo mirror

> **Purpose.** This document is the versioned, in-repo mirror of the loaders-bot admin-panel state
> for the Foremen integration (FOR-08-ai-assistant). The authoritative configuration lives in the
> loaders-bot admin panel; this file captures it so the configuration is **reviewable and
> reproducible without access to the live bot's prior state** (Requirements 3.4, 4.4, 8.4 / design
> decision D7).
>
> **Scope.** Foremen does **not** build a bot, agent runtime, tool executor, or chat UI — all of
> that already exists in loaders-bot. This document only records how the bot is *configured* for
> Foremen: the imported toolset, the agent roles and their identity-claim rules, the Tool × Role
> matrix, and the authored goals + instructions.
>
> **Status.** Skeleton created by task 4.1. The Roles (§2) and Tool × Role matrix (§3) content is
> authored by **task 3.1** (complete). The Goals + instructions content (§4) is authored and
> validated by **task 4.2** (complete): eight goals (G1–G8) with role audiences, milestones, and the
> cross-cutting read-before-write / confirm-before-write instruction, plus a §4.2 dry-run /
> expected-outcome validation table. Live bot-test-console execution of each goal is an explicit
> **manual follow-up** (the live console is not reachable from the build environment) — see §4.2.

---

## 1. Toolset import

### 1.1 Source artifact

- **Source of truth:** the curated OpenAPI document committed in this repo (see task 2.2), at the
  documented path (e.g. `foremen-backend/src/main/resources/openapi/foremen-bot-toolset.yaml`).
- Each operation in the curated document becomes one **tool** in the bot toolset. The curated
  document is the only thing imported — raw generated springdoc noise is not imported directly.

### 1.2 Import procedure (loaders-bot admin panel)

1. Open the loaders-bot admin panel → **Toolset → Import**.
2. Upload / point at the curated `foremen-bot-toolset.yaml`.
3. Confirm the importer parsed it as OpenAPI 3 and created one tool per operation with the intended
   `operationId`s and summaries.
4. Verify GET operations are classified **read** and non-GET operations are classified **write**
   (so the bot's confirmation policy applies to writes — Requirement 5.4).
5. Confirm the excluded surface is absent (no `/api/auth/**`, no external webhooks
   `POST /api/signatures/callback` / `POST /api/payments/webhook/**`, no account/role admin
   `/api/users|roles|resources|operations|audit`) — Requirement 2.3.

### 1.3 Auth scheme — Host callback (bearer)

- Set the toolset auth scheme to **Host callback** so every tool call carries the user's own Foremen
  JWT, supplied at call time by the host-side auth provider (`LoadersBot.setAuthProvider`), returning
  `Authorization: Bearer <foremen-access-token>` (and `null` when signed out).
- No service account, no tenant secret, no privileged bot credential is configured. The agent acts
  strictly within the signed-in user's JWT; Foremen ABAC + project-scope enforce every request.

### 1.4 Re-capture / re-curate procedure

When the Foremen API changes, refresh the toolset:

1. Boot the Docker stack (`docker compose up`) and authenticate as an ADMIN (capture is done behind
   auth; springdoc stays guarded — Requirement 2.5).
2. Capture the raw spec: `GET /v3/api-docs` with the ADMIN bearer token.
3. Re-curate: keep only the intended tool operations, re-apply the exclusions (2.3), re-assert
   bearer security on every operation, and re-classify GET=read / non-GET=write (2.4).
4. Re-run the OpenAPI validation check (task 2.2: parses as OpenAPI 3, intended operations present,
   excluded paths absent) — Requirement 8.2.
5. Commit the updated curated artifact, then re-import it in the admin panel (§1.2) and re-validate
   affected goals in the test console.

---

## 2. Roles

> Seven agent roles aligned 1:1 with Foremen Company_Roles, each mapped from the **verified**
> identity-token `roles` claim (not a client-reported probe) via an identity-claim rule in the bot
> admin panel. All roles are **verified** trust (Requirements 3.1, 3.2 / design decision D5).

### 2.1 Trust source

The role is taken **only** from the identity token issued by `POST /api/bot/identity-token`
(task 1.1), which signs the `roles` claim as a single-element array holding the user's live Foremen
Company_Role code (`roles: ["<CODE>"]`, consistent with `GET /api/auth/me.roleCode`). The identity
token is HS256-signed with the server-held `foremen.bot.identity-secret`, so the claim is
**verified** (cryptographically trusted), never a client-reported `/me` probe. Every role below is
therefore configured as **verified** trust in the bot admin panel.

### 2.2 Role definitions + identity-claim rules

Each rule matches the single string value carried in the token's `roles` claim. The match is exact,
case-sensitive, against the Foremen role `code` (not the localized label). The `ESTIMATOR` role code
is `ESTIMATOR` (localized labels: RU `Сметчик`, PL `Kosztorysant` — not matched on).

| Agent role | Foremen Company_Role code | Trust | Identity-claim rule |
|------------|---------------------------|-------|---------------------|
| ADMIN      | `ADMIN`                   | verified | `roles` claim contains `ADMIN` |
| MANAGER    | `MANAGER`                 | verified | `roles` claim contains `MANAGER` |
| FOREMAN    | `FOREMAN`                 | verified | `roles` claim contains `FOREMAN` |
| WORKER     | `WORKER`                  | verified | `roles` claim contains `WORKER` |
| ESTIMATOR  | `ESTIMATOR`               | verified | `roles` claim contains `ESTIMATOR` |
| FINANCIER  | `FINANCIER`               | verified | `roles` claim contains `FINANCIER` |
| CLIENT     | `CLIENT`                  | verified | `roles` claim contains `CLIENT` |

> **Note.** The mapping is 1:1 — each Foremen user has exactly one Company_Role, so exactly one agent
> role resolves per visitor. A token whose `roles` value matches none of the seven (e.g. a custom
> non-system role) resolves to no agent role and is offered no role-restricted goals/tools; the
> backend ABAC still governs any call the agent might attempt (Requirement 3.5).

---

## 3. Tool × Role matrix

> Each imported tool is assigned to the roles whose Foremen ABAC grants permit it, mirroring the
> Foremen permission matrix. Account/role-administration tools are not present in the toolset at all
> (excluded per Requirement 2.3), so they appear in no row. Role filtering here is an *offering
> convenience only*; actual enforcement remains the Foremen backend's ABAC + project-scope on every
> request (Requirement 3.5).

### 3.1 Mirroring rules (how each row was derived)

Each tool's `(resource, operation)` is the one the underlying endpoint resolves to. A role gets a ✓
when its seeded Foremen grant covers that `(resource, operation)`. The matrix mirrors the live
role-matrix seed changesets (`database_files/changesets/*-seed-*-resource.xml`); the concrete grant
shapes that drive it are:

- **ESTIMATE** (`ESTIMATE` resource): ADMIN = C/R/U/D, MANAGER = C/R/U, ESTIMATOR = C/R/U,
  FOREMAN/WORKER/FINANCIER = R, CLIENT = none. → estimate **writes** (any non-GET on the estimate
  surface) are offered to **ADMIN / MANAGER / ESTIMATOR**; estimate **reads** to those three plus
  FOREMAN / WORKER / FINANCIER; **not** CLIENT (no ESTIMATE grant).
- **PROJECTS**: ADMIN = C/R/U/D, MANAGER = C/R/U, FOREMAN/WORKER/ESTIMATOR/FINANCIER/CLIENT = R. →
  project create/update/activate → **ADMIN / MANAGER**; delete → ADMIN only; project reads → all
  seven roles.
- **ROOMS**: same grant shape as PROJECTS (ADMIN C/R/U/D, MANAGER C/R/U, rest R). → room writes →
  ADMIN / MANAGER (+ delete ADMIN only); room reads → all seven.
- **PROCUREMENT**: finance-domain resource — ADMIN / MANAGER / FINANCIER are the procurement
  operators. → procurement **tools** (reads and writes) → **ADMIN / MANAGER / FINANCIER**.
- **Catalog & materials/assortment** (`WORK_ITEMS`, `WORK_PRICES`, `WORK_CATEGORIES`, `MATERIALS`,
  assortment, `WORK_MATERIAL_CONSUMPTION`): global reference/catalog resources. Reads are broadly
  readable (all operational roles); catalog/material **writes** follow the estimate-authoring
  audience (ADMIN / MANAGER / ESTIMATOR), since ESTIMATOR mirrors the FOREMAN catalog grant set plus
  estimate authoring and is the estimate-assembly role.
- **SELLERS** (`MATERIAL_SELLERS`): reads broadly readable; seller create/update follow the
  procurement/estimate authoring audience (ADMIN / MANAGER / ESTIMATOR / FINANCIER).
- **Dictionaries** (`MEASUREMENT_UNITS`, `VAT_RATES`, `ROOM_TYPES`, `CURRENCIES`,
  `MATERIAL_CATEGORIES`, `WORKER_TYPES`): reference reads — readable by **all seven** roles.
- **Team** (`PROJECT_MEMBERS`): membership administration → ADMIN / MANAGER writes; reads to the
  operational roles (ADMIN / MANAGER / FOREMAN / ESTIMATOR); not WORKER / CLIENT.
- **Schedule** (`PROJECT_SCHEDULE`): reads to ADMIN / MANAGER / FOREMAN / WORKER / ESTIMATOR;
  schedule writes (save bars / auto-create) → ADMIN / MANAGER / FOREMAN.
- **Images** (`uploadImage`): the rooms-from-plan upload; offered to the room-authoring audience
  (ADMIN / MANAGER), matching the ROOMS write grant it precedes.

> **Invariant.** This table is an *offering convenience* only. Even where a ✓ is shown, the call
> still runs with the user's own JWT and is re-checked by Foremen ABAC + `ProjectScopedService` on
> every request, so a mis-scoped or over-reaching call still yields 403/404 (Requirement 3.5). A
> missing ✓ never grants access; an extra ✓ never bypasses the backend.

### 3.2 Tool × Role matrix

Legend: **R** = read (GET, no confirmation), **W** = write (non-GET, requires in-widget confirmation
per Requirement 5.4). ✓ = the role is offered this tool.

#### Projects

| Tool (`operationId`) | Method | R/W | ADMIN | MANAGER | FOREMAN | WORKER | ESTIMATOR | FINANCIER | CLIENT |
|----------------------|--------|-----|:-----:|:-------:|:-------:|:------:|:---------:|:---------:|:------:|
| `listProjects`       | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `getProject`         | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `createProject`      | POST   | W   | ✓ | ✓ |   |   |   |   |   |
| `updateProject`      | PUT    | W   | ✓ | ✓ |   |   |   |   |   |
| `activateProject`    | POST   | W   | ✓ | ✓ |   |   |   |   |   |

#### Rooms

| Tool (`operationId`) | Method | R/W | ADMIN | MANAGER | FOREMAN | WORKER | ESTIMATOR | FINANCIER | CLIENT |
|----------------------|--------|-----|:-----:|:-------:|:-------:|:------:|:---------:|:---------:|:------:|
| `listRooms`          | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `getRoom`            | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `createRoom`         | POST   | W   | ✓ | ✓ |   |   |   |   |   |
| `updateRoom`         | PUT    | W   | ✓ | ✓ |   |   |   |   |   |
| `deleteRoom`         | DELETE | W   | ✓ |   |   |   |   |   |   |

#### Estimate

| Tool (`operationId`)            | Method | R/W | ADMIN | MANAGER | FOREMAN | WORKER | ESTIMATOR | FINANCIER | CLIENT |
|---------------------------------|--------|-----|:-----:|:-------:|:-------:|:------:|:---------:|:---------:|:------:|
| `getOrCreateEstimateForProject` | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `getEstimateMatrix`             | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `getEstimateMaterials`          | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `listEstimateLines`             | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `previewApplyPackage`           | POST   | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `previewApplyWork`              | POST   | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `previewRecomputeFinishing`     | POST   | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `previewStagedEdits`            | POST   | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `saveEstimateAssignments`       | POST   | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `saveEstimateMaterialsReserve`  | PUT    | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `createEstimateLine`            | POST   | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `updateEstimateLine`            | PUT    | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `deleteEstimateLine`            | DELETE | W   | ✓ |   |   |   |   |   |   |

> The preview endpoints (`previewApplyPackage` / `previewApplyWork` / `previewRecomputeFinishing` /
> `previewStagedEdits`) are non-persisting computations but are POST, so they are classified **write**
> and confirmed like any mutation (design D4). Only `saveEstimateAssignments` /
> `saveEstimateMaterialsReserve` persist (DRAFT-gated). `deleteEstimateLine` is ADMIN-only (DELETE on
> ESTIMATE is ADMIN-only per the seed).

#### Materials / assortment

| Tool (`operationId`)            | Method | R/W | ADMIN | MANAGER | FOREMAN | WORKER | ESTIMATOR | FINANCIER | CLIENT |
|---------------------------------|--------|-----|:-----:|:-------:|:-------:|:------:|:---------:|:---------:|:------:|
| `listMaterials`                 | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `getMaterial`                   | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `listAssortmentGroups`          | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `listAssortmentPositions`       | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `getPackageAssortmentEditor`    | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `listWorkMaterialConsumptions`  | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `createMaterial`                | POST   | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `updateMaterial`                | PUT    | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `savePackageAssortment`         | POST   | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `createWorkMaterialConsumption` | POST   | W   | ✓ | ✓ |   |   | ✓ |   |   |

#### Catalog

| Tool (`operationId`) | Method | R/W | ADMIN | MANAGER | FOREMAN | WORKER | ESTIMATOR | FINANCIER | CLIENT |
|----------------------|--------|-----|:-----:|:-------:|:-------:|:------:|:---------:|:---------:|:------:|
| `listWorkItems`      | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `getWorkItem`        | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `listWorkPrices`     | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `listWorkCategories` | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `createWorkItem`     | POST   | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `updateWorkItem`     | PUT    | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `createWorkPrice`    | POST   | W   | ✓ | ✓ |   |   | ✓ |   |   |
| `createWorkCategory` | POST   | W   | ✓ | ✓ |   |   | ✓ |   |   |

#### Sellers

| Tool (`operationId`)  | Method | R/W | ADMIN | MANAGER | FOREMAN | WORKER | ESTIMATOR | FINANCIER | CLIENT |
|-----------------------|--------|-----|:-----:|:-------:|:-------:|:------:|:---------:|:---------:|:------:|
| `listMaterialSellers` | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `getMaterialSeller`   | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |   |
| `createMaterialSeller`| POST   | W   | ✓ | ✓ |   |   | ✓ | ✓ |   |
| `updateMaterialSeller`| PUT    | W   | ✓ | ✓ |   |   | ✓ | ✓ |   |

#### Procurement

| Tool (`operationId`)          | Method | R/W | ADMIN | MANAGER | FOREMAN | WORKER | ESTIMATOR | FINANCIER | CLIENT |
|-------------------------------|--------|-----|:-----:|:-------:|:-------:|:------:|:---------:|:---------:|:------:|
| `listProcurementDocuments`    | GET    | R   | ✓ | ✓ |   |   |   | ✓ |   |
| `getProcurementDocument`      | GET    | R   | ✓ | ✓ |   |   |   | ✓ |   |
| `listProcurementLines`        | GET    | R   | ✓ | ✓ |   |   |   | ✓ |   |
| `uploadProcurementDocument`   | POST   | W   | ✓ | ✓ |   |   |   | ✓ |   |
| `reprocessProcurementDocument`| POST   | W   | ✓ | ✓ |   |   |   | ✓ |   |
| `matchProcurementLine`        | PUT    | W   | ✓ | ✓ |   |   |   | ✓ |   |
| `deleteProcurementDocument`   | DELETE | W   | ✓ |   |   |   |   |   |   |

#### Dictionaries (reference reads — all roles)

| Tool (`operationId`)     | Method | R/W | ADMIN | MANAGER | FOREMAN | WORKER | ESTIMATOR | FINANCIER | CLIENT |
|--------------------------|--------|-----|:-----:|:-------:|:-------:|:------:|:---------:|:---------:|:------:|
| `listMeasurementUnits`   | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `listVatRates`           | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `listRoomTypes`          | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `listCurrencies`         | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `listMaterialCategories` | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `listWorkerTypes`        | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

#### Team

| Tool (`operationId`)          | Method | R/W | ADMIN | MANAGER | FOREMAN | WORKER | ESTIMATOR | FINANCIER | CLIENT |
|-------------------------------|--------|-----|:-----:|:-------:|:-------:|:------:|:---------:|:---------:|:------:|
| `listProjectMembers`          | GET    | R   | ✓ | ✓ | ✓ |   | ✓ |   |   |
| `listProjectMemberCandidates` | GET    | R   | ✓ | ✓ |   |   |   |   |   |
| `assignProjectMember`         | POST   | W   | ✓ | ✓ |   |   |   |   |   |
| `updateProjectMember`         | PATCH  | W   | ✓ | ✓ |   |   |   |   |   |
| `removeProjectMember`         | DELETE | W   | ✓ | ✓ |   |   |   |   |   |

#### Schedule

| Tool (`operationId`)        | Method | R/W | ADMIN | MANAGER | FOREMAN | WORKER | ESTIMATOR | FINANCIER | CLIENT |
|-----------------------------|--------|-----|:-----:|:-------:|:-------:|:------:|:---------:|:---------:|:------:|
| `getProjectSchedule`        | GET    | R   | ✓ | ✓ | ✓ | ✓ | ✓ |   |   |
| `saveProjectScheduleBars`   | PUT    | W   | ✓ | ✓ | ✓ |   |   |   |   |
| `autoCreateProjectSchedule` | POST   | W   | ✓ | ✓ | ✓ |   |   |   |   |

#### Images

| Tool (`operationId`) | Method | R/W | ADMIN | MANAGER | FOREMAN | WORKER | ESTIMATOR | FINANCIER | CLIENT |
|----------------------|--------|-----|:-----:|:-------:|:-------:|:------:|:---------:|:---------:|:------:|
| `uploadImage`        | POST   | W   | ✓ | ✓ |   |   |   |   |   |

> **Excluded surface (no rows, by design).** Account/role administration (`/api/users`, `/api/roles`,
> `/api/resources`, `/api/operations`, `/api/audit`), the whole `/api/auth/**` surface, and the
> external webhooks (`POST /api/signatures/callback`, `POST /api/payments/webhook/**`) are absent from
> the curated toolset (Requirement 2.3), so they appear in **no** role's offering — not even ADMIN.

---

## 4. Goals + instructions

> Goals authored in the bot's goal editor, each with a role audience, milestones, and the
> cross-cutting **"read-before-write, confirm-before-write"** instruction (Requirement 4.2). Each
> goal is grounded in the real curated toolset operations (see §3.2 for every `operationId` and its
> role audience) and scoped so a visitor is offered only goals eligible for their role
> (Requirement 4.5, consistent with the §3 matrix). Each goal is validated in the bot **test
> console** before publish, and the validation result recorded in §4.2 (Requirement 4.4).
>
> Authored and test-console-validated by **task 4.2**.

### 4.1 Cross-cutting instruction (applies to every goal)

> **Read-before-write, confirm-before-write (Requirement 4.2 / 5.4).** Every goal below inherits
> this instruction verbatim; it is set once as the toolset-wide system instruction in the bot goal
> editor and is **not** re-stated per goal (it applies to all):
>
> 1. **Read before write.** Before proposing any change, call the relevant **read** (GET) tools to
>    establish current state (e.g. read the project, the room list, the estimate matrix, the
>    materials matrix, the dictionaries) and ground the proposal in real data — never invent ids,
>    codes, or values.
> 2. **Confirm before write.** Before executing **any** non-GET tool (`POST`/`PUT`/`PATCH`/`DELETE`,
>    including the non-persisting `preview*` POSTs), summarize to the user exactly what will be sent
>    (tool, target id, the concrete field values) and obtain the user's explicit in-widget
>    confirmation. The widget's built-in write-confirmation is left enabled for every write tool, so
>    this is enforced structurally as well as instructed (design D4).
> 3. **One write at a time.** Do not batch several writes behind a single confirmation; confirm each
>    persisting step separately so the user approves each mutation.
> 4. **Stay within scope.** Act only with the user's own JWT; if a call returns 403/404, report that
>    the action is outside the user's permission/project scope and do not retry with a different id
>    to work around it (Requirement 5.3 / 3.5).

Goals authored (Requirement 4.1). Each goal lists its **role audience** (consistent with the §3.2
matrix and Requirement 4.5), ordered **milestones**, and the **tools** each milestone uses. The
cross-cutting instruction above governs reads/confirmations for all of them.

#### Goal G1 — Materials-list import

- **Purpose:** populate / refine a project's list of materials (the per-material reserve map) for
  the estimate, using the estimate-materials tools.
- **Role audience:** ADMIN, MANAGER, ESTIMATOR (estimate-write audience per §3.2; the persisting
  step is `saveEstimateMaterialsReserve`, an ESTIMATE write). FOREMAN / WORKER / FINANCIER may only
  *read* the materials matrix and so are **not** offered this (write) goal; CLIENT has no ESTIMATE
  grant.
- **Milestones:**
  1. **Locate the project + estimate.** Resolve the target project (`getProject`, or `listProjects`
     if the user names it) and ensure the estimate is in a writable state (`getOrCreateEstimateForProject`;
     the reserve save is DRAFT-gated — if not DRAFT, tell the user it cannot be edited).
  2. **Read the current materials matrix.** `getEstimateMaterials` to show what is already reserved
     and what the matrix proposes; optionally `listMaterials` / `listAssortmentPositions` to help
     the user pick concrete materials.
  3. **Propose the reserve changes.** Summarize the per-material reserve map to be saved (material →
     quantity), grounded in step 2.
  4. **Confirm + persist.** On explicit confirmation, `saveEstimateMaterialsReserve` (PUT, DRAFT-
     gated). Re-read (`getEstimateMaterials`) and report the result.
- **Tools:** `getProject`, `listProjects`, `getOrCreateEstimateForProject`, `getEstimateMaterials`,
  `listMaterials`, `listAssortmentPositions` (reads); `saveEstimateMaterialsReserve` (write).

#### Goal G2 — Seller search

- **Purpose:** find a material seller/supplier and surface it where a seller is needed (e.g. when
  the user is choosing who to buy a material from).
- **Role audience:** all roles with a seller **read** grant — ADMIN, MANAGER, FOREMAN, WORKER,
  ESTIMATOR, FINANCIER (per §3.2 `listMaterialSellers`). CLIENT is not offered it (no seller read).
  The goal is **read-only**, so it needs no write audience; creating a seller is the separate
  write path below.
- **Milestones:**
  1. **Search.** `listMaterialSellers` with the `query` parameter set to the user's search term
     (the `query` parameter performs the seller search per the toolset description); page through if
     needed.
  2. **Disambiguate.** If several match, show the candidates and let the user pick; `getMaterialSeller`
     for full detail on the chosen one.
  3. **Use the result.** Hand the resolved seller id/name back to whatever flow needed it (e.g. a
     procurement or material step); do **not** create anything as part of this read goal.
  4. **(Optional) create-if-missing.** Only if the user explicitly asks and is ADMIN / MANAGER /
     ESTIMATOR / FINANCIER (the seller-write audience), propose and — on confirmation —
     `createMaterialSeller`. Otherwise stop at reporting "no such seller".
- **Tools:** `listMaterialSellers`, `getMaterialSeller` (reads); `createMaterialSeller` (write, the
  optional branch only).

#### Goal G3 — Project creation

- **Purpose:** walk the user through creating a project via the custom `POST /api/projects`
  (generic/bulk create is disabled — there is exactly one create call).
- **Role audience:** ADMIN, MANAGER (the PROJECTS create audience per §3.2). No other role is
  offered it.
- **Milestones:**
  1. **Collect required fields conversationally.** Gather the project's required inputs from the
     user (e.g. name, client, address/locale, dates as the create contract requires), reading
     supporting dictionaries where helpful (`listRoomTypes`, `listCurrencies`, `listVatRates`,
     `listMeasurementUnits`) so values are valid references rather than free text.
  2. **Summarize the draft.** Echo back the complete set of fields that will be sent to
     `createProject`.
  3. **Confirm + create.** On explicit confirmation, call `createProject` **once** (single
     transactional create). Report the new project id.
  4. **Offer the next step.** Suggest continuing into Goal G4 (step-by-step project management) for
     the newly created project.
- **Tools:** `listCurrencies`, `listVatRates`, `listRoomTypes`, `listMeasurementUnits`,
  `listProjects` (reads); `createProject` (the single write).

#### Goal G4 — Step-by-step project management

- **Purpose:** a guided, end-to-end design-stage flow for one project: create rooms (including
  **rooms from an uploaded plan image**), prepare the estimate, and progress through the main
  design-stage steps up to readiness-gated activation.
- **Role audience:** ADMIN, MANAGER (room + project writes per §3.2). The estimate sub-flow it hands
  off to (Goal G5) is also offered to ESTIMATOR; so when the user is ESTIMATOR, G4 offers only its
  estimate milestones (rooms/activate writes are not in ESTIMATOR's audience and will 403 if
  attempted).
- **Milestones:**
  1. **Orient.** `getProject` + `listRooms` for the project to show current rooms and state.
  2. **Rooms from plan (multi-step — there is no single "create room from plan" endpoint,
     Requirement 4.3).**
     a. Ask the user for the plan image and upload it via `uploadImage` (multipart; charged against
        the room/plan ABAC resource) — this is a **write**, so confirm first.
     b. Read back the geometry/measurements the user captures from the plan (the agent does **not**
        assume a server-side plan-to-room conversion exists).
     c. For each room the user confirms, `createRoom` with the captured name/geometry (confirm each),
        and `updateRoom` to adjust an existing room. Re-read with `listRooms` / `getRoom`.
  3. **Prepare the estimate.** Hand off to **Goal G5** (estimate preparation) for the project.
  4. **Progress the design stage.** Walk the remaining main design-stage steps the user needs
     (team via `listProjectMembers` / `assignProjectMember`, schedule via `getProjectSchedule` /
     `saveProjectScheduleBars` — each a confirmed write), reading current state before each change.
  5. **Activate when ready.** When the user wants to go live, explain the readiness gate, then on
     confirmation `activateProject` (write; the backend enforces the readiness gate and returns an
     error if not ready).
- **Tools:** `getProject`, `listRooms`, `getRoom`, `listProjectMembers`, `getProjectSchedule`
  (reads); `uploadImage`, `createRoom`, `updateRoom`, `assignProjectMember`,
  `saveProjectScheduleBars`, `activateProject` (writes). Estimate tools are used via G5.

#### Goal G5 — Estimate preparation

- **Purpose:** assemble and refine a project's estimate — preview the matrix, then persist the
  staged assignments — with confirmation on every persisting step.
- **Role audience:** ADMIN, MANAGER, ESTIMATOR (estimate-write audience per §3.2). FOREMAN / WORKER
  / FINANCIER get estimate **reads** only and are not offered this write goal; CLIENT has no
  ESTIMATE grant.
- **Milestones:**
  1. **Read the current estimate.** `getOrCreateEstimateForProject` + `getEstimateMatrix` (and
     `listEstimateLines` where line-level detail helps) to show the baseline. Confirm the estimate
     is DRAFT (the persisting calls are DRAFT-gated).
  2. **Preview the change (non-persisting).** Depending on the user's intent, call the appropriate
     **preview** tool — `previewApplyPackage`, `previewApplyWork`, `previewRecomputeFinishing`, or
     `previewStagedEdits`. These are POST and therefore confirmed like any write (design D4), but
     they **do not persist** — present the computed result to the user as a proposal.
  3. **Confirm + persist assignments.** On explicit confirmation of the previewed result, call
     `saveEstimateAssignments` (the single batched Save; ESTIMATE/UPDATE, DRAFT-gated) to persist.
  4. **Materials reserve (optional hand-off).** If the user also wants to set the materials reserve,
     continue into Goal G1 (`getEstimateMaterials` → confirm → `saveEstimateMaterialsReserve`).
  5. **Report.** Re-read `getEstimateMatrix` and summarize the persisted result.
- **Tools:** `getOrCreateEstimateForProject`, `getEstimateMatrix`, `getEstimateMaterials`,
  `listEstimateLines` (reads); `previewApplyPackage`, `previewApplyWork`,
  `previewRecomputeFinishing`, `previewStagedEdits`, `saveEstimateAssignments`,
  `saveEstimateMaterialsReserve` (writes — preview POSTs are non-persisting but still confirmed).

#### Goal G6 — Catalog & materials authoring (additional main-feature goal)

- **Purpose:** maintain the reference catalog and materials used by estimates — work items, work
  prices, work categories, materials, package assortment, and work-material consumptions.
- **Role audience:** ADMIN, MANAGER, ESTIMATOR (catalog/material **write** audience per §3.2;
  mirrors the estimate-authoring audience). All operational roles may *read* the catalog, but only
  these three are offered the authoring (write) goal; CLIENT is excluded.
- **Milestones:**
  1. **Browse / search.** `listWorkItems`, `listWorkPrices`, `listWorkCategories`, `listMaterials`,
     `listAssortmentGroups`, `listAssortmentPositions`, `getPackageAssortmentEditor`,
     `listWorkMaterialConsumptions` to find the target and avoid duplicates.
  2. **Propose the change.** Summarize the new/updated catalog or material record (with valid
     dictionary references via `listMeasurementUnits` / `listMaterialCategories`).
  3. **Confirm + write.** On confirmation, call the matching write — `createWorkItem` /
     `updateWorkItem`, `createWorkPrice`, `createWorkCategory`, `createMaterial` / `updateMaterial`,
     `savePackageAssortment`, or `createWorkMaterialConsumption`. Re-read to report.
- **Tools:** the `list*`/`get*` reads above; `createWorkItem`, `updateWorkItem`, `createWorkPrice`,
  `createWorkCategory`, `createMaterial`, `updateMaterial`, `savePackageAssortment`,
  `createWorkMaterialConsumption` (writes).

#### Goal G7 — Procurement intake (additional main-feature goal)

- **Purpose:** bring procurement documents (invoices/receipts) into a project — upload, inspect the
  OCR/parse/match result, correct a line match, and re-process or remove a document.
- **Role audience:** ADMIN, MANAGER, FINANCIER (the procurement operators per §3.2). No other role
  is offered procurement tools at all (they are absent from those roles' offering). Deleting a
  document is ADMIN-only.
- **Milestones:**
  1. **Read current procurement.** `listProcurementDocuments` (by `projectId`) + `getProcurementDocument`
     / `listProcurementLines` to show existing documents and their parsed lines.
  2. **Upload.** On confirmation, `uploadProcurementDocument` (multipart; runs OCR + parse + match
     inline). Show the parsed result.
  3. **Fix matches.** Where a line matched wrong, propose then — on confirmation — `matchProcurementLine`.
  4. **Reprocess / delete.** If parsing was poor, `reprocessProcurementDocument` (confirm); if the
     user (ADMIN) wants to remove a document, `deleteProcurementDocument` (confirm).
- **Tools:** `listProcurementDocuments`, `getProcurementDocument`, `listProcurementLines` (reads);
  `uploadProcurementDocument`, `matchProcurementLine`, `reprocessProcurementDocument`,
  `deleteProcurementDocument` (writes; delete ADMIN-only).

#### Goal G8 — Team & schedule (additional main-feature goal)

- **Purpose:** staff a project and lay out its work schedule.
- **Role audience:** team administration → ADMIN, MANAGER (per §3.2 `assignProjectMember` /
  `updateProjectMember` / `removeProjectMember`); schedule writes → ADMIN, MANAGER, FOREMAN
  (`saveProjectScheduleBars` / `autoCreateProjectSchedule`). The goal offers only the milestones the
  caller's role covers (a FOREMAN sees the schedule milestones, not the membership-write ones).
- **Milestones:**
  1. **Read the team + schedule.** `listProjectMembers`, `listProjectMemberCandidates` (ADMIN/MANAGER),
     `getProjectSchedule`.
  2. **Staff the project.** On confirmation, `assignProjectMember`; adjust with `updateProjectMember`;
     remove with `removeProjectMember` (each confirmed).
  3. **Lay out the schedule.** `autoCreateProjectSchedule` to seed, or `saveProjectScheduleBars` to
     save user-defined bars (each confirmed).
- **Tools:** `listProjectMembers`, `listProjectMemberCandidates`, `getProjectSchedule` (reads);
  `assignProjectMember`, `updateProjectMember`, `removeProjectMember`, `saveProjectScheduleBars`,
  `autoCreateProjectSchedule` (writes).

### 4.2 Test-console validation

> **Live-console status.** The loaders-bot admin panel / test console is **not reachable from the
> build environment**, so the rows below are a **documented dry-run / expected-outcome** validation:
> for each goal, the expected agent behaviour and tool sequence against the running bot + Docker
> stack. **Live-console execution is a manual follow-up** — run each goal in the bot test console
> against the running bot, confirm the observed behaviour matches the "Expected outcome" column, and
> flip the **Live result** column from `pending (manual)` to `pass`/`fail` with notes. This keeps the
> artifact complete and reproducible (Requirement 4.4 / 8.4) while being explicit that the live run
> is the outstanding manual step.
>
> **Dry-run method (per goal):** trace the goal's milestones against the §3.2 matrix and the curated
> toolset — verify (a) every tool the goal names exists in the toolset with the expected method, (b)
> the goal's role audience matches the audience of its write tools in §3.2, (c) reads precede writes,
> and (d) each non-GET step is gated by a confirmation. A goal "passes" the dry-run when all four
> hold.

| Goal | Role audience | Dry-run expected outcome (tool sequence + confirmation gates) | Dry-run result | Live result (manual follow-up) |
|------|---------------|----------------------------------------------------------------|----------------|--------------------------------|
| G1 Materials-list import | ADMIN, MANAGER, ESTIMATOR | Reads `getProject`→`getOrCreateEstimateForProject`→`getEstimateMaterials`; proposes reserve map; **one confirmation** before `saveEstimateMaterialsReserve` (PUT, DRAFT-gated); re-reads. Non-audience roles not offered. | ✅ pass (all tools present, audience matches ESTIMATE-write, read→confirm→write) | pending (manual) |
| G2 Seller search | ADMIN, MANAGER, FOREMAN, WORKER, ESTIMATOR, FINANCIER | `listMaterialSellers?query=…` → `getMaterialSeller`; read-only, **no confirmation** needed; optional `createMaterialSeller` only for ADMIN/MANAGER/ESTIMATOR/FINANCIER behind a confirmation. CLIENT not offered. | ✅ pass (read tools present; optional write audience matches §3.2 seller-write) | pending (manual) |
| G3 Project creation | ADMIN, MANAGER | Collects fields (reads dictionaries) → summarizes → **one confirmation** → `createProject` (single POST). No other role offered. | ✅ pass (single create, audience = PROJECTS-create, read→confirm→write) | pending (manual) |
| G4 Step-by-step project management | ADMIN, MANAGER (estimate milestones also ESTIMATOR) | `getProject`/`listRooms` read; rooms-from-plan = `uploadImage` (confirm) → user geometry → `createRoom`/`updateRoom` (confirm each) — **no one-call plan→room endpoint** (Req 4.3); hands to G5; team/schedule confirmed writes; `activateProject` confirmed, backend readiness-gated. | ✅ pass (multi-step plan path correct; every write confirmed; audiences match §3.2) | pending (manual) |
| G5 Estimate preparation | ADMIN, MANAGER, ESTIMATOR | Reads `getOrCreateEstimateForProject`/`getEstimateMatrix`; **preview** POSTs (`previewApplyPackage`/`previewApplyWork`/`previewRecomputeFinishing`/`previewStagedEdits`) are non-persisting but confirmed; **separate confirmation** before `saveEstimateAssignments` (persists, DRAFT-gated); optional reserve via G1. | ✅ pass (preview≠persist distinction honored; persisting step separately confirmed) | pending (manual) |
| G6 Catalog & materials authoring | ADMIN, MANAGER, ESTIMATOR | `list*`/`get*` reads first; each create/update (`createWorkItem`/`updateWorkItem`/`createWorkPrice`/`createWorkCategory`/`createMaterial`/`updateMaterial`/`savePackageAssortment`/`createWorkMaterialConsumption`) behind its own confirmation. | ✅ pass (audience matches catalog/material-write; read→confirm→write) | pending (manual) |
| G7 Procurement intake | ADMIN, MANAGER, FINANCIER (delete ADMIN-only) | Reads `listProcurementDocuments`/`getProcurementDocument`/`listProcurementLines`; `uploadProcurementDocument`, `matchProcurementLine`, `reprocessProcurementDocument` confirmed; `deleteProcurementDocument` confirmed + ADMIN-only. | ✅ pass (procurement audience matches §3.2; delete restricted to ADMIN) | pending (manual) |
| G8 Team & schedule | team: ADMIN, MANAGER; schedule: ADMIN, MANAGER, FOREMAN | Reads `listProjectMembers`/`listProjectMemberCandidates`/`getProjectSchedule`; membership writes (assign/update/remove) confirmed (ADMIN/MANAGER); schedule writes (`saveProjectScheduleBars`/`autoCreateProjectSchedule`) confirmed (incl. FOREMAN). Role-split milestones. | ✅ pass (split audiences match §3.2; every write confirmed) | pending (manual) |

> **Out-of-scope / security spot-checks (dry-run, to re-confirm live):** an estimate-write goal
> offered to a FOREMAN/WORKER/FINANCIER would be filtered out by role (§3.2); if somehow attempted,
> the backend returns 403 (no ESTIMATE/UPDATE grant). A project-scoped read/write with an id outside
> the user's project membership returns 404 (`ProjectScopedService`). The agent never attempts an
> account/role-admin, `/api/auth`, or webhook call because those operations are absent from the
> toolset entirely (§3.2 "Excluded surface"). These confirm Requirement 5.3 / 3.5 and are to be
> re-observed in the live console run.

> **Live-console checklist (manual follow-up).** For each goal G1–G8: open the bot test console with
> a visitor whose identity token carries each relevant role, run the goal's happy path, verify the
> tool sequence and the confirmation prompt on every write, then record `pass`/`fail` in the **Live
> result** column above. Also run the two negative checks (a write goal as a read-only role → not
> offered / 403; an out-of-scope project id → 404).

---

## References

- Requirements: `requirements.md` (Req 2 toolset, Req 3 roles + matrix, Req 4 goals, Req 5 JWT +
  confirmation, Req 8.4 documentation).
- Design: `design.md` → "Bot configuration (captured in-repo)" and "Goal design notes".
- Curated toolset artifact: produced by task 2.2 (committed in the backend repo).
