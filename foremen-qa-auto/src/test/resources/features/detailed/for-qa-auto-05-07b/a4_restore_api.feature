@detailed @FOR-QA-AUTO-05-07B @api
Feature: FOR-05-07b A4. Restore the estimate from an offer snapshot (API)
  Восстановление сметы из снимка оферты: замена строк и количеств, пропущенные комнаты, блокировка сметы, отсутствие снимка, права и пересчёт материалов (тест-кейсы TC-RST-01..07).

  # Повторяемость: свой проект FOR-05-07b {run-id} на сценарий, поэтому порядок сценариев не важен.
  # BASE — оферта, снятая с исходной сметы S3 и отправленная (снимок заморожен), BASE_NET = NET0.
  # Teardown удаляет комнаты, проект (каскадом смета и оферты) и пользователей.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Бэкенд и фронтенд отвечают.
    And an offers project with a client and rooms A, B and C exists
    # что: S1–S2. Клиент, проект и комнаты.
    # ожидание: Проект создан.
    And the offers estimate has two works in rooms and one work in no room
    # что: S3. Наполняем смету.
    # ожидание: Смета готова, NET0 известен.
    And the admin prepares and sends the offer "BASE"
    # что: Готовим и отправляем базовую оферту BASE со снимком S3.
    # ожидание: BASE в статусе SENT.

  Scenario: TC-RST-01 Restore into a changed DRAFT estimate
    TC-RST-01: после правок сметы restore возвращает строки и количества снимка, пишет аудит и идемпотентен по содержанию (2.6, A1).
    When a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем работу 4.
    # ожидание: Строка создана.
    And the admin successfully calls DELETE "/api/estimate-lines/{LINE_2}"
    # что: Удаляем строку работы 2.
    # ожидание: Строка удалена.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":9}'
    # что: Меняем количество работы 1 в Room A на 9.
    # ожидание: 200.
    And the admin calls POST "/api/offers/{BASE}/restore"
    # что: Восстанавливаем смету из снимка BASE.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "restoredLines" is at least 2
    # что: Количество восстановленных строк.
    # ожидание: Не меньше 2.
    And the response field "skippedWorks" equals "0"
    # что: Пропущенных работ нет.
    # ожидание: 0.
    And the response field "skippedRooms" is empty or absent
    # что: Пропущенных комнат нет.
    # ожидание: Пустой список.
    And the response field "estimateTotals.totalNet" equals "{NET0}"
    # что: Итог восстановленной сметы.
    # ожидание: Равен BASE_NET = NET0.
    And the response field "estimateTotals.totalGross" is not empty
    # что: Брутто присутствует.
    # ожидание: Поле есть.
    And the estimate has 0 lines of work "WORK_4"
    # что: Добавленной после снимка работы нет.
    # ожидание: 0 строк.
    And the estimate has 1 lines of work "WORK_2"
    # что: Удалённая работа 2 вернулась.
    # ожидание: 1 строка.
    And the estimate quantity of work "WORK_1" in room "ROOM_A" is "2"
    # что: Количество работы 1 в Room A как в S3.
    # ожидание: 2.
    And the estimate quantity of work "WORK_2" in room "ROOM_A" is "1.5"
    # что: Количество работы 2 в Room A как в S3.
    # ожидание: 1,5.
    And the estimate applied package is "none"
    # что: Пакет сметы равен пакету снимка (в снимке пакета нет).
    # ожидание: null.
    And the offer "BASE" has 1 audit rows "RESTORE_ESTIMATE"
    # что: Аудит восстановления.
    # ожидание: Ровно одна запись RESTORE_ESTIMATE.
    When the admin calls POST "/api/offers/{BASE}/restore"
    # что: Повторное восстановление той же оферты.
    # ожидание: 200, тот же результат.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "estimateTotals.totalNet" equals "{NET0}"
    # что: Итог тот же.
    # ожидание: NET0.
    And the offer "BASE" has 2 audit rows "RESTORE_ESTIMATE"
    # что: Ещё одна запись аудита.
    # ожидание: 2 записи.

  Scenario: TC-RST-02 Rooms deleted after the snapshot are reported as skipped
    TC-RST-02: удалённая комната попадает в skippedRooms, остальная смета восстановлена штатным пересчётом (A5).
    When the admin successfully calls DELETE "/api/rooms/{ROOM_B}"
    # что: Удаляем Room B.
    # ожидание: Комната удалена.
    And the admin calls POST "/api/offers/{BASE}/restore"
    # что: Восстанавливаем смету из BASE.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response array "skippedRooms" has an item with "roomId" equal to "{ROOM_B}"
    # что: Room B среди пропущенных.
    # ожидание: Есть запись с roomId = ROOM_B.
    And the response field "skippedRooms[roomId={ROOM_B}].label" equals "{ROOM_B_LABEL}"
    # что: Подпись пропущенной комнаты.
    # ожидание: Room B {run-id}.
    And the estimate quantity of work "WORK_1" in room "ROOM_A" is "2"
    # что: Количество по Room A восстановлено.
    # ожидание: 2.
    And the estimate quantity of work "WORK_1" in room "ROOM_B" is "none"
    # что: Количеств по Room B нет.
    # ожидание: Нет строки количества.
    And the response field "estimateTotals.totalNet" is less than "{NET0}"
    # что: Итог пересчитан штатно (без Room B).
    # ожидание: Меньше BASE_NET.

  Scenario: TC-RST-03 A locked estimate is not restored
    TC-RST-03: смета не в DRAFT — 409 error.estimate.locked, смета не меняется, аудит не пишется (A1).
    When the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":9}'
    # что: Меняем количество, чтобы было что восстанавливать.
    # ожидание: 200.
    And the estimate is locked
    # что: Переводим смету из DRAFT (статус PRICED, через БД стенда).
    # ожидание: Смета заблокирована.
    And the admin calls POST "/api/offers/{BASE}/restore"
    # что: Пытаемся восстановить смету.
    # ожидание: Отказ.
    Then the response status is 409
    # что: Проверяем код ответа.
    # ожидание: 409.
    And the response error code is "error.estimate.locked"
    # что: Проверяем код ошибки.
    # ожидание: error.estimate.locked.
    And the estimate quantity of work "WORK_1" in room "ROOM_A" is "9"
    # что: Смета не изменилась.
    # ожидание: 9.
    And the offer "BASE" has 0 audit rows "RESTORE_ESTIMATE"
    # что: Аудит не записан.
    # ожидание: 0 записей.

  Scenario: TC-RST-04 No snapshot or a version 1 snapshot gives 404
    TC-RST-04: legacy без снимка и снимок версии 1 — 404 error.offer.snapshot.missing; несуществующий id — 404 (3.3, A3).
    Given a legacy offer "LEG" without a snapshot in status "SENT" exists
    # что: Вставляем legacy-оферту без снимка.
    # ожидание: Оферта создана.
    And an offer "V1" with a version 1 snapshot in status "SENT" exists
    # что: Вставляем оферту со снимком версии 1.
    # ожидание: Оферта создана.
    When the admin calls POST "/api/offers/{LEG}/restore"
    # что: Restore legacy-оферты.
    # ожидание: 404.
    Then the response status is 404
    # что: Проверяем код ответа.
    # ожидание: 404.
    And the response error code is "error.offer.snapshot.missing"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.snapshot.missing.
    When the admin calls POST "/api/offers/{V1}/restore"
    # что: Restore оферты со снимком версии 1.
    # ожидание: 404.
    Then the response status is 404
    # что: Проверяем код ответа.
    # ожидание: 404.
    And the response error code is "error.offer.snapshot.missing"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.snapshot.missing.
    And the estimate quantity of work "WORK_1" in room "ROOM_A" is "2"
    # что: Смета не менялась.
    # ожидание: 2.
    When the admin calls POST "/api/offers/999999999/restore"
    # что: Restore несуществующей оферты.
    # ожидание: 404.
    Then the response status is 404
    # что: Проверяем код ответа.
    # ожидание: 404.

  Scenario: TC-RST-05 Restore leaves every offer unchanged (Round 2 expectation)
    TC-RST-05 (устарел в Round 2, ожидание обратное): после restore обе отправленные оферты остаются SENT, их итоги и снимки не меняются.
    When the admin prepares and sends the offer "X"
    # что: Готовим и отправляем ещё одну оферту X.
    # ожидание: SENT.
    And the state of the offers "BASE, X" is remembered
    # что: Запоминаем статус, версию, итоги, флаг неактивности и снимок обеих оферт.
    # ожидание: Состояние сохранено.
    And the admin successfully calls POST "/api/offers/{BASE}/restore"
    # что: Восстанавливаем смету из BASE.
    # ожидание: 200.
    Then the state of the offers "BASE, X" is unchanged
    # что: Сравниваем состояние оферт после restore.
    # ожидание: Ничего не изменилось, обе SENT.

  Scenario: TC-RST-06 Permissions
    TC-RST-06: клиенту restore запрещён (403), без токена — 401 (3.1).
    When the client calls POST "/api/offers/{BASE}/restore"
    # что: Клиент пытается восстановить смету.
    # ожидание: 403.
    Then the response status is 403
    # что: Проверяем код ответа.
    # ожидание: 403.
    When the anonymous calls POST "/api/offers/{BASE}/restore"
    # что: Запрос без токена.
    # ожидание: 401.
    Then the response status is 401
    # что: Проверяем код ответа.
    # ожидание: 401.

  Scenario: TC-RST-07 Materials are recomputed after restore
    TC-RST-07: после restore материалы сметы выводятся штатным пересчётом без ошибок.
    When the admin successfully calls POST "/api/offers/{BASE}/restore"
    # что: Восстанавливаем смету из BASE.
    # ожидание: 200.
    And the admin calls GET "/api/estimates/project/{PID}/materials"
    # что: Читаем материалы сметы.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200, ошибок пересчёта нет.
