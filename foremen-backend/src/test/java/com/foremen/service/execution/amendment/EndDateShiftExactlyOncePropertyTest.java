package com.foremen.service.execution.amendment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.foremen.dao.EstimateAmendmentDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.SignableDocumentTypeDao;
import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.AmendmentStatus;
import com.foremen.dao.model.ChangeType;
import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.EstimateAmendmentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.service.document.SignableDocumentService;
import com.foremen.service.model.mapper.AmendmentServiceMapper;

import jakarta.persistence.EntityManager;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import org.springframework.context.ApplicationEventPublisher;

/**
 * FOR-06-09 (Requirements 5.1, 5.4) — property-based coverage of the once-only project-end-date shift
 * in {@link AmendmentSigningService#onDocumentFullySigned(Long)}: the {@code Project.endDate} reaches
 * {@code e0.plusDays(round(netHalfDays / 2.0))} on the first full-sign reflection and then never moves
 * again, no matter how many extra times the event is re-delivered — the single {@code SIGNED →
 * EFFECTIVE} edge, guarded by the amendment status, applies the net schedule delta exactly once
 * (design §"End-date shift").
 *
 * <p>The DAOs, the engine, the mapper, the {@link EntityManager} and the
 * {@link ApplicationEventPublisher} are mocked; the two pure calculators are real. Generated schedule
 * deltas are bounded and the project window is wide, so every shift stays a valid range (the R5.5
 * reject guard is exercised by the pure calculator's own test, not here).
 *
 * <p><b>Property 4: The project end-date shift is applied exactly once.</b> {@code endDate} reaches
 * {@code e0.plusDays(netDays)} on the first application and never moves again across N applications.
 *
 * <p>Feature: FOR-06-09-amendment-signing, Property 4
 *
 * <p><b>Validates: Requirements 5.1, 5.4</b>
 */
@Tag("Feature: FOR-06-09-amendment-signing, Property 4")
class EndDateShiftExactlyOncePropertyTest {

    private static final Long DOCUMENT_ID = 10_000L;
    private static final LocalDate PROJECT_START = LocalDate.of(2020, 1, 1);
    private static final LocalDate PROJECT_END_0 = LocalDate.of(2030, 1, 1);

    private final ScheduleDeltaCalculator calculator = new ScheduleDeltaCalculator();

    // --- Property 4: endDate == e0.plusDays(netDays) after the first call, stable thereafter ----

    /**
     * // Feature: FOR-06-09-amendment-signing, Property 4
     *
     * <p>For any set of {@code SCHEDULE_CHANGE} (+ cost) lines and any {@code N ≥ 1}, the project end
     * date equals {@code e0.plusDays(round(netHalfDays / 2.0))} after the first reflection and remains
     * exactly that across all {@code N} applications — the shift is applied once and never re-applied
     * (Requirements 5.1, 5.4).
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-06-09-amendment-signing, Property 4")
    void endDateShiftsOnceAndNeverMovesAgain(
            @ForAll("boundedLines") @Size(max = 8) List<AmendmentLineEntity> lines,
            @ForAll @IntRange(min = 1, max = 6) int applications) {

        int netHalfDays = calculator.netHalfDays(lines);
        LocalDate expectedEnd = PROJECT_END_0.plusDays(Math.round(netHalfDays / 2.0));

        Fixture fixture = new Fixture(lines);

        fixture.service.onDocumentFullySigned(DOCUMENT_ID);
        assertThat(fixture.project.getEndDate())
                .as("end date after the first application")
                .isEqualTo(expectedEnd);

        for (int i = 1; i < applications; i++) {
            fixture.service.onDocumentFullySigned(DOCUMENT_ID);
            assertThat(fixture.project.getEndDate())
                    .as("end date must not move on re-delivery #%d", i)
                    .isEqualTo(expectedEnd);
        }
    }

    // --- Generators -----------------------------------------------------------------------------

    /**
     * Schedule + cost lines with the net half-day delta kept small relative to the ~10-year project
     * window, so {@code round(net / 2)} days never drives the end date before the start date — the
     * valid-range guard is not the subject here.
     */
    @Provide
    Arbitrary<List<AmendmentLineEntity>> boundedLines() {
        Arbitrary<AmendmentLineEntity> schedule =
                Arbitraries.integers().between(-300, 300).map(this::scheduleLine);
        Arbitrary<AmendmentLineEntity> cost =
                Arbitraries.of(ChangeType.ADDITIONAL_WORK, ChangeType.VOLUME_CHANGE).map(this::costLine);
        return Arbitraries.oneOf(schedule, cost).list();
    }

    private AmendmentLineEntity scheduleLine(Integer halfDays) {
        AmendmentLineEntity line = new AmendmentLineEntity();
        line.setChangeType(ChangeType.SCHEDULE_CHANGE);
        line.setScheduleDeltaHalfDays(halfDays);
        return line;
    }

    private AmendmentLineEntity costLine(ChangeType type) {
        AmendmentLineEntity line = new AmendmentLineEntity();
        line.setChangeType(type);
        return line;
    }

    // --- Fixture --------------------------------------------------------------------------------

    private static final class Fixture {
        final EstimateAmendmentEntity amendment;
        final ProjectEntity project;
        final AmendmentSigningService service;

        Fixture(List<AmendmentLineEntity> lines) {
            project = new ProjectEntity();
            project.setId(1L);
            project.setStartDate(PROJECT_START);
            project.setEndDate(PROJECT_END_0);

            SignableDocumentEntity document = new SignableDocumentEntity();
            DocumentSignatureEntity signature = new DocumentSignatureEntity();
            signature.setSignedAt(LocalDateTime.of(2029, 6, 1, 12, 0));
            document.setSignatures(new ArrayList<>(List.of(signature)));

            amendment = new EstimateAmendmentEntity();
            amendment.setId(800L);
            amendment.setSeqNo(4);
            amendment.setProject(project);
            amendment.setStatus(AmendmentStatus.SENT_FOR_SIGNING);
            amendment.setSignableDocument(document);
            amendment.setLines(new ArrayList<>(lines));
            for (AmendmentLineEntity line : lines) {
                line.setAmendment(amendment);
            }

            EstimateAmendmentDao amendmentDao = mock(EstimateAmendmentDao.class);
            when(amendmentDao.findBySignableDocumentId(DOCUMENT_ID)).thenReturn(Optional.of(amendment));
            when(amendmentDao.save(any(EstimateAmendmentEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service = new AmendmentSigningService(
                    amendmentDao,
                    mock(SignableDocumentService.class),
                    mock(SignableDocumentTypeDao.class),
                    mock(ProjectMemberDao.class),
                    new AllPartiesSignedDateResolver(),
                    new ScheduleDeltaCalculator(),
                    mock(AmendmentServiceMapper.class),
                    mock(ApplicationEventPublisher.class),
                    mock(EntityManager.class));
        }
    }
}
