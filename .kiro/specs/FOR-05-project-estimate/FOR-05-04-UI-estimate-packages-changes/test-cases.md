# Тест-кейсы: FOR-05-04-UI — выравнивание UI смет/пакетов и схлопывание пакетного измерения на стороне материалов

## Назначение и характер спеки

Это **full-stack** спека: UI-follow-up к бэкенд-спеке `FOR-05-04-estimate-packages-changes` плюс небольшой бэкенд-делта (миграция схлопывания пакетов у конструкционных материалов + пересбор resolver-а диапазона цен + чистка DTO). Поэтому набор тест-кейсов включает **обе части**:

- **Часть A. API-тесты** — против работающего в Docker приложения (`http://localhost:8080`, auth под `/api/auth`). Каждый шаг — HTTP-запрос с проверкой статуса/тела/заголовков. Покрывают схлопывание конструкционных материалов (R5, бэкенд-половина), эндпоинты ассортимента (включая пересчёт `package-zl-m2`), ключи ошибок формул и гейтинг маршрутов.
- **Часть B. Браузерные (UI) сценарии** — против фронтенда `http://localhost:3000` (browser automation / headless, под ролью ADMIN). Покрывают выровненные экраны работ/цен/конструкционных материалов, CRUD ассортимента + readout zł/m², редакторы формул, а также локализацию и гейтинг маршрутов на уровне SPA.

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание → факт → статус). **Скриншоты не требуются.**

