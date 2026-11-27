# Тест-кейсы: FOR-05-04-estimate-AI — «Создать смету» с ИИ (прямая запись в смету)

> Редакция после рейка (требования 24–27). Job больше не создаёт оферту: он по одному назначению пишет работы, объёмы, материалы и пометки `ai_meta` прямо в живую смету, а отчёт кладёт в `estimates.ai_summary`. Оферта создаётся позже обычным действием «Подготовить оферту», и отчёт копируется в неё. Кейсы про «оферту, созданную job», удалены.

## Назначение и характер спеки

Спека смешанная: часть A — API-тесты против Docker-стека, часть B — UI-сценарии, исполняемые браузерным движком. Результат прогона — MD-репорты с таблицами (скриншоты не нужны).

Соответствие автотестам (для сверки, не замена Docker-прогона): `EstimateAiWorkerIT` (стадии, видимость между вызовами, отмена, счётчики и лог), `EstimateAiEndToEndIT` (сквозной путь, уведомление, оферта из сметы), `AssignWorkServiceIT` (один вызов `assign_work`), Vitest `EstimateJobBanner.test`, `GoToEstimatePrompt.test`, `useEstimateLiveRefresh.test`, `AiSummaryPanel.test`.

## Запуск окружения

`docker compose up` в корне: `postgres` (:5432), `liquibase`, `backend` (:8080, профиль `docker`), `frontend` (:3000). Базовый URL API: `http://localhost:8080`. Первый ADMIN поднимается переменными `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`. Auth-эндпоинты под `/api/auth`.

## Включение ИИ-сметы в Docker

- В `docker-compose.yml` сервиса `backend` теперь есть `FOREMEN_ESTIMATE_AI_ENABLED` (по умолчанию `true`) и пустые `FOREMEN_ESTIMATE_AI_LLM_{STUB,MODEL,PROJECT,LOCATION,API_KEY,CREDENTIALS_PATH,CREDENTIALS_LOCATION}`. Пустое значение не экспортируется, поэтому незаданные параметры берутся из значений распознавания комнат (`FOREMEN_ROOMS_IMPORT_*`): флаг заглушки и учётные данные общие для всех ИИ-компонентов (rooms-import, dashboard AI, estimate AI).
- Для детерминированного прогона достаточно значений по умолчанию: `FOREMEN_ROOMS_IMPORT_ENABLED=true`, `FOREMEN_ROOMS_IMPORT_LLM_STUB=true` (его наследует и планировщик сметы). Явно включить только заглушку сметы: `FOREMEN_ESTIMATE_AI_LLM_STUB=true`.
- Для живой модели: `FOREMEN_ROOMS_IMPORT_LLM_STUB=false` и ключи `FOREMEN_ROOMS_IMPORT_LLM_API_KEY` или `PROJECT`/`LOCATION` + `CREDENTIALS_*` в `.env`; отдельные `FOREMEN_ESTIMATE_AI_LLM_*` нужны только для переопределения. Кейсы ниже на живой модели не воспроизводимы.
- При `FOREMEN_ESTIMATE_AI_ENABLED=false` бины ИИ-сметы и чата не создаются, все кейсы неприменимы.
- Проверка перед прогоном: `docker compose config | grep FOREMEN_ESTIMATE_AI` и `docker compose logs backend | grep -i "estimate-ai"` (нет ошибок бинов).

### Поведение заглушек

- **Распознавание комнат** (`StubRoomsVisionClient`): сценарий выбирается маркером в промпте/тексте файла; по умолчанию и при `STUB:THREE_ROOMS` — 3 помещения. Модификатор `STUB:SLOW=<сек>` (по умолчанию 20) задерживает измерение каждого помещения, нужен для наблюдения счётчика «N из 3 помещений». Другие маркеры: `STUB:UNKNOWN_TYPE` (ни одной комнаты не сохранить), `STUB:AREA_MISMATCH`, `STUB:OPENINGS_TOO_BIG` и др.
- **Планировщик сметы** (`StubEstimatePlanClient`): берёт **первую** каталожную работу и применяет её к каждому сохранённому помещению с `status=ASSUMED`, `confidence=0.8`, `source={stub.pdf, 1}`, фиксированным `rationale`; одно допущение «Stub assumption». Одно назначение = пара (работа, помещение), поэтому при 3 помещениях `worksTotal = worksDone = 3` (плюс существующие комнаты при `useExistingRooms`). Маркеры: `STUB:FAIL` (недоступность модели, `error.estimateai.failed.llm_unavailable`), `STUB:INVALID` (ответ без `works`, `plan_invalid`), `STUB:EMPTY` (пустой план).
- Заглушка не умеет отклонять назначения, ставить оверрайды объёма и коэффициенты. Эти ветки (допуск объёма, `volumeReason`, `priceReason`, `ASSIGNMENT_REJECTED`) проверяются через `AssignWorkServiceIT` и в Docker не воспроизводятся; в плане ниже они помечены «только IT».
- Задержки между назначениями заглушка не делает. Чтобы наблюдать состояние посреди `ASSIGNING` в Docker, опрашивать `GET .../current` с частотой ≥10 Гц сразу после `roomsSavedAt` либо использовать тестовый профиль с барьером из `EstimateAiWorkerIT`; если окно пропущено, кейс помечается «пропущен (окно не поймано)», а покрытие даёт IT.

### Синтаксис заглушки чата (`StubOfferChatClient`)

