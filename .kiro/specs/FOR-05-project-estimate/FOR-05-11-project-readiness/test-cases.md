# Тест-кейсы: FOR-05-11 — Project readiness (агрегированная готовность проекта)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерные экраны**, поэтому набор тест-кейсов включает **оба типа**:

- **Часть A. API-тесты** — против поднятого в Docker приложения (`http://localhost:8080`). Каждый
  шаг — HTTP-запрос к `Readiness_API` (`GET /api/projects/{projectId}/readiness`) с проверкой
  статуса, тела и заголовков. Проверяется: гардинг контроллера (`PROJECTS` READ), проектный скоуп
  (404 для недоступного/несуществующего, идентичное тело), per-area скоуп по READ-грантам, состав и
  порядок областей, `overallPercentage` / `overallState`, пер-зонные проценты и состояния при
  прохождении проекта через этапы готовности, отсутствие записи/аудита при чтении.
- **Часть B. Браузерные (UI) сценарии** — против фронтенда (`http://localhost:3000`), исполняются
  браузерным движком (headless-браузер / browser automation). Экраны: вкладка `readiness`
  воркспейса проекта (общий donut, упорядоченные строки областей, локализованные метки состояний,
  предложения outstanding-items, переход в целевой таб, loading / error / empty) и header-компонент
  `ReadinessWidget` (donut от overall %, по одному chip на область, опущенная область отсутствует,
  плашка ошибки).

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание →
факт → статус). **Скриншоты не требуются.**

### Покрытие требований

Каждое из требований R1–R11 покрыто минимум одним кейсом. R12 (регрессия, документация, артефакты
тестирования) покрыт регрессионной группой в конце файла (критерии 1, 2, 4) и самим фактом наличия
этого `test-cases.md` (критерий 5). Соответствие «кейс ↔ требование» указано в каждом кейсе строкой
`_Покрывает:_`.

> **Зависимость от соседних спек.** `Readiness_API` агрегирует вклады шести областей (rooms,
> estimate, offer, documents, team, schedule), поэтому полноценный прогон требует, чтобы FOR-05-02,
> 03/05, 07, 08, 09, 10 были применены в стеке (их ресурсы `ROOMS` / `ESTIMATE` / `OFFERS` /
> `SIGNABLE_DOCUMENTS` / `PROJECT_MEMBERS` / `WORK_SCHEDULE` засижены). Если какой-то области нет в
> сборке, per-area READ-проверка (R6) просто её не включит, и contributor будет опущен без падения —
> это допустимое поведение, оно отдельно проверяется в TC-SCOPE-04.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции),
   `backend` (:8080, профиль `docker`), `frontend` (:3000). Эта спека **не вводит новый
   Liquibase-changeset и новый ABAC-ресурс** (решение D7) — агрегат ездит на существующем ресурсе
   `PROJECTS`.
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN — через переменные окружения `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`,
   `FOREMEN_ADMIN_PASSWORD`. Логин: `POST /api/auth/login {email, password}` → `accessToken`.
4. Дождаться готовности `backend` (healthcheck 200) и завершения миграций перед прогоном.

## Общая стратегия повторяемости

**Комбинированная: генератор `run-id` + teardown.** Единый на прогон `run-id` (timestamp или uuid,
например `1730000000` или `a1b2c3`) встраивается во все создаваемые идентификаторы, чтобы два
последовательных прогона на одной БД не конфликтовали:

- email тестовых пользователей: `pm+{run-id}@example.com`, `manager+{run-id}@example.com`,
  `foreman+{run-id}@example.com`, `client+{run-id}@example.com`;
- имена проектов: `Readiness Test {run-id}`, `Readiness Scope {run-id}`, `Readiness E2E {run-id}`.

