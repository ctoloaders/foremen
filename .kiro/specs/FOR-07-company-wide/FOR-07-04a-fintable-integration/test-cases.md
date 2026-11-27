# Тест-кейсы: FOR-07-04a — Коннектор банковской ленты Fintable

> Версия: финальная (задача 7), сверена с реализацией: `controller/bank/**`, `FintableConnector`,
> `application.yml`, `docker-compose.yml`, `features/bank/**`, `locales/{ru,pl}.json` и интеграционными
> тестами `FintableStartupIntegrationTest`, `FintableSyncIntegrationTest`,
> `FintableTokenExpiryNotificationIntegrationTest` (см. раздел «Соответствие автотестам»).
> Требования: 16.5; сценарии покрывают также требования 1–12.

## Назначение и характер спеки

Спека **смешанная (backend + UI)**. Три части:

- **Часть A — API-тесты** против поднятого в Docker приложения (`http://localhost:8080`) и **песочницы
  Fintable** (sandbox-подключение Plaid, бесплатное, не расходует лимиты плана).
- **Часть B — браузерные сценарии** (headless-браузер против `http://localhost:3000`): страница «Банк»
  (раздел «Система»), вкладки «Подключения», «Поддерживаемые банки».
- **Часть C — ручной сценарий с реальным польским банком** (только на окружении с оплаченным планом
  Fintable; проверяет форму `raw` и контрагентов).

Результат прогона — MD-репорт с таблицами (шаг → запрос/действие → ожидание → факт → статус), без
скриншотов.

## Окружение и предусловия

| Параметр | Значение |
|----------|----------|
| Docker-стек | `docker compose up` (postgres :5432, liquibase, backend :8080, frontend :3000) |
| Переменные backend | `FINTABLE_ENABLED=true`, `FINTABLE_API_TOKEN` (из `.secrets/fintable.env`), `SALTEDGE_ENABLED=false`, `FOREMEN_PUBLIC_BASE_URL=http://localhost:3000` (HTTP ⇒ без `redirect_url`) |
| Пользователь | ADMIN (`FOREMEN_ADMIN_*`); получить JWT: `POST /api/auth/login` |
| Песочница Fintable | в аккаунте Fintable создано sandbox-подключение (`POST /connections/link/test_sandbox`, одно на Workspace); его id вида `conn_plaid_<N>` берётся из `GET https://fintable.io/api/v2/connections` |
| Крон | `FOREMEN_BANK_SYNC_CRON` (по умолчанию `0 30 6 * * *`), `FOREMEN_BANK_CONSENT_SWEEP_CRON` (по умолчанию `0 0 7 * * *`); для ручной проверки задать ближайшую минуту, напр. `0 * * * * *`, затем вернуть значение |
| Токен | **не печатать** в репортах и логах; репорт содержит только длину/факт наличия |

Переменные сценариев: `{JWT}`, `{SANDBOX_CONN}` (id песочницы), `{RUN}` (run-id), `{CID}` (id подключения Foremen).

## Стратегия повторяемости

- **Генератор данных:** `{RUN}` = `yyyyMMddHHmmss`; `displayName` подключения = `FT-{RUN}`.
- **Teardown (в конце каждого сценария, где создаётся подключение):**
  `POST /api/bank/connections/{CID}/disable` → `DELETE /api/bank/connections/{CID}` (после disable; если
  есть привязки — оставить disabled и пометить в репорте). **Внимание:** для Fintable `disable` вызывает
  `DELETE /connections/{id}` на стороне Fintable и **удаляет sandbox-подключение**; чтобы повторить
  прогон, создать sandbox заново (`POST /connections/link/test_sandbox`, 1 раз в минуту) и обновить
  `{SANDBOX_CONN}`. Поэтому сценарии A-3…A-7 выполняются **одним прогоном** (одно sandbox-подключение
  на весь набор), teardown — только в A-8.
- Сценарии без создания подключения (A-1, A-2, B-1, B-2) повторяются без очистки.

---

## Часть A. API-сценарии

### Группа A1. Флаги провайдеров

**TC-A-1. Fintable доступен, Salt Edge выключен**

