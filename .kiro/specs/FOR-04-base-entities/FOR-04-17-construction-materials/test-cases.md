# Тест-кейсы — FOR-04-17: Construction Materials

Документ описывает пошаговые тест-кейсы для спеки FOR-04-17 (две новые справочника
`ConstructionMaterialType` и `MaterialSeller`, операционная сущность
`ConstructionMaterial` с вычисляемой «вилкой цен», общий сервис хранения изображений
`ImageStorage` + CDN, аддитивная колонка `image` у существующего справочника
`MaterialProducer`, ABAC-матрица трёх ресурсов и три админ-страницы UI).

Спека **смешанная**: основная часть — REST API бэкенда, плюс три страницы UI и
контрол загрузки изображения. Поэтому:

- **API-области** (оба новых справочника, CRUD/валидация/фильтры/вилка цен/загрузка и
  отклонение изображений/ABAC-гранты) исполняются как **API-тесты против поднятого в
  Docker приложения** (`docker compose up`, базовый URL `http://localhost:8080`,
  auth-эндпоинты под `/api/auth`, первый ADMIN — через переменные
  `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`).
- **UI-области** (три админ-страницы + контрол загрузки изображения на форме
  материала и форме производителя) исполняются как **сценарии браузерного движка**
  против `http://localhost:3000`.

**Артефакт прогона** — MD-репорты с таблицами (шаг → запрос/действие → ожидание →
факт → статус). Скриншоты не требуются.

## Подготовка окружения (общая для всех групп)

Предусловия окружения (единые для всех API- и UI-групп):

1. В корне проекта поднят docker-стек: `docker compose up` → `postgres` (:5432),
   `liquibase` (миграции `001`–`059` применены), `backend` (:8080, профиль `docker`),
   `frontend` (:3000).
2. Первый ADMIN создан переменными `FOREMEN_ADMIN_CREATE=true`,
   `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
3. Получен ADMIN-токен: `POST http://localhost:8080/api/auth/login` с телом
   `{ "email": <FOREMEN_ADMIN_EMAIL>, "password": <FOREMEN_ADMIN_PASSWORD> }` →
   `200` + `accessToken`. Далее во всех API-шагах используется заголовок
   `Authorization: Bearer <accessToken>` (если явно не сказано иное).
4. Существуют справочники-зависимости с хотя бы одной активной строкой:
   `MEASUREMENT_UNITS`, `CURRENCIES` (в т.ч. `PLN`), `OFFER_PACKAGES`,
   `MATERIAL_PRODUCERS` (реюз FOR-04-16). Их id получаются через соответствующие
   `GET /api/...` перед сценарием.

### Стратегия повторяемости (общая)

- **Генератор данных.** Для каждого прогона вычисляется `run-id` = метка времени
  или UUID (например `20260101-120000` или короткий uuid). Все создаваемые в
  сценариях `code`, `nameRU`, `namePL` включают `run-id`, чтобы прогоны не
  конфликтовали по уникальному `code` (например `cmt_farba_{run-id}`,
  `seller_leroy_{run-id}`).
- **Teardown.** В конце каждого сценария создания явным `DELETE` удаляются все
  созданные строки (материалы удаляются раньше справочников из-за FK). Для
  сценариев с изображениями teardown дополнительно проверяет удаление объекта из
  бакета (орфан-клинап).
- Каждая группа ниже явно указывает выбранную стратегию в блоке **«Повторяемость»**.

---

## Группа 1 — Справочник ConstructionMaterialType (API)

Ресурс `CONSTRUCTION_MATERIAL_TYPES`, путь `/api/construction-material-types`.
Проверяет Требования 1, 9.

**Повторяемость:** генератор `code = cmt_{slug}_{run-id}` + teardown (`DELETE` всех
созданных типов в конце).

### TC-CMT-01 — CRUD жизненный цикл типа материала

Предусловия: ADMIN-токен получен.

