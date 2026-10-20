# Тест-кейсы: FOR-06-06 — Margin matrix (маржа план × факт по работам)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерный экран**, поэтому набор тест-кейсов включает **оба типа**:

- **Часть A. API-тесты** — против поднятого в Docker приложения (`http://localhost:8080`). Проверяют
  read-модель матрицы маржи, выдаваемую единственным эндпоинтом
  `GET /api/projects/{projectId}/margin-matrix` (ресурс `WORK_REPORTS`, операция `READ`): факт-стоимость
  по тиру worker type каждого отчёта, fallback на базовый тир для «неназначенного» воркера, план-стоимость
  по базовому тиру, свёртка привязанного материала, отдельная сумма косвенных расходов по категориям
  (только в overall-марже), недоступная цена `—`, флаг under-margin, итоги дашборда, проектный скоуп и
  ABAC-гейт `WORK_REPORTS`/READ.
- **Часть B. Браузерные (UI) сценарии** — против фронтенда (`http://localhost:3000`), исполняются
  браузерным движком (headless-браузер / browser automation) под ролью MANAGER (либо ADMIN) на `ACTIVE`
  проекте, плюс отдельный сценарий на `COMPLETED` для проверки read-only. Экран: вкладка `Margin`
  воркспейса проекта — overall-дашборд сверху, группы по категориям работ → строки → план/факт маржа →
  highlight under-margin → детализация по отчётам → read-only.

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание →
факт → статус). **Скриншоты не требуются.**

### Важное свойство read-модели (проекция, без записи)

Спека — **read-only проекция** над существующими read-моделями. Она **не создаёт** ни managed-сущности,
ни ABAC-ресурса, ни справочника, ни Liquibase-changeset. Исходные данные (строки сметы FOR-05,
утверждённые отчёты FOR-06-01, сверенные закупочные строки FOR-06-03/04, тиры worker type FOR-05-06,
`ProjectMember.workerType` FOR-05-09) **сидируются напрямую** через существующие admin/CRUD-API —
никакой зависимости от живого репортинга или OCR в прогоне нет. Сама матрица ничего не изменяет.

### Покрытие требований

| Требование | Покрывающие кейсы |
|------------|-------------------|
| R1.3 (факт-стоимость = Σ тир-стоимость каждого отчёта) | TC-API-ROW-02, TC-UI-DETAIL-01 |
| R1.4 (Uncategorized → базовый тир) | TC-API-FALLBACK-01, TC-UI-DETAIL-02 |
| R1.5 (строка без отчётов: 0 / `—`, без деления на ноль) | TC-API-ZERO-01, TC-UI-ROW-02 |
| R2.5 (fallback базового тира виден в детализации) | TC-API-FALLBACK-01, TC-UI-DETAIL-02 |
| R3.1 (свёртка привязанного материала — только overall) | TC-API-MAT-01, TC-UI-DASH-01 |
| R3.2 (косвенные расходы отдельной суммой по категориям) | TC-API-INDIRECT-01, TC-UI-DASH-02 |
| R3.4 (overall-дашборд: итоги) | TC-API-DASH-01, TC-UI-DASH-01 |
| R4.1 (группировка по категориям + подытоги) | TC-API-GROUP-01, TC-UI-GROUP-01 |
| R4.3 (флаг under-margin) | TC-API-UNDER-01, TC-UI-UNDER-01 |
| R5.1 (панель: строки + highlight + disclosure) | TC-UI-ROW-01, TC-UI-UNDER-01 |
| R5.4 (read-only; на `COMPLETED` идентично) | TC-UI-READONLY-01 |
| R6.1 (ABAC `WORK_REPORTS`/READ, нет нового ресурса) | TC-API-ABAC-01, TC-API-ABAC-03 |
| R6.4 (`ACTIVE`/`COMPLETED` читаемо, `DRAFT` недоступно) | TC-API-GATE-01, TC-UI-GATE-01 |

Соответствие «кейс ↔ требование» дополнительно указано в каждом кейсе строкой `_Покрывает:_`.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции),
   `backend` (:8080, профиль `docker`), `frontend` (:3000). Новых changeset эта спека **не** добавляет.
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN — через переменные окружения `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`,
   `FOREMEN_ADMIN_PASSWORD`. Логин: `POST /api/auth/login {email, password}` → `accessToken`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном (проверка:
   `GET /api/worker-types` отвечает **200** с засиженными тирами FOR-05-06).