Предусловия: стек поднят с переменными выше.

| # | Шаг | Запрос | Ожидание |
|---|-----|--------|----------|
| 1 | Список провайдеров | `GET /api/bank/providers` | 200; в списке `FINTABLE` с `available=true`, `searchOnly=true`; `SALT_EDGE` отсутствует (выключен); `STUB` отсутствует |
| 2 | Старт без токена | Перезапуск backend с пустым `FINTABLE_API_TOKEN` | Приложение стартует; `GET /api/bank/providers` не содержит `FINTABLE`; в логе ERROR с **именами** свойств, без значений |
| 3 | Токен не в ответах | Поиск токена в телах ответов шагов 1–2 и в логе backend | Токен не найден |

**TC-A-2. Поведение существующих подключений Salt Edge при выключенном флаге**

Предусловия: в БД есть подключение `provider=SALT_EDGE`, статус `ACTIVE` (создать SQL-вставкой или
взять из предыдущего окружения).

| # | Шаг | Запрос / действие | Ожидание |
|---|-----|-------------------|----------|
| 1 | Список подключений | `GET /api/bank/connections` | 200; у строки Salt Edge `providerAvailable=false` |
| 2 | Ручная синхронизация | `POST /api/bank/connections/{id}/sync` | 409, код «провайдер недоступен» |
| 3 | Плановый запуск | Дождаться/вызвать тик планировщика (`foremen.bank-feed.sync.cron`) | Для строки Salt Edge нет нового `sync run`; статус не изменился; новых уведомлений `syncFailed` нет |
| 4 | Callback Salt Edge | `POST /api/bank-feed/saltedge/callbacks/success` с произвольным телом | 200, `{"ignored":"provider disabled"}`; состояние не изменилось |
| 5 | Локальное отключение | `POST /api/bank/connections/{id}/disable` | 200, статус `DISABLED`, без обращения к Salt Edge |

### Группа A2. Каталог банков (поиск)

**TC-A-3. Поиск банков в Fintable**

| # | Шаг | Запрос | Ожидание |
|---|-----|--------|----------|
| 1 | Популярные банки | `GET /api/bank/providers/FINTABLE/banks` | 200; непустой список; есть строки PKO, mBank, ING, Pekao, Santander, Millennium, Alior; у строк `via` ∈ {`ENABLEBANKING`,`NORDIGEN`}; `testBank=false` |
| 2 | Поиск | `GET /api/bank/providers/FINTABLE/banks?q=PKO` | 200; есть `code=9079_pko-bank-polski` (или актуальный slug PKO); для банков с несколькими вариантами `ENABLEBANKING` идёт раньше `NORDIGEN` |
| 3 | Слишком короткий запрос | `GET …/banks?q=pk` | 400, `error.bank.fintable.searchTooShort` (PL/RU по `Accept-Language`) |
| 4 | Тип счетов | В ответе шага 2 | Для slug с `business-and-personal` `accountTypes` содержит `business` и `personal` |
| 5 | Кэш | Повторить шаг 2 дважды подряд | Второй ответ быстрее; во втором нет обращений к публичному `/institutions` (по логу DEBUG) |

### Группа A3. Подключение, синхронизация, данные (одним прогоном с песочницей)

Предусловия: sandbox-подключение Fintable существует (`{SANDBOX_CONN}`), минуту назад ссылки не
создавались.

**TC-A-4. Создание подключения и ссылка**

| # | Шаг | Запрос | Ожидание |
|---|-----|--------|----------|
| 1 | Создание | `POST /api/bank/connections` `{"provider":"FINTABLE","providerBankCode":"9079_pko-bank-polski","displayName":"FT-{RUN}"}` | 201; `status=PENDING_AUTH`; `authorizationUrl` начинается с `https://fintable.io/api-link/`; в URL нет токена API; `{CID}` сохранён |
| 2 | Нет redirect_url | Проверка запроса к Fintable (лог DEBUG / mock) | `redirect_url` не передан (база HTTP) |
| 3 | Лимит ссылок | Сразу повторить шаг 1 | 429/409 `error.bank.fintable.rateLimited` с остатком секунд; обращения к Fintable нет |
| 4 | Повторная авторизация | Через минуту `POST /api/bank/connections/{CID}/authorize` | 200, новая ссылка |

