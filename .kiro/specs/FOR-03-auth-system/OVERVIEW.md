# FOR-03: Система авторизации

## Обзор

FOR-03 — система аутентификации и авторизации Foremen. Включает регистрацию по приглашению, JWT-логин, OTP для клиентов, проектные роли, фильтрацию данных по правам (RBAC + project ownership), видимость меню на фронтенде и runtime-валидацию доступа на бекенде.

## Статус

✅ **Завершено** (94%). Реализованы все дочерние спеки: JWT-аутентификация, регистрация по приглашению, permission evaluator, project ownership + валидация проектных действий, OTP для клиентов, фронтенд-аутентификация, видимость меню и защита API (миграция на `anyRequest().authenticated()` + декларативное гардирование контроллеров). Единственный незакрытый пункт — автоматизация API-тестов в FOR-03-01 (задача 17), которая не блокирует функциональность.

## Контекст

### Что уже реализовано (в FOR-01 и FOR-02)

| Компонент | Статус | Описание |
|-----------|--------|----------|
| ABAC-сущности | ✅ FOR-02-03 | `ResourceEntity`, `OperationEntity`, `RoleEntity`, `RoleResourceEntity` — структура "роль × ресурс × операции" |
| ABAC seed | ✅ FOR-02-04 | Системные роли (ADMIN, MANAGER, FOREMAN, WORKER, FINANCIER, CLIENT) + мета-ресурсы (ROLES, OPERATIONS, RESOURCES) |
| UserEntity | ✅ FOR-02-05 | Пользователь с привязкой к одной роли (`role_id → roles`) |
| SecurityConfig (stub) | ✅ FOR-01-10 | `permitAll()` — заглушка, CSRF отключён, stateless session |
| CRUD-фреймворк | ✅ FOR-01 | AdminService / AdminController с хуками `addPermissionConditions` для фильтрации |
| DisplayPreferencesController | ✅ FOR-02-08 | Endpoint с ручной проверкой `SecurityContextHolder` (паттерн авторизации) |

### Что нужно реализовать

1. **Регистрация по приглашению** — админ создаёт пользователя (email = username), на почту приходит приглашение со ссылкой для установки пароля
2. **Аутентификация** — JWT (access + refresh tokens), login по email/password
3. **OTP для клиентов** — беспарольный вход через email-код, длинная сессия с обновлением
4. **Авторизация на уровне API** — Spring Security filter chain, проверка прав на уровне контроллера
5. **Project ownership** — связь пользователей с проектами, фильтрация данных по доступным проектам
6. **Permission evaluator** — runtime проверка "пользователь с ролью X имеет операцию Y на ресурсе Z"
7. **Фронтенд** — auth store, login page, route guards, видимость меню по правам
8. **Интеграция** — подключение security к существующим контроллерам, миграция с `permitAll()` на реальные правила

## Модель регистрации и входа

### Два флоу аутентификации

```
┌──────────────────────────────────────────────────────────────────────────┐
│  СОТРУДНИКИ (ADMIN, MANAGER, FOREMAN, WORKER, FINANCIER)                │
│                                                                          │
│  1. Админ создаёт пользователя (email, роль, имя)                       │
│  2. Система генерирует invite_token (TTL 72 часа)                       │
│  3. На email приходит приглашение (текст зависит от роли)               │
│  4. Пользователь переходит по ссылке /auth/set-password?token=...       │
│  5. Устанавливает пароль → аккаунт активен                              │
│  6. Дальнейший вход: email + password → JWT (access 30 мин / refresh 7д)│
├──────────────────────────────────────────────────────────────────────────┤
│  КЛИЕНТЫ (CLIENT)                                                        │
│                                                                          │
│  1. Админ/менеджер создаёт клиента (email, имя, проект)                 │
│  2. На email приходит приглашение с OTP-инструкцией                     │
│  3. Клиент входит через OTP: вводит email → получает код → вводит код   │
│  4. После верификации получает JWT (access 2 часа / refresh 30 дней)    │
│  5. Refresh token автоматически обновляет сессию                         │
│  6. Нет пароля — только OTP при каждом новом входе                      │
└──────────────────────────────────────────────────────────────────────────┘
```

