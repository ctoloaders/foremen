# Foremen — Прогресс разработки

> Обновлено: 02.11.2026. Источник данных — реальные папки в `.kiro/specs/` (чекбоксы `tasks.md` + `additional-tasks.md`) и код.
> «Дочерних» = реально существующие дочерние спеки (папки с `tasks.md`/`additional-tasks.md`) + запланированные (📋).
> Пустые папки-заготовки (FOR-01-08, FOR-01-09, FOR-QA-AUTO-05/10/11) в счёт не входят.

## Сводка

| Спека | Описание | Дочерних (сущ. + план.) | Выполнено | Прогресс |
|-------|----------|:-----------------------:|:---------:|:--------:|
| FOR-01-backend-structure | Кастомный CRUD-фреймворк, базовая архитектура | 8 | 8 | ✅ 100% |
| FOR-02-admin-panel | Админка: веб-приложение, ABAC-сущности, роли, пользователи, аудит, тема | 13 | 12 | 🟡 92% |
| FOR-03-auth-system | JWT, приглашения, ABAC-evaluator, OTP клиента, меню, защита API, сброс пароля, Google-логин | 11 | 10 | 🟡 91% |
| FOR-04-base-entities | Справочники, прейскурант работ, проект, помещение, материалы, нормы расхода | 22 | 22 | ✅ 100% |
| FOR-05-project-estimate | Проект в проектировании (DRAFT): помещения (размеры, автоимпорт, визуальный редактор), смета, BoM, маржа, оффер, подписание, команда, Гант, готовность, активация, совместное редактирование | 18 | 18 | ✅ 100% |
| FOR-06-project-in-progress | Проект в работе: отчётность, план/факт Гант, OCR закупок, список закупок, матрицы материалов и маржи, оплаты клиента, амендменты, ЗП, коммуникация | 13 + 📋1 | 13 | 🟡 93% |
| FOR-07-company-wide | Общефирменные экраны: Гант занятости, закупки вне проекта, банковский реестр (Salt Edge + AI), меню, ЗП по работникам | 7 + 📋1 | 7 | 🟡 88% |
| FOR-08-ai-estimate | AI-ассистент (оркестратор + tools + чат per project) | 1 | 1 | ✅ 100% |
| FOR-09-client-portal | Клиентский портал (отдельный клиентский UI) | 📋 — | — | ⬜ Не начато (папка пуста) |
| ~~FOR-10-financial-accounting~~ | Свёрнуто в FOR-06 / FOR-07-04 | — | — | — |
| ~~FOR-11-payroll~~ | Свёрнуто в FOR-06-10/11 и FOR-07-06 | — | — | — |
| FOR-12-infrastructure | Деплой в Google Cloud, CI/CD, прод-окружение | 📋 — | — | 🟦 Скрипты есть в `infra/dev` + cloudbuild; спеки нет (папка пуста) |
| FOR-QA-AUTO | E2E-автоматизация (Cucumber + Playwright) | 1 | 0 | 🟡 SMOKE 12/14 |
| receipt-ocr-recognition | OCR чеков/первички (самостоятельная) | 1 | 1 | ✅ 100% |
| telegram-receipt-bot | Telegram-бот приёма чеков (самостоятельная) | 1 | 1 | ✅ 100% (код в `телеграм-бот/telegram-bot`) |
| renovation-management-system | Исходная зонтичная спека (только requirements + design) | — | — | 📄 Справочная |

## Запланированные спеки (в работу не взяты)

| Спека | Где описана | Содержание | Статус |
|-------|-------------|------------|--------|
| FOR-06-13-workspace-integration | FOR-06 OVERVIEW | Встраивание всех исполнительных табов в пространство проекта FOR-05 | 📋 Папки нет |
| FOR-07-07-company-deliveries | FOR-07 OVERVIEW | Экран «Dostawy» вместо заглушки `/deliveries`; **требование ToR 3.2 (доставки + SMS)** | 📋 Папки нет, к уточнению |
| FOR-09-client-portal | PROJECT / FOR-06 OVERVIEW | Клиентский портал: график, оплаты, закупки, коммуникация (сторона клиента) | 📋 Папка пуста |
| FOR-12-infrastructure | PROJECT | Прод-деплой, окружения, мониторинг | 📋 Папка пуста |
| Dashboard | ToR 4.1 | Реальные дашборды по ролям (сейчас `DashboardPage.tsx` — заглушка) | 📋 Спеки нет |
| Импорт прейскуранта из Excel | ToR 1.7 | Загрузка Excel клиентского прайса из приложения | 📋 Спеки нет |

### FOR-01 — Детализация

