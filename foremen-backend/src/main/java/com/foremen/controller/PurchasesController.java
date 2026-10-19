package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.ClassifyRequestModel;
import com.foremen.controller.model.PurchaseLineDtoModel;
import com.foremen.controller.model.PurchaseLineListResponseModel;
import com.foremen.controller.model.RelinkRequestModel;
import com.foremen.controller.model.mapper.PurchaseLineControllerMapper;
import com.foremen.dao.model.MatchStatus;
import com.foremen.service.PurchaseLineReconciliationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * FOR-06-04 (Requirements 6.1, 7.1, 7.2; design §5.4) — REST controller for procurement
 * <strong>reconciliation</strong>, exposed under {@code /api/procurement-lines}. It turns the
 * FOR-06-03 read-only proposed-match review into an actionable reconciled purchases list: the
 * project-wide list read plus the four manual overrides (re-link / clear-match / classify /
 * clear-classification) delegated to {@link PurchaseLineReconciliationService}.
 *
 * <p>Like the sibling {@link ProcurementDocumentController}, reconciliation is not a generic
 * JSON create/update CRUD resource (the mutations are focused verbs keyed on the line id with a thin
 * request body, and the list is a bespoke read model), so this controller does <b>not</b> implement
 * the {@link AdminController} CRUD contract. It wires the reconciliation service surface directly and
 * maps each returned service model to its DTO via the {@link PurchaseLineControllerMapper}.
 *
 * <p><b>ABAC (entity-creation-rules step 3; R7.1, R7.2).</b> The class-level
 * {@link PermissionResource @PermissionResource("PURCHASES")} declares the resource — the same
 * resource FOR-06-03 seeded (changeset 169); <b>no new ABAC resource</b> is introduced and the
 * {@code PURCHASES} matrix is unchanged. Because no endpoint is inherited from {@link AdminController}
 * (so no {@code @PermissionOperation} is inherited), every in-scope handler carries a method-level
 * {@link RequiresPermission @RequiresPermission}, which the {@code PermissionResolver} treats as a
 * complete declaration. This keeps the controller fully annotated, so the startup
 * {@code PermissionAnnotationValidator} classifies every handler {@code COMPLETE} and no
 * half-annotation can degrade an endpoint into an unguarded one. A READ-only caller resolves no
 * {@code UPDATE} and cannot reconcile (R6.4).
 *
 * <p><b>Endpoints.</b> Base path {@code /api/procurement-lines}:
 * <ul>
 *   <li>{@code GET /?projectId=&documentId=&matchStatus=&unplannedCategoryId=} &rarr; {@code READ} —
 *       the project-wide reconciled purchases list, narrowed by the optional filters.</li>
 *   <li>{@code PUT /{id}/match} (body {@code { materialId }}) &rarr; {@code UPDATE} — re-link the
 *       line to a chosen planned material.</li>
 *   <li>{@code DELETE /{id}/match} &rarr; {@code UPDATE} — clear the line's match.</li>
 *   <li>{@code PUT /{id}/classify} (body {@code { categoryId }}) &rarr; {@code UPDATE} — classify a
 *       deliberately unmatched line with an unplanned-expense category.</li>
 *   <li>{@code DELETE /{id}/classify} &rarr; {@code UPDATE} — clear the line's classification.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/procurement-lines")
@RequiredArgsConstructor
@PermissionResource("PURCHASES")
public class PurchasesController {

    private static final String RESOURCE = "PURCHASES";
    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";

    private final PurchaseLineReconciliationService service;
    private final PurchaseLineControllerMapper controllerMapper;

    /**
     * Lists the project-wide reconciled purchases (R4.1): every {@code ProcurementLine} across the
     * project's documents, project-filtered by the service's inherited {@code ProjectScopedService}
     * access filter and narrowed by the optional {@code documentId} / {@code matchStatus} /
     * {@code unplannedCategoryId} filters (R4.2). Resolves to {@code PURCHASES}/{@code READ}.
     *
     * @param projectId           the project whose lines to list (required)
     * @param documentId          optional source-document filter
     * @param matchStatus         optional match-state filter
     * @param unplannedCategoryId optional unplanned-category filter
     * @return the reconciled-list response envelope
     */
    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<PurchaseLineListResponseModel> list(
            @RequestParam("projectId") Long projectId,
            @RequestParam(value = "documentId", required = false) Long documentId,
            @RequestParam(value = "matchStatus", required = false) MatchStatus matchStatus,
            @RequestParam(value = "unplannedCategoryId", required = false) Long unplannedCategoryId) {
        return ResponseEntity.ok(controllerMapper.toListResponse(
                service.list(projectId, documentId, matchStatus, unplannedCategoryId)));
    }

    /**
     * Re-links a line to a chosen planned material (R2.1, R2.2): the service enforces the
     * {@code ACTIVE}-stage gate and verifies the material is on the project's estimate (else
     * {@code error.purchases.relink.notPlanned}, line unchanged) before setting
     * {@code MANUAL_MATCHED} / tier {@code EXACT}. Resolves to {@code PURCHASES}/{@code UPDATE}.
     *
     * @param id      the line to re-link
     * @param request the chosen planned-material id
     * @return the reconciled line row (reflects the new state immediately)
     */
    @PutMapping("/{id}/match")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<PurchaseLineDtoModel> relink(
            @PathVariable Long id,
            @Valid @RequestBody RelinkRequestModel request) {
        return ResponseEntity.ok(controllerMapper.toDto(service.relink(id, request.materialId())));
    }

    /**
     * Clears a line's match (R2.3): the service enforces the {@code ACTIVE}-stage gate and sets
     * {@code MANUAL_UNMATCHED} / tier {@code NONE} (keeping any existing unplanned category). Clearing
     * a match mutates an existing line, so it resolves to {@code PURCHASES}/{@code UPDATE}.
     *
     * @param id the line whose match to clear
     * @return the reconciled line row
     */
    @DeleteMapping("/{id}/match")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<PurchaseLineDtoModel> clearMatch(@PathVariable Long id) {
        return ResponseEntity.ok(controllerMapper.toDto(service.clearMatch(id)));
    }

    /**
     * Classifies a deliberately unmatched line with an unplanned-expense category (R3.1, R3.3, R3.4):
     * the service enforces the {@code ACTIVE}-stage gate, rejects a matched line
     * ({@code error.purchases.classify.matchedLine}), and verifies the category exists and is active
     * ({@code error.purchases.classify.unknownCategory}) before setting {@code MANUAL_UNMATCHED} with
     * the category. Resolves to {@code PURCHASES}/{@code UPDATE}.
     *
     * @param id      the line to classify
     * @param request the active unplanned-expense category id
     * @return the reconciled line row
     */
    @PutMapping("/{id}/classify")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<PurchaseLineDtoModel> classify(
            @PathVariable Long id,
            @Valid @RequestBody ClassifyRequestModel request) {
        return ResponseEntity.ok(controllerMapper.toDto(service.classify(id, request.categoryId())));
    }

    /**
     * Clears a line's classification (R3.2): the service enforces the {@code ACTIVE}-stage gate and
     * nulls the unplanned category while keeping {@code MANUAL_UNMATCHED}. Resolves to
     * {@code PURCHASES}/{@code UPDATE}.
     *
     * @param id the line whose classification to clear
     * @return the reconciled line row
     */
    @DeleteMapping("/{id}/classify")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<PurchaseLineDtoModel> clearClassification(@PathVariable Long id) {
        return ResponseEntity.ok(controllerMapper.toDto(service.clearClassification(id)));
    }
}