**Teardown каждой группы** (если не указано иное) выполняется в конце набора в обратном порядке
создания: удаление созданных членств/offer/документов/расписаний через соответствующие admin-API,
деактивация созданных пользователей, перевод тестовых проектов в `CANCELLED` или их удаление
доступным admin-API. `Readiness_API` сам **ничего не создаёт и не удаляет** (строго read-only, R11),
поэтому его прогон не оставляет следов; подготовка состояния областей делается через их собственные
API. Сид-данные (роли, ресурсы, worker types) **только читаются, никогда не изменяются**. Каждая
группа ниже дополнительно уточняет свою стратегию строкой `**Повторяемость:**`.

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Там, где кейс требует роли MANAGER / FOREMAN / CLIENT или искусственно
ограниченного набора READ-грантов, тестовые пользователи создаются и приглашаются через admin-API в
группе `Setup`, либо соответствующие ABAC-ожидания документируются и покрываются
seed-интеграционными и unit-тестами спеки (`ProjectReadinessService` property-тесты permission
scoping, ABAC-интеграционный тест FOR-05-11). Для аггрегата важно разделение: `PROJECTS` READ
пускает к эндпоинту (R2), а каждый `Area_Resource` READ пускает вклад конкретной области (R6).

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Setup — токены и подготовка проектов

**Повторяемость:** читает сид + создаёт помеченные `run-id` сущности; teardown в конце набора.

### TC-SETUP-01 — Логин ADMIN
**Предусловия:** стек поднят, ADMIN забутстрапен.
1. `POST /api/auth/login {email: <FOREMEN_ADMIN_EMAIL>, password: <FOREMEN_ADMIN_PASSWORD>}` →
   **200** + `accessToken`. Сохранить как `TOKEN_ADMIN`.

_Покрывает:_ инфраструктура (предзависимость всех групп).

### TC-SETUP-02 — Создать «пустой» проект (без областей готовности)
**Предусловия:** TOKEN_ADMIN.
1. `POST /api/projects` → создать проект `Readiness Test {run-id}` в статусе `DRAFT`, без комнат,
   без сметы, без offer, без документов, без расписания. Сохранить `EMPTY_PROJECT_ID`.
   > Проект создаётся с обязательным ACTIVE MANAGER и CLIENT (инвариант FOR-05-09); на readiness
   > областей `team` это уже влияет (есть менеджер, но нет ACTIVE FOREMAN → `team` будет BLOCKED).

_Покрывает:_ предзависимость R4/R5.

### TC-SETUP-03 — Создать MANAGER-пользователя и получить его токен
**Предусловия:** TOKEN_ADMIN.
1. Создать (или получить) пользователя роли MANAGER `manager+{run-id}@example.com`, сделать его
   членом `EMPTY_PROJECT_ID`, активировать и залогинить → сохранить `TOKEN_MANAGER`,
   `MANAGER_USER_ID`.

_Покрывает:_ предзависимость R3/R6 (non-ADMIN caller со скоупом).

---

## Фича 1. Контроллер-гардинг и агрегатный ABAC-гейт (R2)

**Повторяемость:** только чтение + помеченные токены; teardown токенов. `Readiness_API` ничего не
пишет.

### TC-GUARD-01 — Приложение стартует: PermissionAnnotationValidator принимает контроллер
1. Факт успешного старта `backend` (healthcheck 200) подтверждает, что класс-уровневый
   `@PermissionResource("PROJECTS")` + метод-уровневый `@PermissionOperation("READ")` на
   `GET /{projectId}/readiness` приняты валидатором аннотаций без ошибки старта.

_Покрывает:_ R2 (1).

### TC-GUARD-02 — Новый ресурс не вводится; агрегат ездит на `PROJECTS`
**Предусловия:** TOKEN_ADMIN.
1. Прочитать матрицу прав админ-API (`GET /api/resources`) → **200**; ресурса `PROJECT_READINESS`
   (или иного нового ресурса для readiness) в матрице **нет**.
2. Caller с `PROJECTS` READ успешно доходит до эндпоинта (см. TC-READ-01), что подтверждает
   переиспользование существующего гранта.

_Покрывает:_ R2 (2).

### TC-GUARD-03 — 401 без токена
1. `GET /api/projects/{EMPTY_PROJECT_ID}/readiness` без заголовка `Authorization` → **401**; тело
   не содержит `Project_Readiness_View`.

_Покрывает:_ R2 (3), R3 (5 — 401 раньше всего).

### TC-GUARD-04 — 403 без `PROJECTS` READ
**Предусловия:** токен пользователя без гранта `PROJECTS` READ (`TOKEN_NO_PROJECTS`).
1. `GET /api/projects/{EMPTY_PROJECT_ID}/readiness` (Bearer TOKEN_NO_PROJECTS) → **403** +
   `error.access.denied`; данные не изменены.

