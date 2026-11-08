# Requirements Document

## Introduction

This spec (**FOR-04-19-work-catalog-rework**) turns `/catalog/works` into the **single screen for
everything that belongs to a work item**. Today a work item is spread over three list screens and
several popups: the catalog list (`/catalog/works`) with a right-side edit sheet, the work-prices
list (`/catalog/prices`) and the material-consumption list (`/catalog/material-consumption`) with a
drill-in popup. The rework:

1. adds **two synthetic count columns** to the work list — how many consumption norms the work has
   for **construction** and for **finishing** materials — each **filterable** by "has norms / has
   none";
2. replaces the edit **sheet** with a **full edit page** that contains every work-related editor
   (base fields, labour price, volume formula, package overrides, room types) and, **at the
   bottom, an inline consumption table** with easy add/remove and searchable, scrollable dropdowns;
3. rebuilds the **formula field** (volume formula and per-package override formula): the field shows
   **localized measure chips instead of raw keys**, the measure list opens on click or on typing
   (no `@`/`#` needed) and hides when digits/operators/parentheses are typed, with full keyboard
   control;
4. shows **live validation of the final formula** — only numbers, `+ - * /`, parentheses and
   measure pointers are allowed;
5. retires the standalone `/catalog/prices` and `/catalog/material-consumption` screens.

It is a **full-stack** spec: the only backend work is a read-model addition on the work-item list
(two synthetic count fields + their filters). It adds **no entity, no table and no ABAC resource**.

### Findings that shape the scope (verified in code)

- **Consumption norms have no formula.** A norm is `normQty` + `consumptionBasis`
  (`PER_UNIT` | `PER_ROOM`) + optional `wastePct`. The only formulas in the work domain are the
  work **volume** formula (`WorkVolumeFormula`, one per work) and the **per-package override**
  formulas (`WorkPackageOverride.overrideSourceText`). "Improved formula engine" therefore applies
  to those two fields; both use the shared `FormulaInput` component.
- **`@` / `#` are UI-only.** The backend grammar rejects them; the field stores **bare tokens**
  (e.g. `floorArea * 2`). Showing localized chips in the field is a pure presentation change — the
  stored text and the API contract do **not** change.
- **The backend grammar is wider than the new rule** (it also accepts work-cell refs such as `X28`,
  `IFS`, `COUNTIF`, `PRESENT` and comparisons). A check of the dev DB found **no** volume formula
  (209) and **no** override formula (155) using any of them. The new restriction is applied in the
  **editor (client-side)**; the backend grammar is intentionally **left unchanged** (see Req 7.6).
- **Price is 1:1 with the work item** (`work_prices.work_item_id` unique), so the labour price
  becomes an ordinary field on the edit page.
- The list already attaches `packages` and `costCell` with **one grouped query per page** and
  filters `packages.id` with an `EXISTS` subquery (`WorkItemPackagesQueryResolver`). The new count
  fields reuse exactly this pattern.

### Terminology

- **Work item** — `WorkItemEntity` (`/api/work-items`, resource `WORK_CATALOG`).
- **Norm** — a `WorkMaterialConsumption` row (`/api/work-material-consumptions`, resource
  `WORK_MATERIAL_CONSUMPTION`), with a `branch` of `construction` or `finishing`.
- **Branch counts** — the synthetic read-only numbers `constructionConsumptionCount` and
  `finishingConsumptionCount` on the work-item DTO.
- **Measure** — a room-dimension variable usable in a formula (`floorArea`, `wallArea`,
  `perimeter`, … — the 14 tokens in `lib/formula-dimensions.ts`, mirroring the backend
  `FormulaAst.ROOM_DIMENSION_VARS`).
- **Measure chip** — an atomic, non-editable element in the formula field that displays a measure's
  **localized label** and serializes to its bare token.
- **Edit page** — the new full page replacing `WorkItemFormSheet`.

