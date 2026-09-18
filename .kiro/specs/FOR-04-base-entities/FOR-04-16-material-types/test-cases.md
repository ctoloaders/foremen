# Тест-кейсы: FOR-04-16 Material Dictionaries (четыре справочника материалов)

## Назначение и характер спеки

Спека объединяет ЧЕТЫРЕ справочника материалов, построенные ПАРАЛЛЕЛЬНО из
`docs/Materiały pakiety - Lista.csv`:

- **MaterialType** (`MATERIAL_TYPES`, колонка `Typ`) — новый вертикал: REST `/api/material-types` + админ-страница `/material-types`.
- **MaterialProducer** (`MATERIAL_PRODUCERS`, колонка `Producent`) — новый вертикал: REST `/api/material-producers` + админ-страница `/material-producers`.
- **Material** (`MATERIALS`, колонка `Materiał`) — новый вертикал: REST `/api/materials` + админ-страница `/materials`.
- **MaterialCategory** (`MATERIAL_CATEGORIES`, колонка `Kategoria`) — ПЕРЕ-СИД существующего справочника FOR-04-09 (сущность/контроллер/страница переиспользуются как есть). Проверяется только новый сид.

Набор тест-кейсов включает **оба типа**:

- **Часть A. API-тесты** — против работающего в Docker приложения. Каждый шаг — HTTP-запрос к соответствующему `/api/...`.
- **Часть B. Браузерные (UI) сценарии** — против фронтенда `http://localhost:3000`, browser automation / headless, под ролью ADMIN. Только для трёх новых страниц.

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание → факт → статус). **Скриншоты не требуются.**

Покрываемые требования: R1 (MaterialType), R2 (MaterialProducer), R3 (Material), R4 (ABAC новых ресурсов), R5 (пере-сид MATERIAL_CATEGORIES), R6 (админ-страницы новых справочников), R7 (бэкенд-тесты), R8 (UI-тесты), R9 (параллельная поставка).

## Запуск окружения

