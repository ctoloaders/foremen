# Тест-кейсы: FOR-03-01 JWT Authentication

## Введение

Это **API-тест-кейсы**, исполняемые против **работающего в Docker приложения** (не Testcontainers/юнит-слой). Стек поднимается командой `docker compose up` в корне проекта: `postgres` (:5432), `liquibase` (миграции), `backend` (:8080, профиль `docker`), `frontend` (:3000). Каждый шаг — HTTP-запрос к API с проверкой статуса/тела/заголовков. Результат прогона — **MD-репорт с таблицами** (без скриншотов).

- **Базовый URL**: `http://localhost:8080`
- **Auth-эндпоинты**: под `/api/auth` (`/login`, `/refresh`, `/logout`, `/me`, `/password-reset/request`, `/password-reset/confirm`)
- **Первый ADMIN**: поднимается на старте контейнера через переменные `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD` (bcrypt-хеш формируется рантаймом, в БД/сиде нет plaintext).
- **Локали ошибок**: PL (база `messages.properties`) и RU (`messages_ru.properties`); язык выбирается заголовком `Accept-Language`.

### Стратегия повторяемости (глобально)

Прогон должен быть запускаемым многократно без ручной чистки БД:

1. **Генератор данных**: на каждый прогон формируется `run-id` (timestamp или uuid). Все создаваемые сотрудники используют email вида `test+{run-id}+{seq}@example.com`, где `seq` — порядковый номер внутри прогона. Это исключает конфликты уникальности email между прогонами.
2. **Teardown**: в конце каждого набора кейсов создаваемые пользователи деактивируются (PATCH `active=false` / статус `DEACTIVATED`) или удаляются через user-management API; выданные refresh-токены отзываются через `/logout`.

Каждая группа фич ниже дополнительно указывает свою локальную стратегию (Setup / Teardown / генератор).

### Допущения о тестовых пользователях (важно для предусловий)

- Обычные пользователи создаются через user-management API: `POST /api/users`. Роль ADMIN через этот путь **запрещена** (см. группу «Запрет роли ADMIN»), поэтому сотрудников-тестировщиков создаём с ролями `MANAGER` / `FOREMAN` / и т.п.
- **Установка пароля для INVITED-пользователей вне области FOR-03-01** (это FOR-03-02, invite flow). Поэтому пользователь, созданный через `POST /api/users`, стартует в статусе `INVITED` и **не может залогиниться** до активации.
- Чтобы получить **ACTIVE-пользователя с известным паролем** (нужен для позитивных сценариев логина/refresh/logout/`/me`/reset), используется один из документированных путей:
  - **ADMIN, поднятый бутстрапом** (`FOREMEN_ADMIN_EMAIL`/`FOREMEN_ADMIN_PASSWORD`) — гарантированно ACTIVE с известным паролем;
  - либо **DB-хелпер** (документированный SQL/сидовый скрипт для тестового окружения), который проставляет `status=ACTIVE` и `password_hash = bcrypt(<known>)` тестовому не-ADMIN пользователю.
  - В предусловиях каждого позитивного кейса явно указано, какой путь используется.
- ADMIN, поднятый бутстрапом, используется только для чтения/логина; его пароль в тестах не меняем (иначе нарушим инвариант единственного ADMIN на перезапусках).

---

## Группа 1. Login (`POST /api/auth/login`)

**Стратегия повторяемости группы**: ACTIVE-пользователь для позитивного кейса — бутстрап-ADMIN либо ACTIVE не-ADMIN через DB-хелпер (`test+{run-id}+login@example.com`, `password=Passw0rd!`). Негативные кейсы не создают сущностей. Teardown: если создавались пользователи через DB-хелпер — деактивировать в конце.

### TC-LOGIN-01 — Успешный вход ACTIVE-пользователя
**Предусловия**: существует ACTIVE-пользователь с известным паролем (бутстрап-ADMIN или ACTIVE не-ADMIN через DB-хелпер).
**Шаги**:
1. `POST /api/auth/login` с телом `{ "email": "<active-email>", "password": "<known-password>" }`.
   - Ожидание: HTTP 200; тело содержит непустые `accessToken`, `refreshToken` и числовой `expiresIn`.
