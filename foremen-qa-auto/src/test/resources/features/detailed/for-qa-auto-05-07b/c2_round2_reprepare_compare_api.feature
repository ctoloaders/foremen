@detailed @FOR-QA-AUTO-05-07B @api @round2
Feature: FOR-05-07b Round 2. Update an existing offer from the estimate and compare several offers (API)
  Round 2: обновление существующей оферты из сохранённой сметы (prepare с offerId) и сравнение от 2 до 10 оферт (compare-many) (тест-кейсы TC-R2-REP-*, TC-R2-CMP-*).

  # Повторяемость: свой проект FOR-05-07b {run-id} на сценарий; уведомления считаются у клиента, созданного
  # в этом сценарии, и удаляются каскадом вместе с ним в teardown.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Бэкенд и фронтенд отвечают.
    And an offers project with a client and rooms A, B and C exists
    # что: S1–S2. Клиент, проект и комнаты.
    # ожидание: Проект создан.
    And the offers estimate has two works in rooms and one work in no room
    # что: S3. Наполняем смету.
    # ожидание: Смета готова.

  Scenario: TC-R2-REP-01 Prepare without offerId creates a new offer
    TC-R2-REP-01: prepare без offerId создаёт новый черновик версии 1; правила имени сохранены (2.13, 3.4).
    When the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"name":"R2-{RUN}-N"}'
    # что: Готовим новую оферту R2-{run-id}-N.
    # ожидание: 200.
    Then the response field "offer.status" equals "DRAFT"
    # что: Статус новой оферты.
    # ожидание: DRAFT.
    And the response field "offer.revision" equals "1"
    # что: Версия новой оферты.
    # ожидание: 1.
    When the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"name":""}'
    # что: Готовим оферту с пустым именем.
    # ожидание: 200.
    Then the response field "offer.name" matches "Offer [0-9]+"
    # что: Имя по умолчанию.
    # ожидание: Offer N.
    When the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"name":"{NAME_256}"}'
    # что: Имя длиной 256 символов.
    # ожидание: Отказ.
    Then the response status is 400
    # что: Проверяем код ответа.
    # ожидание: 400.
    And the response error code is "error.offer.name.too.long"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.name.too.long.

  Scenario: TC-R2-REP-02 Updating a DRAFT offer keeps its status and raises the version
    TC-R2-REP-02: prepare с offerId черновика пересобирает снимок из сохранённой сметы, статус и имя прежние, версия +1, аудит REPREPARE, новая оферта не создана, клиент не уведомлён (2.13).
    When the admin prepares the offer "A"
    # что: Готовим черновик A (версия r = 1).
    # ожидание: DRAFT.
    And the response field "offer.revision" is remembered as "REV_A"
    # что: Запоминаем версию A.
    # ожидание: Сохранена.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем работу 4 в смету (сохранено через API).
    # ожидание: Строка создана.
    And the live estimate net is remembered as "NET1"
    # что: Запоминаем живой итог сметы.
    # ожидание: Сохранён.
    And the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"offerId":{A}}'
    # что: Обновляем A из сметы.
    # ожидание: 200.
    Then the response field "offer.offerId" equals "{A}"
    # что: Обновлена та же оферта.
    # ожидание: id = A.
    And the response field "offer.status" equals "DRAFT"
    # что: Статус сохранён.
    # ожидание: DRAFT.
    And the response field "offer.revision" equals "{REV_A}" plus 1
    # что: Версия увеличена.
    # ожидание: r + 1.
    And the response field "offer.name" equals "Offer-{RUN}-A"
    # что: Имя прежнее.
    # ожидание: Offer-{run-id}-A.
    And the response field "offer.inactive" equals "false"
    # что: Флаг прежний.
    # ожидание: false.
    And the response field "offer.totalNet" equals "{NET1}"
    # что: Итог пересчитан по новому снимку.
    # ожидание: Равен живому итогу сметы.
    When the admin calls GET "/api/offers/{A}/snapshot"
    # что: Читаем новый снимок A.
    # ожидание: 200.
    Then the snapshot has a row for work "WORK_4"
    # что: Новая работа в снимке.
    # ожидание: Строка есть.
    And the response field "totalNet" equals "{NET1}"
    # что: Итог снимка.
    # ожидание: Равен итогу оферты.
    And the offer "A" has 1 audit rows "REPREPARE"
    # что: Аудит обновления.
    # ожидание: Одна запись REPREPARE.
    And the project has 1 offers
    # что: Новая оферта не создана.
    # ожидание: 1 оферта.
    And the client has 0 notifications of type "notification.offer.reprepared"
    # что: Клиент не уведомляется об обновлении черновика.
    # ожидание: 0 уведомлений.

  Scenario: TC-R2-REP-03 Updating sent offers, including an inactive one, notifies the client
    TC-R2-REP-03: обновление отправленных оферт сохраняет статус и флаг, повышает версию, уведомляет только клиента этого проекта и не трогает другие оферты (2.13).
    Given a second offers project with another client and a filled estimate exists
    # что: Второй проект с CLIENT_OTHER.
    # ожидание: Проект создан.
    When the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the admin prepares and sends the offer "C"
    # что: Готовим и отправляем C.
    # ожидание: SENT.
    And the admin successfully calls POST "/api/offers/{C}/deactivate"
    # что: Помечаем C неактивной.
    # ожидание: 200.
    And the admin prepares and sends the offer "D"
    # что: Готовим и отправляем D (не обновляется).
    # ожидание: SENT.
    And the state of the offer "D" is remembered
    # что: Запоминаем состояние D.
    # ожидание: Сохранено.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем работу 4 в смету.
    # ожидание: Строка создана.
    And the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"offerId":{B}}'
    # что: Обновляем отправленную B.
    # ожидание: 200.
    Then the response field "offer.status" equals "SENT"
    # что: Статус B.
    # ожидание: SENT.
    And the response field "offer.revision" equals "2"
    # что: Версия B.
    # ожидание: 2.
    When the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"offerId":{C}}'
    # что: Обновляем неактивную C.
    # ожидание: 200.
    Then the response field "offer.status" equals "SENT"
    # что: Статус C.
    # ожидание: SENT.
    And the response field "offer.inactive" equals "true"
    # что: Флаг C сохранён.
    # ожидание: true.
    And the response field "offer.revision" equals "2"
    # что: Версия C.
    # ожидание: 2.
    And the client has 2 notifications of type "notification.offer.reprepared"
    # что: Уведомления клиента проекта (с ожиданием асинхронной отправки).
    # ожидание: Ровно по одному на каждую обновлённую оферту (2).
    And the outsider has 0 notifications of type "notification.offer.reprepared"
    # что: Клиент другого проекта не уведомлён.
    # ожидание: 0.
    When the client calls GET "/api/offers/{B}/snapshot"
    # что: Клиент читает новую версию B.
    # ожидание: 200.
    Then the snapshot has a row for work "WORK_4"
    # что: Клиент видит новый снимок.
    # ожидание: Работа 4 есть.
    And the state of the offer "D" is unchanged
    # что: Снимок остальных оферт не изменился.
    # ожидание: D побайтно та же.

  Scenario: TC-R2-REP-04 Terminal offers, offers of another project, missing offers and the client
    TC-R2-REP-04: обновление терминальной оферты — 409, чужой — 400, несуществующей — 404, клиенту — 403 (2.13).
    Given a second offers project with another client and a filled estimate exists
    # что: Второй проект со своей офертой.
    # ожидание: Проект создан.
    When the admin prepares and sends the offer "APP"
    # что: Готовим и отправляем APP.
    # ожидание: SENT.
    And the admin prepares and sends the offer "REJ"
    # что: Готовим и отправляем REJ.
    # ожидание: SENT.
    And the admin prepares the offer "WDR"
    # что: Готовим WDR.
    # ожидание: DRAFT.
    And the client successfully calls POST "/api/offers/{APP}/approve"
    # что: Клиент одобряет APP.
    # ожидание: APPROVED.
    And the client successfully calls POST "/api/offers/{REJ}/reject" with body '{}'
    # что: Клиент отклоняет REJ.
    # ожидание: REJECTED.
    And the admin successfully calls POST "/api/offers/{WDR}/withdraw"
    # что: Отзываем WDR.
    # ожидание: WITHDRAWN.
    And the state of the offers "APP, REJ, WDR" is remembered
    # что: Запоминаем состояние терминальных оферт.
    # ожидание: Сохранено.
    And the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"offerId":{APP}}'
    # что: Пытаемся обновить согласованную оферту.
    # ожидание: Отказ.
    Then the response status is 409
    # что: Проверяем код ответа.
    # ожидание: 409.
    And the response error code is "error.offer.reprepare.terminal"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.reprepare.terminal.
    When the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"offerId":{REJ}}'
    # что: Пытаемся обновить отклонённую оферту.
    # ожидание: Отказ.
    Then the response error code is "error.offer.reprepare.terminal"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.reprepare.terminal.
    When the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"offerId":{WDR}}'
    # что: Пытаемся обновить отозванную оферту.
    # ожидание: Отказ.
    Then the response error code is "error.offer.reprepare.terminal"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.reprepare.terminal.
    And the state of the offers "APP, REJ, WDR" is unchanged
    # что: Снимки и версии терминальных оферт не изменились.
    # ожидание: Без изменений.
    When the admin prepares the offer "OTHER" in the second project
    # что: Готовим оферту во втором проекте.
    # ожидание: 200.
    And the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"offerId":{OTHER}}'
    # что: Обновляем чужую оферту через проект PID.
    # ожидание: Отказ.
    Then the response status is 400
    # что: Проверяем код ответа.
    # ожидание: 400.
    When the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"offerId":999999999}'
    # что: Несуществующая оферта.
    # ожидание: 404.
    Then the response status is 404
    # что: Проверяем код ответа.
    # ожидание: 404.
    When the client calls POST "/api/offers/project/{PID}/prepare" with body '{"offerId":{APP}}'
    # что: Клиент пытается обновить оферту.
    # ожидание: 403.
    Then the response status is 403
    # что: Проверяем код ответа.
    # ожидание: 403.

  Scenario: TC-R2-REP-05 The snapshot is built from the saved estimate
    TC-R2-REP-05: после обновления снимок совпадает с сохранённой сметой по строкам и количествам (2.14).
    When the admin prepares the offer "A"
    # что: Готовим черновик A.
    # ожидание: DRAFT.
    And the admin successfully calls POST "/api/estimates/project/{PID}/assignments" with body '{"edits":[{"kind":"SET_QUANTITY","workItemId":{WORK_1},"roomId":{ROOM_B},"quantity":8}],"stagedIds":["qa-{RUN}"]}'
    # что: Сохраняем в смете новое количество работы 1 в Room B (Save).
    # ожидание: 200.
    And the admin successfully calls POST "/api/offers/project/{PID}/prepare" with body '{"offerId":{A}}'
    # что: Обновляем A из сметы.
    # ожидание: 200.
    And the admin calls GET "/api/offers/{A}/snapshot"
    # что: Читаем снимок A.
    # ожидание: 200.
    Then the snapshot quantity of work "WORK_1" in room "ROOM_B" is "8"
    # что: Количество в снимке.
    # ожидание: 8, как в сохранённой смете.
    And the estimate quantity of work "WORK_1" in room "ROOM_B" is "8"
    # что: Количество в сохранённой смете.
    # ожидание: 8.
    And the snapshot quantity of work "WORK_2" in room "ROOM_A" is "1.5"
    # что: Остальные строки совпадают.
    # ожидание: 1,5.

  Scenario: TC-R2-CMP-01 compare-many for the executor
    TC-R2-CMP-01: сравнение трёх оферт даёт колонки в порядке id, группы и работы с комнатами; суммы групп сходятся с итогами; лимит 10 id (2.12).
    When the admin prepares the offer "A"
    # что: A — смета S3.
    # ожидание: DRAFT.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем работу 4.
    # ожидание: Строка создана.
    And the admin prepares the offer "B"
    # что: B — с работой 4.
    # ожидание: DRAFT.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":5}'
    # что: Меняем количество работы 1 в Room A.
    # ожидание: 200.
    And the admin prepares the offer "C"
    # что: C — с изменённым количеством.
    # ожидание: DRAFT.
    And the admin calls GET "/api/offers/compare-many?ids={A},{B},{C}"
    # что: Сравниваем три оферты.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "offers[0].id" equals "{A}"
    # что: Порядок колонок: первая.
    # ожидание: A.
    And the response field "offers[1].id" equals "{B}"
    # что: Вторая.
    # ожидание: B.
    And the response field "offers[2].id" equals "{C}"
    # что: Третья.
    # ожидание: C.
    And the response field "offers[0].name" equals "Offer-{RUN}-A"
    # что: Имя в шапке колонки.
    # ожидание: Offer-{run-id}-A.
    And the response field "offers[0].version" equals "1"
    # что: Версия в шапке.
    # ожидание: 1.
    And the response field "offers[0].totalGross" is not empty
    # что: Итоги в шапке.
    # ожидание: Есть.
    And the comparison group values add up to each offer net total
    # что: Сумма групп равна итогу каждой оферты.
    # ожидание: Сходится.
    And the response field "works[workItemId={WORK_4}].offers[offerId={A}].quantity" equals "null"
    # что: Работы 4 нет в A.
    # ожидание: quantity = null.
    And the response field "works[workItemId={WORK_4}].offers[offerId={A}].rooms" is empty or absent
    # что: Комнат для отсутствующей работы нет.
    # ожидание: Пусто.
    And the response field "works[workItemId={WORK_4}].offers[offerId={B}].quantity" equals "2"
    # что: Работа 4 в B.
    # ожидание: 2.
    And the response field "works[workItemId={WORK_1}].offers[offerId={C}].rooms[roomId={ROOM_A}].quantity" equals "5"
    # что: Комнаты внутри работы: работа 1 в C, Room A.
    # ожидание: 5.
    And the response field "works[workItemId={WORK_1}].offers[offerId={A}].rooms[roomId={ROOM_A}].quantity" equals "2"
    # что: Та же работа в A.
    # ожидание: 2.
    And the response field "works[workItemId={WORK_1}].unitCode" is not empty
    # что: Единица работы.
    # ожидание: Есть.
    And the response field "groups[0].rooms" is empty or absent
    # что: На уровне групп нет полей по комнатам.
    # ожидание: Отсутствуют.
    When the admin prepares 7 more offers named "M"
    # что: Готовим ещё 7 оферт (всего 10).
    # ожидание: Созданы.
    And the admin calls GET "/api/offers/compare-many?ids={A},{B},{C},{M_IDS}"
    # что: Сравниваем 10 оферт.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response array "offers" has 10 items
    # что: Количество колонок.
    # ожидание: 10.
    When the admin prepares the offer "ELEVEN"
    # что: Готовим одиннадцатую оферту.
    # ожидание: 200.
    And the admin calls GET "/api/offers/compare-many?ids={A},{B},{C},{M_IDS},{ELEVEN}"
    # что: Сравниваем 11 оферт.
    # ожидание: Отказ.
    Then the response status is 400
    # что: Проверяем код ответа.
    # ожидание: 400.

  Scenario: TC-R2-CMP-02 compare-many validation errors
    TC-R2-CMP-02: один id, пустой список, нечисловой id, разные проекты и legacy — 400; несуществующий id — 404; без токена — 401 (2.12).
    Given a second offers project with another client and a filled estimate exists
    # что: Второй проект.
    # ожидание: Создан.
    And a legacy offer "LEG" without a snapshot in status "SENT" exists
    # что: Legacy-оферта без снимка.
    # ожидание: Создана.
    When the admin prepares the offer "A"
    # что: Готовим A.
    # ожидание: DRAFT.
    And the admin prepares the offer "OTHER" in the second project
    # что: Оферта второго проекта.
    # ожидание: DRAFT.
    And the admin calls GET "/api/offers/compare-many?ids={A}"
    # что: Один id.
    # ожидание: 400.
    Then the response status is 400
    # что: Проверяем код ответа.
    # ожидание: 400.
    When the admin calls GET "/api/offers/compare-many?ids="
    # что: Пустой список.
    # ожидание: 400.
    Then the response status is 400
    # что: Проверяем код ответа.
    # ожидание: 400.
    When the admin calls GET "/api/offers/compare-many?ids=a,b"
    # что: Нечисловые id.
    # ожидание: 400.
    Then the response status is 400
    # что: Проверяем код ответа.
    # ожидание: 400.
    When the admin calls GET "/api/offers/compare-many?ids={A},{OTHER}"
    # что: Оферты разных проектов.
    # ожидание: 400.
    Then the response status is 400
    # что: Проверяем код ответа.
    # ожидание: 400.
    When the admin calls GET "/api/offers/compare-many?ids={A},{LEG}"
    # что: Legacy без снимка среди id.
    # ожидание: 400.
    Then the response status is 400
    # что: Проверяем код ответа.
    # ожидание: 400.
    When the admin calls GET "/api/offers/compare-many?ids={A},999999999"
    # что: Несуществующий id.
    # ожидание: 404.
    Then the response status is 404
    # что: Проверяем код ответа.
    # ожидание: 404.
    When the anonymous calls GET "/api/offers/compare-many?ids={A},{A}"
    # что: Запрос без токена.
    # ожидание: 401.
    Then the response status is 401
    # что: Проверяем код ответа.
    # ожидание: 401.

  Scenario: TC-R2-CMP-03 Redaction for the client
    TC-R2-CMP-03: клиент сравнивает отправленные оферты без служебных полей; черновик даёт 404; неактивная допустима; клиент другого проекта получает отказ (2.12, 3.2).
    Given a second offers project with another client and a filled estimate exists
    # что: Второй проект с CLIENT_OTHER.
    # ожидание: Создан.
    When the admin successfully calls POST "/api/estimates/project/{PID}/labour-price-adjustment" with body '{"scope":"PROJECT","pct":10}'
    # что: Корректировка труда, чтобы в снимке были служебные базовые цены.
    # ожидание: 200.
    And the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the admin successfully calls POST "/api/offers/{B}/deactivate"
    # что: Помечаем B неактивной.
    # ожидание: 200.
    And the admin prepares the offer "DR"
    # что: Черновик DR.
    # ожидание: DRAFT.
    And the client calls GET "/api/offers/compare-many?ids={A},{B}"
    # что: Клиент сравнивает A и B.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200 (неактивная B допустима).
    And the response field "offers[1].inactive" equals "true"
    # что: Флаг B в сравнении.
    # ожидание: true.
    And the response body does not contain "baseUnitPrice"
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
    # что: Нет номера строки сметы.
    # ожидание: Отсутствует.
    And the response body does not contain "labour"
    # что: Нет сравнения трудозатрат.
    # ожидание: Отсутствует.
    When the client calls GET "/api/offers/compare-many?ids={A},{DR}"
    # что: Среди id черновик.
    # ожидание: 404.
    Then the response status is 404
    # что: Клиент не видит черновик.
    # ожидание: 404.
    When the outsider calls GET "/api/offers/compare-many?ids={A},{B}"
    # что: Клиент другого проекта.
    # ожидание: Отказ.
    Then the response status is one of "403, 404"
    # что: Проверяем код ответа.
    # ожидание: 403 или 404.

  Scenario: TC-R2-CMP-04 The two-offer compare is kept
    TC-R2-CMP-04: двухоферточное сравнение работает как раньше; разные проекты — 400 (3.5).
    Given a second offers project with another client and a filled estimate exists
    # что: Второй проект.
    # ожидание: Создан.
    When the admin prepares the offer "A"
    # что: Готовим A.
    # ожидание: DRAFT.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем работу 4.
    # ожидание: Строка создана.
    And the admin prepares the offer "B"
    # что: Готовим B с работой 4.
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
    And the response array "added" has an item with "workItemId" equal to "{WORK_4}"
    # что: Работа 4 среди добавленных.
    # ожидание: Есть.
    And the response field "deltaTotalNet" is not empty
    # что: Дельта итога.
    # ожидание: Есть.
    When the admin calls GET "/api/offers/compare?left={A}&right={OTHER}"
    # что: Оферты разных проектов.
    # ожидание: 400.
    Then the response status is 400
    # что: Проверяем код ответа.
    # ожидание: 400.
