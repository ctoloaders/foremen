@detailed @FOR-QA-AUTO-05
Feature: Readiness widget and mode caption
  Виджет готовности (design-стадия) и подпись режима: виджет только в design, подпись соответствует стадии.

  # FOR-QA-AUTO-05 detailed slice mirroring FOR-05-01 test-cases.md, Фича 8.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда.
    # ожидание: Бэкенд и фронтенд отвечают.
    And I am logged in as the seeded admin
    # что: Входим сидовым администратором.
    # ожидание: Оболочка открыта.

  Scenario: TC-08-01 the design stage renders the readiness widget
    В design-стадии виджет готовности отрисован в шапке.
    Given a design-stage project exists
    # что: Создаём design-проект (DRAFT).
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    When I open the design project overview tab
    # что: Открываем /projects/{PID_DESIGN}/overview.
    # ожидание: Стадия design.
    Then the readiness widget is shown
    # что: Проверяем виджет готовности (workspace.readiness, RU: «Готовность к подписанию»).
    # ожидание: workspace-readiness-widget отрисован (донат + подпись), без краха и сырого ключа.

  Scenario: TC-08-02 the execution stage does not render the readiness widget
    В execution-стадии виджет готовности отсутствует.
    Given an execution-stage project exists
    # что: Создаём execution-проект (ACTIVE).
    # ожидание: Проект создан в стадии execution.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    When I open the execution project overview tab
    # что: Открываем /projects/{PID_EXEC}/overview.
    # ожидание: Стадия execution.
    Then the readiness widget is not shown
    # что: Проверяем отсутствие виджета готовности.
    # ожидание: Design-виджет готовности НЕ рендерится.

  Scenario: TC-08-03 the mode caption matches the stage
    Подпись режима соответствует стадии: design → «Проектирование», execution → «В работе».
    Given a design-stage project exists
    # что: Создаём design-проект (DRAFT).
    # ожидание: Проект создан.
    And an execution-stage project exists
    # что: Создаём execution-проект (ACTIVE).
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    When I open the design project overview tab
    # что: Открываем /projects/{PID_DESIGN}/overview.
    # ожидание: Стадия design.
    Then the mode caption reads "Проектирование"
    # что: Проверяем подпись режима.
    # ожидание: workspace.mode.design (RU: «Проектирование»).
    When I open the execution project overview tab
    # что: Открываем /projects/{PID_EXEC}/overview.
    # ожидание: Стадия execution.
    Then the mode caption reads "В работе"
    # что: Проверяем подпись режима.
    # ожидание: workspace.mode.execution (RU: «В работе»).
