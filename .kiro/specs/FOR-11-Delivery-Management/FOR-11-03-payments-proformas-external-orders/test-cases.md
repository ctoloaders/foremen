# Тест-кейсы: FOR-11-03 — Оплата, проформы и внешние заказы

> Часть A — API-тесты против работающего Docker-стека. Часть B — сценарии для браузерного движка
> (headless-браузер). Результат прогона — MD-репорты с таблицами (скриншоты не требуются).

## Окружение

- Стек: `docker compose up` в корне (`postgres` :5432, `liquibase`, `backend` :8080 профиль `docker`,
  `frontend` :3000). Базовый URL API: `http://localhost:8080`. UI: `http://localhost:3000`.
- Первый ADMIN поднимается переменными `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`,
  `FOREMEN_ADMIN_PASSWORD`.
- Профиль компании (`company profile`) заполнен, включая **банковский счёт** (иначе выставление проформы
  вернёт 409, см. TC-A2-06).
- Строки банковского реестра в тестах создаются **SQL-фикстурой** (вставка в `bank_transactions` с
  `title LIKE 'qa-{run-id}%'`), либо ручным вводом кассовой операции, если такой эндпоинт есть. Стадии
  позиций до `ACCEPTED` достигаются через API FOR-11-02 (решение «от имени клиента»).
- Пароли и токены берутся из переменных окружения прогона; в репорт не попадают.

## Стратегия повторяемости (обязательно)

**Генератор + Teardown.**

- `run-id` = `yyyyMMddHHmmss` (UTC). Все создаваемые сущности именуются с `run-id`: проект `QA-{run-id}`,
  пользователи `qa.manager+{run-id}@example.com`, `qa.buyer+{run-id}@example.com`,
  `qa.fin+{run-id}@example.com`, `qa.client+{run-id}@example.com`, примечания `qa-{run-id}-N`, номер
  заказа продавца `QA-{run-id}-ORD`, SQL-фикстура платежей с `title` `qa-{run-id}-…`.
- Teardown (в обратном порядке зависимостей): отвязать ссылки реестра (только для непроведённых) → удалить
  строки фикстуры реестра по `title LIKE 'qa-{run-id}%'` → отменить/удалить внешние заказы (DRAFT) →
  отменить неоплаченные проформы → перевести позиции в `CANCELLED` → удалить позиции (статус «Новое» или
  «Отменено») → деактивировать проект и пользователей. Оплаченная и **выпущенная в работу** проформа не
  удаляется через API: её чистит SQL-teardown по `project_id` проекта `QA-{run-id}` (последний шаг набора).

## Известные риски (найдено при чтении кода)

- ~~`BankTransactionController.LinkBody` не содержал полей `clientProformaId` / `externalOrderId`~~ —
  **ИСПРАВЛЕНО (задача 15.x)**: `POST /api/bank/transactions/{id}/links` и `PUT /api/bank/links/{id}` теперь
  принимают оба поля; покрыто `LedgerProcurementLinksHttpIntegrationTest` (создание, замена, удаление
  ссылок через HTTP). Кейсы TC-A4-*, TC-A7-*, TC-B5-* и TC-A12-* выполняются в штатном режиме.
- Эндпоинты реестра находятся под `/api/bank/...` (не `/api/bank-transactions`); UI-экран реестра —
  `/company/bank-transactions`.

## Форматы запросов (по коду контроллеров)

- Проформа: `POST /api/client-proformas {projectId, positionIds[], dueDate?, note?}`;
  `PUT /{id} {positionIds?, dueDate?, note?}`; `POST /{id}/issue {language?: "pl"|"ru"}` (тело необязательно);
  `POST /{id}/cancel {reason?}`; `GET /{id}/pdf`; клиентские: `GET /api/client-proformas/client/projects/{projectId}`,
  `/client/{id}`, `/client/{id}/pdf`.
- Внешний заказ: `POST /api/external-orders {projectId, sellerId, purchaseRequestId?, assignedBuyerId?,
  header:{referenceNumber, orderLink, deliveryCostNet, deliveryVatRateId, expectedShipDate,
  expectedDeliveryDate, note}, lines:[{positionId, quantity, unitPurchaseNet, purchaseVatRateId?,
  aboveCeilingAcknowledged}]}`; `PUT /{id}` (шапка), `PUT /{id}/lines {lines}`,
  `PUT /{id}/documents {documents:[{procurementDocumentId, amount?}]}`, `DELETE /{id}`,
  `POST /{id}/send`, `/cancel {reason?}`, `/mark-paid {paidDate?, note?}`, `/return {reason}`, `/unmark-paid`.
  В шагах ниже поля шапки (`referenceNumber`, `deliveryCostNet`…) подразумеваются внутри `header`.
- Реестр: `POST /api/bank/transactions/{id}/links {kind, amount, clientProformaId | externalOrderId, note?}`,
  `DELETE /api/bank/links/{linkId}`, `GET /api/bank/transactions/{id}/link-targets?kind=…&q=…`,
  `POST /api/bank/suggestions/{id}/confirm|reject`, `POST /api/bank/transactions/detect`.

## Трассировка требований

