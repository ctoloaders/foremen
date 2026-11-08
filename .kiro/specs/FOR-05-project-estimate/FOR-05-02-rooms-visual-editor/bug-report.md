# Bug report: FOR-05-02-rooms-visual-editor

## Round 1 (2026-11-08)

Screen URL: `https://dev.foremen.eu/projects/2/rooms/25` (room editor → visual plan canvas + wall table). Local reproduction: `http://localhost:3000/projects/2/rooms/3` (room 25 does not exist in the local DB).

Owning spec: all items belong to this spec (canvas + its sync with the wall table, `RoomVisualEditor` / `PlanCanvas` / `RoomWallsEditor`). Items 3 and 5 are feature-sized, see the flags below.

- [x] 1. When I click a wall, the focus jumps to the row in the wall table; it must stay as it was without a shift (otherwise the room picture disappears from view)
  - Triage: observed — selecting a wall (click on the canvas, or adding/selecting an opening) calls `scrollIntoView({ block: 'nearest' })` on the table row (`RoomWallsEditor.tsx`, effect on `selectedWall`), which scrolls the WHOLE PAGE down to the table, so the canvas leaves the viewport. Reproduced locally: after adding an opening the page is scrolled to the table and only the bottom part of the canvas is visible. Expected — selecting on the canvas highlights the row (Requirement 4.6) but never moves the page scroll.
  - Fix: remove the page-level scroll. Keep the row highlight (`data-selected`, ring). If the table sits in its own scroll container, scroll only that container (`scrollTop`), never `scrollIntoView`; otherwise no scrolling at all. The `pointerPressed` workaround becomes unnecessary and is removed. Requirement 4.6 / design § Table sync ("scrolls/highlights") are amended to "highlights".
  - Layer: frontend. Suspect: `foremen-frontend/src/features/rooms/components/RoomWallsEditor.tsx` (the `selectedWall` effect and `pointerPressed`); existing tests of that scroll behaviour must be updated.
  - _Requirements: 4.6, 10.1_
  - _Depends on: none_
- [x] 2. Adding doors/windows does not work
  - Closed by the user (2026-11-08): adding works. Triage had found that both existing paths (right-click menu on a wall, "+" in the table row) work locally; no code change is made for this item. Observation kept for the record only: the table "+" creates an empty opening (blank width/height) that is drawn across the whole wall; it can be raised as a separate bug if it bothers.
  - _Requirements: 5.7, 7.1, 7.2, 7.3_
  - _Depends on: none_
- [x] 3. Delete a door, window, wall and corner in the visual editor (feature-sized, flagged)
  - Triage: observed — only openings can be deleted (Delete/Backspace and the context menu, `PlanCanvas.onKeyDown`); walls and corners cannot. Expected, per the request:
    - Delete/Backspace on a selected opening, wall or corner, AND a trash icon in the top-left corner of the editor while an element is selected.
    - Deletion only after explicit confirmation (dialog) for all four element kinds.
    - Corner: the corner and its two attached walls are removed and the neighbours are joined (polygon ABCD, delete B → triangle ACD; the new wall A–C replaces AB and BC; openings of the removed walls are deleted, the confirmation says so). Not allowed below 3 corners of a closed outline (hint instead).
    - Wall: the wall is removed, the neighbouring walls' free ends STAY where they are; the outline becomes open (a gap) until the user explicitly presses "Close outline" (existing `closeWithWall`). Openings of the wall go with it.
  - Why flagged as feature-sized: new destructive gestures with a confirmation dialog, a new "delete corner/wall" pure-function set (`lib/visual/gestures`), rotating a closed outline into an open chain, and open-outline edge cases (deleting a middle wall of an already open chain would split it into two pieces — proposed rule: allowed only for the first/last wall of an open chain, otherwise a hint). Proposed to add a requirements/design amendment (new Requirement "Deleting elements") to this spec; recorded here so nothing is dropped.
  - Fix (proposed):
    - 3.1 Pure functions `deleteCorner(outline, i)` and `deleteWall(outline, i)` + unit tests (formula key cleanup like `removeOpening`, closed → open chain rotation, minimum corner count, `dirty` / `droppedFormulaKeys`).
    - 3.2 UI: a confirm dialog (AlertDialog, focus returns to the canvas), the trash button in the top-left of the canvas wrapper shown only with a selection, `Delete`/`Backspace` on the canvas opens the dialog instead of deleting at once (openings included; the existing instant opening delete and the context-menu "Delete opening" go through the same confirmation), one undo step per deletion.
  - Layer: frontend. Suspect: `PlanCanvas.tsx` (`onKeyDown`, wrapper), new `lib/visual/gestures/delete.ts`, `RoomVisualEditor.tsx` (`closeOutline` stays as is), new confirm dialog component.
  - i18n (PL/RU, `rooms.visual.*`): trash tooltip/aria, confirm titles + bodies for opening / wall / corner (with "openings will be removed"), confirm/cancel, "cannot delete: minimum 3 corners". No notifications.
  - _Requirements: 3, 4, 5, 7.4, 8, 11, 12 (amendment: new "Deleting elements" requirement proposed)_
  - _Depends on: 4, 5_
