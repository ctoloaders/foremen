# Design Document: FOR-04-19 — Work catalog rework

## Overview

Four coordinated changes, one per concern:

1. **Read model** — two synthetic count fields on the work-item DTO + two `EXISTS` filters, no new
   joins (backend, small).
2. **List** — two columns with a three-state filter; navigation to the edit page (frontend).
3. **Edit page** — a routed page replacing the sheet, hosting the existing editors plus a new
   inline consumption table (frontend).
4. **Formula field** — a token-aware editor (chips + picker) with a pure validator (frontend).

Then the retired screens (`/catalog/prices`, `/catalog/material-consumption`) are redirected and
their code removed.

Repositories (steering `git-repo-structure.md`): frontend code is committed from inside
`foremen-frontend/`; backend, changeset, tests and specs from the root repo.

## 1. Backend read model

### 1.1 DTO and service

`WorkItemDtoModel` (controller/model) gains:

```java
long constructionConsumptionCount,
long finishingConsumptionCount
```

(`WorkItemServiceModel` carries the same two fields, defaulting to 0, mapped by
`WorkItemControllerMapper`). `WorkItemService.find(...)` already post-processes the page with
`attachPackages(rows)`; add `attachConsumptionCounts(rows)` next to it:

```java
Map<Long, long[]> counts = new HashMap<>();           // id -> [construction, finishing]
for (ConsumptionCountRow r : consumptionDao.countByWorkItemIdsGroupedByBranch(ids)) { … }
rows.forEach(row -> { long[] c = counts.getOrDefault(row.getId(), ZERO); row.setConstructionConsumptionCount(c[0]); … });
```

`WorkMaterialConsumptionDao` gets one projection query:

```java
@Query("SELECT c.workItem.id AS workItemId, c.branch AS branch, COUNT(c) AS cnt " +
       "FROM WorkMaterialConsumptionEntity c WHERE c.workItem.id IN :ids " +
       "GROUP BY c.workItem.id, c.branch")
List<ConsumptionCountRow> countByWorkItemIdsGroupedByBranch(@Param("ids") Collection<Long> ids);
```

One statement per page regardless of page size — the same shape as
`WorkPackageOverrideDao.findMembershipRefsByWorkItemIdIn` and the `costCell` batch. The single-item
read (`findById`) goes through the same `attach…` helper for one id. The consumption **entity is
never joined** into the list query, so the page query keeps selecting distinct `work_items` rows.

Why a computed number and not a stored counter (considered): a denormalized counter on `work_items`
would be sortable/filterable with a plain column, but it must be maintained on every write path
(UI, seed/CSV loads, direct SQL) and can drift. The batch + `EXISTS` approach has no drift and
costs one indexed grouped query per page. Sorting by count is the one thing given up (Req 2.6).

### 1.2 Filter resolver

`WorkItemConsumptionCountQueryResolver implements CustomQueryResolver<WorkItemEntity>`, modelled on
`WorkItemPackagesQueryResolver`, registered via `CustomQueryResolverRegistry` for **two** segments.
Because `property()` returns one segment, implement it as one small abstract base +
two `@Component` subclasses (or one component registering twice if the registry allows — verify in
task 1.2; otherwise two classes):

| Segment | Branch |
|---------|--------|
| `constructionConsumptionCount` | `ConsumptionBranch.construction` |
| `finishingConsumptionCount` | `ConsumptionBranch.finishing` |

`toFilter(QueryToken.Filter f)`:
- Accept only field == the segment and value an integer; map the operator to a boolean
  **has / has-none**: `>0`, `>=1`, `!=0` → has; `==0`, `<1`, `<=0` → none; anything else →
  `ForemenApiException(400, "error.query.invalid.field.path", field)` (operator) or the standard
  value-coercion error (non-integer).
- Predicate: `EXISTS (SELECT 1 FROM work_material_consumptions c WHERE c.work_item_id = wi.id AND
  c.branch = :branch)`, negated for "none". `cb.exists(subquery)` — no join, so paging totals and
  DISTINCT rows are preserved.

