@detailed @FOR-QA-AUTO-05-07B @ui
Feature: FOR-05-07b B7. Comparing two offers and the no-snapshot state (UI)
  Сравнение двух оферт одного проекта из истории и недоступность сравнения для оферты без snapshot (тест-кейс TC-UI-CMP-01).

  # Повторяемость: генератор run-id + teardown: свой проект FOR-05-07b {run-id} со сметой S3; оферты через API с именами
  # Offer-{run-id}-X, между ними меняется количество работы 1 в комнате A; teardown удаляет комнаты, проект и клиентов.
  # Round 2 (D4): сравнение открывается чекбоксами offer-select-{id} и кнопкой offer-compare-open.

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

  Scenario: TC-UI-CMP-01 Comparing two offers of one project
    TC-UI-CMP-01: две оферты со snapshot из истории сравниваются на экране сравнения; итоги и дельта совпадают с GET /api/offers/compare; у legacy-оферты чекбокса выбора нет (3.5).
    Given the admin prepares and sends the offer "T1"
    # что: Оферта T1 со сметой S3 (prepare и send).
    # ожидание: T1 SENT.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":6}'
    # что: Меняем количество работы 1 в комнате A на 6.
    # ожидание: 200.
    And the live estimate net is remembered as "NET1"
    # что: Запоминаем новый итог сметы.
    # ожидание: NET_1.
    And the admin prepares and sends the offer "T2"
    # что: Оферта T2 с изменённой сметой.
    # ожидание: T2 SENT.
    And the admin successfully calls POST "/api/offers/{T1}/withdraw"
    # что: Отзываем T1 (история).
    # ожидание: 200.
    And the admin successfully calls POST "/api/offers/{T2}/withdraw"
    # что: Отзываем T2 (история).
    # ожидание: 200.
    And a legacy offer "LW" without a snapshot in status "WITHDRAWN" exists
    # что: Legacy-оферта без snapshot в истории.
    # ожидание: Есть.
    When the admin calls GET "/api/offers/compare?left={T1}&right={T2}"
    # что: Эталон сравнения через API.
    # ожидание: 200.
    Then the response status is 200
    # что: Статус ответа.
    # ожидание: 200.
    And the response array "changed" has an item with "workItemId" equal to "{WORK_1}"
    # что: Изменённые работы в эталоне.
    # ожидание: Работа 1 изменена.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: История с T1, T2 и LW.
    Then no element matches "offer-select-{LW}"
    # что: Чекбокс у legacy-оферты.
    # ожидание: Отсутствует.
    When the user clicks "offer-select-{T1}"
    # что: Отмечаем T1 в истории.
    # ожидание: Отмечена.
    And the user clicks "offer-select-{T2}"
    # что: Отмечаем T2 в истории.
    # ожидание: Отмечена.
    And the user clicks "offer-compare-open"
    # что: Открываем сравнение.
    # ожидание: Экран сравнения.
    Then the URL contains "/projects/{PID}/pricing/compare?ids={T1},{T2}"
    # что: Адрес экрана сравнения.
    # ожидание: ids = T1,T2.
    And "offer-compare-totals-{T1}" shows the amount "{NET0}"
    # что: Итог netto T1.
    # ожидание: NET_0.
    And "offer-compare-totals-{T2}" shows the amount "{NET1}"
    # что: Итог netto T2.
    # ожидание: NET_1.
    When the group "offer-compare-group-{CATKEY_1}" is expanded
    # что: Раскрываем категорию работы 1.
    # ожидание: Видна строка работы 1.
    Then "offer-compare-cell-{WORK_1}-{T1}" contains the text "5"
    # что: Объём работы 1 в T1.
    # ожидание: 5 (A=2, B=3).
    And "offer-compare-cell-{WORK_1}-{T2}" contains the text "9"
    # что: Объём работы 1 в T2 и дельта.
    # ожидание: 9 (A=6, B=3), дельта +4.
    And "offer-compare-qty-delta-{WORK_1}-{T2}" contains the text "+4"
    # что: Дельта объёма к первой оферте.
    # ожидание: +4, как разница количеств в GET /api/offers/compare.
