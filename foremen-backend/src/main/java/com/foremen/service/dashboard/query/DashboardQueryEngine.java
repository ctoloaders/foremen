package com.foremen.service.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.config.WidgetConfig;
import com.foremen.service.dashboard.config.WidgetConfig.Agg;
import com.foremen.service.dashboard.config.WidgetConfig.Bucket;
import com.foremen.service.dashboard.config.WidgetConfig.Dimension;
import com.foremen.service.dashboard.config.WidgetConfig.Filter;
import com.foremen.service.dashboard.config.WidgetConfig.Format;
import com.foremen.service.dashboard.config.WidgetConfig.Localized;
import com.foremen.service.dashboard.config.WidgetConfig.Measure;
import com.foremen.service.dashboard.config.WidgetConfig.Sort;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.QueryTimeoutException;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a validated {@link WidgetConfig} against a {@link Dataset} with the JPA Criteria API
 * (FOR-10 Requirements 5.5, 6.4-6.6, 7.2, 8.3, 8.4). Everything is parameter-bound; no native SQL.
 *
 * <p>Shape: with measures the query selects dimensions plus measures and groups by the dimensions,
 * otherwise it selects the configured columns. Predicates are ANDed: project scope (skipped for
 * ADMIN), bound project, row ownership, dataset predicate, config filters, global date range.
 * Results are capped at {@code min(limit, 200)} rows; one extra row is fetched to detect truncation.
 *
 * <p>Money cells carry the row currency when the field declares a {@code currencyPath} and the value
 * is a plain column or dimension; aggregated money uses the company default currency.
 */
@Component
public class DashboardQueryEngine {

    static final String TIMEOUT_HINT = "jakarta.persistence.query.timeout";
    static final int TIMEOUT_MILLIS = 3000;

    private final EntityManager em;
    private final ZoneId zone;
    private final String defaultCurrency;

