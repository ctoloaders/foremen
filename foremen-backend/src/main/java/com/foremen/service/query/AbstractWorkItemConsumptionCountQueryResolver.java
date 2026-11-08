package com.foremen.service.query;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.exception.ForemenApiException;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Base {@link CustomQueryResolver} for the synthetic per-branch consumption-count segments on
 * {@link WorkItemEntity} (FOR-04-19-work-catalog-rework, Requirements 2.5, 2.6, 7.3, 7.4).
 *
 * <p>{@link CustomQueryResolverRegistry} keys resolvers by {@code (entity, property())} and
 * {@code property()} returns a single segment, so one component cannot own two segments; each segment
 * is therefore a small {@code @Component} subclass of this base.
 *
 * <p>The filter is a three-state has / has-none test, translated to an {@code EXISTS} subquery over
 * {@code work_material_consumptions} (no JOIN, so paging totals and DISTINCT rows are preserved):
 * <ul>
 *   <li>{@code >0}, {@code >=1}, {@code !=0} (and RSQL aliases) &rarr; has at least one norm</li>
 *   <li>{@code ==0}, {@code <1}, {@code <=0} (and RSQL aliases) &rarr; has none ({@code NOT EXISTS})</li>
 *   <li>any other operator/value &rarr; 400 {@code error.query.invalid.field.path}</li>
 * </ul>
 * A non-integer value is a 400 {@code error.query.invalid.field}. Sorting is not supported.
 */
public abstract class AbstractWorkItemConsumptionCountQueryResolver
        implements CustomQueryResolver<WorkItemEntity> {

    private final CustomQueryResolverRegistry registry;
    private final String segment;
    private final ConsumptionBranch branch;

    protected AbstractWorkItemConsumptionCountQueryResolver(CustomQueryResolverRegistry registry,
                                                            String segment,
                                                            ConsumptionBranch branch) {
        this.registry = registry;
        this.segment = segment;
        this.branch = branch;
    }

    @PostConstruct
    void register() {
        registry.register(WorkItemEntity.class, this);
    }

    @Override
    public String property() {
        return segment;
    }

    @Override
    public Specification<WorkItemEntity> toFilter(QueryToken.Filter filter) {
        if (!segment.equals(filter.field())) {
            throw invalidPath(filter);
        }
        long value = parseInteger(filter);
        boolean has = resolveHas(filter, value);

        return (root, query, cb) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<WorkMaterialConsumptionEntity> c = subquery.from(WorkMaterialConsumptionEntity.class);
            subquery.select(cb.literal(1L));
            subquery.where(cb.and(
                    cb.equal(c.get("workItem").get("id"), root.get("id")),
                    cb.equal(c.get("branch"), branch)));
            Predicate exists = cb.exists(subquery);
            return has ? exists : cb.not(exists);
        };
    }

    /** Maps (operator, value) to has ({@code true}) / has-none ({@code false}), or rejects with 400. */
    private boolean resolveHas(QueryToken.Filter filter, long value) {
        QueryOperator op = filter.operator();
        switch (op) {
            case GREATER_THAN, GT -> {
                if (value == 0) {
                    return true;
                }
            }
            case GREATER_THAN_OR_EQUAL, GTE -> {
                if (value == 1) {
                    return true;
                }
            }
            case NOT_EQUALS -> {
                if (value == 0) {
                    return true;
                }
            }
            case EQUALS -> {
                if (value == 0) {
                    return false;
                }
            }
            case LESS_THAN, LT -> {
                if (value == 1) {
                    return false;
                }
            }
            case LESS_THAN_OR_EQUAL, LTE -> {
                if (value == 0) {
                    return false;
                }
            }
            default -> {
                // fall through to rejection
            }
        }
        throw invalidPath(filter);
    }

    private long parseInteger(QueryToken.Filter filter) {
        String raw = filter.value();
        try {
            return Long.parseLong(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                    "error.query.invalid.field", filter.field());
        }
    }

    private static ForemenApiException invalidPath(QueryToken.Filter filter) {
        return new ForemenApiException(HttpStatus.BAD_REQUEST,
                "error.query.invalid.field.path", filter.field());
    }

    @Override
    public Specification<WorkItemEntity> toSort(Sort.Order order) {
        // The counts are not sortable (Requirement 2.6): sorting would need a join/aggregate.
        throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                "error.query.invalid.field", order.getProperty());
    }
}
