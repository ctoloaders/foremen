# Тест-кейсы: FOR-03-09 — Сброс пароля (end-to-end)

## Назначение и характер

Спека имеет API-поверхность и браузерные экраны, поэтому набор включает:

- **Часть A. API-тесты** против поднятого Docker-стека (`http://localhost:8080`):
  `POST /api/auth/password-reset/request`, `POST /api/auth/password-reset/confirm`, `POST /api/auth/login`.
- **Часть B. Браузерные сценарии** против `http://localhost:3000` (браузерный движок):
  `/login`, `/auth/forgot-password`, `/auth/reset-password?token=…`.

Результат прогона — **MD-репорт с таблицами** (шаг → запрос/действие → ожидание → факт → статус).
Скриншоты не требуются.

> Локальный стек отправляет почту через реальный SMTP-relay (без перехватчика писем), поэтому токен
> берётся из БД: `SELECT token FROM password_reset_tokens WHERE user_id = :id ORDER BY id DESC LIMIT 1`.
> Письмо проверяется по логу backend (`Password-reset email dispatched to …`) и, при наличии доступа,
> в ящике тестового адреса.

## Запуск окружения

1. `docker compose up -d` в корне (postgres, liquibase, backend :8080, frontend :3000).
2. ADMIN-токен: `POST /api/auth/login`.

## Стратегия повторяемости

**Генератор `run-id` + teardown.** На прогон создаются через admin-API:
- сотрудник `reset+{run-id}@example.com` (роль MANAGER), активированный через invite set-password
  с паролем `Start-{run-id}!`;
- клиент `reset-client+{run-id}@example.com` (CLIENT, ACTIVE);
- приглашённый `reset-invited+{run-id}@example.com` (INVITED, без активации).

Teardown: деактивация всех трёх пользователей; их токены сброса остаются помеченными (не влияют на
следующий прогон, т.к. адреса уникальны).

## Часть A. API

### Фича A1. Запрос сброса

#### TC-PR-01 — Активный сотрудник получает токен
1. `POST /password-reset/request {"email":"reset+{run-id}@example.com"}` → 200, пустое тело.
2. В `password_reset_tokens` новая строка: `used=false`, `expires_at ≈ now()+60 мин`.
3. В логе backend: `Password-reset email dispatched to reset+{run-id}@example.com`.
- _Покрывает: R3.1, R4_

#### TC-PR-02 — Анти-перебор: неизвестный, приглашённый, клиент
1. Для `nobody+{run-id}@example.com`, `reset-invited+{run-id}@…`, `reset-client+{run-id}@…` →
   каждый раз 200 и пустое тело (идентичный ответ).
2. Новых строк в `password_reset_tokens` для этих пользователей нет; писем в логе нет.
- _Покрывает: R2.3, R3.2_

#### TC-PR-03 — Ссылка в письме
1. При запросе из TC-PR-01 ссылка имеет вид `…/auth/reset-password?token=<token>` (из лога отправки
   / письма), токен совпадает со строкой БД.
- _Покрывает: R4.1_

### Фича A2. Подтверждение

#### TC-PR-04 — Успешная смена пароля
1. `POST /password-reset/confirm {"token":"<token>","newPassword":"New-{run-id}!"}` → 200.
2. `POST /api/auth/login` со старым паролем → 401; с новым → 200.
3. Ранее выданный refresh-токен пользователя → `POST /api/auth/refresh` → 401 (сессии отозваны).
- _Покрывает: R5.4, R5.7_

#### TC-PR-05 — Повторное использование токена
1. Тот же токен повторно → 400; пароль не изменился.

#### TC-PR-06 — Политика длины пароля
1. Новый токен (TC-PR-01), `newPassword` из 7 символов → 400; из 73 символов → 400; токен остаётся
   неиспользованным.
- _Покрывает: R5.6_

#### TC-PR-07 — Неверный токен
1. `{"token":"bogus-{run-id}","newPassword":"Valid-123!"}` → 400.

## Часть B. UI

#### TC-PR-UI-01 — Ссылка «Nie pamiętasz hasła?»
1. Открыть `/login` → ссылка «Nie pamiętasz hasła?» видна, доступна с клавиатуры (Tab), ведёт на
   `/auth/forgot-password`.
- _Покрывает: R1_

#### TC-PR-UI-02 — Запрос сброса и нейтральное сообщение
1. Пустой e-mail → «Wyślij link» не отправляет, видна ошибка валидации.
2. Ввести `reset+{run-id}@example.com` → «Wyślij link» → форма заменена текстом «Jeśli konto istnieje,
   wysłaliśmy link na podany adres. Link jest ważny 60 minut.»
3. Повторить с `nobody+{run-id}@example.com` → тот же текст.
- _Покрывает: R2_

#### TC-PR-UI-03 — Установка нового пароля
1. Открыть `/auth/reset-password?token=<token из БД>`.
2. Несовпадающие пароли → «Hasła nie są zgodne»; 7 символов → «Hasło musi mieć od 8 do 72 znaków».
3. Корректный пароль → «Zapisz hasło» → переход на `/login` с сообщением «Hasło zostało zmienione.
   Zaloguj się nowym hasłem.»
4. Войти новым паролем → успешный вход.
- _Покрывает: R5.1–R5.4_

#### TC-PR-UI-04 — Недействительная ссылка
1. `/auth/reset-password` без токена → «Link jest nieprawidłowy lub wygasł. Poproś o nowy.», формы нет,
   есть ссылка «Poproś o nowy link».
2. Использованный токен → после отправки то же сообщение.
- _Покрывает: R5.2, R5.5_

#### TC-PR-UI-05 — Русская локализация
1. Переключить язык на RU и пройти TC-PR-UI-01…03: «Забыли пароль?», «Сброс пароля»,
   «Отправить ссылку», «Установите новый пароль», «Пароль изменён. Войдите с новым паролем.».
   Сырых ключей нет.
- _Покрывает: R6_

## Регрессия

- **TC-PR-REG-01** — обычный вход по паролю (`/login`) работает.
- **TC-PR-REG-02** — активация по приглашению `/auth/set-password?token=` работает (invite-токен).
- **TC-PR-REG-03** — вход клиента по OTP (`/auth/otp`) работает.

## Шаблон MD-репорта

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-PR-01 | 1 | POST /api/auth/password-reset/request | 200, токен создан | | |
| 2 | TC-PR-02 | 1 | request для CLIENT | 200, токена нет | | |
| 3 | TC-PR-04 | 2 | login старым / новым паролем | 401 / 200 | | |
| 4 | TC-PR-UI-03 | 3 | «Zapisz hasło» | /login + сообщение | | |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp>`. Стратегия повторяемости:
`генератор + teardown`.
