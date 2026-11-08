# Implementation Plan: FOR-05-02-rooms-visual-editor — Interactive room drawing

## Overview

Frontend only (nested repo `foremen-frontend/`, committed separately from the specs). All paths are
relative to `foremen-frontend/src/features/rooms/` unless stated otherwise. Build the pure model and
maths first (`lib/visual/*`, fully unit/property tested), then the SVG canvas and the composite editor,
then replace the static plan in the three hosts.

Test execution rule: run only the affected Vitest files (`npx vitest --run <file>`), redirect output to
`/tmp/for-05-02-visual-test.log` and read that file. Never run the whole frontend suite unless asked.
The Docker frontend serves a prebuilt image: after UI changes rebuild it with
`docker compose up -d --build --no-deps frontend` before browser checks.

## Tasks

- [x] 1. Pure model and maths (`lib/visual/`, `state/`)
  - [x] 1.1 `lib/visual/limits.ts` and `lib/visual/outline.ts`
    - Constants (`MIN_WALL` 0.10 m, `MIN_OPENING_WIDTH` 0.30 m, `SNAP_PX` 10, `DEFAULT_WALL` 2 m, door 0.90×2.10, window 1.20×1.40, history size 50), `minWallLength(wall)`; `Outline` type, `fromGeometry`, `toGeometry` (identity for untouched outlines, 1 mm rounding otherwise), `isClosed`, `wallCount`, `wallVectors`, `clampOpenings`.
    - _Requirements: 3.5, 3.6, 5.7, 8.2, 9.1, 9.3, 9.4_
    - _Depends on: none_
  - [x] 1.2 `lib/visual/viewport.ts`
    - `fitViewport` (same y flip/padding as `fitToViewport`, minimum 4 m extent), exact `worldToScreen` / `screenToWorld`, frozen-viewport helper.
    - _Requirements: 1.3, 1.4, 1.5_
    - _Depends on: none_
  - [x] 1.3 `lib/visual/snapping.ts`
    - Guide computation (extension lines, right-angle and straight-continuation lines at neighbour corners), `snapCorner` (single guide projection, two-guide intersection, free beyond `SNAP_PX`), `snapOpening` (wall start/end/centre/neighbour edges, 0.01 m steps), px→world radius conversion, `Alt` disables.
    - _Requirements: 3.3, 3.4, 4.5, 5.3_
    - _Depends on: 1.1_
  - [x] 1.4 `lib/visual/gestures/cornerWall.ts`
    - `moveCorner`, `translateWall` (normal-constrained, `Shift` free), `stretchWall` (either end), minimum-length enforcement, opening re-clamp, dropped formula keys, works for open outlines and the free end.
    - _Requirements: 3.2, 3.5, 3.6, 3.7, 3.9, 4.2, 4.3, 4.4, 4.5, 9.6_
    - _Depends on: 1.1, 1.3_
  - [x] 1.5 `lib/visual/gestures/openings.ts`
    - `materializeOffsets` (from the existing `placeOpenings` layout), `moveOpening`, `resizeOpening` (either edge, `count` > 1), no-overlap/bounds rules, `addOpeningAt` (nearest free span, `null` when no space), `removeOpening`, dropped formula keys.
    - _Requirements: 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 7.2, 7.3, 7.4, 9.2, 9.6_
    - _Depends on: 1.1, 1.3_
  - [x] 1.6 `lib/visual/gestures/draw.ts`
    - `appendDefaultWall` (first wall 2 m horizontal from the origin; next wall 2 m turned +90° from the free end), `closeByMerge` (≥ 3 walls), `closeWithWall` (≥ 2 walls), `canClose` predicates.
    - _Requirements: 8.2, 8.3, 8.5, 8.6, 8.7_
    - _Depends on: 1.1_
  - [x] 1.7 `lib/visual/projection.ts`
    - `toEditorWalls(outline)` and `applyTableEdit(outline, editedWalls, autoClose)` on exact specs with the pose (first corner + wall-0 heading) preserved; closure detection; reuse `wallsToVertices` / `closeOutline` / `evalField`; formula bookkeeping.
    - _Requirements: 9.5, 10.1, 10.2, 10.3, 10.5_
    - _Depends on: 1.1_
  - [x] 1.8 `lib/visual/liveSizes.ts`
    - `liveSizesFor(gesture, before, after)` returning descriptors for corner (two lengths + interior angle), wall translate (distance + neighbours), stretch (length), opening move (from start/from end) and resize (width/total span).
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
    - _Depends on: 1.1_
  - [x] 1.9 `state/useOutlineHistory.ts`
    - Reducer/hook: `present`, `past`, `future` (max 50), live `preview` outside history, `commit` with coalesce key, `undo`, `redo`, `reset`.
    - _Requirements: 11.2, 11.3, 11.4_
    - _Depends on: 1.1_