- `STUB:CALL <tool> <json-args>`: ровно один вызов инструмента, затем текст `Done (stub).`. Пример: `STUB:CALL set_volume {"workCode":"<code>","roomId":<id>,"quantity":12,"reason":"там ниша"}`.
- Сообщение без маркера: ответ `Stub reply.`, правок нет (`applied=false`).
- `STUB:FAIL`: имитация недоступности модели. Невалидный JSON после имени инструмента: ошибка некорректного ответа.
- Инструменты: `find_work`, `get_offer_state`, `assign_work`, `unassign_work`, `set_volume`, `clear_volume_override`, `adjust_price` (`scope` = `PROJECT` | `WORK_CATEGORY` | `WORK_ITEM`, `targetCode`, `pct` в `[-100, 1000]`, `reason`), `set_line_comment`. Пишущие инструменты требуют непустой `reason`, иначе `error.estimateai.tool.reason_required`.
- Настройки: порог подтверждения `confirm-threshold: 20`, лимит `rate-limit: 60` ходов в час.
- Чат работает только над оффертой (DRAFT), созданной из сметы; `<code>` и `roomId` берутся из снапшота этой оферты.

## Стратегия повторяемости (обязательно, для всех групп)

- **Генератор данных:** `run-id` = timestamp. Проект создаётся на каждый кейс: `AIE-{run-id}-{TC}`; пользователи `aie+{run-id}-{role}@example.com`. Один активный job на проект, поэтому каждый кейс использует свой проект.
- **Teardown:** `DELETE /api/rooms/autoimport/{jobId}` (если job существует), удаление созданных оферт, удаление проекта. Изменения каталога (TC-A1) откатываются возвратом исходного `aiInstructions`.
- Смета, строки, ячейки, материалы и `ai_summary` создаются job в самом проекте и исчезают вместе с проектом; отдельной чистки не требуют.

## Общие предусловия

- Пользователи: ADMIN, MANAGER (права `ROOMS`, `ESTIMATE`, `OFFERS` на чтение и изменение), CLIENT проекта, пользователь без прав `ESTIMATE`.
- Файл `design-plan.txt` (текстовый «дизайн-проект» с описанием помещений) в тестовых ресурсах. Если в проекте мало работ с нормами расхода, у первой каталожной работы должна быть хотя бы одна норма материала (иначе кейсы по материалам помечаются «неприменимо»; в IT норму создаёт фикстура).
- Переменные кейсов: `{pid}` проект, `{jobId}` job, `N` = число сохранённых помещений (`roomsCreated`), матрица сметы: `GET /api/estimates/project/{pid}/matrix`.

---

# Часть A. API

## Фича A1. Инструкции ИИ в каталоге работ (без изменений по смыслу)

### TC-A1-01. Сохранение и чтение aiInstructions у категории и у работы
**Предусловия:** ADMIN, существующая категория и работа каталога.
1. `PUT /api/work-categories/{id}` с `aiInstructions="Гидроизоляция: пол и стены на полную высоту"` → 200, поле равно отправленному.
2. `PUT /api/work-items/{id}` с `aiInstructions="Зашивка WC до 120x100"` → 200.
3. `GET` обеих сущностей → поля сохранены.
4. `PUT` с 8001 символом → 400.
5. `PUT` с пустой строкой/пробелами → 200, значение `null`.
**Teardown:** вернуть исходные значения.

### TC-A1-02. Клиентские представления не содержат aiInstructions
1. CLIENT: `GET /api/client/offers/...` (оферта проекта в статусе SENT) → в теле нет ни одного поля, начинающегося с `ai`.
2. PDF оферты для клиента не содержит текста инструкции.

### TC-A1-03. Сид инструкций категорий идемпотентен
1. В БД у 13 категорий `ai_instructions` не пусто.
2. Изменить значение у одной категории, перезапустить `liquibase` → значение сохранилось.

## Фича A2. Старт job, режимы, права

### TC-A2-01. Старт в режиме ESTIMATE, порядок стадий
1. MANAGER: `POST /api/rooms/project/{pid}/autoimport` multipart: `mode=ESTIMATE`, `prompt`, файл `design-plan.txt` → 202, `{jobId, status}`.
2. Опрашивать `GET /api/rooms/autoimport/{jobId}` (до 60 с) → статусы идут `EXTRACTING → DETECTING_ROOMS → CLASSIFYING → MEASURING → VALIDATING → SAVING_ROOMS → PLANNING_WORKS → ASSIGNING → FINALIZING → COMPLETED`; стадий `QUANTIFYING` и `BUILDING_OFFER` нет ни разу.
3. В `COMPLETED`: `mode=ESTIMATE`, `phase=ESTIMATE`, `roomsCreated=N≥1`, `roomsSavedAt` не null, `worksDone ≥ 1`, `worksTotal = worksDone`, `result.estimate.report.summary` не null.
4. В ответе нет поля `offerId` ни на верхнем уровне, ни в `result.estimate`.

### TC-A2-02. Нет прав на ESTIMATE
1. Пользователь без `ESTIMATE UPDATE`: старт с `mode=ESTIMATE` → 403, job в БД нет.
2. Он же с `mode=ROOMS_ONLY` → 202 (поведение FOR-05-02).

### TC-A2-03. Один активный job на проект
1. Старт ESTIMATE → 202.
2. Сразу второй старт (любой mode) → 429 `error.roomsimport.in_progress`, заголовок `Retry-After`.

### TC-A2-04. Смета заблокирована (не DRAFT)
1. Перевести смету проекта в состояние «не DRAFT».
2. Старт ESTIMATE → 409 `error.estimate.locked`, job не создан.

### TC-A2-05. useExistingRooms
1. Создать 1 комнату вручную.
2. Старт `mode=ESTIMATE`, `useExistingRooms=true` → 202; стадии распознавания и `SAVING_ROOMS` пропущены (журнал стадий: `PLANNING_WORKS, ASSIGNING, FINALIZING`), `roomsCreated=0`, `worksDone=1`, ячейка в смете для существующей комнаты.
3. Проект без комнат + `useExistingRooms=true` → 400 `error.estimateai.no_rooms`.

### TC-A2-06. Неизвестный режим
1. `mode=FOO` → 400, job не создан.

## Фича A3. Запись в живую смету во время job

