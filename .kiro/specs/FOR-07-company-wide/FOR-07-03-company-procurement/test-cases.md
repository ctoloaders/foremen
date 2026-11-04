# Тест-кейсы: FOR-07-03 — Закупки по компании

Спека: `FOR-07-03-company-procurement`. Тип: **UI + API** (UI-кейсы исполняются браузерным движком,
API-кейсы — против работающего Docker-стека). Результат прогона — MD-репорт с таблицами (шаг →
ожидание → факт → статус), скриншоты не требуются.

## Окружение и повторяемость

- Стек: `docker compose up` в корне (postgres :5432, liquibase, backend :8080 профиль `docker`, frontend :3000).
  Базовый URL API: `http://localhost:8080`, UI: `http://localhost:3000`.
- Первый ADMIN: переменные (`FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`).
- Тестовые файлы: `fixtures/receipt-sample.png`, `fixtures/invoice-sample.pdf` (небольшие, до лимита размера), `fixtures/not-an-image.txt`.
- **Стратегия повторяемости: генератор данных + teardown.**
  - `run-id` = `yyyyMMddHHmmss` на момент старта прогона.
  - Пользователи: `test+admin-{run-id}@example.com`, `test+fin-{run-id}@example.com`,
    `test+mgr-{run-id}@example.com`, `test+foreman-{run-id}@example.com`,
    `test+worker-{run-id}@example.com`, `test+client-{run-id}@example.com` (создаются ADMIN через
    `POST /api/users`, роли по коду).
  - Проекты: `QA-{run-id}-A` (ACTIVE, worker и foreman — участники), `QA-{run-id}-B` (ACTIVE, worker не участник),
    `QA-{run-id}-C` (не ACTIVE, например DRAFT).
  - Teardown (в конце набора): `DELETE /api/company-purchases/documents/{id}` для всех документов,
    созданных прогоном (учёт id в контексте), затем деактивация пользователей и проектов `QA-{run-id}-*`.
- Подготовка (Setup) выполняется один раз на набор и описана в TC-00.
- **Стратегия повторяемости по группам** (каждая группа ниже явно указывает свою):
  - Группа 0 — Setup (генератор `{run-id}`);
  - Группы 1–6 — генератор данных (уникальные пользователи/проекты/поставщики `QA-{run-id}-*`) + teardown документов в конце группы;
  - Регрессия — генератор + teardown.
- **Реально реализованные эндпоинты** (сверено с контроллерами):
  - `CompanyPurchasesController` (`/api/company-purchases`, ресурс `COMPANY_PURCHASES`):
    `GET /documents`, `GET /documents/{id}`, `GET /lines` (READ); `PUT /documents/{id}/project`
    (body `{projectId}` → `{document, matchedLines, resetLines}`), `POST /documents/{id}/reprocess` (UPDATE);
    `DELETE /documents/{id}` (DELETE). Параметры списка: `projectId`, `noProject`, `linkStatus`,
    `uploadedFrom`, `uploadedTo`, `uploadedById`, `expenseCategoryId`, `q`, `page`, `size` (по умолчанию 25, максимум 100).
  - `ExpenseController` (`/api/expenses`, ресурс `EXPENSE_UPLOAD`): `POST /` (multipart: `file`, `projectId`,
    `expenseCategoryId`, `paymentChoice`, `cashAccountId`, `cashAmount`, `cashDate`; CREATE),
    `GET /options` (`projects`, `categories`, `canChooseCashAccount`, `cashAccounts`; READ), `GET /{id}/status` (READ).
  - `ProcurementDocumentController`: `POST /api/procurement-documents` (проект обязателен, `PURCHASES/CREATE`) и
    `POST /api/procurement-documents/company` (`uploadCompany`, проект необязателен, принимает все `paymentChoice`
    включая `LINKED_FROM_LEDGER`, `PURCHASES/CREATE`) — используется диалогом загрузки инвойса в банковском журнале.
