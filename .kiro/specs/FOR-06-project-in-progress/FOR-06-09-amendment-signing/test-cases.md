# Тест-кейсы: FOR-06-09 — Amendment signing (подписание анекса → применение дельт)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерные экраны**, поэтому набор тест-кейсов включает **оба типа**:

- **Часть A. API-тесты** — против поднятого в Docker приложения (`http://localhost:8080`). Каждый
  шаг — HTTP-запрос с проверкой статуса, тела и заголовков. Ключевой эндпоинт этой спеки —
  `POST /api/amendments/{id}/send-for-signing`; плюс цепочка подписания через движок FOR-05-08
  (`/api/signable-documents/...`), чтение расширенной модели анекса (FOR-06-08
  `GET /api/amendments/...`), чтение проекта (`GET /api/projects/{id}`) и создание ворк-репорта
  (`POST /api/work-reports`) для проверки живого ценового шва.
- **Часть B. Браузерные (UI) сценарии** — против фронтенда (`http://localhost:3000`), исполняются
  браузерным движком (headless-браузер / browser automation). Экран: вкладка **Aneksy**
  (`features/project-amendments/AmendmentsPanel`, ключ таба `amendments`) воркспейса проекта.
  Проверяются: действие **Send for signing** и его диалог (пикеры метода/уровня), индикатор прогресса
  подписания (signed/total + outstanding + ссылка «перейти к документу»), отображение
  `effectiveDate`, read-only видимость для FOREMAN (без действия отправки).

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание →
факт → статус). **Скриншоты не требуются.**

### Покрытие требований

Покрываются требования, указанные в задаче 11: R1.1, R1.2, R1.3, R1.5, R2.2, R2.3, R3.1, R3.2, R4.2,
R4.4, R5.1, R5.5, R6.1, R6.2, R6.3, R6.4, R7.3. Соответствие «кейс ↔ требование» указано в каждом
кейсе строкой `_Покрывает:_`.

### Границы спеки (что проверяем, а что — нет)

- Проверяем **всё после «готов к подписанию»**: отправку `OPEN`-анекса на подпись, рефлексию статуса
  документа на анекс (`SENT_FOR_SIGNING → SIGNED → EFFECTIVE`), reopen при decline/void
  (`SENT_FOR_SIGNING → OPEN`), установку `effectiveDate` = дата подписания всеми сторонами, сдвиг
  `Project.endDate`, активацию дормантного ценового шва.
