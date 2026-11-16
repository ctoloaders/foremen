# Bug report: FOR-11-04-shipment-tracking-and-schedule

Screen URL: http://localhost:3000/deliveries/shipments, /deliveries/external-orders/<id>, / (mobile 360 px)

## Round 1 (2026-11-16)

Source: e2e run of `foremen-qa-auto/e2e/tests/for11/04-shipment-tracking` (34 passed, 6 skipped as bugs;
STUB tracking and crons are off in the local stack). Evidence: `tests/for11/04-shipment-tracking/FINDINGS.md`.

Decisions: BUG-2 — a `null`/absent field in `PUT /api/shipments/{id}` keeps the stored value (partial
update); an empty string clears a text field. BUG-5 and BUG-6 are implemented as the test-cases describe
(the requirements are silent).

- [x] 1. Frontend production build fails on a type error in a shipments test
  - Triage: observed: `npm run build` (`tsc -b`) fails with `shipmentsPage.test.tsx(118,12): TS2532`, so `docker compose up --build frontend` fails. Fixed during the run (`[0]?.getAttribute`). Layer: frontend (test file).
  - _Requirements: none (build)_
  - _Depends on: none_
- [x] 2. `PUT /api/shipments/{id}` is not a partial update
  - Triage: observed: a sparse body gets 400 `carrier.required` for a CARRIER shipment and wipes link/note/driver of a SHOP_DELIVERY shipment; expected: only supplied fields change. Layer: backend. Suspect: `ShipmentService.update` (`validateMethod`, `applyMethodFields`).
  - _Requirements: R5.1_
  - _Depends on: none_
- [x] 3. The manual status list offers "Nieznany" (UNKNOWN), which the API refuses with a misleading error
  - Triage: observed: UNKNOWN offered; saving gives 400 `method.fieldsMissing`; expected: only CREATED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION; an invalid status gets its own error key. Layer: both. Suspect: `ManualEntryForm.tsx` (`MANUAL_STATUSES`), `ShipmentService.applyManualStatus`.
  - _Requirements: R5.1, R20.3_
  - _Depends on: 2_ (same service)
- [x] 4. "Wróć do daty przewoźnika" is offered where reverting is impossible
  - Triage: observed: shown for a manual ETA on a non-carrier shipment; click gives 409 `revertNotAvailable` and a generic toast; expected: shown only for trackable carrier shipments. Layer: frontend. Suspect: `ShipmentDrawer.tsx` (`etaSource === 'MANUAL'`).
  - _Requirements: R5.2, R20.3_
  - _Depends on: none_
- [x] 5. Creating a shipment from an external order does not lead to the shipments list
  - Triage: observed: dialog closes, URL stays on the order; expected (test-cases B1-01 step 3): the user lands on `/deliveries/shipments` with the new shipment. Layer: frontend. Suspect: `ExternalOrderEditor.tsx` (`ShipmentFormDialog` without `onCreated`).
  - _Requirements: R20.2_
  - _Depends on: none_
- [x] 6. Clicking a notification navigates but does not mark it read
  - Triage: observed: `read:false` after the click; expected (test-cases B5-01 step 2): the clicked notification is marked read. Layer: frontend (shared bell). Suspect: `features/notifications/components/NotificationBell.tsx` (`handleNavigate`).
  - _Requirements: R15_
  - _Depends on: none_
- [x] 7. At 360 px the navigation drawer is open after sign-in and blocks the foreman bottom bar
  - Triage: observed: the drawer backdrop intercepts taps on the bottom bar; expected: drawer closed on mobile until opened. Layer: frontend (app shell). Suspect: `stores/ui-store.ts` (`sidebarOpen: true` default), `app/layout/AppShell.tsx` (mobile `Drawer open={sidebarOpen}`).
  - _Requirements: R19.3, R21.5_
  - _Depends on: none_
- [x] V1. Validate Round 1
  - Rebuild, remove `.skip` from the BUG tests, run `npx e2e run tests/for11/04-shipment-tracking`.
  - _Depends on: 2, 3, 4, 5, 6, 7_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 2, 4, 5, 6, 7 — different files.
- **Wave 2:** 3 — depends on 2 (`ShipmentService`).
- **Wave 3:** V1

## Round 1 — fixes and validation results (2026-11-16)

| Bug | Fix | Files |
|-----|-----|-------|
| 1 | `[0]?.getAttribute(...)` (fixed during the run). | `features/shipments/__tests__/shipmentsPage.test.tsx` |
| 2 | `update` merges the request with the stored carrier/tracking/link/phone and validates the merged state; null driver name / note keeps the value, `""` clears; the form sends `""` to clear. | `service/procurement/shipment/ShipmentService.java`, `controller/model/ShipmentRequest.java`, `ManualEntryForm.tsx` |
| 3 | UNKNOWN removed from the manual statuses; an invalid manual status answers 400 `error.shipment.status.invalid` (PL/RU bundles). | `ManualEntryForm.tsx`, `ShipmentService.java`, `messages*.properties` |
| 4 | Revert-ETA shown only when `etaSource === 'MANUAL' && refreshAvailable`. | `ShipmentDrawer.tsx` |
| 5 | Creating a shipment from an external order navigates to `/deliveries/shipments/{id}`. | `ExternalOrderEditor.tsx` |
| 6 | Clicking an unread notification marks it read, then navigates. | `features/notifications/components/NotificationBell.tsx` |
| 7 | The sidebar starts closed on mobile widths (`max-width: 767px`), open on tablet/desktop. | `stores/ui-store.ts` |

Tests: `ShipmentServiceTest` (new partial-update cases), `ShipmentRulesTest`, `ShipmentControllerTest`,
`ShipmentMessagesParityTest`, `ShipmentLifecycleIntegrationTest`; vitest shipments, notifications, `ui-store.test.ts`.
Validation: former BUG tests of `tests/for11/04-shipment-tracking` unskipped and pass. Full `tests/for10 tests/for11`
run green. Limit: resizing a desktop window to mobile after load does not close the drawer.
