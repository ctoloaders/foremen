# Тест-кейсы: FOR-03-02 User Invitation

## Введение

Это **API-тест-кейсы**, исполняемые против **работающего в Docker приложения** (не Testcontainers/юнит-слой). Стек поднимается командой `docker compose up` в корне проекта: `postgres` (:5432), `liquibase` (миграции), `backend` (:8080, профиль `docker`), `frontend` (:3000). Каждый шаг — HTTP-запрос к API с проверкой статуса/тела/заголовков. Результат прогона — **MD-репорт с таблицами** (без скриншотов).

Спека — вторая дочерняя в FOR-03 и строится поверх FOR-03-01 (JWT). Она покрывает invite-flow: администратор создаёт пользователя через user-management API, система выдаёт invite-токен (TTL 72 ч) и шлёт роль-зависимое письмо, приглашённый устанавливает пароль публичным эндпоинтом и сразу логинится; ADMIN может переотправить приглашение.

- **Базовый URL**: `http://localhost:8080`
- **Auth-эндпоинты FOR-03-02**: под `/api/auth` — `POST /set-password` (публичный), `POST /resend-invite` (только ROLE_ADMIN)
- **User-management**: `POST /api/users` (создание пользователя; всегда стартует в статусе `INVITED` с `passwordHash = null`; роль ADMIN через этот путь запрещена — 403 от FOR-03-01)
- **Первый ADMIN**: поднимается на старте контейнера через переменные `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD` (bcrypt-хеш формируется рантаймом, в БД/сиде нет plaintext). Используется как авторизованный вызывающий для `POST /api/users` и `POST /api/auth/resend-invite`.
- **Локали писем**: язык письма выбирается по полю `locale` пользователя (`PL`/`RU`, case-insensitive), fallback → `PL`. Локаль ошибок HTTP — по заголовку `foremen-language` (`PL`/`RU`), fallback → `PL`.
- **Конфигурация приглашений**: `foremen.mail.invite-base-url` (env `MAIL_INVITE_BASE_URL`, дефолт `http://localhost:3000/auth/set-password`), `foremen.invite.ttl-hours` (env `FOREMEN_INVITE_TTL_HOURS`, дефолт 72, диапазон 1..8760).

### Доступ к invite-токену в тестах

Установка пароля требует знания значения invite-токена, которое обычно уходит в письмо. В тестовом окружении токен получается одним из документированных путей (указывается в предусловиях кейса):

- **Перехват письма** тестовым SMTP (mailhog / встроенный перехватчик), откуда извлекается `token` из `Invite_Link` вида `{invite-base-url}?token={token}`;
- либо **чтение из БД** таблицы `invite_tokens` (`SELECT token FROM invite_tokens WHERE user_id = ? AND used = false`) в тестовом окружении.

### Стратегия повторяемости (глобально)

Прогон должен быть запускаемым многократно без ручной чистки БД:

1. **Генератор данных**: на каждый прогон формируется `run-id` (timestamp или uuid). Все создаваемые пользователи используют email вида `test+{run-id}+{seq}@example.com`, где `seq` — порядковый номер внутри прогона. Это исключает конфликты уникальности email между прогонами.
2. **Teardown**: в конце каждого набора кейсов создаваемые пользователи деактивируются (статус `DEACTIVATED`) или удаляются через user-management API; неиспользованные invite-токены помечаются `used = true` (или удаляются); выданные при auto-login refresh-токены отзываются через `/api/auth/logout`.

Каждая группа фич ниже дополнительно указывает свою локальную стратегию (Setup / Teardown / генератор).

### Допущения о ролях и статусах

- Сотрудники-тестировщики (`Employee_Role`) создаются с ролями `MANAGER` / `FOREMAN` / `WORKER` / `FINANCIER`. Клиент — роль `CLIENT`.
- Роль `ADMIN` через `POST /api/users` **запрещена** (403 до invite-логики), поэтому invite-flow наблюдает только CLIENT или employee-пользователей.
- Каждый созданный через `POST /api/users` пользователь стартует `INVITED`, `passwordHash = null` и получает ровно один invite-токен.
- Бутстрап-ADMIN используется только как авторизованный вызывающий; его учётные данные и статус в тестах не меняем.