- **Не проверяем** домен анекса/строк, инвариант единственного `OPEN`, мастер создания, merge/delete
  строк (это FOR-06-08) и внутренности движка подписания (FOR-05-08) — они потребляются как данность
  и считаются зелёными. Их используем только как предусловия.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции,
   changelog head `179`, без новых changeset'ов этой спеки), `backend` (:8080, профиль `docker`),
   `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN — через переменные окружения `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`,
   `FOREMEN_ADMIN_PASSWORD`. Логин: `POST /api/auth/login {email, password}` → `accessToken`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном (проверка готовности: любой
   защищённый `GET` с валидным токеном отвечает 200; `GET /api/projects` отвечает 200).

## Общая стратегия повторяемости

**Комбинированная: генератор `run-id` + teardown.** Единый на прогон `run-id` (timestamp или uuid,
например `1730000000` или `a1b2c3`) встраивается во все создаваемые идентификаторы, чтобы два
последовательных прогона на одной БД не конфликтовали:

- email пользователей: `mgr+{run-id}@example.com`, `fin+{run-id}@example.com`,
  `foreman+{run-id}@example.com`, `client+{run-id}@example.com`;
- названия проектов: `Amendment Signing Test {run-id}`;
- заголовки/комментарии анекса: `Aneks {run-id}`.

**Teardown каждой группы** выполняется в конце набора в обратном порядке создания: созданные
ворк-репорты удаляются/деактивируются доступным API; тестовые проекты переводятся в `CANCELLED` или
удаляются доступным admin-API (это каскадно обнуляет связанные анексы/документы прогона); созданные
пользователи деактивируются/удаляются через admin-API (`PATCH`/`DELETE /api/users/{id}`). Сид-данные
(роли, ресурсы `AMENDMENTS` / `SIGNABLE_DOCUMENTS`, тип документа `AMENDMENT`) **только читаются,
никогда не изменяются**. Каждая группа ниже дополнительно уточняет свою стратегию строкой
`**Повторяемость:**`.

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Там, где кейс требует роли MANAGER / FINANCIER / FOREMAN / CLIENT,
тестовые пользователи этих ролей создаются и приглашаются в проект через admin-API в группе `Setup`.
Матрица `AMENDMENTS` (из FOR-06-08): **ADMIN — CRUD; MANAGER — CRUD; FINANCIER — CRUD; FOREMAN —
READ; CLIENT — без гранта** (CLIENT достигает анекса только через поверхность подписания FOR-05-08).

### Предусловие «готовый к подписанию OPEN-анекс»

Многие кейсы требуют проекта с подписанной сметой/расписанием (baseline) и одного непустого
`OPEN`-анекса. Это предусловие собирается в группе `Setup` через домен FOR-06-08 (создание анекса,
добавление строк дельт: минимум одна `PRICE_CHANGE`/`ADDITIONAL_WORK` с `extraUnitPrice` и, для
кейсов расписания, одна `SCHEDULE_CHANGE` с `scheduleDeltaHalfDays`). Конкретные эндпоинты создания
строк — из FOR-06-08; здесь они используются как чёрный ящик подготовки данных.

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Setup — токены, проект, участники, baseline, OPEN-анекс

**Повторяемость:** читает сид + создаёт помеченные `run-id` сущности; teardown в конце всего набора.

### TC-SETUP-01 — Логин ADMIN
**Предусловия:** стек поднят; ADMIN забутстрапен переменными окружения.
**Шаги:**
1. `POST /api/auth/login { email: FOREMEN_ADMIN_EMAIL, password: FOREMEN_ADMIN_PASSWORD }`.
   - Ожидание: `200`, тело содержит `accessToken` (сохранить как `ADMIN_TOKEN`).
**Ожидаемый результат:** получен валидный `ADMIN_TOKEN`.
_Покрывает:_ предусловие авторизации.

### TC-SETUP-02 — Создание тестовых пользователей (MANAGER, FINANCIER, FOREMAN, CLIENT)
**Предусловия:** TC-SETUP-01.
**Шаги:**
1. Создать через admin-API пользователей с ролями MANAGER, FINANCIER, FOREMAN, CLIENT и email'ами с
   `run-id`.
   - Ожидание: `201`/`200` на каждого; id сохранены (`MGR_ID`, `FIN_ID`, `FOREMAN_ID`, `CLIENT_ID`).
2. Залогинить каждого (кроме CLIENT, если его флоу отдельный) → сохранить токены `MGR_TOKEN`,
   `FIN_TOKEN`, `FOREMAN_TOKEN`.
   - Ожидание: `200` + `accessToken` на каждый логин.
**Ожидаемый результат:** доступны токены всех ролей, нужных набору.
_Покрывает:_ предусловие ABAC-ролей.

### TC-SETUP-03 — Создание проекта с baseline и участниками
**Предусловия:** TC-SETUP-02.
**Шаги:**
1. Создать проект `Amendment Signing Test {run-id}` (сохранить `PROJECT_ID`, `startDate`, `endDate`
   как `BASE_END_DATE`).
   - Ожидание: `201`, проект создан.
2. Назначить в проект членов: MANAGER, FINANCIER, FOREMAN, CLIENT.
   - Ожидание: `200`/`201` на каждое членство.
3. Довести проект до состояния с подписанной сметой (baseline), где есть позиция `workItem` с
   `line.unitPrice` (сохранить `WORK_ITEM_ID`, `BASE_UNIT_PRICE`).
   - Ожидание: baseline зафиксирован (смета SIGNED/EFFECTIVE согласно FOR-05-08/FOR-06-00).
**Ожидаемый результат:** есть проект с участниками и замороженным baseline.
_Покрывает:_ предусловие данных.

### TC-SETUP-04 — Создание непустого OPEN-анекса
**Предусловия:** TC-SETUP-03.
**Шаги:**
1. Создать анекс проекта (FOR-06-08) → статус `OPEN` (сохранить `AMENDMENT_ID`, `SEQ_NO`).
   - Ожидание: `201`, `status = OPEN`.
2. Добавить строку дельты цены на `WORK_ITEM_ID`: `extraUnitPrice = DELTA` (сохранить `DELTA`).
   - Ожидание: `200`/`201`, строка добавлена.
**Ожидаемый результат:** проект имеет один непустой `OPEN`-анекс.
_Покрывает:_ предусловие R1.1.

---

## Фича 1. Send for signing — отправка OPEN-анекса на подпись (R1)

**Повторяемость:** каждый кейс стартует от свежего `OPEN`-анекса (пересоздать через TC-SETUP-04 при
необходимости); teardown — перевод проекта/документа в терминальное состояние или reopen.

### TC-SFS-01 — Happy path: отправка непустого OPEN-анекса на подпись
**Предусловия:** TC-SETUP-04 (непустой OPEN-анекс), `MGR_TOKEN`.
**Шаги:**
1. `POST /api/amendments/{AMENDMENT_ID}/send-for-signing` с `MGR_TOKEN`, тело пустое или
   `{ "method": null, "level": null }`.
   - Ожидание: `200`; в теле `status = SENT_FOR_SIGNING`, присутствует `signableDocumentId`
     (сохранить как `DOC_ID`).
2. `GET /api/amendments/{AMENDMENT_ID}` с `MGR_TOKEN`.
   - Ожидание: `200`; `status = SENT_FOR_SIGNING`; `signableDocumentId = DOC_ID`;
     `signingProgress.totalCount >= 2` (CLIENT + MANAGER), `signingProgress.signedCount = 0`.
3. `GET /api/signable-documents/{DOC_ID}` с `MGR_TOKEN`.
   - Ожидание: `200`; тип документа `AMENDMENT`; статус `PENDING_SIGNATURES`; тело PDF заморожено.
**Ожидаемый результат:** анекс в `SENT_FOR_SIGNING`, создан и связан документ `AMENDMENT`,
запрошены подписи (CLIENT + MANAGER, уровень по умолчанию `AdES`).
_Покрывает:_ R1.2, R1.3.

### TC-SFS-02 — Выбор метода/уровня подписи в запросе
**Предусловия:** свежий непустой OPEN-анекс, `MGR_TOKEN`.
**Шаги:**
1. `POST /api/amendments/{AMENDMENT_ID}/send-for-signing` с телом
   `{ "method": "<поддерживаемый метод>", "level": "<поддерживаемый уровень>" }`.
   - Ожидание: `200`; `status = SENT_FOR_SIGNING`; у связанного документа выбранные метод/уровень
     отражены в его модели подписей.
**Ожидаемый результат:** переданные method/level применены; при их отсутствии применяется дефолт
движка (`AdES`).
_Покрывает:_ R1.2 (опции диалога).

### TC-SFS-03 — Reject: отправка пустого анекса
**Предусловия:** создан `OPEN`-анекс **без строк**, `MGR_TOKEN`.
**Шаги:**
1. `POST /api/amendments/{EMPTY_AMENDMENT_ID}/send-for-signing` с `MGR_TOKEN`.
   - Ожидание: `422`; тело содержит код/ключ сообщения `error.amendment.emptyNotSignable`.
2. `GET /api/amendments/{EMPTY_AMENDMENT_ID}`.
   - Ожидание: `200`; `status = OPEN` (не изменился); `signableDocumentId` отсутствует/`null`.
**Ожидаемый результат:** пустой анекс не отправлен, статус `OPEN` сохранён, документ не создан.
_Покрывает:_ R1.1.

### TC-SFS-04 — Reject: анекс не в статусе OPEN
**Предусловия:** анекс уже в `SENT_FOR_SIGNING` (из TC-SFS-01), `MGR_TOKEN`.
**Шаги:**
1. `POST /api/amendments/{AMENDMENT_ID}/send-for-signing` повторно.
   - Ожидание: `409`; тело содержит код/ключ сообщения `error.amendment.notOpen`.
2. (Доп. проверка) Повторить для анекса в состояниях `SIGNED`, `EFFECTIVE`, `DELETED` (где
   воспроизводимо).
   - Ожидание: `409` + `error.amendment.notOpen` в каждом случае; дубликат-документ не создаётся.
**Ожидаемый результат:** отправка отклонена для любого не-`OPEN` статуса, in-flight остаётся один.
_Покрывает:_ R1.5.

### TC-SFS-05 — ABAC: FOREMAN не может отправить на подпись
**Предусловия:** свежий OPEN-анекс, `FOREMAN_TOKEN` (роль с `AMENDMENTS` READ).
**Шаги:**
1. `POST /api/amendments/{AMENDMENT_ID}/send-for-signing` с `FOREMAN_TOKEN`.
   - Ожидание: `403` (нет `AMENDMENTS` UPDATE); статус анекса не изменился.
**Ожидаемый результат:** действие доступно только ролям с `AMENDMENTS` UPDATE (ADMIN/MANAGER/FINANCIER).
_Покрывает:_ R1.5 (гейтинг отправки), сопутствует R6.4.

---

## Фича 2. Рефлексия подписания → EFFECTIVE (R2, R3)

**Повторяемость:** каждый кейс начинается с анекса в `SENT_FOR_SIGNING` (TC-SFS-01); teardown —
перевод проекта в терминал.

### TC-REFL-01 — Полное подписание ведёт анекс в EFFECTIVE и ставит effectiveDate
**Предусловия:** анекс `SENT_FOR_SIGNING` со связанным документом `DOC_ID`, подписанты CLIENT + MANAGER.
**Шаги:**
1. Через движок FOR-05-08 завершить подпись MANAGER по `DOC_ID`.
   - Ожидание: `200`; документ остаётся `PENDING_SIGNATURES` (подписан не всеми).
2. `GET /api/amendments/{AMENDMENT_ID}`.
   - Ожидание: `signingProgress.signedCount = 1`, `status = SENT_FOR_SIGNING`.
3. Завершить подпись CLIENT по `DOC_ID` (последняя подпись).
   - Ожидание: `200`; документ переходит в `SIGNED`.
4. `GET /api/amendments/{AMENDMENT_ID}` (после коммита after-commit реакции).
   - Ожидание: `status = EFFECTIVE`; `effectiveDate` установлен и равен дате последней подписи
     (`max(signedAt)`); `signingProgress.signedCount = totalCount`.
**Ожидаемый результат:** анекс отражает полное подписание: `SENT_FOR_SIGNING → SIGNED → EFFECTIVE`,
`effectiveDate` = дата подписания всеми сторонами.
_Покрывает:_ R2.2, R3.1, R3.2.

### TC-REFL-02 — Проект не активируется подписанием анекса (тип не CONTRACT_*)
**Предусловия:** TC-REFL-01 выполнен (анекс стал `EFFECTIVE`); известен `PROJECT_ID` и его статус до.
**Шаги:**
1. `GET /api/projects/{PROJECT_ID}` до и после полного подписания анекса.
   - Ожидание: `Project.status` не изменился из-за подписания анекса (нет project-activation hand-off).
**Ожидаемый результат:** подписание `AMENDMENT`-документа не двигает `Project.status`.
_Покрывает:_ R3.2 (сопутствует R1.7).

### TC-REFL-03 — Decline возвращает анекс в OPEN (reopen)
**Предусловия:** свежий анекс `SENT_FOR_SIGNING` со связанным `DOC_ID`; известны строки анекса.
**Шаги:**
1. Через движок FOR-05-08 один из подписантов выполняет **decline** по `DOC_ID`.
   - Ожидание: `200`; документ переходит в состояние отклонения.
2. `GET /api/amendments/{AMENDMENT_ID}` (после after-commit реакции).
   - Ожидание: `status = OPEN`; `signableDocumentId` отсутствует/`null` (связь очищена);
     список строк анекса не изменён (кол-во и дельты те же).
3. `GET /api/signable-documents/{DOC_ID}`.
   - Ожидание: `200`; отклонённый документ по-прежнему читается (аудит).
**Ожидаемый результат:** decline переоткрывает анекс для редактирования, строки сохранены, документ
остаётся доступен.
_Покрывает:_ R2.3.

### TC-REFL-04 — Void до завершения возвращает анекс в OPEN (reopen)
**Предусловия:** свежий анекс `SENT_FOR_SIGNING` со связанным `DOC_ID`.
**Шаги:**
1. Через движок FOR-05-08 выполнить **void** документа `DOC_ID` до его полного подписания.
   - Ожидание: `200`; документ `VOID`.
2. `GET /api/amendments/{AMENDMENT_ID}`.
   - Ожидание: `status = OPEN`; `signableDocumentId` `null`; строки целы.
**Ожидаемый результат:** void переоткрывает анекс; строки целы; voided-документ читается для аудита.
_Покрывает:_ R2.3.

### TC-REFL-05 — Повторная доставка full-sign идемпотентна
**Предусловия:** анекс уже `EFFECTIVE` (TC-REFL-01); зафиксированы `effectiveDate` и `Project.endDate`.
**Шаги:**
1. Повторно спровоцировать обработку full-sign по тому же `DOC_ID` (повторная доставка события).
   - Ожидание: нет ошибки; никакого повторного применения дельт.
2. `GET /api/amendments/{AMENDMENT_ID}` и `GET /api/projects/{PROJECT_ID}`.
   - Ожидание: `status = EFFECTIVE` неизменно; `effectiveDate` тот же; `Project.endDate` не сдвинут
     повторно.
**Ожидаемый результат:** `SIGNED → EFFECTIVE` и сдвиг даты применяются ровно один раз.
_Покрывает:_ R3.2 (идемпотентность; сопутствует R5.1/R5.4).

---

## Фича 3. Ценовой шов становится живым (R4)

**Повторяемость:** использует `EFFECTIVE`-анекс из Фичи 2; созданные ворк-репорты удаляются в teardown.

### TC-PRICE-01 — Ворк-репорт ПОСЛЕ effectiveDate использует amended-цену
**Предусловия:** анекс `EFFECTIVE` на `WORK_ITEM_ID` с `extraUnitPrice = DELTA`; известен
`effectiveDate`, `BASE_UNIT_PRICE`.
**Шаги:**
1. `POST /api/work-reports` на `WORK_ITEM_ID` c датой отчёта `>= effectiveDate`.
   - Ожидание: `201`; в сохранённом отчёте `clientUnitPrice = BASE_UNIT_PRICE + DELTA`.
**Ожидаемый результат:** после effectiveDate цена клиента отражает `line.unitPrice + Σ extraUnitPrice`.
_Покрывает:_ R4.2, R4.4.

### TC-PRICE-02 — Ворк-репорт ДО effectiveDate использует baseline-цену
**Предусловия:** те же, что TC-PRICE-01.
**Шаги:**
1. `POST /api/work-reports` на `WORK_ITEM_ID` c датой отчёта `< effectiveDate`.
   - Ожидание: `201`; в сохранённом отчёте `clientUnitPrice = BASE_UNIT_PRICE` (без дельты).
**Ожидаемый результат:** до effectiveDate цена остаётся baseline — граница `effectiveDate <= reportDate`
соблюдена.
_Покрывает:_ R4.2, R4.4.

---

## Фича 4. Сдвиг даты окончания проекта (R5)

**Повторяемость:** каждый кейс стартует от свежего OPEN-анекса с нужными `SCHEDULE_CHANGE`-строками;
teardown — перевод проекта в терминал.

### TC-SCHED-01 — EFFECTIVE со SCHEDULE_CHANGE сдвигает только Project.endDate
**Предусловия:** OPEN-анекс со строкой `SCHEDULE_CHANGE` `scheduleDeltaHalfDays = H` (положительный,
например `4` → `+2` дня); известен `BASE_END_DATE`, `Project.startDate`.
**Шаги:**
1. Запомнить `GET /api/projects/{PROJECT_ID}` → `startDate`, `endDate = BASE_END_DATE`.
2. Отправить анекс на подпись и довести до полного подписания (как в TC-SFS-01 + TC-REFL-01).
   - Ожидание: анекс `EFFECTIVE`.
3. `GET /api/projects/{PROJECT_ID}`.
   - Ожидание: `endDate = BASE_END_DATE + round(H/2)` дней; `startDate` не изменился.
**Ожидаемый результат:** сдвигается только `Project.endDate` на нетто-дельту расписания; `startDate`
и Gantt-бары не меняются.
_Покрывает:_ R5.1.

### TC-SCHED-02 — EFFECTIVE без SCHEDULE_CHANGE оставляет endDate без изменений
**Предусловия:** OPEN-анекс только с ценовой строкой, без `SCHEDULE_CHANGE`.
**Шаги:**
1. Запомнить `BASE_END_DATE`.
2. Довести анекс до `EFFECTIVE`.
3. `GET /api/projects/{PROJECT_ID}`.
   - Ожидание: `endDate = BASE_END_DATE` (без изменений).
**Ожидаемый результат:** при отсутствии `SCHEDULE_CHANGE` дата окончания не трогается.
_Покрывает:_ R5.1 (нулевой случай).

### TC-SCHED-03 — Reject: сдвиг уводит endDate раньше startDate
**Предусловия:** OPEN-анекс со строкой `SCHEDULE_CHANGE` с большим **отрицательным**
`scheduleDeltaHalfDays`, так что `endDate` оказался бы раньше `startDate`.
**Шаги:**
1. Отправить анекс на подпись и довести до полного подписания.
   - Ожидание: применение дельты расписания отклоняется правилом валидного диапазона; поверхность
     возвращает/логирует локализованное сообщение `error.amendment.endDateInvalid`; `Project.endDate`
     **не** становится невалидным (остаётся прежним).
**Ожидаемый результат:** недопустимое укорочение отклонено детерминированным правилом с сообщением
`error.amendment.endDateInvalid`, невалидный диапазон не создаётся.
_Покрывает:_ R5.5.

---

# Часть B. Браузерные (UI) сценарии — вкладка Aneksy

**Повторяемость:** UI-кейсы работают поверх API-подготовленных данных (группа `Setup` + свежий
OPEN-анекс). Логин в UI под нужной ролью; teardown — на уровне данных (перевод проекта в терминал).
Исполняются браузерным движком против `http://localhost:3000`.

