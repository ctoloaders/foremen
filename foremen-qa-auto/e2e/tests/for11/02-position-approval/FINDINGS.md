# FOR-11-02 position approval: e2e findings

Spec: `.kiro/specs/FOR-11-Delivery-Management/FOR-11-02-position-approval/` (test-cases.md, requirements.md, design.md).
Stack: local Docker (frontend :3000, backend :8080, `foremen-postgres-1`).

## 1. Summary

**64 passed / 7 skipped as product bugs (6 distinct bugs) / 5 cases not automated (A6-03, R-04, R-05, R-06, R-08); several others are automated only partly (see table).**
Final run (from `foremen-qa-auto/e2e`):

```
export PATH=/Users/alexander.borohov/.nvm/versions/node/v24.18.0/bin:$PATH NPM_CONFIG_USERCONFIG=/Users/alexander.borohov/.kiro/settings/e2e.npmrc
npx e2e run tests/for11/02-position-approval --action-timeout 5000 --retries 0
```
Result: `Test Files 6 passed (6)`, `Tests 64 passed | 7 skipped (71)`, `EXIT=0` (about 8 minutes, one fresh user/project/material set per test).

Repeatability: generator + teardown. Every test builds an `Env` (support.ts) with a run-id: users `dash+<run>-{mgr,cl,fm,by,mgr2,cl2}@example.com`,
projects `QA-A-<run>` / `QA-B-<run>`, materials `QA-MAT-<run>-<n>`, positions with `notes = qa-<run>-<n>`, seller `SELLER-SENTINEL-<run>`.
`Env.cleanup()` runs in `finally` (rounds/options/variants/positions, notifications (twice, because emission is async), materials, seller, projects, users).
After the final run psql shows 0 leftover projects, positions, materials, sellers and test users for these run-id patterns.
Earlier failed iterations left some rows; they were removed by hand with run-id scoped SQL.

Files (all in `tests/for11/02-position-approval/`): `support.ts`, `api-access.e2e.ts`, `api-send-decide.e2e.ts`, `api-analog-choice.e2e.ts`,
`api-behalf-notify.e2e.ts`, `ui-client.e2e.ts`, `ui-staff.e2e.ts`.

## 2. Coverage