### Принципы

- **Username = email** — единый идентификатор для входа
- **Нет самостоятельной регистрации** — все аккаунты создаются администратором
- **Invite-only** — сотрудники получают приглашение и устанавливают пароль
- **Клиенты — без пароля** — только OTP, но сессия долгая (30 дней refresh) для удобства

## Модель доступа

### Уровни контроля

```
┌─────────────────────────────────────────────────────────────┐
│                   УРОВЕНЬ 1: Аутентификация                 │
│              JWT access token в Authorization header         │
├─────────────────────────────────────────────────────────────┤
│                   УРОВЕНЬ 2: RBAC                           │
│     Роль пользователя → разрешённые операции на ресурсах    │
│     (role_resources + role_resource_operations)              │
├─────────────────────────────────────────────────────────────┤
│                   УРОВЕНЬ 3: Project Ownership              │
│     Пользователь видит только данные своих проектов         │
│     (project_members → фильтрация на уровне SQL)           │
├─────────────────────────────────────────────────────────────┤
│                   УРОВЕНЬ 4: Видимость меню                 │
│     Фронтенд скрывает недоступные разделы навигации         │
└─────────────────────────────────────────────────────────────┘
```

### Проектные роли (Project Membership)

Помимо глобальной роли (`UserEntity.role`), каждый пользователь может быть назначен на конкретные проекты:

```
project_members
├── user_id (FK → users)
├── project_id (BIGINT, без FK — таблица projects появится в FOR-06)
└── project_role_id (FK → roles)
```

**Правила:**
- **Проектная роль ссылается на существующую таблицу `roles`** (те же роли: ADMIN, MANAGER, FOREMAN, WORKER, FINANCIER, CLIENT) через `project_role_id` — отдельного enum OWNER/MEMBER/VIEWER нет.
- **ADMIN** видит все проекты без ограничений
- **MANAGER/FOREMAN/WORKER/FINANCIER** видят только проекты, где есть запись в `project_members`
- **CLIENT** видит только свои проекты (есть запись в `project_members`)
- Фильтрация применяется автоматически на уровне CRUD-фреймворка через `addPermissionConditions`

### Матрица доступа (из коммерческого предложения)

| Модуль | ADMIN | MANAGER | FOREMAN | WORKER | CLIENT |
|--------|-------|---------|---------|--------|--------|
| Проекты | CRUD | CRUD (свои) | R (свои) | R (свои) | — |
| Помещения | CRUD | CRUD (свои) | R (свои) | — | — |
| Смета | CRUD | CRUD (свои) | R (свои) | — | R (свои) |
| Склад | CRUD | CRUD (свои) | CRUD (свои) | — | — |
| Финансы | CRUD | CRUD (свои) | R (свои) | — | R (свои) |
| Доставки | CRUD | CRUD (свои) | CRUD (свои) | — | R (свои) |
| Наряд-заказы | CRUD | R | — | R (свои) | — |
| Gantt | CRUD | R (свои) | R (свои) | R (свои) | R (свои) |
| Пользователи | CRUD | — | — | — | — |
| Роли/Доступы | CRUD | — | — | — | — |

*"(свои)" = фильтрация по project_members*

## Стадии работы (дочерние спеки)

Спеки выстроены в порядке зависимостей. Каждая следующая опирается на совокупность предыдущих.

