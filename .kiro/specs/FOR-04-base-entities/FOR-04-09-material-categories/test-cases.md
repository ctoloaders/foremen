# Тест-кейсы: FOR-04-09 Material Categories (справочник категорий материалов)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерный экран**: справочник категорий материалов раздаётся через REST `/api/material-categories` **и** через админ-страницу CRUD по маршруту `/material-categories` (страница + её роут поставляются в этой спеке; группа меню «Справочники» — отдельно в FOR-04-15). Набор тест-кейсов включает **оба типа**:

- **Часть A. API-тесты** — против работающего в Docker приложения. Каждый шаг — HTTP-запрос к `/api/material-categories`.
- **Часть B. Браузерные (UI) сценарии** — против фронтенда `http://localhost:3000`, browser automation / headless, под ролью ADMIN.

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание → факт → статус). **Скриншоты не требуются.**

Покрываемые требования: R1 (сущность/таблица), R2 (CRUD + i18n + сортировка/фильтр + иммутабельный `code` + валидация), R3 (ABAC-ресурс и гранты, **FINANCIER имеет READ, CLIENT без гранта**), R4 (сид двух категорий), R6 (админ-страница CRUD), R7 (UI-тесты).

## Запуск окружения

1. В корне репозитория: `docker compose up` — `postgres` (:5432), `liquibase`, `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth — под `/api/auth`.
3. Первый ADMIN — через `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном.

## Стратегия повторяемости

**Комбинированная — генератор + teardown:**

- **Генератор:** на прогон формируется `run-id`; создаваемые категории получают уникальный `code` вида `mc{run-id}` (не конфликтует с seed-кодами `construction`, `finishing`).
- **Teardown:** удалить созданные категории (`DELETE /api/material-categories/{id}`) и разлогинить токены в конце набора.
- **Seed-данные и системные ресурсы/роли НЕ изменяются.**
- i18n-проверки — `Accept-Language: ru`/`pl`; без заголовка → PL (fallback).

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Кейсы non-ADMIN ролей документируются ожидаемыми результатами и покрываются seed-интеграционным тестом (`MaterialCategoriesResourceSeedIntegrationTest`). **FINANCIER на `MATERIAL_CATEGORIES` ИМЕЕТ грант READ** (как MEASUREMENT_UNITS/WORK_CATEGORIES) — в отличие от DELIVERY_CATEGORIES/DELIVERY_STATUSES. **CLIENT без гранта** (deny-by-default). READ — у MANAGER/FOREMAN/WORKER/FINANCIER; полный CRUD — у ADMIN.

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Токен ADMIN (Setup)

### TC-SETUP-01 — Логин ADMIN
`POST /api/auth/login` `{email, password}` → **200** + `accessToken`; использовать в `Authorization: Bearer`.

---

## Фича 1. CRUD категорий материалов

Покрывает R2.1, R2.4, R2.5. **Повторяемость:** генератор `code = mc{run-id}` + teardown.

### TC-CRUD-01 — Создание (POST) → 200
`POST /api/material-categories` `{"code": "mc{run-id}", "nameRU": "тест рус", "namePL": "test pl", "active": true}` → **200** + `id`, `code`, `nameRU`, `namePL`, `active`.

### TC-CRUD-02 — В списке (GET list)
`GET /api/material-categories?size=100` → **200**; в `content` есть `code == "mc{run-id}"`.

### TC-CRUD-03 — Чтение расширенной модели (GET /{id})
`GET /api/material-categories/{id}` → **200** + `id`, `code`, `nameRU`, `namePL`, `active`.

### TC-CRUD-04 — Обновление без изменения code (PUT /{id})
`PUT /{id}` `{"nameRU": "рус обновл", "namePL": "pl updated", "active": false}` → **200**; поля обновлены. `GET /{id}` → `code` **не изменился**.

### TC-CRUD-05 — Удаление (DELETE /{id}) → 200, затем 404
`DELETE /{id}` → **200**; `GET /{id}` → **404**.

### TC-CRUD-06 — Валидация обязательных полей → 400
`POST` без `code` → **400**; с пустым `nameRU` → **400**; без `namePL` → **400**.

**Teardown набора:** удалить созданные категории (кроме удалённой в TC-CRUD-05).

---

## Фича 2. i18n имени (PL fallback)

Покрывает R2.2. **Повторяемость:** генератор `code = mc{run-id}i` + teardown.

### TC-I18N-01 — name == nameRU при Accept-Language: ru
Создать `{code: mc{run-id}i, nameRU: "имя-рус", namePL: "nazwa-pl"}`; `GET ?size=100` c `ru` → `name == "имя-рус"`.

### TC-I18N-02 — name == namePL при Accept-Language: pl
`GET` c `pl` → `name == "nazwa-pl"`.

### TC-I18N-03 — PL fallback без Accept-Language
`GET` без заголовка → `name == "nazwa-pl"`.

