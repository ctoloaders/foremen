# Тест-кейсы: FOR-04-12 Work Prices (цены работ, сущность WorkPrice + сид из Excel)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерный экран**: цены работ раздаются через REST `/api/work-prices` **и** через админ-страницу CRUD по маршруту `/catalog/prices` (страница + её роут поставляются в этой спеке; группа меню «Каталог» — отдельно в FOR-04-15). `WorkPrice` содержит ДВА внешних ключа: `workItem` (→ WorkItem) и `currency` (→ Currency), плюс `netPrice`, `validFrom`, `validTo` (null = текущая цена). Набор тест-кейсов включает **оба типа**:

- **Часть A. API-тесты** — против работающего в Docker приложения. Каждый шаг — HTTP-запрос к `/api/work-prices`.
- **Часть B. Браузерные (UI) сценарии** — против фронтенда `http://localhost:3000`, browser automation / headless, под ролью ADMIN.

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание → факт → статус). **Скриншоты не требуются.**

Покрываемые требования: R1 (сущность/таблица с FK + даты), R2 (CRUD с FK, netPrice, validFrom/validTo, derived `current`, reference-фильтр, metadata), R3 (ABAC-ресурс и гранты, **MANAGER CRU без DELETE, FINANCIER READ, CLIENT none**), R4 (сид WorkItem + текущих WorkPrice из Excel-прейскуранта), R6 (админ-страница CRUD с reference-колонками + даты), R7 (UI-тесты).

## Запуск окружения

1. В корне репозитория: `docker compose up` — `postgres` (:5432), `liquibase`, `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth — под `/api/auth`.
3. Первый ADMIN — через `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
4. Дождаться готовности `backend` и завершения миграций (сид WorkItem/WorkPrice из CSV) перед прогоном.
5. **Предзависимость по данным:** для создания WorkPrice нужны существующие `WorkItem` (засижены этой спекой из CSV) и `Currency` (PLN засижена в FOR-04-03). Получить их id через `GET /api/work-items?size=1000` и `GET /api/currencies?size=1000`.

## Стратегия повторяемости

**Комбинированная — генератор + teardown:**

- **Генератор:** на прогон формируется `run-id`; создаваемые цены привязываются к существующему WorkItem, но помечаются уникальной датой `validFrom` (например `2099-01-{run-id-суффикс}`) для идентификации.
- **Teardown:** удалить созданные цены (`DELETE /api/work-prices/{id}`) и разлогинить токены в конце набора.
- **Seed-данные (WorkItem/WorkPrice из CSV, валюты) и системные ресурсы/роли НЕ изменяются** — только читаются.
- Проверка derived `current`: цена с `validTo == null` → `current == true`; с непустым `validTo` → `current == false`.

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Кейсы non-ADMIN ролей документируются ожидаемыми результатами и покрываются seed-интеграционным тестом (`WorkPricesResourceSeedIntegrationTest`). Матрица `WORK_PRICES`: **ADMIN — CRUD; MANAGER — CREATE/READ/UPDATE (без DELETE); FOREMAN/WORKER/FINANCIER — READ; CLIENT — без гранта**.

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Setup (токен ADMIN + FK-id)

### TC-SETUP-01 — Логин ADMIN
`POST /api/auth/login` `{email, password}` → **200** + `accessToken`.

### TC-SETUP-02 — Получить FK-id позиции и валюты
1. `GET /api/work-items?size=1000` (Bearer ADMIN) → **200**; сохранить любой `workItemId`.
2. `GET /api/currencies?size=1000` (Bearer ADMIN) → **200**; сохранить `currencyId` для PLN.

---

## Фича 1. CRUD цен (с FK и датами)

Покрывает R2.2, R2.3, R2.4. **Повторяемость:** генератор (уникальная `validFrom`) + teardown.

### TC-CRUD-01 — Создание текущей цены (POST, validTo null) → 200, current=true
`POST /api/work-prices` `{"workItemId": <id>, "currencyId": <pln>, "netPrice": 123.45, "validFrom": "2099-01-01"}` (без validTo) → **200** + `id`, FK ids, `netPrice`, `validFrom`, `validTo == null`, `current == true`.

### TC-CRUD-02 — В списке присутствует с именами связанных сущностей (GET list)
`GET /api/work-prices?size=100` → **200**; элемент с созданным `id` содержит `workItemId`/`currencyId` И `workItemName` (локализованное имя позиции) + `currencyCode == "PLN"` + `current`.

