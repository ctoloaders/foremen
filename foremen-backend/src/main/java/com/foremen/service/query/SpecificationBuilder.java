package com.foremen.service.query;

import com.foremen.exception.ForemenApiException;
import jakarta.persistence.criteria.*;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

public class SpecificationBuilder {

    /**
     * Registry of {@link CustomQueryResolver}s consulted by {@link #buildPredicate} before the default
     * single-column path resolution. Held statically because {@code SpecificationBuilder} is a static
     * translator invoked from the static {@link QueryParser} pipeline, while the registry is a Spring
     * bean; it is wired once at startup via {@link #setCustomQueryResolverRegistry}. Stays {@code null}
     * outside a Spring context (e.g. in unit tests that call {@link #buildPredicate} directly), in
     * which case {@link #buildPredicate} always takes the default path — this branch adds no hard-coded
     * entity or segment knowledge to the translator.
     */
    private static volatile CustomQueryResolverRegistry customQueryResolverRegistry;

    /** Wires the shared {@link CustomQueryResolverRegistry} into the static translator (called once at startup). */
    static void setCustomQueryResolverRegistry(CustomQueryResolverRegistry registry) {
        customQueryResolverRegistry = registry;
    }

    public static <T> Specification<T> buildPredicate(QueryToken.Filter filter, Class<T> entityClass) {
        // Custom-predicate branch: when the filter field's first path segment is registered as a
        // synthetic (non-column) property for this entity, delegate to the registered resolver's
        // toFilter instead of the default single-column path resolution. Kept generic — the registry,
        // not this translator, holds any (entity, segment) knowledge (FOR-04-12b Custom_Predicate_Flow).
        CustomQueryResolverRegistry registry = customQueryResolverRegistry;
        if (registry != null) {
            String leadingSegment = firstSegment(filter.field());
            var resolver = registry.find(entityClass, leadingSegment);
            if (resolver.isPresent()) {
                return resolver.get().toFilter(filter);
            }
        }

        return (root, query, cb) -> {
            Path<?> path = resolvePath(root, filter.field(), query);
            return buildCriteriaPredicate(path, filter.operator(), filter.value(), cb);
        };
    }

    /** Returns the first dot-separated segment of {@code field} (the whole field when it has no dot). */
    private static String firstSegment(String field) {
        int dot = field.indexOf('.');
        return dot < 0 ? field : field.substring(0, dot);
    }

