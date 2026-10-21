# Тест-кейсы: FOR-06-11 — Payroll (выплаты работникам)

## Назначение и характер спеки

Спека **API-центричная**: payroll — это внутренняя бухгалтерская поверхность (деньги **наружу** —
то, что платят работнику), оперируемая ролями FINANCIER / MANAGER / ADMIN. Поэтому набор тест-кейсов —
это **API-тесты против поднятого в Docker приложения** (`http://localhost:8080`), а не браузерные
сценарии. Каждый шаг — HTTP-запрос к payroll-API под `/api/worker-payouts...` с проверкой статуса,
тела и заголовков ответа.

Payroll **не вычисляет стоимость сам**: gross обычной выплаты — это `Σ Cost_Value` по **неоплаченным**
`APPROVED` строкам отчётов работника в выбранном периоде, читаемый через FOR-06-10 `WorkerCostService`
(единственный cost-seam) **дословно** — второй формулы стоимости нет (R3.1). Проверяемые механизмы:

- превью предлагаемой суммы (`GET /suggest`);
- создание **обычной** выплаты (линкует строки отчётов + применяет аванс-кредит);
- создание **аванса** (добавляет кредит, строк не линкует);
- отметка оплаты (`PLANNED → PAID`);
- удаление `PLANNED`-выплаты (освобождает строки + разворачивает кредит);
- защита от двойной оплаты (залинкованная строка больше не предлагается в последующем `suggest`);
- отказ в удалении аванса, кредит которого уже потреблён (`409`);
- ABAC-матрица ресурса `PAYROLL` (ADMIN/MANAGER/FINANCIER — полный доступ; FOREMAN — только чтение;
  CLIENT/WORKER — запрещено);
- project-scope gate (`404` для чужого проекта).

Результат прогона — **MD-репорты с таблицами** (шаг → запрос → ожидание → факт → статус).
**Скриншоты не требуются.**

### Эндпоинты payroll (под `/api/worker-payouts`)

| Метод + путь | Операция ABAC | Назначение |
|--------------|---------------|------------|
| `GET /api/worker-payouts?projectId=&workerId=&status=` | READ | список выплат проекта + остаток аванс-кредита по каждому работнику |
| `GET /api/worker-payouts/suggest?projectId=&workerId=&from=&to=` | READ | превью диалога создания (gross / remainingCredit / appliedCredit / suggested / breakdown) |
| `POST /api/worker-payouts?projectId=` (тело `CreatePayoutRequest`) | CREATE | создать выплату в статусе `PLANNED` |
| `POST /api/worker-payouts/{id}/pay` | UPDATE | отметить оплаченной (`PLANNED → PAID`) |
| `DELETE /api/worker-payouts/{id}` | DELETE | hard-delete только `PLANNED` |

`CreatePayoutRequest = { workerId, periodFrom, periodTo, paymentChannel, amount, note? }`.

### Покрытие требований

Каждый кейс помечен строкой `_Покрывает:_`. Явно покрываемые требования задачи: R3.1, R4.1, R4.2,
R5.5, R5.6, R5.7, R6.2, R6.3, R7.1, R7.3, R10.1, R10.4, R10.5, R12.3.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции,
   включая changesets `180`–`182` этой спеки), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN — через переменные окружения `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`,
   `FOREMEN_ADMIN_PASSWORD`. Логин: `POST /api/auth/login {email, password}` → `accessToken`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном (проверка готовности ABAC:
   `GET /api/worker-payouts?projectId={PROJECT_ID}` под ADMIN на существующем `ACTIVE`-проекте
   отвечает `200` с пустым/непустым списком, а не 401/403/500).

## Общая стратегия повторяемости

**Комбинированная: генератор `run-id` + teardown.** Единый на прогон `run-id` (timestamp или uuid,
например `1730000000` или `a1b2c3`) встраивается во все создаваемые идентификаторы, чтобы два
последовательных прогона на одной БД не конфликтовали:

- названия проектов / клиентов и email пользователей, создаваемых в Setup: `Payroll Test {run-id}`,
  `worker+{run-id}@example.com`, `manager+{run-id}@example.com`, …;
