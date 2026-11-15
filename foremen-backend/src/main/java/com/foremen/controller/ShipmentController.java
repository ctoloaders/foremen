package com.foremen.controller;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import com.foremen.controller.model.ShipmentDto;
import com.foremen.controller.model.ShipmentFilter;
import com.foremen.controller.model.ShipmentRequest;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.shipment.RefreshOutcome;
import com.foremen.service.procurement.shipment.ShipmentService;
import com.foremen.service.procurement.shipment.ShipmentTrackingService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-11-04 (R1.4, R1.5, R10, R16): the shipment API under {@code /api/shipments}.
 *
 * <p>ABAC: class-level {@code @PermissionResource("SHIPMENTS")} and an explicit method-level
 * {@code @RequiresPermission} on every handler (reads READ, create CREATE, update / cancel / refresh /
 * ETA revert UPDATE), so {@code PermissionAnnotationValidator} classifies the controller COMPLETE.
 * Project scoping (404 without access) lives in {@link ShipmentService}.
 *
 * <p>Refresh: {@code UPDATED / UNCHANGED / FAILED} answer 200 with the shipment and {@code refreshResult};
 * a premature manual call answers 429 with {@code Retry-After} and {@code params.retryAfterSeconds}; a
 * paused carrier (or call spacing) and an unavailable refresh answer 409 with their
 * {@code error.shipment.refresh.*} key.
 */
@RestController
@RequestMapping("/api/shipments")
@RequiredArgsConstructor
@PermissionResource("SHIPMENTS")
public class ShipmentController {

    static final String RESOURCE = "SHIPMENTS";
    static final String TOO_SOON = "error.shipment.refresh.tooSoon";
    static final String CARRIER_PAUSED = "error.shipment.refresh.carrierPaused";
    static final String NOT_AVAILABLE = "error.shipment.refresh.notAvailable";
    static final int MAX_PAGE_SIZE = 200;

    private final ShipmentService shipmentService;
    private final ShipmentTrackingService trackingService;

    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = "READ")
    public ResponseEntity<Page<ShipmentDto>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(required = false) String carrierCode,
            @RequestParam(required = false) Long externalOrderId,
            @RequestParam(required = false) Long positionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate etaFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate etaTo,
            @RequestParam(required = false) Boolean degraded,
            @RequestParam(required = false) Boolean delayed,
            @RequestParam(required = false) String text,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ShipmentFilter filter = new ShipmentFilter(projectId, status, carrierCode, externalOrderId, positionId,
                etaFrom, etaTo, degraded, delayed, text);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return ResponseEntity.ok(shipmentService.list(filter, pageable));
    }

    @GetMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = "READ")
    public ResponseEntity<ShipmentDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(shipmentService.get(id));
    }

    @PostMapping
    @RequiresPermission(resource = RESOURCE, operation = "CREATE")
    public ResponseEntity<ShipmentDto> create(@RequestBody ShipmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shipmentService.create(request));
    }

    @PutMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = "UPDATE")
    public ResponseEntity<ShipmentDto> update(@PathVariable Long id, @RequestBody ShipmentRequest request) {
        return ResponseEntity.ok(shipmentService.update(id, request));
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission(resource = RESOURCE, operation = "UPDATE")
    public ResponseEntity<ShipmentDto> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(shipmentService.cancel(id));
    }

    @PostMapping("/{id}/eta/revert")
    @RequiresPermission(resource = RESOURCE, operation = "UPDATE")
    public ResponseEntity<ShipmentDto> revertEta(@PathVariable Long id) {
        return ResponseEntity.ok(shipmentService.revertEta(id));
    }

    @PostMapping("/{id}/refresh")
    @RequiresPermission(resource = RESOURCE, operation = "UPDATE")
    public ResponseEntity<ShipmentDto> refresh(@PathVariable Long id) {
        shipmentService.get(id); // scope check: 404 when the caller has no access
        RefreshOutcome outcome = trackingService.refresh(id, true);
        switch (outcome.result()) {
            case UPDATED, UNCHANGED, FAILED -> {
                return ResponseEntity.ok(shipmentService.get(id).withRefreshResult(outcome.result().name()));
            }
            default -> throw rejection(outcome);
        }
    }

    private static ForemenApiException rejection(RefreshOutcome outcome) {
        String reason = outcome.reason();
        if (RefreshOutcome.REASON_TOO_SOON.equals(reason)) {
            long seconds = outcome.retryAfterSeconds() != null ? outcome.retryAfterSeconds() : 1L;
            return new ForemenApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_SOON)
                    .withParams(Map.of("retryAfterSeconds", seconds))
                    .withHeader("Retry-After", String.valueOf(seconds));
        }
        if (RefreshOutcome.REASON_CARRIER_PAUSED.equals(reason) || RefreshOutcome.REASON_SPACING.equals(reason)) {
            return new ForemenApiException(HttpStatus.CONFLICT, CARRIER_PAUSED);
        }
        return new ForemenApiException(HttpStatus.CONFLICT, NOT_AVAILABLE);
    }
}