- [x] 4. Show the door/window dimensions on the editor — only when the opening is clicked (selected)
  - Triage: observed — an opening is drawn as a coloured segment with no size; the width is only visible in the table or during a drag. Expected (clarified by the user) — the size is shown ONLY for the clicked (selected) opening, not for all openings: width × height in m (2 decimals, like the wall labels; `count` × width and the total span when `count` > 1), placed on the inner side of the wall next to the opening, and it disappears when the selection is cleared.
  - Fix: `PlanCanvas` renders a label for the selected opening only (the selection is already known there), positioned from `placeOpenings`; not drawn in the read-only plan. The label is non-interactive (`pointerEvents="none"`) so it never blocks the handles, and is hidden while an opening gesture runs (the live size takes over, Requirement 6.4).
  - Layer: frontend. Suspect: `PlanCanvas.tsx`, `planGeometry.ts` (`placeOpenings`), `OpeningHandle.tsx`.
  - i18n (PL/RU): none if it is a pure number format `0.90 × 2.10`; add a `rooms.visual.*` key only if the unit is part of the text. No notifications.
  - _Requirements: 5.1, 6.4, 6.5, 14_
  - _Depends on: none_
- [x] 5. Add a new corner on a wall (split a wall in two) (feature-sized, flagged)
  - Triage: new capability — corners can only be moved, never added, so a niche or a bulge cannot be drawn on the canvas. Expected: the user first picks a point on a wall; the wall is divided into two walls at that point and the new corner behaves like any other corner (drag, select, delete, nudge). Example from the request: add four points on one wall and pull the middle part of that wall (a normal wall translate) to make a niche.
  - Fix (proposed):
    - 5.1 Pure function `splitWall(outline, wall, s)` in `lib/visual/gestures/split.ts` + unit tests: inserts the vertex at distance `s` from the wall start; the old wall is replaced by two walls; the gaps (`wallGap`, `finishGap`) are copied to both parts; openings fully before `s` stay on the first part, openings fully after move to the second part with `offset − s`; a point inside an opening is not allowed (the marker snaps to the nearest opening edge). Works for closed and open outlines (a free end is untouched). Geometry formula keys of the split wall and every later wall are dropped (indices shift), like `removeOpening`. Not allowed closer than the minimum wall length (Requirement 3.6) to either end of the wall.
    - 5.2 UI: toolbar toggle "Add corner"; while it is on, hovering a wall shows a marker (snapped to the 0.01 m step, the wall centre and opening edges) and a click inserts the corner; the new corner becomes selected; the mode stays on so several points can be added in a row; `Escape` or the button leaves the mode. The wall context menu gets "Add corner here" as a second entry point. One undo step per inserted corner; the table gets a new row (projection of the outline).
  - Layer: frontend. Suspect: new `lib/visual/gestures/split.ts`, `PlanCanvas.tsx` (mode, hover marker, menu entry), `RoomVisualEditor.tsx` (toolbar), `outline.ts`.
  - i18n (PL/RU, `rooms.visual.*`): toolbar button, hint while the mode is on, context-menu item, "no room for a corner here" tooltip. No notifications.
  - _Requirements: 3, 4, 7, 8, 10, 11, 12 (amendment: new "Adding corners" requirement proposed)_
  - _Depends on: none_
