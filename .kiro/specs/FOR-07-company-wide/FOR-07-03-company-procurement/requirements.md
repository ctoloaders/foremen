# Requirements Document

## Introduction

This spec (**FOR-07-03-company-procurement**) is a child of the FOR-07 "company-wide /
cross-project" parent. It lifts the FOR-06-03/04 procurement document from a strictly
project-scoped record to a **company expense** that may or may not belong to a project, and adds the
company-wide screen and the entry points to create such expenses.

There is **no separate `CompanyExpense` entity**: an expense is a `ProcurementDocument`
(`procurement_documents`) whose `project_id` is now **nullable**. A no-project document is a
company expense.

The spec delivers:

1. **Nullable project** on the procurement document, with every code path that assumed a project
   made null-safe (upload gate, storage namespace, OCR matching, notifications).
2. **Company procurement screen** (`/company/procurement`) — a cross-project list of all
   procurement documents (project and no-project) with a project filter (all / a project /
   no project), a link-status column, lines grouped under their original document (with a flat
   toggle), and a link back to the original document images.
3. **(Re)link to project** — an ADMIN/FINANCIER action that sets or changes a document's project
   after the fact and re-runs plan matching.
4. **Three ways to create an expense** (upload a receipt/invoice, with the full FOR-06-03 OCR flow):
   - in a **project** (the existing FOR-06-03 upload on the project Procurement tab, as now);
   - in the **company-wide list** (an "Add expense" action, project optional);
   - from a **universal "Add expense" entry in the root menu**, available to workers and every other
     internal role: choose a project or none, choose an **expense category**, flag **paid in
     cash**, attach the file.
5. **Expense category at upload** — an optional `UnplannedExpenseCategory` stored on the document and
   applied to the lines that stay unmatched after OCR.
6. **Authorization**: new ABAC resources `COMPANY_PURCHASES` (the company screen) and
   `EXPENSE_UPLOAD` (the universal upload), seeded by changesets.
7. **Notifications** emitted through the generic `NotificationService` (relink, plus the OCR
   deep-link fix for no-project documents).
8. PL + RU localization and tests, plus `test-cases.md`.

### Relationship to existing specs (source of truth)

- **FOR-06-03-procurement-ocr (implemented)** — owns `ProcurementDocumentEntity`,
  `ProcurementLineEntity`, `ProcurementDocumentService` (upload, OCR pipeline, image URL signing),
  `ProcurementOcrNotificationEmitter`. This spec makes `project` optional and reuses the upload and
  OCR pipeline unchanged for the project case.
- **FOR-06-04-purchases-list (implemented)** — owns the line match state (`MatchStatus`,
  `MatchTier`, `unplannedCategory`), `PurchaseLineReconciliationService` and the per-project
  Purchases tab. Line edits (relink-to-material, clear match, classify) **stay in that tab**; this
  spec reuses its states and adds no new match states.
- **FOR-07-04-bank-ledger (implemented)** — owns the payment choice (`PAID_CASH`,
  `ProcurementCashPaymentHandler`), ledger links and `BankLedgerQueryService`, which already handles
  `document.project == null`. Its upload-from-ledger dialog currently requires a project; this spec
  makes the picker optional there.
- **FOR-07-00-company-foundation (implemented)** — added the `/company/procurement` nav slot,
  gated on `PURCHASES/READ` as an interim. This spec re-points nav and route permission to
  `COMPANY_PURCHASES/READ` and adds the missing route and page.
- **FOR-06-00 `UnplannedExpenseCategory`** — the expense-category dictionary (`nameRU`/`namePL`,
  `active`), reused as is.

### Decisions confirmed with the product owner

- The company list is **read-only over lines**; the only company-wide document write is the
  (re)link-to-project action (plus reprocess/delete for ADMIN/FINANCIER).
- Default company view **groups lines under their document** (collapsible) with a flat-list toggle.
- ADMIN, FINANCIER and MANAGER **read** all documents including no-project ones. Only ADMIN and
  FINANCIER may **relink**. MANAGER has no relink grant.
