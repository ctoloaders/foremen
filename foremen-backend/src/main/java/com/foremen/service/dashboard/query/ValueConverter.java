package com.foremen.service.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Map;

/** Converts JSON filter values and date bounds to the Java type of a query expression. */
final class ValueConverter {

    private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
            long.class, Long.class, int.class, Integer.class, short.class, Short.class,
            double.class, Double.class, float.class, Float.class, boolean.class, Boolean.class);

    private ValueConverter() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object fromJson(JsonNode node, Class<?> type, ZoneId zone) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("null filter value");
        }
        Class<?> t = WRAPPERS.getOrDefault(type, type);
        if (t == String.class) {
            return node.asText();
        }
        if (t == Long.class) {
            return node.asLong();
        }
        if (t == Integer.class) {
            return node.asInt();
        }
        if (t == Short.class) {
            return (short) node.asInt();
        }
        if (t == Double.class) {
            return node.asDouble();
        }
        if (t == Float.class) {
            return (float) node.asDouble();
        }
        if (t == BigDecimal.class) {
            return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText());
        }
        if (t == Boolean.class) {
            return node.asBoolean();
        }
        if (t.isEnum()) {
            return Enum.valueOf((Class<? extends Enum>) t, node.asText());
        }
        if (t == LocalDate.class) {
            return LocalDate.parse(node.asText().substring(0, 10));
        }
        LocalDateTime ldt = parseDateTime(node.asText(), zone);
        if (t == LocalDateTime.class) {
            return ldt;
        }
        return bound(t, ldt.atZone(zone).toInstant(), zone);
    }

    private static LocalDateTime parseDateTime(String text, ZoneId zone) {
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        return LocalDate.parse(text).atStartOfDay();
    }

    /** Start of {@code day} expressed in the temporal type {@code type}. */
    static Object startOfDay(Class<?> type, LocalDate day, ZoneId zone) {
        if (type == LocalDate.class) {
            return day;
        }
        if (type == LocalDateTime.class) {
            return day.atStartOfDay();
        }
        return bound(type, day.atStartOfDay(zone).toInstant(), zone);
    }

    private static Object bound(Class<?> type, Instant instant, ZoneId zone) {
        if (type == Instant.class) {
            return instant;
        }
        if (type == OffsetDateTime.class) {
            return instant.atZone(zone).toOffsetDateTime();
        }
        if (type == ZonedDateTime.class) {
            return instant.atZone(zone);
        }
        if (type == Timestamp.class) {
            return Timestamp.from(instant);
        }
        if (type == Date.class) {
            return Date.from(instant);
        }
        throw new IllegalArgumentException("Unsupported temporal type " + type.getName());
    }

    static boolean isTemporal(Class<?> type) {
        return type == LocalDate.class || type == LocalDateTime.class || type == Instant.class
                || type == OffsetDateTime.class || type == ZonedDateTime.class
                || type == Timestamp.class || type == Date.class;
    }
}
