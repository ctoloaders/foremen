package com.foremen.service.execution.amendment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.EstimateAmendmentDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.SignableDocumentTypeDao;
import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.AmendmentStatus;
import com.foremen.dao.model.EstimateAmendmentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.document.CreateDocumentRequest;
import com.foremen.service.document.DocumentBodyInput;
import com.foremen.service.document.RequestSignaturesInput;
import com.foremen.service.document.SignableDocumentDto;
import com.foremen.service.document.SignableDocumentService;
import com.foremen.service.model.AmendmentServiceExtendedModel;
import com.foremen.service.model.mapper.AmendmentServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-09 (Requirements 1–3, 5, 10; design §{@code AmendmentSigningService}) — the single owner of
 * this spec's business logic: the thin <b>reflection + application</b> layer that drives an already
 * FOR-06-08-authored {@code OPEN} amendment through its remaining {@link AmendmentStatus} transitions
 * by riding the existing FOR-05-08 signing engine, then applies the amendment's stored deltas.
 *
 * <p>It composes three collaborators and owns no new signing lifecycle:
 * <ul>
 *   <li>the FOR-06-08 amendment domain ({@link EstimateAmendmentDao}, read + the three transitions
 *       FOR-06-08 left to this spec) — it never re-implements the open-amendment authoring;</li>
 *   <li>the FOR-05-08 {@link SignableDocumentService} engine ({@code create} → {@code saveBody} →
 *       {@code requestSignatures}) — it never reads or writes {@code DocumentSignature} rows
 *       directly, the linked document is the single source of truth for signing progress (R2.1);</li>
 *   <li>the two persistence-free calculators {@link AllPartiesSignedDateResolver} (the
 *       all-parties-signed date, R3.1) and {@link ScheduleDeltaCalculator} (the net schedule delta →
 *       {@code Project.endDate} shift, R5).</li>
 * </ul>
 *
 * <p><b>Overlay, never mutate (R10).</b> Becoming {@code EFFECTIVE} mutates <em>nothing</em> in the
 * estimate / line / room-qty / work-report rows, nothing in {@code Project.startDate}, and no
 * {@code ProjectScheduleBarEntity}. The <b>only</b> schedule write anywhere is the single
 * {@code Project.endDate} shift, applied exactly once on the {@code SIGNED → EFFECTIVE} edge (R5.4).
 * Price/volume effects flow only through the unchanged FOR-06-08 {@code resolveClientUnitPrice} seam,
 * which this spec does not touch — it merely makes an amendment reach {@code EFFECTIVE} so the seam
 * starts contributing (R4.1).
 *
 * <p>Each owned transition publishes an {@link AmendmentLifecycleEvent} consumed after-commit by
 * {@link AmendmentNotificationEmitter}; a notification failure can therefore never roll back the
 * transition (R7.6).
 */
@Service
@Transactional
public class AmendmentSigningService {

    /** 404 when the referenced amendment cannot be resolved. */
    static final String NOT_FOUND = "error.entity.not.found";

    /** 409 when a send targets an amendment that is not {@code OPEN} (R1.5). */
    static final String NOT_OPEN = "error.amendment.notOpen";

    /** 422 when a send targets an {@code OPEN} amendment with no lines (R1.1). */
    static final String EMPTY_NOT_SIGNABLE = "error.amendment.emptyNotSignable";

    /** The FOR-05-08 {@code SignableDocumentType} code bound to an amendment document (R1.2). */
    static final String AMENDMENT_TYPE_CODE = "AMENDMENT";

    /** The signer roles of an amendment document: project CLIENT + executor MANAGER (R1.4). */
    static final String SIGNER_ROLE_CLIENT = "CLIENT";
    static final String SIGNER_ROLE_MANAGER = "MANAGER";

    /** The recipient roles of the amendment-lifecycle notifications: owner MANAGER + FINANCIER (R7.3). */
    private static final String RECIPIENT_ROLE_MANAGER = "MANAGER";
    private static final String RECIPIENT_ROLE_FINANCIER = "FINANCIER";

    private final EstimateAmendmentDao amendmentDao;
    private final SignableDocumentService signableDocumentService;
    private final SignableDocumentTypeDao documentTypeDao;
    private final ProjectMemberDao projectMemberDao;
    private final AllPartiesSignedDateResolver allPartiesSignedDateResolver;
    private final ScheduleDeltaCalculator scheduleDeltaCalculator;
    private final AmendmentServiceMapper serviceMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager entityManager;

