# Тест-кейсы: FOR-05-08 — Подписание документов (универсальный модуль signable-document)

## Назначение и характер спеки

Спека имеет **и API-поверхность, и браузерные экраны**:

- **API** — против работающего в Docker приложения (поднятый docker-стек, НЕ Testcontainers): жизненный цикл документа (`DRAFT → PENDING_SIGNATURES → SIGNED`/`VOID`), генерация тела по шаблону (merge), заморозка неизменяемого PDF + `contentHash`, назначение подписантов, методы подписи (`PRINT`, `TABLET_INITIALS`, `ONLINE`, `PODPIS_GOV_PL`), провайдерский webhook-callback, уведомления, хэндофф активации проекта, ABAC-гейтинг и конфиденциальность себестоимости. Базовый URL `http://localhost:8080`, auth под `/api/auth`.
- **UI** — вкладка проектного воркспейса «Подписание документов / Podpisanie dokumentów» (`DocumentSigningTab` + `DocumentDetail`, диалоги `RequestSignaturesDialog`, `ScanUpload`, `TabletParafkaCapture`, `FormFieldsPanel`), а также админский редактор шаблонов `TemplateEditor` (вставка чипов-плейсхолдеров/полей, test-merge-превью, версии/активация, импорт `.docx`). UI-сценарии исполняются браузерным движком (headless / browser automation).

**Результат прогона для обеих частей — MD-репорты с таблицами** (шаг → запрос/действие → ожидание → факт → статус). **Скриншоты не требуются.**

Покрываемые требования: R1.3 (легальные переходы жизненного цикла), R1.4 (SIGNED iff все подписи SIGNED), R1.5 (заморозка неизменяемого PDF + `contentHash`, запрет правки тела после заморозки), R3.4 (детерминированная обработка неразрешённых плейсхолдеров), R4.5 (агрегированный прогресс подписания), R6.2 (PRINT — скан мокрой подписи), R6.3 (TABLET_INITIALS — парафка на планшете), R6.4 (ONLINE/PODPIS_GOV_PL — провайдер + проверка `contentHash`), R8.4 (CLIENT ограничен своей подписью/своими полями), R9.1 (вкладка «Подписание документов»), R9.2 (действия вкладки), R10.1 (контракт SIGNED → проект готов к `DRAFT → ACTIVE`), R11.2 (пять типов уведомлений с deep-link), R12.4 (полнота i18n PL/RU), R13.1 (REST-поверхность).

---

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции, в т.ч. `137`–`148` этой спеки), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN поднимается через `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
4. Перед прогоном дождаться готовности `backend` и завершения миграций. Признак готовности этой спеки — наличие ресурсов `SIGNABLE_DOCUMENTS`, `SIGNABLE_DOCUMENT_TYPES`, `DOCUMENT_TEMPLATES`, `COMPANY_PROFILE` и засеянных 14 типов документов (`CONTRACT_WORKS`, …, `KEY_HANDOVER`).
5. Провайдер подписи работает в режиме заглушки (`foremen.signing.provider=stub`): он синтезирует `providerRef` и в `verify` сверяет хэш синтетической «запечатанной» улики с `contentHash` документа — без обращения к живому QTSP / podpis.gov.pl.
6. UI-сценарии открывают `http://localhost:3000` и логинятся соответствующей ролью; админский редактор шаблонов открывается под ADMIN.

### Токены ролей для API

Все API-запросы (кроме логина) выполняются с заголовком `Authorization: Bearer <accessToken>` соответствующей роли. В сценариях используются алиасы: `ADMIN`, `MANAGER`, `FOREMAN`, `WORKER`, `FINANCIER`, `CLIENT`, а также `CLIENT2` (второй клиент того же проекта) и `CLIENT_OTHER` (клиент другого проекта / чужой пользователь для негативных кейсов владения).

---

## Стратегия повторяемости

**Комбинированная — генератор `run-id` + teardown. Применяется ко ВСЕМ группам ниже.**

- **Генератор:** на каждый прогон формируется `run-id` (timestamp/uuid). Все создаваемые пользователи — с email вида `test+{role}-{run-id}@example.com`; проект, смета, оферта и документы создаются заново на каждый прогон. `title` документа формируется с суффиксом `run-id` (например `Umowa {run-id}`), что исключает коллизии между прогонами. Админский шаблон, создаваемый в UI-кейсах редактора, именуется `tpl-{run-id}`.
- **Teardown:** в конце набора незавершённые документы переводятся в терминальный статус `VOID` (`POST .../void`), созданные уведомления удаляются их владельцами (`DELETE /api/notifications/{id}`), созданные пользователи деактивируются; созданные в прогоне черновики шаблонов **деактивируются, НЕ удаляются** (R2.5/R3a.1) — активной остаётся ранее засеянная версия. Сид-данные (типы документов, ресурсы/роли/операции, `CompanyProfile`) НЕ изменяются деструктивно — только читаются или создаются версии поверх.
- Каждая группа кейсов явно опирается на этот блок (`Повторяемость: генератор run-id + teardown`).

### Общий Setup, используемый большинством групп