- **UI-идентификаторы (data-testid):** `expense-upload-page`, `expense-form`, `expense-file-input`,
  `expense-project`, `expense-category`, `expense-paid-cash`, `expense-cash-fields`, `expense-cash-amount`,
  `expense-cash-date`, `expense-submit`, `expense-progress`, `expense-add-another`, `expense-open-company`,
  `expense-upload-dialog`; `company-procurement-page`, `company-filter-bar` (`company-filter-project|status|category|from|to|q|reset`),
  `company-add-expense`, `company-view-grouped|flat`, `company-docs-table`, `company-doc-row`, `company-doc-toggle`,
  `company-doc-lines-row`, `company-flat-table`, `company-flat-row`, `company-flat-doc-link`, `company-link-status`,
  `company-doc-view-images`, `company-doc-relink`, `company-doc-reprocess`, `company-doc-delete`,
  `company-doc-open-project`, `company-line-open-project`, `company-relink-dialog|project|confirm|result|error`,
  `company-doc-confirm|confirm-action`, `company-viewer-dialog`, `company-pagination`; проектная вкладка:
  `procurement-upload`, `procurement-upload-category`, `procurement-paid-cash-checkbox`; журнал: `ledger-upload-dialog|project|file|submit`.
- **Тексты UI (RU / PL):** меню «Добавить расход» / «Dodaj wydatek»; раздел-пункт «Закупки компании» / «Zakupy firmowe»;
  фильтр «Без проекта» / «Bez projektu»; статусы связи: «Без проекта|Привязано|Не привязано|Классифицировано» /
  «Bez projektu|Powiązane|Niepowiązane|Sklasyfikowane»; действия «Привязать к проекту» / «Przypisz do projektu»,
  «Показать изображения» / «Pokaż obrazy», «Открыть в проекте» / «Otwórz w projekcie», «Обработать повторно» / «Przetwórz ponownie»;
  результат relink «Сопоставлено позиций: N, сброшено: M» / «Dopasowano pozycji: N, zresetowano: M»;
  «Добавить ещё» / «Dodaj kolejny»; «Оплачено наличными» / «Zapłacono gotówką».

## Трассировка требований

| Требование | Тест-кейсы |
|------------|-----------|
| R1 (nullable project, guard) | TC-01, TC-02, TC-R1, TC-R4 |
| R2 (null-project upload/OCR, storage) | TC-10, TC-27 |
| R3 (категория при загрузке) | TC-11, TC-14, TC-30, TC-40 |
| R4 (read model, фильтры, URL) | TC-21, TC-22, TC-23, TC-24, TC-52 |
| R5 (relink) | TC-30, TC-31, TC-32 |
| R6 (reprocess/delete) | TC-27 |
| R7 (`/api/expenses`) | TC-10…TC-15 |
| R8 (три точки входа, UI) | TC-16, TC-26, TC-40, TC-41 |
| R9 (экран, deep link) | TC-20…TC-28 |
| R10 (ABAC, меню по ролям) | TC-01, TC-15, TC-20, TC-R3 |
| R11 (аудит, целостность) | TC-31, TC-35 |
| R12 (уведомления) | TC-33, TC-34 |
| R13 (локализация) | TC-16, TC-50 |
| R14 (NFR) | TC-51, TC-52 |

---

## Группа 0. Подготовка

### TC-00 Setup данных прогона
- **Предусловия:** стек поднят, ADMIN залогинен (`POST /api/auth/login` → access token).
- **Шаги:**
  1. Создать пользователей по ролям ADMIN(существующий), FINANCIER, MANAGER, FOREMAN, WORKER, CLIENT с `{run-id}`. Ожидание: 201 на каждого.
  2. Создать проекты A, B, C; перевести A и B в ACTIVE (C оставить не ACTIVE). Ожидание: статусы соответствуют.
  3. Добавить worker и foreman участниками проекта A; manager и financier — участниками A и B. Ожидание: 200/201.
  4. Убедиться, что в справочнике есть активная категория расходов (`GET /api/unplanned-expense-categories`), запомнить `categoryId`. Если нет — создать `QA-{run-id}-CAT`.
  5. Выполнить логин каждого пользователя и сохранить токены.

---

## Группа 1. Схема и права (API)

