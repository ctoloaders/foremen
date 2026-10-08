package com.foremen.service.document;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.dao.DocumentSignatureDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.SignableDocumentDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DocumentMediaKind;
import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignatureLevel;
import com.foremen.dao.model.SignatureMethod;
import com.foremen.dao.model.SignatureStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.document.mapper.DocumentDtoMapper;
import com.foremen.service.signing.SigningAuthorizationGuard;
import com.foremen.service.signing.SigningProgress;
import com.foremen.service.signing.SigningProgressCalculator;
import com.foremen.service.signing.provider.SignatureOutcome;
import com.foremen.service.signing.provider.SignatureProvider;
import com.foremen.service.signing.provider.SignatureSession;
import com.foremen.service.signing.provider.Signer;

/**
 * FOR-05-08 (Requirements 4.4, 5.3, 6.2, 6.3, 6.4, 6.5, 6.6; design §Components
 * {@code SignatureService (per-method completion)}): the per-method completion service for a single
 * {@link DocumentSignatureEntity} on a {@link SignableDocumentEntity}.
 *
 * <p>Each completion is nominally an {@code UPDATE} of the owning document (R8.2): a signer completes
 * or declines <b>their own</b> signature. The lifecycle owner ({@link SignableDocumentService})
 * creates one {@code PENDING} signature per designated signer on {@code request-signatures}; this
 * service flips a signature to {@code SIGNED} / {@code DECLINED} under the method-specific
 * precondition and then recomputes the document's derived {@code SIGNED} state (R6.6, parent
 * Property 16) via {@link SignableDocumentService#recomputeSignedState(SignableDocumentEntity)}.
 *
 * <h2>Methods</h2>
 * <ul>
 *   <li>{@link #completeViaScan(Long, MultipartFile)} ({@code PRINT}, R6.2) and
 *       {@link #completeViaTablet(Long, MultipartFile)} ({@code TABLET_INITIALS}, R6.3): store the
 *       uploaded artifact as a {@link DocumentMediaKind#SCAN SCAN} / {@link DocumentMediaKind#TABLET_INITIAL TABLET_INITIAL}
 *       {@code DocumentMedia} via {@link DocumentMediaService}, then mark the caller's signature
 *       {@code SIGNED} with {@code signedAt} and the stored evidence uri. The signature can
 *       <b>never</b> reach {@code SIGNED} without the attached evidence: the upload runs first and a
 *       missing / empty upload is rejected with {@code 409 error.document.evidence.required} before
 *       any status change (R6.2, R6.3, Property 16).</li>
 *   <li>{@link #completeViaProvider(Long, SignRequest)} ({@code ONLINE} / {@code PODPIS_GOV_PL},
 *       R6.4) and {@link #onProviderCallback(ProviderCallback)} (the provider webhook): drive the
 *       {@link SignatureProvider} abstraction. {@code completeViaProvider} <b>initiates</b> the
 *       ceremony (creating a provider session and persisting its {@code providerRef}); the signature
 *       stays {@code PENDING} until the asynchronous {@link #onProviderCallback(ProviderCallback)}
 *       arrives. Before a provider signature is marked {@code SIGNED}, the sealed-evidence integrity
 *       is verified against the document's stored {@code contentHash}; a mismatch is rejected with
 *       {@code 409 error.document.hash.mismatch} (R5.3, R6.4).</li>
 *   <li>{@link #decline(Long, DeclineRequest)} (R4.4): moves the caller's signature to
 *       {@code DECLINED} with the supplied reason. A decline does <b>not</b> by itself void the
 *       document (R4.4) — it only marks that one signature, after which the derived state is
 *       recomputed (and simply stays {@code PENDING_SIGNATURES}, since not all signatures are
 *       {@code SIGNED}).</li>
 * </ul>
 *
 * <p>Every mutation path calls {@link SignableDocumentService#recomputeSignedState} exactly once at
 * the end, so {@link DocumentStatus#SIGNED} is produced only as the derived consequence of the full
 * signature set being {@code SIGNED} and is never assigned here imperatively (R6.6).
 */
@Service
public class SignatureService {

    /**
     * 409 when a {@code PRINT} / {@code TABLET_INITIALS} signature would be marked {@code SIGNED}
     * with no attached evidence media (R6.2, R6.3, Property 16).
     */
    static final String EVIDENCE_REQUIRED_MESSAGE = "error.document.evidence.required";

