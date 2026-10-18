@detailed @FOR-QA-AUTO-06-01 @api
Feature: Отчётность о выполненных работах — API (FOR-06-01)
  Проверка API отчётов о работах (/api/work-reports) против поднятого docker-стека.
  Создание и деривации, work matching, статус-машина, fast path бригадира, заморозка выручки,
  ABAC-матрица WORK_REPORTS и own-records воркера.

  # UI-сценарии (Часть B test-cases) исполняются браузерным движком и покрываются отдельным
  # UI-слайсом; здесь — API-часть A.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека и миграций FOR-06-01 (163–165).
    # ожидание: Backend на :8080 отвечает, таблицы work_reports засеяны.

  Scenario: TC-API-CREATE-01 — воркер создаёт ABSOLUTE-отчёт, SUBMITTED, деривации верны
    Проверка создания отчёта с абсолютными количествами и производных полей.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим ACTIVE-проект с командой, сметой и планами под уникальный run-id.
    # ожидание: Проект, смета (unitPrice=12.50) и планы комнат созданы.
    When the worker creates an absolute report in two rooms
    # что: Воркер создаёт отчёт: комната A=3.000, комната B=4.500 (ABSOLUTE), затем читаем его.
    # ожидание: HTTP 200, статус SUBMITTED.
    Then the work report API responds with HTTP 200
    # что: Проверяем код ответа чтения отчёта.
    # ожидание: Возвращается 200.
    And the report status is "SUBMITTED"
    # что: Проверяем начальный статус.
    # ожидание: status=SUBMITTED (не-бригадир).
    And the report total quantity is "7.500"
    # что: Проверяем totalQuantity = Σ quantity (3.000 + 4.500).
    # ожидание: totalQuantity=7.500.
    And the report client unit price is "12.50"
    # что: Проверяем замороженную цену из сметы.
    # ожидание: clientUnitPrice=12.50.
    And the report revenue value is "93.75"
    # что: Проверяем revenueValue = round2(12.50 × 7.500).
    # ожидание: revenueValue=93.75.

  Scenario: TC-API-CREATE-02 — PERCENT-строка резолвится в абсолют plan × percent / 100
    Проверка резолва процентной строки по плановому количеству.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект (план комнаты A = 20).
    # ожидание: Проект и план созданы.
    When the worker creates a percent report of 50 percent in the planned room
    # что: Воркер создаёт отчёт 50% от плана 20 в комнате A, затем читаем его.
    # ожидание: HTTP 200, резолв 20 × 50 / 100 = 10.
    Then the work report API responds with HTTP 200
    # что: Проверяем код ответа чтения отчёта.
    # ожидание: Возвращается 200.
    And the report total quantity is "10.000"
    # что: Проверяем резолвнутое количество.
    # ожидание: totalQuantity=10.000.
    And the report revenue value is "125.00"
    # что: Проверяем выручку (12.50 × 10).
    # ожидание: revenueValue=125.00.

  Scenario: TC-API-CREATE-03 — PERCENT без плана отклоняется; ABSOLUTE в ту же комнату проходит
    Проверка запрета процентного режима без планового количества.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект с комнатой без плана.
    # ожидание: Проект создан.
    When the worker creates a percent report in the room with no plan
    # что: Воркер создаёт PERCENT-отчёт в комнате без плана.
    # ожидание: HTTP 400 error.workReport.room.noPlan.
    Then the work report API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.
    When the worker creates an absolute report in the room with no plan
    # что: Воркер создаёт ABSOLUTE-отчёт в той же комнате без плана.
    # ожидание: HTTP 200 (абсолют допустим, planQuantity=null).
    Then the work report API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.

  Scenario: TC-API-CREATE-04 — дубликат комнаты отклоняется
    Проверка запрета повторной комнаты в одном отчёте.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the worker creates a report with a duplicate room
    # что: Воркер создаёт отчёт с двумя строками по одной комнате A.
    # ожидание: HTTP 400 error.workReport.room.duplicate.
    Then the work report API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.

  Scenario: TC-API-CREATE-05 — невалидное количество отклоняется
    Проверка отказа при нулевом и отрицательном количестве.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the worker creates a report with quantity "0"
    # что: Воркер создаёт отчёт с quantity=0.
    # ожидание: HTTP 400 error.workReport.quantity.invalid.
    Then the work report API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.
    When the worker creates a report with quantity "-3"
    # что: Воркер создаёт отчёт с quantity=-3.
    # ожидание: HTTP 400.
    Then the work report API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.

  Scenario: TC-API-MATCH-01 — work item вне категорий воркера отклоняется
    Проверка Covering_Work: воркер без покрывающей категории.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект; второй воркер без покрывающей категории.
    # ожидание: Проект создан.
    When the uncovered worker creates a report
    # что: Непокрытый воркер создаёт отчёт по work item.
    # ожидание: HTTP 400 error.workReport.workItem.notCovered.
    Then the work report API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.

  Scenario: TC-API-MATCH-02 — work item вне сметы отклоняется
    Проверка, что отчёт только по работам сметы.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the worker creates a report for a work item not on the estimate
    # что: Воркер создаёт отчёт по work item, которого нет на смете.
    # ожидание: HTTP 400 error.workReport.workItem.notInEstimate.
    Then the work report API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.

  Scenario: TC-API-STATUS-01 — approve SUBMITTED → APPROVED, approver задан
    Проверка перехода approve и установки approver/approvedAt.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the foreman approves the submitted report
    # что: Воркер создаёт SUBMITTED-отчёт, бригадир его утверждает.
    # ожидание: HTTP 200, статус APPROVED.
    Then the work report API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the report status is "APPROVED"
    # что: Проверяем статус после approve.
    # ожидание: status=APPROVED.
    And the report approver is set
    # что: Проверяем approvedById и approvedAt.
    # ожидание: approvedById и approvedAt заполнены.

  Scenario: TC-API-STATUS-02 — reject без причины отклоняется, с причиной → REJECTED
    Проверка обязательности причины отклонения.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the foreman rejects the submitted report without a reason
    # что: Бригадир отклоняет SUBMITTED-отчёт с пустой причиной.
    # ожидание: HTTP 400 error.workReport.rejection.reasonRequired.
    Then the work report API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.
    When the foreman rejects the submitted report with a reason
    # что: Бригадир отклоняет SUBMITTED-отчёт с непустой причиной.
    # ожидание: HTTP 200, статус REJECTED.
    Then the work report API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the report status is "REJECTED"
    # что: Проверяем статус после reject.
    # ожидание: status=REJECTED (терминальный).

  Scenario: TC-API-STATUS-03 — request-changes → CHANGES_REQUESTED, resubmit → SUBMITTED, approve
    Проверка цикла доработки: запрос изменений, повторная отправка и утверждение.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the author edits and resubmits the changes-requested report
    # что: Бригадир запрашивает изменения; автор правит и повторно отправляет.
    # ожидание: HTTP 200, статус возвращается в SUBMITTED.
    Then the work report API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the report status is "SUBMITTED"
    # что: Проверяем статус после resubmit.
    # ожидание: status=SUBMITTED.
    When the foreman approves the resubmitted report
    # что: Бригадир утверждает повторно отправленный отчёт.
    # ожидание: HTTP 200, статус APPROVED.
    Then the work report API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the report status is "APPROVED"
    # что: Проверяем финальный статус.
    # ожидание: status=APPROVED.

  Scenario: TC-API-STATUS-04 — запрещённые переходы отклоняются
    Проверка иммутабельности терминальных статусов.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the foreman tries to edit the approved report
    # что: Бригадир утверждает отчёт и пытается его отредактировать.
    # ожидание: HTTP 409 error.workReport.status.illegalTransition.
    Then the work report API responds with HTTP 409
    # что: Проверяем код ответа.
    # ожидание: Возвращается 409.
    When the foreman tries to approve a rejected report
    # что: Бригадир отклоняет отчёт и пытается его утвердить.
    # ожидание: HTTP 409 (REJECTED терминальный).
    Then the work report API responds with HTTP 409
    # что: Проверяем код ответа.
    # ожидание: Возвращается 409.

  Scenario: TC-API-STATUS-05 — воркер не может ревьюить
    Проверка, что воркер не утверждает даже свой отчёт.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the worker tries to approve their own submitted report
    # что: Воркер пытается утвердить собственный SUBMITTED-отчёт.
    # ожидание: HTTP 403.
    Then the work report API responds with HTTP 403
    # что: Проверяем код ответа.
    # ожидание: Возвращается 403.

  Scenario: TC-API-STATUS-06 — MANAGER не approver
    Проверка, что менеджер не утверждает отчёты.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the manager tries to approve the submitted report
    # что: Менеджер пытается утвердить SUBMITTED-отчёт.
    # ожидание: HTTP 403 (approver только FOREMAN/ADMIN).
    Then the work report API responds with HTTP 403
    # что: Проверяем код ответа.
    # ожидание: Возвращается 403.

  Scenario: TC-API-FAST-01 — самоотчёт бригадира сразу APPROVED
    Проверка fast path: отчёт бригадира о своей работе.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект; у бригадира есть покрывающая категория.
    # ожидание: Проект создан.
    When the foreman creates a report for their own work
    # что: Бригадир создаёт отчёт о своей работе (workerId=себя), затем читаем его.
    # ожидание: HTTP 200, статус сразу APPROVED.
    Then the work report API responds with HTTP 200
    # что: Проверяем код ответа чтения отчёта.
    # ожидание: Возвращается 200.
    And the report status is "APPROVED"
    # что: Проверяем мгновенное утверждение.
    # ожидание: status=APPROVED (без SUBMITTED).
    And the report approver is set
    # что: Проверяем approvedBy = автор.
    # ожидание: approvedById и approvedAt заполнены.

  Scenario: TC-API-FAST-02 — бригадир о работе другого воркера → тоже fast path
    Проверка fast path при отчёте бригадира за воркера.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект; воркер покрыт по категории.
    # ожидание: Проект создан.
    When the foreman creates a report for the worker
    # что: Бригадир создаёт отчёт о работе воркера (workerId=воркер), затем читаем его.
    # ожидание: HTTP 200, статус APPROVED.
    Then the work report API responds with HTTP 200
    # что: Проверяем код ответа чтения отчёта.
    # ожидание: Возвращается 200.
    And the report status is "APPROVED"
    # что: Проверяем fast path для чужой работы.
    # ожидание: status=APPROVED.

  Scenario: TC-API-FREEZE-01 — после approve выручка заморожена и читается
    Проверка, что замороженная выручка доступна на чтении.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the foreman approves the submitted report
    # что: Создаём и утверждаем отчёт.
    # ожидание: HTTP 200, APPROVED.
    Then the work report API responds with HTTP 200
    # что: Проверяем approve.
    # ожидание: Возвращается 200.
    When I read the last report
    # что: Читаем отчёт по id.
    # ожидание: HTTP 200, выручка заморожена.
    Then the work report API responds with HTTP 200
    # что: Проверяем чтение.
    # ожидание: Возвращается 200.
    And the report client unit price is "12.50"
    # что: Проверяем замороженную цену.
    # ожидание: clientUnitPrice=12.50.
    And the report revenue value is "37.50"
    # что: Проверяем выручку (12.50 × 3).
    # ожидание: revenueValue=37.50.

  Scenario: TC-API-ABAC-01 — 401 без токена
    Проверка отказа без аутентификации.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I list reports without a token
    # что: Запрашиваем список без Authorization.
    # ожидание: HTTP 401.
    Then the work report API responds with HTTP 401
    # что: Проверяем код ответа.
    # ожидание: Возвращается 401.

  Scenario: TC-API-ABAC-02 — WORKER видит и трогает только свои отчёты
    Проверка own-records скоупа воркера.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the worker tries to read the other worker's report
    # что: Бригадир создаёт отчёт для worker2; worker1 пытается его прочитать.
    # ожидание: HTTP 403/404 error.workReport.access.notOwner.
    Then the work report API responds with HTTP 403
    # что: Проверяем отказ доступа к чужому отчёту.
    # ожидание: Возвращается 403.

  Scenario: TC-API-ABAC-03 — WORKER не может создать отчёт на чужого воркера
    Проверка self-only CREATE для воркера.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the worker creates a report for another worker
    # что: Воркер создаёт отчёт с workerId другого воркера.
    # ожидание: HTTP 403 error.workReport.worker.notSelf.
    Then the work report API responds with HTTP 403
    # что: Проверяем отказ.
    # ожидание: Возвращается 403.

  Scenario: TC-API-ABAC-04 — MANAGER: нет CREATE, есть READ
    Проверка матрицы менеджера.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the manager tries to create a report
    # что: Менеджер создаёт отчёт.
    # ожидание: HTTP 403 (нет CREATE-гранта).
    Then the work report API responds with HTTP 403
    # что: Проверяем отказ CREATE.
    # ожидание: Возвращается 403.
    When the manager lists reports
    # что: Менеджер читает список отчётов проекта.
    # ожидание: HTTP 200 (READ есть).
    Then the work report API responds with HTTP 200
    # что: Проверяем успешность чтения.
    # ожидание: Возвращается 200.

  Scenario: TC-API-ABAC-05 — ESTIMATOR только READ; CLIENT без гранта
    Проверка read-only админ-персонала и deny-by-default клиента.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the estimator lists reports
    # что: Сметчик читает список отчётов.
    # ожидание: HTTP 200 (READ).
    Then the work report API responds with HTTP 200
    # что: Проверяем чтение.
    # ожидание: Возвращается 200.
    When the estimator tries to create a report
    # что: Сметчик пытается создать отчёт.
    # ожидание: HTTP 403 (нет write-грантов).
    Then the work report API responds with HTTP 403
    # что: Проверяем отказ записи.
    # ожидание: Возвращается 403.
    When the client lists reports
    # что: Клиент пытается прочитать отчёты.
    # ожидание: HTTP 403 (deny-by-default).
    Then the work report API responds with HTTP 403
    # что: Проверяем отказ клиента.
    # ожидание: Возвращается 403.

  Scenario: TC-API-ABAC-06 — FOREMAN видит отчёты всех воркеров
    Проверка полного CRUD бригадира над отчётами команды.
    Given a reportable active project with a crew and a priced estimate exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When the foreman creates a report for the worker
    # что: Бригадир создаёт отчёт за воркера, затем читаем его.
    # ожидание: HTTP 200.
    Then the work report API responds with HTTP 200
    # что: Проверяем создание (чтение созданного отчёта).
    # ожидание: Возвращается 200.
    When the foreman lists reports
    # что: Бригадир читает список отчётов проекта.
    # ожидание: HTTP 200, список содержит отчёт.
    Then the work report API responds with HTTP 200
    # что: Проверяем чтение.
    # ожидание: Возвращается 200.
    And the report list contains the last report
    # что: Проверяем присутствие созданного отчёта в списке.
    # ожидание: Отчёт присутствует.

  # Регрессия (REG-03/REG-04) — старт приложения без PermissionAnnotationValidator-ошибки и
  # идемпотентность сидов 163–165 — проверяются бэкенд-тестами спеки FOR-06-01 и миграционным
  # прогоном (Testcontainers), а не против живого стека; покрытие задокументировано там. Здесь
  # сквозной API-поток уже косвенно подтверждает, что контроллер поднят и ABAC-матрица засеяна.

  # ================================================================================
  # Amendment A1 — прогресс-проекция матрицы и пакетное создание (Часть A1-API)
  # ================================================================================

  Scenario: TC-API-PROGRESS-01 — проекция возвращает оси и ячейки для покрытого воркера
    Проверка, что GET /api/work-reports/progress отдаёт матрицу (комнаты × work items × ячейки).
    Given a reportable active project with a crew for the matrix exists
    # что: Готовим ACTIVE-проект, смету с планами и покрывающую категорию воркера.
    # ожидание: Проект, смета (unitPrice=12.50) и планы комнат созданы.
    When the worker reads the progress matrix for themselves
    # что: Воркер читает прогресс-проекцию по себе.
    # ожидание: HTTP 200, тело WorkReportProgressDto.
    Then the matrix API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the progress matrix has at least one room column
    # что: Проверяем ось столбцов rooms[].
    # ожидание: Есть хотя бы одна комната-столбец.
    And the progress matrix contains the covered work item
    # что: Проверяем, что покрытый work item присутствует в проекции.
    # ожидание: Work item входит в пересечение Covering_Works и сметы.
    And the progress cell for room A has plan "20" and reported "0"
    # что: Проверяем ячейку (work item, комната A).
    # ожидание: planQuantity=20, reportedQuantity=0.

  Scenario: TC-API-PROGRESS-02 — reportedQuantity суммирует неотклонённые статусы, исключает REJECTED
    Проверка подсчёта reportedQuantity по статусам отчётов (R14.2).
    Given a reportable active project with a crew for the matrix exists
    # что: Готовим проект с планом комнаты A = 20.
    # ожидание: Проект создан.
    When reports in SUBMITTED, CHANGES_REQUESTED, APPROVED and REJECTED exist for room A
    # что: Создаём 4 отчёта по комнате A (5, 7, 3, 9), переводим в статусы и читаем прогресс.
    # ожидание: HTTP 200, учитываются 5+7+3, REJECTED 9 исключён.
    Then the matrix API responds with HTTP 200
    # что: Проверяем код ответа чтения прогресса.
    # ожидание: Возвращается 200.
    And the progress cell for room A reported quantity is "15"
    # что: Проверяем сумму reportedQuantity (5 + 7 + 3).
    # ожидание: reportedQuantity=15 (REJECTED исключён).

  Scenario: TC-API-PROGRESS-04 — own-records: воркер не видит чужой прогресс; бригадир да; клиент нет
    Проверка проектного скоупа и own-records прогресс-проекции (R14.6, R10.5).
    Given a reportable active project with a crew for the matrix exists
    # что: Готовим проект; второй воркер — другой пользователь.
    # ожидание: Проект создан.
    When the worker reads the progress matrix for the other worker
    # что: Воркер запрашивает прогресс по чужому workerId.
    # ожидание: HTTP 403 error.workReport.access.notOwner.
    Then the matrix API responds with HTTP 403
    # что: Проверяем отказ own-records.
    # ожидание: Возвращается 403.
    When the foreman reads the progress matrix for the worker
    # что: Бригадир запрашивает прогресс по воркеру проекта.
    # ожидание: HTTP 200 (бригадир может по любому воркеру).
    Then the matrix API responds with HTTP 200
    # что: Проверяем доступ бригадира.
    # ожидание: Возвращается 200.
    When the client reads the progress matrix for the worker
    # что: Клиент (без READ-гранта) запрашивает прогресс.
    # ожидание: HTTP 403 (deny-by-default).
    Then the matrix API responds with HTTP 403
    # что: Проверяем отказ клиента.
    # ожидание: Возвращается 403.

  Scenario: TC-API-BATCH-01 — успешный пакет создаёт все отчёты
    Проверка POST /api/work-reports/batch: all-or-nothing успех (R16.1, R16.5).
    Given a reportable active project with a crew for the matrix exists
    # что: Готовим проект; воркер покрыт по work item.
    # ожидание: Проект создан.
    When the worker submits a valid batch of two reports
    # что: Воркер отправляет пакет из двух отчётов (комната A и комната B).
    # ожидание: HTTP 200/201, созданы оба отчёта.
    Then the matrix API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the batch response created 2 reports
    # что: Проверяем массив created[].
    # ожидание: created содержит 2 id.

  Scenario: TC-API-BATCH-02 — один плохой элемент откатывает весь пакет
    Проверка all-or-nothing отката при сбое элемента (R16.4).
    Given a reportable active project with a crew for the matrix exists
    # что: Готовим проект; известен источник ошибки — PERCENT в комнате без плана.
    # ожидание: Проект создан.
    When the worker submits a batch whose third item is invalid
    # что: Воркер отправляет 3 элемента: два валидных и один PERCENT-без-плана.
    # ожидание: HTTP 400, тело с index сбойного элемента.
    Then the matrix API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.
    And the batch error identifies the failing item at index 2
    # что: Проверяем index сбойного элемента.
    # ожидание: index=2 (первый сбойный, R16.4).
    And no new reports were created for the worker
    # что: Проверяем, что валидные элементы не сохранились (пакет откатился).
    # ожидание: Число отчётов воркера не изменилось.

  Scenario: TC-API-BATCH-03 — пакет fast-path бригадира: все элементы сразу APPROVED, без нотификаций
    Проверка fast-path для пакета бригадира (R16.5, R5.1).
    Given a reportable active project with a crew for the matrix exists
    # что: Готовим проект; бригадир покрыт по work item.
    # ожидание: Проект создан.
    When the foreman submitted-notification count is recorded
    # что: Фиксируем базовое число submitted-нотификаций бригадира.
    # ожидание: Базовый счётчик сохранён.
    And the foreman submits a self batch of two reports
    # что: Бригадир отправляет пакет самоотчётов.
    # ожидание: HTTP 200/201, оба отчёта сразу APPROVED.
    Then the matrix API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the batch response created 2 reports
    # что: Проверяем число созданных отчётов.
    # ожидание: created содержит 2 id.
    And the foreman received 0 new submitted notifications
    # что: Проверяем, что fast-path не эмитит submitted-нотификаций.
    # ожидание: 0 новых submitted (R5, R9.5).

  Scenario: TC-API-BATCH-04 — пакет не-бригадира эмитит одну submitted на каждый отчёт
    Проверка нотификаций пакета не-бригадира (R16.5, R9.1).
    Given a reportable active project with a crew for the matrix exists
    # что: Готовим проект; FOREMAN — ACTIVE member.
    # ожидание: Проект создан.
    When the foreman submitted-notification count is recorded
    # что: Фиксируем базовое число submitted-нотификаций бригадира.
    # ожидание: Базовый счётчик сохранён.
    And the worker submits a valid batch of two reports
    # что: Воркер отправляет пакет из двух отчётов.
    # ожидание: HTTP 200/201, оба SUBMITTED.
    Then the matrix API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the foreman received 2 new submitted notifications
    # что: Проверяем, что бригадир получил по одной нотификации на отчёт.
    # ожидание: 2 новых submitted-нотификации (R16.5).

  Scenario: TC-API-BATCH-05 — пакетное создание гейтировано CREATE и own-records
    Проверка ABAC и own-records пакетного создания (R16.6, R10.5).
    Given a reportable active project with a crew for the matrix exists
    # что: Готовим проект; MANAGER без CREATE, второй воркер — чужой.
    # ожидание: Проект создан.
    When the manager submits a batch
    # что: Менеджер (без CREATE-гранта) отправляет пакет.
    # ожидание: HTTP 403.
    Then the matrix API responds with HTTP 403
    # что: Проверяем отказ CREATE.
    # ожидание: Возвращается 403.
    When the worker submits a batch for another worker
    # что: Воркер отправляет пакет с workerId другого воркера.
    # ожидание: HTTP 400/403 error.workReport.worker.notSelf.
    Then the matrix API responds with HTTP 403
    # что: Проверяем отказ own-records.
    # ожидание: Возвращается 403.
    When a batch is submitted without a token
    # что: Пакет отправляется без Authorization.
    # ожидание: HTTP 401.
    Then the matrix API responds with HTTP 401
    # что: Проверяем требование аутентификации.
    # ожидание: Возвращается 401.
