# Тест-кейсы: FOR-05-07 — Подготовка оферты и клиентское согласование

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерные экраны**:

- **API** — против работающего в Docker приложения (поднятый docker-стек, НЕ Testcontainers): жизненный цикл оферты, скидки, раунды переговоров, выбор чистовых материалов клиентом, уведомления, ABAC-гейтинг и конфиденциальность себестоимости. Базовый URL `http://localhost:8080`, auth под `/api/auth`.
- **UI** — переиспользованная вкладка `pricing` (`/projects/:projectId/pricing`), ставшая вкладкой «Оферта / согласование» (`OfferTab`): лента переговоров (`NegotiationThread`), выбор чистовых материалов клиентом (`ClientMaterialSelection`), а также колокол уведомлений (`NotificationBell`) в верхней панели (`TopBar`). UI-сценарии исполняются браузерным движком (headless / browser automation).

**Результат прогона для обеих частей — MD-репорты с таблицами** (шаг → запрос/действие → ожидание → факт → статус). **Скриншоты не требуются.**

Покрываемые требования: R1 (подготовка оферты из PRICED-сметы), R2 (скидки: валидация, порядок применения, пересчёт итогов), R3 (машина статусов оферты), R4 (раунды переговоров), R5 (клиентские действия + ABAC), R6 (эскалация), R7 (неизменяемость согласованной версии), R8 (вкладка Offer_Tab), R11 (выбор чистового материала клиентом), R12 (пер-материальная скидка LINE-scope), R13 (сервис уведомлений + колокол), R14 (триггеры уведомлений оферты), R15 (конфиденциальность себестоимости), R16 (ужесточённая ролевая модель), R17 (видимость оферты для клиента), R18 (стейджинг), R20 (service-lock сметы).

---

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции `128`–`135`), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN поднимается через `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
4. Перед прогоном дождаться готовности `backend` и завершения миграций (появление ресурсов `OFFERS`/`NOTIFICATIONS`, операции `APPROVE`, снятие грантов ESTIMATE у FOREMAN/WORKER/FINANCIER).
5. UI-сценарии открывают `http://localhost:3000` и логинятся соответствующей ролью.

### Токены ролей для API

Все API-запросы (кроме логина) выполняются с заголовком `Authorization: Bearer <accessToken>` соответствующей роли. В сценариях используются алиасы: `ADMIN`, `MANAGER`, `CLIENT`, `FOREMAN`, `WORKER`, `FINANCIER`, а также `CLIENT2` (второй клиент того же проекта) и `CLIENT_OTHER` (клиент другого проекта / чужой пользователь для негативных кейсов владения).

---

## Стратегия повторяемости

**Комбинированная — генератор `run-id` + teardown. Применяется ко ВСЕМ группам ниже.**

- **Генератор:** на каждый прогон формируется `run-id` (timestamp/uuid). Все создаваемые пользователи — с email вида `test+{role}-{run-id}@example.com`; проект, смета и оферта создаются заново на каждый прогон. Это исключает коллизии и ограничение «не более одной нетерминальной оферты на проект» (R1.4) между прогонами.
- **Teardown:** в конце набора оферта переводится в терминальный статус (`WITHDRAWN`/`REJECTED`) или проект изолируется под `run-id`; созданные пользователи деактивируются; созданные уведомления удаляются их владельцами (`DELETE /api/notifications/{id}`). Сид-данные (ресурсы/роли/операции, пакеты START/COMFORT/PRESTIGE, системные справочники) НЕ изменяются — только читаются.
- Каждая группа кейсов явно опирается на этот блок (`Повторяемость: генератор run-id + teardown`).

### Общий Setup, используемый большинством групп

- **S0. Логин ADMIN** — `POST /api/auth/login` `{email, password}` → **200** + `accessToken` (ADMIN).
- **S1. Поднять MANAGER и CLIENT** — ADMIN создаёт пользователей `MANAGER` и `CLIENT` с email под `run-id`, роли MANAGER и CLIENT соответственно; логин каждого → сохранить `accessToken`.
- **S2. Проект + PRICED-смета** — ADMIN/MANAGER создаёт проект под `run-id`, в нём смету; смета доводится до статуса `PRICED` (назначены работы/материалы/цены; задан `appliedPackageCode`, например `COMFORT`); CLIENT и MANAGER добавляются членами проекта.
- **S3. (при необходимости) второй клиент/чужой клиент** — `CLIENT2` (член того же проекта) и `CLIENT_OTHER` (член другого проекта под тем же `run-id`).