## Общая стратегия повторяемости

**Комбинированная: генератор `run-id` + teardown.** Единый на прогон `run-id` (timestamp или uuid,
например `1730000000` или `a1b2c3`) встраивается во все создаваемые идентификаторы, чтобы два
последовательных прогона на одной БД не конфликтовали:

- email воркеров: `worker-tier+{run-id}@example.com`, `worker-base+{run-id}@example.com`;
- email менеджера: `mgr+{run-id}@example.com`;
- названия проектов: `Margin Test {run-id}` (ACTIVE), `Margin Done {run-id}` (COMPLETED),
  `Margin Draft {run-id}` (DRAFT);
- названия работ/категорий (если создаются): `Work {run-id}`, `Cat {run-id}`.

**Teardown каждой группы** (если не указано иное) выполняется в конце набора в обратном порядке
создания: удаление/деактивация созданных отчётов и закупочных строк доступным admin-API, затем перевод
тестовых проектов в `CANCELLED` (или их удаление), затем деактивация созданных пользователей. Сид-данные
(роли, ресурс `WORK_REPORTS`, тиры worker type FOR-05-06, справочник `UnplannedExpenseCategory`) **только
читаются, никогда не изменяются**. Так как матрица — read-only проекция, сами её вызовы состояние не
меняют и чистки не требуют. Каждая группа ниже дополнительно уточняет свою стратегию строкой
`**Повторяемость:**`.

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Там, где кейс требует роли MANAGER / FOREMAN или роли **без** гранта
`WORK_REPORTS` (WORKER / CLIENT), тестовые пользователи создаются и приглашаются через admin-API в
группе `Setup`. Матрица `WORK_REPORTS` (засижена FOR-06-01): **ADMIN / MANAGER / FOREMAN — READ (и
далее по матрице отчётов); WORKER / CLIENT — без гранта READ на матрицу**. Матрица маржи **ездит** на
`WORK_REPORTS`/READ и **не** вводит нового ресурса.

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Setup — токены, справочники и сид данных

**Повторяемость:** читает сид + создаёт помеченные `run-id` сущности; teardown в конце набора.

### TC-SETUP-01 — Логин ADMIN
**Предусловия:** стек поднят, ADMIN забутстрапен.
1. `POST /api/auth/login {email: <FOREMEN_ADMIN_EMAIL>, password: <FOREMEN_ADMIN_PASSWORD>}` →
   **200** + `accessToken`. Сохранить как `TOKEN_ADMIN`.

_Покрывает:_ инфраструктура (предзависимость всех групп).

### TC-SETUP-02 — Справочник worker types (тиры)
1. `GET /api/worker-types` (Bearer ADMIN) → **200** + список засиженных тиров FOR-05-06. Найти строку
   с флагом `base` (offer share 0.40) → сохранить `WT_BASE` (`tierPct`, `id`). Найти активный **не-базовый**
   тир с ненулевым uplift (например, firm / sole-trader) → сохранить `WT_UPLIFT` (`tierPct`, `id`).
2. (Опционально) найти **неактивный** тир → сохранить `WT_INACTIVE` для кейса fallback.

_Покрывает:_ предзависимость R2 (cost core / тиры — источник «процента от worker type»).

### TC-SETUP-03 — Создать `ACTIVE` проект, MANAGER и двух воркеров разных тиров
**Предусловия:** TOKEN_ADMIN, WT_BASE, WT_UPLIFT.
1. Создать MANAGER-пользователя `mgr+{run-id}@example.com`; создать проект `Margin Test {run-id}`
   (`POST /api/projects`) с этим MANAGER. → **201** + `projectId`. Сохранить `PROJECT_ID`,
   `MANAGER_USER_ID`. Логин MANAGER → `TOKEN_MANAGER`.
2. Создать двух воркеров и добавить членствами в проект:
   - `WORKER_UPLIFT` (`worker-tier+{run-id}@...`) с `ProjectMember.workerType = WT_UPLIFT`;
   - `WORKER_BASE` (`worker-base+{run-id}@...`) **без** назначенного worker type (`workerType = null`,
     Uncategorized_Worker).
   Сохранить `WORKER_UPLIFT_ID`, `WORKER_BASE_ID`.
3. Перевести проект в `ACTIVE` доступным admin-API. `GET /api/projects/{PROJECT_ID}` → статус `ACTIVE`.

