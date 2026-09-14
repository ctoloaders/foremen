@smoke @FOR-04
Feature: Catalog and Dictionaries navigation groups
  Навигационные группы «Каталог» и «Справочники» и корректная маршрутизация их пунктов.

  # FOR-04 base-entities smoke coverage (Requirements 8.6, 4.1, FOR-04-15).
  # The "Catalog" and "Dictionaries" navigation groups are present in the shell and their items
  # route to the correct pages: Catalog -> Work Catalog (/catalog/works) + Work Prices
  # (/catalog/prices); Dictionaries -> the nine flat dictionary routes (a representative subset is
  # verified to route).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.
    And I am logged in as the seeded admin
    # что: Входим под сидовым администратором.
    # ожидание: Администратор аутентифицирован, открыта защищённая оболочка приложения.

  Scenario: The Catalog and Dictionaries nav groups are present and route correctly
    Then the Catalog navigation group items route correctly
    # что: Проверяем маршрутизацию пунктов группы «Каталог».
    # ожидание: Пункты группы «Каталог» ведут на корректные страницы.
    And the Dictionaries navigation group items route correctly
    # что: Проверяем маршрутизацию пунктов группы «Справочники».
    # ожидание: Пункты группы «Справочники» ведут на корректные страницы.
