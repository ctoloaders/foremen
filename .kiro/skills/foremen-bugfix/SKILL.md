---
name: foremen-bugfix
description: >-
  Run a structured Foremen bugfix session driven by a UI URL and a list of
  reported bugs (one per line). Use when the user starts a bugfix session, gives
  a UI URL plus a bug list, or asks to triage/fix bugs against a running screen.
  Resolves the spec that owns the screen at the URL, authors/updates a
  bug-report.md in that spec folder (same structure and logic as tasks.md:
  checkbox leaves with dependency annotations + a parallel-execution waves
  section), WAITS for the user's approval before changing code, then fixes
  backend and/or frontend, brings up the local Docker stack, validates each fix
  in the browser, and marks ONLY confirmed-fixed bugs. Supports multiple rounds
  by appending new rounds to the same bug-report.md until all bugs are closed.
parameters:
  - name: URL
    description: The UI URL of the screen under test (e.g. http://localhost:3000/projects/42/team).
  - name: bugs
    description: The list of reported bugs, one bug per line.
---

# Foremen bugfix session (URL + bug list)

Turn a reported UI URL and a one-bug-per-line list into a reviewed, verified set of fixes. The
driving artifact is a per-spec **`bug-report.md`** that mirrors `tasks.md` in structure and logic
(checkbox leaves, `_Requirements:_` / `_Depends on:_` annotations, and a "Parallel execution plan
(waves)" section). This skill is an approval-gated loop: triage → author bug-report → **wait for
approval** → fix → build stack → validate in browser → mark only fixed → repeat for new rounds.

## Inputs (parameters)

- **URL** — the UI URL of the screen the bugs were observed on. Used to resolve the owning spec and
  to drive browser validation (route, project id, tab).
- **bugs** — the reported bugs, **one per line**. Each line becomes one leaf item in `bug-report.md`.

If either input is missing, ask the user for it before proceeding. Never invent bugs.

## Session rules (the contract with the user)

1. The user gives a UI URL and describes all bugs, one bug per line.
2. Resolve the corresponding spec and create/append a `bug-report.md` in that spec folder — same
   structure and logic as `tasks.md`.
3. **The user reviews and approves before any code changes.** Fixes may span backend and frontend.
4. After fixes, bring up the local Docker stack and validate in the browser. Mark **only** bugs you
   have confirmed fixed.
5. Multiple rounds are expected: when the user reports new bugs, **append** a new round to the same
   `bug-report.md` (never overwrite earlier rounds).
6. Continue until all bugs are addressed.

## Step 1 — Resolve the owning spec from the URL

1. Map the URL path to the feature it belongs to. The frontend router + route-permission config and
   the spec OVERVIEW files are the source of truth:
   - Inspect `foremen-frontend/src/app/router.tsx`, `foremen-frontend/src/config/route-permissions.ts`,
     and the relevant `foremen-frontend/src/features/**` folder to identify the screen/component.
   - Match that feature area to a spec folder under `.kiro/specs/**/<spec-id>/` (use each family's
     `OVERVIEW.md` and the spec `requirements.md`/`design.md` to confirm ownership).
2. If the URL spans more than one spec (e.g. a shared shell plus a feature tab), pick the spec that
   owns the **buggy behavior**, not the shell. If still ambiguous, ask the user which spec with a
   brief `user_input`.
3. Confirm the spec folder exists. The `bug-report.md` lives at
   `.kiro/specs/**/<spec-id>/bug-report.md`.
4. For broad or unfamiliar screens, dispatch a **context-gatherer** sub-agent to map the URL →
   component → backend endpoints → spec before writing anything, so triage is grounded in how the
   code actually works.

## Step 2 — Triage each bug and author `bug-report.md`

Create `bug-report.md` (or append a new round — see Step 6) following the **tasks.md** standards
exactly (`.kiro/steering/tasks-parallel-waves.md`):

- **One leaf per reported bug line.** Keep the user's wording as the bug title; add a short triage
  note: observed vs expected, suspected layer (frontend / backend / both), suspected file(s), and
  the `_Requirements:_` reference from the owning spec that the correct behavior maps to.
- **Dependency annotation on every leaf** (`_Depends on: none_` or the ids it depends on). The graph
  MUST be a DAG.
- **A "Parallel execution plan (waves)" section.** Group mutually independent bug fixes into ordered
  waves; serialize fixes that touch the same file / changeset / shared state. The final validation
  step is a late, dependency-heavy wave of its own.
- Written in **English** (like tasks.md), regardless of the language the bugs were reported in.
- Do **not** mark anything done yet; every bug starts unchecked `[ ]`.

Structure each round under a dated/numbered round heading so multiple rounds coexist:

```markdown
# Bug report: <spec-id>

Screen URL: <URL>

## Round 1 (<date>)

- [ ] 1. <bug title exactly as reported>
  - Triage: observed `<...>`; expected `<...>`. Layer: frontend|backend|both. Suspect: `<file(s)>`.
  - _Requirements: <spec req id>_
  - _Depends on: none_
- [ ] 2. <next bug>
  - ...
  - _Depends on: none_
- [ ] V1. Validate Round 1 in browser
  - Bring up the local stack, reproduce each fixed bug's steps at the URL, confirm fixed.
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1, 2 — independent; different files.
- **Wave 2:** V1 — validation, depends on all fixes.
```

## Step 3 — STOP and get approval (hard gate)

After writing/appending `bug-report.md`, present it and **wait for the user's explicit approval**
before changing any code. This is a hard gate — do not start fixes, do not edit backend/frontend
source, do not run the stack for validation until the user approves. The user may approve all, a
subset, or ask for re-triage. Honor the selection exactly.

## Step 4 — Fix the approved bugs (backend and/or frontend)

Work the approved leaves following the wave plan, respecting the workspace standards:

- **Two repos** (`.kiro/steering/git-repo-structure.md`): frontend source lives in the nested
  `foremen-frontend/` repo (the root repo ignores it); backend/spec/infra live in the root repo.
  Make edits in the correct tree. Commit **only if the user asks**, one commit per repo, staging
  only touched files (never `git add .`), new branch only, no force-push, no git-config changes.
- **New managed entity?** If a fix introduces one, follow `.kiro/steering/entity-creation-rules.md`
  (resource seed changeset + ADMIN grant + `@PermissionResource`/`@PermissionOperation` +
  `getProjectIdPath()` for project-scoped services).
- **i18n:** any new/changed user-facing string needs keys in both `foremen-frontend/src/locales/pl.json`
  and `ru.json`; any new i18n entity field needs its RU/PL variants. Do not hard-code UI text.
- **Backend tests while fixing** (`.kiro/steering/test-execution-rules.md`): never run the full
  `./gradlew build`/`test` (~20 min). Run only affected classes with `--tests`, redirect to
  `/tmp/<spec-id>-test.log`, and read that log / the JUnit XML under
  `foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks.
- Fix the root cause. If an approach fails twice, stop incremental patching, diagnose the real
  cause, and try a fundamentally different approach.

## Step 5 — Build the local stack and validate in the browser

1. From the repo root, bring up the stack: `docker compose up` (postgres :5432, liquibase migrations,
   backend :8080 `docker` profile, frontend :3000). Rebuild the service(s) you changed so containers
   pick up the fix: `docker compose up -d --build backend` and/or `frontend`.
2. Drive the browser (Playwright MCP tools) to the provided **URL**, log in with the appropriate
   role, and reproduce each fixed bug's steps. Confirm observed == expected for each.
3. Capture the result per bug (pass/fail with what you saw). Treat each as the authoritative
   verification — a successful build is not proof the bug is fixed.

## Step 6 — Mark results; append new rounds; repeat

- In `bug-report.md`, mark `[x]` **only** the bugs you confirmed fixed in the browser. Leave any
  still-failing or unverified bug `[ ]` with a note on what remains.
- When the user reports a **new batch** of bugs, **append** a new `## Round N` section (with its own
  waves subsection) to the same `bug-report.md`. Never overwrite or renumber earlier rounds.
- Continue the triage → approve → fix → validate loop until every reported bug across all rounds is
  addressed (fixed-and-verified, or explicitly closed with the user).

## Guardrails

- **Approval gate is mandatory** — no code changes before the user approves the bug-report.
- **Only mark bugs verified in the browser** — never pre-check a bug off a successful build alone.
- Never run the full backend Gradle suite while fixing (~20 min); run only affected classes.
- Respect the two-repo structure for every edit and any commit; commit only when asked.
- Treat the user's reported bug list as the contract; don't silently drop, merge, or invent bugs.
- Keep `bug-report.md` in English; preserve every prior round when appending.

## Quick reference (end-to-end)

1. Inputs: **URL** + **bugs** (one per line). Resolve the owning spec from the URL.
2. Author/append `.kiro/specs/**/<spec-id>/bug-report.md` — tasks.md structure (checkbox leaves,
   `_Depends on:_`, waves). English. Nothing checked yet.
3. **Present and WAIT for approval.** No code until approved.
4. Fix approved bugs in the right repo(s); i18n + entity-creation + test-run standards apply.
5. `docker compose up` (+ `--build` changed services) → validate each fix at the URL in the browser.
6. Mark only verified-fixed bugs; append new rounds for new reports; repeat until all addressed.