_Покрывает:_ предзависимость R1.4 / R2.5 (разные тиры + Uncategorized), R6.4 (ACTIVE).

### TC-SETUP-04 — Засидить смету (план-сторона) с ценой и с «недоступной» ценой
**Предусловия:** TOKEN_ADMIN/`TOKEN_MANAGER`, PROJECT_ID.
1. Сформировать смету проекта (строки сметы FOR-05) с двумя категориями работ:
   - строка `LINE_A` в категории `Cat1`: `unitPrice > 0`, `quantity`, `valueNet = unitPrice × quantity`;
   - строка `LINE_B` в категории `Cat1`: такая же схема с другой ценой;
   - строка `LINE_C` в категории `Cat2`: `unitPrice = null/0` (**недоступная цена**).
   Сохранить `WORKITEM_A`, `WORKITEM_B`, `WORKITEM_C` и ожидаемые `valueNet`.

_Покрывает:_ предзависимость R1.1/R1.2/R1.6, R4.1.

### TC-SETUP-05 — Засидить утверждённые отчёты (факт-сторона, разные тиры)
**Предусловия:** PROJECT_ID, WORKITEM_A, воркеры.
1. Создать и **утвердить** (`APPROVED`) отчёты по `WORKITEM_A`:
   - отчёт `REP_A1`: воркер `WORKER_UPLIFT`, `clientUnitPrice`, `totalQuantity`, `revenueValue`;
   - отчёт `REP_A2`: воркер `WORKER_BASE` (Uncategorized), свои `clientUnitPrice`, `totalQuantity`,
     `revenueValue`.
   (Создание — `POST /api/work-reports/batch`; перевод в `APPROVED` — `POST /api/work-reports/{id}/approve`.)
2. По `WORKITEM_B` отчётов **не** создавать (кейс нулевой факт-строки).

_Покрывает:_ предзависимость R1.3, R1.5, R2.4, R2.5.

### TC-SETUP-06 — Засидить сверенные закупочные строки (материал + косвенные)
**Предусловия:** PROJECT_ID.
1. Засидить закупочные строки через процедуру FOR-06-03/04 (создание документа + сверка) напрямую:
   - `PL_LINKED`: `matchedMaterialId != null`, `matchStatus = MANUAL_MATCHED`, `lineTotal = L`
     (привязанный материал);
   - `PL_IND1`: не привязана, `unplannedCategory = <катег. A>`, `lineTotal = I1`;
   - `PL_IND2`: не привязана, `unplannedCategory = null` (попадёт в bucket «uncategorized»),
     `lineTotal = I2`.
   Сохранить ожидаемые `L`, `I1`, `I2`.

_Покрывает:_ предзависимость R3.1, R3.2.

---

## Фича 1. ABAC и проектный скоуп (R6.1, R6.3)

**Повторяемость:** только чтение матрицы + помеченные токены; teardown токенов.

### TC-API-ABAC-01 — Матрица ездит на `WORK_REPORTS`/READ, нового ресурса нет
**Предусловия:** TOKEN_ADMIN.
1. `GET /api/resources` (Bearer ADMIN) → **200**; в списке есть `WORK_REPORTS` (из FOR-06-01).
2. Убедиться, что **нет** отдельного ресурса под матрицу маржи (например `MARGIN_MATRIX`) — матрица не
   вводит новый ABAC-ресурс.
3. `GET /api/projects/{PROJECT_ID}/margin-matrix` (Bearer MANAGER, у роли есть READ на `WORK_REPORTS`)
   → **200** + тело `MarginMatrixDto`.

_Покрывает:_ R6.1 (нет нового ресурса; гейт на `WORK_REPORTS`/READ).

### TC-API-ABAC-02 — 401 без токена
1. `GET /api/projects/{PROJECT_ID}/margin-matrix` **без** `Authorization` → **401**.

_Покрывает:_ R6.1 (аутентификация до авторизации).

### TC-API-ABAC-03 — 403 для роли без гранта READ
**Предусловия:** токен пользователя без гранта `WORK_REPORTS` READ (например WORKER/CLIENT) — `TOKEN_NOGRANT`.
1. `GET /api/projects/{PROJECT_ID}/margin-matrix` (Bearer `TOKEN_NOGRANT`) → **403**.

_Покрывает:_ R6.1.

