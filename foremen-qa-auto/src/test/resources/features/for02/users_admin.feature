@smoke @FOR-02
Feature: Users admin — list and create
  Список пользователей и создание пользователя с уникальным e-mail (с самоочисткой).

  # FOR-02 admin-panel smoke coverage (Requirements 7.3, 9.2, 4.1).
  # Report narration (feature description above, per-step "# что:"/"# ожидание:" below) is
  # authored inline in this Gherkin and lifted verbatim into the client report — no separate table.
  # The Users page renders the users list; creating a user with a run-unique generated email makes
  # it appear in the list. The created user is deactivated in teardown (self-cleaning).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.
    And I am logged in as the seeded admin
    # что: Входим под сидовым администратором.
    # ожидание: Администратор аутентифицирован, открыта защищённая оболочка приложения.

  Scenario: The users list renders and a created user appears in it
    When I open the Users page
    # что: Открываем страницу «Пользователи».
    # ожидание: Загрузилась страница управления пользователями.
    Then the users list renders
    # что: Проверяем отрисовку списка пользователей.
    # ожидание: Таблица пользователей отображается с колонками (Имя, E-mail, Роль, Статус).
    When I create a user with a generated email
    # что: Создаём пользователя с уникальным сгенерированным e-mail (имя, язык, роль).
    # ожидание: Пользователь создан, лист-панель закрылась без ошибок.
    Then the created user appears in the users list
    # что: Ищем созданного пользователя в списке.
    # ожидание: Строка с созданным пользователем присутствует в таблице.
