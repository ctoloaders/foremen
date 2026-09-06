# FOR-04: Базовые сущности и справочники

## Обзор

FOR-04 закладывает предметный фундамент Foremen: все базовые справочники (единицы измерения, валюты, ставки VAT, типы помещений, категории работ, категории/статусы доставок, категории материалов, пакеты предложений) и ключевые сущности (каталог работ + история цен, проект, помещение), на которые опираются последующие спеки — смета (FOR-05), план-график (FOR-06), трекинг закупок (FOR-07), AI-смета (FOR-08), клиентский портал (FOR-09), финучёт (FOR-10).

Спека также содержит первую подспеку с общим UX-улучшением таблиц — фильтрацией по связанным сущностям (reference-фильтры) — которое делается раньше остальных, потому что становится дефолтным поведением для всех таблиц админки, включая уже существующие (Users, Roles) и все новые справочники FOR-04.

## Контекст: что уже есть

| Компонент | Спека | Использование в FOR-04 |
|-----------|-------|------------------------|
| CRUD-фреймворк (AdminService/AdminController, DAO, MapStruct, i18n-маппинг) | FOR-01 | Каждый справочник/сущность реализуется как generic CRUD-ресурс |
| Метаданные таблицы (`GET /{resource}/metadata`: имена полей, типы, i18n-флаги) | FOR-01/02 | Расширяются reference-дескриптором для FOR-04-01 |
| DataTable, шаблон таблицы, серверные фильтры (RSQL/CustomQueryBuilder) | FOR-02-06t | Расширяются reference-фильтром |
| ABAC (resources, operations, role_resources, role_resource_operations) | FOR-02-03/04 | Каждый новый ресурс сидится в матрицу |
| `@PermissionResource`/`@PermissionOperation`, PermissionResolver, startup-валидатор | FOR-03-08 | Каждый контроллер аннотируется |
| `ProjectScopedService.getProjectIdPath()` — авто-фильтрация по проекту | FOR-03-04 | Project-scoped сущности (Project, Room) подключают фильтрацию |
| Liquibase changelog + сид-паттерн (changesets, `entity-creation-rules.md`) | FOR-02/03 | Справочники сидятся миграциями (сразу на прод) |

## Принципы

- **i18n:** все справочники и каталог работ используют пару `nameRU` / `namePL` (плюс `descriptionRU` / `descriptionPL`, где есть описание). Только RU и PL, PL — фолбэк. Соответствует существующим `RoleEntity` / `ResourceEntity`.
- **Справочники — глобальные admin-ресурсы** (не project-scoped): CRUD у ADMIN, READ у остальных системных ролей, недоступны CLIENT. Сидятся Liquibase-миграциями (данные попадают на прод при деплое).
- **Каталог работ** — глобальный ресурс; цена вынесена в отдельную сущность с временем действия (история цен).
- **Проект и помещение — project-scoped**: доступ фильтруется через `project_members` (FOR-03-04). Проект — «якорь» проектной области; `projects` до FOR-04 была `BIGINT` без FK — FOR-04 создаёт таблицу `projects`.
- **ABAC-сид на ресурс** — вшивается в спеку самой сущности (по стандарту `.kiro/steering/entity-creation-rules.md`): seed-changeset ресурса + грант ADMIN + гранты остальных ролей по матрице ниже.
- **Один дочерний спек = одна сущность/справочник** (кроме FOR-04-01 — сквозного UX-улучшения).

## Полный каталог справочников и сущностей

### Справочники (seeded Liquibase → prod)

