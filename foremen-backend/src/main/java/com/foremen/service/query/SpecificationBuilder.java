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

    public static <T> Specification<T> buildPredicate(QueryToken.Filter filter, Class<T> entityClass) {
        return (root, query, cb) -> {
            Path<?> path = resolvePath(root, filter.field(), query);
            return buildCriteriaPredicate(path, filter.operator(), filter.value(), cb);
        };
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

    @SuppressWarnings("unchecked")
    private static Attribute<?, ?> getAttribute(From<?, ?> from, String attributeName) {
        try {
            ManagedType<?> model = (ManagedType<?>) from.getModel();
            return model.getAttribute(attributeName);
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    @SuppressWarnings("unchecked")
    private static Predicate buildCriteriaPredicate(Path<?> path, QueryOperator operator, String value, CriteriaBuilder cb) {
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

    private static Object convertValue(Path<?> path, String value) {
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
