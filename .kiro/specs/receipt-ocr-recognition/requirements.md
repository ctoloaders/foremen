# Requirements Document

## Introduction

Добавление распознавания отсканированных чеков/фактур в существующий Telegram-бот для сбора чеков. Фича автоматизирует заполнение полей «описание» и «магазин» через Google Cloud Vision OCR + Google Gemini, оставляя ввод суммы за пользователем (с верификацией по OCR-данным). Поддерживается мультистраничное сканирование — работник может отправить несколько фото для одного чека.

**Контекст:** Существующий бот (grammY, TypeScript, Google Cloud Function) собирает чеки с ручным вводом всех полей. Новый flow заменяет ручной ввод описания и магазина на автоматическое распознавание, сохраняя остальную логику (авторизация, выбор проекта, сохранение в Drive + Sheets).

**Текущий flow:** /start → выбор проекта → фото → ввод суммы → ввод описания → ввод магазина → сохранение

**Новый flow:** /start → выбор проекта → фото → [ещё страница / готово] → OCR+Gemini → показ описания+магазина (без суммы) → ввод суммы → проверка совпадения с OCR-суммой → [если не совпадает: «Вы уверены?» → да/нет] → сохранение

## Glossary

- **OCR (Optical Character Recognition)**: Извлечение текста из изображения с помощью Google Cloud Vision API
- **Gemini**: Google Gemini LLM, используемый для структурирования raw-текста OCR в нужные поля (описание, магазин, сумма)
- **Multi-page document (Мультистраничный документ)**: Один чек/фактура, состоящий из нескольких фотографий (страниц)
- **OCR-сумма**: Сумма БРУТТО, распознанная Gemini из текста чека
- **Пользовательская сумма**: Сумма, введённая работником вручную
- **Receipt (Чек/Фактура)**: Фотография бумажного счёта из магазина с метаданными

## Requirements

### Requirement 1: Мультистраничный сбор фото

**User Story:** As a Worker, I want to send multiple photos for a single receipt (multi-page invoice), so that the bot can process the entire document.

#### Acceptance Criteria

1. WHEN a project is selected, THE System SHALL prompt: "Пришлите фото чека 📸"
2. WHEN the Worker sends a photo, THE System SHALL store the photo file_id in the conversation state and display two inline buttons: "📄 Ещё 1 страница" and "✅ Готово"
3. IF the Worker presses "📄 Ещё 1 страница", THEN THE System SHALL prompt: "Пришлите следующую страницу 📸" and wait for the next photo
4. WHEN the Worker sends the next photo, THE System SHALL append the photo file_id to the list of collected photos in conversation state and again display "📄 Ещё 1 страница" and "✅ Готово" buttons
5. THE System SHALL support collecting up to 10 pages per single receipt
6. IF the Worker attempts to send an 11th page, THEN THE System SHALL respond: "Максимум 10 страниц. Нажмите ✅ Готово для обработки."
7. IF the Worker presses "✅ Готово", THEN THE System SHALL proceed to OCR+Gemini processing of all collected photos
8. IF the Worker sends a non-photo message when a photo is expected (during multi-page collection), THEN THE System SHALL respond: "Пожалуйста, отправьте фото чека или нажмите кнопку" and wait
9. THE System SHALL support /cancel at any point during multi-page collection to abort and return to project selection

### Requirement 2: OCR-распознавание текста (Google Cloud Vision)

**User Story:** As a Worker, I want the bot to automatically read text from my receipt photo, so that I don't have to type purchase details manually.

#### Acceptance Criteria

1. WHEN the Worker presses "✅ Готово" (or after first photo if only one page), THE System SHALL send each collected photo to Google Cloud Vision API for text extraction
2. THE System SHALL use the `TEXT_DETECTION` (or `DOCUMENT_TEXT_DETECTION`) feature of Cloud Vision API to extract raw text from each photo
3. THE System SHALL concatenate OCR results from all pages in order (page 1 first, page N last) with a page separator
4. IF Cloud Vision API returns empty text for all pages, THEN THE System SHALL respond: "❌ Не удалось распознать текст на фото. Попробуйте сделать фото чётче или введите данные вручную." and offer "🔄 Попробовать снова" and "✍️ Ввести вручную" inline buttons
5. IF Cloud Vision API call fails (network error, quota exceeded), THEN THE System SHALL retry once after 2 seconds. If retry also fails, respond: "❌ Ошибка сервиса распознавания." and offer "🔄 Попробовать снова" and "✍️ Ввести вручную" inline buttons, and log the error
6. THE System SHALL show a "⏳ Распознаю текст..." message to the user while OCR is in progress

### Requirement 3: Структурирование данных через Gemini

**User Story:** As a Worker, I want the bot to automatically extract item descriptions, store name, and total amount from the receipt text, so that I only need to confirm the sum.

#### Acceptance Criteria

