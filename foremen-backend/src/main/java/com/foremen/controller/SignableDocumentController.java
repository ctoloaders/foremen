package com.foremen.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.document.CreateDocumentRequest;
import com.foremen.service.document.DeclineRequest;
import com.foremen.service.document.DocumentBodyInput;
import com.foremen.service.document.DocumentMediaService;
import com.foremen.service.document.DocumentMediaSummaryDto;
import com.foremen.service.document.FormFieldValuesInput;
import com.foremen.service.document.RequestSignaturesInput;
import com.foremen.service.document.SignRequest;
import com.foremen.service.document.SignableDocumentDto;
import com.foremen.service.document.SignableDocumentService;
import com.foremen.service.document.SignatureService;
import com.foremen.service.document.SigningProgressDto;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-08 (Requirements 8.1, 8.2, 8.6, 9.2, 13.1, 13.2; design §Backend controllers): the
 * project-scoped {@code /api/signable-documents} controller — the single HTTP surface for the
 * document + signing lifecycle of Requirement 13.1. It is a thin delegator: every lifecycle decision
 * lives in {@link SignableDocumentService} (create / generate / saveBody / requestSignatures /
 * fillFormFields / voidDocument / progress), every per-method signature completion / decline in
 * {@link SignatureService}, and all media handling in {@link DocumentMediaService}.
 *
 * <h2>ABAC guarding (R8.1, R8.2, R8.6, per {@code entity-creation-rules} step 3)</h2>
 * The class carries {@link PermissionResource @PermissionResource("SIGNABLE_DOCUMENTS")}; every
 * handler carries a method-level {@link RequiresPermission @RequiresPermission} mapping to the
 * standard CRUD operations of the seeded {@code SIGNABLE_DOCUMENTS} resource (changeset 145), so the
 * controller is <b>fully annotated</b> and {@code PermissionAnnotationValidator} classifies it
 * COMPLETE at startup (R8.6). The mapping uses only standard CRUD — <b>no new ABAC operation</b> is
 * introduced for signing (R8.2):
 * <ul>
 *   <li>reads ({@code list}, {@code read}, {@code document} download, {@code media} list,
 *       {@code progress}) &rarr; {@code READ};</li>
 *   <li>{@code create} &rarr; {@code CREATE};</li>
 *   <li>every mutation / signing action ({@code generate}, {@code body}, {@code request-signatures},
 *       {@code form-fields}, {@code sign}, {@code sign-tablet}, {@code sign-provider}, {@code decline},
 *       {@code media} upload) &rarr; {@code UPDATE} — a signer completing / declining their own
 *       signature is an {@code UPDATE} of the document (R8.2);</li>
 *   <li>destructive actions ({@code void}, {@code media} delete) &rarr; {@code DELETE}.</li>
 * </ul>
 *
 * <p>The coarse CRUD grant is further narrowed server-side: {@link SignableDocumentService} and
 * {@link SignatureService} run {@code SigningAuthorizationGuard} so a CLIENT may only sign / decline /
 * fill their own fields and never create / generate / request / void (R8.4), and
 * {@link SignableDocumentService}'s {@code ProjectScopedService} project filtering restricts every
 * by-id read / mutation to the caller's accessible projects (R1.8). Client-reachable responses are
 * the confidentiality-safe {@link SignableDocumentDto} graph that never carries a cost / estimate /
 * margin field (R9.6, R13.3).
 */
@RestController
@RequestMapping("/api/signable-documents")
@RequiredArgsConstructor
@PermissionResource("SIGNABLE_DOCUMENTS")
public class SignableDocumentController {

    private static final String RESOURCE = "SIGNABLE_DOCUMENTS";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    private final SignableDocumentService documentService;
    private final SignatureService signatureService;
    private final DocumentMediaService mediaService;

    // --- reads (R13.1, R13.2 → READ) ---

    /**
     * Lists the signing documents of a project (R9.1, R13.1). Project membership is enforced by
     * {@link SignableDocumentService}'s {@code ProjectScopedService} filtering, so a caller only ever
     * sees documents of projects they may access.
     *
     * @param projectId the project whose documents to list
     * @return the project's documents as client-reachable DTOs
     */
    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<List<SignableDocumentDto>> list(@RequestParam Long projectId) {
        return ResponseEntity.ok(documentService.listByProject(projectId));
    }

    /**
     * Reads one document by id with its signatures, media, form fields, and derived progress (R9.2,
     * R13.1).
     *
     * @param id the document id
     * @return the document DTO
     */
    @GetMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<SignableDocumentDto> read(@PathVariable Long id) {
        return ResponseEntity.ok(documentService.read(id));
    }

