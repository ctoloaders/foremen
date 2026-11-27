package com.foremen.service.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.OfferService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.estimate.CellAiMeta;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.EstimateAssignmentService;

import jakarta.persistence.EntityManager;

/**
 * FOR-05-04 task 9.6 (Requirements 25.3, 25.5): {@code OfferService.prepareOffer} copies
 * {@code estimates.ai_summary} into {@code offers.ai_report} and flags {@code ai_generated} only when
 * the estimate holds AI marks or a summary.
 */
@DisplayName("FOR-05-04 9.6: prepare copies the estimate AI summary into the offer")
class OfferServiceAiSummaryCopyTest {

    private static final long PROJECT_ID = 42L;

    private final OfferDao offerDao = mock(OfferDao.class);
    private final EstimateDao estimateDao = mock(EstimateDao.class);
    private final OfferService service;

    OfferServiceAiSummaryCopyTest() {
        when(offerDao.save(any())).thenAnswer(i -> i.getArgument(0));
        when(offerDao.findByProjectIdAndStatusNotIn(anyLong(), any())).thenReturn(List.of());
        service = new OfferService(
                offerDao, estimateDao, mock(EstimateLineRoomMaterialDao.class), mock(OfferPackageDao.class),
                mock(UserDao.class), null, mock(ProjectAccessCache.class), null, mock(EntityManager.class),
                new OfferStatusMachine(), new OfferTotalsCalculator(), new DiscountResolver(),
                mock(DraftGateGuard.class), mock(EstimateAssignmentService.class),
                mock(ApplicationEventPublisher.class), mock(ClientOfferReadModelAssembler.class));
    }

    @Test
    @DisplayName("copy on create: a summary is copied to ai_report and ai_generated is set")
    void summaryIsCopied() {
        EstimateEntity estimate = estimate(false);
        estimate.setAiSummary(summary("2026-01-01T10:00:00Z"));
        when(estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(estimate));

        OfferEntity offer = service.prepareOffer(PROJECT_ID);

        assertThat(offer.isAiGenerated()).isTrue();
        assertThat(offer.getAiReport()).containsEntry("jobId", 7L).containsEntry("generatedAt", "2026-01-01T10:00:00Z");
        assertThat(offer.getAiReport()).isNotSameAs(estimate.getAiSummary());
    }

    @Test
    @DisplayName("no copy without marks: no summary and no marked cell keeps ai_generated=false and ai_report=null")
    void noCopyWithoutMarks() {
        EstimateEntity estimate = estimate(false);
        when(estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(estimate));

        OfferEntity offer = service.prepareOffer(PROJECT_ID);

        assertThat(offer.isAiGenerated()).isFalse();
        assertThat(offer.getAiReport()).isNull();
    }

    @Test
    @DisplayName("marks without a summary: ai_generated is set and ai_report stays null")
    void marksWithoutSummary() {
        EstimateEntity estimate = estimate(true);
        when(estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(estimate));

        OfferEntity offer = service.prepareOffer(PROJECT_ID);

        assertThat(offer.isAiGenerated()).isTrue();
        assertThat(offer.getAiReport()).isNull();
    }

    @Test
    @DisplayName("summary survives marker clearing: a summary with no marked cell is still copied")
    void summarySurvivesMarkerClearing() {
        EstimateEntity estimate = estimate(false);
        estimate.setAiSummary(summary("2026-01-01T10:00:00Z"));
        // the cells were edited manually: no ai_meta left anywhere
        estimate.getLines().forEach(l -> l.getRoomQtys().forEach(c -> c.setAiMeta(null)));
        when(estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(estimate));

        OfferEntity offer = service.prepareOffer(PROJECT_ID);

        assertThat(offer.isAiGenerated()).isTrue();
        assertThat(offer.getAiReport()).containsKey("summary");
        assertThat(estimate.getAiSummary()).containsEntry("jobId", 7L);
    }

    private static Map<String, Object> summary(String generatedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", 7L);
        m.put("generatedAt", generatedAt);
        m.put("incomplete", false);
        m.put("summary", new LinkedHashMap<>(Map.of("lines", 1)));
        return m;
    }

    private static EstimateEntity estimate(boolean withMark) {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(100L);
        estimate.setProject(project);
        estimate.setStatus(EstimateStatus.DRAFT);
        estimate.setLines(new ArrayList<>());

        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setId(1L);
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("M2");
        WorkItemEntity work = new WorkItemEntity();
        work.setId(10L);
        work.setWorkCategory(category);
        work.setUnit(unit);
        RoomEntity room = new RoomEntity();
        room.setId(1L);
        room.setLabel("A");
        EstimateLineRoomQtyEntity cell = new EstimateLineRoomQtyEntity();
        cell.setRoom(room);
        cell.setQuantity(new BigDecimal("5.00"));
        if (withMark) {
            cell.setAiMeta(new CellAiMeta("JOB", null, "ASSUMED", new BigDecimal("0.7"), null, "why", null, null, null));
        }
        EstimateLineEntity line = new EstimateLineEntity();
        line.setEstimate(estimate);
        line.setWorkItem(work);
        line.setLineNo(1);
        line.setUnitPrice(new BigDecimal("10.00"));
        line.setQuantity(new BigDecimal("5.00"));
        line.setRoomQtys(new ArrayList<>(List.of(cell)));
        estimate.getLines().add(line);
        return estimate;
    }
}
