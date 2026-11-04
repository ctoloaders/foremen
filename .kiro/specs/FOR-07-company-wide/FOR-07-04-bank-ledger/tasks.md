# Implementation Plan: FOR-07-04 — Company money ledger (bank + cash)

## Overview

Order: schema → pure collaborators (jqwik) → entities/DAOs → connector SPI + STUB → Salt Edge
connector (client, both mode APIs, mapper) → services (secrets, connections / accounts / holder
match / supported banks, ingest, links + effects, cash ledger, detection + reverse document matching,
AI, notifications, procurement + payroll seams, sync orchestration) → controllers + config → backend
integration tests → frontend (locales, Bank page in the administration section, ledger page in the
company section, drawer, cash entries, procurement payment choice, payroll links, navigation, tests)
→ test-cases re-check → environment rollout + Salt Edge dashboard → checkpoint.

**Spans two git repos** (`.kiro/steering/git-repo-structure.md`):
- **Root repo** — changesets 201–206, backend code/tests, message bundles, `application.yml`,
  `docker-compose.yml`, `.env.example`, this spec. Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — `features/bank`, `features/bank-transactions`, procurement
  and payroll UI extensions, `lib/iban.ts`, navigation / router / route-permissions, locales. Commit
  from inside `foremen-frontend/`.

**Migrations.** Next free changeset is `201` (tail: `200-seed-measurement-unit-m.xml`). If a sibling
spec lands changesets first, take the next free numbers in the same order.

**Environment-specific values** (public base URL, Salt Edge mode / keys, lead e-mail, fake providers,
STUB, AI, secrets key) come only from env / Secret Manager — never committed, never hard-coded.

**Test-run policy (backend).** Never run the full suite (~20 min). Run only the affected classes
with `--tests`, redirect output to `/tmp/for-07-04-test.log`, and read the JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` first. Frontend: `npx vitest --run
<file>` from `foremen-frontend/`.

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 2.1, 2.2, 2.3, 2.5, 2.6, 2.7, 2.8, 2.9, 4.1, 6.5, 6.6, 9.1
  - schema changesets (one task: every changeset edits `changelog.xml`);
  - eight independent pure collaborators (distinct files);
  - connector SPI types;
  - the FOR-06-07 and FOR-07-06 service seams (distinct files);
  - frontend locale keys.
- **Wave 2 (parallel):** 1.2, 2.4, 3.1, 4.2, 5.1, 5.2, 6.9
  - ABAC seeds (edit `changelog.xml` after 1.1);
  - rule matcher (needs 2.1);
  - entities/DAOs (need 1.1);
  - STUB connector (needs 4.1);
  - Salt Edge client + `PublicBaseUrl` (needs 4.1, 2.6);
  - Salt Edge mapper (needs 4.1, 2.1);
  - AI classifier (needs 2.5).
- **Wave 3 (parallel):** 5.5, 6.1, 6.3, 6.10, 6.11
  - Salt Edge mode APIs (need 5.1, 5.2);
  - secret service (needs 3.1, 2.7);
  - ingest (needs 3.1, 2.1);
  - notification emitter (needs 3.1);
  - procurement seams (need 3.1).
- **Wave 4 (parallel):** 5.3, 6.7
  - Salt Edge connector (needs 5.1, 5.2, 5.5);
  - link service + effects + counterparty rules (need 3.1, 2.3, 2.9, 6.5, 6.6).
- **Wave 5 (parallel):** 5.4, 6.2, 6.12
  - Salt Edge connector tests (need 5.3);
  - connection / account / holder / supported-bank services (need 6.1, 4.1, 3.1, 2.8);
  - cash ledger + hooks (need 6.7, 6.6, 6.11).
- **Wave 6:** 6.8 — detection + reverse document matching (needs 2.4, 2.5, 6.7, 6.11).
- **Wave 7:** 6.4 — sync orchestrator, scheduler, consent sweep (needs 6.2, 6.3, 2.2, 6.8, 6.10).
- **Wave 8 (parallel):** 7.1, 7.2, 7.3, 7.5, 7.6 — distinct files:
  - bank admin controllers (+ stub controller);
  - ledger controller;
  - Salt Edge callback controller + `SecurityConfig`;
  - configuration files;
  - procurement / payroll controller extensions.
- **Wave 9 (parallel):** 7.4, 9.2, 9.4 — distinct files / repos:
  - backend message bundles (need the final codes from 7.1–7.3, 7.6);
  - Bank API client (needs 7.1);
  - ledger API + page (needs 7.2, 9.1).
- **Wave 10 (parallel):** 8.1, 9.3, 9.5, 9.8
  - backend integration tests (need 7.1–7.6, 1.2);
  - Bank page tabs (need 9.1, 9.2);
  - transaction drawer + cash dialog (need 9.4);
  - procurement / payroll UI (needs 7.6, 9.1).
- **Wave 11:** 9.6 — navigation, router, route-permissions (needs 9.3, 9.5, 9.8).
- **Wave 12:** 9.7 — frontend tests (needs 9.6).
- **Wave 13:** 10 — `test-cases.md` re-sync and re-check against the implementation.
- **Wave 14:** 11 — environment rollout + Salt Edge dashboard registration (needs 8.1, 9.7, 10).
- **Wave 15:** 12 — final checkpoint.

## Task Dependency Graph

Machine-readable form of the waves above (`id` = wave number − 1). Tasks inside one wave are
mutually independent; a wave starts only after every earlier wave has completed.

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "2.2", "2.3", "2.5", "2.6", "2.7", "2.8", "2.9", "4.1", "6.5", "6.6", "9.1"] },
    { "id": 1, "tasks": ["1.2", "2.4", "3.1", "4.2", "5.1", "5.2", "6.9"] },
    { "id": 2, "tasks": ["5.5", "6.1", "6.3", "6.10", "6.11"] },
    { "id": 3, "tasks": ["5.3", "6.7"] },
    { "id": 4, "tasks": ["5.4", "6.2", "6.12"] },
    { "id": 5, "tasks": ["6.8"] },
    { "id": 6, "tasks": ["6.4"] },
    { "id": 7, "tasks": ["7.1", "7.2", "7.3", "7.5", "7.6"] },
    { "id": 8, "tasks": ["7.4", "9.2", "9.4"] },
    { "id": 9, "tasks": ["8.1", "9.3", "9.5", "9.8"] },
    { "id": 10, "tasks": ["9.6"] },
    { "id": 11, "tasks": ["9.7"] },
    { "id": 12, "tasks": ["10"] },
    { "id": 13, "tasks": ["11"] },
    { "id": 14, "tasks": ["12"] }
  ]
}
```

