# Тест-кейсы: FOR-05-03 Estimate core (кострукция сметы: Estimate/EstimateLine/RoomQty/PackagePrice)

## Назначение и характер спеки

Спека **чисто API** (нет браузерных экранов): FOR-05-03 добавляет пять сущностей
(`Estimate`, `EstimateLine`, `EstimateLineRoomQty`, `EstimateLinePackagePrice`,
`EstimateLinePackagePriceHistory`) как generic CRUD-ресурсы на `AdminController`/
`AdminService`, все под единым ABAC-ресурсом `ESTIMATE`. Тест-кейсы — это API-тесты против
работающего в Docker приложения (не Testcontainers/юнит-слой), результат прогона — MD-отчёты
с таблицами. Скриншоты не требуются.

Покрываемые требования (по заданию задачи 16 из `tasks.md`): 1.5, 1.6, 2.3, 3.6, 4.1, 4.5,
5.2, 6.2, 7.2, 8.2, 9.3, 11.3. Дополнительно, для полноты сценариев, кейсы касаются
смежных требований 1.1–1.4, 2.1–2.7, 3.1–3.4, 4.2–4.7, 5.1, 5.3, 6.1, 6.3, 7.1, 7.3, 8.1,
8.3, 8.5, 9.1, 9.2, 9.4, 9.5, 11.1, 11.2.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase`
   (миграции, включая сид `081-seed-estimate-resource.xml`), `backend` (:8080, профиль
   `docker`), `frontend` (:3000, не используется в этой спеке).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN поднимается через переменные `FOREMEN_ADMIN_CREATE=true`,
   `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном.
5. **Предзависимость по данным:** нужны существующие `Project` (FOR-04-13), `Room`
   (FOR-04-14), `WorkItem`/`WorkPrice`/`WorkPackagePrice` (FOR-04-11/12b), `OfferPackage`
   (FOR-04-10, коды `budget`/`norm`/`lux`), `MeasurementUnit`, `Currency` (PLN), `VatRate`.
   Каждый сценарий создаёт свой собственный `Project` (см. «Стратегия повторяемости»), чтобы
   не зависеть от состояния каталога, кроме чтения существующих `WorkItem`/`OfferPackage`.

## Маршруты API (по контроллерам)

| Ресурс | Базовый путь | Особые эндпоинты |
|--------|--------------|--------------------|
| `Estimate` | `/api/estimates` | `GET /api/estimates/project/{projectId}` — get-or-create (R1.5), требует `ESTIMATE:READ` |
| `EstimateLine` | `/api/estimate-lines` | генерик CRUD |
| `EstimateLineRoomQty` | `/api/estimate-line-room-qty` | генерик CRUD |
| `EstimateLinePackagePrice` | `/api/estimate-line-package-prices` | `GET /api/estimate-line-package-prices/{id}/history` — история цен (R6.3), требует `ESTIMATE:READ` |

Все четыре контроллера аннотированы `@PermissionResource("ESTIMATE")`; генерик CRUD —
`POST /`, `GET /` (paginated), `GET /extended`, `GET /{id}`, `PUT /{id}`, `DELETE /{id}`,
`GET /count`, `GET /metadata`, `GET /i18n`.

Ключевые сообщения об ошибках (PL/RU, файлы `messages.properties`/`messages_ru.properties`):

| Код | RU | PL |
|-----|----|----|
| `error.estimate.already.exists` | Смета для этого проекта уже существует. | Kosztorys dla tego projektu już istnieje. |
| `error.estimate.locked` | Смета не находится в статусе Черновик. Редактирование заблокировано; используйте процедуру изменения. | Kosztorys nie jest w statusie Szkic. Edycja jest zablokowana; użyj procedury zmiany. |
| `error.estimate.qty.negative` | Количество не может быть отрицательным. | Ilość nie może być ujemna. |
| `error.estimate.room.cross.project` | Помещение должно принадлежать тому же проекту, что и смета. | Pomieszczenie musi należeć do tego samego projektu co kosztorys. |

## Стратегия повторяемости

**Генератор (run-id).** На каждый прогон формируется уникальный `run-id`
(timestamp/uuid, например `20260115-153000` или короткий uuid-суффикс). Каждый сценарий,
которому нужен изолированный проект, создаёт **свой собственный `Project`** с именем вида
`FOR-05-03 TC-XXX {run-id}` — таким образом сценарии не пересекаются и не требуют очистки
БД между прогонами: повторный прогон просто создаёт новые проекты/сметы/строки с новым
`run-id` в имени. Дополнительной очистки (teardown) не требуется, т.к.:

- Инвариант «одна смета на проект» не может быть нарушен новым прогоном (новый проект →
  новая смета).
- Сид-данные каталога (`WorkItem`/`WorkPackagePrice`/`OfferPackage`/`Currency`/`VatRate`)
  только читаются, не изменяются.
- Ресурс `ESTIMATE` и его гранты — сидированы миграцией один раз, не создаются тестами.

Единственное исключение — Фича 8 (снапшот при изменении каталога), где тест-кейс
**временно** редактирует существующую `WorkPackagePrice`/создаёт новую историческую цену;
для неё явно указан шаг teardown (см. TC-SNAP-04).

Каждый тест-кейс, создающий `Project`, ОБЯЗАН встраивать `{run-id}` в имя проекта, чтобы
множественные прогоны не путали сущности друг с другом (при просмотре БД/логов).

---

## Фича 0. Setup (токен ADMIN + FK-справочники)

### TC-SETUP-01 — Логин ADMIN
**Предусловия:** backend поднят, `FOREMEN_ADMIN_*` применены.

1. `POST /api/auth/login` `{"email": "<FOREMEN_ADMIN_EMAIL>", "password": "<FOREMEN_ADMIN_PASSWORD>"}` → ожидание: **200** + тело содержит `accessToken`.

**Итог:** токен ADMIN сохранён для последующих запросов (`Authorization: Bearer <token>`).

