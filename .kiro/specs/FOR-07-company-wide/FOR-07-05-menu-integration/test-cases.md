# Тест-кейсы: FOR-07-05 — Интеграция меню

Тип спеки: **UI** (с одним backend-грантом). Кейсы исполняются браузерным движком против
Docker-стека (`docker compose up`: frontend `http://localhost:3000`, backend `http://localhost:8080`).
Результат прогона — MD-репорт с таблицами (см. шаблон в конце), скриншоты не требуются.

Вьюпорты: **Mobile 390×844** (BottomNav + Drawer), **Desktop 1280×800** (Sidebar).

## Повторяемость

Стратегия: **генератор данных + teardown**.
- `run-id` = `YYYYMMDDHHmmss`. Тестовые пользователи создаются через админ-API/UI с email
  `menu+{role}-{run-id}@example.com` и ролями WORKER, FOREMAN, FINANCIER, MANAGER, CLIENT.
- Для кейсов с рабочим проектом создаётся проект `MENU-{run-id}` и все тестовые пользователи
  добавляются в него (FOREMAN — как прораб).
- Teardown: деактивировать созданных пользователей, удалить/архивировать проект `MENU-{run-id}`,
  очистить `localStorage` ключ `foremen.workingProjectId`.
- Первый ADMIN: `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`.

---

## Группа A. Структура меню (Desktop, Sidebar)

### TC-A1. ADMIN видит финальную структуру
**Предусловия:** вход под ADMIN, Desktop.
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Открыть `/` | Sidebar: первая секция без заголовка — «Panel», «Dodaj wydatek» |
| 2 | Прочитать секции сверху вниз | Порядок: (без заголовка), **Firma**, **Zasoby**, **Słowniki**, **System**, **Ustawienia** |
| 3 | Раздел Firma | «Grafik zespołu», «Zakupy firmowe», «Wynagrodzenia», «Operacje bankowe» именно в этом порядке |
| 4 | Раздел Zasoby | «Dostawy», «Materiały budowlane», «Materiały wykończeniowe», «Katalog prac» |
| 5 | Проверить отсутствие | Нет секций «Magazyn», «Katalog»; нет пунктов «Finanse», «Pomieszczenia», «Kosztorys», «Projekty», «Ceny prac», «Zużycie materiałów» |
| 6 | Раздел System | Содержит «Użytkownicy», «Role», «Audyt», «Typy dokumentów», «Szablony dokumentów», «Bank» |

### TC-A2. Словари не изменились
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Свернуть секцию «Słowniki» | Пункты скрыты, `aria-expanded=false` |
| 2 | Развернуть | Все прежние пункты на месте, порядок прежний |

### TC-A3. Переходы по пунктам Zasoby
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Клик «Dostawy» | URL `/deliveries`, страница рендерится без ошибок |
| 2 | Клик «Materiały budowlane» | URL `/materials/construction` |
| 3 | Клик «Materiały wykończeniowe» | URL `/materials/finishing` |
| 4 | Клик «Katalog prac» | URL `/catalog/works` |

### TC-A4. Удалённые маршруты
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Открыть `/rooms` | Страница NotFound |
| 2 | Открыть `/estimate` | NotFound |
| 3 | Открыть `/finances` | NotFound |
| 4 | Открыть `/projects` | Список проектов (маршрут жив) |
| 5 | Открыть `/projects/{id}/estimate` и `/projects/{id}/rooms` | Вкладки рабочего пространства открываются |

### TC-A5. Локализация (PL/RU)
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Язык PL | «Zasoby», «Panel», «Dodaj wydatek», «Dostawy», «Katalog prac», «Grafik zespołu», «Zakupy firmowe», «Wynagrodzenia», «Operacje bankowe» |
| 2 | Переключить на RU | «Ресурсы» и русские значения всех пунктов; нет сырых ключей `nav.*` |

### TC-A6. Фильтрация по правам (секция скрывается целиком)
**Предусловия:** пользователь CLIENT (нет прав на пункты Firma/Zasoby).
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Войти, открыть `/` | Заголовки секций без доступных пунктов не показываются |
| 2 | Прямой переход на `/company/payroll` | Страница 403 |

---

## Группа B. Нижняя навигация по ролям (Mobile)

Для каждого кейса: войти под ролью, Mobile 390×844, установить рабочий проект `MENU-{run-id}` (если не указано иное).

### TC-B1. WORKER
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Открыть `/` | В нижней панели ровно 3 пункта: **проект**, **Dodaj raport**, **Dodaj wydatek** |
| 2 | Клик на пункт проекта | URL `/projects/{id}`; подпись — название проекта (усечено) |
| 3 | Клик «Dodaj raport» | URL `/projects/{id}/workReporting` |
| 4 | Клик «Dodaj wydatek» | URL `/expenses/new` |

