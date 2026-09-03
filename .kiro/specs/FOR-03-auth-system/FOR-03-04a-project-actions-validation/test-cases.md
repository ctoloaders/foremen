# Тест-кейсы: FOR-03-04a Project Actions Validation

## Назначение и характер спеки

Спека **чисто бекендовая** (нет браузерных экранов). Поэтому тест-кейсы — это **API-тесты против работающего в Docker приложения** (не Testcontainers/юнит-слой). Каждый шаг — HTTP-запрос к API с проверкой статуса, тела и заголовков. Результат прогона — **MD-репорт с таблицами** (шаг → запрос → ожидание → факт → статус). Скриншоты не требуются.

Спека добавляет **уровень 3 авторизации** — проверку владения проектом на уровне единичного действия (read-by-id, update-by-id, delete-by-id и их batch/soft-варианты) для project-scoped сущностей. Механизм живёт на контракте `ProjectScopedService` (`extends AdminService`): перед делегированием в унаследованное CRUD-поведение вызывается `assertProjectAccess(id)` с правилами ADMIN-bypass, empty-access-denies и membership-test, а отказ отдаётся как `404 error.entity.not.found` (out-of-scope сущность неотличима от несуществующей).

## ⚠️ Важное ограничение runnable-поверхности (обязательно к прочтению)

На текущем этапе **сквозной API-прогон уровня 3 ограничен**:

- **Нет реальной project-scoped сущности/эндпоинта.** `projects` таблица, реальные project-scoped сущности (Room, EstimateItem, MaterialPurchase, WorkOrder и т. п.) и их сервисы/контроллеры приходят в **FOR-06**. В этой спеке механизм проверяется против **test-only** фикстуры (`com.foremen.scoping.ScopedFixtureService` / `ScopedFixtureEntity`) через `@SpringBootTest` + Testcontainers — вне docker-стека HTTP-поверхности для неё нет.
- **Продовые контроллеры ещё не аннотированы `@RequiresPermission`.** Миграция контроллеров с `permitAll()` на ABAC — это **FOR-03-08**. Значит, даже когда реальные project-scoped сущности появятся, до FOR-03-08 у них не будет проходить уровень 2 (resource+operation) единообразно.

**Следствие для этого файла:**

1. Часть кейсов помечена **`PENDING FOR-06`** (нет реальной project-scoped сущности с REST) и/или **`PENDING FOR-03-08`** (продовый контроллер не аннотирован). Для них приведён **точный ожидаемый запрос/ответ**, чтобы при появлении эндпоинта их можно было исполнить без переписывания.
- Плейсхолдер условного project-scoped эндпоинта в кейсах — `/api/rooms` (`GET|PUT|DELETE /api/rooms/{id}`, batch `PUT|DELETE /api/rooms`), где `Room` — представитель project-scoped сущности из FOR-06 с `getProjectIdPath()` = `project.id`. При автоматизации подставить фактический путь/сущность из FOR-06.
2. Ближайшая **реально исполнимая** в docker-стеке поверхность — эндпоинты управления членством из FOR-03-04: `POST /api/project-members` (assign), `DELETE /api/project-members/{id}` (remove), `GET /api/project-members?projectId=...` / `GET /api/project-members/users/{userId}/projects` (list). Они дают детерминированный способ **выдавать/отзывать** доступ пользователя к проекту (наполнять `project_members` → `ProjectAccessCache`) и проверять уровень 2 (403 при отсутствии `PROJECT_MEMBERS` прав) — то есть driving-инфраструктуру для будущих level-3 кейсов. Сам level-3 (владение конкретной сущностью) через `/api/project-members` проверить нельзя, потому что membership-строки не являются project-scoped сущностью с `getProjectIdPath()`.

Итог: **исполнимо сейчас** — driving-инфраструктура доступа (Фича 8) и негативные проверки контракта (401/403 на project-members). **PENDING** — собственно level-3 enforcement (Фичи 2–7) до появления REST project-scoped сущности.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`.
3. Auth-эндпоинты — под `/api/auth`.
4. Первый ADMIN создаётся через переменные окружения бекенда: `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
5. Дождаться готовности `backend` (health/readiness) перед запуском кейсов.

### Предусловие-хелпер: активный пользователь с ролью R

