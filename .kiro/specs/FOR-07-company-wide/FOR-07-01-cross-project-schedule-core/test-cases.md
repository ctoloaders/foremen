# Тест-кейсы: FOR-07-01 — Cross-Project Schedule Core (межпроектный график компании)

## Назначение и характер спеки

Спека **чисто бэкендовая, read-only**: она не добавляет браузерных экранов (UI — это отдельная
спека FOR-07-02). Поэтому набор состоит **только из API-тестов** против поднятого в Docker
приложения (`http://localhost:8080`).

Единственная проверяемая поверхность — один эндпоинт:

```
GET /api/company-schedule
    query: roles, projectIds, memberIds, from, to   (все необязательные)
    ABAC: @PermissionResource("COMPANY_SCHEDULE") + READ
```

Каждый шаг — HTTP-запрос с проверкой статуса, тела (форма проекции, фильтры, конфликты, легенда,
цвета, даты) и ABAC-поведения под разными ролями. **Результат прогона — MD-репорты с таблицами**
(шаг → запрос → ожидание → факт → статус). Скриншоты не требуются.

### Покрытие требований

| Группа | Требования |
|--------|-----------|
| Г1. Форма проекции (строки/бары) | R1.1, R1.2, R1.3, R1.4, R1.5, R1.6, R1.7 |
| Г2. Исключение Uncovered-баров | R2.1, R2.2, R2.3, R2.4 |
| Г3. Детерминированный цвет и легенда | R3.1, R3.2, R3.3 |
| Г4. Абсолютные даты, unanchored / partial | R4.1, R4.2, R4.3, R4.4, R4.5 |
| Г5. Конфликты (только worker-бары) | R5.1, R5.2, R5.4, R5.5, R5.6 |
| Г6. Приоритет прораба над своими worker-барами | R1.8 |
| Г7. Scope / ABAC | R6.1, R6.2, R6.3, R9.1, R9.2, R9.4 |
| Г8. Фильтры (role/project/member/date) | R7.1, R7.2, R7.3, R7.4, R7.5, R7.6, R7.7 |
| Г9. `from > to` → 400 | R7.8, R7.9 |
| Г10. Пустые / краевые случаи | R10.1, R10.2, R10.3, R10.4 |
| Г11. Отсутствие стоимостных полей | R8.1, R8.2, R8.3 |

Соответствие «кейс ↔ требование» указано в каждом кейсе строкой `_Покрывает:_`.

## Запуск окружения

1. В корне репозитория: `docker compose up` — поднимает `postgres` (:5432), `liquibase` (миграции,
   включая новый changeset `187-seed-company-schedule-resource.xml`), `backend` (:8080, профиль
   `docker`), `frontend` (:3000).
