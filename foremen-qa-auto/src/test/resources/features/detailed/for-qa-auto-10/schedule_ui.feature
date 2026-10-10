@detailed @FOR-QA-AUTO-10 @ui
Feature: Планировочный график проекта (Harmonogram) — UI вкладки «График» (FOR-05-10)
  Браузерные сценарии вкладки «Harmonogram / График» воркспейса проекта и chip готовности в ReadinessWidget.
  Данные (проект, смета, команда) засеяны через API и браузер; проверяем роутинг, просмотр, редактирование, автосоздание, маскирование денег, chip готовности и i18n.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Frontend на :3000 и backend на :8080 доступны.

  Scenario: TC-UI-TAB-01 — вкладка «График» видна, открывается и имеет корректный URL
    TC-UI-TAB-01: роутинг вкладки scheduleDesign и её отрисовка.
    Given a scheduling project with a dated anchor and two active workers exists
    # что: Готовим design-проект (якорь 2026-11-02, два активных работника) через API.
    # ожидание: Проект создан.
    And the project estimate has work lines from an applied package
    # что: Через браузер применяем пакет к смете — у графика появляются строки-категории.
    # ожидание: Schedule_API отдаёт минимум одну строку.
    When I open the project workspace
    # что: Открываем /projects/{id} под администратором.
    # ожидание: Воркспейс и лента табов отрисованы.
    Then the schedule tab is visible in the tab strip
    # что: Проверяем наличие таба «Harmonogram» в ленте.
    # ожидание: Таб scheduleDesign присутствует.
    When I click the schedule tab in the strip
    # что: Кликаем по табу «Harmonogram».
    # ожидание: Открывается график.
    Then the schedule workspace URL ends with "/scheduleDesign"
    # что: Проверяем сегмент URL.
    # ожидание: URL оканчивается на /scheduleDesign.
    And the schedule tab is rendered
    # что: Проверяем отрисовку контейнера вкладки.
    # ожидание: Контейнер schedule-tab виден.
    And the schedule tab shows no raw i18n keys
    # что: Проверяем отсутствие сырых ключей i18n.
    # ожидание: На экране нет projectSchedule.* ключей.

  Scenario: TC-UI-VIEW-01 — строки, объём, недельный вид, календарь
    TC-UI-VIEW-01: строки = категории сметы, объём, недельная шкала, выходные.
    Given a scheduling project with a dated anchor and two active workers exists
    # что: Готовим проект с якорем и двумя работниками.
    # ожидание: Проект создан.
    And the project estimate has work lines from an applied package
    # что: Применяем пакет — появляются строки графика.
    # ожидание: В графике есть строки.
    When I open the schedule tab
    # что: Открываем вкладку «График».
    # ожидание: Вкладка отрисована.
    Then the schedule shows one row per estimate category
    # что: Проверяем, что строки соответствуют категориям сметы.
    # ожидание: Минимум одна строка-категория.
    And each schedule row shows its line count and planned duration without man-days or rate
    # что: Проверяем объём каждой строки и отсутствие человеко-дней/ставки.
    # ожидание: У строки есть число позиций; нигде нет man-days/ставки.
    And the week view header is shown with the calendar legend
    # что: Проверяем недельный вид по умолчанию и легенду.
    # ожидание: Активен «Tygodnie», видны заголовок недель и легенда.
    And weekend cells are shaded
    # что: Проверяем штриховку выходных на шкале с якорем.
    # ожидание: Есть заштрихованные ячейки выходных.
    When I switch the schedule to day view
    # что: Переключаем на вид «Dni».
    # ожидание: Шкала переключилась на дни.
    Then the day view header is shown
    # что: Проверяем дневной заголовок.
    # ожидание: Активен «Dni», виден дневной заголовок.

  Scenario: TC-UI-VIEW-02 — деньги видны администратору (Money_Viewer)
    TC-UI-VIEW-02: суммы категорий отображаются для роли с правом на деньги.
    Given a scheduling project with a dated anchor and two active workers exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    And the project estimate has work lines from an applied package
    # что: Применяем пакет.
    # ожидание: В графике есть строки.
    When I open the schedule tab
    # что: Открываем вкладку под администратором (Money_Viewer).
    # ожидание: Вкладка отрисована.
    Then the schedule money columns are shown
    # что: Проверяем наличие сумм категорий.
    # ожидание: В строках показаны суммы нетто.
    And the schedule summary shows the active crew size
    # что: Проверяем итог с числом активных работников.
    # ожидание: В сводке показан размер команды.

  Scenario: TC-UI-VIEW-02b — деньги скрыты для прораба
    TC-UI-VIEW-02: суммы скрыты для роли без права на деньги (FOREMAN), только чтение.
    Given a test user with role "FOREMAN" exists
    # что: Создаём активного прораба.
    # ожидание: Пользователь создан.
    And a scheduling project with a dated anchor and two active workers exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    And the project estimate has work lines from an applied package
    # что: Применяем пакет.
    # ожидание: В графике есть строки.
    And the test user is a member of the scheduling project
    # что: Добавляем прораба в команду проекта (иначе 404 по доступу).
    # ожидание: Прораб — член проекта.
    When I sign out of the browser
    # что: Выходим из сессии администратора (чистим токены).
    # ожидание: Браузер не аутентифицирован.
    And I log in through the UI as the test user
    # что: Логинимся прорабом в браузере.
    # ожидание: Сессия прораба активна.
    And I open the schedule tab
    # что: Открываем вкладку «График».
    # ожидание: Вкладка отрисована.
    Then the schedule money columns are hidden
    # что: Проверяем отсутствие сумм для прораба.
    # ожидание: Сумм категорий нет.

  Scenario: TC-UI-VIEW-04 — пустая смета
    TC-UI-VIEW-04: пустое состояние, когда у проекта нет строк сметы.
    Given an empty scheduling project with no estimate exists
    # что: Готовим проект без сметы.
    # ожидание: Проект создан.
    And I am logged in as the seeded admin
    # что: Логинимся администратором в браузере.
    # ожидание: Сессия администратора активна.
    When I open the schedule tab
    # что: Открываем вкладку «График».
    # ожидание: Отрисовано состояние вкладки.
    Then the empty estimate state is shown
    # что: Проверяем пустое состояние.
    # ожидание: Показано «Brak prac w kosztorysie».

  Scenario: TC-UI-EDIT-05 — автосоздание из UI
    TC-UI-EDIT-05: диалог автосоздания и перестройка полос подряд.
    Given a scheduling project with a dated anchor and two active workers exists
    # что: Готовим проект с двумя работниками.
    # ожидание: Проект создан.
    And the project estimate has work lines from an applied package
    # что: Применяем пакет.
    # ожидание: В графике есть строки.
    When I open the schedule tab
    # что: Открываем вкладку «График».
    # ожидание: Вкладка отрисована.
    And I auto-create the schedule from the UI
    # что: Нажимаем «Utwórz automatycznie», подтверждаем в диалоге (пояснение словами, без ставки).
    # ожидание: Один POST /auto-create, полосы перестроены.
    Then the schedule has bars for every row
    # что: Проверяем готовность графика через API.
    # ожидание: Readiness = DONE (все строки с полосами).

  Scenario: TC-UI-EDIT-01 — перетаскивание (клавиатура) и сохранение
    TC-UI-EDIT-01: редактирование полосы, пометки черновика и сохранение одним PUT.
    Given a scheduling project with a dated anchor and two active workers exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    And the project estimate has work lines from an applied package
    # что: Применяем пакет.
    # ожидание: В графике есть строки.
    When I open the schedule tab
    # что: Открываем вкладку «График».
    # ожидание: Вкладка отрисована.
    And I auto-create the schedule from the UI
    # что: Автосоздаём график, чтобы были полосы для редактирования.
    # ожидание: Полосы созданы.
    And I move the first scheduled bar with the keyboard
    # что: Фокусируем полосу и жмём «Вправо» — сдвиг на день.
    # ожидание: Полоса сдвинута, черновик изменён.
    Then the schedule has unsaved changes
    # что: Проверяем пометки черновика и активные кнопки.
    # ожидание: Виден бейдж «Niezapisane zmiany», строка помечена «Zmieniono», «Zapisz» активна.
    When I save the schedule
    # что: Нажимаем «Zapisz».
    # ожидание: Изменения сохранены, бейдж исчезает.
    Then the schedule has no unsaved changes
    # что: Проверяем снятие пометок.
    # ожидание: Бейдж черновика отсутствует.

  Scenario: TC-UI-EDIT-02 — отмена черновика
    TC-UI-EDIT-02: «Odrzuć zmiany» возвращает полосу и очищает черновик.
    Given a scheduling project with a dated anchor and two active workers exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    And the project estimate has work lines from an applied package
    # что: Применяем пакет.
    # ожидание: В графике есть строки.
    When I open the schedule tab
    # что: Открываем вкладку «График».
    # ожидание: Вкладка отрисована.
    And I auto-create the schedule from the UI
    # что: Автосоздаём график для редактирования.
    # ожидание: Полосы созданы.
    And I move the first scheduled bar with the keyboard
    # что: Сдвигаем полосу клавиатурой.
    # ожидание: Черновик изменён.
    Then the schedule has unsaved changes
    # что: Проверяем пометки черновика.
    # ожидание: Виден бейдж черновика.
    When I discard the schedule changes
    # что: Нажимаем «Odrzuć zmiany».
    # ожидание: Черновик очищен.
    Then the schedule has no unsaved changes
    # что: Проверяем снятие пометок без запроса.
    # ожидание: Бейдж черновика отсутствует.

  Scenario: TC-UI-EDIT-03 — диалог редактирования и валидация
    TC-UI-EDIT-03: открытие диалога по Enter и валидация длительности.
    Given a scheduling project with a dated anchor and two active workers exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    And the project estimate has work lines from an applied package
    # что: Применяем пакет.
    # ожидание: В графике есть строки.
    When I open the schedule tab
    # что: Открываем вкладку «График».
    # ожидание: Вкладка отрисована.
    And I auto-create the schedule from the UI
    # что: Автосоздаём график, чтобы была полоса.
    # ожидание: Полосы созданы.
    And I open the bar edit dialog from the keyboard
    # что: Фокусируем полосу и жмём Enter.
    # ожидание: Открыт диалог «Okres prac».
    Then the bar edit dialog is shown
    # что: Проверяем отрисовку диалога.
    # ожидание: Диалог виден.
    When I enter an invalid duration of 0 in the bar dialog
    # что: Вводим длительность 0.
    # ожидание: Поле помечается ошибкой.
    Then the bar dialog shows a validation error and apply is blocked
    # что: Проверяем ошибку поля и блокировку «Zastosuj».
    # ожидание: Видна ошибка, кнопка «Zastosuj» неактивна.
    When I close the bar edit dialog
    # что: Закрываем диалог.
    # ожидание: Диалог закрыт.

  Scenario: TC-UI-EDIT-04 — убрать строку из графика
    TC-UI-EDIT-04: «Usuń z harmonogramu» делает строку незапланированной в черновике.
    Given a scheduling project with a dated anchor and two active workers exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    And the project estimate has work lines from an applied package
    # что: Применяем пакет.
    # ожидание: В графике есть строки.
    When I open the schedule tab
    # что: Открываем вкладку «График».
    # ожидание: Вкладка отрисована.
    And I auto-create the schedule from the UI
    # что: Автосоздаём график, чтобы строки имели полосы.
    # ожидание: Полосы созданы.
    And I clear the first scheduled row
    # что: Нажимаем «Usuń z harmonogramu» на первой запланированной строке.
    # ожидание: Полоса убрана в черновике.
    Then that row becomes unscheduled in the draft
    # что: Проверяем состояние строки.
    # ожидание: Строка показывает «Nie zaplanowano».

  Scenario: TC-UI-EDIT-05b — автосоздание недоступно без активных работников
    TC-UI-EDIT-05: кнопка автосоздания неактивна при нулевой команде.
    Given a scheduling project with a dated anchor and two active workers exists
    # что: Готовим проект с двумя работниками.
    # ожидание: Проект создан.
    And the project estimate has work lines from an applied package
    # что: Применяем пакет.
    # ожидание: В графике есть строки.
    When I deactivate both workers
    # что: Деактивируем обоих работников через API.
    # ожидание: Активных работников нет (crewSize=0).
    And I open the schedule tab
    # что: Открываем вкладку «График».
    # ожидание: Вкладка отрисована.
    Then the auto-create action is disabled
    # что: Проверяем кнопку автосоздания.
    # ожидание: «Utwórz automatycznie» неактивна (подсказка про «Zespół»).

  Scenario: TC-UI-EDIT-06 — только чтение для проекта в работе
    TC-UI-EDIT-06: баннер «только для чтения» в статусе ACTIVE.
    Given a scheduling project with a dated anchor and two active workers exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    And the project estimate has work lines from an applied package
    # что: Применяем пакет.
    # ожидание: В графике есть строки.
    And the project is moved to an active status
    # что: Переводим проект в ACTIVE через API (Schedule_Locked_Status).
    # ожидание: Статус проекта ACTIVE.
    When I open the schedule tab through the design selector
    # что: Открываем «График» через селектор табов проектирования (стадия исполнения).
    # ожидание: Вкладка отрисована.
    Then the schedule read-only banner is shown
    # что: Проверяем баннер только для чтения.
    # ожидание: Виден баннер «Harmonogram jest tylko do odczytu…».
    And the schedule shows no editing actions
    # что: Проверяем отсутствие ручек/кнопок редактирования.
    # ожидание: Нет интерактивных полос и кнопки «Zapisz».

  Scenario: TC-UI-READY-01 — chip готовности schedule в ReadinessWidget
    TC-UI-READY-01: виджет готовности показывает чип гейта графика.
    Given a scheduling project with a dated anchor and two active workers exists
    # что: Готовим design-проект.
    # ожидание: Проект создан.
    And the project estimate has work lines from an applied package
    # что: Применяем пакет.
    # ожидание: В графике есть строки.
    When I open the project workspace
    # что: Открываем воркспейс design-проекта.
    # ожидание: Виден виджет готовности.
    Then the readiness widget is shown
    # что: Проверяем наличие ReadinessWidget.
    # ожидание: Виден workspace-readiness-widget.
    And the readiness widget shows a schedule gate chip
    # что: Проверяем чип гейта графика с локализованным именем.
    # ожидание: В виджете есть «График»/«Harmonogram».