The frontend sends `constructionConsumptionCount>0` / `constructionConsumptionCount==0`.

### 1.3 Metadata

`WorkItemController.metadata()` already adds the synthetic `costCell` field. Add the two count
fields as `number`, `sortable=false`, `filterable=true`, `synthetic=true` so generic clients do not
offer sort. (The work-catalog frontend declares its columns statically; this only keeps the
metadata endpoint truthful.)

### 1.4 Index

New idempotent changeset `NNN-index-wmc-work-item-branch.xml` (next free number when implemented;
registered last in `changelog.xml`):

```xml
<changeSet id="NNN-index-wmc-work-item-branch" author="foremen">
  <preConditions onFail="MARK_RAN"><not><indexExists tableName="work_material_consumptions" indexName="ix_wmc_work_item_branch"/></not></preConditions>
  <createIndex tableName="work_material_consumptions" indexName="ix_wmc_work_item_branch">
    <column name="work_item_id"/><column name="branch"/>
  </createIndex>
</changeSet>
```

No ABAC changes (no new resource/controller; steering `entity-creation-rules.md` does not apply
beyond confirming this).

## 2. List (`WorkCatalogList`)

- Types: `WorkItemDto` gains the two count fields.
- Columns `constructionConsumptionCount` / `finishingConsumptionCount` (`dataType: 'number'`,
  `sortable: false`, `filterable: true`), render `value ?? 0`.
- **Filter control:** a three-state select (All / Has norms / Has none). If `DataTable` has no
  suitable filter type, add a small `enum`-style filter variant to the column config
  (`filterOptions: [{value:'has', labelKey}, {value:'none', labelKey}]` → emits
  `field>0` / `field==0`, nothing for "all"). Task 2.1 first checks the existing `boolean`/`enum`
  filter support and extends the DataTable minimally if needed; it must keep the persisted-filter
  format backward compatible.
- Cost-range cells and row click → `navigate('/catalog/works/' + id + '#consumption')` for the
  range cells, `/catalog/works/:id` for row/edit; create button → `/catalog/works/new`.
  The `WorkMaterialConsumptionDrillIn`, `WorkItemFormSheet` usage and page-level sheet state are
  removed from `WorkCatalogPage`.

## 3. Edit page

### 3.1 Routes and guards

```
/catalog/works/new   → WorkItemEditPage (mode=create)   guard WORK_CATALOG/CREATE
/catalog/works/:id   → WorkItemEditPage (mode=edit)     guard WORK_CATALOG/READ
```

Registered in `router.tsx` (lazy) and `route-permissions.ts` (`/catalog/works/new` literal entry
declared **before** the `:id` template so the matcher returns the CREATE requirement for `new`;
the equal-segment matcher is first-match-wins, as noted in the existing file comments).
`/catalog/prices` and `/catalog/material-consumption` become
`<Navigate to="/catalog/works" replace />` routes without a guard entry.

### 3.2 Component structure

```
features/work-catalog/
  pages/WorkItemEditPage.tsx            – header, anchors, section stack, unsaved-changes guard
  components/edit/
    BasicDataSection.tsx                – category, unit, nameRU/PL, active  (+ labour price field)
    LabourPriceField.tsx                – price input, currency select (work-prices API)
    ConsumptionSection.tsx              – branch groups, add controls
    ConsumptionRow.tsx                  – inline-editable row + expandable details row
    ConsumptionRowEditor (hook)         – row draft state, validation, commit/cancel
  components/WorkVolumeFormulaEditor.tsx        (existing, now uses the new FormulaInput)
  components/WorkPackageOverridesEditor.tsx     (existing)
  components/WorkRoomTypeAttachmentEditor.tsx   (existing)
```

`WorkItemFormSheet.tsx`, `DeleteWorkItemDialog` usage on the page header stays (delete is a
confirmation by design and is not an edit window; it reuses the existing dialog).
Data: `useWorkItem(id)` (existing) + `useWorkPrice(workItemId)`
(`GET /api/work-prices?query=workItem.id==<id>`, moved from `features/work-prices`) +
`useWorkMaterialConsumptions(workItemId)` (moved from
`features/work-material-consumption`, key factory `workMaterialConsumptionKeys` kept).

