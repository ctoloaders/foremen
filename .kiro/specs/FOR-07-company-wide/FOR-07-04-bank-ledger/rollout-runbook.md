# FOR-07-04 Bank Ledger: Environment Rollout Runbook (Task 11)

Covers Requirements 8.2, 10.1, 12.5, 12.6, 19.9. Order: **dev first**, then any other environment.
Repo prep is already done (see "What the repo already provides"). Everything below is manual.

> Never commit, paste into chat, or write into any file the VALUES of the secrets below.
> This document lists names only.

## What the repo already provides

- `infra/dev/docker-compose.dev.yml` (backend) passes through, with safe defaults:
  `FOREMEN_BANK_STUB_ENABLED` (default `false`, required because the `docker` profile defaults STUB to `true`),
  `FOREMEN_PUBLIC_BASE_URL` (default `https://dev.foremen.eu`), `FOREMEN_BANK_SECRETS_KEY`,
  `SALTEDGE_ENABLED` (`false`), `SALTEDGE_MODE` (`CLIENT`), `SALTEDGE_APP_ID`, `SALTEDGE_SECRET`,
  `SALTEDGE_LEAD_EMAIL`, `SALTEDGE_PRIVATE_KEY_PATH` (all empty by default).
- `infra/dev/vm-startup.sh` reads the secrets by name from Secret Manager at boot and writes them to
  `/opt/foremen/.env`. `SALTEDGE_ENABLED` is set to `true` automatically only when both the app id and secret resolve.
- `infra/dev/terraform/vm.tf` carries the metadata pointers (secret NAMES) listed below.

## 1. Secrets and settings (names only)

Secret Manager secrets (dev), created by hand in the project used by the dev VM:

| Secret Manager name | Becomes env var | Notes |
|---|---|---|
| `foremen-dev-bank-secrets-key` | `FOREMEN_BANK_SECRETS_KEY` | AES-256-GCM key, see format below |
| `foremen-dev-saltedge-app-id` | `SALTEDGE_APP_ID` | From the Salt Edge dashboard |
| `foremen-dev-saltedge-secret` | `SALTEDGE_SECRET` | From the Salt Edge dashboard |
| `foremen-dev-saltedge-lead-email` | `SALTEDGE_LEAD_EMAIL` | Needed for PARTNER mode only |
| `foremen-dev-saltedge-mode` | `SALTEDGE_MODE` | Optional. `CLIENT` (default) or `PARTNER`; other values are ignored |

Non-secret values (compose defaults, override in `/opt/foremen/.env` only if needed):
`FOREMEN_PUBLIC_BASE_URL=https://dev.foremen.eu`, `FOREMEN_BANK_STUB_ENABLED=false`.

### `FOREMEN_BANK_SECRETS_KEY` format

`BankSecretCipher` expects **standard base64 (java `Base64.getDecoder()`) of exactly 32 random bytes**
(AES-256-GCM, key-version 1). Not URL-safe base64, and not a passphrase. A blank or malformed key does not
break startup, but per-connection bank secrets become unavailable. Generate it locally and pipe it straight
into Secret Manager without printing or saving it:

```
openssl rand -base64 32 | tr -d '\n' | gcloud secrets create foremen-dev-bank-secrets-key --data-file=-
# rotate later with: ... | gcloud secrets versions add foremen-dev-bank-secrets-key --data-file=-
```

Do not rotate casually: data encrypted with the old key becomes unreadable.

Create the others the same way (pipe via stdin, never as command-line arguments):
`printf '%s' "<value>" | gcloud secrets create <name> --data-file=-`

Grant the VM service account `roles/secretmanager.secretAccessor` on each new secret (as for the existing ones).

## 2. Instance metadata pointers

`vm-startup.sh` finds the secrets through VM metadata keys. Apply `infra/dev/terraform/vm.tf`, or set them by hand:
`secret-bank-secrets-key`, `secret-saltedge-app-id`, `secret-saltedge-secret`, `secret-saltedge-lead-email`,
`secret-saltedge-mode` (each value is the Secret Manager name from the table above).

Note: a terraform apply replaces the metadata map. Check `terraform plan` first so hand-set keys
(e.g. `secret-bot-identity`, `secret-bot-site-key`, `secret-google-oauth-client`) are not dropped.

## 3. Deploy

1. Make sure the backend image containing FOR-07-04 is built and pushed (`40-build-push.sh` / CI).
2. Regenerate `/opt/foremen/.env` so it picks up the secrets: re-run the startup script on the VM
   (`sudo google_metadata_script_runner startup`, or reboot the VM). `55-redeploy.sh` alone does NOT rewrite `.env`.