_Покрывает:_ R2 (4).

---

## Фича 2. Проектный скоуп доступа (R3)

**Повторяемость:** генератор + teardown. Создаётся второй проект, недоступный non-ADMIN caller.

### TC-SCOPE-01 — Non-ADMIN: недоступный проект = несуществующий (404, тело идентично)
**Предусловия:** TOKEN_MANAGER, принадлежащий `EMPTY_PROJECT_ID`; `OTHER_PROJECT_ID` — проект, где
MANAGER не состоит; `MISSING_ID` — заведомо несуществующий id.
1. `GET /api/projects/{OTHER_PROJECT_ID}/readiness` (Bearer MANAGER) → **404** +
   `error.entity.not.found`.
2. `GET /api/projects/{MISSING_ID}/readiness` (Bearer MANAGER) → **404** + байт-идентичное тело
   (структура и содержимое совпадают с шагом 1 — missing и inaccessible неразличимы).

_Покрывает:_ R3 (1, 2, 3).

### TC-SCOPE-02 — ADMIN обходит скоуп, но не прочие проверки
1. `GET /api/projects/{OTHER_PROJECT_ID}/readiness` (Bearer ADMIN) → **200** (ADMIN видит любой
   существующий проект).
2. `GET /api/projects/{MISSING_ID}/readiness` (Bearer ADMIN) → **404** + `error.entity.not.found`.

_Покрывает:_ R3 (1, 3).

### TC-SCOPE-03 — Валидация `projectId`
1. `GET /api/projects/abc/readiness` (нечисловой id, Bearer ADMIN) → **400**; данные не изменены.
2. `GET /api/projects/-1/readiness` (Bearer ADMIN) → **400** либо **404** в зависимости от биндинга
   пути; тело без `Project_Readiness_View` и без утечек о проекте.

_Покрывает:_ R3 (4).

### TC-SCOPE-04 — Канонический порядок проверок: 401 → 403 → 400 → 404
1. Без токена на несуществующий проект → **401** (аутентификация раньше всего).
2. С токеном без `PROJECTS` READ, нечисловой id → **403** (права раньше формы запроса).
3. С `PROJECTS` READ, нечисловой id, несуществующий проект → **400** (форма раньше 404).
4. С `PROJECTS` READ, валидный id несуществующего проекта → **404**.

_Покрывает:_ R3 (5).

---

## Фича 3. Чтение агрегированной готовности (R4, R7)

**Повторяемость:** только чтение по данным Setup; дополнительное состояние — с teardown.

### TC-READ-01 — Форма ответа: id, overallPercentage, overallState, areas[]
**Предусловия:** TOKEN_ADMIN; `EMPTY_PROJECT_ID`.
1. `GET /api/projects/{EMPTY_PROJECT_ID}/readiness` (Bearer ADMIN) → **200** +
   `Project_Readiness_View`: присутствуют `projectId` (== EMPTY_PROJECT_ID), целочисленный
   `overallPercentage` в диапазоне 0–100, `overallState` ∈ {`DONE`,`PARTIAL`,`BLOCKED`}, массив
   `areas`.
2. Каждый элемент `areas` содержит `key`, `nameKey`, `percentage` (0–100, integer), `state`
   (`DONE`/`PARTIAL`/`BLOCKED`), и массив `items`; у `DONE`/100% области `items` пуст.
3. Каждый `item` содержит `descriptionKey` (i18n-ключ), `params` (map, возможно пустой), `targetTab`
   (валидный ключ `WORKSPACE_TABS`).

_Покрывает:_ R4 (1, 6), R1 (1, 2, 3, 4), R7 (3).

