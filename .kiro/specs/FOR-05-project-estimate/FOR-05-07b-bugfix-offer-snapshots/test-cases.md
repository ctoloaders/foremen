# Тест-кейсы: FOR-05-07b — Багфикс снимков оферт (snapshot, матрица, PDF, восстановление, несколько оферт, имя, маркер пакета)

## Назначение и характер спеки

Спека правит поведение вкладки «Оферты» (`/projects/{id}/pricing`) и вкладки «Смета», владелец — FOR-05-07, данные пакета — FOR-05-06. Есть **и API-поверхность, и браузерные экраны**:

- **Часть A. API-тесты** — против приложения, работающего в Docker (поднятый docker-стек, НЕ Testcontainers). Базовый URL `http://localhost:8080`, auth под `/api/auth`.
- **Часть B. UI-тесты** — сценарии для браузерного движка (headless-браузер / browser automation) на `http://localhost:3000/projects/{PID}/pricing` и на вкладке сметы. Каждый шаг — действие в браузере (переход, ввод, клик, ожидание).

**Результат прогона для обеих частей — MD-репорты с таблицами** (шаг → запрос/действие → ожидание → факт → статус). Скриншоты не требуются.

Покрываемые требования: 1.1–1.8 / 2.1–2.8 (дефекты и ожидаемое поведение), 3.1–3.8 (сохранение поведения). Принятые допущения дизайна **A1–A7** (`design.md`) зафиксированы в кейсах ниже; если ответ пользователя по допущению изменится, правятся только помеченные кейсы:

| Допущение | Суть | Кейсы |
|-----------|------|-------|
| A1 | Restore заменяет всю смету одной транзакцией, пишет аудит, при заблокированной смете (не DRAFT) → 409 `error.estimate.locked` | TC-RST-01, TC-RST-03, TC-UI-RST-01, TC-UI-RST-03 |
| A2 | Approve одной оферты withdraw-ит все остальные нетерминальные оферты проекта | TC-MULTI-03, TC-UI-MULTI-02 |
| A3 | Legacy-оферта без snapshot: нет просмотра, PDF и restore | TC-LEG-01, TC-RST-04, TC-PDF-05 |
| A4 | Restore меняет смету → срабатывает реакция (виденные оферты → WITHDRAWN, включая исходную SENT); диалог показывает это заранее | TC-RST-05, TC-UI-RST-02 |
| A5 | Ячейки несуществующих комнат пропускаются и возвращаются в `skippedRooms` | TC-RST-02, TC-UI-RST-04 |
| A6 | «Обновить из сметы» — действие пользователя над DRAFT; автоматического обновления на каждую правку сметы нет | TC-SNAP-02, TC-SNAP-04 |
| A7 | Legacy DRAFT без snapshot получает его при send / refresh; legacy в других статусах остаются без snapshot | TC-LEG-02 |

---

## Запуск окружения

1. В корне репозитория: `docker compose up` — `postgres` (:5432), `liquibase` (миграции, включая **267** `drop-offers-active-project-index`), `backend` (:8080, профиль `docker`), `frontend` (:3000).
2. Первый ADMIN: `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
3. Перед прогоном дождаться завершения миграций. Проверка: в БД нет индекса `ux_offers_active_project`, есть `ix_offers_project_status`.
4. Заголовок языка `Accept-Language: pl` (по умолчанию) или `ru`; в UI язык фиксируется в начале кейса (по умолчанию RU).

### Токены ролей для API

Все запросы, кроме логина, идут с `Authorization: Bearer <accessToken>`. Алиасы: `ADMIN` (он же исполнитель), `MANAGER`, `CLIENT`.

---

## Стратегия повторяемости (обязательно, для всех групп)

**Комбинация: генератор `run-id` + teardown.**

- **Генератор.** На каждый прогон формируется `run-id` = `yyyyMMdd-HHmmss-xxxx`. Всё создаваемое получает `run-id`: проект `FOR-05-07b {run-id}`, комнаты `Room A/B/C {run-id}`, пользователь-клиент `test+client-{run-id}@example.com`, имена оферт `Offer-{run-id}-{n}`. Проект 1531 не используется как рабочий (он остаётся эталоном для регрессии 1.1, только чтение).
- **Setup (S0–S4)** выполняется один раз на прогон и создаёт собственный проект, поэтому повторный запуск не зависит от остатков предыдущего.
- **Teardown (T)** в конце каждой группы и прогона: все нетерминальные оферты проекта → `POST /api/offers/{id}/withdraw` (исполнитель), клиент деактивируется (`PUT /api/users/{id}` `active=false`), проект закрывается/архивируется штатным способом (или остаётся под `run-id` без влияния на следующий прогон). Смета, изменённая restore, возвращается к DRAFT-состоянию Setup повторным `POST /api/offers/{baselineOfferId}/restore` (если она ещё DRAFT).
- Тесты не пользуются фиксированными id и не требуют ручной чистки БД.

### Общий Setup

- **S0. Логин ADMIN** — `POST /api/auth/login` `{email,password}` → **200**, `accessToken`.
- **S1. Клиент** — ADMIN создаёт пользователя `CLIENT` `test+client-{run-id}@example.com`; логин → `clientToken`. (Также `MANAGER` при необходимости.)
- **S2. Проект и комнаты** — `POST /api/projects` `{"name":"FOR-05-07b {run-id}", …}` → `PID`; `POST /api/rooms` ×3 (`Room A/B/C {run-id}`) → `ROOM_A`, `ROOM_B`, `ROOM_C`. CLIENT добавляется в проект.
- **S3. Смета с данными** — `GET /api/estimates/project/{PID}` → `EID`. Две-три работы из разных категорий: `POST /api/estimate-lines` `{estimateId:EID, workItemId, unitId, lineNo, unitPrice}` → `LINE_1..LINE_3`; количества: `POST /api/estimate-line-room-qty` `{estimateLineId, roomId, quantity}`: `LINE_1` в A (2.0) и B (3.0), `LINE_2` в A (1.5), **`LINE_3` без количества ни в одной комнате** (работа «ни к какой комнате не применена»). Смета доводится до статуса, допускающего prepare (как в FOR-05-07, S2). Фиксируются ожидаемые `NET_0` (итог по `LINE_1`+`LINE_2`, `LINE_3` не влияет), `GROSS_0`.
- **S4. Пакет** — для кейсов с маркером: `POST /api/estimates/project/{PID}/apply-package` `{packageCode:"PREMIUM"}` (или любой существующий код `PKG`) и отдельный проект без пакета `PID_NOPKG` (повторить S2–S3 без apply-package).

Легенда ответов: `ExecutorOfferReadModel` (поля `id`, `name`, `status`, `totalNet`, `totalGross`, `hasSnapshot`), `SnapshotView`, `OfferSummary` (поля `appliedPackageCode`, `appliedPackageName`).

---

# Часть A. API-тесты (docker-стек, `http://localhost:8080`)