- [x] 2. Tests of the pure layer
  - [x] 2.1 Outline / viewport / projection tests
    - Properties: geometry round trip and identity when untouched (1), viewport inverse (7), projection consistency (8); golden fixtures: room 1697, rectangle and L-shape templates, AI draft with non-integer angles and offset-less openings, clockwise polygon, `count` > 1 (11).
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 10.1, 10.2, 1.4_
    - _Depends on: 1.1, 1.2, 1.7_
  - [x] 2.2 Snapping, corner, wall tests
    - Properties: corner locality (2), wall translation invariants (3), stretch invariants (4), snap sanity and idempotence (6); minimum-length cases; open outline free end.
    - _Requirements: 3.2, 3.3, 3.6, 4.2, 4.3, 4.4, 4.5_
    - _Depends on: 1.3, 1.4_
  - [x] 2.3 Opening, draw, live-size, history tests
    - Properties: opening bounds (5), default chain after k presses of "+" (10), undo/redo identity and cancelled gesture (9); live-size descriptors; close by merge / with wall; add-opening-at without free space.
    - _Requirements: 5.2, 5.4, 5.5, 6.1, 6.4, 7.3, 8.2, 8.3, 8.5, 8.6, 11.3_
    - _Depends on: 1.5, 1.6, 1.8, 1.9_

- [x] 3. Canvas and composite editor
  - [x] 3.1 Extract `PlanLayers` from `components/RoomPlanPreview.tsx`
    - Move the drawing code (outline, openings, labels, area, legend, highlights) into a shared `components/visual/PlanLayers.tsx`; `RoomPlanPreview` renders it unchanged (existing tests stay green).
    - _Requirements: 1.1, 1.6_
    - _Depends on: none_
  - [x] 3.2 i18n keys `rooms.visual.*` in `src/locales/pl.json` and `ru.json`
    - All keys of the design table with pl and ru values; locale-parity test stays green.
    - _Requirements: 14.1, 14.2, 14.3_
    - _Depends on: none_
  - [x] 3.3 Presentational components (`components/visual/`)
    - `CornerHandle`, `WallHit`, `OpeningHandle` (with edge grips), `LiveDimension`, `AddWallButton` ("+" top right), `PlanContextMenu` (portal, keyboard operable); hover/selected visuals, hit areas ≥ 24 px, aria-labels from i18n.
    - _Requirements: 3.1, 4.1, 5.1, 6.5, 7.1, 7.5, 8.1, 8.4, 12.1, 12.4_
    - _Depends on: 3.2_
  - [x] 3.4 `components/visual/PlanCanvas.tsx`
    - SVG + pointer-capture protocol (start snapshot, rAF-throttled preview, commit on release, Escape/cancel restore), frozen viewport during gestures, hover/selection, guides, live sizes, `Alt`/`Shift` modifiers, context menu wiring, free-end merge on release, keyboard nudges and Delete, `aria-describedby` help, read-only mode renders layers only.
    - _Requirements: 1.2, 1.3, 1.6, 3.1, 3.2, 3.3, 3.8, 3.9, 4.1, 4.2, 4.4, 5.1, 5.2, 5.5, 6.1, 6.2, 6.3, 6.4, 7.1, 7.2, 7.4, 7.5, 7.6, 8.4, 8.5, 12.1, 12.2, 12.3_
    - _Depends on: 1.2, 1.3, 1.4, 1.5, 1.6, 1.8, 3.1, 3.3_
  - [x] 3.5 `RoomWallsEditor` selection support
    - Add `selectedWall` / `onSelectWall` props (row highlight + scroll into view), keep `onFocusChange`; no behaviour change when the props are absent.
    - _Requirements: 4.6_
    - _Depends on: none_
  - [x] 3.6 `components/visual/RoomVisualEditor.tsx`
    - Composite: history, selection, toolbar (Undo, Redo, Templates, Close outline), "+" visibility rules, template application (closed outline, replace warning), table projection and `applyTableEdit`, `autoClose` defaults (true closed / false open), keyboard shortcuts, `onChange` payload (`outline`, `geometry`, `editorWalls`, `autoClose`, `dirty`, `droppedFormulaKeys`, `saveBlock`).
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 6.6, 8.1, 8.6, 8.7, 8.8, 9.8, 10.1, 10.2, 10.3, 10.4, 10.5, 11.1, 11.2, 11.4, 13.4_
    - _Depends on: 1.7, 1.9, 3.4, 3.5_

