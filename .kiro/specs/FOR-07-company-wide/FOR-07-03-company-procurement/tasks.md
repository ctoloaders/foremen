# Implementation Plan: FOR-07-03 — Company procurement (nullable project, company screen, universal expense upload)

## Overview

Order: schema → pure collaborators → entity → backend services (null-safety, upload, company read
model, relink, notifications) → controllers → backend tests → frontend (locales, expense upload,
company screen, navigation, project-tab and ledger-dialog changes, tests) → test-cases → docker
verification → checkpoint.

**Spans two git repos** (`.kiro/steering/git-repo-structure.md`):
- **Root repo** — changesets 207–210, backend code/tests, message bundles, this spec. Commit from the
  workspace root.
- **Frontend repo** (`foremen-frontend/`) — `features/expense-upload`, `features/company-procurement`,
  procurement-tab and ledger-dialog changes, navigation / router / route-permissions, locales. Commit
  from inside `foremen-frontend/`.

**Migrations.** Next free changeset is `207` (tail: `206-seed-bank-ledger-resource.xml`). If a sibling
spec lands changesets first, take the next free numbers in the same order.

**Test-run policy (backend).** Never run the full suite (~20 min). Run only affected classes with
`--tests`, redirect to `/tmp/for-07-03-test.log`, read the JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` first. Frontend: `npx vitest --run
<file>` from `foremen-frontend/`.

**New managed resources** follow `.kiro/steering/entity-creation-rules.md`: seed changeset registered
last, ADMIN grant, fully annotated controller. Neither new resource is project-scoped by membership,
so no `getProjectIdPath()` override is needed (the existing guard is untouched).

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 2.1, 2.2, 4.9, 6.1, 6.2, 6.4
  - schema changesets 207/208 (one task, edits `changelog.xml`); two pure collaborators; backend
    message bundle; frontend locale keys; two independent frontend API/type modules.
- **Wave 2 (parallel):** 1.2, 3.1, 6.3
  - ABAC seeds (edit `changelog.xml` after 1.1); entity changes (need 1.1); expense upload UI (needs 6.1, 6.2).
- **Wave 3 (parallel):** 4.1, 4.2, 4.7, 5.4
  - `ProcurementDocumentService` null-safety + category; audit of the other null-project consumers;
    notification emitters; changeset integration test.
- **Wave 4 (parallel):** 4.3, 4.4, 4.8, 5.2
  - shared read fields + unchecked variants (same file as 4.1, so after it); expense upload
    service + controller; ledger no-project upload endpoint; no-project service tests.
- **Wave 5 (parallel):** 4.5, 6.5, 6.7
  - company read service + controller; company screen UI; project-tab category + ledger dialog.
- **Wave 6 (parallel):** 4.6, 6.6
  - relink service + endpoint (extends the company controller); navigation, route, permission tests.
- **Wave 7 (parallel):** 5.1, 5.3, 6.8
  - relink tests; controller integration tests; frontend tests.
- **Wave 8 (parallel):** 7.1, 8.2
  - `test-cases.md`; OVERVIEW/registry updates.
- **Wave 9:** 8.1 — docker-stack verification.
- **Wave 10:** 9.1 — final checkpoint.

## Tasks

- [x] 1. Schema and ABAC seeds
  - [x] 1.1 Changesets 207 and 208
    - `207-procurement-documents-project-nullable.xml` drops NOT NULL on `procurement_documents.project_id` (FK and index kept, `MARK_RAN` precondition).
    - `208-procurement-documents-expense-category.xml` adds `expense_category_id` (FK `ON DELETE SET NULL`), `ix_procurement_documents_category`, and `ix_procurement_documents_uploaded_at` when absent.
    - Register both last in `changelog.xml`.
    - _Requirements: 1.1, 3.1, 14.1_
    - _Depends on: none_
  - [x] 1.2 Changesets 209 and 210 (resource seeds)
    - `209-seed-company-purchases-resource.xml` (ADMIN R/U/D, FINANCIER R/U/D, MANAGER R) and `210-seed-expense-upload-resource.xml` (ADMIN, FINANCIER, MANAGER, FOREMAN, WORKER: READ + CREATE), idempotent, following `187-seed-company-schedule-resource.xml`; register last.
    - _Requirements: 10.1, 10.2_
    - _Depends on: 1.1_

- [x] 2. Pure collaborators (jqwik)
  - [x] 2.1 `LinkStatus` enum and `LinkStatusResolver`
    - Pure `resolve(hasProject, matchStatus, hasUnplannedCategory)`; unit test for all combinations and a property test for totality.
    - _Requirements: 4.6_
    - _Depends on: none_
  - [x] 2.2 `ExpenseCategoryApplier`
    - Pure helper that applies a document category to unmatched lines only and clears it on matched lines; unit + property test (line invariant, manual lines untouched).
    - _Requirements: 3.3, 3.4_
    - _Depends on: none_

- [x] 3. Entity
  - [x] 3.1 `ProcurementDocumentEntity` changes
    - `project` optional/nullable; add `expenseCategory`; fix class javadoc; compile check.
    - _Requirements: 1.2, 3.1_
    - _Depends on: 1.1_

- [x] 4. Backend services and controllers
  - [x] 4.1 `ProcurementDocumentService` null-safety and category
    - Null-project upload (no stage gate, `procurement/company/` namespace), `process()` namespace + empty candidates, `reprocess()` gate only with a project, optional `expenseCategoryId` (validated, applied after OCR via 2.2), overloads keep existing signatures.
    - _Requirements: 1.5, 2.1, 2.2, 2.3, 2.4, 2.5, 3.2, 3.3_
    - _Depends on: 3.1, 2.2_
  - [x] 4.2 Audit remaining null-project consumers
    - Verify/fix `BankLinkService`, `ProcurementCashHook`, `ReverseDocumentMatcher`, `DetectionCandidateProviders`, `CounterpartyRuleService`, `PurchaseLineReconciliationService`, `ImageReferenceLookup` for `document.getProject() == null`; add a regression test per fix.
    - _Requirements: 1.5, 11.2, 11.4_
    - _Depends on: 3.1_
  - [x] 4.3 Shared read fields and unchecked variants
    - Extract `ProcurementReadFields` (uploader name, signed image URLs, matched names) from `ProcurementDocumentService`; add `reprocessUnchecked` / `deleteUnchecked` used by the company layer.
    - _Requirements: 4.7, 6.3, 14.2_
    - _Depends on: 4.1_
  - [x] 4.4 `ExpenseUploadService` and `ExpenseController`
    - `POST /api/expenses`, `GET /api/expenses/options`, `GET /api/expenses/{id}/status`; membership check, forbidden payment choices, cash validation, options (projects, categories, cash accounts when allowed); fully annotated for `EXPENSE_UPLOAD`.
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 10.3, 10.5_
    - _Depends on: 4.1, 1.2_
  - [x] 4.5 `CompanyPurchasesService` and `CompanyPurchasesController` (read, reprocess, delete)
    - Documents/lines/document endpoints with the project/no-project/all filters, link-status filter, paging, bulk counts, DTOs; reprocess and delete; annotated for `COMPANY_PURCHASES`.
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.7, 4.8, 6.1, 6.2, 6.3, 10.3, 14.1_
    - _Depends on: 4.3, 2.1, 1.2_
  - [x] 4.6 `DocumentRelinkService` and the relink endpoint
    - `PUT /api/company-purchases/documents/{id}/project`: validations, re-match AUTO/UNMATCHED lines, reset foreign manual matches on project change, category re-apply, audit, result counts, emit notification.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 11.1_
    - _Depends on: 4.5, 4.7_
  - [x] 4.7 Notification emitters
    - New `ProcurementRelinkNotificationEmitter` (`notification.procurement.relinked`, skip uploader==actor, best-effort `REQUIRES_NEW`); OCR emitter deep link for no-project documents (`/company/procurement?doc=` when the recipient holds `COMPANY_PURCHASES/READ`, else `null`).
    - _Requirements: 12.1, 12.2, 12.3, 12.4_
    - _Depends on: 3.1_
  - [x] 4.8 Ledger no-project upload endpoint
    - `POST /api/procurement-documents/company` (`PURCHASES/CREATE`, optional `projectId`, accepts `LINKED_FROM_LEDGER`) used by the FOR-07-04 upload dialog when no project is chosen.
    - _Requirements: 7.7, 8.7_
    - _Depends on: 4.1_
  - [x] 4.9 Backend message bundle
    - Add PL + RU texts for the new `error.expense.*` and `error.companyProcurement.*` codes.
    - _Requirements: 13.3_
    - _Depends on: none_

- [x] 5. Backend tests
  - [x] 5.1 Relink tests
    - `DocumentRelinkServiceTest`, `DocumentRelinkPropertyTest` (invariant, manual preservation, idempotent same-project rejection), relinked-notification emitter test.
    - _Requirements: 5.2, 5.3, 5.4, 5.5, 5.6, 12.2_
    - _Depends on: 4.6_
  - [x] 5.2 No-project service tests
    - `ProcurementDocumentServiceNoProjectTest`: namespace, no stage gate, all lines `UNMATCHED`/`NONE`, category applied only to unmatched lines, reprocess; OCR emitter deep-link variants.
    - _Requirements: 2.1, 2.3, 2.4, 3.3, 12.3_
    - _Depends on: 4.1, 4.7_
  - [x] 5.3 Controller integration tests (Testcontainers)
    - `CompanyPurchasesControllerIT` (filters, role matrix, MANAGER 403 on relink/delete, FINANCIER sees no-project originals), `ExpenseControllerIT` (WORKER with and without project, non-member 404, cash validation, forbidden payment choices, CLIENT 403), guard test still green, startup `PermissionAnnotationValidator`.
    - _Requirements: 4.2, 4.3, 5.1, 7.3, 7.4, 10.1, 10.2, 10.3, 10.4_
    - _Depends on: 4.4, 4.5, 4.6_
  - [x] 5.4 Changeset integration test
    - `Changeset207208ItTest`: project nullable, existing rows intact, category FK `SET NULL`, seeds idempotent on re-run.
    - _Requirements: 1.1, 3.1, 10.1, 10.2_
    - _Depends on: 1.2, 3.1_

- [x] 6. Frontend (`foremen-frontend/`)
  - [x] 6.1 Locale keys
    - `nav.addExpense`, `companyProcurement.*`, `expense.*`, `notification.procurement.relinked`, new `error.*` keys in `pl.json` and `ru.json` (identical key sets).
    - _Requirements: 13.2, 13.3, 13.4_
    - _Depends on: none_
  - [x] 6.2 Expense upload API, types and schema
    - `expense-api.ts` (`createExpense`, `fetchExpenseOptions`, `fetchExpenseStatus`), types, zod schema (file required, cash amount > 0 when the toggle is on).
    - _Requirements: 7.1, 8.6_
    - _Depends on: none_
  - [x] 6.3 `ExpenseUploadForm`, `ExpenseUploadDialog`, `ExpenseUploadPage`
    - Shared form (file, project incl. "No project", category, paid-in-cash toggle with amount/date), progress, status polling, "Add another", company-screen link only with `COMPANY_PURCHASES/READ`; mobile capture.
    - _Requirements: 8.1, 8.3, 8.4, 8.5, 8.6, 14.4_
    - _Depends on: 6.1, 6.2_
  - [x] 6.4 Company purchases API and types
    - `company-purchases-api.ts` (documents, lines, document, relink, reprocess, delete), types, `lib/linkStatus.ts`.
    - _Requirements: 4.4, 4.5, 4.6_
    - _Depends on: none_
  - [x] 6.5 `CompanyProcurementPage`
    - Filter bar (URL-synced), grouped/flat toggle, link-status badges, viewer link, `RelinkDialog`, reprocess/delete, deep link `?doc=`, project-line "Open in project", empty/loading/error states.
    - _Requirements: 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 14.4_
    - _Depends on: 6.3, 6.4_
  - [x] 6.6 Navigation, router and route permissions
    - Re-point `/company/procurement` to `COMPANY_PURCHASES/READ`; add `/expenses/new` main-menu item (`bottomNav: true`, `EXPENSE_UPLOAD/CREATE`); `router.tsx` lazy routes; `route-permissions.ts` entries; update tests asserting the old permission.
    - _Requirements: 8.4, 9.1, 10.6_
    - _Depends on: 6.3, 6.5_
  - [x] 6.7 Project tab category and ledger dialog
    - Category select in the project Procurement upload (`expenseCategoryId` in multipart); `UploadInvoiceDialog` project picker optional, calling `POST /api/procurement-documents/company` when empty.
    - _Requirements: 8.2, 8.7, 7.7_
    - _Depends on: 6.2, 4.8_
  - [x] 6.8 Frontend tests
    - `ExpenseUploadForm`, `ExpenseUploadPage`, `CompanyProcurementPage`, `RelinkDialog`, nav/route-permission tests, pl/ru locale parity.
    - _Requirements: 8.1, 8.4, 9.1, 9.3, 9.6, 13.2_
    - _Depends on: 6.6, 6.7_

- [x] 7. Author test-cases.md
  - [x] 7.1 Author test-cases.md
    - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD report template). Scenarios are UI browser-engine scenarios plus API checks against the Dockerized app. Written in Russian.
    - _Requirements: 1–14_
    - _Depends on: 4.6, 6.8_

- [x] 8. Verification and registry
  - [x] 8.1 Docker-stack verification
    - Bring up the stack, confirm changesets 207–210 applied, upload as WORKER with and without a project and with cash, view the company screen as FINANCIER/MANAGER, relink as FINANCIER, confirm MANAGER cannot relink, confirm the notification and nav visibility per role.
    - _Requirements: 4, 5, 7, 8, 9, 10, 12_
    - _Depends on: 5.3, 6.8, 7.1_
  - [x] 8.2 Update OVERVIEW and registries
    - Update `FOR-07-company-wide/OVERVIEW.md` row 03 (guard decision, resources, new endpoints, changesets 207–210) and `PROGRESS.md`.
    - _Requirements: 1.4_
    - _Depends on: 4.6, 6.6_

- [x] 9. Checkpoint
  - [x] 9.1 Final checkpoint
    - Run affected backend classes and frontend vitest files, confirm all green, and verify the git status in both repos.
    - _Requirements: 1–14_
    - _Depends on: 8.1, 8.2_
