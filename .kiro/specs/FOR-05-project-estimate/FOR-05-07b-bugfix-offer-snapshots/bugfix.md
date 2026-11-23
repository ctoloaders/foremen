# Bugfix Requirements Document

## Introduction

Reported on `http://localhost:3000/projects/1531/pricing` (the "Oferty" tab, owned by FOR-05-07, with package data from FOR-05-06). The offer snapshot cannot be used the way the user expects. Six problems were reported: the snapshot is not visible or not matrix-like, there is no PDF, a project cannot hold several offers awaiting approval, there is no restore from a snapshot, "Przygotuj ofertę" on the estimate does not ask for an offer name, and the applied package is not shown on the offer.

This work is sizable. It is handled as a bug fix by the user's decision, and it amends FOR-05-07 Batch 2 (additional-tasks.md tasks 8 to 14, marked done). The scope below separates what already exists from what is broken and what is missing.

**Already exists (verified in code and in the local DB, not rebuilt by this spec):**
- `offers.name` and `offers.snapshot` (JSONB, `updatable=false`), changeset 258. The name is trimmed, max 255, default "Offer N".
- `OfferService.prepareOffer(projectId, name)` accepts a name and captures the snapshot (`OfferSnapshotBuilder`) in the same transaction. The `POST /api/offers/project/{id}/prepare` endpoint accepts an optional `name`.
- `GET /api/offers/project/{id}/list`, `GET /api/offers/{id}/snapshot` and `GET /api/offers/compare` (`OfferSnapshotController`), with CLIENT redaction.
- Frontend `OfferVersions` (list, view, compare), `OfferSnapshotPanel` (a hand-built read-only table: category groups, room columns, totals) and `OfferComparisonPanel`.
- The name input in `OfferTab`, shown only in the "no offer" state and the "terminal offer" state.
- An HTML to PDF seam (`PdfRenderer` / `OpenHtmlPdfRenderer`) used by document signing and client proformas, plus the frontend `ClientPaymentPdfButton` pattern. Nothing of it is wired to offers.

**Evidence for project 1531 (local DB and API):**
- Offer 261 ("Bazowy", status SENT) was created at 19:50:11. Its only estimate line was added at 19:50:45. The stored snapshot is empty (`rooms: []`, `groups: []`, totals 0), while the live offer totals are 2705.29 net and 2921.71 gross. The snapshot is immutable and is captured at prepare time, so a DRAFT offer keeps an empty or outdated view after the estimate is filled.
- 7 of the 8 offers in the DB have no snapshot (legacy offers).
- The estimate has `applied_package_code = NULL`, so a package marker cannot be shown for this project until a package is applied. The snapshot and the list/snapshot views have no package field at all.

**Bug condition (informal):** C(X) holds when an offer snapshot is requested, listed, exported, restored or prepared, and one of these is true: the snapshot was captured before the estimate was filled or changed; the snapshot contains rows applied to no room; the project already has a non-terminal offer; the action is PDF or restore; the prepare action is triggered from the estimate; the offer was prepared from an estimate with an applied package.

**Assumptions to confirm in design (no user answer yet):**
- Restore replaces the live estimate with the content of the chosen snapshot as one atomic, audited operation, and is allowed only while the estimate is not locked.
- When one of several awaiting offers is approved, the other non-terminal offers are withdrawn.
- Offers without a snapshot (legacy) cannot be viewed, exported to PDF or restored; this stays as is.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN an offer is prepared before the estimate is filled or changed (project 1531: offer 261 prepared at 19:50:11, estimate line added at 19:50:45) THEN the system keeps the early snapshot unchanged until the offer is sent, and the snapshot view shows an empty table with zero totals while the offer shows 2705.29 net.

1.2 WHEN a snapshot is built from the estimate THEN the system includes every estimate line, including work rows that are not applied to any room (no room quantity cell), and the snapshot view renders them.

1.3 WHEN an executor or client opens the snapshot THEN the system renders a simplified custom table (`OfferSnapshotPanel`) that is not the estimate matrix (works by rooms, as on the estimate tab). It is reachable only through a "View" button in the versions list at the bottom of the tab, and only for offers with `hasSnapshot`.

1.4 WHEN a user wants a PDF of an offer snapshot THEN the system offers no PDF download, because no offer PDF endpoint or button exists.

1.5 WHEN a project already has a non-terminal offer and an executor prepares another one THEN the system rejects the request with 409 `error.offer.active.exists`. A partial unique index `ux_offers_active_project` also allows only one non-terminal offer per project. The tab works with one "current" offer, so several offers awaiting approval cannot exist.

1.6 WHEN an executor wants to return the estimate to the state of an earlier offer snapshot THEN the system offers no restore action. There is no endpoint, and the stored snapshot does not hold everything needed to rebuild the estimate (applied package code, per-line price override and comment, labour price adjustment inputs).

1.7 WHEN the executor presses "Przygotuj ofertę" on the estimate tab THEN the system calls prepare immediately with no name (`EstimateTab.handlePrepareOffer` passes only the project id) and navigates to the offers tab, so the offer gets the default name "Offer N".

