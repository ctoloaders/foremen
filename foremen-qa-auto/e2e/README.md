# foremen-qa-auto/e2e

UI tests written with [e2e](https://github.com/e2e-inc/e2e) (TypeScript, Playwright engine), run
against the live Docker stack. They sit beside the Java/Cucumber suite and do not replace it.
Cases mirror the numbered cases of each spec's `test-cases.md` (the TC id is in the test title).

## Prerequisites
- Node 24.8+ (`nvm use 24`).
- Stack up: `docker compose up` in the repo root (frontend :3000, backend :8080, postgres container
  `foremen-postgres-1`). `docker` must be on PATH: test users are inserted with `docker exec ... psql`.
- If `npm install` fails with `E401`, your `~/.npmrc` holds an expired token. Use a clean one:
  `NPM_CONFIG_USERCONFIG=<file with registry=https://registry.npmjs.org/> npm install`.

## Run
```bash
npm install
npx e2e run                 # whole suite
npx e2e run tests/for10     # one spec
```
Reports: `.e2e/summary.md` (markdown) and `.e2e/results/<test>/trace.md` (per failed test). `.e2e/` is git-ignored.

## Config
`e2e.config.ts`: base URL `QA_BASE_URL` (default `http://localhost:3000`); ADMIN login from
`FOREMEN_ADMIN_EMAIL` / `FOREMEN_ADMIN_PASSWORD` (defaults match `docker-compose.yml`).
The UI language is pinned to PL. `tests/auth.setup.e2e.ts` signs in once and shares the `admin` session.

Support code (`tests/support/`):
- `users.ts` creates an ACTIVE user with a known password by a direct DB INSERT (bcrypt, cost 12), the
  same way as the Java `TestUserFixture`, and deletes it with its FK rows. Overrides: `QA_PG_CONTAINER`,
  `QA_PG_USER`, `QA_PG_DB`.
- `api.ts` is setup/teardown through the backend API (`QA_API_URL`, default `http://localhost:8080`):
  projects, custom roles and their grants, clearing or filling a user's dashboard.
- `login.ts` signs a test user in through the login form.

## Conventions
- One `describe` per feature, one behaviour per test, title starts with the TC id.
- Locators: role + name or `testid`. No `agent.*` steps, so no model key is needed.
- Repeatability: every created entity carries a run-id (`dash+<run-id>-<tag>@example.com`,
  `DASH-<run-id>-A`, role `DASH_<RUN-ID>`) and is removed in the test's `finally`. Seeded data and the
  seeded ADMIN layout are left as they were.
- A known product bug gets a `test.skip(...BUG...)` with the cause in a comment; remove `.skip` once fixed.

## Coverage
| Spec | File | Cases |
|---|---|---|
| FOR-10 | `tests/for10/panel.e2e.ts` | TC-B1-01 (toolbar), TC-B2-01, TC-B3-01, TC-B3-04 |
| FOR-10 | `tests/for10/grid.e2e.ts` | TC-B1-01 (4/3/1 columns, order on phone), TC-B1-02 (error + retry) |
| FOR-10 | `tests/for10/date-filter.e2e.ts` | TC-B2-02, TC-B2-03, TC-B2-04, TC-B2-05 |
| FOR-10 | `tests/for10/panel-roles.e2e.ts` | TC-B1-03, TC-B3-02, TC-B3-05, TC-B3-06 |
| FOR-10 | `tests/for10/project-widget.e2e.ts` | TC-B3-03 |
| FOR-10 | `tests/for10/additional-api.e2e.ts` | TC-DX-A01..A09 (`additional-test-cases.md`) |
| FOR-10 | `tests/for10/additional-ui.e2e.ts` | TC-DX-B01..B12 (`additional-test-cases.md`) |
| FOR-11-00..05 | `tests/for11/<NN-spec>/*.e2e.ts` | Parts A and B of each child spec's `test-cases.md`; per-folder `FINDINGS.md` has the coverage table (pass / partial / not automated) |
| FOR-04-11 | `tests/for04/11-standard-work-sets.e2e.ts` | TC-UI-SWM-01..08 (Part C of `test-cases.md`; matrix, groups, toolbar, save, leave dialog, read-only and /403). Not automated: RU locale names (SWM-04 step 4), empty states (SWM-09), sticky column (SWM-03) |

FOR-11 notes: the whole `tests/for10 tests/for11` run takes about 30 minutes; run it in the background and
use `--output .e2e-<name>` so parallel runs do not overwrite each other's traces. Environment skips in the
local stack: expired GCS credentials (photo upload, proforma PDF), STUB tracking and crons off, a
`finishing_materials.package_volume` column from another branch.

Not covered yet in B1–B3: TC-B1-02 loading skeleton and «Нет данных» are not asserted on purpose
(timing-dependent / data-dependent); TC-B2-03 step 2 (new browser context gets the current month) is
covered implicitly by TC-B2-01, which always starts in a fresh context.

## Known issues found by this suite
Tracked in `.kiro/specs/FOR-10-dashboard/bug-report.md`. Round 1 (BUG-1..9) is fixed; its tests in
`tests/for10/additional-*.e2e.ts` now run as regular tests. A new open bug gets a `test.skip` test with
its `BUG-N` id until it is fixed.
