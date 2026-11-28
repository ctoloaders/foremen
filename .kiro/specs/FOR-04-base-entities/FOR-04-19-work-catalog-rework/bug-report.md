# Bug report: FOR-04-19-work-catalog-rework

## Round 1 (2026-11-27)

Screen: work catalog (`/catalog/works`, `WorkCatalogList.tsx`) as WORKER.

- [x] 1. Remove the work price from the work catalog for the WORKER role (and everywhere a WORKER can see it, including the estimate)
  - Triage: a WORKER sees work prices: the catalog's labour price column (`costCell.labourPrice`), the work
    item's `internalPrice`, `/api/work-prices` (`netPrice`), and in the estimate the per-unit work price, the
    labour value of every cell, the works subtotals/totals, the package summary and the generic estimate /
    estimate-line totals. Expected: no work price anywhere for the WORKER role. Layer: both.
  - Fix (as decided by the user): the backend clears the fields from the DTO for the WORKER role through the
    `getAdminOnlyFields()` override (the inherited masking): `WorkItemService` → `internalPrice`;
    `WorkPriceService` → `netPrice`; `EstimateService` → `totalNet`, `totalVat`, `totalGross`;
    `EstimateLineService` → `unitPrice`, `valueNet`. The override returns the fields only for the WORKER role, so
    the other non-admin-staff roles (SALESMAN, OTHER) keep their prices. Values built outside the generic read
    path are cleared for the WORKER role at the point they are built: the catalog `costCell.labourPrice`, and the
    estimate matrix (`unitPrice`, `baseUnitPrice`, cell `labour` / `baseLabour`, works subtotals and totals,
    package summary). The estimate margins endpoint is refused (403) for the WORKER role. The material cost
    ranges stay (they are not work prices). The `WORK_PRICES` grant is kept.
  - UI: for the WORKER role the catalog hides the labour price column, the work item page hides the price block,
    and the estimate hides the work price labels (per-unit price, works subtotals/totals, package summary).
  - _Requirements: FOR-04-19 catalog cost cell; FOR-05-03 estimate; FOR-03-08 API protection_
  - _Depends on: none_
- [ ] V1. Validate Round 1
  - WORKER: no labour price column and no price labels in the catalog and the estimate; the APIs return the
    work-price fields as `null` and margins as 403. MANAGER/ADMIN/SALESMAN: unchanged.
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1 — changeset, service masking and column gate (one coupled change).
- **Wave 2:** V1.

## Round 1 — validation results (2026-11-28)

Local stack (rebuilt backend and frontend), WORKER `info+valera@foremen.eu`, MANAGER `katarzyna@loaders.dev`, SALESMAN `probe.salesman@example.com`.

| Check | WORKER | MANAGER | SALESMAN |
|---|---|---|---|
| `GET /api/work-items` `costCell.labourPrice` | 0/50 non-null ✅ | 50/50 | 50/50 |
| `GET /api/work-prices` `netPrice` | 0/50 non-null ✅ | 50/50 | 50/50 |
| Estimate matrix, project 2: row `unitPrice` / cell `labour` | 0/209, 0/335 ✅ | 209/209, 335/335 | — |
| Estimate matrix `totals.works` | null; materials kept ✅ | value | — |
| `GET /api/estimates/project/2` totals | null ✅ | values | — |
| `GET /api/estimate-lines` `unitPrice` / `valueNet` | null ✅ | — | — |
| `GET /api/estimates/project/2/margins` | 403 ✅ | 200 | — |
| Catalog table (desktop) | no "Robocizna" column ✅ | "Robocizna" shown | — |
| Estimate tab, DRAFT project 8684 (worker added locally for the check, removed after) | no works total, no unit price, no group works subtotal, no "Robocizna" ✅ | — | — |

`ReadOnlyAdminService` now applies the admin-only masking on every generic read (list, by-id, extended, batch) and
rebuilds records with the masked components cleared. Tests: `WorkerPriceAndPayrollScopeTest` 6/6,
`PermissionMaskingPropertyTest`, `EstimateMatrixControllerAbacTest`, `EstimateMarginsControllerAbacTest` pass;
frontend 1419/1419 in the touched areas.

Dashboard widgets (added after the user's follow-up): a `Dataset` can now declare `workerHiddenFields()`; the
query engine treats those fields as forbidden for a WORKER caller, so a widget (or a builder preview) that selects,
filters, groups, sorts or aggregates by one of them is refused with 403 `error.dashboard.widget.disabled`
(reason `NO_ACCESS`). Declared on the datasets a WORKER can reach that carry work prices: `WORK_PRICES`
(`netPrice`), `ESTIMATES` (`totalNet`, `totalVat`, `totalGross`), `ESTIMATE_LINES` (`unitPrice`,
`unitPriceOverride`, `valueNet`). `WORK_REPORTS` has no price field; `OFFERS` / `AMENDMENT_LINES` need resources
the WORKER role does not hold. Test: `DashboardQueryEngineIntegrationTest.workerHiddenFieldRefused` (WORKER 403,
MANAGER rows) — 11/11. V1 stays open until dev is redeployed.
