# Тест-кейсы: FOR-03-08 API Protection

## Назначение и характер спеки

Спека **чисто бекендовая** (нет браузерных экранов). Тест-кейсы — это **API-тесты против работающего в Docker приложения** (не Testcontainers/юнит-слой). Каждый шаг — HTTP-запрос к API с проверкой статуса, тела и заголовков. Результат прогона — **MD-репорт с таблицами** (шаг → запрос → ожидание → факт → статус). Скриншоты не требуются.

Спека закрывает **два композирующихся слоя enforcement**:

1. **Authentication (Spring Security `SecurityFilterChain`)** — catch-all `anyRequest()` мигрирует с `permitAll()` на `authenticated()`. Любой запрос вне публичных auth-матчеров теперь требует валидного JWT-принципала (иначе 401 через `JwtAuthenticationEntryPoint`).
2. **Authorization (Spring MVC `PermissionInterceptor` + `PermissionResolver`)** — декларативное inheritance-aware ABAC-гардирование: `@RequiresPermission` (приоритет) → `@PermissionResource` (класс) + `@PermissionOperation` (метод) → иначе Unguarded (без проверки матрицы). При отказе — 403.

Композиция: сначала фильтр-чейн решает, нужен ли аутентифицированный принципал (401 при отсутствии); затем интерцептор решает, есть ли у роли нужный grant в матрице (403 при deny). Аутентифицированный и разрешённый запрос проходит (200).

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`.
3. Auth-эндпоинты — под `/api/auth`.
4. Первый ADMIN создаётся через переменные окружения бекенда: `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
5. Дождаться готовности `backend` (health/readiness) перед запуском кейсов.

### Токены и заголовки

- **Токен получают** через `POST /api/auth/login` (тело: `{ "email", "password" }`), из ответа берётся `accessToken`.
- Все защищённые запросы идут с заголовком `Authorization: Bearer <accessToken>`.
- Локализация ответов об ошибках проверяется через `Accept-Language: ru|pl`.

### Определения ролей для кейсов

- **ADMIN** — системная роль, обходит матрицу (bypass). Всегда получает 200 на любом гардированном эндпоинте.
- **Роль-с-правом** — тестовая роль, которой через `PUT /api/roles/{id}/permissions` выдан нужный `(resource, operation)` grant (например, `USERS: [READ]`).
- **Роль-без-права** — тестовая роль без нужного grant (пустая матрица или матрица без целевого ресурса/операции).

## Стратегия повторяемости

**Комбинированная — генератор + teardown:**

- **Генератор:** на каждый прогон формируется `run-id` (timestamp/uuid). Создаваемые сущности получают уникальные идентификаторы:
  - тестовые роли: `code = TESTROLE_{run-id}_{seq}` (несистемные → удаляемы);
  - пользователи: `email = apiprot+{run-id}+{seq}@example.com`.
- **Teardown (обязательный шаг в конце каждого набора):** разлогинить выданные refresh-токены (`POST /api/auth/logout`), деактивировать/удалить созданных пользователей (`/api/users`), удалить созданные несистемные роли (`DELETE /api/roles/{id}`).
- Системные роли (ADMIN, MANAGER, …) и seed-данные **не изменяются** — все манипуляции над созданными на прогон ролями/пользователями. Набор запускаем многократно без ручной чистки БД.

### Предусловие-хелпер: активный пользователь с ролью R

Для входа под конкретной ролью нужен ACTIVE-пользователь. Стандартный флоу: ADMIN создаёт пользователя (`POST /api/users`, статус INVITED) → получение invite-токена (через тестовый хелпер БД или mailhog) → `POST /api/auth/set-password` (пользователь становится ACTIVE и сразу получает JWT). В шагах ниже это предусловие обозначено как «активный пользователь роли R».

### Приём выдачи прав роли

Логинимся как ADMIN → `GET /api/resources` и `GET /api/operations` для id → `PUT /api/roles/{testRoleId}/permissions` с нужной матрицей → логинимся пользователем целевой роли → дёргаем защищённый эндпоинт.

---

## Фича 1. Композиция фильтр-чейна: 401 / 403 / 200 (Req 14, 15)

Проверяет, что после миграции `anyRequest()` → `authenticated()` защищённый `/api/**` эндпоинт: без токена → 401 (фильтр-чейн, интерцептор не запускается); с токеном роли-без-права → 403 (интерцептор); с токеном роли-с-правом или ADMIN → 200.

### TC-FC-01 — Без токена к защищённому `/api/**` → 401 (фильтр-чейн)