## Tasks

- [x] 1. Schema (root repo)
  - [x] 1.1 Create changesets 201–204 and register them in `changelog.xml`
    - `201-create-bank-connections-accounts.xml`: create the tables as in the design:
      - `bank_connections`, incl. `provider_mode` and `holder_names`;
      - `bank_connection_secrets`;
      - `bank_accounts`: `type BANK|CASH`, nullable `connection_id` / `external_id` with CHECKs,
        `opening_balance`, `default_for_cash` (partial UNIQUE per currency), `archived`,
        `holder_match`, `holder_override_*`, UNIQUE `(connection_id, external_id)`, index on `iban`;
      - `bank_balance_gaps`.
    - `202-create-bank-sync-runs-transactions.xml`:
      - `bank_sync_runs`, with a partial unique index on `connection_id` where status is `QUEUED` or
        `RUNNING`;
      - `bank_sync_run_accounts`;
      - `bank_transactions`: `channel`, `origin` (CHECK vs channel), nullable `external_id` with a
        partial UNIQUE `(account_id, external_id)`, `system_source` / `system_source_id` with a
        partial UNIQUE, CHECK `amount <> 0`, CHECK booking date when POSTED, indexes on
        `booking_date`, `link_status`, `channel`, `counterparty_key`, `counterparty_iban`.
    - `203-create-bank-links-suggestions-rules.xml`:
      - `bank_transaction_links` (CHECK `amount > 0`, per-kind target CHECK, indexes on payout /
        document);
      - `bank_transaction_suggestions`;
      - `bank_counterparty_rules` (UNIQUE `counterparty_key`).
    - `204-procurement-documents-invoice-fields.xml`: nullable `supplier_nip varchar(10)`,
      `invoice_number varchar(64)`, `invoice_date date`; index on `invoice_number`.
    - Use `NOT tableExists` / `NOT columnExists` guards and `onFail="MARK_RAN"`. Append the four
      includes last, with comments.
    - _Requirements: 1.1, 2.1, 3.1, 3.7, 3.8, 5.2, 6.1, 6.2, 7.2, 13.1, 13.3, 13.7, 17.3, 18.1, 27.1, 27.2_
    - _Depends on: none_
  - [x] 1.2 Create ABAC seeds 205–206
    - `205-seed-bank-connections-resource.xml`:
      - resource `BANK_CONNECTIONS` (RU "Банковские подключения" / PL "Połączenia bankowe" +
        descriptions);
      - grants: ADMIN and FINANCIER CREATE, READ, UPDATE, DELETE;
      - the 187 two-changeSet pattern with `MARK_RAN` guards and `NOT EXISTS`.
    - `206-seed-bank-ledger-resource.xml`:
      - resource `BANK_LEDGER` (RU "Банковские операции" / PL "Operacje bankowe");
      - grants: ADMIN, FINANCIER, MANAGER CRUD.
    - Register both last in `changelog.xml`.
    - _Requirements: 21.1, 21.2, 21.3_
    - _Depends on: 1.1_