| Группа тест-кейсов | Требования |
|--------------------|------------|
| TC-A1-* (черновик) | R2, R3 |
| TC-A2-* (выставление, PDF, отмена) | R2, R4, R5, R26 |
| TC-A3-* (клиентская поверхность) | R6, R23.9 |
| TC-A4-* (платёж, шлюз) | R7, R8, R9, R19 |
| TC-A5-* (строки заказа) | R11, R12, R13, R14 |
| TC-A6-* (передача финансисту) | R15, R17, R19, R20 |
| TC-A7-* (реестр побеждает) | R7.5, R16, R17 |
| TC-A8-* (сопоставление) | R10, R19 |
| TC-A9-* (планировщик) | R18 |
| TC-A10-* (права, изоляция) | R1, R20, R21 |
| TC-A11-* (миграции) | R1.1, R2.8, R7.2 |
| TC-B1-*, B2-* | R22, R23, R25 |
| TC-B3-*, B4-* | R22, R24, R25 |
| TC-B5-* | R7, R10, R24 |
| TC-B6-* | R25, R26 |
| Регрессия R-01…R-10 | R29 |
| Весь документ | R28.4 |

## Набор данных прогона (Setup)

1. ADMIN логинится, создаёт проект `QA-{run-id}` в статусе «в реализации» (по сценарию FOR-11-00).
2. Создаёт пользователей MANAGER, BUYER, FINANCIER, CLIENT; добавляет MANAGER и CLIENT в проект
   (ACTIVE-участники). BUYER и FINANCIER в проект **не добавляются**.
3. Создаёт продавца `QA-{run-id}-SELLER` (NIP валидный тестовый), проектные материалы M1, M2, M3.
4. MANAGER создаёт три позиции `AGREED` (P1: кол-во 10, цена продажи 100.00 net, НДС 8%; P2: кол-во 3,
   цена 250.00, НДС 8%; P3: кол-во 1, цена 40.00, НДС 8%), доводит их до `ACCEPTED` через раунд
   согласования FOR-11-02 «от имени клиента». Позицию P4 (`PACKAGE`) переводит вручную в `WITH_BUYER`.
5. Ожидаемые суммы P1+P2 (проформа Pf1): net 1750.00, VAT 140.00, gross 1890.00.

---

# Часть A. API-тесты

## A1. Проформа: черновик

### TC-A1-01. Создание черновика
- Предусловия: Setup.
- Шаги:
  1. MANAGER: `POST /api/client-proformas {projectId, positionIds:[P1,P2], dueDate:null, note:"qa-{run-id}-1"}`.
     Ожидание: 201/200, `status=DRAFT`, `number=null`, 2 строки, `totalGross=1890.00`.
  2. `GET /api/client-proformas/{id}`. Ожидание: те же суммы; статусы позиций P1,P2 остались `ACCEPTED`.
  3. Сверить `totalGross` с суммой выделения планировщика FOR-11-01 для P1,P2. Ожидание: равны.

### TC-A1-02. Валидации состава
- Шаги: попытки создать черновик с (a) позицией другого проекта; (b) позицией `PLANNED`; (c) позицией
  `PACKAGE` (P4); (d) позицией без цены продажи; (e) P1 повторно при активном черновике.
- Ожидание: 409 с ключами `error.proforma.position.projectMismatch`, `.notAccepted`, `.notAgreed`,
  `.noSalePrice`, `.alreadyInProforma` соответственно.

### TC-A1-03. Редактирование и удаление черновика
- Шаги: (1) `PUT` — добавить P3, сменить срок и примечание → 200, `totalGross` пересчитан;
  (2) `PUT` — убрать P3; (3) `DELETE` → 200/204; (4) `GET` → 404; (5) создать заново (P1,P2).
- Ожидание: на каждом шаге статусы позиций остаются `ACCEPTED`; после удаления P1,P2 снова свободны.

## A2. Проформа: выставление и отмена

### TC-A2-01. Выставление
- Предусловия: черновик Pf1 (P1,P2).
- Шаги:
  1. `POST /api/client-proformas/{id}/issue {language:"pl"}`. Ожидание: 200, `status=ISSUED`, номер вида
     `PF/{yyyy}/{6 цифр}`, `issueDate` = сегодня, `dueDate` = сегодня + 7.
  2. `GET /api/purchase-positions/{P1}` и `{P2}`. Ожидание: `status=IN_PROFORMA`.
  3. `GET /api/client-proformas/{id}/pdf`. Ожидание: 200, `application/pdf`, тело начинается с `%PDF`.
  4. Повторный `GET` PDF возвращает те же байты (хэш совпадает).
  5. Повторный `issue`. Ожидание: 409 `error.proforma.status.notAllowed`.

### TC-A2-02. Заморозка позиций после выставления
- Шаги: MANAGER пытается изменить P1 (`quantity`, `salePrice`, `saleVatRate`, материал).
- Ожидание: 409 `error.proforma.position.locked` для каждого.

### TC-A2-03. Неповторяемость номеров
- Шаги: выставить вторую проформу (P3); сравнить номера.
- Ожидание: номера различны, второй номер больше первого.

### TC-A2-04. Отмена выставленной неоплаченной
- Шаги: `POST /{id}/cancel {reason:"qa-{run-id}"}` для проформы с P3.
- Ожидание: `status=CANCELLED`, `cancelReason` сохранён, P3 снова `ACCEPTED`, PDF по-прежнему доступен;
  P3 можно включить в новый черновик.

### TC-A2-05. Отмена без причины
- Шаги: `cancel` без `reason` для выставленной. Ожидание: 400.

### TC-A2-06. Профиль компании без банковского счёта
- Предусловия: временно очистить банковский счёт профиля (восстановить в teardown).
- Шаги: `issue`. Ожидание: 409 `error.proforma.companyProfile.incomplete`; статусы позиций не изменились.

## A3. Клиентская поверхность проформ

### TC-A3-01. Клиент видит свою проформу
- Предусловия: Pf1 выставлена; CLIENT залогинен.
- Шаги: `GET /api/client-proformas/client/projects/{projectId}`; `GET .../client/{id}`;
  `GET .../client/{id}/pdf`.
