# Тест-кейсы: FOR-11-05 — Приёмка поставок на объекте

Спека содержит и API, и UI. Часть A — API-тесты против работающего Docker-стека (`http://localhost:8080`),
часть B — сценарии браузерного движка для экрана приёмки `/deliveries/receipt`. Результат прогона — MD-репорты
с таблицами (шаг → запрос/действие → ожидание → факт → статус), скриншоты не требуются. Реальные перевозчики
**не вызываются**: везде используется тестовый адаптер `STUB`, отслеживание перевозчика в этой спеке не
проверяется (приёмка работает независимо от него).

Покрываемые требования: R1–R15, R17 (проверка по `requirements.md`); сам файл закрывает R16.3.

## Окружение

- Стек: `docker compose up` в корне (postgres :5432, liquibase, backend :8080 профиль `docker`, frontend :3000).
- Первый ADMIN: `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`. Auth-эндпоинты под `/api/auth`.
- Переменные прогона (`.env.local` / override):
  - `FOREMEN_TRACKING_STUB_ENABLED=true`; реальные адаптеры перевозчиков выключены;
  - `FOREMEN_PROCUREMENT_TRACKING_ENABLED=false` (опрос перевозчика не нужен; отправки создаются и закрываются вручную/приёмкой);
  - хранилище изображений включено (локальный/эмулируемый объектный бакет), иначе TC-A8-01…04 дают `503 error.image.storage.not.configured` — в этом случае они помечаются «пропущено» с указанием причины в отчёте, а кейс TC-A8-05 проверяет именно 503;
  - окно отмены автором `foremen.goods-receipt.void-window=PT24H` (по умолчанию), лимиты фото: 8 на запрос, 10 на запись, 5 МБ на файл.
- Тестовые файлы для фото (создаются в Setup, уникальность не требуется): `ok1.jpg`, `ok2.png`, `ok3.webp` (валидные, < 100 КБ),
  `big.jpg` (JPEG > 5 МБ), `fake.jpg` (текстовый файл с расширением и типом `image/jpeg`), `heic.heic` (тип `image/heic`).
- Пользователи (создаются в Setup): ADMIN, BUYER, MANAGER (член проекта), MANAGER-2 (член проекта, для проверки получателей), FOREMAN
  (член проекта), FOREMAN-B (член проекта, второй приёмщик), FOREMAN-2 (член **другого** проекта), FINANCIER, WORKER, CLIENT (член проекта).

## Стратегия повторяемости (обязательно)

**Генератор данных + teardown.** На каждый прогон генерируется `run-id` (timestamp в миллисекундах). Все сущности
именуются с суффиксом `-{run-id}`: проекты `FOR1105-{run-id}` и `FOR1105-OTHER-{run-id}`, e-mail пользователей
`for1105+{role}-{run-id}@example.com`, номера отслеживания `STUBTRANSIT{run-id}`, комментарии приёмки
`rcpt-{run-id}-{n}`. Каждое событие приёмки создаётся с новым `clientRequestId = uuid` (в сценариях идемпотентности
повторяется тот же uuid намеренно).

**Teardown** в конце каждого набора (выполняется даже при падении шагов):
1. Аннулировать (`POST /api/goods-receipts/{id}/void`, ADMIN) все неаннулированные записи прогона (события приёмки
   удалять нельзя — они append-only, поэтому аннулирование и изоляция по проекту обязательны).
2. Отменить созданные отправки (`POST /api/shipments/{id}/cancel`; для отправок с записями приёмки отмена вернёт
   `409 error.shipment.hasReceipts` — такие отправки остаются в проекте прогона, это ожидаемо).
3. Деактивировать проекты и пользователей прогона `FOR1105-*-{run-id}`, `for1105+*-{run-id}@example.com`.

Изоляция гарантируется уникальным проектом на прогон: проверки списков (`GET /api/goods-receipts`,
`GET /to-receive`) всегда идут с `projectId` проекта прогона, поэтому данные прошлых прогонов не мешают.

## Набор данных прогона (Setup)

1. Логин ADMIN; создание пользователей всех ролей из раздела «Окружение» с e-mail по генератору.
2. Проекты `FOR1105-{run-id}` (дата старта — сегодня) и «чужой» `FOR1105-OTHER-{run-id}`; членство: MANAGER, MANAGER-2, FOREMAN,
   FOREMAN-B, CLIENT, WORKER — в первом проекте, FOREMAN-2 — во втором.
3. Продавец и позиции первого проекта (все через API FOR-11-02/03 доводятся до `SUPPLIER_PAID`: принятие клиентом, проформа,
   оплата, внешний заказ, отметка «оплачен» финансистом):

| Позиция | Путь | Количество | Назначение |
|---------|------|------------|------------|
| PA | AGREED | 3 | простая приёмка, закрытие, пример книги из ТЗ |
| PB | AGREED | 3 | «заказано 3, отправлено 2» (1 не отправлена) |
| PC | AGREED | 4 | FIFO-разнесение по двум отправкам |
| PD | AGREED | 2 | излишек (over-delivery) и повторная доставка |
| PE | CLIENT_OWN | 2 | приёмка без отправки (виртуальное ожидание = cap) |
| PF | AGREED | 5 | идемпотентность и откат пакета |
| PG | AGREED | 2 | права, аннулирование автором / не автором |
| PH | AGREED | 2 | фото |
| PX | AGREED | 2 (во втором проекте) | проверка области видимости 404 |

4. Отправки BUYER-ом (`POST /api/shipments`, метод `SHOP_DELIVERY`, без ссылки и телефона — как у системной повторной доставки;
   либо `CARRIER` со `STUB` и `STUBTRANSIT{run-id}`):
   - `SA` — PA 3 шт.; `SB` — PB 2 шт.; `SC1` — PC 2 шт. (ETA = сегодня + 1), `SC2` — PC 2 шт. (ETA = сегодня + 3);
   - `SD` — PD 2 шт.; `SF` — PF 5 шт.; `SG` — PG 2 шт.; `SH` — PH 2 шт.; `SX` — PX 2 шт. (второй проект).
   После создания позиции PA…PH, PX в статусе `IN_DELIVERY`. PE переводится вручную в `IN_DELIVERY` без отправки.
5. Токены ролей получаются через `POST /api/auth/login`; `{token(ROLE)}` ниже означает заголовок `Authorization: Bearer …`.

## Соглашения для частей A и B

- Обозначения: `L(pos)` = `GET /api/purchase-positions/{pos}/ledger` → `{positionId, positionStatus, ledger{cap, acceptedNet, missing, returned, outstanding, surplus, inFlight, committed, remainder}}`.
- Тело записи: `POST /api/goods-receipts` `{events:[{positionId, shipmentId?, kind, quantity, fromAccepted?, comment?, clientRequestId}]}` → `201` и
  `{receipts[], positions[], redeliveryShipmentIds[]}`.
- Ни один ответ приёмки не содержит полей `price`, `amount`, `cost`, `margin`, `vat`, `storageUri`, `storageKey` (проверяется в TC-A9-03).
- Ошибки сравниваются по ключу `error.*` в теле ответа; ключ локализуется (PL/RU), проверка идёт по ключу, не по тексту.
- Отсутствие прав на ресурс = `403`; позиция/запись/файл вне проектов вызывающего = `404`.

---

# Часть A. API-тесты

Базовый URL: `http://localhost:8080`.

## A1. Запись события приёмки

**Повторяемость набора:** Setup + генератор (`run-id`, уникальный `clientRequestId` на событие), teardown по общему правилу.

### TC-A1-01. Полная приёмка отправки одним событием

