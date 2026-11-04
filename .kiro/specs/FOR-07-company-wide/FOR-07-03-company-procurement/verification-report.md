# Отчёт о прогоне: FOR-07-03 — Закупки по компании (Task 8.1 Docker-stack verification)

Run-id: `20261104211648` (+ setup-run `20261104211430`).
Стратегия повторяемости: **генератор данных + teardown** (уникальные пользователи `test+{role}-{run-id}@example.com`,
сид-документы с уникальным `raw_object_key`; удаление документов через `DELETE /api/company-purchases/documents/{id}`
под ADMIN, снятие созданных `project_members`, деактивация тест-пользователей).
Стенд: ветка `main`, commit `843eed8`, дата прогона 2026-11-04.
Базовый URL API: `http://localhost:8080` (профиль `docker`). UI (`:3000`) — см. ограничения ниже.

## Среда и подготовка

- Docker доступен; на старте работали контейнеры `postgres` (:5432), `redis` (:6379), `liquibase`
  (предыдущий прогон). `backend`/`frontend` были в статусе **Created** (не запущены).
- **Миграции Liquibase 207–210 применены.** Из-за крайне медленного bind-mount на macOS штатный
  `docker compose up` / контейнер `liquibase` зависал на парсинге `changelog.xml` (640 include,
  `Files.exists` по osxfs; thread-dump показал поток `main` в `UnixNativeDispatcher.exists0`, CPU ~3 c
  при elapsed 7+ мин — то есть блокировка на ФС, не на БД). Решение: скопировал `database_files` во
  внутреннюю ФС контейнера liquibase и запустил `update` оттуда — прошло за секунды, применено 27
  новых changeset'ов. Подтверждено в `databasechangelog`:
  - `207-procurement-documents-project-nullable`
  - `208-...-expense-category-column / -fk / -category-index / -uploaded-at-index`
  - `209-seed-company-purchases-resource / -grants`
  - `210-seed-expense-upload-resource / -grants`
  Схема `procurement_documents`: `project_id` **nullable**, добавлена колонка `expense_category_id` (FK).
- Backend поднят (`SPRING_JPA_HIBERNATE_DDL_AUTO=validate` прошёл → схема соответствует сущностям,
  включая новые колонки); admin bootstrap выполнен (`cto+1@loaders.dev`).
- Пользователи созданы через `POST /api/users` (ADMIN), пароль выставлен bcrypt-хэшем напрямую
  (SMTP-инвайт в локальном стеке недоступен), статус переведён в `ACTIVE`; CLIENT — через
  `POST /api/users/client`. Проекты: A=`1526` (ACTIVE), B=`1525` (ACTIVE), C=`1529` (DRAFT).

## Главное ограничение среды (не дефект)

**Загрузка файлов (`POST /api/expenses`, OCR) в локальном Docker-стеке невозможна.** `ImageStorage`
реализован только как `GcsImageStorage`, активный лишь когда заданы `foremen.image-storage.bucket` и
`cdn-base` (GCS bucket + CDN + ADC-креды, которые лежат в `.secrets/*`). `docker-compose.yml` их не
задаёт → активен `DisabledImageStorage`, и любая загрузка отклоняется 400
`error.procurement.upload.storageUnavailable`. По условию задачи секреты не трогались.

Поэтому сценарии, требующие реальной загрузки байтов (сам upload с/без проекта и с наличными,
OCR-статусы, просмотр оригинала), **проверены частично**:
- негативная валидация `POST /api/expenses` (ABAC, типы `paymentChoice`, тип файла, роль) —
  проверена полностью, т.к. контроллер отвергает запрос до обращения к хранилищу либо сразу по правам;
- успешная загрузка (200/201 + сохранение байт) — **заблокирована** (storage unavailable);
- чтение read-model, relink, уведомления — проверены на **засеянных в БД** документах (header+строки
  вставлены напрямую, минуя хранилище), что покрывает логику Requirements 4/5/12 без чтения байт.

