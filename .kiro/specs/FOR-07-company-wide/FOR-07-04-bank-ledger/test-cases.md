# Тест-кейсы: FOR-07-04 — Денежный реестр компании (банк + касса)

> Версия: пересинхронизировано с финальной реализацией (задача 10). Источники сверки:
> контроллеры `foremen-backend/src/main/java/com/foremen/controller/bank/**`,
> `ProcurementDocumentController`, `WorkerPayoutController`, интеграционные тесты
> `BankLedger*IntegrationTest`, `application.yml` / `application-docker.yml` / `docker-compose.yml`,
> фронтенд `features/bank`, `features/bank-transactions`, `config/navigation.ts`, `app/router.tsx`,
> `config/route-permissions.ts`, `locales/{ru,pl}.json`.

## Назначение и характер спеки

Спека **смешанная (backend + UI)**. Набор состоит из трёх частей.

**Часть A — API-тесты** против поднятого в Docker приложения (`http://localhost:8080`, STUB-коннектор):
- подключения, счета, проверка владельца счёта, кассы;
- синхронизация, идемпотентность, замена pending-операций, контроль сальдо;
- автоопределение по правилам, предложения, обучение правил контрагентов;
- ручная привязка, переопределение, разделение, игнорирование, массовые операции;
- записи по кассе и их эффекты (выплаты, закупки);
- эффекты привязок в соседних доменах (FOR-06-07 оплаты клиента, FOR-07-06 выплаты, FOR-06-03/04 закупки);
- валидации, callback'и Salt Edge (негативные), уведомления, ABAC.

**Часть B — браузерные сценарии** (headless-браузер против `http://localhost:3000`):
- меню **Банк / Bank** (раздел «Система / System»: подключения, счета + кассы + владелец, история
  синхронизаций, правила контрагентов, поддерживаемые банки);
- меню **Банковские операции / Operacje bankowe** (раздел «Компания / Firma»: реестр, фильтры, итоги,
  карточка операции, записи по кассе);
- галочка «Оплачено наличными» при загрузке счёта (закупки проекта) и ссылки на реестр в зарплатах.

**Часть C — ручной сценарий Salt Edge на окружении** (базовый URL из env, режим CLIENT / PARTNER).

Результат прогона — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание → факт → статус).
**Скриншоты не требуются.**

Маркеры:
- **[ai]** — требует `BANK_AI_ENABLED=true` (+ ключи Gen AI); иначе кейс пропускается (⏭);
- **[ocr]** — требует хранилище изображений и OCR (`foremen.image-storage.*`, `foremen.ocr.*`) для
  `POST /api/procurement-documents`; иначе пропускается;
- **[env]** — только на окружении с настоящим Salt Edge (часть C);
- **[known]** — известное расхождение с требованиями; ожидаемый результат «❌ known», не «✅».

## Запуск окружения

1. В корне: `docker compose up`. Поднимаются `postgres` (:5432), `liquibase` (включая changesets
   201–206), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Профиль `docker` **включает STUB по умолчанию**: `FOREMEN_BANK_STUB_ENABLED` → `true`
   (`application-docker.yml` и `docker-compose.yml`), `FOREMEN_PUBLIC_BASE_URL` →
   `http://localhost:3000`. Salt Edge выключен (`SALTEDGE_ENABLED=false`), ИИ выключен
   (`BANK_AI_ENABLED=false`).
3. **На dev / prod-окружениях STUB обязательно выключать**: `FOREMEN_BANK_STUB_ENABLED=false`
   (иначе на окружении появится тестовый провайдер и эндпоинт `/api/bank/stub/**`).
