# Design Document: Telegram-бот для сбора бумажных счетов

## Overview

Serverless Telegram-бот на Node.js (TypeScript), работающий как Google Cloud Function (webhook). Бот ведёт пошаговый диалог с работником (прораб, ПМ или админ), собирает фото чеков и метаданные, сохраняет в Google Drive и Google Sheets. Отдельный Google Apps Script принимает webhook от Битрикс24 и поддерживает реестры проектов и работников в актуальном состоянии.

Ключевое архитектурное решение — **нулевая инфраструктура**: все компоненты работают на бесплатных сервисах Google, состояние хранится в Google Sheets (отдельный лист), код деплоится одной командой. Все реестры заполняются исключительно через автоматизации Битрикс24 — ручное редактирование не предусмотрено.

## Architecture

### Системная диаграмма

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          TELEGRAM                                        │
│                                                                          │
│   [Работник] ── сообщения/фото ────► [Telegram Bot API]                 │
│   (прораб/ПМ/админ)                          │                           │
│       ▲                                      │ webhook POST              │
│       └──────── ответы ◄─────────────────────┼───────────────┐          │
│                                              ▼               │          │
├──────────────────────────────────────────────────────────────┤          │
│                   GOOGLE CLOUD                                │          │
│                                                               │          │
│   ┌───────────────────────────────────┐                      │          │
│   │  Cloud Function: receipt-bot       │                      │          │
│   │  (Node.js 20, TypeScript)          │──── ответ ──────────┘          │
│   │                                    │                                 │
│   │  • Telegram webhook handler        │                                 │
│   │  • Conversation state machine      │                                 │
│   │  • Role-based project filtering    │                                 │
│   │  • Google Drive upload             │                                 │
│   │  • Google Sheets append            │                                 │
│   └──────────┬────────┬────────┬──────┘                                 │
│              │        │        │                                          │
│              ▼        ▼        ▼                                          │
│   ┌─────────┐ ┌──────┐ ┌─────────────┐                                 │
│   │Sheets   │ │Drive │ │  Sheets     │                                  │
│   │(реестры)│ │(фото)│ │  (сметы)    │                                  │
│   └────┬────┘ └──────┘ └─────────────┘                                  │
│        │                                                                 │
│        │  пишет в реестры                                                │
│        ▼                                                                 │
│   ┌───────────────────────────────────┐                                 │
│   │  Google Apps Script (Web App)      │                                 │
│   │  • POST /exec — webhook receiver   │                                 │
│   │  • action: upsert_project          │                                 │
│   │  • action: upsert_worker           │                                 │
│   │  • action: remove_worker           │                                 │
│   └───────────────────┬───────────────┘                                 │
│                       ▲                                                  │
└───────────────────────┼──────────────────────────────────────────────────┘
                        │ POST (webhooks)
