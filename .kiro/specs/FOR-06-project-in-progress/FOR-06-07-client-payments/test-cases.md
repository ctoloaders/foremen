# Тест-кейсы: FOR-06-07 — Client payments (клиентские платежи)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерные экраны**, поэтому набор тест-кейсов включает **оба типа**:

- **Часть A. API-тесты** — против поднятого в Docker приложения (`http://localhost:8080`). Каждый
  шаг — HTTP-запрос к платёжному API под `/api/payments...`:
  - жизненный цикл графика: `POST /api/payments/schedules`,
    `POST /api/payments/schedules/{id}/generate`, `POST /api/payments/schedules/{id}/publish`,
    `POST /api/payments/schedules/{id}/re-plan`;
  - ручной планировщик (DRAFT): `POST .../tranches`, `POST .../tranches/{trancheId}`,
    `POST .../tranches/{trancheId}/remove`, `POST .../reorder`;
  - add-tranche-anytime (в т.ч. PUBLISHED): `POST .../add-tranche`;
  - сверка/оплата: `POST /api/payments/schedules/{id}/transactions` (MANUAL-запись),
    `POST /api/payments/tranches/{trancheId}/mark-paid`;
  - чтение: `GET /api/payments/schedules/{id}`, `GET /api/payments/projects/{projectId}/schedule`,
    `GET /api/payments/schedules/{id}/transactions`;
  - внешние каналы: публичный webhook `POST /api/payments/webhook/online` и внутренний ingest
    `POST /api/payments/bank-feed/ingest`.
  Проверяются статус, тело и заголовки ответа.
- **Часть B. Браузерные (UI) сценарии** — против фронтенда (`http://localhost:3000`), исполняются
  браузерным движком (headless-браузер / browser automation) под ролями ADMIN / MANAGER (запись) и
  FOREMAN (read-only). Экран: вкладка **Płatności** (`payments`) воркспейса проекта — шапка графика
  (basis, Σ оплачено, остаток, неразмещённый кредит, производный статус), форма генератора и панель
  ручного планировщика, кнопки publish/re-plan, список транзакций и действие mark-as-paid, локализация
  enum-меток (PL/RU).

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание →
факт → статус). **Скриншоты не требуются.**

### Покрытие требований

Каждый из основных критериев R1–R13 покрыт минимум одним кейсом; R14 (Non-Goals) проверяется
косвенно — No-Op провайдеры ничего не подтверждают (TC-API-WH-02, TC-API-BF-01). Соответствие
«кейс ↔ требование» указано в каждом кейсе строкой `_Покрывает:_`.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции,
   включая changesets `171`–`175` этой спеки), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN — через переменные окружения `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`,
   `FOREMEN_ADMIN_PASSWORD`. Логин: `POST /api/auth/login {email, password}` → `accessToken`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном (проверка готовности ABAC:
   `GET /api/payments/projects/{projectId}/schedule` под ADMIN на существующем проекте без графика
   отвечает `204 No Content`, а не 401/403/500).
5. **No-Op провайдеры активны по умолчанию** (`foremen.payments.online.provider=none`,
   `foremen.payments.bankfeed.provider=none`): webhook не верифицируется, bank-feed пуст. Кейсы,
   завязанные на эти значения, помечены маркером **[no-op]**.

## Общая стратегия повторяемости

**Комбинированная: генератор `run-id` + teardown.** Единый на прогон `run-id` (timestamp или uuid,
например `1730000000` или `a1b2c3`) встраивается во все создаваемые идентификаторы, чтобы два
последовательных прогона на одной БД не конфликтовали:

- внешние ссылки транзакций (`externalRef`): `ext-{run-id}-01`, `ext-{run-id}-02`, …;
- названия проектов/клиентов/офферов, создаваемых в Setup: `Pay Test {run-id}`,
  `Client {run-id}`, `client+{run-id}@example.com`.