    /**
     * The aggregate signing progress of a document — signed / total counts + the outstanding signer
     * list (R4.5, R9.4).
     *
     * @param id the document id
     * @return the signing-progress DTO
     */
    @GetMapping("/{id}/progress")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<SigningProgressDto> progress(@PathVariable Long id) {
        return ResponseEntity.ok(documentService.progress(id));
    }

    /**
     * The downloadable document artifact reference — the DRAFT body / frozen PDF object-storage uri
     * and content hash (R13.1). The bytes live in object storage (FOR-12); this returns the stored
     * reference the client resolves/streams, consistent with the object-key storage model.
     *
     * @param id the document id
     * @return the document's artifact reference
     */
    @GetMapping("/{id}/document")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<DocumentArtifactRef> document(@PathVariable Long id) {
        SignableDocumentDto doc = documentService.read(id);
        return ResponseEntity.ok(new DocumentArtifactRef(doc.documentUri(), doc.contentHash()));
    }

    /**
     * Lists the evidence / attachment media of a document (R7.1, R9.2).
     *
     * @param id the document id
     * @return the document's media summaries
     */
    @GetMapping("/{id}/media")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<List<DocumentMediaSummaryDto>> listMedia(@PathVariable Long id) {
        return ResponseEntity.ok(mediaService.list(id));
    }

    // --- create (R1.1, R13.1 → CREATE) ---

    /**
     * Creates a {@code DRAFT} document bound to a project + type (R1.1, R13.1). A CLIENT caller is
     * rejected by the service-layer {@code SigningAuthorizationGuard} (R8.4).
     *
     * @param request the create payload
     * @return the created DRAFT document DTO
     */
    @PostMapping
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<SignableDocumentDto> create(@RequestBody CreateDocumentRequest request) {
        return ResponseEntity.ok(documentService.create(request));
    }

    // --- mutations / signing actions (R8.2 → UPDATE) ---

    /**
     * Renders the active template body into the DRAFT body by merge (R3.5, R13.1); rejected once the
     * document leaves {@code DRAFT}. Signing is an {@code UPDATE} — no new operation (R8.2).
     *
     * @param id the DRAFT document id
     * @return the document DTO with the generated body
     */
    @PostMapping("/{id}/generate")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<SignableDocumentDto> generate(@PathVariable Long id) {
        return ResponseEntity.ok(documentService.generate(id));
    }

    /**
     * Saves an edited DRAFT body (the template-editor save path, R3.5, R13.1); rejected once the
     * document leaves {@code DRAFT}.
     *
     * @param id   the DRAFT document id
     * @param body the new DRAFT body text
     * @return the document DTO with the saved body
     */
    @PutMapping("/{id}/body")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<SignableDocumentDto> saveBody(
            @PathVariable Long id, @RequestBody DocumentBodyInput body) {
        return ResponseEntity.ok(documentService.saveBody(id, body));
    }

    /**
     * Requests signatures: freezes the immutable PDF, sets {@code contentHash}, creates one
     * {@code PENDING} signature per resolved signer ({@code >= 1}), and moves the document to
     * {@code PENDING_SIGNATURES} (R1.5, R4.2, R13.1). Each designated signer is notified
     * (best-effort) by the service's after-commit event.
     *
     * @param id    the DRAFT document id
     * @param input the signing method + optional level + signer set
     * @return the frozen, pending document DTO
     */
    @PostMapping("/{id}/request-signatures")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<SignableDocumentDto> requestSignatures(
            @PathVariable Long id, @RequestBody RequestSignaturesInput input) {
        return ResponseEntity.ok(documentService.requestSignatures(id, input));
    }

    /**
     * Fills the document's form-field values (R3.6, R13.1); permitted only while
     * {@code PENDING_SIGNATURES}. A CLIENT caller is limited server-side to their own fields (R8.4).
     *
     * @param id    the {@code PENDING_SIGNATURES} document id
     * @param input the form-field values to apply (by key)
     * @return the document DTO with the updated form fields
     */
    @PutMapping("/{id}/form-fields")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<SignableDocumentDto> fillFormFields(
            @PathVariable Long id, @RequestBody FormFieldValuesInput input) {
        return ResponseEntity.ok(documentService.fillFormFields(id, input));
    }

    /**
     * Completes the caller's {@code PRINT} signature by uploading the wet-ink scan as evidence (R6.2,
     * R13.1). The scan is stored first; only then is the signature marked {@code SIGNED} (Property 16).
     *
     * @param id   the {@code PENDING_SIGNATURES} document id
     * @param file the wet-ink scan evidence
     * @return the (possibly now-{@code SIGNED}) document DTO
     */
    @PostMapping("/{id}/sign")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<SignableDocumentDto> sign(
            @PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(signatureService.completeViaScan(id, file));
    }