    public AmendmentSigningService(EstimateAmendmentDao amendmentDao,
                                   SignableDocumentService signableDocumentService,
                                   SignableDocumentTypeDao documentTypeDao,
                                   ProjectMemberDao projectMemberDao,
                                   AllPartiesSignedDateResolver allPartiesSignedDateResolver,
                                   ScheduleDeltaCalculator scheduleDeltaCalculator,
                                   AmendmentServiceMapper serviceMapper,
                                   ApplicationEventPublisher eventPublisher,
                                   EntityManager entityManager) {
        this.amendmentDao = amendmentDao;
        this.signableDocumentService = signableDocumentService;
        this.documentTypeDao = documentTypeDao;
        this.projectMemberDao = projectMemberDao;
        this.allPartiesSignedDateResolver = allPartiesSignedDateResolver;
        this.scheduleDeltaCalculator = scheduleDeltaCalculator;
        this.serviceMapper = serviceMapper;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
    }

    // --- Send for signing (Requirement 1) ------------------------------------------------------

    /**
     * Sends the project's {@code OPEN} amendment for signing (Requirement 1): asserts it is
     * {@code OPEN} (R1.5) and non-empty (R1.1); creates an {@code AMENDMENT} {@link
     * SignableDocumentEntity} on the amendment's project through the FOR-05-08 engine
     * ({@code create} → {@code saveBody} → {@code requestSignatures}), with the signer set = project
     * CLIENT + executor MANAGER and the default {@code AdES} level (overridable from the dialog,
     * R1.4); links the created document onto {@code amendment.signableDocument} and transitions the
     * amendment {@code OPEN → SENT_FOR_SIGNING} (R1.3); and publishes an
     * {@link AmendmentLifecycleEvent.Trigger#SENT_FOR_SIGNING} lifecycle event (R7.3).
     *
     * <p>The nested {@code SignableDocumentService} calls self-enforce the {@code SIGNABLE_DOCUMENTS}
     * CREATE/UPDATE grant (R8.2); the {@code AMENDMENT} type is not {@code CONTRACT_*}, so reaching
     * {@code SIGNED} never activates the project (R1.7 — nothing to suppress, the signal simply never
     * fires).
     *
     * @param amendmentId the amendment to send (must be {@code OPEN} and non-empty)
     * @param options     the optional per-send method/level overrides (may be {@code null} = defaults)
     * @return the refreshed amendment read model ({@code SENT_FOR_SIGNING} with its linked document)
     * @throws ForemenApiException 404 when the amendment is missing; 409 when it is not {@code OPEN}
     *                             (R1.5); 422 when it is empty (R1.1)
     */
    public AmendmentServiceExtendedModel sendForSigning(Long amendmentId, SendForSigningOptions options) {
        EstimateAmendmentEntity amendment = loadAmendment(amendmentId);
        assertOpen(amendment);
        assertNonEmpty(amendment);

        SendForSigningOptions effective = options == null ? SendForSigningOptions.defaults() : options;
        Long projectId = projectIdOf(amendment);

        // R1.2 — create the AMENDMENT DRAFT document on the amendment's project, referencing the
        // amendment via the opaque sourceRef.
        SignableDocumentTypeEntity type = requireAmendmentType();
        SignableDocumentDto created = signableDocumentService.create(new CreateDocumentRequest(
                projectId, type.getId(), null, amendmentTitle(amendment), String.valueOf(amendmentId)));

        // R1.2 — render the body from the amendment's lines + the signed baseline and store it on the
        // DRAFT document (saveBody; the template-driven generate path is a tasks-phase alternative).
        signableDocumentService.saveBody(created.id(), new DocumentBodyInput(renderBody(amendment)));

        // R1.2/R1.4 — freeze the PDF and request signatures from CLIENT + MANAGER at the chosen
        // (default AdES) level; role-resolved signers are bound to the project members at signing time.
        signableDocumentService.requestSignatures(created.id(), new RequestSignaturesInput(
                effective.effectiveMethod(), effective.effectiveLevel(), amendmentSigners()));

        // R1.3 — link the frozen, pending document and move the amendment out of OPEN.
        SignableDocumentEntity document =
                entityManager.getReference(SignableDocumentEntity.class, created.id());
        amendment.setSignableDocument(document);
        amendment.setStatus(AmendmentStatus.SENT_FOR_SIGNING);

        EstimateAmendmentEntity saved = amendmentDao.save(amendment);
        entityManager.flush();

        // R7.3 — notify the amendment owner (MANAGER) + project FINANCIER after this commits.
        publishLifecycle(AmendmentLifecycleEvent.Trigger.SENT_FOR_SIGNING, saved);
        return serviceMapper.toServiceExtendedModel(saved);
    }