### TC-READ-02 — Пустой проект (ADMIN видит все области): проценты и состояния начального этапа
**Предусловия:** TOKEN_ADMIN; `EMPTY_PROJECT_ID` без комнат/сметы/offer/документов/расписания, с
ACTIVE MANAGER и CLIENT, но без ACTIVE FOREMAN.
1. `GET .../readiness` → **200**; в `areas` присутствуют все шесть областей (ADMIN читает всё).
2. `rooms` → `BLOCKED`/0% + item `readiness.rooms.none` (`targetTab=rooms`); `estimate` →
   `BLOCKED`/0% + item `readiness.estimate.noWorks` (`targetTab=estimate`); `offer` → `BLOCKED`/0% +
   `readiness.offer.notReady` (`targetTab=pricing`); `documents` → `BLOCKED`/0% +
   `readiness.documents.notReady` (`targetTab=documentSigning`); `team` → `BLOCKED`/0% +
   `readiness.team.noForeman` (`targetTab=team`); `schedule` → `BLOCKED`/0% +
   `readiness.schedule.unscheduledRows` или аналог «нет строк» (`targetTab=scheduleDesign`).
3. `overallState == BLOCKED`; `overallPercentage == 0` (среднее нулей).

_Покрывает:_ R4 (1, 3, 4), R5 (7), R1 (5).

### TC-READ-03 — Порядок областей стабилен и декларативен
1. В ответе TC-READ-02 `areas` идут строго в порядке: `rooms`, `estimate`, `offer`, `documents`,
   `team`, `schedule` — независимо от порядка обнаружения бинов.
2. Повторный запрос того же неизменного проекта возвращает тот же порядок и те же `items` в том же
   порядке (детерминизм).

_Покрывает:_ R7 (1, 2, 4), R5 (8), R4 (5).

### TC-READ-04 — Чтение отражает коммиченные изменения областей
**Предусловия:** TOKEN_ADMIN; проект `E2E_PROJECT_ID` (см. TC-E2E-01).
1. Снять readiness → зафиксировать `overallPercentage` P0.
2. Через API области (например, добавить комнату с положительной площадью) закоммитить изменение.
3. Повторный `GET .../readiness` сразу после коммита → **200**; `rooms`-процент и, как следствие,
   `overallPercentage` отражают изменение (P1 ≠ P0), без задержки/кэш-рассинхрона.

_Покрывает:_ R4 (7).

### TC-READ-05 — params несут только счётчики/идентификаторы, без денег и масок
**Предусловия:** проект с несколькими непросчитанными строками сметы (область `estimate` в `PARTIAL`).
1. `GET .../readiness` под ESTIMATE-reader → у `estimate` есть item
   `readiness.estimate.unpricedLines` с `params.count` (целое число) и/или
   `readiness.estimate.bomIncomplete` с `params.count`.
2. Ни в одном `params` нет полей денег/ставок/стоимости/замаскированных значений (нет `unitPrice`,
   `valueNet`, `amount`, `price`, `cost`, `rate`); только счётчики и идентификаторы (`roomName`).

_Покрывает:_ R4 (8), R1 (4).

---

## Фича 4. Пер-зонные вклады: completion и outstanding items (R5)

**Повторяемость:** генератор + teardown. Используется один сквозной проект `E2E_PROJECT_ID`,
прогоняемый через этапы; каждая проверка снимает readiness после изменения своей области.

### TC-AREA-ROOMS-01 — rooms: нет комнат → BLOCKED; частичная площадь → PARTIAL; все с площадью → DONE
1. Нет комнат → `rooms` `BLOCKED`/0% + `readiness.rooms.none`.
2. Добавить 2 комнаты, у одной положительная `floorArea`, у другой нет → `rooms` `PARTIAL`,
   `percentage == round(100 × 1/2) == 50`, item `readiness.rooms.noDimensions` с `params.roomName`
   для комнаты без площади; items упорядочены по id комнаты.
3. Задать положительную площадь второй комнате → `rooms` `DONE`/100%, `items` пуст.

_Покрывает:_ R5 (1), R5 (7, 8), R1 (3).

### TC-AREA-ESTIMATE-01 — estimate: нет строк → BLOCKED; непросчёт/неполный BoM → PARTIAL; всё → DONE
1. Нет сметы/строк → `estimate` `BLOCKED`/0% + `readiness.estimate.noWorks`.
2. Добавить строки сметы, часть без `unitPrice>0`, часть материальных строк без consumption →
   `estimate` `PARTIAL`; есть item `readiness.estimate.unpricedLines {count}` и/или
   `readiness.estimate.bomIncomplete {count}` с ненулевыми счётчиками.
