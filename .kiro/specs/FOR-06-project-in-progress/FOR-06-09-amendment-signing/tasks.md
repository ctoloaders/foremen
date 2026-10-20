# Implementation Plan: FOR-06-09 — Amendment signing (effect → apply deltas)

## Overview

This plan delivers the **09 half** of the amendment split as a thin **reflection + application**
layer on top of the already-built FOR-06-08 amendment domain and the FOR-05-08 signing engine. It
adds **no new entity, no new table, no new column, no new enum, and no new ABAC resource** (changelog
head stays at **179**). The feature is built from a pure-collaborator-first core outward to the wiring:

1. **Pure collaborators first** — `AllPartiesSignedDateResolver` (max `signedAt`) and
   `ScheduleDeltaCalculator` (net half-days → day shift + valid-range guard) are persistence-free and
   jqwik property-tested (Properties 5, 7).
2. **The in-process lifecycle event + emitter** — `AmendmentLifecycleEvent` (record) +
   `AmendmentNotificationEmitter` (AFTER_COMMIT, best-effort), emitting only the three
   `notification.amendment.*` types (never a `document.*` duplicate).
3. **The orchestration service** — `AmendmentSigningService` (`sendForSigning`,
   `onDocumentFullySigned`, `onDocumentReopened`), composing the FOR-06-08 reads, the FOR-05-08
   engine calls, and the two pure calculators.
4. **The after-commit observer** — `AmendmentSigningReaction` subscribes to the existing FOR-05-08
   `DocumentNotificationEvent`, filtered to `AMENDMENT`-type linked documents, and calls back into the
   service. A failure in the reaction never rolls the signing commit back.
5. **The one new endpoint + read-model additions** — `POST /api/amendments/{id}/send-for-signing`
   carrying `@RequiresPermission(AMENDMENTS, UPDATE)`, and the amendment extended model gains the
   linked document id, the read-through signing progress, and the effective date.
6. **The frontend Aneksy surface** — `SendForSigningDialog` + `AmendmentSigningProgress` wired into
   the existing `AmendmentsPanel`, plus the PL+RU i18n keys.

**Overlay, never mutate.** Becoming `EFFECTIVE` mutates nothing in the estimate / line / room-qty /
work-report rows, nothing in `Project.startDate`, and no `ProjectScheduleBarEntity`. The only schedule
write anywhere is the single `Project.endDate` shift (Property 6). Price/volume effects flow only
through the unchanged FOR-06-08 `resolveClientUnitPrice` seam — this spec changes neither that seam nor
the FOR-06-06 margin matrix; it only makes an amendment reach `EFFECTIVE` so the seam goes live.

**No new managed entity / resource** (per `.kiro/steering/entity-creation-rules.md`): the feature rides
on the existing `AMENDMENTS` resource (FOR-06-08) and `SIGNABLE_DOCUMENTS` resource (FOR-05-08). No
seed changeset, no `resources`/`role_resources`/`role_resource_operations` row is added. The only
checklist step that applies is controller annotation: the new send-for-signing handler MUST carry
`@RequiresPermission(resource = "AMENDMENTS", operation = "UPDATE")` so the controller stays fully
annotated and `PermissionAnnotationValidator` passes at startup. The `AMENDMENT` document type is not
`CONTRACT_*`, so no project-activation hand-off fires.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — the backend `com.foremen.service.execution.amendment` additions
  (`AmendmentSigningService`, `AmendmentSigningReaction`, `AllPartiesSignedDateResolver`,
  `ScheduleDeltaCalculator`, `AmendmentNotificationEmitter`, `AmendmentLifecycleEvent`), the
  `AmendmentController` endpoint, the read-model additions, the backend PL+RU message bundle, and this
  spec. Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the `features/project-amendments/` changes
  (`SendForSigningDialog`, `AmendmentSigningProgress`, panel wiring, api/hooks) and the
  `pl.json` / `ru.json` keys. Commit from inside `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**No migrations.** Changelog head remains **179**; this spec adds no Liquibase changeset.

**Test-run policy (backend)** — per `.kiro/steering/test-execution-rules.md`: do NOT run the full
Gradle suite while implementing (~20 min). Run only affected test classes with `--tests`, redirect to
`/tmp/for-06-09-test.log`, and read the log / the JUnit XML under
`foremen-backend/build/test-results/test/TEST-<fqcn>.xml`. Use `getDiagnostics` for fast per-file
checks and `./gradlew compileJava` / `compileTestJava` for compile-only verification. Example:

```
./gradlew test \
  --tests "com.foremen.service.execution.amendment.ScheduleDeltaCalculatorPropertyTest" \
  --tests "com.foremen.service.execution.amendment.AllPartiesSignedDatePropertyTest" \
  --tests "com.foremen.service.execution.amendment.AmendmentSigningServiceTest" \
  > /tmp/for-06-09-test.log 2>&1; echo "EXIT=$?"
