# Тест-кейсы — FOR-03-10 Вход через Google

Спека смешанная: API-часть (`POST /api/auth/google`) проверяется против Docker-стека, UI-часть — в
браузере. Настоящий попап Google нельзя автоматизировать (нужен живой Google-аккаунт и разрешённый
origin OAuth-клиента), поэтому позитивные сценарии входа выполняются вручную на `https://dev.foremen.eu`;
негативные сценарии бэкенда на уровне правил покрыты юнит-тестом `AuthServiceGoogleLoginTest`
(замоканный верификатор токенов).

**Окружение:**
- API: `docker compose up` в корне, базовый URL `http://localhost:8080`; `.env` содержит
  `FOREMEN_GOOGLE_CLIENT_ID` и `VITE_GOOGLE_CLIENT_ID`.
- UI (ручной): `https://dev.foremen.eu/login`.
- Для локального UI в OAuth-клиенте должны быть origin'ы `http://localhost:3000` и `http://localhost`.

**Стратегия повторяемости:**
- API-кейсы: генератор — поддельные токены и email вида `google+{run-id}@example.com`; данные в БД
  не создаются.
- Ручные UI-кейсы: генератор — приглашённый сотрудник с Google-email `{alias}+{run-id}@<домен>`
  (Workspace/Gmail поддерживают `+`-алиасы). Teardown: деактивировать созданного пользователя в админке.

## Фича 1. Конфигурация и загрузка кнопки

### TC-01 Кнопка Google отображается на логине (PL)
- Предусловия: сборка с `VITE_GOOGLE_CLIENT_ID`, язык PL.
1. Открыть `/login` → видна кнопка Google «Zaloguj się przez Google» (рендер GIS, iframe `hl=pl`).
2. Консоль браузера → нет ошибок CSP и нет «origin is not allowed».

### TC-02 Кнопка на русском
1. Переключить язык на RU, перезагрузить `/login` → кнопка «Войти через Google» (iframe `hl=ru`).

### TC-03 Без client id кнопки нет
- Предусловия: сборка с пустым `VITE_GOOGLE_CLIENT_ID`.
1. Открыть `/login` → блока Google нет, форма пароля работает.

## Фича 2. API — негативные сценарии (Docker)

### TC-10 Пустой токен
1. `POST /api/auth/google` `{"idToken":""}` → `400`, ошибка валидации.

### TC-11 Поддельный токен
1. `POST /api/auth/google` `{"idToken":"eyJ...forged"}`, `Accept-Language: ru` → `401`, message
   «Недействительный токен Google. Попробуйте войти снова.»; токены не выданы.

### TC-12 Правила (юнит-уровень, `AuthServiceGoogleLoginTest`)
1. `email_verified=false` → `401 error.auth.google.email.unverified`.
2. Email не зарегистрирован → `403 error.auth.google.no.account`.
3. CLIENT (ACTIVE/INVITED) → `403 error.auth.google.client`, статус не меняется.
4. DEACTIVATED → `403 error.auth.account.deactivated`.
5. INVITED сотрудник → `200 AUTHENTICATED`, статус ACTIVE, invite-токены `used`, пароль не задан.
6. ACTIVE сотрудник → `200 AUTHENTICATED`.

## Фича 3. Вход через Google (ручной, dev)

### TC-20 Активный сотрудник входит
- Предусловия: ACTIVE сотрудник (не CLIENT) с Google-email.
1. `/login` → «Zaloguj się przez Google» → выбрать аккаунт → попап закрывается.
2. Ожидание: редирект на `/` (или сохранённый Return_Location), в шапке имя пользователя.

### TC-21 Приглашённый сотрудник активируется автоматически
- Предусловия: админ пригласил сотрудника `{alias}+{run-id}@<домен>` (статус INVITED).
1. Войти через Google этим аккаунтом → вход выполнен без установки пароля.
2. Админка → Пользователи → статус пользователя «Aktywny».
3. Старая ссылка-приглашение из письма → «ссылка недействительна».
4. Teardown: деактивировать пользователя.

### TC-22 Незарегистрированный Google-аккаунт
1. Войти через Google аккаунтом, которого нет в Foremen → сообщение «Najpierw poproś administratora o
   zarejestrowanie Cię w systemie» (RU: «Сначала попросите администратора зарегистрировать вас в
   системе»), вход не выполнен.

### TC-23 Клиент не может войти через Google
- Предусловия: CLIENT с Google-email.
1. Войти через Google → «Klienci logują się jednorazowym kodem wysyłanym na e-mail» и ссылка
   «Zaloguj się kodem z e-maila».
2. Клик по ссылке → `/auth/otp`.

### TC-24 Деактивированный сотрудник
1. Войти через Google деактивированным аккаунтом → «Konto zostało dezaktywowane…», вход не выполнен.

### TC-25 Другой email не связывается
- Предусловия: сотрудник `a@company.pl` (не Google), Google-аккаунт `a@gmail.com`.
1. Войти через Google `a@gmail.com` → «Najpierw poproś administratora…»; аккаунт `a@company.pl` не
   затронут.

## Регрессия

### TC-R1 Вход по паролю
1. `/login` → email/пароль ACTIVE сотрудника → вход выполнен.

### TC-R2 OTP-вход клиента
1. `/auth/otp` → email клиента → код из письма → вход выполнен.

### TC-R3 Приглашение с установкой пароля
1. Пригласить сотрудника → ссылка из письма `/auth/set-password?token=` → задать пароль → вход.

### TC-R4 Сброс пароля (FOR-03-09)
1. «Nie pamiętasz hasła?» → запрос → ссылка → новый пароль → вход новым паролем.

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-11     | 1   | POST /api/auth/google (forged) | 401 token.invalid | | |
| 2 | TC-20     | 1   | Google sign-in (ACTIVE) | редирект на `/` | | |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`. Стратегия повторяемости:
`генератор (+run-id алиасы) + teardown (деактивация)`.
