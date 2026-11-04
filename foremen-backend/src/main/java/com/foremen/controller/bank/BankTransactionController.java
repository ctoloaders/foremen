package com.foremen.controller.bank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LinkKind;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.cash.CashEntryOutcome;
import com.foremen.service.bank.cash.CashEntryRequest;
import com.foremen.service.bank.cash.CashLedgerService;
import com.foremen.service.bank.detect.DetectionService.DetectionSummary;
import com.foremen.service.bank.ledger.BankLedgerBulkService;
import com.foremen.service.bank.ledger.BankLedgerBulkService.BulkConfirmResult;
import com.foremen.service.bank.ledger.BankLedgerQueryService;
import com.foremen.service.bank.ledger.LedgerDtos.LedgerDetail;
import com.foremen.service.bank.ledger.LedgerDtos.LedgerPage;
import com.foremen.service.bank.ledger.LedgerDtos.LineCandidate;
import com.foremen.service.bank.ledger.LedgerDtos.LinkTarget;
import com.foremen.service.bank.ledger.LedgerFilter;
import com.foremen.service.bank.ledger.LedgerFilter.DetectionFilter;
import com.foremen.service.bank.ledger.LedgerFilter.LedgerDirection;
import com.foremen.service.bank.ledger.LedgerFilter.LedgerStatusFilter;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.bank.link.LinkOutcome;
import com.foremen.service.bank.link.LinkRequest;
import com.foremen.service.permission.ForemenPermissionEvaluator;

/**
 * FOR-07-04 task 7.2 (Requirements 13, 16.2, 17.1, 17.2, 17.5, 17.6, 20.2–20.7, 21.2, 21.4, 21.6, 27.3,
 * 27.6, 28.1; design §REST API) — the company money ledger: bank + cash lines, links, suggestions, cash
 * entries.
 *
 * <h2>Endpoints (all under {@code /api/bank})</h2>
 * <ul>
 *   <li>{@code GET /transactions?…filters&page&size&sort} → {@link LedgerPage} (READ)</li>
 *   <li>{@code GET /transactions/{id}} → {@link LedgerDetail}, provider payload ADMIN only (READ)</li>
 *   <li>{@code GET /transactions/{id}/link-targets?kind&q&projectId&paymentStatus} (READ)</li>
 *   <li>{@code GET /transactions/link-candidates?procurementDocumentId} (READ)</li>
 *   <li>{@code POST /transactions/cash} manual cash entry (+ optional link) (CREATE)</li>
 *   <li>{@code PUT /transactions/{id}} / {@code DELETE /transactions/{id}} MANUAL cash entry (UPDATE / DELETE)</li>
 *   <li>{@code POST /transactions/{id}/links} (CREATE), {@code PUT /links/{linkId}} (UPDATE),
 *       {@code DELETE /links/{linkId}} (DELETE)</li>
 *   <li>{@code POST /suggestions/{id}/confirm|reject} (UPDATE)</li>
 *   <li>{@code POST /transactions/{id}/confirm-auto|ignore|unignore} (UPDATE)</li>
 *   <li>{@code POST /transactions/{id}/payout} (CREATE + {@code PAYROLL}/CREATE)</li>
 *   <li>{@code POST /transactions/detect}, {@code POST /transactions/bulk/confirm} (UPDATE)</li>
 * </ul>
 *
 * <h2>ABAC (R21.2, R21.3)</h2>
 * {@link PermissionResource @PermissionResource("BANK_LEDGER")} on the class and a
 * {@link RequiresPermission @RequiresPermission} on every handler, so {@code PermissionAnnotationValidator}
 * classifies the controller COMPLETE. The resource is company-wide (not project-scoped). "Create payout"
 * additionally requires {@code PAYROLL}/CREATE (R20.9, R21.4), checked programmatically through
 * {@link ForemenPermissionEvaluator} (ADMIN bypass included) — 403 {@code error.access.denied} otherwise.
 */
@RestController
@RequestMapping("/api/bank")
@PermissionResource(BankTransactionController.RESOURCE)
public class BankTransactionController {

    static final String RESOURCE = "BANK_LEDGER";
    static final String PAYROLL_RESOURCE = "PAYROLL";
    static final String CREATE = "CREATE";
    static final String READ = "READ";
    static final String UPDATE = "UPDATE";
    static final String DELETE = "DELETE";
    static final String ACCESS_DENIED = "error.access.denied";

    private final BankLedgerQueryService queryService;
    private final BankLedgerBulkService bulkService;
    private final BankLinkService linkService;
    private final CashLedgerService cashLedgerService;
    private final ForemenPermissionEvaluator permissionEvaluator;

    public BankTransactionController(BankLedgerQueryService queryService, BankLedgerBulkService bulkService,
                                     BankLinkService linkService, CashLedgerService cashLedgerService,
                                     ForemenPermissionEvaluator permissionEvaluator) {
        this.queryService = queryService;
        this.bulkService = bulkService;
        this.linkService = linkService;
        this.cashLedgerService = cashLedgerService;
        this.permissionEvaluator = permissionEvaluator;
    }

