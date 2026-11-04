package com.foremen.controller;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import com.foremen.controller.model.ExpenseOptionsDto;
import com.foremen.controller.model.ProcurementDocumentStatusResponse;
import com.foremen.controller.model.ProcurementDocumentUploadResponse;
import com.foremen.controller.model.mapper.ProcurementDocumentControllerMapper;
import com.foremen.dao.model.ProcurementPaymentChoice;
import com.foremen.service.ExpenseUploadService;
import com.foremen.service.ProcurementDocumentService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-07-03 (Requirements 7.1-7.6, 10.3) - the universal "add expense" API under {@code /api/expenses}
 * for every internal role holding the {@code EXPENSE_UPLOAD} resource.
 *
 * <p><b>ABAC (entity-creation-rules step 3).</b> Class-level
 * {@link PermissionResource @PermissionResource("EXPENSE_UPLOAD")} plus a method-level
 * {@link RequiresPermission @RequiresPermission} on every handler (no inherited CRUD), so the startup
 * {@code PermissionAnnotationValidator} classifies each handler {@code COMPLETE}. The controller is
 * not project-scoped: project membership is checked explicitly in {@link ExpenseUploadService}.
 * <ul>
 *   <li>{@code POST /} &rarr; {@code CREATE} - multipart upload.</li>
 *   <li>{@code GET /options} &rarr; {@code READ} - dialog data.</li>
 *   <li>{@code GET /{id}/status} &rarr; {@code READ} - status poll (service enforces
 *       uploader or {@code COMPANY_PURCHASES/READ}).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
@PermissionResource("EXPENSE_UPLOAD")
public class ExpenseController {

    private static final String RESOURCE = "EXPENSE_UPLOAD";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";

    private final ExpenseUploadService service;
    private final ProcurementDocumentControllerMapper controllerMapper;

    /**
     * Creates an expense from an uploaded receipt / invoice.
     *
     * @param file              the image or PDF (required)
     * @param projectId         optional project (membership enforced for non-ADMIN)
     * @param expenseCategoryId optional active expense category
     * @param paymentChoice     {@code NOT_LINKED} (default) or {@code PAID_CASH}
     * @param cashAccountId     cash account (honoured only for callers holding {@code BANK_LEDGER/READ})
     * @param cashAmount        required, {@code > 0}, with {@code PAID_CASH}
     * @param cashDate          payment date (default: upload date)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<ProcurementDocumentUploadResponse> create(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "expenseCategoryId", required = false) Long expenseCategoryId,
            @RequestParam(value = "paymentChoice", required = false) ProcurementPaymentChoice paymentChoice,
            @RequestParam(value = "cashAccountId", required = false) Long cashAccountId,
            @RequestParam(value = "cashAmount", required = false) BigDecimal cashAmount,
            @RequestParam(value = "cashDate", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cashDate) {
        var created = service.create(file, projectId, expenseCategoryId,
                new ProcurementDocumentService.UploadPaymentRequest(
                        paymentChoice, cashAccountId, cashAmount, cashDate));
        return ResponseEntity.ok(controllerMapper.toUploadResponse(created));
    }

    /** Projects, categories and (when allowed) cash accounts for the upload dialog. */
    @GetMapping("/options")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<ExpenseOptionsDto> options() {
        return ResponseEntity.ok(service.options());
    }

    /** Lightweight processing status of an expense (uploader or {@code COMPANY_PURCHASES/READ}). */
    @GetMapping("/{id}/status")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<ProcurementDocumentStatusResponse> status(@PathVariable Long id) {
        ExpenseUploadService.StatusView view = service.status(id);
        return ResponseEntity.ok(ProcurementDocumentStatusResponse.of(
                view.id(), view.status(), view.failureReason()));
    }
}