The shared pieces of the retired features that the page still needs are **moved into
`features/work-catalog/`** (api functions, query/mutation hooks, schema, types); the rest of
`features/work-prices/` and `features/work-material-consumption/` (lists, sheets, drill-in, their
tests) is deleted (task 5.1). Cross-feature imports from other features are checked by grep first
(e.g. cost-range types used by the estimate/BoM screens) and re-exported from the new location when
needed.

### 3.3 Save model

| Section | Save |
|---------|------|
| Basic data + Labour price | One **Save**: work item POST/PUT, then price POST/PUT/DELETE as needed (sequential; price failure keeps the work saved and shows a section error). |
| Volume formula / Package overrides / Room types | Existing per-section buttons and endpoints. |
| Consumption | Per row (commit/cancel), see §4. |

Dirty tracking: react-hook-form `formState.isDirty` for the base form, a `dirty` flag exposed by
each editor, and open ConsumptionRow drafts. A `useBlocker` (React Router data router) prompts on
in-app navigation; a `beforeunload` listener covers tab close.

Create flow: `POST /api/work-items` → on success `navigate('/catalog/works/' + id, { replace:true })`
(price is created in the same Save when entered, after the work exists).

### 3.4 Permissions

`usePermission().hasPermission` per section as in Req 3.6; inputs get `disabled`/`readOnly`, action
buttons are not rendered. The page itself needs only `WORK_CATALOG`/READ.

## 4. Inline consumption table

### 4.1 Layout

A semantic table per branch group (`<table>` with `<caption>`/group heading), columns:
Material type · Unit · Norm qty · Basis · Waste % · Price range (read-only) · actions. A chevron
button toggles the **details row** (`colSpan`) with source type/doc/url/ref and justification RU/PL.
Group header: branch label + count + **Add norm** button. A page-level branch filter (All /
Construction / Finishing) and an empty state.

### 4.2 Row state machine

`view` → (click cell / edit icon) → `editing` → (Enter / confirm) → `saving` → `view`
`editing` → (Esc / cancel) → `view` (draft discarded); `new` (unsaved) → cancel → removed.
`view` → (delete icon) → `confirmDelete` (inline ✓/✕ replace the icon) → `deleting` → removed.
Only one row is in `editing/new` at a time **per group**; starting another edit with a dirty draft
keeps the draft (no silent loss) by committing-or-cancelling prompt-free rule: the first row stays
open and focus moves to it with a hint (simple, predictable).

### 4.3 Dropdowns

`AsyncEntitySelect` (existing, searchable, server-paged, infinite-scroll sentinel, `#<id>` label
fallback + `selectedLabel` prop) for:

| Cell | `targetResource` / endpoint | Notes |
|------|----------------------------|-------|
| Material type (construction) | `/api/construction-material-types` | `labelI18n: true` |
| Material type (finishing) | `/api/material-types` | `labelI18n: true` |
| Unit | `/api/measurement-units` | `labelI18n: true` |

The material-type resource switches with the row's branch; changing branch on an unsaved new row
clears the type. `selectedLabel` is taken from the row DTO (`materialType.name`, unit name) so edit
rows show names immediately.

### 4.4 Request mapping

Create/update use the existing request records (`workItemId`, `materialUnitId`, `branch`,
`consumptionBasis`, exactly one of `constructionMaterialTypeId` / `finishingMaterialTypeId`,
`normQty`, `wastePct`, `justificationRU/PL`, `sourceType`, `sourceDoc`, `sourceUrl`, `sourceRef`).
Defaults for **new** rows (Req 4.8): `consumptionBasis = PER_UNIT`, `sourceType = "manual"`,
`sourceDoc = t('workCatalog.consumption.defaultSourceDoc')`, `sourceRef = new Date().toISOString().slice(0,10)`.
The zod schema is the existing `work-material-consumption-schema` moved over.

### 4.5 Cache invalidation