UI (`:3000`): контейнер `frontend` не запущен (его сборка — ещё один долгий bind-mount шаг), поэтому
**UI не прогонялся**; видимость навигации по ролям (R10) покрыта на уровне ABAC/HTTP (403/401 по ролям
и матрица грантов, которой управляется меню). Прогон UI через Playwright не выполнялся — согласовано
условием «API-only допустимо, если UI слишком тяжёл».

## Результаты прогона

### Часть A — негативная валидация и ABAC загрузки/чтения (API, боевые запросы)

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-13c | — | POST /api/expenses PAID_CASH без cashAmount (WORKER) | 400 | http=400 | ✅ |
| 2 | TC-14 | 1 | POST /api/expenses paymentChoice=LINKED_FROM_LEDGER | 400 paymentChoiceNotAllowed | http=400 («…opcja płatności nie jest dostępna…») | ✅ |
| 3 | TC-14c | 3 | POST /api/expenses файл .txt | 400 неподдерживаемый тип | http=400 | ✅ |
| 4 | TC-15 | 1–2 | POST /api/expenses CLIENT / без токена | 403 / 401 | client=403 anon=401 | ✅ |
| 5 | TC-12 | 1 | POST /api/expenses WORKER в проект B (не участник) | 404 | http=404 | ✅ |
| 6 | TC-10 | 2 | POST /api/expenses WORKER без проекта (успех) | 200/201 | **400 storageUnavailable** (хранилище не настроено) | ⚠️ BLOCKED |
| 7 | TC-11 | 1 | POST /api/expenses WORKER проект A + категория | 200/201 | **400 storageUnavailable** | ⚠️ BLOCKED |
| 8 | TC-13 | 1 | POST /api/expenses WORKER PAID_CASH 123.45 | 200/201 + кассовая запись | **400 storageUnavailable** | ⚠️ BLOCKED |

### Часть B — ABAC матрица и чтение экрана «Закупки компании» (API, боевые запросы)

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 9 | TC-01 | 2–3 | Матрица ролей `COMPANY_PURCHASES` / `EXPENSE_UPLOAD` | CP: ADMIN/FIN=R/U/D, MGR=R; EU: ADMIN/FIN/MGR/FOREMAN/WORKER=R+C | CP={ADMIN,FIN: READ/UPDATE/DELETE; MANAGER: READ}; EU={ADMIN,FIN,MANAGER,FOREMAN,WORKER: CREATE+READ} | ✅ |
| 10 | TC-20 | 3 | GET /company-purchases/documents (worker/foreman/client/аноним) | 403/403/403/401 | worker=403 foreman=403 client=403 anon=401 | ✅ |
| 11 | TC-20b | 4 | GET /company-purchases/documents (manager/financier/admin) | 200 | mgr=200 fin=200 admin=200 | ✅ |
| 12 | TC-10.4 | 4 | FINANCIER GET /documents/{noProject} | 200, projectId=null | http=200 projectId=null | ✅ |
| 13 | TC-21 | 4 | GET /documents?noProject=true | только проект=null (вкл. D1) | count=1 allNull=true D1present=true | ✅ |
| 14 | TC-21b | 3 | GET /documents?projectId=A | содержит D2 | D2present=true | ✅ |
| 15 | TC-21.5 | 5 | GET /documents?projectId=A&noProject=true | 400 filter.conflict | http=400 | ✅ |
| 16 | TC-22.4 | 4 | GET /company-purchases/lines | строки c documentId и метаданными | count=1 documentId present=true | ✅ |
| 17 | TC-24 | — | MANAGER GET /documents/{id} (READ-роль) | 200 | http=200 | ✅ |