- A no-project upload skips the ACTIVE-stage gate, stores under `procurement/company/`, and its lines
  are `UNMATCHED`/`NONE`. Relink requires the target project to be `ACTIVE`.
- Create-expense is available **in the project, in the company list, and from the root menu**; the root
  entry is for workers and all other internal roles, with project optional, expense category, and
  paid-in-cash flag.
- Notifications: `notification.procurement.relinked` (new) and the OCR deep-link fix. Nothing else.
- i18n: **no new i18n entity fields** (no new entity; supplier and line names are OCR user data; link
  status and match state reuse existing enums translated on the frontend). New namespace
  `companyProcurement.*` plus `expense.*` and error keys in `pl.json` / `ru.json`.

## Glossary

- **Expense**: a `ProcurementDocument`, with or without a project.
- **Company_Expense / No_Project_Document**: an expense whose `project_id` is `NULL`.
- **Company_Procurement_Screen**: the `/company/procurement` page.
- **Expense_Upload_Dialog**: the shared upload dialog (file, project, category, paid-in-cash).
- **Universal_Upload**: the root-menu entry (`/expenses/new`) that opens the Expense_Upload_Dialog as a page.
- **Link_Status**: the per-line derived status — `NO_PROJECT`, `LINKED` (matched to a planned
  material), `UNLINKED` (project document, line not matched), `CLASSIFIED` (unmatched line carrying an
  unplanned category).
- **Relink**: set or change a document's project after upload.
- **Expense_Category**: an active `UnplannedExpenseCategory`.
- **Notification_Service**: the generic `NotificationService` (in-app DB notification, bell).

## Requirements

### Requirement 1: Nullable project on the procurement document

**User Story:** As a finance user, I want a procurement document to exist without a project, so that
company-level expenses can be recorded in the same model.

#### Acceptance Criteria

1. THE changeset SHALL drop the NOT NULL constraint on `procurement_documents.project_id`, keeping the FK and the `ix_procurement_documents_project` index. It SHALL be idempotent (`onFail="MARK_RAN"`) and registered last in `changelog.xml` (next free number 207).
2. `ProcurementDocumentEntity.project` SHALL be `optional = true` / `nullable = true`. No data migration is needed; existing rows keep their project.
3. THE per-project surfaces (`GET /api/procurement-documents?projectId=`, the Purchases tab, `PurchaseLineReconciliationService.list`) SHALL keep requiring `projectId` and SHALL never return a no-project document.
4. THE project-scope guard (`ProjectScopedService.addRequiredQuery` / `assertProjectAccess`) SHALL stay **unchanged** (no override). Company-wide access to no-project documents SHALL be granted by role through a dedicated read service (Requirement 5), not by relaxing the guard. For no-project documents the existing per-project endpoints remain ADMIN-only (guard bypass).
5. Every code path that dereferences `document.getProject()` SHALL be audited and made null-safe: `ProcurementDocumentService` (`uploadDocument`, `process`, `reprocess`, `fillMatchedMaterialNames`, `notificationContext`), `PurchaseLineReconciliationService`, `ProcurementOcrNotificationEmitter`, and the FOR-07-04 consumers (`BankLinkService`, `ProcurementCashHook`, `ReverseDocumentMatcher`, `DetectionCandidateProviders`, `CounterpartyRuleService`, `BankLedgerQueryService`).

### Requirement 2: Null-project upload and OCR behavior

**User Story:** As an uploader, I want to upload a receipt without choosing a project, so that I do not need to know the project at upload time.

#### Acceptance Criteria

