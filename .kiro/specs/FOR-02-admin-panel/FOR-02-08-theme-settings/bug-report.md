# Bug report: FOR-02-08-theme-settings

Screen URL: http://localhost:3000/settings/appearance (reproduce on https://dev.foremen.eu/settings/appearance).

## Round 1 (2026-11-19)

- [x] 1. Appearance: applying the font size does not work
  - Triage: `applyFontSize` only set `--font-size-base`, consumed by `body`; rem-based sizes follow the `html` font size. Layer: frontend. Files: `lib/theme-applicator.ts`, `index.css`, `index.html`.
  - _Requirements: FOR-02-08 font size selection_
  - _Depends on: none_
- [x] V1. Validate Round 1 in browser
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1.
- **Wave 2:** V1.

## Round 1 — validation results (2026-11-19)

`/settings/appearance` on the local stack: Small gives a root font size of 13.71px, Very large 20.57px, Default 16px; the saved size persists after reload. Result: 1 fixed / 0 failed.