Для входа под конкретной ролью нужен ACTIVE-пользователь. Стандартный флоу: ADMIN создаёт пользователя (`POST /api/users`, статус INVITED) → получение invite-токена (через тестовый хелпер БД или перехват почты/mailhog) → `POST /api/auth/set-password` (пользователь становится ACTIVE и сразу получает JWT). Если в окружении настроен только логин-флоу — использовать заранее засиженного ACTIVE-пользователя нужной роли. В шагах ниже это предусловие обозначено как «активный пользователь роли R».

### Приём выдачи/отзыва доступа к проекту (driving-инфраструктура)

Логинимся как ADMIN → `POST /api/project-members` с телом `{ "userId": <id>, "projectId": <pid>, "projectRoleId": <rid> }` (наполняет `project_members`, инвалидирует `ProjectAccessCache` пользователя) → пользователь получает доступ к проекту `<pid>`. Отзыв: `DELETE /api/project-members/{membershipId}` (снова инвалидирует кеш). Именно так формируется `Allowed_Project_Ids` для сценариев уровня 3.

## Стратегия повторяемости

**Комбинированная — генератор + teardown:**

- **Генератор:** на каждый прогон формируется `run-id` (timestamp/uuid). Создаваемые сущности получают уникальные идентификаторы:
  - пользователи: `email = pav+{run-id}+{seq}@example.com`;
  - project-id для членства: `projectId = {run-id-числовой-суффикс}{seq}` (синтетический BIGINT, не требует таблицы `projects` — по дизайну FOR-03-04 `project_id` не имеет FK);
  - несистемные роли (при необходимости): `code = TESTROLE_{run-id}_{seq}`.
- **Teardown (обязательный шаг в конце каждого набора):**
  - удалить созданные членства: `DELETE /api/project-members/{id}` для каждого созданного membership (инвалидирует кеш);
  - для каждого созданного пользователя: `POST /api/auth/logout` (отзыв refresh-токена) и деактивация/удаление через `/api/users`;
  - удалить созданные несистемные роли: `DELETE /api/roles/{id}`.
- Системные роли (ADMIN, MANAGER, …) и seed-данные **не изменяются**. Синтетические `projectId` уникальны на прогон, поэтому наборы запускаются многократно без ручной чистки БД.

> Для **PENDING**-кейсов (level-3 против реальной сущности из FOR-06) к стратегии добавится teardown созданных project-scoped сущностей (`DELETE /api/rooms/{id}` под ADMIN) — при их появлении.

---

## Фича 1. Owning-project resolution contract (`getProjectId`)

_Requirements: 1 (контекст), проверяется поведенчески через enforcement из Фич 3–5._

`getProjectId(id)` — дефолтный резолвер, выводящий owning-project-id из `getProjectIdPath()` (single-segment `projectId` и dotted `project.id`), возвращающий `null` для несуществующего id. Напрямую через публичный HTTP API не наблюдается: проверяется **опосредованно** — через результат `assertProjectAccess` (in-scope → 200, out-of-scope/несуществующий для non-ADMIN → 404). Прямое покрытие резолвера обеспечивают интеграционные тесты задачи 5.4 (Testcontainers), вне docker-API-прогона.

### TC-RESOLVE-01 — Резолвер выводит owning-project-id для single-segment пути `PENDING FOR-06`

**Статус:** `PENDING FOR-06` (нужна реальная project-scoped сущность с `getProjectIdPath() == "projectId"` и REST-эндпоинтом).

**Предусловия:** ADMIN залогинен; создана сущность-представитель с `projectId = P1`.

**Ожидаемый запрос/ответ (для будущего исполнения):**
1. `POST /api/auth/login` (ADMIN) → 200.
2. `GET /api/rooms/{roomId}` (сущность с `projectId=P1`), `Authorization: Bearer <ADMIN>` → **200** (ADMIN bypass, резолвер на этом пути не вызывается).
3. Выдать non-ADMIN пользователю доступ к `P1`: `POST /api/project-members {userId, projectId: P1, projectRoleId}` → 200.
4. `GET /api/rooms/{roomId}` токеном non-ADMIN → **200** — резолвер вернул `P1`, `P1 ∈ Allowed_Project_Ids`.

**Ожидаемый результат:** резолвер по single-segment пути возвращает корректный owning-project-id (косвенно подтверждается допуском in-scope non-ADMIN).

### TC-RESOLVE-02 — Резолвер выводит owning-project-id для dotted/join пути (`project.id`) `PENDING FOR-06`

**Статус:** `PENDING FOR-06` (сущность с `getProjectIdPath() == "project.id"`).