**TC-A-5. Завершение подключения (привязка песочницы)**

| # | Шаг | Запрос | Ожидание |
|---|-----|--------|----------|
| 1 | Завершение с параметрами | `POST /api/bank/connections/{CID}/complete` `{"params":{"connection_id":"{SANDBOX_CONN}","status":"connected"}}` | 200; `completed=true`; статус `ACTIVE`; `syncRunId` не пуст |
| 2 | Идемпотентность | Повторить шаг 1 | 200; `completed=false`; новый run не создан |
| 3 | Привязка к другому | Создать второе подключение и завершить с тем же `connection_id` | 409 `error.bank.connection.alreadyLinked` |
| 4 | Неверный id | Третье подключение, `connection_id` = `conn_plaid_0` | Ошибка REAUTH/404-класса; подключение остаётся `PENDING_AUTH` |
| 5 | status=error | `params`: `{"status":"error"}` | 200; `completed=false`; `PENDING_AUTH` |
| 6 | Завершение без параметров (fallback) | `params`: `{}` на подключении с несвязанной песочницей | Привязка происходит только если ровно одно несвязанное подключение создано после старта авторизации; иначе `completed=false` |

**TC-A-6. Синхронизация и данные**

| # | Шаг | Запрос | Ожидание |
|---|-----|--------|----------|
| 1 | Ожидание run | `GET /api/bank/sync-runs?connectionId={CID}` (или `/{runId}` из ответа `/complete`) до статуса не RUNNING | `SUCCEEDED` (или `PARTIAL` с объяснением); счётчики `accountsSeen=14` |
| 2 | Счета | `GET /api/bank/accounts?connectionId={CID}` | 14 счетов (песочница Plaid), валюта USD, `iban=null`; баланс как в Fintable |
| 3 | Транзакции | `GET /api/bank/transactions?accountId=…` | Строки со статусом POSTED; знак суммы: расходы отрицательные; `externalId` вида `tx_…`; `pending`-строк нет |
| 4 | Итоги | Суммарное число строк по всем счетам | Равно числу транзакций в Fintable (`GET /transactions`, 67 на 2026-11-18) |
| 5 | Идемпотентность | `POST /api/bank/connections/{CID}/sync` (ответ 202 с `runId`), затем опрос `GET /api/bank/sync-runs/{runId}` до завершения | Новых строк 0; число строк прежнее |
| 6 | Курсор | Проверка `bank_accounts.transactions_cursor` (SQL) | Значение вида `u:<ISO-время>`; после шага 5 не уменьшилось |
| 7 | Статус подключения | `GET /api/bank/connections/{CID}` | `ACTIVE`; `consentExpiresAt=null`; статус не `CONSENT_EXPIRING` |

**TC-A-7. Ручное обновление и лимиты**

| # | Шаг | Запрос | Ожидание |
|---|-----|--------|----------|
| 1 | Первое обновление | `POST /api/bank/connections/{CID}/refresh` | 200; запущен MANUAL-run; `nextRefreshPossibleAt` = сейчас + `FINTABLE_MANUAL_SYNC_INTERVAL` |
| 2 | Ожидание Fintable | Наблюдать run (лог WARN при таймауте) | Run стартует чтение после завершения синхронизации Fintable либо после `sync-wait`; run не падает по таймауту |
| 3 | Повтор до срока | `POST …/refresh` сразу | 409 `error.bank.refresh.notYetPossible` с временем; обращения `POST /sync` к Fintable нет |
| 4 | Лимит Fintable | Подменить локальный интервал на `0` и вызвать дважды | Второй вызов: Fintable 429 ⇒ 409 notYetPossible со временем по `Retry-After`; либо `already_syncing` ⇒ 200 |

**TC-A-8. Отключение и очистка**

