# Requirements Document: FOR-07-04a — Fintable bank feed connector

## Introduction

FOR-07-04 (bank ledger) collects bank transactions through a provider-agnostic `BankFeedConnector`
SPI. Today the only real provider is **Salt Edge** (plus the dev/test `STUB`). Salt Edge access is
not available to the company, so this spec adds a second real provider, **Fintable**
(<https://fintable.io>, API V2, `https://fintable.io/api/v2`), and puts Salt Edge behind a feature
flag. Everything downstream of the connector (ingest, balance check, detection, links, notifications,
Bank page, ledger page) is reused as-is.

**What Fintable is.** An aggregator over GoCardless (`NORDIGEN`), Enable Banking and Plaid. One
Personal Access Token (PAT) gives access to one Fintable Workspace. One Fintable *connection* is one
bank login. Fintable syncs each connection on its own daily schedule; Foremen reads the result by
polling. Fintable has **no webhooks**.

**Verified against the live API** (token test of 2026-11-18, see `design.md` §Verified facts):
- Polish banks are available (PKO BP incl. iPKO Biznes, mBank, ING, Pekao, Santander, Millennium,
  Alior) through GoCardless and Enable Banking.
- Amounts are decimal strings, negative = money out; ids are opaque (`conn_…`, `acc_…`, `tx_…`).
- Pending items are a replace-all snapshot with no ids and never become transactions.
- `redirect_url` of a connection link must be on the token's *Return hosts* allow-list.
- The institution directory is search-only (`q` ≥ 3 characters, 10 groups per page).
- Link minting is limited to 1 per minute; manual `POST /sync` to 2 per day (Personal) or 1 per hour
  (Business).

### Relationship to existing specs

| Spec | Relation |
|------|----------|
| FOR-07-04 bank ledger | Parent. This spec adds a connector and small SPI generalisations; every other FOR-07-04 requirement keeps holding. Requirement numbers `R<n>` below refer to FOR-07-04. |
| FOR-05-07 notifications | Emit-only through the generic `NotificationService`. |

### Scope boundaries

- **In scope:** the `FINTABLE` provider (connector, configuration, catalogue search, connect /
  reconnect / disconnect, accounts, transactions, state mapping, sync waiting), the Salt Edge on/off
  flag and its behaviour when off, SPI generalisations needed for a second real provider, the Fintable
  token-expiry notification, frontend changes for provider choice, search picker, attribution and
  disabled-provider display, PL + RU localization, runbook, test cases.
- **Out of scope:** removing Salt Edge code or data; migrating existing Salt Edge connections;
  Fintable's categorizer, rules, holdings, spreadsheets or integrations; payment initiation; a direct
  Enable Banking connector (a candidate follow-up spec); webhook-like push (Fintable has none).

## Glossary

- **Fintable_API** — `https://fintable.io/api/v2`, authenticated with a Bearer PAT.
- **PAT** — Fintable Personal Access Token, scopes `read` and `write`, valid 1 year.
- **Fintable_Workspace** — the isolated set of connections of the token's default workspace (or the
  one named by `workspace_id`).
- **Fintable_Connection** — one linked bank login at Fintable, id `conn_{provider}_{number}`; maps
  1:1 to a Foremen `Bank_Connection` (its `externalConnectionId`).
- **Institution_Slug** — Fintable's id of one connection option of a bank (for example
  `9079_pko-bank-polski`); stored as the Foremen `providerCode`.
- **Backing_Provider** — the data provider behind an institution slug: `NORDIGEN` (GoCardless),
  `ENABLEBANKING`, `PLAID`, `AKOYA`, `TABS`, `SNAPTRADE`.
- **Return_Hosts** — the allow-list of hosts accepted as `redirect_url`, configured in the Fintable
  dashboard (Dashboard → API → Return hosts).
- **Provider_Flag** — a per-provider on/off switch: `foremen.bank-feed.saltedge.enabled`
  (`SALTEDGE_ENABLED`) and `foremen.bank-feed.fintable.enabled` (`FINTABLE_ENABLED`).
- **Resume_Cursor** — the per-account cursor stored after a sync (`bank_accounts.transactions_cursor`).
- **Updated_Since_Window** — the incremental read `GET /transactions?order=updated&updated_since=…`.

## Requirements