1. В корне репозитория: `docker compose up` — `postgres` (:5432), `liquibase` (миграции `046`–`052`), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth — под `/api/auth`.
3. Первый ADMIN — через переменные `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
4. Дождаться готовности `backend` и завершения миграций (changeset'ы `046`–`052`) перед прогоном.

## Стратегия повторяемости

**Комбинированная — генератор + teardown:**

- **Генератор:** на прогон формируется `run-id`; создаваемые записи получают уникальный `code` с префиксом справочника: `mt{run-id}` (types), `mp{run-id}` (producers), `mm{run-id}` (materials). Не конфликтует с сид-кодами.
- **Teardown:** удалить созданные записи (`DELETE /api/<dict>/{id}`) и разлогинить токены в конце набора.
- **Сид-данные и системные ресурсы/роли НЕ изменяются.** Пере-сид MATERIAL_CATEGORIES проверяется только чтением.
- i18n-проверки — `Accept-Language: ru`/`pl`; без заголовка → PL (fallback).

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Кейсы non-ADMIN ролей документируются ожидаемыми результатами и покрываются seed-интеграционными тестами (`Material*ResourceSeedIntegrationTest`). Для всех трёх новых ресурсов **FINANCIER ИМЕЕТ грант READ** (как MATERIAL_CATEGORIES). **CLIENT без гранта** (deny-by-default). READ — у MANAGER/FOREMAN/WORKER/FINANCIER; полный CRUD — у ADMIN.

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Токен ADMIN (Setup)

### TC-SETUP-01 — Логин ADMIN
`POST /api/auth/login` `{email, password}` → **200** + `accessToken`; использовать в `Authorization: Bearer`.

---

## Фича 1. CRUD — MaterialType (`/api/material-types`)

Покрывает R1.4–R1.7. **Повторяемость:** генератор `code = mt{run-id}` + teardown.

### TC-MT-CRUD-01 — Создание (POST) → 200
`POST /api/material-types` `{"code": "mt{run-id}", "nameRU": "тест рус", "namePL": "test pl", "active": true}` → **200** + `id`, `code`, `nameRU`, `namePL`, `active`.

### TC-MT-CRUD-02 — В списке (GET list)
`GET /api/material-types?size=100` → **200**; в `content` есть `code == "mt{run-id}"`.

### TC-MT-CRUD-03 — Чтение (GET /{id})
`GET /api/material-types/{id}` → **200** + `id`, `code`, `nameRU`, `namePL`, `active`.

### TC-MT-CRUD-04 — Обновление без изменения code (PUT /{id})
`PUT /{id}` `{"nameRU": "рус обновл", "namePL": "pl updated", "active": false}` → **200**; поля обновлены; `code` не изменился.

### TC-MT-CRUD-05 — Удаление (DELETE /{id}) → 200, затем 404
`DELETE /{id}` → **200**; `GET /{id}` → **404**.

### TC-MT-CRUD-06 — Валидация обязательных полей → 400
`POST` без `code` → **400**; с пустым `nameRU` → **400**; без `namePL` → **400**.

### TC-MT-I18N-01 — i18n name (ru/pl/fallback)
Создать `{code: mt{run-id}i, nameRU: "имя-рус", namePL: "nazwa-pl"}`; `GET ?size=100` c `ru` → `name == "имя-рус"`; c `pl` → `name == "nazwa-pl"`; без заголовка → `name == "nazwa-pl"`.

### TC-MT-SORT-01 — Сортировка/фильтр по локализованному name
Три записи (`nameRU "ааа"/namePL "ccc"`, `"ббб"/"bbb"`, `"ввв"/"aaa"`). `GET ?sort=name,asc` c `ru` и c `pl` дают разный порядок; `GET ?query=name~ct~ааа` (ru) находит нужную. Грамматика `~ct~`.

### TC-MT-SEED-01 — Сид-коды присутствуют (39 `Typ`)
`GET ?size=1000` → присутствуют, напр., `laminat`, `winyl`, `miska_wc`, `plytka_scienna`, `plytka_scienno_podlogowa`; `active == true`, непустые имена (`laminat` → `Ламинат`/`Laminat`).

**Teardown:** удалить `mt{run-id}*`.

---

## Фича 2. CRUD — MaterialProducer (`/api/material-producers`)

Покрывает R2.4–R2.9. **Повторяемость:** генератор `code = mp{run-id}` + teardown.

### TC-MP-CRUD-01 — Создание (POST) → 200
`POST /api/material-producers` `{"code": "mp{run-id}", "nameRU": "Бренд", "namePL": "Brand", "active": true}` → **200** + поля.

### TC-MP-CRUD-02..06 — list / read / update(code неизменен) / delete→404 / валидация 400
Аналогично TC-MT-CRUD-02..06 для `/api/material-producers`.

### TC-MP-I18N-01 — i18n name (ru/pl/fallback)
Аналогично TC-MT-I18N-01.

### TC-MP-SEED-01 — Сид-коды присутствуют (32 `Producent`)
`GET ?size=1000` → присутствуют, напр., `egger`, `arbiton`, `villeroy_boch`, `grohe`, `tubadzin`; `active == true`. Для брендов `nameRU == namePL` (`egger` → `Egger`/`Egger`).

### TC-MP-SEED-02 — Bogus `Podkład` отсутствует
`GET ?size=1000` → НЕТ записи c `namePL == "Podkład"` и НЕТ `code == "podklad"` в справочнике производителей (это ошибочное значение исключено сидом).

**Teardown:** удалить `mp{run-id}*`.

---

## Фича 3. CRUD — Material (`/api/materials`)

Покрывает R3.4–R3.8. **Повторяемость:** генератор `code = mm{run-id}` + teardown.

### TC-MM-CRUD-01 — Создание (POST) → 200
`POST /api/materials` `{"code": "mm{run-id}", "nameRU": "мат рус", "namePL": "mat pl", "active": true}` → **200** + поля.

### TC-MM-CRUD-02..06 — list / read / update(code неизменен) / delete→404 / валидация 400
Аналогично TC-MT-CRUD-02..06 для `/api/materials`.

### TC-MM-I18N-01 — i18n name (ru/pl/fallback)
Аналогично TC-MT-I18N-01.

### TC-MM-SEED-01 — Сид-коды присутствуют (11 `Materiał`)
`GET ?size=1000` → присутствуют `podloga`, `listwy_przypodlogowe`, `wanna_prysznic`, `zestaw_podtynkowy_wc`, `bateria_wannowa_prysznicowa`, `bateria_umywalkowa`, `umywalki`, `drzwi`, `klamki_rozety`, `plytki`, `akcesoria_srodki`; `active == true` (`podloga` → `Пол`/`Podłoga`).

**Teardown:** удалить `mm{run-id}*`.

---

## Фича 4. ABAC-доступ (три новых ресурса)

Покрывает R4.1, R4.2. **Повторяемость:** генератор + teardown. Выполнять для каждого из `/api/material-types`, `/api/material-producers`, `/api/materials`.

### TC-ABAC-01 — 401 без токена
`GET <endpoint>` без `Authorization` → **401**; `POST` без токена → **401**.

### TC-ABAC-02 — ADMIN полный CRUD
POST/GET/PUT/DELETE (Bearer ADMIN) → **200** на всех.

### TC-ABAC-03 — Роль с READ (MANAGER/FOREMAN/WORKER/FINANCIER): GET 200, запись 403
*Покрывается seed-тестом.* GET → **200**; POST/PUT/DELETE → **403**. FINANCIER имеет READ (GET → 200).

### TC-ABAC-04 — CLIENT без гранта: 403 на всё
*Покрывается seed-тестом.* GET (Bearer CLIENT) → **403**; POST → **403**.

**Teardown:** удалить созданные записи; разлогинить токены.

---

## Фича 5. Пере-сид MaterialCategory (`/api/material-categories`)

Покрывает R5.2, R5.3, R5.4. **Повторяемость:** только чтение — teardown не требуется. Переиспользует существующий эндпоинт FOR-04-09.

### TC-MC-RESEED-01 — Obsolete-коды удалены
`GET /api/material-categories?size=1000` → НЕТ `construction`, НЕТ `finishing`.

### TC-MC-RESEED-02 — Пять новых кодов присутствуют
`GET ?size=1000` → присутствуют `drzwi`, `listwy_przypodlogowe`, `podloga`, `plytki`, `sanitariat`; каждый `active == true`, непустые `nameRU`/`namePL` (`sanitariat` → `Сантехника`/`Sanitariat`, `drzwi` → `Двери`/`Drzwi`).

### TC-MC-RESEED-03 — Ресурс/гранты MATERIAL_CATEGORIES не изменены
`GET /api/resources` → `MATERIAL_CATEGORIES` присутствует; гранты прежние (ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none).

---

# Часть B. Браузерные (UI) сценарии

Против `http://localhost:3000` (browser automation / headless). Результат — MD-репорт с таблицами, **без скриншотов**. Покрывают R6, R8. Только для трёх новых страниц (для MATERIAL_CATEGORIES UI уже покрыт в FOR-04-09).