### TC-01 Проект документа nullable, права ABAC засеяны
- **Предусловия:** TC-00.
- **Шаги:**
  1. `GET /api/resources` (ADMIN). Ожидание: есть коды `COMPANY_PURCHASES` и `EXPENSE_UPLOAD`.
  2. Проверить матрицу ролей `COMPANY_PURCHASES`. Ожидание: ADMIN и FINANCIER — READ/UPDATE/DELETE, MANAGER — READ, остальные — пусто.
  3. Проверить матрицу `EXPENSE_UPLOAD`. Ожидание: ADMIN, FINANCIER, MANAGER, FOREMAN, WORKER — READ+CREATE; CLIENT и ESTIMATOR — нет.
  4. Перезапустить `liquibase` (повторный запуск). Ожидание: без ошибок, дубликатов в `role_resources` нет.

### TC-02 Старые проектные эндпоинты не показывают документы без проекта
- **Предусловия:** TC-00, создан документ без проекта (TC-10).
- **Шаги:**
  1. `GET /api/procurement-documents?projectId={A}` (FINANCIER). Ожидание: 200, документа без проекта в ответе нет.
  2. `GET /api/procurement-documents/{noProjectDocId}` (FINANCIER). Ожидание: 404 `error.entity.not.found` (guard не ослаблен).
  3. `GET /api/procurement-documents/{noProjectDocId}` (ADMIN). Ожидание: 200 (ADMIN обходит guard).

---

## Группа 2. Универсальная загрузка расхода (корневое меню)

### TC-10 WORKER загружает расход без проекта (API)
- **Предусловия:** TC-00, токен worker.
- **Шаги:**
  1. `GET /api/expenses/options` (worker). Ожидание: 200; `projects` содержит A, не содержит B и C; `categories` содержит активные категории; `canChooseCashAccount=false`.
  2. `POST /api/expenses` multipart: `file=receipt-sample.png`, без `projectId`. Ожидание: 200/201, `id`, `status` QUEUED (или UPLOADED при выключенном OCR).
  3. Опрашивать `GET /api/expenses/{id}/status` (worker) до `PARSED` или `FAILED` (таймаут 120 с). Ожидание: статус терминальный; доступ у загрузившего есть.
  4. `GET /api/company-purchases/documents/{id}` (financier). Ожидание: 200, `projectId=null`, `projectName=null`; у всех строк `linkStatus=NO_PROJECT`, `matchStatus=UNMATCHED`, `matchTier=NONE`.
  5. Проверить, что ключ в хранилище содержит `procurement/company/` (по `rawImageUrl`/логам), и не содержит `null`.
- **Teardown:** запомнить id для удаления.

### TC-11 WORKER загружает расход в проект с категорией
- **Шаги:**
  1. `POST /api/expenses`: `file`, `projectId={A}`, `expenseCategoryId={categoryId}`. Ожидание: 200/201.
  2. Дождаться `PARSED`. Ожидание: документ принадлежит проекту A, `expenseCategory` заполнена.
  3. `GET /api/company-purchases/documents/{id}` (financier). Ожидание: строки без сопоставления имеют `unplannedCategoryId={categoryId}` и `linkStatus=CLASSIFIED`; сопоставленные (если есть) без категории.

### TC-12 WORKER не может загрузить в чужой проект
- **Шаги:**
  1. `POST /api/expenses` с `projectId={B}` (worker не участник). Ожидание: 404 `error.entity.not.found`, документ не создан.
  2. `POST /api/expenses` с `projectId={C}` (не ACTIVE, где worker участник или ADMIN). Ожидание: 409 `error.purchases.stage.notActive`.
  3. `POST /api/expenses` с `projectId=999999999`. Ожидание: 404.

### TC-13 Оплата наличными при загрузке
- **Шаги:**
  1. `POST /api/expenses` (worker): `paymentChoice=PAID_CASH`, `cashAmount=123.45`, `cashDate=сегодня`. Ожидание: 200/201.
  2. `GET /api/company-purchases/documents/{id}` (financier). Ожидание: `paymentStatus` отражает наличную оплату, `ledgerEntries` содержит кассовую запись на 123.45.
  3. `POST /api/expenses` с `PAID_CASH` без `cashAmount`. Ожидание: 400, документ не создан.
  4. `POST /api/expenses` с `PAID_CASH`, `cashAmount=0`. Ожидание: 400.

