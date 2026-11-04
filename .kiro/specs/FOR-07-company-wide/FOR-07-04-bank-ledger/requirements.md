# Requirements Document

## Introduction

This spec (**FOR-07-04-bank-ledger**) is a child of the FOR-07 "company-wide / cross-project"
parent. It delivers the company **money ledger**: the full flow of **collecting bank transactions**
from the company's bank accounts (several banks, several accounts), recording **non-bank money
flows** (cash) in the same ledger, and **linking** every ledger line to the business entity it pays
for or comes from — automatically (rules + AI) with a user override.

The spec delivers, in one place:

1. **Bank connections administration** — connect a bank through a provider, reconnect when consent
   expires, refresh, disable; choose which accounts feed the ledger; per-connection encrypted
   credential storage for providers that need it.
2. **A provider-agnostic collector** — a `Bank_Feed_Connector` SPI, sync orchestration (provider
   callback, schedule, manual), idempotent ingest, pending → posted handling, balance-continuity
   checks, sync history.
3. **The Salt Edge connector** — the first concrete implementation of the SPI, specified in detail
   for Salt Edge API v6 in **both access modes**, selected by configuration:
   - **Partner mode** (`/api/partners/v1`) — the **production target**: Foremen has no own PSD2/AISP
     licence, so banks such as PKO BP and Erste Bank Polska are reached under Salt Edge's licence
     without own bank developer keys;
   - **Client mode** (`/api/v6`) — used now, because the current Salt Edge account and keys are a
     Client account in Pending status (fake/sandbox banks), and kept for a future own licence.

   Covered for both: authentication and request signing, customer / lead and connect / reconnect /
   refresh sessions, signed callbacks on the environment's public base URL, account and transaction
   fetch, field mapping, limits, error mapping, fake/sandbox testing, dashboard setup and the
   Pending → Test → Live path.
4. **Ledger linking** — a link model covering every money flow the system already records:
   - **worker payouts** (FOR-06-11 / FOR-07-06): link an existing payout or create a new one from the
     ledger line; the payout shows a link back to its ledger line;
   - **procurement documents** (FOR-06-03/04): link an existing document, or upload a not-yet-added
     invoice scan with the full OCR flow straight from the ledger line;
   - **client payments** (FOR-06-07);
   - **other expenses / incomes**, with or without a project;
   - internal transfers and ignored rows.
5. **Non-bank money flows (cash)** — cash accounts and manually created cash entries. Two flows
   create a cash entry automatically, already linked: a payroll payout paid in cash, and a
   procurement document marked "paid in cash". Bank-transfer payouts and documents wait for the
   matching bank line from the next import.
6. **Auto-detection** — a deterministic rule matcher plus an AI classifier (Gemini, reusing the
   existing Google Gen AI infrastructure) that proposes links with a confidence; a counterparty
   memory that learns from user confirmations; every automatic decision is overridable. Each ledger
   line shows an auto-detection marker. Manual and system cash entries carry no marker: they are
   created already linked.
7. **Two main-menu entries:**
   - **Bank**, in the **administration** group (`nav.sections.system`), ADMIN + FINANCIER only. Tabs:
     connections, accounts (bank and cash, with account-holder verification against the company
     profile), sync history, counterparty rules, and **supported banks** (the live list of Polish
     banks read from the Salt Edge API).
   - **Bank transactions**, in the **company-wide** group (`nav.sections.company`), ADMIN +
     FINANCIER + MANAGER. The ledger list of bank and cash lines with channel, direction,
     bank/account, payer/payee, auto-detection marker, linked entity, status, and override actions.
8. **Notifications** emitted through the generic `Notification_Service`, ABAC resources, PL + RU
   localization, tests and `test-cases.md`.

### Relationship to existing specs (source of truth)

- **FOR-06-07-client-payments (implemented)** — owns `PaymentSchedule` / `PaymentTranche` /
  `PaymentTransaction` / `TrancheAllocation`, `ReconciliationService`, the `BankFeedProvider` port
  with `NoOpBankFeedProvider`, and `PaymentTransactionService.ingestBankTransaction`
  (`correlationKey` = project id). This spec **records** confirmed client-payment links as
  `BANK_TRANSFER` `PaymentTransaction` rows through a new explicit-schedule entry point and reuses the
  reconciliation cascade unchanged. The FOR-06-07 `BankFeedProvider` port stays as is (No-Op).
- **FOR-06-11-payroll / FOR-07-06-company-payroll (implemented)** — own `WorkerPayout`
  (worker-wide, `PLANNED → PAID`, `paymentChannel` `BANK_TRANSFER | CASH`) and `WorkerPayoutService`
  (create with distribution, mark paid). This spec:
  - links a ledger line to an **existing** payout, or **creates a new** payout from the ledger line;
  - marks a `PLANNED` payout paid when its bank line is linked;
  - exposes the linked ledger line(s) on the payout read model and the payroll card;
  - creates a **cash ledger entry** automatically when a payout is paid with channel `CASH`.

  Payroll calculation logic is not changed.
- **FOR-06-03-procurement-ocr / FOR-06-04-purchases-list (implemented)** — own
  `ProcurementDocument` / `ProcurementLine`, the upload → async OCR pipeline, `ImageStorage`, and the
  `PURCHASES` resource. This spec links ledger lines to documents and **extends the OCR parse** with
  three nullable document fields used for matching: supplier NIP, invoice number, invoice date.
  Upload has two entry points:
  - **from a ledger line** — the line already exists, so the uploaded document becomes its linked
    entity immediately;
  - **from the procurement screens** — the upload shows a single **"Paid in cash"** checkbox:
    - checked: once OCR has parsed the document, a linked cash entry is created with the OCR amount
      and date on the default cash account (nothing is typed);
    - unchecked (default): the document stays unmatched until the matcher links it during a later
      bank import; a bank transaction can still be linked from the document row (Requirement 28).
- **FOR-07-03-company-procurement (not yet implemented)** — will make
  `procurement_documents.project_id` nullable. Until it lands, uploading an invoice requires
  choosing a project, and a no-project purchase is recorded as an **other expense**. This spec
  defines the seam and does not change the NOT NULL constraint.
- **FOR-06-00 / `UnplannedExpenseCategory` dictionary (implemented)** — reused as the category list
  for other expenses (it already has `nameRU` / `namePL`). Not changed.
- **FOR-05-07 `Notification_Service` + Bell (implemented)** — reused, emit only.
- **FOR-07-00-company-foundation** — owns the `nav.sections.company` menu section, which hosts the
  Bank transactions item. The Bank administration item goes into the existing `nav.sections.system`
  (administration) section.
- **FOR-12 infrastructure** — owns production deployment, Secret Manager wiring and the production
  domain. This spec reads the public base URL and all secrets from **environment-specific
  configuration** (`FOREMEN_PUBLIC_BASE_URL`, `SALTEDGE_*`); nothing environment-specific is
  hard-coded. The dev value (`https://dev.foremen.eu`) is an example of one environment's setting.

### Scope boundaries

- **In scope:** everything listed in items 1–8 above. Liquibase changesets for the new tables (incl.
  cash accounts and manual / system entries), the procurement OCR field extension, the two ABAC seeds,
  and the payroll / procurement hooks that create cash entries. Backend + frontend + tests +
  `test-cases.md`.
- **Out of scope (Non-Goals):** see Requirement 26.

## Glossary

