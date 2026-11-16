package com.foremen.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.service.procurement.estimatesync.EstimateSyncService;
import com.foremen.service.procurement.estimatesync.EstimateSyncSummary;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-06 (R5.6): {@code POST /api/purchase-positions/sync-from-estimate?projectId=}. A separate class
 * under the {@code PURCHASE_POSITIONS} resource; the literal path wins over {@code /{id}}. A project that
 * is not ACTIVE is refused with 409 {@code error.purchasePosition.project.notActive}.
 */
@RestController
@RequestMapping("/api/purchase-positions")
@RequiredArgsConstructor
@PermissionResource("PURCHASE_POSITIONS")
public class EstimateSyncController {

    private final EstimateSyncService service;

    @PostMapping("/sync-from-estimate")
    @PermissionOperation("CREATE")
    @Operation(summary = "Create or refresh the finishing positions from the project's materials list")
    public EstimateSyncSummary sync(@RequestParam Long projectId) {
        return service.syncForCaller(projectId);
    }
}
