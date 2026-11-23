@detailed @FOR-QA-AUTO-05-07B @api
Feature: FOR-05-07b A3. Offer PDF (API)
  PDF оферты из её снимка: для исполнителя и клиента, без конфиденциальных данных, недоступность черновика и legacy (тест-кейсы TC-PDF-01..07).

  # Повторяемость: свой проект FOR-05-07b {run-id} на сценарий; PDF не хранится на сервере, чистка не нужна;
  # teardown удаляет комнаты, проект и пользователей. Текст PDF извлекается PDFBox.

  Background:
    Given the application stack is ready
    # что: Проверяем готовность docker-стека.
    # ожидание: Бэкенд и фронтенд отвечают.
    And an offers project with a client and rooms A, B and C exists
    # что: S1–S2. Клиент, проект и комнаты.
    # ожидание: Проект создан.
    And the offers estimate has two works in rooms and one work in no room
    # что: S3. Наполняем смету.
    # ожидание: Смета готова.

  Scenario: TC-PDF-01 PDF for the executor in Polish and Russian
    TC-PDF-01: исполнитель скачивает PDF отправленной оферты; в нём имя оферты, проект, категория, комнаты и итоги (2.4).
    Given the first offer package is applied to the estimate
    # что: S4. Применяем пакет PKG.
    # ожидание: Пакет отмечен в смете.
    When the admin prepares and sends the offer "PDF"
    # что: Готовим и отправляем оферту Offer-{run-id}-PDF.
    # ожидание: SENT.
    And the admin calls GET "/api/offers/{PDF}/snapshot" with language "pl"
    # что: Читаем снимок, чтобы знать название первой категории.
    # ожидание: 200.
    And the response field "groups[0].name" is remembered as "CAT_PL"
    # что: Запоминаем название категории (PL).
    # ожидание: Значение сохранено.
    And the response field "totalNet" is remembered as "PDF_NET"
    # что: Запоминаем итог нетто снимка.
    # ожидание: Значение сохранено.
    And the response field "totalGross" is remembered as "PDF_GROSS"
    # что: Запоминаем итог брутто снимка.
    # ожидание: Значение сохранено.
    And the admin calls GET "/api/offers/{PDF}/pdf" with language "pl"
    # что: Скачиваем PDF с Accept-Language: pl.
    # ожидание: 200.
    Then the response is a PDF document
    # что: Проверяем тип и сигнатуру файла.
    # ожидание: 200, application/pdf, тело начинается с %PDF.
    And the response header "content-disposition" contains "attachment; filename="
    # что: Проверяем заголовок загрузки.
    # ожидание: attachment; filename=...
    And the response header "content-disposition" contains "{PDF}"
    # что: Имя файла содержит id оферты.
    # ожидание: id есть в имени файла.
    And the response header "content-disposition" contains ".pdf"
    # что: Расширение файла.
    # ожидание: .pdf.
    And the PDF text contains "Offer-{RUN}-PDF"
    # что: Имя оферты в PDF.
    # ожидание: Присутствует.
    And the PDF text contains "{PROJECT_NAME}"
    # что: Название проекта в PDF.
    # ожидание: Присутствует.
    And the PDF text contains "{CAT_PL}"
    # что: Название категории работ в PDF.
    # ожидание: Присутствует.
    And the PDF text contains "{ROOM_A_LABEL}"
    # что: Комната Room A в PDF.
    # ожидание: Присутствует.
    And the PDF text contains "{ROOM_B_LABEL}"
    # что: Комната Room B в PDF.
    # ожидание: Присутствует.
    And the PDF text contains "Razem netto"
    # что: Подпись итога нетто на польском.
    # ожидание: Присутствует.
    And the PDF text contains "VAT"
    # что: Строка VAT со ставкой.
    # ожидание: Присутствует.
    And the PDF text contains the amount "{PDF_NET}"
    # что: Итог нетто в PDF совпадает со снимком.
    # ожидание: Сумма присутствует.
    And the PDF text contains the amount "{PDF_GROSS}"
    # что: Итог брутто в PDF совпадает со снимком.
    # ожидание: Сумма присутствует.
    When the admin calls GET "/api/offers/{PDF}/pdf" with language "ru"
    # что: Скачиваем PDF с Accept-Language: ru.
    # ожидание: 200.
    Then the response is a PDF document
    # что: Проверяем тип файла.
    # ожидание: PDF.
    And the PDF text contains "Итого нетто"
    # что: Подписи на русском.
    # ожидание: «Итого нетто» присутствует.
    And the PDF text contains "НДС"
    # что: Строка НДС на русском.
    # ожидание: Присутствует.

  Scenario: TC-PDF-02 The client PDF shows no confidential data
    TC-PDF-02: при скорректированной цене труда PDF клиента содержит клиентские итоги и не содержит базовых цен (2.4, 3.2).
    When the admin successfully calls POST "/api/estimates/project/{PID}/labour-price-adjustment" with body '{"scope":"PROJECT","pct":10}'
    # что: Поднимаем цену труда на 10%, чтобы базовая цена отличалась от цены оферты.
    # ожидание: 200.
    And the admin prepares and sends the offer "PDF"
    # что: Готовим и отправляем оферту.
    # ожидание: SENT.
    And the admin calls GET "/api/offers/{PDF}/snapshot"
    # что: Исполнитель читает снимок с базовыми ценами.
    # ожидание: 200.
    And the response field "groups[0].rows[0].baseValueNet" is remembered as "BASE_VALUE"
    # что: Запоминаем базовую стоимость первой строки.
    # ожидание: Значение сохранено.
    And the response field "groups[0].rows[0].valueNet" is remembered as "OFFER_VALUE"
    # что: Запоминаем стоимость строки в оферте.
    # ожидание: Значение сохранено.
    And the client calls GET "/api/offers/{PDF}/snapshot"
    # что: Клиент читает свой снимок.
    # ожидание: 200.
    And the response field "totalNet" is remembered as "CLIENT_NET"
    # что: Запоминаем клиентский итог.
    # ожидание: Значение сохранено.
    And the client calls GET "/api/offers/{PDF}/pdf"
    # что: Клиент скачивает PDF.
    # ожидание: 200.
    Then the response is a PDF document
    # что: Проверяем тип файла.
    # ожидание: PDF.
    And the PDF text contains the amount "{CLIENT_NET}"
    # что: Итог в PDF совпадает с клиентским снимком.
    # ожидание: Сумма присутствует.
    And the PDF text contains the amount "{OFFER_VALUE}"
    # что: Стоимость строки по цене оферты есть.
    # ожидание: Присутствует.
    And the PDF text does not contain the amount "{BASE_VALUE}"
    # что: Базовой стоимости (до корректировки) в PDF клиента нет.
    # ожидание: Отсутствует.

  Scenario: TC-PDF-03 A DRAFT offer PDF is hidden from the client
    TC-PDF-03: PDF черновика клиенту недоступен (404), исполнителю доступен (2.4, 3.2).
    When the admin prepares the offer "DR"
    # что: Готовим черновик DR.
    # ожидание: DRAFT.
    And the client calls GET "/api/offers/{DR}/pdf"
    # что: Клиент запрашивает PDF черновика.
    # ожидание: 404.
    Then the response status is 404
    # что: Проверяем код ответа.
    # ожидание: 404.
    When the admin calls GET "/api/offers/{DR}/pdf"
    # что: Исполнитель запрашивает PDF черновика.
    # ожидание: 200 из сохранённого снимка.
    Then the response is a PDF document
    # что: Проверяем файл.
    # ожидание: PDF.

  Scenario: TC-PDF-04 No token, a client of another project and a missing offer
    TC-PDF-04: без токена 401, клиент чужого проекта 403/404, несуществующая оферта 404.
    Given a second offers project with another client and a filled estimate exists
    # что: Создаём второй проект со своим клиентом CLIENT_OTHER.
    # ожидание: Проект создан.
    When the admin prepares and sends the offer "PDF"
    # что: Готовим и отправляем оферту первого проекта.
    # ожидание: SENT.
    And the anonymous calls GET "/api/offers/{PDF}/pdf"
    # что: Запрос без токена.
    # ожидание: 401.
    Then the response status is 401
    # что: Проверяем код ответа.
    # ожидание: 401.
    When the outsider calls GET "/api/offers/{PDF}/pdf"
    # что: Клиент другого проекта запрашивает PDF.
    # ожидание: Отказ, как для GET snapshot.
    Then the response status is one of "403, 404"
    # что: Проверяем код ответа.
    # ожидание: 403 или 404.
    When the admin calls GET "/api/offers/999999999/pdf"
    # что: Запрашиваем PDF несуществующей оферты.
    # ожидание: 404.
    Then the response status is 404
    # что: Проверяем код ответа.
    # ожидание: 404.

  Scenario: TC-PDF-05 A legacy offer without a snapshot has no PDF
    TC-PDF-05: legacy-оферта без снимка — 404 error.offer.snapshot.missing (3.3, A3).
    Given a legacy offer "LEG" without a snapshot in status "SENT" exists
    # что: Вставляем legacy-оферту без снимка в проект {run-id}.
    # ожидание: Оферта создана.
    When the admin calls GET "/api/offers/{LEG}/pdf"
    # что: Запрашиваем её PDF.
    # ожидание: 404.
    Then the response status is 404
    # что: Проверяем код ответа.
    # ожидание: 404.
    And the response error code is "error.offer.snapshot.missing"
    # что: Проверяем код ошибки.
    # ожидание: error.offer.snapshot.missing.

  Scenario: TC-PDF-06 Renderer failure
    TC-PDF-06 (негативный, по возможности): ошибка рендерера даёт 502 error.offer.pdf.failed. На docker-стеке рендерер нельзя сломать без подмены кода.
    Given the case is skipped because "the docker stack cannot force a PdfRenderer failure; covered by OfferHtmlBuilderTest and OfferPdfServiceTest"
    # что: Кейс пропускается: на стенде нельзя вызвать сбой рендера.
    # ожидание: Статус «пропущен», основание в отчёте (покрыто модульными тестами OfferPdfServiceTest).

  Scenario: TC-PDF-07 Signing and proforma PDFs are unchanged
    TC-PDF-07: PDF подписания и клиентской проформы не изменились (3.8). Они проверяются своими сквозными наборами.
    Given the case is skipped because "signing and proforma PDFs are covered by the FOR-QA-AUTO-08 document-signing slice"
    # что: Кейс пропускается в этом наборе: PDF подписания проверяет набор FOR-QA-AUTO-08.
    # ожидание: Статус «пропущен» со ссылкой на набор FOR-QA-AUTO-08.