1. `POST /api/construction-material-types` с телом
   `{ "code": "cmt_farba_{run-id}", "nameRU": "Краска", "namePL": "Farba", "active": true }`
   → **`201`/`200`**, тело содержит `id`, `code`, локализованный `name`.
2. `GET /api/construction-material-types/{id}` → **`200`**, `code = cmt_farba_{run-id}`.
3. `GET /api/construction-material-types` → **`200`**, созданный тип присутствует в списке.
4. `PUT /api/construction-material-types/{id}` с телом
   `{ "nameRU": "Краска2", "namePL": "Farba2", "active": false }`
   → **`200`**, `nameRU`/`namePL`/`active` обновлены.
5. `DELETE /api/construction-material-types/{id}` → **`200`/`204`**.
6. `GET /api/construction-material-types/{id}` → **`404`** (запись удалена).

Ожидаемый итог: полный CRUD работает, объект удалён (teardown выполнен шагами 5–6).

### TC-CMT-02 — `code` неизменяем на update

Предусловия: создан тип `cmt_klej_{run-id}` (шаг 1 как в TC-CMT-01).

1. `PUT /api/construction-material-types/{id}` с телом, содержащим другой
   `code` (`"code": "cmt_other_{run-id}"`) и валидные `nameRU`/`namePL`
   → **`200`**.
2. `GET /api/construction-material-types/{id}` → **`200`**, `code` остался
   `cmt_klej_{run-id}` (изменение `code` проигнорировано).
3. Teardown: `DELETE /api/construction-material-types/{id}` → **`200`/`204`**.

### TC-CMT-03 — Локализация `name` (ru / pl-fallback)

Предусловия: создан тип с `nameRU="Краска"`, `namePL="Farba"`.

1. `GET /api/construction-material-types/{id}` с заголовком `Accept-Language: ru`
   → **`200`**, `name = "Краска"`.
2. Тот же `GET` с `Accept-Language: pl` → **`200`**, `name = "Farba"`.
3. Тот же `GET` с `Accept-Language: en` (нет локали) → **`200`**, `name = "Farba"`
   (PL-fallback).
4. Teardown: `DELETE` созданного типа.

### TC-CMT-04 — Валидация create (пустые обязательные поля → 400)

1. `POST /api/construction-material-types` с телом
   `{ "code": "", "nameRU": "", "namePL": "" }` → **`400`**, тело сообщает о
   невалидных `code`/`nameRU`/`namePL`; ничего не создано.
2. `GET /api/construction-material-types?search=...` → в списке нет пустой записи.

---

## Группа 2 — Справочник MaterialSeller (API, поле website)

Ресурс `MATERIAL_SELLERS`, путь `/api/material-sellers`. Проверяет Требования 2, 9.

**Повторяемость:** генератор `code = seller_{slug}_{run-id}` + teardown.

### TC-MS-01 — CRUD с полем website

Предусловия: ADMIN-токен.

1. `POST /api/material-sellers` с телом
   `{ "code": "seller_leroy_{run-id}", "nameRU": "Леруа", "namePL": "Leroy Merlin", "active": true, "website": "https://www.leroymerlin.pl" }`
   → **`201`/`200`**, тело содержит `id` и `website`.
2. `GET /api/material-sellers/{id}` → **`200`**, `website = "https://www.leroymerlin.pl"`.
3. `PUT /api/material-sellers/{id}` с новым `website="https://castorama.pl"` и
   валидными `nameRU`/`namePL` → **`200`**, `website` обновлён.
4. `PUT /api/material-sellers/{id}` с `website=null` → **`200`**, `website` очищен.
5. Teardown: `DELETE /api/material-sellers/{id}` → **`200`/`204`**.

### TC-MS-02 — Граница длины website (255 / 256)

Предусловия: ADMIN-токен, `run-id`.

1. `POST /api/material-sellers` с `website` длиной ровно **255** символов (+ валидные
   поля) → **`201`/`200`** (принято).
