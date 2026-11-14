# Тест-кейсы: FOR-11-02 — Согласование позиций с клиентом

> Спека full-stack, поэтому тест-кейсы разделены на **Часть A (API-тесты против поднятого в Docker
> приложения)** и **Часть B (сценарии браузерного движка)**. Результат прогона — MD-репорты с таблицами
> (шаблон в конце), скриншоты не требуются. Документ на русском (requirements/design/tasks — на
> английском, per `.kiro/steering/test-cases.md`).

## Окружение

- Docker-стек: `docker compose up` в корне — `postgres` (:5432), `liquibase` (миграции, включая `234` и
  `235`), `backend` (:8080, профиль `docker`), `frontend` (:3000). Базовый URL API:
  `http://localhost:8080`, аутентификация — `/api/auth`.
- Первый ADMIN: `FOREMEN_ADMIN_CREATE=true` + `FOREMEN_ADMIN_EMAIL` + `FOREMEN_ADMIN_PASSWORD`.
- **Ограничение REST (из FOR-11-00):** стадии `IN_PROFORMA` и дальше недостижимы через REST (их ведёт
  FOR-11-03). Позиции в таких стадиях готовятся **SQL-фикстурой** только для позиций текущего прогона
  (`notes LIKE 'qa-{run-id}%'`):
  `docker compose exec postgres psql -U <user> -d <db> -c "UPDATE purchase_positions SET status='IN_PROFORMA' WHERE id=<id>"`.
  Просрочку решения готовим фикстурой
  `UPDATE position_approval_rounds SET decision_deadline = CURRENT_DATE - 1 WHERE id=<round>`.
- **Даты** считаются от «сегодня» (Europe/Warsaw) смещениями (`+N`/`-N` дней), чтобы сценарии были
  повторяемы в любой день.
- **Секреты-маркеры (sentinel)** для проверки конфиденциальности: закупочная цена `61.37`, доставка
  `4.21`, внутренняя цена опции `59.99`, продавец `SELLER-SENTINEL-{run-id}`. Эти значения не должны
  встречаться ни в одном ответе клиентских эндпоинтов и ни в одном DOM-узле клиентского экрана.
- Роли прогона: ADMIN, MANAGER, CLIENT (проект A), CLIENT2 (проект B), BUYER, FOREMAN.

## Стратегия повторяемости (обязательно)

**Генератор + Teardown.** `run-id` = UNIX-timestamp или короткий uuid:

- Пользователи: `qa.manager+{run-id}@example.com`, `qa.client+{run-id}@example.com`,
  `qa.client2+{run-id}@example.com`, `qa.buyer+{run-id}@example.com`, `qa.foreman+{run-id}@example.com`.
- Проекты: `Проект QA A {run-id}` (ACTIVE; MANAGER, CLIENT, FOREMAN — участники),
  `Проект QA B {run-id}` (ACTIVE; только CLIENT2).
- Материалы: `model = "QA-MAT-{run-id}-{n}"`; продавец `SELLER-SENTINEL-{run-id}`.
- Позиции: `notes = "qa-{run-id}-{номер}"`.
- **Teardown (в обратном порядке зависимостей):** перевести позиции в `CANCELLED`
  (`POST /api/purchase-positions/{id}/transition`) → удалить позиции (раунды и опции удаляются каскадом)
  → удалить уведомления пользователей прогона → удалить материалы → удалить участников и проекты →
  продавца → пользователей. `localStorage` и cookies браузера очищаются между сценариями Части B.

Все сценарии запускаемы многократно без ручной чистки БД.

## Набор данных прогона (Setup)

| Код | Проект | Путь | Стадия | Блок | Описание |
|-----|--------|------|--------|------|----------|
| P1 | A | AGREED | PLANNED | 1 | продажа 100.00, VAT продажи 8, закупка 61.37 (sentinel), qty 2 |
| P2 | A | AGREED | PLANNED | 1 | продажа 50.00, VAT 8, закупка 30.00, qty 1 |
| P3 | A | AGREED | PLANNED | 2 | продажа 200.00, VAT 8, закупка 150.00, qty 3 |
| P4 | A | AGREED | PLANNED | — | **без** цены продажи (для кейса priceMissing) |
| P5 | A | PACKAGE | PLANNED | 2 | без цен; для выбора из пакета |
| P6 | A | AGREED | PLANNED | 3 | продажа 80.00, для кейсов «куплю сам» |
| P7 | B | AGREED | PLANNED | 1 | продажа 10.00; проект клиента CLIENT2 (изоляция) |

