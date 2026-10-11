@detailed @FOR-QA-AUTO-11 @ui
Feature: Готовность проекта — UI вкладки «Готовность» и виджет ReadinessWidget (FOR-05-11)
  Браузерные сценарии вкладки `readiness` воркспейса проекта и chip-строки готовности в ReadinessWidget.
  Проект засеян через API; проверяем роутинг вкладки, общий donut и состояние, упорядоченные строки областей с локализованными метками и процентами, переход в целевой таб, пустое состояние без читаемых областей, рефетч после изменения области и стадийный гейт виджета. API-половина FOR-05-11 (гардинг, скоуп, композиция, аудит) остаётся в test-cases.md.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Frontend на :3000 и backend на :8080 доступны.
    And I am logged in as the seeded admin
    # что: Логинимся администратором в браузере.
    # ожидание: Сессия администратора активна, оболочка отрисована.

  Scenario: TC-UI-TAB-01 — вкладка «Готовность» видна, открывается, donut и упорядоченные строки областей
    TC-UI-TAB-01: роутинг вкладки readiness, общий donut + состояние, строки областей в декларативном порядке с локализованными метками.
    Given a readiness design project exists
    # что: Готовим design-проект через API (с менеджером и клиентом, без активного прораба).
    # ожидание: Проект создан.
    When I open the readiness design project workspace
    # что: Открываем /projects/{id} под администратором.
    # ожидание: Воркспейс и лента табов отрисованы.
    Then the readiness tab is visible in the tab strip
    # что: Проверяем наличие таба «Готовность» в ленте.
    # ожидание: Таб readiness присутствует.
    When I click the readiness tab in the strip
    # что: Кликаем по табу «Готовность».
    # ожидание: Открывается вкладка готовности.
    Then the readiness workspace URL ends with "/readiness"
    # что: Проверяем сегмент URL.
    # ожидание: URL оканчивается на /readiness.
    And the readiness tab is rendered
    # что: Проверяем отрисовку контейнера вкладки.
    # ожидание: Контейнер readiness-tab виден.
    And the readiness overall donut and state are shown
    # что: Проверяем общий donut и локализованную метку общего состояния.
    # ожидание: Donut показывает процент, метка состояния непустая и локализованная.
    And the readiness area rows render in declared order
    # что: Проверяем, что строки областей идут в порядке rooms → estimate → offer → documents → team → schedule.
    # ожидание: Отрисованные ключи областей — подпоследовательность декларативного порядка.
    And the readiness area row "rooms" shows a localized state and percentage
    # что: Проверяем строку области «Помещения»: метка состояния и процент.
    # ожидание: У строки rooms локализованная метка состояния и процент с «%».
    And the readiness area row "team" shows a localized state and percentage
    # что: Проверяем строку области «Команда»: метка состояния и процент.
    # ожидание: У строки team локализованная метка состояния и процент с «%».
    And the readiness tab shows no raw i18n keys
    # что: Проверяем отсутствие сырых ключей i18n.
    # ожидание: На экране нет readiness.* и workspace.tab.* ключей.

  Scenario: TC-UI-TAB-02 — переход в целевой таб по строке области
    TC-UI-TAB-02: активация строки области навигирует воркспейс в её targetTab.
    Given a readiness design project exists
    # что: Готовим design-проект.
    # ожидание: Проект создан.
    When I open the readiness tab
    # что: Открываем вкладку «Готовность».
    # ожидание: Вкладка отрисована.
    And I activate the readiness area row "rooms"
    # что: Кликаем по заголовку строки области «Помещения».
    # ожидание: Навигация в таб rooms.
    Then the readiness workspace URL ends with "/rooms"
    # что: Проверяем сегмент URL после перехода.
    # ожидание: URL оканчивается на /rooms.
    When I open the readiness tab
    # что: Возвращаемся на вкладку «Готовность».
    # ожидание: Вкладка снова отрисована.
    And I activate the readiness area row "team"
    # что: Кликаем по заголовку строки области «Команда».
    # ожидание: Навигация в таб team.
    Then the readiness workspace URL ends with "/team"
    # что: Проверяем сегмент URL после перехода.
    # ожидание: URL оканчивается на /team.

  # TC-UI-TAB-03 (empty state: a caller with no readable area) is NOT automated here. No stock role
  # holds PROJECTS READ with zero Area_Resource READ (e.g. FOREMAN still reads ROOMS / WORK_SCHEDULE /
  # PROJECT_MEMBERS / SIGNABLE_DOCUMENTS), so a true no-readable-area caller needs a bespoke custom
  # role with a hand-built grant matrix. The empty-state contract is covered by the FOR-05-11 API
  # test-cases (TC-PSCOPE-02) and the backend ProjectReadinessService test. See the slice OVERVIEW.

  Scenario: TC-UI-TAB-04 — рефетч после изменения области (инвалидация)
    TC-UI-TAB-04: назначение активного прораба переводит строку «Команда» в DONE без перезагрузки страницы.
    Given a readiness design project exists
    # что: Готовим design-проект без активного прораба (team = BLOCKED).
    # ожидание: Проект создан.
    When I open the readiness tab
    # что: Открываем вкладку «Готовность» под администратором.
    # ожидание: Вкладка отрисована.
    Then the readiness area row "team" eventually shows state "BLOCKED"
    # что: Проверяем исходное состояние строки «Команда».
    # ожидание: Строка team в состоянии BLOCKED.
    When I assign an active foreman to the design project
    # что: Назначаем активного прораба через Team API (меняем гейт команды).
    # ожидание: Назначение успешно (2xx).
    Then the readiness area row "team" eventually shows state "DONE"
    # что: Проверяем, что строка «Команда» обновилась без перезагрузки (инвалидация readinessKeys.view).
    # ожидание: Строка team перешла в DONE.

  Scenario: TC-UI-WIDGET-01 — виджет готовности: donut и chip на область
    TC-UI-WIDGET-01: ReadinessWidget показывает donut от общего процента и по chip на читаемую область.
    Given a readiness design project exists
    # что: Готовим design-проект.
    # ожидание: Проект создан.
    When I open the readiness design project workspace
    # что: Открываем воркспейс design-проекта под администратором.
    # ожидание: Виден виджет готовности.
    Then the readiness widget is shown
    # что: Проверяем наличие ReadinessWidget.
    # ожидание: Виден workspace-readiness-widget.
    And the readiness widget shows a chip for the area "rooms"
    # что: Проверяем chip-строку готовности: подписи вида «имя: состояние».
    # ожидание: В строке есть chip «имя: состояние», без сырых ключей.

  Scenario: TC-UI-WIDGET-04 — стадийный гейт виджета на call-site
    TC-UI-WIDGET-04: виджет виден на design-стадии и отсутствует на стадии исполнения (ACTIVE).
    Given a readiness design project exists
    # что: Готовим design-проект.
    # ожидание: Проект создан.
    And a readiness execution project exists
    # что: Готовим проект в статусе ACTIVE (стадия исполнения).
    # ожидание: Проект создан.
    When I open the readiness design project workspace
    # что: Открываем воркспейс design-проекта.
    # ожидание: Воркспейс отрисован.
    Then the readiness widget is shown
    # что: Проверяем наличие виджета на design-стадии.
    # ожидание: ReadinessWidget отрисован.
    When I open the readiness execution project workspace
    # что: Открываем воркспейс ACTIVE-проекта.
    # ожидание: Воркспейс отрисован.
    Then the readiness widget is not shown
    # что: Проверяем отсутствие виджета вне design-стадии.
    # ожидание: ReadinessWidget не отрисован (гейт на call-site ProjectWorkspacePage).