### TC-API-SCOPE-01 — Проектный скоуп: чужой проект не виден
**Предусловия:** два проекта `PROJECT_ID` и `OTHER_PROJECT_ID`; у MANAGER нет членства в `OTHER_PROJECT_ID`.
1. `GET /api/projects/{OTHER_PROJECT_ID}/margin-matrix` (Bearer MANAGER) → **403** (или пустая/ошибка
   по скоупу), **никогда** не данные чужого проекта.
2. `GET /api/projects/{PROJECT_ID}/margin-matrix` (Bearer MANAGER) → **200** с `projectId = PROJECT_ID`.

_Покрывает:_ R6.3.

---

## Фича 2. План-сторона и строки (R1.1, R1.2, R1.6, R4.1)

**Повторяемость:** только чтение матрицы (сид создан в Setup); teardown — на уровне набора.

### TC-API-ROW-01 — План-стоимость по базовому тиру
**Предусловия:** сид Setup, TOKEN_MANAGER.
1. `GET /api/projects/{PROJECT_ID}/margin-matrix` → **200**. Найти строку `WORKITEM_A`.
2. Проверить `planRevenue == valueNet(LINE_A)`.
3. Проверить `planCost == Tier_Cost(unitPrice_A, базовый тир) × quantity_A`, где
   `Base_Cost = round(WT_BASE.tierPct × unitPrice_A до 0.5 zł, HALF_UP)`, `Tier_Cost = Base_Cost × (1 + 0)`.
4. Проверить `planMarginAmount == planRevenue − planCost`, `planMarginPct == planMarginAmount / planRevenue`.

_Покрывает:_ R1.2 (план по базовому тиру через cost core).

### TC-API-ROW-02 — Факт-стоимость = сумма по тиру каждого отчёта
**Предусловия:** сид (REP_A1 на WT_UPLIFT, REP_A2 на базовом), TOKEN_MANAGER.
1. `GET …/margin-matrix` → строка `WORKITEM_A`.
2. `factRevenue == revenueValue(REP_A1) + revenueValue(REP_A2)`.
3. `factCost == Tier_Cost(cup_A1, WT_UPLIFT) × qty_A1 + Tier_Cost(cup_A2, базовый) × qty_A2` — каждый
   отчёт по **своему** тиру.
4. `factQuantity == qty_A1 + qty_A2`, `reportCount == 2`.

_Покрывает:_ R1.3 (факт-свёртка; разные тиры в одной строке).

### TC-API-UNAVAIL-01 — Недоступная цена даёт `—`, строка исключена из знаменателей
**Предусловия:** строка `LINE_C` с `unitPrice = null/0`.
1. `GET …/margin-matrix` → строка `WORKITEM_C` присутствует.
2. `planCost == null`, `planMarginAmount == null`, `planMarginPct == null` (сериализуется как
   «недоступно», на UI `—`) — **не** сфабрикованный ноль.
3. В план-итогах дашборда строка `WORKITEM_C` **не** учтена в знаменателях.

_Покрывает:_ R1.6.

### TC-API-ZERO-01 — Строка без отчётов: факт 0 / pct `—`, без деления на ноль
**Предусловия:** `WORKITEM_B` без отчётов.
1. `GET …/margin-matrix` → строка `WORKITEM_B`.
2. `factRevenue == 0`, `factCost == 0`, `reportCount == 0`.
3. `factMarginPct == null` (недоступно, `—`), ответ **200** без ошибки деления.
4. План-сторона `WORKITEM_B` при этом присутствует.

_Покрывает:_ R1.5.

### TC-API-GROUP-01 — Группировка по категориям и подытоги
1. `GET …/margin-matrix` → `categories` упорядочены как в смете; `Cat1` содержит `WORKITEM_A`, `WORKITEM_B`,
   `Cat2` содержит `WORKITEM_C`.
2. Подытог категории `Cat1`: `planRevenue/planCost/planMarginAmount` и `factRevenue/factCost/factMarginAmount`
   равны суммам по её строкам (строки с недоступным план-значением не ломают сумму).

_Покрывает:_ R4.1.

---

## Фича 3. Тир воркера и fallback (R1.4, R2.5)

**Повторяемость:** только чтение матрицы; teardown — на уровне набора.

