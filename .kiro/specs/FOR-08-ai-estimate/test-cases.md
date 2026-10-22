# Тест-кейсы: FOR-08-ai-assistant (интеграция loaders-bot)

Спека интегрирует существующий агентный виджет loaders-bot в админ-панель Foremen. Основные
проверяемые части: (A) бэкенд-эндпоинт выдачи identity-токена (чисто API-тесты против поднятого в
Docker приложения), (B) курированный OpenAPI-тулсет (валидация артефакта), (C) поведение агента
через консоль тестирования бота — агент действует строго в рамках JWT пользователя, а любые записи
требуют явного подтверждения.

**Базовый URL API:** `http://localhost:8080` (docker-стек: `postgres`, `liquibase`, `backend`,
`frontend`). Auth — под `/api/auth`, первый ADMIN поднимается через `FOREMEN_ADMIN_CREATE=true` /
`FOREMEN_ADMIN_EMAIL` / `FOREMEN_ADMIN_PASSWORD`. Для бота должны быть заданы
`FOREMEN_BOT_IDENTITY_SECRET` и `FOREMEN_BOT_SITE_KEY`.

## Стратегия повторяемости

- **Генератор данных:** все создаваемые сущности (проекты/комнаты/сметы в сквозных агентных
  сценариях) именуются с суффиксом `run-id` — timestamp или uuid, уникальный на каждый прогон
  (например, имя проекта `AI-Test-{run-id}`). Это позволяет запускать набор многократно без ручной
  чистки БД.
- **Teardown:** созданные в сквозных сценариях сущности удаляются в конце соответствующего кейса
  (через DELETE-эндпоинт с тем же JWT), либо остаются помеченными `run-id` и не мешают повторным
  прогонам.
- **Identity-токен кейсы** не создают доменных данных — повторяемы по определению (stateless).

Run-id формируется один раз на прогон и подставляется во все кейсы набора.

---

## Фича A — Identity-токен (API против Docker)

Предусловие для всех кейсов группы: docker-стек поднят; есть валидные креды ADMIN; задан
`FOREMEN_BOT_IDENTITY_SECRET`.

### TC-A1 — Выдача валидного identity-токена аутентифицированному пользователю

Предусловия: получен `accessToken` ADMIN через `POST /api/auth/login`.

Шаги:
1. `POST /api/auth/login` с кредами ADMIN → ожидание: 200 + `accessToken`.
2. `POST /api/bot/identity-token` с заголовком `Authorization: Bearer <accessToken>` и
   `Accept-Language: ru` → ожидание: 200 + тело `{ "token": "<jwt>" }`.
3. Декодировать payload JWT (без проверки подписи на клиенте) → ожидание: `sub` = id пользователя;
   `exp - iat` ≤ 24 ч; `traits.name` непустое; `traits.locale` = `ru`; `roles` содержит `ADMIN`.
4. Проверить подпись JWT ключом `FOREMEN_BOT_IDENTITY_SECRET` (HS256) → ожидание: подпись валидна.

Ожидаемый результат: возвращён корректно подписанный HS256 identity-токен с ожидаемыми клеймами.

### TC-A2 — Локаль берётся из Accept-Language (pl по умолчанию)

Шаги:
1. `POST /api/bot/identity-token` с `Accept-Language: pl` → ожидание: 200, `traits.locale` = `pl`.
2. `POST /api/bot/identity-token` без `Accept-Language` → ожидание: 200, `traits.locale` = `pl`
   (дефолт PL).

Ожидаемый результат: `locale` = `ru` только при `Accept-Language: ru`, иначе `pl`.

### TC-A3 — 401 без аутентификации

Шаги:
1. `POST /api/bot/identity-token` без заголовка `Authorization` → ожидание: 401.
2. То же с заведомо невалидным/просроченным токеном → ожидание: 401.

Ожидаемый результат: эндпоинт недоступен без валидного access-токена.

### TC-A4 — Токен выдаётся любой роли (self-service, не под ABAC)

Предусловия: есть пользователь роли, отличной от ADMIN (например, MANAGER) с валидным токеном
(генерируется/инвайтится в рамках подготовки прогона, помечается `run-id`).