- [x] 2. Pure collaborators + tests (root repo)
  - [x] 2.1 `IbanNormalizer`, `CounterpartyExtractor`, `NameSimilarity` + tests
    - `IbanNormalizer`: strip / uppercase, 26-digit NRB → `PL…`, mod-97 validation.
    - `CounterpartyExtractor`: counterparty key `IBAN:` / `NAME:` with diacritics and legal-suffix
      normalization (shared normalizer reused by 2.8).
    - `NameSimilarity`: token Jaccard similarity.
    - jqwik:
      - normalization is idempotent;
      - a valid NRB round-trips (Property 10);
      - similarity is symmetric and 1.0 on equal normalized names.
    - _Requirements: 6.5, 14.5, 14.6_
    - _Depends on: none_
  - [x] 2.2 `BalanceContinuityChecker` + property test
    - Pure `check(prev, prevAt, new, newAt, posted)` → optional gap (|diff| > 0.01); null balances
      skip.
    - jqwik Property 8: a gap iff there is a mismatch.
    - _Requirements: 7.1, 7.2, 7.4_
    - _Depends on: none_
  - [x] 2.3 `LinkStatusDeriver` + property test
    - Pure derivation per R13.5 from (ignored, |amount|, links[amount, source, confirmed],
      openSuggestions).
    - jqwik Property 4 (totality, IGNORED ⇒ no links) and the Σ ≤ |amount| precondition.
    - _Requirements: 13.5, 17.5_
    - _Depends on: none_
  - [x] 2.4 `RuleMatcher` + `MatchCandidate` / `TxnFacts` / `Decision` + property tests
    - Implement the scoring table and decision rule from the design: auto-threshold, margin,
      suggest-threshold, max 3.
    - Rules:
      - internal transfer and `FM-{projectId}` reference;
      - payouts limited to `BANK_TRANSFER`;
      - documents limited to `UNMATCHED` / `PARTIALLY_PAID`;
      - direction-safe kinds.
    - Expose a single-document procurement scorer reused by reverse matching (6.8).
    - jqwik Property 5 (direction safety) and Property 6 (determinism; AUTO only when threshold and
      margin hold); example tests per kind.
    - _Requirements: 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8, 28.2_
    - _Depends on: 2.1_
  - [x] 2.5 `BankTransactionClassifier` interface + `ClassifierAnswerValidator` + tests
    - Interface: `classify(ClassificationInput) → Optional<ClassifierAnswer>`.
    - Tolerant JSON parse, reusing the `OcrHelpers` fence / brace approach.
    - Validator drops an invalid kind / candidateRef / categoryCode / projectId and caps confidence
      at 0.3.
    - _Requirements: 15.1, 15.3, 15.4_
    - _Depends on: none_
  - [x] 2.6 `SaltEdgeRequestSigner` + `SaltEdgeCallbackVerifier` + property tests
    - Signer: base64 RSA-SHA256 of `Expires-at|METHOD|full_url|body`.
    - Verifier: `callback_url|body` with the key for `Signature-key-version`; bundle the documented
      public key as a classpath PEM.
    - jqwik Property 7 with a generated test key pair: round-trip is true, any byte flip is false.
    - _Requirements: 8.5, 10.2_
    - _Depends on: none_
  - [x] 2.7 `BankSecretCipher` (AES-256-GCM) + tests
    - Key from config (base64 32 bytes, key version 1); random 12-byte IV; encrypt / decrypt a JSON
      map; `isConfigured()`; tampering → exception.
    - _Requirements: 2.1, 2.5_
    - _Depends on: none_
  - [x] 2.8 `HolderMatcher` + property test
    - Pure `match(holderNames, holderTaxIds, accountHolderName, companyFullName, companyNip)`:
      - NIP digits equal → `MATCH`;
      - normalized name similarity ≥ threshold (default 0.8) → `MATCH`;
      - holder data present but no match → `MISMATCH`;
      - no holder data or no company → `UNKNOWN`.
    - Legal-form suffixes and diacritics are ignored.
    - jqwik Property 12 + examples (`Foremen Sp. z o.o.` vs `FOREMEN SPÓŁKA Z O.O.`, private person
      name, empty data).
    - _Requirements: 3.7_
    - _Depends on: none_
  - [x] 2.9 `DocumentPaymentStatusDeriver` + property test
    - Pure: Σ link amounts vs `documentTotal` → `UNMATCHED | PARTIALLY_PAID | PAID` (unknown total
      with Σ > 0 → `PARTIALLY_PAID`).
    - jqwik totality (Property 4).
    - _Requirements: 16.4, 18.3, 28.4_
    - _Depends on: none_

- [x] 3. Entities and DAOs (root repo)
  - [x] 3.1 JPA entities, enums and DAOs for the bank tables + procurement fields
    - Entities: `BankConnectionEntity`, `BankConnectionSecretEntity`, `BankAccountEntity` (type,
      cash fields, holder match / override), `BankBalanceGapEntity`, `BankSyncRunEntity`,
      `BankSyncRunAccountEntity`, `BankTransactionEntity` (channel, origin, system source),
      `BankTransactionLinkEntity`, `BankTransactionSuggestionEntity`,
      `BankCounterpartyRuleEntity`.
    - Enums: `FeedProvider`, `SaltEdgeMode`, `BankAccountType`, `HolderMatch`,
      `BankConnectionStatus`, `SyncTrigger`, `SyncRunStatus`, `BankTxnStatus`, `LedgerChannel`,
      `LedgerOrigin`, `SystemSource`, `LinkKind`, `LinkStatus`, `DetectionSource`,
      `SuggestionStatus`, `RuleOrigin`, `DocumentPaymentStatus`, `ProcurementPaymentChoice`.
    - DAOs (`AdminDao` + `JpaSpecificationExecutor`) with the lookups the services need:
      - by external ids and by counterparty key;
      - the active run per connection;
      - Σ link amount per payout / document;
      - the default cash account per currency;
      - a SYSTEM entry by source.
    - Add `supplierNip`, `invoiceNumber`, `invoiceDate` to `ProcurementDocumentEntity`.
    - _Requirements: 1.1, 2.1, 3.1, 3.7, 5.2, 6.1, 7.2, 13.1, 13.7, 17.3, 18.1, 27.1, 27.2_
    - _Depends on: 1.1_

- [x] 4. Connector SPI (root repo)
  - [x] 4.1 SPI types, registry and errors
    - Types: `BankFeedConnector` (incl. `fetchHolderInfo`), `BankConnectionRef` (incl.
      `providerMode`), `NormalizedAccount`, `NormalizedTransaction`, `TransactionPage`,
      `ConnectionState`, `AuthorizationStart`, `RefreshResult`, `RefreshMode`, `CredentialField`,
      `ProviderBank` (capabilities, `requiresOwnKeys`, `testBank`), `HolderInfo`.
    - Errors: `ConnectorError` (+ `ConnectorErrorClass`).
    - Registry: `BankFeedConnectorRegistry` (beans by provider, `available()`).
    - _Requirements: 4.1, 4.2, 4.3, 4.5, 9.2_
    - _Depends on: none_
  - [x] 4.2 `StubConnector` + `StubDataStore` + `StubProperties`
    - Enabled by `@ConditionalOnProperty(foremen.bank-feed.stub.enabled=true)`.
    - Instant authorization URL to the return route built from `PublicBaseUrl`.
    - Two stub banks in `listBanks`.
    - In-memory accounts / holder / transactions per connection, with cursor + pending semantics.
    - Unit test.
    - _Requirements: 4.4_
    - _Depends on: 4.1_

