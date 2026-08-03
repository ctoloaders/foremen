# Implementation Tasks

## Task 1: Project scaffolding and configuration

**Requirements:** Requirement 8 (Управление конфигурацией и секретами)
**Design Reference:** File structure, config.ts, .env

### Subtasks:
1. Create `/telegram-bot/` directory with `package.json` (dependencies: grammy, googleapis, dotenv; devDeps: typescript, @types/node, tsx)
2. Create `tsconfig.json` (target ES2022, module NodeNext, strict mode)
3. Create `.env.example` with all keys documented with comments
4. Create `.env` placeholder (gitignored)
5. Create `.gitignore` (node_modules, .env, dist/, *.js in src/)
6. Implement `src/config.ts` — reads env vars, validates required fields, exports typed Config object; throws on missing required vars
7. Create `deploy.sh` script for `gcloud functions deploy`

---

## Task 2: Structured logger

**Requirements:** Requirement 9 (Обработка ошибок и логирование)
**Design Reference:** utils/logger.ts

### Subtasks:
1. Implement `src/utils/logger.ts` — JSON structured logger with levels: INFO, WARN, ERROR
2. Logger outputs: timestamp, level, message, context (object), but never logs tokens or file contents
3. Export `logger.info()`, `logger.warn()`, `logger.error()` functions

---

## Task 3: Utility modules

**Requirements:** Requirement 3 (Сбор данных чека), Requirement 4 (Сохранение данных)
**Design Reference:** utils/transliterate.ts, utils/validators.ts

### Subtasks:
1. Implement `src/utils/transliterate.ts` — converts Cyrillic to Latin, replaces spaces/special chars with hyphens, lowercases
2. Implement `src/utils/validators.ts`:
   - `validateSum(input: string): number | null` — positive number, up to 2 decimal places
   - `validateText(input: string, maxLength: number): string | null` — trim, length check
   - `isValidUrl(input: string): boolean` — starts with https://

---

## Task 4: Google Sheets service

**Requirements:** Requirement 1 (Авторизация), Requirement 2 (Выбор проекта), Requirement 4 (Сохранение), Requirement 5 (Реестр проектов), Requirement 5a (Привязки), Requirement 6 (Реестр работников)
**Design Reference:** Component 5 (SheetsService)

### Subtasks:
1. Implement `src/services/sheets.ts` with Google Service Account auth (from JSON key file)
2. Implement `getWorker(telegramId: number): Promise<Worker | null>` — reads "workers" sheet, finds row by telegram_id
3. Implement `getAllWorkers(): Promise<Worker[]>` — reads all rows from "workers" sheet
4. Implement `getProjectsForWorker(worker: Worker): Promise<Project[]>`:
   - If admin → return all active projects
   - Otherwise → read "project_access" sheet, filter by worker_name, cross-reference with "projects" sheet for active status
5. Implement `getAllActiveProjects(): Promise<Project[]>` — reads "projects" sheet, filters by status = "active"
6. Implement `appendReceiptRow(spreadsheetId: string, row: ReceiptRow): Promise<void>` — appends row to target spreadsheet
7. Implement `upsertProjectAccess(projectName, workerName, roleInProject): Promise<"created" | "updated">` — upsert in "project_access" sheet
8. Implement `getProjectAccess(projectName, workerName): Promise<ProjectAccess | null>` — check if binding exists
9. Add helper `extractSpreadsheetId(url: string): string` — extracts ID from Google Sheets URL

---

## Task 5: Conversation state store

**Requirements:** Requirement 3 (Сбор данных чека)
**Design Reference:** Component 3 (State Machine), Component 4 (State Store)

### Subtasks:
1. Implement `src/state/machine.ts` — ConversationStep enum, ConversationState interface, transition validation function
2. Implement `src/state/store.ts`:
   - `getState(telegramId: number): Promise<ConversationState | null>` — reads "bot_state" sheet
   - `setState(state: ConversationState): Promise<void>` — upserts row (update if exists, append if new)
   - `clearState(telegramId: number): Promise<void>` — deletes row
3. Add stale state detection: if `updated_at` > 24 hours, return null (auto-cleanup)

---

## Task 6: Google Drive service