3. Проставить цены всем строкам и consumption всем материальным строкам → `estimate` `DONE`/100%,
   `items` пуст.

_Покрывает:_ R5 (2), R5 (7), R1 (3).

### TC-AREA-OFFER-01 — offer: нет/draft → BLOCKED; sent/on-approval → PARTIAL; approved → DONE
1. Нет offer (или DRAFT/REJECTED/WITHDRAWN) → `offer` `BLOCKED`/0% + `readiness.offer.notReady`.
2. Отправить offer (SENT / CHANGES_REQUESTED / COUNTERED) → `offer` `PARTIAL`/50% +
   `readiness.offer.onApproval`.
3. Approve offer → `offer` `DONE`/100%, `items` пуст.

_Покрывает:_ R5 (3).

### TC-AREA-DOCS-01 — documents: нет контракта → BLOCKED; pending → PARTIAL; signed → DONE
1. Нет Contract_Document (или DRAFT/VOID) → `documents` `BLOCKED`/0% +
   `readiness.documents.notReady`.
2. Создать Contract_Document в `PENDING_SIGNATURES` → `documents` `PARTIAL`/50% +
   `readiness.documents.pending`.
3. Перевести документ в `SIGNED` → `documents` `DONE`/100%, `items` пуст.

_Покрывает:_ R5 (4).

### TC-AREA-TEAM-01 — team: нет ACTIVE FOREMAN → BLOCKED; есть → DONE
1. Проект без ACTIVE FOREMAN → `team` `BLOCKED`/0% + `readiness.team.noForeman` (`targetTab=team`).
2. Назначить ACTIVE FOREMAN → `team` `DONE`/100%, `items` пуст.
3. Значения совпадают с отдельным `GET /api/project-members/readiness` того же проекта (делегация
   правилу FOR-05-09, R12.1).

_Покрывает:_ R5 (5), R12 (1).

### TC-AREA-SCHEDULE-01 — schedule: нет строк → BLOCKED; частично → PARTIAL; все → DONE; процент = scheduled/total
1. Нет строк расписания → `schedule` `BLOCKED`/0%.
2. Создать 4 строки, 1 — scheduled → `schedule` `PARTIAL`, `percentage == round(100 × 1/4) == 25`,
   item `readiness.schedule.unscheduledRows` с `params.count == 3`.
3. Запланировать все строки → `schedule` `DONE`/100%, `items` пуст; значения совпадают с
   `GET /api/project-schedules/readiness` того же проекта (делегация FOR-05-10, R12.1).

_Покрывает:_ R5 (6), R12 (1).

### TC-AREA-EMPTYAREA-01 — область без данных у Readable_Area всё равно возвращается как BLOCKED/0% с первым шагом
1. Для любой читаемой области без данных (комнат/сметы/offer/документов/расписания нет) вклад
   присутствует в ответе как `BLOCKED`/0% с item, называющим первый шаг (а не отсутствует).

_Покрывает:_ R5 (7).

---

## Фича 5. Permission-scoped composition (R6)

**Повторяемость:** генератор ограниченных по грантам пользователей + teardown.

### TC-PSCOPE-01 — Только `ROOMS` READ: видна одна область, headline по ней одной
**Предусловия:** caller `TOKEN_ROOMS_ONLY` с `PROJECTS` READ + `ROOMS` READ и **без** READ прочих
областей; `EMPTY_PROJECT_ID` (где rooms = BLOCKED/0%).
1. `GET /api/projects/{EMPTY_PROJECT_ID}/readiness` (Bearer TOKEN_ROOMS_ONLY) → **200**; `areas`
   содержит ровно одну область `rooms`.
2. `overallPercentage` и `overallState` рассчитаны по одной этой области (для пустого проекта —
   0% / BLOCKED).
3. В ответе нет ни одного поля (key/name/state/percentage/item) любой из опущенных областей
   (estimate/offer/documents/team/schedule) — нет утечки.

_Покрывает:_ R6 (1, 2, 3, 5).

### TC-PSCOPE-02 — Нет READ ни на одну область → 200 + пустой areas + 0% + BLOCKED
**Предусловия:** caller `TOKEN_PROJECTS_ONLY` с `PROJECTS` READ и **без** READ любой `Area_Resource`.
1. `GET .../readiness` (Bearer TOKEN_PROJECTS_ONLY) → **200**; `areas == []`;
   `overallPercentage == 0`; `overallState == "BLOCKED"`; без падения и без сырых ключей в теле.