2. `POST /api/material-sellers` с `website` длиной **256** символов → **`400`**,
   тело идентифицирует поле `website`; ничего не создано.
3. Teardown: `DELETE` строки, созданной на шаге 1.

### TC-MS-03 — `code` неизменяем + локализация `name`

1. Создать продавца, затем `PUT` с другим `code` → **`200`**; `GET` показывает
   исходный `code` (изменение проигнорировано).
2. `GET` с `Accept-Language: ru` → `name` из `nameRU`; с `pl`/`en` → из `namePL`
   (PL-fallback).
3. Teardown: `DELETE` продавца.

### TC-MS-04 — Валидация create (пустые обязательные поля)

1. `POST /api/material-sellers` с пустыми `code`/`nameRU`/`namePL` → **`400`**;
   ничего не создано.

---

## Группа 3 — Сущность ConstructionMaterial: CRUD, ссылки, валидация (API)

Ресурс `MATERIALS_CONSTRUCTION`, путь `/api/construction-materials`. Проверяет
Требования 3, 4.

**Повторяемость:** генератор `nameRU/namePL = cm_{slug}_{run-id}` + teardown
(материалы удаляются первыми, затем вспомогательные типы/продавцы, если создавались
внутри сценария).

Предусловия (единые для группы): получены id активных строк — `typeId`
(из Группы 1), `unitId`, `currencyId` (`PLN`), минимум один `offerPackageId`,
опционально `producerId`, `sellerId`.

### TC-CM-01 — Полный CRUD материала

1. `POST /api/construction-materials` с телом:
   ```json
   {
     "nameRU": "Материал {run-id}", "namePL": "Materiał {run-id}",
     "typeId": <typeId>, "producerId": <producerId>, "sellerId": <sellerId>,
     "offerPackageIds": [<offerPackageId>],
     "unitId": <unitId>, "currencyId": <currencyId>,
     "purchasePrice": 10.00, "retailGross": 15.00, "retailNet": 12.00,
     "website": "https://example.com/offer", "active": true
   }
   ```
   → **`201`/`200`**, тело содержит `id`, `type`/`unit`/`currency` как `RefDto`,
   `packages` как список `RefDto`, цены.
2. `GET /api/construction-materials/{id}` → **`200`**, все ссылки и цены совпадают.
3. `PUT /api/construction-materials/{id}` — изменить `retailNet=20.00`,
   добавить второй пакет `offerPackageIds:[<p1>,<p2>]` → **`200`**, изменения
   применены.
4. `GET /api/construction-materials` → **`200`**, материал присутствует в списке.
5. Teardown: `DELETE /api/construction-materials/{id}` → **`200`/`204`**;
   повторный `GET` → **`404`**.

### TC-CM-02 — Обязательные поля отсутствуют → 400

1. `POST` без `typeId` → **`400`**, тело называет поле `type`; ничего не создано.
2. `POST` без `unitId` → **`400`**, называет `unit`.
3. `POST` без `currencyId` → **`400`**, называет `currency`.
4. `POST` с пустым `offerPackageIds: []` → **`400`**, называет `packages`.
5. После всех попыток `GET /api/construction-materials` не содержит новых строк.

### TC-CM-03 — Битые ссылки (dangling) → 4xx с указанием поля

Предусловия: заведомо несуществующий id `999999999`.

1. `POST` с `typeId=999999999` (остальные валидны) → **`404`/`400`**, тело называет
   поле `type`; ничего не создано.
2. Аналогично по одному невалидному id для `producerId`, `sellerId`, `unitId`,
   `currencyId`, `offerPackageIds:[999999999]` → каждый раз **`404`/`400`** с
   указанием соответствующего поля; ничего не создано.

### TC-CM-04 — Цены вне диапазона [0.00 .. 9 999 999 999.99] → 400