| # | Шаг | Запрос | Ожидание |
|---|-----|--------|----------|
| 1 | Отключение | `POST /api/bank/connections/{CID}/disable` | 200; `DISABLED`; на стороне Fintable подключение удалено (`GET /connections` без `{SANDBOX_CONN}`) |
| 2 | Данные Foremen | `GET /api/bank/transactions` | Ранее импортированные строки на месте |
| 3 | Повторное отключение | Повторить шаг 1 | 200, без ошибки (идемпотентно) |
| 4 | Удаление | `DELETE /api/bank/connections/{CID}` | 200 или 409 `hasLinks` (если есть привязки) |
| 5 | Восстановление песочницы | `POST https://fintable.io/api/v2/connections/link/test_sandbox` | Новое sandbox-подключение для следующего прогона |

### Группа A4. Ошибки провайдера

**Fake-сервер Fintable (вместо песочницы).** Все сценарии A3/A4 можно выполнять против заглушки с тем же
контрактом, что `FakeFintableServer` из `FintableSyncIntegrationTest` (`/connections/link`,
`/connections/{id}`, `/accounts`, `/accounts/{id}/transactions` с двумя страницами и `next_cursor`, `/sync`).
Запуск: любой HTTP-мок на хосте (напр. WireMock :9099); в backend
`FINTABLE_BASE_URL=http://host.docker.internal:9099/api/v2`, `FINTABLE_ALLOW_INSECURE_BASE_URL=true`,
`FINTABLE_API_TOKEN=fake-{RUN}`. Плюсы: нет лимитов плана, песочница не расходуется, статусы
401/403/404/422/429/5xx задаются по требованию. Ожидания A3 в этом режиме берутся из данных мока
(по умолчанию как в автотесте: 1 счёт, 3 строки на 2 страницах, повторный синк — 0 новых строк).
Teardown: `disable` + `DELETE` подключений `FT-{RUN}`, остановка мока. Шаг 1 допустимо проверить и
реальным Fintable с заведомо неверным токеном.

**TC-A-9. Классы ошибок**

| # | Условие | Ожидание |
|---|---------|----------|
| 1 | Токен неверный (401) | Run `FAILED`; подключение `ERROR`; код `error.bank.fintable.tokenInvalid`; уведомление `notification.bank.syncFailed` (не чаще 1 раза в 24 ч) |
| 2 | Нет плана (403 на `POST /sync`) | `error.bank.fintable.noPlan`; подключение не уходит в `REAUTH_REQUIRED` |
| 3 | `needs_reconnect=true` | Статус `REAUTH_REQUIRED`; уведомление `notification.bank.reauthRequired` |
| 4 | 404 подключения | `REAUTH_REQUIRED` |
| 5 | 5xx/таймаут | Повторы оркестратора, затем `ERROR` |
| 6 | Замена токена на верный | Следующий синк успешен, статус возвращается в `ACTIVE` |

### Группа A5. Уведомление об истечении токена

**TC-A-10. Пороги 30 / 7 / 0 дней**

Предусловия: `FINTABLE_ENABLED=true`; роли ADMIN и FINANCIER имеют активных пользователей.
Уведомления смотреть через `GET /api/notifications` под пользователем соответствующей роли. Маркеры
хранятся в `bank_feed_provider_state`. Повторяемость: перед шагом 1 выполнить
`DELETE FROM bank_feed_provider_state WHERE provider='FINTABLE'`. Детерминированный аналог —
`FintableTokenExpiryNotificationIntegrationTest`.

| # | Шаг | Действие | Ожидание |
|---|-----|----------|----------|
| 1 | За 31 день | `FINTABLE_TOKEN_EXPIRES_ON` = сегодня+31; перезапустить backend с `FOREMEN_BANK_CONSENT_SWEEP_CRON=0 * * * * *` и дождаться минуты (HTTP-эндпойнта у sweep нет) | Уведомлений нет |
| 2 | За 30 дней | = сегодня+30; sweep | По одному уведомлению `notification.bank.fintableTokenExpiring` ADMIN и FINANCIER; ссылка `/bank/connections` |
| 3 | Повторный sweep | Тот же день | Новых уведомлений нет |
| 4 | За 7 дней | = сегодня+7; sweep | Ещё по одному уведомлению |
| 5 | Просрочен | = вчера; sweep | Ещё по одному уведомлению (уровень 0), один раз |
| 6 | Новый токен | = сегодня+365; sweep | Маркеры сброшены; уведомлений нет; через изменение даты на +30 уведомление снова приходит |
| 7 | Пустая дата | Пустое значение | Уведомлений нет; одно предупреждение WARN при старте |