## Фича A1. Жизненный цикл snapshot: захват, обновление, заморозка (1.1, 2.1, 3.1)

**Повторяемость:** генератор `run-id` + teardown (T: withdraw всех нетерминальных оферт `PID`).

### TC-SNAP-01 — Prepare на пустой смете, затем наполнение и send: snapshot равен живым итогам (1.1, 2.1)
**Предусловия:** S0–S2; смета проекта **пустая** (S3 ещё не выполнен).
1. `POST /api/offers/project/{PID}/prepare` `{"name":"Offer-{run-id}-1"}` → **200**; `status == "DRAFT"`, `name == "Offer-{run-id}-1"`; запомнить `OFFER_1`. (Допустимый ответ при пустой смете — тот, который давал prepare до фикса; фиксируется в отчёте.)
2. Выполнить S3 (добавить строки и количества).
3. `POST /api/offers/{OFFER_1}/send` → **200**; `status == "SENT"` (или `OFFERED`-эквивалент FOR-05-07).
4. `GET /api/offers/{OFFER_1}/snapshot` (ADMIN) → **200**; `rooms` непусты, `groups` непусты, `totals.totalNet == NET_0`, `totals.totalGross == GROSS_0`; значения совпадают с `totalNet/totalGross` в `GET /api/offers/{OFFER_1}`.
5. Контрольный пункт регрессии 1.1: в snapshot нет `rooms: []` и нулевых итогов при ненулевом `totalNet`.

### TC-SNAP-02 — Явное обновление DRAFT: `refresh-snapshot` (2.1, A6)
**Предусловия:** S0–S3; новая DRAFT-оферта `OFFER_2`, подготовленная **до** добавления `LINE_2` (подготовить заново на копии S3 без `LINE_2` либо добавить `LINE_2` после prepare).
1. `GET /api/offers/{OFFER_2}/snapshot` → в snapshot нет работы `LINE_2`.
2. Добавить работу/количество (или изменить количество в `LINE_1`).
3. `GET /api/offers/{OFFER_2}/snapshot` → **без изменений** (нет автообновления на каждую правку, A6).
4. `POST /api/offers/{OFFER_2}/refresh-snapshot` (ADMIN) → **200**, ответ в форме `ExecutorOfferReadModel`; `hasSnapshot == true`.
5. `GET /api/offers/{OFFER_2}/snapshot` → присутствует `LINE_2`, `totals.totalNet` равен `totalNet` оферты.

### TC-SNAP-03 — После отправки snapshot заморожен (2.1, 3.1)
**Предусловия:** `OFFER_1` в статусе SENT (TC-SNAP-01).
1. Сохранить тело `GET /api/offers/{OFFER_1}/snapshot` как `SNAP_BEFORE`.
2. Изменить смету: `PUT /api/estimate-line-room-qty/{id}` увеличить количество `LINE_1` в A.
3. `GET /api/offers/{OFFER_1}/snapshot` → тело **побайтно равно** `SNAP_BEFORE` (смена сметы переводит `OFFER_1` в WITHDRAWN реакцией FOR-05-07, но snapshot не меняется).
4. `POST /api/offers/{OFFER_1}/refresh-snapshot` → **409**, код `error.offer.illegal.transition`.

### TC-SNAP-04 — Refresh неприменим к недрафтовой оферте и CLIENT-у (2.1, 3.1, A6)
1. `POST /api/offers/{id}/refresh-snapshot` для SENT/WITHDRAWN оферты → **409** `error.offer.illegal.transition`.
2. `POST /api/offers/{OFFER_2}/refresh-snapshot` с `clientToken` → **403** (нет `OFFERS`/UPDATE у CLIENT).
3. Без токена → **401**.

### TC-SNAP-05 — Строки без комнаты не попадают в snapshot (1.2, 2.2)
**Предусловия:** S3 (`LINE_3` не применена ни к одной комнате).
1. `POST /api/offers/project/{PID}/prepare` `{"name":"Offer-{run-id}-nr"}` → **200**; `OFFER_NR`.
2. `GET /api/offers/{OFFER_NR}/snapshot` → в `groups[*].rows[*]` **нет** работы `LINE_3`; у каждой строки есть хотя бы одна ячейка `cells[]` с `quantity > 0`.
3. Нет групп без строк; комната `ROOM_C` (без количеств) отсутствует в `rooms`.
4. Итог `totals.totalNet == NET_0` (лишняя строка не влияет).

### TC-SNAP-06 — Маркер пакета в snapshot, список, сравнение (1.8, 2.8)
**Предусловия:** S4 (в `PID` применён пакет `PKG`), `PID_NOPKG` без пакета.
1. `POST /api/offers/project/{PID}/prepare` → `OFFER_PKG`.
2. `GET /api/offers/{OFFER_PKG}/snapshot` → `appliedPackageCode == "PKG"`, `appliedPackageName` непусто и локализовано (PL/RU по `Accept-Language`).
3. `GET /api/offers/project/{PID}/list` → элемент `OFFER_PKG`: `appliedPackageCode == "PKG"`, `appliedPackageName` непусто.
4. `GET /api/offers/compare?left={OFFER_PKG}&right={OFFER_NR}` → в обеих сторонах присутствует маркер пакета (у `OFFER_NR` тот же пакет, т.к. подготовлена после apply-package; иначе `null`).
5. `POST /api/offers/project/{PID_NOPKG}/prepare` → `GET .../snapshot` → `appliedPackageCode == null`, `appliedPackageName == null`; в `list` те же `null` (маркер не показывается).