### TC-SETUP-02 — Получить справочные id (каталог)
**Предусловия:** TC-SETUP-01 выполнен.

1. `GET /api/work-items?size=1000` (Bearer ADMIN) → **200**; сохранить `workItemId` позиции, у которой есть хотя бы одна `WorkPackagePrice` (для сценариев с полноценным снапшотом) и, отдельно, `workItemIdUnpriced` позиции без цен пакетов (для сценария «unpriced»).
2. `GET /api/offer-packages?size=100` → **200**; сохранить список `offerPackageId` (ожидается 3: `budget`/`norm`/`lux`).
3. `GET /api/measurement-units?size=100` → **200**; сохранить `unitId`.
4. `GET /api/currencies?size=100` → **200**; сохранить `currencyIdPln` (код `PLN`).
5. `GET /api/vat-rates?size=100` → **200**; сохранить `vatRateId` с известной ставкой `rate` (например 23%).

**Итог:** справочные id доступны для всех последующих сценариев.

### TC-SETUP-03 — Создать изолированный проект и комнаты для прогона
**Предусловия:** TC-SETUP-01/02 выполнены.

1. `POST /api/projects` `{"name": "FOR-05-03 base {run-id}", ...}` (минимально необходимые поля проекта) → **200** + `projectId`.
2. `POST /api/rooms` `{"projectId": <projectId>, "name": "Room A {run-id}", ...}` → **200** + `roomIdA`.
3. `POST /api/rooms` `{"projectId": <projectId>, "name": "Room B {run-id}", ...}` → **200** + `roomIdB`.
4. Отдельно: `POST /api/projects` `{"name": "FOR-05-03 other {run-id}", ...}` → **200** + `otherProjectId` (для кросс-проектных негативных кейсов).
5. `POST /api/rooms` `{"projectId": <otherProjectId>, "name": "Room X {run-id}", ...}` → **200** + `otherRoomId`.

**Итог:** базовый проект `projectId` с комнатами `roomIdA`/`roomIdB`, и независимый `otherProjectId`/`otherRoomId` для кросс-проектных тестов готовы.

---

## Фича 1. Estimate 1:1 с проектом / get-or-create (R1.1–R1.6)

**Повторяемость:** генератор — каждый кейс создаёт свой `Project`.

### TC-EST-01 — Get-or-create создаёт смету при первом обращении (R1.5, R1.2, R1.3)
**Предусловия:** новый `Project` `p1` создан (без сметы).

1. `GET /api/estimates/project/{p1}` (Bearer ADMIN) → **200** + тело: `id`, `projectId == p1`, `currencyId` (PLN по умолчанию), `status == "DRAFT"`, `totalNet == 0`, `totalVat == 0`, `totalGross == 0`.

**Итог:** для проекта без сметы создаётся ровно одна смета со статусом `DRAFT`, валютой PLN, нулевыми тоталами.

### TC-EST-02 — Повторный get-or-create возвращает ту же смету (R1.5, R1.6)
**Предусловия:** TC-EST-01 выполнен (смета `estimateId` для `p1` существует).

1. `GET /api/estimates/project/{p1}` повторно → **200**; `id == estimateId` (тот же, не новый).
2. `GET /api/estimates?query=projectId==<p1>` → **200**; ровно **1** элемент.

**Итог:** повторные вызовы `get-or-create` не создают вторую смету; инвариант 1:1 сохраняется.

### TC-EST-03 — Явный POST второй сметы для того же проекта отклоняется (R1.6)
**Предусловия:** смета для `p1` уже существует (TC-EST-01).

1. `POST /api/estimates` `{"projectId": <p1>}` (Bearer ADMIN) → ожидание: **409** с кодом `error.estimate.already.exists`.

**Итог:** явная попытка создать вторую смету для того же проекта отклоняется, инвариант 1:1 не нарушен.

### TC-EST-04 — Явный POST первой сметы с явными currency/vatRate (R1.2, R1.3, R1.4)
**Предусловия:** новый `Project` `p2` без сметы.

1. `POST /api/estimates` `{"projectId": <p2>, "currencyId": <currencyIdPln>, "vatRateId": <vatRateId>}` → **200** + `status == "DRAFT"` (всегда DRAFT при создании), `currencyId == currencyIdPln`, `vatRateId == vatRateId`, `totalNet == totalVat == totalGross == 0`.

**Итог:** явное создание сметы уважает переданные FK, но всегда стартует в `DRAFT` с нулевыми тоталами.

### TC-EST-05 — Дефолт currency = PLN при создании без currencyId (R1.2)
**Предусловия:** новый `Project` `p3`.

1. `POST /api/estimates` `{"projectId": <p3>}` → **200**; `currencyId == currencyIdPln`.

**Итог:** валюта по умолчанию — PLN.

---

## Фича 2. EstimateLine + денормализованный unitPrice (R2.1–R2.7)

**Повторяемость:** генератор — новый проект/смета на кейс.

### TC-LINE-01 — Создание строки со снапшотом unitPrice (R2.1–R2.3)
**Предусловия:** проект `p4` + смета `e4` (через get-or-create); `workItemId` из TC-SETUP-02.

1. `POST /api/estimate-lines` `{"estimateId": <e4>, "workItemId": <workItemId>, "unitId": <unitId>, "lineNo": 1, "unitPrice": 150.00}` → **200** + `id == lineId`, `estimateId == e4`, `workItemId`, `unitId`, `unitPrice == 150.00`, `quantity == 0` (нет room qty), `valueNet == 0`.

**Итог:** строка создана; `unitPrice` — денормализованный снапшот; `quantity`/`valueNet` производные и равны 0 при отсутствии room qty (R2.6, R2.7, R3.4).

### TC-LINE-02 — Провенанс-FK workPriceId не влияет на unitPrice (R2.4)
**Предусловия:** известен `workPriceId` (историческая цена из каталога, FOR-04-12), отличная числом от переданного `unitPrice`.

