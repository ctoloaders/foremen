package com.foremen.service.readiness;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link EstimateReadinessContributor} (FOR-05-11, Property 7 — estimate).
 *
 * <p>For random estimate-line populations (each line priced or not, carrying material lines with or
 * without a resolved consumption) the derived {@code estimate} contribution must satisfy:
 *
 * <ul>
 *   <li><b>Empty.</b> No lines → {@link ReadinessState#BLOCKED}, {@code 0%}, exactly one
 *       {@code readiness.estimate.noWorks} item.</li>
 *   <li><b>Fraction.</b> With lines, {@code percentage == round(mean(pricedFraction, bomFraction))}
 *       where each sub-fraction is {@code round(100 × ok / total)} and the BoM sub-fraction is
 *       {@code 100} when there are no material lines; always in {@code [0, 100]}.</li>
 *   <li><b>State boundary.</b> {@link ReadinessState#DONE} iff every line is priced <b>and</b> every
 *       material line has a consumption, otherwise {@link ReadinessState#PARTIAL} (never
 *       {@code BLOCKED} once a line exists).</li>
 *   <li><b>Items.</b> A {@code readiness.estimate.unpricedLines} item (count = unpriced lines) and a
 *       {@code readiness.estimate.bomIncomplete} item (count = material lines without consumption),
 *       each present only when its count is nonzero, priced-item first; the params carry a count only
 *       (never money); empty items iff {@code DONE}.</li>
 * </ul>
 *
 * <p>Feature: FOR-05-11-project-readiness, Property 7 (estimate)
 *
 * <p><b>Validates: Requirements 1.3, 4.8, 5.2, 5.7, 5.8</b>
 */
@Tag("Feature: FOR-05-11-project-readiness, Property 7 (estimate)")
class EstimateReadinessContributorPropertyTest {

    @Property(tries = 50)
    void noLinesIsBlockedAtZeroWithFirstStepItem(@ForAll("noLines") List<EstimateLineEntity> lines) {
        AreaContribution contribution = EstimateReadinessContributor.deriveContribution(lines);

        assertThat(contribution.key()).isEqualTo("estimate");
        assertThat(contribution.nameKey()).isEqualTo("workspace.tab.estimate");
        assertThat(contribution.state()).isEqualTo(ReadinessState.BLOCKED);
        assertThat(contribution.percentage()).isZero();
        assertThat(contribution.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionKey()).isEqualTo("readiness.estimate.noWorks");
            assertThat(item.params()).isEmpty();
            assertThat(item.targetTab()).isEqualTo("estimate");
        });
    }

    @Property(tries = 400)
    void percentageEqualsMeanOfPricedAndBomSubFractions(@ForAll("lines") List<EstimateLineEntity> lines) {
        AreaContribution contribution = EstimateReadinessContributor.deriveContribution(lines);

        int totalLines = lines.size();
        long pricedLines = lines.stream().filter(EstimateReadinessContributorPropertyTest::isPriced).count();
        long totalMaterials = countMaterials(lines);
        long materialsWithConsumption = countMaterialsWithConsumption(lines);

        int pricedFraction = (int) Math.round(100.0 * pricedLines / totalLines);
        int bomFraction = totalMaterials == 0
                ? 100
                : (int) Math.round(100.0 * materialsWithConsumption / totalMaterials);
        int expected = (int) Math.round((pricedFraction + bomFraction) / 2.0);

        assertThat(contribution.percentage()).isEqualTo(expected);
        assertThat(contribution.percentage()).isBetween(0, 100);
    }

    @Property(tries = 400)
    void stateIsDoneIffAllPricedAndAllMaterialsHaveConsumption(@ForAll("lines") List<EstimateLineEntity> lines) {
        AreaContribution contribution = EstimateReadinessContributor.deriveContribution(lines);

        boolean allPriced = lines.stream().allMatch(EstimateReadinessContributorPropertyTest::isPriced);
        boolean allMaterialsOk = countMaterials(lines) == countMaterialsWithConsumption(lines);

        if (allPriced && allMaterialsOk) {
            assertThat(contribution.state()).isEqualTo(ReadinessState.DONE);
            assertThat(contribution.percentage()).isEqualTo(100);
        } else {
            assertThat(contribution.state()).isEqualTo(ReadinessState.PARTIAL);
        }
        // once a line exists the area is never BLOCKED
        assertThat(contribution.state()).isNotEqualTo(ReadinessState.BLOCKED);
    }

    @Property(tries = 400)
    void itemsCarryOnlyCountsAndMatchTheShortfalls(@ForAll("lines") List<EstimateLineEntity> lines) {
        AreaContribution contribution = EstimateReadinessContributor.deriveContribution(lines);

        long unpriced = lines.size() - lines.stream().filter(EstimateReadinessContributorPropertyTest::isPriced).count();
        long bomIncomplete = countMaterials(lines) - countMaterialsWithConsumption(lines);

        List<OutstandingItem> expected = new ArrayList<>();
        if (unpriced > 0) {
            expected.add(new OutstandingItem(
                    "readiness.estimate.unpricedLines", java.util.Map.of("count", (int) unpriced), "estimate"));
        }
        if (bomIncomplete > 0) {
            expected.add(new OutstandingItem(
                    "readiness.estimate.bomIncomplete", java.util.Map.of("count", (int) bomIncomplete), "estimate"));
        }

        assertThat(contribution.items()).containsExactlyElementsOf(expected);
        // every param is a non-sensitive integer count — never a monetary/masked value (Req 4.8)
        for (OutstandingItem item : contribution.items()) {
            assertThat(item.params()).containsOnlyKeys("count");
            assertThat(item.params().get("count")).isInstanceOf(Integer.class);
        }
    }

    @Property(tries = 300)
    void itemsEmptyIffDone(@ForAll("lines") List<EstimateLineEntity> lines) {
        AreaContribution contribution = EstimateReadinessContributor.deriveContribution(lines);

        boolean fullyReady = contribution.state() == ReadinessState.DONE && contribution.percentage() == 100;
        assertThat(contribution.items().isEmpty()).isEqualTo(fullyReady);
    }

    // --- Helpers mirroring the production rule ---

    private static boolean isPriced(EstimateLineEntity line) {
        BigDecimal unitPrice = line.getUnitPrice();
        return unitPrice != null && unitPrice.signum() > 0;
    }

    private static boolean hasConsumption(EstimateLineRoomMaterialEntity material) {
        BigDecimal value = material.isQtyOverridden() ? material.getManualQty() : material.getNormQty();
        return value != null && value.signum() > 0;
    }

    private static long countMaterials(List<EstimateLineEntity> lines) {
        return lines.stream()
                .flatMap(line -> line.getRoomQtys().stream())
                .flatMap(roomQty -> roomQty.getMaterials().stream())
                .count();
    }

    private static long countMaterialsWithConsumption(List<EstimateLineEntity> lines) {
        return lines.stream()
                .flatMap(line -> line.getRoomQtys().stream())
                .flatMap(roomQty -> roomQty.getMaterials().stream())
                .filter(EstimateReadinessContributorPropertyTest::hasConsumption)
                .count();
    }

    // --- Providers ---

    @Provide
    Arbitrary<List<EstimateLineEntity>> noLines() {
        return Arbitraries.just(List.of());
    }

    /**
     * A non-empty list of estimate lines. Each line is priced or not (unit price null / zero /
     * positive) and carries 0..3 room quantities, each with 0..3 material lines whose consumption is
     * resolved or not (via the {@code normQty} path or the {@code manualQty} override path). This
     * exercises the priced/BoM sub-fraction partition and the null/zero/positive boundaries.
     */
    @Provide
    Arbitrary<List<EstimateLineEntity>> lines() {
        return lineArbitrary().list().ofMinSize(1).ofMaxSize(6);
    }

    private Arbitrary<EstimateLineEntity> lineArbitrary() {
        Arbitrary<Qty> unitPrice = Arbitraries.of(Qty.class);
        Arbitrary<List<EstimateLineRoomMaterialEntity>> materialsPerRoom =
                materialArbitrary().list().ofMinSize(0).ofMaxSize(3);
        Arbitrary<List<List<EstimateLineRoomMaterialEntity>>> rooms =
                materialsPerRoom.list().ofMinSize(0).ofMaxSize(3);
        return Combinators.combine(unitPrice, rooms).as(this::line);
    }

    private EstimateLineEntity line(Qty unitPrice, List<List<EstimateLineRoomMaterialEntity>> rooms) {
        EstimateLineEntity line = new EstimateLineEntity();
        line.setUnitPrice(value(unitPrice));
        List<EstimateLineRoomQtyEntity> roomQtys = new ArrayList<>();
        for (List<EstimateLineRoomMaterialEntity> materials : rooms) {
            EstimateLineRoomQtyEntity roomQty = new EstimateLineRoomQtyEntity();
            roomQty.getMaterials().addAll(materials);
            roomQtys.add(roomQty);
        }
        line.setRoomQtys(roomQtys);
        return line;
    }

    private Arbitrary<EstimateLineRoomMaterialEntity> materialArbitrary() {
        Arbitrary<Boolean> overridden = Arbitraries.of(true, false);
        Arbitrary<Qty> qty = Arbitraries.of(Qty.class);
        return Combinators.combine(overridden, qty).as((isOverride, q) -> {
            EstimateLineRoomMaterialEntity material = new EstimateLineRoomMaterialEntity();
            material.setQtyOverridden(isOverride);
            if (isOverride) {
                material.setManualQty(value(q));
            } else {
                material.setNormQty(value(q));
            }
            return material;
        });
    }

    private static BigDecimal value(Qty qty) {
        return switch (qty) {
            case NULL -> null;
            case ZERO -> BigDecimal.ZERO;
            case POSITIVE -> new BigDecimal("2.5000");
        };
    }

    private enum Qty { NULL, ZERO, POSITIVE }
}