**Ожидаемый запрос/ответ:** аналогично TC-RESOLVE-01, но у сущности проект достигается через join (`project.id`). non-ADMIN с доступом к `project.id == P1` получает `GET /api/rooms/{id}` → **200**.

**Ожидаемый результат:** join-путь резолвится так же корректно, как single-segment.

### TC-RESOLVE-03 — Несуществующий id → резолвер даёт `null` → для non-ADMIN 404 `PENDING FOR-06`

**Статус:** `PENDING FOR-06`.

**Ожидаемый запрос/ответ:**
1. Логин non-ADMIN с непустым `Allowed_Project_Ids`.
2. `GET /api/rooms/{несуществующий-id}` токеном non-ADMIN → **404** с кодом `error.entity.not.found` (резолвер вернул `null`, что для non-ADMIN трактуется как deny).

**Ожидаемый результат:** отсутствие сущности и out-of-scope сущность неотличимы для non-ADMIN (оба 404 `error.entity.not.found`).

---

## Фича 2. Action decision (`assertProjectAccess`) — матрица решений

_Requirements: 2._ Единая точка решения: ADMIN-bypass, empty-access-denies, membership-test, deny = 404 `error.entity.not.found`. Все кейсы `PENDING FOR-06` (нужен единичный project-scoped эндпоинт), кроме прямого 401, который проверяется на любом защищённом эндпоинте.

### TC-DEC-01 — ADMIN-bypass: ADMIN допускается независимо от владения `PENDING FOR-06`

**Статус:** `PENDING FOR-06`. **Требования:** 2.2.

**Ожидаемый запрос/ответ:**
1. Логин ADMIN → 200.
2. `GET /api/rooms/{roomId}` (сущность в проекте, к которому ADMIN не «привязан» членством) токеном ADMIN → **200**. Резолвер `getProjectId` НЕ вызывается (bypass до резолюции).

**Ожидаемый результат:** ADMIN проходит без проверки владения.

### TC-DEC-02 — non-ADMIN in-scope: сущность в разрешённом проекте → allow `PENDING FOR-06`

**Статус:** `PENDING FOR-06`. **Требования:** 2.6.

**Ожидаемый запрос/ответ:**
1. Логин ADMIN → выдать non-ADMIN доступ к `P1`: `POST /api/project-members {userId, projectId: P1, projectRoleId}` → 200.
2. Логин non-ADMIN → 200.
3. `GET /api/rooms/{roomId}` (сущность с owning `P1`) токеном non-ADMIN → **200**.

**Ожидаемый результат:** owning-project-id ∈ Allowed_Project_Ids → доступ разрешён.

### TC-DEC-03 — non-ADMIN out-of-scope: сущность в чужом проекте → 404 `PENDING FOR-06`

**Статус:** `PENDING FOR-06`. **Требования:** 2.7.

**Ожидаемый запрос/ответ:**
1. Логин ADMIN → выдать non-ADMIN доступ к `P1` (но НЕ к `P2`).
2. Логин non-ADMIN → 200.
3. `GET /api/rooms/{roomId}` (сущность с owning `P2`) токеном non-ADMIN → **404** код `error.entity.not.found`.

**Ожидаемый результат:** owning-project-id ∉ Allowed_Project_Ids → 404 (неотличимо от несуществующей).

### TC-DEC-04 — non-ADMIN с пустым доступом: empty-access-denies → 404 `PENDING FOR-06`

**Статус:** `PENDING FOR-06`. **Требования:** 2.5.

**Ожидаемый запрос/ответ:**
1. Non-ADMIN пользователь БЕЗ единого `project_members` (Allowed_Project_Ids пуст).
2. Логин non-ADMIN → 200.
3. `GET /api/rooms/{любой roomId}` токеном non-ADMIN → **404** код `error.entity.not.found`.

**Ожидаемый результат:** пустой allowed-set запрещает любое единичное действие.

### TC-DEC-05 — Неаутентифицированный вызов → отказ

**Статус:** частично исполнимо сейчас (на любом защищённом эндпоинте), полный level-3 контекст — `PENDING FOR-06/FOR-03-08`. **Требования:** 2.3.

**Ожидаемый запрос/ответ:**
1. `GET /api/rooms/{roomId}` **без** заголовка `Authorization`.
   - При аннотированном эндпоинте (FOR-03-08): интерцептор уровня 2 отдаёт **401** `error.auth.unauthorized` (запрос не доходит до `assertProjectAccess`).
   - Если запрос доходит до сервисного слоя без аутентификации, `assertProjectAccess` отдаёт **404** `error.entity.not.found` (Access_Denied_Outcome).