1. `POST` с `retailNet = -1.00` → **`400`**, называет `retailNet`; не создано.
2. `POST` с `purchasePrice = 10000000000.00` → **`400`**, называет `purchasePrice`.
3. `POST` с `retailGross = 12345678901.99` → **`400`**, называет `retailGross`.

### TC-CM-05 — Валидация website (255 / 256)

1. `POST` c `website` длиной 255 → **`201`/`200`** (принято); teardown удаляет строку.
2. `POST` c `website` длиной 256 → **`400`**, называет `website`; не создано.

### TC-CM-06 — Reference-фильтры списка (FOR-04-01)

Предусловия: созданы 2 материала — M1 с `type=T1, packages=[P1]`, M2 с
`type=T2, packages=[P1,P2]`.

1. `GET /api/construction-materials?filter=type.id==<T1>` → **`200`**, только M1.
2. `GET /api/construction-materials?filter=packages.id==<P2>` → **`200`**, только M2
   (коллекционный путь → JOIN + distinct, без дублей).
3. `GET .../?filter=currency.id==<PLN>` → **`200`**, оба материала.
4. Teardown: `DELETE` M1, M2.

### TC-CM-07 — Локализация `name` материала

1. `GET /api/construction-materials/{id}` с `Accept-Language: ru` → `name` из
   `nameRU`; с `pl`/`en` → из `namePL`.
2. Teardown: `DELETE` материала.

---

## Группа 4 — Каскад членства в пакетах (API)

Проверяет Требование 5. Здесь удаляется `OfferPackage`, поэтому сценарий выполняется
на **временных** пакетах, создаваемых внутри сценария.

**Повторяемость:** генератор временных пакетов и материалов на `run-id` + teardown
(что не удалилось каскадом — удаляется явно).

### TC-CASCADE-01 — Удаление пакета: чистка join и удаление «нулевых» материалов

Предусловия: созданы временные пакеты `P1`, `P2`; материалы:
- `M_single` с `packages=[P1]`;
- `M_multi` с `packages=[P1, P2]`.

1. `DELETE /api/offer-packages/{P1}` → **`200`/`204`**.
2. `GET /api/construction-materials?filter=packages.id==<P1>` → **`200`**, пустой
   список (join-строки на `P1` удалены).
3. `GET /api/construction-materials/{M_single}` → **`404`** (материал остался без
   пакетов и удалён — Требование 5.3).
4. `GET /api/construction-materials/{M_multi}` → **`200`**, `packages=[P2]`
   (материал с оставшимся пакетом не тронут — Требование 5.4).
5. Teardown: `DELETE` `M_multi`, `P2`.

---

## Группа 5 — Вычисляемая вилка цен /price-ranges (API)

Путь `GET /api/construction-materials/price-ranges` (опционально `?packageId=&typeId=`).
Проверяет Требование 6.

**Повторяемость:** генератор материалов на `run-id` + teardown всех созданных
материалов.

### TC-PR-01 — MIN..MAX retailNet по паре (пакет, тип)

Предусловия: тип `T`, пакет `P`; созданы активные материалы типа `T`,
`packages=[P]`, с `retailNet` = 10.00, 25.00, 15.00.

1. `GET .../price-ranges?packageId=<P>&typeId=<T>` → **`200`**,
   `{ min: 10.00, max: 25.00 }`.
2. Teardown: `DELETE` созданных материалов.

### TC-PR-02 — Фан-аут: материал в нескольких пакетах

Предусловия: тип `T`; пакеты `P1`, `P2`; материал `M` с `packages=[P1,P2]`,
`retailNet=30.00`; материал `M2` с `packages=[P1]`, `retailNet=5.00`.

1. `GET .../price-ranges?packageId=<P1>&typeId=<T>` → `{ min: 5.00, max: 30.00 }`.
2. `GET .../price-ranges?packageId=<P2>&typeId=<T>` → `{ min: 30.00, max: 30.00 }`
   (M вносит вклад в оба пакета — Требование 6.3).