1.8 WHEN an offer is prepared from an estimate with an applied package THEN the system stores no package marker in the snapshot, and the offers list, the snapshot view and the comparison do not show whether a package was applied. The package code is visible only on the single current offer header, and only as the live value.

### Expected Behavior (Correct)

2.1 WHEN an offer is DRAFT and the estimate changes THEN the system SHALL refresh the offer snapshot from the current estimate when the offer is sent (and on an explicit refresh of the DRAFT offer), and SHALL freeze it (immutable) from the moment the offer leaves DRAFT, so a sent offer always shows the numbers the client received and the DRAFT view is never empty or outdated when the estimate has data.

2.2 WHEN a snapshot is built THEN the system SHALL omit work rows that are not applied to any room and have no quantity, and SHALL keep only groups that have at least one remaining row.

2.3 WHEN an executor or client opens the snapshot THEN the system SHALL render a read-only matrix with the same structure as the estimate tab (works as rows, rooms as columns, grouped by work category, per-room quantities, totals), without staging, edit or assignment controls, and SHALL make it reachable from each offer row of the offers list.

2.4 WHEN a user requests a PDF of an offer with a snapshot THEN the system SHALL return a PDF generated from that snapshot (offer name, date, project, applied-package marker, category-grouped matrix, totals and VAT rate) in the user's locale, guarded by `OFFERS` READ, and a CLIENT PDF SHALL NOT contain base prices, labour comparison or margins; a DRAFT offer SHALL NOT be downloadable by a CLIENT.

2.5 WHEN an executor prepares an offer and the project already has non-terminal offers THEN the system SHALL create the new offer, and the project SHALL be able to hold several offers awaiting approval at the same time, each with its own frozen snapshot, status and totals. The one-active-offer rule (service check and `ux_offers_active_project`) SHALL be removed or relaxed accordingly, and the offers tab SHALL list every offer with its own actions (send, withdraw, approve, reject).

2.6 WHEN an executor chooses "Restore" on an offer that has a snapshot THEN the system SHALL, after an explicit confirmation, replace the live estimate content with the snapshot content (lines, room quantities, prices, applied package) in one transaction, write an audit row, and refuse with 409 when the estimate is service-locked. The snapshot SHALL store all fields needed for the restore, and an offer without a snapshot SHALL NOT offer restore.

2.7 WHEN the executor presses "Przygotuj ofertę" on the estimate tab THEN the system SHALL first ask for an offer name in a dialog (optional, max 255, default "Offer N"), SHALL call prepare with the entered name, and SHALL navigate to the offers tab only after success; cancelling SHALL create nothing.

2.8 WHEN an offer is prepared from an estimate with an applied package THEN the system SHALL store the applied package code in the snapshot and SHALL show an "applied package" marker (package name) in the offers list, the snapshot view, the comparison and the PDF; WHEN no package was applied THEN the system SHALL show no marker.

### Unchanged Behavior (Regression Prevention)

3.1 WHEN an offer is sent, approved, rejected, withdrawn or negotiated THEN the system SHALL CONTINUE TO follow the existing status machine, role gates and audit rows of FOR-05-07.

3.2 WHEN a CLIENT reads offers, snapshots or comparisons THEN the system SHALL CONTINUE TO hide DRAFT offers (404) and SHALL CONTINUE TO exclude `baseUnitPrice`, `baseValueNet`, labour comparison and margins.

3.3 WHEN an offer has no snapshot (legacy offers) THEN the system SHALL CONTINUE TO show the "no snapshot" marker and hide view, compare, PDF and restore.

3.4 WHEN the executor prepares an offer from the offers tab THEN the system SHALL CONTINUE TO accept the optional name, apply the default "Offer N" for a blank name, and reject names longer than 255 characters with `error.offer.name.too.long`.

3.5 WHEN two offers of the same project with snapshots are compared THEN the system SHALL CONTINUE TO return added, removed and changed works and the total delta, and SHALL CONTINUE TO reject offers of different projects with 400.

3.6 WHEN the estimate or rooms change THEN the system SHALL CONTINUE TO trigger the offer reaction of FOR-05-07 (withdraw of seen offers, new DRAFT version after an approved offer), now applied to each affected non-terminal offer.

3.7 WHEN the estimate is edited, a package is applied or labour prices are adjusted THEN the system SHALL CONTINUE TO work as specified in FOR-05-03, FOR-05-04 and FOR-05-06, and the existing estimate matrix components SHALL NOT change behavior.

3.8 WHEN document signing or client payments generate PDFs THEN the system SHALL CONTINUE TO use the existing `PdfRenderer` seam without changes to their output.

## Round 2 (2026-11-20): user decisions on A1 to A7 and follow-up defects

Source: browser validation of Round 1 on project 7678 and the user answers to assumptions A1 to A7 (`design.md`, "Assumptions"). The user model: **the estimate is a working sheet** of the estimator, financier and foreman, and its unsaved state lives in the browser until it is saved. **An offer is a self-contained container** of the whole estimate (its snapshot), built from the saved estimate. Several offers can be sent to the client at the same time so the client can compare them. Estimate edits never change an offer.

### Current Behavior (Defect), Round 2

