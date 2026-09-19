# Тест-кейсы — FOR-04-18: Finishing Materials

Документ описывает пошаговые тест-кейсы для спеки FOR-04-18 (одна новая
операционная сущность `FinishingMaterial` с богатыми отделочными полями: ссылки
`category`/`material`/`type`/`producer`/`unit` + многозначные `packages`, свободные
поля `model`/`sku`/`features`, три цены `purchasePrice`/`retailGross`/`retailNet`,
`link` и опциональное `photo`; реюз общего сервиса хранения изображений
`ImageStorage` + CDN; ABAC-ресурс `MATERIALS_FINISHING`; большой CSV-сид из
`docs/Materiały pakiety - Lista.csv`; одна админ-страница UI `/materials/finishing`).

В отличие от FOR-04-17, у `FinishingMaterial` **нет** `code` и **нет** локализованного
`name` (человекочитаемая метка `label` вычисляется на чтении из `material` + `model`),
**нет** поля цены за м²/единицу и **нет** вычисляемой «вилки цен» / эндпоинта
`/price-ranges`.

Спека **смешанная**: основная часть — REST API бэкенда, плюс одна страница UI и
контрол загрузки изображения. Поэтому:

- **API-области** (CRUD/валидация ссылок/reference-фильтры/каскад пакетов/загрузка и
  отклонение фото/CSV-сид/ABAC-гранты) исполняются как **API-тесты против поднятого
  в Docker приложения** (`docker compose up`, базовый URL `http://localhost:8080`,
  auth-эндпоинты под `/api/auth`, первый ADMIN — через переменные
  `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`).
- **UI-область** (страница `/materials/finishing` + контрол загрузки фото на форме
  материала) исполняется как **сценарий браузерного движка** против
  `http://localhost:3000`.

**Артефакт прогона** — MD-репорты с таблицами (шаг → запрос/действие → ожидание →
факт → статус). Скриншоты не требуются.

## Подготовка окружения (общая для всех групп)

Предусловия окружения (единые для всех API- и UI-групп):

1. В корне проекта поднят docker-стек: `docker compose up` → `postgres` (:5432),
   `liquibase` (миграции `001`–`062` применены, включая `060`–`062` этой спеки),
   `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Первый ADMIN создан переменными `FOREMEN_ADMIN_CREATE=true`,
   `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
3. Получен ADMIN-токен: `POST http://localhost:8080/api/auth/login` с телом
   `{ "email": <FOREMEN_ADMIN_EMAIL>, "password": <FOREMEN_ADMIN_PASSWORD> }` →
   `200` + `accessToken`. Далее во всех API-шагах используется заголовок
   `Authorization: Bearer <accessToken>` (если явно не сказано иное).
4. Существуют справочники-зависимости с хотя бы одной активной строкой (реюз
   FOR-04-16 / FOR-04-10 / FOR-04-02, в т.ч. засеянные CSV-сидом `062`):
   `MATERIAL_CATEGORIES`, `MATERIALS`, `MATERIAL_TYPES`, `MATERIAL_PRODUCERS`,
   `OFFER_PACKAGES` (`Budget`/`Standard`/`Lux`), `MEASUREMENT_UNITS` (в т.ч. `m2`).
   Их id получаются через соответствующие `GET /api/...` перед сценарием.

### Стратегия повторяемости (общая)

- **Генератор данных.** Для каждого прогона вычисляется `run-id` = метка времени
  или UUID (например `20260101-120000` или короткий uuid). Все создаваемые в
  сценариях `model`/`sku` включают `run-id`, чтобы прогоны не конфликтовали и
  созданные строки было легко найти/удалить (например `model = "FM {run-id}"`,
  `sku = "sku-{run-id}"`).
- **Teardown.** В конце каждого сценария создания явным `DELETE` удаляются все
  созданные строки (материалы удаляются раньше вспомогательных пакетов из-за FK).
  Для сценариев с фото teardown дополнительно проверяет удаление объекта из бакета
  (орфан-клинап).
- Каждая группа ниже явно указывает выбранную стратегию в блоке **«Повторяемость»**.

> Примечание: у `FinishingMaterial` нет собственного уникального ключа `code`, поэтому
> идентификация созданных строк в списке ведётся по `model = "... {run-id}"` (через
> `search`/reference-фильтры), а не по `code`.