_Покрывает:_ R6 (4), R4 (3, 4).

### TC-PSCOPE-03 — Подмножество грантов: включены ровно читаемые области
**Предусловия:** caller с `PROJECTS` READ + `ROOMS` READ + `WORK_SCHEDULE` READ (без остальных).
1. `GET .../readiness` → **200**; `areas` = ровно `rooms` и `schedule` в декларативном порядке
   (`rooms` раньше `schedule`); относительный порядок сохранён, промежуточные опущены.
2. `overallPercentage`/`overallState` посчитаны только по этим двум областям.

_Покрывает:_ R6 (1, 2), R7 (4).

### TC-PSCOPE-04 — Независимость per-area READ от агрегатного `PROJECTS` READ
1. Caller без `PROJECTS` READ → **403** даже если у него есть `ROOMS` READ (агрегатный гейт
   закрывает вход).
2. Caller с `PROJECTS` READ, но без `ROOMS` READ → **200** без области `rooms` (per-area гейт
   фильтрует вклад).

_Покрывает:_ R6 (5), R2 (4).

---

## Фича 6. Сквозной прогон этапов (E2E) и инварианты чтения (R4, R11)

**Повторяемость:** генератор `Readiness E2E {run-id}` + teardown (проект → CANCELLED/удаление в конце).

### TC-E2E-01 — overallPercentage/overallState/items меняются при прохождении этапов
**Предусловия:** TOKEN_ADMIN; создать `E2E_PROJECT_ID` с ACTIVE MANAGER + CLIENT, без прочих данных.
1. Снять baseline: все области BLOCKED, `overall` 0%/BLOCKED.
2. Добавить комнаты с площадью + просчитанные строки сметы → снять readiness: `rooms` и `estimate`
   стали DONE, `overallPercentage` вырос, `overallState` всё ещё BLOCKED (offer/docs/team/schedule
   блокируют).
3. Отправить offer → снять: `offer` стал PARTIAL/50%, `overallPercentage` вырос.
4. Approve offer → `offer` DONE/100%.
5. Назначить ACTIVE FOREMAN → `team` DONE/100%.
6. Создать и запланировать строки расписания → `schedule` DONE/100%.
7. Подписать Contract_Document → `documents` DONE/100%; на этом шаге все шесть DONE →
   `overallPercentage == 100`, `overallState == DONE`, у всех областей `items` пуст.

_Покрывает:_ R4 (3, 4, 7), R5 (1, 2, 3, 5, 6).

### TC-E2E-02 — Чтение не пишет и не создаёт аудит
**Предусловия:** TOKEN_ADMIN; `E2E_PROJECT_ID`.
1. Зафиксировать счётчик аудита (`GET /api/audit` total или аналог) до запроса.
2. Выполнить несколько `GET .../readiness` подряд → **200** каждый раз.
3. Повторно снять счётчик аудита → не изменился; состояние областей не изменилось (никаких записей,
   нотификаций, домен-событий).

_Покрывает:_ R11 (1, 2).

---

# Часть B. Браузерные (UI) сценарии (frontend)

**Среда:** `http://localhost:3000`, исполнение браузерным движком. Логин под подготовленными в Части A
пользователями. **Повторяемость:** переиспользуются проекты `run-id` из Части A; teardown — там же.
UI ничего не создаёт на readiness-поверхности (она read-only), поэтому прогоны идемпотентны.

## Фича 7. Вкладка `readiness` воркспейса (R8)

### TC-UI-TAB-01 — Donut, упорядоченные строки, локализованные состояния и проценты
**Предусловия:** проект `E2E_PROJECT_ID` в смешанном состоянии (часть областей DONE, часть PARTIAL/
BLOCKED); смотрящий — ADMIN/MANAGER на design-стадии.
1. Открыть воркспейс проекта → перейти на вкладку `readiness` (ключ таба, стадия `design`, гейт
   `PROJECTS` READ, label-ключ и позиция в `WORKSPACE_TABS` неизменны относительно плейсхолдера).