### Requirement 1: Provider feature flags (Salt Edge off, Fintable on)

**User Story:** As an administrator, I want to switch Salt Edge off and Fintable on by configuration,
so that the company uses only the provider it has access to.

#### Acceptance Criteria

1. THE System SHALL keep `SALTEDGE_ENABLED` (default `false`) as the single Salt Edge flag and SHALL
   add `FINTABLE_ENABLED` (default `false`) as the Fintable flag. No other flag name SHALL be added
   for the same purpose.
2. WHILE a Provider_Flag is `false`, THE connector of that provider SHALL report
   `isAvailable() = false`, SHALL NOT call the provider, and SHALL NOT fail application start-up.
3. THE `GET /api/bank/providers` endpoint and the "Add bank" dialog SHALL list only providers whose
   connector is available (existing R4.5 behaviour, unchanged).
4. WHILE the Salt Edge flag is `false`, THE `BankSyncScheduler` SHALL skip every connection whose
   provider connector is unavailable. A skipped connection SHALL NOT produce a failed run, SHALL NOT
   change its status, and SHALL NOT emit a `syncFailed` notification.
5. WHILE a connection's provider connector is unavailable, THE System SHALL answer manual actions on
   it (authorize, complete, reconnect, refresh, sync, credentials) with the existing
   `409` provider-not-available error (`BankConnectorErrors.providerNotAvailable`). Rename, disable
   and delete SHALL keep working locally; `disable` SHALL skip the provider call and succeed.
6. THE `BankConnectionDto` SHALL expose `providerAvailable` (boolean). The frontend SHALL show a
   "provider disabled" badge on connections whose provider is unavailable and SHALL disable their
   Refresh / Reconnect / Sync actions.
7. WHILE the Salt Edge connector is unavailable, THE `/api/bank-feed/saltedge/callbacks/{type}`
   endpoint SHALL answer `200` with `{"ignored":"provider disabled"}` without changing any state and
   without verifying the signature.
8. THE frontend default provider SHALL be `FINTABLE` when it is available, else the first available
   provider (replaces the Salt Edge preference in `defaultProvider`).
9. NO Salt Edge code, table or row SHALL be removed. Re-enabling `SALTEDGE_ENABLED` SHALL restore the
   previous behaviour with no data migration.

### Requirement 2: Fintable configuration and availability

**User Story:** As an operator, I want Fintable configured from environment variables, so that no
secret is committed and a misconfiguration is visible but not fatal.

#### Acceptance Criteria

1. THE System SHALL read these properties under `foremen.bank-feed.fintable`:

   | Property | Env | Default | Meaning |
   |----------|-----|---------|---------|
   | `enabled` | `FINTABLE_ENABLED` | `false` | Provider flag |
   | `base-url` | `FINTABLE_BASE_URL` | `https://fintable.io/api/v2` | API base |
   | `allow-insecure-base-url` | `FINTABLE_ALLOW_INSECURE_BASE_URL` | `false` | tests only: accept an `http://` base URL (fake server) |
   | `api-token` | `FINTABLE_API_TOKEN` | blank | PAT; passed in the environment: on the VM written by `vm-startup.sh` from Google Secret Manager, locally from `.secrets/fintable.env` |
   | `token-expires-on` | `FINTABLE_TOKEN_EXPIRES_ON` | blank | PAT expiry date `YYYY-MM-DD` |
   | `workspace-id` | `FINTABLE_WORKSPACE_ID` | blank | optional `ws_…`; blank = token default |
   | `http-timeout` | `FINTABLE_HTTP_TIMEOUT` | `30s` | per request |
   | `country-codes` | `FINTABLE_COUNTRY_CODES` | `PL` | catalogue country preference |
   | `provider-preference` | `FINTABLE_PROVIDER_PREFERENCE` | `ENABLEBANKING,NORDIGEN` | Backing_Provider order when a bank offers several slugs |
   | `popular-banks` | `FINTABLE_POPULAR_BANKS` | `PKO,mBank,ING,Pekao,Santander,Millennium,Alior` | queries behind the empty-search suggestions |
   | `sync-wait` | `FINTABLE_SYNC_WAIT` | `10m` | maximum wait for a running Fintable sync before a pull |
   | `sync-poll-interval` | `FINTABLE_SYNC_POLL_INTERVAL` | `15s` | polling step while waiting |
   | `manual-sync-interval` | `FINTABLE_MANUAL_SYNC_INTERVAL` | `12h` | local spacing of manual refreshes (Personal: 2/day; set `1h` on Business) |
   | `cursor-overlap` | `FINTABLE_CURSOR_OVERLAP` | `1h` | subtracted from the stored `updated_at` cursor |
   | `token-warn-days` | `FINTABLE_TOKEN_WARN_DAYS` | `30` | first token-expiry notification threshold |