---

## Группа 1 — FinishingMaterial: CRUD, ссылки и валидация (API)

Ресурс `MATERIALS_FINISHING`, путь `/api/finishing-materials`. Проверяет
Требования 1, 2.

**Повторяемость:** генератор `model = "FM {slug} {run-id}"` + teardown (материалы
удаляются первыми, затем вспомогательные пакеты, если создавались внутри сценария).

Предусловия (единые для группы): получены id активных строк — `categoryId`,
`materialId`, `unitId` (в т.ч. `m2`), минимум один `offerPackageId`, опционально
`typeId`, `producerId`.

### TC-FM-01 — Полный CRUD материала

1. `POST /api/finishing-materials` с телом:
   ```json
   {
     "categoryId": <categoryId>, "materialId": <materialId>,
     "typeId": <typeId>, "producerId": <producerId>,
     "offerPackageIds": [<offerPackageId>],
     "unitId": <unitId>,
     "model": "FM CRUD {run-id}", "sku": "sku-{run-id}", "features": "Cechy",
     "purchasePrice": 10.00, "retailGross": 15.00, "retailNet": 12.00,
     "link": "https://example.com/offer", "active": true
   }
   ```
   → **`201`/`200`**, тело содержит `id`, `category`/`material`/`type`/`producer`/`unit`
   как `RefDto`, `packages` как список `RefDto`, три цены, `link`, `photoUrl=null`,
   и **нет** полей `code`/`name`.
2. `GET /api/finishing-materials/{id}` → **`200`**, все ссылки, цены, `model`/`sku`
   совпадают; присутствует производный `label` (`material + " — " + model`).
3. `PUT /api/finishing-materials/{id}` — изменить `retailNet=20.00`, добавить второй
   пакет `offerPackageIds:[<p1>,<p2>]` → **`200`**, изменения применены.
4. `GET /api/finishing-materials` → **`200`**, материал присутствует в списке.
5. Teardown: `DELETE /api/finishing-materials/{id}` → **`200`/`204`**; повторный
   `GET` → **`404`**.

Ожидаемый итог: полный CRUD работает, объект удалён (teardown выполнен шагами 5).

### TC-FM-02 — Производный `label` на чтении (Требование 2.9)

Предусловия: создан материал с известным `materialId` (например `material="Podłoga"`)
и `model="FM Label {run-id}"`.

1. `GET /api/finishing-materials/{id}` с `Accept-Language: ru` → **`200`**,
   `label = "<material.name(ru)> — FM Label {run-id}"`.
2. Создать материал с пустым/отсутствующим `model` → `GET` → `label` равен только
   имени `material` (fallback без `" — "`).
3. Убедиться, что `label` **не** является отдельной сортируемой/фильтруемой колонкой
   `name` (сорт/фильтр идут по реальным ссылкам).
4. Teardown: `DELETE` созданных материалов.

### TC-FM-03 — Обязательные поля отсутствуют → 400 (Требование 2.3)

1. `POST` без `categoryId` → **`400`**, тело называет поле `category`; ничего не создано.
2. `POST` без `materialId` → **`400`**, называет `material`.
3. `POST` без `unitId` → **`400`**, называет `unit`.
4. `POST` с пустым `offerPackageIds: []` → **`400`**, называет `packages`.
5. После всех попыток `GET /api/finishing-materials?search=FM ... {run-id}` не
   содержит новых строк.

### TC-FM-04 — Битые ссылки (dangling) → 4xx с указанием поля (Требование 2.4)

Предусловия: заведомо несуществующий id `999999999`.

1. `POST` с `categoryId=999999999` (остальные валидны) → **`404`/`400`**, тело
   называет поле `category`; ничего не создано.
2. Аналогично по одному невалидному id для `materialId`, `typeId`, `producerId`,
   `unitId`, `offerPackageIds:[999999999]` → каждый раз **`404`/`400`** с указанием
   соответствующего поля; ничего не создано.

### TC-FM-05 — Цены вне диапазона [0.00 .. 9 999 999 999.99] → 400 (Требование 2.5)