4. Первый ADMIN: `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
   Логин: `POST /api/auth/login {email, password}` → `accessToken`.
5. Проверка готовности: `GET /api/bank/providers` под ADMIN → `200`, `[*].provider = ["STUB"]`.

### Конфигурация, существенная для кейсов

| Параметр | Значение по умолчанию | Где влияет |
|----------|----------------------|------------|
| `foremen.bank-ledger.match.auto-threshold` | `0.90` | автопривязка (`AUTO_LINKED`) только при score ≥ 0.90 … |
| `foremen.bank-ledger.match.margin` | `0.20` | … и отрыве от второго кандидата ≥ 0.20 |
| `foremen.bank-ledger.match.suggest-threshold` | `0.50` | предложения (`SUGGESTED`) при score ≥ 0.50 |
| `foremen.bank-ledger.match.max-suggestions` | `3` | не более 3 предложений на операцию |
| `foremen.bank-ledger.holder.name-similarity` | `0.80` | порог совпадения имени владельца счёта с компанией |
| `foremen.bank-feed.consent.warn-days` | `14` | `CONSENT_EXPIRING` за 14 дней до окончания согласия |

Таблица баллов правил (из `RuleMatcher`): свой счёт (IBAN) — `1.00`; ссылка `FM-{projectId}` — `0.95`;
выплата: сумма `0.50` + дата ±7 дн. `0.20` + имя `0.20` + правило контрагента `0.30`; документ закупки:
сумма `0.50` + номер счёта `0.40` + поставщик `0.20` + дата `0.10`; правило контрагента на категорию —
`0.90`. Сумма ограничена `1.00`.

Прочие факты реализации, на которые опираются ожидания:
- ответ `POST /api/bank/connections` — `201 {connection: {...}, authorizationUrl}`; статус лежит в
  **`connection.status`** (а не на верхнем уровне);
- у STUB `authorizationUrl` = `{FOREMEN_PUBLIC_BASE_URL}/bank/connections/return?connectionId={id}&stub=1`;
- аудит-колонки банковских таблиц — `created_date` / `updated_date` (BaseEntity); в DTO — `createdDate`;
- курсор Salt Edge: в `bank_accounts.transactions_cursor` хранится resume-курсор (макс. id проведённой
  операции); `next:<id>` — курсор продолжения внутри одной выборки (не сохраняется);
- `GET /api/bank/transactions` фильтрует по `linkStatus` (повторяемый параметр, включая группу
  `TO_REVIEW`), а deep-link уведомления — `/company/bank-transactions?status=TO_REVIEW` (UI переводит
  `status` в фильтр статуса);
- блокировка привязки (`locked`) возникает, когда по привязке `CLIENT_PAYMENT` уже создана и
  разнесена `PaymentTransaction`.

### Известные ограничения (документируются как [known])

- **KL-01 (R15.7 выполнено частично).** Во фронтенд не передаётся флаг «ИИ включён». Кнопка
  «Перезапустить автоопределение» в карточке операции **всегда** отправляет
  `POST /api/bank/transactions/detect {ids:[id], includeAi: true}` и видна при выключенном ИИ.
  Backend при выключенном ИИ ИИ не вызывает (`DisabledBankTransactionClassifier`), маркеры ИИ не
  появляются (нет данных с `source = AI`) — эта часть требования выполняется; не выполняется
  «скрыть действие Re-run AI». Кейсы TC-UI-KL-01 и TC-API-KL-01 фиксируют это как ожидаемое
  расхождение.

### Эндпоинты (по факту реализации)

| Метод + путь | ABAC | Ответ / назначение |
|--------------|------|--------------------|
| `GET /api/bank/providers` | BANK_CONNECTIONS READ | доступные провайдеры `[{provider, mode, available, credentialsRequired, credentialFields}]` |
| `GET /api/bank/providers/{provider}/banks?country&q&businessOnly&includeTest&refresh` | BANK_CONNECTIONS READ | `{provider, mode, country, banks[], totalCount, fetchedAt, emptyReason}` |
| `GET /api/bank/connections` / `GET …/{id}` | BANK_CONNECTIONS READ | список / детали (детали — с `accounts[]`) |
| `POST /api/bank/connections {provider, providerBankCode, displayName}` | BANK_CONNECTIONS CREATE | `201 {connection, authorizationUrl}` |
| `POST …/{id}/authorize` | UPDATE | продолжить авторизацию `PENDING_AUTH` → `{connection, authorizationUrl}` |
| `POST …/{id}/complete {params}` | UPDATE | `200 {connection, completed, syncRunId}` (запускает run `CONNECT`) |
| `POST …/{id}/reconnect` | UPDATE | `{connection, authorizationUrl}` |
| `POST …/{id}/refresh` | UPDATE | `{connection, authorizationUrl?, nextRefreshPossibleAt, syncRunId?}` / `409 error.bank.refresh.notYetPossible` |
| `POST …/{id}/sync` | UPDATE | `202 {runId}` |
| `PATCH …/{id} {displayName}` | UPDATE | переименовать |
| `PUT …/{id}/credentials {values}` | UPDATE | `{credentialsSet, credentialsUpdatedAt}` (значения не возвращаются) |
| `POST …/{id}/disable` | UPDATE | `DISABLED` |
| `DELETE …/{id}` | DELETE | `204`; `409 error.bank.connection.notDisabled` / `error.bank.connection.hasLinks` |
| `GET /api/bank/accounts?connectionId&type` / `GET …/{id}` | BANK_CONNECTIONS READ | банковские счета + кассы |
| `PATCH /api/bank/accounts/{id} {includedInLedger, holderOverrideReason?}` | UPDATE | включение / исключение; для `MISMATCH` нужна причина |
| `GET /api/bank/accounts/{id}/balance-gaps` | READ | пропуски сальдо, новые сверху |
| `POST /api/bank/balance-gaps/{id}/acknowledge {note}` | UPDATE | подтвердить пропуск |
| `GET/POST /api/bank/cash-accounts`, `GET/PUT …/{id}`, `POST …/{id}/archive` | READ / CREATE (201) / UPDATE | кассы |
| `GET /api/bank/sync-runs?connectionId&status&from&to&page&size` / `GET …/{id}` | READ | история (Page) / детали с `accounts[]` |
| `GET/POST /api/bank/counterparty-rules`, `GET/PUT/DELETE …/{id}` | BANK_CONNECTIONS CRUD | правила (Page; POST → 201; DELETE → 204) |
| `POST /api/bank/stub/connections/{id}/data` | BANK_CONNECTIONS UPDATE (только при STUB) | `204`; засев данных STUB |
| `GET /api/bank/transactions?from&to&channel&direction&connectionId&accountId&linkStatus&kind&projectId&detection&amountMin&amountMax&q&includePending&transactionId&page&size&sort` | BANK_LEDGER READ | `{content[], page, totals}` |
| `GET /api/bank/transactions/{id}` | BANK_LEDGER READ | `{line, …, suggestions[], providerPayload (только ADMIN)}` |
| `GET /api/bank/transactions/{id}/link-targets?kind&q&projectId&paymentStatus` | READ | цели привязки |
| `GET /api/bank/transactions/link-candidates?procurementDocumentId` | READ | операции-кандидаты для документа |
| `POST /api/bank/transactions/cash` | BANK_LEDGER CREATE | `201 {transactionId, accountId, amount, linkStatus, linkId}` |
| `PUT/DELETE /api/bank/transactions/{id}` | UPDATE / DELETE | правка / удаление ручной записи по кассе; DELETE → `200 {transactionId, payoutStaysPaid}` |
| `POST /api/bank/transactions/{id}/links` | BANK_LEDGER CREATE | `201 {transactionId, linkId, linkStatus, payoutStaysPaid}` |
| `PUT /api/bank/links/{linkId}` / `DELETE /api/bank/links/{linkId}` | UPDATE / DELETE | замена / удаление привязки |
| `POST /api/bank/suggestions/{id}/confirm {remember}` / `…/reject` | UPDATE | предложения |
| `POST /api/bank/transactions/{id}/confirm-auto {remember}` / `ignore {reason}` / `unignore` | UPDATE | статусы |
| `POST /api/bank/transactions/{id}/payout {workerId, amount?, note?, remember?}` | BANK_LEDGER CREATE + PAYROLL CREATE | `201`; выплата из операции |
| `POST /api/bank/transactions/detect {ids? , filter?, includeAi?}` | UPDATE | `{processed, autoLinked, suggested, aiQueued, toReview, skipped}` |
| `POST /api/bank/transactions/bulk/confirm {ids, remember}` | UPDATE | `{confirmed, skipped, failed[]}` |
| `POST /api/procurement-documents` (multipart: `file, projectId`; API также принимает `paymentChoice?, cashAccountId?, cashAmount?, cashDate?`, UI их не отправляет) | PURCHASES CREATE | `200`, документ с `paymentStatus`, `ledgerEntries` |
| `POST /api/procurement-documents/{id}/paid-in-cash {cashAccountId?, amount?, date?}` | PURCHASES UPDATE | `200`; `400 error.procurement.cash.amountRequired` (нет ни суммы, ни итога OCR); `409 error.procurement.payment.notUnmatched`. Галочка UI шлёт `{}`, диалог строки — только `amount` + `date` |
| `GET /api/worker-payouts?workerId…` / `POST /api/worker-payouts` (→ `200`) / `POST …/{id}/pay` | PAYROLL READ / CREATE / UPDATE | `WorkerPayoutDto` с `ledgerEntries[]` |
| `POST /api/bank-feed/saltedge/callbacks/{success\|fail\|notify\|destroy}` | permitAll + подпись | `200 {}`; `401` / `404` / `413` |

### Маршруты фронтенда

| Маршрут | Доступ | Примечание |
|---------|--------|------------|
| `/bank` | BANK_CONNECTIONS READ | редирект на `/bank/connections` (query-строка сохраняется) |
| `/bank/:tab` | BANK_CONNECTIONS READ | `connections`, `accounts`, `sync-runs`, `counterparty-rules`, `supported-banks`; неизвестная вкладка → редирект |
| `/bank/connections/return` | BANK_CONNECTIONS **UPDATE** | страница возврата от провайдера |
| `/company/bank-transactions` | BANK_LEDGER READ | реестр; `?transactionId=` открывает карточку; `?status=TO_REVIEW` — фильтр «На проверку» |

Навигация: «Банк» — в разделе «Система» (ADMIN, FINANCIER); «Банковские операции» — в разделе
«Компания» (ADMIN, FINANCIER, MANAGER).

## Общая стратегия повторяемости

Используется **генератор `run-id` + teardown**. На каждый прогон генерируется `run-id` (8 символов
uuid/timestamp, например `A1B2C3D4`), и он входит во все создаваемые сущности:
- подключения `displayName = "Stub {run-id}"`, `"Stub-LC {run-id}"` и т. п.;
- внешние id STUB `acc-{run-id}-N`, `t-{run-id}-N`, `p-{run-id}-N`, `m-{run-id}-N`;
- назначения платежей содержат `{run-id}`;
- IBAN генерируются на прогон (валидный PL IBAN: 24 случайные цифры BBAN + контрольные цифры
  `98 − (BBAN + "252100") mod 97`): `IBAN_A`, `IBAN_B` (свои счета), `IBAN_W` (работник), `IBAN_X`;
- суммы — уникальные на прогон (например `1000.00 … 9999.99`), где это важно для сопоставления;
- пользователи `financier+{run-id}@example.com`, `manager+{run-id}@example.com`,
  `foreman+{run-id}@example.com`, работник `worker+{run-id}@example.com` (имя `Jan Kowalski {run-id}`);
- проект `Bank07 {run-id}`, касса `Kasa {run-id}`.

Каждое подключение создаётся **заново на прогон**, поэтому его счета и операции изолированы;
запросы к реестру фильтруются по `connectionId` (или `accountId` для касс).

**Teardown** в конце каждой группы, в таком порядке:
1. удалить незаблокированные привязки (`DELETE /api/bank/links/{id}`) и ручные записи по кассе
   (`DELETE /api/bank/transactions/{id}`);
2. удалить правила контрагентов с ключами прогона (`DELETE /api/bank/counterparty-rules/{id}`);
3. `POST …/connections/{id}/disable`, затем `DELETE …/connections/{id}` (если у операций нет привязок;
   иначе подключение остаётся `DISABLED`, изоляцию обеспечивает `run-id`);
4. кассы прогона — `POST /api/bank/cash-accounts/{id}/archive`;
5. проект перевести в `CANCELLED`; пользователей деактивировать.

`PAID`-выплаты, `SYSTEM`-записи по кассе, заблокированные привязки и разнесённые платежи клиента не
удаляются: изоляция обеспечивается новым работником, проектом и кассой.

### Общий Setup (S0)

| # | Действие | Ожидание |
|---|----------|----------|
| 1 | Логин ADMIN → `ADMIN_TOKEN` | `200` |
| 2 | `GET /api/company-profile`; если профиль пуст — `PUT /api/company-profile {fullName: "Foremen Test Sp. z o.o.", nip: "5260250995", registeredAddress: "…"}` (существующий профиль не перезаписывать). Запомнить `COMPANY_NAME = fullName` | `200` |
| 3 | `POST /api/users` — FINANCIER, MANAGER, FOREMAN (`…+{run-id}`), пароли, логин → `FIN_TOKEN`, `MGR_TOKEN`, `FRM_TOKEN` | `201`, логин `200` |
| 4 | `POST /api/users` — WORKER `worker+{run-id}`, имя `Jan Kowalski {run-id}` → `WORKER_ID` | `201` |
| 5 | Создать проект `Bank07 {run-id}` (CLIENT `Anna Nowak {run-id}`), довести до `ACTIVE`; создать и опубликовать график оплат (`POST /api/payments/schedules`, `…/{scheduleId}/publish`) с 2 траншами по `5000.00` → `PROJECT_ID`, `SCHEDULE_ID` | график `PUBLISHED` |
| 6 | `POST /api/bank/connections {provider: "STUB", providerBankCode: "stub_business_pl", displayName: "Stub {run-id}"}` → `CONN_ID = connection.id` | `201`; **`connection.status = PENDING_AUTH`**; `authorizationUrl` начинается с `http://localhost:3000/bank/connections/return?connectionId={CONN_ID}` и заканчивается `stub=1` |
| 7 | `POST /api/bank/stub/connections/{CONN_ID}/data` — `{mode: "REPLACE", accounts: [{externalId: "acc-{run-id}-1", name: "Główne {run-id}", nature: "account", iban: IBAN_A, currency: "PLN", balance: 10000.00, balanceAt: <now−3d>, holderName: COMPANY_NAME}, {externalId: "acc-{run-id}-2", name: "Oszczędności {run-id}", nature: "account", iban: IBAN_B, currency: "PLN", balance: 0.00, balanceAt: <now−3d>, holderName: COMPANY_NAME}], transactions: []}` | `204` |
| 8 | `POST /api/bank/connections/{CONN_ID}/complete {params: {stub: "1"}}` | `200`; `completed = true`; `connection.status = ACTIVE`; `syncRunId` не `null` |
| 9 | Опрашивать `GET /api/bank/sync-runs/{syncRunId}` (раз в 0.5 с, до 30 с), пока статус не уйдёт из `QUEUED`/`RUNNING` | `SUCCEEDED`, `trigger = CONNECT`, `accountsSeen = 2` |
| 10 | `GET /api/bank/connections/{CONN_ID}` → `ACC_1`, `ACC_2` (`accounts[*].id`) | оба `holderMatch = MATCH`, `includedInLedger = true` |

Далее «`sync` + ожидание» = `POST /api/bank/connections/{id}/sync` → `202 {runId}` и опрос
`GET /api/bank/sync-runs/{runId}` как в шаге 9. «`APPEND`» = `POST /api/bank/stub/connections/{id}/data`
с `mode: "APPEND"` (upsert по `externalId`, незаданные поля не меняются).

Формат транзакции STUB: `{accountExternalId, externalId, status: "POSTED"|"PENDING", amount (со знаком),
currency, operationDate, bookingDate (только для POSTED), title, counterpartyName, counterpartyIban,
providerCategory?, providerDuplicate?}`.

---

## Часть A. API-тесты

### A1. Доступ (ABAC)

**Повторяемость:** только чтение / заведомо несуществующие id (`999999999`); пользователи из S0.

#### TC-API-ABAC-01 — Матрица ролей

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `GET /api/bank/connections` и `GET /api/bank/transactions` без токена | `401` |
| 2 | `GET /api/bank/connections`, `/providers`, `/accounts`, `/cash-accounts`, `/sync-runs`, `/counterparty-rules` под ADMIN / FINANCIER | `200` |
| 3 | Те же запросы под MANAGER / FOREMAN | `403` |
| 4 | `GET /api/bank/transactions` под ADMIN / FINANCIER / MANAGER | `200` |
| 5 | `GET /api/bank/transactions` под FOREMAN | `403` |
| 6 | `POST /api/bank/connections {provider: "STUB"}` под MANAGER | `403` |
| 7 | `POST /api/bank/transactions/999999999/payout {workerId}` под MANAGER / FINANCIER | `404` (доступ есть: BANK_LEDGER CREATE + PAYROLL CREATE) |
| 8 | То же под FOREMAN | `403` |
| 9 | `GET /api/bank/transactions/{id}` (любая операция S0; выполнять после A4) под FINANCIER | `providerPayload = null`, `counterpartyRaw = null` |
| 10 | То же под ADMIN | `providerPayload` заполнен |