1. `POST /api/estimate-lines` `{"estimateId": <e4>, "workItemId": <workItemId>, "workPriceId": <workPriceId>, "unitId": <unitId>, "lineNo": 2, "unitPrice": 200.00}` → **200**; `unitPrice == 200.00` (значение из payload, не из каталожной цены `workPriceId`).

**Итог:** `workPriceId` — только провенанс, не источник значения.

### TC-LINE-03 — Дериватив-поля игнорируются на входе (R2.6, R2.7, R8.4)
**Предусловия:** как в TC-LINE-01.

1. `POST /api/estimate-lines` с дополнительными (не входящими в контракт DTO) полями `quantity: 999`, `valueNet: 999999` вместе с обязательными полями → **200**; ответ содержит `quantity == 0`, `valueNet == 0` (поля либо отсутствуют в контракте и игнорируются десериализатором, либо явно пересчитаны сервисом — в обоих случаях клиентские значения не сохраняются).

**Итог:** нет пути передать `quantity`/`valueNet` с клиента — они всегда производные.

### TC-LINE-04 — Обязательные поля валидируются (R2.1, R2.2)
**Предусловия:** смета `e4`.

1. `POST /api/estimate-lines` без `estimateId` → **400**.
2. `POST /api/estimate-lines` без `workItemId` → **400**.
3. `POST /api/estimate-lines` без `unitId` → **400**.
4. `POST /api/estimate-lines` без `unitPrice` → **400**.

**Итог:** обязательные FK и `unitPrice` валидируются на входе.

---

## Фича 3. EstimateLineRoomQty + пересчёт (R3.1–R3.6, R8.1)

**Повторяемость:** генератор.

### TC-RQ-01 — Создание room-qty агрегирует в line.quantity (R3.1–R3.3, R8.1)
**Предусловия:** проект `p5`, смета `e5`, комнаты `roomA`/`roomB` (свои для `p5`), строка `line5` с `unitPrice=100.00`.

1. `POST /api/estimate-line-room-qty` `{"lineId": <line5>, "roomId": <roomA>, "quantity": 3.5}` → **200** + `id`, `quantity == 3.5`.
2. `GET /api/estimate-lines/{line5}` → **200**; `quantity == 3.5`, `valueNet == 350.00` (`100.00 × 3.5`).
3. `POST /api/estimate-line-room-qty` `{"lineId": <line5>, "roomId": <roomB>, "quantity": 1.5}` → **200**.
4. `GET /api/estimate-lines/{line5}` → **200**; `quantity == 5.0` (сумма `3.5 + 1.5`), `valueNet == 500.00`.

**Итог:** `line.quantity` = Σ room qty; `valueNet` пересчитывается автоматически (R2.6, R2.7, R8.1).

### TC-RQ-02 — Изменение room-qty пересчитывает line и totals (R3.3, R8.2)
**Предусловия:** продолжение TC-RQ-01 (`line5.quantity == 5.0`); запомнить id первой room-qty строки `rq1`.

1. `PUT /api/estimate-line-room-qty/{rq1}` `{"lineId": <line5>, "roomId": <roomA>, "quantity": 10.0}` → **200**.
2. `GET /api/estimate-lines/{line5}` → **200**; `quantity == 11.5` (`10.0 + 1.5`), `valueNet == 1150.00`.
3. `GET /api/estimates/project/{p5}` → **200**; `totalNet` включает `1150.00` (сумма по всем строкам сметы).

**Итог:** изменение количества по комнате пересчитывает и строку, и тоталы сметы.

### TC-RQ-03 — Удаление room-qty пересчитывает line (R3.3, R3.4)
**Предусловия:** продолжение TC-RQ-02.

1. `DELETE /api/estimate-line-room-qty/{rq1}` → **200**.
2. `GET /api/estimate-lines/{line5}` → **200**; `quantity == 1.5` (осталась только `roomB`), `valueNet` пересчитан соответственно.

**Итог:** удаление room-qty корректно уменьшает производную сумму.

### TC-RQ-04 — Строка без room-qty имеет quantity=0, valueNet=0, без ошибки (R3.4)
**Предусловия:** новая строка `line5b` (без room qty) в смете `e5`.

1. `GET /api/estimate-lines/{line5b}` → **200**; `quantity == 0`, `valueNet == 0`, без ошибки.

**Итог:** пустой набор room-qty — валидное состояние с нулевыми производными.

### TC-RQ-05 — Отрицательное количество отклоняется (R3.2)
**Предусловия:** строка `line5`.

1. `POST /api/estimate-line-room-qty` `{"lineId": <line5>, "roomId": <roomA>, "quantity": -1.0}` → **400** с кодом `error.estimate.qty.negative`.

**Итог:** негативное `quantity` отклоняется с корректным кодом ошибки.

### TC-RQ-06 — Кросс-проектная комната отклоняется (R3.6) [ключевой для задачи 16 — Requirement 3.6]
**Предусловия:** строка `line5` принадлежит смете проекта `p5`; `otherRoomId` принадлежит `otherProjectId` (из TC-SETUP-03), т.е. другому проекту.

1. `POST /api/estimate-line-room-qty` `{"lineId": <line5>, "roomId": <otherRoomId>, "quantity": 2.0}` → ожидание: **400** с кодом `error.estimate.room.cross.project`.

**Итог:** попытка привязать комнату из чужого проекта к строке текущего проекта отклоняется.

---

## Фича 4. Снапшот per-package цены при добавлении строки (R4.1–R4.7)

**Повторяемость:** генератор.

### TC-SNAP-01 — При создании строки создаётся снапшот на каждый существующий OfferPackage (R4.1, R4.2) [ключевой для Requirement 4.1]
**Предусловия:** новый проект `p6`, смета `e6`; `workItemId` из TC-SETUP-02 имеющий цены на все 3 пакета в каталоге (`budget`/`norm`/`lux`); список `offerPackageId` (3 штуки) из TC-SETUP-02.

