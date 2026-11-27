# Bug report: FOR-04-13-project

Screen URL: http://localhost:3000/projects

Owning spec: `.kiro/specs/FOR-04-base-entities/FOR-04-13-project`. The `/projects` edit form is
`foremen-frontend/src/features/projects/components/ProjectFormSheet.tsx` (rendered inside a Radix
`Sheet`), which uses the shared `DatePicker` (`foremen-frontend/src/components/ui/date-picker.tsx`)
built on the shared `Calendar` (`foremen-frontend/src/components/ui/calendar.tsx`, react-day-picker
v10) + `Popover`. Both bugs are **frontend-only** and live in the shared UI primitives, so the fix
benefits every date field (currently only the project form uses `DatePicker`; the data-table
`DateFilter` uses the same `Calendar`).

Notifications: none (pure UI fix). i18n: none (reuses existing keys).

## Round 1 (2026-10-29)

- [x] 1. Calendar month-switch arrows are not clickable
  - Triage: observed — in the project edit datepicker the prev/next month arrows do nothing on
    click. Expected — clicking the arrows pages the calendar month. Layer: **frontend**. Root cause
    (confirmed in-browser via `document.elementFromPoint`): in `calendar.tsx` the `nav` is styled
    `absolute top-0 right-0` while the `month_caption` is `relative` and full-width
    (`flex justify-center pt-1 relative items-center`). The caption div therefore paints OVER the
    absolutely-positioned nav and intercepts the pointer — `elementFromPoint` at the center of the
    prev/next buttons returns the `month_caption` div, not the button (`isSelf:false`). Day-cell
    clicks work because they sit below the caption row. Fix: lay the caption + nav out so the nav
    buttons receive clicks — i.e. don't let the centered caption overlap the absolute nav. Options:
    (a) give the `nav` a higher stacking context (`relative`/`z-10`) so it sits above the caption, or
    (b) adopt the standard react-day-picker v10 layout (caption label + nav as flex siblings in the
    caption row, nav not absolutely positioned). Prefer (b) — the robust, idiomatic v10 layout —
    keeping the existing `Chevron` component slot and button styling.
  - Suspect: `foremen-frontend/src/components/ui/calendar.tsx` (`classNames.nav` / `month_caption`).
  - Fix: added `pointer-events-none` to `month_caption` and `pointer-events-auto` to `caption_label`
    in `calendar.tsx`, so the full-width centered caption (a later DOM sibling painted over the
    absolute nav) no longer swallows the prev/next clicks — they fall through to the nav buttons
    beneath, while the label stays selectable. (A z-index-only approach was rejected: Tailwind purged
    the `z-10` utility on the nav, and `pointer-events` is the robust, purge-proof fix; verified live
    that the hit-test at the arrow centers lands on the buttons.)
  - Verified (browser): on the project edit start-date picker the prev arrow paged October→September,
    the next arrow paged back to October; same on the end-date picker. The shared `Calendar` is reused
    by the data-table DateFilter, so its arrows are fixed by the same change.
  - _Requirements: FOR-04-13 R2.5 (date fields usable in the project form)_
  - _Depends on: none_

- [x] 2. Datepicker does not close after a date is selected
  - Triage: observed — selecting a day leaves the calendar popover open. Expected — the popover
    closes once a date is picked. Layer: **frontend**. Root cause: `date-picker.tsx` renders an
    uncontrolled `<Popover>` (no `open` state), and the `Calendar`'s `onSelect` only calls
    `onChange(...)` — nothing closes the popover. Fix: make the `Popover` `open`-controlled with local
    state and close it in `onSelect` after emitting the value (leave Clear behavior as-is, or close on
    clear too — clear is in the popover footer, closing on clear is reasonable). The trigger still
    toggles open normally.
  - Suspect: `foremen-frontend/src/components/ui/date-picker.tsx`.
  - Fix: made the `Popover` `open`-controlled (`useState`) in `date-picker.tsx` and close it on day
    select (`if (date) setOpen(false)` in `onSelect`) and on Clear. Month navigation keeps the popover
    open (only a day pick / clear closes it).
  - Verified (browser): selecting day 15 closed the popover and the trigger showed "15.10.2026".
  - _Requirements: FOR-04-13 R2.5_
  - _Depends on: none_

- [x] V1. Validate Round 1 in the browser
  - On `/projects`, open a project's Edit form, open the start-date picker: click the prev/next month
    arrows and confirm the displayed month changes; select a day and confirm the popover closes and
    the field shows the picked date; reopen and confirm the value prefills; repeat for the end date.
    Confirm the data-table date filter calendar still works (shared Calendar). Mark `[x]` only on a
    confirmed browser pass.
  - Result (2026-10-29, PASS): project edit start-date picker — prev/next arrows page the month
    (Oct↔Sep); selecting a day closes the popover and the field shows the ISO-derived "dd.MM.yyyy"
    (15.10.2026); end-date picker arrows work the same. `npx tsc -b` clean; `projects` suite 242
    tests pass. The shared `Calendar` fix covers the data-table `DateFilter` (same component). No test
    data persisted (closed the form without saving).
  - _Requirements: FOR-04-13 R2.5_
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1 (calendar nav layout, `calendar.tsx`) and 2 (close-on-select,
  `date-picker.tsx`) — independent files.