**Teardown:** `DELETE` категории `mc{run-id}i`.

---

## Фича 3. Сортировка и фильтрация по локализованному name

Покрывает R2.3. Грамматика `~ct~`. **Повторяемость:** генератор `code = mc{run-id}s{n}` + teardown.

### TC-SORTFILTER-01 — Сортировка по name резолвится в колонку локали
Три категории (`s1: nameRU "ааа"/namePL "ccc"`, `s2: "ббб"/"bbb"`, `s3: "ввв"/"aaa"`). `GET ?sort=name,asc` c `ru` → `s1,s2,s3`; c `pl` → `s3,s2,s1`.

### TC-SORTFILTER-02 — Фильтр name~ct~ резолвится в колонку локали
`GET ?query=name~ct~ааа` (ru) → есть `s1`; `GET ?query=name~ct~aaa` (pl) → есть `s3`. Убедиться в грамматике `~ct~`.

**Teardown:** удалить `s1,s2,s3`.

---

## Фича 4. ABAC-доступ (MATERIAL_CATEGORIES)

Покрывает R3.1, R3.3. **Повторяемость:** генератор `code = mc{run-id}a` + teardown.

### TC-ABAC-01 — 401 без токена
`GET /api/material-categories` без `Authorization` → **401**; `POST` без токена → **401**.

### TC-ABAC-02 — ADMIN полный CRUD
POST/GET/PUT/DELETE (Bearer ADMIN) → **200** на всех.

### TC-ABAC-03 — Роль с READ (MANAGER/FOREMAN/WORKER/FINANCIER): GET разрешён, запись 403
*Покрывается seed-тестом.* GET → **200**; POST/PUT/DELETE → **403**. **Важно:** FINANCIER здесь ИМЕЕТ READ (GET → 200), в отличие от DELIVERY_CATEGORIES.

### TC-ABAC-04 — CLIENT без гранта: 403 на всё
*Покрывается seed-тестом.* GET (Bearer CLIENT) → **403**; POST → **403**.

**Teardown:** удалить `mc{run-id}a`; разлогинить токены.

---

## Фича 5. Сид справочника (две категории)

Покрывает R4.1. **Повторяемость:** только чтение — teardown не требуется.

### TC-SEED-01 — Два дефолтных кода присутствуют
`GET /api/material-categories?size=1000` → присутствуют `construction`, `finishing`; каждый `active == true`, непустые `nameRU`/`namePL` (`construction` → `Строительные`/`Construction`, `finishing` → `Отделочные`/`Finishing`).

---

# Часть B. Браузерные (UI) сценарии

Против `http://localhost:3000` (browser automation / headless). Результат — MD-репорт с таблицами, **без скриншотов**. Покрывают R6, R7.

## Общий Setup для UI

### TC-UI-SETUP-01 — Вход под ADMIN
Открыть `http://localhost:3000`, ввести email/пароль ADMIN, дождаться редиректа в панель.

## Фича UI-1. Страница «Категории материалов» (/material-categories)

Покрывает R6.1–R6.7, R7.1. **Повторяемость:** генератор `code = mc{run-id}` + teardown (удаление через UI-диалог).

### TC-UI-01 — Открытие и рендер таблицы
Перейти на `/material-categories`; заголовок (`materialCategories.pageTitle`), колонки `code`/`name`/`active`, локализованный бейдж, наличие двух сид-категорий.

### TC-UI-02 — Поиск, сортировка, фильтрация (общий DataTable)
Поиск сокращает список; клик по заголовкам `code`/`name` меняет порядок (серверный `sort=...`); фильтр сужает; снятие восстанавливает.

### TC-UI-03 — Создание через форму-sheet
Кнопка «Создать» видна; заполнить `code = mc{run-id}`, `nameRU`, `namePL`; сохранить; тост успеха (`materialCategories.toast.createSuccess`); строка появилась.

### TC-UI-04 — Валидация формы
Пустой `code`/`nameRU`/`namePL` → инлайновые локализованные ошибки (`materialCategories.validation.*`), сабмит заблокирован.

### TC-UI-05 — Редактирование (code read-only)
Открыть форму строки `mc{run-id}`; `code` read-only; изменить имена/`active`; сохранить; тост; строка обновлена, `code` неизменен.

### TC-UI-06 — Удаление через диалог подтверждения
«Удалить» → диалог (`materialCategories.delete.*`) → подтвердить → тост успеха → строка исчезла.

### TC-UI-07 — Пермишн-гейтинг (READ-роль)
*Покрывается `MaterialCategoriesList.test.tsx`, если роль недоступна.* READ (включая FINANCIER) видит список, контролы Создать/Редактировать/Удалить скрыты.

