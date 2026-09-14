@smoke @FOR-04
Feature: Projects — list and create happy path
  Проекты: список и создание проекта по основному сценарию.

  # FOR-04 base-entities smoke coverage (Requirements 8.4, 4.1, 9.2).
  # The Projects page renders the list; creating a project via the happy path (name only) makes it
  # appear in the list. The created project is deleted in teardown (self-cleaning, Requirement 3).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.
    And I am logged in as the seeded admin
    # что: Входим под сидовым администратором.
    # ожидание: Администратор аутентифицирован, открыта защищённая оболочка приложения.

  Scenario: The projects list renders and a created project appears in it
    When I open the Projects page
    # что: Открываем «Проекты».
    # ожидание: Загрузилась страница проектов.
    Then the projects list renders
    # что: Проверяем отрисовку списка проектов.
    # ожидание: Список проектов отображается.
    When I create a project via the happy path
    # что: Создаём проект по основному сценарию.
    # ожидание: Проект создан.
    Then the created project appears in the projects list
    # что: Ищем созданный проект в списке.
    # ожидание: Созданный проект присутствует в списке.