_Покрывает:_ R21.1, R21.2, R21.4, R21.6.

### A2. Жизненный цикл подключения

**Повторяемость:** отдельные подключения `Stub-LC {run-id}`, `Stub-RA {run-id}`; в конце `disable` + `DELETE`.

#### TC-API-CON-01 — Создание, переименование, отключение, удаление

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `POST /api/bank/connections {provider: "STUB", providerBankCode: "stub_personal_pl", displayName: "Stub-LC {run-id}"}` | `201`, `connection.status = PENDING_AUTH`, `authorizationUrl` с `stub=1` |
| 2 | `POST …/{id}/sync` до авторизации | `409 error.bank.connection.notAuthorized` |
| 3 | `POST …/{id}/complete {params: {stub: "1"}}` | `200`, `connection.status = ACTIVE`, `completed = true` |
| 4 | `PATCH …/{id} {displayName: "Stub-LC2 {run-id}"}` | `200`, `displayName` обновлён |
| 5 | `GET …/{id}` | нет полей с секретами; `credentialsRequired = false`, `credentialsSet = false`; есть `createdDate` |
| 6 | `DELETE …/{id}` (подключение активно) | `409 error.bank.connection.notDisabled` |
| 7 | `POST …/{id}/disable` | `200`, `status = DISABLED` |
| 8 | `POST …/{id}/sync` | `409 error.bank.connection.disabled` |
| 9 | `DELETE …/{id}` | `204` |

_Покрывает:_ R1.3, R1.4, R1.7, R1.8, R2.3.

#### TC-API-CON-02 — Удаление подключения с привязками запрещено

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | В конце прогона (после A7) у подключения S0 есть привязки; `POST …/{CONN_ID}/disable` | `DISABLED` |
| 2 | `DELETE /api/bank/connections/{CONN_ID}` | `409 error.bank.connection.hasLinks` |

_Покрывает:_ R1.8. **Teardown:** подключение остаётся `DISABLED`.

#### TC-API-CON-03 — Истекающее согласие и переавторизация (STUB)

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | Создать и завершить `Stub-RA {run-id}`, засеять 1 счёт (`REPLACE`) | `ACTIVE` |
| 2 | `APPEND` с `state: {status: "ACTIVE", consentExpiresAt: <now + 5 дней>}`; `sync` + ожидание | `GET …/{id}` → `status = CONSENT_EXPIRING`, `consentExpiresAt` ≈ now+5д |
| 3 | `APPEND` с `state: {status: "REAUTH"}`; `sync` + ожидание | run `FAILED`; подключение `REAUTH_REQUIRED` |
| 4 | `POST …/{id}/reconnect` | `200 {connection, authorizationUrl}` (URL с `stub=1`) |
| 5 | `APPEND` с `state: {status: "ACTIVE", consentExpiresAt: <now + 90 дней>}`; `POST …/{id}/complete {params: {stub: "1"}}` | `connection.status = ACTIVE`; число счетов прежнее; `externalConnectionId` прежний |

_Покрывает:_ R1.2, R1.5, R1.9, R12.4.

#### TC-API-CON-04 — Поддерживаемые банки (STUB)

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `GET /api/bank/providers/STUB/banks?country=PL` | `200`; `provider = STUB`; `banks[*].code = [stub_business_pl, stub_personal_pl]`; `fetchedAt` заполнен; `emptyReason = null` |
| 2 | `…?businessOnly=true` | только `stub_business_pl` |
| 3 | `…?q=no-such-bank-{run-id}` | `banks = []`, `totalCount = 2`, `emptyReason = null` |
| 4 | `…?includeTest=false` | `banks = []`, `emptyReason = null` (STUB-банки — тестовые) |
| 5 | `…?refresh=true` | `200`, `fetchedAt` заполнен (кэш обойдён) |
| 6 | `GET /api/bank/providers/SALT_EDGE/banks` в Docker (Salt Edge выключен) | `409 error.bank.provider.notAvailable` |

_Покрывает:_ R9.2, R19.9.

### A3. Проверка владельца счёта

**Повторяемость:** отдельное подключение `Stub-HM {run-id}`; teardown — `disable` + `DELETE`.

#### TC-API-HLD-01 — MATCH / MISMATCH / UNKNOWN и переопределение

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | Создать `Stub-HM {run-id}` (без `complete`); `REPLACE`: счета `A-MATCH` (`holderName = COMPANY_NAME`), `A-MISMATCH` (`holderName = "Jan Prywatny {run-id}"`), `A-UNKNOWN` (без `holderName`); по одной POSTED-операции на `A-MATCH` и `A-MISMATCH` | `204` |
| 2 | `complete {params: {stub: "1"}}` + ожидание run | `SUCCEEDED`, `accountsSeen = 3`, `transactionsNew = 1` (MISMATCH не загружается) |
| 3 | `GET /api/bank/connections/{id}` | `A-MATCH`: `holderMatch = MATCH`, `includedInLedger = true`, `holderWarning = false`; `A-MISMATCH`: `MISMATCH`, `includedInLedger = false`, `holderWarning = true`; `A-UNKNOWN`: `UNKNOWN`, `includedInLedger = true` |
| 4 | `PATCH /api/bank/accounts/{mismatchId} {includedInLedger: true}` | `400 error.bank.holder.reasonRequired` |
| 5 | `PATCH /api/bank/accounts/{mismatchId} {}` | `400 error.bank.account.includedInLedgerRequired` |
| 6 | `PATCH … {includedInLedger: true, holderOverrideReason: "Konto wspólnika {run-id}"}` | `200`; `includedInLedger = true`, `holderMatch = MISMATCH`, `holderWarning = false`, `holderOverrideBy` заполнен |
| 7 | `sync` + ожидание | `SUCCEEDED`; операция счёта `A-MISMATCH` появилась в реестре |

_Покрывает:_ R3.2, R3.7, R3.8.

### A4. Загрузка операций, идемпотентность, pending

**Повторяемость:** подключение S0 (`ACC_1`), все id содержат `run-id`.

#### TC-API-ING-01 — Загрузка и повторная синхронизация без дублей

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `APPEND` 3 POSTED на `acc-{run-id}-1` с `bookingDate = today`: `t-{run-id}-1` `-1500.00` «Przelew {run-id}» (контрагент `Odbiorca {run-id}`, `IBAN_W`); `t-{run-id}-2` `+5000.00` «Zapłata FM-{PROJECT_ID} {run-id}» (контрагент `Anna Nowak {run-id}`); `t-{run-id}-3` `-200.00` «Paliwo {run-id}» (контрагент `Orlen {run-id}`). Баланс `ACC_1` = `13300.00`, `balanceAt = now` | `204` |
| 2 | `sync` + ожидание | `SUCCEEDED`, `trigger = MANUAL`, `transactionsNew = 3` |
| 3 | `GET /api/bank/transactions?connectionId={CONN_ID}` | 3 строки в `content[]`; `t-…-1` и `t-…-3` `direction = EXPENSE`, `t-…-2` `INCOME`; `channel = BANK`, `origin = BANK_FEED`; `counterpartyIban` у `t-…-1` нормализован (`PL…` без пробелов); `totals.count = 3` |
| 4 | Повторить `sync` без изменений данных | `transactionsNew = 0`; в реестре по-прежнему 3 строки; привязки и статусы не изменились |

_Покрывает:_ R6.1, R6.2, R6.5 (Property 1).

#### TC-API-ING-02 — Pending: замена и переход в POSTED

**Повторяемость:** отдельное подключение `Stub-PND {run-id}` с одним счётом `ACC` (замена pending
требует `REPLACE`-засева, поэтому S0 не используется); teardown — `disable` + `DELETE`.

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `REPLACE`: счёт `ACC`; POSTED `p-{run-id}-0` `-5.00`; PENDING `p-{run-id}-1` `-99.00` и `p-{run-id}-2` `-10.00` (без `bookingDate`); `complete` + ожидание | `transactionsNew = 1`; обе pending в реестре (`includePending` по умолчанию `true`) со `status = PENDING`, `linkStatus = UNLINKED`, без предложений |
| 2 | `GET /api/bank/transactions?connectionId={id}&includePending=false` | pending-строк нет |
| 3 | `POST /api/bank/transactions/{p-…-1 id}/links {kind: "OTHER_EXPENSE", amount: 99.00, unplannedCategoryId}` | `409 error.bank.link.notPosted` |
| 4 | `REPLACE`: тот же счёт; `p-…-0` (POSTED, без изменений); `p-…-1` как POSTED (`bookingDate = today−1`); `p-…-2` не присылать; новый PENDING `p-{run-id}-3` `-12.00`; `sync` | `p-…-2` исчезла; `p-…-1` стала `POSTED` **с тем же id** (продвижение на месте); `p-…-3` — PENDING; `pendingReplaced ≥ 1`; всего 3 строки |

_Покрывает:_ R6.3 (Property 2).

#### TC-API-ING-03 — Сбой одного счёта даёт PARTIAL

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `APPEND {failNextFetch: "TRANSIENT"}`; `sync` + ожидание | run `PARTIAL` (упал один из двух счетов); в `accounts[]` run'а у одного счёта ошибка; подключение `ERROR`, `lastErrorCode` заполнен |
| 2 | Повторить `sync` | `SUCCEEDED`; подключение снова `ACTIVE` |

_Покрывает:_ R5.5, R12.2, R12.4.

### A5. Контроль сальдо

**Повторяемость:** отдельное подключение `Stub-BAL {run-id}` с одним счётом.