┌───────────────────────┼──────────────────────────────────────────────────┐
│                  БИТРИКС24                                                │
│                                                                           │
│   [Проект] ─── робот 1 (поля заполнены) ──► webhook upsert_project       │
│                                                                           │
│   [Сотрудник] ─ робот 2 (TG ID изменён) ──► webhook upsert_worker        │
│                                                                           │
│   Кастомные поля проекта:                                                │
│   • UF_GOOGLE_DRIVE_URL                                                  │
│   • UF_GOOGLE_SHEETS_URL                                                 │
│   • Прораб (employee)                                                    │
│   • Менеджер проекта (employee)                                          │
│   • Сметчик (employee)                                                   │
│   • Продавец (employee)                                                  │
│   • UF_SENT_TO_REGISTRY (flag)                                           │
│                                                                           │
│   Кастомные поля сотрудника:                                             │
│   • UF_TELEGRAM_ID (integer)                                             │
│   • UF_BOT_ROLE (list: foreman/pm/estimator/sales/admin/other)            │
└───────────────────────────────────────────────────────────────────────────┘
```

### Стек технологий

| Компонент | Технология | Обоснование |
|-----------|-----------|-------------|
| Бот (runtime) | Google Cloud Function gen2 | Serverless, free tier, рядом с Google API |
| Бот (язык) | Node.js 20 + TypeScript | Быстрый cold start, хорошие типы для Google API |
| Telegram SDK | grammY | Lightweight, TypeScript-first, webhook-native |
| Google API | googleapis (npm) | Официальный SDK, поддержка Service Account |
| Состояние | Google Sheets (лист "bot_state") | Без доп. сервисов, достаточно для <100 req/day |
| Apps Script | Google Apps Script (JavaScript) | Бесплатно, живёт внутри таблицы |
| Deploy | gcloud CLI | Одна команда деплоя |
| Secrets | .env + Secret Manager (prod) | Разделение окружений |

### Файловая структура проекта

```
/telegram-bot/
├── src/
│   ├── index.ts              # Cloud Function entry point (webhook handler)
│   ├── config.ts             # Environment config loader with validation
│   ├── bot.ts                # grammY bot instance and middleware setup
│   ├── handlers/
│   │   ├── start.ts          # /start command — auth + project selection
│   │   ├── photo.ts          # Photo message handler
│   │   ├── text.ts           # Text message handler (sum, description, store)
│   │   └── cancel.ts         # /cancel command handler
│   ├── services/
│   │   ├── sheets.ts         # Google Sheets read/write operations
│   │   ├── drive.ts          # Google Drive upload operations
│   │   └── telegram.ts       # Telegram file download helper
│   ├── state/
│   │   ├── machine.ts        # Conversation state machine (steps enum + transitions)
│   │   └── store.ts          # State persistence (read/write to Sheets "bot_state" tab)
│   └── utils/
│       ├── logger.ts         # Structured JSON logger
│       ├── transliterate.ts  # Cyrillic → Latin for file naming
│       └── validators.ts     # Input validation helpers
├── apps-script/
│   ├── Code.gs              # Apps Script webhook handler (doPost)
│   └── README.md            # Инструкция по деплою Apps Script
├── bitrix24/
│   └── README.md            # Инструкция по настройке робота в Битрикс24
├── .env.example             # Template with all required env vars
├── .env                     # Local secrets (gitignored)
├── .gitignore
├── package.json
├── tsconfig.json
└── deploy.sh                # One-command deploy script
```

## Components and Interfaces

### Component 1: Cloud Function Entry Point (`index.ts`)

**Responsibility:** Принимает HTTP POST от Telegram, парсит Update, направляет в grammY.

```typescript
// Интерфейс
export async function receiptBot(req: Request, res: Response): Promise<void>
```

- Проверяет секретный токен в URL (webhook secret)
- Передаёт body в `bot.handleUpdate()`
- Возвращает 200 OK (Telegram требует быстрый ответ)

### Component 2: Bot Instance (`bot.ts`)

**Responsibility:** Конфигурация grammY бота, middleware chain.

```typescript
// Middleware chain:
// 1. Logger middleware (log every update)
// 2. State loader middleware (load user state from Sheets)
// 3. Auth middleware (check Foreman Registry)
// 4. Router (command handlers + message handlers)
```

### Component 3: Conversation State Machine (`state/machine.ts`)

**Responsibility:** Определяет шаги диалога и допустимые переходы.

```typescript
enum ConversationStep {
  IDLE = "idle",                      // Ожидание /start
  SELECT_PROJECT = "select_project",  // Показаны кнопки проектов
  AWAIT_PHOTO = "await_photo",        // Ожидание фото
  AWAIT_SUM = "await_sum",            // Ожидание суммы
  AWAIT_DESCRIPTION = "await_desc",   // Ожидание описания
  AWAIT_STORE = "await_store",        // Ожидание магазина
  SAVING = "saving"                   // Сохранение в Drive + Sheets
}

