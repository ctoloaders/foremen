package com.foremen.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.PositionVariantDto;
import com.foremen.controller.model.PurchasePositionDtoModel;
import com.foremen.controller.model.VariantReplaceRequest;
import com.foremen.controller.model.VariantSuggestionDto;
import com.foremen.controller.model.mapper.PurchasePositionControllerMapper;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.position.PositionVariantService;
import com.foremen.service.procurement.position.ReplaceCommand;
import com.foremen.service.procurement.position.SuggestionView;
import com.foremen.service.procurement.position.VariantView;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-00 (R21): variant history of a purchase position. Every handler is guarded explicitly with
 * {@link RequiresPermission} on {@code PURCHASE_POSITIONS} (READ for reads, UPDATE for writes); the
 * class carries no {@code @PermissionResource}. Writes are MANUAL (R21.7) and return the updated
 * position DTO; money passes through the price-visibility policy in the service and the mapper.
 */
@RestController
@RequestMapping("/api/purchase-positions/{id}/variants")
@RequiredArgsConstructor
public class PositionVariantController {

    private final PositionVariantService service;
    private final PurchasePositionControllerMapper positionMapper;
    private final ImageStorage imageStorage;

    @GetMapping
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "READ")
    public ResponseEntity<List<PositionVariantDto>> history(@PathVariable Long id) {
        return ResponseEntity.ok(service.history(id).stream().map(this::toDto).toList());
    }

    @GetMapping("/suggestions")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "READ")
    public ResponseEntity<List<VariantSuggestionDto>> suggestions(
            @PathVariable Long id, @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(service.suggestions(id, limit).stream().map(this::toDto).toList());
    }

    @PostMapping("/replace")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "UPDATE")
    public ResponseEntity<PurchasePositionDtoModel> replace(
            @PathVariable Long id, @Valid @RequestBody VariantReplaceRequest request) {
        ReplaceCommand cmd = new ReplaceCommand(request.materialId(), request.salePrice(),
                request.plannedPurchasePrice(), request.reason(), request.note());
        return ResponseEntity.ok(positionMapper.toUpdateResponse(service.replace(id, cmd, Actor.MANUAL)));
    }

    @PostMapping("/{variantId}/restore")
    @RequiresPermission(resource = "PURCHASE_POSITIONS", operation = "UPDATE")
    public ResponseEntity<PurchasePositionDtoModel> restore(@PathVariable Long id, @PathVariable Long variantId) {
        return ResponseEntity.ok(positionMapper.toUpdateResponse(service.restore(id, variantId, Actor.MANUAL)));
    }

    private PositionVariantDto toDto(VariantView v) {
        return new PositionVariantDto(v.id(), v.positionId(), v.materialId(), v.model(), v.producer(), v.seller(),
                v.link(), imageStorage.toCdnUrl(v.photo()), v.unitSalePrice(), v.unitPlannedPurchasePrice(),
                v.reason(), v.note(), v.retiredAt(), v.retiredBy());
    }

    private VariantSuggestionDto toDto(SuggestionView s) {
        return new VariantSuggestionDto(s.materialId(), s.model(), s.producer(), s.seller(), s.link(),
                imageStorage.toCdnUrl(s.photo()), s.salePrice(), s.lastKnownAt());
    }
}