- заметки выплат (`note`): `pay-{run-id}-01`, `pay-{run-id}-02`, …

Поскольку payroll-выплаты и `paidByPayout`-линки создаются внутри теста, каждая группа, создающая
выплаты, работает на **новом проекте на прогон** через admin-API и доводит его до `ACTIVE` с нужным
набором `APPROVED`-отчётов работника с резолвимым `Cost_Value` (через FOR-06-01 reporting +
FOR-06-10 cost-rates). Это и есть генератор-повторяемость.

**Teardown каждой группы** (если не указано иное) выполняется в конце набора в обратном порядке
создания: все `PLANNED`-выплаты проекта удаляются (`DELETE /api/worker-payouts/{id}` — освобождает
залинкованные строки отчётов и разворачивает кредит), затем тестовый проект переводится в `CANCELLED`
либо удаляется доступным admin-API (каскад снимает отчёты/линки по FK), затем деактивируются/удаляются
созданные пользователи. **Сид-данные** (роли, ресурс `PAYROLL`, матрица из changeset 182) — **только
читаются, никогда не изменяются**. `PAID`-выплаты неудаляемы (R7.2); если группа довела выплату до
`PAID`, teardown опирается на удаление/отмену всего проекта. Каждая группа ниже дополнительно уточняет
стратегию строкой `**Повторяемость:**`.

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Там, где кейс требует роли MANAGER / FINANCIER / FOREMAN / CLIENT / WORKER,
тестовые пользователи создаются и приглашаются в проект как `ProjectMember` соответствующей роли через
admin-API в группе `Setup`. Матрица ресурса `PAYROLL` (changeset 182, R10.4):

| Ресурс | ADMIN | MANAGER | FINANCIER | FOREMAN | CLIENT | WORKER |
|--------|-------|---------|-----------|---------|--------|--------|
| `PAYROLL` | CRUD | CRUD | CRUD | R | — | — |

Доступ проект-scoped: чтение никогда не возвращает выплаты чужого проекта, запись ограничена
резолвимым проектом (enforced `ProjectScopedService`, `getProjectIdPath = "project.id"`). Для
чужого / несуществующего проекта или выплаты ожидается `404` (неразличимо с «не найдено»), R10.5.

### Эталонный набор enum (для проверки локализации)

- `PayoutStatus`: `PLANNED`, `PAID` → `payroll.status.*` (`PAID` терминален).
- `PaymentChannel`: `BANK_TRANSFER`, `CASH` → `payroll.channel.*`.

### Коды ошибок payroll

- `error.workerPayout.channel.required` — отсутствующий/неизвестный канал (R2.2) → `422`.
- `error.workerPayout.amount.invalid` — отрицательная/нечисловая сумма (R4.4) → `422`.
- `error.workerPayout.period.invalid` — `periodTo < periodFrom` (R4.5) → `422`.
- `error.workerPayout.worker.notMember` — работник не ACTIVE-участник проекта (R4.6) → `422`.
- `error.workerPayout.status.illegalTransition` — операция над `PAID`-выплатой (R7.2) → `409`.
- `error.workerPayout.advance.alreadyApplied` — удаление аванса с уже потреблённым кредитом (R5.7) → `409`.

---

# API-тесты (docker-стек)

## Фича 0. Setup — токены, проект, участники и APPROVED-отчёты

**Повторяемость:** читает сид + создаёт помеченные `run-id` сущности; teardown в конце набора.

### TC-API-SETUP-01 — Логин ADMIN

