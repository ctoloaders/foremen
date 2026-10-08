package com.foremen.service.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.CompanyProfileDao;
import com.foremen.dao.DocumentTemplateDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.SignableDocumentDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.CompanyProfileEntity;
import com.foremen.dao.model.DocumentFormFieldEntity;
import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.DocumentTemplateEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;
import com.foremen.dao.model.SignatureLevel;
import com.foremen.dao.model.SignatureStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.document.mapper.DocumentDtoMapper;
import com.foremen.service.model.SignableDocumentServiceExtendedModel;
import com.foremen.service.model.SignableDocumentServiceModel;
import com.foremen.service.model.mapper.SignableDocumentServiceMapper;
import com.foremen.service.signing.DocumentAction;
import com.foremen.service.signing.DocumentStatusMachine;
import com.foremen.service.signing.SigningAuthorizationGuard;
import com.foremen.service.signing.SigningProgress;
import com.foremen.service.signing.SigningProgressCalculator;
import com.foremen.service.signing.merge.MergeContext;
import com.foremen.service.signing.merge.MergeMode;
import com.foremen.service.signing.merge.MergeResult;
import com.foremen.service.signing.merge.TemplateMergeEngine;

import jakarta.persistence.EntityManager;

/**
 * FOR-05-08 (Requirements 1.1, 1.3, 1.5, 1.7, 1.8, 3.5, 3.7, 4.2, 4.3, 8.4; design §Components
 * {@code SignableDocumentService}): the project-scoped lifecycle service for a
 * {@link SignableDocumentEntity} — the single owner of the {@code DRAFT → PENDING_SIGNATURES →
 * SIGNED} (+ {@code VOID}) lifecycle.
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@code OfferService}/{@code EstimateService}),
 * it implements exactly one CRUD contract — {@link ProjectScopedService} — supplies the standard CRUD
 * plumbing, the single mandatory per-entity override {@link #getProjectIdPath()} &rarr;
 * {@code "project.id"} (R1.8, per {@code entity-creation-rules} step 4), and wires
 * {@link #allowedProjectIds(Long)} to {@link ProjectAccessCache}, so by-id reads/mutations run the
 * inherited {@code assertProjectAccess} project-membership gate.
 *
 * <h2>Lifecycle methods (task 7.1)</h2>
 * <ul>
 *   <li>{@link #create(CreateDocumentRequest)} — persists a {@code DRAFT} document bound to a project
 *       + type (R1.1).</li>
 *   <li>{@link #generate(Long)} — renders the active template body by merge into the DRAFT body and
 *       recomputes the body hash; rejected once the document leaves {@code DRAFT}
 *       ({@code 409 error.document.frozen}, R3.5, R3.7).</li>
 *   <li>{@link #saveBody(Long, DocumentBodyInput)} — saves an edited DRAFT body (template-editor save);
 *       rejected once the document leaves {@code DRAFT} (R3.5, R3.7).</li>
 *   <li>{@link #requestSignatures(Long, RequestSignaturesInput)} — freezes the immutable PDF, sets
 *       {@code documentUri} + {@code contentHash}, creates one {@code PENDING} signature per resolved
 *       signer ({@code >= 1}), and transitions {@code DRAFT → PENDING_SIGNATURES} via
 *       {@link DocumentStatusMachine} (R1.5, R4.2, R4.3).</li>
 *   <li>{@link #fillFormFields(Long, FormFieldValuesInput)} — fills form-field values; permitted only
 *       while {@code PENDING_SIGNATURES} (R1.5, R3.6).</li>
 *   <li>{@link #voidDocument(Long)} — transitions {@code DRAFT}/{@code PENDING_SIGNATURES → VOID} via
 *       {@link DocumentStatusMachine} (R1.3, R1.7).</li>
 *   <li>{@link #progress(Long)} — the aggregate signing progress via
 *       {@link SigningProgressCalculator} (R4.5).</li>
 * </ul>
 *
 * <p>Every status transition is delegated to the pure {@link DocumentStatusMachine} (R1.3); the
 * service never assigns {@code PENDING_SIGNATURES}/{@code VOID} directly. {@link DocumentStatus#SIGNED}
 * is <b>derived</b> ({@code recomputeSignedState}, task 7.2) and never set here. The CLIENT-narrowing
 * operation policy is layered on top of ABAC by {@link SigningAuthorizationGuard} (R8.4): every write
 * action asserts the acting project role is allowed before touching the document.
 *
 * <h2>Deferred seams</h2>
 * <ul>
 *   <li><b>{@code recomputeSignedState}</b> (task 7.2) — the derived {@code SIGNED} recompute invoked
 *       after each signature mutation. Not implemented here; the signature-mutating paths live in
 *       {@code SignatureService}.</li>
 *   <li><b>{@code SignatureService}</b> (task 7.4) — per-method completion (PRINT / TABLET_INITIALS /
 *       ONLINE / PODPIS_GOV_PL) and decline. This service only <i>creates</i> the {@code PENDING}
 *       signatures on {@code requestSignatures}; it does not complete them.</li>
 * </ul>
 */
