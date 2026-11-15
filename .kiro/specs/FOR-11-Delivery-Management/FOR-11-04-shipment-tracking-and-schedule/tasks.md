# Implementation Plan: FOR-11-04 — Shipment tracking and delivery schedule

## Overview

Implements the `Shipment` aggregate, the carrier-tracking SPI with scheduler and manual refresh, ETA
propagation, the extended `upcoming` endpoint, the Gantt materials overlay, the deadline sweep and four
notifications, plus the buyer / manager / foreman frontend.

- **Reuse:** `PositionLifecycleService` and `PositionTransitionPolicy`, `PlannedDeliveryDateSynchronizer`,
  `UpcomingDeliveriesService`, `ProcurementProjectAccess`, `ProformaOrderNotificationEmitter` (pattern),
  `NotificationService`, `NipLookupService` / `BankFeedConnectorRegistry` (SPI pattern),
  `PurchaseRequestOverdueSweepService` (sweep pattern), `ScheduledCategoryService`, the Gantt
  `lib/timeline`, FOR-11-03 external orders.
- **Spans two git repos:** backend, specs and infrastructure commit from the workspace root; all
  `foremen-frontend/` changes commit from inside `foremen-frontend/` (the root repo ignores it). Commit
  only when asked.
- **Migrations:** changesets **241** (shipments, lines, events), **242** (`SHIPMENTS` resource and
  grants), **243** (`purchase_positions.order_by_notified_for`), each registered last in
  `changelog.xml`.
- **Test-run policy:** run only the affected test classes with `--tests`, redirect output to
  `/tmp/for-11-04-test.log`, read the JUnit XML under `foremen-backend/build/test-results/test/`; never
  run the full suite unless asked (it takes about 20 minutes). Compile-only checks use
  `./gradlew compileJava compileTestJava`.
- **Shared-code changes:** `PositionTransitionPolicy` (one new edge), `PlannedDeliveryDateSynchronizer`,
  `UpcomingDeliveriesService` + `UpcomingDeliveryDto` (additive), `application.yml`,
  `docker-compose.yml`, `messages*.properties`, `navigation.ts`, `route-permissions.ts`, `router.tsx`,
  the two Gantt screens, the external-order detail, `pl.json` / `ru.json`.
- **No carrier is called in tests.** Adapters are tested on recorded fixtures; scenarios use the `STUB`
  adapter. The master switch `foremen.procurement.tracking.enabled` ships `false`.

## Tasks

- [x] 1. Database
  - [x] 1.1 Create `241-create-shipments.xml`
    - Tables `shipments`, `shipment_lines`, `shipment_events` with CHECKs, FKs and indexes per design
      section 1; idempotent; register last in `changelog.xml`.
    - _Requirements: 2.1, 2.2, 2.4, 2.5_
  - [x] 1.2 Create `242-seed-shipments-resource.xml`
    - Resource `SHIPMENTS` (RU/PL name and description) and the grants of Requirement 1.2 in a second
      changeSet; both guarded by `onFail="MARK_RAN"` preconditions; register last after 241.
    - _Requirements: 1.1, 1.2, 1.3_
    - _Depends on: 1.1_
  - [x] 1.3 Create `243-add-position-order-by-notified-for.xml`
    - `purchase_positions.order_by_notified_for date NULL`; register last after 242.
    - _Requirements: 2.3, 14.2_
    - _Depends on: 1.2_

- [x] 2. Carrier spike (deliverable: `carrier-spike.md`)
  - [x] 2.1 Spike InPost, Orlen Paczka and Poczta Polska / Pocztex
    - For each: mechanism (public JSON behind the page, documented unauthenticated endpoint, or page
      parsing), URL pattern, number pattern, redacted sample response, terms-of-use and robots notes,
      observed rate limits, verdict `ADAPTER` or `MANUAL_ONLY`. Output `spike/inpost-orlen-poczta.md`.
      Use only a handful of requests, identify the client, never hammer.
    - _Requirements: 8.1, 8.2_
  - [x] 2.2 Spike DPD and GLS
    - Same template. Output `spike/dpd-gls.md`.
    - _Requirements: 8.1, 8.2_
  - [x] 2.3 Spike DHL, UPS and FedEx
    - Same template. Output `spike/dhl-ups-fedex.md`.
    - _Requirements: 8.1, 8.2_
  - [x] 2.4 Consolidate `carrier-spike.md`
    - Merge the three spike files into one table of verdicts (carrier, mechanism, verdict, notes) and
      list which adapters task 4.1 builds. Present the verdicts to the product owner.
    - _Requirements: 8.1, 8.2, 8.5_
    - _Depends on: 2.1, 2.2, 2.3_