#### TC-API-BAL-01 — Обнаружение и подтверждение пропуска

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `REPLACE`: счёт `ACC` баланс `2000.00`, `balanceAt = now−3d`; POSTED `-100.00` и `+50.00` с датой `today−5`; `complete` + ожидание | `SUCCEEDED`, `transactionsNew = 2` |
| 2 | `sync` без изменений | `GET /api/bank/accounts/{id}/balance-gaps` → `[]` |
| 3 | `REPLACE`: тот же счёт, баланс `2747.00` (= `2000 − 30 + 777`), `balanceAt = now`; POSTED `-30.00` с датой `today−1`; `sync` | `SUCCEEDED`; `balance-gaps` → 1 запись, `difference = 777.00`, `hasBalanceGap = true`; у счёта `hasBalanceGap = true` |
| 4 | `POST /api/bank/balance-gaps/{gapId}/acknowledge {note: "sprawdzone {run-id}"}` | `200`, `hasBalanceGap = false`, `acknowledgedBy` заполнен, `note` сохранён |

_Покрывает:_ R7.1–R7.4 (Property 8).

### A6. Автоопределение по правилам

**Повторяемость:** операции, работник и проект с `run-id` из S0.

#### TC-API-DET-01 — Перевод между своими счетами

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `APPEND`: на `acc-…-1` POSTED `t-{run-id}-int` `-300.00` с `counterpartyIban = IBAN_B`, `counterpartyName = COMPANY_NAME`; `sync` | `GET /api/bank/transactions/{id}` → `line.linkStatus = AUTO_LINKED`; `line.links[0].kind = INTERNAL_TRANSFER`, `counterAccountId = ACC_2`, `source = RULE`, `confidence = 1.00`; `line.detection.source = RULE` |
| 2 | `POST /api/bank/transactions/{id}/confirm-auto {remember: false}` | `200`, `linkStatus = LINKED` |

_Покрывает:_ R14.2, R17.1.

#### TC-API-DET-02 — Оплата клиента по ссылке FM-{projectId} + блокировка

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `GET /api/bank/transactions/{t-…-2 id}` (после ING-01) | `line.linkStatus = AUTO_LINKED`; `links[0].kind = CLIENT_PAYMENT`, `paymentScheduleId = SCHEDULE_ID`, `paymentTransactionId` заполнен, `locked = true`; `line.locked = true` |
| 2 | `GET /api/payments/schedules/{SCHEDULE_ID}/transactions` | одна транзакция `channel = BANK_TRANSFER`, `status = CONFIRMED`, `amountGross = 5000.00`, `externalRef = "bank-txn:{txnId}:{linkId}"` |
| 3 | `GET /api/payments/schedules/{SCHEDULE_ID}` | первый транш `PAID` |
| 4 | `DELETE /api/bank/links/{linkId}` | `409 error.bank.link.locked` |
| 5 | `PUT /api/bank/links/{linkId} {kind: "OTHER_INCOME", amount: 5000.00, …}` | `409 error.bank.link.locked` |

_Покрывает:_ R14.4, R16.5, R16.6 (Property 9).

#### TC-API-DET-03 — Предложение выплаты → подтверждение → PAID + обучение правила

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `POST /api/worker-payouts {workerId: WORKER_ID, payoutDate: today, periodFrom: today−7, periodTo: today−1, paymentChannel: "BANK_TRANSFER", amount: 1500.00}` | `200`, `status = PLANNED` |
| 2 | `POST /api/bank/transactions/detect {ids: [t-…-1 id]}` | `200`, `processed = 1`, `suggested = 1` |
| 3 | `GET /api/bank/transactions/{t-…-1 id}` | `line.linkStatus = SUGGESTED` (балл `0.70`: сумма + дата; имя контрагента не совпадает); `suggestions[0].kind = WORKER_PAYOUT`, `workerPayoutId` = выплата шага 1, `source = RULE`; выплата ещё `PLANNED` |
| 4 | `POST /api/bank/suggestions/{sugId}/confirm {remember: true}` | `200`, `linkStatus = LINKED` |
| 5 | `GET /api/worker-payouts?workerId={WORKER_ID}` | выплата `PAID`, `paymentChannel = BANK_TRANSFER`, `ledgerEntries[0].transactionId = t-…-1 id`, `ledgerEntries[0].channel = BANK` |
| 6 | `GET /api/bank/counterparty-rules` | в `content[]` правило `counterpartyKey = "IBAN:{IBAN_W}"`, `targetKind = WORKER_PAYOUT`, `workerId = WORKER_ID`, `origin = LEARNED` |
| 7 | `POST /api/bank/suggestions/{sugId}/confirm` повторно | `409 error.bank.suggestion.notOpen` |

_Покрывает:_ R14.5, R16.1, R16.3, R17.1, R17.3.

#### TC-API-DET-04 — Обученное правило срабатывает при следующей операции

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | Создать ещё одну `PLANNED` выплату `WORKER_ID` на `800.00` (`BANK_TRANSFER`, `payoutDate = today`); `APPEND` POSTED `t-{run-id}-4` `-800.00` от `IBAN_W`; `sync` | `transactionsNew = 1`; операция `AUTO_LINKED`, `links[0].kind = WORKER_PAYOUT` на новую выплату, `source = RULE`; выплата `PAID` |

_Покрывает:_ R14.3, R14.7.

#### TC-API-DET-05 — Счёт поставщика и обратное сопоставление [ocr]

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `APPEND` POSTED `t-{run-id}-doc` `-{DOC_TOTAL}` «FV/{run-id}/1» (контрагент `Hurtownia {run-id}`); `sync` | операция `UNLINKED` (документа ещё нет) |
| 2 | `POST /api/procurement-documents` (multipart: фикстура-счёт на `DOC_TOTAL`, номер `FV/{run-id}/1`, `projectId = PROJECT_ID`, `paymentChoice = NOT_LINKED`) | `200`, `paymentStatus = UNMATCHED` |
| 3 | Дождаться `status = PARSED` | `invoiceNumber`, `invoiceDate`, `supplierNip` (если NIP валиден) заполнены |
| 4 | Через ≤ 15 с `GET /api/bank/transactions/{t-…-doc id}` | обратное сопоставление: `AUTO_LINKED` (или `SUGGESTED` при неполном совпадении), `links[0].kind = PROCUREMENT`, `source = RULE` |
| 5 | `GET /api/procurement-documents/{docId}` | `paymentStatus = PAID` (для автопривязки на всю сумму), `ledgerEntries[*].transactionId` содержит операцию |

_Покрывает:_ R14.6, R16.4, R18.1–R18.3, R28.2.

#### TC-API-DET-06 — ИИ-классификация прочего расхода [ai]

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | Для `t-…-3` («Paliwo {run-id}», `-200.00`): `POST /api/bank/transactions/detect {ids: [id], includeAi: true}` | `aiQueued = 1` |
| 2 | Через ≤ 30 с `GET /api/bank/transactions/{id}` | предложение `source = AI`, `kind = OTHER_EXPENSE`, `rationale` непустой; статус `SUGGESTED` (не `AUTO_LINKED`); `aiClassifiedAt` заполнен |

_Покрывает:_ R15.4, R15.5.

#### TC-API-KL-01 — ИИ выключен: includeAi=true не вызывает ИИ [known-контекст]

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | В Docker (`BANK_AI_ENABLED=false`) `POST /api/bank/transactions/detect {ids: [t-…-3 id], includeAi: true}` | `200`, `aiQueued = 0`; у операции нет предложений с `source = AI`, `aiClassifiedAt = null` |

_Покрывает:_ R15.7 (backend-часть — ✅). UI-часть — см. TC-UI-KL-01 (❌ known).

### A7. Ручная привязка, переопределение, разделение, игнорирование

**Повторяемость:** новые операции `m-{run-id}-N` на `ACC_1`; ручные привязки удаляются в teardown.
`CAT_1`, `CAT_2` — два id из справочника непредвиденных категорий (`GET /api/unplanned-expense-categories`).

#### TC-API-LNK-01 — Ручная привязка прочего расхода и обучение правила

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `APPEND` `m-…-1` `-{E1}` «Rachunek {run-id}» (контрагент `Energia {run-id}`, без IBAN); `sync` | `UNLINKED` |
| 2 | `POST /api/bank/transactions/{id}/links {kind: "OTHER_EXPENSE", amount: E1, unplannedCategoryId: CAT_1, projectId: PROJECT_ID, remember: true}` | `201`, `linkStatus = LINKED`, `linkId` заполнен; `links[0].source = MANUAL` |
| 3 | `GET /api/bank/counterparty-rules` | правило `counterpartyKey = "NAME:…"` (нормализованное имя), `unplannedCategoryId = CAT_1` |
| 4 | `APPEND` `m-…-1b` `-{E2}` от `Energia {run-id}`; `sync` | `AUTO_LINKED`, `OTHER_EXPENSE`, `source = RULE`, `unplannedCategoryId = CAT_1` |
| 5 | `PUT /api/bank/links/{linkId шага 4} {kind: "OTHER_EXPENSE", amount: E2, unplannedCategoryId: CAT_2, remember: true}` | `200`, `LINKED`; `links[0].source = MANUAL`; правило переобучено на `CAT_2` |
| 6 | `APPEND` `m-…-2` `-50.00`; привязка `OTHER_EXPENSE` без `projectId`, `remember: false` | `201`, `LINKED` |

_Покрывает:_ R13.3, R16.8, R17.1, R17.3.

#### TC-API-LNK-02 — Разделение суммы и превышение

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `APPEND` `m-…-3` `-1000.00`; привязка `OTHER_EXPENSE` на `600.00` | `201`, `PARTIALLY_LINKED` |
| 2 | Вторая привязка на `400.00` | `201`, `LINKED`; в деталях `linkedAmount = 1000.00`, `remainingAmount = 0.00` |
| 3 | Третья привязка на `1.00` | `400 error.bank.link.overAllocated` |
| 4 | Привязка с `amount: 0` (на другой операции) | `400 error.bank.link.amountInvalid` |

_Покрывает:_ R13.4, R13.5 (Property 3).

#### TC-API-LNK-03 — Удаление привязки

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `DELETE /api/bank/links/{linkId}` (вторая привязка `m-…-3`) | `200`, `linkStatus = PARTIALLY_LINKED`, `payoutStaysPaid = false` |
| 2 | `DELETE` привязки `WORKER_PAYOUT` из DET-03 | `200`, `linkStatus = UNLINKED`, **`payoutStaysPaid = true`**; выплата остаётся `PAID` |