- **Предусловия:** Setup; роль FOREMAN; PA в `IN_DELIVERY`, `SA` (3 шт.).
- **Шаги:**
  1. `GET /api/goods-receipts/to-receive?projectId={p}` (FOREMAN) → `200`, среди элементов `positionId=PA`, `positionStatus=IN_DELIVERY`,
     `ledger.cap=3`, `inFlight=3`, `outstanding=3`, `acceptedNet=0`; в `shipments` есть `SA` с `quantity=3`, `unresolvedQuantity=3`.
  2. `POST /api/goods-receipts` `{events:[{positionId:PA, shipmentId:SA, kind:"ACCEPTED", quantity:3, clientRequestId:u1}]}` →
     `201`; `receipts[0].kind=ACCEPTED`, `quantity=3`, `shipmentId=SA`, `receivedById` = FOREMAN, `occurredAt` ≈ серверное время (±1 мин);
     `positions[0].positionStatus=RECEIVED`, `ledger.acceptedNet=3`, `outstanding=0`, `surplus=0`, `inFlight=0`; `redeliveryShipmentIds=[]`.
  3. `GET /api/purchase-positions/{PA}` → статус `RECEIVED`, `acceptedQuantity=3`.
  4. `GET /api/shipments/{SA}` (BUYER) → статус `DELIVERED`, `deliveredAt` заполнено (закрытие, см. A5).
  5. `GET /api/notifications` (MANAGER, BUYER) → уведомлений `notification.receipt.discrepancy` по PA нет (чистая приёмка).
  6. `GET /api/goods-receipts/to-receive?projectId={p}` → PA в списке отсутствует.
- **Ожидание:** этап `RECEIVED`, `acceptedQuantity` равно принятому нетто, отправка закрыта, уведомление не создаётся (R4, R6, R8.6, R12.3).

### TC-A1-02. Пример из ТЗ: заказано 3, отправлено 2, 1 возвращена

- **Предусловия:** Setup; PB (cap 3), `SB` с 2 шт.; роль FOREMAN.
- **Шаги:**
  1. `POST` пакет из трёх событий PB в этом порядке: `ACCEPTED 1`, `RETURNED 1` (`fromAccepted=false`, comment `rcpt-{run-id}-1`),
     `MISSING 1` (comment `rcpt-{run-id}-2`), все с `shipmentId=SB` и разными `clientRequestId` → `201`.
     Для события `MISSING 1`: units In-Flight перед ним = 2−1−1 = 0 → **ожидается `409 error.goodsReceipt.exceedsInFlight`** (одна
     отправленная единица принята, одна возвращена на входе, на отправке `SB` больше нет единиц в пути). Пакет откатывается целиком.
  2. `L(PB)` → без изменений (`acceptedNet=0`, `inFlight=2`, статус `IN_DELIVERY`), записей нет.
  3. Повторить пакет в виде `ACCEPTED 1`, `RETURNED 1` (дверной возврат), без `MISSING` (отправка 2 шт. полностью учтена) → `201`:
     `acceptedNet=1`, `returned=1`, `missing=0`, `outstanding=2`, `inFlight=0`, `remainder=2`, `surplus=0`, статус `PARTIALLY_RECEIVED`.
  4. Через `GET /api/goods-receipts?positionId={PB}` → две записи, обе не аннулированы.
- **Ожидание:** `outstanding = 2`; единица, которую не отправляли, остаётся `outstanding`, но в `remainder` она учтена (формулы дизайна, строка 2 таблицы).
- **Примечание:** точный «пример из ТЗ» с тремя событиями (`ACCEPTED 1`, `RETURNED 1`, `MISSING 1`, cap 3) проверяется на PA-подобной позиции с отправкой
  на 3 шт. — см. TC-A1-03.

### TC-A1-03. Пакет `ACCEPTED 1`, `RETURNED 1`, `MISSING 1` при отправке на 3 шт.

- **Предусловия:** отдельная позиция PA2 (AGREED, 3 шт.) с отправкой `SA2` на 3 шт. в `IN_DELIVERY` (добавляется в Setup по запросу набора).
- **Шаги:**
  1. `POST` пакет `[ACCEPTED 1, RETURNED 1 (fromAccepted=false), MISSING 1]` с `shipmentId=SA2`, комментарии у `RETURNED`/`MISSING` обязательны → `201`.
  2. `positions[0].ledger` → `acceptedNet=1`, `missing=1`, `returned=1`, `outstanding=2`, `inFlight=0`, `surplus=0`; `positionStatus=PARTIALLY_RECEIVED`.
  3. `redeliveryShipmentIds` содержит один id (освобождено 2 шт.: `min(remainder=2, released=2)=2`); `GET /api/shipments/{id}` (BUYER):
     метод `SHOP_DELIVERY`, статус `IN_TRANSIT`/`CREATED` (по правилам FOR-11-04, но не терминальный), одна линия PA2 на 2 шт., без ссылки, телефона, ETA,
     примечание содержит `Re-delivery after receipt`; `GET /api/purchase-positions/{PA2}` → статус `IN_DELIVERY` (позиция вернулась в доставку, R8.3).
  4. `GET /api/shipments/{SA2}` → `DELIVERED` (все линии закрыты: `1+1+1 ≥ 3`).
- **Ожидание:** книга точно воспроизводит пример ТЗ; создана ровно одна повторная отправка.

### TC-A1-04. Приёмка без указания отправки (FIFO-разнесение)

- **Предусловия:** PC (cap 4), `SC1` (2 шт., ETA сегодня+1), `SC2` (2 шт., ETA сегодня+3); роль FOREMAN.
- **Шаги:**
  1. `POST` `[ACCEPTED 3]` без `shipmentId` → `201`; в `receipts` **две строки**: 2 шт. с `shipmentId=SC1` и 1 шт. с `shipmentId=SC2` (разбиение по частям).
  2. `GET /api/shipments/{SC1}` → `DELIVERED` (линия закрыта); `SC2` не терминальна.
  3. `L(PC)` → `acceptedNet=3`, `inFlight=1`, `outstanding=1`, статус `PARTIALLY_RECEIVED`.
  4. `POST` `[ACCEPTED 1]` → `201`, `shipmentId=SC2`, `PC` → `RECEIVED`, `SC2` → `DELIVERED`.
- **Ожидание:** разнесение по ETA (nulls last, затем по id); ledger совпадает.

### TC-A1-05. Приёмка CLIENT_OWN без отправки

- **Предусловия:** PE (cap 2, `IN_DELIVERY`, отправок нет).
- **Шаги:**
  1. `POST` `[ACCEPTED 2]` → `201`, `shipmentId=null`, `PE` → `RECEIVED`, `acceptedQuantity=2`.
  2. `POST` `[RETURNED 1, fromAccepted=true, comment]` для PE (статус `RECEIVED`: допустим только такой возврат) → `201`; `acceptedNet=1`, статус `PARTIALLY_RECEIVED`.
  3. `POST` `[ACCEPTED 1]` → `RECEIVED`.
- **Ожидание:** путь `CLIENT_OWN` работает (R4.11), переоткрытие `RECEIVED → PARTIALLY_RECEIVED` и возврат (R6.3).

### TC-A1-06. Излишек (over-delivery) и уведомление

- **Предусловия:** PD (cap 2), `SD` (2 шт.).
- **Шаги:**
  1. `POST` `[ACCEPTED 3, shipmentId=SD]` → `201`; `ledger.acceptedNet=3`, `surplus=1`, `outstanding=0`; статус `RECEIVED`; запись разнесена: 2 шт. на `SD`
     и 1 шт. с `shipmentId=null` (остаток сверх линий).
  2. `GET /api/notifications` ролями MANAGER и MANAGER-2 (члены проекта) и BUYER → у каждого ровно одно `notification.receipt.discrepancy`
     со ссылкой `/deliveries/receipt?position={PD}`; у FOREMAN (актор) уведомления нет; у FINANCIER и CLIENT нет.
  3. `orderedQuantity` позиции PD (`GET /api/purchase-positions/{PD}`) не изменилось (2).
