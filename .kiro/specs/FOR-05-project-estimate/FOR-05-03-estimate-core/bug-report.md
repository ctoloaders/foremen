# Bug report: FOR-05-03-estimate-core

Screen URL: https://dev.foremen.eu/projects/5/estimate (estimate matrix, `EstimateTab`; reproduce locally at `http://localhost:3000/projects/{id}/estimate`).

## Round 1 (2026-11-19)

- [~] 1. Speed up loading of consumption norms when clicking a cell (`POST /api/estimates/project/5/preview` with a growing `edits` list)
  - Triage: every cell click sends the whole staged `edits` list to the read-only preview and the matrix with consumption norms is recomputed from scratch. Expected: the click feels instant.
  - Layer: both. Suspect: `EstimateAssignmentService` preview path, `mutation-hooks.ts`, `EstimateTab.tsx`.
  - _Requirements: FOR-05-03 preview (R15.6)_
  - _Depends on: none_
- [ ] V1. Validate Round 1 in browser (latency before/after, matrix identical, Save persists the same data)
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1.
- **Wave 2:** V1.

## Round 1 — validation results (2026-11-19)

Implemented: client collapses redundant edits (`collapseStagedEdits.ts`), aborts superseded previews (`useStagedPreview.ts`), server loads catalog data once per request (`EstimateAssignmentService`). Vitest estimate suites 368 pass; `EstimateAssignmentServiceIntegrationTest` 35/35. Latency was not measured in the browser, so the item stays `[~]`.
