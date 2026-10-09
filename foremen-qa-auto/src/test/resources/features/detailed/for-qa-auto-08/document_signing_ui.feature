@detailed @FOR-QA-AUTO-08 @ui
Feature: Подписание документов — UI вкладки и детали (FOR-05-08)
  Браузерные сценарии вкладки «Подписание документов».
  Документы засеяны через API (в UI нет формы создания); проверяем отрисовку, действия и i18n.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Frontend на :3000 и backend на :8080 доступны.
    And I am logged in as the seeded admin
    # что: Логинимся администратором в браузере.
    # ожидание: Открыт AppShell, сессия администратора активна.

  Scenario: TC-UI-TAB-01 — вкладка подписания отрисована и показывает документ
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом через API.
    # ожидание: Проект создан.
    And a pending-signatures document exists in the project
    # что: Засеиваем документ и запрашиваем подписи через API.
    # ожидание: Документ в статусе PENDING_SIGNATURES.
    When I open the document signing tab
    # что: Переходим на вкладку documentSigning проекта.
    # ожидание: Вкладка отрисована.
    Then the document signing tab is rendered
    # что: Проверяем отрисовку вкладки.
    # ожидание: Контейнер document-signing-tab виден.
    And the tab lists the seeded document
    # что: Проверяем, что список содержит засеянный документ.
    # ожидание: Список отрисован, строка документа присутствует.

  Scenario: TC-UI-TAB-06/05 — владелец видит действие void и воидит документ
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом.
    # ожидание: Проект создан.
    And a pending-signatures document exists in the project
    # что: Засеиваем PENDING-документ через API.
    # ожидание: Документ PENDING_SIGNATURES.
    When I open the document signing tab
    # что: Открываем вкладку подписания.
    # ожидание: Вкладка отрисована.
    And I open the seeded document detail
    # что: Открываем деталь засеянного документа.
    # ожидание: DocumentDetail отрисован.
    Then the owner can see the void action
    # что: Проверяем видимость действия аннулирования у владельца.
    # ожидание: Кнопка action-void видна.
    And the signing surface shows no raw i18n keys
    # что: Проверяем отсутствие сырых ключей i18n.
    # ожидание: Нет текста documentSigning.* на экране.
    When I void the document from the detail view
    # что: Нажимаем «Аннулировать» и подтверждаем.
    # ожидание: Отправлен POST .../void.
    Then the void request succeeds
    # что: Проверяем результат аннулирования.
    # ожидание: HTTP 200.