    /**
     * 409 when the sealed evidence of an {@code ONLINE} / {@code PODPIS_GOV_PL} completion fails the
     * integrity check against the document's stored {@code contentHash} (R5.3, R6.4).
     */
    static final String HASH_MISMATCH_MESSAGE = "error.document.hash.mismatch";

    /** 409 when a completion / decline targets a document that is not {@code PENDING_SIGNATURES}. */
    static final String DOCUMENT_NOT_PENDING_MESSAGE = "error.document.not.pending";

    /** 409 when the method of a completion call does not match the caller's pending signature. */
    static final String METHOD_MISMATCH_MESSAGE = "error.document.method.mismatch";

    /** 404 when the referenced document / signature cannot be resolved. */
    static final String NOT_FOUND_MESSAGE = "error.entity.not.found";

    /** 401 when the acting user cannot be resolved for the own-signature gate. */
    static final String UNAUTHORIZED_MESSAGE = "error.auth.unauthorized";

    private final SignableDocumentDao documentDao;
    private final DocumentSignatureDao signatureDao;
    private final ProjectMemberDao projectMemberDao;
    private final UserDao userDao;
    private final DocumentMediaService mediaService;
    private final SignatureProvider signatureProvider;
    private final SignableDocumentService documentService;
    private final SigningAuthorizationGuard authorizationGuard;
    private final SigningProgressCalculator progressCalculator;
    private final DocumentDtoMapper dtoMapper;
    private final ApplicationEventPublisher eventPublisher;

    public SignatureService(SignableDocumentDao documentDao,
                            DocumentSignatureDao signatureDao,
                            ProjectMemberDao projectMemberDao,
                            UserDao userDao,
                            DocumentMediaService mediaService,
                            SignatureProvider signatureProvider,
                            SignableDocumentService documentService,
                            SigningAuthorizationGuard authorizationGuard,
                            SigningProgressCalculator progressCalculator,
                            DocumentDtoMapper dtoMapper,
                            ApplicationEventPublisher eventPublisher) {
        this.documentDao = documentDao;
        this.signatureDao = signatureDao;
        this.projectMemberDao = projectMemberDao;
        this.userDao = userDao;
        this.mediaService = mediaService;
        this.signatureProvider = signatureProvider;
        this.documentService = documentService;
        this.authorizationGuard = authorizationGuard;
        this.progressCalculator = progressCalculator;
        this.dtoMapper = dtoMapper;
        this.eventPublisher = eventPublisher;
    }

    // --- PRINT completion (R6.2) ---

    /**
     * Completes the caller's {@code PRINT} signature: stores the uploaded wet-ink scan as a
     * {@link DocumentMediaKind#SCAN SCAN} {@code DocumentMedia}, then marks the signature
     * {@code SIGNED} with {@code signedAt} and the stored evidence uri (R6.2).
     *
     * <p>The scan upload (validated + stored by {@link DocumentMediaService}) runs first; the
     * signature reaches {@code SIGNED} only once that evidence is attached — an empty / disallowed /
     * oversized upload is rejected by the media service and the signature stays {@code PENDING}
     * (Property 16).
     *
     * @param documentId the owning document id (must be {@code PENDING_SIGNATURES})
     * @param scan       the wet-ink scan to store as evidence; must be non-empty
     * @return the client-reachable DTO of the (possibly now-{@code SIGNED}) document
     * @throws ForemenApiException 403 when the acting role may not sign; 404 when the document /
     *                             caller's pending {@code PRINT} signature is missing; 409 when the
     *                             document is not pending or the upload is missing
     */
    @Transactional
    public SignableDocumentDto completeViaScan(Long documentId, MultipartFile scan) {
        return completeWithEvidence(documentId, scan, SignatureMethod.PRINT, DocumentMediaKind.SCAN);
    }

    // --- TABLET_INITIALS completion (R6.3) ---