```

## Parallel execution plan (waves)

- **Wave 0 (parallel):** 1.1, 1.2, 3.1 — the two pure calculators and the lifecycle-event record are
  all persistence-free and touch distinct files; mutually independent.
- **Wave 1 (parallel):** 1.3, 2.1 — the two pure-calculator property tests (need 1.1 / 1.2) and the
  notification emitter (needs the event record 3.1); mutually independent files.
- **Wave 2:** 4.1 — `AmendmentSigningService` (needs the two calculators 1.1/1.2 and the event+emitter
  3.1/2.1 to publish lifecycle events).
- **Wave 3 (parallel):** 3.2, 4.2, 5.1, 6.1, 7.1 — the emitter unit test (needs 2.1), the service-level
  property tests, the after-commit reaction, the controller endpoint, and the read-model additions;
  each touches a distinct file and depends only on earlier waves, so they are mutually independent.
- **Wave 4 (parallel):** 4.3, 6.2, 7.2 — the service unit/example tests, the controller/ABAC-startup
  test, and the read-model mapper test; independent test classes over already-built code.
- **Wave 5 (parallel):** 5.2, 8.1 — the reaction integration test (needs the reaction 5.1) and the
  price-seam integration test (needs an `EFFECTIVE` amendment produced via the service); distinct
  integration classes against the Docker stack.
- **Wave 6:** 9.1 — frontend `SendForSigningDialog` + `AmendmentSigningProgress` + `AmendmentsPanel`
  wiring + api/hooks (needs the endpoint 6.1 and the read-model fields 7.1).
- **Wave 7 (parallel):** 9.2, 10.1 — the frontend component tests and the PL+RU i18n keys + locale
  parity (both need the frontend module 9.1).
- **Wave 8:** 11 — Author test-cases.md (depends on the full API + UI feature).
- **Wave 9:** 12 — Final verification + checkpoint (depends on everything).

## Tasks

- [x] 1. Backend: pure collaborators (persistence-free, property-testable)
  - [x] 1.1 Implement `AllPartiesSignedDateResolver`
    - Create `com.foremen.service.execution.amendment.AllPartiesSignedDateResolver` with
      `LocalDate resolve(List<DocumentSignatureEntity> signatures)` = `max(signedAt).toLocalDate()`
      over a non-empty, all-`SIGNED` set; assert the precondition defensively (throw if empty or any
      `signedAt` is null). No persistence, no Spring wiring beyond `@Component`.
    - _Requirements: 3.1_
    - _Depends on: none_
  - [x] 1.2 Implement `ScheduleDeltaCalculator`
    - Create `com.foremen.service.execution.amendment.ScheduleDeltaCalculator` with
      `int netHalfDays(List<AmendmentLineEntity> lines)` (Σ `scheduleDeltaHalfDays` over
      `SCHEDULE_CHANGE` lines, skipping null deltas), `LocalDate shift(LocalDate endDate, int netHalfDays)`
      (`endDate.plusDays(round(netHalfDays / 2.0))`), and
      `void assertValidRange(LocalDate startDate, LocalDate shiftedEnd)` throwing a `409`
      `error.amendment.endDateInvalid` when `shiftedEnd < startDate` (reject, not clamp). Pure.
    - _Requirements: 5.1, 5.3, 5.5_
    - _Depends on: none_
  - [x] 1.3 Write property tests for the pure calculators
    - **Property 5: Net schedule delta is a pure sum over SCHEDULE_CHANGE lines**
      (`ScheduleDeltaCalculatorPropertyTest`): `netHalfDays(L) == Σ SCHEDULE_CHANGE deltas`,
      `shift(e, n) == e.plusDays(round(n/2.0))`, and the zero case leaves the date unchanged.
      **Validates: Requirements 5.1, 5.3**
    - **Property 7: The effective date is the latest signature completion time**
      (`AllPartiesSignedDatePropertyTest`): `resolve(S) == max(signedAt).toLocalDate()`,
      order-insensitive (`resolve(S) == resolve(permute(S))`). **Validates: Requirements 3.1**
    - 100+ iterations each; tag each with `// Feature: FOR-06-09-amendment-signing, Property {n}`.
    - _Requirements: 3.1, 5.1, 5.3_
    - _Depends on: 1.1, 1.2_