- **Ожидание:** излишек принимается, помечается `surplus`, уведомление уходит по правилу получателей (R4.5, R12).

### TC-A1-07. Валидация формы события

- **Предусловия:** PF в `IN_DELIVERY`, `SF` (5 шт.). Ни один из запросов не должен создать записи (проверка `GET ?positionId={PF}` пусто после серии).
- **Шаги:**
  1. `quantity=0` → `400 error.goodsReceipt.quantity.invalid`; `quantity=-1` → то же; `quantity=1.0001` (4 знака) → то же.
  2. `kind=MISSING` без `comment` → `400 error.goodsReceipt.comment.required`; `comment="   "` → то же; `kind=RETURNED` без комментария → то же.
  3. `kind=ACCEPTED`, `fromAccepted=true` → `400 error.goodsReceipt.fromAccepted.invalid`; `kind=MISSING`, `fromAccepted=true` → то же.
  4. `kind=ACCEPTED` без комментария → `201` допустимо (после этого шага запись аннулировать — см. teardown, либо выполнять шаг последним).
  5. Чужая отправка: `shipmentId=SA` (отправка PA, не содержит PF) → `400 error.goodsReceipt.shipment.mismatch`; несуществующий `shipmentId` → `404`.
  6. `positionId` несуществующий → `404`.
  7. `events` пустой массив → `400`.
- **Ожидание:** ключи ошибок как указано (R4.3, R4.7, R4.10, R2.3).

### TC-A1-08. Допустимые этапы позиции

- **Предусловия:** позиции `PW` (`SUPPLIER_PAID`, без отправки), `PN` (`WITH_BUYER`), PE после `RECEIVED` (TC-A1-05).
- **Шаги:**
  1. `POST [ACCEPTED 1]` для `PW` → `409 error.goodsReceipt.notReceivable`; то же для `PN`.
  2. `POST [ACCEPTED 1]` для позиции в `RECEIVED` → `409 error.goodsReceipt.notReceivable`.
  3. `POST [MISSING 1, comment]` для позиции в `RECEIVED` → `409 error.goodsReceipt.notReceivable`.
  4. `POST [RETURNED 1, fromAccepted=true, comment]` для позиции в `RECEIVED` → `201`.
- **Ожидание:** в `RECEIVED` допустим только возврат из принятого (R4.2).

### TC-A1-09. Границы MISSING и RETURNED

- **Предусловия:** PF, `SF` на 5 шт., в `IN_DELIVERY`.
- **Шаги:**
  1. `[MISSING 6, comment]` → `409 error.goodsReceipt.exceedsInFlight` (в пути 5).
  2. `[MISSING 5, comment]` → `201`; `inFlight=0`, `missing=5`; затем `[MISSING 1, comment]` → `409 error.goodsReceipt.exceedsInFlight`.
  3. Аннулировать запись шага 2 (A3), затем `[ACCEPTED 2]` → `201`; `[RETURNED 3, fromAccepted=true, comment]` → `409 error.goodsReceipt.returnExceedsAccepted`;
     `[RETURNED 2, fromAccepted=true, comment]` → `201`, `acceptedNet=0`.
  4. `[RETURNED 3, fromAccepted=false, comment]` (дверной, ≤ в пути 3) → `201`.
- **Ожидание:** `acceptedNet` и `inFlight` никогда не уходят в минус (R3.4, R4.4).

## A2. Пакет и идемпотентный повтор

**Повторяемость набора:** уникальные `clientRequestId` на каждый прогон; в сценарии повтора uuid сохраняется в переменной и подставляется намеренно.

### TC-A2-01. Идемпотентный повтор того же запроса

- **Предусловия:** PF, `SF` на 5 шт.
- **Шаги:**
  1. `POST [ACCEPTED 2, clientRequestId=U]` → `201`, запоминаем `receipts[0].id`.
  2. Повторить тот же запрос (тот же `U`) → `201` с теми же `id`, теми же `occurredAt`, тем же ledger.
  3. Повторить с тем же `U`, но `quantity=4` (изменённая нагрузка) → ответ совпадает с первым (нагрузка повтора игнорируется), записей не добавилось.
  4. `GET /api/goods-receipts?positionId={PF}` → ровно одна запись; `L(PF)` → `acceptedNet=2`; `GET /api/notifications` — новых уведомлений нет.
  5. Повтор пакета из двух событий, где первое уже сохранено, а второе новое (`U2`) → первое отдаётся из хранилища, второе создаётся; итого 2 записи.
- **Ожидание:** повтор не создаёт строк, уведомлений и отправок (R4.8, свойство 9).

### TC-A2-02. Разбитая запись при повторе

- **Предусловия:** PC после шага 1 TC-A1-04 (две строки одного события).
- **Шаги:** повторить запрос `[ACCEPTED 3]` с тем же `clientRequestId`.
- **Ожидание:** возвращаются обе части (`part_no` 0 и 1) без новых строк.

### TC-A2-03. Атомарность пакета

- **Предусловия:** PF с текущим состоянием `acceptedNet = a`; запомнить `L(PF)` и число записей `n`.
- **Шаги:**
  1. `POST [ACCEPTED 1 (валидно), MISSING 99 (comment)]` → `409 error.goodsReceipt.exceedsInFlight`.
  2. Сверить `L(PF)` и число записей `GET ?positionId={PF}` → без изменений (`n`), статус позиции прежний, `acceptedQuantity` прежнее, новых отправок нет.
  3. `POST [ACCEPTED 1 (валидно), ACCEPTED 1 для позиции PW в SUPPLIER_PAID]` → `409 error.goodsReceipt.notReceivable`, ничего не сохранено.
- **Ожидание:** первая ошибка откатывает весь пакет (R4.1, свойство 10).

### TC-A2-04. Учёт порядка событий в пакете

- **Шаги:** `POST [MISSING 5, comment] ` на PF до приёмки → `201`; затем в одном пакете `[ACCEPTED 1, MISSING 1]` → `409` (в пути уже 0);
  в другом пакете для чистой позиции `[ACCEPTED 3, RETURNED 3 fromAccepted=true]` → `201` (второе событие видит результат первого), `acceptedNet=0`.
- **Ожидание:** события применяются по порядку, каждое проверяется по книге с учётом предыдущих.

## A3. Аннулирование записи

**Повторяемость набора:** для каждого кейса создаётся собственная запись; teardown аннулирует остатки.

### TC-A3-01. Аннулирование автором в окне и пересчёт

- **Предусловия:** PG (cap 2), `SG` (2 шт.); FOREMAN создаёт `[ACCEPTED 1]` → запись `R1`; позиция `PARTIALLY_RECEIVED`, `acceptedQuantity=1`.
- **Шаги:**
  1. `POST /api/goods-receipts/{R1}/void` `{reason:"rcpt-{run-id}-void"}` (FOREMAN-автор) → `200`: `receipt.voidedAt` заполнено, `voidReason` совпадает,
     `position.ledger.acceptedNet=0`, `position.positionStatus=IN_DELIVERY` (событий нет), `redeliveryKept=false`.
  2. `GET /api/purchase-positions/{PG}` → `acceptedQuantity=0`, статус `IN_DELIVERY`.
  3. `GET /api/goods-receipts?positionId={PG}` → записи нет (по умолчанию аннулированные скрыты); с `includeVoided=true` запись есть и `voidedAt` не null.
  4. Уведомления: аннулирование ничего не создало.
- **Ожидание:** запись остаётся в БД, книга пересчитана, статус возвращён (R5.1, R5.3, R6.1).

### TC-A3-02. Повторное аннулирование и пустая причина

- **Шаги:**
  1. Повторный `void` той же записи → `409 error.goodsReceipt.alreadyVoided`.
  2. `void` новой записи с `reason` пустым / пробелы / отсутствует → `400`; запись остаётся неаннулированной.
