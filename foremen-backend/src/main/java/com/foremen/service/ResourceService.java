package com.foremen.service;

import com.foremen.dao.ResourceDao;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.service.model.ResourceServiceExtendedModel;
import com.foremen.service.model.ResourceServiceModel;
import com.foremen.service.model.mapper.ResourceServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class ResourceService implements ReadOnlyAdminService<
        ResourceServiceModel, ResourceServiceExtendedModel, ResourceEntity, Long> {

    private final ResourceDao readDao;
    private final ResourceServiceMapper mapper;
    private final EntityManager entityManager;
    private final Class<ResourceEntity> daoModelClass = ResourceEntity.class;
}
