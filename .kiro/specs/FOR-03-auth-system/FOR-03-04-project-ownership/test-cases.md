# Тест-кейсы: FOR-03-04 Project Ownership

## Назначение и характер спеки

Спека **чисто бекендовая** (нет браузерных экранов). Поэтому тест-кейсы — это **API-тесты против работающего в Docker приложения** (не Testcontainers/юнит-слой, а поднятый docker-стек). Каждый шаг — HTTP-запрос к API с проверкой статуса, тела и заголовков. Результат прогона — **MD-репорт с таблицами** (шаг → запрос → ожидание → факт → статус). Скриншоты не требуются.

Спека доставляет:
- таблицу `project_members` с уникальностью `(user_id, project_id)` и её сущность/DAO;
- сервис управления членством (assign/remove/list) с инвалидацией кеша доступа;
- REST-контроллер `/api/project-members`, защищённый `@RequiresPermission(PROJECT_MEMBERS, ...)`;
- ABAC-сид ресурса `PROJECT_MEMBERS` (ADMIN/MANAGER — полный CRUD, FOREMAN/FINANCIER — READ, WORKER/CLIENT — без прав);
- механизм проектной фильтрации чтений (`ProjectScopedService`) с ADMIN-байпасом;
- локализацию новых кодов ошибок PL/RU.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`.
3. Auth-эндпоинты — под `/api/auth`.
4. Первый ADMIN создаётся через переменные окружения бекенда: `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
5. Дождаться готовности `backend` (health/readiness) перед запуском кейсов.

### Эндпоинты, покрываемые набором

- `POST /api/project-members` — assign (тело `{ userId, projectId, projectRoleId }`), требует `(PROJECT_MEMBERS, CREATE)`, успех → 201 `ProjectMemberResponse`.
- `DELETE /api/project-members?userId=&projectId=` — remove, требует `(PROJECT_MEMBERS, DELETE)`, успех → 204 без тела.
- `GET /api/project-members?projectId=` — list-members, требует `(PROJECT_MEMBERS, READ)`, успех → 200 список `ProjectMemberResponse`.
- `GET /api/project-members/projects?userId=` — list-projects, требует `(PROJECT_MEMBERS, READ)`, успех → 200 множество `projectId`.

Вспомогательные (для подготовки данных и проверки прав):
- `POST /api/auth/login`, `POST /api/auth/logout` — получение/отзыв токенов.
- `POST /api/users` — создание пользователей (ADMIN); `GET /api/roles` — id системных ролей.

### Коды ошибок, проверяемые набором

| Код | HTTP | Когда |
|-----|------|-------|
| `error.project.member.duplicate` | 409 | assign для уже существующей пары `(userId, projectId)` |
| `error.project.role.not.found` | 404 | assign с несуществующим `projectRoleId` |
| `error.project.member.not.found` | 404 | remove несуществующего членства |
| `error.access.denied` | 403 | у роли нет требуемого права на `PROJECT_MEMBERS` |
| `error.auth.unauthorized` | 401 | запрос без аутентификации |
| `error.entity.not.found` | 404 | assign для несуществующего `userId` |

## Стратегия повторяемости

**Комбинированная — генератор + teardown (обязательна для всего файла):**

- **Генератор:** на каждый прогон формируется `run-id` (timestamp/uuid). Создаваемые сущности получают уникальные идентификаторы:
  - пользователи: `email = pm+{run-id}+{seq}@example.com`;
  - `projectId` — синтетический уникальный `BIGINT` на прогон, например `projectId = {run-id-numeric}*100 + {seq}` (таблица `projects` в этой спеке отсутствует, поэтому `project_id` — произвольное число без FK; допустимы любые несовпадающие значения).
- **Teardown (обязательный шаг в конце каждого набора):**
  - удалить каждое созданное членство через `DELETE /api/project-members?userId=&projectId=` (idempotent-безопасно);
  - деактивировать/удалить созданных пользователей через `/api/users`;
  - отозвать выданные refresh-токены (`POST /api/auth/logout`).