---

## Группа 1. Выдача invite-токена при создании пользователя (`POST /api/users`)

**Покрывает**: Req 3.1, 3.7, 3.8.
**Стратегия повторяемости группы**: генератор `test+{run-id}+{seq}@example.com`; авторизованный вызывающий — бутстрап-ADMIN. Teardown: деактивировать созданных пользователей, пометить их invite-токены `used`.

### TC-INV-ISSUE-01 — Создание сотрудника выдаёт ровно один invite-токен, статус INVITED, пароль null
**Предусловия**: авторизован бутстрап-ADMIN; используется email `test+{run-id}+emp@example.com`, роль `MANAGER`, `locale = "RU"`.
**Шаги**:
1. `POST /api/users` с телом `{ "name": "Emp", "email": "test+{run-id}+emp@example.com", "roleId": <MANAGER-id>, "locale": "RU" }` и токеном ADMIN.
   - Ожидание: HTTP 200/201; тело пользователя со `status = INVITED`; поле `status` в запросе отсутствует и игнорируется.
2. В тестовом окружении проверить `invite_tokens` по `user_id` созданного пользователя.
   - Ожидание: ровно одна запись; `token` — канонический UUID из 36 символов; `used = false`.
3. Проверить, что у пользователя `passwordHash IS NULL` (через БД-хелпер или что вход `POST /api/auth/login` возвращает 403 not.activated).
   - Ожидание: пароль не установлен, вход невозможен до активации.
**Ожидаемый итог**: создан INVITED-пользователь с ровно одним invite-токеном и без пароля (Req 3.1, 3.7, 3.8).
**Teardown**: деактивировать пользователя; пометить токен `used`.

### TC-INV-ISSUE-02 — Поле status в теле запроса игнорируется (всегда INVITED)
**Предусловия**: авторизован бутстрап-ADMIN; email `test+{run-id}+status@example.com`, роль `FOREMAN`.
**Шаги**:
1. `POST /api/users` с телом, содержащим лишнее `"status": "ACTIVE"`.
   - Ожидание: HTTP 200/201; созданный пользователь всё равно `status = INVITED` (DTO не экспонирует `status`, маппер жёстко проставляет INVITED).
**Ожидаемый итог**: клиентское значение status не влияет на результат (Req 3.7).
**Teardown**: деактивировать пользователя.

### TC-INV-ISSUE-03 — Создание клиента выдаёт ровно один invite-токен
**Предусловия**: авторизован бутстрап-ADMIN; email `test+{run-id}+cli@example.com`, роль `CLIENT`.
**Шаги**:
1. `POST /api/users` с ролью `CLIENT`.
   - Ожидание: HTTP 200/201; `status = INVITED`; в `invite_tokens` ровно одна запись `used = false` с 36-символьным UUID.
**Ожидаемый итог**: клиент тоже получает ровно один invite-токен (Req 3.1, 3.8).
**Teardown**: деактивировать пользователя; пометить токен `used`.

---

## Группа 2. Роль-зависимое письмо приглашения

**Покрывает**: Req 4.1, 4.2.
**Стратегия повторяемости группы**: генератор email; перехват письма тестовым SMTP. Teardown: деактивировать пользователей, пометить токены `used`, очистить mailbox прогона.

### TC-INV-MAIL-01 — Employee получает Set_Password_Invitation со ссылкой
**Предусловия**: перехватчик писем активен; создаётся сотрудник (`MANAGER`, `test+{run-id}+empmail@example.com`).
**Шаги**:
1. `POST /api/users` (роль MANAGER).
   - Ожидание: HTTP 200/201; создан INVITED-пользователь.
2. Проверить перехваченный mailbox адресата.
   - Ожидание: ровно одно письмо Set_Password_Invitation; тело содержит `Invite_Link` вида `{invite-base-url}?token={token}`, где `{token}` совпадает со значением токена из `invite_tokens`.
**Ожидаемый итог**: сотруднику отправлено одно письмо со ссылкой на установку пароля (Req 4.1).
**Teardown**: деактивировать пользователя; пометить токен `used`.