Покрываемые требования: **R1** (список работ — единая cost-cell), **R2** (селекты правки работы показывают имена, не id), **R3** (список цен — плоская строка), **R4** (форма цены — единая цена, грузится без зависания), **R5** (схлопывание пакетного измерения у конструкционных материалов и норм расхода — full-stack), **R6** (UI ассортимента и конфигурации zł/m²), **R7** (UI формулы объёма работы и per-package override), **R8** (сквозные ограничения: ABAC, i18n, безопасность миграции, раскладка репозиториев).

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth — под `/api/auth`.
3. Первый ADMIN — через переменные окружения `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
4. Дождаться готовности `backend` и завершения миграций (в т.ч. нового changeset `NNN-archive-and-drop-construction-material-packages.xml`, зарегистрированного **последним** в `changelog.xml`) перед прогоном.
5. **Предзависимости по данным (FK-id для создания сущностей):**
   - `GET /api/work-items?size=1000` — id позиций работ.
   - `GET /api/currencies?size=1000&sort=code,asc` — id валют.
   - `GET /api/work-categories?size=1000`, `GET /api/measurement-units?size=1000` — id категорий и единиц.
   - `GET /api/offer-packages?size=1000` — id и `code` пакетов предложений (нужны для `package-zl-m2` и line-item ассортимента).
   - `GET /api/construction-material-types?size=1000` — id типов конструкционных материалов.

## Стратегия повторяемости

**Комбинированная — генератор + teardown** (единая для всех наборов, если в наборе не указано иное):

- **Генератор:** на каждый прогон формируется `run-id` (timestamp/uuid). Создаваемые сущности получают уникальные имена вида `nameRU/namePL = <prefix>{run-id}` (например `grp{run-id}`, `li{run-id}`, `wp{run-id}`, `cm{run-id}`). Тестовый пользователь для проверок ABAC (где создаётся) — `test+{run-id}@example.com`.
- **Teardown:** в конце каждого набора удалить созданные сущности (`DELETE /api/...` или удаление через UI-диалог) и разлогинить токены. Наборы «только чтение» (метаданные, гейтинг, регресс) teardown не требуют.
- **Seed-данные и системные ресурсы/роли НЕ изменяются** — только читаются для получения FK-id.
- i18n-проверки выполняются с `Accept-Language: ru`/`pl`; без заголовка → PL (fallback).

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Кейсы non-ADMIN ролей (READ-гранты, отсутствие гранта) документируются ожидаемыми результатами; при отсутствии готового пользователя они помечаются как покрываемые seed-интеграционными и guard-тестами фронтенда. Ресурсы, релевантные спеке: `WORK_CATALOG`, `WORK_PRICES`, `MATERIALS_CONSTRUCTION`, `OFFER_PACKAGES`, `PACKAGE_ASSORTMENT` (последний засижен бэкенд-changeset `089`).

---

# Часть A. API-тесты (docker-стек)

## Фича A0. Setup (токен ADMIN + FK-id)

### TC-SETUP-01 — Логин ADMIN
**Предусловия:** docker-стек поднят, миграции применены.
1. `POST /api/auth/login` `{email, password}` (ADMIN) → **200** + `accessToken` (+ `refreshToken`). Сохранить `accessToken`.

### TC-SETUP-02 — Получить FK-id справочников
1. `GET /api/work-items?size=1000` (Bearer ADMIN) → **200**; сохранить `workItemId`.
2. `GET /api/currencies?size=1000&sort=code,asc` → **200**; сохранить `currencyId` и `currencyCode`.
3. `GET /api/offer-packages?size=1000` → **200**; сохранить `offerPackageId` и `packageCode`.
4. `GET /api/construction-material-types?size=1000` → **200**; сохранить `constructionMaterialTypeId`.

---

## Фича A1. Схлопывание пакетов у конструкционных материалов и норм расхода (R5, бэкенд)

Покрывает R5.1–R5.9, R8.8, R8.9. **Повторяемость:** генератор `cm{run-id}` + teardown; проверки схемы/архива — только чтение (миграция уже применена стеком).

### TC-A1-01 — Контракт создания конструкционного материала без пакетного измерения
**Предусловия:** есть `constructionMaterialTypeId`.
1. `POST /api/construction-materials` с телом БЕЗ `offerPackageIds` (`{"typeId": <id>, "nameRU": "cm{run-id} рус", "namePL": "cm{run-id} pl", "retailNet": 100.00, "active": true, ...}`) → **200** + `id`; тело ответа НЕ содержит `packages`/`offerPackageIds`. _(R5.2)_
2. Повторить `POST` c явно переданным `offerPackageIds` → поле игнорируется (не влияет), либо **400/422**, если строгая валидация запрещает неизвестное поле; в любом случае созданный материал не имеет привязки к пакетам. _(R5.2)_

### TC-A1-02 — Список конструкционных материалов не отдаёт `packages`
1. `GET /api/construction-materials?size=100` (Bearer ADMIN) → **200**; ни один элемент не содержит поле `packages`. _(R5.1)_

### TC-A1-03 — Расширенная модель без `packages`/`offerPackageIds`
1. `GET /api/construction-materials/{id}` (созданный в TC-A1-01) → **200**; тело содержит `typeId`, `nameRU`, `namePL`, `retailNet`, `active`; НЕ содержит `packages`/`offerPackageIds`. _(R5.1, R5.2)_

### TC-A1-04 — Диапазон цен ключуется ТОЛЬКО по типу материала (`priceRanges`)
**Предусловия:** создать 2–3 материала одного `constructionMaterialTypeId` с разными `retailNet` (напр. 50, 100, 200), все `active`.
1. `GET /api/construction-materials?size=1000` → **200**; в поле `priceRanges` присутствует запись с ключом `constructionMaterialTypeId == <id>` и `min == 50`, `max == 200`. _(R5.3, R5.7)_
2. Запись диапазона НЕ содержит `offerPackageId` (ключ — только тип). _(R5.7)_

### TC-A1-05 — `retailNet == null` и `active == false` исключены из диапазона
1. Создать материал того же типа с `retailNet = null` и материал с `active = false` и `retailNet = 999`.
2. `GET /api/construction-materials?size=1000` → диапазон типа НЕ учитывает ни `null`, ни неактивный: `max` остаётся 200 (из TC-A1-04). _(R5.7)_

### TC-A1-06 — Пустой bucket типа → `null..null` без фабрикации
**Предусловия:** тип материала, у которого нет ни одного квалифицирующего материала (все неактивны/`null`).
1. `GET /api/construction-materials?size=1000` → для такого типа `priceRanges` либо отсутствует, либо `min == null` и `max == null` (нет выдуманного fallback). _(R5.7)_

### TC-A1-07 — Диапазон инвариантен к пакетам (независимость от привязки)
1. Зафиксировать диапазон типа из TC-A1-04.
2. Так как пакетная привязка на стороне материала удалена, любые операции над пакетами (создание/удаление offer-package) не меняют `priceRanges` того же набора материалов: повторный `GET` → те же `min/max`. _(R5.3, R5.7)_

### TC-A1-08 — Норма расхода создаётся без `offerPackageId`
**Предусловия:** есть `workItemId`, `constructionMaterialTypeId`.
1. `POST /api/work-material-consumptions` с телом БЕЗ `offerPackageId` (`{"workItemId": <id>, "materialTypeId": <id>, "branch": "CONSTRUCTION", "quantity": 1.5, ...}`) → **200** + `id`; создание успешно без пакета. _(R5.6)_
2. `GET /api/work-material-consumptions/{id}` → **200**; тело НЕ содержит `offerPackageId`. _(R5.6)_
3. `POST` с явным `offerPackageId` → поле не является обязательным и не резолвится (нет `@NotNull`); материал-норма создаётся/поле игнорируется. _(R5.5, R5.6)_

### TC-A1-09 — cost-cell работы (construction/finishing) считается по работе, не по пакету (R5.8)
**Предусловия:** у `workItemId` есть нормы расхода и цены материалов.
1. `GET /api/work-items?size=1000` → **200**; у позиции присутствует `costCell.construction {min,max}` и `costCell.finishing {min,max}`; значения не зависят от какой-либо пакетной привязки. _(R5.8)_

### TC-A1-10 — Архив создан до дропа M:N, миграция идемпотентна (R5.4, R5.9, R8.8, R8.9)
**Характер:** проверяется Liquibase-интеграционным тестом; в docker-прогоне подтверждается косвенно.
1. Таблица `construction_material_packages_archive` существует (снимок строк до дропа + `archived_at`). _(R5.4, R8.8)_
2. Join-таблица `construction_material_packages` отсутствует после применения. _(R5.4)_
3. Повторный прогон миграции против уже схлопнутой БД не меняет схему/данные (`preConditions onFail="MARK_RAN"`). _(R5.9, R8.9)_

**Teardown набора:** удалить созданные материалы и нормы расхода (`DELETE /api/construction-materials/{id}`, `DELETE /api/work-material-consumptions/{id}`).

---

## Фича A2. Плоская цена работы (контракт формы/списка) (R3, R4)

Покрывает R3.1–R3.4, R4.1–R4.5. **Повторяемость:** генератор + teardown.

### TC-A2-01 — Список цен — плоская модель
1. `GET /api/work-prices?size=100` (Bearer ADMIN) → **200**; каждый элемент содержит `id`, `workItemId`, `workItemName`, `currencyId`, `currencyCode`, `netPrice`; НЕ содержит `prices`/пакетных pivot-полей. _(R3.1, R3.2, R3.3, R3.4)_

### TC-A2-02 — Создание цены плоским payload
**Предусловия:** есть `workItemId`, `currencyId`.
1. `POST /api/work-prices` `{"workItemId": <id>, "currencyId": <id>, "netPrice": 123.45}` → **200** + `id`; тело содержит плоские поля, без `packagePrices`. _(R4.1, R4.4, R4.5)_

### TC-A2-03 — Расширенная модель для формы правки
1. `GET /api/work-prices/{id}` → **200**; тело `{id, workItemId, workItemName?, currencyId, currencyCode?, netPrice}` — достаточно для префилла формы без пакетных данных. _(R4.2, R4.3)_

### TC-A2-04 — Обновление цены плоским payload
1. `PUT /api/work-prices/{id}` `{"workItemId": <id>, "currencyId": <другой id>, "netPrice": 200.00}` → **200**; `GET /{id}` → значения обновлены. _(R4.4, R4.5)_

### TC-A2-05 — Валидация плоской цены
1. `POST` с `netPrice` < 0 → **400**; с несуществующим `workItemId` → 4xx (нарушение FK); без `currencyId` → **400**. _(R4.1)_

**Teardown набора:** удалить созданные цены.

---

## Фича A3. Ассортимент: группы, line items, пересчёт zł/m² (R6)

Покрывает R6.1–R6.8. **Повторяемость:** генератор `grp{run-id}`/`li{run-id}` + teardown.

### TC-A3-01 — CRUD группы ассортимента
1. `POST /api/assortment-groups` `{"nameRU": "grp{run-id} рус", "namePL": "grp{run-id} pl", "sortOrder": 10}` → **200** + `id`. _(R6.1)_
2. `GET /api/assortment-groups?size=100` → созданная группа присутствует.
3. `PUT /api/assortment-groups/{id}` (смена `sortOrder`) → **200**; `GET /{id}` → обновлено.
4. `DELETE /api/assortment-groups/{id}` → **200**; `GET /{id}` → **404** (удаляется в конце набора после line items).

### TC-A3-02 — CRUD line item ассортимента (с FK и `qtyRef50`)
**Предусловия:** есть `assortmentGroupId` (TC-A3-01), `offerPackageId`.
1. `POST /api/assortment-line-items` `{"assortmentGroupId": <id>, "offerPackageId": <id>, "nameRU": "li{run-id} рус", "namePL": "li{run-id} pl", "minPrice": 10, "avgPrice": 20, "maxPrice": 30, "qtyRef50": 5}` → **200** + `id`; `qtyRef50` обязателен. _(R6.2)_
2. `GET /api/assortment-line-items?size=100` → элемент содержит `assortmentGroupId`/`assortmentGroupName`, `offerPackageId`/`offerPackageName`. _(R6.2, R6.3)_
3. `POST` без `qtyRef50` → **400** (обязательное). _(R6.2)_

### TC-A3-03 — Опциональный `typicalProductId` — только provenance
**Предусловия:** есть id типового продукта (если справочник доступен) либо пропустить с обоснованием.
1. `POST /api/assortment-line-items` c `typicalProductId` → **200**; ответ содержит `typicalProductId`/`typicalProductName`.
2. Значения `minPrice/avgPrice/maxPrice` заданы явно и НЕ выводятся из типового продукта (provenance-ссылка не меняет вычисляемое значение). _(R6.4)_

### TC-A3-04 — Readout zł/m² по коду пакета
**Предусловия:** есть `packageCode` с хотя бы одним line item.
1. `GET /api/assortment-line-items/package-zl-m2?packageCode={code}` (Bearer ADMIN) → **200** + `{"value": <число>}`. _(R6.5)_
2. Значение возвращается как есть (не домножено на площадь пола). _(R6.8)_

### TC-A3-05 — Пересчёт zł/m² из текущих данных (не кэш)
1. Зафиксировать `value` из TC-A3-04.
2. Изменить данные ассортимента пакета (`POST`/`PUT`/`DELETE` line item, влияющий на пакет).
3. Повторный `GET .../package-zl-m2?packageCode={code}` → **200**; `value` отражает новые данные (пересчитан, не старый кэш). _(R6.6)_

### TC-A3-06 — Пустой пакет → value 0
**Предусловия:** `packageCode` без line items.
1. `GET .../package-zl-m2?packageCode={code}` → **200** + `{"value": 0}` (не ошибка). _(R6.5)_

**Teardown набора:** удалить созданные line items, затем группы.

---

## Фича A4. Формулы: дефолтная формула объёма и per-package override; ключи ошибок (R7)

Покрывает R7.1–R7.10. **Повторяемость:** генератор + teardown; одна дефолтная формула на работу.

### TC-A4-01 — CRUD дефолтной формулы объёма (одна на работу)
**Предусловия:** есть `workItemId` без формулы.
1. `POST /api/work-volume-formulas` `{"workItemId": <id>, "sourceText": "length * width"}` → **200** + `id`, `sourceText`. _(R7.1)_
2. `GET /api/work-volume-formulas?size=100` → присутствует; `PUT /{id}` меняет `sourceText` → **200**.
3. Повторный `POST` для того же `workItemId` → отклонён (одна дефолтная формула на работу) или обновляет существующую (по контракту бэкенда).

### TC-A4-02 — CRUD per-package override; present override ⇒ member (R7.2, R7.3, R7.4)
**Предусловия:** есть `workItemId`, `offerPackageId`.
1. `POST /api/work-package-overrides` `{"workItemId": <id>, "offerPackageId": <id>, "member": true, "overrideSourceText": "height * 2"}` → **200**; ответ БЕЗ поля цены. _(R7.2, R7.4)_
2. `POST` с `overrideSourceText` present, но `member: false` → сервер трактует как member (present override implies member) — итоговое состояние `member == true`. _(R7.3)_

### TC-A4-03 — Ошибка: неизвестная переменная (R7.5)
1. `POST /api/work-volume-formulas` `{"workItemId": <id>, "sourceText": "foo123 * 2"}` → **4xx**; тело содержит ключ/локализованное сообщение `error.formula.unknown.variable`.

### TC-A4-04 — Ошибка: неизвестная ссылка на работу (R7.6)
1. `POST` `{"sourceText": "work(999999)"}` (несуществующая ссылка) → **4xx**; `error.formula.unknown.work.reference`.

### TC-A4-05 — Ошибка: недопустимый оператор (R7.7)
1. `POST` `{"sourceText": "length % width"}` (или иной запрещённый оператор) → **4xx**; `error.formula.illegal.operator`.

### TC-A4-06 — Ошибка: цикл (R7.8)
1. Создать формулы, ссылающиеся друг на друга по кругу → **409** (или 4xx); `error.formula.cycle`.

### TC-A4-07 — Ошибка: деление на ноль (R7.9)
1. `POST` `{"sourceText": "length / 0"}` → **4xx**; `error.formula.division.by.zero`.

### TC-A4-08 — Локализованное сообщение при разных Accept-Language (R7.10)
1. Повторить TC-A4-03 с `Accept-Language: ru` и `pl` → тело содержит локализованное сообщение (не сырой ключ) в обеих локалях.

**Teardown набора:** удалить созданные формулы и overrides.

---

## Фича A5. ABAC-гейтинг ресурсов (R8.1–R8.5)

Покрывает R8.1, R8.2, R8.3, R8.4, R8.5. **Повторяемость:** только чтение (создание пользователя `test+{run-id}@example.com` — при наличии сценария создания ролей).

### TC-A5-01 — 401 без токена
1. `GET /api/construction-materials`, `GET /api/work-prices`, `GET /api/assortment-groups` без `Authorization` → **401**.

### TC-A5-02 — ADMIN доступ ко всем релевантным ресурсам
1. `GET` по `/api/work-items`, `/api/work-prices`, `/api/construction-materials`, `/api/offer-packages`, `/api/assortment-groups`, `/api/assortment-line-items` (Bearer ADMIN) → **200** на всех.

### TC-A5-03 — Отсутствие READ на `PACKAGE_ASSORTMENT` → 403 (покрывается seed/guard-тестом)
1. Пользователь без гранта `PACKAGE_ASSORTMENT` READ: `GET /api/assortment-groups` → **403**. _(R8.5)_

### TC-A5-04 — Существующие гейты не регрессировали
1. Ресурсы `WORK_CATALOG`, `WORK_PRICES`, `MATERIALS_CONSTRUCTION`, `OFFER_PACKAGES` присутствуют в `GET /api/resources` и продолжают гейтить свои эндпоинты. _(R8.1–R8.4)_

---

# Часть B. Браузерные (UI) сценарии

Против `http://localhost:3000` (browser automation / headless, под ролью ADMIN). Результат — MD-репорт с таблицами, **без скриншотов**. Покрывают R1, R2, R3, R4, R5 (frontend), R6, R7, R8 (i18n/гейтинг на уровне SPA).

