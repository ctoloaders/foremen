# Bug report: FOR-07-06-company-payroll

Screen URLs:
- `https://dev.foremen.eu/company/payroll` (company payroll overview)
- `https://dev.foremen.eu/company/payroll/{workerId}?from=&to=` (worker payroll card)

## Round 1 (2026-11-05)

Source: meeting notes. Four items (E, F, G, H). Decisions agreed with the user: planning mode is the
**default** (always show all workers) + a summary status column; month grouping on the worker card;
a month quick-select; and previous-month payment hints with quick-add.

Background (grounded in code):
- `PayrollReadModelService.companyTable(from, to, workerIds)` loads every WORKER in the unfiltered
  branch but then **drops zero-activity workers**: `if (!filtered && !activity) continue;`. "Planned"
  and "reported" are already costed in `load(...)` from APPROVED report lines (`line.getQuantity()` →
  reported, `line.getPlanQuantity()` → planned), but a worker with no lines/payouts/additional lines
  has `activity=false` and is removed. The frontend (`CompanyPayrollTable.tsx`) is a pass-through.
- `workerCard(...)` already builds one `PayrollMonthDto` per month in `[from,to]`
  (`MonthAttribution.monthsBetween`), with earned/additional/payouts/balances per month — so BUG F is
  largely present; the visible-symptom is a single-month default range (ties to G).
- The list filter bar (`PayrollFilterBar.tsx`) has only `MONTH`/`YEAR` presets + prev/next; the
  worker card (`WorkerPayrollCard.tsx`) has only prev/next + raw date inputs. No month picker.
  `lib/dateRange.ts` already exposes `monthOf`/`currentMonth`/`presetRange`/`shift`.
- Payment creation (`CreatePayoutDialog.tsx`) builds lines only from the live
  `usePayoutSuggestion(...)` → `PayoutSuggestionService`. The existing previous-month "hints" machinery
  (`PayrollHintSelector`, the `hints` endpoint) is for **additional free-text lines only** — there is
  no previous-month payout-line hint.

### E — Planning mode: show ALL workers by default + a payment status summary column

- [x] 1. Company payroll lists every worker by default (zero-activity included)
  - Triage: observed — unfiltered `companyTable` drops workers with no activity in the period
    (`if (!filtered && !activity) continue;`), so only workers with records appear. Expected — by
    **default** every WORKER is listed, including those with 0 planned/reported/approved work (their
    sums are 0 and `projects` empty, `balanceAtTo` from the opening balance). Layer: **backend**.
    - Remove the zero-activity drop for the default view (the activity gate becomes unconditional
      "keep"), preserving the explicit-`workerIds` behavior. Emit a zeroed `PayrollSumsDto` + empty
      `projects` for no-activity workers.
    - This revises **Requirement 10.2** (currently "one row per WORKER that HAS … any report line,
      payout, or additional line") — amend the acceptance criterion to "all workers (planning mode,
      default)". Update `requirements.md`/`design.md` accordingly.
  - _Requirements: 10.2 (amended)_
  - _Depends on: none_
- [x] 2. Add a per-worker payment status summary column
  - Triage: expected — a summary column classifying each worker, e.g. **fully paid** (balance settled,
    nothing outstanding), **has debt** (negative balance / unpaid approved work), **unreported works**
    (planned > reported), etc. Layer: **both** (backend derives the status; frontend renders a
    badge).
    - Backend: derive a per-row status enum on `CompanyPayrollRowDto` from the already-computed
      figures (reported / planned / paid / `balanceAtTo`) — e.g. `FULLY_PAID` | `HAS_DEBT` |
      `UNREPORTED_WORK` | `NO_ACTIVITY` (define precedence in design). No new query needed.
    - Frontend: render the status as a localized badge column in `CompanyPayrollTable.tsx`.
    - i18n: status labels + column header in `pl.json` + `ru.json`.
  - _Requirements: 10.x (new status-column requirement)_
  - _Depends on: 1_
- [x] 3. Create-payment affordance from the company list row
  - Triage: expected — a worker can be paid directly from the overview row (today the create dialog
    lives only on the worker card). Layer: **frontend**.
    - Add a `CreatePayoutDialog` trigger per row in `CompanyPayrollTable.tsx`, gated on
      `PAYROLL`/CREATE via `usePermission`, reusing the existing dialog.
  - _Requirements: (new create-from-list requirement)_
  - _Depends on: 1_

### F — Worker payroll grouped by month (verify; likely surfaced by G)

- [x] 4. Ensure the worker card shows all planned/reported/approved works grouped by month
  - Triage: observed — `workerCard` already groups by month, but the default single-month range
    hides multi-month grouping. Expected — the card shows the worker's planned/reported/approved
    works grouped by month across the selected range. Layer: **frontend** (range) — confirm no
    backend grouping regression first.
    - Verify in the browser whether grouping is genuinely missing or just a narrow default range. If
      only the range, the fix is G (month/longer range quick-select). If a real gap, fix the month
      iteration in `workerCard`/`PayrollMonthBlock`. Ensure planned + reported + approved all appear
      per month block.
  - _Requirements: 11.x_
  - _Depends on: 6_
- [x] V1a. Validate E + F in browser
  - Rebuild; on `/company/payroll` confirm all workers show (incl. zero-work) with the status column
    and a per-row create-payment control; open a worker and confirm planned/reported/approved works
    grouped by month.
  - _Requirements: 10.2, 10.x, 11.x_
  - _Depends on: 1, 2, 3, 4_

### G — Month quick-selection (list + worker card)

- [x] 5. Month quick-select on the company payroll filter bar
  - Triage: expected — a control to set `from`/`to` to a whole chosen month (first..last day),
    setting the preset to MONTH. Layer: **frontend**.
    - Add a month picker (e.g. `<input type="month">` or a recent-months dropdown) to
      `PayrollFilterBar.tsx`, wired to `setRange` using `monthOf(...)` from `lib/dateRange.ts`.
    - i18n: picker label in `pl.json` + `ru.json`.
  - _Requirements: 15.2 (extended)_
  - _Depends on: none_
- [x] 6. Month quick-select on the worker payroll card
  - Triage: expected — the same month quick-select on the worker card's period bar (today only
    prev/next + raw dates), so multi-month data (BUG F) is reachable. Layer: **frontend**.
    - Add the month picker to `WorkerPayrollCard.tsx`'s `periodBar`, wired to `applyRange(monthOf(...))`.
  - _Requirements: 15.2 (extended)_
  - _Depends on: 5_