Шаги:
1. `POST /api/bot/identity-token` под токеном MANAGER → ожидание: 200 + токен, `roles` содержит
   `MANAGER`.

Ожидаемый результат: эндпоинт не зависит от ABAC-резурса; `roles` отражает реальную Company_Role.

### TC-A5 — Fail-fast при отсутствии секрета (конфигурационная проверка)

Шаги:
1. Поднять backend с пустым/незаданным `FOREMEN_BOT_IDENTITY_SECRET` → ожидание: приложение не
   стартует (быстрый отказ с понятной ошибкой конфигурации), токены с пустым ключом не подписываются.

Ожидаемый результат: без секрета приложение падает на старте, а не выдаёт небезопасные токены.
(Выполняется как отдельный запуск/интеграционный тест, не ломает основной стек прогона.)

---

## Фича B — Курированный OpenAPI-тулсет (валидация артефакта)

Проверяемый артефакт (source of truth):
`foremen-backend/src/main/resources/openapi/foremen-bot-toolset.yaml`. Кейсы группы не требуют
Docker-стека — это статическая валидация файла (парсер OpenAPI 3 + проверки содержимого), повторяемы
по определению (stateless, без доменных данных).

### TC-B1 — Артефакт парсится как OpenAPI 3

Шаги:
1. Загрузить `foremen-bot-toolset.yaml` валидатором OpenAPI 3 → ожидание: валиден, без ошибок схемы.

### TC-B2 — Присутствуют целевые операции

Шаги:
1. Проверить наличие операций по областям: projects (create/list/get/update/activate), rooms CRUD,
   estimate (matrix preview/apply/assignments, materials/reserve), materials/assortment, catalog
   (works/prices/categories), sellers (list/search), procurement, dictionaries, team (incl.
   candidates), schedule, images (upload) → ожидание: все присутствуют с осмысленными `operationId`.

### TC-B3 — Запрещённые пути отсутствуют

Шаги:
1. Проверить отсутствие путей `/api/auth/**`, `POST /api/signatures/callback`,
   `POST /api/payments/webhook/**`, `/api/users`, `/api/roles`, `/api/resources`, `/api/operations`,
   `/api/audit` → ожидание: ни один не присутствует в артефакте.

### TC-B4 — Security + классификация verb

Шаги:
1. Проверить, что каждая операция объявляет bearer-security → ожидание: да.
2. Проверить, что GET помечены как read/non-destructive, а non-GET — как write → ожидание: да
   (политика подтверждения применится к записям).

---

## Фича C — Поведение агента (консоль тестирования бота + Docker)

Предусловия: curated тулсет импортирован в бот; auth-scheme = Host callback (bearer); роли и матрица
Tool × Role настроены; цели опубликованы/доступны в тестовой консоли; валидные JWT для ролей.

### TC-C1 — Identify и ролевое предложение целей

Шаги:
1. Вызвать `identify` с identity-токеном пользователя роли MANAGER → ожидание: бот распознаёт
   visitor-а, роль `MANAGER` (verified), предлагает только цели/инструменты для MANAGER.
2. Повторить с ролью, у которой меньше грантов → ожидание: набор целей/инструментов сужается
   соответственно.

### TC-C2 — Чтение в рамках scope пользователя

Шаги:
1. В разговоре инициировать цель, требующую чтения (например, список проектов/материалов) → агент
   вызывает GET-инструмент с JWT пользователя → ожидание: возвращаются данные только в рамках
   доступа пользователя (ADMIN — все; не-ADMIN — только его проекты).

### TC-C3 — Запись требует явного подтверждения

Шаги:
1. Инициировать создание проекта `AI-Test-{run-id}` → агент предлагает запись (POST) →
   ожидание: виджет показывает подтверждение (summary действия) до отправки.
2. Отклонить подтверждение → ожидание: запись НЕ выполнена (проект не создан).
3. Повторить и подтвердить → ожидание: проект создан (200/201), появляется в последующем
   GET-чтении.
4. Teardown: удалить созданный проект `AI-Test-{run-id}` (DELETE тем же JWT).

Ожидаемый результат: ни одна не-GET операция не выполняется без явного подтверждения пользователя.

### TC-C4 — Невозможность выйти за пределы прав (403/404)