### TC-CRUD-03 — Чтение расширенной модели (GET /{id})
`GET /api/work-prices/{id}` → **200** + `id`, `workItemId`, `currencyId`, `netPrice`, `validFrom`, `validTo`.

### TC-CRUD-04 — Создание исторической цены (validTo задан) → current=false
`POST` `{"workItemId": <id>, "currencyId": <pln>, "netPrice": 99.00, "validFrom": "2099-02-01", "validTo": "2099-03-01"}` → **200**; в списке этот элемент имеет `current == false`.

### TC-CRUD-05 — Обновление цены/дат (PUT /{id})
`PUT /{id}` `{"workItemId": <id>, "currencyId": <pln>, "netPrice": 150.00, "validFrom": "2099-01-01", "validTo": null}` → **200**; поля обновлены, `current == true`.

### TC-CRUD-06 — Удаление (DELETE /{id}) → 200, затем 404
`DELETE /{id}` → **200**; `GET /{id}` → **404**.

### TC-CRUD-07 — Валидация обязательных полей → 400
`POST` без `workItemId` → **400**; без `currencyId` → **400**; без `netPrice` → **400**; `netPrice` ≤ 0 → **400** (`@Positive`); без `validFrom` → **400**.

### TC-CRUD-08 — Несуществующий FK → ошибка целостности
`POST` с `workItemId` заведомо несуществующим → 4xx/5xx (нарушение FK), цена не создаётся.

**Teardown набора:** удалить созданные цены (кроме удалённой в TC-CRUD-06).

---

## Фича 2. Reference-фильтр и metadata (FK)

Покрывает R2.5, R2.6. **Повторяемость:** только чтение (по сид-данным).

### TC-REF-01 — metadata эмитит reference-дескрипторы для workItem и currency
`GET /api/work-prices/metadata` (Bearer ADMIN) → **200**; поля `workItem` (`idPath=workItem.id`, `optionsPath=/api/work-items`) и `currency` (`idPath=currency.id`, `optionsPath=/api/currencies`) присутствуют.

### TC-REF-02 — Фильтр по workItem.id сужает список
`GET /api/work-prices?size=100&query=workItem.id==<id>` → **200**; только цены выбранной позиции.

### TC-REF-03 — Фильтр по currency.id
`GET /api/work-prices?size=100&query=currency.id==<pln>` → **200**; только цены в PLN.

---

## Фича 3. ABAC-доступ (WORK_PRICES)

Покрывает R3.1, R3.3. **Повторяемость:** генератор + teardown.

### TC-ABAC-01 — 401 без токена
`GET /api/work-prices` без `Authorization` → **401**; `POST` без токена → **401**.

### TC-ABAC-02 — ADMIN полный CRUD
POST/GET/PUT/DELETE (Bearer ADMIN) → **200** на всех.

### TC-ABAC-03 — MANAGER: CREATE/READ/UPDATE разрешены, DELETE запрещён (403)
*Покрывается seed-тестом.* GET → **200**; POST → **200**; PUT → **200**; **DELETE → 403**.

### TC-ABAC-04 — FOREMAN/WORKER/FINANCIER: только READ (запись 403)
*Покрывается seed-тестом.* GET → **200**; POST/PUT/DELETE → **403**.

### TC-ABAC-05 — CLIENT без гранта: 403
*Покрывается seed-тестом.* GET (Bearer CLIENT) → **403**.

**Teardown:** удалить созданные цены; разлогинить токены.

---

## Фича 4. Сид из Excel-прейскуранта (WorkItem + текущие WorkPrice)

Покрывает R4.1, R4.3, R4.6. **Повторяемость:** только чтение — teardown не требуется.

### TC-SEED-01 — Позиции каталога засижены из CSV
`GET /api/work-items?size=1000` → **200**; список НЕ пуст (позиции из `Oferta.csv`); присутствуют известные позиции (например `namePL` "Rozbiórki ściany murowanej" в категории PLUMBING_ROUGH/PRELIMINARY соответственно) с корректной категорией и единицей.