interface ConversationState {
  visitorId: number;          // Telegram user ID
  step: ConversationStep;
  selectedProject?: {
    name: string;
    driveUrl: string;
    sheetsUrl: string;
  };
  receipt?: {
    photoFileId: string;
    sum?: number;
    description?: string;
    storeName?: string;
  };
  updatedAt: string;          // ISO timestamp
}
```

### Component 4: State Store (`state/store.ts`)

**Responsibility:** Персистентность состояния диалога в Google Sheets.

```typescript
interface StateStore {
  getState(telegramId: number): Promise<ConversationState | null>;
  setState(state: ConversationState): Promise<void>;
  clearState(telegramId: number): Promise<void>;
}
```

**Реализация:** Отдельный лист "bot_state" в таблице реестра проектов.

| Столбец | Описание |
|---------|----------|
| telegram_id | Telegram ID пользователя (ключ) |
| step | Текущий шаг (enum) |
| project_name | Выбранный проект |
| project_drive_url | URL папки Drive |
| project_sheets_url | URL таблицы сметы |
| photo_file_id | Telegram file_id фото |
| sum | Сумма |
| description | Описание покупки |
| store_name | Магазин |
| updated_at | Время последнего обновления |

При каждом взаимодействии бот читает строку по telegram_id, обновляет и записывает обратно. При завершении — удаляет строку.

### Component 5: Google Sheets Service (`services/sheets.ts`)

**Responsibility:** Чтение реестров и запись данных.

```typescript
interface SheetsService {
  // Чтение реестра работников
  getWorker(telegramId: number): Promise<Worker | null>;

  // Чтение всех работников (для админ-команды /assign)
  getAllWorkers(): Promise<Worker[]>;

  // Чтение проектов для работника (через project_access или all for admin)
  getProjectsForWorker(worker: Worker): Promise<Project[]>;

  // Чтение всех активных проектов (для админ-команды /assign)
  getAllActiveProjects(): Promise<Project[]>;

  // Запись строки в смету проекта
  appendReceiptRow(sheetsUrl: string, row: ReceiptRow): Promise<void>;

  // Добавление/обновление привязки работника к проекту (admin command)
  upsertProjectAccess(projectName: string, workerName: string, roleInProject: string): Promise<"created" | "updated">;

  // Проверка существования привязки
  getProjectAccess(projectName: string, workerName: string): Promise<ProjectAccess | null>;
}

interface Worker {
  bitrixUserId: string;
  telegramId: number;
  name: string;
  role: "foreman" | "pm" | "estimator" | "sales" | "admin" | "other";
}

interface Project {
  name: string;
  googleDriveUrl: string;
  googleSheetsUrl: string;
  status: "active" | "archived";
}

interface ProjectAccess {
  projectName: string;
  workerName: string;
  roleInProject: string;
}