**Ограничение домена: один график на проект** (UNIQUE `project_id`, R1.1). Поэтому повторные прогоны
**не** могут переиспользовать один и тот же проект — каждая группа, создающая график, создаёт **новый
проект на прогон** через admin-API и доводит его до стадии с резолвимым total (APPROVED-оффер /
`OFFER_BASE`). Это и есть генератор-повторяемость для графиков.

**Teardown каждой группы** (если не указано иное) выполняется в конце набора в обратном порядке
создания: перевод тестовых проектов в `CANCELLED` либо удаление доступным admin-API (каскад удаляет
график → транши → транзакции → аллокации по FK `ON DELETE CASCADE`), затем деактивация/удаление
созданных пользователей. **Сид-данные** (роли, ресурс `PAYMENTS`, матрица из changeset 175) —
**только читаются, никогда не изменяются**. Каждая группа ниже дополнительно уточняет стратегию
строкой `**Повторяемость:**`.

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Там, где кейс требует роли MANAGER / FINANCIER / FOREMAN / WORKER / CLIENT,
тестовые пользователи создаются и приглашаются в проект как `ProjectMember` соответствующей роли через
admin-API в группе `Setup`. Матрица ресурса `PAYMENTS` (changeset 175, R11.2):

| Ресурс | ADMIN | MANAGER | FOREMAN | WORKER | FINANCIER | CLIENT |
|--------|-------|---------|---------|--------|-----------|--------|
| `PAYMENTS` | CRUD | CRUD(own) | R(own) | — | CRUD(own) | R(own) |

`(own)` = доступ только к проектам, в которых пользователь состоит участником (enforced
`ProjectScopedService`). Для чужого/несуществующего графика ожидается `404 error.entity.not.found`
(неразличимо с «не найдено»).

### Эталонный набор enum (для проверки локализации, R13.2)

- `ScheduleStatus`: `DRAFT`, `PUBLISHED`, `SETTLED` → `payments.scheduleStatus.*`
- `TrancheStatus`: `PENDING`, `DUE`, `PARTIALLY_PAID`, `PAID`, `OVERDUE` → `payments.trancheStatus.*`
- `TransactionStatus`: `PENDING`, `CONFIRMED`, `FAILED`, `REFUNDED` → `payments.transactionStatus.*`
- `PaymentChannel`: `BANK_TRANSFER`, `ONLINE`, `MANUAL` → `payments.channel.*`
- `GeneratorStrategy`: `EQUAL`, `PERCENT`, `CUSTOM` → `payments.generator.strategy.*`
- `Periodicity`: `WEEKLY`, `BIWEEKLY`, `MONTHLY`, `CUSTOM_DATES` → `payments.generator.periodicity.*`

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Setup — токены, проект и резолвимый total

**Повторяемость:** читает сид + создаёт помеченные `run-id` сущности; teardown в конце набора.

### TC-API-SETUP-01 — Логин ADMIN