## Общий Setup для UI

### TC-UI-SETUP-01 — Вход под ADMIN
1. Открыть `http://localhost:3000`, ввести email/пароль ADMIN, дождаться редиректа в панель.

## Фича UI-1. Список каталога работ — единая cost-cell (R1)

Покрывает R1.1–R1.5. **Повторяемость:** только чтение (или генератор `wi{run-id}` + teardown при создании позиций).

### TC-UI-01 — Три колонки cost-cell вместо pivot-столбцов
1. Перейти на `/catalog/works`.
2. Ожидание: три статичные колонки — цена труда (`labourPrice`), диапазон `construction` (min..max), диапазон `finishing` (min..max), взятые из `row.costCell`. _(R1.1, R1.5)_
3. Пакетных pivot-столбцов (цена/нормы по пакетам) НЕТ. _(R1.2)_

### TC-UI-02 — Пустой `labourPrice` → плейсхолдер
1. Для позиции с `costCell.labourPrice == null` в ячейке цены труда отображается локализованный плейсхолдер (пустое значение), а не `null`/`0`. _(R1.3)_

### TC-UI-03 — Диапазон `0..0` рендерится буквально
1. Для позиции с `construction`/`finishing`, где `min == 0` и `max == 0`, диапазон отображается как `0..0` (форматтер НЕ схлопывает `min===max` в одиночный `0` для этого случая). _(R1.4)_