> Примечание по non-bootstrap ролям: бутстрапится только ADMIN. Негативные ABAC-кейсы для FOREMAN/WORKER/FINANCIER документируются ожидаемыми результатами и подкреплены серверными интеграционными тестами (`ABAC`/seed-тесты из задачи 9.4 и 1.9). Там, где роль нельзя поднять в прогоне, статус в репорте — `N/A (покрыто интеграционным тестом)`.

---

# Часть A. API-тесты (docker-стек)

## Фича 1. Подготовка оферты из PRICED-сметы (R1)

Покрывает R1.1, R1.2, R1.3, R1.4, R1.5, R1.6, R1.7. **Повторяемость:** генератор `run-id` + teardown.

### TC-PREP-01 — Подготовка оферты из PRICED-сметы → 201/200, DRAFT, revision=1
**Предусловия:** S0–S2 (смета в статусе `PRICED`, `appliedPackageCode = COMFORT`).
1. `POST /api/offers` (MANAGER) `{"projectId": <projectId>}` → **200/201**; тело: `status == "DRAFT"`, `revision == 1`, `estimateId == <estimateId>`.
2. Проверить, что `selectedPackageCode == "COMFORT"` — пакет засижен из `appliedPackageCode` (R1.6).
3. Проверить итоги: `totalNet`/`totalVat`/`totalGross` присутствуют и неотрицательны; `totalNet` равен клиентскому нетто сметы (скидок пока нет) (R1.3).

### TC-PREP-02 — Подготовка из не-PRICED сметы → 400 error.offer.estimate.not.priced
**Предусловия:** проект под `run-id` со сметой в статусе `DRAFT` (не PRICED).
1. `POST /api/offers` (MANAGER) `{"projectId": <projectId>}` → **400**, код `error.offer.estimate.not.priced`; оферта НЕ создана (повторный `GET` офферов проекта пуст).

### TC-PREP-03 — Вторая нетерминальная оферта запрещена → 409 error.offer.active.exists
**Предусловия:** TC-PREP-01 успешен (активная DRAFT-оферта существует).
1. `POST /api/offers` (MANAGER) `{"projectId": <projectId>}` ещё раз → **409**, код `error.offer.active.exists`.

### TC-PREP-04 — appliedPackageCode = null → оферта создаётся с selectedPackage = null
**Предусловия:** проект под `run-id`, смета `PRICED`, `appliedPackageCode = null`.
1. `POST /api/offers` (MANAGER) → **200/201**; `selectedPackageCode == null`; создание НЕ падает (R1.7).

### TC-PREP-05 — Смена пакета пересчитывает итоги (R1.5)
**Предусловия:** активная DRAFT-оферта (TC-PREP-01).
1. `POST /api/offers/{offerId}/select-package` (MANAGER) `{"packageCode": "PRESTIGE"}` → **200**; `selectedPackageCode == "PRESTIGE"`.
2. Итоги `totalNet/totalGross` пересчитаны для PRESTIGE (отличаются от COMFORT, если цены пакетов различны).

**Teardown группы:** `POST /api/offers/{offerId}/withdraw` (MANAGER) → оферта `WITHDRAWN`.

---

## Фича 2. Скидки оферты: валидация и пересчёт (R2)

Покрывает R2.2, R2.3, R2.4, R2.6, R2.7, R2.8, R3.9, R5.3. **Повторяемость:** генератор `run-id` + teardown.

### TC-DISC-01 — Отрицательное значение скидки → 400
1. `POST /api/offers/{offerId}/discounts` (MANAGER) `{"scope":"GLOBAL","kind":"PERCENT","value":-5}` → **400** (локализованный код валидации); скидка не создана.

### TC-DISC-02 — PERCENT > 100 → 400
1. `POST .../discounts` (MANAGER) `{"scope":"GLOBAL","kind":"PERCENT","value":120}` → **400**.

### TC-DISC-03 — ABSOLUTE > базы scope → 400
1. `POST .../discounts` (MANAGER) `{"scope":"GLOBAL","kind":"ABSOLUTE","value":<больше, чем totalNet>}` → **400**.

### TC-DISC-04 — Валидная скидка применяется и пересчитывает итоги (R2.6)
1. Запомнить текущий `totalNet0`.
2. `POST .../discounts` (MANAGER) `{"scope":"GLOBAL","kind":"PERCENT","value":10}` → **200**; скидка создана.
3. `GET /api/offers/{offerId}` (MANAGER) → `totalNet == totalNet0 * 0.9` (с учётом округления до 2 знаков); `totalNet >= 0` (R2.7).
4. Удалить скидку → итоги возвращаются к `totalNet0` (пересчёт на удаление, R2.6).

### TC-DISC-05 — Скидку пишет не-исполнитель (CLIENT) → 403 (R5.3/R2.8)
1. `POST .../discounts` (CLIENT) `{"scope":"GLOBAL","kind":"PERCENT","value":5}` → **403** (ABAC: клиенту запрещена прямая запись OfferDiscount).