3. Teardown.

### TC-PR-03 — null retailNet исключается; неактивные исключаются

Предусловия: тип `T`, пакет `P`; материалы: `A` (`retailNet=20.00, active=true`),
`B` (`retailNet=null, active=true`), `C` (`retailNet=100.00, active=false`).

1. `GET .../price-ranges?packageId=<P>&typeId=<T>` → `{ min: 20.00, max: 20.00 }`
   (B с null и неактивный C исключены — Требования 6.2, 6.5).
2. Teardown.

### TC-PR-04 — Пустая вилка для пары без цен

Предусловия: пара (`P`, `T`), у которой нет активных материалов с непустым `retailNet`.

1. `GET .../price-ranges?packageId=<P>&typeId=<T>` → **`200`**, пустая вилка
   (`min=null, max=null`) — Требование 6.6.

### TC-PR-05 — Детерминированность и min ≤ max

1. Дважды подряд `GET .../price-ranges` с одинаковым набором данных → идентичные
   `min`/`max` (Требование 6.7).
2. В каждой непустой вилке `min ≤ max`.

### TC-PR-06 — Вилка в списочном DTO

1. `GET /api/construction-materials` → **`200`**; в каждой строке присутствует
   payload вилки на её пары (`package`, `type`), UI не делает второй запрос.

---

## Группа 6 — ImageStorage: загрузка, отклонение, орфан-клинап (API)

Путь `POST /api/images` (`multipart/form-data`, `entityKind`/`resource`). Проверяет
Требование 7.

**Повторяемость:** каждая загрузка создаёт объект с уникальным ключом (uuid в
имени); teardown удаляет объект (детач/делит сущности → орфан-клинап) и созданную
сущность.

### TC-IMG-01 — Успешная загрузка + резолв CDN URL

Предусловия: image-storage сконфигурирован (`isConfigured()=true`).

1. `POST /api/images` c `resource=MATERIALS_CONSTRUCTION`, файл `png` допустимого
   размера → **`200`**, тело `{ objectKey, imageUrl }`, `objectKey` начинается с
   префикса `construction-materials/` (Требование 7.5).
2. `POST /api/construction-materials` с `image=<objectKey>` → **`201`/`200`**.
3. `GET /api/construction-materials/{id}` → **`200`**, `imageUrl = cdnBase + "/" +
   objectKey`, а сам `objectKey` в DTO не отдаётся (только `imageUrl`).
4. Teardown: `DELETE` материала (объект должен быть удалён из бакета орфан-клинапом).

### TC-IMG-02 — Отклонение недопустимого типа контента

1. `POST /api/images` с файлом `application/pdf` (или `text/plain`) → **`400`**,
   объект не сохранён (Требование 7.6).

### TC-IMG-03 — Отклонение превышения размера

1. `POST /api/images` с файлом больше сконфигурированного максимума → **`400`**,
   объект не сохранён (Требование 7.7).

### TC-IMG-04 — Орфан-клинап при замене / детаче / удалении

Предусловия: материал `M` c `image=K1`.

1. `PUT /api/construction-materials/{M}` c `image=K2` (другой ключ) → **`200`**;
   объект `K1` удалён из бакета (Требование 7.8).
2. `PUT /api/construction-materials/{M}` c `image=null` → **`200`**; объект `K2`
   удалён (Требование 7.9).
3. Создать `M2` c `image=K3`, затем `DELETE /api/construction-materials/{M2}` →
   объект `K3` удалён.
4. Teardown: `DELETE` `M`.

### TC-IMG-05 — Периодическая реконсиляция удаляет неучтённые объекты

Предусловия: в бакете есть объект `K_orphan`, на который не ссылается ни одна строка БД.

1. Дождаться/инициировать запуск `ImageReconciliationJob` → `K_orphan` удалён из
   бакета; объекты, на которые есть ссылки в БД, сохранены (Требование 7.10).

