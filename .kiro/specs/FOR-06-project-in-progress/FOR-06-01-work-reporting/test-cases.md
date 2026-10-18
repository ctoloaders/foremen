# Тест-кейсы: FOR-06-01 — Work reporting (отчётность о выполненных работах)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерные экраны**, поэтому набор тест-кейсов включает **оба
типа**:

- **Часть A. API-тесты** — против поднятого в Docker приложения (`http://localhost:8080`). Каждый
  шаг — HTTP-запрос к `WorkReport_API` (`/api/work-reports` CRUD) и к review-эндпоинтам
  (`/api/work-reports/{id}/approve`, `/reject`, `/request-changes`), с проверкой статуса, тела и
  заголовков. Проверяется статус-машина, деривации (`totalQuantity`, `revenueValue`), fast-path
  бригадира, work matching, заморозка выручки, ABAC-матрица `WORK_REPORTS` и own-records-скоуп
  воркера.
- **Часть B. Браузерные (UI) сценарии** — против фронтенда (`http://localhost:3000`), исполняются
  браузерным движком (headless-браузер / browser automation). Экраны: вкладка `workReporting`
  воркспейса `ACTIVE`-проекта — список отчётов с фильтрами, билдер отчёта
  (`WorkReportBuilder`: пикер воркера → R3-ограниченные work items, строки по помещениям с
  переключателем ABSOLUTE/PERCENT, период, медиа, заметка, localStorage-черновик) и ревью
  (`WorkReportReview`: approve / reject / request-changes + причина). Проверяются create → submit →
  approve/reject/request-changes, видимость собственных отчётов воркером, fast-path бригадира.

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание →
факт → статус). **Скриншоты не требуются.**

### Покрытие требований

Тест-кейсы покрывают требования R2.2 (percent/absolute), R3.2 (work matching), R4.1–R4.4 и R4.6/R4.7
(статус-машина и ревью-цикл), R5.1 (fast path), R6.1 (заморозка выручки), R9.1 (нотификации),
R10.4/R10.5 (ABAC-матрица и own-records), R11.3/R11.4 (UI-действия ревью и видимость воркера). Прочие
требования затрагиваются сопутствующими шагами. Соответствие «кейс ↔ требование» указано в каждом
кейсе строкой `_Покрывает:_`.