### TC-14 Запрещённые варианты и валидация файла
- **Шаги:**
  1. `POST /api/expenses` с `paymentChoice=LINKED_FROM_LEDGER`. Ожидание: 400 `error.expense.upload.paymentChoiceNotAllowed`.
  2. То же с `FIND_BANK_TRANSACTION`. Ожидание: 400.
  3. `POST /api/expenses` с `file=not-an-image.txt`. Ожидание: 400 (неподдерживаемый тип), документ не создан.
  4. `POST /api/expenses` с `expenseCategoryId=999999999`. Ожидание: 400 `error.expense.category.unknown`, файл в хранилище не сохранён.
  5. `POST /api/expenses` с деактивированной категорией. Ожидание: 400 `error.expense.category.unknown`.

### TC-15 Права на загрузку по ролям
- **Шаги:**
  1. `POST /api/expenses` с токеном CLIENT. Ожидание: 403.
  2. Без токена. Ожидание: 401.
  3. FOREMAN, MANAGER, FINANCIER, ADMIN. Ожидание: 200/201 для каждого (для проектов, где они участники / ADMIN — любой ACTIVE).

### TC-16 Пункт меню «Dodaj wydatek» и страница `/expenses/new` (UI)
- **Предусловия:** TC-00.
- **Шаги:**
  1. Войти как worker. Ожидание: в главном меню (Sidebar/Drawer) и в BottomNav на мобильной ширине есть пункт «Dodaj wydatek» (RU: «Добавить расход»).
  2. Войти как client. Ожидание: пункта нет; прямой переход на `/expenses/new` ведёт на экран отказа/редирект.
  3. Войти как worker, открыть пункт. Ожидание: форма с полями: файл, проект (опция «Bez projektu»), категория, переключатель «Zapłacono gotówką».
  4. Включить переключатель. Ожидание: появляются обязательное поле суммы и дата (по умолчанию сегодня). Выключить — поля скрыты.
  5. Нажать отправку без файла. Ожидание: ошибка валидации под полем файла, запрос не отправлен.
  6. Выбрать `receipt-sample.png`, проект A, категорию, отправить. Ожидание: индикатор загрузки, затем статус обработки (очередь/OCR/готово), кнопка «Dodaj kolejny».
  7. Нажать «Dodaj kolejny». Ожидание: форма очищена.
  8. Переключить язык PL↔RU. Ожидание: все подписи, ошибки и статусы локализованы, без «сырых» ключей.
  9. Для worker ссылка `expense-open-company` («Открыть закупки компании» / «Otwórz zakupy firmowe») не отображается; для financier — отображается и ведёт на `/company/procurement?doc={id}`.
  10. **Стратегия повторяемости:** генератор (уникальные файлы/пользователи `{run-id}`), teardown — удаление созданных документов через `DELETE /api/company-purchases/documents/{id}` (admin).

---

## Группа 3. Экран «Закупки по компании»

### TC-20 Видимость пункта меню и маршрута по ролям (UI + API)
- **Стратегия повторяемости:** генератор (пользователи `{run-id}` из TC-00), данных не создаёт.
- **Шаги:**
  1. Войти как financier, manager, admin. Ожидание: в company-секции меню есть «Zakupy firmowe», ведёт на `/company/procurement`; страница `company-procurement-page` открывается.
  2. Войти как worker, foreman, client, estimator. Ожидание: пункта нет; прямой переход на `/company/procurement` — экран отказа/редирект.
  3. `GET /api/company-purchases/documents` с токеном worker, foreman, client. Ожидание: 403; без токена — 401.
  4. `GET /api/company-purchases/documents` с токеном manager/financier/admin. Ожидание: 200.
  5. Пункт «Dodaj wydatek» (`/expenses/new`): виден для admin, financier, manager, foreman, worker; скрыт для client и estimator (в Sidebar, Drawer и BottomNav).
  6. Кнопка `company-add-expense` на экране: видна manager/financier/admin (у них `EXPENSE_UPLOAD/CREATE`).