**Ожидаемый результат:** неаутентифицированный вызов отклонён (401 на уровне 2 или 404 на уровне 3). Проверить на реально доступном защищённом эндпоинте, что 401 работает (см. TC-INFRA-04).

### TC-DEC-06 — non-numeric principal → deny (404) `PENDING FOR-06`

**Статус:** `PENDING FOR-06`. **Требования:** 2.4.

**Примечание:** в штатном JWT-флоу principal-name всегда numeric (`sub`=userId). Кейс на нештатный/повреждённый принципал — воспроизводится только на уровне сервиса/интеграции. Ожидание при доходе до `assertProjectAccess`: **404** `error.entity.not.found`.

---

## Фича 3. Read-by-id enforcement (`findById`)

_Requirements: 3._ Все кейсы `PENDING FOR-06`: нужен `GET /api/rooms/{id}` реальной project-scoped сущности.

### TC-READ-01 — non-ADMIN читает in-scope сущность → 200 `PENDING FOR-06`

**Требования:** 3.2. Как TC-DEC-02, метод — read-by-id. `GET /api/rooms/{roomId}` (owning ∈ allowed) → **200**, тело — сущность как у унаследованного `findById`.

### TC-READ-02 — non-ADMIN читает out-of-scope сущность → 404 `PENDING FOR-06`

**Требования:** 3.3. `GET /api/rooms/{roomId}` (owning ∉ allowed) → **404** `error.entity.not.found`; делегирование в унаследованный `findById` НЕ происходит.

### TC-READ-03 — ADMIN читает любую сущность → 200 `PENDING FOR-06`

**Требования:** 3.4. `GET /api/rooms/{roomId}` токеном ADMIN → **200** без проверки владения.

### TC-READ-04 — ADMIN читает несуществующий id → 404 (framework) `PENDING FOR-06`

**Требования:** 2.2 (bypass не маскирует framework-404). `GET /api/rooms/{несуществующий}` токеном ADMIN → **404** `error.entity.not.found`, отдаётся штатным `findById` (резолвер `getProjectId` на ADMIN-пути не дёргается).

---

## Фича 4. Update-by-id enforcement + отсутствие частичной мутации

_Requirements: 4._ Все кейсы `PENDING FOR-06`: нужен `PUT /api/rooms/{id}`.

### TC-UPD-01 — non-ADMIN обновляет in-scope сущность → 200 `PENDING FOR-06`

**Требования:** 4.3. `PUT /api/rooms/{roomId}` (owning ∈ allowed) с валидным телом → **200**; апдейт выполнен как у унаследованного `update` (с валидацией и аудитом).

### TC-UPD-02 — non-ADMIN обновляет out-of-scope сущность → 404, строка не изменена `PENDING FOR-06`

**Требования:** 4.2, 8.3.

**Ожидаемый запрос/ответ:**
1. Non-ADMIN с доступом к `P1`, целевая сущность в `P2`.
2. `PUT /api/rooms/{roomId}` (owning `P2`) токеном non-ADMIN → **404** `error.entity.not.found`.
3. Логин ADMIN → `GET /api/rooms/{roomId}` → **200**; поля сущности **не изменились** (мутация не дошла до DAO, `assertProjectAccess` бросил раньше).

**Ожидаемый результат:** отказ + отсутствие частичной мутации.

### TC-UPD-03 — ADMIN обновляет любую сущность → 200 `PENDING FOR-06`

**Требования:** 4.4. `PUT /api/rooms/{roomId}` токеном ADMIN → **200** без проверки владения.

### TC-UPD-04 — Multi-id / single-field update: abort-on-first-deny `PENDING FOR-06`

**Требования:** 4.5, 8.3. См. Фичу 7 (multi-id abort) — тот же принцип для `updateAll`/`updateSingleField`.

---

## Фича 5. Delete-by-id enforcement + отсутствие частичной мутации

_Requirements: 5._ Все кейсы `PENDING FOR-06`: нужен `DELETE /api/rooms/{id}`.

### TC-DEL-01 — non-ADMIN удаляет in-scope сущность → 200/204 `PENDING FOR-06`

