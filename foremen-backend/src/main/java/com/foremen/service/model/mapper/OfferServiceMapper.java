package com.foremen.service.model.mapper;

import java.util.Collections;
import java.util.Set;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.OfferServiceExtendedModel;
import com.foremen.service.model.OfferServiceModel;

import jakarta.persistence.EntityManager;

/**
 * Service mapper for {@link OfferEntity} (FOR-05-07, Requirement 1), following the FOR-05-03
 * {@code EstimateServiceMapper} FK-resolution pattern: an <b>abstract class</b> holding an injected
 * {@link EntityManager} so the flat {@code projectId}/{@code estimateId}/{@code selectedPackageId}
 * become managed references via {@code getReference(...)} without a SELECT.
 *
 * <p>The offer has no own i18n {@code name}, so {@link #getI18nSupportedProperties()} returns an
 * empty set. The read model resolves the {@code selectedPackageCode} in an {@code @AfterMapping}.
 *
 * <p><b>Derived columns are ignored inbound (R1.3, R19.4):</b> {@code totalNet}/{@code totalVat}/
 * {@code totalGross} are recomputed by {@code OfferTotalsCalculator} from the live-referenced
 * estimate, so {@link #toCreateDaoModel} and {@link #updateFields} never copy them onto the entity.
 * The offer lifecycle ({@code status}/{@code revision}/{@code approvedRevision}) is driven through
 * {@code OfferService}'s dedicated methods; the CRUD mapping merely round-trips them.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class OfferServiceMapper
        implements ServiceToDaoMapper<OfferEntity, OfferServiceModel, OfferServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected ProjectEntity projectRef(Long id) {
        return id == null ? null : entityManager.getReference(ProjectEntity.class, id);
    }

    protected EstimateEntity estimateRef(Long id) {
        return id == null ? null : entityManager.getReference(EstimateEntity.class, id);
    }

    protected OfferPackageEntity offerPackageRef(Long id) {
        return id == null ? null : entityManager.getReference(OfferPackageEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", expression = "java(projectRef(source.getProjectId()))")
    @Mapping(target = "estimate", expression = "java(estimateRef(source.getEstimateId()))")
    @Mapping(target = "selectedPackage", expression = "java(offerPackageRef(source.getSelectedPackageId()))")
    @Mapping(target = "totalNet", ignore = true)
    @Mapping(target = "totalVat", ignore = true)
    @Mapping(target = "totalGross", ignore = true)
    @Mapping(target = "discounts", ignore = true)
    @Mapping(target = "negotiationRounds", ignore = true)
    public abstract OfferEntity toCreateDaoModel(OfferServiceExtendedModel source);

    /**
     * Update-path mapping: {@code project} and {@code estimate} are deliberately left untouched (no
     * {@code @Mapping} for them at all), mirroring {@code EstimateServiceMapper#updateFields} — the
     * owning project and referenced estimate are fixed at create time (R1.1), so re-deriving them
     * from a payload that never populates their ids would null out the existing FKs on every update.
     */
    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "selectedPackage", expression = "java(offerPackageRef(source.getSelectedPackageId()))")
    @Mapping(target = "totalNet", ignore = true)
    @Mapping(target = "totalVat", ignore = true)
    @Mapping(target = "totalGross", ignore = true)
    @Mapping(target = "discounts", ignore = true)
    @Mapping(target = "negotiationRounds", ignore = true)
    public abstract void updateFields(OfferServiceExtendedModel source, @MappingTarget OfferEntity target);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "estimateId", source = "estimate.id")
    @Mapping(target = "selectedPackageId", source = "selectedPackage.id")
    @Mapping(target = "selectedPackageCode", ignore = true)
    public abstract OfferServiceModel toServiceModel(OfferEntity source);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "estimateId", source = "estimate.id")
    @Mapping(target = "selectedPackageId", source = "selectedPackage.id")
    public abstract OfferServiceExtendedModel toServiceExtendedModel(OfferEntity source);

    /** Resolves the selected package's code onto the read model (offer-level fact only). */
    @AfterMapping
    protected void resolveReferencedNames(@MappingTarget OfferServiceModel target, OfferEntity source) {
        OfferPackageEntity selectedPackage = source.getSelectedPackage();
        if (selectedPackage != null) {
            target.setSelectedPackageCode(selectedPackage.getCode());
        }
    }
}