## Фича UI-2. Правка работы — селекты по имени, не по id (R2)

Покрывает R2.1–R2.3. **Повторяемость:** только чтение.

### TC-UI-04 — Селект категории работ показывает имя
1. На `/catalog/works` открыть позицию на редактирование.
2. Селект «Категория» в закрытом состоянии показывает локализованное имя текущей категории (через `selectedLabel`, засеянный из строки списка), а не `#<id>`. _(R2.1, R2.3)_

### TC-UI-05 — Селект единицы показывает имя
1. В той же форме селект «Единица» в закрытом состоянии показывает локализованное имя единицы, а не `#<id>`. _(R2.2, R2.3)_

## Фича UI-3. Список цен — плоская строка (R3)

Покрывает R3.1–R3.4. **Повторяемость:** только чтение.

### TC-UI-06 — Три колонки, без pivot
1. Перейти на `/catalog/prices`.
2. Колонки: имя работы (`workItemName`), валюта (`currencyCode`), нетто-цена (`netPrice`). _(R3.1, R3.2, R3.3)_
3. Пакетных pivot-столбцов НЕТ. _(R3.4)_

## Фича UI-4. Форма цены — единая цена, грузится без зависания (R4)

Покрывает R4.1–R4.5. **Повторяемость:** генератор `wp{run-id}` + teardown (удаление через UI/DELETE).

