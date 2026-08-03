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
