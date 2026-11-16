package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.GoodsReceiptDelegationDto;
import com.foremen.controller.model.GoodsReceiptDelegationRequest;
import com.foremen.dao.model.DelegationStatus;
import com.foremen.service.procurement.receipt.GoodsReceiptDelegationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FOR-11-06 (R12.2, R12.3): receipt delegation API under {@code /api/goods-receipts/delegations}, guarded by
 * the existing {@code GOODS_RECEIPTS} resource (create CREATE, revoke UPDATE, list READ). The caller check
 * (ADMIN or project MANAGER) and project scoping live in {@link GoodsReceiptDelegationService}.
 */
@RestController
@RequestMapping("/api/goods-receipts/delegations")
@RequiredArgsConstructor
@PermissionResource("GOODS_RECEIPTS")
public class GoodsReceiptDelegationController {

    static final String RESOURCE = "GOODS_RECEIPTS";
    static final int MAX_PAGE_SIZE = 200;

    private final GoodsReceiptDelegationService delegationService;

    @PostMapping
    @RequiresPermission(resource = RESOURCE, operation = "CREATE")
    public ResponseEntity<GoodsReceiptDelegationDto> delegate(@RequestBody GoodsReceiptDelegationRequest request) {
        Long shipmentId = request == null ? null : request.shipmentId();
        Long assigneeId = request == null ? null : request.assigneeId();
        return ResponseEntity.status(HttpStatus.CREATED).body(delegationService.delegate(shipmentId, assigneeId));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = "UPDATE")
    public ResponseEntity<GoodsReceiptDelegationDto> revoke(@PathVariable Long id) {
        return ResponseEntity.ok(delegationService.revoke(id));
    }

    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = "READ")
    public ResponseEntity<Page<GoodsReceiptDelegationDto>> list(
            @RequestParam(required = false) Long shipmentId,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) DelegationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return ResponseEntity.ok(delegationService.list(shipmentId, assigneeId, status, pageable));
    }
}