- Системные роли (ADMIN, MANAGER, …) и seed-данные (`PROJECT_MEMBERS` ABAC) **не изменяются** — все манипуляции идут над созданными на прогон пользователями/членствами и синтетическими `projectId`. Это делает набор запускаемым многократно без ручной чистки БД.
- Кеш доступа (`ProjectAccessCache`) инвалидируется автоматически по коду при assign/remove и при смене роли пользователя — отдельной ручной чистки не требуется.

### Предусловие-хелпер: активный пользователь с ролью R

Для входа под конкретной ролью нужен ACTIVE-пользователь. Стандартный флоу: ADMIN создаёт пользователя (`POST /api/users`, статус INVITED) → получение invite-токена (через тестовый хелпер БД или перехват почты/mailhog) → `POST /api/auth/set-password` (пользователь становится ACTIVE и сразу получает JWT). Если в окружении настроен только логин-флоу — использовать заранее засиженного ACTIVE-пользователя нужной роли. В шагах ниже это предусловие обозначено как «активный пользователь роли R». Бутстрап-ADMIN логинится напрямую по `FOREMEN_ADMIN_EMAIL`/`FOREMEN_ADMIN_PASSWORD`.

### Приём получения id ролей

`GET /api/roles` (под ADMIN) возвращает системные роли; из ответа берём `id` ролей `ADMIN`, `MANAGER`, `FOREMAN`, `WORKER`, `FINANCIER`, `CLIENT`. Эти id используются как `projectRoleId` при assign.

---

## Фича 1. Схема project_members и уникальность (Req 1)

Проверяет, что членство хранится с уникальностью на пару `(user_id, project_id)`: повторная выдача той же пары запрещается (наблюдается через API как 409).

### TC-SCHEMA-01 — Первое членство создаётся, повтор той же пары отклоняется (409)

**Предусловия:** ADMIN залогинен; создан пользователь `U` (`pm+{run-id}+1@example.com`, роль MANAGER, ACTIVE); известен `managerRoleId`; выбран синтетический `projectId = P1`.

**Шаги:**
1. `POST /api/auth/login` (ADMIN) → 200, получить access-токен ADMIN.
2. `POST /api/project-members` (ADMIN) с телом `{ "userId": U, "projectId": P1, "projectRoleId": managerRoleId }` → **ожидание: 201**, тело `ProjectMemberResponse` с `userId=U`, `projectId=P1`.
3. `POST /api/project-members` (ADMIN) с тем же телом `{ U, P1, managerRoleId }` → **ожидание: 409**, код `error.project.member.duplicate` (вторая строка не создана — уникальность `uk_project_members_user_project`).

**Ожидаемый результат:** пара `(U, P1)` существует в единственном экземпляре; повтор отклонён на уровне бизнес-проверки/констрейнта.

### TC-SCHEMA-02 — Тот же пользователь допускается в другой проект (201)

**Предусловия:** из TC-SCHEMA-01 пользователь `U` уже в проекте `P1`; выбран `projectId = P2 ≠ P1`.

**Шаги:**
1. Логин ADMIN → 200.
2. `POST /api/project-members` с `{ U, P2, managerRoleId }` → **ожидание: 201** (уникальность на пару, не на пользователя — разные `projectId` допустимы).

**Ожидаемый результат:** `list-projects` для `U` содержит и `P1`, и `P2` (проверяется в TC-LIST-02).

---

## Фича 2. Управление членством: assign / remove / list (Req 3, Req 10)

### TC-ASSIGN-01 — Успешное назначение возвращает 201 и ProjectMemberResponse

**Предусловия:** ADMIN залогинен; создан пользователь `U` (ACTIVE); известен `foremanRoleId`; `projectId = P3`.

**Шаги:**
1. Логин ADMIN → 200.
2. `POST /api/project-members` с `{ "userId": U, "projectId": P3, "projectRoleId": foremanRoleId }` → **ожидание: 201**; тело содержит непустой `id`, `userId=U`, `projectId=P3`, `projectRoleId=foremanRoleId`, `projectRoleCode="FOREMAN"`.

