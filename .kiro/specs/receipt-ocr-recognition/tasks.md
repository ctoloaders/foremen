# Implementation Plan: OCR-распознавание чеков (Receipt OCR Recognition)

## Overview

Расширение Telegram-бота (grammY, TypeScript, Google Cloud Function) автоматическим распознаванием чеков через Google Cloud Vision + Gemini. Реализация включает: мультистраничный сбор фото, OCR-сервис, Gemini-парсинг, верификацию суммы, обновление state machine и feature flag для постепенного раскатывания.

## Tasks

- [x] 1. Подготовка инфраструктуры и интерфейсов
  - [x] 1.1 Install dependencies and update config
    - Run `npm install @google-cloud/vision @google/generative-ai`
    - Add OCR/Gemini environment variables to `src/config.ts`: `OCR_ENABLED`, `GOOGLE_CLOUD_PROJECT_ID`, `GEMINI_API_KEY`, `GEMINI_MODEL`
    - Add fail-fast validation: if `OCR_ENABLED=true` and `GEMINI_API_KEY` is missing, throw at startup
    - Update `.env.example` with new variables and comments
    - _Requirements: 7.1, 7.2, 7.3_

  - [x] 1.2 Extend state machine (`src/state/machine.ts`)
    - Add new enum values to `ConversationStep`: `AWAIT_MORE_PAGES`, `PROCESSING_OCR`, `CONFIRM_SUM`
    - Extend `ConversationState` interface with: `photoFileIds?: string[]`, `ocrDescription?: string`, `ocrStoreName?: string`, `ocrGrossAmount?: number`
    - Keep `photoFileId` for backward compatibility
    - _Requirements: 8.1, 8.2_

  - [x] 1.3 Extend state store (`src/state/store.ts`)
    - Expand column range from `A:J` to `A:M` to store new fields (K: ocr_description, L: ocr_store_name, M: ocr_gross_amount)
    - Change column F from single `photoFileId` to JSON-serialized `photoFileIds` array
    - Implement `serializePhotoIds` / `deserializePhotoIds` helpers with backward compat (single string → array of one)
    - Update `getState` to deserialize new columns K-M
    - Update `setState` to serialize new fields into row
    - Update `clearState` to clear all 13 columns
    - _Requirements: 8.3, 8.4_

- [x] 2. Checkpoint — Ensure state layer compiles
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. OCR и Gemini сервисы
  - [x] 3.1 Create retry utility (`src/utils/retry.ts`)
    - Implement generic `withRetry<T>(fn, retries=1, delayMs=2000)` function
    - Handle errors: retry N times with fixed delay, then re-throw
    - _Requirements: 2.5, 3.5_

  - [x] 3.2 Create OCR service (`src/services/ocr.ts`)
    - Initialize `ImageAnnotatorClient` with service account credentials from config
    - Implement `extractText(imageBuffer: Buffer): Promise<OcrResult>` using `DOCUMENT_TEXT_DETECTION`
    - Implement `extractTextFromPages(imageBuffers: Buffer[]): Promise<string>` — iterate pages sequentially, concatenate with `--- Page N ---` separator, return empty string if all pages empty
    - Wrap individual page calls with `withRetry`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x] 3.3 Create Gemini service (`src/services/gemini.ts`)
    - Initialize `GoogleGenerativeAI` with `GEMINI_API_KEY` from config
    - Implement `parseReceipt(ocrText: string): Promise<GeminiReceiptData | null>`
    - Craft prompt handling multilingual receipts (Russian, Polish, Hebrew, English)
    - Parse JSON from Gemini response (handle markdown code block wrapping)
    - Validate that `description` and `store_name` are non-empty strings; `gross_amount` is number or null
    - Return `null` if validation fails
    - Wrap Gemini call with `withRetry`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_

  - [x] 3.4 Write property tests for OCR text concatenation
    - **Property 4: OCR text concatenation preserves page order**
    - **Validates: Requirements 2.3**

  - [x] 3.5 Write property tests for Gemini parsing
    - **Property 5: Gemini response parsing extracts valid fields**
    - **Validates: Requirements 3.3, 3.4**

  - [x] 3.6 Write property test for sum comparison logic
    - **Property 7: Sum comparison determines match within tolerance**
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4**