- **S0. Логин ADMIN** — `POST /api/auth/login` `{email, password}` → **200** + `accessToken` (ADMIN).
- **S1. Поднять MANAGER, CLIENT, (при необходимости) FOREMAN** — ADMIN создаёт пользователей с email под `run-id` и соответствующими системными ролями; логин каждого → сохранить `accessToken`.
- **S2. Проект + APPROVED-оферта** — ADMIN/MANAGER создаёт проект под `run-id`, в нём смету и оферту; оферта доводится до `APPROVED` (нужно для хэндофф-кейсов R10). CLIENT и MANAGER добавляются членами проекта.
- **S3. CompanyProfile заполнен** — убедиться, что единственная строка `company_profile` содержит реквизиты исполнителя (`fullName`, `registeredAddress`, `nip`, `regon`, `representativeName`, `representativeRole`, `email`) — иначе `CompanyRequisitesResolver` вернёт неразрешённые плейсхолдеры. При пустой строке ADMIN заполняет её через `PUT /api/company-profile`.
- **S4. (при необходимости) второй/чужой клиент** — `CLIENT2` (член того же проекта) и `CLIENT_OTHER` (член другого проекта под тем же `run-id`).

> Примечание по non-bootstrap ролям: бутстрапится только ADMIN. Негативные ABAC-кейсы для FOREMAN/WORKER/FINANCIER документируются ожидаемыми результатами и подкреплены серверными интеграционными тестами (`ABAC`/startup-тесты из задачи 11.4). Там, где роль нельзя поднять в прогоне, статус в репорте — `N/A (покрыто интеграционным тестом)`.

---

# Часть A. API-тесты (docker-стек)

## Фича 1. Жизненный цикл документа и легальные переходы (R1.3, R13.1)

Покрывает R1.3 (легальные переходы), R1.7 (VOID исключается из «ожидающих», но читается), R13.1 (create/read/void). **Повторяемость:** генератор `run-id` + teardown.

### TC-LC-01 — Создание документа → 201/200, DRAFT
**Предусловия:** S0–S2.
1. `POST /api/signable-documents` (MANAGER) `{"projectId":<projectId>,"documentTypeId":<CONTRACT_WORKS id>,"templateLocale":"PL","title":"Umowa {run-id}"}` → **200/201**; тело: `status == "DRAFT"`, `projectId == <projectId>`, `documentTypeCode == "CONTRACT_WORKS"`, `documentUri == null`, `contentHash == null`.
2. `GET /api/signable-documents/{id}` (MANAGER) → **200**; те же поля; `signatures == []`, `progress.totalCount == 0`, `progress.allSigned == false`.

### TC-LC-02 — Легальный переход DRAFT → VOID
**Предусловия:** TC-LC-01 (DRAFT-документ).
1. `POST /api/signable-documents/{id}/void` (MANAGER) → **200**; `status == "VOID"`.
2. `GET /api/signable-documents?projectId=<projectId>` (MANAGER) → документ присутствует (читается для аудита, R1.7), но помечен `VOID` и исключён из «ожидающих» (например в выборке `status=PENDING_SIGNATURES` его нет).

### TC-LC-03 — Нелегальный переход (выход из терминального VOID) → 409 error.document.illegal.transition
**Предусловия:** TC-LC-02 (документ `VOID`).
1. `POST /api/signable-documents/{id}/request-signatures` (MANAGER) `{"method":"PRINT","signers":[{"role":"CLIENT"}]}` → **409**, код `error.document.illegal.transition`; статус не изменился.

### TC-LC-04 — request-signatures: DRAFT → PENDING_SIGNATURES
**Предусловия:** свежий DRAFT-документ (как TC-LC-01), тело сгенерировано (см. Фичу 2, шаг generate).
1. `POST /api/signable-documents/{id}/request-signatures` (MANAGER) `{"method":"PRINT","level":"AdES","signers":[{"role":"CLIENT"}]}` → **200**; `status == "PENDING_SIGNATURES"`; создана ≥1 подпись со статусом `PENDING`.

### TC-LC-05 — Повторный request-signatures на PENDING_SIGNATURES → 409
**Предусловия:** TC-LC-04 (документ `PENDING_SIGNATURES`).
1. `POST /api/signable-documents/{id}/request-signatures` (MANAGER) ещё раз → **409**, код `error.document.illegal.transition` (единственный легальный выход из `PENDING_SIGNATURES` — `void` или деривация `SIGNED`).

### TC-LC-06 — PENDING_SIGNATURES → VOID легален
**Предусловия:** свежий документ в `PENDING_SIGNATURES`.
1. `POST /api/signable-documents/{id}/void` (MANAGER) → **200**; `status == "VOID"`.

**Teardown группы:** все созданные документы в терминальном `VOID`; деактивировать пользователей `run-id`.

---

## Фича 2. Генерация тела по шаблону и неразрешённые плейсхолдеры (R3.4, R13.1)

Покрывает R3.4 (детерминированная обработка неразрешённых плейсхолдеров — пометка `«___»` ЛИБО 422), R3.5 (генерация пишет `documentUri` + `contentHash`), R13.1 (generate, body). **Повторяемость:** генератор `run-id` + teardown.

### TC-GEN-01 — generate на DRAFT с полным контекстом → 200, documentUri + contentHash
**Предусловия:** DRAFT-документ (TC-LC-01), `CompanyProfile` заполнен (S3), оферта `APPROVED`.
1. `POST /api/signable-documents/{id}/generate` (MANAGER) → **200**; тело: `documentUri != null`, `contentHash != null`.
2. `GET /api/signable-documents/{id}/document` (MANAGER) → **200**; возвращён текущий артефакт (тело DRAFT).

