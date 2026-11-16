package com.foremen.controller;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

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
import com.foremen.controller.model.staff.StaffApprovalPositionDto;
import com.foremen.controller.model.staff.StaffRoundDto;
import com.foremen.controller.model.staff.StaffViewAssembler;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.approval.ApprovalDeadlinePolicy;
import com.foremen.service.procurement.approval.PositionApprovalBulkExecutor;
import com.foremen.service.procurement.approval.PositionApprovalService;
import com.foremen.service.procurement.approval.PositionApprovalService.AcceptCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.AnalogCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.Decider;
import com.foremen.service.procurement.approval.PositionApprovalService.SendChoiceCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.SendCommand;
import com.foremen.service.procurement.approval.StaffApprovalData;
import com.foremen.service.procurement.approval.StaffApprovalFilter;
import com.foremen.service.procurement.approval.StaffApprovalQueryService;
import com.foremen.service.procurement.position.PositionAudienceResolver;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-02 staff surface of the position approval workflow (design section 5; R1.5, R4.5, R6.1, R6.7, R9.1,
 * R10.3, R12.1, R13.1, R13.2, R14.2).
 *
 * <p>ABAC: no class-level {@code @PermissionResource}; every handler has an explicit
 * {@link RequiresPermission} on {@code POSITION_APPROVALS} (READ for the staff view, CREATE for send /
 * send-choice / propose-analog, UPDATE for withdraw / deadline / decline, APPROVE for the on-behalf
 * decisions). A CLIENT holds READ and APPROVE only, so it reaches the staff reads and the on-behalf
 * endpoints; those fail on the audience re-check ({@code staff.forbidden}) and on the MANAGER/ADMIN role
 * check ({@code onBehalf.forbidden}). A BUYER holds READ only.
 */
@RestController
@RequestMapping("/api/position-approvals/staff")
@RequiredArgsConstructor
public class PositionApprovalStaffController {

    private static final String RESOURCE = "POSITION_APPROVALS";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String APPROVE = "APPROVE";
    private static final Set<String> ON_BEHALF_ROLES = Set.of("ADMIN", "MANAGER");
    private static final List<String> ROLE_PRIORITY = List.of("ADMIN", "MANAGER", "BUYER", "CLIENT");

    public static final String ERROR_ON_BEHALF_FORBIDDEN = "error.positionApproval.onBehalf.forbidden";

    private final StaffApprovalQueryService queryService;
    private final StaffViewAssembler assembler;
    private final PositionAudienceResolver audienceResolver;
    private final PositionApprovalService approvalService;
    private final ProcurementProjectAccess projectAccess;
    private final EntityManager entityManager;
    private final Clock clock;
    private final PositionApprovalBulkExecutor bulkExecutor;

    // ------------------------------------------------------------------ reads

    @GetMapping("/projects/{projectId}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "Staff view of the positions in the approval workflow")
    public ResponseEntity<List<StaffApprovalPositionDto>> list(
            @PathVariable Long projectId,
            @RequestParam(name = "status", required = false) List<PositionStatus> status,
            @RequestParam(name = "overdue", required = false, defaultValue = "false") boolean overdue,
            @RequestParam(name = "blockNo", required = false) Integer blockNo,
            @RequestParam(name = "sourcingPath", required = false) SourcingPath sourcingPath) {
        Audience audience = requireStaffAudience();
        assertProjectAccess(projectId);
        StaffApprovalFilter filter = new StaffApprovalFilter(
                status == null || status.isEmpty() ? null : new LinkedHashSet<>(status), overdue, blockNo,
                sourcingPath);
        StaffApprovalData data = queryService.loadStaffData(projectId, filter);
        return ResponseEntity.ok(assembler.assemble(data, audience, currentRoleCode(),
                ApprovalDeadlinePolicy.today(clock), isPolish()));
    }

    @GetMapping("/positions/{positionId}/rounds")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "Full round history of one position, with internal prices")
    public ResponseEntity<List<StaffRoundDto>> rounds(@PathVariable Long positionId) {
        Audience audience = requireStaffAudience();
        List<Long> projectIds = entityManager.createQuery(
                "SELECT p.project.id FROM PurchasePositionEntity p WHERE p.id = :id", Long.class)
                .setParameter("id", positionId).getResultList();
        if (projectIds.isEmpty() || !hasAccess(projectIds.get(0))) {
            throw notFound();
        }
        List<StaffApprovalPositionDto> dtos = assembler.assemble(queryService.loadStaffPosition(positionId),
                audience, currentRoleCode(), ApprovalDeadlinePolicy.today(clock), isPolish());
        if (dtos.isEmpty()) {
            throw notFound();
        }
        StaffApprovalPositionDto dto = dtos.get(0);
        List<StaffRoundDto> all = Stream.of(
                        dto.openRound() == null ? Stream.<StaffRoundDto>empty() : Stream.of(dto.openRound()),
                        dto.openSuggestions().stream(), dto.history().stream())
                .flatMap(s -> s)
                .sorted(Comparator.comparing(StaffRoundDto::roundNo).reversed())
                .toList();
        return ResponseEntity.ok(all);
    }

