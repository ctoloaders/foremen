@detailed @FOR-QA-AUTO-05
Feature: Working-project surface
  Всегда-видимая поверхность рабочего проекта: кнопка сайдбара (desktop), чип TopBar (mobile), взаимоисключение и мгновенная синхронизация.

  # FOR-QA-AUTO-05 detailed slice mirroring FOR-05-01 test-cases.md, Фичи 3 и 4.
  # Приоритетный сценарий FIX-1 — TC-04-05 (мгновенное обновление поверхности без перезагрузки).
  # Поверхность не имеет testid: локаторы по RU-тексту, скоуп aside (desktop) / header (mobile).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность стенда.
    # ожидание: Бэкенд и фронтенд отвечают.
    And I am logged in as the seeded admin
    # что: Входим сидовым администратором.
    # ожидание: Оболочка приложения открыта.

  Scenario: TC-03-01 and TC-03-02 desktop sidebar button shows name+status and opens the workspace
    На desktop кнопка рабочего проекта показывает имя+статус и по клику открывает рабочее пространство.
    Given a design-stage project exists
    # что: Создаём design-проект через API.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Рабочий проект не установлен.
    And the viewport is desktop
    # что: Ставим ширину окна desktop (1440×900).
    # ожидание: Отображается сайдбар.
    When I open the projects list
    # что: Открываем /projects.
    # ожидание: Список проектов отрисован.
    And I click the design project row
    # что: Кликаем строку design-проекта.
    # ожидание: Установлен рабочий проект, открыто рабочее пространство.
    Then the desktop working-project button shows the design project name
    # что: Проверяем содержимое кнопки в сайдбаре.
    # ожидание: Кнопка показывает имя design-проекта.
    And the desktop working-project button shows the working caption
    # что: Проверяем подпись «Рабочий проект».
    # ожидание: Подпись workspace.workingProject отображается.
    When I click the desktop working-project button
    # что: Кликаем кнопку рабочего проекта.
    # ожидание: Навигация в рабочее пространство проекта.
    Then the browser is on the design project workspace
    # что: Проверяем URL.
    # ожидание: URL нормализован до /projects/{PID_DESIGN}/:tab.

  Scenario: TC-03-03 with no working project the sidebar shows the choose affordance
    Без рабочего проекта кнопка сайдбара показывает «Выбрать проект» и ведёт в список.
    Given the working-project and tab memory is cleared
    # что: Чистим localStorage (рабочего проекта нет).
    # ожидание: Рабочий проект отсутствует.
    And the viewport is desktop
    # что: Ставим desktop-ширину.
    # ожидание: Сайдбар виден.
    When I open the projects list
    # что: Открываем /projects.
    # ожидание: Оболочка с сайдбаром отрисована.
    Then the working-project surface shows the choose affordance
    # что: Проверяем текст кнопки рабочего проекта.
    # ожидание: Показан workspace.chooseProject (RU: «Выбрать проект»).
    When I click the choose-project affordance
    # что: Кликаем «Выбрать проект».
    # ожидание: Навигация на список /projects.
    Then the browser is on the projects list
    # что: Проверяем URL.
    # ожидание: Открыт /projects.

  Scenario: TC-03-04 the change-project affordance opens the list
    Аффорданс «Сменить проект» при установленном рабочем проекте ведёт в список.
    Given a design-stage project exists
    # что: Создаём design-проект.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    And the viewport is desktop
    # что: Ставим desktop-ширину.
    # ожидание: Сайдбар виден.
    When I open the projects list
    # что: Открываем /projects.
    # ожидание: Список отрисован.
    And I click the design project row
    # что: Кликаем строку проекта, устанавливая рабочий проект.
    # ожидание: Рабочий проект установлен, кнопка показывает имя.
    Then the desktop working-project button shows the working caption
    # что: Убеждаемся, что рабочий проект установлен.
    # ожидание: Подпись «Рабочий проект» видна, значит доступен аффорданс «Сменить проект».
    When I click the change-project affordance
    # что: Кликаем «Сменить проект».
    # ожидание: Навигация на список /projects.
    Then the browser is on the projects list
    # что: Проверяем URL.
    # ожидание: Открыт /projects.

  Scenario: TC-04-01 and TC-04-02 mobile chip shows name and opens the workspace
    На mobile чип в TopBar показывает имя рабочего проекта и по клику открывает рабочее пространство.
    Given a design-stage project exists
    # что: Создаём design-проект.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    And the viewport is mobile
    # что: Ставим ширину окна mobile (390×844).
    # ожидание: Показан TopBar.
    When I open the projects list
    # что: Открываем /projects.
    # ожидание: Список отрисован.
    And I click the design project row
    # что: Кликаем строку проекта, устанавливая рабочий проект.
    # ожидание: Рабочий проект установлен.
    Then the mobile working-project chip shows the design project name
    # что: Проверяем чип в TopBar.
    # ожидание: Чип показывает имя design-проекта (статус-точка + усечённое имя).
    When I click the mobile working-project chip
    # что: Кликаем чип рабочего проекта.
    # ожидание: Навигация в рабочее пространство.
    Then the browser is on the design project workspace
    # что: Проверяем URL.
    # ожидание: URL нормализован до /projects/{PID_DESIGN}/:tab.

  Scenario: TC-04-03 with no working project the mobile chip shows the choose affordance
    Без рабочего проекта чип на mobile показывает «Выбрать проект» и ведёт в список.
    Given the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Рабочего проекта нет.
    And the viewport is mobile
    # что: Ставим mobile-ширину.
    # ожидание: Показан TopBar.
    When I open the projects list
    # что: Открываем /projects.
    # ожидание: Оболочка отрисована.
    Then the working-project surface shows the choose affordance
    # что: Проверяем чип.
    # ожидание: Показан workspace.chooseProject (RU: «Выбрать проект»).
    When I click the choose-project affordance
    # что: Кликаем «Выбрать проект».
    # ожидание: Навигация на /projects.
    Then the browser is on the projects list
    # что: Проверяем URL.
    # ожидание: Открыт /projects.

  Scenario: TC-04-04 the surfaces are mutually exclusive by breakpoint
    Взаимоисключение поверхностей: на desktop видна кнопка сайдбара (не чип), на mobile виден чип (не кнопка).
    Given a design-stage project exists
    # что: Создаём design-проект.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    And the viewport is desktop
    # что: Ставим desktop-ширину.
    # ожидание: Сайдбар виден.
    When I open the projects list
    # что: Открываем /projects.
    # ожидание: Список отрисован.
    And I click the design project row
    # что: Устанавливаем рабочий проект кликом по строке.
    # ожидание: Рабочий проект установлен.
    Then the desktop working-project button is present
    # что: Проверяем кнопку сайдбара на desktop.
    # ожидание: Кнопка присутствует.
    And the mobile working-project chip is not shown
    # что: Проверяем отсутствие чипа TopBar на desktop.
    # ожидание: Чип не отрисован при desktop-ширине.
    Given the viewport is mobile
    # что: Сужаем окно до mobile.
    # ожидание: Раскладка переключилась на mobile.
    When I open the design project workspace
    # что: Открываем рабочее пространство, чтобы перерисовать оболочку в mobile-режиме.
    # ожидание: Оболочка перерисована в mobile.
    Then the mobile working-project chip is present
    # что: Проверяем чип TopBar на mobile.
    # ожидание: Чип виден.
    And the desktop working-project button is not shown
    # что: Проверяем отсутствие кнопки сайдбара на mobile.
    # ожидание: Кнопка сайдбара не отрисована при mobile-ширине.

  Scenario: TC-04-05 selecting a row updates the working-project surface immediately without reload
    FIX-1: выбор проекта из списка мгновенно обновляет поверхность рабочего проекта БЕЗ перезагрузки; выбор другого — переключает сразу.
    Given a design-stage project exists
    # что: Создаём первый design-проект (PID_DESIGN).
    # ожидание: Проект создан.
    And another design-stage project exists
    # что: Создаём второй проект (PID_OTHER).
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage (рабочего проекта нет).
    # ожидание: Рабочий проект отсутствует.
    And the viewport is desktop
    # что: Ставим desktop-ширину.
    # ожидание: Сайдбар виден.
    When I open the projects list
    # что: Открываем /projects при пустом рабочем проекте.
    # ожидание: Поверхность показывает «Выбрать проект».
    Then the working-project surface shows the choose affordance
    # что: Проверяем стартовое состояние поверхности.
    # ожидание: Показан workspace.chooseProject.
    When I click the design project row
    # что: Кликаем строку PID_DESIGN.
    # ожидание: Общий store useWorkingProject уведомляет поверхности синхронно.
    Then the desktop working-project button shows the design project name
    # что: Проверяем поверхность сразу после клика (без reload).
    # ожидание: Кнопка мгновенно показывает имя PID_DESIGN.
    When I open the projects list
    # что: Возвращаемся на /projects.
    # ожидание: Список отрисован.
    And I click the other project row
    # что: Кликаем строку PID_OTHER.
    # ожидание: Поверхность переключается на другой проект синхронно.
    Then the desktop working-project button shows the other project name
    # что: Проверяем поверхность после выбора другого проекта (без reload).
    # ожидание: Кнопка мгновенно показывает имя PID_OTHER.

  Scenario: TC-02-03 the working project survives a reload
    Рабочий проект переживает перезагрузку страницы (восстанавливается из localStorage).
    Given a design-stage project exists
    # что: Создаём design-проект.
    # ожидание: Проект создан.
    And the working-project and tab memory is cleared
    # что: Чистим localStorage.
    # ожидание: Состояние чистое.
    And the viewport is desktop
    # что: Ставим desktop-ширину.
    # ожидание: Сайдбар виден.
    When I open the projects list
    # что: Открываем /projects.
    # ожидание: Список отрисован.
    And I click the design project row
    # что: Устанавливаем рабочий проект.
    # ожидание: foremen.workingProjectId = PID_DESIGN.
    Then the stored working project is the design project
    # что: Читаем localStorage['foremen.workingProjectId'].
    # ожидание: Значение равно id design-проекта.
    When I reload the page
    # что: Перезагружаем страницу (F5).
    # ожидание: Рабочий проект восстановлен из localStorage.
    Then the desktop working-project button shows the design project name
    # что: Проверяем поверхность после перезагрузки.
    # ожидание: Показан тот же рабочий проект PID_DESIGN.