| # | Спека | Описание | Зависит от | Статус |
|---|-------|----------|------------|--------|
| 01 | FOR-03-01-jwt-auth | JWT аутентификация: login endpoint, access/refresh tokens, Spring Security filter chain, password hashing (bcrypt), token refresh endpoint | — | 🟨 Функционал завершён (осталась автоматизация API-тестов) |
| 02 | FOR-03-02-user-invitation | Регистрация по приглашению: админ создаёт пользователя → invite email (зависит от роли) → страница установки пароля по токену | 01 | ✅ Завершено |
| 03 | FOR-03-03-permission-evaluator | Permission evaluator: загрузка прав из БД по роли, аннотация `@RequiresPermission(resource, operation)`, интеграция с SecurityContext | 01 | ✅ Завершено |
| 04 | FOR-03-04-project-ownership | Project membership: таблица `project_members`, сервис назначения, автоматическая фильтрация в CRUD-фреймворке через `addPermissionConditions` | 03 | ✅ Завершено |
| 04a | FOR-03-04a-project-actions-validation | Валидация проектных действий (project-scoped actions) поверх project ownership | 04 | ✅ Завершено |
| 05 | FOR-03-05-otp-client-auth | OTP аутентификация для клиентов: генерация 6-значного кода, отправка по email, верификация, выдача долгоживущего JWT (access 2ч / refresh 30д) | 01 | ✅ Завершено |
| 06 | FOR-03-06-frontend-auth | Фронтенд: auth store (Zustand), login page, set-password page, token storage, API client (attach JWT + auto-refresh), logout, protected routes | 01, 02 | ✅ Завершено |
| 07 | FOR-03-07-menu-visibility | Фронтенд: загрузка прав текущего пользователя, route guards по ресурсу/операции, скрытие пунктов меню без доступа, redirect на /403 | 06, 03 | ✅ Завершено |
| 08 | FOR-03-08-api-protection | Миграция существующих контроллеров: `anyRequest().authenticated()` + декларативное `@PermissionResource`/`@PermissionOperation`-гардирование через `PermissionResolver`, startup-валидация полноты аннотаций, seed ресурса `USERS`, интеграционные тесты | 03, 04 | ✅ Завершено |

### Граф зависимостей

```
01-jwt-auth ──────┬──── 02-user-invitation
                  │
                  ├──── 03-permission-evaluator ──── 04-project-ownership
                  │            │                            │
                  │            ▼                            │
                  │     07-menu-visibility                  │
                  │            ▲                            ▼
                  │            │                    08-api-protection
                  ├──── 06-frontend-auth
                  │            ▲
                  │            │
                  │     02-user-invitation
                  │
                  └──── 05-otp-client-auth
```

### Детализация каждой спеки

#### FOR-03-01-jwt-auth — JWT аутентификация

**Бекенд:**
- `POST /api/auth/login` — email + password → access token (30 мин) + refresh token (7 дней)
- `POST /api/auth/refresh` — refresh token → новый access token (+ новый refresh token, rotation)
- `POST /api/auth/logout` — инвалидация refresh token
- `GET /api/auth/me` — возвращает текущего пользователя + роль + permissions
- Password hashing: bcrypt cost factor 12 (добавить поле `password_hash` в `users`)
- `JwtTokenProvider` — генерация/валидация JWT (claims: userId, role, email)
- `JwtAuthenticationFilter` — извлечение токена из `Authorization: Bearer ...`, установка `SecurityContext`
- Миграция: добавить `password_hash VARCHAR(255)` и `status VARCHAR(20) DEFAULT 'INVITED'` в таблицу `users`
- Статусы пользователя: `INVITED` (ещё не установил пароль), `ACTIVE` (работает), `DEACTIVATED`
- Seed: admin user с дефолтным паролем, status = ACTIVE
- Таблица `refresh_tokens` (id, token, user_id, expires_at, revoked, created_date)

**Ключевые решения:**
- Access token хранит минимум: `sub=userId`, `role=roleCode`, `email`
- Refresh token — отдельная таблица (не в JWT) для возможности отзыва
- Refresh token rotation: при использовании старый отзывается, выдаётся новый
- Stateless: нет сессий, нет cookies — чистый Bearer token
- Login запрещён для пользователей со статусом `INVITED` (нужно сначала установить пароль)

---

#### FOR-03-02-user-invitation — Регистрация по приглашению

**Бекенд:**
- Таблица `invite_tokens` (id, user_id, token UUID, expires_at, used, created_date)
- При создании пользователя через админку (POST /api/users):
  1. Создаётся UserEntity со статусом `INVITED`, `password_hash = null`
  2. Генерируется `invite_token` (UUID, TTL 72 часа)
  3. Отправляется email с приглашением (текст зависит от роли)
- `POST /api/auth/set-password` — принимает `{ token, password }`:
  1. Валидирует токен (не истёк, не использован)
  2. Хеширует пароль (bcrypt)
  3. Ставит `user.status = ACTIVE`, `user.password_hash = hash`
  4. Помечает токен как `used = true`
  5. Возвращает JWT tokens (пользователь сразу залогинен)