2. Виден общий donut с `Overall_Percentage` и `Overall_State`; по одной строке на каждую
   `Area_Contribution` в декларативном порядке (rooms → estimate → offer → documents → team →
   schedule, с учётом только читаемых областей).
3. В каждой строке: локализованное имя области (`t(area.nameKey)`), локализованная метка состояния
   (`readiness.state.done|partial|blocked` — состояние читается текстом, не только цветом),
   процент, и outstanding-items как локализованные предложения (`descriptionKey` + `params`).

_Покрывает:_ R8 (1, 2, 3, 7).

### TC-UI-TAB-02 — Jump-to-tab: активация item/строки ведёт в targetTab
1. Кликнуть outstanding-item у области `estimate` (например «N unpriced lines») → воркспейс
   навигирует на таб `estimate` того же проекта (`/projects/:id/estimate`).
2. Кликнуть по строке области `team` → навигация на таб `team`.
3. Каждый `targetTab` — валидный ключ `WORKSPACE_TABS` соответствующей области (`rooms`, `estimate`,
   `pricing`, `documentSigning`, `team`, `scheduleDesign`).

_Покрывает:_ R8 (4), R7 (3).

### TC-UI-TAB-03 — Loading / error+retry / empty
1. Во время загрузки readiness — неблокирующий skeleton, без сырого i18n-ключа.
2. При сбое запроса — локализованная ошибка с кнопкой retry; остальной воркспейс остаётся рабочим
   (другие табы доступны).
3. Для caller без READ любой области (`areas == []`) — локализованный empty-state «нечего показать /
   нет читаемых областей» при 0%, без падения и без сырого ключа.

_Покрывает:_ R8 (5, 6, 7).

### TC-UI-TAB-04 — Рефетч после изменения области (инвалидация)
**Предусловия:** открыт таб `readiness`; у области, например, `team` BLOCKED.
1. В другом табе (`team`) назначить ACTIVE FOREMAN и сохранить.
2. Вернуться на `readiness` (или дождаться инвалидации `readinessKeys.view(projectId)`) → строка
   `team` и общий donut обновились на DONE/новый процент без полной перезагрузки страницы.

_Покрывает:_ R9 (6), R8 (2).

## Фича 8. Header-компонент `ReadinessWidget` (R9)

### TC-UI-WIDGET-01 — Donut от overall % и по одному chip на область
**Предусловия:** design-стадия; смотрящий с несколькими Area_Resource READ.
1. `ReadinessWidget` показывает donut от `overallPercentage` агрегата (значение сервера, не
   пересчёт на клиенте).
2. По одному chip на каждую возвращённую `Area_Contribution`, подпись
   `${t(area.nameKey)}: ${t('readiness.state.'+state)}` на pl и ru; состояния различимы текстом.

_Покрывает:_ R9 (1, 2, 3).

### TC-UI-WIDGET-02 — Опущенная область отсутствует и не влияет на headline
**Предусловия:** смотрящий `TOKEN_ROOMS_ONLY` (только `ROOMS` READ сверх `PROJECTS`).
1. В виджете только chip `rooms`; chips estimate/offer/documents/team/schedule отсутствуют.
2. Headline-процент посчитан только по читаемым областям (сервером), опущенные не учтены.

_Покрывает:_ R9 (4), R6 (2, 3).

### TC-UI-WIDGET-03 — Плашка ошибки, без падения
1. При сбое запроса readiness виджет показывает локализованную плашку «не удалось загрузить
   готовность», без сырого ключа и без краша; остальной воркспейс доступен.

_Покрывает:_ R9 (5).

### TC-UI-WIDGET-04 — Стадийный гейт на call-site + консистентность с табом
1. Вне design-стадии (например ACTIVE) `ReadinessWidget` не рендерится (гейт на call-site
   `ProjectWorkspacePage` сохранён).
2. На design-стадии headline-процент виджета совпадает с donut вкладки `readiness` для того же
   проекта и смотрящего (единый источник — агрегат).

_Покрывает:_ R9 (3), R9 (2).

## Фича 9. Локализация UI (R10)

