@smoke @FOR-03
Feature: Employee password login
  Вход по e-mail и паролю: успешный логин, клиентская валидация и ошибка неверных данных.

  # FOR-03 authentication smoke coverage (Requirements 5.1, 5.2, 5.3).
  # Validates the core email + password sign-in on /login: a valid admin login lands on / with both
  # tokens stored, empty fields are blocked client-side without hitting the API, and invalid
  # credentials surface a localized error while keeping the user on /login.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).
    # ожидание: Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.
    And I am on the login page
    # что: Открываем страницу входа /login.
    # ожидание: Отрисована форма входа (поля E-mail, Пароль, кнопка входа).

  Scenario: Admin logs in with valid credentials and tokens are stored
    When I log in with the seeded admin credentials
    # что: Вводим учётные данные администратора и отправляем форму.
    # ожидание: Форма принята, выполняется переход с /login.
    Then I land on the home page
    # что: Дожидаемся перехода на главную.
    # ожидание: URL сменился на /, отрисована оболочка приложения.
    And access and refresh tokens are stored
    # что: Проверяем сохранение токенов сессии.
    # ожидание: В localStorage присутствуют access- и refresh-токены.

  Scenario: Empty fields are blocked by client validation with no request
    When I submit the login form without filling any field
    # что: Отправляем форму входа с пустыми полями.
    # ожидание: Клиентская валидация блокирует отправку, запрос на сервер не уходит.
    Then submission is blocked by client validation
    # что: Проверяем срабатывание клиентской валидации.
    # ожидание: Показаны сообщения о обязательных полях, форма не отправлена.
    And no login request is sent
    # что: Проверяем сетевую активность формы.
    # ожидание: Запрос POST /api/auth/login не отправлялся.
    And I stay on the login page
    # что: Проверяем, что остаёмся на странице входа.
    # ожидание: URL по-прежнему /login.

  Scenario: Invalid credentials show a localized error and stay on login
    When I log in with invalid credentials
    # что: Вводим неверные учётные данные и отправляем форму.
    # ожидание: Сервер отвечает 401, форма показывает ошибку.
    Then a localized login error message is shown
    # что: Проверяем сообщение об ошибке входа.
    # ожидание: На форме отображается локализованное сообщение о неверных учётных данных.
    And I stay on the login page
    # что: Проверяем, что остаёмся на странице входа.
    # ожидание: URL по-прежнему /login.
    And no tokens are stored
    # что: Проверяем отсутствие токенов.
    # ожидание: В localStorage нет access/refresh токенов.