3. Run `infra/dev/55-redeploy.sh` (or let CI deploy) so the new compose and the Liquibase changelog (changesets 201-206) are applied.
4. On the VM check names only, never values:
   `grep -E '^(SALTEDGE_(ENABLED|MODE)|FOREMEN_PUBLIC_BASE_URL|FOREMEN_BANK_STUB_ENABLED)=' /opt/foremen/.env`
   Expect `SALTEDGE_ENABLED=true`, `SALTEDGE_MODE=CLIENT`, and STUB `false`.
5. Check that the backend is healthy and `GET https://dev.foremen.eu/api/bank/providers` (as ADMIN) lists `SALT_EDGE` and not `STUB`.

## 4. Salt Edge dashboard

Do these in the Salt Edge dashboard (account created with the client app id/secret above):

1. Fill in **Application info** and **Company info** (required before Live/Partner access is considered).
2. Register the four callback URLs for dev:
   - `https://dev.foremen.eu/api/bank-feed/saltedge/callbacks/success`
   - `https://dev.foremen.eu/api/bank-feed/saltedge/callbacks/fail`
   - `https://dev.foremen.eu/api/bank-feed/saltedge/callbacks/notify`
   - `https://dev.foremen.eu/api/bank-feed/saltedge/callbacks/destroy`

   For another environment use `{FOREMEN_PUBLIC_BASE_URL}/api/bank-feed/saltedge/callbacks/{success,fail,notify,destroy}`.
   Callbacks must be reachable from the internet over HTTPS. The backend verifies the signature (bad signature gives `401`).
3. Free fake-connection slots: remove stale fake connections (fake providers count against the account limit),
   e.g. with `scripts/saltedge-fake-connections.sh status` (see the repo script) or in the dashboard.
4. Open the **Supported banks** tab and note which providers are available for Poland (see section 6).
5. Click **Request Test Access** (opens access to real banks in test mode).
6. Request **Partner mode** (see section 7).

## 5. Verification with `fakebank_simple_xf`

Follow **Part C of `test-cases.md`** (cases C-1 to C-10) in this folder. Short form:

1. In the UI: Bank, Add bank, Salt Edge, "Fake Bank Simple" (`fakebank_simple_xf`), "Go to bank".
2. In the Salt Edge widget log in with `username{run-id}` / `secret`.
3. You are returned to `https://dev.foremen.eu/bank/connections/return?...`; the connection becomes Active.
4. Check backend logs for the `success` callback with a verified signature and a sync run.
5. Accounts and bank transactions are loaded and the holder badge is set; counterparty fields are filled.
6. Sync again: no duplicates. Disconnect at the end (teardown) so no fake-connection slot stays used.

Record results in the MD report template at the end of `test-cases.md`.

## 6. Supported banks tab: expectations

- The list comes from the Salt Edge API, not from a hardcoded list.
- While the account is in Pending status, expect only fake banks, or the message that the provider does not yet open Polish banks.
- In CLIENT mode some banks show "Need your own keys" and Connect is unavailable for them. That is expected, not a bug.

## 7. Partner mode request

1. In the dashboard send the Partner mode request (and "Request Test Access" if not done).
2. Wait for Salt Edge to grant it. Until then keep `SALTEDGE_MODE=CLIENT`.
3. After the grant: set `foremen-dev-saltedge-mode` to `PARTNER` (new version) and create/update
   `foremen-dev-saltedge-lead-email` (the lead identity used by the backend for PARTNER). Update `SALTEDGE_APP_ID` /
   `SALTEDGE_SECRET` if Salt Edge issues new Partner credentials.
4. Re-run the startup script and redeploy (section 3), then repeat section 5 in PARTNER mode.
   C-5 expects the lead (`SALTEDGE_LEAD_EMAIL`) to be used instead of the customer identity.

## 8. Optional: RSA request signing

1. Generate an RSA key pair locally; upload only the **public** key in the Salt Edge dashboard.
2. Put the private key on the VM (outside git, mode 600) and mount it read-only into the backend container
   (a compose volume is not added by default).
3. Set `SALTEDGE_PRIVATE_KEY_PATH` to the path **inside the container**. When set, every request is signed.
4. Restart the backend and repeat the quick check from section 5.

## 9. Rollback

- Fast switch-off: remove or blank `foremen-dev-saltedge-app-id`/`-secret` (or set `SALTEDGE_ENABLED=false` in
  `/opt/foremen/.env`), re-run the startup script / `docker compose up -d backend`. Note that on the next boot
  `vm-startup.sh` re-enables Salt Edge if both secrets still resolve, so a manual `.env` edit alone is temporary.
- Expected after switch-off (REG-06): app starts, `GET /api/bank/providers` returns `[]`, callbacks return `401`,
  `/api/bank/stub/**` returns `404`. Existing ledger data is kept.