### TC-A3-01. Комнаты видны до завершения
1. Старт `ESTIMATE` с `STUB:THREE_ROOMS STUB:SLOW=3`, опрашивать `GET .../current`.
2. Как только `roomsSavedAt` не null: `GET /api/rooms/project/{pid}` → `N` комнат с `aiImported=true`, статус job ещё не `COMPLETED`; оферт в проекте нет (`GET /api/offers/project/{pid}` пусто).

### TC-A3-02. Строки, ячейки, материалы и ai_meta появляются по ходу job
Предусловия: у первой каталожной работы есть норма материала.
1. Опрашивать `.../current` и матрицу сметы в цикле до терминального статуса, сохраняя `worksDone` и число ячеек ИИ.
2. Число ячеек работы ИИ в матрице монотонно растёт и в любой момент опроса равно `worksDone` текущего ответа (±1 в окне между коммитом и обновлением счётчика; ячейка видна не позже, чем увеличен счётчик в следующем опросе).
3. В итоге: одна строка сметы для работы ИИ (`line`), `N` ячеек; у **каждой** ячейки есть `ai` (`origin=JOB`, `rationale`, `status=ASSUMED`, `confidence=0.8`, `source`), и у каждой ячейки есть минимум одна строка материала из нормы (`quantity = normQty × объём ячейки`).
4. Ячейка не бывает без материалов: ни в одном опросе ячейка с `ai` не появляется с пустым списком материалов, если у работы есть нормы.
5. Для работы без норм: `materials=[]`, ошибок нет (IT: `AssignWorkServiceIT.committedAndVisible`).

### TC-A3-03. Запись заблокирована, чтение разрешено
Пока job активен (до терминального статуса):
1. `PUT /api/rooms/{id}` → 409 `error.project.ai.locked`, `params.jobId`, `params.stage`.
2. `POST /api/rooms/project/{pid}/save` → 409.
3. `POST /api/estimates/project/{pid}/assignments` → 409 `error.project.ai.locked`, `params.jobId = {jobId}`.
4. `POST /api/estimates/project/{pid}/labour-price-adjustment` → 409.
5. `POST /api/offers/{id}/restore` и `POST /api/offers/project/{pid}/prepare` → 409.
6. `GET` комнат, матрицы, списка оферт → 200.
7. После терминального статуса те же записи не дают 409.

### TC-A3-04. ROOMS_ONLY ничего не блокирует
1. Старт `mode=ROOMS_ONLY` → при активном job `PUT /api/rooms/{id}` существующей комнаты → не 409.

### TC-A3-05. Отмена на PLANNING_WORKS (до назначений)
1. Старт ESTIMATE с блокирующим планировщиком (тестовый профиль) либо с `STUB:SLOW`; отменить, когда статус `PLANNING_WORKS`.
2. `DELETE /api/rooms/autoimport/{jobId}` или cancel-эндпоинт → 204.
3. Комнаты остались (`N`), `roomsSavedAt` не null, блокировка снята (`PUT` комнаты → 200), оферт нет, уведомлений `notification.estimateAi.*` нет.
4. `ai_summary` не записан (нет назначений).

### TC-A3-06. Отмена во время ASSIGNING сохраняет строки
1. Старт ESTIMATE; поймать статус `ASSIGNING` при `worksDone ≥ 1` и `worksDone < worksTotal` (окно ловится тестовым профилем с барьером; иначе «пропущен», покрытие — `EstimateAiWorkerIT.assignmentsVisibleBetweenCallsAndCancelKeepsLines`).
2. Отмена → 204.
3. Дальнейших назначений нет: число ячеек ИИ остаётся равным числу, зафиксированному в момент отмены (повторный `GET matrix` через 2 с даёт то же).
4. Уже назначенные строки, ячейки, материалы и `ai` остались; `estimates.ai_summary.incomplete=true`, summary содержит `assignments.done ≥ 1`.
5. Блокировка снята (`PUT` комнаты → 200), `GET .../current` → 204, оферт нет, уведомлений нет.

### TC-A3-07. Сбой модели после сохранения комнат
1. Старт с `STUB:THREE_ROOMS STUB:FAIL` → job `FAILED`, `failureReason=error.estimateai.failed.llm_unavailable`.
2. `roomsCreated ≥ 1`, комнаты сохранены, назначений нет, оферт нет, проект разблокирован, `ai_summary` не записан (нет назначений), уведомление `notification.estimateAi.failed` ровно одно.

### TC-A3-08. Невалидный план, нет комнат, пустой план
1. `STUB:INVALID` → `FAILED`, `error.estimateai.failed.plan_invalid`.
2. `STUB:UNKNOWN_TYPE` на проекте без комнат → `FAILED`, `error.estimateai.failed.no_rooms`, ничего не создано.
3. `STUB:EMPTY` → `COMPLETED`, `worksDone=0`, оферт нет; ячеек ИИ нет.

### TC-A3-09. Повтор (работа, помещение) обновляет ячейку (только IT)
`AssignWorkServiceIT.idempotent`: повторный вызов даёт одну строку, одну ячейку, последний объём, без лишних материалов. В Docker не воспроизводится заглушкой; пропуск фиксируется в отчёте.

### TC-A3-10. Отклонения, допуск объёма, коэффициент (только IT)
`AssignWorkServiceIT.volumeOverride`, `coefficientOnlyWhenStated`, `rejections`, `onlyTheActiveOwningJobWrites`: оверрайд объёма только вне допуска с `volumeReason`, цена меняется только при явном коэффициенте, отклонённый вызов ничего не пишет, чужой/завершённый job писать не может.

## Фича A4. Счётчики и журнал действий в poll DTO

### TC-A4-01. Счётчики
1. `GET .../{jobId}` в процессе: на стадии `MEASURING` `roomsTotal` задан после `DETECTING_ROOMS`, `roomsDone` растёт от 0 до `roomsTotal`; `worksTotal = null` до конца `PLANNING_WORKS`.
2. На `ASSIGNING`: `worksTotal` задан, `worksDone` растёт на 1 за коммит.
3. В `COMPLETED`: `roomsDone = roomsTotal`, `worksDone = worksTotal`.
4. Неизвестный на стадии счётчик равен `null` (не 0).

