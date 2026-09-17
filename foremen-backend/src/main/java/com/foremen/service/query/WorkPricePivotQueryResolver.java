package com.foremen.service.query;

import com.foremen.dao.model.WorkPackagePriceEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.pricing.SeededOfferPackages;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The unified {@link CustomQueryResolver} for the {@code WorkPrice} catalog's synthetic {@code prices}
 * pivot key (FOR-04-12b, "Custom_Predicate_Flow").
 *
 * <p>A {@code Pivot_Key} has the form {@code prices.{packageId}.{field}} — first segment the literal
 * {@code prices} (this resolver's {@link #property()}), second segment an offer package's numeric
 * {@code offerPackage.id}, third segment a filterable/sortable {@link WorkPackagePriceEntity} field
 * (e.g. {@code netPrice}). Both {@link #toFilter(QueryToken.Filter)} and {@link #toSort(Sort.Order)}
 * route through one shared {@link #parse(String) parse}/validate step and one shared correlated
 * {@link #packageMemberSubquery correlated-subquery} discriminator over the aggregator's
 * {@code packagePrices}, so the two paths cannot drift: they use the identical
 * {@code offerPackage.id == {packageId}} discriminator (correlated on the same
 * {@link WorkPackagePriceEntity}) and identical id/field validation (and hence identical error
 * reporting).
 *
 * <p><b>Why a correlated subquery instead of a collection JOIN + {@code DISTINCT}.</b> The original
 * implementation JOINed {@code WorkPrice} onto its {@code packagePrices} collection and set
 * {@code query.distinct(true)} to keep paging over distinct {@code WorkPrice} rows. That works for
 * the filter alone, but the sort path then applied {@code ORDER BY pp.net_price} on that joined,
 * non-selected column while {@code DISTINCT} was in force — which PostgreSQL rejects
 * ({@code SQLState 42P10}, "for SELECT DISTINCT, ORDER BY expressions must appear in select list"),
 * producing an HTTP 500. Because a request typically carries <em>both</em> a pivot filter and a pivot
 * sort, the filter's {@code DISTINCT} and the sort's foreign {@code ORDER BY} expression always
 * collide. Rebuilding both paths as correlated subqueries over {@code packagePrices} removes the
 * collection JOIN (and therefore the need for {@code DISTINCT}) entirely: no {@code WorkPrice} row is
 * ever multiplied, and the ordered expression is a scalar subquery in the main query rather than a
 * column that must appear in a {@code DISTINCT} select list.
 *
 * <ul>
 *   <li>{@link #toFilter} emits {@code EXISTS (SELECT 1 FROM WorkPackagePrice pp WHERE
 *       pp.workPrice = root AND pp.offerPackage.id = {packageId} AND pp.{field} <op> {value})}. The
 *       value comparison reuses {@link SpecificationBuilder#buildCriteriaPredicate} /
 *       {@link SpecificationBuilder#convertValue} so operators and value coercion match the default
 *       column path exactly (identical to the previous behaviour: the row matches iff its price for
 *       that package satisfies the predicate).</li>
 *   <li>{@link #toSort} applies {@code query.orderBy(cb.asc/desc(scalar-subquery))} as a side effect
 *       (ordering by the row's {@code {field}} for that package) and returns the same
 *       {@code EXISTS(offerPackage.id = {packageId})} discriminator predicate for the caller to
 *       compose.</li>
 * </ul>
 *
 * <p>Validation errors (Requirement 4.6): an unknown {@code offerPackage.id} raises
 * {@code error.workprices.unknown.package}; a segment-3 field that is not a real
 * {@link WorkPackagePriceEntity} attribute raises {@code error.query.invalid.field.path} (the same
 * identifier {@link SpecificationBuilder} raises for bad paths). Both filter and sort run the same
 * {@link #parse} validation and the same {@link #resolveFieldPath} attribute check, so they report
 * identical errors for the same bad key.
 */
@Component
public class WorkPricePivotQueryResolver implements CustomQueryResolver<WorkPriceEntity> {

    /** The leading synthetic segment this resolver owns. */
    public static final String PROPERTY = "prices";

    /** Client-error identifier for a Pivot_Key naming a non-existent {@code offerPackage.id}. */
    public static final String ERROR_UNKNOWN_PACKAGE = "error.workprices.unknown.package";

    /** Client-error identifier for a Pivot_Key naming an unknown WorkPackagePrice field. */
    public static final String ERROR_INVALID_FIELD_PATH = "error.query.invalid.field.path";

    private final SeededOfferPackages seededOfferPackages;
    private final CustomQueryResolverRegistry registry;

    public WorkPricePivotQueryResolver(SeededOfferPackages seededOfferPackages,
                                       CustomQueryResolverRegistry registry) {
        this.seededOfferPackages = seededOfferPackages;
        this.registry = registry;
    }

    /** Registers this resolver as the owner of {@code (WorkPriceEntity, "prices")}. */
    @PostConstruct
    void register() {
        registry.register(WorkPriceEntity.class, this);
    }

    @Override
    public String property() {
        return PROPERTY;
    }

    @Override
    public Specification<WorkPriceEntity> toFilter(QueryToken.Filter filter) {
        ParsedKey key = parse(filter.field());
        return (root, query, cb) -> {
            // EXISTS correlated subquery over packagePrices: a WorkPrice matches iff it owns a price
            // for the requested package whose {field} satisfies the value predicate. No collection
            // JOIN and no DISTINCT, so this composes cleanly with a pivot ORDER BY on the same query.
            Subquery<Long> sub = query.subquery(Long.class);
            Root<WorkPackagePriceEntity> member = sub.from(WorkPackagePriceEntity.class);
            sub.select(cb.literal(1L));
            Path<?> fieldPath = resolveFieldPath(member, key.field(), filter.field());
            Predicate value = SpecificationBuilder.buildCriteriaPredicate(
                    fieldPath, filter.operator(), filter.value(), cb);
            sub.where(
                    cb.equal(member.get("workPrice"), root),
                    cb.equal(member.get("offerPackage").get("id"), key.packageId()),
                    value);
            return cb.exists(sub);
        };
    }

    @Override
    public Specification<WorkPriceEntity> toSort(Sort.Order order) {
        ParsedKey key = parse(order.getProperty());
        boolean ascending = order.getDirection().isAscending();
        return (root, query, cb) -> {
            // Order by a correlated scalar subquery yielding this WorkPrice's {field} for the requested
            // package. The ordered expression lives in the main query (not a joined column), so no
            // DISTINCT is required and PostgreSQL accepts "ORDER BY (subquery)". A WorkPrice has at
            // most one price per package (unique (work_price_id, offer_package_id)), so the scalar
            // subquery yields a single value per row.
            Expression<?> orderExpr = scalarFieldSubquery(root, query, cb, key, order.getProperty());
            query.orderBy(ascending ? cb.asc(orderExpr) : cb.desc(orderExpr));

            // Same discriminator semantics as the filter path: keep rows that own a price for the
            // requested package, via a correlated EXISTS (no collection join, no DISTINCT).
            return packageMemberSubquery(root, query, cb, key);
        };
    }

    // --- shared parse / validate / field resolution ----------------------------------------------

    /** A validated {@code prices.{packageId}.{field}} key. */
    private record ParsedKey(Long packageId, String field) {
    }

    /**
     * Splits {@code prices.{packageId}.{field}} and validates both dynamic segments. Shared by the
     * filter and the sort path so they report identical errors for the same bad key.
     *
     * @throws ForemenApiException {@code BAD_REQUEST}/{@code error.query.invalid.field.path} when the
     *                             key is malformed (wrong segment count or non-numeric package id),
     *                             or {@code error.workprices.unknown.package} when the numeric package
     *                             id names no seeded package
     */
    private ParsedKey parse(String rawKey) {
        String[] segments = rawKey.split("\\.");
        if (segments.length != 3 || !PROPERTY.equals(segments[0])
                || segments[1].isEmpty() || segments[2].isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_INVALID_FIELD_PATH, rawKey);
        }
        long packageId;
        try {
            packageId = Long.parseLong(segments[1]);
        } catch (NumberFormatException e) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_INVALID_FIELD_PATH, rawKey);
        }
        if (!seededOfferPackages.existsById(packageId)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_UNKNOWN_PACKAGE, packageId);
        }
        return new ParsedKey(packageId, segments[2]);
    }

    /**
     * Resolves the third-segment field to a {@link Path} on a {@link WorkPackagePriceEntity} subquery
     * root, mapping an unknown attribute to the same client error the default path resolution raises
     * rather than letting the {@link IllegalArgumentException} bubble up as a 500. Shared by the
     * filter and sort subqueries so both validate the field identically.
     */
    private Path<?> resolveFieldPath(Root<WorkPackagePriceEntity> member, String field, String rawKey) {
        try {
            return member.get(field);
        } catch (IllegalArgumentException e) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_INVALID_FIELD_PATH, rawKey);
        }
    }

    // --- correlated-subquery building blocks -----------------------------------------------------

    /**
     * A correlated {@code EXISTS} restricting the main query to {@code WorkPrice} rows that own a
     * price for the requested package — the shared discriminator, equivalent to
     * {@code offerPackage.id == {packageId}} on the collection but expressed as a correlated subquery
     * (no collection join, no {@code DISTINCT}).
     */
    private Predicate packageMemberSubquery(Root<WorkPriceEntity> root, CriteriaQuery<?> query,
                                            CriteriaBuilder cb, ParsedKey key) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<WorkPackagePriceEntity> member = sub.from(WorkPackagePriceEntity.class);
        sub.select(cb.literal(1L));
        sub.where(
                cb.equal(member.get("workPrice"), root),
                cb.equal(member.get("offerPackage").get("id"), key.packageId()));
        return cb.exists(sub);
    }

    /**
     * A correlated scalar subquery selecting {@code field} of the {@link WorkPackagePriceEntity} that
     * belongs to {@code root} (the current {@code WorkPrice}) and to the requested package. Used as the
     * {@code ORDER BY} expression so the ordered value lives in the main query.
     *
     * <p>The {@code field} is validated exactly as the filter path does — an unknown attribute maps to
     * {@link #ERROR_INVALID_FIELD_PATH} rather than bubbling up as a 500.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Expression<?> scalarFieldSubquery(
            Root<WorkPriceEntity> root, CriteriaQuery<?> query, CriteriaBuilder cb, ParsedKey key,
            String rawKey) {
        Subquery<Object> sub = query.subquery(Object.class);
        Root<WorkPackagePriceEntity> member = sub.from(WorkPackagePriceEntity.class);
        Path<?> fieldPath = resolveFieldPath(member, key.field(), rawKey);
        sub.select((Expression<Object>) fieldPath);
        sub.where(
                cb.equal(member.get("workPrice"), root),
                cb.equal(member.get("offerPackage").get("id"), key.packageId()));
        return sub;
    }
}