### TC-UI-01 — Действие Send for signing и диалог (MANAGER)
**Предусловия:** непустой OPEN-анекс; логин MANAGER (роль с `AMENDMENTS` UPDATE).
**Шаги:**
1. Открыть воркспейс проекта → вкладка **Aneksy**.
   - Ожидание: отображается строка `OPEN`-анекса с доступным действием **Send for signing**.
2. Нажать **Send for signing**.
   - Ожидание: открывается диалог отправки с (опциональными) пикерами **метода** и **уровня** подписи
     (лейблы переиспользуют FOR-05-08).
3. Подтвердить отправку в диалоге.
   - Ожидание: вызывается `POST /{id}/send-for-signing`; статус строки меняется на `SENT_FOR_SIGNING`
     (бейдж `amendments.status.*`); диалог закрывается.
**Ожидаемый результат:** MANAGER может отправить непустой OPEN-анекс через диалог с пикерами.
_Покрывает:_ R6.1.

### TC-UI-02 — Индикатор прогресса подписания и ссылка на документ
**Предусловия:** анекс `SENT_FOR_SIGNING` со связанным документом; логин MANAGER.
**Шаги:**
1. Открыть вкладку **Aneksy**, найти строку анекса `SENT_FOR_SIGNING`.
   - Ожидание: показан прогресс подписания **signed/total** и список **outstanding** подписантов,
     прочитанные из связанного документа.