All three mutations invalidate `workMaterialConsumptionKeys.lists()` (the section) and
`workItemKeys.lists()` / `workItemKeys.detail(id)` (counts + cost cells in the list and the page).

## 5. Formula field

### 5.1 Editor model

`FormulaInput` keeps its props. Internally it becomes a **token editor**:

- Value ⇄ segments: `parseSegments(text)` splits the bare text into
  `{type:'measure', token} | {type:'text', value}` (regex on the known token list, longest-match,
  word boundaries); `serialize(segments)` is the inverse. `renderFormulaSegments` (existing) is
  reused/extended for labels.
- Rendering: a `contentEditable` container (`role="combobox"`, `aria-multiline` for the volume
  field) whose children are text nodes and `<span contenteditable="false" data-token="floorArea"
  class="chip">Powierzchnia podłogi</span>` chips. Single-line mode disables line breaks.
- Input handling via `beforeinput`/`input` events on the container: after each edit the DOM is
  re-read into segments, the value is serialized and `onChange(text)` is called; the DOM is
  re-rendered from `value` (controlled) while preserving the caret (saved as `{segmentIndex,
  offset}`).
- Chip atomicity: `Backspace`/`Delete` at a chip boundary removes the chip; arrow keys skip over
  chips; the caret cannot be placed inside (chips are `contenteditable=false`).
- Paste: `onPaste` takes `text/plain`, runs `parseSegments`, inserts at the caret.
- Language change: chips get their label from `t(labelKey)` at render, so a language switch
  re-renders without touching the value.

Why not an overlay textarea: chip labels are wider/narrower than the tokens, so a transparent
textarea over a rendered layer cannot keep caret geometry in sync. A small contentEditable model is
the standard approach and keeps the stored text untouched. A fallback `ariaLabel`/hidden input keeps
form semantics and the existing tests' `getByLabelText` usage.

### 5.2 Picker rules (state machine)

State: `open: boolean`, `query: string`, `active: number`.
Let `word` = the run of letters/digits-free letters immediately left of the caret
(`/[A-Za-z]+$/` over the text before the caret, ignoring chips).

| Event | Result |
|-------|--------|
| focus / click in field | `open = true`, `query = word` |
| user types a **letter** | `open = true`, `query = word`, `active = 0` |
| user types **digit, `+ - * /`, `( )`, `.`, space** | `open = false` |
| `@` or `#` typed (legacy) | `open = true`, trigger char removed on insert |
| `ArrowDown` / `Ctrl+Space` while closed | `open = true` |
| `Esc` / blur (120 ms delay for click) | `open = false` |
| `ArrowDown` / `ArrowUp` while open | `active` moves (wrap), `listbox.children[active].scrollIntoView({block:'nearest'})` |
| `Enter` / `Tab` while open and ≥1 match | insert active measure chip (replace `word`), close; `preventDefault` |
| `Enter` / `Tab` while closed or no match | default behavior |
| click an option | insert chip, close |

Matches: `FORMULA_DIMENSIONS.filter(d => token/label includes query, case-insensitive)`; when
`query` is empty all measures show. The listbox is `role="listbox"` with `id`, options
`role="option"`, `aria-selected`; the editor has `aria-expanded`, `aria-controls`,
`aria-activedescendant`. The list has `max-height` with `overflow-y:auto`. Positioned below the
caret's line box (absolute inside the `relative` wrapper), flipped above near the viewport bottom.

### 5.3 Validator (`lib/formula-validator.ts`)

Pure function `validateFormula(text): { valid: boolean; problems: Problem[] }` where
`Problem = { code, token?, char?, start, end }`. Tokenize left to right:

```
number  : [0-9]+(\.[0-9]+)?
measure : one of FORMULA_DIMENSION_TOKENS (longest match, whole word)
op      : + - * /
paren   : ( )
space   : skipped
word    : other [A-Za-z][A-Za-z0-9]*   → unknownToken
other   : any other char               → illegalChar
```

Grammar check with a tiny state machine on the token stream (`expectOperand` ⇄ `expectOperator`):