**Предусловия:** стек поднят, `FOREMEN_ADMIN_*` заданы.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/auth/login` c телом `{ "email": "<FOREMEN_ADMIN_EMAIL>", "password": "<FOREMEN_ADMIN_PASSWORD>" }` | `200`, тело содержит непустой `accessToken` (сохранить как `ADMIN_TOKEN`) |

_Покрывает:_ предусловие для всех API-кейсов.

### TC-API-SETUP-02 — Проект с резолвимым total

**Предусловия:** `ADMIN_TOKEN` получен.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | Создать проект `Pay Test {run-id}` через admin-API, перевести его в стадию `ACTIVE` | `2xx`; сохранить `PROJECT_ID` |
| 2 | Обеспечить у проекта **APPROVED-оффер / `OFFER_BASE`** (например `100000.00`) по флоу FOR-05-07/14 | `2xx`; `basisGross` будет сидиться из этого total |
| 3 | Пригласить тестовых участников: MANAGER, FINANCIER, FOREMAN (`ProjectMember`) | `2xx`; сохранить токены `MANAGER_TOKEN`, `FINANCIER_TOKEN`, `FOREMAN_TOKEN` |

_Покрывает:_ R1.2 (источник total), предусловие для групп 1–7.

---

## Фича 1. Создание и чтение графика (R1)

**Повторяемость:** новый проект на прогон (TC-API-SETUP-02); teardown — `CANCELLED`/удаление проекта.

### TC-API-SCH-01 — Создание графика сидит basisGross из APPROVED-оффера

**Предусловия:** `PROJECT_ID` с резолвимым total; `ADMIN_TOKEN`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/payments/schedules` `{ "projectId": PROJECT_ID, "source": "GENERATED" }` (Bearer `ADMIN_TOKEN`) | `200`; тело `PaymentScheduleView`: `status = "DRAFT"`, `basisGross = 100000.00`, `currency` из проекта, `source = "GENERATED"`, `tranches = []` |
| 2 | Сохранить `SCHEDULE_ID` из ответа | — |
| 3 | `GET /api/payments/schedules/{SCHEDULE_ID}` | `200`; `sumAmountGross = 0`, `sumPaidGross = 0`, `unpaidRemainder = 100000.00`, `unallocatedCredit = 0` |

_Покрывает:_ R1.1, R1.2, R1.4.

### TC-API-SCH-02 — Повторное создание графика запрещено (UNIQUE project)

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/payments/schedules` `{ "projectId": PROJECT_ID }` ещё раз | `409`, тело содержит код `error.payment.schedule.exists` |

_Покрывает:_ R1.1.

### TC-API-SCH-03 — Создание без резолвимого total отклоняется

**Предусловия:** отдельный проект `PROJECT_NO_TOTAL` **без** APPROVED-оффера.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/payments/schedules` `{ "projectId": PROJECT_NO_TOTAL }` | `422`, код `error.payment.schedule.noProjectTotal`; график не создан |
| 2 | `GET /api/payments/projects/{PROJECT_NO_TOTAL}/schedule` | `204 No Content` (графика нет) |

_Покрывает:_ R1.3.

### TC-API-SCH-04 — Чтение графика по проекту

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `GET /api/payments/projects/{PROJECT_ID}/schedule` | `200`; `id = SCHEDULE_ID` |
| 2 | На проекте без графика | `204 No Content` |

_Покрывает:_ R1.4, R7.6 (reusable read-action).

---

## Фича 2. Генератор траншей (R2)

**Повторяемость:** генератор заменяет все транши DRAFT-графика; teardown общий.

### TC-API-GEN-01 — EQUAL: сумма точна, остаток на последнем транше

