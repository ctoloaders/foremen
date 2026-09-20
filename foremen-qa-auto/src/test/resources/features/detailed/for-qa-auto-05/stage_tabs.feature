@detailed @FOR-QA-AUTO-05
Feature: Stage-dependent workspace tabs
  Стадийно-зависимый набор вкладок: design vs execution, клик по вкладке меняет URL, и селектор design-вкладок для execution (FIX-2).

  # FOR-QA-AUTO-05 detailed slice mirroring FOR-05-01 test-cases.md, Фича 6.
  # Приоритетные FIX-2 сценарии: TC-06-06 (селектор design-вкладок на execution), TC-06-07
  # (у design-проекта селектора нет).
  # ВАЖНО: сидовый ADMIN обходит ABAC (ForemenPermissionEvaluator + клиентский usePermission →
  # для роли ADMIN hasPermission всегда true), поэтому засиженность отдельных ресурсов вкладок НЕ
  # влияет на видимость вкладок для ADMIN — ему видны ВСЕ стадийные вкладки. Набор для design:
  # overview, readiness, pricing, rooms, estimate, scheduleDesign, contract; для execution:
  # overview, procurement, documents, planActual, payroll, amendments, priceHistory.
  # TC-06-08 (селектор скрыт при 0 доступных design-вкладках) НЕ автоматизируется: любой доступ в
  # рабочее пространство требует PROJECTS/READ, а readiness — design-вкладка под PROJECTS/READ, так
  # что доступная design-вкладка есть всегда → «0 design-вкладок» недостижимо (см. OVERVIEW).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда.
    # ожидание: Бэкенд и фронтенд отвечают.

  Scenario: TC-06-01 the design stage shows its ordered tab set
    Design-проект показывает свой упорядоченный набор design-вкладок; execution-вкладки в основной полосе отсутствуют.
    Given I am logged in as the seeded admin
    # что: Входим сидовым администратором (обходит ABAC — видит все вкладки).
    # ожидание: Оболочка открыта.
    And a design-stage project exists
    # что: Создаём design-проект (DRAFT).
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage (рабочий проект и память вкладок; токены и локаль сохранены/фиксированы).
    # ожидание: Состояние чистое, пользователь остаётся аутентифицированным.
    When I open the design project overview tab
    # что: Открываем /projects/{PID_DESIGN}/overview.
    # ожидание: Стадия определена как design.
    Then the visible workspace tabs are "overview, readiness, pricing, rooms, estimate, scheduleDesign, contract" in order
    # что: Проверяем видимые design-вкладки в порядке WORKSPACE_TABS.
    # ожидание: Видны Обзор, Готовность, Расценки, Помещения, Смета, График, Договор.
    And the workspace tab "procurement" is hidden
    # что: Проверяем отсутствие execution-вкладки «Закупки» в основной полосе design-проекта.
    # ожидание: Вкладка procurement скрыта для design-стадии.
    And the workspace tab "documents" is hidden
    # что: Проверяем отсутствие execution-вкладки «Документы» в основной полосе design-проекта.
    # ожидание: Вкладка documents скрыта для design-стадии.

  Scenario: TC-06-02 the execution stage shows its ordered tab set and no readiness widget
    Execution-проект показывает свой набор execution-вкладок; design-вкладки в основной полосе отсутствуют; виджет готовности не рендерится.
    Given I am logged in as the seeded admin
    # что: Входим сидовым администратором.
    # ожидание: Оболочка открыта.
    And an execution-stage project exists
    # что: Создаём execution-проект (ACTIVE) через API с inline status=ACTIVE.
    # ожидание: Проект создан в стадии execution.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    When I open the execution project overview tab
    # что: Открываем /projects/{PID_EXEC}/overview.
    # ожидание: Стадия определена как execution.
    Then the visible workspace tabs are "overview, procurement, documents, planActual, payroll, amendments, priceHistory" in order
    # что: Проверяем видимые execution-вкладки в порядке WORKSPACE_TABS.
    # ожидание: Видны Обзор, Закупки, Документы, План/Факт, ФОТ, Изменения, История цен.
    And the workspace tab "readiness" is hidden
    # что: Проверяем отсутствие design-вкладки «Готовность» в основной полосе execution-проекта.
    # ожидание: readiness не в основной полосе (доступна через селектор design-вкладок).
    And the workspace tab "rooms" is hidden
    # что: Проверяем отсутствие design-вкладки «Помещения» в основной полосе execution-проекта.
    # ожидание: rooms не в основной полосе (доступна через селектор design-вкладок).
    And the readiness widget is not shown
    # что: Проверяем отсутствие виджета готовности на execution-стадии.
    # ожидание: Design-виджет готовности НЕ рендерится.

  Scenario: TC-06-03 clicking a tab changes the URL to the tab key
    Клик по вкладке меняет URL на key вкладки.
    Given I am logged in as the seeded admin
    # что: Входим сидовым администратором.
    # ожидание: Оболочка открыта.
    And a design-stage project exists
    # что: Создаём design-проект.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    When I open the design project overview tab
    # что: Открываем /projects/{PID_DESIGN}/overview.
    # ожидание: Вкладка «Помещения» видима.
    And I click the workspace tab "rooms"
    # что: Кликаем вкладку «Помещения».
    # ожидание: Навигация на /projects/{PID_DESIGN}/rooms.
    Then the workspace URL ends with "rooms" for the design project
    # что: Проверяем URL.
    # ожидание: URL содержит key=rooms.
    When I click the workspace tab "estimate"
    # что: Кликаем вкладку «Смета».
    # ожидание: Навигация на /projects/{PID_DESIGN}/estimate.
    Then the workspace URL ends with "estimate" for the design project
    # что: Проверяем URL.
    # ожидание: URL стал /projects/{PID_DESIGN}/estimate.

  Scenario: TC-06-06 an execution project shows the design-tabs selector as the last strip element
    FIX-2: у execution-проекта в конце полосы есть селектор design-вкладок; клик по пункту (rooms) ведёт на /rooms и пишет память вкладки; после перезагрузки восстанавливается.
    Given I am logged in as the seeded admin
    # что: Входим сидовым администратором (все design-вкладки доступны).
    # ожидание: Оболочка открыта.
    And an execution-stage project exists
    # что: Создаём execution-проект (ACTIVE).
    # ожидание: Проект создан в стадии execution.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    When I open the execution project overview tab
    # что: Открываем /projects/{PID_EXEC}/overview.
    # ожидание: Показана полоса execution-вкладок.
    Then the design selector is shown
    # что: Проверяем конец полосы вкладок.
    # ожидание: Последним элементом показан сгруппированный селектор design-вкладок (workspace.designTabs).
    When I open the design selector
    # что: Открываем селектор design-вкладок.
    # ожидание: Внутри — доступные design-вкладки без overview.
    Then the design-selector tab "rooms" is available
    # что: Проверяем наличие пункта «Помещения» в селекторе.
    # ожидание: Пункт rooms доступен.
    When I click the design-selector tab "rooms"
    # что: Кликаем пункт «Помещения».
    # ожидание: Навигация на /projects/{PID_EXEC}/rooms.
    Then the workspace URL ends with "rooms" for the execution project
    # что: Проверяем URL.
    # ожидание: URL стал /projects/{PID_EXEC}/rooms (design-вкладка валидна для execution).
    And the stored tab for the execution project reads "rooms"
    # что: Читаем foremen.projectTab.{PID_EXEC}.
    # ожидание: Значение = rooms — выбор запомнен как обычный клик по вкладке.
    When I reload the page
    # что: Перезагружаем и остаёмся на выбранной вкладке.
    # ожидание: Страница перезагружена.
    And I open the execution project bare route
    # что: Переходим на голый /projects/{PID_EXEC}.
    # ожидание: Резолвинг берёт вкладку из памяти проекта.
    Then the workspace URL ends with "rooms" for the execution project
    # что: Проверяем восстановленную вкладку.
    # ожидание: Восстановлена rooms (валидный набор = стадийные ∪ селектор design-вкладок).

  Scenario: TC-06-07 a design project has no separate design-tabs selector
    У design-проекта отдельного селектора design-вкладок нет (они уже в основной полосе).
    Given I am logged in as the seeded admin
    # что: Входим сидовым администратором.
    # ожидание: Оболочка открыта.
    And a design-stage project exists
    # что: Создаём design-проект (DRAFT).
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    When I open the design project overview tab
    # что: Открываем /projects/{PID_DESIGN}/overview.
    # ожидание: Стадия design; design-вкладки уже в основной полосе.
    Then the design selector is hidden
    # что: Проверяем полосу вкладок.
    # ожидание: Отдельный селектор workspace.designTabs НЕ отрисован (для design resolveDesignSelectorTabs = []).
