# Implementation Plan

Notes for the whole plan:

- Two repositories (see `.kiro/steering/git-repo-structure.md`): backend, migration and spec changes are committed from the root repo; frontend changes (`foremen-frontend/src/**`) are committed from inside `foremen-frontend/`. Commit only when the user asks.
- Backend tests: run only the affected classes with `--tests`, redirect to `/tmp/for-05-07b-test.log` (`> /tmp/for-05-07b-test.log 2>&1; echo "EXIT=$?"`), and read the result from `foremen-backend/build/test-results/test/TEST-<fqcn>.xml` (`.kiro/steering/test-execution-rules.md`). Never run the full suite unless the user asks (about 20 minutes). Frontend: `npx vitest --run <affected files>` in `foremen-frontend/`.
- Liquibase: this spec uses changeset number **267**. Spec FOR-05-02c uses 266 in parallel. At the time of writing, the highest file in `foremen-backend/database_files/changesets/` is 265, and 267 is free. If 267 is taken when task 3.1 is edited, take the next free number and state the number used in the task result and in `design.md` (B3 and Data Models).
- Assumptions A1 to A7 are listed in `design.md` ("Assumptions to confirm with the user"). Tasks 3.3, 3.4 and 3.7 reference them so they can be adjusted if the answers differ.

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Offer snapshot is empty, stale, unusable or missing
  - **CRITICAL**: These tests MUST FAIL on unfixed code. Failure confirms the bug exists.
  - **DO NOT attempt to fix the tests or the code when they fail.**
  - **NOTE**: The tests encode the expected behavior, so they validate the fix when they pass after task 3.
  - **GOAL**: Surface counterexamples for `isBugCondition(input)` of `design.md` (Bug Condition section).
  - **Scoped PBT Approach**: The bugs are deterministic, so scope each property to the concrete failing case.
  - Backend (new classes, so existing tests are not edited before the fix):
    - `OfferSnapshotRefreshExplorationTest` (integration, Testcontainers, same setup as `OfferSnapshotIntegrationTest`): prepare an offer on an empty estimate, add a line with a room quantity, send the offer, read the snapshot. Assert the snapshot has the row and its totals equal the live offer totals. Expected counterexample: `rooms: []`, `groups: []`, totals 0 against a live total above 0 (the offer 261 shape of project 1531).
    - `OfferSnapshotBuilderExplorationTest` (unit): an estimate with one line without room cells. Assert the line is absent from the snapshot and no empty group or room remains. Expected counterexample: a row with `cells = []`.
    - Same unit class: an estimate with `appliedPackageCode` set. Assert the snapshot carries the code and names. Expected counterexample: no such field.
    - `OfferMultiOfferExplorationTest` (integration): prepare twice for one project. Assert both succeed and two non-terminal offers exist. Expected counterexample: 409 `error.offer.active.exists`.
    - Same integration class: `GET /api/offers/{id}/pdf` and `POST /api/offers/{id}/restore` expect 200 for an offer with a snapshot. Expected counterexample: 404 (no endpoint).
  - Frontend (new file `foremen-frontend/src/features/estimate/__tests__/EstimateTab.prepareOfferName.exploration.test.tsx`): click "Przygotuj ofertę" and expect a name dialog and no prepare call before confirm. Expected counterexample: the prepare mutation fires immediately with only the project id.
  - Run the backend classes and the frontend file on UNFIXED code.
  - **EXPECTED OUTCOME**: Tests FAIL (this is correct, it proves the bug exists).
  - Document the counterexamples found to confirm or refute the root causes of `design.md`.
  - Mark the task complete when the tests are written, run, and the failures are documented.
  - _Requirements: 1.1, 1.2, 1.4, 1.5, 1.6, 1.7, 1.8_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Behavior outside the defect conditions is unchanged
  - **IMPORTANT**: Follow the observation-first methodology.
  - Observe on UNFIXED code, then write tests that assert what was observed:
    - Status machine, role gates and audit rows of send, approve, reject, withdraw and negotiate (3.1): re-run `OfferStatusMachinePropertyTest` and `TerminalOfferImmutabilityPropertyTest` as they are.
    - CLIENT reads: DRAFT offer answers 404, no `baseUnitPrice`, `baseValueNet`, labour comparison or margin in list, snapshot and compare (3.2). New class `OfferSnapshotPreservationTest` (integration) records the current allow-listed JSON field set of `SnapshotView` and `OfferSummary` for CLIENT and asserts it is a subset of the future field set (additive only).
    - Legacy offer with `snapshot = NULL`: `hasSnapshot = false`, snapshot view reports no snapshot, compare is refused (3.3).
    - Prepare naming (3.4): trimmed name, default "Offer N" for a blank name, `error.offer.name.too.long` above 255. Re-run `OfferServicePrepareOfferPropertyTest` (the active-offer case is changed only in task 3.4).
    - Compare of two same-project snapshots returns added, removed and changed works and the total delta; different projects return 400 (3.5).
    - Frozen snapshot bytes: jqwik property in a new class `OfferFrozenSnapshotPreservationPropertyTest`. For any offer past DRAFT (SENT, APPROVED, REJECTED, WITHDRAWN) the stored snapshot JSON is identical before and after an estimate edit and after the status transitions of the same offer.
    - Single-offer estimate reaction (3.6): `OfferEstimateReactionServiceTest` single-offer cases as they are.
    - Proforma and signing PDFs use `PdfRenderer` unchanged (3.8): re-run the existing proforma PDF test.
  - Frontend: record `WorksRoomsMatrix.test.tsx`, `OfferTab.test.tsx` and `OfferVersions.test.tsx` as the baseline (3.7). No edits before the fix.
  - Run the tests on UNFIXED code.
  - **EXPECTED OUTCOME**: Tests PASS (this confirms the baseline behavior to preserve).
  - Mark the task complete when the tests are written, run, and passing on unfixed code.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