_Покрывает:_ R16.8, R17.1.

#### TC-API-LNK-04 — Игнорирование

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `APPEND` `m-…-4` `+3.21` «Zwrot prowizji {run-id}»; `POST …/{id}/ignore {}` | `400 error.bank.ignore.reasonRequired` |
| 2 | `POST …/{id}/ignore {reason: "Prowizja {run-id}"}` | `200`, `IGNORED`; в `GET /api/bank/transactions?connectionId={CONN_ID}&linkStatus=TO_REVIEW` операции нет; в итогах суммы не учитываются |
| 3 | `POST …/links` на игнорируемую операцию | `409 error.bank.link.transactionIgnored` |
| 4 | Игнорировать операцию с привязкой (`m-…-2`) | `409 error.bank.ignore.hasLinks` |
| 5 | `POST …/{m-…-4}/unignore` | `200`, `UNLINKED` |
| 6 | `sync` | статусы `IGNORED` / `LINKED` других операций сохранены |

_Покрывает:_ R17.5.

#### TC-API-LNK-05 — Выплата из операции

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `APPEND` `m-…-5` `-700.00` от `IBAN_X`; `POST /api/bank/transactions/{id}/payout {workerId: WORKER_ID}` | `201`, `linkStatus = LINKED` |
| 2 | `GET /api/worker-payouts?workerId={WORKER_ID}` | новая выплата `700.00`, `PAID`, `paymentChannel = BANK_TRANSFER`, `ledgerEntries[0].transactionId = id` |

_Покрывает:_ R16.2, R21.4 (роль без PAYROLL/CREATE — TC-API-ABAC-01 шаг 8).

### A8. Массовые операции и перезапуск автоопределения

**Повторяемость:** операции прогона; ничего не создаётся, кроме привязок (teardown общий).

| ID | Запрос | Ожидание | Покрывает |
|----|--------|----------|-----------|
| TC-API-BLK-01 | `APPEND` две операции, автопривязываемые правилом (как DET-01: `-11.00` и `-12.00` на `IBAN_B`); `POST /api/bank/transactions/bulk/confirm {ids: [a, b, <id UNLINKED>], remember: false}` | `200 {confirmed: 2, skipped: 1, failed: []}`; обе `LINKED` | R20.7 |
| TC-API-BLK-02 | `POST /api/bank/transactions/bulk/confirm {ids: [1…501]}` (501 разный id) | `400 error.bank.bulk.tooMany` | R20.7 |
| TC-API-BLK-03 | `POST /api/bank/transactions/detect {}` (нет `ids` и `filter`) | `400 error.bank.detect.selectionRequired` | R17.6 |
| TC-API-BLK-04 | `POST /api/bank/transactions/detect {filter: {connectionId: CONN_ID}}` | `200`, `processed` = число `UNLINKED`/`SUGGESTED` строк подключения (≤ 500) | R17.6 |
| TC-API-BLK-05 | `POST /api/bank/transactions/detect {ids: [1…501]}` | `400 error.bank.bulk.tooMany` | R17.6 |

### A9. Кассы и записи по кассе

**Повторяемость:** касса `Kasa {run-id}` (не по умолчанию), архивируется в teardown; суммы уникальны.

#### TC-API-CASH-01 — Касса: создание, правка, архив

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `POST /api/bank/cash-accounts {name: "Kasa {run-id}", currency: "PLN", openingBalance: 500.00, defaultForCash: false}` → `CASH_ID` | `201`, `balance = 500.00`, `archived = false` |
| 2 | `GET /api/bank/accounts?type=CASH` | `CASH_ID` присутствует, `type = CASH` |
| 3 | `PUT /api/bank/cash-accounts/{CASH_ID} {name: "Kasa {run-id} 2"}` | `200`, имя обновлено |
| 4 | `POST /api/bank/cash-accounts {name: ""}` | `400 error.bank.cash.nameRequired` |

_Покрывает:_ R27.1.

#### TC-API-CASH-02 — Ручная запись с привязкой к выплате

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `POST /api/worker-payouts {… paymentChannel: "BANK_TRANSFER", amount: C1}` → `PAYOUT_C` | `PLANNED` |
| 2 | `POST /api/bank/transactions/cash {accountId: CASH_ID, direction: "EXPENSE", amount: C1, date: today, counterpartyName: "Jan Kowalski {run-id}", title: "Wypłata gotówką {run-id}", link: {kind: "WORKER_PAYOUT", workerPayoutId: PAYOUT_C}}` | `201`; `amount = -C1`; `linkStatus = LINKED`; `transactionId` заполнен |
| 3 | `GET /api/worker-payouts?workerId=…` | `PAYOUT_C` `PAID`, `paymentChannel = CASH`; `ledgerEntries[0].channel = CASH` |
| 4 | `GET /api/bank/transactions/{transactionId}` | `line.channel = CASH`, `line.origin = MANUAL`, `line.editable = true`, `line.detection = null` |
| 5 | `GET /api/bank/transactions?channel=CASH&transactionId={transactionId}` | ровно эта строка |
| 6 | `GET /api/bank/cash-accounts/{CASH_ID}` | `balance = 500.00 − C1` |
| 7 | `DELETE /api/bank/transactions/{transactionId}` | `200 {transactionId, payoutStaysPaid: true}`; выплата остаётся `PAID` |

_Покрывает:_ R27.3, R27.5, R27.6, R16.8. Для ручной записи `SYSTEM`-запись не создаётся (R27.4).

#### TC-API-CASH-03 — Наличная выплата в зарплатах → одна SYSTEM-запись

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `POST /api/worker-payouts {… paymentChannel: "CASH", amount: C2}` → `PAYOUT_K` | `PLANNED` |
| 2 | `POST /api/worker-payouts/{PAYOUT_K}/pay` | `200`, `status = PAID` |
| 3 | Повторный `POST …/{PAYOUT_K}/pay` | `409` |
| 4 | `GET /api/worker-payouts?workerId=…` → `ledgerEntries` у `PAYOUT_K` | ровно 1 запись, `channel = CASH` |
| 5 | `GET /api/bank/transactions/{ledgerEntries[0].transactionId}` | `systemSource = PAYROLL_PAYOUT`, `line.origin = SYSTEM`, `line.editable = false`, `amount = -C2`, `LINKED` (касса по умолчанию; создаётся автоматически, если её нет) |
| 6 | `PUT` / `DELETE /api/bank/transactions/{id}` | `409 error.bank.entry.systemReadOnly` |

_Покрывает:_ R27.2, R27.4, R27.6 (Property 11).

#### TC-API-CASH-04 — Валидации записей по кассе

| ID | Запрос | Ожидание |
|----|--------|----------|
| 1 | `POST /api/bank/transactions/cash {}` | `400` (`error.bank.cash.*Required`) |
| 2 | Запись без `direction` | `400 error.bank.cash.directionRequired` |
| 3 | `PUT /api/bank/transactions/{id банковской операции}` | `400 error.bank.entry.notCash` |
| 4 | Запись по кассе, затем `PUT /api/bank/cash-accounts/{CASH_ID} {currency: "EUR"}` | `409 error.bank.cash.currencyLocked` |
| 5 | `POST /api/bank/cash-accounts/{CASH_ID}/archive`, затем запись в эту кассу | `409 error.bank.cash.accountArchived` |

_Покрывает:_ R27.1, R27.3, R27.6.

### A10. Закупки: «Оплачено наличными» и привязка к операции [ocr]

**Повторяемость:** документы в проекте `Bank07 {run-id}`; суммы уникальны.

#### TC-API-PRC-01 — Галочка «Оплачено наличными» (как её выполняет UI) и «Отметить как оплаченное наличными» [ocr]

UI отправляет загрузку без полей оплаты. Если галочка стоит, после `PARSED` UI вызывает `paid-in-cash` с пустым телом.
Сумма берётся из итога OCR, дата — из даты счёта (иначе дата загрузки), касса — по умолчанию.

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `POST /api/procurement-documents` (multipart: `file` — фикстура-счёт на `T1` с датой `D1`, `projectId`; без `paymentChoice`) | `200`; `paymentStatus = UNMATCHED`; `ledgerEntries = []` |
| 2 | Опрашивать `GET /api/procurement-documents/{id}/status` до `PARSED`, затем `GET …/{id}` | `documentTotal = T1`, `invoiceDate = D1` |
| 3 | `POST /api/procurement-documents/{id}/paid-in-cash {}` | `200`; `paymentStatus = PAID`; `ledgerEntries` — 1 запись `channel = CASH` |
| 4 | `GET /api/bank/transactions/{ledgerEntries[0].transactionId}` | `origin = SYSTEM`, `systemSource = PROCUREMENT_CASH`, `amount = -T1`, `bookingDate = D1`, касса по умолчанию |
| 5 | Повторный `POST …/paid-in-cash {}` | `409 error.procurement.payment.notUnmatched` |
| 6 | Документ `PARSED` без итога OCR (`documentTotal = null`): `POST …/paid-in-cash {}` | `400 error.procurement.cash.amountRequired`; записи по кассе нет |
| 7 | Тот же документ: `POST …/paid-in-cash {amount: C3, date: today−1}` (диалог строки) | `200`; запись `-C3`, `bookingDate = today−1`, касса по умолчанию |
| 8 | Документ `UNMATCHED` в проекте, переведённом в `COMPLETED`: `POST …/paid-in-cash {amount: C4}` | **`200`** — на завершённом проекте действие разрешено (нет stage-gate) |

_Покрывает:_ R28.1, R28.4, R28.5.

#### TC-API-PRC-02 — «Найти банковскую операцию»

| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | Для `UNMATCHED` документа `GET /api/bank/transactions/link-candidates?procurementDocumentId={id}` | `200`, список `{line, remaining, score, reasons[]}`, по убыванию `score` |
| 2 | `POST /api/bank/transactions/{line.id}/links {kind: "PROCUREMENT", amount: <итог / 2>, procurementDocumentId: id}` | `201`, `PARTIALLY_LINKED`; документ `PARTIALLY_PAID` |
| 3 | `PUT /api/bank/links/{linkId}` на полную сумму | `LINKED`; документ `PAID`, `ledgerEntries` содержит операцию |

_Покрывает:_ R16.4, R18.3, R28.1.

### A11. Валидации привязок

