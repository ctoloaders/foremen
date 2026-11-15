package com.foremen.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.RequiresPermission;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.ExternalOrderDocumentServiceModel;
import com.foremen.service.model.ExternalOrderLineServiceModel;
import com.foremen.service.model.ExternalOrderServiceExtendedModel;
import com.foremen.service.model.ExternalOrderServiceModel;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.externalorder.ExternalOrderLifecycleService;
import com.foremen.service.procurement.externalorder.ExternalOrderService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-03 external orders (R11, R12, R14, R15, R21.2, R1.6).
 *
 * <p>ABAC: no class-level {@code @PermissionResource}; every handler has an explicit
 * {@link RequiresPermission} on {@code EXTERNAL_ORDERS}: READ for reads, CREATE for create, UPDATE for
 * header, lines, documents, send and cancel, DELETE for deleting a draft, APPROVE for the financier
 * actions {@code mark-paid}, {@code return} and {@code unmark-paid} (R15.7: a BUYER holds no APPROVE, a
 * FINANCIER holds no CREATE/UPDATE/DELETE). Purchase prices are returned only to money-privileged roles
 * (ADMIN, MANAGER, BUYER) and to the FINANCIER; any other caller gets 403 even if the matrix lets it in.
 * Project confinement is enforced inside the services.
 */
@RestController
@RequestMapping("/api/external-orders")
@RequiredArgsConstructor
public class ExternalOrderController {

    private static final String RESOURCE = "EXTERNAL_ORDERS";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";
    private static final String APPROVE = "APPROVE";

    private final ExternalOrderService service;
    private final ExternalOrderLifecycleService lifecycleService;
    private final ProcurementProjectAccess access;

