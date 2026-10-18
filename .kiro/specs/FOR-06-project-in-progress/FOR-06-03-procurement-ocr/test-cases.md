# Тест-кейсы: FOR-06-03 — Procurement intake + OCR auto-match (приёмка закупочных документов и авто-сопоставление)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерный экран**, поэтому набор тест-кейсов включает **оба типа**:

- **Часть A. API-тесты** — против поднятого в Docker приложения (`http://localhost:8080`). Каждый
  шаг — HTTP-запрос к `Procurement_API` (`/api/procurement-documents`), с проверкой статуса, тела и
  заголовков. Покрывают: правила загрузки (тип/размер/недоступное хранилище), жизненный цикл
  `UPLOADED → PROCESSING → PARSED/FAILED`, переобработку, тиры предложенного сопоставления над
  засиженным набором планового материала, двойные рендиции изображений, матрицу ABAC `PURCHASES` по
  ролям и стадийный гейт.
- **Часть B. Браузерный (UI) сценарий** — против фронтенда (`http://localhost:3000`), исполняется
  браузерным движком (headless-браузер / browser automation) под ролью с CRUD-грантом `PURCHASES`
  (ADMIN / MANAGER / FINANCIER / FOREMAN) и, где требуется проверка read-only, под ESTIMATOR. Экран:
  вкладка `Procurement` воркспейса `ACTIVE`-проекта (список документов, контрол загрузки, цепочка
  статусов, read-only обзор предложенных сопоставлений, тир-бейджи, просмотрщик изображений,
  действие re-process).

Результат прогона для обеих частей — **MD-репорты с таблицами** (шаг → запрос/действие → ожидание →
факт → статус). **Скриншоты не требуются.**

### Поведение при недоступном OCR-провайдере (важно)

В docker-стеке OCR-провайдер (Google Cloud Vision + LLM) **обычно не сконфигурирован**
(`foremen.ocr.enabled=false` → `DisabledOcrService`). Поэтому:

- **OCR-зависимые сценарии** проверяют именно поведение disabled-fallback: приложение стартует без
  провайдера, загрузка проходит, документ остаётся `UPLOADED` (либо помечается `FAILED` при явном
  запросе обработки), **процессированной рендиции и строк нет** (R3.5). Это нормальный ожидаемый
  результат в стеке без провайдера, а не дефект.
- **Детерминированные сценарии match-engine** (тиры 100/80/50/no-match) проверяются через **прямые
  unit/property-тесты движка** (`MatchEngine` — jqwik, задачи 3.1/3.1a) и через **засиженные строки**
  (`procurement_lines`), т.к. движок — чистая функция без I/O и не зависит от реального OCR.
- Если в целевом окружении OCR **сконфигурирован** (`foremen.ocr.enabled=true` + валидные
  credentials), соответствующие кейсы помечены маркером **[ocr-enabled]** и прогоняются по
  «happy-path OCR»-варианту (документ доходит до `PARSED`, появляются строки и `processedObjectKey`).

### Покрытие требований

| Кейс / группа | Покрываемые требования |
|---------------|------------------------|
| Фича 1 (загрузка) | R2.2, R2.3, R2.4 |
| Фича 2 (жизненный цикл + OCR fallback) | R3.3, R3.4 |
| Фича 3 (переобработка) | R8.1–R8.3 |
| Фича 4 (тиры сопоставления) | R5.2, R5.3, R5.4, R5.5 |
| Фича 5 (рендиции изображений) | R3.0, R6.4 |
| Фича 6 (матрица ABAC `PURCHASES`) | R7.4, R7.5 |
| Фича 7 (стадийный гейт) | R7.1 |
| Часть B (UI-панель) | R6.1, R6.2, R6.3, R6.4, R6.5 |
| Регрессия | R11.2, R11.3 |