1. `POST` с `retailNet = -1.00` → **`400`**, называет `retailNet`; не создано.
2. `POST` с `purchasePrice = 10000000000.00` → **`400`**, называет `purchasePrice`.
3. `POST` с `retailGross = 12345678901.99` → **`400`**, называет `retailGross`.
4. `POST` со всеми тремя ценами `null` → **`201`/`200`** (цены nullable); teardown
   удаляет строку.

### TC-FM-06 — Границы длины `model`/`sku` (255/256) и `link` (1024/1025)

Требования 2.6, 2.7.

1. `POST` с `model` длиной ровно **255** символов (+ валидные поля) → **`201`/`200`**;
   teardown удаляет строку.
2. `POST` с `model` длиной **256** → **`400`**, называет `model`; не создано.
3. `POST` с `sku` длиной **256** → **`400`**, называет `sku`; не создано.
4. `POST` с `link` длиной ровно **1024** → **`201`/`200`**; teardown удаляет строку.
5. `POST` с `link` длиной **1025** → **`400`**, называет `link`; не создано.

### TC-FM-07 — Опциональные `type`/`producer` (Требования 1.4, 1.5)

1. `POST` без `typeId` и без `producerId`, но с валидными обязательными полями →
   **`201`/`200`**; `GET` → `type=null`, `producer=null`.
2. Teardown: `DELETE` материала.

---

## Группа 2 — Reference-фильтры списка (API)

Проверяет Требование 2.10. Reference-фильтры едут на грамматике FOR-04-01
(`category.id`, `material.id`, `type.id`, `producer.id`, `unit.id`, `packages.id`).

**Повторяемость:** генератор `model = "FM Filter {run-id}"` + teardown.

### TC-FILT-01 — Фильтры по одиночным ссылкам

Предусловия: созданы 2 материала — M1 (`type=T1`, `producer=Pr1`, `packages=[P1]`),
M2 (`type=T2`, `producer=Pr2`, `packages=[P1,P2]`).

1. `GET /api/finishing-materials?filter=type.id==<T1>` → **`200`**, только M1.
2. `GET /api/finishing-materials?filter=producer.id==<Pr2>` → **`200`**, только M2.
3. `GET /api/finishing-materials?filter=category.id==<C>` → **`200`**, оба (если общая
   категория).
4. `GET /api/finishing-materials?filter=unit.id==<m2>` → **`200`**, оба (если общий unit).
5. Teardown: `DELETE` M1, M2.

### TC-FILT-02 — Коллекционный фильтр `packages.id` (JOIN + distinct)

Предусловия: как в TC-FILT-01.

1. `GET /api/finishing-materials?filter=packages.id==<P2>` → **`200`**, только M2 без
   дублей (коллекционный путь → JOIN + distinct).
2. `GET /api/finishing-materials?filter=packages.id==<P1>` → **`200`**, M1 и M2,
   каждый ровно один раз (нет дублей от JOIN).
3. Teardown: `DELETE` M1, M2.

---

## Группа 3 — Каскад членства в пакетах (API)

Проверяет Требование 3. Здесь удаляется `OfferPackage`, поэтому сценарий выполняется
на **временных** пакетах, создаваемых внутри сценария.

**Повторяемость:** генератор временных пакетов и материалов на `run-id` + teardown
(что не удалилось каскадом — удаляется явно).

### TC-CASCADE-01 — Удаление пакета: чистка join и удаление «нулевых» материалов

Предусловия: созданы временные пакеты `P1`, `P2`; материалы:
- `M_single` с `packages=[P1]`;
- `M_multi` с `packages=[P1, P2]`.

1. `DELETE /api/offer-packages/{P1}` → **`200`/`204`**.
2. `GET /api/finishing-materials?filter=packages.id==<P1>` → **`200`**, пустой список
   (join-строки на `P1` удалены — Требование 3.2).
3. `GET /api/finishing-materials/{M_single}` → **`404`** (материал остался без пакетов
   и удалён — Требование 3.3).
4. `GET /api/finishing-materials/{M_multi}` → **`200`**, `packages=[P2]` (материал с
   оставшимся пакетом не тронут — Требование 3.4).
5. Teardown: `DELETE` `M_multi`, `P2`.

### TC-CASCADE-02 — `packages` обязателен (≥ 1) на write-пути (Требование 3.1)

1. `POST /api/finishing-materials` с `offerPackageIds: []` → **`400`**, называет
   `packages`; не создано.
