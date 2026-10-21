package com.foremen.service.model.mapper;

import java.util.Set;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.WorkerPayoutServiceExtendedModel;
import com.foremen.service.model.WorkerPayoutServiceModel;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-11 (design §Components) — service mapper for {@link WorkerPayoutEntity}, following the
 * {@code WorkReportServiceMapper} FK-resolution pattern: an <b>abstract class</b> holding an
 * injected {@link EntityManager} so the flat {@code projectId}/{@code workerId} become managed
 * references via {@code getReference(...)} without a SELECT.
 *
 * <p>This mapper completes the generic {@code AdminService} CRUD contract {@code WorkerPayoutService}
 * implements; the payout lifecycle itself is owned by the service's dedicated
 * {@code create(CreatePayoutRequest)} / {@code markPaid} / {@code delete} endpoints (which build the
 * entity directly, deriving {@code isAdvance}, snapshotting {@code suggestedAmount}, and applying
 * credit), not by this field-by-field mapping. Accordingly the <b>write path</b> sets only the
 * {@code project} / {@code worker} references and the author-supplied scalars and {@code ignore}s
 * every server-derived field ({@code suggestedAmount}, {@code advanceApplied}, {@code status},
 * {@code isAdvance}, {@code paidAt}) plus the {@code BaseEntity} audit columns. The <b>read path</b>
 * flattens the FK ids and resolves the worker's localized name ({@code ru → pl → code}, R8.1).
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class WorkerPayoutServiceMapper
        implements ServiceToDaoMapper<WorkerPayoutEntity, WorkerPayoutServiceModel,
        WorkerPayoutServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected ProjectEntity projectRef(Long id) {
        return id == null ? null : entityManager.getReference(ProjectEntity.class, id);
    }

    protected UserEntity userRef(Long id) {
        return id == null ? null : entityManager.getReference(UserEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    // --- Write path ------------------------------------------------------------------------------

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", expression = "java(projectRef(source.getProjectId()))")
    @Mapping(target = "worker", expression = "java(userRef(source.getWorkerId()))")
    @Mapping(target = "suggestedAmount", ignore = true)
    @Mapping(target = "advanceApplied", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "advance", ignore = true)
    @Mapping(target = "paidAt", ignore = true)
    public abstract WorkerPayoutEntity toCreateDaoModel(WorkerPayoutServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "worker", ignore = true)
    @Mapping(target = "suggestedAmount", ignore = true)
    @Mapping(target = "advanceApplied", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "advance", ignore = true)
    @Mapping(target = "paidAt", ignore = true)
    public abstract void updateFields(WorkerPayoutServiceExtendedModel source,
                                      @MappingTarget WorkerPayoutEntity target);

    // --- Read path -------------------------------------------------------------------------------

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "workerId", source = "worker.id")
    @Mapping(target = "workerName", ignore = true)
    public abstract WorkerPayoutServiceModel toServiceModel(WorkerPayoutEntity source);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "workerId", source = "worker.id")
    @Mapping(target = "workerName", ignore = true)
    public abstract WorkerPayoutServiceExtendedModel toServiceExtendedModel(WorkerPayoutEntity source);

    /** Resolve the worker's localized display name (ru → pl → code) for the list projection (R8.1). */
    @AfterMapping
    protected void resolveReadModel(@MappingTarget WorkerPayoutServiceModel target,
                                    WorkerPayoutEntity source) {
        if (source.getWorker() != null) {
            target.setWorkerName(source.getWorker().getName());
        }
    }

    /** Resolve the worker's localized display name (ru → pl → code) for the single-read projection (R8.1). */
    @AfterMapping
    protected void resolveReadModel(@MappingTarget WorkerPayoutServiceExtendedModel target,
                                    WorkerPayoutEntity source) {
        if (source.getWorker() != null) {
            target.setWorkerName(source.getWorker().getName());
        }
    }
}
