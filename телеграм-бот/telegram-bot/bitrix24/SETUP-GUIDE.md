# Пошаговая настройка Битрикс24 (с нуля)

Минимальный сетап для тестирования webhook-интеграции с ботом.

**Исходные условия:** свежий аккаунт Битрикс24, ничего не настроено.

---

## Шаг 1: Добавить поле "Telegram ID" в карточку сотрудника

1. Перейди: **Компания** (левое меню) → **Сотрудники**
2. Открой свою карточку сотрудника (нажми на своё имя)
3. Нажми **"Редактировать"**
4. Прокрути вниз → найди секцию или нажми **"Добавить поле"** (или "Ещё" → "Добавить пользовательское поле")
5. Создай поле:
   - Название: `Telegram ID`
   - Тип: **Число** (целое)
   - Код (если спросит): `UF_TELEGRAM_ID`
6. Создай ещё одно поле:
   - Название: `Роль для бота`
   - Тип: **Список**
   - Значения списка:
     - `foreman`
     - `pm`
     - `estimator`
     - `sales`
     - `admin`
     - `other`
   - Код: `UF_BOT_ROLE`
7. Заполни для себя:
   - Telegram ID: `571744833`
   - Роль для бота: `admin`
8. Сохрани

> **Примечание:** Если не получается добавить пользовательское поле в карточку сотрудника напрямую — попробуй через **Настройки** → **Настройки продукта** → **Пользовательские поля** → сущность "Пользователь".

---

## Шаг 2: Создать проект с нужными полями

1. Перейди: **Задачи и проекты** → **Проекты** (или **Группы**)
2. Нажми **"Создать проект"** (или "Создать группу")
3. Назови: `Тест-проект`
4. Создай проект (пока без доп. полей — их добавим через настройки)

### Добавить пользовательские поля проекта:

5. Перейди в созданный проект → **Настройки** (шестерёнка) → **Пользовательские поля** (или может быть через Настройки → Рабочие группы → Пользовательские поля)

   > В некоторых версиях Битрикс24: **Настройки** (основные) → **Настройки продукта** → **Пользовательские поля** → сущность "Рабочая группа" (или "Социальная сеть: группы")

6. Создай поля:

| Название | Тип | Код |
|----------|-----|-----|
| Ссылка на Google Drive | Строка | `UF_GOOGLE_DRIVE_URL` |
| Ссылка на Google Sheets | Строка | `UF_GOOGLE_SHEETS_URL` |
| Отправлено в реестр | Да/Нет | `UF_SENT_TO_REGISTRY` |

7. Вернись в проект → **Редактировать** → заполнишь эти поля позже (или робот заполнит автоматически)

---

## Шаг 3: Назначить ответственных в проекте

1. Открой проект `Тест-проект`
2. В настройках проекта / участниках:
   - **Владелец/Руководитель** — назначь себя (это будет "Менеджер проекта")
   - **Модератор** или просто **Участник** — добавь себя же (или тестового пользователя как "Прораб")

> **Важно:** В стандартном Битрикс24 "Проект" (рабочая группа) имеет роли: Владелец, Модератор, Участник. Для нашей задачи нужны кастомные поля-привязки. Если твоя версия Битрикс24 не позволяет создать поле типа "Привязка к сотруднику" на группе — используй обычные строковые поля и вписывай имена вручную.

### Альтернатива (если нет полей привязки к сотруднику):

Создай дополнительные строковые поля:

| Название | Тип | Код |
|----------|-----|-----|
| Прораб | Строка | `UF_FOREMAN_NAME` |
| Менеджер проекта | Строка | `UF_PM_NAME` |
| Сметчик | Строка | `UF_ESTIMATOR_NAME` |
| Продавец | Строка | `UF_SALES_NAME` |

Заполни для теста:
- Прораб: `Alexander Borohov`
- Менеджер проекта: `Alexander Borohov`

---

## Шаг 4: Создать Webhook (исходящий)

### 4.1: Добавить исходящий webhook для проекта

1. Перейди: **Разработчикам** (левое меню, внизу) или введи в URL: `https://ТВОЙ_ДОМЕН.bitrix24.ru/devops/section/standard/`
2. → **Другое** → **Исходящий вебхук** → **Добавить**
3. Настрой:
   - Название: `Sync Project to Bot`
   - URL обработчика: `http://localhost:3000` (для теста) или URL Cloud Function после деплоя
   - Тип события: **ONSONETGROUPUPDATE** (обновление группы/проекта)
   
   > Если нет такого события — создай через **Вебхук REST** (входящий) + скрипт. См. Шаг 5.

### 4.2: Добавить исходящий webhook для сотрудника

1. Аналогично: **Исходящий вебхук** → **Добавить**
   - Название: `Sync Worker to Bot`  
   - URL обработчика: `http://localhost:3000`
   - Тип события: **ONUSERUPDATE** (обновление пользователя)

---

## Шаг 5: Альтернатива — Бизнес-процесс + HTTP запрос

Если исходящие вебхуки не подходят (например, нет нужного события), используй **Бизнес-процесс**:

### Для проекта:

1. Перейди: **CRM** → **Настройки** → **Автоматизация** → **Бизнес-процессы** (или **Задачи** → **Роботы**)
2. Создай бизнес-процесс для сущности "Рабочая группа"
3. Добавь действие: **Запрос по URL** (или "Webhook")
   - URL: `http://localhost:3000` (для теста)
   - Метод: POST
   - Тело:
```json
{
  "secret": "foremen-apps-script-secret-2024",
  "action": "upsert_project",
  "project_name": "{=Document:NAME}",
  "google_drive_url": "{=Document:UF_GOOGLE_DRIVE_URL}",
  "google_sheets_url": "{=Document:UF_GOOGLE_SHEETS_URL}",
  "workers": [
    {"worker_name": "{=Document:UF_FOREMAN_NAME}", "role_in_project": "foreman"},
    {"worker_name": "{=Document:UF_PM_NAME}", "role_in_project": "pm"}
  ]
}
```
4. После webhook'а — добавь действие **"Изменить документ"**:
   - `UF_GOOGLE_DRIVE_URL` = `{=Variable:webhook_response.google_drive_url}` (если было пустое)
   - `UF_GOOGLE_SHEETS_URL` = `{=Variable:webhook_response.google_sheets_url}` (если было пустое)
   - `UF_SENT_TO_REGISTRY` = Да

### Для сотрудника:

Аналогично, но сущность "Пользователь" и тело:
```json
{
  "secret": "foremen-apps-script-secret-2024",
  "action": "upsert_worker",
  "bitrix_user_id": "{=Document:ID}",
  "worker_name": "{=Document:NAME} {=Document:LAST_NAME}",
  "role": "{=Document:UF_BOT_ROLE}",
  "telegram_id": "{=Document:UF_TELEGRAM_ID}"
}
```

---

## Шаг 6: Тест без роботов (curl)

Пока не настроены роботы — можно протестировать webhook вручную:

### Запусти webhook-сервер локально:
```bash
cd телеграм-бот/telegram-bot
bun run dev:webhook
```

### Тест создания проекта (без URL'ов — создаст папки автоматически):
```bash
curl -X POST http://localhost:3000 \
  -H "Content-Type: application/json" \
  -d '{
    "secret": "foremen-apps-script-secret-2024",
    "action": "upsert_project",
    "project_name": "Квартира Иванова",
    "workers": [
      {"worker_name": "Alexander Borohov", "role_in_project": "pm"},
      {"worker_name": "Alexander Borohov", "role_in_project": "foreman"}
    ]
  }'
```

**Ожидаемый ответ:**
```json
{
  "status": "ok",
  "message": "project created with new resources",
  "google_drive_url": "https://drive.google.com/drive/folders/...",
  "google_sheets_url": "https://docs.google.com/spreadsheets/d/.../edit"
}
```

### Тест синхронизации работника:
```bash
curl -X POST http://localhost:3000 \
  -H "Content-Type: application/json" \
  -d '{
    "secret": "foremen-apps-script-secret-2024",
    "action": "upsert_worker",
    "bitrix_user_id": "1",
    "worker_name": "Alexander Borohov",
    "role": "admin",
    "telegram_id": 571744833
  }'
```

### Тест обновления проекта (с URL'ами — просто обновит реестр):
```bash
curl -X POST http://localhost:3000 \
  -H "Content-Type: application/json" \
  -d '{
    "secret": "foremen-apps-script-secret-2024",
    "action": "upsert_project",
    "project_name": "Квартира Иванова",
    "google_drive_url": "https://drive.google.com/drive/folders/XXXXX",
    "google_sheets_url": "https://docs.google.com/spreadsheets/d/XXXXX/edit",
    "workers": [
      {"worker_name": "Alexander Borohov", "role_in_project": "pm"},
      {"worker_name": "Дима Петров", "role_in_project": "foreman"}
    ]
  }'
```

---

## Шаг 7: Проверка результата

После curl'ов проверь:

1. **Реестр проектов** → https://docs.google.com/spreadsheets/d/1YjSN9w_OpCFKNElj0Nrhvva2JgWlMtmSHbiVDFuSKaQ/edit
   - Должна появиться строка "Квартира Иванова" с URL'ами

2. **Реестр работников** → https://docs.google.com/spreadsheets/d/10Y_f33aXzBhCz55y-l-GeX5raYeyTZb9gGiQt6azchs/edit
   - Лист "workers": должна появиться/обновиться строка с Alexander Borohov
   - Лист "project_access": привязка Квартира Иванова → Alexander Borohov

3. **Shared Drive** → https://drive.google.com/drive/u/0/folders/0AHkU6n74cG-CUk9PVA
   - В папке "Проекты" должна появиться папка "Квартира Иванова — Чеки" со спредшитом "Квартира Иванова — Смета"

4. **Telegram бот** → отправь /start → должен показать "Квартира Иванова" в списке проектов

---

## Минимальный чеклист

- [ ] Поле "Telegram ID" в карточке сотрудника
- [ ] Поле "Роль для бота" в карточке сотрудника  
- [ ] Проект с полями: UF_GOOGLE_DRIVE_URL, UF_GOOGLE_SHEETS_URL, UF_SENT_TO_REGISTRY
- [ ] Поля имён работников на проекте (или привязки к сотрудникам)
- [ ] Webhook-сервер запущен (`bun run dev:webhook`)
- [ ] Curl-тест пройден (проект создался в таблицах и на Drive)
- [ ] Telegram бот показывает проект после /start