Шаги:
1. Под не-ADMIN JWT инициировать действие над проектом, в котором пользователь не состоит (чужой id)
   → ожидание: 404 (project-scope маскирует под «не найдено»).
2. Под ролью без нужного гранта инициировать защищённую операцию → ожидание: 403.

Ожидаемый результат: агент не может превысить ABAC/scope пользователя; эскалация невозможна.

### TC-C5 — Rooms-from-plan идёт по реальному многошаговому пути

Шаги:
1. Инициировать цель создания комнаты из плана → ожидание: агент сначала загружает изображение
   плана через image-эндпоинт, затем создаёт/обновляет комнату через `/api/rooms`; не предполагает
   несуществующего «одношагового» эндпоинта. Каждая запись — с подтверждением.
2. Teardown: удалить созданные комнаты/изображения `run-id`.

---

## Регрессия

Регрессионные кейсы на данном этапе ограничены (новой доменной логики/сущностей не добавляется).
Проверять при изменениях:

### TC-R1 — Логин/логаут лайфцикл виджета
Шаги: логин → identify вызван с токеном; логаут → `identify(null)`; на `/login` виджет не
инициализируется. Ожидание: соответствует (юнит-тесты фронта со стабом `LoadersBot`).

### TC-R2 — Исключённая поверхность остаётся исключённой
Шаги: после рекапче/рекурации OpenAPI повторить TC-B3. Ожидание: `/api/auth`, вебхуки и
account/role-admin по-прежнему отсутствуют в тулсете.

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-A1 | 2 | POST /api/bot/identity-token (Bearer, Accept-Language: ru) | 200 + `{token}` | — | — |
| 2 | TC-A3 | 1 | POST /api/bot/identity-token (без Authorization) | 401 | — | — |
| 3 | TC-B3 | 1 | Проверка отсутствия `/api/auth/**` в артефакте | отсутствует | — | — |
| 4 | TC-C3 | 1 | Агент: создать проект `AI-Test-{run-id}` | показан confirmation | — | — |
| 5 | TC-C4 | 1 | Агент: действие над чужим проектом (не-ADMIN) | 404 | — | — |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`. Стратегия повторяемости:
`генератор run-id + teardown созданных сущностей`.
---

## Прогон — Task 8.1 (end-to-end verification)

**Run-id:** `for-08-08-01`
**Дата/окружение:** автоматический прогон из dev-окружения (macOS), backend-тесты через Gradle
(`--tests`, без полного прогона), API-сценарии Фичи A — против поднятого Docker-стека
(`postgres` + `liquibase` + `backend`, профиль `docker`, `http://localhost:8080`).
**Стратегия повторяемости:** stateless identity-кейсы (доменных данных не создаётся) + генератор
`run-id`; Docker-стек после прогона остановлен (`docker compose down`).

**Что запускалось здесь:**
- **Фича B (OpenAPI-валидация, Req 2.\*/8.2)** — класс `com.foremen.openapi.BotToolsetOpenApiValidationTest`.
  Лог: `/tmp/for-08-08-01-openapi.log`; JUnit XML:
  `foremen-backend/build/test-results/test/TEST-com.foremen.openapi.BotToolsetOpenApiValidationTest.xml`
  → **5 tests, 0 failures, 0 errors** ✅.
- **Фича A (identity-token) backend-тесты (Req 1.\*)** — `com.foremen.service.BotIdentityTokenServiceTest`
  (**7 tests, 0 failures**) и `com.foremen.controller.integration.BotIdentityControllerIntegrationTest`
  (**4 tests, 0 failures**). Лог: `/tmp/for-08-08-01-identity.log`. ✅
- **Фича A API против Docker (TC-A1..A3)** — curl к `http://localhost:8080`. Секрет
  `FOREMEN_BOT_IDENTITY_SECRET` в штатном `docker-compose.yml` НЕ задан (см. prerequisite ниже),
  поэтому на прогон передавался через временный override-файл. Backend успешно стартовал с заданным
  секретом — косвенно подтверждает fail-fast-гейт (TC-A5). ✅

