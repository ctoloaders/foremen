# Requirements Document

## Introduction

This spec (**FOR-05-02-rooms-visual-editor**) is a sub-spec of **FOR-05-02-rooms-autoimport**. It
turns the read-only SVG wall plan of a room (`RoomPlanPreview`) into an **interactive visual room
editor**: corners, walls, doors and windows become draggable and resizable directly on the drawing,
and a user can build a room from scratch on the canvas. The existing wall table
(`RoomWallsEditor`: length, turn, gaps, openings) stays and is kept in two-way sync with the drawing.

The feature is **frontend only**. The stored `RoomGeometry` contract (`vertices`, `walls[]` with
`wallGap`, `finishGap`, `openings[]` with `type`, `count`, `height`, `width`, optional `offset`) and
the backend validation (`RoomService.validateGeometry`) are unchanged.

### Glossary

- **Outline** — the room contour: an ordered chain of corners (points) joined by walls. It is
  *closed* (a polygon, wall `i` = corner `i` → corner `(i+1) mod n`) or *open* (a chain with a free
  end, used while drawing from scratch or while the table makes the contour not closed).
- **Corner** — a vertex of the outline.
- **Free end** — the last corner of an open outline; not connected to the first corner.
- **Opening** — a door or a window placed on a wall. Its position is `offset` (metres from the
  wall start along the wall direction), its occupied span is `count × width`.
- **Snap** — a gentle attraction of a dragged element to a guide (extension of another wall, right
  angle, wall end, centre). Beyond the snap radius the element moves freely.
- **Gesture** — one pointer interaction from press to release (drag corner, drag wall, stretch
  wall end, move opening, resize opening).
- **Live size** — dimension labels drawn on the canvas while a gesture is in progress.
- **Host** — a screen that embeds the editor: the room editor page, the new-room dialog of the
  rooms matrix (generated/AI draft rooms), and the room form sheet.

### Scope boundaries

- **In scope:** the interactive canvas (corners, walls, doors, windows), the template-first creation
  flow, the blank-canvas "+" flow, live sizes, context menu, undo/redo, keyboard operation, two-way
  sync with the wall table, backward compatibility with stored and AI-generated geometry, pl + ru
  localization, the three hosts.
- **Out of scope:** any backend or database change; inserting or deleting corners on the canvas
  (walls are still added/removed in the table; "+" only appends); curved walls; multiple rooms on
  one canvas; zoom/pan of the canvas; rotating the whole room; touch-optimised hit sizes and
  gestures (pointer events are used, so touch works in principle, but only desktop mouse behaviour
  is required and tested).

### Notifications

This feature emits **no notifications**. It is a client-side editing surface; it changes no domain
state of other users beyond the existing room save, which already has its own behaviour.

### Backend i18n

No entity fields require internationalization: no new entity, column or DTO field is introduced.

## Requirements

### Requirement 1: Interactive canvas

**User Story:** As an estimator, I want to edit the room directly on its drawing, so that I do not
have to translate a shape into lengths and angles by hand.

#### Acceptance Criteria

1. THE editor SHALL render the outline, wall length labels, area, door and window symbols and the
   legend exactly as the current `RoomPlanPreview` does, as the base layer of the interactive canvas.
2. THE canvas SHALL use pointer events with pointer capture, so a gesture continues when the pointer
   leaves the element and always ends on release or `Escape` (Escape cancels the gesture and
   restores the state at its start).
3. WHILE a gesture is in progress THE canvas viewport (scale and offset) SHALL stay fixed; it SHALL
   be re-fitted to the outline only after the gesture ends, so the drawing never rescales under the
   pointer.
4. THE canvas SHALL keep the world orientation of `RoomPlanPreview` (y up, metres) and SHALL convert
   pointer positions to world coordinates with the exact inverse of the rendering transform.
5. THE canvas SHALL keep a minimum visible extent of 4 m × 4 m so an empty or tiny outline is
   comfortable to draw on.
6. WHEN the host is read-only (no `ROOMS`/`UPDATE` permission or the project is not in DRAFT) THE
   canvas SHALL render exactly as before with no handles, no hover effects, no context menu and no
   "+" button.

### Requirement 2: Template-first creation flow

**User Story:** As an estimator creating a room from scratch, I want to start from a standard shape
and its dimensions, so that I only adjust what differs.

#### Acceptance Criteria

1. THE editor SHALL offer the existing templates dialog (`RoomTemplatesDialog`: choose a template,
   enter its measures, rotate) as the first step of the scratch flow.
2. WHEN a template is applied THE resulting closed outline SHALL appear on the canvas immediately in
   interactive mode, with the template's walls, and no further confirmation step.