### TC-A4-02. Журнал действий
1. В `COMPLETED` `actionLog[*].code` содержит `ROOM_MEASURED`, `ROOM_SAVED`, `PLAN_READY`, `WORK_ASSIGNED`, `SUMMARY_WRITTEN` (и `MATERIALS_SEEDED`, если у работы есть нормы); `actionLog[0].code = SUMMARY_WRITTEN` (новые сверху).
2. Каждая запись `{at, stage, code, params}`; `params` у `WORK_ASSIGNED` содержит код и название работы, помещение, объём, единицу; текста документа нет (рекурсивная проверка: нет полей `text`, `content`).
3. Число записей `WORK_ASSIGNED` = `worksDone`; в смете для каждой записи есть ячейка (журнал не опережает коммит).
4. Журнал ограничен 200 записями (проверка в IT `JobActionLogTest`; в Docker — только `size ≤ 200`).
5. `GET .../current` во время job возвращает те же поля; для CLIENT эндпоинт job недоступен (403/404).

## Фича A5. Пометки ai_meta в каждой ячейке (Требование 21 в рамке прямой записи)

### TC-A5-01. Ячейка по формуле
Предусловия: job завершён, у работы ИИ есть применимая формула.
1. `GET matrix` (MANAGER): ячейка работы ИИ содержит `ai`: `origin=JOB`, непустой `rationale`, `volume.source=FORMULA`, `formulaKey`, `finalVolume = formulaVolume`, `correction` отсутствует; `volumeOverridden=false`.
2. `ai.source = {file:"stub.pdf", page:1}`, `status=ASSUMED`, `confidence=0.8`.

### TC-A5-02. Объём без формулы
1. Работа без применимой формулы → `volume.source=UNIT_FALLBACK`.

### TC-A5-03. Поправка модели и наценка (только IT)
`AssignWorkServiceIT.volumeOverride` и `coefficientOnlyWhenStated`: `volume.source=MODEL_OVERRIDE`, `correction.delta/pct`, `adjustmentReason`; `price` только при явном коэффициенте.

### TC-A5-04. Права на пометки
1. ADMIN, MANAGER: `ai` и `aiSummary` в матрице есть.
2. CLIENT проекта: `GET matrix` (если доступен) или клиентские представления не содержат ни одного ключа, начинающегося с `ai` (рекурсивная проверка), включая `aiSummary`.

### TC-A5-05. Ручная правка снимает пометку
1. `POST /api/estimates/project/{pid}/assignments` с `SET_QUANTITY` для одной ИИ-ячейки (после завершения job) → у неё `ai` отсутствует, у остальных сохранён.
2. Ручной `ADJUST_LABOUR_PRICE` по категории → у ячеек категории исчез блок `ai.price` (если был), остальное сохранено.
3. `estimates.ai_summary` после этих правок не изменился (`generatedAt` тот же).

## Фича A6. Материалы из норм (Требование 23/24.4)

### TC-A6-01. Материал по ячейке при назначении
1. После `COMPLETED` в матрице у каждой ячейки ИИ строки материалов совпадают с нормами работы: тип, ветка (`branch`), `normQty`, диапазон цен; `quantity = normQty × итоговый объём ячейки`.
2. Число строк материалов ≥ числа ячеек (если у работы норма одна, равно числу ячеек).
3. `ai.materials = {seededFromNorms:true, lines:n, overridden:0}`, n равно числу строк в ячейке.

### TC-A6-02. Паритет с ручной сметой
1. Проект P1: job завершён.
2. Проект P2 (те же помещения и размеры, `useExistingRooms` не нужен): вручную `POST /api/estimates/project/{pid}/assignments` с теми же `ASSIGN` для тех же пар (работа, помещение).
3. Матрицы P1 и P2: те же строки, объёмы, цены труда, **те же строки материалов в каждой ячейке** (тип, ветка, норма, диапазон, количество), те же итоги; отличаются только `ai`.

### TC-A6-03. Работа без норм и пакет
1. Работа без норм: `materials=[]`, ошибок нет, `ai_summary.summary.materials.worksWithoutNorms` содержит её код.
2. Старт с `packageCode=COMFORT` → материалы чистовых типов с диапазонами пакета COMFORT; применённый пакет сметы COMFORT. Без `packageCode` — диапазоны «без пакета» (как у ручного назначения).

### TC-A6-04. Выбор материалов не за ИИ
1. После job у строк материалов нет выбранного продукта; в чате запрос «добавь материал» → `applied=false`.

### TC-A6-05. Вкладки-потребители
1. Список материалов (FOR-05-05b), BoM и маржи (FOR-05-06) проекта после job возвращают строки без ошибок, маржи рассчитаны.

## Фича A7. Сводка ИИ в смете и копирование в оферту (Требование 25)

### TC-A7-01. ai_summary после завершения
1. `GET matrix` (MANAGER) → `aiSummary` не null: `assumptions[]` (одно «Stub assumption»), `questions[]`, `unpriced[]`, `warnings[]`, `incomplete=false` (если `unpriced` пуст), `summary {lines, cells, totalNet, byCategory[], materials{lines, cellsWithMaterials, overriddenLines, worksWithoutNorms[]}, assignments{done, rejected}}`, `promptVersion`, `jobId={jobId}`, `generatedAt`.
2. `summary.cells = worksDone`, `summary.assignments.done = worksDone`, `summary.materials.lines` = числу строк материалов из TC-A6-01.

### TC-A7-02. Повторный job заменяет сводку
1. В том же проекте разблокировать (job завершён), запустить второй job `useExistingRooms=true` → `aiSummary.jobId` = новый job, `generatedAt` новее; значение одно (не список).