    // --- Reflection callbacks (invoked by AmendmentSigningReaction, Requirements 2/3/5) --------

    /**
     * Reflects a linked document reaching {@code SIGNED} onto its amendment (Requirements 2.2, 3, 5):
     * resolves the amendment linked to {@code documentId} and, <b>only</b> when it is still
     * {@code SENT_FOR_SIGNING} (else a no-op — idempotent re-processing, R3.3), sets its
     * {@code effectiveDate} to the all-parties-signed date (R3.1), transitions
     * {@code SENT_FOR_SIGNING → SIGNED → EFFECTIVE} (R2.2, R3.2), applies the net schedule delta to
     * {@code Project.endDate} exactly once (R5), and publishes an
     * {@link AmendmentLifecycleEvent.Trigger#EFFECTIVE} lifecycle event (R7.3).
     *
     * <p>Price/volume deltas need no action here — once {@code EFFECTIVE} they flow through the
     * unchanged FOR-06-08 {@code resolveClientUnitPrice} seam (R4.1). The only mutation outside the
     * amendment's own status/effectiveDate is the single {@code Project.endDate} shift (R10).
     *
     * <p>Runs in its own {@link Propagation#REQUIRES_NEW} transaction: the reaction invokes this from
     * an {@code AFTER_COMMIT} listener (after the signing transaction has committed and its
     * synchronization is being torn down), so the reflection must open — and durably commit — a fresh,
     * independent transaction of its own rather than silently join the completing one. A failure here
     * still cannot roll the signing back, because the signing already committed (R2.4, R7.6).
     *
     * @param documentId the linked signing document that reached {@code SIGNED}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDocumentFullySigned(Long documentId) {
        EstimateAmendmentEntity amendment = findLinkedAmendment(documentId);
        if (amendment == null || amendment.getStatus() != AmendmentStatus.SENT_FOR_SIGNING) {
            return; // R3.3 — idempotent: not linked / not awaiting signing ⇒ no-op.
        }

        SignableDocumentEntity document = amendment.getSignableDocument();
        // R3.1 — the all-parties-signed date = max(signedAt) across the document's signatures.
        LocalDate effectiveDate = allPartiesSignedDateResolver.resolve(document.getSignatures());
        amendment.setEffectiveDate(effectiveDate);

        // R2.2 / R3.2 — SENT_FOR_SIGNING → SIGNED → EFFECTIVE (the effective date is the signing date,
        // so there is no deferred effective window; the single edge guards the once-only effect).
        amendment.setStatus(AmendmentStatus.SIGNED);
        amendment.setStatus(AmendmentStatus.EFFECTIVE);

        // R5 — apply the net schedule delta to Project.endDate exactly once (the status guard above
        // makes this the single SIGNED → EFFECTIVE edge, so a re-entry never shifts twice, R5.4).
        applyScheduleDelta(amendment);

        EstimateAmendmentEntity saved = amendmentDao.save(amendment);
        entityManager.flush();

        publishLifecycle(AmendmentLifecycleEvent.Trigger.EFFECTIVE, saved);
    }

    /**
     * Reflects a linked document being declined or voided onto its amendment (Requirement 2.3):
     * resolves the amendment linked to {@code documentId} and, <b>only</b> when it is still
     * {@code SENT_FOR_SIGNING} (else a no-op), transitions {@code SENT_FOR_SIGNING → OPEN}, clears
     * {@code amendment.signableDocument} (the declined/voided document stays readable for audit on the
     * engine side), and leaves the amendment lines intact so the manager can amend and resend; then
     * publishes an {@link AmendmentLifecycleEvent.Trigger#REOPENED} lifecycle event (R7.3).
     *
     * <p>Runs in its own {@link Propagation#REQUIRES_NEW} transaction for the same reason as
     * {@link #onDocumentFullySigned(Long)}: invoked from the {@code AFTER_COMMIT} reaction, it must
     * open and durably commit a fresh, independent transaction rather than join the completing signing
     * transaction's synchronization.
     *
     * @param documentId the linked signing document that was declined/voided
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDocumentReopened(Long documentId) {
        EstimateAmendmentEntity amendment = findLinkedAmendment(documentId);
        if (amendment == null || amendment.getStatus() != AmendmentStatus.SENT_FOR_SIGNING) {
            return; // no-op unless the amendment is awaiting signing.
        }

        // R2.3 — reopen for editing: clear the link, leave the lines intact (do NOT touch effectiveDate
        // — it was never set while SENT_FOR_SIGNING).
        amendment.setStatus(AmendmentStatus.OPEN);
        amendment.setSignableDocument(null);

        EstimateAmendmentEntity saved = amendmentDao.save(amendment);
        entityManager.flush();

        publishLifecycle(AmendmentLifecycleEvent.Trigger.REOPENED, saved);
    }

    // --- Effect application (Requirement 5) ----------------------------------------------------

    /**
     * Shifts {@code Project.endDate} by the amendment's net schedule delta, exactly once on the
     * {@code SIGNED → EFFECTIVE} edge (R5). No {@code SCHEDULE_CHANGE} line ⇒ net delta {@code 0} ⇒
     * the end date is left unchanged (R5.3). The prospective end date is validated against the project
     * start date <b>before</b> the single write ({@code assertValidRange}, R5.5), and <b>only</b>
     * {@code Project.endDate} is ever written — never {@code startDate} and no schedule bar (R5.2,
     * R10.2).
     */
    private void applyScheduleDelta(EstimateAmendmentEntity amendment) {
        int netHalfDays = scheduleDeltaCalculator.netHalfDays(amendment.getLines());
        if (netHalfDays == 0) {
            return; // R5.3 — no schedule change ⇒ end date unchanged.
        }
        ProjectEntity project = amendment.getProject();
        if (project == null || project.getEndDate() == null) {
            return; // nothing anchored to shift.
        }
        LocalDate shifted = scheduleDeltaCalculator.shift(project.getEndDate(), netHalfDays);
        scheduleDeltaCalculator.assertValidRange(project.getStartDate(), shifted); // R5.5 (before the write)
        project.setEndDate(shifted); // R5.2 — the single schedule mutation anywhere.
    }

