# FOR-11-04 shipment tracking and schedule: e2e findings

Spec: `.kiro/specs/FOR-11-Delivery-Management/FOR-11-04-shipment-tracking-and-schedule/` (`test-cases.md`, `requirements.md`).
Suite: `foremen-qa-auto/e2e/tests/for11/04-shipment-tracking/` (`shipments-api`, `schedule-api`, `ui-shipments`, `ui-views`, `support.ts`).

## 1. Summary

**34 tests passed, 6 skipped as known product bugs (BUG-2 to BUG-7), 0 failed.** Not automated: see section 2 (STUB tracking, scheduler, sweep).
Final run: `npx e2e run tests/for11/04-shipment-tracking` returned `EXIT=0` (34 passed, 6 skipped, 163 s).
After the run no test data remains: `FOR1104-%` projects, `for1104+%` users, `FOR1104-%` external orders and purchase requests, orphan shipments and notifications are all 0 (psql).

Repeatability: every world carries a run-id (`FOR1104-<run-id>`, `FOR1104-OTHER-<run-id>`, `for1104+<run-id>-<role>@example.com`); users, projects, positions, external orders, shipments, schedule/estimate rows, notifications and audit rows are created by `makeWorld()` and deleted in `finally` (`world.cleanup()`).
Setup is by SQL for positions (`SUPPLIER_PAID`), paid external orders, the schedule bar and one estimate line (no API reaches those states without the whole FOR-11-02/03 flow); everything else goes through the real API/UI.

## 2. Environment limits (not product bugs)

The running Docker stack has `FOREMEN_PROCUREMENT_TRACKING_ENABLED=false`, `FOREMEN_TRACKING_STUB_ENABLED=false`, `FOREMEN_PROCUREMENT_TRACKING_CRON=-` and `FOREMEN_PROCUREMENT_DEADLINE_SWEEP_CRON=-` (see `docker-compose.yml` lines 132-147, `docker exec foremen-backend-1 env`).
The prerequisites of `test-cases.md` ("Окружение") are therefore not met:

- carrier `STUB` is not registered (`POST /api/shipments` with `carrierCode: "STUB"` answers 400 `Nieznany przewoźnik`), so every STUB-driven step is not automated; the suite uses the link-out carrier `DPD` (never fetched) instead;
- no adapter is enabled, so `refresh` answers 409 `error.shipment.refresh.notAvailable` and `eta/revert` 409 `error.shipment.eta.revertNotAvailable` (R10.1), and the drawer button "Odśwież teraz" is disabled;
- no scheduler cycle and no deadline sweep run.

Restarting the stack with the flags of `test-cases.md` was out of scope (no container restarts).

## 3. Coverage table