- `POST /api/auth/resend-invite` — перегенерирует токен и отправляет повторное приглашение (доступно админу)

**Email шаблоны (Thymeleaf):**
- Сотрудник (MANAGER/FOREMAN/WORKER/FINANCIER): "Вас добавили в систему Foremen как {роль}. Перейдите по ссылке для установки пароля."
- Клиент (CLIENT): "Вам предоставлен доступ к порталу проекта. Для входа используйте код, который придёт на этот email."

**Фронтенд:**
- Страница `/auth/set-password?token=...` — форма ввода нового пароля (+ подтверждение)
- Валидация: минимум 8 символов
- После успешной установки — редирект на dashboard

---

#### FOR-03-03-permission-evaluator — Проверка прав

**Бекенд:**
- `ForemenPermissionEvaluator` — загрузка `role_resources` + `role_resource_operations` для роли текущего пользователя
- Кеширование прав в Caffeine (key = roleId, TTL = 5 мин)
- Аннотация `@RequiresPermission(resource = "PROJECTS", operation = "CREATE")`
- AOP aspect или `HandlerInterceptor`, проверяющий аннотацию перед выполнением метода контроллера
- ADMIN bypass — роль с кодом ADMIN не проверяется через матрицу (всегда разрешено)
- При отсутствии прав — `ForemenApiException(403, "error.access.denied")`

---

#### FOR-03-04-project-ownership — Привязка к проектам

Большинство сущностей системы привязаны к проекту: комнаты, работы, прейскурант, чеки, фин. документы, медиафайлы, отчёты, доставки, наряд-заказы и т.д. Нужен универсальный механизм фильтрации по доступным проектам, работающий на уровне CRUD-фреймворка.

**Принцип работы (по аналогии с `getEntityParamName` в tickets):**

```
┌─────────────────────────────────────────────────────────────────────────┐
│  1. При аутентификации (или при первом запросе) загружаем список        │
│     доступных projectId для текущего пользователя                       │
│                                                                         │
│  2. Кешируем в Caffeine: key = userId → value = Set<Long> projectIds   │
│     (TTL 5 мин, инвалидация при assign/remove member)                  │
│                                                                         │
│  3. Каждый сервис проектной сущности override-ит addRequiredQuery()    │
│     → возвращает Specification: WHERE entity.project.id IN (:ids)      │
│                                                                         │
│  4. ReadOnlyAdminService.buildFinalSpecification() автоматически        │
│     добавляет этот фильтр к каждому find/findExtended/getCount         │
│                                                                         │
│  5. ADMIN bypass: addRequiredQuery() возвращает null → фильтр не       │
│     применяется, видны все проекты                                      │
└─────────────────────────────────────────────────────────────────────────┘
```

**Бекенд:**

*Миграция:*
- Создать таблицу `project_members` (id, user_id FK→users, project_id BIGINT без FK, project_role_id FK→roles, created_date)
- project_role_id: FK на существующую таблицу `roles` (переиспользуем сиды ADMIN/MANAGER/FOREMAN/WORKER/FINANCIER/CLIENT из FOR-02-04; отдельного enum OWNER/MEMBER/VIEWER нет)
- Unique constraint: (user_id, project_id)

*Сущности и DAO:*
- `ProjectMemberEntity`, `ProjectMemberDao`
- `ProjectMemberService` — assign/remove/list members, инвалидация кеша при изменениях

*Кеширование доступных проектов:*
- `ProjectAccessCache` — Caffeine cache: `userId → Set<Long> projectIds`
- TTL: 5 минут
- Инвалидация: при assign/remove member, при изменении роли пользователя
- Загрузка: `SELECT project_id FROM project_members WHERE user_id = ?`
- Для ADMIN: возвращает пустой Optional (означает "доступ ко всем")

*Интерфейс для проектных сервисов:*
- Маркерный интерфейс или базовый класс `ProjectScopedService`
- Определяет как добраться до `project_id` в конкретной сущности (поле может называться `project.id`, `projectId`, или быть через связь `room.project.id`)
- Метод `getProjectIdPath(): String` — JPA-path до поля project_id в entity (например `"project.id"` или `"room.project.id"`)

