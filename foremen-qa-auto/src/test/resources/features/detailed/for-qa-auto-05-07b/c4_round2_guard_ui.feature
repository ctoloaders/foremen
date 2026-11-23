@detailed @FOR-QA-AUTO-05-07B @ui
Feature: FOR-05-07b C (UI). Round 2 unsaved estimate edits guard and the prepare dialog
  Round 2: защита от несохранённых правок сметы перед подготовкой, обновлением snapshot и restore; диалог подготовки с выбором новой или существующей оферты (тест-кейсы TC-R2-UI-GUARD-01..04, TC-R2-UI-REP-01..03).

  # Повторяемость: генератор run-id + teardown: свой проект FOR-05-07b {run-id} со сметой S3 и клиентом на сценарий;
  # оферты через API с именами Offer-{run-id}-X, R2-{run-id}-UI; несохранённая правка ставится в localStorage браузера
  # (foremen.estimateMatrix.{PID}) и уходит вместе с контекстом; teardown удаляет комнаты, проект и клиентов.
  # Порядок диалогов (согласовано): «Подготовить оферту» сначала открывает диалог выбора оферты, guard
  # показывается после подтверждения и до запроса prepare (тест-кейс описывает обратный порядок; snapshot в обоих
  # случаях строится из сохранённой сметы, 2.14).

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

  Scenario: TC-R2-UI-GUARD-01 Prepare with unsaved edits: save, discard or cancel
    TC-R2-UI-GUARD-01: при несохранённых правках подготовка спрашивает «Сохранить и продолжить» / «Отклонить изменения» / «Отмена»; сохранение идёт до prepare, отказ отбрасывает правку, отмена ничего не отправляет и сохраняет правку (2.14).
    When the user opens the estimate tab
    # что: Открываем вкладку сметы.
    # ожидание: Матрица видна.
    And the estimate has an unsaved quantity edit of work "WORK_1" in room "ROOM_A" set to "6"
    # что: Несохранённая правка: работа 1 в комнате A = 6.
    # ожидание: Правка в localStorage.
    And the user reloads the page
    # что: Перезагружаем вкладку.
    # ожидание: Правка в очереди изменений.
    And the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "estimate-prepare-offer"
    # что: Нажимаем «Подготовить оферту».
    # ожидание: Открыт диалог подготовки.
    And the user types "R2-{RUN}-save" into "prepare-offer-name-input"
    # что: Вводим имя новой оферты.
    # ожидание: Поле заполнено.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем.
    # ожидание: Открыт guard несохранённых правок.
    Then "unsaved-estimate-guard-body" is visible
    # что: Диалог защиты с текстом.
    # ожидание: Виден.
    And no POST request to "/api/offers/project/{PID}/prepare" was sent
    # что: Запрос prepare до решения по правкам.
    # ожидание: Не отправлен.
    When the user clicks "unsaved-estimate-guard-save"
    # что: Нажимаем «Сохранить и продолжить».
    # ожидание: Save сметы, затем prepare.
    Then 1 POST requests to "/api/estimates/project/{PID}/assignments" were sent
    # что: Сохранение сметы.
    # ожидание: Один запрос Save.
    And 1 POST requests to "/api/offers/project/{PID}/prepare" were sent
    # что: Подготовка оферты после сохранения.
    # ожидание: Один запрос prepare.
    And the URL path is "/projects/{PID}/pricing"
    # что: Переход на вкладку оферт.
    # ожидание: /projects/{PID}/pricing.
    And the estimate has no unsaved edits
    # что: Очередь несохранённых правок.
    # ожидание: Пуста.
    And the estimate quantity of work "WORK_1" in room "ROOM_A" is "6"
    # что: Сохранённая смета.
    # ожидание: 6.
    When the estimate has an unsaved quantity edit of work "WORK_1" in room "ROOM_A" set to "7"
    # что: Новая несохранённая правка: 7.
    # ожидание: Правка в localStorage.
    And the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "offer-prepare"
    # что: Кнопка подготовки на вкладке оферт.
    # ожидание: Диалог подготовки.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем новую оферту.
    # ожидание: Открыт guard.
    And the user clicks "unsaved-estimate-guard-discard"
    # что: Нажимаем «Отклонить изменения».
    # ожидание: Правка отброшена, prepare выполнен.
    Then no POST request to "/api/estimates/project/{PID}/assignments" was sent
    # что: Сохранение сметы.
    # ожидание: Не выполнялось.
    And 1 POST requests to "/api/offers/project/{PID}/prepare" were sent
    # что: Подготовка оферты.
    # ожидание: Один запрос.
    And the estimate has no unsaved edits
    # что: Очередь правок.
    # ожидание: Пуста.
    And the estimate quantity of work "WORK_1" in room "ROOM_A" is "6"
    # что: Смета после отказа.
    # ожидание: По-прежнему 6 (7 не сохранено).
    When the estimate has an unsaved quantity edit of work "WORK_1" in room "ROOM_A" set to "8"
    # что: Ещё одна несохранённая правка: 8.
    # ожидание: Правка в localStorage.
    And the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "offer-prepare"
    # что: Снова открываем подготовку.
    # ожидание: Диалог подготовки.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем.
    # ожидание: Открыт guard.
    And the user clicks "unsaved-estimate-guard-cancel"
    # что: Нажимаем «Отмена».
    # ожидание: Диалоги закрыты.
    Then "unsaved-estimate-guard" is not shown
    # что: Guard.
    # ожидание: Закрыт.
    And "prepare-offer-dialog" is not shown
    # что: Диалог подготовки.
    # ожидание: Закрыт.
    And no POST request to "/api/offers/project/{PID}/prepare" was sent
    # что: Запрос prepare.
    # ожидание: Нет.
    And no POST request to "/api/estimates/project/{PID}/assignments" was sent
    # что: Запрос Save.
    # ожидание: Нет.
    And the estimate still has the unsaved edit
    # что: Несохранённая правка.
    # ожидание: На месте.

  Scenario: TC-R2-UI-GUARD-02 A failed save does not continue
    TC-R2-UI-GUARD-02: если Save отвечает 500, показан toast ошибки, prepare не выполняется, правки остаются (2.14).
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Вкладка отображается.
    And the estimate has an unsaved quantity edit of work "WORK_1" in room "ROOM_A" set to "6"
    # что: Несохранённая правка сметы.
    # ожидание: Правка в localStorage.
    And the next POST request to "/api/estimates/project/{PID}/assignments" answers 500
    # что: Подменяем ответ Save на 500.
    # ожидание: Перехват активен.
    And the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "offer-prepare"
    # что: Кнопка подготовки.
    # ожидание: Диалог подготовки.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем.
    # ожидание: Открыт guard.
    And the user clicks "unsaved-estimate-guard-save"
    # что: Нажимаем «Сохранить и продолжить».
    # ожидание: Save отвечает 500.
    Then a toast containing "Не удалось сохранить смету" is shown
    # что: Уведомление об ошибке.
    # ожидание: Toast ошибки сохранения.
    And no POST request to "/api/offers/project/{PID}/prepare" was sent
    # что: Продолжение.
    # ожидание: prepare не выполнен.
    And "unsaved-estimate-guard" is visible
    # что: Guard после ошибки.
    # ожидание: Остаётся открытым.
    And the estimate still has the unsaved edit
    # что: Несохранённая правка.
    # ожидание: На месте.
    And the project has 0 offers
    # что: Оферты проекта в БД.
    # ожидание: Не созданы.

  Scenario: TC-R2-UI-GUARD-03 Refresh snapshot and restore go through the guard
    TC-R2-UI-GUARD-03: «Обновить снимок» и restore при несохранённых правках открывают guard; «Сохранить» → Save и refresh; «Отклонить» → диалог restore; «Отмена» — без запросов; без правок guard не показывается (2.14).
    Given the admin prepares the offer "D"
    # что: DRAFT-оферта D через API.
    # ожидание: Создана.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточка D.
    And the estimate has an unsaved quantity edit of work "WORK_1" in room "ROOM_A" set to "6"
    # что: Несохранённая правка: 6.
    # ожидание: Правка в localStorage.
    And the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "offer-refresh-snapshot-{D}"
    # что: Нажимаем «Обновить снимок из сметы».
    # ожидание: Открыт guard.
    Then "unsaved-estimate-guard" is visible
    # что: Guard.
    # ожидание: Виден.
    When the user clicks "unsaved-estimate-guard-save"
    # что: «Сохранить и продолжить».
    # ожидание: Save, затем refresh.
    Then 1 POST requests to "/api/estimates/project/{PID}/assignments" were sent
    # что: Сохранение сметы.
    # ожидание: Один запрос.
    And 1 POST requests to "/api/offers/{D}/refresh-snapshot" were sent
    # что: Обновление snapshot.
    # ожидание: Один запрос.
    And "estimate-cell-volume-{WORK_1}-{ROOM_A}" shows the amount "6" in "offer-snapshot-matrix"
    # что: Snapshot D после обновления.
    # ожидание: 6, как в сохранённой смете.
    When the estimate has an unsaved quantity edit of work "WORK_1" in room "ROOM_A" set to "8"
    # что: Новая несохранённая правка: 8.
    # ожидание: Правка в localStorage.
    And the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "offer-refresh-snapshot-{D}"
    # что: Снова «Обновить снимок».
    # ожидание: Guard.
    And the user clicks "unsaved-estimate-guard-cancel"
    # что: «Отмена».
    # ожидание: Запросов нет.
    Then no POST request to "/api/offers/{D}/refresh-snapshot" was sent
    # что: Обновление.
    # ожидание: Не запущено.
    And the estimate still has the unsaved edit
    # что: Правка.
    # ожидание: На месте.
    When the user clicks "offer-restore-{D}"
    # что: Нажимаем restore при несохранённых правках (кнопка активна).
    # ожидание: Открыт guard.
    Then "unsaved-estimate-guard" is visible
    # что: Guard.
    # ожидание: Виден, restore-диалога ещё нет.
    And "offer-restore-dialog-{D}" is not shown
    # что: Диалог restore.
    # ожидание: Не открыт.
    When the user clicks "unsaved-estimate-guard-discard"
    # что: «Отклонить изменения».
    # ожидание: Открыт диалог restore.
    Then "offer-restore-dialog-{D}" is visible
    # что: Диалог restore.
    # ожидание: Открыт.
    When the user clicks "offer-restore-confirm-{D}"
    # что: Подтверждаем restore.
    # ожидание: 200.
    Then 1 POST requests to "/api/offers/{D}/restore" were sent
    # что: Запрос restore.
    # ожидание: Один.
    And a toast containing "Смета восстановлена из оферты" is shown
    # что: Toast успеха.
    # ожидание: Показан.
    And the estimate has no unsaved edits
    # что: Очередь правок.
    # ожидание: Пуста.
    When the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "offer-refresh-snapshot-{D}"
    # что: «Обновить снимок» без несохранённых правок.
    # ожидание: Сразу запрос.
    Then 1 POST requests to "/api/offers/{D}/refresh-snapshot" were sent
    # что: Запрос refresh.
    # ожидание: Один, без guard.
    And "unsaved-estimate-guard" is not shown
    # что: Guard.
    # ожидание: Не показан.

  Scenario: TC-R2-UI-GUARD-04 The snapshot is built from the saved estimate
    TC-R2-UI-GUARD-04: после «Сохранить и продолжить» snapshot новой оферты содержит сохранённое значение и его итог равен итогу сметы; после «Отклонить» отброшенная правка в snapshot не попадает (2.14, 2.15).
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Вкладка отображается.
    And the estimate has an unsaved quantity edit of work "WORK_1" in room "ROOM_A" set to "6"
    # что: Несохранённая правка: 6.
    # ожидание: Правка в localStorage.
    And the user clicks "offer-prepare"
    # что: Кнопка подготовки.
    # ожидание: Диалог.
    And the user types "R2-{RUN}-saved" into "prepare-offer-name-input"
    # что: Имя новой оферты.
    # ожидание: Поле заполнено.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем.
    # ожидание: Guard.
    And the user clicks "unsaved-estimate-guard-save"
    # что: «Сохранить и продолжить».
    # ожидание: Save и prepare.
    And the offer named "R2-{RUN}-saved" is remembered as "S"
    # что: Находим новую оферту.
    # ожидание: Найдена.
    And the live estimate net is remembered as "NET1"
    # что: Итог сохранённой сметы.
    # ожидание: NET_1.
    And the admin calls GET "/api/offers/{S}/snapshot"
    # что: Snapshot новой оферты.
    # ожидание: 200.
    Then the snapshot quantity of work "WORK_1" in room "ROOM_A" is "6"
    # что: Количество в snapshot.
    # ожидание: 6 (попало благодаря Save).
    And the response field "totalNet" equals "{NET1}"
    # что: Итог snapshot.
    # ожидание: Равен итогу сохранённой сметы.
    When the estimate has an unsaved quantity edit of work "WORK_1" in room "ROOM_A" set to "9"
    # что: Новая несохранённая правка: 9.
    # ожидание: Правка в localStorage.
    And the user clicks "offer-prepare"
    # что: Кнопка подготовки.
    # ожидание: Диалог.
    And the user types "R2-{RUN}-discarded" into "prepare-offer-name-input"
    # что: Имя второй оферты.
    # ожидание: Поле заполнено.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем.
    # ожидание: Guard.
    And the user clicks "unsaved-estimate-guard-discard"
    # что: «Отклонить изменения».
    # ожидание: Prepare без Save.
    And the offer named "R2-{RUN}-discarded" is remembered as "X"
    # что: Находим вторую оферту.
    # ожидание: Найдена.
    And the admin calls GET "/api/offers/{X}/snapshot"
    # что: Snapshot второй оферты.
    # ожидание: 200.
    Then the snapshot quantity of work "WORK_1" in room "ROOM_A" is "6"
    # что: Количество в snapshot.
    # ожидание: 6 — отброшенная 9 не попала.