### TC-21 Фильтр по проекту: все / проект / без проекта
- **Предусловия:** есть документы: D1 (без проекта), D2 (проект A), D3 (проект B).
- **Шаги:**
  1. UI: открыть экран как financier без фильтров. Ожидание: D1, D2, D3 в списке; у D1 в колонке проекта «Bez projektu».
  2. Выбрать фильтр «Wszystkie projekty» → «Bez projektu». Ожидание: только документы без проекта; в URL `noProject=true`.
  3. Выбрать проект A. Ожидание: только D2 (и прочие документы A).
  4. API: `GET /api/company-purchases/documents?noProject=true` — каждая строка `projectId=null`.
  5. API: `GET ...?projectId={A}&noProject=true`. Ожидание: 400 `error.companyProcurement.filter.conflict`.
  6. Перезагрузить страницу с фильтром в URL. Ожидание: фильтр восстановлен.
  7. Manager: видит те же документы, включая проекты, где он не участник и документы без проекта.

### TC-22 Группировка строк под документом и плоский режим
- **Шаги:**
  1. Открыть экран. Ожидание: по умолчанию документы — сворачиваемые группы (поставщик, проект/«Bez projektu», сумма, статус, категория, бейдж оплаты, счётчики статусов).
  2. Развернуть группу D2. Ожидание: строки документа с названием, количеством, ценой, суммой, статусом связи.
  3. Переключить на плоский список. Ожидание: колонка «Dokument» со ссылкой на группу; клик раскрывает/подсвечивает документ.
  4. API: `GET /api/company-purchases/lines?projectId={A}`. Ожидание: каждая строка содержит `documentId`, `documentSupplierName`, `documentUploadedAt`, `projectName`.

### TC-23 Статус связи со строкой
- **Предусловия:** документ в проекте с планом материалов (есть автосопоставленные и несопоставленные строки), документ без проекта, документ с категорией.
- **Шаги:**
  1. Фильтр по статусу `LINKED`. Ожидание: только строки/документы с сопоставлением (AUTO/MANUAL_MATCHED).
  2. Фильтр `UNLINKED`. Ожидание: строки проектных документов без сопоставления и без категории.
  3. Фильтр `CLASSIFIED`. Ожидание: несопоставленные строки с категорией.
  4. Фильтр `NO_PROJECT`. Ожидание: строки документов без проекта.
  5. Сумма счётчиков по статусам для каждого документа равна `lineCount`.
  6. Бейджи различаются подписью и иконкой, а не только цветом.

### TC-24 Просмотр оригинала документа
- **Шаги:**
  1. Нажать «Pokaż obrazy» (`company-doc-view-images`) у документа без проекта (financier). Ожидание: открывается `DocumentImageViewer` с исходным и обработанным изображением (подписанные URL открываются, не 403).
  2. То же у проектного документа. Ожидание: работает.
  3. `GET /api/company-purchases/documents/{id}` (worker). Ожидание: 403.

### TC-25 Нет редактирования строк на экране
- **Шаги:**
  1. Осмотреть строки на экране. Ожидание: нет действий relink-to-material / clear / classify.
  2. У строки проектного документа нажать «Otwórz w projekcie». Ожидание: переход на вкладку Purchases проекта.
  3. Кнопка «Otwórz w projekcie» у документа без проекта отсутствует.

### TC-26 Создание расхода из списка компании (UI)
- **Шаги:**
  1. Financier: на экране нажать «Dodaj wydatek». Ожидание: диалог с теми же полями, что на `/expenses/new`.
  2. Выбрать фильтр по проекту A, снова открыть диалог. Ожидание: проект A предвыбран.
  3. Загрузить файл без проекта. Ожидание: документ появляется в списке со статусом обработки, затем `PARSED`; в фильтре «Bez projektu» присутствует.
  4. Удостовериться, что кнопка видна только при `EXPENSE_UPLOAD/CREATE`.