### TC-INV-MAIL-02 — Client получает Client_Portal_Invitation без set-password ссылки
**Предусловия**: перехватчик писем активен; создаётся клиент (`CLIENT`, `test+{run-id}+climail@example.com`).
**Шаги**:
1. `POST /api/users` (роль CLIENT).
   - Ожидание: HTTP 200/201; создан INVITED-пользователь.
2. Проверить перехваченный mailbox адресата.
   - Ожидание: ровно одно письмо Client_Portal_Invitation; тело инструктирует войти по OTP-коду на тот же email и **не содержит** set-password `Invite_Link`.
**Ожидаемый итог**: клиенту отправлено одно письмо портала без ссылки на установку пароля (Req 4.2).
**Teardown**: деактивировать пользователя; пометить токен `used`.

---

## Группа 3. Установка пароля и авто-логин (`POST /api/auth/set-password`)

**Покрывает**: Req 5.1, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 5.10.
**Стратегия повторяемости группы**: под каждый позитивный кейс создаётся свежий INVITED-пользователь по генератору, токен берётся из письма/БД. Teardown: деактивировать пользователей, отозвать refresh-токены (`/logout`), пометить оставшиеся invite-токены `used`.

### TC-SETPWD-01 — Успешная установка пароля активирует и логинит (auto-login)
**Предусловия**: создан INVITED-сотрудник (`MANAGER`, `test+{run-id}+set@example.com`); получен валидный неиспользованный токен `T`.
**Шаги**:
1. `POST /api/auth/set-password` с телом `{ "token": "<T>", "password": "Passw0rd!" }` (без заголовка Authorization).
   - Ожидание: HTTP 200; тело содержит непустые `accessToken`, `refreshToken` и числовой `expiresIn` (auto-login).
2. Проверить в БД: пользователь `status = ACTIVE`, `passwordHash` — bcrypt-хеш (cost 12), токен `used = true`.
   - Ожидание: все три изменения применены атомарно.
3. `POST /api/auth/login` с `{ "email": "<email>", "password": "Passw0rd!" }`.
   - Ожидание: HTTP 200; вход работает с новым паролем.
**Ожидаемый итог**: пароль установлен, аккаунт активирован, токены выданы (Req 5.4, 5.5).
**Teardown**: `/logout` для выданных refresh-токенов; деактивировать пользователя.

### TC-SETPWD-02 — Эндпоинт доступен без аутентификации и по своему пути
**Предусловия**: нет валидного токена (произвольная строка).
**Шаги**:
1. `POST /api/auth/set-password` с телом `{ "token": "any-{run-id}", "password": "Passw0rd!" }` без заголовка Authorization.
   - Ожидание: запрос доходит до бизнес-логики (не 401 из-за отсутствия аутентификации); поскольку токен неизвестен — HTTP 400 `error.invite.token.invalid`.
**Ожидаемый итог**: `POST /api/auth/set-password` публичен и достижим по своему пути (Req 5.1, 5.2, 5.6).

### TC-SETPWD-03 — Пустой/отсутствующий token → 400 до поиска токена
**Предусловия**: нет.
**Шаги**:
1. `POST /api/auth/set-password` с телом `{ "token": "", "password": "Passw0rd!" }`.
   - Ожидание: HTTP 400; ошибка валидации на поле `token`; поиск токена не выполняется.
2. Повторить с `token`, состоящим из пробелов (`"   "`), и с полностью отсутствующим полем `token`.
   - Ожидание: HTTP 400 в обоих случаях.
**Ожидаемый итог**: blank/missing token отклонён до бизнес-логики, изменений нет (Req 5.3).

### TC-SETPWD-04 — Пароль вне 8..72 символов → 400 до поиска токена
**Предусловия**: нет.
**Шаги**:
1. `POST /api/auth/set-password` с телом `{ "token": "any-{run-id}", "password": "short" }` (7 символов).
   - Ожидание: HTTP 400 (`@Size(min=8)`); поиск токена не выполняется.
2. Повторить с паролем длиной 73 символа.
   - Ожидание: HTTP 400 (`@Size(max=72)`).
3. Повторить с полностью отсутствующим полем `password`.
   - Ожидание: HTTP 400 (`@NotBlank`).