    /**
     * Completes the caller's {@code TABLET_INITIALS} signature by uploading the captured on-tablet
     * {@code parafka} image as evidence (R6.3, R13.1). The image is stored first; only then is the
     * signature marked {@code SIGNED} (Property 16).
     *
     * @param id   the {@code PENDING_SIGNATURES} document id
     * @param file the captured initial image evidence
     * @return the (possibly now-{@code SIGNED}) document DTO
     */
    @PostMapping("/{id}/sign-tablet")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<SignableDocumentDto> signTablet(
            @PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(signatureService.completeViaTablet(id, file));
    }

    /**
     * Initiates / advances the caller's {@code ONLINE} / {@code PODPIS_GOV_PL} provider ceremony
     * (R6.4, R13.1). The signature stays {@code PENDING} until the provider webhook
     * ({@code SignatureCallbackController}) arrives; the {@code SIGNED} transition is gated there by
     * the hash-integrity check against the frozen artifact.
     *
     * @param id      the {@code PENDING_SIGNATURES} document id
     * @param request the sign request (optional explicit signer)
     * @return the document DTO with the ceremony initiated (still pending)
     */
    @PostMapping("/{id}/sign-provider")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<SignableDocumentDto> signProvider(
            @PathVariable Long id, @RequestBody SignRequest request) {
        return ResponseEntity.ok(signatureService.completeViaProvider(id, request));
    }

    /**
     * Declines the caller's own signature with a reason (R4.4, R13.1); does NOT by itself void the
     * document. A decline is an {@code UPDATE} of the document (R8.2).
     *
     * @param id      the {@code PENDING_SIGNATURES} document id
     * @param request the decline payload carrying the reason
     * @return the document DTO with the caller's signature declined
     */
    @PostMapping("/{id}/decline")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<SignableDocumentDto> decline(
            @PathVariable Long id, @RequestBody DeclineRequest request) {
        return ResponseEntity.ok(signatureService.decline(id, request));
    }

    /**
     * Uploads an evidence / attachment media file to a document (R7.1, R13.1) — an additive mutation
     * of the document, so {@code UPDATE} (R8.2).
     *
     * @param id   the document id
     * @param file the media file to store
     * @return the updated document DTO
     */
    @PostMapping("/{id}/media")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<SignableDocumentDto> uploadMedia(
            @PathVariable Long id, @RequestPart("file") MultipartFile file) {
        mediaService.upload(id, file, com.foremen.dao.model.DocumentMediaKind.ATTACHMENT);
        return ResponseEntity.ok(documentService.read(id));
    }

    // --- destructive actions (→ DELETE) ---

    /**
     * Voids a {@code DRAFT} / {@code PENDING_SIGNATURES} document (R1.3, R1.7, R13.1); a
     * {@code SIGNED} document is terminal and rejected. Still-pending signers are notified
     * (best-effort) by the service's after-commit event. A void is a destructive lifecycle action
     * &rarr; {@code DELETE}.
     *
     * @param id the document id to void
     * @return the voided document DTO
     */
    @PostMapping("/{id}/void")
    @RequiresPermission(resource = RESOURCE, operation = DELETE)
    public ResponseEntity<SignableDocumentDto> voidDocument(@PathVariable Long id) {
        return ResponseEntity.ok(documentService.voidDocument(id));
    }

    /**
     * Deletes an evidence / attachment media row (R7.4, R13.1); refused for media backing a
     * {@code SIGNED} signature. A media delete is destructive &rarr; {@code DELETE}.
     *
     * @param id      the owning document id (path scope)
     * @param mediaId the media id to delete
     * @return 204 No Content
     */
    @DeleteMapping("/{id}/media/{mediaId}")
    @RequiresPermission(resource = RESOURCE, operation = DELETE)
    public ResponseEntity<Void> deleteMedia(@PathVariable Long id, @PathVariable Long mediaId) {
        mediaService.delete(mediaId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The downloadable artifact reference of a document — the object-storage uri + content hash of
     * the DRAFT body / frozen PDF (R13.1). Carries no cost / estimate / margin data (R9.6, R13.3).
     *
     * @param documentUri the DRAFT body / frozen PDF object-storage uri, or {@code null}
     * @param contentHash the sha-256 of the body / frozen PDF, or {@code null}
     */
    public record DocumentArtifactRef(String documentUri, String contentHash) {
    }
}
