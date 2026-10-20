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

import org.springframework.context.ApplicationEventPublisher;

/**
 * FOR-06-09 (Requirements 2.2, 3.2) — property-based coverage of the full-sign reflection in
 * {@link AmendmentSigningService#onDocumentFullySigned(Long)}: for <em>any</em> amendment that is
 * {@code SENT_FOR_SIGNING} and linked to an all-{@code SIGNED} document (every signature carrying a
 * {@code signedAt}), a single reflection call drives the amendment to {@code EFFECTIVE} with a set
 * {@code effectiveDate} equal to the all-parties-signed date (design §"Signed → effective →
 * apply-deltas sequence").
 *
 * <p>The DAOs, the engine {@link SignableDocumentService}, the mapper, the {@link EntityManager} and
 * the {@link ApplicationEventPublisher} are mocked so this is a fast, persistence-free unit-level
 * property: the callback path reads only the linked amendment + its document signatures, mutates the
 * amendment's status/effectiveDate, applies the pure {@link ScheduleDeltaCalculator} shift, and
 * publishes a lifecycle event — none of which needs a persistence context. The two pure calculators
 * are wired as REAL instances so the reflection's real arithmetic is exercised.
 *
 * <p><b>Property 1: Full-sign reflects onto the amendment as EFFECTIVE.</b> A {@code SENT_FOR_SIGNING}
 * amendment linked to an all-{@code SIGNED} document ends {@code EFFECTIVE} with a set
 * {@code effectiveDate} (= {@code max(signedAt).toLocalDate()}) after {@code onDocumentFullySigned}.
 *
 * <p>Feature: FOR-06-09-amendment-signing, Property 1
 *
 * <p><b>Validates: Requirements 2.2, 3.2</b>
 */
@Tag("Feature: FOR-06-09-amendment-signing, Property 1")
class AmendmentReflectionPropertyTest {

    private static final Long DOCUMENT_ID = 7_000L;

    // --- Property 1: SENT_FOR_SIGNING + all-SIGNED document ⇒ EFFECTIVE + effectiveDate set ------

    /**
     * // Feature: FOR-06-09-amendment-signing, Property 1
     *
     * <p>For any non-empty signature set and any set of schedule/cost lines, reflecting a full-sign
     * onto a {@code SENT_FOR_SIGNING} amendment ends it {@code EFFECTIVE} (R2.2, R3.2) with its
     * {@code effectiveDate} set to the latest signature completion date (R3.1 supporting R3.2).
     */
    @Property(tries = 200)
    @Tag("Feature: FOR-06-09-amendment-signing, Property 1")
    void fullSignEndsEffectiveWithEffectiveDate(
            @ForAll("signedAtLists") List<LocalDateTime> signedAtTimes,
            @ForAll("lines") List<AmendmentLineEntity> lines) {

        Fixture fixture = new Fixture(signedAtTimes, lines);
        LocalDate expectedEffectiveDate = signedAtTimes.stream()
                .max(LocalDateTime::compareTo)
                .orElseThrow()
                .toLocalDate();

        fixture.service.onDocumentFullySigned(DOCUMENT_ID);

        assertThat(fixture.amendment.getStatus()).isEqualTo(AmendmentStatus.EFFECTIVE);
        assertThat(fixture.amendment.getEffectiveDate()).isEqualTo(expectedEffectiveDate);
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
        Arbitrary<AmendmentLineEntity> schedule = scheduleHalfDays().map(this::scheduleLine);
        Arbitrary<AmendmentLineEntity> cost =
                Arbitraries.of(ChangeType.ADDITIONAL_WORK, ChangeType.VOLUME_CHANGE).map(this::costLine);
        return Arbitraries.oneOf(schedule, cost).list().ofMaxSize(6);
    }

    @Provide
    Arbitrary<Integer> scheduleHalfDays() {
        return Arbitraries.integers().between(-200, 200);
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

    /**
     * A {@code SENT_FOR_SIGNING} amendment linked to an all-{@code SIGNED} document, with the DAO
     * stubbed to resolve it by document id and echo on save, wired into a real-calculator
     * {@link AmendmentSigningService}. The project is anchored with a start/end date far enough apart
     * that any generated schedule shift stays a valid range (this property is about the status +
     * effectiveDate, not the range guard).
     */
    private static final class Fixture {
        final EstimateAmendmentEntity amendment;
        final AmendmentSigningService service;

        Fixture(List<LocalDateTime> signedAtTimes, List<AmendmentLineEntity> lines) {
            ProjectEntity project = new ProjectEntity();
            project.setId(1L);
            project.setStartDate(LocalDate.of(2000, 1, 1));
            project.setEndDate(LocalDate.of(2200, 1, 1));

            SignableDocumentEntity document = new SignableDocumentEntity();
            document.setSignatures(signaturesFor(signedAtTimes));

            amendment = new EstimateAmendmentEntity();
            amendment.setId(500L);
            amendment.setSeqNo(1);
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