| # | Подспека | Статус |
|---|----------|--------|
| 01 | FOR-01-01-gradle | ✅ Завершено |
| 02 | FOR-01-02-base-entity | ✅ Завершено (19/20, 1 необязательная) |
| 03 | FOR-01-03-exception | ✅ Завершено |
| 04 | FOR-01-04-mapstruct | ✅ Завершено |
| 05 | FOR-01-05-crud-dao | ✅ Завершено |
| 06 | FOR-01-06-crud-service | ✅ Завершено (41/42, 1 необязательная) |
| 07 | FOR-01-07-crud-controller | ✅ Завершено |
| 10 | FOR-01-10-config | ✅ Завершено |

> FOR-01-08-audit и FOR-01-09-liquibase — пустые заготовки; аудит реализован в FOR-02-06a, миграции — Liquibase в `foremen-backend/database_files`.

### FOR-02 — Детализация

| # | Подспека | Статус |
|---|----------|--------|
| 01 | FOR-02-01-frontend-setup | ✅ Завершено |
| 02 | FOR-02-02-app-shell | ✅ Завершено |
| 03 | FOR-02-03-abac-entities | ✅ Завершено |
| 04 | FOR-02-04-abac-seed | ✅ Завершено |
| 05 | FOR-02-05-user-entity | ✅ Завершено |
| 06 | FOR-02-06-roles-ui | ✅ Завершено |
| 06a | FOR-02-06a-audit | ✅ Завершено |
| 06a | FOR-02-06a-audit-comparison-view | ✅ Завершено |
| 06t | FOR-02-06t-table-template | ✅ Завершено |
| 07 | FOR-02-07-users-ui | ✅ Завершено |
| 07 | FOR-02-07-users-ui-fixes | ✅ Завершено (26/26) |
| 07 | FOR-02-07-users-create-jsonb-null-fix | 🟨 В работе (1/2) |
| 08 | FOR-02-08-theme-settings | ✅ Завершено |

### FOR-03 — Детализация

| # | Подспека | Статус |
|---|----------|--------|
| 01 | FOR-03-01-jwt-auth | 🟨 В работе (54/62) — функционал завершён, осталась автоматизация API-тестов |
| 02 | FOR-03-02-user-invitation | ✅ Завершено |
| 03 | FOR-03-03-permission-evaluator | ✅ Завершено |
| 04 | FOR-03-04-project-ownership | ✅ Завершено |
| 04a | FOR-03-04a-project-actions-validation | ✅ Завершено |
| 05 | FOR-03-05-otp-client-auth | ✅ Завершено |
| 06 | FOR-03-06-frontend-auth | ✅ Завершено |
| 07 | FOR-03-07-menu-visibility | ✅ Завершено |
| 08 | FOR-03-08-api-protection | ✅ Завершено |
| 09 | FOR-03-09-password-reset | ✅ Завершено |
| 10 | FOR-03-10-google-login | ✅ Завершено |

### FOR-04 — Детализация

| # | Подспека | Статус |
|---|----------|--------|
| 01 | FOR-04-01-table-reference-filter | ✅ Завершено |
| 02 | FOR-04-02-measurement-units | ✅ Завершено |
| 03 | FOR-04-03-currencies | ✅ Завершено |
| 04 | FOR-04-04-vat-rates | ✅ Завершено |
| 05 | FOR-04-05-room-types | ✅ Завершено |
| 06 | FOR-04-06-work-categories | ✅ Завершено |
| 07 | FOR-04-07-delivery-categories | ✅ Завершено |
| 08 | FOR-04-08-delivery-statuses | ✅ Завершено |
| 09 | FOR-04-09-material-categories | ✅ Завершено |
| 10 | FOR-04-10-offer-packages | ✅ Завершено |
| 11 | FOR-04-11-work-catalog | ✅ Завершено |
| 12 | FOR-04-12-work-prices | ✅ Завершено |
| 12b | FOR-04-12b-work-prices-packages | ✅ Завершено |
| 13 | FOR-04-13-project | ✅ Завершено |
| 14 | FOR-04-14-room | ✅ Завершено |
| 15 | FOR-04-15-menu-grouping | ✅ Завершено |
| 16 | FOR-04-16-material-types | ✅ Завершено |
| 17 | FOR-04-17-construction-materials | ✅ Завершено |
| 18 | FOR-04-18-finishing-materials | ✅ Завершено |
| 19 | FOR-04-19-work-catalog-material-consumption | ✅ Завершено |
| 19 | FOR-04-19-work-catalog-rework | ✅ Завершено |
| — | FOR-04-bugs | ✅ Завершено |
| — | FOR-04-RESEARCH-material-norms | ✅ Research-артефакт (CSV-сиды норм) |