- [x] 4. Hosts
  - [x] 4.1 `pages/RoomEditorPage.tsx`
    - Replace local `walls/autoClose/preview` state and the `RoomPlanPreview` + `RoomWallsEditor` pair by `RoomVisualEditor`; save sends the stored geometry when not dirty, merged formulas minus dropped keys; live metrics from the outline; read-only when `!canEdit`.
    - _Requirements: 9.3, 9.4, 9.6, 9.8, 13.1_
    - _Depends on: 3.6_
  - [x] 4.2 `components/workspace/NewRoomEditorDialog.tsx`
    - Use `RoomVisualEditor`; `applyGeometryToDraft` on save with the exact-geometry round trip for AI drafts.
    - _Requirements: 9.7, 13.2_
    - _Depends on: 3.6_
  - [x] 4.3 `components/RoomFormSheet.tsx`
    - Use `RoomVisualEditor` stacked (canvas above table) in the sheet; create and edit modes.
    - _Requirements: 13.3, 13.4_
    - _Depends on: 3.6_

- [x] 5. Component and regression tests
  - [x] 5.1 `PlanCanvas` tests
    - Hover highlight, corner drag with and without snapping, wall translate/stretch, opening move/resize, context menu add/delete, Escape cancel, frozen viewport, keyboard nudge, read-only (SVG matrix and pointer capture mocked).
    - _Requirements: 1.2, 1.3, 1.6, 3.1, 3.2, 3.3, 4.2, 4.4, 5.2, 5.5, 7.1, 7.2, 7.4, 12.2_
    - _Depends on: 3.4_
  - [x] 5.2 `RoomVisualEditor` tests
    - Scratch flow ("+" chain, free end, close by merge / action), template flow, table ↔ canvas sync, undo/redo, no-op save keeps stored geometry, formula dropping, read-only.
    - _Requirements: 2.2, 2.4, 8.1, 8.2, 8.3, 8.5, 8.6, 8.7, 9.3, 9.6, 10.1, 10.2, 11.1, 11.2_
    - _Depends on: 3.6_
  - [x] 5.3 Host and existing-test updates
    - Update/extend `RoomFormSheet.test`, room editor page tests, `NewRoomEditorDialog` tests and `rooms.i18n.test`; confirm `RoomWallsEditor.test` and `RoomPlanPreview` tests stay green.
    - _Requirements: 13.1, 13.2, 13.3, 14.2_
    - _Depends on: 4.1, 4.2, 4.3_

- [x] 6. Verification
  - [x] 6.1 Checkpoint: type-check, lint and rooms tests
    - `npx tsc --noEmit -p tsconfig.app.json`, eslint on changed files, `npx vitest --run src/features/rooms src/components/ui` (log to `/tmp/for-05-02-visual-test.log`).
    - _Requirements: 15.2, 15.3, 15.4_
    - _Depends on: 2.1, 2.2, 2.3, 5.1, 5.2, 5.3_
  - [x] 6.2 Browser verification on the Docker stack
    - Rebuild the frontend image; execute the UI scenarios of `test-cases.md` on `http://localhost:3000` (room 1701, an AI-generated draft, a from-scratch room); write the MD report with tables.
    - _Requirements: 1–13_
    - _Depends on: 6.1_
  - [x] 6.3 Author test-cases.md
    - Created `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD report template). UI spec: browser-engine scenarios; results are MD reports with tables.
    - _Requirements: 1–13_
    - _Depends on: 6.1_
  - [x] 6.4 Final checkpoint
    - All tests green, report from 6.2 attached, frontend changes committed in the `foremen-frontend/` repo and spec files in the root repo (on request).
    - _Requirements: 1–15_
    - _Depends on: 6.2, 6.3_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "3.1", "3.2", "3.5"] },
    { "id": 1, "tasks": ["1.3", "1.6", "1.7", "1.8", "1.9", "3.3"] },
    { "id": 2, "tasks": ["1.4", "1.5", "2.1"] },
    { "id": 3, "tasks": ["2.2", "2.3", "3.4"] },
    { "id": 4, "tasks": ["3.6", "5.1"] },
    { "id": 5, "tasks": ["4.1", "4.2", "4.3", "5.2"] },
    { "id": 6, "tasks": ["5.3"] },
    { "id": 7, "tasks": ["6.1"] },
    { "id": 8, "tasks": ["6.2", "6.3"] },
    { "id": 9, "tasks": ["6.4"] }
  ]
}
```