**Ожидаемый результат:** строка членства создана, ответ содержит все поля `ProjectMemberResponse` (Req 10.6, 10.14).

### TC-ASSIGN-02 — Дубликат членства → 409

**Предусловия:** членство `(U, P3)` уже создано (TC-ASSIGN-01).

**Шаги:**
1. Логин ADMIN → 200.
2. `POST /api/project-members` с `{ U, P3, foremanRoleId }` → **ожидание: 409**, код `error.project.member.duplicate` (Req 10.7).

**Ожидаемый результат:** второй строки нет; 409 с локализованным сообщением.

### TC-ASSIGN-03 — Неизвестный projectRoleId → 404

**Предусловия:** ADMIN залогинен; `U` создан; `projectId = P4`; выбран `badRoleId` — заведомо отсутствующий id роли (например, `999999999`).

**Шаги:**
1. Логин ADMIN → 200.
2. `POST /api/project-members` с `{ U, P4, 999999999 }` → **ожидание: 404**, код `error.project.role.not.found` (Req 3.3, 10.8).

**Ожидаемый результат:** членство не создано; 404 с кодом роли-не-найдено.

### TC-ASSIGN-04 — Неизвестный userId → 404 (entity not found)

**Предусловия:** ADMIN залогинен; `projectId = P5`; `badUserId = 999999999` (нет такого пользователя); известен любой валидный `roleId`.

**Шаги:**
1. Логин ADMIN → 200.
2. `POST /api/project-members` с `{ 999999999, P5, roleId }` → **ожидание: 404**, код `error.entity.not.found`.

**Ожидаемый результат:** назначение несуществующему пользователю отклонено.

### TC-REMOVE-01 — Удаление существующего членства → 204

**Предусловия:** создано членство `(U, P6)` (шагом ниже).

**Шаги:**
1. Логин ADMIN → 200.
2. `POST /api/project-members` с `{ U, P6, foremanRoleId }` → 201.
3. `DELETE /api/project-members?userId=U&projectId=P6` (ADMIN) → **ожидание: 204** без тела (Req 3.4, 10.9).
4. `GET /api/project-members?projectId=P6` → 200; в списке нет строки с `userId=U`.

**Ожидаемый результат:** строка удалена, повторный список её не содержит.

### TC-REMOVE-02 — Удаление несуществующего членства → 404

**Предусловия:** заведомо нет членства `(U, P7)` для выбранной пары в текущем прогоне.

**Шаги:**
1. Логин ADMIN → 200.
2. `DELETE /api/project-members?userId=U&projectId=P7` → **ожидание: 404**, код `error.project.member.not.found` (Req 3.4, 10.10).

**Ожидаемый результат:** удаление отсутствующего членства отклонено с 404.

### TC-LIST-01 — Список членов проекта возвращает строки этого projectId

**Предусловия:** ADMIN залогинен; созданы два пользователя `U1`, `U2`; оба назначены в `P8`.

**Шаги:**
1. Логин ADMIN → 200.
2. `POST /api/project-members` `{ U1, P8, managerRoleId }` → 201; `POST` `{ U2, P8, foremanRoleId }` → 201.
3. `GET /api/project-members?projectId=P8` → **ожидание: 200**; список содержит ровно строки для `U1` и `U2`, каждая с корректным `projectRoleCode` (Req 3.5, 10.11).
4. `GET /api/project-members?projectId=P9` (пустой проект) → 200; **пустой** список.

**Ожидаемый результат:** возвращаются только члены запрошенного `projectId`.

### TC-LIST-02 — Список проектов пользователя возвращает distinct projectId

**Предусловия:** из TC-SCHEMA-01/02 пользователь `U` в `P1` и `P2`.

**Шаги:**
1. Логин ADMIN → 200.
2. `GET /api/project-members/projects?userId=U` → **ожидание: 200**; множество содержит `P1` и `P2`, без дубликатов (Req 3.6, 10.12).
3. `GET /api/project-members/projects?userId=<пользователь-без-членств>` → 200; **пустое** множество.

**Ожидаемый результат:** возвращается distinct-набор `projectId` пользователя.

