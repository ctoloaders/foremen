package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.RequiresPermission;
import com.foremen.service.procurement.planner.BoardService;
import com.foremen.service.procurement.planner.BoardService.Board;
import com.foremen.service.procurement.planner.PlannerFilter.BuyerMode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-01 (R8): the read-only BUYER board. The handler is guarded explicitly with
 * {@link RequiresPermission} on {@code PURCHASE_POSITIONS} READ; the class carries no
 * {@code @PermissionResource} (the {@code PositionVariantController} precedent). The literal path
 * {@code /board} is declared here so it cannot clash with {@code /{id}} of the 00 controller.
 */
@RestController
@RequestMapping("/api/purchase-positions")
@RequiredArgsConstructor
public class PurchaseBoardController {

    private final BoardService service;

    @GetMapping("/board")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "READ")
    @Operation(summary = "Buyer board: six stage columns (WITH_BUYER..RECEIVED) with counts and cards")
    public ResponseEntity<Board> board(
            @Parameter(description = "Restrict to one project (still intersected with the caller's scope)")
            @RequestParam(required = false) Long projectId,
            @Parameter(description = "ALL (default), MINE or UNASSIGNED")
            @RequestParam(required = false, defaultValue = "ALL") BuyerMode buyer,
            @Parameter(description = "Case-insensitive text over material, seller, request reference and notes")
            @RequestParam(required = false) String q,
            @Parameter(description = "When true the RECEIVED column returns its count without cards")
            @RequestParam(required = false, defaultValue = "false") boolean hideDelivered,
            @Parameter(description = "Cards per column, 1..100 (default 30)")
            @RequestParam(required = false) Integer limit,
            Authentication authentication) {
        return ResponseEntity.ok(service.board(projectId, buyer, q, hideDelivered, limit, authentication));
    }
}