# Часть A. API-тесты

Токены: `T_ADMIN`, `T_MANAGER`, `T_CLIENT`, `T_CLIENT2`, `T_BUYER`, `T_FOREMAN` (получены через `/api/auth`).

## A1. Доступы (ABAC) и видимость

### TC-A1-01. Клиент не видит внутренние ресурсы
**Предусловия:** Setup выполнен. **Шаги:**
1. `GET /api/purchase-positions` с `T_CLIENT` → ожидание: 403.
2. `GET /api/purchase-requests`, `GET /api/procurement/projects` с `T_CLIENT` → 403.
3. `GET /api/position-approvals/client/projects/{A}` с `T_CLIENT` → 200, пустой список (ничего не отправлено).

### TC-A1-02. Неотправленная позиция невидима клиенту
1. `GET /api/position-approvals/client/positions/{P1}` с `T_CLIENT` → 404.
2. Менеджер: `GET /api/position-approvals/staff/projects/{A}` → 200, P1 присутствует.

### TC-A1-03. Клиент не может вызвать staff-эндпоинты
1. `POST /api/position-approvals/staff/send` с `T_CLIENT` (body `{positionIds:[P1], ...}`) → 403.
2. `GET /api/position-approvals/staff/projects/{A}` с `T_CLIENT` → 403 (`error.positionApproval.staff.forbidden` либо отказ ABAC).
3. `POST .../staff/rounds/{id}/decide-on-behalf` с `T_CLIENT` → 403.

### TC-A1-04. Роли без доступа
1. `GET /api/position-approvals/client/projects/{A}` с `T_FOREMAN` → 403.
2. `POST .../staff/send` с `T_BUYER` → 403 (у BUYER только READ).
3. `GET .../staff/projects/{A}` с `T_BUYER` → 200.

### TC-A1-05. Изоляция проектов
1. `GET /api/position-approvals/client/projects/{A}` с `T_CLIENT2` → 403 (`error.access.denied`).
2. `GET /api/position-approvals/client/projects/{B}` с `T_CLIENT` → 403.
3. `GET /api/position-approvals/client/positions/{P1}` с `T_CLIENT2` → 404 (чужая позиция неотличима от несуществующей).
4. `GET /api/position-approvals/staff/projects/{B}` с `T_MANAGER` (не участник B) → 403.

## A2. Отправка на согласование (менеджер)

### TC-A2-01. Отправка блока
1. `POST /api/position-approvals/staff/send` с `T_MANAGER`, `{positionIds:[P1,P2], decisionDeadline:+7, managerNote:"qa-{run-id}"}` → 200.
2. `GET .../staff/projects/{A}` → P1 и P2 в стадии `PROPOSED`, у каждой открытый раунд `PROPOSAL`, срок +7.
3. `GET .../client/projects/{A}` с `T_CLIENT` → видны P1, P2; `clientStatus = AWAITING_DECISION`; `money.unitNet = 100.00 / 50.00`, `vatRatePercent = 8`, `unitGross = 108.00 / 54.00`, `lineGross = 216.00 / 54.00`.
4. Тело ответа клиента **не содержит** `61.37`, `30.00`, `SELLER-SENTINEL`, ключей `purchase`, `margin`, `delivery`, `seller`.

### TC-A2-02. Позиция без цены
1. `POST .../staff/send` с `[P4]` → 409 `error.positionApproval.send.priceMissing`; P4 остаётся `PLANNED`.

### TC-A2-03. Срок в прошлом
1. `POST .../staff/send` с `[P3]`, `decisionDeadline:-1` → 400 `error.positionApproval.deadline.invalid`.

### TC-A2-04. Атомарность
1. `POST .../staff/send` с `[P3, P4]` → 409 (offender = P4); P3 остаётся `PLANNED`, раундов нет.

