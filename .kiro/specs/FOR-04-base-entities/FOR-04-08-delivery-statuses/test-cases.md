# Тест-кейсы: FOR-04-08 Delivery Statuses (справочник статусов доставок)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерный экран**: справочник статусов доставок раздаётся через REST `/api/delivery-statuses` **и** через админ-страницу CRUD по маршруту `/delivery-statuses` (страница + её роут поставляются в этой спеке; группа меню «Справочники» — отдельно в FOR-04-15). Набор тест-кейсов включает **оба типа**:

- **Часть A. API-тесты** — против работающего в Docker приложения. Каждый шаг — HTTP-запрос к `/api/delivery-statuses` с проверкой статуса, тела и заголовков.
- **Часть B. Браузерные (UI) сценарии** — против фронтенда `http://localhost:3000`, browser automation / headless, под ролью ADMIN.

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание → факт → статус). **Скриншоты не требуются.**

Покрываемые требования: R1 (сущность/таблица c `orderNo`), R2 (CRUD + i18n + сортировка/фильтр + иммутабельный `code` + валидация + обновление `orderNo`), R3 (ABAC-ресурс и гранты, **FINANCIER без гранта**), R4 (сид четырёх статусов, orderNo 1..4), R6 (админ-страница CRUD), R7 (UI-тесты).

## Запуск окружения

1. В корне репозитория: `docker compose up` — `postgres` (:5432), `liquibase`, `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth — под `/api/auth`.
3. Первый ADMIN — через `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном.

## Стратегия повторяемости

**Комбинированная — генератор + teardown:**

- **Генератор:** на прогон формируется `run-id`; создаваемые статусы получают уникальный `code` вида `ds{run-id}` (не конфликтует с seed-кодами `new`, `ordered`, `delivered`, `cancelled`).
- **Teardown:** удалить созданные статусы (`DELETE /api/delivery-statuses/{id}`) и разлогинить токены (`POST /api/auth/logout`) в конце набора.
- **Seed-данные и системные ресурсы/роли НЕ изменяются.**
- i18n-проверки — заголовок `Accept-Language: ru`/`pl`; без заголовка → PL (fallback).

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Кейсы non-ADMIN ролей документируются ожидаемыми результатами и покрываются seed-интеграционным тестом (`DeliveryStatusesResourceSeedIntegrationTest`). **FINANCIER и CLIENT на `DELIVERY_STATUSES` без гранта** (deny-by-default). READ — у MANAGER/FOREMAN/WORKER; полный CRUD — у ADMIN.

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Токен ADMIN (Setup)

### TC-SETUP-01 — Логин ADMIN
`POST /api/auth/login` `{email, password}` → **200** + `accessToken`; использовать в `Authorization: Bearer`.

---

## Фича 1. CRUD статусов доставок

Покрывает R2.1, R2.4, R2.5. **Повторяемость:** генератор `code = ds{run-id}` + teardown.

### TC-CRUD-01 — Создание (POST) → 200
`POST /api/delivery-statuses` `{"code": "ds{run-id}", "orderNo": 99, "nameRU": "тест рус", "namePL": "test pl", "active": true}` → **200** + `id`, `code`, `orderNo == 99`, `nameRU`, `namePL`, `active`.

### TC-CRUD-02 — В списке (GET list)
`GET /api/delivery-statuses?size=100` → **200**; в `content` есть `code == "ds{run-id}"`.

### TC-CRUD-03 — Чтение расширенной модели (GET /{id})
`GET /api/delivery-statuses/{id}` → **200** + `id`, `code`, `orderNo`, `nameRU`, `namePL`, `active`.

### TC-CRUD-04 — Обновление orderNo/имён/active без изменения code (PUT /{id})
`PUT /{id}` `{"orderNo": 5, "nameRU": "рус обновл", "namePL": "pl updated", "active": false}` → **200**; поля обновлены. `GET /{id}` → `code` **не изменился**, `orderNo == 5`.

### TC-CRUD-05 — Удаление (DELETE /{id}) → 200, затем 404
`DELETE /{id}` → **200**; `GET /{id}` → **404**.

### TC-CRUD-06 — Валидация обязательных полей → 400
`POST` без `code` → **400**; с пустым `nameRU` → **400**; без `namePL` → **400**; без `orderNo` → **400** (`@NotNull`).

**Teardown набора:** удалить созданные статусы (кроме удалённого в TC-CRUD-05).

---

## Фича 2. i18n имени (PL fallback)

Покрывает R2.2. **Повторяемость:** генератор `code = ds{run-id}i` + teardown.

### TC-I18N-01 — name == nameRU при Accept-Language: ru
Создать `{code: ds{run-id}i, orderNo: 50, nameRU: "имя-рус", namePL: "nazwa-pl"}`; `GET ?size=100` c `ru` → элемент имеет `name == "имя-рус"`.

### TC-I18N-02 — name == namePL при Accept-Language: pl
`GET` c `pl` → `name == "nazwa-pl"`.