2. Базовый URL для API-тестов: `http://localhost:8080`; auth-эндпоинты — под `/api/auth`.
3. Первый ADMIN — через `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.
   Логин: `POST /api/auth/login {email, password}` → `accessToken`. Во всех запросах
   `Authorization: Bearer <accessToken>`.
4. Дождаться готовности `backend` и завершения миграций перед прогоном (проверка: ADMIN получает
   **200** на `GET /api/company-schedule`).
5. Для проверки ABAC/scope под не-ADMIN ролями создать (или переиспользовать) пользователей ролей
   MANAGER, FOREMAN, WORKER, CLIENT и получить их `accessToken`.

### Вспомогательные эндпоинты для подготовки данных

Проекция строится из существующих данных планирования. Для подготовки фикстур используем:

- `POST /api/projects` — создать проект (поля `name`, `startDate`, `endDate`).
- `POST /api/project-members` — добавить в проект участника с `projectRole.code` ∈ {`FOREMAN`,
  `WORKER`} и `assignmentStatus = ACTIVE`.
- `PUT /api/project-schedules/bars?projectId={id}` — сохранить per-worker бары (half-day поля
  `startHalfDays` / `durationHalfDays`, привязка к `projectMember` и `workCategory`); либо
  `POST /api/project-schedules/auto-create?projectId={id}` для авто-раскладки.
- Uncovered-бар = бар с `projectMember = null` (виртуальная непокрытая категория), получается
  авто-созданием при непокрытой категории.

## Общая стратегия повторяемости

**Комбинированная: генератор `run-id` + teardown.** Единый на прогон `run-id` (timestamp или uuid)
встраивается в имена всех создаваемых сущностей, чтобы прогоны не конфликтовали и были изолируемы по
фильтру:

- проекты: `name` = `TC7 {run-id} P1`, `TC7 {run-id} P2`, …;
- пользователи: `email` = `tc7+{run-id}-w1@example.com` и т.п.

Поскольку эндпоинт сам по себе **read-only** и ничего не изменяет, повторяемость обеспечивается
именно уникальностью подготовленных фикстур: каждый кейс читает проекцию, **отфильтрованную по своим
`projectIds` / `memberIds`**, поэтому посторонние данные прошлых прогонов не влияют на проверки.

**Teardown** в конце набора: деактивировать/удалить созданные проекты и участников с `run-id` в
имени (`DELETE /api/projects/{id}`, `DELETE /api/project-members/{id}`), либо оставить их
деактивированными — проекция read-only, историю не портит. Дефолтные сид-данные не трогаются.

---

## Группа 1. Форма проекции: строки и бары

### TC-CS-01 — Одна строка на участника, агрегирование по всем проектам
_Покрывает: R1.1, R1.2, R1.5, R1.6_
**Предусловия:** ADMIN залогинен. Создать проекты `P1` и `P2` (оба с `startDate`/`endDate`). Одного и
того же WORKER `W1` сделать ACTIVE-участником обоих проектов и назначить ему worker-бары в каждом из
них (сохранить через `PUT /api/project-schedules/bars`).
1. `GET /api/company-schedule?projectIds={P1},{P2}` под ADMIN → **200**.
2. В `rows` ровно **одна** строка с `userId = W1`, а не две (агрегирование по пользователю).
3. У этой строки `bars` содержат бары из **обоих** проектов (`projectId` = P1 и P2).
4. Каждый worker-бар несёт `projectId`, `projectName`, `workCategoryId`, локализованное
   `workCategoryName`, `startHalfDays`, `durationHalfDays`, `colorIndex`, `kind = WORKER_CATEGORY`.

### TC-CS-02 — Строка прораба собирается из foreman-span баров
_Покрывает: R1.3, R1.7, R1.4_
**Предусловия:** создать проект `P3` c `startDate`/`endDate`; добавить FOREMAN `F1` как
ACTIVE-участника (`projectRole.code = FOREMAN`).
1. `GET /api/company-schedule?projectIds={P3}` под ADMIN → **200**.
2. В строке `userId = F1` есть бар `kind = FOREMAN_SPAN` для проекта P3.
3. У span-бара: `startDate = project.startDate`, `endDate = project.endDate`,
   `workCategoryId = null`, `workCategoryName = null`, `startHalfDays = null`,
   `durationHalfDays = null`.
4. Поле `companyRole` строки = `FOREMAN` (из `projectRole.code` вкладывающего участника); для строки
   из TC-CS-01 оно = `WORKER`.

### TC-CS-03 — Прораб ведёт несколько проектов → несколько span-баров в одной строке
_Покрывает: R1.3, R1.5_
**Предусловия:** FOREMAN `F1` — ACTIVE-прораб проектов `P3` и `P4` (оба датированы).
1. `GET /api/company-schedule?projectIds={P3},{P4}` под ADMIN → **200**.
2. Одна строка `userId = F1`, в ней **два** бара `kind = FOREMAN_SPAN` (по одному на P3 и P4).

---

## Группа 2. Исключение Uncovered (null-member) баров

### TC-CS-04 — Uncovered-бары не попадают в проекцию
_Покрывает: R2.1, R2.4_
**Предусловия:** создать проект `P5` с хотя бы одной непокрытой категорией (auto-create даёт
виртуальный бар с `projectMember = null`) и одной покрытой категорией для WORKER `W2`.
1. `GET /api/company-schedule?projectIds={P5}` под ADMIN → **200**.
2. В `rows` есть строка `W2` с её worker-баром; **ни один** бар не происходит из null-member бара.
3. В проекции нет строки без идентифицируемого пользователя.

### TC-CS-05 — Проект только из uncovered-баров и без прораба не даёт строк
_Покрывает: R2.2, R2.3_
**Предусловия:** проект `P6`, все категории непокрыты, участников-прорабов нет.
1. `GET /api/company-schedule?projectIds={P6}` под ADMIN → **200**.
2. `rows` не содержит ни одной строки, относящейся к P6; `legend` не содержит P6 (нет ни одного
   реального бара).

---

## Группа 3. Детерминированный цвет и легенда

### TC-CS-06 — Легенда и стабильный colorIndex внутри ответа
_Покрывает: R3.1, R3.3_
**Предусловия:** проекты `P1`, `P2` с барами (из Г1).
1. `GET /api/company-schedule?projectIds={P1},{P2}` → **200**.
2. `legend` содержит запись на каждый проект: `{projectId, projectName, colorIndex}`.
3. Все бары проекта P1 имеют один и тот же `colorIndex`, равный `colorIndex` записи легенды P1; то же
   для P2.

### TC-CS-07 — Цвет детерминирован по projectId между запросами
_Покрывает: R3.2_
1. Выполнить `GET /api/company-schedule?projectIds={P1},{P2}` дважды подряд.
2. `colorIndex` для каждого `projectId` **идентичен** в обоих ответах (не зависит от времени запроса).

---

## Группа 4. Абсолютные даты, unanchored / partial-date бары

### TC-CS-08 — Абсолютные даты worker-бара через calendarDayOfHalfDay
_Покрывает: R4.1_
**Предусловия:** проект `P1` с известным `startDate` (anchor); worker-бар `W1` с конкретными
`startHalfDays` / `durationHalfDays`.
1. `GET /api/company-schedule?projectIds={P1}&memberIds={W1}` → **200**.
2. Для бара: `startDate = anchor + (calendarDayOfHalfDay(startHalfDays) - 1)` дней и
   `endDate = anchor + (calendarDayOfHalfDay(startHalfDays + durationHalfDays - 1) - 1)` дней
   (формула из `ScheduleCalculator`). Сверить вычисленные даты с ответом.

### TC-CS-09 — Worker-бар unanchored-проекта: null-даты + unanchored
_Покрывает: R4.3, R4.5_
**Предусловия:** проект `P7` с `startDate = null`; worker-бар `W3`.
1. `GET /api/company-schedule?projectIds={P7}` (без Date_Window) → **200**.
2. Бар W3 присутствует, `startDate = null`, `endDate = null`, `unanchored = true`, но `startHalfDays`
   / `durationHalfDays` сохранены.

### TC-CS-10 — Foreman-span датируется только при обеих датах проекта
_Покрывает: R4.2, R4.4_
**Предусловия:** проект `P8` с обеими датами; проект `P9` с `endDate = null`; у обоих прораб `F2`.
1. `GET /api/company-schedule?projectIds={P8},{P9}&roles=FOREMAN` → **200**.
2. Span-бар P8: `startDate = P8.startDate`, `endDate = P8.endDate`, `unanchored = false`.
3. Span-бар P9: `startDate = null`, `endDate = null`, `unanchored = true`.

---

## Группа 5. Конфликты (только worker-бары)

### TC-CS-11 — Пересекающиеся worker-бары → конфликт
_Покрывает: R5.1, R5.2_
**Предусловия:** WORKER `W1` имеет два датируемых worker-бара (в P1 и P2), интервалы которых
пересекаются по правилу `a.start <= b.end && b.start <= a.end`.
1. `GET /api/company-schedule?memberIds={W1}&projectIds={P1},{P2}` → **200**.
2. В строке W1 массив `conflicts` содержит пару индексов `{barIndexA, barIndexB}`, указывающих на эти
   два бара.

### TC-CS-12 — Undatable-бар не участвует в конфликте
_Покрывает: R5.4_
**Предусловия:** WORKER `W3` имеет один датируемый worker-бар и один бар из unanchored-проекта
(`startDate = null`).
1. `GET /api/company-schedule?memberIds={W3}` (без Date_Window) → **200**.
2. `conflicts` строки W3 **не** содержит пары с undatable-баром.

### TC-CS-13 — Foreman-span не участвует в конфликте, пересечения проектов не флагируются
_Покрывает: R5.5, R5.6_
**Предусловия:** FOREMAN `F1` ведёт P3 и P4 с перекрывающимися датами проектов.
1. `GET /api/company-schedule?memberIds={F1}&roles=FOREMAN` → **200**.
2. `conflicts` строки F1 — **пусто**, несмотря на перекрытие span-баров.
3. Ни один конфликт не связывает бары двух разных пользователей (проверка по всем строкам ответа).

---

## Группа 6. Приоритет прораба над собственными worker-барами

### TC-CS-14 — User одновременно FOREMAN и владелец worker-баров в одном проекте
_Покрывает: R1.8_
**Предусловия:** в проекте `P3` пользователь `U1` — ACTIVE FOREMAN, и у него же есть worker-бары в
том же P3.
1. `GET /api/company-schedule?projectIds={P3}&memberIds={U1}` → **200**.
2. В строке U1 для P3 присутствует **только** бар `kind = FOREMAN_SPAN`.
3. **Нет** ни одного `kind = WORKER_CATEGORY` бара пользователя U1 для проекта P3.

---

## Группа 7. Scope и ABAC

### TC-CS-15 — ADMIN видит все проекты (bypass scope)
_Покрывает: R6.2, R9.1, R9.4_
1. `GET /api/company-schedule` под ADMIN (без фильтров) → **200**; проекция включает бары проектов,
   в которых ADMIN не является участником (видны все).

### TC-CS-16 — Non-ADMIN видит только свои проекты
_Покрывает: R6.1_
**Предусловия:** MANAGER `M1` — участник только P1; существует P2, где M1 не участник, с барами.
1. `GET /api/company-schedule` под M1 → **200**.
2. Проекция содержит бары только из P1; баров/легенды P2 нет.

### TC-CS-17 — Non-ADMIN без доступных проектов → пустая проекция
_Покрывает: R6.3, R10.1_
**Предусловия:** MANAGER `M2` не участник ни одного проекта.
1. `GET /api/company-schedule` под M2 → **200**, `rows = []`, `legend = []` (не ошибка).

### TC-CS-18 — ABAC: READ выдан ADMIN и MANAGER, запрещён WORKER/CLIENT
_Покрывает: R9.1, R9.2, R9.4_
1. `GET /api/company-schedule` под ADMIN → **200**.
2. `GET /api/company-schedule` под MANAGER → **200**.
3. `GET /api/company-schedule` под WORKER → **403**.
4. `GET /api/company-schedule` под CLIENT → **403** (ресурс `COMPANY_SCHEDULE` им не выдан).
5. Факт успешного старта `backend` подтверждает, что `PermissionAnnotationValidator` не упал на
   `@PermissionResource("COMPANY_SCHEDULE")` + READ-хендлере.

---

## Группа 8. Серверные фильтры (role / project / member / date)

### TC-CS-19 — Role_Filter: FOREMAN / WORKER / оба
_Покрывает: R7.2_
1. `GET /api/company-schedule?roles=FOREMAN` → только строки с `companyRole = FOREMAN` и
   span-барами.
2. `GET /api/company-schedule?roles=WORKER` → только строки с worker-барами.
3. `GET /api/company-schedule` (без `roles`) → обе роли присутствуют.

### TC-CS-20 — Project_Filter игнорирует out-of-scope id, не расширяя scope
_Покрывает: R7.3, R7.7_
**Предусловия:** MANAGER `M1` — участник P1, НЕ участник P2.
1. `GET /api/company-schedule?projectIds={P1},{P2}` под M1 → **200**.
2. Результат идентичен запросу `?projectIds={P1}`: бары P2 отсутствуют, ошибки нет, факт
   существования P2 не раскрыт (id молча проигнорирован).

### TC-CS-21 — Member_Filter по userId
_Покрывает: R7.4_
1. `GET /api/company-schedule?memberIds={W1}` → только строка(и) с `userId = W1`.
2. `GET /api/company-schedule` (без `memberIds`) → все участники.

### TC-CS-22 — Date_Window: пересечение интервала, исключение undatable
_Покрывает: R7.5, R7.6, R4.5_
**Предусловия:** W1 имеет датируемый бар в окне `[from, to]` и бар вне окна; W3 имеет undatable-бар.
1. `GET /api/company-schedule?from={D1}&to={D2}` → в результат попадают только бары, чей интервал
   пересекает `[D1, D2]`; **все** undatable-бары исключены (т.к. задана граница окна).
2. `GET /api/company-schedule` (без `from`/`to`) → присутствуют и датируемые, и undatable-бары.

### TC-CS-23 — Конъюнкция фильтров (AND), без расширения scope
_Покрывает: R7.7, R7.1_
1. `GET /api/company-schedule?roles=WORKER&projectIds={P1}&memberIds={W1}&from={D1}&to={D2}` →
   **200**; каждый бар в ответе удовлетворяет **всем** фильтрам одновременно и принадлежит
   доступному проекту.

---

## Группа 9. Валидация Date_Window `from > to`

### TC-CS-24 — `from` позже `to` → 400, данные не меняются
_Покрывает: R7.8, R7.9_
1. `GET /api/company-schedule?from=2026-05-10&to=2026-05-01` → **400** с кодом
   `error.company.schedule.window.invalid`.
2. Контрольное чтение `GET /api/company-schedule` после этого → **200**; никакие
   `project_schedules` / `project_schedule_bars` не изменены, версия расписаний не инкрементирована
   (эндпоинт read-only).

---

## Группа 10. Пустые и краевые случаи

### TC-CS-25 — Доступный проект без строки project_schedules
_Покрывает: R10.2_
**Предусловия:** проект `P10` создан, расписание ни разу не сохранялось; у P10 есть ACTIVE
FOREMAN `F3`.
1. `GET /api/company-schedule?projectIds={P10}` под ADMIN → **200** (без ошибки).
2. Worker-баров из P10 нет; при этом span-бар прораба F3 присутствует.

### TC-CS-26 — Пустой результат после фильтров → строка опускается
_Покрывает: R10.3_
**Предусловия:** у W1 все бары попадают вне выбранного Date_Window.
1. `GET /api/company-schedule?memberIds={W1}&from={D3}&to={D4}` (окно без баров W1) → **200**.
2. В `rows` **нет** строки W1 (строка эмитится только при ≥1 включённом баре).

### TC-CS-27 — Детерминированный порядок строк/баров/легенды
_Покрывает: R10.4_
1. Выполнить один и тот же `GET /api/company-schedule?projectIds={P1},{P2}` дважды.
2. Порядок `rows`, порядок `bars` внутри строки и порядок `legend` **идентичны** в обоих ответах
   (идентичный вход → идентичный ответ).

---

## Группа 11. Отсутствие стоимостных / чувствительных полей

### TC-CS-28 — В проекции нет денег, man-days, ставок, pay-tier
_Покрывает: R8.1, R8.2, R8.3_
1. `GET /api/company-schedule` под ADMIN с наполненными данными → **200**.
2. Ни в одной `MemberRow`, ни в одном `ProjectedBar`, ни в записи `legend` нет полей суммы денег,
   man-days, дневной выработки (Daily_Output_Rate), ставки или pay-tier/типа работника.

---

## Регрессия

Регрессионная группа для перепроверки сквозных путей и ранее критичных инвариантов спеки. Прогоняется
после любых изменений в проекторе, сервисе, контроллере или сид-данных ABAC.

### TC-CS-REG-01 — Сквозной happy-path проекции
_Покрывает: R1.1, R3.1, R4.1, R5.1_
Полный сценарий: два датированных проекта с пересекающимися worker-барами одного работника и прорабом
на одном из них → `GET /api/company-schedule` возвращает одну строку работника с конфликтом, строку
прораба со span-баром, легенду с двумя стабильными цветами, корректные абсолютные даты.

### TC-CS-REG-02 — Инвариант scope под non-ADMIN после изменения членства
_Покрывает: R6.1, R6.4_
После удаления участника из проекта (инвалидация `ProjectAccessCache`) повторный
`GET /api/company-schedule` под этим non-ADMIN больше не возвращает бары утраченного проекта.

### TC-CS-REG-03 — Read-only гарантия
_Покрывает: R7.9_
Серия из N запросов `GET /api/company-schedule` (с фильтрами и без) не создаёт/не изменяет/не удаляет
ни одной строки расписаний и не меняет версию ни одного `project_schedules`.

### TC-CS-REG-04 — Старт приложения с сид-ресурсом COMPANY_SCHEDULE
_Покрывает: R9.1, R9.2_
После чистого `docker compose up` (прогон changeset 187) ресурс `COMPANY_SCHEDULE` засижен с READ для
ADMIN и MANAGER и без CREATE/UPDATE/DELETE; повторный прогон миграций идемпотентен (дубликатов нет).

---

## Шаблон MD-репорта прогона

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-CS-01 | 1 | `GET /api/company-schedule?projectIds=P1,P2` (ADMIN) | 200 | | |
| 2 | TC-CS-01 | 2 | проверка: одна строка на W1 | 1 строка | | |
| 3 | TC-CS-24 | 1 | `GET /api/company-schedule?from=2026-05-10&to=2026-05-01` | 400 `error.company.schedule.window.invalid` | | |
| … | … | … | … | … | | |

Итог: **X пройдено / Y провалено / Z пропущено**.
Run-id: `<timestamp/uuid>`.
Стратегия повторяемости: **генератор run-id в именах фикстур + teardown созданных проектов/участников**.
Окружение: `docker compose up`, backend `http://localhost:8080`, профиль `docker`, changeset `187` применён.