- Ожидание: 200; в JSON есть number, status, суммы, строки (наименование, SKU, количество, единица, цена
  нетто, НДС, брутто), `remainingGross`; **нет** полей закупочной цены, продавца, маржи, стоимости
  доставки, статуса позиции, заказа. PDF скачивается.

### TC-A3-02. Черновик клиенту не виден
- Шаги: создать черновик; CLIENT запрашивает список и `GET .../client/{draftId}`.
- Ожидание: в списке нет черновика; `GET` по id → 404.

### TC-A3-03. Изоляция
- Шаги: (a) CLIENT другого проекта запрашивает Pf1 → 404; (b) CLIENT вызывает `GET /api/client-proformas`
  → 403; (c) CLIENT вызывает `GET /api/external-orders`, `GET /api/purchase-positions` → 403.

### TC-A3-04. Сентинел закупочных цен
- Предусловия: в позицию P1 заранее записана закупочная цена-сентинел `777.77` и создан внешний заказ с
  ценой `666.66`.
- Шаги: все клиентские эндпоинты проформ; поиск строк `777.77`, `666.66`, `QA-{run-id}-SELLER` в ответах.
- Ожидание: ни одного совпадения.

## A4. Платёж по проформе и шлюз

### TC-A4-01. Частичная оплата не отпускает позиции
- Предусловия: Pf1 выставлена (gross 1890.00); фикстура: кредитовая строка реестра 1000.00 PLN,
  `title = "qa-{run-id} PF ..."`.
- Шаги: FINANCIER: `POST /api/bank/transactions/{txn}/links {kind:"PROFORMA_PAYMENT", amount:1000.00,
  clientProformaId}`.
- Ожидание: ссылка создана; проформа `PARTIALLY_PAID`, `paidGross=1000.00`; P1,P2 остались `IN_PROFORMA`;
  уведомлений `proforma.paid` и `order.releasedToBuyer` нет.

### TC-A4-02. Полная оплата отпускает позиции
- Шаги: вторая строка реестра 890.00 PLN → ссылка `PROFORMA_PAYMENT` на 890.00.
- Ожидание: проформа `PAID`, `paidAt` и `releasedAt` заполнены; P1,P2 имеют статус `WITH_BUYER`;
  в канбане закупщика (FOR-11-01) они в колонке «WITH_BUYER»; MANAGER получил `notification.proforma.paid`;
  BUYER получил `notification.order.releasedToBuyer` (одно на проформу).

### TC-A4-03. Превышение остатка
- Шаги: попытаться привязать к `PAID` проформе ещё 10.00. Ожидание: 409 (`targetOverLinked`/`overAllocated`
  или `error.bank.link.*` по правилам FOR-07-04); `paidGross` не изменился.

### TC-A4-04. Блокировка ссылки после отпуска
- Шаги: `DELETE` ссылки проформы с `releasedAt` ≠ null. Ожидание: 409 `error.bank.link.locked`.

### TC-A4-05. Удаление ссылки до отпуска
- Предусловия: проформа Pf2 с частичной оплатой.
- Шаги: удалить ссылку. Ожидание: проформа возвращается в `ISSUED`, `paidGross=0`.

### TC-A4-06. Направление и валюта
- Шаги: (a) `PROFORMA_PAYMENT` на дебетовую строку → 409 `error.bank.link.kindDirection`;
  (b) строка в EUR → 409 `error.bank.link.currencyMismatch`.

### TC-A4-07. Идемпотентность отпуска
- Шаги: повторно вызвать пересчёт (повтор ссылки тем же запросом / повторная обработка события).
- Ожидание: статусы позиций и счётчики уведомлений не меняются.

### TC-A4-08. Отмена оплаченной
- Шаги: `cancel` проформы с платежом. Ожидание: 409 `error.proforma.cancel.hasPayments`.

## A5. Внешние заказы: создание и строки

### TC-A5-01. Создание черновика заказа
- Предусловия: P1,P2 в `WITH_BUYER` (после A4-02); BUYER залогинен (в проекте не состоит).
- Шаги: `POST /api/external-orders {projectId, sellerId, referenceNumber:"QA-{run-id}-ORD",
  deliveryCostNet:50.00, deliveryVatRateId:23%, lines:[{P1, qty:6, unitPurchaseNet:80.00}, {P2, qty:3,
  unitPurchaseNet:200.00}]}`.
- Ожидание: 200/201, `status=DRAFT`; BUYER видит заказ без членства в проекте; `deliveryCostShare` строк:
  доля по закупочной стоимости (P1 480.00, P2 600.00 → 22.22 и 27.78), сумма **ровно 50.00**.

### TC-A5-02. Покрытие количества
- Шаги: добавить второй заказ с P1 qty 5 (6+5 > 10) → 409 `error.externalOrder.line.overCoverage`;
  qty 4 → допустимо (6+4=10).

### TC-A5-03. Потолок цены
- Шаги: строка P1 с `unitPurchaseNet:120.00` (> 100.00 цены продажи) без подтверждения →
  409 `error.externalOrder.line.aboveCeiling`; с `aboveCeilingAcknowledged:true` → принимается,
  в DTO `aboveCeiling=true`.

### TC-A5-04. Недоступные позиции
- Шаги: строка с позицией `CLIENT_OWN`; строка с позицией в статусе `IN_PROFORMA`; строка с позицией
  другого проекта. Ожидание: 409 `error.externalOrder.position.notPurchasable` /
  `.projectMismatch`.

