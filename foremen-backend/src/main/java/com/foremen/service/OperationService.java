package com.foremen.service;

import com.foremen.dao.OperationDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.service.model.OperationServiceExtendedModel;
import com.foremen.service.model.OperationServiceModel;
import com.foremen.service.model.mapper.OperationServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class OperationService implements ReadOnlyAdminService<
        OperationServiceModel, OperationServiceExtendedModel, OperationEntity, Long> {

    private final OperationDao readDao;
    private final OperationServiceMapper mapper;
    private final EntityManager entityManager;
    private final Class<OperationEntity> daoModelClass = OperationEntity.class;
}