1. `POST /api/estimate-lines` `{"estimateId": <e6>, "workItemId": <workItemId>, "unitId": <unitId>, "lineNo": 1, "unitPrice": 100.00}` → **200** + `id == line6`.
2. `GET /api/estimate-line-package-prices?query=lineId==<line6>` → **200**; ровно **3** строки — по одной на каждый `offerPackageId`, существовавший на момент создания (UNIQUE `(line, package)` — без дублей).
3. Для каждой строки: `originalUnitPrice` не `null` (если у `workItemId` есть цена пакета или MAX-fallback применим), `unpriced == false`, эффективный `unitPrice == originalUnitPrice` (нет скидки по умолчанию — R5.2).

**Итог:** ровно один снапшот на каждый существующий пакет, скопированный из каталога через MAX-fallback; эффективная цена равна оригинальной при отсутствии скидки.

### TC-SNAP-02 — MAX-fallback: пакет без собственной цены получает MAX среди цен позиции (R4.3)
**Предусловия:** известен `workItemId2`, у которого хотя бы один `OfferPackage` не имеет собственной `WorkPackagePrice`, но у позиции есть цены на другие пакеты (проверить через `GET /api/work-package-prices?query=workItemId==<workItemId2>` заранее — зафиксировать ожидаемый MAX).

1. `POST /api/estimate-lines` `{"estimateId": <e6>, "workItemId": <workItemId2>, "unitId": <unitId>, "lineNo": 2, "unitPrice": 50.00}` → **200** + `id == line6b`.
2. `GET /api/estimate-line-package-prices?query=lineId==<line6b>` → **200**; строка для пакета без собственной цены имеет `originalUnitPrice == MAX(netPrice по остальным пакетам позиции)`, `unpriced == false`.

**Итог:** MAX-fallback корректно резолвится при отсутствии собственной цены пакета.

### TC-SNAP-03 — Позиция без цен пакетов → unpriced=true, без блокировки создания строки (R4.7)
**Предусловия:** `workItemIdUnpriced` из TC-SETUP-02 (позиция без каталожных цен пакетов).

1. `POST /api/estimate-lines` `{"estimateId": <e6>, "workItemId": <workItemIdUnpriced>, "unitId": <unitId>, "lineNo": 3, "unitPrice": 10.00}` → **200** (строка создаётся без ошибки).
2. `GET /api/estimate-line-package-prices?query=lineId==<line6c>` → **200**; для каждого пакета: `unpriced == true`, `originalUnitPrice == null`, эффективный `unitPrice == null` (не выдумана фиктивная цена).

**Итог:** отсутствие цен в каталоге не блокирует добавление строки; пакеты помечаются `unpriced`.

### TC-SNAP-04 — Снапшот неизменен после редактирования каталожной цены (R4.5, R2.5) [ключевой для Requirement 4.5 — регрессия]
**Предусловия:** строка `line6` из TC-SNAP-01 с известным `originalUnitPrice = X` для пакета `budget`, скопированным из `WorkPackagePrice wpp1` (провенанс `workPackagePriceId == wpp1`).

1. Зафиксировать текущее значение `originalUnitPrice` для `(line6, budget)` через `GET /api/estimate-line-package-prices?query=lineId==<line6>`.
2. `PUT /api/work-package-prices/{wpp1}` (каталог FOR-04-12b) изменить `netPrice` на существенно другое значение (например `+1000.00`) → **200**.
3. `GET /api/estimate-line-package-prices?query=lineId==<line6>` повторно → **200**; `originalUnitPrice` для `(line6, budget)` **не изменился** (осталось прежнее `X`).
4. **Teardown:** `PUT /api/work-package-prices/{wpp1}` вернуть исходное `netPrice` (либо создать отдельную историческую запись, не трогая текущую — по конвенции FOR-04-12b), чтобы не искажать состояние каталога для последующих прогонов.

**Итог:** снапшот проектной цены полностью независим от последующих правок каталога (contract «catalog is a reference / snapshot copy»).

### TC-SNAP-05 — Пакет, созданный после строки, не ретро-популируется (R4.6, R12.7)
**Предусловия:** строка `line6` уже существует с 3 снапшотами. *(Кейс документируется как ожидаемое поведение; создание нового `OfferPackage` — операция каталога FOR-04-10, обычно не выполняемая в рамках изолированного API-прогона; если создание нового пакета доступно тестовому окружению — выполнить шаги ниже, иначе кейс помечается как "покрыт интеграционным тестом снапшота", см. design.md §Testing Strategy).*

1. (Если доступно) `POST /api/offer-packages` создать новый пакет `newPkg`.
2. `GET /api/estimate-line-package-prices?query=lineId==<line6>` → количество строк для `line6` остаётся **3** (не 4) — для `newPkg` строка не создана.

**Итог:** ретро-популяция для новых пакетов не происходит — подтверждает границу R4.6/R12.7.

---

## Фича 5. Скидка и эффективная цена (R5.1–R5.3)

**Повторяемость:** генератор.

### TC-DISC-01 — Отсутствие скидки: effective == original (R5.2) [ключевой для Requirement 5.2]
**Предусловия:** снапшот `(line6, budget)` из TC-SNAP-01 с `packagePriceId == pp1`, `originalUnitPrice == X`, без скидки.

1. `GET /api/estimate-line-package-prices/{pp1}` → **200**; `discountKind == null` (или отсутствует), `unitPrice == originalUnitPrice == X`.

**Итог:** нейтральное состояние (нет скидки) ⇒ эффективная цена равна оригинальной.

### TC-DISC-02 — Процентная скидка (PERCENT) (R5.1, R5.3)
**Предусловия:** `pp1` с `originalUnitPrice == 100.00`.

1. `PUT /api/estimate-line-package-prices/{pp1}` `{"lineId": <line6>, "offerPackageId": <budgetPkgId>, "discountKind": "PERCENT", "discountValue": 10}` → **200**; `unitPrice == 90.00` (`100.00 × (1 - 10/100)`).