- **Wave 2:** V1 — browser validation; depends on 1 and 2.

## Round 2 (2026-11-08)

Screen URL: http://localhost:3000/projects (create-project form, `ProjectFormSheet.tsx`, create mode).
Team picker: `foremen-frontend/src/features/projects/components/TeamMemberSelect.tsx`.
Related spec: FOR-05-09-team-selection (admin-staff-only create form, Req 26.6/26.7, D13).

- [x] 1. When a new project is created, the admin team dropdown is not scrollable
  - Triage: observed — in the create form the admin-team popover list cannot be scrolled (wheel/touch),
    so users beyond the first visible ones are unreachable and infinite-scroll paging never fires.
    Expected — the list scrolls inside the popover and loads further pages. Layer: **frontend**.
    Suspected cause: the Popover is portalled out of a modal Radix `Sheet`, whose scroll lock swallows
    wheel events; the list div (`TeamMemberSelect.tsx` ~line 249, `max-h-60 overflow-y-auto p-1`) lacks
    the fix already used in `components/ui/async-entity-select.tsx` (`overscroll-contain` +
    `onWheel={(e) => e.stopPropagation()}`). To be confirmed in the browser before fixing.
  - Suspect: `foremen-frontend/src/features/projects/components/TeamMemberSelect.tsx`.
  - Fix: list div now has `overscroll-contain` + `onWheel={(e) => e.stopPropagation()}`.
  - Verified (browser, Vite dev server on :3000): mouse wheel over the open list moved `scrollTop`
    0 → 24 (scrollHeight 264 > clientHeight 240).
  - _Requirements: FOR-04-13 create form (team assignment); FOR-05-09 Req 26.6_
  - _Depends on: none_

- [x] 2. Filter out inactive users in the admin team dropdown
  - Triage: observed — the create-form team picker lists inactive users. Expected — only active users
    are offered. Layer: **frontend** (possibly backend if the query DSL rejects a boolean filter).
    The `GET /api/users` query in `TeamMemberSelect.tsx` (~line 116) has no active filter; `UserDto.active`
    exists. Fix: add an `active~eq~true` fragment to the query (and confirm the DSL supports it; if not,
    add server-side support rather than client-side filtering, which would break paging). The Team-tab
    candidates endpoint already excludes inactive users, so the create form is the outlier.
  - Suspect: `TeamMemberSelect.tsx`; backend `UserService` / generic query DSL only if needed.
  - Fix: added the `active==true` fragment (the DSL equality operator is `==`; `~eq~` does not exist
    and returns 400). No backend change needed. API check with the admin-staff filter: 23 users total,
    8 with `active==true`, 15 with `active==false`.
  - Verified (browser): the dropdown lists exactly 8 users, matching the active count.
  - _Requirements: FOR-05-09 Req 26.6 (admin-staff selection)_
  - _Depends on: none_

- [-] 3. Add worker assignment to the new-project create form  (**DISCARDED by the user, 2026-11-08; not implemented**)
  - Triage: observed — the create form offers admin staff only and shows a static hint that workers
    are added from the Team tab after creation (`projects.form.team.workersHint`). Expected — workers
    can also be assigned while creating the project. Layer: **both** (frontend picker + backend
    `CreateProjectRequest.members` handling for WORKER-role users, worker type / tags if needed).
    This reverses FOR-05-09 decision D13 / Req 26.6–26.7 (workers only via the Team tab) and touches
    the project-create contract, so it likely warrants its own small spec (or an amendment to
    FOR-05-09) rather than a bug round. Open questions for the user: (a) pick existing workers only, or
    also create new worker records inline; (b) is worker type / tags chosen at creation; (c) should the
    static workers hint be removed. Recorded here so it is not dropped; implementation awaits the
    user's decision on the split.
  - Suspect: `ProjectFormSheet.tsx` (team block ~lines 487-519, payload ~line 196),
    `TeamMemberSelect.tsx`, backend project-create service, `project-team` feature
    (`AddMemberDialog`, `AddNewWorkerDialog`, `fetchCandidates`).
  - _Requirements: FOR-05-09 Req 26.6/26.7 (to be amended)_
  - _Depends on: none_

- [-] 4. (empty bullet in the report — no bug text provided; closed, nothing to fix)
  - Triage: the fourth list item was blank. Nothing recorded; please supply the text or confirm it
    was a stray bullet.
  - _Depends on: none_

