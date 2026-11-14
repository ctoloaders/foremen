package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.MaterialSellerControllerMapper;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.MaterialSellerService;
import com.foremen.service.model.MaterialSellerServiceExtendedModel;
import com.foremen.service.model.MaterialSellerServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.SellerLookupService;
import com.foremen.service.nip.NipLookupResult;
import com.foremen.service.SellerQuickService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/material-sellers")
@RequiredArgsConstructor
@PermissionResource("MATERIAL_SELLERS")
public class MaterialSellerController implements AdminController<
        MaterialSellerServiceModel,
        MaterialSellerServiceExtendedModel,
        MaterialSellerDtoModel,
        MaterialSellerDtoExtendedModel,
        MaterialSellerEntity,
        Long,
        MaterialSellerCreateRequest,
        MaterialSellerCreateResponse,
        MaterialSellerUpdateRequest,
        MaterialSellerUpdateResponse> {

    private final MaterialSellerService service;
    private final MaterialSellerControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;
    private final SellerLookupService sellerLookupService;
    private final SellerQuickService sellerQuickService;

    @Override
    public ControllerToServiceMapper<MaterialSellerServiceModel, MaterialSellerServiceExtendedModel,
            MaterialSellerDtoModel, MaterialSellerDtoExtendedModel,
            MaterialSellerCreateRequest, MaterialSellerCreateResponse,
            MaterialSellerUpdateRequest, MaterialSellerUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<MaterialSellerServiceModel, MaterialSellerServiceExtendedModel,
            MaterialSellerEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /** FOR-11-00 (R20): inline seller creation; 201 when created, 200 when an existing NIP matched. */
    @PostMapping("/quick")
    @RequiresPermission(resource = "MATERIAL_SELLERS", operation = "CREATE")
    public ResponseEntity<SellerQuickResponse> quickCreate(@Valid @RequestBody SellerQuickRequest request) {
        SellerQuickService.QuickResult r = sellerQuickService.findOrCreate(request.name(), request.nip());
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new SellerQuickResponse(controllerMapper.toDto(r.seller()), r.created()));
    }

    /** FOR-11-00 (R5): dictionary-first NIP lookup; literal path wins over {@code /{id}}. */
    @GetMapping("/lookup")
    @RequiresPermission(resource = "MATERIAL_SELLERS", operation = "READ")
    public SellerLookupResponse lookup(@RequestParam("nip") String nip) {
        SellerLookupService.SellerLookupResult r = sellerLookupService.lookup(nip);
        NipLookupResult c = r.candidate();
        return new SellerLookupResponse(
                r.nip(),
                SellerLookupResponse.Source.valueOf(r.source().name()),
                r.seller() == null ? null : controllerMapper.toDto(r.seller()),
                c == null ? null : new SellerLookupResponse.RegistryCandidate(
                        c.nip(), c.name(), c.address(), c.regon(), c.krs(),
                        c.registry() == null ? null : c.registry().name()),
                r.registryAvailable());
    }
}
