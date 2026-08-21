package com.foremen.controller;

import com.foremen.dao.model.ResourceEntity;
import com.foremen.service.ReadOnlyAdminService;
import com.foremen.service.ResourceService;
import com.foremen.service.model.ResourceServiceExtendedModel;
import com.foremen.service.model.ResourceServiceModel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resources")
@RequiredArgsConstructor
public class ResourceController implements AdminReadOnlyController<
        ResourceServiceModel, ResourceServiceExtendedModel, ResourceEntity, Long> {

    private final ResourceService resourceService;

    @Override
    public ReadOnlyAdminService<ResourceServiceModel, ResourceServiceExtendedModel, ResourceEntity, Long> getService() {
        return resourceService;
    }
}