| ID | Запрос | Ожидание | Покрывает |
|----|--------|----------|-----------|
| TC-API-VAL-01 | Привязка `CLIENT_PAYMENT` к расходной операции | `400 error.bank.link.kindDirection` | R13.2 |
| TC-API-VAL-02 | Привязка `WORKER_PAYOUT` без `workerPayoutId` | `400 error.bank.link.targetRequired` | R13.3 |
| TC-API-VAL-03 | Привязка к `PLANNED` выплате на сумму, отличную от суммы выплаты | `409 error.bank.link.payoutAmountMismatch` | R16.1 |
| TC-API-VAL-04 | Привязка уже полностью привязанной выплаты (DET-04) к другой операции | `409 error.bank.link.targetOverLinked` | R13.6 |
| TC-API-VAL-05 | `GET /api/bank/sync-runs?from=2026-02-01&to=2026-01-01` | `422 error.bank.syncRun.periodInvalid` | R19.6 |
| TC-API-VAL-06 | `POST /api/bank/counterparty-rules` с уже существующим `counterpartyKey` | `409 error.bank.rule.duplicateKey` | R17.4 |
| TC-API-VAL-07 | `POST /api/bank/stub/connections/{id не-STUB}/data` | `409 error.bank.stub.notStubConnection` (на STUB-стенде без SE-подключений — пропуск) | R4.4 |

Примечание: `409 error.bank.refresh.notYetPossible` (R1.6) на STUB не воспроизводится — STUB всегда
разрешает обновление и не выставляет `nextRefreshPossibleAt`. Проверяется в части C (шаг C-7).

### A12. Callback'и Salt Edge (негативные)

**Повторяемость:** запросы не меняют состояние.

| ID | Запрос | Ожидание | Покрывает |
|----|--------|----------|-----------|
| TC-API-CB-01 | `POST /api/bank-feed/saltedge/callbacks/success` (тело `{"data":{}}`) без заголовка `Signature` | `401 error.bank.callback.unverified` | R10.2 |
| TC-API-CB-02 | Тот же запрос с `Signature: AAAA`, `Signature-key-version: 1` | `401` | R10.2 |
| TC-API-CB-03 | `POST …/callbacks/unknown` | `404` | R10.1 |
| TC-API-CB-04 | Тело больше 1 МБ | `413 error.bank.callback.tooLarge` | R24.2 |
| TC-API-CB-05 | Запрос без JWT вообще | не `401` от JWT-фильтра, а результат проверки подписи (`401 error.bank.callback.unverified`) — путь `permitAll` | R21.5 |

Положительная проверка подписи выполняется в `BankLedgerCallbackIntegrationTest`, а с настоящим
Salt Edge — в части C.

### A13. Уведомления

**Повторяемость:** проверяются только уведомления, созданные после отметки времени начала группы;
для сбоев — отдельное подключение `Stub-NTF {run-id}` с **одним** счётом (сбой единственного счёта даёт
`FAILED`).

| ID | Действие | Ожидание | Покрывает |
|----|----------|----------|-----------|
| TC-API-NTF-01 | `APPEND` новую несопоставимую операцию на S0; `sync` | у ADMIN, FINANCIER и MANAGER в `GET /api/notifications` есть `notification.bank.transactionsToReview` с deep-link `/company/bank-transactions?status=TO_REVIEW`; у FOREMAN нет | R22.2 #5 |
| TC-API-NTF-02 | `Stub-NTF`: `APPEND state.consentExpiresAt = now + 3 дня`; `sync` | у ADMIN и FINANCIER ровно одно `notification.bank.consentExpiring` (deep-link `/bank/connections?connectionId={id}`); повторный `sync` второго не создаёт | R22.2 #1 |
| TC-API-NTF-03 | `Stub-NTF`: `failNextFetch = PROVIDER`; `sync` → `FAILED`; снова `failNextFetch = PROVIDER`; `sync` | одно `notification.bank.syncFailed` за 24 ч (deep-link `/bank/sync-runs?connectionId={id}`) | R22.2 #3 |
| TC-API-NTF-04 | `Stub-NTF`: `state.status = REAUTH`; `sync` | `notification.bank.reauthRequired` у ADMIN и FINANCIER | R22.2 #2 |
| TC-API-NTF-05 | Пропуск сальдо (как BAL-01 шаг 3) | `notification.bank.balanceGap` с deep-link `/bank/accounts?accountId={id}` у ADMIN и FINANCIER | R22.2 #4 |
| TC-API-NTF-06 | Записи по кассе (CASH-02 / CASH-03), переопределение владельца (HLD-01) | новых `notification.bank.*` нет | R22.6 |

---

## Часть B. Браузерные сценарии

**Повторяемость:** пользователи и STUB-подключение из S0; данные засеиваются через API перед группой
(`POST /api/bank/stub/connections/{id}/data` + `sync`); teardown — как в общей стратегии. Язык по
умолчанию — RU; ожидаемые подписи приведены по `ru.json` (PL — в B12).

### B1. Навигация и маршруты

| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Войти под ADMIN | в разделе «Система» есть пункт **Банк**; в разделе «Компания» — **Банковские операции** |
| 2 | Клик «Банк» | URL `/bank/connections` (редирект с `/bank`), вкладка «Подключения» активна |
| 3 | Открыть `/bank/unknown-tab` | редирект на `/bank/connections` |
| 4 | Открыть `/bank?connectionId=1` | URL `/bank/connections?connectionId=1` (query сохранён) |
| 5 | Войти под FINANCIER | оба пункта видны |
| 6 | Войти под MANAGER | виден только «Банковские операции»; переход на `/bank/connections` и `/bank/connections/return` вручную — экран «нет доступа» |
| 7 | Войти под FOREMAN | ни одного из пунктов; `/company/bank-transactions` — «нет доступа» |

_Покрывает:_ R19.1, R19.2, R20.1, R21.1, R21.2. Примечание: `/bank/connections/return` требует
`BANK_CONNECTIONS/UPDATE`; стандартных ролей «READ без UPDATE» нет, поэтому отдельный кейс
«READ-only видит вкладки, но не страницу возврата» выполняется только при наличии такой тестовой роли.

### B2. Вкладка «Подключения»

| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | ADMIN → Банк | вкладка «Подключения»; колонки «Банк», «Название», «Провайдер», «Статус», «Согласие до», «Последняя синхронизация», «Счета» |
| 2 | «Добавить банк» → провайдер «Тестовый (stub)» → банк «Stub Bank Business» (поиск «Поиск банка…») → «Перейти в банк» | браузер уходит на `/bank/connections/return?connectionId=…&stub=1`, видно «Завершаем подключение…», затем тост «Банк подключён. Загружаем операции.» и возврат на `/bank/connections` |
| 3 | Строка нового подключения | статус «Активно», есть дата последней синхронизации |
| 4 | «Синхронизировать» | тост «Синхронизация запущена»; во вкладке «История синхронизаций» новая строка с запуском «Вручную» |
| 5 | «Переименовать» → `Stub UI {run-id}` | имя обновилось |
| 6 | Через API засеять `state.consentExpiresAt = now + 5 дней` и синхронизировать; обновить страницу | баннер «Согласие для … истекает …. Переподключите банк.» |
| 7 | «Отключить» → диалог «Отключить подключение?» → подтвердить | статус «Отключено»; действие «Удалить» доступно |
| 8 | «Удалить» → подтвердить | строка исчезла (у подключения UI нет привязок) |
| 9 | Открыть `/bank/connections/return?connectionId=999999999&stub=1` | «Не удалось подключить банк.» с локализованной ошибкой и кнопкой «Вернуться к подключениям» |

_Покрывает:_ R1.3–R1.8, R19.3, R19.4.

### B3. Вкладка «Счета»: владелец, пропуск, кассы

Предусловие: подключение `Stub-HM {run-id}` из HLD-01 (до шага 6) и `Stub-BAL {run-id}` с пропуском.

| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Открыть «Счета» (`/bank/accounts`) | счета сгруппированы по подключению; IBAN группами по 4; баланс в формате локали; колонки «Счёт», «IBAN», «Валюта», «Баланс», «Доступно», «Баланс на», «Владелец», «В реестре» |
| 2 | Счета `Stub-HM` | бейджи «Владелец совпадает с компанией» / «Владелец счёта не совпадает с компанией» (переключатель выкл.) / «Владелец не проверен» |
| 3 | Включить переключатель «В реестре» у MISMATCH | диалог «Включить в реестр счёт другого владельца?» с текстом про владельца и компанию; без причины кнопка «Включить в реестр» не проходит |
| 4 | Ввести причину `Konto wspólnika {run-id}` → «Включить в реестр» | переключатель включён, предупреждение исчезло, после перезагрузки состояние сохранено |
| 5 | Счёт `Stub-BAL` | бейдж «Пропуск данных», кнопка «Подтвердить» → диалог с «Комментарий» → бейдж исчезает |
| 6 | Группа «Кассы (наличные)» → «Добавить кассу» → «Название кассы» `Kasa UI {run-id}`, PLN, «Начальный остаток» `100,00` | касса в группе, баланс `100,00` |
| 7 | «Редактировать кассу» → отметить «Касса по умолчанию» | флаг по умолчанию перешёл на эту кассу (у прежней снят) |
| 8 | «Архивировать» | касса помечена архивной / скрыта из активных |

_Покрывает:_ R3.2, R3.7, R3.8, R7.3, R19.5, R27.1.

### B4. «История синхронизаций» и «Правила контрагентов»

| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | «История синхронизаций», фильтр по подключению `Stub {run-id}` | строки с колонками «Начало», «Окончание», «Подключение», «Запуск», «Статус», «Новые», «Обновлённые», «Заменено ожидающих», «На проверку», «Ошибка»; у `PARTIAL`-строки (ING-03) — локализованная ошибка |
| 2 | Фильтр по статусу и периоду | список фильтруется; период «с > по» не отправляется / показывает ошибку |
| 3 | Раскрыть строку | разбивка по счетам |
| 4 | «Правила контрагентов» | правило из DET-03 (работник `Jan Kowalski {run-id}`, «Привязка» — выплата работнику) и правило из LNK-01; колонки «Срабатываний», «Последний раз», «Активно» |
| 5 | «Редактировать правило» → выключить «Активно» | правило неактивно; новая операция `-800.00` от `IBAN_W` (через API) больше не автопривязывается правилом |
| 6 | Удалить правило | строка исчезла |