2. Проверить наличие ссылки «перейти к документу».
   - Ожидание: ссылка ведёт на поверхность подписания документа
     (`/projects/{id}/documentSigning`); сама церемония — на движке FOR-05-08, не продублирована на
     вкладке Aneksy.
**Ожидаемый результат:** панель показывает читаемый прогресс и ссылку на документ, не переизобретая
церемонию.
_Покрывает:_ R6.2.

### TC-UI-03 — Отображение статуса и effectiveDate
**Предусловия:** доступны анексы в статусах `SENT_FOR_SIGNING`, `SIGNED`, `EFFECTIVE`; логин MANAGER.
**Шаги:**
1. Открыть вкладку **Aneksy**.
   - Ожидание: статусы `SENT_FOR_SIGNING` / `SIGNED` / `EFFECTIVE` отрисованы бейджем через лейблы
     `amendments.status.*` (не сырой enum).
2. Для `EFFECTIVE`-анекса проверить отображение даты вступления в силу.
   - Ожидание: `effectiveDate` отображается рядом со статусом.
**Ожидаемый результат:** статус локализован через `amendments.status.*`; `effectiveDate` показан после
установки.
_Покрывает:_ R6.3.

### TC-UI-04 — FOREMAN: read-only, без действия отправки
**Предусловия:** есть анексы разных статусов; логин FOREMAN (роль с `AMENDMENTS` READ).
**Шаги:**
1. Открыть вкладку **Aneksy**.
   - Ожидание: статусы и прогресс подписания видны (read-only); действие **Send for signing**
     **отсутствует** для FOREMAN.
