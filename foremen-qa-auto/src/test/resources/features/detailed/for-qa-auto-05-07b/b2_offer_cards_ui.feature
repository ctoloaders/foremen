@detailed @FOR-QA-AUTO-05-07B @ui
Feature: FOR-05-07b B2. Offer cards and several awaiting offers (UI)
  Карточки оферт на вкладке «Оферты»: несколько ожидающих оферт, действия строго по карточке, правила имени (тест-кейсы TC-UI-MULTI-01..04).

  # Повторяемость: генератор run-id (имена UI-{run-id}-X и Offer-{run-id}-X) + teardown: каждый сценарий создаёт свой
  # проект FOR-05-07b {run-id} со сметой S3; teardown удаляет комнаты, проект с офертами и клиентов.
  # Round 2: блок подготовки на вкладке оферт — кнопка offer-prepare, открывающая диалог prepare-offer-dialog
  # (inline-поля offer-name-input больше нет, TC-R2-UI-REP-03).

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

  Scenario: TC-UI-MULTI-01 Preparing several offers from the offers tab
    TC-UI-MULTI-01: блок подготовки виден всегда; две DRAFT-оферты создаются подряд; отправка одной не меняет другую; у каждой карточки свои итоги и действия (2.5).
    When the user opens the offers tab
    # что: Открываем /projects/{PID}/pricing.
    # ожидание: Вкладка оферт отображается.
    Then "offer-prepare-block" is visible
    # что: Проверяем блок подготовки.
    # ожидание: offer-prepare-block виден.
    When the user clicks "offer-prepare"
    # что: Нажимаем «Подготовить оферту».
    # ожидание: Открыт диалог подготовки.
    And the user types "UI-{RUN}-A" into "prepare-offer-name-input"
    # что: Вводим имя UI-{run-id}-A.
    # ожидание: Поле заполнено.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем.
    # ожидание: Оферта A создана.
    And the offer named "UI-{RUN}-A" is remembered as "A"
    # что: Находим оферту A через API.
    # ожидание: Найдена.
    Then "offer-name" contains the text "UI-{RUN}-A" in "offer-card-{A}"
    # что: Карточка A в списке ожидающих.
    # ожидание: Имя UI-{run-id}-A.
    And "offer-status" contains the text "Черновик" in "offer-card-{A}"
    # что: Статус карточки A.
    # ожидание: «Черновик».
    And "offer-prepare-block" is visible
    # что: Блок подготовки при наличии ожидающей оферты.
    # ожидание: Блок по-прежнему виден.
    When the user clicks "offer-prepare"
    # что: Снова открываем диалог.
    # ожидание: Диалог открыт.
    And the user types "UI-{RUN}-B" into "prepare-offer-name-input"
    # что: Вводим имя UI-{run-id}-B.
    # ожидание: Поле заполнено.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем.
    # ожидание: Оферта B создана, ошибки «уже есть активная оферта» нет.
    And the offer named "UI-{RUN}-B" is remembered as "B"
    # что: Находим оферту B через API.
    # ожидание: Найдена.
    Then "offer-card-{A}" is visible in "offer-awaiting-list"
    # что: Карточка A в списке ожидающих.
    # ожидание: Видна.
    And "offer-card-{B}" is visible in "offer-awaiting-list"
    # что: Карточка B в списке ожидающих.
    # ожидание: Видна.
    And 2 elements match "offer-card-actions-"
    # что: Считаем карточки.
    # ожидание: Две карточки.
    When the user clicks "offer-send" in "offer-card-{A}"
    # что: Отправляем A кнопкой внутри её карточки.
    # ожидание: Запрос send для A.
    Then "offer-status" contains the text "Отправлена" in "offer-card-{A}"
    # что: Статус A.
    # ожидание: «Отправлена».
    And "offer-status" contains the text "Черновик" in "offer-card-{B}"
    # что: Статус B.
    # ожидание: По-прежнему «Черновик».
    When the user clicks "offer-send" in "offer-card-{B}"
    # что: Отправляем B.
    # ожидание: Запрос send для B.
    Then "offer-status" contains the text "Отправлена" in "offer-card-{B}"
    # что: Статус B.
    # ожидание: «Отправлена».
    And "offer-card-{A}" is visible in "offer-awaiting-list"
    # что: A остаётся в ожидающих.
    # ожидание: Видна.
    And "offer-card-{B}" is visible in "offer-awaiting-list"
    # что: B остаётся в ожидающих.
    # ожидание: Видна.
    And "offer-total-net" shows the amount "{NET0}" in "offer-card-{A}"
    # что: Итог netto карточки A.
    # ожидание: NET_0.
    And "offer-total-net" shows the amount "{NET0}" in "offer-card-{B}"
    # что: Итог netto карточки B.
    # ожидание: NET_0.
    And "offer-withdraw" is visible in "offer-card-actions-{A}"
    # что: Действия карточки A.
    # ожидание: Свой блок действий с «Отозвать».
    And "offer-withdraw" is visible in "offer-card-actions-{B}"
    # что: Действия карточки B.
    # ожидание: Свой блок действий с «Отозвать».

  Scenario: TC-UI-MULTI-02 Approving one offer keeps the others awaiting (Round 2 expectation)
    TC-UI-MULTI-02 (УСТАРЕЛ, Round 2 D2): после approve одной оферты вторая остаётся в «ожидающих» со статусом «Отправлена»; состояние сохраняется после F5. Актуальный кейс — TC-R2-UI-APPR-01.
    Given the admin prepares and sends the offer "A"
    # что: Оферта Offer-{run-id}-A через API: prepare и send.
    # ожидание: A SENT.
    And the admin prepares and sends the offer "B"
    # что: Оферта Offer-{run-id}-B через API: prepare и send.
    # ожидание: B SENT.
    And the client is signed in to the browser
    # что: Входим клиентом проекта по OTP.
    # ожидание: Оболочка приложения открыта.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Две карточки в offer-awaiting-list.
    Then "offer-card-{A}" is visible in "offer-awaiting-list"
    # что: Карточка A.
    # ожидание: Видна.
    And "offer-card-{B}" is visible in "offer-awaiting-list"
    # что: Карточка B.
    # ожидание: Видна.
    When the user clicks "offer-approve" in "offer-card-{A}"
    # что: Клиент согласует A.
    # ожидание: Запрос approve для A.
    Then the history row named "Offer-{RUN}-A" contains the text "Согласована"
    # что: A ушла в историю.
    # ожидание: Статус «Согласована».
    And "offer-status" contains the text "Отправлена" in "offer-card-{B}"
    # что: B не отозвана.
    # ожидание: B в ожидающих, «Отправлена».
    When the user reloads the page
    # что: Перезагружаем страницу (F5).
    # ожидание: Страница загружена заново.
    Then "offer-card-{B}" is visible in "offer-awaiting-list"
    # что: Состояние после F5.
    # ожидание: B по-прежнему в ожидающих.
    And the history row named "Offer-{RUN}-A" contains the text "Согласована"
    # что: A после F5.
    # ожидание: «Согласована».

  Scenario: TC-UI-MULTI-03 Actions apply only to their own card
    TC-UI-MULTI-03: отзыв B кнопкой внутри её карточки не трогает A; у клиента нет send/withdraw, есть approve/reject у отправленных; DRAFT клиенту не виден (3.1).
    Given the admin prepares and sends the offer "A"
    # что: Оферта A через API (SENT).
    # ожидание: A SENT.
    And the admin prepares and sends the offer "B"
    # что: Оферта B через API (SENT).
    # ожидание: B SENT.
    And the admin prepares the offer "C"
    # что: DRAFT-оферта C через API.
    # ожидание: C DRAFT.
    When the user opens the offers tab
    # что: Открываем вкладку оферт исполнителем.
    # ожидание: Три карточки.
    And the user clicks "offer-withdraw" in "offer-card-{B}"
    # что: Отзываем B кнопкой внутри карточки B.
    # ожидание: Запрос withdraw для B.
    Then the history row named "Offer-{RUN}-B" contains the text "Отозвана"
    # что: B ушла в историю.
    # ожидание: «Отозвана».
    And "offer-status" contains the text "Отправлена" in "offer-card-{A}"
    # что: A не затронута.
    # ожидание: «Отправлена».
    Given the client is signed in to the browser
    # что: Входим клиентом по OTP.
    # ожидание: Оболочка приложения открыта.
    When the user opens the offers tab
    # что: Вкладка оферт клиента.
    # ожидание: Видна только отправленная A.
    Then "offer-approve" is visible in "offer-card-{A}"
    # что: Решения клиента у отправленной оферты.
    # ожидание: «Согласовать» есть.
    And "offer-reject" is visible in "offer-card-{A}"
    # что: Кнопка отклонения.
    # ожидание: «Отклонить» есть.
    And "offer-send" is not shown in "offer-card-{A}"
    # что: Отправка недоступна клиенту.
    # ожидание: offer-send нет.
    And "offer-withdraw" is not shown in "offer-card-{A}"
    # что: Отзыв недоступен клиенту.
    # ожидание: offer-withdraw нет.
    And 1 elements match "offer-card-actions-"
    # что: Считаем карточки клиента.
    # ожидание: Одна (DRAFT C не показана, B в истории).
    And no element matches "offer-card-{C}"
    # что: DRAFT-оферта C.
    # ожидание: У клиента её нет.

  Scenario: TC-UI-MULTI-04 Name rules on the offers tab
    TC-UI-MULTI-04: имя из пробелов даёт «Offer N»; поле имени ограничено 255 символами (3.4).
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Вкладка отображается.
    And the user clicks "offer-prepare"
    # что: Открываем диалог подготовки.
    # ожидание: Диалог открыт.
    And the user types "   " into "prepare-offer-name-input"
    # что: Вводим только пробелы.
    # ожидание: Поле заполнено пробелами.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем.
    # ожидание: Оферта создана с именем по умолчанию.
    And the newest offer is remembered as "A"
    # что: Находим созданную оферту.
    # ожидание: Найдена.
    Then "offer-name" matches the text pattern "Offer [0-9]+"
    # что: Имя карточки.
    # ожидание: «Offer N».
    When the user clicks "offer-prepare"
    # что: Снова открываем диалог.
    # ожидание: Диалог открыт.
    Then the attribute "maxlength" of "prepare-offer-name-input" is "255"
    # что: Ограничение поля имени.
    # ожидание: maxLength = 255.
    When the user types "{NAME_256}" into "prepare-offer-name-input"
    # что: Вставляем 256 символов.
    # ожидание: Поле принимает только 255.
    Then the value of "prepare-offer-name-input" has 255 characters
    # что: Длина значения.
    # ожидание: 255.
    When the user clicks "prepare-offer-cancel"
    # что: Закрываем диалог без подтверждения.
    # ожидание: Диалог закрыт.
    Then the project has 1 offers
    # что: Проверяем через БД, что лишняя оферта не создана.
    # ожидание: Одна оферта.
