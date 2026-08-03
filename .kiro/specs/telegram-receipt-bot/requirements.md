# Requirements Document

## Introduction

Telegram-бот для сбора бумажных счетов (фактур) на ремонтных объектах. Ремонтная компания ведёт несколько параллельных объектов с несколькими прорабами и проект-менеджерами. Любой авторизованный работник (прораб, ПМ, сметчик, продавец, бухгалтер, админ) может фотографировать чеки и отправлять их боту для проектов, к которым он привязан. Бот сохраняет фото на Google Drive и добавляет запись в Google Sheets-смету проекта.

Связь между Битрикс24 и ботом — через Google Sheets-реестры (проектов, работников, привязок), которые заполняются автоматизациями Битрикс24 через Google Apps Script webhook. Админ также может добавлять работников на проект через команду бота.

Система состоит из трёх компонентов:
1. **Telegram-бот** (Google Cloud Function, webhook mode) — диалог с работником, загрузка фото, запись в таблицу, админ-команды
2. **Google Apps Script** — webhook-приёмник от Битрикс24, пишет в реестры проектов, работников и привязок
3. **Автоматизация Битрикс24** — роботы: (а) отправка данных проекта при заполнении полей, (б) синхронизация карточки работника при изменении Telegram ID

## Glossary

- **Worker (Работник)**: Любой сотрудник компании, зарегистрированный в реестре работников
- **Role (Роль)**: Должность/роль работника в компании: "foreman" (прораб), "pm" (проект-менеджер), "estimator" (сметчик), "sales" (продавец), "admin" (администратор), "other" (прочие — бухгалтерия и т.д.)
- **Admin**: Работник с ролью "admin", имеет доступ ко всем проектам и может добавлять работников на проекты через бота. Роль назначается вручную при первичной настройке реестра.
- **Project (Проект/Объект)**: Ремонтный объект (квартира, дом, офис)
- **Project Access (Привязка к проекту)**: Запись в таблице привязок, определяющая что конкретный работник имеет доступ к конкретному проекту с конкретной ролью
- **Receipt (Чек/Фактура)**: Фотография бумажного счёта из магазина с метаданными (сумма, описание, магазин)
- **Project Registry (Реестр проектов)**: Google Sheets — список проектов с ссылками на Drive/Sheets, заполняется из Битрикс24
- **Worker Registry (Реестр работников)**: Google Sheets — список работников с Telegram ID, ролью и Bitrix ID, заполняется из Битрикс24 (роль "admin" задаётся вручную один раз)
- **Project Access Registry (Реестр привязок)**: Google Sheets — таблица "работник ↔ проект", заполняется из Битрикс24 + админом через бота
- **Project Estimate (Смета проекта)**: Google Sheets-таблица конкретного проекта, куда бот добавляет строки расходов
- **Project Folder (Папка проекта)**: Папка на Google Drive, куда бот загружает фотографии чеков
- **Bot State (Состояние диалога)**: Текущий шаг диалога работника с ботом
- **Apps Script Webhook**: Google Apps Script, развёрнутый как Web App, принимающий POST-запросы от Битрикс24 и от бота
- **Bitrix24 Robot (Робот Битрикс24)**: Автоматизация в Битрикс24, отправляющая webhook при событиях

## Requirements

### Requirement 1: Авторизация работника

**User Story:** As a Worker, I want the bot to recognize me by my Telegram ID, so that I can submit receipts for projects I'm assigned to.

#### Acceptance Criteria

1. WHEN a user sends /start to the bot, THE System SHALL look up the user's Telegram ID in the Worker Registry (Google Sheets)
2. IF the Telegram ID is found in the Worker Registry, THEN THE System SHALL greet the user by name and role, and proceed to project selection
3. IF the Telegram ID is NOT found in the Worker Registry, THEN THE System SHALL respond with a message "Доступ запрещён. Обратитесь к администратору. Ваш Telegram ID: <id>" and stop the conversation
4. THE Worker Registry SHALL contain columns: Bitrix24 User ID (text, unique key), Telegram ID (integer, unique), Worker Name (text), Role (text: "foreman", "pm", "estimator", "sales", "admin", "other")
5. THE Worker Registry SHALL be populated and updated by the Apps Script webhook triggered from Bitrix24. The "admin" role MAY be set manually directly in the spreadsheet during initial setup (for the first 1-2 admin users).
6. WHEN any user sends /myid to the bot, THE System SHALL respond with: "Ваш Telegram ID: <id>" regardless of whether the user is registered (this command works for everyone)
7. Workers with role "admin" SHALL have access to all active projects regardless of project_access assignments
8. All other workers SHALL have access only to projects listed in the Project Access Registry for their worker name

