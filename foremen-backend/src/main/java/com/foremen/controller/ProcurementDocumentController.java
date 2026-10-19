package com.foremen.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.ProcurementDocumentDtoExtendedModel;
import com.foremen.controller.model.ProcurementDocumentDtoModel;
import com.foremen.controller.model.ProcurementDocumentImagesResponse;
import com.foremen.controller.model.ProcurementDocumentReprocessResponse;
import com.foremen.controller.model.ProcurementDocumentStatusResponse;
import com.foremen.controller.model.ProcurementDocumentUploadResponse;
import com.foremen.controller.model.mapper.ProcurementDocumentControllerMapper;
import com.foremen.service.ProcurementDocumentService;
import com.foremen.service.model.ProcurementDocumentServiceExtendedModel;
import com.foremen.service.model.ProcurementDocumentServiceModel;

import lombok.RequiredArgsConstructor;

/**
 * FOR-06-03 (Requirements 6.1, 7.3, 8.1; design §5.5) — REST controller for the project-scoped
 * {@code ProcurementDocument} resource, exposed under {@code /api/procurement-documents}.
 *
 * <p>Procurement intake is <b>file-driven</b>: a document is created by a multipart upload and
 * re-run by the re-process endpoint — neither takes a JSON create/update body — so this controller
 * does <b>not</b> implement the generic {@link AdminController} CRUD contract (the standard
 * {@code POST}/{@code PUT} create/update request shape does not apply). It instead wires the bespoke
 * {@link ProcurementDocumentService} upload / list / read / re-process / delete / images surface
 * directly, mapping each service model to its DTO via the
 * {@link ProcurementDocumentControllerMapper}.
 *
 * <p><b>ABAC (entity-creation-rules step 3).</b> The class-level
 * {@link PermissionResource @PermissionResource("PURCHASES")} declares the resource; because no
 * endpoint is inherited from {@link AdminController} (so no {@code @PermissionOperation} is
 * inherited), every in-scope handler carries a method-level
 * {@link RequiresPermission @RequiresPermission}, which the {@code PermissionResolver} treats as a
 * complete declaration. This keeps the controller fully annotated — the startup
 * {@code PermissionAnnotationValidator} classifies every handler {@code COMPLETE}, so no
 * half-annotation can degrade an endpoint into an unguarded one (R7.3). The {@code PURCHASES}
 * resource row and its role grants are seeded by changeset 169 (task 6.2).
 *
 * <p><b>Endpoints.</b> Base path {@code /api/procurement-documents}:
 * <ul>
 *   <li>{@code POST /} (multipart {@code projectId} + {@code file}) &rarr; {@code CREATE} — upload a
 *       raw receipt/invoice; the service stores it, creates the document, and runs OCR + match
 *       inline (R2, R8.1).</li>
 *   <li>{@code GET /?projectId=} &rarr; {@code READ} — the project-scoped document list; the
 *       {@code projectId} filter is layered on top of the service's automatic project-scope
 *       access filter.</li>
 *   <li>{@code GET /{id}} &rarr; {@code READ} — the single-document read-only review projection
 *       (document + lines + proposed matches).</li>
 *   <li>{@code POST /{id}/reprocess} &rarr; {@code UPDATE} — re-run OCR + match on the already-stored
 *       raw bytes (R8); the document state is a mutation of an existing row, so it resolves to
 *       {@code PURCHASES}/{@code UPDATE}.</li>
 *   <li>{@code DELETE /{id}} &rarr; {@code DELETE} — delete the document (cascades to lines, cleans
 *       up object keys).</li>
 *   <li>{@code GET /{id}/images} &rarr; {@code READ} — resolve the raw + processed image URLs for
 *       the viewer.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/procurement-documents")
@RequiredArgsConstructor
@PermissionResource("PURCHASES")
public class ProcurementDocumentController {

    private static final String RESOURCE = "PURCHASES";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    private final ProcurementDocumentService service;
    private final ProcurementDocumentControllerMapper controllerMapper;

    /**
     * Uploads a primary procurement document (image or PDF) on an {@code ACTIVE} project (R2): the
     * service validates storage availability, the content type and the size before storing anything,
     * persists an {@code UPLOADED} document, and runs the OCR + parse + match orchestration inline
     * (R2.5), so the echoed response carries the resulting lifecycle state. Resolves to
     * {@code PURCHASES}/{@code CREATE}.
     *
     * @param projectId the owning {@code ACTIVE} project
     * @param file      the uploaded image or PDF
     * @return the uploaded document projection (status {@code PROCESSING}/{@code PARSED}/
     *         {@code FAILED}/{@code UPLOADED} depending on the inline OCR outcome)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<ProcurementDocumentUploadResponse> upload(
            @RequestParam("projectId") Long projectId,
            @RequestPart("file") MultipartFile file) {
        ProcurementDocumentServiceExtendedModel uploaded = service.upload(projectId, file);
        return ResponseEntity.ok(controllerMapper.toUploadResponse(uploaded));
    }

    /**
     * Lists the procurement documents of a project (R6.1) as the project-scoped list rows. The
     * {@code projectId} request filter selects the project's documents; the service's inherited
     * project-scope access filter ({@code ProjectScopedService.addRequiredQuery}) is applied on top,
     * so a caller only ever sees documents of projects they may access. Resolves to
     * {@code PURCHASES}/{@code READ}.
     *
     * @param projectId the project whose documents to list
     * @param pageable  the paging / sorting request
     * @return a page of document list rows
     */
    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<Page<ProcurementDocumentDtoModel>> list(
            @RequestParam("projectId") Long projectId,
            Pageable pageable) {
        Page<ProcurementDocumentServiceModel> page =
                service.find(pageable, "project.id==" + projectId);
        return ResponseEntity.ok(page.map(controllerMapper::toDto));
    }

    /**
     * Reads a single document's read-only review projection (R6): the document, its ordered lines,
     * and the proposed matches. The service applies the project-scope access guard (an out-of-scope
     * document is indistinguishable from a missing one). Resolves to {@code PURCHASES}/{@code READ}.
     *
     * @param id the document id
     * @return the single-document detail DTO
     */
    @GetMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<ProcurementDocumentDtoExtendedModel> findById(@PathVariable Long id) {
        ProcurementDocumentServiceExtendedModel model = service.findById(id);
        return ResponseEntity.ok(controllerMapper.toExtendedDto(model));
    }

    /**
     * Amendment A1 (R16.1) — the lightweight status poll endpoint. Returns ONLY
     * {@code {id, status, stage, failureReason}} (no lines, no signed image URLs, no derived names)
     * so the client can cheaply poll an in-progress document (and poll many in-progress list rows)
     * until it reaches a terminal state, then fetch the full projection via {@link #findById}
     * (the "status then full dto" contract). Same project-scope access guard as the other reads — an
     * out-of-scope document is indistinguishable from a missing one. Resolves to
     * {@code PURCHASES}/{@code READ}.
     *
     * @param id the document id
     * @return the lightweight lifecycle status projection
     */
    @GetMapping("/{id}/status")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<ProcurementDocumentStatusResponse> status(@PathVariable Long id) {
        ProcurementDocumentService.StatusView view = service.status(id);
        return ResponseEntity.ok(ProcurementDocumentStatusResponse.of(
                view.id(), view.status(), view.failureReason()));
    }

    /**
     * Re-runs OCR + parse + match on an already-stored document's raw bytes (R8), only for a
     * {@code FAILED} or {@code PARSED} document on an {@code ACTIVE} project; it regenerates the
     * processed rendition and replaces all lines atomically. Re-processing mutates the state of an
     * existing document, so it resolves to {@code PURCHASES}/{@code UPDATE} (the stage gate is
     * enforced in the service).
     *
     * @param id the document to re-process
     * @return the refreshed document projection
     */
    @PostMapping("/{id}/reprocess")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<ProcurementDocumentReprocessResponse> reprocess(@PathVariable Long id) {
        ProcurementDocumentServiceExtendedModel reprocessed = service.reprocess(id);
        return ResponseEntity.ok(controllerMapper.toReprocessResponse(reprocessed));
    }

    /**
     * Deletes a document (R1.4): the service checks project-scope access, cascades to the owned
     * {@code procurement_lines}, cleans up both stored object keys, and writes an audit row. Resolves
     * to {@code PURCHASES}/{@code DELETE}.
     *
     * @param id the document to delete
     * @return {@code 204 No Content}
     */
    @DeleteMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = DELETE)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Resolves the raw + processed image URLs for the document viewer (R6.4). Reuses the single-read
     * projection (which the service resolves from the stored object keys via the shared
     * {@code ImageStorage}), so the same project-scope access guard applies. On a {@code FAILED}
     * document the raw (and processed, if one was produced) URLs remain available. Resolves to
     * {@code PURCHASES}/{@code READ}.
     *
     * @param id the document id
     * @return the raw + processed image URLs
     */
    @GetMapping("/{id}/images")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<ProcurementDocumentImagesResponse> images(@PathVariable Long id) {
        ProcurementDocumentServiceExtendedModel model = service.findById(id);
        return ResponseEntity.ok(new ProcurementDocumentImagesResponse(
                model.getId(), model.getRawImageUrl(), model.getProcessedImageUrl()));
    }
}