**Предусловия:** окружение поднято; токен не используется.

**Шаги:**
1. `GET /api/users` **без** заголовка `Authorization` → **ожидание: 401**, тело `ErrorResponse` с кодом `error.auth.unauthorized`. Ответ формируется `JwtAuthenticationEntryPoint` на слое фильтр-чейна; `PermissionInterceptor` не выполняется.

**Ожидаемый результат:** 401; catch-all `authenticated()` отклоняет неаутентифицированный запрос до контроллера. (Req 14.1, 14.2, 15.1)

### TC-FC-02 — Невалидный токен к защищённому `/api/**` → 401

**Шаги:**
1. `GET /api/users` с `Authorization: Bearer invalid.token.value` → **ожидание: 401**, код `error.auth.unauthorized`.

**Ожидаемый результат:** 401; контекст не аутентифицирован. (Req 14.2, 15.1)

### TC-FC-03 — Токен роли без нужного grant → 403 (интерцептор)

**Предусловия:** тестовая роль без grant `USERS/READ`; активный пользователь этой роли.

**Шаги:**
1. Логин ADMIN → 200.
2. `PUT /api/roles/{testRoleId}/permissions` с матрицей БЕЗ `USERS` (пустая или только другой ресурс) → 200.
3. Логин пользователем роли → 200, получить его access-токен.
4. `GET /api/users` с токеном роли → **ожидание: 403**, код `error.access.denied`. Запрос прошёл аутентификацию (принципал есть), но интерцептор отклонил по матрице.

**Ожидаемый результат:** 403 `error.access.denied`; аутентификация пройдена, авторизация отклонена. (Req 15.2)

### TC-FC-04 — Токен роли с нужным grant → 200

**Предусловия:** тестовая роль с grant `USERS/READ`; активный пользователь этой роли.

**Шаги:**
1. Логин ADMIN → 200.
2. `PUT /api/roles/{testRoleId}/permissions` с `USERS: [READ]` → 200.
3. Логин пользователем роли → 200.
4. `GET /api/users` с токеном роли → **ожидание: 200**, тело — успешный список/страница.

**Ожидаемый результат:** 200; аутентификация + авторизация пройдены. (Req 15.3)

### TC-FC-05 — ADMIN проходит гардированный эндпоинт минуя матрицу → 200

**Предусловия:** ADMIN залогинен; матрица ADMIN не изменяется.

**Шаги:**
1. Логин ADMIN → 200.
2. `GET /api/users` с токеном ADMIN → **ожидание: 200** независимо от содержимого матрицы (ADMIN bypass).

**Ожидаемый результат:** 200; ADMIN не проверяется через матрицу. (Req 7.4, 15.3)

---

## Фича 2. Публичные auth-матчеры и порядок матчеров (Req 14.3–14.6, 16.3)

Проверяет, что после миграции `/api/auth/**` остаётся `permitAll`, `/api/auth/me` — `authenticated`, `POST /api/auth/resend-invite` — `hasRole('ADMIN')`, и специфичные auth-правила имеют приоритет над мигрированным catch-all.

### TC-AUTH-01 — `/api/auth/**` остаётся публичным (не 401 от фильтр-чейна)

**Шаги:**
1. `POST /api/auth/login` с корректными кредами ADMIN и **без** заголовка `Authorization` → **ожидание: 200** (эндпоинт достижим без принципала, `Filter_Chain_Public`).
2. `POST /api/auth/login` с заведомо неверным паролем → **ожидание: 401/400 доменной ошибки логина** (НЕ 401 от `JwtAuthenticationEntryPoint`) — важно, что запрос **дошёл до хендлера**, а не был отсечён фильтр-чейном.

**Ожидаемый результат:** `/api/auth/**` достижим без аутентификации; фильтр-чейн его не отсекает. (Req 14.3, 15.4, 16.3)

### TC-AUTH-02 — `GET /api/auth/me` без токена → 401

**Шаги:**
1. `GET /api/auth/me` **без** `Authorization` → **ожидание: 401**, код `error.auth.unauthorized`.

**Ожидаемый результат:** 401; `/api/auth/me` требует аутентификации. (Req 14.4)

### TC-AUTH-03 — `GET /api/auth/me` с валидным токеном → 200

**Предусловия:** любой активный пользователь (например ADMIN) залогинен.

**Шаги:**
1. Логин ADMIN → 200.
2. `GET /api/auth/me` с токеном → **ожидание: 200**, тело — профиль текущего пользователя.

