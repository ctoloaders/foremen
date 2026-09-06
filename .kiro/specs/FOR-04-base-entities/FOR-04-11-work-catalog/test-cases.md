# Тест-кейсы: FOR-04-11 Work Catalog (каталог работ, сущность WorkItem)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерный экран**: каталог работ раздаётся через REST `/api/work-items` **и** через админ-страницу CRUD по маршруту `/catalog/works` (страница + её роут поставляются в этой спеке; группа меню «Каталог» — отдельно в FOR-04-15). В отличие от плоских справочников, `WorkItem` содержит ДВА внешних ключа: `workCategory` (→ WorkCategory) и `unit` (→ MeasurementUnit). Набор тест-кейсов включает **оба типа**:

- **Часть A. API-тесты** — против работающего в Docker приложения. Каждый шаг — HTTP-запрос к `/api/work-items`.
- **Часть B. Браузерные (UI) сценарии** — против фронтенда `http://localhost:3000`, browser automation / headless, под ролью ADMIN.

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание → факт → статус). **Скриншоты не требуются.**

Покрываемые требования: R1 (сущность/таблица с FK), R2 (CRUD с FK-полями, i18n, reference-фильтр, metadata), R3 (ABAC-ресурс и гранты, **MANAGER CRU без DELETE, FINANCIER READ, CLIENT none**), R4 (нет дефолтного сида позиций — сидятся в FOR-04-12), R6 (админ-страница CRUD с reference-колонками), R7 (UI-тесты).

## Запуск окружения

1. В корне репозитория: `docker compose up` — `postgres` (:5432), `liquibase`, `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth — под `/api/auth`.
3. Первый ADMIN — через `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном.
5. **Предзависимость по данным:** для создания WorkItem нужны существующие `WorkCategory` (засижены в FOR-04-06, 13 категорий) и `MeasurementUnit` (засижены в FOR-04-02). Получить их id через `GET /api/work-categories?size=1000` и `GET /api/measurement-units?size=1000`.

## Стратегия повторяемости

**Комбинированная — генератор + teardown:**

- **Генератор:** на прогон формируется `run-id`; создаваемые позиции получают уникальные `nameRU`/`namePL` вида `wi{run-id}` (у WorkItem нет `code`; уникальность не требуется на уровне БД, но удобна для поиска).
- **Teardown:** удалить созданные позиции (`DELETE /api/work-items/{id}`) и разлогинить токены в конце набора.
- **Seed-данные (категории/единицы) и системные ресурсы/роли НЕ изменяются** — только читаются для получения FK-id.
- i18n-проверки — `Accept-Language: ru`/`pl`; без заголовка → PL (fallback).

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Кейсы non-ADMIN ролей документируются ожидаемыми результатами и покрываются seed-интеграционным тестом (`WorkCatalogResourceSeedIntegrationTest`). Матрица `WORK_CATALOG`: **ADMIN — CRUD; MANAGER — CREATE/READ/UPDATE (без DELETE); FOREMAN/WORKER/FINANCIER — READ; CLIENT — без гранта**.

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Setup (токен ADMIN + FK-id)

### TC-SETUP-01 — Логин ADMIN
`POST /api/auth/login` `{email, password}` → **200** + `accessToken`.

### TC-SETUP-02 — Получить FK-id категории и единицы
1. `GET /api/work-categories?size=1000` (Bearer ADMIN) → **200**; сохранить любой `workCategoryId` (например категории с `code` из сида FOR-04-06).
2. `GET /api/measurement-units?size=1000` (Bearer ADMIN) → **200**; сохранить любой `unitId`.

---

## Фича 1. CRUD позиций каталога (с FK)

Покрывает R2.2, R2.3, R2.5. **Повторяемость:** генератор `nameRU/namePL = wi{run-id}` + teardown.

### TC-CRUD-01 — Создание (POST) → 200
`POST /api/work-items` `{"workCategoryId": <id>, "unitId": <id>, "nameRU": "wi{run-id} рус", "namePL": "wi{run-id} pl", "active": true}` → **200** + `id`, `workCategoryId`, `unitId`, `nameRU`, `namePL`, `active`.

### TC-CRUD-02 — В списке присутствует, с именами связанных сущностей (GET list)
`GET /api/work-items?size=100` → **200**; элемент с созданным `id` содержит `workCategoryId`/`unitId` И локализованные `workCategoryName`/`unitName` (имена связанных сущностей для отображения).

