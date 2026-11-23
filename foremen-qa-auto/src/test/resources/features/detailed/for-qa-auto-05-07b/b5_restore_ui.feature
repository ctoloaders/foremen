@detailed @FOR-QA-AUTO-05-07B @ui
Feature: FOR-05-07b B5. Restoring the estimate from an offer (UI)
  Восстановление сметы из snapshot оферты: подтверждение, отмена, блокировка сметы, пропущенные комнаты, видимость действия, обновление кэша (тест-кейсы TC-UI-RST-01..06).

  # Повторяемость: генератор run-id + teardown: свой проект FOR-05-07b {run-id} со сметой S3 на сценарий; оферты через API
  # с именами Offer-{run-id}-X; правки сметы делаются в этом же проекте; teardown удаляет комнаты, проект (смета и оферты
  # каскадом) и клиентов. Сценарий с блокировкой сметы ставит статус PRICED только своей сметы.

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

  Scenario: TC-UI-RST-01 Restore with confirmation
    TC-UI-RST-01: диалог восстановления открывается без запроса; после подтверждения один POST restore, toast успеха; смета снова равна snapshot (работы 4 нет, работа 2 на месте, количества S3, итог NET_0); аудит RESTORE_ESTIMATE (2.6, A1).
    Given the admin prepares the offer "R"
    # что: Оферта Offer-{run-id}-R через API со snapshot сметы S3.
    # ожидание: Оферта создана.
    And a fourth work is added to the estimate in room "ROOM_A" with quantity "2"
    # что: Правка сметы: добавляем работу 4.
    # ожидание: Строка добавлена.
    And the admin successfully calls DELETE "/api/estimate-lines/{LINE_2}"
    # что: Правка сметы: удаляем строку работы 2.
    # ожидание: 200.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточка R видна.
    And the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "offer-restore-{R}"
    # что: Нажимаем «Восстановить смету из оферты».
    # ожидание: Открыт диалог подтверждения.
    Then "offer-restore-dialog-{R}" is visible
    # что: Диалог восстановления.
    # ожидание: Виден.
    And "offer-restore-body-{R}" is visible
    # что: Текст о замене сметы и пересчёте материалов.
    # ожидание: Виден.
    And "offer-restore-confirm-{R}" is visible
    # что: Кнопка подтверждения.
    # ожидание: Видна.
    And "offer-restore-cancel-{R}" is visible
    # что: Кнопка отмены.
    # ожидание: Видна.
    And no POST request to "/api/offers/{R}/restore" was sent
    # что: Запрос restore до подтверждения.
    # ожидание: Не отправлен.
    When the user clicks "offer-restore-confirm-{R}"
    # что: Подтверждаем восстановление.
    # ожидание: Запрос restore.
    Then 1 POST requests to "/api/offers/{R}/restore" were sent
    # что: Считаем запросы restore.
    # ожидание: Ровно один.
    And a toast containing "Смета восстановлена из оферты" is shown
    # что: Уведомление об успехе.
    # ожидание: Toast успеха.
    And the estimate has 0 lines of work "WORK_4"
    # что: Проверяем смету через БД: работа 4.
    # ожидание: Строки нет.
    And the estimate has 1 lines of work "WORK_2"
    # что: Работа 2.
    # ожидание: Строка восстановлена.
    And the estimate quantity of work "WORK_1" in room "ROOM_A" is "2"
    # что: Количество работы 1 в комнате A.
    # ожидание: 2, как в S3.
    And the offer "R" has 1 audit rows "RESTORE_ESTIMATE"
    # что: Аудит восстановления.
    # ожидание: Одна запись RESTORE_ESTIMATE.
    When the admin calls GET "/api/estimates/project/{PID}"
    # что: Читаем итоги сметы через API.
    # ожидание: 200.
    Then the response field "totalNet" equals "{NET0}"
    # что: Итог netto сметы.
    # ожидание: NET_0.
    When the user opens the estimate tab
    # что: Открываем вкладку сметы.
    # ожидание: Матрица сметы отображается.
    Then "estimate-group-header-{CAT_2}" is visible
    # что: Группа работы 2 на вкладке сметы.
    # ожидание: Есть (работа 2 на месте).
    And no element matches "estimate-work-row-{WORK_4}"
    # что: Строка работы 4 на вкладке сметы (группа категории остаётся: в неё входят и другие работы сметы).
    # ожидание: Отсутствует.

  Scenario: TC-UI-RST-02 Cancelling the restore changes nothing (Round 2 expectation)
    TC-UI-RST-02 (УСТАРЕЛ, Round 2 D3): блока влияния на оферты нет; проверяется только отмена — диалог закрыт, запросов нет, оферты и смета не изменились.
    Given the admin prepares and sends the offer "R"
    # что: SENT-оферта R через API.
    # ожидание: R SENT.
    And the admin successfully calls DELETE "/api/estimate-lines/{LINE_2}"
    # что: Правка сметы: удаляем строку работы 2.
    # ожидание: 200.
    And the state of the offer "R" is remembered
    # что: Запоминаем статус, версию, итоги и snapshot R.
    # ожидание: Состояние сохранено.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточка R видна.
    And the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "offer-restore-{R}"
    # что: Открываем диалог восстановления.
    # ожидание: Диалог открыт.
    Then no element matches "offer-restore-impact-"
    # что: Блок влияния на оферты.
    # ожидание: Отсутствует.
    When the user clicks "offer-restore-cancel-{R}"
    # что: Нажимаем «Отмена».
    # ожидание: Диалог закрыт.
    Then "offer-restore-dialog-{R}" is not shown
    # что: Диалог восстановления.
    # ожидание: Закрыт.
    And no POST request to "/api/offers/{R}/restore" was sent
    # что: Запрос restore.
    # ожидание: Не отправлен.
    And the state of the offer "R" is unchanged
    # что: Сравниваем состояние R.
    # ожидание: Без изменений.
    And the estimate has 0 lines of work "WORK_2"
    # что: Смета не восстановлена.
    # ожидание: Строки работы 2 по-прежнему нет.

  Scenario: TC-UI-RST-03 Restore is unavailable while the estimate is locked
    TC-UI-RST-03 (пункты 1 и 3; пункт 2 изменён в Round 2 D6 и покрыт TC-R2-UI-GUARD-03): при заблокированной смете кнопка неактивна с подсказкой, диалог не открывается; прямой вызов API даёт 409 error.estimate.locked (2.6, A1).
    Given the admin prepares the offer "R"
    # что: Оферта R через API.
    # ожидание: Создана.
    And the estimate is locked
    # что: Переводим смету проекта в статус PRICED (не DRAFT).
    # ожидание: Смета заблокирована.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточка R видна.
    Then "offer-restore-{R}" is disabled
    # что: Кнопка восстановления.
    # ожидание: Неактивна.
    And "offer-restore-hint-{R}" is present
    # что: Подсказка о блокировке.
    # ожидание: «Смета заблокирована, восстановление невозможно».
    And "offer-restore-dialog-{R}" is not shown
    # что: Диалог восстановления.
    # ожидание: Не открыт.
    When the admin calls POST "/api/offers/{R}/restore"
    # что: Вызываем restore через API в обход UI.
    # ожидание: Отказ.
    Then the response status is 409
    # что: Статус ответа.
    # ожидание: 409.
    And the response error code is "error.estimate.locked"
    # что: Код ошибки.
    # ожидание: error.estimate.locked.
    And the offer "R" has 0 audit rows "RESTORE_ESTIMATE"
    # что: Аудит.
    # ожидание: Записи RESTORE_ESTIMATE нет.

  Scenario: TC-UI-RST-04 Skipped rooms are listed in a toast
    TC-UI-RST-04: комната, удалённая после создания оферты, перечислена в toast по label; остальная смета восстановлена (A5).
    Given the admin prepares the offer "R"
    # что: Оферта R через API со snapshot по комнатам A и B.
    # ожидание: Создана.
    And the admin successfully calls DELETE "/api/rooms/{ROOM_B}"
    # что: Удаляем комнату B.
    # ожидание: 200.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточка R видна.
    And the user clicks "offer-restore-{R}"
    # что: Открываем диалог восстановления.
    # ожидание: Диалог открыт.
    And the user clicks "offer-restore-confirm-{R}"
    # что: Подтверждаем.
    # ожидание: Ответ 200 со skippedRooms.
    Then a toast containing "Смета восстановлена из оферты" is shown
    # что: Toast успеха.
    # ожидание: Показан.
    And a toast containing "{ROOM_B_LABEL}" is shown
    # что: Toast с пропущенными комнатами.
    # ожидание: Перечислена Room B {run-id}.
    And the estimate quantity of work "WORK_1" in room "ROOM_A" is "2"
    # что: Остальная смета.
    # ожидание: Работа 1 в комнате A = 2.

  Scenario: TC-UI-RST-05 Who sees the restore action
    TC-UI-RST-05: у исполнителя действие есть у каждой карточки и строки истории со snapshot; у legacy-оферты его нет; у CLIENT его нет (3.3, 3.1).
    Given the admin prepares and sends the offer "R"
    # что: SENT-оферта R через API.
    # ожидание: R SENT.
    And the admin prepares and sends the offer "T"
    # что: Оферта T через API.
    # ожидание: T SENT.
    And the admin successfully calls POST "/api/offers/{T}/withdraw"
    # что: Отзываем T (строка истории).
    # ожидание: 200.
    And a legacy offer "L" without a snapshot in status "DRAFT" exists
    # что: Legacy DRAFT-оферта без snapshot.
    # ожидание: Оферта L есть.
    When the user opens the offers tab
    # что: Вкладка оферт исполнителя.
    # ожидание: Карточки R, L и история с T.
    Then "offer-restore-{R}" is visible in "offer-card-{R}"
    # что: Действие в карточке со snapshot.
    # ожидание: Есть.
    And "offer-restore-{T}" is visible in "offer-versions"
    # что: Действие в строке истории.
    # ожидание: Есть.
    And no element matches "offer-restore-{L}"
    # что: Действие у legacy-оферты.
    # ожидание: Отсутствует.
    Given the client is signed in to the browser
    # что: Входим клиентом по OTP.
    # ожидание: Оболочка приложения открыта.
    When the user opens the offers tab
    # что: Вкладка оферт клиента.
    # ожидание: Карточка R видна.
    Then "offer-card-{R}" is visible
    # что: Карточка R у клиента.
    # ожидание: Видна.
    And no element matches "offer-restore-"
    # что: Действие восстановления у клиента.
    # ожидание: Нигде нет.

  Scenario: TC-UI-RST-06 The page refreshes its data after a restore
    TC-UI-RST-06: после restore без перезагрузки страницы повторно запрошены список оферт, оферта и матрица сметы; итоги карточки прежние (2.6).
    Given the admin prepares the offer "R"
    # что: Оферта R через API.
    # ожидание: Создана.
    And the admin successfully calls DELETE "/api/estimate-lines/{LINE_2}"
    # что: Правка сметы: удаляем строку работы 2.
    # ожидание: 200.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточка R видна.
    And the user clicks "offer-restore-{R}"
    # что: Открываем диалог восстановления.
    # ожидание: Диалог открыт.
    And the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "offer-restore-confirm-{R}"
    # что: Подтверждаем.
    # ожидание: restore 200.
    Then a toast containing "Смета восстановлена из оферты" is shown
    # что: Toast успеха.
    # ожидание: Показан.
    And at least one GET request to "/api/offers/project/{PID}/list" was sent
    # что: Повторный запрос списка оферт.
    # ожидание: Есть.
    And at least one GET request to "/api/offers/{R}" was sent
    # что: Повторный запрос карточки оферты.
    # ожидание: Есть.
    And at least one GET request to "/api/estimates/project/{PID}/matrix" was sent
    # что: Повторный запрос матрицы сметы.
    # ожидание: Есть.
    And the URL path is "/projects/{PID}/pricing"
    # что: Страница не перезагружалась и не уходила со вкладки.
    # ожидание: /projects/{PID}/pricing.
    And "offer-total-net" shows the amount "{NET0}" in "offer-card-{R}"
    # что: Итог карточки.
    # ожидание: NET_0 (итоги оферты из её snapshot).
    And the estimate has 1 lines of work "WORK_2"
    # что: Смета после restore.
    # ожидание: Работа 2 восстановлена.