### TC-A2-05. Повторная отправка
1. Повторить `POST .../staff/send` с `[P1]` → 409 `error.positionApproval.send.notEligible`.

## A3. Принятие клиентом (фиксированная цена)

### TC-A3-01. Успешное принятие
**Предусловия:** TC-A2-01. **Шаги:**
1. `POST .../client/rounds/{R(P1)}/accept` с `T_CLIENT`, `{optionId, expectedUnitSalePrice:100.00}` → 200.
2. Staff: P1 в стадии `ACCEPTED`, раунд `ACCEPTED`, `decidedSide = CLIENT`, `decisionDeadline` позиции очищен.
3. Клиент: `clientStatus = ACCEPTED`, цена видна, `history[0].status = ACCEPTED`.

### TC-A3-02. Цена не совпала
1. `POST .../client/rounds/{R(P2)}/accept` с `expectedUnitSalePrice:49.00` → 409 `error.positionApproval.priceChanged`; раунд остаётся `OPEN`.

### TC-A3-03. Устаревшее предложение (stale)
1. Менеджер меняет цену P2 на 55.00: `POST /api/purchase-positions/{P2}/variants/replace` (причина `REPRICED`) → 200.
2. Клиент принимает раунд P2 с `expectedUnitSalePrice:50.00` → 409 `error.positionApproval.stale`.

### TC-A3-04. Повторное принятие
1. Повторить принятие P1 → 409 `error.positionApproval.round.notOpen`.

### TC-A3-05. Чужой клиент
1. `T_CLIENT2` принимает раунд P1 → 404.

## A4. Отклонение

### TC-A4-01. Отклонение с причиной
1. Менеджер отправляет P3 (`decisionDeadline:+3`); клиент `POST .../client/rounds/{R(P3)}/reject` `{comment:"qa-{run-id} дорого"}` → 200.
2. Staff: раунд `REJECTED`, P3 в `PLANNED`; клиент: P3 `NOT_STARTED`, в истории комментарий.

### TC-A4-02. Без причины
1. Отправить P6, затем `reject` с пустым `comment` → 400 `error.positionApproval.decision.commentRequired`; раунд `OPEN`.

### TC-A4-03. Повторная отправка после отклонения
1. Менеджер снижает цену P3 (`variants/replace`, `REPRICED`, 180.00) и снова `send` → 200; `roundNo = 2`; история клиента показывает раунд 1 (REJECTED) и раунд 2 (OPEN).

## A5. Аналог

### TC-A5-01. Аналог из PROPOSED
1. P6 в `PROPOSED` (раунд 1 OPEN). Менеджер `POST .../staff/positions/{P6}/propose-analog` `{materialId:M2, unitSalePrice:90.00, internalUnitPurchasePrice:59.99, decisionDeadline:+5}` → 200.
2. Раунд 1 → `SUPERSEDED`; новый раунд `ANALOG` `OPEN`; позиция остаётся `PROPOSED` и пока с прежним материалом.
3. Клиент видит `openRound.kind = ANALOG`, опцию с ценой 90.00; в JSON нет `59.99`.

### TC-A5-02. Принятие аналога
1. Клиент принимает (`expectedUnitSalePrice:90.00`) → 200.
2. Позиция: `ACCEPTED`, материал M2, `salePrice = 90.00`; `GET /api/purchase-positions/{P6}/variants` (staff) → в истории прежний материал с причиной `ANALOG`.

### TC-A5-03. Аналог после принятия
1. Менеджер предлагает новый аналог для P6 (в `ACCEPTED`) → 200; позиция → `PROPOSED`; `previousStatus = ACCEPTED`.
2. Менеджер отзывает раунд (`withdraw`) → позиция возвращается в `ACCEPTED`.

### TC-A5-04. Недопустимые стадии и значения
1. Фикстура: P6 → `IN_PROFORMA`. `propose-analog` → 409 `error.positionApproval.stage.notAllowed`.
2. Аналог с тем же материалом и ценой, что у позиции → 409 `error.positionApproval.option.sameAsCurrent`.
3. `unitSalePrice = 0` → 400.

## A6. Выбор из пакета

