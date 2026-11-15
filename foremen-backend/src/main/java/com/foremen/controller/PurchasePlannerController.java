package com.foremen.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.RequiresPermission;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.planner.PlannerFilter;
import com.foremen.service.procurement.planner.PlannerGroupService;
import com.foremen.service.procurement.planner.PlannerGroupService.PlannerGroupDto;
import com.foremen.service.procurement.planner.PlannerFilter.BuyerMode;
import com.foremen.service.procurement.planner.PlannerQueryService;
import com.foremen.service.procurement.planner.PlannerRowDto;
import com.foremen.service.procurement.planner.PlannerSliceRules.Slice;
import com.foremen.service.procurement.planner.PlannerWorkLinkService;
import com.foremen.service.procurement.planner.PlannerWorkLinkService.ScheduledCategoryDto;
import com.foremen.service.procurement.planner.PlannerWorkLinkService.WorkLinkPreview;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-01 (R1, R2.6, R5.2, R5.10, R9.1): read endpoints of the purchase planner. Not an
 * {@code AdminController}; every handler carries an explicit {@code @RequiresPermission} on
 * {@code PURCHASE_POSITIONS} READ and the class has no {@code @PermissionResource}
 * (the {@link PositionVariantController} precedent), so {@code PermissionAnnotationValidator} accepts it.
 * Literal paths win over {@code PurchasePositionController}'s {@code /{id}}.
 */
@RestController
@RequestMapping("/api/purchase-positions")
@RequiredArgsConstructor
public class PurchasePlannerController {

    static final String SLICE_INVALID = "error.purchasePlanner.slice.invalid";

    private final PlannerQueryService plannerQueryService;
    private final PlannerWorkLinkService workLinkService;
    private final PlannerGroupService groupService;