**Teardown группы:** withdraw всех нетерминальных оферт `PID`, `PID_NOPKG`.

---

## Фича A2. Несколько нетерминальных оферт (1.5, 2.5, 3.1, 3.4, 3.6)

**Повторяемость:** генератор `run-id` + teardown (T: withdraw оставшихся нетерминальных).

### TC-MULTI-01 — Вторая нетерминальная оферта создаётся (1.5, 2.5)
**Предусловия:** S0–S3; пустой список оферт `PID`.
1. `POST .../prepare` `{"name":"Offer-{run-id}-A"}` → **200**; `OFFER_A`.
2. `POST .../prepare` `{"name":"Offer-{run-id}-B"}` → **200** (раньше: 409 `error.offer.active.exists`); `OFFER_B != OFFER_A`.
3. `GET /api/offers/project/{PID}/list` → обе оферты присутствуют, у каждой свой `status`, `totalNet`, `hasSnapshot == true`.
4. Ошибка `error.offer.active.exists` больше не возвращается ни в одном сценарии.

### TC-MULTI-02 — Две SENT-оферты независимо (2.5)
1. `POST /api/offers/{OFFER_A}/send` → **200**; `POST /api/offers/{OFFER_B}/send` → **200**.
2. `GET /api/offers/project/{PID}/list` (CLIENT) → обе видны (CLIENT видит только отправленные); без `baseUnitPrice`/`baseValueNet`.
3. У каждой оферты свой замороженный snapshot (`GET .../snapshot` двух оферт возвращает разные `name`/`id`).

### TC-MULTI-03 — Approve одной withdraw-ит остальные (2.5, A2, 3.1)
**Предусловия:** TC-MULTI-02 (две SENT).
1. `POST /api/offers/{OFFER_A}/approve` (CLIENT) → **200**; `OFFER_A.status == "APPROVED"`.
2. `GET /api/offers/{OFFER_B}` (ADMIN) → `status == "WITHDRAWN"`.
3. Аудит: запись с `operation == "SYSTEM_WITHDRAW_ON_APPROVE"` для `OFFER_B`, ссылка на `OFFER_A` (`GET /api/audit?...` либо проверка по БД).
4. Повторный approve `OFFER_B` → **409** `error.offer.illegal.transition`.
5. `POST .../prepare` после approve допустим (новая оферта создаётся, как в FOR-05-07).

### TC-MULTI-04 — Изменение сметы withdraw-ит все виденные оферты (3.6)
**Предусловия:** две SENT-оферты `OFFER_C`, `OFFER_D` в свежем проекте (повторить S2–S3 и prepare/send ×2).
1. `PUT /api/estimate-line-room-qty/{id}` — изменить количество.
2. `GET /api/offers/{OFFER_C}` и `GET /api/offers/{OFFER_D}` → обе `WITHDRAWN` (раньше реагировала только оферта с max id).
3. Аудит: по одной записи реакции на каждую оферту.
4. DRAFT-оферта проекта (если есть) реакцией **не** затрагивается: статус DRAFT.

### TC-MULTI-05 — Правила имени сохранены (3.4)
1. `POST .../prepare` `{"name":"   "}` → **200**, `name == "Offer {N}"` (дефолт).
2. `POST .../prepare` без тела/без `name` → **200**, дефолтное имя.
3. `POST .../prepare` `{"name":"  Offer-{run-id}-trim  "}` → **200**, `name == "Offer-{run-id}-trim"` (обрезано).
4. `POST .../prepare` с именем длиной 255 → **200**; длиной 256 → **400**, код `error.offer.name.too.long`; оферта не создана.

**Teardown группы:** withdraw всех нетерминальных оферт проектов группы.

---

## Фича A3. PDF оферты (1.4, 2.4, 3.2, 3.3, 3.8)

**Повторяемость:** генератор `run-id` + teardown (PDF не хранится на сервере, чистка не требуется; T: withdraw оферт).

### TC-PDF-01 — PDF для исполнителя (2.4)
**Предусловия:** S0–S3; `OFFER_PDF` (SENT, со snapshot, пакет применён, S4).
1. `GET /api/offers/{OFFER_PDF}/pdf` (ADMIN, `Accept-Language: pl`) → **200**; `Content-Type: application/pdf`; `Content-Disposition` начинается с `attachment; filename=` и содержит `{OFFER_PDF}` и `.pdf`.
2. Тело начинается с `%PDF`, размер > 0.
3. Текст PDF (парсинг) содержит: имя оферты, название проекта, название пакета, название категории работ, названия комнат `Room A/B {run-id}`, итоги net/VAT/gross, ставку VAT.
4. Повтор с `Accept-Language: ru` → **200**, подписи на русском.

### TC-PDF-02 — PDF для CLIENT без конфиденциальных данных (2.4, 3.2)
**Предусловия:** `OFFER_PDF` SENT.
1. `GET /api/offers/{OFFER_PDF}/pdf` (CLIENT) → **200**, `%PDF`.
2. Текст PDF **не содержит** базовых цен, сравнения трудозатрат, маржи; содержит клиентские итоги, совпадающие с `GET .../snapshot` (CLIENT).

### TC-PDF-03 — DRAFT недоступен CLIENT (2.4, 3.2)
**Предусловия:** DRAFT-оферта `OFFER_DR`.
1. `GET /api/offers/{OFFER_DR}/pdf` (CLIENT) → **404**.
2. `GET /api/offers/{OFFER_DR}/pdf` (ADMIN) → **200** (из текущего сохранённого snapshot).

### TC-PDF-04 — Нет токена / чужой проект
1. Без токена → **401**.
2. CLIENT другого проекта (`CLIENT_OTHER`) на `OFFER_PDF` → **403/404** (как для `GET /snapshot`).
3. Несуществующий id → **404**.

### TC-PDF-05 — Legacy без snapshot (3.3, A3)
**Предусловия:** оферта с `snapshot = NULL` (legacy; в локальной БД это 7 из 8 оферт проекта 1531, либо создать вставкой в БД на `PID` и пометить `run-id` в имени).
1. `GET /api/offers/{LEGACY}/pdf` (ADMIN) → **404**, код `error.offer.snapshot.missing`.