| Ресурс (code) | Сущность | Ключевые поля | Сид |
|---------------|----------|---------------|-----|
| `MEASUREMENT_UNITS` | MeasurementUnit | code (m2/mb/szt/kpl/godz/proc/m3), nameRU, namePL, active | m², mb, szt, kpl, godz, %, m³ |
| `CURRENCIES` | Currency | code (ISO-4217: PLN/EUR/USD), symbol, nameRU, namePL, active | PLN, EUR, USD |
| `VAT_RATES` | VatRate | rate (numeric %), nameRU, namePL, isDefault, active | 23%, 8%, 5%, 0% |
| `ROOM_TYPES` | RoomType | code, nameRU, namePL, active | przedpokój, hol, kuchnia, salon, biuro, master, pokój, łazienka |
| `WORK_CATEGORIES` | WorkCategory | code, orderNo (1..13), nameRU, namePL, active | 13 категорий (см. ниже) |
| `DELIVERY_CATEGORIES` | DeliveryCategory | code, nameRU, namePL, active | Wyposażenie łazienki, Akcesoria, Dekor, Płytki, Farby, Oświetlenie, Podłoga, Drzwi, Okna |
| `DELIVERY_STATUSES` | DeliveryStatus | code, orderNo, nameRU, namePL, active | Nowe, Zamówione, Dostarczone, Anulowane |
| `MATERIAL_CATEGORIES` | MaterialCategory | code, nameRU, namePL, active | Construction, Finishing |
| `OFFER_PACKAGES` | OfferPackage | code, orderNo, nameRU, namePL, active | Budget/START, Norm/COMFORT, Lux/PRESTIGE |

### Категории работ (сид, порядок и польские названия — источник правды)

| № | code | namePL | nameRU |
|---|------|--------|--------|
| 1 | `PRELIMINARY` | PRACE WSTĘPNE, DEMONTAŻE | Подготовительные работы, демонтажи |
| 2 | `CONSTRUCTIONS_GK` | KONSTRUKCJE I GK | Конструкции и ГК |
| 3 | `PLUMBING_ROUGH` | HYDRAULIKA STAN SUROWY | Сантехника черновая |
| 4 | `PLUMBING_FINISH` | HYDRAULIKA BIAŁY MONTAŻ | Сантехника чистовая |
| 5 | `ELECTRICAL_ROUGH` | ELEKTRYKA STAN SUROWY | Электрика черновая |
| 6 | `ELECTRICAL_FINISH` | ELEKTRYKA BIAŁY MONTAŻ | Электрика чистовая |
| 7 | `TILING` | PRACE GLAZURNICZE | Плиточные работы |
| 8 | `PLASTERING` | GŁADZIE, PRZYGOTOWANIE PODŁOŻA | Шпаклёвка, подготовка основания |
| 9 | `PAINTING_DECOR` | PRACE MALARSKIE, SZTUKATERIA, DEKOR | Малярные работы, лепнина, декор |
| 10 | `FLOORS` | POSADZKI | Полы |
| 11 | `CARPENTRY` | STOLARKA | Столярка |
| 12 | `EXTRAS` | DODATKI | Дополнения |
| 13 | `OTHER` | INNE / KOORDYNACJA / NIESTANDARDOWE | Прочее / координация / нестандартное |

### Ключевые сущности

| Ресурс (code) | Сущность | Ключевые поля | Scope |
|---------------|----------|---------------|-------|
| `WORK_CATALOG` | WorkItem | workCategory (FK), unit (FK MeasurementUnit), nameRU, namePL, active | global |
| `WORK_PRICES` | WorkPrice | workItem (FK), currency (FK), netPrice, validFrom, validTo (null=текущая) | global |
| `PROJECTS` | Project | name, address, area, client (FK user), manager (FK user), startDate, endDate, status | project (anchor) |
| `ROOMS` | Room | project (FK), roomType (FK), dimensions (площади/периметр/высоты/проёмы), auto-calc поля | project (`project.id`) |

**Модель цены (WorkPrice):** цена работы = (workItem, currency, netPrice, validFrom, validTo). Текущая цена = строка с `validTo IS NULL`. История цен сохраняется, смета ссылается на действующую на дату цену. Сид цен — из существующего Excel-прейскуранта (`docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv`): категория-заголовок → WorkCategory; строки `N,MM` → WorkItem (namePL=`ZAKRES`, nameRU=`ПЕРЕЧЕНЬ РАБОТ`, unit=`JM`) + WorkPrice (netPrice=`CENA`, currency=PLN, validFrom=дата сида).