    // ------------------------------------------------------------------ reads

    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "List external orders (filters through the standard query parameter)")
    public ResponseEntity<Page<ExternalOrderServiceModel>> find(
            Pageable pageable, @RequestParam(name = "query", required = false) String query) {
        requireMoneyAudience();
        return ResponseEntity.ok(service.find(pageable, query));
    }

    @GetMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "One external order with lines and documents")
    public ResponseEntity<ExternalOrderServiceExtendedModel> findById(@PathVariable Long id) {
        requireMoneyAudience();
        return ResponseEntity.ok(service.findById(id));
    }

    // ------------------------------------------------------------------ draft

    @PostMapping
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    @Operation(summary = "Create a draft external order (optionally with initial lines)")
    public ResponseEntity<ExternalOrderServiceExtendedModel> create(@Valid @RequestBody CreateRequest request) {
        requireMoneyAudience();
        ExternalOrderServiceExtendedModel model = new ExternalOrderServiceExtendedModel();
        model.setProjectId(request.projectId());
        model.setSellerId(request.sellerId());
        model.setPurchaseRequestId(request.purchaseRequestId());
        model.setAssignedBuyerId(request.assignedBuyerId());
        copyHeader(model, request.header());
        model.setLines(request.lines());
        return ResponseEntity.ok(service.create(model));
    }

    @PutMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    @Operation(summary = "Update the header of a draft")
    public ResponseEntity<ExternalOrderServiceExtendedModel> update(
            @PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        requireMoneyAudience();
        ExternalOrderServiceExtendedModel model = new ExternalOrderServiceExtendedModel();
        model.setProjectId(request.projectId());
        model.setSellerId(request.sellerId());
        model.setPurchaseRequestId(request.purchaseRequestId());
        model.setAssignedBuyerId(request.assignedBuyerId());
        copyHeader(model, request.header());
        return ResponseEntity.ok(service.update(id, model));
    }

    @PutMapping("/{id}/lines")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    @Operation(summary = "Replace all lines of a draft")
    public ResponseEntity<ExternalOrderServiceExtendedModel> replaceLines(
            @PathVariable Long id, @Valid @RequestBody LinesRequest request) {
        requireMoneyAudience();
        return ResponseEntity.ok(service.replaceLines(id, request.lines()));
    }

    @PutMapping("/{id}/documents")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    @Operation(summary = "Replace the linked supplier documents of a draft or sent order")
    public ResponseEntity<ExternalOrderServiceExtendedModel> replaceDocuments(
            @PathVariable Long id, @Valid @RequestBody DocumentsRequest request) {
        requireMoneyAudience();
        return ResponseEntity.ok(service.replaceDocuments(id, request.documents()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = DELETE)
    @Operation(summary = "Delete a draft external order")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        requireMoneyAudience();
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ buyer actions

    @PostMapping("/{id}/send")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    @Operation(summary = "Send a draft to the financier for payment")
    public ResponseEntity<ExternalOrderServiceExtendedModel> send(@PathVariable Long id) {
        requireMoneyAudience();
        return ResponseEntity.ok(lifecycleService.send(id));
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    @Operation(summary = "Cancel a draft or an unpaid sent order")
    public ResponseEntity<ExternalOrderServiceExtendedModel> cancel(
            @PathVariable Long id, @Valid @RequestBody(required = false) CancelRequest request) {
        requireMoneyAudience();
        return ResponseEntity.ok(lifecycleService.cancel(id));
    }

    // ------------------------------------------------------------------ financier actions

    @PostMapping("/{id}/mark-paid")
    @RequiresPermission(resource = RESOURCE, operation = APPROVE)
    @Operation(summary = "Financier: mark a sent order as paid")
    public ResponseEntity<ExternalOrderServiceExtendedModel> markPaid(
            @PathVariable Long id, @Valid @RequestBody(required = false) MarkPaidRequest request) {
        requireMoneyAudience();
        return ResponseEntity.ok(lifecycleService.markPaid(id,
                request == null ? null : request.paidDate(), request == null ? null : request.note()));
    }

    @PostMapping("/{id}/return")
    @RequiresPermission(resource = RESOURCE, operation = APPROVE)
    @Operation(summary = "Financier: return a sent order to the buyer with a reason")
    public ResponseEntity<ExternalOrderServiceExtendedModel> returnToBuyer(
            @PathVariable Long id, @Valid @RequestBody ReturnRequest request) {
        requireMoneyAudience();
        return ResponseEntity.ok(lifecycleService.returnToBuyer(id, request.reason()));
    }

    @PostMapping("/{id}/unmark-paid")
    @RequiresPermission(resource = RESOURCE, operation = APPROVE)
    @Operation(summary = "Financier: revert a hand-marked paid order to sent")
    public ResponseEntity<ExternalOrderServiceExtendedModel> unmarkPaid(@PathVariable Long id) {
        requireMoneyAudience();
        return ResponseEntity.ok(lifecycleService.unmarkPaid(id));
    }

    // ------------------------------------------------------------------ requests

    /** Optional header fields shared by create and update. */
    public record Header(@Size(max = 100) String referenceNumber, @Size(max = 1000) String orderLink,
                         java.math.BigDecimal deliveryCostNet, Long deliveryVatRateId,
                         LocalDate expectedShipDate, LocalDate expectedDeliveryDate,
                         @Size(max = 1000) String note) {
    }

    public record CreateRequest(@NotNull Long projectId, @NotNull Long sellerId, Long purchaseRequestId,
                                Long assignedBuyerId, @Valid Header header,
                                @Size(max = 200) List<ExternalOrderLineServiceModel> lines) {
    }

    public record UpdateRequest(Long projectId, Long sellerId, Long purchaseRequestId, Long assignedBuyerId,
                                @Valid Header header) {
    }

    public record LinesRequest(@NotNull @Size(max = 200) List<ExternalOrderLineServiceModel> lines) {
    }

    public record DocumentsRequest(@NotNull @Size(max = 50) List<ExternalOrderDocumentServiceModel> documents) {
    }

    public record CancelRequest(@Size(max = 500) String reason) {
    }

    public record MarkPaidRequest(LocalDate paidDate, @Size(max = 500) String note) {
    }

    public record ReturnRequest(@NotBlank @Size(max = 500) String reason) {
    }

    // ------------------------------------------------------------------ helpers

    private static void copyHeader(ExternalOrderServiceExtendedModel model, Header h) {
        if (h == null) {
            return;
        }
        model.setReferenceNumber(h.referenceNumber());
        model.setOrderLink(h.orderLink());
        model.setDeliveryCostNet(h.deliveryCostNet());
        model.setDeliveryVatRateId(h.deliveryVatRateId());
        model.setExpectedShipDate(h.expectedShipDate());
        model.setExpectedDeliveryDate(h.expectedDeliveryDate());
        model.setNote(h.note());
    }

    /**
     * Purchase prices leave the API only to ADMIN, MANAGER, BUYER and FINANCIER. No client role holds a
     * grant, but the check is repeated here so a mis-seeded matrix cannot leak them.
     */
    private void requireMoneyAudience() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!access.isMoneyPrivileged(auth) && !access.isOrderPaymentRole(auth)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }
    }
}
