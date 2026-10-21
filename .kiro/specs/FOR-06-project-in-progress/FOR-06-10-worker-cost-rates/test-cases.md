# Тест-кейсы: FOR-06-10 — Worker cost rates (деривация себестоимости работника)

## Назначение и характер спеки

Спека **чисто API** — она не вводит собственный браузерный экран. Её результат (деривация
себестоимости работника) поверхностно проявляется в матрице маржи FOR-06-06, но сам read-model
FOR-06-10 доступен только через один read-эндпоинт:

```
GET /api/projects/{projectId}/worker-costs   →  WorkerCostReadModelDto   (WORK_REPORTS / READ)
```

Поэтому все тест-кейсы — **API-тесты против поднятого в Docker приложения** (`http://localhost:8080`,
auth под `/api/auth`), а не Testcontainers/юнит-слой. Каждый шаг — HTTP-запрос с проверкой статуса,
тела и заголовков. **Результат прогона — MD-репорты с таблицами** (шаг → запрос/действие → ожидание →
факт → статус). Скриншоты не требуются.

Эта спека ничего не пишет в домен: себестоимость всегда **деривируется на лету** из (1) типа
работника (`ProjectMember.workerType` → fallback на базовый тир) и (2) ставки работы проекта
(`WorkReport.clientUnitPrice`), через ядро FOR-05-06 `MarginCostService`. Поэтому апрувнутые отчёты
(с работниками разных типов) и ставки позиций сметы **засиживаются напрямую** (через admin/MANAGER
API FOR-06-01 и FOR-05-09), без зависимости от живого флоу отчётности.

### Формула себестоимости (источник истины — requirements/design)

- `Base_Cost = round(base.tierPct × Work_Item_Rate до 0.5 zł, HALF_UP)`, где `base.tierPct` —
  `tierPct` базового тира (сид 0.40).
- `Worker_Service_Cost (unitServiceCost) = Base_Cost × (1 + Resolved_Worker_Type.uplift)` — единичная
  себестоимость; `uplift` = `tierPct` небазового тира (например +10 % / +26.5 % / +65 %), 0 для базы.
- `Cost_Value (costValue) = Worker_Service_Cost × totalQuantity`.
- Ставка null/0 → `unitServiceCost` и `costValue` = **недоступны** (`null`), никогда не
  сфабрикованный ноль.

### Форма read-model (`WorkerCostReadModelDto`)

```
{
  projectId,
  lines:      [ WorkerCostLineDto ... ],      // по одному на APPROVED-отчёт (R3.1)
  byWorker:   [ WorkerCostByWorkerDto ... ],  // агрегат под payroll
  byWorkItem: [ WorkerCostByWorkItemDto ... ] // агрегат под матрицу маржи
}
```

- `WorkerCostLineDto`: `reportId, workerId, workerName, workItemId, workItemName, workerTypeId,
  workerTypeName, baseTierFallback, workItemRate (= clientUnitPrice), totalQuantity, unitServiceCost
  (null = недоступно), costValue (null = недоступно)`.
- `WorkerCostByWorkerDto`: `workerId, workerName, reportCount, totalCost` (Σ `costValue` по
  апрувнутым отчётам работника).
- `WorkerCostByWorkItemDto`: `workItemId, workItemName, reportCount, totalCost` (Σ `costValue` по
  апрувнутым отчётам позиции).

### Покрытие требований