- [x] 4. Checkpoint — Ensure OCR/Gemini services compile
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Обработчики: мультистраничный сбор и OCR flow
  - [x] 5.1 Update photo handler (`src/handlers/photo.ts`)
    - Add feature flag check: if `OCR_ENABLED=false`, use old flow (single photo → AWAIT_SUM)
    - If OCR enabled: on first photo, init `photoFileIds = [file_id]`, transition to `AWAIT_MORE_PAGES`
    - On subsequent photos (state is `AWAIT_PHOTO` but `photoFileIds` already exists): append file_id, stay in `AWAIT_MORE_PAGES`
    - Enforce 10-page limit: reject 11th photo with message "Максимум 10 страниц..."
    - Show inline keyboard: "📄 Ещё 1 страница" (`ocr:more_pages`) and "✅ Готово" (`ocr:done`)
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 7.4_

  - [x] 5.2 Create callback handler (`src/handlers/callback.ts`)
    - Handle `ocr:more_pages` — transition to `AWAIT_PHOTO`, prompt for next page
    - Handle `ocr:done` — call `processOcr` pipeline
    - Handle `ocr:retry` — reset `photoFileIds`, transition to `AWAIT_PHOTO`
    - Handle `ocr:manual` — transition to `AWAIT_SUM` (manual mode, clear `ocrGrossAmount`)
    - Handle `ocr:confirm_yes` — proceed to save with user-entered sum
    - Handle `ocr:confirm_no` — return to `AWAIT_SUM` for re-entry
    - Implement `processOcr`: download photos → `extractTextFromPages` → `parseReceipt` → store results in state → display recognized data → prompt for sum
    - Handle OCR/Gemini failures: show error message with "🔄 Попробовать снова" / "✍️ Ввести вручную" buttons
    - Show progress messages: "⏳ Распознаю текст..." and "🤖 Анализирую чек..."
    - _Requirements: 1.3, 1.7, 2.4, 2.5, 2.6, 3.4, 3.5, 3.6, 4.1, 4.2, 4.3, 5.5, 5.6, 6.4_

  - [x] 5.3 Update text handler (`src/handlers/text.ts`)
    - In `AWAIT_MORE_PAGES` state: respond "Пожалуйста, отправьте фото чека или нажмите кнопку"
    - In `AWAIT_SUM` state: after validating sum, check if `ocrGrossAmount` exists and compare
    - If match (±0.01) or no OCR amount: proceed to save (OCR flow) or description (manual flow)
    - If mismatch: transition to `CONFIRM_SUM`, show warning with OCR amount vs user amount and confirmation buttons
    - In `CONFIRM_SUM` state: inform user to use buttons
    - In OCR flow (has `ocrDescription` + `ocrStoreName`): skip description/store steps, go directly to save
    - _Requirements: 1.8, 4.4, 4.5, 5.1, 5.2, 5.3, 5.4, 5.7, 6.2, 6.3_

  - [x] 5.4 Write property tests for photo collection
    - **Property 1: Photo collection appends to list**
    - **Property 2: Maximum page limit enforcement**
    - **Validates: Requirements 1.2, 1.4, 1.5, 1.6**

  - [x] 5.5 Write property test for non-photo message during collection
    - **Property 3: Non-photo messages do not mutate state during page collection**
    - **Validates: Requirements 1.8**

- [x] 6. Checkpoint — Ensure handlers compile and OCR flow works end-to-end
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Сохранение и интеграция
  - [x] 7.1 Update Drive service for multi-page upload
    - Add `uploadPhotos` function: uploads all collected photos to project Drive folder
    - Implement multi-page naming: `YYYY-MM-DD_HH-MM_<store>_<sum>_page<N>.<ext>` when N > 1
    - Single-page naming: existing pattern `YYYY-MM-DD_HH-MM_<store>_<sum>.<ext>`
    - Return array of webViewLinks for all uploaded photos
    - _Requirements: 9.2, 9.3, 9.4_

  - [x] 7.2 Implement OCR save flow (wire into callback handler)
    - Create `saveReceiptOcr` function: downloads all photos, uploads to Drive, writes single row to Sheets
    - Photo Link column: join all links with newline for multi-page receipts
    - Use user-entered sum (not OCR sum) in saved row
    - Apply same retry logic (1 retry, 2s delay) as existing flow for Drive/Sheets failures
    - Send confirmation: "✅ Записал: [Проект], [Сумма] PLN, [Магазин], [Описание]"
    - Clear state after successful save
    - _Requirements: 9.1, 9.5, 9.6, 9.7, 9.8_

  - [x] 7.3 Register callback handler in `src/bot.ts`
    - Import `handleOcrCallbacks` from `handlers/callback.ts`
    - Register `bot.callbackQuery(/^ocr:/, handleOcrCallbacks)` after project selection handler
    - _Requirements: 1.2, 1.3, 1.7_

  - [x] 7.4 Write property tests for file naming and photo links
    - **Property 11: Multi-page file naming includes sequential page numbers**
    - **Property 12: Multi-page photo links concatenation**
    - **Validates: Requirements 9.3, 9.4, 9.6**

  - [x] 7.5 Write property test for saved receipt sum
    - **Property 8: Saved receipt always uses user-entered sum**
    - **Validates: Requirements 5.7, 9.1**

- [x] 8. Feature flag и backward compatibility
  - [x] 8.1 Implement feature flag routing
    - Verify `OCR_ENABLED=false` completely disables OCR flow (photo → AWAIT_SUM, no multi-page, no Gemini calls)
    - Ensure old `photoFileId` (single) is still read if `photoFileIds` array is empty for backward compat
    - Test that existing state records without OCR columns (K-M) don't break deserialization
    - _Requirements: 7.4, 8.4_

  - [x] 8.2 Write property tests for feature flag and serialization
    - **Property 9: Feature flag disables OCR flow entirely**
    - **Property 10: photoFileIds JSON serialization round-trip**
    - **Validates: Requirements 7.4, 8.3**

- [x] 9. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- The project uses `bun` as runtime — use `bun test` for running tests
- TypeScript compilation: `tsc` (npm run build)
- All new code is in `src/` under the `телеграм-бот/telegram-bot/` directory

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3", "3.1"] },
    { "id": 2, "tasks": ["3.2", "3.3"] },
    { "id": 3, "tasks": ["3.4", "3.5", "3.6"] },
    { "id": 4, "tasks": ["5.1", "5.2"] },
    { "id": 5, "tasks": ["5.3", "5.4", "5.5"] },
    { "id": 6, "tasks": ["7.1", "7.3"] },
    { "id": 7, "tasks": ["7.2", "7.4", "7.5"] },
    { "id": 8, "tasks": ["8.1", "8.2"] }
  ]
}
```
