@detailed @FOR-QA-AUTO-05-07B @api @round2
Feature: FOR-05-07b Round 2. Inactive offers, one approval, estimate independence and snapshot totals (API)
  Round 2: ручной флаг неактивности, одна согласованная оферта на проект, правки сметы не меняют оферты, итоги оферты берутся из её снимка (тест-кейсы TC-R2-INACT-*, TC-R2-APPR-*, TC-R2-EST-*, TC-R2-TOT-*).

  # Повторяемость: генератор run-id + teardown. Каждый сценарий создаёт свой проект FOR-05-07b {run-id}
  # с клиентом, комнатами и сметой S3; оферты R2 называются Offer-{run-id}-X. Teardown удаляет комнаты
  # (в том числе добавленные сценарием), проект (каскадом смета, оферты, флаги inactive) и пользователей.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Бэкенд и фронтенд отвечают.
    And an offers project with a client and rooms A, B and C exists
    # что: S1–S2. Клиент, проект FOR-05-07b {run-id} и комнаты A/B/C.
    # ожидание: Проект создан.
    And the offers estimate has two works in rooms and one work in no room
    # что: S3. Наполняем смету через API.
    # ожидание: Смета готова, NET0 известен.

  Scenario: TC-R2-INACT-01 Toggling the inactive flag
    TC-R2-INACT-01: флаг inactive включается и выключается у черновика и у отправленной оферты, статус не меняется, повтор идемпотентен, аудит по каждому реальному переключению (2.10).
    When the admin prepares the offer "A"
    # что: S5. Готовим черновик A.
    # ожидание: DRAFT.
    And the admin prepares and sends the offer "B"
    # что: S5. Готовим и отправляем B.
    # ожидание: SENT.
    And the admin prepares the offer "C"
    # что: S5. Готовим черновик C.
    # ожидание: DRAFT.
    And the admin calls POST "/api/offers/{A}/deactivate"
    # что: Помечаем A неактивной.
    # ожидание: 200.
    Then the response field "offer.inactive" equals "true"
    # что: Флаг A.
    # ожидание: inactive = true.
    And the response field "offer.status" equals "DRAFT"
    # что: Статус A не изменился.
    # ожидание: DRAFT.
    When the admin calls POST "/api/offers/{B}/deactivate"
    # что: Помечаем B неактивной.
    # ожидание: 200.
    Then the response field "offer.inactive" equals "true"
    # что: Флаг B.
    # ожидание: inactive = true.
    And the response field "offer.status" equals "SENT"
    # что: Статус B не изменился.
    # ожидание: SENT.
    When the admin calls GET "/api/offers/project/{PID}/list"
    # что: Читаем список оферт.
    # ожидание: 200.
    Then the response field "[id={A}].inactive" equals "true"
    # что: Флаг A в списке.
    # ожидание: true.
    And the response field "[id={B}].inactive" equals "true"
    # что: Флаг B в списке.
    # ожидание: true.
    And the response field "[id={C}].inactive" equals "false"
    # что: Флаг C в списке.
    # ожидание: false.
    When the admin calls POST "/api/offers/{A}/deactivate"
    # что: Повторно помечаем A неактивной.
    # ожидание: 200, состояние то же.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "offer.inactive" equals "true"
    # что: Флаг A после повтора.
    # ожидание: true.
    When the admin calls POST "/api/offers/{B}/activate"
    # что: Снимаем флаг с B.
    # ожидание: 200.
    Then the response field "offer.inactive" equals "false"
    # что: Флаг B.
    # ожидание: false.
    And the response field "offer.status" equals "SENT"
    # что: Статус B.
    # ожидание: SENT.
    And the offer "A" has 1 audit rows "DEACTIVATE"
    # что: Аудит A: одна запись о реальном переключении.
    # ожидание: 1 запись DEACTIVATE.
    And the offer "B" has 1 audit rows "DEACTIVATE"
    # что: Аудит B: выключение.
    # ожидание: 1 запись DEACTIVATE.
    And the offer "B" has 1 audit rows "ACTIVATE"
    # что: Аудит B: включение.
    # ожидание: 1 запись ACTIVATE.

  Scenario: TC-R2-INACT-02 An inactive offer can be sent, viewed and compared
    TC-R2-INACT-02: неактивный черновик отправляется с сохранением флага; клиент видит его снимок, PDF, список и сравнение (2.10).
    When the admin prepares the offer "A"
    # что: Готовим черновик A.
    # ожидание: DRAFT.
    And the admin successfully calls POST "/api/offers/{A}/deactivate"
    # что: Помечаем A неактивной.
    # ожидание: 200.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the admin calls POST "/api/offers/{A}/send"
    # что: Отправляем неактивную A.
    # ожидание: 200.
    Then the response field "offer.status" equals "SENT"
    # что: Статус A.
    # ожидание: SENT.
    And the response field "offer.inactive" equals "true"
    # что: Флаг сохранён после отправки.
    # ожидание: inactive = true.
    When the client calls GET "/api/offers/{A}/snapshot"
    # что: Клиент читает снимок A.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "inactive" equals "true"
    # что: Флаг в снимке.
    # ожидание: true.
    When the client calls GET "/api/offers/{A}/pdf"
    # что: Клиент скачивает PDF A.
    # ожидание: 200.
    Then the response is a PDF document
    # что: Проверяем файл.
    # ожидание: PDF.
    When the client calls GET "/api/offers/project/{PID}/list"
    # что: Клиент читает список.
    # ожидание: 200.
    Then the response field "[id={A}].inactive" equals "true"
    # что: A видна клиенту с флагом.
    # ожидание: true.
    When the client calls GET "/api/offers/compare-many?ids={A},{B}"
    # что: Клиент сравнивает A и B.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "offers[0].inactive" equals "true"
    # что: Флаг первой колонки сравнения.
    # ожидание: true.

  Scenario: TC-R2-INACT-03 The client cannot decide on an inactive offer
    TC-R2-INACT-03: approve, reject и запрос скидки по неактивной оферте отклоняются 409 error.offer.inactive; после снятия флага approve проходит (2.10).
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin successfully calls POST "/api/offers/{A}/deactivate"
    # что: Помечаем A неактивной.
    # ожидание: 200.
    And the client calls POST "/api/offers/{A}/approve"
    # что: Клиент одобряет неактивную A.
    # ожидание: Отказ.
    Then the response status is 409
    # что: Проверяем код ответа.
    # ожидание: 409.
    And the response error code is "error.offer.inactive"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.inactive.
    When the client calls POST "/api/offers/{A}/reject" with body '{}'
    # что: Клиент отклоняет неактивную A.
    # ожидание: Отказ.
    Then the response error code is "error.offer.inactive"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.inactive.
    When the client calls POST "/api/offers/{A}/rounds/discount-request" with body '{"scope":"GLOBAL","justification":"QA {RUN}"}'
    # что: Клиент запрашивает скидку по неактивной A.
    # ожидание: Отказ.
    Then the response error code is "error.offer.inactive"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.inactive.
    When the admin calls GET "/api/offers/{A}"
    # что: Читаем A.
    # ожидание: 200.
    Then the response field "offer.status" equals "SENT"
    # что: Статус не изменился.
    # ожидание: SENT.
    When the admin successfully calls POST "/api/offers/{A}/activate"
    # что: Снимаем флаг неактивности.
    # ожидание: 200.
    And the client calls POST "/api/offers/{A}/approve"
    # что: Клиент снова одобряет A.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "status" equals "APPROVED"
    # что: Статус A.
    # ожидание: APPROVED.

  Scenario: TC-R2-INACT-04 Permissions and edge cases of the inactive flag
    TC-R2-INACT-04: клиенту 403, без токена 401, нет оферты 404, терминальная оферта 409, клиент другого проекта не меняет флаг (2.10, 3.1).
    Given a second offers project with another client and a filled estimate exists
    # что: Второй проект со своим клиентом.
    # ожидание: Проект создан.
    When the admin prepares the offer "A"
    # что: Готовим черновик A.
    # ожидание: DRAFT.
    And the client calls POST "/api/offers/{A}/deactivate"
    # что: Клиент пытается поставить флаг.
    # ожидание: 403.
    Then the response status is 403
    # что: Проверяем код ответа.
    # ожидание: 403.
    When the anonymous calls POST "/api/offers/{A}/activate"
    # что: Запрос без токена.
    # ожидание: 401.
    Then the response status is 401
    # что: Проверяем код ответа.
    # ожидание: 401.
    When the admin calls POST "/api/offers/999999999/deactivate"
    # что: Несуществующая оферта.
    # ожидание: 404.
    Then the response status is 404
    # что: Проверяем код ответа.
    # ожидание: 404.
    When the admin prepares the offer "W"
    # что: Готовим оферту W.
    # ожидание: DRAFT.
    And the admin successfully calls POST "/api/offers/{W}/withdraw"
    # что: Отзываем W (терминальный статус).
    # ожидание: WITHDRAWN.
    And the admin calls POST "/api/offers/{W}/deactivate"
    # что: Пытаемся поставить флаг на терминальную оферту.
    # ожидание: Отказ.
    Then the response status is 409
    # что: Проверяем код ответа.
    # ожидание: 409.
    And the response error code is "error.offer.illegal.transition"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.illegal.transition.
    When the outsider calls POST "/api/offers/{A}/deactivate"
    # что: Клиент другого проекта пытается поставить флаг.
    # ожидание: Отказ.
    Then the response status is one of "403, 404"
    # что: Проверяем код ответа.
    # ожидание: 403 или 404.

  Scenario: TC-R2-APPR-01 Approve leaves the other offers unchanged
    TC-R2-APPR-01: одобрение A не отзывает B и C: статус, итоги и снимки неизменны, системного отзыва нет (2.10).
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the admin prepares and sends the offer "C"
    # что: Готовим и отправляем C.
    # ожидание: SENT.
    And the state of the offers "B, C" is remembered
    # что: Запоминаем состояние B и C (SNAP_B_BEFORE, SNAP_C_BEFORE).
    # ожидание: Сохранено.
    And the client calls POST "/api/offers/{A}/approve"
    # что: Клиент одобряет A.
    # ожидание: 200.
    Then the response field "status" equals "APPROVED"
    # что: Статус A.
    # ожидание: APPROVED.
    And the state of the offers "B, C" is unchanged
    # что: Сравниваем B и C с сохранённым состоянием.
    # ожидание: Обе SENT, итоги и снимки побайтно те же.
    And the offer "B" has 0 audit rows "SYSTEM_WITHDRAW_ON_APPROVE"
    # что: Аудита системного отзыва B нет.
    # ожидание: 0 записей.
    And the offer "C" has 0 audit rows "SYSTEM_WITHDRAW_ON_APPROVE"
    # что: Аудита системного отзыва C нет.
    # ожидание: 0 записей.

  Scenario: TC-R2-APPR-02 A second approval is refused
    TC-R2-APPR-02: при наличии согласованной оферты approve другой оферты — 409 error.offer.already.approved; reject и ручной отзыв работают (2.10).
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the admin prepares and sends the offer "C"
    # что: Готовим и отправляем C.
    # ожидание: SENT.
    And the client successfully calls POST "/api/offers/{A}/approve"
    # что: Клиент одобряет A.
    # ожидание: APPROVED.
    And the client calls POST "/api/offers/{B}/approve"
    # что: Клиент пытается одобрить B.
    # ожидание: Отказ.
    Then the response status is 409
    # что: Проверяем код ответа.
    # ожидание: 409.
    And the response error code is "error.offer.already.approved"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.already.approved.
    And the project has exactly one approved offer
    # что: Считаем согласованные оферты проекта.
    # ожидание: Ровно одна.
    When the client calls POST "/api/offers/{B}/reject" with body '{}'
    # что: Клиент отклоняет B.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200 (ограничение касается только approve).
    When the admin calls POST "/api/offers/{C}/withdraw"
    # что: Исполнитель отзывает C вручную.
    # ожидание: 200.
    Then the response field "offer.status" equals "WITHDRAWN"
    # что: Статус C.
    # ожидание: WITHDRAWN.

  Scenario: TC-R2-APPR-03 Several sent offers at once and a concurrent approval
    TC-R2-APPR-03: три отправленные оферты видны клиенту; два одновременных approve дают ровно одну согласованную оферту (2.10).
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the admin prepares and sends the offer "C"
    # что: Готовим и отправляем C.
    # ожидание: SENT.
    And the client calls GET "/api/offers/project/{PID}/list"
    # что: Клиент читает список.
    # ожидание: 200.
    Then the response array "" has 3 items
    # что: Клиент видит все три.
    # ожидание: 3 записи.
    And the response field "[id={C}].status" equals "SENT"
    # что: Статус C.
    # ожидание: SENT.
    When the client approves the offers "A" and "B" at the same time
    # что: Два одновременных approve по A и B (проверка гонки).
    # ожидание: Один успешный, второй отклонён.
    Then exactly one of the concurrent approvals succeeded and the other got 409
    # что: Проверяем коды ответов.
    # ожидание: 200 и 409.
    And the project has exactly one approved offer
    # что: Считаем согласованные оферты.
    # ожидание: Ровно одна.

  Scenario: TC-R2-APPR-04 Prepare after approve is allowed
    TC-R2-APPR-04: после согласования можно подготовить новую оферту; согласованная не меняется (3.4).
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the client successfully calls POST "/api/offers/{A}/approve"
    # что: Клиент одобряет A.
    # ожидание: APPROVED.
    And the state of the offer "A" is remembered
    # что: Запоминаем состояние A.
    # ожидание: Сохранено.
    And the admin prepares the offer "D"
    # что: Готовим новую оферту D.
    # ожидание: 200.
    Then the response field "offer.status" equals "DRAFT"
    # что: Статус D.
    # ожидание: DRAFT.
    And the state of the offer "A" is unchanged
    # что: Согласованная A не изменилась.
    # ожидание: Состояние то же.

  Scenario: TC-R2-EST-01 Estimate edits, packages, labour and rooms leave every offer unchanged
    TC-R2-EST-01: после каждого воздействия на смету ни статус, ни версия, ни итоги, ни снимок ни одной оферты не меняются (2.11).
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin prepares the offer "B"
    # что: Готовим черновик B.
    # ожидание: DRAFT.
    And the admin prepares and sends the offer "C"
    # что: Готовим и отправляем C.
    # ожидание: SENT.
    And the client successfully calls POST "/api/offers/{C}/approve"
    # что: Клиент одобряет C.
    # ожидание: APPROVED.
    And the state of the offers "A, B, C" is remembered
    # что: STATE_BEFORE: статус, версия, итоги и снимок каждой оферты.
    # ожидание: Сохранено.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":6}'
    # что: Воздействие 1: меняем количество работы 1 в Room A.
    # ожидание: 200.
    Then the state of the offers "A, B, C" is unchanged
    # что: Сравниваем состояние оферт.
    # ожидание: Без изменений.
    When the admin successfully calls POST "/api/estimates/project/{PID}/apply-package" with body '{"packageCode":"budget"}'
    # что: Воздействие 2: расчёт смены пакета (apply-package).
    # ожидание: 200.
    Then the state of the offers "A, B, C" is unchanged
    # что: Сравниваем состояние оферт.
    # ожидание: Без изменений.
    When the admin successfully calls POST "/api/estimates/project/{PID}/labour-price-adjustment" with body '{"scope":"PROJECT","pct":10}'
    # что: Воздействие 3: корректировка цены труда на 10%.
    # ожидание: 200.
    Then the state of the offers "A, B, C" is unchanged
    # что: Сравниваем состояние оферт.
    # ожидание: Без изменений.
    When a room "D" is added to the project
    # что: Воздействие 4: добавляем комнату Room D {run-id}.
    # ожидание: Комната создана.
    And the admin successfully calls DELETE "/api/rooms/{ROOM_C}"
    # что: Воздействие 4: удаляем Room C.
    # ожидание: Комната удалена.
    Then the state of the offers "A, B, C" is unchanged
    # что: Сравниваем состояние оферт.
    # ожидание: Без изменений.
    When the admin successfully calls POST "/api/estimates/project/{PID}/assignments" with body '{"edits":[{"kind":"SET_QUANTITY","workItemId":{WORK_1},"roomId":{ROOM_A},"quantity":4}],"stagedIds":["qa-{RUN}"]}'
    # что: Воздействие 5: сохранение сметы (Save) с изменённым количеством.
    # ожидание: 200.
    Then the state of the offers "A, B, C" is unchanged
    # что: Сравниваем состояние оферт.
    # ожидание: Без изменений.
    When the admin calls GET "/api/offers/project/{PID}/list"
    # что: Читаем список оферт.
    # ожидание: 200.
    Then the response array "" has 3 items
    # что: Новых ревизий не появилось.
    # ожидание: Те же 3 оферты.

  Scenario: TC-R2-EST-02 Restore from an offer leaves the offers unchanged
    TC-R2-EST-02: restore из A возвращает смету к снимку A и не меняет ни A, ни B (2.11).
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":6}'
    # что: Меняем смету.
    # ожидание: 200.
    And the state of the offers "A, B" is remembered
    # что: STATE_BEFORE.
    # ожидание: Сохранено.
    And the admin successfully calls POST "/api/offers/{A}/restore"
    # что: Восстанавливаем смету из A.
    # ожидание: 200.
    Then the state of the offers "A, B" is unchanged
    # что: Сравниваем состояние оферт.
    # ожидание: Обе SENT, итоги и снимки те же.
    And the estimate quantity of work "WORK_1" in room "ROOM_A" is "2"
    # что: Смета равна снимку A.
    # ожидание: 2.

  Scenario: TC-R2-EST-03 The offer-impact endpoint answers NONE
    TC-R2-EST-03: устаревший endpoint влияния на оферты всегда отвечает NONE (2.11).
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем A.
    # ожидание: SENT.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT.
    And the client successfully calls POST "/api/offers/{B}/approve"
    # что: Клиент одобряет B.
    # ожидание: APPROVED.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":6}'
    # что: Меняем смету.
    # ожидание: 200.
    And the admin calls GET "/api/estimates/project/{PID}/offer-impact"
    # что: Читаем влияние на оферты.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "impact" equals "NONE"
    # что: Проверяем значение.
    # ожидание: NONE.

  Scenario: TC-R2-TOT-01 Offer totals do not follow the estimate
    TC-R2-TOT-01: после правки сметы итоги черновика и отправленной оферты остаются равными их снимку; refresh черновика переносит новый итог (2.15).
    When the admin prepares the offer "A"
    # что: Готовим черновик A.
    # ожидание: DRAFT, итог NET0.
    And the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT, итог NET0.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":7}'
    # что: Увеличиваем количество работы 1 в Room A.
    # ожидание: 200.
    And the live estimate net is remembered as "NET1"
    # что: Запоминаем новый живой итог сметы NET1.
    # ожидание: NET1 сохранён.
    And the admin calls GET "/api/offers/{A}"
    # что: Читаем черновик A.
    # ожидание: 200.
    Then the response field "offer.totalNet" equals "{NET0}"
    # что: Итог A равен снимку, а не смете.
    # ожидание: NET0.
    And the response field "offer.totalNet" does not equal "{NET1}"
    # что: Итог A не последовал за сметой.
    # ожидание: Не NET1.
    When the admin calls GET "/api/offers/{B}"
    # что: Читаем отправленную B.
    # ожидание: 200.
    Then the response field "offer.totalNet" equals "{NET0}"
    # что: Итог B.
    # ожидание: NET0.
    When the admin calls GET "/api/offers/project/{PID}/list"
    # что: Читаем список.
    # ожидание: 200.
    Then the response field "[id={A}].totalNet" equals "{NET0}"
    # что: Итог A в списке.
    # ожидание: NET0.
    And the response field "[id={B}].totalNet" equals "{NET0}"
    # что: Итог B в списке.
    # ожидание: NET0.
    When the admin calls POST "/api/offers/{A}/refresh-snapshot"
    # что: Обновляем снимок черновика A.
    # ожидание: 200.
    Then the response field "offer.totalNet" equals "{NET1}"
    # что: Итог A сошёлся со сметой.
    # ожидание: NET1.
    When the admin calls GET "/api/offers/{A}/snapshot"
    # что: Читаем снимок A.
    # ожидание: 200.
    Then the response field "totalNet" equals "{NET1}"
    # что: Итог снимка A.
    # ожидание: NET1.

  Scenario: TC-R2-TOT-02 Discounts are computed from the snapshot
    TC-R2-TOT-02: скидка 10% по переговорам применяется к ценам снимка (NET0), а не к изменённой смете (2.15).
    When the admin prepares and sends the offer "B"
    # что: Готовим и отправляем B.
    # ожидание: SENT, итог NET0.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":7}'
    # что: Меняем смету (живой итог NET1).
    # ожидание: 200.
    And the client successfully calls POST "/api/offers/{B}/rounds/discount-request" with body '{"scope":"GLOBAL","justification":"QA {RUN}"}'
    # что: Клиент запрашивает скидку.
    # ожидание: Открыт раунд DISCOUNT_REQUEST.
    And the response field "negotiationThread[0].id" is remembered as "ROUND"
    # что: Запоминаем id раунда.
    # ожидание: Сохранён.
    And the admin successfully calls POST "/api/offers/rounds/{ROUND}/propose" with body '{"kind":"PERCENT","value":10}'
    # что: Исполнитель предлагает 10%.
    # ожидание: Открыто предложение менеджера.
    And the response field "offer.negotiationThread[1].id" is remembered as "PROPOSAL"
    # что: Запоминаем id предложения.
    # ожидание: Сохранён.
    And the client successfully calls POST "/api/offers/rounds/{PROPOSAL}/accept"
    # что: Клиент принимает предложение.
    # ожидание: Скидка применена.
    And the value "{NET0}" reduced by 10 percent is remembered as "NET0_DISCOUNTED"
    # что: Ожидаемый итог: NET0 минус скидка 10% (сумма скидки округляется до гроша, как в бэкенде).
    # ожидание: Значение рассчитано от NET0, а не от NET1.
    And the admin calls GET "/api/offers/{B}"
    # что: Читаем B.
    # ожидание: 200.
    Then the response field "offer.totalNet" equals "{NET0_DISCOUNTED}"
    # что: Итог B со скидкой от цен снимка.
    # ожидание: NET0 минус 10%.
    When the admin calls GET "/api/offers/{B}/snapshot"
    # что: Исполнитель читает снимок B.
    # ожидание: 200.
    Then the response field "groups[0].rows[0].baseValueNet" is not empty
    # что: У исполнителя есть базовые значения из снимка.
    # ожидание: baseValueNet присутствует.
    When the client calls GET "/api/offers/{B}/snapshot"
    # что: Клиент читает снимок B.
    # ожидание: 200.
    Then the response body does not contain "baseValueNet"
    # что: Клиенту базовые значения не отдаются.
    # ожидание: Поля нет.

  Scenario: TC-R2-TOT-03 Legacy offers keep the live totals
    TC-R2-TOT-03: чтение legacy-оферты без снимка идёт по живому пути и следует за сметой (design D7: legacy keeps the live path); оферта со снимком версии 1 читается без 5xx (2.15, 3.3).
    Given a legacy offer "LEG" without a snapshot in status "SENT" exists
    # что: Вставляем legacy-оферту (сохранённый итог 100).
    # ожидание: Создана.
    And an offer "V1" with a version 1 snapshot in status "SENT" exists
    # что: Вставляем оферту со снимком версии 1.
    # ожидание: Создана.
    When the admin calls GET "/api/offers/{LEG}"
    # что: Читаем legacy-оферту.
    # ожидание: 200.
    Then the response field "offer.hasSnapshot" equals "false"
    # что: Legacy без снимка.
    # ожидание: false.
    And the response field "offer.totalNet" equals "{NET0}"
    # что: Итог legacy — живой итог сметы, а не сохранённые 100.
    # ожидание: NET0.
    When the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":7}'
    # что: Меняем смету.
    # ожидание: 200.
    And the live estimate net is remembered as "NET1"
    # что: Запоминаем NET1.
    # ожидание: Сохранён.
    And the admin calls GET "/api/offers/{LEG}"
    # что: Снова читаем legacy-оферту.
    # ожидание: 200.
    Then the response field "offer.totalNet" equals "{NET1}"
    # что: Итог legacy последовал за сметой (допустимое отличие legacy).
    # ожидание: NET1.
    When the admin calls GET "/api/offers/{V1}"
    # что: Читаем оферту со снимком версии 1.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200, без 5xx.
