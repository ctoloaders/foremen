package com.foremen.service.execution.amendment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.foremen.dao.EstimateAmendmentDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.SignableDocumentTypeDao;
import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.AmendmentStatus;
import com.foremen.dao.model.ChangeType;
import com.foremen.dao.model.EstimateAmendmentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.service.document.SignableDocumentService;
import com.foremen.service.model.mapper.AmendmentServiceMapper;

import jakarta.persistence.EntityManager;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import org.springframework.context.ApplicationEventPublisher;

/**
 * FOR-06-09 (Requirement 2.3) — property-based coverage of the decline/void reopen in
 * {@link AmendmentSigningService#onDocumentReopened(Long)}: for <em>any</em> {@code SENT_FOR_SIGNING}
 * amendment linked to a document that was declined or voided, a reopen call returns the amendment to
 * {@code OPEN}, clears the document link, and leaves the amendment's overlay (its lines) untouched so
 * the manager can amend and resend (design §{@code onDocumentReopened}).
 *
 * <p>The DAOs, the engine {@link SignableDocumentService}, the mapper, the {@link EntityManager} and
 * the {@link ApplicationEventPublisher} are mocked — the reopen path reads only the linked amendment,
 * flips its status, nulls the link and publishes a lifecycle event, so it needs no persistence
 * context. Line integrity is checked order-insensitively on count + per-line {@code (changeType,
 * deltaQuantity, extraUnitPrice, scheduleDeltaHalfDays)} deltas.
 *
 * <p><b>Property 2: Decline/void reopens to OPEN preserving the overlay.</b> {@code onDocumentReopened}
 * yields {@code OPEN}, a cleared {@code signableDocument}, and lines unchanged (count + per-line
 * deltas, order-insensitive).
 *
 * <p>Feature: FOR-06-09-amendment-signing, Property 2
 *
 * <p><b>Validates: Requirements 2.3</b>
 */
@Tag("Feature: FOR-06-09-amendment-signing, Property 2")
class AmendmentReopenPropertyTest {

    private static final Long DOCUMENT_ID = 8_000L;

    // --- Property 2: reopen ⇒ OPEN + cleared link + lines unchanged -----------------------------

    /**
     * // Feature: FOR-06-09-amendment-signing, Property 2
     *
     * <p>For any set of amendment lines, reopening a {@code SENT_FOR_SIGNING} amendment ends it
     * {@code OPEN} with a {@code null} {@code signableDocument}, and its lines are preserved exactly
     * (same count, same per-line deltas regardless of order) (Requirement 2.3).
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-06-09-amendment-signing, Property 2")
    void reopenReturnsToOpenPreservingLines(
            @ForAll("lines") List<AmendmentLineEntity> lines) {

        List<LineDelta> before = snapshot(lines);
        Fixture fixture = new Fixture(lines);

        fixture.service.onDocumentReopened(DOCUMENT_ID);

        assertThat(fixture.amendment.getStatus()).isEqualTo(AmendmentStatus.OPEN);
        assertThat(fixture.amendment.getSignableDocument()).isNull();

        List<LineDelta> after = snapshot(fixture.amendment.getLines());
        assertThat(after).hasSameSizeAs(before);
        assertThat(after).containsExactlyInAnyOrderElementsOf(before);
    }

    // --- Line snapshot (order-insensitive equality on the deltas) -------------------------------

    private record LineDelta(ChangeType changeType,
                             BigDecimal deltaQuantity,
                             BigDecimal extraUnitPrice,
                             Integer scheduleDeltaHalfDays) {
    }

    private static List<LineDelta> snapshot(List<AmendmentLineEntity> lines) {
        List<LineDelta> deltas = new ArrayList<>();
        if (lines != null) {
            for (AmendmentLineEntity line : lines) {
                deltas.add(new LineDelta(
                        line.getChangeType(),
                        line.getDeltaQuantity(),
                        line.getExtraUnitPrice(),
                        line.getScheduleDeltaHalfDays()));
            }
        }
        return deltas;
    }

    // --- Generators -----------------------------------------------------------------------------

    @Provide
    Arbitrary<List<AmendmentLineEntity>> lines() {
        Arbitrary<AmendmentLineEntity> schedule =
                Arbitraries.integers().between(-200, 200).map(this::scheduleLine);
        Arbitrary<AmendmentLineEntity> cost = Combinators.combine(
                        Arbitraries.of(ChangeType.ADDITIONAL_WORK, ChangeType.VOLUME_CHANGE),
                        Arbitraries.bigDecimals().between(BigDecimal.valueOf(-50), BigDecimal.valueOf(50)),
                        Arbitraries.bigDecimals().between(BigDecimal.ZERO, BigDecimal.valueOf(1000)))
                .as(this::costLine);
        return Arbitraries.oneOf(schedule, cost).list().ofMaxSize(8);
    }

    private AmendmentLineEntity scheduleLine(Integer halfDays) {
        AmendmentLineEntity line = new AmendmentLineEntity();
        line.setChangeType(ChangeType.SCHEDULE_CHANGE);
        line.setScheduleDeltaHalfDays(halfDays);
        return line;
    }

    private AmendmentLineEntity costLine(ChangeType type, BigDecimal qty, BigDecimal price) {
        AmendmentLineEntity line = new AmendmentLineEntity();
        line.setChangeType(type);
        line.setDeltaQuantity(qty);
        line.setExtraUnitPrice(price);
        return line;
    }

    // --- Fixture --------------------------------------------------------------------------------

    private static final class Fixture {
        final EstimateAmendmentEntity amendment;
        final AmendmentSigningService service;

        Fixture(List<AmendmentLineEntity> lines) {
            ProjectEntity project = new ProjectEntity();
            project.setId(1L);

            amendment = new EstimateAmendmentEntity();
            amendment.setId(600L);
            amendment.setSeqNo(2);
            amendment.setProject(project);
            amendment.setStatus(AmendmentStatus.SENT_FOR_SIGNING);
            amendment.setSignableDocument(new SignableDocumentEntity());
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