- [x] 3. Fix for offer snapshots (empty or stale snapshot, no matrix view, no PDF, no restore, one offer only, no name dialog, no package marker)

  - [x] 3.1 Database: drop the one-active-offer index (changeset 267)
    - Create `foremen-backend/database_files/changesets/267-drop-offers-active-project-index.xml` (use the next free number if 267 is taken at edit time and say so in the task result and in `design.md`). One changeSet with `<preConditions onFail="MARK_RAN"><indexExists indexName="ux_offers_active_project" tableName="offers"/></preConditions>` running `DROP INDEX IF EXISTS ux_offers_active_project;`.
    - Rollback recreates the unique partial index of changeset 128 (`ON offers (project_id) WHERE status NOT IN ('APPROVED','REJECTED','WITHDRAWN')`), guarded by a precondition that no project has two non-terminal offers.
    - Second changeSet creates the plain index `ix_offers_project_status` on `offers (project_id, status)` with a NOT `indexExists` precondition and `MARK_RAN`.
    - Register the file LAST in `foremen-backend/database_files/changelog.xml`, after `265-standard-work-sets-update-grant.xml`.
    - Update `OfferApprovalMigrationIntegrationTest.partialUniqueIndexEnforcesOneActiveOfferPerProject` to assert that the index is gone, that applying the changeset twice is a no-op, and that two non-terminal offers of one project can be inserted.
    - Verify with `./gradlew compileJava` (log to `/tmp/for-05-07b-compile.log`) and the one test class.
    - _Bug_Condition: isBugCondition(input) where input.action == PREPARE AND nonTerminalOfferCount(input.project) >= 1_
    - _Expected_Behavior: Property 5 (design) - n + 1 non-terminal offers can exist_
    - _Preservation: 3.1, 3.4_
    - _Requirements: 1.5, 2.5, 3.1_

  - [x] 3.2 Snapshot v2 content and skipping rows applied to no room
    - `OfferSnapshotBuilder`: set `VERSION = 2`; readers keep accepting version 1 (`ignoreUnknown`).
    - `OfferSnapshot`: add `appliedPackageCode`, `appliedPackageNameRu`, `appliedPackageNamePl` (nullable); `Row` gets `unitPriceOverride`, `comment`, `workPriceId`, `lineNo` (nullable where applicable). `unitPrice` and `baseUnitPrice` keep their meaning. `Cell` is unchanged.
    - `OfferSnapshotBuilder.build(...)`: take the package data resolved by the caller (`OfferPackageDao.findByCode`, null-safe); skip a line without a cell of positive quantity; create a group only when a row survives; register a room only from surviving rows.
    - Update the `prepareOffer` call site only as far as needed to pass the package data (the lifecycle change is in task 3.3).
    - Add `OfferSnapshotBuilderTest` (unit) and `OfferSnapshotBuilderPropertyTest` (jqwik): every emitted row has a cell, every group has a row, every cell room is in `rooms`, the sum of cell quantities equals the row quantity, override, comment, work price id and package marker are stored, a null package is handled.
    - _Bug_Condition: isBugCondition(input) where input.action == BUILD AND appliedToNoRoom(line), and appliedPackageCode != NULL AND NOT packageMarkerPresent(offer)_
    - _Expected_Behavior: Property 2 and Property 8 (design) - only applied rows, package marker stored_
    - _Preservation: 3.3 (version 1 stays readable), 3.7_
    - _Requirements: 1.2, 1.6, 1.8, 2.2, 2.6, 2.8, 3.3_

  - [x] 3.3 Snapshot lifecycle: refresh on send, explicit refresh of a DRAFT, freeze afterwards
    - Assumptions: **A6** (explicit refresh is a user action on a DRAFT offer; no refresh on every estimate edit) and **A7** (a legacy DRAFT offer without a snapshot gets one on send or refresh; legacy offers in other statuses stay without one). If either answer differs, adjust only this task.
    - `OfferEntity.snapshot`: change `updatable = false` to updatable. Immutability moves to the service through one private `assertSnapshotWritable(offer)` that throws 409 `error.offer.illegal.transition` unless the status is `DRAFT` (the message key `error.offer.snapshot.frozen` is added in task 3.8).
    - Add `OfferService.refreshSnapshot(offerId)`: assert DRAFT, `recomputeTotals`, rebuild the snapshot from the live estimate, save.
    - `OfferService.send`: when the offer is DRAFT, run the same refresh before the status transition, in the same transaction.
    - Add `POST /api/offers/{offerId}/refresh-snapshot` to `OfferController` with `@RequiresPermission(OFFERS, UPDATE)`; 409 when the offer is not DRAFT.
    - Tests: `OfferSnapshotRefreshIntegrationTest` (project 1531 shape: prepare, fill, send, assert snapshot equals live totals; refresh; 409 after send), `OfferFrozenSnapshotPropertyTest` (the snapshot changes only while DRAFT).
    - _Bug_Condition: isBugCondition(input) where input.action IN {VIEW, LIST, SEND} AND offer.status == DRAFT AND snapshotIsStale(offer)_
    - _Expected_Behavior: Property 1 (design) - snapshot equals the estimate at send time and is frozen afterwards_
    - _Preservation: 3.1 (status machine), 3.3_
    - _Requirements: 1.1, 2.1, 3.1, 3.3_

  - [x] 3.4 Several non-terminal offers per project
    - Assumptions: **A2** (approving one offer withdraws every other non-terminal offer of the project in the same transaction) and **A4** (the estimate-change reaction runs for each affected offer). If A2 differs (for example siblings stay open), change only the approve step of this task and the matching test.
    - `OfferService.prepareOffer`: remove the `findByProjectIdAndStatusNotIn` check and the 409. Remove the constant `ACTIVE_OFFER_EXISTS_MESSAGE` once no reference remains.
    - `OfferService.approve`: after the transition, load the other non-terminal offers of the project and withdraw each one (`offerStatusMachine.transition(status, WITHDRAW, SYSTEM_ACTOR)`), save, write one audit row per offer with operation `SYSTEM_WITHDRAW_ON_APPROVE` and the approved offer id.
    - `OfferEstimateReactionService`: `withdraw` acts on every offer in `WITHDRAWABLE` (one audit row per offer) instead of the one with the highest id. `Reaction` keeps the first and last offer id for the existing listener and gets an additional list of withdrawn ids. `revise` is unchanged. DRAFT offers are left alone.
    - `OfferService.getCurrentOfferByProject` is kept and returns the latest non-terminal offer, else the latest offer. Update the javadoc of `OfferDao.findByProjectIdAndStatusNotIn`.
    - Update the existing tests: `OfferServiceIntegrationTest` (second prepare succeeds), `OfferServicePrepareOfferPropertyTest` (no active-offer rejection), `OfferEstimateReactionServiceTest` (multi-offer withdraw, DRAFT untouched, audit row per offer).
    - Add `OfferMultiOfferIntegrationTest` (two SENT offers, approve one, the other is WITHDRAWN with an audit row; an estimate change withdraws both seen offers) and `OfferMultiOfferPropertyTest` (for any sequence of prepare, send, withdraw and approve, after an approve there is at most one APPROVED offer and all others are terminal).
    - _Bug_Condition: isBugCondition(input) where input.action == PREPARE AND nonTerminalOfferCount(input.project) >= 1_
    - _Expected_Behavior: Property 5 (design)_
    - _Preservation: 3.1, 3.4, 3.6 (reaction rules stay, applied per offer)_
    - _Requirements: 1.5, 2.5, 3.1, 3.4, 3.6_

  - [x] 3.5 Snapshot read model with package marker
    - `OfferSnapshotViews`: `OfferSummary` and `SnapshotView` get `appliedPackageCode` and `appliedPackageName` (localized from the snapshot, null when none or no snapshot). `Comparison` sides get the package marker.
    - CLIENT redaction stays allow-list based: the package marker is added to the allow-list; `unitPriceOverride`, `workPriceId` and `lineNo` are NOT exposed to CLIENT.
    - `OfferSnapshotService`: filter rows without cells when building a view (read-time guard for stored version 1 snapshots; no data migration).
    - Tests: extend `OfferSnapshotIntegrationTest` (marker present only when the snapshot has a package, redaction, empty-row guard, compare with a marker on both sides); `OfferSnapshotPreservationTest` from task 2 must still pass.
    - _Bug_Condition: isBugCondition(input) where input.action IN {PREPARE, VIEW, LIST, COMPARE} AND appliedPackageCode != NULL AND NOT packageMarkerPresent(offer)_
    - _Expected_Behavior: Property 8 (design)_
    - _Preservation: 3.2 (CLIENT redaction), 3.3, 3.5_
    - _Requirements: 1.2, 1.8, 2.2, 2.8, 3.2, 3.3, 3.5_

  - [x] 3.6 Offer PDF endpoint
    - New `service/offer/OfferPdfService`, `OfferHtmlBuilder`, `OfferPdfLabels`. `OfferPdfService.render(offerId, role, language)` uses the same visibility rules as `OfferSnapshotService.snapshot` (404 for a DRAFT offer seen by a CLIENT, 404 `error.offer.snapshot.missing` when there is no snapshot), builds XHTML and calls `PdfRenderer.renderToPdf`. The PDF is generated on demand from the stored snapshot and is not stored.
    - Content: offer name, date, project name and address, package marker when present, a table grouped by work category (works as rows, rooms as columns, total column), totals net, VAT at the snapshot `vatRatePct`, gross. The CLIENT variant is built from the redacted view, so it cannot contain base prices, labour comparison or margins. Labels follow `ProformaPdfLabels.forLanguage` (pl, ru). Rendering failure maps to 502 `error.offer.pdf.failed`.
    - Endpoint `GET /api/offers/{offerId}/pdf` in `OfferSnapshotController`, `@RequiresPermission(OFFERS, READ)`, `application/pdf`, `Content-Disposition: attachment; filename="<name-slug>-<id>.pdf"`. `PdfRenderer`, `PdfFreezeService` and the proforma and signing code are not changed.
    - Tests: `OfferHtmlBuilderTest` (CLIENT variant has no base price strings, staff variant does, PL and RU labels, marker present or absent), integration cases in `OfferSnapshotIntegrationTest` (200 `application/pdf` with `%PDF` prefix, CLIENT 404 for DRAFT, 404 for a legacy offer), re-run the proforma PDF test.
    - _Bug_Condition: isBugCondition(input) where input.action == PDF AND NOT pdfAvailable(offer)_
    - _Expected_Behavior: Property 4 (design)_
    - _Preservation: 3.2, 3.3, 3.8_
    - _Requirements: 1.4, 2.4, 3.2, 3.3, 3.8_

  - [x] 3.7 Restore the estimate from a snapshot
    - Assumptions to keep adjustable: **A1** (restore replaces the whole live estimate in one transaction, writes an audit row, and is refused with 409 `error.estimate.locked` when the estimate is not DRAFT), **A4** (a restore changes the estimate, so the estimate-change reaction runs and seen offers, including the source offer when SENT, are withdrawn; the dialog shows this beforehand), **A5** (snapshot cells of rooms that no longer exist are skipped and reported in `skippedRooms`; works that no longer exist are skipped and counted), **A6** and **A7** (restore needs a version 2 snapshot; a legacy offer, including a legacy DRAFT, is refused until a snapshot exists). A3 applies: an offer without a snapshot has no restore. If an answer differs, adjust only this task and its test.
    - New `service/offer/OfferRestoreService` and `POST /api/offers/{offerId}/restore` in `OfferController`, `@RequiresPermission(OFFERS, UPDATE)`. Response: `{ restoredLines, skippedRooms[], estimateTotals }`.
    - One `@Transactional` method: resolve the offer; 404 `error.offer.snapshot.missing` when there is no snapshot or its version is below 2; `DraftGateGuard.assertDraft(estimate)` for 409 `error.estimate.locked`; delete the estimate lines and recreate them from the snapshot rows in `lineNo` order (work item by `workItemId`, `baseUnitPrice`, `unitPriceOverride`, `comment`, work price by `workPriceId` when it still exists, room quantities for rooms that still exist); set `estimate.appliedPackageCode` from the snapshot (null clears it); recompute through the existing `EstimateRecomputeService` (no totals are copied from the snapshot); write one `AuditLogEntity` (`entityClass = Offer`, `operation = RESTORE_ESTIMATE`, before and after JSON with counts and totals, acting user); publish `EstimateChangedEvent` with trigger `OFFER_RESTORE`.
    - Per-line chosen materials are not in the snapshot; the limit is recorded in the response notes and shown by the frontend dialog (task 3.12).
    - Tests: `OfferRestoreServiceTest` (locked estimate, no snapshot, version 1 snapshot, counts of skipped works and rooms), `OfferRestoreIntegrationTest` (restore into an edited DRAFT estimate, audit row, totals, 409 when locked, removed rooms in `skippedRooms`, reaction runs), `OfferSnapshotRestoreRoundTripPropertyTest` (snapshot, restore into a fresh DRAFT estimate, rebuild, equal rows, cells, prices, comments, package and totals).
    - _Bug_Condition: isBugCondition(input) where input.action == RESTORE_
    - _Expected_Behavior: Property 6 (design)_
    - _Preservation: 3.1, 3.3, 3.6, 3.7_
    - _Requirements: 1.6, 2.6, 3.1, 3.3, 3.6, 3.7_

  - [x] 3.8 Backend message keys (PL and RU)
    - `messages.properties` and `messages_ru.properties`: add `error.offer.snapshot.missing`, `error.offer.pdf.failed`, `error.offer.restore.locked` (reuse the `error.estimate.locked` text if present), `error.offer.snapshot.frozen`.
    - Remove `error.offer.active.exists` from both files only after `grep` shows no remaining reference in backend code and tests.
    - Run the backend i18n parity test if one exists (search `src/test` for a messages parity test before running).
    - _Bug_Condition: isBugCondition(input) where input.action IN {PDF, RESTORE, PREPARE} (new or removed error codes)_
    - _Expected_Behavior: Error Handling section of design_
    - _Preservation: 3.4 (`error.offer.name.too.long` stays)_
    - _Requirements: 2.4, 2.5, 2.6, 3.4_

  - [x] 3.9 Frontend API layer, types and package marker component (repo `foremen-frontend/`)
    - `features/project-offer/api/offer-api.ts` and `features/project-offer/types`: add `fetchOfferPdf(offerId): Promise<Blob>`, `restoreOffer(offerId)`, `refreshOfferSnapshot(offerId)`; `prepareOffer({ projectId, name? })` keeps sending the optional name; add `appliedPackageCode` and `appliedPackageName` to the summary, snapshot view and comparison types. Add hooks (`useRefreshOfferSnapshot`, `useRestoreOffer`) next to the existing offer hooks.
    - New `features/project-offer/components/PackageMarker.tsx`: a badge with the package name when `appliedPackageName` is set, nothing otherwise. Add `PackageMarker.test.tsx`.
    - _Bug_Condition: isBugCondition(input) where appliedPackageCode != NULL AND NOT packageMarkerPresent(offer), and input.action IN {PDF, RESTORE}_
    - _Expected_Behavior: Property 8 (design)_
    - _Preservation: 3.3, 3.7_
    - _Requirements: 1.4, 1.6, 1.8, 2.4, 2.6, 2.8, 3.3_

  - [x] 3.10 Snapshot matrix view through WorksRoomsMatrix, read-only (repo `foremen-frontend/`)
    - New pure adapter `features/project-offer/state/snapshotToMatrix.ts` that maps `SnapshotView` to the `EstimateMatrixDto` shape (groups to work-type groups, rows to work rows, cells to room cells, totals); staff-only labour and cost fields stay empty. Add `snapshotToMatrix.test.ts` (fast-check on the pure function: every row and cell is mapped, totals are preserved).
    - New `OfferSnapshotMatrix.tsx` renders `features/estimate/components/WorksRoomsMatrix.tsx` with `canEdit={false}` and no-op handlers, with the `PackageMarker` in its header. If the read-only render still shows controls that need the estimate store (assignment, hammer, report, staging), add a small `readOnly` presentational prop to `WorksRoomsMatrix` that hides them; the default behavior must not change. Cover it in `WorksRoomsMatrix.test.tsx`.
    - `OfferSnapshotPanel.tsx` becomes a wrapper that loads the snapshot and renders `OfferSnapshotMatrix`, so existing imports keep working.
    - _Bug_Condition: isBugCondition(input) where input.action == VIEW AND NOT isEstimateMatrixPresentation(viewOf(offer))_
    - _Expected_Behavior: Property 3 (design)_
    - _Preservation: 3.3, 3.7 (estimate matrix behavior unchanged)_
    - _Requirements: 1.2, 1.3, 2.2, 2.3, 2.8, 3.3, 3.7_

  - [x] 3.11 Offer PDF button (repo `foremen-frontend/`)
    - New `features/project-offer/components/OfferPdfButton.tsx` following the `ClientPaymentPdfButton` pattern: `fetchOfferPdf(offerId)`, open the blob in a new tab through `URL.createObjectURL`, revoke after 60 s, error toast `offers.pdf.error`. Hidden when `hasSnapshot` is false.
    - Add `OfferPdfButton.test.tsx` (click opens the blob, error toast, hidden for legacy offers).
    - _Bug_Condition: isBugCondition(input) where input.action == PDF AND NOT pdfAvailable(offer)_
    - _Expected_Behavior: Property 4 (design)_
    - _Preservation: 3.3, 3.8_
    - _Requirements: 1.4, 2.4, 3.3, 3.8_

  - [x] 3.12 Restore button with confirmation (repo `foremen-frontend/`)
    - Assumptions: **A1**, **A4**, **A5** and **A6/A7** as in task 3.7. The dialog text and the enable rules follow them and are the only parts to change if the answers differ.
    - New `features/project-offer/components/OfferRestoreButton.tsx`: an `AlertDialog` that states what is replaced, that chosen materials are re-derived, and the offer impact from `impactOf` (A4). Disabled with a tooltip when the estimate is locked (status not DRAFT, A1) or when the estimate tab has staged, unsaved edits (read the existing `estimateMatrixStore` / `projectStagedEdits` state). Visible only to executor roles (existing `usePermission` check for `OFFERS` update) and only when `hasSnapshot` is true.
    - After success: invalidate the estimate matrix, offers list and offer queries; toast with skipped rooms when `skippedRooms` is not empty (A5).
    - Add `OfferRestoreButton.test.tsx` (disabled when locked or staged edits exist, confirmation text, success invalidation, skipped rooms toast, hidden for legacy offers and CLIENT).
    - _Bug_Condition: isBugCondition(input) where input.action == RESTORE_
    - _Expected_Behavior: Property 6 (design)_
    - _Preservation: 3.3, 3.7_
    - _Requirements: 1.6, 2.6, 3.3, 3.7_

  - [x] 3.13 Offers tab with several offers, offer cards and per-offer actions (repo `foremen-frontend/`)
    - Assumptions: **A2** (approving one offer withdraws the others, so the list query is invalidated after approve and withdrawn siblings leave the awaiting list), **A6** (a "Refresh from estimate" action on DRAFT offers), **A7** (a legacy DRAFT offer shows refresh, other legacy offers show the "no snapshot" marker only).
    - New `OfferCard.tsx`: header, totals, `PackageMarker`, status, and its own actions (send, withdraw, approve, reject, refresh snapshot for DRAFT, View, PDF, Restore). The per-offer hooks (`useSendOffer(offerId, ...)` and the rest) move into the card.
    - `OfferTab.tsx`: stop holding a single `offerId`; use `fetchOfferList(projectId)` and render one `OfferCard` per non-terminal offer ("awaiting" list), then the history of terminal offers through `OfferVersions`. The "Prepare offer" block with the name input moves to the top and is always shown to executors. View opens `OfferSnapshotMatrix` from each offer row.
    - `OfferVersions.tsx`: show the `PackageMarker` per row; View, PDF and Restore appear for each row with a snapshot.
    - Update `OfferTab.test.tsx` and `OfferVersions.test.tsx` (several awaiting offers, per-offer actions, approve refreshes the list, legacy offers hide View, PDF and Restore).
    - _Bug_Condition: isBugCondition(input) where input.action == PREPARE AND nonTerminalOfferCount(project) >= 1, and input.action == VIEW_
    - _Expected_Behavior: Property 3 and Property 5 (design)_
    - _Preservation: 3.1 (role gates and status actions), 3.2, 3.3, 3.4_
    - _Requirements: 1.3, 1.5, 2.3, 2.5, 2.8, 3.1, 3.2, 3.3, 3.4_

  - [x] 3.14 Offer name dialog on "Przygotuj ofertę" in the estimate tab (repo `foremen-frontend/`)
    - New `features/estimate/PrepareOfferDialog.tsx`: optional name input, `maxLength` 255, placeholder "Offer N", the same `zod` rule as the offers-tab input.
    - `features/estimate/EstimateTab.tsx`: `handlePrepareOffer` opens the dialog; confirm calls `prepareOffer.mutate({ projectId, name })` and navigates to `/projects/{id}/pricing` in `onSuccess`; cancel closes without a request and creates nothing.
    - Update `EstimateTab.test.tsx` so prepare is reached through the dialog; add `PrepareOfferDialog.test.tsx` (confirm sends the name, blank name is allowed, cancel sends nothing, 255 limit, no navigation before success).
    - _Bug_Condition: isBugCondition(input) where input.action == PREPARE_FROM_ESTIMATE AND NOT nameAsked()_
    - _Expected_Behavior: Property 7 (design)_
    - _Preservation: 3.4, 3.7_
    - _Requirements: 1.7, 2.7, 3.4, 3.7_

  - [x] 3.15 Package marker in the comparison and the summary header (repo `foremen-frontend/`)
    - `OfferComparisonPanel.tsx`: show the `PackageMarker` in each side header. `OfferSummaryHeader.tsx`: show it next to the offer name. No marker when the view model has no package.
    - Extend `OfferSummaryHeader.labour.test.tsx` and add a comparison test case (marker on both sides, absent when no package).
    - _Bug_Condition: isBugCondition(input) where input.action IN {VIEW, LIST, COMPARE} AND appliedPackageCode != NULL AND NOT packageMarkerPresent(offer)_
    - _Expected_Behavior: Property 8 (design)_
    - _Preservation: 3.5_
    - _Requirements: 1.8, 2.8, 3.5_

  - [x] 3.16 Frontend PL and RU keys (repo `foremen-frontend/`)
    - `src/locales/pl.json` and `src/locales/ru.json`, added together: `offers.snapshot.view`, `offers.snapshot.refresh`, `offers.snapshot.refreshed`, `offers.pdf.download`, `offers.pdf.error`, `offers.restore.action`, `offers.restore.confirmTitle`, `offers.restore.confirmBody`, `offers.restore.impactWithdraw`, `offers.restore.lockedHint`, `offers.restore.stagedHint`, `offers.restore.success`, `offers.restore.skippedRooms`, `offers.package.marker`, `offers.list.awaiting`, `offers.list.history`, `estimate.prepareOffer.dialogTitle`, `estimate.prepareOffer.nameLabel`, `estimate.prepareOffer.namePlaceholder`, `estimate.prepareOffer.confirm`, `estimate.prepareOffer.cancel`. Existing keys (`offers.snapshot.noSnapshot`, `offers.prepare.nameLabel`) are reused.
    - Run the project's locale key parity check if present (search `foremen-frontend` for a locales test before running).
    - _Bug_Condition: isBugCondition(input) where the new UI actions (view, PDF, restore, prepare dialog, marker) have no label_
    - _Expected_Behavior: Property 3, 4, 6, 7, 8 (design)_
    - _Preservation: 3.7_
    - _Requirements: 2.3, 2.4, 2.6, 2.7, 2.8, 3.7_

  - [x] 3.17 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Offer snapshot is filled, frozen, usable
    - **IMPORTANT**: Re-run the SAME tests from task 1. Do NOT write new tests.
    - Run `OfferSnapshotRefreshExplorationTest`, `OfferSnapshotBuilderExplorationTest`, `OfferMultiOfferExplorationTest` with `--tests`, and the frontend exploration file with `npx vitest --run`.
    - **EXPECTED OUTCOME**: Tests PASS (confirms the bug is fixed).
    - _Requirements: 2.1, 2.2, 2.4, 2.5, 2.6, 2.7, 2.8_

  - [x] 3.18 Verify preservation tests still pass
    - **Property 2: Preservation** - Behavior outside the defect conditions is unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 2. Do NOT write new tests.
    - Run the preservation classes, `OfferStatusMachinePropertyTest`, `TerminalOfferImmutabilityPropertyTest`, the proforma PDF test, and the frontend `WorksRoomsMatrix.test.tsx`.
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions).
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