1. WHEN a document is uploaded with no project THEN the system SHALL NOT call `assertProjectActive`, SHALL store the raw file under the object-store namespace `procurement/company/`, and SHALL store the processed rendition under `procurement/company/processed/` (never `procurement/null/`).
2. WHEN a document is uploaded with a project THEN the system SHALL behave exactly as today: the project must exist and be `ACTIVE` (409 `error.purchases.stage.notActive` otherwise) and storage uses `procurement/{projectId}/`.
3. FOR a no-project document the OCR pipeline SHALL run unchanged (CLEANING → OCR_TEXT → PARSING → MATCHING → PARSED/FAILED) but the matching stage SHALL use an empty candidate set, so every extracted line is saved `matchStatus = UNMATCHED`, `matchTier = NONE`, `matchedMaterialId = null`. The line invariant (`matchedMaterialId == null ⟺ matchTier == NONE`) SHALL hold.
4. Reprocess of a no-project document SHALL skip the ACTIVE-stage gate and keep its lines unmatched; reprocess of a project document keeps the existing gate and manual-line preservation.
5. THE matched-material names lookup SHALL return an empty map for a null project (already the case) and no line of a no-project document SHALL carry a matched name.

### Requirement 3: Expense category at upload

**User Story:** As an uploader, I want to tag an expense with a category when I upload it, so that finance does not have to classify it later.

#### Acceptance Criteria

1. THE changeset SHALL add nullable `procurement_documents.expense_category_id` (FK → `unplanned_expense_categories(id)`, `ON DELETE SET NULL`), idempotent, next free number 208. `ProcurementDocumentEntity` SHALL gain `expenseCategory` (`@ManyToOne(LAZY)`, nullable).
2. THE upload endpoints SHALL accept an optional `expenseCategoryId`. A category that does not exist or is not `active` SHALL be rejected with 400 `error.expense.category.unknown`, nothing stored.
3. WHEN OCR completes (`PARSED`) AND the document has an `expenseCategory` THEN every line saved `UNMATCHED` SHALL get `unplannedCategory = expenseCategory` (`matchStatus` stays `UNMATCHED`). Matched lines SHALL NOT be classified (FOR-06-04 R1.3 invariant). Manually reconciled lines preserved by a reprocess SHALL NOT be overwritten.
4. WHEN a relink produces new matches THEN lines that become matched SHALL drop the category classification; lines that stay unmatched keep it.
5. THE category SHALL be optional everywhere; the dictionary shown in pickers lists active categories only, localized by `nameRU`/`namePL`.

### Requirement 4: Company-wide document and line read model

**User Story:** As a finance user or manager, I want to see all expenses across all projects and without a project in one place.

#### Acceptance Criteria

1. A new `CompanyPurchasesController` at `/api/company-purchases`, annotated `@PermissionResource("COMPANY_PURCHASES")`, SHALL expose, each handler with `@RequiresPermission`:
   - `GET /documents` (READ) — paged documents;
   - `GET /documents/{id}` (READ) — one document with lines and signed raw/processed image URLs;
   - `GET /lines` (READ) — flat lines.
2. THE read service SHALL NOT be project-scoped by membership: a caller with `COMPANY_PURCHASES/READ` (ADMIN, FINANCIER, MANAGER) sees documents of **all** projects and all no-project documents.
3. THE list filters SHALL be: `projectId` (a specific project), `noProject=true` (only no-project), neither (all); `linkStatus`; `uploadedFrom`/`uploadedTo`; `uploadedById`; `expenseCategoryId`; free-text `q` (supplier name, invoice number). `projectId` and `noProject=true` together SHALL be 400 `error.companyProcurement.filter.conflict`.
4. Each document row SHALL carry: `id`, `projectId` + `projectName` (null for no project), `supplierName`, `documentTotal`, `status`, `failureReason`, `uploadedAt`, `uploaderName`, `expenseCategory` (id + localized name), `paymentStatus` and `ledgerEntries` (FOR-07-04), `lineCount`, per-Link_Status line counts, and `rawImageUrl` / `processedImageUrl` (signed through `ImageStorage.signedDownloadUrl`, bucket stays private).
5. Each line row (`/lines`, and the lines of `/documents/{id}`) SHALL carry: `lineId`, `documentId`, document `supplierName` and `uploadedAt`, `projectId` + `projectName`, line fields (`lineNo`, `rawName`, `quantity`, `unitHint`, `unitPrice`, `lineTotal`, `manufacturerHint`), `matchedMaterialId` + name, `matchTier`, `matchStatus`, `unplannedCategory` id + name, and the derived `linkStatus`. The FK `ProcurementLineEntity.document` already exists — no new schema.
6. `linkStatus` SHALL be derived, never stored: `NO_PROJECT` when the document has no project; else `LINKED` when `matchStatus ∈ {AUTO, MANUAL_MATCHED}`; else `CLASSIFIED` when `unplannedCategory != null`; else `UNLINKED`.
7. THE `GET /documents/{id}` and image-URL access for a no-project document SHALL NOT depend on project membership (it is gated by the role grant), so a FINANCIER can open the original of a no-project document.
8. THE list SHALL be paged (`page`, `size`, default size 25, max 100) and sorted by `uploadedAt` descending, then id.