    // --- Body rendering (Requirement 1.2) ------------------------------------------------------

    /**
     * Renders the amendment-document body from the amendment's stored lines (R1.2). The amendment
     * {@code lines} are the reproducible record of what changed (R10.3); this summary makes them
     * human-readable in the frozen artifact. A template-driven {@code generate} is the alternative when
     * an active {@code AMENDMENT} template exists (design §{@code sendForSigning} step 3); the text
     * summary keeps the flow exercisable end-to-end without one.
     */
    private static String renderBody(EstimateAmendmentEntity amendment) {
        StringBuilder body = new StringBuilder();
        body.append("Amendment #").append(amendment.getSeqNo());
        if (amendment.getNote() != null && !amendment.getNote().isBlank()) {
            body.append(" — ").append(amendment.getNote());
        }
        body.append('\n');
        List<AmendmentLineEntity> lines = amendment.getLines();
        if (lines != null) {
            for (AmendmentLineEntity line : lines) {
                if (line == null) {
                    continue;
                }
                body.append("- ").append(line.getChangeType());
                BigDecimal qty = line.getDeltaQuantity();
                if (qty != null) {
                    body.append(" qty=").append(qty.toPlainString());
                }
                BigDecimal price = line.getExtraUnitPrice();
                if (price != null) {
                    body.append(" unitPrice=").append(price.toPlainString());
                }
                Integer halfDays = line.getScheduleDeltaHalfDays();
                if (halfDays != null) {
                    body.append(" scheduleHalfDays=").append(halfDays);
                }
                if (line.getDescription() != null && !line.getDescription().isBlank()) {
                    body.append(" (").append(line.getDescription()).append(')');
                }
                body.append('\n');
            }
        }
        return body.toString();
    }

    private static String amendmentTitle(EstimateAmendmentEntity amendment) {
        return "Amendment #" + amendment.getSeqNo();
    }