Соответствие «кейс ↔ требование» указано в каждом кейсе строкой `_Покрывает:_`.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции,
   включая changesets `167`–`169`: `procurement_documents`, `procurement_lines`, seed ресурса
   `PURCHASES`), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN — через переменные окружения `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`,
   `FOREMEN_ADMIN_PASSWORD`. Логин: `POST /api/auth/login {email, password}` → `accessToken`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном (проверка: приложение
   поднялось без ошибки `PermissionAnnotationValidator`, `foremen.ocr.*` биндится, OCR-провайдер
   **disabled** — R3.5; `GET /api/procurement-documents?projectId=<id>` под ADMIN отвечает 200).

## Общая стратегия повторяемости

**Комбинированная: генератор `run-id` + teardown.** Единый на прогон `run-id` (timestamp или uuid,
например `1730000000` или `a1b2c3`) встраивается во все создаваемые идентификаторы, чтобы два
последовательных прогона на одной БД не конфликтовали:

- email тестовых пользователей по ролям: `fin+{run-id}@example.com`, `mgr+{run-id}@example.com`,
  `fman+{run-id}@example.com`, `est+{run-id}@example.com`, `wrk+{run-id}@example.com`,
  `cli+{run-id}@example.com`;
- имена проектов: `Procurement Test {run-id}`;
- имена файлов загрузки: `receipt-{run-id}.png`, `invoice-{run-id}.pdf`, `bad-{run-id}.txt`.

**Teardown каждой группы** выполняется в конце набора в обратном порядке создания:
`DELETE /api/procurement-documents/{id}` для каждого созданного документа (каскадно удаляет строки и
чистит объекты через `deleteIfOrphan`), затем деактивация/удаление созданных пользователей
админ-API (`PATCH`/`DELETE /api/users/{id}`), затем перевод тестовых проектов в `CANCELLED` или их
удаление доступным admin-API. Сид-данные (роли, ресурс `PURCHASES`, гранты, changesets 167–169)
**только читаются, никогда не изменяются**. Каждая группа ниже дополнительно уточняет свою стратегию
строкой `**Повторяемость:**`.

### Замечание про non-ADMIN роли (ABAC)

Бутстрапится только ADMIN. Там, где кейс требует ролей FINANCIER / MANAGER / FOREMAN / ESTIMATOR /
WORKER / CLIENT, тестовые пользователи этих ролей создаются и приглашаются в тестовый проект через
admin-API в группе `Setup`. Матрица `PURCHASES`: **ADMIN / FINANCIER / MANAGER / FOREMAN — CRUD;
ESTIMATOR (прочий admin-staff) — только READ; WORKER / CLIENT — без гранта** (deny-by-default).

### Засев планового материала для match-engine

Сценарии тиров (Фича 4) требуют, чтобы у тестового проекта был непустой набор `Planned_Material`
(выбранные на смете `Concrete_Material` с `producer`, `type`, `nameRU/namePL`). В группе `Setup`
через существующий флоу сметы/списка материалов (FOR-05-05/05b) на проект заводится минимум три
конкретных материала с различимыми (producer, type, name) — см. TC-SETUP-04. Строки для проверки
тиров заносятся в `procurement_lines` засевом (fixture), поскольку реальный OCR в стеке отключён.

---

# Часть A. API-тесты (docker-стек)

## Фича 0. Setup — токены, проект, роли, плановый материал

**Повторяемость:** читает сид + создаёт помеченные `run-id` сущности; teardown в конце набора.

### TC-SETUP-01 — Логин ADMIN
**Предусловия:** стек поднят, ADMIN забутстрапен.
1. `POST /api/auth/login {email: <FOREMEN_ADMIN_EMAIL>, password: <FOREMEN_ADMIN_PASSWORD>}` →
   **200** + `accessToken`. Сохранить как `TOKEN_ADMIN`.

_Покрывает:_ инфраструктура (предзависимость всех групп).

