@smoke @FOR-02
Feature: Roles admin — list and access matrix
  Список ролей и отображение матрицы доступов.

  # FOR-02 admin-panel smoke coverage (Requirements 7.2, 4.1).
  # The Roles page renders the roles list, and its Matrix tab shows the access matrix: a grid of
  # role rows x resource columns with toggleable C/R/U/D operation controls. This slice verifies
  # both the list and the matrix render for the seeded ADMIN.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.
    And I am logged in as the seeded admin
    # что: Входим под сидовым администратором.
    # ожидание: Администратор аутентифицирован, открыта защищённая оболочка приложения.

  Scenario: The roles list and the access matrix render
    When I open the Roles page
    # что: Открываем страницу «Роли».
    # ожидание: Загрузилась страница ролей.
    Then the roles list renders
    # что: Проверяем отрисовку списка ролей.
    # ожидание: Таблица ролей отображается с заголовками колонок.
    When I open the access matrix
    # что: Открываем матрицу доступов роли.
    # ожидание: Открыта матрица (ресурс × операция).
    Then the access matrix is shown
    # что: Проверяем отрисовку матрицы доступов.
    # ожидание: Матрица доступов видна: есть колонки ресурсов и строки ролей.
