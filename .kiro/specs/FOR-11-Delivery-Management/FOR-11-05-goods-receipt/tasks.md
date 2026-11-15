# Implementation Plan: FOR-11-05 — Goods receipt on site

## Overview

Implements the append-only `GoodsReceipt` event (`ACCEPTED`, `MISSING`, `RETURNED`), the quantity ledger,
position status derivation through the existing SYSTEM edges, automatic re-delivery of released units,
shipment closure, receipt photos, one notification, one dataset and the mobile-first foreman receipt
screen. Implementation language: Java (backend, Spring Boot) and TypeScript / React (frontend), as in the
design.

- **Reuse:** `PositionLifecycleService` and `PositionTransitionPolicy` (no new edge), `ShipmentService`
  and `ShipmentRules` (the `capFor` seam), `PlannedDeliveryDateSynchronizer`, `ProcurementProjectAccess`,
  `ImageStorage` with the `RoomMediaService` pattern, `ImageReferenceLookup`,
  `ShipmentNotificationEmitter` (pattern), `UserDao.findActiveByRoleCode`, `DatasetRegistry`, the
  FOR-11-04 navigation and upcoming-deliveries card.
- **Spans two git repos:** backend, specs and infrastructure commit from the workspace root; all
  `foremen-frontend/` changes commit from inside `foremen-frontend/` (set the tool `cwd` to
  `foremen-frontend`; the root repo ignores that directory, so a root commit never contains frontend
  source). Stage specific files, push only to a new branch, never force-push. Commit only when asked.
  Before reporting a feature done, check `git status` in both repos.
- **Migrations:** changesets **244** (`goods_receipts`, `goods_receipt_media`, `shipments.origin_receipt_id`)
  and **245** (`GOODS_RECEIPTS` resource and grants), each registered last in `changelog.xml` after 243.
- **Test-run policy (workspace rule):** run only the affected test classes with `--tests`, redirect output
  to a temp log and read the JUnit XML. Never run the full suite unless the user asks (about 20 minutes).
  Example:
  `./gradlew test --tests "com.foremen.service.procurement.receipt.GoodsReceiptRulesTest" > /tmp/for-11-05-test.log 2>&1; echo "EXIT=$?"`,
  then read `/tmp/for-11-05-test.log` or
  `foremen-backend/build/test-results/test/TEST-<fqcn>.xml`. Compile-only checks use
  `./gradlew compileJava compileTestJava` (also redirected to a temp log). Frontend tests run with
  `vitest --run` on the affected files only.
- **Shared-code changes (minimal):** `ShipmentService`, `ShipmentEntity` (one nullable column),
  `ImageReferenceLookup`, `PurchasePositionController` (one `GET /{id}/ledger` handler),
  `messages.properties` / `messages_ru.properties`, `changelog.xml`, `application.yml`, `navigation.ts`,
  `route-permissions.ts`, `router.tsx`, the upcoming-deliveries card, the planner and kanban drawers,
  `pl.json` / `ru.json`. `PositionTransitionPolicy` and `ShipmentRules` are NOT edited.
- **Entity checklist:** steps 1 to 5 of `.kiro/steering/entity-creation-rules.md` are covered by tasks
  1.2, 2.9 and 2.10 (design section 8 records the deliberate `ShipmentService`-style scoping).

## Tasks

- [x] 1. Database
  - [x] 1.1 Create `244-create-goods-receipts.xml`
    - Tables `goods_receipts` (CHECKs on kind, quantity, `from_accepted`, void columns; partial unique
      index on `(client_request_id, part_no)`) and `goods_receipt_media`; indexes per design section 1;
      second changeSet adds `shipments.origin_receipt_id` (FK `ON DELETE SET NULL`, indexed) behind a
      column-exists `MARK_RAN` precondition; register last in `changelog.xml` after 243.
    - _Requirements: 2.1, 2.2, 2.3, 2.5_
  - [x] 1.2 Create `245-seed-goods-receipts-resource.xml`
    - Resource `GOODS_RECEIPTS` (RU/PL name and description) and the grants of Requirement 1.2 in a second
      changeSet, both guarded by `onFail="MARK_RAN"` `sqlCheck` preconditions (pattern of 242); register
      last in `changelog.xml` after 244.
    - _Requirements: 1.1, 1.2, 14.1_
    - _Depends on: 1.1_