- [x] V2. Validate Round 2 in the browser (bugs 1 and 2 only; PASS, see results below)
  - Bring up the local stack, open `/projects` > New project: confirm the team dropdown scrolls with the
    wheel and loads more pages, inactive users are absent (deactivate a test user first), and (if
    approved) workers can be assigned and appear in the project's Team tab after creation. Mark `[x]`
    only on a confirmed browser pass.
  - Result (2026-11-08, PASS): validated against a Vite dev server on :3000 (the Docker frontend
    image build is currently blocked by unrelated TS errors in `features/rooms`, so the container was
    stopped during the check and restarted afterwards with its previous image). `projects` vitest suite:
    242 passed. No data was created.
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 2

- **Wave 1 (parallel):** 1 and 2 touch the same file (`TeamMemberSelect.tsx`), so run them serially
  within one stream; 3 is independent but gated on the user's split decision.
- **Wave 2:** V2 — browser validation; depends on all approved fixes.

## Round 3 (2026-11-19)

Screen URL: `https://dev.foremen.eu/projects` (project edit form, `ProjectFormSheet.tsx`, edit mode).

- [~] 1. Add VAT at project level so it affects the offer (it was always 0)
  - Decision: existing projects are set to the 8% VAT rate, looked up by value (not by a fixed id). Layer: both. Changeset 255, `Project.vatRate`, `OfferService.vatRateOf`.
  - _Requirements: FOR-04-13; cross-spec FOR-05-07_
  - _Depends on: none_
- [~] 2. In project editing: allow adding a client (was only on creation)
  - Layer: both. New `POST /api/projects/{id}/client`, `ClientBlock` in edit mode.
  - _Requirements: FOR-04-13_
  - _Depends on: none_
- [ ] V3. Validate Round 3 in browser
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 3

- **Wave 1:** 1, 2 touch the same form and update service; run serially.
- **Wave 2:** V3.

## Round 3 — validation results (2026-11-19)

Changesets 255 applied; all 459 projects have VAT 8% (id 2). The edit form of project 1531 shows the VAT select prefilled with 8% and the client block. Not verified in the browser: adding a new client in edit mode, VAT in a prepared offer. `ProjectVatRateAndClientTest` passes. Three older tests (`ProjectServiceProjectionTest`, `ProjectClientRolePropertyTest`) fail with NPEs in `autoAddCreator`/`toSummary`, code this change did not touch.

## Round 4 (2026-11-23)
Screen URL: `https://dev.foremen.eu/projects` (project form, `GoogleAddressAutocomplete` in `ProjectFormSheet.tsx`). Reproduction API call: `https://dev.foremen.eu/api/addresses/autocomplete?query=Jana+Kazi`. Unauthenticated, that call returns HTTP 401 (expected), so the empty result was observed with a logged-in session.

- [ ] 1. Address autocomplete always returns an empty result (query "Jana Kazi")
  - Triage: observed `[]` for every query; expected a list of predictions, each with `description` and `placeId`. Layer: backend (config and service). Suspect: `GooglePlacesService.autocomplete` returns `List.of()` when `foremen.google-places.enabled` is false, with no error or log.
  - Confirmed with gcloud (2026-11-23, no secret values printed): secret `foremen-dev-google-places-key` has one enabled version (created 2026-10-05, 39 characters). The VM `foremen-dev` (SA `foremen-dev-vm@`) points at this secret in its metadata. The live `/opt/foremen/.env` on the VM has `GOOGLE_PLACES_API_KEY` empty and `GOOGLE_PLACES_ENABLED=false`, so the backend returns `[]`.
  - Cause: the startup script gates the key on `gcloud secrets describe` (`infra/dev/vm-startup.sh` line 70, also in the live metadata). The VM SA holds only `roles/secretmanager.secretAccessor`, which has no `secrets.get`, so `describe` fails and the key is skipped. Commit `28ce51f` (2026-11-04) applied this same fix to the OCR key but not to Places. Every startup-script run rewrites `.env`, so a key added by hand is lost on the next run.
  - Ruled out: the `query` parameter is bound correctly; the key is not deleted; a Google-side failure would surface as an HTTP error rather than `[]`.
  - Note: FOR-04-13 Requirement 5.1 names the path `/api/projects/address/autocomplete`, but the code serves `/api/addresses/autocomplete`. Spec text is stale; fix t  - Note: FOR-04-13 Requirement 5.1 names the path `/api/projects/addrquirement 5.1, 5.3, 5.5_
  - _Depends on: none_
- [ ] V4. Validate Round 4 in browser
  - Log in on dev, type "Jana Kazi" in the project address field, confirm predictions appear, select one and confirm details load.
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 4

- **Wave 1:** 1 — the fix path depends on the chosen option (config or code), so it is one leaf.
- **Wave 2:** V4 — validation after the fix is deployed.