**Предусловия:** стек поднят, `FOREMEN_ADMIN_*` заданы.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/auth/login` c телом `{ "email": "<FOREMEN_ADMIN_EMAIL>", "password": "<FOREMEN_ADMIN_PASSWORD>" }` | `200`, тело содержит непустой `accessToken` (сохранить как `ADMIN_TOKEN`) |

_Покрывает:_ предусловие для всех API-кейсов.

### TC-API-SETUP-02 — Проект, участники и резолвимая стоимость

**Предусловия:** `ADMIN_TOKEN` получен.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | Создать проект `Payroll Test {run-id}` через admin-API, перевести в стадию `ACTIVE` | `2xx`; сохранить `PROJECT_ID` |
| 2 | Пригласить тестовых участников-работников `WORKER_A`, `WORKER_B` (`ProjectMember`, ACTIVE) с назначенным `workerType` (для резолва `Worker_Service_Cost` через FOR-06-10) | `2xx`; сохранить `WORKER_A_ID`, `WORKER_B_ID` |
| 3 | Пригласить ролевых пользователей: MANAGER, FINANCIER, FOREMAN, CLIENT (`ProjectMember`) | `2xx`; сохранить `MANAGER_TOKEN`, `FINANCIER_TOKEN`, `FOREMAN_TOKEN`, `CLIENT_TOKEN` |
| 4 | Создать для `WORKER_A` набор `APPROVED` строк отчётов (`WorkReportRoom`) с резолвимым `Cost_Value` в периоде `[2027-01-01, 2027-01-31]` (напр. суммарный gross `3000.00`) по флоу FOR-06-01 | `2xx`; все строки `paidByPayout = null` (payable) |
| 5 | Создать как минимум одну строку отчёта с **недоступной** стоимостью (null/zero-rate work-item) для проверки R3.4 | `2xx`; строка помечаема unavailable |

_Покрывает:_ R4.6 (ACTIVE-membership), R10.1 (стадия `ACTIVE`), предусловие для групп 1–7.

---

## Фича 1. Превью предлагаемой суммы (R3)

**Повторяемость:** `suggest` ничего не персистит; teardown общий.

### TC-API-SUG-01 — gross = Σ Cost_Value по неоплаченным доступным строкам

**Предусловия:** `PROJECT_ID`, `WORKER_A_ID` с `APPROVED` строками на `3000.00` gross; остаток
аванс-кредита работника = 0.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `GET /api/worker-payouts/suggest?projectId={PROJECT_ID}&workerId={WORKER_A_ID}&from=2027-01-01&to=2027-01-31` (Bearer `ADMIN_TOKEN`) | `200`; `PayoutSuggestionDto`: `isAdvance=false`, `gross=3000.00`, `remainingCredit=0.00`, `appliedCredit=0.00`, `suggested=3000.00` |
| 2 | Проверить `breakdown[]` | по каждой **доступной** строке: `workItemName`, `roomName`, период, `workerTypeName` (+ `baseTierFallback`), `quantity`, `unitServiceCost`, `costValue`, `available=true` |
| 3 | Проверить строку с недоступной стоимостью | в `breakdown` помечена `available=false`, её `costValue` **исключён** из `gross` (не фабрикуется ноль) |

_Покрывает:_ R3.1, R3.3, R3.4.

### TC-API-SUG-02 — предложение учитывает остаток аванс-кредита (floored at 0)

**Предусловия:** у `WORKER_A` есть остаток аванс-кредита `1000.00` (создан авансом в TC-API-ADV-01);
gross по периоду = `3000.00`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `GET /api/worker-payouts/suggest?...workerId={WORKER_A_ID}&from=2027-01-01&to=2027-01-31` | `200`; `gross=3000.00`, `remainingCredit=1000.00`, `appliedCredit=1000.00`, `suggested=2000.00` |
| 2 | Проверить floor при кредите ≥ gross (напр. кредит `5000.00`, gross `3000.00`) | `appliedCredit=3000.00`, `suggested=0.00` (не отрицателен) |

_Покрывает:_ R3.1, R3.2.

---

## Фича 2. Создание обычной выплаты — линк строк + применение кредита (R4)

**Повторяемость:** новый проект/работник на прогон; `PLANNED`-выплаты удаляются в teardown.

### TC-API-REG-01 — Обычная выплата: PLANNED + линк строк + снапшот suggestedAmount

**Предусловия:** `PROJECT_ID`, `WORKER_A_ID`, gross по `[2027-01-01, 2027-01-31]` = `3000.00`,
остаток кредита = 0; дата создания такова, что `periodTo` **не** в будущем (обычная, не аванс).

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/worker-payouts?projectId={PROJECT_ID}` `{ "workerId": WORKER_A_ID, "periodFrom": "2027-01-01", "periodTo": "2027-01-31", "paymentChannel": "BANK_TRANSFER", "amount": 3000.00, "note": "pay-{run-id}-01" }` (Bearer `FINANCIER_TOKEN`) | `200/201`; `WorkerPayoutDto`: `status="PLANNED"`, `isAdvance=false`, `suggestedAmount=3000.00`, `advanceApplied=0.00`, `amount=3000.00`; сохранить `PAYOUT_ID` |
| 2 | `GET /api/worker-payouts/suggest?...from=2027-01-01&to=2027-01-31` снова | `200`; `gross=0.00` — ранее покрытые строки больше **не** предлагаются (их `paidByPayout` выставлен) |
| 3 | `GET /api/worker-payouts?projectId={PROJECT_ID}&workerId={WORKER_A_ID}` | `200`; выплата присутствует со `status="PLANNED"` |