*Интеграция с CRUD-фреймворком (ключевой момент):*
- Каждый проектный сервис override-ит `addRequiredQuery()` из `ReadOnlyAdminService`:

```java
@Override
default Specification<DaoModel> addRequiredQuery() {
    if (isCallerAdmin()) return null; // bypass

    Set<Long> allowedProjectIds = projectAccessCache.getProjectIds(currentUserId());
    if (allowedProjectIds.isEmpty()) {
        // Нет доступных проектов → пустой результат
        return (root, query, cb) -> cb.disjunction();
    }
    String path = getProjectIdPath(); // e.g. "project.id"
    return (root, query, cb) -> root.get(path).in(allowedProjectIds);
}
```

- Это автоматически фильтрует ВСЕ запросы: find, findExtended, getCount — через `buildFinalSpecification()`
- Сущности без привязки к проекту (users, roles, resources) НЕ override-ят этот метод → работают без фильтра

*Какие сущности будут проектными:*

| Сущность | Path до project_id | Модуль |
|----------|-------------------|--------|
| Project | `id` (сам проект) | FOR-06 |
| Room | `project.id` | FOR-06 |
| EstimateItem | `estimate.project.id` | FOR-06 |
| MaterialPurchase | `project.id` | FOR-06 |
| DeliveryItem | `project.id` | FOR-06 |
| WorkOrder | `project.id` | FOR-06 |
| GanttPhase | `project.id` | FOR-06 |
| FinancialDocument | `project.id` | FOR-06 |
| ProjectMember | `projectId` | FOR-03-04 |

*ADMIN bypass:*
- Роль ADMIN → `addRequiredQuery()` возвращает `null` → спецификация не добавляется → видны все данные

**Примечание:** таблицы `projects` ещё нет (она будет в FOR-06). Здесь создаём инфраструктуру: `project_members`, кеш, базовый `ProjectScopedService`. Конкретные сервисы подключат фильтрацию при реализации своих сущностей.

---

#### FOR-03-05-otp-client-auth — OTP для клиентов

**Бекенд:**
- `POST /api/auth/otp/request` — email → генерация 6-значного кода, отправка через SMTP
- `POST /api/auth/otp/verify` — email + code → JWT tokens (access 2 часа + refresh 30 дней)
- Таблица `otp_tokens` (id, email, code, expires_at, used, attempts, created_date)
- TTL кода: 15 минут, max 3 попытки ввода, rate limiting (5 запросов / час на email)
- Шаблон email: Thymeleaf (простой HTML с кодом)
- Spring Mail конфигурация (SMTP credentials в env vars)
- Клиент НЕ устанавливает пароль — только OTP при каждом новом входе

**Длинная сессия клиента:**
- Access token: 2 часа (vs 30 мин у сотрудников)
- Refresh token: 30 дней (vs 7 дней у сотрудников)
- Refresh token rotation работает так же — при обновлении выдаётся новый
- Пока клиент регулярно заходит (хотя бы раз в 30 дней), сессия живёт бесконечно

---

#### FOR-03-06-frontend-auth — Фронтенд аутентификация

**Фронтенд:**
- `src/stores/auth-store.ts` — Zustand: user, tokens, isAuthenticated, login, logout, refreshToken
- `src/features/auth/LoginPage.tsx` — форма email + password, вызов `/api/auth/login`
- `src/features/auth/SetPasswordPage.tsx` — форма установки пароля по invite-токену
- `src/features/auth/OtpLoginPage.tsx` — форма для клиентов: ввод email → ввод кода
- `src/lib/api-client.ts` — fetch wrapper с автоматическим attach `Authorization: Bearer` и auto-refresh при 401
- Token storage: `localStorage` (access + refresh tokens)
- Redirect на `/login` при отсутствии токена
- Routes без авторизации: `/login`, `/auth/set-password`, `/auth/otp`

---

#### FOR-03-07-menu-visibility — Видимость меню и действий

