package com.foremen.service.model.mapper;

import java.util.Collections;
import java.util.Set;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.PaymentScheduleServiceExtendedModel;
import com.foremen.service.model.PaymentScheduleServiceModel;

import jakarta.persistence.EntityManager;

/**
 * Service mapper for {@link PaymentScheduleEntity} (FOR-06-07, Requirement 1), following the
 * FOR-05-10 {@code ProjectScheduleServiceMapper} FK-resolution pattern: an <b>abstract class</b>
 * holding an injected {@link EntityManager} so the flat {@code projectId} becomes a managed
 * {@code ProjectEntity} reference via {@code getReference(...)} without a SELECT.
 *
 * <p>The schedule has no own i18n {@code name}, so {@link #getI18nSupportedProperties()} returns an
 * empty set. The business columns — {@code currency}, {@code basisGross}, {@code source},
 * {@code status}, {@code unallocatedCredit} — and the owned {@code tranches} collection are driven by
 * {@code PaymentScheduleService}'s dedicated lifecycle methods (create seeds them from the project's
 * APPROVED offer; generate/publish/re-plan own them thereafter), so the generic CRUD mapping here
 * never writes them — it only round-trips the flat {@code projectId} scalar. The create path leaves
 * the remaining columns at their entity defaults; the service overwrites them transactionally.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class PaymentScheduleServiceMapper
        implements ServiceToDaoMapper<PaymentScheduleEntity,
                PaymentScheduleServiceModel, PaymentScheduleServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected ProjectEntity projectRef(Long id) {
        return id == null ? null : entityManager.getReference(ProjectEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", expression = "java(projectRef(source.getProjectId()))")
    @Mapping(target = "currency", ignore = true)
    @Mapping(target = "basisGross", ignore = true)
    @Mapping(target = "source", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "unallocatedCredit", ignore = true)
    @Mapping(target = "tranches", ignore = true)
    public abstract PaymentScheduleEntity toCreateDaoModel(PaymentScheduleServiceExtendedModel source);

    /**
     * Update-path mapping: {@code project} and every business column / the owned {@code tranches}
     * collection are deliberately left untouched (the project FK is fixed at create time; the money
     * columns and tranches are driven by the service's lifecycle methods), so the generic update only
     * round-trips the flat scalar.
     */
    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "currency", ignore = true)
    @Mapping(target = "basisGross", ignore = true)
    @Mapping(target = "source", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "unallocatedCredit", ignore = true)
    @Mapping(target = "tranches", ignore = true)
    public abstract void updateFields(PaymentScheduleServiceExtendedModel source,
                                      @MappingTarget PaymentScheduleEntity target);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    public abstract PaymentScheduleServiceModel toServiceModel(PaymentScheduleEntity source);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    public abstract PaymentScheduleServiceExtendedModel toServiceExtendedModel(PaymentScheduleEntity source);
}
