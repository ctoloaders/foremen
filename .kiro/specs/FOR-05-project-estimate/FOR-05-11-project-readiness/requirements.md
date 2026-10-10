# Requirements Document

## Introduction

This spec (**FOR-05-11-project-readiness**) is the **last child spec of FOR-05** (the design stage).
It delivers the **aggregated project-readiness surface**: a single read endpoint that collects a
readiness contribution from every design-stage area of a project, averages them into one headline
percentage and overall state, and lists — in human-readable, localized terms — what still blocks the
project from being 100% ready to sign. It also turns the placeholder `readiness` workspace tab into
the real readiness checklist and switches the header `ReadinessWidget` from its current per-gate
fan-out to the aggregated endpoint.

It realizes the parent design's readiness tracker (`../design.md` §1.4, §6.3) and the OVERVIEW row
11, but with the architecture the stakeholders chose for this spec:

- **Each area owns its own readiness.** There is no central calculator that re-derives nine gates
  from raw entities. Instead a backend **superinterface** (`ProjectReadinessContributor`) is
  implemented by each design-stage area's service; each contributor decides its own completion
  logic and its own percentage formula and returns a uniform contribution object.
- **A thin aggregator** (`ProjectReadinessController` + `ProjectReadinessService`) discovers the
  contributors, calls each one the caller is permitted to read, and composes them into one
  response: an overall percentage (the equal-weight average of the contributing percentages), an
  overall state, and the merged list of localized "what is left / what is blocking" descriptions.
- **Each area weighs equally.** The overall percentage is the simple arithmetic mean of the
  per-area percentages (every contributing area counts the same), not a line-weighted or
  value-weighted figure.

### Scope boundaries

In scope:

1. **The `ProjectReadinessContributor` superinterface (backend)** and its uniform contribution
   contract: a stable area key, a localized area name key, a 0–100 integer percentage, a
   DONE/PARTIAL/BLOCKED state, and an ordered list of localized outstanding-item descriptions (each
   an i18n key + parameters + the workspace tab to jump to).
2. **Contributor implementations for the design-stage areas that have backing data today**: rooms
   (and room dimensions), estimate (works present, works priced, bill-of-materials complete), offer
   approval, document signing, team, and schedule. Each is implemented in (or next to) the area's
   existing service so the area owns its own rule.
3. **The aggregated readiness API (backend)**: a single `GET /api/projects/{projectId}/readiness`
   returning the `Project_Readiness_View` (overall percentage, overall state, and the ordered list
   of per-area contributions with their outstanding items).
4. **Permission-scoped composition**: a contributor is included only when the caller holds READ on
   that area's ABAC resource; areas the caller cannot read are omitted from the response and
   excluded from the average, with no information leak about them.
5. **The `readiness` workspace tab (frontend)**: the real checklist panel replacing the current
   `PlaceholderTab` — an overall donut + per-area rows (name, state, percentage, the outstanding
   items) with a "go to the area's tab" affordance on each.
6. **The header `ReadinessWidget` switch (frontend)**: the widget consumes the single aggregated
   endpoint instead of fetching per-area readiness endpoints itself.
7. **Localization** (pl + ru) of every area name, state label, headline, outstanding-item
   description, and empty/error state; **regression safety** and **test artifacts**.

Out of scope:

- Any change to how an area computes its own underlying data (rooms geometry, estimate pricing, BoM
  derivation, offer negotiation, signing state machine, team membership, schedule bars). This spec
  only reads each area's already-computed state through its contributor.
- Any mutation. The readiness surface is strictly read-only; it emits no domain event and performs
  no write.
- Execution-stage readiness/progress (`ACTIVE…COMPLETED`). The readiness tracker is a design-stage
  (DRAFT…APPROVED) surface; execution-stage progress is owned by FOR-06.
- A per-project override of area weights, or value-weighted averaging. Every area weighs equally.
- Removing the existing per-area readiness endpoints (`GET /api/project-members/readiness`,
  `GET /api/project-schedules/readiness`). They remain for their owning specs' own use; this spec
  does not depend on them from the frontend after the switch, and MAY reuse their services' logic
  on the backend through the contributor interface.