- [x] 2. Backend: amendment lifecycle notifications
  - [x] 2.1 Implement `AmendmentNotificationEmitter`
    - Create `com.foremen.service.execution.amendment.AmendmentNotificationEmitter` (`@Component`),
      an AFTER_COMMIT `@TransactionalEventListener(fallbackExecution = true)` on
      `AmendmentLifecycleEvent`. For each recipient, call FOR-05-07
      `NotificationService.create(recipientUserId, type, body, deepLink)` where `type` is one of
      `notification.amendment.sentForSigning` / `.reopened` / `.effective`, the body identifies the
      amendment by project + `seqNo`, and the deep-link is `/projects/{projectId}/amendments`. Mirror
      `DocumentNotificationEmitter`: best-effort, log and swallow failures (never roll back the
      transition). Emit only `notification.amendment.*` — never a `document.*` duplicate, never a
      second CLIENT-facing notification.
    - _Requirements: 7.1, 7.3, 7.4, 7.5, 7.6_
    - _Depends on: 3.1_

- [x] 3. Backend: lifecycle-event record + emitter tests
  - [x] 3.1 Define `AmendmentLifecycleEvent`
    - Create the `AmendmentLifecycleEvent` record
      `(Trigger trigger, Long amendmentId, Long projectId, Integer seqNo, List<Long> recipientUserIds)`
      with `enum Trigger { SENT_FOR_SIGNING, REOPENED, EFFECTIVE }` — an in-process event
      discriminator, not a persisted classifier.
    - _Requirements: 7.3_
    - _Depends on: none_
  - [x] 3.2 Write unit test for `AmendmentNotificationEmitter`
    - Each `Trigger` maps to the correct `notification.amendment.*` type, recipients (amendment owner
      MANAGER + project FINANCIER), and a clickable Aneksy deep-link; assert it emits no `document.*`
      type and no second CLIENT notification; a `NotificationService` failure is swallowed.
    - _Requirements: 7.2, 7.3, 7.4, 7.5, 7.6_
    - _Depends on: 2.1_

