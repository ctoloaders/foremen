package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.SelectionTotalRequest;
import com.foremen.controller.model.SelectionTotalResponse;
import com.foremen.service.procurement.planner.SelectionTotalService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-01 (R6): tick-to-proforma selection total. The handler is guarded explicitly with
 * {@link RequiresPermission} on {@code PURCHASE_POSITIONS} READ; the class carries no
 * {@code @PermissionResource} (the {@code PositionVariantController} precedent). The service restricts
 * the endpoint to the {@code STAFF_FULL} audience and never writes.
 */
@RestController
@RequestMapping("/api/purchase-positions")
@RequiredArgsConstructor
public class PurchaseSelectionController {

    private final SelectionTotalService service;

    @PostMapping("/selection-total")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "READ")
    @Operation(summary = "Total of ticked positions (1..500 ids); counts only AGREED, ACCEPTED, priced positions")
    public ResponseEntity<SelectionTotalResponse> selectionTotal(@RequestBody SelectionTotalRequest request) {
        return ResponseEntity.ok(service.total(request == null ? null : request.positionIds()));
    }
}