- In-app notifications (see D9).

### Relationship to existing specs and code (source of truth)

- Parent: `../OVERVIEW.md` (child spec table, row 11) and `../design.md` §1.4 (readiness checklist
  pattern), §6.3 (`computeReadiness` algorithm and the per-area gate definitions), §7.3 / §8
  (`readiness` tab key and the `ReadinessController` / `ReadinessWidget` component names).
- `FOR-05-01-workspace-shell`: the `WORKSPACE_TABS` contract, `resolveWorkspaceTabs`,
  `resolveActiveTab`, the `readiness` tab entry (today gated on `PROJECTS` READ with the generic
  `PlaceholderTab`), and the `ReadinessWidget` shell. This spec replaces the `readiness` tab's
  placeholder panel and the widget's internal per-gate fan-out.
- `FOR-05-02-rooms-dimensions`: `RoomEntity` (`project` FK, `floorArea`/`wallArea` geometry
  columns), `RoomDao.findByProjectId`; ABAC resource `ROOMS`.
- `FOR-05-03-estimate-core` / `FOR-05-05-bill-of-materials`: `EstimateEntity` (one per project),
  `EstimateDao.findByProjectId`, `EstimateLineEntity` (`unitPrice`, `valueNet`, `quantity`),
  `EstimateLineRoomMaterialEntity` (the BoM material line: `branch`, `normQty`, `manualQty`,
  `qtyOverridden`); ABAC resource `ESTIMATE`.
- `FOR-05-07-offer-approval`: `OfferEntity`, `OfferStatus`
  (`DRAFT`/`SENT`/`CHANGES_REQUESTED`/`COUNTERED`/`APPROVED`/`REJECTED`/`WITHDRAWN`),
  `OfferVisibilityResolver`, `OfferDao.findByProjectIdOrderByIdAsc`; ABAC resource `OFFERS`.
- `FOR-05-08-document-signing`: `SignableDocumentEntity`, `DocumentStatus`
  (`DRAFT`/`PENDING_SIGNATURES`/`SIGNED`/`VOID`), `SignableDocumentTypeEntity.code` (contract types
  by the `CONTRACT_*` prefix), `SignableDocumentDao.findByProjectIdOrderByIdDesc`; ABAC resource
  `SIGNABLE_DOCUMENTS`.
- `FOR-05-09-team-selection`: `ProjectMemberService.readiness(projectId)` → `TeamReadiness`
  (`team` gate: DONE iff ≥1 ACTIVE FOREMAN, else BLOCKED); ABAC resource `PROJECT_MEMBERS`.
- `FOR-05-10-project-gantt`: `ProjectScheduleService.readiness(projectId)` → `ScheduleReadiness`
  (`schedule` gate: DONE/PARTIAL/BLOCKED from `rowCount`/`scheduledCount`); ABAC resource
  `WORK_SCHEDULE`.
- `FOR-03-08` API protection: `@PermissionResource`, `@PermissionOperation`,
  `PermissionAnnotationValidator`, `ProjectScopedService`/`ProjectAccessCache`,
  `ForemenPermissionEvaluator.isAllowed(roleCode, resource, operation)`.
- Project lifecycle: `ProjectStatus` = `DRAFT`, `READY_TO_OFFER`, `OFFERED`, `APPROVED`, `ACTIVE`,
  `ON_HOLD`, `COMPLETED`, `CANCELLED`; the design stage is `DRAFT`/`READY_TO_OFFER`/`OFFERED`/
  `APPROVED`; `stageOf` (frontend) maps those to `design`.

### Baseline: existing implementation this spec builds on

- The two existing readiness gates (`team`, `schedule`) are area-specific endpoints with their own
  response shapes; there is **no** aggregated readiness endpoint and **no** `ProjectReadinessContributor`
  interface yet.
- The `ReadinessWidget` currently fetches `GET /api/project-members/readiness` and
  `GET /api/project-schedules/readiness` directly and composes the two chips plus any base gates on
  the client.