| TC | Test (file: title) | Status |
|----|--------------------|--------|
| A1-01 | api-access: TC-A1-01 | pass |
| A1-02 | api-access: TC-A1-02 (step 1) and skipped "TC-A1-02 step 2" | step 1 pass; step 2 **BUG-1** |
| A1-03 | api-access: TC-A1-03 | pass |
| A1-04 | api-access: TC-A1-04 | pass |
| A1-05 | api-access: TC-A1-05 | pass |
| A2-01..05 | api-send-decide: TC-A2-01..05 | pass |
| A3-01..05 | api-send-decide: TC-A3-01..05 | pass |
| A4-01, A4-03 | api-send-decide: TC-A4-01, TC-A4-03 | pass |
| A4-02 | api-send-decide: TC-A4-02 (400 + round stays OPEN) and api-behalf-notify skipped "TC-A9-01 step 2 / TC-A4-02" | partial; error key **BUG-3** |
| A5-01..04 | api-analog-choice: TC-A5-01..04 | pass |
| A6-01 | api-analog-choice: TC-A6-01 (two tests: 3 options/no prices; 1 and 6 options rejected) | pass |
| A6-02 | api-analog-choice: skipped TC-A6-02 | **BUG-2** |
| A6-03 | not automated: the send-choice API has no price field in an option, so the negative case cannot be sent | not automated |
| A7-01, A7-02 | api-analog-choice: TC-A7-01, TC-A7-02 (create, notification, resolve via analog, decline with reason) | pass |
| A8-01..03 | api-analog-choice: TC-A8-01..03 | pass |
| A9-01 | api-behalf-notify: TC-A9-01 (accept on behalf, BUYER 403, blank comment 400, flags, no manager notification) | pass (key of blank-comment error: BUG-3) |
| A10-01..03 | api-behalf-notify: TC-A10-01..03 | pass |
| A11-01 | api-behalf-notify: TC-A11-01 (all client GETs and POSTs, sentinel values, forbidden keys, staff control) | pass |
| A12-01, A12-02 | api-behalf-notify: TC-A12-01/02 | pass |
| A12-03 | api-behalf-notify: TC-A12-03 | pass |
| A12-04 | covered inside api-analog-choice TC-A7-02 | pass |
| A12-05 | api-behalf-notify: TC-A12-05 (manager and the created buyer; the "assigned buyer only" branch needs a purchase request, not built) | partial |
| A12-06 | covered inside TC-A9-01 | pass |
| A12 (foreign notification) | api-behalf-notify: "reading or deleting somebody else's notification" | pass |
| A13-01 | api-behalf-notify: TC-A13-01 (resource once, no duplicate grants, index exists) and concurrent double send (200 + 409) | partial: re-running `liquibase` twice not done (containers must not be restarted) |
| R-01, R-02, R-03, R-07 | api-behalf-notify: regression group | pass |
| R-04, R-05, R-06, R-08 | R-04 (FOR-05-07 offer tab) and R-05 (chat tab) belong to other specs; R-06 only via TC-B4-01; R-08 needs a liquibase restart | not automated |
| B1-01 | ui-client: TC-B1-01 (client sees tab, no procurement tabs; foreman and design stage: no tab) | pass |
| B1-02 | ui-client: TC-B1-02 (badge 2 then 1) | pass |
| B2-01 | ui-client: TC-B2-01 (block order, deadline text, overdue badge, filters pending/accepted/all; "Kupuję sam" filter not exercised) | pass (partial) |
| B2-02 | ui-client: TC-B2-02 (card, 100,00 / 8 / 108,00 / 216,00, no 61,37, seller, "Marża", "Zakup") | pass |
| B3-01 | ui-client: TC-B3-01 | pass |
| B3-02 | ui-client: skipped TC-B3-02 | **BUG-4** |
| B3-03 | ui-client: TC-B3-03 | pass |
| B3-04 | ui-client: TC-B3-04 | pass |
| B3-05 | ui-client: TC-B3-05 | pass |
| B3-06 | ui-client: TC-B3-06 (before/after compare, 97,20 gross, accept shows new material) | pass |
| B3-07 | ui-client: TC-B3-07 (3 options, no prices) and skipped "TC-B3-07 step 2" | step 1 pass; step 2 **BUG-2** |
| B3-08 | ui-client: TC-B3-08 | pass |
| B4-01 | ui-client: TC-B4-01 (bell text, click, tab opens, `?position=` card gets `ring-2` highlight; the 2 s fade is not asserted) | pass |
| B5-01 | ui-client: TC-B5-01 (360 px: no horizontal scroll, one column, button visible) | pass |
| B5-02 | ui-client: TC-B5-02 (RU tab name, status, dialog, no `positionApproval.*` keys; PL is the default of every other test) | pass |
| B5-03 | ui-client: TC-B5-03 (route log: no `/api/purchase-*`, `/api/procurement/*`, `/api/finishing-materials`, no staff endpoints) | pass |
| B6-01 | ui-staff: TC-B6-01 (internal price/margin visible, not-eligible list, send, status "Zaproponowane"); the deadline date picker is not driven | pass (partial). Sending a never-sent block is impossible in the UI: **BUG-1** |
| B6-02 | ui-staff: TC-B6-02 (preview shows sale price only; the material picker is not driven, so the final analog submit is covered by the API tests) | partial |
| B6-03 | ui-staff: TC-B6-03 | pass |
| B6-04 | ui-staff: TC-B6-04 (inbox shows link and comment, "propose analog" opens the dialog) and skipped "TC-B6-04 step 2" | step 1 pass; decline **BUG-5** |
| B6-05 | ui-staff: TC-B6-05 (manager buttons) and skipped "TC-B6-05 step 2" | manager pass; buyer **BUG-6** |

## 3. Bugs

All seven skipped tests keep the correct expectation and carry `// BUG-<n> (FINDINGS.md): ...`. Remove `.skip` when fixed.