### TC-GEN-02 — Неразрешённые плейсхолдеры: режим fail → 422 error.document.unresolved.placeholders
**Предусловия:** DRAFT-документ на проекте, где часть источников пуста (например `CompanyProfile` очищен ADMIN на отдельном проекте под `run-id`); режим генерации — «fail on unresolved».
1. `POST /api/signable-documents/{id}/generate` (MANAGER) с режимом строгой генерации → **422**, код `error.document.unresolved.placeholders`; тело ответа содержит список неразрешённых токенов (например реквизиты компании).
2. Повторный `generate` в режиме «mark blank» → **200**; в сгенерированном теле неразрешённые токены помечены `«___»` (НЕ пустая строка).

### TC-GEN-03 — Детерминированность generate
**Предусловия:** DRAFT-документ с фиксированным контекстом.
1. `POST .../generate` (MANAGER) дважды подряд без изменения источников → оба **200**; множество `unresolvedPlaceholders` (в режиме mark-blank — набор помеченных `«___»` токенов) идентично между вызовами; одинаковый `contentHash` при идентичном теле.

### TC-GEN-04 — generate после заморозки запрещён → 409 error.document.frozen
**Предусловия:** документ в `PENDING_SIGNATURES` (после request-signatures).
1. `POST /api/signable-documents/{id}/generate` (MANAGER) → **409**, код `error.document.frozen`.
2. `PUT /api/signable-documents/{id}/body` (MANAGER) `{...}` → **409**, код `error.document.frozen` (тело неизменяемо после заморозки, R1.5).

**Teardown группы:** `void` созданных документов; восстановить `CompanyProfile` на вспомогательном проекте (если очищался).

---

## Фича 3. Заморозка неизменяемого PDF и стабильность contentHash (R1.5)

Покрывает R1.5 (заморозка PDF + `contentHash`; после заморозки — только значения полей и подписи), R1.6 (SIGNED полностью неизменяем). **Повторяемость:** генератор `run-id` + teardown.

### TC-FREEZE-01 — request-signatures замораживает PDF и фиксирует contentHash
**Предусловия:** DRAFT-документ с сгенерированным телом (TC-GEN-01); запомнить `contentHash0`.
1. `POST /api/signable-documents/{id}/request-signatures` (MANAGER) `{"method":"PRINT","level":"AdES","signers":[{"role":"CLIENT"}]}` → **200**; `status == "PENDING_SIGNATURES"`; `contentHash != null` (хэш замороженного PDF).
2. Запомнить `contentHashFrozen`.

### TC-FREEZE-02 — Заполнение form-fields не меняет contentHash
**Предусловия:** TC-FREEZE-01, у документа объявлено хотя бы одно form-field (например `pesel`).
1. `PUT /api/signable-documents/{id}/form-fields` (MANAGER или уполномоченный подписант) `{"values":{"pesel":"12345678901"}}` → **200**; значение сохранено.
2. `GET /api/signable-documents/{id}` → `contentHash == contentHashFrozen` (хэш тела стабилен; значения полей не меняют замороженный PDF, R1.5).

### TC-FREEZE-03 — Правка тела/типа/привязки после заморозки отклоняется
**Предусловия:** TC-FREEZE-01.
1. `PUT /api/signable-documents/{id}/body` (MANAGER) → **409** `error.document.frozen`.
2. Попытка сменить `documentTypeId`/`templateLocale` через любой путь обновления → **409** (`error.document.frozen`/`error.document.illegal.transition`); `contentHash` неизменен.

### TC-FREEZE-04 — SIGNED полностью неизменяем
**Предусловия:** документ доведён до `SIGNED` (см. Фичу 5).
1. `PUT /api/signable-documents/{id}/form-fields` (любой) → **409** (после SIGNED значения полей тоже заморожены, R1.6).
2. `POST /api/signable-documents/{id}/void` (MANAGER) → **409** `error.document.illegal.transition` (SIGNED не переходит в VOID).

**Teardown группы:** незавершённые документы → `void`.

---

## Фича 4. Подписанты и агрегированный прогресс (R4.5, R13.1)

Покрывает R4.2 (набор подписантов ≥1, статус PENDING), R4.3 (идентификация по user/role), R4.5 (прогресс: signed/total + outstanding), R13.1 (request-signatures, read). **Повторяемость:** генератор `run-id` + teardown.

### TC-SIG-01 — request-signatures с двумя подписантами создаёт 2 PENDING-подписи
**Предусловия:** DRAFT-документ с телом; в проекте есть CLIENT и MANAGER.
1. `POST .../request-signatures` (MANAGER) `{"method":"PRINT","signers":[{"role":"CLIENT"},{"userId":<managerId>}]}` → **200**; создано ровно 2 `DocumentSignature`, оба `status == "PENDING"`.
2. `GET /api/signable-documents/{id}` → `progress.totalCount == 2`, `progress.signedCount == 0`, `progress.allSigned == false`, `progress.outstanding` содержит обоих подписантов.

### TC-SIG-02 — Пустой набор подписантов → 400
1. `POST .../request-signatures` (MANAGER) `{"method":"PRINT","signers":[]}` → **400** (требуется ≥1 подписант, R4.2); документ остаётся `DRAFT`.

### TC-SIG-03 — Прогресс отражает частичное подписание
**Предусловия:** TC-SIG-01 (2 PENDING-подписи).
1. Первый подписант завершает подпись (PRINT, см. Фичу 5) → `progress.signedCount == 1`, `progress.totalCount == 2`, `progress.allSigned == false`; `outstanding` содержит оставшегося.