- [x] 4. Backend: `AmendmentSigningService` (orchestration + effect application)
  - [x] 4.1 Implement `AmendmentSigningService`
    - Create `com.foremen.service.execution.amendment.AmendmentSigningService` (`@Service
      @Transactional`) with `sendForSigning(Long amendmentId, SendForSigningOptions options)`,
      `onDocumentFullySigned(Long documentId)`, `onDocumentReopened(Long documentId)`.
      `sendForSigning`: load via the FOR-06-08 project-scoped read, assert `OPEN`
      (`error.amendment.notOpen`) and non-empty (`error.amendment.emptyNotSignable`); call
      `SignableDocumentService.create` (AMENDMENT type, sourceRef = amendmentId) → generate/saveBody
      from lines + signed baseline → `requestSignatures` (signers = CLIENT + MANAGER, default `AdES`,
      overridable from the dialog); set `signableDocument` and `OPEN → SENT_FOR_SIGNING`; publish
      `AmendmentLifecycleEvent(SENT_FOR_SIGNING, …)`. `onDocumentFullySigned`: resolve the linked
      amendment, no-op unless `SENT_FOR_SIGNING`; set `effectiveDate` via
      `AllPartiesSignedDateResolver`; `SENT_FOR_SIGNING → SIGNED → EFFECTIVE`; apply the
      `ScheduleDeltaCalculator` end-date shift to `Project.endDate` exactly once (guarded by status;
      `assertValidRange` before the single write); publish `AmendmentLifecycleEvent(EFFECTIVE, …)`.
      `onDocumentReopened`: no-op unless `SENT_FOR_SIGNING`; `SENT_FOR_SIGNING → OPEN`, clear
      `signableDocument`, leave lines intact; publish `AmendmentLifecycleEvent(REOPENED, …)`.
      Mutate only `Project.endDate` — never estimate/line/room-qty/work-report/`startDate`/schedule bars.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.7, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 3.4, 3.5, 5.1, 5.2, 5.3, 5.4, 5.5, 10.1, 10.2, 10.3_
    - _Depends on: 1.1, 1.2, 3.1, 2.1_
  - [x] 4.2 Write property tests for the service effect logic
    - **Property 1: Full-sign reflects onto the amendment as EFFECTIVE**
      (`AmendmentReflectionPropertyTest`): a `SENT_FOR_SIGNING` amendment linked to an all-`SIGNED`
      document ends `EFFECTIVE` with a set `effectiveDate` after `onDocumentFullySigned`.
      **Validates: Requirements 2.2, 3.2**
    - **Property 2: Decline/void reopens to OPEN preserving the overlay**
      (`AmendmentReopenPropertyTest`): `onDocumentReopened` yields `OPEN`, cleared link, lines
      unchanged (count + per-line deltas, order-insensitive). **Validates: Requirements 2.3**
    - **Property 3: SIGNED → EFFECTIVE is idempotent** (`AmendmentEffectIdempotencePropertyTest`):
      applying `onDocumentFullySigned` N≥1 times yields the same `(status, effectiveDate, endDate)`.
      **Validates: Requirements 3.3, 3.5**
    - **Property 4: The project end-date shift is applied exactly once**
      (`EndDateShiftExactlyOncePropertyTest`): `endDate` reaches `e0.plusDays(netDays)` on the first
      application and never moves again across N applications. **Validates: Requirements 5.1, 5.4**
    - **Property 6: Overlay integrity — only Project.endDate is mutated**
      (`AmendmentOverlayIntegrityPropertyTest`): estimate / line / room-qty / work-report rows,
      `startDate`, and every schedule bar unchanged; only `endDate` changes; effect derivable from
      lines + baseline. **Validates: Requirements 5.2, 10.1, 10.2, 10.3**
    - 100+ iterations each; tag each with `// Feature: FOR-06-09-amendment-signing, Property {n}`.
    - _Requirements: 2.2, 2.3, 3.2, 3.3, 3.5, 5.1, 5.2, 5.4, 10.1, 10.2, 10.3_
    - _Depends on: 4.1_
  - [x] 4.3 Write unit / example tests for `AmendmentSigningService`
    - send-for-signing: empty-amendment reject (`error.amendment.emptyNotSignable`), non-`OPEN` reject
      exhaustive across the status enum (`error.amendment.notOpen`), signer set = CLIENT + MANAGER with
      default `AdES`, `OPEN → SENT_FOR_SIGNING` + link set, no project-activation for the `AMENDMENT`
      type, single-in-flight; reflection callbacks: full-sign ends `EFFECTIVE` immediately, reopen
      clears link + preserves lines, `EFFECTIVE`-reentry is a no-op;
      `ScheduleDeltaCalculator.assertValidRange` edge cases incl. exact `shiftedEnd == startDate`.
    - _Requirements: 1.1, 1.3, 1.4, 1.5, 1.7, 2.3, 3.2, 3.3, 3.4, 5.5_
    - _Depends on: 4.1_