### TC-SETUP-02 — Создать `ACTIVE` тестовый проект
**Предусловия:** TOKEN_ADMIN.
1. Создать проект `Procurement Test {run-id}` через `POST /api/projects` (с обязательным MANAGER и
   CLIENT в `members[]`). → **201** + `projectId`. Сохранить `PROJECT_ID`.
2. Перевести проект в стадию `ACTIVE` доступным admin-API-переходом. → **200/2xx**. Сохранить, что
   `stage=ACTIVE`.

_Покрывает:_ предзависимость R2/R7 (загрузка доступна только на `ACTIVE`).

### TC-SETUP-03 — Создать тестовых пользователей по ролям и пригласить в проект
**Предусловия:** TOKEN_ADMIN, PROJECT_ID.
1. Создать и пригласить в `PROJECT_ID` пользователей ролей FINANCIER, MANAGER, FOREMAN, ESTIMATOR;
   создать пользователей WORKER и CLIENT. → **2xx**.
2. Залогинить каждого → сохранить `TOKEN_FIN`, `TOKEN_MGR`, `TOKEN_FMAN`, `TOKEN_EST`, `TOKEN_WRK`,
   `TOKEN_CLI`.

_Покрывает:_ предзависимость R7.4/R7.5 (матрица по ролям).

### TC-SETUP-04 — Засеять плановый материал проекта (candidate set)
**Предусловия:** TOKEN_ADMIN, PROJECT_ID.
1. Через флоу сметы/списка материалов (FOR-05-05/05b) добавить на проект ≥ 3 конкретных материала с
   различимыми характеристиками, например:
   - M1: producer `Śnieżka`, type `Краска`, nameRU `Эмаль белая`;
   - M2: producer `Knauf`, type `Грунтовка`, nameRU `Грунт глубокого проникновения`;
   - M3: producer `Ceresit`, type `Клей`, nameRU `Клей плиточный`.
   → **2xx**. Сохранить `MAT_M1_ID`, `MAT_M2_ID`, `MAT_M3_ID`.
2. Убедиться, что проекция кандидатов непуста (косвенно — через последующие тиры).

_Покрывает:_ предзависимость R4/R5 (candidate set для тиров).

---

## Фича 1. Загрузка документа: правила типа / размера / хранилища (R2.2, R2.3, R2.4)

**Повторяемость:** созданные документы удаляются в teardown; отклонённые загрузки ничего не создают.

### TC-UP-01 — Принять допустимый тип (PNG) и создать `UPLOADED`-документ
**Предусловия:** TOKEN_FIN, PROJECT_ID (`ACTIVE`), хранилище сконфигурировано.
1. `POST /api/procurement-documents` (multipart: `projectId=PROJECT_ID`, `file=receipt-{run-id}.png`,
   `Content-Type: image/png`), Bearer FIN → **201/2xx** + тело с `id`, `status`, `sourceMimeType`.
   Сохранить `DOC_PNG_ID`.
2. Проверить в теле: `sourceMimeType=image/png`, `uploadedBy` = текущий пользователь, `uploadedAt`
   проставлен, `rawObjectKey` присутствует (непустой), а при disabled-OCR — `status ∈ {UPLOADED}`
   (либо `PROCESSING→FAILED` при явном запросе обработки). **Ожидание:** объект raw сохранён,
   документ создан.

_Покрывает:_ R2.1 (контекст), подготовка к R2.2/R2.3 (позитивный контроль).

### TC-UP-02 — Отклонить недопустимый тип (text/plain)
**Предусловия:** TOKEN_FIN, PROJECT_ID.
1. `POST /api/procurement-documents` (multipart: `file=bad-{run-id}.txt`,
   `Content-Type: text/plain`) → **4xx** + код ошибки `error.procurement.upload.unsupportedType`.
2. `GET /api/procurement-documents?projectId=PROJECT_ID` → в списке **нет** нового документа (ничего
   не создано, ничего не сохранено).

_Покрывает:_ R2.2.