**Итог:** скидка PERCENT корректно выводит эффективную цену.

### TC-DISC-03 — Абсолютная скидка (ABSOLUTE), не уходит в минус (R5.1, R5.3)
**Предусловия:** `pp1` с `originalUnitPrice == 100.00`.

1. `PUT /api/estimate-line-package-prices/{pp1}` `{"lineId": <line6>, "offerPackageId": <budgetPkgId>, "discountKind": "ABSOLUTE", "discountValue": 30}` → **200**; `unitPrice == 70.00`.
2. `PUT /api/estimate-line-package-prices/{pp1}` `{"lineId": <line6>, "offerPackageId": <budgetPkgId>, "discountKind": "ABSOLUTE", "discountValue": 500}` → **200**; `unitPrice == 0.00` (флор на нуле, не отрицательное).

**Итог:** ABSOLUTE-скидка корректна и не даёт отрицательную эффективную цену.

### TC-DISC-04 — Нулевая скидка нейтральна (R5.2)
**Предусловия:** `pp1` с `originalUnitPrice == 100.00`.

1. `PUT /api/estimate-line-package-prices/{pp1}` `{"lineId": <line6>, "offerPackageId": <budgetPkgId>, "discountKind": "PERCENT", "discountValue": 0}` → **200**; `unitPrice == 100.00` (равно original).

**Итог:** нулевая скидка — нейтральное состояние, как и отсутствие скидки.

### TC-DISC-05 — Unpriced остаётся unpriced независимо от скидки (R4.7, R5.2)
**Предусловия:** снапшот из TC-SNAP-03 (`unpriced == true`, `originalUnitPrice == null`) `ppUnpriced`.

1. `PUT /api/estimate-line-package-prices/{ppUnpriced}` `{"lineId": <line6c>, "offerPackageId": <pkgId>, "discountKind": "PERCENT", "discountValue": 10}` → **200** (или ожидаемое поведение по контракту); эффективный `unitPrice` остаётся `null` (скидка не применяется к отсутствующей цене — null passthrough).

**Итог:** unpriced-строка не получает фиктивную эффективную цену при попытке задать скидку.

---

## Фича 6. Price-history (R6.1–R6.3)

**Повторяемость:** генератор.

### TC-HIST-01 — История создаётся при инициальном снапшоте (R6.1, R6.2)
**Предусловия:** `pp1` создан в TC-SNAP-01 (снапшот при добавлении строки).

1. `GET /api/estimate-line-package-prices/{pp1}/history` (Bearer ADMIN) → **200**; список содержит **не менее 1** записи (начальный capture), с полями `packagePriceId`, `originalUnitPrice`, `discountKind`, `discountValue`, `unitPrice`, `changedBy`, `changedAt`.

**Итог:** начальный снапшот уже отражён в истории.

### TC-HIST-02 — Изменение скидки добавляет новую запись истории (R6.2) [ключевой для Requirement 6.2]
**Предусловия:** `pp1`; текущее количество записей истории `N` (из TC-HIST-01).

1. `PUT /api/estimate-line-package-prices/{pp1}` `{"lineId": <line6>, "offerPackageId": <budgetPkgId>, "discountKind": "PERCENT", "discountValue": 15}` → **200**.
2. `GET /api/estimate-line-package-prices/{pp1}/history` → **200**; количество записей `== N + 1`; последняя запись отражает новый `discountKind == "PERCENT"`, `discountValue == 15`, пересчитанный `unitPrice`.

**Итог:** каждое изменение оригинальной цены/скидки/эффективной цены добавляет новую запись истории.

### TC-HIST-03 — История append-only, порядок от старых к новым (R6.1)
**Предусловия:** продолжение TC-HIST-02 (≥2 записи).

1. `GET /api/estimate-line-package-prices/{pp1}/history` → **200**; записи отсортированы от старых к новым (`changedAt` монотонно неубывает); попытки `PUT`/`DELETE` по самой истории отсутствуют в контракте (нет эндпоинта записи в историю) — история доступна только на чтение.

**Итог:** история — только на добавление, читается по возрастанию времени изменения.

---

## Фича 7. DRAFT-гейт (R7.1–R7.3)

**Повторяемость:** генератор.

### TC-GATE-01 — Свободное редактирование разрешено в DRAFT (R7.1)
**Предусловия:** новый проект `p7`, смета `e7` (статус `DRAFT` по умолчанию).

1. `POST /api/estimate-lines` `{"estimateId": <e7>, "workItemId": <workItemId>, "unitId": <unitId>, "lineNo": 1, "unitPrice": 100.00}` → **200** (успешно, смета в DRAFT).

**Итог:** создание строки в DRAFT-смете проходит без ошибки.

### TC-GATE-02 — Свободное редактирование блокируется после DRAFT (R7.2, R7.3) [ключевой для Requirement 7.2]
**Предусловия:** смета `e7` со строкой `line7` (из TC-GATE-01).

1. `PUT /api/estimates/{e7}` `{"currencyId": <currencyIdPln>, "vatRateId": <vatRateId>, "status": "PRICED"}` → **200** (перевод статуса — операция сметы самой, не гейтится DRAFT-гейтом строк/room-qty/цен).
2. `POST /api/estimate-lines` `{"estimateId": <e7>, "workItemId": <workItemId>, "unitId": <unitId>, "lineNo": 2, "unitPrice": 50.00}` → ожидание: **409** с кодом `error.estimate.locked`.
3. `PUT /api/estimate-lines/{line7}` `{"estimateId": <e7>, "workItemId": <workItemId>, "unitId": <unitId>, "lineNo": 1, "unitPrice": 999.00}` → **409** `error.estimate.locked`.
4. `DELETE /api/estimate-lines/{line7}` → **409** `error.estimate.locked`.
5. `POST /api/estimate-line-room-qty` `{"lineId": <line7>, "roomId": <roomA_p7>, "quantity": 1.0}` → **409** `error.estimate.locked`.
6. `PUT /api/estimate-line-package-prices/{ppOfLine7}` `{"lineId": <line7>, "offerPackageId": <pkgId>, "discountKind": "PERCENT", "discountValue": 5}` → **409** `error.estimate.locked`.

