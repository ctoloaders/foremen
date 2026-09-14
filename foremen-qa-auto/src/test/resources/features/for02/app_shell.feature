@smoke @FOR-02
Feature: Admin app shell renders with usable navigation
  Оболочка приложения (сайдбар + топбар) и работоспособность основной навигации.

  # FOR-02 admin-panel smoke coverage (Requirements 7.1, 4.1).
  # When an authenticated user loads the app, the SPA shell (topbar + sidebar navigation on
  # desktop) renders and the primary navigation is operable — clicking primary nav items routes to
  # their pages. This is the known-good foundation the rest of the admin panel builds on.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.
    And I am logged in as the seeded admin
    # что: Входим под сидовым администратором.
    # ожидание: Администратор аутентифицирован, открыта защищённая оболочка приложения.

  Scenario: The authenticated shell renders and primary navigation is usable
    Then the application shell renders
    # что: Проверяем отрисовку оболочки приложения.
    # ожидание: Отрисованы сайдбар и топбар на десктопе.
    And the primary navigation is usable
    # что: Проверяем работоспособность основной навигации.
    # ожидание: Переходы по основным пунктам меню работают.
