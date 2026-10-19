# Тест-кейсы: FOR-06-04 — Reconciled purchases list (сверка закупок)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерный экран**, поэтому набор тест-кейсов включает **оба типа**:

- **Часть A. API-тесты** — против поднятого в Docker приложения (`http://localhost:8080`). Каждый
  шаг — HTTP-запрос к `Purchases_API` (`/api/procurement-lines`), с проверкой статуса, тела и
  заголовков. Покрывают: ручное переназначение (`relink`) на плановый / неплановый материал,
  очистку сопоставления (`clearMatch`), классификацию неплановой статьи (`classify`) на unmatched /
  отклонение на matched / неизвестной категории, снятие классификации (`clearClassification`),
  фильтрацию сверочного списка (`list`), стадийный гейт (`ACTIVE`-only), ABAC-гейт `PURCHASES`
  (UPDATE/READ), сохранение ручной сверки при переобработке документа (`reprocess`) и аудит.
- **Часть B. Браузерный (UI) сценарий** — против фронтенда (`http://localhost:3000`), исполняется
  браузерным движком (headless-браузер / browser automation) под ролью с CRUD-грантом `PURCHASES`
  (ADMIN / MANAGER / FINANCIER / FOREMAN) и, где требуется проверка read-only, под ESTIMATOR. Экран:
  вкладка `Purchases` воркспейса `ACTIVE`-проекта (сверочный список → re-link → classify → read-only
  гейтинг → бейджи состояния сопоставления и тира).

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание →
факт → статус). **Скриншоты не требуются.**

### Независимость от живого OCR-провайдера (важно)

В docker-стеке OCR-провайдер (Google Cloud Vision + LLM) **обычно не сконфигурирован**
(`foremen.ocr.enabled=false` → `DisabledOcrService`, см. FOR-06-03). Эта спека **сверяет
сопоставление, а не извлечённые данные**, поэтому её сценарии **не зависят** от живого OCR:

- строки закупки (`procurement_lines`) **засеиваются напрямую** в БД (или через FOR-06-03
  disabled-OCR документ, которому строки добавлены засеиванием), со значениями `rawName`,
  `quantity`, `unitHint`, `unitPrice`, `lineTotal`, `manufacturerHint`, начальным `matchStatus`
  (`AUTO`/`UNMATCHED`) и `matchTier`. Это нормальная подготовка данных, а не дефект;
- сценарий **переобработки** (`reprocess`, Фича 7) проверяет чистую партицию
  `ReprocessLinePartitioner` (preserve `MANUAL_*` / replace `AUTO`/`UNMATCHED`): он валиден и при
  disabled-OCR, т.к. набор свежих строк может быть пустым (replace-набор просто удаляется, preserve
  сохраняется и перенумеровывается). Детерминированное извлечение/матчинг свежих строк — зона
  FOR-06-03 и здесь не проверяется.

### Покрытие требований

| Кейс / группа | Покрываемые требования |
|---------------|------------------------|
| Фича 1 (re-link на плановый материал) | R2.1, R2.3, R2.4 |
| Фича 2 (re-link отклонения) | R2.2, R2.5 |
| Фича 3 (классификация неплановой статьи) | R3.1, R3.2, R3.3, R3.4, R3.5 |
| Фича 4 (сверочный список + фильтры) | R4.1, R4.2, R4.3, R4.4 |
| Фича 5 (инварианты строки) | R1.3, R1.4 |
| Фича 6 (ABAC `PURCHASES` + стадийный гейт) | R2.4, R3.5, R7.1, R7.4 |
| Фича 7 (переобработка сохраняет ручную сверку) | R5.1, R5.2, R5.3 |
| Фича 8 (аудит) | R10.1 |
| Часть B (UI-панель) | R6.1, R6.2, R6.3, R6.4, R6.5, R7.4 |
| Регрессия | R10.2, R7.2, R1.1 |