- [x] 5. Backend: after-commit observer + its integration test
  - [x] 5.1 Implement `AmendmentSigningReaction`
    - Create `com.foremen.service.execution.amendment.AmendmentSigningReaction` (`@Component`), an
      AFTER_COMMIT `@TransactionalEventListener(fallbackExecution = true)` on the FOR-05-08
      `DocumentNotificationEvent`. No-op unless `documentTypeCode == "AMENDMENT"`; switch on the
      trigger: `DOCUMENT_FULLY_SIGNED` → `onDocumentFullySigned`; `DOCUMENT_SIGNING_DECLINED` /
      `DOCUMENT_VOIDED` → `onDocumentReopened`; others ignored. Wrap defensively, log and swallow on
      failure so the committed signing transition is never rolled back.
    - _Requirements: 2.1, 2.4, 7.6_
    - _Depends on: 4.1_
  - [x] 5.2 Write after-commit reaction integration test (Dockerized app)
    - Driving a linked `AMENDMENT` document to `SIGNED` through the engine flips the amendment to
      `EFFECTIVE` after the signing commits; the decline/void path reopens it to `OPEN`; a thrown
      reaction leaves the signing committed. Run only this class with `--tests`.
    - _Requirements: 2.2, 2.3, 2.4, 7.6_
    - _Depends on: 5.1_

- [x] 6. Backend: controller endpoint + ABAC startup
  - [x] 6.1 Add `POST /api/amendments/{id}/send-for-signing`
    - Add the handler to the existing `AmendmentController` (`@PermissionResource("AMENDMENTS")`),
      carrying `@RequiresPermission(resource = "AMENDMENTS", operation = "UPDATE")` so the controller
      stays fully annotated. Optional body `SendForSigningOptions { SignatureMethod method;
      SignatureLevel level; }`. Delegate to `AmendmentSigningService.sendForSigning` and return the
      refreshed amendment extended model. No new `resources` row (rides on existing `AMENDMENTS` +
      `SIGNABLE_DOCUMENTS`).
    - _Requirements: 1.6, 8.1, 8.2, 8.4_
    - _Depends on: 4.1_
  - [x] 6.2 Write controller / ABAC-startup test
    - The new endpoint resolves to `AMENDMENTS` UPDATE; a role lacking the grant is rejected (`403`);
      `PermissionAnnotationValidator` passes at startup (handler annotated); assert no new `resources`
      row is introduced by this spec.
    - _Requirements: 8.1, 8.2, 8.4_
    - _Depends on: 6.1_

- [x] 7. Backend: amendment read-model additions (DTO only, no schema)
  - [x] 7.1 Extend the amendment extended model
    - Add `signableDocumentId` (`amendment.signableDocument.id`), `signingProgress`
      (`signedCount`, `totalCount`, `outstanding[]`, read-through
      `SignableDocumentService.progress(signableDocumentId)`), and `effectiveDate` to
      `AmendmentServiceExtendedModel` / `AmendmentDtoExtendedModel` and their mapper. No schema change;
      progress is read-through, never stored.
    - _Requirements: 2.5, 6.3_
    - _Depends on: 4.1_
  - [x] 7.2 Write mapper/read-model test
    - For a `SENT_FOR_SIGNING` amendment the model exposes the linked document id + read-through
      progress (signed/total + outstanding); for an `EFFECTIVE` amendment it exposes the
      `effectiveDate`; for an `OPEN` amendment the signing fields are absent/null.
    - _Requirements: 2.5, 6.3_
    - _Depends on: 7.1_