### TC-A5-05. Редактирование только черновика
- Шаги: `PUT /{id}/lines` для заказа в `SENT_FOR_PAYMENT`. Ожидание: 409 `error.externalOrder.notDraft`.

### TC-A5-06. Документы поставщика
- Шаги: загрузить счёт продавца (`POST /api/procurement-documents`, сумма 1500.00, NIP = NIP продавца) →
  `PUT /{id}/documents [{procurementDocumentId, amount:1500.00}]` → 200; повторно привязать тот же документ
  к другому заказу на 100.00 → 409 `error.externalOrder.documents.overAttributed`; документ с другим NIP →
  `sellerMismatch=true` в DTO.

## A6. Отправка на оплату и передача финансисту

### TC-A6-01. Отправка
- Предусловия: заказ Eo1 (P1 qty 6, P2 qty 3) с документом.
- Шаги: BUYER `POST /{id}/send`.
- Ожидание: `status=SENT_FOR_PAYMENT`, `sentAt`; P2 (покрыта полностью) → `ORDERED`; P1 (6 из 10) остаётся
  `WITH_BUYER`, `orderedQuantity=6`; FINANCIER получил `notification.externalOrder.sentForPayment`;
  `actualPurchasePrice` P2 = 200.00, `deliveryCostShare` P2 = 27.78.

### TC-A6-02. Неполный заказ
- Шаги: `send` пустого заказа / без документа и без номера. Ожидание: 409 `error.externalOrder.send.incomplete`.

### TC-A6-03. Очередь финансиста
- Шаги: FINANCIER (в проекте не состоит) `GET /api/external-orders?status=SENT_FOR_PAYMENT`.
- Ожидание: Eo1 в списке, с документами и их `DocumentPaymentStatus`; FINANCIER **не** может
  `GET /api/purchase-positions` (403).

### TC-A6-04. Возврат закупщику
- Шаги: FINANCIER `POST /{id}/return {reason:"qa-{run-id}"}`.
- Ожидание: `status=DRAFT`, `returnReason` заполнен; P2 вернулась в `WITH_BUYER`, `orderedQuantity` P2 = 0;
  без причины → 400 `error.externalOrder.return.reasonRequired`.

### TC-A6-05. Разграничение прав
- Шаги: BUYER вызывает `mark-paid`, `return` → 403; FINANCIER вызывает `send`, `PUT /lines`, `POST` создания
  → 403.

### TC-A6-06. Оплата вручную
- Предусловия: Eo1 заново отправлен.
- Шаги: FINANCIER `POST /{id}/mark-paid {paidDate:today, note:"qa-{run-id}"}`.
- Ожидание: `status=PAID`, `paidSource=FINANCIER`; P2 → `SUPPLIER_PAID`; BUYER получил
  `notification.externalOrder.paid`; FINANCIER (актор) не получил; дата в будущем → 400
  `error.externalOrder.paidDate.future`.

### TC-A6-07. Откат ручной оплаты
- Шаги: `POST /{id}/unmark-paid`. Ожидание: `SENT_FOR_PAYMENT`, P2 → `ORDERED`.

## A7. Оплата заказа через реестр («реестр побеждает»)

### TC-A7-01. Оплата банковским платежом
- Предусловия: Eo1 в `SENT_FOR_PAYMENT`; фикстура: дебетовая строка реестра на `payableGross`.
- Шаги: ссылка `EXTERNAL_ORDER_PAYMENT` на заказ на полную сумму.
- Ожидание: `status=PAID`, `paidSource=LEDGER`; P2 → `SUPPLIER_PAID`; BUYER получил
  `notification.externalOrder.paid`.

### TC-A7-02. Реестр побеждает ручную оплату
- Предусловия: Eo2 оплачен вручную (`paidSource=FINANCIER`).
- Шаги: привязать ссылку `EXTERNAL_ORDER_PAYMENT` на полную сумму.
- Ожидание: `paidSource=LEDGER`; статус `PAID`; **повторного** уведомления `externalOrder.paid` нет.

### TC-A7-03. Оплата через документы
- Предусловия: у заказа привязан документ; ссылки `PROCUREMENT` покрывают его на 100%.
- Ожидание: заказ автоматически `PAID` (`LEDGER`).

### TC-A7-04. Исчезновение подтверждения
- Шаги: удалить ссылку `EXTERNAL_ORDER_PAYMENT` у заказа с `paidSource=LEDGER`.
- Ожидание: заказ `SENT_FOR_PAYMENT`, `paidAt=null`, P2 → `ORDERED`. Для заказа с `paidSource=FINANCIER`
  заказ остаётся `PAID`.

### TC-A7-05. Защита поздних стадий
- Предусловия: позиция заказа переведена в `IN_DELIVERY` (SQL-фикстура).
- Шаги: попытка отмены заказа / снятия подтверждения. Ожидание: 409
  `error.externalOrder.cancel.positionsInDelivery` / `error.externalOrder.unlink.positionsInDelivery`.

### TC-A7-06. Недопустимые цели ссылки
- Шаги: `EXTERNAL_ORDER_PAYMENT` на заказ в `DRAFT` / `CANCELLED` → 409
  `error.externalOrder.link.notLinkable`; на кредитовую строку → `error.bank.link.kindDirection`.

## A8. Сопоставление и подсказки

### TC-A8-01. Прямое сопоставление проформы
- Предусловия: выставленная проформа; фикстура: кредитовая строка с `title = "PF/{yyyy}/{seq} qa-{run-id}"`
  и суммой = остатку.
- Шаги: запустить обработку строки (`rerunDetection` / штатный ингест).
- Ожидание: подсказка `PROFORMA_PAYMENT` с причинами `PROFORMA_NUMBER` и `PROFORMA_AMOUNT`; FINANCIER получил
  `notification.invoice.matchProposed` (одно на транзакцию).

