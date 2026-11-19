# Additional tasks: FOR-05-04-estimate-packages-changes (package applied flag and package pricing)

Source: bug-report Round 1, item 1 (2026-11-19). English per the spec-doc standard; `test-cases.md` in Russian.
Screen URL: `http://localhost:3000/projects/{id}/estimate`.

## Scope and decisions
- Project gets `packageApplied` (boolean, default false) and `offerPackageId` (nullable FK to offer packages).
- With a package: volumes of works covered by the package come from the package; total = package price per m2 x apartment area + extra works beyond the package at the price list. Without a package: price-list calculation only.
- Applying a package sets the flag in one indivisible operation. "Remove package" (already implemented in the estimate UI as `REMOVE_PACKAGE`) clears the flag.
- Assumptions to confirm in design: "apartment area" is the project's total floor area from rooms; works "beyond the package" are all works the package does not cover.
- Notifications: none. i18n: no new translated entity fields (package names are reused). Frontend keys under `estimate.package.*` (applied badge, package price per m2, area, extra works subtotal) in `pl.json` and `ru.json`.
- Totals are net; VAT comes from the project VAT rate at offer level.

## Tasks
- [x] 1. Migration and entity: `projects.package_applied`, `projects.offer_package_id` (next free changeset number, registered last). Project DTO and mapper.
  - _Depends on: none_
  - DONE: changeset 260, ProjectEntity.packageApplied/offerPackage, ProjectReadDto/ProjectListDto.
- [x] 2. Pricing: package total in the estimate total service (formula above) with unit and property tests.
  - _Depends on: 1_
  - DONE: EstimatePackagePricing + EstimatePackagePricingService, unit and property tests.
- [x] 3. Apply and remove package set and clear the flag atomically with the matrix change; preview reflects the flag.
  - _Depends on: 1, 2_
  - DONE: syncProjectPackageFlag in EstimateAssignmentService.doApplyAssignments (same tx; preview reflects it); integration test.
- [x] 4. Matrix DTO exposes flag, package, package subtotal and extras subtotal; offer preparation uses the same totals.
  - _Depends on: 2_
  - DONE: EstimateMatrixDto.packageSummary; OfferService uses the same totals.
- [x] 5. Frontend: package state and split totals in the estimate header (PL/RU keys).
  - _Depends on: 4_
  - DONE: EstimateHeader package block, estimate.package.* PL/RU keys.
- [x] 6. Tests for 2 to 4 (affected classes only) and frontend tests for 5.
  - _Depends on: 3, 5_
  - DONE: EstimatePackagePricingTest, EstimatePackagePricingServiceTest, integration + EstimateHeader tests.
- [x] 7. Author test-cases.md
  - Per `.kiro/steering/test-cases.md`; repeatability via teardown (remove package).
  - _Depends on: 6_
  - DONE: Part B appended to test-cases.md.
- [ ] 8. Checkpoint: browser validation.
  - _Depends on: 7_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1"] },
    { "id": 1, "tasks": ["2"] },
    { "id": 2, "tasks": ["3", "4"] },
    { "id": 3, "tasks": ["5"] },
    { "id": 4, "tasks": ["6"] },
    { "id": 5, "tasks": ["7"] },
    { "id": 6, "tasks": ["8"] }
  ]
}
```