### TC-UP-03 — Отклонить превышение максимального размера
**Предусловия:** TOKEN_FIN, PROJECT_ID.
1. `POST /api/procurement-documents` с файлом, превышающим настроенный лимит `ImageStorage` (тип
   допустимый, напр. `image/png`) → **4xx** + код `error.procurement.upload.tooLarge`.
2. Список документов проекта **не пополнился** (ничего не создано).

_Покрывает:_ R2.3.

### TC-UP-04 — Грейсфул-отказ при недоступном хранилище (DisabledImageStorage)
**Предусловия:** стек с **неконфигурированным** object store (`DisabledImageStorage`), TOKEN_FIN.
> Прогоняется только в окружении с отключённым хранилищем; помечен **[storage-disabled]**. Если
> хранилище сконфигурировано — кейс пропускается со статусом ⏭ и пометкой причины.
1. `POST /api/procurement-documents` (валидный PNG) → **4xx** + код
   `error.procurement.upload.storageUnavailable`.
2. Документ **не создан**, объект **не сохранён**.

_Покрывает:_ R2.4.

---

## Фича 2. Жизненный цикл обработки + OCR disabled-fallback (R3.3, R3.4)

**Повторяемость:** документы помечены `run-id`; teardown удаляет их каскадно.

### TC-LC-01 — Disabled-OCR: документ остаётся `UPLOADED`, строк нет
**Предусловия:** стек с **disabled** OCR (`foremen.ocr.enabled=false`), TOKEN_FIN, `DOC_PNG_ID` из
TC-UP-01.
1. `GET /api/procurement-documents/{DOC_PNG_ID}` → **200**; `status=UPLOADED` (или `FAILED`, если был
   явный запрос обработки), `processedObjectKey=null`, массив `lines` **пуст**, `supplierName=null`,
   `documentTotal=null`.
2. **Ожидание:** приложение работает без OCR-провайдера, строки не извлечены (R3.5), документ и raw
   сохранены.

_Покрывает:_ R3.5 (контекст для R3.3/R3.4), R11.3.

### TC-LC-02 — Явный запрос обработки без провайдера → `FAILED` с сохранением raw
**Предусловия:** disabled OCR, TOKEN_FIN, `DOC_PNG_ID`.
1. `POST /api/procurement-documents/{DOC_PNG_ID}/reprocess` (Bearer FIN) → **2xx**.
2. `GET /api/procurement-documents/{DOC_PNG_ID}` → `status=FAILED`, `failureReason` — стабильный код
   (напр. OCR unavailable), `lines` пуст, `rawObjectKey` **сохранён** (не удалён), документ не удалён.

_Покрывает:_ R3.4.

### TC-LC-03 — [ocr-enabled] Happy-path: `UPLOADED → PROCESSING → PARSED`, строки и totals
**Предусловия:** стек с **сконфигурированным** OCR (`foremen.ocr.enabled=true`), TOKEN_FIN,
читаемый чек-образец. Помечен **[ocr-enabled]**; при disabled-OCR пропускается (⏭).
1. `POST /api/procurement-documents` (валидный PNG чека) → **201**, `status ∈ {UPLOADED, PROCESSING}`.
2. Опросить `GET /api/procurement-documents/{id}` до терминального статуса → `status=PARSED`.
3. Проверить: `lines` непуст, `lineNo` последователен от 1, у каждой строки есть `rawName`;
   `supplierName`/`documentTotal` проставлены из парса; `parsedAt` проставлен; `processedObjectKey`
   не пуст.

_Покрывает:_ R3.3.

---

## Фича 3. Переобработка документа (R8.1, R8.2, R8.3)

**Повторяемость:** переобработка идемпотентна по эффекту на один документ; teardown удаляет документ.

### TC-RP-01 — Re-process разрешён для `FAILED` и `PARSED`
**Предусловия:** TOKEN_FIN, документ в `FAILED` (из TC-LC-02) и/или `PARSED` (из TC-LC-03).
1. `POST /api/procurement-documents/{id}/reprocess` на `FAILED`-документе → **2xx**; документ проходит
   `PROCESSING` и возвращается в терминальный статус (`FAILED` при disabled-OCR / `PARSED` при
   [ocr-enabled]).