    /**
     * Resolves a dot-notation field path to a JPA Path.
     * Internally detects collection attributes and automatically uses JOIN.
     * No external distinction needed — the caller simply passes "tasks.status"
     * and this method handles JOIN creation transparently.
     */
    @SuppressWarnings("unchecked")
    private static <T> Path<?> resolvePath(Root<T> root, String field, CriteriaQuery<?> query) {
        if (!field.contains(".")) {
            try {
                return root.get(field);
            } catch (IllegalArgumentException e) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                        "error.query.invalid.field", field);
            }
        }

        // Dot-notation: navigate nested fields with automatic collection join detection
        String[] parts = field.split("\\.");
        From<?, ?> currentFrom = root;
        Path<?> currentPath = root;

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            try {
                if (currentFrom != null && i < parts.length - 1) {
                    // Non-terminal segment: check if it's a collection needing JOIN
                    Attribute<?, ?> attr = getAttribute(currentFrom, part);
                    if (attr != null && attr.isCollection()) {
                        // Collection attribute — use JOIN, reuse existing if present
                        currentFrom = getOrCreateJoin(currentFrom, part);
                        currentPath = currentFrom;
                        if (query != null) {
                            query.distinct(true);
                        }
                    } else {
                        // Singular association or embedded — navigate with get()
                        currentPath = currentFrom.get(part);
                        // Check if the next segment needs From context (for further joins)
                        if (attr != null && attr.isAssociation()) {
                            currentFrom = getOrCreateJoin(currentFrom, part);
                            currentPath = currentFrom;
                        } else {
                            currentFrom = null; // no longer navigable as From
                        }
                    }
                } else {
                    // Terminal segment or no From context — just get()
                    currentPath = (currentFrom != null) ? currentFrom.get(part) : currentPath.get(part);
                }
            } catch (IllegalArgumentException e) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                        "error.query.invalid.field.path", field);
            }
        }

        return currentPath;
    }

    private static Attribute<?, ?> getAttribute(From<?, ?> from, String attributeName) {
        try {
            ManagedType<?> model = managedTypeOf(from);
            return model != null ? model.getAttribute(attributeName) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Resolves the {@link ManagedType} a {@link From} navigates from.
     *
     * <p>A {@link Root} exposes its {@link jakarta.persistence.metamodel.EntityType} directly via
     * {@code getModel()}, which is a {@link ManagedType}. A {@link Join}, however, returns its
     * <em>attribute</em> from {@code getModel()} (e.g. a {@code ListAttributeImpl} for a
     * {@code @OneToMany} collection), not a managed type — so casting it to {@link ManagedType}
     * throws (the cause of the failure on nested collection paths such as
     * {@code members.user.id}). For a {@link Join} we therefore take the target managed type from
     * the attribute's element type ({@code PluralAttribute#getElementType()} for a collection join,
     * or {@code SingularAttribute#getType()} for a to-one join). Only managed (entity/embeddable)
     * targets are navigable further; a basic-typed leaf yields {@code null} so the caller falls
     * back to plain {@code get()} navigation.
     */
    private static ManagedType<?> managedTypeOf(From<?, ?> from) {
        if (from instanceof Root<?> root) {
            return root.getModel();
        }
        if (from instanceof Join<?, ?> join) {
            Attribute<?, ?> joinAttribute = join.getAttribute();
            jakarta.persistence.metamodel.Type<?> targetType =
                    joinAttribute instanceof jakarta.persistence.metamodel.PluralAttribute<?, ?, ?> plural
                            ? plural.getElementType()
                            : ((jakarta.persistence.metamodel.SingularAttribute<?, ?>) joinAttribute).getType();
            return targetType instanceof ManagedType<?> managed ? managed : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> Join<T, ?> getOrCreateJoin(From<?, ?> from, String attributeName) {
        // Reuse existing JOIN if already created for this attribute
        for (Join<?, ?> existingJoin : from.getJoins()) {
            if (existingJoin.getAttribute().getName().equals(attributeName)) {
                return (Join<T, ?>) existingJoin;
            }
        }
        return (Join<T, ?>) from.join(attributeName);
    }

    /**
     * Builds the JPA {@link Predicate} for a single {@code (path, operator, value)} triple, coercing
     * the raw string {@code value} to the {@code path}'s Java type via {@link #convertValue(Path, String)}.
     *
     * <p>Package-visible so a {@link CustomQueryResolver} in this package can reuse the same operator
     * switch and value coercion when it builds the value half of a synthetic pivot predicate
     * (FOR-04-12b Custom_Predicate_Flow), keeping the comparison semantics identical to the default
     * single-column path resolution.
     */
    @SuppressWarnings("unchecked")
    static Predicate buildCriteriaPredicate(Path<?> path, QueryOperator operator, String value, CriteriaBuilder cb) {
        return switch (operator) {
            // Equality
            case EQUALS -> cb.equal(path, convertValue(path, value));
            case NOT_EQUALS -> cb.notEqual(path, convertValue(path, value));

            // Text search (case-insensitive)
            case CONTAINS, LIKE -> cb.like(cb.lower((Path<String>) path), "%" + value.toLowerCase() + "%");
            case STARTS_WITH -> cb.like(cb.lower((Path<String>) path), value.toLowerCase() + "%");
            case ENDS_WITH -> cb.like(cb.lower((Path<String>) path), "%" + value.toLowerCase());

            // Text search (case-sensitive)
            case CONTAINS_CS -> cb.like((Path<String>) path, "%" + value + "%");
            case STARTS_WITH_CS -> cb.like((Path<String>) path, value + "%");
            case ENDS_WITH_CS -> cb.like((Path<String>) path, "%" + value);

            // Numeric comparison
            case GREATER_THAN, GT -> cb.greaterThan((Path<Comparable>) path, (Comparable) convertValue(path, value));
            case LESS_THAN, LT -> cb.lessThan((Path<Comparable>) path, (Comparable) convertValue(path, value));
            case GREATER_THAN_OR_EQUAL, GTE -> cb.greaterThanOrEqualTo((Path<Comparable>) path, (Comparable) convertValue(path, value));
            case LESS_THAN_OR_EQUAL, LTE -> cb.lessThanOrEqualTo((Path<Comparable>) path, (Comparable) convertValue(path, value));

            // Date comparison
            case GT_DATE -> cb.greaterThan((Path<LocalDateTime>) path, parseDateTime(value));
            case LT_DATE -> cb.lessThan((Path<LocalDateTime>) path, parseDateTime(value));

            // Set membership
            case IN -> ((Path<Object>) path).in(parseInValues(value));
            case NOT_IN -> cb.not(((Path<Object>) path).in(parseInValues(value)));

            // Null checks
            case NULL -> cb.isNull(path);
            case NOT_NULL -> cb.isNotNull(path);
        };
    }

    static Object convertValue(Path<?> path, String value) {
        Class<?> javaType = path.getJavaType();
        if (javaType == Long.class || javaType == long.class) {
            return Long.parseLong(value);
        }
        if (javaType == Integer.class || javaType == int.class) {
            return Integer.parseInt(value);
        }
        if (javaType == Double.class || javaType == double.class) {
            return Double.parseDouble(value);
        }
        if (javaType == Float.class || javaType == float.class) {
            return Float.parseFloat(value);
        }
        if (javaType == Boolean.class || javaType == boolean.class) {
            return Boolean.parseBoolean(value);
        }
        if (javaType == LocalDateTime.class) {
            return parseDateTime(value);
        }
        if (javaType == LocalDate.class) {
            return LocalDate.parse(value);
        }
        return value; // String default
    }

    private static LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (DateTimeParseException e2) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                        "error.query.invalid.date", value);
            }
        }
    }

    private static List<Object> parseInValues(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(v -> (Object) v)
                .toList();
    }
}