### TC-A8-02. Обратное сопоставление
- Предусловия: строка реестра есть заранее; затем выставляется проформа / заказ отправляется на оплату.
- Ожидание: появляется подсказка к существующей строке (reverse matcher).

### TC-A8-03. Номер без слэшей
- Шаги: `title = "PF 2026 000123"` / `"PF-2026-000123"`. Ожидание: причина `PROFORMA_NUMBER` найдена.

### TC-A8-04. Принять и отклонить
- Шаги: принять подсказку (confirm) → ссылка создана с эффектом; отклонить другую → подсказка исчезает.

### TC-A8-05. Поиск цели
- Шаги: ручной поиск цели: по номеру проформы, по номеру заказа продавца, по сумме. Ожидание: найдены.

### TC-A8-06. Существующие виды не затронуты
- Шаги: строка с `CLIENT_PAYMENT`-кандидатом (график работ) и `PROCUREMENT`-кандидатом.
- Ожидание: подсказки и оценки те же, что до изменения.

## A9. Планировщик

### TC-A9-01. Группировка по проформе и заказу
- Шаги: `GET /api/purchase-planner/...groupBy=PROFORMA`, `groupBy=EXTERNAL_ORDER` (путь — по контракту
  FOR-11-01).
- Ожидание: группы по проформам/заказам, группа без проформы `NO_PROFORMA`; у внешней группы — стоимость
  доставки, даты, продавец, статус; P1 в двух заказах присутствует в обеих группах; неизвестное значение →
  400 `error.purchasePlanner.groupBy.invalid`; `REQUEST` и `NONE` работают как прежде.

### TC-A9-02. Фильтры и поля строки
- Шаги: фильтры `proformaId`, `externalOrderId`. Ожидание: строки соответствуют; в строке `proformaNumber`,
  `externalOrderCount`.

### TC-A9-03. Фильтры `noProforma` / `noExternalOrder` (на сервере)
- Предусловия: проект `QA-{run-id}`; P1 в проформе, P2 в проформе и во внешнем заказе, P3 ни там, ни там.
- Шаги:
  1. `GET /api/purchase-positions/planner?projectId={id}&noProforma=true`. Ожидание: 200; только строки без активной
     строки проформы (P3 и позиции без проформы); P1, P2 отсутствуют.
  2. `GET /api/purchase-positions/planner?projectId={id}&noExternalOrder=true`. Ожидание: P1 и P3; P2 отсутствует.
  3. Оба фильтра вместе: только P3.
  4. `GET /api/purchase-positions/planner/groups?groupBy=PROFORMA&noProforma=true` и
     `GET /api/purchase-positions/planner/slice-counts?noProforma=true`. Ожидание: фильтр учтён в группах и счётчиках
     срезов (суммы согласованы с шагом 1); без параметров поведение прежнее.
  5. Постраничность: при более 100 подходящих строках `totalElements` соответствует отфильтрованному набору
     (фильтр применяется до разбиения на страницы, а не на клиенте).
- Покрытие автотестами: `PlannerGroupsIntegrationTest`.

## A10. Права доступа и изоляция

### TC-A10-01. Матрица
| Роль | CLIENT_PROFORMAS | EXTERNAL_ORDERS |
|------|------------------|-----------------|
| ADMIN | CRUD | CRUD + APPROVE |
| MANAGER | CRUD | READ |
| BUYER | READ | CRUD |
| FINANCIER | READ | READ + APPROVE |
| CLIENT | READ (только клиентские эндпоинты) | нет |
| FOREMAN | нет | нет |
- Шаги: для каждой роли вызвать по одному эндпоинту на каждую операцию. Ожидание: соответствие матрице
  (403 при отсутствии права).

### TC-A10-02. Изоляция проектов
- Шаги: MANAGER проекта A запрашивает проформу и заказ проекта B → 404/пустой список; BUYER и FINANCIER видят
  оба проекта.

## A11. Миграции

### TC-A11-01. Идемпотентность
- Шаги: повторный запуск `liquibase` на уже мигрированной БД. Ожидание: без ошибок, без дубликатов
  ресурсов/грантов.

### TC-A11-02. CHECK-ограничения
- Шаги: SQL-вставка ссылки `WORKER_PAYOUT` с заполненным `client_proforma_id`; ссылки `PROFORMA_PAYMENT`
  без `client_proforma_id`. Ожидание: нарушение CHECK в обоих случаях.

## A12. Список платежей проформы, ссылка заказа на проформу, ссылки реестра по HTTP

Повторяемость: генератор (`run-id` в названиях проекта, продавца, пользователей, `title` строк реестра) +
teardown по `title LIKE 'qa-{run-id}%'`.

### TC-A12-01. Список платежей в карточке проформы (STAFF)
- Предусловия: выставленная проформа PF1 на P1+P2 (брутто G); строка реестра T1 (кредит PLN, сумма > G/3).
- Шаги:
  1. FINANCIER: `POST /api/bank/transactions/{T1}/links {kind:"PROFORMA_PAYMENT", amount:G/3, clientProformaId:PF1}`.
     Ожидание: 201, в теле `linkId`.
  2. MANAGER: `GET /api/client-proformas/{PF1}`. Ожидание: 200; `payments` — один элемент
     `{linkId, bookingDate, amount = G/3, title}`; в `payments` нет контрагента, счёта, IBAN.
  3. Вторая строка T2 на остаток → `GET` снова. Ожидание: `payments` из двух элементов по дате проводки
     и `linkId`; статус `PAID`.
  4. Проформа без платежей: `payments` = `[]`.
  5. `GET /api/client-proformas/client/{PF1}` (CLIENT). Ожидание: в ответе **нет** поля `payments`
     (утечка запрещена, `ClientProformaClientDtoLeakGuardTest`).