---

## Фича 3. RBAC-энфорсмент на PROJECT_MEMBERS: 403/401 + ADMIN-байпас (Req 10, Req 11)

Проверяет, что доступ к эндпоинтам управляется матрицей `PROJECT_MEMBERS`: ADMIN/MANAGER — полный CRUD, FOREMAN/FINANCIER — только READ, WORKER/CLIENT — отказ; неаутентифицированный — 401; ADMIN всегда проходит.

### TC-RBAC-01 — MANAGER имеет полный CRUD (201/200/204)

**Предусловия:** активный пользователь роли MANAGER; создан пользователь-цель `U`; `projectId = P10`.

**Шаги:**
1. Логин MANAGER → 200, токен MANAGER.
2. `POST /api/project-members` (MANAGER) `{ U, P10, foremanRoleId }` → **ожидание: 201**.
3. `GET /api/project-members?projectId=P10` (MANAGER) → **ожидание: 200**.
4. `DELETE /api/project-members?userId=U&projectId=P10` (MANAGER) → **ожидание: 204**.

**Ожидаемый результат:** MANAGER выполняет CREATE/READ/DELETE (Req 11.3).

### TC-RBAC-02 — FOREMAN имеет только READ (200 на GET, 403 на write)

**Предусловия:** активный пользователь роли FOREMAN; существует членство в `P8` (из TC-LIST-01); цель `U`.

**Шаги:**
1. Логин FOREMAN → 200, токен FOREMAN.
2. `GET /api/project-members?projectId=P8` (FOREMAN) → **ожидание: 200** (READ разрешён).
3. `POST /api/project-members` (FOREMAN) `{ U, P11, foremanRoleId }` → **ожидание: 403**, код `error.access.denied` (нет CREATE).
4. `DELETE /api/project-members?userId=U&projectId=P8` (FOREMAN) → **ожидание: 403**, код `error.access.denied` (нет DELETE).

**Ожидаемый результат:** FOREMAN читает, но не пишет (Req 10.3, 11.3).

### TC-RBAC-03 — FINANCIER имеет только READ

**Предусловия:** активный пользователь роли FINANCIER.

**Шаги:**
1. Логин FINANCIER → 200.
2. `GET /api/project-members/projects?userId=U` (FINANCIER) → **ожидание: 200** (READ разрешён).
3. `POST /api/project-members` (FINANCIER) `{ U, P12, foremanRoleId }` → **ожидание: 403**, `error.access.denied`.

**Ожидаемый результат:** FINANCIER аналогичен FOREMAN (READ only) (Req 11.3).

### TC-RBAC-04 — WORKER не имеет прав (403 на всё)

**Предусловия:** активный пользователь роли WORKER.

**Шаги:**
1. Логин WORKER → 200.
2. `GET /api/project-members?projectId=P8` (WORKER) → **ожидание: 403**, `error.access.denied` (нет записи в матрице — deny-by-default).
3. `POST /api/project-members` (WORKER) `{ U, P13, foremanRoleId }` → **ожидание: 403**, `error.access.denied`.

**Ожидаемый результат:** WORKER полностью запрещён (Req 11.4, 11.7).

### TC-RBAC-05 — CLIENT не имеет прав (403 на всё)

**Предусловия:** активный пользователь роли CLIENT.

**Шаги:**
1. Логин CLIENT → 200.
2. `GET /api/project-members?projectId=P8` (CLIENT) → **ожидание: 403**, `error.access.denied`.
3. `DELETE /api/project-members?userId=U&projectId=P8` (CLIENT) → **ожидание: 403**, `error.access.denied`.

**Ожидаемый результат:** CLIENT полностью запрещён (Req 11.4, 11.7).

### TC-RBAC-06 — Неаутентифицированный запрос → 401

**Шаги:**
1. `POST /api/project-members` **без** заголовка `Authorization` с телом `{ U, P14, foremanRoleId }` → **ожидание: 401**, код `error.auth.unauthorized`.
2. `GET /api/project-members?projectId=P8` без токена → **ожидание: 401**, `error.auth.unauthorized`.

