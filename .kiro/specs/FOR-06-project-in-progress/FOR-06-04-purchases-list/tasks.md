# Implementation Plan: FOR-06-04 — Reconciled purchases list

## Overview

This plan delivers procurement **reconciliation** as a thin additive vertical slice over FOR-06-03:
the backend `ProcurementLine` extension (one nullable FK column + a broadened `MatchStatus` enum), a
project-scoped reconciliation service (re-link / clear-match / classify / clear-classification + the
reconciled-list read model), the pure re-process manual-preservation partition that completes
FOR-06-03 R8.4, a `@PermissionResource("PURCHASES")` controller, and the frontend Purchases panel
wired into the already-reserved `purchases` execution tab.

It introduces **no** new managed entity, **no** new ABAC resource, and **no** new dictionary — so the
`.kiro/steering/entity-creation-rules.md` checklist produces no resource seed. It **extends** the
FOR-06-03 `ProcurementLineEntity`, **rides** the FOR-06-03 `PURCHASES` resource (reconciliation =
UPDATE, list = READ), and **reuses** the FOR-06-00 `UnplannedExpenseCategory` dictionary and the
FOR-06-03 `PlannedMaterialCandidateProvider`.

**Ownership boundary.** This spec owns reconciliation (manual re-link, unplanned-expense
classification, the reconciled list, the manual-preservation re-process rule). It does NOT own
intake / OCR / auto-match (FOR-06-03) or the materials matrix (FOR-06-05). It never edits the
verbatim OCR values — it reconciles the *match*, not the extracted data.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — changeset `170`, the `ProcurementLineEntity` extension + `MatchStatus` broadening,
  the reconciliation service + partitioner + controller + read-model DTOs/mappers, the extended
  FOR-06-03 `reprocess` wiring, the backend `error.purchases.*` message bundle, and this spec. Commit
  from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the `purchases` feature folder, the `purchases` tab
  `lazy` swap, the API client, and the `pl.json`/`ru.json` keys. Commit from inside
  `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Migrations.** The single additive changeset begins at `170` (current changelog tail after FOR-06-03
is `169`; if a sibling spec lands a changeset first, use the next free `NNN`). It follows the
`NOT columnExists` + `onFail="MARK_RAN"` idempotency convention and is appended **last** in
`changelog.xml`. **No resource seed** (rides FOR-06-03 `PURCHASES`).

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected test classes with `--tests`, redirect to
`/tmp/for-06-04-test.log`, and read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks. Backend
property tests use **jqwik**; frontend property tests use **fast-check + Vitest** (minimum 100
iterations each, tagged `Feature: FOR-06-04-purchases-list, Property {n}: ...`).

## Parallel execution plan (waves)

- **Wave 0:** 1.1 — the additive `procurement_lines.unplanned_category_id` changeset (170), first in
  the changelog chain for this spec.
- **Wave 1 (parallel):** 2.1 (entity extension + `MatchStatus` broadening), 3.1 (the pure
  `ReprocessLinePartitioner`), 6.1 (frontend `purchases` tab `lazy` swap scaffold) — mutually
  independent; 2.1 needs the column (1.1), 3.1 is a pure self-contained unit, 6.1 lives in the
  frontend repo.
- **Wave 2 (parallel):** 2.2 (read-model + request DTOs/mappers), 3.1a (partitioner property tests) —
  2.2 needs the entity (2.1); 3.1a needs the partitioner (3.1).
- **Wave 3:** 4.1 (`PurchaseLineReconciliationService` — relink / clearMatch / classify /
  clearClassification / list + the `assertInvariant` helper, wiring the candidate provider +
  dictionary + partitioner into FOR-06-03 `reprocess`) — needs the entity, DTOs, and partitioner.
- **Wave 4:** 4.1a (service property/unit tests) — needs the service.
- **Wave 5:** 5.1 (`PurchasesController` + the extended FOR-06-03 `reprocess` endpoint wiring) —
  needs the service.
- **Wave 6:** 7.1 (frontend `PurchasesPanel` + RelinkControl + ClassifyControl + MatchStateBadge +
  api client) — needs the controller live and the tab scaffold (6.1).
- **Wave 7 (parallel):** 8.1 (i18n PL+RU + backend messages), 8.2 (backend tests), 8.3 (frontend
  tests) — independent.
- **Wave 8:** 9 (Author test-cases.md) — needs the full slice.
- **Wave 9:** 10 (final verification + checkpoint) — depends on everything.

## Tasks

- [x] 1. Backend: additive migration
  - [x] 1.1 Add the `procurement_lines.unplanned_category_id` changeset
    - Add `170-add-procurement-line-unplanned-category.xml`: `addColumn procurement_lines
      .unplanned_category_id BIGINT NULL` + `addForeignKeyConstraint` referencing
      `unplanned_expense_categories(id)` with `ON DELETE SET NULL`. Guard `NOT columnExists` +
      `onFail="MARK_RAN"`; register **last** in `changelog.xml`. **No resource seed** (rides the
      FOR-06-03 `PURCHASES` resource).
    - _Requirements: 1.1, 1.3_
    - _Depends on: none_

- [x] 2. Backend: entity extension + DTOs
  - [x] 2.1 Extend `ProcurementLineEntity` + broaden `MatchStatus`
    - Add the `unplannedCategory` `@ManyToOne(fetch = LAZY) @JoinColumn(name =
      "unplanned_category_id")` FK to `UnplannedExpenseCategoryEntity` (design §4.1). Broaden the
      `MatchStatus` enum to `{AUTO, UNMATCHED, MANUAL_MATCHED, MANUAL_UNMATCHED}` (design §4.2) — a
      string column, no schema change; existing rows keep `AUTO`/`UNMATCHED`.
    - _Requirements: 1.1, 1.2_
    - _Depends on: 1.1_
  - [x] 2.2 Add the reconciled-list read model + request DTOs + mappers
    - `PurchaseLineDtoModel` + `PurchaseLineListResponseModel` (design §5.3): each row carries the
      source document (`documentId`, `supplierName`, `uploadedAt`), `lineNo`, `rawName`, `quantity`,
      `unitHint`, `unitPrice`, `lineTotal`, `manufacturerHint`, the matched-material **localized
      name** (`ru → pl → code`) or null, `matchTier`, the derived tier percent (100/80/50/0),
      `matchStatus`, and the `unplannedCategory` **localized name** (`ru → pl → code`) or null. Request
      DTOs `RelinkRequestModel{ materialId }`, `ClassifyRequestModel{ categoryId }`. Service +
      controller mappers. No `nameRU`/`namePL` columns on the entity.
    - _Requirements: 4.1, 9.1_
    - _Depends on: 2.1_

- [x] 3. Backend: pure re-process partitioner
  - [x] 3.1 Implement `ReprocessLinePartitioner`
    - Add the pure, deterministic `@Component` (design §5.2) with `partition(List<ProcurementLineEntity>)
      → Partition(preserve, replace)`: `preserve = matchStatus ∈ {MANUAL_MATCHED, MANUAL_UNMATCHED}`,
      `replace = matchStatus ∈ {AUTO, UNMATCHED}`; `preserve ∪ replace` = input, `preserve ∩ replace`
      = ∅. No I/O.
    - _Requirements: 5.1, 5.2, 5.3_
    - _Depends on: none_
  - [x] 3.1a Write property tests for the partitioner (jqwik)
    - **Property 4: Re-process partition preserves manual and replaces auto** — **Validates:
      Requirements 5.1, 5.2, 5.3**. Generate prior line sets over all four match states (with/without
      an unplanned category, including the all-auto and all-manual extremes); assert the partition is
      a disjoint cover and that `preserve` is empty iff no line is manual.
    - _Depends on: 3.1_

- [x] 4. Backend: reconciliation service
  - [x] 4.1 Add `PurchaseLineReconciliationService`
    - Project-scoped over `ProcurementLineEntity` (design §5.1); `getProjectIdPath()` →
      `"document.project.id"`; standard CRUD plumbing. **`relink(lineId, materialId)`**: stage-gate
      (`error.purchases.stage.notActive`); verify `materialId ∈ PlannedMaterialCandidateProvider
      .candidates(projectId)` (else `error.purchases.relink.notPlanned`, line unchanged); set
      `matchedMaterialId`, `matchStatus=MANUAL_MATCHED`, `matchTier=EXACT`, `unplannedCategory=null`;
      audit. **`clearMatch(lineId)`**: stage-gate; `matchedMaterialId=null`,
      `matchStatus=MANUAL_UNMATCHED`, `matchTier=NONE`; audit. **`classify(lineId, categoryId)`**:
      stage-gate; reject a matched line (`error.purchases.classify.matchedLine`); verify the category
      exists + `active` (else `error.purchases.classify.unknownCategory`); set `unplannedCategory`,
      `matchStatus=MANUAL_UNMATCHED`, `matchedMaterialId=null`, `matchTier=NONE`; audit.
      **`clearClassification(lineId)`**: stage-gate; `unplannedCategory=null`, keep
      `MANUAL_UNMATCHED`; audit. **`list(projectId, filters)`**: the reconciled read model,
      project-filtered, with optional `documentId`/`matchStatus`/`unplannedCategoryId` filters. Add
      the pure `assertInvariant(line)` helper (design §4.3) run before each save. Wire the
      `ReprocessLinePartitioner` into the FOR-06-03 `reprocess(id)` path so only `AUTO`/`UNMATCHED`
      lines are replaced and `MANUAL_*` lines are preserved + renumbered (R5.1). Emit **no**
      notifications (R8.1).
    - _Requirements: 1.4, 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4, 5.1, 5.2, 7.3, 7.4, 8.1, 10.1_
    - _Depends on: 2.2, 3.1_
  - [x] 4.1a Write property/unit tests for the service (jqwik + stubbed seams)
    - **Property 1: Line-invariant totality** across every reconciliation action — **Validates:
      Requirements 1.4, 2.1, 2.3, 3.1, 3.2**.
    - **Property 2: Re-link candidate-set soundness** (on-estimate ⟺ MANUAL_MATCHED; off-estimate →
      reject, unchanged) — **Validates: Requirements 2.1, 2.2**.
    - **Property 3: Classify rejected on a matched line** — **Validates: Requirements 3.1, 3.3**.
    - **Property 5: Reconciled-list projection fidelity** (project-only, `ru → pl → code` names,
      filter correctness) — **Validates: Requirements 4.1, 4.2, 4.3**.
    - Drive the service with a stubbed `PlannedMaterialCandidateProvider` (configurable candidate set,
      incl. empty + off-estimate ids) and an in-memory `UnplannedExpenseCategory` lookup (active +
      inactive + unknown). Add example unit tests for the stage gate on a non-`ACTIVE` project, the
      `clearClassification` keeps-`MANUAL_UNMATCHED` case, the extended `reprocess` preserving a manual
      line (stubbed OCR), the no-notification assertion, and audit rows on each op.
    - _Depends on: 4.1_

- [x] 5. Backend: controller
  - [x] 5.1 Add `PurchasesController`
    - `@RestController @PermissionResource("PURCHASES")` under `/api/procurement-lines` (design §5.4):
      `GET /?projectId=&documentId=&matchStatus=&unplannedCategoryId=` → READ (reconciled list);
      `PUT /{id}/match` (body `{ materialId }`) → UPDATE; `DELETE /{id}/match` → UPDATE (clear match);
      `PUT /{id}/classify` (body `{ categoryId }`) → UPDATE; `DELETE /{id}/classify` → UPDATE (clear
      classification). Each in-scope handler carries a matching `@PermissionOperation`/
      `@RequiresPermission`. Verify the app starts (no `PermissionAnnotationValidator` failure) and
      that **no new ABAC resource** was added (the `PURCHASES` matrix is unchanged from FOR-06-03).
    - _Requirements: 6.1, 7.1, 7.2_
    - _Depends on: 4.1_

- [x] 6. Frontend: tab wiring
  - [x] 6.1 Swap the `purchases` tab panel
    - In the workspace tab registry, change the `purchases` entry's `lazy` from `PlaceholderTab` to
      the new `PurchasesPanel`. Do not touch the `WORKSPACE_TABS` contract shape,
      `resolveWorkspaceTabs`, or `stageOf`. The `workspace.tab.purchases` label is already seeded by
      FOR-06-00 — reuse unchanged.
    - _Requirements: 7.5_
    - _Depends on: none_

- [x] 7. Frontend: purchases panel
  - [x] 7.1 Build the `purchases` feature folder
    - `features/project-execution/purchases/` with `PurchasesPanel.tsx` (reconciled DataTable: source
      document, raw name, amounts, matched-material name + `TierBadge` or unplanned-category name,
      `MatchStateBadge`; filters by document / match state / unplanned category; read-only when the
      caller has only READ or the project is `COMPLETED`), `components/RelinkControl.tsx` (planned-
      material picker from the project's candidate set + clear-match, inline `relink.notPlanned`),
      `components/ClassifyControl.tsx` (active-`UnplannedExpenseCategory` picker + clear-classification,
      enabled only on an unmatched line, inline `classify.matchedLine`/`classify.unknownCategory`),
      `components/MatchStateBadge.tsx` (localized `auto`/`unmatched`/`manualMatched`/`manualUnmatched`),
      `components/TierBadge.tsx` (reuse/mirror FOR-06-03), `api/purchases-api.ts` (`list / relink /
      clearMatch / classify / clearClassification` via the shared `apiRequest` + `buildFetchQuery`),
      `schemas/`, `types/`, `__tests__/`.
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
    - _Depends on: 5.1, 6.1_

- [x] 8. i18n + tests
  - [x] 8.1 Add i18n keys (PL + RU, mirrored) + backend messages
    - Add the `purchases.*` feature block (design §6.1): page/list title + columns, filters, match-
      state badges (`auto`/`unmatched`/`manualMatched`/`manualUnmatched`), re-link labels, classify
      labels, toasts, and the inline error codes (`notPlanned`/`classifyMatchedLine`/`unknownCategory`/
      `notActive`) to **both** `pl.json` and `ru.json` with real PL + RU values (no English fallback).
      Reuse `workspace.tab.purchases` (FOR-06-00) unchanged. Add every `error.purchases.*` code to the
      backend PL + RU message bundle. Run the locale-parity test.
    - _Requirements: 9.2, 9.3, 9.4_
    - _Depends on: 4.1, 5.1, 7.1_
  - [x] 8.2 Backend example / integration / smoke tests
    - The additive migration (changeset 170) + idempotent re-run via Testcontainers (R1.1); the
      `ON DELETE SET NULL` behaviour on deleting a referenced category (R1.3); audit rows on each
      reconciliation op (R10.1); the extended FOR-06-03 `reprocess` end-to-end preserving a manual
      line (R5.1, stubbed OCR); a startup smoke asserting `PermissionAnnotationValidator` classifies
      `PurchasesController` COMPLETE (R7.2) and that **no new ABAC resource** was seeded (the
      `PURCHASES` matrix unchanged). Run only the affected classes with `--tests` →
      `/tmp/for-06-04-test.log`; verify via JUnit XML. (Line-invariant, candidate-gate, classify, and
      partition property tests live in 3.1a / 4.1a.)
    - _Requirements: 1.1, 1.3, 5.1, 7.2, 10.1, 10.2_
    - _Depends on: 1.1, 4.1, 5.1_
  - [x] 8.3 Frontend unit tests (Vitest + fast-check)
    - `PurchasesPanel` render + filters (R6.1); the `RelinkControl` / `ClassifyControl` flows + inline
      server-error mapping (R6.2, R6.5); `MatchStateBadge` over the four states + `TierBadge` over the
      four tiers (R6.3); the read-only assertion (controls hidden for READ-only + on `COMPLETED`,
      R6.4). **Property 6: Locale parity** over `pl.json` / `ru.json` — **Validates: Requirements
      9.2**; plus a no-raw-key render assertion (R9.4). Resolver fixtures (`purchases` present on
      `ACTIVE` with `PURCHASES`, read-only on `COMPLETED`, absent on `DRAFT`, R7.4/R7.5).
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 7.4, 7.5, 9.2, 9.4_
    - _Depends on: 7.1, 8.1_

- [x] 9. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown, a
    regression group, the MD report template). This is an **API + UI** spec: the reconciliation
    endpoints (re-link on-estimate / off-estimate, clear-match, classify unmatched / matched-reject /
    unknown-category, clear-classification, the reconciled-list filtering, the stage gate, the
    re-process manual-preservation) are **API tests** against the Dockerized app
    (`http://localhost:8080`, auth under `/api/auth`); the Purchases panel (list → re-link → classify
    → read-only gating → match-state/tier badges) is a **browser-engine** scenario on an `ACTIVE`
    project. Procurement lines are seeded directly (or via a FOR-06-03 disabled-OCR document) so the
    scenarios do not depend on a live OCR provider. Written in Russian. Result artifacts are MD
    reports with tables.
  - _Requirements: 2.1, 2.2, 2.3, 3.1, 3.3, 3.4, 4.1, 4.2, 5.1, 6.1, 6.4, 7.4, 10.3_
  - _Depends on: 5.1, 7.1, 8.1, 8.2, 8.3_