- **Bank_Connection** — one authorized access to one bank login through one provider (e.g. the
  company's PKO BP iPKO biznes login via Salt Edge). Has a status, a consent expiry, and one or more
  accounts.
- **Bank_Account** — one money account in the ledger, with `type`:
  - `BANK` — discovered under a connection (IBAN, currency, balance); only bank accounts with
    `includedInLedger = true` feed the ledger;
  - `CASH` — a company cash register created manually, with no connection.
- **Cash_Account** — a `Bank_Account` with `type = CASH`. One may be flagged `defaultForCash`; it is
  the target of automatically created cash entries.
- **Bank_Transaction (ledger line)** — one normalized money operation on a `Bank_Account`: signed
  amount, currency, operation date, booking date, title, counterparty name/IBAN, status
  `PENDING | POSTED`, `channel` (`BANK | CASH`) and `origin`:
  - `BANK_FEED` — collected by a connector;
  - `MANUAL` — entered by a user, cash only;
  - `SYSTEM` — created by a payroll / procurement hook, cash only.
- **Direction** — `INCOME` (amount > 0) or `EXPENSE` (amount < 0); derived from the sign, never
  stored independently.
- **Bank_Feed_Connector** — the provider-agnostic SPI a provider implementation fulfils (connect,
  complete, reconnect, refresh, fetch accounts, fetch transactions, disable, callback handling).
- **Feed_Provider** — the enum of connector implementations: `SALT_EDGE`, `STUB` (dev/test only).
  Further providers (e.g. statement file import) are future work.
- **Salt_Edge_Mode** — `PARTNER` (Partners API v1, Salt Edge's licence, production target) or
  `CLIENT` (API v6 with own keys, current Pending account); one mode per environment.
- **Public_Base_URL** — the externally reachable HTTPS base URL of the running environment
  (`FOREMEN_PUBLIC_BASE_URL`); return URLs and callback URLs are derived from it.
- **Sync_Run** — one execution of fetching accounts + transactions for a connection, with trigger
  (`CALLBACK | SCHEDULE | MANUAL | CONNECT`), status, counters and error.
- **External_Id** — the provider's stable identifier of an account or transaction; the dedupe key
  together with the owning account.
- **Balance_Gap** — a detected inconsistency between a provider-reported account balance and the
  previous balance plus the newly posted transactions (bank accounts only).
- **Link** — an applied association between a `Bank_Transaction` (or a part of its amount) and a
  target: `WORKER_PAYOUT`, `PROCUREMENT`, `CLIENT_PAYMENT`, `OTHER_EXPENSE`, `OTHER_INCOME`,
  `INTERNAL_TRANSFER`.
- **Suggestion** — a proposed, not yet applied link produced by the rule matcher or the AI
  classifier, with `source` and `confidence`.
- **Link_Status** — the ledger status of a transaction: `UNLINKED`, `SUGGESTED`, `AUTO_LINKED`,
  `PARTIALLY_LINKED`, `LINKED`, `IGNORED`. `To_Review` = `UNLINKED ∪ SUGGESTED ∪ PARTIALLY_LINKED ∪
  AUTO_LINKED-not-yet-reviewed`.
- **Detection_Source** — `RULE`, `AI`, `MANUAL`. A transaction is **auto-detected** when its current
  links or suggestions come from `RULE` or `AI`.
- **Holder_Match** — the result of comparing a bank account's holder (as reported by the provider)
  with the company profile: `MATCH`, `MISMATCH`, `UNKNOWN` (no holder data or no company profile).
- **Supported_Bank** — a bank (provider) offered by the active connector for a country, read live
  from the provider API (Salt Edge `GET /providers`), with its capabilities.
- **Document_Payment_Status** — the payment state of a procurement document derived from its links:
  `UNMATCHED` (no link), `PARTIALLY_PAID`, `PAID`.
- **Counterparty_Rule** — a learned or hand-made mapping from a counterparty key (normalized IBAN,
  or normalized name when no IBAN) to a default link target (worker, supplier name, project's client
  schedule, other-expense category + optional project, internal transfer).
- **Own_Account** — any `BANK`-type `Bank_Account` of the company (used to detect internal transfers).
- **Project_Reference** — the token `FM-{projectId}` that clients are asked to put in the transfer
  title; it reuses the FOR-06-07 `correlationKey = projectId` semantics.
- **Notification_Service / Notification_Type / Deep_Link** — per FOR-05-07: the generic in-app
  notification service, the i18n type key, and the optional UI target.
- **Enum_Label_I18n** — the frontend convention that every user-facing enum is rendered through an
  i18n key with PL + RU parity.

## Requirements

### Requirement 1: Bank connection lifecycle

**User Story:** As an ADMIN or FINANCIER, I want to connect each company bank login once and keep it
working, so that transactions of all company accounts flow in without manual work.

#### Acceptance Criteria

1. THE System SHALL persist a `Bank_Connection` with: `provider` (`Feed_Provider`), provider-side
   identifiers (`externalCustomerId`, `externalConnectionId`, `externalConsentId`, all nullable until
   known), `providerCode` and `bankName` (as reported by the provider), `displayName` (user editable),
   `status`, `consentExpiresAt`, `lastSyncAt`, `lastSuccessfulSyncAt`, `nextRefreshPossibleAt`,
   `lastErrorCode`, `lastErrorMessage`, audit columns.
2. THE `Bank_Connection.status` SHALL be one of:
   - `PENDING_AUTH` — created, authorization not finished;
   - `ACTIVE`;
   - `CONSENT_EXPIRING` — consent expires within the configured warning window, default 14 days;
   - `REAUTH_REQUIRED` — consent expired or revoked, or the provider reports the connection
     inactive;
   - `ERROR` — the last sync failed for a non-consent reason;
   - `DISABLED` — switched off by a user or destroyed at the provider.
3. WHEN a user starts "Add bank" with a chosen provider (and optionally a provider bank code), THE
   System SHALL create a `PENDING_AUTH` connection, ask the connector for an authorization URL and
   return it to the frontend, which redirects the user to it.
4. WHEN the provider reports successful authorization (callback or return-URL completion), THE System
   SHALL store the provider identifiers, set the status to `ACTIVE`, and start a `Sync_Run` with
   trigger `CONNECT`.
5. WHEN a user chooses "Reconnect" on a connection, THE System SHALL obtain a reconnect URL from the
   connector for the same provider connection (renewing consent). On success THE System SHALL return
   the connection to `ACTIVE` without creating a new `Bank_Connection` or duplicating accounts.
6. WHEN a user chooses "Refresh now", THE System SHALL ask the connector to refresh the provider-side
   data. IF the provider does not allow a refresh yet (`nextRefreshPossibleAt` in the future), THEN
   THE System SHALL reject with `409 error.bank.refresh.notYetPossible` and show the earliest allowed
   time.
7. WHEN a user disables a connection, THE System SHALL ask the connector to revoke the provider-side
   access, set the status to `DISABLED`, stop all syncs for it, and keep its accounts and transactions
   (read-only history).
8. THE System SHALL allow deleting a `DISABLED` connection only when none of its transactions has a
   link; otherwise THE System SHALL reject with `409 error.bank.connection.hasLinks`.
9. THE System SHALL recompute `CONSENT_EXPIRING` / `REAUTH_REQUIRED` from `consentExpiresAt` on every
   sync and on a daily consent sweep.
10. A user-abandoned authorization SHALL leave the connection in `PENDING_AUTH`. THE System SHALL let
    the user retry ("Continue authorization") or delete it. The daily sweep SHALL remove
    `PENDING_AUTH` connections older than 24 hours.

### Requirement 2: Per-connection credential storage

**User Story:** As an ADMIN, I want provider credentials that belong to a single connection to be
stored securely and never shown again, so that a leaked database or UI does not leak bank access.

#### Acceptance Criteria

1. THE System SHALL provide an encrypted per-connection secret store (`bank_connection_secrets`)
   holding a ciphertext blob, IV and key version. Encryption SHALL be AES-256-GCM with a key read
   from configuration (`FOREMEN_BANK_SECRETS_KEY`, base64, 32 bytes), never from the database.
2. A connector SHALL declare which credential fields it needs (`credentialFields()` descriptor: name,
   label key, secret flag); the frontend SHALL render that form dynamically.
3. THE API SHALL accept credential values on create / replace only and SHALL NEVER return them; read
   responses SHALL expose only `credentialsSet: boolean` and `credentialsUpdatedAt`.
4. Every credential write SHALL be audited (who, when, connection) without the secret values.
5. IF the encryption key is not configured, THEN connectors that require credentials SHALL be
   reported as unavailable, and creating such a connection SHALL fail with
   `409 error.bank.secrets.notConfigured`. Connectors that need no per-connection credentials (Salt
   Edge) SHALL keep working.
6. Provider-level (application-wide) secrets SHALL come from environment-specific configuration
   (Secret Manager in production) and SHALL NEVER be stored in the database or returned by any API.
   These are the Salt Edge `App-id`, `Secret` and RSA private key.

### Requirement 3: Bank accounts

**User Story:** As an ADMIN or FINANCIER, I want to see every account of each connection and choose
which ones feed the ledger, so that private or irrelevant accounts stay out.

#### Acceptance Criteria

1. THE System SHALL upsert `BANK`-type `Bank_Account` rows per connection from the connector, keyed by
   `(connection, externalId)`, with: `name`, `iban` (nullable), `accountNumber` (nullable), `currency`,
   `nature` (e.g. account, card, savings — as reported), `balance`, `availableBalance`,
   `balanceUpdatedAt`, `includedInLedger`, `holderName` (nullable).
2. A newly discovered account SHALL default to `includedInLedger = true` when its nature is a current
   / checking account and `false` otherwise; users SHALL be able to toggle it.
3. Transactions SHALL be ingested only for accounts with `includedInLedger = true`; toggling an
   account off SHALL keep its existing transactions.
4. An account no longer returned by the provider SHALL be marked `closedAtProvider = true` and SHALL
   keep its history.
5. THE System SHALL treat every bank account's IBAN (normalized: uppercase, no spaces) as an
   `Own_Account` key for internal-transfer detection (Requirement 14.2).
6. Cash accounts are specified in Requirement 27.
7. **Account holder verification.** Bank authorization (login + SCA + consent at the bank) proves
   that the user has access to an account, not that the account belongs to the company. Therefore,
   after every account upsert THE System SHALL compute `holderMatch` for each bank account:
   - inputs: the provider-reported holder (account `holderName`, and the connection's holder info
     when fetched — names and, when present, tax id) versus the company profile (`CompanyProfile`
     legal name and `nip`);
   - `MATCH` when the NIP is equal, or when the normalized names match (lowercase, diacritics
     stripped, legal-form suffixes such as `sp. z o.o.`, `s.a.`, `sp.j.` ignored, token similarity
     ≥ 0.8);
   - `MISMATCH` when holder data is present and neither the NIP nor the name matches;
   - `UNKNOWN` when the provider returns no holder data or the company profile is missing.
8. A `MISMATCH` account SHALL default to `includedInLedger = false` regardless of its nature and
   SHALL show a warning badge "Account holder does not match the company". Including it SHALL require
   an explicit confirmation with a reason, recorded in the audit trail (`holderOverrideBy`,
   `holderOverrideAt`, `holderOverrideReason`).
9. An `UNKNOWN` account SHALL keep the nature-based default (criterion 2) and SHALL show an
   informational badge "Account holder not verified".
10. `holderMatch` SHALL be recomputed when holder data or the company profile changes; a change to
    `MISMATCH` SHALL NOT exclude an already included account automatically, but SHALL show the
    warning until a user confirms or excludes it.

### Requirement 4: Bank_Feed_Connector SPI

**User Story:** As a developer, I want one provider-agnostic contract, so that Salt Edge today and
other providers later plug in without changing the ledger.

#### Acceptance Criteria

1. THE System SHALL define a `Bank_Feed_Connector` interface with these methods:
   - `provider()`, `isAvailable()`, `credentialFields()`;
   - `listBanks(country)` — optional provider bank catalogue for the picker;
   - `startAuthorization(connection, providerBankCode, returnUrl)` → authorization URL;
   - `completeAuthorization(connection, returnParams)`;
   - `startReconnect(connection, returnUrl)`;
   - `requestRefresh(connection)` → refresh result incl. `nextRefreshPossibleAt`;
   - `fetchConnectionState(connection)` → status + consent expiry;
   - `fetchAccounts(connection)`;
   - `fetchTransactions(connection, account, sinceCursor)` → page of normalized transactions + next
     cursor;
   - `disable(connection)`.
2. Connectors SHALL return **normalized** DTOs (`NormalizedAccount`, `NormalizedTransaction`) and
   SHALL NOT write ledger tables directly; all persistence belongs to the ledger services.
3. Connectors SHALL be Spring beans selected per connection by `provider`. A connector whose
   configuration is missing SHALL report `isAvailable() = false` and SHALL NOT break application
   start-up (No-Op pattern consistent with FOR-05-08 / FOR-06-07).
4. THE System SHALL provide a `STUB` connector, enabled only when `foremen.bank-feed.stub.enabled =
   true` (default false; never in production). It SHALL authorize instantly and serve deterministic
   accounts / transactions that tests seed through a stub-only endpoint, so API tests against the
   Docker stack are repeatable without a real bank.
5. THE frontend "Add bank" dialog SHALL list only providers whose connector reports
   `isAvailable() = true`.

### Requirement 5: Sync orchestration

**User Story:** As a FINANCIER, I want new transactions to appear automatically and reliably, so that
the ledger is always current.

#### Acceptance Criteria

1. THE System SHALL run a `Sync_Run` for a connection on each of these triggers:
   - `CALLBACK` — a provider callback signalling new data;
   - `SCHEDULE` — the scheduled fallback pull (cron `foremen.bank-feed.sync.cron`, default daily
     06:30 Europe/Warsaw);
   - `MANUAL` — a user "Sync now";
   - `CONNECT` — right after authorization.
2. A `Sync_Run` SHALL perform these steps in order:
   - fetch the connection state and upsert accounts;
   - for every included account, fetch transactions since the stored cursor and ingest them
     (Requirement 6);
   - run the balance check (Requirement 7);
   - run auto-detection on new posted transactions (Requirements 14–15);
   - record counters (`accountsSeen`, `transactionsNew`, `transactionsUpdated`, `pendingReplaced`,
     `toReview`) and the final status `SUCCEEDED | PARTIAL | FAILED` with error code / message.
3. THE System SHALL run at most one `Sync_Run` per connection at a time; a trigger arriving while a
   run is active SHALL be coalesced into one follow-up run.
4. Sync work SHALL run asynchronously (dedicated bounded executor) and SHALL NOT block the callback
   HTTP response or the user request; "Sync now" SHALL return `202` with the run id.
5. A failure on one account SHALL NOT abort the other accounts (status `PARTIAL`). A failure fetching
   the connection state SHALL fail the run and set the connection status per Requirement 12.4.
6. THE System SHALL recover runs left `RUNNING` for longer than `foremen.bank-feed.sync.stale-after`
   (default 30 min) by marking them `FAILED` (`error.bank.sync.stale`) on start-up and on each
   scheduled tick.
7. Sync history SHALL be retained for at least 90 days and shown in the Bank menu "Sync history"
   tab.

### Requirement 6: Transaction ingest and normalization

**User Story:** As a FINANCIER, I want each bank operation to appear exactly once with clean fields,
so that totals and links are correct.

#### Acceptance Criteria

1. THE System SHALL persist each `Bank_Transaction` with these fields:
   - `account`, `externalId` (nullable for `MANUAL` / `SYSTEM` entries), `origin`, `channel`;
   - `status` (`PENDING | POSTED`), `amount` (signed, scale 2), `currency`;
   - `operationDate`, `bookingDate` (nullable for pending);
   - `title` (provider description, trimmed), `counterpartyName`, `counterpartyIban` (normalized),
     `counterpartyRaw` (provider JSON fragment for audit);
   - `category` (provider category code, nullable), `providerPayload` (raw JSON, for audit and
     re-mapping);
   - `linkStatus`, `ingestedAt`, `lastSeenAt`.
2. Ingest SHALL be idempotent: a `POSTED` feed transaction is unique by `(account, externalId)`.
   Re-delivery SHALL update only mutable provider fields and `lastSeenAt` and SHALL NOT reset links or
   suggestions.
3. Pending transactions SHALL be stored as `PENDING` and SHALL be replaced wholesale per account on
   each successful fetch (pending rows not returned again are deleted). They SHALL NOT be auto-linked
   and SHALL be shown greyed with a "pending" badge.
4. THE System SHALL skip provider rows flagged as duplicates by the provider.
5. Counterparty extraction SHALL take the payer for `INCOME` and the payee for `EXPENSE` from the
   connector's normalized fields. IBANs SHALL be normalized: uppercase, no spaces, Polish 26-digit
   NRB numbers prefixed with `PL`.
6. Amounts SHALL be stored in the account currency; no currency conversion is performed in this spec.
7. Sync SHALL never delete `BANK_FEED` transactions once `POSTED`, and a user cannot delete them; the
   only user action is the `IGNORED` status (Requirement 17.5). Deletion of `MANUAL` / `SYSTEM` cash
   entries is governed by Requirement 27.

### Requirement 7: Balance continuity check

**User Story:** As a FINANCIER, I want to know when the feed missed operations, so that I can fix the
gap before closing the month.

#### Acceptance Criteria

1. WHEN a `Sync_Run` obtains a provider balance for a bank account that also had a balance from the
   previous successful run, THE System SHALL compare `newBalance` with `previousBalance + Σ amount of
   POSTED transactions booked after the previous balance timestamp`.
2. IF the difference exceeds 0.01 in the account currency, THEN THE System SHALL record a
   `Balance_Gap` (account, period, expected, actual, difference), mark the account
   `hasBalanceGap = true`, and emit the balance-gap notification (Requirement 22, row 4).
3. A user SHALL be able to acknowledge a gap (with a note); acknowledging clears `hasBalanceGap` and
   is audited.
4. Accounts without a provider balance, including cash accounts, SHALL skip the check silently.

### Requirement 8: Salt Edge connector — configuration, authentication and signing

**User Story:** As a developer, I want the Salt Edge connector configured only through
environment-specific properties and secured exactly as Salt Edge requires, so that the same code runs
in every environment (local, dev, prod) and in Pending, Test and Live.

#### Acceptance Criteria

1. THE Salt Edge connector SHALL be configured by `foremen.bank-feed.saltedge.*`, every value
   overridable per environment through environment variables:

   | Property | Default / requirement |
   |---|---|
   | `enabled` | default false |
   | `mode` | `PARTNER` or `CLIENT`; env `SALTEDGE_MODE`, default `CLIENT` until the Partner account is granted |
   | `base-url` | default per mode: `https://www.saltedge.com/api/partners/v1` for PARTNER, `https://www.saltedge.com/api/v6` for CLIENT |
   | `app-id`, `secret` | required when enabled |
   | `private-key-path` | optional PEM |
   | `callback-public-key` | Salt Edge callback verification key (PEM); default = the documented key |
   | `customer-identifier` | default `foremen-company`; CLIENT mode |
   | `lead-email` | the company finance mailbox used as the Partner lead; PARTNER mode |
   | `fetch-days` | default 89 |
   | `include-fake-providers` | default true outside production |
   | `country-codes` | default `PL` |
   | `http-timeout` | default 60s |

2. THE environment's `Public_Base_URL` SHALL be read from `foremen.public-base-url`
   (`FOREMEN_PUBLIC_BASE_URL`). It has **no hard-coded default** and SHALL be set per environment
   (e.g. dev = `https://dev.foremen.eu`, production per FOR-12). WHEN Salt Edge is enabled and the
   value is missing or not HTTPS (HTTP is allowed only in Pending/Test), THE connector SHALL report
   `isAvailable() = false` and log a configuration error. All return URLs and callback URLs SHALL be
   derived from it.
3. THE connector SHALL report `isAvailable() = true` only when `enabled = true`, `app-id`,
   `secret` and `Public_Base_URL` are set, and the mode-specific identity (`customer-identifier` or
   `lead-email`) is configured.
4. Every request SHALL carry `Accept`/`Content-type: application/json`, `App-id` and `Secret`
   headers.
5. WHEN `private-key-path` is configured, THE connector SHALL sign every request with two headers:
   - `Expires-at` — UNIX seconds, now + 60 s, never more than 1 hour;
   - `Signature` — base64(RSA-SHA256(`Expires-at|METHOD|full_url|body`)) using the private key,
     where `body` is the exact request body string (empty for GET).

   Signing SHALL be mandatory before switching the Salt Edge account to Live (both modes).
6. Secrets and the private key SHALL never be logged; request logging SHALL redact `App-id`,
   `Secret`, `Signature`.
7. THE connector SHALL keep exactly one Salt Edge identity for the company:
   - in CLIENT mode, one customer identified by `customer-identifier`; a `DuplicatedCustomer`
     response SHALL be resolved by looking the customer up;
   - in PARTNER mode, one lead identified by `lead-email` (create-or-return lead).

   The resulting `customer_id` SHALL be stored on each Salt Edge `Bank_Connection` and cached.

### Requirement 9: Salt Edge connector — connect, reconnect, refresh, consent

**User Story:** As a FINANCIER, I want to connect PKO BP, Erste and other banks through the Salt Edge
widget and renew access when consent expires, so that I never handle bank passwords in Foremen.

#### Acceptance Criteria

1. `startAuthorization` SHALL open a Salt Edge session and return its redirect URL:
   - **CLIENT mode:** `POST /connections/connect`, returning `data.connect_url`;
   - **PARTNER mode:** the Partners lead-session create endpoint, returning `data.redirect_url`.

   The request body SHALL contain:
   - `customer_id`;
   - `consent` `{scopes: ["accounts", "transactions"] plus the holder-information scope when the
     bank supports it, from_date: today − fetch-days}`;
   - `attempt` `{fetch_scopes: ["accounts", "balance", "transactions"] plus `holder_info` when the
     bank's `supported_fetch_scopes` include it (used by Requirement 3.7), fetch_from_date: today −
     fetch-days, return_to: {Public_Base_URL}/bank/connections/return?connectionId={id},
     custom_fields: {foremenConnectionId: id}}`;
   - `widget` `{skip_provider_selection: true when a provider code is given}`;
   - `provider` `{code, include_sandboxes: include-fake-providers}`;
   - `return_connection_id: true`, `return_error_class: true`, `automatic_refresh: true`,
     `categorization: "business"`.

   Exact Partners routes and field names SHALL be pinned against the Partners API v1 reference during
   implementation.
2. `listBanks("PL")` SHALL read the supported Polish banks live from the Salt Edge API —
   `GET /providers?country_code=PL&include_sandboxes=…&exclude_inactive=true` (paginated by
   `next_id`) — and return for each `Supported_Bank`:
   - `code`, `name`, `logo_url`, `home_url`, `status`;
   - access `mode` (`oauth | web | api | file`) and `regulated` (PSD2 channel);
   - `supported_account_types` (personal / business), `supported_fetch_scopes` (incl. holder
     info), `automatic_fetch`, `max_consent_days`, `max_fetch_interval`;
   - key ownership (`key_owner` / client-keys requirement), so that in CLIENT mode banks that need
     own provider keys are flagged as "requires own keys".

   Fake (`XF`) and sandbox providers SHALL be included only when `include-fake-providers = true`
   and SHALL be labelled as test banks. The result SHALL be cached for 1 hour and refreshable on
   demand (Requirement 19.9). No bank list SHALL be hard-coded in Foremen.
3. `completeAuthorization` SHALL accept the `connection_id` returned on `return_to`, or delivered by
   the success callback via `custom_fields.foremenConnectionId`, and SHALL be idempotent whichever
   arrives first.
4. `startReconnect` SHALL renew consent through the mode's reconnect endpoint (CLIENT
   `POST /connections/{id}/reconnect`; PARTNER the lead-session reconnect endpoint). It SHALL send a
   fresh consent (`scopes`, `from_date`) and the same `attempt.return_to`. On success the connection
   keeps its `externalConnectionId`.
5. `requestRefresh` SHALL first read the connection. IF `next_refresh_possible_at` is in the future,
   THEN it SHALL return "not yet possible" without calling refresh. Otherwise it SHALL call the mode's
   refresh endpoint (CLIENT `POST /connections/{id}/refresh` or `/background_refresh`; PARTNER the
   lead-session refresh endpoint) and store the new `next_refresh_possible_at`.
6. THE connector SHALL respect the PSD2 background-refresh limit (4 per 24 h per connection). It
   SHALL rely on Salt Edge `automatic_refresh` / `daily_refresh` for the daily refresh and SHALL NOT
   trigger background refresh from the scheduled pull; the scheduled pull SHALL only read
   already-fetched data.
7. `fetchConnectionState` SHALL read the connection and its latest consent. It SHALL map `status`
   (`active | inactive | disabled`) and the consent `expires_at` / `revoked_at` to
   `Bank_Connection.status` and `consentExpiresAt`.
8. `disable` SHALL revoke the consent (CLIENT: AIS consent; PARTNER: the partner consent, as required
   for Live) and remove the connection at Salt Edge. A `ConnectionNotFound` response SHALL be treated
   as success.
9. Switching `mode` SHALL NOT require ledger changes. Connections created under one mode SHALL remain
   readable; reconnecting them under the other mode SHALL be done by adding a new connection, and
   ingest dedupes by `(account, externalId)` per new account.

### Requirement 10: Salt Edge connector — callbacks

**User Story:** As the system, I want to react to Salt Edge callbacks safely, so that new data is
fetched promptly and forged requests are rejected.

#### Acceptance Criteria

1. THE System SHALL expose unauthenticated (JWT-free, `permitAll`) endpoints
   `POST /api/bank-feed/saltedge/callbacks/{type}` for `type ∈ {success, fail, notify, destroy}`.
   The URLs to register in the Salt Edge dashboard of each environment SHALL be
   `{Public_Base_URL}/api/bank-feed/saltedge/callbacks/{type}`; for example dev registers
   `https://dev.foremen.eu/api/bank-feed/saltedge/callbacks/success` (and `/fail`, `/notify`,
   `/destroy`).
2. Every callback SHALL be verified before use:
   - the `Signature` header SHALL be a base64 RSA-SHA256 signature of `{callback_url}|{raw_body}`,
     checked with the Salt Edge public key matching `Signature-key-version`;
   - `callback_url` SHALL be the exact `Public_Base_URL`-derived URL of that endpoint;
   - IF verification fails, THEN THE System SHALL respond `401` and ignore the payload.
3. Verified callbacks SHALL be answered `200` immediately; processing SHALL happen asynchronously
   after the response is committed.
4. `success` SHALL resolve the connection (by `connection_id`, else by
   `custom_fields.foremenConnectionId`), complete authorization when still `PENDING_AUTH`, and
   enqueue a `CALLBACK` `Sync_Run`.
5. `fail` SHALL store `error_class` / `error_message`, set the connection status per Requirement 12.4,
   and emit the sync-failed or re-auth notification as applicable.
6. `notify` SHALL only record the reported stage on the latest `Sync_Run` (no data fetch).
7. `destroy` SHALL set the connection `DISABLED` (history kept).
8. Callbacks for unknown connections SHALL be logged and answered `200` (so Salt Edge does not retry
   forever) without side effects.
9. Callback handling SHALL be idempotent: a repeated identical callback causes at most one extra
   (coalesced) `Sync_Run`.

### Requirement 11: Salt Edge connector — data fetch and mapping

**User Story:** As a FINANCIER, I want Salt Edge data mapped completely into the ledger, so that
payer/payee and dates are usable for matching.

#### Acceptance Criteria

1. `fetchAccounts` SHALL call `GET /accounts?connection_id=…` (paginated by `next_id`) and map:
   - `id → externalId`; `name`, `nature`, `currency_code`, `balance` to the same-named fields;
   - `extra.available_amount → availableBalance`;
   - `extra.iban → iban`, `extra.account_number → accountNumber`;
   - `extra.client_name | extra.holder_name → holderName`.
2. `fetchTransactions` SHALL call `GET /transactions?connection_id=…&account_id=…&from_id={cursor}`
   (paginated by `next_id`) for posted rows. It SHALL fetch pending rows as exposed by the
   transactions API of the active mode; the exact parameter SHALL be confirmed against the API
   reference during implementation. The cursor SHALL be the last seen transaction id per account.
3. Transaction mapping SHALL be:
   - `id → externalId`; `status (posted|pending) → status`;
   - `amount → amount` (signed); `currency_code → currency`;
   - `made_on → operationDate`; `extra.posting_date → bookingDate` (fallback `made_on` for posted);
   - `description → title`, with `extra.information` appended when present;
   - `category → category`;
   - for `INCOME`: `extra.payer → counterpartyName`, and `extra.payer_information` → parsed IBAN
     when present;
   - for `EXPENSE`: `extra.payee → counterpartyName`, and `extra.payee_information` → parsed IBAN
     when present;
   - `duplicated = true` → the row is skipped;
   - the full transaction JSON → `providerPayload`.
4. Mapping SHALL tolerate missing `extra` fields, which are provider-dependent (the provider's
   `supported_transaction_extra_fields`). It SHALL never fail the run on one malformed row; the row is
   skipped and counted.
5. Fetching SHALL use only data Salt Edge already holds (no implicit refresh).

### Requirement 12: Salt Edge connector — limits, errors and environments

**User Story:** As an operator, I want predictable behaviour on Salt Edge errors and a clear path
from Pending to Live, so that the integration can be certified.

#### Acceptance Criteria

1. THE connector SHALL map HTTP / `error.class` responses to typed connector errors:

   | Connector error | Salt Edge classes |
   |---|---|
   | `CONFIG` (auth / config) | `WrongClientCredentials`, `ClientDisabled`, `ClientPending`, `ClientRestricted`, `SignatureNotMatch`, `ExpiresAtInvalid`, `PublicKeyNotProvided` |
   | `REAUTH` (consent) | `AispConsentExpired`, `AispConsentRevoked`, `ConnectionDisabled`, `InvalidCredentials` and the Partners partner-consent equivalents |
   | `LIMIT` | `ConnectionCannotBeRefreshed`, `BackgroundFetchLimitExceeded`, `RateLimitExceeded`, `FakeConnectionsLimitReached`, `ConnectionLimitReached` |
   | `PROVIDER` (availability) | `ProviderUnavailable`, `ProviderInactive`, `ProviderDisabled`, `ProviderKeyNotFound` |
   | `TRANSIENT` | anything else, 5xx, timeout |

2. `TRANSIENT` errors SHALL be retried up to 3 times with exponential backoff (1 s, 4 s, 16 s) within
   a run; other classes SHALL NOT be retried.
3. Error messages shown to users SHALL be localized by class (`error.bank.provider.*`), never the raw
   provider text; the raw class and message SHALL be stored on the run for support.
4. Connection status mapping:
   - `REAUTH` → `REAUTH_REQUIRED`;
   - `CONFIG`, `PROVIDER`, and `TRANSIENT` after retries → `ERROR`;
   - `LIMIT` → status unchanged, run `PARTIAL` with the limit code.
5. THE spec SHALL document (design + test-cases) the Salt Edge dashboard setup per environment and
   per mode:
   - Application information and Company information;
   - the four callback URLs derived from that environment's `Public_Base_URL`;
   - the public key for request signing and team 2FA;
   - the "Request Test Access" step;
   - the request to switch the account to **Partner mode**, which is the production target.
6. THE connector SHALL work with fake (`fakebank_simple_xf`, `fakebank_oauth_xf`,
   `fake_business_demobank_xf`) and sandbox providers in Pending/Test status. In CLIENT mode it SHALL
   surface `ProviderKeyNotFound` (no own bank keys) as a localized "this bank requires provider keys —
   use Partner mode" message.

### Requirement 13: Link model

**User Story:** As a FINANCIER, I want every ledger line to be tied to what it actually paid for or
came from, so that project costs, payroll and client payments reconcile with the bank and the cash
register.

#### Acceptance Criteria

1. THE System SHALL persist applied `Link` rows with:
   - `transaction`, `kind`, `amount` (> 0, ≤ |transaction amount|);
   - target references by kind: `workerPayoutId`, `procurementDocumentId`, `paymentTransactionId` +
     `paymentScheduleId`, `projectId`, `unplannedCategoryId`;
   - `note`, `source` (`RULE | AI | MANUAL`), `confidence` (0..1, nullable for MANUAL), `createdBy`,
     audit.
2. Allowed kinds by direction:
   - `EXPENSE` → `WORKER_PAYOUT`, `PROCUREMENT`, `OTHER_EXPENSE`, `INTERNAL_TRANSFER`;
   - `INCOME` → `CLIENT_PAYMENT`, `OTHER_INCOME`, `INTERNAL_TRANSFER`.

   A mismatch SHALL be rejected with `400 error.bank.link.kindDirection`. The same rules apply to
   bank and cash lines.
3. Kind-specific required targets:
   - `WORKER_PAYOUT` → payout;
   - `PROCUREMENT` → document;
   - `CLIENT_PAYMENT` → schedule;
   - `OTHER_EXPENSE` → category, project optional;
   - `OTHER_INCOME` → project and note optional;
   - `INTERNAL_TRANSFER` → counter-account optional (incl. bank ↔ cash deposits / withdrawals).
4. A transaction MAY have several links (split payment, e.g. one transfer paying two invoices); the
   sum of link amounts SHALL NOT exceed |amount| (`400 error.bank.link.overAllocated`).
5. `linkStatus` SHALL be derived as the first rule that applies:
   - `IGNORED` — the transaction is ignored;
   - `LINKED` — Σ links = |amount| and every link is `MANUAL` or was confirmed;
   - `AUTO_LINKED` — Σ links = |amount| and at least one link is unconfirmed `RULE`;
   - `PARTIALLY_LINKED` — 0 < Σ links < |amount|;
   - `SUGGESTED` — no links but at least one open suggestion;
   - `UNLINKED` — otherwise.
6. A target SHALL NOT be linked beyond its own amount across all ledger lines: a payout's amount, a
   document's total when known, a tranche via FOR-06-07 reconciliation. Over-linking a payout or a
   document SHALL be rejected with `409 error.bank.link.targetOverLinked`.
7. Suggestions SHALL be stored separately (`bank_transaction_suggestions`) with the same target
   shape plus `rationale` (short text) and SHALL never have side effects until confirmed.

### Requirement 14: Auto-detection — rule matcher

**User Story:** As a FINANCIER, I want obvious matches to be found without AI, so that most of the
routine work is done deterministically and explainably.

#### Acceptance Criteria

1. WHEN a `BANK_FEED` transaction becomes `POSTED` and has no links, THE System SHALL run the rule
   matcher, then (if no `AUTO_LINKED` result) the AI classifier (Requirement 15). `MANUAL` / `SYSTEM`
   cash entries are created already linked or linked by the user and are not auto-detected.
2. Internal transfer: IF the counterparty IBAN equals an `Own_Account` IBAN, THEN the matcher SHALL
   create an `INTERNAL_TRANSFER` link (`RULE`, confidence 1.0) → `AUTO_LINKED`.
3. Counterparty rule: IF a `Counterparty_Rule` matches the counterparty key, THEN the matcher SHALL
   resolve candidates of the rule's target type (e.g. the worker's open payouts) and score them.