### TC-IMG-06 — Storage не сконфигурирован: graceful reject, чтение без картинок

Предусловия: image-storage выключен (`DisabledImageStorage`, `isConfigured()=false`).

1. `POST /api/images` → **`400`** (graceful reject, Требование 7.12).
2. `GET /api/construction-materials/{id}` для строки с `image=null` → **`200`**,
   `imageUrl=null`, чтение/листинг работают.

---

## Группа 7 — MaterialProducer: реюз изображения (API)

Проверяет Требование 8 (аддитивная колонка `image` у существующего справочника
`MATERIAL_PRODUCERS`, без пересоздания ресурса/сущности/страницы).

**Повторяемость:** генератор `code = prod_{slug}_{run-id}` + teardown.

### TC-PROD-IMG-01 — Round-trip ключа объекта → CDN URL

Предусловия: image-storage сконфигурирован.

1. `POST /api/images` c `resource=MATERIAL_PRODUCERS`, `png` → **`200`**,
   `objectKey` начинается с `producers/` (Требование 7.5).
2. `POST /api/material-producers` (или `PUT` существующего) с `image=<objectKey>`
   → **`201`/`200`**.
3. `GET /api/material-producers/{id}` → **`200`**, `imageUrl = cdnBase + "/" +
   objectKey`.
4. Teardown: `DELETE`/детач производителя (объект удаляется орфан-клинапом).

---

## Группа 8 — ABAC-матрица трёх ресурсов (API)

Проверяет Требование 9. Матрица:

| Ресурс | ADMIN | MANAGER | FOREMAN | WORKER | FINANCIER | CLIENT |
|--------|-------|---------|---------|--------|-----------|--------|
| CONSTRUCTION_MATERIAL_TYPES | CRUD | R | R | R | R | — |
| MATERIAL_SELLERS | CRUD | R | R | R | R | — |
| MATERIALS_CONSTRUCTION | CRUD | C R U | R | R | R | — |

**Повторяемость:** генератор пользователей/строк на `run-id` + teardown; логины
выполняются под сервисными аккаунтами ролей (или создаются на `run-id`).

### TC-ABAC-01 — ADMIN имеет полный доступ

1. Под ADMIN: `POST`/`GET`/`PUT`/`DELETE` по всем трём ресурсам → успешные коды
   (`2xx`).

### TC-ABAC-02 — MANAGER: READ везде, C/U на материалах, но НЕ DELETE

1. Под MANAGER: `GET` по всем трём → **`200`**.
2. `POST`/`PUT /api/construction-materials` → **`2xx`** (CREATE/UPDATE разрешены).
3. `DELETE /api/construction-materials/{id}` → **`403`** (DELETE только у ADMIN —
   Требования 9.3, 9.4).
4. `POST /api/construction-material-types` → **`403`** (у справочников MANAGER
   только READ).

### TC-ABAC-03 — FOREMAN/WORKER/FINANCIER: только READ

1. Под каждой из ролей: `GET` по всем трём ресурсам → **`200`**.
2. Любой `POST`/`PUT`/`DELETE` → **`403`**.

### TC-ABAC-04 — CLIENT: доступ запрещён (deny-by-default)

1. Под CLIENT: `GET`/`POST`/`PUT`/`DELETE` по всем трём ресурсам → **`403`**
   (гранта нет).

### TC-ABAC-05 — Ресурсы засеяны с полными полями

1. Проверить (через seed/DB или админ-API), что строки ресурсов
   `CONSTRUCTION_MATERIAL_TYPES`, `MATERIAL_SELLERS`, `MATERIALS_CONSTRUCTION`
   присутствуют с непустыми `code`, `name_ru`, `name_pl`, `description_ru`,
   `description_pl` (Требование 9.1).

---

## Группа 9 — UI: три админ-страницы + контрол изображения (браузер)