## Общий Setup для UI

### TC-UI-SETUP-01 — Вход под ADMIN
Открыть `http://localhost:3000`, ввести email/пароль ADMIN, дождаться редиректа в панель.

## Фича UI-1. Страница «Типы материалов» (/material-types)

Покрывает R6.1–R6.7, R8.1. **Повторяемость:** генератор `code = mt{run-id}` + teardown (удаление через UI-диалог).

### TC-UI-MT-01 — Открытие и рендер таблицы
Перейти на `/material-types`; заголовок (`materialTypes.pageTitle`), колонки `code`/`name`/`active`, локализованный бейдж, наличие сид-типов.

### TC-UI-MT-02 — Поиск, сортировка, фильтрация (общий DataTable)
Поиск сокращает список; клик по заголовкам `code`/`name` меняет порядок (серверный `sort=...`); фильтр сужает; снятие восстанавливает.

### TC-UI-MT-03 — Создание через форму-sheet
«Создать» → `code = mt{run-id}`, `nameRU`, `namePL` → сохранить → тост (`materialTypes.toast.createSuccess`) → строка появилась.

### TC-UI-MT-04 — Валидация формы
Пустой `code`/`nameRU`/`namePL` → инлайновые локализованные ошибки (`materialTypes.validation.*`), сабмит заблокирован.