---

## Часть B. Браузерные сценарии

Базовый URL `http://localhost:3000`; вход под ADMIN. Действия — в браузерном движке; результат — таблица
«шаг → ожидание → факт → статус».

**TC-B-1. Добавление банка Fintable (UI)**

| # | Действие | Ожидание |
|---|----------|----------|
| 1 | «Система» → «Банк» → вкладка «Подключения» → «Добавить банк» | Диалог открыт; провайдер по умолчанию — Fintable; виден текст `bank.fintable.poweredBy` со ссылкой на fintable.io (рабочая, открывается в новой вкладке) |
| 2 | Не вводить запрос | Показан список «Популярные банки» |
| 3 | Ввести `pk` | Подсказка о минимуме 3 символов, запрос не отправляется |
| 4 | Ввести `PKO` | Результаты; у каждой строки название, типы счетов, «Через: …» |
| 5 | Выбрать строку и подтвердить | Показано пояснение о переходе в Fintable и 30 минутах; браузер перенаправлен на `fintable.io/api-link/…` |
| 6 | Вернуться в Foremen вручную | В списке подключение со статусом «Ожидает авторизации»; доступны «Продолжить авторизацию» и «Завершить подключение» |
| 7 | Нажать «Завершить подключение» (Fintable-подключение ещё не создано) | Сообщение `bank.fintable.finishPending`; статус прежний |
| 8 | Повторно «Добавить банк» сразу после шага 5 | Сообщение о паузе `bank.fintable.linkCooldown` с секундами |

Repeatability: `displayName` = `FT-{RUN}`; teardown — удалить подключение (Удалить в меню строки).

**TC-B-2. Salt Edge выключен**

| # | Действие | Ожидание |
|---|----------|----------|
| 1 | Открыть «Подключения» при существующем подключении Salt Edge | Бейдж «Провайдер отключён» (PL: «Dostawca wyłączony») с подсказкой |
| 2 | Меню строки | Действия «Обновить», «Переподключить», «Синхронизировать» недоступны; «Переименовать», «Отключить», «Удалить» доступны |
| 3 | В диалоге «Добавить банк» | Salt Edge отсутствует в выборе провайдера |

**TC-B-3. Подключение песочницы и статус (UI)**

Предусловия: подключение создано по TC-A-4 и завершено по TC-A-5.

| # | Действие | Ожидание |
|---|----------|----------|
| 1 | Вкладка «Подключения» | Статус «Активно»; нет баннера о сроке согласия; нет бейджа «Согласие истекает» |
| 2 | Вкладка «Счета» | 14 счетов песочницы, валюта USD |
| 3 | «Обновить сейчас» | Запуск; повторное нажатие сразу — сообщение `bank.fintable.refreshLimited` со временем |
| 4 | «Отключить» | Диалог содержит `bank.fintable.disableWarning`; после подтверждения статус «Отключено» |
| 5 | Язык PL ↔ RU | Все новые строки переведены; ключей-«сырых» (`bank.fintable.*`) на экране нет |

**TC-B-4. Вкладка «Поддерживаемые банки»**

| # | Действие | Ожидание |
|---|----------|----------|
| 1 | Открыть вкладку, провайдер Fintable | Поле поиска и популярные банки |
| 2 | Ввести `mBank` | Строки mBank (retail / corporate / business-and-personal) |
| 3 | Ввести `zzzzzz` | Пустое состояние `bank.fintable.noResults` |

---

## Часть C. Ручной сценарий с реальным польским банком

Выполняется один раз на окружении с оплаченным планом Fintable (не пробным); подключается **только
собственный счёт компании** (условия API Fintable).