## Базовая матрица доступа (системные роли × ресурс)

Операции: C=CREATE, R=READ, U=UPDATE, D=DELETE. «(own)» = автофильтрация по `project_members`. ADMIN обходит матрицу (bypass), но гранты всё равно сидятся для полноты.

| Ресурс | ADMIN | MANAGER | FOREMAN | WORKER | FINANCIER | CLIENT |
|--------|:-----:|:-------:|:-------:|:------:|:---------:|:------:|
| MEASUREMENT_UNITS | CRUD | R | R | R | R | — |
| CURRENCIES | CRUD | R | R | R | R | — |
| VAT_RATES | CRUD | R | R | R | R | — |
| ROOM_TYPES | CRUD | R | R | R | — | — |
| WORK_CATEGORIES | CRUD | R | R | R | R | — |
| DELIVERY_CATEGORIES | CRUD | R | R | R | — | — |
| DELIVERY_STATUSES | CRUD | R | R | R | — | — |
| MATERIAL_CATEGORIES | CRUD | R | R | R | R | — |
| OFFER_PACKAGES | CRUD | R | R | R | R | — |
| WORK_CATALOG | CRUD | CRU | R | R | R | — |
| WORK_PRICES | CRUD | CRU | R | R | R | — |
| PROJECTS | CRUD | CRU(own) | R(own) | R(own) | R(own) | R(own) |
| ROOMS | CRUD | CRUD(own) | R(own) | — | — | — |

Примечания:
- Справочники правит только ADMIN; остальным нужен READ, чтобы выбирать значения в формах (единицы, валюты, VAT, категории и т.д.).
- WORK_CATALOG/WORK_PRICES: MANAGER может дополнять/править (без удаления), остальные — только читать.
- PROJECTS: MANAGER создаёт/ведёт свои проекты; FOREMAN/WORKER/FINANCIER/CLIENT видят свои (по membership). DELETE проектов — только ADMIN.
- ROOMS: правит ADMIN/MANAGER (свои проекты), FOREMAN читает свои; для WORKER/FINANCIER/CLIENT помещения недоступны напрямую (они видят производные — смету/график).

## Группировка в главном меню

Существующие группы: (без заголовка) Dashboard/Projects/Rooms/Estimate; «Склад» (Materials/Finances/Deliveries); «Система» (Users/Roles/Audit); «Настройки» (Appearance). FOR-04 добавляет:

- **Каталог / Catalog** (новая группа): Work Catalog (`/catalog/works`), Work Prices (`/catalog/prices`)
- **Проекты / Projects** (существующая безымянная группа): Projects (`/projects`), Rooms (`/rooms`) — привязываются к реальным сущностям FOR-04
- **Справочники / Dictionaries** (новая сворачиваемая группа, `nav.sections.dictionaries`): Measurement Units, Currencies, VAT Rates, Room Types, Work Categories, Delivery Categories, Delivery Statuses, Material Categories, Offer Packages

Все пункты меню фильтруются по правам (FOR-03-07): роль без READ на ресурс не видит пункт. i18n-ключи меню и страниц справочников добавляются в `pl.json` / `ru.json` (PL + RU).

## Стадии работы (дочерние спеки)