### TC-SEED-02 — Текущие цены засижены (PLN, validTo null)
`GET /api/work-prices?size=1000` → **200**; список НЕ пуст; каждая сид-цена имеет `currencyCode == "PLN"`, `validTo == null` (`current == true`), `netPrice > 0`, соответствующий CENA из CSV (например `1,02 Rozbiórki ściany murowanej` → `netPrice == 163.90`).

### TC-SEED-03 — Ресурс WORK_PRICES засижен
`GET /api/resources` → присутствует `WORK_PRICES`.

---

# Часть B. Браузерные (UI) сценарии

Против `http://localhost:3000` (browser automation / headless). Результат — MD-репорт с таблицами, **без скриншотов**. Покрывают R6, R7.

## Общий Setup для UI

### TC-UI-SETUP-01 — Вход под ADMIN
Открыть `http://localhost:3000`, ввести email/пароль ADMIN, дождаться редиректа в панель.

## Фича UI-1. Страница «Цены работ» (/catalog/prices)

Покрывает R6.1–R6.7, R7.1. **Повторяемость:** генератор (уникальная validFrom) + teardown (удаление через UI-диалог).

### TC-UI-01 — Открытие и рендер таблицы
Перейти на `/catalog/prices`; заголовок (`workPrices.pageTitle`), колонки `workItem`/`currency`/`netPrice`/`validFrom`/`validTo`/`current`; reference-колонки отображают локализованное имя позиции и код валюты; бейдж `current` (Текущая/Историческая) локализован; присутствуют сид-цены.

### TC-UI-02 — Reference-фильтр по позиции и валюте
Reference-фильтр колонки `workItem` подтягивает позиции из `/api/work-items`; выбор позиции сужает список серверно (`workItem.id==...`). Аналогично `currency` (`/api/currencies`).

### TC-UI-03 — Создание текущей цены через форму-sheet
Кнопка «Создать» видна; форма содержит селект позиции (опции `/api/work-items`), селект валюты (`/api/currencies`), поле `netPrice`, дату `validFrom`, опциональную дату `validTo`. Заполнить без `validTo`; сохранить; тост успеха (`workPrices.toast.createSuccess`); строка появилась с бейджем «Текущая».

### TC-UI-04 — Валидация формы
Не выбрать позицию/валюту, оставить пустой `netPrice`/`validFrom`, или `netPrice` ≤ 0 → инлайновые локализованные ошибки (`workPrices.validation.*`), сабмит заблокирован.

### TC-UI-05 — Редактирование
Открыть форму строки; предзаполнены позиция/валюта/цена/даты; изменить `netPrice` и `validTo`; сохранить; тост; строка обновлена (бейдж current меняется в зависимости от validTo).

### TC-UI-06 — Удаление через диалог (ADMIN)
«Удалить» → диалог (`workPrices.delete.*`) → подтвердить → тост успеха → строка исчезла.

### TC-UI-07 — Пермишн-гейтинг MANAGER (нет DELETE)
*Покрывается `WorkPricesList.test.tsx`, если роль недоступна.* MANAGER видит список, «Создать»/«Редактировать» доступны, «Удалить» скрыто.

### TC-UI-08 — Пермишн-гейтинг READ-роль (FOREMAN/WORKER/FINANCIER)
READ-роль видит список, контролы Создать/Редактировать/Удалить скрыты.

### TC-UI-09 — Пермишн-гейтинг CLIENT (нет доступа)
Интерим-гард `/catalog/prices` (`{ resource: 'WORK_PRICES', operation: 'READ' }`) блокирует CLIENT → редирект на `/403`.

### TC-UI-10 — i18n PL/RU
Переключение локали меняет заголовок, колонки, подписи формы, бейджи; локализованное имя позиции меняется по локали; сырые ключи `workPrices.*` не отображаются (паритет `pl.json`/`ru.json`).

**Teardown набора (UI):** удалить созданные цены через UI-диалог. Кейсы «только чтение» teardown не требуют.

---

## Регрессия

**Повторяемость:** только чтение.

### TC-REG-01 — ABAC-гард действует
`GET /api/work-prices` без токена → **401**.

### TC-REG-02 — Смежные ресурсы не затронуты
`GET /api/resources` → присутствует `WORK_PRICES` наряду с `WORK_CATALOG`, `CURRENCIES` и прочими; позиции каталога (WORK_CATALOG) на месте.

### TC-REG-03 — Сид идемпотентен
После повторного применения миграций число сид-позиций/цен не растёт (guard sqlCheck), дубликатов нет.