- **Ожидание:** запись нельзя аннулировать дважды; причина обязательна (R5.1, R5.4).

### TC-A3-03. Права на аннулирование

- **Предусловия:** запись `R2` автора FOREMAN.
- **Шаги:**
  1. `void` ролью FOREMAN-B (не автор, FOREMAN) → `403 error.goodsReceipt.void.forbidden`.
  2. `void` ролью MANAGER (не автор) → `200`.
  3. Новая запись `R3` автора FOREMAN; чтобы проверить границу окна, ADMIN сдвигает `occurredAt` записи в БД на −25 ч
     (SQL: `UPDATE goods_receipts SET occurred_at = occurred_at - interval '25 hours' WHERE id = {R3}`), либо стенд запускается с `FOREMEN_GOODS_RECEIPT_VOID_WINDOW=PT1S`;
     затем `void` автором FOREMAN → `403 error.goodsReceipt.void.forbidden`; ролью ADMIN → `200`.
  4. `GET /api/goods-receipts/{id}` автором в окне → `canVoid=true`; у FOREMAN-B → `canVoid=false`; у MANAGER → `true`; у BUYER → `false`.
- **Ожидание:** автор — только в окне, MANAGER/ADMIN — всегда (R5.2, свойство 11).

### TC-A3-04. Аннулирование и повторная доставка

- **Предусловия:** TC-A1-03 выполнен (создана повторная отправка `RD`, статус `CREATED`/нетронутая).
- **Шаги:**
  1. Аннулировать запись `MISSING 1` (или `RETURNED 1`) из TC-A1-03 ролью MANAGER → `200`. Повторная отправка `RD` ещё в исходном виде
     (метод `SHOP_DELIVERY`, без данных, одна линия) → отменена: `GET /api/shipments/{RD}` → `CANCELLED`, `redeliveryKept=false`. (Если после аннулирования
     потребность в повторной доставке осталась по другой записи, отправка может быть сохранена — в этом случае ожидается `redeliveryKept=true`; сценарий
     фиксирует фактический исход и сверяет его с книгой.)
  2. Для сценария «изменённой» повторной доставки: BUYER редактирует `RD` (добавляет ETA через `PUT /api/shipments/{RD}`), затем аннулирование записи → ответ
     `redeliveryKept=true`, `RD` не отменена.
  3. Закрытая приёмкой отправка `SA2` после аннулирования записи **не** переоткрывается: статус остаётся `DELIVERED` (документированное ограничение, R8.6).
- **Ожидание:** автоматическая отмена только у нетронутой повторной доставки (R5.5, R8.5).

### TC-A3-05. Аннулирование возвращает `RECEIVED` в `PARTIALLY_RECEIVED`

- **Шаги:** принять позицию полностью (`RECEIVED`); аннулировать запись `ACCEPTED` → позиция `IN_DELIVERY` (событий нет) или `PARTIALLY_RECEIVED` (если остались другие записи).
- **Ожидание:** статус выводится только по допустимым рёбрам, без 409.

## A4. Повторная доставка недостающих единиц

**Повторяемость набора:** позиции создаются заново на каждый прогон (`run-id`); teardown как в общем разделе.

### TC-A4-01. Создание повторной отправки: `min(remainder, released)`

- **Предусловия:** PB (cap 3, `SB` на 2 шт.), чистая.
- **Шаги:**
  1. `POST [ACCEPTED 1, RETURNED 1 (дверной, comment)]` → `201`. Освобождено 1 (дверной возврат); `remainder = cap − committed`: `committed = max(shippedEff, acceptedNet) = max(2−0−1, 1) = 1`,
     `remainder=2`; `redeliveryQuantity = min(2, 1) = 1` → `redeliveryShipmentIds` содержит один id.
  2. `GET /api/shipments/{id}` → одна линия PB на **1** шт., метод `SHOP_DELIVERY`, `etaDate=null`, `externalOrderId` такой же, как у `SB`.
  3. `L(PB)`: `outstanding=2`, `remainder` уменьшился на 1 (новая линия учитывается в `committed`); недоотправленная единица остаётся `outstanding` и доступна для
     ручной отправки BUYER: `POST /api/shipments` на PB 1 шт. → `201` (в рамках `remainder`), 2 шт. → `400 error.shipment.line.exceedsCap`.
  4. Статус PB: `IN_DELIVERY` (создание отправки вернуло позицию из `PARTIALLY_RECEIVED`).
- **Ожидание:** повторная доставка равна `min(remainder, released)`, а не всему `outstanding` (R8.2, свойство 7).

### TC-A4-02. Одна повторная отправка на позицию за запрос

- **Шаги:** в одном пакете для чистой позиции с отправкой на 4 шт.: `[RETURNED 1 (comment), MISSING 2 (comment)]` → `201`; `redeliveryShipmentIds` длиной 1, линия на 3 шт.
- **Ожидание:** создано ровно одно событие создания отправки (R8.4).

### TC-A4-03. Повторная доставка не шлёт «shipped»

- **Шаги:** после TC-A4-01 — `GET /api/notifications` ролью MANAGER и FOREMAN.
- **Ожидание:** `notification.shipment.shipped` по повторной отправке нет; есть `notification.receipt.discrepancy`.

### TC-A4-04. Приёмка повторной отправки

- **Шаги:** `POST [ACCEPTED 1, shipmentId={redelivery}]` → `201`; для PB итого `acceptedNet=2`, `remainder`/`outstanding` по формуле, повторная отправка `DELIVERED`.
  Позиция после следующей приёмки недостающей единицы (ручная отправка + приёмка) → `RECEIVED`.
- **Ожидание:** цикл «недопоставка → повторная отправка → приёмка» замыкается.

### TC-A4-05. Лимит отправок учитывает освобождённые единицы

- **Предусловия:** позиция с внешним заказом на её 3 шт.; отправлены 3; `MISSING 1`.
- **Шаги:** ручная отправка BUYER на 1 шт. по тому же внешнему заказу → `201` (освобождённые единицы вычитаются из лимита заказа); ещё на 1 шт. → `400 error.shipment.line.exceedsOrderLine` / `exceedsCap`.
- **Ожидание:** расширенная проверка лимита (по позиции и по строке заказа) соблюдается.

## A5. Закрытие отправки

**Повторяемость набора:** отдельные отправки на прогон, teardown общий.

### TC-A5-01. Закрытие при полном разрешении линий

- **Шаги:** отправка с двумя линиями (позиции PU1 = 2, PU2 = 1); `[ACCEPTED 2 для PU1]` → отправка не терминальна (вторая линия не закрыта);
  `[MISSING 1 для PU2, comment]` → отправка `DELIVERED`, `statusSource=MANUAL`, `deliveredAt` заполнено, `nextCheckAt=null`, в событиях отправки запись «Closed by goods receipt».
- **Ожидание:** закрытие только когда `accepted + missing + returnedDoor ≥ количество` по каждой линии (R8.6, свойство 8).

### TC-A5-02. Возврат из принятого не закрывает линию

- **Шаги:** линия 2 шт.; `[ACCEPTED 1, RETURNED 1 fromAccepted=true]` → линия не закрыта (`1 + 0 + 0 < 2`), отправка не терминальна.
- **Ожидание:** `fromAccepted` не участвует в закрытии.

### TC-A5-03. Терминальная отправка не меняется

- **Шаги:** `PUT /api/shipments/{SA}` (закрытая) → `409`/`400 error.shipment.terminal`; приёмка в закрытую отправку (`shipmentId=SA`) для позиции, у которой остались
  невыполненные единицы по другой линии → допустима по правилам книги.
- **Ожидание:** приёмка меняет отправку только закрытием и повторной доставкой (R8.8).

### TC-A5-04. Отмена отправки с записями приёмки

