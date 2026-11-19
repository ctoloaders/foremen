# Тест-кейсы: FOR-05-05c — Корректировка цены работ («%») как staged-правка

> Документ следует стандарту `.kiro/steering/test-cases.md`. Спека **полностековая** (bugfix):
> Часть A — API-тесты против поднятого в Docker приложения (`http://localhost:8080`),
> Часть B — сценарии браузерного движка на `http://localhost:3000/projects/{id}/estimate`.
> Результат прогона — **MD-репорты с таблицами** (шаг → ожидание → факт → статус), без скриншотов.
> Покрываемые требования: 1.1–1.3, 2.1–2.6, 3.1–3.3, 3.5–3.7 (bugfix.md).

## Суть исправления (что проверяем)

Контрол «%» (`LabourPriceAdjustControl`) больше не пишет в БД сразу. Он **стейджит** правку
`ADJUST_LABOUR_PRICE` (`scope` ∈ `PROJECT` / `WORK_CATEGORY` / `WORK_ITEM`, `targetId`, `pct`):

- правка видна в списке «Zmiany (N)»: PL «Korekta robocizny {{pct}}%: {{target}}»,
  RU «Корректировка работ {{pct}}%: {{target}}»; `target` — вся смета (PROJECT) / название
  категории работ (WORK_CATEGORY) / название работы (WORK_ITEM);
- работают undo / redo / discard / удаление одной записи; в БД ничего не пишется до Save;
- debounce-preview и Save проигрывают правку по порядку staged-набора: ячейки, созданные
  **до** корректировки, получают скорректированную цену; **новая строка (line), созданная после**
  корректировки, сохраняет исходную цену; пересекающиеся корректировки считаются от исходной цены
  (не накапливаются), **побеждает более поздняя**; `pct = 0` сбрасывает на исходную;
- применяется и к staged-only ячейкам (ещё не сохранённым); остальные ячейки и staged-правки
  других пользователей не сбрасываются; правка расшаривается через общий черновик FOR-05-13;
- при read-only матрице «%» недоступен;
- старый эндпоинт `POST /api/estimates/project/{projectId}/labour-price-adjustment` работает как
  раньше (для API-клиентов), помечен `@Deprecated`, UI им больше не пользуется.

Формула: `unit_price_override = round2(unit_price × (1 + pct/100))`, HALF_UP, scale 2;
в матрице `labour = override × volume`, `baseLabour = unit_price × volume`; `unit_price` и `value_net`
не меняются; материальные строки не затрагиваются.

## Окружение и запуск

### Docker-стек
- `docker compose up` в корне: `postgres` (:5432), `liquibase`, `backend` (:8080, профиль `docker`),
  `frontend` (:3000).