    @GetMapping("/planner")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "READ")
    @Operation(summary = "Purchase planner rows",
            description = "Positions of the caller's projects, sorted by site delivery date (planned delivery, "
                    + "else need-by) with nulls last unless 'sort' is given. Allowed sort fields: "
                    + "siteDeliveryDate, needByDate, orderByDate, decisionDeadline, createdDate, status. "
                    + "'money' is null for callers without the STAFF_FULL audience.")
    public ResponseEntity<Page<PlannerRowDto>> planner(
            Pageable pageable,
            @Parameter(description = "Restrict to one project (intersected with the caller's scope)")
            @RequestParam(name = "projectId", required = false) Long projectId,
            @Parameter(description = "Stage filter; repeat the parameter for several stages")
            @RequestParam(name = "status", required = false) Set<PositionStatus> status,
            @Parameter(description = "Sourcing path: AGREED or PACKAGE or CLIENT_OWN")
            @RequestParam(name = "sourcingPath", required = false) SourcingPath sourcingPath,
            @Parameter(description = "Only positions of this purchase request")
            @RequestParam(name = "requestId", required = false) Long requestId,
            @Parameter(description = "Only positions without a purchase request")
            @RequestParam(name = "noRequest", required = false, defaultValue = "false") boolean noRequest,
            @Parameter(description = "ALL (default), MINE (requests assigned to me) or UNASSIGNED")
            @RequestParam(name = "buyer", required = false) BuyerMode buyer,
            @Parameter(description = "Quick slice: ALL, ON_SCHEDULE, LATE, CHANGED, PAID, NOT_PAID")
            @RequestParam(name = "slice", required = false) String slice,
            @Parameter(description = "Only urgent positions")
            @RequestParam(name = "urgent", required = false, defaultValue = "false") boolean urgent,
            @Parameter(description = "Case-insensitive text over material, producer, seller, request reference, notes")
            @RequestParam(name = "q", required = false) String q,
            @Parameter(description = "Hide stage RECEIVED (default false)")
            @RequestParam(name = "hideDelivered", required = false, defaultValue = "false") boolean hideDelivered,
            @Parameter(description = "Only positions on this proforma (active line)")
            @RequestParam(name = "proformaId", required = false) Long proformaId,
            @Parameter(description = "Only positions with a line on this external order")
            @RequestParam(name = "externalOrderId", required = false) Long externalOrderId,
            @Parameter(description = "Only positions without an active proforma line")
            @RequestParam(name = "noProforma", required = false, defaultValue = "false") boolean noProforma,
            @Parameter(description = "Only positions without a line on a non-cancelled external order")
            @RequestParam(name = "noExternalOrder", required = false, defaultValue = "false") boolean noExternalOrder,
            Authentication authentication) {
        PlannerFilter filter = filter(projectId, status, sourcingPath, requestId, noRequest, buyer, slice,
                urgent, q, hideDelivered, proformaId, externalOrderId, noProforma, noExternalOrder);
        return ResponseEntity.ok(plannerQueryService.plan(filter, pageable, authentication));
    }

    @GetMapping("/planner/groups")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "READ")
    @Operation(summary = "Purchase planner groups",
            description = "Page of groups (by purchase request, or one synthetic group with groupBy=NONE) over the "
                    + "planner filters, ordered by the earliest site delivery date (nulls last), then request id. "
                    + "Positions without a request form the synthetic group with requestId null. 'totals' is null "
                    + "for callers without the STAFF_FULL audience. Rows are loaded through /planner with "
                    + "requestId or noRequest. groupBy=PROFORMA or EXTERNAL_ORDER groups by the proforma / supplier "
                    + "order (positions without one form NO_PROFORMA / NO_EXTERNAL_ORDER; a position on several "
                    + "orders appears in each). Unknown groupBy values give 400.")
    public ResponseEntity<Page<PlannerGroupDto>> groups(
            Pageable pageable,
            @Parameter(description = "REQUEST (default), NONE, PROFORMA or EXTERNAL_ORDER")
            @RequestParam(name = "groupBy", required = false) String groupBy,
            @RequestParam(name = "projectId", required = false) Long projectId,
            @RequestParam(name = "status", required = false) Set<PositionStatus> status,
            @RequestParam(name = "sourcingPath", required = false) SourcingPath sourcingPath,
            @Parameter(description = "Restrict to one request (used by the deep link)")
            @RequestParam(name = "requestId", required = false) Long requestId,
            @RequestParam(name = "noRequest", required = false, defaultValue = "false") boolean noRequest,
            @RequestParam(name = "buyer", required = false) BuyerMode buyer,
            @RequestParam(name = "slice", required = false) String slice,
            @RequestParam(name = "urgent", required = false, defaultValue = "false") boolean urgent,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "hideDelivered", required = false, defaultValue = "false") boolean hideDelivered,
            @Parameter(description = "Only positions on this proforma (active line)")
            @RequestParam(name = "proformaId", required = false) Long proformaId,
            @Parameter(description = "Only positions with a line on this external order")
            @RequestParam(name = "externalOrderId", required = false) Long externalOrderId,
            @Parameter(description = "Only positions without an active proforma line")
            @RequestParam(name = "noProforma", required = false, defaultValue = "false") boolean noProforma,
            @Parameter(description = "Only positions without a line on a non-cancelled external order")
            @RequestParam(name = "noExternalOrder", required = false, defaultValue = "false") boolean noExternalOrder,
            Authentication authentication) {
        PlannerGroupService.GroupBy mode = PlannerGroupService.parseGroupBy(groupBy);
        PlannerFilter filter = filter(projectId, status, sourcingPath, requestId, noRequest, buyer, slice,
                urgent, q, hideDelivered, proformaId, externalOrderId, noProforma, noExternalOrder);
        return ResponseEntity.ok(groupService.groups(filter, mode, pageable, authentication));
    }

    @GetMapping("/planner/slice-counts")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "READ")
    @Operation(summary = "Counts per planner slice",
            description = "Counts for ALL, ON_SCHEDULE, LATE, CHANGED, PAID, NOT_PAID over the same filters as the "
                    + "planner except the slice itself.")
    public ResponseEntity<Map<Slice, Long>> sliceCounts(
            @RequestParam(name = "projectId", required = false) Long projectId,
            @RequestParam(name = "status", required = false) Set<PositionStatus> status,
            @RequestParam(name = "sourcingPath", required = false) SourcingPath sourcingPath,
            @RequestParam(name = "requestId", required = false) Long requestId,
            @RequestParam(name = "noRequest", required = false, defaultValue = "false") boolean noRequest,
            @RequestParam(name = "buyer", required = false) BuyerMode buyer,
            @RequestParam(name = "urgent", required = false, defaultValue = "false") boolean urgent,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "hideDelivered", required = false, defaultValue = "false") boolean hideDelivered,
            @Parameter(description = "Only positions on this proforma (active line)")
            @RequestParam(name = "proformaId", required = false) Long proformaId,
            @Parameter(description = "Only positions with a line on this external order")
            @RequestParam(name = "externalOrderId", required = false) Long externalOrderId,
            @Parameter(description = "Only positions without an active proforma line")
            @RequestParam(name = "noProforma", required = false, defaultValue = "false") boolean noProforma,
            @Parameter(description = "Only positions without a line on a non-cancelled external order")
            @RequestParam(name = "noExternalOrder", required = false, defaultValue = "false") boolean noExternalOrder,
            Authentication authentication) {
        PlannerFilter filter = filter(projectId, status, sourcingPath, requestId, noRequest, buyer, null,
                urgent, q, hideDelivered, proformaId, externalOrderId, noProforma, noExternalOrder);
        return ResponseEntity.ok(plannerQueryService.sliceCounts(filter, authentication));
    }

    @GetMapping("/scheduled-categories")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "READ")
    @Operation(summary = "Work categories scheduled in a project",
            description = "Categories with a stored schedule bar that are still schedule rows, earliest start "
                    + "first, at most 50. 404 for an inaccessible project.")
    public ResponseEntity<List<ScheduledCategoryDto>> scheduledCategories(
            @Parameter(description = "Project id", required = true)
            @RequestParam(name = "projectId") Long projectId,
            @Parameter(description = "Case-insensitive text over the Russian and Polish category names")
            @RequestParam(name = "q", required = false) String q,
            Authentication authentication) {
        return ResponseEntity.ok(workLinkService.scheduledCategories(projectId, q, authentication));
    }

    @GetMapping("/work-link-preview")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "READ")
    @Operation(summary = "Automatic work-category link for a material in a project",
            description = "Resolution of the effective work category before the position exists. "
                    + "404 for an inaccessible project or unknown material.")
    public ResponseEntity<WorkLinkPreview> workLinkPreview(
            @Parameter(description = "Project id", required = true)
            @RequestParam(name = "projectId") Long projectId,
            @Parameter(description = "Finishing material id", required = true)
            @RequestParam(name = "materialId") Long materialId,
            Authentication authentication) {
        return ResponseEntity.ok(workLinkService.preview(projectId, materialId, authentication));
    }

    private static PlannerFilter filter(Long projectId, Set<PositionStatus> status, SourcingPath sourcingPath,
                                        Long requestId, boolean noRequest, BuyerMode buyer, String slice,
                                        boolean urgent, String q, boolean hideDelivered, Long proformaId,
                                        Long externalOrderId, boolean noProforma, boolean noExternalOrder) {
        return new PlannerFilter(projectId, status, sourcingPath, requestId, noRequest,
                buyer == null ? BuyerMode.ALL : buyer, parseSlice(slice), urgent, q, hideDelivered, proformaId,
                externalOrderId, noProforma, noExternalOrder);
    }

    static Slice parseSlice(String slice) {
        if (slice == null || slice.isBlank()) {
            return Slice.ALL;
        }
        try {
            return Slice.valueOf(slice.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, SLICE_INVALID);
        }
    }
}