- [x] 4. Fix checking (new tests for the fixed behavior)

  - [x] 4.1 Backend fix-check integration tests
    - New `OfferFixCheckIntegrationTest` covering, end to end against the Testcontainers setup: project 1531 shape (prepare, fill, send: snapshot equals live totals, frozen afterwards, byte-identical after an estimate edit and a restore of another offer); two SENT offers with approve of one; restore round trip with audit row; PDF for staff and CLIENT (no base price text for CLIENT); package marker only when a package is applied.
    - Run only the affected classes and read the XML results:
      `./gradlew test --tests "com.foremen.service.offer.OfferSnapshotBuilderTest" --tests "com.foremen.service.offer.OfferSnapshotBuilderPropertyTest" --tests "com.foremen.service.offer.OfferSnapshotRefreshIntegrationTest" --tests "com.foremen.service.offer.OfferMultiOfferIntegrationTest" --tests "com.foremen.service.offer.OfferRestoreIntegrationTest" --tests "com.foremen.service.offer.OfferFixCheckIntegrationTest" --tests "com.foremen.service.offer.OfferEstimateReactionServiceTest" --tests "com.foremen.service.offer.OfferServicePrepareOfferPropertyTest" --tests "com.foremen.service.offer.OfferServiceIntegrationTest" --tests "com.foremen.service.offer.OfferSnapshotIntegrationTest" --tests "com.foremen.integration.OfferApprovalMigrationIntegrationTest" > /tmp/for-05-07b-test.log 2>&1; echo "EXIT=$?"`
    - _Requirements: 2.1, 2.2, 2.4, 2.5, 2.6, 2.8, 3.1, 3.2, 3.3_

  - [x] 4.2 Frontend fix-check run
    - Run in `foremen-frontend/`: `npx vitest --run src/features/project-offer src/features/estimate/EstimateTab.test.tsx src/features/estimate/components/WorksRoomsMatrix.test.tsx` and `npx tsc --noEmit`. Fix failures caused by this spec only.
    - _Requirements: 2.3, 2.4, 2.6, 2.7, 2.8, 3.7_