- [x] 5. Salt Edge connector (root repo)
  - [x] 5.1 `SaltEdgeProperties`, `PublicBaseUrl`, `SaltEdgeClient`, `SaltEdgeErrorMapper`
    - `@ConfigurationProperties("foremen.bank-feed.saltedge")`: incl. `mode`, the mode-default base
      URL, `lead-email`, `customer-identifier`, `banks-cache-ttl`.
    - `PublicBaseUrl` from `foremen.public-base-url`: no default, validation (blank / non-HTTPS for
      Live → connector unavailable + config log), `returnUrl` / `callbackUrl` builders.
    - `SaltEdgeClient`:
      - `RestClient` with timeout; single body serialization;
      - `App-id` / `Secret` headers; optional signing via 2.6;
      - error body → `ConnectorError` per the design mapping (incl. Partners consent classes);
      - TRANSIENT retry 3× (1 s / 4 s / 16 s);
      - redacted DEBUG logging;
      - `next_id` pagination helper.
    - Unit tests for the mapper and `PublicBaseUrl`.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 12.1, 12.2, 24.6_
    - _Depends on: 4.1, 2.6_
  - [x] 5.2 `SaltEdgeMapper` + fixture tests
    - Account and transaction mapping per the design table: signed amount, dates, title +
      information, payer / payee by direction, IBAN parsed from `*_information`, `duplicated` flag,
      payload.
    - Provider → `ProviderBank` mapping: capabilities, test bank, `requiresOwnKeys` via the
      `key_owner` diff.
    - Holder info → `HolderInfo`.
    - Tolerant of missing `extra`; a malformed row → skipped count.
    - JSON fixtures under `src/test/resources/saltedge/` (from `fakebank_simple_xf` responses and the
      providers listing).
    - _Requirements: 9.2, 11.1, 11.3, 11.4_
    - _Depends on: 4.1, 2.1_
  - [x] 5.5 `SaltEdgeModeApi` + `ClientModeApi` + `PartnerModeApi`
    - Port methods: identity (customer / lead), session create / reconnect / refresh, connection +
      consent read, holder info read, accounts / transactions / pending read, providers read, revoke
      / delete.
    - `ClientModeApi`: `/api/v6` routes per the design.
    - `PartnerModeApi`: `/api/partners/v1` — `POST /leads`, the lead-session routes, partner-consent
      revoke, data reads.
    - Pin the exact Partners routes and field names against the Partners API v1 reference and record
      them in fixtures under `src/test/resources/saltedge/partner/`.
    - _Requirements: 8.7, 9.1, 9.4, 9.5, 9.8, 9.9_
    - _Depends on: 5.1, 5.2_
  - [x] 5.3 `SaltEdgeConnector`
    - Depends only on `SaltEdgeModeApi`, selected by `mode`.
    - Methods:
      - `ensureIdentity`;
      - `listBanks` (PL + XF when fake enabled; `requiresOwnKeys` in CLIENT mode);
      - `startAuthorization` (session body exactly as the design, holder scope when supported,
        `return_to` from `PublicBaseUrl`, `custom_fields.foremenConnectionId`);
      - `completeAuthorization`, `startReconnect`;
      - `requestRefresh` (respects `next_refresh_possible_at`; background refresh not used by the
        scheduler);
      - `fetchConnectionState`, `fetchHolderInfo`, `fetchAccounts`, `fetchTransactions` (posted by
        cursor + pending);
      - `disable` (mode-specific revoke + delete; not-found = success);
      - `isAvailable` (enabled, keys, base URL, mode identity).
    - _Requirements: 8.3, 8.7, 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 11.2, 11.5, 12.4, 12.6_
    - _Depends on: 5.1, 5.2, 5.5_
  - [x] 5.4 `SaltEdgeConnectorTest` (MockRestServiceServer + fixtures, both modes)
    - Identity: customer create / duplicate (CLIENT); lead create-or-return (PARTNER).
    - Session: connect / lead-session body assertions incl. `return_to` derived from the configured
      base URL; reconnect.
    - Refresh: not-yet / allowed.
    - State mapping: active, expiring, inactive, revoked, disabled.
    - Data: paged accounts / transactions, pending rows, holder info.
    - Providers: listing with `requiresOwnKeys` and test banks.
    - Errors: error classes → connector error classes.
    - Signing headers present when a key is configured.
    - Confirm the pending-transactions parameter / route and the holder-info route per mode against
      the API reference, and pin them in code + fixtures.
    - _Requirements: 25.2, 9.2, 11.2, 12.1_
    - _Depends on: 5.3_

