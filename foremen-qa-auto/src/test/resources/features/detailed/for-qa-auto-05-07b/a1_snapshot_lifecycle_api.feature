@detailed @FOR-QA-AUTO-05-07B @api
Feature: FOR-05-07b A1. Offer snapshot lifecycle (API)
  Жизненный цикл снимка оферты: захват при подготовке, обновление черновика из сметы, заморозка после отправки, строки без комнат и маркер пакета (тест-кейсы TC-SNAP-01..06).

  # Повторяемость: каждый сценарий создаёт свой проект FOR-05-07b {run-id} с клиентом и комнатами
  # A/B/C (DataGen), смета заполняется через API (/api/estimate-lines, /api/estimate-line-room-qty).
  # Teardown (LIFO): комнаты, проект (каскадом смета и оферты), пользователи.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Бэкенд и фронтенд отвечают.
    And an offers project with a client and rooms A, B and C exists
    # что: S1–S2. Создаём ACTIVE-клиента, проект FOR-05-07b {run-id} с клиентом-участником и комнаты Room A/B/C.
    # ожидание: Проект, комнаты и пустая смета созданы, teardown зарегистрирован.

  Scenario: TC-SNAP-01 Prepare on an empty estimate, then fill it and send: the snapshot equals the live totals
    TC-SNAP-01: оферта подготовлена на пустой смете; после наполнения сметы отправка обновляет снимок до живых итогов (1.1, 2.1).
    When the admin prepares the offer "A"
    # что: POST /api/offers/project/{PID}/prepare с именем Offer-{run-id}-A на пустой смете.
    # ожидание: 200, оферта DRAFT с заданным именем.
    Then the response field "offer.status" equals "DRAFT"
    # что: Проверяем статус новой оферты.
    # ожидание: status = DRAFT.
    And the response field "offer.name" equals "Offer-{RUN}-A"
    # что: Проверяем имя оферты.
    # ожидание: Имя совпадает с переданным.
    Given the offers estimate has two works in rooms and one work in no room
    # что: S3. Добавляем работы 1 (Room A = 2, Room B = 3), 2 (Room A = 1,5) и 3 (ни в одной комнате).
    # ожидание: Строки и количества созданы, NET0 = итог сметы.
    When the admin sends the offer "A"
    # что: POST /api/offers/{A}/send.
    # ожидание: 200, оферта отправлена.
    And the admin calls GET "/api/offers/{A}/snapshot"
    # что: Читаем снимок отправленной оферты исполнителем.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "rooms" is not empty
    # что: Контроль дефекта 1.1: в снимке есть комнаты.
    # ожидание: rooms не пустой.
    And the response field "groups" is not empty
    # что: Контроль дефекта 1.1: в снимке есть группы работ.
    # ожидание: groups не пустой.
    And the response field "totalNet" equals "{NET0}"
    # что: Сравниваем итог снимка с живым итогом сметы.
    # ожидание: totalNet снимка = NET0.
    And the snapshot totals are consistent with its VAT rate
    # что: Проверяем согласованность итогов снимка.
    # ожидание: totalGross = totalNet + totalVat, totalVat = totalNet × vatRatePct.
    When the response field "totalGross" is remembered as "SNAP_GROSS"
    # что: Запоминаем брутто снимка.
    # ожидание: Значение сохранено.
    And the admin calls GET "/api/offers/{A}"
    # что: Читаем оферту исполнителем.
    # ожидание: 200.
    Then the response field "offer.totalNet" equals "{NET0}"
    # что: Итог оферты равен итогу снимка.
    # ожидание: totalNet = NET0.
    And the response field "offer.totalGross" equals "{SNAP_GROSS}"
    # что: Брутто оферты равно брутто снимка.
    # ожидание: totalGross совпадает.
    And the response field "offer.status" equals "SENT"
    # что: Проверяем статус после отправки.
    # ожидание: SENT.

  Scenario: TC-SNAP-02 Explicit refresh of a DRAFT picks up estimate edits
    TC-SNAP-02: снимок черновика не следует за каждой правкой сметы (A6) и обновляется только явным refresh-snapshot (2.1).
    Given the offers estimate has two works in rooms and one work in no room
    # что: S3. Наполняем смету.
    # ожидание: Смета с тремя работами.
    When the admin prepares the offer "B"
    # что: Готовим черновик B.
    # ожидание: 200, DRAFT.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем в смету работу 4 с количеством 2 в Room A после подготовки.
    # ожидание: Строка и количество созданы.
    And the admin calls GET "/api/offers/{B}/snapshot"
    # что: Читаем снимок B.
    # ожидание: 200.
    Then the snapshot has no row for work "WORK_4"
    # что: Проверяем отсутствие автообновления снимка (A6).
    # ожидание: Работы 4 в снимке нет.
    When the admin calls POST "/api/offers/{B}/refresh-snapshot"
    # что: Явно обновляем снимок черновика из сметы.
    # ожидание: 200, модель исполнителя.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "offer.hasSnapshot" equals "true"
    # что: Проверяем флаг снимка.
    # ожидание: hasSnapshot = true.
    And the response field "offer.status" equals "DRAFT"
    # что: Обновление не меняет статус.
    # ожидание: DRAFT.
    When the response field "offer.totalNet" is remembered as "B_NET"
    # что: Запоминаем итог оферты после обновления.
    # ожидание: Значение сохранено.
    And the admin calls GET "/api/offers/{B}/snapshot"
    # что: Снова читаем снимок B.
    # ожидание: 200.
    Then the snapshot has a row for work "WORK_4"
    # что: Проверяем, что новая работа попала в снимок.
    # ожидание: Строка работы 4 есть.
    And the response field "totalNet" equals "{B_NET}"
    # что: Итог снимка равен итогу оферты.
    # ожидание: totalNet совпадает.

  Scenario: TC-SNAP-03 After send the snapshot is frozen
    TC-SNAP-03: правка сметы не меняет снимок и статус отправленной оферты; refresh недоступен (2.1, 3.1).
    Given the offers estimate has two works in rooms and one work in no room
    # что: S3. Наполняем смету.
    # ожидание: Смета готова.
    When the admin prepares and sends the offer "A"
    # что: Готовим и отправляем оферту A.
    # ожидание: A в статусе SENT.
    And the admin calls GET "/api/offers/{A}/snapshot"
    # что: Читаем снимок A.
    # ожидание: 200.
    And the response body is remembered as "SNAP_BEFORE"
    # что: Запоминаем тело снимка.
    # ожидание: SNAP_BEFORE сохранён.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":7}'
    # что: Меняем количество работы 1 в Room A с 2 на 7.
    # ожидание: 200, смета изменена.
    And the admin calls GET "/api/offers/{A}/snapshot"
    # что: Снова читаем снимок A.
    # ожидание: 200.
    Then the response body equals the remembered "SNAP_BEFORE"
    # что: Сравниваем тело снимка с сохранённым.
    # ожидание: Тело побайтно равно SNAP_BEFORE.
    When the admin calls GET "/api/offers/{A}"
    # что: Читаем оферту A.
    # ожидание: 200.
    Then the response field "offer.status" equals "SENT"
    # что: Round 2: правка сметы не меняет статус.
    # ожидание: SENT.
    When the admin calls POST "/api/offers/{A}/refresh-snapshot"
    # что: Пытаемся обновить снимок отправленной оферты.
    # ожидание: Отказ.
    Then the response status is 409
    # что: Проверяем код ответа.
    # ожидание: 409.
    And the response error code is "error.offer.illegal.transition"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.illegal.transition.

  Scenario: TC-SNAP-04 Refresh is refused for a non-DRAFT offer, for a client and without a token
    TC-SNAP-04: refresh-snapshot доступен только исполнителю и только для черновика (2.1, 3.1, A6).
    Given the offers estimate has two works in rooms and one work in no room
    # что: S3. Наполняем смету.
    # ожидание: Смета готова.
    When the admin prepares the offer "W"
    # что: Готовим оферту W.
    # ожидание: DRAFT.
    And the admin successfully calls POST "/api/offers/{W}/withdraw"
    # что: Отзываем W.
    # ожидание: W в статусе WITHDRAWN.
    And the admin calls POST "/api/offers/{W}/refresh-snapshot"
    # что: Пытаемся обновить снимок отозванной оферты.
    # ожидание: Отказ.
    Then the response status is 409
    # что: Проверяем код ответа.
    # ожидание: 409.
    And the response error code is "error.offer.illegal.transition"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.illegal.transition.
    When the admin prepares the offer "B"
    # что: Готовим черновик B.
    # ожидание: DRAFT.
    And the client calls POST "/api/offers/{B}/refresh-snapshot"
    # что: Клиент пытается обновить снимок.
    # ожидание: Нет права OFFERS/UPDATE.
    Then the response status is 403
    # что: Проверяем код ответа.
    # ожидание: 403.
    When the anonymous calls POST "/api/offers/{B}/refresh-snapshot"
    # что: Запрос без токена.
    # ожидание: Требуется аутентификация.
    Then the response status is 401
    # что: Проверяем код ответа.
    # ожидание: 401.

  Scenario: TC-SNAP-05 Works applied to no room are not in the snapshot
    TC-SNAP-05: работа 3 без количеств ни в одной комнате не попадает в снимок; комнаты без количеств тоже (1.2, 2.2).
    Given the offers estimate has two works in rooms and one work in no room
    # что: S3. Наполняем смету (работа 3 без комнат).
    # ожидание: Смета готова.
    When the admin prepares the offer "NR"
    # что: Готовим оферту NR.
    # ожидание: 200.
    And the admin calls GET "/api/offers/{NR}/snapshot"
    # что: Читаем снимок NR.
    # ожидание: 200.
    Then the snapshot has no row for work "WORK_3"
    # что: Проверяем отсутствие работы без комнат.
    # ожидание: Строки работы 3 нет.
    And every snapshot row has a cell with a positive quantity
    # что: У каждой строки есть хотя бы одна ячейка с количеством > 0.
    # ожидание: Условие выполнено для всех строк.
    And the snapshot has no group without rows
    # что: Проверяем, что нет пустых групп.
    # ожидание: У каждой группы есть строки.
    And the snapshot rooms are exactly "ROOM_A, ROOM_B"
    # что: Room C без количеств не должна быть колонкой снимка.
    # ожидание: В rooms только Room A и Room B.
    And the snapshot quantity of work "WORK_1" in room "ROOM_B" is "3"
    # что: Проверяем ячейку работы 1 в Room B.
    # ожидание: 3.
    And the response field "totalNet" equals "{NET0}"
    # что: Лишняя строка не влияет на итог.
    # ожидание: totalNet = NET0.

  Scenario: TC-SNAP-06 Package marker in the snapshot, the list and the comparison
    TC-SNAP-06: маркер применённого пакета виден в снимке, списке и сравнении; без пакета маркера нет (1.8, 2.8).
    Given the offers estimate has two works in rooms and one work in no room
    # что: S3. Наполняем смету.
    # ожидание: Смета готова.
    And the first offer package is applied to the estimate
    # что: S4. Отмечаем в смете применённый пакет PKG (первый активный пакет справочника).
    # ожидание: applied_package_code = PKG.
    And a second offers project with another client and a filled estimate exists
    # что: Создаём второй проект PID_NOPKG со своей сметой без пакета.
    # ожидание: Проект создан.
    And no package is applied to the estimate of the second project
    # что: Убеждаемся, что во втором проекте пакет не применён.
    # ожидание: applied_package_code = null.
    When the admin prepares the offer "PKG_OFFER"
    # что: Готовим оферту на проекте с пакетом.
    # ожидание: 200.
    And the admin prepares the offer "NR"
    # что: Готовим вторую оферту того же проекта.
    # ожидание: 200.
    And the admin calls GET "/api/offers/{PKG_OFFER}/snapshot" with language "pl"
    # что: Читаем снимок с Accept-Language: pl.
    # ожидание: 200.
    Then the response field "appliedPackageCode" equals "{PKG}"
    # что: Проверяем код пакета в снимке.
    # ожидание: appliedPackageCode = PKG.
    And the response field "appliedPackageName" is not empty
    # что: Проверяем название пакета (PL).
    # ожидание: Название не пустое.
    When the admin calls GET "/api/offers/{PKG_OFFER}/snapshot" with language "ru"
    # что: Читаем снимок с Accept-Language: ru.
    # ожидание: 200.
    Then the response field "appliedPackageName" is not empty
    # что: Проверяем локализованное название пакета (RU).
    # ожидание: Название не пустое.
    When the admin calls GET "/api/offers/project/{PID}/list"
    # что: Читаем список оферт проекта.
    # ожидание: 200.
    Then the response field "[id={PKG_OFFER}].appliedPackageCode" equals "{PKG}"
    # что: Проверяем маркер в элементе списка.
    # ожидание: appliedPackageCode = PKG.
    And the response field "[id={PKG_OFFER}].appliedPackageName" is not empty
    # что: Проверяем название пакета в списке.
    # ожидание: Не пустое.
    When the admin calls GET "/api/offers/compare?left={PKG_OFFER}&right={NR}"
    # что: Сравниваем две оферты проекта.
    # ожидание: 200.
    Then the response field "left.appliedPackageCode" equals "{PKG}"
    # что: Маркер у левой стороны.
    # ожидание: PKG.
    And the response field "right.appliedPackageCode" equals "{PKG}"
    # что: Маркер у правой стороны (подготовлена после применения пакета).
    # ожидание: PKG.
    When the admin prepares the offer "NOPKG" in the second project
    # что: Готовим оферту на проекте без пакета.
    # ожидание: 200.
    And the admin calls GET "/api/offers/{NOPKG}/snapshot"
    # что: Читаем её снимок.
    # ожидание: 200.
    Then the response field "appliedPackageCode" equals "null"
    # что: Маркера пакета нет.
    # ожидание: appliedPackageCode = null.
    And the response field "appliedPackageName" equals "null"
    # что: Названия пакета нет.
    # ожидание: appliedPackageName = null.
    When the admin calls GET "/api/offers/project/{X_PID}/list"
    # что: Читаем список второго проекта.
    # ожидание: 200.
    Then the response field "[id={NOPKG}].appliedPackageCode" equals "null"
    # что: В списке тоже нет маркера.
    # ожидание: null.
