package com.foremen.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.exception.ForemenApiException;
import com.foremen.exception.dto.ErrorResponse;
import com.foremen.service.payroll.PayrollLineService;
import com.foremen.service.payroll.PayrollProfileService;
import com.foremen.service.payroll.PayrollReadModelService;
import com.foremen.service.payroll.dto.CompanyPayrollDto;
import com.foremen.service.payroll.dto.OpeningBalanceRequest;
import com.foremen.service.payroll.dto.PayrollLineDto;
import com.foremen.service.payroll.dto.PayrollLineRequest;
import com.foremen.service.payroll.dto.PayrollProfileDto;
import com.foremen.service.payroll.dto.WorkerPayrollCardDto;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * FOR-07-06 (design §Controllers and endpoints; Requirements 8.1, 9.2, 10.1, 11.1, 14.1): the
 * ABAC-wired controller for the <b>company payroll</b> — the workers × projects table, the per-worker
 * card, previous-month hints, additional payroll lines and the worker's opening balance.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/payroll/company?from&to&workerIds} &rarr; {@link CompanyPayrollDto}
 *       ({@code PAYROLL} READ, R10). {@code workerIds} accepts a repeated param or a comma list.</li>
 *   <li>{@code GET /api/payroll/workers/{workerId}/card?from&to} &rarr; {@link WorkerPayrollCardDto}
 *       ({@code PAYROLL} READ, R11).</li>
 *   <li>{@code GET /api/payroll/workers/{workerId}/hints?month} &rarr; {@code List<PayrollLineDto>}
 *       ({@code PAYROLL} READ, R8.5).</li>
 *   <li>{@code POST /api/payroll/lines} &rarr; {@link PayrollLineDto} ({@code PAYROLL} CREATE, R8.1).</li>
 *   <li>{@code PUT /api/payroll/lines/{id}} &rarr; {@link PayrollLineDto} ({@code PAYROLL} UPDATE).</li>
 *   <li>{@code DELETE /api/payroll/lines/{id}} &rarr; 204 ({@code PAYROLL} DELETE).</li>
 *   <li>{@code PUT /api/payroll/workers/{workerId}/opening-balance} &rarr; {@link PayrollProfileDto}
 *       ({@code PAYROLL} UPDATE, R9.2).</li>
 * </ul>
 *
 * <h2>ABAC guarding (R14.1)</h2>
 * {@link PermissionResource @PermissionResource("PAYROLL")} on the class and a method-level
 * {@link RequiresPermission @RequiresPermission} on every handler, so
 * {@code PermissionAnnotationValidator} classifies the controller COMPLETE at startup. The services
 * are company-wide (not project-scoped).
 *
 * <h2>Validation</h2>
 * {@code from > to} is rejected with 422 {@code error.payroll.period.invalid} (R10.1, R11.1). A
 * non-numeric {@code amount} / {@code openingBalance} fails JSON binding before the service; the
 * controller-local {@link #handleUnreadableBody} maps it to 422 {@code error.payrollLine.amount.invalid}
 * / {@code error.payrollProfile.openingBalance.invalid} (R8.3, R9.2), rendered through the global
 * advice so the error body shape is unchanged. Other controllers are unaffected.
 */
@RestController
@RequestMapping("/api/payroll")
@RequiredArgsConstructor
@PermissionResource("PAYROLL")
public class PayrollController {

    private static final String PAYROLL_RESOURCE = "PAYROLL";
    private static final String CREATE_OPERATION = "CREATE";
    private static final String READ_OPERATION = "READ";
    private static final String UPDATE_OPERATION = "UPDATE";
    private static final String DELETE_OPERATION = "DELETE";

    static final String PERIOD_INVALID = "error.payroll.period.invalid";
    static final String AMOUNT_INVALID = "error.payrollLine.amount.invalid";
    static final String OPENING_BALANCE_INVALID = "error.payrollProfile.openingBalance.invalid";

    private final PayrollReadModelService readModelService;
    private final PayrollLineService lineService;
    private final PayrollProfileService profileService;
    private final ForemenControllerAdvice controllerAdvice;

    // --- Company table (R10) -------------------------------------------------------------------

    /**
     * Workers × projects table for {@code [from, to]}. {@code PAYROLL} READ.
     *
     * @param from      period start (inclusive, ISO date)
     * @param to        period end (inclusive, ISO date), {@code >= from}
     * @param workerIds optional worker selection (repeated param or comma list); empty → every worker
     *                  with activity in the period
     */
    @GetMapping("/company")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<CompanyPayrollDto> company(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> workerIds) {
        assertPeriod(from, to);
        return ResponseEntity.ok(readModelService.companyTable(from, to, workerIds == null ? List.of() : workerIds));
    }

    // --- Worker card (R11) ---------------------------------------------------------------------

    /**
     * The worker's month-by-month payroll card for {@code [from, to]}. {@code PAYROLL} READ.
     */
    @GetMapping("/workers/{workerId}/card")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<WorkerPayrollCardDto> card(
            @PathVariable Long workerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        assertPeriod(from, to);
        return ResponseEntity.ok(readModelService.workerCard(workerId, from, to));
    }

    // --- Hints (R8.5) --------------------------------------------------------------------------

    /**
     * Previous-month line hints for the month of {@code month} (current month when omitted).
     * Visual helper only — nothing is created (R8.6). {@code PAYROLL} READ.
     */
    @GetMapping("/workers/{workerId}/hints")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<List<PayrollLineDto>> hints(
            @PathVariable Long workerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month) {
        return ResponseEntity.ok(lineService.hints(workerId, month));
    }

    // --- Additional lines (R8) -----------------------------------------------------------------

    /** Creates an additional payroll line (month normalized to its first day). {@code PAYROLL} CREATE. */
    @PostMapping("/lines")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = CREATE_OPERATION)
    public ResponseEntity<PayrollLineDto> createLine(@RequestBody PayrollLineRequest request) {
        return ResponseEntity.ok(lineService.create(request));
    }

    /** Updates an additional payroll line. {@code PAYROLL} UPDATE. */
    @PutMapping("/lines/{id}")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<PayrollLineDto> updateLine(@PathVariable Long id, @RequestBody PayrollLineRequest request) {
        return ResponseEntity.ok(lineService.update(id, request));
    }

    /** Deletes an additional payroll line. {@code PAYROLL} DELETE. */
    @DeleteMapping("/lines/{id}")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = DELETE_OPERATION)
    public ResponseEntity<Void> deleteLine(@PathVariable Long id) {
        lineService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Opening balance (R9.2) ----------------------------------------------------------------

    /** Sets (upserts) the worker's opening balance; any number, may be negative. {@code PAYROLL} UPDATE. */
    @PutMapping("/workers/{workerId}/opening-balance")
    @RequiresPermission(resource = PAYROLL_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<PayrollProfileDto> setOpeningBalance(
            @PathVariable Long workerId, @RequestBody OpeningBalanceRequest request) {
        return ResponseEntity.ok(profileService.setOpeningBalance(workerId,
                request == null ? null : request.openingBalance()));
    }

    // --- Error mapping -------------------------------------------------------------------------

    /**
     * Controller-local mapping of an unreadable JSON body: a non-numeric {@code amount} /
     * {@code openingBalance} becomes the field's 422 validation code, rendered by the global advice.
     * Any other unreadable body is rethrown and falls through to Spring's default resolution.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request, Locale locale) {
        String code = invalidFieldCode(ex);
        if (code == null) {
            throw ex;
        }
        return controllerAdvice.handleForemenApiException(
                new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, code), request, locale);
    }

    /**
     * Resolves the validation code for the failing field from the Jackson message's reference
     * chain ({@code ...["amount"]}); version-agnostic (Jackson 2 and 3 render the same chain).
     */
    static String invalidFieldCode(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            if (message.contains("[\"openingBalance\"]")) {
                return OPENING_BALANCE_INVALID;
            }
            if (message.contains("[\"amount\"]")) {
                return AMOUNT_INVALID;
            }
        }
        return null;
    }

    private static void assertPeriod(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, PERIOD_INVALID);
        }
    }
}
