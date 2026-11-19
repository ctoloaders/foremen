# Bug report: FOR-05-13-collaborative-estimate

Screen URL: https://dev.foremen.eu/projects/5/estimate (reproduce locally at `http://localhost:3000/projects/{id}/estimate`).

## Round 1 (2026-11-19)

- [~] 1. The estimate constantly shows "unsaved changes" (when another user made changes)
  - Triage: the dirty indicator is derived from the shared draft instead of the own ops. Expected: only the current user's own uncommitted edits raise it; remote ops and remote `SAVED` do not.
  - Layer: frontend. Suspect: `features/estimate/collab/useEstimateCollaboration.ts`, `EstimateTab.tsx`, `EstimateHeader.tsx`.
  - _Requirements: FOR-05-13 staged ops, SAVED broadcast_
  - _Depends on: none_
- [ ] V1. Validate with two users: B stages and saves, A sees no indicator; A's own edit still raises it
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1.
- **Wave 2:** V1.

## Round 1 — validation results (2026-11-19)

Implemented: other users' edits are flagged `foreign`; indicator and Discard count only own edits; others' edits are no longer written to local storage. Vitest estimate suites pass. Not verified with two real users, so the item stays `[~]`.
