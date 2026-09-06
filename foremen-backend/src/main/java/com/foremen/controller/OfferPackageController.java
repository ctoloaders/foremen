package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.OfferPackageControllerMapper;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.OfferPackageService;
import com.foremen.service.model.OfferPackageServiceExtendedModel;
import com.foremen.service.model.OfferPackageServiceModel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/offer-packages")
@RequiredArgsConstructor
@PermissionResource("OFFER_PACKAGES")
public class OfferPackageController implements AdminController<
        OfferPackageServiceModel,
        OfferPackageServiceExtendedModel,
        OfferPackageDtoModel,
        OfferPackageDtoExtendedModel,
        OfferPackageEntity,
        Long,
        OfferPackageCreateRequest,
        OfferPackageCreateResponse,
        OfferPackageUpdateRequest,
        OfferPackageUpdateResponse> {

    private final OfferPackageService service;
    private final OfferPackageControllerMapper controllerMapper;

    @Override
    public ControllerToServiceMapper<OfferPackageServiceModel, OfferPackageServiceExtendedModel,
            OfferPackageDtoModel, OfferPackageDtoExtendedModel,
            OfferPackageCreateRequest, OfferPackageCreateResponse,
            OfferPackageUpdateRequest, OfferPackageUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<OfferPackageServiceModel, OfferPackageServiceExtendedModel,
            OfferPackageEntity, Long> getService() {
        return service;
    }
}