interface ReceiptRow {
  date: string;       // YYYY-MM-DD
  sum: number;
  description: string;
  storeName: string;
  photoLink: string;
}
```

**Логика фильтрации проектов по роли:**
```typescript
function getProjectsForWorker(worker: Worker): Project[] {
  if (worker.role === "admin") {
    return allActiveProjects; // админ видит все
  }
  // Для остальных — через таблицу привязок
  const accessRows = projectAccess.filter(a => a.workerName === worker.name);
  const projectNames = accessRows.map(a => a.projectName);
  return allActiveProjects.filter(p => projectNames.includes(p.name));
}
```

### Component 6: Google Drive Service (`services/drive.ts`)

**Responsibility:** Загрузка файлов на Drive.

```typescript
interface DriveService {
  uploadPhoto(
    folderId: string,
    fileName: string,
    fileBuffer: Buffer,
    mimeType: string
  ): Promise<{ fileId: string; webViewLink: string }>;
}
```

**Логика именования файла:**
```
2025-01-15_14-30_lerua-merlen_340.jpg
```
Pattern: `YYYY-MM-DD_HH-MM_<transliterated_store>_<sum>.<ext>`

### Component 7: Google Apps Script (`apps-script/Code.gs`)

**Responsibility:** Webhook-приёмник от Битрикс24 для обоих реестров (проекты + работники).

```javascript
function doPost(e) {
  // 1. Validate secret
  // 2. Parse JSON body
  // 3. Route by action field:
  //    - "upsert_project" → validate + upsert in projects sheet
  //    - "upsert_worker" → validate + upsert in workers sheet (by bitrix_user_id)
  //    - "remove_worker" → find and delete row in workers sheet
  // 4. Return result
}
```

**Входные форматы (POST body):**

Action: `upsert_project`
```json
{
  "secret": "shared_webhook_secret",
  "action": "upsert_project",
  "project_name": "Квартира Иванова",
  "google_drive_url": "https://drive.google.com/drive/folders/xxx",
  "google_sheets_url": "https://docs.google.com/spreadsheets/d/xxx",
  "workers": [
    {"worker_name": "Дима Петров", "role_in_project": "foreman"},
    {"worker_name": "Алекс Борохов", "role_in_project": "pm"},
    {"worker_name": "Ира Сидорова", "role_in_project": "estimator"},
    {"worker_name": "Олег Козлов", "role_in_project": "sales"}
  ]
}
```

Action: `upsert_worker`
```json
{
  "secret": "shared_webhook_secret",
  "action": "upsert_worker",
  "bitrix_user_id": "user_45",
  "worker_name": "Дима Петров",
  "role": "foreman",
  "telegram_id": 123456789
}
```

Action: `remove_worker`
```json
{
  "secret": "shared_webhook_secret",
  "action": "remove_worker",
  "bitrix_user_id": "user_45"
}
```

**Ответы:**
- 200 `{"status": "ok", "message": "project upserted"}` — проект добавлен/обновлён
- 200 `{"status": "ok", "message": "worker upserted"}` — работник добавлен/обновлён
- 200 `{"status": "ok", "message": "worker removed"}` — работник удалён
- 200 `{"status": "ok", "message": "worker not found, skipped"}` — удаление несуществующего
- 400 `{"status": "error", "message": "missing fields: ..."}` — ошибка валидации
- 400 `{"status": "error", "message": "unknown action"}` — неизвестное действие
- 401 `{"status": "error", "message": "invalid secret"}` — неверный секрет

### Component 8: Битрикс24 Robot Configuration

**Responsibility:** Синхронизация проектов и работников с реестрами через Apps Script.

**Настройка (выполняется вручную в интерфейсе Битрикс24):**

#### Робот 1: Синхронизация проекта

1. Кастомные поля на сущности "Группа/Проект":
   - `UF_GOOGLE_DRIVE_URL` (строка, URL)
   - `UF_GOOGLE_SHEETS_URL` (строка, URL)
   - `Прораб` (привязка к сотруднику — employee)
   - `Менеджер проекта` (привязка к сотруднику — employee)
   - `Сметчик` (привязка к сотруднику — employee)
   - `Продавец` (привязка к сотруднику — employee)
   - `UF_SENT_TO_REGISTRY` (Да/Нет, по умолчанию Нет)

2. Робот (бизнес-процесс):
   - **Триггер:** Изменение группы/проекта
   - **Условие:** `UF_GOOGLE_DRIVE_URL` не пустое И `UF_GOOGLE_SHEETS_URL` не пустое И хотя бы один сотрудник назначен И `UF_SENT_TO_REGISTRY` = Нет
   - **Действие 1:** Webhook (POST) на Apps Script URL с телом:
     ```json
     {
       "secret": "{{shared_secret}}",
       "action": "upsert_project",
       "project_name": "{{Название группы}}",
       "google_drive_url": "{{UF_GOOGLE_DRIVE_URL}}",
       "google_sheets_url": "{{UF_GOOGLE_SHEETS_URL}}",
       "workers": [
         {"worker_name": "{{Прораб: Имя}} {{Прораб: Фамилия}}", "role_in_project": "foreman"},
         {"worker_name": "{{Менеджер: Имя}} {{Менеджер: Фамилия}}", "role_in_project": "pm"},
         {"worker_name": "{{Сметчик: Имя}} {{Сметчик: Фамилия}}", "role_in_project": "estimator"},
         {"worker_name": "{{Продавец: Имя}} {{Продавец: Фамилия}}", "role_in_project": "sales"}
       ]
     }
     ```
   - **Действие 2:** Установить `UF_SENT_TO_REGISTRY` = Да
   - **Примечание:** Пустые worker_name (если поле не заполнено) Apps Script игнорирует

#### Робот 2: Синхронизация работника

1. Кастомные поля на сущности "Сотрудник":
   - `UF_TELEGRAM_ID` (целое число)
   - `UF_BOT_ROLE` (список: foreman, pm, estimator, sales, admin, other)

2. Робот (бизнес-процесс):
   - **Триггер:** Изменение карточки сотрудника
   - **Условие:** `UF_TELEGRAM_ID` не пустое И `UF_BOT_ROLE` не пустое
   - **Действие:** Webhook (POST) на Apps Script URL с телом:
     ```json
     {
       "secret": "{{shared_secret}}",
       "action": "upsert_worker",
       "bitrix_user_id": "{{ID сотрудника}}",
       "worker_name": "{{Имя}} {{Фамилия}}",
       "role": "{{UF_BOT_ROLE}}",
       "telegram_id": "{{UF_TELEGRAM_ID}}"
     }
     ```

3. Робот (при увольнении/деактивации):
   - **Триггер:** Деактивация сотрудника
   - **Действие:** Webhook (POST):
     ```json
     {
       "secret": "{{shared_secret}}",
       "action": "remove_worker",
       "bitrix_user_id": "{{ID сотрудника}}"
     }
     ```

## Data Models

### Google Sheets: Реестр проектов (лист "projects")

| Column | Type | Description |
|--------|------|-------------|
| A: project_name | string | Название проекта (unique key for upsert) |
| B: google_drive_url | URL | Ссылка на папку Drive проекта |
| C: google_sheets_url | URL | Ссылка на таблицу-смету проекта |
| D: status | string | Статус проекта: "active" или "archived" (default: "active") |
| E: date_added | date | Дата добавления/обновления записи |

### Google Sheets: Реестр привязок (лист "project_access")

| Column | Type | Description |
|--------|------|-------------|
| A: project_name | string | Название проекта (FK → projects) |
| B: worker_name | string | Имя работника |
| C: role_in_project | string | Роль на проекте: "foreman", "pm", "estimator", "sales", "other" |

Composite key: (project_name + worker_name) — уникальная пара.

### Google Sheets: Реестр работников (лист "workers")

| Column | Type | Description |
|--------|------|-------------|
| A: bitrix_user_id | string | ID в Битрикс24 (unique key for upsert) |
| B: telegram_id | integer | Telegram ID |
| C: worker_name | string | Имя работника |
| D: role | string | Роль: "foreman", "pm", "estimator", "sales", "admin", "other" |

### Google Sheets: Состояние бота (лист "bot_state")

| Column | Type | Description |
|--------|------|-------------|
| A: telegram_id | integer | Ключ |
| B: step | enum string | Текущий шаг |
| C: project_name | string | Выбранный проект |
| D: project_drive_url | URL | URL Drive папки |
| E: project_sheets_url | URL | URL таблицы сметы |
| F: photo_file_id | string | Telegram file_id |
| G: sum | number | Сумма |
| H: description | string | Что куплено |
| I: store_name | string | Магазин |
| J: updated_at | ISO string | Последнее обновление |

### Google Sheets: Смета проекта (в отдельном файле, по одному на проект)

| Column | Type | Description |
|--------|------|-------------|
| A: date | date (YYYY-MM-DD) | Дата покупки |
| B: sum | number | Сумма |
| C: description | string | Что куплено |
| D: store_name | string | Название магазина |
| E: photo_link | URL | Ссылка на фото в Drive |

### Конфигурация (.env)

```bash
# Telegram
TELEGRAM_BOT_TOKEN=              # Токен от @BotFather
TELEGRAM_WEBHOOK_SECRET=         # Секрет для верификации webhook URL