### TC-A7-03. Частичная сводка при отмене/сбое
1. После TC-A3-06: `aiSummary.incomplete=true`, `assignments.done` = числу сохранённых назначений.
2. Сбой планирования без назначений (TC-A3-07): `aiSummary` не меняется/не создаётся.

### TC-A7-04. Копирование в оферту при подготовке
1. После `COMPLETED`: `POST /api/offers/project/{pid}/prepare` → 200; в проекте ровно 1 оферта, `DRAFT`.
2. `offers.ai_generated = true`; `ai_report.jobId = {jobId}`; `ai_report.generatedAt = aiSummary.generatedAt`.
3. `GET /api/offers/{offerId}/snapshot`: число ячеек с `ai` = `worksDone`; число строк `materials[]` в ячейках = числу строк материалов сметы (TC-A6-01).

### TC-A7-05. Без пометок и сводки копирования нет
1. Проект без job: ручная смета → `prepare` → `ai_generated=false`, `ai_report=null`.
2. Проект, где пометки сняты ручными правками, но `ai_summary` есть → оферта `ai_generated=true`, `ai_report` скопирован.

### TC-A7-06. Обновление оферты ИИ более новой сводкой
1. Оферта `aiGenerated` создана по job №1; запустить job №2; обновить оферту из сметы (`refresh`) → `ai_report.jobId` = job №2.

### TC-A7-07. Видимость оферты и сводки
1. MANAGER и ADMIN: `GET /api/offers/project/{pid}` → оферта есть, `aiGenerated=true`.
2. CLIENT проекта: `GET /api/offers/{offerId}` → 404; после отправки (`POST /api/offers/{id}/send`) клиент видит оферту без полей `ai*`, без отчёта и чата; `GET .../ai-chat` от CLIENT → 404.

### TC-A7-08. Восстановление и паритет
1. Изменить смету (убрать одну ячейку), `POST /api/offers/{offerId}/restore` → 200, `skippedMaterials=0`.
2. Матрица после restore совпадает с матрицей на момент `prepare`: те же ячейки, объёмы, цены, строки материалов, пометки `ai`; итоги равны итогам снапшота.
3. Пометки переносятся: `ai.rationale`, `volume.source` те же.

### TC-A7-09. Сравнение, PDF
1. `GET /api/offers/{offerId}/snapshot` → 200; `GET /api/offers/{offerId}/pdf` → 200, `application/pdf`; `GET /api/offers/compare-many` с оффертой ИИ и второй → 200.

### TC-A7-10. Снапшот версии 2 и обычная оферта
1. Оферта, подготовленная до спеки (снапшот v2): `snapshot` → 200 без `ai`, `restore` работает, материалы не создаются, ошибок нет.
2. Новая обычная оферта (без ИИ): снапшот содержит `materials[]`; restore возвращает материалы с оверрайдами.

### TC-A7-11. Клиент не видит материалов и объяснений
1. После отправки оферты клиенту: клиентский ответ и клиентский PDF не содержат `ai*`, `rationale`, `formulaVolume`, `adjustmentReason`, `materials`, диапазонов цен материалов, `manualQty`, продуктов (рекурсивная проверка по ключам).

## Фича A8. Чат оферты (на оферте, созданной из сметы)

Предусловия: оферта DRAFT из TC-A7-04; `{code}` и `{roomId}` из снапшота.

### TC-A8-01. Простая правка объёма
1. `POST /api/offers/{id}/ai-chat` `{message:"STUB:CALL set_volume {\"workCode\":\"{code}\",\"roomId\":{roomId},\"quantity\":12,\"reason\":\"user asked\"}", clientMessageId}` → 200, `applied=true`, версия снапшота +1.
2. Снапшот: ячейка = 12, `volumeOverridden=true`, итоги пересчитаны; `GET .../ai-chat` → 2 сообщения (USER, ASSISTANT).
3. Живая смета проекта **не изменилась** (чат правит только снапшот).

### TC-A8-02. Причина записывается в ячейку
1. `reason="там ниша"` → в ячейке `ai.origin=CHAT`, `ai.turnId` = id хода, `ai.rationale` отражает указание, `volume.source=CHAT_OVERRIDE`, `formulaVolume` — значение формулы.
2. Неизменённые ячейки сохраняют `origin=JOB`.

### TC-A8-03. Наценка на категорию
1. `STUB:CALL adjust_price {"scope":"WORK_CATEGORY","targetCode":"<код категории>","pct":10,"reason":"user asked"}` → `applied=true`, у строк категории `unitPriceOverride = unitPrice × 1.10`.

### TC-A8-04. Подтверждение широкой правки
1. `STUB:CALL adjust_price {"scope":"PROJECT","pct":-5,"reason":"user asked"}` → `applied=false`, `pendingAction` с итогом до/после.
2. Повтор с `confirmPendingId` → `applied=true`, итог как в preview.
3. Новое сообщение без подтверждения аннулирует pending.

### TC-A8-05. Откат
1. `POST /api/offers/{id}/ai-chat/undo` → 200, снапшот равен состоянию до последнего хода.
2. Повторный undo → 409 `error.estimateai.chat.undo_stale`.

### TC-A8-06. Материалы и оверрайды в чате
1. Изменить объём ячейки через чат → у неоверрайженных материалов `quantity` пересчитано; `manualQty`/`qtyOverridden` и выбранный продукт сохранены.

### TC-A8-07. Ограничения инструментов
1. `STUB:CALL delete_rooms {}`, `STUB:CALL send_offer {}` → `applied=false`, комнаты и статус оферты не изменились.
2. `set_volume` с несуществующим `workCode` → `applied=false`.
3. `adjust_price` с `pct=1001` → `error.estimateai.tool.invalid_pct`; без `reason` → `error.estimateai.tool.reason_required`.
4. `STUB:FAIL` → ответ об ошибке модели, снапшот не изменён.

