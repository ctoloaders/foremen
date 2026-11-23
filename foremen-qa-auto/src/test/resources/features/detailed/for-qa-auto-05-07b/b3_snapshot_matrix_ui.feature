@detailed @FOR-QA-AUTO-05-07B @ui
Feature: FOR-05-07b B3. Offer snapshot shown as the estimate matrix (UI)
  Просмотр snapshot оферты как матрицы сметы: группы по категориям, работы × комнаты, только чтение, просмотр из истории, «Обновить из сметы» (тест-кейсы TC-UI-SNAP-01..05).

  # Повторяемость: генератор run-id + teardown: каждый сценарий создаёт свой проект FOR-05-07b {run-id} со сметой S3;
  # оферты готовятся через API с именами Offer-{run-id}-X; teardown удаляет комнаты, проект с офертами и клиентов.
  # Вкладка оферт сама открывает матрицу последней ожидающей оферты со snapshot; сценарии закрывают её и открывают заново
  # кнопкой просмотра, как в тест-кейсе.

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

  Scenario: TC-UI-SNAP-01 The matrix shows works by rooms grouped by category
    TC-UI-SNAP-01: матрица offer-snapshot-matrix: группы категорий работ 1 и 2, ячейки работа × комната с количествами S3, колонки только комнат A и B, итог netto = NET_0 (2.3).
    Given the admin prepares the offer "S"
    # что: Оферта Offer-{run-id}-S через API (DRAFT со snapshot).
    # ожидание: Оферта создана.
    When the user opens the offers tab
    # что: Открываем /projects/{PID}/pricing.
    # ожидание: Вкладка оферт отображается.
    And the user clicks "offer-snapshot-matrix-close"
    # что: Закрываем матрицу, открытую по умолчанию.
    # ожидание: Матрица закрыта.
    Then "offer-snapshot-matrix" is not shown
    # что: Проверяем, что матрицы нет.
    # ожидание: Не показана.
    When the user clicks "offer-view-snapshot-{S}"
    # что: Нажимаем «Открыть снимок» в карточке S.
    # ожидание: Открыта матрица.
    Then "offer-snapshot-matrix-grid" is visible in "offer-snapshot-matrix"
    # что: Проверяем сетку матрицы.
    # ожидание: Сетка видна.
    And "estimate-group-header-{CAT_1}" is visible in "offer-snapshot-matrix"
    # что: Заголовок группы категории работы 1.
    # ожидание: Виден.
    And "estimate-group-header-{CAT_2}" is visible in "offer-snapshot-matrix"
    # что: Заголовок группы категории работы 2.
    # ожидание: Виден.
    And "estimate-work-row-{WORK_1}" is visible in "offer-snapshot-matrix"
    # что: Строка работы 1.
    # ожидание: Видна.
    And "estimate-work-row-{WORK_2}" is visible in "offer-snapshot-matrix"
    # что: Строка работы 2.
    # ожидание: Видна.
    And "estimate-cell-volume-{WORK_1}-{ROOM_A}" shows the amount "2" in "offer-snapshot-matrix"
    # что: Ячейка работа 1 × комната A.
    # ожидание: 2.
    And "estimate-cell-volume-{WORK_1}-{ROOM_B}" shows the amount "3" in "offer-snapshot-matrix"
    # что: Ячейка работа 1 × комната B.
    # ожидание: 3.
    And "estimate-cell-volume-{WORK_2}-{ROOM_A}" shows the amount "1.5" in "offer-snapshot-matrix"
    # что: Ячейка работа 2 × комната A.
    # ожидание: 1,5.
    And "estimate-room-col-{ROOM_A}" is present
    # что: Колонка комнаты A.
    # ожидание: Есть.
    And "estimate-room-col-{ROOM_B}" is present
    # что: Колонка комнаты B.
    # ожидание: Есть.
    And no element matches "estimate-room-col-{ROOM_C}"
    # что: Колонка комнаты C (в ней нет работ).
    # ожидание: Отсутствует.
    And "offer-snapshot-matrix-total-net" shows the amount "{NET0}"
    # что: Итог netto матрицы.
    # ожидание: NET_0.
    And "offer-total-net" shows the amount "{NET0}" in "offer-card-{S}"
    # что: Итог netto карточки.
    # ожидание: Совпадает с матрицей (NET_0).

  Scenario: TC-UI-SNAP-02 Works without a room are not shown
    TC-UI-SNAP-02: работа 3 без количеств не попадает в матрицу; для старого snapshot версии 1 с пустой строкой она тоже не показывается (read-time guard) (1.2, 2.2).
    Given the admin prepares the offer "S"
    # что: Оферта S через API при наличии работы 3 без комнаты.
    # ожидание: Оферта создана.
    And an offer "V1" with a version 1 snapshot holding a row without rooms in status "SENT" exists
    # что: Вставляем в БД оферту со snapshot v1: работа 1 с ячейкой и работа 3 без ячеек.
    # ожидание: Оферта V1 есть.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Вкладка отображается.
    And the user clicks "offer-snapshot-matrix-close"
    # что: Закрываем матрицу по умолчанию.
    # ожидание: Закрыта.
    And the user clicks "offer-view-snapshot-{S}"
    # что: Открываем матрицу S.
    # ожидание: Матрица видна.
    Then "estimate-work-row-{WORK_1}" is visible in "offer-snapshot-matrix"
    # что: Строка работы 1.
    # ожидание: Видна.
    And no element matches "estimate-work-row-{WORK_3}"
    # что: Строка работы 3 без комнаты.
    # ожидание: Отсутствует.
    And no element matches "estimate-group-header-{CAT_3}"
    # что: Группа категории работы 3.
    # ожидание: Пустой группы нет.
    When the user clicks "offer-view-snapshot-{V1}"
    # что: Открываем матрицу оферты со snapshot v1.
    # ожидание: Матрица V1 видна.
    Then "offer-snapshot-matrix" contains the text "qa-v1-rows"
    # что: Баннер матрицы V1.
    # ожидание: Имя оферты qa-v1-rows.
    And "estimate-work-row-{WORK_1}" is visible in "offer-snapshot-matrix"
    # что: Строка с ячейкой.
    # ожидание: Видна.
    And no element matches "estimate-work-row-{WORK_3}"
    # что: Пустая строка из snapshot v1.
    # ожидание: Не показана.

  Scenario: TC-UI-SNAP-03 The matrix is read-only
    TC-UI-SNAP-03: клик по ячейке не открывает редактор, полей ввода и кнопок правки нет; у клиента нет себестоимости и сравнения трудозатрат; вкладка сметы по-прежнему редактируется (2.3, 3.7).
    Given the admin prepares and sends the offer "S"
    # что: Оферта S через API: prepare и send.
    # ожидание: S SENT.
    When the user opens the offers tab
    # что: Открываем вкладку оферт (матрица S открыта по умолчанию).
    # ожидание: Матрица видна.
    And the user force-clicks "estimate-cell-{WORK_1}-{ROOM_A}" in "offer-snapshot-matrix"
    # что: Кликаем по ячейке работа 1 × комната A.
    # ожидание: Ничего не открывается.
    Then "estimate-cell-report" is not shown
    # что: Редактор ячейки.
    # ожидание: Не открыт.
    And no input field is shown in "offer-snapshot-matrix"
    # что: Поля ввода количества.
    # ожидание: Нет.
    And no visible element in "offer-snapshot-matrix" matches "estimate-work-hammer-"
    # что: Молоток (отчёт) у строк.
    # ожидание: Скрыт.
    And no visible element in "offer-snapshot-matrix" matches "estimate-work-magnifier-"
    # что: Лупа у строк.
    # ожидание: Скрыта.
    And no visible element in "offer-snapshot-matrix" matches "estimate-work-cheapest-"
    # что: Кнопка «самый дешёвый» у строк.
    # ожидание: Скрыта.
    And no visible element in "offer-snapshot-matrix" matches "estimate-work-clear-"
    # что: Кнопка очистки строки.
    # ожидание: Скрыта.
    And no visible element in "offer-snapshot-matrix" matches "estimate-group-cheapest-"
    # что: Массовое действие группы.
    # ожидание: Скрыто.
    When the user opens the estimate tab
    # что: Открываем вкладку сметы.
    # ожидание: Матрица сметы отображается.
    And the group "estimate-group-header-{CAT_1}" is expanded
    # что: Раскрываем группу категории работы 1 (на вкладке сметы группы свёрнуты).
    # ожидание: Строка работы 1 видна.
    And the user triggers a click on "estimate-cell-{WORK_1}-{ROOM_A}"
    # что: Кликаем по той же ячейке в смете.
    # ожидание: Редактор открывается.
    Then "estimate-cell-report" is visible
    # что: Редактирование в смете.
    # ожидание: Редактор ячейки открыт (3.7).
    Given the client is signed in to the browser
    # что: Входим клиентом по OTP.
    # ожидание: Оболочка приложения открыта.
    When the user opens the offers tab
    # что: Вкладка оферт клиента, матрица S открыта.
    # ожидание: Матрица видна.
    Then "offer-snapshot-matrix-grid" is visible in "offer-snapshot-matrix"
    # что: Матрица у клиента.
    # ожидание: Видна.
    And no element matches "offer-labour-"
    # что: Сравнение трудозатрат и базовые цены.
    # ожидание: У клиента их нет.
    And no input field is shown in "offer-snapshot-matrix"
    # что: Поля ввода у клиента.
    # ожидание: Нет.

  Scenario: TC-UI-SNAP-04 Viewing from the history and the no-snapshot state
    TC-UI-SNAP-04: у терминальной оферты в истории есть просмотр, открывающий ту же матрицу; у legacy-оферты без snapshot просмотра нет, вместо него метка «нет снимка» (2.3, 3.3).
    Given the admin prepares and sends the offer "T"
    # что: Оферта T через API: prepare и send.
    # ожидание: T SENT.
    And the admin successfully calls POST "/api/offers/{T}/withdraw"
    # что: Отзываем T через API (терминальная).
    # ожидание: 200, WITHDRAWN.
    And a legacy offer "L" without a snapshot in status "DRAFT" exists
    # что: Вставляем legacy DRAFT-оферту без snapshot.
    # ожидание: Оферта L есть.
    And a legacy offer "LW" without a snapshot in status "WITHDRAWN" exists
    # что: Вставляем legacy WITHDRAWN-оферту без snapshot.
    # ожидание: Оферта LW есть.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточка L и история с T и LW.
    Then "offer-version-view-{T}" is visible in "offer-versions"
    # что: Просмотр у терминальной T.
    # ожидание: Есть.
    When the user clicks "offer-version-view-{T}"
    # что: Открываем snapshot T из истории.
    # ожидание: Матрица открыта.
    Then "offer-snapshot-matrix-grid" is visible in "offer-versions"
    # что: Матрица в истории.
    # ожидание: Видна.
    And "estimate-work-row-{WORK_1}" is visible in "offer-versions"
    # что: Строка работы 1 в матрице T.
    # ожидание: Видна.
    And "offer-card-no-snapshot-{L}" is visible
    # что: Метка «нет снимка» у legacy-карточки.
    # ожидание: Видна.
    And no element matches "offer-view-snapshot-{L}"
    # что: Просмотр у legacy-карточки.
    # ожидание: Нет.
    And 1 elements match "offer-version-no-snapshot"
    # что: Метка «нет снимка» у legacy-строки истории LW.
    # ожидание: Одна.
    And no element matches "offer-version-view-{LW}"
    # что: Просмотр у legacy-строки истории.
    # ожидание: Нет.

  Scenario: TC-UI-SNAP-05 Refresh from the estimate for a DRAFT
    TC-UI-SNAP-05: новая работа сметы появляется в матрице DRAFT-оферты только после «Обновить снимок из сметы»; итог матрицы равен итогу карточки; у SENT-оферты кнопки обновления нет (2.1, A6).
    Given the admin prepares the offer "D"
    # что: DRAFT-оферта D через API.
    # ожидание: Создана.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Добавляем в смету работу 4 в комнате A.
    # ожидание: Строка сметы добавлена.
    And the live estimate net is remembered as "NET1"
    # что: Запоминаем новый итог сметы.
    # ожидание: NET_1 > NET_0.
    When the user opens the offers tab
    # что: Открываем вкладку оферт (матрица D открыта по умолчанию).
    # ожидание: Матрица видна.
    Then "estimate-work-row-{WORK_1}" is visible in "offer-snapshot-matrix"
    # что: Матрица загружена.
    # ожидание: Строка работы 1 видна.
    And no element matches "estimate-work-row-{WORK_4}"
    # что: Новая работа в матрице до обновления.
    # ожидание: Её нет.
    When the user clicks "offer-snapshot-matrix-close"
    # что: Закрываем матрицу.
    # ожидание: Закрыта.
    And the user clicks "offer-refresh-snapshot-{D}"
    # что: Нажимаем «Обновить снимок из сметы».
    # ожидание: Запрос refresh-snapshot.
    Then a toast containing "Снимок оферты обновлён" is shown
    # что: Уведомление об обновлении.
    # ожидание: Toast «Снимок оферты обновлён».
    When the user clicks "offer-view-snapshot-{D}"
    # что: Снова открываем матрицу D.
    # ожидание: Матрица видна.
    Then "estimate-group-header-{CAT_4}" is visible in "offer-snapshot-matrix"
    # что: Группа категории новой работы появилась в матрице.
    # ожидание: Заголовок группы виден.
    When the group "estimate-group-header-{CAT_4}" is expanded in "offer-snapshot-matrix"
    # что: Раскрываем группу новой работы (если свёрнута).
    # ожидание: Строки группы видны.
    Then "estimate-work-row-{WORK_4}" is visible in "offer-snapshot-matrix"
    # что: Новая работа после обновления.
    # ожидание: Видна.
    And "offer-snapshot-matrix-total-net" shows the amount "{NET1}"
    # что: Итог матрицы.
    # ожидание: NET_1.
    And "offer-total-net" shows the amount "{NET1}" in "offer-card-{D}"
    # что: Итог карточки.
    # ожидание: NET_1, как у матрицы.
    When the admin successfully calls POST "/api/offers/{D}/send"
    # что: Отправляем D через API.
    # ожидание: 200, SENT.
    And the user reloads the page
    # что: Перезагружаем вкладку.
    # ожидание: Статус D обновлён.
    Then "offer-status" contains the text "Отправлена" in "offer-card-{D}"
    # что: Статус карточки D.
    # ожидание: «Отправлена».
    And no element matches "offer-refresh-snapshot-{D}"
    # что: Кнопка обновления у SENT-оферты.
    # ожидание: Отсутствует.
