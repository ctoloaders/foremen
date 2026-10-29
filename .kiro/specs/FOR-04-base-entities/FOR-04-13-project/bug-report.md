# Bug report: FOR-04-13-project

Screen URL: http://localhost:3000/projects

Owning spec: `.kiro/specs/FOR-04-base-entities/FOR-04-13-project`. The `/projects` edit form is
`foremen-frontend/src/features/projects/components/ProjectFormSheet.tsx` (rendered inside a Radix
`Sheet`), which uses the shared `DatePicker` (`foremen-frontend/src/components/ui/date-picker.tsx`)
built on the shared `Calendar` (`foremen-frontend/src/components/ui/calendar.tsx`, react-day-picker
v10) + `Popover`. Both bugs are **frontend-only** and live in the shared UI primitives, so the fix
benefits every date field (currently only the project form uses `DatePicker`; the data-table
`DateFilter` uses the same `Calendar`).

Notifications: none (pure UI fix). i18n: none (reuses existing keys).

## Round 1 (2026-10-29)

- [x] 1. Calendar month-switch arrows are not clickable
  - Triage: observed — in the project edit datepicker the prev/next month arrows do nothing on
    click. Expected — clicking the arrows pages the calendar month. Layer: **frontend**. Root cause
    (confirmed in-browser via `document.elementFromPoint`): in `calendar.tsx` the `nav` is styled
    `absolute top-0 right-0` while the `month_caption` is `relative` and full-width
    (`flex justify-center pt-1 relative items-center`). The caption div therefore paints OVER the
    absolutely-positioned nav and intercepts the pointer — `elementFromPoint` at the center of the
    prev/next buttons returns the `month_caption` div, not the button (`isSelf:false`). Day-cell
    clicks work because they sit below the caption row. Fix: lay the caption + nav out so the nav
    buttons receive clicks — i.e. don't let the centered caption overlap the absolute nav. Options:
    (a) give the `nav` a higher stacking context (`relative`/`z-10`) so it sits above the caption, or
    (b) adopt the standard react-day-picker v10 layout (caption label + nav as flex siblings in the
    caption row, nav not absolutely positioned). Prefer (b) — the robust, idiomatic v10 layout —
    keeping the existing `Chevron` component slot and button styling.
  - Suspect: `foremen-frontend/src/components/ui/calendar.tsx` (`classNames.nav` / `month_caption`).
  - Fix: added `pointer-events-none` to `month_caption` and `pointer-events-auto` to `caption_label`
    in `calendar.tsx`, so the full-width centered caption (a later DOM sibling painted over the
    absolute nav) no longer swallows the prev/next clicks — they fall through to the nav buttons
    beneath, while the label stays selectable. (A z-index-only approach was rejected: Tailwind purged
    the `z-10` utility on the nav, and `pointer-events` is the robust, purge-proof fix; verified live
    that the hit-test at the arrow centers lands on the buttons.)
  - Verified (browser): on the project edit start-date picker the prev arrow paged October→September,
    the next arrow paged back to October; same on the end-date picker. The shared `Calendar` is reused
    by the data-table DateFilter, so its arrows are fixed by the same change.
  - _Requirements: FOR-04-13 R2.5 (date fields usable in the project form)_
  - _Depends on: none_

- [x] 2. Datepicker does not close after a date is selected
  - Triage: observed — selecting a day leaves the calendar popover open. Expected — the popover
    closes once a date is picked. Layer: **frontend**. Root cause: `date-picker.tsx` renders an
    uncontrolled `<Popover>` (no `open` state), and the `Calendar`'s `onSelect` only calls
    `onChange(...)` — nothing closes the popover. Fix: make the `Popover` `open`-controlled with local
    state and close it in `onSelect` after emitting the value (leave Clear behavior as-is, or close on
    clear too — clear is in the popover footer, closing on clear is reasonable). The trigger still
    toggles open normally.
  - Suspect: `foremen-frontend/src/components/ui/date-picker.tsx`.
  - Fix: made the `Popover` `open`-controlled (`useState`) in `date-picker.tsx` and close it on day
    select (`if (date) setOpen(false)` in `onSelect`) and on Clear. Month navigation keeps the popover
    open (only a day pick / clear closes it).
  - Verified (browser): selecting day 15 closed the popover and the trigger showed "15.10.2026".
  - _Requirements: FOR-04-13 R2.5_
  - _Depends on: none_

- [x] V1. Validate Round 1 in the browser
  - On `/projects`, open a project's Edit form, open the start-date picker: click the prev/next month
    arrows and confirm the displayed month changes; select a day and confirm the popover closes and
    the field shows the picked date; reopen and confirm the value prefills; repeat for the end date.
    Confirm the data-table date filter calendar still works (shared Calendar). Mark `[x]` only on a
    confirmed browser pass.
  - Result (2026-10-29, PASS): project edit start-date picker — prev/next arrows page the month
    (Oct↔Sep); selecting a day closes the popover and the field shows the ISO-derived "dd.MM.yyyy"
    (15.10.2026); end-date picker arrows work the same. `npx tsc -b` clean; `projects` suite 242
    tests pass. The shared `Calendar` fix covers the data-table `DateFilter` (same component). No test
    data persisted (closed the form without saving).
  - _Requirements: FOR-04-13 R2.5_
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1 (calendar nav layout, `calendar.tsx`) and 2 (close-on-select,
  `date-picker.tsx`) — independent files.
- **Wave 2:** V1 — browser validation; depends on 1 and 2.