    @Autowired
    public DashboardQueryEngine(EntityManager em,
                                @Value("${foremen.dashboard.zone:}") String zoneId,
                                @Value("${foremen.dashboard.default-currency:PLN}") String defaultCurrency) {
        this(em, zoneId == null || zoneId.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zoneId),
                defaultCurrency);
    }

    public DashboardQueryEngine(EntityManager em, ZoneId zone, String defaultCurrency) {
        this.em = em;
        this.zone = zone;
        this.defaultCurrency = defaultCurrency;
    }

    @Transactional(readOnly = true)
    public WidgetResult execute(Dataset dataset, WidgetConfig config, QueryContext ctx) {
        Run main = run(dataset, config, ctx);
        Map<String, Object> previous = null;
        boolean compare = config.presentation() != null
                && Boolean.TRUE.equals(config.presentation().compareToPrevious());
        if (compare && !config.measures().isEmpty() && config.dimensions().isEmpty()
                && ctx.dateFrom() != null && ctx.dateTo() != null) {
            long days = ChronoUnit.DAYS.between(ctx.dateFrom(), ctx.dateTo()) + 1;
            LocalDate prevTo = ctx.dateFrom().minusDays(1);
            LocalDate prevFrom = prevTo.minusDays(days - 1);
            Run prev = run(dataset, config, ctx.withDates(prevFrom, prevTo));
            previous = prev.rows().isEmpty() ? null : prev.rows().get(0);
        }
        return new WidgetResult(main.columns(), main.rows(), null, previous, main.truncated(), Instant.now());
    }

    private record Run(List<WidgetResult.Column> columns, List<Map<String, Object>> rows, boolean truncated) {
    }

    /** One output cell: tuple position of the value and, for money, of its currency (or -1). */
    private record Out(String key, FieldType type, int index, int currencyIndex, Agg agg) {
    }

    private Run run(Dataset ds, WidgetConfig cfg, QueryContext ctx) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<?> root = cq.from(ds.rootEntity());
        Resolver r = new Resolver(ds, cb, cq, root);

        List<Selection<?>> selections = new ArrayList<>();
        List<Out> outs = new ArrayList<>();
        List<WidgetResult.Column> columns = new ArrayList<>();
        List<Expression<?>> groupBy = new ArrayList<>();
        Map<String, Expression<?>> sortable = new HashMap<>();

        boolean grouped = !cfg.measures().isEmpty();
        if (grouped) {
            for (Dimension d : cfg.dimensions()) {
                FieldDescriptor f = r.field(d.field());
                if (!f.groupable()) {
                    throw new IllegalArgumentException("Field is not groupable: " + f.name());
                }
                Expression<?> e = r.expression(f);
                FieldType type = f.type();
                if (d.bucket() != null && (type == FieldType.DATE || type == FieldType.DATETIME)) {
                    e = bucket(cb, e, d.bucket());
                    type = FieldType.DATE;
                }
                int idx = add(selections, e);
                groupBy.add(e);
                int cur = addCurrency(r, f, selections, groupBy);
                sortable.put(f.name(), e);
                outs.add(new Out(f.name(), type, idx, cur, null));
                columns.add(new WidgetResult.Column(f.name(), type, loc(d.label(), ctx), f.labelKey(), null));
            }
            for (Measure m : cfg.measures()) {
                FieldDescriptor f = r.field(m.field());
                if (!f.aggregatable() && m.agg() != Agg.COUNT && m.agg() != Agg.COUNT_DISTINCT) {
                    throw new IllegalArgumentException("Field is not aggregatable: " + f.name());
                }
                Expression<?> e = aggregate(cb, r.expression(f), m.agg());
                String key = m.alias() != null ? m.alias() : m.agg().name().toLowerCase() + "_" + m.field();
                boolean count = m.agg() == Agg.COUNT || m.agg() == Agg.COUNT_DISTINCT;
                FieldType type = count || (m.agg() == Agg.AVG && f.type() == FieldType.ID)
                        ? FieldType.NUMBER : f.type();
                int idx = add(selections, e);
                sortable.put(key, e);
                outs.add(new Out(key, type, idx, -1, m.agg()));
                columns.add(new WidgetResult.Column(key, type, loc(m.label(), ctx),
                        "dashboard.measure." + m.agg().name().toLowerCase(), m.format()));
            }
        } else {
            for (WidgetConfig.Column c : cfg.columns()) {
                FieldDescriptor f = r.field(c.field());
                if (!f.selectable()) {
                    throw new IllegalArgumentException("Field is not selectable: " + f.name());
                }
                Expression<?> e = r.expression(f);
                int idx = add(selections, e);
                int cur = addCurrency(r, f, selections, null);
                sortable.put(f.name(), e);
                outs.add(new Out(f.name(), f.type(), idx, cur, null));
                columns.add(new WidgetResult.Column(f.name(), f.type(), loc(c.label(), ctx),
                        f.labelKey(), c.format()));
            }
        }
        if (selections.isEmpty()) {
            throw new IllegalArgumentException("Widget_Config selects nothing");
        }
        cq.multiselect(selections);

        List<Predicate> where = predicates(ds, cfg, ctx, r, cb, root);
        if (!where.isEmpty()) {
            cq.where(where.toArray(Predicate[]::new));
        }
        if (grouped && !groupBy.isEmpty()) {
            cq.groupBy(groupBy);
        }
        List<Order> order = new ArrayList<>();
        for (Sort s : cfg.sort()) {
            Expression<?> e = sortable.get(s.by());
            if (e != null) {
                order.add(s.dir() == WidgetConfig.Dir.DESC ? cb.desc(e) : cb.asc(e));
            }
        }
        if (!order.isEmpty()) {
            cq.orderBy(order);
        }

        int limit = Math.min(cfg.effectiveLimit(), WidgetConfig.MAX_LIMIT);
        TypedQuery<Tuple> q = em.createQuery(cq).setMaxResults(limit + 1);
        q.setHint(TIMEOUT_HINT, TIMEOUT_MILLIS);
        List<Tuple> tuples;
        try {
            tuples = q.getResultList();
        } catch (QueryTimeoutException e) {
            throw new DashboardQueryTimeoutException(e);
        } catch (PersistenceException e) {
            if (e.getCause() instanceof java.sql.SQLTimeoutException) {
                throw new DashboardQueryTimeoutException(e);
            }
            throw e;
        }
        boolean truncated = tuples.size() > limit;
        List<Tuple> page = truncated ? tuples.subList(0, limit) : tuples;

        List<Map<String, Object>> rows = new ArrayList<>(page.size());
        for (Tuple t : page) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Out o : outs) {
                Object currency = o.currencyIndex() >= 0 ? t.get(o.currencyIndex()) : null;
                row.put(o.key(), convert(t.get(o.index()), o, currency == null ? null : currency.toString()));
            }
            rows.add(row);
        }
        return new Run(columns, rows, truncated);
    }

    private static int add(List<Selection<?>> selections, Selection<?> s) {
        selections.add(s);
        return selections.size() - 1;
    }

    /** Appends the row currency selection for a money field and returns its tuple index, or -1. */
    private static int addCurrency(Resolver r, FieldDescriptor f, List<Selection<?>> selections,
                                   List<Expression<?>> groupBy) {
        if (f.type() != FieldType.MONEY || f.currencyPath() == null) {
            return -1;
        }
        Expression<?> cur = r.path(f.currencyPath());
        if (groupBy != null) {
            groupBy.add(cur);
        }
        return add(selections, cur);
    }

    private List<Predicate> predicates(Dataset ds, WidgetConfig cfg, QueryContext ctx, Resolver r,
                                       CriteriaBuilder cb, Root<?> root) {
        List<Predicate> ps = new ArrayList<>();
        var projectPath = ds.projectPath();

        // project scope; ADMIN bypass exactly like ProjectScopedService
        if (projectPath.isPresent() && !ctx.admin()) {
            Predicate in = ctx.allowedProjectIds().isEmpty()
                    ? cb.disjunction()
                    : r.path(projectPath.get()).in(ctx.allowedProjectIds());
            Predicate nullRule = ds.nullProjectPredicate(ctx, cb, root);
            ps.add(nullRule == null ? in : cb.or(in, nullRule));
        }
        if (ctx.boundProjectId() != null) {
            ps.add(projectPath.isPresent()
                    ? cb.equal(r.path(projectPath.get()), ctx.boundProjectId())
                    : cb.disjunction());
        }
        boolean own = ds.alwaysOwn()
                || cfg.effectiveRowScope() == WidgetConfig.RowScope.OWN
                || (ctx.roleCode() != null && ds.ownerRestrictedRoles().contains(ctx.roleCode()));
        if (own) {
            ps.add(ds.ownerPath().isPresent()
                    ? cb.equal(r.path(ds.ownerPath().get()), ctx.userId())
                    : cb.disjunction());
        }
        Predicate extra = ds.extraPredicate(ctx, cb, root);
        if (extra != null) {
            ps.add(extra);
        }
        for (Filter f : cfg.filters()) {
            ps.add(filter(r, cb, f));
        }
        String dateField = cfg.dateField() != null ? cfg.dateField() : ds.defaultDateField().orElse(null);
        if (dateField != null && (ctx.dateFrom() != null || ctx.dateTo() != null)) {
            addDateRange(ps, r, cb, r.field(dateField), ctx);
        }
        return ps;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void addDateRange(List<Predicate> ps, Resolver r, CriteriaBuilder cb, FieldDescriptor f,
                              QueryContext ctx) {
        Expression e = r.expression(f);
        Class<?> type = e.getJavaType();
        if (!ValueConverter.isTemporal(type)) {
            throw new IllegalArgumentException("Date field is not temporal: " + f.name());
        }
        if (ctx.dateFrom() != null) {
            ps.add(cb.greaterThanOrEqualTo(e,
                    (Comparable) ValueConverter.startOfDay(type, ctx.dateFrom(), zone)));
        }
        if (ctx.dateTo() != null) {
            ps.add(cb.lessThan(e,
                    (Comparable) ValueConverter.startOfDay(type, ctx.dateTo().plusDays(1), zone)));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate filter(Resolver r, CriteriaBuilder cb, Filter f) {
        FieldDescriptor fd = r.field(f.field());
        if (!fd.filterable()) {
            throw new IllegalArgumentException("Field is not filterable: " + f.field());
        }
        Expression e = r.expression(fd);
        Class<?> type = e.getJavaType();
        JsonNode v = f.value();
        return switch (f.op()) {
            case IS_NULL -> cb.isNull(e);
            case IS_NOT_NULL -> cb.isNotNull(e);
            case EQ -> cb.equal(e, ValueConverter.fromJson(v, type, zone));
            case NE -> cb.notEqual(e, ValueConverter.fromJson(v, type, zone));
            case GT -> cb.greaterThan(e, (Comparable) ValueConverter.fromJson(v, type, zone));
            case GTE -> cb.greaterThanOrEqualTo(e, (Comparable) ValueConverter.fromJson(v, type, zone));
            case LT -> cb.lessThan(e, (Comparable) ValueConverter.fromJson(v, type, zone));
            case LTE -> cb.lessThanOrEqualTo(e, (Comparable) ValueConverter.fromJson(v, type, zone));
            case IN -> {
                List<Object> values = new ArrayList<>();
                for (JsonNode n : v) {
                    values.add(ValueConverter.fromJson(n, type, zone));
                }
                yield values.isEmpty() ? cb.disjunction() : e.in(values);
            }
            case BETWEEN -> {
                if (v == null || v.size() != 2) {
                    throw new IllegalArgumentException("BETWEEN needs exactly two values");
                }
                yield cb.between(e, (Comparable) ValueConverter.fromJson(v.get(0), type, zone),
                        (Comparable) ValueConverter.fromJson(v.get(1), type, zone));
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Expression<?> aggregate(CriteriaBuilder cb, Expression<?> e, Agg agg) {
        return switch (agg) {
            case COUNT -> cb.count(e);
            case COUNT_DISTINCT -> cb.countDistinct(e);
            case SUM -> cb.sum((Expression<Number>) e);
            case AVG -> cb.avg((Expression<Number>) e);
            case MIN -> cb.least((Expression<Comparable>) e);
            case MAX -> cb.greatest((Expression<Comparable>) e);
        };
    }

    private static Expression<?> bucket(CriteriaBuilder cb, Expression<?> e, Bucket b) {
        return cb.function("date_trunc", LocalDateTime.class, cb.literal(b.name().toLowerCase()),
                e.as(LocalDateTime.class));
    }

    private Object convert(Object raw, Out o, String rowCurrency) {
        if (raw == null) {
            if (o.agg() == Agg.COUNT || o.agg() == Agg.COUNT_DISTINCT) {
                return 0L;
            }
            if (o.agg() == Agg.SUM) {
                return o.type() == FieldType.MONEY ? money(BigDecimal.ZERO, rowCurrency) : BigDecimal.ZERO;
            }
            return null;
        }
        if (o.type() == FieldType.MONEY && raw instanceof Number n) {
            return money(n instanceof BigDecimal bd ? bd : new BigDecimal(n.toString()), rowCurrency);
        }
        if (raw instanceof Double || raw instanceof Float) {
            return BigDecimal.valueOf(((Number) raw).doubleValue()).setScale(4, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
        }
        if (raw instanceof Enum<?> en) {
            return en.name();
        }
        if (raw instanceof LocalDateTime ldt && o.type() == FieldType.DATE) {
            return ldt.toLocalDate().toString();
        }
        if (raw instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (raw instanceof Temporal || raw instanceof java.util.Date) {
            return raw.toString();
        }
        return raw;
    }

    private Map<String, Object> money(BigDecimal amount, String currency) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        m.put("currency", currency != null ? currency : defaultCurrency);
        return m;
    }

    private static String loc(Localized l, QueryContext ctx) {
        if (l == null) {
            return null;
        }
        boolean pl = "pl".equals(ctx.locale());
        String primary = pl ? l.pl() : l.ru();
        return primary != null ? primary : (pl ? l.ru() : l.pl());
    }

    /** Resolves field names and JPA paths to criteria expressions, reusing joins and computed values. */
    private static final class Resolver {
        private final Map<String, FieldDescriptor> fields = new HashMap<>();
        private final Map<String, From<?, ?>> joins = new HashMap<>();
        private final Map<String, Expression<?>> computed = new HashMap<>();
        private final CriteriaBuilder cb;
        private final CriteriaQuery<?> cq;
        private final Root<?> root;

        Resolver(Dataset ds, CriteriaBuilder cb, CriteriaQuery<?> cq, Root<?> root) {
            this.cb = cb;
            this.cq = cq;
            this.root = root;
            ds.allFields().forEach(f -> fields.put(f.name(), f));
        }

        FieldDescriptor field(String name) {
            FieldDescriptor f = fields.get(name);
            if (f == null) {
                throw new IllegalArgumentException("Unknown field: " + name);
            }
            return f;
        }

        Expression<?> expression(FieldDescriptor f) {
            if (f.computed() != null) {
                return computed.computeIfAbsent(f.name(), n -> f.computed().build(cb, cq, root));
            }
            return path(f.jpaPath());
        }

        Expression<?> path(String dotPath) {
            String[] seg = dotPath.split("\\.");
            From<?, ?> from = root;
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < seg.length - 1; i++) {
                key.append(seg[i]).append('.');
                From<?, ?> existing = joins.get(key.toString());
                if (existing == null) {
                    existing = from.join(seg[i], JoinType.LEFT);
                    joins.put(key.toString(), existing);
                }
                from = existing;
            }
            return from.get(seg[seg.length - 1]);
        }
    }
}
