@detailed @FOR-QA-AUTO-05-07B @ui
Feature: FOR-05-07b C (UI). Round 2 comparison of several offers
  Экран сравнения 2–10 оферт: выбор и переход, колонки и группы, раскрытие категории, комнаты по клику, клиент, замена старой панели (тест-кейсы TC-R2-UI-CMP-01..06).

  # Повторяемость: генератор run-id + teardown: свой проект FOR-05-07b {run-id} со сметой S3 на сценарий; три оферты
  # A, B, C с разными сметами (A — S3, B — с работой 4 и количеством 6, C — DRAFT), B неактивна, плюс legacy-оферта
  # без snapshot; teardown удаляет комнаты, проект и клиентов. Пакет у одной из оферт покрыт TC-UI-PKG-01.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Бэкенд и фронтенд отвечают.
    And an offers project with a client and rooms A, B and C exists
    # что: Клиент, проект FOR-05-07b {run-id} и комнаты A, B, C через API.
    # ожидание: Проект создан.
    And the offers estimate has two works in rooms and one work in no room
    # что: Смета S3 через API: работа 1 в A=2 и B=3, работа 2 в A=1,5, работа 3 без комнаты.
    # ожидание: Смета готова, NET_0 запомнен.
    And the admin is signed in to the browser
    # что: Входим в браузер исполнителем (ADMIN), язык RU.
    # ожидание: Оболочка приложения открыта.

  Scenario: TC-R2-UI-CMP-01 Selecting offers and opening the comparison
    TC-R2-UI-CMP-01: чекбоксы есть только у оферт со snapshot; одна отмеченная оферта не даёт кнопки сравнения; три — дают «Сравнить (3)» и переход на /pricing/compare?ids=...; прямая загрузка с одним id и с несуществующим id; ссылка «назад» (2.12).
    Given the admin prepares and sends the offer "A"
    # что: Оферта A со сметой S3 (prepare и send).
    # ожидание: A SENT.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем в смету работу 4 в комнате A.
    # ожидание: Работы 4 в A нет, в B будет.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":6}'
    # что: Меняем количество работы 1 в комнате A на 6.
    # ожидание: 200.
    And the live estimate net is remembered as "NET1"
    # что: Запоминаем новый итог сметы.
    # ожидание: NET_1.
    And the admin prepares and sends the offer "B"
    # что: Оферта B с изменённой сметой.
    # ожидание: B SENT.
    And the admin prepares the offer "C"
    # что: DRAFT-оферта C.
    # ожидание: C DRAFT.
    And the admin successfully calls POST "/api/offers/{B}/deactivate"
    # что: Делаем B неактивной.
    # ожидание: 200.
    And a legacy offer "L" without a snapshot in status "SENT" exists
    # что: Legacy-оферта без snapshot.
    # ожидание: Есть.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточки A, B, C и L.
    Then "offer-select-{A}" is visible
    # что: Чекбокс у A.
    # ожидание: Есть.
    And no element matches "offer-select-{L}"
    # что: Чекбокс у legacy-оферты.
    # ожидание: Отсутствует.
    When the user clicks "offer-select-{A}"
    # что: Отмечаем одну оферту.
    # ожидание: Отмечена.
    Then no element matches "offer-compare-open"
    # что: Кнопка сравнения с одной офертой.
    # ожидание: Скрыта.
    When the user clicks "offer-select-{B}"
    # что: Отмечаем вторую.
    # ожидание: Отмечена.
    And the user clicks "offer-select-{C}"
    # что: Отмечаем третью.
    # ожидание: Отмечена.
    Then "offer-selection-bar" is visible
    # что: Панель выбора.
    # ожидание: Видна.
    And "offer-compare-open" contains the text "(3)"
    # что: Кнопка сравнения.
    # ожидание: «Сравнить (3)».
    When the user clicks "offer-compare-open"
    # что: Открываем сравнение.
    # ожидание: Экран сравнения.
    Then the URL contains "/projects/{PID}/pricing/compare?ids={A},{B},{C}"
    # что: Адрес.
    # ожидание: ids = A,B,C.
    And "offer-compare-page" is visible
    # что: Экран.
    # ожидание: Виден.
    And "offer-compare-table" is visible
    # что: Таблица.
    # ожидание: Видна.
    When the user opens the path "/projects/{PID}/pricing/compare?ids={A}"
    # что: Прямая загрузка с одним id.
    # ожидание: Сообщение «мало оферт».
    Then "offer-compare-too-few" is visible
    # что: Сообщение.
    # ожидание: Видно.
    When the user opens the path "/projects/{PID}/pricing/compare?ids={A},999999999"
    # что: Прямая загрузка с несуществующим id.
    # ожидание: Сообщение об ошибке, без падения страницы.
    Then "offer-compare-error" is visible
    # что: Сообщение об ошибке.
    # ожидание: Видно.
    And "offer-compare-back" is visible
    # что: Ссылка «назад».
    # ожидание: Есть.
    When the user clicks "offer-compare-back"
    # что: Нажимаем «назад».
    # ожидание: Возврат на вкладку оферт.
    Then the URL path is "/projects/{PID}/pricing"
    # что: Адрес.
    # ожидание: /projects/{PID}/pricing.

  Scenario: TC-R2-UI-CMP-02 Columns and category rows
    TC-R2-UI-CMP-02: по колонке на оферту с именем, статусом, бейджем неактивности, версией и итогами; группы свёрнуты, у первой колонки нет дельты, у остальных есть; колонок по комнатам нет (2.12).
    Given the admin prepares and sends the offer "A"
    # что: Оферта A со сметой S3 (prepare и send).
    # ожидание: A SENT.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем в смету работу 4 в комнате A.
    # ожидание: Работы 4 в A нет, в B будет.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":6}'
    # что: Меняем количество работы 1 в комнате A на 6.
    # ожидание: 200.
    And the live estimate net is remembered as "NET1"
    # что: Запоминаем новый итог сметы.
    # ожидание: NET_1.
    And the admin prepares and sends the offer "B"
    # что: Оферта B с изменённой сметой.
    # ожидание: B SENT.
    And the admin prepares the offer "C"
    # что: DRAFT-оферта C.
    # ожидание: C DRAFT.
    And the admin successfully calls POST "/api/offers/{B}/deactivate"
    # что: Делаем B неактивной.
    # ожидание: 200.
    And a legacy offer "L" without a snapshot in status "SENT" exists
    # что: Legacy-оферта без snapshot.
    # ожидание: Есть.
    When the user opens the path "/projects/{PID}/pricing/compare?ids={A},{B}"
    # что: Открываем сравнение A и B по адресу.
    # ожидание: Экран сравнения.
    Then "offer-compare-col-{A}" contains the text "Offer-{RUN}-A"
    # что: Колонка A.
    # ожидание: Имя оферты.
    And "offer-compare-col-{B}" contains the text "Offer-{RUN}-B"
    # что: Колонка B.
    # ожидание: Имя оферты.
    And "offer-compare-status-{A}" contains the text "Отправлена"
    # что: Статус A.
    # ожидание: «Отправлена».
    And "offer-compare-inactive-{B}" is visible
    # что: Бейдж неактивности B.
    # ожидание: Виден.
    And no element matches "offer-compare-inactive-{A}"
    # что: Бейдж у активной A.
    # ожидание: Нет.
    And "offer-compare-totals-{A}" shows the amount "{NET0}"
    # что: Итог netto A.
    # ожидание: NET_0.
    And "offer-compare-totals-{B}" shows the amount "{NET1}"
    # что: Итог netto B.
    # ожидание: NET_1.
    And "offer-compare-group-cost-{CATKEY_1}-{A}" is visible
    # что: Стоимость категории работы 1 у A.
    # ожидание: Есть.
    And no element matches "offer-compare-group-delta-{CATKEY_1}-{A}"
    # что: Дельта у первой колонки.
    # ожидание: Нет.
    And "offer-compare-group-delta-{CATKEY_1}-{B}" contains the text "+"
    # что: Дельта у B к первой оферте.
    # ожидание: Положительная (B дороже).
    And no element matches "offer-compare-work-{WORK_1}"
    # что: Работы категории.
    # ожидание: По умолчанию группы свёрнуты.
    And no element matches "estimate-room-col-{ROOM_A}"
    # что: Колонки по комнатам.
    # ожидание: Нет.

  Scenario: TC-R2-UI-CMP-03 Expanding a category: works, volumes, costs and deltas
    TC-R2-UI-CMP-03: раскрытие категории показывает работы с объёмом, стоимостью и дельтами; отсутствующая работа — прочерк; повторный клик сворачивает (2.12).
    Given the admin prepares and sends the offer "A"
    # что: Оферта A со сметой S3 (prepare и send).
    # ожидание: A SENT.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем в смету работу 4 в комнате A.
    # ожидание: Работы 4 в A нет, в B будет.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":6}'
    # что: Меняем количество работы 1 в комнате A на 6.
    # ожидание: 200.
    And the live estimate net is remembered as "NET1"
    # что: Запоминаем новый итог сметы.
    # ожидание: NET_1.
    And the admin prepares and sends the offer "B"
    # что: Оферта B с изменённой сметой.
    # ожидание: B SENT.
    And the admin prepares the offer "C"
    # что: DRAFT-оферта C.
    # ожидание: C DRAFT.
    And the admin successfully calls POST "/api/offers/{B}/deactivate"
    # что: Делаем B неактивной.
    # ожидание: 200.
    And a legacy offer "L" without a snapshot in status "SENT" exists
    # что: Legacy-оферта без snapshot.
    # ожидание: Есть.
    When the user opens the path "/projects/{PID}/pricing/compare?ids={A},{B}"
    # что: Сравнение A и B.
    # ожидание: Экран сравнения.
    And the group "offer-compare-group-{CATKEY_1}" is expanded
    # что: Раскрываем категорию работы 1.
    # ожидание: Видна строка работы 1.
    Then "offer-compare-work-{WORK_1}" is visible
    # что: Строка работы 1.
    # ожидание: Видна.
    And "offer-compare-cell-{WORK_1}-{A}" contains the text "5"
    # что: Объём работы 1 в A.
    # ожидание: 5.
    And "offer-compare-cell-{WORK_1}-{B}" contains the text "9"
    # что: Объём работы 1 в B.
    # ожидание: 9.
    And "offer-compare-qty-delta-{WORK_1}-{B}" contains the text "+4"
    # что: Дельта объёма B к A.
    # ожидание: +4.
    And "offer-compare-cost-delta-{WORK_1}-{B}" contains the text "+"
    # что: Дельта стоимости.
    # ожидание: Положительная.
    When the group "offer-compare-group-{CATKEY_4}" is expanded
    # что: Раскрываем категорию работы 4 (её нет в A).
    # ожидание: Видна строка работы 4.
    Then "offer-compare-missing-{WORK_4}-{A}" is visible
    # что: Ячейка работы 4 у A.
    # ожидание: Прочерк, работы в оферте нет.
    And "offer-compare-cell-{WORK_4}-{B}" is visible
    # что: Ячейка работы 4 у B.
    # ожидание: Есть.
    When the group "offer-compare-group-{CATKEY_1}" is toggled
    # что: Повторный клик по категории работы 1.
    # ожидание: Категория свёрнута.
    Then no element matches "offer-compare-work-{WORK_1}"
    # что: Работы категории 1.
    # ожидание: Скрыты.

  Scenario: TC-R2-UI-CMP-04 Rooms of a work by a click on its cell
    TC-R2-UI-CMP-04: клик по ячейке работы открывает список комнат этой оферты с объёмами; у A и B объёмы по комнатам разные; для отсутствующей работы списка нет (2.12).
    Given the admin prepares and sends the offer "A"
    # что: Оферта A со сметой S3 (prepare и send).
    # ожидание: A SENT.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем в смету работу 4 в комнате A.
    # ожидание: Работы 4 в A нет, в B будет.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":6}'
    # что: Меняем количество работы 1 в комнате A на 6.
    # ожидание: 200.
    And the live estimate net is remembered as "NET1"
    # что: Запоминаем новый итог сметы.
    # ожидание: NET_1.
    And the admin prepares and sends the offer "B"
    # что: Оферта B с изменённой сметой.
    # ожидание: B SENT.
    And the admin prepares the offer "C"
    # что: DRAFT-оферта C.
    # ожидание: C DRAFT.
    And the admin successfully calls POST "/api/offers/{B}/deactivate"
    # что: Делаем B неактивной.
    # ожидание: 200.
    And a legacy offer "L" without a snapshot in status "SENT" exists
    # что: Legacy-оферта без snapshot.
    # ожидание: Есть.
    When the user opens the path "/projects/{PID}/pricing/compare?ids={A},{B}"
    # что: Сравнение A и B.
    # ожидание: Экран сравнения.
    And the group "offer-compare-group-{CATKEY_1}" is expanded
    # что: Раскрываем категорию работы 1.
    # ожидание: Видна работа 1.
    And the user clicks "offer-compare-cell-{WORK_1}-{A}"
    # что: Кликаем по ячейке работы 1 у A.
    # ожидание: Открыт popover.
    Then "offer-compare-rooms-{WORK_1}-{A}" is visible
    # что: Popover комнат.
    # ожидание: Виден.
    And "offer-compare-rooms-{WORK_1}-{A}" contains the text "{ROOM_A_LABEL}"
    # что: Комната A в popover.
    # ожидание: Есть.
    And "offer-compare-rooms-{WORK_1}-{A}" contains the text "{ROOM_B_LABEL}"
    # что: Комната B в popover.
    # ожидание: Есть.
    And "offer-compare-rooms-{WORK_1}-{A}" contains the text "2"
    # что: Объём по комнате A у A.
    # ожидание: 2.
    When the user presses "Escape"
    # что: Закрываем popover.
    # ожидание: Закрыт.
    And the user clicks "offer-compare-cell-{WORK_1}-{B}"
    # что: Кликаем по ячейке работы 1 у B.
    # ожидание: Popover оферты B.
    Then "offer-compare-rooms-{WORK_1}-{B}" contains the text "6"
    # что: Объём по комнате A у B.
    # ожидание: 6 (у A было 2).
    When the user presses "Escape"
    # что: Закрываем popover.
    # ожидание: Закрыт.
    And the group "offer-compare-group-{CATKEY_4}" is expanded
    # что: Раскрываем категорию работы 4.
    # ожидание: Видна работа 4.
    Then no element matches "offer-compare-cell-{WORK_4}-{A}"
    # что: Ячейка отсутствующей работы.
    # ожидание: Кликабельной ячейки нет, popover не открывается.

  Scenario: TC-R2-UI-CMP-05 The client comparison
    TC-R2-UI-CMP-05: у клиента чекбоксы только у не-DRAFT оферт; без базовых цен и трудозатрат; прямой URL с DRAFT-офертой даёт ошибку (2.12, 3.2).
    Given the admin prepares and sends the offer "A"
    # что: Оферта A со сметой S3 (prepare и send).
    # ожидание: A SENT.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем в смету работу 4 в комнате A.
    # ожидание: Работы 4 в A нет, в B будет.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":6}'
    # что: Меняем количество работы 1 в комнате A на 6.
    # ожидание: 200.
    And the live estimate net is remembered as "NET1"
    # что: Запоминаем новый итог сметы.
    # ожидание: NET_1.
    And the admin prepares and sends the offer "B"
    # что: Оферта B с изменённой сметой.
    # ожидание: B SENT.
    And the admin prepares the offer "C"
    # что: DRAFT-оферта C.
    # ожидание: C DRAFT.
    And the admin successfully calls POST "/api/offers/{B}/deactivate"
    # что: Делаем B неактивной.
    # ожидание: 200.
    And a legacy offer "L" without a snapshot in status "SENT" exists
    # что: Legacy-оферта без snapshot.
    # ожидание: Есть.
    Given the client is signed in to the browser
    # что: Входим клиентом по OTP.
    # ожидание: Оболочка открыта.
    When the user opens the offers tab
    # что: Вкладка оферт клиента.
    # ожидание: Карточки A и B (SENT).
    Then no element matches "offer-card-{C}"
    # что: DRAFT-оферта C.
    # ожидание: У клиента её нет.
    And no element matches "offer-select-{C}"
    # что: Чекбокс DRAFT-оферты.
    # ожидание: Нет.
    When the user clicks "offer-select-{A}"
    # что: Отмечаем A.
    # ожидание: Отмечена.
    And the user clicks "offer-select-{B}"
    # что: Отмечаем неактивную B.
    # ожидание: Отмечена.
    And the user clicks "offer-compare-open"
    # что: Открываем сравнение.
    # ожидание: Экран сравнения.
    Then "offer-compare-table" is visible
    # что: Таблица.
    # ожидание: Видна.
    And "offer-compare-inactive-{B}" is visible
    # что: Неактивная SENT-оферта в сравнении.
    # ожидание: Допустима, бейдж виден.
    And no element matches "offer-labour-"
    # что: Трудозатраты и маржа.
    # ожидание: Нет.
    When the user opens the path "/projects/{PID}/pricing/compare?ids={A},{C}"
    # что: Прямой URL с DRAFT-офертой C.
    # ожидание: Ошибка (404 от API).
    Then "offer-compare-error" is visible
    # что: Сообщение об ошибке.
    # ожидание: Видно.

  Scenario: TC-R2-UI-CMP-06 The old comparison panel is gone
    TC-R2-UI-CMP-06: встроенной панели сравнения нет; сравнение только через offer-compare-open; итоги на экране равны ответу GET /api/offers/compare (3.5).
    Given the admin prepares and sends the offer "A"
    # что: Оферта A со сметой S3 (prepare и send).
    # ожидание: A SENT.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем в смету работу 4 в комнате A.
    # ожидание: Работы 4 в A нет, в B будет.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":6}'
    # что: Меняем количество работы 1 в комнате A на 6.
    # ожидание: 200.
    And the live estimate net is remembered as "NET1"
    # что: Запоминаем новый итог сметы.
    # ожидание: NET_1.
    And the admin prepares and sends the offer "B"
    # что: Оферта B с изменённой сметой.
    # ожидание: B SENT.
    And the admin prepares the offer "C"
    # что: DRAFT-оферта C.
    # ожидание: C DRAFT.
    And the admin successfully calls POST "/api/offers/{B}/deactivate"
    # что: Делаем B неактивной.
    # ожидание: 200.
    And a legacy offer "L" without a snapshot in status "SENT" exists
    # что: Legacy-оферта без snapshot.
    # ожидание: Есть.
    When the user opens the offers tab
    # что: Вкладка оферт.
    # ожидание: Карточки.
    Then no element matches "offer-compare-button"
    # что: Кнопка старой панели.
    # ожидание: Нет.
    And no element matches "offer-compare-row"
    # что: Строки старой панели.
    # ожидание: Нет.
    And no element matches "offer-compare-total-delta"
    # что: Дельта итога старой панели.
    # ожидание: Нет.
    When the user clicks "offer-select-{A}"
    # что: Отмечаем A.
    # ожидание: Отмечена.
    And the user clicks "offer-select-{B}"
    # что: Отмечаем B.
    # ожидание: Отмечена.
    And the user clicks "offer-compare-open"
    # что: Открываем сравнение.
    # ожидание: Экран сравнения.
    And the admin calls GET "/api/offers/compare?left={A}&right={B}"
    # что: Эталон через API.
    # ожидание: 200.
    Then the response status is 200
    # что: Статус.
    # ожидание: 200.
    And the response array "changed" has an item with "workItemId" equal to "{WORK_1}"
    # что: Изменённые работы в эталоне.
    # ожидание: Работа 1.
    And the response array "added" has an item with "workItemId" equal to "{WORK_4}"
    # что: Добавленные работы в эталоне.
    # ожидание: Работа 4.
    And "offer-compare-totals-{B}" shows the amount "{NET1}"
    # что: Итог B на экране.
    # ожидание: NET_1, как в оферте.
    And "offer-compare-totals-{A}" shows the amount "{NET0}"
    # что: Итог A на экране.
    # ожидание: NET_0.