### TC-UI-07 — Создание: три поля
1. На `/catalog/prices` нажать «Создать».
2. Форма содержит: селект работы (`AsyncEntitySelect`, `/api/work-items`), селект валюты (`AsyncEntitySelect`, `/api/currencies`, `sort=code,asc`), числовое поле нетто-цены. _(R4.1)_
3. Пакетных полей цены НЕТ. _(R4.5)_

### TC-UI-08 — Правка префиллит и НЕ зависает в скелетоне (регресс R4.2)
1. Открыть существующую цену на редактирование.
2. Форма префиллит работу/валюту/нетто-цену из `WorkPriceExtendedDto`, селекты показывают имена (seeded `selectedLabel`), и форма выходит из состояния skeleton (не висит бесконечно на `useSeededPackages`). _(R4.2, R4.3)_

### TC-UI-09 — Сабмит отправляет плоский payload
1. Заполнить/изменить поля, сохранить.
2. В сетевом запросе тело — ровно `{workItemId, currencyId, netPrice}`; тост успеха; строка обновлена. _(R4.4, R4.5)_

**Teardown набора:** удалить созданную цену.

## Фича UI-5. Конструкционные материалы без пакетного измерения (R5, frontend)

Покрывает R5.1–R5.3. **Повторяемость:** генератор `cm{run-id}` + teardown (через UI-диалог).