- [x] 6. Backend services (root repo)
  - [x] 6.1 `BankConnectionSecretService`
    - Write-only replace (encrypt via 2.7); `credentialsSet` / `updatedAt` read; audit without
      values.
    - `error.bank.secrets.notConfigured` when the key is missing and the connector needs credentials.
    - _Requirements: 2.2, 2.3, 2.4, 2.5_
    - _Depends on: 3.1, 2.7_
  - [x] 6.2 `BankConnectionService`, `BankAccountService` (+ holder match), `SupportedBanksService`
    - `BankConnectionService`:
      - create (PENDING_AUTH + authorization URL, `provider_mode`), continue authorization;
      - complete (validated via `fetchConnectionState`), reconnect, refresh (409 not-yet);
      - rename, disable, delete (409 has links);
      - status recompute with `warn-days`; audit.
    - `BankAccountService`:
      - bank account upsert (default `includedInLedger` by nature, `closedAtProvider`);
      - holder match via 2.8 + `CompanyProfile` (recompute on profile change; a new MISMATCH is
        excluded; an override requires a reason and is audited); toggle; gap acknowledge.
    - `SupportedBanksService`: cache per (provider, mode, country) for `banks-cache-ttl`, `refresh`
      bypass, `fetchedAt`, `emptyReason`.
    - _Requirements: 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 3.1, 3.2, 3.3, 3.4, 3.7, 3.8, 3.9, 3.10, 7.3, 9.2, 19.9, 24.5_
    - _Depends on: 6.1, 4.1, 3.1, 2.8_
  - [x] 6.3 `BankTransactionIngestService`
    - POSTED upsert by `(account, externalId)`, keeping links (`channel = BANK`, `origin =
      BANK_FEED`).
    - PENDING wholesale replacement per account; PENDING → POSTED promotion.
    - Provider duplicates skipped; counterparty key / IBAN; counters.
    - Tests for Property 1 (idempotence) and Property 2 (pending replacement).
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_
    - _Depends on: 3.1, 2.1_
  - [x] 6.4 `BankSyncOrchestrator`, `BankSyncExecutorConfig`, `BankSyncScheduler`, `BankConsentSweep`
    - Enqueue with the one-active-run index + a coalesced follow-up.
    - Async run steps per the design, incl. holder info + holder match on account upsert.
    - Per-account isolation (`PARTIAL`); connector error → connection status (R12.4).
    - Balance check (bank only) + gap event.
    - Detection on new POSTED `BANK_FEED` rows; counters + `to_review`; `BankSyncFinished` event.
    - Stale-run recovery at start-up and on each tick; scheduled pull (no background refresh).
    - Consent sweep: expiring once per consent, re-auth, purge stale PENDING_AUTH.
    - Unit tests with a fake connector.
    - _Requirements: 1.4, 1.9, 1.10, 3.7, 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 7.1, 7.2, 9.6, 12.4_
    - _Depends on: 6.2, 6.3, 2.2, 6.8, 6.10_
  - [x] 6.5 FOR-06-07 seam: `PaymentTransactionService.recordBankTransfer`
    - New `BankTransferInput` record, incl. channel (`BANK_TRANSFER` / `MANUAL`).
    - Idempotent by `(schedule, channel, externalRef)`; save CONFIRMED +
      `reconciliationService.reconcile`.
    - Extract the shared private method used by the correlated branch of `ingestBankTransaction`.
    - Unit test; existing FOR-06-07 tests stay green.
    - _Requirements: 16.6_
    - _Depends on: none_
  - [x] 6.6 FOR-07-06 seams: payout paid event, `markPaidFromLedger`, `ledgerEntries`
    - `markPaid` publishes `WorkerPayoutPaidEvent(payoutId, channel, paidAt, amount, currency,
      workerName, note, fromLedger=false)`.
    - New `markPaidFromLedger(id, paidAt, channel)` sets `paidAt` / `paymentChannel` and publishes
      the event with `fromLedger=true`.
    - Extend `WorkerPayoutDto` with `ledgerEntries[{transactionId, channel, bookingDate, accountName,
      amount}]` (read via the links DAO; empty until the ledger exists).
    - Unit tests; existing payroll tests stay green.
    - _Requirements: 16.1, 16.2, 16.3, 27.4_
    - _Depends on: none_
  - [x] 6.7 `BankLinkService`, `LinkEffects`, `CounterpartyRuleService`
    - Operations from the design table: apply auto, confirm suggestion / auto, reject, create,
      replace, remove, ignore / unignore.
    - Validations: kind / direction, targets, Σ ≤ |amount|, target over-link, payout amount
      equality, locked.
    - Effects per kind:
      - payout: existing → `markPaidFromLedger` with the line channel; create new payout from the
        line;
      - client payment via `recordBankTransfer` with the channel;
      - document: payment status via 2.9.
    - Status cache via 2.3; rule learning on BANK_FEED lines + CRUD; one transaction per operation;
      audit.
    - Tests: Property 3 (allocation bound), Property 9 (locked), effects per kind incl. cash lines.
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7, 16.1, 16.2, 16.3, 16.4, 16.6, 16.7, 16.8, 16.9, 16.10, 17.1, 17.3, 17.4, 17.5_
    - _Depends on: 3.1, 2.3, 2.9, 6.5, 6.6_
  - [x] 6.8 `DetectionService` + candidate providers + `ReverseDocumentMatcher`
    - Candidate providers:
      - own account;
      - counterparty rule;
      - client schedule (`FM-{id}` + CLIENT name / NIP + open tranche remainder);
      - payout (`BANK_TRANSFER` only);
      - procurement (`UNMATCHED` / `PARTIALLY_PAID`);
      - category / project.
    - Run `RuleMatcher`; auto-apply / suggestions via 6.7.
    - Async AI step through the `BankTransactionClassifier` interface (≤ max-per-run,
      `ai_classified_at`).
    - Reverse matching on `ProcurementDocumentParsedEvent` for `UNMATCHED` documents (skip
      `LINKED_FROM_LEDGER`).
    - `rerunDetection`; tests.
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 15.2, 15.5, 15.6, 17.6, 28.2, 28.3_
    - _Depends on: 2.4, 2.5, 6.7, 6.11_
  - [x] 6.9 `GeminiBankTransactionClassifier` + `DisabledBankTransactionClassifier` + `BankAiProperties`
    - `@Conditional` on `foremen.bank-ledger.ai.enabled`.
    - Lazy Gen AI client (Vertex or keyed API, as in `GeminiMessageTranslationService`).
    - Prompt from the design with a masked IBAN; tolerant parse + validator (2.5); failures → empty.
    - Unit test with a stubbed client.
    - _Requirements: 15.1, 15.2, 15.3, 15.6, 15.7, 24.3_
    - _Depends on: 2.5_
  - [x] 6.10 `BankNotificationEmitter` + events
    - Five types and deep-links (`/bank/...`, `/company/bank-transactions?status=TO_REVIEW`) from the
      design.
    - Recipients by company role (`UserDao.findByRoleCode`, active, de-duplicated); `AFTER_COMMIT`;
      failures logged.
    - syncFailed 24 h throttle; consentExpiring once per consent.
    - No events for cash entries or holder match.
    - Unit tests.
    - _Requirements: 22.1, 22.2, 22.5, 22.6_
    - _Depends on: 3.1_
  - [x] 6.11 Procurement seams (FOR-06-03/04)
    - Extend `PER_LINE_PARSE_PROMPT` and `parsePerLineResponse` with `supplierNip` (checksum via
      `NipValidator`, else null), `invoiceNumber`, `invoiceDate` (ISO, else null); persist them in the
      OCR worker.
    - Publish `ProcurementDocumentParsedEvent(documentId)` on the `PARSED` transition.
    - `ProcurementDocumentService.upload` accepts `paymentChoice` (+ `cashAccountId`, `cashAmount`,
      `cashDate`) and delegates `PAID_CASH` to the cash hook interface (implemented in 6.12;
      no-op default until then).
    - Add `markPaidInCash(documentId, …)`.
    - Document DTOs expose the three fields, `paymentStatus` (2.9) and `ledgerEntries`.
    - Parser + upload tests; existing FOR-06-03/04 tests stay green.
    - _Requirements: 18.1, 18.2, 18.3, 18.4, 16.4, 16.5, 28.1, 28.4, 28.5_
    - _Depends on: 3.1_
  - [x] 6.12 `CashLedgerService` + `PayoutCashHook` + `ProcurementCashHook`
    - Cash accounts CRUD (default per currency, archive, computed balance).
    - Manual entries: create with an optional link through 6.7; edit / delete for MANUAL without
      locked links; SYSTEM read-only.
    - `PayoutCashHook` (synchronous `@EventListener` on `WorkerPayoutPaidEvent`: CASH and not
      `fromLedger` → one SYSTEM entry + `WORKER_PAYOUT` link; auto-create the default cash account
      when missing).
    - `ProcurementCashHook` (`PAID_CASH` / mark paid in cash → SYSTEM entry + `PROCUREMENT` link;
      amount required while the total is unknown).
    - Tests for Property 11 (one entry per source; none for ledger-originated payments) and the
      edit / delete rules.
    - _Requirements: 27.1, 27.2, 27.3, 27.4, 27.5, 27.6, 27.7, 28.1_
    - _Depends on: 6.7, 6.6, 6.11_