- [x] 2. Backend
  - [x] 2.1 Enum, pure rules and properties
    - `ReceiptKind`; `GoodsReceiptRules` (`ledger`, `deriveStatus`, `validateEvent`, `canVoid`,
      `lineResolved`, `redeliveryQuantity`, `recipients`, `validateMedia`, records `Inputs` / `Ledger`);
      `GoodsReceiptProperties` (`foremen.goods-receipt`: media limits, `void-window`) with defaults in
      `application.yml`. No Spring in the rules class.
    - _Requirements: 3.1, 3.2, 3.4, 3.5, 3.6, 4.3, 4.4, 4.5, 4.6, 4.7, 5.2, 6.1, 7.2, 8.4, 8.6, 12.2_
  - [x] 2.2 Entities and DAOs
    - `GoodsReceiptEntity`, `GoodsReceiptMediaEntity`, `GoodsReceiptDao` (sums by position and kind,
      `releasedByOrder`, `existsActiveByPositionId`, FIFO line lookup, idempotency lookup, Specification
      support), `GoodsReceiptMediaDao`; map `ShipmentEntity.originReceiptId`.
    - _Requirements: 2.1, 2.2, 2.4, 8.5, 8.7_
    - _Depends on: 1.1, 2.1_
  - [x] 2.3 Backend messages
    - All `error.goodsReceipt.*`, `error.shipment.hasReceipts` and `error.shipment.position.hasReceipts`
      keys of design section 9 in `messages.properties` (PL) and `messages_ru.properties` (RU), plus a
      parity test extension.
    - _Requirements: 13.2, 15.2_
  - [x] 2.4 `GoodsReceiptLedgerService`
    - `ledgers(positionIds)` in three queries, `committed(positionIds, excludeShipmentId)`,
      `releasedByOrder(orderId, positionIds)`; cap through the unchanged `ShipmentRules.capFor`; no money
      read or returned.
    - _Requirements: 3.1, 3.2, 3.6, 8.1_
    - _Depends on: 2.2_
  - [x] 2.5 `ShipmentService` extensions
    - Cap check uses `ledgerService.committed(...)`; order-line check subtracts `releasedByOrder`;
      `moveToInDelivery` also moves `PARTIALLY_RECEIVED -> IN_DELIVERY`; `reverseToSupplierPaid` refuses
      when receipt events exist; `cancel` refuses a shipment with receipt rows;
      `validateLines` refuses a position with receipt events and no shipment line; new
      `createRedelivery(positionId, quantity, originReceiptId)` (no `ShipmentCreatedEvent`) and
      `closeByReceipt(shipmentId)`. Existing FOR-11-04 expectations stay unchanged.
    - _Requirements: 8.1, 8.2, 8.3, 8.6, 8.7, 8.8, 17.1_
    - _Depends on: 2.2, 2.3, 2.4_
  - [x] 2.6 `GoodsReceiptService`
    - `record` (idempotency by `clientRequestId` and `part_no`, position locks ascending, stage check,
      per-event ledger validation, FIFO allocation with split, `acceptedQuantity` write, status
      derivation through `PositionLifecycleService` SYSTEM, closure, one re-delivery per position,
      discrepancy events, audit), `void` (permission, status and `acceptedQuantity` recompute,
      re-delivery cancel when unchanged), `list`, `get`, `toReceive`, per-row `canVoid`; project scoping
      with 404 outside scope; no DTO carries money.
    - _Requirements: 1.4, 3.3, 3.4, 4.1, 4.2, 4.8, 4.9, 4.10, 4.11, 5.1, 5.2, 5.3, 5.4, 5.5, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 8.2, 8.4, 8.5, 8.6, 9.1, 9.2, 9.3, 9.5, 13.3_
    - _Depends on: 2.4, 2.5, 2.8_
  - [x] 2.7 `GoodsReceiptMediaService` and image registration
    - Upload (declared type plus magic bytes, limits, nothing stored on rejection), content bytes through
      `ImageStorage.readObject` (503 when storage is off), delete with `deleteIfOrphan` after commit,
      audit; register `goods_receipt_media.storage_uri` in `ImageReferenceLookup.IMAGE_COLUMNS`; DTO
      carries id, file name, size and content type only.
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 13.3_
    - _Depends on: 2.2, 2.3_
  - [x] 2.8 `GoodsReceiptDiscrepancyEvent` and `GoodsReceiptNotificationEmitter`
    - Event record; emitter with `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`,
      recipients = active project MANAGER members plus active BUYER users, de-duplicated, minus the actor;
      type `notification.receipt.discrepancy`, link `/deliveries/receipt?position={id}`; never throws.
    - _Requirements: 12.1, 12.2, 12.3, 12.4_
    - _Depends on: 2.1, 2.3_
  - [x] 2.9 `GoodsReceiptController`, DTOs and ledger endpoint
    - `/api/goods-receipts` with `@PermissionResource("GOODS_RECEIPTS")` and explicit
      `@RequiresPermission` on every handler (create, list, get, `to-receive`, void, media upload, content,
      delete); `GET /api/purchase-positions/{id}/ledger` in `PurchasePositionController` with
      `@RequiresPermission(resource = "GOODS_RECEIPTS", operation = "READ")`; DTOs under `controller/model`;
      `PermissionAnnotationValidator` must pass at startup.
    - _Requirements: 1.3, 1.5, 9.4, 13.1, 13.2_
    - _Depends on: 2.6, 2.7_
  - [x] 2.10 `GoodsReceiptsDataset`
    - Dataset class and registration (code `GOODS_RECEIPTS`, project path `project.id`, date field
      `occurredAt`, fields per design section 8, computed `voided`, company-wide predicate for BUYER);
      no comment, void reason, storage or request-id fields; `DatasetCoverageTest` and
      `SensitiveFieldGuardTest` stay green.
    - _Requirements: 14.2, 14.3_
    - _Depends on: 1.2, 2.2_