### TC-UI-10 — Нет колонки пакетов в списке
1. Перейти на `/materials/construction`; колонка «Пакеты» отсутствует. _(R5.1)_
2. Колонка диапазона цен присутствует и рендерит **ровно один** диапазон на строку, ключёванный по `type` строки (`constructionMaterialTypeId === row.type.id`), без per-package fan-out. _(R5.3)_

### TC-UI-11 — Нет мультиселекта пакетов в форме
1. Открыть форму создания/правки материала; мультиселект пакетов (`PackagesMultiSelect`) отсутствует; сохранение работает без выбора пакетов. _(R5.2)_

**Teardown набора:** удалить созданный материал через UI-диалог.

## Фича UI-6. UI ассортимента + readout zł/m² (R6)

Покрывает R6.1–R6.8. **Повторяемость:** генератор `grp{run-id}`/`li{run-id}` + teardown.

### TC-UI-12 — CRUD группы ассортимента
1. Открыть страницу ассортимента; создать группу (`nameRU`, `namePL`, `sortOrder`); тост успеха; группа в списке. Правка/удаление работают. _(R6.1)_

### TC-UI-13 — CRUD line item, сгруппировано по группе
1. Создать line item (`nameRU`, `namePL`, `minPrice`, `avgPrice`, `maxPrice`, обязательный `qtyRef50`, селекты группы/пакета/опц. типового продукта через `AsyncEntitySelect`). _(R6.2)_
2. Список отображает line items **сгруппированными по AssortmentGroup** (заголовок группы, сортировка по `sortOrder`, затем имя). _(R6.3)_

### TC-UI-14 — Типовой продукт — только provenance
1. У line item с типовым продуктом имя типового продукта показывается как метка-provenance, НЕ как поле, влияющее на min/avg/max; отправляется только опциональный `typicalProductId`. _(R6.4)_

### TC-UI-15 — Readout zł/m² показывает value эндпоинта, без домножения на площадь
1. В контексте пакета открыть readout zł/m²; отображается `value` из `GET .../package-zl-m2?packageCode={code}` как есть, без домножения на площадь пола. _(R6.5, R6.8)_
2. Для пакета без line items отображается `0`. _(R6.5)_

### TC-UI-16 — Мутация инвалидирует readout (пересчёт при повторном просмотре)
1. Изменить данные ассортимента (создать/изменить/удалить line item/группу).
2. Повторно открыть readout → значение пересчитано (query `['package-zl-m2']` инвалидирован на `onSuccess`, `staleTime: 0`), не старый кэш. _(R6.6)_

### TC-UI-17 — Точка входа из контекста правки пакета
1. На `/offer-packages` открыть пакет на редактирование; присутствует действие «Управление ассортиментом / посмотреть zł/m²», ведущее на маршрут ассортимента, ограниченный `code` пакета; readout zł/m² показан inline в sheet. _(R6.7)_

**Teardown набора:** удалить созданные line items, затем группы.

## Фича UI-7. UI формул: дефолтная формула объёма + per-package override (R7)

Покрывает R7.1–R7.10. **Повторяемость:** генератор + teardown.

### TC-UI-18 — Редактор дефолтной формулы объёма
1. В потоке правки работы (`/catalog/works` → редактирование) есть редактор дефолтной формулы: свободный `sourceText` над 14 размерами комнаты и cross-work ссылками; UI отправляет только `sourceText`. _(R7.1)_

### TC-UI-19 — Редактор per-package membership + override; present override ⇒ member
1. Есть редактор per-package: одна строка на пакет с тумблером `member` и полем `overrideSourceText`. _(R7.2)_
2. При вводе `overrideSourceText` `member` принудительно `true` и снятие галочки заблокировано, пока есть текст. _(R7.3)_
3. Поля цены НЕТ нигде в override. _(R7.4)_

### TC-UI-20 — Пять ключей ошибок формул рендерят локализованное сообщение (не сырой ключ)
1. Отправить формулы, вызывающие каждую из ошибок; для каждого показывается локализованное сообщение: `error.formula.unknown.variable` _(R7.5)_, `error.formula.unknown.work.reference` _(R7.6)_, `error.formula.illegal.operator` _(R7.7)_, `error.formula.cycle` _(R7.8)_, `error.formula.division.by.zero` _(R7.9)_.
2. Сырой ключ ошибки НЕ отображается ни в одном случае (fallback `formulaErrors.*`). _(R7.10)_

## Фича UI-8. Гейтинг маршрутов и i18n (R8)

Покрывает R8.1–R8.7. **Повторяемость:** только чтение (guard-кейсы non-ADMIN — покрываются guard-тестами при отсутствии готовой роли).

### TC-UI-21 — Гейт маршрута ассортимента (PACKAGE_ASSORTMENT)
1. Пользователь без `PACKAGE_ASSORTMENT` READ при переходе на маршрут ассортимента → редирект на `/403`. _(R8.5)_