### Requirement 2: Выбор проекта

**User Story:** As a Worker, I want to select which project I'm buying materials for, so that receipts are filed to the correct project.

#### Acceptance Criteria

1. WHEN a recognized Worker sends /start, THE System SHALL resolve the Worker's name and role from the Worker Registry, then:
   - If role is "admin": show ALL active projects from Project Registry
   - Otherwise: query Project Access Registry for projects where worker_name matches, then cross-reference with Project Registry to get only active projects
2. THE System SHALL display the list of matching projects as inline keyboard buttons (one button per project, showing project name)
3. IF the Worker has exactly one project, THE System SHALL still display it as a button for explicit confirmation
4. IF the Worker has zero projects assigned, THE System SHALL respond with "У вас нет активных проектов. Обратитесь к менеджеру."
5. WHEN the Worker taps a project button, THE System SHALL store the selected project in the conversation state and prompt for receipt photo
6. THE System SHALL allow the Worker to change the selected project at any time by sending /start again

### Requirement 3: Сбор данных чека

**User Story:** As a Worker, I want to send a receipt photo and provide purchase details step-by-step, so that the bot records complete information about each purchase.

#### Acceptance Criteria

1. WHEN a project is selected, THE System SHALL prompt: "Пришлите фото чека 📸"
2. WHEN the Worker sends a photo, THE System SHALL store the photo file_id in conversation state and prompt: "Какая сумма? (число)"
3. IF the Worker sends a non-photo message when a photo is expected, THEN THE System SHALL respond: "Пожалуйста, отправьте фото чека" and wait for a photo
4. WHEN the Worker sends a sum, THE System SHALL validate that it is a positive number (integer or decimal with up to 2 decimal places), store it in conversation state, and prompt: "Что куплено?"
5. IF the sum is not a valid positive number, THEN THE System SHALL respond: "Введите сумму числом (например, 340 или 250.50)" and wait for a valid input
6. WHEN the Worker sends a purchase description, THE System SHALL store it (max 500 characters) in conversation state and prompt: "Название магазина?"
7. WHEN the Worker sends a store name, THE System SHALL store it (max 200 characters) in conversation state and proceed to saving
8. THE System SHALL support a /cancel command at any step to abort the current receipt entry and return to project selection

### Requirement 4: Сохранение данных

**User Story:** As a Worker, I want the bot to save the receipt photo and details automatically, so that the data appears in the project's spreadsheet and Drive folder without manual work.

#### Acceptance Criteria

1. WHEN all receipt data is collected (photo, sum, description, store name), THE System SHALL upload the photo to the Google Drive folder linked to the selected project (from Project Registry column "Ссылка на Google Drive")
2. THE System SHALL name the uploaded file using the pattern: `YYYY-MM-DD_HH-MM_<store_name>_<sum>.<ext>` (date-time of submission, store name transliterated, sum)
3. WHEN the photo is uploaded, THE System SHALL obtain the shareable link to the uploaded file
4. THE System SHALL append a new row to the Google Sheets estimate table linked to the selected project (from Project Registry column "Ссылка на Google Sheets") with columns: Date (YYYY-MM-DD), Sum (number), Description (text), Store Name (text), Photo Link (URL)
5. WHEN both operations succeed, THE System SHALL send a confirmation: "✅ Записал: [Проект], [Сумма]₪, [Магазин], [Описание]"
6. IF the upload to Google Drive fails, THEN THE System SHALL retry once after 2 seconds, and if it fails again, respond: "❌ Ошибка загрузки фото. Попробуйте ещё раз." and keep the conversation state intact for retry
7. IF the write to Google Sheets fails, THEN THE System SHALL retry once after 2 seconds, and if it fails again, respond: "❌ Ошибка записи в таблицу. Фото загружено, но строка не добавлена. Попробуйте /retry." and store the pending data for retry
8. AFTER successful save, THE System SHALL reset the conversation state and prompt: "Можете отправить следующий чек или выбрать другой проект (/start)"

### Requirement 5: Реестр проектов (Google Sheets)

**User Story:** As an administrator, I want the project registry to be automatically populated from Bitrix24, so that new projects appear in the bot without manual configuration.

#### Acceptance Criteria