2. (при наличии `PARSED`) повторить на `PARSED`-документе → **2xx**.

_Покрывает:_ R8.1.

### TC-RP-02 — Re-process атомарно заменяет набор строк
**Предусловия:** [ocr-enabled] ИЛИ засеянные строки, TOKEN_FIN, документ с известным набором строк.
1. Зафиксировать текущие `lines` (id/`lineNo`).
2. `POST /{id}/reprocess` → **2xx**; дождаться терминального статуса.
3. `GET /{id}` → старые строки **полностью заменены** свежими (прежние `ProcurementLine` не
   сосуществуют с новыми), `lineNo` вновь последователен от 1.

_Покрывает:_ R8.2.

### TC-RP-03 — При неуспехе re-process raw остаётся нетронутым
**Предусловия:** disabled OCR, TOKEN_FIN, `DOC_PNG_ID`.
1. `GET /{DOC_PNG_ID}` → запомнить `rawObjectKey`.
2. `POST /{DOC_PNG_ID}/reprocess` → **2xx**; `status=FAILED`.
3. `GET /{DOC_PNG_ID}` → `rawObjectKey` **не изменился** и остаётся доступным (raw никогда не теряется).

_Покрывает:_ R8.3.

---

## Фича 4. Тиры предложенного сопоставления над засиженным плановым материалом (R5.2–R5.5)

**Повторяемость:** строки заносятся засевом (fixture) под `run-id`-документ; teardown удаляет документ.
> **Примечание.** Поскольку реальный OCR в стеке отключён, строки для проверки тиров заносятся
> напрямую в `procurement_lines` (fixture) либо через [ocr-enabled]-прогон на подготовленном чеке.
> Чистая детерминированность движка подтверждается property-тестами (jqwik, задача 3.1a); здесь —
> сквозная проверка, что тир корректно виден в read-model и в `matchStatus`.

### TC-TIER-01 — EXACT (100%): producer + category + name
**Предусловия:** PROJECT_ID с M1 (`Śnieżka`/`Краска`/`Эмаль белая`), строка
`rawName="Эмаль белая Краска"`, `manufacturerHint="Śnieżka"`.
1. `GET /api/procurement-documents/{doc}` → строка имеет `matchTier=EXACT`,
   `matchedMaterialId=MAT_M1_ID`, `matchStatus=AUTO`, производный процент = **100**.

_Покрывает:_ R5.2.

### TC-TIER-02 — MANUFACTURER_CATEGORY (80%): producer + category, без совпадения имени
**Предусловия:** строка с `manufacturerHint="Knauf"`, `rawName` содержит категорию `Грунтовка`, но
не имя M2.
1. `GET /{doc}` → строка: `matchTier=MANUFACTURER_CATEGORY`, `matchedMaterialId=MAT_M2_ID`,
   `matchStatus=AUTO`, процент = **80**.

_Покрывает:_ R5.3.

### TC-TIER-03 — CATEGORY (50%): только категория
**Предусловия:** строка с `rawName`, содержащим категорию `Клей`, но без совпадения производителя.
1. `GET /{doc}` → строка: `matchTier=CATEGORY`, `matchedMaterialId=MAT_M3_ID`, `matchStatus=AUTO`,
   процент = **50**.

_Покрывает:_ R5.4.

### TC-TIER-04 — NONE: нет совпадения ни на одном тире
**Предусловия:** строка с `rawName="Перчатки рабочие"`, не пересекающаяся ни с одним кандидатом.
1. `GET /{doc}` → строка: `matchTier=NONE`, `matchedMaterialId=null`, `matchStatus=UNMATCHED`,
   процент = **0**.

_Покрывает:_ R5.5 (и инвариант R1.5: `matchedMaterialId == null ⟺ tier == NONE`).

