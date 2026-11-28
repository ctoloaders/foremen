package com.foremen.service.estimate.matrix;

import java.util.List;

import com.foremen.config.security.CallerContext;

/**
 * Clears the work (labour) prices from an estimate matrix for the WORKER role (FOR-04-19 bug-report
 * Round 1, "no work price anywhere for a WORKER, including the estimate view").
 *
 * <p>Cleared: the per-unit work price of each row ({@code unitPrice}, {@code baseUnitPrice}), the
 * labour value of each cell ({@code labour}, {@code baseLabour}), the works subtotal of each group and
 * the works total, and the money of the package summary (its totals include the works). Volumes,
 * fill states and the material ranges are kept. Every other role gets the matrix unchanged.
 */
public final class EstimateMatrixWorkerMask {

    private EstimateMatrixWorkerMask() {
    }

    /** The matrix to return to the current caller. */
    public static EstimateMatrixDto apply(EstimateMatrixDto dto) {
        if (dto == null || !CallerContext.isWorker()) {
            return dto;
        }
        return mask(dto);
    }

    /** The matrix with the work prices cleared (no caller check). */
    static EstimateMatrixDto mask(EstimateMatrixDto dto) {
        List<WorkTypeGroupDto> groups = dto.groups() == null ? null
                : dto.groups().stream().map(EstimateMatrixWorkerMask::group).toList();
        EstimatePackageSummaryDto summary = dto.packageSummary() == null ? null
                : new EstimatePackageSummaryDto(dto.packageSummary().applied(), dto.packageSummary().offerPackageId(),
                        dto.packageSummary().packageCode(), null, dto.packageSummary().areaM2(), null, null, null);
        return new EstimateMatrixDto(dto.projectId(), dto.editable(), dto.rooms(), groups, subtotals(dto.totals()),
                dto.fillIndicatorPct(), dto.packageMaterialsTotal(), dto.packageMaterialsMoney(),
                dto.appliedPackageCode(), summary, dto.aiSummary());
    }

    private static WorkTypeGroupDto group(WorkTypeGroupDto g) {
        if (g == null) {
            return null;
        }
        List<WorkRowDto> rows = g.rows() == null ? null : g.rows().stream().map(EstimateMatrixWorkerMask::row).toList();
        return new WorkTypeGroupDto(g.workCategoryId(), g.workCategoryName(), rows, subtotals(g.subtotals()),
                g.packageVolume(), g.packageMoney());
    }

    private static WorkRowDto row(WorkRowDto r) {
        if (r == null) {
            return null;
        }
        List<CellDto> cells = r.cells() == null ? null : r.cells().stream().map(EstimateMatrixWorkerMask::cell).toList();
        return new WorkRowDto(r.workItemId(), r.workItemName(), r.roomTypeIds(), cells, r.packageVolume(),
                r.packageMoney(), r.unitCode(), r.unitName(), null, null);
    }

    private static CellDto cell(CellDto c) {
        if (c == null) {
            return null;
        }
        return new CellDto(c.workItemId(), c.roomId(), c.assigned(), c.volume(), c.formulaUsed(), c.formulaKey(),
                c.fallbackUsed(), c.volumeOverridden(), null, c.materials(), withoutLabour(c.costRange(), c.labour()),
                c.fillState(), null, c.ai());
    }

    /** The cell cost band minus its labour part, i.e. the material range only. */
    private static MoneyRange withoutLabour(MoneyRange cost, java.math.BigDecimal labour) {
        if (cost == null || labour == null) {
            return cost;
        }
        java.math.BigDecimal min = cost.min() == null ? null : cost.min().subtract(labour);
        java.math.BigDecimal max = cost.max() == null ? null : cost.max().subtract(labour);
        return new MoneyRange(min, max);
    }

    private static BranchSubtotals subtotals(BranchSubtotals s) {
        return s == null ? null : new BranchSubtotals(null, s.construction(), s.finishing());
    }
}