# Google Service Account
GOOGLE_SERVICE_ACCOUNT_JSON=     # Путь к JSON-ключу сервисного аккаунта

# Google Sheets
REGISTRY_SPREADSHEET_ID=         # ID таблицы с реестрами (projects, workers, bot_state)
PROJECTS_SHEET_NAME=projects     # Название листа реестра проектов
WORKERS_SHEET_NAME=workers       # Название листа реестра работников
ACCESS_SHEET_NAME=project_access # Название листа привязок работник↔проект
BOT_STATE_SHEET_NAME=bot_state   # Название листа состояния бота

# Apps Script Webhook
APPS_SCRIPT_WEBHOOK_SECRET=      # Общий секрет для валидации входящих webhooks

# Cloud Function
GCP_PROJECT_ID=                  # Google Cloud Project ID
GCP_REGION=me-west1              # Регион (ближайший к Израилю)
FUNCTION_NAME=receipt-bot        # Имя Cloud Function
```

## Error Handling

### Telegram Bot

| Ситуация | Действие |
|----------|----------|
| Неизвестный пользователь | "Доступ запрещён" + показ Telegram ID |
| Нет проектов | "Нет активных проектов" |
| Невалидная сумма | Повторный запрос с подсказкой формата |
| Нефото вместо фото | "Отправьте фото чека" |
| Drive upload failed | Retry 1x → сообщение об ошибке |
| Sheets write failed | Retry 1x → сообщение + сохранение для /retry |
| Unhandled exception | Generic error message + log |
| State corrupted/old (>24h) | Auto-reset to IDLE |

### Apps Script Webhook

| Ситуация | HTTP Code | Ответ |
|----------|-----------|-------|
| Invalid secret | 401 | `{"status":"error","message":"invalid secret"}` |
| Unknown action | 400 | `{"status":"error","message":"unknown action"}` |
| Missing fields | 400 | `{"status":"error","message":"missing: field1, field2"}` |
| Project upserted | 200 | `{"status":"ok","message":"project upserted"}` |
| Worker upserted | 200 | `{"status":"ok","message":"worker upserted"}` |
| Worker removed | 200 | `{"status":"ok","message":"worker removed"}` |
| Worker not found | 200 | `{"status":"ok","message":"worker not found, skipped"}` |
| Sheets API error | 500 | `{"status":"error","message":"internal error"}` |

### Retry Strategy

- Google API calls: 1 automatic retry after 2s delay
- Telegram API calls: no retry (grammY handles internally)
- Stale state cleanup: states older than 24 hours are auto-cleared on next access

## Testing Strategy

### Unit Tests

| Module | What to test |
|--------|-------------|
| `validators.ts` | Sum parsing, URL validation, text length limits |
| `transliterate.ts` | Cyrillic → Latin conversion, special characters |
| `machine.ts` | State transitions, invalid transition rejection |
| `config.ts` | Missing env var detection, type coercion |

### Integration Tests (local)

| Scenario | Approach |
|----------|----------|
| Full conversation flow | Mock Telegram API + real Sheets (test spreadsheet) |
| Drive upload | Real upload to test folder |
| Apps Script webhook | HTTP POST to deployed test script |

### E2E Tests (manual)

| Test | Steps |
|------|-------|
| Happy path | /start → select project → photo → sum → desc → store → verify in Sheets + Drive |
| Unknown user | Send /start from unregistered Telegram account |
| Invalid sum | Send "abc" instead of number |
| Cancel flow | Start receipt, /cancel mid-way, verify state cleared |
| Bitrix→Registry | Fill fields in Bitrix24, verify row appears in registry |

### PBT Assessment

Property-based testing applicable for:
- `transliterate()`: for any cyrillic input, output contains only [a-z0-9-_]
- `validateSum()`: for any string matching `/^\d+(\.\d{1,2})?$/`, returns valid number
- State machine: for any valid state+input, next state is deterministic and valid

## Security Considerations

1. **Webhook verification**: Telegram webhook URL contains secret token; Apps Script validates shared secret
2. **Service Account permissions**: Минимальные права — только к конкретным папкам/таблицам (через sharing)
3. **No secret logging**: Tokens and keys never appear in logs
4. **Input sanitization**: All user text trimmed, length-limited, no code execution
5. **State isolation**: Each user's state keyed by their Telegram ID, no cross-user access
6. **HTTPS only**: All communication over TLS (Cloud Functions, Apps Script, Telegram API)

## Addendum: Session Log (Журнал сессий)

> This addendum documents the Session Log feature (Requirement 11). It reflects the current implemented codebase, which has evolved beyond the original design above: the receipt flow now supports multi-page receipts (`photoFileIds: string[]`, up to 10 pages) and an OCR pipeline (Google Cloud Vision OCR + Gemini parsing) with a sum-verification/mismatch confirmation step. The Session Log wraps that existing flow with persistent, append-then-update logging. It is a backend-only data artifact: no UI, no i18n fields.

### Purpose and relationship to `bot_state`

`bot_state` (sheet, columns A–M in the workers workbook) is ephemeral conversation state. `getState`/`setState` upsert a single row per `telegram_id`; `clearState` blanks the row on success/cancel; rows older than 24h are auto-purged in `getState`. It cannot serve as history.

The Session Log is a **separate** sheet (`session_log`) in the **same** workbook (`WORKERS_SPREADSHEET_ID`). It records one row per session, keyed by a generated `session_id`, and is never cleared by the completion/cancel/stale paths. This is the "variant B" decision: `bot_state` stays as-is for live state; `session_log` mirrors the lifecycle into durable history.

Both sheets are written during a session, but they are independent: a failure to write `session_log` must never break the receipt flow (best-effort logging).

### Data Model — Google Sheets: `session_log`

Sheet name configurable via `SESSION_LOG_SHEET_NAME` (default `session_log`). Columns:

| Col | Field | Type | Notes |
|-----|-------|------|-------|
| A | session_id | string (UUID) | Unique key. Generated on session start. |
| B | telegram_id | integer | |
| C | worker_name | string | Resolved from Worker Registry at session start |
| D | role | string | Resolved from Worker Registry at session start |
| E | started_at | ISO 8601 | Session start |
| F | last_activity_at | ISO 8601 | Updated on every interaction |
| G | step | string | Current/final `ConversationStep` |
| H | project_name | string | |
| I | project_drive_url | URL | |
| J | project_sheets_url | URL | |
| K | photo_file_ids | JSON array | Multi-page: `["id1","id2",...]` (one-element for single page) |
| L | photo_links | JSON array | Drive links; filled on successful upload |
| M | sum | number | Recognized-or-entered amount actually used |
| N | description | string | |
| O | store_name | string | |
| P | status | enum | `in_progress` \| `success` \| `failed` \| `cancelled` |
| Q | error_trace | string | Sanitized message + stack; empty unless failure |

`photo_file_ids` / `photo_links` reuse the JSON-array serialization already present in `state/store.ts` (`serializePhotoIds` / `deserializePhotoIds`).

### Component: SessionLogService (`src/services/session-log.ts`)

New module, using the same `GoogleAuth` + Sheets v4 client and the same `spreadsheetId = config.google.workersSpreadsheetId` as `state/store.ts`.

```typescript
interface SessionLogRecord {
  sessionId: string;
  telegramId: number;
  workerName?: string;
  role?: string;
  startedAt: string;         // ISO
  lastActivityAt: string;    // ISO
  step: string;              // ConversationStep
  projectName?: string;
  projectDriveUrl?: string;
  projectSheetsUrl?: string;
  photoFileIds?: string[];
  photoLinks?: string[];
  sum?: number;
  description?: string;
  storeName?: string;
  status: "in_progress" | "success" | "failed" | "cancelled";
  errorTrace?: string;
}