- Первый ADMIN: `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
- Auth — под `/api/auth` (`POST /api/auth/login` → access-токен; далее `Authorization: Bearer <token>`).
- Порядок деплоя при ручной проверке: сначала backend, затем frontend (старый backend не десериализует
  `kind = ADJUST_LABOUR_PRICE`).

### Эндпоинты в фокусе (`EstimateMatrixController`, `@RequestMapping("/api/estimates")`)
| Метод | Путь | ABAC `ESTIMATE` | Персистит? |
|-------|------|-----------------|-----------|
| GET  | `/api/estimates/project/{projectId}/matrix` | READ | нет |
| POST | `/api/estimates/project/{projectId}/preview` | READ | нет (откат транзакции) |
| POST | `/api/estimates/project/{projectId}/assignments` | **UPDATE** | да (батч-Save, одна транзакция) |
| POST | `/api/estimates/project/{projectId}/labour-price-adjustment` | **UPDATE** | да, сразу (deprecated) |
| GET  | `/api/estimates/project/{projectId}/collab` | READ | нет (снимок общего черновика) |

### Формат тела (`ApplyAssignmentsRequest` / `StagedEditPayload`)
`preview` и `assignments` принимают одно тело:
```json
{
  "edits": [
    { "kind": "ASSIGN", "workItemId": 11, "roomId": 21 },
    { "kind": "ADJUST_LABOUR_PRICE", "scope": "WORK_ITEM", "targetId": 11, "pct": 10 }
  ],
  "stagedIds": ["<client-staged-id>"]
}
```
- Поля `StagedEditPayload`: `kind`, `workItemId`, `roomId`, `roomQtyId`, `branch`, `typeId`,
  `materialLineId`, `materialId`, `packageCode`, `quantity`, `scope`, `targetId`, `pct`.
- `scope` ∈ `PROJECT | WORK_CATEGORY | WORK_ITEM`; `targetId` (id категории / работы) не нужен для
  `PROJECT`; `pct` — знаковое число (допустимы отрицательные и `0`).
- `stagedIds` (необязательно) — id записей общего черновика, которые коммитит Save.
- Устаревший эндпоинт: `{ "scope": "...", "targetId": <id|null>, "pct": <число> }`.
- Матрица (ответ): `groups[].workCategoryId`, `groups[].rows[]{ workItemId, unitPrice (эффективная),
  baseUnitPrice (исходная), cells[]{ workItemId, roomId, assigned, volume, labour, baseLabour } }`,
  `rooms[]`, `editable`.

### Стратегия повторяемости (обязательно, единая для документа)
- **Генератор данных.** `run-id` = UNIX-timestamp (`date +%s`). Проект `E2E 05c {run-id}`, пользователи
  `est.editor+{run-id}@example.com`, `est.editor2+{run-id}@example.com`,
  `est.reader+{run-id}@example.com`. Все сущности создаются через API внутри прогона.
- **Teardown.** После набора: Discard staged-правок в UI (или
  `localStorage.removeItem('foremen.estimateMatrix.<projectId>')`), удаление/архивация проекта
  `{run-id}` и деактивация созданных пользователей. Общий черновик очищается Save/Discard.
- Ручной чистки БД не требуется: каждый прогон работает в собственном проекте.

### Общий Setup (один раз на прогон)
1. ADMIN: `POST /api/auth/login` → `ADMIN_TOKEN`.
2. Создать пользователей editor (A), editor2 (B) с `ESTIMATE` READ+UPDATE и reader с `ESTIMATE` READ
   без UPDATE; назначить на проект.
3. Создать проект `E2E 05c {run-id}` в статусе **DRAFT** с ≥2 помещениями (`R1`, `R2`) с заполненными
   измерениями, чтобы объём работ был > 0.
4. В каталоге выбрать ≥2 категории работ (`CAT1`, `CAT2`), в `CAT1` ≥2 работы (`W1`, `W2`), в `CAT2`
   работу `W3`; у всех положительная цена. Сохранить id: `CAT1`, `CAT2`, `W1`, `W2`, `W3`, `R1`, `R2`.
5. Базовая смета (через `POST …/assignments`): `ASSIGN W1/R1`, `ASSIGN W2/R1`, `ASSIGN W3/R1`.
   Запомнить исходные цены `P(W)` = `baseUnitPrice` и объёмы `V(W,R)` из `GET …/matrix`.
6. Хелпер `exp(P, pct) = round2(P × (1 + pct/100))` (HALF_UP); ожидаемая `labour = exp × volume`.

---

# ЧАСТЬ A. API-сценарии (Docker, `http://localhost:8080`)

## Фича A1. Staged-правка через preview (без записи в БД)

### TC-API-01 — Preview применяет корректировку WORK_ITEM к сохранённой ячейке
**Предусловия:** базовая смета из Setup; токен A.
**Шаги:**
1. `POST /preview` с `edits: [{kind:"ADJUST_LABOUR_PRICE", scope:"WORK_ITEM", targetId:W1, pct:10}]`.
   _Ожидание:_ `200`; в `W1` `unitPrice = exp(P(W1),10)`, `baseUnitPrice = P(W1)`,
   `cells[R1].labour = exp(P(W1),10) × V(W1,R1)`, `baseLabour = P(W1) × V(W1,R1)`.
2. Проверить остальные работы в ответе.
   _Ожидание:_ `W2`, `W3` не изменились (`unitPrice = baseUnitPrice`) (2.1).
3. `GET /matrix`.
   _Ожидание:_ `200`; `W1.unitPrice` всё ещё равен `P(W1)` — preview ничего не записал (2.3, 2.4).

### TC-API-02 — Preview применяет корректировку к staged-only ячейке
**Предусловия:** базовая смета; `W1` ещё не назначена в `R2`.
**Шаги:**
1. `POST /preview` с `[ASSIGN W1/R2, ADJUST_LABOUR_PRICE{WORK_ITEM, W1, +10}]`.
   _Ожидание:_ `200`; ячейка `W1/R2` `assigned=true`, `labour = exp(P(W1),10) × V(W1,R2)` (1.2, 2.2).
2. Тот же набор в обратном порядке (`ADJUST…` затем `ASSIGN W1/R2`).
   _Ожидание:_ `W1` уже имеет строку (line) → `W1/R2` наследует скорректированный override строки;
   `labour = exp(P(W1),10) × V(W1,R2)` (добавление комнаты к существующей строке).
3. `GET /matrix`.
   _Ожидание:_ `W1/R2` `assigned=false` — в БД ничего нет.

### TC-API-03 — Порядок: новая строка после корректировки сохраняет исходную цену
**Предусловия:** базовая смета; работа `W4` из `CAT1` **не назначена** ни в одной комнате.
**Шаги:**
1. `POST /preview` с `[ADJUST_LABOUR_PRICE{PROJECT, null, +10}, ASSIGN W4/R1]`.
   _Ожидание:_ `200`; `W1..W3` скорректированы на +10 %; `W4` (новая line после корректировки)
   `unitPrice = baseUnitPrice = P(W4)` (D2.2).
2. `POST /preview` с `[ASSIGN W4/R1, ADJUST_LABOUR_PRICE{PROJECT, null, +10}]`.
   _Ожидание:_ `W4` тоже скорректирована: `exp(P(W4),10)` (D2.1).