### TC-TIER-05 — Пустой candidate set → все строки NONE
**Предусловия:** отдельный проект без выбранных Concrete_Material, строка с любым `rawName`.
1. `GET /{doc}` → все строки `NONE`/`UNMATCHED`, без ошибки.

_Покрывает:_ R5.5 (контекст R4.2).

---

## Фича 5. Двойные рендиции изображений (R3.0, R6.4 — API)

**Повторяемость:** читает документы из предыдущих фич; без создания новых сущностей.

### TC-IMG-01 — `GET /{id}/images` возвращает raw-URL
**Предусловия:** TOKEN_FIN, `DOC_PNG_ID`.
1. `GET /api/procurement-documents/{DOC_PNG_ID}/images` → **200** + тело с разрешённым URL **raw**
   рендиции; при disabled-OCR processed-URL `null`/отсутствует.

_Покрывает:_ R3.0 (хранение raw), R6.4 (raw просматриваем даже на `FAILED`).

### TC-IMG-02 — [ocr-enabled] Присутствуют обе рендиции (raw + processed PNG)
**Предусловия:** [ocr-enabled] `PARSED`-документ.
1. `GET /{id}/images` → **200**; присутствуют **оба** URL — raw и processed; processed — `image/png`
   (white-background); для многостраничного PDF processed — постраничный набор.

_Покрывает:_ R3.0.

---

## Фича 6. Матрица ABAC `PURCHASES` по ролям (R7.4, R7.5)

**Повторяемость:** только проверка грантов помеченными токенами; teardown токенов/пользователей.

### TC-ABAC-01 — ADMIN / FINANCIER / MANAGER / FOREMAN: полный CRUD
**Предусловия:** PROJECT_ID (`ACTIVE`), токены по ролям, валидный PNG.
1. Для каждой из ролей ADMIN, FINANCIER, MANAGER, FOREMAN выполнить:
   - `POST /api/procurement-documents` (CREATE) → **2xx**, сохранить `id`;
   - `GET /api/procurement-documents?projectId=PROJECT_ID` (READ) → **200**;
   - `GET /{id}` (READ) → **200**;
   - `POST /{id}/reprocess` (UPDATE) → **2xx**;
   - `DELETE /{id}` (DELETE) → **2xx**.

_Покрывает:_ R7.4 (ADMIN/FINANCIER/MANAGER/FOREMAN — CRUD).

### TC-ABAC-02 — ESTIMATOR: только READ
**Предусловия:** TOKEN_EST, существующий документ `DOC_PNG_ID`.
1. `GET /api/procurement-documents?projectId=PROJECT_ID` → **200**.
2. `GET /{DOC_PNG_ID}` → **200**.
3. `POST /api/procurement-documents` (CREATE) → **403**.
4. `POST /{DOC_PNG_ID}/reprocess` (UPDATE) → **403**.
5. `DELETE /{DOC_PNG_ID}` (DELETE) → **403**.

_Покрывает:_ R7.4 (ESTIMATOR — READ), R7.5 (reader не загружает и не переобрабатывает).

### TC-ABAC-03 — WORKER и CLIENT: нет гранта (deny-by-default)
**Предусловия:** TOKEN_WRK, TOKEN_CLI.
1. Для каждого из WORKER и CLIENT: `GET /api/procurement-documents?projectId=PROJECT_ID` → **403**;
   `POST /api/procurement-documents` → **403**.

_Покрывает:_ R7.5 (deny-by-default для WORKER/CLIENT).

### TC-ABAC-04 — 401 без токена
**Предусловия:** нет Authorization-заголовка.
1. `GET /api/procurement-documents?projectId=PROJECT_ID` (без Bearer) → **401** (до проверки
   грантов/скоупа).

_Покрывает:_ R7.5 (предохранитель аутентификации).

---

## Фича 7. Стадийный гейт (R7.1)

**Повторяемость:** переводы стадий — на изолированных `run-id`-проектах; teardown переводит в `CANCELLED`.