3. THE flow SHALL keep the existing replace warning when walls already exist.
4. WHEN a template has been applied THE "+" add-wall button (Requirement 8) SHALL be hidden because
   the outline is closed.

### Requirement 3: Corner hover and drag

**User Story:** As an estimator, I want to grab a corner and move it, so that walls follow and I see
the new shape at once.

#### Acceptance Criteria

1. WHEN the pointer hovers a corner (hit radius of at least 12 px) THE corner SHALL be highlighted
   and the cursor SHALL indicate that it can be dragged.
2. WHEN a corner is dragged THE two walls attached to it SHALL follow the corner (their endpoints
   move with it); all other corners and walls SHALL stay unchanged.
3. WHILE a corner is dragged THE corner SHALL stick to snap guides within a snap radius of 10 px:
   (a) the infinite extension line of any wall not attached to it, (b) the line through a
   neighbouring corner that makes a right angle with that neighbour's other wall, (c) the line
   through a neighbouring corner that continues that neighbour's other wall straight (180°). Active
   guides SHALL be drawn as dashed lines. WHEN the pointer is further than the snap radius from every
   guide THE corner SHALL move freely, following the pointer.
4. WHEN the `Alt` key is held THE snapping SHALL be disabled for the gesture.
5. THE corner position SHALL be rounded to 1 mm when the gesture ends.
6. A corner drag SHALL NOT make any attached wall shorter than the larger of 0.10 m and the total
   span of the openings on that wall; the corner SHALL stop at that limit.
7. THE openings of an attached wall SHALL keep their `offset` from the wall start and SHALL be
   re-clamped to remain within the wall when the wall length changes.
8. A corner drag that makes the outline self-intersecting SHALL be allowed; the crossing walls SHALL
   be drawn in the existing error colour and the existing save-block rule SHALL apply.
9. EACH corner of an open outline, including the free end, SHALL be draggable with the same rules.

### Requirement 4: Wall selection, translation and stretching

**User Story:** As an estimator, I want to move a whole wall or change its length by its edges, so
that I can adjust the room without touching the corners one by one.

#### Acceptance Criteria

1. WHEN the pointer hovers a wall THE wall SHALL be highlighted; WHEN it is clicked THE wall SHALL
   become selected (distinct highlight, end grips visible); clicking empty canvas or pressing
   `Escape` SHALL clear the selection.
2. WHEN a selected wall is dragged by its body THE whole wall SHALL be translated: its length and
   direction SHALL NOT change; both endpoints move by the same vector and the attached neighbour
   walls follow.
3. THE translation SHALL by default be constrained to the wall's normal (perpendicular) direction;
   WHEN `Shift` is held THE translation SHALL be free in any direction.
4. WHEN a grip at a wall end is dragged THE wall length SHALL change along the wall's own direction:
   the dragged end moves, the other end stays, and the attached neighbour wall follows.
5. THE wall length SHALL snap to 0.01 m steps while stretching and SHALL NOT go below the limit of
   Requirement 3.6.
6. Selecting a wall SHALL also highlight its row in the wall table, and focusing a table row SHALL
   highlight (not select) the wall on the canvas (existing behaviour).

### Requirement 5: Doors and windows on walls

**User Story:** As an estimator, I want to slide a door or window along its wall and change its
width, so that I can place it as it is in reality.

#### Acceptance Criteria

1. WHEN the pointer hovers an opening THE opening SHALL be highlighted; WHEN it is clicked THE
   opening SHALL become selected and its edge grips SHALL be shown.
2. WHEN an opening is dragged THE opening SHALL move only along its own wall; its `offset` SHALL be
   clamped to `[0, wallLength − span]` and SHALL NOT change the wall.
3. THE opening position SHALL snap (within 10 px) to the wall start, the wall end, the wall centre
   and the edges of neighbouring openings, and otherwise to 0.01 m steps.
4. AN opening SHALL NOT overlap another opening on the same wall: the drag SHALL stop at the
   neighbour's edge.
5. WHEN an edge grip of an opening is dragged THE opening SHALL be resized along the wall: the
   dragged edge moves, the opposite edge stays; for `count` > 1 the per-unit `width` SHALL change and
   the total span SHALL be shown. THE minimum width SHALL be 0.30 m and the maximum SHALL be the
   free space up to the wall end or the neighbouring opening.
6. WHEN an opening without a stored `offset` (laid out automatically) is first moved or resized THE
   editor SHALL materialize `offset` for it and SHALL keep the other offset-less openings visually
   in place.
7. THE `height` and `count` of an opening SHALL NOT be changed on the canvas (they stay editable in
   the table); a new opening SHALL get defaults: door 0.90 m × 2.10 m, window 1.20 m × 1.40 m,
   `count` 1.