    // ------------------------------------------------------------------ writes

    @PostMapping("/send")
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    @Operation(summary = "Send positions to the client for a decision")
    public ResponseEntity<List<Long>> send(@Valid @RequestBody SendRequest request) {
        return ResponseEntity.ok(approvalService.send(new SendCommand(request.positionIds(),
                request.decisionDeadline(), request.managerNote())));
    }

    @PostMapping("/send-choice")
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    @Operation(summary = "Offer the client a choice of 2..5 package materials")
    public ResponseEntity<Long> sendChoice(@Valid @RequestBody SendChoiceRequest request) {
        return ResponseEntity.ok(approvalService.sendChoice(new SendChoiceCommand(request.positionId(),
                request.materialIds(), request.decisionDeadline(), request.managerNote())));
    }

    @PostMapping("/positions/{positionId}/propose-analog")
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    @Operation(summary = "Propose an analog for a position")
    public ResponseEntity<Long> proposeAnalog(@PathVariable Long positionId,
                                              @Valid @RequestBody ProposeAnalogRequest request) {
        return ResponseEntity.ok(approvalService.proposeAnalog(positionId, new AnalogCommand(
                request.materialId(), request.unitSalePrice(), request.internalUnitPurchasePrice(),
                request.decisionDeadline(), request.managerNote(), request.sourceSuggestionId())));
    }

