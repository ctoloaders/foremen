package com.foremen.service.estimateai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.service.OfferService;
import com.foremen.service.offer.OfferSnapshot;

/** FOR-05-04 task 9.6 (Requirements 25.1, 25.2): the summary document and its persistence. */
@DisplayName("FOR-05-04 9.6: AiSummaryWriter")
class AiSummaryWriterTest {

    private static AiSummaryWriter.Request request(boolean completed, EstimatePlan plan) {
        return new AiSummaryWriter.Request(9L, 42L, plan, null, List.of(), completed, 3, 1, "v1");
    }

    @Test
    @DisplayName("complete run: incomplete=false, ids, counters and no chat changes[]")
    @SuppressWarnings("unchecked")
    void completeRun() {
        Map<String, Object> s = AiSummaryWriter.build(request(true, new EstimatePlan(null, null, null, null, null)),
                emptySnapshot());

        assertThat(s.get("incomplete")).isEqualTo(false);
        assertThat(s).containsEntry("jobId", 9L).containsEntry("promptVersion", "v1")
                .containsKeys("generatedAt", "assumptions", "questions", "unpriced", "warnings", "summary")
                .doesNotContainKey("changes");
        Map<String, Object> summary = (Map<String, Object>) s.get("summary");
        assertThat(summary).containsKeys("lines", "cells", "totalNet", "byCategory", "materials");
        assertThat((Map<String, Object>) summary.get("assignments"))
                .containsEntry("done", 3).containsEntry("rejected", 1);
    }

    @Test
    @DisplayName("cancelled or failed run is marked incomplete even without unpriced items")
    void partialRunIsIncomplete() {
        Map<String, Object> s = AiSummaryWriter.build(request(false, null), emptySnapshot());

        assertThat(s.get("incomplete")).isEqualTo(true);
    }

    @Test
    @DisplayName("write stores the summary on the estimate; a project without estimate writes nothing")
    void writePersists() {
        EstimateDao dao = mock(EstimateDao.class);
        OfferService offers = mock(OfferService.class);
        EstimateEntity estimate = new EstimateEntity();
        when(dao.findByProjectId(42L)).thenReturn(Optional.of(estimate));
        when(dao.findByProjectId(43L)).thenReturn(Optional.empty());
        when(offers.buildScratchSnapshot(estimate)).thenReturn(emptySnapshot());
        AiSummaryWriter writer = new AiSummaryWriter(dao, offers);

        Map<String, Object> stored = writer.write(request(true, null));

        assertThat(estimate.getAiSummary()).isSameAs(stored).containsEntry("jobId", 9L);
        verify(dao).save(estimate);

        assertThat(writer.write(new AiSummaryWriter.Request(9L, 43L, null, null, List.of(), true, 0, 0, null)))
                .isNull();
        verify(dao, times(1)).save(any(EstimateEntity.class));
    }

    private static OfferSnapshot emptySnapshot() {
        return new OfferSnapshot(3, "2026-01-01T00:00:00Z", new BigDecimal("23"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, List.of(), List.of());
    }
}
