# Bug report: FOR-11-05-goods-receipt

Screen URL: http://localhost:3000/deliveries/receipt

## Round 1 (2026-11-16)

Source: e2e run of `foremen-qa-auto/e2e/tests/for11/05-goods-receipt` (82 passed, 2 skipped as bugs,
5 environment skips: image storage token expired). Evidence: `tests/for11/05-goods-receipt/FINDINGS.md`.

- [x] 1. The `?position=` deep link does not open the position history
  - Triage: observed: the card is highlighted, but the "Do odbioru" tab stays active and no history is shown; expected: scroll, highlight and open the history of the position. Layer: frontend. Suspect: `features/goods-receipt/components/GoodsReceiptPage.tsx` (fixed tab, `position` only used for highlight), `PositionReceiptCard.tsx`, `ReceiptHistory` (already takes `positionId`).
  - _Requirements: R10.6_
  - _Depends on: none_
- [x] 2. A server 409 on Save shows only a generic error and the card is not refreshed
  - Triage: observed: "Nie udało się zapisać odbioru. Spróbuj ponownie." and the old ledger; expected: the localized `exceedsInFlight` message and the card reloaded. Layer: frontend. Suspect: `ReceiveSheet.tsx` (`onSave` bare `catch`), `confirmAcceptAll` in `GoodsReceiptPage.tsx`/`AcceptAllDialog.tsx`, `hooks/useReceiptMutations.ts` (invalidate on error).
  - _Requirements: R10.5_
  - _Depends on: 1_ (`GoodsReceiptPage.tsx`)
- [x] V1. Validate Round 1
  - Rebuild, remove `.skip` from the BUG tests, run `npx e2e run tests/for11/05-goods-receipt`.
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1
- **Wave 2:** 2
- **Wave 3:** V1

## Round 1 — fixes and validation results (2026-11-16)

| Bug | Fix | Files |
|-----|-----|-------|
| 1 | The highlighted card shows its "Historia odbioru". A linked position that is not on "Do odbioru" opens the History tab filtered by it, but only when it has receipt events; an unknown id keeps "Do odbioru" (found during validation: the first version switched to an empty history for any unknown id). | `components/GoodsReceiptPage.tsx`, `ToReceiveList.tsx`, `PositionReceiptCard.tsx` |
| 2 | Save / accept-all show the server's localized message (`lib/serverMessage.ts`), queries reload on error too, the sheet uses the refreshed item; when the refreshed data already shows the same local validation message, the server text is not repeated. | `ReceiveSheet.tsx`, `AcceptAllDialog.tsx`, `GoodsReceiptPage.tsx`, `hooks/useReceiptMutations.ts`, `lib/serverMessage.ts` |

Tests: vitest `goods-receipt` (145 tests; new deep-link, unknown-id and server-refusal tests).
Validation: former BUG tests of `tests/for11/05-goods-receipt` unskipped and pass; TC-B4-01 now waits for the
history rows before reading their order. Full `tests/for10 tests/for11` run green. Environment skips remain
(GCS credentials expired: photo upload / PDF).
