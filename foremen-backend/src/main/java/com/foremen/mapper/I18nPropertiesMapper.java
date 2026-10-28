package com.foremen.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.MappingTarget;
import org.springframework.context.i18n.LocaleContextHolder;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Set;

public interface I18nPropertiesMapper<TargetModel, SourceModel> {

    Set<String> getI18nSupportedProperties();

    /**
     * The i18n properties used ONLY for sort/filter field resolution (appending the active-locale
     * {@code RU}/{@code PL} suffix to a query path). Defaults to {@link #getI18nSupportedProperties()}
     * so existing mappers are unaffected.
     *
     * <p>Override this when a sortable/filterable column maps to a <em>localized association path</em>
     * (e.g. {@code role.name} &rarr; {@code role.nameRU}/{@code role.namePL}) that must NOT be added
     * to {@link #getI18nSupportedProperties()} — that set also drives the read/write model-field i18n
     * {@code @AfterMapping} hooks ({@code processI18n} / {@code processI18nEmptyValues}), which expect
     * a bare property resolvable to a {@code <prop>RU}/{@code <prop>PL} field on the entity and a
     * matching {@code <prop>} field on the model. A nested path like {@code role.name} there would
     * make those hooks look up a non-existent {@code role.namePL} field and fail.
     */
    default Set<String> getI18nSortFilterProperties() {
        return getI18nSupportedProperties();
    }

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
