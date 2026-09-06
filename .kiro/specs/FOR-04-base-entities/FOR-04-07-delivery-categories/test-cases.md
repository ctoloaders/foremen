# Тест-кейсы: FOR-04-07 Delivery Categories (справочник категорий доставок)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерный экран**: справочник категорий доставок раздаётся через REST `/api/delivery-categories` **и** через админ-страницу CRUD по маршруту `/delivery-categories` (страница + её роут поставляются в этой спеке; группа меню «Справочники» — отдельно в FOR-04-15). Поэтому набор тест-кейсов, согласно стандарту `.kiro/steering/test-cases.md`, включает **оба типа**:

- **Часть A. API-тесты** — против работающего в Docker приложения (не Testcontainers/юнит-слой, а поднятый docker-стек). Каждый шаг — HTTP-запрос к API `/api/delivery-categories` с проверкой статуса, тела и заголовков.
- **Часть B. Браузерные (UI) сценарии** — против фронтенда `http://localhost:3000`, исполняются браузерным движком (browser automation / headless), под ролью ADMIN. Каждый шаг — действие в браузере.

Результат прогона для **обеих частей** — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание → факт → статус). **Скриншоты не требуются.**

Покрываемые требования: R1 (сущность/таблица), R2 (CRUD + i18n + сортировка/фильтр + иммутабельный `code` + валидация), R3 (ABAC-ресурс и гранты, **FINANCIER без гранта**), R4 (сид девяти категорий), R6 (админ-страница CRUD), R7 (UI-тесты).

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`.
3. Auth-эндпоинты — под `/api/auth`.
4. Первый ADMIN создаётся через переменные окружения бекенда: `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
5. Дождаться готовности `backend` и завершения миграций `liquibase` перед запуском кейсов.

## Стратегия повторяемости

**Комбинированная — генератор + teardown:**

- **Генератор:** на каждый прогон формируется `run-id` (timestamp/uuid). Создаваемые категории получают уникальный `code` вида `dc{run-id}`, чтобы не конфликтовать с уникальным индексом `code` и seed-кодами (`bathroom_equipment`, `accessories`, `decor`, `tiles`, `paints`, `lighting`, `flooring`, `doors`, `windows`).
- **Teardown (обязательный шаг в конце каждого набора):** удалить все созданные на прогон категории через `DELETE /api/delivery-categories/{id}` и разлогинить выданные токены (`POST /api/auth/logout`).
- **Seed-данные и системные ресурсы/роли НЕ изменяются.**
- Для i18n-проверок используется заголовок `Accept-Language: ru` / `pl`; отсутствие заголовка эквивалентно PL (fallback).

### Замечание про non-ADMIN роли (ABAC)

Приложение при старте бутстрапит **только ADMIN**. Проверка гранта READ у MANAGER/FOREMAN/WORKER требует активного пользователя нужной роли (вне скоупа быстрого API-прогона):

- Кейсы с ADMIN и «без токена» исполняются напрямую API-прогоном.
- Кейсы для non-ADMIN ролей задокументированы ожидаемыми результатами и помечены как покрываемые seed-интеграционным тестом (`DeliveryCategoriesResourceSeedIntegrationTest`).
- **FINANCIER и CLIENT на `DELIVERY_CATEGORIES` НЕ имеют гранта** (deny-by-default). READ есть только у MANAGER/FOREMAN/WORKER; полный CRUD — у ADMIN.

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Получение токена ADMIN (общий Setup)

### TC-SETUP-01 — Логин ADMIN

**Предусловия:** стек поднят; ADMIN засижен через env-переменные.

**Шаги:**
1. `POST /api/auth/login` `{"email": "<FOREMEN_ADMIN_EMAIL>", "password": "<FOREMEN_ADMIN_PASSWORD>"}` → **200**, в теле `accessToken` (+ `refreshToken`).
2. Сохранить `accessToken` для заголовка `Authorization: Bearer <accessToken>`.

**Ожидаемый результат:** получен валидный `accessToken` ADMIN.

---

## Фича 1. CRUD категорий доставок

Покрывает R2.1, R2.4, R2.5. **Повторяемость:** генератор `code = dc{run-id}` + teardown.

### TC-CRUD-01 — Создание категории (POST) → 200

