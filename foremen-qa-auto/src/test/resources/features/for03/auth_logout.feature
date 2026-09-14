@smoke @FOR-03
Feature: Logout clears the session
  Выход очищает сессию и токены.

  # FOR-03 authentication smoke coverage (Requirement 5.5).
  # An authenticated user who triggers logout has their stored tokens cleared and is redirected
  # back to /login.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.

  Scenario: Logout clears tokens and redirects to login
    Given I am logged in as the seeded admin
    # что: Входим под сидовым администратором.
    # ожидание: Администратор аутентифицирован, открыта защищённая оболочка приложения.
    And access and refresh tokens are stored
    # что: Проверяем сохранение токенов сессии.
    # ожидание: В localStorage присутствуют access- и refresh-токены.
    When I log out
    # что: Выполняем выход из приложения.
    # ожидание: Сессия завершена.
    Then I am redirected to the login page
    # что: Проверяем редирект на вход.
    # ожидание: Произошёл переход на /login.
    And no tokens are stored
    # что: Проверяем отсутствие токенов.
    # ожидание: В localStorage нет access/refresh токенов.
