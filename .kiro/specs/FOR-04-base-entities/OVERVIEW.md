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
- **Каждая дочерняя спека справочника/сущности включает свой UI**: помимо бэкенд-вертикали (entity + CRUD-ресурс + сид + ABAC), она реализует и админ-страницу CRUD на базе общего DataTable (страница + роут + api-клиент + форма create/edit + удаление + i18n `<entity>.*` на PL/RU), с интерим route-guard'ом до появления пункта меню. FOR-04-15 отвечает ТОЛЬКО за групповую навигацию главного меню («Справочники»/«Каталог») и привязку пунктов к маршрутам — не за сами страницы.
- **Каталог работ** — глобальный ресурс; цена вынесена в отдельную сущность **на пакет предложения** (одна цена на пару работа+пакет, без интервалов); история цен ведётся через аудит и представление `PRICE_HISTORY` (FOR-05).
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
| `MATERIAL_CATEGORIES` | MaterialCategory | code, nameRU, namePL, active | 5 категорий из CSV: Drzwi, Listwy przypodłogowe, Podłoga, Płytki, Sanitariat (FOR-04-16 ПЕРЕ-сидит, заменяя устаревшие construction/finishing) |
| `OFFER_PACKAGES` | OfferPackage | code, orderNo, nameRU, namePL, active | Budget/START, Norm/COMFORT, Lux/PRESTIGE |
| `MATERIAL_TYPES` | MaterialType | code, nameRU, namePL, active — тип материала (Typ) | distinct значения колонки `Typ` из `docs/Materiały pakiety - Lista.csv` |
| `MATERIAL_PRODUCERS` | MaterialProducer | code, nameRU, namePL, active — производитель (Producent) | distinct значения колонки `Producent` из `docs/Materiały pakiety - Lista.csv` |
| `MATERIALS` | Material | code, nameRU, namePL, active — именованный материал (Materiał) | distinct значения колонки `Materiał` из `docs/Materiały pakiety - Lista.csv` |

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
| `WORK_PRICES` | WorkPrice | workItem (FK), offerPackage (FK), currency (FK), netPrice — одна строка на пару (работа, пакет), без интервалов | global |
| `PROJECTS` | Project | name, address, area, client (FK user), manager (FK user), startDate, endDate, status | project (anchor) |
| `ROOMS` | Room | project (FK), roomType (FK), dimensions (площади/периметр/высоты/проёмы), auto-calc поля | project (`project.id`) |
| `MATERIALS_CONSTRUCTION` | ConstructionMaterial | **name (nameRU/namePL)**, packages (M2M OfferPackage), unit (FK MeasurementUnit), purchasePrice, retailGross, retailNet, retailPerUnit (computed), active | global |
| `MATERIALS_FINISHING` | FinishingMaterial | packages (M2M OfferPackage ← `Pakiet`), category (FK MATERIAL_CATEGORIES ← `Kategoria`), material (FK MATERIALS ← `Materiał`), type (FK MATERIAL_TYPES ← `Typ`), producer (FK MATERIAL_PRODUCERS ← `Producent`), model (`Model`), sku (`SKU`), features (`Cechy`), purchasePrice (`Cena zakup`), retailGross (`Cena detal brutto`), retailNet (`Cena detal netto`), retailPerUnitM2 (`Cena detal / m2`), link (`Link`), photo (`Photo`), unit (FK MeasurementUnit), active | global |

**Материалы — фиксированное разделение на две ветки (НЕ справочник):** строительные (Construction) и отделочные (Finishing) материалы — это ДВЕ фиксированные ветки, управляемые как ОТДЕЛЬНЫЕ ресурсы (`MATERIALS_CONSTRUCTION` / `MATERIALS_FINISHING`) на ОТДЕЛЬНЫХ админ-страницах, с РАЗНЫМ набором полей. Это не значение справочника-категории, а зашитое двухветочное деление (разные таблицы/страницы). Поля отделочной ветки выводятся из CSV `Materiały pakiety - Lista.csv` (колонки Pakiet/Kategoria/Materiał/Typ/Producent/Model/SKU/Cechy/цены/Link/Photo); поля строительной ветки задаются по логике — без Producent/Typ/Model/SKU/Cechy, только `name`, packages, unit и цены.

**Модель цены (WorkPrice):** цена работы задаётся **на пакет предложения** — `(workItem, offerPackage, currency, netPrice)`, одна строка на пару (работа, пакет). Интервалы `validFrom` / `validTo` **убраны**: история цен ведётся через аудит и представление `PRICE_HISTORY` (FOR-05), а не через интервалы в WorkPrice. **Фолбэк:** если у пакета нет цены на работу, используется **максимум** цен этой работы по остальным пакетам.

