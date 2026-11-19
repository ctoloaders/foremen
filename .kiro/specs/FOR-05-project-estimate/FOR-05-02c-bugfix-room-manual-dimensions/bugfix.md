# Bugfix Requirements Document

## Introduction

In the rooms matrix (`/projects/{id}/rooms`), a room that has saved polygon geometry silently loses the manually entered "missing wall" (`wallGap`) and "missing finish" (`finishGap`) lengths. Reproduced on project 1531, room 1701 ("Przedpokój", one DOOR 0.58 × 2.0 in geometry): the save request `POST /api/rooms/project/1531/save?force=false` carries `"wallGap":2,"finishGap":3` and `"wallArea":12.93`, the backend answers successfully, but the database keeps `wall_gap = 0`, `finish_gap = 0`, `wall_area = 26.43`.

Cause: on the geometry path the backend (`RoomService.applyCalculatedMetrics`) overwrites `wallGap`/`finishGap` with the sum of the per-wall gaps in the geometry (all `null`, so 0) and recomputes `wallArea` from them. Unlike the five area/perimeter metrics, these two values have no `MANUAL`/`CALCULATED` source and no per-field override, so a root-level value can never win over the geometry.

Two related display gaps belong to the same manual-vs-geometry rule:

- For a room with geometry the matrix does not show which wall lengths the perimeter consists of (the wall-area formula popover already exists).
- Door/window height and width stay 0 for a room with geometry (room 1701 stores `door_height = 0`, `door_width = 0` although the geometry holds one door 0.58 × 2.0), and nothing shows the opening size.

Rule applied to all three: a value entered manually in the room's root properties takes priority over the value derived from the geometry, and is stored flagged as manual.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a room with saved geometry is saved with a root-level `wallGap` and/or `finishGap` that differs from the geometry sums (for example `wallGap = 2`, `finishGap = 3` with all per-wall gaps `null`) THEN the system discards the submitted values and persists the geometry sums (`wall_gap = 0`, `finish_gap = 0`), and returns a success response.

1.2 WHEN such a room is saved with a manually entered `wallGap`/`finishGap` THEN the system recomputes `wallArea` from the geometry sums instead of the submitted values (room 1701: stored 26.43, while the screen showed 12.93 = (10.22 − 3 − 2) × 2.7 − 1.16), so the value shown before the save differs from the value after the reload.

1.3 WHEN `wallGap`/`finishGap` are stored THEN the system keeps no manual/calculated flag for them, so a manually entered value cannot be told apart from a geometry-derived one and cannot be preserved on later saves.

1.4 WHEN a room has geometry and the matrix shows its perimeter THEN the system shows only the number and offers no breakdown of the wall lengths the perimeter is the sum of.

1.5 WHEN a room has geometry containing openings THEN the system leaves `doorHeight`, `doorWidth`, `windowHeight`, `windowWidth` at the submitted or stored value (0 for room 1701) instead of the size of the opening, so the matrix shows 0 for a room with one 0.58 × 2.0 door.

1.6 WHEN `doorHeight`, `doorWidth`, `windowHeight` or `windowWidth` is entered manually for a room with geometry THEN the system does not flag it as manual, so a later geometry recalculation or save can replace it.

### Expected Behavior (Correct)

2.1 WHEN a room with saved geometry is saved with a root-level `wallGap` and/or `finishGap` that differs from the geometry sums THEN the system SHALL persist the submitted value, stamp it `MANUAL`, and return it unchanged in the response and on the next read.

2.2 WHEN a room has a `MANUAL` root `wallGap`/`finishGap` THEN the system SHALL compute `wallArea` (and its formula explanation) from the manual values, so the stored `wallArea` equals the value shown on screen before the save.

2.3 WHEN a room has no manual `wallGap`/`finishGap` (source absent or `CALCULATED`) THEN the system SHALL set the value to the sum of the per-wall gaps in the geometry and stamp it `CALCULATED`. The backend SHALL expose both sources on the room read/write models, accept them on create/update requests, and keep existing rows consistent (existing values with all-null per-wall gaps and value 0 become `CALCULATED`).

2.4 WHEN a room has geometry THEN the system SHALL show, on the perimeter cell of the matrix, the lengths the perimeter consists of in the same formula-popover style as the wall-area formula (wall lengths as formula elements, each with its length, and the sum as result). A wall length entered as a formula (`dimensionFormulas["geometry.walls[i].length"]`) SHALL be shown as that formula.

2.5 WHEN a room has geometry and, for a door (or window) type, the total count of its openings is exactly 1 THEN the system SHALL show that opening's height and width in the `doorHeight`/`doorWidth` (or `windowHeight`/`windowWidth`) cells, calculated from the geometry.