**Ожидаемый результат:** 200; аутентифицированный доступ к `me` сохранён. (Req 14.4)

### TC-AUTH-04 — `POST /api/auth/resend-invite` без токена → 401

**Шаги:**
1. `POST /api/auth/resend-invite` (валидное тело) **без** `Authorization` → **ожидание: 401**, код `error.auth.unauthorized`.

**Ожидаемый результат:** 401; эндпоинт защищён `hasRole('ADMIN')`, неаутентифицированный запрос отклонён. (Req 14.5)

### TC-AUTH-05 — `POST /api/auth/resend-invite` под non-ADMIN → 403; под ADMIN → 200

**Предусловия:** активный пользователь non-ADMIN роли; существующий INVITED-пользователь для повторного приглашения.

**Шаги:**
1. Логин пользователем non-ADMIN роли → 200.
2. `POST /api/auth/resend-invite` с токеном non-ADMIN → **ожидание: 403** (правило `hasRole('ADMIN')` на фильтр-чейне).
3. Логин ADMIN → 200.
4. `POST /api/auth/resend-invite` с токеном ADMIN (валидное тело) → **ожидание: 200/2xx**.

**Ожидаемый результат:** только ADMIN проходит `resend-invite`; правило матчера имеет приоритет над catch-all. (Req 14.5, 14.6)

### TC-AUTH-06 — Приоритет порядка матчеров сохранён

**Проверка:** специфичные правила (`/api/auth/me`, `POST /api/auth/resend-invite`, `/api/auth/**`) объявлены и срабатывают ДО catch-all `anyRequest().authenticated()`.

**Шаги:**
1. TC-AUTH-01 (публичный `/api/auth/login` доступен) + TC-AUTH-02 (`/api/auth/me` → 401) + TC-AUTH-04 (`resend-invite` без токена → 401) прогоняются вместе → **ожидание:** каждый эндпоинт ведёт себя по своему специфичному правилу, а не по catch-all.

**Ожидаемый результат:** порядок матчеров сохранён: `/api/auth/me` → `POST /api/auth/resend-invite` → `/api/auth/**` → `anyRequest()`. (Req 14.6)

---

## Фича 3. Разрешение прав: `@PermissionResource` + `@PermissionOperation` (Req 10.1)

Проверяет, что унаследованные CRUD-эндпоинты гардированных контроллеров резолвятся в пару «ресурс контроллера + операция метода».

### TC-RES-01 — `GET /api/users` резолвится в `USERS/READ`

**Предусловия:** роль с `USERS: [READ]` (без CREATE); активный пользователь роли.

**Шаги:**
1. Логин ADMIN → 200; выдать роли `USERS: [READ]`.
2. Логин пользователем роли → 200.
3. `GET /api/users` с токеном роли → **ожидание: 200** (READ разрешён).
4. `POST /api/users` (валидное тело нового пользователя) с тем же токеном → **ожидание: 403** `error.access.denied` (CREATE не выдан → пара `USERS/CREATE` отклонена).

**Ожидаемый результат:** READ-эндпоинт резолвится в `USERS/READ` (200), create-эндпоинт — в `USERS/CREATE` (403 без grant). (Req 10.1)

### TC-RES-02 — `POST /api/users` резолвится в `USERS/CREATE`

**Предусловия:** роль с `USERS: [CREATE]`; активный пользователь роли.

**Шаги:**
1. Логин ADMIN → 200; выдать роли `USERS: [CREATE]`.
2. Логин пользователем роли → 200.
3. `POST /api/users` c уникальным `email = apiprot+{run-id}+create@example.com` → **ожидание: 2xx** (CREATE разрешён).

**Ожидаемый результат:** create-эндпоинт резолвится в `USERS/CREATE` и проходит при наличии grant. (Req 10.1)
_Teardown: удалить/деактивировать созданного пользователя._

### TC-RES-03 — Read-only контроллеры резолвятся в `<RESOURCE>/READ`

**Предусловия:** роль с `RESOURCES: [READ]` и `OPERATIONS: [READ]`; активный пользователь роли.

**Шаги:**
1. Логин ADMIN → 200; выдать роли `RESOURCES: [READ]`, `OPERATIONS: [READ]`.
2. Логин пользователем роли → 200.
3. `GET /api/resources` с токеном роли → **ожидание: 200** (`RESOURCES/READ`).
4. `GET /api/operations` с токеном роли → **ожидание: 200** (`OPERATIONS/READ`).
5. `GET /api/audit` (или соответствующий путь `AuditController`) с этим же токеном (без `AUDIT` grant) → **ожидание: 403** `error.access.denied` (`AUDIT/READ` не выдан).

