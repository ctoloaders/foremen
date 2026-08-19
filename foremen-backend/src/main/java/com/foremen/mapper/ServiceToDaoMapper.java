package com.foremen.mapper;

import com.foremen.mapper.qualifier.ToExtendedServiceModel;
import com.foremen.mapper.qualifier.ToServiceModel;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.AfterMapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.lang.reflect.Field;
import java.util.Set;

public interface ServiceToDaoMapper<DaoModel, ServiceModel, ServiceExtendedModel>
        extends I18nPropertiesMapper<ServiceModel, DaoModel> {

    @ToServiceModel
    ServiceModel toServiceModel(DaoModel source);

    @ToExtendedServiceModel
    ServiceExtendedModel toServiceExtendedModel(DaoModel source);

    @Mapping(target = "id", ignore = true)
    DaoModel toCreateDaoModel(ServiceExtendedModel source);

    @Mapping(target = "id", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateFields(ServiceExtendedModel source, @MappingTarget DaoModel target);

    @AfterMapping
    default void processI18nEmptyValues(@MappingTarget DaoModel target, ServiceExtendedModel source) {
        Set<String> properties = getI18nSupportedProperties();
        if (properties == null || properties.isEmpty()) {
            return;
        }

        for (String property : properties) {
            normalizeLocaleField(target, property + "RU");
            normalizeLocaleField(target, property + "PL");
        }
    }

    private static void normalizeLocaleField(Object target, String fieldName) {
        try {
            Field field = findDeclaredField(target.getClass(), fieldName);
            if (field == null) {
                return;
            }
            field.setAccessible(true);
            Object value = field.get(target);
            if (value instanceof String str && str.isBlank()) {
                field.set(target, null);
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(
                    "Cannot access field '%s' on class '%s'"
                            .formatted(fieldName, target.getClass().getName()), e);
        }
    }

    private static Field findDeclaredField(Class<?> clazz, String fieldName) {
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