interface SessionLogService {
  // Create a new row with status=in_progress. Returns the sessionId.
  startSession(input: {
    sessionId: string;
    telegramId: number;
    workerName?: string;
    role?: string;
    step: string;
  }): Promise<void>;

  // Upsert (update by sessionId) with the latest state; refreshes last_activity_at.
  updateSession(sessionId: string, patch: Partial<SessionLogRecord>): Promise<void>;

  // Terminal transitions (set status + last_activity_at, plus final fields / error).
  finalizeSuccess(sessionId: string, patch: Partial<SessionLogRecord>): Promise<void>;
  finalizeCancelled(sessionId: string): Promise<void>;
  finalizeFailed(sessionId: string, error: unknown, patch?: Partial<SessionLogRecord>): Promise<void>;
}
```

Implementation notes:
- Row lookup is by `session_id` in column A (analogous to the `telegram_id` lookup in `store.ts`): read `session_log!A2:A`, find index, update `A{n}:Q{n}`; append if not found.
- Every method wraps its Sheets call in try/catch and, on failure, logs via `logger.error("session-log write failed", ...)` and returns without throwing (Requirement 11.12 — best-effort, never breaks the flow).
- `finalizeFailed` runs the error through a sanitizer before writing (below).

### session_id lifecycle and where it lives

The session must be correlated across handlers (photo.ts, text.ts, callback.ts) that each independently load state via `getState`. To carry the id without a second lookup, add a `sessionId?: string` field to `ConversationState` (`state/machine.ts`) and persist it in a new `bot_state` column (extend the sheet to column N; update `store.ts` read/write ranges from `A:M` to `A:N`).

Flow:
- On `/start` (in `handlers/start.ts`), after the worker is recognized and a fresh receipt flow begins: generate `sessionId = crypto.randomUUID()`, set it on the state, and call `sessionLog.startSession(...)`.
- All subsequent handlers read `state.sessionId` and call `sessionLog.updateSession` / finalize methods.
- If a worker sends `/start` again mid-flow (allowed by Requirement 2.6), the previous unfinished session row remains `in_progress` (it represents an abandoned session) and a new `session_id` is generated for the new flow.

### Error trace sanitization (`src/utils/sanitize.ts`)

`sanitizeErrorTrace(error: unknown): string`:
- Compose `message` + `stack` (stack already truncated in current code to ~500 chars in some paths; keep a generous cap, e.g. 4000 chars, to fit a Sheets cell comfortably).
- Redact: the bot token, service-account private key material, Gemini API key, webhook secrets, and any `https://...` query strings that may carry tokens (replace with `***REDACTED***`). Source the secret values from `config` so redaction stays in sync.
- This satisfies Requirement 11.10 and reuses the intent of Requirement 9.6 (no secret logging).