| # | Действие | Ожидание |
|---|----------|----------|
| 1 | Добавить host окружения в Dashboard → API → Return hosts; задать `FOREMEN_PUBLIC_BASE_URL` с HTTPS | Backend стартует; провайдер Fintable доступен |
| 2 | «Добавить банк» → выбрать вариант с `ENABLEBANKING` (например `*-business-and-personal`) | Переход на Fintable; вход в банк; возврат в Foremen на `/bank/connections/return?...&connection_id=conn_…&status=connected` |
| 3 | Страница возврата | «Подключение завершено»; статус «Активно»; запущена первая синхронизация |
| 4 | Счета | IBAN заполнен; имя владельца (`holder_name`) сопоставлено с профилем компании (бейдж владельца) |
| 5 | Транзакции | Есть контрагент (имя) и IBAN у входящих/исходящих; назначение платежа заполнено; суммы и знаки верны (сверка с выпиской банка по 5 операциям) |
| 6 | Сверка `raw` | Зафиксировать, какие поля `raw` дали контрагента/IBAN/NIP; обновить `FintableMapper` и фикстуры (задача 8.2) |
| 7 | Ночная синхронизация | На следующий день плановый pull после 06:30 Europe/Warsaw принёс новые операции без дублей |
| 8 | Проверка автосопоставления | Для известного контрагента из проформы появилось предложение/авто-привязка |

Результат: заполненный репорт + запись о форме `raw` в runbook.

---

## Соответствие автотестам

| Автотест | Покрывает сценарии | Требования |
|----------|--------------------|-----------|
| `FintableStartupIntegrationTest` (оба флага off / Fintable on) | TC-A-1, TC-A-2 (шаг 1), REG-4 | 1.2, 1.3, 2.2 |
| `FintableSyncIntegrationTest` (fake-сервер: connect → complete → sync, `u:`-курсор, идемпотентность, нет pending) | TC-A-4…TC-A-6 | 5, 7, 8, 9 |
| `FintableTokenExpiryNotificationIntegrationTest` | TC-A-10 | 12.3, 12.4 |
| `SaltEdgeDisabledIntegrationTest` (+ `BankSyncSchedulerTest`, `SaltEdgeCallbackControllerTest`) | TC-A-2 | 1.4–1.7 |
| `FintableClientTest`, `FintableErrorMapperTest`, `FintableMapperTest` | TC-A-9 | 3.2, 8.4, 8.5 |

Docker-прогон дополняет автотесты: проверяет реальную сборку, compose-переменные и UI.

---

## Регрессия

| ID | Проверка | Ожидание |
|----|----------|----------|
| REG-1 | Существующие тесты Salt Edge (`SaltEdge*Test`, `BankLedger*IntegrationTest`) | Проходят после обобщения SPI |
| REG-2 | STUB-коннектор (`FOREMEN_BANK_STUB_ENABLED=true`) | Подключение, синхронизация и курсор работают как раньше (курсор `null`) |
| REG-3 | Оркестратор: курсор Salt Edge | Курсор «максимальный id» без изменений |
| REG-4 | Страница «Банк» без подключений | Пустое состояние; Fintable в выборе провайдера |
| REG-5 | Паритет локалей (`locale-parity.for0704.test.ts`) | Зелёный с новыми ключами |
| REG-6 | Уведомления FOR-07-04 (`reauthRequired`, `syncFailed`, `balanceGap`, `transactionsToReview`) | Приходят для подключений Fintable по тем же правилам; `consentExpiring` не приходит |

---

## Шаблон MD-репорта прогона

Run-id: `<yyyyMMddHHmmss>`. Окружение: `<local|dev|prod>`. Версия: `<git sha>`. Стратегия
повторяемости: `<генератор run-id + teardown A-8>`. Токен в репорт не включается.

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-A-1 | 1 | GET /api/bank/providers | FINTABLE available, SALT_EDGE нет | | |
| 2 | TC-A-5 | 1 | POST /api/bank/connections/{CID}/complete | 200, ACTIVE | | |

Итог: X пройдено / Y провалено / Z пропущено.

Отклонения и найденные дефекты:

| # | Тест-кейс | Описание | Серьёзность | Ссылка на баг |
|---|-----------|----------|-------------|---------------|
| | | | | |