### Requirement 5: (Re)link a document to a project

**User Story:** As an ADMIN or FINANCIER, I want to assign or change a document's project after upload, so that no-project expenses can be attributed and plan-matched.

#### Acceptance Criteria

1. `PUT /api/company-purchases/documents/{id}/project` with body `{ projectId }` SHALL require `COMPANY_PURCHASES/UPDATE` (ADMIN, FINANCIER). MANAGER, FOREMAN and others SHALL receive 403.
2. THE target project SHALL exist and be `ACTIVE`, else 404 `error.entity.not.found` / 409 `error.purchases.stage.notActive`. A `null` `projectId` SHALL be rejected 400 `error.companyProcurement.relink.projectRequired` (clearing a project is out of scope).
3. IF `projectId` equals the document's current project THEN the system SHALL reject with 409 `error.companyProcurement.relink.sameProject`, changing nothing.
4. IF the document is still being processed (`QUEUED`, `RASTERIZING`, `CLEANING`, `OCR_TEXT`, `PARSING`, `MATCHING`, or `UPLOADED` with OCR enabled) THEN the system SHALL reject with 409 `error.companyProcurement.relink.processing`.
5. ON success THE system SHALL, in one transaction: set `project`; re-run the matcher for the new project's candidate set over lines with `matchStatus ∈ {AUTO, UNMATCHED}` (manual lines `MANUAL_MATCHED` / `MANUAL_UNMATCHED` are preserved); drop the category classification on lines that become matched; re-apply the document `expenseCategory` to lines that stay unmatched; keep the line invariant; write an UPDATE audit row.
6. WHEN a document moves from one project to another THEN `MANUAL_MATCHED` lines whose material is not in the new project's candidate set SHALL be reset to `UNMATCHED`/`NONE`; the response SHALL report how many lines were reset.
7. THE FOR-07-04 ledger links of the document SHALL be preserved unchanged (links reference the document, not the project).
8. THE response SHALL be the updated document row (Requirement 4.4) with per-Link_Status counts.
9. THE relink SHALL emit `notification.procurement.relinked` (Requirement 12).

### Requirement 6: Reprocess and delete from the company screen

**User Story:** As an ADMIN or FINANCIER, I want to retry a failed no-project document or delete a wrong upload without opening a project.

#### Acceptance Criteria

1. `POST /api/company-purchases/documents/{id}/reprocess` SHALL require `COMPANY_PURCHASES/UPDATE` and reuse `ProcurementDocumentService.reprocess` semantics (only `FAILED`/`PARSED`).
2. `DELETE /api/company-purchases/documents/{id}` SHALL require `COMPANY_PURCHASES/DELETE` (ADMIN, FINANCIER) and reuse the existing delete behavior including image orphan cleanup and the FOR-07-04 ledger-link rules.
3. THESE endpoints SHALL work for documents with and without a project and SHALL NOT require project membership.

### Requirement 7: Create an expense — shared upload endpoint

**User Story:** As any internal user, I want one consistent way to upload an expense.

#### Acceptance Criteria

1. A new `ExpenseController` at `/api/expenses`, annotated `@PermissionResource("EXPENSE_UPLOAD")`, SHALL expose:
   - `POST /api/expenses` (CREATE), multipart: `file` (required), `projectId` (optional), `expenseCategoryId` (optional), `paymentChoice` (`NOT_LINKED` | `PAID_CASH`, default `NOT_LINKED`), `cashAmount`, `cashDate`, `cashAccountId` (the FOR-07-04 cash fields, only with `PAID_CASH`);
   - `GET /api/expenses/options` (READ) — the data the dialog needs.