### Integration points (where calls are added)

| Location (existing code) | Session Log call |
|--------------------------|------------------|
| `handlers/start.ts` — new receipt flow begins after auth + project resolution | `startSession(...)` with resolved worker_name/role; store `sessionId` in state |
| `handlers/start.ts` — project selected (callback) | `updateSession(step=AWAIT_PHOTO, project_name/urls)` |
| `handlers/photo.ts` — page received (both legacy and OCR flow) | `updateSession(step, photo_file_ids)` |
| `handlers/callback.ts` — `ocr:done` → `processOcr` transitions | `updateSession(step, description/store_name)` |
| `handlers/text.ts` — AWAIT_SUM / AWAIT_DESCRIPTION / AWAIT_STORE steps | `updateSession(step, sum/description/store_name)` |
| `handlers/text.ts` `saveReceipt` — success branch (after `clearState`) | `finalizeSuccess(sum, photo_links=[photoLink])` |
| `handlers/text.ts` `saveReceipt` — Drive/Sheets failure branches & catch | `finalizeFailed(error, ...)` |
| `handlers/callback.ts` `saveReceiptOcr` — success branch | `finalizeSuccess(sum, photo_links=links)` (reuse `links` already produced by `uploadPhotos`) |
| `handlers/callback.ts` `saveReceiptOcr` — Drive/Sheets failure branches & catch | `finalizeFailed(error, ...)` |
| `handlers/callback.ts` `processOcr` — OCR/Gemini failure paths | `finalizeFailed(error, ...)` OR keep `in_progress` if the user can retry (retry keeps same session; see note) |
| `handlers/cancel.ts` — `/cancel` and the Отмена button (callback `cancel`) | `finalizeCancelled(sessionId)` before/after `clearState` |