### TC-API-FALLBACK-01 — Uncategorized воркер → базовый тир, флаг в детализации
**Предусловия:** REP_A2 от `WORKER_BASE` (workerType = null).
1. `GET …/margin-matrix` → строка `WORKITEM_A`, в `reports[]` найти деталь по `REP_A2`.
2. `workerTypeId == null` (или id базового), резолв на **базовый** тир: `baseTierFallback == true`.
3. `derivedTierCost(REP_A2) == Tier_Cost(cup_A2, базовый тир) × qty_A2` (uplift 0).
4. Для `REP_A1` (назначенный WT_UPLIFT): `baseTierFallback == false`, `derivedTierCost` по WT_UPLIFT.
5. Сумма `derivedTierCost` по всем `reports[]` строки `== factCost` строки (нет скрытой агрегации).

_Покрывает:_ R1.4, R2.5, R4.2, R4.4.

---

## Фича 4. Материал, косвенные расходы и overall-дашборд (R3)

**Повторяемость:** только чтение матрицы; teardown закупочных строк в конце набора.

### TC-API-MAT-01 — Привязанный материал свёрнут только в overall, не в строку
**Предусловия:** `PL_LINKED` (lineTotal = L).
1. `GET …/margin-matrix` → `dashboard.linkedMaterialCost == L`.
2. Ни одна `MarginRowDto` **не** содержит материальную стоимость в своём `factCost` (per-row — только труд).

_Покрывает:_ R3.1.

### TC-API-INDIRECT-01 — Косвенные отдельной суммой по категориям (+ uncategorized)
**Предусловия:** `PL_IND1` (категория A, I1), `PL_IND2` (без категории, I2).
1. `GET …/margin-matrix` → `dashboard.indirectCost == I1 + I2`.
2. `dashboard.indirectByCategory` содержит запись категории A с `amount == I1` и запись
   «uncategorized» (`categoryId == null`) с `amount == I2`.
3. Косвенные **не** входят ни в одну per-row маржу (строки не содержат косвенных).

_Покрывает:_ R3.2, R3.3.

### TC-API-DASH-01 — Итоги overall-дашборда
1. `GET …/margin-matrix` → dashboard.
2. `overallRevenue == Σ factRevenue(row)`.
3. `overallCost == Σ factCost(row) + linkedMaterialCost + indirectCost == Σ factCost + L + I1 + I2`.
4. `overallMarginAmount == overallRevenue − overallCost`; `overallMarginPct == amount / overallRevenue`
   (при `overallRevenue == 0` → `null`).
5. План-итоги: `planRevenue/planCost/planMarginAmount` равны суммам доступных план-строк (без `WORKITEM_C`).

_Покрывает:_ R3.4, R3.5.

---

## Фича 5. Флаг under-margin (R4.3)

**Повторяемость:** только чтение матрицы; teardown — на уровне набора.

### TC-API-UNDER-01 — Строка помечена under-margin при факт% существенно ниже план%
**Предусловия:** строка, где `planMarginPct` доступен и `factMarginPct` доступен, а
`planMarginPct − factMarginPct ≥ порог` (по умолчанию 0.05).
1. `GET …/margin-matrix` → строка `WORKITEM_A`.
2. Если условие выполнено → `underMargin == true`.
3. Для строки, где любой из pct недоступен (`WORKITEM_B` без отчётов, либо `WORKITEM_C` без цены) →
   `underMargin == false` (флаг не поднимается при недоступном pct).

_Покрывает:_ R4.3.

### TC-API-GATE-01 — `DRAFT` недоступно, `ACTIVE`/`COMPLETED` читаемо
**Предусловия:** `DRAFT_PROJECT_ID` (проект в `DRAFT`), `PROJECT_ID` (ACTIVE), `DONE_PROJECT_ID`
(COMPLETED, при наличии).
1. `GET /api/projects/{PROJECT_ID}/margin-matrix` (ACTIVE) → **200**.
2. `GET /api/projects/{DONE_PROJECT_ID}/margin-matrix` (COMPLETED) → **200** (идентичная read-модель).
3. Для `DRAFT` поверхность матрицы недоступна с фронта (вкладка не резолвится stage-резолвером);
   прямой API-вызов, если проект DRAFT, подтверждает, что бизнес-поверхность не предполагается на DRAFT
   (фиксируем фактический ответ в репорте).

_Покрывает:_ R6.4.

---

# Часть B. Браузерные (UI) сценарии (frontend)

**Среда:** `http://localhost:3000`, роль MANAGER (`TOKEN_MANAGER` / логин через UI), проект `PROJECT_ID`
в статусе `ACTIVE` с сидом из Setup. Исполняется браузерным движком.