Каталог цен работ (UI FOR-04-12) показывает **сводные (pivot) колонки** — по одной колонке на пакет предложения с заголовком «Cena {package}» (например, «Cena START», «Cena COMFORT», «Cena PRESTIGE»). Фильтрация и сортировка должны работать по вложенному ключу вида `prices.{package}.netPrice` — реализуется через кастомную колонку + метаданные, чтобы серверный RSQL-фильтр и сортировка работали по вложенному pivot-полю.

Сид цен — из существующего Excel-прейскуранта (`docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv`): категория-заголовок → WorkCategory; строки `N,MM` → WorkItem (namePL=`ZAKRES`, nameRU=`ПЕРЕЧЕНЬ РАБОТ`, unit=`JM`). Excel содержит колонки цен по пакетам `PAKIET START` / `COMFORT` / `PRESTIGE`, поэтому сид порождает **по одной WorkPrice на пару (работа, пакет)** (netPrice из соответствующей колонки пакета, currency=PLN).

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
| MATERIAL_TYPES | CRUD | R | R | R | R | — |
| MATERIAL_PRODUCERS | CRUD | R | R | R | R | — |
| MATERIALS | CRUD | R | R | R | R | — |
| WORK_CATALOG | CRUD | CRU | R | R | R | — |
| WORK_PRICES | CRUD | CRU | R | R | R | — |
| MATERIALS_FINISHING | CRUD | CRU | R | R | R | — |
| MATERIALS_CONSTRUCTION | CRUD | CRU | R | R | R | — |
| PROJECTS | CRUD | CRU(own) | R(own) | R(own) | R(own) | R(own) |
| ROOMS | CRUD | CRUD(own) | R(own) | — | — | — |

Примечания:
- Справочники правит только ADMIN; остальным нужен READ, чтобы выбирать значения в формах (единицы, валюты, VAT, категории и т.д.).
- MATERIAL_TYPES/MATERIAL_PRODUCERS/MATERIALS — глобальные справочники: CRUD у ADMIN, READ у остальных системных ролей, CLIENT недоступны.
- MATERIALS_FINISHING/MATERIALS_CONSTRUCTION: MANAGER может дополнять/править (без удаления), остальные системные роли — только читать; CLIENT недоступны.
- WORK_CATALOG/WORK_PRICES: MANAGER может дополнять/править (без удаления), остальные — только читать.
- PROJECTS: MANAGER создаёт/ведёт свои проекты; FOREMAN/WORKER/FINANCIER/CLIENT видят свои (по membership). DELETE проектов — только ADMIN.
- ROOMS: правит ADMIN/MANAGER (свои проекты), FOREMAN читает свои; для WORKER/FINANCIER/CLIENT помещения недоступны напрямую (они видят производные — смету/график).

## Группировка в главном меню

Существующие группы: (без заголовка) Dashboard/Projects/Rooms/Estimate; «Склад» (Materials/Finances/Deliveries); «Система» (Users/Roles/Audit); «Настройки» (Appearance). FOR-04 добавляет:

- **Каталог / Catalog** (новая группа): Work Catalog (`/catalog/works`), Work Prices (`/catalog/prices`)
- **Проекты / Projects** (существующая безымянная группа): Projects (`/projects`), Rooms (`/rooms`) — привязываются к реальным сущностям FOR-04
- **Справочники / Dictionaries** (новая сворачиваемая группа, `nav.sections.dictionaries`): Measurement Units, Currencies, VAT Rates, Room Types, Work Categories, Delivery Categories, Delivery Statuses, Material Categories, Offer Packages, **Material Types, Material Producers, Materials** (три новых справочника материалов — поставляются в FOR-04-16)
- **Склад / Warehouse** (существующая группа): к Materials/Finances/Deliveries добавляются **Construction Materials** (`/materials/construction`, FOR-04-17) и **Finishing Materials** (`/materials/finishing`, FOR-04-18) — материальные каталоги (две раздельные ветки)