### TC-PDF-06 — Сбой рендера (негативный, по возможности)
1. При принудительной ошибке рендерера (стенд с отключённым шрифтом/подменой `PdfRenderer`) → **502**, код `error.offer.pdf.failed`. Если стенд не воспроизводит — статус «пропущен», основание в отчёте (покрыто `OfferHtmlBuilderTest`).

### TC-PDF-07 — PDF подписи/проформ не изменился (3.8)
1. Сгенерировать PDF подписания/клиентской проформы теми же вызовами, что в тест-кейсах FOR-05-08 / FOR-08-xx → **200**, `%PDF`, содержание соответствует предыдущему прогону (сравнение по ключевым фразам).

**Teardown группы:** withdraw оферт.

---

## Фича A4. Восстановление сметы из snapshot (1.6, 2.6, 3.1, 3.3, 3.6, 3.7)

**Повторяемость:** генератор `run-id` + teardown. Каждый кейс работает на собственном проекте под `run-id`, поэтому порядок кейсов не важен. T: возврат сметы в исходное состояние (restore базовой оферты) и withdraw оферт.

Вспомогательно: **BASE** — оферта, снятая с исходной сметы S3 (prepare, затем send, чтобы snapshot заморожен), `BASE_NET = NET_0`.

### TC-RST-01 — Успешное восстановление в изменённую DRAFT-смету (2.6, A1)
**Предусловия:** S0–S3, `BASE` подготовлена и **не** отправлена или отправлена (snapshot v2 есть). Смета в DRAFT.
1. Изменить смету: добавить `LINE_4`, удалить `LINE_2`, поменять количество `LINE_1` в A, сменить комментарий.
2. `POST /api/offers/{BASE}/restore` (ADMIN) → **200**; тело: `restoredLines >= 2`, `skippedWorks == 0`, `skippedRooms == []`, `estimateTotals.totalNet == BASE_NET`, `totalVat`, `totalGross` присутствуют.
3. `GET /api/estimates/project/{PID}/matrix` → строки и количества равны S3: `LINE_4` отсутствует, `LINE_2` есть, количество `LINE_1` в A равно 2.0; комментарий и `unitPriceOverride` равны snapshot.
4. `appliedPackageCode` сметы равен значению из snapshot (пакет `PKG` из S4, либо `null`, если в snapshot его нет — отдельная проверка на `PID_NOPKG`).
5. Аудит: **ровно одна** запись `entityClass=Offer`, `operation=RESTORE_ESTIMATE` с before/after (счётчики и итоги), пользователь — ADMIN.
6. Повторный `restore` той же оферты → **200**, результат идентичен (идемпотентность содержания), ещё одна аудит-запись.

### TC-RST-02 — Пропущенные комнаты в ответе (A5)
**Предусловия:** `BASE` со snapshot по комнатам A и B.
1. `DELETE /api/rooms/{ROOM_B}` (допустимо, если нет блокирующих зависимостей).
2. `POST /api/offers/{BASE}/restore` → **200**; `skippedRooms` содержит `{"roomId": ROOM_B, "label": "Room B {run-id}"}`; `restoredLines` учитывает оставшиеся строки.
3. В смете нет количеств по `ROOM_B`; количества по `ROOM_A` восстановлены.
4. `estimateTotals` рассчитаны штатным пересчётом (а не скопированы из snapshot) и меньше `BASE_NET`.

### TC-RST-03 — Смета заблокирована → 409 (A1)
**Предусловия:** смета проекта переведена из DRAFT (в статус не-DRAFT, как в TC FOR-05-03 «e7»).
1. `POST /api/offers/{BASE}/restore` (ADMIN) → **409**, код `error.estimate.locked`.
2. `GET .../matrix` → без изменений (все строки/количества как до вызова).
3. Аудит `RESTORE_ESTIMATE` **не** записан.

### TC-RST-04 — Нет snapshot / snapshot v1 → 404 (3.3, A3)
1. `POST /api/offers/{LEGACY}/restore` (оферта с `snapshot = NULL`) → **404**, код `error.offer.snapshot.missing`.
2. То же для оферты со snapshot версии 1 (подготовлена до фикса, нет `lineNo`/`unitPriceOverride`) → **404** `error.offer.snapshot.missing`.
3. Смета не менялась.
4. Несуществующий id → **404**.

### TC-RST-05 — Реакция на изменение сметы после restore (A4, 3.6)
**Предусловия:** `BASE` SENT; ещё одна SENT-оферта `OFFER_X`; смета в DRAFT.
1. `POST /api/offers/{BASE}/restore` → **200**.
2. После коммита: `GET /api/offers/{BASE}` и `GET /api/offers/{OFFER_X}` → обе `WITHDRAWN` (виденные оферты, включая исходную).
3. Аудит реакции — по записи на каждую.

### TC-RST-06 — Права (3.1)
1. `POST /api/offers/{BASE}/restore` с `clientToken` → **403**.
2. Без токена → **401**.

### TC-RST-07 — Материалы не восстанавливаются, но пересчитываются (границы)
1. После restore `GET /api/estimates/project/{PID}/materials` → выбор материалов выводится штатным путём пересчёта; ошибок нет; ответ 200.

**Teardown группы:** restore базовой оферты в DRAFT-смету, withdraw оферт, деактивация клиента.

---

## Фича A5. Legacy-оферты (3.3, A3, A7)

**Повторяемость:** генератор `run-id` + teardown (legacy создаются вставкой/миграционным способом в проект под `run-id` и удаляются/withdraw-ятся в T).

### TC-LEG-01 — Legacy без snapshot (3.3, A3)
**Предусловия:** `LEGACY` (`snapshot = NULL`, статус SENT/APPROVED).
1. `GET /api/offers/project/{PID}/list` → элемент: `hasSnapshot == false`, `appliedPackageCode == null`.
2. `GET /api/offers/{LEGACY}/snapshot` → **404**.
3. `GET /api/offers/{LEGACY}/pdf` → **404** `error.offer.snapshot.missing`.
4. `POST /api/offers/{LEGACY}/restore` → **404** `error.offer.snapshot.missing`.
5. `GET /api/offers/compare?left={LEGACY}&right={OFFER_A}` → отказ (**400/404**, как до фикса).
6. Snapshot у legacy в не-DRAFT статусах не появляется ни одним из вызовов (после шагов 1–5 `hasSnapshot` по-прежнему `false`).