### TC-SIG-04 — decline переводит подпись в DECLINED, но не воидит документ (R4.4)
**Предусловия:** TC-SIG-01.
1. `POST /api/signable-documents/{id}/decline` (один из подписантов) `{"reason":"не согласен"}` → **200**; его подпись `status == "DECLINED"` с `declineReason`.
2. `GET /api/signable-documents/{id}` → `status == "PENDING_SIGNATURES"` (документ сам по себе не стал VOID); `progress.allSigned == false`.

**Teardown группы:** `void` документов; деактивировать пользователей `run-id`.

---

## Фича 5. Методы подписи PRINT и TABLET_INITIALS (R6.2, R6.3, R1.4, R13.1)

Покрывает R6.2 (PRINT — скан; без улики не SIGNED), R6.3 (TABLET_INITIALS — парафка; без улики не SIGNED), R1.4 (SIGNED iff все подписи SIGNED), R6.6 (деривация SIGNED одной операцией), R13.1 (media, sign-tablet). **Повторяемость:** генератор `run-id` + teardown.

### TC-PRINT-01 — PRINT: загрузка скана переводит подпись в SIGNED
**Предусловия:** документ `PENDING_SIGNATURES` с единственной PRINT-подписью подписанта.
1. `POST /api/signable-documents/{id}/media` (подписант, multipart: PDF/PNG скан мокрой подписи) → **200**; создан `DocumentMedia` `kind == "SCAN"`.
2. `GET /api/signable-documents/{id}` → подпись `status == "SIGNED"`, `signedAt != null`, `evidenceUri != null`.
3. Так как это последняя подпись → `status == "SIGNED"` (деривация, R1.4/R6.6); `progress.allSigned == true`.

### TC-PRINT-02 — SIGNED без улики недостижим → 409 error.document.evidence.required
**Предусловия:** PRINT-подпись без приложенного `DocumentMedia`.
1. Попытка пометить подпись SIGNED напрямую (путь завершения без загрузки скана) → **409**, код `error.document.evidence.required`; подпись остаётся `PENDING`.

### TC-TAB-01 — TABLET_INITIALS: захват парафки переводит подпись в SIGNED
**Предусловия:** документ `PENDING_SIGNATURES` с TABLET_INITIALS-подписью.
1. `POST /api/signable-documents/{id}/sign-tablet` (подписант, multipart: PNG изображения парафки) → **200**; создан `DocumentMedia` `kind == "TABLET_INITIAL"`; подпись `status == "SIGNED"`, `signedAt != null`.

### TC-TAB-02 — TABLET_INITIALS без изображения → 409 error.document.evidence.required
1. `POST /api/signable-documents/{id}/sign-tablet` (подписант) без файла → **4xx/409**, код `error.document.evidence.required`; подпись остаётся `PENDING`.

### TC-SIGNED-01 — SIGNED достигается только когда все подписи SIGNED (R1.4)
**Предусловия:** документ с 2 подписями (TC-SIG-01), первая уже SIGNED (TC-SIG-03).
1. Второй подписант завершает свою подпись (PRINT/TABLET) → **200**.
2. `GET /api/signable-documents/{id}` → `status == "SIGNED"`; `progress.signedCount == progress.totalCount`, `allSigned == true`. До завершения последней подписи документ оставался `PENDING_SIGNATURES`.

**Teardown группы:** документы в терминальном SIGNED остаются (неизменяемы); незавершённые → `void`; удалить созданные уведомления.

---

## Фича 6. Методы ONLINE / PODPIS_GOV_PL через провайдер (R6.4, R13.1)

Покрывает R6.4 (провайдерская церемония + сверка `contentHash` перед SIGNED), R5.3 (provider abstraction: initiate/callback/verify), R13.1 (sign, /api/signatures/callback). **Повторяемость:** генератор `run-id` + teardown. Провайдер — заглушка (`stub`).

### TC-PROV-01 — ONLINE: initiate → callback (hash совпадает) → SIGNED
**Предусловия:** документ `PENDING_SIGNATURES` с ONLINE-подписью; `contentHash` зафиксирован на заморозке.
1. `POST /api/signable-documents/{id}/sign` (подписант) `{"method":"ONLINE"}` → **200/202**; подпись получает `providerRef`, остаётся `PENDING` (церемония инициирована).
2. `POST /api/signatures/callback` (webhook, без Authorization) `{"providerRef":"<ref>","outcome":"SIGNED","evidence":"<sealed>"}` → **200**; stub-provider в `verify` сверяет хэш запечатанной улики с `contentHash`.
3. `GET /api/signable-documents/{id}` → подпись `status == "SIGNED"`, `signedAt != null`.

### TC-PROV-02 — callback с несовпадающим hash → 409 error.document.hash.mismatch
**Предусловия:** инициирована ONLINE-подпись (TC-PROV-01 шаг 1).
1. `POST /api/signatures/callback` `{"providerRef":"<ref>","outcome":"SIGNED","evidence":"<искажённая улика>"}` → **409**, код `error.document.hash.mismatch`; подпись остаётся `PENDING` (целостность не подтверждена).

### TC-PROV-03 — callback DECLINED → подпись DECLINED
1. `POST /api/signatures/callback` `{"providerRef":"<ref>","outcome":"DECLINED"}` → **200**; подпись `status == "DECLINED"`; документ не воидится.

