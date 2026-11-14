package com.foremen.controller;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.client.ClientApprovalPositionDto;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.approval.ApprovalDeadlinePolicy;
import com.foremen.service.procurement.approval.ApprovalFilter;
import com.foremen.service.procurement.approval.ApprovalQueryService;
import com.foremen.service.procurement.approval.ApprovalSummary;
import com.foremen.service.procurement.approval.ClientApprovalData;
import com.foremen.service.procurement.approval.ClientStatus;
import com.foremen.service.procurement.approval.ClientViewAssembler;
import com.foremen.service.procurement.approval.PositionApprovalService;
import com.foremen.service.procurement.approval.PositionApprovalService.AcceptCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.Decider;
import com.foremen.service.procurement.approval.PositionApprovalService.SuggestCommand;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-02 client surface of the position approval workflow (design section 4, R1.5, R5, R7, R8, R10, R11).
 *
 * <p>ABAC: no class-level {@code @PermissionResource}; every handler carries an explicit
 * {@link RequiresPermission} ({@code POSITION_APPROVALS} READ for reads, APPROVE for decisions), so
 * {@code PermissionAnnotationValidator} classifies the controller COMPLETE. Reads always return the
 * Client_View (never a staff type). Project confinement of reads is checked here with
 * {@link ProcurementProjectAccess}; decisions are confined by the Client_Member check inside
 * {@link PositionApprovalService} (the {@link Decider#client} path).
 */
@RestController
@RequestMapping("/api/position-approvals/client")
@RequiredArgsConstructor
public class PositionApprovalClientController {

    private static final String RESOURCE = "POSITION_APPROVALS";
    private static final String READ = "READ";
    private static final String APPROVE = "APPROVE";

    private final ApprovalQueryService queryService;
    private final ClientViewAssembler assembler;
    private final PositionApprovalService approvalService;
    private final ProcurementProjectAccess projectAccess;
    private final EntityManager entityManager;
    private final Clock clock;

    // ------------------------------------------------------------------ reads

    @GetMapping("/projects/{projectId}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "Client view of the positions sent for approval")
    public ResponseEntity<List<ClientApprovalPositionDto>> list(
            @PathVariable Long projectId,
            @RequestParam(name = "status", required = false) List<ClientStatus> status,
            @RequestParam(name = "onlyPending", required = false, defaultValue = "false") boolean onlyPending,
            @RequestParam(name = "blockNo", required = false) Integer blockNo) {
        assertProjectAccess(projectId);
        if (status != null && status.size() > 1) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invalid.field.name", "status");
        }
        ClientStatus single = status == null || status.isEmpty() ? null : status.get(0);
        // The client endpoint never uses the staff-only filters (overdue, sourcingPath).
        ApprovalFilter filter = new ApprovalFilter(single, onlyPending, blockNo, false, (SourcingPath) null);
        ClientApprovalData data = queryService.loadClientData(projectId, filter);
        return ResponseEntity.ok(assembler.assemble(data, locale(), ApprovalDeadlinePolicy.today(clock)));
    }

    @GetMapping("/projects/{projectId}/summary")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "Counts for the tab badge")
    public ResponseEntity<ApprovalSummary> summary(@PathVariable Long projectId) {
        assertProjectAccess(projectId);
        return ResponseEntity.ok(queryService.summary(projectId));
    }

    @GetMapping("/positions/{positionId}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "One position in the client view; 404 when it has no round")
    public ResponseEntity<ClientApprovalPositionDto> position(@PathVariable Long positionId) {
        List<Long> projectIds = entityManager.createQuery(
                "SELECT p.project.id FROM PurchasePositionEntity p WHERE p.id = :id", Long.class)
                .setParameter("id", positionId).getResultList();
        if (projectIds.isEmpty()) {
            throw notFound();
        }
        // Unknown and foreign positions look the same to a non-member: 404.
        if (!hasAccess(projectIds.get(0))) {
            throw notFound();
        }
        List<ClientApprovalPositionDto> dtos = assembler.assemble(
                queryService.loadClientPosition(positionId), locale(), ApprovalDeadlinePolicy.today(clock));
        if (dtos.isEmpty()) {
            throw notFound();
        }
        return ResponseEntity.ok(dtos.get(0));
    }

    // ------------------------------------------------------------------ decisions

    @PostMapping("/rounds/{roundId}/accept")
    @RequiresPermission(resource = RESOURCE, operation = APPROVE)
    @Operation(summary = "Accept an open round")
    public ResponseEntity<Void> accept(@PathVariable Long roundId, @Valid @RequestBody AcceptRequest request) {
        approvalService.accept(roundId, new AcceptCommand(request.optionId(), request.expectedUnitSalePrice(),
                request.comment()), decider());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rounds/{roundId}/reject")
    @RequiresPermission(resource = RESOURCE, operation = APPROVE)
    @Operation(summary = "Reject an open round with a comment")
    public ResponseEntity<Void> reject(@PathVariable Long roundId, @Valid @RequestBody RejectRequest request) {
        approvalService.reject(roundId, request.comment(), decider());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/positions/{positionId}/suggest")
    @RequiresPermission(resource = RESOURCE, operation = APPROVE)
    @Operation(summary = "Suggest an own product link")
    public ResponseEntity<Void> suggest(@PathVariable Long positionId, @Valid @RequestBody SuggestRequest request) {
        approvalService.suggest(positionId, new SuggestCommand(request.link(), request.comment()), decider());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/positions/{positionId}/client-own")
    @RequiresPermission(resource = RESOURCE, operation = APPROVE)
    @Operation(summary = "Declare that the client buys the position themselves")
    public ResponseEntity<Void> clientOwn(@PathVariable Long positionId,
                                          @Valid @RequestBody(required = false) ClientOwnRequest request) {
        approvalService.declareClientOwn(positionId, request == null ? null : request.comment(), decider());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ requests

    public record AcceptRequest(@NotNull Long optionId, @NotNull BigDecimal expectedUnitSalePrice,
                                @Size(max = 2000) String comment) {
    }

    public record RejectRequest(@NotBlank @Size(max = 2000) String comment) {
    }

    public record SuggestRequest(@NotBlank @Size(max = 2000) String link, @Size(max = 2000) String comment) {
    }

    public record ClientOwnRequest(@Size(max = 2000) String comment) {
    }

    // ------------------------------------------------------------------ helpers

    private static Locale locale() {
        return LocaleContextHolder.getLocale();
    }

    private Decider decider() {
        return Decider.client(currentUserId());
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long userId)) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        return userId;
    }

    private void assertProjectAccess(Long projectId) {
        if (!hasAccess(projectId)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }
    }

    /** ADMIN sees every project; everyone else only the projects of their ACTIVE memberships. */
    private boolean hasAccess(Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            for (GrantedAuthority ga : auth.getAuthorities()) {
                if ("ROLE_ADMIN".equals(ga.getAuthority())) {
                    return true;
                }
            }
        }
        return projectAccess.allowedProjectIds(currentUserId()).contains(projectId);
    }

    private static ForemenApiException notFound() {
        return new ForemenApiException(HttpStatus.NOT_FOUND, PositionApprovalService.ERROR_ROUND_NOT_FOUND);
    }
}