- [x] 3. Backend verification
  - [x] 3.1 Property tests for the pure rules
    - jqwik `GoodsReceiptRulesPropertyTest`, one `@Property` per property, tagged
      `Feature: FOR-11-05-goods-receipt, Property N`. Properties 1, 2, 3, 4, 6, 7, 8, 11, 12 and 13
      (Properties 4 and 6 call the real `PositionTransitionPolicy` and `ShipmentRules.capFor`).
    - _Requirements: 3.1, 3.2, 3.4, 4.3, 4.5, 4.6, 4.7, 5.2, 6.1, 6.2, 7.2, 8.1, 8.2, 8.4, 8.6, 12.1, 12.2, 12.3, 17.1_
    - _Depends on: 2.1_
  - [x] 3.2 Unit tests for `GoodsReceiptRules`
    - `GoodsReceiptRulesTest`: worked-example table of design section 2 (cap 3 cases), every error key,
      batch ordering `[ACCEPTED 1, RETURNED 1, MISSING 1]`, void predicate, media limits.
    - _Requirements: 3.2, 4.3, 4.4, 4.7, 5.2, 7.2_
    - _Depends on: 2.1_
  - [x] 3.3 Service tests, including Properties 5, 9 and 10
    - `GoodsReceiptServiceTest` over mocked or in-memory DAOs: FIFO split, closure, re-delivery quantity,
      void with and without cancelling the re-delivery, status at `RECEIVED` with a later return,
      scope 404, idempotent retry (Property 9), batch atomicity (Property 10), void equals removal and
      `acceptedQuantity` in step with the ledger (Property 5).
    - _Requirements: 3.3, 4.1, 4.8, 5.1, 5.3, 5.4, 5.5, 6.3, 8.2, 8.4, 8.6_
    - _Depends on: 2.6_
  - [x] 3.4 `ShipmentService` additions
    - Extend `ShipmentServiceTest` only with new tests (`committed`, `moveToInDelivery`,
      `reverseToSupplierPaid` guard, `cancel` guard, position-has-receipts guard, `createRedelivery`
      without `ShipmentCreatedEvent`, `closeByReceipt`); old expectations untouched.
    - _Requirements: 8.1, 8.2, 8.3, 8.6, 8.7, 17.1_
    - _Depends on: 2.5_
  - [x] 3.5 Emitter and media tests
    - `GoodsReceiptNotificationEmitterTest` (recipients, link, no emission for clean `ACCEPTED` and void,
      failure isolation); `GoodsReceiptMediaServiceTest` (magic bytes, limits, nothing stored on
      rejection, `ImageReferenceLookup` reports a stored key as referenced); DTO reflection guard (no
      price, amount, VAT, storage key); message-file parity.
    - _Requirements: 7.2, 7.5, 9.5, 12, 15.2, 16.2_
    - _Depends on: 2.7, 2.8_
  - [x] 3.6 Integration tests (Testcontainers)
    - Record, batch, retry with the same `clientRequestId`, void, re-delivery, closure, ledger endpoint,
      permission matrix per role (FOREMAN create and read, BUYER read only, FINANCIER and CLIENT 403),
      project scope 404 for a foreman of another project, startup annotation validation, changesets 244
      and 245 (CHECKs, `MARK_RAN` re-run), dataset registration with `DatasetCoverageTest` and
      `SensitiveFieldGuardTest`, FOR-11-04 shipment and position transition classes run unchanged.
    - _Requirements: 1, 2, 3, 4, 5, 6, 8, 9, 13, 14, 16.1, 16.2, 17_
    - _Depends on: 1.2, 2.9, 2.10_