_Покрывает:_ R4.1, R4.2, R6.2.

### TC-API-REG-02 — Применение аванс-кредита уменьшает suggested, строки линкуются полностью

**Предусловия:** у `WORKER_B` остаток аванс-кредита `1000.00`; gross по периоду = `3000.00`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `GET /api/worker-payouts/suggest?...workerId={WORKER_B_ID}&...` | `200`; `gross=3000.00`, `appliedCredit=1000.00`, `suggested=2000.00` |
| 2 | `POST /api/worker-payouts?projectId={PROJECT_ID}` `{ "workerId": WORKER_B_ID, ..., "amount": 2000.00 }` | `200/201`; `advanceApplied=1000.00`, `amount=2000.00`, `suggestedAmount=2000.00` |
| 3 | `GET /api/worker-payouts?projectId={PROJECT_ID}&workerId={WORKER_B_ID}` | `200`; остаток аванс-кредита работника уменьшился до `0.00` |
| 4 | Проверить линковку: `suggest` по периоду снова | все покрытые строки залинкованы (gross=0) — кредит уменьшил **платёж**, но строки залинкованы полностью (R5.6) |

_Покрывает:_ R4.2, R5.6, R6.2.

### TC-API-REG-03 — Override суммы не меняет линк строк и advanceApplied

**Предусловия:** `WORKER_A`, gross = `3000.00`, остаток кредита `1000.00` (suggested = `2000.00`).

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/worker-payouts?projectId={PROJECT_ID}` `{ ..., "amount": 500.00 }` (override ниже suggested) | `200/201`; `amount=500.00`, но `suggestedAmount=2000.00`, `advanceApplied=1000.00` (следуют gross, не override) |
| 2 | Проверить залинкованные строки | весь покрытый набор строк залинкован (не зависит от cash-amount) |

_Покрывает:_ R4.2, R4.4.

### TC-API-REG-04 — Валидации создания

**Предусловия:** `PROJECT_ID`, `WORKER_A_ID`, посторонний `NON_MEMBER_ID` (не участник).

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST ...` без `paymentChannel` | `422`, код `error.workerPayout.channel.required`; ничего не создано |
| 2 | `POST ...` `{ ..., "amount": -1 }` | `422`, код `error.workerPayout.amount.invalid`; ничего не создано |
| 3 | `POST ...` `{ ..., "periodFrom": "2027-02-01", "periodTo": "2027-01-01" }` | `422`, код `error.workerPayout.period.invalid`; ничего не создано |
| 4 | `POST ...` `{ "workerId": NON_MEMBER_ID, ... }` | `422`, код `error.workerPayout.worker.notMember`; ничего не создано |

_Покрывает:_ R4.1, R4.4.

---

## Фича 3. Создание аванса — добавление кредита (R5)

**Повторяемость:** новый проект/работник на прогон; teardown удаляет `PLANNED`-авансы (если кредит
не потреблён).

### TC-API-ADV-01 — Аванс: advanceApplied=0, строк не линкует, кредит += amount