### TC-DISC-06 — Запись скидки на терминальную оферту → 409 (R3.9)
**Предусловия:** оферта в терминальном статусе (например `WITHDRAWN`).
1. `POST .../discounts` (MANAGER) → **409**, код `error.offer.illegal.transition` (или эквивалент терминальной неизменяемости).

### TC-DISC-07 — Override-and-cancel: GLOBAL отменяет CATEGORY/LINE (R2.5)
1. Создать `LINE`-скидку на линию L1 и `CATEGORY`-скидку на её категорию → итог отражает CATEGORY поверх LINE для L1.
2. Создать `GLOBAL`-скидку → итог считается только по GLOBAL (LINE и CATEGORY вытеснены); результат детерминирован и не зависит от порядка вставки.

**Teardown группы:** удалить созданные скидки; `withdraw` оферты.

---

## Фича 3. Машина статусов и жизненный цикл оферты (R3, R7, R17)

Покрывает R3.1–R3.9, R7.1, R7.2, R17.4, R17.6. **Повторяемость:** генератор `run-id` + teardown.

### TC-LC-01 — send: DRAFT→SENT, проект READY_TO_OFFER→OFFERED, видимость ON_APPROVAL (R3.2/R17.6)
**Предусловия:** активная DRAFT-оферта; проект в `READY_TO_OFFER`.
1. `POST /api/offers/{offerId}/send` (MANAGER) → **200**; `status == "SENT"`.
2. `GET` проекта → `status == "OFFERED"`.
3. Видимость оферты → `ON_APPROVAL` (клиент теперь видит оферту, см. Фичу 8).

### TC-LC-02 — Клиент одобряет: SENT→APPROVED, проект OFFERED→APPROVED, фиксация revision (R3.5/R7.1)
**Предусловия:** оферта `SENT`, клиент видит её.
1. `POST /api/offers/{offerId}/approve` (CLIENT) → **200**; `status == "APPROVED"`; `approvedRevision == <текущая revision>`.
2. `GET` проекта → `status == "APPROVED"`.

### TC-LC-03 — Клиент отклоняет: SENT/COUNTERED→REJECTED (R3.6)
1. `POST /api/offers/{offerId}/reject` (CLIENT) → **200**; `status == "REJECTED"` (терминальный).

### TC-LC-04 — Исполнитель отзывает нетерминальную оферту → WITHDRAWN (R3.7)
1. `POST /api/offers/{offerId}/withdraw` (MANAGER) → **200**; `status == "WITHDRAWN"`.

### TC-LC-05 — Недопустимый переход (выход из терминального) → 409 error.offer.illegal.transition (R3.8)
**Предусловия:** оферта `APPROVED`.
1. `POST /api/offers/{offerId}/send` (MANAGER) → **409**, код `error.offer.illegal.transition`; статус не изменился.

### TC-LC-06 — Терминальная неизменяемость: запись после APPROVED → 409 (R3.9/R7.2)
**Предусловия:** оферта `APPROVED`.
1. `POST .../discounts`, `POST .../select-package`, `POST .../negotiation/...` (MANAGER) → каждый **409**; согласованная версия (итоги/пакет/скидки) не меняется.

**Teardown группы:** оферта уже терминальна; деактивировать пользователей `run-id`.

---

## Фича 4. Раунды переговоров (R4, R6)

Покрывает R4.1–R4.9, R3.3, R3.4, R6.1–R6.6. **Повторяемость:** генератор `run-id` + teardown. Предусловие для всех: оферта в статусе `SENT`, клиент видит её.

### TC-NEG-01 — Клиент открывает запрос скидки (без фигуры) → CHANGES_REQUESTED (R4.1/R3.3)
1. `POST /api/offers/{offerId}/negotiation/requests` (CLIENT) `{"scope":"GLOBAL","justification":"прошу скидку"}` → **200**; создан раунд `kind=DISCOUNT_REQUEST`, `value`/`valueKind` отсутствуют (R4.1).
2. Оферта `status == "CHANGES_REQUESTED"`.
3. Попытка клиента передать `value`/`kind` в запросе → поле проигнорировано/отклонено (фигуру клиент не задаёт).

### TC-NEG-02 — LINE-scope запрос с clientComment (R4.2/R12.1)
1. `POST .../negotiation/requests` (CLIENT) `{"scope":"LINE","targetId":<lineId>,"justification":"дорогая плитка","clientComment":"можно дешевле?"}` → **200**; раунд LINE-scope с `clientComment`.

