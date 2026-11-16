package com.foremen.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.BuyerAddedPositionRequest;
import com.foremen.controller.model.PurchasePositionDtoModel;
import com.foremen.controller.model.mapper.PurchasePositionControllerMapper;
import com.foremen.service.procurement.position.BuyerAddedPositionService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-06 (R10.1): {@code POST /api/purchase-positions/buyer-added}. A separate class under the
 * {@code PURCHASE_POSITIONS} resource; the generic create of {@link PurchasePositionController} is
 * unchanged. The literal path wins over {@code /{id}}. Roles other than BUYER, MANAGER and ADMIN are
 * refused by the service with {@code error.purchasePosition.buyerAdded.forbidden}. The response passes
 * the price-visibility policy like every purchase-position DTO.
 */
@RestController
@RequestMapping("/api/purchase-positions")
@RequiredArgsConstructor
@PermissionResource("PURCHASE_POSITIONS")
public class BuyerAddedPositionController {

    private final BuyerAddedPositionService service;
    private final PurchasePositionControllerMapper controllerMapper;

    @PostMapping("/buyer-added")
    @PermissionOperation("CREATE")
    @Operation(summary = "Create a buyer-added position (unpaid by the client, moved to WITH_BUYER)")
    public ResponseEntity<PurchasePositionDtoModel> create(@Valid @RequestBody BuyerAddedPositionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(controllerMapper.toCreateResponse(service.create(request)));
    }
}