2. THE Fintable connector SHALL be available only when `enabled = true`, `api-token` is non-blank,
   `base-url` is an absolute HTTPS URL (HTTP only when
   `allow-insecure-base-url = true`), and `FOREMEN_PUBLIC_BASE_URL` is configured (the return URL is
   built from it). Otherwise it SHALL report unavailable and SHALL log the failing property **names**
   (never values) at ERROR once at start-up.
3. THE application SHALL read the PAT only from the `FINTABLE_API_TOKEN` environment variable and SHALL
   have no cloud secret-store dependency. On the dev VM `infra/dev/vm-startup.sh` SHALL read it from
   **Google Secret Manager** (secret `foremen-dev-fintable-api-token`, expiry in
   `foremen-dev-fintable-token-expires-on`; names published to the VM as metadata in
   `infra/dev/terraform/vm.tf`) into `/opt/foremen/.env`, and SHALL set `FINTABLE_ENABLED=true` only when
   the token resolves to a non-empty value. A rotated token takes effect when the startup script is
   re-run and the backend restarts.
4. THE PAT SHALL never be logged, returned by any API, stored in the database, or included in an
   exception message or audit snapshot. The real value lives in Secret Manager (deployed) or
   `.secrets/fintable.env` (git-ignored, mode `600`, local development).
5. THE System SHALL wire the new variables in `docker-compose.yml` (`${VAR:-}` form), document them in
   `.env.example` (with a pointer to `.secrets/fintable.env` and the Secret Manager secret names),
   and add the properties to `application.yml` with the defaults above.
6. THE connector's `credentialFields()` SHALL return an empty list (the token is global, not
   per-connection); `BankConnectionSecretService.assertUsable` SHALL therefore not require
   `FOREMEN_BANK_SECRETS_KEY` for Fintable.

### Requirement 3: Fintable HTTP client and error mapping

**User Story:** As a developer, I want one small, well-tested HTTP layer, so that every Fintable
failure becomes a classified `ConnectorError`.

#### Acceptance Criteria

1. THE `FintableClient` SHALL call the Fintable_API with `Authorization: Bearer <token>`,
   `Accept: application/json`, the configured timeout, and `workspace_id` as a query parameter when
   configured. It SHALL use the same HTTP stack (`RestClient`) as `SaltEdgeClient`.
2. THE client SHALL map responses to `ConnectorError` classes:

   | Response | Class | Message key |
   |----------|-------|-------------|
   | `401` | `CONFIG` | `error.bank.fintable.tokenInvalid` |
   | `403` on sync / link ("no plan", free account) | `CONFIG` | `error.bank.fintable.noPlan` |
   | `403` other | `CONFIG` | `error.bank.fintable.forbidden` |
   | `404` on a connection / account | `REAUTH` | `error.bank.provider.reauth` |
   | `422` on `redirect_url` (return hosts) | `CONFIG` | `error.bank.fintable.returnHostNotAllowed` |
   | `422` connection limit / plan headroom | `LIMIT` | `error.bank.fintable.connectionLimit` |
   | `422` other | `PROVIDER` | `error.bank.provider.unavailable` |
   | `429` | `LIMIT` | `error.bank.fintable.rateLimited` (parameter = `Retry-After` seconds) |
   | `5xx`, timeout, I/O | `TRANSIENT` | `error.bank.provider.transient` |

3. THE provider error `type` and `message` from the response envelope SHALL be kept as the
   `ConnectorError` provider code / message (truncated), never the request headers or body.
4. THE client SHALL honour `Retry-After` on `429` and SHALL NOT retry a `429` itself; retry of
   `TRANSIENT` errors stays with the orchestrator (existing behaviour).
5. THE client SHALL never log the `Authorization` header, and SHALL log at most the method, path
   (without query values that are ids of users) and status.

### Requirement 4: Bank catalogue (search-only)

