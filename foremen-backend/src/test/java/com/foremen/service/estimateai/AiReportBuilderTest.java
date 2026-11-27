package com.foremen.service.estimateai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.service.offer.OfferSnapshot;

/** FOR-05-04 task 3.8 (Requirements 9.1, 9.3, 8.8): the pure report builder. */
class AiReportBuilderTest {

    private static OfferSnapshot.Row row(String price, String qty) {
        BigDecimal p = new BigDecimal(price);
        BigDecimal q = new BigDecimal(qty);
        return new OfferSnapshot.Row(1L, "r", "p", "m2", "m2", "m2", q, p, p, p.multiply(q), p.multiply(q),
                List.of());
    }

    @Test
    @DisplayName("summary groups rows by category and carries totals, perM2 and the incomplete flag")
    @SuppressWarnings("unchecked")
    void summaryByCategory() {
        OfferSnapshot snapshot = new OfferSnapshot(2, "now", new BigDecimal("23"), new BigDecimal("150.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(
                        new OfferSnapshot.Group(1L, "A", "A", List.of(row("10.00", "5"), row("20.00", "2"))),
                        new OfferSnapshot.Group(2L, "B", "B", List.of(row("30.00", "2")))));
        EstimatePlan plan = new EstimatePlan(List.of(),
                List.of(new EstimatePlan.Note("x".repeat(900), "W1", 7L, "plan.pdf p1")), List.of(),
                List.of(new Unpriced("Gold", "m2", new BigDecimal("3"), WarningCodes.NOT_IN_CATALOG, null, null)),
                List.of());

        Map<String, Object> report = AiReportBuilder.build(plan, snapshot, new BigDecimal("30"), List.of());

        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertThat(summary.get("lines")).isEqualTo(3);
        assertThat((BigDecimal) summary.get("perM2")).isEqualByComparingTo("5.00");
        List<Map<String, Object>> byCategory = (List<Map<String, Object>>) summary.get("byCategory");
        assertThat(byCategory).hasSize(2);
        assertThat((BigDecimal) byCategory.get(0).get("totalNet")).isEqualByComparingTo("90.00");
        assertThat((BigDecimal) byCategory.get(1).get("totalNet")).isEqualByComparingTo("60.00");
        assertThat(report.get("incomplete")).isEqualTo(true);
        List<Map<String, Object>> assumptions = (List<Map<String, Object>>) report.get("assumptions");
        assertThat((String) assumptions.get(0).get("text")).hasSize(500);
        assertThat(assumptions.get(0)).containsEntry("workCode", "W1").containsEntry("roomId", 7L);
    }

    @Test
    @DisplayName("an empty snapshot yields the EMPTY_OFFER warning and no incomplete flag without unpriced items")
    @SuppressWarnings("unchecked")
    void emptyOffer() {
        OfferSnapshot snapshot = new OfferSnapshot(2, "now", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, List.of(), List.of());

        Map<String, Object> report = AiReportBuilder.build(null, snapshot, null, List.of());

        List<Map<String, Object>> warnings = (List<Map<String, Object>>) report.get("warnings");
        assertThat(warnings).extracting(w -> w.get("code")).containsExactly(WarningCodes.EMPTY_OFFER);
        assertThat(report.get("incomplete")).isEqualTo(false);
        assertThat((Map<String, Object>) report.get("summary")).containsEntry("lines", 0)
                .doesNotContainKey("perM2");
    }

    private static OfferSnapshot.MaterialLine material(Long typeId, String min, String max, boolean overridden) {
        BigDecimal one = BigDecimal.ONE;
        return new OfferSnapshot.MaterialLine("construction", typeId, null, one, "PER_UNIT",
                min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max),
                overridden ? Boolean.TRUE : null, overridden ? new BigDecimal("3") : null, one,
                null, null, null, null, null, null);
    }

    private static OfferSnapshot.Row rowWith(Long workItemId, List<OfferSnapshot.Cell> cells) {
        BigDecimal p = new BigDecimal("10.00");
        return new OfferSnapshot.Row(workItemId, "r", "p", "m2", "m2", "m2", BigDecimal.ONE, p, p, p, p, cells);
    }

    @Test
    @DisplayName("summary.materials counts lines, cells and overrides, lists works without norms and warns about a missing range (R23.9)")
    @SuppressWarnings("unchecked")
    void materialsSummaryAndPriceRangeWarning() {
        OfferSnapshot.Cell withMaterials = new OfferSnapshot.Cell(1L, BigDecimal.TEN, null, null, null, null,
                List.of(material(5L, "1.00", "2.00", false), material(6L, null, null, true)));
        OfferSnapshot.Cell otherRoom = new OfferSnapshot.Cell(2L, BigDecimal.TEN, null, null, null, null,
                List.of(material(5L, "1.00", "2.00", true), material(6L, null, null, false)));
        OfferSnapshot.Cell noNorms = new OfferSnapshot.Cell(1L, BigDecimal.ONE, null, null, null, null, List.of());
        OfferSnapshot snapshot = new OfferSnapshot(3, "now", BigDecimal.ZERO, new BigDecimal("20.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(new OfferSnapshot.Group(1L, "A", "A", List.of(
                        rowWith(11L, List.of(withMaterials, otherRoom)), rowWith(12L, List.of(noNorms))))));
        EstimatePlan plan = new EstimatePlan(List.of(
                new PlannedWork("W-PAINT", 11L, 1L, null, null, null, null, null, null, null, null, null),
                new PlannedWork("W-DEMO", 12L, 1L, null, null, null, null, null, null, null, null, null)),
                List.of(), List.of(), List.of(), List.of());
        Map<String, Object> report = AiReportBuilder.build(plan, snapshot, null, List.of());
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertThat((Map<String, Object>) summary.get("materials"))
                .containsEntry("lines", 4)
                .containsEntry("cellsWithMaterials", 2)
                .containsEntry("overriddenLines", 2)
                .containsEntry("worksWithoutNorms", List.of("W-DEMO"));
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) report.get("warnings");
        // one warning per (work, type), not per cell; the work without norms yields none
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).containsEntry("code", WarningCodes.MATERIAL_PRICE_RANGE_MISSING)
                .containsEntry("workCode", "W-PAINT");
        assertThat((Map<String, Object>) warnings.get(0).get("params"))
                .containsEntry("workCode", "W-PAINT").containsEntry("materialTypeId", 6L);
    }
}