**Ожидаемый результат:** FOREMAN видит статус подписания только на чтение и не видит действия отправки.
_Покрывает:_ R6.4.

---

# Регрессия

**Повторяемость:** регрессионные кейсы переиспользуют стратегию `run-id` + teardown из основного
набора; каждый кейс самодостаточен и перезапускаем без ручной чистки БД.

### REG-01 — Уведомление о sent-for-signing (deep-link на Aneksy)
**Предусловия:** непустой OPEN-анекс; получатели — владелец-MANAGER + FINANCIER проекта.
**Шаги:**
1. Отправить анекс на подпись (TC-SFS-01).
2. Проверить in-app колокольчик у MANAGER и FINANCIER.
   - Ожидание: пришло уведомление типа `notification.amendment.sentForSigning`, идентифицирующее
     анекс (проект + `seqNo`), кликабельное, с deep-link `/projects/{projectId}/amendments`.
3. Проверить, что CLIENT-сторона покрыта reused `notification.document.sentForSigning`, а не второй
   amendment-нотификацией.
   - Ожидание: нет дублирующей CLIENT-facing amendment-нотификации для того же события.
**Ожидаемый результат:** amendment-нотификация sent-for-signing доставлена владельцу+FINANCIER с
корректным deep-link; дубликатов нет.
_Покрывает:_ R7.3.