- [x] 10. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava compileTestJava` → temp log) and run only the affected
    test classes with `--tests` → `/tmp/for-06-04-test.log` (verify via JUnit XML); type-check +
    unit-test the frontend; confirm the locale-parity test passes and the app starts (no
    `PermissionAnnotationValidator` failure, no new ABAC resource). Confirm the `purchases` tab
    appears for a permitted caller on an `ACTIVE` project and the reconcile flow (re-link → classify →
    clear → read-only on `COMPLETED`) works end-to-end, and that a re-process preserves a manually
    reconciled line. Ensure all tests pass; ask the user if questions arise. Then stage and commit per
    repo (two commits — root for backend/migration/spec, `foremen-frontend/` for the panel) — only if
    the user asks.
  - _Requirements: all_
  - _Depends on: 1.1, 2.1, 2.2, 3.1, 4.1, 5.1, 6.1, 7.1, 8.1, 8.2, 8.3, 9_

## Notes

- Tasks marked with `a` suffixes are the property/unit test tasks placed next to the implementation
  they validate (partitioner, service) to catch errors early.
- Each task references specific requirement sub-clauses for traceability.
- Backend property tests use **jqwik**; frontend property tests use **fast-check + Vitest** (minimum
  100 iterations, tagged with the design property number).
- The feature spans two git repos — backend/migration/spec in the root repo, the Purchases panel in
  `foremen-frontend/` — so a full commit is two commits, one per repo.
- **No new entity / resource / dictionary.** This spec extends the FOR-06-03 line, rides the
  FOR-06-03 `PURCHASES` resource, and reuses the FOR-06-00 `UnplannedExpenseCategory` dictionary.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1", "3.1", "6.1"] },
    { "id": 2, "tasks": ["2.2", "3.1a"] },
    { "id": 3, "tasks": ["4.1"] },
    { "id": 4, "tasks": ["4.1a"] },
    { "id": 5, "tasks": ["5.1"] },
    { "id": 6, "tasks": ["7.1"] },
    { "id": 7, "tasks": ["8.1", "8.2", "8.3"] },
    { "id": 8, "tasks": ["9"] },
    { "id": 9, "tasks": ["10"] }
  ]
}
```
