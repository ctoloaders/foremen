@smoke @FOR-04
Feature: Work Catalog — list and create with FK selects
  Каталог работ: список и создание позиции с выбором связей через reference-селекты.

  # FOR-04 base-entities smoke coverage (Requirements 8.2, 4.1, 9.2).
  # The Work Catalog page renders the list; creating a work item selects a category and a unit via
  # the two FK reference selects, then the item appears in the list. Fixtures are torn down in
  # FK-safe LIFO order (self-cleaning, Requirement 3).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.
    And I am logged in as the seeded admin
    # что: Входим под сидовым администратором.
    # ожидание: Администратор аутентифицирован, открыта защищённая оболочка приложения.

  Scenario: The work catalog list renders and a created work item appears in it
    When I open the Work Catalog page
    # что: Открываем «Каталог работ».
    # ожидание: Загрузилась страница каталога работ.
    Then the work catalog list renders
    # что: Проверяем отрисовку каталога работ.
    # ожидание: Список позиций каталога работ отображается.
    When I create a work item selecting a category and a unit
    # что: Создаём позицию каталога, выбирая категорию и единицу через reference-селекты.
    # ожидание: Позиция создана с выбранными связями.
    Then the created work item appears in the work catalog list
    # что: Ищем созданную позицию в каталоге.
    # ожидание: Созданная позиция присутствует в списке.