### TC-UI-08 — Пермишн-гейтинг (CLIENT без доступа)
Интерим-гард `/material-categories` (`{ resource: 'MATERIAL_CATEGORIES', operation: 'READ' }`) блокирует CLIENT → редирект на `/403`. FINANCIER, напротив, попадает на страницу (имеет READ).

### TC-UI-09 — i18n PL/RU
Переключение локали меняет заголовок, колонки, подписи формы, бейджи; сырые ключи `materialCategories.*` не отображаются (паритет `pl.json`/`ru.json`).

**Teardown набора (UI):** удалить созданные категории через UI-диалог. Кейсы «только чтение» teardown не требуют.

---

## Регрессия

**Повторяемость:** только чтение.

### TC-REG-01 — Сид-категории на месте после повторного прогона
`GET ?size=1000` → два кода присутствуют, дубликатов нет (идемпотентность).

### TC-REG-02 — ABAC-гард действует
`GET /api/material-categories` без токена → **401**.

### TC-REG-03 — Смежные ресурсы не затронуты; FINANCIER имеет грант
`GET /api/resources` → присутствует `MATERIAL_CATEGORIES` наряду с прежними; у FINANCIER есть грант READ на `MATERIAL_CATEGORIES`.

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SETUP-01 | 1 | POST /api/auth/login (ADMIN) | 200 + accessToken |  |  |
| 2 | TC-CRUD-01 | 1 | POST /api/material-categories | 200 + id, code |  |  |
| 3 | TC-CRUD-02 | 1 | GET ?size=100 | 200, содержит code |  |  |
| 4 | TC-CRUD-03 | 1 | GET /{id} | 200 + nameRU/namePL/code/active |  |  |
| 5 | TC-CRUD-04 | 2 | GET /{id} после PUT | 200, code неизменен |  |  |
| 6 | TC-CRUD-05 | 2 | GET /{id} после DELETE | 404 |  |  |
| 7 | TC-CRUD-06 | 1 | POST без code | 400 |  |  |
| 8 | TC-I18N-01 | 2 | GET (Accept-Language: ru) | name == nameRU |  |  |
| 9 | TC-I18N-02 | 2 | GET (Accept-Language: pl) | name == namePL |  |  |
| 10 | TC-I18N-03 | 2 | GET (без Accept-Language) | name == namePL |  |  |
| 11 | TC-SORTFILTER-01 | 1 | GET ?sort=name,asc (ru) | порядок по nameRU |  |  |
| 12 | TC-SORTFILTER-02 | 1 | GET ?query=name~ct~ааа (ru) | найден s1 |  |  |
| 13 | TC-ABAC-01 | 1 | GET без токена | 401 |  |  |
| 14 | TC-ABAC-02 | 1-4 | ADMIN CRUD | 200 на всех |  |  |
| 15 | TC-ABAC-03 | 2 | GET (FINANCIER) | 200 (есть READ) |  |  |
| 16 | TC-ABAC-04 | 1 | GET (CLIENT) | 403 (нет гранта) |  |  |
| 17 | TC-SEED-01 | 1 | GET ?size=1000 | 2 кода присутствуют |  |  |
| 18 | TC-REG-01 | 1 | GET ?size=1000 | сид идемпотентен |  |  |
| 19 | TC-REG-02 | 1 | GET без токена | 401 |  |  |
| 20 | TC-REG-03 | 1 | GET /api/resources | есть MATERIAL_CATEGORIES |  |  |
| 21 | TC-UI-SETUP-01 | 2 | Логин ADMIN в браузере | редирект в панель |  |  |
| 22 | TC-UI-01 | 1 | Открытие /material-categories | колонки + бейдж + 2 сид-категории |  |  |
| 23 | TC-UI-02 | 2 | Клик по заголовку (сортировка) | серверный sort, порядок меняется |  |  |
| 24 | TC-UI-03 | 3 | Создать (форма-sheet) | тост успеха + строка |  |  |
| 25 | TC-UI-04 | 1 | Сабмит с пустым code | инлайновая ошибка, сабмит заблокирован |  |  |
| 26 | TC-UI-05 | 3 | Редактирование (code read-only) | code неизменяем, тост, строка обновлена |  |  |
| 27 | TC-UI-06 | 3 | Удаление через диалог | тост успеха, строка исчезла |  |  |
| 28 | TC-UI-07 | 3 | READ-роль на /material-categories | контролы скрыты |  |  |
| 29 | TC-UI-08 | 1 | CLIENT на /material-categories | редирект на /403 |  |  |
| 30 | TC-UI-09 | 1 | Переключение локали PL/RU | надписи локализованы, сырые ключи не видны |  |  |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`.

Стратегия повторяемости:
- Часть A (API): `генератор (code = mc{run-id}) + teardown (DELETE созданных категорий)`; кейсы «только чтение» teardown не требуют.
- Часть B (UI): `генератор (code = mc{run-id}) + teardown (удаление через UI-диалог)`; кейсы «только чтение» teardown не требуют.