    @PostMapping("/rounds/{roundId}/withdraw")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    @Operation(summary = "Withdraw an open round")
    public ResponseEntity<Void> withdraw(@PathVariable Long roundId) {
        approvalService.withdraw(roundId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rounds/{roundId}/deadline")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    @Operation(summary = "Change the decision deadline of an open round")
    public ResponseEntity<Void> changeDeadline(@PathVariable Long roundId,
                                               @Valid @RequestBody DeadlineRequest request) {
        approvalService.changeDeadline(roundId, request.decisionDeadline());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/suggestions/{roundId}/decline")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    @Operation(summary = "Decline an open client suggestion")
    public ResponseEntity<Void> declineSuggestion(@PathVariable Long roundId,
                                                  @Valid @RequestBody DeclineRequest request) {
        approvalService.declineSuggestion(roundId, request.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rounds/{roundId}/decide-on-behalf")
    @RequiresPermission(resource = RESOURCE, operation = APPROVE)
    @Operation(summary = "Record the client's accept or reject on their behalf")
    public ResponseEntity<Void> decideOnBehalf(@PathVariable Long roundId,
                                               @Valid @RequestBody DecideOnBehalfRequest request) {
        Decider decider = onBehalfDecider();
        if (request.decision() == Decision.ACCEPT) {
            approvalService.accept(roundId, new AcceptCommand(request.optionId(),
                    request.expectedUnitSalePrice(), request.comment()), decider);
        } else {
            approvalService.reject(roundId, request.comment(), decider);
        }
        return ResponseEntity.noContent().build();
    }

    /** FOR-11-06 R8.4-8.6: accept the open round of each position on behalf of the client, one tx per item. */
    @PostMapping("/rounds/decide-on-behalf-bulk")
    @RequiresPermission(resource = RESOURCE, operation = APPROVE)
    @Operation(summary = "Record the client's accept on their behalf for several positions")
    public ResponseEntity<BulkResultDto> decideOnBehalfBulk(@Valid @RequestBody DecideBulkRequest request) {
        PositionApprovalBulkExecutor.assertWithinCap(request.items());
        Decider decider = onBehalfDecider();
        List<PositionApprovalBulkExecutor.AcceptItem> items = request.items().stream()
                .map(i -> new PositionApprovalBulkExecutor.AcceptItem(i.positionId(), i.optionId(),
                        i.expectedUnitSalePrice(), i.comment()))
                .toList();
        return ResponseEntity.ok(BulkResultDto.of(bulkExecutor.acceptOnBehalf(items, decider)));
    }

    /** FOR-11-06 R8.4, 8.6: withdraw several open rounds, one tx per item. */
    @PostMapping("/rounds/withdraw-bulk")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    @Operation(summary = "Withdraw several open rounds")
    public ResponseEntity<BulkResultDto> withdrawBulk(@Valid @RequestBody WithdrawBulkRequest request) {
        PositionApprovalBulkExecutor.assertWithinCap(request.items());
        List<Long> roundIds = request.items().stream().map(WithdrawBulkItem::roundId).toList();
        return ResponseEntity.ok(BulkResultDto.of(bulkExecutor.withdraw(roundIds)));
    }

    @PostMapping("/positions/{positionId}/client-own-on-behalf")
    @RequiresPermission(resource = RESOURCE, operation = APPROVE)
    @Operation(summary = "Record 'client buys it themselves' on the client's behalf")
    public ResponseEntity<Void> clientOwnOnBehalf(@PathVariable Long positionId,
                                                  @Valid @RequestBody ClientOwnOnBehalfRequest request) {
        approvalService.declareClientOwn(positionId, request.comment(), onBehalfDecider());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ requests

    public enum Decision { ACCEPT, REJECT }

    public record SendRequest(@NotEmpty List<@NotNull Long> positionIds, LocalDate decisionDeadline,
                              @Size(max = 1000) String managerNote) {
    }

    public record SendChoiceRequest(@NotNull Long positionId, @NotEmpty List<Long> materialIds,
                                    LocalDate decisionDeadline, @Size(max = 1000) String managerNote) {
    }

    public record ProposeAnalogRequest(@NotNull Long materialId, @NotNull BigDecimal unitSalePrice,
                                       BigDecimal internalUnitPurchasePrice, LocalDate decisionDeadline,
                                       @Size(max = 1000) String managerNote, Long sourceSuggestionId) {
    }

    public record DeadlineRequest(@NotNull LocalDate decisionDeadline) {
    }

    public record DeclineRequest(@NotBlank @Size(max = 1000) String note) {
    }

    public record DecideOnBehalfRequest(@NotNull Decision decision, Long optionId,
                                        BigDecimal expectedUnitSalePrice,
                                        @Size(max = 1000) String comment) {
    }

    public record ClientOwnOnBehalfRequest(@Size(max = 1000) String comment) {
    }

    public record DecideBulkItem(@NotNull Long positionId, Long optionId, BigDecimal expectedUnitSalePrice,
                                 @Size(max = 1000) String comment) {
    }

    public record DecideBulkRequest(@NotEmpty List<@NotNull @Valid DecideBulkItem> items) {
    }

    public record WithdrawBulkItem(@NotNull Long roundId) {
    }

    public record WithdrawBulkRequest(@NotEmpty List<@NotNull @Valid WithdrawBulkItem> items) {
    }

    /** {@code {succeeded: [positionId], failed: [{positionId, errorKey}]}}. */
    public record BulkResultDto(List<Long> succeeded, List<BulkFailureDto> failed) {
        static BulkResultDto of(PositionApprovalBulkExecutor.Result r) {
            return new BulkResultDto(r.succeeded(), r.failed().stream()
                    .map(f -> new BulkFailureDto(f.positionId(), f.errorKey())).toList());
        }
    }

    public record BulkFailureDto(Long positionId, String errorKey) {
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isPolish() {
        return "pl".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
    }

    /** The staff view is for STAFF_FULL only; anyone else (CLIENT, SITE) gets 403 staff.forbidden. */
    private Audience requireStaffAudience() {
        Audience audience = audienceResolver.current();
        if (audience != Audience.STAFF_FULL) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, StaffViewAssembler.ERROR_FORBIDDEN);
        }
        return audience;
    }

    private Decider onBehalfDecider() {
        if (!ON_BEHALF_ROLES.contains(currentRoleCode())) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, ERROR_ON_BEHALF_FORBIDDEN);
        }
        return Decider.staffOnBehalf(currentUserId());
    }

    /** Highest-priority known role among the caller's authorities; {@code null} when none. */
    private static String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        Set<String> codes = new LinkedHashSet<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (a != null && a.startsWith("ROLE_")) {
                codes.add(a.substring("ROLE_".length()));
            }
        }
        for (String code : ROLE_PRIORITY) {
            if (codes.contains(code)) {
                return code;
            }
        }
        return codes.isEmpty() ? null : codes.iterator().next();
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

    /** ADMIN sees every project; everyone else only the projects of their ACTIVE memberships (BUYER: all). */
    private boolean hasAccess(Long projectId) {
        if ("ADMIN".equals(currentRoleCode())) {
            return true;
        }
        return projectAccess.allowedProjectIds(currentUserId()).contains(projectId);
    }

    private static ForemenApiException notFound() {
        return new ForemenApiException(HttpStatus.NOT_FOUND, PositionApprovalService.ERROR_ROUND_NOT_FOUND);
    }
}