4. Client payment: for `INCOME`, IF the title contains a valid `Project_Reference` `FM-{projectId}`
   for a project with a published schedule, THEN the matcher SHALL propose `CLIENT_PAYMENT` for that
   schedule with confidence 0.95. It SHALL auto-apply that proposal (`AUTO_LINKED`) when the project
   exists and the schedule is published. Without a reference, it SHALL score schedules whose project
   CLIENT member's name or NIP matches the counterparty and whose open tranche remainder equals the
   amount.
5. Worker payout: for `EXPENSE`, the matcher SHALL score payouts with `paymentChannel =
   BANK_TRANSFER`:
   - `PLANNED` payouts: exact amount +0.5; payout date within ±7 days of the booking date +0.2;
     counterparty name ≈ worker name (normalized, diacritics-insensitive token match) +0.2, or
     counterparty rule hit +0.3;
   - `PAID` payouts not yet linked: the same criteria with −0.1.
6. Procurement: for `EXPENSE`, the matcher SHALL score procurement documents in
   `Document_Payment_Status` `UNMATCHED` or `PARTIALLY_PAID`:
   - amount = `documentTotal` (±0.01): +0.5;
   - invoice number found in the title: +0.4;
   - supplier NIP or name ≈ counterparty: +0.2;
   - uploaded / invoice date within −60..+30 days of the booking date: +0.1.