**Предусловия:** `PROJECT_ID`, `WORKER_A_ID`; выбранный период **forward-looking** — `periodTo`
**позже** даты создания (напр. `[today, today+30d]`), поэтому `isAdvance=true`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `GET /api/worker-payouts/suggest?...workerId={WORKER_A_ID}&from={today}&to={today+30d}` | `200`; `isAdvance=true`; suggested от **планового** базиса (assigned items × planned qty); аванс-кредит **не** вычитается |
| 2 | `POST /api/worker-payouts?projectId={PROJECT_ID}` `{ "workerId": WORKER_A_ID, "periodFrom": "{today}", "periodTo": "{today+30d}", "paymentChannel": "CASH", "amount": 1000.00 }` | `200/201`; `isAdvance=true`, `advanceApplied=0.00`, `amount=1000.00`; строк отчётов **не** залинковано |
| 3 | `GET /api/worker-payouts?projectId={PROJECT_ID}&workerId={WORKER_A_ID}` | `200`; остаток аванс-кредита работника = `1000.00` (увеличен на сумму аванса) |

_Покрывает:_ R5.5, R10.1.

---

## Фича 4. Жизненный цикл: отметка оплаты и удаление (R7)

**Повторяемость:** новый проект/выплата на прогон; `PAID`-выплаты неудаляемы — teardown через
удаление/отмену проекта.

### TC-API-LC-01 — Mark paid: PLANNED → PAID, запись замораживается

**Предусловия:** `PLANNED`-выплата `PAYOUT_ID`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/worker-payouts/{PAYOUT_ID}/pay` (Bearer `MANAGER_TOKEN`) | `200`; `status="PAID"`, `paidAt` непустой |
| 2 | Повторно `POST /api/worker-payouts/{PAYOUT_ID}/pay` | `409`, код `error.workerPayout.status.illegalTransition`; ничего не изменилось |
| 3 | `DELETE /api/worker-payouts/{PAYOUT_ID}` | `409`, код `error.workerPayout.status.illegalTransition`; выплата не удалена |

_Покрывает:_ R7.1, R7.2.

### TC-API-LC-02 — Delete PLANNED: освобождение строк + разворот кредита

**Предусловия:** обычная `PLANNED`-выплата `PAYOUT_ID`, залинковавшая строки `WORKER_A` на gross
`3000.00`, с `advanceApplied=1000.00` (остаток кредита перед созданием был `1000.00`, после — `0`).

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `GET /api/worker-payouts/suggest?...` до удаления | `gross=0.00` (строки залинкованы); остаток кредита работника `0.00` |
| 2 | `DELETE /api/worker-payouts/{PAYOUT_ID}` (Bearer `FINANCIER_TOKEN`) | `200/204`; выплата hard-deleted |
| 3 | `GET /api/worker-payouts/suggest?...` после удаления | `gross=3000.00` — строки снова payable (`paidByPayout` очищен) |
| 4 | `GET /api/worker-payouts?projectId={PROJECT_ID}&workerId={WORKER_A_ID}` | остаток аванс-кредита восстановлен до `1000.00` (разворот `advanceApplied`) |

_Покрывает:_ R6.3, R7.3, R5.6.

### TC-API-LC-03 — Delete аванса с уже потреблённым кредитом → 409

**Предусловия:** аванс `ADVANCE_ID` добавил кредит `1000.00`; затем создана **обычная** выплата,
потребившая этот кредит (`advanceApplied=1000.00`), поэтому остаток = `0`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `DELETE /api/worker-payouts/{ADVANCE_ID}` (Bearer `MANAGER_TOKEN`) | `409`, код `error.workerPayout.advance.alreadyApplied`; аванс не удалён, баланс не изменён |
| 2 | Удалить сначала потребившую обычную выплату, затем аванс | обычная: `200/204` (кредит возвращён в баланс); аванс: `200/204` (теперь кредит не потреблён, удаление разрешено) |

_Покрывает:_ R5.7, R7.3.

---

## Фича 5. Защита от двойной оплаты (R6)

**Повторяемость:** новый проект/работник на прогон; teardown общий.

### TC-API-DBL-01 — Залинкованная строка не предлагается повторно

**Предусловия:** `WORKER_A` с `APPROVED` строками на `3000.00` в `[2027-01-01, 2027-01-31]`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/worker-payouts?projectId={PROJECT_ID}` обычная выплата на период | `200/201`; строки залинкованы (`paidByPayout` выставлен) |
| 2 | `GET /api/worker-payouts/suggest?...from=2027-01-01&to=2027-01-31` | `200`; `gross=0.00`, `breakdown` не содержит уже оплаченных строк |
| 3 | Создать для `WORKER_A` **новую** `APPROVED` строку в том же периоде, повторить `suggest` | только новая строка в `breakdown`; ранее залинкованные по-прежнему исключены |