### REG-02 — Уведомление о effective и о reopened (deep-link на Aneksy)
**Предусловия:** анекс, доводимый до `EFFECTIVE` и, отдельно, до reopen.
**Шаги:**
1. Довести анекс до `EFFECTIVE` (TC-REFL-01).
   - Ожидание: MANAGER + FINANCIER получают `notification.amendment.effective` с датой и deep-link'ом
     на строку анекса в Aneksy.
2. На отдельном анексе выполнить decline/void (TC-REFL-03/04).
   - Ожидание: MANAGER + FINANCIER получают `notification.amendment.reopened` с deep-link'ом.
**Ожидаемый результат:** amendment-нотификации effective/reopened доставлены владельцу+FINANCIER,
кликабельны.
_Покрывает:_ R7.3.

### REG-03 — Единственный in-flight анекс на проект
**Предусловия:** проект с одним анексом в `SENT_FOR_SIGNING`.
**Шаги:**
1. Попытаться создать новый `OPEN`-анекс (FOR-06-08) при наличии `SENT_FOR_SIGNING`.
   - Ожидание: отклонено инвариантом FOR-06-08 (не более одного активного).
2. Выполнить reopen текущего (decline/void) и повторить создание/редактирование.
   - Ожидание: после reopen анекс снова `OPEN` и редактируем; одновременно `SENT_FOR_SIGNING` не более
     одного.
