package com.foremen.dao.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.config.persistence.CellAiMetaConverter;
import com.foremen.service.estimate.CellAiMeta;

/**
 * FOR-05-04 task 1.1: the new estimate stages are non-terminal and fit the status column; the
 * {@code ai_meta} converter round-trips.
 *
 * <p>Validates: Requirements 2.3, 8.2, 21.3
 */
@DisplayName("RoomsImportStatus estimate stages and CellAiMeta converter")
class RoomsImportStatusTest {

    @Test
    @DisplayName("new estimate stages are non-terminal and fit VARCHAR(24)")
    void estimateStagesAreNonTerminal() {
        for (RoomsImportStatus s : EnumSet.of(RoomsImportStatus.SAVING_ROOMS, RoomsImportStatus.PLANNING_WORKS,
                RoomsImportStatus.ASSIGNING, RoomsImportStatus.FINALIZING)) {
            assertThat(s.isTerminal()).isFalse();
        }
        for (RoomsImportStatus s : RoomsImportStatus.values()) {
            assertThat(s.name().length()).isLessThanOrEqualTo(24);
        }
        assertThat(RoomsImportStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(RoomsImportStatus.FAILED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("CellAiMetaConverter round-trips a record and maps null to null")
    void converterRoundTrip() {
        CellAiMetaConverter c = new CellAiMetaConverter();
        CellAiMeta meta = new CellAiMeta("JOB", null, "ASSUMED", new BigDecimal("0.72"),
                new CellAiMeta.Source("plan.pdf", 3), "why", 
                new CellAiMeta.Volume("MODEL_OVERRIDE", "wallArea", new BigDecimal("12.40"),
                        new BigDecimal("14.00"), new CellAiMeta.Correction(new BigDecimal("1.60"), new BigDecimal("12.9")), "niche"),
                new CellAiMeta.Materials(true, 3, 1),
                new CellAiMeta.Price(new BigDecimal("120.00"), new BigDecimal("138.00"), new BigDecimal("15"), "PROJECT", "r"));
        assertThat(c.convertToEntityAttribute(c.convertToDatabaseColumn(meta))).isEqualTo(meta);
        assertThat(c.convertToDatabaseColumn(null)).isNull();
        assertThat(c.convertToEntityAttribute(null)).isNull();
    }
}
