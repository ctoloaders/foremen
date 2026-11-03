package com.foremen.controller;

import java.time.LocalDate;
import java.util.List;

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
import com.foremen.service.payroll.dto.PayoutAllocationDto;
import com.foremen.service.payroll.dto.PayoutFilter;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;

import lombok.RequiredArgsConstructor;

/**
 * FOR-07-06 (design §Controllers and endpoints; Requirements 4.1, 5.1, 6.1, 14.1, 14.2): the
 * ABAC-wired controller for <b>company-wide</b> worker payouts. Payouts are no longer tied to a
 * project: a payout covers the worker's unpaid {@code APPROVED} work across <b>all</b> projects and
 * its amount is distributed over the oldest unpaid report lines first. No endpoint takes a
 * {@code projectId} (R4.1, R5.1).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/worker-payouts?workerId&from&to&status} &rarr; {@code List<WorkerPayoutDto>}
 *       ({@code PAYROLL} READ).</li>
 *   <li>{@code GET /api/worker-payouts/suggest?workerId&from&to} &rarr; {@link PayoutSuggestionDto}
 *       ({@code PAYROLL} READ, R4).</li>
 *   <li>{@code POST /api/worker-payouts} with {@link CreatePayoutRequest} &rarr; {@link WorkerPayoutDto}
 *       ({@code PAYROLL} CREATE, R5).</li>
 *   <li>{@code POST /api/worker-payouts/{id}/pay} &rarr; {@link WorkerPayoutDto}
 *       ({@code PAYROLL} UPDATE — {@code PLANNED → PAID} is a state transition, R6.1).</li>
 *   <li>{@code DELETE /api/worker-payouts/{id}} &rarr; 204 ({@code PAYROLL} DELETE, R6.2–R6.4).</li>
 *   <li>{@code GET /api/worker-payouts/{id}/allocations} &rarr; {@code List<PayoutAllocationDto>}
 *       ({@code PAYROLL} READ).</li>
 * </ul>
 *
 * <h2>ABAC guarding (R14.1, R14.2)</h2>
 * The class carries {@link PermissionResource @PermissionResource("PAYROLL")} and every handler a
 * method-level {@link RequiresPermission @RequiresPermission}, so {@code PermissionAnnotationValidator}
 * classifies the controller COMPLETE at startup. Lifecycle actions map onto the standard CRUD
 * operations; no new ABAC operation is introduced. {@link WorkerPayoutService} is <b>not</b>
 * project-scoped: reads are company-wide for any role holding the {@code PAYROLL} grant (ADMIN,
 * MANAGER, FINANCIER — full CRUD; FOREMAN, CLIENT, WORKER — no grant). The {@code PLANNED}-only
 * mutability / delete rules are enforced in the service on top of the ABAC grant.
 *
 * <p>The controller is thin: validation, distribution and lifecycle rules live in
 * {@link WorkerPayoutService} and its collaborators.
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

    // --- List ----------------------------------------------------------------------------------

    /**
     * Company-wide payout list, optionally narrowed by worker, payout-date window and status.
     * {@code PAYROLL} READ.
     *
     * @param workerId narrow to a single worker's payouts, or {@code null} for all workers
     * @param from     payout-date window start (inclusive, ISO date), or {@code null}
     * @param to       payout-date window end (inclusive, ISO date), or {@code null}
     * @param status   narrow to {@code PLANNED} / {@code PAID}, or {@code null} for all
     */
    @GetMapping
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<List<WorkerPayoutDto>> list(
            @RequestParam(required = false) Long workerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) PayoutStatus status) {
        return ResponseEntity.ok(workerPayoutService.list(new PayoutFilter(workerId, from, to, status)));
    }

    // --- Suggest (R4) --------------------------------------------------------------------------

    /**
     * The create-dialog preview for a worker + period across all the worker's projects — gross,
     * remaining advance credit, credit to apply, suggested amount and the per-line breakdown with
     * the allocation preview (R4). Takes no project (R4.1). {@code PAYROLL} READ.
     *
     * @param workerId the worker the preview is for
     * @param from     the period start (inclusive, ISO date)
     * @param to       the period end (inclusive, ISO date)
     */
    @GetMapping("/suggest")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<PayoutSuggestionDto> suggest(
            @RequestParam Long workerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(workerPayoutService.suggest(workerId, from, to));
    }

    // --- Create (R5) ---------------------------------------------------------------------------

    /**
     * Creates a {@code PLANNED} payout and, for a regular payout, distributes
     * {@code amount + advanceApplied} over the worker's oldest unpaid lines in one transaction (R5).
     * The body carries no project (R5.1). {@code PAYROLL} CREATE.
     *
     * @param request worker, payout date, period, channel, optional amount and note
     */
    @PostMapping
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = CREATE_OPERATION)
    public ResponseEntity<WorkerPayoutDto> create(@RequestBody CreatePayoutRequest request) {
        return ResponseEntity.ok(workerPayoutService.create(request));
    }

    // --- Mark paid (R6.1) ----------------------------------------------------------------------

    /**
     * Marks a {@code PLANNED} payout {@code PAID} and freezes it (R6.1); a {@code PAID} source is
     * rejected with 409. {@code PAYROLL} UPDATE.
     *
     * @param id the payout to mark paid
     */
    @PostMapping("/{id}/pay")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<WorkerPayoutDto> pay(@PathVariable Long id) {
        return ResponseEntity.ok(workerPayoutService.markPaid(id));
    }

    // --- Delete (R6.2–R6.4) --------------------------------------------------------------------

    /**
     * Hard-deletes a {@code PLANNED} payout, releasing its allocations (regular) or after the
     * worker-wide consumed-credit check (advance) (R6.2–R6.4). {@code PAYROLL} DELETE.
     *
     * @param id the {@code PLANNED} payout to delete
     */
    @DeleteMapping("/{id}")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = DELETE_OPERATION)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        workerPayoutService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Allocations ---------------------------------------------------------------------------

    /**
     * The report-line allocations a payout's amount was distributed over (project, work item, room,
     * allocated amount); empty for an advance. 404 for an unknown payout. {@code PAYROLL} READ.
     *
     * @param id the payout whose allocations are read
     */
    @GetMapping("/{id}/allocations")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<List<PayoutAllocationDto>> allocations(@PathVariable Long id) {
        return ResponseEntity.ok(workerPayoutService.allocations(id));
    }
}
