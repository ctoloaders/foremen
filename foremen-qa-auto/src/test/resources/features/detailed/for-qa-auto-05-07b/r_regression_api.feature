@detailed @FOR-QA-AUTO-05-07B @api @regression
Feature: FOR-05-07b Regression (API)
  Регрессия FOR-05-07b: закрытые дефекты 1.1–1.8, скрытие данных от клиента, legacy, сравнение, поток согласования FOR-05-07, смета, миграции 267/268, замечания Round 1 и эталонные данные (тест-кейсы TC-REG-01..11, часть API).

  # Повторяемость: генератор run-id + teardown; каждый сценарий работает на своём проекте FOR-05-07b {run-id}.
  # Эталонные проекты 1531 и 7678 только читаются (GET); если проекта нет в БД стенда, кейс пропускается.
  # UI-части регрессии (матрица, диалог имени, маркер в карточке) — в файле r_regression_ui.feature.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Бэкенд и фронтенд отвечают.
    And an offers project with a client and rooms A, B and C exists
    # что: S1–S2. Клиент, проект и комнаты.
    # ожидание: Проект создан.

  Scenario: TC-REG-01 Closed defects 1.1 to 1.8 in one pass
    TC-REG-01: сквозной проход по закрытым дефектам на одном проекте: снимок после send (1.1), без строк без комнат (1.2), PDF (1.4), вторая оферта (1.5), restore (1.6), имя (1.7), маркер пакета (1.8). Матрица (1.3) — в UI-регрессии.
    Given the first offer package is applied to the estimate
    # что: Отмечаем в смете применённый пакет PKG (маркер берётся при prepare).
    # ожидание: Отмечен в смете.
    When the admin prepares the offer "A"
    # что: 1.1: prepare на пустой смете.
    # ожидание: DRAFT.
    And the offers estimate has two works in rooms and one work in no room
    # что: Наполняем смету.
    # ожидание: Смета готова.
    And the admin sends the offer "A"
    # что: Отправляем A.
    # ожидание: SENT.
    And the admin calls GET "/api/offers/{A}/snapshot"
    # что: Читаем снимок A.
    # ожидание: 200.
    Then the response field "totalNet" equals "{NET0}"
    # что: 1.1: снимок не пуст, итог равен смете.
    # ожидание: NET0.
    And the snapshot has no row for work "WORK_3"
    # что: 1.2: работа без комнат отсутствует.
    # ожидание: Строки нет.
    And the response field "appliedPackageCode" equals "{PKG}"
    # что: 1.8: маркер пакета (захвачен при отправке).
    # ожидание: PKG.
    When the admin calls GET "/api/offers/{A}/pdf"
    # что: 1.4: PDF.
    # ожидание: 200.
    Then the response is a PDF document
    # что: Проверяем файл.
    # ожидание: PDF.
    When the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"name":"Offer-{RUN}-B"}'
    # что: 1.5 и 1.7: вторая оферта с именем.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200, без error.offer.active.exists.
    And the response field "offer.name" equals "Offer-{RUN}-B"
    # что: Имя сохранено.
    # ожидание: Offer-{run-id}-B.
    When the admin calls POST "/api/offers/{A}/restore"
    # что: 1.6: restore.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.

  Scenario: TC-REG-02 Confidential data is hidden from the client
    TC-REG-02: список, снимок, сравнение и PDF для клиента без базовых цен и служебных полей; маркер пакета есть; черновик скрыт (3.2).
    Given the offers estimate has two works in rooms and one work in no room
    # что: Наполняем смету.
    # ожидание: Смета готова.
    And the first offer package is applied to the estimate
    # что: Применяем пакет.
    # ожидание: Отмечен.
    When the admin successfully calls POST "/api/estimates/project/{PID}/labour-price-adjustment" with body '{"scope":"PROJECT","pct":10}'
    # что: Корректируем труд, чтобы базовые цены отличались.
    # ожидание: 200.
    And the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the admin prepares the offer "DR"
    # что: Черновик DR.
    # ожидание: DRAFT.
    And the client calls GET "/api/offers/project/{PID}/list"
    # что: Клиент читает список.
    # ожидание: 200.
    Then the response array "" has no item with "id" equal to "{DR}"
    # что: Черновика в списке клиента нет.
    # ожидание: DR отсутствует.
    And the response field "[id={A}].appliedPackageCode" equals "{PKG}"
    # что: Маркер пакета в списке.
    # ожидание: PKG.
    And the response field "[id={A}].inactive" equals "false"
    # что: Поле inactive присутствует (TC-REG-10).
    # ожидание: false.
    When the client calls GET "/api/offers/{A}/snapshot"
    # что: Клиент читает снимок.
    # ожидание: 200.
    Then the response body does not contain "baseUnitPrice"
    # что: Нет базовой цены.
    # ожидание: Отсутствует.
    And the response body does not contain "baseValueNet"
    # что: Нет базовой стоимости.
    # ожидание: Отсутствует.
    And the response body does not contain "unitPriceOverride"
    # что: Нет переопределённой цены.
    # ожидание: Отсутствует.
    And the response body does not contain "workPriceId"
    # что: Нет ссылки на прайс.
    # ожидание: Отсутствует.
    And the response body does not contain "lineNo"
    # что: Нет номера строки.
    # ожидание: Отсутствует.
    And the response field "appliedPackageName" is not empty
    # что: Маркер пакета в снимке.
    # ожидание: Есть.
    When the client calls GET "/api/offers/compare?left={A}&right={B}"
    # что: Клиент сравнивает две оферты.
    # ожидание: 200.
    Then the response body does not contain "baseUnitPrice"
    # что: Нет базовой цены в сравнении.
    # ожидание: Отсутствует.
    When the client calls GET "/api/offers/{DR}"
    # что: Клиент читает черновик.
    # ожидание: 404.
    Then the response status is 404
    # что: Проверяем код ответа.
    # ожидание: 404.
    When the client calls GET "/api/offers/{DR}/snapshot"
    # что: Клиент читает снимок черновика.
    # ожидание: 404.
    Then the response status is 404
    # что: Проверяем код ответа.
    # ожидание: 404.

  Scenario: TC-REG-03 Reference project 1531 legacy offers (read only)
    TC-REG-03: на эталонном проекте 1531 legacy-оферты без снимка дают 404 на снимок и PDF, оферты со снимком читаются; данные не меняются (3.3).
    Given the reference project 1531 is present, otherwise the case is skipped
    # что: Проверяем, что эталонный проект есть в БД стенда.
    # ожидание: Проект 1531 найден (иначе статус «пропущен»).
    When the admin calls GET "/api/offers/project/{REF_PID}/list"
    # что: Читаем список оферт 1531.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And every listed offer without a snapshot answers 404 on snapshot and PDF
    # что: Legacy-оферты без снимка (GET).
    # ожидание: 404 на снимок и PDF.
    And every listed offer with a snapshot answers 200 on snapshot and PDF
    # что: Оферты со снимком (GET).
    # ожидание: 200 и PDF.

  Scenario: TC-REG-04 Two-offer comparison
    TC-REG-04: сравнение двух оферт одного проекта — 200 с дельтой; разных проектов — 400 (3.5).
    Given the offers estimate has two works in rooms and one work in no room
    # что: Наполняем смету.
    # ожидание: Смета готова.
    And a second offers project with another client and a filled estimate exists
    # что: Второй проект.
    # ожидание: Создан.
    When the admin prepares the offer "A"
    # что: Готовим A.
    # ожидание: DRAFT.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":4}'
    # что: Меняем количество.
    # ожидание: 200.
    And the admin prepares the offer "B"
    # что: Готовим B.
    # ожидание: DRAFT.
    And the admin prepares the offer "OTHER" in the second project
    # что: Оферта второго проекта.
    # ожидание: DRAFT.
    And the admin calls GET "/api/offers/compare?left={A}&right={B}"
    # что: Сравниваем A и B.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response array "changed" has an item with "workItemId" equal to "{WORK_1}"
    # что: Работа 1 среди изменённых.
    # ожидание: Есть.
    When the admin calls GET "/api/offers/compare?left={A}&right={OTHER}"
    # что: Оферты разных проектов.
    # ожидание: 400.
    Then the response status is 400
    # что: Проверяем код ответа.
    # ожидание: 400.

  Scenario: TC-REG-05 The FOR-05-07 approval flow
    TC-REG-05: prepare → send → approve/reject/withdraw работают; терминальная неизменяемость; клиенту запрещены действия исполнителя (3.1).
    Given the offers estimate has two works in rooms and one work in no room
    # что: Наполняем смету.
    # ожидание: Смета готова.
    When the admin prepares and sends the offer "APP"
    # что: prepare → send.
    # ожидание: SENT.
    And the client calls POST "/api/offers/{APP}/approve"
    # что: Клиент одобряет.
    # ожидание: APPROVED.
    Then the response field "status" equals "APPROVED"
    # что: Статус.
    # ожидание: APPROVED.
    When the admin prepares and sends the offer "REJ"
    # что: Вторая цепочка: prepare → send.
    # ожидание: SENT.
    And the client calls POST "/api/offers/{REJ}/reject" with body '{}'
    # что: Клиент отклоняет.
    # ожидание: REJECTED.
    Then the response field "status" equals "REJECTED"
    # что: Статус.
    # ожидание: REJECTED.
    When the client calls POST "/api/offers/{REJ}/approve"
    # что: Повторное изменение терминальной оферты.
    # ожидание: Отказ.
    Then the response status is 409
    # что: Проверяем код ответа.
    # ожидание: 409.
    When the admin prepares and sends the offer "WDR"
    # что: Третья цепочка: prepare → send.
    # ожидание: SENT.
    And the admin calls POST "/api/offers/{WDR}/withdraw"
    # что: Исполнитель отзывает.
    # ожидание: WITHDRAWN.
    Then the response field "offer.status" equals "WITHDRAWN"
    # что: Статус.
    # ожидание: WITHDRAWN.
    When the admin prepares the offer "DR"
    # что: Черновик для проверки прав.
    # ожидание: DRAFT.
    And the client calls POST "/api/offers/{DR}/send"
    # что: Клиент пытается отправить.
    # ожидание: 403.
    Then the response status is 403
    # что: Проверяем код ответа.
    # ожидание: 403.
    When the client calls POST "/api/offers/{DR}/withdraw"
    # что: Клиент пытается отозвать.
    # ожидание: 403.
    Then the response status is 403
    # что: Проверяем код ответа.
    # ожидание: 403.
    When the client calls POST "/api/offers/project/{PID}/prepare" with body '{"name":"x"}'
    # что: Клиент пытается подготовить оферту.
    # ожидание: 403.
    Then the response status is 403
    # что: Проверяем код ответа.
    # ожидание: 403.

  Scenario: TC-REG-06 Estimate, packages and labour still work
    TC-REG-06: матрица сметы, расчёт пакета и корректировка труда отвечают как до фикса (3.7).
    Given the offers estimate has two works in rooms and one work in no room
    # что: Наполняем смету.
    # ожидание: Смета готова.
    When the admin calls GET "/api/estimates/project/{PID}/matrix"
    # что: Читаем матрицу сметы.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "editable" equals "true"
    # что: Смета редактируема.
    # ожидание: true.
    And the response array "rooms" has 3 items
    # что: Колонки комнат.
    # ожидание: 3.
    When the admin calls POST "/api/estimates/project/{PID}/apply-package" with body '{"packageCode":"budget"}'
    # что: Расчёт пакета.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    When the admin calls POST "/api/estimates/project/{PID}/labour-price-adjustment" with body '{"scope":"PROJECT","pct":5}'
    # что: Корректировка труда.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.

  Scenario: TC-REG-07 Migration 267 left one project-status index and no one-active-offer index
    TC-REG-07: уникального индекса одной активной оферты нет, индекс проект+статус один, две нетерминальные оферты создаются (2.5). Повторный liquibase update выполняется при каждом docker compose up.
    Then the index "ux_offers_active_project" exists 0 times
    # что: Ищем удалённый индекс в pg_indexes.
    # ожидание: Отсутствует.
    And the index "ix_offers_project_status" exists 1 times
    # что: Ищем индекс проект+статус.
    # ожидание: Ровно один.
    Given the offers estimate has two works in rooms and one work in no room
    # что: Наполняем смету.
    # ожидание: Смета готова.
    When the admin prepares the offer "A"
    # что: Первая нетерминальная оферта.
    # ожидание: 200.
    And the admin prepares the offer "B"
    # что: Вторая нетерминальная оферта того же проекта.
    # ожидание: 200, без нарушения ограничений.
    Then the project has 2 offers
    # что: Считаем оферты.
    # ожидание: 2.

  Scenario: TC-REG-08 Migration 268 added the inactive column once
    TC-REG-08: колонка offers.inactive существует в единственном экземпляре (boolean, not null, default false); новая оферта создаётся с false (2.10).
    Then the column "inactive" of table "offers" is "boolean|NO|false|1"
    # что: Читаем information_schema по offers.inactive.
    # ожидание: boolean, NOT NULL, default false, одна колонка.
    Given the offers estimate has two works in rooms and one work in no room
    # что: Наполняем смету.
    # ожидание: Смета готова.
    When the admin prepares the offer "A"
    # что: Создаём новую оферту.
    # ожидание: 200.
    Then the response field "offer.inactive" equals "false"
    # что: Флаг новой оферты.
    # ожидание: false.

  Scenario: TC-REG-09 Round 1 browser findings 1.10, 1.11 and 1.14 (API part)
    TC-REG-09: API-часть перепроверки замечаний Round 1: approve не отзывает остальные и есть флаг inactive (1.10), правка сметы не создаёт ревизий (1.11), итоги черновика равны снимку (1.14). Замечания 1.9, 1.12, 1.13 — в UI-регрессии.
    Given the offers estimate has two works in rooms and one work in no room
    # что: Наполняем смету.
    # ожидание: Смета готова.
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the admin prepares the offer "C"
    # что: Черновик C.
    # ожидание: DRAFT.
    And the admin successfully calls POST "/api/offers/{B}/deactivate"
    # что: 1.10: ручной флаг inactive.
    # ожидание: 200.
    And the client successfully calls POST "/api/offers/{A}/approve"
    # что: 1.10: клиент одобряет A.
    # ожидание: APPROVED.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":7}'
    # что: 1.11 и 1.14: меняем смету.
    # ожидание: 200.
    And the admin calls GET "/api/offers/project/{PID}/list"
    # что: Читаем список.
    # ожидание: 200.
    Then the response array "" has 3 items
    # что: 1.11: новых ревизий не появилось.
    # ожидание: 3 оферты.
    And the response field "[id={B}].status" equals "SENT"
    # что: 1.10: B не отозвана.
    # ожидание: SENT.
    And the response field "[id={B}].inactive" equals "true"
    # что: 1.10: флаг B.
    # ожидание: true.
    And the response field "[id={C}].totalNet" equals "{NET0}"
    # что: 1.14: итог черновика равен его снимку.
    # ожидание: NET0.

  Scenario: TC-REG-10 Round 1 behaviour kept under Round 2
    TC-REG-10: ответы содержат inactive; approve при существующей APPROVED и approve неактивной — 409; у терминальных оферт снимок заморожен (3.1, 3.2, 3.3, 3.4, 3.8).
    Given the offers estimate has two works in rooms and one work in no room
    # что: Наполняем смету.
    # ожидание: Смета готова.
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the admin prepares and sends the offer "C"
    # что: Готовим и отправляем C.
    # ожидание: SENT.
    And the admin successfully calls POST "/api/offers/{C}/deactivate"
    # что: Помечаем C неактивной.
    # ожидание: 200.
    And the client calls POST "/api/offers/{C}/approve"
    # что: Клиент одобряет неактивную C.
    # ожидание: 409.
    Then the response error code is "error.offer.inactive"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.inactive.
    When the client successfully calls POST "/api/offers/{A}/approve"
    # что: Клиент одобряет A.
    # ожидание: APPROVED.
    And the client calls POST "/api/offers/{B}/approve"
    # что: Клиент одобряет B при существующей APPROVED.
    # ожидание: 409.
    Then the response error code is "error.offer.already.approved"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.already.approved.
    When the state of the offer "A" is remembered
    # что: Запоминаем состояние согласованной A.
    # ожидание: Сохранено.
    And the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"offerId":{A}}'
    # что: Пытаемся обновить согласованную A.
    # ожидание: 409.
    Then the response error code is "error.offer.reprepare.terminal"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.reprepare.terminal.
    And the state of the offer "A" is unchanged
    # что: Снимок терминальной оферты заморожен.
    # ожидание: Без изменений.
    When the client calls GET "/api/offers/compare-many?ids={A},{B}"
    # что: Клиентское сравнение.
    # ожидание: 200.
    Then the response field "offers[0].inactive" equals "false"
    # что: Поле inactive в ответе.
    # ожидание: Присутствует.
    And the response body does not contain "baseValueNet"
    # что: Нет базовых значений.
    # ожидание: Отсутствуют.

  Scenario: TC-REG-11 Reference project 7678 (read only)
    TC-REG-11: эталонный проект 7678 читается без изменений: список, снимки и PDF работают, legacy — 404 (только GET).
    Given the reference project 7678 is present, otherwise the case is skipped
    # что: Проверяем, что проект 7678 есть в БД стенда.
    # ожидание: Найден (иначе «пропущен»).
    When the admin calls GET "/api/offers/project/{REF_PID}/list"
    # что: Читаем список оферт 7678.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And every listed offer with a snapshot answers 200 on snapshot and PDF
    # что: Снимки и PDF.
    # ожидание: 200.
    And every listed offer without a snapshot answers 404 on snapshot and PDF
    # что: Legacy.
    # ожидание: 404.