Все пункты меню фильтруются по правам (FOR-03-07): роль без READ на ресурс не видит пункт (в т.ч. новые справочники материалов и каталоги Finishing/Construction Materials — видимость ABAC-gated, CLIENT их не видит). i18n-ключи меню и страниц справочников добавляются в `pl.json` / `ru.json` (PL + RU).

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
| 12 | FOR-04-12-work-prices | сущность | WorkPrice **на пакет** — `(workItem, offerPackage, currency, netPrice)`, одна строка на пару (работа, пакет), без интервалов; фолбэк = MAX цены по другим пакетам; UI со сводными (pivot) колонками «Cena {package}» + фильтр/сортировка по `prices.{package}.netPrice`; сид из Excel (колонки PAKIET START/COMFORT/PRESTIGE → по WorkPrice на пакет) + ABAC | 11, 03, 10 |
| 13 | FOR-04-13-project | сущность | Project (создаёт таблицу `projects`, project-scoped anchor) + ABAC | 01 |
| 14 | FOR-04-14-room | сущность | Room (размеры, авто-расчёт, `getProjectIdPath()="project.id"`) + ABAC | 13, 05 |
| 15 | FOR-04-15-menu-grouping | фронтенд | Группа «Справочники» + «Каталог» в NAV_CONFIG, i18n-ключи меню (PL/RU), привязка уже существующих страниц-справочников (реализованных в 02–14) к пунктам меню. Сами CRUD-страницы делаются в своих спеках (02–14), здесь — только навигация/группировка | 02–14 |
| 16 | FOR-04-16-material-dictionaries | справочники | Четыре справочника материалов ПАРАЛЛЕЛЬНО: MaterialType (`MATERIAL_TYPES`, Typ), MaterialProducer (`MATERIAL_PRODUCERS`, Producent), Material (`MATERIALS`, Materiał) — новые + сид из distinct значений CSV `Materiały pakiety - Lista.csv`; и ПЕРЕ-СИД существующего `MATERIAL_CATEGORIES` (Kategoria) — удалить construction/finishing, засидить 5 значений из CSV. Reuse `MATERIAL_CATEGORIES` ресурс/страницу (FOR-04-09) | 01, 09 |
| 17 | FOR-04-17-construction-materials | сущность | ConstructionMaterial (`MATERIALS_CONSTRUCTION`): **name (nameRU/namePL)**, packages (M2M OfferPackage), unit (FK MeasurementUnit), purchasePrice, retailGross, retailNet, retailPerUnit (computed), active + ABAC. Строительная ветка — управляется отдельно от отделочной; строит. CSV пока нет, сид минимальный/плейсхолдер | 10, 02 |
| 18 | FOR-04-18-finishing-materials | сущность | FinishingMaterial (`MATERIALS_FINISHING`): packages (M2M OfferPackage), category (FK MATERIAL_CATEGORIES), material (FK MATERIALS), type (FK MATERIAL_TYPES), producer (FK MATERIAL_PRODUCERS), model, sku, features, purchasePrice, retailGross, retailNet, retailPerUnitM2, link, photo, unit (FK MeasurementUnit), active + большой CSV-сид из `Materiały pakiety - Lista.csv` + ABAC | 09, 10, 16, 02 |
| 19 | FOR-04-19-work-catalog-material-consumption | сущность | Расширение WorkItem двумя коллекциями расхода материалов **по пакетам**: расход строительных материалов и расход отделочных материалов. Каждая запись: ссылка на материал, min/max цена за единицу материала, норма расхода на единицу работы. Производные на работу: цена работы/ед + Σ строит. материалов/ед + Σ отдел. материалов/ед. UI: клик по колонке пакета/материала показывает список входящих материалов; если в пакете несколько материалов одного типа — показывается цена «от/до». Сид норм — из артефакта research-спеки | 11, 12, 17, 18, RESEARCH |
| — | FOR-04-RESEARCH-material-norms | research | **✅ ГОТОВО.** Артефакт польских строительных норм (карты производителей / KNR из открытых тендеров / polskie normy): маппинг работа → материал (по типу-аналогу) → норма расхода на единицу работы, по пакетам, с обоснованием и ссылкой на норматив. Становится CSV-сидом для FOR-04-19. Результат в `FOR-04-RESEARCH-material-norms/`: `work-material-norms.csv` (615 норм по 205 из 227 работ; 2 ветки — 519 construction / 96 finishing), `construction-materials.csv` (195 материалов, типовой производитель+продавец+цены), `new-measurement-units.csv` (kg/l/m2/mb/szt), `research.md` (методология). Каждая норма несёт `justification_pl`/`justification_ru` (текст в таблицу FOR-04-19) + `source_type`/`source_doc`/`source_url`/`source_ref`. 22 работы без норм — намеренно (наценка/координация/логистика 13.01/13.03/13.04, демонтаж рамок 5.05–5.07, пустые строки 13.05–13.20) | 11 |

### Граф зависимостей

```
01-table-reference-filter ──> (дефолт для всех таблиц ниже)

02 units ─┐
03 currencies ─┼─> 11 work-catalog ──> 12 work-prices (pivot по пакетам, сид из Excel)
06 work-categories ─┘                         │ (10 offer-packages)
10 offer-packages ────────────────────────────┘

05 room-types ──┐
13 project ─────┴─> 14 room

02..14 ──> 15 menu-grouping

# Материалы (16–19) — строительная (17) и отделочная (18) ветки управляются раздельно
# 16 = ЧЕТЫРЕ справочника материалов параллельно (types/producers/materials — новые + пере-сид categories)
01 ──> 16 material-dictionaries ┬─> MATERIAL_TYPES (Typ, новый)
       (09 categories)         ├─> MATERIAL_PRODUCERS (Producent, новый)
                               ├─> MATERIALS (Materiał, новый)
                               └─> MATERIAL_CATEGORIES re-seed (Kategoria, пере-сид FOR-04-09)

10 offer-packages, 02 units ──> 17 construction-materials ──┐
                                                            │
16 material-dictionaries ───────> 18 finishing-materials ───┤
       (09 categories, 10 packages, 02 units)               │
                                                            │
11 work-catalog ──> RESEARCH material-norms ────────────────┤
                       (KNR / polskie normy → CSV)          │
                                                            ▼
11, 12, 17, 18, RESEARCH ──> 19 work-catalog-material-consumption
```