### TC-UI-22 — Существующие гейты продолжают действовать
1. Без READ на `WORK_CATALOG`/`WORK_PRICES`/`MATERIALS_CONSTRUCTION`/`OFFER_PACKAGES` соответствующие экраны недоступны (редирект на `/403`). _(R8.1, R8.2, R8.3, R8.4)_

### TC-UI-23 — Локализация PL/RU без сырых ключей
1. Переключение локали меняет заголовки, колонки, подписи форм на всех затронутых экранах (работы, цены, конструкционные материалы, ассортимент, формулы, offer-packages). _(R8.6)_
2. Сырые ключи (`workCatalog.*`, `workPrices.*`, `constructionMaterials.*`, `offerPackages.*`, `assortment.*`, `formulaErrors.*`) НЕ отображаются; паритет `pl.json`/`ru.json`. _(R8.7)_

---

## Регрессия

**Повторяемость:** только чтение.

### TC-REG-01 — ABAC-гарды действуют
1. `GET /api/construction-materials`, `/api/work-prices`, `/api/assortment-groups` без токена → **401**.

### TC-REG-02 — Смежные ресурсы не затронуты
1. `GET /api/resources` → присутствуют `WORK_CATALOG`, `WORK_PRICES`, `MATERIALS_CONSTRUCTION`, `OFFER_PACKAGES`, `PACKAGE_ASSORTMENT`.

### TC-REG-03 — Схлопывание материалов идемпотентно и архив сохранён
1. Таблица `construction_material_packages_archive` присутствует; join-таблица `construction_material_packages` отсутствует; повторный прогон миграции ничего не меняет. _(R5.4, R5.9)_

### TC-REG-04 — Форма цены не регрессировала (не зависает)
1. Открыть правку существующей цены на `/catalog/prices` → форма префиллит и выходит из skeleton (регресс R4.2 закрыт). _(R4.2)_