### TC-A6-01. Отправка выбора
1. `POST .../staff/send-choice` `{positionId:P5, materialIds:[M1,M2,M3], decisionDeadline:+7}` → 200; P5 остаётся `PLANNED`.
2. С одним материалом → 400 `error.positionApproval.choice.optionsInvalid`; с шестью → 400.
3. Клиент: `openRound.kind = PACKAGE_CHOICE`, три опции, у каждой `money = null`; в JSON нет ни одной цены.

### TC-A6-02. Выбор
1. Клиент `accept` с `optionId` второй опции (без `expectedUnitSalePrice`) → 200.
2. P5 остаётся `PLANNED`, материал = M2, цена `null`; клиент: раунд `ACCEPTED`, `chosenOptionId`.

### TC-A6-03. Цена в варианте пакета
1. `send-choice` с ценой в опции (если API допускает поле) → 400 `error.positionApproval.option.priceNotAllowed`.

## A7. Предложение клиента (своя ссылка)

### TC-A7-01. Валидация
1. P1 (`ACCEPTED`): `POST .../client/positions/{P1}/suggest` `{link:"not a url"}` → 400 `error.positionApproval.suggestion.linkInvalid`.
2. `{link:"ftp://x"}` → 400.

### TC-A7-02. Создание и разбор
1. `{link:"https://shop.example/qa-{run-id}", comment:"хочу вот этот"}` → 200; позиция и её раунды не изменились.
2. Менеджер получает уведомление `notification.position.suggestionReceived` (TC-A12-04).
3. Менеджер `propose-analog` с `sourceSuggestionId` → предложение `RESOLVED`.
4. Второе предложение клиента → менеджер `POST .../staff/suggestions/{id}/decline` `{note:"нет в наличии"}` → `DECLINED`; клиент видит причину.

## A8. «Куплю сам»

### TC-A8-01. Из PROPOSED
1. P6 (новая) отправлена; клиент `POST .../client/positions/{P6}/client-own` `{comment:"qa"}` → 200.
2. Staff: `sourcingPath = CLIENT_OWN`, стадия `PLANNED`, открытый раунд `SUPERSEDED`, новый раунд `CLIENT_OWN` в статусе `DECLARED`; в аудите запись об изменении пути.
3. Клиент: `clientStatus = CLIENT_OWN`, `money = null`.
4. Менеджер выполняет существующий переход `PLANNED → IN_DELIVERY` (`POST /api/purchase-positions/{id}/transition`) → 200.

### TC-A8-02. Из ACCEPTED
1. Позиция в `ACCEPTED` → `client-own` → 200; итог такой же, `decisionDeadline` очищен.

### TC-A8-03. Недопустимо
1. P5 (`PACKAGE`) → 409 `error.positionApproval.clientOwn.notAllowed`.
2. Фикстура `IN_PROFORMA` → 409.

## A9. Решение от имени клиента

### TC-A9-01. Принятие от имени
1. Менеджер `POST .../staff/rounds/{R}/decide-on-behalf` `{decision:"ACCEPT", expectedUnitSalePrice, comment:"по телефону, qa-{run-id}"}` → 200; раунд `ACCEPTED`, `decidedSide = STAFF`, `onBehalf = true`.
2. Без `comment` → 400 `error.positionApproval.decision.commentRequired`.
3. `T_BUYER` → 403 `error.positionApproval.onBehalf.forbidden` (или отказ ABAC).
4. Клиент видит в истории признак «записано менеджером» без имени менеджера; уведомление менеджерам **не** создаётся.

## A10. Отзыв, срок, просрочка

### TC-A10-01. Отзыв
1. Менеджер `withdraw` открытого раунда → `WITHDRAWN`, позиция `PLANNED`, срок очищен; клиент видит позицию как `NOT_STARTED` с историей.

### TC-A10-02. Изменение срока
1. `POST .../staff/rounds/{R}/deadline` `{decisionDeadline:+10}` → 200; в обоих представлениях срок обновлён. `{decisionDeadline:-1}` → 400.

### TC-A10-03. Просрочка
1. Фикстура: срок раунда = вчера. Клиент и staff: `overdue = true`; `GET .../staff/projects/{A}?overdue=true` возвращает позицию; `GET .../client/projects/{A}/summary` → `overdue ≥ 1`.
2. Никаких автоматических смен статуса и уведомлений о просрочке не создаётся (проверить список уведомлений).