7. THE matcher SHALL auto-apply a candidate (`AUTO_LINKED`) only when its score ≥
   `foremen.bank-ledger.match.auto-threshold` (default 0.9) **and** it beats the runner-up by ≥ 0.2.
   Candidates scoring ≥ `suggest-threshold` (default 0.5) SHALL be stored as suggestions (top 3 per
   transaction); others are discarded.
8. THE matcher SHALL be a pure, deterministic component (same inputs → same output) covered by
   property tests.

### Requirement 15: Auto-detection — AI classifier

**User Story:** As a FINANCIER, I want an AI hint for transactions the rules cannot place, so that
other expenses get a category and ambiguous cases get a ranked guess.

#### Acceptance Criteria

1. THE System SHALL define a `Bank_Transaction_Classifier` interface with two implementations: a
   Gemini one (Google Gen AI SDK, reusing the existing Vertex / Gemini configuration pattern) enabled
   by `foremen.bank-ledger.ai.enabled` (default false), and a disabled No-Op fallback.
2. The classifier input SHALL contain:
   - direction, amount, currency, booking date, title;
   - counterparty name and the counterparty IBAN **masked to the last 4 digits**;
   - the bank name;
   - the rule-matcher candidates (opaque refs + human labels);
   - the active `UnplannedExpenseCategory` list (code + PL/RU names);
   - the list of active projects (id + name + city).
