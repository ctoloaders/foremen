package com.foremen.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.PositionTransitionRequest;
import com.foremen.controller.model.PurchasePositionCreateRequest;
import com.foremen.controller.model.PurchasePositionDtoModel;
import com.foremen.controller.model.PurchasePositionUpdateRequest;
import com.foremen.controller.model.mapper.PurchasePositionControllerMapper;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.PurchasePositionService;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.model.AuditServiceModel;
import com.foremen.service.model.PurchasePositionServiceExtendedModel;
import com.foremen.service.model.PurchasePositionServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import com.foremen.controller.model.UpcomingDeliveryDto;
import com.foremen.service.procurement.UpcomingDeliveriesService;
import com.foremen.service.procurement.position.PositionAudienceResolver;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * CRUD controller for purchase positions (FOR-11-00 R9). The inherited, fully annotated CRUD defaults
 * combine with the class-level {@link PermissionResource}; the transition endpoint is guarded
 * explicitly with {@link RequiresPermission}. Every DTO passes through the price-visibility policy in
 * {@link PurchasePositionControllerMapper}.
 *
 * <p>{@code POST /{id}/transition} drives only {@link Actor#MANUAL} edges (cancel, reopen, the
 * PACKAGE and CLIENT_OWN shortcuts), and only for MANAGER / ADMIN (R8.4, R8.5, R8.7); every other
 * edge is reachable only through internal services.
 */
@RestController
@RequestMapping("/api/purchase-positions")
@RequiredArgsConstructor
@PermissionResource("PURCHASE_POSITIONS")
public class PurchasePositionController implements AdminController<
        PurchasePositionServiceModel,
        PurchasePositionServiceExtendedModel,
        PurchasePositionDtoModel,
        PurchasePositionDtoModel,
        PurchasePositionEntity,
        Long,
        PurchasePositionCreateRequest,
        PurchasePositionDtoModel,
        PurchasePositionUpdateRequest,
        PurchasePositionDtoModel> {

    /** Audit snapshot keys that carry Money_Fields; hidden from callers that are not STAFF_FULL. */
    private static final Set<String> MONEY_AUDIT_KEYS = Set.of(
            "plannedPurchasePrice", "salePrice", "actualPurchasePrice",
            "purchaseVatRateId", "saleVatRateId", "deliveryCostShare");

    private final PurchasePositionService service;
    private final PurchasePositionControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;
    private final PositionLifecycleService lifecycleService;
    private final PositionAudienceResolver audienceResolver;
    private final UpcomingDeliveriesService upcomingDeliveriesService;

    @Override
    public ControllerToServiceMapper<PurchasePositionServiceModel, PurchasePositionServiceExtendedModel,
            PurchasePositionDtoModel, PurchasePositionDtoModel,
            PurchasePositionCreateRequest, PurchasePositionDtoModel,
            PurchasePositionUpdateRequest, PurchasePositionDtoModel> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<PurchasePositionServiceModel, PurchasePositionServiceExtendedModel,
            PurchasePositionEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /** Paginated list; documents the {@code hideDelivered} flag that the service reads from the request (R22). */
    @Override
    @GetMapping
    @PermissionOperation("READ")
    @Operation(summary = "List purchase positions",
            parameters = @Parameter(name = "hideDelivered", in = ParameterIn.QUERY,
                    description = "When true, positions in stage RECEIVED are excluded (PARTIALLY_RECEIVED stay "
                            + "visible). Composes with query, sort, pagination and project scoping. Default false.",
                    schema = @Schema(type = "boolean", defaultValue = "false")))
    public ResponseEntity<Page<PurchasePositionDtoModel>> find(
            Pageable pageable, @RequestParam(name = "query", required = false) String query) {
        return AdminController.super.find(pageable, query);
    }

    /**
     * Planned deliveries of the next {@code days} days plus overdue ones (R23). A literal path, so it wins
     * over {@code /{id}}. The DTO has no Money_Field, so no price-visibility step is needed.
     */
    @GetMapping("/upcoming")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "READ")
    @Operation(summary = "Upcoming deliveries")
    public ResponseEntity<List<UpcomingDeliveryDto>> upcoming(
            @RequestParam(name = "days", required = false) Integer days,
            @RequestParam(name = "projectId", required = false) Long projectId) {
        return ResponseEntity.ok(upcomingDeliveriesService.upcoming(days, projectId));
    }

    /** Manual stage change (R8.7). Requires MANAGER or ADMIN on top of the UPDATE grant. */
    @PostMapping("/{id}/transition")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "UPDATE")
    public ResponseEntity<PurchasePositionDtoModel> transition(
            @PathVariable Long id, @Valid @RequestBody PositionTransitionRequest request) {
        if (!hasAuthority("ADMIN") && !hasAuthority("MANAGER")) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }
        PurchasePositionServiceExtendedModel result =
                lifecycleService.transition(id, request.target(), Actor.MANUAL);
        service.fillVariantCounts(List.of(result));
        return ResponseEntity.ok(controllerMapper.toUpdateResponse(result));
    }

    /** Audit trail; snapshots omit the Money_Fields unless the caller is STAFF_FULL. */
    @Override
    @GetMapping("/audit/{id}")
    @PermissionOperation("READ")
    public ResponseEntity<List<AuditServiceModel>> getAudit(@PathVariable Long id) {
        List<AuditLogEntity> records = service.getAuditLogDao()
                .findByEntityClassAndEntityIdOrderByPerformedAtAsc(
                        service.getDaoModelClass().getSimpleName(), id);
        boolean staff = audienceResolver.isStaffFull();
        List<AuditServiceModel> models = records.stream()
                .map(auditServiceMapper::toServiceModel)
                .map(m -> staff ? m : withoutMoney(m))
                .toList();
        return ResponseEntity.ok(models);
    }

    private static AuditServiceModel withoutMoney(AuditServiceModel m) {
        return new AuditServiceModel(m.id(), m.entityClass(), m.entityId(), m.operation(), m.performedBy(),
                m.performedAt(), strip(m.snapshotBefore()), strip(m.snapshotAfter()));
    }

    private static Map<String, Object> strip(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(snapshot);
        copy.keySet().removeAll(MONEY_AUDIT_KEYS);
        return copy;
    }

    private static boolean hasAuthority(String code) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (("ROLE_" + code).equals(ga.getAuthority()) || code.equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
