package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.PackagePriceUpsert;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPackagePriceEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceModel;
import com.foremen.service.model.WorkPriceServiceModel.PackagePrice;
import com.foremen.service.pricing.EffectivePriceResolver;
import com.foremen.service.pricing.SeededOfferPackages;
import com.foremen.service.pricing.SeededOfferPackages.OfferPackageInfo;
import jakarta.persistence.EntityManager;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Service mapper for the package-based {@link WorkPriceEntity} aggregator (FOR-04-12b).
 *
 * <p>An <b>abstract class</b> (not an interface) so it can hold injected collaborators: an
 * {@link EntityManager} (to turn flat ids into managed references via {@code getReference(...)} without
 * a SELECT), the {@link SeededOfferPackages} provider (to iterate the seeded packages when building the
 * row DTO prices map), and the {@link EffectivePriceResolver} (the pure max-fallback rule).
 *
 * <p>Reads. {@code toServiceModel} maps {@code workItemId} directly; the referenced work item's
 * localized display name ({@code workItemName}) is resolved in {@link #resolveReferencedNames} using the
 * request-locale rule (RU when the request locale language is {@code ru}, PL otherwise). The row DTO's
 * code-keyed {@code prices} map is assembled in {@link #buildPricesMap}: for each seeded offer package it
 * calls {@link EffectivePriceResolver#resolve(java.util.Collection, String)} over the aggregator's
 * {@code packagePrices} collection and, when a price is present, puts a {@link PackagePrice} carrying the
 * package id, the currency code of the member that supplied the effective price, and the net price.
 * Unpriced packages omit their key.
 *
 * <p>Writes. {@code toCreateDaoModel} / {@code updateFields} turn the write model's
 * {@code (workItemId, packagePrices)} into the aggregator plus its owned {@link WorkPackagePriceEntity}
 * collection. The {@code workItem} reference is set by an {@code expression} mapping; the collection is
 * (re)built in {@link #buildPackagePrices} from the {@link PackagePriceUpsert} list — clearing the
 * existing collection in place (so JPA {@code orphanRemoval} deletes removed rows) and setting each
 * child's {@code workPrice} back-reference.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class WorkPriceServiceMapper
        implements ServiceToDaoMapper<WorkPriceEntity, WorkPriceServiceModel, WorkPriceServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    @Autowired
    protected SeededOfferPackages seededOfferPackages;

    @Autowired
    protected EffectivePriceResolver effectivePriceResolver;

    protected WorkItemEntity workItemRef(Long id) {
        return id == null ? null : entityManager.getReference(WorkItemEntity.class, id);
    }

    protected CurrencyEntity currencyRef(Long id) {
        return id == null ? null : entityManager.getReference(CurrencyEntity.class, id);
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
    @Mapping(target = "workItem", expression = "java(workItemRef(source.workItemId()))")
    @Mapping(target = "packagePrices", ignore = true)
    public abstract WorkPriceEntity toCreateDaoModel(WorkPriceServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workItem", expression = "java(workItemRef(source.workItemId()))")
    @Mapping(target = "packagePrices", ignore = true)
    public abstract void updateFields(WorkPriceServiceExtendedModel source, @MappingTarget WorkPriceEntity target);

    @Override
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "prices", ignore = true)
    public abstract WorkPriceServiceModel toServiceModel(WorkPriceEntity source);

    @Override
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "packagePrices", expression = "java(toPackagePriceUpserts(source.getPackagePrices()))")
    public abstract WorkPriceServiceExtendedModel toServiceExtendedModel(WorkPriceEntity source);

    /**
     * Builds the extended read model's {@code packagePrices} list from the aggregator's owned
     * {@link WorkPackagePriceEntity} collection: one {@link PackagePriceUpsert} per member carrying the
     * member's {@code offerPackage.id}, {@code currency.id}, and {@code netPrice}. Because
     * {@link WorkPriceServiceExtendedModel} is an immutable record, this list is built up front and
     * passed into the record constructor via an {@code expression} mapping (an {@code @AfterMapping} with
     * {@code @MappingTarget} cannot mutate a record). A {@code null}/empty collection yields an empty
     * list.
     */
    protected List<PackagePriceUpsert> toPackagePriceUpserts(List<WorkPackagePriceEntity> members) {
        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }
        List<PackagePriceUpsert> upserts = new ArrayList<>(members.size());
        for (WorkPackagePriceEntity member : members) {
            if (member == null) {
                continue;
            }
            Long offerPackageId = member.getOfferPackage() != null ? member.getOfferPackage().getId() : null;
            Long currencyId = member.getCurrency() != null ? member.getCurrency().getId() : null;
            upserts.add(new PackagePriceUpsert(offerPackageId, currencyId, member.getNetPrice()));
        }
        return upserts;
    }

    /**
     * Resolves the referenced work item's localized display name into {@code workItemName} using the
     * same per-request locale rule the framework uses for {@code name} (RU when the request locale
     * language is {@code ru}, PL otherwise).
     */
    @AfterMapping
    protected void resolveReferencedNames(@MappingTarget WorkPriceServiceModel target, WorkPriceEntity source) {
        WorkItemEntity item = source.getWorkItem();
        if (item != null) {
            target.setWorkItemName(isRussianLocale() ? item.getNameRU() : item.getNamePL());
        }
    }

    /**
     * Fills the row DTO's code-keyed {@code prices} map. Iterates every seeded offer package and, for
     * each, resolves the effective price over the aggregator's {@code packagePrices} collection via the
     * {@link EffectivePriceResolver}. When a price is present, puts a {@link PackagePrice} carrying the
     * package id, the currency code of the member that supplied the effective price (the exact member,
     * or the max member on fallback), and the net price. Packages that resolve to no price omit their
     * key (an unpriced work item yields an empty map).
     */
    @AfterMapping
    protected void buildPricesMap(@MappingTarget WorkPriceServiceModel target, WorkPriceEntity source) {
        List<WorkPackagePriceEntity> members = source.getPackagePrices();
        if (members == null || members.isEmpty()) {
            return;
        }
        for (OfferPackageInfo pkg : seededOfferPackages.all()) {
            Optional<BigDecimal> effective = effectivePriceResolver.resolve(members, pkg.code());
            if (effective.isEmpty()) {
                continue;
            }
            BigDecimal netPrice = effective.get();
            String currencyCode = currencyCodeFor(members, pkg.code(), netPrice);
            target.getPrices().put(pkg.code(), new PackagePrice(pkg.id(), currencyCode, netPrice));
        }
    }

    /**
     * Returns the currency code of the member that supplied the effective price: the member for
     * {@code packageCode} when one exists, otherwise a member whose {@code netPrice} equals the resolved
     * (max-fallback) price. Re-finds the member rather than extending the pure resolver.
     */
    private String currencyCodeFor(List<WorkPackagePriceEntity> members, String packageCode, BigDecimal netPrice) {
        WorkPackagePriceEntity exact = null;
        WorkPackagePriceEntity byPrice = null;
        for (WorkPackagePriceEntity member : members) {
            if (member == null) {
                continue;
            }
            OfferPackageEntity offerPackage = member.getOfferPackage();
            if (exact == null && offerPackage != null && packageCode != null
                    && packageCode.equals(offerPackage.getCode())) {
                exact = member;
            }
            if (byPrice == null && member.getNetPrice() != null && member.getNetPrice().compareTo(netPrice) == 0) {
                byPrice = member;
            }
        }
        WorkPackagePriceEntity chosen = exact != null ? exact : byPrice;
        if (chosen == null || chosen.getCurrency() == null) {
            return null;
        }
        return chosen.getCurrency().getCode();
    }

    /**
     * Reconciles the aggregator's owned {@code packagePrices} collection with the write model's upsert
     * list <b>in place</b>, keyed by {@code offerPackageId}. Members whose offer package is still present
     * are <b>updated</b> (currency + net price) rather than deleted and re-inserted; members for offer
     * packages no longer present are removed (JPA {@code orphanRemoval} deletes their rows); upserts for
     * offer packages not yet present are appended as new {@link WorkPackagePriceEntity} rows with managed
     * {@code offerPackage} / {@code currency} references and the {@code workPrice} back-reference set to
     * the aggregator.
     *
     * <p>Reconciling in place (rather than clear-and-recreate) is required because the collection has a
     * unique constraint on {@code (work_price_id, offer_package_id)} and id generation is
     * {@code IDENTITY}: a clear-and-recreate would flush the INSERT of the replacement row for an
     * unchanged offer package before the {@code orphanRemoval} DELETE of the old row, transiently
     * violating the unique constraint (surfacing as a 409). Updating the existing row in place avoids the
     * conflicting INSERT entirely.
     */
    @AfterMapping
    protected void buildPackagePrices(@MappingTarget WorkPriceEntity target, WorkPriceServiceExtendedModel source) {
        List<WorkPackagePriceEntity> collection = target.getPackagePrices();
        List<PackagePriceUpsert> upserts = source.packagePrices();
        if (upserts == null) {
            collection.clear();
            return;
        }
        // Retain and update members whose offer package is still requested; collect the requested ids.
        Set<Long> requestedPackageIds = new HashSet<>();
        for (PackagePriceUpsert upsert : upserts) {
            if (upsert != null && upsert.offerPackageId() != null) {
                requestedPackageIds.add(upsert.offerPackageId());
            }
        }
        // Remove members whose offer package is no longer requested (orphanRemoval deletes their rows).
        collection.removeIf(member -> {
            Long existingId = member != null && member.getOfferPackage() != null
                    ? member.getOfferPackage().getId() : null;
            return existingId == null || !requestedPackageIds.contains(existingId);
        });
        for (PackagePriceUpsert upsert : upserts) {
            if (upsert == null) {
                continue;
            }
            WorkPackagePriceEntity member = findByOfferPackageId(collection, upsert.offerPackageId());
            if (member == null) {
                member = new WorkPackagePriceEntity();
                member.setWorkPrice(target);
                member.setOfferPackage(offerPackageRef(upsert.offerPackageId()));
                collection.add(member);
            }
            member.setCurrency(currencyRef(upsert.currencyId()));
            member.setNetPrice(upsert.netPrice());
        }
    }

    private static WorkPackagePriceEntity findByOfferPackageId(List<WorkPackagePriceEntity> collection, Long offerPackageId) {
        if (offerPackageId == null) {
            return null;
        }
        for (WorkPackagePriceEntity member : collection) {
            if (member != null && member.getOfferPackage() != null
                    && offerPackageId.equals(member.getOfferPackage().getId())) {
                return member;
            }
        }
        return null;
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