3. The prompt SHALL demand JSON only in the shape `{kind, candidateRef|null, categoryCode|null,
   projectId|null, confidence (0..1), rationale (≤ 200 chars)}`. Parsing SHALL be tolerant (fence
   stripping + brace slicing, as in `OcrHelpers`).
4. THE System SHALL validate the answer: `kind` allowed for the direction; `candidateRef` one of the
   supplied candidates; `categoryCode` an active category; `projectId` an active project. Any field
   that fails SHALL be dropped and the confidence capped at 0.3.
5. AI results SHALL always be stored as **suggestions** (`source = AI`), never auto-applied.
6. The classifier SHALL run asynchronously after ingest, at most once per transaction unless a user
   requests "Re-run AI". Calls SHALL be rate-limited (`foremen.bank-ledger.ai.max-per-run`, default
   200). Failures SHALL leave the transaction `UNLINKED` without failing the sync.
7. No AI call SHALL be made when the classifier is disabled; the UI SHALL then hide AI markers and the
   "Re-run AI" action.

### Requirement 16: Link effects on the linked domain

**User Story:** As a FINANCIER, I want confirming a link to update payroll, purchases and client
payments, so that I don't record the same payment twice.

#### Acceptance Criteria

1. Linking to an **existing** payout: the target picker SHALL offer the worker's existing payouts
   first (`PLANNED`, then `PAID` not yet fully linked).
   - Linking a `PLANNED` payout (auto or manual) SHALL require the link amount to equal the payout
     amount. It SHALL mark the payout `PAID` with `paidAt = bookingDate` and `paymentChannel =
     BANK_TRANSFER` (or `CASH` for a cash line) through `WorkerPayoutService`; FOR-07-06 logic is
     unchanged.
   - Linking an already `PAID` payout SHALL only record the link.
