# Bug report: FOR-11-02-position-approval

Screen URL: http://localhost:3000/projects/<id>/approvals (staff view), client approval screen

## Round 1 (2026-11-16)

Source: e2e run of `foremen-qa-auto/e2e/tests/for11/02-position-approval` (64 passed, 7 skipped as bugs).
Evidence: `tests/for11/02-position-approval/FINDINGS.md`.

Decisions taken for conflicting documents: BUG-1 follows requirements R19.1 (design.md "at least one round"
is outdated); BUG-5 makes the reason required in the UI (API and test-case B6-04 agree); BUG-6 opens the
approvals tab to a role holding `POSITION_APPROVALS` READ through a route-guard exception, not a new
`PROJECTS` grant.

- [x] 1. The staff view never lists positions that were not sent yet
  - Triage: observed: `GET /staff/projects/{id}` returns `[]` until a round exists, so a first block can never be sent from the UI; expected: AGREED/PACKAGE positions listed with state "Nie wysłano" and the SEND action. Layer: backend. Suspect: `service/procurement/approval/StaffApprovalQueryService.java` (`HAS_ROUND` filter in `queryPositions`).
  - _Requirements: R19.1, R19.2_
  - _Depends on: none_
- [x] 2. The client cannot confirm a package choice (400)
  - Triage: observed: 400 `fieldErrors.expectedUnitSalePrice` because the DTO requires it; expected: a price-less choice is accepted (service already handles it). Layer: backend. Suspect: `PositionApprovalClientController.AcceptRequest` (`@NotNull expectedUnitSalePrice`).
  - _Requirements: R7, R8, R18.3, R18.4_
  - _Depends on: none_
- [x] 3. A blank comment returns the generic validation error instead of `decision.commentRequired`
  - Triage: observed: 400 "Błąd walidacji" + `fieldErrors.comment`; expected: 400 `error.positionApproval.decision.commentRequired`. Layer: backend. Suspect: `@NotBlank` on `RejectRequest.comment` (client controller), `DecideOnBehalfRequest.comment`, `ClientOwnOnBehalfRequest.comment` (staff controller).
  - _Requirements: R7.2, R12.2_
  - _Depends on: 2_ (same client controller)
- [x] 4. A 409 stale / price-changed conflict is not reported in the client UI
  - Triage: observed: 409 from the API, but no toast, dialog stays open with the old price; expected: "Pozycja została zmieniona. Odśwież widok." toast, list reloaded, dialog closed. Layer: both. Suspect: error bodies carry no machine code (`exception/dto/ErrorResponse.java`, `ForemenControllerAdvice`), `features/position-approval/components/clientDialogUtils.ts` maps by `ApiError.code`.
  - _Requirements: R18.4, R7_
  - _Depends on: none_
- [x] 5. Declining a client suggestion without a reason fails
  - Triage: observed: the dialog labels the reason optional, posts `{note:null}`, API answers 400; expected: the reason is required in the dialog (confirm disabled until typed), matching the API. Layer: frontend. Suspect: `RoundActionDialogs.tsx` (`DeclineSuggestionDialog`), locale key `positionApproval.staff.declineNote`.
  - _Requirements: R10.3_
  - _Depends on: none_
- [x] 6. A BUYER cannot open the approvals tab (redirected to /403)
  - Triage: observed: `/projects/<id>/approvals` redirects BUYER to `/403` (route needs `PROJECTS` READ; BUYER has only `POSITION_APPROVALS` READ); expected: the tab opens read-only. Layer: frontend (route guard), plus the project shell fetches. Suspect: `foremen-frontend/src/config/route-permissions.ts`, project workspace shell.
  - _Requirements: R17 (tab visibility), R19.8_
  - _Depends on: none_
- [x] V1. Validate Round 1
  - Rebuild, remove `.skip` from the BUG tests, run `npx e2e run tests/for11/02-position-approval`.
  - _Depends on: 1, 2, 3, 4, 5, 6_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1, 2, 4, 5, 6 — different files (6 edits `route-permissions.ts`, shared with FOR-11-03 BUG-1/2; done by the same executor).
- **Wave 2:** 3 — depends on 2 (client controller).
- **Wave 3:** V1

## Round 1 — fixes and validation results (2026-11-16)

| Bug | Fix | Files |
|-----|-----|-------|
| 1 | Staff view also lists AGREED/PACKAGE positions without a round (SEND/SEND_CHOICE actions); CLIENT_OWN without a round stays hidden; design.md section 4 reworded to R19.1. | `service/procurement/approval/StaffApprovalQueryService.java`, `design.md` |
| 2 | `@NotNull` removed from `AcceptRequest.expectedUnitSalePrice`; the service keeps the price rule. | `PositionApprovalClientController.java` |
| 3 | `@NotBlank` removed from the decision comments; the service answers `decision.commentRequired`. | `PositionApprovalClientController.java`, `PositionApprovalStaffController.java` |
| 4 | `ErrorResponse` carries the machine `code` (message code of `ForemenApiException`, omitted when null); the client dialogs already map `stale`/`priceChanged` to toast + refetch + close. Applies to all API errors (additive field). | `exception/dto/ErrorResponse.java`, `controller/advice/ForemenControllerAdvice.java`, `lib/api-client.ts` (comment) |
| 5 | The decline reason is required (confirm disabled until non-blank); label "Powód" / "Причина". | `RoundActionDialogs.tsx`, `types.staff.ts`, `locales/pl.json`, `locales/ru.json` |
| 6 | Route-guard alternative grant: `/projects/:id/positionApprovals` opens with `POSITION_APPROVALS` READ; for a role without `PROJECTS` READ (BUYER) the workspace renders an approvals-only page without fetching the project. | `config/route-permissions.ts`, `app/guards/PermissionGuard.tsx`, `features/project-workspace/pages/ProjectWorkspacePage.tsx` |

Tests: position-approval ABAC tests, `StaffApprovalQueryServiceIntegrationTest`, `ForemenControllerAdviceTest`,
`ForemenControllerAdviceIntegrationTest`, `ErrorResponseTest`; vitest `position-approval` (+ `declineSuggestionDialog.test.tsx`),
`config/__tests__/route-granted.for11.test.ts`.
Validation: all former BUG tests of `tests/for11/02-position-approval` run unskipped and pass; the toast waits in
`ui-client.e2e.ts` got a 15 s timeout (the accept call is slower under the full parallel run). Full
`tests/for10 tests/for11` run green.