### TC-CRUD-03 — Чтение расширенной модели (GET /{id})
`GET /api/work-items/{id}` → **200** + `id`, `workCategoryId`, `unitId`, `nameRU`, `namePL`, `active`.

### TC-CRUD-04 — Обновление FK и имён (PUT /{id})
`PUT /{id}` `{"workCategoryId": <другой id>, "unitId": <другой id>, "nameRU": "рус обновл", "namePL": "pl updated", "active": false}` → **200**; `GET /{id}` → FK и имена обновлены.

### TC-CRUD-05 — Удаление (DELETE /{id}) → 200, затем 404
`DELETE /{id}` → **200**; `GET /{id}` → **404**.

### TC-CRUD-06 — Валидация обязательных полей → 400
`POST` без `workCategoryId` → **400** (`@NotNull`); без `unitId` → **400**; с пустым `nameRU`/`namePL` → **400**.

### TC-CRUD-07 — Несуществующий FK → ошибка
`POST` с `workCategoryId` заведомо несуществующим (например 999999999) → ошибка целостности (4xx/5xx — нарушение FK-constraint), позиция не создаётся.

**Teardown набора:** удалить созданные позиции (кроме удалённой в TC-CRUD-05).

---

## Фича 2. i18n имени (PL fallback)

Покрывает R2.4. **Повторяемость:** генератор `wi{run-id}i` + teardown.

### TC-I18N-01 — name == nameRU при Accept-Language: ru
Создать `{workCategoryId, unitId, nameRU: "имя-рус", namePL: "nazwa-pl"}`; `GET ?size=100` c `ru` → элемент имеет `name == "имя-рус"`.

### TC-I18N-02 — name == namePL при Accept-Language: pl
`GET` c `pl` → `name == "nazwa-pl"`.

### TC-I18N-03 — PL fallback без Accept-Language
`GET` без заголовка → `name == "nazwa-pl"`.

**Teardown:** `DELETE` позиции.

---

## Фича 3. Reference-фильтр и metadata (FK)

Покрывает R2.6, R2.7. **Повторяемость:** генератор + teardown.

### TC-REF-01 — metadata эмитит reference-дескрипторы для workCategory и unit
`GET /api/work-items/metadata` (Bearer ADMIN) → **200**; в полях присутствуют `workCategory` и `unit` с reference-дескриптором (`idPath` = `workCategory.id`/`unit.id`, `targetResource`, `optionsPath` = `/api/work-categories`/`/api/measurement-units`, `labelField`).

### TC-REF-02 — Фильтр по workCategory.id сужает список
Создать две позиции с разными `workCategoryId` (catA, catB). `GET /api/work-items?size=100&query=workCategory.id==<catA>` → **200**; в результате только позиции с категорией catA, позиции catB отсутствуют.

### TC-REF-03 — Фильтр по нескольким категориям (~in~)
`GET /api/work-items?size=100&query=workCategory.id~in~<catA>,<catB>` → **200**; присутствуют позиции обеих категорий.

**Teardown:** удалить созданные позиции.

---

## Фича 4. ABAC-доступ (WORK_CATALOG)

Покрывает R3.1, R3.3. **Повторяемость:** генератор + teardown.

### TC-ABAC-01 — 401 без токена
`GET /api/work-items` без `Authorization` → **401**; `POST` без токена → **401**.

### TC-ABAC-02 — ADMIN полный CRUD
POST/GET/PUT/DELETE (Bearer ADMIN) → **200** на всех.

### TC-ABAC-03 — MANAGER: CREATE/READ/UPDATE разрешены, DELETE запрещён (403)
*Покрывается seed-тестом.* GET → **200**; POST → **200**; PUT → **200**; **DELETE → 403** (у MANAGER нет DELETE на WORK_CATALOG).

### TC-ABAC-04 — FOREMAN/WORKER/FINANCIER: только READ (запись 403)
*Покрывается seed-тестом.* GET → **200**; POST/PUT/DELETE → **403**.

### TC-ABAC-05 — CLIENT без гранта: 403 на всё
*Покрывается seed-тестом.* GET (Bearer CLIENT) → **403**.

**Teardown:** удалить созданные позиции; разлогинить токены.

---

## Фича 5. Отсутствие дефолтного сида позиций

