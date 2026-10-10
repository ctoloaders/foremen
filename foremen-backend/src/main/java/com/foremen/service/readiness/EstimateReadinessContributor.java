package com.foremen.service.readiness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;

/**
 * The {@code estimate} area's readiness contribution (FOR-05-11 Requirement 5.2, Decision D12).
 *
 * <p>Combines the parent's {@code works}, {@code priced}, and {@code bom} gates into one
 * equally-weighted {@code estimate} area that maps 1:1 to the {@code estimate} workspace tab. The
 * presence of lines drives the {@code BLOCKED}/first-step case; the unpriced-line and
 * incomplete-BoM facts surface as the area's outstanding items.
 *
 * <ul>
 *   <li>No estimate / no lines → {@link ReadinessState#BLOCKED}, {@code 0%}, one
 *       {@code readiness.estimate.noWorks} item (first step: add works).</li>
 *   <li>Otherwise the area percentage is the mean of two sub-fractions:
 *       <ul>
 *         <li>priced = {@code round(100 × pricedLines / totalLines)} where a line is priced iff its
 *             {@code unitPrice > 0};</li>
 *         <li>bom = {@code round(100 × materialLinesWithConsumption / totalMaterialLines)}, and
 *             {@code 100%} when there are no material lines (nothing to complete).</li>
 *       </ul>
 *       The area is {@link ReadinessState#DONE} at {@code 100%} iff every line is priced
 *       <b>and</b> every material line has a resolved consumption, and
 *       {@link ReadinessState#PARTIAL} otherwise.</li>
 * </ul>
 *
 * <p>Material lines are reached by traversing {@code line → roomQtys → materials}; a material line
 * has a resolved consumption iff {@code qtyOverridden ? manualQty > 0 : normQty > 0}. The
 * outstanding items carry <b>counts only</b> — the unpriced-line count and the incomplete-BoM count,
 * never money (Requirement 4.8). Items are listed deterministically (priced first, then BoM) and
 * only when their count is nonzero (Requirement 5.2, 5.8). This contributor is side-effect free
 * (Requirement 1.6).
 */
@Component
public class EstimateReadinessContributor implements ProjectReadinessContributor {

    static final String AREA_KEY = "estimate";
    static final String NAME_KEY = "workspace.tab.estimate";
    static final String TARGET_TAB = "estimate";
    static final String ITEM_NO_WORKS = "readiness.estimate.noWorks";
    static final String ITEM_UNPRICED_LINES = "readiness.estimate.unpricedLines";
    static final String ITEM_BOM_INCOMPLETE = "readiness.estimate.bomIncomplete";

    private final EstimateDao estimateDao;
    private final EstimateLineDao estimateLineDao;

    public EstimateReadinessContributor(EstimateDao estimateDao, EstimateLineDao estimateLineDao) {
        this.estimateDao = estimateDao;
        this.estimateLineDao = estimateLineDao;
    }

    @Override
    public String areaKey() {
        return AREA_KEY;
    }

    @Override
    public String areaResource() {
        return "ESTIMATE";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public AreaContribution contribute(Long projectId) {
        boolean hasEstimate = estimateDao.findByProjectId(projectId).isPresent();
        List<EstimateLineEntity> lines = hasEstimate
                ? estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId)
                : List.of();
        return deriveContribution(lines);
    }

    /**
     * The pure derivation of the {@code estimate} contribution from the project's estimate lines
     * (with their room quantities and material lines reachable through the loaded graph). Spring-free
     * so it is directly property-testable (FOR-05-11 Property 7, estimate).
     *
     * @param lines the project's estimate lines (empty when there is no estimate or no lines)
     * @return the well-formed {@code estimate} area contribution
     */
    static AreaContribution deriveContribution(List<EstimateLineEntity> lines) {
        if (lines.isEmpty()) {
            return new AreaContribution(
                    AREA_KEY,
                    NAME_KEY,
                    0,
                    ReadinessState.BLOCKED,
                    List.of(new OutstandingItem(ITEM_NO_WORKS, Map.of(), TARGET_TAB)));
        }

        int totalLines = lines.size();
        int pricedLines = 0;
        int totalMaterialLines = 0;
        int materialLinesWithConsumption = 0;

        for (EstimateLineEntity line : lines) {
            if (isPriced(line)) {
                pricedLines++;
            }
            for (EstimateLineRoomMaterialEntity material : materialLinesOf(line)) {
                totalMaterialLines++;
                if (hasConsumption(material)) {
                    materialLinesWithConsumption++;
                }
            }
        }

        int unpricedCount = totalLines - pricedLines;
        int bomIncompleteCount = totalMaterialLines - materialLinesWithConsumption;

        int pricedFraction = Math.toIntExact(Math.round(100.0 * pricedLines / totalLines));
        int bomFraction = totalMaterialLines == 0
                ? 100
                : Math.toIntExact(Math.round(100.0 * materialLinesWithConsumption / totalMaterialLines));
        int percentage = Math.toIntExact(Math.round((pricedFraction + bomFraction) / 2.0));

        boolean done = unpricedCount == 0 && bomIncompleteCount == 0;
        ReadinessState state = done ? ReadinessState.DONE : ReadinessState.PARTIAL;

        List<OutstandingItem> items = new ArrayList<>();
        if (unpricedCount > 0) {
            items.add(new OutstandingItem(ITEM_UNPRICED_LINES, Map.of("count", unpricedCount), TARGET_TAB));
        }
        if (bomIncompleteCount > 0) {
            items.add(new OutstandingItem(ITEM_BOM_INCOMPLETE, Map.of("count", bomIncompleteCount), TARGET_TAB));
        }

        // When DONE the percentage is exactly 100 (both sub-fractions are 100); keep it well-formed.
        if (done) {
            percentage = 100;
        }

        return new AreaContribution(AREA_KEY, NAME_KEY, percentage, state, List.copyOf(items));
    }

    /**
     * Every material (BoM) line reachable from one estimate line by traversing
     * {@code line → roomQtys → materials}.
     */
    private static List<EstimateLineRoomMaterialEntity> materialLinesOf(EstimateLineEntity line) {
        List<EstimateLineRoomMaterialEntity> materials = new ArrayList<>();
        for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
            materials.addAll(roomQty.getMaterials());
        }
        return materials;
    }

    private static boolean isPriced(EstimateLineEntity line) {
        BigDecimal unitPrice = line.getUnitPrice();
        return unitPrice != null && unitPrice.signum() > 0;
    }

    /**
     * A material line has a resolved consumption iff, when manually overridden, its {@code manualQty}
     * is positive; otherwise its copied {@code normQty} is positive (FOR-05-11 design,
     * {@code qtyOverridden ? manualQty > 0 : normQty > 0}).
     */
    private static boolean hasConsumption(EstimateLineRoomMaterialEntity material) {
        if (material.isQtyOverridden()) {
            return isPositive(material.getManualQty());
        }
        return isPositive(material.getNormQty());
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
