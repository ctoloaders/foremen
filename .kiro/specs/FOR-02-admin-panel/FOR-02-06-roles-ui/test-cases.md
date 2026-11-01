# Тест-кейсы: FOR-02-06 — системная роль SALESMAN (дополнительные задачи)

## Назначение и характер

Дополнительная задача FOR-02-06 засевает встроенную роль **SALESMAN** («Продавец» / «Sprzedawca»):
READ на все ресурсы, которые читает MANAGER, плюс CRU (CREATE + READ + UPDATE, **без** DELETE и
APPROVE) на `OFFERS` и `SIGNABLE_DOCUMENTS`; **никаких** записей на `SIGNABLE_DOCUMENT_TYPES` и
`DOCUMENT_TEMPLATES`. Набор включает:

- **Часть A. API-тесты** против поднятого Docker-стека (`http://localhost:8080`).
- **Часть B. Браузерные сценарии** против фронтенда (`http://localhost:3000`), исполняются
  браузерным движком.

Результат прогона — **MD-репорт с таблицами** (шаг → запрос/действие → ожидание → факт → статус).
Скриншоты не требуются.

## Запуск окружения

1. `docker compose up` в корне — `postgres`, `liquibase` (включая changeset `189-seed-salesman-role`),
   `backend` (:8080), `frontend` (:3000).
2. Логин ADMIN: `POST /api/auth/login {email, password}` → `accessToken`.

## Стратегия повторяемости

**Генератор `run-id` + teardown.** Сид-данные (роль SALESMAN и её гранты) только читаются. Для
проверки прав создаётся пользователь `salesman+{run-id}@example.com` с ролью SALESMAN; в конце
набора он деактивируется (`PATCH /api/users/{id}` → `active=false`).

## Фича 1. Сид роли (API)

### TC-SALES-01 — Роль засеяна с локализованными названиями
- Предусловия: ADMIN-токен.
- Шаги:
  1. `GET /api/roles?query=SALESMAN` → 200, ровно одна роль с `code=SALESMAN`.
  2. Проверить `nameRU="Продавец"`, `namePL="Sprzedawca"`, описания RU/PL непустые.
- _Покрывает: доп. задача 1_

### TC-SALES-02 — READ-зеркало MANAGER
- Шаги:
  1. Получить матрицу прав MANAGER и SALESMAN (`GET /api/roles/{id}/permissions` для обеих ролей).
  2. Для каждого ресурса, где у MANAGER есть `READ` → у SALESMAN тоже есть `READ`.
  3. У SALESMAN нет `DELETE` ни на одном ресурсе из зеркала.
- _Покрывает: доп. задача 1_

### TC-SALES-03 — CRU на OFFERS и SIGNABLE_DOCUMENTS, ничего на типах/шаблонах
- Шаги:
  1. В матрице SALESMAN: `OFFERS` = {CREATE, READ, UPDATE}; нет `DELETE`, нет `APPROVE`.
  2. `SIGNABLE_DOCUMENTS` = {CREATE, READ, UPDATE}; нет `DELETE`.
  3. `SIGNABLE_DOCUMENT_TYPES` и `DOCUMENT_TEMPLATES` — нет CREATE/UPDATE/DELETE.
- _Покрывает: доп. задача 1, решение 4_

### TC-SALES-04 — Идемпотентность сида
- Шаги: повторный запуск миграций (`docker compose run --rm liquibase update`) → 0 новых changeset'ов,
  `GET /api/roles?query=SALESMAN` по-прежнему возвращает одну роль.

## Фича 2. Права в работе (API)

### TC-SALES-05 — Продавец пишет оферты, но не удаляет и не утверждает
- Предусловия: создан `salesman+{run-id}@example.com` (SALESMAN), добавлен членом DRAFT-проекта.
- Шаги:
  1. Логин продавцом.
  2. `GET /api/offers/project/{projectId}` → 200 или 204.
  3. `POST /api/offers/project/{projectId}/prepare` → 200 (CREATE).
  4. `DELETE /api/offers/{offerId}` → 403.
  5. `POST /api/signable-document-types` (минимальное тело) → 403.
- Teardown: оферта отзывается ADMIN-ом; пользователь деактивируется.

## Фича 3. Экран ролей (UI)

### TC-SALES-UI-01 — Роль видна на /roles
- Шаги:
  1. Войти ADMIN-ом, открыть `/roles`.
  2. В списке есть строка «Sprzedawca» (PL); переключить язык на RU → «Продавец».
  3. Открыть матрицу прав роли: отмечены CRU на «Oferty» / «Dokumenty do podpisu», нет отметок
     записи на типах документов и шаблонах.
- Ожидание: нет сырых i18n-ключей.

## Регрессия

- **TC-REG-01** — роль MANAGER не изменилась: её матрица прав совпадает со снимком до changeset 189.
- **TC-REG-02** — роль ESTIMATOR (changeset 136) не изменилась.

## Шаблон MD-репорта

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-SALES-01 | 1 | GET /api/roles?query=SALESMAN | 200, одна роль | | |
| 2 | TC-SALES-03 | 1 | матрица SALESMAN / OFFERS | C,R,U; нет D/APPROVE | | |
| 3 | TC-SALES-05 | 4 | DELETE /api/offers/{id} | 403 | | |
| 4 | TC-SALES-UI-01 | 2 | /roles, язык RU | «Продавец» | | |

Итог: X пройдено / Y провалено / Z пропущено. Run-id: `<timestamp/uuid>`. Стратегия повторяемости:
`генератор + teardown`.