## A11. Конфиденциальность (sentinel)

### TC-A11-01. Все клиентские эндпоинты
**Предусловия:** позиция с закупкой `61.37`, доставкой `4.21`, внутренней ценой опции `59.99`, продавцом `SELLER-SENTINEL-{run-id}`, аналогом и выбором из пакета. **Шаги:**
1. Вызвать с `T_CLIENT` каждый клиентский `GET` и каждый `POST` (accept, reject, suggest, client-own) и сохранить тела ответов.
2. Ни одно тело не содержит подстрок `61.37`, `4.21`, `59.99`, `SELLER-SENTINEL`, а также ключей `purchase`, `internal`, `margin`, `delivery`, `seller`, `needBy`, `orderBy`.
3. `GET /api/purchase-positions/{id}/variants` и `/suggestions` с `T_CLIENT` → 403.
4. Staff-представление с `T_MANAGER` содержит внутренние значения (контроль, что маскирование не «выключило» данные у персонала).

## A12. Уведомления

| TC | Событие | Получатели | Ожидание |
|----|---------|------------|----------|
| TC-A12-01 | `send` двух позиций | CLIENT | ровно **одно** `notification.position.decisionRequested`, `deepLink = /projects/{A}/positionApprovals` |
| TC-A12-02 | `propose-analog` | CLIENT | `notification.position.analogProposed`, `deepLink = .../positionApprovals?position={id}` |
| TC-A12-03 | accept / reject клиентом | MANAGER | `notification.position.accepted` / `.rejected`; клиент не получает свои уведомления |
| TC-A12-04 | `suggest` | MANAGER | `notification.position.suggestionReceived` |
| TC-A12-05 | `client-own` | MANAGER + BUYER | `notification.position.clientOwn`; при назначенном закупщике заявки — только он, без заявки — все BUYER |
| TC-A12-06 | решение on-behalf | — | менеджерам уведомление **не** создаётся |

Проверка: `GET /api/notifications` под каждым получателем; прочтение и удаление чужого уведомления → 403.

## A13. Миграции

### TC-A13-01. Идемпотентность
1. Перезапустить `liquibase` дважды → ошибок нет; ресурс `POSITION_APPROVALS` один; гранты без дублей; индекс `uq_par_open_per_position` существует.
2. Параллельная вторая отправка той же позиции (два одновременных `send`) → один 200, один 409.

# Часть B. Сценарии браузерного движка

Каждый шаг — действие в браузере; результат — таблица «шаг → ожидание → факт → статус».
Базовый URL: `http://localhost:3000`; вкладка рабочей области проекта — ключ `positionApprovals`
(маршрут `/projects/{A}/positionApprovals`, deep-link `?position={positionId}`).

## Справочник data-testid (`foremen-frontend/src/features/position-approval`)
| Область | data-testid |
|---------|-------------|
| Вкладка | `position-approvals-tab-badge` |
| Клиент: экран | `client-approvals-view`, `client-empty`, `filter-{key}`, `client-block-group`, `client-position-card`, `client-status-chip`, `deadline-badge`, `money-block`, `money-{key}`, `option-compare`, `package-choice-list`, `choice-list`, `history-toggle`, `history-round` |
| Клиент: действия | `action-accept`, `action-reject`, `action-suggest`, `action-client-own`, `action-discuss` |
| Клиент: диалоги | `accept-dialog`/`accept-confirm`, `reject-dialog`/`reject-comment`/`reject-confirm`, `suggest-dialog`/`suggest-link`/`suggest-invalid`/`suggest-confirm`, `client-own-dialog`/`client-own-confirm` |
| Staff: экран | `staff-approvals-view`, `block-{n\|none}`, `position-row-{id}`, `send-block-button`, `internal-purchase`, `internal-margin`, `round-status-chip`, `round-history-drawer`, `history-block`, `history-internal`, `history-round-{roundId}`, `manager-note` |
| Staff: диалоги | `send-block-dialog`/`send-count`/`not-eligible`/`send-confirm`, `send-choice-dialog`, `propose-analog-dialog`/`client-preview`/`preview-price`/`analog-confirm`, `decide-on-behalf-dialog`/`decision-{ACCEPT\|REJECT}`/`behalf-confirm`, `withdraw-dialog`/`withdraw-confirm`, `deadline-dialog`/`deadline-confirm`, `decline-dialog`/`decline-confirm` |
| Предложения | `suggestions-inbox`, `suggestion-{roundId}`, `suggestion-propose`, `suggestion-decline` |