### BUG-1: staff view never lists positions that were not sent yet
- Where: `GET /api/position-approvals/staff/projects/{projectId}`; staff tab `/projects/{id}/positionApprovals` (manager).
- Repro: create an ACTIVE project and an AGREED position in `PLANNED` (no round), call the staff list as MANAGER, or open the tab.
- Observed: `200 []`; the tab shows "Brak pozycji w uzgodnieniu". Only positions that already have at least one round are returned.
- Expected: test-cases A1-02 step 2 ("P1 present"), requirements R19.1 ("list the project's positions in AGREED and PACKAGE paths") and R19.2 (send a block of positions, with not-eligible ones listed). The UI has a "Nie wysłano" row state and a `SEND` action for round-less rows, which can never occur.
- Impact: the first send of a block and the "price missing" list in the send dialog cannot be used from the UI. Only re-sends after a reject or withdraw work (the tests prepare positions that way). The API `POST /staff/send` itself works.
- Evidence: `TC-A1-02 step 2` (skipped); manual API check returns `[]`; B6 tests have to send through the API first.
- Layer: backend (design.md section 4 also says "positions with at least one round", so the design and R19.1 disagree).
- Suspected file: `foremen-backend/.../service/procurement/approval/StaffApprovalQueryService.java`, constant `HAS_ROUND` added to every query in `queryPositions`.
- Requirement: R19.1, R19.2, R4 (staff view). Feature-sized: no; small query change (include AGREED/PACKAGE `PLANNED` positions without rounds), plus a decision on the design text.

### BUG-2: a package choice cannot be confirmed by the client (400)
- Where: `POST /api/position-approvals/client/rounds/{roundId}/accept`; client tab, card with "Wybór z pakietu".
- Repro: `send-choice` with 2..5 materials, then accept as CLIENT with `{optionId}` (no price) or with `expectedUnitSalePrice: null` (what the frontend posts).
- Observed: `400 "Błąd walidacji"`, `fieldErrors.expectedUnitSalePrice: "nie może mieć wartości null"`. In the UI the choice is never saved.
- Expected: test-cases A6-02 / B3-07: pick the second option without a price, `200/204`, material fixed, round `ACCEPTED`. The service already handles the price-less case (`PositionApprovalService` around line 346 for `PACKAGE_CHOICE`).
- Evidence: tests `TC-A6-02` and `TC-B3-07 step 2` (skipped). Staff path `decide-on-behalf` for a package choice returns 204, so only the client DTO blocks it.
- Layer: backend (DTO validation); the frontend correctly sends `null`.
- Suspected file: `PositionApprovalClientController.AcceptRequest`: `@NotNull BigDecimal expectedUnitSalePrice`.
- Requirement: R7 (accept), R8 (package choice), R18.3/R18.4. Feature-sized: no (drop `@NotNull`, keep the service check).

### BUG-3: blank comment returns the generic validation error, not `decision.commentRequired`
- Where: `POST /api/position-approvals/client/rounds/{id}/reject` and `POST /api/position-approvals/staff/rounds/{id}/decide-on-behalf`.
- Repro: send `{"comment":""}` (also whitespace or missing).
- Observed: `400` with message "Błąd walidacji. Sprawdź wprowadzone dane." and `fieldErrors.comment`.
- Expected: `400 error.positionApproval.decision.commentRequired` ("Podaj komentarz" translation) as in test-cases A4-02 and A9-01 and requirements lines 323 and 407. The key exists in both message bundles and in the service, but the DTO annotation `@NotBlank` fires first.
- Evidence: skipped test `TC-A9-01 step 2 / TC-A4-02 ... commentRequired`; the status code (400) part is asserted by passing tests.
- Layer: backend. Suspected files: `RejectRequest.comment` (`@NotBlank`) in `PositionApprovalClientController`, `DecideOnBehalfRequest.comment` and `ClientOwnOnBehalfRequest.comment` in `PositionApprovalStaffController`.
- Requirement: R7.2 / R12.2 (comment required). Feature-sized: no. Low severity.

### BUG-4: stale / price-changed conflict is not reported in the client UI
- Where: client tab, accept dialog (also reject, client-own, suggest dialogs share the helper).
- Repro: open the accept dialog, the manager changes the price (`variants/replace`), confirm in the dialog.
- Observed: the API answers `409` (`stale`), but the UI shows no "Pozycja została zmieniona. Odśwież widok." toast, the dialog stays open and still shows the old gross price (54,00 instead of the new one).
- Expected: test-cases B3-02: toast, list reloaded, dialog closed.
- Root cause: `reportClientError` maps 409 by `ApiError.code.includes('priceChanged' | 'stale')`, but the backend `ErrorResponse` has no machine code (api-client.ts says so: "does not currently expose a machine code"), so `code` is always empty and the generic branch runs.
- Evidence: trace of `TC-B3-02` (screen at failure: `accept-dialog` open with 54,00; network `POST .../accept 409`).
- Layer: both (backend lacks a `code` in the error body, or the frontend must map by status plus message).
- Suspected files: `foremen-frontend/src/features/position-approval/components/clientDialogUtils.ts`, `foremen-frontend/src/lib/api-client.ts` (`buildApiError`), `foremen-backend/.../exception/dto/ErrorResponse.java`.
- Requirement: R18.4, R7 (409 handling). Feature-sized: no, but the fix touches the shared error contract, so check the other consumers.