### TC-27 Повторная обработка и удаление
- **Шаги:**
  1. Создать документ, который завершится `FAILED` (повреждённый PNG или выключенный OCR-провайдер в тестовой конфигурации). Ожидание: статус FAILED, кнопка «Przetwórz ponownie».
  2. Нажать повторную обработку (financier). Ожидание: статус QUEUED → терминальный; для документа без проекта работает без проверки стадии проекта.
  3. Manager: `POST /api/company-purchases/documents/{id}/reprocess`. Ожидание: 403.
  4. Financier: удалить документ с подтверждением. Ожидание: документ исчез; повторный GET — 404.
  5. Manager: `DELETE`. Ожидание: 403.

### TC-28 Deep link `/company/procurement?doc={id}`
- **Стратегия повторяемости:** генератор (документ создаётся в кейсе), teardown — удаление документа.
- **Шаги:**
  1. Financier загружает документ без проекта (`POST /api/expenses`), дожидается `PARSED`, запоминает `id`.
  2. UI: открыть `http://localhost:3000/company/procurement?doc={id}`. Ожидание: строка документа (`company-doc-row`, `data-highlighted="true"`) прокручена в видимую область и раскрыта; открыт `company-viewer-dialog` с изображениями.
  3. Открыть deep link на несуществующий id. Ожидание: страница загружается без падения, viewer не открывается.
  4. Открыть deep link под manager. Ожидание: работает (READ); под worker — отказ в доступе.

---

## Группа 4. Привязка документа к проекту (relink)

### TC-30 Привязка документа без проекта к проекту
- **Предусловия:** документ D1 без проекта в статусе `PARSED`; в проекте A есть план материалов (смета/BoM), материал совпадает с текстом строки D1; загружен D1 с категорией `{categoryId}`.
- **Шаги:**
  1. UI: у D1 нажать «Przypisz do projektu». Ожидание: диалог со списком только ACTIVE-проектов.
  2. Выбрать проект A, подтвердить. Ожидание: закрытие диалога, результат «Dopasowano N pozycji, zresetowano 0», обновлённый список.
  3. API: `GET /api/company-purchases/documents/{D1}`. Ожидание: `projectId={A}`; совпавшие строки `LINKED`, без категории; несовпавшие — `CLASSIFIED` с категорией документа; инвариант (matched ⟺ tier≠NONE) соблюдён.
  4. `GET /api/company-purchases/lines?projectId={A}` (financier) и вкладка «Purchases» проекта A (UI, участник проекта). Ожидание: строки D1 видны.
  5. Ключ файла в хранилище не изменился (`rawImageUrl` указывает на тот же объект `procurement/company/...`).
  6. Ответ `PUT` содержит `document` (с `linkCounts`), `matchedLines`, `resetLines`; в UI диалог (`company-relink-result`) показывает «Dopasowano pozycji: N, zresetowano: M».

### TC-31 Смена проекта
- **Предусловия:** D2 в проекте A с ручными сопоставлениями: строка L1 `MANUAL_MATCHED` с материалом, которого нет в B; строка L2 `MANUAL_UNMATCHED`.
- **Шаги:**
  1. Financier: `PUT /api/company-purchases/documents/{D2}/project` `{projectId: B}`. Ожидание: 200; `resetLines ≥ 1`.
  2. Проверить L1: `UNMATCHED`, tier `NONE`, материал null. L2 без изменений (`MANUAL_UNMATCHED`).
  3. Связи с банковскими транзакциями (FOR-07-04) у D2 сохранены.
  4. Доп. ручная проверка: заголовок документа в экране теперь показывает проект B; действие называется «Zmień projekt» (если проект уже задан).

### TC-32 Негативные сценарии relink
- **Шаги:**
  1. `PUT ... {projectId: null}`. Ожидание: 400 `error.companyProcurement.relink.projectRequired`.
  2. `PUT ...` на текущий проект. Ожидание: 409 `error.companyProcurement.relink.sameProject`, состояние не изменено.
  3. `PUT ... {projectId: C}` (не ACTIVE). Ожидание: 409 `error.purchases.stage.notActive`.
  4. `PUT ... {projectId: 999999999}`. Ожидание: 404.
  5. `PUT` для документа в статусе обработки (QUEUED/CLEANING/…). Ожидание: 409 `error.companyProcurement.relink.processing`.
  6. Manager, foreman, worker: `PUT`. Ожидание: 403.
  7. `PUT` на несуществующий документ. Ожидание: 404.