**Итог:** после ухода сметы из `DRAFT` любая попытка свободного редактирования строк/room-qty/пакетных цен блокируется с кодом `error.estimate.locked`, независимо от конкретного нового статуса (`PRICED`/`APPROVED`/`SIGNED`).

### TC-GATE-03 — Каждый нестандартный статус блокирует запись (R7.2, обзор по всем значениям enum)
**Предусловия:** три независимых проекта/сметы `e7b` (→ `APPROVED`), `e7c` (→ `SIGNED`).

1. Перевести `e7b.status` в `APPROVED` через `PUT /api/estimates/{e7b}`; попытаться `POST /api/estimate-lines` → **409** `error.estimate.locked`.
2. Перевести `e7c.status` в `SIGNED`; попытаться `POST /api/estimate-lines` → **409** `error.estimate.locked`.

**Итог:** гейт действует для всех нестандартных (не `DRAFT`) статусов одинаково.

---

## Фича 8. Пересчёт тоталов сметы (R8.1–R8.5)

**Повторяемость:** генератор.

### TC-TOTALS-01 — totalNet = сумма valueNet строк (R8.2) [ключевой для Requirement 8.2]
**Предусловия:** проект `p8`, смета `e8` с `vatRateId` (ставка, например 23%); две строки: `lineA` (`unitPrice=100`, `quantity=2` через room-qty ⇒ `valueNet=200`), `lineB` (`unitPrice=50`, `quantity=4` ⇒ `valueNet=200`).

1. `GET /api/estimates/project/{p8}` → **200**; `totalNet == 400.00` (`200 + 200`).
2. `totalVat == round2(400.00 × 0.23) == 92.00` (пример при ставке 23%; реальное значение зависит от `vatRateId.rate`).
3. `totalGross == round2(totalNet + totalVat) == 492.00`.

**Итог:** тоталы корректно агрегируют строки и применяют ставку НДС.

### TC-TOTALS-02 — Пустая смета: все тоталы 0 без ошибки (R8.5)
**Предусловия:** новый проект `p8b`, смета `e8b` без строк.

1. `GET /api/estimates/project/{p8b}` → **200**; `totalNet == 0`, `totalVat == 0`, `totalGross == 0`.

**Итог:** смета без строк валидна и не выбрасывает ошибку.

### TC-TOTALS-03 — Тоталы не принимают значения с клиента (R8.4)
**Предусловия:** смета `e8`.

1. `PUT /api/estimates/{e8}` с полем `totalNet: 999999` в теле (сверх контракта DTO) → **200**; `totalNet` в ответе остаётся производным значением (не `999999`), т.к. `EstimateUpdateRequest` не содержит tотал-полей.

**Итог:** derived-тоталы невозможно перезаписать напрямую через API.

---

## Фича 9. ABAC — ресурс ESTIMATE и матрица ролей (R9.1–R9.6)

**Повторяемость:** генератор для create-кейсов; только чтение для проверки сида.

### TC-ABAC-01 — 401 без токена
1. `GET /api/estimates` без `Authorization` → **401**.
2. `POST /api/estimate-lines` без токена → **401**.

**Итог:** неаутентифицированный доступ отклоняется.

### TC-ABAC-02 — Ресурс ESTIMATE засижен (R9.1, R9.6)
1. `GET /api/resources` (Bearer ADMIN) → **200**; список содержит ресурс с `code == "ESTIMATE"`.

**Итог:** сид ресурса применён.

### TC-ABAC-03 — ADMIN полный CRUD на всех четырёх контроллерах (R9.2, R9.3, R9.4)
**Предусловия:** проект `p9`, смета `e9` (ADMIN).

1. `POST /api/estimates` (или get-or-create) → **200**.
2. `POST /api/estimate-lines` → **200**; `PUT`/`DELETE` на созданной строке → **200**.
3. `POST /api/estimate-line-room-qty` → **200**; `PUT`/`DELETE` → **200**.
4. `POST /api/estimate-line-package-prices` (если явный create поддержан, иначе через снапшот) / `PUT` дисконта / `GET .../history` → **200**.

**Итог:** ADMIN имеет полный CRUD на все четыре ресурса под `ESTIMATE`.

### TC-ABAC-04 — MANAGER: C/R/U разрешены, DELETE запрещён (409/403) (R9.5)
*Требует тестового пользователя с ролью MANAGER, привязанного к проекту `p9` (member). Покрывается также интеграционным seed-тестом на стороне backend.*

1. Логин под MANAGER-пользователем → **200** + токен.
2. `POST /api/estimate-lines` (свой проект) → **200**.
3. `PUT /api/estimate-lines/{id}` → **200**.
4. `DELETE /api/estimate-lines/{id}` → **403** (нет грант DELETE у MANAGER на ESTIMATE).

**Итог:** матрица `ESTIMATE`: MANAGER = C R U (без DELETE), подтверждена на живом стеке.

### TC-ABAC-05 — FOREMAN/WORKER/FINANCIER: только READ (запись 403) (R9.5)
*Аналогично, покрывается seed-тестом; здесь — API-подтверждение для одной из ролей (FOREMAN).*

1. Логин под FOREMAN-пользователем (member проекта `p9`) → **200**.
2. `GET /api/estimates/project/{p9}` → **200**.
3. `POST /api/estimate-lines` → **403**.

**Итог:** READ-роли не могут создавать/изменять записи `ESTIMATE`.

### TC-ABAC-06 — CLIENT без гранта (R9.5)
1. Логин под CLIENT-пользователем → **200**.
2. `GET /api/estimates/project/{p9}` → **403**.

**Итог:** CLIENT не имеет доступа к ресурсу `ESTIMATE` (клиентский доступ — за FOR-09).