_Покрывает:_ R6.2, R3.1.

---

## Фича 6. ABAC-матрица ресурса PAYROLL (R10.4)

**Повторяемость:** читает сид-матрицу; teardown общий.

### TC-API-ABAC-01 — ADMIN / MANAGER / FINANCIER — полный доступ

**Предусловия:** `PROJECT_ID`, участники-роли приглашены (Setup).

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/worker-payouts?projectId={PROJECT_ID}` (Bearer `MANAGER_TOKEN`) | `200/201` (CREATE разрешён) |
| 2 | `POST /api/worker-payouts/{id}/pay` (Bearer `FINANCIER_TOKEN`) | `200` (UPDATE разрешён) |
| 3 | `DELETE /api/worker-payouts/{id2}` (Bearer `ADMIN_TOKEN`) | `200/204` (DELETE разрешён) |

_Покрывает:_ R10.4.

### TC-API-ABAC-02 — FOREMAN — только чтение

**Предусловия:** `FOREMAN_TOKEN` (участник проекта), `PLANNED`-выплата `PAYOUT_ID`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `GET /api/worker-payouts?projectId={PROJECT_ID}` (Bearer `FOREMAN_TOKEN`) | `200` (READ разрешён) |
| 2 | `POST /api/worker-payouts?projectId={PROJECT_ID}` (Bearer `FOREMAN_TOKEN`) | `403` (нет CREATE-гранта) |
| 3 | `POST /api/worker-payouts/{PAYOUT_ID}/pay` (Bearer `FOREMAN_TOKEN`) | `403` (нет UPDATE-гранта) |
| 4 | `DELETE /api/worker-payouts/{PAYOUT_ID}` (Bearer `FOREMAN_TOKEN`) | `403` (нет DELETE-гранта) |

_Покрывает:_ R10.4, R7.4.

### TC-API-ABAC-03 — CLIENT / WORKER — запрещено (deny-by-default)

**Предусловия:** `CLIENT_TOKEN` (участник), токен рядового WORKER `WORKER_TOKEN`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `GET /api/worker-payouts?projectId={PROJECT_ID}` (Bearer `CLIENT_TOKEN`) | `403` (нет гранта у CLIENT) |
| 2 | `GET /api/worker-payouts?projectId={PROJECT_ID}` (Bearer `WORKER_TOKEN`) | `403` (нет гранта у WORKER) |
| 3 | `POST /api/worker-payouts?projectId={PROJECT_ID}` (Bearer `WORKER_TOKEN`) | `403` |

_Покрывает:_ R10.4.

---

## Фича 7. Project-scope gate (R10.5)

**Повторяемость:** два проекта на прогон; teardown — оба.

### TC-API-SCOPE-01 — Чужой проект не виден в списке и недоступен для записи

**Предусловия:** `PROJECT_ID` (участник — `MANAGER`), второй проект `OTHER_PROJECT_ID`, в котором
`MANAGER` **не** участник, c собственной `PLANNED`-выплатой `OTHER_PAYOUT_ID`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `GET /api/worker-payouts?projectId={OTHER_PROJECT_ID}` (Bearer `MANAGER_TOKEN`) | `404` (проект вне scope; неразличимо с «не найдено») |
| 2 | `GET /api/worker-payouts?projectId={PROJECT_ID}` (Bearer `MANAGER_TOKEN`) | `200`; в списке **нет** выплат `OTHER_PROJECT_ID` |
| 3 | `POST /api/worker-payouts/{OTHER_PAYOUT_ID}/pay` (Bearer `MANAGER_TOKEN`) | `404` (запись вне resolved-проекта запрещена) |
| 4 | `DELETE /api/worker-payouts/{OTHER_PAYOUT_ID}` (Bearer `MANAGER_TOKEN`) | `404` |

_Покрывает:_ R10.5.

---

# Регрессия

**Повторяемость:** каждый кейс самодостаточен, сущности помечены `run-id`; teardown общий.

### REG-01 — Освобождение строк при удалении `PLANNED`-выплаты (round-trip линковки)

**Предусловия:** `WORKER_A` с `APPROVED` строками на `3000.00` в периоде, остаток кредита `0`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | Снять снапшот: `suggest` → `gross=3000.00`, строки payable | зафиксировано |
| 2 | `POST /api/worker-payouts?projectId={PROJECT_ID}` обычная выплата | строки залинкованы, `suggest` → `gross=0.00` |
| 3 | `DELETE /api/worker-payouts/{id}` (`PLANNED`) | `200/204` |
| 4 | `suggest` снова | `gross=3000.00` — состояние строк **идентично** снапшоту шага 1 (create→delete = identity на `paidByPayout`) |

_Покрывает:_ R6.3, R7.3 (Property 4 LinkageExactReversible).

### REG-02 — Running-balance аванс-кредита (аванс ↑, обычная ↓, никогда < 0)

**Предусловия:** `WORKER_B` на чистом проекте, остаток кредита `0`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | Создать аванс `amount=1000.00` | остаток кредита → `1000.00` |
| 2 | Создать аванс `amount=500.00` | остаток кредита → `1500.00` (Σ авансов) |
| 3 | Создать обычную выплату, gross `3000.00` | `appliedCredit=1500.00`, `suggested=1500.00`; остаток кредита → `0.00` (Σ авансов − Σ advanceApplied) |
| 4 | Создать обычную выплату при gross `500.00`, остаток кредита `0` | `appliedCredit=0.00`, `suggested=500.00`; остаток остаётся `0.00` (не отрицателен) |
| 5 | Удалить `PLANNED`-обычную выплату шага 3 | остаток кредита возвращается к `1500.00` (разворот `advanceApplied`) |

_Покрывает:_ R5.5, R5.6, R5.7 (Property 3 AdvanceCreditBalance).

### REG-03 — Сквозной путь: suggest → regular → advance → mark-paid → delete

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `suggest` для `WORKER_A` по прошедшему периоду | `gross` корректен, `isAdvance=false` |
| 2 | `POST` обычная выплата | `PLANNED`, строки залинкованы, `advanceApplied` по кредиту |
| 3 | `POST` аванс (forward-looking период) для `WORKER_A` | `isAdvance=true`, кредит += amount, строк не линкует |
| 4 | `POST /{regularId}/pay` | обычная → `PAID`, заморожена |
| 5 | `DELETE /{advanceId}` (`PLANNED`, кредит не потреблён) | `200/204`, кредит возвращён |
| 6 | `GET /api/worker-payouts?projectId={PROJECT_ID}` | агрегаты (список, статусы, остаток кредита по работникам) корректны |

_Покрывает:_ R3.1, R4.1, R4.2, R5.5, R7.1, R7.3 (критичный сквозной путь).

---

# Шаблон MD-репорта прогона

Заполняется по итогам каждого прогона.

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-API-SUG-01 | 1 | GET /api/worker-payouts/suggest | 200 + gross=3000.00, suggested=3000.00 | 200 | ✅ |
| 2 | TC-API-REG-01 | 1 | POST /api/worker-payouts | 200 + PLANNED, suggestedAmount=3000.00 | — | ⬜ |
| 3 | TC-API-LC-02 | 3 | GET /suggest после delete | gross=3000.00 (строки снова payable) | — | ⬜ |
| … | … | … | … | … | … | … |

**Итог:** X пройдено / Y провалено / Z пропущено.
**Run-id:** `<timestamp/uuid>`.
**Стратегия повторяемости:** генератор `run-id` + новый проект/работник на прогон + teardown
(удаление `PLANNED`-выплат → `CANCELLED`/удаление проекта, каскад FK; `PAID`-выплаты неудаляемы).
**Окружение:** `docker compose up`, backend `http://localhost:8080`; auth под `/api/auth`;
первый ADMIN через `FOREMEN_ADMIN_CREATE/EMAIL/PASSWORD`.
**Легенда статусов:** ✅ пройден · ❌ провален · ⬜ не запускался · ⚠️ пройден с оговоркой (приложить ссылку/комментарий).
