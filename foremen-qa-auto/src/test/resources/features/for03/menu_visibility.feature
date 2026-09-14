@smoke @FOR-03
Feature: Role-based menu visibility & authorization
  Фильтрация навигации по правам роли и запрет доступа к чужим маршрутам (страница /403).

  # FOR-03 menu-visibility smoke coverage (Requirements 6.1, 6.2, 6.3, 6.4).
  # The navigation surfaces are filtered by the caller's permission grants, so a user only sees the
  # items for resources they can READ. The restricted-role setup/teardown honors the self-cleaning
  # contract (Requirement 6.4 / 3).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.

  Scenario: Admin sees the primary navigation and the current role in the shell
    Given I am logged in as the seeded admin
    # что: Входим под сидовым администратором.
    # ожидание: Администратор аутентифицирован, открыта защищённая оболочка приложения.
    Then the primary navigation groups are visible
    # что: Проверяем видимость групп навигации для администратора.
    # ожидание: Основные группы навигации видны.
    And the current role name is shown in the shell
    # что: Проверяем отображение текущей роли в оболочке.
    # ожидание: В оболочке показано название текущей роли.

  Scenario: A USERS:READ-only role hides navigation items for resources it lacks
    Given a role granting only "USERS" "READ" exists
    # что: Через API создаём роль с единственным грантом USERS:READ.
    # ожидание: Создана ограниченная роль с правом USERS:READ.
    And a test user in the restricted role exists
    # что: Создаём активного тест-пользователя в ограниченной роли (прямая вставка в БД).
    # ожидание: Пользователь создан и готов к UI-входу.
    When I log in through the UI as the test user
    # что: Входим через UI под тест-пользователем.
    # ожидание: Тест-пользователь аутентифицирован, оболочка под его ролью.
    Then the navigation item for route "/users" is visible
    # что: Проверяем видимость пункта меню для /users.
    # ожидание: Пункт навигации на /users виден.
    And the navigation item for route "/" is visible
    # что: Проверяем видимость пункта меню для /.
    # ожидание: Пункт навигации на / виден.
    But the navigation item for route "/roles" is hidden
    # что: Проверяем скрытие пункта меню для /roles.
    # ожидание: Пункт навигации на /roles скрыт (нет прав на ресурс).
    And the navigation item for route "/projects" is hidden
    # что: Проверяем скрытие пункта меню для /projects.
    # ожидание: Пункт навигации на /projects скрыт (нет прав на ресурс).
    And the navigation item for route "/audit" is hidden
    # что: Проверяем скрытие пункта меню для /audit.
    # ожидание: Пункт навигации на /audit скрыт (нет прав на ресурс).

  Scenario: A restricted user deep-linking to a forbidden route lands on /403 with Go Back
    Given a role granting only "USERS" "READ" exists
    # что: Через API создаём роль с единственным грантом USERS:READ.
    # ожидание: Создана ограниченная роль с правом USERS:READ.
    And a test user in the restricted role exists
    # что: Создаём активного тест-пользователя в ограниченной роли (прямая вставка в БД).
    # ожидание: Пользователь создан и готов к UI-входу.
    When I log in through the UI as the test user
    # что: Входим через UI под тест-пользователем.
    # ожидание: Тест-пользователь аутентифицирован, оболочка под его ролью.
    And I open the deep-link "/roles"
    # что: Открываем диплинк на запрещённый маршрут /roles.
    # ожидание: Выполняется переход/редирект по маршруту /roles.
    Then I am shown the forbidden page
    # что: Проверяем страницу запрета доступа.
    # ожидание: Открыта страница /403 с заголовком «Нет доступа/Brak dostępu».
    When I click Go Back on the forbidden page
    # что: Нажимаем «Вернуться назад» на странице /403.
    # ожидание: Клик по кнопке возврата выполнен.
    Then I am taken away from the forbidden page
    # что: Проверяем уход со страницы /403.
    # ожидание: Пользователь покинул маршрут /403.