1. THE Project Registry SHALL be a Google Sheets spreadsheet with columns: Project Name (text, unique key), Google Drive URL (URL), Google Sheets URL (URL), Status (text: "active" or "archived", default "active"), Date Added (date)
2. THE Project Registry SHALL be populated and updated by the Google Apps Script webhook triggered from Bitrix24 (no manual edits to data rows)
3. WHEN the Apps Script webhook receives a valid POST request with action "upsert_project" and all required fields (project_name, google_drive_url, google_sheets_url, workers — list of {worker_name, role_in_project}), THE System SHALL:
   - Upsert the project row in Project Registry (by project_name)
   - For each worker in the workers list: upsert a row in Project Access Registry with (project_name, worker_name, role_in_project)
4. IF the webhook receives a request with missing required fields, THEN THE System SHALL return HTTP 400 with an error message indicating which fields are missing
5. THE Apps Script webhook SHALL validate that google_drive_url and google_sheets_url are valid URLs starting with "https://"

### Requirement 5a: Реестр привязок работник↔проект (Google Sheets)

**User Story:** As a Worker, I want to see only projects I'm assigned to, so that I don't accidentally file receipts to the wrong project.

#### Acceptance Criteria

1. THE Project Access Registry SHALL be a separate sheet with columns: Project Name (text), Worker Name (text), Role in Project (text: "foreman", "pm", "estimator", "sales", "other")
2. THE Project Access Registry SHALL be populated by: (a) Apps Script webhook from Bitrix24 when a project is created/updated, (b) Admin via bot command /assign
3. A worker SHALL have access to a project if there exists at least one row in Project Access Registry matching their name (regardless of role_in_project)
4. THE System SHALL allow multiple workers with different roles on the same project (e.g., 1 foreman + 1 PM + 1 estimator + 1 sales)
5. THE System SHALL allow the same worker to be assigned to multiple projects
6. Duplicate entries (same project_name + same worker_name) SHALL be prevented; if the worker is already assigned, update the role_in_project instead

### Requirement 6: Реестр работников (Google Sheets)

**User Story:** As an administrator, I want the worker registry to be automatically synced from Bitrix24, so that new employees get bot access as soon as their Telegram ID is added in Bitrix24.

#### Acceptance Criteria

1. THE Worker Registry SHALL be a separate sheet (or tab) in the same Google Sheets workbook as the Project Registry
2. THE Worker Registry SHALL contain columns: Bitrix24 User ID (text, unique key), Telegram ID (integer, unique), Worker Name (text), Role (text: "foreman", "pm", "estimator", "sales", "admin", "other")
3. THE System (bot) SHALL read the Worker Registry at the start of each conversation (on /start) to verify access and determine role
4. THE Worker Registry SHALL be populated and updated by the Apps Script webhook triggered from Bitrix24, EXCEPT for the "admin" role which MAY be set manually in the spreadsheet for initial setup
5. WHEN the Apps Script webhook receives a valid POST request with action "upsert_worker" and fields (bitrix_user_id, worker_name, role, telegram_id), THE System SHALL:
   - If a row with matching bitrix_user_id exists: UPDATE that row's telegram_id, worker_name, and role (but NOT override role if current role is "admin" and incoming role is different — admin role is sticky unless explicitly removed manually)
   - If no row with matching bitrix_user_id exists: APPEND a new row
6. IF telegram_id is empty or zero in the webhook payload, THEN THE System SHALL skip the upsert (worker without Telegram ID is not relevant to the bot)
7. THE System SHALL support removing a worker's access by receiving a webhook with action "remove_worker" and bitrix_user_id, which deletes the matching row

### Requirement 7: Автоматизация Битрикс24

**User Story:** As an administrator, I want Bitrix24 to automatically sync projects and worker data to the bot, so that everything stays up-to-date without manual intervention.

#### Acceptance Criteria

**Robot 1: Синхронизация проекта**

1. IN Bitrix24, THE System SHALL have two custom fields on the Project entity: "Ссылка на Google Drive" (URL) and "Ссылка на Google Sheets" (URL)
2. IN Bitrix24, THE System SHALL have employee fields on the Project entity: "Прораб", "Менеджер проекта", "Сметчик", "Продавец" (all employee/user type)
3. WHEN both Google Drive URL and Google Sheets URL fields are filled on a Project in Bitrix24, THE System SHALL trigger a Robot/Business Process that sends an outgoing webhook (POST) to the Apps Script Web App URL
4. THE webhook payload SHALL contain: action "upsert_project", project_name, google_drive_url, google_sheets_url, and workers array containing each assigned employee with their display name and role (e.g., [{worker_name: "Дима Петров", role_in_project: "foreman"}, {worker_name: "Алекс Борохов", role_in_project: "pm"}, ...])
5. THE Robot SHALL only fire once per project (use a flag field "Sent to Registry" = true to prevent re-sending)
6. IF the webhook returns a non-200 status, THE System SHOULD log the error in Bitrix24 activity feed for the project