**Requirements:** Requirement 4 (Сохранение данных)
**Design Reference:** Component 6 (DriveService)

### Subtasks:
1. Implement `src/services/drive.ts` with Google Service Account auth
2. Implement `uploadPhoto(folderId, fileName, fileBuffer, mimeType): Promise<{fileId, webViewLink}>`:
   - Creates file in specified folder
   - Sets sharing to "anyone with link can view"
   - Returns web view link
3. Add helper `extractFolderId(driveUrl: string): string` — extracts folder ID from Google Drive URL
4. Implement file naming: `YYYY-MM-DD_HH-MM_<transliterated_store>_<sum>.<ext>`

---

## Task 7: Telegram file download helper

**Requirements:** Requirement 4 (Сохранение данных)
**Design Reference:** services/telegram.ts

### Subtasks:
1. Implement `src/services/telegram.ts`:
   - `downloadFile(bot, fileId): Promise<{buffer: Buffer, mimeType: string}>` — uses Telegram Bot API getFile + download
2. Handle photo sizes: always pick the largest available photo size (last element in photo array)

---

## Task 8: Bot handlers — /start and auth

**Requirements:** Requirement 1 (Авторизация), Requirement 2 (Выбор проекта)
**Design Reference:** Component 2 (Bot), handlers/start.ts

### Subtasks:
1. Implement `src/handlers/start.ts`:
   - Check Worker Registry (by Telegram ID) → if not found, reply "Доступ запрещён. Обратитесь к администратору. Ваш Telegram ID: <id>" and return
   - Get worker name and role from registry
   - If admin: get ALL active projects
   - Otherwise: get projects from project_access table matching worker name, cross-referenced with active status
   - If zero projects → "Нет активных проектов"
   - Display inline keyboard with project names as buttons
   - On callback_query (project selected) → save to state, prompt for photo
2. Implement callback query handler for project selection buttons
3. Implement `src/handlers/myid.ts`:
   - Respond with "Ваш Telegram ID: <user.id>" — no auth check, works for any user

---

## Task 9: Bot handlers — receipt collection flow

**Requirements:** Requirement 3 (Сбор данных чека)
**Design Reference:** handlers/photo.ts, handlers/text.ts, handlers/cancel.ts

### Subtasks:
1. Implement `src/handlers/photo.ts`:
   - Validate state is AWAIT_PHOTO
   - Store file_id of largest photo
   - Update state to AWAIT_SUM
   - Reply "Какая сумма? (число)"
2. Implement `src/handlers/text.ts` — routes based on current step:
   - AWAIT_SUM: validate number → store → AWAIT_DESCRIPTION → "Что куплено?"
   - AWAIT_DESCRIPTION: validate text ≤500 chars → store → AWAIT_STORE → "Название магазина?"
   - AWAIT_STORE: validate text ≤200 chars → store → trigger save flow
3. Implement `src/handlers/cancel.ts`:
   - Clear state
   - Reply "Отменено. Отправьте /start для нового чека"

---

## Task 10: Bot handlers — save flow (Drive + Sheets)

**Requirements:** Requirement 4 (Сохранение данных)
**Design Reference:** Component 5, Component 6, error handling

### Subtasks:
1. Implement save orchestration in text handler (after store name received):
   - Download photo from Telegram
   - Upload to Drive (with retry 1x on failure)
   - Append row to project's Sheets (with retry 1x on failure)
   - On success: confirmation message "✅ Записал: ..." + clear state
   - On Drive failure: "❌ Ошибка загрузки фото" + keep state
   - On Sheets failure: "❌ Ошибка записи в таблицу" + keep state for /retry
2. Implement `/retry` command that re-attempts the save with existing state data

---

## Task 11: Bot instance and Cloud Function entry point

**Requirements:** All bot requirements
**Design Reference:** Component 1 (index.ts), Component 2 (bot.ts)

### Subtasks:
1. Implement `src/bot.ts`:
   - Create grammY Bot instance with token from config
   - Register middleware: logger, state loader, auth check
   - Register handlers: /start, /cancel, /retry, /myid, /assign, photo, text, callback_query
   - Export bot instance
