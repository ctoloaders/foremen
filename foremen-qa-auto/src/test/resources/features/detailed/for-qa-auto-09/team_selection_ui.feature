@detailed @FOR-QA-AUTO-09 @ui
Feature: Подбор и назначение команды проекта — UI вкладки Team (FOR-05-09)
  Браузерные сценарии вкладки «Команда» воркспейса проекта.
  Данные засеяны через API; проверяем роутинг, три блока, предупреждения, фильтр тегов, readiness и i18n.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Frontend на :3000 и backend на :8080 доступны.
    And I am logged in as the seeded admin
    # что: Логинимся администратором в браузере.
    # ожидание: Открыт AppShell, сессия администратора активна.

  Scenario: TC-UI-TAB-01 — вкладка Team доступна по маршруту и отрисована
    Проверка роутинга вкладки Team и её отрисовки.
    Given a team project with a manager and a client exists for the UI
    # что: Готовим design-проект с менеджером и клиентом через API.
    # ожидание: Проект создан.
    When I open the team tab
    # что: Переходим на /projects/{id}/team.
    # ожидание: Вкладка Team отрисована.
    Then the team tab is rendered
    # что: Проверяем отрисовку контейнера вкладки.
    # ожидание: Контейнер team-tab виден.
    And the workspace URL ends with "/team"
    # что: Проверяем сегмент URL.
    # ожидание: URL оканчивается на /team.
    And the team tab shows no raw i18n keys
    # что: Проверяем отсутствие сырых ключей i18n.
    # ожидание: Нет team.block.* / team.column.* / workspace.tab.* на экране.

  Scenario: TC-UI-VIEW-01 — три блока с заголовками и счётчиками, в порядке
    Проверка трёх блоков ADMIN_STAFF → WORKERS → CLIENTS и их счётчиков.
    Given a team project with a manager and a client exists for the UI
    # что: Готовим проект (1 менеджер + 1 клиент).
    # ожидание: Проект создан.
    When I open the team tab
    # что: Открываем вкладку Team.
    # ожидание: Вкладка отрисована.
    Then the three team blocks are rendered in order
    # что: Проверяем наличие и DOM-порядок трёх блоков.
    # ожидание: ADMIN_STAFF выше WORKERS выше CLIENTS.
    And the "ADMIN_STAFF" block count is 1
    # что: Проверяем счётчик админ-персонала.
    # ожидание: (1) — один менеджер.
    And the "CLIENTS" block count is 1
    # что: Проверяем счётчик клиентов.
    # ожидание: (1) — один клиент.

  Scenario: TC-UI-VIEW-02 — hint о недостающем активном прорабе
    Проверка подсказки «нужен активный прораб» при нуле ACTIVE FOREMAN.
    Given a team project with a manager and a client exists for the UI
    # что: Готовим проект без ACTIVE FOREMAN.
    # ожидание: Проект создан.
    When I open the team tab
    # что: Открываем вкладку Team.
    # ожидание: Вкладка отрисована.
    Then the foreman readiness hint is shown
    # что: Проверяем hint в блоке ADMIN_STAFF.
    # ожидание: Виден team-foreman-hint.

  Scenario: TC-UI-WT-01 — бейдж и блок-уровневое предупреждение о недостающем типе
    Проверка индикации Uncategorized_Worker.
    Given a team project with a manager and a client exists for the UI
    # что: Готовим проект.
    # ожидание: Проект создан.
    And the project has an uncategorized worker
    # что: Назначаем WORKER без типа через API.
    # ожидание: В команде есть Uncategorized_Worker.
    When I open the team tab
    # что: Открываем вкладку Team под Internal_Attribute_Viewer (ADMIN).
    # ожидание: Вкладка отрисована.
    Then the worker-type-missing badge is shown
    # что: Проверяем бейдж на строке/карточке воркера.
    # ожидание: Виден worker-type-missing.
    And the block-level missing worker-type warning is shown
    # что: Проверяем блок-уровневое предупреждение WORKERS.
    # ожидание: Виден team-missing-worker-type-warning.

  Scenario: TC-UI-WT-02 — inline «Назначить тип работника» убирает предупреждение
    Проверка назначения типа работника через UI и исчезновения бейджа.
    Given a team project with a manager and a client exists for the UI
    # что: Готовим проект.
    # ожидание: Проект создан.
    And the project has an uncategorized worker
    # что: Назначаем WORKER без типа через API.
    # ожидание: В команде есть Uncategorized_Worker.
    When I open the team tab
    # что: Открываем вкладку Team.
    # ожидание: Вкладка отрисована, виден бейдж отсутствующего типа.
    And I assign a worker type to the uncategorized worker through the UI
    # что: Через меню члена открываем диалог и назначаем активный тип.
    # ожидание: PATCH сохраняет тип, диалог закрывается.
    Then the worker-type-missing badge is no longer shown
    # что: Проверяем, что бейдж отсутствующего типа исчез без перезагрузки.
    # ожидание: worker-type-missing отсутствует.

  Scenario: TC-UI-TAGFILTER-01 — фильтр по тегам и состояние «нет совпадений»
    Проверка single-select фильтра тегов и фильтрации блоков.
    Given a team project with a manager and a client exists for the UI
    # что: Готовим проект.
    # ожидание: Проект создан.
    And the project has an uncategorized worker
    # что: Назначаем WORKER с тегом «vip» через API.
    # ожидание: У члена есть тег для фильтра.
    When I open the team tab
    # что: Открываем вкладку Team.
    # ожидание: Вкладка отрисована.
    Then the tag filter is shown
    # что: Проверяем наличие контрола фильтра тегов.
    # ожидание: Виден team-tag-filter.
    When I filter the team by tag "vip"
    # что: Выбираем тег «vip» в фильтре.
    # ожидание: Показаны только члены с тегом vip.
    Then the "ADMIN_STAFF" block shows no matching members
    # что: Проверяем состояние блока без совпадений.
    # ожидание: В ADMIN_STAFF виден team-no-match (у менеджера нет тега vip).

  Scenario: TC-UI-READY-01 — chip гейта team в ReadinessWidget
    Проверка readiness-виджета и чипа гейта команды на design-стадии.
    Given a team project with a manager and a client exists for the UI
    # что: Готовим design-проект.
    # ожидание: Проект создан.
    When I open the team tab
    # что: Открываем воркспейс design-проекта.
    # ожидание: Вкладка Team отрисована, виден виджет готовности.
    Then the readiness widget is shown
    # что: Проверяем наличие ReadinessWidget.
    # ожидание: Виден workspace-readiness-widget.
    And the readiness widget shows a team gate chip
    # что: Проверяем чип гейта команды с локализованным именем.
    # ожидание: В виджете есть «Команда»/«Zespół».

  Scenario: TC-UI-CREATE-01 — форма создания проекта: hint о добавлении воркеров во вкладке Team
    Проверка admin-staff-only подсказки в форме создания проекта.
    When I open the project create form
    # что: Открываем правую панель создания проекта.
    # ожидание: Форма создания открыта.
    Then the create form shows the team workers hint
    # что: Проверяем видимый hint про добавление воркеров во вкладке Team.
    # ожидание: Виден team-workers-hint с локализованным текстом (не сырой ключ).