2. Проверить, что `expiresIn == FOREMEN_JWT_ACCESS_TTL_MINUTES * 60` (при дефолте — `1800`).
   - Ожидание: значение совпадает.
**Ожидаемый итог**: получена валидная пара токенов, `expiresIn` соответствует конфигурации (Req 3.3, 3.9).

### TC-LOGIN-02 — Пустой email → 400 (до проверки учётных данных)
**Предусловия**: нет.
**Шаги**:
1. `POST /api/auth/login` с телом `{ "email": "", "password": "whatever" }`.
   - Ожидание: HTTP 400; ошибка валидации указывает на пустое/отсутствующее поле `email`; проверка учётных данных не выполняется.
2. Повторить с `email`, состоящим из пробелов (`"   "`).
   - Ожидание: HTTP 400.
**Ожидаемый итог**: blank email отклонён до верификации (Req 3.2).

### TC-LOGIN-03 — Пустой password → 400
**Предусловия**: нет.
**Шаги**:
1. `POST /api/auth/login` с телом `{ "email": "test+{run-id}@example.com", "password": "" }`.
   - Ожидание: HTTP 400; ошибка валидации на поле `password`.
**Ожидаемый итог**: blank password отклонён (Req 3.2).

### TC-LOGIN-04 — Неизвестный email → 401 invalid.credentials
**Предусловия**: пользователя с таким email не существует (используем `test+{run-id}+unknown@example.com`).
**Шаги**:
1. `POST /api/auth/login` с телом `{ "email": "test+{run-id}+unknown@example.com", "password": "Passw0rd!" }`.
   - Ожидание: HTTP 401; message code `error.auth.invalid.credentials`.
**Ожидаемый итог**: неизвестный email не раскрывается, единый код ошибки (Req 3.4).

### TC-LOGIN-05 — Неверный пароль → 401 invalid.credentials
**Предусловия**: существует ACTIVE-пользователь с известным паролем.
**Шаги**:
1. `POST /api/auth/login` с телом `{ "email": "<active-email>", "password": "wrong-password" }`.
   - Ожидание: HTTP 401; message code `error.auth.invalid.credentials` (тот же код, что и для неизвестного email).
**Ожидаемый итог**: неверный пароль отклонён тем же кодом, без утечки существования аккаунта (Req 3.5).

### TC-LOGIN-06 — INVITED-пользователь не может войти → 403 not.activated
**Предусловия**: создан через `POST /api/users` пользователь-сотрудник (роль MANAGER, email `test+{run-id}+invited@example.com`), статус `INVITED` (пароль не установлен, установка вне FOR-03-01).
**Шаги**:
1. `POST /api/auth/login` с email этого пользователя и любым паролем.
   - Ожидание: HTTP 403; message code `error.auth.account.not.activated`.
**Ожидаемый итог**: INVITED не может логиниться (Req 3.7).
**Teardown**: деактивировать/удалить созданного пользователя.

### TC-LOGIN-07 — DEACTIVATED-пользователь не может войти → 403 deactivated
**Предусловия**: существует пользователь в статусе `DEACTIVATED` (через DB-хелпер или деактивацию ранее ACTIVE тестового пользователя), email `test+{run-id}+deact@example.com`.
**Шаги**:
1. `POST /api/auth/login` с email этого пользователя и любым паролем.
   - Ожидание: HTTP 403; message code `error.auth.account.deactivated`.
**Ожидаемый итог**: DEACTIVATED не может логиниться (Req 3.8).

### TC-LOGIN-08 — expiresIn равен access-ttl × 60
**Предусловия**: известно значение `FOREMEN_JWT_ACCESS_TTL_MINUTES` контейнера backend (дефолт 30).
**Шаги**:
1. Выполнить успешный логин (как TC-LOGIN-01), считать `expiresIn`.
   - Ожидание: `expiresIn == access-ttl-minutes * 60`.
**Ожидаемый итог**: длительность доступа соответствует конфигурации (Req 3.9).