    /**
     * Completes the caller's {@code TABLET_INITIALS} signature: stores the captured on-tablet
     * {@code parafka} image as a {@link DocumentMediaKind#TABLET_INITIAL TABLET_INITIAL}
     * {@code DocumentMedia}, then marks the signature {@code SIGNED} with {@code signedAt} and the
     * stored evidence uri (R6.3). The evidence upload runs first, exactly as for {@code PRINT}, so a
     * missing capture never yields a {@code SIGNED} signature (Property 16).
     *
     * @param documentId the owning document id (must be {@code PENDING_SIGNATURES})
     * @param image      the captured initial image to store as evidence; must be non-empty
     * @return the client-reachable DTO of the (possibly now-{@code SIGNED}) document
     * @throws ForemenApiException 403 when the acting role may not sign; 404 when the document /
     *                             caller's pending {@code TABLET_INITIALS} signature is missing; 409
     *                             when the document is not pending or the capture is missing
     */
    @Transactional
    public SignableDocumentDto completeViaTablet(Long documentId, MultipartFile image) {
        return completeWithEvidence(
                documentId, image, SignatureMethod.TABLET_INITIALS, DocumentMediaKind.TABLET_INITIAL);
    }

    /**
     * Shared evidence-upload completion for the two wet/parafka methods ({@code PRINT},
     * {@code TABLET_INITIALS}). Resolves the caller's matching {@code PENDING} signature, stores the
     * uploaded artifact as {@code DocumentMedia} of {@code kind}, and only then flips the signature
     * to {@code SIGNED} — so the signature cannot reach {@code SIGNED} without attached evidence
     * (R6.2, R6.3, Property 16).
     */
    private SignableDocumentDto completeWithEvidence(
            Long documentId, MultipartFile file, SignatureMethod method, DocumentMediaKind kind) {
        SignableDocumentEntity doc = requireDocument(documentId);
        assertPending(doc);

        DocumentSignatureEntity signature = requireOwnPendingSignature(doc, method);
        // Property 16: a PRINT/TABLET_INITIALS signature SHALL NOT reach SIGNED without evidence.
        // Reject an absent upload up front so no status change happens with no evidence attached.
        if (file == null || file.isEmpty()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, EVIDENCE_REQUIRED_MESSAGE);
        }

        // Store the evidence first (DocumentMediaService validates type/size); only on success does
        // the signature become SIGNED, with the stored object key recorded as its evidence uri.
        DocumentMediaSummaryDto media = mediaService.upload(documentId, file, kind);

