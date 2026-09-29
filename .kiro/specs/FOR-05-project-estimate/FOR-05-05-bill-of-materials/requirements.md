# Requirements Document

## Introduction

This spec (**FOR-05-05-bill-of-materials**) delivers the **Estimate tab** of the project workspace
(`/projects/:projectId`, tab "Смета / Kosztorys") as a single full-stack feature. It renders an
interactive **works × rooms matrix**, assigns works to rooms (deriving the volume from the work's
formula, or — when a work has no formula — from the room dimension measured in the work's unit),
computes a **cost range** (labour + all materials) per cell, and lets the user drill into any cell
for a full price-calculation report and material selection.

Per the FOR-05 OVERVIEW split, FOR-05-05 was originally "bill-of-materials" (material aggregation)
with the interactive matrix in FOR-05-18 and works-composition in FOR-05-19. This spec **absorbs the
interactive matrix and the in-tab works assignment** so the Estimate tab is one cohesive
deliverable; FOR-05-18 and FOR-05-19 are folded into FOR-05-05 (OVERVIEW updated accordingly).

The feature builds on already-shipped foundations: the `Estimate` / `EstimateLine` /
`EstimateLineRoomQty` model (FOR-05-03), the single work price + volume-formula engine + per-package
override + package zł/m² model (FOR-05-04), the room 14-dimension model (FOR-04-14), the
material catalogs and consumption norms (FOR-04-19/20/21), and the reworked assortment
positions/prices (FOR-05-04-UI).

### Cost model (as confirmed)

- **Snapshot prices onto the estimate.** When a work is assigned, its labour price is **copied**
  from the catalog `WorkItem`'s single work price (with a provenance reference back to the work).
  Material costs are copied as a **collection keyed by material type** — construction materials
  (copied price range per type, + reference to the source material) and finishing materials (copied
  price range per type, + reference to the source finishing material). The estimate owns its frozen
  copies; catalog changes do not retro-actively mutate an estimate.
- **One volume per cell, shared by labour and every material.** The cell's **work volume** is
  computed once from the work's volume formula against the room's 14 dimensions (or the no-formula
  fallback). That SAME volume drives labour AND every material: labour = `workPrice × volume`, and
  each material's physical quantity = `consumption norm (per one work-unit) × volume`. Labour and
  each material type therefore always use the identical volume; the material's money contribution is
  `consumptionNorm × volume × typePriceRange`.
- **Cell cost range** = `labour + Σ material ranges`, where labour = `workPrice × volume` (a point)
  and each material contributes `consumptionNorm × volume × typePriceRange`. The cell shows a range
  `labour + Σ min .. labour + Σ max`. When **every** material type in the cell has a concrete product
  chosen, the range **collapses to a single value**.
- **Volume** = the work's volume formula evaluated against the room's 14 dimensions; when the work
  has no formula, the volume is the room dimension measured in the work's unit. All work items SHALL
  be seeded with a volume formula (this spec seeds them), so the no-formula fallback is a safety net.

## Glossary

- **Estimate_Tab**: the frontend project-workspace tab at `/projects/:projectId` (tab "Смета /
  Kosztorys"), ABAC resource `ESTIMATE`.
- **Works_Rooms_Matrix**: the grid rendered inside Estimate_Tab — works in rows, project rooms in
  columns, one editable assignment cell at each intersection.
- **Assignment**: a work assigned to a room — persisted as an `EstimateLine` (the work, once per
  estimate) plus an `EstimateLineRoomQty` (the per-room volume).
- **Volume**: the work's quantity for a room, derived from the work's volume formula (default or
  per-package override) evaluated against the room's 14 dimensions, or — with no formula — the room
  dimension in the work's unit. A single Volume per cell drives BOTH labour AND every material of
  that cell (a material's physical quantity = `consumption norm × Volume`); there is never a separate
  per-material volume.
- **Work_Price**: the single catalog `WorkItem` net work price (FOR-05-04), copied onto the estimate
  line as a frozen `unitPrice` with a provenance reference to the work.
- **Material_Type**: a `ConstructionMaterialType` (construction branch) or `MaterialType` (finishing
  branch) — the analog group a work consumes; never a concrete product.
- **Concrete_Material**: a specific `ConstructionMaterial` or `FinishingMaterial` product chosen from
  the catalog for a given material type in a cell.
- **Material_Line**: the estimate's copied material entry for a cell, keyed by
  `(assignment, branch, material type)`, carrying a copied price range and an optional
  `Concrete_Material` reference; a Material_Line with no concrete product is a **Placeholder**.
- **Placeholder**: a Material_Line that carries a price range but no chosen `Concrete_Material` yet.
- **Type_Price_Range**: the MIN..MAX `retailNet` over the active priced materials of a material type
  (construction: `PriceRangeResolver`; finishing: a new type[/package]-keyed resolver introduced
  here). Assortment placeholders use the assortment position's per-package min/avg/max instead.
- **Cell_Cost_Range**: the cell's cost band `labour + Σ material-range min .. labour + Σ material-
  range max`; collapses to a point when every Material_Line has a `Concrete_Material`.
- **Offer_Package**: an `OfferPackage` (`budget`/`norm`/`lux`) with a membership of works
  (`WorkPackageOverride.member`) and optional per-package volume override formula
  (`WorkPackageOverride.overrideParsedAst`).
- **Apply_Package**: the action that assigns every package-member work to its applicable rooms,
  layering on top of existing assignments without overriding already-assigned volumes.
- **Recompute_Finishing_Prices**: the action that recomputes the finishing-material price RANGE for
  already-assigned finishing Material_Lines under the selected package, changing only the range
  (never a chosen concrete product).
- **Assortment_Placeholder**: a finishing Placeholder seeded from a package's assortment position
  (matched to the work's finishing consumption type by material type), carrying the position's
  per-package price band.
- **Room_Type_Attachment**: an optional collection of room types on a work marking which rooms the
  work attaches to on Apply_Package; empty ⇒ the work attaches to all rooms.
- **Materials_Fill_Indicator**: the header indicator showing the share of assigned material lines
  that have a concrete product chosen vs placeholder-only.
- **Fill_State**: one of at most three states for an assigned cell's materials — fully filled (all
  concrete), partial (some concrete), placeholder-only (none concrete) — rendered via a theme-derived
  color scheme with a header legend and an accessible non-color cue.
- **Work_Material_Summary**: the work-level drill-in (opened by the work-row magnifier) aggregating a
  work's material lines across ALL its assigned rooms, allowing bulk concrete-product selection and
  material add/remove applied to every affected cell at once — the work-level analog of Cell_Report.
- **Work_Row_Apply (hammer)**: the work-row action that assigns the work to all rooms of the work's
  Room_Type_Attachment (all rooms when empty), layering on top without overriding existing volumes —
  the per-work analog of Apply_Package.
- **Cell_Report**: the drill-in panel opened on an assigned cell, describing the full price
  calculation (formula used + resolved Volume + work sum; per material type: norm, `norm × Volume`,
  and money range) and offering material add/remove and concrete-product selection.
- **Work_Type_Group**: a collapsible group of work rows sharing the same work type (work category),
  with a header carrying the group's aggregated mid-result subtotals (works / construction / finishing
  cost ranges) across the rooms. Collapsed by default; expands to reveal its work-item rows.
- **Positioning_Store**: the per-project `localStorage` store of the Estimate screen's view state
  (group expand/collapse, scroll/layout), independent of the staged-edits store.
- **Staged_Edits_Store**: the per-project `localStorage` store of pending (uncommitted) matrix edits
  plus a session-scoped undo/redo history, cleared on Save/Discard.

## Requirements

> Ordering: matrix shape (1–2) → assignment & volume (3–5) → cell drill-in & fill state (6–8) →
> work-row & catalog knobs (9–10) → package actions (11–12) → pricing snapshot & totals (13–14) →
> matrix state & view (15–16) → seeding (17) → cross-cutting i18n / permissions (18–19).

### Requirement 1: Works × rooms matrix rendering

**User Story:** As a foreman, I want to see all works as rows and all project rooms as columns, so I
can assign works to rooms in one grid.

#### Acceptance Criteria

1. WHEN the user opens Estimate_Tab for a project, THE Works_Rooms_Matrix SHALL render every catalog
   work item as a row (within its Work_Type_Group) and every room of the project as a column.
2. THE matrix SHALL show, in each assigned cell, the Cell_Cost_Range (labour + all materials).
3. WHERE a cell is unassigned, THE matrix SHALL render it as empty/assignable.
4. WHERE a room has no dimensions required by an assigned work's formula, THE cell SHALL still render
   with the volume computed under the formula engine's missing-variable-is-zero contract.
5. THE matrix SHALL be usable on desktop (dense table) and degrade gracefully on narrow viewports.
6. WHERE the user lacks `ESTIMATE` READ, THE Estimate_Tab SHALL be gated from that user.
7. WHEN the user opens Estimate_Tab for a project that has no estimate yet (e.g. an existing project
   created before the estimate feature), THE matrix read SHALL create the project's single estimate
   (defaulted to PLN/DRAFT, reusing the shipped get-or-create path) and return an empty, assignable
   matrix rather than failing, resolving to the same estimate on repeated reads without creating a
   second one.

### Requirement 2: Group works by type with group subtotals

**User Story:** As a foreman, I want works grouped by their type (work category) with per-group
subtotals, collapsed by default, so the matrix stays scannable.

#### Acceptance Criteria

1. THE Works_Rooms_Matrix SHALL group work rows by the work's type (work category), rendering a
   Work_Type_Group header row per type.
2. THE group header SHALL show the group's aggregated mid-results (subtotals) applied within the
   group — the group's works cost, construction-materials cost, and finishing-materials cost across
   the rooms, each as a collapsing range (per Requirement 14's range/collapse rules).
3. THE groups SHALL be COLLAPSED by default; clicking a group header SHALL reveal (expand) the list of
   work-item rows in that group, and clicking again SHALL collapse it.
4. THE per-group subtotals SHALL recompute live as assignments, materials, or a package apply change.
5. WHERE a group has no assigned works, its subtotals SHALL be zero (or an empty range) and the group
   SHALL still render as collapsible.

### Requirement 3: Assign a work to a room by clicking an empty cell

**User Story:** As a foreman, I want to click an empty cell to assign the work to the room and get
its price automatically, so I don't hand-enter volumes.

#### Acceptance Criteria

1. WHEN the user clicks an empty cell, THE system SHALL assign the work to the room (create/extend the
   `EstimateLine` and add an `EstimateLineRoomQty`).
2. WHEN a work is assigned, THE Volume SHALL be computed from the work's volume formula (default, or
   the selected package's override formula when a package is applied) evaluated against the room's 14
   dimensions.
3. IF the work has no volume formula, THEN THE Volume SHALL be the room dimension measured in the
   work's measurement unit (per the unit→dimension fallback in Requirement 5).
4. WHEN a work is assigned, THE system SHALL copy the work's labour price onto the line as a frozen
   `unitPrice` with a provenance reference to the work, and SHALL create the material lines for the
   work's consumption types with copied Type_Price_Range values.
5. WHEN a cell is assigned, THE Cell_Cost_Range SHALL be computed and displayed immediately, and THE
   header totals and the Materials_Fill_Indicator SHALL update.

### Requirement 4: One volume per cell, shared by labour and every material

**User Story:** As the system, I want a single volume per cell used for both labour and all its
materials, so labour and material quantities are always consistent.

#### Acceptance Criteria

1. THE cell's Volume SHALL be computed ONCE (formula or fallback) and used identically for labour and
   for every material of the cell; THE system SHALL NOT compute a separate volume per material.
2. THE physical quantity of each material in a cell SHALL be `consumption norm (per one work-unit) ×
   Volume`.
3. THE money contribution of each material SHALL be `consumption norm × Volume × Type_Price_Range`,
   and labour SHALL be `workPrice × Volume` — sharing the same Volume.
4. THE Cell_Report and Work_Material_Summary SHALL disclose the shared Volume and the per-material
   `norm × Volume` derivation.

### Requirement 5: Volume unit→dimension fallback (no formula)

**User Story:** As the system, I want a defined fallback when a work has no formula, so a cell still
gets a volume.

#### Acceptance Criteria

1. WHERE a work has no volume formula and no package override formula, THE Volume SHALL be the room
   dimension whose conventional unit matches the work's measurement unit.
2. THE mapping SHALL be: `m2` → floor area; `m` (length) → perimeter; `szt` (count) → 1 per room
   (unit assignment), unless a more specific mapping is defined for the work.
3. WHERE the work's unit maps ambiguously to more than one room dimension, THE system SHALL use the
   documented default (floor area for `m2`) and the Cell_Report SHALL disclose that a fallback was
   used.
4. WHERE no room dimension matches the work's unit, THE Volume SHALL be zero and the cell SHALL show
   the placeholder/needs-attention state.

### Requirement 6: Cell report and material management

**User Story:** As a foreman, I want to click an assigned cell and see exactly how its price is
calculated and manage its materials, so I control the estimate precisely.

#### Acceptance Criteria

1. WHEN the user clicks an assigned cell, THE system SHALL open the Cell_Report describing: the
   formula used for the work and the resolved Volume and work sum; and, per material type (both
   branches), its consumption norm, the derived physical quantity `norm × Volume`, and the money
   range `norm × Volume × Type_Price_Range`.
2. THE Cell_Report SHALL allow removing any material line and adding a material line of either branch
   (construction or finishing) by material type.
3. WHEN the user clicks a material type in the Cell_Report, THE system SHALL let the user pick a
   Concrete_Material of that type from the corresponding catalog (construction or finishing).
4. WHEN a Concrete_Material is chosen for a material line, THE line's contribution SHALL become that
   product's price (the line's range collapses to that product's price).
5. WHEN a material line is added, removed, or its concrete product changes, THE Cell_Cost_Range, the
   group subtotals, the header totals, and the Materials_Fill_Indicator SHALL update.
6. THE Cell_Report SHALL show, per material line, whether it is a Placeholder (range only) or has a
   Concrete_Material chosen.

### Requirement 7: Cell fill-state indicator

**User Story:** As a foreman, I want to see at a glance which cells still have placeholder materials,
so I know where concrete products are missing.

#### Acceptance Criteria

1. WHERE any Material_Line in an assigned cell is a Placeholder (no Concrete_Material chosen), THE
   cell SHALL show a "not all materials selected" indicator (the partial or placeholder-only state of
   Requirement 8).
2. WHERE every Material_Line in an assigned cell has a Concrete_Material chosen, THE cell SHALL show
   the fully-filled state (no needs-attention indicator) and the Cell_Cost_Range SHALL be a single
   collapsed value.
3. THE indicator SHALL be visually distinct from the cost range text and accessible (not colour-only),
   per the color scheme + non-color cue of Requirement 8.

### Requirement 8: Fill-state color scheme with header legend

**User Story:** As a foreman, I want a clear, theme-based color scheme for material fill states with a
small legend in the header, so I instantly see what's filled, partial, or placeholder-only.

#### Acceptance Criteria

1. THE Estimate_Tab SHALL represent each assigned cell's Fill_State with a color scheme of AT MOST
   THREE states derived from the user's main theme color: (a) all materials have a Concrete_Material
   chosen (fully filled); (b) some but not all chosen (partial); (c) none chosen (placeholder-only).
2. THE header SHALL show a small legend/map mapping each color to its state.
3. THE color scheme SHALL be derived from the current theme's primary color (e.g. tints/shades of the
   theme color) and SHALL respect light/dark theme.
4. THE Fill_State SHALL NOT be conveyed by color alone — an accessible non-color cue
   (icon/label/aria) SHALL accompany the color.
5. THE color scheme SHALL apply consistently to cells and, where meaningful, to the Work_Type_Group
   subtotals and work-row indicators.

### Requirement 9: Work-row actions: work-level material summary and room-type apply

**User Story:** As a foreman, I want per-work-row actions to review/fill all of a work's materials at
once and to apply a work to all its room types in one click, so I work at the work level, not only
cell by cell.

#### Acceptance Criteria

1. THE Works_Rooms_Matrix SHALL render, on each work row, a magnifier icon and a hammer icon.
2. WHEN the user clicks the work-row magnifier, THE system SHALL open a Work_Material_Summary — the
   work-level analog of the Cell_Report — aggregating that work's material lines across ALL its
   assigned rooms: per material type (both branches), its consumption norm, the aggregated quantity
   and money range across the assigned rooms, and whether concrete products are chosen or placeholders
   remain.
3. THE Work_Material_Summary SHALL allow choosing a Concrete_Material for a material type and applying
   it to ALL of the work's assigned cells at once (bulk fill), and removing/adding a material type for
   the work; changes SHALL propagate to every affected cell.
4. WHEN a bulk apply/change is made in the Work_Material_Summary, THE affected cells' Cell_Cost_Range,
   the group subtotals, the header totals, and the Materials_Fill_Indicator SHALL update, and the
   change SHALL be undoable (Requirement 15).
5. WHEN the user clicks the work-row hammer, THE system SHALL assign the work to all rooms whose room
   type is in the work's Room_Type_Attachment (all rooms when the attachment is empty), computing each
   attached room's Volume individually by the work's formula — the per-work analog of Apply_Package
   scoped to one work.
6. THE work-row hammer SHALL layer on top of existing assignments and SHALL NOT override
   already-assigned volumes for that work (mirroring Apply_Package's layering rule).
7. WHERE the matrix is read-only (Requirement 15.5), THE magnifier SHALL remain available (read-only
   summary) and the hammer and any fill/add/remove actions SHALL be disabled.

### Requirement 10: Room-type attachment on works

**User Story:** As a catalog admin, I want a work to optionally target specific room types, so
applying a package (or the work-row hammer) attaches the work only to relevant rooms.

#### Acceptance Criteria

1. THE system SHALL support an optional Room_Type_Attachment (a set of room types) on a work item.
2. WHERE a work's Room_Type_Attachment is non-empty, an apply (package or work-row hammer) SHALL
   attach the work only to rooms of those types; each attached room's Volume SHALL be computed
   individually by the work's formula.
3. WHERE a work's Room_Type_Attachment is empty, THE work SHALL attach to all rooms on apply.
4. THE volume formula SHALL remain independent of the Room_Type_Attachment (the attachment governs
   which rooms a work attaches to, not how the volume is computed).
5. WHEN a user opens the Work Catalog edit form (`/catalog/works`, edit mode) for a work item, THE
   form SHALL let the user view and manage that work item's Room_Type_Attachment — view the currently
   attached room types, add or remove room types, and save the change — persisting the selected set
   independently of the main work-item create/update form; AND WHERE the user clears all selections,
   saving SHALL clear the attachment so the work attaches to all rooms on apply (per criterion 3).
6. THE system SHALL seed each work item's INITIAL Room_Type_Attachment from the meaning of its work
   category: wet-room works (tiling and plumbing rough/finish) SHALL attach to the kitchen and
   bathroom; floor works SHALL attach to the dry rooms (excluding kitchen and bathroom); carpentry
   works SHALL attach to the dry living/circulation rooms; and every other category SHALL be left
   with an EMPTY attachment (attaches to all rooms on apply, per criterion 3). The seed SHALL be an
   idempotent Liquibase changeset registered last in sequence, and SHALL NOT overwrite the
   attachment of any work item that already has one (so an attachment edited via criterion 5 is
   preserved).

### Requirement 11: Package selector and Apply_Package

**User Story:** As a foreman, I want to pick a package and apply its works to the rooms, so the whole estimate is populated and priced automatically.

#### Acceptance Criteria

1. THE Estimate_Tab header SHALL provide an Offer_Package selector that lists every Offer_Package available to the current estimate and permits exactly one package to be selected per Apply_Package invocation.
2. WHEN the user applies a package, THE system SHALL compute, without issuing any persistence request to the server, for every work whose `WorkPackageOverride.member` is true, the set of applicable rooms, each assignment's Volume, and each Cell_Cost_Range.
3. WHEN Apply_Package has computed the assignments, THE system SHALL apply the computed assignments onto the matrix as staged edits that are undoable and redoable per Requirement 15 and that are written to the server only upon Save.
4. WHEN Apply_Package assigns a work to a room where that work is not already assigned, IF `WorkPackageOverride.overrideParsedAst` is present for that work, THEN THE system SHALL compute the Volume using the package override formula, ELSE THE system SHALL compute the Volume using the work's default volume formula.
5. IF a work is already assigned in a room at the time Apply_Package runs, THEN THE system SHALL retain the existing Volume unchanged and SHALL NOT replace it with any package-derived Volume.
6. WHERE a work has a non-empty Room_Type_Attachment, Apply_Package SHALL attach the work only to rooms whose room type is a member of that Room_Type_Attachment set, and SHALL NOT attach it to any room whose room type is absent from that set.
7. WHERE a work has an empty Room_Type_Attachment, Apply_Package SHALL attach the work to every room in the estimate.
8. WHEN Apply_Package runs, THE system SHALL seed one finishing Assortment_Placeholder for each work-finishing-consumption whose material type equals the material type of a package assortment position, carrying that position's per-package price band onto the seeded placeholder, and SHALL NOT seed a placeholder for any finishing consumption material type that has no matching package assortment position.
9. WHEN Apply_Package has staged its computed assignments, THE system SHALL recompute the header totals and the Materials_Fill_Indicator from the staged, not-yet-committed state so that they reflect the staged applied works.

### Requirement 12: Recompute finishing-material prices under a package

**User Story:** As a foreman, I want to recompute finishing-material prices for the selected package, so I can compare package pricing without changing my chosen products.

#### Acceptance Criteria

1. THE Estimate_Tab header SHALL provide a Recompute_Finishing_Prices action for the selected package, enabled only WHILE exactly one package is selected and at least one already-assigned finishing Material_Line exists.
2. WHEN Recompute_Finishing_Prices runs, THE system SHALL compute the recomputed finishing-material price RANGE for already-assigned finishing Material_Lines only, without persisting to the server, and SHALL apply the recomputed ranges onto the matrix as staged edits that are undoable/redoable per Requirement 15 and persisted only on Save.
3. WHEN Recompute_Finishing_Prices runs, THE system SHALL restrict its effect to finishing Material_Lines and SHALL NOT modify construction materials, labour, or volumes.
4. WHEN Recompute_Finishing_Prices runs, THE system SHALL modify only the displayed and staged price range of each affected finishing Material_Line and SHALL NOT change the chosen Concrete_Material of any line.
5. THE finishing Type_Price_Range for a package SHALL equal the minimum-to-maximum `retailNet` value over all active priced finishing materials of that type that belong to that package (`finishing_material_packages`).
6. IF Recompute_Finishing_Prices runs and no already-assigned finishing Material_Line has at least one active priced finishing material for its type within the selected package, THEN THE system SHALL leave every affected line's existing price range unchanged, SHALL create no staged edit, and SHALL present an indication that no recomputable finishing prices were found.

### Requirement 13: Estimate snapshot / copied prices

**User Story:** As a foreman, I want the estimate to freeze its prices, so later catalog edits don't
silently change my estimate.

#### Acceptance Criteria

1. WHEN a work is assigned, THE estimate SHALL store a copied `unitPrice` (from the work's single work
   price) with a provenance reference to the source work.
2. WHEN a material line is created, THE estimate SHALL store a copied Type_Price_Range for the branch
   and material type with a provenance reference to the source catalog material (the material that
   contributed the range, and the concrete product when chosen).
3. WHERE a source catalog price later changes, THE estimate's copied prices SHALL remain unchanged
   until the user re-assigns or recomputes.
4. THE copied material collection SHALL be keyed by material type per assignment.

### Requirement 14: Header totals and fill indicators

**User Story:** As a foreman, I want to see the overall costs and how filled the estimate is, so I
understand the estimate at a glance.

#### Acceptance Criteria

1. THE Estimate_Tab header SHALL show the total cost of **works**, of **construction materials**, and
   of **finishing materials**, separately.
2. Each total SHALL be a range (min..max) EXCEPT WHERE all contributing material lines have a
   Concrete_Material chosen, in which case the affected total SHALL collapse to a single value.
3. THE header SHALL show the Materials_Fill_Indicator as a percentage of assigned material lines that
   have a Concrete_Material chosen out of all assigned material lines in the current work volume.
4. THE totals and the indicator SHALL recompute live as the user assigns, edits, or applies a package.

### Requirement 15: Interactive matrix state (staging), undo/redo, and commit

**User Story:** As a foreman, I want my matrix edits staged locally with an unsaved indicator and full undo/redo, so I commit deliberately (mirroring the rooms/assortment editors).

#### Acceptance Criteria

1. WHEN the user makes an assignment or material edit, THE Works_Rooms_Matrix SHALL record it client-side per project in the Staged_Edits_Store, persist it to `localStorage`, and display an "unsaved changes" indicator until the staged set is committed or discarded.
2. THE matrix SHALL provide a Save action that persists the staged set to the server in one batched request and a Discard action that clears the staged set without persisting.
3. THE matrix SHALL support Undo and Redo of every staged change — assign, unassign, add material, remove material, choose concrete product, Apply_Package result, and Recompute_Finishing_Prices result — up until Save, and WHEN Save succeeds THE matrix SHALL clear both the undo/redo history and the staged set.
4. THE undo/redo history SHALL be session-scoped and SHALL NOT be persisted; THE staged pending values SHALL be persisted to `localStorage` so that a reload restores the in-progress estimate.
5. WHERE the project is not editable (not DRAFT) or the user lacks `ESTIMATE` UPDATE, THE matrix SHALL be read-only, disabling assign, edit, Apply_Package, Recompute_Finishing_Prices, Save, Discard, Undo, and Redo.
6. WHEN Apply_Package or Recompute_Finishing_Prices is invoked, THE system SHALL NOT persist any change to the server at that moment, SHALL obtain the operation's calculated result (WHEN the result is computed server-side, via a read-only endpoint that persists nothing), and SHALL apply that result onto the matrix as staged edits that are undoable and redoable per criterion 3 and persisted to the server only on Save.
7. THE matrix SHALL commit every edit — cell edits and Apply_Package and Recompute_Finishing_Prices results alike — through the single batched Save, and SHALL NOT write any matrix edit to the server outside that batched Save.

### Requirement 16: Persisted screen positioning

**User Story:** As a foreman, I want the Estimate screen to remember its layout, so re-opening it
keeps my view.

#### Acceptance Criteria

1. THE Estimate_Tab SHALL persist its screen positioning to `localStorage` per project (the
   Positioning_Store) — at least the expanded/collapsed state of each Work_Type_Group, and any
   scroll/layout position the UI exposes.
2. WHEN the user re-opens the Estimate_Tab for the same project, THE persisted positioning SHALL be
   restored (groups re-open as previously left; default is all collapsed on first visit).
3. THE Positioning_Store SHALL be independent of the Staged_Edits_Store (positioning is a view
   concern; clearing staged edits or committing SHALL NOT reset positioning).

### Requirement 17: Seed all work items with volume formulas

**User Story:** As the system, I want every work item to have a volume formula, so cell volumes are
computed automatically rather than hand-entered.

#### Acceptance Criteria

1. THE system SHALL seed a volume formula for every catalog work item, derived from the meaning of the
   work over the room's 14 dimensions.
2. Each seeded formula SHALL be valid under `FormulaValidator` (only the 14 known dimension variables
   and known cross-work references).
3. WHERE a work already has a volume formula, THE seed SHALL NOT overwrite it (idempotent).
4. THE seed SHALL be an idempotent Liquibase changeset registered last in sequence.

### Requirement 18: Internationalization

**User Story:** As a bilingual user, I want every new UI string localized, so the tab is usable in PL
and RU.

#### Acceptance Criteria

1. THE frontend SHALL provide every new or changed user-facing string (tab title, package selector,
   apply-package, recompute-finishing-prices, cell-report labels, work-material-summary labels,
   add/remove material, concrete-product picker, the not-all-materials indicator + fill-state legend,
   totals labels, group headers, errors) in BOTH the Polish and Russian locale files.
2. THE frontend SHALL NOT surface a raw localization key to the user on any new or changed screen.
3. THE feature SHALL NOT introduce new backend i18n entity fields; it reuses the existing localized
   names (work / room / material / package names already carry RU/PL).

### Requirement 19: Permissions, persistence integrity, git

#### Acceptance Criteria

1. All new estimate read/write endpoints SHALL be guarded by the `ESTIMATE` ABAC resource with the
   appropriate operation (READ for reads, UPDATE for assignment/material/package writes).
2. WHEN any schema is added (material lines, room-type attachment), THE backend SHALL use idempotent
   Liquibase changesets; a re-run against an already-migrated database SHALL make no further changes.
3. THE estimate's per-(line, room) material collection SHALL cascade-delete with its owning line/room
   quantity.
4. Frontend source changes SHALL be committed from within the `foremen-frontend/` repository; backend,
   migration, and spec changes SHALL be committed from the root repository (two commits when a change
   spans both).

## Subsequently Affected / Out of Scope

- **Margins / package VALUE comparison (FOR-05-06)** — this spec computes and displays cost ranges and
  supports comparing package pricing via Recompute_Finishing_Prices, but the margin model
  (`EstimateLineMargin`) and offer VALUE remain FOR-05-06.
- **Offer / approval (FOR-05-07)** and client rendering (FOR-09) are out of scope.
- FOR-05-05 does NOT redefine the material catalogs, the formula engine, or the assortment model; it
  consumes them. The only new material logic is the finishing type[/package] price-range resolver and
  the estimate's copied-price material collection.
- The OVERVIEW is updated to fold FOR-05-18 (estimate-matrix) and FOR-05-19 (works-composition) into
  FOR-05-05.