| TC | Test (file: title) | Status |
|----|--------------------|--------|
| TC-A1-01 | `shipments-api`: TC-A1-01 BUYER creates a carrier shipment | partial: steps 1-2 pass with carrier DPD (201, CREATED, 1 event, ETA empty, positions IN_DELIVERY, no money fields, `trackingPageUrl`); steps 3-4 (STUB `IN_TRANSIT`, `etaSource=TRACKING`, planned date +2) not automated: STUB disabled |
| TC-A1-02 | `shipments-api`: TC-A1-02 | pass (shop + phone methods, foreign fields null, `etaSource=MANUAL`, P3 stays IN_DELIVERY). Doc mismatch: missing phone answers `error.shipment.phone.required`, test-cases says `method.fieldsMissing` |
| TC-A1-03 | `shipments-api`: TC-A1-03 | pass (steps 1-7). Doc mismatches: cap/order-line/stage errors are HTTP 409 (R16.5), test-cases says 400; the example "7 with 4 already shipped" trips the cap check before the order-line check, the test uses 3 |
| TC-A2-01 | `shipments-api`: TC-A2-01 (partial) | partial: manual ETA/window stored as MANUAL and fed to `plannedDeliveryDate` pass; revert (step 3) and refresh (step 2) not automated (no tracker), their 409 "not available" answers are asserted. The sparse `PUT {etaDate...}` of step 1 is BUG-2 (skipped test) |
| TC-A2-02 | `shipments-api`: TC-A2-02, TC-A2-02 (API side of BUG-3) | pass: manual `OUT_FOR_DELIVERY`/`DELIVERED`, `deliveredAt`, positions stay IN_DELIVERY, terminal shipment 409 except `note`, `UNKNOWN`/`CANCELLED` refused with 400. Sparse `PUT {status}` is BUG-2 |
| TC-A3-01 | `shipments-api`: TC-A3-01 | pass (steps 1-6) |
| TC-A4-01 | none | not automated: 429/`retryAfterSeconds` needs an enabled tracker |
| TC-A4-02 | `shipments-api`: TC-A4-02/03 (partial) | partial: `refresh` never answers 5xx, manual ETA works; `FAILED/TIMEOUT` and `consecutiveFailures` not automated (STUB) |
| TC-A4-03 | same test | not automated (STUB `BLOCKED`, carrier pause) |
| TC-A4-04 | none | not automated: scheduler disabled |
| TC-A5-01 | `schedule-api`: TC-A5-01 | pass (shipment fields, `shipmentId` filter, `days=0` is 400, no money keys) |
| TC-A5-02 | `schedule-api`: TC-A5-02 | pass (foreman of another project: empty `upcoming`, 404 on shipment and overlay) |
| TC-A5-03 | `schedule-api`: TC-A5-03 | pass (`worksStartDate` = start + 4, `downtimeRisk` true/false by ETA, `orderByMissed`, no money, empty `categories` without schedule) |
| TC-A6-01 | none | not automated: sweep cron disabled (no endpoint to trigger it) |
| TC-A6-02 | `schedule-api`: TC-A6-02 | pass (shipped to MANAGER and FOREMAN with their links, delayed and downtimeRisk once per ETA, none for the actor, none for an unchanged ETA) |
| TC-A7-01 | `shipments-api`: TC-A7-01 | pass (full matrix, `note=null` for FOREMAN, 404 for another project's foreman, 403 for CLIENT) |
| TC-A8-01 | `schedule-api`: TC-A8-01 / TC-R-02 | pass |
| TC-B1-01 | `ui-shipments`: TC-B1-01 steps 1-3, step 5, client validation | partial: steps 1-3 (dialog from the paid order, caps "Wysłano 0 z 10", create), step 5 (error at the row, dialog stays) pass; step 3 redirect to `/deliveries/shipments` is BUG-5 (skipped test); step 4 (STUB `IN_TRANSIT`) not automated |
| TC-B1-02 | `ui-shipments`: TC-B1-02 | pass (status filter in URL, list narrowed, kept on reload) |
| TC-B2-01 | `ui-shipments`: TC-B2-01 (partial) | partial: refresh button disabled without a tracker; countdown not automated |
| TC-B2-02 | `ui-shipments`: TC-B2-02 manual entry, invalid phone | partial: manual ETA/window/phone saved and "Wpisana ręcznie" shown, bad phone rejected; revert step is BUG-4 and the status list is BUG-3 (skipped tests) |
| TC-B2-03 | none | not automated: STUB `FAIL` and an enabled refresh |
| TC-B2-04 | `ui-shipments`: TC-B2-04 | pass |
| TC-B3-01 | `ui-views`: TC-B3-01 foreman mobile list, empty period | partial: 360 px, bottom-bar link, card (window, carrier, `tel:`, tracking link), 7/14/30 periods, empty state, deep-link highlight, no money pass; the drawer covering the bottom bar is BUG-7; the "Просрочено" group first and exactly 4 bottom-bar slots are not asserted |
| TC-B3-02 | `ui-views`: TC-B3-02 (3 tests) | pass (other foreman sees nothing; foreman read-only list/drawer without create/refresh/cancel and without note; CLIENT gets "Brak dostępu"). WORKER not checked |
| TC-B4-01 | `ui-views`: TC-B4-01 (4 tests) | pass: toggle off by default, on, persists after reload, off; markers (risk, missed order-by, read-only `role=img`); foreman read-only Gantt (`planActual`, project set ACTIVE) hides order-by markers by default; overlay 500 shows `materials-overlay-error`, Gantt stays. Dragging impossibility is only asserted as "markers are images" |
| TC-B5-01 | `ui-views`: TC-B5-01 bell | partial: titles "Dostawa w drodze" / "Dostawa opóźniona", no raw keys, click opens `/deliveries/shipments?shipment=<id>` with the drawer pass; "marked read" is BUG-6; PL/RU switch (step 3) not automated; the downtimeRisk title is not asserted in the UI |
| TC-R-01 | `schedule-api`: TC-R-01 | pass |
| TC-R-02 | `schedule-api`: TC-A8-01 / TC-R-02 | pass |
| TC-R-03 | `shipments-api`: TC-R-03 | partial: manual transition `IN_DELIVERY -> SUPPLIER_PAID` is refused; the rest of the matrix is a unit-test concern |
| TC-R-04 | `ui-views`: TC-B4-01 toggle | partial: toggle off leaves the Gantt as before only implicitly; drag/cascade/save not automated |
| TC-R-05 | none | covered implicitly: the whole suite runs on a stack with tracking disabled and cron `-`; the backend log was not inspected |
| TC-R-06, TC-R-07 | none | not automated (other specs' flows and other roles' navigation) |

## 4. Bugs

Severity is the tester's estimate. "Feature-sized" = needs more than a small localized change.

### BUG-1 (fixed) Frontend production build failed on a test file
- Layer: frontend (test file). Status: fixed by the coordinator (not touched here).
- `src/features/shipments/__tests__/shipmentsPage.test.tsx(118,12): error TS2532: Object is possibly 'undefined'`. `npm run build` runs `tsc -b`, which type-checks test files, so `docker compose up --build frontend` failed. Fixed with `[0]?.getAttribute(...)`.

### BUG-2 `PUT /api/shipments/{id}` is not a partial update (sparse body rejected or wipes fields)
- Endpoint: `PUT /api/shipments/{id}`. Test: `shipments-api` "TC-A2-01 step 1 / TC-A2-02 step 1 a sparse PUT..." (skipped).
- Repro: create a `CARRIER` shipment (DPD, driver `Jan`, note `keep me`); `PUT {"etaDate":"<today+5>","etaTimeFrom":"09:00","etaTimeTo":"12:00"}` (the body of test-cases A2-01 step 1). Also create a `SHOP_DELIVERY` shipment with link, driver name/phone and note, then `PUT {"status":"IN_TRANSIT"}` (A2-02 step 1).
- Observed: CARRIER shipment: `400` `Wybierz przewoźnika` (`fieldErrors.carrierCode`, key `error.shipment.carrier.required`). SHOP_DELIVERY shipment: `200` but `shopOrderLink`, `note`, `driverName`, `driverPhone` all become `null`.
- Expected (test-cases A2-01/A2-02, R5.1): only the supplied field changes (ETA / status), the rest is kept.
- Evidence: reproduced with a throwaway test during this session (status codes above); `ShipmentRequest` javadoc says "the method, driver and note fields are replaced as given".
- Layer: backend. Suspected: `ShipmentService.update` (calls `validateMethod(request, method)` and `applyMethodFields`, `setDriverName(blankToNull(request.driverName()))`, `setNote(...)` unconditionally). The frontend `ManualEntryForm.tsx` works around it by echoing every current field.
- Requirement: R5.1 (and test-cases A2-01 step 1, A2-02 step 1). Not feature-sized (merge null fields with the current values), but it is a contract decision: either fix the API or fix the test-cases.

### BUG-3 Manual status list offers "Nieznany" (UNKNOWN), which the API refuses
- Screen: `/deliveries/shipments/<id>` -> "Wpisz ręcznie" -> select `manual-status`. Test: `ui-shipments` "TC-B2-02 the manual status list offers only statuses the API accepts" (skipped).
- Repro: open any open shipment as BUYER, tap "Wpisz ręcznie", open the status select.
- Observed: options include "Nieznany" (UNKNOWN); selecting it and saving gets `400` with the misleading key `error.shipment.method.fieldsMissing` ("Uzupełnij wymagane pola dla wybranej metody dostawy"). API check (passing test): `PUT status=UNKNOWN` and `CANCELLED` both answer 400.
- Expected: only `CREATED`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `EXCEPTION` (R5.1).
- Layer: frontend (primary), backend (wrong error key for an invalid manual status). Suspected: `foremen-frontend/src/features/shipments/components/ManualEntryForm.tsx` (`MANUAL_STATUSES` contains `'UNKNOWN'`); `ShipmentService.applyManualStatus` throws `FIELDS_MISSING` for a status outside `MANUAL_STATUSES`.
- Requirement: R5.1, R20.3. Not feature-sized.

### BUG-4 "Wróć do daty przewoźnika" is offered where reverting is impossible
- Screen: `/deliveries/shipments/<id>`. Test: `ui-shipments` "TC-B2-02 step 2 the revert-ETA action is offered only where it can work" (skipped).
- Repro: BUYER creates a `PHONE_CONTACT` shipment with `etaDate` (manual ETA), opens the drawer.
- Observed: the button `shipment-revert-eta` is shown; clicking it sends `POST /eta/revert` -> `409 error.shipment.eta.revertNotAvailable`, and the user sees the generic toast "Nie udało się zapisać przesyłki".
- Expected: the action exists only for carrier shipments with an enabled tracker (R5.2 "carrier shipments only"; R20.3).
- Layer: frontend. Suspected: `ShipmentDrawer.tsx`, condition `s.etaSource === 'MANUAL'` for the revert button (should also require a trackable carrier shipment, e.g. `s.refreshAvailable`). Requirement: R5.2, R20.3. Not feature-sized.

### BUG-5 After "Utwórz przesyłkę" from an external order the user is not taken to the shipments list
- Screen: `/deliveries/external-orders/<id>` (PAID order) -> "Dodaj przesyłkę". Test: `ui-shipments` "TC-B1-01 step 3 after saving the user lands on /deliveries/shipments..." (skipped).
- Repro: fill positions, carrier DPD, number; "Utwórz przesyłkę".
- Observed: dialog closes, URL stays `/deliveries/external-orders/<id>`, only a toast. (The same dialog opened from `/deliveries/shipments` does navigate to the drawer.) The shipment and position stages are saved correctly.
- Expected (test-cases B1-01 step 3): navigation to `/deliveries/shipments` with the new row "Utworzona".
- Layer: frontend. Suspected: `features/external-orders/components/buyer/ExternalOrderEditor.tsx` renders `ShipmentFormDialog` without `onCreated`. Requirement: R20.2 does not state the redirect, so this may be a test-cases wording issue (low). Not feature-sized.

### BUG-6 Clicking a notification does not mark it read
- Screen: top-bar bell. Test: `ui-views` "TC-B5-01 step 2 clicking the notification marks it read" (skipped).
- Repro: MANAGER with an unread `notification.shipment.shipped`; open the bell, click the row.
- Observed: navigates to `/deliveries/shipments?shipment=<id>` (drawer opens) but `GET /api/notifications` still shows `read:false`; only the separate toggle marks it read.
- Expected (test-cases B5-01 step 2): the notification is marked read.
- Layer: frontend (shared component). Suspected: `features/notifications/components/NotificationBell.tsx` `handleNavigate` (navigates only, never calls `toggleRead`). Requirement: R15 does not mention read-on-click; low, likely a test-cases expectation that the shared bell never implemented. Not feature-sized.

### BUG-7 On a 360 px screen the navigation drawer is open right after sign-in and covers the foreman bottom bar
- Screen: `/` at viewport 360x800 as FOREMAN. Test: `ui-views` "TC-B3-01 step 1 at 360 px the bottom bar is usable right after sign-in" (skipped). The passing B3-01 test closes the drawer first (`Close menu`).
- Repro: set a 360 px viewport, sign in as FOREMAN, try to tap the bottom-bar link "Nadchodzące dostawy".
- Observed: the tap is blocked: `div.fixed.inset-0.z-50.visible` (the `Drawer` with its dimmed backdrop) intercepts pointer events; the drawer is open although nobody opened it.
- Expected: drawer closed on mobile, bottom bar (4 slots, last "Nadchodzące dostawy", R19.3) usable at 360 px (R21.5).
- Layer: frontend. Suspected: `foremen-frontend/src/stores/ui-store.ts:23` (`sidebarOpen: true` as the default) used by `app/layout/AppShell.tsx:76` (`<Drawer open={sidebarOpen}>`) for the mobile breakpoint. Not specific to FOR-11-04 (shell, FOR-07-05). Requirement: R19.3, R21.5. Not feature-sized.

## 5. Other observations (not counted as bugs)

- `test-cases.md` uses UI test ids that do not exist (`shipment-create-btn`, `shipment-refresh-btn`, `overlay-toggle`, `overlay-marker-*`); the app has `shipment-new`, `shipment-refresh`, `materials-overlay-toggle`, `materials-overlay-marker-*`. The tests use the real ids.
- `test-cases.md` expects `error.shipment.*` keys; the API error body carries only the localized message, so the tests assert the PL text (the `MSG` map in `support.ts`).
- The create dialog pre-fills the quantity of a position with its remaining cap, not the remaining quantity of the selected external order line; submitting it unchanged for an order line smaller than the cap gives `exceedsOrderLine` (409) at the row. Not asserted (UX).
- `TC-A6-02`: `delayed`/`downtimeRisk` are emitted once per distinct ETA as specified; both go to MANAGER and FOREMAN with their own links.

## 6. What blocked me

- Tracking, STUB and both crons are off in the running stack (section 2); no way to enable them without restarting containers.
- No API creates a `SUPPLIER_PAID` position, a paid external order or a schedule row cheaply, so the world is built by SQL (own rows only, removed in cleanup).
- Heredocs in the shell mangled some text I wrote (file edits were redone with the file tools); no impact on the delivered files.
