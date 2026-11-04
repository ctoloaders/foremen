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
