package com.foremen.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.ClientPaymentView;
import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.service.clientpayment.ClientPaymentFilter;
import com.foremen.service.clientpayment.ClientPaymentKind;
import com.foremen.service.clientpayment.ClientPaymentService;
import com.foremen.service.clientpayment.ClientPaymentService.StoredPdf;
import com.foremen.service.clientpayment.ClientPaymentService.TrancheRef;
import com.foremen.service.clientpayment.ClientPaymentSummary;
import com.foremen.service.clientpayment.DisplayStatus;
import com.foremen.service.model.ClientProformaServiceExtendedModel;
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
import com.foremen.service.procurement.proforma.ClientProformaService;
import com.foremen.service.procurement.proforma.ProformaCancelService;
import com.foremen.service.procurement.proforma.ProformaIssueService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-06 (R1.3 - R1.10, R3.4; design 3): the unified client payment API. Reads (list, summary, by id, PDF)
 * go through {@link ClientPaymentService}; the writes delegate to the existing schedule, tranche, transaction
 * and proforma services, addressed by the payment's base id.
 *
 * <p>ABAC: class-level {@link PermissionResource @PermissionResource("CLIENT_PAYMENTS")}, every handler a
 * {@link PermissionOperation}. The per-kind rule (R1.7) is a service-level check on top of the matrix:
 * FOREMAN WORKS only, BUYER MATERIALS only, FINANCIER writes WORKS only, CLIENT reads a client-safe view and
 * gets 403 on every staff action; a project outside the caller's scope answers 404.
 */
@RestController
@RequestMapping("/api/client-payments")
@RequiredArgsConstructor
@PermissionResource("CLIENT_PAYMENTS")
public class ClientPaymentController {

    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    private final ClientPaymentService service;
    private final ClientProformaService proformaService;
    private final ProformaIssueService issueService;
    private final ProformaCancelService cancelService;
    private final PaymentScheduleService paymentScheduleService;
    private final ManualSchedulerService manualSchedulerService;
    private final ReconciliationService reconciliationService;
    private final PaymentTransactionService paymentTransactionService;
    private final OnlinePaymentProvider onlinePaymentProvider;
    private final PaymentScheduleDao paymentScheduleDao;
    private final PaymentTransactionDao paymentTransactionDao;
    private final PaymentDtoMapper paymentDtoMapper;

    // ------------------------------------------------------------------ reads

    @GetMapping
    @PermissionOperation(READ)
    @Operation(summary = "List client payments (works tranches and materials proformas)")
    public ResponseEntity<Page<ClientPaymentView>> find(
            Pageable pageable,
            @RequestParam(name = "projectId", required = false) Long projectId,
            @RequestParam(name = "kind", required = false) ClientPaymentKind kind,
            @RequestParam(name = "status", required = false) DisplayStatus status,
            @RequestParam(name = "overdue", required = false) Boolean overdue,
            @RequestParam(name = "dueFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dueFrom,
            @RequestParam(name = "dueTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dueTo,
            @RequestParam(name = "query", required = false) String query) {
        return ResponseEntity.ok(service.find(pageable,
                new ClientPaymentFilter(projectId, kind, status, overdue, dueFrom, dueTo, query)));
    }

    @GetMapping("/summary")
    @PermissionOperation(READ)
    @Operation(summary = "Totals of the filtered client payments")
    public ResponseEntity<ClientPaymentSummary> summary(
            @RequestParam(name = "projectId", required = false) Long projectId,
            @RequestParam(name = "kind", required = false) ClientPaymentKind kind,
            @RequestParam(name = "status", required = false) DisplayStatus status,
            @RequestParam(name = "overdue", required = false) Boolean overdue,
            @RequestParam(name = "dueFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dueFrom,
            @RequestParam(name = "dueTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dueTo,
            @RequestParam(name = "query", required = false) String query) {
        return ResponseEntity.ok(service.summary(
                new ClientPaymentFilter(projectId, kind, status, overdue, dueFrom, dueTo, query)));
    }

    @GetMapping("/{id}")
    @PermissionOperation(READ)
    @Operation(summary = "One client payment (staff or client-safe view)")
    public ResponseEntity<ClientPaymentView> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    @GetMapping("/{id}/pdf")
    @PermissionOperation(READ)
    @Operation(summary = "Stored PDF of an issued materials proforma")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        StoredPdf pdf = service.pdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.fileName() + "\"")
                .body(pdf.bytes());
    }

    // ------------------------------------------------------------------ materials (proforma) writes

    @PostMapping("/materials")
    @PermissionOperation(CREATE)
    @Operation(summary = "Create a draft materials proforma from accepted positions")
    public ResponseEntity<ClientProformaServiceExtendedModel> createMaterials(
            @Valid @RequestBody CreateMaterialsRequest request) {
        service.requireMaterialsWrite();
        ClientProformaServiceExtendedModel model = new ClientProformaServiceExtendedModel();
        model.setProjectId(request.projectId());
        model.setPositionIds(request.positionIds());
        model.setDueDate(request.dueDate());
        model.setNote(request.note());
        return ResponseEntity.ok(service.withPaymentId(proformaService.create(model)));
    }

    /** MATERIALS: edits a draft proforma; WORKS: edits the tranche amount and due date of a DRAFT schedule. */
    @PutMapping("/{id}")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Update a draft materials proforma or a works tranche")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        if (service.kindOf(id) == ClientPaymentKind.WORKS) {
            TrancheRef ref = service.worksTrancheForWrite(id);
            return ResponseEntity.ok(manualSchedulerService.editTranche(ref.scheduleId(), ref.trancheId(),
                    new AddTrancheInput(request.amountGross(), request.dueDate())));
        }
        Long proformaId = service.materialsProformaIdForWrite(id);
        ClientProformaServiceExtendedModel model = new ClientProformaServiceExtendedModel();
        model.setPositionIds(request.positionIds());
        model.setDueDate(request.dueDate());
        model.setNote(request.note());
        return ResponseEntity.ok(service.withPaymentId(proformaService.update(proformaId, model)));
    }

