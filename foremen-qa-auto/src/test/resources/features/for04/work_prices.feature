@smoke @FOR-04
Feature: Work Prices — list and current price
  Цены работ: список и наличие текущей цены.

  # FOR-04 base-entities smoke coverage (Requirements 8.3, 4.1).
  # The Work Prices page renders the list and shows a current price (validTo null). A full chain
  # (unit -> category -> work item -> seeded currency -> open-ended price) is set up via the API
  # with LIFO teardown so the run stays self-cleaning (Requirement 3).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.
    And I am logged in as the seeded admin
    # что: Входим под сидовым администратором.
    # ожидание: Администратор аутентифицирован, открыта защищённая оболочка приложения.

  Scenario: The work prices list renders and a current price is shown
    When a current work price exists
    # что: Готовим текущую цену работы (validTo = null).
    # ожидание: Существует актуальная цена работы.
    And I open the Work Prices page
    # что: Открываем «Цены работ».
    # ожидание: Загрузилась страница цен работ.
    Then the work prices list renders and a current price is shown
    # что: Проверяем список цен и наличие текущей цены.
    # ожидание: Список цен отображается, показана текущая цена.