### TC-API-04 — Пересекающиеся корректировки: от исходной цены, побеждает последняя
**Предусловия:** базовая смета.
**Шаги:**
1. `POST /preview` с `[PROJECT +10, WORK_ITEM W1 +5]`.
   _Ожидание:_ `W1` = `exp(P(W1),5)`; `W2`, `W3` = `exp(P,10)` (не накапливается: не `1.1×1.05`) (D2.4).
2. `POST /preview` с `[WORK_ITEM W1 +5, PROJECT +10]`.
   _Ожидание:_ все работы, включая `W1`, = `exp(P,10)`.
3. `POST /preview` с `[WORK_CATEGORY CAT1 +10, WORK_CATEGORY CAT1 +20]`.
   _Ожидание:_ `W1`, `W2` = `exp(P,20)` (не `1.1×1.2`).

### TC-API-05 — pct = 0 сбрасывает на исходную цену
**Предусловия:** базовая смета; на `W1` сохранён override +10 % (через TC-API-10).
**Шаги:**
1. `POST /preview` с `[WORK_ITEM W1 pct:0]`.
   _Ожидание:_ `W1.unitPrice = baseUnitPrice = P(W1)`, `labour = baseLabour` (2.2, 3.1).

### TC-API-06 — Отрицательный pct и округление HALF_UP
**Предусловия:** базовая смета.
**Шаги:**
1. `POST /preview` с `[PROJECT pct:-20]`.
   _Ожидание:_ у всех работ `unitPrice = exp(P,-20)`.
2. `POST /preview` с `[WORK_ITEM W1 pct:0.33]` (scale 2).
   _Ожидание:_ `unitPrice` равен `round2(P(W1)×1.0033)` HALF_UP (сверить расчётом клиента теста).

### TC-API-07 — Область WORK_CATEGORY затрагивает только свою категорию
**Предусловия:** базовая смета.
**Шаги:**
1. `POST /preview` с `[WORK_CATEGORY CAT1 +10]`.
   _Ожидание:_ `W1`, `W2` скорректированы; `W3` (CAT2) без изменений (2.1).
2. `POST /preview` с `[WORK_CATEGORY <несуществующий id> +10]`.
   _Ожидание:_ `200`, матрица без изменений (неизвестная цель — no-op, не ошибка).

### TC-API-08 — Остальные staged-правки в наборе не меняются
**Предусловия:** базовая смета.
**Шаги:**
1. `POST /preview` с `[ASSIGN W1/R2, SET_QUANTITY W2/R1 quantity:7]`.
   Запомнить ответ `M0`.
2. `POST /preview` с теми же правками + `[WORK_ITEM W3 +10]`.
   _Ожидание:_ ячейки `W1/R2` и `W2/R1` (`volume = 7`, `volumeOverridden=true`) идентичны `M0`;
   меняется только `W3` (2.1, 3.5).

## Фича A2. Save: персистенция override

### TC-API-09 — Save корректировки сохраняет `unit_price_override`
**Предусловия:** базовая смета; токен A.
**Шаги:**
1. `POST /assignments` с `[WORK_CATEGORY CAT1 +10]`.
   _Ожидание:_ `200`; в ответе `W1`, `W2`: `unitPrice = exp(P,10)`, `baseUnitPrice = P`; `W3` без изменений.
2. `GET /matrix`.
   _Ожидание:_ те же значения; `baseLabour` не изменился (`unit_price`/`value_net` на исходной цене) (3.1, 3.2).
3. (Опционально, БД) `SELECT unit_price, unit_price_override FROM estimate_lines …`.
   _Ожидание:_ `unit_price` исходный, `unit_price_override = round2(unit_price × 1.10)`; материальные
   строки не тронуты (3.1).

### TC-API-10 — Save смешанного набора: assign + quantity + корректировка
**Предусловия:** базовая смета.
**Шаги:**
1. `POST /preview` с `[ASSIGN W1/R2, SET_QUANTITY W1/R2 quantity:5, WORK_CATEGORY CAT1 +10]` → ответ `M_prev`.
2. `POST /assignments` с тем же набором → ответ `M_save`.
   _Ожидание:_ `M_save` совпадает с `M_prev` по `labour`/`unitPrice` всех ячеек;
   `W1/R2.labour = exp(P(W1),10) × 5` (2.4).
3. `GET /matrix`.
   _Ожидание:_ равен `M_save`.

### TC-API-11 — Save не затрагивает корректировку, если её нет в наборе (preservation)
**Предусловия:** на `W1` сохранён override +10 %.
**Шаги:**
1. `POST /assignments` с `[ASSIGN W2/R2]`.
   _Ожидание:_ `200`; `W1.unitPrice` по-прежнему `exp(P(W1),10)` (override не сброшен) (3.4, 3.5).