1. WHEN OCR text is extracted successfully, THE System SHALL send the concatenated text to Google Gemini API with a structured prompt requesting three fields: description (summary of purchased items), store_name (seller/shop name), and gross_amount (total BRUTTO amount)
2. THE Gemini prompt SHALL instruct the model to return a JSON object with fields: `description` (string, summary of items purchased), `store_name` (string, shop/company name), `gross_amount` (number or null if not found)
3. IF Gemini returns a valid JSON response with at least `description` and `store_name`, THE System SHALL store all extracted fields in conversation state
4. IF Gemini fails to parse the receipt or returns incomplete data (missing description OR store_name), THEN THE System SHALL respond: "❌ Не удалось структурировать данные чека. Попробуйте другое фото или введите данные вручную." and offer "🔄 Попробовать снова" and "✍️ Ввести вручную" inline buttons
5. IF Gemini API call fails, THEN THE System SHALL retry once after 2 seconds. If retry also fails, offer manual input fallback
6. THE System SHALL show "🤖 Анализирую чек..." message while Gemini is processing
7. THE Gemini prompt SHALL be written to handle receipts in multiple languages (Hebrew, Russian, English, Polish) as workers buy from various shops

### Requirement 4: Показ распознанных данных и ввод суммы

**User Story:** As a Worker, I want to see what the bot recognized from my receipt, so that I can verify the data is correct before confirming.

#### Acceptance Criteria

1. WHEN Gemini successfully extracts fields, THE System SHALL display the recognized data to the user in the format:
   ```
   📋 Распознано:
   🛒 Куплено: <description>
   🏪 Магазин: <store_name>
   
   Введите сумму покупки (число):
   ```
2. THE System SHALL NOT display the OCR-recognized amount to the user (it is used only for internal verification)
3. THE System SHALL transition to the AWAIT_SUM step after displaying recognized data
4. WHEN the Worker enters a sum, THE System SHALL validate that it is a positive number (integer or decimal with up to 2 decimal places)
5. IF the sum is not a valid positive number, THEN THE System SHALL respond: "Введите сумму числом (например, 340 или 250.50)" and wait for valid input

### Requirement 5: Верификация суммы (сравнение с OCR)

**User Story:** As a Worker, I want the bot to cross-check my entered amount against the OCR-recognized amount, so that typos and errors are caught before saving.

#### Acceptance Criteria

1. WHEN the Worker enters a valid sum, THE System SHALL compare it with the OCR-recognized `gross_amount` (from Gemini)
2. IF the OCR `gross_amount` is null (not recognized), THEN THE System SHALL proceed to saving without verification (skip comparison)
3. IF the entered sum matches the OCR `gross_amount` (exact match or within rounding tolerance of ±0.01), THEN THE System SHALL proceed to saving
4. IF the entered sum does NOT match the OCR `gross_amount`, THEN THE System SHALL respond:
   ```
   ⚠️ Распознанная сумма: <ocr_amount>
   Вы ввели: <user_amount>
   
   Вы уверены?
   ```
   with two inline buttons: "✅ Да, сохранить" and "✏️ Ввести заново"
5. IF the Worker presses "✅ Да, сохранить", THEN THE System SHALL proceed to saving using the user's entered sum (user takes responsibility)
6. IF the Worker presses "✏️ Ввести заново", THEN THE System SHALL return to the sum input step (AWAIT_SUM) and prompt: "Введите сумму покупки (число):"
7. THE System SHALL use the user's entered sum for saving in all cases (OCR sum is only advisory)

### Requirement 6: Ручной ввод как fallback

**User Story:** As a Worker, I want to be able to enter receipt details manually if OCR fails, so that I'm not blocked by recognition errors.

#### Acceptance Criteria

1. WHEN OCR or Gemini fails and the user sees "✍️ Ввести вручную" button, pressing it SHALL switch to the existing manual flow: prompt sum → prompt description → prompt store name → save
2. WHEN manual input flow is activated, THE System SHALL follow the same validation rules as the original flow (sum: positive number; description: max 500 chars; store name: max 200 chars)
3. THE System SHALL NOT attempt OCR verification of the sum in manual input mode (no comparison step)
4. THE "🔄 Попробовать снова" button SHALL reset the photo collection step — the user needs to send photos again

### Requirement 7: Конфигурация и секреты для OCR/Gemini

**User Story:** As a developer, I want OCR and Gemini settings to be configurable via environment variables, so that I can switch between test and production without code changes.

#### Acceptance Criteria

1. THE System SHALL add the following environment variables to configuration:
   - `GOOGLE_CLOUD_PROJECT_ID` — GCP project ID for Vision API
   - `GEMINI_API_KEY` — API key for Google Gemini (or use the same service account if using Vertex AI)
   - `GEMINI_MODEL` — Gemini model name (default: "gemini-2.0-flash")
   - `OCR_ENABLED` — feature flag to enable/disable OCR (default: "true"); when "false", the bot uses the old manual flow