- Покрытие автотестами: `LedgerProcurementLinksHttpIntegrationTest` (запрос
  `findProformaPaymentsByClientProformaId` на реальной БД), `ClientProformaServiceMapperPaymentsTest`.

### TC-A12-02. Ссылка заказа на проформу
- Предусловия: PF1 полностью оплачена, P2 отпущена (`WITH_BUYER`), активная строка проформы для P2 остаётся.
- Шаги:
  1. BUYER: создать заказ с P2 и отправить на оплату.
  2. BUYER: `GET /api/external-orders/{id}`. Ожидание: 200; `proformas` = `[{id: PF1, number: "PF/…", status: "PAID"}]`.
  3. Заказ с позицией, не входившей ни в одну проформу: `proformas` = `[]`.
  4. Заказ из P1 и P2 одной проформы: в `proformas` одна запись (без дублей); позиции из двух разных
     проформ дают две записи.
  5. Проформа отменена (строки неактивны): ссылка в карточке заказа исчезает.
- Покрытие автотестами: `LedgerProcurementLinksHttpIntegrationTest` (запрос `findActiveWithProformaByPositionIds`),
  `ExternalOrderServiceMapperProformasTest`.

### TC-A12-03. Ссылки реестра по HTTP, включая PUT-замену оплаченного через реестр заказа
- Предусловия: заказ O1 в `SENT_FOR_PAYMENT` (payable = N); строка реестра T3 (дебет PLN, −N).
- Шаги:
  1. FINANCIER: `POST /api/bank/transactions/{T3}/links {kind:"EXTERNAL_ORDER_PAYMENT", amount:N, externalOrderId:O1}`.
     Ожидание: 201; O1 `PAID`, `paidSource=LEDGER`, позиции `SUPPLIER_PAID`.
  2. `PUT /api/bank/links/{linkId} {kind:"EXTERNAL_ORDER_PAYMENT", amount:N-10.00, externalOrderId:O1}`.
     Ожидание: 200 с новым `linkId`; O1 → `SENT_FOR_PAYMENT`, `paidSource=null`, позиции `ORDERED`.
  3. Доплата 10.00 из той же строки (ADMIN) → O1 `PAID`; `DELETE /api/bank/links/{id}` обеих ссылок → 200;
     O1 `SENT_FOR_PAYMENT`, позиции `ORDERED`.
  4. Проформа: частичная ссылка → `PARTIALLY_PAID`; `PUT` с новой суммой → `paidGross` обновлён; остаток →
     `PAID`, позиции `WITH_BUYER`; `DELETE`/`PUT` отпущенной ссылки → 409, ничего не меняется.
  5. Отклонения (ничего не записано): черновик проформы/заказа → 409; дебет для проформы и кредит для
     заказа → 400; валюта не PLN → 409; сумма сверх остатка/payable → 409.
- Покрытие автотестами: `LedgerProcurementLinksHttpIntegrationTest` (4 сценария).

---

# Часть B. Сценарии браузерного движка

## Справочник `data-testid`

| Элемент | data-testid |
|---------|-------------|
| Кнопка «Создать проформу» (выделение планировщика) | `create-proforma` |
| Предупреждение о смешанных проектах | `selection-mixed-projects` / `request-mixed-projects` |
| Редактор черновика проформы | `proforma-draft-editor` (строки `draft-line-{positionId}`, удаление `draft-remove-{positionId}`) |
| Срок оплаты | `proforma-due-date` |
| Кнопка «Выставить» в черновике | `draft-issue` |
| Диалог выставления / язык / подтверждение | `issue-proforma-dialog` / `issue-language` / `issue-proforma-submit` |
| Диалог отмены / причина / подтверждение | `cancel-proforma-dialog` / `cancel-proforma-reason` / `cancel-proforma-submit` |
| Список / карточка / страница проформы | `proforma-list` / `proforma-card` / `proforma-detail-page` |
| Номер, статус, брутто, оплачено, остаток, прогресс | `proforma-number`, `proforma-status`, `proforma-gross`, `proforma-paid`, `proforma-remaining`, `proforma-progress` |
| PDF, копирование назначения платежа | `proforma-pdf`, `proforma-copy-title` |
| Клиент: карточки / карточка / пусто / бейдж вкладки | `client-proforma-cards` / `client-proforma-card` / `client-proformas-empty` / `client-proformas-tab-badge` |
| Список внешних заказов / новый / редактор | `external-order-list` / `external-order-new` / `external-order-editor` |
| Номер заказа продавца, доставка, НДС доставки | `external-order-reference`, `external-order-delivery-cost`, `external-order-delivery-vat` |
| Выбор позиции, строка, цена, потолок, подтверждение превышения | `external-order-position-picker`, `external-order-line-{positionId}`, `external-order-line-price-{positionId}`, `external-order-line-ceiling-{positionId}`, `external-order-line-ack-{positionId}` |
| Документы: блок / выбор / прикрепить / сумма | `external-order-documents` / `external-order-document-picker` / `external-order-document-attach` / `external-order-document-amount` |
| Отправить на оплату / подтверждение | `external-order-send` / `external-order-send-confirm` |
| Отмена заказа / причина / подтверждение | `external-order-cancel` / `cancel-external-order-reason` / `cancel-external-order-submit` |
| Причина возврата (у закупщика и в диалоге финансиста) | `external-order-return-reason` |
| Бейдж источника оплаты | `external-order-paid-source` |
| Очередь финансиста / карточка / раскрытие | `financier-queue` / `financier-order-card` / `financier-order-toggle` |
| Отметить оплаченным (кнопка / диалог / заметка / подтверждение) | `financier-mark-paid` / `mark-paid-dialog` / `mark-paid-note` / `mark-paid-submit` |
| Вернуть закупщику / снять оплату | `financier-return` / `financier-unmark-paid` |
| Диалог привязки реестра | `ledger-link-editor`, `ledger-link-kind`, `ledger-link-search`, `ledger-link-target`, `ledger-link-amount`, `ledger-link-save` |