_Покрывает:_ R17.4, R19.6, R19.7.

### B5. Вкладка «Поддерживаемые банки»

| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Открыть `/bank/supported-banks` | 2 строки «Stub Bank Business», «Stub Bank Personal» с бейджем «Тестовый банк»; колонки возможностей (PSD2, бизнес / личные счета, данные владельца, ежедневное обновление, макс. дней согласия, статус); «Список от …» |
| 2 | «Только бизнес» | одна строка |
| 3 | «Скрыть тестовые банки» | пусто, сообщение «Нет банков по выбранным фильтрам» (не «провайдер не открывает банки») |
| 4 | «Обновить список» | время «Список от …» обновилось |
| 5 | «Подключить» у «Stub Bank Personal» | открывается «Добавить банк» с предвыбранным банком |

_Покрывает:_ R19.9.

### B6. Список «Банковские операции»

| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | FINANCIER → «Банковские операции», «Период» → «Текущий месяц», «Банк» = `Stub {run-id}` | видны операции из A4–A8; колонки «Дата», «Канал», «Вид» (Приход/Расход), «Банк / касса», «Счёт», «Контрагент» (для прихода — «Плательщик», для расхода — «Получатель»), «Назначение», «Сумма» (знак + цвет), «Автоопределение», «Связанная сущность», «Статус» |
| 2 | Навести на маркер автоопределения у `t-…-2` | тултип «Правило · уверенность 95%»; у `t-…-int` — «… 100%» |
| 3 | «Связанная сущность» у `t-…-2` | «Оплата клиента» + проект `Bank07 {run-id}` + замок (тултип «Привязка заблокирована: платёж уже разнесён»); ссылка ведёт к оплатам проекта |
| 4 | Фильтр «На проверку» | только «Не привязана» / «Есть предложение» / «Частично привязана» / неподтверждённые «Привязана автоматически» |
| 5 | Фильтр «Канал» = «Наличные» | только записи по кассе (ручные — «Вручную», системные — «Автоматически»); маркера автоопределения у них нет |
| 6 | Перезагрузить страницу | фильтры восстановлены из `localStorage` |
| 7 | Панель итогов | «Приход», «Расход», «Итог за период», «Операций», «На проверку» совпадают с суммой видимых строк (игнорируемые не учитываются); клик «На проверку» включает фильтр |
| 8 | Фильтр «Банк» = `Stub-PND {run-id}`, pending-операция `p-…-3` (ING-02) | бейдж «Ожидает проводки», действия привязки недоступны |
| 9 | Открыть `/company/bank-transactions?status=TO_REVIEW` (deep-link уведомления) | применён фильтр «На проверку» |
| 10 | Открыть `/company/bank-transactions?transactionId={t-…-2 id}` | сразу открыта карточка «Детали операции» этой операции; закрытие убирает параметр из URL |

_Покрывает:_ R20.2–R20.4, R20.6, R20.8, R6.3, R22.3.

### B7. Карточка операции: подтверждение, ручная привязка, переопределение

| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Операция `SUGGESTED` (подготовить как DET-03 с новой выплатой) | блок «Предложения»: вариант с источником и уверенностью; «Запомнить для этого контрагента» включено по умолчанию; «Подтвердить» → статус «Привязана» |
| 2 | Другое предложение → «Отклонить» | предложение исчезло |
| 3 | `UNLINKED` расход → «Привязать вручную» → «Тип привязки» «Прочий расход» → «Категория» → «Проект (необязательно)» → «Сумма» (по умолчанию весь остаток) | привязка создана, статус «Привязана» |
| 4 | «Изменить привязку» → «Выплата работнику» → выбрать выплату той же суммы | привязка заменена |
| 5 | «Удалить привязку» у привязки к выплате | предупреждение «Выплата останется оплаченной — при необходимости исправьте её в зарплатах.»; после подтверждения «Не привязана» |
| 6 | «Разделить» на две суммы | «Частично привязана» (видно «Осталось привязать: …»), затем «Привязана» |
| 7 | «Игнорировать» → «Причина» | «Игнорируется»; «Вернуть» → «Не привязана» |
| 8 | Заблокированная `t-…-2` | действия изменения / удаления привязки недоступны, виден замок |
| 9 | Под ADMIN — блок «Данные провайдера» | виден; под FINANCIER — не виден |
| 10 | Выбрать 2 строки «Привязана автоматически» → «Подтвердить выбранные» | обе «Привязана» |
| 11 | «Создать выплату» на расходе от `IBAN_X` → «Работник» `Jan Kowalski {run-id}` | привязка «Выплата работнику», выплата видна в зарплатах как оплаченная |

_Покрывает:_ R16.1, R16.2, R16.8, R17.1–R17.3, R17.5, R20.5, R20.7, R21.6.

### B8. Запись по кассе

| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | «Добавить запись по кассе» | диалог «Запись по кассе»: «Способ оплаты» = «Наличные», «Касса», вид, «Сумма», «Дата», «Контрагент», «Описание», «Привязка (необязательно)» |
| 2 | Касса `Kasa {run-id}`, «Расход», сумма, «Описание» `Kawa {run-id}`, без привязки → сохранить | строка с каналом «Наличные», происхождение «Вручную», «Не привязана» |
| 3 | Открыть её → «Редактировать» → изменить сумму | сумма обновлена |
| 4 | «Удалить» → «Удалить запись по кассе?» → подтвердить | строка исчезла |
| 5 | Открыть SYSTEM-запись (CASH-03) | текст «Запись создана автоматически — исправьте её в источнике (зарплаты / закупки).»; «Редактировать» / «Удалить» недоступны |
| 6 | Новая запись с привязкой «Выплата работнику» к `PLANNED`-выплате | статус «Привязана»; выплата в зарплатах — оплачена наличными |

_Покрывает:_ R27.3, R27.5, R27.6, R20.8.

### B9. Загрузка счёта из операции [ocr]

| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | В расходной операции «Загрузить счёт» → диалог «Загрузить счёт к операции» → проект `Bank07 {run-id}` → файл-фикстура | сразу появляется привязка «Закупка материалов», статус документа «Распознаём счёт…» |
| 2 | Дождаться окончания OCR | у документа поставщик, сумма, номер счёта; ссылка ведёт в закупки проекта |
| 3 | Загрузить нечитаемый файл | «Не удалось распознать счёт», кнопка «Распознать повторно»; привязка сохраняется |

_Покрывает:_ R16.4, R16.5, R18.

### B10. Закупки проекта: галочка «Оплачено наличными» [ocr]

| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Проект `Bank07 {run-id}` → закупки | у кнопки «Загрузить» только галочка «Оплачено наличными» (по умолчанию снята); нет «Как оплачено?», «Сумма», «Дата», «Касса», «Найти банковскую операцию» |
| 2 | Поставить галочку → загрузить читаемый счёт на `T1` | идёт распознавание; после `PARSED` — строка «Оплачено наличными» с зелёной отметкой; галочка снова снята |
| 3 | Строка документа в списке | бейдж «Оплачен»; в «Операции» ссылка на запись по кассе на `T1` с датой счёта (касса по умолчанию) |
| 4 | Без галочки загрузить ещё один счёт | после `PARSED` бейдж «Не сопоставлен»; записи по кассе нет |
| 5 | С галочкой загрузить нечитаемый файл | «Не удалось распознать счёт»; записи по кассе нет, бейдж «Не сопоставлен» |
| 6 | С галочкой загрузить счёт, где OCR не нашёл итог | под загрузкой ошибка «сумма обязательна»; документ «Не сопоставлен» |
| 7 | Строка «Не сопоставлен» → «Отметить как оплаченное наличными» | в диалоге только «Сумма» (итог OCR) и «Дата» (дата счёта), поля «Касса» нет; после сохранения — «Оплачен» |
| 8 | Строка «Не сопоставлен» → «Найти банковскую операцию» → выбрать операцию | бейдж «Оплачен» / «Частично оплачен»; операция привязана «Закупка материалов» |
| 9 | Проект `COMPLETED`, документ «Не сопоставлен» | загрузки нет; «Отметить как оплаченное наличными» **доступно** и выполняется |
| 10 | FOREMAN без `BANK_LEDGER` | «Найти банковскую операцию» в строке не показывается |
| 11 | Пользователь только с `PURCHASES` READ | ни загрузки, ни галочки, ни «Отметить как оплаченное наличными» |

_Покрывает:_ R28.1, R28.4, R28.5.

### B11. Зарплаты: ссылки на реестр

| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | ADMIN → `/company/payroll` → карточка работника `Jan Kowalski {run-id}` | у выплат из DET-03 / CASH-03 ссылка «Операция в реестре» (дата, счёт, сумма) |
| 2 | Клик по ссылке | `/company/bank-transactions?transactionId={id}`, открыта карточка операции |
| 3 | Выплата без привязки | ссылки нет |

_Покрывает:_ R16.3.

### B12. Локализация

| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Переключить RU → PL на `/bank/*`, `/company/bank-transactions`, в карточке операции и диалогах | PL: «Bank», «Operacje bankowe», вкладки «Połączenia», «Rachunki», «Historia synchronizacji», «Reguły kontrahentów», «Obsługiwane banki»; бейдж пропуска «Luka w danych» (`bank.accounts.gap.label`), поле «Opis» в записи по кассе (`bankTransactions.cash.titleField`); сырых ключей (`bank.…`, `bankTransactions.…`) нет |
| 2 | Вызвать ошибки (например, игнор без причины, превышение суммы) | тексты `error.bank.*` локализованы |
| 3 | Колокольчик уведомлений | заголовки `notification.bank.*` локализованы (RU: «Новые банковские операции ждут проверки» и т. д.) |

_Покрывает:_ R23.

### B13. Известное ограничение: действие ИИ при выключенном ИИ [known]

#### TC-UI-KL-01 — «Перезапустить автоопределение» видно и шлёт includeAi=true