- [x] 5. Author test-cases.md
  - Create `test-cases.md` in the spec folder, in Russian, following `.kiro/steering/test-cases.md`:
    - UI scenarios executed by a browser engine on `http://localhost:3000/projects/1531/pricing` (offer cards, several awaiting offers, snapshot matrix view read-only through WorksRoomsMatrix, PDF button, restore with confirmation and the locked or staged-edits disabled states, package marker with and without an applied package) and on the estimate tab prepare-offer name dialog (confirm with a name, blank name, cancel creates nothing, 255-character limit).
    - API scenarios against the Dockerized app (`http://localhost:8080`): prepare twice, refresh snapshot, send then freeze, approve one of several, `GET /api/offers/{id}/pdf` as executor and CLIENT, restore (200, 409 locked, 404 no snapshot), legacy offer without a snapshot, CLIENT redaction.
    - Group by feature; each case has ID, title, preconditions, numbered steps with the expected result on each step.
    - State the repeatability strategy for every set (generator with a `run-id` in offer names and test data, plus teardown that withdraws or deletes created offers and restores the estimate).
    - Final group "Регрессия": re-check of closed defects 1.1 to 1.8 and of the critical paths (send, approve, reject, withdraw, CLIENT redaction, legacy offers, comparison).
    - End with the MD report template (table: # / test case / step / request or action / expectation / fact / status, plus the totals line with run-id and repeatability strategy).
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [x] 6. Checkpoint - Ensure all tests pass
  - Ensure all affected backend classes and frontend files pass (no full suite unless the user asks). Run `validate_spec_format` on this file. Ask the user if questions arise, in particular about assumptions A1 to A7 of `design.md`.

## Round 2 (2026-11-20)

Implements bugfix.md 2.9 to 2.16 and design.md "Round 2 design" (D1 to D8). Backend, changeset and spec live in the root repo, frontend in `foremen-frontend/`.

- [x] 7. Backend, Round 2

  - [x] 7.1 Manual inactive flag and one approved offer (D2)
    - Changeset `268-offers-inactive-flag.xml` (next free number at edit time, `MARK_RAN` precondition, registered last in `changelog.xml`), `OfferEntity.inactive`, read models expose `inactive`.
    - `POST /api/offers/{id}/deactivate` and `/activate` (`OFFERS` UPDATE, audit rows); client approve, reject and request-changes on an inactive offer answer 409 `error.offer.inactive`. Remove the Round 1 sibling withdraw from `OfferService.approve`; approve answers 409 `error.offer.already.approved` when an APPROVED offer exists. PL and RU message keys.
    - Tests: new `OfferInactiveFlagIntegrationTest`; update `OfferFixCheckIntegrationTest` and `OfferApprovalMigrationIntegrationTest`.
    - _Requirements: 2.10, 3.1_

  - [x] 7.2 Remove the offer reaction to estimate changes (D3)
    - Delete `OfferEstimateReactionListener` and the withdraw and revise paths of `OfferEstimateReactionService`. The offer-impact endpoint of `EstimateMatrixController` answers `NONE` (`@Deprecated`); `RoomsSaveService` sends `NONE`.
    - Replace `OfferEstimateReactionServiceTest` and the reaction cases of the integration tests with `EstimateChangeLeavesOffersPropertyTest` (estimate save, package apply, labour adjustment, room change and restore leave every offer byte-identical).
    - _Requirements: 2.11_

  - [x] 7.3 Offer totals from the snapshot (D7)
    - For version 2 snapshots replace `computeLiveTotals` with a computation over snapshot rows, offer discounts and the snapshot `vatRatePct`; drop `applyLiveTotals` from the read path; `OfferDiscountService` and `NegotiationService` use it; legacy offers keep the live path; labour comparison from the snapshot.
    - Tests: new `OfferSnapshotTotalsTest` (card totals equal the snapshot after an estimate edit; discounts applied on snapshot prices).
    - _Depends on: 7.1 (same `OfferService`)_
    - _Requirements: 2.15_

  - [x] 7.4 Update an existing offer from the estimate (D5, backend)
    - Prepare body `{ name?, offerId? }`; `OfferService.reprepare(offerId)`: 400 for another project, 409 `error.offer.reprepare.terminal` for terminal offers, snapshot rebuilt from the saved estimate, totals from the snapshot, status, name and inactive flag kept, version + 1, audit `REPREPARE`. For a non-DRAFT offer, an after-commit event notifies the CLIENT members of the project through `NotificationService` (PL and RU text).
    - Tests: new `OfferReprepareIntegrationTest` (SENT and inactive offers keep status, version + 1, notification only when not DRAFT, terminal refused, the snapshot of other offers unchanged).
    - _Depends on: 7.3_
    - _Requirements: 2.1, 2.13_

  - [x] 7.5 Multi-offer comparison endpoint (D4, backend)
    - `GET /api/offers/compare-many?ids=...` in `OfferSnapshotController` (`OFFERS` READ): 2 to 10 ids of one project with a snapshot, else 400; DRAFT for a CLIENT 404. `MultiComparison` with offers, groups (`valueNet` per offer) and works (per offer quantity, value and rooms); CLIENT redaction. `GET /compare` stays.
    - Tests: new `OfferCompareManyIntegrationTest`.
    - _Requirements: 2.12, 3.2, 3.5_

- [x] 8. Frontend, Round 2 (repo `foremen-frontend/`)

  - [x] 8.1 Snapshot cache after send (D1)
    - `useSendOffer` invalidates the snapshot query of the offer. Test: send with an open snapshot view shows the rebuilt snapshot without reload.
    - _Requirements: 2.9_

  - [x] 8.2 Inactive toggle and no offer-impact warnings (D2, D3)
    - Badge `offer-inactive-badge` ("Nieaktywna") and toggle `offer-toggle-inactive-{id}` for executors; the client sees the badge and no approve, reject or negotiation buttons on inactive offers. Remove `OfferImpactWarning` from the estimate Save confirmation (`EstimateTab`), `EstimateRecalcDialog` and `OfferRestoreButton`. PL and RU keys.
    - Tests: `OfferTab.test.tsx` (toggle, badge, client actions hidden), updated `EstimateTab` and restore tests.
    - _Depends on: 8.1 (shared offer files and locales), 7.1_
    - _Requirements: 2.10, 2.11_

  - [x] 8.3 Unsaved estimate edits guard (D6)
    - New `UnsavedEstimateGuard` dialog over the staged-edits store: "Zapisz i kontynuuj" (continue only after a successful Save), "Odrzuć zmiany", "Anuluj". Wrap refresh snapshot and restore; it replaces the disabled restore state. PL and RU keys.
    - Tests: `UnsavedEstimateGuard.test.tsx`.
    - _Depends on: 8.2_
    - _Requirements: 2.14_

  - [x] 8.4 Prepare dialog: update an existing offer or create a new one (D5, frontend)
    - `PrepareOfferDialog` lists every offer of the project (`prepare-offer-option-{id}`: name, status, inactive badge, version; terminal offers disabled) and "Nowa oferta" with `prepare-offer-name-input` (max 255). A sent offer shows `prepare-offer-update-hint`. Used by the estimate tab and the offers tab (the inline name input becomes a button). Goes through `UnsavedEstimateGuard`. `prepareOffer` sends `offerId` for an existing offer and invalidates the list, the offer and its snapshot.
    - Tests: `PrepareOfferDialog.test.tsx` (existing offer, new name, cancel, terminal disabled, update hint, 255 limit), updated `EstimateTab.test.tsx` and `OfferTab.test.tsx`.
    - _Depends on: 8.3, 7.4_
    - _Requirements: 2.13, 2.14, 3.4_

  - [x] 8.5 Selection, comparison screen and send selected (D4, D8)
    - Checkbox `offer-select-{id}` on every card and history row with a snapshot. `offer-compare-open` ("Porównaj (n)", 2 or more) navigates to `/projects/{id}/pricing/compare?ids=...`; `offer-send-selected` ("Wyślij zaznaczone (n)") sends the checked DRAFT offers.
    - New `OfferComparisonPage`: one column per offer (name, status, inactive badge, version, package marker, totals); category rows with cost and delta to the first offer, collapsed by default, expanding to work rows (volume with unit, cost, deltas); a click on a work cell opens a popover with the rooms and volumes of that offer; no room columns; back link. Replaces the in-tab `OfferComparisonPanel`. PL and RU keys.
    - Tests: `OfferComparisonPage.test.tsx`, `OfferTab.test.tsx` (selection, buttons hidden below their thresholds, send selected).
    - _Depends on: 8.4, 7.5_
    - _Requirements: 2.12, 2.16, 3.2_

- [x] 9. Fix checking, Round 2

  - [x] 9.1 Backend affected-class run
    - Run only the Round 2 classes of design.md "Testing, Round 2" with `--tests`, output to `/tmp/for-05-07b-r2-test.log`, read the JUnit XML.
    - _Requirements: 2.10, 2.11, 2.12, 2.13, 2.15_

  - [x] 9.2 Frontend run
    - In `foremen-frontend/`: `npx vitest --run src/features/project-offer src/features/estimate src/features/rooms/components/EstimateRecalcDialog.test.tsx` and `npx tsc --noEmit`.
    - _Requirements: 2.9, 2.10, 2.11, 2.12, 2.13, 2.14, 2.16_

- [x] 10. Update test-cases.md for Round 2
  - Add a Round 2 group in Russian following `.kiro/steering/test-cases.md`: inactive toggle (sendable, client cannot approve), one approved offer per project, estimate edit leaves offers unchanged, update of an existing offer (status kept, version + 1, client notified), unsaved-edits guard, comparison screen (groups, expand, deltas, rooms popover), send selected, snapshot view refreshed after send, totals from the snapshot. Repeatability through a run-id generator and teardown; extend "Регрессия" with the Round 1 browser findings.
  - _Requirements: 2.9, 2.10, 2.11, 2.12, 2.13, 2.14, 2.15, 2.16_

- [x] 11. Checkpoint, Round 2
  - Ensure the affected backend classes and frontend files pass (no full suite unless asked), run `validate_spec_format` on this file, then repeat the browser validation on a fresh QA project. Ask the user about open points O1 and O2 of `design.md`.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1", "2"] },
    { "id": 1, "tasks": ["3.1", "3.2", "3.9", "3.16"] },
    { "id": 2, "tasks": ["3.3", "3.5", "3.10", "3.11", "3.12", "3.14", "3.15"] },
    { "id": 3, "tasks": ["3.4", "3.6", "3.13"] },
    { "id": 4, "tasks": ["3.7", "3.8"] },
    { "id": 5, "tasks": ["3.17", "3.18"] },
    { "id": 6, "tasks": ["4.1", "4.2"] },
    { "id": 7, "tasks": ["5"] },
    { "id": 8, "tasks": ["6"] },
    { "id": 9, "tasks": ["7.1", "7.2", "7.5", "8.1"] },
    { "id": 10, "tasks": ["7.3", "8.2"] },
    { "id": 11, "tasks": ["7.4", "8.3"] },
    { "id": 12, "tasks": ["8.4"] },
    { "id": 13, "tasks": ["8.5"] },
    { "id": 14, "tasks": ["9.1", "9.2"] },
    { "id": 15, "tasks": ["10"] },
    { "id": 16, "tasks": ["11"] }
  ]
}
```
