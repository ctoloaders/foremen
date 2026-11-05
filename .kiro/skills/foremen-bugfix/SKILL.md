---
name: foremen-bugfix
description: >-
  Run a structured Foremen bugfix session driven by reported bugs against one or
  more running UI screens. Use when the user starts a bugfix session, gives a UI
  URL (or several URLs) plus a bug list, or asks to triage/fix bugs against
  running screens. Each reported bug is routed to the spec that OWNS the screen
  it was observed on, so a single session can span MANY specs at once: it
  authors/updates a bug-report.md in EACH owning spec folder (same structure and
  logic as tasks.md: checkbox leaves with dependency annotations + a
  parallel-execution waves section), WAITS for the user's approval before
  changing code, then fixes backend and/or frontend, brings up the local Docker
  stack, validates each fix in the browser, and marks ONLY confirmed-fixed bugs.
  Supports multiple rounds by appending new rounds to the relevant bug-report.md
  until all bugs across all specs are closed.
parameters:
  - name: screens
    description: >-
      One or more UI URLs under test, each with the bugs observed on it. A
      single URL is the common case; multiple URLs (each with its own bugs) are
      fully supported and routed to their respective owning specs.
  - name: bugs
    description: >-
      The reported bugs, one bug per line, each associated with the screen/URL
      it was observed on. When several screens are given, group the bugs under
      their screen.
---

# Foremen bugfix session (URL(s) + bug list)