Note on OCR retry: `ocr:retry` and `ocr:manual` keep the same session (the user continues), so those paths call `updateSession`, not a finalize. Only a terminal give-up/exception finalizes as `failed`.

The critical "reuse the Drive link" point (Requirement 11.7): in `saveReceiptOcr` the `links` array from `uploadPhotos(...)` is already in scope and is the exact value written to the estimate via `formatPhotoLinks(links)`. `finalizeSuccess` stores that same array as `photo_links` — no additional upload or Drive call.

### Config additions

Add to `config.google` in `src/config.ts`:
```typescript
sessionLogSheetName: process.env.SESSION_LOG_SHEET_NAME || "session_log",
```
Document `SESSION_LOG_SHEET_NAME` in `.env.example`. No new spreadsheet, credentials, or scopes are required (reuses `WORKERS_SPREADSHEET_ID` and the existing spreadsheets scope).

### Verification value (answers "how do we know all receipts were recorded")

With the Session Log in place, an administrator can answer the customer's question without reading chat history:
- Count `status = success` rows per project/worker/date.
- `status = failed` rows (with `error_trace`) are exactly the receipts a worker sent that did NOT reach the estimate.
- `status = cancelled` / lingering `in_progress` rows show abandoned attempts.
- Because a successful row carries the same Drive `photo_links` written to the estimate, the log ↔ estimate correspondence can be cross-checked. (A dedicated reconciliation report is out of scope for this iteration but is enabled by this data model.)