---

## Группа 2. Refresh + ротация (`POST /api/auth/refresh`)

**Стратегия повторяемости группы**: перед каждым кейсом получаем свежую пару токенов логином ACTIVE-пользователя (генератор run-id). Teardown: отозвать все актуальные refresh-токены пользователя через `/logout`.

### TC-REFRESH-01 — Валидный refresh → новые токены, старый отозван (ротация)
**Предусловия**: выполнен успешный логин, есть валидный `refreshToken` R1.
**Шаги**:
1. `POST /api/auth/refresh` с телом `{ "refreshToken": "<R1>" }`.
   - Ожидание: HTTP 200; тело содержит новый `accessToken` и новый `refreshToken` R2 (R2 ≠ R1); `expiresIn` присутствует.
2. Повторно `POST /api/auth/refresh` со **старым** R1.
   - Ожидание: HTTP 401; message code `error.auth.refresh.invalid` или `error.auth.refresh.revoked` (R1 помечен revoked ротацией).
**Ожидаемый итог**: ротация выдаёт новую пару и делает старый токен неиспользуемым (Req 7.3, 7.7).

### TC-REFRESH-02 — Повторное использование ротированного токена → 401
**Предусловия**: выполнен TC-REFRESH-01 шаг 1 (R1 ротирован в R2).
**Шаги**:
1. `POST /api/auth/refresh` с R1.
   - Ожидание: HTTP 401; code `error.auth.refresh.revoked` (или `error.auth.refresh.invalid`).
**Ожидаемый итог**: одноразовость refresh-токена подтверждена (Req 7.5, 7.7).

### TC-REFRESH-03 — Неизвестный refresh-токен → 401 invalid
**Предусловия**: сгенерировать случайную строку, не соответствующую ни одному persisted токену.
**Шаги**:
1. `POST /api/auth/refresh` с телом `{ "refreshToken": "nonexistent-{run-id}" }`.
   - Ожидание: HTTP 401; message code `error.auth.refresh.invalid`.
**Ожидаемый итог**: неизвестный токен отклонён (Req 7.4).

### TC-REFRESH-04 — Истёкший refresh-токен → 401 expired
**Предусловия**: persisted refresh-токен с `expires_at` в прошлом (создаётся DB-хелпером для тестового пользователя или конфигурацией короткого TTL в тестовом окружении).
**Шаги**:
1. `POST /api/auth/refresh` с этим токеном.
   - Ожидание: HTTP 401; message code `error.auth.refresh.expired`.
**Ожидаемый итог**: истёкший токен отклонён отдельным кодом (Req 7.6).

### TC-REFRESH-05 — Пустой refreshToken → 400
**Предусловия**: нет.
**Шаги**:
1. `POST /api/auth/refresh` с телом `{ "refreshToken": "" }`.
   - Ожидание: HTTP 400 (ошибка валидации `@NotBlank`).
**Ожидаемый итог**: blank поле отклонено до бизнес-логики (Req 7.2).

---

## Группа 3. Logout (`POST /api/auth/logout`)

**Стратегия повторяемости группы**: перед кейсом — свежий логин для получения refresh-токена. Идемпотентность позволяет повторный прогон без чистки.

### TC-LOGOUT-01 — Logout известного токена → 204, токен становится непригодным
**Предусловия**: выполнен логин, есть валидный `refreshToken` R.
**Шаги**:
1. `POST /api/auth/logout` с телом `{ "refreshToken": "<R>" }`.
   - Ожидание: HTTP 204 (без тела).
2. `POST /api/auth/refresh` с R.
   - Ожидание: HTTP 401; code `error.auth.refresh.revoked` (или `error.auth.refresh.invalid`).
**Ожидаемый итог**: logout отзывает токен, после него refresh невозможен (Req 8.2, 8.4).

### TC-LOGOUT-02 — Logout неизвестного токена → 204 (идемпотентность)
**Предусловия**: нет.
**Шаги**:
1. `POST /api/auth/logout` с телом `{ "refreshToken": "nonexistent-{run-id}" }`.
   - Ожидание: HTTP 204, без ошибки.
