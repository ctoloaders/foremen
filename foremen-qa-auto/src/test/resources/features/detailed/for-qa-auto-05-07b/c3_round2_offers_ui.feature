@detailed @FOR-QA-AUTO-05-07B @ui
Feature: FOR-05-07b C (UI). Round 2 offers tab: snapshot refresh, inactive offers, approve, estimate warnings, sending several offers
  Round 2 на вкладке оферт: обновление открытого snapshot после отправки, неактивные оферты у исполнителя и клиента, approve одной оферты, отсутствие предупреждений об отзыве, отправка нескольких оферт (тест-кейсы TC-R2-SNAP-01, TC-R2-UI-INACT-01/02, TC-R2-UI-APPR-01, TC-R2-UI-EST-01, TC-R2-UI-SEND-01/02).

  # Повторяемость: генератор run-id + teardown: свой проект FOR-05-07b {run-id} со сметой S3 и клиентом
  # test+client-{run-id}; оферты через API с именами Offer-{run-id}-X; teardown удаляет комнаты, проект (оферты каскадом,
  # включая APPROVED и неактивные) и клиентов. Несохранённые правки сметы живут в localStorage браузера и уходят вместе
  # с контекстом.

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

  Scenario: TC-R2-SNAP-01 The open snapshot matrix shows the new snapshot after send without a reload
    TC-R2-SNAP-01: снимок DRAFT-оферты не следует за сохранённой сметой сам по себе; после «Отправить» открытая матрица без F5 показывает новый snapshot, итог матрицы равен итогу карточки, в сети повторный GET snapshot (2.9).
    Given the admin prepares the offer "A"
    # что: DRAFT-оферта A через API со сметой S3.
    # ожидание: Snapshot A: работа 1 в A = 2.
    And the admin successfully calls PUT "/api/estimate-line-room-qty/{QTY_1A}" with body '{"lineId":{LINE_1},"roomId":{ROOM_A},"quantity":6}'
    # что: Сохраняем в смете количество работы 1 в комнате A = 6.
    # ожидание: 200.
    And the live estimate net is remembered as "NET1"
    # что: Запоминаем новый итог сметы.
    # ожидание: NET_1.
    When the user opens the offers tab
    # что: Открываем вкладку оферт (матрица A открыта по умолчанию).
    # ожидание: Матрица видна.
    Then "estimate-cell-volume-{WORK_1}-{ROOM_A}" shows the amount "2" in "offer-snapshot-matrix"
    # что: Ячейка работа 1 × комната A до отправки.
    # ожидание: 2 (автообновления нет, A6).
    When the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "offer-send" in "offer-card-{A}"
    # что: Нажимаем «Отправить» в карточке A.
    # ожидание: POST /api/offers/{A}/send.
    Then 1 POST requests to "/api/offers/{A}/send" were sent
    # что: Запрос отправки.
    # ожидание: Один.
    And "estimate-cell-volume-{WORK_1}-{ROOM_A}" shows the amount "6" in "offer-snapshot-matrix"
    # что: Та же ячейка без перезагрузки страницы.
    # ожидание: 6 (новый snapshot).
    And "offer-snapshot-matrix-total-net" shows the amount "{NET1}"
    # что: Итог матрицы.
    # ожидание: NET_1.
    And "offer-total-net" shows the amount "{NET1}" in "offer-card-{A}"
    # что: Итог карточки.
    # ожидание: NET_1, равен матрице.
    And at least one GET request to "/api/offers/{A}/snapshot" was sent
    # что: Инвалидация кэша snapshot.
    # ожидание: Повторный GET snapshot после send.
    When the admin calls GET "/api/offers/{A}/snapshot"
    # что: Snapshot A через API.
    # ожидание: 200.
    Then the response field "totalNet" equals "{NET1}"
    # что: Итог snapshot.
    # ожидание: NET_1, как на экране.

  Scenario: TC-R2-UI-INACT-01 The executor toggles the inactive flag and sees the badge
    TC-R2-UI-INACT-01: переключатель «Сделать неактивным» отправляет deactivate/activate, бейдж появляется и исчезает, статус не меняется; состояние сохраняется после F5; у терминальных оферт переключателя нет (2.10).
    Given the admin prepares the offer "A"
    # что: DRAFT-оферта A через API.
    # ожидание: A DRAFT.
    And the admin prepares and sends the offer "B"
    # что: SENT-оферта B через API.
    # ожидание: B SENT.
    And the admin prepares and sends the offer "T"
    # что: Оферта T через API.
    # ожидание: T SENT.
    And the admin successfully calls POST "/api/offers/{T}/withdraw"
    # что: Отзываем T (история).
    # ожидание: 200.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточки A и B, история с T.
    And the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "offer-toggle-inactive-{A}"
    # что: Нажимаем «Сделать неактивным» у A.
    # ожидание: POST deactivate.
    Then 1 POST requests to "/api/offers/{A}/deactivate" were sent
    # что: Запрос deactivate.
    # ожидание: Один.
    And "offer-inactive-badge" is visible in "offer-card-{A}"
    # что: Бейдж неактивности A.
    # ожидание: «Неактивное».
    And "offer-status" contains the text "Черновик" in "offer-card-{A}"
    # что: Статус A.
    # ожидание: По-прежнему «Черновик».
    When the user clicks "offer-toggle-inactive-{A}"
    # что: Нажимаем переключатель повторно.
    # ожидание: POST activate.
    Then 1 POST requests to "/api/offers/{A}/activate" were sent
    # что: Запрос activate.
    # ожидание: Один.
    And "offer-inactive-badge" is not shown in "offer-card-{A}"
    # что: Бейдж A.
    # ожидание: Исчез.
    When the user clicks "offer-toggle-inactive-{B}"
    # что: Делаем неактивной отправленную B.
    # ожидание: POST deactivate.
    Then "offer-inactive-badge" is visible in "offer-card-{B}"
    # что: Бейдж B.
    # ожидание: Виден.
    And "offer-status" contains the text "Отправлена" in "offer-card-{B}"
    # что: Статус B.
    # ожидание: «Отправлена» не изменился.
    When the user reloads the page
    # что: Перезагружаем страницу (F5).
    # ожидание: Данные загружены заново.
    Then "offer-inactive-badge" is visible in "offer-card-{B}"
    # что: Бейдж B после F5.
    # ожидание: Сохранился.
    And "offer-inactive-badge" is not shown in "offer-card-{A}"
    # что: A после F5.
    # ожидание: Активна.
    And no element matches "offer-toggle-inactive-{T}"
    # что: Переключатель у терминальной T.
    # ожидание: Отсутствует.

  Scenario: TC-R2-UI-INACT-02 The client sees the badge but cannot decide on an inactive offer
    TC-R2-UI-INACT-02: у клиента на неактивной оферте есть бейдж, просмотр, PDF и выбор, но нет approve/reject и переключателя; после снятия флага исполнителем решения снова доступны (2.10).
    Given the admin prepares and sends the offer "B"
    # что: SENT-оферта B через API.
    # ожидание: B SENT.
    And the admin successfully calls POST "/api/offers/{B}/deactivate"
    # что: Делаем B неактивной через API.
    # ожидание: 200.
    And the client is signed in to the browser
    # что: Входим клиентом по OTP.
    # ожидание: Оболочка приложения открыта.
    When the user opens the offers tab
    # что: Вкладка оферт клиента.
    # ожидание: Карточка B видна.
    Then "offer-inactive-badge" is visible in "offer-card-{B}"
    # что: Бейдж неактивности у клиента.
    # ожидание: Виден.
    And "offer-approve" is not shown in "offer-card-{B}"
    # что: Кнопка «Согласовать».
    # ожидание: Нет.
    And "offer-reject" is not shown in "offer-card-{B}"
    # что: Кнопка «Отклонить».
    # ожидание: Нет.
    And "offer-view-snapshot-{B}" is visible
    # что: Просмотр матрицы.
    # ожидание: Доступен.
    And "offer-pdf-{B}" is visible
    # что: Кнопка PDF.
    # ожидание: Доступна.
    And "offer-select-{B}" is visible
    # что: Чекбокс выбора.
    # ожидание: Доступен.
    And no element matches "offer-toggle-inactive-{B}"
    # что: Переключатель неактивности у клиента.
    # ожидание: Нет.
    When the admin successfully calls POST "/api/offers/{B}/activate"
    # что: Исполнитель снимает флаг через API.
    # ожидание: 200.
    And the user reloads the page
    # что: Клиент перезагружает страницу.
    # ожидание: Данные обновлены.
    Then "offer-approve" is visible in "offer-card-{B}"
    # что: Кнопка «Согласовать» после снятия флага.
    # ожидание: Появилась.
    And "offer-reject" is visible in "offer-card-{B}"
    # что: Кнопка «Отклонить».
    # ожидание: Появилась.

  Scenario: TC-R2-UI-APPR-01 Approving one offer leaves the others awaiting
    TC-R2-UI-APPR-01: клиент согласует A; B и C остаются в «ожидающих» со статусом «Отправлена»; попытка согласовать B показывает читаемую ошибку error.offer.already.approved; состояние сохраняется после F5 (2.10).
    Given the admin prepares and sends the offer "A"
    # что: SENT-оферта A.
    # ожидание: A SENT.
    And the admin prepares and sends the offer "B"
    # что: SENT-оферта B.
    # ожидание: B SENT.
    And the admin prepares and sends the offer "C"
    # что: SENT-оферта C.
    # ожидание: C SENT.
    And the client is signed in to the browser
    # что: Входим клиентом по OTP.
    # ожидание: Оболочка приложения открыта.
    When the user opens the offers tab
    # что: Вкладка оферт клиента.
    # ожидание: Три карточки.
    And the user clicks "offer-approve" in "offer-card-{A}"
    # что: Согласуем A.
    # ожидание: A APPROVED.
    Then the history row named "Offer-{RUN}-A" contains the text "Согласована"
    # что: A в истории.
    # ожидание: «Согласована».
    And "offer-status" contains the text "Отправлена" in "offer-card-{B}"
    # что: B.
    # ожидание: В ожидающих, «Отправлена».
    And "offer-status" contains the text "Отправлена" in "offer-card-{C}"
    # что: C.
    # ожидание: В ожидающих, «Отправлена».
    When the user clicks "offer-approve" in "offer-card-{B}" and looks for a toast
    # что: Пытаемся согласовать вторую оферту B.
    # ожидание: Отказ 409.
    Then a toast is shown
    # что: Сообщение об ошибке.
    # ожидание: Читаемый toast error.offer.already.approved.
    And "offer-status" contains the text "Отправлена" in "offer-card-{B}"
    # что: B после отказа.
    # ожидание: Остаётся «Отправлена», страница работает.
    And the project has exactly one approved offer
    # что: Проверка через БД.
    # ожидание: Одна APPROVED.
    When the user reloads the page
    # что: Перезагружаем страницу (F5).
    # ожидание: Данные загружены заново.
    Then "offer-card-{B}" is visible in "offer-awaiting-list"
    # что: B после F5.
    # ожидание: В ожидающих.
    And "offer-card-{C}" is visible in "offer-awaiting-list"
    # что: C после F5.
    # ожидание: В ожидающих.
    And the history row named "Offer-{RUN}-A" contains the text "Согласована"
    # что: A после F5.
    # ожидание: «Согласована».

  Scenario: TC-R2-UI-EST-01 No offer withdrawal warnings remain
    TC-R2-UI-EST-01: сохранение сметы, диалог корректировки трудозатрат и диалог restore не предупреждают об отзыве оферт; после сохранения и restore статусы и итоги SENT-оферт прежние (2.11).
    Given the admin prepares and sends the offer "A"
    # что: SENT-оферта A.
    # ожидание: A SENT.
    And the state of the offer "A" is remembered
    # что: Запоминаем статус, версию, итоги и snapshot A.
    # ожидание: Сохранено.
    When the user opens the estimate tab
    # что: Открываем вкладку сметы.
    # ожидание: Матрица видна.
    And the estimate has an unsaved quantity edit of work "WORK_1" in room "ROOM_A" set to "6"
    # что: Несохранённая правка количества работы 1 в комнате A.
    # ожидание: Правка в localStorage.
    And the user reloads the page
    # что: Перезагружаем вкладку, чтобы смета подняла правку.
    # ожидание: Правка в очереди изменений.
    And the user clicks "estimate-save"
    # что: Нажимаем «Сохранить».
    # ожидание: Смета сохранена.
    Then no dialog mentions withdrawing offers
    # что: Диалоги после сохранения.
    # ожидание: Нет упоминаний отзыва оферт.
    And the estimate quantity of work "WORK_1" in room "ROOM_A" is "6"
    # что: Сохранённая правка.
    # ожидание: 6.
    When the user clicks "estimate-labour-adjust-project"
    # что: Открываем корректировку цены работ (диалог пересчёта).
    # ожидание: Диалог открыт.
    Then no dialog mentions withdrawing offers
    # что: Диалог пересчёта.
    # ожидание: Без предупреждения об офертах.
    When the user presses "Escape"
    # что: Закрываем диалог.
    # ожидание: Закрыт.
    And the user opens the offers tab
    # что: Вкладка оферт.
    # ожидание: Карточка A видна.
    And the user clicks "offer-restore-{A}"
    # что: Открываем диалог restore.
    # ожидание: Диалог открыт.
    Then no element matches "offer-restore-impact-{A}"
    # что: Блок влияния на оферты.
    # ожидание: Нет.
    And no dialog mentions withdrawing offers
    # что: Текст диалога restore.
    # ожидание: Только замена сметы и пересчёт материалов.
    When the user clicks "offer-restore-confirm-{A}"
    # что: Подтверждаем restore.
    # ожидание: 200.
    Then a toast containing "Смета восстановлена из оферты" is shown
    # что: Toast успеха.
    # ожидание: Показан.
    And the state of the offer "A" is unchanged
    # что: Статус, версия, итоги и snapshot A после save и restore.
    # ожидание: Без изменений.
    And "offer-status" contains the text "Отправлена" in "offer-card-{A}"
    # что: Статус на экране.
    # ожидание: «Отправлена».

  Scenario: TC-R2-UI-SEND-01 Sending the ticked offers
    TC-R2-UI-SEND-01: «Отправить отмеченные (2)» отправляет A и B двумя запросами; C остаётся черновиком; клиент видит A и B (2.16).
    Given the admin prepares the offer "A"
    # что: DRAFT A.
    # ожидание: Создана.
    And the admin prepares the offer "B"
    # что: DRAFT B.
    # ожидание: Создана.
    And the admin prepares the offer "C"
    # что: DRAFT C.
    # ожидание: Создана.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Три DRAFT-карточки.
    And the user clicks "offer-select-{A}"
    # что: Отмечаем A.
    # ожидание: Отмечена.
    And the user clicks "offer-select-{B}"
    # что: Отмечаем B.
    # ожидание: Отмечена.
    Then "offer-send-selected" contains the text "(2)"
    # что: Кнопка отправки отмеченных.
    # ожидание: «Отправить отмеченные (2)».
    When the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "offer-send-selected"
    # что: Отправляем отмеченные.
    # ожидание: Два запроса send.
    Then 1 POST requests to "/api/offers/{A}/send" were sent
    # что: Отправка A.
    # ожидание: Один запрос.
    And 1 POST requests to "/api/offers/{B}/send" were sent
    # что: Отправка B.
    # ожидание: Один запрос.
    And no POST request to "/api/offers/{C}/send" was sent
    # что: C не отмечена.
    # ожидание: Запроса нет.
    And a toast containing "Отправлено оферт: 2" is shown
    # что: Итоговое уведомление.
    # ожидание: «Отправлено оферт: 2».
    And "offer-status" contains the text "Отправлена" in "offer-card-{A}"
    # что: Статус A.
    # ожидание: «Отправлена».
    And "offer-status" contains the text "Отправлена" in "offer-card-{B}"
    # что: Статус B.
    # ожидание: «Отправлена».
    And "offer-status" contains the text "Черновик" in "offer-card-{C}"
    # что: Статус C.
    # ожидание: «Черновик».
    When the client calls GET "/api/offers/project/{PID}/list"
    # что: Список оферт клиента через API.
    # ожидание: 200.
    Then the response array "" has an item with "id" equal to "{A}"
    # что: A у клиента.
    # ожидание: Видна.
    And the response array "" has an item with "id" equal to "{B}"
    # что: B у клиента.
    # ожидание: Видна.
    And the response array "" has no item with "id" equal to "{C}"
    # что: C у клиента.
    # ожидание: Не видна (DRAFT).

  Scenario: TC-R2-UI-SEND-02 Mixed selection and partial failures
    TC-R2-UI-SEND-02: кнопка считает только DRAFT; без DRAFT её нет; при отказе одной отправки — один toast с перечнем, остальные отправлены; неактивную DRAFT можно отправить; у клиента кнопки нет (2.16, 3.1).
    Given the admin prepares the offer "A"
    # что: DRAFT A.
    # ожидание: Создана.
    And the admin prepares and sends the offer "B"
    # что: SENT B.
    # ожидание: B SENT.
    And the admin prepares the offer "C"
    # что: DRAFT C.
    # ожидание: Создана.
    And the admin successfully calls POST "/api/offers/{C}/deactivate"
    # что: Делаем C неактивной.
    # ожидание: 200.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточки A, B, C.
    And the user clicks "offer-select-{A}"
    # что: Отмечаем DRAFT A.
    # ожидание: Отмечена.
    And the user clicks "offer-select-{B}"
    # что: Отмечаем SENT B.
    # ожидание: Отмечена.
    Then "offer-send-selected" contains the text "(1)"
    # что: Счётчик кнопки.
    # ожидание: (1) — только DRAFT.
    When the user clicks "offer-select-{A}"
    # что: Снимаем отметку с A (остаётся только SENT B).
    # ожидание: Отметка снята.
    Then no element matches "offer-send-selected"
    # что: Кнопка без DRAFT в выборе.
    # ожидание: Скрыта.
    When the user clicks "offer-select-{B}"
    # что: Снимаем отметку с B.
    # ожидание: Выбор пуст.
    And the user clicks "offer-select-{A}"
    # что: Отмечаем A.
    # ожидание: Отмечена.
    And the user clicks "offer-select-{C}"
    # что: Отмечаем неактивную DRAFT C.
    # ожидание: Отмечена.
    And the next POST request to "/api/offers/{A}/send" answers 409
    # что: Подменяем ответ send для A на 409.
    # ожидание: Перехват активен.
    And the user clicks "offer-send-selected"
    # что: Отправляем отмеченные.
    # ожидание: A отказ, C отправлена.
    Then a toast containing "Не удалось отправить оферты" is shown
    # что: Один итоговый toast.
    # ожидание: С перечнем ошибок.
    And a toast containing "Offer-{RUN}-A" is shown
    # что: Перечень ошибок.
    # ожидание: Названа A.
    And "offer-status" contains the text "Отправлена" in "offer-card-{C}"
    # что: Неактивная C отправлена.
    # ожидание: «Отправлена».
    And "offer-inactive-badge" is visible in "offer-card-{C}"
    # что: Флаг C после отправки.
    # ожидание: Сохранён.
    And "offer-status" contains the text "Черновик" in "offer-card-{A}"
    # что: A после отказа.
    # ожидание: «Черновик».
    Given the client is signed in to the browser
    # что: Входим клиентом.
    # ожидание: Оболочка открыта.
    When the user opens the offers tab
    # что: Вкладка оферт клиента.
    # ожидание: Карточки B и C.
    And the user clicks "offer-select-{B}"
    # что: Клиент отмечает B.
    # ожидание: Отмечена.
    Then no element matches "offer-send-selected"
    # что: Кнопка отправки у клиента.
    # ожидание: Отсутствует.