### TC-API-12 — Эквивалентность staged-пути и устаревшего эндпоинта
**Предусловия:** два одинаковых проекта (или откат через `pct:0` между шагами).
**Шаги:**
1. Проект 1: `POST /assignments` с `[WORK_ITEM W1 +12.5]`.
2. Проект 2: `POST /labour-price-adjustment` с `{scope:"WORK_ITEM", targetId:W1, pct:12.5}`.
   _Ожидание:_ `unitPrice` у `W1` совпадает в обоих проектах (3.1, 3.7).

## Фича A3. Валидация и ошибки

### TC-API-13 — Невалидная правка → 400 `error.validation` (preview и Save)
**Предусловия:** базовая смета; запомнить `unitPrice` всех работ.
**Шаги (каждая строка — отдельный запрос в `preview` и затем в `assignments`):**
1. `[{kind:"ADJUST_LABOUR_PRICE", pct:10}]` — нет `scope`.
2. `[{kind:"ADJUST_LABOUR_PRICE", scope:"WORK_ITEM", pct:10}]` — нет `targetId`.
3. `[{kind:"ADJUST_LABOUR_PRICE", scope:"WORK_CATEGORY", pct:10}]` — нет `targetId`.
4. `[{kind:"ADJUST_LABOUR_PRICE", scope:"PROJECT"}]` — нет `pct`.
5. `[{kind:"ADJUST_LABOUR_PRICE", scope:"BAD", pct:10}]` — неизвестный `scope`.
   _Ожидание для 1–4:_ HTTP `400`, код ошибки `error.validation`. Для 5: `400` (ошибка разбора enum).
6. После каждого `assignments` с ошибкой — `GET /matrix`.
   _Ожидание:_ значения не изменились (Save атомарен: ни одной частичной записи) (3.3, D4).
7. Набор `[ASSIGN W1/R2, ADJUST(без scope)]` в `assignments`.
   _Ожидание:_ `400`; `W1/R2` **не** создана (откат всей транзакции).

### TC-API-14 — Не-DRAFT проект → 409 `error.estimate.locked`
**Предусловия:** проект `{run-id}` переведён из DRAFT в следующий статус (согласно жизненному циклу проекта).
**Шаги:**
1. `POST /assignments` с `[WORK_ITEM W1 +10]`.
   _Ожидание:_ HTTP `409`, `error.estimate.locked`; в БД ничего не изменилось (3.3).
2. `POST /labour-price-adjustment` с `{scope:"PROJECT", pct:10}`.
   _Ожидание:_ `409`, `error.estimate.locked`.
3. `GET /matrix`.
   _Ожидание:_ `200`, `editable=false`.
4. Teardown: вернуть/создать DRAFT-проект для следующих кейсов.

### TC-API-15 — Нет права `ESTIMATE` UPDATE → 403
**Предусловия:** токен reader (READ без UPDATE).
**Шаги:**
1. `POST /assignments` с `[WORK_ITEM W1 +10]`.
   _Ожидание:_ `403`; данные не изменены (3.3).
2. `POST /preview` с тем же набором.
   _Ожидание:_ `200` (preview — READ; ничего не персистит).
3. `POST /labour-price-adjustment` с `{scope:"PROJECT", pct:10}`.
   _Ожидание:_ `403`.
4. Запрос без токена.
   _Ожидание:_ `401`.

## Фича A4. Устаревший эндпоинт `labour-price-adjustment`

### TC-API-16 — Deprecated эндпоинт работает без изменений
**Предусловия:** базовая смета; токен A.
**Шаги:**
1. `POST /labour-price-adjustment` с `{scope:"WORK_ITEM", targetId:W1, pct:10}`.
   _Ожидание:_ `200`; тело — матрица (`editable=true`), `W1.unitPrice = exp(P(W1),10)` (3.7).
2. `GET /matrix`.
   _Ожидание:_ override сохранён сразу (запись без Save); `baseUnitPrice = P(W1)`.
3. `POST /labour-price-adjustment` с `{scope:"WORK_ITEM", targetId:W1, pct:0}`.
   _Ожидание:_ `W1.unitPrice = P(W1)` (сброс).
4. Негатив: `{scope:"WORK_ITEM", pct:10}` (нет `targetId`), `{scope:"PROJECT"}` (нет `pct`).
   _Ожидание:_ `400 error.validation`.
5. Проверка «не видит staged»: `POST /preview` с `[ASSIGN W1/R2]` не затрагивается прямым вызовом —
   прямой вызов корректирует только персистентные строки (задокументированное ограничение, 1.2).

## Фича A5. Общий черновик (collab) на уровне API

### TC-API-17 — Снимок черновика содержит правку нового вида
**Предусловия:** пользователи A и B на одном проекте, collab включён. STOMP-клиент или UI A
добавляет в общий черновик `ADJUST_LABOUR_PRICE` (см. TC-UI-17).
**Шаги:**
1. `GET /collab` токеном B.
   _Ожидание:_ `200`; черновик содержит запись `kind=ADJUST_LABOUR_PRICE` с `scope`, `targetId`, `pct`,
   в порядке `seq`, вместе с остальными записями (2.5).