### TC-STAGE-01 — `ACTIVE`: загрузка / обработка / чтение открыты
**Предусловия:** TOKEN_FIN, PROJECT_ID (`ACTIVE`).
1. `POST /api/procurement-documents` → **2xx**; `GET …?projectId` → **200**; `POST /{id}/reprocess`
   → **2xx**.

_Покрывает:_ R7.1 (ACTIVE — открыто).

### TC-STAGE-02 — `COMPLETED`: только чтение, загрузка/переобработка запрещены
**Предусловия:** проект, переведённый в `COMPLETED` (с уже существующим документом), TOKEN_FIN.
1. `GET /api/procurement-documents?projectId=…` → **200** (история видна).
2. `GET /{id}` → **200**.
3. `POST /api/procurement-documents` (upload) → **4xx** + `error.procurement.stage.notActive`.
4. `POST /{id}/reprocess` → **4xx** + `error.procurement.stage.notActive`.

_Покрывает:_ R7.1 (COMPLETED — read-only).

### TC-STAGE-03 — `DRAFT`: поверхность недостижима
**Предусловия:** проект в `DRAFT`, TOKEN_FIN.
1. `POST /api/procurement-documents` (upload) → **4xx** `error.procurement.stage.notActive` (либо
   недоступность по скоупу/вкладке).

_Покрывает:_ R7.1 (DRAFT — недостижимо).

---

# Часть B. Браузерные (UI) сценарии (headless-браузер, `http://localhost:3000`)

**Повторяемость:** UI-кейсы переиспользуют проект/документы Части A под `run-id`; созданное через UI
удаляется в teardown (DELETE документа). Логин каждой роли — через UI-форму либо предустановленный
токен в хранилище сессии.

## UI-Фича 8. Панель Procurement на `ACTIVE`-проекте (R6.1–R6.5)

### TC-UI-01 — Вкладка Procurement видна и показывает список документов
**Предусловия:** вход под ролью с CRUD-грантом `PURCHASES` (напр. FINANCIER), `ACTIVE`-проект.
1. Открыть воркспейс проекта → вкладка `Procurement` присутствует и кликается.
2. Панель рендерит список документов с колонками: загрузивший, время загрузки, поставщик, сумма
   документа, бейдж статуса обработки.

_Покрывает:_ R6.1.

### TC-UI-02 — Контрол загрузки: цепочка статусов и inline-ошибки
**Предусловия:** FINANCIER, `ACTIVE`-проект.
1. Загрузить допустимый PNG через контрол → отображается прогресс и цепочка статусов
   `UPLOADED → PROCESSING → PARSED/FAILED` (при disabled-OCR — завершение в `UPLOADED`/`FAILED`).
2. Загрузить `.txt` → inline-ошибка `unsupportedType`; загрузить слишком большой файл → `tooLarge`;
   (при disabled-storage) → `storageUnavailable`.

_Покрывает:_ R6.2, R6.6.

### TC-UI-03 — Read-only обзор предложенных сопоставлений + тир-бейджи
**Предусловия:** документ с засеянными строками известных тиров (EXACT/MAN_CAT/CATEGORY/NONE).
1. Раскрыть документ → таблица строк: raw name, количество, ед., цена за ед., сумма строки, имя
   предложенного материала, **тир-бейдж** (локализованный, цветокодированный 100%/80%/50%/no-match).
2. Убедиться, что **нет** контролов подтверждения / переназначения / классификации (только чтение).

_Покрывает:_ R6.3, R6.5.

### TC-UI-04 — Просмотрщик изображений (raw + processed)
**Предусловия:** документ с raw-рендицией (и processed при [ocr-enabled]).
1. Открыть просмотрщик → виден raw; при [ocr-enabled] доступна и processed white-background рендиция
   (постранично для PDF). На `FAILED`-документе raw остаётся просматриваемым.

_Покрывает:_ R6.4.