    /** MATERIALS: deletes a draft proforma; WORKS: removes the tranche from a DRAFT schedule. */
    @DeleteMapping("/{id}")
    @PermissionOperation(DELETE)
    @Operation(summary = "Delete a draft materials proforma or a works tranche")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (service.kindOf(id) == ClientPaymentKind.WORKS) {
            TrancheRef ref = service.worksTrancheForWrite(id);
            manualSchedulerService.removeTranche(ref.scheduleId(), ref.trancheId());
        } else {
            proformaService.deleteById(service.materialsProformaIdForWrite(id));
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/issue")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Issue a draft proforma: number, frozen prices, PDF, positions to IN_PROFORMA")
    public ResponseEntity<ClientProformaServiceExtendedModel> issue(
            @PathVariable Long id, @Valid @RequestBody(required = false) IssueRequest request) {
        Long proformaId = service.materialsProformaIdForWrite(id);
        return ResponseEntity.ok(service.withPaymentId(
                issueService.issue(proformaId, request == null ? null : request.language())));
    }

    @PostMapping("/{id}/cancel")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Cancel a draft or an unpaid issued proforma")
    public ResponseEntity<ClientProformaServiceExtendedModel> cancel(
            @PathVariable Long id, @Valid @RequestBody(required = false) CancelRequest request) {
        Long proformaId = service.materialsProformaIdForWrite(id);
        return ResponseEntity.ok(service.withPaymentId(
                cancelService.cancel(proformaId, request == null ? null : request.reason())));
    }

    // ------------------------------------------------------------------ works: schedule lifecycle

    @PostMapping("/schedules")
    @PermissionOperation(CREATE)
    @Operation(summary = "Create a project's payment schedule in DRAFT")
    public ResponseEntity<PaymentScheduleView> createSchedule(@RequestBody CreateScheduleRequest request) {
        service.requireWorks();
        return ResponseEntity.ok(paymentScheduleService.create(request));
    }