**User Story:** As a FINANCIER, I want to search for my bank when adding a connection, so that I pick
the right connection option.

#### Acceptance Criteria

1. THE `BankFeedConnector` SPI SHALL gain `default boolean catalogueSearchOnly()` (`false`); the
   Fintable connector SHALL return `true` and SHALL implement
   `searchBanks(String countryCode, String query)`.
2. WHEN `q` has at least 3 characters, THE connector SHALL call the public
   `GET /institutions?q=…&country=…&supported=1` (no token) and map each `connections[]` entry of
   each returned group to one `ProviderBank` row: `code` = connection slug, `name` = connection name,
   `homeUrl` from the group domain, `testBank` = `is_test_connection`, `regulated` = backing provider
   is `NORDIGEN` or `ENABLEBANKING`, `accountTypes` derived from the slug / name (`business`,
   `corporate`, `biznes` → `business`; `retail`, `personal` → `personal`; `business-and-personal` →
   both), `mode = "api"`. Defunct institutions SHALL be dropped.
3. WHEN `q` is blank, THE connector SHALL return the popular banks: the results of the configured
   `popular-banks` queries, merged and de-duplicated by slug.
4. WHEN `q` has 1–2 characters, THE endpoint SHALL answer `400 error.bank.fintable.searchTooShort`.
5. WHEN a bank group offers several connections, THE rows SHALL be ordered by the configured
   `provider-preference`, then by name; the backing provider SHALL be exposed in a new nullable
   `ProviderBank.via` field.
6. THE results SHALL be cached for 1 hour per `(country, q)`; the cache SHALL respect the public
   endpoint limit (60 requests per minute per IP) — a burst above it SHALL serve stale cache or
   answer `429 error.bank.fintable.rateLimited`.
7. THE `SupportedBanksService` SHALL route search-only providers through `searchBanks` and SHALL skip
   the full-catalogue cache and the Salt Edge `emptyReason` special-case for them
   (`NO_BANKS` only when the query returned nothing).
8. THE Supported banks tab SHALL work for Fintable as a search box (min 3 characters) with the popular
   banks as the initial content.

### Requirement 5: Connect, complete and reconnect

**User Story:** As a FINANCIER, I want to connect a bank through Fintable and come back to Foremen,
so that the connection becomes active and the first sync runs.

#### Acceptance Criteria

1. WHEN a user starts "Add bank" with provider `FINTABLE` and a bank slug, THE connector SHALL call
   `POST /connections/link` with `{institution: <slug>, redirect_url: <return URL>}` and return the
   single-use `url` and `expires_at` (30 minutes) as `AuthorizationStart`.
2. THE return URL SHALL be `PublicBaseUrl.returnUrl(connectionId)`
   (`{base}/bank/connections/return?connectionId={id}`). WHEN that URL is not `https`, THE connector
   SHALL omit `redirect_url` (Fintable accepts only HTTPS) and the flow SHALL use the manual return
   of criterion 6.
3. THE System SHALL NOT call `POST /connections/link` more than once per minute: a local guard SHALL
   answer `429 error.bank.fintable.rateLimited` with the remaining seconds before calling Fintable, and
   a Fintable `429` SHALL be mapped the same way.
4. WHEN the user returns to `/bank/connections/return`, THE existing return page SHALL forward all
   query parameters to `POST /api/bank/connections/{id}/complete`. THE connector's
   `completeAuthorization` SHALL read `connection_id` and `status` from them.
5. WHEN `status = connected` and `connection_id` is present, THE connector SHALL return the state
   read from `GET /connections/{connection_id}`; the connection becomes `ACTIVE` and a `CONNECT`
   sync run starts (existing R1.4). WHEN `status = error`, it SHALL return `PENDING_AUTH` so the user
   can retry.
6. WHEN `connection_id` is absent (HTTP return URL, or the user closed the tab), THE connector SHALL
   list `GET /connections` and, through the `BoundConnectionIds` port, find Fintable connections not
   bound to any Foremen connection. IF exactly one such connection exists and was created at or after
   the authorization start, THE connector SHALL bind it; ELSE it SHALL return `PENDING_AUTH`. The
   frontend SHALL offer a "Finish connection" button on a `PENDING_AUTH` Fintable connection that
   calls `/complete` with no parameters.