### TC-REG-05 — cost-cell работ и диапазоны материалов независимы от пакетов
1. `GET /api/work-items?size=1000` → `costCell` присутствует и стабилен; `GET /api/construction-materials?size=1000` → `priceRanges` ключёваны по типу и не зависят от пакетов. _(R5.7, R5.8)_

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SETUP-01 | 1 | POST /api/auth/login (ADMIN) | 200 + accessToken |  |  |
| 2 | TC-SETUP-02 | 1-4 | GET work-items/currencies/offer-packages/cm-types | 200 + FK-id |  |  |
| 3 | TC-A1-01 | 1 | POST /api/construction-materials (без offerPackageIds) | 200, без packages |  |  |
| 4 | TC-A1-02 | 1 | GET /api/construction-materials | 200, нет packages |  |  |
| 5 | TC-A1-03 | 1 | GET /api/construction-materials/{id} | 200, без packages/offerPackageIds |  |  |
| 6 | TC-A1-04 | 1 | GET (priceRanges) | ключ типа, min=50 max=200 |  |  |
| 7 | TC-A1-05 | 2 | GET после null/inactive | max=200 (исключены) |  |  |
| 8 | TC-A1-06 | 1 | GET пустой bucket типа | null..null |  |  |
| 9 | TC-A1-07 | 2 | GET после операций с пакетами | те же min/max |  |  |
| 10 | TC-A1-08 | 1 | POST /api/work-material-consumptions (без offerPackageId) | 200 |  |  |
| 11 | TC-A1-09 | 1 | GET /api/work-items (costCell) | construction/finishing присутствуют |  |  |
| 12 | TC-A1-10 | 1-3 | Проверка архива/дропа/идемпотентности | архив есть, join нет, re-run no-op |  |  |
| 13 | TC-A2-01 | 1 | GET /api/work-prices | плоская модель, без pivot |  |  |
| 14 | TC-A2-02 | 1 | POST /api/work-prices (плоский) | 200 + id |  |  |
| 15 | TC-A2-03 | 1 | GET /api/work-prices/{id} | плоские поля |  |  |
| 16 | TC-A2-04 | 1 | PUT /api/work-prices/{id} | 200, обновлено |  |  |
| 17 | TC-A2-05 | 1 | POST невалидный netPrice/FK | 400/4xx |  |  |
| 18 | TC-A3-01 | 1 | POST /api/assortment-groups | 200 + id |  |  |
| 19 | TC-A3-02 | 1 | POST /api/assortment-line-items | 200 + id, qtyRef50 обязателен |  |  |
| 20 | TC-A3-03 | 2 | line item с typicalProductId | provenance, не влияет на цену |  |  |
| 21 | TC-A3-04 | 1 | GET package-zl-m2 | 200 + {value} |  |  |
| 22 | TC-A3-05 | 3 | GET package-zl-m2 после изменения | пересчитан (не кэш) |  |  |
| 23 | TC-A3-06 | 1 | GET package-zl-m2 (пустой пакет) | 200 + {value:0} |  |  |
| 24 | TC-A4-01 | 1 | POST /api/work-volume-formulas | 200 + id/sourceText |  |  |
| 25 | TC-A4-02 | 2 | POST override (present ⇒ member) | member=true, без цены |  |  |
| 26 | TC-A4-03 | 1 | POST формула unknown variable | 4xx + error.formula.unknown.variable |  |  |
| 27 | TC-A4-04 | 1 | POST unknown work reference | 4xx + error.formula.unknown.work.reference |  |  |
| 28 | TC-A4-05 | 1 | POST illegal operator | 4xx + error.formula.illegal.operator |  |  |
| 29 | TC-A4-06 | 1 | Формулы с циклом | 409/4xx + error.formula.cycle |  |  |
| 30 | TC-A4-07 | 1 | POST деление на ноль | 4xx + error.formula.division.by.zero |  |  |
| 31 | TC-A4-08 | 1 | Ошибка при ru/pl | локализованное сообщение, не ключ |  |  |
| 32 | TC-A5-01 | 1 | GET без токена | 401 |  |  |
| 33 | TC-A5-02 | 1 | GET всех ресурсов (ADMIN) | 200 на всех |  |  |
| 34 | TC-A5-03 | 1 | GET assortment-groups без гранта | 403 |  |  |
| 35 | TC-A5-04 | 1 | GET /api/resources | все ресурсы присутствуют |  |  |
| 36 | TC-UI-SETUP-01 | 1 | Логин ADMIN в браузере | редирект в панель |  |  |
| 37 | TC-UI-01 | 2 | /catalog/works три колонки cost-cell | labour + construction + finishing, без pivot |  |  |
| 38 | TC-UI-02 | 1 | labourPrice == null | плейсхолдер |  |  |
| 39 | TC-UI-03 | 1 | диапазон 0..0 | рендер `0..0` |  |  |
| 40 | TC-UI-04 | 2 | селект категории | имя, не #id |  |  |
| 41 | TC-UI-05 | 1 | селект единицы | имя, не #id |  |  |
| 42 | TC-UI-06 | 2 | /catalog/prices три колонки | work/currency/netPrice, без pivot |  |  |
| 43 | TC-UI-07 | 2 | форма цены — 3 поля | work/currency/netPrice, без пакетных |  |  |
| 44 | TC-UI-08 | 2 | правка цены | префилл + выход из skeleton |  |  |
| 45 | TC-UI-09 | 2 | сабмит цены | payload {workItemId,currencyId,netPrice} |  |  |
| 46 | TC-UI-10 | 1-2 | /materials/construction | нет колонки пакетов, один диапазон по типу |  |  |
| 47 | TC-UI-11 | 1 | форма материала | нет мультиселекта пакетов |  |  |
| 48 | TC-UI-12 | 1 | CRUD группы ассортимента | тост + строка |  |  |
| 49 | TC-UI-13 | 2 | line items сгруппированы | заголовки групп по sortOrder |  |  |
| 50 | TC-UI-14 | 1 | типовой продукт | provenance, не влияет на цену |  |  |
| 51 | TC-UI-15 | 1-2 | readout zł/m² | value как есть, 0 для пустого |  |  |
| 52 | TC-UI-16 | 2 | мутация → readout | пересчитан при повторном просмотре |  |  |
| 53 | TC-UI-17 | 1 | точка входа из offer-package | действие + inline readout |  |  |
| 54 | TC-UI-18 | 1 | редактор дефолтной формулы | отправляет только sourceText |  |  |
| 55 | TC-UI-19 | 2 | override present ⇒ member | member=true, без поля цены |  |  |
| 56 | TC-UI-20 | 1-2 | 5 ключей ошибок формул | локализованные сообщения, не ключ |  |  |
| 57 | TC-UI-21 | 1 | маршрут ассортимента без гранта | редирект /403 |  |  |
| 58 | TC-UI-22 | 1 | существующие гейты | редирект /403 без READ |  |  |
| 59 | TC-UI-23 | 1-2 | локали PL/RU | нет сырых ключей, паритет |  |  |
| 60 | TC-REG-01 | 1 | GET без токена | 401 |  |  |
| 61 | TC-REG-02 | 1 | GET /api/resources | все ресурсы присутствуют |  |  |
| 62 | TC-REG-03 | 1 | архив/дроп/идемпотентность | архив есть, join нет, re-run no-op |  |  |
| 63 | TC-REG-04 | 1 | правка цены | не зависает |  |  |
| 64 | TC-REG-05 | 1 | costCell/priceRanges | независимы от пакетов |  |  |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`.

Стратегия повторяемости:
- Часть A (API): `генератор (<prefix>{run-id}) + teardown (DELETE созданных сущностей)`; кейсы «только чтение» (архив/схема, гейтинг, регресс) teardown не требуют.
- Часть B (UI): `генератор (<prefix>{run-id}) + teardown (удаление через UI-диалог/DELETE)`; кейсы «только чтение» teardown не требуют.