| Шаг | Действие | Ожидание по R15.7 | Фактическое поведение реализации | Статус |
|-----|----------|------------------|----------------------------------|--------|
| 1 | В Docker (`BANK_AI_ENABLED=false`) открыть карточку `UNLINKED` операции | действие повторного ИИ-анализа скрыто | кнопка «Перезапустить автоопределение» видна | ❌ known |
| 2 | Нажать кнопку, наблюдать сетевой запрос | — | `POST /api/bank/transactions/detect {ids:[id], includeAi: true}` | ❌ known |
| 3 | Проверить результат | маркеров ИИ нет | маркеров ИИ нет (`aiQueued = 0`) | ✅ |

Не считать провалом прогона; закрывается после выставления флага «ИИ включён» во фронтенд.

---

## Часть C. Ручной сценарий Salt Edge на окружении [env]

Выполняется **на каждом окружении** (dev первым), отдельно для режима **CLIENT** (сейчас) и **PARTNER**
(после получения доступа).

**Предусловия (env / Secret Manager, не в git):**
- `FOREMEN_PUBLIC_BASE_URL` — публичный HTTPS-адрес окружения (например, для dev `https://dev.foremen.eu`);
  далее `{BASE}`;
- `FOREMEN_BANK_STUB_ENABLED=false` (обязательно вне локального Docker);
- `SALTEDGE_ENABLED=true`, `SALTEDGE_MODE=CLIENT|PARTNER`, `SALTEDGE_APP_ID`, `SALTEDGE_SECRET`;
  для PARTNER — `SALTEDGE_LEAD_EMAIL`; `FOREMEN_BANK_SECRETS_KEY`;
- в дашборде Salt Edge зарегистрированы callback'и `{BASE}/api/bank-feed/saltedge/callbacks/{success,fail,notify,destroy}`;
- в лимите fake-подключений есть свободные слоты.

**Повторяемость:** подключение `SE {run-id}` (логин fake-банка `username{run-id}`); в конце `disable`
(подключение удаляется / отзывается и в Salt Edge).

| # | Действие | Ожидание |
|---|----------|----------|
| C-1 | `GET {BASE}/api/bank/providers` под ADMIN | есть `SALT_EDGE` с `mode` = настроенному; `STUB` **отсутствует** |
| C-2 | Банк → «Поддерживаемые банки» | список из API Salt Edge (не захардкожен); при аккаунте в статусе Pending — только fake-банки или сообщение «Провайдер пока не открывает польские банки…»; в CLIENT — у части банков «Нужны свои ключи» (кнопка «Подключить» недоступна) |
| C-3 | «Добавить банк» → Salt Edge → «Fake Bank Simple» (`fakebank_simple_xf`) → «Перейти в банк» | переход на виджет Salt Edge Connect |
| C-4 | Логин `username{run-id}`, пароль `secret`, подтвердить | возврат на `{BASE}/bank/connections/return?…`, затем «Банк подключён. Загружаем операции.»; подключение «Активно» |
| C-5 | Логи backend | принят `success`-callback с проверенной подписью; run с запуском «После подключения» / «Уведомление провайдера»; в PARTNER — использован lead (`SALTEDGE_LEAD_EMAIL`), в CLIENT — customer |
| C-6 | «Счета» и «Банковские операции» | счета и операции fake-банка загружены, бейдж владельца выставлен, у операций заполнены плательщик / получатель |
| C-7 | «Обновить сейчас» сразу после подключения | «Обновление станет доступно после …» (`409 error.bank.refresh.notYetPossible`, `next_refresh_possible_at`) |
| C-8 | «Синхронизировать» повторно; SQL `SELECT transactions_cursor FROM bank_accounts WHERE connection_id = …` | новых дублей нет; в курсоре — числовой resume-id (макс. id проведённой операции), **не** `next:<id>` (это курсор продолжения внутри одной выборки) |
| C-9 | `POST {BASE}/api/bank-feed/saltedge/callbacks/notify` с неверной подписью (curl) | `401` |
| C-10 | «Отключить» | статус «Отключено»; в Salt Edge подключение удалено / согласие отозвано (`scripts/saltedge-fake-connections.sh status` его больше не показывает) |

_Покрывает:_ R8–R12 (end-to-end), R1.6, R9.2, R19.9.

---

## Регрессия

**Повторяемость:** только чтение или сущности с `run-id`; teardown как в общей стратегии.

| ID | Проверка | Ожидание |
|----|----------|----------|
| REG-01 | `POST /api/payments/bank-feed/ingest` (FOR-06-07) под ADMIN | `200 {"polled": 0}` — No-Op-провайдер не затронут |
| REG-02 | Ручной `POST /api/worker-payouts/{id}/pay` для выплаты `BANK_TRANSFER` (FOR-07-06) | `PAID`, `paymentChannel = BANK_TRANSFER`; SYSTEM-запись по кассе **не** создаётся; `ledgerEntries = []` |
| REG-03 | Загрузка счёта без `paymentChoice` (FOR-06-03), список строк закупок (FOR-06-04) | работает как раньше; `paymentStatus = UNMATCHED`; для старых документов новые поля (`supplierNip`, `invoiceNumber`, `invoiceDate`) — `null` |
| REG-04 | Меню «Компания»: «Grafik zespołu», «Zakupy firmowe», «Wynagrodzenia»; меню «Система»: Users, Roles, Audit | на месте, видимость по ролям не изменилась |
| REG-05 | Ручная оплата клиента (FOR-06-07 `POST /api/payments/schedules/{id}/transactions`) | транши и уведомления `payment.*` как раньше |
| REG-06 | Старт backend с `SALTEDGE_ENABLED=false`, `FOREMEN_BANK_STUB_ENABLED=false`, пустым `FOREMEN_PUBLIC_BASE_URL` и без `FOREMEN_BANK_SECRETS_KEY` | приложение стартует; `GET /api/bank/providers` → `[]`; `/api/bank/stub/**` → `404`; callback → `401` |
| REG-07 | Повторное применение changelog (liquibase) на существующей БД | changesets 201–206 не вставляют дублей (`MARK_RAN`) |
| REG-08 | `PermissionAnnotationValidator` при старте | старт без ошибок; `SaltEdgeCallbackController` без permission-аннотаций (намеренно) |
| REG-09 | Упрощённая загрузка в закупках (после выкатки): только галочка «Оплачено наличными»; с галочкой → после `PARSED` запись по кассе на итог OCR, касса по умолчанию; без галочки — «Не сопоставлен»; в диалоге строки нет «Касса» | как в B10 шаги 1–4 и 7 |

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | S0 | 6 | `POST /api/bank/connections {provider: STUB, …}` | `201`, `connection.status = PENDING_AUTH` | | ✅/❌/⏭ |
| 2 | TC-API-ING-01 | 2 | `POST /api/bank/connections/{id}/sync` + опрос run | `SUCCEEDED`, `transactionsNew = 3` | | |
| 3 | TC-API-DET-02 | 1 | `GET /api/bank/transactions/{t-2}` | `AUTO_LINKED`, `CLIENT_PAYMENT`, `locked = true` | | |
| 4 | TC-API-BLK-03 | 1 | `POST /api/bank/transactions/detect {}` | `400 error.bank.detect.selectionRequired` | | |
| 5 | TC-UI-B6 | 2 | наведение на маркер `t-2` | «Правило · уверенность 95%» | | |
| 6 | TC-UI-KL-01 | 1 | карточка операции при выключенном ИИ | кнопка скрыта | кнопка видна | ❌ known |

Легенда статусов: ✅ пройдено · ❌ провалено · ❌ known — известное расхождение (не влияет на итог) ·
⏭ пропущено (маркер [ai] / [ocr] / [env] без окружения).

Итог: X пройдено / Y провалено / K known / Z пропущено. Run-id: `<timestamp/uuid>`.
Стратегия повторяемости: генератор `run-id` + teardown. Окружение: `<docker | dev | …>`, режим Salt Edge:
`<— | CLIENT | PARTNER>`.


## Отчёт о прогоне автотестов (final checkpoint)

Прогонялись только затронутые классы (не полный набор). Источник: JUnit XML (`foremen-backend/build/test-results/test/`) и `/tmp/for-07-04-final-frontend.log`.

### Backend (`./gradlew test --tests ...`, EXIT=0)

| Класс / набор | tests | failures | errors | Статус |
|---|---|---|---|---|
| `com.foremen.service.bank.**` (account, cash, connection, connector, saltedge, stub, detect, ingest, ledger, link, notify, secret, sync, PublicBaseUrlTest) | 503 | 0 | 0 | ✅ |
| `com.foremen.controller.bank.**` (10 классов, вкл. ABAC) | 45 | 0 | 0 | ✅ |
| `BankLedger{Abac,Callback,CashEndToEnd,Startup,StubEndToEnd}IntegrationTest` | 18 | 0 | 0 | ✅ |
| `BankEntitiesMappingIntegrationTest` | 3 | 0 | 0 | ✅ |
| `BankLedgerMessagesParityTest` | 2 | 0 | 0 | ✅ |
| `PaymentTransactionServiceTest` | 14 | 0 | 0 | ✅ |
| `PayrollControllerTest` | 7 | 0 | 0 | ✅ |
| `ProcurementDocumentControllerAbacTest` | 15 | 0 | 0 | ✅ |
| `WorkerPayoutServiceTest` (с вложенными) | 26 | 0 | 0 | ✅ |
| `AllocationReversiblePropertyTest` / `WorkerPayoutLifecyclePropertyTest` | 3 / 7 | 0 | 0 | ✅ |
| **Итого** | **643** | **0** | **0** | ✅ |

### Frontend (`foremen-frontend`)

| Проверка | Результат | Статус |
|---|---|---|
| `npx tsc --noEmit -p tsconfig.app.json` | 0 ошибок | ✅ |
| `vitest --run` по `src/features/bank`, `src/features/bank-transactions`, `src/features/project-execution/procurement`, `src/features/company-payroll`, `src/config`, `src/locales` | 47 файлов / 474 теста, 0 падений | ✅ |
| Паритет локалей (`src/locales/__tests__/locale-parity.*`, `locale-parity.for0603`) | пройден в составе прогона выше | ✅ |

Примечание: ранее известные падения в `src/app/layout` (Sidebar/AppShell/TopBar) и `project-workspace/roomsTabSwapAndRouteGating` лежат вне директорий этой спеки, в прогон не входили и не исправлялись.

### Не выполнено в этом прогоне

Ручные сценарии частей A/B/C (Docker-стек, браузер, реальная интеграция Salt Edge) в этом прогоне не выполнялись и остаются для QA / rollout.