| Требование | Кейсы |
|------------|-------|
| 1.1 (сервис деривации, per-report cost) | TC-COST-01, TC-COST-02 |
| 1.3 (`Cost_Value = unit × totalQuantity`) | TC-COST-01, TC-COST-02 |
| 1.5 (ставка null/0 → недоступно) | TC-UNAVAIL-01 |
| 2.1 (резолв типа из `ProjectMember.workerType`) | TC-COST-01, TC-FALLBACK-01 |
| 2.2 (fallback на базу: Uncategorized / inactive / без membership) | TC-FALLBACK-01, TC-FALLBACK-02, TC-FALLBACK-03 |
| 3.2 (агрегаты per-worker / per-work-item) | TC-AGG-01, TC-AGG-02 |
| 3.3 (только APPROVED, прочие статусы исключены) | TC-AGG-03 |
| 3.4 (недоступная строка исключена из сумм) | TC-UNAVAIL-01, TC-AGG-01 |
| 4.2 (паритет с фактовой себестоимостью матрицы FOR-06-06) | TC-PARITY-01 |
| 5.1 (ABAC-гейт `WORK_REPORTS`/READ; 401/403) | TC-ABAC-01…TC-ABAC-06 |
| 5.4 (FINANCIER допущен; project-scope) | TC-ABAC-05, TC-SCOPE-01 |

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции,
   включая FOR-06-01 changeset `165` с ресурсом `WORK_REPORTS` и грантом FINANCIER READ), `backend`
   (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN — через переменные окружения `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`,
   `FOREMEN_ADMIN_PASSWORD`. Логин: `POST /api/auth/login {email, password}` → `accessToken`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном (проверка:
   `GET /api/worker-types` отвечает 200 с засиженными тирами FOR-05-06, включая базовый тир
   `base == true`, `tierPct == 0.40`).

## Общая стратегия повторяемости

**Комбинированная: генератор `run-id` + teardown.** Единый на прогон `run-id` (timestamp или uuid,
например `1730000000` / `a1b2c3`) встраивается во все создаваемые идентификаторы, чтобы два
последовательных прогона на одной БД не конфликтовали:

- email работников: `w-active+{run-id}@example.com`, `w-uncat+{run-id}@example.com`,
  `w-inactive+{run-id}@example.com`, `w-nomember+{run-id}@example.com`;
- email роли-без-гранта (WORKER/CLIENT): `denied+{run-id}@example.com`;
- email прочих ролей: `manager+{run-id}@example.com`, `foreman+{run-id}@example.com`,
  `financier+{run-id}@example.com`;
- названия проектов: `Cost Test {run-id}`, `Cost Test B {run-id}` (для project-scope);
- коды/названия позиций сметы и работ — с суффиксом `{run-id}`.

**Teardown каждой группы** (если не указано иное) выполняется в конце набора в обратном порядке
создания доступным admin/MANAGER API: перевод/удаление апрувнутых отчётов → удаление членств
(`DELETE /api/project-members?...`) → деактивация/удаление созданных пользователей
(`PATCH`/`DELETE /api/users/{id}`) → перевод тестовых проектов в `CANCELLED` или их удаление. Сид
(роли, ресурс `WORK_REPORTS`, тиры worker type FOR-05-06) **только читается, никогда не изменяется**.
Каждая группа ниже уточняет стратегию строкой `**Повторяемость:**`.

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Пользователи ролей MANAGER / FOREMAN / FINANCIER (допущены к
`WORK_REPORTS` READ) и роли без гранта (WORKER / CLIENT) создаются и приглашаются через admin-API в
группе `Setup`. Матрица для `WORK_REPORTS`: **ADMIN / MANAGER / FOREMAN / FINANCIER — READ; WORKER /
CLIENT — без гранта**. Там, где создание живого пользователя роли невозможно, ABAC-ожидание
документируется и покрывается seed-интеграционными тестами спеки (`WorkerCostControllerAbacTest`).

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Setup — токены, проект, тиры, работники, апрувнутые отчёты

**Повторяемость:** читает сид + создаёт помеченные `run-id` сущности; teardown в конце набора.

### TC-SETUP-01 — Логин ADMIN и чтение тиров

**Предусловия:** docker-стек поднят; ADMIN забутстрапен.

1. `POST /api/auth/login` `{email: FOREMEN_ADMIN_EMAIL, password: FOREMEN_ADMIN_PASSWORD}` →
   **200**, тело содержит `accessToken`. Сохранить как `ADMIN_TOKEN`.
2. `GET /api/worker-types` с `Authorization: Bearer ADMIN_TOKEN` → **200**, массив тиров. Найти:
   базовый тир (`base == true`, `tierPct == 0.40`) → сохранить `BASE_TIER`; небазовый активный тир
   (например `tierPct == 0.10`) → сохранить `TIER_UPLIFT`; неактивный тир (`active == false`), если
   есть, → `TIER_INACTIVE` (иначе создать/деактивировать через admin-API).

**Ожидаемый результат:** получен `ADMIN_TOKEN`; известны id и `tierPct` базового, небазового активного
и неактивного тиров.

### TC-SETUP-02 — Создание проекта и работников разных типов

**Предусловия:** TC-SETUP-01.

1. Создать проект `Cost Test {run-id}` через admin/MANAGER API и перевести его в `ACTIVE` →
   сохранить `PROJECT_A`.
2. Создать 4 работников (воркер-флоу FOR-05-09) с email из стратегии повторяемости:
   `W_ACTIVE` (будет с активным назначенным типом), `W_UNCAT` (Uncategorized — тип null),
   `W_INACTIVE` (тип будет указывать на неактивный тир), `W_NOMEMBER` (без членства в проекте).
3. Добавить в `PROJECT_A` членства: `W_ACTIVE` с `workerType = TIER_UPLIFT`; `W_UNCAT` с
   `workerType = null`; `W_INACTIVE` с `workerType = TIER_INACTIVE`. `W_NOMEMBER` — **не** добавлять.
4. Создать позиции сметы/работ с ненулевой ставкой (`clientUnitPrice`), например `RATE_1 = 100.00`,
   `RATE_2 = 200.00`, и одну с нулевой/пустой ставкой `RATE_ZERO = 0`.

**Ожидаемый результат:** проект `ACTIVE`; четыре работника с разной конфигурацией типа; известны
ставки позиций.

### TC-SETUP-03 — Засев апрувнутых отчётов

**Предусловия:** TC-SETUP-02.

1. Через API отчётности FOR-06-01 создать и **довести до статуса `APPROVED`** отчёты на `PROJECT_A`:
   - `R_ACTIVE`: `worker = W_ACTIVE`, `workItem = WI_1`, `clientUnitPrice = 100.00`,
     `totalQuantity = 2`;
   - `R_UNCAT`: `worker = W_UNCAT`, `workItem = WI_1`, `clientUnitPrice = 100.00`,
     `totalQuantity = 1`;
   - `R_INACTIVE`: `worker = W_INACTIVE`, `workItem = WI_2`, `clientUnitPrice = 200.00`,
     `totalQuantity = 1`;
   - `R_NOMEMBER`: `worker = W_NOMEMBER`, `workItem = WI_2`, `clientUnitPrice = 200.00`,
     `totalQuantity = 1`;
   - `R_ZERO`: `worker = W_ACTIVE`, `workItem = WI_ZERO`, `clientUnitPrice = 0`, `totalQuantity = 3`;
   - `R_SUBMITTED`: любой работник, статус оставить `SUBMITTED` (не апрувить).
2. Создать второй проект `Cost Test B {run-id}` (`PROJECT_B`, `ACTIVE`) с одним апрувнутым отчётом
   `R_B` (для project-scope проверки).

**Ожидаемый результат:** на `PROJECT_A` 5 апрувнутых отчётов + 1 `SUBMITTED`; на `PROJECT_B` 1
апрувнутый отчёт.

---

## Фича 1. Per-report себестоимость и Cost_Value (R1.1, R1.3, R2.1)

**Повторяемость:** read-only чтение; опирается на засев Setup; собственного teardown нет.

### TC-COST-01 — Единичная себестоимость и Cost_Value для активного типа

**Предусловия:** TC-SETUP-01…03.

1. `GET /api/projects/{PROJECT_A}/worker-costs` с `Authorization: Bearer ADMIN_TOKEN` → **200**,
   тело `WorkerCostReadModelDto` с `projectId == PROJECT_A`.
2. В `lines[]` найти строку с `reportId == R_ACTIVE`. Проверить:
   - `workerId == W_ACTIVE`, `workItemId == WI_1`, `workItemRate == 100.00`, `totalQuantity == 2`;
   - `workerTypeId == TIER_UPLIFT`, `baseTierFallback == false`;
   - `unitServiceCost == round(0.40 × 100.00 до 0.5) × (1 + uplift)` (например при uplift=0.10:
     `40.00 × 1.10 = 44.00`);
   - `costValue == unitServiceCost × 2` (например `88.00`).

**Ожидаемый результат:** единичная себестоимость и `costValue` совпадают с формулой
`MarginCostService` на активном назначенном тире; `baseTierFallback == false`.

### TC-COST-02 — Линейность Cost_Value по количеству

**Предусловия:** TC-COST-01.

1. `GET /api/projects/{PROJECT_A}/worker-costs` → **200**. Сравнить строку `R_ACTIVE`
   (`totalQuantity == 2`) со строкой `R_UNCAT` на том же `WI_1`/ставке 100.00.
2. Убедиться, что для одинакового `unitServiceCost` значение `costValue` пропорционально
   `totalQuantity` (удвоение количества → удвоение `costValue`).

**Ожидаемый результат:** `costValue == unitServiceCost × totalQuantity` для каждой строки; масштаб по
количеству линейный.

---

## Фича 2. Fallback на базовый тир (R2.1, R2.2)

**Повторяемость:** read-only; опирается на засев Setup.

### TC-FALLBACK-01 — Uncategorized работник (тип null) → базовый тир

**Предусловия:** TC-SETUP-01…03.

1. `GET /api/projects/{PROJECT_A}/worker-costs` → **200**. Найти строку `R_UNCAT`.
2. Проверить: `baseTierFallback == true`; `workerTypeId == BASE_TIER`; `unitServiceCost` равен
   **базовой** себестоимости (`round(0.40 × 100.00 до 0.5) = 40.00`, uplift базы = 0).

**Ожидаемый результат:** работник без назначенного типа резолвится на базовый тир, флаг
`baseTierFallback == true`, себестоимость = базовая.

### TC-FALLBACK-02 — Работник с неактивным типом → базовый тир

**Предусловия:** TC-SETUP-01…03.

1. `GET /api/projects/{PROJECT_A}/worker-costs` → **200**. Найти строку `R_INACTIVE`.
2. Проверить: `baseTierFallback == true`; `workerTypeId == BASE_TIER`; `unitServiceCost ==
   round(0.40 × 200.00 до 0.5) = 80.00`.

**Ожидаемый результат:** неактивный тир игнорируется, резолв падает на базу, флаг
`baseTierFallback == true`.

### TC-FALLBACK-03 — Работник без членства в проекте → базовый тир

**Предусловия:** TC-SETUP-01…03.

1. `GET /api/projects/{PROJECT_A}/worker-costs` → **200**. Найти строку `R_NOMEMBER`.
2. Проверить: `baseTierFallback == true`; `workerTypeId == BASE_TIER`; `unitServiceCost ==
   round(0.40 × 200.00 до 0.5) = 80.00`.

**Ожидаемый результат:** отсутствие членства не ошибка — резолв на базовый тир с
`baseTierFallback == true`.

---

## Фича 3. Недоступная ставка (R1.5, R3.4)

**Повторяемость:** read-only; опирается на засев Setup (`R_ZERO`).

### TC-UNAVAIL-01 — Нулевая/пустая ставка → unitServiceCost и costValue = null, исключение из сумм

**Предусловия:** TC-SETUP-01…03.

1. `GET /api/projects/{PROJECT_A}/worker-costs` → **200**. Найти строку `R_ZERO`
   (`workItemRate == 0`/null).
2. Проверить: `unitServiceCost == null` и `costValue == null` (а не 0.00).
3. В `byWorker[]` для `W_ACTIVE` убедиться, что `totalCost` **не включает** `R_ZERO`
   (сумма = только `costValue` доступных апрувнутых отчётов этого работника).
4. В `byWorkItem[]` убедиться, что `WI_ZERO` либо отсутствует, либо имеет `totalCost`, не
   содержащий фабрикованного нуля от `R_ZERO`.

**Ожидаемый результат:** недоступная ставка даёт `null` (не 0) и исключается из обоих агрегатов.

---

## Фича 4. Агрегаты per-worker и per-work-item (R3.2, R3.3, R3.4)

**Повторяемость:** read-only; опирается на засев Setup.

### TC-AGG-01 — byWorker: сумма только доступных апрувнутых отчётов

**Предусловия:** TC-SETUP-01…03.

1. `GET /api/projects/{PROJECT_A}/worker-costs` → **200**.
2. Для `W_ACTIVE` в `byWorker[]`: `totalCost == Σ costValue` его **доступных** апрувнутых строк
   (`R_ACTIVE`), **без** `R_ZERO`; `reportCount` отражает число учтённых строк.
3. Для `W_UNCAT` / `W_INACTIVE` / `W_NOMEMBER` проверить соответствующие `totalCost` против их строк.

**Ожидаемый результат:** `byWorker[w].totalCost` равен сумме `costValue` по доступным апрувнутым
отчётам работника; недоступные строки не учтены.

### TC-AGG-02 — byWorkItem: сумма по позиции

**Предусловия:** TC-SETUP-01…03.

1. `GET /api/projects/{PROJECT_A}/worker-costs` → **200**.
2. Для `WI_1` в `byWorkItem[]`: `totalCost == costValue(R_ACTIVE) + costValue(R_UNCAT)`.
3. Для `WI_2`: `totalCost == costValue(R_INACTIVE) + costValue(R_NOMEMBER)`.

**Ожидаемый результат:** `byWorkItem[m].totalCost` равен сумме `costValue` по апрувнутым отчётам
позиции.

### TC-AGG-03 — Не-APPROVED отчёты исключены из read-model и агрегатов

**Предусловия:** TC-SETUP-01…03 (есть `R_SUBMITTED`).

1. `GET /api/projects/{PROJECT_A}/worker-costs` → **200**.
2. Убедиться, что в `lines[]` **нет** строки с `reportId == R_SUBMITTED` (и других не-APPROVED).
3. Убедиться, что `R_SUBMITTED` не влияет на `byWorker`/`byWorkItem`.

**Ожидаемый результат:** в read-model только `APPROVED`-отчёты; `SUBMITTED`/`CHANGES_REQUESTED`/
`REJECTED` не входят ни в строки, ни в агрегаты.

---

## Фича 5. Паритет с матрицей маржи FOR-06-06 (R4.2)

**Повторяемость:** read-only; сравнивает два read-эндпоинта.

### TC-PARITY-01 — Фактовая себестоимость матрицы = Cost_Value сервиса

**Предусловия:** TC-SETUP-01…03; доступен read-эндпоинт матрицы маржи FOR-06-06 на `PROJECT_A`.

1. `GET /api/projects/{PROJECT_A}/worker-costs` с `ADMIN_TOKEN` → **200**. Сохранить `byWorkItem[]`.
2. Получить матрицу маржи FOR-06-06 для `PROJECT_A` (её read-эндпоинт) → **200**. Для каждой позиции
   взять фактовую себестоимость труда.
3. Сверить **penny-for-penny**: фактовая себестоимость позиции в матрице == `byWorkItem[m].totalCost`
   из сервиса (идентичный fallback на базу для Uncategorized).

**Ожидаемый результат:** после рефактора FOR-06-06 единственная формула — себестоимость матрицы
совпадает с `Cost_Value` сервиса покопеечно.

---

## Фича 6. ABAC-гейт WORK_REPORTS / READ (R5.1, R5.4)

**Повторяемость:** генератор `run-id` для пользователей ролей + teardown (деактивация).

### TC-ABAC-01 — ADMIN допущен

1. `GET /api/projects/{PROJECT_A}/worker-costs` с `ADMIN_TOKEN` → **200** + read-model.

**Ожидаемый результат:** ADMIN читает read-model (200).

### TC-ABAC-02 — MANAGER допущен

**Предусловия:** создан и приглашён MANAGER (`manager+{run-id}@example.com`), получен `MANAGER_TOKEN`.

1. `GET /api/projects/{PROJECT_A}/worker-costs` с `MANAGER_TOKEN` → **200**.

**Ожидаемый результат:** MANAGER читает read-model (200).

### TC-ABAC-03 — FOREMAN допущен

**Предусловия:** создан FOREMAN, получен `FOREMAN_TOKEN`.

1. `GET /api/projects/{PROJECT_A}/worker-costs` с `FOREMAN_TOKEN` → **200**.

**Ожидаемый результат:** FOREMAN читает read-model (200).

### TC-ABAC-04 — роль без гранта → 403

**Предусловия:** создан пользователь роли без `WORK_REPORTS` READ (WORKER или CLIENT),
`DENIED_TOKEN`.

1. `GET /api/projects/{PROJECT_A}/worker-costs` с `DENIED_TOKEN` → **403**.

**Ожидаемый результат:** роль без гранта получает 403 (запрос отклонён ABAC).

### TC-ABAC-05 — FINANCIER допущен (грант FOR-06-01)

**Предусловия:** создан FINANCIER, `FINANCIER_TOKEN`.

1. `GET /api/projects/{PROJECT_A}/worker-costs` с `FINANCIER_TOKEN` → **200**.

**Ожидаемый результат:** FINANCIER читает read-model (200) — грант `WORK_REPORTS` READ засеян
FOR-06-01 (changeset 165), эта спека миграцию не добавляет.

### TC-ABAC-06 — Неаутентифицированный запрос → 401

1. `GET /api/projects/{PROJECT_A}/worker-costs` **без** заголовка `Authorization` → **401**.

**Ожидаемый результат:** без токена доступ отклонён (401).

---

## Фича 7. Project-scope гейт (R5.3, R5.4)

**Повторяемость:** read-only; опирается на `PROJECT_A` и `PROJECT_B` из Setup.

### TC-SCOPE-01 — Нет утечки между проектами

**Предусловия:** TC-SETUP-03 (есть `PROJECT_A` и `PROJECT_B` с собственными апрувнутыми отчётами).

1. `GET /api/projects/{PROJECT_A}/worker-costs` с `ADMIN_TOKEN` → **200**. Убедиться, что все
   `lines[].reportId` принадлежат `PROJECT_A`; отчёта `R_B` среди них нет.
2. `GET /api/projects/{PROJECT_B}/worker-costs` → **200**. Убедиться, что присутствует только `R_B`;
   отчётов `PROJECT_A` нет.

**Ожидаемый результат:** read-model каждого проекта project-scoped — ни одна строка не относится к
чужому проекту.

---

# Регрессия

**Повторяемость:** read-only + повторный прогон ключевых кейсов; опирается на засев Setup.

Поскольку спека рефакторит фактовую себестоимость FOR-06-06 на общий сервис (behaviour-preserving) и
ездит на существующем ресурсе `WORK_REPORTS`, регрессионная группа перепроверяет, что соседние
фичи не сломаны.

### REG-01 — Паритет FOR-06-06 после рефактора (повтор TC-PARITY-01)

1. Повторить TC-PARITY-01 → фактовая себестоимость матрицы покопеечно равна `byWorkItem[m].totalCost`
   сервиса. **Ожидание:** поведение FOR-06-06 не изменилось (R4.2, R8.1).

### REG-02 — Отчётность FOR-06-01 не затронута

1. `GET`/создание/апрув отчётов FOR-06-01 на `PROJECT_A` работает как раньше (revenue-сторона
   `clientUnitPrice`/`revenueValue` не изменена). **Ожидание:** read/approve-флоу отчётов проходит.

### REG-03 — ABAC-матрица WORK_REPORTS без новых ресурсов

1. Повторить TC-ABAC-01…06. **Ожидание:** допуск ADMIN/MANAGER/FOREMAN/FINANCIER и отказ
   роли-без-гранта/анонима неизменны; новый ABAC-ресурс не появился (приложение стартует без
   `PermissionAnnotationValidator`-ошибки).

### REG-04 — Тиры FOR-05-06 и членства FOR-05-09 только читаются

1. Сверить `GET /api/worker-types` до и после прогона — тиры (`tierPct`, `base`, `active`) не
   изменились; `ProjectMember.workerType` засеянных членств не мутировал. **Ожидание:** вход read-only.

---

# Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SETUP-01 | 1 | POST /api/auth/login (ADMIN) | 200 + accessToken | | |
| 2 | TC-SETUP-01 | 2 | GET /api/worker-types | 200 + base/uplift/inactive тиры | | |
| 3 | TC-COST-01 | 2 | GET /api/projects/{A}/worker-costs | R_ACTIVE: unit=44.00, cost=88.00, fallback=false | | |
| 4 | TC-COST-02 | 2 | GET .../worker-costs | costValue = unit × totalQuantity | | |
| 5 | TC-FALLBACK-01 | 2 | GET .../worker-costs | R_UNCAT: fallback=true, type=BASE, unit=40.00 | | |
| 6 | TC-FALLBACK-02 | 2 | GET .../worker-costs | R_INACTIVE: fallback=true, type=BASE, unit=80.00 | | |
| 7 | TC-FALLBACK-03 | 2 | GET .../worker-costs | R_NOMEMBER: fallback=true, type=BASE, unit=80.00 | | |
| 8 | TC-UNAVAIL-01 | 2 | GET .../worker-costs | R_ZERO: unitServiceCost=null, costValue=null | | |
| 9 | TC-UNAVAIL-01 | 3 | GET .../worker-costs (byWorker) | R_ZERO исключён из суммы | | |
| 10 | TC-AGG-01 | 2 | GET .../worker-costs (byWorker) | totalCost = Σ доступных апрувнутых | | |
| 11 | TC-AGG-02 | 2 | GET .../worker-costs (byWorkItem) | WI_1 = cost(R_ACTIVE)+cost(R_UNCAT) | | |
| 12 | TC-AGG-03 | 2 | GET .../worker-costs (lines) | R_SUBMITTED отсутствует | | |
| 13 | TC-PARITY-01 | 3 | GET matrix vs worker-costs | penny-for-penny равенство | | |
| 14 | TC-ABAC-01 | 1 | GET .../worker-costs (ADMIN) | 200 | | |
| 15 | TC-ABAC-02 | 1 | GET .../worker-costs (MANAGER) | 200 | | |
| 16 | TC-ABAC-03 | 1 | GET .../worker-costs (FOREMAN) | 200 | | |
| 17 | TC-ABAC-04 | 1 | GET .../worker-costs (WORKER/CLIENT) | 403 | | |
| 18 | TC-ABAC-05 | 1 | GET .../worker-costs (FINANCIER) | 200 | | |
| 19 | TC-ABAC-06 | 1 | GET .../worker-costs (без токена) | 401 | | |
| 20 | TC-SCOPE-01 | 1 | GET /api/projects/{A}/worker-costs | нет строк PROJECT_B | | |
| 21 | TC-SCOPE-01 | 2 | GET /api/projects/{B}/worker-costs | только R_B | | |
| 22 | REG-01 | 1 | повтор паритета | матрица == сервис | | |
| 23 | REG-03 | 1 | повтор ABAC + старт приложения | без новых ресурсов, старт OK | | |

**Итог:** X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`. Стратегия
повторяемости: `генератор run-id (email/коды/имена проектов) + teardown (удаление отчётов → членств →
пользователей → проектов; сид только читается)`.