- The `readiness` workspace tab points at the generic `PlaceholderTab`, gated on `PROJECTS` READ.
- `ProjectEntity` has a nullable `startDate`/`endDate` and a `status`.
- The last Liquibase changeset registered in `changelog.xml` is
  `153-seed-work-schedule-resource.xml`; this spec's changeset(s), if any, start at `154`.

### Decisions taken in this spec

- **D1 — Contributor-owned readiness (superinterface).** Each design-stage area owns its readiness
  through a backend superinterface `ProjectReadinessContributor`. The aggregator never re-derives an
  area's completion from raw entities; it only composes the contributions. This replaces the
  parent `../design.md` §6.3 "one central `computeReadiness` reads all entities" sketch for the
  purposes of this spec; §6.3's per-area rules survive as each contributor's definition.
- **D2 — Uniform contribution object.** A contribution is
  `{ key, nameKey, percentage ∈ [0,100] (integer), state ∈ {DONE, PARTIAL, BLOCKED}, items[] }`,
  where each item is `{ descriptionKey, params{}, targetTab }` — a localized description of one
  outstanding or blocking fact plus the workspace tab the UI should jump to. A fully-ready area
  (`DONE`, 100%) returns an empty `items` list.
- **D3 — Each area owns its percentage formula (fractional where natural).** A contributor computes
  its own percentage from its own data where a fractional notion exists (e.g. priced =
  pricedLines / totalLines; schedule = scheduledRows / totalRows; BoM = materialLinesWithConsumption
  / totalMaterialLines). An area with no meaningful fraction maps its state to a percentage with the
  canonical fallback DONE = 100, PARTIAL = 50, BLOCKED = 0. Every percentage is rounded to a whole
  number, with 0 ≤ percentage ≤ 100.
- **D4 — Equal-weight average.** The overall percentage is `round(mean(percentage of every included
  contribution))`, each area weighing the same, and 0 when no contribution is included.
- **D5 — Overall state is state-based, shown with the percentage.** The overall state is `BLOCKED`
  if any included contribution is `BLOCKED`, else `PARTIAL` if any is `PARTIAL`, else `DONE`. The
  response carries both the overall state and the overall percentage (D4); the two are consistent
  but independent (a 90% overall can still be `BLOCKED` if one area is blocked).
- **D6 — Permission-scoped composition.** A contribution is included only when the caller holds READ
  on the area's ABAC resource (`ROOMS` for rooms/dimensions, `ESTIMATE` for works/priced/bom,
  `OFFERS` for offer, `SIGNABLE_DOCUMENTS` for documents, `PROJECT_MEMBERS` for team,
  `WORK_SCHEDULE` for schedule). An area the caller cannot read is absent from the response and
  excluded from the average, and the response reveals nothing about it.
- **D7 — The aggregate endpoint reuses `PROJECTS` READ, not a new resource.** Calling the aggregate
  endpoint requires `PROJECTS` READ (the same coarse gate as the `/projects` list, the workspace
  route, and the `overview`/`readiness` tabs). No new ABAC resource is minted: per-area READ checks
  (D6) do the real filtering, so a dedicated `PROJECT_READINESS` resource would add a seed/grant
  with no additional security value.
- **D8 — Design-stage areas only.** The contributors cover the design-stage areas (rooms,
  dimensions, works, priced, bom, offer, documents, team, schedule). Execution-stage areas
  contribute nothing here (owned by FOR-06). The endpoint still answers for any project status, but
  outside the design stage the UI (widget + tab) is not rendered (the FOR-05-01 call site already
  stage-gates the widget; the `readiness` tab is a design-stage tab).
- **D9 — No notifications.** This spec emits no in-app notification. It is a read-only aggregation
  with no domain state change and no hand-over event; each area's own spec owns any notifications
  about that area.
- **D10 — No new translated entity fields.** Area names, state labels, and outstanding-item
  descriptions are **i18n keys resolved on the client** (returned by the API as keys + params),
  not translated columns. No new business entity and no new translated column is introduced. No new
  ABAC resource is seeded (D7), so there is no new resource row to translate either.
