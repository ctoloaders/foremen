@detailed @FOR-QA-AUTO-05-07B @ui
Feature: FOR-05-07b B4. Offer PDF download (UI)
  Скачивание PDF оферты: исполнитель, ошибка загрузки, клиент и legacy-оферта без snapshot (тест-кейсы TC-UI-PDF-01..03).

  # Повторяемость: генератор run-id + teardown: свой проект FOR-05-07b {run-id} со сметой S3 на сценарий; оферты через API
  # с именами Offer-{run-id}-X; teardown удаляет комнаты, проект и клиентов. Загруженный файл живёт во временной папке
  # браузера и удаляется вместе с контекстом.

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

  Scenario: TC-UI-PDF-01 The executor downloads the offer PDF
    TC-UI-PDF-01: кнопка PDF выполняет GET /api/offers/{id}/pdf и скачивает файл %PDF; повторное нажатие тоже работает, ошибок в консоли нет (2.4).
    Given the admin prepares the offer "P"
    # что: Оферта Offer-{run-id}-P через API (snapshot есть).
    # ожидание: Оферта создана.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточка P видна.
    And the request log is cleared
    # что: Начинаем запись запросов.
    # ожидание: Журнал пуст.
    And the user downloads the PDF with "offer-pdf-{P}"
    # что: Нажимаем «Скачать PDF» в карточке P.
    # ожидание: Началась загрузка файла.
    Then the downloaded file is a PDF
    # что: Проверяем файл.
    # ожидание: Начинается с %PDF.
    And 1 GET requests to "/api/offers/{P}/pdf" were sent
    # что: Запросы PDF.
    # ожидание: Один GET /api/offers/{P}/pdf.
    When the user downloads the PDF with "offer-pdf-{P}"
    # что: Скачиваем повторно.
    # ожидание: Вторая загрузка.
    Then the downloaded file is a PDF
    # что: Проверяем второй файл.
    # ожидание: %PDF.
    And 2 GET requests to "/api/offers/{P}/pdf" were sent
    # что: Запросы PDF.
    # ожидание: Два.
    And the browser console has no errors
    # что: Консоль браузера (без сетевых сообщений).
    # ожидание: Ошибок нет.

  Scenario: TC-UI-PDF-02 A failed PDF shows a toast
    TC-UI-PDF-02: при ответе 502 на запрос PDF показан toast offers.pdf.error, кнопка снова активна и следующая загрузка работает (2.4).
    Given the admin prepares the offer "P"
    # что: Оферта P через API.
    # ожидание: Создана.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточка P видна.
    And the next GET request to "/api/offers/{P}/pdf" answers 502
    # что: Подменяем ответ PDF на 502 (перехват в браузере).
    # ожидание: Перехват активен.
    And the user clicks "offer-pdf-{P}"
    # что: Нажимаем «Скачать PDF».
    # ожидание: Ответ 502.
    Then a toast containing "Не удалось скачать PDF" is shown
    # что: Уведомление об ошибке.
    # ожидание: Локализованный toast offers.pdf.error.
    And "offer-pdf-{P}" is enabled
    # что: Кнопка после ошибки.
    # ожидание: Снова активна.
    When the user downloads the PDF with "offer-pdf-{P}"
    # что: Повторяем загрузку без перехвата.
    # ожидание: Файл скачан.
    Then the downloaded file is a PDF
    # что: Проверяем файл.
    # ожидание: %PDF.

  Scenario: TC-UI-PDF-03 PDF for the client and for a legacy offer
    TC-UI-PDF-03: клиент скачивает PDF отправленной оферты; DRAFT-оферты у клиента нет; у legacy-оферты без snapshot кнопки PDF нет (3.2, 3.3).
    Given the admin prepares and sends the offer "P"
    # что: Оферта P через API: prepare и send.
    # ожидание: P SENT.
    And the admin prepares the offer "D"
    # что: DRAFT-оферта D через API.
    # ожидание: D DRAFT.
    And a legacy offer "L" without a snapshot in status "SENT" exists
    # что: Legacy SENT-оферта без snapshot.
    # ожидание: Оферта L есть.
    When the user opens the offers tab
    # что: Вкладка оферт исполнителя.
    # ожидание: Карточка L видна.
    Then "offer-card-no-snapshot-{L}" is visible
    # что: Метка legacy-карточки.
    # ожидание: «Нет снимка».
    And no element matches "offer-pdf-{L}"
    # что: Кнопка PDF у legacy-оферты.
    # ожидание: Отсутствует.
    Given the client is signed in to the browser
    # что: Входим клиентом по OTP.
    # ожидание: Оболочка приложения открыта.
    When the user opens the offers tab
    # что: Вкладка оферт клиента.
    # ожидание: Карточка P видна.
    And the user downloads the PDF with "offer-pdf-{P}"
    # что: Клиент скачивает PDF отправленной оферты.
    # ожидание: Файл скачан.
    Then the downloaded file is a PDF
    # что: Проверяем файл.
    # ожидание: %PDF.
    And no element matches "offer-card-{D}"
    # что: DRAFT-оферта у клиента.
    # ожидание: Карточки нет.
    And no element matches "offer-pdf-{L}"
    # что: Кнопка PDF legacy-оферты у клиента.
    # ожидание: Отсутствует.