**Требования:** 5.3. `DELETE /api/rooms/{roomId}` (owning ∈ allowed) токеном non-ADMIN → **успех**; удаление как у унаследованного `deleteById` (с аудитом).

### TC-DEL-02 — non-ADMIN удаляет out-of-scope сущность → 404, строка на месте `PENDING FOR-06`

**Требования:** 5.2, 8.3.

**Ожидаемый запрос/ответ:**
1. Non-ADMIN с доступом к `P1`, целевая сущность в `P2`.
2. `DELETE /api/rooms/{roomId}` (owning `P2`) токеном non-ADMIN → **404** `error.entity.not.found`.
3. Логин ADMIN → `GET /api/rooms/{roomId}` → **200**; строка **присутствует** (удаление не дошло до DAO).

**Ожидаемый результат:** отказ + строка не удалена.

### TC-DEL-03 — ADMIN удаляет любую сущность → 200/204 `PENDING FOR-06`

**Требования:** 5.4. `DELETE /api/rooms/{roomId}` токеном ADMIN → **успех** без проверки владения.

### TC-DEL-04 — soft-delete / setPropertiesToNull: те же правила `PENDING FOR-06`

**Требования:** 5.5. Для `softDelete`/`setPropertiesToNull` (batch по множеству id) — abort-on-first-deny, см. Фичу 7.

---

## Фича 6. Локализация Access_Denied_Outcome (PL/RU)

_Requirements: 6._ Отказ уровня 3 переиспользует существующий код `error.entity.not.found` (уже локализован в `messages.properties` PL и `messages_ru.properties` RU). Новый код не вводится.

### TC-I18N-01 — 404 сообщение отличается для pl и ru `PENDING FOR-06`

**Статус:** `PENDING FOR-06`. **Требования:** 6.1, 6.3.

**Ожидаемый запрос/ответ:**
1. non-ADMIN out-of-scope: `GET /api/rooms/{roomId}` (owning ∉ allowed), `Accept-Language: pl` → **404**; запомнить `message`.
2. Тот же запрос с `Accept-Language: ru` → **404**; запомнить `message`.
3. Сравнить → **ожидание:** оба непустые и различаются (PL vs RU), код в обоих `error.entity.not.found`.

**Ожидаемый результат:** денай уровня 3 локализован в обоих языках.

> Косвенно код `error.entity.not.found` и его локализацию можно проверить **сейчас** на любом эндпоинте, отдающем 404 на несуществующий id (например `GET /api/project-members/{несуществующий}` под ADMIN, если эндпоинт отдаёт 404) — см. TC-INFRA-05.

---

## Фича 7. Multi-id abort-on-first-deny

_Requirements: 4.5, 5.5._ Batch-варианты (`updateAll`, `deleteAll`, `softDelete`, `setPropertiesToNull`) валидируют КАЖДЫЙ id и прерывают всю операцию при первом отказе — ни одна строка пакета не мутируется. Все `PENDING FOR-06`.

### TC-MULTI-01 — Batch update с одним out-of-scope id прерывается целиком `PENDING FOR-06`

**Требования:** 4.5, 8.3.

**Ожидаемый запрос/ответ:**
1. Non-ADMIN с доступом к `P1`. Есть сущности `A(P1)`, `B(P1)`, `C(P2)`.
2. `PUT /api/rooms` (batch по ids `[A, B, C]`, одно значение поля) токеном non-ADMIN → **404** `error.entity.not.found` (отказ на `C`).
3. Логин ADMIN → `GET /api/rooms/{A}`, `GET /api/rooms/{B}` → **200**; поля `A` и `B` **не изменились** (весь пакет прерван, не только `C`).

**Ожидаемый результат:** abort-on-first-deny — ни одна строка пакета не мутирована.

### TC-MULTI-02 — Batch delete / softDelete с одним out-of-scope id прерывается целиком `PENDING FOR-06`

**Требования:** 5.5, 8.3.

**Ожидаемый запрос/ответ:**
1. Non-ADMIN с доступом к `P1`. Сущности `A(P1)`, `C(P2)`.
2. `DELETE /api/rooms` (batch ids `[A, C]`) токеном non-ADMIN → **404** `error.entity.not.found`.
3. Логин ADMIN → `GET /api/rooms/{A}` → **200**; `A` **присутствует** (пакет прерван до удаления).

**Ожидаемый результат:** ни одна строка batch-удаления не удалена при первом отказе.

### TC-MULTI-03 — Batch со всеми in-scope id проходит целиком `PENDING FOR-06`