### TC-REG-04 — Reference-инфраструктура на месте
`GET /api/work-prices/metadata` → reference-дескрипторы для workItem/currency присутствуют.

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SETUP-01 | 1 | POST /api/auth/login (ADMIN) | 200 + accessToken |  |  |
| 2 | TC-SETUP-02 | 1-2 | GET work-items/currencies | 200 + FK-id |  |  |
| 3 | TC-CRUD-01 | 1 | POST /api/work-prices (validTo null) | 200 + current=true |  |  |
| 4 | TC-CRUD-02 | 1 | GET ?size=100 | FK ids + workItemName + currencyCode + current |  |  |
| 5 | TC-CRUD-03 | 1 | GET /{id} | 200 + FK ids/netPrice/validFrom/validTo |  |  |
| 6 | TC-CRUD-04 | 1 | POST (validTo задан) | current=false |  |  |
| 7 | TC-CRUD-05 | 1 | PUT /{id} | 200, поля обновлены |  |  |
| 8 | TC-CRUD-06 | 2 | GET /{id} после DELETE | 404 |  |  |
| 9 | TC-CRUD-07 | 1 | POST без netPrice / netPrice≤0 | 400 |  |  |
| 10 | TC-CRUD-08 | 1 | POST с несуществующим FK | 4xx/5xx |  |  |
| 11 | TC-REF-01 | 1 | GET /api/work-prices/metadata | reference для workItem/currency |  |  |
| 12 | TC-REF-02 | 1 | GET ?query=workItem.id==id | только эта позиция |  |  |
| 13 | TC-REF-03 | 1 | GET ?query=currency.id==pln | только PLN |  |  |
| 14 | TC-ABAC-01 | 1 | GET без токена | 401 |  |  |
| 15 | TC-ABAC-02 | 1-4 | ADMIN CRUD | 200 на всех |  |  |
| 16 | TC-ABAC-03 | 4 | DELETE (MANAGER) | 403 (нет DELETE) |  |  |
| 17 | TC-ABAC-04 | 2 | POST (FOREMAN/WORKER/FINANCIER) | 403 |  |  |
| 18 | TC-ABAC-05 | 1 | GET (CLIENT) | 403 |  |  |
| 19 | TC-SEED-01 | 1 | GET /api/work-items?size=1000 | позиции из CSV присутствуют |  |  |
| 20 | TC-SEED-02 | 1 | GET /api/work-prices?size=1000 | PLN, validTo null, netPrice>0 |  |  |
| 21 | TC-SEED-03 | 1 | GET /api/resources | есть WORK_PRICES |  |  |
| 22 | TC-UI-SETUP-01 | 2 | Логин ADMIN в браузере | редирект в панель |  |  |
| 23 | TC-UI-01 | 1 | Открытие /catalog/prices | колонки + бейдж current + сид-цены |  |  |
| 24 | TC-UI-02 | 1 | Reference-фильтр по позиции | список сужается серверно |  |  |
| 25 | TC-UI-03 | 1 | Создать текущую цену | тост успеха + строка «Текущая» |  |  |
| 26 | TC-UI-04 | 1 | Сабмит без цены / netPrice≤0 | инлайновая ошибка, сабмит заблокирован |  |  |
| 27 | TC-UI-05 | 1 | Редактирование (validTo) | тост, бейдж current меняется |  |  |
| 28 | TC-UI-06 | 1 | Удаление (ADMIN) | тост успеха, строка исчезла |  |  |
| 29 | TC-UI-07 | 1 | MANAGER — нет «Удалить» | Удалить скрыто |  |  |
| 30 | TC-UI-08 | 1 | READ-роль | контролы скрыты |  |  |
| 31 | TC-UI-09 | 1 | CLIENT на /catalog/prices | редирект на /403 |  |  |
| 32 | TC-UI-10 | 1 | Переключение локали PL/RU | надписи + имя позиции локализованы |  |  |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`.

Стратегия повторяемости:
- Часть A (API): `генератор (уникальная validFrom) + teardown (DELETE созданных цен)`; кейсы «только чтение» (metadata/сид/ABAC/регресс) teardown не требуют.
- Часть B (UI): `генератор (уникальная validFrom) + teardown (удаление через UI-диалог)`; кейсы «только чтение» teardown не требуют.