| # | Спека | Тип | Описание | Зависит от |
|---|-------|-----|----------|------------|
| 01 | FOR-04-01-table-reference-filter | сквозной UX | Фильтрация таблиц по связанным сущностям: reference-дескриптор в метаданных, мини-дропдаун с инфинит-скроллом (алфавит по имени, бекенд-серч), single → `x.id==id`, multi (чекбоксы) → `x.id=in=(...)`. Дефолт для всех таблиц. **Плюс мобильные/эргономические фиксы таблицы:** резолв i18n-ключей контролов, кликабельная панель фильтров, липкие поиск/тоггл сверху и пагинация снизу, единообразные карточки, явная сортировка, применение → схлоп → саммари «N фильтров · M сортировок». | FOR-02-06t, FOR-01 |
| 02 | FOR-04-02-measurement-units | справочник | MeasurementUnit + сид + ABAC | 01 |
| 03 | FOR-04-03-currencies | справочник | Currency + сид + ABAC | 01 |
| 04 | FOR-04-04-vat-rates | справочник | VatRate + сид + ABAC | 01 |
| 05 | FOR-04-05-room-types | справочник | RoomType + сид + ABAC | 01 |
| 06 | FOR-04-06-work-categories | справочник | WorkCategory + сид (13 категорий) + ABAC | 01 |
| 07 | FOR-04-07-delivery-categories | справочник | DeliveryCategory + сид + ABAC | 01 |
| 08 | FOR-04-08-delivery-statuses | справочник | DeliveryStatus + сид + ABAC | 01 |
| 09 | FOR-04-09-material-categories | справочник | MaterialCategory + сид + ABAC | 01 |
| 10 | FOR-04-10-offer-packages | справочник | OfferPackage + сид + ABAC | 01 |
| 11 | FOR-04-11-work-catalog | сущность | WorkItem (категория + единица + i18n) + ABAC | 06, 02 |
| 12 | FOR-04-12-work-prices | сущность | WorkPrice (история цен) + сид из Excel-прейскуранта + ABAC | 11, 03 |
| 13 | FOR-04-13-project | сущность | Project (создаёт таблицу `projects`, project-scoped anchor) + ABAC | 01 |
| 14 | FOR-04-14-room | сущность | Room (размеры, авто-расчёт, `getProjectIdPath()="project.id"`) + ABAC | 13, 05 |
| 15 | FOR-04-15-menu-grouping | фронтенд | Группа «Справочники» + «Каталог» в NAV_CONFIG, i18n-ключи (PL/RU), привязка пунктов к ресурсам | 02–14 |

### Граф зависимостей

```
01-table-reference-filter ──> (дефолт для всех таблиц ниже)

02 units ─┐
03 currencies ─┼─> 11 work-catalog ──> 12 work-prices (сид из Excel)
06 work-categories ─┘

05 room-types ──┐
13 project ─────┴─> 14 room

02..14 ──> 15 menu-grouping
```

## Стратегия сидирования

- Справочники (02–10) и категории работ (06): каждая — отдельный Liquibase changeset(ы) в `foremen-backend/database_files/changesets/`, зарегистрированный последним в `changelog.xml`, идемпотентный (`preConditions onFail="MARK_RAN"` + `sqlCheck` на `code`/`rate`). Данные попадают на прод при миграции.
- ABAC-сид на каждый ресурс — в том же наборе миграций спеки этой сущности (по `entity-creation-rules.md`): вставка ресурса + грант ADMIN + гранты по матрице.
- Каталог работ (11) и цены (12): сид из `docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv`. Парсинг CSV → генерация changeset'ов: WorkCategory (уже засижены в 06), WorkItem, WorkPrice (PLN, validFrom = дата сида, validTo = null).

## Нефункциональные требования

- Все текстовые предметные поля — `nameRU`/`namePL` (+ `descriptionRU`/`descriptionPL` при наличии), PL-фолбэк.
- Сиды идемпотентны и безопасны для повторного прогона на засиженной БД.
- Reference-фильтр (01) — обратно совместим: таблицы без reference-полей работают как раньше; поля-ссылки получают дропдаун автоматически по метаданным.
- Каждый новый контроллер проходит startup-валидацию аннотаций (FOR-03-08): `@PermissionResource` + `@PermissionOperation`/`@RequiresPermission` обязательны.
