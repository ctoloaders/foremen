@smoke @FOR-03
Feature: Protected deep-link guard round-trip
  Гард защищённых диплинков: редирект на вход и возврат на исходный маршрут после логина.

  # FOR-03 authentication smoke coverage (Requirement 5.4).
  # An unauthenticated user who opens a protected deep-link is redirected to /login; after a
  # successful login they are returned to the original deep-link (Return_Location round-trip).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.

  Scenario: Unauthenticated deep-link redirects to login then returns after login
    When I open the protected deep-link "/users" while unauthenticated
    # что: Без сессии открываем защищённый диплинк /users.
    # ожидание: Гард перенаправляет на /login, исходный маршрут /users запомнен.
    Then I am redirected to the login page
    # что: Проверяем редирект на вход.
    # ожидание: Произошёл переход на /login.
    When I log in with the seeded admin credentials
    # что: Вводим учётные данные администратора и отправляем форму.
    # ожидание: Форма принята, выполняется переход с /login.
    Then I am returned to the deep-link "/users"
    # что: После входа проверяем возврат на исходный маршрут.
    # ожидание: Приложение вернулось на /users.