- [x] 7. Controllers and configuration (root repo)
  - [x] 7.1 Bank administration controllers (+ stub controller)
    - Controllers, all `@PermissionResource("BANK_CONNECTIONS")` with per-handler operations from
      the REST table:
      - `BankProviderController` (providers, supported banks with filters / `refresh` / `fetchedAt` /
        `emptyReason`);
      - `BankConnectionController`;
      - `BankAccountController` (bank + cash accounts, holder override, balance gaps);
      - `BankSyncRunController`;
      - `CounterpartyRuleController`.
    - `StubBankFeedController` exists only with the stub enabled (UPDATE).
    - DTOs never expose secrets; `PermissionAnnotationValidator` passes.
    - _Requirements: 1.3–1.8, 2.3, 3.2, 3.8, 4.4, 4.5, 5.1, 7.3, 9.2, 17.4, 19.3–19.9, 21.1, 21.3, 21.5, 21.6, 27.1_
    - _Depends on: 6.2, 6.4, 6.7, 6.12, 4.2_
  - [x] 7.2 `BankTransactionController` (ledger)
    - `@PermissionResource("BANK_LEDGER")`.
    - Reads: filtered page + totals (incl. `channel`, `transactionId`); detail (payload ADMIN only);
      link-target search per kind; link candidates for a document.
    - Cash entries: create / edit / delete.
    - Links: create / replace / remove; suggestion confirm / reject; confirm auto; ignore / unignore;
      detect; bulk confirm.
    - Create payout (also checks `PAYROLL`/CREATE).
    - _Requirements: 13.*, 16.2, 17.1, 17.2, 17.5, 17.6, 20.2–20.7, 21.2, 21.4, 21.6, 27.3, 27.6, 28.1_
    - _Depends on: 6.7, 6.8, 6.12_
  - [x] 7.3 `SaltEdgeCallbackController` + `SecurityConfig`
    - Raw body ≤ 1 MB; signature verification against `PublicBaseUrl.callbackUrl(type)` before
      parsing (401 otherwise).
    - `200` + async handling of success / fail / notify / destroy per the design; an unknown
      connection → 200 no-op; idempotent.
    - `permitAll` matcher for `/api/bank-feed/saltedge/callbacks/**`.
    - _Requirements: 10.1–10.9, 21.5, 24.2_
    - _Depends on: 5.3, 6.4, 2.6_
  - [x] 7.4 Backend message bundles
    - Add every `error.bank.*` code (PL, RU, default) used by 6.x / 7.1–7.3 / 7.6.
    - _Requirements: 23.4, 12.3_
    - _Depends on: 7.1, 7.2, 7.3, 7.6_
  - [x] 7.5 Configuration (environment-specific, externalized)
    - `application.yml`: `foremen.public-base-url` (no default), `foremen.bank-feed.*` (incl.
      `saltedge.mode`, `lead-email`, `banks-cache-ttl`), `foremen.bank-ledger.*` (incl.
      `holder.name-similarity`), `foremen.bank-secrets.*`, all off by default.
    - `application-docker.yml`: STUB on, `FOREMEN_PUBLIC_BASE_URL=http://localhost:3000`.
    - `docker-compose.yml`: pass `FOREMEN_PUBLIC_BASE_URL`, `SALTEDGE_*` (incl. `SALTEDGE_MODE`,
      `SALTEDGE_LEAD_EMAIL`), `BANK_AI_*`, `FOREMEN_BANK_SECRETS_KEY`, `FOREMEN_BANK_STUB_ENABLED`
      from env.
    - `.env.example`: placeholders without values.
    - `.secrets/saltedge.env`: add `SALTEDGE_MODE` / `SALTEDGE_LEAD_EMAIL` /
      `FOREMEN_PUBLIC_BASE_URL` lines (local only).
    - _Requirements: 2.6, 4.3, 4.4, 8.1, 8.2, 15.1, 24.6_
    - _Depends on: 5.1, 6.9_
  - [x] 7.6 Procurement / payroll controller extensions
    - `ProcurementDocumentController`: upload accepts `paymentChoice` + cash fields; `POST
      /{id}/paid-in-cash` (PURCHASES UPDATE); DTOs with `paymentStatus` / `ledgerEntries`.
    - `WorkerPayoutController` / `PayrollController` DTOs expose `ledgerEntries`.
    - Existing ABAC unchanged; `PermissionAnnotationValidator` passes.
    - _Requirements: 16.3, 18.3, 28.1, 28.4_
    - _Depends on: 6.11, 6.12, 6.6_