Браузерные сценарии против `http://localhost:3000`. Проверяет Требования 10, 13.
Предусловия: залогинен ADMIN (у которого есть CREATE/UPDATE/DELETE), image-storage
сконфигурирован для сценариев с картинкой.

**Повторяемость:** генератор данных форм на `run-id` + teardown (удаление созданных
строк через UI-диалог удаления в конце сценария).

### TC-UI-CMT-01 — Страница ConstructionMaterialTypes

1. Перейти на `/construction-material-types` → страница открыта, `DataTable`
   отрисован с колонками `code`, `name`, `active` (active — локализованный бейдж).
2. Нажать «Создать», заполнить форму (`code`, `nameRU`, `namePL`, `active`),
   сохранить → строка появилась в списке, показан тост.
3. Открыть редактирование → поле `code` доступно только для чтения.
4. Пустой список (по несуществующему фильтру) → отрисован локализованный empty-state.
5. Проверить, что ни один сырой i18n-ключ (`constructionMaterialTypes.*`) не виден.
6. Teardown: удалить созданную строку через диалог подтверждения.

### TC-UI-MS-01 — Страница MaterialSellers (колонка website)

1. Перейти на `/material-sellers` → колонки `code`, `name`, `active`, **`website`**.
2. Создать продавца с `website` через форму → строка отображает `website`; тост.
3. Редактирование: `code` read-only, `website` редактируется.
4. Проверить empty-state и отсутствие сырых ключей `materialSellers.*`.
5. Teardown: удалить строку.

### TC-UI-CM-01 — Страница ConstructionMaterials + вилка цен

1. Перейти на `/materials/construction` → колонки `name`, `type`, `producer`,
   `seller`, `packages`, `unit`, `currency`, `retailNet` и отображение вычисленной
   вилки цен по паре (`package`, `type`).
2. Создать материал: `name`, обязательный `type`, опциональные `producer`/`seller`,
   обязательный мультиселект `packages` (≥ 1), обязательные `unit`/`currency`, три
   поля цен, опциональный `website`, тумблер `active`. Форма **без поля `code`**.
3. Сабмит с пустыми `packages` / без `type` → сабмит заблокирован, введённые
   значения сохранены, показаны локализованные сообщения по каждому невалидному полю.
4. Проверить empty-state, отсутствие сырых ключей `constructionMaterials.*`.
5. Teardown: удалить материал.

### TC-UI-CM-02 — Контрол загрузки изображения на форме материала

Предусловия: image-storage сконфигурирован.

1. На форме материала выбрать изображение (`png`/`jpeg`/`webp`) → контрол загружает
   файл через `POST /api/images`, после успеха отображает CDN-превью.
2. Сохранить материал → возвращённый `objectKey` привязан к сущности.
3. Открыть материал с изображением → отрисовано разрешённое CDN-изображение.
4. Teardown: удалить материал.

### TC-UI-PROD-01 — Контрол изображения на форме производителя

1. Открыть существующую форму `MaterialProducer` → присутствует контрол загрузки
   изображения (Требование 10.7).
2. Загрузить изображение → загрузка через `POST /api/images`, CDN-превью показано,
   ключ привязан при сохранении.
3. Производитель с изображением отрисовывает разрешённое CDN-изображение.

### TC-UI-NAV-01 — Пункт меню «Стройматериалы» + гварды маршрутов

1. В секции «Склад / Warehouse» присутствует пункт с ключом
   `nav.constructionMaterials`, ведущий на `/materials/construction`, ABAC-gated на
   `MATERIALS_CONSTRUCTION` READ.
2. Под пользователем без READ на `MATERIALS_CONSTRUCTION` пункт скрыт, а прямой
   переход на `/materials/construction` заблокирован интеримным гвардом.
3. Под пользователем без `CREATE/UPDATE/DELETE` соответствующие кнопки скрыты
   (`usePermission`).