**Robot 2: Синхронизация работника**

7. IN Bitrix24, THE System SHALL have a custom field "Telegram ID" (integer) on the Employee/User entity
8. IN Bitrix24, THE System SHALL have a custom field "Роль для бота" (list: "foreman", "pm", "estimator", "sales", "admin", "other") on the Employee/User entity
9. WHEN the "Telegram ID" field is filled or changed on an Employee in Bitrix24, THE System SHALL trigger a Robot/Business Process that sends an outgoing webhook (POST) to the Apps Script Web App URL
10. THE webhook payload SHALL contain: action "upsert_worker", bitrix_user_id, worker_name (employee display name), role (from "Роль для бота" field), telegram_id as JSON
11. THE Robot SHALL fire on every change to the "Telegram ID" or "Роль для бота" field (not just the first time)
12. WHEN an Employee is deactivated/dismissed in Bitrix24, THE System SHALL trigger a webhook with action "remove_worker" and bitrix_user_id to remove access

### Requirement 8: Управление конфигурацией и секретами

**User Story:** As a developer, I want all secrets and configuration to be in a separate file, so that I can easily switch between test and production environments.

#### Acceptance Criteria

1. THE System SHALL store all configuration in a single `.env` file (for local development) and environment variables (for production deployment)
2. THE configuration SHALL include: TELEGRAM_BOT_TOKEN, GOOGLE_SERVICE_ACCOUNT_JSON (path or inline JSON), REGISTRY_SPREADSHEET_ID, WORKERS_SHEET_NAME, APPS_SCRIPT_WEBHOOK_SECRET (shared secret for webhook validation)
3. THE System SHALL include a `.env.example` file with all keys listed (values empty or placeholder) and comments describing each key
4. THE bot code SHALL NOT contain any hardcoded secrets, API keys, spreadsheet IDs, or folder IDs
5. THE Apps Script code SHALL validate incoming webhook requests using a shared secret (passed as a query parameter or header) to prevent unauthorized writes to the registry
6. THE System SHALL include a `config.ts` (or `config.py`) module that reads environment variables and exports typed configuration, failing fast with a clear error if required variables are missing

### Requirement 9: Обработка ошибок и логирование

**User Story:** As a developer, I want the bot to handle errors gracefully and log important events, so that issues can be diagnosed quickly.

#### Acceptance Criteria

1. THE System SHALL log all incoming messages (Telegram ID, timestamp, message type) at INFO level
2. THE System SHALL log all Google API calls (Drive upload, Sheets append) with result status at INFO level
3. THE System SHALL log all errors (API failures, validation errors) at ERROR level with full context (user ID, project, step, error message)
4. IF an unhandled exception occurs during message processing, THEN THE System SHALL respond to the user with "⚠️ Произошла ошибка. Попробуйте позже или напишите /start" and log the exception at ERROR level
5. THE System SHALL use structured logging (JSON format) suitable for Google Cloud Logging
6. THE System SHALL NOT log sensitive data (full file contents, tokens) in log messages

### Requirement 10: Админ-команда — добавление работника на проект

**User Story:** As an Admin, I want to assign any worker to any project through the bot, so that I can grant access to additional employees (e.g., accountant) without going through Bitrix24.

#### Acceptance Criteria

1. WHEN a Worker with role "admin" sends /assign, THE System SHALL start the "assign worker to project" flow
2. IF a non-admin Worker sends /assign, THEN THE System SHALL respond with "Эта команда доступна только администраторам."
3. THE System SHALL first display a list of all active projects as inline keyboard buttons for the admin to select
4. AFTER the admin selects a project, THE System SHALL display a list of all workers from the Worker Registry as inline keyboard buttons (showing "Name — Role") for the admin to select
5. THE System SHALL also offer a button "Ввести вручную" that allows the admin to type worker name and role step-by-step (for workers not yet in the registry)
6. IF the admin selects an existing worker, THE System SHALL ask for the role_in_project (inline keyboard: "Прораб", "ПМ", "Сметчик", "Продавец", "Другое")
7. IF the admin chooses "Ввести вручную", THE System SHALL ask for worker name (text), then role_in_project (inline keyboard)
8. WHEN all data is collected, THE System SHALL write a new row to the Project Access Registry (project_name, worker_name, role_in_project) via Google Sheets API directly
9. THE System SHALL confirm: "✅ [Имя] добавлен на проект [Название] как [Роль]"
10. IF the worker is already assigned to that project, THE System SHALL respond: "Этот работник уже привязан к проекту. Обновить роль?" with Yes/No buttons
11. THE /assign flow SHALL support /cancel at any step to abort