2. `PUT` существующего материала с `offerPackageIds: []` → **`400`**; предыдущий
   набор пакетов сохранён (ничего не изменено).
3. Teardown: `DELETE` материала.

---

## Группа 4 — Фото через общий ImageStorage: загрузка, отклонение, резолв, орфан-клинап (API)

Путь `POST /api/images` (`multipart/form-data`, `entityKind=finishing-materials`,
`resource=MATERIALS_FINISHING`). Проверяет Требование 4.

**Повторяемость:** каждая загрузка создаёт объект с уникальным ключом (uuid в имени);
teardown удаляет объект (детач/делит сущности → орфан-клинап) и созданную сущность.

### TC-IMG-01 — Успешная загрузка + резолв CDN URL (Требования 4.2, 4.3, 4.4)

Предусловия: image-storage сконфигурирован (`isConfigured()=true`).

1. `POST /api/images` c `entityKind=finishing-materials`,
   `resource=MATERIALS_FINISHING`, файл `png` допустимого размера → **`200`**, тело
   `{ objectKey, imageUrl }`, `objectKey` начинается с префикса `finishing-materials/`.
2. `POST /api/finishing-materials` с `photo=<objectKey>` + валидные обязательные поля
   → **`201`/`200`**.
3. `GET /api/finishing-materials/{id}` → **`200`**, `photoUrl = cdnBase + "/" +
   objectKey`, а сам `objectKey`/`photo` в DTO не отдаётся (только `photoUrl`).
4. Teardown: `DELETE` материала (объект должен быть удалён из бакета орфан-клинапом).

### TC-IMG-02 — Отклонение недопустимого типа контента (Требование 4.7)

1. `POST /api/images` с файлом `application/pdf` (или `text/plain`),
   `entityKind=finishing-materials` → **`400`**, объект не сохранён.

### TC-IMG-03 — Отклонение превышения размера (Требование 4.7)

1. `POST /api/images` с файлом больше сконфигурированного максимума → **`400`**,
   объект не сохранён.

### TC-IMG-04 — Орфан-клинап при замене / детаче / удалении (Требование 4.6)

Предусловия: материал `M` c `photo=K1`.

1. `PUT /api/finishing-materials/{M}` c `photo=K2` (другой ключ) → **`200`**; объект
   `K1` удалён из бакета.
2. `PUT /api/finishing-materials/{M}` c `photo=null` → **`200`**; объект `K2` удалён.
3. Создать `M2` c `photo=K3`, затем `DELETE /api/finishing-materials/{M2}` → объект
   `K3` удалён.
4. Teardown: `DELETE` `M`.

### TC-IMG-05 — ImageReferenceLookup учитывает `finishing_materials.photo` (Требование 4.5)

Предусловия: материал `M` c `photo=K_ref`; в бакете есть орфан `K_orphan` без ссылок.

1. Инициировать/дождаться `ImageReconciliationJob` → `K_ref` **сохранён** (на него
   ссылается `finishing_materials.photo`), `K_orphan` удалён.
2. `PUT /api/finishing-materials/{M}` c `photo=null` → на следующем прогоне реконсиляции
   `K_ref` становится орфаном и удаляется.
3. Teardown: `DELETE` `M`.

### TC-IMG-06 — Storage не сконфигурирован: graceful reject, чтение без картинок

Предусловия: image-storage выключен (`isConfigured()=false`).

1. `POST /api/images` → **`400`** (graceful reject).
2. `GET /api/finishing-materials/{id}` для строки с `photo=null` → **`200`**,
   `photoUrl=null`, чтение/листинг работают.

---

## Группа 5 — Верификация большого CSV-сида (API)

Проверяет Требование 6 (сид `062` из `docs/Materiały pakiety - Lista.csv`, 581 строка).

**Повторяемость:** сид применяется Liquibase при старте стека и идемпотентен —
дополнительной чистки не требуется. **Стратегия: read-only проверка + идемпотентность**
(отдельного teardown/генератора не нужно — сценарии не создают новых строк, только
читают засеянные).

### TC-CSV-01 — Репрезентативные строки засеяны с резолвом ссылок

Предусловия: сид `062` применён; получен `count` = `GET /api/finishing-materials/count`.

