@detailed @FOR-QA-AUTO-05-07B @ui
Feature: FOR-05-07b B1. Offer name dialog on Przygotuj ofertę (UI)
  Диалог имени оферты при нажатии «Przygotuj ofertę» на вкладке сметы: создание с именем и без имени, отмена, ограничение 255 символов, ошибка prepare (тест-кейсы TC-UI-NAME-01..05).

  # Повторяемость: генератор run-id (имена оферт UI-{run-id}-n) + teardown: каждый сценарий создаёт свой
  # проект FOR-05-07b {run-id} со сметой S3 через API, исполнитель — ADMIN, язык UI — RU; teardown удаляет проект
  # с офертами. Сетевые запросы браузера записываются, чтобы проверить «запроса не было».

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Бэкенд и фронтенд отвечают.
    And an offers project with a client and rooms A, B and C exists
    # что: Клиент, проект и комнаты через API.
    # ожидание: Проект создан.
    And the offers estimate has two works in rooms and one work in no room
    # что: Смета S3 через API.
    # ожидание: Смета готова.
    And the admin is signed in to the browser
    # что: Входим в браузер исполнителем (ADMIN), язык RU.
    # ожидание: Оболочка приложения открыта.
    And the user opens the estimate tab
    # что: Открываем /projects/{PID}/estimate.
    # ожидание: Матрица сметы отображается.

  Scenario: TC-UI-NAME-01 Confirming with a name creates the offer with that name
    TC-UI-NAME-01: кнопка открывает диалог без запроса prepare; после подтверждения — один запрос с именем, переход на вкладку оферт и карточка с этим именем (2.7).
    When the request log is cleared
    # что: Начинаем запись сетевых запросов с чистого листа.
    # ожидание: Журнал пуст.
    And the user clicks "estimate-prepare-offer"
    # что: Нажимаем «Przygotuj ofertę».
    # ожидание: Открывается диалог.
    Then "prepare-offer-dialog" is visible
    # что: Проверяем диалог.
    # ожидание: prepare-offer-dialog виден.
    And no POST request to "/api/offers/project/{PID}/prepare" was sent
    # что: Запрос prepare до подтверждения не отправляется.
    # ожидание: Запросов нет.
    And the URL path is "/projects/{PID}/estimate"
    # что: URL не изменился.
    # ожидание: /projects/{PID}/estimate.
    When the user types "UI-{RUN}-1" into "prepare-offer-name-input"
    # что: Вводим имя UI-{run-id}-1.
    # ожидание: Поле заполнено.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем.
    # ожидание: Запрос prepare отправлен.
    Then 1 POST requests to "/api/offers/project/{PID}/prepare" were sent
    # что: Считаем запросы prepare.
    # ожидание: Ровно один.
    And the last POST request to "/api/offers/project/{PID}/prepare" had the field "name" equal to "UI-{RUN}-1"
    # что: Тело запроса.
    # ожидание: name = UI-{run-id}-1.
    And the URL path is "/projects/{PID}/pricing"
    # что: Переход после успешного ответа.
    # ожидание: /projects/{PID}/pricing.
    When the offer named "UI-{RUN}-1" is remembered as "A"
    # что: Находим созданную оферту по имени через API.
    # ожидание: Оферта найдена.
    Then "offer-name" contains the text "UI-{RUN}-1" in "offer-card-{A}"
    # что: Карточка на вкладке оферт.
    # ожидание: Имя UI-{run-id}-1.

  Scenario: TC-UI-NAME-02 An empty name is allowed and gives Offer N
    TC-UI-NAME-02: подтверждение без имени создаёт оферту «Offer N» (2.7, 3.4).
    When the user clicks "estimate-prepare-offer"
    # что: Открываем диалог.
    # ожидание: Диалог открыт.
    Then the value of "prepare-offer-name-input" has 0 characters
    # что: Поле имени пустое.
    # ожидание: Пусто.
    When the user clicks "prepare-offer-confirm"
    # что: Подтверждаем без имени.
    # ожидание: Запрос prepare отправлен.
    Then the URL path is "/projects/{PID}/pricing"
    # что: Переход на вкладку оферт.
    # ожидание: /projects/{PID}/pricing.
    When the newest offer is remembered as "A"
    # что: Находим созданную оферту.
    # ожидание: Найдена.
    Then "offer-name" contains the text "Offer " in "offer-card-{A}"
    # что: Имя карточки по шаблону.
    # ожидание: Offer N.

  Scenario: TC-UI-NAME-03 Cancelling creates nothing
    TC-UI-NAME-03: отмена кнопкой и Esc закрывает диалог без запроса и без навигации (2.7).
    When the request log is cleared
    # что: Запись запросов с чистого листа.
    # ожидание: Журнал пуст.
    And the user clicks "estimate-prepare-offer"
    # что: Открываем диалог.
    # ожидание: Диалог открыт.
    And the user types "UI-{RUN}-cancel" into "prepare-offer-name-input"
    # что: Вводим имя.
    # ожидание: Поле заполнено.
    And the user clicks "prepare-offer-cancel"
    # что: Нажимаем «Отмена».
    # ожидание: Диалог закрыт.
    Then "prepare-offer-dialog" is not shown
    # что: Проверяем диалог.
    # ожидание: Закрыт.
    And no POST request to "/api/offers/project/{PID}/prepare" was sent
    # что: Запроса prepare не было.
    # ожидание: 0 запросов.
    And the URL path is "/projects/{PID}/estimate"
    # что: URL прежний.
    # ожидание: /projects/{PID}/estimate.
    And the project has 0 offers
    # что: Проверка через БД: оферта UI-{run-id}-cancel не создана.
    # ожидание: 0 оферт.
    When the user clicks "estimate-prepare-offer"
    # что: Снова открываем диалог.
    # ожидание: Диалог открыт.
    And the user presses "Escape"
    # что: Закрываем по Esc.
    # ожидание: Диалог закрыт.
    Then "prepare-offer-dialog" is not shown
    # что: Проверяем диалог.
    # ожидание: Закрыт.
    And no POST request to "/api/offers/project/{PID}/prepare" was sent
    # что: Запроса prepare не было.
    # ожидание: 0 запросов.

  Scenario: TC-UI-NAME-04 The 255 character limit
    TC-UI-NAME-04: поле имени принимает не более 255 символов; с 255 символами оферта создаётся (2.7, 3.4).
    When the user clicks "estimate-prepare-offer"
    # что: Открываем диалог.
    # ожидание: Диалог открыт.
    Then the attribute "maxlength" of "prepare-offer-name-input" is "255"
    # что: Ограничение поля.
    # ожидание: maxLength = 255.
    When the user types "{NAME_256}" into "prepare-offer-name-input"
    # что: Вставляем 256 символов.
    # ожидание: Поле не принимает больше 255.
    Then the value of "prepare-offer-name-input" has 255 characters
    # что: Длина значения.
    # ожидание: 255.
    When the user clicks "prepare-offer-confirm"
    # что: Подтверждаем.
    # ожидание: Оферта создана.
    Then the URL path is "/projects/{PID}/pricing"
    # что: Переход на вкладку оферт.
    # ожидание: /projects/{PID}/pricing.
    And the project has 1 offers
    # что: Оферта с именем из 255 символов создана.
    # ожидание: 1 оферта.

  Scenario: TC-UI-NAME-05 A failing prepare does not navigate
    TC-UI-NAME-05: при ошибке prepare (перехват ответа 500) показан toast, URL прежний, оферта не создана (2.7).
    Given the next POST request to "/api/offers/project/{PID}/prepare" answers 500
    # что: Подменяем ответ prepare на 500 (перехват в браузере).
    # ожидание: Перехват активен.
    When the user clicks "estimate-prepare-offer"
    # что: Открываем диалог.
    # ожидание: Диалог открыт.
    And the user types "UI-{RUN}-err" into "prepare-offer-name-input"
    # что: Вводим имя.
    # ожидание: Поле заполнено.
    And the user clicks "prepare-offer-confirm"
    # что: Подтверждаем.
    # ожидание: Ответ 500.
    Then "estimate-prepare-offer-toast" is visible
    # что: Проверяем уведомление об ошибке.
    # ожидание: Toast показан.
    And the URL path is "/projects/{PID}/estimate"
    # что: Навигации нет.
    # ожидание: /projects/{PID}/estimate.
    And the project has 0 offers
    # что: Оферта не создана.
    # ожидание: 0 оферт.