**Ожидаемый результат:** отсутствие аутентификации → 401 на всех эндпоинтах (Req 10.4).

### TC-RBAC-07 — ADMIN проходит на все эндпоинты (байпас матрицы)

**Предусловия:** ADMIN залогинен; матрица ADMIN не изменяется.

**Шаги:**
1. Логин ADMIN → 200.
2. `POST /api/project-members` (ADMIN) `{ U, P15, foremanRoleId }` → **ожидание: 201**.
3. `GET /api/project-members?projectId=P15` (ADMIN) → **ожидание: 200**.
4. `GET /api/project-members/projects?userId=U` (ADMIN) → **ожидание: 200**.
5. `DELETE /api/project-members?userId=U&projectId=P15` (ADMIN) → **ожидание: 204**.

**Ожидаемый результат:** ADMIN достигает каждого эндпоинта через байпас независимо от матрицы (Req 10.5).

---

## Фича 4. Проектная фильтрация чтений: ADMIN vs member vs none (Req 6, Req 7)

`ProjectScopedService` фильтрует чтения проектно-скоупленных сущностей по `Allowed_Project_Ids` с ADMIN-байпасом. В проде на этом этапе нет реального проектно-скоупленного контроллера (это FOR-06/FOR-03-08), поэтому механизм доказывается интеграционными тестами через тест-фикстуру (`ScopedFixtureService`). Ниже — **поведенческие API-кейсы**, применимые как только появится проектно-скоупленный read-эндпоинт; для чистого docker-прогона FOR-03-04 они помечены как проверяемые опосредованно через `list-members`/`list-projects` (единственные проектно-связанные чтения этой спеки) и через интеграционный слой фикстуры.

> Примечание: `list-members`/`list-projects` из `ProjectMemberController` **не** проходят через `ProjectScopedService` (они читают сами членства, не проектные данные). Поэтому строгую проверку ADMIN-vs-member-vs-none фильтрации выполняет интеграционный тест фикстуры (задачи 12.2–12.3). API-кейсы ниже фиксируют ожидаемое поведение будущего проектно-скоупленного эндпоинта и подтверждают отсутствие утечки на доступных чтениях.

### TC-SCOPE-01 — ADMIN видит данные без фильтра (байпас)

**Предусловия:** есть проектно-скоупленный read-эндпоинт (условно `GET /api/<scoped>`); данные заведены в проектах `P1` и `P2`.

**Шаги:**
1. Логин ADMIN → 200.
2. `GET /api/<scoped>` токеном ADMIN → **ожидание: 200**; возвращаются строки из всех проектов (`addRequiredQuery()` → `null`, фильтр не применяется) (Req 6.1, 6.4).

**Ожидаемый результат:** ADMIN получает нефильтрованную выборку.

### TC-SCOPE-02 — Не-ADMIN член видит только свои проекты

**Предусловия:** пользователь `U` роли FOREMAN назначен только в `P1`; данные есть в `P1` и `P2`.

**Шаги:**
1. Логин `U` → 200.
2. `GET /api/<scoped>` токеном `U` → **ожидание: 200**; возвращаются **только** строки с `projectId ∈ {P1}`; ни одной строки из `P2` (`projectIdPath IN (P1)`) (Req 6.2, 7.1–7.3).

**Ожидаемый результат:** ни одна строка вне разрешённого набора не просочилась; все строки in-set присутствуют.

### TC-SCOPE-03 — Не-ADMIN без членств не видит ничего

**Предусловия:** пользователь `U0` роли FOREMAN без единого членства.

**Шаги:**
1. Логин `U0` → 200.
2. `GET /api/<scoped>` токеном `U0` → **ожидание: 200**; **пустая** выборка (Empty_Access_Result: `cb.disjunction()`) (Req 6.3).

**Ожидаемый результат:** пустой набор доступа → ноль строк.

### TC-SCOPE-04 — Неаутентифицированный читатель не видит ничего

**Шаги:**
1. `GET /api/<scoped>` **без** токена → на проектно-скоупленном чтении фильтр даёт match-nothing (Req 6.6); если эндпоинт защищён `@RequiresPermission` — предшествующий 401. **Ожидание:** отсутствие раскрытия данных (пусто либо 401).

