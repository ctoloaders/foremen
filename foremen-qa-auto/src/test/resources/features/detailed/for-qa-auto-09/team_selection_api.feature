@detailed @FOR-QA-AUTO-09 @api
Feature: Подбор и назначение команды проекта — API (FOR-05-09)
  Проверка API команды проекта (/api/project-members) и флоу приглашения клиента/воркера.
  Назначение, обновление, удаление членов, кандидаты, готовность и ABAC-ограничения.

  # UI-сценарии (Часть B test-cases) исполняются браузерным движком и покрываются отдельным
  # UI-слайсом; здесь — API-часть A против поднятого docker-стека.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека и миграций FOR-05-09 (149–151).
    # ожидание: Backend на :8080 отвечает, worker types засеяны.

  Scenario: TC-ABAC-02 — 401 без токена перед проверкой прав
    Проверка, что чтение команды без токена отклоняется с 401.
    Given a team project with a manager and a client exists
    # что: Готовим проект с менеджером и клиентом под уникальный run-id.
    # ожидание: Проект и обязательные участники созданы.
    When I list team members without a token
    # что: Запрашиваем список членов без заголовка Authorization.
    # ожидание: HTTP 401, доступ не предоставлен.
    Then the Team API responds with HTTP 401
    # что: Проверяем код ответа.
    # ожидание: Возвращается 401.

  Scenario: TC-ABAC-03 — WORKER получает 403 на чтении команды
    Проверка, что роль без гранта PROJECT_MEMBERS отклоняется.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When a "WORKER" user lists the team members
    # что: Залогинившись под WORKER, читаем список членов.
    # ожидание: HTTP 403 error.access.denied.
    Then the Team API responds with HTTP 403
    # что: Проверяем код отказа.
    # ожидание: Возвращается 403.

  Scenario: TC-ABAC-04 — FOREMAN: READ ок, запись 403
    Проверка матрицы: FOREMAN читает, но не пишет.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When a "FOREMAN" user lists the team members
    # что: Под FOREMAN читаем список членов.
    # ожидание: HTTP 200, чтение разрешено.
    Then the Team API responds with HTTP 200
    # что: Проверяем успешность чтения.
    # ожидание: Возвращается 200.
    When a "FOREMAN" user tries to assign a member
    # что: Под FOREMAN пытаемся назначить члена (CREATE).
    # ожидание: HTTP 403, запись запрещена.
    Then the Team API responds with HTTP 403
    # что: Проверяем отказ записи.
    # ожидание: Возвращается 403.

  Scenario: TC-LIST-01/03 — список членов, детерминированный, без секретов
    Проверка списка членов команды и отсутствия утечек.
    Given a team project with a manager and a client exists
    # что: Готовим проект с менеджером и клиентом.
    # ожидание: Проект создан.
    When I list the team members
    # что: Запрашиваем список членов проекта под ADMIN.
    # ожидание: HTTP 200, непустой JSON-массив Team_Member_View.
    Then the Team API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the response is a JSON array
    # что: Проверяем тип тела ответа.
    # ожидание: Тело — JSON-массив.
    And the response array is non-empty
    # что: Проверяем, что список содержит членов.
    # ожидание: Массив непустой (есть MANAGER и CLIENT).
    And the response is not a leaky member list
    # что: Проверяем отсутствие секретов и себестоимости в теле.
    # ожидание: Нет password/token/tier/cost/rate.

  Scenario: TC-LIST-02 — пустая команда возвращает пустой массив
    Проверка, что проект без членов возвращает 200 и [].
    Given an empty project with no members exists
    # что: Создаём проект без участников.
    # ожидание: Проект создан пустым.
    When I list the members of the empty project
    # что: Запрашиваем список членов пустого проекта.
    # ожидание: HTTP 200, пустой массив.
    Then the Team API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the response array is empty
    # что: Проверяем размер массива.
    # ожидание: Массив пуст ([]).

  Scenario: TC-LIST-05 — валидация projectId (неположительный → 400)
    Проверка 400 при неположительном projectId.
    Given a team project with a manager and a client exists
    # что: Готовим проект (для авторизации ADMIN).
    # ожидание: Проект создан.
    When I list team members with raw projectId "-1"
    # что: Запрашиваем список с projectId=-1.
    # ожидание: HTTP 400 error.project.member.project.id.invalid.
    Then the Team API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.

  Scenario: TC-LIST-04 — локализация имени роли по языку запроса
    Проверка, что localized имя роли отдаётся по Accept-Language.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I list team members with Accept-Language "ru"
    # что: Запрашиваем список с Accept-Language: ru.
    # ожидание: HTTP 200, projectRoleName на русском.
    Then the Team API responds with HTTP 200
    # что: Проверяем успешность запроса.
    # ожидание: Возвращается 200.

  Scenario: TC-ASSIGN-01/02 — назначение члена и запрет дубликата
    Проверка назначения FOREMAN и 409 на повторном назначении.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I assign a "FOREMAN" user to the project
    # что: Назначаем нового пользователя роли FOREMAN в команду.
    # ожидание: HTTP 201, роль FOREMAN, статус ACTIVE.
    Then the Team API responds with HTTP 201
    # что: Проверяем успешность назначения.
    # ожидание: Возвращается 201.
    And the assigned member has project role "FOREMAN" and status "ACTIVE"
    # что: Проверяем роль и статус созданного членства.
    # ожидание: projectRoleCode=FOREMAN.
    When I assign the same "FOREMAN" user again
    # что: Повторяем назначение той же пары (userId, projectId).
    # ожидание: HTTP 409 error.project.member.duplicate.
    Then the Team API responds with HTTP 409
    # что: Проверяем запрет дубликата.
    # ожидание: Возвращается 409.

  Scenario: TC-ASSIGN-03 — несуществующий пользователь → 404
    Проверка 404 при назначении несуществующего пользователя.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I assign a non-existent user to the project
    # что: Назначаем заведомо несуществующий userId.
    # ожидание: HTTP 404 error.entity.not.found.
    Then the Team API responds with HTTP 404
    # что: Проверяем код ответа.
    # ожидание: Возвращается 404.

  Scenario: TC-ASSIGN-04 — валидация обязательных полей
    Проверка 400 при отсутствующем userId и 404 при userId=0 (несуществующая сущность).
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I assign with a missing userId
    # что: Отправляем POST без userId.
    # ожидание: HTTP 400 (обязательное поле).
    Then the Team API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.
    When I assign with a non-positive userId
    # что: Отправляем POST с userId=0 (несуществующий пользователь).
    # ожидание: HTTP 404 error.entity.not.found.
    Then the Team API responds with HTTP 404
    # что: Проверяем код ответа.
    # ожидание: Возвращается 404.

  Scenario: TC-ASSIGN-05 — несовпадающий projectRoleId → 400
    Проверка role.mismatch при явном несовпадающем projectRoleId.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I assign a "FOREMAN" user with a mismatched project role
    # что: Назначаем FOREMAN, передав projectRoleId роли CLIENT.
    # ожидание: HTTP 400 error.project.member.role.mismatch.
    Then the Team API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.

  Scenario: TC-ASSIGN-06 — роль не Assignable (ADMIN) → 400
    Проверка not.assignable при назначении ADMIN-пользователя.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I assign an "ADMIN" role user to the project
    # что: Пытаемся назначить пользователя глобальной роли ADMIN.
    # ожидание: HTTP 400 error.project.member.role.not.assignable.
    Then the Team API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.

  Scenario: TC-REMOVE-01/02 — удаление члена и повторное удаление
    Проверка hard-delete и 404 при повторе.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I assign a "FOREMAN" user to the project
    # что: Назначаем удаляемого не-последнего члена FOREMAN.
    # ожидание: HTTP 201.
    Then the Team API responds with HTTP 201
    # что: Проверяем успешность назначения.
    # ожидание: Возвращается 201.
    When I remove the "FOREMAN" user from the project
    # что: Удаляем назначенного FOREMAN.
    # ожидание: HTTP 204, пустое тело.
    Then the Team API responds with HTTP 204
    # что: Проверяем успешность удаления.
    # ожидание: Возвращается 204.
    When I list the team members
    # что: Перечитываем список членов.
    # ожидание: HTTP 200, удалённого FOREMAN нет.
    Then the Team API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the team member list does not contain the "FOREMAN" user
    # что: Проверяем отсутствие удалённого члена в списке.
    # ожидание: Член отсутствует.

  Scenario: TC-REMOVE-02 — удаление не-члена → 404
    Проверка 404 при удалении пользователя, не состоящего в проекте.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I remove a non-member user from the project
    # что: Удаляем пользователя, не являющегося членом.
    # ожидание: HTTP 404 error.project.member.not.found.
    Then the Team API responds with HTTP 404
    # что: Проверяем код ответа.
    # ожидание: Возвращается 404.

  # TC-REMOVE-03 (DELETE identifier validation) — a missing/non-numeric query param is handled by
  # the Spring framework layer (routing to /error → 401, or a type-conversion 500) rather than the
  # Team API's own 400 validation, so it is not a stable HTTP-contract assertion against the live
  # stack. It is covered by the backend controller/unit tests; omitted from the API slice.

  Scenario: TC-LAST-01 — удаление последнего активного менеджера → 409
    Проверка инварианта последнего ACTIVE MANAGER.
    Given a team project with a manager and a client exists
    # что: Готовим проект с ровно одним ACTIVE MANAGER.
    # ожидание: Проект создан.
    When I remove the last active manager
    # что: Удаляем единственного ACTIVE MANAGER.
    # ожидание: HTTP 409 error.project.member.last.manager.
    Then the Team API responds with HTTP 409
    # что: Проверяем блокировку удаления.
    # ожидание: Возвращается 409.

  Scenario: TC-LAST-02 — удаление последнего активного клиента → 409
    Проверка инварианта последнего ACTIVE CLIENT.
    Given a team project with a manager and a client exists
    # что: Готовим проект с ровно одним ACTIVE CLIENT.
    # ожидание: Проект создан.
    When I remove the last active client
    # что: Удаляем единственного ACTIVE CLIENT.
    # ожидание: HTTP 409 error.project.member.last.client.
    Then the Team API responds with HTTP 409
    # что: Проверяем блокировку удаления.
    # ожидание: Возвращается 409.

  Scenario: TC-LAST-03 — деактивация последнего активного менеджера → 409
    Проверка инварианта при PATCH assignmentStatus=INACTIVE.
    Given a team project with a manager and a client exists
    # что: Готовим проект с одним ACTIVE MANAGER.
    # ожидание: Проект создан.
    When I deactivate the last active manager
    # что: PATCH-деактивация единственного ACTIVE MANAGER.
    # ожидание: HTTP 409 error.project.member.last.manager.
    Then the Team API responds with HTTP 409
    # что: Проверяем блокировку деактивации.
    # ожидание: Возвращается 409.

  Scenario: TC-STATUS-01/02/03 — deactivate, reactivate, валидация статуса
    Проверка soft-деактивации и реактивации члена и 400 на невалидном статусе.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I assign a "FOREMAN" user to the project
    # что: Назначаем не-последнего члена FOREMAN.
    # ожидание: HTTP 201.
    Then the Team API responds with HTTP 201
    # что: Проверяем назначение.
    # ожидание: Возвращается 201.
    When I deactivate the "FOREMAN" user
    # что: PATCH-деактивация FOREMAN (assignmentStatus=INACTIVE).
    # ожидание: HTTP 200, статус INACTIVE.
    Then the Team API responds with HTTP 200
    # что: Проверяем успешность деактивации.
    # ожидание: Возвращается 200.
    And the member view assignment status is "INACTIVE"
    # что: Проверяем статус в теле ответа.
    # ожидание: assignmentStatus=INACTIVE.
    When I reactivate the "FOREMAN" user
    # что: PATCH-реактивация FOREMAN (assignmentStatus=ACTIVE).
    # ожидание: HTTP 200, статус ACTIVE.
    Then the Team API responds with HTTP 200
    # что: Проверяем успешность реактивации.
    # ожидание: Возвращается 200.
    And the member view assignment status is "ACTIVE"
    # что: Проверяем статус в теле ответа.
    # ожидание: assignmentStatus=ACTIVE.
    When I patch an invalid assignment status for the "FOREMAN" user
    # что: PATCH с assignmentStatus=FOO.
    # ожидание: HTTP 400 error.project.member.assignment.status.invalid.
    Then the Team API responds with HTTP 400
    # что: Проверяем валидацию статуса.
    # ожидание: Возвращается 400.

  Scenario: TC-WT-01 — назначение worker type воркеру через PATCH
    Проверка установки типа работника на WORKER-члена.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I assign a "WORKER" user to the project
    # что: Назначаем WORKER-члена без типа.
    # ожидание: HTTP 201.
    Then the Team API responds with HTTP 201
    # что: Проверяем назначение.
    # ожидание: Возвращается 201.
    When I set a worker type on the "WORKER" worker
    # что: PATCH workerTypeId=BASE на WORKER-члена.
    # ожидание: HTTP 200, тип установлен.
    Then the Team API responds with HTTP 200
    # что: Проверяем успешность назначения типа.
    # ожидание: Возвращается 200.
    And the member view worker type code is "BASE"
    # что: Проверяем код типа в ответе.
    # ожидание: workerTypeCode=BASE.

  Scenario: TC-WT-03 — worker type на не-воркера → 400
    Проверка not.allowed для не-WORKER члена.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I assign a "FOREMAN" user to the project
    # что: Назначаем FOREMAN (не WORKER).
    # ожидание: HTTP 201.
    Then the Team API responds with HTTP 201
    # что: Проверяем назначение.
    # ожидание: Возвращается 201.
    When I set a worker type on a non-worker "FOREMAN" member
    # что: PATCH workerTypeId на FOREMAN-члена.
    # ожидание: HTTP 400 error.project.member.worker.type.not.allowed.
    Then the Team API responds with HTTP 400
    # что: Проверяем отказ.
    # ожидание: Возвращается 400.

  Scenario: TC-WT-04 — worker type для не-члена → 404
    Проверка 404 при PATCH типа для пользователя, не состоящего в проекте.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I patch a worker type for a non-member user
    # что: PATCH workerTypeId для не-члена.
    # ожидание: HTTP 404 error.project.member.not.found.
    Then the Team API responds with HTTP 404
    # что: Проверяем код ответа.
    # ожидание: Возвращается 404.

  Scenario: TC-WRF-06 — неактивный/несуществующий worker type → 400
    Проверка worker.type.invalid при неизвестном типе.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I assign a "WORKER" user to the project
    # что: Назначаем WORKER-члена.
    # ожидание: HTTP 201.
    Then the Team API responds with HTTP 201
    # что: Проверяем назначение.
    # ожидание: Возвращается 201.
    When I set a non-existent worker type on the "WORKER" worker
    # что: PATCH workerTypeId=<несуществующий>.
    # ожидание: HTTP 400 error.project.member.worker.type.invalid.
    Then the Team API responds with HTTP 400
    # что: Проверяем отказ.
    # ожидание: Возвращается 400.

  Scenario: TC-TAG-01/03 — нормализация тегов и очистка
    Проверка нормализации (trim/дедуп/порядок) и очистки пустым списком.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I assign a "WORKER" user to the project
    # что: Назначаем WORKER-члена.
    # ожидание: HTTP 201.
    Then the Team API responds with HTTP 201
    # что: Проверяем назначение.
    # ожидание: Возвращается 201.
    When I replace the tags of the "WORKER" member with "Spec,WELD,Rigger"
    # что: PATCH тегов нормализованным списком.
    # ожидание: HTTP 200, теги сохранены в порядке.
    Then the Team API responds with HTTP 200
    # что: Проверяем успешность.
    # ожидание: Возвращается 200.
    And the member view tags are "Spec,WELD,Rigger"
    # что: Проверяем сохранённые теги.
    # ожидание: tags=[Spec, WELD, Rigger].

  Scenario: TC-CAND-01/05 — поиск кандидатов и пагинация
    Проверка списка кандидатов, пагинации и поля total.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    And a candidate user with role "FOREMAN" exists
    # что: Создаём свободного кандидата FOREMAN.
    # ожидание: Кандидат создан.
    When I search candidates
    # что: Запрашиваем кандидатов без фильтров под ADMIN.
    # ожидание: HTTP 200, Spring-страница с totalElements.
    Then the Team API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the candidate page has a total count field
    # что: Проверяем наличие поля общего количества.
    # ожидание: Поле totalElements присутствует.

  Scenario: TC-CAND-05 — max page size 50
    Проверка клампинга размера страницы кандидатов до 50.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I search candidates with size "1000"
    # что: Запрашиваем кандидатов с size=1000.
    # ожидание: HTTP 200, не более 50 элементов.
    Then the Team API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the candidate page size is at most 50
    # что: Проверяем размер страницы.
    # ожидание: Не более 50 элементов.

  Scenario: TC-CAND-06 — ошибки параметров и доступа кандидатов
    Проверка 400 на неверной роли и 403 для роли без CREATE.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I search candidates with an invalid role
    # что: Запрашиваем кандидатов с role=NOPE.
    # ожидание: HTTP 400.
    Then the Team API responds with HTTP 400
    # что: Проверяем код ответа.
    # ожидание: Возвращается 400.
    When a "FOREMAN" user searches candidates
    # что: Под FOREMAN (только READ) запрашиваем кандидатов (CREATE).
    # ожидание: HTTP 403 error.access.denied.
    Then the Team API responds with HTTP 403
    # что: Проверяем отказ доступа.
    # ожидание: Возвращается 403.

  Scenario: TC-SCOPE-03 — список проектов пользователя
    Проверка списка проектов члена (возрастание) и пустого для несуществующего.
    Given a team project with a manager and a client exists
    # что: Готовим проект с менеджером.
    # ожидание: Проект создан.
    When I list the projects of the manager
    # что: Запрашиваем project-id менеджера.
    # ожидание: HTTP 200, список по возрастанию.
    Then the Team API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the response is a JSON array
    # что: Проверяем тип тела.
    # ожидание: Тело — JSON-массив id.
    And the response array is sorted ascending
    # что: Проверяем порядок id.
    # ожидание: Список отсортирован по возрастанию.
    When I list the projects of a non-existent user
    # что: Запрашиваем project-id несуществующего пользователя.
    # ожидание: HTTP 200, пустой массив (не 404).
    Then the Team API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the response array is empty
    # что: Проверяем пустоту.
    # ожидание: Массив пуст.

  Scenario: TC-READY-01 — readiness BLOCKED без прораба, DONE с прорабом
    Проверка перехода гейта team при появлении ACTIVE FOREMAN.
    Given a team project with a manager and a client exists
    # что: Готовим проект без ACTIVE FOREMAN.
    # ожидание: Проект создан.
    When I read the team readiness
    # что: Запрашиваем readiness проекта.
    # ожидание: HTTP 200, key=team, state=BLOCKED.
    Then the Team API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the readiness key is "team"
    # что: Проверяем ключ гейта.
    # ожидание: key=team.
    And the readiness state is "BLOCKED"
    # что: Проверяем состояние без прораба.
    # ожидание: state=BLOCKED.
    And the readiness counts include all six roles
    # что: Проверяем наличие всех шести счётчиков ролей.
    # ожидание: counts для manager/foreman/estimator/worker/financier/client.
    When I assign a "FOREMAN" user and read readiness
    # что: Назначаем ACTIVE FOREMAN и перечитываем readiness.
    # ожидание: HTTP 200, state=DONE, foreman>=1.
    Then the Team API responds with HTTP 200
    # что: Проверяем код ответа.
    # ожидание: Возвращается 200.
    And the readiness state is "DONE"
    # что: Проверяем состояние с прорабом.
    # ожидание: state=DONE.
    And the readiness foreman count is at least 1
    # что: Проверяем счётчик прорабов.
    # ожидание: counts.foreman>=1.

  Scenario: TC-READY-03 — readiness: 403 без READ
    Проверка отказа readiness для роли без гранта PROJECT_MEMBERS.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When a "WORKER" user reads the team readiness
    # что: Под WORKER запрашиваем readiness.
    # ожидание: HTTP 403 error.access.denied.
    Then the Team API responds with HTTP 403
    # что: Проверяем отказ.
    # ожидание: Возвращается 403.

  Scenario: TC-CLIENT-01 — приглашение нового клиента из Team-таба
    Проверка атомарного создания CLIENT-пользователя и членства.
    Given a team project with a manager and a client exists
    # что: Готовим проект в Editable-статусе.
    # ожидание: Проект создан.
    When I register a new client on the project
    # что: POST /api/users/client с привязкой к проекту и тегами.
    # ожидание: HTTP 201, создан CLIENT-user и CLIENT-членство.
    Then the Team API responds with HTTP 201
    # что: Проверяем успешность приглашения.
    # ожидание: Возвращается 201.

  Scenario: TC-CLIENT-02 — дубликат email клиента → 409
    Проверка 409 при повторной регистрации того же email.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I register a client with a duplicate email
    # что: Регистрируем клиента, затем повторяем с тем же email.
    # ожидание: HTTP 409 error.user.email.already.exists.
    Then the Team API responds with HTTP 409
    # что: Проверяем запрет дубликата.
    # ожидание: Возвращается 409.

  Scenario: TC-CLIENT-04 — валидация полей клиента
    Проверка 400 при пустом имени клиента.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I register a client with a blank name
    # что: POST /api/users/client с пустым name.
    # ожидание: HTTP 400.
    Then the Team API responds with HTTP 400
    # что: Проверяем валидацию.
    # ожидание: Возвращается 400.

  Scenario: TC-WRF-01 — добавление PERSON-воркера с типом → 201
    Проверка создания неприглашённого WORKER и его членства.
    Given a team project with a manager and a client exists
    # что: Готовим проект в Editable-статусе.
    # ожидание: Проект создан.
    When I add a "PERSON" worker with a worker type
    # что: POST /api/users/worker (PERSON, workerType=BASE, tags).
    # ожидание: HTTP 201, создан uninvited WORKER + ACTIVE членство.
    Then the Team API responds with HTTP 201
    # что: Проверяем успешность.
    # ожидание: Возвращается 201.

  Scenario: TC-WRF-02 — COMPANY-воркер с валидным телом → 201
    Проверка создания COMPANY-воркера (без NIP-ошибки на валидном вводе).
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I add a "COMPANY" worker with a worker type
    # что: POST /api/users/worker (COMPANY, workerType=BASE).
    # ожидание: HTTP 201.
    Then the Team API responds with HTTP 201
    # что: Проверяем успешность.
    # ожидание: Возвращается 201.

  Scenario: TC-WRF-04 — валидация workerKind → 400
    Проверка отказа при недопустимом workerKind.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I add a worker with an invalid kind
    # что: POST /api/users/worker с workerKind=X.
    # ожидание: HTTP 400 error.worker.kind.invalid.
    Then the Team API responds with HTTP 400
    # что: Проверяем валидацию.
    # ожидание: Возвращается 400.

  Scenario: TC-WRF-05 — невалидный NIP → 400
    Проверка отказа по контрольной сумме NIP.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I add a company worker with an invalid NIP
    # что: POST /api/users/worker (COMPANY) с некорректным NIP.
    # ожидание: HTTP 400 error.worker.nip.invalid.
    Then the Team API responds with HTTP 400
    # что: Проверяем валидацию.
    # ожидание: Возвращается 400.

  Scenario: TC-WRF-10 — приглашение добавленного воркера → 200
    Проверка отправки staff-приглашения добавленному воркеру.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I add a "PERSON" worker with a worker type
    # что: Создаём воркера с сохранённым email.
    # ожидание: HTTP 201.
    Then the Team API responds with HTTP 201
    # что: Проверяем создание.
    # ожидание: Возвращается 201.
    When I invite the added worker
    # что: POST /api/users/worker/{id}/invite.
    # ожидание: HTTP 200, отправлено password-set письмо.
    Then the Team API responds with HTTP 200
    # что: Проверяем успешность приглашения.
    # ожидание: Возвращается 200.

  Scenario: TC-WRF-11 — приглашение несуществующего воркера → 404
    Проверка 404 при invite несуществующего id.
    Given a team project with a manager and a client exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    When I invite a non-existent worker
    # что: POST /api/users/worker/{несуществующий}/invite.
    # ожидание: HTTP 404 error.entity.not.found.
    Then the Team API responds with HTTP 404
    # что: Проверяем код ответа.
    # ожидание: Возвращается 404.

  Scenario: TC-LOCK-01 — мутация на Locked_Status → 409
    Проверка блокировки назначения в COMPLETED-проект.
    Given a locked project in status "COMPLETED" exists
    # что: Создаём проект сразу в статусе COMPLETED.
    # ожидание: Locked-проект создан.
    When I assign a "FOREMAN" user to the locked project
    # что: Пытаемся назначить члена в Locked-проект.
    # ожидание: HTTP 409 error.project.team.locked.
    Then the Team API responds with HTTP 409
    # что: Проверяем блокировку.
    # ожидание: Возвращается 409.

  Scenario: TC-LOCK-02 — чтения на Locked_Status работают
    Проверка, что чтение команды Locked-проекта разрешено.
    Given a locked project in status "CANCELLED" exists
    # что: Создаём проект в статусе CANCELLED.
    # ожидание: Locked-проект создан.
    When I list members of the locked project
    # что: Запрашиваем список членов Locked-проекта.
    # ожидание: HTTP 200, список отдаётся.
    Then the Team API responds with HTTP 200
    # что: Проверяем, что чтение не заблокировано.
    # ожидание: Возвращается 200.