### TC-NEG-03 — Менеджер предлагает фигуру: → COUNTERED (R4.3/R3.4)
**Предусловия:** открыт DISCOUNT_REQUEST (TC-NEG-01).
1. `POST .../negotiation/rounds/{roundId}/propose` (MANAGER) `{"kind":"PERCENT","value":7}` → **200**; раунд `MANAGER_PROPOSAL` с `value=7`.
2. Оферта `status == "COUNTERED"`.

### TC-NEG-04 — Отклонение менеджера без объяснения → 400 error.offer.reject.explanation.required (R4.8/R6.6)
1. `POST .../negotiation/rounds/{roundId}/reject` (MANAGER) `{"explanation":""}` → **400**, код `error.offer.reject.explanation.required`; `{"explanation":"   "}` (пробелы) → тоже **400**; раунд REJECT НЕ записан.

### TC-NEG-05 — Отклонение менеджера с объяснением → записан MANAGER_REJECT
1. `POST .../negotiation/rounds/{roundId}/reject` (MANAGER) `{"explanation":"бюджет не позволяет"}` → **200**; раунд `MANAGER_REJECT` записан с объяснением.

### TC-NEG-06 — Клиент принимает предложение: материализация скидки + bump revision (R4.5)
**Предусловия:** есть открытый `MANAGER_PROPOSAL` (TC-NEG-03), `revision == r0`.
1. `POST .../negotiation/rounds/{roundId}/accept` (CLIENT) → **200**; создан применённый `OfferDiscount`, соответствующий предложению.
2. `GET /api/offers/{offerId}` → `revision == r0 + 1`; итоги пересчитаны с учётом новой скидки.

### TC-NEG-07 — Клиент отклоняет предложение; менеджер отвечает снова (R4.4)
1. `POST .../rounds/{roundId}/decline` (CLIENT) → **200**; раунд `CLIENT_DECLINE`.
2. Менеджер может снова `propose` или `reject` (с объяснением) — новый раунд открывается.

### TC-NEG-08 — Действие над уже разрешённым раундом → 409 error.offer.round.resolved (R4.7)
**Предусловия:** раунд уже `ACCEPTED`.
1. Повторный `accept`/`decline`/`propose` по тому же `roundId` → **409**, код `error.offer.round.resolved`.

### TC-NEG-09 — Эскалация: предложение сверх порога требует ADMIN (R6.3/R6.5)
**Предусловия:** `Escalation_Threshold` (GLOBAL или per-project) задан, напр. PERCENT ≤ 15%.
1. `POST .../propose` (MANAGER) `{"kind":"PERCENT","value":40}` без ADMIN-одобрения → **4xx**, локализованный код эскалации (предложение/акцепт отклонены).
2. С ADMIN-одобрением (`adminApproved=true` через соответствующий путь) → **200**.

### TC-NEG-10 — Broader-scope вытесняет narrower (R4.9)
1. Открыть и принять `LINE`-предложение по L1; затем создать `GLOBAL`-предложение и принять его.
2. Проверить, что `LINE`-скидка по L1 помечена `SUPERSEDED` / не применяется; действует только GLOBAL.

**Teardown группы:** `withdraw` оферты; удалить сопутствующие уведомления.

---

## Фича 5. Выбор чистового материала клиентом (R11, R12)

Покрывает R5.8, R11.1–R11.8, R12.1–R12.5. **Повторяемость:** генератор `run-id` + teardown. Предусловие: оферта `SENT`/`COUNTERED`, смета `DRAFT`, в пакете есть чистовой Placeholder-слот.

### TC-MAT-01 — Клиент выбирает конкретный материал на чистовой Placeholder → 200 (R11.3)
1. `POST /api/offers/{offerId}/finishing/{materialLineId}/choose-concrete` (CLIENT) `{"materialId":<finishingMaterialId>}` → **200**; линия получила `Concrete_Material`; итоги пересчитаны (R11.6).

### TC-MAT-02 — Клиент пишет в НЕ-чистовую (construction) линию → 403/400 (R5.8/R11.4)
1. `POST .../finishing/{constructionLineId}/choose-concrete` (CLIENT) → **4xx**; запись отклонена сервером.

### TC-MAT-03 — Клиент пишет в чистовую линию с уже выбранным материалом (не Placeholder) → 4xx (R11.4)
1. Для линии, где `Concrete_Material` уже выбран, `choose-concrete` (CLIENT) → **4xx**; изменение отклонено.

### TC-MAT-04 — Клиент чужого проекта пишет в линию → 403/404 (R11.5)
1. `POST .../choose-concrete` (CLIENT_OTHER) → **403/404** (own-project гейт).