- expectOperand: accepts number, measure, `(`, or unary `-` (only at start / after `(`); an operator
  here → `leadingOperator` (start/after `(`) or `doubleOperator`; `)` here → `emptyParens` if
  previous was `(`, else `trailingOperator`.
- expectOperator: accepts `+ - * /`, `)`; a number/measure/`(` here → `missingOperator`.
- Parenthesis depth counter: negative → `unbalanced`; non-zero at end → `unbalanced`.
- End while expectOperand with tokens present → `trailingOperator`.
- Operator `/` followed by a number literal equal to 0 → `divisionByZero`.
- Empty/whitespace-only → `empty` (the volume section treats it as "clear formula", the override row
  as "no override").

Problems carry source offsets so the editor can underline the offending segment (CSS
`text-decoration: wavy underline` on the chip/text run). Messages are i18n keys with params
(Req 8.2). The vocabulary comes from `FORMULA_DIMENSIONS` only; a test compares it with a JSON
fixture generated from the backend `ROOM_DIMENSION_VARS` (listed in the test, updated with it).

### 5.4 Integration

`WorkVolumeFormulaEditor`: validate on change (debounced 150 ms), render the status line
(`role="status"`, green check / red list), disable Save while `problems.length>0 && text.trim()!==''`,
keep the server-error mapping. `WorkPackageOverridesEditor`: per-row validation; the row's commit is
blocked while invalid; the row shows a compact error under the field.

Unsupported-but-stored constructs (cell refs/`IFS`…) load as plain red text with `unknownToken`
and are not rewritten (Req 6.6).

## 6. Retired screens

- Routes: `/catalog/prices`, `/catalog/material-consumption` → `Navigate` to `/catalog/works`.
- Delete: `features/work-prices/**` (except what moves), `features/work-material-consumption/**`
  (except what moves), their locale keys (after grep), route-permissions entries
  (`/catalog/prices`, `/catalog/material-consumption`) — the redirect routes need no guard.
- Update: `nav-consistency.for0705.test.ts`, `route-permissions*.test.ts`,
  `PermissionGuard.for0504.test.tsx`, assistant `page-context` (+ test), QA-auto files
  (`menu_grouping.feature`, `DictionarySteps`, `WorkPricesPage`), spec cross-references.

## 7. Testing strategy

- **Backend:** unit test of the resolver predicates (operator/value matrix); integration test with
  Testcontainers: seed works with 0/1/N norms per branch, assert counts, `>0`/`==0` filters, paging
  totals, and an **N+1 guard** using Hibernate statistics (statement count constant for page sizes 5
  and 50). Run only these classes with `--tests`, log to `/tmp`, read the JUnit XML
  (steering `test-execution-rules.md`).
- **Frontend (vitest + Testing Library):** list columns/filter emission; edit page by permission set
  (ADMIN, MANAGER, READ-only); create→redirect; unsaved-changes blocker; consumption table
  (add/commit/cancel/delete-confirm, dropdown search + scroll with mocked paged API, required-field
  defaults); formula editor (picker open/close matrix, Enter/Tab/Arrow, chip atomicity, paste, language
  switch); validator table tests incl. the examples of Req 6.3.
- **Browser (test-cases.md):** run per role on desktop and a narrow viewport.

## 8. Risks / decisions

| Item | Decision |
|------|----------|
| contentEditable complexity | Isolated in one component with a pure segment model + extensive unit tests; the textarea-based version stays available behind `multiline` only if a critical IME/caret bug is found (keep old file until 4.3 is green). |
| Source fields mandatory | Defaults pre-filled for new rows; API unchanged (Req 4.8). |
| Count not sortable | Accepted trade-off to avoid joins (Req 2.6). |
| Backend grammar wider than editor | Documented; editor-only restriction (Req 6.6). |
| Two specs numbered 19 | Folders differ by suffix (`…-material-consumption` vs `…-rework`); the rework supersedes the consumption UI of the former, not its entity/API. |
| Moved modules | Imports from other features are grep-checked before deletion (task 5.1). |