- Optionally remove the Salt Edge connection (Disconnect in the UI) and the callbacks in the dashboard.
- Never turn `FOREMEN_BANK_STUB_ENABLED` on outside local Docker.

## 10. Completion checklist

- [ ] Secrets created in Secret Manager (5 names above) and accessible to the VM service account
- [ ] Metadata pointers applied; `.env` regenerated; backend deployed
- [ ] `/api/bank/providers` shows `SALT_EDGE`, no `STUB`
- [ ] Application info and Company info filled in the dashboard
- [ ] Four callback URLs registered
- [ ] Fake-connection slots freed
- [ ] `test-cases.md` Part C (C-1 to C-10) passed in CLIENT mode and the report is saved
- [ ] Supported banks tab checked; Request Test Access sent
- [ ] Partner mode requested (and, once granted, Part C repeated in PARTNER mode)

---

# 11. Fintable bank feed (FOR-07-04a)

Covers Requirements 9.3, 14.1, 14.5 of FOR-07-04a. Fintable replaces Salt Edge as the active bank feed
provider. Spec: `.kiro/specs/FOR-07-company-wide/FOR-07-04a-fintable-integration/`.
The same rule applies as above: names only, never values, in this file, in chat, in logs or in commits.

## 11.1 Personal access token (PAT)

- A token is created in the Fintable account (one workspace per token, owner role, read + write scope).
  It is valid for **1 year**. The expiry date is not returned by the API, so it is recorded by hand.
- Store it in `.secrets/fintable.env` (git-ignored, mode 600) with two keys:
  `FINTABLE_API_TOKEN` and `FINTABLE_TOKEN_EXPIRES_ON` (ISO date, e.g. `2027-11-18`).
  Do not open or print this file in shared sessions; check key names only, e.g.
  `grep -oE '^FINTABLE_[A-Z_]+' .secrets/fintable.env`.
- Environments other than local read the same names from Secret Manager / the VM environment
  (`/opt/foremen/.env`). Create the secret by piping from stdin, as in section 1:
  `printf '%s' "<value>" | gcloud secrets create <name> --data-file=-`.
- The backend warns ADMIN and FINANCIER in the app at 30, 7 and 0 days before `FINTABLE_TOKEN_EXPIRES_ON`
  (`FINTABLE_TOKEN_WARN_DAYS`, default 30). If the date is blank, the backend logs one WARN at start-up and sends no warnings.
  **Always set `FINTABLE_TOKEN_EXPIRES_ON` when the token is issued.**

### Rotation

1. Create a new token in Fintable (before the old one expires).
2. Add a new Secret Manager version / update `.secrets/fintable.env` with the new `FINTABLE_API_TOKEN`
   and the new `FINTABLE_TOKEN_EXPIRES_ON`.
3. Re-run the startup script and redeploy (section 3). The expiry markers reset when the date changes.
4. Check `GET /api/bank/providers` (ADMIN) lists `FINTABLE` as available, and run one manual refresh.
5. Revoke the old token in Fintable.

## 11.2 Return_Hosts (per environment)

Fintable returns the user to Foremen only if the host of `redirect_url` is on the token's **Return_Hosts**
list; otherwise `POST /connections/link` fails with **422**.

| Environment | `FOREMEN_PUBLIC_BASE_URL` | Return_Hosts entry |
|---|---|---|
| local | `http://localhost:3000` | none. HTTP means no `redirect_url` is sent, and the user returns manually |
| dev | `https://dev.foremen.eu` | `dev.foremen.eu` |
| other | the environment's HTTPS URL | its host name |

Add the host in Fintable before enabling the provider in that environment. Without HTTPS or a public base URL
the provider is reported unavailable.

## 11.3 Plan choice and sync limits

Real bank syncs need a paid plan. The trial (`tier: trial`, 3 connections) is enough only for the sandbox.

| Plan | Sync limit | Setting |
|---|---|---|
| Personal | 2 syncs per day | keep `FINTABLE_MANUAL_SYNC_INTERVAL=12h` (default). The scheduled daily sync uses one of the two, so "Refresh now" is effectively once per day |
| Business | 1 sync per hour | lower `FINTABLE_MANUAL_SYNC_INTERVAL` (e.g. `1h`) |

Choose by how often users need "Refresh now". Foremen enforces the interval locally; a refresh before
`nextRefreshPossibleAt` answers `409 error.bank.refresh.notYetPossible` and does not call Fintable.
Minting a link is limited to one per minute (429 otherwise); the backend also guards this locally.

## 11.4 One-time sync schedule

The default Fintable daily sync is `12:29 UTC`. Set it once per workspace so data is ready before the
Foremen pull (`06:30 Europe/Warsaw`, `foremen.bank-feed.sync.cron`, unchanged):