### TC-MAT-05 — Выбор только пока negotiable и смета DRAFT (R11.7)
**Предусловия:** оферта терминальна ЛИБО смета не `DRAFT`.
1. `POST .../choose-concrete` (CLIENT) → **409/4xx**; выбор чистовых заморожен.

### TC-MAT-06 — Смена пакета клиентом ре-деривирует чистовую выборку + bump revision (R11.1/R11.2)
1. `POST /api/offers/{offerId}/select-package` (CLIENT) `{"packageCode":"PRESTIGE"}` → **200**; `Package_Derived_Finishing_Selection` пересобрана из пакета; `revision` увеличилась; итоги пересчитаны.

### TC-MAT-07 — Пер-материальная LINE-скидка — тот же механизм раунда (R12.1/R12.2)
1. `POST .../negotiation/requests` (CLIENT) `{"scope":"LINE","targetId":<finishingLineId>,"justification":"..."}` → **200**; это обычный LINE-scope раунд (не отдельный механизм); далее проходит тот же propose/accept/эскалацию (R12.3/R12.4).

### TC-MAT-08 — Новый пер-материальный запрос на терминальной оферте → 409 (R12.5)
1. На `APPROVED`/`WITHDRAWN` оферте `POST .../negotiation/requests` (CLIENT, scope LINE) → **409**.

**Teardown группы:** `withdraw` оферты.

---

## Фича 6. ABAC-гейтинг OFFERS/NOTIFICATIONS и ужесточённая ролевая модель (R5, R16)

Покрывает R5.1, R5.4, R5.6, R16.1, R16.2, R16.3, R16.6. **Повторяемость:** генератор `run-id` + teardown.

### TC-ABAC-01 — Клиентский approve использует (OFFERS, APPROVE)
1. CLIENT с грантом `OFFERS R(own)+APPROVE` вызывает `POST /api/offers/{offerId}/approve` → **200** (на видимой `SENT`/`COUNTERED` оферте).

### TC-ABAC-02 — Caller без гранта OFFERS → 403 (R5.6)
1. Пользователь без OFFERS-гранта (например без членства) вызывает `GET /api/offers/{offerId}` → **403** (серверный отказ, не только UI).