1. `GET /api/finishing-materials?filter=material.id==<Podłoga>` → **`200`**, среди
   строк присутствует Egger `Dąb North piaskowy EL2157 AC4 8 mm` (`category=Podłoga`,
   `type=Laminat`, `producer=Egger`, `unit=m2`, `packages=[Budget]`).
2. Проверить, что `category`/`material`/`type`/`producer`/`unit` отданы как `RefDto`
   с резолвнутыми id (не сырые строки CSV) — Требования 6.1, 6.5.

### TC-CSV-02 — Comma-decimal цены и `retailNet`-fallback из `Cena detal / m2`

Требования 6.2, 6.3.

1. Найти строку Egger `Dąb North piaskowy EL2157...` (в CSV `Cena detal netto` пуст,
   `Cena detal / m2 = "53,61"`) → `GET` показывает `retailNet = 53.61` (comma →
   dot; fallback из `Cena detal / m2`).
2. Найти строку с пустыми и `Cena detal netto`, и `Cena detal / m2` → `retailNet = null`.
3. Проверить, что нет отдельного per-m² поля (только три цены; `retailNet` наполнен
   fallback-ом).

### TC-CSV-03 — Нормализация многозначного `Pakiet` (Требование 6.4)

1. Найти строку с `Pakiet = "Budget, Standard"` (Arbiton `Podkład Multiprotec ...`) →
   `packages = {Budget, Standard}`.
2. Найти строку с `Pakiet = "Budget, Lux, Standard"` → `packages = {Budget, Lux,
   Standard}` (порядок неважен, без дублей).
3. Убедиться, что токены с артефактами (`+ `, кавычки, `Standart`) нормализованы:
   `Standart → Standard`; лишние пробелы/кавычки срезаны.

### TC-CSV-04 — Пропуск строк без `category` и без резолвимой обязательной ссылки

Требования 6.6, 6.9.

1. Проверить, что общее число засеянных строк < 581 (пропущены ~6 строк с пустой
   `Kategoria` и строки без резолвимых `category`/`material`/`unit`).
2. Ни одна засеянная строка не имеет пустого `category`/`material`/`unit`.

### TC-CSV-05 — Идемпотентность повторного применения (Требование 6.7)

1. Зафиксировать `count1 = GET /api/finishing-materials/count`.
2. Перезапустить `liquibase`/стек (повторный прогон сида `062`) → изменений схемы/данных
   нет (`sqlCheck` + `MARK_RAN`).
3. `count2 = GET /api/finishing-materials/count` → `count2 == count1` (нет дублей).

---

## Группа 6 — ABAC-матрица ресурса MATERIALS_FINISHING (API)

Проверяет Требование 5. Матрица:

| Ресурс | ADMIN | MANAGER | FOREMAN | WORKER | FINANCIER | CLIENT |
|--------|-------|---------|---------|--------|-----------|--------|
| MATERIALS_FINISHING | CRUD | C R U | R | R | R | — |

**Повторяемость:** генератор строк на `run-id` + teardown; логины выполняются под
сервисными аккаунтами ролей (или создаются на `run-id`).

### TC-ABAC-01 — ADMIN имеет полный доступ

1. Под ADMIN: `POST`/`GET`/`PUT`/`DELETE /api/finishing-materials` → успешные коды
   (`2xx`).
2. Teardown: удалённая строка на шаге DELETE.

### TC-ABAC-02 — MANAGER: C/R/U, но НЕ DELETE (Требования 5.2, 5.3)

1. Под MANAGER: `GET /api/finishing-materials` → **`200`**.
2. `POST`/`PUT /api/finishing-materials` → **`2xx`** (CREATE/UPDATE разрешены).
3. `DELETE /api/finishing-materials/{id}` → **`403`** (DELETE только у ADMIN).
4. Teardown: под ADMIN `DELETE` строки, созданной MANAGER-ом.

### TC-ABAC-03 — FOREMAN/WORKER/FINANCIER: только READ (Требование 5.2)

1. Под каждой из ролей: `GET /api/finishing-materials` → **`200`**.
2. Любой `POST`/`PUT`/`DELETE` → **`403`**.

### TC-ABAC-04 — CLIENT: доступ запрещён (deny-by-default, Требование 5.2)

1. Под CLIENT: `GET`/`POST`/`PUT`/`DELETE /api/finishing-materials` → **`403`**
   (гранта нет).

