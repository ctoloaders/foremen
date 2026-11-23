@detailed @FOR-QA-AUTO-05-07B @api
Feature: FOR-05-07b A2. Several non-terminal offers (API)
  Несколько нетерминальных оферт одного проекта: создание второй оферты, независимая отправка, правила имени (тест-кейсы TC-MULTI-01..05).

  # Повторяемость: свой проект FOR-05-07b {run-id} на сценарий (DataGen), имена оферт Offer-{run-id}-X;
  # teardown удаляет комнаты, проект (каскадом оферты) и пользователей.
  # TC-MULTI-03 и TC-MULTI-04 помечены в test-cases.md как устаревшие (Round 2): выполняется только
  # оговорённая часть, обратное ожидание Round 2 проверяется в c3/c4 (TC-R2-APPR-*, TC-R2-EST-01).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Бэкенд и фронтенд отвечают.
    And an offers project with a client and rooms A, B and C exists
    # что: S1–S2. Клиент, проект FOR-05-07b {run-id} и комнаты A/B/C.
    # ожидание: Проект создан, teardown зарегистрирован.
    And the offers estimate has two works in rooms and one work in no room
    # что: S3. Наполняем смету через API.
    # ожидание: Смета с тремя работами, NET0 известен.

  Scenario: TC-MULTI-01 A second non-terminal offer is created
    TC-MULTI-01: вторая нетерминальная оферта создаётся без ошибки error.offer.active.exists (1.5, 2.5).
    When the admin prepares the offer "A"
    # что: Готовим оферту Offer-{run-id}-A.
    # ожидание: 200, DRAFT.
    And the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"name":"Offer-{RUN}-B"}'
    # что: Готовим вторую оферту Offer-{run-id}-B, пока A не завершена.
    # ожидание: 200 (раньше было 409 error.offer.active.exists).
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "offer.offerId" does not equal "{A}"
    # что: Создана отдельная оферта.
    # ожидание: id B отличается от A.
    And the response body does not contain "error.offer.active.exists"
    # что: Проверяем отсутствие старой ошибки.
    # ожидание: Кода error.offer.active.exists нет.
    When the response field "offer.offerId" is remembered as "B"
    # что: Запоминаем id оферты B.
    # ожидание: B сохранён.
    And the admin calls GET "/api/offers/project/{PID}/list"
    # что: Читаем список оферт проекта.
    # ожидание: 200.
    Then the response array "" has 2 items
    # что: В списке обе оферты.
    # ожидание: Две записи.
    And the response field "[id={A}].hasSnapshot" equals "true"
    # что: У A есть снимок.
    # ожидание: hasSnapshot = true.
    And the response field "[id={B}].hasSnapshot" equals "true"
    # что: У B есть снимок.
    # ожидание: hasSnapshot = true.
    And the response field "[id={B}].status" equals "DRAFT"
    # что: Статус B.
    # ожидание: DRAFT.

  Scenario: TC-MULTI-02 Two sent offers live independently
    TC-MULTI-02: две отправленные оферты видны клиенту, у каждой свой замороженный снимок, без базовых цен (2.5).
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the client calls GET "/api/offers/project/{PID}/list"
    # что: Клиент читает список оферт.
    # ожидание: 200.
    Then the response array "" has 2 items
    # что: Клиент видит обе отправленные оферты.
    # ожидание: Две записи.
    And the response field "[id={A}].status" equals "SENT"
    # что: Статус A.
    # ожидание: SENT.
    And the response field "[id={B}].status" equals "SENT"
    # что: Статус B.
    # ожидание: SENT.
    When the client calls GET "/api/offers/{A}/snapshot"
    # что: Клиент читает снимок A.
    # ожидание: 200.
    Then the response field "name" equals "Offer-{RUN}-A"
    # что: Снимок принадлежит A.
    # ожидание: name = Offer-{run-id}-A.
    And the response body does not contain "baseUnitPrice"
    # что: Клиенту не отдаются базовые цены.
    # ожидание: Поля baseUnitPrice нет.
    And the response body does not contain "baseValueNet"
    # что: Клиенту не отдаётся базовая стоимость.
    # ожидание: Поля baseValueNet нет.
    When the client calls GET "/api/offers/{B}/snapshot"
    # что: Клиент читает снимок B.
    # ожидание: 200.
    Then the response field "name" equals "Offer-{RUN}-B"
    # что: Снимок принадлежит B.
    # ожидание: name = Offer-{run-id}-B.
    And the response field "offerId" equals "{B}"
    # что: id в снимке.
    # ожидание: offerId = B.

  Scenario: TC-MULTI-03 Approve of one offer (Round 2 part)
    TC-MULTI-03 (устарел в Round 2, выполняется шаг 1): клиент одобряет одну из двух отправленных оферт.
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the client calls POST "/api/offers/{A}/approve"
    # что: Клиент одобряет A.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    When the admin calls GET "/api/offers/{A}"
    # что: Читаем A исполнителем.
    # ожидание: 200.
    Then the response field "offer.status" equals "APPROVED"
    # что: Статус A.
    # ожидание: APPROVED.

  Scenario: TC-MULTI-04 An estimate change leaves sent offers SENT (Round 2 expectation)
    TC-MULTI-04 (устарел в Round 2, ожидание обратное): после правки сметы обе отправленные оферты и черновик остаются в прежних статусах.
    When the admin prepares and sends the offer "C"
    # что: Готовим и отправляем C.
    # ожидание: SENT.
    And the admin prepares and sends the offer "D"
    # что: Готовим и отправляем D.
    # ожидание: SENT.
    And the admin prepares the offer "E"
    # что: Готовим черновик E.
    # ожидание: DRAFT.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":5}'
    # что: Меняем количество работы 1 в Room A.
    # ожидание: 200.
    And the admin calls GET "/api/offers/project/{PID}/list"
    # что: Читаем список оферт.
    # ожидание: 200.
    Then the response field "[id={C}].status" equals "SENT"
    # что: Статус C.
    # ожидание: SENT (не WITHDRAWN).
    And the response field "[id={D}].status" equals "SENT"
    # что: Статус D.
    # ожидание: SENT (не WITHDRAWN).
    And the response field "[id={E}].status" equals "DRAFT"
    # что: Статус черновика E.
    # ожидание: DRAFT.

  Scenario: TC-MULTI-05 Offer name rules
    TC-MULTI-05: пустое имя даёт «Offer N», имя обрезается, допустимо 255 символов, 256 — ошибка без создания оферты (3.4).
    When the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"name":"   "}'
    # что: Готовим оферту с именем из пробелов.
    # ожидание: 200, имя по умолчанию.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "offer.name" matches "Offer [0-9]+"
    # что: Проверяем имя по умолчанию.
    # ожидание: Offer N.
    When the admin calls POST "/api/offers/project/{PID}/prepare"
    # что: Готовим оферту без тела запроса.
    # ожидание: 200, имя по умолчанию.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "offer.name" matches "Offer [0-9]+"
    # что: Проверяем имя по умолчанию.
    # ожидание: Offer N.
    When the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"name":"  Offer-{RUN}-trim  "}'
    # что: Готовим оферту с пробелами по краям имени.
    # ожидание: 200.
    Then the response field "offer.name" equals "Offer-{RUN}-trim"
    # что: Имя обрезано.
    # ожидание: Offer-{run-id}-trim.
    When the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"name":"{NAME_255}"}'
    # что: Готовим оферту с именем длиной 255 символов.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the project has 4 offers
    # что: Считаем оферты проекта в БД.
    # ожидание: 4.
    When the admin calls POST "/api/offers/project/{PID}/prepare" with body '{"name":"{NAME_256}"}'
    # что: Готовим оферту с именем длиной 256 символов.
    # ожидание: Отказ.
    Then the response status is 400
    # что: Проверяем код ответа.
    # ожидание: 400.
    And the response error code is "error.offer.name.too.long"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.name.too.long.
    And the project has 4 offers
    # что: Оферта не создана.
    # ожидание: По-прежнему 4.
