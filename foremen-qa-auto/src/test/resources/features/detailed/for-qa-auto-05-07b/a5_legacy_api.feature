@detailed @FOR-QA-AUTO-05-07B @api
Feature: FOR-05-07b A5. Legacy offers (API)
  Оферты без снимка (legacy): видимость в списке, отказ в просмотре, PDF, restore и сравнении; черновик legacy получает снимок при refresh или send (тест-кейсы TC-LEG-01..02).

  # Повторяемость: legacy-оферты вставляются в проект FOR-05-07b {run-id} этого сценария и удаляются
  # каскадом вместе с проектом в teardown.

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

  Scenario: TC-LEG-01 A legacy offer without a snapshot
    TC-LEG-01: legacy видна в списке с hasSnapshot = false; снимок, PDF и restore — 404, сравнение отклоняется; снимок не появляется (3.3, A3).
    Given a legacy offer "LEG" without a snapshot in status "SENT" exists
    # что: Вставляем legacy-оферту SENT без снимка.
    # ожидание: Оферта создана.
    And the admin prepares the offer "A"
    # что: Готовим обычную оферту A для сравнения.
    # ожидание: DRAFT.
    When the admin calls GET "/api/offers/project/{PID}/list"
    # что: Читаем список оферт.
    # ожидание: 200.
    Then the response field "[id={LEG}].hasSnapshot" equals "false"
    # что: Флаг снимка legacy.
    # ожидание: false.
    And the response field "[id={LEG}].appliedPackageCode" equals "null"
    # что: Маркера пакета нет.
    # ожидание: null.
    When the admin calls GET "/api/offers/{LEG}/snapshot"
    # что: Запрашиваем снимок legacy.
    # ожидание: 404.
    Then the response status is 404
    # что: Проверяем код ответа.
    # ожидание: 404.
    When the admin calls GET "/api/offers/{LEG}/pdf"
    # что: Запрашиваем PDF legacy.
    # ожидание: 404.
    Then the response error code is "error.offer.snapshot.missing"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.snapshot.missing.
    When the admin calls POST "/api/offers/{LEG}/restore"
    # что: Restore legacy.
    # ожидание: 404.
    Then the response error code is "error.offer.snapshot.missing"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.snapshot.missing.
    When the admin calls GET "/api/offers/compare?left={LEG}&right={A}"
    # что: Сравниваем legacy с обычной офертой.
    # ожидание: Отказ, как до фикса.
    Then the response status is one of "400, 404"
    # что: Проверяем код ответа.
    # ожидание: 400 или 404.
    When the admin calls GET "/api/offers/project/{PID}/list"
    # что: Снова читаем список.
    # ожидание: 200.
    Then the response field "[id={LEG}].hasSnapshot" equals "false"
    # что: Снимок у legacy не появился ни одним из вызовов.
    # ожидание: false.

  Scenario: TC-LEG-02 A legacy DRAFT gets a snapshot on refresh or send
    TC-LEG-02: legacy-черновик получает снимок при refresh-snapshot, второй — при send; после send снимок заморожен (A7).
    Given a legacy offer "LD1" without a snapshot in status "DRAFT" exists
    # что: Вставляем legacy-черновик LD1.
    # ожидание: Оферта создана.
    And a legacy offer "LD2" without a snapshot in status "DRAFT" exists
    # что: Вставляем legacy-черновик LD2.
    # ожидание: Оферта создана.
    When the admin calls POST "/api/offers/{LD1}/refresh-snapshot"
    # что: Обновляем снимок LD1 из сметы.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "offer.hasSnapshot" equals "true"
    # что: Снимок появился.
    # ожидание: hasSnapshot = true.
    When the admin calls GET "/api/offers/{LD1}/snapshot"
    # что: Читаем снимок LD1.
    # ожидание: 200.
    Then the response field "totalNet" equals "{NET0}"
    # что: Снимок равен текущей смете.
    # ожидание: totalNet = NET0.
    When the admin sends the offer "LD2"
    # что: Отправляем legacy-черновик LD2.
    # ожидание: 200.
    And the admin calls GET "/api/offers/{LD2}/snapshot"
    # что: Читаем снимок LD2.
    # ожидание: 200.
    Then the response status is 200
    # что: Проверяем код ответа.
    # ожидание: 200.
    And the response field "totalNet" equals "{NET0}"
    # что: Итог снимка равен итогу оферты.
    # ожидание: NET0.
    When the response body is remembered as "LD2_SNAP"
    # что: Запоминаем снимок LD2.
    # ожидание: Сохранён.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":8}'
    # что: Меняем смету после отправки.
    # ожидание: 200.
    And the admin calls GET "/api/offers/{LD2}/snapshot"
    # что: Снова читаем снимок LD2.
    # ожидание: 200.
    Then the response body equals the remembered "LD2_SNAP"
    # что: Снимок заморожен.
    # ожидание: Тело не изменилось.