### TC-33 Уведомление о привязке
- **Шаги:**
  1. Worker загружает документ без проекта (uploader = worker). Financier привязывает его к проекту A.
  2. Под worker: `GET /api/notifications`. Ожидание: уведомление `notification.procurement.relinked` с названием поставщика и проектом; ссылка `/projects/{A}/procurement?doc={id}`.
  3. В UI колокольчик показывает +1 непрочитанное; текст на PL и RU корректен.
  4. Financier сам загружает документ без проекта и сам же привязывает. Ожидание: уведомления нет.
  5. Сбой отправки уведомления (имитация) не ломает relink (200).

### TC-34 Уведомления OCR для документа без проекта
- **Шаги:**
  1. Worker загружает документ без проекта; после `PARSED` проверить уведомление `notification.procurement.ocr.parsed`. Ожидание: уведомление есть, **без ссылки** (не кликабельно).
  2. Financier загружает документ без проекта; после `PARSED`. Ожидание: ссылка `/company/procurement?doc={id}`; клик открывает экран с раскрытым документом.
  3. Документ в проекте A: ссылка прежняя `/projects/{A}/procurement?doc={id}`.
  4. `FAILED`-вариант аналогично.

### TC-35 Аудит и целостность
- **Стратегия повторяемости:** генератор, teardown — удаление документов.
- **Шаги:**
  1. После загрузки (TC-10), relink (TC-30), reprocess и delete (TC-27): `GET /api/audit` (ADMIN, фильтр по сущности процуремент-документа). Ожидание: строки CREATE / UPDATE / DELETE; в UPDATE relink видны старый и новый `project_id`.
  2. После relink ссылки банковского журнала (FOR-07-04) на документ не изменились.
  3. Документ без проекта доступен как цель привязки в банковском журнале (TC-R2).

---

## Группа 5. Расход в проекте (как раньше + категория)

### TC-40 Загрузка на вкладке «Zakupy» проекта
- **Шаги:**
  1. Foreman открывает проект A → вкладка закупок → загрузка. Ожидание: проект зафиксирован (селектора проекта нет), есть выбор категории и блок «оплачено наличными» как раньше.
  2. Загрузить файл с категорией. Ожидание: после `PARSED` несопоставленные строки классифицированы категорией.
  3. Загрузить без категории. Ожидание: поведение как до изменений (регресс).
  4. `POST /api/procurement-documents` без `projectId`. Ожидание: 400 (проектный эндпоинт по-прежнему требует проект).

### TC-41 Диалог «Загрузить инвойс» в банковском журнале
- **Стратегия повторяемости:** генератор (файлы/поставщики с `{run-id}`), teardown — удаление созданных документов; строка журнала берётся тестовая (создаётся через импорт/фикстуру FOR-07-04).
- **Шаги:**
  1. Financier в `/company/bank-transactions` открывает строку, открывает диалог «Загрузить инвойс» (`ledger-upload-dialog`). Ожидание: поле проекта (`ledger-upload-project`) необязательное, есть опция «Без проекта».
  2. Загрузить без проекта (`ledger-upload-file`, `ledger-upload-submit`). Ожидание: запрос `POST /api/procurement-documents/company` без `projectId`; документ создан без проекта и привязан к строке журнала (`paymentChoice=LINKED_FROM_LEDGER`); в `/company/procurement` он виден с `NO_PROJECT`.
  3. Загрузить с проектом A. Ожидание: как раньше, документ принадлежит проекту.
  4. API: `POST /api/procurement-documents/company` с токеном worker/client. Ожидание: 403 (нужен `PURCHASES/CREATE`); `POST /api/procurement-documents` без `projectId`. Ожидание: 400.
  5. Тот же `/api/expenses` с `LINKED_FROM_LEDGER` отклоняется (TC-14) — различие эндпоинтов подтверждено.

