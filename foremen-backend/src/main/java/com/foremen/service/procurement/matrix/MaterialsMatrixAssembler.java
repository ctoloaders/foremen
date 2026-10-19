package com.foremen.service.procurement.matrix;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.MatchStatus;
import com.foremen.service.model.mapper.ProcurementDocumentServiceMapper;

/**
 * FOR-06-05 (design §B1, R1, R2, R3, R4) — the materials matrix plan-vs-fact projection. A pure,
 * stateless {@link Component} that builds a {@link MaterialsMatrixDto} from the FOR-05-05b planned
 * rows ({@link MatrixPlannedRow}) plus the FOR-06-04 reconciled lines ({@link MatrixFactLine}),
 * already resolved and localized by {@code MaterialsMatrixService} (task 2.1). It performs
 * <strong>no I/O</strong> beyond reading the passed-in models — every figure is derived here and the
 * whole fold lives in pure {@code static} helpers so it is property-testable without Spring.
 *
 * <p>The projection:
 * <ul>
 *   <li>indexes the linked lines by {@code matchedMaterialId}, keeping only
 *       {@code matchStatus ∈ {AUTO, MANUAL_MATCHED}} — so an unmatched / cleared / unplanned line is
 *       dropped up front and contributes to no row (R1.6);</li>
 *   <li>per planned row folds {@code Fact_Brutto} (Σ {@code lineTotal}, all tiers at full weight,
 *       R1.2/R1.3), {@code Fact_Quantity} (same-unit lines only via {@link #unitEq}, R2.1),
 *       {@code Unit_Mismatch_Count} + flag (R2.2), {@code Fulfilment_Percent} (uncapped, {@code null}
 *       when planned brutto null/0, R1.3/R1.4), {@code Fill_Indicator} = {@code min(pct, 100)} and
 *       {@code overPurchased} = {@code pct > 100} (R4.1);</li>
 *   <li>groups rows by branch then category (preserving the FOR-05-05b order, R3.1) and folds the
 *       per-branch and grand-total dashboards over the rows with a non-null planned brutto
 *       (R3.2/R3.3).</li>
 * </ul>
 *
 * <p>The canonical {@link #unitEq(String, String)} and {@link #fulfilment(BigDecimal, BigDecimal)}
 * helpers are the single place those rules live — shared by rows and the dashboard so the figures
 * never drift — and are property-tested.
 */
@Component
public class MaterialsMatrixAssembler {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int PERCENT_SCALE = 4;

    /**
     * Assemble the materials-matrix read model from the FOR-05-05b planned rows and the FOR-06-04
     * reconciled lines.
     *
     * @param projectId   the owning project id (echoed into the read model)
     * @param plannedRows the planned-material rows, in FOR-05-05b order (branch → category →
     *                    first-seen); {@code null} treated as empty
     * @param factLines   the reconciled lines of the project; {@code null} treated as empty. Only
     *                    matched lines ({@code AUTO} / {@code MANUAL_MATCHED} with a non-null
     *                    {@code matchedMaterialId}) contribute (R1.6)
     * @return the assembled {@link MaterialsMatrixDto}
     */
    public MaterialsMatrixDto assemble(
            Long projectId,
            List<MatrixPlannedRow> plannedRows,
            List<MatrixFactLine> factLines) {
        return project(projectId, plannedRows, factLines);
    }

    // ---------------------------------------------------------------------------------------------
    // Canonical helpers (design §B1) — the single source of the unit-match and % выборки rules
    // ---------------------------------------------------------------------------------------------

    /**
     * Case/whitespace-insensitive unit equality (R2.1). A blank/null {@code unitHint} — or a
     * {@code null} norm unit — never matches, so a line with no usable unit is treated as a mismatch.
     *
     * @param unitHint the OCR line unit hint
     * @param normUnit the planned material's norm unit
     * @return {@code true} iff both are non-blank and equal ignoring case and surrounding whitespace
     */
    static boolean unitEq(String unitHint, String normUnit) {
        if (unitHint == null || unitHint.isBlank() || normUnit == null) {
            return false;
        }
        return unitHint.strip().equalsIgnoreCase(normUnit.strip());
    }