    @GetMapping("/projects/{projectId}/schedule")
    @PermissionOperation(READ)
    @Operation(summary = "The project's payment schedule (204 when none)")
    public ResponseEntity<PaymentScheduleView> getScheduleByProject(@PathVariable Long projectId) {
        service.requireWorks();
        PaymentScheduleEntity schedule = paymentScheduleDao.findByProjectId(projectId).orElse(null);
        if (schedule == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(paymentScheduleService.get(schedule.getId()));
    }

    @GetMapping("/schedules/{scheduleId}")
    @PermissionOperation(READ)
    @Operation(summary = "Aggregate schedule view")
    public ResponseEntity<PaymentScheduleView> getSchedule(@PathVariable Long scheduleId) {
        service.requireWorks();
        return ResponseEntity.ok(paymentScheduleService.get(scheduleId));
    }

    @PostMapping("/schedules/{scheduleId}/generate")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Run the tranche generator on a DRAFT schedule")
    public ResponseEntity<PaymentScheduleView> generate(
            @PathVariable Long scheduleId, @RequestBody GenerateRequest request) {
        service.requireWorks();
        return ResponseEntity.ok(paymentScheduleService.generate(scheduleId, request));
    }

    @PostMapping("/schedules/{scheduleId}/publish")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Publish a reconciling DRAFT schedule")
    public ResponseEntity<PaymentScheduleView> publish(@PathVariable Long scheduleId) {
        service.requireWorks();
        return ResponseEntity.ok(paymentScheduleService.publish(scheduleId));
    }

    @PostMapping("/schedules/{scheduleId}/re-plan")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Re-plan the unpaid remainder of a PUBLISHED schedule")
    public ResponseEntity<PaymentScheduleView> rePlan(
            @PathVariable Long scheduleId, @RequestBody RePlanRequest request) {
        service.requireWorks();
        return ResponseEntity.ok(paymentScheduleService.rePlan(scheduleId, request));
    }

    // ------------------------------------------------------------------ works: tranches

    @PostMapping("/schedules/{scheduleId}/tranches")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Add a tranche to a DRAFT schedule")
    public ResponseEntity<PaymentScheduleView> addTranche(
            @PathVariable Long scheduleId, @RequestBody AddTrancheInput request) {
        service.requireWorks();
        return ResponseEntity.ok(manualSchedulerService.addTranche(scheduleId, request));
    }

    @PostMapping("/schedules/{scheduleId}/tranches/{trancheId}")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Edit a tranche of a DRAFT schedule")
    public ResponseEntity<PaymentScheduleView> editTranche(
            @PathVariable Long scheduleId, @PathVariable Long trancheId, @RequestBody AddTrancheInput request) {
        service.requireWorks();
        return ResponseEntity.ok(manualSchedulerService.editTranche(scheduleId, trancheId, request));
    }

    @PostMapping("/schedules/{scheduleId}/tranches/{trancheId}/remove")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Remove a tranche from a DRAFT schedule")
    public ResponseEntity<PaymentScheduleView> removeTranche(
            @PathVariable Long scheduleId, @PathVariable Long trancheId) {
        service.requireWorks();
        return ResponseEntity.ok(manualSchedulerService.removeTranche(scheduleId, trancheId));
    }

    @PostMapping("/schedules/{scheduleId}/reorder")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Re-derive the tranche order by due date")
    public ResponseEntity<PaymentScheduleView> reorder(@PathVariable Long scheduleId) {
        service.requireWorks();
        return ResponseEntity.ok(manualSchedulerService.reorder(scheduleId));
    }

    @PostMapping("/schedules/{scheduleId}/add-tranche")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Add a tranche at any time, also on a PUBLISHED schedule")
    public ResponseEntity<PaymentScheduleView> addTrancheAnytime(
            @PathVariable Long scheduleId, @RequestBody AddTrancheInput request) {
        service.requireWorks();
        return ResponseEntity.ok(manualSchedulerService.addTrancheAnytime(scheduleId, request));
    }

    /** {@code id} is the base id of the works payment (not the tranche id). */
    @PostMapping("/{id}/mark-paid")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Mark a works tranche paid manually")
    public ResponseEntity<PaymentScheduleView> markPaid(
            @PathVariable Long id, @RequestBody(required = false) ManualPayInput request) {
        TrancheRef ref = service.worksTrancheForWrite(id);
        return ResponseEntity.ok(reconciliationService.markTranchePaid(ref.trancheId(), request));
    }

    // ------------------------------------------------------------------ works: transactions, online

    @PostMapping("/schedules/{scheduleId}/transactions")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Record a manual incoming payment against a schedule")
    public ResponseEntity<PaymentTransactionDto> recordManual(
            @PathVariable Long scheduleId, @RequestBody ManualTxnInput request) {
        service.requireWorks();
        return ResponseEntity.ok(paymentTransactionService.recordManual(scheduleId, request));
    }

    @GetMapping("/schedules/{scheduleId}/transactions")
    @PermissionOperation(READ)
    @Operation(summary = "Transactions of a schedule with their allocations")
    public ResponseEntity<List<PaymentTransactionDto>> listTransactions(@PathVariable Long scheduleId) {
        service.requireWorks();
        PaymentScheduleView schedule = paymentScheduleService.get(scheduleId); // access gate (404 out of scope)
        return ResponseEntity.ok(paymentDtoMapper.toTransactionDtos(
                paymentTransactionDao.findByScheduleIdOrderByIdAsc(schedule.id())));
    }

    @PostMapping("/online/initiate")
    @PermissionOperation(UPDATE)
    @Operation(summary = "Initiate an online payment intent")
    public ResponseEntity<OnlineInitiateResult> initiateOnlinePayment(@RequestBody InitiatePayment request) {
        service.requireWorks();
        return ResponseEntity.ok(onlinePaymentProvider.initiate(request));
    }

    // ------------------------------------------------------------------ requests

    public record CreateMaterialsRequest(@NotNull Long projectId, @NotEmpty @Size(max = 200) List<Long> positionIds,
                                         LocalDate dueDate, @Size(max = 1000) String note) {
    }

    /** Materials: {@code positionIds}, {@code dueDate}, {@code note}; works: {@code amountGross}, {@code dueDate}. */
    public record UpdateRequest(@Size(max = 200) List<Long> positionIds, LocalDate dueDate,
                                @Size(max = 1000) String note, BigDecimal amountGross) {
    }

    public record IssueRequest(@Size(max = 2) String language) {
    }

    public record CancelRequest(@Size(max = 500) String reason) {
    }
}