    // --- Signer + recipient resolution ---------------------------------------------------------

    /**
     * The amendment document's signer set (R1.4): the project CLIENT member(s) and the executor
     * MANAGER representative, declared by role so the FOR-05-08 engine resolves them to the concrete
     * project members at signing time (sign-any).
     */
    private static List<RequestSignaturesInput.SignerInput> amendmentSigners() {
        List<RequestSignaturesInput.SignerInput> signers = new ArrayList<>(2);
        signers.add(new RequestSignaturesInput.SignerInput(null, SIGNER_ROLE_CLIENT));
        signers.add(new RequestSignaturesInput.SignerInput(null, SIGNER_ROLE_MANAGER));
        return signers;
    }

    /**
     * The amendment-lifecycle notification recipients (R7.3): the project's MANAGER member(s) (the
     * amendment owner role) and the project's FINANCIER member(s), de-duplicated. Resolved from the
     * project membership so the after-commit emitter does no further lookup.
     */
    private List<Long> resolveRecipients(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        List<Long> recipients = new ArrayList<>();
        for (ProjectMemberEntity member : projectMemberDao.findByProjectId(projectId)) {
            String role = projectRoleCode(member);
            if ((RECIPIENT_ROLE_MANAGER.equals(role) || RECIPIENT_ROLE_FINANCIER.equals(role))
                    && member.getUser() != null) {
                Long userId = member.getUser().getId();
                if (userId != null && !recipients.contains(userId)) {
                    recipients.add(userId);
                }
            }
        }
        return recipients;
    }

    private static String projectRoleCode(ProjectMemberEntity member) {
        if (member == null || member.getProjectRole() == null) {
            return null;
        }
        RoleEntity role = member.getProjectRole();
        return role.getCode();
    }

    // --- Event publishing ----------------------------------------------------------------------

    /**
     * Publishes an {@link AmendmentLifecycleEvent} for the owned transition; consumed after-commit by
     * {@link AmendmentNotificationEmitter}, so a notification failure cannot roll back the transition
     * (R7.3, R7.6). The recipient set is resolved here (inside the transaction that holds the loaded
     * amendment + project) and carried on the event.
     */
    private void publishLifecycle(AmendmentLifecycleEvent.Trigger trigger,
                                  EstimateAmendmentEntity amendment) {
        Long projectId = projectIdOf(amendment);
        eventPublisher.publishEvent(new AmendmentLifecycleEvent(
                trigger,
                amendment.getId(),
                projectId,
                amendment.getSeqNo(),
                resolveRecipients(projectId)));
    }

    // --- Helpers -------------------------------------------------------------------------------

    /** Loads the managed amendment or 404s. */
    private EstimateAmendmentEntity loadAmendment(Long id) {
        if (id == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "amendmentId", id);
        }
        return amendmentDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "amendmentId", id));
    }

    /** Resolves the amendment linked to a signing document, or {@code null} when none (R2.2/R2.3). */
    private EstimateAmendmentEntity findLinkedAmendment(Long documentId) {
        if (documentId == null) {
            return null;
        }
        return amendmentDao.findBySignableDocumentId(documentId).orElse(null);
    }

    /** A send requires the amendment to be {@code OPEN} (R1.5 — {@link #NOT_OPEN}). */
    private static void assertOpen(EstimateAmendmentEntity amendment) {
        if (amendment.getStatus() != AmendmentStatus.OPEN) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_OPEN);
        }
    }

    /** A send requires the amendment to have at least one line (R1.1 — {@link #EMPTY_NOT_SIGNABLE}). */
    private static void assertNonEmpty(EstimateAmendmentEntity amendment) {
        List<AmendmentLineEntity> lines = amendment.getLines();
        if (lines == null || lines.isEmpty()) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, EMPTY_NOT_SIGNABLE);
        }
    }

    /** Resolves the {@code AMENDMENT} document type or 404s (it is seeded by FOR-05-08). */
    private SignableDocumentTypeEntity requireAmendmentType() {
        return documentTypeDao.findByCode(AMENDMENT_TYPE_CODE)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, NOT_FOUND, "documentType", AMENDMENT_TYPE_CODE));
    }

    private static Long projectIdOf(EstimateAmendmentEntity amendment) {
        return amendment.getProject() != null ? amendment.getProject().getId() : null;
    }
}