2. Creating a **new** payout from a ledger line, when no existing payout fits: THE System SHALL let the
   user pick a worker and create a payout through the existing create (distribution) flow with
   `payoutDate = bookingDate`, `amount = |link amount|`, and `paymentChannel` = the line's channel.
   It SHALL then mark the payout `PAID` and link it.
3. Every payout linked to a ledger line SHALL expose its ledger line(s) (`ledgerEntryIds`, channel,
   booking date, account) on the payout read model. The FOR-07-06 payroll card SHALL show a link to
   each line (`/company/bank-transactions?transactionId={id}`).
4. `PROCUREMENT`: linking SHALL record the link, recompute the document's `Document_Payment_Status`,
   and expose the linked ledger line(s) on the document read model. No other document field is
   changed.
5. "Upload invoice from a ledger line": THE System SHALL let the user upload a scan through the
   existing FOR-06-03 upload endpoint (project selection required until FOR-07-03 makes the project
   optional). The new document SHALL be linked to the already existing ledger line immediately, and
   the document status SHALL be shown live as the OCR pipeline proceeds. The link SHALL be kept when
   OCR fails (the user can reprocess). No "Paid in cash" checkbox is shown here (Requirement 28 does not
   apply).
6. `CLIENT_PAYMENT`: applying the link SHALL record a `CONFIRMED` `PaymentTransaction` on the chosen
   schedule through a new `PaymentTransactionService.recordBankTransfer(scheduleId,
   BankTransferInput)`:
   - channel `BANK_TRANSFER` for a bank line, `MANUAL` for a cash line;
   - `externalRef = "bank-txn:{transactionId}:{linkId}"` and `receivedAt = bookingDate`.

   It SHALL then reconcile it with the unchanged FOR-06-07 cascade; the FOR-06-07 `payment.received`
   / `tranchePaid` notifications fire as today.
7. Once a `CLIENT_PAYMENT` link has been reconciled, it SHALL be **locked**: unlink / change SHALL be
   rejected with `409 error.bank.link.locked` (reversal of reconciliation is out of scope).
8. Removing a `WORKER_PAYOUT` link SHALL NOT revert the payout status (the UI warns that the payout
   stays `PAID` and must be corrected in payroll if wrong).
9. `OTHER_EXPENSE`, `OTHER_INCOME`, `INTERNAL_TRANSFER` links have no external side effects.
10. Every link create / change / remove SHALL be audited and, together with its effects, executed in
    one database transaction.

### Requirement 17: Override, manual linking and learning

**User Story:** As a FINANCIER or MANAGER, I want to accept, change or reject any automatic decision
and link manually, so that the ledger is always right.

#### Acceptance Criteria

1. A user SHALL be able to:
   - confirm a suggestion (→ link, `source` kept, `confirmedBy` set);
   - confirm an `AUTO_LINKED` transaction (marks it reviewed → `LINKED`);
   - reject a suggestion;
   - replace any unlocked link with another target (override);
   - add a split link;
   - remove an unlocked link.
2. Manual linking SHALL offer target pickers per kind with search:
   - workers with their existing payouts, plus "create new payout";
   - procurement documents (filter by project, supplier, amount, date, `Document_Payment_Status`),
     plus "upload invoice";
   - projects with published schedules;
   - other expense categories with an optional project;
   - own bank and cash accounts for internal transfers.
3. WHEN a user confirms or creates a link on a `BANK_FEED` line, THE System SHALL upsert a
   `Counterparty_Rule` for the counterparty key → target type (worker id, supplier name/NIP, project
   schedule, category + project, internal transfer), unless the user unticks "Remember for this
   counterparty".
4. `Counterparty_Rule` rows SHALL be manageable (list, edit target, disable, delete) in the Bank
   menu "Counterparty rules" tab.
5. A user SHALL be able to mark a transaction `IGNORED` with a reason (e.g. a private card
   operation) and un-ignore it. Ignored transactions are excluded from "To review" and from totals of
   linked amounts.
6. A user SHALL be able to request "Re-run detection" (rules + AI) on one transaction or on a filtered
   selection of `UNLINKED` / `SUGGESTED` transactions.

### Requirement 18: Procurement OCR field extension for matching

**User Story:** As a FINANCIER, I want invoices to carry supplier NIP, invoice number and invoice date,
so that bank transfers are matched to the right invoice.

#### Acceptance Criteria

1. THE System SHALL add nullable columns `supplier_nip` (10 digits, normalized), `invoice_number`
   (≤ 64 chars), `invoice_date` (date) to `procurement_documents`.
2. The FOR-06-03 per-line parse prompt SHALL additionally request `supplierNip`, `invoiceNumber`,
   `invoiceDate` (ISO date). The parser SHALL capture them tolerantly (invalid NIP checksum → null,
   unparseable date → null) without changing the existing line extraction.
3. The procurement document read DTOs SHALL expose the three fields, the `Document_Payment_Status`
   and the linked ledger line(s). Existing documents keep null values (no back-fill) and are
   `UNMATCHED`.
4. Existing FOR-06-03/04 tests SHALL keep passing; new parser tests cover the three fields.

### Requirement 19: Bank menu (connections, accounts, sync history, counterparty rules)

**User Story:** As an ADMIN or FINANCIER, I want one Bank menu entry with all bank administration
tabs, so that bank setup is in one place.

#### Acceptance Criteria

1. THE frontend SHALL add a main-menu item **Bank** (`nav.bank`, path `/bank`, icon `landmark`) in the
   **administration** section `nav.sections.system` (next to Users, Roles, Audit). It SHALL be gated
   by `BANK_CONNECTIONS`/READ, so it is visible to ADMIN and FINANCIER only.
2. The Bank page SHALL have tabs:
   - **Connections** (`/bank/connections`, default);
   - **Accounts** (`/bank/accounts`, bank and cash accounts);
   - **Sync history** (`/bank/sync-runs`);
   - **Counterparty rules** (`/bank/counterparty-rules`);
   - **Supported banks** (`/bank/supported-banks`).
3. Connections tab:
   - table columns: bank logo + name, display name, provider, status badge, consent valid until,
     last successful sync, accounts count, error hint;
   - actions: Add bank, Continue authorization, Reconnect, Refresh now, Sync now, Rename, Disable
     (confirm dialog), Delete (disabled + no links only), Credentials (only for connectors with
     credential fields);
   - a banner for `CONSENT_EXPIRING` / `REAUTH_REQUIRED` connections.
4. Add bank dialog: provider choice (available providers only), then a bank picker with search over
   the live `Supported_Bank` list (`listBanks`, Requirement 9.2) showing logo, name and business /
   personal support, with banks flagged "requires own keys" disabled in CLIENT mode; then redirect. The return route `/bank/connections/return` SHALL complete the
   authorization, show success or a localized error, and navigate back to the Connections tab.
5. Accounts tab:
   - bank accounts grouped by connection: name, IBAN formatted, currency, balance, available, balance
     updated at, holder name with the `Holder_Match` badge (match / mismatch warning / not verified),
     included-in-ledger switch (including a `MISMATCH` account opens the confirmation-with-reason
     dialog of Requirement 3.8), balance-gap badge with "Acknowledge" action, closed-at-provider
     badge;
   - a **Cash accounts** group: name, currency, computed balance, default-for-cash flag;
   - actions "Add cash account", "Edit", "Archive" (Requirement 27).
6. Sync history tab: table (started, finished, connection, trigger, status, new / updated / pending
   replaced / to review counts, error) with filters by connection, status and period. Row detail
   shows the per-account breakdown.
7. Counterparty rules tab: table (counterparty key, display name, target type, target label, project,
   category, hits, last used, enabled) with edit / disable / delete.
8. All tables SHALL use the shared `components/data-table` set (sorting, filters, pagination, mobile
   cards).