7. THE System SHALL reject a `connection_id` already bound to another Foremen connection
   (`409 error.bank.connection.alreadyLinked`, existing behaviour) and SHALL validate every id against
   the Fintable_API before storing it (existing R24.5).
8. WHEN a user chooses "Reconnect", THE connector SHALL call `POST /connections/{id}/link` with the
   same `redirect_url` rules and return the URL; completion SHALL re-read the state.
9. WHEN the Fintable_API answers `422` because `redirect_url` is not on the Return_Hosts list, THE
   System SHALL fail with `error.bank.fintable.returnHostNotAllowed` and the runbook SHALL describe how
   to add the host.

### Requirement 6: Connection state mapping

**User Story:** As a FINANCIER, I want the connection status to reflect the Fintable state, so that I
know when action is needed.

#### Acceptance Criteria

1. THE connector's `fetchConnectionState` SHALL read `GET /connections/{id}` and return:
   - `REAUTH_REQUIRED` when `needs_reconnect = true`;
   - `ERROR` when `healthy = false` and `needs_reconnect = false`, or the latest `sync_status.state`
     is `failed`;
   - `ACTIVE` otherwise;
   - `externalConnectionId` = the Fintable id, `bankName` = `institution_name`,
     `consentExpiresAt = null` (Fintable exposes no consent end date),
     `externalConsentId = null`.
2. BECAUSE `consentExpiresAt` is `null`, THE `CONSENT_EXPIRING` status and its notification SHALL NOT
   occur for Fintable connections (existing `ConnectionStatusCalculator` already treats a null expiry
   as "no consent window").
3. WHEN `GET /connections/{id}` answers `404`, THE connector SHALL throw `REAUTH` (connection gone at
   Fintable); the orchestrator maps it to `REAUTH_REQUIRED` (existing R12.4).
4. THE connector SHALL store `status_text` of an unhealthy connection as the provider error message of
   the thrown or returned state, truncated to the existing `lastErrorMessage` width.

### Requirement 7: Accounts and holder

**User Story:** As a FINANCIER, I want all enabled Fintable accounts of a connection listed with
balances and holder, so that the ledger knows which accounts it reads.

#### Acceptance Criteria

1. THE connector's `fetchAccounts` SHALL call `GET /accounts?connection_id=…` (enabled accounts only)
   and map: `externalId` = `id` (`acc_…`), `name` = `display_name` or `name`, `nature` from `type`
   (`depository / …` → `account`, `credit / …` → `card`, `… savings|money market|cd` → `savings`,
   other → `account`), `iban`, `currency`, `balance`, `availableBalance` (`balance_available`),
   `holderName` = `holder_name`, `balanceAt` = the connection's `last_successful_update`.
2. THE connector's `fetchHolderInfo` SHALL return the distinct non-blank `holder_name` values of the
   connection's accounts as `names` and an empty `taxIds`; it SHALL return empty when none is present.
3. AN account disabled or removed at Fintable SHALL disappear from `fetchAccounts`; the ledger keeps
   its stored account and transactions (existing behaviour for vanished accounts).
4. THE System SHALL use Fintable's own `id` (not `ext_id`) as the stable key of accounts and
   transactions.

### Requirement 8: Transactions

**User Story:** As a FINANCIER, I want transactions imported incrementally and without duplicates, so
that the ledger stays correct across syncs.

#### Acceptance Criteria

1. THE connector's `fetchTransactions(connection, account, cursor)` SHALL call
   `GET /accounts/{id}/transactions?order=updated&updated_since=…&limit=500&include=raw` and follow
   Fintable's `next_cursor` between calls.
2. THE cursor passed between pages SHALL be encoded as `p:<since>|<fintable cursor>`; the Resume_Cursor
   stored after a sync SHALL be encoded as `u:<max updated_at>`. WHEN no Resume_Cursor exists, the first
   call SHALL read from the beginning (`updated_since` omitted); WHEN one exists, the call SHALL use
   `updated_since = stored value − cursor-overlap`.
3. THE connector SHALL implement the new SPI method `resumeCursor(previous, pages)` returning
   `u:<max updated_at over all posted rows>` (never moving backwards; the previous value when no row
   arrived). The Salt Edge max-id logic SHALL move into `SaltEdgeConnector.resumeCursor`, and
   `BankSyncOrchestrator.resumeCursor` SHALL delegate to the connector (default `null`).