**Ожидаемый результат:** каждый read-only контроллер резолвится в свой ресурс + `READ`. (Req 10.1)

---

## Фича 4. Приоритет `@RequiresPermission` (Req 10.2, 10.3)

### TC-RP-01 — `POST /api/users/client` резолвится в `PROJECTS/EDIT` (не `USERS/CREATE`)

**Предусловия:** роль с `PROJECTS: [EDIT]`, но БЕЗ `USERS: [CREATE]`; активный пользователь роли.

**Шаги:**
1. Логин ADMIN → 200; выдать роли `PROJECTS: [EDIT]` (без `USERS`).
2. Логин пользователем роли → 200.
3. `POST /api/users/client` (валидное тело) с токеном роли → **ожидание: 2xx** — эндпоинт резолвится по `@RequiresPermission(PROJECTS, EDIT)`, а не по классовому `USERS/CREATE`.

**Ожидаемый результат:** `@RequiresPermission` имеет приоритет над `@PermissionResource`+`@PermissionOperation`. (Req 10.2)
_Teardown: удалить/деактивировать созданного клиента._

### TC-RP-02 — `POST /api/users/client` без `PROJECTS/EDIT` → 403

**Предусловия:** роль с `USERS: [CREATE]`, но БЕЗ `PROJECTS: [EDIT]`; активный пользователь роли.

**Шаги:**
1. Логин ADMIN → 200; выдать роли `USERS: [CREATE]` (без `PROJECTS`).
2. Логин пользователем роли → 200.
3. `POST /api/users/client` с токеном роли → **ожидание: 403** `error.access.denied` — резолвится в `PROJECTS/EDIT`, а не в `USERS/CREATE`, поэтому наличие `USERS/CREATE` не помогает.

**Ожидаемый результат:** подтверждает, что классовые аннотации игнорируются при наличии `@RequiresPermission`. (Req 10.2)

### TC-RP-03 — `ProjectMemberController` резолвится в `PROJECT_MEMBERS`

**Предусловия:** роль без grant на `PROJECT_MEMBERS`; активный пользователь роли; ADMIN с grant.

**Шаги:**
1. Логин пользователем роли-без-права → 200.
2. Запрос к любому эндпоинту `ProjectMemberController` (например `GET /api/project-members/...` по фактическому пути) с токеном роли → **ожидание: 403** `error.access.denied` (пара на `PROJECT_MEMBERS`).
3. Логин ADMIN → 200; тот же запрос токеном ADMIN → **ожидание: 200** (bypass).

**Ожидаемый результат:** `ProjectMemberController` сохраняет per-method `@RequiresPermission` на `PROJECT_MEMBERS`. (Req 10.3)

---

## Фича 5. Unguarded-контроллеры: authenticated, но без проверки матрицы (Req 10.4, 16)

Проверяет, что Unguarded-хендлеры (`DisplayPreferencesController`, `AuthController`-self-service) после миграции требуют аутентификации на непубличных путях, но НЕ проходят проверку матрицы.

### TC-UG-01 — `DisplayPreferencesController` без токена → 401 (фильтр-чейн)

**Шаги:**
1. Запрос к эндпоинту `DisplayPreferencesController` (по фактическому пути, напр. `GET /api/display-preferences/{userId}`) **без** `Authorization` → **ожидание: 401**, код `error.auth.unauthorized`. Путь НЕ `Filter_Chain_Public`, поэтому фильтр-чейн требует принципала.

**Ожидаемый результат:** 401; Unguarded ≠ public — после миграции нужен аутентифицированный принципал. (Req 16.1, 16.2)

### TC-UG-02 — `DisplayPreferencesController` с токеном → без проверки матрицы (self-check)

**Предусловия:** активный пользователь любой роли (без специальных grant); знает свой `userId`.

**Шаги:**
1. Логин пользователем → 200.
2. Запрос своих display-preferences (свой `userId`) с токеном → **ожидание: 2xx** — интерцептор НЕ проверяет матрицу (Unguarded), контроллер выполняет собственную self-versus-requested-id логику.
3. Запрос display-preferences ЧУЖОГО `userId` с тем же токеном → **ожидание: отказ по собственной логике контроллера** (например 403/404 из self-check), а НЕ по матрице.