## Стратегия сидирования

- Справочники (02–10) и категории работ (06): каждая — отдельный Liquibase changeset(ы) в `foremen-backend/database_files/changesets/`, зарегистрированный последним в `changelog.xml`, идемпотентный (`preConditions onFail="MARK_RAN"` + `sqlCheck` на `code`/`rate`). Данные попадают на прод при миграции.
- ABAC-сид на каждый ресурс — в том же наборе миграций спеки этой сущности (по `entity-creation-rules.md`): вставка ресурса + грант ADMIN + гранты по матрице.
- Каталог работ (11) и цены (12): сид из `docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv`. Парсинг CSV → генерация changeset'ов: WorkCategory (уже засижены в 06), WorkItem, WorkPrice — **по одной строке на пару (работа, пакет)**: колонки `PAKIET START` / `COMFORT` / `PRESTIGE` → отдельные WorkPrice (netPrice из колонки пакета, currency=PLN), без `validFrom`/`validTo`.
- Справочники материалов (16) и материальные каталоги (17–18): FOR-04-16 засидивает `MATERIAL_TYPES`/`MATERIAL_PRODUCERS`/`MATERIALS` из distinct значений колонок `Typ`/`Producent`/`Materiał` файла `docs/Materiały pakiety - Lista.csv` И ПЕРЕ-сидивает `MATERIAL_CATEGORIES` (удалить construction/finishing → вставить 5 категорий из CSV: Drzwi, Listwy przypodłogowe, Podłoga, Płytki, Sanitariat) — все идемпотентно, changeset'ы 046+ (create+seed для трёх новых, re-seed для категорий). Эти справочники сидятся первыми, поскольку на них ссылаются FK отделочной ветки. Затем ConstructionMaterial (17) — строительного CSV пока нет, сид ограничивается минимальным набором-плейсхолдером (это отмечается явно). Затем FinishingMaterial (18) — большой CSV-сид из `Materiały pakiety - Lista.csv`, с маппингом колонок в поля согласно таблице сущности, где M2M-пакеты парсятся из колонки `Pakiet` (может содержать несколько пакетов через запятую, например «Budget, Standard»). ABAC-сид на каждый ресурс — в том же наборе миграций. **Текущий максимальный Liquibase changeset — 045, поэтому новые changeset'ы нумеруются с 046+.**
- Расход материалов на работы (19): сид норм расхода из артефакта research-спеки `FOR-04-RESEARCH-material-norms` (карты производителей / KNR из открытых тендеров, маппинг работа → материал → норма на единицу работы), оформленного как CSV. **✅ Research-артефакт готов** (`FOR-04-RESEARCH-material-norms/work-material-norms.csv` + `construction-materials.csv` + `new-measurement-units.csv`, генераторы `database_files/generators/research_*.py`, методология в `research.md`). При генерации сида FOR-04-19: (а) добавить `work_items.code` из позиционного Excel-`LP` (напр. `1.01`), на который ссылается `work_code` в CSV норм; (б) добавить недостающие единицы (`kg`/`l`) в `MEASUREMENT_UNITS`; (в) норму привязывать к `ConstructionMaterialType` (тип-аналог) — вилка (норма × retail_net) считается внутри типа по материалам пакета, с фолбэком на более бюджетный пакет; (г) перенести `justification_*` в строки таблицы. Строительный CSV из этого research также закрывает плейсхолдер-сид FOR-04-17 (`construction-materials.csv` — реальные строки с типовым производителем/продавцом/ценами).

## Нефункциональные требования

- Все текстовые предметные поля — `nameRU`/`namePL` (+ `descriptionRU`/`descriptionPL` при наличии), PL-фолбэк.
- Сиды идемпотентны и безопасны для повторного прогона на засиженной БД.
- Reference-фильтр (01) — обратно совместим: таблицы без reference-полей работают как раньше; поля-ссылки получают дропдаун автоматически по метаданным.
- Каждый новый контроллер проходит startup-валидацию аннотаций (FOR-03-08): `@PermissionResource` + `@PermissionOperation`/`@RequiresPermission` обязательны.