4. THE connector SHALL map each transaction to `NormalizedTransaction`: `externalId` = `id`
   (`tx_…`), `status = POSTED`, `amount` = `amount` as `BigDecimal` (no float), `currency`,
   `operationDate` = `auth_date` if present else `date`, `bookingDate` = `date`, `title` = `description`
   (fallback `external_memo`, then `merchant`), `providerCategory` = `category.name`,
   `providerDuplicate = false`, `payload` = the full Fintable object including `raw` and
   `updated_at`.
5. THE connector SHALL fill `counterpartyName` and `counterpartyIbanRaw` from `raw` by direction:
   an outgoing row (amount < 0) uses the creditor / payee, an incoming row the debtor / payer. It
   SHALL look up a fixed, documented list of candidate paths for each backing provider (GoCardless:
   `creditorName`, `creditorAccount.iban`, `debtorName`, `debtorAccount.iban`; Enable Banking:
   `creditor.name`, `creditor_account.iban`, `debtor.name`, `debtor_account.iban`; Plaid:
   `counterparties[0].name`), tolerate missing or differently shaped `raw`, and fall back to
   `merchant`, then `account_owner`, then `null`. A malformed row SHALL be counted in
   `skippedMalformed` and SHALL NOT fail the page.
6. THE connector SHALL NOT read the pending snapshot: `TransactionPage.pending` SHALL always be empty.
7. DELETIONS at Fintable are not visible to `updated_since` polling and SHALL NOT be propagated: the
   ledger never deletes a row because Fintable stopped returning it.
8. THE connector SHALL stop with a `TRANSIENT` error after 10 000 pages (orchestrator guard,
   existing).

### Requirement 9: Sync orchestration adaptations

**User Story:** As a FINANCIER, I want a sync to read fresh data, so that "Refresh now" shows what the
bank reported, not what Fintable had yesterday.

#### Acceptance Criteria

1. THE SPI SHALL gain `default void awaitProviderSync(BankConnectionRef ref, Duration maxWait)`
   (no-op). THE orchestrator SHALL call it after `fetchConnectionState` and before `fetchAccounts` on
   every run, with `foremen.bank-feed.fintable.sync-wait` for Fintable.
2. THE Fintable implementation SHALL read `GET /sync`; WHEN the connection's `sync_status.state` is
   `queued`, `executing` or `retrying`, OR a refresh was requested by this application and
   `finished_at` is older than that request, it SHALL poll every `sync-poll-interval` until the state
   is `finished` / `failed` or `maxWait` passes. A timeout SHALL log a WARN and continue (it SHALL NOT
   fail the run). A `failed` state SHALL be left to `fetchConnectionState` to report.
3. THE scheduled pull SHALL NOT call `POST /sync` (existing R9.6). Fintable's own daily sync is the
   source of fresh data; the runbook SHALL set Fintable's daily sync time 30–60 minutes before the
   Foremen cron (`0 30 6 * * *`, Europe/Warsaw) through `PATCH /sync/schedule`.
4. WHEN a user chooses "Refresh now", THE connector's `requestRefresh(INTERACTIVE)` SHALL:
   - return `RefreshResult.notYet(nextAt)` without calling Fintable when the local spacing
     (`manual-sync-interval` since the last refresh by this application) has not passed;
   - otherwise call `POST /sync/{connection_id}`; `started` and `already_syncing` SHALL both return
     `RefreshResult.started(now + manual-sync-interval, null)`;
   - map `429` to `notYet(now + Retry-After)` and `403` to `ConnectorError(CONFIG, noPlan)`.
5. `requestRefresh(BACKGROUND)` SHALL return `notYet` without calling Fintable.
6. THE existing `409 error.bank.refresh.notYetPossible` response SHALL carry the earliest time; the
   frontend SHALL show it as "Fintable limits manual refreshes; next possible at <time>".
7. THE `FintableRefreshTracker` (in-memory, per connection id) is best-effort: after an application
   restart `awaitProviderSync` falls back to the state check of criterion 2.

### Requirement 10: Disconnect

**User Story:** As an ADMIN, I want disabling a connection to free the Fintable connection slot, with
a clear warning that Fintable data is purged.

#### Acceptance Criteria

