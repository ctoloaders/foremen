@smoke @FOR-04
Feature: Reference filter narrows a list
  Фильтр по reference-колонке сужает список позиций.

  # FOR-04 base-entities smoke coverage (Requirements 8.5, 4.1).
  # On the Work Catalog list, a reference-backed column (work category) exposes a filter popover.
  # Applying a single-value reference filter narrows the list to rows matching the picked category.
  # Two run-created work items in two run-created categories are set up via the API and torn down
  # afterward (self-cleaning, Requirement 3).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.
    And I am logged in as the seeded admin
    # что: Входим под сидовым администратором.
    # ожидание: Администратор аутентифицирован, открыта защищённая оболочка приложения.

  Scenario: A single-value reference filter narrows the work catalog list
    When two work items in two different categories exist
    # что: Готовим две позиции в разных категориях.
    # ожидание: Созданы две позиции в двух разных категориях.
    And I open the Work Catalog page
    # что: Открываем «Каталог работ».
    # ожидание: Загрузилась страница каталога работ.
    Then the work catalog list renders
    # что: Проверяем отрисовку каталога работ.
    # ожидание: Список позиций каталога работ отображается.
    And applying a single-value category filter narrows the list
    # что: Применяем фильтр по одной категории.
    # ожидание: Список сузился до позиций выбранной категории.