2. A выполняет Save с `stagedIds` этой записи.
   _Ожидание:_ после Save `GET /collab` токеном B не содержит эту запись, но содержит записи, добавленные
   другими редакторами во время Save (не потеряны).

### Teardown Части A
1. `pct:0` не требуется: проект `{run-id}` удаляется/архивируется целиком.
2. Деактивировать пользователей `{run-id}`.

---

# ЧАСТЬ B. UI-сценарии (браузерный движок, `http://localhost:3000/projects/{id}/estimate`)

**Общее:** каждый шаг — действие в браузере (переход, ввод, клик, ожидание). Селекторы (`data-testid`):
- проект: `estimate-labour-adjust-project-open|-input|-apply|-cancel|-popover`;
- категория: `estimate-labour-adjust-group-{workCategoryId}-open|-input|-apply`;
- работа: `estimate-labour-adjust-work-{workItemId}-open|-input|-apply`;
- список правок: `estimate-changes-open` (кнопка «Zmiany (N)»), `estimate-changes-list`;
- шапка: `estimate-undo`, `estimate-redo`, `estimate-discard`, `estimate-save`, `estimate-unsaved`;
- collab: `estimate-collab-bar`, `estimate-collab-user-{userId}`, `estimate-cell-collab-staged`;
- ячейка: Cell_Report `estimate-cell-report-labour`.

Язык интерфейса: PL (повторить ключевые кейсы TC-UI-01 и TC-UI-02 в RU).
**Setup:** пользователь A (editor) залогинен; проект DRAFT из общего Setup; staged-правки в
`localStorage` очищены (Discard) перед каждым кейсом.

## Фича B1. Staging корректировки на трёх областях

### TC-UI-01 — «%» на уровне проекта стейджит правку, а не пишет в БД
**Предусловия:** базовая смета; staged пуст.
**Шаги:**
1. Открыть `/projects/{id}/estimate`.
   _Ожидание:_ матрица отрисована, `Zmiany` отключена/(0), `estimate-unsaved` отсутствует.
2. Кликнуть `estimate-labour-adjust-project-open`, ввести `10`, нажать `…-apply`.
   _Ожидание:_ поповер закрылся; без индикатора busy и без ошибок (2.3).
3. Дождаться debounce-preview.
   _Ожидание:_ у всех назначенных работ `labour` выросла на 10 % (значения совпадают с `exp(P,10) × V`);
   у работ появился бейдж надбавки (`unitPrice ≠ baseUnitPrice`) (2.2).
4. Открыть `estimate-changes-open`.
   _Ожидание:_ «Zmiany (1)»; запись «Korekta robocizny 10%: <вся смета>» (RU:
   «Корректировка работ 10%: <вся смета>») (2.3).
5. Проверить сеть.
   _Ожидание:_ вызова `POST …/labour-price-adjustment` **нет**; есть `POST …/preview`
   (с `kind: "ADJUST_LABOUR_PRICE"`) (2.3, 3.6).
6. `GET /api/estimates/project/{id}/matrix` (API-вызовом).
   _Ожидание:_ `unitPrice` не изменились — в БД ничего нет до Save (2.3).

### TC-UI-02 — «%» на категории работ
**Предусловия:** базовая смета.
**Шаги:**
1. Развернуть группу `CAT1`; в заголовке группы нажать `estimate-labour-adjust-group-{CAT1}-open`,
   ввести `-15`, применить.
   _Ожидание:_ работы `W1`, `W2` пересчитаны на −15 %; `W3` (CAT2) без изменений (2.1).
2. Открыть «Zmiany».
   _Ожидание:_ запись «Korekta robocizny -15%: <название CAT1>» (RU: «Корректировка работ -15%: …»).

### TC-UI-03 — «%» на одной работе
**Предусловия:** базовая смета.
**Шаги:**
1. В строке `W2` нажать `estimate-labour-adjust-work-{W2}-open`, ввести `5`, применить.
   _Ожидание:_ меняется только `W2` (все её ячейки); остальные без изменений.
2. Открыть «Zmiany».
   _Ожидание:_ запись «Korekta robocizny 5%: <название W2>».

### TC-UI-04 — Валидация ввода в поповере
**Предусловия:** базовая смета.
**Шаги:**
1. Открыть поповер проекта, ввести пустое значение / `abc`.
   _Ожидание:_ кнопка `…-apply` отключена.
2. Ввести `10`, нажать `…-cancel`.
   _Ожидание:_ правка не создана; «Zmiany» без изменений.
3. Ввести `10` и нажать Enter.
   _Ожидание:_ правка застейджена (как по Apply).

## Фича B2. Undo / redo / discard / удаление записи / Save

### TC-UI-05 — Undo и redo корректировки
**Предусловия:** застейджена корректировка из TC-UI-01.
**Шаги:**
1. Нажать `estimate-undo`.
   _Ожидание:_ запись исчезла из «Zmiany», значения вернулись к исходным (`labour = baseLabour`),
   бейдж надбавки пропал (2.3, 2.6).