**Ожидаемый итог**: пароль вне диапазона/отсутствующий отклонён до поиска токена, изменений нет (Req 5.3).

### TC-SETPWD-05 — Неизвестный token → 400 error.invite.token.invalid
**Предусловия**: значение токена не соответствует ни одной записи `invite_tokens`.
**Шаги**:
1. `POST /api/auth/set-password` с телом `{ "token": "nonexistent-{run-id}", "password": "Passw0rd!" }`.
   - Ожидание: HTTP 400; message code `error.invite.token.invalid`; изменений в пользователях/токенах нет.
**Ожидаемый итог**: неизвестный токен отклонён без изменений (Req 5.6).

### TC-SETPWD-06 — Истёкший token → 400 error.invite.token.expired
**Предусловия**: существует invite-токен `T` с `expires_at <= now` (через БД-хелпер или короткий TTL в тестовом окружении), владелец `INVITED`, `used = false`.
**Шаги**:
1. `POST /api/auth/set-password` с телом `{ "token": "<T>", "password": "Passw0rd!" }`.
   - Ожидание: HTTP 400; message code `error.invite.token.expired`; владелец и токен не изменены.
**Ожидаемый итог**: истёкший токен отклонён отдельным кодом без изменений (Req 5.7).
**Teardown**: деактивировать пользователя.

### TC-SETPWD-07 — Уже использованный token → 400 error.invite.token.used
**Предусловия**: существует invite-токен `T` с `used = true`.
**Шаги**:
1. `POST /api/auth/set-password` с телом `{ "token": "<T>", "password": "Passw0rd!" }`.
   - Ожидание: HTTP 400; message code `error.invite.token.used`; владелец и токен не изменены.
**Ожидаемый итог**: использованный токен отклонён без изменений (Req 5.8).

### TC-SETPWD-08 — Повторное использование токена после успеха → 400 used (одноразовость)
**Предусловия**: создан INVITED-сотрудник, получен валидный токен `T`.
**Шаги**:
1. `POST /api/auth/set-password` с `{ "token": "<T>", "password": "Passw0rd!" }`.
   - Ожидание: HTTP 200; пара токенов; аккаунт ACTIVE, `T.used = true`.
2. Повторить `POST /api/auth/set-password` с тем же `T` и любым паролем.
   - Ожидание: HTTP 400; message code `error.invite.token.used`.
**Ожидаемый итог**: успешно потреблённый токен нельзя переиспользовать (Req 5.9).
**Teardown**: `/logout` для refresh-токенов; деактивировать пользователя.

### TC-SETPWD-09 — Валидный токен, но владелец DEACTIVATED → 409 already.active
**Предусловия**: существует валидный неиспользованный неистёкший токен `T`, владелец которого переведён в `DEACTIVATED` (через БД-хелпер / деактивацию).
**Шаги**:
1. `POST /api/auth/set-password` с `{ "token": "<T>", "password": "Passw0rd!" }`.
   - Ожидание: HTTP 409; message code `error.invite.user.already.active`; владелец не изменён; `T.used` остаётся `false`.
**Ожидаемый итог**: установка пароля для деактивированного владельца отклонена, токен не потреблён (Req 5.10).

### TC-SETPWD-10 — Порядок проверок: invalid → used → expired → status
**Предусловия**: подготовлены токены разных состояний (несуществующий; used+expired; expired при INVITED-владельце).
**Шаги**:
1. Несуществующий токен → ожидание HTTP 400 `error.invite.token.invalid`.
2. Токен `used = true` И одновременно `expires_at` в прошлом → ожидание HTTP 400 `error.invite.token.used` (used проверяется раньше expired).
3. Токен `used = false`, `expires_at` в прошлом, владелец INVITED → ожидание HTTP 400 `error.invite.token.expired`.
**Ожидаемый итог**: порядок оценки соответствует контракту, ни одна ветка отказа не меняет состояние (Req 5.6, 5.7, 5.8).
**Teardown**: деактивировать созданных владельцев.

---

## Группа 4. Переотправка приглашения и авторизация ADMIN (`POST /api/auth/resend-invite`)