- **Шаги:** `POST /api/shipments/{SA}/cancel` для отправки с неаннулированными записями → `409 error.shipment.hasReceipts`; после аннулирования всех записей и повторной отмены
  терминальной отправки → `error.shipment.terminal` (отправка уже `DELIVERED`).
- **Ожидание:** отправку с приёмкой отменить нельзя (R8).

### TC-A5-05. Возврат позиции в `SUPPLIER_PAID` запрещён при приёмке

- **Шаги:** позиция с отправкой и неаннулированной записью → отмена **другой** отправки позиции / отмена единственной (после аннулирования записей) —
  при наличии неаннулированной записи позиция не уходит в `SUPPLIER_PAID`; после аннулирования всех записей отмена единственной активной отправки возвращает `SUPPLIER_PAID`.
- **Ожидание:** R8.7.

### TC-A5-06. Позиция с приёмкой без отправок нельзя поставить на новую отправку

- **Шаги:** PE (CLIENT_OWN, принята без отправки, одна единица возвращена `fromAccepted`) → `POST /api/shipments` с линией PE → `409 error.shipment.position.hasReceipts`.
- **Ожидание:** защита виртуального ожидания (дизайн, раздел 5).

## A6. Книга позиции и чтение

**Повторяемость набора:** чтение на данных Setup/предыдущих кейсов своего прогона; фильтры всегда включают `projectId` прогона.

### TC-A6-01. `GET /api/purchase-positions/{id}/ledger`

- **Шаги:** для PA до приёмки, после частичной приёмки и после полной: `L(PA)` ролями MANAGER, BUYER, FOREMAN.
- **Ожидание:** поля `cap, acceptedNet, missing, returned, outstanding, surplus, inFlight, committed, remainder` — числа ≥ 0; `acceptedNet = acceptedQuantity` позиции;
  `positionStatus` совпадает со статусом позиции; ответ без денежных полей; FINANCIER/CLIENT/WORKER → `403`; FOREMAN-2 на PA → `404`.

### TC-A6-02. Список с фильтрами и пагинацией

- **Шаги:** `GET /api/goods-receipts?projectId={p}` → `200`, страница `content[]`, по умолчанию новые сверху (`occurredAt` по убыванию), `size=20`;
  `?kind=MISSING` → только `MISSING`; `?positionId=`, `?shipmentId=` → соответствующие записи; `?from=<iso>&to=<iso>` → интервал по `occurredAt`;
  `?includeVoided=true` добавляет аннулированные; `?size=1000` → не более 200 элементов; `?size=0` → не меньше 1; отрицательная `page` трактуется как 0;
  `?kind=BAD` → `400`.
- **Ожидание:** фильтры сочетаются; в элементах нет полей цены/суммы/НДС.

### TC-A6-03. `GET /api/goods-receipts/{id}`

- **Шаги:** запись с фото и без: поля `materialLabel`, `unitName`, `receivedByName`, `media[]` (id, fileName, sizeBytes, contentType), `canVoid`, `canEditMedia`.
- **Ожидание:** отсутствие `storageUri`/ключей хранилища; запись другого проекта ролью FOREMAN-2 → `404`.

### TC-A6-04. `GET /api/goods-receipts/to-receive`

- **Шаги:** с `projectId` и без (для BUYER — все проекты, для FOREMAN — только свои).
- **Ожидание:** только позиции в `IN_DELIVERY`/`PARTIALLY_RECEIVED`; сначала позиции с `DELIVERED`-отправками, затем по ETA по возрастанию (null последними), затем по id;
  в `shipments` только неотменённые линии, `unresolvedQuantity` уменьшается после приёмки; `RECEIVED` позиции отсутствуют.

## A7. Матрица прав и область видимости

**Повторяемость набора:** используются пользователи и позиции текущего прогона; каждая проверка записи создаёт запись, которая аннулируется в teardown.

### TC-A7-01. Матрица ролей

| Роль | `GET` list / get / to-receive / ledger | `POST` запись | `POST` void | `POST`/`DELETE` media | `GET` media content |
|------|-----------------------------------------|---------------|-------------|------------------------|---------------------|
| ADMIN | 200 | 201 | 200 | 200 / 204 | 200 |
| MANAGER (член) | 200 | 201 | 200 | 200 / 204 | 200 |
| FOREMAN (член) | 200 | 201 | 200 (автор в окне) | 200 / 204 (автор в окне) | 200 |
| BUYER | 200 (все проекты) | 403 | 403 | 403 | 200 |
| FINANCIER | 403 | 403 | 403 | 403 | 403 |
| WORKER | 403 | 403 | 403 | 403 | 403 |
| CLIENT | 403 | 403 | 403 | 403 | 403 |

- **Шаги:** выполнить по запросу каждой ролью; для ролей с `403` убедиться, что состояние не изменилось (ledger прежняя).
- **Ожидание:** по таблице; `403` — ключ `error.access.denied`.

### TC-A7-02. Область видимости: чужой проект даёт 404

- **Предусловия:** PX и `SX` во втором проекте; FOREMAN первого проекта; FOREMAN-2 второго.
- **Шаги:**
  1. FOREMAN: `POST [ACCEPTED 1 для PX]` → `404 error.entity.not.found`; `GET /api/purchase-positions/{PX}/ledger` → `404`.
  2. FOREMAN: `GET /api/goods-receipts?projectId={второй проект}` → пустая страница или `404` (не `200` с чужими данными).
  3. FOREMAN-2 создаёт запись по PX → `201`; FOREMAN (первый проект) пытается `GET /api/goods-receipts/{id}` → `404`; `void` → `404`; `GET /{id}/media/{m}/content` → `404`.
  4. FOREMAN: `GET /to-receive?projectId={второй проект}` → не содержит PX.
  5. Пакет `[ACCEPTED для PA (свой), ACCEPTED для PX (чужой)]` → `404`, запись по PA тоже не сохранена (откат).
  6. BUYER и ADMIN видят оба проекта.
- **Ожидание:** вне проектов — всегда 404, а не 403 (R1.5 с поправкой дизайна).

### TC-A7-03. Отсутствие токена

- **Шаги:** любой эндпоинт без `Authorization` → `401`.

## A8. Фото

**Повторяемость набора:** каждый кейс создаёт собственную запись PH-события; teardown аннулирует записи (файлы не удаляются явным API кроме `DELETE media`; в кейсах, где фото
загружаются, последним шагом выполняется `DELETE …/media/{mediaId}` для очистки).

### TC-A8-01. Загрузка и чтение

- **Предусловия:** запись `RH` (FOREMAN, `ACCEPTED 1` по PH).
- **Шаги:**
  1. `POST /api/goods-receipts/{RH}/media` multipart `files=ok1.jpg` → `200`, массив из одного `GoodsReceiptMediaDto` (`id`, `fileName=ok1.jpg`, `sizeBytes`, `contentType=image/jpeg`), без `storageUri`.
  2. `GET /api/goods-receipts/{RH}/media/{mediaId}/content` → `200`, `Content-Type: image/jpeg`, `Content-Disposition: inline; filename*=…ok1.jpg`, `X-Content-Type-Options: nosniff`, тело = исходные байты.
  3. `POST` с тремя файлами `ok1.jpg`, `ok2.png`, `ok3.webp` → `200`, три записи; `GET /{RH}` → `media.length=4`.
- **Ожидание:** метаданные без ключей хранилища (R7.5), тип и заголовки безопасности.

### TC-A8-02. Отклонение недопустимых файлов (ничего не сохраняется)

- **Шаги:**
  1. `big.jpg` (> 5 МБ) → `400 error.goodsReceipt.media.size`.
  2. `fake.jpg` (текст под видом JPEG) → `400 error.goodsReceipt.media.type` (не совпадает сигнатура).
  3. `heic.heic` → `400 error.goodsReceipt.media.type` (HEIC не принимается).
  4. 9 файлов за запрос → `400 error.goodsReceipt.media.count`.
  5. Запрос из `ok1.jpg` + `big.jpg` → `400`, и `ok1.jpg` **не** сохранён (число media не изменилось).
  6. Довести число фото записи до 10 и загрузить ещё одно → `400 error.goodsReceipt.media.count`.