- [x] V1. Validate Round 1 in browser
  - Rebuild the frontend, open the room editor, apply a template: (1) click each wall — the page does not scroll, the row is highlighted; (2) closed, no check; (3) delete opening / wall / corner by Delete, Backspace and the trash button with confirmation; the ABCD → ACD case; wall deletion leaves the gap and "Close outline" closes it; undo restores; (4) the size label appears only for the clicked door/window and disappears on deselect; (5) add corners on a wall (hover marker, click, several in a row, Escape), pull the middle wall to make a niche, undo each step, openings stay on the right part of the split wall.
  - _Depends on: 1, 3, 4, 5_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1 (`RoomWallsEditor`), 4 (`PlanCanvas` selected-opening label), 3.1 (new pure `delete.ts`), 5.1 (new pure `split.ts`) — independent files.
- **Wave 2:** 5.2 — UI for adding corners (`PlanCanvas`, `RoomVisualEditor` toolbar), uses 5.1; after 4 because both edit `PlanCanvas`.
- **Wave 3:** 3.2 — UI for deleting elements (`PlanCanvas`, `RoomVisualEditor`), uses 3.1; after 5.2 for the same file reasons.
- **Wave 4:** V1 — browser validation.

## Round 1 — validation results (2026-11-08)

Validated on the local Docker stack (`http://localhost:3000/projects/2/rooms/3`, Rectangle template applied in the draft, nothing saved). Dev (`dev.foremen.eu`) is not redeployed yet.

- **#1 Wall click does not move the page — fixed.** `scrollY` was 533 before and after clicking a wall; the row is highlighted. The page-level `scrollIntoView` (and the `pointerPressed` workaround) is removed from `RoomWallsEditor`.
- **#2 — closed by the user (no change).**
- **#3 Delete opening / wall / corner with confirmation — fixed.** `Delete` / `Backspace` and the trash button (top-left of the canvas, only with a selection) open a confirmation dialog; nothing changes before "Delete". Corner delete: 8 → 7 corners (neighbours joined). Wall delete: closed → open chain (7 corners kept, 6 walls), the closing-wall checkbox switches off, "Close outline" appears and restores 7 walls with the checkbox on; undo works. Blocked cases show an explanation instead of the confirm button (triangle corner; middle wall of an open chain).
- **#4 Opening size only on click — fixed.** After adding a window and clicking it the plan shows `1.20 × 1.40 м`; after `Escape` (deselect) the label is gone. `count` > 1 shows `N × w × h`.
- **#5 Add corner — fixed.** Toolbar toggle "Add corner" (hint line, crosshair cursor, marker follows the pointer near a wall); four clicks on the top wall turned 4 walls into 8, the table got the new rows, the page did not scroll. Pulling the middle part of the split wall made a recess; the neighbouring split walls stay attached to it (they are slanted, because the translate gesture keeps the attached walls connected and the points of a straight wall are collinear — for a rectangular niche drag the inner two corners instead, or ask for a dedicated "niche" helper as a follow-up). The wall context menu also has "Add corner here". `Escape` leaves the mode (also from the toolbar button).
- Automated: `splitDelete.test.ts` (22 unit tests of `splitWall` / `snapSplit` / `deleteCorner` / `deleteWall`), new `PlanCanvas` tests (delete dialog, trash button, add-corner mode, opening size label), updated `RoomWallsEditor` scroll test; `src/features/rooms` suite 1232 passed, `tsc -b` and eslint clean.
- Files (frontend repo only): `RoomWallsEditor.tsx`, `visual/PlanCanvas.tsx`, `visual/PlanContextMenu.tsx`, `visual/RoomVisualEditor.tsx`, `lib/visual/gestures/{split,delete,formulaKeys}.ts`, PL/RU `rooms.visual.*` keys (`addCorner*`, `openingSize*`, `delete.*`, `menu.addCorner`, `menu.noCornerSpace`).