### Scope boundaries

- **In scope:** the two count columns/filters (backend read model + list UI); the edit page and its
  sections; the inline consumption table; the new formula field, picker and live validation;
  redirecting/retiring `/catalog/prices` and `/catalog/material-consumption`; updating the tests
  and QA-automation references to the retired screens; PL/RU i18n.
- **Out of scope:**
  - Any change to the backend formula grammar, parser, evaluator or error codes.
  - Adding formulas to consumption norms.
  - New ABAC resources or changes to the role matrices (`WORK_CATALOG`, `WORK_PRICES`,
    `WORK_MATERIAL_CONSUMPTION` stay as they are).
  - The main-menu slot — FOR-07-05 already points "Katalog prac" at `/catalog/works`.
  - The standalone dictionaries `/material-types`, `/construction-material-types`,
    `/measurement-units` (only *consumed* here through dropdowns).

## Requirements

### Requirement 1: One screen for all work-item data

**User Story:** As a catalog manager, I want to manage a work item's data in one place, so that I do
not hop between three screens.

#### Acceptance Criteria

1. THE `/catalog/works` list SHALL remain the entry point; its create button, row click and edit
   icon SHALL navigate to the Edit page instead of opening a sheet.
2. THE `/catalog/prices` and `/catalog/material-consumption` routes SHALL redirect (replace) to
   `/catalog/works`; their list pages, form sheets, delete dialogs, API hooks only used by them,
   and tests SHALL be removed. The `WORK_PRICES` and `WORK_MATERIAL_CONSUMPTION` resources and
   their endpoints SHALL remain (they are now consumed by the Edit page).
3. THE construction/finishing **cost-range cells** in the list SHALL navigate to the Edit page of
   that work scrolled to the consumption section (`/catalog/works/:id#consumption`) instead of
   opening the drill-in popup, which SHALL be removed.
4. THE list SHALL keep its current columns, sorting, filters, pagination and persisted table state.

### Requirement 2: Consumption count columns (construction / finishing)

**User Story:** As a catalog manager, I want to see at a glance which works still have no material
norms, per branch.

#### Acceptance Criteria

1. THE list SHALL show two new columns, **Construction materials (norms)** and **Finishing
   materials (norms)**, each displaying the **number of norms** of the work for that branch.
2. WHEN a work has no norm for a branch, THE column SHALL show `0` (never empty/dash), and a count
   of `0` SHALL be treated as "does not exist" for filtering.
3. EACH column SHALL have a filter with three states — **All** (no filter), **Has norms**, **Has
   none** — that restricts the server-side result and pagination totals accordingly.
4. THE counts SHALL be **synthetic fields** of the work-item DTO
   (`constructionConsumptionCount`, `finishingConsumptionCount`; non-negative integers): they SHALL
   NOT be stored, SHALL NOT be produced by joining the consumption entity into the list query, and
   SHALL be attached per page with **one grouped query** for the page's work ids (no per-row query;
   no N+1).
5. THE filter SHALL be an `EXISTS` subquery registered as a `CustomQueryResolver` for the synthetic
   segments `constructionConsumptionCount` / `finishingConsumptionCount` on `WorkItemEntity`
   (supported forms: `>0`, `==0`, `!=0`, `>=1`), so a filtered page still returns **distinct work
   rows** and correct `totalElements`. Any other operator or value SHALL be rejected with the
   standard `400 error.query.invalid.field.path` / value-coercion error.
6. THE two columns SHALL NOT be sortable (a sort would require a join or correlated count in the
   list query, which this spec forbids); the sort controls SHALL be hidden for them.
7. THE counts SHALL reflect create/delete of norms immediately: after a norm mutation on the Edit
   page, the list data SHALL be refreshed (query invalidation).
8. THE existing `costCell` columns (labour price, construction range, finishing range) SHALL remain
   unchanged.

### Requirement 3: Edit page (replaces the sheet)