1. THE connector's `disable` SHALL call `DELETE /connections/{id}`; `404` SHALL be treated as success.
   A `503` (connection locked mid-sync) SHALL be a `TRANSIENT` error and the Foremen connection SHALL
   stay unchanged so the user can retry.
2. THE Foremen ledger rows (accounts, transactions, links) SHALL be kept (existing R1.7).
3. THE frontend SHALL show a confirmation for disabling a Fintable connection that states that the
   connection and its data are removed at Fintable and the bank must be reconnected to resume.

### Requirement 11: Frontend

**User Story:** As a FINANCIER, I want a clear Fintable flow in the Bank page.

#### Acceptance Criteria

1. THE `FeedProvider` type and `FEED_PROVIDERS` SHALL include `FINTABLE`; `defaultProvider` SHALL
   prefer it (Requirement 1.8).
2. THE "Add bank" dialog for `FINTABLE` SHALL show a search box (min 3 characters, debounced) with the
   popular banks as initial content; each result row SHALL show the bank name, the account types and
   the backing provider; picking a row selects its slug.
3. THE dialog and the Connections tab SHALL show the Fintable attribution: a visible, working link to
   <https://fintable.io> with the text from `bank.fintable.poweredBy` (Fintable API terms).
4. THE dialog SHALL state that the user is redirected to Fintable to log in to the bank and that the
   link is valid for 30 minutes; WHEN `redirect_url` was omitted it SHALL show the "come back and
   click Finish connection" instruction.
5. A `PENDING_AUTH` Fintable connection SHALL show "Finish connection" (calls `/complete` with no
   params) next to "Continue authorization".
6. THE link cooldown (`error.bank.fintable.rateLimited`) SHALL be shown with the remaining seconds.
7. EVERY new user-facing string SHALL have PL and RU values (Requirement 13); the locale-parity test
   SHALL cover the new keys.

### Requirement 12: Notifications

**User Story:** As an ADMIN or FINANCIER, I want to be told before the Fintable token expires.

#### Acceptance Criteria

1. THE System SHALL emit notifications only through the generic `NotificationService`
   (`BankNotificationEmitter`, `AFTER_COMMIT`, best-effort, recipients by company role, active users
   only) and SHALL NOT reimplement delivery.
2. THE System SHALL reuse unchanged, for Fintable connections: `notification.bank.reauthRequired`,
   `notification.bank.syncFailed`, `notification.bank.balanceGap`,
   `notification.bank.transactionsToReview` (FOR-07-04 R22.2). `notification.bank.consentExpiring`
   SHALL NOT fire for Fintable (Requirement 6.2).
3. THE System SHALL add:

   | Trigger | Recipients | Type key | Title (PL / RU) | Deep-link |
   |---------|------------|----------|-----------------|-----------|
   | Fintable is enabled and `token-expires-on` is within `token-warn-days` (default 30), again at 7 days, and once when expired | ADMIN, FINANCIER | `notification.bank.fintableTokenExpiring` | `Token API Fintable wkrótce wygasa` / `Токен API Fintable скоро истекает` | `/bank/connections` |

   `body = null` (title localized from the type key, as the other bank notifications).
4. THE daily check SHALL run in the existing consent sweep schedule
   (`foremen.bank-feed.consent.sweep-cron`). Each threshold (`30`, `7`, `0`) SHALL be sent at most
   once per `token-expires-on` value, tracked by a persisted marker (design §Persistence). Changing
   `token-expires-on` (a new token) SHALL reset the markers.
5. WHEN `token-expires-on` is blank THE check SHALL do nothing and SHALL log one WARN at start-up.

### Requirement 13: Internationalization

#### Acceptance Criteria

1. THE System SHALL add no entity field that needs RU / PL variants (no new managed entity, no new
   dictionary).
2. BACKEND message keys `error.bank.fintable.*` SHALL be added to `messages.properties` (PL, default)
   and `messages_ru.properties` (RU) with identical key sets (design §Messages).
3. FRONTEND keys SHALL be added to `locales/pl.json` and `locales/ru.json` with identical key sets
   (design §Frontend i18n), and the locale-parity test SHALL be extended.
4. THE notification type `notification.bank.fintableTokenExpiring` SHALL have PL and RU titles.

### Requirement 14: Security, compliance and data protection

#### Acceptance Criteria