**Предусловия:** DRAFT `SCHEDULE_ID`, `basisGross = 100000.00`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/payments/schedules/{SCHEDULE_ID}/generate` `{ "strategy": "EQUAL", "count": 3, "startDate": "2027-01-01", "periodicity": "MONTHLY" }` | `200`; 3 транша; `seqNo` = 1,2,3; `dueDate` 2027-01-01 / 02-01 / 03-01 |
| 2 | Проверить суммы: `33333.33 + 33333.33 + 33333.34` | `Σ amountGross = 100000.00` ровно; каждая сумма ≤ 2 знаков |
| 3 | `GET /api/payments/schedules/{SCHEDULE_ID}` | `sumAmountGross = 100000.00`, статусы траншей `PENDING` |

_Покрывает:_ R2.1, R2.2, R2.6.

### TC-API-GEN-02 — PERCENT: сумма процентов ≠ 100 отклоняется

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `generate` `{ "strategy": "PERCENT", "parts": [30, 30, 30], "startDate": "2027-01-01", "periodicity": "MONTHLY" }` | `422`, код `error.payment.generator.percentSum` |
| 2 | `generate` `{ "strategy": "PERCENT", "parts": [50, 30, 20], ... }` | `200`; суммы `50000.00 / 30000.00 / 20000.00`, `Σ = 100000.00` |

_Покрывает:_ R2.3.

### TC-API-GEN-03 — CUSTOM: сумма сумм ≠ basis отклоняется

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `generate` `{ "strategy": "CUSTOM", "parts": [40000, 40000], ... }` (Σ = 80000 ≠ 100000) | `422`, код `error.payment.generator.amountSum` |
| 2 | `generate` `{ "strategy": "CUSTOM", "parts": [40000, 60000], ... }` | `200`; суммы сохранены, `Σ = 100000.00` |

_Покрывает:_ R2.4.

### TC-API-GEN-04 — Невалидный ввод генератора

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `generate` `{ "strategy": "EQUAL", "count": 0, "startDate": "2027-01-01", "periodicity": "MONTHLY" }` | `422`, код `error.payment.generator.invalid` |
| 2 | `generate` `{ "strategy": "EQUAL", "count": 3, "periodicity": "MONTHLY" }` (нет `startDate`) | `422`, код `error.payment.generator.invalid` |

_Покрывает:_ R2.5.

---

## Фича 3. Ручной планировщик + add-tranche-anytime (R3)

**Повторяемость:** отдельный DRAFT-график (новый проект) для DRAFT-правок; teardown общий.

### TC-API-MAN-01 — Добавление/правка/удаление/реордер транша в DRAFT

**Предусловия:** DRAFT `SCHEDULE_ID` (пустой или с траншами), `MANAGER_TOKEN`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST .../schedules/{id}/tranches` `{ "amountGross": 60000, "dueDate": "2027-03-01" }` | `200`; добавлен транш |
| 2 | `POST .../schedules/{id}/tranches` `{ "amountGross": 40000, "dueDate": "2027-01-01" }` | `200`; `seqNo` пересчитан по `dueDate`: 01-01 → seq 1, 03-01 → seq 2 |
| 3 | `POST .../schedules/{id}/tranches/{trancheId}` `{ "amountGross": 50000, "dueDate": "2027-02-01" }` (правка) | `200`; транш обновлён, `seqNo` пересчитан contiguous 1..N |
| 4 | `POST .../schedules/{id}/tranches/{trancheId}/remove` | `200`; транш удалён, `seqNo` остаётся contiguous 1..N |

_Покрывает:_ R3.1, R3.2.

### TC-API-MAN-02 — Невалидный транш отклоняется

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST .../tranches` `{ "amountGross": -100, "dueDate": "2027-01-01" }` | `422`, код `error.payment.tranche.invalid` |
| 2 | `POST .../tranches` `{ "amountGross": 100 }` (нет `dueDate`) | `422`, код `error.payment.tranche.invalid` |

_Покрывает:_ R3.6.

### TC-API-MAN-03 — add-tranche-anytime на PUBLISHED поднимает basisGross

**Предусловия:** PUBLISHED `SCHEDULE_ID`, `basisGross = 100000.00`, Σ траншей = 100000.00.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/payments/schedules/{id}/add-tranche` `{ "amountGross": 20000, "dueDate": "2027-06-01" }` | `200`; схема остаётся `PUBLISHED` |
| 2 | `GET .../schedules/{id}` | `basisGross = 120000.00`; `Σ amountGross = 120000.00` (инвариант держится); новый транш получил `seqNo` по `dueDate` |
| 3 | Проверить, что PAID-транши (если были) не затронуты | их `amountGross`/`paidGross`/`seqNo` и аллокации неизменны |

_Покрывает:_ R3.3, R3.4.