2. Implement `src/index.ts`:
   - Export `receiptBot` Cloud Function (HTTP trigger)
   - Parse webhook update from request body
   - Call `bot.handleUpdate(update)`
   - Return 200 OK
   - Catch unhandled errors → log + return 200 (to avoid Telegram retries)

---

## Task 12: Google Apps Script — webhook receiver

**Requirements:** Requirement 5 (Реестр проектов), Requirement 5a (Привязки), Requirement 6 (Реестр работников), Requirement 7 (Автоматизация Битрикс24)
**Design Reference:** Component 7 (Apps Script)

### Subtasks:
1. Implement `apps-script/Code.gs`:
   - `doPost(e)` function:
     - Parse JSON from `e.postData.contents`
     - Validate secret field matches configured secret
     - Route by `action` field:
   - **action = "upsert_project":**
     - Validate required fields: project_name, google_drive_url, google_sheets_url, workers (array)
     - Validate URLs start with "https://"
     - Upsert project row in "projects" sheet by project_name
     - Write: [project_name, google_drive_url, google_sheets_url, "active", new Date()]
     - For each worker in workers array (skip if worker_name is empty):
       - Upsert row in "project_access" sheet by (project_name + worker_name)
       - Write: [project_name, worker_name, role_in_project]
   - **action = "upsert_worker":**
     - Validate required fields: bitrix_user_id, worker_name, role, telegram_id
     - Validate role is one of: "foreman", "pm", "estimator", "sales", "admin", "other"
     - Validate telegram_id is positive integer (skip if 0 or empty)
     - Find existing row by bitrix_user_id → update if found, append if not
     - If existing role is "admin" and incoming role is different — keep "admin" (sticky)
     - Write: [bitrix_user_id, telegram_id, worker_name, role]
   - **action = "remove_worker":**
     - Validate required field: bitrix_user_id
     - Find row by bitrix_user_id → delete if found
     - Return appropriate JSON response
2. Add `CONFIG` object at top of script with `WEBHOOK_SECRET`, `PROJECTS_SHEET_NAME`, `WORKERS_SHEET_NAME`, `ACCESS_SHEET_NAME` constants
3. Write `apps-script/README.md` — step-by-step deploy instructions

---

## Task 13: Битрикс24 — настройка роботов (документация)

**Requirements:** Requirement 7 (Автоматизация Битрикс24)
**Design Reference:** Component 8 (Bitrix24 Robot)

### Subtasks:
1. Write `bitrix24/README.md` with step-by-step instructions:
   - **Робот 1 (проекты):**
     - How to create custom fields on Project: UF_GOOGLE_DRIVE_URL, UF_GOOGLE_SHEETS_URL, UF_SENT_TO_REGISTRY
     - How to use employee fields: "Прораб", "Менеджер проекта", "Сметчик", "Продавец"
     - How to create Robot/Business Process:
       - Trigger: project/group change
       - Condition: URL fields filled + at least one employee assigned + SENT_TO_REGISTRY = No
       - Action 1: Outgoing webhook POST with action "upsert_project" including workers array
       - Action 2: Set UF_SENT_TO_REGISTRY = Yes
   - **Робот 2 (работники):**
     - How to create custom fields on Employee: UF_TELEGRAM_ID (integer), UF_BOT_ROLE (list: foreman, pm, estimator, sales, admin, other)
     - How to create Robot/Business Process:
       - Trigger: employee card change
       - Condition: UF_TELEGRAM_ID not empty AND UF_BOT_ROLE not empty
       - Action: Outgoing webhook POST with action "upsert_worker"
   - **Робот 3 (увольнение):**
     - Trigger: employee deactivation
     - Action: webhook with action "remove_worker"
   - Troubleshooting: how to verify webhook is firing, how to check Apps Script logs

---

## Task 14: Google Sheets — подготовка таблиц (документация)

**Requirements:** Requirement 5, Requirement 5a, Requirement 6
**Design Reference:** Data Models

### Subtasks:
1. Write `docs/sheets-setup.md`:
   - How to create the registry spreadsheet with 4 sheets: projects, project_access, workers, bot_state
   - Column headers for each sheet
   - How to manually add the first admin user(s) to the "workers" sheet (initial bootstrap)
   - How to share the spreadsheet with the Service Account email
   - How to create a per-project estimate spreadsheet (template with headers: Дата, Сумма, Что куплено, Магазин, Ссылка на фото)
   - How to create a per-project Drive folder and share with Service Account
   - How to get spreadsheet ID from URL