- **D11 — Contribution ordering.** The contributions are returned in a stable, declared area order
  matching the design-stage tab order (rooms, estimate works, estimate priced, bom, offer,
  documents, team, schedule — grouped per area), so the UI renders a stable checklist. The order is
  defined by the aggregator, not by contributor discovery order.
- **D12 — Area-vs-item granularity.** The parent §6.3 lists `rooms`, `dimensions`, `works`,
  `priced`, `bom` as separate gates. In this spec, rooms+dimensions are one **rooms** contribution
  (dimensions appear as its outstanding items) and works+priced+bom are one **estimate**
  contribution (missing-works / unpriced-lines / incomplete-BoM appear as its outstanding items),
  so each equally-weighted area maps 1:1 to a workspace tab. This keeps D4's equal weighting aligned
  with the tabs the user navigates.

### Open questions

- **Q1 — RESOLVED.** Each contributor owns its own percentage formula (D3); fractional where
  natural, state-mapped otherwise.
- **Q2 — RESOLVED.** Every contributing area weighs equally in the overall average (D4).
- **Q3 — RESOLVED.** Areas the caller cannot READ are omitted and excluded from the average (D6).
- **Q4 — RESOLVED.** The response carries both the overall state and the overall percentage (D5).
- **Q5 — RESOLVED.** The aggregate endpoint reuses `PROJECTS` READ; no new ABAC resource (D7).

## Glossary

- **Readiness_API**: the backend REST surface for aggregated readiness defined by this spec,
  `GET /api/projects/{projectId}/readiness`.
- **Readiness_Service**: the backend aggregator (`ProjectReadinessService`) that discovers the
  Readiness_Contributors, calls each permitted one, and composes the Project_Readiness_View.
- **Readiness_Contributor**: an implementation of the `ProjectReadinessContributor` superinterface,
  owned by a design-stage area's service, that returns that area's Area_Contribution for a project.
- **Area_Contribution**: the uniform object a Readiness_Contributor returns
  (`{ key, nameKey, percentage, state, items[] }`, D2).
- **Readiness_State**: `DONE` / `PARTIAL` / `BLOCKED` — the tri-state of an area or of the overall
  readiness.
- **Outstanding_Item**: one localized description of a fact that keeps an area below 100%
  (`{ descriptionKey, params, targetTab }`, D2).
- **Project_Readiness_View**: the read model the Readiness_API returns (Requirement 4): the overall
  percentage, overall state, and the ordered list of Area_Contributions.
- **Overall_Percentage**: the equal-weight mean of the included Area_Contribution percentages (D4).
- **Overall_State**: the state-based roll-up of the included Area_Contribution states (D5).
- **Area_Resource**: the ABAC resource that governs READ of an area's data (`ROOMS`, `ESTIMATE`,
  `OFFERS`, `SIGNABLE_DOCUMENTS`, `PROJECT_MEMBERS`, `WORK_SCHEDULE`).
- **Readable_Area**: a design-stage area whose Area_Resource READ the caller holds (ADMIN holds all).
- **Design_Stage_Status**: `DRAFT`, `READY_TO_OFFER`, `OFFERED`, or `APPROVED`.
- **Accessible_Project**: for a non-ADMIN caller, a project the caller is a Project_Member of; for
  ADMIN, every project (the `ProjectScopedService` / `ProjectAccessCache` rule).
- **Readiness_Tab**: the `readiness` workspace tab panel (replaces the FOR-05-01 placeholder).
- **Readiness_Widget**: the FOR-05-01 workspace header readiness component.
- **Contract_Document**: a `SignableDocument` whose `documentType.code` starts with `CONTRACT_`
  (FOR-05-08).

## Requirements

### Requirement 1: The `ProjectReadinessContributor` superinterface

**User Story:** As a backend developer of a design-stage area, I want a single interface to expose my
area's readiness, so that the readiness surface composes every area uniformly and I own my area's rule.

#### Acceptance Criteria

1. THE backend SHALL define a `ProjectReadinessContributor` superinterface whose single method
   returns, for a given project id, an Area_Contribution `{ key, nameKey, percentage, state,
   items[] }`, and SHALL expose a stable area `key` and the declared order position of the contributor.