2. Нажать `estimate-redo`.
   _Ожидание:_ запись и +10 % вернулись.
3. Проверить БД через `GET /matrix`.
   _Ожидание:_ `unitPrice` не менялся на всех шагах (2.6).

### TC-UI-06 — Discard
**Предусловия:** застейджены корректировка и ещё одна правка (например, ASSIGN `W1/R2`).
**Шаги:**
1. Нажать `estimate-discard`, подтвердить.
   _Ожидание:_ «Zmiany» пусты, матрица равна сохранённому состоянию, `estimate-unsaved` пропал (2.6).
2. Перезагрузить страницу.
   _Ожидание:_ правки не восстановились.

### TC-UI-07 — Удаление одной записи из «Zmiany»
**Предусловия:** застейджены ASSIGN `W1/R2` и затем корректировка PROJECT +10.
**Шаги:**
1. Открыть «Zmiany», удалить запись корректировки.
   _Ожидание:_ остаётся 1 запись (ASSIGN); цены вернулись к исходным; ячейка `W1/R2` остаётся (2.3).
2. Повторить: удалить запись ASSIGN, оставив корректировку.
   _Ожидание:_ корректировка остаётся и применяется к сохранённым строкам.

### TC-UI-08 — Save персистит корректировку вместе с остальными правками
**Предусловия:** застейджены ASSIGN `W1/R2` и CAT1 +10.
**Шаги:**
1. Нажать `estimate-save` (при предупреждении offer-impact подтвердить `estimate-offer-impact-confirm`).
   _Ожидание:_ Save прошёл, «Zmiany» пусты, `estimate-unsaved` пропал; в ответе `…/assignments`
   значения как в preview (2.4).
2. Обновить страницу.
   _Ожидание:_ скорректированные цены и ячейка `W1/R2` сохранились; бейдж надбавки виден.
3. `GET /matrix`.
   _Ожидание:_ `unitPrice = exp(P,10)`, `baseUnitPrice = P` (3.1, 3.2).

### TC-UI-09 — Сброс `pct = 0`
**Предусловия:** на `W1` сохранён override +10 % (TC-UI-08).
**Шаги:**
1. Для `W1` применить `0`.
   _Ожидание:_ в «Zmiany» запись «Korekta robocizny 0%: <W1>»; `labour = baseLabour`, бейдж пропал.
2. Save.
   _Ожидание:_ в БД `unit_price_override = unit_price` (`GET /matrix`: `unitPrice = baseUnitPrice`) (2.2).

## Фича B3. Staged-only ячейки, порядок, остальные ячейки не сбрасываются

### TC-UI-10 — Корректировка применяется к staged-only ячейке (баг 1.2)
**Предусловия:** базовая смета; `W1` не назначена в `R2`.
**Шаги:**
1. Кликнуть пустую ячейку `W1/R2` (ASSIGN, staged).
   _Ожидание:_ ячейка назначена, цена исходная.
2. Применить `estimate-labour-adjust-work-{W1}` `+10`.
   _Ожидание:_ после preview `labour` ячейки `W1/R2` = `exp(P(W1),10) × V(W1,R2)` (2.2).
3. Открыть Cell_Report ячейки.
   _Ожидание:_ `estimate-cell-report-labour` совпадает с матрицей.
4. Save → обновить страницу.
   _Ожидание:_ значения сохранены.

### TC-UI-11 — Порядок: строка, созданная после корректировки, сохраняет исходную цену
**Предусловия:** `W4` (CAT1) не назначена нигде.
**Шаги:**
1. Применить PROJECT +10.
2. Назначить `W4/R1`.
   _Ожидание:_ `W4` — исходная цена (бейджа нет), остальные +10 % (D2.2).
3. Открыть «Zmiany».
   _Ожидание:_ порядок записей: корректировка, затем ASSIGN.
4. Undo (ASSIGN) → применить заново ASSIGN `W4/R1` **до** корректировки (сначала undo корректировки,
   назначить, применить PROJECT +10).
   _Ожидание:_ теперь `W4` тоже +10 %.

### TC-UI-12 — Остальные staged-ячейки не сбрасываются (баг 1.1)
**Предусловия:** базовая смета.
**Шаги:**
1. Застейджить: ASSIGN `W1/R2`; изменить объём `W2/R1` на `7` (SET_QUANTITY).
   _Ожидание:_ обе ячейки показывают staged-значения.
2. Применить PROJECT +10; следить за матрицей во время и после preview.
   _Ожидание:_ ячейки `W1/R2` и `W2/R1` не «мигают» к прежним значениям и не сбрасываются;
   `W2/R1.volume = 7`, `volumeOverridden`; меняется только цена (2.1).
3. Проверить «Zmiany».
   _Ожидание:_ 3 записи.