### Часть C — привязка к проекту (relink), reprocess/delete, уведомления (API, боевые запросы; документы засеяны в БД)

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 18 | TC-30 | 2–3 | PUT /documents/{noProj}/project → A (FINANCIER) | 200, projectId=A | http=200 resp.projectId=1526 db.project_id=1526 | ✅ |
| 19 | TC-31 | 1 | PUT /documents/{D2}/project A→B (FINANCIER) | 200, смена проекта | http=200 db.project_id=1525 | ✅ |
| 20 | TC-32.1 | 1 | PUT relink projectId=null | 400 projectRequired | http=400 | ✅ |
| 21 | TC-32.2 | 2 | PUT relink на текущий проект | 409 sameProject | http=409 | ✅ |
| 22 | TC-32.3 | 3 | PUT relink на DRAFT-проект C | 409 stage.notActive | http=409 | ✅ |
| 23 | TC-32.4 | 4 | PUT relink на несуществующий проект | 404 | http=404 | ✅ |
| 24 | TC-32.6 | 6 | PUT relink (MANAGER) | 403 | http=403 | ✅ |
| 25 | TC-32.6b | 6 | PUT relink (WORKER / FOREMAN) | 403 / 403 | worker=403 foreman=403 | ✅ |
| 26 | TC-27.3 | 3 | POST /documents/{id}/reprocess (MANAGER) | 403 | http=403 | ✅ |
| 27 | TC-27.5 | 5 | DELETE /documents/{id} (MANAGER) | 403 | http=403 | ✅ |
| 28 | TC-27.4 | 4 | DELETE /documents/{id} (FINANCIER) + повторный GET | 2xx, затем 404 | del=204 get=404 | ✅ |
| 29 | TC-33 | 2 | GET /api/notifications (WORKER) после relink FINANCIER | уведомление `procurement.relinked` со ссылкой на проект | type=`notification.procurement.relinked`, body=«QA Supplier … → QA Project …», deepLink=`/projects/{id}/procurement?doc={id}` | ✅ |

**Итог: 26 пройдено (✅) / 0 провалено / 3 заблокировано средой (⚠️, №6–8 — storageUnavailable).**

Трассировка требований: R4 (чтение/фильтры — №12–17), R5 (relink — №18–25), R7 (валидация загрузки
— №1–5; успешная загрузка — blocked), R8 (точки входа — косвенно через R7/R10; UI не прогонялся),
R9 (экран — read-model №12–17), R10 (ABAC/меню по ролям — №9–11,24,25), R12 (уведомления — №29).

## Дефекты

| ID | Тест-кейс | Описание | Серьёзность | Статус |
|----|-----------|----------|-------------|--------|
| — | — | Дефектов в поведении не выявлено. №6–8 заблокированы отсутствием настроенного объектного хранилища (GCS) в локальном стеке — ограничение среды, не дефект кода. | — | — |

## Teardown (выполнено)

- Удалены все засеянные QA-документы (`DELETE /api/company-purchases/documents/{id}` под ADMIN →
  строки каскадно удалены; проверка: `QA Supplier%` = 0 документов, 0 строк).
- Удалены 13 тестовых `project_members` и 2 уведомления тест-пользователей.
- Деактивированы 9 тест-пользователей (`active=false`, `status=INVITED`).
- Временные контейнеры/файлы удалены. Стек возвращён в исходное состояние: `postgres`/`redis`/
  `liquibase` — Up; `backend` остановлен (на старте был «Created»); `frontend` — «Created».
  Применённые миграции 207–210 остаются в БД (постоянное, ожидаемое изменение).

## Рекомендации для полного (не заблокированного) прогона

1. Для сквозной загрузки/OCR задать в окружении стека `FOREMEN_IMAGE_STORAGE_BUCKET` и
   `FOREMEN_IMAGE_STORAGE_CDN_BASE` (+ ADC-креды из `.secrets/`), тогда активируется `GcsImageStorage`
   и пройдут TC-10/11/13 и просмотр оригинала (TC-24 с изображениями).
2. Поднять `frontend` (:3000) и прогнать UI-кейсы (меню/навигация по ролям, диалог relink, deep link)
   через Playwright.
3. При запуске на macOS избегать `liquibase`-контейнера на bind-mount `database_files`: копировать
   changelog во внутреннюю ФС контейнера (как сделано в этом прогоне) либо запускать миграции из
   backend-образа.