**Ожидаемый результат:** `Authenticated_But_Not_Matrix_Checked` — аутентификация требуется, матрица не проверяется, работает self-check. (Req 16.1, 16.2)

### TC-UG-03 — `AuthController` self-service остаётся публичным

**Шаги:**
1. Публичные `/api/auth/**` (login/refresh/logout/set-password, OTP request/verify) достижимы без токена (см. TC-AUTH-01) → **ожидание:** запросы доходят до хендлеров, фильтр-чейн не отсекает 401.

**Ожидаемый результат:** `AuthController` под `/api/auth/**` остаётся `Filter_Chain_Public`. (Req 16.3, 16.4)

---

## Фича 6. Полнота seed матрицы (Req 11)

Проверяет наличие ресурса `USERS` и остальных ресурсов в матрице, а также полноту ADMIN CRUD grants и идемпотентность seed.

### TC-SEED-01 — Ресурс `USERS` присутствует в матрице

**Предусловия:** ADMIN залогинен.

**Шаги:**
1. Логин ADMIN → 200.
2. `GET /api/resources` → **ожидание: 200**; в списке присутствуют строки для `USERS`, `ROLES`, `AUDIT`, `RESOURCES`, `OPERATIONS`, `PROJECT_MEMBERS`.

**Ожидаемый результат:** все шесть ресурсов присутствуют, включая ранее отсутствовавший `USERS`. (Req 11.1, 11.2)

### TC-SEED-02 — ADMIN держит CRUD grants на `USERS`

**Предусловия:** ADMIN залогинен; известен id роли ADMIN.

**Шаги:**
1. Логин ADMIN → 200.
2. `GET /api/roles/{adminRoleId}/permissions` → **ожидание: 200**; для ресурса `USERS` присутствуют операции `CREATE`, `READ`, `UPDATE`, `DELETE` (полный CRUD-grant для ADMIN).

**Ожидаемый результат:** ADMIN grants на `USERS` полны для гардированных пар, которые резолвят CRUD-эндпоинты `UserController`. (Req 11.3)

### TC-SEED-03 — Идемпотентность seed при повторном прогоне

**Предусловия:** возможность перезапустить контейнер `liquibase` против уже засиженной БД (не пересоздавая volume).

**Шаги:**
1. Зафиксировать число строк `resources` и `role_resource_operations` (через `GET /api/resources` и `GET /api/roles/{adminRoleId}/permissions`).
2. Перезапустить `liquibase` (`docker compose up liquibase` без сброса volume) → миграции проходят без ошибок.
3. Повторно снять те же метрики → **ожидание:** количество строк не изменилось (нет дублей вставок).

**Ожидаемый результат:** changeset `017-seed-users-resource` идемпотентен (`preConditions onFail="MARK_RAN"`), повторный прогон не вставляет дубли. (Req 11.4)

_Примечание: changeset `017-seed-users-resource.xml` зарегистрирован в `changelog.xml` последним по порядку (Req 11.5) — проверяется структурно на уровне репозитория/юнит-теста, не через API._

---

## Teardown (выполняется в конце каждого прогона)

1. Для каждого созданного пользователя: `POST /api/auth/logout` (отзыв refresh-токена) и деактивация/удаление через `/api/users`.
2. Для каждой созданной тестовой роли: `DELETE /api/roles/{id}` (несистемные роли удаляемы).
3. Проверить отсутствие остаточных сущностей с текущим `run-id`.
4. Матрицы системных ролей (ADMIN и др.) не трогались — дополнительная чистка не требуется.

---

## Регрессия

Перепроверка критичных сквозных путей после миграции фильтр-чейна и рефакторинга разрешения прав.

### REG-01 — Композиция 401 → 403 → 200 на одном эндпоинте
`GET /api/users`: без токена → 401; токеном роли-без-права → 403; токеном роли-с-правом → 200; токеном ADMIN → 200.
_Покрывает: TC-FC-01, TC-FC-03, TC-FC-04, TC-FC-05._

### REG-02 — Публичные auth-эндпоинты не сломаны миграцией
`POST /api/auth/login` доступен без токена; `GET /api/auth/me` без токена → 401; `POST /api/auth/resend-invite` без токена → 401.
_Покрывает: TC-AUTH-01, TC-AUTH-02, TC-AUTH-04._

### REG-03 — `@RequiresPermission` не деградировал
`POST /api/users/client` резолвится в `PROJECTS/EDIT` (а не `USERS/CREATE`); `ProjectMemberController` резолвится в `PROJECT_MEMBERS`.
_Покрывает: TC-RP-01, TC-RP-02, TC-RP-03._