### TC-UI-13 — Пересекающиеся корректировки: побеждает более поздняя
**Предусловия:** базовая смета.
**Шаги:**
1. PROJECT +10, затем `W1` +5.
   _Ожидание:_ `W1` = `exp(P,5)`; остальные = `exp(P,10)` (не накапливается) (D2.4).
2. Undo последней.
   _Ожидание:_ `W1` снова `exp(P,10)`.
3. Discard → `W1` +5, затем PROJECT +10.
   _Ожидание:_ все = `exp(P,10)`.

### TC-UI-14 — Правка сохраняется в `localStorage` и восстанавливается
**Предусловия:** базовая смета.
**Шаги:**
1. Применить CAT1 +10 (без Save), перезагрузить страницу.
   _Ожидание:_ запись «Zmiany (1)» восстановлена, preview пересчитан, цены +10 %.
2. Discard (teardown).

## Фича B4. Read-only

### TC-UI-15 — «%» недоступен при read-only матрице
**Предусловия (вариант 1):** залогинен reader (нет `ESTIMATE` UPDATE). **(вариант 2):** проект не в DRAFT.
**Шаги:**
1. Открыть `/projects/{id}/estimate`.
   _Ожидание:_ матрица видна, `editable=false`.
2. Проверить `estimate-labour-adjust-project-open`, `…-group-…-open`, `…-work-…-open`.
   _Ожидание:_ контролы отключены/не активируют правку; «Zmiany» остаются пустыми (3.6).
3. Попытка кликнуть/применить.
   _Ожидание:_ правка не создаётся, запросов на запись нет.

## Фича B5. Совместное редактирование (две браузерные сессии)

**Setup для B5:** два независимых контекста браузера — сессия 1 (пользователь A) и сессия 2
(пользователь B), оба на `/projects/{id}/estimate` одного проекта в DRAFT, collab включён.
Teardown: Discard в обеих сессиях (или Save), закрытие контекстов.

### TC-UI-16 — Присутствие и общий черновик
**Предусловия:** A и B открыли вкладку.
**Шаги:**
1. В обеих сессиях проверить `estimate-collab-bar`.
   _Ожидание:_ виден второй пользователь (`estimate-collab-user-{id}`) (2.5).

### TC-UI-17 — A корректирует цену, пока у B есть staged-ячейки: ячейки B не сбрасываются
**Предусловия:** TC-UI-16; в сессии 2 B застейджил ASSIGN `W1/R2` и SET_QUANTITY `W2/R1 = 7`.
**Шаги:**
1. Сессия 1 (A): убедиться, что на ячейках B виден маркер `estimate-cell-collab-staged`.
   _Ожидание:_ правки B видны A.
2. A применяет PROJECT +10.
   _Ожидание в сессии 1:_ цены +10 %, ячейки B сохраняют staged-значения, запись в «Zmiany» (2.1, 2.5).
3. Сессия 2 (B): дождаться доставки (≤ нескольких секунд).
   _Ожидание:_ в «Zmiany» B появилась запись «Korekta robocizny 10%: …» (чужая правка) **вместе** со
   своими двумя; собственные ячейки B (`W1/R2`, `W2/R1 = 7`) **не сброшены**; цены +10 %, в том числе
   у `W1/R2` (staged-only, ASSIGN до корректировки) (2.1, 2.5).
4. B не делает Save; A отменяет свою корректировку (`estimate-undo`).
   _Ожидание:_ у B запись пропала, цены вернулись к исходным, свои staged-ячейки на месте.

### TC-UI-18 — B корректирует цену, пока у A есть staged-ячейки (симметрия)
**Предусловия:** у A застейджены ASSIGN `W3/R2` и CAT1 не тронут; у B пусто.
**Шаги:**
1. B применяет `W3` +10 (`estimate-labour-adjust-work-{W3}`).
   _Ожидание:_ у A в «Zmiany» появляется запись «Korekta robocizny 10%: <W3>»; ячейка `W3/R2` у A
   (staged) получает +10 %, её staged-состояние не сброшено (2.5).
2. A делает Save.
   _Ожидание:_ в БД сохранены ASSIGN `W3/R2` и override `W3` +10 % (все ячейки `W3`);
   `…/assignments` ушёл с `stagedIds` обеих записей.
3. Сессия 2 (B): дождаться события SAVED.
   _Ожидание:_ у B «Zmiany» очищены, матрица обновлена из БД, значения совпадают с A.

### TC-UI-19 — Save одним пользователем не теряет правки, добавленные другим во время Save
**Предусловия:** A и B с общим черновиком; A готов к Save.
**Шаги:**
1. A нажимает Save; почти одновременно B добавляет корректировку `W2` +5.
   _Ожидание:_ после Save у A/B в черновике остаётся только запись B (если она не вошла в `stagedIds`
   Save A) и она не потеряна (2.5).
2. Teardown: B применяет Discard.

---

# Регрессия

Регрессионные кейсы перепроверяют ранее закрытые баги и критичные сквозные пути (3.x).

