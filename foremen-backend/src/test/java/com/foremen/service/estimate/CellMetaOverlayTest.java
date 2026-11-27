package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;

/** FOR-05-04 task 3.11 (Requirements 21.2, 21.4, 21.6): pure parts of the cell meta overlay and the plan warnings. */
class CellMetaOverlayTest {

    private static EstimateLineEntity line(String base, String effective) {
        EstimateLineEntity line = new EstimateLineEntity();
        line.setUnitPrice(new BigDecimal(base));
        line.setUnitPriceOverride(effective != null ? new BigDecimal(effective) : null);
        line.setRoomQtys(new ArrayList<>());
        return line;
    }

    private static EstimateLineRoomQtyEntity cell(String qty, boolean overridden) {
        EstimateLineRoomQtyEntity cell = new EstimateLineRoomQtyEntity();
        cell.setQuantity(new BigDecimal(qty));
        cell.setVolumeOverridden(overridden);
        return cell;
    }

    private static CellMetaInput input(String origin, String volumeReason, String priceReason) {
        return new CellMetaInput("1.01", 7L, origin, "CHAT".equals(origin) ? 5L : null, "  why  ", volumeReason,
                "CONFIRMED", null, null, null, priceReason, false);
    }

    @Test
    @DisplayName("overridden cell: formula volume from the engine, correction, model or chat override source")
    void overriddenCell() {
        CellFactsResolver.Facts facts = new CellFactsResolver.Facts(new BigDecimal("12.40"), "FORMULA", "wallArea", false);
        CellAiMeta job = CellMetaOverlay.build(input("JOB", "Niche", null), line("10", "10"), cell("14.00", true), facts);
        assertThat(job.rationale()).isEqualTo("why");
        assertThat(job.source()).isNull();
        assertThat(job.price()).isNull();
        assertThat(job.volume().source()).isEqualTo("MODEL_OVERRIDE");
        assertThat(job.volume().formulaKey()).isEqualTo("wallArea");
        assertThat(job.volume().formulaVolume()).isEqualByComparingTo("12.40");
        assertThat(job.volume().correction().delta()).isEqualByComparingTo("1.60");
        assertThat(job.volume().correction().pct()).isEqualByComparingTo("12.9");
        assertThat(job.volume().adjustmentReason()).isEqualTo("Niche");

        CellAiMeta chat = CellMetaOverlay.build(input("CHAT", null, null), line("10", null), cell("10.00", true), facts);
        assertThat(chat.turnId()).isEqualTo(5L);
        assertThat(chat.volume().source()).isEqualTo("CHAT_OVERRIDE");
        assertThat(chat.volume().reasonCode()).isEqualTo(CellMetaOverlay.MODEL_VOLUME_DIFFERS);
    }

    @Test
    @DisplayName("formula cell: no correction, no reason; price block only when the effective price differs")
    void formulaCellAndPrice() {
        CellFactsResolver.Facts facts = new CellFactsResolver.Facts(new BigDecimal("8"), "UNIT_FALLBACK", "fallback", true);
        CellAiMeta meta = CellMetaOverlay.build(input("JOB", "ignored", "Stated +15%"), line("120.00", "138.00"),
                cell("8", false), facts);
        assertThat(meta.volume().source()).isEqualTo("UNIT_FALLBACK");
        assertThat(meta.volume().correction()).isNull();
        assertThat(meta.volume().adjustmentReason()).isNull();
        assertThat(meta.price().basePrice()).isEqualByComparingTo("120.00");
        assertThat(meta.price().effectivePrice()).isEqualByComparingTo("138.00");
        assertThat(meta.price().pct()).isEqualByComparingTo("15");
        assertThat(meta.price().reason()).isEqualTo("Stated +15%");
        assertThat(CellMetaOverlay.price(line("120.00", "120.00"), "x")).isNull();
        assertThat(CellMetaOverlay.correction(new BigDecimal("5"), new BigDecimal("5.00"))).isNull();
        assertThat(CellMetaOverlay.correction(BigDecimal.ZERO, new BigDecimal("3")).pct()).isNull();
    }
}
