# Bug report: FOR-07-04a-fintable-integration

Screen URL: http://localhost:3000/bank/connections (also /bank/supported-banks)

Found by the e2e suite `foremen-qa-auto/e2e/tests/for07/04a-fintable-ui.e2e.ts` against the real
Fintable API and its Plaid sandbox (backend with `FINTABLE_ENABLED=true`, `SALTEDGE_ENABLED=false`).

## Round 1 (2026-11-19)

- [x] 1. Add bank dialog does not preselect Fintable (BUG-1)
  - Triage: observed: with STUB and FINTABLE both available the provider select stays empty and the
    bank picker is disabled; expected: FINTABLE is preselected whenever it is available. Layer:
    frontend. Suspect: `foremen-frontend/src/features/bank/connections/AddBankDialog.tsx` preselects
    only when exactly one provider is available and never calls `defaultProvider()`.
  - _Requirements: 1.8, 11.1_
  - _Depends on: none_
- [x] 2. Linking, completing and sync-waiting fail against the real Fintable API (BUG-2)
  - Triage: observed: Go to bank always answers 409 `error.bank.fintable.linkFailed`; `complete` with
    a valid sandbox `connection_id` leaves the connection `PENDING_AUTH` with no error; expected: the
    browser is sent to `https://fintable.io/api-link/...` and `complete` binds the connection
    (`ACTIVE`, first sync runs). Cause: Fintable wraps single-object responses in
    `{"data": {...}, "workspace_id": ...}` (`POST /connections/link`, `GET /connections/{id}`,
    `GET /sync`, ...), but the connector reads the fields at the top level. Lists were already
    unwrapped. The fake server in `FintableSyncIntegrationTest` returned bare objects, so tests passed.
    Layer: backend. Suspect: `FintableClient` (single-object calls), `FakeFintableServer`, fixtures.
  - _Requirements: 5.1, 5.3, 5.4, 6.1, 9.4_
  - _Depends on: none_
- [x] 3. Bank search and popular banks list non-Polish banks (BUG-3)
  - Triage: observed: PL catalogue search for `mBank` / the popular list show US (PLAID, AKOYA),
    CZ and SK groups; expected: banks of the configured `country-codes` (PL). Fintable's `country`
    parameter only reorders results; each group carries `countries`, which the connector ignores.
    Layer: backend. Suspect: `FintableConnector.mapInstitutions`.
  - _Requirements: 2.1 (`country-codes`), 4.2_
  - _Depends on: none_
- [x] V1. Validate Round 1 in the browser
  - Rebuild backend and frontend, run `tests/for07/04a-fintable-ui.e2e.ts` with the BUG skips
    removed, and finish the TC-B-3 sandbox cases (status, accounts, refresh limit, disable).
  - _Depends on: 1, 2, 3_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1, 2 — different repos (frontend dialog vs backend client).
- **Wave 2:** 3 — touches `FintableConnector`, kept after 2 to avoid concurrent edits in the package.
- **Wave 3:** V1 — validation, depends on all fixes.

## Round 1 — validation results (2026-11-19)

Stack rebuilt (`docker compose up -d --build backend frontend`, backend with `FINTABLE_ENABLED=true`,
`SALTEDGE_ENABLED=false`, real `FINTABLE_API_TOKEN`). Browser run:
`foremen-qa-auto/e2e/tests/for07/04a-fintable-ui.e2e.ts` — 19 passed, 0 skipped, 0 failed.

| # | Bug | Fix | Evidence |
|---|-----|-----|----------|
| 1 | Add bank does not preselect Fintable | `AddBankDialog` uses `defaultProvider(available)` | TC-B-1 step 1 passes with STUB + FINTABLE available; vitest `FintableUi.test.tsx` "preselects Fintable when another provider is available too" |
| 2 | Link / complete / sync-wait fail on the real API | `FintableClient.object()` unwraps `{data: {...}}` for `me`, `connection`, `mintLink`, `mintReconnectLink`, `deleteConnection`, `sync`, `syncOne`; `FakeFintableServer` now answers in the real shapes | TC-B-1 step 5 redirects to `https://fintable.io/api-link/...`; TC-B-3:| 2 | Link / complete / sync-wait fail on the real API | `FintableClient.object()` ows| 2 | Link / complete / sync-wait fail on thenta| 2 | Link / complete / sync-wait fail on the real API | `FintableClient.object()` unwraps `{data:mit | 2 | Link / complete / sync-wait fail on the real API | `Fintabync| 2 | Link / complete / sync-wait fail on the real API | `FintableClient.object() in search / | 2 | Link / compleConnector.| 2 | Link / complete / syn of the requested country (all groups when none matches) | `GET /api/bank/providers/FINTABLE/banks?q=mBank` returns only the 3 PL variants; TC-B-4 step 2 asserts the US-only "mBank - Business" is absent; `FintableCatalogueCountryTest` (3) green |

Observation (not fixed, outside this round): the mapper gives Plaid `loan`, `mortgage`,
`line of credit`, `investment / 401k`, `ira` accounts the nature `account`, so they default to
`includedInLedger = true`. Relevant only for US (Plaid) accounts; Polish banks report
checking / savings / card.