**Шаги:**
1. `POST /api/delivery-categories` (Bearer ADMIN) `{"code": "dc{run-id}", "nameRU": "тест рус", "namePL": "test pl", "active": true}` → **200**; в теле `id`, `code`, `nameRU`, `namePL`, `active == true`.
2. Сохранить `id`.

**Ожидаемый результат:** категория создана, `id` присвоен.

### TC-CRUD-02 — Категория в списке (GET list)

**Шаги:**
1. `GET /api/delivery-categories?size=100` (Bearer ADMIN) → **200**; в `content` присутствует `code == "dc{run-id}"`.

**Ожидаемый результат:** созданная категория найдена в списке.

### TC-CRUD-03 — Чтение расширенной модели (GET /{id})

**Шаги:**
1. `GET /api/delivery-categories/{id}` (Bearer ADMIN) → **200**; `id`, `code`, `nameRU == "тест рус"`, `namePL == "test pl"`, `active == true`.

**Ожидаемый результат:** возвращена расширенная модель со всеми локалями и `code`.

### TC-CRUD-04 — Обновление без изменения code (PUT /{id})

**Шаги:**
1. `PUT /api/delivery-categories/{id}` `{"nameRU": "рус обновл", "namePL": "pl updated", "active": false}` → **200**; поля обновлены.
2. `GET /api/delivery-categories/{id}` → `code` **не изменился**.
3. (Опционально) PUT с полем `code` → значение игнорируется (иммутабельность, R2.4).

**Ожидаемый результат:** имена/`active` обновлены; `code` неизменен.

### TC-CRUD-05 — Удаление (DELETE /{id}) → 200, затем 404

**Шаги:**
1. `DELETE /api/delivery-categories/{id}` → **200**.
2. `GET /api/delivery-categories/{id}` → **404**.

**Ожидаемый результат:** категория удалена; повторное чтение 404.

### TC-CRUD-06 — Валидация обязательных полей → 400

**Шаги:**
1. `POST` без `code` → **400**.
2. `POST` с пустым `nameRU` → **400**.
3. `POST` без `namePL` → **400**.

**Ожидаемый результат:** каждый запрос с отсутствующим/пустым обязательным полем отклоняется 400.

**Teardown набора:** удалить созданные категории (`DELETE`), кроме удалённой в TC-CRUD-05.

---

## Фича 2. i18n имени (PL fallback)

Покрывает R2.2. **Повторяемость:** генератор `code = dc{run-id}i` + teardown.

### TC-I18N-01 — name == nameRU при Accept-Language: ru
Создать `{code: dc{run-id}i, nameRU: "имя-рус", namePL: "nazwa-pl"}`; `GET ...?size=100` с `Accept-Language: ru` → элемент имеет `name == "имя-рус"`.

### TC-I18N-02 — name == namePL при Accept-Language: pl
`GET` с `Accept-Language: pl` → `name == "nazwa-pl"`.

### TC-I18N-03 — PL fallback без Accept-Language
`GET` без заголовка → `name == "nazwa-pl"`.

**Teardown:** `DELETE` категории `dc{run-id}i`.

---

## Фича 3. Сортировка и фильтрация по локализованному name

Покрывает R2.3. Грамматика фильтра тильдо-обёрнутая (`~ct~`). **Повторяемость:** генератор `code = dc{run-id}s{n}` + teardown.

### TC-SORTFILTER-01 — Сортировка по name резолвится в колонку локали
Создать три категории (`s1: nameRU "ааа", namePL "ccc"`, `s2: "ббб"/"bbb"`, `s3: "ввв"/"aaa"`). `GET ?sort=name,asc` с `ru` → порядок `s1,s2,s3`; с `pl` → `s3,s2,s1`.

### TC-SORTFILTER-02 — Фильтр name~ct~ резолвится в колонку локали
`GET ?query=name~ct~ааа` (ru) → присутствует `s1`; `GET ?query=name~ct~aaa` (pl) → присутствует `s3`. Убедиться, что грамматика `~ct~`, а не `=ct=`.

**Teardown:** удалить `s1,s2,s3`.

---

## Фича 4. ABAC-доступ (DELIVERY_CATEGORIES)

Покрывает R3.1, R3.3. **Повторяемость:** генератор `code = dc{run-id}a` + teardown.

### TC-ABAC-01 — 401 без токена
`GET /api/delivery-categories` без `Authorization` → **401**; `POST` без токена → **401**.

