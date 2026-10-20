package com.foremen.service.execution.amendment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import com.foremen.dao.EstimateAmendmentDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.SignableDocumentTypeDao;
import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.AmendmentStatus;
import com.foremen.dao.model.ChangeType;
import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.EstimateAmendmentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;
import com.foremen.dao.model.SignatureLevel;
import com.foremen.dao.model.SignatureMethod;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.document.CreateDocumentRequest;
import com.foremen.service.document.DocumentBodyInput;
import com.foremen.service.document.RequestSignaturesInput;
import com.foremen.service.document.SignableDocumentDto;
import com.foremen.service.document.SignableDocumentService;
import com.foremen.service.model.AmendmentServiceExtendedModel;
import com.foremen.service.model.mapper.AmendmentServiceMapper;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

/**
 * FOR-06-09 (task 4.3) — unit / example coverage of {@link AmendmentSigningService}, the thin
 * reflection + application layer over the FOR-06-08 amendment domain and the FOR-05-08 signing
 * engine. These are plain-object Mockito unit tests (no Spring context): the DAOs, the engine
 * {@link SignableDocumentService}, the mapper, the {@link EntityManager} and the
 * {@link ApplicationEventPublisher} are mocked, while the two persistence-free calculators
 * ({@link AllPartiesSignedDateResolver}, {@link ScheduleDeltaCalculator}) are wired as REAL instances
 * so the real send/reflect arithmetic is exercised — mirroring the sibling property-test fixtures.
 *
 * <p>The property tests ({@code AmendmentReflectionPropertyTest}, {@code AmendmentReopenPropertyTest},
 * {@code EndDateShiftExactlyOncePropertyTest}, …) cover the universal invariants across many inputs;
 * this class pins the specific examples and edge cases the task enumerates:
 *
 * <ul>
 *   <li><b>send-for-signing</b>: empty-amendment reject ({@code error.amendment.emptyNotSignable},
 *       R1.1); non-{@code OPEN} reject exhaustive across the status enum
 *       ({@code error.amendment.notOpen}, R1.5); signer set = CLIENT + MANAGER at the default
 *       {@code AdES} level (R1.4); {@code OPEN → SENT_FOR_SIGNING} with the document linked (R1.3); no
 *       project-activation for the {@code AMENDMENT} type (R1.7); single-in-flight (R3.4);</li>
 *   <li><b>reflection callbacks</b>: full-sign ends {@code EFFECTIVE} immediately (R3.2); reopen
 *       clears the link and preserves the lines (R2.3); an already-{@code EFFECTIVE} re-entry is a
 *       no-op (R3.3);</li>
 *   <li><b>schedule-range edge cases</b>: {@link ScheduleDeltaCalculator#assertValidRange} including
 *       the exact {@code shiftedEnd == startDate} boundary (R5.5).</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 1.1, 1.3, 1.4, 1.5, 1.7, 2.3, 3.2, 3.3, 3.4, 5.5</b>
 */
class AmendmentSigningServiceTest {

    private static final Long AMENDMENT_ID = 500L;
    private static final Long PROJECT_ID = 42L;
    private static final Long DOCUMENT_ID = 7_000L;
    private static final Long AMENDMENT_TYPE_ID = 9L;

    // ===========================================================================================
    // send-for-signing
    // ===========================================================================================

    @Nested
    @DisplayName("sendForSigning")
    class SendForSigning {

        @Test
        @DisplayName("rejects an empty OPEN amendment with error.amendment.emptyNotSignable (R1.1)")
        void rejectsEmptyAmendment() {
            Fixture fixture = Fixture.openAmendment(List.of());

            assertThatThrownBy(() -> fixture.service.sendForSigning(AMENDMENT_ID, null))
                    .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                        assertThat(ex.getMessageCode()).isEqualTo("error.amendment.emptyNotSignable");
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    });