### TC-LEG-02 — Legacy DRAFT получает snapshot при send/refresh (A7)
**Предусловия:** `LEGACY_DRAFT` (`snapshot = NULL`, статус DRAFT, смета с данными S3).
1. `POST /api/offers/{LEGACY_DRAFT}/refresh-snapshot` → **200**; `hasSnapshot == true`; snapshot равен текущей смете.
2. Второй legacy DRAFT `LEGACY_DRAFT2`: `POST .../send` → **200**; `GET .../snapshot` → **200**, итоги = `totalNet` оферты; далее заморожен.

**Teardown группы:** withdraw / удаление созданных legacy.

---

# Часть B. UI-тесты (браузерный движок, `http://localhost:3000`)

## Общие предусловия Части B

- Данные Части A (проект `PID` под `run-id`, S0–S4) используются как фикстура; UI-кейсы **создают собственные оферты** с именем `UI-{run-id}-{n}`.
- Исполнитель — ADMIN (RU, если не указано иное). Вход — форма логина; сессия сбрасывается между кейсами (новый контекст браузера / `localStorage.clear()`).
- Теги `data-testid` берутся из реализации (список в разделе «Справочник testid»). Для действий, у которых `testid` не зависит от оферты (`offer-send`, `offer-withdraw`, `offer-approve`, `offer-reject`, `offer-package-select`), селектор **ограничивается карточкой** `[data-testid="offer-card-{id}"]`.
- Критерий успеха — только структурные проверки DOM/URL/сети; скриншоты не обязательны.

### Справочник testid

| Элемент | testid |
|---------|--------|
| Вкладка оферт | `offer-tab` |
| Блок «Подготовить оферту» (вкладка оферт) | `offer-prepare-block`, `offer-name-input`, `offer-prepare` |
| Список ожидающих оферт | `offer-awaiting-list` |
| История версий | `offer-versions` |
| Карточка оферты | `offer-card-{id}` (атрибут `data-offer-id`), `offer-card-actions-{id}`, `offer-card-no-snapshot-{id}`, `offer-name` |
| Действия карточки | `offer-view-snapshot-{id}`, `offer-refresh-snapshot-{id}`, `offer-pdf-{id}`, `offer-restore-{id}` (+ `-dialog-`, `-confirm-`, `-cancel-`, `-impact-`, `-hint-`) |
| Действия без id (внутри карточки) | `offer-send`, `offer-withdraw`, `offer-approve`, `offer-reject`, `offer-package-select` |
| Матрица snapshot | `offer-snapshot-matrix`, `offer-snapshot-matrix-grid`, `offer-snapshot-matrix-total-net`, `estimate-group-header-{cat}`, `estimate-work-row-{id}`, `estimate-cell-{work}-{room}` |
| Маркер пакета | `offer-package-marker` |
| Смета: кнопка в шапке | `estimate-prepare-offer` |
| Диалог имени (смета) | `prepare-offer-dialog`, `prepare-offer-name-input`, `prepare-offer-confirm`, `prepare-offer-cancel` |

---

## Фича B1. Диалог имени при «Przygotuj ofertę» на вкладке сметы (1.7, 2.7, 3.4)

**Повторяемость:** генератор `run-id` (имена оферт `UI-{run-id}-n`) + teardown (withdraw созданных оферт через API по `PID`).

### TC-UI-NAME-01 — Подтверждение с именем создаёт оферту с этим именем (2.7)
**Предусловия:** ADMIN, RU, `PID` со сметой S3, оферт в проекте нет.
1. Открыть `/projects/{PID}/estimate` (вкладка сметы) → отображается матрица; кнопка `estimate-prepare-offer` доступна.
2. Нажать `estimate-prepare-offer` → открывается `prepare-offer-dialog`; **в сети нет** запроса `POST /api/offers/project/{PID}/prepare`; URL не изменился.
3. Ввести в `prepare-offer-name-input` значение `UI-{run-id}-1`.
4. Нажать `prepare-offer-confirm` → отправлен один `POST .../prepare` с `{"name":"UI-{run-id}-1"}` → **200**.
5. Только после успешного ответа выполнена навигация на `/projects/{PID}/pricing`.
6. На вкладке оферт есть карточка с `[data-testid="offer-name"]` = `UI-{run-id}-1`.

### TC-UI-NAME-02 — Пустое имя допустимо, подставляется «Offer N» (2.7, 3.4)
1. На вкладке сметы нажать `estimate-prepare-offer` → диалог открыт; поле пустое, placeholder содержит «Offer N».
2. Нажать `prepare-offer-confirm` без ввода → один `POST .../prepare` (имя пустое/отсутствует) → **200**.
3. Переход на `/projects/{PID}/pricing`; имя новой карточки соответствует шаблону `Offer {N}`.

### TC-UI-NAME-03 — Отмена ничего не создаёт (2.7)
1. Нажать `estimate-prepare-offer` → диалог открыт.
2. Ввести `UI-{run-id}-cancel`; нажать `prepare-offer-cancel` → диалог закрыт.
3. В сети нет `POST .../prepare`; URL остаётся `/projects/{PID}/estimate`.
4. `GET /api/offers/project/{PID}/list` не содержит оферты с именем `UI-{run-id}-cancel` (проверка через API).
5. Закрытие по Esc или клику вне диалога даёт тот же результат.

### TC-UI-NAME-04 — Ограничение 255 символов (2.7, 3.4)
1. В диалоге ввести строку из 256 символов → поле не принимает больше 255 (`maxLength`) либо показывает ошибку валидации и блокирует confirm.
2. Ввести ровно 255 → confirm → `POST` → **200**; оферта создана.
3. Обход ограничения (вставка 300 символов) → в запросе не более 255; если же ответ **400** `error.offer.name.too.long` — показан понятный текст ошибки, диалог остаётся открытым, навигации нет.