**Требования:** 4.5, 5.5. Non-ADMIN с доступом к `P1`, все сущности batch в `P1` → операция выполняется полностью (**200/успех**).

---

## Фича 8. Read-filter non-regression (FOR-03-04) + driving-инфраструктура доступа

_Requirements: 7._ Проверяет, что list-фильтр FOR-03-04 не изменился, и что эндпоинты `/api/project-members` (реально исполнимые сейчас) корректно формируют `Allowed_Project_Ids` и защищены уровнем 2. Кейсы уровня list-фильтра против реальной project-scoped сущности — `PENDING FOR-06`; membership-инфраструктура — **исполнимо сейчас**.

### TC-INFRA-01 — Выдача членства формирует доступ пользователя к проекту

**Статус:** исполнимо сейчас. **Требования:** 7 (driving), 2.10.

**Предусловия:** ADMIN залогинен; активный non-ADMIN пользователь `U`; синтетический `projectId = P1`.

**Шаги:**
1. `POST /api/auth/login` (ADMIN) → 200.
2. `POST /api/project-members` c телом `{ "userId": U, "projectId": P1, "projectRoleId": <rid> }` → **ожидание: 200/201**, создано членство `M1`.
3. `GET /api/project-members/users/{U}/projects` токеном ADMIN → **200**, список содержит `P1`.

**Ожидаемый результат:** членство создано; `Allowed_Project_Ids(U)` включает `P1` (через `ProjectAccessCache`).

### TC-INFRA-02 — Отзыв членства убирает доступ (инвалидация кеша)

**Статус:** исполнимо сейчас. **Требования:** 7 (driving), 2.10.

**Шаги:**
1. Предусловие TC-INFRA-01 (членство `M1` на `P1`).
2. `DELETE /api/project-members/{M1}` токеном ADMIN → **ожидание: 200/204**.
3. `GET /api/project-members/users/{U}/projects` → **200**, список НЕ содержит `P1`.

**Ожидаемый результат:** после отзыва `Allowed_Project_Ids(U)` больше не содержит `P1` (кеш инвалидирован на мутации).

### TC-INFRA-03 — Уровень 2: без прав PROJECT_MEMBERS → 403

**Статус:** исполнимо сейчас. **Требования:** 7 (контекст уровня 2).

**Предусловия:** активный пользователь роли без прав `PROJECT_MEMBERS` (например кастомная роль без грантов).

**Шаги:**
1. Логин пользователем такой роли → 200.
2. `POST /api/project-members {userId, projectId, projectRoleId}` токеном роли → **ожидание: 403**, код `error.access.denied`.

**Ожидаемый результат:** management-эндпоинты защищены `@RequiresPermission(PROJECT_MEMBERS,...)`; уровень 2 работает независимо от уровня 3.

### TC-INFRA-04 — Уровень 1: без токена → 401

**Статус:** исполнимо сейчас. **Требования:** 2.3 (косвенно, уровень 1).

**Шаги:**
1. `GET /api/project-members?projectId=P1` **без** `Authorization` → **ожидание: 401**, код `error.auth.unauthorized`.

**Ожидаемый результат:** неаутентифицированный доступ к защищённому эндпоинту отклонён на уровне 1/2 (подтверждает поведение, ожидаемое TC-DEC-05).

### TC-INFRA-05 — Локализация 404 `error.entity.not.found` (косвенно)

**Статус:** исполнимо сейчас, если эндпоинт отдаёт 404 на несуществующий id. **Требования:** 6.1, 6.3.

**Шаги:**
1. Логин ADMIN → 200.
2. `DELETE /api/project-members/{несуществующий-id}` с `Accept-Language: pl` → **404**; запомнить `message`.
3. Тот же запрос с `Accept-Language: ru` → **404**; запомнить `message`.
4. Сравнить → **ожидание:** оба непустые и различаются, код `error.entity.not.found` в обоих.

**Ожидаемый результат:** тот самый код, что используется как Access_Denied_Outcome уровня 3, локализован PL+RU (подтверждает основу TC-I18N-01).

### TC-REGR-LIST-01 — List-фильтр не изменён: ADMIN видит всё `PENDING FOR-06`

**Статус:** `PENDING FOR-06`. **Требования:** 7.1, 7.2, 8.4. `GET /api/rooms` токеном ADMIN → **200**, список НЕ отфильтрован по проекту.