### TC-PROV-04 — Webhook не требует аутентификации
1. `POST /api/signatures/callback` без заголовка `Authorization` (с валидным `providerRef`) → **200** (эндпоинт намеренно не ABAC-защищён, валидация по `providerRef` + `contentHash`, R13.1).
2. callback с неизвестным `providerRef` → **4xx** (не найдено/отклонено); никакая подпись не меняется.

### TC-PROV-05 — PODPIS_GOV_PL проходит ту же церемонию
1. `POST /api/signable-documents/{id}/sign` (подписант) `{"method":"PODPIS_GOV_PL"}` → **200/202**; далее callback с совпадающим hash → подпись `SIGNED` (тот же путь, что ONLINE).

**Teardown группы:** незавершённые документы → `void`.

---

## Фича 7. ABAC-гейтинг SIGNABLE_DOCUMENTS и серверное ограничение CLIENT (R8.4, R13.1)

Покрывает R8.2 (подпись — это UPDATE, без новой операции), R8.3 (ролевая матрица), R8.4 (CLIENT ограничен своей подписью/полями на сервере), R8.5 (create/generate/request-signatures/void — MANAGER/ADMIN, FOREMAN для своих типов), R13.1/R13.3. **Повторяемость:** генератор `run-id` + teardown.

### TC-ABAC-01 — CLIENT подписывает свою подпись (UPDATE own) → 200
**Предусловия:** документ `PENDING_SIGNATURES`; у CLIENT есть собственная PENDING-подпись.
1. CLIENT завершает свою подпись (`POST .../media` для PRINT, либо `POST .../sign` для ONLINE) → **200** (подпись = `UPDATE(own)`, R8.2).

### TC-ABAC-02 — CLIENT пытается create/generate/request-signatures/void → 403 error.document.operation.forbidden
**Предусловия:** S2 (CLIENT — член проекта).
1. `POST /api/signable-documents` (CLIENT) → **403**, код `error.document.operation.forbidden`.
2. `POST /api/signable-documents/{id}/generate` (CLIENT) → **403**.
3. `POST /api/signable-documents/{id}/request-signatures` (CLIENT) → **403**.
4. `POST /api/signable-documents/{id}/void` (CLIENT) → **403**. (Все эти пути отклоняются сервером операционным гардом, несмотря на номинальный UPDATE, R8.4/R8.5.)

### TC-ABAC-03 — CLIENT завершает ЧУЖУЮ подпись → 403
**Предусловия:** в документе есть подпись другого подписанта (не CLIENT).
1. CLIENT пытается завершить/отклонить подпись, принадлежащую другому → **403**, код `error.document.operation.forbidden` (ограничение own-signature, R8.4).

### TC-ABAC-04 — CLIENT заполняет только свои form-fields
**Предусловия:** документ `PENDING_SIGNATURES` с полями, у которых `ownerRole` различается.
1. `PUT /api/signable-documents/{id}/form-fields` (CLIENT) с попыткой записать поле с чужим `ownerRole` → **403/4xx**; собственное поле CLIENT записывается → **200**.

### TC-ABAC-05 — Caller без гранта/членства → 403
1. Пользователь без членства в проекте → `GET /api/signable-documents/{id}` → **403** (серверный отказ, не только UI).

