package com.foremen.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.service.payment.ManualSchedulerService;
import com.foremen.service.payment.PaymentScheduleService;
import com.foremen.service.payment.PaymentTransactionService;
import com.foremen.service.payment.ReconciliationService;
import com.foremen.service.payment.dto.AddTrancheInput;
import com.foremen.service.payment.dto.CreateScheduleRequest;
import com.foremen.service.payment.dto.GenerateRequest;
import com.foremen.service.payment.dto.InitiatePayment;
import com.foremen.service.payment.dto.ManualPayInput;
import com.foremen.service.payment.dto.ManualTxnInput;
import com.foremen.service.payment.dto.PaymentScheduleView;
import com.foremen.service.payment.dto.PaymentTransactionDto;
import com.foremen.service.payment.dto.RePlanRequest;
import com.foremen.service.payment.dto.mapper.PaymentDtoMapper;
import com.foremen.service.payment.provider.OnlineInitiateResult;
import com.foremen.service.payment.provider.OnlinePaymentProvider;

import lombok.RequiredArgsConstructor;

/**
 * FOR-06-07 (design §Controllers / Backend controllers; Requirements 5.9, 7.6, 11.1, 11.3, 11.4,
 * 12.2, 14.3): the ABAC-wired controller for the internal/admin side of client payments — the whole
 * schedule / tranche / transaction / action surface of the Płatności tab, plus the two reusable
 * server actions FOR-09 consumes.
 *
 * <h2>ABAC guarding (R11.1, R11.3, R11.4, R12.2)</h2>
 * The class carries {@link PermissionResource @PermissionResource("PAYMENTS")}; every handler carries
 * a method-level {@link RequiresPermission @RequiresPermission} that takes precedence over the class
 * default (mirroring {@link OfferController}), so the controller is <b>fully annotated</b> and
 * {@code PermissionAnnotationValidator} classifies it COMPLETE at startup (no half-annotated handler
 * fails the boot, per {@code entity-creation-rules} step 3). The custom actions map to the
 * <b>standard CRUD</b> operations — <b>no new ABAC operation</b> is introduced (R11.1):
 *
 * <ul>
 *   <li>{@code create} &rarr; {@code PAYMENTS} CREATE (a new schedule).</li>
 *   <li>{@code generate} / {@code publish} / {@code rePlan} / {@code addTranche} / {@code editTranche}
 *       / {@code removeTranche} / {@code reorder} / {@code addTrancheAnytime} / {@code markTranchePaid}
 *       / {@code recordManual} / {@code initiateOnlinePayment} &rarr; {@code PAYMENTS} UPDATE (they
 *       mutate an existing schedule / its tranches / its transactions).</li>
 *   <li>{@code getSchedule} / {@code getScheduleByProject} / {@code listTransactions} &rarr;
 *       {@code PAYMENTS} READ.</li>
 * </ul>
 *
 * <p>The {@code PAYMENTS} resource scopes by {@code schedule.project.id} via
 * {@link PaymentScheduleService} / {@link ManualSchedulerService} / {@link ReconciliationService}
 * ({@code ProjectScopedService}); a caller reaches only schedules of projects they belong to
 * (R11.3). FOREMAN / CLIENT reach only the READ endpoints; WORKER has no grant (the seeded matrix,
 * task 7.1).
 *
 * <h2>Reusable FOR-09 server actions (R7.6, R14.3)</h2>
 * {@link #getScheduleByProject(Long)} (read-schedule) and {@link #initiateOnlinePayment(InitiatePayment)}
 * (initiate-online-pay) are the two server actions the FOR-09 client portal reuses without a server
 * rewrite. The initiate action delegates to the config-selected {@link OnlinePaymentProvider}; under
 * the No-Op default it returns a {@linkplain OnlineInitiateResult#disabled() disabled} result and
 * confirms nothing (R7.2).
 *
 * <p>Controllers are thin: every lifecycle decision + invariant lives in the services + the pure
 * collaborators; this class only maps HTTP to the service calls and renders the DTOs.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@PermissionResource("PAYMENTS")
public class PaymentScheduleController {

    private static final String PAYMENTS_RESOURCE = "PAYMENTS";
    private static final String CREATE_OPERATION = "CREATE";
    private static final String READ_OPERATION = "READ";
    private static final String UPDATE_OPERATION = "UPDATE";

    private final PaymentScheduleService paymentScheduleService;
    private final ManualSchedulerService manualSchedulerService;
    private final ReconciliationService reconciliationService;
    private final PaymentTransactionService paymentTransactionService;
    private final OnlinePaymentProvider onlinePaymentProvider;
    private final PaymentScheduleDao paymentScheduleDao;
    private final PaymentTransactionDao paymentTransactionDao;
    private final PaymentDtoMapper paymentDtoMapper;

    // --- Schedule lifecycle (R1, R2, R4, R6) ---

    /**
     * Creates a project's payment schedule in {@code DRAFT}, seeding {@code basisGross} from the
     * project's {@code APPROVED} offer total (R1.1–1.3). {@code PAYMENTS} CREATE.
     */
    @PostMapping("/schedules")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = CREATE_OPERATION)
    public ResponseEntity<PaymentScheduleView> create(@RequestBody CreateScheduleRequest request) {
        return ResponseEntity.ok(paymentScheduleService.create(request));
    }

    /**
     * Runs the tranche generator on a {@code DRAFT} schedule, replacing any existing tranches (R2,
     * R2.6). {@code PAYMENTS} UPDATE.
     */
    @PostMapping("/schedules/{scheduleId}/generate")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<PaymentScheduleView> generate(
            @PathVariable Long scheduleId, @RequestBody GenerateRequest request) {
        return ResponseEntity.ok(paymentScheduleService.generate(scheduleId, request));
    }

    /**
     * Publishes a reconciling {@code DRAFT} schedule — {@code DRAFT → PUBLISHED}, requiring
     * {@code Σ amountGross == basisGross} and at least one tranche (R4.1, R4.2). {@code PAYMENTS}
     * UPDATE.
     */
    @PostMapping("/schedules/{scheduleId}/publish")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<PaymentScheduleView> publish(@PathVariable Long scheduleId) {
        return ResponseEntity.ok(paymentScheduleService.publish(scheduleId));
    }

    /**
     * Re-plans the unpaid remainder of a {@code PUBLISHED} schedule after an amendment changes the
     * project total — {@code PAID} tranches + allocations stay immutable (R6). {@code PAYMENTS}
     * UPDATE.
     */
    @PostMapping("/schedules/{scheduleId}/re-plan")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<PaymentScheduleView> rePlan(
            @PathVariable Long scheduleId, @RequestBody RePlanRequest request) {
        return ResponseEntity.ok(paymentScheduleService.rePlan(scheduleId, request));
    }

    // --- Manual scheduler: DRAFT structural edits + add-tranche-anytime (R3) ---

    /**
     * Adds a tranche to a {@code DRAFT} schedule by hand (R3.1, R3.2). {@code PAYMENTS} UPDATE.
     */
    @PostMapping("/schedules/{scheduleId}/tranches")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<PaymentScheduleView> addTranche(
            @PathVariable Long scheduleId, @RequestBody AddTrancheInput request) {
        return ResponseEntity.ok(manualSchedulerService.addTranche(scheduleId, request));
    }

    /**
     * Edits an existing tranche's amount + due date on a {@code DRAFT} schedule (R3.1, R3.2).
     * {@code PAYMENTS} UPDATE.
     */
    @PostMapping("/schedules/{scheduleId}/tranches/{trancheId}")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<PaymentScheduleView> editTranche(
            @PathVariable Long scheduleId, @PathVariable Long trancheId,
            @RequestBody AddTrancheInput request) {
        return ResponseEntity.ok(manualSchedulerService.editTranche(scheduleId, trancheId, request));
    }

    /**
     * Removes a tranche from a {@code DRAFT} schedule (R3.1, R3.2). {@code PAYMENTS} UPDATE.
     */
    @PostMapping("/schedules/{scheduleId}/tranches/{trancheId}/remove")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<PaymentScheduleView> removeTranche(
            @PathVariable Long scheduleId, @PathVariable Long trancheId) {
        return ResponseEntity.ok(manualSchedulerService.removeTranche(scheduleId, trancheId));
    }

    /**
     * Re-orders a {@code DRAFT} schedule's tranches — re-derives {@code seqNo} by {@code dueDate}
     * (R3.1, R3.2). {@code PAYMENTS} UPDATE.
     */
    @PostMapping("/schedules/{scheduleId}/reorder")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<PaymentScheduleView> reorder(@PathVariable Long scheduleId) {
        return ResponseEntity.ok(manualSchedulerService.reorder(scheduleId));
    }

    /**
     * Adds a single tranche by hand at any time — permitted even while {@code PUBLISHED}; raises
     * {@code basisGross} by the added amount on a published schedule and re-runs held credit (R3.3,
     * R3.4). {@code PAYMENTS} UPDATE.
     */
    @PostMapping("/schedules/{scheduleId}/add-tranche")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<PaymentScheduleView> addTrancheAnytime(
            @PathVariable Long scheduleId, @RequestBody AddTrancheInput request) {
        return ResponseEntity.ok(manualSchedulerService.addTrancheAnytime(scheduleId, request));
    }

    // --- Reconciliation: manual mark-tranche-paid (R5.9–5.12) ---

    /**
     * Marks a single tranche {@code PAID} manually, independent of the nearest-open cascade — settles
     * exactly that tranche's remaining amount through a recorded {@code MANUAL}/{@code CONFIRMED}
     * transaction; idempotent on an already-{@code PAID} tranche (R5.9–5.12). {@code PAYMENTS} UPDATE.
     */
    @PostMapping("/tranches/{trancheId}/mark-paid")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<PaymentScheduleView> markTranchePaid(
            @PathVariable Long trancheId, @RequestBody(required = false) ManualPayInput request) {
        return ResponseEntity.ok(reconciliationService.markTranchePaid(trancheId, request));
    }

    // --- Transactions: manual record + per-schedule read (R8.5, R5.7, R8.4) ---

    /**
     * Records a {@code MANUAL} incoming payment against a schedule — reconciles identically through
     * the nearest-open cascade (R8.5). {@code PAYMENTS} UPDATE.
     */
    @PostMapping("/schedules/{scheduleId}/transactions")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<PaymentTransactionDto> recordManual(
            @PathVariable Long scheduleId, @RequestBody ManualTxnInput request) {
        return ResponseEntity.ok(paymentTransactionService.recordManual(scheduleId, request));
    }

    /**
     * Lists every transaction recorded against a schedule (oldest first) with its per-tranche
     * allocations — the Płatności transactions panel (R5.7, R8.4, R12.2). {@code PAYMENTS} READ.
     *
     * <p>Project scoping is enforced by resolving + access-gating the schedule through
     * {@link PaymentScheduleService#get(Long)} first (404 for a missing / out-of-scope schedule,
     * R11.3); the transaction list is then read for that in-scope schedule.
     */
    @GetMapping("/schedules/{scheduleId}/transactions")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<List<PaymentTransactionDto>> listTransactions(@PathVariable Long scheduleId) {
        PaymentScheduleView schedule = paymentScheduleService.get(scheduleId); // access-gate (R11.3).
        List<PaymentTransactionDto> transactions = paymentDtoMapper.toTransactionDtos(
                paymentTransactionDao.findByScheduleIdOrderByIdAsc(schedule.id()));
        return ResponseEntity.ok(transactions);
    }

    // --- Reads (R1.4) + reusable FOR-09 server actions (R7.6, R14.3) ---

    /**
     * The aggregate schedule view by schedule id — header, ordered tranches, and derived aggregates
     * (R1.4); statuses re-derived on read so overdue reflects the current clock (R10.2).
     * {@code PAYMENTS} READ.
     */
    @GetMapping("/schedules/{scheduleId}")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<PaymentScheduleView> getSchedule(@PathVariable Long scheduleId) {
        return ResponseEntity.ok(paymentScheduleService.get(scheduleId));
    }

    /**
     * The project's payment schedule by project id — the by-project read the Płatności tab resolves
     * on a fresh load and the <b>reusable read-schedule server action FOR-09 consumes</b> (R7.6,
     * R14.3). {@code PAYMENTS} READ.
     *
     * <p>Resolves the (at-most-one) schedule for the project via
     * {@link PaymentScheduleDao#findByProjectId(Long)}, then serves it through
     * {@link PaymentScheduleService#get(Long)} so the same access gate (404 for an out-of-scope
     * schedule, R11.3) and on-read status re-derivation (R10.2) apply as the by-id read. Returns
     * {@code 204 No Content} when the project has no schedule yet, letting the tab distinguish "no
     * schedule" from an error on a fresh load (mirroring {@code OfferController}'s by-project read).
     */
    @GetMapping("/projects/{projectId}/schedule")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<PaymentScheduleView> getScheduleByProject(@PathVariable Long projectId) {
        PaymentScheduleEntity schedule = paymentScheduleDao.findByProjectId(projectId).orElse(null);
        if (schedule == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(paymentScheduleService.get(schedule.getId()));
    }

    /**
     * Initiates an online (przelewy24) payment intent — the <b>reusable initiate-online-pay server
     * action FOR-09 triggers</b> from the client portal (R7.6, R14.3). Delegates to the
     * config-selected {@link OnlinePaymentProvider}; under the No-Op default it returns a
     * {@linkplain OnlineInitiateResult#disabled() disabled} result and confirms nothing (R7.2).
     * {@code PAYMENTS} UPDATE (it is a schedule-mutating payment action).
     */
    @PostMapping("/online/initiate")
    @RequiresPermission(resource = PAYMENTS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<OnlineInitiateResult> initiateOnlinePayment(
            @RequestBody InitiatePayment request) {
        return ResponseEntity.ok(onlinePaymentProvider.initiate(request));
    }
}
