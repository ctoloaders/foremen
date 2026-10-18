@detailed @FOR-QA-AUTO-06-01 @ui
Feature: Отчётность о выполненных работах — UI вкладки «Отчёты» (FOR-06-01)
  Браузерные сценарии вкладки workReporting воркспейса ACTIVE-проекта.
  Данные засеяны через API; проверяем роутинг, список и ревью бригадира, own-records воркера, и
  матричную поверхность пакетного внесения (Amendment A1): выбор воркера, ввод в ячейку, накопление
  черновика, массовое сохранение и мобильный схлопываемый черновик.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Frontend на :3000 и backend на :8080 доступны.

  Scenario: TC-UI-NAV-01 — вкладка «Отчёты» доступна бригадиру на ACTIVE-проекте
    Проверка роутинга и отрисовки панели отчётности, наличия действия «новый отчёт».
    Given a reportable active project with a crew and a priced estimate exists for the UI
    # что: Готовим ACTIVE-проект с командой, сметой и планами через API.
    # ожидание: Проект создан и переведён в ACTIVE.
    When the foreman opens the work reporting tab
    # что: Логинимся бригадиром и переходим на вкладку workReporting.
    # ожидание: Панель отчётности отрисована.
    Then the work reporting panel is rendered
    # что: Проверяем контейнер панели.
    # ожидание: work-reporting-panel виден.
    And the workspace URL ends with work reporting
    # что: Проверяем сегмент URL.
    # ожидание: URL оканчивается на /workReporting.
    And the new report action is visible
    # что: Проверяем кнопку «новый отчёт» (CREATE на ACTIVE).
    # ожидание: Виден work-reporting-new.

  Scenario: TC-UI-MATRIX-01 — «новый отчёт» открывает матрицу; выбор воркера грузит прогресс
    Проверка, что действие «новый отчёт» открывает матрицу (не старый билдер) и пикер воркера грузит прогресс.
    Given a reportable active project with a crew and a priced estimate exists for the UI
    # что: Готовим проект; у воркера есть покрывающая категория.
    # ожидание: Проект создан.
    When the foreman opens the work reporting tab
    # что: Логинимся бригадиром и открываем вкладку.
    # ожидание: Панель отрисована.
    And the foreman opens the matrix
    # что: Нажимаем «новый отчёт».
    # ожидание: Открывается матрица WorkReportMatrix.
    Then the matrix dialog is open
    # что: Проверяем контейнер матрицы.
    # ожидание: Виден work-report-matrix.
    When the foreman picks the worker in the matrix
    # что: Для бригадира показан пикер воркера; выбираем воркера.
    # ожидание: Матрица перезагружается из GET /progress.
    Then the matrix grid is rendered
    # что: Проверяем сетку матрицы.
    # ожидание: Виден work-report-matrix-grid (строки work items × комнаты).

  Scenario: TC-UI-MATRIX-02 — WORKER видит матрицу по себе без пикера
    Проверка, что воркер грузит матрицу по себе без пикера воркера (own-records, R17.1).
    Given a reportable active project with a crew and a priced estimate exists for the UI
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the worker opens the work reporting tab
    # что: Логинимся воркером и открываем вкладку.
    # ожидание: Панель отрисована.
    And the worker opens the matrix
    # что: Нажимаем «новый отчёт».
    # ожидание: Матрица открыта.
    Then the matrix dialog is open
    # что: Проверяем контейнер матрицы.
    # ожидание: Виден work-report-matrix.
    And the matrix has no worker picker
    # что: Проверяем отсутствие пикера воркера (грузится self).
    # ожидание: work-report-matrix-worker отсутствует.
    And the matrix grid is rendered
    # что: Проверяем, что прогресс воркера отрисован.
    # ожидание: Виден work-report-matrix-grid.

  Scenario: TC-UI-CELL-01 — попап ячейки: percent/absolute, единица, период, no-plan
    Проверка попапа ячейки: PERCENT-резолв и блок «нет плана» (R15.1, R15.3, R15.6).
    Given a reportable active project with a crew and a priced estimate exists for the UI
    # что: Готовим проект; комната A с планом 20, комната без плана.
    # ожидание: Проект создан.
    When the worker opens the work reporting tab
    # что: Логинимся воркером, открываем вкладку.
    # ожидание: Панель отрисована.
    And the worker opens the matrix
    # что: Открываем матрицу.
    # ожидание: Матрица открыта с сеткой.
    And the worker opens the cell for room A
    # что: Кликаем ячейку (work item, комната A).
    # ожидание: Открыт попап ячейки.
    Then the cell popup is open
    # что: Проверяем контейнер попапа.
    # ожидание: Виден work-report-cell-popup.
    When the worker enters a percent of 50 in the cell
    # что: Переключаем на PERCENT и вводим 50.
    # ожидание: Показан резолв 20 × 50 / 100 = 10.
    Then the cell popup shows the resolved preview
    # что: Проверяем превью резолва.
    # ожидание: Виден work-report-cell-resolved.
    When the worker confirms the cell entry
    # что: Подтверждаем ввод.
    # ожидание: Ячейка получает staged-бейдж.
    Then the cell for room A is staged
    # что: Проверяем staged-флаг ячейки.
    # ожидание: data-staged=true на ячейке.

  Scenario: TC-UI-DRAFT-A1-01 — накопление черновика; повтор ячейки заменяет строку
    Проверка панели черновика: строки, счётчик, замена строки при повторном вводе (R15.4, R17.3).
    Given a reportable active project with a crew and a priced estimate exists for the UI
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the worker opens the work reporting tab
    # что: Логинимся воркером, открываем вкладку.
    # ожидание: Панель отрисована.
    And the worker opens the matrix
    # что: Открываем матрицу.
    # ожидание: Матрица открыта.
    And the worker stages a cell for room A with 5 absolute
    # что: Вносим ячейку (work item, комната A) = 5 ABSOLUTE.
    # ожидание: Строка добавлена в черновик.
    And the worker stages a cell for room B with 3 absolute
    # что: Вносим ячейку (work item, комната B) = 3 ABSOLUTE.
    # ожидание: Вторая строка добавлена.
    Then the draft has 2 lines
    # что: Проверяем число строк черновика.
    # ожидание: В панели черновика 2 строки.
    When the worker stages the cell for room A again with 7 absolute
    # что: Повторно вносим ячейку комнаты A = 7.
    # ожидание: Строка этой ячейки заменена, не добавлена.
    Then the draft has 2 lines
    # что: Проверяем, что строк по-прежнему две.
    # ожидание: Одна staged-строка на ячейку (R15.4).
    When the worker removes the draft line for room B
    # что: Удаляем строку комнаты B из черновика.
    # ожидание: Счётчик уменьшается.
    Then the draft has 1 lines
    # что: Проверяем число строк после удаления.
    # ожидание: В черновике одна строка.

  Scenario: TC-UI-BATCH-01 — bulk save создаёт отчёты, прогресс обновляется, черновик очищается
    Проверка массового сохранения черновика через POST /batch (R16.1, R16.2, R16.5).
    Given a reportable active project with a crew and a priced estimate exists for the UI
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the worker opens the work reporting tab
    # что: Логинимся воркером, открываем вкладку.
    # ожидание: Панель отрисована.
    And the worker opens the matrix
    # что: Открываем матрицу.
    # ожидание: Матрица открыта.
    And the worker stages a cell for room A with 5 absolute
    # что: Вносим ячейку комнаты A = 5.
    # ожидание: Строка в черновике.
    And the worker stages a cell for room B with 3 absolute
    # что: Вносим ячейку комнаты B = 3.
    # ожидание: Вторая строка в черновике.
    When the worker saves the draft
    # что: Нажимаем Save — клиент группирует строки и шлёт POST /batch.
    # ожидание: Пакет создан, черновик очищается, прогресс рефетчится.
    Then the draft is empty after save
    # что: Проверяем очистку черновика после успеха.
    # ожидание: Черновик пуст (строк нет).

  Scenario: TC-UI-MOBILE-01 — мобильный схлопываемый черновик с reveal-контролем
    Проверка мобильного черновика: reveal справа + счётчик + выезжающий ящик (R18.2, R18.3).
    Given a reportable active project with a crew and a priced estimate exists for the UI
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the worker opens the work reporting tab
    # что: Логинимся воркером, открываем вкладку (десктоп-ширина, чтобы навигация не перекрывала).
    # ожидание: Панель отрисована.
    And the worker opens the matrix
    # что: Открываем матрицу.
    # ожидание: Матрица открыта.
    And the worker stages a cell for room A with 5 absolute
    # что: Вносим одну ячейку в черновик.
    # ожидание: Строка добавлена в черновик.
    And the worker switches to a mobile viewport
    # что: Переключаем на узкий мобильный вьюпорт 390×844 при открытой матрице.
    # ожидание: Панель черновика сворачивается в мобильный лейаут.
    Then the mobile draft reveal is visible
    # что: Проверяем reveal-контрол у правого края со счётчиком.
    # ожидание: Виден work-report-draft-reveal.
    When the worker opens the mobile draft drawer
    # что: Тапаем reveal.
    # ожидание: Выезжает ящик черновика (Sheet).
    Then the mobile draft drawer is open
    # что: Проверяем выезжающий ящик.
    # ожидание: Виден work-report-draft-sheet со строками и Save.

  Scenario: TC-UI-REVIEW-01 — бригадир утверждает SUBMITTED-отчёт
    Проверка действия approve из UI (список и ревью не затронуты амендментом).
    Given a reportable active project with a crew and a priced estimate exists for the UI
    # что: Готовим проект.
    # ожидание: Проект создан.
    And a submitted report by the worker exists
    # что: Через API создаём SUBMITTED-отчёт воркера.
    # ожидание: Отчёт в статусе SUBMITTED.
    When the foreman opens the work reporting tab
    # что: Логинимся бригадиром и открываем вкладку.
    # ожидание: Панель отрисована, виден отчёт.
    Then the foreman sees a review action on the submitted report
    # что: Проверяем действие ревью на строке SUBMITTED.
    # ожидание: Виден work-reporting-review.
    When the foreman opens the review and approves
    # что: Открываем ревью и нажимаем approve.
    # ожидание: Отчёт утверждён, диалог закрыт.
    Then the report is approved in the list
    # что: Проверяем, что панель перечитала список.
    # ожидание: Панель отрисована после approve.

  Scenario: TC-UI-REVIEW-02 — reject требует причину
    Проверка обязательности причины при отклонении из UI.
    Given a reportable active project with a crew and a priced estimate exists for the UI
    # что: Готовим проект.
    # ожидание: Проект создан.
    And a submitted report by the worker exists
    # что: Через API создаём SUBMITTED-отчёт.
    # ожидание: Отчёт SUBMITTED.
    When the foreman opens the work reporting tab
    # что: Логинимся бригадиром, открываем вкладку.
    # ожидание: Панель отрисована.
    And the foreman opens the review and rejects without a reason
    # что: Открываем ревью, выбираем reject, подтверждаем без причины.
    # ожидание: Поле причины обязательно, запрос не уходит.
    Then the review reason is required
    # что: Проверяем, что диалог открыт и поле причины показано.
    # ожидание: Виден work-report-review-reason-field, диалог не закрыт.

  Scenario: TC-UI-WORKER-SCOPE-01 — воркер видит только свои отчёты без ревью
    Проверка own-records видимости и отсутствия действий ревью у воркера.
    Given a reportable active project with a crew and a priced estimate exists for the UI
    # что: Готовим проект.
    # ожидание: Проект создан.
    And a submitted report by the worker exists
    # что: Через API создаём SUBMITTED-отчёт воркера.
    # ожидание: Отчёт SUBMITTED.
    When the worker opens the work reporting tab
    # что: Логинимся воркером, открываем вкладку.
    # ожидание: Панель отрисована.
    Then the worker sees only their own reports and no review actions
    # что: Проверяем отсутствие действий ревью у воркера.
    # ожидание: Нет work-reporting-review.

  # REG-A1-01 — старый одиночный билдер больше не является точкой входа — покрыт сценарием
  # TC-UI-MATRIX-01 (действие «новый отчёт» открывает матрицу work-report-matrix, а не билдер).