2. THE endpoint SHALL delegate to the existing upload path (`ProcurementDocumentService.upload` with `UploadPaymentRequest`), keeping file type/size validation, OCR enqueue and the cash handler unchanged. `LINKED_FROM_LEDGER` and `FIND_BANK_TRANSACTION` SHALL NOT be accepted here (400).
3. WHEN `projectId` is given AND the caller is not ADMIN THEN the caller SHALL be a member of that project (allowed set from `ProjectAccessCache`), else 404 `error.entity.not.found` (indistinguishable from missing). The project must be `ACTIVE`.
4. WHEN `paymentChoice = PAID_CASH` THEN `cashAmount > 0` is required (400 as today), and the FOR-07-04 `ProcurementCashPaymentHandler` SHALL create the cash ledger entry exactly as for the project upload. If `cashAccountId` is omitted the handler's existing default cash account rule applies; workers are never shown an account picker.
5. `GET /api/expenses/options` SHALL return: the projects the caller can upload into (ADMIN: all `ACTIVE`; others: `ACTIVE` projects they are a member of; id + name), the active Expense_Categories (id, code, `nameRU`, `namePL`), and `canChooseCashAccount` (true only when the caller also holds `BANK_LEDGER/READ`) with the cash accounts then.
6. THE response SHALL return the created document id and its processing status (same shape as `ProcurementDocumentUploadResponse`) so the UI can poll the existing status endpoint — that status read for a no-project document SHALL work for the uploader via `GET /api/expenses/{id}/status` (uploader or `COMPANY_PURCHASES/READ`).
7. THE project Procurement tab SHALL keep calling `POST /api/procurement-documents` (`PURCHASES/CREATE`); that endpoint SHALL gain the optional `expenseCategoryId` param (Requirement 3) and its `projectId` stays required there.

### Requirement 8: Expense_Upload_Dialog — three entry points

**User Story:** As a user, I want to create an expense in a project, from the company list, or from the root menu.

#### Acceptance Criteria

1. THE frontend SHALL provide one shared `ExpenseUploadForm` with: file input (image/PDF, same accepted types and size message as today), project select (optional, with a "No project" option, populated from `/api/expenses/options`), expense category select (optional, localized), and a "Paid in cash" toggle which reveals cash amount (required) and date (default today).
2. **In a project** — the existing project Procurement tab upload keeps its fixed project (no selector) and gains the category select; the paid-in-cash fields stay as they are.
3. **In the company-wide list** — the Company_Procurement_Screen SHALL have an "Add expense" button (visible with `EXPENSE_UPLOAD/CREATE`) opening the dialog with project preselected to the active filter when a specific project is filtered, otherwise empty.
4. **Universal root-menu entry** — a top-level menu item "Add expense" (`/expenses/new`, `nav.addExpense`, icon `receipt`) gated by `EXPENSE_UPLOAD/CREATE`, shown in the main section for ADMIN, FINANCIER, MANAGER, FOREMAN and WORKER, hidden for CLIENT and ESTIMATOR. It SHALL appear in Sidebar, Drawer and BottomNav (`bottomNav: true`) for mobile workers.
5. THE page SHALL work on mobile (camera capture accepted via `accept="image/*,application/pdf"` with `capture` hint), show upload progress, and after success show the processing status (polling the status endpoint), a "Add another" action, and — only when the caller holds `COMPANY_PURCHASES/READ` — a link to the document in the company screen.
6. Validation SHALL be client + server: file required; cash amount `> 0` when the toggle is on; unknown category / forbidden project show localized errors.
7. THE ledger "upload invoice" dialog (FOR-07-04) SHALL make its project picker optional (reusing `ExpenseUploadForm` project select).

### Requirement 9: Company_Procurement_Screen