**Ожидаемый результат:** в любой момент времени не более одного анекса `SENT_FOR_SIGNING`.
_Покрывает:_ сквозной инвариант (R2.3 + R1.5).

### REG-04 — Сквозной путь: send → decline → edit → resend → sign → EFFECTIVE
**Предусловия:** непустой OPEN-анекс.
**Шаги:**
1. Отправить на подпись → `SENT_FOR_SIGNING` (TC-SFS-01).
2. Decline → анекс `OPEN`, строки целы (TC-REFL-03).
3. Отредактировать строки (FOR-06-08) и снова отправить на подпись → `SENT_FOR_SIGNING`.
4. Полностью подписать → `EFFECTIVE`, `effectiveDate` установлен (TC-REFL-01).
5. Создать ворк-репорт после `effectiveDate` → amended-цена (TC-PRICE-01).
**Ожидаемый результат:** полный жизненный цикл проходит без залипания, дельты применяются один раз,
ценовой шов живой.
_Покрывает:_ сквозная проверка R1.3, R2.2, R2.3, R3.2, R4.2.

---

# Шаблон MD-репорта прогона

## Часть A — API (docker-стек)

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SFS-01 | 1 | POST /api/amendments/{id}/send-for-signing | 200 + status=SENT_FOR_SIGNING + signableDocumentId |  |  |
| 2 | TC-SFS-03 | 1 | POST .../send-for-signing (пустой) | 422 + error.amendment.emptyNotSignable |  |  |
| 3 | TC-SFS-04 | 1 | POST .../send-for-signing (не OPEN) | 409 + error.amendment.notOpen |  |  |
| 4 | TC-REFL-01 | 4 | GET /api/amendments/{id} | status=EFFECTIVE + effectiveDate |  |  |
| 5 | TC-PRICE-01 | 1 | POST /api/work-reports (>= effectiveDate) | clientUnitPrice = base + Σdelta |  |  |
| 6 | TC-SCHED-01 | 3 | GET /api/projects/{id} | endDate = base + round(H/2) |  |  |

## Часть B — UI (браузерный движок)

| # | Тест-кейс | Шаг | Действие | Ожидание | Факт | Статус |
|---|-----------|-----|----------|----------|------|--------|
| 1 | TC-UI-01 | 2 | Клик «Send for signing» | открыт диалог с пикерами метода/уровня |  |  |
| 2 | TC-UI-02 | 1 | Открыть строку SENT_FOR_SIGNING | показан signed/total + outstanding |  |  |
| 3 | TC-UI-03 | 2 | Открыть EFFECTIVE-анекс | показан effectiveDate + статус |  |  |
| 4 | TC-UI-04 | 1 | Открыть Aneksy под FOREMAN | нет действия Send for signing |  |  |

**Итог:** X пройдено / Y провалено / Z пропущено.
**Run-id:** `<timestamp/uuid>`.
**Стратегия повторяемости:** генератор `run-id` + teardown (перевод проекта в терминал, деактивация
созданных пользователей; сид-данные только читаются).
**Окружение:** `docker compose up`, backend `http://localhost:8080`, frontend `http://localhost:3000`,
changelog head `179`.