**Повторяемость:** UI-сценарии только читают матрицу (write-контролов нет); данные засижены в Части A /
Setup под `run-id`; teardown — на уровне всего набора.

### TC-UI-GATE-01 — Вкладка `Margin` доступна на `ACTIVE`
**Предусловия:** логин MANAGER, открыт воркспейс `PROJECT_ID` (ACTIVE).
1. Открыть воркспейс проекта → в списке вкладок присутствует вкладка **Margin** (label `workspace.tab.margin`).
2. Кликнуть вкладку `Margin` → панель матрицы маржи загрузилась (не Placeholder), без сырых i18n-ключей.

_Покрывает:_ R5.1, R6.4, R6.5.

### TC-UI-GROUP-01 — Строки сгруппированы по категориям работ с подытогами
1. На панели `Margin` видны группы категорий (`Cat1`, `Cat2`) в порядке сметы.
2. Внутри `Cat1` — строки `WORKITEM_A`, `WORKITEM_B`; внутри `Cat2` — `WORKITEM_C`.
3. У каждой группы отображён подытог (plan/fact revenue / cost / margin).

_Покрывает:_ R4.1, R5.1.

### TC-UI-ROW-01 — Строка: план vs факт, кол-во, маржа
1. Строка `WORKITEM_A`: planned qty + unit; plan revenue / cost / margin (amount + pct); reported qty;
   fact revenue / cost / margin; report count = 2.
2. Числа совпадают с API-ответом из TC-API-ROW-01/02.

_Покрывает:_ R5.1.

### TC-UI-ROW-02 — Недоступные значения рендерятся как `—`
1. Строка `WORKITEM_C` (цена недоступна): plan cost / plan margin → локализованный `—`.
2. Строка `WORKITEM_B` (нет отчётов): fact margin pct → `—`; строка отображена с план-стороной.

_Покрывает:_ R5.5, R1.5, R1.6.

### TC-UI-UNDER-01 — Highlight under-margin
**Предусловия:** строка с `underMargin == true` (см. TC-API-UNDER-01).
1. Строка-under помечена визуально (badge «Ниже плана» + tooltip с дельтой `{deltaPct}`).
2. Строки без выполнения условия и строки с недоступным pct **не** помечены.

_Покрывает:_ R4.3, R5.1, R5.5.

### TC-UI-DETAIL-01 — Детализация по отчётам: сумма = факт-стоимость строки
1. В строке `WORKITEM_A` открыть disclosure → drawer детализации.
2. В drawer перечислены вклады-отчёты (`REP_A1`, `REP_A2`): воркер, тир worker type, reported qty,
   client unit price, revenue, derived tier cost.
3. Сумма derived tier cost по строкам drawer **равна** fact cost строки `WORKITEM_A`.

_Покрывает:_ R5.3, R4.2, R4.4, R1.3.

### TC-UI-DETAIL-02 — В детализации виден базовый тир и метка fallback
1. В drawer строки `WORKITEM_A` отчёт `REP_A2` (воркер без типа) помечен **base-tier fallback**
   (локализованная метка, например «Базовый тип (не назначен)»).
2. Отчёт `REP_A1` показывает назначенный тир WT_UPLIFT **без** метки fallback.

_Покрывает:_ R2.5, R4.2, R1.4.

### TC-UI-DASH-01 — Overall-дашборд: план vs факт, свёртка материала
1. Над матрицей — дашборд: plan vs fact revenue / cost / margin.
2. Split показывает labour / linked-material / indirect; `linkedMaterialCost` равен значению API.
3. Overall маржа совпадает с API (TC-API-DASH-01).

_Покрывает:_ R5.2, R3.1, R3.4.

### TC-UI-DASH-02 — Косвенные по категориям, включая uncategorized
1. В дашборде блок indirect-by-category содержит категорию A (`I1`) и bucket «uncategorized» (`I2`).
2. Косвенные **не** появляются ни в одной строке матрицы (только в overall).

_Покрывает:_ R3.2, R5.2.

### TC-UI-READONLY-01 — Панель read-only; на `COMPLETED` идентично
**Предусловия:** `PROJECT_ID` (ACTIVE) и `DONE_PROJECT_ID` (COMPLETED) с сопоставимым сидом.
1. На `ACTIVE`: панель `Margin` не содержит write-контролов (нет кнопок редактирования/создания/
   пересчёта).