9. Supported banks tab: the live list of Polish banks from the active connector (Requirement 9.2),
   read from the Salt Edge API — never hard-coded:
   - columns: logo + name, access mode, PSD2 regulated, business accounts supported, personal accounts
     supported, holder info supported, automatic daily refresh, max consent days, status, and in
     CLIENT mode "requires own keys";
   - filters: search by name, business-only, hide test (fake / sandbox) banks;
   - a "Connect" action per bank that opens the Add bank flow with that bank preselected (disabled
     for banks not usable in the current mode);
   - a "Refresh list" action bypassing the 1-hour cache, and the time the list was last fetched;
   - an explanatory empty state when the provider returns no Polish banks — e.g. a Salt Edge account
     in Pending status exposes only fake providers — telling the user to request Test / Live access
     (or Partner mode) in the Salt Edge dashboard;
   - a localized error state when the connector is unavailable or the provider call fails.

### Requirement 20: Bank transactions menu (ledger)

**User Story:** As an ADMIN, FINANCIER or MANAGER, I want a list of all bank and cash money flows with
what each one is linked to, so that I can review and correct them quickly.

#### Acceptance Criteria

1. THE frontend SHALL add a main-menu item **Bank transactions** (`nav.bankTransactions`, path
   `/company/bank-transactions`, icon `arrow-left-right`) in the **company-wide** section
   `nav.sections.company` (next to Staffing Gantt, Company procurement, Payroll). It SHALL be gated by
   `BANK_LEDGER`/READ (ADMIN, FINANCIER, MANAGER) and SHALL list bank and cash lines together.
2. The list SHALL show per row:
   - booking date (operation date when pending);
   - **channel** badge (Bank / Cash) and **origin** hint (feed / manual / system);
   - **direction** badge (Income / Expense);
   - **bank / account**: bank name + logo and account name + IBAN last 4, or the cash account name;
   - **counterparty**: payer for income, payee for expense, with IBAN;
   - title, truncated, full on hover;
   - amount, signed, coloured, with currency;
   - **auto-detection marker**: Rule / AI icon with confidence tooltip and rationale; none for manual
     and system entries;
   - **linked entity**: kind badge + label + project, multiple when split, linking to the entity;
   - **status** badge (`Link_Status`, plus Pending badge).
3. Filters:
   - period, with presets current month / previous month / current year;
   - channel (Bank / Cash), direction, bank / connection, account;
   - link status (incl. "To review"), link kind, project;
   - auto-detected (Rule / AI / Manual / none);
   - amount range;
   - free-text search over title, counterparty and IBAN.

   The filter state SHALL persist in the browser (`localStorage`). A `transactionId` query parameter
   SHALL open that line's drawer, for deep links from payouts and documents.
4. Totals bar: Σ income, Σ expense, net, count, count to review — for the current filter.
5. Row detail drawer:
   - all transaction fields; the provider payload (collapsed, ADMIN only);
   - current links with their source / confidence / confirmed-by;
   - suggestions with "Confirm" / "Reject";
   - "Remember for this counterparty" checkbox.

   Drawer actions:
   - Link manually (kind picker → target picker → amount, default full remainder), Split, Change link
     (override), Remove link;
   - Link existing payout / Create payout (expense);
   - Link existing document / Upload invoice (expense; reuses the FOR-06-03 upload with project picker
     and shows OCR progress);
   - Mark ignored / Un-ignore, Re-run detection;
   - for `MANUAL` cash entries, Edit / Delete (Requirement 27).
6. A toolbar action **Add cash entry** SHALL open the manual entry form (Requirement 27.3).
7. Bulk actions on selected rows: Confirm all auto-links / top suggestions, Re-run detection, Mark
   ignored.
8. Locked links SHALL show a lock icon and disabled edit actions with an explanatory tooltip.
9. Actions not permitted to the user SHALL be hidden: Upload invoice requires `PURCHASES`/CREATE for
   the chosen project; Create payout requires `PAYROLL`/CREATE.

### Requirement 21: Access control (ABAC)

**User Story:** As an ADMIN, I want bank administration restricted and the ledger available to the
roles that work with money, so that bank access stays safe.

#### Acceptance Criteria

1. THE System SHALL seed resource `BANK_CONNECTIONS` with grants ADMIN — CREATE, READ, UPDATE,
   DELETE and FINANCIER — CREATE, READ, UPDATE, DELETE. The resource covers connections, bank and
   cash accounts, sync runs, credentials and counterparty rules.
2. THE System SHALL seed resource `BANK_LEDGER` with grants ADMIN, FINANCIER, MANAGER — CREATE, READ,
   UPDATE, DELETE. The resource covers ledger lines, manual cash entries, links, suggestions, ignore
   and re-run.
3. Both resources are company-wide (not project-scoped). Controllers SHALL carry
   `@PermissionResource` and per-handler operations per the workspace entity checklist. Seeds SHALL be
   idempotent (`onFail="MARK_RAN"` guards) and registered last in `changelog.xml`.
4. Link effects SHALL be executed by the ledger service as a system action authorized by
   `BANK_LEDGER`. The cash-entry hooks of Requirements 27–28 SHALL run as system actions inside the
   payroll / procurement operations already authorized by `PAYROLL` / `PURCHASES`. The UI SHALL
   additionally hide "Upload invoice" and "Create payout" unless the user holds `PURCHASES`/CREATE and
   `PAYROLL`/CREATE respectively, and those two actions SHALL call the existing endpoints with their
   existing guards.
5. The Salt Edge callback endpoints SHALL be `permitAll` in `SecurityConfig` and authenticated solely
   by signature verification (Requirement 10.2). The stub seeding endpoint SHALL exist only when the
   stub connector is enabled and SHALL require `BANK_CONNECTIONS`/UPDATE.
6. Read DTOs SHALL never contain credentials or provider secrets; `providerPayload` SHALL be returned
   only to ADMIN.

### Requirement 22: Notifications

**User Story:** As an ADMIN, FINANCIER or MANAGER, I want to be told when the bank feed needs action or
new transactions need review, so that nothing slips.

#### Acceptance Criteria

1. THE System SHALL emit notifications only through the generic `Notification_Service` (FOR-05-07)
   and SHALL NOT re-implement delivery. Recipients are resolved by **company role**
   (`UserDao.findByRoleCode`, active users only).
2. THE System SHALL emit the following set. The title is localized from the type key, `body = null`,
   and every row carries a deep-link:

   | # | Trigger | Recipients | Type key | Deep-link |
   |---|---------|-----------|----------|-----------|
   | 1 | A connection enters `CONSENT_EXPIRING` (once per consent) | ADMIN, FINANCIER | `notification.bank.consentExpiring` | `/bank/connections?connectionId={id}` |
   | 2 | A connection enters `REAUTH_REQUIRED` | ADMIN, FINANCIER | `notification.bank.reauthRequired` | `/bank/connections?connectionId={id}` |
   | 3 | A `Sync_Run` ends `FAILED` (at most once per connection per 24 h) | ADMIN, FINANCIER | `notification.bank.syncFailed` | `/bank/sync-runs?connectionId={id}` |
   | 4 | A `Balance_Gap` is recorded | ADMIN, FINANCIER | `notification.bank.balanceGap` | `/bank/accounts?accountId={id}` |
   | 5 | A `Sync_Run` ingested ≥ 1 new posted transaction that ends in `To_Review` | ADMIN, FINANCIER, MANAGER | `notification.bank.transactionsToReview` | `/company/bank-transactions?status=TO_REVIEW` |

3. Client-payment links SHALL rely on the existing FOR-06-07 notifications (`payment.received`,
   `payment.tranchePaid`, `payment.amountMismatch`) — no duplicate bank notification.
4. Invoice uploads SHALL rely on the existing FOR-06-03 OCR notifications.
5. Cash entries (manual or created by the payroll / procurement hooks) SHALL emit no notification:
   they are created by the user's own action and are already linked. A `Holder_Match` `MISMATCH`
   SHALL also emit no notification: it appears during the connecting user's own session and is shown
   as a warning badge and banner on the Accounts tab.
6. Emitting is best-effort: a failure SHALL be logged and SHALL NOT roll back the triggering change
   (emit `AFTER_COMMIT`).
7. Every new type key SHALL have PL + RU titles (locale-parity test).

### Requirement 23: Internationalization

**User Story:** As a user, I want the whole bank UI in my language, so that no raw key or English-only
text appears.

#### Acceptance Criteria

1. THE System SHALL NOT add any backend `nameRU` / `namePL` column. Bank names come from the
   provider; account names (incl. cash account names), titles and counterparties are user or bank
   data; categories reuse `UnplannedExpenseCategory`, which is already localized.
2. THE frontend SHALL localize in **PL and RU** with full parity. This covers navigation items, Bank
   tab labels, every table column, filter, button, dialog, placeholder, empty state, confirmation,
   toast, tooltip, validation and error message, the cash-entry form, the procurement "Paid in cash" checkbox,
   the holder-verification badges and override dialog, the Supported banks tab (columns, filters,
   empty and error states), and the five notification titles. The key set is listed in the design (`bank.*`,
   `bankTransactions.*`, `nav.bank`, `nav.bankTransactions`, `notification.bank.*`, `error.bank.*`).
3. Every user-facing enum SHALL use `Enum_Label_I18n`: `Feed_Provider`, `Bank_Connection.status`,
   `Bank_Account.type`, `Sync_Run.trigger`, `Sync_Run.status`, `Bank_Transaction.status`, `channel`,
   `origin`, `Direction`, `Link_Status`, link `kind`, `Detection_Source`, `Document_Payment_Status`,
   `Holder_Match`, the Supported_Bank access mode and account types,
   the procurement "Paid in cash" checkbox, and the connector error class.