@Service
public class SignableDocumentService
        implements ProjectScopedService<SignableDocumentServiceModel,
        SignableDocumentServiceExtendedModel, SignableDocumentEntity, Long> {

    /** 404 when the referenced document / project / type / user cannot be resolved. */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /**
     * 409 when a body-mutating action ({@code generate} / {@code saveBody}) targets a document that
     * is no longer {@code DRAFT} — the frozen artifact's body text is immutable (R1.5, R3.7).
     */
    static final String DOCUMENT_FROZEN_MESSAGE = "error.document.frozen";

    /**
     * 409 when a form-field fill targets a document that is not {@code PENDING_SIGNATURES} — form
     * fields are enterable only in that state (DRAFT has no frozen artifact yet; SIGNED/VOID are
     * immutable) (R1.5, R1.6, R3.6).
     */
    static final String DOCUMENT_NOT_PENDING_MESSAGE = "error.document.not.pending";

    /** 422 when a request-signatures call supplies no signers — a document needs >= 1 (R4.2). */
    static final String SIGNERS_REQUIRED_MESSAGE = "error.document.signers.required";

    /** 404 when no active template exists for the document's (type, locale) at generation (R3.2). */
    static final String TEMPLATE_NOT_FOUND_MESSAGE = "error.document.template.not.found";

    /** 401 when the acting user cannot be resolved for the owner / role gate. */
    static final String UNAUTHORIZED_MESSAGE = "error.auth.unauthorized";

    private final SignableDocumentDao documentDao;
    private final DocumentTemplateDao templateDao;
    private final CompanyProfileDao companyProfileDao;
    private final ProjectDao projectDao;
    private final ProjectMemberDao projectMemberDao;
    private final RoomDao roomDao;
    private final UserDao userDao;
    private final SignableDocumentServiceMapper serviceMapper;
    private final DocumentDtoMapper dtoMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final DocumentStatusMachine statusMachine;
    private final SigningProgressCalculator progressCalculator;
    private final SigningAuthorizationGuard authorizationGuard;
    private final TemplateMergeEngine mergeEngine;
    private final PdfFreezeService pdfFreezeService;
    private final ProjectActivationSignal projectActivationSignal;
    private final ApplicationEventPublisher eventPublisher;

    public SignableDocumentService(SignableDocumentDao documentDao,
                                   DocumentTemplateDao templateDao,
                                   CompanyProfileDao companyProfileDao,
                                   ProjectDao projectDao,
                                   ProjectMemberDao projectMemberDao,
                                   RoomDao roomDao,
                                   UserDao userDao,
                                   SignableDocumentServiceMapper serviceMapper,
                                   DocumentDtoMapper dtoMapper,
                                   ProjectAccessCache projectAccessCache,
                                   AuditLogDao auditLogDao,
                                   EntityManager entityManager,
                                   DocumentStatusMachine statusMachine,
                                   SigningProgressCalculator progressCalculator,
                                   SigningAuthorizationGuard authorizationGuard,
                                   TemplateMergeEngine mergeEngine,
                                   PdfFreezeService pdfFreezeService,
                                   ProjectActivationSignal projectActivationSignal,
                                   ApplicationEventPublisher eventPublisher) {
        this.documentDao = documentDao;
        this.templateDao = templateDao;
        this.companyProfileDao = companyProfileDao;
        this.projectDao = projectDao;
        this.projectMemberDao = projectMemberDao;
        this.roomDao = roomDao;
        this.userDao = userDao;
        this.serviceMapper = serviceMapper;
        this.dtoMapper = dtoMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.statusMachine = statusMachine;
        this.progressCalculator = progressCalculator;
        this.authorizationGuard = authorizationGuard;
        this.mergeEngine = mergeEngine;
        this.pdfFreezeService = pdfFreezeService;
        this.projectActivationSignal = projectActivationSignal;
        this.eventPublisher = eventPublisher;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<SignableDocumentEntity, Long> getDao() {
        return documentDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<SignableDocumentEntity, SignableDocumentServiceModel,
            SignableDocumentServiceExtendedModel> getMapper() {
        return serviceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<SignableDocumentEntity> getDaoModelClass() {
        return SignableDocumentEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /** The document resolves its project boundary through its {@code @ManyToOne project} FK (R1.8). */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Lifecycle: create (R1.1) ---

    /**
     * Creates a new {@code SignableDocument} in {@code DRAFT} bound to the request's project + type.
     *
     * <p>The acting role must be allowed to {@code create} by {@link SigningAuthorizationGuard}
     * (MANAGER/ADMIN, plus FOREMAN for its owned type set; CLIENT is rejected, R8.4/R8.5). The project
     * + type references are resolved to managed entities; {@code createdByUser} is stamped from the
     * authenticated caller; {@code signatureLevel} defaults from the type's
     * {@code defaultSignatureLevel} when the type declares one (R5.2).
     *
     * @param req the create payload (project id + type id required; title / sourceRef / templateLocale optional)
     * @return the client-reachable DTO of the created DRAFT document
     * @throws ForemenApiException 403 when the acting role may not create; 404 when the project / type
     *                             is missing; 401 when the caller cannot be resolved
     */
    @Transactional
    public SignableDocumentDto create(CreateDocumentRequest req) {
        SignableDocumentTypeEntity type = requireType(req.documentTypeId());
        // R8.4/R8.5: the CLIENT-narrowing operation guard decides create permission beyond coarse ABAC.
        authorizationGuard.assertAllowed(
                actingProjectRole(req.projectId()),
                SigningAuthorizationGuard.DocumentAction.CREATE,
                type.getCode(),
                false);

        ProjectEntity project = requireProject(req.projectId());

        SignableDocumentEntity doc = new SignableDocumentEntity();
        doc.setProject(project);
        doc.setDocumentType(type);
        doc.setStatus(DocumentStatus.DRAFT);
        doc.setTitle(req.title());
        doc.setSourceRef(req.sourceRef());
        doc.setTemplateLocale(req.templateLocale());
        doc.setSignatureLevel(type.getDefaultSignatureLevel());
        doc.setCreatedByUser(requireCurrentUser());

        SignableDocumentEntity saved = documentDao.save(doc);
        entityManager.flush();
        return toDto(saved);
    }

    // --- Lifecycle: generate (R3.5, R3.7) ---

    /**
     * Renders the active template body for the document's (type, locale) by merge and stores it as
     * the editable DRAFT body ({@code documentUri}), recomputing the body hash.
     *
     * <p>Rejected once the document is no longer {@code DRAFT} ({@code 409 error.document.frozen},
     * R3.7): past the freeze, only form-field entry and signatures are permitted (R1.5). The merge is
     * performed in {@link MergeMode#MARK_BLANK} so an unresolved placeholder leaves a human-visible
     * blank for manual completion rather than failing generation (R3.4 option (a)); the DRAFT body
     * stays editable and re-generable.
     *
     * @param id the DRAFT document id
     * @return the client-reachable DTO with the generated body uri + hash
     * @throws ForemenApiException 403 when the acting role may not generate; 404 when the document /
     *                             active template is missing; 409 when the document is not {@code DRAFT}
     */
    @Transactional
    public SignableDocumentDto generate(Long id) {
        SignableDocumentEntity doc = requireDocument(id);
        assertAllowed(doc, SigningAuthorizationGuard.DocumentAction.GENERATE);
        assertDraft(doc);

        DocumentTemplateEntity template = requireActiveTemplate(doc);
        MergeContext context = buildMergeContext(doc);
        MergeResult result = mergeEngine.render(templateBody(template), context, MergeMode.MARK_BLANK);

        applyBody(doc, result.body());

        documentDao.save(doc);
        entityManager.flush();
        return toDto(doc);
    }

    // --- Lifecycle: saveBody (R3.5, R3.7) ---

    /**
     * Saves an edited DRAFT body (the template-editor "save" path, R3.5), recomputing the body hash.
     * Rejected once the document is no longer {@code DRAFT} ({@code 409 error.document.frozen}, R3.7).
     *
     * @param id   the DRAFT document id
     * @param body the new DRAFT body text
     * @return the client-reachable DTO with the saved body uri + hash
     * @throws ForemenApiException 403 when the acting role may not save; 404 when the document is
     *                             missing; 409 when the document is not {@code DRAFT}
     */
    @Transactional
    public SignableDocumentDto saveBody(Long id, DocumentBodyInput body) {
        SignableDocumentEntity doc = requireDocument(id);
        assertAllowed(doc, SigningAuthorizationGuard.DocumentAction.SAVE_BODY);
        assertDraft(doc);

        applyBody(doc, body == null ? null : body.body());

        documentDao.save(doc);
        entityManager.flush();
        return toDto(doc);
    }

    // --- Lifecycle: request-signatures (R1.5, R4.2, R4.3) ---

    /**
     * Freezes the current DRAFT body into the canonical immutable PDF, sets {@code documentUri} +
     * {@code contentHash}, creates one {@code PENDING} {@link DocumentSignatureEntity} per resolved
     * signer ({@code >= 1}), and transitions {@code DRAFT → PENDING_SIGNATURES} via the pure
     * {@link DocumentStatusMachine} (R1.5, R4.2).
     *
     * <p>Each requested signer is created {@code PENDING} with the chosen {@code method} and the level
     * (the request override, else the document default, else {@code AdES}, R5.2); a signer may be
     * identified by user, by role, or both (R4.3) — resolution of a {@code CLIENT} role to the project
     * CLIENT member(s) is performed later at signing time by {@code SignatureService} (sign-any,
     * decision 8), so the {@code PENDING} row carries the role as declared.
     *
     * <p>The derived {@code SIGNED} state and the {@code DOCUMENT_SENT_FOR_SIGNING} notification are
     * out of scope here (tasks 7.2 / 9.1): this method only moves the document to
     * {@code PENDING_SIGNATURES} and seeds the signatures.
     *
     * @param id the DRAFT document id
     * @param in the signing method + optional level + signer set ({@code >= 1})
     * @return the client-reachable DTO of the frozen, pending document
     * @throws ForemenApiException 403 when the acting role may not request signatures; 404 when the
     *                             document is missing; 409 on an illegal transition (not {@code DRAFT});
     *                             422 when no signers were supplied
     */
    @Transactional
    public SignableDocumentDto requestSignatures(Long id, RequestSignaturesInput in) {
        SignableDocumentEntity doc = requireDocument(id);
        assertAllowed(doc, SigningAuthorizationGuard.DocumentAction.REQUEST_SIGNATURES);

        List<RequestSignaturesInput.SignerInput> signers = in == null ? null : in.signers();
        if (signers == null || signers.isEmpty()) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, SIGNERS_REQUIRED_MESSAGE);
        }

        // R1.3: validate the transition on the pure machine BEFORE any mutation; it throws 409 on a
        // non-DRAFT document, leaving the document unchanged.
        DocumentStatus next = statusMachine.transition(doc.getStatus(), DocumentAction.REQUEST_SIGNATURES);

        // R1.5: freeze the immutable PDF of the current body and anchor its content hash.
        FrozenArtifact frozen = pdfFreezeService.freeze(doc);
        doc.setDocumentUri(frozen.storageUri());
        doc.setContentHash(frozen.contentHash());

        // R4.2/R4.3/R5.2: one PENDING signature per requested signer, with the resolved level.
        SignatureLevel level = resolveLevel(in.level(), doc);
        for (RequestSignaturesInput.SignerInput signer : signers) {
            doc.getSignatures().add(buildPendingSignature(doc, in, signer, level));
        }

        doc.setStatus(next);

        documentDao.save(doc);
        entityManager.flush();

        // R11.2: notify each designated signer that the document was sent for signing. Published as an
        // after-commit event so a notification failure can never roll back this transition (R11.1).
        publishDocumentNotification(doc, DocumentNotificationEvent.Trigger.DOCUMENT_SENT_FOR_SIGNING,
                resolveSignerRecipients(doc));
        return toDto(doc);
    }

    // --- Lifecycle: fill form fields (R1.5, R3.6) ---

    /**
     * Fills (sets/clears) the document's form-field values. Permitted only while the document is
     * {@code PENDING_SIGNATURES} ({@code 409 error.document.not.pending} otherwise, R1.5): form fields
     * are the only textual content a signer may add after the body is frozen; a DRAFT document has no
     * frozen artifact to fill, and a {@code SIGNED}/{@code VOID} document is immutable (R1.6).
     *
     * <p>Only known field keys are updated (an unknown key is ignored); a {@code null} value clears
     * the field. The CLIENT own-fields narrowing (R8.4) is enforced at the controller/guard boundary
     * via {@link SigningAuthorizationGuard}; here the write action is gated as
     * {@link SigningAuthorizationGuard.DocumentAction#FILL_FORM_FIELDS}.
     *
     * @param id the {@code PENDING_SIGNATURES} document id
     * @param in the form-field values to apply (by key)
     * @return the client-reachable DTO with the updated form fields
     * @throws ForemenApiException 403 when the acting role may not fill; 404 when the document is
     *                             missing; 409 when the document is not {@code PENDING_SIGNATURES}
     */
    @Transactional
    public SignableDocumentDto fillFormFields(Long id, FormFieldValuesInput in) {
        SignableDocumentEntity doc = requireDocument(id);
        // The own-ness narrowing for a CLIENT is applied by the caller; here the targetsOwn flag is
        // conservatively true for a non-CLIENT writer and relies on the controller for CLIENT scoping.
        assertFormFieldAllowed(doc);
        assertPending(doc);

        if (in != null && in.values() != null) {
            for (FormFieldValuesInput.FormFieldValue value : in.values()) {
                if (value == null || value.key() == null) {
                    continue;
                }
                applyFormFieldValue(doc, value.key(), value.value());
            }
        }

        documentDao.save(doc);
        entityManager.flush();
        return toDto(doc);
    }

    // --- Lifecycle: void (R1.3, R1.7) ---

    /**
     * Voids a {@code DRAFT} or {@code PENDING_SIGNATURES} document, transitioning it {@code → VOID}
     * via the pure {@link DocumentStatusMachine} (R1.3). A {@code SIGNED}/{@code VOID} document is
     * terminal, so the machine rejects the void with {@code 409 error.document.illegal.transition}
     * (the signed artifact is fully immutable, R1.6). A voided document stays readable for audit but
     * is excluded from pending/to-sign lists (R1.7).
     *
     * <p>The {@code DOCUMENT_VOIDED} notification to still-{@code PENDING} signers is out of scope here
     * (task 9.1).
     *
     * @param id the document id to void
     * @return the client-reachable DTO of the voided document
     * @throws ForemenApiException 403 when the acting role may not void; 404 when the document is
     *                             missing; 409 on an illegal transition (terminal document)
     */
    @Transactional
    public SignableDocumentDto voidDocument(Long id) {
        SignableDocumentEntity doc = requireDocument(id);
        assertAllowed(doc, SigningAuthorizationGuard.DocumentAction.VOID);

        // R11.2: resolve the still-PENDING signers to notify of the void BEFORE the transition (the
        // void does not alter the signature set, so the pending set is the same before and after).
        boolean wasPending = doc.getStatus() == DocumentStatus.PENDING_SIGNATURES;
        List<Long> pendingSigners = wasPending ? resolvePendingSignerRecipients(doc) : List.of();

        DocumentStatus next = statusMachine.transition(doc.getStatus(), DocumentAction.VOID);
        doc.setStatus(next);

        documentDao.save(doc);
        entityManager.flush();

        // R11.2: notify all still-pending signers of the void (only a PENDING_SIGNATURES document has
        // pending signers; voiding a DRAFT notifies no-one). After-commit, best-effort (R11.1).
        if (wasPending) {
            publishDocumentNotification(doc, DocumentNotificationEvent.Trigger.DOCUMENT_VOIDED, pendingSigners);
        }
        return toDto(doc);
    }

    // --- Derived SIGNED recompute (R1.4, R6.6; parent Property 16) ---

    /**
     * Recomputes the document's derived {@link DocumentStatus#SIGNED} state from its signature set
     * (R1.4, R6.6; design §Components {@code recomputeSignedState}, parent Property 16). This is the
     * <b>single</b> place {@code SIGNED} is ever assigned: {@code SIGNED} is never set imperatively by
     * any command path (the pure {@link DocumentStatusMachine} explicitly cannot produce it). It must
     * be invoked after every signature mutation — the per-method completion paths in
     * {@code SignatureService} (task 7.4) call it after a signature flips to {@code SIGNED} /
     * {@code DECLINED}.
     *
     * <p>The rule is derived straight from the pure {@link SigningProgressCalculator}: the document is
     * {@code SIGNED} <b>iff</b> every {@link DocumentSignatureEntity} is {@code SIGNED} and at least
     * one signature exists ({@code allSigned == signedCount == totalCount && totalCount > 0}), so an
     * empty signature set is never all-signed. The promotion is applied as a single consistent
     * operation on the managed entity.
     *
     * <p>Only a {@code PENDING_SIGNATURES} document is promoted. {@code DRAFT} carries no signatures to
     * complete; {@code VOID} is a discarded terminal state the recompute must never resurrect; and a
     * document already {@code SIGNED} is left untouched (idempotent re-invocation). A document whose
     * signatures are not (yet) all {@code SIGNED} is likewise left in {@code PENDING_SIGNATURES} — this
     * method only ever promotes to {@code SIGNED}, it never demotes.
     *
     * <p>On the full-sign transition (and only then) it fires the {@link ProjectActivationSignal}
     * contract-signed hand-off (R10.1): for a {@code CONTRACT_*} document on a project whose offer is
     * {@code APPROVED}, the project becomes {@code ACTIVE}. The signal is itself contract/offer-gated
     * and idempotent (R10.2–R10.4), so a non-contract full-sign is a no-op.
     *
     * @param doc the managed document to recompute (its {@code signatures} must be loaded); a
     *            {@code null} document is a no-op
     * @return {@code true} iff this call transitioned the document to {@code SIGNED}
     */
    @Transactional
    public boolean recomputeSignedState(SignableDocumentEntity doc) {
        if (doc == null || doc.getStatus() != DocumentStatus.PENDING_SIGNATURES) {
            return false;
        }
        if (!progressCalculator.progress(doc.getSignatures()).allSigned()) {
            return false;
        }
        doc.setStatus(DocumentStatus.SIGNED);
        // Contract-signed → project activation hand-off (R10.1): only on the full-sign transition,
        // and only for CONTRACT_* documents whose project offer is APPROVED. The signal consumes the
        // FOR-05-07/13 approval outcome and is contract/offer-gated and idempotent internally (R10.2,
        // R10.3, R10.4), so a non-contract full-sign is a safe no-op.
        projectActivationSignal.onContractSigned(doc);
        return true;
    }

    // --- Read: by id / by project (R9.1, R9.2, R13.1) ---

    /**
     * Reads one document by id as the confidentiality-safe {@link SignableDocumentDto} graph (R9.2,
     * R13.1), running the inherited {@code ProjectScopedService} project-membership gate first so a
     * document outside the caller's accessible projects is indistinguishable from a missing one
     * ({@code 404}, R1.8).
     *
     * @param id the document id
     * @return the document DTO with its signatures, media, form fields, and derived progress
     * @throws ForemenApiException 404 when the document is missing or out of the caller's project scope
     */
    @Transactional(readOnly = true)
    public SignableDocumentDto read(Long id) {
        assertProjectAccess(id);
        return toDto(requireDocument(id));
    }

    /**
     * Lists the documents of a project as confidentiality-safe DTOs, newest first — the signing-tab
     * listing (R9.1, R13.1). The caller must be able to access the project; the per-row project gate
     * is the same {@code ProjectScopedService} boundary used by every by-id read.
     *
     * @param projectId the owning project id
     * @return the project's documents as client-reachable DTOs, newest first
     */
    @Transactional(readOnly = true)
    public List<SignableDocumentDto> listByProject(Long projectId) {
        assertProjectListAccess(projectId);
        List<SignableDocumentEntity> documents = documentDao.findByProjectIdOrderByIdDesc(projectId);
        List<SignableDocumentDto> dtos = new ArrayList<>(documents.size());
        for (SignableDocumentEntity doc : documents) {
            dtos.add(toDto(doc));
        }
        return dtos;
    }

    // --- Read: progress (R4.5) ---

    /**
     * The aggregate signing progress of a document — count signed / total and the outstanding signer
     * list — computed by the pure {@link SigningProgressCalculator} over the document's signature set
     * (R4.5).
     *
     * @param id the document id
     * @return the signing-progress DTO
     * @throws ForemenApiException 404 when the document is missing
     */
    @Transactional(readOnly = true)
    public SigningProgressDto progress(Long id) {
        SignableDocumentEntity doc = requireDocument(id);
        return toProgressDto(doc.getSignatures());
    }

    // --- DTO assembly ---

    /**
     * Assembles the full client-reachable {@link SignableDocumentDto}: the entity graph mapped by
     * {@link DocumentDtoMapper} with the derived {@link SigningProgressDto} (which the entity mapper
     * leaves {@code null}) wired in from the pure calculator.
     */
    private SignableDocumentDto toDto(SignableDocumentEntity doc) {
        SignableDocumentDto base = dtoMapper.toDto(doc);
        return new SignableDocumentDto(
                base.id(), base.projectId(), base.documentTypeCode(), base.status(),
                base.signatureLevel(), base.title(), base.sourceRef(), base.templateLocale(),
                base.documentUri(), base.contentHash(), base.createdAt(),
                base.signatures(), base.media(), base.formFields(), toProgressDto(doc.getSignatures()));
    }

    /**
     * Projects the document's signature set onto the client-reachable {@link SigningProgressDto}: the
     * pure {@link SigningProgressCalculator} supplies the counts and the derived {@code allSigned}
     * flag, and the structured outstanding {@code (signerUserId, signerRole)} refs are re-derived
     * directly from the still-{@code PENDING} (not-{@code SIGNED}) signatures so the typed DTO needs no
     * string parsing (R4.5).
     */
    private SigningProgressDto toProgressDto(List<DocumentSignatureEntity> signatures) {
        SigningProgress progress = progressCalculator.progress(signatures);
        List<SigningProgressDto.SignerRefDto> outstanding = new ArrayList<>();
        for (DocumentSignatureEntity signature : signatures) {
            if (signature != null && signature.getStatus() != SignatureStatus.SIGNED) {
                Long signerUserId = signature.getSignerUser() != null ? signature.getSignerUser().getId() : null;
                outstanding.add(new SigningProgressDto.SignerRefDto(signerUserId, signature.getSignerRole()));
            }
        }
        return new SigningProgressDto(
                progress.signedCount(), progress.totalCount(), progress.allSigned(), outstanding);
    }

    // --- Merge-context assembly (R3.3) ---

    /**
     * Assembles the {@link MergeContext} for a document from its project / CLIENT member(s) / rooms /
     * executor representative / {@code CompanyProfile} requisites. Every datum is best-effort: a
     * missing source leaves the corresponding context field null/empty so the merge engine reports an
     * unresolved placeholder rather than a silent empty substitution (R3.3, R3.4).
     *
     * <p>The approved-offer net total ({@code {TotalBeforeTax}}) is intentionally left unset here: it
     * is resolved by the FOR-05-07 agreed-offer read path and is wired in a follow-up; a null net
     * total simply yields an unresolved {@code {TotalBeforeTax}} placeholder.
     */
    private MergeContext buildMergeContext(SignableDocumentEntity doc) {
        ProjectEntity project = doc.getProject();
        Long projectId = project != null ? project.getId() : null;

        List<ProjectMemberEntity> members =
                projectId == null ? List.of() : projectMemberDao.findByProjectId(projectId);

        List<UserEntity> clientMembers = new ArrayList<>();
        UserEntity representative = null;
        for (ProjectMemberEntity member : members) {
            String roleCode = projectRoleCode(member);
            if ("CLIENT".equals(roleCode) && member.getUser() != null) {
                clientMembers.add(member.getUser());
            } else if (representative == null
                    && ("MANAGER".equals(roleCode) || "FOREMAN".equals(roleCode))
                    && member.getUser() != null) {
                representative = member.getUser();
            }
        }

        List<RoomEntity> rooms = projectId == null ? List.of() : roomDao.findByProjectId(projectId);
        CompanyProfileEntity companyProfile = singleCompanyProfile();

        return MergeContext.builder()
                .document(doc)
                .project(project)
                .clientMembers(clientMembers)
                .rooms(rooms)
                .representative(representative)
                .companyProfile(companyProfile)
                .build();
    }

    /** The single admin-managed {@code CompanyProfile} row, or {@code null} when none is configured. */
    private CompanyProfileEntity singleCompanyProfile() {
        for (CompanyProfileEntity profile : companyProfileDao.findAll()) {
            return profile;
        }
        return null;
    }

    // --- helpers ---

    /** Loads the managed document entity or 404s. */
    private SignableDocumentEntity requireDocument(Long id) {
        return documentDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "documentId", id));
    }

    /** Loads the managed type entity or 404s. */
    private SignableDocumentTypeEntity requireType(Long typeId) {
        return typeId == null ? null : documentTypeOr404(typeId);
    }

    private SignableDocumentTypeEntity documentTypeOr404(Long typeId) {
        SignableDocumentTypeEntity type = entityManager.find(SignableDocumentTypeEntity.class, typeId);
        if (type == null) {
            throw new ForemenApiException(
                    HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "documentTypeId", typeId);
        }
        return type;
    }

    /** Loads the managed project entity or 404s. */
    private ProjectEntity requireProject(Long projectId) {
        if (projectId == null) {
            throw new ForemenApiException(
                    HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", projectId);
        }
        return projectDao.findById(projectId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", projectId));
    }

    /** Resolves the single active template for the document's (type, locale) or 404s (R3.2). */
    private DocumentTemplateEntity requireActiveTemplate(SignableDocumentEntity doc) {
        Long typeId = doc.getDocumentType() != null ? doc.getDocumentType().getId() : null;
        String locale = doc.getTemplateLocale();
        if (typeId == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, TEMPLATE_NOT_FOUND_MESSAGE);
        }
        return templateDao.findByDocumentTypeIdAndLocaleAndActiveTrue(typeId, locale)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, TEMPLATE_NOT_FOUND_MESSAGE, typeId, locale));
    }

    /**
     * The template's source body text for the merge engine. The merge engine's contract is a plain
     * {@code String} body carrying {@code {Token}} placeholders (design §TemplateMergeEngine); the
     * {@code .docx} import that lands a real body into object storage is owned by
     * {@code DocumentTemplateService} (task 10.1). Until that read seam exists, the stored
     * {@code storageUri} doubles as the template body text, so generation is exercisable end-to-end;
     * this is the single place to swap in a storage-read when the import toolkit lands.
     */
    private String templateBody(DocumentTemplateEntity template) {
        String body = template.getStorageUri();
        return body == null ? "" : body;
    }

    /** Rejects a body-mutating action on a non-{@code DRAFT} document (R3.7, R1.5). */
    private void assertDraft(SignableDocumentEntity doc) {
        if (doc.getStatus() != DocumentStatus.DRAFT) {
            throw new ForemenApiException(HttpStatus.CONFLICT, DOCUMENT_FROZEN_MESSAGE);
        }
    }

    /** Rejects a form-field fill on a document that is not {@code PENDING_SIGNATURES} (R1.5, R3.6). */
    private void assertPending(SignableDocumentEntity doc) {
        if (doc.getStatus() != DocumentStatus.PENDING_SIGNATURES) {
            throw new ForemenApiException(HttpStatus.CONFLICT, DOCUMENT_NOT_PENDING_MESSAGE);
        }
    }

    /**
     * Writes the body text onto {@code documentUri} and recomputes the body hash (R3.5). While DRAFT
     * the body is stored directly as the editable body text and the hash tracks each change; the
     * canonical immutable PDF + anchor hash are produced only on {@code request-signatures}
     * ({@link PdfFreezeService}).
     */
    private void applyBody(SignableDocumentEntity doc, String body) {
        String resolved = body == null ? "" : body;
        doc.setDocumentUri(resolved);
        doc.setContentHash(sha256Hex(resolved));
    }

    /** Lowercase hex sha-256 of a string body (R3.5). */
    private static String sha256Hex(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new ForemenApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error.document.hash.failed");
        }
    }

    /** Updates an existing form field by key (null value clears it); unknown keys are ignored (R3.6). */
    private void applyFormFieldValue(SignableDocumentEntity doc, String key, String value) {
        for (DocumentFormFieldEntity field : doc.getFormFields()) {
            if (field != null && key.equals(field.getKey())) {
                field.setValue(value);
                return;
            }
        }
    }

    /** Builds a {@code PENDING} signature for one requested signer (R4.2, R4.3, R5.2). */
    private DocumentSignatureEntity buildPendingSignature(SignableDocumentEntity doc,
                                                          RequestSignaturesInput in,
                                                          RequestSignaturesInput.SignerInput signer,
                                                          SignatureLevel level) {
        DocumentSignatureEntity signature = new DocumentSignatureEntity();
        signature.setDocument(doc);
        signature.setMethod(in.method());
        signature.setLevel(level);
        signature.setStatus(SignatureStatus.PENDING);
        signature.setSignerRole(signer.signerRole());
        if (signer.signerUserId() != null) {
            signature.setSignerUser(entityManager.getReference(UserEntity.class, signer.signerUserId()));
        }
        return signature;
    }

    /**
     * The effective signature level for the request: the request override, else the document-level
     * default, else {@code AdES} (R5.2).
     */
    private static SignatureLevel resolveLevel(SignatureLevel requested, SignableDocumentEntity doc) {
        if (requested != null) {
            return requested;
        }
        return doc.getSignatureLevel() != null ? doc.getSignatureLevel() : SignatureLevel.AdES;
    }

    // --- authorization (SigningAuthorizationGuard wiring, R8.4/R8.5) ---

    /**
     * Asserts the acting caller may perform a write {@code action} on {@code doc}, delegating to the
     * {@link SigningAuthorizationGuard} with the caller's project role and the document type code. The
     * write actions ({@code generate} / {@code saveBody} / {@code requestSignatures} / {@code void})
     * are MANAGER/ADMIN (plus FOREMAN for its owned types) and are never CLIENT, so {@code targetsOwn}
     * is irrelevant and passed {@code false}.
     */
    private void assertAllowed(SignableDocumentEntity doc, SigningAuthorizationGuard.DocumentAction action) {
        String typeCode = doc.getDocumentType() != null ? doc.getDocumentType().getCode() : null;
        authorizationGuard.assertAllowed(actingProjectRole(projectIdOf(doc)), action, typeCode, true);
    }

    /**
     * Asserts the acting caller may fill form fields on {@code doc}. {@code FILL_FORM_FIELDS} is a
     * signer action a CLIENT may perform on their own fields; the own-ness narrowing is applied by the
     * controller boundary (which knows which fields the CLIENT targets), so here it is passed
     * {@code true} and the guard enforces role eligibility.
     */
    private void assertFormFieldAllowed(SignableDocumentEntity doc) {
        String typeCode = doc.getDocumentType() != null ? doc.getDocumentType().getCode() : null;
        authorizationGuard.assertAllowed(
                actingProjectRole(projectIdOf(doc)),
                SigningAuthorizationGuard.DocumentAction.FILL_FORM_FIELDS,
                typeCode,
                true);
    }

    private static Long projectIdOf(SignableDocumentEntity doc) {
        return doc.getProject() != null ? doc.getProject().getId() : null;
    }

    // --- notification recipient resolution (R11.2) ---

    /**
     * Resolves the recipient user ids for the {@code DOCUMENT_SENT_FOR_SIGNING} trigger — every
     * designated signer of the document (R11.2). An explicit {@code signerUser} contributes its id; a
     * role-resolved signer (no explicit user, e.g. a {@code CLIENT} sign-any signer) contributes every
     * project member holding that role (decision 8). De-duplicated.
     */
    private List<Long> resolveSignerRecipients(SignableDocumentEntity doc) {
        return resolveRecipients(doc, doc.getSignatures());
    }

    /**
     * Resolves the recipient user ids for the {@code DOCUMENT_VOIDED} trigger — the still-{@code
     * PENDING} signers only (R11.2). Signatures already {@code SIGNED} / {@code DECLINED} are excluded.
     */
    private List<Long> resolvePendingSignerRecipients(SignableDocumentEntity doc) {
        List<DocumentSignatureEntity> pending = new ArrayList<>();
        for (DocumentSignatureEntity signature : doc.getSignatures()) {
            if (signature != null && signature.getStatus() == SignatureStatus.PENDING) {
                pending.add(signature);
            }
        }
        return resolveRecipients(doc, pending);
    }

    /**
     * Resolves a set of signatures to de-duplicated recipient user ids: an explicit {@code signerUser}
     * maps to its id; a role-only signer maps to every project member holding that role (R4.3,
     * decision 8). Null ids are dropped.
     */
    private List<Long> resolveRecipients(SignableDocumentEntity doc, List<DocumentSignatureEntity> signatures) {
        Long projectId = projectIdOf(doc);
        List<ProjectMemberEntity> members =
                projectId == null ? List.of() : projectMemberDao.findByProjectId(projectId);
        List<Long> recipients = new ArrayList<>();
        for (DocumentSignatureEntity signature : signatures) {
            if (signature == null) {
                continue;
            }
            Long explicit = signature.getSignerUser() != null ? signature.getSignerUser().getId() : null;
            if (explicit != null) {
                addDistinct(recipients, explicit);
                continue;
            }
            String role = signature.getSignerRole();
            if (role == null) {
                continue;
            }
            for (ProjectMemberEntity member : members) {
                if (role.equals(projectRoleCode(member)) && member.getUser() != null) {
                    addDistinct(recipients, member.getUser().getId());
                }
            }
        }
        return recipients;
    }

    private static void addDistinct(List<Long> recipients, Long userId) {
        if (userId != null && !recipients.contains(userId)) {
            recipients.add(userId);
        }
    }

    /**
     * Publishes a {@link DocumentNotificationEvent} for a lifecycle change (R11.2). The event carries
     * only ids / codes / the resolved recipient set (never the entity), so it stays valid on the
     * after-commit thread; {@link DocumentNotificationEmitter} maps it to the in-app notifications.
     * Nothing is published for pure DRAFT edits (those paths never call this), so no notification is
     * ever emitted for them (R11.5).
     */
    private void publishDocumentNotification(SignableDocumentEntity doc,
                                             DocumentNotificationEvent.Trigger trigger,
                                             List<Long> recipientUserIds) {
        String typeCode = doc.getDocumentType() != null ? doc.getDocumentType().getCode() : null;
        eventPublisher.publishEvent(new DocumentNotificationEvent(
                trigger, doc.getId(), projectIdOf(doc), typeCode, doc.getTitle(), recipientUserIds));
    }

    /**
     * Asserts the caller may list the documents of {@code projectId}, mirroring the inherited
     * {@code ProjectScopedService.assertProjectAccess} decision at the <b>project</b> grain (there is
     * no owning entity id to resolve for a list): an ADMIN authority bypasses; otherwise the project
     * must be in the caller's {@link #allowedProjectIds(Long)} set. Denial throws
     * {@code 404 error.entity.not.found}, indistinguishable from an empty / unknown project, so an
     * out-of-scope project is not revealed (R1.8).
     */
    private void assertProjectListAccess(Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", projectId);
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if ("ROLE_ADMIN".equals(a) || "ADMIN".equals(a)) {
                return;
            }
        }
        Long userId = parseUserId(auth.getName());
        Set<Long> allowed = userId == null ? null : allowedProjectIds(userId);
        if (projectId == null || allowed == null || !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", projectId);
        }
    }

    /**
     * Resolves the acting caller's role code for {@link SigningAuthorizationGuard}: {@code "ADMIN"}
     * when the authentication carries the ADMIN authority; otherwise the caller's <b>project</b> role
     * on {@code projectId} (the {@code project_members} role code), else the caller's global role code
     * as a fallback. Returns {@code null} when no role can be resolved — the guard treats a {@code null}
     * role as forbidden.
     */
    private String actingProjectRole(Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if ("ROLE_ADMIN".equals(a) || "ADMIN".equals(a)) {
                return "ADMIN";
            }
        }
        Long userId = parseUserId(auth.getName());
        if (userId == null) {
            return null;
        }
        if (projectId != null) {
            ProjectMemberEntity member =
                    projectMemberDao.findByUserIdAndProjectId(userId, projectId).orElse(null);
            String projectRole = projectRoleCode(member);
            if (projectRole != null) {
                return projectRole;
            }
        }
        return globalRoleCode(userId);
    }

    /** The {@code project_members} role code of a membership, or {@code null}. */
    private static String projectRoleCode(ProjectMemberEntity member) {
        if (member == null || member.getProjectRole() == null) {
            return null;
        }
        return member.getProjectRole().getCode();
    }

    /** The caller's global role code (fallback when no project membership resolves), or {@code null}. */
    private String globalRoleCode(Long userId) {
        UserEntity user = userDao.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        RoleEntity role = user.getRole();
        return role != null ? role.getCode() : null;
    }

    /** The authenticated caller as a managed {@link UserEntity} for the {@code createdByUser} provenance. */
    private UserEntity requireCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }
        Long userId = parseUserId(auth.getName());
        if (userId == null) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }
        return userDao.findById(userId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE));
    }

    private static Long parseUserId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