            // No document is created for an empty amendment.
            verify(fixture.signableDocumentService, never()).create(any(CreateDocumentRequest.class));
            assertThat(fixture.amendment.getStatus()).isEqualTo(AmendmentStatus.OPEN);
        }

        @ParameterizedTest(name = "status {0} is rejected with error.amendment.notOpen (R1.5)")
        @EnumSource(value = AmendmentStatus.class, names = "OPEN", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("rejects every non-OPEN status with error.amendment.notOpen (R1.5)")
        void rejectsNonOpenAmendment(AmendmentStatus status) {
            Fixture fixture = Fixture.amendmentWithStatus(status, List.of(scheduleLine(4)));

            assertThatThrownBy(() -> fixture.service.sendForSigning(AMENDMENT_ID, null))
                    .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                        assertThat(ex.getMessageCode()).isEqualTo("error.amendment.notOpen");
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });

            verify(fixture.signableDocumentService, never()).create(any(CreateDocumentRequest.class));
            assertThat(fixture.amendment.getStatus()).isEqualTo(status);
        }

        @Test
        @DisplayName("the non-OPEN reject covers SENT_FOR_SIGNING/SIGNED/EFFECTIVE/DELETED — the four "
                + "statuses leaving a single amendment in flight (R1.5, R3.4)")
        void nonOpenRejectIsExhaustive() {
            EnumSet<AmendmentStatus> rejected =
                    EnumSet.complementOf(EnumSet.of(AmendmentStatus.OPEN));
            assertThat(rejected).containsExactlyInAnyOrder(
                    AmendmentStatus.SENT_FOR_SIGNING,
                    AmendmentStatus.SIGNED,
                    AmendmentStatus.EFFECTIVE,
                    AmendmentStatus.DELETED);

            // An amendment already SENT_FOR_SIGNING cannot be re-sent, so at most one is ever in
            // flight at a time (the single-in-flight guard, R3.4).
            Fixture fixture =
                    Fixture.amendmentWithStatus(AmendmentStatus.SENT_FOR_SIGNING, List.of(scheduleLine(2)));
            assertThatThrownBy(() -> fixture.service.sendForSigning(AMENDMENT_ID, null))
                    .isInstanceOf(ForemenApiException.class);
            verify(fixture.signableDocumentService, never()).create(any(CreateDocumentRequest.class));
        }

        @Test
        @DisplayName("404s when the amendment cannot be resolved")
        void missingAmendmentIs404() {
            Fixture fixture = Fixture.openAmendment(List.of(scheduleLine(2)));
            when(fixture.amendmentDao.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> fixture.service.sendForSigning(999L, null))
                    .isInstanceOfSatisfying(ForemenApiException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("requests signatures from CLIENT + MANAGER at the default AdES level when no "
                + "options are supplied (R1.4)")
        void signerSetIsClientAndManagerWithDefaultAdES() {
            Fixture fixture = Fixture.openAmendment(List.of(scheduleLine(2)));

            fixture.service.sendForSigning(AMENDMENT_ID, null);

            ArgumentCaptor<RequestSignaturesInput> captor =
                    ArgumentCaptor.forClass(RequestSignaturesInput.class);
            verify(fixture.signableDocumentService).requestSignatures(eq(DOCUMENT_ID), captor.capture());

            RequestSignaturesInput sent = captor.getValue();
            assertThat(sent.level()).isEqualTo(SignatureLevel.AdES);
            assertThat(sent.method()).isEqualTo(SendForSigningOptions.DEFAULT_METHOD);
            assertThat(sent.signers())
                    .extracting(RequestSignaturesInput.SignerInput::signerRole)
                    .containsExactlyInAnyOrder("CLIENT", "MANAGER");
            // Signers are declared by role (resolved at signing time), not by explicit user.
            assertThat(sent.signers())
                    .allSatisfy(s -> assertThat(s.signerUserId()).isNull());
        }

        @Test
        @DisplayName("honors an explicit method/level override from the send dialog (R1.4)")
        void honorsExplicitOverride() {
            Fixture fixture = Fixture.openAmendment(List.of(scheduleLine(2)));

            fixture.service.sendForSigning(
                    AMENDMENT_ID, new SendForSigningOptions(SignatureMethod.PRINT, SignatureLevel.QES));

            ArgumentCaptor<RequestSignaturesInput> captor =
                    ArgumentCaptor.forClass(RequestSignaturesInput.class);
            verify(fixture.signableDocumentService).requestSignatures(eq(DOCUMENT_ID), captor.capture());
            assertThat(captor.getValue().method()).isEqualTo(SignatureMethod.PRINT);
            assertThat(captor.getValue().level()).isEqualTo(SignatureLevel.QES);
        }

        @Test
        @DisplayName("creates an AMENDMENT-type document on the amendment's project, referencing the "
                + "amendment by sourceRef, then saves a body before requesting signatures (R1.2)")
        void createsAmendmentDocumentAndBodyThenRequestsSignatures() {
            Fixture fixture = Fixture.openAmendment(List.of(scheduleLine(2)));

            fixture.service.sendForSigning(AMENDMENT_ID, null);

            ArgumentCaptor<CreateDocumentRequest> createCaptor =
                    ArgumentCaptor.forClass(CreateDocumentRequest.class);
            verify(fixture.signableDocumentService).create(createCaptor.capture());
            CreateDocumentRequest req = createCaptor.getValue();
            assertThat(req.projectId()).isEqualTo(PROJECT_ID);
            assertThat(req.documentTypeId()).isEqualTo(AMENDMENT_TYPE_ID);
            assertThat(req.sourceRef()).isEqualTo(String.valueOf(AMENDMENT_ID));

            verify(fixture.signableDocumentService).saveBody(eq(DOCUMENT_ID), any(DocumentBodyInput.class));
            verify(fixture.signableDocumentService).requestSignatures(eq(DOCUMENT_ID), any());
        }

        @Test
        @DisplayName("transitions OPEN → SENT_FOR_SIGNING and links the created document (R1.3)")
        void transitionsToSentForSigningAndLinksDocument() {
            Fixture fixture = Fixture.openAmendment(List.of(scheduleLine(2)));

            fixture.service.sendForSigning(AMENDMENT_ID, null);

            assertThat(fixture.amendment.getStatus()).isEqualTo(AmendmentStatus.SENT_FOR_SIGNING);
            assertThat(fixture.amendment.getSignableDocument()).isSameAs(fixture.linkedDocument);
        }

        @Test
        @DisplayName("publishes a SENT_FOR_SIGNING lifecycle event for the amendment (R7.3)")
        void publishesSentForSigningLifecycleEvent() {
            Fixture fixture = Fixture.openAmendment(List.of(scheduleLine(2)));

            fixture.service.sendForSigning(AMENDMENT_ID, null);

            ArgumentCaptor<AmendmentLifecycleEvent> captor =
                    ArgumentCaptor.forClass(AmendmentLifecycleEvent.class);
            verify(fixture.eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().trigger())
                    .isEqualTo(AmendmentLifecycleEvent.Trigger.SENT_FOR_SIGNING);
            assertThat(captor.getValue().amendmentId()).isEqualTo(AMENDMENT_ID);
            assertThat(captor.getValue().projectId()).isEqualTo(PROJECT_ID);
        }

        @Test
        @DisplayName("never triggers the FOR-05-08 project-activation hand-off — the AMENDMENT type is "
                + "not CONTRACT_*, so Project.status is never touched (R1.7)")
        void neverActivatesTheProject() {
            Fixture fixture = Fixture.openAmendment(List.of(scheduleLine(2)));
            ProjectStatus statusBefore = fixture.project.getStatus();

            fixture.service.sendForSigning(AMENDMENT_ID, null);

            // The service drives the engine by document id only; it never reads or writes the project
            // status (the AMENDMENT type's code is not CONTRACT_*, so the engine's activation signal
            // simply never fires — nothing here to suppress). The project status is left untouched.
            assertThat(fixture.project.getStatus()).isEqualTo(statusBefore);
            // The created document type is the AMENDMENT type (code = "AMENDMENT", not CONTRACT_*).
            assertThat(fixture.amendmentType.getCode()).doesNotStartWith("CONTRACT_");
        }
    }

    // ===========================================================================================
    // onDocumentFullySigned — reflect SIGNED onto the amendment and make it EFFECTIVE
    // ===========================================================================================

    @Nested
    @DisplayName("onDocumentFullySigned")
    class OnDocumentFullySigned {

        @Test
        @DisplayName("a SENT_FOR_SIGNING amendment ends EFFECTIVE immediately with the effectiveDate "
                + "set to the all-parties-signed date (R3.2)")
        void fullSignEndsEffectiveImmediately() {
            LocalDateTime earlier = LocalDateTime.of(2025, 3, 1, 9, 0);
            LocalDateTime latest = LocalDateTime.of(2025, 3, 4, 17, 30);
            Fixture fixture = Fixture.sentForSigning(List.of(scheduleLine(0)), List.of(earlier, latest));

            fixture.service.onDocumentFullySigned(DOCUMENT_ID);

            assertThat(fixture.amendment.getStatus()).isEqualTo(AmendmentStatus.EFFECTIVE);
            assertThat(fixture.amendment.getEffectiveDate()).isEqualTo(latest.toLocalDate());
        }

        @Test
        @DisplayName("shifts Project.endDate by the net schedule delta (halfDays/2 rounded) on the "
                + "SIGNED → EFFECTIVE edge (R5.1)")
        void shiftsEndDateByNetScheduleDelta() {
            // +5 half-days ⇒ round(5/2) = 3 days later (one SCHEDULE_CHANGE line).
            Fixture fixture = Fixture.sentForSigning(
                    List.of(scheduleLine(5)), List.of(LocalDateTime.of(2025, 1, 1, 12, 0)));
            LocalDate end0 = fixture.project.getEndDate();

            fixture.service.onDocumentFullySigned(DOCUMENT_ID);

            assertThat(fixture.project.getEndDate()).isEqualTo(end0.plusDays(3));
        }

        @Test
        @DisplayName("leaves Project.endDate unchanged when there is no SCHEDULE_CHANGE line (R5.3)")
        void leavesEndDateUnchangedWithoutScheduleLine() {
            Fixture fixture = Fixture.sentForSigning(
                    List.of(costLine(ChangeType.ADDITIONAL_WORK)),
                    List.of(LocalDateTime.of(2025, 1, 1, 12, 0)));
            LocalDate end0 = fixture.project.getEndDate();

            fixture.service.onDocumentFullySigned(DOCUMENT_ID);

            assertThat(fixture.project.getEndDate()).isEqualTo(end0);
            assertThat(fixture.amendment.getStatus()).isEqualTo(AmendmentStatus.EFFECTIVE);
        }

        @Test
        @DisplayName("an already-EFFECTIVE amendment is a no-op — status, effectiveDate and endDate "
                + "are unchanged on re-entry (R3.3)")
        void effectiveReentryIsNoOp() {
            Fixture fixture = Fixture.sentForSigning(
                    List.of(scheduleLine(6)), List.of(LocalDateTime.of(2025, 1, 1, 12, 0)));

            // First application drives it EFFECTIVE and shifts the end date once.
            fixture.service.onDocumentFullySigned(DOCUMENT_ID);
            LocalDate endAfterFirst = fixture.project.getEndDate();
            LocalDate effectiveAfterFirst = fixture.amendment.getEffectiveDate();

            // Re-entry while already EFFECTIVE must change nothing.
            fixture.service.onDocumentFullySigned(DOCUMENT_ID);

            assertThat(fixture.amendment.getStatus()).isEqualTo(AmendmentStatus.EFFECTIVE);
            assertThat(fixture.amendment.getEffectiveDate()).isEqualTo(effectiveAfterFirst);
            assertThat(fixture.project.getEndDate()).isEqualTo(endAfterFirst);
        }

        @Test
        @DisplayName("an unlinked document id is a no-op")
        void unlinkedDocumentIsNoOp() {
            Fixture fixture = Fixture.sentForSigning(
                    List.of(scheduleLine(2)), List.of(LocalDateTime.of(2025, 1, 1, 12, 0)));
            when(fixture.amendmentDao.findBySignableDocumentId(123_456L)).thenReturn(Optional.empty());

            assertThatCode(() -> fixture.service.onDocumentFullySigned(123_456L))
                    .doesNotThrowAnyException();
            assertThat(fixture.amendment.getStatus()).isEqualTo(AmendmentStatus.SENT_FOR_SIGNING);
        }
    }

    // ===========================================================================================
    // onDocumentReopened — decline/void returns the amendment to OPEN
    // ===========================================================================================

    @Nested
    @DisplayName("onDocumentReopened")
    class OnDocumentReopened {

        @Test
        @DisplayName("reopens to OPEN, clears the document link, and preserves the lines (R2.3)")
        void reopensClearsLinkPreservesLines() {
            List<AmendmentLineEntity> lines =
                    List.of(scheduleLine(4), costLine(ChangeType.VOLUME_CHANGE));
            Fixture fixture = Fixture.sentForSigning(lines, List.of(LocalDateTime.of(2025, 1, 1, 12, 0)));

            fixture.service.onDocumentReopened(DOCUMENT_ID);

            assertThat(fixture.amendment.getStatus()).isEqualTo(AmendmentStatus.OPEN);
            assertThat(fixture.amendment.getSignableDocument()).isNull();
            assertThat(fixture.amendment.getLines()).hasSize(2);
            assertThat(fixture.amendment.getLines())
                    .extracting(AmendmentLineEntity::getChangeType)
                    .containsExactlyInAnyOrder(ChangeType.SCHEDULE_CHANGE, ChangeType.VOLUME_CHANGE);
        }

        @Test
        @DisplayName("does not shift Project.endDate on reopen (no delta is applied)")
        void reopenDoesNotShiftEndDate() {
            Fixture fixture = Fixture.sentForSigning(
                    List.of(scheduleLine(10)), List.of(LocalDateTime.of(2025, 1, 1, 12, 0)));
            LocalDate end0 = fixture.project.getEndDate();

            fixture.service.onDocumentReopened(DOCUMENT_ID);

            assertThat(fixture.project.getEndDate()).isEqualTo(end0);
        }

        @Test
        @DisplayName("publishes a REOPENED lifecycle event (R7.3)")
        void publishesReopenedLifecycleEvent() {
            Fixture fixture = Fixture.sentForSigning(
                    List.of(scheduleLine(2)), List.of(LocalDateTime.of(2025, 1, 1, 12, 0)));

            fixture.service.onDocumentReopened(DOCUMENT_ID);

            ArgumentCaptor<AmendmentLifecycleEvent> captor =
                    ArgumentCaptor.forClass(AmendmentLifecycleEvent.class);
            verify(fixture.eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().trigger())
                    .isEqualTo(AmendmentLifecycleEvent.Trigger.REOPENED);
        }

        @Test
        @DisplayName("is a no-op unless the amendment is SENT_FOR_SIGNING")
        void noOpUnlessSentForSigning() {
            Fixture fixture = Fixture.amendmentWithStatus(
                    AmendmentStatus.EFFECTIVE, List.of(scheduleLine(2)));
            when(fixture.amendmentDao.findBySignableDocumentId(DOCUMENT_ID))
                    .thenReturn(Optional.of(fixture.amendment));
            fixture.amendment.setSignableDocument(fixture.linkedDocument);

            fixture.service.onDocumentReopened(DOCUMENT_ID);

            assertThat(fixture.amendment.getStatus()).isEqualTo(AmendmentStatus.EFFECTIVE);
            assertThat(fixture.amendment.getSignableDocument()).isSameAs(fixture.linkedDocument);
            verify(fixture.eventPublisher, never()).publishEvent(any());
        }
    }

    // ===========================================================================================
    // ScheduleDeltaCalculator.assertValidRange — range-guard edge cases (R5.5)
    // ===========================================================================================

    @Nested
    @DisplayName("ScheduleDeltaCalculator.assertValidRange edge cases (R5.5)")
    class AssertValidRangeEdgeCases {

        private final ScheduleDeltaCalculator calculator = new ScheduleDeltaCalculator();

        @Test
        @DisplayName("the exact boundary shiftedEnd == startDate is a VALID zero-length range")
        void exactBoundaryIsValid() {
            LocalDate start = LocalDate.of(2025, 6, 1);

            assertThatCode(() -> calculator.assertValidRange(start, start))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("shiftedEnd one day after startDate is valid")
        void oneDayAfterStartIsValid() {
            LocalDate start = LocalDate.of(2025, 6, 1);

            assertThatCode(() -> calculator.assertValidRange(start, start.plusDays(1)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("shiftedEnd one day before startDate is rejected with 409 "
                + "error.amendment.endDateInvalid")
        void oneDayBeforeStartIsRejected() {
            LocalDate start = LocalDate.of(2025, 6, 1);

            assertThatThrownBy(() -> calculator.assertValidRange(start, start.minusDays(1)))
                    .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                        assertThat(ex.getMessageCode()).isEqualTo("error.amendment.endDateInvalid");
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }

        @Test
        @DisplayName("a full-sign whose net shortening would drive the end date before the start date "
                + "is rejected and leaves the amendment still SENT_FOR_SIGNING (R5.5)")
        void fullSignRejectsInvalidShortening() {
            // startDate = 2024-12-25, endDate = 2025-01-01 ⇒ only 7 days of schedule remain.
            // -40 half-days ⇒ -20 days ⇒ 2024-12-12, which is before the start date ⇒ reject.
            Fixture fixture = Fixture.sentForSigning(
                    List.of(scheduleLine(-40)),
                    List.of(LocalDateTime.of(2025, 1, 1, 12, 0)),
                    LocalDate.of(2024, 12, 25),
                    LocalDate.of(2025, 1, 1));

            assertThatThrownBy(() -> fixture.service.onDocumentFullySigned(DOCUMENT_ID))
                    .isInstanceOfSatisfying(ForemenApiException.class,
                            ex -> assertThat(ex.getMessageCode())
                                    .isEqualTo("error.amendment.endDateInvalid"));

            // The guard runs before the single Project.endDate write, so the end date is untouched.
            assertThat(fixture.project.getEndDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        }
    }

    // ===========================================================================================
    // line builders
    // ===========================================================================================

    private static AmendmentLineEntity scheduleLine(int halfDays) {
        AmendmentLineEntity line = new AmendmentLineEntity();
        line.setChangeType(ChangeType.SCHEDULE_CHANGE);
        line.setScheduleDeltaHalfDays(halfDays);
        return line;
    }

    private static AmendmentLineEntity costLine(ChangeType type) {
        AmendmentLineEntity line = new AmendmentLineEntity();
        line.setChangeType(type);
        line.setDeltaQuantity(BigDecimal.ONE);
        line.setExtraUnitPrice(BigDecimal.TEN);
        return line;
    }

    // ===========================================================================================
    // fixture — a service wired with mocked collaborators + the two real calculators
    // ===========================================================================================

    private static final class Fixture {

        final EstimateAmendmentEntity amendment;
        final ProjectEntity project;
        final SignableDocumentTypeEntity amendmentType;
        final SignableDocumentEntity linkedDocument;
        final EstimateAmendmentDao amendmentDao;
        final SignableDocumentService signableDocumentService;
        final ApplicationEventPublisher eventPublisher;
        final AmendmentSigningService service;

        /** An {@code OPEN} amendment ready to be sent for signing (no linked document yet). */
        static Fixture openAmendment(List<AmendmentLineEntity> lines) {
            return new Fixture(AmendmentStatus.OPEN, lines, null,
                    LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1), false);
        }

        /** An amendment pinned to an arbitrary status (for the non-OPEN reject matrix). */
        static Fixture amendmentWithStatus(AmendmentStatus status, List<AmendmentLineEntity> lines) {
            return new Fixture(status, lines, null,
                    LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1), false);
        }

        /** A {@code SENT_FOR_SIGNING} amendment linked to an all-{@code SIGNED} document. */
        static Fixture sentForSigning(List<AmendmentLineEntity> lines, List<LocalDateTime> signedAt) {
            return new Fixture(AmendmentStatus.SENT_FOR_SIGNING, lines, signedAt,
                    LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1), true);
        }

        /** A {@code SENT_FOR_SIGNING} fixture with an explicit project window (range-guard tests). */
        static Fixture sentForSigning(List<AmendmentLineEntity> lines, List<LocalDateTime> signedAt,
                                      LocalDate startDate, LocalDate endDate) {
            return new Fixture(AmendmentStatus.SENT_FOR_SIGNING, lines, signedAt,
                    startDate, endDate, true);
        }

        private Fixture(AmendmentStatus status,
                        List<AmendmentLineEntity> lines,
                        List<LocalDateTime> signedAt,
                        LocalDate startDate,
                        LocalDate endDate,
                        boolean linkDocument) {
            project = new ProjectEntity();
            project.setId(PROJECT_ID);
            project.setStartDate(startDate);
            project.setEndDate(endDate);

            amendment = new EstimateAmendmentEntity();
            amendment.setId(AMENDMENT_ID);
            amendment.setSeqNo(1);
            amendment.setProject(project);
            amendment.setStatus(status);
            amendment.setLines(new ArrayList<>(lines));
            for (AmendmentLineEntity line : lines) {
                line.setAmendment(amendment);
            }

            // The all-SIGNED document the reflection reads (only when the amendment is linked).
            linkedDocument = new SignableDocumentEntity();
            linkedDocument.setStatus(DocumentStatus.SIGNED);
            if (signedAt != null) {
                List<DocumentSignatureEntity> signatures = new ArrayList<>(signedAt.size());
                for (LocalDateTime at : signedAt) {
                    DocumentSignatureEntity signature = new DocumentSignatureEntity();
                    signature.setSignedAt(at);
                    signatures.add(signature);
                }
                linkedDocument.setSignatures(signatures);
            }
            if (linkDocument) {
                amendment.setSignableDocument(linkedDocument);
            }

            amendmentType = new SignableDocumentTypeEntity();
            amendmentType.setId(AMENDMENT_TYPE_ID);
            amendmentType.setCode("AMENDMENT");
            amendmentType.setNamePL("Aneks");

            amendmentDao = mock(EstimateAmendmentDao.class);
            when(amendmentDao.findById(AMENDMENT_ID)).thenReturn(Optional.of(amendment));
            when(amendmentDao.findBySignableDocumentId(DOCUMENT_ID)).thenReturn(Optional.of(amendment));
            when(amendmentDao.save(any(EstimateAmendmentEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            signableDocumentService = mock(SignableDocumentService.class);
            when(signableDocumentService.create(any(CreateDocumentRequest.class)))
                    .thenReturn(documentDto(DOCUMENT_ID));

            SignableDocumentTypeDao documentTypeDao = mock(SignableDocumentTypeDao.class);
            when(documentTypeDao.findByCode("AMENDMENT")).thenReturn(Optional.of(amendmentType));

            ProjectMemberDao projectMemberDao = mock(ProjectMemberDao.class);
            when(projectMemberDao.findByProjectId(anyLong())).thenReturn(List.of());

            AmendmentServiceMapper serviceMapper = mock(AmendmentServiceMapper.class);
            when(serviceMapper.toServiceExtendedModel(any(EstimateAmendmentEntity.class)))
                    .thenReturn(mock(AmendmentServiceExtendedModel.class));

            eventPublisher = mock(ApplicationEventPublisher.class);

            EntityManager entityManager = mock(EntityManager.class);
            when(entityManager.getReference(eq(SignableDocumentEntity.class), eq(DOCUMENT_ID)))
                    .thenReturn(linkedDocument);

            service = new AmendmentSigningService(
                    amendmentDao,
                    signableDocumentService,
                    documentTypeDao,
                    projectMemberDao,
                    new AllPartiesSignedDateResolver(),
                    new ScheduleDeltaCalculator(),
                    serviceMapper,
                    eventPublisher,
                    entityManager);
        }

        private static SignableDocumentDto documentDto(Long id) {
            return new SignableDocumentDto(id, PROJECT_ID, "AMENDMENT", DocumentStatus.DRAFT,
                    SignatureLevel.AdES, "Amendment #1", String.valueOf(AMENDMENT_ID), null,
                    null, null, LocalDateTime.now(), List.of(), List.of(), List.of(), null);
        }
    }
}