---

## Task 15: Deploy and webhook setup

**Requirements:** All
**Design Reference:** deploy.sh, Architecture

### Subtasks:
1. Finalize `deploy.sh`:
   - `gcloud functions deploy receipt-bot --gen2 --runtime nodejs20 --trigger-http --allow-unauthenticated --entry-point receiptBot --region me-west1 --set-secrets ...`
   - Print deployed URL
2. Add `scripts/set-webhook.ts` — calls Telegram `setWebhook` API with the Cloud Function URL + secret token
3. Write `README.md` in `/telegram-bot/` root:
   - Prerequisites (Node.js 20, gcloud CLI, Google Cloud project)
   - Quick start (clone, npm install, fill .env, deploy)
   - Local development (npm run dev with polling mode for testing)
   - Architecture overview (link to spec)
   - Troubleshooting

---

## Task 16: Local development mode (polling)

**Requirements:** Requirement 8 (dev/prod separation)
**Design Reference:** Architecture

### Subtasks:
1. Add `src/dev.ts` — runs bot in long-polling mode (not webhook) for local development
2. Add `npm run dev` script in package.json that runs `tsx src/dev.ts`
3. Add `npm run deploy` script that runs `deploy.sh`
4. Ensure config.ts supports both modes: webhook (production) and polling (development)

---

## Task 17: End-to-end testing with test environment

**Requirements:** Requirement 8, Requirement 9
**Design Reference:** Testing Strategy

### Subtasks:
1. Create test spreadsheet with sample data (3 workers with different roles, 3 projects)
2. Create test Drive folder
3. Fill `.env` with test bot token and test spreadsheet IDs
4. Manual E2E test checklist:
   - [ ] /myid from any user → shows Telegram ID
   - [ ] /start from registered foreman → see only foreman's project buttons
   - [ ] /start from registered pm → see only pm's project buttons
   - [ ] /start from registered admin → see ALL active project buttons
   - [ ] /start from unknown user → access denied + shows Telegram ID
   - [ ] Select project → photo → sum → description → store → verify row in Sheets + file in Drive
   - [ ] Invalid sum → error message → retry with valid sum
   - [ ] /cancel mid-flow → state cleared
   - [ ] Send text when photo expected → error prompt
   - [ ] Bitrix24 project webhook → row appears/updates in projects registry
   - [ ] Bitrix24 worker webhook (upsert_worker) → row appears/updates in workers registry
   - [ ] Bitrix24 worker webhook (remove_worker) → row deleted from workers registry
   - [ ] Bot picks up new project/worker after registry updated


---

## Task 18: Admin command — /assign (add worker to project)

**Requirements:** Requirement 10 (Админ-команда)
**Design Reference:** SheetsService.upsertProjectAccess

### Subtasks:
1. Implement `src/handlers/assign.ts`:
   - Check user role → if not "admin", reply "Эта команда доступна только администраторам." and return
   - Step 1: Display all active projects as inline keyboard buttons → admin selects project
   - Step 2: Display all workers from Worker Registry as inline keyboard buttons (format: "Имя Фамилия — роль") + button "Ввести вручную"
   - Step 2a (manual): Ask for worker name (text input), then proceed to step 3
   - Step 3: Ask for role_in_project (inline keyboard: "Прораб", "ПМ", "Сметчик", "Продавец", "Другое")
   - Step 4: Check if binding already exists → if yes, ask "Обновить роль?" (Yes/No)
   - Step 5: Write to project_access sheet via `upsertProjectAccess()`
   - Step 6: Confirm "✅ [Имя] добавлен на проект [Название] как [Роль]"
2. Add assign-specific conversation states to state machine:
   - ASSIGN_SELECT_PROJECT
   - ASSIGN_SELECT_WORKER
   - ASSIGN_ENTER_NAME (manual)
   - ASSIGN_SELECT_ROLE
   - ASSIGN_CONFIRM_UPDATE
3. Support /cancel at any step within the assign flow
4. Register /assign handler in bot.ts