**User Story:** As a finance user, I want a company-wide expenses screen with filters, grouping and relink.

#### Acceptance Criteria

1. THE route `/company/procurement` SHALL be added to `router.tsx` and `route-permissions.ts` with `COMPANY_PURCHASES/READ`; `navigation.ts` SHALL re-point the existing item to the same permission; the nav/route-permission tests that assert `PURCHASES/READ` for this path SHALL be updated.
2. THE screen SHALL show a filter bar: project (All projects / No project / each project), link status, expense category, uploader, date range, text search; filters SHALL be reflected in the URL query string.
3. THE default view SHALL group lines under their document (collapsible rows: supplier, project or "No project", total, status, category, payment badge, link-status counts); a toggle SHALL switch to the flat line list with a "Document" column linking to the group.
4. EACH document row SHALL show a link/button to view the original (raw + processed) using the existing `DocumentImageViewer`.
5. THE link-status column/badge SHALL show `NO_PROJECT`, `LINKED`, `UNLINKED`, `CLASSIFIED` with distinct localized labels.
6. A project-document row SHALL link to the project's Procurement tab (`/projects/{projectId}/procurement?doc={id}`); a document row's "Relink to project" action (visible with `COMPANY_PURCHASES/UPDATE`) SHALL open a dialog with an ACTIVE-project select, show the result counts (matched / reset), and refresh the list.
7. Line editing SHALL NOT be available on this screen; a project line SHALL offer "Open in project" deep link to the Purchases tab.
8. THE screen SHALL handle loading, empty, error and processing/failed document states; a `FAILED` row offers "Reprocess" (UPDATE) and any row offers "Delete" (DELETE) with confirmation.
9. THE deep link `/company/procurement?doc={id}` SHALL scroll to and expand that document, and open its viewer when the document exists.

### Requirement 10: Authorization — ABAC resources

**User Story:** As an administrator, I want the company screen and the universal upload governed by the permission matrix.

#### Acceptance Criteria

1. Changeset 209 SHALL seed resource `COMPANY_PURCHASES` (`name_ru` "Закупки по компании", `name_pl` "Zakupy firmowe", descriptions RU/PL), idempotent (`MARK_RAN`, `NOT EXISTS` grants), registered last. Grants: ADMIN → READ, UPDATE, DELETE; FINANCIER → READ, UPDATE, DELETE; MANAGER → READ. All other roles deny-by-default.
2. Changeset 210 SHALL seed resource `EXPENSE_UPLOAD` (`name_ru` "Загрузка расходов", `name_pl` "Dodawanie wydatków"), idempotent. Grants: ADMIN, FINANCIER, MANAGER, FOREMAN, WORKER → READ, CREATE. CLIENT and ESTIMATOR: none.
3. `CompanyPurchasesController` and `ExpenseController` SHALL be fully annotated (`@PermissionResource` at class level plus method-level `@RequiresPermission` / `@PermissionOperation`) so `PermissionAnnotationValidator` passes at startup.
4. THE per-project `PURCHASES` grants SHALL stay unchanged. WORKER SHALL NOT gain `PURCHASES`, `COMPANY_PURCHASES` or any read access to documents other than the status of their own upload.
5. THE services behind these controllers are not project-scoped by membership and SHALL NOT implement `ProjectScopedService`; the upload membership check (Requirement 7.3) SHALL be explicit and tested.
6. THE nav items SHALL be gated by `isNavItemVisible` on the same resource/operation as the route and the backend endpoint, so the audience agrees across menu, route guard and API.

### Requirement 11: Audit and data integrity

#### Acceptance Criteria

1. Upload, relink, reprocess and delete SHALL write audit rows through the existing `saveAudit` (CREATE / UPDATE / DELETE), including the old and new `project_id` and `expense_category_id` on relink.
2. `ImageReferenceLookup` registration of `raw_object_key` / `processed_object_key` SHALL stay unchanged.
3. Storage keys of already-uploaded documents SHALL NOT change on relink (the namespace is only a storage prefix).
4. No-project documents SHALL appear in the FOR-07-04 ledger link targets exactly as today (`BankLedgerQueryService` null-project branch); the link-candidate providers SHALL be verified null-safe.