2. THE Area_Contribution `percentage` SHALL be an integer in the inclusive range 0–100, and THE
   Area_Contribution `state` SHALL be exactly one of `DONE`, `PARTIAL`, `BLOCKED`.
3. WHEN a Readiness_Contributor's area is fully ready, THE contributor SHALL return `state = DONE`,
   `percentage = 100`, and an empty `items` list.
4. THE Area_Contribution `items` SHALL each be an Outstanding_Item `{ descriptionKey, params,
   targetTab }` where `descriptionKey` is an i18n key, `params` is a (possibly empty) map of
   placeholder values, and `targetTab` is the workspace tab key the UI jumps to for that item.
5. THE contributor SHALL compute its own `percentage` from its own data where a fractional notion
   exists, and OTHERWISE SHALL map its state to a percentage with the canonical fallback
   `DONE = 100`, `PARTIAL = 50`, `BLOCKED = 0`.
6. THE contributor SHALL be side-effect free: computing a contribution SHALL read only and change no
   data.

### Requirement 2: Controller guarding and the aggregate ABAC gate

**User Story:** As a security reviewer, I want the aggregate readiness endpoint guarded by a
project-read permission, so that only a caller who may read the project can read its readiness.

#### Acceptance Criteria

1. THE Readiness_API controller SHALL be annotated so that the aggregate read resolves to the
   `PROJECTS` resource with the READ operation, and the application SHALL start with
   `PermissionAnnotationValidator` accepting it.
2. THE Readiness_API SHALL NOT introduce a new ABAC resource; it SHALL reuse the existing `PROJECTS`
   READ grant (Decision D7).
3. IF an unauthenticated request reaches the Readiness_API, THEN THE Readiness_API SHALL respond
   with HTTP 401.
4. IF an authenticated caller lacks `PROJECTS` READ, THEN THE Readiness_API SHALL respond with HTTP
   403 and message code `error.access.denied` and change no data.

### Requirement 3: Project-scoped access

**User Story:** As a manager, I want to read only the readiness of projects I belong to, so that
other projects' readiness stays private.

#### Acceptance Criteria

1. THE Readiness_Service SHALL resolve the project through the existing project-access rule (ADMIN
   sees every project; a non-ADMIN caller sees only Accessible_Projects).
2. IF a non-ADMIN caller requests the readiness of a project that is not an Accessible_Project,
   THEN THE Readiness_API SHALL respond with HTTP 404 and message code `error.entity.not.found` and
   change no data.
3. IF any caller, ADMIN included, requests the readiness of a project id that does not exist, THEN
   THE Readiness_API SHALL respond with HTTP 404 and message code `error.entity.not.found`, with a
   response body identical to the one of criterion 2 (missing and inaccessible are indistinguishable).
4. IF the `projectId` path value is missing or non-numeric, THEN THE Readiness_API SHALL respond
   with HTTP 400 and change no data.
5. THE Readiness_API SHALL evaluate checks in this order: authentication (401), permission (403),
   request shape (400), then project existence and accessibility (404).

### Requirement 4: Read the aggregated readiness

**User Story:** As a project participant, I want one call that tells me the overall readiness and
exactly what is left per area, so that I know how close the project is to being signable.

#### Acceptance Criteria

1. WHEN a caller with `PROJECTS` READ requests the readiness of an Accessible_Project, THE
   Readiness_API SHALL respond with HTTP 200 and a Project_Readiness_View containing: the project
   id, the Overall_Percentage, the Overall_State, and the ordered list of Area_Contributions for
   the caller's Readable_Areas.
2. THE Readiness_Service SHALL include an Area_Contribution if and only if the caller holds READ on
   that area's Area_Resource (Requirement 6); a non-Readable_Area SHALL be absent from the response.
3. THE Overall_Percentage SHALL equal `round(mean(percentage of every included Area_Contribution))`
   with every area weighed equally, and SHALL be `0` when no Area_Contribution is included.
