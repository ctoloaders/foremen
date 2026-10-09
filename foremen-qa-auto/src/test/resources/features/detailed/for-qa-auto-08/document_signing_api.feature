@detailed @FOR-QA-AUTO-08 @api
Feature: Подписание документов — API жизненного цикла (FOR-05-08)
  Жизненный цикл signable-документа через API.
  Создание, заморозка, подписанты, методы подписи и ABAC-ограничение клиента.

  # Кейсы редактора шаблонов, роли FOREMAN/WORKER/FINANCIER и строгая генерация (422)
  # не воспроизводятся на stub-стеке и покрыты серверными интеграционными тестами (N/A).

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека и миграций.
    # ожидание: Backend на :8080 отвечает, типы документов засеяны.

  Scenario: TC-LC-01/02/03 — создание, аннулирование и запрет выхода из VOID
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом-подписантом и комнатой.
    # ожидание: Проект и его участники созданы под уникальный run-id.
    And a draft signable document exists
    # что: Создаём документ CONTRACT_WORKS.
    # ожидание: Документ создан в статусе DRAFT.
    When the document is voided
    # что: Переводим DRAFT в терминальный VOID.
    # ожидание: HTTP 200, документ аннулирован.
    Then the document status is "VOID"
    # что: Читаем статус документа.
    # ожидание: Статус равен VOID.
    When signatures are requested from the client with method "PRINT"
    # что: Пытаемся запросить подписи у аннулированного документа.
    # ожидание: HTTP 409 error.document.illegal.transition.
    Then the last signing call returns HTTP 409
    # что: Проверяем код ответа нелегального перехода.
    # ожидание: Возвращается 409, статус документа не меняется.

  Scenario: TC-LC-04/05 — запрос подписей замораживает документ, повтор запрещён
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом.
    # ожидание: Проект создан.
    And a draft signable document exists
    # что: Создаём DRAFT-документ.
    # ожидание: Документ в статусе DRAFT.
    When signatures are requested from the client with method "PRINT"
    # что: Запрашиваем подписи (PRINT, AdES) у клиента.
    # ожидание: HTTP 200, документ переходит в PENDING_SIGNATURES.
    Then the last signing call returns HTTP 200
    # что: Проверяем успешность запроса подписей.
    # ожидание: Возвращается 200.
    And the document status is "PENDING_SIGNATURES"
    # что: Читаем статус после запроса подписей.
    # ожидание: Статус PENDING_SIGNATURES.
    And the document has a non-null content hash
    # что: Проверяем, что PDF заморожен и зафиксирован contentHash.
    # ожидание: contentHash не null.
    When signatures are requested again from the client with method "PRINT"
    # что: Повторно запрашиваем подписи у PENDING-документа.
    # ожидание: HTTP 409 error.document.illegal.transition.
    Then the last signing call returns HTTP 409
    # что: Проверяем запрет повторного запроса.
    # ожидание: Возвращается 409.

  Scenario: TC-SIG-01 — два подписанта дают прогресс 0 из 2
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом.
    # ожидание: Проект создан.
    And a draft signable document exists
    # что: Создаём DRAFT-документ.
    # ожидание: Документ в статусе DRAFT.
    When signatures are requested from two signers with method "PRINT"
    # что: Запрашиваем подписи у двух подписантов.
    # ожидание: HTTP 200, создано 2 PENDING-подписи.
    Then the last signing call returns HTTP 200
    # что: Проверяем успех запроса.
    # ожидание: Возвращается 200.
    And the signing progress shows 0 signed of 2
    # что: Читаем агрегированный прогресс.
    # ожидание: signedCount=0, totalCount=2.

  Scenario: TC-SIG-02 — пустой набор подписантов отклоняется
    Given a project with a signing client and a room exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    And a draft signable document exists
    # что: Создаём DRAFT-документ.
    # ожидание: Документ в статусе DRAFT.
    When signatures are requested with an empty signer set
    # что: Запрашиваем подписи без подписантов.
    # ожидание: HTTP 400, документ остаётся DRAFT.
    Then the last signing call returns HTTP 400
    # что: Проверяем код отказа.
    # ожидание: Возвращается 400.
    And the document status is "DRAFT"
    # что: Проверяем, что статус не изменился.
    # ожидание: Документ по-прежнему DRAFT.

  Scenario: TC-FREEZE-03/GEN-04 — после заморозки правка тела и generate запрещены
    Given a project with a signing client and a room exists
    # что: Готовим проект.
    # ожидание: Проект создан.
    And a draft signable document exists
    # что: Создаём DRAFT-документ.
    # ожидание: Документ в статусе DRAFT.
    When signatures are requested from the client with method "PRINT"
    # что: Запрашиваем подписи, замораживая PDF.
    # ожидание: HTTP 200, документ PENDING_SIGNATURES.
    And the body is edited
    # что: Пытаемся изменить тело после заморозки.
    # ожидание: HTTP 409 error.document.frozen.
    Then the last signing call returns HTTP 409
    # что: Проверяем запрет правки тела.
    # ожидание: Возвращается 409.
    When generate is invoked
    # что: Пытаемся перегенерировать тело после заморозки.
    # ожидание: HTTP 409 error.document.frozen.
    Then the last signing call returns HTTP 409
    # что: Проверяем запрет generate.
    # ожидание: Возвращается 409.

  Scenario: TC-PRINT-01/02 — скан завершает подпись, без улики подпись не SIGNED
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом.
    # ожидание: Проект создан.
    And a draft signable document exists
    # что: Создаём DRAFT-документ.
    # ожидание: Документ DRAFT.
    When signatures are requested from the client user with method "PRINT"
    # что: Назначаем клиента единственным PRINT-подписантом.
    # ожидание: HTTP 200, документ PENDING_SIGNATURES.
    And the client attempts to sign without evidence
    # что: Пытаемся завершить подпись без скана.
    # ожидание: HTTP 409 error.document.evidence.required.
    Then the last signing call returns HTTP 409
    # что: Проверяем, что без улики SIGNED недостижим.
    # ожидание: Возвращается 409.
    When the client signs via scan upload
    # что: Загружаем скан мокрой подписи.
    # ожидание: HTTP 200, подпись SIGNED.
    Then the last signing call returns HTTP 200
    # что: Проверяем успех загрузки скана.
    # ожидание: Возвращается 200.
    And the document status is "SIGNED"
    # что: Проверяем деривацию статуса документа.
    # ожидание: Единственная подпись завершена, документ SIGNED.
    And the document reports all signatures signed
    # что: Проверяем агрегированный флаг allSigned.
    # ожидание: allSigned=true.

  Scenario: TC-TAB-01 — парафка на планшете завершает подпись
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом.
    # ожидание: Проект создан.
    And a draft signable document exists
    # что: Создаём DRAFT-документ.
    # ожидание: Документ DRAFT.
    When signatures are requested from the client user with method "TABLET_INITIALS"
    # что: Назначаем клиента подписантом с методом парафки.
    # ожидание: HTTP 200, документ PENDING_SIGNATURES.
    When the client signs via tablet initials
    # что: Загружаем изображение парафки.
    # ожидание: HTTP 200, подпись SIGNED.
    Then the last signing call returns HTTP 200
    # что: Проверяем успех захвата парафки.
    # ожидание: Возвращается 200.
    And the document status is "SIGNED"
    # что: Проверяем деривацию статуса.
    # ожидание: Документ SIGNED.

  Scenario: TC-PROV-01/04 — провайдерский callback с совпадающим hash завершает подпись
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом.
    # ожидание: Проект создан.
    And a draft signable document exists
    # что: Создаём DRAFT-документ.
    # ожидание: Документ DRAFT.
    When signatures are requested from the client user with method "ONLINE"
    # что: Назначаем клиента ONLINE-подписантом.
    # ожидание: HTTP 200, документ PENDING_SIGNATURES.
    And the client initiates a provider signature
    # что: Инициируем провайдерскую церемонию.
    # ожидание: Подпись получает providerRef, остаётся PENDING.
    When the provider calls back signed with a matching hash
    # что: Провайдер шлёт callback SIGNED с верным contentHash (без Authorization).
    # ожидание: HTTP 200, хэш улики сверён.
    Then the last signing call returns HTTP 200
    # что: Проверяем приём callback.
    # ожидание: Возвращается 200.
    And a signature exists with status "SIGNED"
    # что: Проверяем статус подписи.
    # ожидание: Подпись SIGNED.

  Scenario: TC-PROV-02 — callback с несовпадающим hash отклоняется
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом.
    # ожидание: Проект создан.
    And a draft signable document exists
    # что: Создаём DRAFT-документ.
    # ожидание: Документ DRAFT.
    When signatures are requested from the client user with method "ONLINE"
    # что: Назначаем клиента ONLINE-подписантом.
    # ожидание: HTTP 200, документ PENDING_SIGNATURES.
    And the client initiates a provider signature
    # что: Инициируем провайдерскую церемонию.
    # ожидание: Подпись получает providerRef.
    When the provider calls back signed with a mismatching hash
    # что: Провайдер шлёт callback с искажённым contentHash.
    # ожидание: HTTP 409 error.document.hash.mismatch.
    Then the last signing call returns HTTP 409
    # что: Проверяем отказ по несовпадению хэша.
    # ожидание: Возвращается 409, подпись остаётся PENDING.

  Scenario: TC-PROV-03 — callback DECLINED отклоняет подпись без аннулирования документа
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом.
    # ожидание: Проект создан.
    And a draft signable document exists
    # что: Создаём DRAFT-документ.
    # ожидание: Документ DRAFT.
    When signatures are requested from the client user with method "ONLINE"
    # что: Назначаем клиента ONLINE-подписантом.
    # ожидание: HTTP 200, документ PENDING_SIGNATURES.
    And the client initiates a provider signature
    # что: Инициируем церемонию.
    # ожидание: Подпись получает providerRef.
    When the provider calls back declined
    # что: Провайдер шлёт callback DECLINED.
    # ожидание: HTTP 200, подпись DECLINED.
    Then the last signing call returns HTTP 200
    # что: Проверяем приём callback.
    # ожидание: Возвращается 200.
    And a signature exists with status "DECLINED"
    # что: Проверяем статус подписи.
    # ожидание: Подпись DECLINED, документ не аннулирован.
    And the document status is "PENDING_SIGNATURES"
    # что: Проверяем, что документ остаётся ожидающим.
    # ожидание: Статус PENDING_SIGNATURES.

  Scenario: TC-ABAC-02 — клиент не может создать документ
    Given a project with a signing client and a room exists
    # что: Готовим проект, где клиент — участник.
    # ожидание: Проект создан.
    When the client attempts to create a document
    # что: Клиент пробует создать документ.
    # ожидание: HTTP 403 error.document.operation.forbidden.
    Then the last signing call returns HTTP 403
    # что: Проверяем серверный отказ операции создания.
    # ожидание: Возвращается 403.

  Scenario: TC-CONF-01 — клиентский ответ документа не содержит себестоимости и маржи
    Given a project with a signing client and a room exists
    # что: Готовим проект с клиентом.
    # ожидание: Проект создан.
    And a draft signable document exists
    # что: Создаём DRAFT-документ.
    # ожидание: Документ DRAFT.
    When signatures are requested from the client user with method "PRINT"
    # что: Делаем клиента подписантом, чтобы документ был ему виден.
    # ожидание: HTTP 200, документ PENDING_SIGNATURES.
    Then the client document view exposes no cost or margin fields
    # что: Клиент читает документ и проверяем тело ответа.
    # ожидание: Нет полей cost/margin/estimateUnitPrice/workerRate.