### TC-I18N-03 — PL fallback без Accept-Language
`GET` без заголовка → `name == "nazwa-pl"`.

**Teardown:** `DELETE` статуса `ds{run-id}i`.

---

## Фича 3. Сортировка и фильтрация по локализованному name

Покрывает R2.3. Грамматика `~ct~`. **Повторяемость:** генератор `code = ds{run-id}s{n}` + teardown.

### TC-SORTFILTER-01 — Сортировка по name резолвится в колонку локали
Три статуса (`s1: nameRU "ааа"/namePL "ccc"`, `s2: "ббб"/"bbb"`, `s3: "ввв"/"aaa"`). `GET ?sort=name,asc` c `ru` → `s1,s2,s3`; c `pl` → `s3,s2,s1`.

### TC-SORTFILTER-02 — Фильтр name~ct~ резолвится в колонку локали
`GET ?query=name~ct~ааа` (ru) → есть `s1`; `GET ?query=name~ct~aaa` (pl) → есть `s3`. Убедиться в грамматике `~ct~`, не `=ct=`.

**Teardown:** удалить `s1,s2,s3`.

---

## Фича 4. ABAC-доступ (DELIVERY_STATUSES)

Покрывает R3.1, R3.3. **Повторяемость:** генератор `code = ds{run-id}a` + teardown.

### TC-ABAC-01 — 401 без токена
`GET /api/delivery-statuses` без `Authorization` → **401**; `POST` без токена → **401**.

### TC-ABAC-02 — ADMIN полный CRUD
POST/GET/PUT/DELETE (Bearer ADMIN) → **200** на всех.

### TC-ABAC-03 — Роль с READ (MANAGER/FOREMAN/WORKER): GET разрешён, запись 403
*Покрывается seed-тестом.* GET → **200**; POST/PUT/DELETE → **403**.

### TC-ABAC-04 — FINANCIER и CLIENT без гранта: 403 на всё
*Покрывается seed-тестом.* GET (Bearer FINANCIER) → **403**; POST → **403**; аналогично CLIENT.

**Teardown:** удалить `ds{run-id}a`; разлогинить токены.

---

## Фича 5. Сид справочника (четыре статуса)

Покрывает R4.1. **Повторяемость:** только чтение — teardown не требуется.

### TC-SEED-01 — Четыре дефолтных кода присутствуют с orderNo 1..4
`GET /api/delivery-statuses?size=1000` → присутствуют `new`(orderNo 1), `ordered`(2), `delivered`(3), `cancelled`(4); каждый `active == true`, непустые `nameRU`/`namePL` (например `delivered` → `Доставлен`/`Dostarczone`).

---

# Часть B. Браузерные (UI) сценарии

Против `http://localhost:3000` (browser automation / headless). Результат — MD-репорт с таблицами, **без скриншотов**. Покрывают R6, R7.

## Общий Setup для UI

### TC-UI-SETUP-01 — Вход под ADMIN
Открыть `http://localhost:3000`, ввести email/пароль ADMIN, дождаться редиректа в панель.

## Фича UI-1. Страница «Статусы доставок» (/delivery-statuses)

Покрывает R6.1–R6.7, R7.1. **Повторяемость:** генератор `code = ds{run-id}` + teardown (удаление через UI-диалог).

### TC-UI-01 — Открытие и рендер таблицы
Перейти на `/delivery-statuses`; проверить заголовок (`deliveryStatuses.pageTitle`), колонки `orderNo`/`code`/`name`/`active`, локализованный бейдж активности, наличие четырёх сид-статусов, сортировку по умолчанию по `orderNo` asc (порядок 1..4).

### TC-UI-02 — Поиск, сортировка, фильтрация (общий DataTable)
Поиск сокращает список; клик по заголовкам `orderNo`/`code`/`name` меняет порядок (серверный `sort=...`); фильтр по колонке сужает; снятие восстанавливает.

### TC-UI-03 — Создание через форму-sheet
Кнопка «Создать» видна; заполнить `code = ds{run-id}`, `orderNo`, `nameRU`, `namePL`; сохранить; тост успеха (`deliveryStatuses.toast.createSuccess`); строка появилась.

### TC-UI-04 — Валидация формы
Пустой `code`/`nameRU`/`namePL` или отсутствующий `orderNo` → инлайновые локализованные ошибки (`deliveryStatuses.validation.*`), сабмит заблокирован.

### TC-UI-05 — Редактирование (code read-only)
Открыть форму строки `ds{run-id}`; `code` read-only; изменить `orderNo`/имена/`active`; сохранить; тост; строка обновлена, `code` неизменен.

### TC-UI-06 — Удаление через диалог подтверждения
«Удалить» → диалог (`deliveryStatuses.delete.*`) → подтвердить → тост успеха → строка исчезла.

### TC-UI-07 — Пермишн-гейтинг (READ-роль)
*Покрывается `DeliveryStatusesList.test.tsx`, если роль недоступна.* READ видит список, контролы Создать/Редактировать/Удалить скрыты.