4. Проверить `nav.constructionMaterials` на паритет в `pl.json` и `ru.json`.

---

## Регрессия

Раздел проверяет ранее закрытые дефекты, найденные при реализации спеки, и критичные
сквозные пути. **Повторяемость:** генератор на `run-id` + teardown.

### TC-REG-01 — MapStruct не применяет toCdnUrl к текстовым полям производителя

Закрытый баг: маппер производителя по имени свойства автоматически прогонял
`toCdnUrl(...)` не только на `image`, но и на `code`/`nameRU`/`namePL`, портя их
значения. Регресс:

1. `POST`/`PUT /api/material-producers` с `code`, `nameRU`, `namePL` и `image`
   (объект-ключ) → **`200`**.
2. `GET /api/material-producers/{id}` → **`200`**: `code`, `nameRU`, `namePL`
   возвращаются **без изменений** (не превращены в CDN URL); только `imageUrl`
   является резолвом `toCdnUrl(image)`.
3. Teardown: удалить/детач производителя.

### TC-REG-02 — Нет ложного 404 на PUT, когда image = null

Закрытый баг: `PUT` материала с `image=null` вызывал орфан-клинап/резолв так, что
возвращался ложный `404`. Регресс:

1. Создать материал `M` без изображения (`image` отсутствует/`null`).
2. `PUT /api/construction-materials/{M}` с телом, где `image=null`, прочие поля
   валидны → **`200`** (а не `404`); материал обновлён.
3. `GET /api/construction-materials/{M}` → **`200`**, `imageUrl=null`.
4. Повторить `PUT` с `image=null` идемпотентно → снова **`200`**.
5. Teardown: `DELETE` `M`.

### TC-REG-03 — Сквозной путь: справочники → материал → вилка → удаление пакета

1. Создать тип `T`, продавца `S`, материал `M` (`type=T`, `seller=S`,
   `packages=[P]`, `retailNet=10.00`) → все `2xx`.
2. `GET .../price-ranges?packageId=<P>&typeId=<T>` → `{ min:10.00, max:10.00 }`.
3. `DELETE` пакета `P` (временного) → `M` без пакетов удалён (каскад/чистка).
4. `GET .../price-ranges?packageId=<P>&typeId=<T>` → пустая вилка.
5. Teardown: `DELETE` типа `T`, продавца `S`.

### TC-REG-04 — Чистый старт под PermissionAnnotationValidator

1. Приложение стартует без ошибок при полностью аннотированных контроллерах, включая
   кастомные хендлеры `/price-ranges` и `/api/images` (Требование 9.6, 12.11);
   проверяется отсутствием фейла старта в логах бэкенда `docker compose`.

---

## Шаблон MD-репорта прогона

Заполняется по итогам каждого прогона (отдельная таблица на группу или общая):

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-CMT-01 | 1 | POST /api/construction-material-types | 201 + id/code/name |  |  |
| 2 | TC-CMT-01 | 2 | GET /api/construction-material-types/{id} | 200, code совпадает |  |  |
| 3 | TC-CM-02  | 4 | POST c пустым offerPackageIds | 400, поле packages |  |  |
| 4 | TC-PR-01  | 1 | GET /price-ranges?packageId&typeId | 200 {min:10.00,max:25.00} |  |  |
| 5 | TC-IMG-02 | 1 | POST /api/images (pdf) | 400, объект не сохранён |  |  |
| 6 | TC-ABAC-02| 3 | DELETE /api/construction-materials/{id} под MANAGER | 403 |  |  |
| 7 | TC-UI-CM-01 | 1 | Открыть /materials/construction | DataTable + колонки + вилка |  |  |
| 8 | TC-REG-02 | 2 | PUT материала с image=null | 200 (не 404) |  |  |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`.
Стратегия повторяемости: `<генератор run-id + teardown>`. Тип прогона:
`<API против http://localhost:8080 | UI против http://localhost:3000>`.