**User Story:** As a catalog manager, I want a roomy edit page instead of a popup so that I can edit
the formula, packages and norms comfortably.

#### Acceptance Criteria

1. THE app SHALL provide `/catalog/works/new` (guard `WORK_CATALOG`/CREATE) and
   `/catalog/works/:id` (guard `WORK_CATALOG`/READ), registered in the router and in the
   route-permission map, with a header (back link to the list, work name, active badge, delete
   action when permitted).
2. THE page SHALL contain, top to bottom, these sections: **Basic data** (work category, unit,
   name RU, name PL, active), **Labour price**, **Volume formula**, **Package overrides**,
   **Room types**, and — **at the bottom** — **Material consumption** (anchor `#consumption`).
   Everything the former sheet and the former popups offered SHALL be reachable here.
3. THE **Basic data** and **Labour price** fields SHALL be saved together by one Save action:
   `POST/PUT /api/work-items`, then create/update `/api/work-prices` when the price changed (and
   delete it when cleared and permitted). Validation SHALL reuse the current schemas
   (`work-item-schema`, price range rules).
4. IN **create** mode THE page SHALL show Basic data and Labour price only; after a successful
   save it SHALL navigate (replace) to `/catalog/works/:newId` where the remaining sections are
   available (as the former sheet only offered them in edit mode). The remaining sections SHALL
   show a short hint in create mode explaining this.
5. THE **Volume formula**, **Package overrides** and **Room types** sections SHALL keep their
   current independent save behavior and endpoints (`/api/work-volume-formulas`,
   `/api/work-package-overrides`, `/api/work-items/{id}/room-types`).
6. THE price section SHALL be visible with `WORK_PRICES`/READ and editable with CREATE/UPDATE; the
   consumption section SHALL be visible with `WORK_MATERIAL_CONSUMPTION`/READ and editable per
   Req 4.7; a user holding only `WORK_CATALOG`/READ SHALL get a fully read-only page (inputs
   disabled, no save/add/delete controls).
7. WHEN the user leaves the page with unsaved changes in any section, THE app SHALL ask for
   confirmation (in-app prompt; no browser dialog for router navigations).
8. THE page SHALL be responsive (single column on mobile) and keyboard accessible (logical tab
   order, visible focus, labelled inputs, section headings as landmarks).

### Requirement 4: Inline consumption table

**User Story:** As a catalog manager, I want to add and remove material norms in a table right on
the work page, picking related entities from searchable dropdowns.

#### Acceptance Criteria

1. THE consumption section SHALL list all norms of the work (`GET
   /api/work-material-consumptions?query=workItem.id==<id>`, large page size), **grouped by branch**
   (construction, finishing) with a count per group and a branch filter; an empty state SHALL show
   a localized message and the add control.
2. EACH row SHALL be **editable in place** (no modal, no sheet): *Material type*, *Unit*, *Norm
   qty*, *Basis*, *Waste %* are inline cells; the row's secondary fields (*source type*, *source
   document*, *source URL*, *source reference*, *justification RU/PL*) are in an **expandable
   details row** within the same table.
3. **Add:** an "Add norm" control (per branch group and a general one) SHALL insert an empty
   editable row at the top of the group with focus on *Material type*; the branch is fixed by the
   group (or chosen by a segmented control for the general control).
4. **Dropdowns:** *Material type* and *Unit* SHALL use the searchable, server-paged,
   infinite-scroll `AsyncEntitySelect`:
   - construction branch → `/api/construction-material-types`;
   - finishing branch → `/api/material-types`;
   - unit → `/api/measurement-units`.
   Typing SHALL search; scrolling SHALL load further pages; the selected label SHALL show
   immediately in edit rows; the lists SHALL be keyboard operable.