Повторяемость Части B: те же `run-id`, Setup и Teardown, что в Части A (данные готовятся API-вызовами
Части A, сам UI — только проверяется и используется для решений).

## B1. Клиент: вход и вкладка

### TC-B1-01. Видимость вкладки
1. Войти как CLIENT, открыть проект A (стадия execution) → ожидание: вкладка «Согласование позиций» / «Zatwierdzanie pozycji» есть; вкладки «Закупки», «Снабжение» и др. отсутствуют.
2. Войти как FOREMAN → вкладки нет.
3. Проект на стадии design → вкладки нет.

### TC-B1-02. Бейдж
1. После отправки двух позиций у клиента на вкладке бейдж `2`; после принятия одной — `1`.

## B2. Клиент: список

### TC-B2-01. Группировка и срок
1. Открыть вкладку → группы «Блок 1», «Блок 2», «Без блока» (последний); внутри ожидающие первыми.
2. Заголовок блока показывает ближайший срок «Решение до …»; для просроченного (фикстура) — красный бейдж «Срок решения истёк».
3. Фильтры «На решение / Согласованные / Покупаю сам / Все» переключают список.

### TC-B2-02. Карточка и цены
1. Карточка P1: фото, производитель, модель, SKU, ссылка на магазин, количество, статус «Ждёт вашего решения».
2. Блок цены: без НДС 100,00 · НДС 8 % · с НДС 108,00 · итого с НДС 216,00 (PLN).
3. Поиск по тексту страницы: `61,37`, `61.37`, `SELLER-SENTINEL`, «маржа», «закуп» → **не найдены**.

## B3. Клиент: решения

### TC-B3-01. Согласование
1. Нажать «Согласовать» → диалог повторяет модель и цену с НДС.
2. Подтвердить → тост «Согласовано»; карточка в статусе «Согласовано»; бейдж уменьшился; в истории раунд со статусом «Принято».

### TC-B3-02. Цена изменилась
1. Менеджер (API) меняет цену открытой позиции; клиент в уже открытом диалоге подтверждает → тост «Позиция изменилась. Обновите экран.»; список перезагружен.

### TC-B3-03. Отклонение
1. «Отклонить» без причины → кнопка подтверждения неактивна / ошибка поля; с причиной → тост «Отклонено», карточка «Готовится», в истории комментарий.

### TC-B3-04. «Куплю сам»
1. «Куплю сам» → диалог с пояснением → подтвердить; статус «Вы покупаете сами»; блок цены исчезает.

### TC-B3-05. Своя ссылка
1. «Предложить свою ссылку» → ввод `abc` → сообщение «Укажите корректный адрес http или https»; ввод `https://shop.example/qa` → тост «Предложение отправлено».

### TC-B3-06. Аналог
1. Менеджер предлагает аналог; клиент видит карточку «до/после» (прежний и новый материал, обе цены с НДС); принять → карточка показывает новый материал.

### TC-B3-07. Выбор из пакета
1. Карточка P5: 3 варианта, **ни одной цены** на экране; выбрать второй → подтверждение → вариант закреплён, статус «Готовится».

### TC-B3-08. Чат
1. Кнопка «Обсудить в чате» открывает вкладку «Komunikacja».

## B4. Клиент: уведомления и deep-link

### TC-B4-01. Колокольчик
1. После `send` у клиента в колокольчике «Новые позиции ждут вашего решения»; клик → открывается вкладка согласования.
2. После `propose-analog` клик по «Предложен аналог позиции» → открыта вкладка, карточка позиции прокручена в видимую область и подсвечена ~2 с.

## B5. Клиент: мобильная раскладка и локали

### TC-B5-01. 360 px
1. Окно 360 px: карточки в один столбец, горизонтальной прокрутки нет, кнопки действий доступны.