- [x] 4. Frontend (inner repo `foremen-frontend/`)
  - [x] 4.1 Locale keys
    - `goodsReceipt.*`, `nav.goodsReceipt` and `notification.receipt.discrepancy` in `pl.json` and
      `ru.json` at parity; add `locale-parity.for1105.test.ts` (copy the FOR-11-04 parity test).
    - _Requirements: 15.1, 15.3_
  - [x] 4.2 API layer and helpers
    - `features/goods-receipt/api` (types mirroring the DTOs, client, query keys), hooks
      (`useToReceive`, `useReceiptHistory`, `useReceiptMutations`), `lib/validateReceipt.ts`,
      `lib/downscaleImage.ts` (1600 px, JPEG 0.8), `lib/newRequestId.ts`; ledger types for the planner.
    - _Requirements: 4.8, 9, 10.3, 10.5_
  - [x] 4.3 Receipt screen: To receive tab
    - `GoodsReceiptPage`, `ToReceiveList`, `PositionReceiptCard`, `LedgerBar`, `ReceiveSheet`,
      `AcceptAllDialog`; retry with the same request ids; card updates from the batch result; deep link
      `?position=` scroll and highlight; loading, error and retry states; 360 px layout; no money.
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.8_
    - _Depends on: 4.1, 4.2_
  - [x] 4.4 Receipt screen: history, void and photos
    - `ReceiptHistory`, `VoidDialog` (reason required, shown only when the DTO says `canVoid`),
      `PhotoPicker` (`accept="image/jpeg,image/png,image/webp"`, downscale, upload after the event,
      "retry photo" only on failure), thumbnails through the content endpoint, voided rows struck through.
    - _Requirements: 7.4, 10.5, 10.7, 10.8_
    - _Depends on: 4.3_
  - [x] 4.5 Routes, navigation and permissions
    - `route-permissions.ts` (`/deliveries/receipt` -> `GOODS_RECEIPTS` READ), lazy route in `router.tsx`,
      `navigation.ts` item `goodsReceipt` and the FOREMAN bottom profile with five slots; extend (do not
      replace) `navigation.test.ts` and `BottomNav.test.tsx`; other roles unchanged.
    - _Requirements: 10.9, 11.1, 11.4, 17.4_
    - _Depends on: 4.1, 4.3_
  - [x] 4.6 Upcoming-deliveries action and planner ledger
    - **Receive** button on `DeliveryCard` (permission `GOODS_RECEIPTS` CREATE and receivable status,
      links to `/deliveries/receipt?position={id}`); read-only `LedgerPanel` with the event list in the
      planner and kanban position drawers.
    - _Requirements: 11.2, 11.3_
    - _Depends on: 4.1, 4.2_
  - [x] 4.7 Frontend tests
    - Route guard, navigation and bottom-bar slots, `validateReceipt` against the server examples, receive
      sheet flows, accept-all batch, retry with the same request ids, photo retry, deep link highlight,
      void dialog visibility, upcoming card button, ledger panel.
    - _Requirements: 10, 11, 16.1_
    - _Depends on: 4.3, 4.4, 4.5, 4.6_