2. Повторить тот же запрос ещё раз.
   - Ожидание: HTTP 204.
**Ожидаемый итог**: logout идемпотентен для неизвестных токенов (Req 8.3).

---

## Группа 4. Текущий пользователь (`GET /api/auth/me`)

**Стратегия повторяемости группы**: используем access-токен, полученный логином ACTIVE-пользователя (генератор). Кейс не создаёт сущностей.

### TC-ME-01 — Валидный токен → 200 с идентичностью и правами
**Предусловия**: выполнен логин, есть валидный `accessToken`.
**Шаги**:
1. `GET /api/auth/me` с заголовком `Authorization: Bearer <accessToken>`.
   - Ожидание: HTTP 200; тело содержит `id`, `name`, `email`, `roleCode` и набор `permissions` (список `{ resource, operations }`), производный от связей роль-ресурс-операция.
**Ожидаемый итог**: возвращена корректная идентичность и производные права (Req 9.2, 9.4).

### TC-ME-02 — Без токена → 401
**Предусловия**: нет.
**Шаги**:
1. `GET /api/auth/me` без заголовка `Authorization`.
   - Ожидание: HTTP 401; тело в форме `ErrorResponse` с сообщением `error.auth.unauthorized` в локали запроса.
**Ожидаемый итог**: защищённый эндпоинт требует аутентификацию (Req 9.1, 9.3).

### TC-ME-03 — Невалидный/битый токен → 401
**Предусловия**: нет.
**Шаги**:
1. `GET /api/auth/me` с `Authorization: Bearer not.a.valid.token`.
   - Ожидание: HTTP 401.
2. `GET /api/auth/me` с заголовком без префикса `Bearer ` (например `Authorization: Token abc`).
   - Ожидание: HTTP 401 (контекст не аутентифицирован).
**Ожидаемый итог**: невалидный токен не даёт доступа (Req 9.3, 5.3, 5.4).

---

## Группа 5. Сброс пароля (`/api/auth/password-reset/*`)

**Стратегия повторяемости группы**: для позитивного пути нужен ACTIVE-пользователь (DB-хелпер, `test+{run-id}+reset@example.com`). Токен сброса читается из перехваченного письма (тестовый SMTP / mailhog) либо из БД (`password_reset_tokens`) в тестовом окружении. Teardown: деактивировать пользователя, отозвать его refresh-токены.

### TC-RESET-01 — Запрос сброса для ACTIVE → 200 (+ токен и письмо)
**Предусловия**: существует ACTIVE-пользователь.
**Шаги**:
1. `POST /api/auth/password-reset/request` с телом `{ "email": "<active-email>" }`.
   - Ожидание: HTTP 200; в тестовом окружении в `password_reset_tokens` появляется единичный неиспользованный токен с TTL ≈ 60 минут; отправлено письмо.
**Ожидаемый итог**: для ACTIVE выдан одноразовый токен и письмо (Req 13.2).

### TC-RESET-02 — Запрос сброса для неизвестного/не-ACTIVE → 200 без раскрытия
**Предусловия**: email не существует (`test+{run-id}+noone@example.com`) и отдельно — INVITED/DEACTIVATED пользователь.
**Шаги**:
1. `POST /api/auth/password-reset/request` с несуществующим email.
   - Ожидание: HTTP 200; токен не создан; письмо не отправлено.
2. Повторить для не-ACTIVE (INVITED) email.
   - Ожидание: HTTP 200; токен не создан; письмо не отправлено.
**Ожидаемый итог**: нет user enumeration (Req 13.3).

### TC-RESET-03 — Confirm с невалидным/использованным/истёкшим токеном → 400
**Предусловия**: нет валидного токена (используем произвольную строку); отдельно — уже использованный и истёкший токены (через БД-хелпер).
**Шаги**:
1. `POST /api/auth/password-reset/confirm` с телом `{ "token": "invalid-{run-id}", "newPassword": "Passw0rd!" }`.
   - Ожидание: HTTP 400; message code `error.auth.reset.token.invalid`.