### FOR-05 — Детализация (стадия проектирования)

| # | Подспека | Статус |
|---|----------|--------|
| 01 | FOR-05-01-workspace-shell | ✅ Завершено (42/43, 1 необязательная) |
| 02 | FOR-05-02-rooms-dimensions | ✅ Завершено |
| 02 | FOR-05-02-rooms-autoimport | ✅ Завершено |
| 02 | FOR-05-02-rooms-visual-editor | ✅ Завершено |
| 03 | FOR-05-03-estimate-core | ✅ Завершено |
| 04 | FOR-05-04-estimate-packages-changes | ✅ Завершено (миграция) |
| 04-UI | FOR-05-04-UI-estimate-packages-changes | ✅ Завершено |
| 05 | FOR-05-05-bill-of-materials | ✅ Завершено |
| 05b | FOR-05-05b-list-of-materials | ✅ Завершено |
| 06 | FOR-05-06-packages-margins | ✅ Завершено |
| 07 | FOR-05-07-offer-approval | ✅ Завершено |
| 08 | FOR-05-08-document-signing | ✅ Завершено |
| 09 | FOR-05-09-team-selection | ✅ Завершено |
| 10 | FOR-05-10-project-gantt | ✅ Завершено |
| 10b | FOR-05-10b-gantt-granularity | ✅ Завершено |
| 11 | FOR-05-11-project-readiness | ✅ Завершено |
| 12 | FOR-05-12-project-activation | ✅ Завершено (проверено по коду: backend, frontend, i18n, тесты; открыта только финальная живая верификация, задача 9) |
| 13 | FOR-05-13-collaborative-estimate | ✅ Завершено (11/11, только additional-tasks + test-cases) |

### FOR-06 — Детализация (стадия исполнения)

| # | Подспека | Статус |
|---|----------|--------|
| 00 | FOR-06-00-execution-foundation | ✅ Завершено |
| 01 | FOR-06-01-work-reporting | ✅ Завершено |
| 02 | FOR-06-02-plan-fact-gantt | ✅ Завершено |
| 03 | FOR-06-03-procurement-ocr | ✅ Завершено |
| 04 | FOR-06-04-purchases-list | ✅ Завершено |
| 05 | FOR-06-05-materials-matrix | ✅ Завершено |
| 06 | FOR-06-06-margin-matrix | ✅ Завершено |
| 07 | FOR-06-07-client-payments | ✅ Завершено |
| 08 | FOR-06-08-amendments | ✅ Завершено |
| 09 | FOR-06-09-amendment-signing | ✅ Завершено |
| 10 | FOR-06-10-worker-cost-rates | ✅ Завершено |
| 11 | FOR-06-11-payroll | ✅ Завершено |
| 12 | FOR-06-12-client-communication | ✅ Завершено |
| 13 | FOR-06-13-workspace-integration | 📋 Запланировано (папки нет) |

### FOR-07 — Детализация (кросс-проектные / общефирменные)

| # | Подспека | Статус |
|---|----------|--------|
| 00 | FOR-07-00-company-foundation | ✅ Завершено |
| 01 | FOR-07-01-cross-project-schedule-core | ✅ Завершено |
| 02 | FOR-07-02-staffing-gantt-ui | ✅ Завершено |
| 03 | FOR-07-03-company-procurement | ✅ Завершено |
| 04 | FOR-07-04-bank-ledger | ✅ Завершено (Salt Edge, правила + Gemini-классификатор, кэш-записи) |
| 05 | FOR-07-05-menu-integration | ✅ Завершено |
| 06 | FOR-07-06-company-payroll | 🟨 Почти завершено (38/39) |
| 07 | FOR-07-07-company-deliveries | 📋 Запланировано (папки нет, «Dostawy», к уточнению) |

### FOR-08 — Детализация

| # | Подспека | Статус |
|---|----------|--------|
| — | FOR-08-ai-estimate | ✅ Завершено (23/23; дочерних спек нет, есть `openapi/` и `bot-configuration.md`) |

### FOR-QA-AUTO — Детализация

| # | Подспека | Статус |
|---|----------|--------|
| — | FOR-QA-AUTO-SMOKE | 🟨 В работе (12/14) |
| 05 | FOR-QA-AUTO-05-workspace-shell | ⬜ Папка пуста |
| 10 | FOR-QA-AUTO-10-project-gantt | ⬜ Папка пуста |
| 11 | FOR-QA-AUTO-11-project-readiness | ⬜ Папка пуста |

---

*Обновляется при добавлении/изменении/завершении спек.*
