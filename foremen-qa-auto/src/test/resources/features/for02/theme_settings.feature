@smoke @FOR-02
Feature: Appearance settings — theme persistence
  Переключение темы оформления и её сохранение после перезагрузки.

  # FOR-02 admin-panel smoke coverage (Requirements 7.4, 4.1).
  # On the Appearance settings page, toggling the theme mode (dark <-> light) persists across a
  # full page reload: the selection is remembered, the preference is stored in localStorage, and
  # the applied theme (the `dark` class on <html>) survives the reload.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.
    And I am logged in as the seeded admin
    # что: Входим под сидовым администратором.
    # ожидание: Администратор аутентифицирован, открыта защищённая оболочка приложения.

  Scenario: Toggling the theme persists across a page reload
    When I open the Appearance settings page
    # что: Открываем страницу оформления (тема).
    # ожидание: Загрузилась страница «Оформление» с выбором темы.
    And I toggle the theme mode
    # что: Переключаем режим темы (светлая/тёмная) и сохраняем.
    # ожидание: Выбран противоположный режим темы и сохранён.
    Then the theme selection persists across a page reload
    # что: Перезагружаем страницу и проверяем сохранение темы.
    # ожидание: После перезагрузки выбранный режим темы сохранился (класс dark и localStorage совпадают).