- **Ожидание:** `GET /{RH}` после каждого отклонения не показывает новых файлов (R7.2).

### TC-A8-03. Права на изменение фото

- **Шаги:** FOREMAN-B (не автор) `POST media` / `DELETE media` → `403 error.goodsReceipt.media.forbidden`; MANAGER → `200` / `204`; автор после выхода из окна (см. TC-A3-03, шаг 3) → `403`;
  фото аннулированной записи → отказ (запись read-only).
- **Ожидание:** те же правила, что для аннулирования (R7.3, R5.4).

### TC-A8-04. Удаление файла

- **Шаги:** `DELETE /api/goods-receipts/{RH}/media/{mediaId}` → `204`; повторный `GET …/content` → `404`; `DELETE` несуществующего `mediaId` → `404`;
  `mediaId` от другой записи в пути этой записи → `404`.
- **Ожидание:** файл удалён из записи; объект в хранилище уходит через `deleteIfOrphan` (проверить, что соседние неудалённые фото читаются — не удалены лишним образом).

### TC-A8-05. Хранилище выключено

- **Предусловия:** стенд с отключённым хранилищем изображений (или кейс помечается «пропущен: окружение»).
- **Шаги:** `POST media` и `GET content` → `503 error.image.storage.not.configured`; события приёмки (`POST /api/goods-receipts`) при этом работают.
- **Ожидание:** сбой фото не ломает приёмку (R7, раздел «Error Handling» дизайна).

### TC-A8-06. Ссылки на фото не считаются осиротевшими

- **Шаги:** загрузить фото; дождаться цикла сверки хранилища (или вызвать задачу сверки на тестовом стенде); `GET content`.
- **Ожидание:** файл доступен (колонка `goods_receipt_media.storage_uri` зарегистрирована в `ImageReferenceLookup`).

## A9. Контракты, ошибки и защита данных

**Повторяемость набора:** без собственных данных; использует записи предыдущих наборов своего прогона.

### TC-A9-01. Локализация ошибок

- **Шаги:** вызвать `409 error.goodsReceipt.exceedsInFlight` с `Accept-Language: pl` и `ru`.
- **Ожидание:** тексты различаются и не равны ключу (PL «Ilość przekracza ilość w drodze», RU «Количество превышает находящееся в пути»).

### TC-A9-02. Время события — серверное

- **Шаги:** `POST` с лишним полем `occurredAt` = год назад в теле события.
- **Ожидание:** `201`; `occurredAt` ответа ≈ текущее серверное время (клиентское значение игнорируется); `receivedById` = вызывающий.

### TC-A9-03. Нет денег и ключей хранилища

- **Шаги:** рекурсивно обойти JSON ответов `POST /api/goods-receipts`, `GET` list/get/to-receive/ledger, void, media.
- **Ожидание:** ни одного ключа, имя которого содержит `price`, `amount`, `cost`, `margin`, `vat`, `storageUri`, `storageKey`, `clientRequestId` (в чтении), `iban`.

### TC-A9-04. Аудит

- **Шаги:** после записи, void, загрузки и удаления фото — `GET /api/audit?entityType=GOODS_RECEIPT…` (или раздел аудита админ-панели) ролью ADMIN.
- **Ожидание:** события создания, аннулирования, добавления и удаления фото присутствуют.

### TC-A9-05. Датасет дашборда

- **Шаги:** ADMIN: каталог датасетов дашборда содержит `GOODS_RECEIPTS`, поля `kind, quantity, occurredAt, project, position, voided`; поля `comment`, `void_reason`, `client_request_id`, `storage_uri` отсутствуют.
- **Ожидание:** R14.2.

---

# Часть B. Сценарии браузерного движка

Экран приёмки: `/deliveries/receipt` (мобильный первый, проверяется при ширине 360 px и один раз на десктопе 1280 px). Данные — те же, что в части A (Setup выполняется через API до открытия
браузера; свежий набор позиций на прогон). Вспомогательные идентификаторы `data-testid`:

| data-testid | Элемент |
|-------------|---------|
| `receipt-card-{positionId}` | карточка позиции на вкладке «К приёмке» |
| `receive-btn-{positionId}` | кнопка «Принять / Receive» на карточке |
| `accept-all-{shipmentId}` | кнопка «Принять всё» по отправке |
| `ledger-bar` / `ledger-accepted` / `ledger-inflight` / `ledger-outstanding` | полоса книги |
| `receive-sheet` | нижняя панель приёмки |
| `photo-picker` / `photo-input` / `photo-upload-failed` | выбор фото и повтор загрузки |
| `receipt-history` / `history-empty` | вкладка «История» |
| `history-row-{id}` / `history-main-{id}` / `history-void-{id}` | строка истории и кнопка «Аннулировать» |
| `receipt-thumb-{mediaId}` | миниатюра фото |
| `void-dialog` | диалог аннулирования |
| `ledger-panel` / `ledger-summary` / `ledger-events` | панель книги (ящики планировщика и канбана) |
| `receive-{positionId}` | кнопка «Принять» на карточке `/deliveries/upcoming` |

Тексты проверяются по ключам локали (PL/RU), язык интерфейса — RU, если не указано иное.

## Фича B1. Вход на экран, вкладки, права

**Повторяемость набора:** Setup через API с `run-id`; браузерный контекст создаётся на каждый кейс (чистая сессия); teardown — общий (аннулирование записей).

### TC-B1-01. Вкладка «К приёмке» у прораба

1. Войти FOREMAN → открыть `/deliveries/receipt` (ширина 360 px). → заголовок «Приёмка поставок», две вкладки «К приёмке» и «История»; активна «К приёмке».
2. Проверить, что на экране есть карточка `receipt-card-{PA}` с названием материала, единицей, `ledger-bar` («Принято 0 из 3 …»), чипом отправки (статус, ETA, код перевозчика) и кнопкой `receive-btn-{PA}`.
3. Проверить порядок карточек: сначала позиции с доставленными отправками, затем по ETA, без ETA — в конце.
4. Проверить отсутствие денежных значений (`zł`, `PLN`, «цена», «сумма», «НДС») на экране.
5. Позиции `RECEIVED` и позиции другого проекта отсутствуют.

### TC-B1-02. Права и маршрут

1. Войти BUYER → `/deliveries/receipt` открывается; на карточках **нет** кнопок «Принять» и «Принять всё», в истории нет «Аннулировать» (только чтение).
2. Войти FINANCIER и CLIENT → пункт меню «Приёмка поставок» отсутствует; прямой переход на `/deliveries/receipt` → страница «Нет доступа».
3. MANAGER: экран открывается, кнопки приёмки видны.

### TC-B1-03. Переключение проекта

1. FOREMAN с двумя проектами (добавить во втором проекте тоже) переключает рабочий проект → список карточек обновляется под выбранный проект; данные чужого проекта не видны.

## Фича B2. Приёмка через панель (Receive sheet)

**Повторяемость набора:** позиции PA…PF создаются заново на прогон; комментарии уникальны (`rcpt-{run-id}-ui-{n}`); teardown — аннулирование записей через API.

### TC-B2-01. Принято (ACCEPTED) частично

1. На карточке PC (cap 4, две отправки) нажать `receive-btn-{PC}` → открыта `receive-sheet`: переключатель вида (Принято / Не хватает / Возврат), степпер количества, поле комментария, `photo-picker`, «Сохранить».
2. Вид «Принято» выбран по умолчанию; количество по умолчанию = «в пути» (4).
3. Уменьшить степпер до 3 → «Сохранить». → панель закрывается без перезагрузки страницы; на карточке `ledger-accepted` = 3, `ledger-inflight` = 1, `ledger-outstanding` = 1, чип статуса «Принято частично».
4. Вкладка «История» → `history-row-*` с видом «Принято», количеством 3, именем автора и временем.

