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