### TC-ABAC-07 — Доступ вне своего проекта отклоняется (R9.4, project-scoping)
**Предусловия:** MANAGER-пользователь состоит в `p9`, но не в `otherProjectId`.

1. Логин MANAGER → токен.
2. `GET /api/estimates/project/{otherProjectId}` → **403** или **404** (в зависимости от реализации `assertProjectAccess`; ожидание — доступ отсутствует).

**Итог:** project-scoping через `getProjectIdPath()` отсекает доступ к чужим проектам независимо от операции.

---

## Фича 10. i18n паритет PL/RU (R11.1–R11.3)

**Повторяемость:** только чтение.

### TC-I18N-01 — Сообщения об ошибках присутствуют на PL и RU (R11.1, R11.3) [ключевой для Requirement 11.3]
**Предусловия:** воспроизвести условие каждой ошибки (см. Фичи 1, 3, 7) с заголовком `Accept-Language: ru` и `Accept-Language: pl` (или иной механизм выбора локали, принятый в проекте — заголовок/параметр).

1. Спровоцировать `error.estimate.already.exists` (TC-EST-03) с `Accept-Language: ru` → сообщение на русском, не сырой код `error.estimate.already.exists`.
2. То же с `Accept-Language: pl` → сообщение на польском.
3. Повторить для `error.estimate.locked` (TC-GATE-02), `error.estimate.qty.negative` (TC-RQ-05), `error.estimate.room.cross.project` (TC-RQ-06).

**Итог:** ни один raw-код ошибки не возвращается пользователю; на PL и RU есть непустой перевод.

### TC-I18N-02 — Enum-лейблы (EstimateStatus, DiscountKind) на PL и RU (R11.2)
1. `GET /api/estimates/i18n` (или соответствующий metadata-эндпоинт enum-лейблов) → **200**; для каждого значения `EstimateStatus` (`DRAFT`/`PRICED`/`APPROVED`/`SIGNED`) и `DiscountKind` (`PERCENT`/`ABSOLUTE`) присутствуют непустые `nameRU` и `namePL`.

**Итог:** все значения перечислений имеют пару RU/PL лейблов.

### TC-I18N-03 — Ресурс ESTIMATE имеет name_ru/name_pl (R11.1)
1. `GET /api/resources` → **200**; элемент `code == "ESTIMATE"` имеет непустые `name_ru == "Смета"`, `name_pl == "Kosztorys"`.

**Итог:** отображаемое имя ресурса локализовано на оба языка.

---

## Регрессия

**Повторяемость:** генератор (для сценариев с созданием данных); чтение для сид-проверок.
Регрессионные кейсы перепроверяют критичные сквозные пути спеки после любых последующих
изменений (например, доработок FOR-05-06/07/08/14/15, которые логически соседствуют с этим
кодом).

### TC-REG-01 — Неизменность снапшота при правке каталога (R4.5, R2.5)
Повтор TC-SNAP-04 целиком: снапшот проектной цены не сдвигается после `PUT` каталожной
`WorkPackagePrice`; провенанс-FK может обнулиться при удалении каталожной записи, но число
снапшота остаётся прежним. **Teardown:** вернуть каталожную цену в исходное состояние.

### TC-REG-02 — Инвариант «одна смета на проект» (R1.1, R1.6)
Повтор TC-EST-02 + TC-EST-03: множественные `get-or-create` не создают дублей; явный
повторный `POST` отклоняется `409 error.estimate.already.exists`; в БД для проекта не более
одной строки `estimates` (`GET /api/estimates?query=projectId==<id>` возвращает ровно 1
элемент).

### TC-REG-03 — Отклонение кросс-проектной комнаты (R3.6)
Повтор TC-RQ-06: комната из чужого проекта не может быть привязана к строке текущей сметы;
код ошибки `error.estimate.room.cross.project` неизменен.

### TC-REG-04 — DRAFT-гейт действует одинаково на всех сущностях (R7.1–R7.3)
Повтор ключевых шагов TC-GATE-02: после выхода из `DRAFT` блокируются создание/изменение/
удаление строки, room-qty и пакетной цены — единым кодом `error.estimate.locked`; `DRAFT`
остаётся полностью свободным для правок.

### TC-REG-05 — Сид ABAC-ресурса ESTIMATE идемпотентен (R9.1, R9.2, R9.6)
После повторного применения миграций (`liquibase update` повторно на той же БД) количество
строк `resources` с `code = 'ESTIMATE'` и строк `role_resources`/`role_resource_operations`
для этого ресурса не растёт (guard `onFail="MARK_RAN"` в `081-seed-estimate-resource.xml`).
Проверяется через `GET /api/resources` (один элемент `ESTIMATE`) и, при доступности,
интеграционным seed-тестом на стороне backend.