1. THE System SHALL use Fintable only for the company's own accounts (first-party use). The runbook
   SHALL state that connecting accounts of other persons is forbidden by the Fintable API terms.
2. THE attribution of Requirement 11.3 SHALL be present on the connection screen.
3. THE PAT SHALL be stored only in Google Secret Manager (deployed environments) and in
   `.secrets/fintable.env` for local development, which SHALL stay git-ignored (`.secrets/`) with mode `600`.
4. THE `payload` stored per transaction (raw Fintable object) SHALL keep following the existing bank
   ledger retention and access rules (ABAC `BANK_LEDGER`); no new endpoint SHALL return it.
5. THE runbook SHALL record that bank data is processed by Fintable (and its backing providers) as a
   processor, so the data-processing agreement and privacy documentation must be updated by the
   company before production use.
6. THE System SHALL NOT call any Fintable endpoint that changes categorizer, rules, integrations,
   workspace membership or accounts (`PATCH /accounts`, `PATCH /transactions*`, `/categorizer/*`,
   `/integrations`). Allowed calls: `GET /me`, `GET /connections`, `GET /connections/{id}`,
   `POST /connections/link`, `POST /connections/{id}/link`, `DELETE /connections/{id}`,
   `GET /accounts`, `GET /accounts/{id}/transactions`, `GET /sync`, `POST /sync/{id}`, public
   `GET /institutions`.

### Requirement 15: Persistence and access control

#### Acceptance Criteria

1. THE System SHALL add changeset `NNN-fintable-provider.xml` (next free number after `253`, checked at
   implementation time) that (a) replaces `ck_bank_connections_provider` with
   `CHECK (provider IN ('SALT_EDGE','STUB','FINTABLE'))` and (b) creates
   `bank_feed_provider_state` (design §Persistence). It SHALL be idempotent (`preConditions`
   `onFail="MARK_RAN"`) and registered last in `changelog.xml`.
2. `FeedProvider` SHALL gain `FINTABLE`. `provider_mode` SHALL be `null` for Fintable connections.
3. THE System SHALL add no `resources` row and no ABAC grant: no new managed entity or controller is
   introduced; existing `BANK_CONNECTIONS` / `BANK_LEDGER` guards cover every endpoint used. The
   entity-creation checklist (resource seed, `@PermissionResource`, `getProjectIdPath`, dashboard
   dataset) is therefore not applicable; `bank_feed_provider_state` is an internal technical table.

### Requirement 16: Testing

#### Acceptance Criteria

1. THE System SHALL provide unit tests for: `FintablePropertiesTest` (availability matrix),
   `FintableClientTest` (headers, status → `ConnectorError` table, `Retry-After`, no token in logs),
   `FintableMapperTest` + a jqwik property test (money exactness, sign, counterparty direction,
   malformed rows), `FintableConnectorTest` (catalogue, authorize / complete / reconnect incl. the
   binding fallback, state mapping, accounts / holder, cursor encoding and `resumeCursor`, refresh
   spacing, `awaitProviderSync`, disable), `FintableTokenExpiryCheckTest`.
2. THE System SHALL provide recorded fixtures under `src/test/resources/fintable/` (sandbox Plaid
   fixtures from the 2026-11-18 run, hand-built GoCardless and Enable Banking `raw` fixtures, replaced
   by real captures in task 8.2).
3. THE System SHALL provide integration tests: start-up with both flags off / on (no failure), the
   scheduler skipping an unavailable provider, an orchestrator run against a fake Fintable HTTP server
   (accounts + two pages + resume cursor + re-run idempotency), the Salt Edge callback `ignored`
   answer, and the token-expiry notification with its markers.
4. THE existing Salt Edge test classes SHALL keep passing after the SPI generalisation.
5. THE System SHALL author `test-cases.md` (Russian) per `.kiro/steering/test-cases.md`.
6. Backend tests SHALL be run per `.kiro/steering/test-execution-rules.md` (affected classes only).

### Requirement 17: Non-goals

THE System SHALL NOT: remove Salt Edge; implement a direct Enable Banking or GoCardless connector;
read Fintable pending snapshots; propagate Fintable-side deletions; use the Fintable MCP server or
OAuth; manage Fintable categories, rules, workspaces or sync schedule from code (the runbook
documents the one-time `PATCH /sync/schedule`); or show Fintable data of other users.