4. Backend error codes (`error.bank.*`) SHALL be resolvable in the backend message bundles (PL, RU,
   default), consistent with existing `error.*` handling.
5. Amounts and dates SHALL be formatted with the active locale; IBANs SHALL be displayed in groups of
   four.

### Requirement 24: Security and data protection

**User Story:** As the company, I want bank data handled carefully, so that we stay compliant and safe.

#### Acceptance Criteria

1. Provider secrets and per-connection credentials SHALL follow Requirement 2. Logs SHALL never
   contain secrets, signatures, full IBANs of counterparties (mask to last 4), or provider payloads at
   INFO level.
2. Callback endpoints SHALL verify signatures before parsing business data and SHALL limit body size
   (1 MB).
3. AI prompts SHALL contain only the fields listed in Requirement 15.2 (no full IBANs, no provider
   payload, no user emails).
4. All mutating ledger, cash and connection actions SHALL be recorded in the existing audit trail.
5. The return route SHALL not trust query parameters beyond the connection id; completion SHALL be
   validated against the provider (`fetchConnectionState`).
6. Environment-specific values (public base URL, Salt Edge mode, keys, AI and encryption settings)
   SHALL come only from configuration / environment and SHALL NOT be committed.

### Requirement 25: Testing

**User Story:** As a developer, I want the bank flow covered by fast tests, so that changes don't
break money data.

#### Acceptance Criteria

1. Pure components SHALL have unit tests, plus jqwik property tests where invariants exist:
   - components: IBAN normalizer, counterparty extraction, balance-continuity checker, link-status
     deriver, rule matcher scoring, AI answer validator, Salt Edge request signer and callback
     verifier, Salt Edge mapper (both modes), document payment status deriver, holder matcher
     (NIP / name normalization, MATCH / MISMATCH / UNKNOWN), supported-bank mapper;
   - invariants: idempotent ingest, Σ links ≤ |amount|, matcher determinism, status derivation
     totality, signature round-trip.
2. The Salt Edge connector SHALL be tested in CLIENT and PARTNER modes against recorded JSON fixtures
   with an HTTP mock (WireMock or `MockRestServiceServer`), including pagination, pending rows, errors
   and limits.
3. Integration tests (Testcontainers) SHALL cover:
   - STUB connect → sync → ingest → auto-link → effects (payout paid, client payment reconciled);
   - the cash flows of Requirements 27–28 (cash payout → cash line; procurement paid in cash → cash
     line; document left unmatched, then auto-linked on the next import);
   - callback signature accept / reject;
   - ABAC per role;
   - idempotent re-sync.
4. Frontend tests (vitest) SHALL cover the two pages, the drawer actions, the cash-entry form, the
   procurement "Paid in cash" checkbox, the filters persistence and locale parity.
5. `test-cases.md` SHALL describe API scenarios against the Docker stack (STUB connector) and
   browser scenarios, with a run-id strategy and an MD report template.

### Requirement 26: Non-goals

1. Payment initiation (PIS, bulk payouts) from Foremen.
2. Statement file import (MT940 / camt.053) and other providers (emSzmal, Enable Banking, Kontomatik)
   — future connectors on the same SPI.
3. Obtaining an own PSD2/AISP licence and own bank developer keys (PKO BP, Erste, …) — production
   relies on Salt Edge Partner mode.
4. Currency conversion and multi-currency reporting.
5. Reversal of a reconciled client payment (locked links, Requirement 16.7).
6. Making `procurement_documents.project_id` nullable (FOR-07-03).
7. Accounting exports (JPK, ERP postings), VAT split-payment handling, and fiscal cash-register
   (KSeF / kasa fiskalna) integration.
8. Email / SMS / Telegram delivery and the async delegating notification fan-out.
9. Production deployment, Secret Manager provisioning and the production domain (FOR-12).

### Requirement 27: Cash accounts and non-bank money flows

**User Story:** As a FINANCIER or MANAGER, I want cash payments recorded in the same ledger as bank
operations, so that every money flow of the company — bank or cash — is visible and linked.

#### Acceptance Criteria

1. THE System SHALL support `Cash_Account`s (`Bank_Account.type = CASH`): name (user data), currency,
   optional opening balance, `defaultForCash` flag (at most one per currency), archived flag. Cash
   accounts are created / edited / archived in the Bank menu "Accounts" tab (`BANK_CONNECTIONS`).
   A cash account's balance SHALL be computed as opening balance + Σ its posted entries.
2. Cash entries SHALL be `Bank_Transaction` rows with `channel = CASH`, `status = POSTED`, `origin`
   `MANUAL` or `SYSTEM`, on a cash account. They SHALL use the same link model, statuses, filters and
   totals as bank lines.
3. **Manual entry** — in "Bank transactions", "Add cash entry" (`BANK_LEDGER`/CREATE) SHALL ask for:
   - payment method — **Cash** (the only manual method in this spec; bank lines come only from the
     feed);
   - cash account, defaulting to `defaultForCash`;
   - direction, amount, date, counterparty, title;
   - an optional link, set in the same form (any kind allowed for the direction; e.g. create / link a
     payout, link / upload a document, client payment, other expense/income with optional project).

   With a link the entry is `LINKED`; without one it is `UNLINKED`.
4. **Payroll in cash** — WHEN a payroll payout (FOR-07-06) is marked `PAID` with `paymentChannel =
   CASH` (in the payroll screens, not from the ledger), THE System SHALL create a `SYSTEM` cash entry
   (expense) on the `defaultForCash` account of the payout currency:
   - amount = payout amount; date = `paidAt`; counterparty = worker name; title = payout note or
     period;
   - linked `WORKER_PAYOUT` to that payout, `LINKED`.

   IF no default cash account exists, THEN THE System SHALL create one (name "Cash {currency}") and
   flag it default. The payout read model SHALL show the entry (Requirement 16.3).
5. A payout paid with `paymentChannel = BANK_TRANSFER` SHALL NOT create any entry. It stays an open
   candidate for the matcher until its bank line arrives (Requirement 14.5).
6. `MANUAL` cash entries SHALL be editable and deletable while none of their links is locked.
   `SYSTEM` entries SHALL be read-only in the ledger; they are corrected at their source (payroll /
   procurement). Deleting a `MANUAL` entry SHALL remove its unlocked links (payout status unchanged,
   Requirement 16.8).
7. Cash entries SHALL be excluded from the balance-continuity check (Requirement 7.4), from connector
   sync, and from auto-detection (Requirement 14.1).

### Requirement 28: Procurement documents uploaded outside the ledger

**User Story:** As a FINANCIER or MANAGER uploading an invoice in the procurement screens, I want to
tick "Paid in cash" when it was paid in cash, so that the document is linked to its money flow
now (amount and date taken from OCR) or automatically later.

#### Acceptance Criteria

1. WHEN a procurement document is uploaded from the procurement screens (FOR-06-03/04, not from a
   ledger line), the upload control SHALL offer only a **"Paid in cash"** checkbox (unchecked by
   default). It SHALL NOT ask for an amount, a date, a cash account or any other payment choice.
   - **Checked** — the document is uploaded as a plain upload. WHEN its OCR pipeline reaches
     `PARSED`, THE System SHALL mark it paid in cash with no user input: it creates a `SYSTEM` cash
     entry (expense) on the **default** cash account and links it `PROCUREMENT` to the document:
     - amount = the OCR `documentTotal`;
     - date = the OCR invoice date when known, else the upload date.
     IF OCR ends `FAILED` / `UPLOADED` (OCR disabled), no cash entry SHALL be created. IF OCR found
               the request SHALL be refuse   ith `error.procurement.cash.amountRequired`, shown
     inline; the user can then use "Mark paid in cash" on the document row (criterion 4).
   - **Unchecked** (default) —    - **Unchecked** (default) —    - **Unchecked** (default) —    - **Unchecked** (default) —    y to a caller with
     `PURCHASES`/UPDATE.
2. WHEN the OCR pipeline finishes for an `UNMATCHED` document (`PARSED`), THE System SHALL run reverse
   matching against existing `UNLINKED` / `PARTIALLY_LINKED` expense lines. It SHALL use the
   Requirement 14.6 scoring and thresholds: auto-link at ≥ auto-threshold with margin, otherwise
   store a suggestion on the best line.
3. `UNMATCHED` documents SHALL remain candidates for every later bank import (Requirement 14.6), so
   they are eventually linked automatically or suggested for confirmation.
4. The procurement document list and detail SHALL show the `Document_Payment_Status` (Unmatched /
   Partially paid / Paid) and links to the ledger line(s), and SHALL offer "Find bank transaction" /
   "Mark paid in cash" actions on `UNMATCHED` documents. "Find bank transaction" shows unlinked /
   partially linked expense lines (bank or cash) ranked by the matcher's procurement scoring and
   links the chosen one. "Mark paid in cash" asks only for the amount (prefilled with
   `documentTotal`) and the date (prefilled with the invoice date); the entry is always booked on
   the default cash account (no cash account is chosen).
5. A document uploaded from a ledger line (Requirement 16.5) is linked to that line at creation and
   shows no "Paid in cash" checkbox.