    // ============================================================================================
    // Request bodies
    // ============================================================================================

    /** {@code {kind, amount, targets…, note, remember}} — {@code remember} defaults to {@code true} (R17.3). */
    public record LinkBody(LinkKind kind, BigDecimal amount, Long workerPayoutId, Long procurementDocumentId,
                           Long paymentScheduleId, Long projectId, Long unplannedCategoryId, Long counterAccountId,
                           String note, Boolean remember) {
        LinkRequest toRequest() {
            return new LinkRequest(kind, amount, workerPayoutId, procurementDocumentId, paymentScheduleId, projectId,
                    unplannedCategoryId, counterAccountId, note);
        }
    }

    /** {@code {remember}} (defaults to {@code true}). */
    public record RememberBody(Boolean remember) {
    }

    /** {@code {reason}} (required by the service, R17.5). */
    public record IgnoreBody(String reason) {
    }

    /** "Create payout from ledger line" (R16.2): {@code amount null} → the line's unallocated remainder. */
    public record PayoutBody(Long workerId, BigDecimal amount, String note, Boolean remember) {
    }

    /** "Re-run detection" (R17.6): explicit {@code ids}, or a ledger {@code filter} when no ids are given. */
    public record DetectBody(List<Long> ids, LedgerFilter filter, Boolean includeAi) {
    }

    /** Bulk "confirm all auto-links / top suggestions" (R20.7). */
    public record BulkConfirmBody(List<Long> ids, Boolean remember) {
    }

    /** {@code DELETE /transactions/{id}} result: {@code payoutStaysPaid} → the UI warns (R16.8). */
    public record CashEntryDeleted(Long transactionId, boolean payoutStaysPaid) {
    }

    // ============================================================================================
    // Reads
    // ============================================================================================