### TC-REG-06 — Пустая смета остаётся валидной (R8.5)
Повтор TC-TOTALS-02: смета без строк не выбрасывает ошибку и отдаёт нулевые тоталы.

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SETUP-01 | 1 | POST /api/auth/login (ADMIN) | 200 + accessToken | | |
| 2 | TC-SETUP-02 | 1-5 | GET справочников (work-items/offer-packages/units/currencies/vat-rates) | 200 + id сохранены | | |
| 3 | TC-SETUP-03 | 1-5 | POST projects/rooms (базовый + other) | 200 + id сохранены | | |
| 4 | TC-EST-01 | 1 | GET /api/estimates/project/{p1} | 200, создана DRAFT/PLN/0/0/0 | | |
| 5 | TC-EST-02 | 1-2 | Повторный GET get-or-create | тот же id, 1 запись | | |
| 6 | TC-EST-03 | 1 | POST /api/estimates (дубль) | 409 error.estimate.already.exists | | |
| 7 | TC-EST-04 | 1 | POST /api/estimates (явные FK) | 200, DRAFT, FK применены | | |
| 8 | TC-EST-05 | 1 | POST /api/estimates без currencyId | 200, currencyId=PLN | | |
| 9 | TC-LINE-01 | 1 | POST /api/estimate-lines | 200, unitPrice снапшот, quantity=0 | | |
| 10 | TC-LINE-02 | 1 | POST со сторонним workPriceId | 200, unitPrice из payload | | |
| 11 | TC-LINE-03 | 1 | POST с quantity/valueNet в payload | 200, значения игнорированы | | |
| 12 | TC-LINE-04 | 1-4 | POST без обязательных полей | 400 на каждое | | |
| 13 | TC-RQ-01 | 1-4 | POST room-qty x2 | line.quantity/valueNet агрегированы | | |
| 14 | TC-RQ-02 | 1-3 | PUT room-qty | line + totals пересчитаны | | |
| 15 | TC-RQ-03 | 1-2 | DELETE room-qty | line.quantity уменьшен | | |
| 16 | TC-RQ-04 | 1 | GET строки без room-qty | quantity=0, valueNet=0 | | |
| 17 | TC-RQ-05 | 1 | POST room-qty quantity=-1 | 400 error.estimate.qty.negative | | |
| 18 | TC-RQ-06 | 1 | POST room-qty с чужой комнатой | 400 error.estimate.room.cross.project | | |
| 19 | TC-SNAP-01 | 1-3 | POST line → GET package-prices | 3 строки, MAX-fallback, unitPrice=original | | |
| 20 | TC-SNAP-02 | 1-2 | POST line (частичные цены) | MAX среди цен позиции | | |
| 21 | TC-SNAP-03 | 1-2 | POST line (unpriced workItem) | unpriced=true, null-цены, строка создана | | |
| 22 | TC-SNAP-04 | 1-4 | PUT каталожной цены после снапшота | снапшот не изменился; teardown выполнен | | |
| 23 | TC-SNAP-05 | 1-2 | Новый OfferPackage после строки | нет ретро-популяции | | |
| 24 | TC-DISC-01 | 1 | GET package-price без скидки | unitPrice=original | | |
| 25 | TC-DISC-02 | 1 | PUT discountKind=PERCENT 10 | unitPrice=90.00 | | |
| 26 | TC-DISC-03 | 1-2 | PUT discountKind=ABSOLUTE 30/500 | unitPrice=70.00 затем 0.00 (флор) | | |
| 27 | TC-DISC-04 | 1 | PUT discountValue=0 | unitPrice=original | | |
| 28 | TC-DISC-05 | 1 | PUT скидка на unpriced | unitPrice остаётся null | | |
| 29 | TC-HIST-01 | 1 | GET .../history | ≥1 запись начального capture | | |
| 30 | TC-HIST-02 | 1-2 | PUT скидка → GET history | +1 запись, отражает новые значения | | |
| 31 | TC-HIST-03 | 1 | GET history повторно | append-only, сортировка по времени | | |
| 32 | TC-GATE-01 | 1 | POST line в DRAFT | 200 | | |
| 33 | TC-GATE-02 | 1-6 | Смена статуса → POST/PUT/DELETE line, room-qty, package-price | все 409 error.estimate.locked | | |
| 34 | TC-GATE-03 | 1-2 | Статусы APPROVED/SIGNED → POST line | 409 error.estimate.locked на каждый | | |
| 35 | TC-TOTALS-01 | 1-3 | GET смета с 2 строками | totalNet/Vat/Gross корректны | | |
| 36 | TC-TOTALS-02 | 1 | GET пустая смета | все тоталы 0 | | |
| 37 | TC-TOTALS-03 | 1 | PUT с totalNet в payload | значение игнорировано | | |
| 38 | TC-ABAC-01 | 1-2 | Запросы без токена | 401 | | |
| 39 | TC-ABAC-02 | 1 | GET /api/resources | содержит ESTIMATE | | |
| 40 | TC-ABAC-03 | 1-4 | ADMIN CRUD на всех 4 ресурсах | 200 везде | | |
| 41 | TC-ABAC-04 | 1-4 | MANAGER CRUD | C/R/U=200, DELETE=403 | | |
| 42 | TC-ABAC-05 | 1-3 | FOREMAN READ-only | GET=200, POST=403 | | |
| 43 | TC-ABAC-06 | 1-2 | CLIENT | 403 | | |
| 44 | TC-ABAC-07 | 1-2 | MANAGER на чужом проекте | 403/404 | | |
| 45 | TC-I18N-01 | 1-3 | Ошибки с Accept-Language ru/pl | переведённые сообщения, не raw-код | | |
| 46 | TC-I18N-02 | 1 | GET i18n enum-лейблов | nameRU/namePL непустые | | |
| 47 | TC-I18N-03 | 1 | GET /api/resources (ESTIMATE) | name_ru/name_pl корректны | | |
| 48 | TC-REG-01 | 1-4 | Регрессия: снапшот vs правка каталога | не изменился; teardown | | |
| 49 | TC-REG-02 | 1-2 | Регрессия: одна смета на проект | инвариант держится | | |
| 50 | TC-REG-03 | 1 | Регрессия: кросс-проектная комната | 400 error.estimate.room.cross.project | | |
| 51 | TC-REG-04 | 1-6 | Регрессия: DRAFT-гейт на всех сущностях | единый 409 error.estimate.locked | | |
| 52 | TC-REG-05 | 1 | Регрессия: идемпотентность сида ESTIMATE | без роста строк после повторной миграции | | |
| 53 | TC-REG-06 | 1 | Регрессия: пустая смета | тоталы 0, без ошибки | | |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`.

Стратегия повторяемости: **генератор** — каждый сценарий создаёт свой `Project` (имя вида
`FOR-05-03 TC-XXX {run-id}`), что делает прогон повторяемым без ручной очистки БД;
исключение — TC-SNAP-04/TC-REG-01, где предусмотрен явный шаг teardown (возврат каталожной
цены `WorkPackagePrice` в исходное состояние после проверки неизменности снапшота).