**Ожидаемый результат:** без аутентификации данные не раскрываются.

### TC-SCOPE-05 — Отсутствие утечки на доступных чтениях этой спеки

**Предусловия:** пользователь `U` роли MANAGER (имеет READ на `PROJECT_MEMBERS`); членства заведены в `P1`, `P2`.

**Шаги:**
1. Логин `U` (MANAGER) → 200.
2. `GET /api/project-members?projectId=P1` → 200; возвращаются строки `P1`.
3. `GET /api/project-members?projectId=P2` → 200; возвращаются строки `P2`.

**Ожидаемый результат:** контроллер членства возвращает запрошенные данные по `projectId`; строгая проектная фильтрация проектных данных подтверждается интеграционным тестом фикстуры (задачи 12.2–12.3).

---

## Фича 5. Инвалидация кеша доступа при смене членства/роли (Req 8)

Через публичный API кеш напрямую не наблюдается; проверяется поведенчески — эффект инвалидации сразу после мутации (без ожидания idle-TTL 1440 мин).

### TC-CACHE-01 — Назначение членства применяется на следующем чтении проектов

**Предусловия:** пользователь `U` без членств; `projectId = P16`.

**Шаги:**
1. Логин ADMIN → 200.
2. `GET /api/project-members/projects?userId=U` → 200; **пустое** множество (кеш загрузил пустой набор).
3. `POST /api/project-members` `{ U, P16, foremanRoleId }` → 201 (assign инвалидирует кеш `U`).
4. `GET /api/project-members/projects?userId=U` → **ожидание: 200**; множество содержит `P16` (перезагружено из БД) (Req 8.1, 8.3).

**Ожидаемый результат:** после assign следующий запрос отражает новое членство без ожидания TTL.

### TC-CACHE-02 — Удаление членства применяется на следующем чтении

**Предусловия:** из TC-CACHE-01 у `U` есть членство `P16`.

**Шаги:**
1. Логин ADMIN → 200.
2. `DELETE /api/project-members?userId=U&projectId=P16` → 204 (remove инвалидирует кеш `U`).
3. `GET /api/project-members/projects?userId=U` → **ожидание: 200**; множество **не** содержит `P16` (Req 8.2, 8.3).

**Ожидаемый результат:** после remove доступ к проекту снят немедленно.

### TC-CACHE-03 — Смена роли пользователя инвалидирует кеш доступа

**Предусловия:** пользователь `U` роли FOREMAN с членством в `P1`; проектно-скоупленный эндпоинт `GET /api/<scoped>` (см. Фича 4) доступен для наблюдения байпаса.

**Шаги:**
1. Логин `U` (FOREMAN) → 200; `GET /api/<scoped>` → видит только `P1` (не-ADMIN, фильтр применён).
2. Логин ADMIN → 200; `PUT /api/users/{U}` со сменой роли `U` на ADMIN → 200 (`UserService` инвалидирует кеш доступа `U`, Req 8.4).
3. Повторный логин `U` (теперь ADMIN) → 200; `GET /api/<scoped>` → **ожидание:** нефильтрованная выборка (байпас), доступ пересчитан после инвалидации.

**Ожидаемый результат:** смена роли через `UserService` немедленно отражается на доступе (Req 8.4). Если проектно-скоупленного эндпоинта в docker-прогоне нет — кейс проверяется интеграционно; в API-прогоне достаточно подтвердить успешную смену роли (200) и повторный вход.

---

## Фича 6. Локализация новых кодов ошибок PL/RU (Req 9)

### TC-I18N-01 — duplicate: 409 сообщение отличается для pl и ru

**Предусловия:** существует членство `(U, P17)`.

**Шаги:**
1. Логин ADMIN → 200.
2. `POST /api/project-members` `{ U, P17, foremanRoleId }` с `Accept-Language: pl` → **ожидание: 409**, код `error.project.member.duplicate`; запомнить `message` (польский).
3. Тот же запрос с `Accept-Language: ru` → 409; запомнить `message` (русский).
4. Сравнить → **ожидание:** оба непустые и различаются; код в обоих `error.project.member.duplicate` (Req 9.1).