Покрывает R4.1. **Повторяемость:** только чтение.

### TC-SEED-01 — Ресурс WORK_CATALOG засижен, дефолтных позиций спека не добавляет
1. `GET /api/resources` (Bearer ADMIN) → присутствует `WORK_CATALOG`.
2. Дефолтные строки WorkItem в этой спеке НЕ сидятся (позиции появляются после FOR-04-12 из Excel-прейскуранта). На чистой БД после миграций FOR-04-11 `GET /api/work-items?size=1` возвращает пустой список (если FOR-04-12 ещё не применён).

---

# Часть B. Браузерные (UI) сценарии

Против `http://localhost:3000` (browser automation / headless). Результат — MD-репорт с таблицами, **без скриншотов**. Покрывают R6, R7.

## Общий Setup для UI

### TC-UI-SETUP-01 — Вход под ADMIN
Открыть `http://localhost:3000`, ввести email/пароль ADMIN, дождаться редиректа в панель.

## Фича UI-1. Страница «Каталог работ» (/catalog/works)

Покрывает R6.1–R6.7, R7.1. **Повторяемость:** генератор `wi{run-id}` + teardown (удаление через UI-диалог).

### TC-UI-01 — Открытие и рендер таблицы
Перейти на `/catalog/works`; заголовок (`workCatalog.pageTitle`), колонки `name`/`workCategory`/`unit`/`active`; колонки `workCategory`/`unit` отображают локализованные имена связанных сущностей; бейдж активности локализован.

### TC-UI-02 — Reference-фильтр по категории и единице
В колонке `workCategory` открыть reference-фильтр → выпадающий список подтягивает категории из `/api/work-categories`; выбрать категорию → список сужается до позиций этой категории (серверный фильтр `workCategory.id==...`). Аналогично для `unit`.

### TC-UI-03 — Создание через форму-sheet (селекты FK)
Кнопка «Создать» видна; форма-sheet содержит селект «Категория» (опции из `/api/work-categories`), селект «Единица» (опции из `/api/measurement-units`), поля `nameRU`/`namePL`, `active`. Заполнить, сохранить; тост успеха (`workCatalog.toast.createSuccess`); строка появилась с выбранными категорией/единицей.

### TC-UI-04 — Валидация формы
Не выбрать категорию/единицу или оставить пустыми имена → инлайновые локализованные ошибки (`workCatalog.validation.*`), сабмит заблокирован.

### TC-UI-05 — Редактирование
Открыть форму строки; предзаполнены категория/единица/имена/active; сменить категорию и единицу; сохранить; тост; строка обновлена.

### TC-UI-06 — Удаление через диалог подтверждения (ADMIN)
«Удалить» → диалог (`workCatalog.delete.*`) → подтвердить → тост успеха → строка исчезла.

### TC-UI-07 — Пермишн-гейтинг MANAGER (нет DELETE)
*Покрывается `WorkCatalogList.test.tsx`, если роль недоступна.* MANAGER видит список, кнопки «Создать»/«Редактировать» доступны, но действие «Удалить» скрыто (нет DELETE на WORK_CATALOG).

### TC-UI-08 — Пермишн-гейтинг READ-роль (FOREMAN/WORKER/FINANCIER)
READ-роль видит список, контролы Создать/Редактировать/Удалить скрыты.

### TC-UI-09 — Пермишн-гейтинг CLIENT (нет доступа)
Интерим-гард `/catalog/works` (`{ resource: 'WORK_CATALOG', operation: 'READ' }`) блокирует CLIENT → редирект на `/403`.

### TC-UI-10 — i18n PL/RU
Переключение локали меняет заголовок, колонки, подписи формы, бейджи; локализованные имена связанных сущностей меняются по локали; сырые ключи `workCatalog.*` не отображаются (паритет `pl.json`/`ru.json`).

**Teardown набора (UI):** удалить созданные позиции через UI-диалог. Кейсы «только чтение» teardown не требуют.

---

## Регрессия

**Повторяемость:** только чтение.

### TC-REG-01 — ABAC-гард действует
`GET /api/work-items` без токена → **401**.

### TC-REG-02 — Смежные ресурсы не затронуты
`GET /api/resources` → присутствует `WORK_CATALOG` наряду с прежними; категории/единицы (WORK_CATEGORIES/MEASUREMENT_UNITS) на месте.