### TC-API-MAN-04 — FOREMAN не может писать (ABAC)

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST .../schedules/{id}/add-tranche` (Bearer `FOREMAN_TOKEN`) | `403` (нет UPDATE-гранта у FOREMAN) |

_Покрывает:_ R3.7, R11.2.

---

## Фича 4. Публикация графика (R4)

**Повторяемость:** общий teardown.

### TC-API-PUB-01 — Публикация сверяющегося графика

**Предусловия:** DRAFT `SCHEDULE_ID`, Σ траншей = `basisGross`, ≥1 транш.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/payments/schedules/{id}/publish` | `200`; `status = "PUBLISHED"` |
| 2 | (опц.) проверить эмиссию `notification.payment.schedulePublished` для CLIENT в Bell | уведомление с deep-link присутствует |

_Покрывает:_ R4.1, R4.3.

### TC-API-PUB-02 — Публикация при несведённой сумме / нуле траншей отклоняется

**Предусловия:** DRAFT-график, где `Σ amountGross ≠ basisGross`.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST .../publish` при Σ ≠ basis | `422`, код `error.payment.schedule.sumMismatch` |
| 2 | `POST .../publish` на графике без траншей | `422`, код `error.payment.schedule.sumMismatch` |

_Покрывает:_ R3.5, R4.2.

---

## Фича 5. Сверка входящих платежей + ручная оплата транша (R5)

**Повторяемость:** `externalRef` помечены `run-id`; новый проект/график на прогон; teardown общий.

### TC-API-REC-01 — MANUAL-платёж = остаток ближайшего транша → PAID

**Предусловия:** PUBLISHED `SCHEDULE_ID` с траншами `T1=40000`, `T2=60000` (все open).

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/payments/schedules/{id}/transactions` `{ "amountGross": 40000, "feeGross": 0, "receivedAt": "2027-01-05T10:00:00Z", "note": "cash" }` | `200`; `PaymentTransactionDto`: `channel = "MANUAL"`, `status = "CONFIRMED"`, 1 аллокация на `T1` суммой 40000 |
| 2 | `GET .../schedules/{id}` | `T1.status = "PAID"`, `T1.paidGross = 40000`; `T2.status` open; `sumPaidGross = 40000` |

_Покрывает:_ R5.1, R5.3, R8.5.

### TC-API-REC-02 — Недоплата → PARTIALLY_PAID

**Предусловия:** PUBLISHED график, `T1=40000` open.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST .../transactions` `{ "amountGross": 15000, ... }` | `200`; аллокация 15000 на `T1` |
| 2 | `GET .../schedules/{id}` | `T1.status = "PARTIALLY_PAID"`, `T1.paidGross = 15000` |

_Покрывает:_ R5.2.

### TC-API-REC-03 — Переплата → каскад + неразмещённый кредит

**Предусловия:** PUBLISHED график, `T1=40000`, `T2=60000` open, всего два транша.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST .../transactions` `{ "amountGross": 110000, ... }` | `200`; аллокации: 40000→T1, 60000→T2 |
| 2 | `GET .../schedules/{id}` | `T1 = PAID`, `T2 = PAID`, `status = "SETTLED"`; `unallocatedCredit = 10000` (излишек после последнего транша, фантомных траншей нет) |

_Покрывает:_ R5.4, R5.5, R5.6.

### TC-API-REC-04 — feeGross записывается, не вычитается из аллокации

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST .../transactions` `{ "amountGross": 40000, "feeGross": 500, ... }` на `T1=40000` | `200`; транзакция `feeGross = 500`; аллокация на `T1` = 40000 (не 39500) |
| 2 | `GET .../schedules/{id}` | `T1 = PAID`, `paidGross = 40000` |

_Покрывает:_ R7.4 (Payer_Borne_Fee).

### TC-API-REC-05 — mark-tranche-paid адресно и идемпотентно

**Предусловия:** PUBLISHED график, `T1=40000` open, `T2=60000` open.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/payments/tranches/{T2_ID}/mark-paid` `{ "note": "waiver" }` | `200`; оплачен **именно `T2`** (не ближайший open `T1`): `T2 = PAID`, `paidGross = 60000`; записана MANUAL/CONFIRMED-транзакция на остаток `T2` |
| 2 | Повторить `POST .../tranches/{T2_ID}/mark-paid` | `200`; **no-op** — вторая транзакция не создана, аллокации не задвоены |
| 3 | `GET .../schedules/{id}/transactions` | по `T2` ровно одна MANUAL-транзакция |