- [x] 8. Backend integration tests (root repo)
  - [x] 8.1 Testcontainers integration + ABAC
    - STUB end-to-end:
      - connect → complete → sync → accounts with holder MATCH / MISMATCH (excluded) / UNKNOWN →
        override with a reason;
      - transactions → internal transfer AUTO;
      - `FM-{id}` client payment AUTO → `PaymentTransaction` + reconcile;
      - payout suggestion → confirm → PAID + `ledgerEntries`;
      - procurement link + `paymentStatus`;
      - override + rule learning, ignore;
      - re-sync idempotent, pending replacement, balance gap.
    - Cash:
      - manual entry with a link;
      - CASH payout in payroll → exactly one SYSTEM entry, none for a ledger-originated payment;
      - procurement `PAID_CASH`;
      - a `NOT_LINKED` document reverse-matched after the parsed event.
    - Supported banks endpoint with STUB (+ empty reason).
    - Callbacks: accepted with a test key, rejected with a wrong one.
    - ABAC matrix (ADMIN, FINANCIER, MANAGER, FOREMAN) for both resources; seeds idempotent.
    - Start-up: the app starts with Salt Edge disabled and with a blank public base URL (Salt Edge
      unavailable).
    - _Requirements: 25.3, 21.1, 21.2, 21.5, 3.7, 27.4, 28.2_
    - _Depends on: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 1.2_

