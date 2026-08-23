package com.foremen.util;

import com.foremen.controller.model.MetadataResponse;
import jakarta.persistence.Embedded;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility that reflectively resolves JPA entity metadata.
 * Cached per entity class — reflection performed only once.
 */
public class EntityMetadataResolver {

    private static final Map<Class<?>, MetadataResponse> CACHE = new ConcurrentHashMap<>();

    /**
     * Thread-local set to track classes currently being resolved — prevents infinite
     * recursion for circular entity references (e.g., User → Role → User).
     */
    private static final ThreadLocal<Set<Class<?>>> IN_PROGRESS = ThreadLocal.withInitial(HashSet::new);

    private static final Set<String> LOCALE_SUFFIXES = Set.of("RU", "PL");

    private EntityMetadataResolver() {}

    public static MetadataResponse resolve(Class<?> entityClass) {
        MetadataResponse cached = CACHE.get(entityClass);
        if (cached != null) {
            return cached;
        }
        MetadataResponse result = buildMetadata(entityClass);
        CACHE.put(entityClass, result);
        return result;
    }

    private static MetadataResponse buildMetadata(Class<?> clazz) {
        Set<Class<?>> inProgress = IN_PROGRESS.get();
        if (inProgress.contains(clazz)) {
            // Circular reference detected — return empty metadata to break the cycle
            return new MetadataResponse(List.of());
        }
        inProgress.add(clazz);
        try {
            return doBuildMetadata(clazz);
        } finally {
            inProgress.remove(clazz);
        }
    }

    private static MetadataResponse doBuildMetadata(Class<?> clazz) {
        List<MetadataResponse.FieldInfo> fields = new ArrayList<>();
        Set<String> i18nBaseFields = new HashSet<>();

        List<Field> allFields = getAllFields(clazz);

        // First pass: identify i18n base fields by locale suffixes
        for (Field field : allFields) {
            String name = field.getName();
            for (String suffix : LOCALE_SUFFIXES) {
                if (name.endsWith(suffix)) {
                    String baseName = name.substring(0, name.length() - suffix.length());
                    i18nBaseFields.add(baseName);
                }
            }
        }

        // Track which i18n base fields have been emitted (either as real fields or synthetic)
        Set<String> emittedI18nBases = new HashSet<>();

        // Second pass: build metadata (skip suffixed i18n fields, skip JPA internals)
        for (Field field : allFields) {
            String name = field.getName();

            // Skip locale-suffixed fields
            boolean isSuffixed = LOCALE_SUFFIXES.stream()
                    .anyMatch(s -> name.endsWith(s) && i18nBaseFields.contains(
                            name.substring(0, name.length() - s.length())));
            if (isSuffixed) continue;

            // Skip JPA internal fields
            if (name.startsWith("$$") || name.equals("serialVersionUID")) continue;

            MetadataResponse.DataType dataType = mapJavaType(field.getType());
            boolean isI18n = i18nBaseFields.contains(name);
            List<MetadataResponse.FieldInfo> nested = null;

            if (isNestedEntity(field)) {
                MetadataResponse nestedMeta = resolve(field.getType());
                nested = nestedMeta.fields();
            }

            if (isI18n) {
                emittedI18nBases.add(name);
            }

            fields.add(new MetadataResponse.FieldInfo(name, dataType, isI18n, nested));
        }

        // Third pass: emit synthetic i18n base fields that don't exist as real fields
        for (String baseName : i18nBaseFields) {
            if (!emittedI18nBases.contains(baseName)) {
                fields.add(new MetadataResponse.FieldInfo(baseName, MetadataResponse.DataType.STRING, true, null));
            }
        }

        return new MetadataResponse(fields);
    }

    private static MetadataResponse.DataType mapJavaType(Class<?> type) {
        if (type == String.class) return MetadataResponse.DataType.STRING;
        if (type == Integer.class || type == int.class ||
            type == Long.class || type == long.class ||
            type == Double.class || type == double.class ||
            type == BigDecimal.class) return MetadataResponse.DataType.NUMBER;
        if (type == LocalDate.class || type == LocalDateTime.class) return MetadataResponse.DataType.DATE;
        if (type == Boolean.class || type == boolean.class) return MetadataResponse.DataType.BOOLEAN;
        if (type.isEnum()) return MetadataResponse.DataType.ENUM;
        return MetadataResponse.DataType.STRING; // fallback
    }

    private static boolean isNestedEntity(Field field) {
        return field.isAnnotationPresent(Embedded.class) ||
               field.isAnnotationPresent(ManyToOne.class) ||
               field.isAnnotationPresent(OneToOne.class);
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }
}