### TC-ABAC-03 — FOREMAN/WORKER/FINANCIER отказано в OFFERS и ESTIMATE (R16.1/R16.2/R16.3)
1. FOREMAN/WORKER/FINANCIER → `GET /api/offers/{offerId}` → **403**.
2. Те же роли → доступ к ESTIMATE/costs/margins → **403**. _(Если роль не поднята в прогоне — `N/A, покрыто seed/ABAC интеграционным тестом задач 1.9/9.4.)_

### TC-ABAC-04 — NOTIFICATIONS доступны всем ролям (READ/UPDATE/DELETE), без CREATE (R13.13)
1. Любая роль → `GET /api/notifications` → **200**.
2. Прямого end-user CREATE нет: отсутствует endpoint/грант на создание уведомления пользователем.

### TC-ABAC-05 — Множественные CLIENT-члены получают равные гранты (R5.5)
1. CLIENT и CLIENT2 (оба члены проекта) → каждый `GET /api/offers/{offerId}` → **200** (на видимой оферте).

**Teardown группы:** деактивировать пользователей `run-id`.

---

## Фича 7. Конфиденциальность себестоимости (R15)

Покрывает R5.11, R8.10, R10.12, R15.1, R15.2, R15.4, R15.5. **Повторяемость:** генератор `run-id` + teardown.

### TC-CONF-01 — Клиентский ответ оферты НЕ содержит cost/margin/себестоимость/estimate unit price
**Предусловия:** оферта видима клиенту (`ON_APPROVAL`).
1. `GET /api/offers/{offerId}` (CLIENT) → **200**; тело содержит ТОЛЬКО offer-level поля (`totalNet/Vat/Gross`, per-line/per-category offer price, package prices, applied discounts, finishing `Type_Price_Range` / цена выбранного продукта).
2. Проверить, что в JSON НЕТ полей `costNet`/`cost`/`margin*`/`workerRate*`/`workerTypeTier*`/`estimateUnitPrice` и любых себестоимостных/маржинальных имён (структурно — ни на одном уровне вложенности).
3. Ссылка на смету присутствует ТОЛЬКО как id (`estimateId`), без развёрнутых смётных цен.

### TC-CONF-02 — Клиент не достаёт смету/costs через negotiation-эндпоинты (R5.11)
1. Любой клиентский чит/запрос ленты переговоров `GET .../negotiation` (CLIENT) → **200**, но без себестоимости/маржи/смётных unit-price.

**Teardown группы:** `withdraw` оферты.

---

## Фича 8. Видимость оферты для клиента (R17)

Покрывает R5.9, R17.4, R17.5, R17.9. **Повторяемость:** генератор `run-id` + teardown.

### TC-VIS-01 — DRAFT-видимость: клиенту отказано, неотличимо от отсутствия → 404 (R5.9/R17.5)
**Предусловия:** оферта `DRAFT` (ещё не отправлена клиенту).
1. `GET /api/offers/{offerId}` (CLIENT) → **404**, код `error.entity.not.found` (не 403 — неотличимо от «не существует»).
2. `POST /api/offers/{offerId}/approve` (CLIENT) → **404** (действие недоступно).

### TC-VIS-02 — После send видимость ON_APPROVAL: клиент получает оферту (R17.4)
**Предусловия:** TC-LC-01 (оферта `SENT`).
1. `GET /api/offers/{offerId}` (CLIENT) → **200** (теперь видима).

### TC-VIS-03 — MANAGER/ADMIN видят DRAFT-оферту всегда
1. `GET /api/offers/{offerId}` (MANAGER) на DRAFT-оферте → **200** (гейт видимости только для CLIENT).

**Teardown группы:** `withdraw` оферты.

---

## Фича 9. Сервис уведомлений: владение, бейдж, триггеры (R13, R14)

Покрывает R13.1, R13.4, R13.6, R13.7, R13.8, R13.9, R14.1, R14.2, R14.3, R10.9, R10.10, R10.19, R14.5. **Повторяемость:** генератор `run-id` + teardown (владелец удаляет свои уведомления).

### TC-NOTIF-01 — Триггер: клиентский запрос скидки уведомляет MANAGER (R14.1)
1. CLIENT выполняет `POST .../negotiation/requests` (как TC-NEG-01).
2. `GET /api/notifications` (MANAGER) → **200**; в списке есть новое уведомление offer-negotiation-типа с `deepLink` на Offer_Tab проекта.

### TC-NOTIF-02 — Триггер: MANAGER_PROPOSAL уведомляет CLIENT (R14.2)
1. MANAGER выполняет `propose` (как TC-NEG-03).
2. `GET /api/notifications` (CLIENT) → **200**; новое уведомление proposal-варианта с `deepLink` на Offer_Tab.

### TC-NOTIF-03 — Триггер: MANAGER_REJECT уведомляет CLIENT (R14.3)
1. MANAGER выполняет `reject` с объяснением (как TC-NEG-05).
2. `GET /api/notifications` (CLIENT) → **200**; новое уведомление rejected-варианта с `deepLink`.

### TC-NOTIF-04 — Список только собственных уведомлений, сортировка createdAt DESC (R13.4/R13.8)
1. `GET /api/notifications` (CLIENT) → **200**; все элементы имеют `recipient == CLIENT`; порядок — по `createdAt` убыванию.

### TC-NOTIF-05 — Бейдж = число непрочитанных (R10.10)
1. `GET /api/notifications/unread-count` (CLIENT) → **200**; значение равно числу собственных непрочитанных уведомлений.

### TC-NOTIF-06 — Toggle read/unread обновляет бейдж (R13.6)
1. `PATCH /api/notifications/{id}/toggle-read` (CLIENT, своё уведомление) → **200**; `read` переключён.
2. `unread-count` изменился на ±1 соответственно.

### TC-NOTIF-07 — Удаление уведомления обновляет список и бейдж (R13.7)
1. `DELETE /api/notifications/{id}` (CLIENT, своё) → **200**; уведомление пропало из списка; `unread-count` обновлён.

### TC-NOTIF-08 — Владение: чужое уведомление toggle/delete → 403 error.notification.forbidden (R13.9/R10.9/R10.19)
1. `PATCH /api/notifications/{idCLIENT}/toggle-read` (CLIENT_OTHER) → **403**, код `error.notification.forbidden`.
2. `DELETE /api/notifications/{idCLIENT}` (CLIENT_OTHER) → **403**.
3. Чужое уведомление не возвращается в `GET /api/notifications` (CLIENT_OTHER) (own-recipient фильтр).

### TC-NOTIF-09 — Best-effort эмиссия не блокирует переход (R14.5/R10.11)
1. (Документальный/интеграционный) Переход переговоров совершается и коммитится даже если эмиссия уведомления падает — доменная транзакция не откатывается. _(Проверяется property-тестом Property 20; в API-прогоне — переход всегда успешен независимо от доставки уведомления.)_

**Teardown группы:** владельцы удаляют созданные уведомления; `withdraw` оферты.

---

## Фича 10. Service-lock сметы при ACTIVE проекте (R20)

Покрывает R10.14, R20.2, R20.5. **Повторяемость:** генератор `run-id` + teardown.

### TC-LOCK-01 — Запись в смету/оферту при ACTIVE проекте → 409 error.estimate.locked
**Предусловия:** проект под `run-id` доведён до `ProjectStatus = ACTIVE` (через соответствующий путь; смета service-locked).
1. `POST .../choose-concrete` или любой путь записи цены/материала/объёма сметы → **409**, код `error.estimate.locked`.

---

# Часть B. Браузерные (UI) сценарии

Исполняются браузерным движком против `http://localhost:3000`. Результат — MD-репорт с таблицами (шаг → действие → ожидание → факт → статус). **Скриншоты не требуются.** **Повторяемость:** генератор `run-id` + teardown (данные готовятся по S0–S3, UI их переиспользует).

## Фича 11. Вкладка «Оферта / согласование» (переиспользование `pricing`) (R8)

Покрывает R8.1, R8.2, R8.3, R8.4, R8.8.

### TC-UI-OFFER-01 — Вкладка видна под именем «Оферта / согласование», гейт OFFERS
1. Логин MANAGER, открыть `/projects/{projectId}/pricing`.
2. Ожидание: вкладка подписана «Оферта / согласование» (не «Ценообразование»); отрисован реальный `OfferTab` (не Placeholder).

### TC-UI-OFFER-02 — Отрисовка оферты: статус, revision, пакет, итоги, скидки, лента
1. На Offer_Tab видны: текущий статус, номер revision, выбранный пакет, итоги (net/gross), список применённых скидок, лента переговоров (упорядоченные раунды с инициатором, kind, value, обоснованием, статусом).

### TC-UI-OFFER-03 — Действия исполнителя соответствуют статусу (R8.3)
1. Под MANAGER на `DRAFT`-оферте доступны: задать пакет, добавить/изменить/удалить скидку, «Отправить клиенту».
2. После send недоступные действия скрыты/задизейблены согласно машине статусов.

### TC-UI-OFFER-04 — Терминальная оферта read-only с полной историей (R8.8)
1. На `APPROVED`/`REJECTED`/`WITHDRAWN` оферте все действия скрыты; видна полная история раундов и терминальный статус.

### TC-UI-OFFER-05 — Клиентские действия соответствуют статусу (R8.4)
1. Под CLIENT на видимой `SENT`-оферте доступны: «Одобрить», «Отклонить», «Запросить скидку», выбор пакета.

## Фича 12. Лента переговоров и выбор чистовых материалов (UI) (R8.6/R8.7, R11, R18)

### TC-UI-NEG-01 — Клиент запрашивает скидку из ленты (без фигуры)
1. Под CLIENT нажать «Запросить скидку», выбрать scope/target, ввести обоснование (поля value НЕТ) → отправить.
2. Ожидание: статус оферты в UI → «CHANGES_REQUESTED» (локализовано); в ленте появился раунд DISCOUNT_REQUEST.

### TC-UI-NEG-02 — Менеджер видит клиентскую выборку материалов и пер-материальную ветку (R8.7)
1. Под MANAGER на Offer_Tab видна `ClientMaterialSelection`: чистовые Placeholder-слоты, read-only прочие материалы/работы/цены, аффорданс пер-материального запроса скидки.

### TC-UI-MAT-01 — Клиент выбирает чистовой продукт на Placeholder (стейджинг + save)
1. Под CLIENT на видимой оферте в `ClientMaterialSelection` выбрать продукт для Placeholder-слота — изменение попадает в стейджинг (не сохранено).
2. Undo → слот возвращается к прежнему состоянию; Redo → снова выбран (R18.1/R18.2).
3. Discard → восстановлена последняя сохранённая выборка (R18.3).
4. Save/Submit → вызывается серверный write-path; выбор сохранён; итоги/ревизия обновлены.

### TC-UI-MAT-02 — Прочее read-only для клиента (R8.6/R11.4)
1. Под CLIENT конструкционные материалы, работы, объёмы, цены и уже выбранные чистовые материалы отрисованы read-only (нет контролов записи).

### TC-UI-CONF-01 — Клиенту не показывается себестоимость/маржа/смётная unit-price (R8.10/R15)
1. Под CLIENT на Offer_Tab отсутствуют любые ячейки/колонки себестоимости, маржи, ставки рабочих, смётной unit-price — только offer-level цены.

## Фича 13. Колокол уведомлений в TopBar (R13)

Покрывает R13.3, R13.4, R13.5, R13.6, R13.7, R13.10, R13.11.

### TC-UI-BELL-01 — Колокол в правой части TopBar, перед кнопкой языка, с бейджем непрочитанных (R13.3)
1. Логин любой роли; в TopBar справа, непосредственно перед кнопкой языка, есть иконка-колокол с бейджем = число непрочитанных.

### TC-UI-BELL-02 — Попап со списком newest-first (R13.4)
1. Клик по колоколу → попап со списком уведомлений, отсортированных по `createdAt` убыванию.

### TC-UI-BELL-03 — Две операции в строке: toggle read/unread и delete (R13.5/R13.6/R13.7)
1. На строке уведомления доступны ровно две операции: переключить прочитано/непрочитано и удалить.
2. Toggle → бейдж обновился; Delete → строка исчезла, бейдж обновился.

### TC-UI-BELL-04 — Deep-link кликабелен, non-deep-link — нет (R13.10)
1. Уведомление с `deepLink` (напр. offer-negotiation) — сообщение кликабельно; клик уводит на Offer_Tab проекта.
2. Уведомление без `deepLink` — сообщение неинтерактивно (без навигации).

### TC-UI-BELL-05 — Локализация без сырых ключей (R13.11/R9)
1. В попапе и на Offer_Tab все подписи локализованы (RU/PL), сырых i18n-ключей не видно.

### TC-UI-BELL-06 — Сквозной триггер: запрос клиента → колокол менеджера загорается
1. Под CLIENT отправить запрос скидки (TC-UI-NEG-01).
2. Под MANAGER (другая сессия/обновление) бейдж колокола увеличился; в попапе — новое уведомление с deep-link на Offer_Tab.

---

# Регрессия

**Повторяемость:** генератор `run-id` + teardown. Раздел перепроверяет критичные сквозные пути и ранее закрытые риски.

### TC-REG-01 — Полный happy-path: prepare → send → request → propose → accept → approve
1. MANAGER: prepare (из PRICED) → DRAFT; send → SENT (проект OFFERED, видимость ON_APPROVAL).
2. CLIENT: request скидки (без фигуры) → CHANGES_REQUESTED.
3. MANAGER: propose (в пределах порога) → COUNTERED; уведомление клиенту.
4. CLIENT: accept → материализована скидка, revision++, уведомления.
5. CLIENT: approve → APPROVED, проект APPROVED, зафиксирован approvedRevision.
6. Проверка: `AgreedOfferView` стабилен; дальнейшие записи → 409 (неизменяемость).

### TC-REG-02 — Конфиденциальность под нагрузкой переговоров
1. После серии раундов и смены пакета `GET /api/offers/{offerId}` (CLIENT) по-прежнему не содержит ни одного себестоимостного/маржинального/смётного unit-price поля (структурная проверка).

### TC-REG-03 — Изоляция владения уведомлений в многопользовательском проекте
1. CLIENT, CLIENT2, MANAGER получают только свои уведомления; попытки toggle/delete чужого → 403; бейджи независимы.

### TC-REG-04 — Live-referenced pricing: изменение сметы отражается в оферте
1. Пока оферта нетерминальна и смета DRAFT, изменение клиентской финальной цены сметы (напр. через выбор чистового материала) отражается в итогах оферты без «замороженной» копии (R19) — проверить, что `totalNet` оферты пересчитался от живой ссылки.

### TC-REG-05 — Override-and-cancel детерминизм при повторном прогоне
1. Повторить TC-NEG-10 с иным порядком создания раундов → итоговые применённые скидки идентичны (независимость от порядка вставки, R2.5/R4.9/R10.16).

> Обоснование состава регрессии: охвачены самый вероятный к поломке сквозной путь (REG-01), security-критичная конфиденциальность (REG-02), многопользовательское владение уведомлений (REG-03), ключевая модель live-referenced pricing (REG-04) и детерминизм вытеснения скидок (REG-05).

---

# Шаблон MD-репорта прогона

Заполняется по итогам каждого прогона (отдельно для Части A — API и Части B — UI, либо единой таблицей с пометкой типа).

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-PREP-01 | 1 | POST /api/offers {projectId} | 200/201 + status=DRAFT, revision=1 |  | ⬜ |
| 2 | TC-PREP-01 | 2 | (тело ответа) | selectedPackageCode=COMFORT |  | ⬜ |
| 3 | TC-NEG-04 | 1 | POST .../reject {explanation:""} | 400 error.offer.reject.explanation.required |  | ⬜ |
| 4 | TC-VIS-01 | 1 | GET /api/offers/{id} (CLIENT, DRAFT) | 404 error.entity.not.found |  | ⬜ |
| 5 | TC-UI-BELL-01 | 1 | Открыть любую страницу под ролью | колокол в TopBar + бейдж непрочитанных |  | ⬜ |

Статусы: ✅ пройдено / ❌ провалено / ⏭️ пропущено / ⬜ не выполнено.

**Итог:** X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`. Стратегия повторяемости: `генератор run-id + teardown`. Окружение: `docker compose up` (backend :8080, frontend :3000), профиль `docker`.