**Ожидаемый результат:** код дубликата локализован в PL и RU.

### TC-I18N-02 — role not found: 404 сообщение отличается для pl и ru

**Шаги:**
1. Логин ADMIN → 200.
2. `POST /api/project-members` `{ U, P18, 999999999 }` с `Accept-Language: pl` → 404, `error.project.role.not.found`; запомнить `message`.
3. То же с `Accept-Language: ru` → 404; запомнить `message`.
4. Сравнить → **ожидание:** оба непустые и различны (Req 9.2).

**Ожидаемый результат:** код роли-не-найдено локализован PL+RU.

### TC-I18N-03 — member not found: 404 сообщение отличается для pl и ru

**Шаги:**
1. Логин ADMIN → 200.
2. `DELETE /api/project-members?userId=U&projectId=P19` (нет такого членства) с `Accept-Language: pl` → 404, `error.project.member.not.found`; запомнить `message`.
3. То же с `Accept-Language: ru` → 404; запомнить `message`.
4. Сравнить → **ожидание:** оба непустые и различны (Req 9.3).

**Ожидаемый результат:** код членства-не-найдено локализован PL+RU.

---

## Teardown (выполняется в конце каждого прогона)

1. Для каждого созданного членства: `DELETE /api/project-members?userId=&projectId=` (под ADMIN; 404 на уже удалённых — допустимо).
2. Для каждого созданного пользователя: `POST /api/auth/logout` (отзыв refresh-токенов) и деактивация/удаление через `/api/users`.
3. Если в TC-CACHE-03 менялась роль тестового пользователя — вернуть исходную роль либо деактивировать пользователя (системные роли и seed при этом не трогаем).
4. Проверить отсутствие остаточных членств/пользователей с текущим `run-id`.

---

## Регрессия

Перепроверка критичных сквозных путей и ранее закрытых рисков после изменений в механизме проектного скоупинга и RBAC.

### REG-01 — Полный жизненный цикл членства
assign `(U, P)` → 201 → `list-projects(U)` содержит `P` → `list-members(P)` содержит `U` → remove → 204 → `list-projects(U)` без `P`. Подтверждает связку сервис + DAO + инвалидация кеша.
_Покрывает: TC-ASSIGN-01, TC-LIST-01, TC-LIST-02, TC-REMOVE-01, TC-CACHE-01, TC-CACHE-02._

### REG-02 — Матрица PROJECT_MEMBERS соответствует сиду
Под каждой системной ролью проверить доступ к `GET /api/project-members?projectId=P`: ADMIN/MANAGER/FOREMAN/FINANCIER → 200, WORKER/CLIENT → 403; запись/удаление: ADMIN/MANAGER → успех, FOREMAN/FINANCIER/WORKER/CLIENT → 403. Подтверждает целостность ABAC-сида `015`.
_Покрывает: TC-RBAC-01..05, Req 11.3, 11.4, 11.7._

### REG-03 — ADMIN не блокируется матрицей
После любых изменений членств/ролей ADMIN сохраняет доступ ко всем четырём эндпоинтам.
_Покрывает: TC-RBAC-07._

### REG-04 — 401 раньше 403 для неаутентифицированного
Неаутентифицированный запрос к любому эндпоинту `/api/project-members` → 401 `error.auth.unauthorized` (а не 403), подтверждая порядок enforcement.
_Покрывает: TC-RBAC-06, Req 10.4._