2.6 WHEN a room has geometry and the total count of the openings of a type is greater than 1 THEN the system SHALL show a dash (no value) in that type's height and width cells, and SHALL NOT use a sum or average as a size. The area (`doorArea`/`windowArea`) SHALL keep being computed from the individual openings.

2.7 WHEN `doorHeight`, `doorWidth`, `windowHeight` or `windowWidth` is entered manually for a room with geometry THEN the system SHALL persist the value, stamp it `MANUAL` (flag stored per field), and show it instead of the geometry-derived size or dash, and a later save SHALL NOT overwrite it with the geometry value. Clearing the manual value SHALL return the field to the geometry-derived value.

2.8 WHEN `doorArea`/`windowArea` is `CALCULATED` for a room with a manual door/window size THEN the system SHALL keep deriving the area from the geometry openings, unless the area itself is manually overridden (existing Round 10 rule).

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a room has no geometry (flat matrix room) THEN the system SHALL CONTINUE TO keep the submitted `wallGap`/`finishGap` and door/window dimensions and compute `wallArea = max(0, (perimeter − finishGap − wallGap) × ceilingHeight − doorArea − windowArea)` as before.

3.2 WHEN a room with geometry has per-wall `wallGap`/`finishGap` values and no manual root value THEN the system SHALL CONTINUE TO use their sum as the room's `wallGap`/`finishGap`.

3.3 WHEN a per-wall gap formula (`geometry.walls[i].wallGap|finishGap`) is submitted THEN the system SHALL CONTINUE TO evaluate it and write it into the geometry.

3.4 WHEN `floorArea`, `wallArea`, `perimeter`, `doorArea` or `windowArea` carries `MANUAL` or `CALCULATED` THEN the system SHALL CONTINUE TO apply the existing per-field override rule (explicit value with `MANUAL` source is kept; otherwise recomputed from geometry).

3.5 WHEN a room with geometry has no manual values and exactly one opening of a type THEN `doorCount`/`windowCount`, `doorArea`, `windowArea`, `floorArea` and `perimeter` SHALL CONTINUE TO equal the geometry-derived values.

3.6 WHEN the existing "Obliczone/Calculated" badge popover of `wallArea`, `doorArea`, `windowArea` is opened THEN the system SHALL CONTINUE TO show the formula with the used value under every element.

3.7 WHEN rooms are created or updated through room import (AI import) or the visual editor THEN the system SHALL CONTINUE TO behave as before when no manual root values are present.

3.8 WHEN a value outside `[0, 9999999999.99]` is submitted for `wallGap`/`finishGap` or a door/window size THEN the system SHALL CONTINUE TO reject it with the existing metric-range error.

## Bug Condition

```pascal
FUNCTION isBugCondition(X)
  INPUT: X of type RoomSaveInput (X.geometry, X.wallGap, X.finishGap,
         X.doorHeight, X.doorWidth, X.windowHeight, X.windowWidth, X.sources)
  OUTPUT: boolean

  hasGeo ← X.geometry ≠ null AND size(X.geometry.vertices) > 0
  RETURN hasGeo AND (
       (X.wallGap   ≠ null AND X.wallGap   ≠ Σ wall.wallGap   over X.geometry.walls)
    OR (X.finishGap ≠ null AND X.finishGap ≠ Σ wall.finishGap over X.geometry.walls)
    OR X.geometry contains openings                       // sizes / perimeter breakdown not derived or shown
    OR any of door/window height/width has a manual value )
END FUNCTION
```

```pascal
// Property: Fix Checking
FOR ALL X WHERE isBugCondition(X) DO
  result ← save'(X)
  ASSERT result.wallGap   = X.wallGap   AND result.wallGapSource   = MANUAL    // when X.wallGap supplied
  ASSERT result.finishGap = X.finishGap AND result.finishGapSource = MANUAL    // when X.finishGap supplied
  ASSERT result.wallArea  = max(0, (result.perimeter - result.finishGap - result.wallGap)
                                   * X.ceilingHeight - result.doorArea - result.windowArea)
         WHEN wallAreaSource = CALCULATED
  FOR t IN {DOOR, WINDOW}:
    IF manual size(t) supplied THEN result.size(t) = manual value AND source = MANUAL
    ELSE IF Σ count of openings of type t = 1 THEN result.size(t) = that opening's height/width
    ELSE result.size(t) = null                                                  // dash
  ASSERT explanation(perimeter) lists every wall length and their sum
END FOR
```

```pascal
// Property: Preservation Checking
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT save(X) = save'(X)
END FOR
```
