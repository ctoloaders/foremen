package com.foremen.service.execution.amendment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

import org.springframework.context.ApplicationEventPublisher;

/**
 * FOR-06-09 (Requirements 3.3, 3.5) — property-based coverage of the idempotence of the
 * {@code SIGNED → EFFECTIVE} reflection in
 * {@link AmendmentSigningService#onDocumentFullySigned(Long)}: once an amendment is {@code EFFECTIVE},
 * re-processing the same full-sign event any number of extra times is a no-op — the amendment's
 * {@code (status, effectiveDate, endDate)} observable triple never changes after the first call, so
 * the deltas are never re-applied and the end date is never re-shifted (design §Key-decision-4).
 *
 * <p>The DAOs, the engine, the mapper, the {@link EntityManager} and the
 * {@link ApplicationEventPublisher} are mocked; the two pure calculators are real. The status guard in
 * {@code onDocumentFullySigned} (no-op unless {@code SENT_FOR_SIGNING}) is what makes re-entry safe,
 * so this property drives N≥1 calls and asserts the triple is stable from the first.
 *
 * <p><b>Property 3: SIGNED → EFFECTIVE is idempotent.</b> Applying {@code onDocumentFullySigned} N≥1
 * times yields the same {@code (status, effectiveDate, endDate)}.
 *
 * <p>Feature: FOR-06-09-amendment-signing, Property 3
 *
 * <p><b>Validates: Requirements 3.3, 3.5</b>
 */
@Tag("Feature: FOR-06-09-amendment-signing, Property 3")
class AmendmentEffectIdempotencePropertyTest {

    private static final Long DOCUMENT_ID = 9_000L;

    // --- Property 3: the observable (status, effectiveDate, endDate) is stable across N calls ----

    /**
     * // Feature: FOR-06-09-amendment-signing, Property 3
     *
     * <p>For any non-empty signature set, any schedule/cost lines, and any {@code N ≥ 1}, applying the
     * full-sign reflection {@code N} times leaves the amendment's {@code (status, effectiveDate)} and
     * the project's {@code endDate} identical to their values after the first application — the effect
     * is applied exactly once and re-processing is a no-op (Requirements 3.3, 3.5).
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-06-09-amendment-signing, Property 3")
    void repeatedFullSignIsIdempotent(
            @ForAll("signedAtLists") List<LocalDateTime> signedAtTimes,
            @ForAll("lines") List<AmendmentLineEntity> lines,
            @ForAll @IntRange(min = 1, max = 6) int applications) {

        Fixture fixture = new Fixture(signedAtTimes, lines);

        fixture.service.onDocumentFullySigned(DOCUMENT_ID);

        AmendmentStatus statusAfterFirst = fixture.amendment.getStatus();
        LocalDate effectiveDateAfterFirst = fixture.amendment.getEffectiveDate();
        LocalDate endDateAfterFirst = fixture.project.getEndDate();

        for (int i = 1; i < applications; i++) {
            fixture.service.onDocumentFullySigned(DOCUMENT_ID);
        }

        assertThat(fixture.amendment.getStatus()).isEqualTo(statusAfterFirst);
        assertThat(fixture.amendment.getEffectiveDate()).isEqualTo(effectiveDateAfterFirst);
        assertThat(fixture.project.getEndDate()).isEqualTo(endDateAfterFirst);

        // And the first application did reach the terminal EFFECTIVE state with a set date.
        assertThat(statusAfterFirst).isEqualTo(AmendmentStatus.EFFECTIVE);
        assertThat(effectiveDateAfterFirst).isNotNull();
    }

    // --- Generators -----------------------------------------------------------------------------

    @Provide
    Arbitrary<List<LocalDateTime>> signedAtLists() {
        long min = LocalDateTime.of(2001, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC);
        long max = LocalDateTime.of(2099, 12, 31, 23, 59).toEpochSecond(ZoneOffset.UTC);
        Arbitrary<LocalDateTime> element = Arbitraries.longs()
                .between(min, max)
                .map(epochSecond -> LocalDateTime.ofEpochSecond(epochSecond, 0, ZoneOffset.UTC));
        return element.list().ofMinSize(1).ofMaxSize(6);
    }

    @Provide
    Arbitrary<List<AmendmentLineEntity>> lines() {
        Arbitrary<AmendmentLineEntity> schedule =
                Arbitraries.integers().between(-200, 200).map(this::scheduleLine);
        Arbitrary<AmendmentLineEntity> cost =
                Arbitraries.of(ChangeType.ADDITIONAL_WORK, ChangeType.VOLUME_CHANGE).map(this::costLine);
        return Arbitraries.oneOf(schedule, cost).list().ofMaxSize(6);
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

        Fixture(List<LocalDateTime> signedAtTimes, List<AmendmentLineEntity> lines) {
            project = new ProjectEntity();
            project.setId(1L);
            // Wide range so any generated schedule shift stays valid (range guard is Property 4's job).
            project.setStartDate(LocalDate.of(2000, 1, 1));
            project.setEndDate(LocalDate.of(2200, 1, 1));

            SignableDocumentEntity document = new SignableDocumentEntity();
            document.setSignatures(signaturesFor(signedAtTimes));

            amendment = new EstimateAmendmentEntity();
            amendment.setId(700L);
            amendment.setSeqNo(3);
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

        private static List<DocumentSignatureEntity> signaturesFor(List<LocalDateTime> signedAtTimes) {
            List<DocumentSignatureEntity> signatures = new ArrayList<>(signedAtTimes.size());
            for (LocalDateTime signedAt : signedAtTimes) {
                DocumentSignatureEntity signature = new DocumentSignatureEntity();
                signature.setSignedAt(signedAt);
                signatures.add(signature);
            }
            return signatures;
        }
    }
}