### TC-ABAC-05 — Ресурс засеян с полными полями (Требование 5.1)

1. Проверить (через seed/DB или админ-API), что строка ресурса `MATERIALS_FINISHING`
   присутствует с непустыми `code`, `name_ru`, `name_pl`, `description_ru`,
   `description_pl`.

### TC-ABAC-06 — Неаутентифицированный доступ → 401

1. `GET /api/finishing-materials` без заголовка `Authorization` → **`401`**.

---

## Группа 7 — UI: страница /materials/finishing + контрол фото (браузер)

Браузерные сценарии против `http://localhost:3000`. Проверяет Требование 7.
Предусловия: залогинен ADMIN (у которого есть CREATE/UPDATE/DELETE), image-storage
сконфигурирован для сценариев с картинкой.

**Повторяемость:** генератор данных форм на `run-id` (`model = "FM UI {run-id}"`) +
teardown (удаление созданных строк через UI-диалог удаления в конце сценария).

### TC-UI-01 — Страница FinishingMaterials: список и колонки

1. Перейти на `/materials/finishing` → страница открыта, `DataTable` отрисован с
   колонками `category`, `material`, `type`, `producer`, `model`, `sku`, `retailNet`,
   `link` (опционально thumbnail `photo`).
2. Проверить серверные поиск/сортировку/фильтр/пагинацию (`DataTable`
   `entityKey="finishing-materials"`).
3. Пустой список (по несуществующему фильтру) → отрисован локализованный empty-state.
4. Проверить, что ни один сырой i18n-ключ (`finishingMaterials.*`) не виден.

### TC-UI-02 — Создание материала через форму (без code/name)

1. Нажать «Создать» → форма содержит: обязательный `category`, обязательный
   `material`, опциональные `type`/`producer`, обязательный мультиселект `packages`
   (≥ 1), обязательный `unit`, поля `model`/`sku`/`features`, три поля цен, `link`,
   тумблер `active`. Форма **без полей `code` и `name`**.
2. Заполнить валидными значениями (`model="FM UI {run-id}"`), сохранить → строка
   появилась в списке, показан тост.
3. Сабмит с пустыми `packages` / без `category`/`material`/`unit` / ценой вне
   диапазона / `model`>255 / `link`>1024 → сабмит заблокирован, введённые значения
   сохранены, показаны локализованные сообщения по каждому невалидному полю
   (Требование 7.4).
4. Teardown: удалить материал через диалог подтверждения.

### TC-UI-03 — Контрол загрузки фото на форме материала (Требование 7.5)

Предусловия: image-storage сконфигурирован.

1. На форме материала присутствует реюзнутый `ImageUploadControl`
   (`entityKind="finishing-materials"`, `resource="MATERIALS_FINISHING"`).
2. Выбрать изображение (`png`/`jpeg`/`webp`) → контрол загружает файл через
   `POST /api/images`, после успеха отображает резолвнутое CDN-превью.
3. Сохранить материал → возвращённый `objectKey` привязан к сущности.
4. Открыть материал с фото → отрисовано разрешённое CDN-изображение (`photoUrl`).
5. Teardown: удалить материал.

### TC-UI-04 — Пункт меню + гварды маршрутов + пермишен-гейтинг

Требования 7.7, 7.9, 7.10, 7.11.

1. В секции «Склад / Warehouse» присутствует пункт с ключом `nav.finishingMaterials`,
   ведущий на `/materials/finishing`, ABAC-gated на `MATERIALS_FINISHING` READ.
2. Под пользователем без READ на `MATERIALS_FINISHING` пункт скрыт, а прямой переход
   на `/materials/finishing` заблокирован интеримным гвардом.
3. Под пользователем без `CREATE/UPDATE/DELETE` соответствующие кнопки скрыты
   (`usePermission`).
4. Проверить `nav.finishingMaterials` и `finishingMaterials.*` на паритет в `pl.json`
   и `ru.json`; ни один сырой ключ не рендерится.

---

## Регрессия

Раздел проверяет ранее закрытые дефекты, найденные при реализации спеки, и критичные
сквозные пути. **Повторяемость:** генератор на `run-id` + teardown.

### TC-REG-01 — Производный `label` не портит текстовые поля и не персистится