### TC-B2-02. Валидация в форме

1. Открыть панель PF, вид «Не хватает», оставить комментарий пустым → «Сохранить» заблокирована или показана ошибка «Комментарий обязателен»; запрос не уходит.
2. Ввести количество 0 / отрицательное / 4 знака после запятой → сообщение о некорректном количестве.
3. Вид «Не хватает», количество больше «в пути» → ошибка границы; ввести допустимое количество и комментарий → сохранение проходит.
4. Вид «Принято»: переключатель «из уже принятого» скрыт; вид «Возврат»: переключатель виден.
5. Серверный отказ (подготовить гонку: через API записать то же количество в пути с другого устройства, затем сохранить в UI) → показана ошибка `exceedsInFlight` локализованным текстом, данные карточки перезагружены.

### TC-B2-03. Недостача и повторная доставка

1. PB, вид «Не хватает» → количество, комментарий `rcpt-{run-id}-ui-1` → «Сохранить».
2. Карточка показывает увеличившийся `ledger-outstanding`; для освобождённых единиц отображается новая отправка (повторная доставка) и позиция возвращается в «В доставке».
3. Под BUYER (в другой сессии) в `/deliveries/shipments` видна отправка «Re-delivery after receipt …».

### TC-B2-04. Возврат из принятого

1. На принятой частично позиции: вид «Возврат» + переключатель «из уже принятого» → количество больше принятого → ошибка; допустимое → сохранение, `ledger-accepted` уменьшается.
2. На позиции в статусе «Принято» (`RECEIVED`) доступен только вид «Возврат из принятого» (другие виды недоступны или дают отказ).

### TC-B2-05. Позиция без отправки (CLIENT_OWN)

1. На карточке PE нет чипов отправок; «Принять» → количество = 2 → сохранение → статус «Принято», карточка исчезает из «К приёмке».

### TC-B2-06. Повтор при сбое сети

1. Включить эмуляцию обрыва сети (offline) после нажатия «Сохранить» (или перехват запроса с задержкой > таймаута) → показана ошибка с кнопкой «Повторить».
2. Вернуть сеть, нажать «Повторить» → запись создана **один раз** (в истории одна строка; `GET /api/goods-receipts?positionId=` — одна запись, тот же `clientRequestId`).

## Фича B3. «Принять всё»

**Повторяемость набора:** отдельные позиции и отправки на прогон; teardown общий.

### TC-B3-01. Принять всю отправку

1. На карточке отправки `SC1` нажать `accept-all-{SC1}` → диалог подтверждения с перечнем линий и количеств.
2. «Отмена» → ничего не записано.
3. Повторно нажать и подтвердить → одна пакетная операция; для каждой неразрешённой линии записано `ACCEPTED` на нераспределённое количество; карточки обновлены.
4. Отправка в истории отражена закрытой (через BUYER-сессию/страницу отправок статус «Доставлена»).
5. Повторное нажатие «Принять всё» для уже принятой отправки недоступно (кнопка скрыта/отключена).

### TC-B3-02. Принять всё с несколькими позициями

1. Отправка на две позиции (создать в Setup) → диалог перечисляет обе; подтверждение → обе позиции в `RECEIVED` / `PARTIALLY_RECEIVED` соответственно; ошибка по одной позиции (подготовить через API, чтобы одна позиция уже была
   `RECEIVED`) → пакет откатывается целиком и показана ошибка; другая позиция не изменилась.

## Фича B4. История, аннулирование

**Повторяемость набора:** записи создаются в кейсе; teardown аннулирует остатки.

### TC-B4-01. История проекта

1. Вкладка «История» → список записей проекта, новые сверху: вид, количество, автор, время, комментарий, миниатюры.
2. Пустой проект/фильтр → `history-empty`.
3. Фильтр «Показывать аннулированные» (если есть на экране) → аннулированные записи показываются зачёркнутыми с причиной.

### TC-B4-02. Аннулирование в UI

1. У записи автора в окне есть `history-void-{id}`; нажать → `void-dialog`.
2. «Аннулировать» без причины → ошибка валидации; ввести причину → подтвердить.
3. Строка зачёркнута, показана причина; кнопка аннулирования исчезла; карточка позиции на «К приёмке» вернулась к прежнему состоянию (`ledger-accepted` уменьшилось, статус пересчитан).
4. Под FOREMAN-B (не автор) кнопки аннулирования нет; под MANAGER она есть для любой записи; под BUYER нет.
5. Запись автора старше 24 ч (подготовить сдвигом `occurred_at` или настройкой окна) — у автора кнопки нет.

## Фича B5. Фото

**Повторяемость набора:** запись PH создаётся в кейсе; фото в конце удаляются через UI/API.

### TC-B5-01. Добавление фото к записи

1. Панель приёмки PH, вид «Принято», в `photo-input` выбрать `ok1.jpg` (допустимы только `image/jpeg,image/png,image/webp`) → в панели миниатюра.
2. «Сохранить» → запись создана, фото загружено после записи; в истории у записи миниатюра `receipt-thumb-{id}`.
3. Открыть миниатюру → показывается изображение (содержимое загружено через `/media/{id}/content`).
4. Большой снимок (> 5 МБ, например 4000×3000 px) перед отправкой уменьшается до ≤ 1600 px по длинной стороне, загрузка проходит.

### TC-B5-02. Сбой загрузки фото не теряет запись

1. Перехватить запрос `POST …/media` и вернуть `500`/оборвать соединение → «Сохранить».
2. Событие приёмки сохранено (в истории есть строка, карточка обновлена); в панели показан блок `photo-upload-failed` с кнопкой «Повторить загрузку фото».
3. Снять перехват, нажать «Повторить» → фото загружено, повторная запись события **не** создана.

### TC-B5-03. Ограничения на фото

1. Выбрать 9 файлов → подсказка/ошибка о лимите; 11-е фото к записи с 10 → отказ.
2. Выбрать файл неверного типа (PDF/HEIC) → не принимается полем или показана ошибка типа.
3. Удалить фото из записи автором в окне → миниатюра исчезает; у FOREMAN-B (не автор) кнопки удаления нет.

## Фича B6. Глубокая ссылка и ящики

**Повторяемость набора:** позиции прогона; уведомление создаётся записью приёмки MANAGER/FOREMAN.

### TC-B6-01. `?position={id}`

1. Открыть `/deliveries/receipt?position={PD}` (ссылка из уведомления о расхождении) → страница прокручена к `receipt-card-{PD}`, карточка выделена, открыта её история.
2. Если позиция уже в `RECEIVED` — карточка доступна через историю/поиск позиции, страница не падает; `?position=999999999` → без ошибки, сообщение «позиция не найдена в приёмке» или просто отсутствие подсветки.
3. Клик по уведомлению `notification.receipt.discrepancy` в колокольчике MANAGER → открывается этот же URL.

### TC-B6-02. Кнопка «Принять» на ближайших поставках

1. FOREMAN → `/deliveries/upcoming`: карточка позиции в `IN_DELIVERY` показывает `receive-{positionId}`; нажатие ведёт на `/deliveries/receipt?position={id}`.
2. Для позиции в `RECEIVED` / `SUPPLIER_PAID` кнопки нет (и позиции `RECEIVED` вообще не в списке).
3. BUYER / FINANCIER: кнопки нет (нет права `CREATE`).

### TC-B6-03. Книга в ящике планировщика/канбана