_Покрывает:_ R5.9, R5.10, R5.11, R5.12.

### TC-API-REC-06 — Чтение транзакций с аллокациями

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `GET /api/payments/schedules/{id}/transactions` | `200`; список (oldest-first) `PaymentTransactionDto`, у каждого — массив `allocations` c `{ trancheId, amount }` |

_Покрывает:_ R5.7, R12.2.

---

## Фича 6. Re-plan остатка после изменения total (R6)

**Повторяемость:** новый проект/график на прогон; teardown общий.

### TC-API-REPLAN-01 — Re-plan переавторизует только open-транши

**Предусловия:** PUBLISHED график, `basisGross = 100000`, `T1=40000` **PAID**, `T2=60000` open.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/payments/schedules/{id}/re-plan` `{ "newBasisGross": 120000 }` | `200`; `status` остаётся `"PUBLISHED"`, `basisGross = 120000` |
| 2 | `GET .../schedules/{id}` | `T1` (PAID) **неизменен** (amount 40000, paidGross 40000, seqNo, аллокации); open-транши пересуммированы до остатка `120000 − 40000 = 80000` |

_Покрывает:_ R6.1, R6.2, R6.4.

### TC-API-REPLAN-02 — Отрицательный остаток → обнуление open + кредит

**Предусловия:** PUBLISHED график, `basisGross = 100000`, оплачено 90000 (PAID-транши), open = 10000.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST .../re-plan` `{ "newBasisGross": 80000 }` (остаток `80000 − 90000 = −10000`) | `200`; open-транши обнулены/закрыты; излишек `10000` вынесен в `unallocatedCredit`; `status = "PUBLISHED"` |

_Покрывает:_ R6.3.

---

## Фича 7. Внешние каналы: webhook + bank-feed (R7, R8)

**Повторяемость:** `[no-op]` — провайдеры ничего не меняют; teardown не требуется.

### TC-API-WH-01 — Неверифицированный webhook отклоняется [no-op]

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/payments/webhook/online` с произвольным телом (без валидной подписи) | `401`, код `error.payment.webhook.unverified` (No-Op `verifySignature` = false) |

_Покрывает:_ R7.5, R14.1.

### TC-API-WH-02 — initiate online под No-Op возвращает disabled [no-op]

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/payments/online/initiate` `{ ... }` (Bearer `MANAGER_TOKEN`) | `200`; `OnlineInitiateResult` помечен disabled; ни одна транзакция не переведена в `CONFIRMED` |

_Покрывает:_ R7.1, R7.2, R7.6.

### TC-API-BF-01 — bank-feed ingest под No-Op безвреден [no-op]

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `POST /api/payments/bank-feed/ingest` (Bearer `ADMIN_TOKEN`) | `200`; `{ "polled": 0 }`; состояние графиков неизменно |
| 2 | `POST /api/payments/bank-feed/ingest` (Bearer `FOREMAN_TOKEN`) | `403` (ingest = UPDATE, FOREMAN без гранта) |

_Покрывает:_ R8.1, R8.2, R8.3, R11.4.

---

## Фича 8. ABAC и project-scope (R11)

**Повторяемость:** читает сид-матрицу; teardown общий.

### TC-API-ABAC-01 — Чужой/несуществующий график → 404

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `GET /api/payments/schedules/{SCHEDULE_ID}` под `MANAGER_TOKEN`, где менеджер **не** участник проекта | `404`, код `error.entity.not.found` |
| 2 | `GET /api/payments/schedules/{SCHEDULE_ID}` под участником-MANAGER того же проекта | `200` |

