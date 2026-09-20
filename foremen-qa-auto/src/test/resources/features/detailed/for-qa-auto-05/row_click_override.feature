@detailed @FOR-QA-AUTO-05
Feature: Projects list row-click override
  Переопределение клика по строке в списке проектов: клик открывает рабочее пространство и ставит рабочий проект; редактирование доступно вторичным действием.

  # FOR-QA-AUTO-05 detailed slice mirroring FOR-05-01 test-cases.md, Фича 5.
  # Клик по строке НЕ открывает форму редактирования (FOR-05-01 Req 5.1/5.2); редактирование —
  # вторичное действие «Редактировать проект» в Обзоре (Req 5.3) и per-row иконки со stopPropagation.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда.
    # ожидание: Бэкенд и фронтенд отвечают.
    And I am logged in as the seeded admin
    # что: Входим сидовым администратором.
    # ожидание: Оболочка открыта.

  Scenario: TC-05-01 a row click opens the workspace and sets the working project
    Клик по строке открывает рабочее пространство и ставит рабочий проект, не открывая форму редактирования.
    Given a design-stage project exists
    # что: Создаём design-проект.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    When I open the projects list
    # что: Открываем /projects.
    # ожидание: Список отрисован.
    And I click the design project row
    # что: Кликаем строку проекта (не по иконкам действий).
    # ожидание: Навигация на /projects/{PID_DESIGN} (рабочее пространство).
    Then the project edit form is not open
    # что: Проверяем, что форма редактирования НЕ открылась.
    # ожидание: Форма редактирования проекта не отображается.
    And the browser is on the design project workspace
    # что: Проверяем URL.
    # ожидание: Открыто рабочее пространство /projects/{PID_DESIGN}.
    And the stored working project is the design project
    # что: Читаем foremen.workingProjectId.
    # ожидание: Значение = id design-проекта.

  Scenario: TC-05-03 editing is available as a secondary action in the overview
    Редактирование доступно вторичным действием «Редактировать проект» в панели «Обзор».
    Given a design-stage project exists
    # что: Создаём design-проект.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    When I open the design project overview tab
    # что: Открываем /projects/{PID_DESIGN}/overview.
    # ожидание: Панель «Обзор» отрисована.
    Then the overview edit affordance is shown
    # что: Проверяем аффорданс «Редактировать проект» (workspace.edit).
    # ожидание: Аффорданс присутствует и виден.
    When I click the overview edit affordance
    # что: Кликаем «Редактировать проект».
    # ожидание: Открывается существующая форма редактирования проекта.
    Then the project edit form is open
    # что: Проверяем открытие формы редактирования.
    # ожидание: Форма редактирования проекта открыта.

  Scenario: TC-05-02 row actions still work without opening the workspace
    Иконка «Редактировать» в строке открывает форму редактирования и не перехватывается кликом по строке (stopPropagation).
    Given a design-stage project exists
    # что: Создаём design-проект.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    When I open the projects list
    # что: Открываем /projects.
    # ожидание: Список отрисован.
    And I click the edit action on the design project row
    # что: Кликаем иконку «Редактировать» (Pencil) в строке проекта.
    # ожидание: Сработал stopPropagation — открывается форма, навигации в рабочее пространство нет.
    Then the project edit form is open
    # что: Проверяем открытие формы редактирования.
    # ожидание: Форма редактирования проекта открыта; клик по строке не перехвачен.