**Покрывает**: Req 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9.
**Стратегия повторяемости группы**: авторизованный вызывающий — бутстрап-ADMIN; целевые пользователи создаются по генератору. Teardown: деактивировать пользователей, пометить их invite-токены `used`, очистить mailbox прогона.

### TC-RESEND-01 — ADMIN переотправляет для INVITED → 200, ротация токена + письмо
**Предусловия**: авторизован бутстрап-ADMIN; создан INVITED-сотрудник (`MANAGER`, `test+{run-id}+resend@example.com`) с исходным неиспользованным токеном `T1`.
**Шаги**:
1. `POST /api/auth/resend-invite` с телом `{ "userId": <id> }` и токеном ADMIN.
   - Ожидание: HTTP 200.
2. Проверить `invite_tokens` пользователя: `T1.used = true`; появился новый токен `T2` (`used = false`, `T2 != T1`, `expires_at ≈ now + ttlHours` ±5 c).
   - Ожидание: старый токен инвалидирован, выдан новый.
3. Проверить mailbox: отправлено новое роль-зависимое письмо (Set_Password_Invitation для сотрудника).
   - Ожидание: ровно одно новое письмо.
4. `POST /api/auth/set-password` со старым `T1` → HTTP 400 `error.invite.token.used`; с новым `T2` → HTTP 200.
   - Ожидание: активирует только новый токен.
**Ожидаемый итог**: переотправка ротирует токен и шлёт новое письмо (Req 6.6).
**Teardown**: `/logout` для выданных refresh-токенов; деактивировать пользователя.

### TC-RESEND-02 — Пустой/отсутствующий userId → 400 до поиска пользователя
**Предусловия**: авторизован бутстрап-ADMIN.
**Шаги**:
1. `POST /api/auth/resend-invite` с телом `{ }` (нет `userId`) и токеном ADMIN.
   - Ожидание: HTTP 400; ошибка валидации; поиск пользователя не выполняется, письмо не отправлено.
2. Повторить с `{ "userId": null }`.
   - Ожидание: HTTP 400.
**Ожидаемый итог**: отсутствующий userId отклонён до бизнес-логики (Req 6.3).

### TC-RESEND-03 — Без токена → 401, без изменений и письма
**Предусловия**: существует INVITED-пользователь с известным `id`.
**Шаги**:
1. `POST /api/auth/resend-invite` с телом `{ "userId": <id> }` **без** заголовка Authorization.
   - Ожидание: HTTP 401; токены/письма не изменены.
**Ожидаемый итог**: неаутентифицированный вызов отклонён (Req 6.4).

### TC-RESEND-04 — Не-ADMIN токен → 403, без изменений и письма
**Предусловия**: есть access-токен обычного (не-ADMIN) ACTIVE-пользователя; существует INVITED-пользователь с известным `id`.
**Шаги**:
1. `POST /api/auth/resend-invite` с `{ "userId": <id> }` и `Authorization: Bearer <non-admin-token>`.
   - Ожидание: HTTP 403; токены/письма не изменены.
**Ожидаемый итог**: не-ADMIN не может переотправлять приглашения (Req 6.2, 6.5).

### TC-RESEND-05 — Неизвестный userId → 404 error.invite.user.not.found
**Предусловия**: авторизован бутстрап-ADMIN; `userId`, которого нет (например `999999999`).
**Шаги**:
1. `POST /api/auth/resend-invite` с `{ "userId": 999999999 }` и токеном ADMIN.
   - Ожидание: HTTP 404; message code `error.invite.user.not.found`; изменений нет, письмо не отправлено.
**Ожидаемый итог**: несуществующий пользователь → 404 без побочных эффектов (Req 6.7).

### TC-RESEND-06 — Пользователь ACTIVE → 409 already.active
**Предусловия**: авторизован бутстрап-ADMIN; существует пользователь в статусе `ACTIVE` (активирован через set-password ранее).
**Шаги**:
1. `POST /api/auth/resend-invite` с `{ "userId": <active-id> }` и токеном ADMIN.
   - Ожидание: HTTP 409; message code `error.invite.user.already.active`; изменений нет, письмо не отправлено.
**Ожидаемый итог**: переотправка для ACTIVE запрещена (Req 6.8).
**Teardown**: деактивировать пользователя.