### TC-UI-08 — Пермишн-гейтинг (FINANCIER без доступа)
Интерим-гард `/delivery-statuses` (`{ resource: 'DELIVERY_STATUSES', operation: 'READ' }`) блокирует FINANCIER → редирект на `/403`.

### TC-UI-09 — i18n PL/RU
Переключение локали меняет заголовок, колонки, подписи формы, бейджи; сырые ключи `deliveryStatuses.*` не отображаются (паритет `pl.json`/`ru.json`).

**Teardown набора (UI):** удалить созданные статусы через UI-диалог. Кейсы «только чтение» teardown не требуют.

---

## Регрессия

**Повторяемость:** только чтение.

### TC-REG-01 — Сид-статусы на месте после повторного прогона
`GET ?size=1000` → четыре кода присутствуют, дубликатов нет (идемпотентность).

### TC-REG-02 — ABAC-гард действует
`GET /api/delivery-statuses` без токена → **401**.

### TC-REG-03 — Смежные ресурсы не затронуты; FINANCIER без гранта
`GET /api/resources` → присутствует `DELIVERY_STATUSES` наряду с прежними; у FINANCIER нет гранта на `DELIVERY_STATUSES`.

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SETUP-01 | 1 | POST /api/auth/login (ADMIN) | 200 + accessToken |  |  |
| 2 | TC-CRUD-01 | 1 | POST /api/delivery-statuses | 200 + id, code, orderNo |  |  |
| 3 | TC-CRUD-02 | 1 | GET ?size=100 | 200, содержит code |  |  |
| 4 | TC-CRUD-03 | 1 | GET /{id} | 200 + orderNo/nameRU/namePL/code/active |  |  |
| 5 | TC-CRUD-04 | 2 | GET /{id} после PUT | 200, code неизменен, orderNo обновлён |  |  |
| 6 | TC-CRUD-05 | 2 | GET /{id} после DELETE | 404 |  |  |
| 7 | TC-CRUD-06 | 1 | POST без code / без orderNo | 400 |  |  |
| 8 | TC-I18N-01 | 2 | GET (Accept-Language: ru) | name == nameRU |  |  |
| 9 | TC-I18N-02 | 2 | GET (Accept-Language: pl) | name == namePL |  |  |
| 10 | TC-I18N-03 | 2 | GET (без Accept-Language) | name == namePL |  |  |
| 11 | TC-SORTFILTER-01 | 1 | GET ?sort=name,asc (ru) | порядок по nameRU |  |  |
| 12 | TC-SORTFILTER-02 | 1 | GET ?query=name~ct~ааа (ru) | найден s1 |  |  |
| 13 | TC-ABAC-01 | 1 | GET без токена | 401 |  |  |
| 14 | TC-ABAC-02 | 1-4 | ADMIN CRUD | 200 на всех |  |  |
| 15 | TC-ABAC-03 | 3 | POST (READ-роль) | 403 |  |  |
| 16 | TC-ABAC-04 | 2 | GET (FINANCIER) | 403 (нет гранта) |  |  |
| 17 | TC-SEED-01 | 1 | GET ?size=1000 | 4 кода, orderNo 1..4 |  |  |
| 18 | TC-REG-01 | 1 | GET ?size=1000 | сид идемпотентен |  |  |
| 19 | TC-REG-02 | 1 | GET без токена | 401 |  |  |
| 20 | TC-REG-03 | 1 | GET /api/resources | есть DELIVERY_STATUSES |  |  |
| 21 | TC-UI-SETUP-01 | 2 | Логин ADMIN в браузере | редирект в панель |  |  |
| 22 | TC-UI-01 | 1 | Открытие /delivery-statuses | колонки + бейдж + 4 статуса + сорт orderNo |  |  |
| 23 | TC-UI-02 | 2 | Клик по заголовку (сортировка) | серверный sort, порядок меняется |  |  |
| 24 | TC-UI-03 | 3 | Создать (форма-sheet) | тост успеха + строка |  |  |
| 25 | TC-UI-04 | 1 | Сабмит с пустым code/orderNo | инлайновая ошибка, сабмит заблокирован |  |  |
| 26 | TC-UI-05 | 3 | Редактирование (code read-only) | code неизменяем, тост, строка обновлена |  |  |
| 27 | TC-UI-06 | 3 | Удаление через диалог | тост успеха, строка исчезла |  |  |
| 28 | TC-UI-07 | 3 | READ-роль на /delivery-statuses | контролы скрыты |  |  |
| 29 | TC-UI-08 | 1 | FINANCIER на /delivery-statuses | редирект на /403 |  |  |
| 30 | TC-UI-09 | 1 | Переключение локали PL/RU | надписи локализованы, сырые ключи не видны |  |  |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`.

Стратегия повторяемости:
- Часть A (API): `генератор (code = ds{run-id}) + teardown (DELETE созданных статусов)`; кейсы «только чтение» teardown не требуют.
- Часть B (UI): `генератор (code = ds{run-id}) + teardown (удаление через UI-диалог)`; кейсы «только чтение» teardown не требуют.