Маршруты UI: `/deliveries`, `/deliveries/proformas`, `/deliveries/proformas/:id`,
`/deliveries/external-orders`, `/deliveries/external-orders/:id`, вкладка проекта
`/projects/{id}/clientProformas`, реестр `/company/bank-transactions`.

## B1. Проформа менеджера

### TC-B1-01. От выделения до выставления
- Роль: MANAGER. Шаги:
  1. Открыть планировщик `/deliveries`, выделить P1 и P2 (оба `ACCEPTED`, `AGREED`).
  2. Нажать `create-proforma`. Ожидание: открыт `proforma-draft-editor`, сумма 1 890,00 zł.
  3. Изменить срок (`proforma-due-date`), нажать `draft-issue`. Ожидание: диалог `issue-proforma-dialog` с
     суммой и числом позиций, выбор языка `issue-language`.
  4. Подтвердить (`issue-proforma-submit`). Ожидание: карточка показывает номер `PF/…`, статус «Wystawiona», кнопка «Pobierz PDF».
  5. Вернуться в планировщик. Ожидание: P1, P2 — этап «В проформе».

### TC-B1-02. Смешанное выделение
- Шаги: выделить позиции двух проектов. Ожидание: кнопка `create-proforma` недоступна, показано `selection-mixed-projects`.

### TC-B1-03. Отмена
- Шаги: открыть неоплаченную проформу → «Anuluj proformę» → ввести причину → подтвердить.
- Ожидание: статус «Anulowana»; позиции вернулись в «Принято».

## B2. Клиентская проформа

### TC-B2-01. Карточка клиента (390 px)
- Роль: CLIENT; вьюпорт 390×844. Шаги: открыть вкладку «Proformy» проекта.
- Ожидание: карточка `client-proforma-card` без горизонтального скролла: номер, суммы, срок, статус,
  прогресс; кнопка PDF открывает документ; `proforma-copy-title` копирует «Proforma PF/…».

### TC-B2-02. Отсутствие внутренних данных и сетевая белая схема
- Шаги: открыть вкладку и перехватить сетевые запросы.
- Ожидание: запросы только к `/api/client-proformas/client/`; в DOM нет закупочных цен и имени продавца.

### TC-B2-03. Пустое состояние
- Шаги: проект без проформ. Ожидание: локализованное «Brak proform.» / «Проформ нет.».

## B3. Заказы закупщика

### TC-B3-01. Создание и отправка
- Роль: BUYER. Шаги:
  1. `/deliveries/external-orders` → «Новый заказ», выбрать проект и продавца (SellerPicker), номер заказа.
  2. Добавить P1 (qty 6) и P2 (qty 3); поле показывает «Осталось: 4» для P1.
  3. Ввести цену закупки выше цены продажи для P1. Ожидание: маркер «Выше цены продажи», кнопка отправки
     заблокирована до `external-order-line-ack-{P1}`.
  4. Вернуть цену ≤ 100.00, прикрепить документ (`external-order-document-attach`), нажать
     `external-order-send` и подтвердить `external-order-send-confirm`.
- Ожидание: статус «Отправлен на оплату»; редактирование полей недоступно.

### TC-B3-02. Возвращённый заказ
- Шаги: после возврата финансистом закупщик открывает заказ. Ожидание: баннер с причиной возврата,
  заказ редактируем.

## B4. Очередь финансиста

### TC-B4-01. Оплата и возврат
- Роль: FINANCIER (в меню виден только «Dostawy», вкладка «Zamówienia zewnętrzne»). Шаги:
  1. Открыть `financier-queue`: заказ закупщика в списке с документами и статусами оплаты.
  2. `financier-return` без причины (`external-order-return-reason`) → поле обязательно; с причиной →
     заказ исчез из очереди.
  3. Повторно отправить (BUYER), затем `financier-mark-paid` (диалог `mark-paid-dialog`, `mark-paid-submit`) с датой по умолчанию «сегодня».
- Ожидание: бейдж `external-order-paid-source` = «Финансист»; позиции получили этап «Оплачено поставщику».

### TC-B4-02. Колокольчик
- Шаги: после действий проверить колокольчики ролей. Ожидание: FINANCIER — «Заказ на оплату»;
  BUYER — «Заказ оплачен, добавьте доставку» (клик ведёт на заказ с подсветкой); MANAGER — «Проформа
  оплачена».

## B5. Реестр

### TC-B5-01. Привязка платежа к проформе
- Роль: FINANCIER. Шаги: открыть строку реестра → «Привязать» → вид «Оплата проформы» → найти проформу по
  номеру → сумма по умолчанию = остаток → подтвердить.
- Ожидание: проформа в карточке показывает платёж; при полной сумме статус «Оплачена».

### TC-B5-02. Принять подсказку
- Шаги: строка с подсказкой: показаны причины «Номер проформы в названии платежа», «Сумма равна остатку»;
  принять. Ожидание: ссылка создана.