_Покрывает:_ R11.1, R11.3.

### TC-API-ABAC-02 — FOREMAN read-only

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | `GET /api/payments/schedules/{id}` (Bearer `FOREMAN_TOKEN`, участник) | `200` (R(own)) |
| 2 | `POST /api/payments/schedules/{id}/publish` (Bearer `FOREMAN_TOKEN`) | `403` |

_Покрывает:_ R11.2.

---

# Часть B. Браузерные (UI) сценарии — вкладка Płatności

Исполняются браузерным движком против `http://localhost:3000`. Логин через UI-форму под нужной ролью;
переход на `/projects/{PROJECT_ID}/payments`.

**Повторяемость:** переиспользует проект/график, созданные в Setup Части A на том же `run-id` (либо
создаёт свои через UI); teardown — общий с Частью A.

### TC-UI-01 — Шапка графика и таблица траншей

**Предусловия:** PUBLISHED график с траншами и хотя бы одной оплатой; логин MANAGER.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | Открыть вкладку **Płatności** проекта | рендерится реальная панель (не placeholder) |
| 2 | Проверить шапку | видны basis, Σ оплачено, остаток, неразмещённый кредит, производный статус графика |
| 3 | Проверить таблицу траншей | строки с `seqNo`, суммой, due date и **меткой статуса** (не сырой enum) |

_Покрывает:_ R12.1, R12.2.

### TC-UI-02 — Генератор с клиентской валидацией сумм

**Предусловия:** DRAFT график; логин MANAGER.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | Открыть форму генератора, выбрать `PERCENT`, ввести проценты `30/30/30` | клиентская валидация подсвечивает ошибку «сумма ≠ 100», submit заблокирован |
| 2 | Исправить на `50/30/20`, задать startDate + periodicity, submit | график перегенерирован, Σ = basis, таблица траншей обновлена |

_Покрывает:_ R2.2 (клиентская проверка), R12.2.

### TC-UI-03 — Ручной планировщик + publish + re-plan

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | В DRAFT добавить/отредактировать/удалить транш через панель ручного планировщика | `seqNo` пересчитывается, Σ отображается |
| 2 | Нажать Publish при сведённой сумме | статус графика → `PUBLISHED` (метка локализована) |
| 3 | На PUBLISHED открыть диалог Re-plan, ввести новый total | open-транши пересуммированы, PAID-транши неизменны |

_Покрывает:_ R3.1, R4.1, R6.1, R12.2.

### TC-UI-04 — Транзакции и mark-as-paid

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | Открыть список транзакций | строки транзакций с per-tranche аллокациями; непривязанные (unmatched) выделены |
| 2 | Нажать MANUAL-record, ввести сумму | транзакция создана, транш(и) сверены, статусы обновлены |
| 3 | Нажать mark-as-paid у open-транша | транш → `PAID`, запись MANUAL-транзакции появилась в списке |

_Покрывает:_ R5.9, R8.4, R12.2.

### TC-UI-05 — FOREMAN read-only

**Предусловия:** логин FOREMAN (участник проекта).

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | Открыть вкладку Płatności | график виден (read) |
| 2 | Проверить управляющие элементы | кнопки generate/publish/re-plan/add-tranche/mark-as-paid/record **скрыты или disabled** |

_Покрывает:_ R11.2, R12.3, R12.4.

### TC-UI-06 — Локализация enum-меток (PL/RU)

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | В UI (язык PL) проверить метки статусов траншей/графика/транзакций, каналов, стратегий, периодичности | отображаются локализованные строки из `payments.*`, **ни одной сырой enum-константы** |
| 2 | Переключить язык на RU, повторить | все те же метки локализованы на RU (паритет PL/RU) |

_Покрывает:_ R13.1, R13.2, R13.4.

---

# Регрессия

**Повторяемость:** каждый кейс самодостаточен, `externalRef` помечены `run-id`; teardown общий.

