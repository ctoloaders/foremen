# Walkthrough report: FOR-07-05 — Menu integration

Run-id: `20261108155814` · Stack: Docker (`frontend :3000` rebuilt, `backend :8080` restarted, Liquibase 217 applied)
Repeatability strategy: generator + teardown (users `menu+{role}-{run-id}@example.com` inserted with the admin password hash, project `MENU-{run-id}`; teardown = users `active=false`, project `CANCELLED`).
Browser engine: Playwright (Chromium), fresh context per role. Locale: users `pl` (RU checked via the language button).

## Group A. Menu structure (desktop 1280×800)

| # | Test case | Step | Action | Expectation | Fact | Status |
|---|-----------|------|--------|-------------|------|--------|
| 1 | TC-A1 | 1–6 | ADMIN sidebar (RU) | Untitled (Panel, Add expense) · Company · Resources · Dictionaries · System (+Bank) · Settings; no Warehouse/Catalog | Exactly that order; Resources = Deliveries, Construction, Finishing, Work catalog | ✅ |
| 2 | TC-A1 | 3–4 | MANAGER sidebar | Company 4 items, Resources items only where permitted | Company: gantt, procurement, payroll, bank tx; Resources: construction, finishing, catalog (no DELIVERIES grant) | ✅ |
| 3 | TC-A1 | – | FOREMAN / FINANCIER / WORKER sidebars | Only permitted items; empty sections hidden | WORKER: Resources + Settings only; FOREMAN: gantt (no procurement/payroll/bank); FINANCIER: procurement, payroll, bank tx, `/bank` | ✅ |
| 4 | TC-A4 | 1–3 | `/rooms`, `/estimate`, `/finances` | NotFound | "404 — Strona nie znaleziona" ×3 | ✅ |
| 5 | TC-A4 | 4–5 | `/projects`, `/projects/1532/estimate`, `/projects/1532/rooms` | Still work | List renders; workspace tabs render | ✅ |
| 6 | TC-A6 | 2 | CLIENT opens `/company/payroll` | 403 page | Redirected to `/403` | ✅ |
| 7 | TC-B10 | 1 | Bottom bar on desktop | None | `nav.fixed.bottom-0` count = 0 for all roles | ✅ |
| 8 | TC-A5 | 2 | RU labels | Translated, no raw keys | Sidebar fully RU ("Ресурсы", …); bottom bar RU ("Добавить отчёт", "Добавить расход") | ✅ |

## Group B. Role bottom bar (mobile 390×844, working project set)

| # | Test case | Role | Fact (label → href) | Status |
|---|-----------|------|---------------------|--------|
| 9 | TC-B1 | WORKER | MENU-… → `/projects/1532`; Dodaj raport → `/projects/1532/workReporting`; Dodaj wydatek → `/expenses/new` | ✅ |
| 10 | TC-B2 | FOREMAN | MENU-… ; Grafik zespołu → `/company/staffing-gantt`; Dodaj wydatek | ✅ |
| 11 | TC-B3 | FINANCIER | MENU-… ; Zakupy firmowe ; Operacje bankowe | ✅ |
| 12 | TC-B4 | MANAGER | MENU-… ; Grafik zespołu ; Zakupy firmowe ; Operacje bankowe | ✅ |
| 13 | TC-B5 | ADMIN | Same 4 as MANAGER | ✅ |
| 14 | TC-B6 | CLIENT | Project slot only | ✅ |
| 15 | TC-B7 | WORKER, no working project | "Wybierz projekt" → `/projects`; Dodaj raport → `/projects`; click goes to `/projects` | ✅ |
| 16 | TC-B2 | FOREMAN clicks Grafik zespołu | Page opens (no 403), slot highlighted | ✅ |
| 17 | TC-B1 | WORKER clicks Dodaj raport | Opens work-reporting tab; report slot highlighted, project slot not | ✅ |
| 18 | TC-B9 | MANAGER / WORKER Drawer | Lists every permitted item (incl. items not on the bar); click → `/materials/construction` | ✅ |

## Group C. API (backend :8080)

| # | Test case | Request | Expectation | Fact | Status |
|---|-----------|---------|-------------|------|--------|
| 19 | TC-C3 | `docker compose up liquibase` | 217 applied once | 2 changesets applied; FOREMAN row added to `COMPANY_SCHEDULE` | ✅ |
| 20 | TC-C1 | FOREMAN `GET /api/company-schedule` | 200, member projects only | 200; legend = project 1532 only | ✅ |
| 21 | TC-C1 | ADMIN `GET /api/company-schedule` | 200 | 200 | ✅ |
| 22 | TC-C2 | FOREMAN `GET /api/company-purchases/documents` | 403 | 403 | ✅ |
| 23 | TC-C2 | FOREMAN `GET /api/payroll/company` | 403 | 403 | ✅ |
| 24 | TC-C2 | WORKER / FINANCIER / CLIENT `GET /api/company-schedule` | 403 | 403 / 403 / 403 | ✅ |
| 25 | – | MANAGER schedule/purchases/payroll; FINANCIER purchases/payroll | 200 | 200 | ✅ |

Итог: 25 пройдено / 0 провалено / 0 пропущено. Run-id: `20261108155814`. Стратегия повторяемости: генератор + teardown.

## Findings outside this spec's scope

1. **Pre-existing:** `ui-store.sidebarOpen` defaults to `true`, so on a fresh mobile load the Drawer is open over the screen and covers the new bottom bar until the user closes it. Not changed by FOR-07-05.
2. **Pre-existing:** `usePageMeta` falls back to "Panel" for `/projects` since FOR-07-00 removed it from the menu; `usePageMeta.test` is stale for the same reason.
3. **Pre-existing:** `TopBar.test` and `TopBar.breakpoint.test` fail to load (their `react-i18next` mock lacks `initReactI18next`).
4. Not exercised: the regression cases R1–R6 beyond the checks above (expense upload form, tab-level permissions, bank page contents).
