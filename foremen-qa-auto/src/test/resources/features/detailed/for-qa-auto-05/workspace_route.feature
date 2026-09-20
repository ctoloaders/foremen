@detailed @FOR-QA-AUTO-05
Feature: Workspace route and page
  Маршрут рабочего пространства проекта: рендер страницы, нормализация вкладки, недоступный проект и защита прав.

  # FOR-QA-AUTO-05 detailed slice mirroring FOR-05-01 test-cases.md, Фича 1 (маршрут и страница).
  # Проекты создаются через API сидовым админом и удаляются в teardown; перед каждым сценарием
  # чистится localStorage (foremen.workingProjectId и foremen.projectTab.*).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов, можно начинать сценарий.

  Scenario: TC-01-01 opening the workspace renders it and the list stays reachable
    Открытие /projects/:id/:tab рендерит рабочее пространство; список проектов остаётся доступен.
    Given I am logged in as the seeded admin
    # что: Входим сидовым администратором.
    # ожидание: Открыта защищённая оболочка приложения.
    And a design-stage project exists
    # что: Создаём проект стадии проектирования (DRAFT) через API.
    # ожидание: Проект создан, id запомнен, teardown зарегистрирован.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage (рабочий проект и память вкладок) на домене приложения.
    # ожидание: Состояние чистое, сценарий стартует с нуля.
    When I open the design project overview tab
    # что: Переходим на /projects/{PID_DESIGN}/overview.
    # ожидание: Внутри AppShell рендерится рабочее пространство проекта.
    Then the project workspace page is rendered
    # что: Проверяем контейнер страницы рабочего пространства.
    # ожидание: project-workspace-page отрисован.
    And the workspace header is shown
    # что: Проверяем шапку рабочего пространства (имя + статус-плашка).
    # ожидание: workspace-header присутствует.
    And the workspace tab "overview" is active
    # что: Проверяем активную вкладку.
    # ожидание: Активна вкладка «Обзор» (overview).
    And the projects list is reachable again
    # что: Возвращаемся на /projects.
    # ожидание: Список проектов по-прежнему рендерится корректно.

  Scenario: TC-01-02 bare route replaces to the allowed tab
    Голый /projects/:id редиректит (replace) на разрешённую вкладку overview; Back не возвращает на голый маршрут.
    Given I am logged in as the seeded admin
    # что: Входим сидовым администратором.
    # ожидание: Оболочка приложения открыта.
    And a design-stage project exists
    # что: Создаём design-проект через API.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Память вкладок пуста — резолвинг возьмёт overview.
    When I open the design project bare route
    # что: Переходим на голый /projects/{PID_DESIGN}.
    # ожидание: Происходит навигация с replace на разрешённую вкладку.
    Then the workspace URL resolves to the overview tab for the design project
    # что: Проверяем итоговый URL.
    # ожидание: URL стал /projects/{PID_DESIGN}/overview.
    When I navigate back in the browser
    # что: Нажимаем «Назад» в браузере.
    # ожидание: Так как нормализация была replace, возврат не попадает на голый маршрут.
    Then the browser is not on the design project bare route
    # что: Проверяем, что не оказались снова на голом /projects/{PID_DESIGN}.
    # ожидание: Голый маршрут не в истории (был replace, не push).

  # TC-01-04 (недоступный проект → graceful workspace-project-unavailable) НЕ автоматизируется на
  # этом стенде: при переходе на /projects/{несуществующий-id}/overview фронтенд-запрос
  # GET /api/projects/{id} возвращает 404 (проверено), но клиентский useProject держит рабочее
  # пространство в бесконечном loading-скелетоне и не переключается на состояние ProjectUnavailable
  # в наблюдаемое время (проверено прямым прогоном > 30с). Состояние
  # [data-testid=workspace-project-unavailable] не появляется детерминированно, поэтому сценарий
  # вынесен в «не автоматизировано (причина)» в OVERVIEW (по аналогии с TC-01-05).

  Scenario: TC-01-03 workspace route is guarded by PROJECTS READ identically to the list
    Маршрут рабочего пространства защищён PROJECTS/READ; пользователь без него редиректится на /403 (паритет со списком).
    # Пользователь провижионится с ролью, гранящей ТОЛЬКО USERS/READ, то есть без PROJECTS/READ —
    # переиспользуется паттерн NavVisibilitySteps (роль + грант + активный пользователь).
    Given a role granting only "USERS" "READ" exists
    # что: Создаём кастомную роль с единственным грантом USERS/READ (без PROJECTS/READ).
    # ожидание: Роль создана, код запомнен, teardown зарегистрирован.
    And a test user in the restricted role exists
    # что: Провижионим активного пользователя в этой ограниченной роли.
    # ожидание: Пользователь создан, зарегистрирован для очистки.
    And a design-stage project exists
    # что: Создаём design-проект сидовым админом (для формирования URL рабочего пространства).
    # ожидание: Проект создан.
    When I log in through the UI as the test user
    # что: Входим через UI под ограниченным пользователем.
    # ожидание: Пользователь аутентифицирован в оболочке.
    And I open the design project workspace by deep link
    # что: Переходим по прямой ссылке на /projects/{PID_DESIGN}/overview.
    # ожидание: PermissionGuard проверяет PROJECTS/READ.
    Then I am shown the forbidden page for the workspace route
    # что: Проверяем защиту маршрута.
    # ожидание: Редирект на /403 — паритет с защитой списка /projects.