> **Известный открытый вопрос (R9.1 — resubmit-нотификация).** Дизайн §3.3 допускает переход
> `CHANGES_REQUESTED → SUBMITTED` (автор дорабатывает и повторно отправляет отчёт), но §4.4 явно
> перечисляет только четыре триггера нотификаций (submit не-бригадиром, approve, reject,
> request-changes) и **не оговаривает**, должна ли повторная отправка
> (`CHANGES_REQUESTED → SUBMITTED`) снова разослать `notification.workReport.submitted` активным
> бригадирам. Кейс **TC-API-RESUBMIT-NOTIFY** помечен маркером **[open-question-R9.1]** — тестировщик
> ОБЯЗАН зафиксировать фактическое поведение (разослалась нотификация или нет) в репорте со статусом
> ⚠️ и передать находку владельцу спеки для уточнения требования, а не трактовать любой из исходов как
> баг.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции,
   включая changesets `163`–`165` этой спеки), `backend` (:8080, профиль `docker`), `frontend`
   (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN — через переменные окружения `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`,
   `FOREMEN_ADMIN_PASSWORD`. Логин: `POST /api/auth/login {email, password}` → `accessToken`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном (проверка:
   `GET /api/work-reports?projectId=<любой доступный>` отвечает 200, т.е. ресурс `WORK_REPORTS`
   засижен и контроллер поднят без ошибки `PermissionAnnotationValidator`).

## Общая стратегия повторяемости

**Комбинированная: генератор `run-id` + teardown.** Единый на прогон `run-id` (timestamp или uuid,
например `1730000000` или `a1b2c3`) встраивается во все создаваемые идентификаторы, чтобы два
последовательных прогона на одной БД не конфликтовали:

- email тестовых пользователей: `foreman+{run-id}@example.com`, `worker+{run-id}@example.com`,
  `worker2+{run-id}@example.com`, `manager+{run-id}@example.com`, `estimator+{run-id}@example.com`,
  `client+{run-id}@example.com`;
- имена: `Foreman {run-id}`, `Worker {run-id}`, `Manager {run-id}`;
- имена проектов: `WorkReport Test {run-id}`;
- заметки отчётов: `note {run-id}` — для надёжного поиска созданных отчётов в списке.

**Teardown каждой группы** (если не указано иное) выполняется в конце набора в обратном порядке
создания: `DELETE /api/work-reports/{id}` для всех созданных **не-терминальных** отчётов
(`SUBMITTED` / `CHANGES_REQUESTED`); `APPROVED`/`REJECTED` терминальны и редактированию/удалению
через обычный флоу не подлежат — удаляются админ-API `DELETE /api/work-reports/{id}` под ADMIN (у
ADMIN есть DELETE-грант) там, где требуется чистота. Затем деактивация/удаление созданных
пользователей и перевод тестового проекта в `CANCELLED`/удаление доступным admin-API. Сид-данные
(роли, ресурс `WORK_REPORTS`, гранты) **только читаются, никогда не изменяются**. Каждая группа ниже
дополнительно уточняет свою стратегию строкой `**Повторяемость:**`.

### Замечание про роли (ABAC-матрица `WORK_REPORTS`)

Бутстрапится только ADMIN. Роли MANAGER / FOREMAN / WORKER / ESTIMATOR / FINANCIER / CLIENT
создаются и приглашаются в тестовый проект через admin-API в группе `Setup`. Матрица
`WORK_REPORTS` (дизайн §3.5): **ADMIN — CRUD (все); FOREMAN — CRUD (все воркеры) + ревью; WORKER —
CRUD только своих записей, без ревью; MANAGER — READ/UPDATE/DELETE (без CREATE), без ревью;
ESTIMATOR / FINANCIER — READ; CLIENT — без гранта**. Ресурс проектно-скоупленный: любой грант
действует только в рамках проектного членства вызывающего.

### Общие предзависимости доменной модели

Чтобы отчёт сохранился, в тестовом `ACTIVE`-проекте должны существовать: подписанная смета с
`EstimateLine` (замороженный `unitPrice`) по выбранному work item; план-количества
(`EstimateLineRoomQty`) по `(workItem, room)` — для PERCENT-строк; у воркера — проектные
work-категории (`project_member_work_categories`), покрывающие категорию выбранного work item
(Covering_Work). Группа `Setup` готовит эти данные; при их отсутствии ожидаемы ошибки
`error.workReport.workItem.notInEstimate`, `error.workReport.room.noPlan`,
`error.workReport.workItem.notCovered` соответственно.

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Setup — токены, проект, роли и доменные данные

**Повторяемость:** читает сид + создаёт помеченные `run-id` сущности; teardown в конце набора.

### TC-SETUP-01 — Логин ADMIN
**Предусловия:** стек поднят, ADMIN забутстрапен.
1. `POST /api/auth/login {email: <FOREMEN_ADMIN_EMAIL>, password: <FOREMEN_ADMIN_PASSWORD>}` →
   **200** + `accessToken`. Сохранить как `TOKEN_ADMIN`.

_Покрывает:_ инфраструктура (предзависимость всех групп).

### TC-SETUP-02 — Тестовый проект в статусе `ACTIVE` с подписанной сметой
**Предусловия:** TOKEN_ADMIN.
1. Создать проект `WorkReport Test {run-id}` (`POST /api/projects`), наполнить смету
   (`EstimateLine` c `unitPrice` по work item с известной `WorkCategory`), подписать её и перевести
   проект в `ACTIVE`. → проект доступен, его `status = ACTIVE`. Сохранить `PROJECT_ID`,
   `WORK_ITEM_ID`, `ESTIMATE_LINE_UNIT_PRICE`.
2. Создать как минимум два помещения с планом: `ROOM_A` c `EstimateLineRoomQty.quantity = PLAN_A`,
   `ROOM_B` c `PLAN_B`; и одно помещение без плана по этому work item: `ROOM_NOPLAN`. Сохранить
   `ROOM_A_ID`, `PLAN_A`, `ROOM_B_ID`, `PLAN_B`, `ROOM_NOPLAN_ID`.

_Покрывает:_ предзависимость R1/R2/R6 (смета + планы — источник `clientUnitPrice` и Plan_Quantity).

### TC-SETUP-03 — Пользователи ролей и проектные членства
**Предусловия:** TOKEN_ADMIN, PROJECT_ID.
1. Создать и пригласить в проект (ACTIVE-членства): FOREMAN `foreman+{run-id}`, WORKER
   `worker+{run-id}`, второй WORKER `worker2+{run-id}`, MANAGER `manager+{run-id}`, ESTIMATOR
   `estimator+{run-id}`, CLIENT `client+{run-id}`. → **201/200** на каждое. Сохранить
   `FOREMAN_USER_ID`, `WORKER_USER_ID`, `WORKER2_USER_ID`, `MANAGER_USER_ID`, `ESTIMATOR_USER_ID`,
   `CLIENT_USER_ID`.
2. Назначить WORKER (`worker+{run-id}`) проектную work-категорию, покрывающую `WorkCategory`
   у `WORK_ITEM_ID` (`project_member_work_categories`). WORKER2 (`worker2+{run-id}`) оставить
   **без** покрывающей категории.
3. Залогинить каждого (`POST /api/auth/login`) → сохранить `TOKEN_FOREMAN`, `TOKEN_WORKER`,
   `TOKEN_WORKER2`, `TOKEN_MANAGER`, `TOKEN_ESTIMATOR`, `TOKEN_CLIENT`.

_Покрывает:_ предзависимость R3/R4/R5/R10 (роли, approver-бригадир, Covering_Work).

---

## Фича 1. Создание отчёта и деривации (R1.4, R1.5, R2.2, R2.6)

**Повторяемость:** все созданные отчёты помечены `note {run-id}`; teardown удаляет не-терминальные,
`APPROVED`/`REJECTED` чистятся DELETE под ADMIN.

### TC-API-CREATE-01 — Воркер создаёт отчёт (ABSOLUTE), статус `SUBMITTED`, деривации верны
**Предусловия:** TOKEN_WORKER, PROJECT_ID, WORK_ITEM_ID, ROOM_A_ID, ROOM_B_ID.
1. `POST /api/work-reports` (Bearer WORKER) с телом: `projectId=PROJECT_ID`,
   `workItemId=WORK_ITEM_ID`, `workerId=WORKER_USER_ID`, `performedFrom`/`performedTo` (валидный
   интервал), `rooms=[{roomId:ROOM_A_ID, quantity:Q_A, inputMode:ABSOLUTE},
   {roomId:ROOM_B_ID, quantity:Q_B, inputMode:ABSOLUTE}]`, `note="note {run-id}"`. →
   **201**; `status = SUBMITTED`. Сохранить `REPORT_ID_1`.
2. Проверить в теле ответа: `totalQuantity = Q_A + Q_B` (R1.4), `clientUnitPrice =
   ESTIMATE_LINE_UNIT_PRICE` (амендменты = 0, R6.2), `revenueValue = round2(clientUnitPrice ×
   totalQuantity)` (R1.5). `costValue` и `margin` — отсутствуют или явный `null` (R6.5).
3. Проверить, что каждая строка `rooms` имеет снимок `planQuantity` (для ROOM_A → `PLAN_A`,
   ROOM_B → `PLAN_B`) и `inputMode = ABSOLUTE` (R2.6).

_Покрывает:_ R1.4, R1.5, R2.6, R4.1.

### TC-API-CREATE-02 — PERCENT-строка резолвится в абсолют `planQuantity × percent / 100`
**Предусловия:** TOKEN_WORKER, ROOM_A_ID с `PLAN_A`.
1. `POST /api/work-reports` (Bearer WORKER) с одной строкой
   `{roomId:ROOM_A_ID, quantity:50, inputMode:PERCENT}` (50% плана). → **201**; `status =
   SUBMITTED`. Сохранить `REPORT_ID_PCT`.
2. Проверить: в сохранённой строке `quantity = PLAN_A × 50 / 100`, `inputMode = PERCENT`,
   `planQuantity = PLAN_A`; `totalQuantity` равен этому резолвнутому количеству.

_Покрывает:_ R2.2.

### TC-API-CREATE-03 — PERCENT без плана отклоняется `error.workReport.room.noPlan`
**Предусловия:** TOKEN_WORKER, ROOM_NOPLAN_ID (нет `EstimateLineRoomQty`).
1. `POST /api/work-reports` (Bearer WORKER) со строкой
   `{roomId:ROOM_NOPLAN_ID, quantity:30, inputMode:PERCENT}`. → **400/422**; код
   `error.workReport.room.noPlan`, в сообщении назван room; ничего не создано.
2. Тот же room в режиме `ABSOLUTE` (`{roomId:ROOM_NOPLAN_ID, quantity:5, inputMode:ABSOLUTE}`) →
   **201**; строка сохранена с `planQuantity = null`.

_Покрывает:_ R2.2, R2.3.

### TC-API-CREATE-04 — Дубликат помещения отклоняется `error.workReport.room.duplicate`
**Предусловия:** TOKEN_WORKER.
1. `POST /api/work-reports` с двумя строками по одному `ROOM_A_ID`. → **400/422**; код
   `error.workReport.room.duplicate`, назван room; ничего не создано.

_Покрывает:_ R2.4.

### TC-API-CREATE-05 — Невалидное количество отклоняется `error.workReport.quantity.invalid`
**Предусловия:** TOKEN_WORKER.
1. `POST /api/work-reports` со строкой `{roomId:ROOM_A_ID, quantity:0, inputMode:ABSOLUTE}` →
   **400/422**; код `error.workReport.quantity.invalid`; ничего не создано.
2. Повторить с `quantity:-3` → тот же код **400/422**.

_Покрывает:_ R2.5.

---

## Фича 2. Work matching (R3.2, R3.4)

**Повторяемость:** попытки создания отклоняются сервером (ничего не создаётся); отдельного teardown
не требуется.

### TC-API-MATCH-01 — Work item вне work-категорий воркера → `notCovered`
**Предусловия:** TOKEN_WORKER2 (WORKER2 без покрывающей категории), WORK_ITEM_ID.
1. `POST /api/work-reports` (Bearer WORKER2) с `workerId=WORKER2_USER_ID`,
   `workItemId=WORK_ITEM_ID`. → **400/422**; код `error.workReport.workItem.notCovered`, назван
   work item; ничего не создано.

_Покрывает:_ R3.2.

### TC-API-MATCH-02 — Work item вне сметы → `notInEstimate`
**Предусловия:** TOKEN_WORKER, `WORK_ITEM_NOT_IN_ESTIMATE_ID` (существует в каталоге, но не на смете
проекта).
1. `POST /api/work-reports` (Bearer WORKER) c `workItemId=WORK_ITEM_NOT_IN_ESTIMATE_ID`. →
   **400/422**; код `error.workReport.workItem.notInEstimate`, назван work item; ничего не создано.

_Покрывает:_ R3.4.

---

## Фича 3. Статус-машина и ревью-цикл (R4.1, R4.2, R4.3, R4.4, R4.5, R4.6, R4.7)

**Повторяемость:** каждый сценарий создаёт собственный `SUBMITTED`-отчёт (с `note {run-id}`); approve
переводит в терминал, teardown чистит DELETE под ADMIN.

### TC-API-STATUS-01 — Approve `SUBMITTED` → `APPROVED`, approvedBy/approvedAt заданы
**Предусловия:** TOKEN_WORKER создаёт `REPORT_ID` (SUBMITTED); TOKEN_FOREMAN — approver.
1. `POST /api/work-reports/{REPORT_ID}/approve` (Bearer FOREMAN) → **200**; `status = APPROVED`,
   `approvedById = FOREMAN_USER_ID`, `approvedAt` непустой.
2. `GET /api/work-reports/{REPORT_ID}` → `clientUnitPrice`/`revenueValue` присутствуют и заморожены.

_Покрывает:_ R4.2.

### TC-API-STATUS-02 — Reject без причины отклоняется `rejection.reasonRequired`
**Предусловия:** свежий `SUBMITTED` `REPORT_ID`; TOKEN_FOREMAN.
1. `POST /api/work-reports/{REPORT_ID}/reject` (Bearer FOREMAN) с пустой/отсутствующей причиной →
   **400/422**; код `error.workReport.rejection.reasonRequired`; статус не изменился (`SUBMITTED`).
2. Повтор с непустой `reason` → **200**; `status = REJECTED`, `rejectionReason` сохранён.

_Покрывает:_ R4.3.

### TC-API-STATUS-03 — Request-changes → `CHANGES_REQUESTED`, затем автор resubmit → `SUBMITTED`
**Предусловия:** свежий `SUBMITTED` `REPORT_ID` (автор WORKER); TOKEN_FOREMAN, TOKEN_WORKER.
1. `POST /api/work-reports/{REPORT_ID}/request-changes` (Bearer FOREMAN) c непустой `reason` →
   **200**; `status = CHANGES_REQUESTED`.
2. Request-changes с пустой причиной (на новом отчёте) → **400/422** (причина обязательна, R4.4).
3. `PUT /api/work-reports/{REPORT_ID}` (Bearer WORKER, автор) с правкой строк → **200**; статус
   возвращается в `SUBMITTED` (resubmit, R4.5).

_Покрывает:_ R4.4, R4.5.

### TC-API-STATUS-04 — Запрещённые переходы → `status.illegalTransition`
**Предусловия:** один `APPROVED` `REPORT_ID_APPROVED`, один `REJECTED` `REPORT_ID_REJECTED`.
1. `PUT /api/work-reports/{REPORT_ID_APPROVED}` (правка APPROVED) → **400/409**; код
   `error.workReport.status.illegalTransition`; ничего не изменилось (R6.4, R4.6).
2. `POST /api/work-reports/{REPORT_ID_REJECTED}/approve` → **400/409**; код
   `error.workReport.status.illegalTransition` (REJECTED терминален).

_Покрывает:_ R4.6.

### TC-API-STATUS-05 — Воркер не может ревьюить (даже свой отчёт)
**Предусловия:** `SUBMITTED` `REPORT_ID` автора WORKER; TOKEN_WORKER.
1. `POST /api/work-reports/{REPORT_ID}/approve` (Bearer WORKER) → **403**; статус не изменился.
2. То же для `/reject` и `/request-changes` (Bearer WORKER) → **403** на каждый.

_Покрывает:_ R4.7, R10.5.

### TC-API-STATUS-06 — MANAGER держит UPDATE/DELETE, но не approver
**Предусловия:** `SUBMITTED` `REPORT_ID`; TOKEN_MANAGER.
1. `POST /api/work-reports/{REPORT_ID}/approve` (Bearer MANAGER) → **403** (approver только
   FOREMAN/ADMIN, R4.7); статус не изменился.
2. `PUT /api/work-reports/{REPORT_ID}` (Bearer MANAGER, обычная правка не-терминального) → **200**
   (UPDATE-грант есть).

_Покрывает:_ R4.7, R10.7.

---

## Фича 4. Fast path бригадира (R5.1, R5.2, R5.3, R5.4)

**Повторяемость:** fast-path отчёты сразу `APPROVED` (терминал); teardown DELETE под ADMIN.

### TC-API-FAST-01 — Отчёт, созданный FOREMAN о своей работе, сразу `APPROVED`, без нотификации
**Предусловия:** TOKEN_FOREMAN; FOREMAN сам имеет покрывающую категорию по `WORK_ITEM_ID`.
1. `POST /api/work-reports` (Bearer FOREMAN) с `workerId=FOREMAN_USER_ID`. → **201**; `status =
   APPROVED` сразу (без `SUBMITTED`), `approvedById = FOREMAN_USER_ID`, `approvedAt` = момент
   создания.
2. Проверить, что ревью-нотификация **не** эмитится (см. нотификации TC-API-NOTIFY-04).
3. `revenueValue`/`clientUnitPrice` заморожены как при обычном approve (R5.4, R6).

_Покрывает:_ R5.1, R5.4.

### TC-API-FAST-02 — FOREMAN о работе другого воркера → тоже fast path
**Предусловия:** TOKEN_FOREMAN; WORKER_USER_ID с покрывающей категорией.
1. `POST /api/work-reports` (Bearer FOREMAN) с `workerId=WORKER_USER_ID` (чужая работа). → **201**;
   `status = APPROVED`; `worker` = WORKER_USER_ID, `author` = FOREMAN_USER_ID; submit-нотификация
   не эмитится (R5.3).

_Покрывает:_ R5.1, R5.3.

---

## Фича 5. Заморозка выручки (R6.1, R6.2, R6.4)

**Повторяемость:** читает замороженные значения; изменение сметы — обратимое (teardown возвращает
исходный `unitPrice`) либо выполняется на отдельном тестовом work item.

### TC-API-FREEZE-01 — После approve изменение сметы не меняет отчёт
**Предусловия:** `APPROVED` `REPORT_ID` c зафиксированными `clientUnitPrice`/`revenueValue`.
1. Запомнить `clientUnitPrice_0`, `revenueValue_0` из `GET /api/work-reports/{REPORT_ID}`.
2. Изменить `EstimateLine.unitPrice` соответствующей строки (через signed amendment / admin-путь,
   где доступно). 
3. Повторный `GET /api/work-reports/{REPORT_ID}` → `clientUnitPrice`/`revenueValue` **равны**
   `clientUnitPrice_0`/`revenueValue_0` (не пересчитались, R6.4).

_Покрывает:_ R6.1, R6.2, R6.4.

---

## Фича 6. ABAC-матрица `WORK_REPORTS` и own-records воркера (R10.4, R10.5, R10.7, R10.8)

**Повторяемость:** читает/пробует операции под разными токенами; созданные под воркером отчёты
помечены `run-id`; teardown DELETE под ADMIN.

### TC-API-ABAC-01 — 401 без токена
1. `GET /api/work-reports?projectId=PROJECT_ID` без `Authorization` → **401**.

_Покрывает:_ R10 (аутентификация до скоупа).

### TC-API-ABAC-02 — WORKER видит и трогает только свои отчёты
**Предусловия:** два отчёта: `REPORT_W1` (worker=WORKER_USER_ID), `REPORT_W2`
(worker=WORKER2_USER_ID); TOKEN_WORKER.
1. `GET /api/work-reports?projectId=PROJECT_ID` (Bearer WORKER) → **200**; список содержит
   `REPORT_W1` и **не** содержит `REPORT_W2` (own-records фильтр).
2. `GET /api/work-reports/{REPORT_W2}` (Bearer WORKER) → **403/404**; код
   `error.workReport.access.notOwner`.
3. `PUT /api/work-reports/{REPORT_W2}` / `DELETE /api/work-reports/{REPORT_W2}` (Bearer WORKER) →
   **403/404**; код `error.workReport.access.notOwner`; ничего не изменилось.

_Покрывает:_ R10.5.

### TC-API-ABAC-03 — WORKER не может создать отчёт на чужого воркера → `worker.notSelf`
**Предусловия:** TOKEN_WORKER, WORKER2_USER_ID.
1. `POST /api/work-reports` (Bearer WORKER) с `workerId=WORKER2_USER_ID`. → **400/403**; код
   `error.workReport.worker.notSelf`; ничего не создано.

_Покрывает:_ R10.5.

### TC-API-ABAC-04 — MANAGER: нет CREATE, есть READ/UPDATE/DELETE
**Предусловия:** TOKEN_MANAGER; существующий не-терминальный `REPORT_ID`.
1. `POST /api/work-reports` (Bearer MANAGER) → **403** (нет CREATE-гранта, R10.7).
2. `GET /api/work-reports?projectId=PROJECT_ID` (Bearer MANAGER) → **200**; видит все отчёты
   проекта.
3. `PUT /api/work-reports/{REPORT_ID}` → **200**; `DELETE /api/work-reports/{REPORT_ID}` → **200/204**.

_Покрывает:_ R10.4, R10.7.

### TC-API-ABAC-05 — ESTIMATOR/FINANCIER: только READ; CLIENT: без гранта
**Предусловия:** TOKEN_ESTIMATOR, TOKEN_CLIENT; существующий `REPORT_ID`.
1. `GET /api/work-reports?projectId=PROJECT_ID` (Bearer ESTIMATOR) → **200** (READ есть).
2. `POST` / `PUT` / `DELETE` (Bearer ESTIMATOR) → **403** на каждый (нет write-грантов, R10.8).
3. Любая операция (Bearer CLIENT) по `WORK_REPORTS` → **403** (deny-by-default, R10.8).

_Покрывает:_ R10.4, R10.8.

### TC-API-ABAC-06 — FOREMAN: полный CRUD над отчётами всех воркеров
**Предусловия:** TOKEN_FOREMAN; `REPORT_W1` (worker=WORKER), `REPORT_W2` (worker=WORKER2).
1. `GET /api/work-reports?projectId=PROJECT_ID` (Bearer FOREMAN) → **200**; видит `REPORT_W1` **и**
   `REPORT_W2` (все воркеры).
2. `PUT` не-терминального чужого отчёта → **200**; `DELETE` не-терминального → **200/204**.

_Покрывает:_ R10.4.

---

## Фича 7. Нотификации (R9.1, R9.2, R9.3, R9.4, R9.5)

**Повторяемость:** проверка через Bell-API получателя; созданные отчёты помечены `run-id`; teardown
DELETE под ADMIN. Нотификации читаются, не удаляются (или помечаются прочитанными в teardown).

### TC-API-NOTIFY-01 — Submit не-бригадиром рассылает `submitted` всем ACTIVE FOREMAN
**Предусловия:** TOKEN_WORKER (автор), FOREMAN — ACTIVE member проекта.
1. `POST /api/work-reports` (Bearer WORKER) → **201** `SUBMITTED`; сохранить `REPORT_ID`.
2. Прочитать нотификации FOREMAN (Bearer FOREMAN, Bell-API) → присутствует запись типа
   `notification.workReport.submitted` с deep-link `/projects/{PROJECT_ID}/workReporting?report={REPORT_ID}`.

_Покрывает:_ R9.1.

### TC-API-NOTIFY-02 — Approve/Reject/Request-changes шлют автору
**Предусловия:** `SUBMITTED` `REPORT_ID` автора WORKER; TOKEN_FOREMAN.
1. Approve → у автора (WORKER) появляется `notification.workReport.approved` (R9.2).
2. На новом отчёте: Reject c причиной → у автора `notification.workReport.rejected`, тело содержит
   причину (R9.3).
3. На новом отчёте: Request-changes c причиной → у автора
   `notification.workReport.changesRequested`, тело содержит причину (R9.4).

_Покрывает:_ R9.2, R9.3, R9.4.

### TC-API-NOTIFY-03 — Fast path не эмитит нотификаций
**Предусловия:** TOKEN_FOREMAN.
1. `POST /api/work-reports` (Bearer FOREMAN, самоотчёт) → `APPROVED`.
2. Проверить Bell FOREMAN и автора: **нет** ни submit-, ни approve-нотификации по этому отчёту (R9.5).

_Покрывает:_ R9.5.

### TC-API-RESUBMIT-NOTIFY — **[open-question-R9.1]** повторная отправка после CHANGES_REQUESTED
**Предусловия:** отчёт автора WORKER в статусе `CHANGES_REQUESTED`; TOKEN_WORKER; FOREMAN — ACTIVE.
1. `PUT /api/work-reports/{REPORT_ID}` (Bearer WORKER) с правкой → **200**; статус → `SUBMITTED`
   (resubmit).
2. Прочитать Bell FOREMAN → **зафиксировать факт**: разослалась ли повторная
   `notification.workReport.submitted` активным бригадирам.
   - Дизайн §4.4 перечисляет только четыре триггера и не оговаривает resubmit-рассылку.
   - **Статус в репорте — ⚠️** (open question), с указанием фактического поведения; находку передать
     владельцу спеки для уточнения R9.1. Любой из исходов (разослалась / не разослалась) НЕ является
     багом до уточнения требования.

_Покрывает:_ R9.1 (пограничный случай — требует уточнения).

---

# Часть B. Браузерные (UI) сценарии (frontend)

Экраны: вкладка `workReporting` воркспейса `ACTIVE`-проекта. Все сценарии — на том же тестовом
проекте и ролях из группы `Setup`. Вход на фронтенд: `http://localhost:3000`, логин под нужной ролью.

**Повторяемость:** UI создаёт отчёты с заметкой `note {run-id}`; teardown — те же DELETE под ADMIN,
что и в части A; localStorage-черновики чистятся кнопкой «discard» в сценарии TC-UI-DRAFT.

### TC-UI-NAV-01 — Вкладка `workReporting` видна на ACTIVE-проекте допущенной роли
**Предусловия:** логин FOREMAN; проект `ACTIVE`.
1. Открыть воркспейс проекта → в списке вкладок присутствует `workReporting` (ресурс засижен,
   `WORK_REPORTS`/READ гейт пройден).
2. Кликнуть вкладку → рендерится панель отчётности (не `PlaceholderTab`): список + действие «новый
   отчёт».

_Покрывает:_ R11.1, R10.6.

### TC-UI-CREATE-01 — Воркер создаёт отчёт через билдер (ABSOLUTE), submit
**Предусловия:** логин WORKER; у воркера есть покрывающая категория.
1. Открыть вкладку → «новый отчёт» → в билдере пикер воркера предзаполнен собой; список work items
   ограничен Covering_Works (R3-limited).
2. Выбрать work item, добавить строку по `ROOM_A`, режим `ABSOLUTE`, ввести количество, задать
   период, заметку `note {run-id}`.
3. Submit → тост `toast.submitted`; отчёт появляется в списке со статусом-бейджем `SUBMITTED`,
   колонками worker / work item / период / количество / выручка / статус.

_Покрывает:_ R11.1, R11.2, R2.2.

### TC-UI-CREATE-02 — Переключатель PERCENT/ABSOLUTE в билдере
**Предусловия:** логин WORKER; `ROOM_A` имеет план.
1. В строке `ROOM_A` переключить на `PERCENT`, ввести `50`. → UI показывает резолв в абсолют
   `PLAN_A × 50 / 100` (или сохраняет и список показывает резолвнутое количество).
2. Для `ROOM_NOPLAN` в режиме `PERCENT` при submit → inline-ошибка `error.workReport.room.noPlan`
   (локализованная), submit не проходит.

_Покрывает:_ R2.2, R11.5.

### TC-UI-DRAFT-01 — localStorage-черновик: continue / discard
**Предусловия:** логин WORKER.
1. Начать заполнять билдер (воркер, work item, строки, заметка), **не** submit, уйти со страницы.
2. Вернуться на вкладку → предложение восстановить черновик (`form.draftRestored`) с выбором
   «continue» / «discard».
3. «continue» → форма восстановлена с прежними значениями. Повторить, выбрать «discard» → форма
   пустая, ключ `workReport:draft:{projectId}:{userId}` в localStorage очищен.

_Покрывает:_ R7.1, R7.2, R11.2.

### TC-UI-REVIEW-01 — Бригадир видит approve/reject/request-changes на SUBMITTED
**Предусловия:** логин FOREMAN; есть `SUBMITTED`-отчёт (из TC-UI-CREATE-01).
1. Открыть вкладку → на строке `SUBMITTED` доступны действия approve / reject / request-changes
   (R11.3).
2. Approve → тост `toast.approved`; бейдж меняется на `APPROVED`; действия ревью исчезают.

_Покрывает:_ R11.3, R4.2.

### TC-UI-REVIEW-02 — Reject и request-changes требуют причину
**Предусловия:** логин FOREMAN; свежий `SUBMITTED`-отчёт.
1. Reject без причины → поле `form.rejectionReason` подсвечивается как обязательное, запрос не
   уходит.
2. Ввести причину → reject проходит; бейдж `REJECTED`; тост `toast.rejected`.
3. На другом отчёте: request-changes c причиной → бейдж `CHANGES_REQUESTED`; тост
   `toast.changesRequested`.

_Покрывает:_ R11.3, R4.3, R4.4.

### TC-UI-WORKER-SCOPE-01 — Воркер видит только свои отчёты, без действий ревью
**Предусловия:** логин WORKER; в проекте есть отчёты WORKER и WORKER2.
1. Открыть вкладку → список содержит только отчёты текущего воркера; отчётов WORKER2 нет (R11.4).
2. На собственных не-терминальных отчётах доступны edit/создание; действий approve/reject/
   request-changes **нет** ни на одной строке.

_Покрывает:_ R11.4, R10.5.

### TC-UI-FAST-01 — Самоотчёт бригадира сразу APPROVED в UI
**Предусловия:** логин FOREMAN.
1. Создать отчёт о своей работе через билдер → после submit строка сразу со статусом `APPROVED`
   (без промежуточного `SUBMITTED`), ревью-действий нет.

_Покрывает:_ R5.1 (UI-отражение fast path), R11.3.

---

# Регрессия

**Повторяемость:** регрессионные кейсы читают неизменяемый сид и системные флоу; собственные
созданные сущности чистятся общим teardown.

### REG-01 — Воркспейс-шелл и резолвер вкладок не сломаны (FOR-05 / FOR-06-00)
1. На `ACTIVE`-проекте вкладки `resolveWorkspaceTabs` рендерятся как прежде, `stageOf` даёт
   execution-стадию; прочие execution-вкладки показывают свои плейсхолдеры без ошибок.
2. На `DRAFT`-проекте вкладка `workReporting` **отсутствует**; на `COMPLETED` — отчётность
   read-only (создание/переходы недоступны).

_Покрывает:_ R10.1 (стадийное гейтирование), R13.2.

### REG-02 — Нотификационный сервис и Bell работают как прежде
1. Существующие типы нотификаций (FOR-05-07) продолжают доставляться и отображаться в Bell; новые
   `notification.workReport.*` не ломают список.

_Покрывает:_ R9.6, R13.2.

### REG-03 — Старт приложения без `PermissionAnnotationValidator`-ошибки
1. После деплоя changesets `163`–`165` backend стартует без ошибки валидатора аннотаций; контроллер
   `WorkReportController` аннотирован `@PermissionResource("WORK_REPORTS")`, все in-scope-хендлеры
   имеют `@PermissionOperation`/`@RequiresPermission`.

_Покрывает:_ R10.3.

### REG-04 — Идемпотентность сидов 163–165
1. Повторный прогон миграций на уже-засиженной БД не создаёт дублей таблиц / строки ресурса
   `WORK_REPORTS` / грантов (precondition `MARK_RAN` / `SELECT COUNT(*)=0`).

_Покрывает:_ R1.3, R10.4 (идемпотентность сида, P10).

### REG-05 — Локаль-паритет PL/RU
1. `pl.json` и `ru.json` имеют идентичный набор ключей после правок; все
   `workReporting.*`, статус-бейджи, `notification.workReport.*` присутствуют в обоих; бэкенд-бандл
   содержит все `error.workReport.*` в PL и RU.

_Покрывает:_ R12.2, R12.3, R12.4.

---

# Шаблон MD-репорта прогона

Заполняется по итогам каждого прогона (отдельная таблица для части A и части B либо единая сквозная).

## Репорт прогона — Часть A (API)

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SETUP-01 | 1 | POST /api/auth/login | 200 + accessToken | | |
| 2 | TC-API-CREATE-01 | 1 | POST /api/work-reports (ABSOLUTE) | 201 + SUBMITTED | | |
| 3 | TC-API-CREATE-01 | 2 | тело ответа | totalQuantity=Q_A+Q_B, revenueValue=round2(...) | | |
| 4 | TC-API-CREATE-02 | 1 | POST (PERCENT 50%) | 201, quantity=PLAN_A×0.5 | | |
| 5 | TC-API-CREATE-03 | 1 | POST (PERCENT, no plan) | 400 error.workReport.room.noPlan | | |
| 6 | TC-API-STATUS-01 | 1 | POST /{id}/approve | 200 + APPROVED | | |
| 7 | TC-API-STATUS-02 | 1 | POST /{id}/reject (no reason) | 400 reasonRequired | | |
| 8 | TC-API-FAST-01 | 1 | POST (FOREMAN self) | 201 + APPROVED, no notify | | |
| 9 | TC-API-ABAC-02 | 2 | GET /{reportW2} as WORKER | 403/404 notOwner | | |
| 10 | TC-API-ABAC-03 | 1 | POST worker=other as WORKER | 400/403 worker.notSelf | | |
| 11 | TC-API-NOTIFY-01 | 2 | Bell FOREMAN | есть .submitted + deep-link | | |
| 12 | TC-API-RESUBMIT-NOTIFY | 2 | Bell FOREMAN | ⚠️ зафиксировать факт (open R9.1) | | |

## Репорт прогона — Часть B (UI)

| # | Тест-кейс | Шаг | Действие в браузере | Ожидание | Факт | Статус |
|---|-----------|-----|---------------------|----------|------|--------|
| 1 | TC-UI-NAV-01 | 1 | открыть воркспейс ACTIVE | вкладка workReporting видна | | |
| 2 | TC-UI-CREATE-01 | 3 | submit отчёта | тост submitted + строка SUBMITTED | | |
| 3 | TC-UI-DRAFT-01 | 2 | вернуться на вкладку | предложение continue/discard | | |
| 4 | TC-UI-REVIEW-01 | 2 | approve | бейдж APPROVED, тост approved | | |
| 5 | TC-UI-REVIEW-02 | 1 | reject без причины | поле причины обязательно | | |
| 6 | TC-UI-WORKER-SCOPE-01 | 1 | список как WORKER | только свои отчёты, нет ревью | | |
| 7 | TC-UI-FAST-01 | 1 | самоотчёт FOREMAN | строка сразу APPROVED | | |

**Итог:** X пройдено / Y провалено / Z пропущено / W ⚠️ (open question).
Run-id: `<timestamp/uuid>`. Стратегия повторяемости: `генератор run-id + teardown (DELETE под ADMIN)`.
Открытые вопросы: `R9.1 — resubmit-нотификация (TC-API-RESUBMIT-NOTIFY)`.

---

# Amendment A1 — Матричное пакетное внесение (matrix batch authoring)

Amendment A1 заменяет одиночный `WorkReportBuilder` матричной поверхностью пакетного внесения и
добавляет два бэкенд-эндпоинта: прогресс-проекцию чтения (`GET /api/work-reports/progress`) и
массовое создание (`POST /api/work-reports/batch`). Новых сущностей, схемы, ABAC-ресурсов и типов
нотификаций **нет** — переиспользуются `WorkReport` / `WorkReportRoom`, ресурс `WORK_REPORTS`,
статус-машина, оценка выручки, fast-path бригадира и четыре типа `notification.workReport.*`.

Эта группа тест-кейсов **дополняет** набор выше и опирается на те же `Setup`-данные части A (проект
`ACTIVE`, подписанная смета с планами `EstimateLineRoomQty`, роли с проектными членствами, воркер с
покрывающей категорией). Как и основной набор, она состоит из двух частей:

- **Часть A1-API** — HTTP-тесты против docker-стека (`http://localhost:8080`): прогресс-проекция и
  пакетное создание (all-or-nothing + успех).
- **Часть A1-UI** — браузерные сценарии (`http://localhost:3000`), исполняются браузерным движком:
  выбор воркера → ввод в ячейку → накопление черновика → массовое сохранение → отражение в прогрессе;
  мобильный схлопываемый черновик.

### Покрытие требований (Amendment A1)

Тест-кейсы покрывают R14.1 (прогресс-проекция как источник матрицы), R15.1 (ввод в ячейку и
клиентский черновик), R16.1 (пакетное сохранение), R17.1 (выбор воркера и матричная поверхность),
R18.2 (мобильный схлопываемый черновик). Сопутствующе затрагиваются R14.2/R14.4 (подсчёт
`reportedQuantity`, скрытие 100%-work item), R15.3 (PERCENT-без-плана), R16.2/R16.4/R16.5
(группировка в payloads, all-or-nothing, нотификации на коммите), R17.2/R17.3 (disabled-ячейка,
редактируемые строки черновика). Соответствие «кейс ↔ требование» указано строкой `_Покрывает:_`.

## Общая стратегия повторяемости (Amendment A1)

**Комбинированная: тот же генератор `run-id` + teardown**, что и в основном наборе. Пакетно
создаваемые отчёты помечаются заметкой `batch {run-id}` (поле `note` каждого элемента), чтобы их
можно было найти и вычистить. Teardown в конце группы: `DELETE /api/work-reports/{id}` под ADMIN для
всех созданных пакетом отчётов (не-терминальные `SUBMITTED`/`CHANGES_REQUESTED` — обычным флоу,
`APPROVED` fast-path бригадира — DELETE под ADMIN). UI-черновики (localStorage, ключ
`workReport:draft:{projectId}:{userId}`) чистятся кнопкой «discard» / успешным сохранением в самом
сценарии. Прогресс-проекция только читается. Сид (роли, ресурс `WORK_REPORTS`, гранты) неизменяем.

> **Доменная предзависимость.** Для содержательной матрицы у воркера (`WORKER_USER_ID`) должна быть
> покрывающая work-категория по `WORK_ITEM_ID`, а у work item — планы `EstimateLineRoomQty` по
> `ROOM_A`/`ROOM_B` (`PLAN_A`/`PLAN_B`) и хотя бы одно помещение без плана (`ROOM_NOPLAN`). Эти
> данные готовит группа `Setup` части A (TC-SETUP-02/03) — она является предусловием всех кейсов
> ниже.

---

# Часть A1-API. Прогресс-проекция и пакетное создание (docker-стек)

## Фича A1-1. Прогресс-проекция матрицы (R14.1, R14.2, R14.4, R14.6)

**Повторяемость:** проекция только читается; созданные для проверки подсчёта отчёты помечены
`batch {run-id}` и чистятся общим teardown.

### TC-API-PROGRESS-01 — Проекция возвращает оси и ячейки для воркера с покрывающей категорией
**Предусловия:** TOKEN_WORKER, PROJECT_ID, WORKER_USER_ID (есть покрывающая категория по
`WORK_ITEM_ID`); планы `PLAN_A`/`PLAN_B` по `ROOM_A`/`ROOM_B`; отчётов по этому воркеру ещё нет.
1. `GET /api/work-reports/progress?projectId=PROJECT_ID&workerId=WORKER_USER_ID` (Bearer WORKER) →
   **200**; тело — `WorkReportProgressDto` c `projectId`, `workerId`, непустым `rooms[]` (ось
   столбцов, содержит `ROOM_A`/`ROOM_B`) и `categories[]` → `workItems[]` → `cells[]`.
2. Проверить: `WORK_ITEM_ID` присутствует в соответствующей `WorkCategory`-группе (входит в
   пересечение Covering_Works ∩ work items сметы, R14.1); имена локализованы ru→pl→code, у work item
   задан `unitCode`.
3. Для ячеек `(WORK_ITEM_ID, ROOM_A)` и `(…, ROOM_B)`: `planQuantity = PLAN_A` / `PLAN_B`,
   `reportedQuantity = 0`, `reportedPercent = 0`. Для `(…, ROOM_NOPLAN)` (если возвращается):
   `planQuantity = null`, `reportedPercent = null`.

_Покрывает:_ R14.1, R14.3.

### TC-API-PROGRESS-02 — `reportedQuantity` суммирует SUBMITTED + CHANGES_REQUESTED + APPROVED, исключает REJECTED
**Предусловия:** TOKEN_WORKER; свежая проекция (reportedQuantity = 0 по `ROOM_A`).
1. Создать по `(WORK_ITEM_ID, ROOM_A)` три отчёта в разных статусах: `R_SUB` (остаётся `SUBMITTED`,
   `quantity = q1`), `R_CHG` (перевести в `CHANGES_REQUESTED` через request-changes, `quantity =
   q2`), `R_APP` (approve → `APPROVED`, `quantity = q3`); и один `R_REJ` (reject → `REJECTED`,
   `quantity = q4`). Все с `note="batch {run-id}"`.
2. `GET /api/work-reports/progress?projectId=PROJECT_ID&workerId=WORKER_USER_ID` → для ячейки
   `(WORK_ITEM_ID, ROOM_A)` `reportedQuantity = q1 + q2 + q3` (REJECTED `q4` **исключён**, R14.2),
   `reportedPercent = round(reportedQuantity / PLAN_A × 100)`.

_Покрывает:_ R14.2.

### TC-API-PROGRESS-03 — Work item со всеми планами ≥100% скрывается из проекции
**Предусловия:** TOKEN_WORKER; у `WORK_ITEM_ID` все плановые помещения (`ROOM_A`, `ROOM_B`) имеют
планы; `ROOM_NOPLAN` по этому work item отсутствует (все помещения плановые).
1. Создать и довести до учитываемого статуса отчёты, доводящие `reportedQuantity` по **каждому**
   плановому помещению до `≥ planQuantity` (≥100%).
2. `GET /api/work-reports/progress?projectId=PROJECT_ID&workerId=WORKER_USER_ID` → `WORK_ITEM_ID`
   **отсутствует** в проекции (все плановые ячейки ≥100%, R14.4).
3. Контроль: work item, у которого хотя бы одно плановое помещение < 100% ИЛИ есть беcплановое
   (absolute-only) помещение, **остаётся** в проекции.

_Покрывает:_ R14.4.

### TC-API-PROGRESS-04 — Own-records guard: WORKER не может запросить чужой `workerId`
**Предусловия:** TOKEN_WORKER, WORKER2_USER_ID (другой воркер).
1. `GET /api/work-reports/progress?projectId=PROJECT_ID&workerId=WORKER2_USER_ID` (Bearer WORKER) →
   **403/404**; код `error.workReport.access.notOwner`.
2. `GET …&workerId=WORKER_USER_ID` (свой) → **200**.
3. Тот же запрос по чужому `workerId` под TOKEN_FOREMAN → **200** (бригадир может по любому воркеру
   проекта, R14.6); под TOKEN_CLIENT (нет READ-гранта) → **403**.

_Покрывает:_ R14.6, R10.5.

---

## Фича A1-2. Пакетное создание — all-or-nothing + успех (R16.1, R16.2, R16.3, R16.4, R16.5)

**Повторяемость:** успешно созданные пакетом отчёты помечены `batch {run-id}`, чистятся teardown;
откатившиеся пакеты ничего не создают — чистки не требуют.

### TC-API-BATCH-01 — Успешный пакет создаёт все отчёты, группировка по `(workItem, from, to)`
**Предусловия:** TOKEN_WORKER; `WORK_ITEM_ID` покрыт; планы по `ROOM_A`/`ROOM_B`.
1. `POST /api/work-reports/batch` (Bearer WORKER) с телом
   `{reports:[ {workItemId:WORK_ITEM_ID, workerId:WORKER_USER_ID, performedFrom:D, performedTo:D,
   rooms:[{roomId:ROOM_A_ID, quantity:Q_A, inputMode:ABSOLUTE}], note:"batch {run-id}"},
   {workItemId:WORK_ITEM_ID, workerId:WORKER_USER_ID, performedFrom:D, performedTo:D,
   rooms:[{roomId:ROOM_B_ID, quantity:Q_B, inputMode:ABSOLUTE}], note:"batch {run-id}"} ]}`. →
   **200/201**; тело `WorkReportBatchResponse { created:[id,…] }` с ожидаемым числом созданных
   отчётов, каждый `status = SUBMITTED`.
2. `GET /api/work-reports?projectId=PROJECT_ID` (Bearer WORKER) → все созданные отчёты видимы; у
   каждого верные деривации (`totalQuantity`, `revenueValue`) как в одиночном создании.
3. _(Клиентская группировка R16.2)_: при подаче нескольких ячеек одного `(workItemId, from, to)`
   фронт объединяет их в один payload с несколькими room-строками — проверяется в части A1-UI
   (TC-UI-BATCH-01). На уровне API принимается тот список payloads, что прислан.

_Покрывает:_ R16.1, R16.5.

### TC-API-BATCH-02 — Один плохой элемент откатывает весь пакет (all-or-nothing)
**Предусловия:** TOKEN_WORKER; валидный `WORK_ITEM_ID`; известный источник ошибки (например,
`ROOM_NOPLAN_ID` в режиме `PERCENT` → `error.workReport.room.noPlan`, либо `quantity:0` →
`quantity.invalid`).
1. Запомнить текущее число отчётов воркера: `GET /api/work-reports?projectId=PROJECT_ID` → `N0`.
2. `POST /api/work-reports/batch` (Bearer WORKER) с телом из трёх элементов: `[0]` валиден, `[1]`
   валиден, `[2]` невалиден (`{roomId:ROOM_NOPLAN_ID, quantity:30, inputMode:PERCENT}`). →
   **400/422**; тело содержит `{index:2, workItemId:…, roomId:ROOM_NOPLAN_ID?, message}` с кодом
   `error.workReport.room.noPlan` (первый сбойный элемент, R16.4).
3. `GET /api/work-reports?projectId=PROJECT_ID` → число отчётов снова `N0` (валидные `[0]`,`[1]`
   **не** сохранились — весь пакет откатился, R16.4).

_Покрывает:_ R16.4.

### TC-API-BATCH-03 — Пакет fast-path бригадира: все элементы сразу APPROVED, без submit-нотификаций
**Предусловия:** TOKEN_FOREMAN; FOREMAN покрыт по `WORK_ITEM_ID`.
1. `POST /api/work-reports/batch` (Bearer FOREMAN) с 2 элементами `workerId=FOREMAN_USER_ID`
   (самоотчёты). → **200/201**; каждый созданный отчёт `status = APPROVED` сразу (R5.1).
2. Прочитать Bell FOREMAN и автора → по этим отчётам **нет** ни submit-, ни approve-нотификаций
   (fast-path эмитит ничего, R16.5 + R5).

_Покрывает:_ R16.5, R5.1.

### TC-API-BATCH-04 — Пакет не-бригадира эмитит одну `submitted` на каждый созданный отчёт
**Предусловия:** TOKEN_WORKER (не бригадир); FOREMAN — ACTIVE member.
1. `POST /api/work-reports/batch` (Bearer WORKER) с 2 валидными элементами. → **200/201**; оба
   `SUBMITTED`; `created` содержит 2 id.
2. Прочитать Bell FOREMAN → присутствуют **две** записи `notification.workReport.submitted` (по
   одной на созданный отчёт, R16.5 / R9.1), у каждой deep-link
   `/projects/{PROJECT_ID}/workReporting?report={id}`.

_Покрывает:_ R16.5.

### TC-API-BATCH-05 — Пакетное создание гейтировано `WORK_REPORTS`/CREATE и own-records
**Предусловия:** TOKEN_MANAGER (нет CREATE), TOKEN_WORKER, WORKER2_USER_ID.
1. `POST /api/work-reports/batch` (Bearer MANAGER) → **403** (нет CREATE-гранта, R16.6).
2. `POST /api/work-reports/batch` (Bearer WORKER) с элементом `workerId=WORKER2_USER_ID` (чужой
   воркер) → **400/403**; код `error.workReport.worker.notSelf`; ничего не создано (own-records).
3. `POST /api/work-reports/batch` без `Authorization` → **401**.

_Покрывает:_ R16.6, R10.5.

---

# Часть A1-UI. Матричная поверхность (frontend)

Экран: вкладка `workReporting` воркспейса `ACTIVE`-проекта → действие «новый отчёт» открывает
**матрицу** `WorkReportMatrix` (не старый `WorkReportBuilder`). Компоненты: матрица (строки —
work items, сгруппированные по категориям; столбцы — помещения; в ячейке план + reported%),
попап ячейки `WorkReportCellPopup` (percent|absolute-переключатель, единица, период from/to с
day −/+ степперами, блок no-plan), боковая панель черновика `WorkReportDraftPanel` (накопленные
строки `work · room · volume`, счётчик, edit/remove, Save; на мобиле — схлопываемый ящик).

**Повторяемость:** UI создаёт отчёты пакетом с заметкой `batch {run-id}`; teardown — те же DELETE
под ADMIN, что и в API. Черновик (localStorage) очищается «discard»/успешным сохранением внутри
сценариев.

### TC-UI-MATRIX-01 — «Новый отчёт» открывает матрицу; выбор воркера (не-WORKER) грузит проекцию
**Предусловия:** логин FOREMAN; проект `ACTIVE`; у `WORKER_USER_ID` есть покрывающая категория.
1. Открыть вкладку `workReporting` → «новый отчёт» → открывается матрица (`matrix.title`), **не**
   одиночный билдер (R17.4).
2. Для FOREMAN отображается пикер воркера (`matrix.pickWorker`); выбрать `Worker {run-id}` → матрица
   (пере)загружается из `GET /api/work-reports/progress` для выбранного воркера (R17.1).
3. Матрица рендерит группы категорий → строки work items (с единицей) × столбцы помещений; в каждой
   ячейке — план (`matrix.cell.plan`) и reported% (`matrix.cell.percent`). Пустая проекция → пустое
   состояние (`matrix.empty`).

_Покрывает:_ R17.1, R17.4, R14.5.

### TC-UI-MATRIX-02 — WORKER видит матрицу по себе без пикера; ячейка ≥100% заблокирована
**Предусловия:** логин WORKER; есть ячейка `(WORK_ITEM_ID, ROOM_A)` с reported% < 100 и, при
возможности, ячейка со 100%.
1. Открыть матрицу → пикера воркера **нет** (грузится self, R17.1); отображается прогресс воркера.
2. Ячейка с reported% ≥ 100 визуально **disabled** — клик не открывает попап (R17.2); ячейка < 100%
   кликается.

_Покрывает:_ R17.1, R17.2.

### TC-UI-CELL-01 — Попап ячейки: percent|absolute, единица, период с day-степперами, no-plan
**Предусловия:** логин WORKER; матрица открыта; `ROOM_A` имеет план, `ROOM_NOPLAN` — нет.
1. Клик по ячейке `(WORK_ITEM_ID, ROOM_A)` → попап (`cell.title`) с переключателем
   percent|absolute (`cell.modeToggle.*`), меткой единицы (`cell.unit`, из `unitCode`), полями
   периода `cell.periodFrom`/`cell.periodTo` (по умолчанию сегодня/сегодня) и степперами
   `cell.dayPrev`/`cell.dayNext` (−/+ один день).
2. Переключить на `PERCENT`, ввести `50` → подтвердить (`cell.confirm`) → ячейка показывает
   staged-бейдж (`matrix.cell.staged`) с резолвом `PLAN_A × 50 / 100` (R15.6).
3. Клик по ячейке `(WORK_ITEM_ID, ROOM_NOPLAN)` → в режиме `PERCENT` отображается блок no-plan
   (`cell.noPlan`), подтверждение percent заблокировано (R15.3); режим `ABSOLUTE` разрешён.

_Покрывает:_ R15.1, R15.3, R15.6.

### TC-UI-DRAFT-A1-01 — Накопление черновика: строки, счётчик, edit/remove, повтор ячейки заменяет строку
**Предусловия:** логин WORKER; матрица открыта.
1. Внести ячейки `(WORK_ITEM_ID, ROOM_A)` и `(WORK_ITEM_ID, ROOM_B)` → в панели черновика
   (`draft.title`) появляются две строки `work · room · volume` (`draft.line`), счётчик
   (`draft.count`) = 2.
2. Повторно открыть ячейку `(WORK_ITEM_ID, ROOM_A)`, изменить количество, подтвердить → в черновике
   по-прежнему **две** строки (строка этой ячейки заменена, не добавлена — одна staged-строка на
   ячейку, R15.4); значение обновилось.
3. На строке черновика «edit» (`draft.edit`) → переоткрывает соответствующую ячейку; «remove»
   (`draft.remove`) → строка удаляется, счётчик уменьшается (R17.3).

_Покрывает:_ R15.4, R17.3.

### TC-UI-DRAFT-A1-02 — Черновик переживает навигацию (continue/discard) и чистится после сохранения
**Предусловия:** логин WORKER; в черновике ≥1 staged-строка (не сохранён).
1. Уйти с вкладки и вернуться → предложение восстановить черновик (continue/discard, R7/R15.5);
   «continue» → staged-строки восстановлены, ячейки снова показывают staged-бейджи.
2. После успешного bulk-сохранения (см. TC-UI-BATCH-01) черновик **пуст**, ключ
   `workReport:draft:{projectId}:{userId}` очищен (R15.5).

_Покрывает:_ R15.1, R15.5.

### TC-UI-BATCH-01 — Bulk save: группировка в payloads, прогресс обновляется, тост успеха
**Предусловия:** логин WORKER; в черновике staged-строки по `ROOM_A` и `ROOM_B` одного
`WORK_ITEM_ID` с одинаковым периодом (`from=to=сегодня`), плюс строка по другому периоду.
1. Нажать Save (`draft.save`) → клиент группирует строки в payloads по `(workItemId, from, to)`
   (две ячейки одного периода → один payload с двумя room-строками; другой период → отдельный
   payload) и шлёт один `POST /api/work-reports/batch` (R16.2).
2. Успех → тост `toast.batchSaved`; черновик очищается; матрица рефетчит `GET …/progress` —
   `reportedQuantity`/reported% по затронутым ячейкам возрастают, staged-бейджи исчезают (R16.5).
3. Созданные отчёты видны в списке панели со статусом `SUBMITTED` и заметкой `batch {run-id}`.

_Покрывает:_ R16.1, R16.2, R15.1.

### TC-UI-BATCH-02 — Ошибка пакета показывается inline, прогресс не меняется
**Предусловия:** логин WORKER; в черновик добавлена заведомо сбойная ячейка (например,
`ROOM_NOPLAN` в режиме PERCENT, если клиент пропускает её до сервера).
1. Save → бэкенд отвечает **400** с указанием сбойного элемента; UI показывает локализованное
   сообщение (`toast.batchError` + inline на соответствующей ячейке/строке черновика, R16.4).
2. Матрица **не** обновляет прогресс (пакет откатился целиком); черновик сохранён — автор правит
   сбойную ячейку и повторяет Save.

_Покрывает:_ R16.4.

### TC-UI-MOBILE-01 — Мобильный схлопываемый черновик с счётчиком на reveal-контроле
**Предусловия:** логин WORKER; мобильный вьюпорт (эмуляция узкого экрана, например 390×844); в
черновике ≥2 staged-строки.
1. На мобиле панель черновика **скрыта** по умолчанию; у правого края виден reveal-контрол
   (`draft.reveal`) со счётчиком staged-строк (`draft.count`), не перекрывающий матрицу (R18.2/18.3).
2. Тап по reveal → выезжает ящик со staged-строками и кнопкой Save (`draft.collapse` сворачивает
   обратно); матрица остаётся прокручиваемой по горизонтали (R18.1).
3. Свернуть → матрица снова не перекрыта; счётчик на reveal-контроле актуален.

_Покрывает:_ R18.2, R18.3, R18.1.

---

# Регрессия (Amendment A1)

**Повторяемость:** регрессионные кейсы читают неизменяемый сид и системные флоу; созданные сущности
чистятся общим teardown.

### REG-A1-01 — Старый одиночный билдер больше не является точкой входа
1. Действие «новый отчёт» на вкладке `workReporting` открывает матрицу `WorkReportMatrix`, а **не**
   `WorkReportBuilder`; старый per-report диалог как точка входа отсутствует (R17.4).

_Покрывает:_ R17.4.

### REG-A1-02 — Ревью-цикл и список не затронуты амендментом
1. Созданные пакетом `SUBMITTED`-отчёты проходят обычный ревью-цикл (approve / reject /
   request-changes) из части A/части B без изменений; список и статус-бейджи работают как прежде.

_Покрывает:_ R4.x, R11.3 (неизменность ревью при переходе на матрицу).

### REG-A1-03 — Нет новых сущностей / ресурсов / типов нотификаций
1. Пакетный флоу не вводит новых таблиц, ресурса ABAC или типа нотификации: `batch` эмитит те же
   четыре `notification.workReport.*`; приложение стартует без ошибки `PermissionAnnotationValidator`
   с новыми эндпоинтами `/progress` и `/batch` (гейты READ и CREATE).

_Покрывает:_ R16.6 (гейтирование), R9.6.

### REG-A1-04 — Локаль-паритет PL/RU для новых ключей матрицы
1. `pl.json` и `ru.json` содержат идентичный набор новых ключей `workReporting.matrix.*`,
   `workReporting.cell.*`, `workReporting.draft.*`, `workReporting.toast.batch*` с реальными PL и RU
   значениями; locale-parity тест зелёный (R19.1, R19.2).

_Покрывает:_ R19.1, R19.2.

---

# Шаблон MD-репорта прогона (Amendment A1)

Заполняется по итогам прогона матричного флоу (отдельные таблицы для части A1-API и части A1-UI).

## Репорт прогона — Часть A1-API (прогресс + batch)

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-API-PROGRESS-01 | 1 | GET /api/work-reports/progress | 200 + rooms[]/categories[]/cells[] | | |
| 2 | TC-API-PROGRESS-02 | 2 | GET …/progress после 4 отчётов | reportedQuantity=q1+q2+q3 (REJECTED искл.) | | |
| 3 | TC-API-PROGRESS-03 | 2 | GET …/progress при всех ≥100% | work item отсутствует | | |
| 4 | TC-API-PROGRESS-04 | 1 | GET …/progress чужой workerId (WORKER) | 403/404 access.notOwner | | |
| 5 | TC-API-BATCH-01 | 1 | POST /api/work-reports/batch (валидный) | 200/201 + created[] | | |
| 6 | TC-API-BATCH-02 | 2 | POST /batch c 1 плохим элементом | 400 {index:2,…} room.noPlan | | |
| 7 | TC-API-BATCH-02 | 3 | GET список после отката | число отчётов = N0 (ничего не создано) | | |
| 8 | TC-API-BATCH-03 | 2 | Bell FOREMAN после batch (self) | нет нотификаций (fast path) | | |
| 9 | TC-API-BATCH-04 | 2 | Bell FOREMAN после batch (2 non-FOREMAN) | 2× .submitted + deep-links | | |
| 10 | TC-API-BATCH-05 | 1 | POST /batch as MANAGER | 403 (нет CREATE) | | |

## Репорт прогона — Часть A1-UI (матрица → черновик → bulk save)

| # | Тест-кейс | Шаг | Действие в браузере | Ожидание | Факт | Статус |
|---|-----------|-----|---------------------|----------|------|--------|
| 1 | TC-UI-MATRIX-01 | 1 | «новый отчёт» | открывается матрица (не билдер) | | |
| 2 | TC-UI-MATRIX-01 | 2 | выбрать воркера (FOREMAN) | матрица грузится из /progress | | |
| 3 | TC-UI-MATRIX-02 | 2 | ячейка ≥100% | disabled, клик не открывает попап | | |
| 4 | TC-UI-CELL-01 | 2 | PERCENT 50 → confirm | staged-бейдж, резолв PLAN×0.5 | | |
| 5 | TC-UI-CELL-01 | 3 | PERCENT на ROOM_NOPLAN | блок no-plan, confirm заблокирован | | |
| 6 | TC-UI-DRAFT-A1-01 | 2 | повтор ячейки | строка заменена (не добавлена) | | |
| 7 | TC-UI-DRAFT-A1-02 | 1 | вернуться на вкладку | continue/discard, строки восстановлены | | |
| 8 | TC-UI-BATCH-01 | 1 | Save | группировка по (workItem,from,to), один POST /batch | | |
| 9 | TC-UI-BATCH-01 | 2 | после успеха | тост batchSaved, прогресс вырос, черновик пуст | | |
| 10 | TC-UI-BATCH-02 | 1 | Save со сбойной ячейкой | 400, inline-ошибка, прогресс не изменился | | |
| 11 | TC-UI-MOBILE-01 | 1 | мобильный вьюпорт | черновик скрыт, reveal справа + счётчик | | |
| 12 | TC-UI-MOBILE-01 | 2 | тап reveal | выезжает ящик со строками + Save | | |

**Итог:** X пройдено / Y провалено / Z пропущено.
Run-id: `<timestamp/uuid>`. Стратегия повторяемости: `генератор run-id (note="batch {run-id}") + teardown (DELETE под ADMIN)`.