### TC-REG-03 — Reference-инфраструктура на месте
`GET /api/work-items/metadata` → reference-дескрипторы для workCategory/unit присутствуют (FOR-04-01 не регрессировал).

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SETUP-01 | 1 | POST /api/auth/login (ADMIN) | 200 + accessToken |  |  |
| 2 | TC-SETUP-02 | 1-2 | GET work-categories/measurement-units | 200 + FK-id |  |  |
| 3 | TC-CRUD-01 | 1 | POST /api/work-items (FK ids) | 200 + id, FK ids |  |  |
| 4 | TC-CRUD-02 | 1 | GET ?size=100 | содержит FK ids + referenced names |  |  |
| 5 | TC-CRUD-03 | 1 | GET /{id} | 200 + workCategoryId/unitId/nameRU/namePL |  |  |
| 6 | TC-CRUD-04 | 1 | PUT /{id} (смена FK) | 200, FK обновлены |  |  |
| 7 | TC-CRUD-05 | 2 | GET /{id} после DELETE | 404 |  |  |
| 8 | TC-CRUD-06 | 1 | POST без workCategoryId | 400 |  |  |
| 9 | TC-CRUD-07 | 1 | POST с несуществующим FK | 4xx/5xx (нарушение FK) |  |  |
| 10 | TC-I18N-01 | 2 | GET (Accept-Language: ru) | name == nameRU |  |  |
| 11 | TC-I18N-02 | 2 | GET (Accept-Language: pl) | name == namePL |  |  |
| 12 | TC-I18N-03 | 2 | GET (без Accept-Language) | name == namePL |  |  |
| 13 | TC-REF-01 | 1 | GET /api/work-items/metadata | reference для workCategory/unit |  |  |
| 14 | TC-REF-02 | 1 | GET ?query=workCategory.id==catA | только catA |  |  |
| 15 | TC-REF-03 | 1 | GET ?query=workCategory.id~in~catA,catB | обе категории |  |  |
| 16 | TC-ABAC-01 | 1 | GET без токена | 401 |  |  |
| 17 | TC-ABAC-02 | 1-4 | ADMIN CRUD | 200 на всех |  |  |
| 18 | TC-ABAC-03 | 4 | DELETE (MANAGER) | 403 (нет DELETE) |  |  |
| 19 | TC-ABAC-04 | 2 | POST (FOREMAN/WORKER/FINANCIER) | 403 |  |  |
| 20 | TC-ABAC-05 | 1 | GET (CLIENT) | 403 |  |  |
| 21 | TC-SEED-01 | 1 | GET /api/resources | есть WORK_CATALOG |  |  |
| 22 | TC-UI-SETUP-01 | 2 | Логин ADMIN в браузере | редирект в панель |  |  |
| 23 | TC-UI-01 | 1 | Открытие /catalog/works | колонки name/category/unit/active |  |  |
| 24 | TC-UI-02 | 1 | Reference-фильтр по категории | список сужается серверно |  |  |
| 25 | TC-UI-03 | 1 | Создать (селекты FK) | тост успеха + строка |  |  |
| 26 | TC-UI-04 | 1 | Сабмит без категории/единицы | инлайновая ошибка, сабмит заблокирован |  |  |
| 27 | TC-UI-05 | 1 | Редактирование (смена FK) | тост, строка обновлена |  |  |
| 28 | TC-UI-06 | 1 | Удаление (ADMIN) | тост успеха, строка исчезла |  |  |
| 29 | TC-UI-07 | 1 | MANAGER — нет «Удалить» | Удалить скрыто |  |  |
| 30 | TC-UI-08 | 1 | READ-роль | контролы скрыты |  |  |
| 31 | TC-UI-09 | 1 | CLIENT на /catalog/works | редирект на /403 |  |  |
| 32 | TC-UI-10 | 1 | Переключение локали PL/RU | надписи + имена связанных сущностей локализованы |  |  |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`.

Стратегия повторяемости:
- Часть A (API): `генератор (nameRU/namePL = wi{run-id}) + teardown (DELETE созданных позиций)`; кейсы «только чтение» (metadata/сид/ABAC/регресс) teardown не требуют.
- Часть B (UI): `генератор (wi{run-id}) + teardown (удаление через UI-диалог)`; кейсы «только чтение» teardown не требуют.