### TC-UI-NAME-05 — Ошибка prepare не навигирует (2.7)
1. Подменить ответ prepare на 500 (route-перехват) → нажать confirm.
2. Показан toast ошибки; URL остаётся `/projects/{PID}/estimate`; оферта не создана.

---

## Фича B2. Карточки оферт и несколько ожидающих (1.5, 2.5, 3.4)

**Повторяемость:** генератор `run-id` + teardown (withdraw всех нетерминальных).

### TC-UI-MULTI-01 — Подготовка нескольких оферт со вкладки оферт (2.5)
**Предусловия:** ADMIN, `/projects/{PID}/pricing`, S3.
1. Открыть вкладку: виден `offer-tab`, блок `offer-prepare-block` **показан всегда** (также когда уже есть ожидающая оферта).
2. Ввести в `offer-name-input` `UI-{run-id}-A`, нажать `offer-prepare` → в `offer-awaiting-list` появилась карточка `offer-card-{id}` с именем `UI-{run-id}-A`, статус DRAFT.
3. Повторить с `UI-{run-id}-B` → в списке **две** карточки; ошибки «уже существует активная оферта» нет.
4. Нажать `offer-send` внутри карточки A → статус A = «Отправлена»; карточка B по-прежнему DRAFT.
5. Нажать `offer-send` внутри карточки B → обе в статусе «Отправлена»; обе в `offer-awaiting-list`.
6. У каждой карточки свои итоги (`totalNet`) и свои действия в `offer-card-actions-{id}`.

### TC-UI-MULTI-02 — Approve одной убирает остальные из «ожидающих» (2.5, A2)
**Предусловия:** TC-UI-MULTI-01, две SENT-оферты; вход под CLIENT (или ADMIN с правом APPROVE по настройкам стенда).
1. Открыть `/projects/{PID}/pricing` → две карточки в `offer-awaiting-list`.
2. В карточке A нажать `offer-approve` → после подтверждения (если запрошено) список обновился: карточка B исчезла из `offer-awaiting-list` (WITHDRAWN) и находится в `offer-versions` со статусом «Отозвана».
3. Карточка A в статусе «Согласована»; действия отправки/отзыва недоступны.
4. Обновить страницу (F5) → состояние сохранилось.

### TC-UI-MULTI-03 — Действия строго по карточкам (3.1)
**Предусловия:** две SENT-оферты A и B.
1. В карточке B нажать `offer-withdraw` (селектор в пределах `offer-card-{B}`) → B отозвана, A **не** затронута (статус остаётся SENT).
2. Для CLIENT: `offer-send`/`offer-withdraw` отсутствуют; `offer-approve`/`offer-reject` доступны только у отправленных.
3. DRAFT-оферта у CLIENT не отображается вовсе.

### TC-UI-MULTI-04 — Имя из вкладки оферт: правила (3.4)
1. В `offer-name-input` ввести пустую строку / пробелы → `offer-prepare` → создана оферта «Offer N».
2. Ввести 256 символов → клиентская валидация не пропускает (zod) либо ограничение `maxLength`; оферта не создаётся.

---

## Фича B3. Просмотр snapshot как матрицы сметы (1.2, 1.3, 2.2, 2.3, 3.3, 3.7)

**Повторяемость:** генератор `run-id` + teardown.

### TC-UI-SNAP-01 — Матрица работ × комнаты, сгруппированная по категориям (2.3)
**Предусловия:** ADMIN, `PID` (S3), оферта `UI-{run-id}-S` создана (SENT или DRAFT со snapshot).
1. На `/projects/{PID}/pricing` в карточке нажать `offer-view-snapshot-{id}` → открыт `offer-snapshot-matrix` с `offer-snapshot-matrix-grid`.
2. Присутствуют заголовки групп `estimate-group-header-{cat}` для категорий работ `LINE_1`, `LINE_2`.
3. Строки `estimate-work-row-{id}` соответствуют работам оферты; ячейки `estimate-cell-{work}-{room}` для `LINE_1` × A = 2, `LINE_1` × B = 3, `LINE_2` × A = 1,5.
4. Колонки комнат — только `Room A`, `Room B` (комнаты `Room C` нет).
5. `offer-snapshot-matrix-total-net` равен `NET_0` (и значению `totalNet` карточки).
6. Структура сопоставима со вкладкой сметы (сгруппированная сетка, подписи комнат в колонках).

### TC-UI-SNAP-02 — Нет строк без комнаты (1.2, 2.2)
**Предусловия:** S3 содержит `LINE_3` без количеств; оферта подготовлена при её наличии.
1. Открыть матрицу (`offer-view-snapshot-{id}`).
2. `estimate-work-row-{LINE_3}` **отсутствует**; нет групп без строк.
3. Для старого (v1) snapshot с пустой строкой (если есть в БД): строка всё равно не показывается (read-time guard).

### TC-UI-SNAP-03 — Режим «только чтение» (2.3, 3.7)
1. В открытой матрице: клик по ячейке не открывает редактор; нет полей ввода количества.
2. Нет элементов постановки в очередь (staging), назначения исполнителей, молотка/отчёта (`hammer`/`report`) и кнопок массовых действий.
3. Для CLIENT: нет колонок/полей себестоимости, базовой цены, сравнения трудозатрат.
4. Вкладка сметы (`/estimate`) после просмотра работает как прежде: редактирование ячейки по-прежнему доступно (3.7).

### TC-UI-SNAP-04 — Просмотр из истории и из карточки (2.3)
1. Для терминальной оферты в `offer-versions` присутствует действие просмотра → открывается та же матрица.
2. Для оферты без snapshot (legacy) действия просмотра нет, вместо него `offer-card-no-snapshot-{id}` / метка «нет снимка» (3.3).

### TC-UI-SNAP-05 — «Обновить из сметы» для DRAFT (2.1, A6)
**Предусловия:** DRAFT-оферта, затем в смету добавлена работа.
1. Открыть матрицу: новой работы нет.
2. Закрыть, нажать `offer-refresh-snapshot-{id}` → toast «снимок обновлён».
3. Открыть матрицу: работа присутствует; `offer-snapshot-matrix-total-net` равен итогу карточки.
4. Для SENT/APPROVED оферты кнопки `offer-refresh-snapshot-{id}` нет.

