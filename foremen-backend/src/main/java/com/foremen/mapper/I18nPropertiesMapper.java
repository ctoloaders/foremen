package com.foremen.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.MappingTarget;
import org.springframework.context.i18n.LocaleContextHolder;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Set;

public interface I18nPropertiesMapper<TargetModel, SourceModel> {

    Set<String> getI18nSupportedProperties();

    @AfterMapping
    default void processI18n(@MappingTarget TargetModel target, SourceModel source) {
        Set<String> properties = getI18nSupportedProperties();
        if (properties == null || properties.isEmpty()) {
            return;
        }

        for (String property : properties) {
            Object value = getInCurrentLocale(property, source);
            setFieldValue(target, property, value);
        }
    }

    default Object getInCurrentLocale(String basePropertyName, Object source) {
        Locale locale = LocaleContextHolder.getLocale();
        String suffix = resolveSuffix(locale);
        String localizedFieldName = basePropertyName + suffix;
        return getFieldValue(source, localizedFieldName);
    }

    private static String resolveSuffix(Locale locale) {
        if (locale != null && "ru".equalsIgnoreCase(locale.getLanguage())) {
            return "RU";
        }
        return "PL"; // default locale — Polish
    }

    private static Object getFieldValue(Object source, String fieldName) {
        try {
            Field field = findField(source.getClass(), fieldName);
            if (field == null) {
                throw new IllegalArgumentException(
                        "Field '%s' not found on class '%s'"
                                .formatted(fieldName, source.getClass().getName()));
            }
            field.setAccessible(true);
            return field.get(source);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(
                    "Cannot access field '%s' on class '%s'"
                            .formatted(fieldName, source.getClass().getName()), e);
        }
    }

    private static void setFieldValue(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            if (field == null) {
                throw new IllegalArgumentException(
                        "Field '%s' not found on class '%s'"
                                .formatted(fieldName, target.getClass().getName()));
            }
            field.setAccessible(true);
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(
                    "Cannot access field '%s' on class '%s'"
                            .formatted(fieldName, target.getClass().getName()), e);
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