### TC-ABAC-02 — ADMIN полный CRUD
POST/GET/PUT/DELETE (Bearer ADMIN) → **200** на всех.

### TC-ABAC-03 — Роль с READ (MANAGER/FOREMAN/WORKER): GET разрешён, запись 403
*Покрывается seed-тестом, если нет активного пользователя роли.* GET → **200**; POST/PUT/DELETE → **403**.

### TC-ABAC-04 — FINANCIER и CLIENT без гранта: 403 на всё
*Покрывается seed-тестом.* `GET` (Bearer FINANCIER) → **403**; `POST` → **403**; аналогично CLIENT.

**Teardown:** удалить `dc{run-id}a`; разлогинить токены.

---

## Фича 5. Сид справочника (девять категорий)

Покрывает R4.1. **Повторяемость:** только чтение — teardown не требуется.

### TC-SEED-01 — Девять дефолтных кодов присутствуют
`GET /api/delivery-categories?size=1000` → в `content` присутствуют: `bathroom_equipment`, `accessories`, `decor`, `tiles`, `paints`, `lighting`, `flooring`, `doors`, `windows`; каждый `active == true`, непустые `nameRU`/`namePL` (например `tiles` → `Плитка`/`Płytki`).

**Ожидаемый результат:** все девять категорий засижены и активны.

---

# Часть B. Браузерные (UI) сценарии

Сценарии против `http://localhost:3000` (browser automation / headless). Результат — MD-репорт с таблицами, **без скриншотов**. Покрывают R6, R7.

## Общий Setup для UI

### TC-UI-SETUP-01 — Вход под ADMIN
Открыть `http://localhost:3000`, ввести email/пароль ADMIN, дождаться редиректа в панель.

## Фича UI-1. Страница «Категории доставок» (/delivery-categories)

Покрывает R6.1–R6.7, R7.1. **Повторяемость:** генератор `code = dc{run-id}` + teardown (удаление через UI-диалог).

### TC-UI-01 — Открытие и рендер таблицы
Перейти на `/delivery-categories`; проверить заголовок (`deliveryCategories.pageTitle`, не сырой ключ), колонки `code`/`name`/`active`, локализованный бейдж активности, наличие девяти сид-категорий.

### TC-UI-02 — Поиск, сортировка, фильтрация (общий DataTable)
Поиск по имени сокращает список; клик по заголовку `code`/`name` меняет порядок, серверная сортировка (`sort=...` уходит на бэкенд); фильтр по колонке сужает список; снятие восстанавливает.

### TC-UI-03 — Создание через форму-sheet
Кнопка «Создать» видна (ADMIN CREATE); заполнить `code = dc{run-id}`, `nameRU`, `namePL`; сохранить; тост успеха (`deliveryCategories.toast.createSuccess`); строка появилась.

### TC-UI-04 — Валидация формы (пустые обязательные поля)
Пустой `code`/`nameRU`/`namePL` → инлайновые локализованные ошибки (`deliveryCategories.validation.*`), сабмит заблокирован.

### TC-UI-05 — Редактирование (code read-only)
Открыть форму строки `dc{run-id}`; `code` read-only; изменить имена и `active`; сохранить; тост успеха; строка обновлена, `code` неизменен.

### TC-UI-06 — Удаление через диалог подтверждения
Действие «Удалить» → диалог (`deliveryCategories.delete.*`) → подтвердить → тост успеха → строка исчезла.

### TC-UI-07 — Пермишн-гейтинг (READ-роль)
*Покрывается компонентным тестом `DeliveryCategoriesList.test.tsx`, если роль недоступна.* Пользователь READ видит список, но контролы Создать/Редактировать/Удалить скрыты.

### TC-UI-08 — Пермишн-гейтинг (FINANCIER без доступа)
Интерим-гард `/delivery-categories` (`{ resource: 'DELIVERY_CATEGORIES', operation: 'READ' }`) блокирует FINANCIER → редирект на `/403`.

### TC-UI-09 — i18n PL/RU
Переключение локали меняет заголовок, колонки, подписи формы, бейджи; сырые ключи `deliveryCategories.*` не отображаются (паритет `pl.json`/`ru.json`).

**Teardown набора (UI):** удалить созданные категории через UI-диалог (TC-UI-06). Кейсы «только чтение» teardown не требуют.

---

## Регрессия

**Повторяемость:** только чтение.