### REG-05 — Уникальность пары не деградировала
Повторный assign существующей пары `(U, P)` стабильно → 409 `error.project.member.duplicate` на многократных прогонах.
_Покрывает: TC-SCHEMA-01, TC-ASSIGN-02._

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SCHEMA-01 | 2 | POST /api/project-members (U,P1) | 201 ProjectMemberResponse | | |
| 2 | TC-SCHEMA-01 | 3 | POST повтор (U,P1) | 409 error.project.member.duplicate | | |
| 3 | TC-SCHEMA-02 | 2 | POST (U,P2) | 201 | | |
| 4 | TC-ASSIGN-01 | 2 | POST (U,P3,FOREMAN) | 201 + projectRoleCode=FOREMAN | | |
| 5 | TC-ASSIGN-02 | 2 | POST повтор (U,P3) | 409 error.project.member.duplicate | | |
| 6 | TC-ASSIGN-03 | 2 | POST (U,P4,badRoleId) | 404 error.project.role.not.found | | |
| 7 | TC-ASSIGN-04 | 2 | POST (badUserId,P5) | 404 error.entity.not.found | | |
| 8 | TC-REMOVE-01 | 3 | DELETE ?userId=U&projectId=P6 | 204 | | |
| 9 | TC-REMOVE-02 | 2 | DELETE ?userId=U&projectId=P7 | 404 error.project.member.not.found | | |
| 10 | TC-LIST-01 | 3 | GET ?projectId=P8 | 200 + U1,U2 | | |
| 11 | TC-LIST-01 | 4 | GET ?projectId=P9 | 200 пустой список | | |
| 12 | TC-LIST-02 | 2 | GET /projects?userId=U | 200 + {P1,P2} distinct | | |
| 13 | TC-RBAC-01 | 2-4 | MANAGER CREATE/READ/DELETE | 201/200/204 | | |
| 14 | TC-RBAC-02 | 3 | FOREMAN POST | 403 error.access.denied | | |
| 15 | TC-RBAC-02 | 2 | FOREMAN GET | 200 | | |
| 16 | TC-RBAC-03 | 3 | FINANCIER POST | 403 error.access.denied | | |
| 17 | TC-RBAC-04 | 2 | WORKER GET | 403 error.access.denied | | |
| 18 | TC-RBAC-05 | 2 | CLIENT GET | 403 error.access.denied | | |
| 19 | TC-RBAC-06 | 1 | POST без токена | 401 error.auth.unauthorized | | |
| 20 | TC-RBAC-07 | 2-5 | ADMIN на все эндпоинты | 201/200/200/204 | | |
| 21 | TC-SCOPE-01 | 2 | GET /api/<scoped> (ADMIN) | 200 нефильтрованно | | |
| 22 | TC-SCOPE-02 | 2 | GET /api/<scoped> (член P1) | 200 только P1 | | |
| 23 | TC-SCOPE-03 | 2 | GET /api/<scoped> (без членств) | 200 пусто | | |
| 24 | TC-SCOPE-04 | 1 | GET /api/<scoped> (без токена) | пусто/401 | | |
| 25 | TC-SCOPE-05 | 2-3 | GET /api/project-members?projectId | 200 корректные данные | | |
| 26 | TC-CACHE-01 | 4 | GET /projects?userId=U после assign | 200 + P16 | | |
| 27 | TC-CACHE-02 | 3 | GET /projects?userId=U после remove | 200 без P16 | | |
| 28 | TC-CACHE-03 | 2-3 | PUT смена роли U, повторное чтение | роль сменена, доступ пересчитан | | |
| 29 | TC-I18N-01 | 4 | 409 pl vs ru | тексты непустые и различны | | |
| 30 | TC-I18N-02 | 4 | 404 role pl vs ru | тексты непустые и различны | | |
| 31 | TC-I18N-03 | 4 | 404 member pl vs ru | тексты непустые и различны | | |
| 32 | REG-01 | — | assign→list→remove→list | 201→…→без P | | |
| 33 | REG-02 | — | матрица по всем ролям | соответствует сиду | | |
| 34 | REG-03 | — | ADMIN после изменений | доступ ко всем эндпоинтам | | |
| 35 | REG-04 | — | без токена → 401 (не 403) | 401 error.auth.unauthorized | | |
| 36 | REG-05 | — | повтор assign (U,P) | 409 error.project.member.duplicate | | |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`. Стратегия повторяемости: `генератор (pm+{run-id}, синтетический projectId) + teardown (DELETE членств, деактивация пользователей, logout)`.