---

## Группа 6. Локализация и доступность

### TC-50 Локализация PL/RU
- **Шаги:**
  1. Пройти `/expenses/new`, `/company/procurement`, диалог relink на PL, затем на RU. Ожидание: нет сырых ключей `companyProcurement.*`, `expense.*`, `error.*`.
  2. Вызвать каждую ошибку из TC-12, TC-14, TC-32. Ожидание: сообщение локализовано.
  3. Автотест паритета ключей `pl.json`/`ru.json` зелёный.

### TC-51 Доступность
- **Шаги:**
  1. Пройти форму загрузки и экран только с клавиатуры (Tab/Enter/Space/Esc). Ожидание: все контролы достижимы, фокус виден, диалоги закрываются по Esc.
  2. Проверить, что у полей есть подписи (label) и ошибки связаны с полем.
  3. Бейдж статуса связи (`company-link-status`) содержит текст, а не только цвет.

### TC-52 Нефункциональные: пагинация и фильтры
- **Стратегия повторяемости:** генератор — читающие запросы, данных не меняют.
- **Шаги:**
  1. `GET /api/company-purchases/documents` без параметров. Ожидание: страница размером 25, сортировка по `uploadedAt` убывание.
  2. `GET ...?size=1000`. Ожидание: размер ограничен 100 (или 400), ответ не падает.
  3. `GET ...?q={supplier-run-id}`, `?expenseCategoryId=`, `?uploadedById=`, `?uploadedFrom=&uploadedTo=`. Ожидание: фильтры применяются корректно.
  4. `GET /api/company-purchases/lines`. Ожидание: строки без `rawImageUrl/processedImageUrl`; URL изображений приходят только в `GET /documents/{id}` и строках документов страницы.
  5. UI: пагинация (`company-pagination`, `company-page-next/prev`) переключает страницы, фильтры сохраняются в URL.

---

## Регрессия

### TC-R1 Проектная вкладка закупок (FOR-06-03/04)
- Загрузка в проект, OCR, автосопоставление, relink/classify строки на вкладке Purchases работают как до изменений; список проекта не показывает документы без проекта.

### TC-R2 Банковский журнал (FOR-07-04)
- Цели привязки `PROCUREMENT` показывают документы без проекта и с проектом; оплата наличными из диалога создаёт кассовую запись; ручной relink строки журнала не затронут.

### TC-R3 Права и меню (FOR-03, FOR-07-00)
- Для каждой роли набор пунктов меню соответствует матрице; пункты «Staffing Gantt», «Payroll», «Bank transactions» не пропали; пункты `/projects`, `/rooms`, `/estimate` по-прежнему отсутствуют в главном меню.

### TC-R4 Старт приложения и guard
- Бэкенд стартует (проверка `PermissionAnnotationValidator` для `CompanyPurchasesController` и `ExpenseController`); тест `ProjectScopedService` guard зелёный.

---

## Шаблон MD-репорта прогона

Run-id: `<yyyyMMddHHmmss>`. Стратегия повторяемости: генератор + teardown. Стенд: `<commit/branch, дата>`.

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-10 | 2 | POST /api/expenses (worker, без проекта) | 200/201, QUEUED | | |
| 2 | TC-21 | 2 | UI: фильтр «Bez projektu» | только документы без проекта | | |
| 3 | TC-32 | 6 | PUT .../project (manager) | 403 | | |
| 4 | TC-27 | 5 | DELETE .../documents/{id} (manager) | 403 | | |
| 5 | TC-28 | 2 | UI: /company/procurement?doc={id} | строка раскрыта, viewer открыт | | |
| 6 | TC-20 | 5 | UI: меню «Dodaj wydatek» (client) | пункта нет | | |
| 7 | TC-41 | 2 | UI: инвойс из журнала без проекта | документ без проекта создан | | |

Итог: X пройдено / Y провалено / Z пропущено.

Дефекты:

| ID | Тест-кейс | Описание | Серьёзность | Статус |
|----|-----------|----------|-------------|--------|
| | | | | |