### TC-REG-01 — Сид-категории на месте после повторного прогона
`GET ?size=1000` → девять кодов присутствуют, дубликатов нет (сид идемпотентен).

### TC-REG-02 — ABAC-гард действует
`GET /api/delivery-categories` без токена → **401**.

### TC-REG-03 — Смежные ресурсы не затронуты; FINANCIER без гранта
`GET /api/resources` → присутствует `DELIVERY_CATEGORIES` наряду с прежними; у FINANCIER нет гранта на `DELIVERY_CATEGORIES`.

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SETUP-01 | 1 | POST /api/auth/login (ADMIN) | 200 + accessToken |  |  |
| 2 | TC-CRUD-01 | 1 | POST /api/delivery-categories | 200 + id, code |  |  |
| 3 | TC-CRUD-02 | 1 | GET ?size=100 | 200, содержит code |  |  |
| 4 | TC-CRUD-03 | 1 | GET /{id} | 200 + nameRU/namePL/code/active |  |  |
| 5 | TC-CRUD-04 | 2 | GET /{id} | 200, code неизменен |  |  |
| 6 | TC-CRUD-05 | 2 | GET /{id} после DELETE | 404 |  |  |
| 7 | TC-CRUD-06 | 1 | POST без code | 400 |  |  |
| 8 | TC-I18N-01 | 2 | GET (Accept-Language: ru) | name == nameRU |  |  |
| 9 | TC-I18N-02 | 2 | GET (Accept-Language: pl) | name == namePL |  |  |
| 10 | TC-I18N-03 | 2 | GET (без Accept-Language) | name == namePL |  |  |
| 11 | TC-SORTFILTER-01 | 1 | GET ?sort=name,asc (ru) | порядок по nameRU |  |  |
| 12 | TC-SORTFILTER-02 | 1 | GET ?query=name~ct~ааа (ru) | найден s1 |  |  |
| 13 | TC-ABAC-01 | 1 | GET без токена | 401 |  |  |
| 14 | TC-ABAC-02 | 1-4 | ADMIN CRUD | 200 на всех |  |  |
| 15 | TC-ABAC-03 | 3 | POST (READ-роль) | 403 |  |  |
| 16 | TC-ABAC-04 | 2 | GET (FINANCIER) | 403 (нет гранта) |  |  |
| 17 | TC-SEED-01 | 1 | GET ?size=1000 | 9 кодов присутствуют |  |  |
| 18 | TC-REG-01 | 1 | GET ?size=1000 | сид идемпотентен |  |  |
| 19 | TC-REG-02 | 1 | GET без токена | 401 |  |  |
| 20 | TC-REG-03 | 1 | GET /api/resources | есть DELIVERY_CATEGORIES |  |  |
| 21 | TC-UI-SETUP-01 | 2 | Логин ADMIN в браузере | редирект в панель |  |  |
| 22 | TC-UI-01 | 1 | Открытие /delivery-categories | колонки + бейдж + 9 сид-категорий |  |  |
| 23 | TC-UI-02 | 3 | Клик по заголовку (сортировка) | серверный sort, порядок меняется |  |  |
| 24 | TC-UI-03 | 5 | Создать (форма-sheet) | тост успеха + строка |  |  |
| 25 | TC-UI-04 | 2 | Сабмит с пустым code | инлайновая ошибка, сабмит заблокирован |  |  |
| 26 | TC-UI-05 | 3 | Редактирование (code read-only) | code неизменяем, тост, строка обновлена |  |  |
| 27 | TC-UI-06 | 3 | Удаление через диалог | тост успеха, строка исчезла |  |  |
| 28 | TC-UI-07 | 3 | READ-роль на /delivery-categories | контролы скрыты |  |  |
| 29 | TC-UI-08 | 1 | FINANCIER на /delivery-categories | редирект на /403 |  |  |
| 30 | TC-UI-09 | 1 | Переключение локали PL/RU | надписи локализованы, сырые ключи не видны |  |  |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`.

Стратегия повторяемости:
- Часть A (API): `генератор (code = dc{run-id}) + teardown (DELETE созданных категорий)`; кейсы «только чтение» (сид/ABAC/регресс) teardown не требуют.
- Часть B (UI): `генератор (code = dc{run-id}) + teardown (удаление через UI-диалог)`; кейсы «только чтение» teardown не требуют.