Turn reported UI screens and a one-bug-per-line list into a reviewed, verified set of fixes. Bugs
may span a single screen or MANY screens at once; each bug is routed to the spec that owns its
screen. The driving artifact is a per-spec **`bug-report.md`** that mirrors `tasks.md` in structure
and logic (checkbox leaves, `_Requirements:_` / `_Depends on:_` annotations, and a "Parallel
execution plan (waves)" section). This skill is an approval-gated loop: triage → author/append the
per-spec bug-reports → **wait for approval** → fix → build stack → validate in browser → mark only
fixed → repeat for new rounds.

## Inputs (parameters)

- **screens** — one or more UI URLs the bugs were observed on. Each URL resolves to an owning spec
  and drives browser validation (route, project id, tab). A single URL is the common case; multiple
  URLs are supported.
- **bugs** — the reported bugs, **one per line**, each tied to the screen it was seen on. Each line
  becomes one leaf item in the bug-report of that screen's owning spec.

If the screens or the bugs are missing, ask the user before proceeding. Never invent bugs.

## Multi-spec routing (the core of a batch session)

A session's bug list is frequently a mix of unrelated bugs across different screens/specs — e.g.
estimate + schedule + payroll in one message. Do **not** force them into one bug-report. Instead:

1. **Group the reported bugs by owning spec.** Resolve each bug's screen to its spec (Step 1); build
   a mapping `spec-id → [bugs for that spec]`.
2. **One `bug-report.md` per owning spec.** Each spec that receives at least one bug gets its own
   `bug-report.md` round (created or appended). A bug is written ONLY into its owning spec's report.
3. **Round numbering is per spec**, independent across specs (spec A may be on Round 3 while spec B
   gets its Round 1). Date the rounds the same (the session date).
4. **Classify bug-sized vs feature-sized up front.** A bugfix round fits a defect or a small,
   well-scoped change. If a reported "bug" is actually a sizable new feature (new entity/table +
   migration, a new cross-cutting capability), say so during triage and offer to split it into a
   proper spec (requirements/design/tasks) instead of cramming it into a bug-report round — but
   still record it in the owning spec's bug-report so nothing is dropped. Let the user decide the
   split.
5. **Parallelize investigation.** For a broad batch, dispatch several **context-gatherer** sub-agents
   in parallel (one per screen/area) to map URL → component → backend endpoint → owning spec before
   writing anything. For large, independent fix streams, you may delegate whole per-spec work streams
   to **general-task-execution** sub-agents (only in Autopilot mode); keep the critical path yourself.

The rest of the workflow (approval gate, fixing, validation, marking) runs **per spec** but under a
**single session-wide approval gate** and a **single final build + browser-validation pass**.

## Session rules (the contract with the user)

1. The user gives one or more UI screens and describes all bugs, one bug per line, grouped by screen.
2. Resolve each bug's owning spec and create/append a `bug-report.md` in **each** affected spec
   folder — same structure and logic as `tasks.md`.
3. **The user reviews and approves before any code changes.** One approval gate covers the whole
   batch; the user may approve all, a subset, or a per-spec selection. Fixes may span backend and
   frontend.
4. After fixes, bring up the local Docker stack and validate in the browser. Mark **only** bugs you
   have confirmed fixed, in their own spec's report.
5. Multiple rounds are expected: when the user reports new bugs, **append** a new round to the
   relevant spec's `bug-report.md` (never overwrite earlier rounds).
6. Continue until all bugs across all specs are addressed.

## Step 1 — Resolve the owning spec for EACH screen

For every screen/URL in the batch:

1. Map the URL path to the feature it belongs to. The frontend router + route-permission config and
   the spec OVERVIEW files are the source of truth:
   - Inspect `foremen-frontend/src/app/router.tsx`, `foremen-frontend/src/config/route-permissions.ts`,
     and the relevant `foremen-frontend/src/features/**` folder to identify the screen/component.
   - Match that feature area to a spec folder under `.kiro/specs/**/<spec-id>/` (use each family's
     `OVERVIEW.md` and the spec `requirements.md`/`design.md` to confirm ownership).
2. If a URL spans more than one spec (e.g. a shared shell plus a feature tab), pick the spec that
   owns the **buggy behavior**, not the shell. If still ambiguous, ask the user which spec with a
   brief `user_input`.
3. Confirm each spec folder exists. Its `bug-report.md` lives at `.kiro/specs/**/<spec-id>/bug-report.md`.
   A spec that doesn't have one yet gets a new file (Round 1); one that already has rounds gets an
   appended round.
4. For broad or unfamiliar screens, dispatch **context-gatherer** sub-agents (in parallel across the
   batch) to map URL → component → backend endpoints → spec before writing anything, so triage is
   grounded in how the code actually works.

Produce the `spec-id → bugs` grouping explicitly before writing any report.

## Step 2 — Triage each bug and author the per-spec `bug-report.md`(s)

For **each** affected spec, create `bug-report.md` (or append a new round — see Step 6) following the
**tasks.md** standards exactly (`.kiro/steering/tasks-parallel-waves.md`):

- **One leaf per reported bug line** (that belongs to this spec). Keep the user's wording as the bug
  title; add a short triage note: observed vs expected, suspected layer (frontend / backend / both),
  suspected file(s), and the `_Requirements:_` reference from THIS spec that the correct behavior
  maps to. Flag feature-sized items here (per Multi-spec routing #4).
- **Dependency annotation on every leaf** (`_Depends on: none_` or the ids it depends on). The graph
  MUST be a DAG. Dependencies are within a spec's own report (cross-spec coupling is rare; if a fix
  genuinely depends on another spec's change, note it in prose).
- **A "Parallel execution plan (waves)" section** per spec round. Group mutually independent bug
  fixes into ordered waves; serialize fixes that touch the same file / changeset / shared state. The
  final validation step is a late, dependency-heavy wave of its own.
- Written in **English** (like tasks.md), regardless of the language the bugs were reported in.
- Record the **screen URL** each round pertains to (so validation knows where to reproduce).
- Do **not** mark anything done yet; every bug starts unchecked `[ ]`.

Structure each round under a dated/numbered round heading so multiple rounds coexist:

```markdown
# Bug report: <spec-id>

Screen URL: <URL for this spec's screen>

## Round N (<date>)

- [ ] 1. <bug title exactly as reported>
  - Triage: observed `<...>`; expected `<...>`. Layer: frontend|backend|both. Suspect: `<file(s)>`.
  - _Requirements: <spec req id>_
  - _Depends on: none_
- [ ] 2. <next bug for THIS spec>
  - ...
  - _Depends on: none_
- [ ] VN. Validate Round N in browser
  - Bring up the local stack, reproduce each fixed bug's steps at the URL, confirm fixed.
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round N

- **Wave 1 (parallel):** 1, 2 — independent; different files.
- **Wave 2:** VN — validation, depends on all fixes.
```

When a batch touches several specs, keep a short running index in your reply (not in the files) of
`spec-id → round → bugs` so the user can review the whole batch at a glance.

## Step 3 — STOP and get approval (hard gate)

After writing/appending **all** the per-spec `bug-report.md`(s), present them together and **wait for
the user's explicit approval** before changing any code. This is a hard gate — do not start fixes, do
not edit backend/frontend source, do not run the stack for validation until the user approves. One
gate covers the whole batch; the user may approve everything, a subset, or a per-spec/per-bug
selection, and may ask to re-triage or to split feature-sized items into their own specs. Honor the
selection exactly.

## Step 4 — Fix the approved bugs (backend and/or frontend), spec by spec

Work the approved leaves following each spec's wave plan. Independent specs/streams can be worked in
parallel (optionally via `general-task-execution` sub-agents in Autopilot mode); respect the
workspace standards everywhere:

- **Two repos** (`.kiro/steering/git-repo-structure.md`): frontend source lives in the nested
  `foremen-frontend/` repo (the root repo ignores it); backend/spec/infra live in the root repo.
  Make edits in the correct tree. Commit **only if the user asks** — then one commit per repo (NOT
  per spec), staging only the touched files (never `git add .`; a batch touches many unrelated files,
  so enumerate your files explicitly and leave everything else unstaged), no force-push, no
  git-config changes. Commit to the branch the user names (e.g. `main`) only when they say so.
- **New managed entity?** If a fix introduces one, follow `.kiro/steering/entity-creation-rules.md`
  (resource seed changeset + ADMIN grant + `@PermissionResource`/`@PermissionOperation` +
  `getProjectIdPath()` for project-scoped services).
- **DB migrations:** a new column/table needs a Liquibase changeset registered LAST in
  `changelog.xml`; take the next free changeset number at edit time (other sessions may be adding
  changesets concurrently). The local stack must apply it (see Step 5).
- **i18n:** any new/changed user-facing string needs keys in both `foremen-frontend/src/locales/pl.json`
  and `ru.json`; any new i18n entity field needs its RU/PL variants. Do not hard-code UI text.
- **Backend tests while fixing** (`.kiro/steering/test-execution-rules.md`): never run the full
  `./gradlew build`/`test` (~20 min). Run only affected classes with `--tests`, redirect to
  `/tmp/<spec-id>-test.log`, and read that log / the JUnit XML under
  `foremen-backend/build/test-results/test/`. Use `getDiagnostics` (and `tsc -b` for the frontend)
  for fast per-file checks.
- Fix the root cause. If an approach fails twice, stop incremental patching, diagnose the real
  cause, and try a fundamentally different approach.

## Step 5 — Build the local stack and validate in the browser

Do ONE build + validation pass covering the whole batch:

1. Rebuild the service(s) you changed so containers pick up the fixes: `docker compose up -d --build
   backend` and/or `frontend` (postgres :5432, liquibase migrations, backend :8080 `docker` profile,
   frontend :3000). If you added a changeset, ensure liquibase applied it (verify the column/table /
   `databasechangelog` row); if the stack's liquibase is stuck, resolve it before validating so the
   running backend matches the new schema.
2. Drive the browser (Playwright MCP tools) to **each** screen in the batch, log in with the
   appropriate role, and reproduce each fixed bug's steps. Confirm observed == expected for each.
3. Capture the result per bug (pass/fail with what you saw). Treat each as the authoritative
   verification — a successful build is not proof the bug is fixed. If a screen isn't reachable
   locally (e.g. the project's status hides it), say so and fall back to code+test evidence for that
   item rather than silently marking it fixed.

## Step 6 — Mark results; append new rounds; repeat

- In **each** spec's `bug-report.md`, mark `[x]` **only** the bugs you confirmed fixed in the
  browser, and append a short "Round N — validation results" section with per-bug evidence. Leave any
  still-failing or unverified bug `[ ]` (or `[~]` for partially done) with a note on what remains.
- When the user reports a **new batch** of bugs, re-run Step 1 routing and **append** a new
  `## Round N` section (with its own waves subsection) to each relevant spec's `bug-report.md`. Never
  overwrite or renumber earlier rounds.
- Continue the triage → approve → fix → validate loop until every reported bug across all specs and
  rounds is addressed (fixed-and-verified, or explicitly closed with the user).

## Guardrails

- **Approval gate is mandatory** — no code changes before the user approves the bug-report(s). One
  gate for the whole batch.
- **Route every bug to its owning spec** — never dump bugs from different screens into one spec's
  report; never invent, merge, or silently drop a reported bug.
- **Only mark bugs verified in the browser** — never pre-check a bug off a successful build alone.
- Never run the full backend Gradle suite while fixing (~20 min); run only affected classes.
- Respect the two-repo structure for every edit and any commit; stage only your own files (a batch
  dirties many unrelated files); commit only when asked, one commit per repo.
- Flag feature-sized "bugs" and offer to split them into a real spec rather than forcing a round.
- Keep every `bug-report.md` in English; preserve every prior round when appending.

## Quick reference (end-to-end)

1. Inputs: **screens** (one or more URLs) + **bugs** (one per line, grouped by screen). Resolve the
   owning spec for EACH screen and build the `spec-id → bugs` grouping.
2. Author/append `.kiro/specs/**/<spec-id>/bug-report.md` for EACH affected spec — tasks.md structure
   (checkbox leaves, `_Depends on:_`, waves), per-spec round numbering. English. Nothing checked yet.
   Flag feature-sized items.
3. **Present ALL reports and WAIT for one batch approval.** No code until approved.
4. Fix approved bugs per spec in the right repo(s) (parallelize independent streams); i18n +
   entity-creation + migration + test-run standards apply.
5. ONE `docker compose up -d --build` (ensure new migrations applied) → validate each fix at its
   screen in the browser.
6. Mark only verified-fixed bugs in their own spec's report (+ a validation-results section); append
   new rounds for new reports; repeat until all addressed. Commit only when asked — one commit per
   repo, staging only your files.