### TC-REGR-LIST-02 — List-фильтр не изменён: non-ADMIN видит только in-membership строки `PENDING FOR-06`

**Статус:** `PENDING FOR-06`. **Требования:** 7.1, 7.2, 8.4. non-ADMIN с доступом к `P1`: `GET /api/rooms` → **200**, только сущности с owning `P1`.

### TC-REGR-LIST-03 — List-фильтр не изменён: non-ADMIN без членств видит пусто `PENDING FOR-06`

**Статус:** `PENDING FOR-06`. **Требования:** 7.1, 7.2, 8.4. non-ADMIN без `project_members`: `GET /api/rooms` → **200**, пустой список.

---

## Teardown (выполняется в конце каждого прогона)

1. Для каждого созданного членства: `DELETE /api/project-members/{id}` под ADMIN (инвалидирует кеш).
2. Для каждого созданного пользователя: `POST /api/auth/logout` (отзыв refresh-токена) и деактивация/удаление через `/api/users`.
3. Для каждой созданной тестовой роли: `DELETE /api/roles/{id}` (несистемные роли удаляемы).
4. (Для PENDING FOR-06) Для каждой созданной project-scoped сущности: `DELETE /api/rooms/{id}` под ADMIN.
5. Проверить отсутствие остаточных сущностей/членств с текущим `run-id`.

---

## Регрессия

Перепроверка критичных сквозных путей и ранее закрытых рисков.

### REG-01 — Read-filter FOR-03-04 не сломан этой спекой `PENDING FOR-06`
`find`/`findExtended`/`getCount` через project-scoped эндпоинт: ADMIN — без фильтра; non-ADMIN с членствами — только in-membership; non-ADMIN без членств — пусто.
_Покрывает: TC-REGR-LIST-01..03. **PENDING FOR-06** (нет реальной project-scoped REST-поверхности)._

### REG-02 — Единичные действия и list-чтение читают доступ из одного источника
Выдать членство (`POST /api/project-members`) → у пользователя одновременно появляется доступ к list-чтению и к единичным действиям того же проекта; отзыв (`DELETE /api/project-members/{id}`) синхронно убирает и то, и другое (общий `ProjectAccessCache`).
_Покрывает: TC-INFRA-01, TC-INFRA-02; связь с TC-DEC-02/03. Инфраструктурная часть **исполнима сейчас**, level-3 часть **PENDING FOR-06**._

### REG-03 — ADMIN-bypass не маскирует framework-404 `PENDING FOR-06`
ADMIN на несуществующем id получает штатный 404 `error.entity.not.found` (bypass не превращает «нет сущности» в «нет доступа»).
_Покрывает: TC-READ-04. **PENDING FOR-06**._

### REG-04 — Уровни авторизации независимы
Отсутствие прав уровня 2 (`PROJECT_MEMBERS`) даёт 403 `error.access.denied`; отсутствие токена — 401 `error.auth.unauthorized`; уровень 3 (владение) — 404 `error.entity.not.found`. Коды и статусы не смешиваются.
_Покрывает: TC-INFRA-03, TC-INFRA-04; связь с TC-DEC-05. **Исполнимо сейчас** (management-эндпоинты)._

