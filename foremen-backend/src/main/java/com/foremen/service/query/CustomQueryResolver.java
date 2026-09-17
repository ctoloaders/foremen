package com.foremen.service.query;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Resolves a <em>synthetic</em> (non-column) leading query segment into a JPA {@link Specification}
 * for both filtering and sorting, replacing the default single-column path resolution of
 * {@link SpecificationBuilder} for that segment.
 *
 * <p>Some entities expose a query path whose first segment does not map to a plain persistent column
 * or a straightforward association navigation — for example the work-prices catalog exposes a
 * {@code prices.{packageId}.{field}} pivot key that must JOIN {@code WorkPrice} onto its
 * {@code packagePrices} collection, discriminate the join by {@code offerPackage.id == {packageId}},
 * and then apply {@code {field} <op> {value}}. A path like this cannot be expressed as a single
 * column path, so when the query grammar sees a leading segment owned by a resolver it branches into
 * the resolver instead of {@code SpecificationBuilder}'s default path resolution
 * (FOR-04-12b design, "Custom_Predicate_Flow").
 *
 * <p>A resolver owns <b>both</b> the filter and the sort capability for its segment so the two paths
 * cannot drift: implementations are expected to route {@link #toFilter(QueryToken.Filter)} and
 * {@link #toSort(Sort.Order)} through one shared parse/validate/join/discriminator helper. Filter and
 * sort therefore build on the identical join, the identical discriminator, and identical
 * id/field validation (and thus identical error reporting).
 *
 * <p>A resolver is looked up by the {@code (entityClass, leadingSegment)} pair via
 * {@link CustomQueryResolverRegistry}; {@link #property()} returns the leading segment it owns.
 * The filter half is consulted by {@link SpecificationBuilder}; the sort half is consulted by the
 * concrete service that overrides its read to wire synthetic sort orders — both resolve to the same
 * registered instance.
 *
 * @param <T> the entity type this resolver produces specifications for
 */
public interface CustomQueryResolver<T> {

    /**
     * The leading query-path segment this resolver owns for its entity (e.g. {@code "prices"}).
     * A query path whose first dot-separated segment equals this value is routed through this
     * resolver instead of the default column-path resolution.
     */
    String property();

    /**
     * Builds the compound predicate for a parsed synthetic filter token whose
     * {@link QueryToken.Filter#field() field} starts with {@link #property()} (e.g.
     * {@code prices.5.netPrice > 10} → {@code offerPackage.id == 5 AND netPrice > 10} over the joined
     * collection). Implementations parse and validate the remaining segments, JOIN the backing
     * collection, and AND the value predicate onto the discriminator.
     *
     * @param filter the parsed filter token carrying the synthetic {@code field}, operator, and value
     * @return a specification that restricts the query root to matching rows
     */
    Specification<T> toFilter(QueryToken.Filter filter);

    /**
     * Builds the sort contribution for a parsed synthetic {@link Sort.Order} whose
     * {@link Sort.Order#getProperty() property} starts with {@link #property()} (e.g.
     * {@code prices.5.netPrice,asc}). Implementations parse and validate the remaining segments, JOIN
     * the backing collection, restrict it to the requested discriminator, and apply the {@code orderBy}
     * on the joined field <b>as a side effect</b> on the {@link jakarta.persistence.criteria.CriteriaQuery};
     * the returned specification is the discriminator predicate for the caller to compose onto the
     * query's specification.
     *
     * @param order the parsed sort order carrying the synthetic property and direction
     * @return the discriminator specification (the {@code orderBy} itself is applied as a side effect)
     */
    Specification<T> toSort(Sort.Order order);
}