### TC-UI-MT-05 — Редактирование (code read-only)
Открыть форму строки `mt{run-id}`; `code` read-only; изменить имена/`active`; сохранить; тост; строка обновлена, `code` неизменен.

### TC-UI-MT-06 — Удаление через диалог
«Удалить» → диалог (`materialTypes.delete.*`) → подтвердить → тост → строка исчезла.

### TC-UI-MT-07 — Пермишн-гейтинг / интерим-гард
READ-роль (в т.ч. FINANCIER) видит список, контролы скрыты (*покрывается `MaterialTypesList.test.tsx`*). Интерим-гард `/material-types` (`{ resource: 'MATERIAL_TYPES', operation: 'READ' }`) блокирует CLIENT → редирект на `/403`.

### TC-UI-MT-08 — i18n PL/RU
Переключение локали меняет заголовок/колонки/подписи формы/бейджи; сырые ключи `materialTypes.*` не отображаются (паритет `pl.json`/`ru.json`).

**Teardown:** удалить `mt{run-id}*` через UI-диалог.

## Фича UI-2. Страница «Производители материалов» (/material-producers)

Покрывает R6.1–R6.7, R8.1. **Повторяемость:** генератор `code = mp{run-id}` + teardown. Кейсы **TC-UI-MP-01..08** — аналог TC-UI-MT-01..08 для маршрута `/material-producers`, namespace `materialProducers.*`, resource `MATERIAL_PRODUCERS`. Дополнительно в TC-UI-MP-01 проверить наличие сид-производителей (`egger`, `grohe`, `villeroy_boch`).

## Фича UI-3. Страница «Материалы» (/materials)

Покрывает R6.1–R6.7, R8.1. **Повторяемость:** генератор `code = mm{run-id}` + teardown. Кейсы **TC-UI-MM-01..08** — аналог TC-UI-MT-01..08 для маршрута `/materials`, namespace `materials.*`, resource `MATERIALS`. Дополнительно в TC-UI-MM-01 проверить наличие сид-материалов (`podloga`, `plytki`, `drzwi`).

**Teardown набора (UI):** удалить созданные записи через UI-диалог. Кейсы «только чтение» teardown не требуют.

---

## Регрессия

**Повторяемость:** только чтение.

### TC-REG-01 — Сид новых справочников идемпотентен
`GET ?size=1000` для каждого из трёх новых эндпоинтов → репрезентативные коды присутствуют, дубликатов `code` нет (идемпотентность `046`–`051`).

### TC-REG-02 — ABAC-гарды действуют
`GET` без токена на каждый из `/api/material-types`, `/api/material-producers`, `/api/materials` → **401**.

### TC-REG-03 — Новые ресурсы зарегистрированы; FINANCIER имеет гранты
`GET /api/resources` → присутствуют `MATERIAL_TYPES`, `MATERIAL_PRODUCERS`, `MATERIALS` наряду с прежними; у FINANCIER есть грант READ на каждый.

### TC-REG-04 — Пере-сид категорий: construction/finishing удалены, 5 новых на месте
`GET /api/material-categories?size=1000` → НЕТ `construction`/`finishing`; присутствуют `drzwi`, `listwy_przypodlogowe`, `podloga`, `plytki`, `sanitariat`; дубликатов нет (идемпотентность `052`). Ресурс/гранты `MATERIAL_CATEGORIES` не изменены.