### TC-B5-02. PL и RU
1. Переключить язык PL ↔ RU: все подписи, статусы, диалоги, тосты и заголовки уведомлений переведены, ключей вида `positionApproval.*` на экране нет.

### TC-B5-03. Сетевой allow-list
1. Журнал сети за сценарий: все запросы клиента к `/api/position-approvals/client/**`, `/api/auth/**`, `/api/notifications/**`, `/api/client-messages/**`; **ни одного** обращения к `/api/purchase-*`, `/api/procurement/*`, `/api/finishing-materials`.

## B6. Менеджер

### TC-B6-01. Отправка блока
1. Вкладка (staff): таблица по блокам; видны закупочная цена и маржа.
2. «Отправить блок на согласование» для блока 1: диалог со списком, срок (минимум — сегодня), комментарий; позиция без цены перечислена с причиной «Нет цены продажи или ставки НДС».
3. Подтвердить → тост «Отправлено на согласование»; позиции в статусе «Предложено».

### TC-B6-02. Аналог с предпросмотром
1. «Предложить аналог» → выбрать материал, цену продажи, внутреннюю закупочную цену → блок «Так увидит клиент» содержит только цену продажи и НДС.

### TC-B6-03. Решение за клиента
1. «Записать решение клиента» → без комментария кнопка неактивна; с комментарием «по телефону» — раунд принят, в истории пометка «Записано менеджером».

### TC-B6-04. Входящие предложения
1. Блок «Предложения клиента» показывает ссылку и комментарий; «Предложить аналог» из него предзаполняет форму; «Отклонить предложение» требует причину.

### TC-B6-05. Права кнопок
1. Под BUYER кнопки отправки и решения отсутствуют (только просмотр); под MANAGER доступны по `allowedActions`.

# Регрессия

| TC | Что проверяем | Шаги и ожидание |
|----|---------------|-----------------|
| R-01 | FOR-11-00: `POST /api/purchase-positions/{id}/transition` | допускает только MANUAL-рёбра: `PLANNED → PROPOSED` вручную → 409 `transition.notAllowed`; `PLANNED → CANCELLED` → 200 |
| R-02 | FOR-11-00: варианты | `variants/replace` в `PLANNED`/`PROPOSED` работает; в `ACCEPTED` вручную → 409 `variant.stageNotAllowed`; из согласования (SYSTEM) — проходит |
| R-03 | Политика цен | клиент не видит цену `PLANNED`-позиции (позиция невидима); для `PACKAGE`/`CLIENT_OWN` цен нет; ADMIN/MANAGER/BUYER видят всё |
| R-04 | FOR-05-07 | вкладка «Оферта» у клиента работает как раньше (согласование, скидки) |
| R-05 | FOR-06-12 | вкладка «Komunikacja» у клиента работает; сообщения не привязаны к позициям |
| R-06 | Колокольчик | существующие типы уведомлений отображаются; новые типы переведены |
| R-07 | ABAC | у CLIENT нет доступа к `PURCHASE_POSITIONS`, `PURCHASE_REQUESTS`, `PROJECT_MATERIALS`; набор прав других ролей вне `POSITION_APPROVALS` не изменился |
| R-08 | Миграции | повторный запуск `liquibase` ничего не меняет |

# Шаблон MD-репорта прогона

**Run-id:** `<timestamp/uuid>` · **Стратегия повторяемости:** генератор + teardown · **Стенд:** docker compose, дата `<YYYY-MM-DD>`

## Часть A. API

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-A2-01 | 1 | POST /api/position-approvals/staff/send | 200, позиции PROPOSED | 200 | ✅ |

## Часть B. UI

| # | Тест-кейс | Шаг | Действие в браузере | Ожидание | Факт | Статус |
|---|-----------|-----|---------------------|----------|------|--------|
| 1 | TC-B3-01 | 2 | Подтвердить согласование | тост «Согласовано», статус «Согласовано» | … | ✅ |

## Регрессия

| # | Тест-кейс | Результат | Статус |
|---|-----------|-----------|--------|
| 1 | R-01 | … | ✅ |

**Итог:** X пройдено / Y провалено / Z пропущено. Найденные дефекты: `<список с ссылками на bug-report.md>`.