1. MANAGER: открыть позицию в ящике планировщика закупок или канбана → виден `ledger-panel` со сводкой `ledger-summary` («Принято X из Y …») и списком событий `ledger-events`.
2. BUYER: тот же вид, без кнопок «Принять»/«Аннулировать».
3. Позиция без событий → пустое состояние `ledger-empty`.

## Фича B7. Нижняя панель прораба и навигация

**Повторяемость набора:** без изменения данных; выполняется на любом проекте прогона.

### TC-B7-01. Пять слотов у FOREMAN

1. FOREMAN (360 px): в нижней панели 5 слотов в порядке: активный проект, график персонала, ближайшие поставки, «Приёмка поставок», добавить расход; активный слот подсвечивается на `/deliveries/receipt` и по префиксу пути.
2. Нажатие «Приёмка поставок» → `/deliveries/receipt`; панель не ломает вёрстку (5 пунктов помещаются в 360 px).
3. MANAGER, BUYER, FINANCIER, WORKER: состав нижней панели **не изменился** (соответствует FOR-11-04 и до него).
4. Боковое меню (десктоп): пункт «Приёмка поставок» есть у ролей с `GOODS_RECEIPTS READ` и нет у FINANCIER/WORKER/CLIENT.

### TC-B7-02. Локализация

1. Переключить язык на PL → «Odbiór dostaw», заголовки вкладок, виды приёмки, сообщения проверки полей и диалог аннулирования — на польском; нет сырых ключей `goodsReceipt.*` ни в RU, ни в PL.
2. Уведомление `notification.receipt.discrepancy` отображается локализованным текстом («Rozbieżność przy odbiorze dostawy» / «Расхождение при приёмке поставки»).

## Фича B8. Адаптивность и состояния

### TC-B8-01. Состояния экрана

1. Эмулировать `500` на `GET /to-receive` → сообщение об ошибке и кнопка «Повторить»; после восстановления — список.
2. Проект без ожидающих позиций → пустое состояние «Нет поставок к приёмке».
3. Индикатор загрузки (скелетон) виден при медленной сети; горизонтальной прокрутки на 360 px нет; кнопки не менее 44 px по высоте (поддержка управления одной рукой).
4. Десктоп 1280 px: макет не растягивается некрасиво, функциональность та же.

---

# Регрессия

**Повторяемость набора:** новый проект/позиции на прогон (`run-id`); общий teardown.

### TC-R-01. Правила лимита FOR-11-04 без событий приёмки не изменились

- **Шаги:** для позиции без записей приёмки повторить проверки FOR-11-04 TC-A1-03: превышение лимита заказа (`error.shipment.line.exceedsOrderLine`), превышение количества позиции
  (`error.shipment.line.exceedsCap`), дубль позиции, пустые линии, позиция вне допустимого этапа.
- **Ожидание:** результаты идентичны (свойство 6, R17.1).

### TC-R-02. Отмена отправки до приёмки возвращает `SUPPLIER_PAID`

- **Шаги:** создать и отменить отправку для позиции без приёмки (FOR-11-04 TC-A3-01).
- **Ожидание:** позиция `SUPPLIER_PAID`, прежнее поведение.

### TC-R-03. `DELIVERED` перевозчика не меняет этап позиции

- **Шаги:** отправка `STUBDELIV{run-id}` (или ручной статус `DELIVERED`) без приёмки → позиция остаётся `IN_DELIVERY`, `acceptedQuantity=0`.
- **Ожидание:** этап меняет только приёмка (R8.8).

### TC-R-04. Матрица переходов позиции не расширена

- **Шаги:** ADMIN: ручной переход `RECEIVED → IN_DELIVERY` и прочие, не описанные FOR-11-02, пользовательским API (`PUT/POST` перехода) → прежние ответы (`409`/`400`);
  `acceptedQuantity` через `PUT /api/purchase-positions/{id}` → отказ (поле только для чтения).
- **Ожидание:** `PositionTransitionPolicy` без изменений (R17.2).

### TC-R-05. Upcoming и риск простоя

- **Шаги:** `GET /api/purchase-positions/upcoming?days=14&projectId={p}` до и после полной приёмки позиции; `GET …/schedule-overlay`.
- **Ожидание:** принятая позиция исчезает из `upcoming` и не даёт `downtimeRisk`; `plannedDeliveryDate` сохраняется последним значением; остальные поля `upcoming` как в FOR-11-04.

### TC-R-06. Деньги, маржа и проформы не затронуты

- **Шаги:** до и после серии приёмок (включая излишек и возврат) сравнить `actualPurchasePrice`, `orderedQuantity`, маржу проекта, проформу и статус оплаты поставщику.
- **Ожидание:** без изменений (R17.3, «Out of scope»).

### TC-R-07. Навигация других ролей и страницы FOR-11-04

- **Шаги:** BUYER/MANAGER открывают `/deliveries/shipments`, `/deliveries/upcoming`, `/deliveries/external-orders` — страницы работают как раньше; нижние панели других ролей прежние.
- **Ожидание:** R17.4.

### TC-R-08. Закрытая приёмкой отправка не ломает планировщик

- **Шаги:** подождать 2 цикла (≈ 45 с) после закрытия отправки приёмкой при включённом опросе (`FOREMEN_PROCUREMENT_TRACKING_ENABLED=true` на стенде для этого кейса).
- **Ожидание:** `lastCheckedAt` закрытой отправки не меняется; ошибок планировщика в логах нет.

---

# Шаблон MD-репорта прогона

Файл отчёта создаётся на каждый прогон: `reports/FOR-11-05-{run-id}.md`.

```markdown
# Отчёт прогона FOR-11-05

- Run-id: `<timestamp/uuid>`
- Дата и окружение: `<дата>`, backend `<версия/commit>`, frontend `<commit>`, профиль `docker`
- Стратегия повторяемости: генератор (run-id в именах проектов, e-mail, комментариях, номерах) + teardown (аннулирование записей, деактивация проектов и пользователей)
- Конфигурация: хранилище изображений `<вкл/выкл>`, окно аннулирования `<PT24H/…>`, tracking `<вкл/выкл>`

## Сводка

| Часть | Пройдено | Провалено | Пропущено | Всего |
|-------|----------|-----------|-----------|-------|
| A. API | X | Y | Z | N |
| B. UI | X | Y | Z | N |
| Регрессия | X | Y | Z | N |
| **Итого** | X | Y | Z | N |

Итог: X пройдено / Y провалено / Z пропущено.

## Часть A. API

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-A1-01 | 2 | POST /api/goods-receipts [ACCEPTED 3, PA, SA] | 201, RECEIVED, acceptedNet=3 | 201, RECEIVED, 3 | ✅ |
| 2 | TC-A1-07 | 2 | POST … MISSING без comment | 400 error.goodsReceipt.comment.required | 400 … | ✅ |

## Часть B. UI

| # | Тест-кейс | Шаг | Действие (браузер) | Ожидание | Факт | Статус |
|---|-----------|-----|--------------------|----------|------|--------|
| 1 | TC-B2-01 | 3 | Сохранить приёмку 3 из 4 | ledger-accepted=3, чип «Принято частично» | … | ✅ |

## Регрессия

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-R-01 | 1 | POST /api/shipments сверх лимита | 400 error.shipment.line.exceedsCap | … | ✅ |

## Дефекты и замечания

| # | Тест-кейс | Шаг | Описание | Серьёзность | Ссылка/ID |
|---|-----------|-----|----------|-------------|-----------|

## Teardown

| Действие | Количество | Результат |
|----------|------------|-----------|
| Аннулировано записей приёмки | N | ✅ |
| Отменено отправок | N | ✅ (кроме отправок с записями приёмки — ожидаемо `error.shipment.hasReceipts`) |
| Деактивировано проектов / пользователей | N / N | ✅ |
```

Статусы: ✅ пройден, ❌ провален, ⏭ пропущен (указать причину, например «хранилище выключено»).
