# Настройка роботов Битрикс24

## Предварительные требования

1. Apps Script задеплоен как Web App (см. `apps-script/README.md`)
2. URL вебхука скопирован (формат: `https://script.google.com/macros/s/.../exec`)

---

## Робот 1: Синхронизация проекта

### Кастомные поля на сущности "Группа/Проект"

Создай следующие поля (CRM → Настройки → Пользовательские поля, или в карточке проекта):

| Поле | Тип | Код |
|------|-----|-----|
| Ссылка на Google Drive | Строка | `UF_GOOGLE_DRIVE_URL` |
| Ссылка на Google Sheets | Строка | `UF_GOOGLE_SHEETS_URL` |
| Прораб | Привязка к сотруднику | (стандартное или `UF_FOREMAN`) |
| Менеджер проекта | Привязка к сотруднику | (стандартное или `UF_PM`) |
| Сметчик | Привязка к сотруднику | `UF_ESTIMATOR` |
| Продавец | Привязка к сотруднику | `UF_SALES` |
| Отправлено в реестр | Да/Нет | `UF_SENT_TO_REGISTRY` |

### Настройка робота

1. Перейди в **Автоматизация** (роботы) для проектов/групп
2. Создай новый робот:

**Триггер:** Создание или изменение группы/проекта

**Условие:**
- Хотя бы один сотрудник назначен (Прораб или Менеджер)
- `UF_SENT_TO_REGISTRY` = Нет

> **Важно:** Поля `UF_GOOGLE_DRIVE_URL` и `UF_GOOGLE_SHEETS_URL` могут быть пустыми!
> Если они пустые — webhook автоматически создаст папку и смету в Google Drive и вернёт URL'ы.

**Действие 1: Webhook (исходящий)**
- Метод: POST
- URL: `<WEBHOOK_URL>` (URL Cloud Function или localhost для теста)
- Тело (JSON):

```json
{
  "secret": "foremen-apps-script-secret-2024",
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

> Если `google_drive_url` и `google_sheets_url` пустые строки (поля не заполнены) — webhook создаст ресурсы и вернёт URL'ы в ответе.

**Действие 2: Заполнить поля из ответа webhook** (условие: если поля были пустые)
- `UF_GOOGLE_DRIVE_URL` = `{{Результат webhook.google_drive_url}}`
- `UF_GOOGLE_SHEETS_URL` = `{{Результат webhook.google_sheets_url}}`

**Действие 3: Изменить поле**
- Установить `UF_SENT_TO_REGISTRY` = Да

### Логика работы

| Сценарий | Что происходит |
|----------|----------------|
| Новый проект (Drive/Sheets пустые) | Webhook создаёт папку + смету → возвращает URL'ы → робот записывает их в проект |
| Обновление проекта (Drive/Sheets заполнены) | Webhook обновляет реестр и привязки работников (URL'ы не меняются) |
| Повторное сохранение | `UF_SENT_TO_REGISTRY` = Да → робот не срабатывает |

### Формат ответа webhook

```json
{
  "status": "ok",
  "message": "project created with new resources",
  "google_drive_url": "https://drive.google.com/drive/folders/...",
  "google_sheets_url": "https://docs.google.com/spreadsheets/d/.../edit"
}
```

Поля `google_drive_url` и `google_sheets_url` присутствуют в ответе **только** если ресурсы были созданы. Робот должен заполнять поля проекта из этих значений.

---

## Робот 2: Синхронизация работника

### Кастомные поля на сущности "Сотрудник"

| Поле | Тип | Код |
|------|-----|-----|
| Telegram ID | Целое число | `UF_TELEGRAM_ID` |
| Роль для бота | Список | `UF_BOT_ROLE` |

**Значения списка UF_BOT_ROLE:**
- `foreman` — Прораб
- `pm` — Проект-менеджер
- `estimator` — Сметчик
- `sales` — Продавец
- `admin` — Администратор
- `other` — Другое

### Настройка робота

**Триггер:** Изменение карточки сотрудника

**Условие:**
- `UF_TELEGRAM_ID` не пустое
- `UF_BOT_ROLE` не пустое

**Действие: Webhook (исходящий)**
- Метод: POST
- URL: `https://script.google.com/macros/s/XXXXX/exec`
- Тело (JSON):

```json
{
  "secret": "foremen-apps-script-secret-2024",
  "action": "upsert_worker",
  "bitrix_user_id": "{{ID сотрудника}}",
  "worker_name": "{{Имя}} {{Фамилия}}",
  "role": "{{UF_BOT_ROLE}}",
  "telegram_id": "{{UF_TELEGRAM_ID}}"
}
```

---

## Робот 3: Удаление работника (увольнение)

### Настройка робота

**Триггер:** Деактивация/увольнение сотрудника

**Действие: Webhook (исходящий)**
- Метод: POST
- URL: `https://script.google.com/macros/s/XXXXX/exec`
- Тело (JSON):

```json
{
  "secret": "foremen-apps-script-secret-2024",
  "action": "remove_worker",
  "bitrix_user_id": "{{ID сотрудника}}"
}
```

---

## Тестирование

### Проверка робота 1 (проект):
1. Создай тестовый проект в Битрикс24
2. Назначь прораба и менеджера
3. Заполни поля `UF_GOOGLE_DRIVE_URL` и `UF_GOOGLE_SHEETS_URL` (любые https:// URL для теста)
4. Убедись что `UF_SENT_TO_REGISTRY` = Нет
5. Сохрани → робот должен сработать
6. Проверь в Google Sheets "Реестр проектов" — должна появиться строка

### Проверка робота 2 (работник):
1. Открой карточку сотрудника в Битрикс24
2. Заполни `UF_TELEGRAM_ID` (например 123456789) и `UF_BOT_ROLE` (например "foreman")
3. Сохрани → робот должен сработать
4. Проверь в Google Sheets "Реестр работников" лист "workers" — должна появиться строка

### Отладка:
- В Apps Script: **Executions** (боковая панель) — видны все вызовы и ошибки
- В Битрикс24: лог роботов в карточке проекта/сотрудника — статус webhook

---

## Данные для настройки

| Параметр | Значение |
|----------|----------|
| Webhook Secret | `foremen-apps-script-secret-2024` |
| Webhook URL | Получишь после деплоя: `bash deploy.sh` (выведет URL) |
| Для локального теста | `http://localhost:3000` (запусти `bun run dev:webhook`) |
| Workers Registry ID | `10Y_f33aXzBhCz55y-l-GeX5raYeyTZb9gGiQt6azchs` |
| Projects Registry ID | `1YjSN9w_OpCFKNElj0Nrhvva2JgWlMtmSHbiVDFuSKaQ` |

## Деплой

Webhook реализован как **Google Cloud Function** (не Apps Script).
Деплоится одной командой:

```bash
cd telegram-bot
bash deploy.sh
```

Это задеплоит 2 функции:
- `receipt-bot` — Telegram бот (webhook)
- `bitrix-webhook` — приёмник данных от Битрикс24

URL для Битрикс24 роботов — это URL функции `bitrix-webhook` (выводится после деплоя).