### REG-05 — Отказ уровня 3 не оставляет частичной мутации `PENDING FOR-06`
Денай update/delete/batch не изменяет и не удаляет целевые строки.
_Покрывает: TC-UPD-02, TC-DEL-02, TC-MULTI-01, TC-MULTI-02. **PENDING FOR-06**._

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-INFRA-01 | 2 | POST /api/project-members (ADMIN) | 200/201 membership | | |
| 2 | TC-INFRA-01 | 3 | GET /api/project-members/users/{U}/projects | 200, содержит P1 | | |
| 3 | TC-INFRA-02 | 2 | DELETE /api/project-members/{M1} | 200/204 | | |
| 4 | TC-INFRA-02 | 3 | GET .../users/{U}/projects | 200, без P1 | | |
| 5 | TC-INFRA-03 | 2 | POST /api/project-members (роль без прав) | 403 error.access.denied | | |
| 6 | TC-INFRA-04 | 1 | GET /api/project-members (без токена) | 401 error.auth.unauthorized | | |
| 7 | TC-INFRA-05 | 2-4 | DELETE .../{нет} pl vs ru | 404, тексты различны | | |
| 8 | TC-DEC-01 | 2 | GET /api/rooms/{id} (ADMIN) | 200 | | PENDING FOR-06 |
| 9 | TC-DEC-02 | 3 | GET /api/rooms/{id} (non-ADMIN in-scope) | 200 | | PENDING FOR-06 |
| 10 | TC-DEC-03 | 3 | GET /api/rooms/{id} (non-ADMIN out-of-scope) | 404 error.entity.not.found | | PENDING FOR-06 |
| 11 | TC-DEC-04 | 3 | GET /api/rooms/{id} (non-ADMIN empty access) | 404 error.entity.not.found | | PENDING FOR-06 |
| 12 | TC-DEC-05 | 1 | GET /api/rooms/{id} (без токена) | 401 / 404 | | PENDING FOR-03-08 |
| 13 | TC-READ-01 | — | GET /api/rooms/{id} (in-scope) | 200 | | PENDING FOR-06 |
| 14 | TC-READ-02 | — | GET /api/rooms/{id} (out-of-scope) | 404 error.entity.not.found | | PENDING FOR-06 |
| 15 | TC-READ-03 | — | GET /api/rooms/{id} (ADMIN) | 200 | | PENDING FOR-06 |
| 16 | TC-READ-04 | — | GET /api/rooms/{нет} (ADMIN) | 404 error.entity.not.found | | PENDING FOR-06 |
| 17 | TC-UPD-01 | — | PUT /api/rooms/{id} (in-scope) | 200 | | PENDING FOR-06 |
| 18 | TC-UPD-02 | 2-3 | PUT /api/rooms/{id} (out-of-scope) + проверка | 404 + строка не изменена | | PENDING FOR-06 |
| 19 | TC-UPD-03 | — | PUT /api/rooms/{id} (ADMIN) | 200 | | PENDING FOR-06 |
| 20 | TC-DEL-01 | — | DELETE /api/rooms/{id} (in-scope) | 200/204 | | PENDING FOR-06 |
| 21 | TC-DEL-02 | 2-3 | DELETE /api/rooms/{id} (out-of-scope) + проверка | 404 + строка на месте | | PENDING FOR-06 |
| 22 | TC-DEL-03 | — | DELETE /api/rooms/{id} (ADMIN) | 200/204 | | PENDING FOR-06 |
| 23 | TC-MULTI-01 | 2-3 | PUT /api/rooms (batch, один out-of-scope) | 404 + пакет не мутирован | | PENDING FOR-06 |
| 24 | TC-MULTI-02 | 2-3 | DELETE /api/rooms (batch, один out-of-scope) | 404 + пакет не удалён | | PENDING FOR-06 |
| 25 | TC-MULTI-03 | — | PUT /api/rooms (batch, все in-scope) | 200/успех | | PENDING FOR-06 |
| 26 | TC-I18N-01 | 1-3 | GET /api/rooms/{id} out-of-scope pl vs ru | 404, тексты различны | | PENDING FOR-06 |
| 27 | TC-RESOLVE-01 | 4 | GET /api/rooms/{id} (single path, in-scope) | 200 | | PENDING FOR-06 |
| 28 | TC-RESOLVE-02 | — | GET /api/rooms/{id} (join path, in-scope) | 200 | | PENDING FOR-06 |
| 29 | TC-RESOLVE-03 | 2 | GET /api/rooms/{нет} (non-ADMIN) | 404 error.entity.not.found | | PENDING FOR-06 |
| 30 | TC-REGR-LIST-01 | — | GET /api/rooms (ADMIN) | 200, без фильтра | | PENDING FOR-06 |
| 31 | TC-REGR-LIST-02 | — | GET /api/rooms (non-ADMIN с членствами) | 200, только in-membership | | PENDING FOR-06 |
| 32 | TC-REGR-LIST-03 | — | GET /api/rooms (non-ADMIN без членств) | 200, пусто | | PENDING FOR-06 |
| 33 | REG-04 | — | 403/401/404 не смешиваются | коды разделены | | |

Итог: X пройдено / Y провалено / Z пропущено / W отложено (PENDING). Run-id: `<timestamp/uuid>`. Стратегия повторяемости: `генератор (pav+{run-id}, синтетические projectId) + teardown (project-members / users / roles)`.

> **Легенда статусов:** ✅ пройдено · ❌ провалено · ⏭️ пропущено · ⏳ PENDING FOR-06 (нет реальной project-scoped REST-сущности) · ⏳ PENDING FOR-03-08 (продовый контроллер не аннотирован `@RequiresPermission`).