4. THE Overall_State SHALL be `BLOCKED` if any included Area_Contribution is `BLOCKED`, else
   `PARTIAL` if any included Area_Contribution is `PARTIAL`, else `DONE`; WHEN no Area_Contribution
   is included THE Overall_State SHALL be `BLOCKED`.
5. THE Area_Contributions SHALL be returned in the stable declared area order (Requirement 7),
   independent of contributor discovery order.
6. THE Project_Readiness_View SHALL carry, for each Area_Contribution, its `key`, its localized name
   key, its `percentage`, its `state`, and its ordered list of Outstanding_Items.
7. THE Readiness_API read SHALL reflect every area change (rooms, estimate, offer, documents, team,
   schedule) committed before the request was received.
8. THE Readiness_API SHALL contain no field that an area's own ABAC rule would hide from the caller
   (e.g. no estimate money for a non-`ESTIMATE`-reader): an Outstanding_Item's `params` SHALL carry
   only non-sensitive counts and identifiers, never masked values.

### Requirement 5: Area contributions — completion and outstanding items

**User Story:** As a manager, I want each area to report its own completion and a clear list of what
is missing, so that I can act on specifics instead of a single opaque percentage.

#### Acceptance Criteria

1. THE **rooms** contribution (Area_Resource `ROOMS`, `targetTab = rooms`) SHALL be `BLOCKED` at 0%
   when the project has no room; otherwise its percentage SHALL be
   `round(100 × roomsWithPositiveArea / totalRooms)`, `DONE` at 100% when every room has a positive
   area, and `PARTIAL` otherwise, listing one Outstanding_Item per room without a positive area
   (`params` = the room identifier/name).
2. THE **estimate** contribution (Area_Resource `ESTIMATE`, `targetTab = estimate`) SHALL be
   `BLOCKED` at 0% when the project's estimate has no line; otherwise it SHALL combine three
   sub-conditions into its percentage and items: lines priced (`unitPrice > 0`), and material (BoM)
   lines having a resolved consumption, so that it is `DONE` at 100% only when every estimate line
   is priced and every material line has a consumption, `PARTIAL` otherwise, listing an
   Outstanding_Item for "unpriced lines" (`params` = count) and for "material lines without
   consumption" (`params` = count) when either is nonzero.
3. THE **offer** contribution (Area_Resource `OFFERS`, `targetTab = pricing`) SHALL be `DONE` at
   100% when the project's current offer is `APPROVED`, `PARTIAL` at 50% when the offer is in a
   sent / on-approval state (`SENT` / `CHANGES_REQUESTED` / `COUNTERED`), and `BLOCKED` at 0%
   otherwise (no offer, draft, rejected, or withdrawn), listing an Outstanding_Item describing the
   next offer step.
4. THE **documents** contribution (Area_Resource `SIGNABLE_DOCUMENTS`, `targetTab =
   documentSigning`) SHALL be `DONE` at 100% when the project's Contract_Document is `SIGNED`,
   `PARTIAL` at 50% when it is `PENDING_SIGNATURES`, and `BLOCKED` at 0% otherwise (no
   Contract_Document, draft, or void), listing an Outstanding_Item describing the next signing step.
5. THE **team** contribution (Area_Resource `PROJECT_MEMBERS`, `targetTab = team`) SHALL be `DONE`
   at 100% when the project has at least one ACTIVE FOREMAN member and `BLOCKED` at 0% otherwise,
   reusing the FOR-05-09 team-readiness rule, listing an Outstanding_Item "assign an active foreman"
   when blocked.
6. THE **schedule** contribution (Area_Resource `WORK_SCHEDULE`, `targetTab = scheduleDesign`) SHALL
   derive its state from the FOR-05-10 schedule-readiness rule (DONE when every schedule row is
   scheduled and there is at least one row; BLOCKED when nothing is scheduled or there are no rows;
   PARTIAL otherwise) with percentage `round(100 × scheduledRows / totalRows)` (0 when there are no
   rows), listing an Outstanding_Item "N rows not scheduled" (`params` = count) when not DONE.
