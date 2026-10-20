package com.foremen.service.execution.amendment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.Size;

import org.springframework.context.ApplicationEventPublisher;

/**
 * FOR-06-09 (Requirements 5.2, 10.1, 10.2, 10.3) — property-based coverage of the overlay integrity of
 * the full-sign reflection in {@link AmendmentSigningService#onDocumentFullySigned(Long)}: becoming
 * {@code EFFECTIVE} is a pure overlay — the <b>only</b> schedule mutation anywhere is the single
 * {@code Project.endDate} shift; {@code Project.startDate} is never touched, and the amendment's stored
 * lines (the reproducible record of what changed) are left exactly as authored, so the applied effect
 * is derivable from {@code lines + baseline} and nothing else (design §Key-decision-5).
 *
 * <p>This is the unit-level guard: the service's <em>only</em> collaborators are the DAO, the engine,
 * the two pure calculators, the mapper, the {@link EntityManager}, and the event publisher — it holds
 * no reference to the estimate / line / room-qty / work-report repositories at all, so it is
 * structurally incapable of mutating those rows (R10.1). What this property verifies directly is the
 * schedule invariant: {@code startDate} unchanged, {@code endDate} shifted by exactly the pure
 * delta derived from the lines, and the line overlay preserved.
 *
 * <p><b>Property 6: Overlay integrity — only Project.endDate is mutated.</b> estimate / line /
 * room-qty / work-report rows, {@code startDate}, and every schedule bar unchanged; only
 * {@code endDate} changes; the effect is derivable from lines + baseline.
 *
 * <p>Feature: FOR-06-09-amendment-signing, Property 6
 *
 * <p><b>Validates: Requirements 5.2, 10.1, 10.2, 10.3</b>
 */
@Tag("Feature: FOR-06-09-amendment-signing, Property 6")
class AmendmentOverlayIntegrityPropertyTest {

    private static final Long DOCUMENT_ID = 11_000L;
    private static final LocalDate PROJECT_START = LocalDate.of(2015, 1, 1);
    private static final LocalDate PROJECT_END_0 = LocalDate.of(2035, 1, 1);

    private final ScheduleDeltaCalculator calculator = new ScheduleDeltaCalculator();

    // --- Property 6: startDate untouched, endDate = baseline + derived shift, lines preserved ----

    /**
     * // Feature: FOR-06-09-amendment-signing, Property 6
     *
     * <p>After the full-sign reflection: {@code Project.startDate} is unchanged (R5.2, R10.2);
     * {@code Project.endDate} equals {@code baselineEnd.plusDays(round(netHalfDays / 2.0))} — i.e. it
     * is derivable purely from the amendment lines + the frozen baseline end date (R5.2, R10.3); and
     * the amendment's lines are preserved exactly (count + per-line deltas, order-insensitive), so the
     * stored overlay remains the reproducible record (R10.1, R10.3).
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-06-09-amendment-signing, Property 6")
    void overlayIntegrityOnlyEndDateMutated(
            @ForAll("lines") @Size(max = 8) List<AmendmentLineEntity> lines) {

        List<LineDelta> before = snapshot(lines);
        int netHalfDays = calculator.netHalfDays(lines);
        LocalDate derivedEnd = PROJECT_END_0.plusDays(Math.round(netHalfDays / 2.0));

        Fixture fixture = new Fixture(lines);

        fixture.service.onDocumentFullySigned(DOCUMENT_ID);

        // R5.2 / R10.2 — the start date (and thus every derived planning bar) is never touched.
        assertThat(fixture.project.getStartDate()).isEqualTo(PROJECT_START);

        // R5.2 / R10.3 — the only schedule mutation is endDate, and it is exactly the pure delta
        // derived from the lines + the frozen baseline end date.
        assertThat(fixture.project.getEndDate()).isEqualTo(derivedEnd);

        // R10.1 / R10.3 — the stored line overlay is preserved exactly (the reproducible record).
        List<LineDelta> after = snapshot(fixture.amendment.getLines());
        assertThat(after).containsExactlyInAnyOrderElementsOf(before);
    }

    // --- Line snapshot --------------------------------------------------------------------------

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

    /** Schedule deltas bounded so the shift stays a valid range against the ~20-year project window. */
    @Provide
    Arbitrary<List<AmendmentLineEntity>> lines() {
        Arbitrary<AmendmentLineEntity> schedule =
                Arbitraries.integers().between(-500, 500).map(this::scheduleLine);
        Arbitrary<AmendmentLineEntity> cost = Combinators.combine(
                        Arbitraries.of(ChangeType.ADDITIONAL_WORK, ChangeType.VOLUME_CHANGE),
                        Arbitraries.bigDecimals().between(BigDecimal.valueOf(-50), BigDecimal.valueOf(50)),
                        Arbitraries.bigDecimals().between(BigDecimal.ZERO, BigDecimal.valueOf(1000)))
                .as(this::costLine);
        return Arbitraries.oneOf(schedule, cost).list();
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
        final ProjectEntity project;
        final AmendmentSigningService service;

        Fixture(List<AmendmentLineEntity> lines) {
            project = new ProjectEntity();
            project.setId(1L);
            project.setStartDate(PROJECT_START);
            project.setEndDate(PROJECT_END_0);

            SignableDocumentEntity document = new SignableDocumentEntity();
            DocumentSignatureEntity signature = new DocumentSignatureEntity();
            signature.setSignedAt(LocalDateTime.of(2034, 3, 1, 9, 0));
            document.setSignatures(new ArrayList<>(List.of(signature)));

            amendment = new EstimateAmendmentEntity();
            amendment.setId(900L);
            amendment.setSeqNo(5);
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