### TC-UI-05 — Действие re-process и его скрытие для read-only / COMPLETED
**Предусловия:** (а) FINANCIER на `FAILED`-документе; (б) ESTIMATOR (READ) на том же; (в) любой на
`COMPLETED`-проекте.
1. (а) У `FAILED`-документа видно действие re-process; клик запускает переобработку.
2. (б) Под ESTIMATOR контролы загрузки и re-process **скрыты** (панель read-only).
3. (в) На `COMPLETED`-проекте контролы загрузки/re-process **скрыты**, история видна.

_Покрывает:_ R6.4, R6.5, R7.5.

---

# Регрессия

**Повторяемость:** регрессионные кейсы читают сид и ранее закрытое поведение; без деструктивных
изменений.

### REG-01 — Старт приложения с disabled-OCR и целостность аннотаций
1. Приложение стартует без OCR-провайдера (`foremen.ocr.enabled=false`), без ошибки
   `PermissionAnnotationValidator`; `foremen.ocr.*` биндится без хардкода ключей.
2. `ProcurementDocumentController` классифицируется COMPLETE (каждый in-scope handler несёт
   `@PermissionOperation`/`@RequiresPermission` под `@PermissionResource("PURCHASES")`).

_Покрывает:_ R11.3, R3.5 (контекст).

### REG-02 — Идемпотентность changesets 167–169
1. Повторный прогон миграций на уже засиженной БД **не вставляет** дубликатов таблиц / ресурса
   `PURCHASES` / грантов (`NOT tableExists` / `COUNT(*)=0` / `MARK_RAN`).

_Покрывает:_ R11.2, R11.3.

### REG-03 — Не затронутые поверхности FOR-05 / FOR-06-00 работают
1. Воркспейс-шелл, `resolveWorkspaceTabs`, `stageOf`, плейсхолдеры прочих execution-вкладок,
   `ImageStorage`, каталоги материалов — отвечают как раньше (смоук по ключевым эндпоинтам).

_Покрывает:_ R11.2.

### REG-04 — Каскадное удаление документа чистит строки и объекты
1. `DELETE /api/procurement-documents/{id}` → **2xx**; `procurement_lines` документа удалены
   (`ON DELETE CASCADE`), объекты raw/processed подчищены через `deleteIfOrphan`; документ исчезает
   из списка.

_Покрывает:_ R11.2 (контекст R1.4).

---

# Шаблон MD-репорта прогона

Заполняется по итогам каждого прогона (отдельно для Части A и Части B).

## Репорт — Часть A (API)

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-UP-01  | 1   | POST /api/procurement-documents (PNG) | 201 + id, UPLOADED | — | ⬜ |
| 2 | TC-UP-02  | 1   | POST … (text/plain) | 4xx unsupportedType | — | ⬜ |
| 3 | TC-ABAC-02| 3   | POST … под ESTIMATOR | 403 | — | ⬜ |

## Репорт — Часть B (UI)

| # | Тест-кейс | Шаг | Действие (браузер) | Ожидание | Факт | Статус |
|---|-----------|-----|--------------------|----------|------|--------|
| 1 | TC-UI-01  | 1   | Открыть вкладку Procurement | Вкладка видна, список рендерится | — | ⬜ |
| 2 | TC-UI-03  | 2   | Раскрыть документ | Нет confirm/re-link/classify | — | ⬜ |

**Легенда статусов:** ✅ пройдено · ❌ провалено · ⚠️ отклонение/известный нюанс · ⏭ пропущено
(например, [ocr-enabled]/[storage-disabled] в неподходящем окружении) · ⬜ не выполнялось.

**Итог:** X пройдено / Y провалено / Z пропущено.
**Run-id:** `<timestamp/uuid>`.
**Окружение OCR:** `disabled | enabled`. **Окружение storage:** `configured | disabled`.
**Стратегия повторяемости:** генератор `run-id` + teardown (DELETE документов → деактивация
пользователей → перевод проектов в `CANCELLED`).