7. WHERE an area has no data at all for the project (e.g. no estimate, no rooms), THE contribution
   SHALL still be returned for a Readable_Area as `BLOCKED` at 0% with an Outstanding_Item that
   names the first step for that area.
8. THE Outstanding_Items of every contribution SHALL be ordered deterministically (a stable order
   for the same project state), so two reads of an unchanged project return the same list in the
   same order.

### Requirement 6: Permission-scoped composition

**User Story:** As a client with limited visibility, I want to see readiness only for the areas I may
read, so that restricted areas neither appear nor affect the headline I see.

#### Acceptance Criteria

1. THE Readiness_Service SHALL determine the caller's Readable_Areas using the same permission
   evaluation as the areas' own controllers (`ForemenPermissionEvaluator.isAllowed(role,
   Area_Resource, READ)`, with the ADMIN bypass).
2. WHEN the caller cannot READ an area's Area_Resource, THE Readiness_Service SHALL omit that area's
   Area_Contribution entirely and SHALL exclude it from the Overall_Percentage and Overall_State.
3. THE Readiness_Service SHALL leak nothing about an omitted area: no count, name, state,
   percentage, or Outstanding_Item for it SHALL appear in the response.
4. WHEN the caller can READ no area at all, THE Readiness_API SHALL respond with HTTP 200, an empty
   Area_Contribution list, Overall_Percentage 0, and Overall_State `BLOCKED`.
5. THE per-area READ checks SHALL be independent of the aggregate `PROJECTS` READ gate (Requirement
   2): holding `PROJECTS` READ admits the caller to the endpoint, and each Area_Resource READ admits
   that area's contribution.

### Requirement 7: Stable area ordering and keys

**User Story:** As a frontend developer, I want a stable area order and stable keys, so that the
checklist renders consistently and I can map each area to its tab.

#### Acceptance Criteria

1. THE Readiness_Service SHALL order the Area_Contributions in a single declared order matching the
   design-stage tab order: rooms, estimate, offer, documents, team, schedule.
2. THE area `key` of each contribution SHALL be a stable, lowercase identifier (`rooms`,
   `estimate`, `offer`, `documents`, `team`, `schedule`) usable as a React list key.
3. THE `targetTab` of each Outstanding_Item SHALL be a valid `WORKSPACE_TABS` key for the owning
   area (`rooms`, `estimate`, `pricing`, `documentSigning`, `team`, `scheduleDesign`).
4. THE area order SHALL NOT depend on Spring bean discovery order or on which areas the caller can
   read; omitting a non-readable area SHALL preserve the relative order of the remaining areas.

### Requirement 8: The `readiness` workspace tab

**User Story:** As a project participant, I want a readiness tab that shows the overall percentage
and a per-area checklist with jump-to-tab links, so that I can see and fix what blocks signing.

#### Acceptance Criteria

1. THE `readiness` workspace tab SHALL render the real readiness checklist panel, replacing the
   generic placeholder, keeping its tab key, stage (`design`), `PROJECTS` READ gate, label key, and
   position in `WORKSPACE_TABS` unchanged.
2. WHEN the tab loads, THE panel SHALL fetch the aggregated readiness of the working project and
   render an overall readiness donut (the Overall_Percentage) with the Overall_State, and one row
   per Area_Contribution.
3. THE panel SHALL show, for each Area_Contribution, its localized area name, its localized state
   label (so the state reads from text, not colour alone), its percentage, and its ordered
   Outstanding_Items each as a localized sentence.
4. WHEN the user activates an Outstanding_Item (or an area row), THE panel SHALL navigate the
   workspace to that item's `targetTab`.
5. WHILE the readiness request is loading, THE panel SHALL show a non-blocking skeleton and never a
   raw i18n key; IF the request fails, THEN THE panel SHALL show a localized error with a retry and
   SHALL leave the rest of the workspace usable.
6. WHEN the aggregated readiness returns an empty Area_Contribution list, THE panel SHALL show a
   localized "nothing to show / no readable areas" state at 0% without a crash and without a raw key.
7. THE panel SHALL render only localized text for area names, states, headlines, and
   Outstanding_Items, resolving the API's description keys + params through i18n.

### Requirement 9: Switch the header `ReadinessWidget` to the aggregated endpoint

**User Story:** As a project participant, I want the header readiness widget to match the readiness
tab, so that the headline percentage is computed once and consistently.

#### Acceptance Criteria

1. THE Readiness_Widget SHALL consume the single aggregated Readiness_API instead of fetching the
   per-area readiness endpoints itself.
2. THE Readiness_Widget SHALL show the Overall_Percentage and one chip per Area_Contribution labeled
   with the area's localized name and state label, consistent with the Readiness_Tab.
3. THE Readiness_Widget SHALL be rendered only in the design stage (the FOR-05-01 call site keeps
   the stage gate); its headline and chips SHALL reflect the aggregated response for Readable_Areas
   only.
4. WHEN an area the caller cannot read is omitted by the API, THE Readiness_Widget SHALL neither show
   its chip nor count it in the headline (consistent with Requirement 6).
5. IF the aggregated readiness request fails, THEN THE Readiness_Widget SHALL show a localized
   "could not load readiness" indication and SHALL leave the rest of the workspace usable, never a
   raw i18n key and never a crash.
6. WHEN an area's data changes through its own tab and that area's query is invalidated, THE
   Readiness_Widget and the Readiness_Tab SHALL refetch the aggregated readiness so the headline and
   chips update without a full page reload.

### Requirement 10: Localization

**User Story:** As a Polish or Russian user, I want every readiness label and description in my
language, so that the checklist is fully usable.

#### Acceptance Criteria

1. THE frontend SHALL provide pl and ru translations for every readiness UI string: the tab label,
   the overall headline, the three state labels, every area name, every Outstanding_Item
   description (with placeholders), the loading/empty/error states, and the "go to tab" affordance.
2. THE pl.json and ru.json files SHALL contain the exact same set of readiness keys and the same
   placeholder names in each description, so a locale-parity test passes.
3. THE Readiness_API SHALL return description keys and params (not pre-rendered sentences), and THE
   frontend SHALL resolve them, so a language switch re-renders every readiness string without a new
   request.
4. WHERE an area name is also surfaced by its own tab, THE readiness strings MAY reuse the existing
   `workspace.tab.*` label keys for consistency, but the Outstanding_Item descriptions SHALL live
   under a dedicated `readiness.*` namespace owned by this spec.

### Requirement 11: No notifications

**User Story:** As a product owner, I want the readiness surface to stay a passive view, so that it
never spams anyone.

#### Acceptance Criteria

1. THE readiness surface SHALL emit no in-app notification, send no email/SMS/Telegram, and create
   no notification record (Decision D9).
2. THE Readiness_API SHALL perform no write of any kind; a readiness read SHALL change no data and
   SHALL produce no audit row.

### Requirement 12: Regression safety, documentation, and test artifacts

**User Story:** As a maintainer, I want the readiness spec to leave the rest of the system working
and to update the FOR-05 map, so that the last child spec closes FOR-05 cleanly.

#### Acceptance Criteria

1. THE existing per-area readiness endpoints (`GET /api/project-members/readiness`,
   `GET /api/project-schedules/readiness`) and their behavior SHALL remain unchanged; this spec
   SHALL add the aggregate without breaking them.
2. THE existing `WORKSPACE_TABS` resolver tests (`resolveWorkspaceTabs` / `resolveActiveTab` /
   `resolveDesignSelectorTabs`) SHALL pass unchanged after the `readiness` tab panel swap.
3. THE FOR-05 OVERVIEW row 11 SHALL be updated to mark FOR-05-11 as implemented, and the parent
   `../design.md` §6.3 SHALL note the contributor-based architecture chosen here.
4. THE FOR-03-08 guarded-controller enumeration test SHALL include the new readiness controller
   mapped to `PROJECTS` READ (reusing the existing resource; no new resource row).
5. THE spec SHALL include a `test-cases.md` authored per `.kiro/steering/test-cases.md` (API
   scenarios against the Dockerized app and browser-engine scenarios for the tab and widget),
   covering at least one case for each of Requirements 1–11.