### H — Payment hints from previous month's lines + quick add

- [x] 7. Previous-month payout-line hints with quick-add in the create-payment dialog
  - Triage: observed — the create dialog builds lines only from the live suggestion; there is no
    "suggest the line items paid/reported last month and quick-add them." Expected — when creating a
    payment, surface suggested line items from the **previous month** and let the user quick-add
    them. Layer: **both**.
    - Backend: add a hints endpoint returning the worker's previous-month payout/report lines
      (previous-month `WorkerPayoutAllocation`s or previous-month APPROVED report lines), analogous to
      the existing `PayrollHintSelector` pattern but for payout lines. Project-scoped / PAYROLL-read.
    - Frontend: extend `CreatePayoutDialog.tsx` (and/or `PayoutBreakdownTable.tsx`) to show the
      previous-month line hints with a quick-add that pre-fills them; `lib/period.ts`'s
      `PREVIOUS_MONTH` resolves the window to seed the query.
    - i18n: hint labels + quick-add action in `pl.json` + `ru.json`.
  - _Requirements: 8.x (new payout-line-hints requirement; R8 today covers additional-line hints only)_
  - _Depends on: none_
- [x] V1b. Validate G + H in browser
  - Rebuild; confirm the month quick-select sets a whole-month range on both the list and the worker
    card, and that creating a payment surfaces previous-month line hints with working quick-add.
  - _Requirements: 15.2, 8.x_
  - _Depends on: 5, 6, 7_

- [x] 8. Update requirements.md/design.md + author test-cases.md
  - Amend R10.2 (planning default), add the status-column, create-from-list, month quick-select, and
    payout-line-hints requirements; author/refresh `test-cases.md` (API tests against the Dockerized
    app for the company-table-all-workers, the status derivation, and the new hints endpoint; browser
    scenarios for the status column, create-from-list, month picker, and quick-add) per the
    test-cases standard.
  - _Requirements: all of Round 1_
  - _Depends on: 1, 2, 3, 5, 6, 7_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1 (backend all-workers), 5 (list month picker), 7 (payout-line hints) —
  independent across backend list / FE filter bar / hints endpoint.
- **Wave 2 (parallel):** 2 (status column, depends on 1), 3 (create-from-list, depends on 1),
  6 (card month picker, depends on 5).
- **Wave 3:** 4 (month grouping verify, depends on 6).
- **Wave 4:** 8 (requirements + test-cases, depends on 1, 2, 3, 5, 6, 7).
- **Wave 5:** V1a (depends on 1, 2, 3, 4) and V1b (depends on 5, 6, 7) — browser validation.

## Round 1 — validation results (2026-11-05)

Stack: local Docker rebuilt (backend :8080, frontend :3000, admin `cto+1@loaders.dev`). Screens:
`/company/payroll` and `/company/payroll/2645?from=2026-09-01&to=2026-11-30`.

- **E — all workers by default — ✅ FIXED.** The overview lists **14** worker rows including
  zero-activity workers (status `Нет активности` / NO_ACTIVITY), confirming the activity-drop is
  gone (default planning mode).
- **E — status column — ✅ FIXED.** New `Статус` column with derived per-worker statuses
  (`Нет активности` / NO_ACTIVITY, `Задолженность` / HAS_DEBT, etc.), testid
  `company-payroll-status-<id>`.
- **E — create-payment from the list — ✅ FIXED.** Each row has a create-payment control (14
  triggers in the `Действия` column).
- **F — worker card month grouping — ✅ FIXED.** Worker 2645 over Sep–Nov renders distinct month
  blocks (`payroll-month-2026-09`, `payroll-month-2026-10`, …) with per-month total/paid/balance.
- **G — month quick-select — ✅ FIXED.** A month picker is present on both the overview filter bar
  (`company-payroll-month`, `type=month`) and the worker card (`payroll-card-month`).
- **H — previous-month payment hints + quick-add — ✅ FIXED.** Opening the create-payment dialog
  shows a previous-month payout-line hints section (`payroll-payout-hints`) with individual line
  hints (`payroll-payout-hint-139`) and a quick-add-all control (`payroll-payout-hints-add-all`).

Backend: affected tests green (`PayrollReadModelServiceTest` 8/8, `CompanyPaymentStatusTest` 8/8,
`PayoutLineHintServiceTest` 3/3, `PayrollControllerTest` 7/7); `compileJava/compileTestJava` green.
Frontend: 151 company-payroll tests green; `tsc -b` clean. No new migrations; no new notifications.

Result: E, F, G, H all fixed / 0 failed.