### TC-B5-03. Платежи в карточке проформы (STAFF)
- Роль: MANAGER. Предусловие: TC-A12-01 выполнен (проформа с платежами).
- Шаги: открыть `/deliveries/proformas/{id}`.
- Ожидание: блок `card-payments` показывает дату, сумму и название платежа для каждой ссылки; у проформы
  без платежей — `card-payments-empty`. Данных контрагента нет. В клиентской карточке (CLIENT, вкладка
  «Proformy») блока платежей нет.

### TC-B5-04. Ссылка на проформу в карточке заказа
- Роль: BUYER. Предусловие: TC-A12-02 (заказ с позицией, бывшей в проформе).
- Шаги: открыть `/deliveries/external-orders/{id}`.
- Ожидание: блок `external-order-proformas` с read-only ссылкой `external-order-proforma-{proformaId}`
  (номер `PF/…` и статус); клик ведёт на `/deliveries/proformas/{proformaId}`. У заказа без проформы
  блок не показан.

### TC-B5-05. Группы планировщика «Без проформы» / «Без заказа»
- Роль: MANAGER. Предусловие: данные TC-A9-03.
- Шаги: `/deliveries` → группировка «Проформа», открыть группу `NO_PROFORMA`; то же для «Заказ» и
  `NO_EXTERNAL_ORDER`.
- Ожидание: в сети уходит запрос с `noProforma=true` / `noExternalOrder=true` (фильтрация на сервере, не по
  первым 100 строкам на клиенте); в группе только подходящие позиции, счётчик группы совпадает с числом строк.

### TC-B5-06. Создание проформы и ссылки на заказ из диалога реестра
- Роль: FINANCIER. Шаги: открыть строку реестра → «Привязать» → «Оплата проформы» → выбрать проформу →
  подтвердить; для дебетовой строки → «Оплата заказа» → выбрать заказ → подтвердить.
- Ожидание: тело запроса содержит `clientProformaId` / `externalOrderId`; ссылка создана, в строке видна
  привязка. Покрыто UI-тестом `TransactionDrawer.test.tsx`.

## B7. Маршруты и меню (R22)

### TC-B7-01. Гейтинг маршрутов по ролям
- Шаги: для каждой роли открыть прямым URL `/deliveries/proformas`, `/deliveries/external-orders`,
  `/deliveries`.
- Ожидание: MANAGER — проформы да, заказы да (только чтение), планировщик да; BUYER — все три; FINANCIER —
  только `/deliveries/external-orders` (на `/deliveries` — экран «нет доступа»/редирект); CLIENT и FOREMAN —
  `/deliveries/*` недоступны; вкладка проекта `clientProformas` видна CLIENT и MANAGER.

### TC-B7-02. Меню
- Шаги: войти каждой ролью и сверить пункт «Dostawy» в меню.
- Ожидание: пункт ведёт на разрешённый роли маршрут; у FINANCIER — на `/deliveries/external-orders`.

## B6. Локализация

### TC-B6-01. PL ↔ RU
- Шаги: переключить язык интерфейса; пройти экраны проформ, заказов, очереди, диалога реестра.
- Ожидание: нет сырых ключей и непереведённых строк; заголовки уведомлений локализованы.

---

# Регрессия

| ID | Проверка | Ожидание |
|----|----------|----------|
| R-01 | График платежей работ (FOR-06-07): создать, опубликовать, оплатить транш через реестр `CLIENT_PAYMENT` | Работает как прежде; ссылка блокируется после записи платежа |
| R-02 | Ссылки `WORKER_PAYOUT`, `PROCUREMENT`, `OTHER_*`, `INTERNAL_TRANSFER` | Создание/удаление/направление без изменений |
| R-03 | Обнаружение и подсказки для существующих видов | Оценки и причины прежние |
| R-04 | Гейт FOR-11-00: `PLANNED → WITH_BUYER` для `AGREED` | 409 `error.purchasePosition.transition.gateViolation` |
| R-05 | Ручные ветки `PACKAGE` и `CLIENT_OWN` | Работают; `CLIENT_OWN` не попадает в внешний заказ |
| R-06 | Планировщик: `groupBy=REQUEST|NONE`, срезы, выделение | Без изменений |
| R-07 | Согласование позиций FOR-11-02 | Раунды, принятие, аналог работают; после `IN_PROFORMA` новые раунды запрещены |
| R-08 | Колокольчик уведомлений | Список, прочитано/не прочитано, удаление, счётчик |
| R-09 | OCR закупочных документов FOR-06-03/04 | Загрузка и разбор без изменений |
| R-10 | Статус заявки (`PurchaseRequest`) | Выводится из статусов позиций, включая `ORDERED`/`SUPPLIER_PAID` |

---

# Шаблон MD-репорта прогона

Run-id: `<yyyyMMddHHmmss>`. Стратегия повторяемости: `генератор + teardown`. Окружение: `<commit, стек>`.

## Часть A (API)

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-A1-01 | 1 | POST /api/client-proformas | 200, DRAFT, gross 1890.00 | | |

## Часть B (UI)

| # | Тест-кейс | Шаг | Действие | Ожидание | Факт | Статус |
|---|-----------|-----|----------|----------|------|--------|
| 1 | TC-B1-01 | 2 | Нажать `create-proforma` | Открыт редактор, сумма 1 890,00 zł | | |

## Регрессия

| # | ID | Проверка | Ожидание | Факт | Статус |
|---|----|----------|----------|------|--------|
| 1 | R-01 | График работ | Без изменений | | |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<…>`. Стратегия повторяемости: `<генератор|teardown>`.
Замечания и найденные дефекты: `<список>`.
