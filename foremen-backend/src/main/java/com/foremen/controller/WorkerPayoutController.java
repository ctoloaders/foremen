package com.foremen.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.service.payroll.WorkerPayoutService;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.payroll.dto.PayrollFilter;
import com.foremen.service.payroll.dto.PayrollView;
import com.foremen.service.payroll.dto.WorkerPayoutDto;

import lombok.RequiredArgsConstructor;

/**
 * FOR-06-11 (design §Backend controller; Requirements 4.1, 7.4, 10.1, 10.3, 11.1): the ABAC-wired
 * controller for payroll — the money-OUT counterpart of the FOR-06-07 {@link PaymentScheduleController}.
 * It exposes the project-scoped payout surface of the <b>Rozliczenie wynagrodzeń</b> execution tab:
 * the list + per-worker remaining advance credit, the create-dialog suggestion preview, the create,
 * the mark-paid state transition, and the hard-delete of a {@code PLANNED} payout.
 *
 * <h2>ABAC guarding (R10.3, entity-creation-rules step 3)</h2>
 * The class carries {@link PermissionResource @PermissionResource("PAYROLL")} (the resource seeded by
 * task 7.1); every in-scope handler carries a method-level {@link RequiresPermission @RequiresPermission}
 * that takes precedence over the class default (mirroring {@link PaymentScheduleController} /
 * {@link OfferController}), so the controller is <b>fully annotated</b> and
 * {@code PermissionAnnotationValidator} classifies it COMPLETE at startup — no half-annotated handler
 * fails the boot. The lifecycle actions map onto the <b>standard CRUD</b> operations — <b>no new ABAC
 * operation</b> is introduced (R10.4):
 *
 * <ul>
 *   <li>{@code list} / {@code suggest} &rarr; {@code PAYROLL} READ (the tab list + the dialog preview).</li>
 *   <li>{@code create} &rarr; {@code PAYROLL} CREATE (issue a {@code PLANNED} payout, R4).</li>
 *   <li>{@code pay} &rarr; {@code PAYROLL} UPDATE ({@code PLANNED → PAID} is a state transition, R7.1).</li>
 *   <li>{@code delete} &rarr; {@code PAYROLL} DELETE (hard-delete a {@code PLANNED} payout only, R7.3).</li>
 * </ul>
 *
 * <p>The {@code PAYROLL} resource scopes by {@code project.id} via {@link WorkerPayoutService}
 * ({@code ProjectScopedService}); a caller reaches only payouts of projects they belong to (R10.5).
 * Per the seeded matrix (task 7.1): ADMIN / MANAGER / FINANCIER get full CRUD; FOREMAN gets READ
 * only (visibility, no issuing); CLIENT and WORKER have no grant. The {@code PLANNED}-only mutability
 * / delete rules are enforced in the service <b>on top of</b> the ABAC grant (R7).
 *
 * <p>Controllers are thin: every lifecycle decision + invariant lives in {@link WorkerPayoutService}
 * and its pure collaborators; this class only maps HTTP to the service calls and renders the DTOs.
 */
@RestController
@RequestMapping("/api/worker-payouts")
@RequiredArgsConstructor
@PermissionResource("PAYROLL")
public class WorkerPayoutController {

    private static final String PAYROLL_RESOURCE = "PAYROLL";
    private static final String CREATE_OPERATION = "CREATE";
    private static final String READ_OPERATION = "READ";
    private static final String UPDATE_OPERATION = "UPDATE";
    private static final String DELETE_OPERATION = "DELETE";

    private final WorkerPayoutService workerPayoutService;

    // --- List (R11.1, R5.8) --------------------------------------------------------------------

    /**
     * The project's payroll read model — the payouts (optionally narrowed by {@code workerId} /
     * {@code status}) plus each worker's remaining advance credit (R11.1, R5.8). {@code PAYROLL}
     * READ; the service enforces project scope so another project's payouts are never returned
     * (R10.5).
     *
     * @param projectId the owning project id (project scope is enforced by the service)
     * @param workerId  narrow to a single worker's payouts, or {@code null} for all
     * @param status    narrow to {@code PLANNED} / {@code PAID}, or {@code null} for all
     */
    @GetMapping
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<PayrollView> list(
            @RequestParam Long projectId,
            @RequestParam(required = false) Long workerId,
            @RequestParam(required = false) PayoutStatus status) {
        return ResponseEntity.ok(workerPayoutService.list(projectId, new PayrollFilter(workerId, status)));
    }

    // --- Suggest (R3) --------------------------------------------------------------------------

    /**
     * The create-dialog preview for a worker + period — the gross (Σ FOR-06-10 {@code Cost_Value}
     * over the unpaid {@code APPROVED} lines, or the planned basis for an advance), the credit
     * arithmetic, the suggested cash amount, and the per-line breakdown (R3). {@code PAYROLL} READ.
     *
     * @param projectId the owning project id (project scope is enforced by the service)
     * @param workerId  the worker the preview is for
     * @param from      the period start (inclusive, ISO date)
     * @param to        the period end (inclusive, ISO date)
     */
    @GetMapping("/suggest")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<PayoutSuggestionDto> suggest(
            @RequestParam Long projectId,
            @RequestParam Long workerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(workerPayoutService.suggest(projectId, workerId, from, to));
    }

    // --- Create (R4, R5) -----------------------------------------------------------------------

    /**
     * Creates a {@code PLANNED} payout for a worker over a period on the given project — links the
     * covered unpaid {@code APPROVED} lines + applies advance credit for a regular payout, or adds
     * worker credit for an advance, all in one transaction (R4, R5). {@code PAYROLL} CREATE. The
     * owning project is supplied as a request param (the {@link CreatePayoutRequest} body carries no
     * project id).
     *
     * @param projectId the owning project id (project scope is enforced by the service)
     * @param request   the create payload (worker, period, channel, amount, note)
     */
    @PostMapping
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = CREATE_OPERATION)
    public ResponseEntity<WorkerPayoutDto> create(
            @RequestParam Long projectId,
            @RequestBody CreatePayoutRequest request) {
        return ResponseEntity.ok(workerPayoutService.create(projectId, request));
    }

    // --- Mark paid (R7.1, R7.2) ----------------------------------------------------------------

    /**
     * Marks a {@code PLANNED} payout {@code PAID}, sets {@code paidAt}, and freezes the record
     * (R7.1); a non-{@code PLANNED} source is rejected ({@code 409}, R7.2). This is a state
     * transition, so it maps onto {@code PAYROLL} UPDATE — <b>no</b> new ABAC operation.
     *
     * @param id the payout to mark paid
     */
    @PostMapping("/{id}/pay")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<WorkerPayoutDto> pay(@PathVariable Long id) {
        return ResponseEntity.ok(workerPayoutService.markPaid(id));
    }

    // --- Delete (R7.3, R5.7) -------------------------------------------------------------------

    /**
     * Hard-deletes a {@code PLANNED} payout — releasing its linked report lines and reversing its
     * advance-credit effect (R7.3); a {@code PAID} delete and an advance whose credit a later regular
     * payout already consumed are rejected ({@code 409}, R5.7, R7.2). {@code PAYROLL} DELETE; the
     * {@code PLANNED}-only rule is enforced in the service on top of the ABAC grant.
     *
     * @param id the {@code PLANNED} payout to delete
     */
    @DeleteMapping("/{id}")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = DELETE_OPERATION)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        workerPayoutService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