- [x] 8. Backend: price-seam integration (make the dormant seam go live)
  - [x] 8.1 Write the price-seam integration test (Dockerized app)
    - With an amendment made `EFFECTIVE` through the signing flow, a new work report on an amended
      `(workItem)` resolves `line.unitPrice + Σ extraUnitPrice`; a report dated before `effectiveDate`
      resolves the baseline `line.unitPrice`. Exercise the public `WorkReportService.create` path
      (mirror the FOR-06-08 `WorkReportAmendmentSeamTest` but with a genuinely `EFFECTIVE` amendment).
      This spec changes neither the seam formula nor the FOR-06-06 margin matrix.
    - **Property 8: Price resolution respects the effective-date boundary** — assert
      `resolveClientUnitPrice(line, r) == line.unitPrice + (r >= effectiveDate ? Σ extraUnitPrice : 0)`.
      **Validates: Requirements 4.1, 4.2, 4.4**
    - Run only this class with `--tests`.
    - _Requirements: 4.1, 4.2, 4.3, 4.4_
    - _Depends on: 4.1_

- [x] 9. Frontend: Aneksy signing surface (`foremen-frontend/`)
  - [x] 9.1 Wire the send-for-signing surface into the Aneksy panel
    - Add `features/project-amendments/components/SendForSigningDialog.tsx` (confirm + optional
      method/level pickers reusing FOR-05-08 labels; calls `POST /{id}/send-for-signing`) and
      `components/AmendmentSigningProgress.tsx` (signed/total + outstanding signers from the linked
      document; a "go to document" link to `/projects/{id}/documentSigning`). Extend
      `AmendmentsPanel.tsx`: the Send-for-signing action on an `OPEN` non-empty amendment, gated on
      `AMENDMENTS` UPDATE via `usePermission`; the progress read for `SENT_FOR_SIGNING`; the
      effective-date display; reuse `AmendmentStatusBadge` for `SENT_FOR_SIGNING` / `SIGNED` /
      `EFFECTIVE`. Add the `sendForSigning` mutation to the api client + query-hooks. A FOREMAN sees
      the status read-only with no send action.
    - _Requirements: 6.1, 6.2, 6.3, 6.4_
    - _Depends on: 6.1, 7.1_
  - [x] 9.2 Write frontend component tests
    - `SendForSigningDialog` renders for an `OPEN` non-empty amendment with `AMENDMENTS` UPDATE (method/
      level pickers); `AmendmentSigningProgress` renders signed/total + outstanding + the document link;
      the status badge renders `SENT_FOR_SIGNING` / `SIGNED` / `EFFECTIVE` via `amendments.status.*`;
      FOREMAN read-only visibility (no send action).
    - _Requirements: 6.1, 6.2, 6.3, 6.4_
    - _Depends on: 9.1_

- [x] 10. Frontend: internationalization (PL + RU)
  - [x] 10.1 Add the i18n keys and the locale-parity assertion
    - Add non-empty PL + RU values (Polish primary) for: the Send-for-signing action label + its
      confirm/send dialog (method/level reuse FOR-05-08 labels), the three error messages
      (`error.amendment.emptyNotSignable`, `error.amendment.notOpen`, `error.amendment.endDateInvalid`),
      the signing-progress labels (reusing FOR-05-08 progress labels where possible), the effective-date
      label, and the three notification types `notification.amendment.sentForSigning` / `.reopened` /
      `.effective` — in both `src/locales/pl.json` and `src/locales/ru.json`. Add the backend PL+RU
      message bundle entries for the three `error.amendment.*` codes in the root repo.
    - **Property 9: Locale parity for every introduced i18n key** — the locale-parity test asserts
      every introduced key has a non-empty value in both `pl.json` and `ru.json`.
      **Validates: Requirements 9.3**
    - _Requirements: 9.1, 9.2, 9.3_
    - _Depends on: 9.1_