    /**
     * The money-based % выборки, uncapped (R1.3, R1.4): {@code factBrutto ÷ plannedBrutto × 100}, or
     * {@code null} when there is no meaningful denominator (a {@code null} or zero planned brutto),
     * never a divide-by-zero.
     *
     * @param factBrutto   the aggregated fact brutto (Σ lineTotal); {@code null} treated as 0
     * @param plannedBrutto the planned brutto denominator
     * @return the uncapped percent, or {@code null} when {@code plannedBrutto} is {@code null}/0
     */
    static BigDecimal fulfilment(BigDecimal factBrutto, BigDecimal plannedBrutto) {
        if (plannedBrutto == null || plannedBrutto.signum() == 0) {
            return null;
        }
        BigDecimal fact = factBrutto == null ? BigDecimal.ZERO : factBrutto;
        return fact.multiply(HUNDRED).divide(plannedBrutto, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------------------------------------
    // Pure projection core — property-testable without Spring
    // ---------------------------------------------------------------------------------------------

    /**
     * The pure projection (R1, R2, R3, R4). Package-visible {@code static} so the property tests can
     * exercise it directly without a Spring context.
     */
    static MaterialsMatrixDto project(
            Long projectId,
            List<MatrixPlannedRow> plannedRows,
            List<MatrixFactLine> factLines) {

        Map<Long, List<MatrixFactLine>> linkedByMaterial = indexLinkedLines(factLines);

        // Preserve the FOR-05-05b order: group by branch, then by category within the branch, both
        // in first-seen order (R3.1).
        Map<ConsumptionBranch, Map<String, List<MatrixRowDto>>> rowsByBranchCategory =
                new EnumMap<>(ConsumptionBranch.class);
        for (ConsumptionBranch branch : ConsumptionBranch.values()) {
            rowsByBranchCategory.put(branch, new LinkedHashMap<>());
        }

        List<MatrixPlannedRow> rows = plannedRows == null ? List.of() : plannedRows;
        for (MatrixPlannedRow planned : rows) {
            if (planned == null) {
                continue;
            }
            MatrixRowDto rowDto = foldRow(planned, linkedByMaterial.get(planned.materialId()));
            ConsumptionBranch branch = planned.branch();
            Map<String, List<MatrixRowDto>> byCategory = rowsByBranchCategory.computeIfAbsent(
                    branch, b -> new LinkedHashMap<>());
            byCategory.computeIfAbsent(categoryKey(planned.category()), c -> new ArrayList<>()).add(rowDto);
        }

        List<MatrixBranchGroupDto> branches = new ArrayList<>();
        BigDecimal grandPlanned = null;
        BigDecimal grandFact = null;
        for (ConsumptionBranch branch : ConsumptionBranch.values()) {
            Map<String, List<MatrixRowDto>> byCategory = rowsByBranchCategory.get(branch);
            if (byCategory == null || byCategory.isEmpty()) {
                continue; // a branch with no planned rows is omitted
            }
            List<MatrixCategoryGroupDto> categories = new ArrayList<>();
            BigDecimal branchPlanned = null;
            BigDecimal branchFact = null;
            for (Map.Entry<String, List<MatrixRowDto>> entry : byCategory.entrySet()) {
                List<MatrixRowDto> categoryRows = entry.getValue();
                categories.add(new MatrixCategoryGroupDto(categoryRows.get(0).category(), categoryRows));
                for (MatrixRowDto row : categoryRows) {
                    if (row.plannedBrutto() == null) {
                        continue; // null-brutto rows excluded from money totals (R3.3)
                    }
                    branchPlanned = nullSafeAdd(branchPlanned, row.plannedBrutto());
                    branchFact = nullSafeAdd(branchFact, row.factBrutto());
                }
            }
            BigDecimal branchPct = fulfilment(branchFact, branchPlanned);
            branches.add(new MatrixBranchGroupDto(branch, categories, branchPlanned, branchFact, branchPct));
            grandPlanned = sumNullable(grandPlanned, branchPlanned);
            grandFact = sumNullable(grandFact, branchFact);
        }

        BigDecimal grandPct = fulfilment(grandFact, grandPlanned);
        return new MaterialsMatrixDto(projectId, branches, grandPlanned, grandFact, grandPct);
    }

    /**
     * Index the linked lines by {@code matchedMaterialId}, keeping only matched lines
     * ({@code matchStatus ∈ {AUTO, MANUAL_MATCHED}} with a non-null {@code matchedMaterialId}). Every
     * other line — unmatched, manually cleared, or unplanned — is dropped so it contributes to no row
     * (R1.6). Insertion order is preserved so the detail lists lines deterministically.
     */
    private static Map<Long, List<MatrixFactLine>> indexLinkedLines(List<MatrixFactLine> factLines) {
        Map<Long, List<MatrixFactLine>> byMaterial = new LinkedHashMap<>();
        if (factLines == null) {
            return byMaterial;
        }
        for (MatrixFactLine line : factLines) {
            if (line == null || line.matchedMaterialId() == null || !isMatched(line.matchStatus())) {
                continue; // unmatched / cleared / unplanned ⇒ no row (R1.6)
            }
            byMaterial.computeIfAbsent(line.matchedMaterialId(), id -> new ArrayList<>()).add(line);
        }
        return byMaterial;
    }

    /** A matched line is {@code AUTO} or {@code MANUAL_MATCHED}; cleared/unmatched never counts (R1.6). */
    private static boolean isMatched(MatchStatus status) {
        return status == MatchStatus.AUTO || status == MatchStatus.MANUAL_MATCHED;
    }

    /**
     * Fold one planned row against its linked lines (R1.2, R1.3, R1.4, R1.5, R2.1, R2.2, R4.1). The
     * {@code matchTier} never scales a contribution — every linked line adds its full
     * {@code lineTotal} (R1.2).
     */
    private static MatrixRowDto foldRow(MatrixPlannedRow planned, List<MatrixFactLine> linked) {
        List<MatrixLinkedLineDto> detail = new ArrayList<>();
        BigDecimal factBrutto = BigDecimal.ZERO;
        BigDecimal factQuantity = BigDecimal.ZERO;
        int mismatchCount = 0;

        if (linked != null) {
            for (MatrixFactLine line : linked) {
                boolean matchesUnit = unitEq(line.unitHint(), planned.unit());
                boolean unitMismatch = !matchesUnit;
                if (unitMismatch) {
                    mismatchCount++;
                } else if (line.quantity() != null) {
                    factQuantity = factQuantity.add(line.quantity()); // same-unit only (R2.1)
                }
                if (line.lineTotal() != null) {
                    factBrutto = factBrutto.add(line.lineTotal()); // all linked lines, full weight (R1.3)
                }
                detail.add(new MatrixLinkedLineDto(
                        line.lineId(),
                        line.documentId(),
                        line.supplierName(),
                        line.uploadedAt(),
                        line.rawName(),
                        line.manufacturerHint(),
                        line.quantity(),
                        line.unitHint(),
                        line.matchTier(),
                        ProcurementDocumentServiceMapper.tierPercent(line.matchTier()),
                        line.matchStatus(),
                        line.lineTotal(),
                        unitMismatch));
            }
        }

        BigDecimal pct = fulfilment(factBrutto, planned.plannedBrutto()); // uncapped; null when no denom
        BigDecimal fill = pct == null ? null : pct.min(HUNDRED);          // min(pct, 100) (R4.1)
        boolean overPurchased = pct != null && pct.compareTo(HUNDRED) > 0;

        return new MatrixRowDto(
                planned.materialId(),
                planned.materialName(),
                planned.branch(),
                planned.category(),
                planned.unit(),
                planned.plannedQuantity(),
                planned.plannedBrutto(),
                factQuantity,
                mismatchCount,
                mismatchCount > 0,
                factBrutto,
                pct,
                fill,
                overPurchased,
                detail.size(),
                detail);
    }

    /** A stable, non-null grouping key for a (possibly null/blank) category label. */
    private static String categoryKey(String category) {
        return category == null ? "" : category;
    }

    /**
     * Add {@code addend} to a possibly-{@code null} running total; a {@code null} total starts the
     * sum (so a branch/grand total stays {@code null} until a non-null-brutto row contributes).
     */
    private static BigDecimal nullSafeAdd(BigDecimal total, BigDecimal addend) {
        BigDecimal value = addend == null ? BigDecimal.ZERO : addend;
        return total == null ? value : total.add(value);
    }

    /** Sum two possibly-{@code null} branch totals into a grand total, {@code null} when both null. */
    private static BigDecimal sumNullable(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.add(b);
    }
}
