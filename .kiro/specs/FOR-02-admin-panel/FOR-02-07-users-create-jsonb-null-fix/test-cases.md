# Тест-кейсы: FOR-02-07 Users Create — jsonb null-bind fix

## Назначение и характер спеки

Спека **чисто бекендовая** (нет отдельных браузерных экранов — проверяется поведение API).
Поэтому тест-кейсы — это **API-тесты против работающего в Docker приложения** (не
Testcontainers/юнит-слой, а поднятый docker-стек). Каждый шаг — HTTP-запрос к API с проверкой
статуса, тела и заголовков. Результат прогона — **MD-репорт с таблицами** (шаг → запрос →
ожидание → факт → статус). Скриншоты не требуются.

Цель набора — подтвердить, что создание пользователя без поля `displayPreferences` больше не
падает с ошибкой `Unable to bind parameter ... null [Unknown Types value.]`, а поведение для
непустого `displayPreferences` (jsonb) сохранено.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase`
   (миграции), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`.
3. Auth-эндпоинты — под `/api/auth`. Управление пользователями — под `/api/users`.
4. Первый ADMIN поднимается через переменные бекенда: `FOREMEN_ADMIN_CREATE=true`,
   `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
5. Дождаться готовности `backend` (health/readiness) перед запуском кейсов.
6. Все запросы на создание/обновление пользователя выполняются с access-токеном ADMIN
   (`Authorization: Bearer <admin-token>`), полученным через `POST /api/auth/login`.

## Стратегия повторяемости

**Комбинированная — генератор + teardown:**

- **Генератор:** на каждый прогон формируется `run-id` (timestamp/uuid). Создаваемые
  пользователи получают уникальный email: `pref+{run-id}+{seq}@example.com`. Это исключает
  конфликт по уникальному индексу `uk_users_email` при многократных прогонах.
- **Teardown (обязательный шаг в конце набора):** деактивировать созданных пользователей через
  `DELETE /api/users/{id}` (soft-delete, `active=false`) — этого достаточно, чтобы набор был
  повторяемым без ручной чистки БД (email остаётся занятым, но каждый прогон использует новый
  `run-id`, поэтому коллизий нет).
- Системные данные (роли, ADMIN, seed) **не изменяются**. Все манипуляции — только над
  создаваемыми на прогон пользователями.

### Предусловие-хелпер: `roleId` не-ADMIN роли

Для создания пользователя нужен `roleId` существующей **не-ADMIN** роли (создание с ADMIN-ролью
запрещено бизнес-правилом). Получить список ролей: `GET /api/roles` (ADMIN-токен) и взять `id`
любой не-ADMIN роли (например, MANAGER/CLIENT). В шагах ниже он обозначен как `{roleId}`.

---

## Фича 1. Создание пользователя без displayPreferences (основной баг)

Проверяет, что запрос без поля `displayPreferences` успешно создаёт пользователя. По
пересмотренному фиксу (`@JdbcTypeCode(SqlTypes.JSON)`) `display_preferences` сохраняется как SQL
`NULL`. Для API это неотличимо от пустого объекта, т.к. чтение коалесцирует `NULL` к `{}`.

### TC-JSONB-01 — Создание без displayPreferences возвращает 201

**Предусловия:** ADMIN залогинен; известен `{roleId}` не-ADMIN роли; сформирован `run-id`.

**Шаги:**
1. `POST /api/auth/login` (ADMIN) → 200, сохранить access-токен ADMIN.
2. `GET /api/roles` (ADMIN-токен) → 200; выбрать `{roleId}` не-ADMIN роли.
3. `POST /api/users` (ADMIN-токен), тело:
   `{"name":"Alex","email":"pref+{run-id}+1@example.com","phone":"+48789736624","roleId":{roleId},"locale":"pl"}`
   (поле `displayPreferences` **отсутствует**).
   → **Ожидание: 201 Created**; в теле — созданный пользователь с `id`, `status=INVITED`,
   `locale=pl`. В БД `display_preferences` хранится как SQL `NULL`; в ответе API поле
   коалесцируется к `{}`.
4. `GET /api/users/{id}` (ADMIN-токен) → 200; `displayPreferences` = `{}` (пустой объект,
   т.к. API коалесцирует `NULL` к `{}`).

**Ожидаемый результат:** шаг 3 возвращает 201 (а НЕ 500 с "Unknown Types value"); пользователь
создан.

### TC-JSONB-02 — Создание с displayPreferences = null явно возвращает 201

**Предусловия:** как в TC-JSONB-01.

**Шаги:**
1. `POST /api/users` (ADMIN-токен), тело:
   `{"name":"Alex2","email":"pref+{run-id}+2@example.com","roleId":{roleId},"locale":"ru","displayPreferences":null}`
   → **Ожидание: 201 Created**.

**Ожидаемый результат:** явный `null` в теле обрабатывается так же, как отсутствие поля — 201.

---

## Фича 2. Создание/чтение пользователя с непустым displayPreferences (регресс 1.7)

Проверяет, что непустой `displayPreferences` по-прежнему сохраняется как валидный `jsonb` и
корректно читается обратно.

### TC-JSONB-03 — Создание с непустым displayPreferences возвращает 201 и сохраняет jsonb

**Предусловия:** как в TC-JSONB-01.

**Шаги:**
1. `POST /api/users` (ADMIN-токен), тело:
   `{"name":"Alex3","email":"pref+{run-id}+3@example.com","roleId":{roleId},"locale":"pl","displayPreferences":{"themeMode":"dark","colorScheme":"zinc","fontSize":"default"}}`
   → **Ожидание: 201 Created**; в теле `displayPreferences` = переданный объект.
2. `GET /api/users/{id}/display-preferences` (токен владельца или ADMIN, по правилам
   авторизации эндпоинта) → 200; тело — объект с `themeMode/colorScheme/fontSize`.

**Ожидаемый результат:** значение сохранено как `jsonb` и прочитано обратно без ошибки типа
(`jsonb` vs `character varying`).

### TC-JSONB-04 — Чтение display-preferences у пользователя без предпочтений возвращает {}

**Предусловия:** создан пользователь из TC-JSONB-01 (без `displayPreferences`; в БД хранится SQL `NULL`).

**Шаги:**
1. `GET /api/users/{id}/display-preferences` → 200; тело — пустой объект `{}`.

**Ожидаемый результат:** SQL `NULL` коалесцируется и читается как `{}` (поведение API
неизменно).

---

## Фича 3. Обновление пользователя и null-путь на update

Проверяет, что обновление пользователя, у которого `display_preferences` = NULL, без передачи
`displayPreferences` завершается успешно (тот же null-bind, но на UPDATE).

### TC-JSONB-05 — Update без displayPreferences у пользователя с NULL-предпочтениями

**Предусловия:** создан пользователь из TC-JSONB-01, известен его `{id}`.

**Шаги:**
1. `PUT /api/users/{id}` (ADMIN-токен), тело без `displayPreferences`, например:
   `{"name":"Alex Renamed","email":"pref+{run-id}+1@example.com","roleId":{roleId},"locale":"ru"}`
   → **Ожидание: 200 OK** (без ошибки "Unknown Types value").
2. `GET /api/users/{id}` → 200; `name` обновлён, `displayPreferences` = `{}` (в БД остаётся
   SQL `NULL`, API коалесцирует к `{}`).

**Ожидаемый результат:** обновление проходит; null-путь на UPDATE не падает.

---

## Регрессия

Отдельная перепроверка ранее закрытого поведения и критичных сквозных путей создания.

### TC-REG-01 — Валидный create проходит полный конвейер (валидации + инвайт)

**Шаги:**
1. `POST /api/users` с валидными `name/email/roleId/locale` (не-ADMIN роль) → 201; `status`
   создаётся как `INVITED`.
2. Убедиться, что инвайт-флоу отработал (issueInvite в afterCreate): проверить наличие
   invite-токена/письма доступными в окружении средствами (mailhog/БД-хелпер), если настроено.

**Ожидаемый результат:** создание завершает полный конвейер без регресса.

### TC-REG-02 — Создание с ADMIN-ролью запрещено (403)

**Шаги:**
1. `GET /api/roles` → найти `id` роли с кодом `ADMIN`.
2. `POST /api/users` c `roleId` = id ADMIN-роли → **403 Forbidden**, ключ
   `error.user.admin.role.forbidden`.

**Ожидаемый результат:** ADMIN-прохибиция сохранена.

### TC-REG-03 — Невалидный locale отклоняется (400)

**Шаги:**
1. `POST /api/users` c `locale` = `"en"` → **400 Bad Request**, ключ
   `error.user.locale.invalid`.

**Ожидаемый результат:** поддерживаются только `ru`/`pl` — поведение сохранено.

### Teardown (обязательно в конце прогона)

1. Для каждого созданного `{id}`: `DELETE /api/users/{id}` (ADMIN-токен) → 200/204 (soft-delete).

---

## Шаблон MD-репорта прогона

| # | Тест-кейс   | Шаг | Запрос/Действие                                  | Ожидание                     | Факт | Статус |
|---|-------------|-----|--------------------------------------------------|------------------------------|------|--------|
| 1 | TC-JSONB-01 | 3   | POST /api/users (без displayPreferences)         | 201 + user, prefs {} (БД NULL)|      |        |
| 2 | TC-JSONB-02 | 1   | POST /api/users (displayPreferences=null)        | 201                          |      |        |
| 3 | TC-JSONB-03 | 1   | POST /api/users (непустой displayPreferences)    | 201 + jsonb сохранён         |      |        |
| 4 | TC-JSONB-03 | 2   | GET /api/users/{id}/display-preferences          | 200 + объект                 |      |        |
| 5 | TC-JSONB-04 | 1   | GET /api/users/{id}/display-preferences (null)   | 200 + {}                     |      |        |
| 6 | TC-JSONB-05 | 1   | PUT /api/users/{id} (без displayPreferences)     | 200 (без Unknown Types)      |      |        |
| 7 | TC-REG-01   | 1   | POST /api/users (валидный)                       | 201 + INVITED                |      |        |
| 8 | TC-REG-02   | 2   | POST /api/users (ADMIN roleId)                   | 403 admin.role.forbidden     |      |        |
| 9 | TC-REG-03   | 1   | POST /api/users (locale=en)                      | 400 locale.invalid           |      |        |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`. Стратегия
повторяемости: `генератор (email pref+{run-id}+{seq}@example.com) + teardown (DELETE /api/users/{id})`.
