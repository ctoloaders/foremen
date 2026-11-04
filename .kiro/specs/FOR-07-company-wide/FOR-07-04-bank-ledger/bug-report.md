# Bug report: FOR-07-04-bank-ledger

Screen URL: https://dev.foremen.eu/bank/connections

## Round 1 (2026-11-04)

- [ ] 1. The "Add bank" popup does not fit all the text (screenshot)
  - Triage: observed: content of the Add bank dialog overflows / is clipped; expected: every label,
    bank name and capability chip is readable inside the dialog at desktop and mobile widths.
    Layer: frontend. Suspect: `foremen-frontend/src/features/bank/connections/BankPicker.tsx`
    (option row: `flex-1 truncate` name without `min-w-0`, so the name cannot shrink and the
    `SupportedBankCapabilities` chips push the row wider than the `sm:max-w-lg` dialog; long chips
    such as "requires own keys" never wrap) and `AddBankDialog.tsx` (dialog width / long hint).
    Fix: `min-w-0` + 2-line clamp for the name, chips `flex-none` and wrapping under the name on
    narrow widths, dialog `sm:max-w-xl` with `max-h-[90vh] overflow-y-auto`.
  - _Requirements: 19.4, 23.1_
  - _Depends on: none_
- [ ] 2. `POST /api/bank/connections` returns 409 "Provider connection limit reached"
  - Triage: observed: `409 error.bank.provider.connectionLimit` (backend log 14:23:10,
    `code=error.bank.provider.connectionLimit, status=409`; the reported "429" is the same request,
    the body status is 409). Salt Edge refused the connect session with
    `FakeConnectionsLimitReached` / `ConnectionLimitReached`: the Salt Edge client is still in
    Pending/Test status and its fake-connection slots are used up by the 9 connections created on
    2026-11-03 by `scripts/saltedge-fake-connections.sh` (8 active). The backend behaves as designed:
    the new local connection is rolled back (`bank_connections` on dev is empty, no orphan).
    Expected: a new bank can be added. Layer: environment (Salt Edge account) + small backend/
    frontend UX fix.
    Fix: (a) environment: delete the old fake connections at Salt Edge (`DELETE /connections/{id}`
    for the script's test customer) to free the slots, as in rollout-runbook step "free fake-connection
    slots"; add a `delete` command to `scripts/saltedge-fa    slots"; add a `delete` command to `scripts/saltedge-fa    slots"; add a `delete` ns of the provider are used up: delete old test
    connections in Salt Edge or switch the account to Live"), shown inline in the dialog instead of
    a toast only.
  - _Requirements: 8.3, 12.1, 19.4_
  - _Depends on: none_
- [ ] V1. Validate Round 1 in browser
  - Local stack (`docker compose up -d --build frontend backend`) for bug 1 (STUB provider, narrow
    and wide viewports, PL + RU); dev (https://dev.foremen.eu/bank/connections) for bug 2 after the
    Salt Edge slots are freed: "Add bank" → Salt Edge → `fakebank_simple_xf` → redirect to Salt Edge
    Connect without 409.
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1 — dialog / picker layout.
- **Wave 2:** 2 — free the Salt Edge slots, helper script, message keys, inline dialog error. Serialized
  after 1 because both edit `AddBankDialog.tsx`.
- **Wave 3:** V1 — validation, depends on all fixes.