- [x] 3. Backend
  - [x] 3.1 Entities, enums persistence and DAOs
    - `ShipmentEntity`, `ShipmentLineEntity`, `ShipmentEventEntity`; `ShipmentDao` (incl.
      `findDueForTracking`, active-with-ETA-per-project), `ShipmentLineDao` (incl. active quantity sum
      per position, active shipments for position ids, `existsActiveWithEta`), `ShipmentEventDao`;
      map `PurchasePositionEntity.orderByNotifiedFor`.
    - _Requirements: 2.1, 2.2, 2.3, 4.4_
    - _Depends on: 1.1, 3.2_
  - [x] 3.2 Enums and pure rules
    - `DeliveryMethod`, `ShipmentStatus` (+ `isTerminal`, `rank`), `ShipmentSource`, `EtaSource`;
      `ShipmentRules` (tracking-number normalisation, method field rules, cap, shippability,
      status-overwrite rule, effective ETA, delay detection); `TrackingBackoff`; `WorksStartResolver`.
      Unit tests for each.
    - _Requirements: 3.1 to 3.6, 4.2, 4.4, 5.3, 7.6, 11.1, 11.3, 13.4_
  - [x] 3.3 Position machine: new SYSTEM edge
    - Add `IN_DELIVERY -> SUPPLIER_PAID` for `AGREED` and `PACKAGE` in `PositionTransitionPolicy`;
      extend the policy tests with a full edge-matrix "nothing else changed" assertion.
    - _Requirements: 6.2, 26.2_
  - [x] 3.4 Tracking SPI infrastructure
    - `CarrierTrackingAdapter`, `CarrierTrackingRegistry`, `TrackingResult`, `TrackingFailure`,
      `TrackingUnavailableException`, `TrackingHttpClient` (host-pinned, timeouts, size cap, same-host
      redirects only, no body logging), `CarrierThrottle`, `TrackingProperties`, `StubTrackingAdapter`
      (code `STUB`, prefix-scripted, property-gated). Add **all** properties of design section 5
      (tracking and `deadline-sweep`) to `application.yml` with real defaults, and the env mappings to
      `docker-compose.yml` (empty cron mapped to `-`). SPI contract test base class.
    - _Requirements: 7.1 to 7.7, 9.1, 14.1, 25.6, 26.5_
    - _Depends on: 3.2_
  - [x] 3.5 Backend messages
    - All `error.shipment.*` keys of Requirement 24.2 in `messages.properties` (PL) and
      `messages_ru.properties` (RU) plus a parity test.
    - _Requirements: 24.1, 24.2, 24.3_
  - [x] 3.6 Synchronizer extension
    - `PlannedDeliveryDateSynchronizer.onShipmentChanged(positionId)`; `onRequestDateChanged` skips
      positions with an active shipment ETA; unit tests (precedence, fallback, terminal positions).
    - _Requirements: 11.1, 11.2, 11.5, 26.3_
    - _Depends on: 3.1, 3.2_
  - [x] 3.7 `MaterialScheduleRiskService`
    - Pure flag computation (`orderByMissed`, `downtimeRisk`, `noArrivalDate`) plus a loader of works
      start per project and category (earliest bar through `WorksStartResolver`); unit tests incl. the
      half-day conversion and missing anchor or bars.
    - _Requirements: 13.3 to 13.5, 13.7_
    - _Depends on: 3.1, 3.2_
  - [x] 3.8 `ShipmentService`, DTOs and events
    - Create (locks, cap and order-line checks, `SUPPLIER_PAID -> IN_DELIVERY`, synchronizer, first
      refresh after commit), update, cancel (+ position reversal), ETA revert, list, get; foreman note
      masking; `ShipmentCreatedEvent`, `ShipmentEtaDelayedEvent`, `ShipmentDowntimeRiskEvent`,
      `PositionOrderByMissedEvent`; `ShipmentDto` and request models; audit snapshots; unit tests with
      mocks.
    - _Requirements: 3, 4, 5, 6, 11.3, 11.4, 16.2 to 16.7, 17_
    - _Depends on: 3.1, 3.2, 3.3, 3.5, 3.6, 3.7_
  - [x] 3.9 `ShipmentTrackingService` and scheduler
    - Refresh flow with cooldown, spacing, carrier pause, back-off, status / ETA application, events,
      `RefreshOutcome`; `ShipmentTrackingScheduler` (cron, batch, grouping by carrier, summary log);
      tests with the `STUB` adapter.
    - _Requirements: 7.5 to 7.9, 9, 10_
    - _Depends on: 3.4, 3.8_
  - [x] 3.10 `ShipmentController`
    - `/api/shipments` list, get, create, update, cancel, refresh, ETA revert;
      `@PermissionResource("SHIPMENTS")` with explicit `@RequiresPermission` on the actions; `429` body
      with `retryAfterSeconds`; startup annotation validation must pass.
    - _Requirements: 1.4, 1.5, 10, 16_
    - _Depends on: 3.8, 3.9_
  - [x] 3.11 `ShipmentNotificationEmitter`
    - Four types, recipient resolution (MANAGER + FOREMAN members, actor removed, manager link wins),
      delay threshold of one day, failure isolation; unit tests for recipients, links and the one-day
      rule.
    - _Requirements: 15_
    - _Depends on: 3.8_
  - [x] 3.12 Extend `upcoming`
    - New nullable DTO fields, the batch query of active shipments, nearest-shipment selection,
      optional `shipmentId` parameter, reflection guard test for money-like components; keep existing
      fields and order.
    - _Requirements: 12, 26.1_
    - _Depends on: 3.1_
  - [x] 3.13 Materials overlay
    - `ScheduleOverlayService`, records, `ScheduleOverlayController`
      (`GET /api/purchase-positions/schedule-overlay`, `PURCHASE_POSITIONS` READ, project scope), reuse of
      `ScheduledCategoryService` / `WorkCategoryResolver`; unit tests of the assembly.
    - _Requirements: 13, 17.3_
    - _Depends on: 3.1, 3.7_
  - [x] 3.14 `ProcurementDeadlineSweepService`
    - Order-by candidates query on `PurchasePositionDao`, downtime evaluation over active shipments,
      one-shot markers, events published after staging, per-item failure isolation, idempotence test.
    - _Requirements: 14_
    - _Depends on: 1.3, 3.7, 3.8_
  - [x] 3.15 `ShipmentsDataset`
    - Dataset class, registration, sensitive-field exclusions; `DatasetCoverageTest` and
      `SensitiveFieldGuardTest` stay green.
    - _Requirements: 18_
    - _Depends on: 1.2, 3.1_