### TC-RESEND-07 — Пользователь DEACTIVATED → 409 already.active
**Предусловия**: авторизован бутстрап-ADMIN; существует пользователь в статусе `DEACTIVATED`.
**Шаги**:
1. `POST /api/auth/resend-invite` с `{ "userId": <deact-id> }` и токеном ADMIN.
   - Ожидание: HTTP 409; message code `error.invite.user.already.active`; изменений нет, письмо не отправлено.
**Ожидаемый итог**: переотправка для DEACTIVATED запрещена тем же кодом (Req 6.9).

---

## Группа 5. Конфигурация и локализация

**Покрывает**: Req 7.1, 8.1.
**Стратегия повторяемости группы**: проверка конфигурации — read-only на уровне запущенного контейнера; локализация — негативный запрос без создания сущностей.

### TC-CFG-01 — Invite-ссылка формируется от базового URL из конфигурации (дефолт)
**Предусловия**: backend поднят без переопределения `MAIL_INVITE_BASE_URL` (действует дефолт `http://localhost:3000/auth/set-password`); перехватчик писем активен; создаётся сотрудник по генератору.
**Шаги**:
1. `POST /api/users` (роль MANAGER) → создан INVITED-пользователь, отправлено письмо.
   - Ожидание: HTTP 200/201.
2. Проверить `Invite_Link` в письме.
   - Ожидание: ссылка начинается с `http://localhost:3000/auth/set-password` и содержит `?token=<url-encoded token>`; значение токена после декодирования совпадает с записью в `invite_tokens`.
**Ожидаемый итог**: базовый URL берётся из конфигурации, дефолт применяется при отсутствии env (Req 7.1).
**Teardown**: деактивировать пользователя; пометить токен `used`.

### TC-I18N-01 — Ошибка приглашения различается для PL и RU по заголовку foremen-language
**Предусловия**: нет (используется неизвестный токен → детерминированная ошибка `error.invite.token.invalid`).
**Шаги**:
1. `POST /api/auth/set-password` с `{ "token": "nonexistent-{run-id}", "password": "Passw0rd!" }` и заголовком `foremen-language: PL`.
   - Ожидание: HTTP 400; message code `error.invite.token.invalid`; текст — на польском (из `messages.properties`).
2. Тот же запрос с `foremen-language: RU`.
   - Ожидание: HTTP 400; тот же code; текст — на русском (из `messages_ru.properties`), отличается от польского.
3. Тот же запрос без заголовка `foremen-language`.
   - Ожидание: HTTP 400; текст на польском (fallback → PL).
**Ожидаемый итог**: сообщения приглашений локализованы в PL и RU, есть корректный fallback (Req 8.1).

---

## Регрессия

**Стратегия повторяемости раздела**: каждый сквозной кейс использует свежего пользователя по генератору run-id; teardown отзывает refresh-токены через `/logout`, деактивирует пользователей и помечает токены `used`.

### REG-01 — Полный invite-путь: create → письмо → set-password → auto-login → повтор токена
**Шаги**:
1. `POST /api/users` (роль MANAGER, ADMIN-токен) → 200/201; INVITED-пользователь, один invite-токен, одно письмо.
2. Извлечь токен `T` из письма/БД; `POST /api/auth/set-password` `{ token: T, password: Passw0rd! }` → 200, пара токенов; пользователь ACTIVE, `T.used = true`.
3. Повторить `POST /api/auth/set-password` с тем же `T` → 400 `error.invite.token.used`.
4. `POST /api/auth/login` новым паролем → 200.
**Ожидаемый итог**: сквозной invite-flow работает, одноразовость токена подтверждена (Req 3.1, 4.1, 5.4, 5.5, 5.9).
**Teardown**: `/logout`; деактивировать пользователя.

### REG-02 — Одноразовость set-password токена (перепроверка)
**Шаги**:
1. Активировать пользователя через `POST /api/auth/set-password` → 200.
2. Ещё раз тот же токен → 400 `error.invite.token.used`.
**Ожидаемый итог**: потреблённый токен не активируется повторно (Req 5.9).