### TC-ABAC-06 — WORKER/FINANCIER только R(own); FOREMAN CRU(own)
1. WORKER/FINANCIER → `GET /api/signable-documents?projectId=<id>` → **200**; любой write (`create`/`void`) → **403**.
2. FOREMAN → `create`/`read`/`update` в рамках своих типов (протоколы/акты/дефектный формуляр/заявление/передача ключей) → **200**; `delete` → **403**. _(Если роль не поднята в прогоне — `N/A, покрыто seed/ABAC интеграционным тестом задачи 11.4.)_

**Teardown группы:** `void` документов; деактивировать пользователей `run-id`.

---

## Фича 8. Хэндофф активации проекта по подписанному контракту (R10.1)

Покрывает R10.1 (контракт SIGNED на проекте с `APPROVED`-офертой → eligible для `DRAFT → ACTIVE`), R10.3 (не-контракт не триггерит), R10.4 (идемпотентность). **Повторяемость:** генератор `run-id` + teardown.

### TC-ACT-01 — Контракт SIGNED → проект eligible для ACTIVE
**Предусловия:** проект под `run-id` с офертой `APPROVED`; документ типа `CONTRACT_WORKS` доведён до `SIGNED` (Фича 5).
1. После деривации `SIGNED` → проверить, что проект сигнализирован как eligible для `DRAFT → ACTIVE` (например `GET` проекта показывает признак готовности/переход в `ACTIVE` по соответствующему пути FOR-05-07/13).
2. Хэндофф не переопределяет service-lock сметы и `OfferPriceSnapshot` (владение FOR-05-07/13) — только сигнал.

### TC-ACT-02 — Не-контрактный документ SIGNED не триггерит активацию (R10.3)
**Предусловия:** документ типа `WORKS_ACCEPTANCE` (не `CONTRACT_*`) доведён до `SIGNED`.
1. После SIGNED → статус проекта не меняется; никакого сигнала активации.

### TC-ACT-03 — Идемпотентность (R10.4)
**Предусловия:** проект уже `ACTIVE`.
1. Повторный сигнал (например подпись второго контракта / повторная деривация) → no-op; ошибок нет, статус проекта не ломается.

**Teardown группы:** SIGNED-документы остаются; деактивировать пользователей `run-id`.

---

## Фича 9. Уведомления модуля подписания (R11.2)

Покрывает R11.2 (пять типов уведомлений с deep-link), R11.1 (только через generic NotificationService), R11.3 (идентификация документа + кликабельность), R11.5 (нет уведомлений на DRAFT-правках). **Повторяемость:** генератор `run-id` + teardown (владелец удаляет свои уведомления).

### TC-NOTIF-01 — DOCUMENT_SENT_FOR_SIGNING каждому подписанту при request-signatures
**Предусловия:** DRAFT-документ с телом; подписанты CLIENT + MANAGER.
1. `POST .../request-signatures` (MANAGER) `{"method":"PRINT","signers":[{"role":"CLIENT"},{"userId":<managerId>}]}` → **200**.
2. `GET /api/notifications` (CLIENT) → **200**; есть уведомление `DOCUMENT_SENT_FOR_SIGNING` с `deepLink` на документ во вкладке подписания и идентификацией документа (тип + title/id).

### TC-NOTIF-02 — DOCUMENT_SIGNED_BY_PARTY владельцу при завершении одной подписи
**Предусловия:** TC-NOTIF-01; владелец — создатель (MANAGER).
1. Один подписант завершает свою подпись → `GET /api/notifications` (MANAGER-владелец) → уведомление `DOCUMENT_SIGNED_BY_PARTY` с deep-link.

### TC-NOTIF-03 — DOCUMENT_FULLY_SIGNED владельцу и всем подписантам при полной подписи
**Предусловия:** все подписи завершены (документ → SIGNED).
1. `GET /api/notifications` (владелец и каждый подписант) → у каждого есть `DOCUMENT_FULLY_SIGNED` с deep-link.

### TC-NOTIF-04 — DOCUMENT_SIGNING_DECLINED владельцу при decline
1. Подписант `POST .../decline` `{"reason":"..."}` → владелец получает `DOCUMENT_SIGNING_DECLINED` с deep-link.

### TC-NOTIF-05 — DOCUMENT_VOIDED всем ещё-PENDING подписантам при void PENDING-документа
**Предусловия:** документ `PENDING_SIGNATURES` с ≥1 PENDING-подписью.
1. `POST .../void` (MANAGER) → каждый ещё-`PENDING` подписант получает `DOCUMENT_VOIDED` с deep-link.

### TC-NOTIF-06 — Нет уведомлений на DRAFT-правках (R11.5)
**Предусловия:** DRAFT-документ.
1. `POST .../generate` / `PUT .../body` (MANAGER) несколько раз → `GET /api/notifications` у всех причастных → новых уведомлений модуля подписания НЕТ (эмиссия только с момента request-signatures).

**Teardown группы:** владельцы удаляют созданные уведомления (`DELETE /api/notifications/{id}`); незавершённые документы → `void`.

---

## Фича 10. Конфиденциальность клиентского ответа (R13.1, R13.3)

Покрывает R13.3 (ответы, доступные клиенту, не содержат cost/estimate/margin), R9.6. **Повторяемость:** генератор `run-id` + teardown.

### TC-CONF-01 — Клиентский ответ документа НЕ содержит cost/margin/estimate-unit-price
**Предусловия:** документ видим клиенту (CLIENT — подписант).
1. `GET /api/signable-documents/{id}` (CLIENT) → **200**; тело содержит только document-level поля (`status`, `signatures`, `progress`, `formFields`, `media`-summary, `title`, `documentTypeCode`).
2. Проверить структурно (на всех уровнях вложенности): нет полей `cost*`/`margin*`/`workerRate*`/`estimateUnitPrice` и любых себестоимостных/маржинальных имён.

**Teardown группы:** `void` документов.

---

# Часть B. Браузерные (UI) сценарии

Исполняются браузерным движком против `http://localhost:3000`. Результат — MD-репорт с таблицами (шаг → действие → ожидание → факт → статус). **Скриншоты не требуются.** **Повторяемость:** генератор `run-id` + teardown (данные готовятся по S0–S4, UI их переиспользует; незавершённые документы воидятся, созданные версии шаблонов деактивируются).

## Фича 11. Вкладка «Подписание документов» (R9.1, R9.2)

Покрывает R9.1 (вкладка со списком документов: тип, статус, прогресс, дата), R9.2 (действия вкладки), R9.3 (видимость действий по ABAC), R9.4 (обновление прогресса после действий).

### TC-UI-TAB-01 — Вкладка видна под именем «Подписание документов», гейт SIGNABLE_DOCUMENTS READ
1. Логин MANAGER, открыть `/projects/{projectId}/...` и перейти на вкладку `documentSigning`.
2. Ожидание: вкладка подписана «Podpisanie dokumentów / Подписание документов»; отрисован реальный `DocumentSigningTab`; список документов показывает тип, статус, прогресс подписания и дату создания (R9.1).

### TC-UI-TAB-02 — Создание и генерация черновика
1. Под MANAGER нажать «Создать документ», выбрать тип (`CONTRACT_WORKS`) и локаль шаблона (`PL`), указать title `Umowa {run-id}` → документ создан в статусе `DRAFT`.
2. Открыть `DocumentDetail`, нажать «Сгенерировать» → тело отрисовано (превью артефакта); при неразрешённых токенах показан их список.

### TC-UI-TAB-03 — Запрос подписей замораживает PDF (R9.2)
1. Под MANAGER на DRAFT-документе открыть `RequestSignaturesDialog`, выбрать подписантов, метод (`PRINT`), уровень (`AdES`) → подтвердить.
2. Ожидание: статус в UI → «PENDING_SIGNATURES» (локализовано); тело становится неизменяемым (нет кнопки правки тела); показан per-signer прогресс (R9.2/R9.4).

### TC-UI-TAB-04 — Загрузка скана (PRINT) и захват парафки (TABLET_INITIALS)
1. Под подписантом на `PENDING_SIGNATURES`-документе: `ScanUpload` → выбрать файл скана → подпись становится «SIGNED», прогресс обновился (R9.4).
2. Для TABLET-подписанта: `TabletParafkaCapture` → нарисовать парафку/загрузить изображение → подпись «SIGNED».

### TC-UI-TAB-05 — Decline, download, void (R9.2)
1. Подписант: «Отклонить» с указанием причины → его подпись помечена «DECLINED»; документ остаётся `PENDING_SIGNATURES`.
2. Любой уполномоченный: «Скачать» → загружается текущий артефакт.
3. MANAGER: «Аннулировать» (void) на `PENDING`-документе → статус «VOID».

### TC-UI-TAB-06 — Видимость действий по роли (R9.3)
1. Под CLIENT на видимом документе доступны только: подписать свою подпись, отклонить, заполнить свои form-fields, скачать. Отсутствуют: создать, сгенерировать, запросить подписи, аннулировать.
2. Под MANAGER доступен полный набор действий согласно статусу.

### TC-UI-TAB-07 — CLIENT не видит cost/estimate/margin (R9.6)
1. Под CLIENT на `DocumentDetail` отсутствуют любые ячейки/колонки себестоимости, маржи, смётной unit-price — только статус, подписи, прогресс, тело документа и form-fields.

## Фича 12. form-fields и прогресс (R9.2, R9.4)

### TC-UI-FF-01 — Заполнение form-fields только в PENDING, CLIENT ограничен своими
1. Под подписантом на `PENDING_SIGNATURES`-документе `FormFieldsPanel` позволяет ввести значения своих полей; поля чужого `ownerRole` недоступны/read-only (R8.4).
2. На `DRAFT`-документе панель form-fields недоступна (поля — часть замороженного артефакта).

### TC-UI-FF-02 — Прогресс обновляется после каждого действия (R9.4)
1. После завершения одной подписи индикатор прогресса на `DocumentDetail` обновляется (например «1 из 2 подписано»), список outstanding сокращается.

## Фича 13. Админский редактор шаблонов TemplateEditor (R3.4, R13.1)

Покрывает R3a.2 (WYSIWYG), R3a.3 (вставка чипов плейсхолдеров/полей), R3a.4 (test-merge превью + список неразрешённых, R3.4), R3a.5 (версии/активация), R3a.1 (CRUD + импорт `.docx`), R13.1 (admin DocumentTemplate CRUD). Доступ — ADMIN only.

### TC-UI-TPL-01 — Открытие редактора шаблонов (ADMIN only)
1. Логин ADMIN, открыть админский экран «Шаблоны документов» → отрисован список шаблонов (по типам/локалям); доступны create/import/activate/deactivate.
2. Под не-ADMIN экран недоступен (нет пункта меню / 403 при прямом переходе).

### TC-UI-TPL-02 — Вставка чипов плейсхолдеров и form-fields
1. В `TemplateEditor` открыть каталог плейсхолдеров (R3.3), выбрать `{ContactName}` → в тело вставлен визуально отличимый чип (без ручного ввода `{...}`).
2. Вставить form-field (например `pesel`) → чип поля добавлен; raw-токен `{Begindate}`, введённый вручную, также принят.

### TC-UI-TPL-03 — Test-merge превью со списком неразрешённых (R3a.4/R3.4)
1. Нажать «Test-merge» против выбранного проекта или sample-данных → показан отрендеренный результат и список неразрешённых плейсхолдеров.
2. На проекте с пустым `CompanyProfile` список неразрешённых содержит токены реквизитов компании (помечены `«___»` в превью, не пустые).

### TC-UI-TPL-04 — Версионирование и активация (R3a.5)
1. Отредактировать шаблон `tpl-{run-id}` и сохранить → `version` увеличился; предыдущая версия остаётся доступной для аудита.
2. Активировать версию → именно она используется генерацией; на пару (`documentType`, `locale`) активна ровно одна (при активации новой прежняя деактивируется).

### TC-UI-TPL-05 — Импорт .docx и деактивация (не удаление)
1. Импортировать `.docx` (например из `docs/templates/`) → создан шаблон-черновик.
2. «Деактивировать» шаблон → он скрыт из активных привязок, но читается (R2.5/R3a.1); кнопки физического удаления нет.

## Фича 14. Полнота i18n PL/RU в UI (R12.4)

Покрывает R12.2/R12.4 (все ключи модуля непусты в PL и RU).

### TC-UI-I18N-01 — Переключение языка PL ↔ RU без «сырых» ключей
1. На `DocumentSigningTab`/`DocumentDetail`/диалогах и в `TemplateEditor` переключить язык PL → RU → PL.
2. Ожидание: все метки (название вкладки, типы документов, статусы `DRAFT`/`PENDING_SIGNATURES`/`SIGNED`/`VOID`, методы, уровни SES/AdES/QES, все действия, прогресс, chrome редактора, валидации, пять типов уведомлений) локализованы; нет отображения «сырых» ключей вида `documentSigning.*` и нет пустых значений (R12.4).

---

# Регрессия

Раздел перепроверяет критичные сквозные пути и ранее закрытые инварианты после изменений в модуле подписания или смежных (FOR-05-07 уведомления, FOR-03-04/08 ABAC). **Повторяемость:** генератор `run-id` + teardown.

### TC-REG-01 — Сквозной happy-path контракта (API)
1. create (`CONTRACT_WORKS`) → generate → request-signatures (PRINT, 2 подписанта) → первая подпись (scan) → вторая подпись (scan) → документ `SIGNED`; `progress.allSigned == true`.
2. На проекте с `APPROVED`-офертой — проект сигнализирован eligible для `ACTIVE` (R10.1).
3. Все пять уведомлений (при соответствующих триггерах) доставлены с deep-link (R11.2).

### TC-REG-02 — Инвариант SIGNED iff все подписи SIGNED (повторная проверка R1.4)
1. Документ с 2+ подписями не достигает `SIGNED`, пока хотя бы одна `PENDING`/`DECLINED`; достигает `SIGNED` ровно в момент завершения последней (без ручного выставления статуса).

### TC-REG-03 — Неизменяемость после заморозки (повторная проверка R1.5/R1.6)
1. После request-signatures любые `generate`/`body`/смена типа → `409`; `contentHash` стабилен через заполнение form-fields; после `SIGNED` — полная неизменяемость.

### TC-REG-04 — ABAC CLIENT-ограничение (повторная проверка R8.4)
1. CLIENT: завершение своей подписи → 200; create/generate/request-signatures/void и завершение чужой подписи → 403.

### TC-REG-05 — Целостность провайдерской подписи (повторная проверка R6.4)
1. ONLINE/PODPIS_GOV_PL: callback с совпадающим hash → SIGNED; с искажённой уликой → `409 error.document.hash.mismatch`.

### TC-REG-06 — Регресс конфиденциальности (повторная проверка R13.3)
1. Клиентские ответы (`GET` документа) и UI (`DocumentDetail` под CLIENT) не содержат cost/margin/estimate-unit-price ни на одном уровне.

---

# Шаблон MD-репорта прогона

## Часть A — API (docker-стек)

| # | Тест-кейс | Шаг | Запрос | Ожидание | Факт | Статус |
|---|-----------|-----|--------|----------|------|--------|
| 1 | TC-LC-01 | 1 | POST /api/signable-documents | 200/201 + DRAFT | | |
| 2 | TC-LC-03 | 1 | POST .../request-signatures (на VOID) | 409 error.document.illegal.transition | | |
| 3 | TC-GEN-02 | 1 | POST .../generate (strict) | 422 error.document.unresolved.placeholders | | |
| 4 | TC-FREEZE-02 | 2 | GET .../{id} | contentHash == frozen | | |
| 5 | TC-PRINT-01 | 1 | POST .../media (scan) | 200 + kind=SCAN, подпись SIGNED | | |
| 6 | TC-PRINT-02 | 1 | complete без улики | 409 error.document.evidence.required | | |
| 7 | TC-PROV-02 | 1 | POST /api/signatures/callback (bad hash) | 409 error.document.hash.mismatch | | |
| 8 | TC-ABAC-02 | 1 | POST /api/signable-documents (CLIENT) | 403 error.document.operation.forbidden | | |
| 9 | TC-ACT-01 | 1 | contract SIGNED → проект eligible ACTIVE | eligible/ACTIVE | | |
| 10 | TC-NOTIF-01 | 2 | GET /api/notifications (CLIENT) | DOCUMENT_SENT_FOR_SIGNING + deepLink | | |

Итог части A: X пройдено / Y провалено / Z пропущено.

## Часть B — UI (браузерный движок)

| # | Тест-кейс | Шаг | Действие | Ожидание | Факт | Статус |
|---|-----------|-----|----------|----------|------|--------|
| 1 | TC-UI-TAB-01 | 1 | Открыть вкладку documentSigning | Список: тип/статус/прогресс/дата | | |
| 2 | TC-UI-TAB-03 | 2 | Request-signatures | Статус PENDING_SIGNATURES, тело заморожено | | |
| 3 | TC-UI-TAB-06 | 1 | CLIENT: набор действий | Только sign/decline/fill-own/download | | |
| 4 | TC-UI-TPL-02 | 1 | Вставить чип {ContactName} | Отличимый чип, без ручного {...} | | |
| 5 | TC-UI-TPL-03 | 1 | Test-merge | Рендер + список неразрешённых | | |
| 6 | TC-UI-TPL-04 | 2 | Активировать версию | Ровно одна активная на (type, locale) | | |
| 7 | TC-UI-I18N-01 | 2 | Переключить PL↔RU | Нет сырых ключей / пустых значений | | |

Итог части B: X пройдено / Y провалено / Z пропущено.

---

**Итог прогона:** X пройдено / Y провалено / Z пропущено.
**Run-id:** `<timestamp/uuid>`.
**Стратегия повторяемости:** генератор `run-id` + teardown (документы → VOID; уведомления удалены владельцами; версии шаблонов деактивированы, не удалены; пользователи `run-id` деактивированы).