- [x] 4. Carrier adapters
  - [x] 4.1 Implement adapters for carriers with verdict `ADAPTER`
    - One adapter per carrier (fetch, pure parse, status mapping), recorded fixtures in
      `src/test/resources`, per-carrier enable property, SPI contract test. Carriers with
      `MANUAL_ONLY` get only a `trackingPageUrl` link builder entry (no fetch). Never call real carriers
      in tests.
    - _Requirements: 8.2 to 8.5, 7.3_
    - _Depends on: 2.4, 3.4_

- [x] 5. Backend verification
  - [x] 5.1 Integration tests: shipment lifecycle
    - Testcontainers: create (stage change to `IN_DELIVERY`, cap, order-line cap), update, manual ETA and
      revert, cancel with reversal, project scope 404, ABAC per role.
    - _Requirements: 3, 4, 5, 6, 11, 16, 17_
    - _Depends on: 3.10_
  - [x] 5.2 Integration tests: tracking, upcoming, overlay, sweep
    - Refresh with `STUB` (cooldown 429, failure → `FAILED`, back-off), scheduler selection query,
      `upcoming` extension, overlay endpoint, deadline sweep idempotence.
    - _Requirements: 7, 9, 10, 12, 13, 14_
    - _Depends on: 3.10, 3.12, 3.13, 3.14_
  - [x] 5.3 Property tests
    - jqwik: cap invariant, effective-ETA invariant, order-by one-shot, back-off monotonic and capped.
    - _Requirements: 25.2_
    - _Depends on: 3.2, 3.6_
  - [x] 5.4 Grants integration test
    - `SHIPMENTS` grants per role and idempotent re-run of 242.
    - _Requirements: 1_
    - _Depends on: 1.2, 3.10_