### Requirement 12: Notifications

**User Story:** As an uploader, I want to know when finance assigns my expense to a project.

#### Acceptance Criteria

1. THE spec SHALL only **emit** through the generic `NotificationService.create(recipientUserId, typeKey, body, deepLink)` (in-app DB notification, bell). Email, SMS, Telegram and async fan-out are out of scope.
2. `notification.procurement.relinked` — recipient: the document's uploader; trigger: a successful relink by ADMIN/FINANCIER; skipped when the actor is the uploader; body: "Document {{supplier}} was assigned to project {{project}}" (placeholders `supplier`, `project`; supplier falls back to the invoice number, then "#id"); deep link: `/projects/{projectId}/procurement?doc={documentId}`. Emission is best-effort (failure logged, never fails the relink), in `REQUIRES_NEW` like the OCR emitter.
3. `notification.procurement.ocr.parsed` and `notification.procurement.ocr.failed` SHALL keep their behavior for project documents. For a no-project document the deep link SHALL be `/company/procurement?doc={documentId}` when the recipient holds `COMPANY_PURCHASES/READ`, and `null` (no link, not clickable) otherwise — workers who uploaded from the root menu get a non-clickable notification.
4. NO other notifications are in scope (no "new expense" notification to finance).

### Requirement 13: Localization (PL + RU)

#### Acceptance Criteria

1. No backend i18n entity fields are added. Expense category names use the existing `nameRU`/`namePL`.
2. THE frontend SHALL add, in both `pl.json` and `ru.json` with identical key sets: `nav.addExpense`; the `companyProcurement.*` namespace (page title, filter labels, project filter options "All"/"No project", grouping/flat toggle, column headers, link-status labels and badges, relink dialog texts and result counts, reprocess/delete confirmations, empty/loading/error states, "View original", "Open in project"); the `expense.*` namespace (form labels: file, project, "No project", category, "Paid in cash", cash amount, cash date; buttons "Add expense", "Add another"; progress, success, processing-status texts; validation messages); `notification.procurement.relinked`.
3. Backend message-bundle codes SHALL be added with PL + RU translations and matching `error.*` frontend keys: `error.expense.category.unknown`, `error.expense.upload.projectForbidden`, `error.expense.upload.paymentChoiceNotAllowed`, `error.companyProcurement.filter.conflict`, `error.companyProcurement.relink.projectRequired`, `error.companyProcurement.relink.sameProject`, `error.companyProcurement.relink.processing`.
4. THE existing `nav.companyProcurement` ("Zakupy firmowe") SHALL be kept; PL strings follow the existing glossary ("wydatek", "kategoria wydatku", "zapłacono gotówką").

### Requirement 14: Non-functional

#### Acceptance Criteria

1. THE company list queries SHALL use indexed filters (`project_id`, `uploaded_at`, `expense_category_id` index added in changeset 208) and avoid N+1 (fetch uploader, project, category, and line counts in bulk).
2. Signed image URLs SHALL be generated only for the requested page / the opened document, not for the whole list in the flat-lines endpoint (flat lines carry the `documentId`; URLs are fetched with the document).
3. File upload limits and accepted types SHALL be unchanged (`ACCEPTED_CONTENT_TYPES`, `maxUploadSize`).
4. All new UI SHALL be keyboard accessible with labelled controls and not rely on color alone for link status.

## Out of Scope

1. Creating an expense **without** a file (manual entry of amount/supplier); every expense is a document.
2. Clearing a document's project (relink to "no project").
3. Editing lines (relink-to-material, classify, clear match) on the company screen; stays in FOR-06-04.
4. Editing a document's expense category after upload (lines can still be classified per line in FOR-06-04).
5. A worker-visible list of their own uploads.
6. Notifications to finance on new uploads; email/SMS/Telegram delivery.
7. Statement/bank import and bank linking changes (FOR-07-04).
8. Project-scope guard changes (`ProjectScopedService` stays as is).