2. Повторить с уже использованным токеном.
   - Ожидание: HTTP 400; `error.auth.reset.token.invalid`.
3. Повторить с истёкшим токеном.
   - Ожидание: HTTP 400; `error.auth.reset.token.invalid`.
**Ожидаемый итог**: недействительный токен отклонён (Req 13.6).

### TC-RESET-04 — Confirm с коротким паролем (<8) → 400
**Предусловия**: есть валидный (свежий) токен сброса.
**Шаги**:
1. `POST /api/auth/password-reset/confirm` с телом `{ "token": "<valid-token>", "newPassword": "short" }`.
   - Ожидание: HTTP 400 (ошибка валидации `@Size(min=8)`).
**Ожидаемый итог**: слабый пароль отклонён (Req 13.7).

### TC-RESET-05 — Успешный confirm отзывает существующие refresh-токены
**Предусловия**: ACTIVE-пользователь залогинен (есть refresh-токен R), запрошен сброс, получен валидный токен T.
**Шаги**:
1. `POST /api/auth/password-reset/confirm` с телом `{ "token": "<T>", "newPassword": "NewPassw0rd!" }`.
   - Ожидание: HTTP 200; пароль обновлён (bcrypt).
2. `POST /api/auth/refresh` со старым R.
   - Ожидание: HTTP 401 (`error.auth.refresh.revoked`/`invalid`) — все refresh-токены пользователя отозваны.
3. `POST /api/auth/login` с новым паролем `NewPassw0rd!`.
   - Ожидание: HTTP 200; выдана новая пара токенов.
**Ожидаемый итог**: успешный сброс меняет пароль и инвалидирует прежние сессии (Req 13.5, 13.8).

---

## Группа 6. Bootstrap ADMIN

**Стратегия повторяемости группы**: проверяется на уровне конфигурации старта контейнера, **не через API-create** (создание ADMIN через API запрещено). Кейс read-only, повторяем без чистки.

### TC-ADMIN-01 — Единственный ADMIN поднят бутстрапом и может логиниться
**Предусловия**: backend поднят с `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD` (проверяется в конфиге docker-стека, не создаётся тестом).
**Шаги**:
1. `POST /api/auth/login` с `FOREMEN_ADMIN_EMAIL` / `FOREMEN_ADMIN_PASSWORD`.
   - Ожидание: HTTP 200; выданы токены; последующий `GET /api/auth/me` возвращает `roleCode = "ADMIN"`.
**Ожидаемый итог**: единственный ADMIN существует и активен (Req 11.1, 11.4). Наличие ADMIN обеспечивается конфигом старта контейнера, а не API-создание.

---

## Группа 7. Запрет роли ADMIN через user-management API

**Стратегия повторяемости группы**: используется access-токен ADMIN (или любой авторизованный доступ к user-management согласно текущим правилам). Создаваемые не-ADMIN пользователи — по генератору; teardown их деактивирует.

### TC-NOADMIN-01 — Создание пользователя с ролью ADMIN → 403 forbidden
**Предусловия**: доступен эндпоинт `POST /api/users`.
**Шаги**:
1. `POST /api/users` с телом, задающим роль `ADMIN` (email `test+{run-id}+adm@example.com`).
   - Ожидание: HTTP 403; message code `error.user.admin.role.forbidden`; пользователь не создан.
**Ожидаемый итог**: создать ADMIN через API нельзя (Req 12.1).

### TC-NOADMIN-02 — Промоушен существующего пользователя в ADMIN → 403 forbidden
**Предусловия**: существует не-ADMIN пользователь (роль MANAGER, создан по генератору).
**Шаги**:
1. `PATCH`/`PUT /api/users/{id}` с изменением роли на `ADMIN`.
   - Ожидание: HTTP 403; message code `error.user.admin.role.forbidden`; изменение не сохранено.
**Ожидаемый итог**: повысить до ADMIN через API нельзя (Req 12.2).
**Teardown**: деактивировать созданного пользователя.

---

## Группа 8. Локализация ошибок (Accept-Language)