### REG-04 — Unguarded-эндпоинты стали authenticated, но не matrix-checked
`DisplayPreferencesController` без токена → 401; с токеном — работает self-check без проверки матрицы.
_Покрывает: TC-UG-01, TC-UG-02._

### REG-05 — Матрица прав и seed целостны
`GET /api/resources` содержит все шесть ресурсов; ADMIN держит CRUD на `USERS`; матрицы системных ролей не повреждены.
_Покрывает: TC-SEED-01, TC-SEED-02._

### REG-06 — Локализация ошибок PL/RU
403 (`error.access.denied`) и 401 (`error.auth.unauthorized`) возвращают локализованные сообщения при `Accept-Language: ru` и `Accept-Language: pl` (тексты непустые и различны).
_Проверяется на кейсах TC-FC-01/03 с разными `Accept-Language`._

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-FC-01 | 1 | GET /api/users (без токена) | 401 error.auth.unauthorized | | |
| 2 | TC-FC-02 | 1 | GET /api/users (bad token) | 401 error.auth.unauthorized | | |
| 3 | TC-FC-03 | 4 | GET /api/users (роль без USERS/READ) | 403 error.access.denied | | |
| 4 | TC-FC-04 | 4 | GET /api/users (роль с USERS/READ) | 200 | | |
| 5 | TC-FC-05 | 2 | GET /api/users (ADMIN) | 200 | | |
| 6 | TC-AUTH-01 | 1 | POST /api/auth/login (без токена) | 200 (достижим) | | |
| 7 | TC-AUTH-02 | 1 | GET /api/auth/me (без токена) | 401 error.auth.unauthorized | | |
| 8 | TC-AUTH-03 | 2 | GET /api/auth/me (валидный токен) | 200 профиль | | |
| 9 | TC-AUTH-04 | 1 | POST /api/auth/resend-invite (без токена) | 401 error.auth.unauthorized | | |
| 10 | TC-AUTH-05 | 2/4 | POST /api/auth/resend-invite (non-ADMIN / ADMIN) | 403 / 2xx | | |
| 11 | TC-AUTH-06 | 1 | Совокупный прогон auth-матчеров | правила по приоритету | | |
| 12 | TC-RES-01 | 3/4 | GET/POST /api/users (роль USERS:READ) | 200 / 403 | | |
| 13 | TC-RES-02 | 3 | POST /api/users (роль USERS:CREATE) | 2xx | | |
| 14 | TC-RES-03 | 3-5 | GET /api/resources,/operations,/audit | 200,200,403 | | |
| 15 | TC-RP-01 | 3 | POST /api/users/client (роль PROJECTS:EDIT) | 2xx | | |
| 16 | TC-RP-02 | 3 | POST /api/users/client (роль USERS:CREATE) | 403 error.access.denied | | |
| 17 | TC-RP-03 | 2/3 | ProjectMember endpoint (роль / ADMIN) | 403 / 200 | | |
| 18 | TC-UG-01 | 1 | display-preferences (без токена) | 401 error.auth.unauthorized | | |
| 19 | TC-UG-02 | 2/3 | display-preferences (свой / чужой id) | 2xx / отказ self-check | | |
| 20 | TC-UG-03 | 1 | /api/auth/** (без токена) | достижимы, не 401 фильтра | | |
| 21 | TC-SEED-01 | 2 | GET /api/resources | 6 ресурсов вкл. USERS | | |
| 22 | TC-SEED-02 | 2 | GET /api/roles/{admin}/permissions | USERS: CRUD | | |
| 23 | TC-SEED-03 | 3 | Повторный прогон liquibase | без дублей | | |
| 24 | REG-01 | — | GET /api/users: 401→403→200→200(ADMIN) | цепочка совпала | | |
| 25 | REG-02 | — | публичные auth-эндпоинты | login OK, me/resend 401 | | |
| 26 | REG-03 | — | RequiresPermission precedence | PROJECTS/EDIT, PROJECT_MEMBERS | | |
| 27 | REG-04 | — | Unguarded → authenticated, не matrix | 401 без токена, self-check с токеном | | |
| 28 | REG-05 | — | целостность матрицы/seed | 6 ресурсов, ADMIN CRUD USERS | | |
| 29 | REG-06 | — | локализация 401/403 pl vs ru | тексты непустые и различны | | |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`. Стратегия повторяемости: `генератор (TESTROLE_/apiprot+{run-id}) + teardown`.