    /** The ledger page + totals of the filter (R20.2–R20.4). */
    @GetMapping("/transactions")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @SuppressWarnings("java:S107")
    public ResponseEntity<LedgerPage> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) LedgerChannel channel,
            @RequestParam(required = false) LedgerDirection direction,
            @RequestParam(required = false) Long connectionId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) List<LedgerStatusFilter> linkStatus,
            @RequestParam(required = false) LinkKind kind,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) List<DetectionFilter> detection,
            @RequestParam(required = false) BigDecimal amountMin,
            @RequestParam(required = false) BigDecimal amountMax,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean includePending,
            @RequestParam(required = false) Long transactionId,
            Pageable pageable) {
        LedgerFilter filter = new LedgerFilter(from, to, channel, direction, connectionId, accountId, linkStatus,
                kind, projectId, detection, amountMin, amountMax, q, includePending, transactionId);
        return ResponseEntity.ok(queryService.list(filter, pageable));
    }

    /** One line; {@code providerPayload} / {@code counterpartyRaw} only for ADMIN (R21.6). */
    @GetMapping("/transactions/{id}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<LedgerDetail> detail(@PathVariable Long id) {
        boolean admin = ForemenPermissionEvaluator.ADMIN_ROLE_CODE.equals(currentRoleCode());
        return ResponseEntity.ok(queryService.detail(id, admin));
    }

    /** Target picker per kind (R17.2). */
    @GetMapping("/transactions/{id}/link-targets")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<List<LinkTarget>> linkTargets(
            @PathVariable Long id,
            @RequestParam LinkKind kind,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) List<DocumentPaymentStatus> paymentStatus) {
        return ResponseEntity.ok(queryService.linkTargets(id, kind, q, projectId, paymentStatus));
    }

    /** Ranked ledger lines that could pay a procurement document (R28.1 "Find bank transaction"). */
    @GetMapping("/transactions/link-candidates")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<List<LineCandidate>> linkCandidates(@RequestParam Long procurementDocumentId) {
        return ResponseEntity.ok(queryService.linkCandidates(procurementDocumentId));
    }

    // ============================================================================================
    // Cash entries (R27.3, R27.6)
    // ============================================================================================

    /** "Add cash entry" with an optional link in the same transaction (R27.3). */
    @PostMapping("/transactions/cash")
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<CashEntryOutcome> createCashEntry(@RequestBody CashEntryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cashLedgerService.createManual(request));
    }

    /** Edits a MANUAL cash entry; SYSTEM / locked → 409 (R27.6). */
    @PutMapping("/transactions/{id}")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<CashEntryOutcome> updateCashEntry(@PathVariable Long id,
                                                            @RequestBody CashEntryRequest request) {
        return ResponseEntity.ok(cashLedgerService.updateManual(id, request));
    }

    /** Deletes a MANUAL cash entry and its unlocked links; payouts stay PAID (R27.6, R16.8). */
    @DeleteMapping("/transactions/{id}")
    @RequiresPermission(resource = RESOURCE, operation = DELETE)
    public ResponseEntity<CashEntryDeleted> deleteCashEntry(@PathVariable Long id) {
        return ResponseEntity.ok(new CashEntryDeleted(id, cashLedgerService.deleteManual(id)));
    }

    // ============================================================================================
    // Links (R13, R17.1)
    // ============================================================================================

    /** Manual link / split (R13.1–R13.4, R17.1). */
    @PostMapping("/transactions/{id}/links")
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<LinkOutcome> createLink(@PathVariable Long id, @RequestBody LinkBody body) {
        LinkBody b = body == null ? emptyLink() : body;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(linkService.createLink(id, b.toRequest(), remember(b.remember())));
    }

    /** Override an unlocked link (R17.1); locked → 409 {@code error.bank.link.locked}. */
    @PutMapping("/links/{linkId}")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<LinkOutcome> replaceLink(@PathVariable Long linkId, @RequestBody LinkBody body) {
        LinkBody b = body == null ? emptyLink() : body;
        return ResponseEntity.ok(linkService.replaceLink(linkId, b.toRequest(), remember(b.remember())));
    }

    /** Removes an unlocked link; {@code payoutStaysPaid} flags the R16.8 warning. */
    @DeleteMapping("/links/{linkId}")
    @RequiresPermission(resource = RESOURCE, operation = DELETE)
    public ResponseEntity<LinkOutcome> removeLink(@PathVariable Long linkId) {
        return ResponseEntity.ok(linkService.removeLink(linkId));
    }

    /** Confirms an open suggestion (R17.1). */
    @PostMapping("/suggestions/{id}/confirm")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<LinkOutcome> confirmSuggestion(@PathVariable Long id,
                                                         @RequestBody(required = false) RememberBody body) {
        return ResponseEntity.ok(linkService.confirmSuggestion(id, remember(body == null ? null : body.remember())));
    }

    /** Rejects an open suggestion (R17.1). */
    @PostMapping("/suggestions/{id}/reject")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<LinkOutcome> rejectSuggestion(@PathVariable Long id) {
        return ResponseEntity.ok(linkService.rejectSuggestion(id));
    }

    /** Confirms the auto links of an {@code AUTO_LINKED} line → {@code LINKED} (R17.1). */
    @PostMapping("/transactions/{id}/confirm-auto")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<LinkOutcome> confirmAuto(@PathVariable Long id,
                                                   @RequestBody(required = false) RememberBody body) {
        return ResponseEntity.ok(linkService.confirmAuto(id, remember(body == null ? null : body.remember())));
    }

    /** Marks a line ignored with a reason (R17.5). */
    @PostMapping("/transactions/{id}/ignore")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<LinkOutcome> ignore(@PathVariable Long id, @RequestBody(required = false) IgnoreBody body) {
        return ResponseEntity.ok(linkService.ignore(id, body == null ? null : body.reason()));
    }

    /** Clears the ignored mark (R17.5). */
    @PostMapping("/transactions/{id}/unignore")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<LinkOutcome> unignore(@PathVariable Long id) {
        return ResponseEntity.ok(linkService.unignore(id));
    }

    /**
     * "Create payout from ledger line" (R16.2): {@code BANK_LEDGER}/CREATE (annotation) and
     * {@code PAYROLL}/CREATE (programmatic, R20.9 / R21.4).
     */
    @PostMapping("/transactions/{id}/payout")
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<LinkOutcome> createPayout(@PathVariable Long id, @RequestBody PayoutBody body) {
        requirePermission(PAYROLL_RESOURCE, CREATE);
        PayoutBody b = body == null ? new PayoutBody(null, null, null, null) : body;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(linkService.createPayoutFromLine(id, b.workerId(), b.amount(), b.note(), remember(b.remember())));
    }

    // ============================================================================================
    // Detection + bulk (R17.6, R20.7)
    // ============================================================================================

    /** "Re-run detection" on ids or on the UNLINKED / SUGGESTED lines of a filter (R17.6). */
    @PostMapping("/transactions/detect")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<DetectionSummary> detect(@RequestBody(required = false) DetectBody body) {
        DetectBody b = body == null ? new DetectBody(null, null, null) : body;
        return ResponseEntity.ok(bulkService.detect(b.ids(), b.filter(), Boolean.TRUE.equals(b.includeAi())));
    }

    /** Bulk confirm of auto links / top suggestions (R20.7); per-line failures in the result. */
    @PostMapping("/transactions/bulk/confirm")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<BulkConfirmResult> bulkConfirm(@RequestBody(required = false) BulkConfirmBody body) {
        BulkConfirmBody b = body == null ? new BulkConfirmBody(null, null) : body;
        return ResponseEntity.ok(bulkService.confirm(b.ids(), remember(b.remember())));
    }

    // ============================================================================================
    // Helpers
    // ============================================================================================

    private static LinkBody emptyLink() {
        return new LinkBody(null, null, null, null, null, null, null, null, null, null);
    }

    private static boolean remember(Boolean value) {
        return value == null || value;
    }

    private void requirePermission(String resource, String operation) {
        String roleCode = currentRoleCode();
        if (roleCode == null || !permissionEvaluator.isAllowed(roleCode, resource, operation)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, ACCESS_DENIED);
        }
    }

    /** The caller's role code from the {@code ROLE_<code>} authority (JWT filter convention). */
    static String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith("ROLE_")) {
                return authority.substring("ROLE_".length());
            }
        }
        return null;
    }
}