2. THE `config.ts` module SHALL export these values with proper typing and fail fast if required variables are missing (when OCR_ENABLED is "true")
3. THE `.env.example` file SHALL be updated with the new variables and comments explaining each one
4. THE System SHALL support graceful degradation: if OCR_ENABLED is "false", the bot SHALL use the original manual flow (photo → sum → description → store → save) without any OCR/Gemini calls

### Requirement 8: Обновление состояния диалога

**User Story:** As a developer, I want the conversation state to support multi-page photos and OCR results, so that the new flow works correctly within the existing state machine.

#### Acceptance Criteria

1. THE ConversationState interface SHALL be extended with:
   - `photoFileIds: string[]` — array of photo file_ids (for multi-page support, replaces single `photoFileId`)
   - `ocrDescription?: string` — description extracted by OCR+Gemini
   - `ocrStoreName?: string` — store name extracted by OCR+Gemini
   - `ocrGrossAmount?: number` — gross amount extracted by OCR+Gemini (used for verification only)
   - `firstEnteredSum?: number` — first sum entered by user (for detecting re-entry and adding cell comments)
2. THE ConversationStep enum SHALL be extended with new steps:
   - `AWAIT_MORE_PAGES` — waiting for user to send another page or press "Готово"
   - `PROCESSING_OCR` — OCR+Gemini in progress
   - `CONFIRM_SUM` — user entered sum that doesn't match OCR, awaiting confirmation
3. THE bot_state sheet in Google Sheets SHALL be updated to store the new fields (photoFileIds as JSON string, OCR results)
4. THE System SHALL maintain backward compatibility — existing states without OCR fields SHALL continue to work (old records in bot_state do not break the bot)

### Requirement 9: Сохранение данных (обновление)

**User Story:** As a Worker, I want the bot to save the receipt with OCR-extracted data the same way as before, so that the data appears in the project's spreadsheet and Drive folder.

#### Acceptance Criteria

1. WHEN OCR flow completes (sum confirmed), THE System SHALL save the receipt using: user-entered sum, OCR-extracted description, OCR-extracted store name
2. THE System SHALL upload ALL collected photos (all pages) to Google Drive in the project folder
3. FOR multi-page receipts, THE System SHALL name files using the pattern: `YYYY-MM-DD_HH-MM_<store_name>_<sum>_page<N>.<ext>` (where N is page number starting from 1)
4. FOR single-page receipts, THE System SHALL use the existing naming pattern: `YYYY-MM-DD_HH-MM_<store_name>_<sum>.<ext>`
5. THE System SHALL append a single row to the project estimate sheet (regardless of number of pages) with: Date, Sum, Description, Store Name, Photo Link(s)
6. FOR multi-page receipts, THE Photo Link column SHALL contain all photo links separated by newline or comma
7. AFTER successful save, THE System SHALL send confirmation: "✅ Записал: [Проект], [Сумма] PLN, [Магазин], [Описание]" and reset state
8. THE System SHALL use the same retry logic (1 retry after 2s) for Drive upload and Sheets write failures as the existing flow


### Requirement 10: Комментарий к ячейке суммы при отклонениях от happy path

**User Story:** As a project manager, I want to see a comment on the sum cell when the receipt data wasn't entered perfectly, so that I can identify receipts that need extra attention.

#### Acceptance Criteria

1. WHEN saving a receipt row to the project estimate sheet, THE System SHALL add a Google Sheets note (cell comment) to the Sum cell in the following non-happy-path cases:
   - **Case A (Manual input):** OCR failed or user chose "✍️ Ввести вручную" → comment: "⚠️ Данные введены вручную (OCR недоступен)"
   - **Case B (Sum mismatch — user insisted):** User entered sum ≠ OCR sum, and user pressed "✅ Да, сохранить" → comment: "⚠️ Введено: <user_sum>, OCR: <ocr_sum> — сотрудник подтвердил свою сумму"
   - **Case C (Sum mismatch — re-entered):** User entered sum ≠ OCR sum on first attempt, then pressed "✏️ Ввести заново" and entered a new value (regardless of whether the new value matches OCR) → comment: "⚠️ Первый ввод: <first_sum>, OCR: <ocr_sum> — сумма исправлена"
2. IF the user's first entered sum matches the OCR `gross_amount` (within ±0.01 tolerance), THE System SHALL NOT add any comment (happy path)
3. THE comment SHALL be added using the Google Sheets API `addNote` or equivalent method on the Sum cell of the appended row
4. THE System SHALL store the first entered sum in conversation state to detect Case C (re-entry scenario)
5. IF OCR `gross_amount` is null (not recognized by Gemini), AND the user is in OCR flow (not manual), THE System SHALL NOT add a comment (cannot compare, not an error)