### TC-A8-08. Права, состояние, блокировка
1. Без `ESTIMATE UPDATE`: `POST ai-chat` → 403.
2. Оферта SENT: 409 `error.offer.not.editable`.
3. Во время активного ESTIMATE job (в другом проекте не блокируется, в этом — да): `POST ai-chat` → 409 `error.project.ai.locked`.

### TC-A8-09. Конкурентность, новая сессия, лимит
1. Два одновременных `POST` → один 200, второй 409 `error.estimateai.chat.busy`.
2. Другой токен MANAGER: `GET .../ai-chat` → видна вся история.
3. 61 ход за час → 61-й 429 `error.estimateai.chat.rate`.

## Фича A9. Уведомления (ссылка на вкладку Сметы)

### TC-A9-01. Готово
1. Старт ESTIMATE пользователем MANAGER, ожидание `COMPLETED`.
2. `GET /api/notifications` (MANAGER) → ровно одно `notification.estimateAi.completed`, `deepLink=/projects/{pid}/estimate`; нет `notification.roomsImport.*`; повторный опрос через 1 с — всё ещё одно.
3. Другие участники проекта уведомления не получили.

### TC-A9-02. Ошибка и отмена
1. Сбой модели → одно `notification.estimateAi.failed`, `body=error.estimateai.failed.llm_unavailable`, deepLink `/projects/{pid}/rooms`.
2. Отмена → уведомлений нет.

---

# Часть B. UI (браузерный движок)

Общие предусловия: стек поднят, пользователь MANAGER, проект `AIE-{run-id}`, локаль PL, затем повтор ключевых кейсов в RU. Для наблюдения счётчика помещений использовать `STUB:SLOW=3` в промпте. Селекторы: `estimate-job-banner`, `estimate-job-stage`, `estimate-job-progress-text`, `estimate-job-actions-toggle`, `estimate-job-actions-list`, `estimate-job-open-estimate`, `go-to-estimate-prompt`, `go-to-estimate-action`, `go-to-estimate-dismiss`, `ai-summary-generated-at`.

Примечание о текстах прогресса: счётчик помещений («2 z 3 pomieszczeń gotowych» / «Готово помещений: 2 из 3») ловится в Docker с `STUB:SLOW`. Счётчик работ при заглушке растёт за доли секунды, поэтому в Docker проверяется итоговое «3 z 3 prac przypisanych». Тексты с «4 из 6 помещений» и «25 из 200 работ» проверяются через Vitest (`EstimateJobBanner.test`) и мок опроса; в отчёте это отмечается как «покрыто Vitest».

## Фича B1. Кнопка «Создать смету» и диалог

### TC-B1-01. Кнопка и диалог
1. Открыть `/projects/{pid}/rooms` → кнопка «Utwórz kosztorys» (RU «Создать смету»); текста «Generate rooms» нет.
2. Нажать → диалог: промпт, файлы, имя оферты (если поле осталось в UI, оно не влияет на job: оферта не создаётся), выбор пакета (по умолчанию применённый пакет сметы или «без пакета»), примечание о длительности; переключателя «использовать существующие комнаты» нет в пустом проекте.
3. Загрузить `design-plan.txt`, «Start» → диалог закрыт, на вкладке виден баннер job.

### TC-B1-02. Переключатель существующих комнат
1. Создать комнату вручную → в диалоге появился переключатель.

## Фича B2. Общий баннер на вкладках «Помещения» и «Смета»

### TC-B2-01. Баннер на Rooms: стадия, прогресс по помещениям
1. После старта с `STUB:SLOW=3` на вкладке «Pomieszczenia» виден `estimate-job-banner`: подпись стадии на `MEASURING`, текст прогресса «{done} z {total} pomieszczeń gotowych», полоса прогресса (`aria-valuenow/aria-valuemax`).
2. На неопределённых стадиях (`EXTRACTING`, `PLANNING_WORKS`, `FINALIZING`) показана только подпись шага без полосы.
3. Кнопки правки комнат, редактор, загрузка медиа — **disabled (видны)**, сообщение о блокировке «Kosztorys jest tworzony…».
4. Кнопка «Cancel» есть, пока job активен.

### TC-B2-02. Раскрываемый список действий
1. По умолчанию список свёрнут: `estimate-job-actions-toggle` с текстом «Pokaż działania ({n})», `aria-expanded=false`, списка нет.
2. Клик (и клавиатура: Tab, Enter/Space) → список раскрыт, `aria-expanded=true`, в нём локализованные записи: «Pomieszczenie „…": zmierzone», «Przypisano {код} {название} do pomieszczenia „…" ({объём} {ед.})», «Zastosowano normy materiałów…», «Plan prac gotowy: {n} prac»; новые сверху.
3. Дождаться нескольких опросов: список остаётся раскрытым и дополняется (состояние сохраняется между опросами).
4. «Ukryj działania» сворачивает; записи с неизвестным кодом показываются как «Działanie: {code}», без падения.
5. В RU те же записи на русском («Помещение «…»: измерено», «Назначено …»).

### TC-B2-03. Одинаковый баннер на вкладке «Смета»
1. Во время job открыть вкладку «Kosztorys»: тот же `estimate-job-banner` (тот же текст стадии, счётчики, список действий, кнопка «Cancel»); раскрытое состояние списка в рамках сессии не ломается переходом между вкладками «Pomieszczenia» ↔ «Kosztorys» (допустимо свёрнутое после перехода, но без ошибок).
2. Сравнить DOM баннера на двух вкладках: идентичная структура и тексты.
3. Элементы записи на вкладке «Kosztorys» (staging, применение работ, сохранение) disabled, видны, с баннером блокировки.

### TC-B2-04. Баннера нет на вкладке «Оферта»
1. Во время job открыть вкладку «Oferta»: `estimate-job-banner` отсутствует.
2. Панель чата отключена с сообщением о блокировке; кнопки записи оферты disabled.