---

## Фича B4. PDF (1.4, 2.4, 3.2, 3.3)

**Повторяемость:** генератор `run-id` + teardown (файлы загрузок удаляются из папки автоматизации).

### TC-UI-PDF-01 — Скачивание PDF исполнителем (2.4)
**Предусловия:** ADMIN, оферта со snapshot.
1. В карточке нажать `offer-pdf-{id}` → выполнен `GET /api/offers/{id}/pdf` → **200**; открыта новая вкладка с blob-URL (или инициирована загрузка), тип `application/pdf`.
2. Файл начинается с `%PDF`.
3. Повторное нажатие работает (blob-URL освобождается через ≈60 с, без утечки ошибок в консоль).

### TC-UI-PDF-02 — Ошибка PDF показывает toast (2.4)
1. Перехватить `GET .../pdf` и вернуть 502 → нажать `offer-pdf-{id}`.
2. Показан toast с текстом `offers.pdf.error` (локализованный); интерфейс остаётся рабочим, кнопка снова активна.

### TC-UI-PDF-03 — PDF для CLIENT и legacy (3.2, 3.3)
1. Под CLIENT: `offer-pdf-{id}` у отправленной оферты доступна и возвращает 200; для DRAFT оферты карточки нет.
2. Для legacy-оферты без snapshot `offer-pdf-{id}` **отсутствует**.

---

## Фича B5. Восстановление сметы (1.6, 2.6, 3.1, 3.3, 3.7)

**Повторяемость:** генератор `run-id` + teardown (T: restore базовой оферты и withdraw созданных оферт).

### TC-UI-RST-01 — Restore с подтверждением (2.6, A1)
**Предусловия:** ADMIN, смета DRAFT, оферта `UI-{run-id}-R` со snapshot S3; в смете сделаны правки (добавлена работа, удалена `LINE_2`), несохранённых (staged) правок нет.
1. На `/projects/{PID}/pricing` нажать `offer-restore-{id}` → открыт `offer-restore-dialog-{id}`: текст о замене сметы, о том, что материалы пересчитываются; видны `offer-restore-impact-{id}` (влияние на оферты) и кнопки `offer-restore-confirm-{id}` / `offer-restore-cancel-{id}`.
2. **Запросов `POST .../restore` до подтверждения нет.**
3. Нажать `offer-restore-confirm-{id}` → `POST /api/offers/{id}/restore` → **200**; показан toast успеха (количество строк).
4. Перейти на вкладку сметы: матрица соответствует snapshot (добавленной работы нет, `LINE_2` на месте, количества как в S3, итог = `NET_0`).
5. Аудит (через API) содержит `RESTORE_ESTIMATE`.

### TC-UI-RST-02 — Диалог показывает влияние на оферты (A4)
**Предусловия:** есть SENT-оферта (в т.ч. исходная).
1. Нажать `offer-restore-{id}` → в `offer-restore-impact-{id}` перечислено, что виденные оферты будут отозваны.
2. Подтвердить → после успеха в `offer-awaiting-list` виденные оферты исчезли, в `offer-versions` они «Отозвана».
3. Отмена (`offer-restore-cancel-{id}`) на другом проходе: диалог закрыт, запросов нет, оферты не изменены.

### TC-UI-RST-03 — Недоступность: смета заблокирована или есть несохранённые правки (2.6, A1)
1. Смета не в DRAFT → `offer-restore-{id}` **неактивна** (disabled), есть подсказка `offer-restore-hint-{id}` про блокировку сметы. Клик не открывает диалог.
2. Смета DRAFT, но на вкладке сметы есть staged (несохранённые) правки → кнопка неактивна, подсказка просит сохранить или отменить правки; после сохранения/отмены кнопка активна.
3. Принудительный вызов API при заблокированной смете (в обход UI) → 409 `error.estimate.locked`, смета не меняется (см. TC-RST-03).

### TC-UI-RST-04 — Пропущенные комнаты в toast (A5)
**Предусловия:** после создания оферты удалена одна комната.
1. Подтвердить restore → ответ содержит `skippedRooms`.
2. Показан toast со списком пропущенных комнат (по `label`); остальная смета восстановлена.

### TC-UI-RST-05 — Видимость действия (3.3, 3.1)
1. Для CLIENT `offer-restore-{id}` отсутствует.
2. Для legacy-оферты без snapshot `offer-restore-{id}` отсутствует.
3. Для исполнителя у каждой строки истории и каждой карточки со snapshot действие присутствует.

### TC-UI-RST-06 — Кэш после restore (2.6)
1. После успешного restore вкладка сметы, список и карточка оферты обновляются без перезагрузки страницы (запросы matrix/list/offer повторены; итоги совпадают с ответом `estimateTotals`).

---

## Фича B6. Маркер пакета (1.8, 2.8, 3.5)

**Повторяемость:** генератор `run-id` + teardown. Нужны два проекта: `PID` с пакетом (S4) и `PID_NOPKG` без пакета.

### TC-UI-PKG-01 — Маркер виден при применённом пакете (2.8)
**Предусловия:** оферта `UI-{run-id}-P` подготовлена на `PID` с пакетом `PKG`.
1. На `/projects/{PID}/pricing` в карточке виден `offer-package-marker` с названием пакета.
2. Открыть матрицу snapshot → в шапке `offer-snapshot-matrix` тот же маркер.
3. В истории (`offer-versions`) у строки оферты тот же маркер.
4. Открыть сравнение двух оферт → у обеих сторон показан маркер пакета.
5. PDF (TC-PDF-01) содержит название пакета.

### TC-UI-PKG-02 — Нет маркера без пакета (2.8)
**Предусловия:** оферта на `PID_NOPKG`.
1. `offer-package-marker` не отображается ни в карточке, ни в истории, ни в матрице, ни в сравнении.
2. Заголовок карточки оферты проекта 1531 (где пакет не применён) — без маркера (контроль на эталонных данных, только чтение).

### TC-UI-PKG-03 — Язык (2.8)
1. Переключить язык PL ↔ RU → название пакета в маркере меняется на локализованное.

---