### TC-UI-I18N-01 — Паритет pl/ru, без сырых ключей
1. При pl и ru каждый видимый readiness-текст (label таба, headline, три метки состояний, имена
   областей, каждое outstanding-item-предложение с подставленными `params`, loading/empty/error,
   affordance «перейти в таб») рендерится на выбранном языке; нет сырых i18n-ключей и backend
   message code — ни во вкладке, ни в виджете.
2. Переключение языка при открытом табе `readiness` меняет все readiness-строки без нового запроса
   (API вернул ключи+params, фронт их резолвит).
3. Имена областей переиспользуют `workspace.tab.*`, а outstanding-item-описания — из namespace
   `readiness.*`.

_Покрывает:_ R10 (1, 2, 3, 4).

---

# Регрессия (R12)

Группа перепроверяет критичные сквозные пути и зафиксированные контракты. **Повторяемость:** генератор
`run-id` + teardown (созданные проекты переводятся в CANCELLED/удаляются, пользователи
деактивируются). Покрывает R12 критерии 1, 2, 4.

### TC-REG-01 — Пер-зонные readiness-эндпоинты не сломаны (R12.1)
1. `GET /api/project-members/readiness?projectId=E2E_PROJECT_ID` → **200** с прежней формой ответа
   (FOR-05-09), поведение неизменно.
2. `GET /api/project-schedules/readiness?projectId=E2E_PROJECT_ID` → **200** с прежней формой
   (FOR-05-10), поведение неизменно.
3. Значения этих эндпоинтов согласованы с соответствующими областями агрегата (`team` / `schedule`).

_Покрывает:_ R12 (1).

### TC-REG-02 — Резолверы `WORKSPACE_TABS` не сломаны после подмены панели (R12.2)
1. Юнит-тесты `resolveWorkspaceTabs` / `resolveActiveTab` / `resolveDesignSelectorTabs` проходят без
   изменений: таб `readiness` сохранил ключ, стадию `design`, гейт `PROJECTS` READ, label-ключ и
   позицию; изменилась только панель (с `PlaceholderTab` на `ReadinessTab`).

_Покрывает:_ R12 (2).

### TC-REG-03 — FOR-03-08: контроллер в перечне, нового ресурса нет (R12.4)
1. Enumeration-тест FOR-03-08 перечисляет `ProjectReadinessController` → `PROJECTS` READ.
2. Утверждается, что **новая строка ресурса не засижена** (нет `PROJECT_READINESS`); матрица
   `PROJECTS` неизменна.

_Покрывает:_ R12 (4), R2 (2).

### TC-REG-04 — Устойчивость к сбою одного contributor
**Предусловия:** область, чей contributor бросает исключение (искусственно, напр. при недоступном
соседнем сервисе), прочие области в норме.
1. `GET .../readiness` → **200**; сбойная область **опущена** (как нечитаемая для композиции),
   прочие области и headline рассчитаны по оставшимся; весь ответ не падает в 5xx.

_Покрывает:_ R12 (устойчивость readiness-поверхности; согласовано с темой «leave the rest usable»).

---

# Шаблон MD-репорта прогона

Заполняется по итогам каждого прогона (отдельно для Части A и Части B или единой таблицей).

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-GUARD-03 | 1 | GET /api/projects/{id}/readiness без токена | 401 | — | ⬜ |
| 2 | TC-READ-02 | 2 | GET .../readiness (пустой проект, ADMIN) | 200 + 6 областей BLOCKED/0% | — | ⬜ |
| 3 | TC-PSCOPE-02 | 1 | GET .../readiness (только PROJECTS READ) | 200 + areas=[] + 0% + BLOCKED | — | ⬜ |
| 4 | TC-UI-TAB-02 | 1 | Клик по unpriced-item | навигация на таб estimate | — | ⬜ |

Легенда статусов: ✅ пройдено · ❌ провалено · ⬜ не запускалось · ⏭️ пропущено · ⚠️ ожидаемое
ограничение (например отсутствие соседней области в сборке).

**Итог:** X пройдено / Y провалено / Z пропущено (из них W — ⚠️ известные ограничения).
Run-id: `<timestamp/uuid>`. Стратегия повторяемости: **генератор run-id в email/именах/проектах +
teardown созданных членств/offer/документов/расписаний/проектов; `Readiness_API` read-only (ничего не
создаёт); сид (роли, ресурсы) не изменяется**.