2. Открыть вкладку `Margin` на `COMPLETED` проекте → рендер идентичен (history view), тоже read-only.

_Покрывает:_ R5.4.

---

# Регрессия

**Повторяемость:** только чтение существующих поверхностей под теми же `run-id`-сущностями; состояние
не меняется, teardown — общий в конце набора.

Матрица маржи — read-only проекция и ничего в исходных read-моделях не меняет. Регрессионная группа
перепроверяет, что интеграция не сломала поставщиков данных и ABAC.

### REG-01 — Поставщики данных живы (план / факт / закупки)
1. `GET` сметы проекта (FOR-05) → **200**, строки читаются как прежде.
2. `GET /api/work-reports/progress?projectId=PROJECT_ID` (FOR-06-01) → **200**, утверждённые отчёты на месте.
3. `GET` сверенных закупочных строк (FOR-06-03/04) → **200**, привязки/категории не изменились матрицей.

_Покрывает:_ R9.1 (регрессия FOR-05 / FOR-06-01 / FOR-06-03/04).

### REG-02 — ABAC `WORK_REPORTS` не изменён, нового ресурса/миграции нет
1. `GET /api/resources` → `WORK_REPORTS` присутствует; **нет** нового ресурса под матрицу.
2. Приложение стартовало без ошибки `PermissionAnnotationValidator` (контроллер классифицирован COMPLETE).
3. Changeset под эту спеку **не** добавлен (`changelog.xml` без новой записи margin-matrix).

_Покрывает:_ R6.1, R6.2.

### REG-03 — Паритет локалей и отсутствие сырых ключей (UI)
1. На панели `Margin` и в drawer детализации не отображается ни один сырой i18n-ключ (`marginMatrix.*`).
2. Переключение языка PL/RU меняет подписи колонок, дашборда и бейджа under-margin; оба файла локалей
   имеют одинаковый набор ключей (locale-parity).

_Покрывает:_ R7.2, R7.3.

---

# Шаблон MD-репорта прогона

## Часть A — API (docker-стек)

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SETUP-01 | 1 | POST /api/auth/login (ADMIN) | 200 + accessToken | | |
| 2 | TC-API-ROW-02 | 3 | GET /api/projects/{id}/margin-matrix | factCost = Σ по тиру каждого отчёта | | |
| 3 | TC-API-FALLBACK-01 | 2 | GET …/margin-matrix (деталь REP_A2) | baseTierFallback = true | | |
| 4 | TC-API-INDIRECT-01 | 2 | GET …/margin-matrix (dashboard) | indirectByCategory: A=I1, uncategorized=I2 | | |
| 5 | TC-API-DASH-01 | 3 | GET …/margin-matrix (dashboard) | overallCost = ΣfactCost + L + I1 + I2 | | |
| … | | | | | | |

## Часть B — UI (browser engine)

| # | Тест-кейс | Шаг | Действие | Ожидание | Факт | Статус |
|---|-----------|-----|----------|----------|------|--------|
| 1 | TC-UI-GATE-01 | 2 | Открыть вкладку Margin (ACTIVE) | Панель матрицы загружена, без сырых ключей | | |
| 2 | TC-UI-DETAIL-01 | 3 | Раскрыть детализацию WORKITEM_A | Σ derived cost = fact cost строки | | |
| 3 | TC-UI-DETAIL-02 | 1 | Деталь REP_A2 | Метка base-tier fallback видна | | |
| 4 | TC-UI-READONLY-01 | 2 | Вкладка Margin на COMPLETED | Рендер идентичен, read-only | | |
| … | | | | | | |

## Регрессия

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | REG-01 | 2 | GET /api/work-reports/progress | 200, отчёты на месте | | |
| 2 | REG-02 | 1 | GET /api/resources | WORK_REPORTS есть, нового ресурса нет | | |
| 3 | REG-03 | 1 | UI: панель Margin | Нет сырых i18n-ключей | | |

**Итог:** X пройдено / Y провалено / Z пропущено.
**Run-id:** `<timestamp/uuid>`.
**Стратегия повторяемости:** комбинированная — генератор `run-id` + teardown в конце набора.
**Окружение:** `docker compose up` (backend :8080, frontend :3000); ADMIN через
`FOREMEN_ADMIN_CREATE/EMAIL/PASSWORD`.