### BUG-5: declining a client suggestion without a reason fails
- Where: staff tab, "Propozycje klienta", button "Odrzuć propozycję"; `POST /api/position-approvals/staff/suggestions/{roundId}/decline`.
- Repro: open the decline dialog, leave the reason empty (the label says "Powód (opcjonalnie)"), confirm.
- Observed: the UI posts `{note: null}`; the API answers `400` validation error (`fieldErrors.note`). An error toast is shown and the suggestion stays open. Verified by API for `{note:null}`, `{}` and `{note:""}`.
- Expected: test-cases B6-04 step 2: the decline requires a reason (dialog should disable the button until a reason is typed), or, if the reason is optional (as the label says), the API must accept it. UI and API contradict each other. (Requirements R10.3 name the body field `managerNote`; the implementation uses `note`.)
- Evidence: skipped `TC-B6-04 step 2`; passing API test TC-A7-02 uses a non-empty note.
- Layer: both. Suspected files: `RoundActionDialogs.tsx` (`DeclineSuggestionDialog`: `note.trim() || null`, `decline-confirm` never disabled), `PositionApprovalStaffController.DeclineRequest` (`@NotBlank String note`), locale key `positionApproval.staff.declineNote`.
- Requirement: R10.3. Feature-sized: no.

### BUG-6: a BUYER cannot open the approval tab (403 page)
- Where: `/projects/{id}/positionApprovals` as BUYER.
- Repro: sign in as a BUYER and open the project workspace.
- Observed: redirect to `/403`. The BUYER role has `POSITION_APPROVALS: READ` and the staff API answers 200, but has no `PROJECTS: READ`, which `route-permissions.ts` requires for `/projects/:projectId/:tab`.
- Expected: requirements R17 (line 488): the tab is visible to BUYER through ABAC; test-cases B6-05: BUYER sees the staff view without action buttons. The clientOwn notification (A12-05) also targets BUYERs with a deep link they cannot open.
- Evidence: trace of `TC-B6-05` (screen at failure `http://localhost:3000/403`); DB: BUYER grants are `PURCHASE_POSITIONS C/R/U`, `POSITION_APPROVALS R`, no `PROJECTS`.
- Layer: backend seed or frontend route guard. Suspected files: Liquibase seeds `234/235` (role matrix), `foremen-frontend/src/config/route-permissions.ts` (lines 107-108).
- Requirement: R17.x (tab visibility), R19.8. Feature-sized: no (a grant or a guard exception), but it needs a product decision on what else a BUYER may then see in the project.

## 4. Blockers and observations

- `POST /api/finishing-materials` returns `409 Naruszenie integralności danych` on this database: column `finishing_materials.package_volume` is `NOT NULL` with no default, is absent from the Liquibase changelog and from the entity. The tests therefore insert materials by SQL (`Env.material()`), cloned from the seeded shape. This looks like database drift or a missing changeset; it is outside FOR-11-02 and was not filed as a numbered bug.
- Project activation: projects are created through the API (DRAFT) and set to `ACTIVE` with one SQL `UPDATE`, because the readiness-gated activation is not part of this spec.
- Error bodies carry only localized text (no key), so API error checks compare the message with the RU and PL translations of the key (`errIs`, reads `messages.properties` / `messages_ru.properties`; PL is the default bundle).
- Test-cases text vs behaviour (not counted as bugs): A1-05 expects 403 for a foreign project while requirements R5.3 says 404; the implementation returns 403 (test-cases win). The decline body field is `note`, not `managerNote`.
- Notification emission is asynchronous (after commit), so tests poll and `Env.cleanup()` purges notifications twice.
- The shared `.e2e/results` directory is wiped by parallel runs from other agents; use `--output <dir inside the project>` to keep traces during triage.
- Not done: re-running `liquibase` twice (no container restarts allowed), the `Komunikacja`, FOR-05-07 offer tab regressions (R-04, R-05), assigned-buyer routing of the clientOwn notification, the date picker and material picker dialogs, the 2 s highlight fade.