### REG-01 — Идемпотентность по дублю externalRef

**Предусловия:** стенд с активным bank-feed/online-провайдером **или** эквивалентный путь ingest,
позволяющий подать транзакцию с заданным `externalRef` (на No-Op проверяется на уровне интеграционных
тестов спеки — Property 4 / `PaymentTransactionService`). Документируемое ожидание:

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | Подать CONFIRMED-транзакцию с `externalRef = ext-{run-id}-dup` на `SCHEDULE_ID` | создана одна транзакция, выполнена аллокация |
| 2 | Повторно подать ту же `(schedule, channel, externalRef)` | **no-op**: вторая строка не создана, аллокация не задвоена, `paidGross`/`unallocatedCredit` идентичны состоянию после шага 1 |

_Покрывает:_ R5.8, R8.3 (Property 4 AllocationIdempotency).

### REG-02 — Re-plan не трогает PAID-транши и аллокации

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | Снять снапшот PAID-траншей и их `TrancheAllocation` до re-plan | зафиксированы amount/paidGross/seqNo/аллокации |
| 2 | `POST .../re-plan` c новым total | `200` |
| 3 | Сравнить PAID-транши и их аллокации со снапшотом | **полностью неизменны**; изменены только open-транши; `status = PUBLISHED` |

_Покрывает:_ R6.1, R6.2 (Property 6 RePlanImmutability).

### REG-03 — Overdue-уведомление ровно один раз

**Предусловия:** PUBLISHED график с траншем, у которого `dueDate` в прошлом и он не PAID.

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | Дождаться/инициировать sweep (или `GET` графика, re-derive на чтении) | транш переходит в `OVERDUE`, эмитится `notification.payment.trancheOverdue` (один раз), ставится `overdueNotifiedAt` |
| 2 | Повторить sweep/чтение, пока транш остаётся OVERDUE | **повторных** уведомлений нет (count == 1) |

_Покрывает:_ R9.3, R10.2 (Property 8 OverdueOnce).

### REG-04 — Сквозной путь create → generate → publish → reconcile → mark-paid → re-plan

| Шаг | Действие | Ожидаемый результат |
|-----|----------|---------------------|
| 1 | create → generate(EQUAL,3) → publish | график PUBLISHED, Σ = basis |
| 2 | MANUAL-record на первый транш | T1 сверен |
| 3 | mark-paid на второй транш | T2 → PAID |
| 4 | re-plan остатка | open-транши пересуммированы, PAID неизменны, статус PUBLISHED |
| 5 | Проверить агрегаты в `GET .../schedules/{id}` | `sumPaidGross`, `unpaidRemainder`, `unallocatedCredit`, производный `status` корректны |

_Покрывает:_ R1.2, R2.2, R4.1, R5.1, R5.9, R6.1 (критичный сквозной путь).

---

# Шаблон MD-репорта прогона

Заполняется по итогам каждого прогона (отдельно для Части A и Части B или единым файлом).

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-API-SCH-01 | 1 | POST /api/payments/schedules | 200 + DRAFT, basisGross=100000.00 | 200 | ✅ |
| 2 | TC-API-SCH-01 | 3 | GET /api/payments/schedules/{id} | unpaidRemainder=100000.00 | — | ⬜ |
| … | … | … | … | … | … | … |

**Итог:** X пройдено / Y провалено / Z пропущено.
**Run-id:** `<timestamp/uuid>`.
**Стратегия повторяемости:** генератор `run-id` + новый проект на прогон + teardown (`CANCELLED`/удаление проекта, каскад FK).
**Окружение:** `docker compose up`, backend `http://localhost:8080`, frontend `http://localhost:3000`; No-Op провайдеры (`*.provider=none`).
**Легенда статусов:** ✅ пройден · ❌ провален · ⬜ не запускался · ⚠️ пройден с оговоркой (приложить ссылку/комментарий).
