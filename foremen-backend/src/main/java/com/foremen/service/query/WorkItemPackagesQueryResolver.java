package com.foremen.service.query;

import java.util.Arrays;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.exception.ForemenApiException;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * {@link CustomQueryResolver} for the synthetic {@code packages} query segment on
 * {@link WorkItemEntity}, backing the work-catalog list's package filter
 * ({@code packages.id==<id>} / {@code packages.id~in~<id1>,<id2>}).
 *
 * <p>A work item "belongs to" an offer package when a
 * {@code WorkPackageOverride(workItem, offerPackage, member=true)} row exists. That membership is NOT
 * a plain JPA {@code @ManyToMany} (the {@code member} flag rules it out), so it cannot be expressed as
 * a straightforward association JOIN and the default {@link SpecificationBuilder} path resolution
 * cannot reach it. This resolver therefore owns the {@code packages} leading segment and translates
 * {@code packages.id <op> <value>} into an {@code EXISTS} subquery over the membership rows:
 *
 * <pre>{@code
 *   WHERE EXISTS (SELECT 1 FROM work_package_overrides o
 *                 WHERE o.work_item_id = wi.id AND o.member = true AND o.offer_package_id <op> <value>)
 * }</pre>
 *
 * <p>Using {@code EXISTS} (rather than a JOIN) keeps the paginated list over DISTINCT work-item rows:
 * a work item that is a member of several selected packages still matches exactly once, so the
 * membership filter never multiplies rows. Only {@code packages.id} is supported; {@code EQUALS}
 * matches a single package, {@code IN} matches any of a comma-separated set (single and multiple
 * selection both work), and {@code NOT_EQUALS}/{@code NOT_IN} negate the membership. The resolver
 * self-registers for {@code (WorkItemEntity, "packages")} at startup.
 */
@Component
public class WorkItemPackagesQueryResolver implements CustomQueryResolver<WorkItemEntity> {

    /** The synthetic leading segment this resolver owns on {@link WorkItemEntity}. */
    static final String SEGMENT = "packages";

    private final CustomQueryResolverRegistry registry;

    public WorkItemPackagesQueryResolver(CustomQueryResolverRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        registry.register(WorkItemEntity.class, this);
    }

    @Override
    public String property() {
        return SEGMENT;
    }

    @Override
    public Specification<WorkItemEntity> toFilter(QueryToken.Filter filter) {
        // Only `packages.id` is a supported filter path. Anything else (e.g. `packages.code`,
        // or a bare `packages`) is rejected with the same BAD_REQUEST contract the default path
        // resolution uses for an invalid field path.
        if (!"packages.id".equals(filter.field())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                    "error.query.invalid.field.path", filter.field());
        }

        List<Long> packageIds = parsePackageIds(filter);
        boolean negated = filter.operator() == QueryOperator.NOT_EQUALS
                || filter.operator() == QueryOperator.NOT_IN;

        return (root, query, cb) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<WorkPackageOverrideEntity> override = subquery.from(WorkPackageOverrideEntity.class);
            subquery.select(cb.literal(1L));

            Predicate sameWork = cb.equal(override.get("workItem").get("id"), root.get("id"));
            Predicate isMember = cb.isTrue(override.get("member"));
            Predicate inPackages = override.get("offerPackage").get("id").in(packageIds);
            subquery.where(cb.and(sameWork, isMember, inPackages));

            Predicate exists = cb.exists(subquery);
            return negated ? cb.not(exists) : exists;
        };
    }

    /**
     * Parses the filter value into the target package ids. {@code EQUALS}/{@code NOT_EQUALS} carry a
     * single id; {@code IN}/{@code NOT_IN} carry a comma-separated set (the same
     * {@code packages.id~in~1,2} grammar the generic DSL uses for real associations). A non-numeric or
     * empty value is rejected with a BAD_REQUEST, matching the generic value-coercion contract.
     */
    private List<Long> parsePackageIds(QueryToken.Filter filter) {
        String value = filter.value();
        return switch (filter.operator()) {
            case EQUALS, NOT_EQUALS -> List.of(parseId(value));
            case IN, NOT_IN -> {
                List<Long> ids = Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(this::parseId)
                        .toList();
                if (ids.isEmpty()) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                            "error.query.invalid.field", filter.field());
                }
                yield ids;
            }
            default -> throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                    "error.query.invalid.field", filter.field());
        };
    }

    private Long parseId(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                    "error.query.invalid.field", "packages.id");
        }
    }

    @Override
    public Specification<WorkItemEntity> toSort(Sort.Order order) {
        // Sorting a work item by its package membership set is not meaningful (a work can belong to
        // many packages), and the work-catalog list never requests it. Reject rather than silently
        // ignore, so a bad sort surfaces as a BAD_REQUEST.
        throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                "error.query.invalid.field", order.getProperty());
    }
}