Соответствие «кейс ↔ требование» указано в каждом кейсе строкой `_Покрывает:_`.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции,
   включая changesets `157`/`158` — словарь `UnplannedExpenseCategory`; `167`–`169` — FOR-06-03
   `procurement_documents` / `procurement_lines` / seed ресурса `PURCHASES`; `170` —
   `procurement_lines.unplanned_category_id`), `backend` (:8080, профиль `docker`), `frontend`
   (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN — через переменные окружения `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`,
   `FOREMEN_ADMIN_PASSWORD`. Логин: `POST /api/auth/login {email, password}` → `accessToken`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном (проверки: приложение
   поднялось без ошибки `PermissionAnnotationValidator`; `GET /api/unplanned-expense-categories`
   отвечает 200 с пятью засеянными строками FOR-06-00; `GET /api/procurement-lines?projectId=<id>`
   под ADMIN отвечает 200).

## Общая стратегия повторяемости

**Комбинированная: генератор `run-id` + teardown.** Единый на прогон `run-id` (timestamp или uuid,
например `1730000000` или `a1b2c3`) встраивается во все создаваемые идентификаторы, чтобы два
последовательных прогона на одной БД не конфликтовали:

- email тестовых пользователей по ролям: `fin+{run-id}@example.com`, `mgr+{run-id}@example.com`,
  `fman+{run-id}@example.com`, `est+{run-id}@example.com`;
- имена проектов: `Purchases Test {run-id}`;
- raw-имена засеянных строк: `Cement {run-id}`, `Paint {run-id}`, `Fuel {run-id}`.

**Teardown каждой группы** выполняется в конце набора в обратном порядке создания:
`DELETE /api/procurement-documents/{id}` для каждого созданного документа (каскадно удаляет строки
через `ON DELETE CASCADE`), затем деактивация/удаление созданных пользователей админ-API
(`PATCH`/`DELETE /api/users/{id}`), затем перевод тестовых проектов в `CANCELLED` или их удаление
доступным admin-API. Сид-данные (роли, ресурс `PURCHASES`, гранты, словарь
`UnplannedExpenseCategory`, changesets 157/158/167–170) **только читаются, никогда не изменяются**.
Каждая группа ниже дополнительно уточняет свою стратегию строкой `**Повторяемость:**`.

### Засеивание строк и сброс состояния сверки

Строки закупки засеиваются напрямую в `procurement_lines` (или через FOR-06-03 документ с
дозасеянными строками) в начальном автоматическом состоянии (`AUTO`/`UNMATCHED`). Так как
`relink`/`clearMatch`/`classify` меняют состояние строки, для **повторяемости без ручной чистки**
каждый API-кейс, меняющий строку:

- либо работает на **свежезасеянной** на этот прогон строке (помеченной `run-id`), которую группа
  удаляет в teardown вместе с документом;
- либо начинается с явного шага-сброса (например `DELETE /{id}/match` → вернуть в `MANUAL_UNMATCHED`
  / пере-засеять), зафиксированного в предусловиях кейса.

Повторный прогон на той же БД получает **новый набор строк** (новый документ/`run-id`), поэтому
предыдущее состояние сверки не влияет на результат.

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Там, где кейс требует ролей FINANCIER / MANAGER / FOREMAN / ESTIMATOR,
тестовые пользователи этих ролей создаются и приглашаются в тестовый проект через admin-API в группе
`Setup`. Матрица `PURCHASES` (засеяна FOR-06-03, changeset 169, не изменяется этой спекой):
**ADMIN / FINANCIER / MANAGER / FOREMAN — CRUD (вкл. UPDATE); ESTIMATOR — READ; прочие — без
гранта**. `ADMIN` обходит матрицу в `ForemenPermissionEvaluator` для кода роли `ADMIN`.

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Setup — токены, проект, словарь, кандидаты, засеянные строки

**Повторяемость:** читает сид + создаёт помеченные `run-id` сущности; teardown в конце набора.

### TC-SETUP-01 — Логин ADMIN
**Предусловия:** стек поднят, ADMIN забутстрапен.
1. `POST /api/auth/login {email: <FOREMEN_ADMIN_EMAIL>, password: <FOREMEN_ADMIN_PASSWORD>}` →
   **200** + `accessToken`. Сохранить как `TOKEN_ADMIN`.

_Покрывает:_ инфраструктура (предзависимость всех групп).

### TC-SETUP-02 — Словарь неплановых категорий
1. `GET /api/unplanned-expense-categories` (Bearer ADMIN) → **200** + пять засеянных FOR-06-00 строк
   (reclamation / unforeseen / materials / fuel / other). Сохранить `id` одной активной категории
   как `CAT_ACTIVE`; при наличии деактивированной — `CAT_INACTIVE`; вычислить заведомо
   несуществующий `CAT_MISSING`.

_Покрывает:_ предзависимость R3.1/R3.4.

### TC-SETUP-03 — `ACTIVE`-проект, команда ролей, estimate с плановым материалом
**Предусловия:** TOKEN_ADMIN.
1. Создать проект `Purchases Test {run-id}` и довести его до статуса `ACTIVE` (через FOR-06
   execution-foundation flow); пригласить в команду FINANCIER / MANAGER / FOREMAN / ESTIMATOR. →
   сохранить `PROJECT_ID` и токены `TOKEN_FIN`, `TOKEN_MGR`, `TOKEN_FMAN`, `TOKEN_EST`.
2. Убедиться, что estimate проекта содержит минимум два плановых (on-estimate) материала; сохранить
   их id как `MAT_PLANNED_1`, `MAT_PLANNED_2`. Вычислить id материала, которого **нет** на estimate,
   как `MAT_OFF_ESTIMATE`.
3. Создать второй `ACTIVE`-проект `Purchases Other {run-id}`, где `TOKEN_FIN` **не** состоит;
   сохранить `OTHER_PROJECT_ID` (для проверки проектного скоупа Фичи 4).

_Покрывает:_ предзависимость R2.1/R2.2/R7.4.

### TC-SETUP-04 — Засеять строки закупки проекта
**Предусловия:** PROJECT_ID; документ закупки проекта (FOR-06-03 disabled-OCR, `UPLOADED`).
1. Засеять в `procurement_lines` документа строки с известным начальным состоянием (помечены
   `run-id`):
   - `LINE_AUTO` — `matchStatus=AUTO`, `matchedMaterialId=MAT_PLANNED_1`, `matchTier=EXACT`;
   - `LINE_UNMATCHED` — `matchStatus=UNMATCHED`, `matchedMaterialId=null`, `matchTier=NONE`;
   - `LINE_UNMATCHED_2` — ещё одна `UNMATCHED` строка для классификации.
2. `GET /api/procurement-lines?projectId=PROJECT_ID` (Bearer ADMIN) → **200** + три строки с
   ожидаемыми `matchStatus`/`matchTier`.

_Покрывает:_ предзависимость всех мутационных фич.

---

## Фича 1. Re-link на плановый материал (R2.1, R2.3, R2.4)

**Повторяемость:** свежезасеянные строки + teardown документа; `ACTIVE`-проект.

### TC-RELINK-01 — Re-link на плановый материал → MANUAL_MATCHED, EXACT, без категории
**Предусловия:** `LINE_UNMATCHED` в состоянии `UNMATCHED`; `MAT_PLANNED_2 ∈` candidate set.
1. `PUT /api/procurement-lines/{LINE_UNMATCHED}/match {materialId: MAT_PLANNED_2}` (Bearer FIN) →
   **200** + обновлённый read-model: `matchStatus == "MANUAL_MATCHED"`,
   `matchedMaterialId == MAT_PLANNED_2`, `matchTier == "EXACT"` (tier percent 100),
   `unplannedCategory == null`, локализованное имя материала (`ru → pl → code`).
2. `GET /api/procurement-lines?projectId=PROJECT_ID` → строка отражает новое состояние немедленно.

_Покрывает:_ R2.1, R4.4.

### TC-RELINK-02 — Re-link перекрывает авто-сопоставление (AUTO → MANUAL_MATCHED)
**Предусловия:** `LINE_AUTO` в `AUTO` с `MAT_PLANNED_1`.
1. `PUT /{LINE_AUTO}/match {materialId: MAT_PLANNED_2}` (Bearer MGR) → **200**;
   `matchStatus == "MANUAL_MATCHED"`, `matchedMaterialId == MAT_PLANNED_2`, `matchTier == "EXACT"`.

_Покрывает:_ R2.1.

### TC-RELINK-03 — Повторный re-link на другой плановый материал (MANUAL_MATCHED → MANUAL_MATCHED)
**Предусловия:** строка в `MANUAL_MATCHED` с `MAT_PLANNED_2` (после TC-RELINK-01).
1. `PUT /{LINE}/match {materialId: MAT_PLANNED_1}` → **200**; материал заменён, состояние остаётся
   `MANUAL_MATCHED`, `matchTier == "EXACT"`.

_Покрывает:_ R2.1.

### TC-RELINK-04 — Clear match → MANUAL_UNMATCHED, NONE, материал null
**Предусловия:** строка в `MANUAL_MATCHED` или `AUTO`.
1. `DELETE /api/procurement-lines/{LINE}/match` (Bearer FIN) → **200**;
   `matchStatus == "MANUAL_UNMATCHED"`, `matchedMaterialId == null`, `matchTier == "NONE"`.
2. Если у строки была неплановая категория — она сохраняется (clear-match её не трогает).

_Покрывает:_ R2.3.

### TC-RELINK-05 — Re-link / clear доступны ESTIMATOR (READ) запрещены
**Предусловия:** TOKEN_EST (только `PURCHASES` READ).
1. `PUT /{LINE}/match {materialId: MAT_PLANNED_1}` (Bearer EST) → **403** + `error.access.denied`;
   строка не изменена.
2. `DELETE /{LINE}/match` (Bearer EST) → **403** + `error.access.denied`.

_Покрывает:_ R2.4, R7.1.

---

## Фича 2. Re-link отклонения (R2.2, R2.5)

**Повторяемость:** свежезасеянные строки; строка остаётся неизменной при отклонении.

### TC-RELINK-REJ-01 — Материал не на estimate → 4xx relink.notPlanned, строка без изменений
**Предусловия:** `LINE_UNMATCHED` в `UNMATCHED`; `MAT_OFF_ESTIMATE` **вне** candidate set.
1. Зафиксировать текущее состояние строки (`GET`).
2. `PUT /{LINE_UNMATCHED}/match {materialId: MAT_OFF_ESTIMATE}` (Bearer FIN) → **4xx** +
   `error.purchases.relink.notPlanned`.
3. `GET` строки → состояние **байт-в-байт** как на шаге 1 (строка не изменена).

_Покрывает:_ R2.2.

### TC-RELINK-REJ-02 — Re-link на non-`ACTIVE` проекте → 4xx stage.notActive
**Предусловия:** `COMPLETED_PROJECT_ID` с засеянной строкой `LINE_C`.
1. `PUT /api/procurement-lines/{LINE_C}/match {materialId: MAT_PLANNED_1}` (Bearer FIN) → **4xx** +
   `error.purchases.stage.notActive`; строка не изменена.
2. `DELETE /{LINE_C}/match` → **4xx** + `error.purchases.stage.notActive`.

_Покрывает:_ R2.5.

### TC-RELINK-REJ-03 — Reads на `COMPLETED` разрешены
1. `GET /api/procurement-lines?projectId=COMPLETED_PROJECT_ID` (Bearer FIN) → **200** + список
   (чтение на `COMPLETED` допустимо, мутации — нет).

_Покрывает:_ R2.5, R7.4.

---

## Фича 3. Классификация неплановой статьи (R3.1–R3.5)

**Повторяемость:** свежезасеянные `UNMATCHED`/`MANUAL_UNMATCHED` строки + teardown.

### TC-CLASSIFY-01 — Классификация UNMATCHED строки → MANUAL_UNMATCHED + категория
**Предусловия:** `LINE_UNMATCHED_2` в `UNMATCHED`; `CAT_ACTIVE` активна.
1. `PUT /api/procurement-lines/{LINE_UNMATCHED_2}/classify {categoryId: CAT_ACTIVE}` (Bearer FMAN) →
   **200**; `matchStatus == "MANUAL_UNMATCHED"`, `unplannedCategory` = локализованное имя `CAT_ACTIVE`
   (`ru → pl → code`), `matchedMaterialId == null`, `matchTier == "NONE"`.

_Покрывает:_ R3.1.

### TC-CLASSIFY-02 — Клиринг match с последующей классификацией (MANUAL_UNMATCHED)
**Предусловия:** `LINE_AUTO` в `AUTO`.
1. `DELETE /{LINE_AUTO}/match` → **200**; `MANUAL_UNMATCHED`, материал null.
2. `PUT /{LINE_AUTO}/classify {categoryId: CAT_ACTIVE}` → **200**; категория установлена, состояние
   остаётся `MANUAL_UNMATCHED`.

_Покрывает:_ R3.1, R2.3.

### TC-CLASSIFY-03 — Снятие классификации оставляет MANUAL_UNMATCHED
**Предусловия:** строка `MANUAL_UNMATCHED` с установленной категорией.
1. `DELETE /api/procurement-lines/{LINE}/classify` (Bearer FIN) → **200**;
   `unplannedCategory == null`, `matchStatus` остаётся `"MANUAL_UNMATCHED"` (строка — осознанный
   не-match).

_Покрывает:_ R3.2.

### TC-CLASSIFY-04 — Классификация matched строки отклонена → classify.matchedLine
**Предусловия:** `LINE_M` в `AUTO` (или `MANUAL_MATCHED`) с материалом.
1. `PUT /{LINE_M}/classify {categoryId: CAT_ACTIVE}` → **4xx** +
   `error.purchases.classify.matchedLine`; строка не изменена (материал/состояние прежние).

_Покрывает:_ R3.3.

### TC-CLASSIFY-05 — Неизвестная / неактивная категория → classify.unknownCategory
**Предусловия:** `LINE_UNMATCHED` в `UNMATCHED`.
1. `PUT /{LINE_UNMATCHED}/classify {categoryId: CAT_MISSING}` → **4xx** +
   `error.purchases.classify.unknownCategory`; строка не изменена.
2. При наличии `CAT_INACTIVE`: `PUT /{LINE_UNMATCHED}/classify {categoryId: CAT_INACTIVE}` → **4xx**
   + `error.purchases.classify.unknownCategory`.

_Покрывает:_ R3.4.

### TC-CLASSIFY-06 — Classify / clear-classification запрещены ESTIMATOR + стадийный гейт
**Предусловия:** TOKEN_EST (READ); `COMPLETED_PROJECT_ID` со строкой `LINE_C`.
1. `PUT /{LINE_UNMATCHED}/classify {categoryId: CAT_ACTIVE}` (Bearer EST) → **403** +
   `error.access.denied`.
2. `PUT /{LINE_C}/classify {categoryId: CAT_ACTIVE}` (Bearer FIN, `COMPLETED`-проект) → **4xx** +
   `error.purchases.stage.notActive`.

_Покрывает:_ R3.5.

---

## Фича 4. Сверочный список + фильтры (R4.1–R4.4)

**Повторяемость:** читает засеянные строки проекта; дополнительные строки — с teardown.

### TC-LIST-01 — Список включает все строки проекта по всем документам, с полями read-model
**Предусловия:** PROJECT_ID с ≥2 документами, в каждом засеяны строки разных состояний.
1. `GET /api/procurement-lines?projectId=PROJECT_ID` (Bearer MGR) → **200** + по строке на каждый
   `ProcurementLine` проекта; каждый элемент содержит: source document (`documentId`,
   `supplierName`, `uploadedAt`), `lineNo`, `rawName`, `quantity`, `unitHint`, `unitPrice`,
   `lineTotal`, `manufacturerHint`, локализованное имя материала (`ru → pl → code`) или null,
   `matchTier`, tier percent (100/80/50/0), `matchStatus`, локализованное имя неплановой категории
   (`ru → pl → code`) или null.
2. Строки с matched материалом несут имя материала и tier percent; `UNMATCHED`/`MANUAL_UNMATCHED` —
   `material == null`, percent 0.

_Покрывает:_ R4.1.

### TC-LIST-02 — Фильтр по документу
1. `GET /api/procurement-lines?projectId=PROJECT_ID&documentId=DOC_1` → **200** + только строки
   документа `DOC_1`.

_Покрывает:_ R4.2.

### TC-LIST-03 — Фильтр по matchStatus
1. `GET ...&matchStatus=MANUAL_MATCHED` → только `MANUAL_MATCHED`-строки.
2. `GET ...&matchStatus=UNMATCHED` → только `UNMATCHED`-строки.

_Покрывает:_ R4.2.

### TC-LIST-04 — Фильтр по неплановой категории
**Предусловия:** есть строка, классифицированная `CAT_ACTIVE`.
1. `GET ...&unplannedCategoryId=CAT_ACTIVE` → только строки с этой категорией.

_Покрывает:_ R4.2.

### TC-LIST-05 — Проектный скоуп: чужой проект не возвращается
**Предусловия:** TOKEN_FIN не состоит в `OTHER_PROJECT_ID`.
1. `GET /api/procurement-lines?projectId=OTHER_PROJECT_ID` (Bearer FIN) → **404**
   (недоступный проект = несуществующий), либо **200 + []** согласно `ProjectScopedService`
   плюмбингу; в любом случае строки `OTHER_PROJECT_ID` **не** попадают в выдачу по `PROJECT_ID`.
2. `GET /api/procurement-lines?projectId=PROJECT_ID` (Bearer FIN) → **200**, без строк чужого
   проекта.

_Покрывает:_ R4.3.

### TC-LIST-06 — Список отражает состояние немедленно после сверки
1. `PUT /{LINE}/match {materialId: MAT_PLANNED_1}` → **200**.
2. Немедленный `GET /api/procurement-lines?projectId=PROJECT_ID` → строка уже `MANUAL_MATCHED` с
   новым материалом (нет устаревшего состояния).

_Покрывает:_ R4.4.

---

## Фича 5. Инварианты строки (R1.3, R1.4)

**Повторяемость:** проверка инвариантов после каждого действия; строки свежезасеяны.

### TC-INV-01 — Инвариант после каждого действия
1. После `relink` (Фича 1): `matchedMaterialId != null` ⟺ `matchStatus ∈ {AUTO, MANUAL_MATCHED}` и
   `matchTier != NONE`; `unplannedCategory == null`.
2. После `clearMatch` / `classify` / `clearClassification`: `matchedMaterialId == null` ⟺
   `matchStatus ∈ {UNMATCHED, MANUAL_UNMATCHED}` и `matchTier == NONE`; `unplannedCategory != null`
   ⟹ `matchedMaterialId == null`.

_Покрывает:_ R1.4.

### TC-INV-02 — ON DELETE SET NULL при удалении категории не каскадит на строку
**Предусловия:** строка `L_CLS` классифицирована категорией `CAT_TEMP` (временная, созданная на
`run-id`).
1. Удалить/деактивировать `CAT_TEMP` средствами admin-API словаря.
2. `GET /api/procurement-lines?projectId=PROJECT_ID` → строка `L_CLS` сохранилась,
   `unplannedCategory == null` (FK `ON DELETE SET NULL`), `matchStatus` остаётся `MANUAL_UNMATCHED`;
   строка **не** удалена каскадом.

_Покрывает:_ R1.3.

---

## Фича 6. ABAC `PURCHASES` + стадийный гейт (R2.4, R3.5, R7.1, R7.4)

**Повторяемость:** только токены + чтение матрицы; teardown токенов.

### TC-ABAC-01 — 401 без токена
1. `GET /api/procurement-lines?projectId=PROJECT_ID` без `Authorization` → **401**.
2. `PUT /{LINE}/match {materialId: MAT_PLANNED_1}` без токена → **401**; строка не изменена.

_Покрывает:_ R7.1 (контекст).

### TC-ABAC-02 — ESTIMATOR: READ ок, запись 403
1. `GET /api/procurement-lines?projectId=PROJECT_ID` (Bearer EST) → **200**.
2. `PUT /{LINE}/match`, `DELETE /{LINE}/match`, `PUT /{LINE}/classify`, `DELETE /{LINE}/classify`
   (Bearer EST) → каждый **403** + `error.access.denied`.

_Покрывает:_ R7.1, R2.4, R3.5.

### TC-ABAC-03 — CRUD-роли могут сверять на `ACTIVE`
1. `PUT /{LINE}/match {materialId: MAT_PLANNED_1}` под FIN / MGR / FMAN поочерёдно → **200** (каждая
   роль имеет `PURCHASES` UPDATE).

_Покрывает:_ R7.4, R2.4.

### TC-ABAC-04 — `PURCHASES` матрица не изменена (нет нового ресурса)
1. `GET /api/resources` (Bearer ADMIN) → ресурс `PURCHASES` существует; **нового** ресурса
   `PURCHASES_*` / `PURCHASE_LINE` / `RECONCILIATION` **нет** (спека ездит на FOR-06-03 `PURCHASES`).

_Покрывает:_ R7.1.

### TC-ABAC-05 — Приложение стартует: PermissionAnnotationValidator принимает контроллер
1. Факт успешного старта `backend` (healthcheck 200) подтверждает, что
   `@PermissionResource("PURCHASES")` + `@PermissionOperation`/`@RequiresPermission` каждого
   in-scope handler `PurchasesController` приняты валидатором без ошибки аннотаций.

_Покрывает:_ R7.1 (контекст R7.2).

---

## Фича 7. Переобработка сохраняет ручную сверку (R5.1, R5.2, R5.3)

**Повторяемость:** свежий документ на прогон + teardown; disabled-OCR допустим.

### TC-REPROCESS-01 — Preserve MANUAL_*, replace AUTO/UNMATCHED
**Предусловия:** документ `DOC_R` с засеянными строками: `L_MM` (`MANUAL_MATCHED`, материал),
`L_MU` (`MANUAL_UNMATCHED`, категория), `L_A` (`AUTO`), `L_U` (`UNMATCHED`).
1. Запустить переобработку документа (FOR-06-03 `reprocess`, disabled-OCR → свежий набор строк
   пустой).
2. `GET /api/procurement-lines?projectId=PROJECT_ID&documentId=DOC_R` → `L_MM` и `L_MU`
   **сохранены** (с `matchedMaterialId` / `unplannedCategory` целыми); `L_A` и `L_U` **удалены**
   (replace-набор). `lineNo` перенумерованы последовательно, preserve-строки идут первыми.

_Покрывает:_ R5.1.

### TC-REPROCESS-02 — Документ без ручных строк → FOR-06-03-эквивалент (replace all)
**Предусловия:** документ `DOC_AUTO` только с `AUTO`/`UNMATCHED` строками.
1. Переобработка → все строки заменены (preserve пуст); поведение идентично FOR-06-03.

_Покрывает:_ R5.2.

### TC-REPROCESS-03 — Партиция чистая и детерминированная (дизъюнктное покрытие)
1. На любом prior-наборе строк: `preserve ∪ replace` = весь набор, `preserve ∩ replace` = ∅,
   `preserve` = ровно `MANUAL_*`, `replace` = ровно `AUTO`/`UNMATCHED`. (Проверяется также
   property-тестом Property 4 / задача 3.1a — здесь end-to-end подтверждение.)

_Покрывает:_ R5.3.

---

## Фича 8. Аудит (R10.1)

**Повторяемость:** читает аудит по созданным строкам; без деструктива.

### TC-AUDIT-01 — Аудит-строка на каждое действие сверки
1. Выполнить `relink` → в аудите появилась запись `UPDATE` по строке.
2. Выполнить `clearMatch`, `classify`, `clearClassification` → по одной аудит-записи на действие.
3. Отклонённые действия (`relink.notPlanned`, `classify.matchedLine`, стадийный гейт) аудит-записей
   **не** создают (строка не изменена).

_Покрывает:_ R10.1.

---

# Часть B. Браузерные (UI) сценарии

**Повторяемость:** `ACTIVE`-проект + свежезасеянные строки на `run-id`; teardown документа.
Исполняется браузерным движком против `http://localhost:3000`.

### TC-UI-01 — Вкладка Purchases видна и рендерит сверочный список
**Предусловия:** логин под CRUD-ролью `PURCHASES` (ADMIN/MGR/FIN/FMAN); `ACTIVE`-проект с засеянными
строками.
1. Открыть воркспейс проекта → вкладка `Purchases` видна (лейбл `workspace.tab.purchases`, без
   сырого ключа).
2. Панель рендерит сверочный DataTable: source document, raw name, количество/ед./цена/сумма, имя
   материала + `TierBadge` или имя неплановой категории, `MatchStateBadge`.

_Покрывает:_ R6.1.

### TC-UI-02 — Фильтры по документу / состоянию / неплановой категории
1. Применить фильтр по документу → список сужается до строк документа.
2. Применить фильтр по состоянию (`MANUAL_MATCHED`) и по неплановой категории → список
   соответствует фильтрам.

_Покрывает:_ R6.1.

### TC-UI-03 — Re-link: выбор планового материала и clear-match, тост, inline-ошибка
**Предусловия:** строка `UNMATCHED`.
1. На строке открыть RelinkControl → пикер показывает только материалы из candidate set проекта.
   Выбрать `MAT_PLANNED_1` → строка немедленно `MANUAL_MATCHED`, тост
   `purchases.toast.relinked`, бейдж/тир обновились (100%).
2. Нажать clear-match → строка `MANUAL_UNMATCHED`, тост `purchases.toast.matchCleared`.
3. (Негатив) Если UI допускает выбор off-estimate материала — сервер вернёт
   `error.purchases.relink.notPlanned`, который отображается **inline** локализованным текстом
   (`purchases.error.notPlanned`), не сырым ключом.

_Покрывает:_ R6.2, R6.5.

### TC-UI-04 — Classify: категория и clear-classification, enabled только на unmatched
**Предусловия:** строка `UNMATCHED`/`MANUAL_UNMATCHED` и строка `MANUAL_MATCHED`.
1. На unmatched-строке ClassifyControl **активен**; выбрать активную категорию → строка
   `MANUAL_UNMATCHED` + категория, тост `purchases.toast.classified`.
2. Нажать clear-classification → категория снята, состояние остаётся `MANUAL_UNMATCHED`, тост
   `purchases.toast.classificationCleared`.
3. На matched-строке ClassifyControl **отключён** (или попытка даёт inline
   `purchases.error.classifyMatchedLine`).

_Покрывает:_ R6.2, R6.5.

### TC-UI-05 — Бейджи состояния сопоставления и тира
1. Над строками четырёх состояний видны локализованные `MatchStateBadge`
   (`auto` / `unmatched` / `manualMatched` / `manualUnmatched`), цветокодированные.
2. На matched-строках `TierBadge` рендерит 100% / 80% / 50% / no-match согласованно с FOR-06-03.

_Покрывает:_ R6.3.

### TC-UI-06 — Read-only гейтинг: ESTIMATOR и `COMPLETED`-проект
**Предусловия:** (а) ESTIMATOR (READ) на `ACTIVE`-проекте; (б) любая роль на `COMPLETED`-проекте.
1. (а) Под ESTIMATOR панель read-only: контролы re-link и classify **скрыты**, список виден.
2. (б) На `COMPLETED`-проекте контролы re-link/classify **скрыты** для всех, история видна.

_Покрывает:_ R6.4, R7.4.

---

# Регрессия

**Повторяемость:** регрессионные кейсы читают сид и ранее закрытое поведение; без деструктивных
изменений.

### REG-01 — Старт приложения и целостность аннотаций (нет нового ABAC-ресурса)
1. Приложение стартует без ошибки `PermissionAnnotationValidator`; `PurchasesController`
   классифицируется COMPLETE.
2. `GET /api/resources` → ресурс `PURCHASES` присутствует, **нового** ресурса не добавлено (матрица
   FOR-06-03 неизменна).

_Покрывает:_ R7.2, R10.2.

### REG-02 — Идемпотентность changeset 170 + аддитивность
1. Повторный прогон миграций на уже засеянной БД **не** добавляет колонку повторно
   (`NOT columnExists` + `MARK_RAN`); существующие строки сохранили `AUTO`/`UNMATCHED` (enum
   `MatchStatus` расширен без schema-change).

_Покрывает:_ R1.1, R1.2, R10.2.

### REG-03 — Не затронутые поверхности FOR-06-03 / FOR-06-00 работают
1. FOR-06-03: загрузка документов, OCR fallback, read-only вкладка `Procurement`, ресурс+матрица
   `PURCHASES` — отвечают как раньше (смоук по ключевым эндпоинтам).
2. FOR-06-00: словарь `UnplannedExpenseCategory` (`GET /api/unplanned-expense-categories`) и его
   пять засеянных строк — без изменений.
3. Переобработка документа **без** ручных строк остаётся FOR-06-03-эквивалентной (replace all).

_Покрывает:_ R10.2, R5.2.

### REG-04 — Нет нотификаций
1. Любое действие сверки (`relink`/`clearMatch`/`classify`/`clearClassification`) **не** создаёт
   user-facing нотификаций (R8.1).

_Покрывает:_ R10.2 (контекст R8.1).

---

# Шаблон MD-репорта прогона

Заполняется по итогам каждого прогона (отдельно для Части A и Части B).

## Репорт — Часть A (API)

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-RELINK-01 | 1 | PUT /api/procurement-lines/{id}/match {materialId} | 200 + MANUAL_MATCHED, EXACT | — | ⬜ |
| 2 | TC-RELINK-REJ-01 | 2 | PUT …/match {off-estimate} | 4xx relink.notPlanned, строка без изменений | — | ⬜ |
| 3 | TC-CLASSIFY-04 | 1 | PUT …/classify на matched | 4xx classify.matchedLine | — | ⬜ |
| 4 | TC-LIST-03 | 1 | GET …?matchStatus=MANUAL_MATCHED | 200 + только MANUAL_MATCHED | — | ⬜ |
| 5 | TC-REPROCESS-01 | 2 | reprocess, затем GET строк | preserve MANUAL_*, replace AUTO/UNMATCHED | — | ⬜ |

## Репорт — Часть B (UI)

| # | Тест-кейс | Шаг | Действие (браузер) | Ожидание | Факт | Статус |
|---|-----------|-----|--------------------|----------|------|--------|
| 1 | TC-UI-01 | 1 | Открыть вкладку Purchases | Вкладка видна, список рендерится | — | ⬜ |
| 2 | TC-UI-03 | 1 | Re-link на плановый материал | MANUAL_MATCHED, тост, тир 100% | — | ⬜ |
| 3 | TC-UI-06 | 1 | Открыть панель под ESTIMATOR | Контролы re-link/classify скрыты | — | ⬜ |

**Легенда статусов:** ✅ пройдено · ❌ провалено · ⚠️ отклонение/известный нюанс · ⏭ пропущено
(например, засеивание строк недоступно в целевом окружении) · ⬜ не выполнялось.

**Итог:** X пройдено / Y провалено / Z пропущено.
**Run-id:** `<timestamp/uuid>`.
**Окружение OCR:** `disabled` (строки засеяны напрямую — сценарии не зависят от OCR).
**Стратегия повторяемости:** генератор `run-id` + teardown (засеянные строки/документы: DELETE
документов → каскад строк → деактивация пользователей → перевод проектов в `CANCELLED`).