- [x] 6. Frontend (inner repo `foremen-frontend/`)
  - [x] 6.1 API layer
    - `features/shipments/api` (types mirroring `ShipmentDto`, client, query keys), `useShipments`,
      mutations, `useRefreshCooldown`; `upcomingApi` and `overlayApi` types.
    - _Requirements: 16, 20_
    - _Depends on: 3.10_
  - [x] 6.2 Locale keys
    - Add every key of Requirement 23 and the design (statuses, methods, carriers, shipments,
      upcomingDeliveries, scheduleOverlay, nav, four notifications) to `pl.json` and `ru.json` at parity.
    - _Requirements: 23_
  - [x] 6.3 Shipments page, table and filters
    - `ShipmentsPage`, `ShipmentFilters`, `ShipmentTable`, `ShipmentStatusChip`, URL-synced state,
      degraded badge, quick actions; route `:id` opens the drawer.
    - _Requirements: 20.1, 20.5, 20.6_
    - _Depends on: 6.1, 6.4, 6.5_
  - [x] 6.4 Create dialog and "Add shipment" entry
    - `ShipmentFormDialog` (project, optional order, positions with remaining quantity, method fields,
      API errors at fields); "Add shipment" button on the `PAID` external-order detail.
    - _Requirements: 20.2, 20.5_
    - _Depends on: 6.1, 6.2_
  - [x] 6.5 Detail drawer and manual actions
    - `ShipmentDrawer`, `ManualEntryForm`, `RefreshButton` with cooldown, revert ETA, cancel with
      confirmation, event history, carrier link, manual / degraded notices; read-only for FOREMAN.
    - _Requirements: 20.3, 20.4, 20.5_
    - _Depends on: 6.1, 6.2_
  - [x] 6.6 Foreman upcoming deliveries screen
    - `UpcomingDeliveriesPage`, `DeliveryCard`, grouping by date with an overdue group, days selector,
      `tel:` and tracking links, `?shipment=` highlight, empty / loading / error states, 360 px layout.
    - _Requirements: 21_
    - _Depends on: 3.12, 6.2_
  - [x] 6.7 Gantt materials overlay
    - `MaterialsOverlayToggle` in `ScheduleTab` and `PlanActualPanel`, `MaterialsOverlayLane`,
      `overlayGeometry` over the existing timeline, optional `overlay` prop on `GanttRow`, legend, fail
      soft, FOREMAN default hides order-by markers; DOM unchanged when off.
    - _Requirements: 22_
    - _Depends on: 3.13, 6.2_
  - [x] 6.8 Routes, navigation and bottom bar
    - `route-permissions.ts`, `router.tsx`, `DeliveriesSectionNav` "Shipments" tab, `navigation.ts` item
      `upcomingDeliveries` and the FOREMAN bottom-bar slot; update `navigation.test.ts` and
      `BottomNav.test.tsx`.
    - _Requirements: 19_
    - _Depends on: 6.3, 6.6_
  - [x] 6.9 Frontend tests
    - Forms and cooldown, upcoming grouping and deep link, overlay toggle and fail-soft, route
      permissions, locale parity test for the new keys (copy the FOR-11-03 parity test).
    - _Requirements: 25.4_
    - _Depends on: 6.3, 6.4, 6.5, 6.6, 6.7, 6.8_

- [x] 7. Documentation
  - [x] 7.1 Author test-cases.md
    - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
      (feature grouping, step-by-step scenarios, repeatability via generator / clean-up, regression group,
      MD report template). Scenarios are API tests against the Dockerized app (Part A) and browser-engine
      scenarios (Part B), using the `STUB` carrier adapter. Result artifacts are MD reports with tables.
    - _Requirements: 25.5, 25.6_
    - _Depends on: 3.10, 6.8_
  - [x] 7.2 Update `OVERVIEW.md`
    - Mark child 04 "Spec ready" with the decisions made here (shipment model, SPI, spike verdicts,
      new edge, `upcoming` extension, overlay, four notifications, changesets 241 to 243).
    - _Requirements: 25_
    - _Depends on: 2.4, 6.8_

- [x] 8. Final checkpoint
  - Run the affected backend test classes only (shipment, tracking, synchronizer, policy, upcoming,
    overlay, sweep, dataset, messages parity), the affected frontend tests, `compileJava` and
    `compileTestJava`; confirm Requirement 26 (non-regression). Ask the user before any full-suite run.
  - _Requirements: 25, 26_
  - _Depends on: 5.1, 5.2, 5.3, 5.4, 6.9, 7.1, 7.2_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "2.2", "2.3", "3.2", "3.3", "3.5", "6.2"] },
    { "id": 1, "tasks": ["1.2", "2.4", "3.1", "3.4"] },
    { "id": 2, "tasks": ["1.3", "3.6", "3.7", "3.12", "3.15", "4.1"] },
    { "id": 3, "tasks": ["3.8", "3.13", "6.6"] },
    { "id": 4, "tasks": ["3.9", "3.11", "3.14", "5.3", "6.7"] },
    { "id": 5, "tasks": ["3.10"] },
    { "id": 6, "tasks": ["5.1", "5.2", "5.4", "6.1"] },
    { "id": 7, "tasks": ["6.4", "6.5"] },
    { "id": 8, "tasks": ["6.3"] },
    { "id": 9, "tasks": ["6.8"] },
    { "id": 10, "tasks": ["6.9"] },
    { "id": 11, "tasks": ["7.1", "7.2"] },
    { "id": 12, "tasks": ["8"] }
  ]
}
```