### REG-03 — Только ADMIN может resend-invite
**Шаги**:
1. `POST /api/auth/resend-invite` без токена → 401.
2. `POST /api/auth/resend-invite` с не-ADMIN токеном → 403.
3. `POST /api/auth/resend-invite` с ADMIN-токеном для INVITED-пользователя → 200, ротация токена.
**Ожидаемый итог**: авторизация resend-invite ограничена ROLE_ADMIN (Req 6.2, 6.4, 6.5).

### REG-04 — FOR-03-01 auth-пути остаются достижимы
**Предусловия**: изменения FOR-03-02 не должны ломать существующие эндпоинты.
**Шаги**:
1. `POST /api/auth/login` (ACTIVE, например бутстрап-ADMIN) → 200, пара токенов.
2. `GET /api/auth/me` без токена → 401; с валидным токеном → 200.
3. `POST /api/auth/set-password` доступен без аутентификации (публичен), `POST /api/auth/resend-invite` требует ADMIN — порядок security-матчеров не сломал FOR-03-01 правила.
**Ожидаемый итог**: добавление новых эндпоинтов не нарушило ранее доступные auth-пути (Req 5.2, 6.2 и FOR-03-01 §6, §9).

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-INV-ISSUE-01 | 1 | POST /api/users (MANAGER) | 200/201 + status INVITED | | |
| 2 | TC-INV-ISSUE-01 | 2 | Проверка invite_tokens | 1 запись, UUID 36, used=false | | |
| 3 | TC-INV-MAIL-01 | 2 | Проверка письма сотрудника | Set_Password_Invitation + Invite_Link | | |
| 4 | TC-INV-MAIL-02 | 2 | Проверка письма клиента | Client_Portal_Invitation без ссылки | | |
| 5 | TC-SETPWD-01 | 1 | POST /api/auth/set-password (valid) | 200 + accessToken/refreshToken/expiresIn | | |
| 6 | TC-SETPWD-01 | 2 | Проверка БД | ACTIVE + bcrypt + token used | | |
| 7 | TC-SETPWD-03 | 1 | POST /api/auth/set-password (blank token) | 400, без поиска токена | | |
| 8 | TC-SETPWD-04 | 1-2 | set-password (password <8 / >72) | 400 @Size | | |
| 9 | TC-SETPWD-05 | 1 | set-password (unknown token) | 400 error.invite.token.invalid | | |
| 10 | TC-SETPWD-06 | 1 | set-password (expired) | 400 error.invite.token.expired | | |
| 11 | TC-SETPWD-07 | 1 | set-password (used) | 400 error.invite.token.used | | |
| 12 | TC-SETPWD-08 | 1-2 | set-password дважды | 200, затем 400 used | | |
| 13 | TC-SETPWD-09 | 1 | set-password (DEACTIVATED owner) | 409 error.invite.user.already.active | | |
| 14 | TC-RESEND-01 | 1-2 | POST /api/auth/resend-invite (ADMIN, INVITED) | 200 + ротация токена | | |
| 15 | TC-RESEND-02 | 1 | resend-invite (нет userId) | 400 до поиска | | |
| 16 | TC-RESEND-03 | 1 | resend-invite (без токена) | 401 | | |
| 17 | TC-RESEND-04 | 1 | resend-invite (не-ADMIN) | 403 | | |
| 18 | TC-RESEND-05 | 1 | resend-invite (unknown userId) | 404 error.invite.user.not.found | | |
| 19 | TC-RESEND-06 | 1 | resend-invite (ACTIVE) | 409 error.invite.user.already.active | | |
| 20 | TC-RESEND-07 | 1 | resend-invite (DEACTIVATED) | 409 error.invite.user.already.active | | |
| 21 | TC-CFG-01 | 2 | Проверка Invite_Link | база из конфига + ?token= (url-encoded) | | |
| 22 | TC-I18N-01 | 1-3 | set-password (unknown) с foremen-language PL/RU/absent | 400, тексты по локали + fallback PL | | |
| 23 | REG-01 | 1-4 | Полный invite-путь | сквозной сценарий проходит | | |
| … | … | … | … | … | | |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`. Стратегия повторяемости: генератор `test+{run-id}+{seq}@example.com` + teardown (деактивация пользователей, пометка invite-токенов `used`, `/logout` для refresh-токенов).