- [x] 11. Author test-cases.md
  - Create `test-cases.md` in the spec folder per `.kiro/steering/test-cases.md`.
    Write `test-cases.md` (in Russian) grouped by feature area, with detailed step-by-step scenarios
      (ID, preconditions, numbered steps, expected result per step), an explicit repeatability strategy
      (run-id generator for unique data and/or teardown), a dedicated regression group, and the MD-report
      table template. This spec spans BOTH surfaces, so include: **API scenarios** against the
      Dockerized app (`docker compose up`, base URL `http://localhost:8080`) — `POST
      /api/amendments/{id}/send-for-signing` happy path + empty/non-open rejects, the signing →
      `EFFECTIVE` reflection, the decline/void → reopen path, the price-seam-live check, and the
      end-date shift; and **browser-engine UI scenarios** for the Aneksy panel — send dialog, signing
      progress, effective-date display, FOREMAN read-only. Results are MD reports with tables.
    - _Requirements: 1.1, 1.2, 1.3, 1.5, 2.2, 2.3, 3.1, 3.2, 4.2, 4.4, 5.1, 5.5, 6.1, 6.2, 6.3, 6.4, 7.3_
    - _Depends on: 4.1, 5.1, 6.1, 7.1, 8.1, 9.1, 10.1_

- [x] 12. Final checkpoint — ensure all tests pass
  - Run the affected test classes and verify green.
    Run only the affected backend classes with `--tests` filters (the pure-calculator property tests,
      the service property + unit tests, the reaction + price-seam integration tests, the controller/
      ABAC-startup test, the read-model test), the frontend component + locale-parity tests, redirect to
      `/tmp/for-06-09-test.log`, and confirm pass/fail via the JUnit XML. Do NOT run the full suite
      unless explicitly asked (~20 min). Ensure all tests pass; ask the user if questions arise.
    - _Requirements: 1.1, 2.2, 3.2, 4.4, 5.1, 6.1, 7.3, 8.4, 9.3, 10.1_
    - _Depends on: 1.3, 3.2, 4.2, 4.3, 5.2, 6.2, 7.2, 8.1, 9.2, 10.1, 11_

## Notes

- Tasks marked with `*` are optional (tests) and can be skipped for a faster MVP; core implementation
  tasks are never optional.
- Each leaf task references the specific requirements it covers and carries a `_Depends on:_`
  annotation (predecessor leaf task ids or `none`); the dependency graph is a DAG.
- **No Liquibase changeset** — changelog head stays at `179`; no new entity/resource/column (per
  `entity-creation-rules`, only the controller-annotation step applies).
- **Two repos** — backend code + the backend message bundle + this spec commit from the **root** repo;
  the `foremen-frontend/` changes commit from **inside** `foremen-frontend/` (per `git-repo-structure`).
- Property tests validate the design's correctness properties P1–P9; unit/example/integration tests
  cover the ABAC wiring, after-commit reliability, orchestration, and UI affordances.
- Per `test-execution-rules`, verification runs only the affected classes with `--tests`, never the
  full ~20-min suite unless explicitly requested.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "3.1"] },
    { "id": 1, "tasks": ["1.3", "2.1"] },
    { "id": 2, "tasks": ["4.1"] },
    { "id": 3, "tasks": ["3.2", "4.2", "5.1", "6.1", "7.1"] },
    { "id": 4, "tasks": ["4.3", "6.2", "7.2"] },
    { "id": 5, "tasks": ["5.2", "8.1"] },
    { "id": 6, "tasks": ["9.1"] },
    { "id": 7, "tasks": ["9.2", "10.1"] },
    { "id": 8, "tasks": ["11"] },
    { "id": 9, "tasks": ["12"] }
  ]
}
```