### Requirement 6: Live sizes during gestures

**User Story:** As an estimator, I want to see the real sizes while I drag, so that I can stop at
the exact value.

#### Acceptance Criteria

1. WHILE a corner is dragged THE canvas SHALL show the lengths of the two attached walls and the
   interior angle at the corner (whole degrees).
2. WHILE a wall is translated THE canvas SHALL show the translation distance and the lengths of the
   two neighbour walls.
3. WHILE a wall end is stretched THE canvas SHALL show the new wall length.
4. WHILE an opening is moved THE canvas SHALL show the distance from the wall start and from the
   wall end to the opening; WHILE it is resized THE canvas SHALL show its width (and the total span
   when `count` > 1).
5. Live sizes SHALL use the same unit (m) and 2-decimal format as the wall labels, SHALL be
   localized, and SHALL disappear when the gesture ends.
6. AFTER a gesture the wall table SHALL show the final values.

### Requirement 7: Context menu

**User Story:** As an estimator, I want to add a door or window with a right click on a wall, so that
I do not have to use the table.

#### Acceptance Criteria

1. WHEN the user right-clicks a wall THE editor SHALL open a context menu (instead of the browser
   menu) with the items "Add door" and "Add window".
2. CHOOSING an item SHALL add an opening of that type with the defaults of Requirement 5.7, centred
   at the click position on the wall, clamped inside the wall and moved to the nearest free span;
   the new opening SHALL become selected.
3. IF the wall has no free span wide enough for the new opening THEN the menu item SHALL be disabled
   with an explanatory tooltip.
4. WHEN the user right-clicks an opening THE menu SHALL contain "Delete opening"; the `Delete` /
   `Backspace` key SHALL delete the selected opening too.
5. THE menu SHALL close on selection, outside click, `Escape` or the start of any gesture, and SHALL
   be keyboard operable (arrow keys, `Enter`).
6. THE context menu SHALL NOT appear in read-only mode.

### Requirement 8: Blank canvas, "+" and free end

**User Story:** As an estimator starting without a template, I want to draw the walls one by one, so
that I can build any shape.

#### Acceptance Criteria

1. WHEN the outline has no walls THE canvas SHALL show a "+" button in its top-right corner (next to
   the templates action); it SHALL be a real, labelled, focusable button.
2. WHEN "+" is pressed on an empty outline THE editor SHALL create a default horizontal wall 2.00 m
   long, from (0, 0) to (2, 0).
3. WHEN "+" is pressed on an open outline THE editor SHALL append a new 2.00 m wall starting at the
   current free end, turned 90° to the left (counter-clockwise) relative to the previous wall.
4. THE free end of an open outline SHALL be freely draggable (Requirement 3.9) and SHALL be marked
   distinctly (an open end indicator and the hint "free end").
5. WHEN the free end is dragged within 10 px of the first corner and released THE outline SHALL
   close by merging the free end into the first corner, provided it then has at least 3 walls.
6. WHEN an open outline has at least 2 walls THE editor SHALL show a "Close outline" action that adds
   one straight closing wall from the free end to the first corner.
7. WHEN the outline is closed THE "+" button and the "Close outline" action SHALL be hidden.
8. AN open outline SHALL NOT be saveable (existing "not closed" save-block rule and message).

### Requirement 9: Backward compatibility and round trip

**User Story:** As a user with existing and AI-generated rooms, I want them to open in the new
editor unchanged, so that nothing is lost or rotated.

#### Acceptance Criteria

1. THE editor SHALL load any stored `RoomGeometry` that the backend accepts: arbitrary vertex
   positions (not anchored at the origin), clockwise or counter-clockwise order, non-integer angles,
   non-orthogonal shapes, and any number of walls.
2. THE editor SHALL load openings with and without `offset`, with `count` > 1, and with
   `width`/`height` of any positive value; automatic layout of offset-less openings SHALL match the
   current `placeOpenings` result.
3. IF the user makes no geometry change THEN saving SHALL send the stored geometry unchanged
   (same vertices and walls, no re-derivation through rounded lengths and turns).
4. WHEN the user edits the geometry THE saved vertices SHALL be rounded to 1 mm, and untouched
   openings and gaps SHALL be preserved as they were.
5. Table edits SHALL NOT rotate or move the drawing: the editor SHALL keep the position of the first
   corner and the heading of the first wall of the loaded geometry when it re-derives the outline
   from lengths and turns.
6. WHEN a canvas gesture changes a value that has a stored dimension formula (`dimensionFormulas`
   key `geometry.walls[i].length`, `.turn`, or an opening `offset`/`width`) THE formula of that field
   SHALL be dropped and the numeric value kept; formulas of untouched fields SHALL be preserved.