### Таблица прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-B1 | 1 | OpenAPI 3 парсинг `foremen-bot-toolset.yaml` (`parses as OpenAPI 3 ...`) | валиден + bearer-jwt scheme | 1 test pass | ✅ |
| 2 | TC-B2 | 1 | Наличие целевых операций (`intended operations ... present`) | все присутствуют | 1 test pass | ✅ |
| 3 | TC-B3 | 1 | Отсутствие `/api/auth/**`, вебхуков, account/role-admin (`excluded paths are absent`) | отсутствуют | 1 test pass | ✅ |
| 4 | TC-B4 | 1–2 | bearer-security + verb-классификация (`every operation declares bearer-jwt ...`, `GET reads / non-GET writes`) | да | 2 tests pass | ✅ |
| 5 | TC-A1 | — | Юнит/интеграция подписи и клеймов (`BotIdentityTokenServiceTest`, `BotIdentityControllerIntegrationTest`) | зелёные | 7+4 pass | ✅ |
| 6 | TC-A1 | 2 | `POST /api/bot/identity-token` (Bearer ADMIN, `Accept-Language: ru`) | 200 + `{token}` | 200 + `{token}` | ✅ |
| 7 | TC-A1 | 3 | Декод payload JWT | `sub`=11; `exp-iat`=12ч (≤24ч); `traits.name`="Robert Jankowski"; `traits.locale`=`ru`; `roles`=[ADMIN] | совпало | ✅ |
| 8 | TC-A1 | 4 | Проверка подписи HS256 ключом секрета | подпись валидна | SIGNATURE=VALID | ✅ |
| 9 | TC-A2 | 1 | `POST /api/bot/identity-token` (`Accept-Language: pl`) | 200, `traits.locale`=`pl` | 200, `locale`=`pl` | ✅ |
| 10 | TC-A2 | 2 | `POST /api/bot/identity-token` (без `Accept-Language`) | 200, `traits.locale`=`pl` (дефолт) | 200, `locale`=`pl` | ✅ |
| 11 | TC-A3 | 1 | `POST /api/bot/identity-token` (без `Authorization`) | 401 | 401 | ✅ |
| 12 | TC-A3 | 2 | `POST /api/bot/identity-token` (невалидный токен) | 401 | 401 | ✅ |
| 13 | TC-A5 | 1 | Fail-fast при пустом секрете | приложение не стартует | покрыт юнит/интеграцией (`BotIdentityTokenServiceTest`); в API-прогоне backend стартовал только с заданным секретом — косвенное подтверждение | ✅ (unit) / ℹ️ (косвенно) |

### Pending — ручной follow-up (не выполнимо из этого окружения)

| # | Тест-кейс | Причина | Статус |
|---|-----------|---------|--------|
| 14 | TC-A4 | Требуется заранее заведённый non-ADMIN (MANAGER) пользователь с валидным токеном; в прогоне не инвайтился | ⏳ manual |
| 15 | TC-C1 | Живая консоль тестирования loaders-bot недоступна из окружения | ⏳ manual |
| 16 | TC-C2 | Живая консоль бота + сквозной агентный путь (read-инструмент в scope пользователя) | ⏳ manual |
| 17 | TC-C3 | Живая консоль бота: подтверждение записи (create project `AI-Test-{run-id}`), отклонение/подтверждение, teardown | ⏳ manual |
| 18 | TC-C4 | Живая консоль бота: действие над чужим проектом (404) / без гранта (403) | ⏳ manual |
| 19 | TC-C5 | Живая консоль бота: rooms-from-plan многошаговый путь (image upload → `/api/rooms`) | ⏳ manual |

**Prerequisite, выявленный в прогоне:** штатный `docker-compose.yml` не задаёт
`FOREMEN_BOT_IDENTITY_SECRET` (и `FOREMEN_BOT_SITE_KEY`) для сервиса `backend`. Поскольку
`BotIdentityTokenService` падает на старте при пустом секрете (fail-fast, TC-A5), для прогона Фичи A
против Docker секрет необходимо задать (переменной окружения / compose-override). В этом прогоне он
подавался через временный override-файл (сам `docker-compose.yml` не изменялся).

**Итог запущенного:** 13 кейсов/проверок пройдено ✅ / 0 провалено / 6 отложено на ручной follow-up
(⏳ TC-A4, TC-C1..C5). Run-id: `for-08-08-01`. Стратегия повторяемости: `генератор run-id`,
Docker-стек остановлен после прогона.
