@detailed @FOR-QA-AUTO-05
Feature: URL-driven active tab with per-project memory
  URL-driven активная вкладка и память по проекту: URL побеждает память, клик пишет память, голый маршрут восстанавливает, неизвестная вкладка нормализуется, память независима по проектам.

  # FOR-QA-AUTO-05 detailed slice mirroring FOR-05-01 test-cases.md, Фича 9.
  # Приоритет резолвинга: валидная разрешённая вкладка из URL > память проекта > overview.
  # Сидовый ADMIN обходит ABAC, поэтому для него валидны все design-вкладки; в сценариях памяти
  # используем стабильные design-вкладки rooms и readiness. Значения procurement (execution) и
  # garbage (несуществующая) для design-проекта заведомо невалидны и служат для проверки
  # нормализации к overview.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда.
    # ожидание: Бэкенд и фронтенд отвечают.
    And I am logged in as the seeded admin
    # что: Входим сидовым администратором.
    # ожидание: Оболочка открыта.

  Scenario: TC-09-01 a valid permitted tab from the URL beats stored memory
    Валидная разрешённая вкладка из URL побеждает сохранённую в памяти вкладку.
    Given a design-stage project exists
    # что: Создаём design-проект.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    And the stored tab for the design project is "readiness"
    # что: Записываем в память проекта foremen.projectTab.{PID_DESIGN} = readiness (валидна и разрешена).
    # ожидание: Память проекта содержит readiness.
    When I open the design project tab "rooms"
    # что: Открываем прямой линк /projects/{PID_DESIGN}/rooms (валидная разрешённая вкладка, ROOMS засижен).
    # ожидание: URL выигрывает над памятью.
    Then the workspace tab "rooms" is active
    # что: Проверяем активную вкладку.
    # ожидание: Активна rooms, несмотря на сохранённую readiness.
    And the workspace URL ends with "rooms" for the design project
    # что: Проверяем URL.
    # ожидание: URL остаётся /projects/{PID_DESIGN}/rooms без нормализующего replace.

  Scenario: TC-09-02 switching a tab updates the URL and writes per-project memory
    Переключение вкладки обновляет URL и пишет память проекта.
    Given a design-stage project exists
    # что: Создаём design-проект.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Память вкладок пуста.
    When I open the design project overview tab
    # что: Открываем /projects/{PID_DESIGN}/overview.
    # ожидание: Активна overview.
    And I click the workspace tab "rooms"
    # что: Кликаем вкладку «Помещения» (ROOMS засижен).
    # ожидание: URL стал /projects/{PID_DESIGN}/rooms.
    Then the workspace URL ends with "rooms" for the design project
    # что: Проверяем URL.
    # ожидание: URL = /projects/{PID_DESIGN}/rooms.
    And the stored tab for the design project reads "rooms"
    # что: Читаем foremen.projectTab.{PID_DESIGN}.
    # ожидание: Значение = rooms.

  Scenario: TC-09-03 the bare route restores the stored tab
    Голый маршрут восстанавливает сохранённую (валидную) вкладку.
    Given a design-stage project exists
    # что: Создаём design-проект.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    And the stored tab for the design project is "rooms"
    # что: Записываем память проекта foremen.projectTab.{PID_DESIGN} = rooms.
    # ожидание: Память содержит rooms (валидна и разрешена).
    When I open the design project bare route
    # что: Переходим на голый /projects/{PID_DESIGN}.
    # ожидание: URL нет → память валидна → берётся rooms.
    Then the workspace URL ends with "rooms" for the design project
    # что: Проверяем URL.
    # ожидание: Redirect (replace) на /projects/{PID_DESIGN}/rooms.

  Scenario: TC-09-04 an unknown or cross-stage tab normalizes to overview
    Неизвестная/кросс-стадийная вкладка нормализуется к overview без краха.
    Given a design-stage project exists
    # что: Создаём design-проект.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Память пуста.
    When I open the design project tab "procurement"
    # что: Открываем /projects/{PID_DESIGN}/procurement (execution-вкладка, невалидна для design).
    # ожидание: Резолвинг: память пуста → overview.
    Then the workspace URL ends with "overview" for the design project
    # что: Проверяем URL.
    # ожидание: Redirect (replace) на /projects/{PID_DESIGN}/overview.
    When I open the design project tab "garbage"
    # что: Открываем /projects/{PID_DESIGN}/garbage (несуществующий key).
    # ожидание: Нормализация к overview.
    Then the workspace URL ends with "overview" for the design project
    # что: Проверяем URL.
    # ожидание: Redirect (replace) на /projects/{PID_DESIGN}/overview.

  Scenario: TC-09-05 per-project tab memory is independent
    Память вкладок независима по проектам: PID_DESIGN и PID_OTHER помнят разные вкладки.
    Given a design-stage project exists
    # что: Создаём первый design-проект (PID_DESIGN).
    # ожидание: Проект создан.
    And another design-stage project exists
    # что: Создаём второй design-проект (PID_OTHER).
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Память вкладок пуста.
    When I open the design project overview tab
    # что: Открываем /projects/{PID_DESIGN}/overview.
    # ожидание: Активна overview.
    And I click the workspace tab "rooms"
    # что: Переключаемся на «Помещения» у PID_DESIGN (ROOMS засижен).
    # ожидание: foremen.projectTab.{PID_DESIGN} = rooms.
    Then the stored tab for the design project reads "rooms"
    # что: Читаем память PID_DESIGN.
    # ожидание: rooms.
    When I open the other project overview tab
    # что: Открываем /projects/{PID_OTHER}/overview.
    # ожидание: Активна overview у PID_OTHER.
    And I click the other project workspace tab "readiness"
    # что: Переключаемся на «Готовность» у PID_OTHER (readiness под PROJECTS/READ).
    # ожидание: foremen.projectTab.{PID_OTHER} = readiness.
    Then the stored tab for the other project reads "readiness"
    # что: Читаем память PID_OTHER.
    # ожидание: readiness.
    And the stored tab for the design project reads "rooms"
    # что: Повторно читаем память PID_DESIGN после работы с PID_OTHER.
    # ожидание: По-прежнему rooms — память PID_OTHER (readiness) не перезаписала память PID_DESIGN, ключи независимы.
    # Замечание по восстановлению через голый маршрут: на этом фронтенде голый /projects/:id всегда
    # резолвится относительно РАБОЧЕГО проекта (working project), поэтому голый маршрут к НЕ-рабочему
    # проекту редиректит на вкладку рабочего. Восстановление сохранённой вкладки через голый маршрут
    # для рабочего проекта уже покрыто TC-09-03; здесь независимость памяти доказывается раздельными
    # ключами foremen.projectTab.{PID_DESIGN}=rooms и foremen.projectTab.{PID_OTHER}=readiness.