7. THE draft rooms produced by the AI auto-import and edited in the new-room dialog SHALL get the same
   behaviour, including the exact-geometry round trip.
8. THE existing live metrics (areas, perimeter, door/window counts) SHALL be recalculated from the
   current outline after every gesture with the existing `calculateRoomMetrics`.

### Requirement 10: Sync with the wall table and validation

**User Story:** As an estimator, I want the table and the drawing to always agree, so that I can use
whichever I prefer.

#### Acceptance Criteria

1. AFTER every gesture THE table rows SHALL show the lengths (3 decimals, mm) and turns (whole
   degrees) of the new outline, the openings with their `offset`, `width`, and the gaps unchanged.
2. WHEN the user edits a length, turn, opening or gap in the table THE canvas SHALL redraw at once; a
   turn or length edit SHALL be applied to the exact (unrounded) outline so that untouched walls keep
   their exact shape.
3. THE closing-wall mode of the table (`autoClose`) SHALL keep working for table edits; a canvas
   gesture SHALL never be altered by it (a closed outline stays closed because corners are shared).
4. THE existing validation (outline status, crossing walls, opening fits the wall, formula issues)
   and the save-block reasons SHALL apply unchanged to the edited outline.
5. THE editor SHALL NOT keep two independent copies of the geometry: the outline model is the single
   source of truth and the table rows are a projection of it.

### Requirement 11: Undo and redo

**User Story:** As an estimator, I want to undo a wrong drag, so that experimenting is safe.

#### Acceptance Criteria

1. THE editor SHALL provide Undo and Redo buttons and the shortcuts `Ctrl/Cmd+Z` and
   `Ctrl/Cmd+Shift+Z` (also `Ctrl+Y`) while the editor has focus.
2. EACH completed gesture, each "+" press, each template application, each context-menu action and
   each committed table edit (one entry per focus session of a table field) SHALL be one history
   entry; a cancelled gesture SHALL create none.
3. UNDO followed by REDO SHALL restore exactly the same outline (vertices, walls, openings).
4. THE history SHALL be local to the editing session of the host (reset on load of another room) and
   SHALL hold at least 50 entries.

### Requirement 12: Keyboard operation and accessibility

**User Story:** As a keyboard user, I want to operate the drawing without a mouse.

#### Acceptance Criteria

1. THE canvas SHALL be focusable; corners, walls and openings SHALL be reachable by `Tab` /
   arrow keys as focusable elements with `aria-label`s (corner index, wall index, opening type and
   wall).
2. WHILE an element is focused, the arrow keys SHALL nudge it by 0.01 m and `Shift` + arrows by
   0.10 m, with the same rules and limits as dragging (a corner moves, a wall translates along its
   normal, an opening slides along its wall); `Delete` SHALL delete a focused opening.
3. THE canvas SHALL expose a short keyboard help text through `aria-describedby`.
4. Colour SHALL NOT be the only indicator of hover/selection/error: selection also changes stroke
   width and shows grips.

### Requirement 13: Hosts

**User Story:** As a user, I want the same editor wherever I edit a room.

#### Acceptance Criteria

1. THE room editor page (`/projects/:projectId/rooms/:roomId`) SHALL use the interactive editor
   instead of the static plan, with the wall table beside/below it as today.
2. THE new-room dialog of the rooms matrix (generated/AI draft rooms) SHALL use the same editor.
3. THE room form sheet SHALL use the same editor; WHERE the sheet is narrow the canvas SHALL be
   stacked above the table.
4. ALL hosts SHALL share one component (`RoomVisualEditor`) and one model; no host SHALL contain
   drag logic.

### Requirement 14: Localization

**User Story:** As a Polish- or Russian-speaking user, I want all texts of the editor in my language.

#### Acceptance Criteria

1. EVERY user-facing text of the editor (buttons, hover hints, context menu items, live-size labels,
   error/limit messages, aria-labels, keyboard help) SHALL come from i18n keys under `rooms.visual.*`
   with pl and ru values; no hard-coded text.
2. THE pl and ru locale files SHALL have the same key set (the existing locale-parity test SHALL pass).
3. THE number format of live sizes SHALL follow the existing wall labels.

### Requirement 15: Non-functional

#### Acceptance Criteria

1. A drag SHALL update the drawing on every pointer move without perceptible lag for outlines of up to
   50 walls and 50 openings (re-render limited to the canvas, one update per animation frame).
2. THE feature SHALL NOT change any backend code, API contract or database schema.
3. THE geometry maths (snapping, gestures, outline model, viewport) SHALL be pure TypeScript
   functions without React/DOM dependencies, covered by unit and property-based tests.
4. THE feature SHALL NOT add new runtime dependencies (SVG + pointer events only).