        markSigned(signature, media.storageUri());
        return finishSignature(doc);
    }

    // --- ONLINE / PODPIS_GOV_PL completion (R6.4) ---

    /**
     * Initiates (or advances) the caller's {@code ONLINE} / {@code PODPIS_GOV_PL} ceremony through the
     * {@link SignatureProvider} abstraction (R6.4). When the caller's signature has no provider
     * session yet, a session is created and its {@code providerRef} is persisted; the signature stays
     * {@code PENDING} until the asynchronous {@link #onProviderCallback(ProviderCallback)} arrives
     * carrying the sealed evidence. The actual {@code SIGNED} transition — gated by the hash-integrity
     * verification against the stored {@code contentHash} — happens on that callback, never
     * optimistically here (R5.3, R6.4).
     *
     * @param documentId the owning document id (must be {@code PENDING_SIGNATURES})
     * @param req        the sign request (optional explicit {@code signerUserId}; {@code providerRef}
     *                   echoed back once a session exists)
     * @return the client-reachable DTO of the document with the ceremony initiated (still pending)
     * @throws ForemenApiException 403 when the acting role may not sign; 404 when the document /
     *                             caller's pending provider signature is missing; 409 when the
     *                             document is not pending
     */
    @Transactional
    public SignableDocumentDto completeViaProvider(Long documentId, SignRequest req) {
        SignableDocumentEntity doc = requireDocument(documentId);
        assertPending(doc);

        DocumentSignatureEntity signature = requireOwnPendingProviderSignature(doc);

        if (signature.getProviderRef() == null) {
            // Initiate the ceremony; the stub drives initiate → pending → callback → signed/declined
            // with no live QTSP / podpis.gov.pl call. The signature remains PENDING until the webhook.
            URI documentUri = toUri(doc.getDocumentUri());
            SignatureSession session = signatureProvider.createSession(
                    documentUri, doc.getContentHash(), toSigner(signature), signature.getLevel());
            signature.setProviderRef(session.providerRef());
            signatureDao.save(signature);
        }
        // No optimistic SIGNED here: completion is driven exclusively by onProviderCallback (R6.4).
        // No signature flipped, so nothing to notify yet — plain recompute without emission.
        return finishMutation(doc);
    }

    /**
     * Handles the provider webhook ({@code POST /api/signatures/callback}) for an {@code ONLINE} /
     * {@code PODPIS_GOV_PL} ceremony (R6.4). The {@link ProviderCallback#providerRef()} is matched to
     * the pending signature it was issued for; a {@code DECLINED} outcome moves the signature to
     * {@code DECLINED}; a {@code SIGNED} outcome is accepted only after the sealed-evidence integrity
     * verifies against the document's stored {@code contentHash} — a mismatch is rejected with
     * {@code 409 error.document.hash.mismatch} (R5.3, R6.4). The webhook is intentionally
     * unauthenticated and is validated purely by the {@code providerRef} + hash (design
     * §SignatureCallbackController), so no project-role guard runs here.
     *
     * @param cb the inbound provider callback (its {@code providerRef} must match a pending signature)
     * @return the client-reachable DTO of the (possibly now-{@code SIGNED}) document
     * @throws ForemenApiException 404 when no pending signature matches the {@code providerRef}; 409
     *                             on a hash mismatch for a {@code SIGNED} outcome
     */
    @Transactional
    public SignableDocumentDto onProviderCallback(ProviderCallback cb) {
        if (cb == null || cb.providerRef() == null || cb.providerRef().isBlank()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, "providerRef");
        }
        DocumentSignatureEntity signature = requireSignatureByProviderRef(cb.providerRef());
        SignableDocumentEntity doc = signature.getDocument();

        if (cb.outcome() == SignatureStatus.DECLINED) {
            applyDecline(signature, cb.declineReason());
            return finishDecline(doc);
        }

        // SIGNED outcome: verify the sealed-evidence integrity against the stored contentHash before
        // marking SIGNED (R5.3, R6.4). The provider's reported hash must match the frozen artifact's.
        verifyProviderIntegrity(doc, cb);
        markSigned(signature, cb.evidenceUri());
        return finishSignature(doc);
    }

    // --- decline (R4.4) ---

    /**
     * Declines the caller's own signature with the supplied reason (R4.4): the signature moves to
     * {@code DECLINED} with {@code declineReason}. A decline does <b>not</b> by itself void the
     * document — the document stays {@code PENDING_SIGNATURES} (the derived recompute cannot promote
     * it to {@code SIGNED} while a non-{@code SIGNED} signature remains), and a manager may still void
     * it explicitly (R4.4).
     *
     * @param documentId the owning document id (must be {@code PENDING_SIGNATURES})
     * @param req        the decline payload carrying the reason
     * @return the client-reachable DTO of the document with the caller's signature declined
     * @throws ForemenApiException 403 when the acting role may not decline; 404 when the document /
     *                             caller's pending signature is missing; 409 when the document is not
     *                             pending
     */
    @Transactional
    public SignableDocumentDto decline(Long documentId, DeclineRequest req) {
        SignableDocumentEntity doc = requireDocument(documentId);
        assertPending(doc);

        DocumentSignatureEntity signature = requireOwnPendingSignature(doc, null);
        applyDecline(signature, req == null ? null : req.reason());
        return finishDecline(doc);
    }

    // --- shared mutation helpers ---

    /** Marks a signature {@code SIGNED} now, recording its evidence uri (every method, R6.5). */
    private void markSigned(DocumentSignatureEntity signature, String evidenceUri) {
        signature.setStatus(SignatureStatus.SIGNED);
        signature.setSignedAt(LocalDateTime.now());
        if (evidenceUri != null) {
            signature.setEvidenceUri(evidenceUri);
        }
        signatureDao.save(signature);
    }

    /** Marks a signature {@code DECLINED} with the reason; does not void the document (R4.4). */
    private void applyDecline(DocumentSignatureEntity signature, String reason) {
        signature.setStatus(SignatureStatus.DECLINED);
        signature.setDeclineReason(reason);
        signatureDao.save(signature);
    }

    /**
     * Verifies the integrity of a provider {@code SIGNED} outcome against the document's stored
     * {@code contentHash}, rejecting a mismatch with {@code 409 error.document.hash.mismatch} (R5.3,
     * R6.4). The provider's returned sealed-evidence hash (as reported on the callback) must equal
     * the frozen artifact's content hash the session was created for.
     */
    private void verifyProviderIntegrity(SignableDocumentEntity doc, ProviderCallback cb) {
        String storedHash = doc.getContentHash();
        boolean verified = storedHash != null
                && cb.contentHash() != null
                && constantTimeEquals(storedHash, cb.contentHash());
        if (!verified) {
            throw new ForemenApiException(HttpStatus.CONFLICT, HASH_MISMATCH_MESSAGE);
        }
    }

    /**
     * Recomputes the document's derived {@code SIGNED} state after a signature mutation (R6.6, parent
     * Property 16) and returns the refreshed client-reachable DTO. {@code recomputeSignedState} is the
     * single place {@code SIGNED} is ever assigned. This plain variant emits no notification — used by
     * the provider-ceremony initiation, which flips no signature yet.
     */
    private SignableDocumentDto finishMutation(SignableDocumentEntity doc) {
        documentService.recomputeSignedState(doc);
        documentDao.save(doc);
        return toDto(doc);
    }

    /**
     * Finishes a <b>signing</b> completion: recomputes the derived {@code SIGNED} state and emits the
     * R11.2 notifications (R11.1, after-commit, best-effort). When the completion flips the document
     * fully {@code SIGNED}, it emits {@code DOCUMENT_FULLY_SIGNED} to the owner + all signers;
     * otherwise it emits {@code DOCUMENT_SIGNED_BY_PARTY} to the owner for the single party that just
     * signed.
     */
    private SignableDocumentDto finishSignature(SignableDocumentEntity doc) {
        boolean fullySigned = documentService.recomputeSignedState(doc);
        documentDao.save(doc);
        if (fullySigned) {
            publish(doc, DocumentNotificationEvent.Trigger.DOCUMENT_FULLY_SIGNED, ownerPlusSigners(doc));
        } else {
            publish(doc, DocumentNotificationEvent.Trigger.DOCUMENT_SIGNED_BY_PARTY, ownerRecipient(doc));
        }
        return toDto(doc);
    }

    /**
     * Finishes a <b>decline</b>: recomputes the derived state (a decline never promotes to
     * {@code SIGNED}) and emits {@code DOCUMENT_SIGNING_DECLINED} to the document owner (R11.2).
     */
    private SignableDocumentDto finishDecline(SignableDocumentEntity doc) {
        documentService.recomputeSignedState(doc);
        documentDao.save(doc);
        publish(doc, DocumentNotificationEvent.Trigger.DOCUMENT_SIGNING_DECLINED, ownerRecipient(doc));
        return toDto(doc);
    }

    // --- notification recipients + publish (R11.1, R11.2) ---

    /** The document owner (createdBy) as a single-element recipient list, or empty when unknown. */
    private List<Long> ownerRecipient(SignableDocumentEntity doc) {
        Long ownerId = doc.getCreatedByUser() != null ? doc.getCreatedByUser().getId() : null;
        return ownerId == null ? List.of() : List.of(ownerId);
    }

    /**
     * The owner + every signer of a fully-signed document (R11.2). Explicit {@code signerUser}s
     * contribute their id; de-duplicated, with the owner first. Role-only signers without an explicit
     * user are resolved by the lifecycle service at request time; here the completed signatures carry
     * the resolved signer identity, so an explicit id is used when present.
     */
    private List<Long> ownerPlusSigners(SignableDocumentEntity doc) {
        List<Long> recipients = new ArrayList<>();
        Long ownerId = doc.getCreatedByUser() != null ? doc.getCreatedByUser().getId() : null;
        if (ownerId != null) {
            recipients.add(ownerId);
        }
        for (DocumentSignatureEntity signature : doc.getSignatures()) {
            if (signature == null) {
                continue;
            }
            Long signerId = signature.getSignerUser() != null ? signature.getSignerUser().getId() : null;
            if (signerId != null && !recipients.contains(signerId)) {
                recipients.add(signerId);
            }
        }
        return recipients;
    }

    /**
     * Publishes a {@link DocumentNotificationEvent} carrying only ids / codes / the resolved recipient
     * set (never the entity), so it stays valid on the after-commit thread; the
     * {@code DocumentNotificationEmitter} maps it to the in-app notifications (R11.1, R11.2).
     */
    private void publish(SignableDocumentEntity doc, DocumentNotificationEvent.Trigger trigger,
                         List<Long> recipientUserIds) {
        Long projectId = projectIdOf(doc);
        String typeCode = doc.getDocumentType() != null ? doc.getDocumentType().getCode() : null;
        eventPublisher.publishEvent(new DocumentNotificationEvent(
                trigger, doc.getId(), projectId, typeCode, doc.getTitle(), recipientUserIds));
    }

    // --- signature resolution (the caller's own PENDING signature) ---

    /**
     * Resolves the caller's own {@code PENDING} signature on the document for a wet/parafka or decline
     * completion. A match is a {@code PENDING} signature whose {@code signerUser} is the caller, or —
     * for a role-resolved (sign-any) signer with no explicit user — whose {@code signerRole} equals
     * the caller's project role (decision 8). When {@code method} is non-{@code null}, the signature's
     * method must also match ({@code 409 error.document.method.mismatch} otherwise). The write is first
     * gated by {@link SigningAuthorizationGuard} as a signer action on the caller's own record (R8.4).
     */
    private DocumentSignatureEntity requireOwnPendingSignature(
            SignableDocumentEntity doc, SignatureMethod method) {
        Long callerId = requireCurrentUserId();
        String callerRole = actingProjectRole(projectIdOf(doc), callerId);

        List<DocumentSignatureEntity> own = ownPendingSignatures(doc, callerId, callerRole);
        if (own.isEmpty()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, "signature");
        }

        // R8.4: a signer action is permitted only on the caller's own record; the guard decides role
        // eligibility (CLIENT only sign/decline/fill on own; MANAGER/ADMIN/FOREMAN allowed).
        String typeCode = doc.getDocumentType() != null ? doc.getDocumentType().getCode() : null;
        SigningAuthorizationGuard.DocumentAction action = method == null
                ? SigningAuthorizationGuard.DocumentAction.DECLINE
                : SigningAuthorizationGuard.DocumentAction.SIGN;
        authorizationGuard.assertAllowed(callerRole, action, typeCode, true);

        if (method == null) {
            return own.get(0);
        }
        for (DocumentSignatureEntity signature : own) {
            if (signature.getMethod() == method) {
                return signature;
            }
        }
        // The caller has an own pending signature, but not of the requested completion method.
        throw new ForemenApiException(HttpStatus.CONFLICT, METHOD_MISMATCH_MESSAGE);
    }

    /**
     * Resolves the caller's own {@code PENDING} provider ({@code ONLINE} / {@code PODPIS_GOV_PL})
     * signature for ceremony initiation. Reuses the own-signature resolution, then requires a provider
     * method.
     */
    private DocumentSignatureEntity requireOwnPendingProviderSignature(SignableDocumentEntity doc) {
        Long callerId = requireCurrentUserId();
        String callerRole = actingProjectRole(projectIdOf(doc), callerId);

        List<DocumentSignatureEntity> own = ownPendingSignatures(doc, callerId, callerRole);
        String typeCode = doc.getDocumentType() != null ? doc.getDocumentType().getCode() : null;
        authorizationGuard.assertAllowed(
                callerRole, SigningAuthorizationGuard.DocumentAction.SIGN, typeCode, true);

        for (DocumentSignatureEntity signature : own) {
            if (signature.getMethod() == SignatureMethod.ONLINE
                    || signature.getMethod() == SignatureMethod.PODPIS_GOV_PL) {
                return signature;
            }
        }
        throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, "signature");
    }

    /**
     * The caller's own {@code PENDING} signatures on a document — an explicit {@code signerUser}
     * match, or a role-resolved (sign-any) signature with no explicit user whose {@code signerRole}
     * equals the caller's project role (R4.3, decision 8).
     */
    private List<DocumentSignatureEntity> ownPendingSignatures(
            SignableDocumentEntity doc, Long callerId, String callerRole) {
        List<DocumentSignatureEntity> own = new ArrayList<>();
        for (DocumentSignatureEntity signature : doc.getSignatures()) {
            if (signature == null || signature.getStatus() != SignatureStatus.PENDING) {
                continue;
            }
            Long signerUserId = signature.getSignerUser() != null ? signature.getSignerUser().getId() : null;
            boolean explicitMatch = signerUserId != null && signerUserId.equals(callerId);
            boolean roleMatch = signerUserId == null
                    && callerRole != null
                    && callerRole.equals(signature.getSignerRole());
            if (explicitMatch || roleMatch) {
                own.add(signature);
            }
        }
        return own;
    }

    /** Resolves the pending signature a {@code providerRef} was issued for, or 404 (webhook match). */
    private DocumentSignatureEntity requireSignatureByProviderRef(String providerRef) {
        for (DocumentSignatureEntity signature : signatureDao.findAll()) {
            if (signature != null
                    && signature.getStatus() == SignatureStatus.PENDING
                    && providerRef.equals(signature.getProviderRef())) {
                return signature;
            }
        }
        throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, "providerRef");
    }

    // --- misc helpers ---

    private SignableDocumentEntity requireDocument(Long id) {
        return documentDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, "documentId", id));
    }

    /** A completion / decline is permitted only while the document is {@code PENDING_SIGNATURES}. */
    private void assertPending(SignableDocumentEntity doc) {
        if (doc.getStatus() != DocumentStatus.PENDING_SIGNATURES) {
            throw new ForemenApiException(HttpStatus.CONFLICT, DOCUMENT_NOT_PENDING_MESSAGE);
        }
    }

    private static Long projectIdOf(SignableDocumentEntity doc) {
        return doc.getProject() != null && doc.getProject().getId() != null ? doc.getProject().getId() : null;
    }

    /** Builds the provider {@link Signer} carrier from a signature's resolved signer identity. */
    private Signer toSigner(DocumentSignatureEntity signature) {
        UserEntity user = signature.getSignerUser();
        if (user == null) {
            return new Signer(null, null, null, signature.getSignerRole());
        }
        return new Signer(user.getId(), user.getEmail(), user.getName(), signature.getSignerRole());
    }

    /** Null-safe parse of the frozen artifact uri; a null/blank uri yields {@code null}. */
    private static URI toUri(String documentUri) {
        if (documentUri == null || documentUri.isBlank()) {
            return null;
        }
        try {
            return URI.create(documentUri);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The derived progress of a document (unused by the mutation paths directly but kept so the DTO
     * assembly mirrors {@link SignableDocumentService}).
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

    /** Assembles the client-reachable DTO with the derived progress wired in (R4.5). */
    private SignableDocumentDto toDto(SignableDocumentEntity doc) {
        SignableDocumentDto base = dtoMapper.toDto(doc);
        return new SignableDocumentDto(
                base.id(), base.projectId(), base.documentTypeCode(), base.status(),
                base.signatureLevel(), base.title(), base.sourceRef(), base.templateLocale(),
                base.documentUri(), base.contentHash(), base.createdAt(),
                base.signatures(), base.media(), base.formFields(), toProgressDto(doc.getSignatures()));
    }

    // --- auth / role resolution (mirrors SignableDocumentService) ---

    private Long requireCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }
        Long userId = parseUserId(auth.getName());
        if (userId == null) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }
        return userId;
    }

    /**
     * The acting caller's role code for {@link SigningAuthorizationGuard}: {@code "ADMIN"} when the
     * authentication carries the ADMIN authority; otherwise the caller's project role on
     * {@code projectId}, else the global role code. {@code null} when none resolves (treated as
     * forbidden by the guard).
     */
    private String actingProjectRole(Long projectId, Long userId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            for (GrantedAuthority ga : auth.getAuthorities()) {
                String a = ga.getAuthority();
                if ("ROLE_ADMIN".equals(a) || "ADMIN".equals(a)) {
                    return "ADMIN";
                }
            }
        }
        if (projectId != null) {
            ProjectMemberEntity member =
                    projectMemberDao.findByUserIdAndProjectId(userId, projectId).orElse(null);
            if (member != null && member.getProjectRole() != null) {
                return member.getProjectRole().getCode();
            }
        }
        UserEntity user = userDao.findById(userId).orElse(null);
        if (user != null && user.getRole() != null) {
            return user.getRole().getCode();
        }
        return null;
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

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