**Стратегия повторяемости группы**: негативный auth-запрос (например неизвестный email), кейс не создаёт сущностей.

### TC-I18N-01 — Сообщение auth-ошибки различается для PL и RU
**Предусловия**: нет.
**Шаги**:
1. `POST /api/auth/login` с неизвестным email и заголовком `Accept-Language: pl`.
   - Ожидание: HTTP 401; тело с message code `error.auth.invalid.credentials`; текст сообщения — на польском (из `messages.properties`).
2. Тот же запрос с `Accept-Language: ru`.
   - Ожидание: HTTP 401; тот же code; текст сообщения — на русском (из `messages_ru.properties`), отличается от польского.
**Ожидаемый итог**: сообщение разрешается по локали запроса, тексты для PL и RU различны (Req 15.1, 15.2).

---

## Регрессия

**Стратегия повторяемости раздела**: каждый сквозной кейс использует свежий логин по генератору run-id; teardown отзывает refresh-токены через `/logout`.

### REG-01 — Полный жизненный цикл сессии: login → refresh → logout → refresh-again
**Шаги**:
1. `POST /api/auth/login` (ACTIVE) → 200, пара токенов (A1, R1).
2. `POST /api/auth/refresh` с R1 → 200, новая пара (A2, R2); R1 отозван.
3. `POST /api/auth/logout` с R2 → 204.
4. `POST /api/auth/refresh` с R2 → 401 (`error.auth.refresh.revoked`/`invalid`).
**Ожидаемый итог**: ротация и отзыв работают на всём пути (Req 3.3, 7.3, 7.7, 8.2, 8.4).

### REG-02 — Публичность `/api/auth/**` и защита `/api/auth/me`
**Шаги**:
1. `POST /api/auth/login` без токена → доступен (не 401 из-за отсутствия аутентификации; результат зависит от учётных данных).
2. `GET /api/auth/me` без токена → 401.
3. `GET /api/auth/me` с валидным токеном → 200.
**Ожидаемый итог**: `/api/auth/**` открыт, а `/api/auth/me` требует аутентификацию (Req 6.1, 9.1, 9.3).

### REG-03 — Существующие не-auth эндпоинты остаются доступны (до FOR-03-08)
**Предусловия**: FOR-03-08 ещё не ужесточил `permitAll()`.
**Шаги**:
1. Обратиться к существующему не-auth эндпоинту (например, публичный справочный/health эндпоинт) без токена.
   - Ожидание: доступ сохраняется как до внедрения JWT (не появилось неожиданных 401 из-за фильтра).
**Ожидаемый итог**: внедрение JWT-фильтра не ломает ранее доступные пути (Req 6 — только `/api/auth/me` ужесточён).

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-LOGIN-01 | 1 | POST /api/auth/login (ACTIVE) | 200 + accessToken/refreshToken/expiresIn | | |
| 2 | TC-LOGIN-01 | 2 | Проверка expiresIn | == ttl*60 (1800) | | |
| 3 | TC-LOGIN-04 | 1 | POST /api/auth/login (unknown email) | 401 error.auth.invalid.credentials | | |
| 4 | TC-REFRESH-01 | 1 | POST /api/auth/refresh (R1) | 200 + новая пара, R1 отозван | | |
| 5 | TC-LOGOUT-01 | 1 | POST /api/auth/logout (R) | 204 | | |
| 6 | TC-ME-02 | 1 | GET /api/auth/me (без токена) | 401 error.auth.unauthorized | | |
| 7 | TC-RESET-03 | 1 | POST /api/auth/password-reset/confirm (invalid token) | 400 error.auth.reset.token.invalid | | |
| 8 | TC-NOADMIN-01 | 1 | POST /api/users (role=ADMIN) | 403 error.user.admin.role.forbidden | | |
| 9 | TC-I18N-01 | 1-2 | login (unknown) с Accept-Language pl/ru | 401, тексты различаются по локали | | |
| … | … | … | … | … | | |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`. Стратегия повторяемости: генератор `test+{run-id}+{seq}@example.com` + teardown (деактивация пользователей, `/logout` для refresh-токенов).