- [x] 5. Documentation
  - [x] 5.1 Author test-cases.md
    - Create `test-cases.md` in the spec folder, in Russian, following the `.kiro/steering/test-cases.md`
      standard (feature grouping, step-by-step scenarios, repeatability via run-id generator / clean-up,
      regression group, MD report template). Part A: API scenarios against the Dockerized app (the `STUB`
      carrier adapter keeps tracking off); Part B: browser-engine scenarios for the receipt screen.
      Result artifacts are MD reports with tables, not screenshots.
    - _Requirements: 16.3_
    - _Depends on: 2.9, 4.5_
  - [x] 5.2 Update `OVERVIEW.md`
    - Mark child 05 "Spec ready" with the decisions made (event model, ledger formulas, re-delivery
      `min(remainder, released)`, shipment closure, `GOODS_RECEIPTS` resource, changesets 244 and 245,
      corrections to requirements.md listed in the design).
    - _Requirements: 16_
    - _Depends on: 2.9, 4.5_

- [x] 6. Final checkpoint
  - Run only the affected backend test classes (rules, property, service, shipment additions, emitter,
    media, integration, dataset, messages parity), the affected frontend tests, `compileJava` and
    `compileTestJava`, all redirected to temp logs; confirm Requirement 17 (non-regression); check
    `git status` in both repos. Ask the user before any full-suite run.
  - _Requirements: 16, 17_
  - _Depends on: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 4.7, 5.1, 5.2_

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP; task 3.1 (property tests) is
  required because the ledger and status rules are the core of the spec.
- Each task references specific requirements for traceability.
- Waves keep `ShipmentService` (2.5, then its tests 3.4), `changelog.xml` (1.1, then 1.2), `messages*.properties`
  (2.3 only) and `pl.json` / `ru.json` (4.1 only) single-writer per wave.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "2.3", "4.1", "4.2"] },
    { "id": 1, "tasks": ["1.2", "2.2", "2.8", "3.1", "3.2"] },
    { "id": 2, "tasks": ["2.4", "2.7", "2.10", "4.3", "4.6"] },
    { "id": 3, "tasks": ["2.5", "3.5", "4.4", "4.5"] },
    { "id": 4, "tasks": ["2.6", "3.4", "4.7"] },
    { "id": 5, "tasks": ["2.9", "3.3"] },
    { "id": 6, "tasks": ["3.6", "5.1", "5.2"] }
  ]
}
```
