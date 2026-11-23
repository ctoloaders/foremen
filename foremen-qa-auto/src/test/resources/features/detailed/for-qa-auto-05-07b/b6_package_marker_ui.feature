@detailed @FOR-QA-AUTO-05-07B @ui
Feature: FOR-05-07b B6. Applied package marker (UI)
  Маркер применённого пакета: в карточке, матрице snapshot, истории и сравнении; отсутствие маркера без пакета; локализация названия (тест-кейсы TC-UI-PKG-01..03).

  # Повторяемость: генератор run-id + teardown: свой проект FOR-05-07b {run-id} со сметой S3 на сценарий (для PKG-02 — второй
  # проект без пакета с префиксом X_); пакет применяется только к смете своего проекта; teardown удаляет комнаты, проекты
  # и клиентов. Эталонный проект 1531 только читается.

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

  Scenario: TC-UI-PKG-01 The marker is shown when a package is applied
    TC-UI-PKG-01: при применённом пакете маркер с его названием виден в карточке, в шапке матрицы snapshot, в строке истории и в обеих колонках сравнения; PDF содержит название пакета (2.8).
    Given the first offer package is applied to the estimate
    # что: Применяем к смете первый пакет оферт (через БД, как S4).
    # ожидание: Пакет PKG применён.
    And the admin prepares the offer "P"
    # что: Оферта Offer-{run-id}-P через API.
    # ожидание: Snapshot с маркером пакета.
    And the admin prepares and sends the offer "H"
    # что: Оферта Offer-{run-id}-H через API: prepare и send.
    # ожидание: H SENT.
    And the admin successfully calls POST "/api/offers/{H}/withdraw"
    # что: Отзываем H, чтобы она попала в историю.
    # ожидание: 200.
    When the user opens the offers tab
    # что: Открываем вкладку оферт.
    # ожидание: Карточка P, матрица P и история с H.
    Then "offer-package-marker" contains the text "{PKG_NAME_RU}" in "offer-card-{P}"
    # что: Маркер в карточке P.
    # ожидание: Название пакета на русском.
    And "offer-package-marker" contains the text "{PKG_NAME_RU}" in "offer-snapshot-matrix-banner"
    # что: Маркер в шапке матрицы snapshot.
    # ожидание: То же название.
    And "offer-package-marker" is visible in the history row named "Offer-{RUN}-H"
    # что: Маркер в строке истории H.
    # ожидание: Виден.
    When the user clicks "offer-select-{P}"
    # что: Отмечаем P для сравнения.
    # ожидание: Отмечена.
    And the user clicks "offer-select-{H}"
    # что: Отмечаем H для сравнения.
    # ожидание: Отмечена.
    And the user clicks "offer-compare-open"
    # что: Открываем экран сравнения.
    # ожидание: Экран сравнения.
    Then "offer-package-marker" contains the text "{PKG_NAME_RU}" in "offer-compare-col-{P}"
    # что: Маркер в колонке P.
    # ожидание: Название пакета.
    And "offer-package-marker" contains the text "{PKG_NAME_RU}" in "offer-compare-col-{H}"
    # что: Маркер в колонке H.
    # ожидание: Название пакета.
    When the admin calls GET "/api/offers/{P}/pdf" with language "pl"
    # что: Скачиваем PDF P через API (PL).
    # ожидание: 200.
    Then the PDF text contains "{PKG_NAME_PL}"
    # что: Текст PDF.
    # ожидание: Название пакета на польском.

  Scenario: TC-UI-PKG-02 No marker without a package
    TC-UI-PKG-02: в проекте без применённого пакета маркера нет ни в карточке, ни в матрице, ни в истории, ни в сравнении (2.8).
    Given a second offers project with another client and a filled estimate exists
    # что: Второй проект X_ со своим клиентом и сметой S3.
    # ожидание: Проект создан.
    And no package is applied to the estimate of the second project
    # что: Убеждаемся, что пакет второй сметы не применён.
    # ожидание: Пакета нет.
    And the admin prepares the offer "N" in the second project
    # что: Оферта N во втором проекте.
    # ожидание: Создана.
    And the admin prepares the offer "N2" in the second project
    # что: Оферта N2 во втором проекте.
    # ожидание: Создана.
    And the admin successfully calls POST "/api/offers/{N2}/send"
    # что: Отправляем N2.
    # ожидание: 200.
    And the admin successfully calls POST "/api/offers/{N2}/withdraw"
    # что: Отзываем N2 (история).
    # ожидание: 200.
    When the user opens the offers tab of the second project
    # что: Открываем вкладку оферт второго проекта.
    # ожидание: Карточка N, матрица N и история с N2.
    Then "offer-snapshot-matrix-banner" is visible
    # что: Матрица N открыта по умолчанию.
    # ожидание: Видна.
    And no element matches "offer-package-marker"
    # что: Маркер пакета в карточке, матрице и истории.
    # ожидание: Нигде нет.
    When the user clicks "offer-select-{N}"
    # что: Отмечаем N.
    # ожидание: Отмечена.
    And the user clicks "offer-select-{N2}"
    # что: Отмечаем N2.
    # ожидание: Отмечена.
    And the user clicks "offer-compare-open"
    # что: Открываем сравнение.
    # ожидание: Экран сравнения.
    Then "offer-compare-table" is visible
    # что: Таблица сравнения.
    # ожидание: Видна.
    And no element matches "offer-package-marker"
    # что: Маркер в сравнении.
    # ожидание: Нет.

  Scenario: TC-UI-PKG-02 Reference project 1531 has no marker
    TC-UI-PKG-02 (п. 2): на эталонном проекте 1531 без применённого пакета у карточек нет маркера (только чтение).
    Given the reference project 1531 is present, otherwise the case is skipped
    # что: Проверяем, что эталонный проект 1531 есть в этой БД.
    # ожидание: Есть, иначе кейс пропущен.
    When the user opens the path "/projects/1531/pricing"
    # что: Открываем вкладку оферт проекта 1531 (только чтение).
    # ожидание: Вкладка оферт отображается.
    Then "offer-tab" is visible
    # что: Вкладка оферт.
    # ожидание: Видна.
    And no element matches "offer-package-marker"
    # что: Маркер пакета.
    # ожидание: Нет.

  Scenario: TC-UI-PKG-03 The marker follows the UI language
    TC-UI-PKG-03: при переключении языка PL ↔ RU название пакета в маркере меняется на локализованное (2.8).
    Given the first offer package is applied to the estimate
    # что: Применяем к смете первый пакет.
    # ожидание: Пакет применён.
    And the admin prepares the offer "P"
    # что: Оферта P через API.
    # ожидание: Создана.
    When the user opens the offers tab
    # что: Открываем вкладку оферт (RU).
    # ожидание: Карточка P видна.
    Then "offer-package-marker" contains the text "{PKG_NAME_RU}" in "offer-card-{P}"
    # что: Маркер на русском.
    # ожидание: Русское название пакета.
    When the UI language is switched to "pl"
    # что: Переключаем язык интерфейса на PL.
    # ожидание: Страница перезагружена на польском.
    Then "offer-package-marker" contains the text "{PKG_NAME_PL}" in "offer-card-{P}"
    # что: Маркер на польском.
    # ожидание: Польское название пакета.
    When the UI language is switched to "ru"
    # что: Возвращаем язык RU.
    # ожидание: Страница на русском.
    Then "offer-package-marker" contains the text "{PKG_NAME_RU}" in "offer-card-{P}"
    # что: Маркер снова на русском.
    # ожидание: Русское название.
