package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.procurement.schedule.ScheduleOverlayService;
import com.foremen.service.procurement.schedule.ScheduleOverlayService.ScheduleOverlayView;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-04 (R13): read-only materials overlay of the project Gantt. Mapped under the purchase-positions
 * prefix; the literal path wins over {@code /{id}} of {@link PurchasePositionController}. Project scope is
 * enforced by {@link ScheduleOverlayService} (404 without access).
 */
@RestController
@RequestMapping("/api/purchase-positions")
@RequiredArgsConstructor
@PermissionResource("PURCHASE_POSITIONS")
public class ScheduleOverlayController {

    private final ScheduleOverlayService overlayService;

    @GetMapping("/schedule-overlay")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "READ")
    @Operation(summary = "Materials overlay for the project Gantt")
    public ResponseEntity<ScheduleOverlayView> overlay(@RequestParam("projectId") Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(overlayService.overlay(projectId, auth));
    }
}