### TC-REG-05 — Смежные справочники материалов не затронуты
`GET /api/material-categories`, соседние ресурсы FOR-04 отвечают штатно; пере-сид не задел структуру таблицы `material_categories` (схема прежняя).

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SETUP-01 | 1 | POST /api/auth/login (ADMIN) | 200 + accessToken |  |  |
| 2 | TC-MT-CRUD-01 | 1 | POST /api/material-types | 200 + id, code |  |  |
| 3 | TC-MT-CRUD-04 | 2 | GET /{id} после PUT | 200, code неизменен |  |  |
| 4 | TC-MT-CRUD-05 | 2 | GET /{id} после DELETE | 404 |  |  |
| 5 | TC-MT-CRUD-06 | 1 | POST без code | 400 |  |  |
| 6 | TC-MT-I18N-01 | 2 | GET (ru/pl/без заголовка) | name резолвится по локали |  |  |
| 7 | TC-MT-SEED-01 | 1 | GET ?size=1000 | 39 `Typ` коды присутствуют |  |  |
| 8 | TC-MP-CRUD-01 | 1 | POST /api/material-producers | 200 + id, code |  |  |
| 9 | TC-MP-SEED-01 | 1 | GET ?size=1000 | 32 бренда присутствуют |  |  |
| 10 | TC-MP-SEED-02 | 1 | GET ?size=1000 | `Podkład` отсутствует |  |  |
| 11 | TC-MM-CRUD-01 | 1 | POST /api/materials | 200 + id, code |  |  |
| 12 | TC-MM-SEED-01 | 1 | GET ?size=1000 | 11 `Materiał` коды присутствуют |  |  |
| 13 | TC-ABAC-01 | 1 | GET без токена (каждый эндпоинт) | 401 |  |  |
| 14 | TC-ABAC-02 | 1-4 | ADMIN CRUD (каждый эндпоинт) | 200 на всех |  |  |
| 15 | TC-ABAC-03 | 2 | GET (FINANCIER) | 200 (есть READ) |  |  |
| 16 | TC-ABAC-04 | 1 | GET (CLIENT) | 403 (нет гранта) |  |  |
| 17 | TC-MC-RESEED-01 | 1 | GET /api/material-categories?size=1000 | нет construction/finishing |  |  |
| 18 | TC-MC-RESEED-02 | 1 | GET ?size=1000 | 5 новых кодов присутствуют |  |  |
| 19 | TC-MC-RESEED-03 | 1 | GET /api/resources | MATERIAL_CATEGORIES гранты прежние |  |  |
| 20 | TC-UI-SETUP-01 | 2 | Логин ADMIN в браузере | редирект в панель |  |  |
| 21 | TC-UI-MT-01 | 1 | Открытие /material-types | колонки + бейдж + сид-типы |  |  |
| 22 | TC-UI-MT-03 | 3 | Создать (форма-sheet) | тост успеха + строка |  |  |
| 23 | TC-UI-MT-05 | 3 | Редактирование (code read-only) | code неизменяем, тост |  |  |
| 24 | TC-UI-MT-06 | 3 | Удаление через диалог | тост успеха, строка исчезла |  |  |
| 25 | TC-UI-MT-07 | 1 | CLIENT на /material-types | редирект на /403 |  |  |
| 26 | TC-UI-MP-01 | 1 | Открытие /material-producers | колонки + сид-производители |  |  |
| 27 | TC-UI-MP-03 | 3 | Создать (форма-sheet) | тост успеха + строка |  |  |
| 28 | TC-UI-MM-01 | 1 | Открытие /materials | колонки + сид-материалы |  |  |
| 29 | TC-UI-MM-03 | 3 | Создать (форма-sheet) | тост успеха + строка |  |  |
| 30 | TC-REG-01 | 1 | GET ?size=1000 (3 эндпоинта) | сид идемпотентен |  |  |
| 31 | TC-REG-03 | 1 | GET /api/resources | 3 новых ресурса + FINANCIER READ |  |  |
| 32 | TC-REG-04 | 1 | GET /api/material-categories | пере-сид корректен |  |  |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`.

Стратегия повторяемости:
- Часть A (API): `генератор (code = mt/mp/mm{run-id}) + teardown (DELETE созданных записей)`; кейсы «только чтение» (сид, пере-сид категорий) teardown не требуют.
- Часть B (UI): `генератор (code = mt/mp/mm{run-id}) + teardown (удаление через UI-диалог)`; кейсы «только чтение» teardown не требуют.