**Фронтенд:**
- При успешном логине загружаются permissions из `/api/auth/me`
- `src/hooks/usePermission.ts` — хук `hasPermission(resource, operation): boolean`
- `src/components/ProtectedRoute.tsx` — оборачивает route, проверяет permission, redirect на /403
- Обновление `NAV_CONFIG` — каждый пункт меню получает `requiredPermission: { resource, operation }`
- Sidebar/BottomNav фильтруют пункты через `hasPermission`
- Страница 403 (Forbidden)

**Видимость действий в таблицах (CRUD-операции):**

Все CRUD-таблицы на фронтенде показывают/скрывают кнопки действий на основе permissions текущего пользователя:

| Элемент UI | Условие видимости | Пример |
|------------|-------------------|--------|
| Кнопка "Добавить" (над таблицей) | `hasPermission(resource, 'CREATE')` | "Создать пользователя" видна только если есть CREATE на USERS |
| Кнопка "✏️ Редактировать" (в строке) | `hasPermission(resource, 'UPDATE')` | Иконка карандаша в каждой строке таблицы |
| Кнопка "🗑️ Удалить" (в строке) | `hasPermission(resource, 'DELETE')` | Иконка корзины / деактивации в строке |
| Кнопка "📋 Аудит" (в строке) | `hasPermission('AUDIT', 'READ')` | Кнопка просмотра аудит-лога записи |

**Реализация:**
- Компонент `DataTable` принимает пропс `permissions: { resource: string }` (или берёт из контекста страницы)
- Кнопки действий рендерятся условно через `hasPermission`
- Если у пользователя нет ни UPDATE, ни DELETE, ни AUDIT READ — колонка "Действия" скрывается полностью
- Кнопка "Добавить" — отдельный компонент над таблицей, не рендерится если нет CREATE
- Аудит — отдельный ресурс (`AUDIT`), т.к. доступ к аудиту не привязан к конкретной сущности, а настраивается глобально

---

#### FOR-03-08-api-protection — Защита API

**Бекенд:**
- Замена `SecurityConfig.permitAll()` на правила:
  - `/api/auth/**` — permitAll
  - `/api/**` — authenticated
- Добавление `@RequiresPermission` на все существующие контроллеры:
  - `ResourceController` — READ on RESOURCES
  - `OperationController` — READ on OPERATIONS
  - `RoleController` — CRUD on ROLES
  - `DisplayPreferencesController` — authenticated (без resource-level check)
- Интеграционные тесты: запросы без токена → 401, запросы с токеном без прав → 403, запросы с правами → 200

## Адаптация относительно исходного проекта (tickets)

| Аспект | tickets (исходный) | Foremen (целевой) |
|--------|-------------------|-------------------|
| Auth | Custom session-based | JWT stateless (access + refresh) |
| Registration | Self-registration | Invite-only (админ создаёт → email → set password) |
| Client auth | N/A | OTP по email (без пароля, длинная сессия) |
| Project scoping | N/A | `project_members` + SQL filter |
| Permission model | `@AccessControlOperation` | `@RequiresPermission` + `ForemenPermissionEvaluator` |
| Permission storage | Hardcoded in annotations | БД (role_resources, configurable via admin UI) |
| Caching | N/A | Caffeine (permissions per role) |
| Frontend guards | N/A | `usePermission` hook + ProtectedRoute |

## Нефункциональные требования

### Сотрудники (ADMIN, MANAGER, FOREMAN, WORKER, FINANCIER)
- JWT access token TTL: 30 минут
- JWT refresh token TTL: 7 дней
- Invite token TTL: 72 часа
- Password: bcrypt с cost factor 12
- Минимальная длина пароля: 8 символов

### Клиенты (CLIENT)
- JWT access token TTL: 2 часа
- JWT refresh token TTL: 30 дней
- OTP код TTL: 15 минут
- Max попыток ввода OTP: 3
- Rate limiting OTP: 5 запросов / час на email

### Общее
- Permission cache TTL: 5 минут (Caffeine)
- Refresh token rotation: при каждом использовании старый отзывается
- Все ошибки авторизации через единый `ForemenApiException` → `ForemenControllerAdvice`
- Email отправка: Spring Mail + Thymeleaf шаблоны
