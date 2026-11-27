# Lifecycle suite design

## Scope

One project, followed from creation to deletion, across nine phases and 48 steps. Each step has one
acting role. The roles are ADMIN, MANAGER, ESTIMATOR, SALESMAN, FOREMAN, WORKER, FINANCIER, BUYER,
CLIENT and OTHER. The timeline is in `03-ROLE-TIMELINES.md`, the graph in `01-LIFECYCLE-GRAPH.md`, and
the access matrix in `02-ROLES-MATRIX.md`.

Not covered:
- Procurement OCR, receipt photos, proforma PDFs. They depend on Google Cloud Storage (BLK-1).
- Delivery chain (purchase request to receipt). It is covered by `tests/for11`.
- Acceptance acts (FOR-06-13). Only requirements exist.
- Client portal (FOR-09). Not started.
- Finalization gate. No spec defines it (gap G-1).

## Files

```
foremen-qa-auto/e2e/
  lifecycle/
    01-LIFECYCLE-GRAPH.md     stages, dependencies, spec gaps
    02-ROLES-MATRIX.md        live ABAC matrix for lifecycle resources
    03-ROLE-TIMELINES.md      the 48 steps, per role, with the visual graph and results
    04-SUITE-DESIGN.md        this file
    BUG-REPORT.md             single aggregated bug report (run-11)
    reports/<run-id>/         generated per run: REPORT.md, results.json, steps/, state.json, screenshots/
  tests/lifecycle/
    lifecycle.e2e.ts          the steps (one test per step, in timeline order)
    support/lc.ts             step recording, gating, per-role API, screenshots, report writer
```

## Model

- **One step, one test.** Each step is a test titled `L<nn> [ROLE] <title>`. Steps are plain tests in
  one file, run in declaration order with one worker. They are not a serial group. A serial group would
  skip every later step after the first failure and hide coverage.
- **Gating by declared needs.** A step lists `needs`. If a needed step did not pass or run as a
  WORKAROUND, the step is recorded as BLOCKED with the reason and skipped with `test.skip`. The needs
  lists are the edges of the lifecycle graph.
- **Two modes.** `UI` steps use the screens through the e2e engine with `testid` and role locators.
  `API` steps call the backend with the acting role's own token (`/api/auth/login`), so the matrix
  is tested as each role. Negative checks (403 or 404) are API steps.
- **Expectations.** A failed expectation records the actual response and the defect ID on the step.
  Known defects stay red on purpose. The suite is not wrapped in `test.fail`, so each defect shows up
  in every run until it is fixed.
- **WORKAROUND status.** A step that moves the lifecycle along a different path (L22, force activation)
  reports WORKAROUND. Its dependents run, and the report marks the path.
- **No agent steps.** No `agent.*` calls, so no model key is needed. Locators come from the accessible
  names and `data-testid` values that the frontend renders.

## Data and repeatability

- Users are created per run with a bcrypt hash through `support/users.ts`
  (`dash+<id>-<role>@example.com`). Passwords are random per run. The admin uses the configured credentials.
- The project name is `QA-LC-<run-id>`. Each run gets its own project, so runs don't collide.
- State is saved to `reports/<run-id>/state.json` after every step, so a reloaded worker resumes the run.
- **Teardown is opt-in** (`LC_CLEANUP=1`). The default run leaves its project and users in place. The
  project cannot be deleted while it has rooms (BUG-09), so a default cleanup would fail anyway. Test
  data is left in the database and can be removed by run-id if needed.

## Reporting

- `reports/<run-id>/REPORT.md`: a table of every step (role, mode, status, bug IDs, blocked-by), then
  per step what the user does, what was expected, what happened, and the screenshot if one was taken.
- `reports/<run-id>/results.json`: the same data in machine-readable form.
- `reports/<run-id>/screenshots/`: one PNG per UI step that takes a snapshot, copied from the engine's
  artifact folder.
- Engine output (traces, screenshots, `summary.md`) stays in `.e2e-lifecycle/`.

## Running

Requirements: Docker stack up (`docker compose up` in the repo root), Node 24.8 or newer (here
`~/.nvm/versions/node/v24.18.0`, since the default Node is 23).

```bash
export PATH=$HOME/.nvm/versions/node/v24.18.0/bin:$PATH
cd foremen-qa-auto/e2e
LIFECYCLE_RUN_ID=run-12 npx e2e run tests/lifecycle/lifecycle.e2e.ts --output .e2e-lifecycle --retries 0 --workers 1
```

- Without `LIFECYCLE_RUN_ID` the run id is chosen once and stored in `reports/.current-run`. Delete
  that file to start a new run.
- `LC_CLEANUP=1` tears down the project and users. Expect BUG-09 while the project has rooms.
- `QA_API_URL` (default `http://localhost:8080`) and `QA_BASE_URL` (default `http://localhost:3000`) come
  from the existing config.

## Results of the last run

`run-19` (final, on a restarted backend): 51 PASS, 0 WORKAROUND, 0 FAIL, 0 BLOCKED. Run-11 found the defects
(BLK-1, BUG-01, 02, 03, 06, 07, 09, 10). All are fixed in the backend or the tests and checked in run-19.
Details in `BUG-REPORT.md`. Known open backend issues (BUG-11 deadlock, BUG-12 heap) can fail a run that
hits them. A failed run with "fetch failed" or a missing Panel heading points to the backend, not the test.

## Known limits

- UI coverage is 11 steps. Most lifecycle actions are API steps. Each UI step checks a real screen; the
  API steps check the matrix and the state transitions.
- The `needs` graph blocks everything behind L22 when the activation path breaks. A future change could
  let the amendment chain run independently.
- The client's offer approval is through the UI (L13), but the client view does not show the offer
  status (O-2), so the outcome is read through the API.
- Repeat runs in the same database add projects and users. Cleanup by run-id is not automated yet.