### TC-REG-01 — Устойчивость override к конкурентной записи (FOR-05-07 Round 5, `@DynamicUpdate`) — 3.4
**Шаги:**
1. Сохранить override +10 % на `W1` (Save, TC-API-09).
2. Выполнить `POST /assignments` с другим набором (`[ASSIGN W2/R2]`), который перерасчитывает смету.
   _Ожидание:_ `W1.unitPrice` остался `exp(P(W1),10)`.
3. Параллельно (2 клиента) отправить Save корректировки и Save с `ASSIGN` в другую ячейку.
   _Ожидание:_ оба `200`; итоговый override `W1` сохранён.

### TC-REG-02 — Прочие staged-виды без корректировки не изменились — 3.5
**Шаги:**
1. Выполнить preview/Save для наборов: ASSIGN, UNASSIGN, SET_QUANTITY, ADD/REMOVE_MATERIAL,
   CHOOSE_CONCRETE, APPLY_PACKAGE, APPLY_CHEAPEST (по одному набору).
   _Ожидание:_ результат идентичен ожидаемому по спекам FOR-05-04/05 (цены без изменений);
   undo/redo/discard в UI работают как раньше.

### TC-REG-03 — Offer snapshot читает `unit_price_override` (FOR-05-07) — 3.2
**Шаги:**
1. Сохранить корректировку PROJECT +10 в DRAFT-проекте.
2. `GET /api/estimates/project/{id}/offer-impact`.
   _Ожидание:_ `200`, `impact` ∈ `NONE|WITHDRAW|REVISE` по правилам FOR-05-07.
3. Сформировать/просмотреть оффер (по сценарию FOR-05-07).
   _Ожидание:_ в оффере труд посчитан по `unit_price_override`, а не по `unit_price`.

### TC-REG-04 — `value_net` и `unit_price` не меняются — 3.2
**Шаги:**
1. До и после Save корректировки сравнить `baseUnitPrice` и `baseLabour` в `GET /matrix`
   (и `unit_price`, `value_net` в БД).
   _Ожидание:_ без изменений; меняются только `unitPrice`/`labour`.

### TC-REG-05 — Материальные строки не затрагиваются — 3.1
**Шаги:**
1. Запомнить `materials[]` ячейки `W1/R1`; применить PROJECT +10 и Save.
   _Ожидание:_ `materials[]` идентичны (цены/количества не изменились).

### TC-REG-06 — Старый эндпоинт для API-клиентов — 3.7
**Шаги:**
1. Выполнить TC-API-16 после релиза.
   _Ожидание:_ без отличий в поведении; UI-трафик не содержит обращений к нему (TC-UI-01 шаг 5).

---

# Шаблон MD-репорта прогона

**Run-id:** `<timestamp>` · **Стратегия повторяемости:** генератор + teardown · **Стек:** `docker compose up`,
backend `http://localhost:8080`, frontend `http://localhost:3000` · **Дата:** `<YYYY-MM-DD>` ·
**Билд/коммит:** `<sha backend>` / `<sha frontend>` · **Язык UI:** PL/RU

## Часть A — API

| # | Тест-кейс | Шаг | Запрос | Ожидание | Факт | Статус |
|---|-----------|-----|--------|----------|------|--------|
| 1 | TC-API-01 | 1 | POST /api/estimates/project/{id}/preview [ADJUST_LABOUR_PRICE WORK_ITEM W1 +10] | 200, W1.unitPrice = exp(P,10) | | ✅/❌ |
| 2 | TC-API-13 | 2 | POST /api/estimates/project/{id}/assignments [без targetId] | 400 error.validation | | ✅/❌ |
| 3 | TC-API-14 | 1 | POST /api/estimates/project/{id}/assignments (не DRAFT) | 409 error.estimate.locked | | ✅/❌ |

## Часть B — UI

| # | Тест-кейс | Шаг | Действие | Ожидание | Факт | Статус |
|---|-----------|-----|----------|----------|------|--------|
| 1 | TC-UI-01 | 2 | Клик «%» проекта → 10 → Apply | Правка застейджена, нет вызова labour-price-adjustment | | ✅/❌ |
| 2 | TC-UI-17 | 3 | Сессия B: ожидание доставки | Запись A в «Zmiany» B, собственные ячейки B не сброшены | | ✅/❌ |

## Регрессия

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-REG-01 | 2 | POST /assignments [ASSIGN W2/R2] | override W1 сохранён | | ✅/❌ |

## Итог

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`. Стратегия повторяемости: `<генератор|teardown>`.

| Часть | Пройдено | Провалено | Пропущено |
|-------|----------|-----------|-----------|
| A — API | | | |
| B — UI | | | |
| Регрессия | | | |
| **Всего** | | | |

**Найденные дефекты:** `<ID кейса — описание — серьёзность>`
**Teardown выполнен:** ☐ проект `{run-id}` удалён/архивирован ☐ пользователи деактивированы ☐ staged в `localStorage` очищен ☐ контексты браузера закрыты
