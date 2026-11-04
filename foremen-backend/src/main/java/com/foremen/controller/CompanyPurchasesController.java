package com.foremen.controller;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.CompanyDocumentDetailDto;
import com.foremen.controller.model.CompanyDocumentRowDto;
import com.foremen.controller.model.CompanyLineRowDto;
import com.foremen.controller.model.CompanyRelinkRequest;
import com.foremen.controller.model.CompanyRelinkResponse;
import com.foremen.service.CompanyPurchasesService;
import com.foremen.service.procurement.LinkStatus;

import lombok.RequiredArgsConstructor;

/**
 * FOR-07-03 (Requirements 4.1-4.5, 4.7, 4.8, 6.1-6.3, 10.3) - the company-wide purchases API under
 * {@code /api/company-purchases}, gated by the {@code COMPANY_PURCHASES} role grant (not by project
 * membership).
 *
 * <p><b>ABAC (entity-creation-rules step 3).</b> Class-level
 * {@link PermissionResource @PermissionResource("COMPANY_PURCHASES")} plus a method-level
 * {@link RequiresPermission @RequiresPermission} on every handler, so the startup
 * {@code PermissionAnnotationValidator} classifies each handler {@code COMPLETE}.
 * <ul>
 *   <li>{@code GET /documents}, {@code GET /documents/{id}}, {@code GET /lines} &rarr; {@code READ}</li>
 *   <li>{@code POST /documents/{id}/reprocess} &rarr; {@code UPDATE}</li>
 *   <li>{@code PUT /documents/{id}/project} (relink) &rarr; {@code UPDATE}</li>
 *   <li>{@code DELETE /documents/{id}} &rarr; {@code DELETE}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/company-purchases")
@RequiredArgsConstructor
@PermissionResource("COMPANY_PURCHASES")
public class CompanyPurchasesController {

    private static final String RESOURCE = "COMPANY_PURCHASES";
    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    private final CompanyPurchasesService service;

    /**
     * Paged documents, newest upload first. {@code projectId} (a project), {@code noProject=true}
     * (company expenses) or neither (all); both together are a 400.
     */
    @GetMapping("/documents")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<Page<CompanyDocumentRowDto>> listDocuments(
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "noProject", defaultValue = "false") boolean noProject,
            @RequestParam(value = "linkStatus", required = false) LinkStatus linkStatus,
            @RequestParam(value = "uploadedFrom", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadedFrom,
            @RequestParam(value = "uploadedTo", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadedTo,
            @RequestParam(value = "uploadedById", required = false) Long uploadedById,
            @RequestParam(value = "expenseCategoryId", required = false) Long expenseCategoryId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return ResponseEntity.ok(service.listDocuments(
                new CompanyPurchasesService.Filter(projectId, noProject, linkStatus, uploadedFrom,
                        uploadedTo, uploadedById, expenseCategoryId, q),
                page, size));
    }

    /** One document with its lines and signed raw / processed image URLs. */
    @GetMapping("/documents/{id}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<CompanyDocumentDetailDto> getDocument(@PathVariable Long id) {
        return ResponseEntity.ok(service.getDocument(id));
    }

    /** Paged flat line rows, with the same filters as {@code /documents}. */
    @GetMapping("/lines")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<Page<CompanyLineRowDto>> listLines(
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "noProject", defaultValue = "false") boolean noProject,
            @RequestParam(value = "linkStatus", required = false) LinkStatus linkStatus,
            @RequestParam(value = "uploadedFrom", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadedFrom,
            @RequestParam(value = "uploadedTo", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadedTo,
            @RequestParam(value = "uploadedById", required = false) Long uploadedById,
            @RequestParam(value = "expenseCategoryId", required = false) Long expenseCategoryId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return ResponseEntity.ok(service.listLines(
                new CompanyPurchasesService.Filter(projectId, noProject, linkStatus, uploadedFrom,
                        uploadedTo, uploadedById, expenseCategoryId, q),
                page, size));
    }

    /** Assigns or changes the document's project and re-matches its lines (ADMIN / FINANCIER). */
    @PutMapping("/documents/{id}/project")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<CompanyRelinkResponse> relink(@PathVariable Long id,
                                                        @RequestBody CompanyRelinkRequest request) {
        return ResponseEntity.ok(service.relink(id, request == null ? null : request.projectId()));
    }

    /** Re-runs OCR for a {@code FAILED} / {@code PARSED} document (project or no project). */
    @PostMapping("/documents/{id}/reprocess")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<CompanyDocumentRowDto> reprocess(@PathVariable Long id) {
        return ResponseEntity.ok(service.reprocess(id));
    }

    /** Deletes a document (project or no project) with image cleanup and the ledger-link rules. */
    @DeleteMapping("/documents/{id}")
    @RequiresPermission(resource = RESOURCE, operation = DELETE)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