1.9 WHEN an offer is sent while its snapshot view is open (or was opened before) THEN the view keeps showing the pre-send snapshot until the page is reloaded, although the backend rebuilt the snapshot on send.

1.10 WHEN one of several offers is approved THEN the other non-terminal offers of the project are WITHDRAWN, and there is no way to mark an offer inactive by hand while keeping it visible to the client.

1.11 WHEN the estimate changes (save, package apply, labour adjustment, room change, restore from an offer) THEN offers the client has seen are WITHDRAWN and, after an approved offer, a new DRAFT revision is created. The restore dialog warns about this.

1.12 WHEN offers are compared THEN only two offers can be compared, inside the offers tab, with a per-room layout. There is no multi-select and no comparison screen.

1.13 WHEN "Przygotuj ofertę" is pressed THEN a new offer is always created. An existing offer cannot be chosen and updated, and unsaved estimate edits are not considered.

1.14 WHEN a DRAFT offer is shown THEN its card totals live-track the current estimate and can differ from its own snapshot.

### Expected Behavior (Correct), Round 2

2.9 WHEN an offer snapshot is rebuilt (send, refresh, update of an existing offer) THEN the frontend SHALL invalidate the snapshot query of that offer, and an open snapshot view SHALL show the new snapshot without a page reload.

2.10 WHEN the executor marks an offer inactive or active again (manual action, any non-terminal offer) THEN the system SHALL store an `inactive` flag that is independent of the status, and SHALL show an "Nieaktywna" badge next to the status. An inactive offer SHALL still be sendable, visible to the client when it is not DRAFT, viewable, comparable and downloadable as PDF, but the client SHALL NOT approve, reject or negotiate it (409 `error.offer.inactive`). Approving an offer SHALL NOT change the other offers of the project. While the project has an APPROVED offer, approving another one SHALL be refused with 409 `error.offer.already.approved`. Several offers MAY be SENT at the same time. (Replaces A2.)

2.11 WHEN the estimate or rooms change, including a restore from an offer, THEN the system SHALL NOT change the status, totals or snapshot of any offer and SHALL NOT create offer revisions. The offer reaction to estimate changes (withdraw of seen offers, revise after approval) SHALL be removed, and no change-impact dialog SHALL announce an offer withdrawal. (Replaces A4.)

2.12 WHEN the executor or the client selects two or more offers with a snapshot by checkbox THEN a "Porównaj" button SHALL open a simple comparison screen: one column per selected offer (header: name, status, inactive badge, version, package marker, totals net, VAT, gross), one row per work category with its cost in each offer and the difference to the first selected offer, expandable to the works of that category. A work row SHALL show the project-wide volume (with unit) and cost in each offer and the difference to the first selected offer. Clicking a work cell SHALL show the rooms of that offer with their volumes for that work. Room columns SHALL NOT be shown. A category row shows cost only, because its works can have different units. A CLIENT SHALL only select non-DRAFT offers and SHALL receive redacted data (3.2). Offers of different projects SHALL be rejected with 400.

2.13 WHEN the executor presses "Przygotuj ofertę" (estimate tab or offers tab) THEN a dialog SHALL list every offer of the project with its name, status, inactive badge and version, plus a "new offer" choice with a name input. Entering a new name SHALL create a new DRAFT offer. Choosing an existing non-terminal offer (active or inactive) SHALL update it: rebuild its snapshot from the saved estimate, recompute its totals, KEEP its status, increase its version by one, write an audit row (`REPREPARE`, previous version and totals) and, when the offer is visible to the client (not DRAFT), send an in-app notification to the client members of the project. Terminal offers (APPROVED, REJECTED, WITHDRAWN) SHALL be listed but not selectable.

2.14 WHEN "Przygotuj ofertę", "Odśwież migawkę z kosztorysu" or "Przywróć kosztorys z oferty" is used while the estimate has unsaved (staged, browser-only) edits THEN the UI SHALL first ask to save or discard them ("Zapisz i kontynuuj", "Odrzuć zmiany", "Anuluj"). The snapshot SHALL always be built from the saved estimate.

2.15 WHEN an offer is shown, in any status, THEN its totals SHALL come from its own snapshot (the container), not from the live estimate. The live estimate is read only when a snapshot is built.

2.16 WHEN the executor selects several DRAFT offers by checkbox THEN a "Wyślij zaznaczone (n)" action SHALL send all of them to the client in one step.

### Changes to Unchanged Behavior, Round 2

- 2.1 is amended: the snapshot of a sent, non-terminal offer may be rebuilt only by the update of 2.13 (version + 1, client notified). Terminal offers stay frozen.
- 3.1 is amended: the `inactive` flag blocks client approve, reject and negotiation (2.10), a project holds at most one APPROVED offer, and update (2.13) keeps the status. All other transitions, role gates and audit rows stay.
- 3.5 is extended: the two-offer comparison endpoint stays for existing callers, and the N-offer comparison of 2.12 is added.
- 3.6 is superseded by 2.11: estimate and room changes no longer react on offers.
- A1, A3, A5, A6, A7 are confirmed by the user without change. A2 is replaced by 2.10 and A4 by 2.11.
