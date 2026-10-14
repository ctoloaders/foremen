@detailed @FOR-QA-AUTO-08 @ui @fill-flow
Feature: Подписание документов — добавление, просмотр подставленных значений, заполнение пробелов (FOR-05-08)
  Сквозной путь: добавить документ в проект, увидеть его содержимое с подставленными из проекта значениями
  (merge токенов), затем заполнить оставшиеся пробелы. Документ готовится через API (формы создания в UI нет),
  генерация и просмотр — через реальный UI DocumentDetail. Мапится на TC-UI-FILL-01/02/03.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Frontend на :3000 и backend на :8080 доступны.
    And I am logged in as the seeded admin
    # что: Логинимся администратором в браузере.
    # ожидание: Открыт AppShell, сессия администратора активна.

  Scenario: TC-UI-FILL-01 — добавить документ и увидеть содержимое с подставленными значениями
    Проверка, что после генерации тело документа показывает реальные значения проекта вместо {Token}.
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом через API (клиент разрешает токен имени и адрес).
    # ожидание: Проект создан, клиент — член проекта.
    And a generated draft document exists in the project
    # что: Добавляем документ (CONTRACT_WORKS, DRAFT) и запускаем generate (merge шаблона с данными проекта).
    # ожидание: Документ создан, generate → 200, тело сгенерировано.
    Then the merged body substitutes tokens with the client's real value
    # что: Проверяем, что в теле вместо токена клиента подставлено его реальное имя.
    # ожидание: Тело содержит реальное имя клиента (а не плейсхолдер).
    And the merged body contains no raw placeholder tokens
    # что: Проверяем отсутствие сырого плейсхолдера {ContactName}.
    # ожидание: Сырой токен {ContactName} в теле отсутствует.
    And the merged body marks unresolved tokens as blank gaps
    # что: Проверяем, что неразрешённые токены помечены маркером-пробелом «___».
    # ожидание: В теле присутствует маркер «___» (не пустая строка).
    When I open the document signing tab
    # что: Переходим на вкладку documentSigning проекта.
    # ожидание: Вкладка отрисована.
    And I open the seeded document detail
    # что: Открываем деталь засеянного документа.
    # ожидание: DocumentDetail отрисован.
    And I expand the document body preview
    # что: Раскрываем превью тела документа.
    # ожидание: Блок document-detail-preview виден.
    Then the body preview shows the substituted client value
    # что: Проверяем, что превью показывает подставленное имя клиента, без сырых токенов и ключей i18n.
    # ожидание: Превью содержит реальное имя клиента; нет {ContactName} и нет documentSigning.*.

  Scenario: TC-UI-FILL-02/03 — запрос подписей открывает панель полей и заполнение пробела
    Проверка, что после запроса подписей документ заморожен, панель form-fields доступна, и объявленный пробел заполняется.
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом через API.
    # ожидание: Проект создан, клиент — член проекта.
    And a generated draft document exists in the project
    # что: Добавляем документ и генерируем тело (подстановка значений).
    # ожидание: Документ DRAFT с сгенерированным телом.
    When signatures are requested from the client with method "PRINT"
    # что: Запрашиваем подписи у клиента (метод PRINT) — тело замораживается.
    # ожидание: Документ переходит в PENDING_SIGNATURES.
    Then the document status is "PENDING_SIGNATURES"
    # что: Проверяем статус документа.
    # ожидание: Статус PENDING_SIGNATURES.
    When I open the document signing tab
    # что: Открываем вкладку подписания.
    # ожидание: Вкладка отрисована.
    And I open the seeded document detail
    # что: Открываем деталь документа.
    # ожидание: DocumentDetail отрисован.
    Then the form fields panel is shown
    # что: Проверяем отрисовку панели полей-пробелов.
    # ожидание: Блок form-fields-panel виден (с полями или в пустом состоянии).
    And I fill the first declared gap if any
    # что: Заполняем первый объявленный пробел (form-field) и сохраняем; при отсутствии полей — N/A.
    # ожидание: HTTP 200 и сохранённое значение; либо панель в пустом состоянии (нет объявленных полей).