### TC-B2-05. Новая сессия
1. Во время job открыть проект в новом окне/сессии → баннер, прогресс и блокировка видны (через current-job).

### TC-B2-06. Завершение
1. По `COMPLETED` баннер показывает «Kosztorys gotowy», счётчики (вопросы/неоценённые/назначено работ) и ссылку «Otwórz kosztorys» (`estimate-job-open-estimate`) → `/projects/{pid}/estimate`; ссылки на оферту нет.
2. Блокировка снята, элементы активны. Закрытие баннера (`estimate-job-dismiss`) скрывает его.

### TC-B2-07. Отмена и ошибка
1. «Cancel» на `PLANNING_WORKS` → сообщение «сохранено N комнат», кнопка «Повторить» и вариант «использовать существующие комнаты».
2. «Cancel» на `ASSIGNING` (окно ловится при замедлении; иначе покрыто Vitest): назначенные строки остаются в смете; баннер показывает отмену и «Открыть смету».
3. Заглушка `STUB:FAIL` → баннер ошибки с причиной и «Повторить»; `estimate-job-rooms-saved` с числом сохранённых комнат.

### TC-B2-08. CLIENT и проект без job
1. Войти как CLIENT проекта → баннера нет на любой вкладке.
2. Проект без job → баннера нет.

## Фича B3. Приглашение перейти к смете

### TC-B3-01. Появление один раз за job
1. Стоя на вкладке «Pomieszczenia» во время job, дождаться `roomsSavedAt` → неблокирующее уведомление `go-to-estimate-prompt` («Pomieszczenia są gotowe. Otwórz kosztorys, aby zobaczyć, jak powstaje.»), не модальное: фон остаётся доступным, страницу можно прокручивать.
2. «Przejdź do kosztorysu» → открывается вкладка «Kosztorys», баннер job виден.
3. Вернуться на «Pomieszczenia»: приглашение для этого job не появляется повторно (один раз за job).

### TC-B3-02. Закрытие
1. Новый job: закрыть приглашение (`go-to-estimate-dismiss`) → исчезло, в рамках job не возвращается после очередных опросов.
2. Новый следующий job того же проекта → приглашение показывается снова.

## Фича B4. Живая вкладка «Смета» во время job

### TC-B4-01. Строки появляются без перезагрузки
1. Открыть вкладку «Kosztorys» сразу после `roomsSavedAt` (для наблюдения использовать барьер/замедление планировщика; если окно не поймано — проверять итоговое состояние).
2. Новые строки, объёмы, строки материалов и маркеры «AI» появляются без перезагрузки страницы (обновление раз в ~2 с и при изменении `worksDone`); число ячеек ИИ в матрице растёт вместе со счётчиком баннера.
3. Прокрутка матрицы не сбрасывается между обновлениями; открытая карточка «Почему» остаётся открытой и не закрывается обновлением.
4. После терминального статуса происходит последний refetch, дальнейших запросов матрицы нет (проверка по сети: запросы `.../matrix` прекращаются в течение 5 с).

### TC-B4-02. Только чтение
1. Во время job элементы staging, применения работ, сохранения — disabled, не скрыты; показан баннер блокировки.
2. После завершения элементы активны.

### TC-B4-03. Маркер и карточка «Почему» (Требование 22)
1. В ячейках ИИ угловой маркер «AI»/«ИИ»; в ячейках без `ai` маркера нет.
2. Клик по маркеру ячейки с формулой → карточка: обоснование; строка «Formuła {ключ}: {значение}»; статус, уверенность, источник (файл `stub.pdf`, страница 1); «Utworzone przez job kosztorysu».
3. Строка «Materiały: n pozycji z norm zużycia» (RU «Материалы: n позиций из норм расхода»); n равно числу строк материалов в ячейке.
4. Ячейка без обоснования → плейсхолдер «AI не дал обоснования для этой ячейки».
5. Клавиатура: Tab до маркера, Enter открывает, Esc закрывает; `aria-label` со статусом.
6. Фильтры «Только ячейки ИИ» и «Только скорректированные» работают как в Требовании 22.

### TC-B4-04. Ручная правка в UI снимает маркер
1. После job изменить объём ИИ-ячейки вручную → в предпросмотре маркер исчез; после Save остаётся снятым; у остальных ячеек маркеры на месте; панель сводки не изменилась (виден прежний `generatedAt`).

## Фича B5. Панель сводки ИИ на вкладке «Смета»

### TC-B5-01. Панель
1. После job на вкладке «Kosztorys» виден сворачиваемый блок «Podsumowanie AI kosztorysu» с `generatedAt` (`ai-summary-generated-at`); списки допущений («Stub assumption»), вопросов, неоценённых позиций, предупреждений, количества строк/ячеек, итоги, сводка материалов (строки, ячейки с материалами, оверрайды, работы без норм).
2. Индикатор «итог неполный» виден при `incomplete=true` (после отмены на `ASSIGNING`) и отсутствует при полном job.
3. Клик по строке сводки с `workCode` прокручивает матрицу к строке.
4. Блок есть для ADMIN и MANAGER, отсутствует для CLIENT (в DOM нет текста сводки).

### TC-B5-02. Сводка после ручных правок
1. Снять пометки ручными правками (TC-B4-04) → панель сводки остаётся с прежним временем генерации.

## Фича B6. Оферта из сметы и отчёт ИИ

### TC-B6-01. Создание оферты
1. На вкладке «Oferta» создать оферту из сметы → карточка с бейджем «AI»/«ИИ» и подсказкой.
2. Панель отчёта оферты показывает те же допущения, вопросы, неоценённые, предупреждения; индикатор «итог неполный» совпадает со сводкой сметы.
3. Матрица снапшота: те же маркеры «AI» и карточки «Почему», что в смете; строки материалов ячеек с отметкой оверрайда количества для сотрудника; в клиентском просмотре строк материалов нет.