```
PATCH {FINTABLE_BASE_URL}/sync/schedule      (Authorization: Bearer <token>; do not echo the token)
body: time 05:30, timezone Europe/Warsaw
```

Take the exact body fields from the Fintable API reference. Verify with the matching `GET`, and
keep the command out of shell history if the token is passed inline (read it from the env file inside the shell).
This is not repeated per deploy; repeat only if the workspace or token changes.

## 11.5 Switching Salt Edge off

1. Set `SALTEDGE_ENABLED=false` and `FINTABLE_ENABLED=true` (and `FOREMEN_PUBLIC_BASE_URL`) in `/opt/foremen/.env`.
   Note: `vm-startup.sh` turns Salt Edge on again if its app id and secret still resolve, so also remove the
   Salt Edge metadata pointers or secrets (section 9).
2. Redeploy. Existing Salt Edge connections stay visible with a "provider disabled" badge, with actions disabled.
3. Expected: the scheduler skips them without a run or notification, Salt Edge callbacks answer
   `200 {"ignored":"provider disabled"}`, and disabling such a connection is local only (no provider call).
4. Existing ledger data is kept. Users reconnect their banks through Fintable.

## 11.6 First-party-only and attribution rules

- Use only for the company's **own** bank accounts (first-party). Do not connect customers' or third parties'
  accounts through the Foremen token: one workspace and one token serve one company.
- Show the "via Fintable" attribution where the UI shows bank connections (Add bank dialog, Connections tab). Do not remove it
  or the `via` provider label in the bank picker.
- Do not resell or expose the feed to other tenants.

## 11.7 Data-processor note

Fintable and the underlying aggregators (Enable Banking, GoCardless/Nordigen, Plaid) process bank data on behalf of the
company. Before production use, record Fintable as a data processor in the processing register, check that a
data processing agreement is in place, and mention it in the privacy notice. The sandbox uses fake data only.
Fintable data is loaded into the ledger; sensitive fields (token, account numbers) must not appear in logs.

## 11.8 Trial-end warning

The current Fintable **trial ends 2026-11-20**. Subscribe to the chosen plan before that date, otherwise real syncs stop.
Rollout order is in tasks 8.1 and 8.2 of the spec: fill `.secrets/fintable.env`, add Return_Hosts, set the
flags, run the schedule PATCH, deploy, verify with the sandbox, then connect one real bank.

## 11.9 Open points found during implementation

1. **`GET /sync` semantics are unconfirmed.** The connector uses it to wait for the provider sync
   (`awaitProviderSync`). It must be checked against the real API whether it only reports the current state (poll)
   or can start a sync (trigger). Known: `POST /sync/{id}` returns `started`, and a second call `already_syncing`
   (not an error). Confirm during task 8.1 and adjust the connector and its tests if it differs.
2. **Secrets come from Google Secret Manager through `vm-startup.sh`, not from the application.** The backend only
   reads `FINTABLE_API_TOKEN` and `FINTABLE_TOKEN_EXPIRES_ON` from the environment. The dev VM script pulls
   `foremen-dev-fintable-api-token` and `foremen-dev-fintable-token-expires-on` into `/opt/foremen/.env` and sets
   `FINTABLE_ENABLED=true` only when the token resolves (same pattern as Salt Edge). There is no AWS dependency.
   Rotation: add a new version of both secrets and re-run the startup script (see 11.1).
3. **`status_text` of unhealthy connections is not stored.** The provider's explanation is not kept, so the UI can show
   the state only. Diagnose through the Fintable dashboard until it is added.
4. Counterparty field paths for real Polish banks are still hand-built candidates; verify them in task 8.2 and record
   the result here.

## 11.10 Fintable completion checklist

- [x] PAT created; `FINTABLE_API_TOKEN` and `FINTABLE_TOKEN_EXPIRES_ON` stored locally and in Secret Manager
      (`foremen-dev-fintable-api-token`, `foremen-dev-fintable-token-expires-on`; expiry 2027-11-17)
- [x] Environment host added to Return_Hosts (dev: `dev.foremen.eu`)
- [x] Plan chosen: Personal, monthly (renews 2026-12-27); `FINTABLE_MANUAL_SYNC_INTERVAL` stays `12h`
- [x] `PATCH /sync/schedule` done (05:30 Europe/Warsaw, next run 2026-11-28 04:30 UTC)
- [ ] Terraform metadata applied (`secret-fintable-*` in `infra/dev/terraform/vm.tf`) and the dev VM startup script re-run
- [ ] One real bank connected and verified (task 8.2)
- [ ] `SALTEDGE_ENABLED=false`, `FINTABLE_ENABLED=true`; `/api/bank/providers` lists `FINTABLE`, not `SALT_EDGE`
- [ ] Sandbox flow verified, then one real bank
- [ ] Open points 11.9 resolved or tracked