5. **Commit / cancel:** Enter in any cell or the row's confirm button SHALL save the row
   (`POST`/`PUT /api/work-material-consumptions`); Escape or the cancel button SHALL discard the
   row's edits (and remove a never-saved new row). A row with validation errors SHALL stay open,
   mark the invalid cells and show the localized message (server errors via the existing
   `workMaterialConsumption.*` / error-key mapping).
6. **Remove:** each row SHALL have a delete icon that turns into an **inline confirm/cancel pair**
   (no modal) and then calls `DELETE /api/work-material-consumptions/{id}`.
7. Permissions: rows are editable with `WORK_MATERIAL_CONSUMPTION`/UPDATE, "Add" needs CREATE,
   delete needs DELETE; READ-only users see a plain table.
8. **Required backend fields:** `sourceType`, `sourceDoc` and `sourceRef` are mandatory in the API.
   For a **new** row the page SHALL pre-fill them with defaults (`sourceType = "manual"`,
   `sourceDoc` = a localized "Entered in the work catalog" label, `sourceRef` = today's ISO date)
   so a norm can be added with only type, unit and quantity; the user can change them in the details
   row. No API validation is relaxed.
9. Validation (client, mirroring the backend): material type and unit required; `normQty` between 0
   and 99999999.9999; `wastePct` empty or 0–100; branch/type consistent. The existing
   `workMaterialConsumption.validation.*` keys SHALL be reused.
10. Each saved row SHALL show the type's **price range** (`typeBatchRange`) read-only, as the old
    drill-in did; after any norm mutation the section, the work list (counts + cost cells) SHALL
    be refreshed via query invalidation.
11. Create/update/delete of norms SHALL keep being audited by the existing service (no change).

### Requirement 5: Formula field — localized chips and measure picker

**User Story:** As a catalog manager, I want to see readable measure names in the formula and pick
them from a list as I type.

#### Acceptance Criteria

1. THE formula field (volume formula and each package-override formula) SHALL render every measure
   token as a **measure chip** showing the **localized label** (PL/RU by current language, with the
   unit symbol as tooltip); digits, operators and parentheses render as plain text. The field
   SHALL NOT show raw i18n keys or bare identifiers for known measures.
2. THE stored/sent value SHALL remain the **bare-token text** (e.g. `floorArea * 2 + perimeter`);
   loading an existing formula SHALL display it as chips; switching the UI language SHALL re-label
   the chips without changing the value.
3. THE **measure list** SHALL open (a) when the field is **clicked/focused**, and (b) when the user
   **starts typing letters** — without any `@`/`#` — filtering by the typed word against the
   localized label and the token. The legacy `@`/`#` trigger MAY still open the list.
4. THE list SHALL be **hidden** when the user types a **digit, an arithmetic operator
   (`+ - * /`), a parenthesis, a decimal point or whitespace**, and it SHALL stay hidden until the
   next letter is typed, the field is clicked again, or `ArrowDown`/`Ctrl+Space` is pressed. `Esc`
   SHALL close it.
5. WHILE the list is open: `ArrowDown`/`ArrowUp` SHALL move the active item and **scroll it into
   view** in the (scrollable) list; `Enter` or `Tab` SHALL **insert the active measure as a chip**
   (replacing the typed partial word) and close the list; mouse click SHALL do the same. WHEN the
   list is closed, `Enter` SHALL NOT insert anything (single-line fields ignore it; the multiline
   volume field inserts a newline as today) and `Tab` SHALL move focus normally.
6. WHEN the filter has no match, THE list SHALL show a localized "no matching measure" row (and the
   typed word stays as plain text, flagged by validation per Req 6).
7. A chip SHALL behave atomically: `Backspace`/`Delete` next to a chip removes the whole chip;
   the caret cannot enter a chip; copy/paste of text SHALL serialize chips to bare tokens and a
   pasted bare token that is a known measure SHALL become a chip.
8. THE component SHALL expose the same props contract as today's `FormulaInput`
   (`value`, `onChange(text)`, `disabled`, `placeholder`, `multiline`, `ariaLabel`, `id`) so the
   volume editor and the package-override rows adopt it without API change; it SHALL be accessible
   (`role="combobox"` with `aria-expanded`/`aria-activedescendant`, listbox/option roles, announced
   chips).

### Requirement 6: Live validation of the final formula

**User Story:** As a catalog manager, I want to know immediately whether my formula is valid.

#### Acceptance Criteria

1. WHILE editing, THE field SHALL validate the formula on every change (debounced ≤ 150 ms) with a
   pure client function and show the result under the field: a localized **"Formula is valid"**
   state, or the **list of problems** with the offending token highlighted.
2. THE allowed content SHALL be exactly: non-negative decimal numbers (`.` separator), the
   operators `+ - * /`, parentheses, whitespace, and the known measure tokens. Everything else is a
   problem.
3. THE validator SHALL report (each with an i18n message key): empty formula; unknown word/token;
   unsupported character; unbalanced parentheses; empty parentheses `()`; two operands in a row
   without an operator (e.g. `2 floorArea`, `floorArea perimeter` — implicit multiplication is not
   allowed); two operators in a row; a binary operator (`+ * /`) at the start or after `(`; any
   operator at the end or before `)`; division by a literal zero (`/ 0`). A **unary `-`** is allowed
   **only at the start of the formula or right after `(`** (the backend `FormulaParser.parseFactor`
   accepts it); `2 - -3` is reported as two operators in a row (stricter than the backend, by
   design).
4. A formula with at least one problem SHALL disable the section's Save action (and the override
   row's commit) and mark the field `aria-invalid`; an **empty** volume formula keeps its current
   meaning (clearing deletes the formula) and is not an error there.
5. THE server SHALL remain the authority: a server error on save (`error.formula.*`) SHALL still be
   shown through the existing `formula-error` mapping, under the field.
6. THE backend grammar (cell refs, `IFS`/`COUNTIF`/`PRESENT`, comparisons) SHALL NOT be changed. If
   an existing stored formula contains such constructs, the editor SHALL show it as invalid with the
   unsupported token highlighted and SHALL NOT modify or delete it unless the user edits it.
7. THE measure vocabulary SHALL come from a single source (`FORMULA_DIMENSIONS`) shared by the
   picker, the chips and the validator, and a test SHALL pin it to the backend
   `ROOM_DIMENSION_VARS` list (fixture-based parity).

### Requirement 7: Backend read model (counts)

**User Story:** As a frontend developer, I want the counts delivered by the list endpoint without
extra calls.

#### Acceptance Criteria

1. `WorkItemDtoModel` SHALL gain `constructionConsumptionCount` and `finishingConsumptionCount`
   (`long`, default 0); `WorkItemService.find(...)` SHALL attach them after the page is mapped with
   a single grouped query (`SELECT workItem.id, branch, COUNT(*) … WHERE workItem.id IN :ids
   GROUP BY workItem.id, branch`) added to `WorkMaterialConsumptionDao`.
2. THE single-item `GET /api/work-items/{id}` SHALL return the same fields.
3. THE metadata endpoint (`/api/work-items/metadata`) SHALL describe the two fields as non-sortable
   numeric synthetic fields so generic clients do not offer sorting.
4. THE `EXISTS` filter resolver SHALL be registered at startup for both segments (like
   `WorkItemPackagesQueryResolver`) and SHALL not alter sorting, paging or the DISTINCT row set.
5. A Liquibase changeset SHALL add the index `ix_wmc_work_item_branch` on
   `work_material_consumptions (work_item_id, branch)` (the table currently has only its primary
   key — verified on the dev DB — so the grouped count and the `EXISTS` filter would otherwise scan
   the table). The changeset is idempotent (`onFail="MARK_RAN"` guarded by an `indexExists`
   precondition) and uses the next free changeset number at implementation time, registered last in
   `changelog.xml`. No other schema change.
6. No endpoint is added for writes: norms continue to be written through
   `/api/work-material-consumptions`.

### Requirement 8: Internationalization

**User Story:** As a PL or RU user, I want every new text in my language.

#### Acceptance Criteria

1. NO backend entity field needs i18n variants (no new entity or column). The existing `nameRU` /
   `namePL` of work items and `justificationRU` / `justificationPL` of norms are edited on the page
   as two inputs each, exactly as today.
2. THE spec SHALL add these keys to **both** `pl.json` and `ru.json` (identical key sets; the
   locale-parity tests stay green):

   | Key | PL | RU |
   |-----|----|----|
   | `workCatalog.table.constructionConsumption` | Materiały budowlane (normy) | Стройматериалы (нормы) |
   | `workCatalog.table.finishingConsumption` | Materiały wykończeniowe (normy) | Отделочные материалы (нормы) |
   | `workCatalog.table.consumptionFilter.all` | Wszystkie | Все |
   | `workCatalog.table.consumptionFilter.has` | Z normami | С нормами |
   | `workCatalog.table.consumptionFilter.none` | Bez norm | Без норм |
   | `workCatalog.page.createTitle` | Nowa pozycja prac | Новая позиция работ |
   | `workCatalog.page.editTitle` | Edycja pozycji prac | Редактирование позиции работ |
   | `workCatalog.page.back` | Wróć do katalogu | Назад к каталогу |
   | `workCatalog.page.save` | Zapisz | Сохранить |
   | `workCatalog.page.createdHint` | Pozostałe sekcje będą dostępne po zapisaniu pozycji. | Остальные разделы станут доступны после сохранения позиции. |
   | `workCatalog.page.unsavedTitle` | Niezapisane zmiany | Несохранённые изменения |
   | `workCatalog.page.unsavedMessage` | Masz niezapisane zmiany. Czy na pewno chcesz wyjść? | Есть несохранённые изменения. Выйти без сохранения? |
   | `workCatalog.page.stay` | Zostań | Остаться |
   | `workCatalog.page.leave` | Wyjdź | Выйти |
   | `workCatalog.page.section.basic` | Dane podstawowe | Основные данные |
   | `workCatalog.page.section.labourPrice` | Cena robocizny | Цена работы |
   | `workCatalog.page.section.volumeFormula` | Formuła ilości | Формула объёма |
   | `workCatalog.page.section.packages` | Pakiety | Пакеты |
   | `workCatalog.page.section.roomTypes` | Typy pomieszczeń | Типы помещений |
   | `workCatalog.page.section.consumption` | Zużycie materiałów | Расход материалов |
   | `workCatalog.consumption.add` | Dodaj normę | Добавить норму |
   | `workCatalog.consumption.empty` | Brak norm zużycia dla tej pozycji | Для этой позиции нет норм расхода |
   | `workCatalog.consumption.group.construction` | Materiały budowlane | Стройматериалы |
   | `workCatalog.consumption.group.finishing` | Materiały wykończeniowe | Отделочные материалы |
   | `workCatalog.consumption.col.materialType` | Typ materiału | Тип материала |
   | `workCatalog.consumption.col.unit` | Jednostka | Единица |
   | `workCatalog.consumption.col.normQty` | Norma | Норма |
   | `workCatalog.consumption.col.basis` | Podstawa | Основа |
   | `workCatalog.consumption.col.waste` | Straty % | Потери % |
   | `workCatalog.consumption.col.priceRange` | Cena (zakres) | Цена (вилка) |
   | `workCatalog.consumption.details` | Szczegóły i źródło | Детали и источник |
   | `workCatalog.consumption.confirmDelete` | Usunąć? | Удалить? |
   | `workCatalog.consumption.confirm` | Potwierdź | Подтвердить |
   | `workCatalog.consumption.cancel` | Anuluj | Отмена |
   | `workCatalog.consumption.defaultSourceDoc` | Wprowadzono w katalogu prac | Введено в каталоге работ |
   | `workCatalog.consumption.select.placeholder` | Wybierz… | Выберите… |
   | `workCatalog.formula.picker.noMatch` | Brak pasującej miary | Нет подходящей меры |
   | `workCatalog.formula.picker.label` | Lista miar | Список мер |
   | `workCatalog.formula.validation.valid` | Formuła jest poprawna | Формула корректна |
   | `workCatalog.formula.validation.empty` | Formuła jest pusta | Формула пуста |
   | `workCatalog.formula.validation.unknownToken` | Nieznany element: {{token}} | Неизвестный элемент: {{token}} |
   | `workCatalog.formula.validation.illegalChar` | Niedozwolony znak: {{char}} | Недопустимый символ: {{char}} |
   | `workCatalog.formula.validation.unbalanced` | Niezgodne nawiasy | Несогласованные скобки |
   | `workCatalog.formula.validation.emptyParens` | Puste nawiasy | Пустые скобки |
   | `workCatalog.formula.validation.missingOperator` | Brak operatora między wartościami | Нет оператора между значениями |
   | `workCatalog.formula.validation.doubleOperator` | Dwa operatory pod rząd | Два оператора подряд |
   | `workCatalog.formula.validation.leadingOperator` | Formuła nie może zaczynać się od operatora | Формула не может начинаться с оператора |
   | `workCatalog.formula.validation.trailingOperator` | Formuła nie może kończyć się operatorem | Формула не может заканчиваться оператором |
   | `workCatalog.formula.validation.divisionByZero` | Dzielenie przez zero | Деление на ноль |

   Existing keys (`workCatalog.table.*`, `workMaterialConsumption.*`, `formulaErrors.*`,
   `workCatalog.formula.*` dimension labels) SHALL be reused where they fit; keys used only by the
   retired screens SHALL be removed after a repository-wide grep confirms no other usage.

### Requirement 9: Notifications

**User Story:** As a product owner, I want an explicit notification statement.

#### Acceptance Criteria

1. THIS spec SHALL emit **no** notifications and add no notification types: it reorganizes an admin
   catalog screen and adds a read-model field; it introduces no recipient-facing domain event. It
   does not use the generic notification service.

### Requirement 10: Regression safety and test updates

**User Story:** As a developer, I want the retirement of the old screens to be safe.

#### Acceptance Criteria

1. THE tests that reference the retired routes/components SHALL be updated or removed:
   `nav-consistency.for0705.test.ts` ("routes kept for the pending FOR-04-19 rework" becomes
   "redirects to /catalog/works"), `route-permissions*.test.ts`,
   `PermissionGuard.for0504.test.tsx`, `navigation.test.ts` (R1.6 stays valid), the work-prices and
   work-material-consumption feature tests, and the QA-automation artifacts in `foremen-qa-auto`
   (`menu_grouping.feature`, `DictionarySteps`, `WorkPricesPage`).
2. THE assistant page-context derivation SHALL map `/catalog/works/...` sub-routes to the same
   `catalog` page type.
3. Backend: tests SHALL cover the count attach (0 and >0, both branches, page of mixed works), the
   `EXISTS` filter forms (`>0`, `==0`, invalid operator), the unchanged DISTINCT paging totals and
   an **N+1 guard** (the number of SQL statements for a page does not grow with page size).
4. Frontend: tests SHALL cover the columns/filter, the Edit page sections and permissions, the
   inline consumption table flows (add, edit, cancel, delete confirm, dropdown search/scroll), the
   formula editor interactions (picker open/close rules, keyboard, chips, paste) and the validator
   (property-style table of valid/invalid inputs).
5. `test-cases.md` SHALL be authored per `.kiro/steering/test-cases.md` (browser scenarios,
   API checks for the count/filter, regression group, MD report template).
