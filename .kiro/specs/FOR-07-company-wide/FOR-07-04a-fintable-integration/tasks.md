# Implementation Plan: FOR-07-04a — Fintable bank feed connector

## Overview

Order: schema + SPI generalisation + provider flags → configuration → Fintable client / mapper /
connector → token-expiry notification → message bundles → tests → frontend → runbook → test-cases →
rollout with a real Polish bank → checkpoint.

**Spans two git repos** (`.kiro/steering/git-repo-structure.md`):
- **Root repo** — changeset, backend code and tests, message bundles, `application.yml`,
  `docker-compose.yml`, `.env.example`, runbook, this spec. Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — `features/bank` changes and `locales/{pl,ru}.json`. Commit
  from inside `foremen-frontend/`.

**Migrations.** Next free changeset is `254` (tail: `253-drop-legacy-payment-columns.xml`); re-check
the tail before creating it and take the next free number if a sibling spec landed first.

**Secrets.** The real token lives only in `.secrets/fintable.env` (git-ignored, mode 600), the VM
environment or Secret Manager. Never commit it, never print it, never put it in a test fixture.

**Test-run policy (backend).** Never run the full suite (~20 min). Run only the affected classes with
`--tests`, redirect output to `/tmp/for-07-04a-test.log`, read the JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` first. Frontend: `npx vitest --run
<file>` from `foremen-frontend/`.

**Assumptions to confirm** (taken from the planning conversation, to be corrected before
implementation if wrong): no entity field needs RU / PL variants (Requirement 13.1); the i18n key sets
in `design.md`; the notification set of Requirement 12 (one new type, the rest reused).

## Parallel execution plan (waves)

- **Wave 0 (parallel):** 1.1, 1.2, 1.3, 2.1, 3.1, 5.1
  - schema + enum; SPI generalisation (SPI, Salt Edge connector, orchestrator, supported-banks
    service); Salt Edge-off behaviour (scheduler, DTO, callback, `disable`); configuration
    (`application.yml`, compose, `.env.example`, properties class); backend messages; frontend types
    and locales.
- **Wave 1 (parallel):** 2.2, 5.2
- **Wave 2 (parallel):** 2.3, 2.5, 5.3
- **Wave 3:** 2.4
- **Wave 4 (parallel):** 4.1, 4.2, 6.1
- **Wave 5:** 7
- **Wave 6:** 8.1
- **Wave 7:** 8.2
- **Wave 8:** 9

## Tasks

- [x] 1. Schema, SPI and provider flags
  - [x] 1.1 Changeset `254-fintable-provider.xml` + `FeedProvider.FINTABLE` + provider-state entity
    - Create the changeset (Requirement 15.1): drop / re-add `ck_bank_connections_provider` with
      `'FINTABLE'`; create `bank_feed_provider_state`. Guard with `preConditions onFail="MARK_RAN"`.
      Register it last in `database_files/changelog.xml`.
    - Add `FINTABLE` to `FeedProvider` (update its Javadoc). Add `BankFeedProviderStateEntity` (base
      class and id strategy copied from an existing bank entity) and `BankFeedProviderStateDao`.
    - Fix every `switch` / use site of `FeedProvider` that no longer compiles or is non-exhaustive.
    - _Requirements: 15.1, 15.2, 15.3_
  - [x] 1.2 Generalise the SPI
    - `BankFeedConnector`: add `default String resumeCursor(String previous, List<TransactionPage>
      pages)` (returns `null`), `default void awaitProviderSync(BankConnectionRef, Duration)`
      (no-op), `default boolean catalogueSearchOnly()` (`false`), `default List<ProviderBank>
      searchBanks(String countryCode, String query)` (empty).
    - `ProviderBank`: add nullable `via` + a compatibility constructor with the existing 14 arguments.
    - Move the Salt Edge max-id logic from `BankSyncOrchestrator.resumeCursor` into
      `SaltEdgeConnector.resumeCursor`; the orchestrator delegates to the connector. The STUB keeps
      returning `null`.
    - Orchestrator: call `connector.awaitProviderSync(ref, maxWait)` after `fetchConnectionState` and
      before `fetchAccounts` (wait comes from the connector; add a `Duration maxWait()` default method
      or a constant read through the connector, whichever keeps the SPI minimal). Wrap in the existing
      `ConnectorError` handling; any other exception is logged and ignored.
    - `SupportedBanksService`: route `catalogueSearchOnly()` providers to `searchBanks`; keep the
      Salt Edge `emptyReason` behaviour. `BankProviderController.banks`: `q` length validation for
      search-only providers (`400 error.bank.fintable.searchTooShort`); `BankProviderDto.searchOnly`.
    - Update `BankSyncOrchestratorTest`, `SupportedBanksServiceTest`, `SaltEdgeConnectorTest`,
      `BankFeedConnectorRegistryTest` to the new signatures; all existing assertions must keep holding.
    - _Requirements: 4.1, 4.7, 8.3, 9.1_
  - [x] 1.3 Behaviour when a provider is off (Salt Edge)
    - `BankSyncScheduler.tick`: skip connections whose provider connector is unavailable (no run, no
      status change, no notification).
    - `BankConnectionDto`: add `providerAvailable`; fill it in `BankAdminDtoMapper` from the registry.
    - `BankConnectionService.disable`: skip the provider call when the connector is unavailable.
    - `SaltEdgeCallbackController`: answer `200 {"ignored":"provider disabled"}` when the Salt Edge
      connector is unavailable, before signature verification, with no state change.
    - Tests: `BankSyncSchedulerTest` (skip), `SaltEdgeCallbackControllerTest` (ignored), a
      `BankConnectionService` disable test.
    - _Requirements: 1.2, 1.4, 1.5, 1.6, 1.7, 1.9_

- [x] 2. Fintable connector
  - [x] 2.1 Configuration and availability
    - `FintableProperties` (`@ConfigurationProperties("foremen.bank-feed.fintable")`, Requirement 2.1)
      with `problems()` (property names only) and `isAvailable(PublicBaseUrl)`.
    - `FintableConfig` (`@ConditionalOnProperty` `enabled=true`) registering the client and
      connector beans; ERROR log of `problems()` once when enabled but unavailable.
    - `application.yml` block (design §Configuration); `docker-compose.yml` variables;
      `.env.example` commented block pointing to `.secrets/fintable.env`.
    - `FintablePropertiesTest` (availability matrix: flag off, blank token, HTTP base URL with and without
      `allow-insecure-base-url`, blank public base URL, valid).
    - _Requirements: 1.1, 1.2, 2.1, 2.2, 2.3, 2.4, 2.5_
  - [x] 2.2 `FintableClient`, response records, `FintableErrorMapper`
    - `RestClient`-based client with the methods of design §FintableClient; `Authorization` set in one
      place and never logged; optional `workspace_id`; public `institutions` call without
      `Authorization`.
    - `FintableErrorMapper`: the status table of Requirement 3.2, `Retry-After`, provider `type` and
      truncated `message` as provider code / message.
    - `FintableClientTest` + `FintableErrorMapperTest` (headers, every row of the table, `Retry-After`,
      the token never appears in an exception message or log capture).
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 14.6_
    - _Depends on: 2.1_
  - [x] 2.3 `FintableMapper`
    - Pure mapping functions of design §FintableMapper: account, transaction, counterparty by
      direction with candidate paths per backing provider and fallbacks, `ProviderBank` from an
      institution group connection, `maxUpdatedAt`.
    - Fixtures under `src/test/resources/fintable/`: sandbox Plaid account / transaction samples
      (from the 2026-11-18 run, without any token or personal data), hand-built GoCardless and Enable
      Banking `raw` samples, an institution search response.
    - `FintableMapperTest` + `FintableMapperPropertyTest` (jqwik).
    - _Requirements: 4.2, 7.1, 8.4, 8.5, 8.6, 16.1, 16.2_
    - _Depends on: 2.2_
  - [x] 2.4 `FintableConnector`
    - Implement the SPI per design §FintableConnector: catalogue search (1 h cache, popular banks,
      provider preference), authorization with the local 1/min guard and the HTTPS rule for
      `redirect_url`, `completeAuthorization` (params, status handling, fallback binding through the
      `BoundConnectionIds` port), reconnect, state mapping, holder, accounts, transactions with the
      cursor encoding, `resumeCursor`, `requestRefresh` spacing, `awaitProviderSync` with
      `FintableRefreshTracker`, `disable`.
    - `BoundConnectionIds` port in `service.bank.connection`, implemented by `BankConnectionService`.
    - `FintableConnectorTest` (every method incl. fallback binding with 0 / 1 / 2 unbound candidates,
      `status=error`, cursor round trip, refresh spacing, wait timeout continues).
    - _Requirements: 4.1–4.6, 5.1–5.9, 6.1–6.4, 7.1–7.4, 8.1–8.8, 9.1–9.7, 10.1, 10.2_
    - _Depends on: 1.2, 2.2, 2.3_
  - [x] 2.5 Token-expiry notification
    - `FintableTokenExpiryCheck` (design §Token expiry notification) called from `BankConsentSweep`;
      persisted markers in `bank_feed_provider_state` (claim in `REQUIRES_NEW`, reset when
      `token-expires-on` changes); `FintableTokenExpiringEvent`; listener in `BankNotificationEmitter`
      (`AFTER_COMMIT`, ADMIN + FINANCIER, `body = null`, deep-link `/bank/connections`); one WARN at
      start-up when the expiry date is blank.
    - `FintableTokenExpiryCheckTest` (thresholds 30 / 7 / 0, once per level, reset on a new date,
      blank date, disabled provider).
    - _Requirements: 12.1–12.5_
    - _Depends on: 1.1, 2.1_

- [x] 3. Backend messages
  - [x] 3.1 Message bundles
    - Add the `error.bank.fintable.*` keys of design §Backend messages to `messages.properties` (PL)
      and `messages_ru.properties` (RU) next to the other `error.bank.*` keys; identical key sets.
    - _Requirements: 13.2_

- [x] 4. Backend tests
  - [x] 4.1 Run and complete unit tests
    - Run the unit classes of tasks 1.2, 1.3, 2.1–2.5 together; fix failures; ensure the existing Salt
      Edge test classes still pass (Requirement 16.4).
    - _Requirements: 16.1, 16.4, 16.6_
    - _Depends on: 2.4, 2.5_
  - [x] 4.2 Integration tests
    - `FintableStartupIntegrationTest` (both flags off, and Fintable on with a valid fake token:
      context starts, registry shape).
    - `FintableSyncIntegrationTest`: a local fake Fintable HTTP server (connections, accounts, two
      transaction pages, `/sync` states); run connect → complete → sync; assert accounts, ledger
      rows, stored `u:` cursor, an idempotent second run, and that no pending rows exist.
    - `SaltEdgeDisabledIntegrationTest`: `providerAvailable=false`, scheduler skip, callback ignored,
      local disable.
    - Token-expiry notification integration test with the markers.
    - _Requirements: 16.3, 16.6_
    - _Depends on: 1.3, 2.4, 2.5, 3.1_

- [x] 5. Frontend (repo: `foremen-frontend/`)
  - [x] 5.1 Types, helpers and locales
    - `types/index.ts`: `FINTABLE`, `providerAvailable`, `searchOnly`, `via`; `helpers.ts`
      `defaultProvider` prefers `FINTABLE`.
    - `locales/pl.json` and `ru.json`: every key of design §Frontend i18n, identical sets; extend
      `locale-parity.for0704.test.ts`.
    - _Requirements: 1.8, 11.1, 11.7, 13.3, 13.4_
  - [x] 5.2 Components
    - `BankPicker` search mode; `AddBankDialog` Fintable notes + attribution + cooldown;
      `ConnectionsTab` attribution, `providerDisabled` badge, disabled actions, "Finish connection",
      disable confirmation with the purge warning; `SupportedBanksTab` search mode;
      `ConnectionReturnPage` copy for `status=error`; verify `lib/errors.ts` renders
      `error.bank.fintable.*` and the refresh-limited message.
    - _Requirements: 1.6, 4.8, 10.3, 11.2–11.6_
    - _Depends on: 5.1_
  - [x] 5.3 Frontend tests
    - vitest: `BankPicker` (min 3 chars, popular list, via), `AddBankDialog` (Fintable notes,
      attribution link), `ConnectionsTab` (badge, finish, disable warning), `bank-api` provider
      type; run the locale-parity test.
    - _Requirements: 11.2–11.7, 13.3_
    - _Depends on: 5.2_

- [x] 6. Documentation
  - [x] 6.1 Runbook
    - Add a Fintable chapter to `FOR-07-04-bank-ledger/rollout-runbook.md`: obtaining and rotating the
      PAT (1 year), `.secrets/fintable.env`, Return_Hosts setup per environment, plan choice and the
      sync limits (Personal 2/day, Business 1/h and `FINTABLE_MANUAL_SYNC_INTERVAL`), the one-time
      `PATCH /sync/schedule` to 05:30 Europe/Warsaw, switching Salt Edge off, first-party-only and
      attribution rules, data-processor note, trial-end warning.
    - _Requirements: 9.3, 14.1, 14.5_
    - _Depends on: 2.4_

- [x] 7. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown,
    regression group, MD report template), authored in Russian. Both surfaces: API scenarios against
    the Dockerized app with a fake Fintable server or the Fintable sandbox, browser-engine scenarios
    for the Bank page, and a manual real-bank scenario. Result artifacts are MD reports with tables.
  - _Requirements: 16.5_
  - _Depends on: 4.2, 5.3, 6.1_

- [ ] 8. Rollout and real-bank verification (manual, needs the user)
  - [~] 8.1 Environment rollout
    - Subscribe to the chosen Fintable plan (the trial ends 2026-11-20); fill `.secrets/fintable.env`
      (token, expiry date); add the environment's host to Return_Hosts; set `FINTABLE_ENABLED=true`,
      `SALTEDGE_ENABLED=false`, `FOREMEN_PUBLIC_BASE_URL`; run `PATCH /sync/schedule` to 05:30
      Europe/Warsaw; deploy; verify the Add bank flow with the sandbox, then one real bank.
    - _Requirements: 1.1, 2.4, 5.9, 9.3_
    - _Depends on: 7_
  - [~] 8.2 Real-bank payload verification
    - With one real Polish bank connected (prefer an `ENABLEBANKING` slug, then `NORDIGEN`), read one
      page of `GET /transactions?include=raw` and check which `raw` fields carry counterparty name,
      IBAN, NIP and title. Update the candidate-path lists in `FintableMapper`, replace the hand-built
      fixtures with sanitized real captures (no names, IBANs or amounts of real people), re-run the
      mapper tests, and record the result in the runbook.
    - _Requirements: 8.5, 16.2_
    - _Depends on: 8.1_

- [~] 9. Final checkpoint
  - Affected backend test classes green (JUnit XML); frontend vitest green; locale parity green;
    `test-cases.md` run report attached; both repos committed on a feature branch when the user asks.
  - _Requirements: all_
  - _Depends on: 8.2_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "2.1", "3.1", "5.1"] },
    { "id": 1, "tasks": ["2.2", "5.2"] },
    { "id": 2, "tasks": ["2.3", "2.5", "5.3"] },
    { "id": 3, "tasks": ["2.4"] },
    { "id": 4, "tasks": ["4.1", "4.2", "6.1"] },
    { "id": 5, "tasks": ["7"] },
    { "id": 6, "tasks": ["8.1"] },
    { "id": 7, "tasks": ["8.2"] },
    { "id": 8, "tasks": ["9"] }
  ]
}
```
