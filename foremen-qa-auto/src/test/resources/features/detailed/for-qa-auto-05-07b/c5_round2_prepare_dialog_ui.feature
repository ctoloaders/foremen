@detailed @FOR-QA-AUTO-05-07B @ui
Feature: FOR-05-07b C (UI). Round 2 prepare dialog: new or existing offer
  Диалог подготовки оферты: обновление существующей оферты, новая оферта и отмена, те же правила на вкладке оферт (тест-кейсы TC-R2-UI-REP-01..03).

  # Повторяемость: генератор run-id + teardown: свой проект FOR-05-07b {run-id} со сметой S3 и клиентом на сценарий; оферты через API
  # с именами Offer-{run-id}-X и R2-{run-id}-UI; teardown удаляет комнаты, проект (оферты каскадом, включая APPROVED) и клиентов.
  # Уведомление клиенту об обновлении (шаг 7 REP-01) проверяется в API-части (TC-R2-REP-03).

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

  Scenario: TC-R2-UI-REP-01 Updating an existing offer from the dialog
    TC-R2-UI-REP-01: в диалоге видны все оферты проекта и «новая», APPROVED и WITHDRAWN недоступны; у отправленной оферты есть подсказка; подтверждение шлёт prepare с offerId без name; статус и неактивность сохранены, версия +1 (2.13).
    Given the admin prepares the offer "A"
    # что: DRAFT-оферта A.
    # ожидание: A DRAFT.
    And the admin prepares and sends the offer "B"
    # что: SENT-оферта B.
    # ожидание: B SENT.
    And the admin successfully calls POST "/api/offers/{B}/deactivate"
    # что: Делаем B неактивной.
    # ожидание: 200.
    And the admin prepares and sends the offer "C"
    # что: SENT-оферта C.
    # ожидание: C SENT.
    And the client successfully calls POST "/api/offers/{C}/approve"
    # что: Клиент согласует C.
    # ожидание: C APPROVED.
    And the admin prepares and sends the offer "D"
    # что: Оферта D.
    # ожидание: D SENT.
    And the admin successfully calls POST "/api/offers/{D}/withdraw"
    # что: Отзываем D.
    # ожидание: D WITHDRAWN.
    And the state of the offer "A" is remembered
    # что: Запоминаем состояние A для проверки, что она не изменится.
    # ожидание: Сохранено.
    When the user opens the estimate tab
    # что: Открываем вкладку сметы.
    # ожидание: Матрица видна.
    And the user clicks "estimate-prepare-offer"
    # что: Нажимаем «Подготовить оферту».
    # ожидание: Открыт диалог подготовки.
    Then "prepare-offer-options" is visible
    # что: Список вариантов.
    # ожидание: Виден.
    And "prepare-offer-option-{A}" is visible
    # что: Вариант A.
    # ожидание: Есть.
    And "prepare-offer-option-{B}" is visible
    # что: Вариант B.
    # ожидание: Есть.
    And "prepare-offer-option-{C}" is visible
    # что: Вариант C (APPROVED).
    # ожидание: Показан.
    And "prepare-offer-option-{D}" is visible
    # что: Вариант D (WITHDRAWN).
    # ожидание: Показан.
    And "prepare-offer-option-new" is visible
    # что: Вариант «новая оферта».
    # ожидание: Есть.
    And "prepare-offer-inactive-{B}" is visible
    # что: Бейдж неактивности у B.
    # ожидание: Виден.
    And "prepare-offer-option-{C}" is disabled
    # что: Терминальная APPROVED.
    # ожидание: Не выбирается.
    And "prepare-offer-option-{D}" is disabled
    # что: Терминальная WITHDRAWN.
    # ожидание: Не выбирается.
    When the user clicks "prepare-offer-option-{A}"
    # что: Выбираем DRAFT A.
    # ожидание: Подсказки нет.
    Then no element matches "prepare-offer-update-hint"
    # что: Подсказка об обновлении клиенту.
    # ожидание: У DRAFT её нет.
    When the user clicks "prepare-offer-option-{B}"
    # что: Выбираем отправленную B.
    # ожидание: Показана подсказка.
    Then "prepare-offer-update-hint" is visible
    # что: Подсказка: клиент получит новую версию.
    # ожидание: Видна.
    And no element matches "prepare-offer-name-input"
    # что: Поле имени.
    # ожидание: При обновлении не показывается.
    When the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем обновление B.
    # ожидание: Запрос prepare.
    Then 1 POST requests to "/api/offers/project/{PID}/prepare" were sent
    # что: Запросы prepare.
    # ожидание: Один.
    And the last POST request to "/api/offers/project/{PID}/prepare" had the field "offerId" equal to "{B}"
    # что: Тело запроса.
    # ожидание: offerId = B.
    And the URL path is "/projects/{PID}/pricing"
    # что: Переход после успеха.
    # ожидание: /projects/{PID}/pricing.
    And "offer-status" contains the text "Отправлена" in "offer-card-{B}"
    # что: Статус B.
    # ожидание: «Отправлена» сохранён.
    And "offer-inactive-badge" is visible in "offer-card-{B}"
    # что: Флаг неактивности B.
    # ожидание: Сохранён.
    And "offer-revision" contains the text "2" in "offer-card-{B}"
    # что: Версия B.
    # ожидание: Версия 2.
    And the state of the offer "A" is unchanged
    # что: Другая оферта A.
    # ожидание: Без изменений.
    And the project has 4 offers
    # что: Число оферт проекта.
    # ожидание: Четыре, новая не создана.

  Scenario: TC-R2-UI-REP-02 A new offer and cancelling
    TC-R2-UI-REP-02: выбор «новая» с именем создаёт DRAFT-карточку, остальные не меняются; пустое имя даёт «Offer N»; отмена и Esc без запросов; поле имени до 255 символов (2.13).
    Given the admin prepares and sends the offer "B"
    # что: SENT-оферта B.
    # ожидание: B SENT.
    And the state of the offer "B" is remembered
    # что: Запоминаем состояние B.
    # ожидание: Сохранено.
    When the user opens the estimate tab
    # что: Открываем вкладку сметы.
    # ожидание: Матрица видна.
    And the user clicks "estimate-prepare-offer"
    # что: Открываем диалог.
    # ожидание: Диалог открыт.
    And the user clicks "prepare-offer-option-new"
    # что: Выбираем «новая оферта».
    # ожидание: Видно поле имени.
    Then the attribute "maxlength" of "prepare-offer-name-input" is "255"
    # что: Ограничение поля имени.
    # ожидание: maxLength = 255.
    When the user types "{NAME_256}" into "prepare-offer-name-input"
    # что: Вставляем 256 символов.
    # ожидание: Поле принимает 255.
    Then the value of "prepare-offer-name-input" has 255 characters
    # что: Длина значения.
    # ожидание: 255.
    When the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user clicks "prepare-offer-cancel"
    # что: Нажимаем «Отмена».
    # ожидание: Диалог закрыт.
    Then "prepare-offer-dialog" is not shown
    # что: Диалог.
    # ожидание: Закрыт.
    And no POST request to "/api/offers/project/{PID}/prepare" was sent
    # что: Запрос prepare.
    # ожидание: Нет.
    When the user clicks "estimate-prepare-offer"
    # что: Снова открываем диалог.
    # ожидание: Диалог открыт.
    And the user presses "Escape"
    # что: Закрываем по Esc.
    # ожидание: Диалог закрыт.
    Then "prepare-offer-dialog" is not shown
    # что: Диалог.
    # ожидание: Закрыт.
    And no POST request to "/api/offers/project/{PID}/prepare" was sent
    # что: Запрос prepare.
    # ожидание: Нет.
    And the state of the offer "B" is unchanged
    # что: Оферта B.
    # ожидание: Без изменений.
    When the user clicks "estimate-prepare-offer"
    # что: Открываем диалог для создания.
    # ожидание: Диалог открыт.
    And the user clicks "prepare-offer-option-new"
    # что: Выбираем «новая».
    # ожидание: Поле имени.
    And the user types "R2-{RUN}-UI" into "prepare-offer-name-input"
    # что: Имя R2-{run-id}-UI.
    # ожидание: Поле заполнено.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем.
    # ожидание: Новая DRAFT-оферта.
    Then the last POST request to "/api/offers/project/{PID}/prepare" had the field "name" equal to "R2-{RUN}-UI"
    # что: Тело запроса.
    # ожидание: name = R2-{run-id}-UI.
    When the offer named "R2-{RUN}-UI" is remembered as "N"
    # что: Находим новую оферту.
    # ожидание: Найдена.
    Then "offer-status" contains the text "Черновик" in "offer-card-{N}"
    # что: Статус новой карточки.
    # ожидание: «Черновик».
    And "offer-status" contains the text "Отправлена" in "offer-card-{B}"
    # что: Статус B.
    # ожидание: Не изменился.
    And the state of the offer "B" is unchanged
    # что: Состояние B после создания новой.
    # ожидание: Без изменений.
    When the user clicks "offer-prepare"
    # что: Снова подготовка, на этот раз без имени.
    # ожидание: Диалог открыт.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем без имени.
    # ожидание: Оферта с именем по умолчанию.
    And the newest offer is remembered as "M"
    # что: Находим созданную оферту.
    # ожидание: Найдена.
    Then "offer-name" matches the text pattern "Offer [0-9]+" in "offer-card-{M}"
    # что: Имя карточки.
    # ожидание: «Offer N».

  Scenario: TC-R2-UI-REP-03 The offers tab uses the same dialog
    TC-R2-UI-REP-03: на вкладке оферт блок подготовки — кнопка, открывающая тот же диалог с вариантами; у клиента кнопки нет (2.13, 3.4).
    Given the admin prepares and sends the offer "B"
    # что: SENT-оферта B.
    # ожидание: B SENT.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточка B.
    Then no element matches "offer-name-input"
    # что: Инлайн-поле имени на вкладке.
    # ожидание: Его нет.
    When the user clicks "offer-prepare"
    # что: Нажимаем кнопку подготовки.
    # ожидание: Открыт диалог с вариантами.
    Then "prepare-offer-option-{B}" is visible
    # что: Вариант B.
    # ожидание: Есть.
    And "prepare-offer-option-new" is visible
    # что: Вариант «новая».
    # ожидание: Есть.
    When the user clicks "prepare-offer-option-{B}"
    # что: Выбираем B.
    # ожидание: Подсказка.
    Then "prepare-offer-update-hint" is visible
    # что: Подсказка об обновлении.
    # ожидание: Видна.
    When the user clicks "prepare-offer-cancel"
    # что: Отмена.
    # ожидание: Диалог закрыт.
    Given the client is signed in to the browser
    # что: Входим клиентом.
    # ожидание: Оболочка открыта.
    When the user opens the offers tab
    # что: Вкладка оферт клиента.
    # ожидание: Карточка B.
    Then no element matches "offer-prepare"
    # что: Кнопка подготовки у клиента.
    # ожидание: Нет.
    And no element matches "offer-prepare-block"
    # что: Блок подготовки у клиента.
    # ожидание: Нет.