### TC-B6-02. Действия над оффертой
1. «Восстановить смету из оферты» → матрица сметы показывает те же строки, материалы и маркеры.
2. «Сравнить» → экран сравнения открывается; «PDF» → скачивается.
3. «Отправить» → одноразовое предупреждение для оферты ИИ с открытыми вопросами; «Продолжить» отправляет.

### TC-B6-03. Клиент
1. CLIENT не видит DRAFT-оферту ИИ; после отправки нет бейджа, отчёта, маркеров и обоснований (в DOM нет `ai*` текстов).

## Фича B7. Чат оферты

### TC-B7-01. Правка через чат
1. Вкладка «Oferta», оферта DRAFT: панель чата, история пуста, чипы примеров.
2. Ввести `STUB:CALL adjust_price {"scope":"WORK_CATEGORY","targetCode":"<код>","pct":10,"reason":"marża"}` → «Pracuję…», затем ответ ассистента и сводка изменений.
3. Итоги оферты и матрица снапшота обновились без перезагрузки.

### TC-B7-02. Подтверждение и отмена
1. `STUB:CALL adjust_price {"scope":"PROJECT","pct":-5,"reason":"user asked"}` → карточка подтверждения с итогом до/после.
2. «Отмена» — без изменений; повторить и «Подтвердить» — итог изменился.

### TC-B7-03. Undo и ограничения
1. «Отменить последнее изменение» возвращает снапшот и итоги; кнопка исчезает.
2. Обновить страницу — история на месте.
3. Оферта SENT — панель отключена с сообщением; во время активного job — отключена с сообщением о блокировке.

## Фича B8. Каталог

### TC-B8-01. Поле «Правила расчёта для ИИ»
1. «Kategorie prac» → многострочное поле со счётчиком (до 8000), сохранить → индикатор «заполнено».
2. «Katalog prac» → то же.
3. Более 8000 символов → ошибка валидации, сохранение недоступно.

## Фича B9. Уведомления в колокольчике

### TC-B9-01. Тексты и переход на смету
1. После завершения job в колокольчике «Kosztorys AI gotowy» (RU «Смета ИИ готова»); клик открывает вкладку **«Kosztorys»** (`/projects/{pid}/estimate`), не «Oferta».
2. Ошибка: «Tworzenie kosztorysu przez AI nie powiodło się» / «Создание сметы ИИ не удалось», клик ведёт на вкладку «Pomieszczenia».

## Фича B10. Локализация

### TC-B10-01. Паритет PL/RU
1. Пройти B1–B9 в PL и RU: нет сырых ключей вида `estimateJob.…`, `estimateAi.…`, нет смешения языков; тексты счётчиков и действий соответствуют шаблонам обеих локалей.

---

# Регрессия

### TC-R-01. Режим ROOMS_ONLY (FOR-05-02)
Старт без `mode` → draft комнат, ручное сохранение через `POST /api/rooms/project/{pid}/save`, уведомления `notification.roomsImport.*`, проект не блокируется, стадий `ASSIGNING`/`FINALIZING` нет, `result` без `estimate`, смета не тронута.

### TC-R-02. Ручная смета
Ручное применение работы к комнате, оверрайд объёма, `labour-price-adjustment`, Save — без изменений (проект без активного job). Материалы засеиваются теми же нормами, что у job (паритет TC-A6-02).

### TC-R-03. Снапшоты оферт (FOR-05-07b)
Подготовка оферты из сметы, обновление, восстановление из обычной (не AI) оферты, сравнение, PDF — без изменений; оверрайд количества материала и выбранный продукт сохраняются при prepare → restore.

### TC-R-04. Клиентский просмотр оферт
Оферты, подготовленные вручную, отображаются клиенту так же, как раньше; в клиентских моделях нет свойств `ai*`, включая `aiSummary`.

### TC-R-05. Каталог работ
Создание и редактирование категорий и работ без `aiInstructions` — как прежде.

### TC-R-06. Матрица сметы без ИИ
Проект без ИИ-ячеек: нет маркеров, фильтры ИИ скрыты/неактивны, панели сводки нет, ручной оверрайд и восстановление из обычной оферты работают.

### TC-R-07. Устаревшие статусы и ссылки
1. В БД и API нет job в статусах `QUANTIFYING`/`BUILDING_OFFER` (миграция перевела зависшие в `FAILED`, `error.estimateai.failed.superseded`).
2. Старые уведомления `notification.estimateAi.completed` со ссылкой на оферту открываются без ошибки (страница оферты).

### TC-R-08. Старые AI-оферты
Оферты, созданные job до рейка (`aiGenerated=true`, `ai_report` задан), открываются, восстанавливаются, чат работает; панель отчёта показывается.

---

# Шаблон MD-репорта прогона

**Run-id:** `<timestamp>` | **Стратегия повторяемости:** генератор имён + teardown | **Окружение:** docker compose, LLM stub, `FOREMEN_ESTIMATE_AI_ENABLED=true`

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-A2-01 | 1 | POST /api/rooms/project/{pid}/autoimport mode=ESTIMATE | 202 + jobId | | |
| 2 | TC-A3-03 | 3 | POST /api/estimates/project/{pid}/assignments при активном job | 409 error.project.ai.locked | | |
| 3 | TC-A3-02 | 3 | GET matrix после COMPLETED | N ячеек с ai и материалами | | |
| 4 | TC-A7-04 | 2 | POST /api/offers/project/{pid}/prepare | ai_generated=true, ai_report.jobId={jobId} | | |
| 5 | TC-B2-03 | 1 | Открыть вкладку «Kosztorys» во время job | Тот же баннер, что на «Pomieszczenia» | | |

Статусы: ✅ пройден, ❌ провален, ⏭ пропущен (указать причину: «окно не поймано», «только IT», «покрыто Vitest»).

Итог: X пройдено / Y провалено / Z пропущено. Найденные дефекты: `<список с привязкой к TC>`.