### TC-B2. FOREMAN
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Открыть `/` | 3 пункта: **проект**, **Grafik zespołu**, **Dodaj wydatek** |
| 2 | Клик «Grafik zespołu» | URL `/company/staffing-gantt`, статус 200, данные только по проектам, где FOREMAN участник |
| 3 | Проверить отсутствие | Нет «Dodaj raport» |

### TC-B3. FINANCIER
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Открыть `/` | 3 пункта: **проект**, **Zakupy firmowe**, **Operacje bankowe** |
| 2 | Клик по каждому | URL `/company/procurement`, `/company/bank-transactions` |

### TC-B4. MANAGER
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Открыть `/` | 4 пункта: **проект**, **Grafik zespołu**, **Zakupy firmowe**, **Operacje bankowe** |

### TC-B5. ADMIN
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Открыть `/` | Тот же набор, что у MANAGER (4 пункта) |

### TC-B6. Прочие роли (CLIENT)
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Открыть `/` | Только пункт проекта (если есть `PROJECTS`/READ), без остальных |

### TC-B7. Нет рабочего проекта
**Предусловия:** `localStorage.removeItem('foremen.workingProjectId')`.
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | WORKER, открыть `/` | Пункт проекта подписан «Projekt», ведёт на `/projects` |
| 2 | Клик «Dodaj raport» | Переход на `/projects` (сначала выбрать проект) |
| 3 | Выбрать проект в списке | Рабочий проект установлен; пункты нижней панели обновились без перезагрузки (подпись = имя проекта) |

### TC-B8. Скрытие пункта без права
**Предусловия:** у роли временно отозвано право (например, `EXPENSE_UPLOAD`/CREATE у WORKER в матрице).
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Перелогиниться WORKER | «Dodaj wydatek» отсутствует, остальные пункты на месте |
| 2 | **Teardown** | Вернуть право, проверить появление пункта |

### TC-B9. Остальное доступно в раскрывающемся меню
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | MANAGER, Mobile: нажать кнопку меню в TopBar | Открывается Drawer со всеми доступными секциями (Firma, Zasoby, Słowniki…) |
| 2 | Клик «Materiały budowlane» в Drawer | Переход на `/materials/construction`, Drawer закрывается |
| 3 | WORKER: открыть Drawer | Пункты, которых нет в нижней панели и на которые есть право (например, «Dostawy»), присутствуют |

### TC-B10. Desktop не затронут
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Desktop 1280×800, любая роль | Нижней панели нет; Sidebar по структуре из TC-A1 |

---

## Группа C. Доступ FOREMAN к графику компании (API, `http://localhost:8080`)

### TC-C1. Грант выдан, область ограничена
| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | `POST /api/auth/login` FOREMAN | 200 + токен |
| 2 | `GET /api/company-schedule?from=..&to=..` | 200; в ответе только проекты, где FOREMAN — участник |
| 3 | ADMIN: тот же запрос | 200; данные по всем проектам |

### TC-C2. Нет лишних прав
| Шаг | Запрос | Ожидание |
|-----|--------|----------|
| 1 | FOREMAN: `GET /api/company-purchases/documents` | 403 |
| 2 | FOREMAN: `GET /api/payroll/workers` (company payroll) | 403 |
| 3 | WORKER: `GET /api/company-schedule` | 403 |

### TC-C3. Идемпотентность миграции
| Шаг | Действие | Ожидание |
|-----|----------|----------|
| 1 | Перезапустить `liquibase` | Changeset 217 `MARK_RAN`/уже применён, дублей в `role_resources`/`role_resource_operations` нет |

---

## Регрессия

| ID | Кейс | Ожидание |
|----|------|----------|
| R1 | Универсальная загрузка расхода `/expenses/new` (FOR-07-03) доступна из меню и нижней панели | Форма открывается, загрузка работает |
| R2 | Рабочее пространство проекта: все вкладки (в т.ч. Pomieszczenia, Kosztorys) открываются | Без ошибок, права на вкладки как раньше |
| R3 | Переключение рабочего проекта из Sidebar/TopBar | Нижняя панель на мобильном синхронно обновляется |
| R4 | Страницы Operacje bankowe, Wynagrodzenia, Zakupy firmowe, Grafik zespołu | Открываются по прямым URL для разрешённых ролей, 403 для остальных |
| R5 | Меню System → Bank (`/bank`) для ADMIN/FINANCIER | Пункт виден и открывается |
| R6 | Переключение PL ↔ RU | Все подписи нижней панели и Drawer переведены |

---

## Шаблон MD-репорта прогона

Run-id: `<YYYYMMDDHHmmss>` · Стратегия повторяемости: генератор + teardown · Viewport: `<390×844 | 1280×800>`

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-A1 | 1 | Открыть `/` под ADMIN | Panel, Dodaj wydatek | | |
| 2 | TC-B2 | 2 | Клик «Grafik zespołu» (FOREMAN) | `/company/staffing-gantt`, 200 | | |

Итог: X пройдено / Y провалено / Z пропущено. Дефекты: `<ссылки>`.