## Фича B7. Сравнение и состояние «нет снимка» в UI (3.3, 3.5)

**Повторяемость:** генератор `run-id` + teardown.

### TC-UI-CMP-01 — Сравнение двух оферт одного проекта (3.5)
1. В `offer-versions` выбрать две оферты со snapshot → открыть сравнение.
2. Показаны добавленные/удалённые/изменённые работы и дельта итога; значения совпадают с `GET /api/offers/compare`.
3. Выбор оферты без snapshot для сравнения недоступен.

---

# Регрессия

**Повторяемость:** генератор `run-id` + teardown (все кейсы работают на собственных проектах под `run-id`; T: withdraw нетерминальных оферт).

### TC-REG-01 — Закрытые дефекты 1.1–1.8 (сквозной проход, API)
На одном проекте: (1.1) prepare → наполнить смету → send → snapshot не пуст, итоги равны (TC-SNAP-01); (1.2) строка без комнаты отсутствует (TC-SNAP-05); (1.3) матрица в UI (TC-UI-SNAP-01); (1.4) PDF 200 (TC-PDF-01); (1.5) вторая нетерминальная оферта создаётся (TC-MULTI-01); (1.6) restore 200 (TC-RST-01); (1.7) диалог имени (TC-UI-NAME-01); (1.8) маркер пакета есть/нет (TC-SNAP-06). Ожидание: каждый пункт даёт результат, описанный в соответствующем кейсе; в ответах нет `error.offer.active.exists`.

### TC-REG-02 — Скрытие конфиденциальных данных для CLIENT (3.2)
1. `GET /api/offers/project/{PID}/list`, `GET .../snapshot`, `GET /api/offers/compare` и `GET .../pdf` с `clientToken` → в телах нет полей `baseUnitPrice`, `baseValueNet`, labour-сравнения и маржи; нет и новых служебных полей `unitPriceOverride`, `workPriceId`, `lineNo` (в JSON-ответах и в тексте PDF).
2. Маркер пакета (`appliedPackageCode`, `appliedPackageName`) присутствует там, где пакет применён.
3. DRAFT-оферта для CLIENT: `GET /api/offers/{DRAFT}`, `.../snapshot`, `.../pdf` → **404**; в списке её нет.

### TC-REG-03 — Legacy-оферты (3.3)
1. Эталонный проект 1531 (только чтение): у legacy-оферт в списке `hasSnapshot == false`; UI показывает метку «нет снимка», нет кнопок просмотра, PDF, restore, сравнения.
2. Оферта 261 («Bazowy», SENT, пустой snapshot до фикса) остаётся без изменений (snapshot не переписывается, т.к. не DRAFT): `GET .../snapshot` возвращает прежнее содержимое (в т.ч. пустое), заморожено.

### TC-REG-04 — Сравнение (3.5)
1. `GET /api/offers/compare?left=A&right=B` для оферт одного проекта → **200**, `added/removed/changed`, дельта итога.
2. Для оферт разных проектов → **400**.

### TC-REG-05 — Поток согласования FOR-05-07 (3.1)
1. prepare → send → approve (CLIENT) → `APPROVED`; аудит записан.
2. prepare → send → reject (CLIENT) → `REJECTED`; повторное изменение запрещено (терминальная неизменяемость, `error.offer.illegal.transition`).
3. prepare → send → withdraw (исполнитель) → `WITHDRAWN`.
4. Негативные права: CLIENT на `send`/`withdraw`/`prepare`/`refresh-snapshot`/`restore` → **403**; не-исполнитель на `approve` → **403**.
5. Раунды переговоров (`/api/offers/{id}/rounds/discount-request`, `/api/offers/rounds/{roundId}/propose|accept|reject|decline`) работают как в FOR-05-07 (по тест-кейсам этой спеки).

### TC-REG-06 — Смета, пакеты, трудозатраты не изменились (3.7)
1. `GET /api/estimates/project/{PID}/matrix`, `apply-package`, `labour-price-adjustment` ведут себя как до фикса (структура ответа, расчёты) — контрольные значения из FOR-05-03/04/06.
2. UI: вкладка сметы редактируема, матрица (`WorksRoomsMatrix`) отображается как прежде.

### TC-REG-07 — Миграция 267 идемпотентна (2.5)
1. Повторный запуск `liquibase update` не падает; индекс `ux_offers_active_project` отсутствует; `ix_offers_project_status` существует в единственном экземпляре.
2. Два нетерминальных `offers` одного проекта вставляются без нарушения ограничений.

---

# Шаблон MD-репорта прогона

Заполняется по итогам прогона отдельно для Части A (API) и Части B (UI).

**Окружение:** docker-стек (дата/commit), браузерный движок и версия, язык UI, пользователь.
**Run-id:** `<yyyyMMdd-HHmmss-xxxx>`. **Проект(ы):** `PID=<...>`, `PID_NOPKG=<...>`.
**Стратегия повторяемости:** генератор `run-id` + teardown (withdraw оферт, деактивация клиента, restore базовой сметы).

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SNAP-01 | 1 | `POST /api/offers/project/{PID}/prepare` `{"name":"Offer-{run-id}-1"}` | 200, DRAFT | | |
| 2 | TC-SNAP-01 | 4 | `GET /api/offers/{OFFER_1}/snapshot` | 200, итоги = `NET_0` | | |
| 3 | TC-UI-NAME-01 | 2 | Клик `estimate-prepare-offer` | диалог открыт, запросов prepare нет | | |
| 4 | TC-UI-SNAP-01 | 5 | Проверка `offer-snapshot-matrix-total-net` | равно `NET_0` | | |

Статусы: ✅ пройден, ❌ провален, ⏭ пропущен (с основанием).

**Итог:** X пройдено / Y провалено / Z пропущено. Run-id: `<run-id>`. Стратегия повторяемости: `генератор + teardown`.

**Дефекты (если есть):**

| ID | Кейс/шаг | Описание | Ожидание | Факт | Серьёзность |
|----|----------|----------|----------|------|-------------|
| | | | | | |

**Teardown выполнен:** ☐ оферты withdraw ☐ клиент деактивирован ☐ смета возвращена ☐ артефакты загрузок удалены.