Закрытый риск (по аналогии с FOR-04-17): маппер не должен превращать `model`/`sku`
в CDN URL и не должен сохранять `label` как колонку. Регресс:

1. `POST`/`GET /api/finishing-materials` с `model`/`sku` и `photo` → **`200`**:
   `model`/`sku` возвращаются без изменений; только `photoUrl` — резолв
   `toCdnUrl(photo)`; `label` присутствует лишь в DTO.
2. Проверить (DB/метаданные), что колонки `label` в таблице `finishing_materials` нет.
3. Teardown: `DELETE` материала.

### TC-REG-02 — Нет ложного 404 на PUT, когда photo = null

Закрытый риск: `PUT` материала с `photo=null` вызывал орфан-клинап/резолв так, что
возвращался ложный `404`. Регресс:

1. Создать материал `M` без фото (`photo` отсутствует/`null`).
2. `PUT /api/finishing-materials/{M}` с телом, где `photo=null`, прочие поля валидны →
   **`200`** (а не `404`); материал обновлён.
3. `GET /api/finishing-materials/{M}` → **`200`**, `photoUrl=null`.
4. Повторить `PUT` с `photo=null` идемпотентно → снова **`200`**.
5. Teardown: `DELETE` `M`.

### TC-REG-03 — Сквозной путь: ссылки → материал → фильтры → удаление пакета

1. Создать временный пакет `P`, материал `M` (`category=C`, `material=Mat`,
   `packages=[P]`, `retailNet=10.00`) → все `2xx`.
2. `GET /api/finishing-materials?filter=packages.id==<P>` → **`200`**, содержит `M`.
3. `DELETE` пакета `P` (временного) → `M` без пакетов удалён (каскад/чистка).
4. `GET /api/finishing-materials/{M}` → **`404`**.
5. Teardown: убедиться, что временный пакет и материал отсутствуют.

### TC-REG-04 — Чистый старт под PermissionAnnotationValidator (Требование 5.5)

1. Приложение стартует без ошибок при полностью аннотированном
   `FinishingMaterialController` (`@PermissionResource("MATERIALS_FINISHING")` +
   `@PermissionOperation` на унаследованных CRUD-хендлерах); проверяется отсутствием
   фейла старта в логах бэкенда `docker compose`.

---

## Шаблон MD-репорта прогона

Заполняется по итогам каждого прогона (отдельная таблица на группу или общая):

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-FM-01  | 1 | POST /api/finishing-materials | 201 + id/refs/prices, без code/name |  |  |
| 2 | TC-FM-02  | 1 | GET /api/finishing-materials/{id} (Accept-Language: ru) | 200, label = material — model |  |  |
| 3 | TC-FM-03  | 4 | POST c пустым offerPackageIds | 400, поле packages |  |  |
| 4 | TC-FILT-02| 1 | GET ?filter=packages.id==<P2> | 200, только M2 без дублей |  |  |
| 5 | TC-CASCADE-01 | 3 | GET /{M_single} после DELETE пакета | 404 (материал без пакетов удалён) |  |  |
| 6 | TC-IMG-01 | 1 | POST /api/images (finishing-materials, png) | 200, objectKey ~ finishing-materials/ |  |  |
| 7 | TC-IMG-02 | 1 | POST /api/images (pdf) | 400, объект не сохранён |  |  |
| 8 | TC-CSV-02 | 1 | GET строки Egger EL2157 | 200, retailNet = 53.61 (fallback) |  |  |
| 9 | TC-CSV-05 | 3 | count после повторного сида | count2 == count1 |  |  |
| 10| TC-ABAC-02| 3 | DELETE /api/finishing-materials/{id} под MANAGER | 403 |  |  |
| 11| TC-ABAC-04| 1 | GET/POST под CLIENT | 403 |  |  |
| 12| TC-UI-01  | 1 | Открыть /materials/finishing | DataTable + колонки |  |  |
| 13| TC-UI-03  | 2 | Загрузить фото в ImageUploadControl | POST /api/images → CDN-превью |  |  |
| 14| TC-REG-02 | 2 | PUT материала с photo=null | 200 (не 404) |  |  |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`.
Стратегия повторяемости: `<генератор run-id + teardown; CSV-группа — read-only + идемпотентность>`.
Тип прогона: `<API против http://localhost:8080 | UI против http://localhost:3000>`.