- [x] 9. Frontend (foremen-frontend repo)
  - [x] 9.1 Locale keys (PL + RU)
    - Add every key from the design localization table to `pl.json` and `ru.json`.
    - Locale-parity test for `nav.bank*`, `bank.*`, `bankTransactions.*`, `procurement.payment*`,
      `procurement.paymentStatus.*`, `payroll.payout.ledgerLink`, `notification.bank.*`,
      `error.bank.*`.
    - _Requirements: 23.2, 23.3, 22.7_
    - _Depends on: none_
  - [x] 9.2 Bank API client, hooks, types, `lib/iban.ts`
    - `features/bank/api/*` for:
      - providers and supported banks (filters, refresh);
      - connections (create / authorize / complete / reconnect / refresh / sync / rename /
        credentials / disable / delete);
      - accounts (holder override), cash accounts, gaps;
      - sync runs, counterparty rules.
    - React Query hooks; IBAN format / last-4 helpers + tests.
    - _Requirements: 19.3–19.9, 23.5, 27.1_
    - _Depends on: 7.1_
  - [x] 9.3 Bank page with tabs (administration section)
    - `BankPage` (URL-synced tabs under `/bank`).
    - `ConnectionsTab` (table, actions, consent banner); `AddBankDialog` + `BankPicker` (live list,
      business / personal, "requires own keys" disabled, preselected bank); `CredentialsDialog`
      (dynamic, write-only); `ConnectionReturnPage`.
    - `AccountsTab`: holder badge + `HolderOverrideDialog`, cash accounts group +
      `CashAccountDialog`, acknowledge gap.
    - `SyncRunsTab` (+ detail); `CounterpartyRulesTab` (+ edit dialog).
    - `SupportedBanksTab`: capabilities columns, filters, Connect, Refresh list, `fetchedAt`, empty /
      error states.
    - Shared data-table set; enum labels.
    - _Requirements: 19.2, 19.3, 19.4, 19.5, 19.6, 19.7, 19.8, 19.9, 1.6, 2.2, 3.8, 3.9, 27.1_
    - _Depends on: 9.1, 9.2_
  - [x] 9.4 Ledger API + `BankTransactionsPage` (company section)
    - API / hooks / types.
    - `LedgerFilterBar` + `usePersistedLedgerFilters` (localStorage, presets, channel);
      `LedgerTotalsBar`.
    - `LedgerTable` columns: channel / origin, direction, bank or cash account, counterparty (payer /
      payee), title, amount, `DetectionMarker` (none for manual / system), `LinkedEntityCell`,
      `LinkStatusBadge`, pending badge.
    - Row selection + bulk actions; "Add cash entry" toolbar action; `transactionId` deep link opens
      the drawer; mobile cards.
    - _Requirements: 20.1, 20.2, 20.3, 20.4, 20.6, 20.7, 20.8, 23.5_
    - _Depends on: 7.2, 9.1_
  - [x] 9.5 `TransactionDrawer`, `CashEntryDialog` and actions
    - Drawer contents: fields; payload (ADMIN); links (source / confidence / confirmed, lock);
      suggestions confirm / reject.
    - `LinkEditor`: kind → target picker (existing payouts first + "create payout"; documents with
      payment status + "upload invoice") → amount; split, change, remove with the payout warning.
    - `UploadInvoiceDialog`: project picker, `paymentChoice=LINKED_FROM_LEDGER`, status polling +
      reprocess, then link.
    - `CreatePayoutFromTxnDialog`, `IgnoreDialog`, re-run detection, "remember" checkbox.
    - `CashEntryDialog`: method Cash, cash account, direction, amount, date, counterparty, title,
      optional link; edit / delete for MANUAL; SYSTEM read-only notice.
    - Permission-based hiding.
    - _Requirements: 16.1, 16.2, 16.5, 16.8, 17.1, 17.2, 17.3, 17.5, 17.6, 20.5, 20.8, 20.9, 27.3, 27.6_
    - _Depends on: 9.4_
  - [x] 9.8 Procurement + payroll UI extensions
    - FOR-06-03/04 upload dialog: `PaymentChoiceField` (Not linked yet default / Paid in cash with
      amount + cash account / Find bank transaction → `FindBankTransactionDialog`).
    - Document list / detail: `PaymentStatusBadge`, `LedgerLinksCell`, actions "Mark paid in cash" /
      "Find bank transaction" on `UNMATCHED`.
    - FOR-07-06 payroll card / payout rows: `PayoutLedgerLinks` → `/company/bank-transactions?
      transactionId=`.
    - _Requirements: 16.3, 28.1, 28.4, 28.5_
    - _Depends on: 7.6, 9.1_
  - [x] 9.6 Navigation, router, route-permissions
    - `NAV_CONFIG` items:
      - **Bank** in `nav.sections.system` (path `/bank`, `BANK_CONNECTIONS`/READ, icon `landmark`);
      - **Bank transactions** in `nav.sections.company` (path `/company/bank-transactions`,
        `BANK_LEDGER`/READ, icon `arrow-left-right`).
    - Routes: `/bank`, `/bank/:tab`, `/bank/connections/return` (extra requirement
      `BANK_CONNECTIONS`/UPDATE), `/company/bank-transactions`.
    - _Requirements: 19.1, 19.2, 20.1, 21.1, 21.2_
    - _Depends on: 9.3, 9.5, 9.8_
  - [x] 9.7 Frontend tests
    - Nav visibility per role and section.
    - Bank page: tabs routing incl. supported banks (list, filters, empty / error), AddBank + return
      flow (mocked API), holder badges + override, cash accounts.
    - Ledger: filters persistence incl. channel; cash entry dialog; drawer actions (confirm, link,
      override, remove, ignore, locked, cash edit / delete); detection marker hidden when AI is
      disabled.
    - Procurement payment choice + status badge; payroll ledger links.
    - Locale parity.
    - _Requirements: 25.4_
    - _Depends on: 9.6_

- [x] 10. Author / re-sync test-cases.md
  - Update `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard:
    feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown,
    regression group, MD report template; authored in Russian.
  - This spec has **both** surfaces:
    - API scenarios against the Dockerized app (`http://localhost:8080`, STUB connector);
    - browser-engine scenarios (`http://localhost:3000`) for the Bank menu (administration section,
      `/bank/...`, incl. holder verification, cash accounts and supported banks) and the Bank
      transactions menu (company section, incl. cash entries);
    - the procurement payment choice and payroll ledger links;
    - a manual Salt Edge scenario per environment (base URL from env, CLIENT / PARTNER mode).
  - Result artifacts are MD reports with tables. Re-check against the final implementation.
  - _Requirements: 25.5_
  - _Depends on: 8.1, 9.7_

- [x] 11. Environment rollout + Salt Edge dashboard
  - Per environment (dev first), set on the VM / Secret Manager (never committed): `SALTEDGE_ENABLED=
    true`, `SALTEDGE_MODE` (CLIENT now → PARTNER once granted), `SALTEDGE_APP_ID`, `SALTEDGE_SECRET`,
    `SALTEDGE_LEAD_EMAIL` (PARTNER), `FOREMEN_PUBLIC_BASE_URL` (dev = `https://dev.foremen.eu`),
    `FOREMEN_BANK_SECRETS_KEY`. Deploy.
  - In the Salt Edge dashboard of that environment:
    - fill Application info and Company info;
    - register the four callback URLs `{FOREMEN_PUBLIC_BASE_URL}/api/bank-feed/saltedge/callbacks/
      {success,fail,notify,destroy}`;
    - free fake-connection slots (delete old fake connections);
    - connect `fakebank_simple_xf` through the UI and verify callback → sync → ledger rows → holder
      badge;
    - check the Supported banks tab (fake banks only / empty-state message in Pending);
    - "Request Test Access" and request **Partner mode**.
  - Optional: generate an RSA key pair, upload the public key, set `SALTEDGE_PRIVATE_KEY_PATH`, and
    verify signed requests.
  - _Requirements: 8.2, 10.1, 12.5, 12.6, 19.9_
  - _Depends on: 8.1, 9.7, 10_

- [x] 12. Final checkpoint
  - Affected backend test classes green (JUnit XML); frontend vitest green; locale parity green.
  - `test-cases.md` run report attached.
  - Both repos committed (root + `foremen-frontend/`) on a feature branch when the user asks.
  - _Requirements: all_
  - _Depends on: 11_
