package com.foremen.service;

import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.query.QueryParser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public interface ReadOnlyAdminService<ServiceModel, ServiceExtendedModel, DaoModel, ID> {

    // --- Abstract methods (must be implemented by concrete service) ---

    ServiceToDaoMapper<DaoModel, ServiceModel, ServiceExtendedModel> getMapper();

    ReadOnlyAdminDao<DaoModel, ID> getReadDao();

    EntityManager getEntityManager();

    default Class<DaoModel> getDaoModelClass() {
        throw new UnsupportedOperationException("Subclass must provide DaoModel class");
    }

    // --- Find by ID ---

    default ServiceExtendedModel findById(ID id) {
        DaoModel entity = getReadDao().findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id));
        return getMapper().toServiceExtendedModel(entity);
    }

    default ServiceModel findByIdLocalized(ID id) {
        DaoModel entity = getReadDao().findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id));
        return getMapper().toServiceModel(entity);
    }

    // --- Paginated Find ---

    default Page<ServiceModel> find(Pageable pageable) {
        return find(pageable, null);
    }

    default Page<ServiceModel> find(Pageable pageable, String rawQuery) {
        Pageable processedPageable = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                processSort(pageable.getSort())
        );

        Specification<DaoModel> spec = buildFinalSpecification(rawQuery);

        Page<DaoModel> page;
        if (getReadDao().getViewSelectQuery() != null) {
            page = executeViewQuery(processedPageable, spec);
        } else {
            page = getReadDao().findAll(spec, processedPageable);
        }

        return page.map(entity -> {
            if (isDeleted(entity)) return null;
            ServiceModel model = getMapper().toServiceModel(entity);
            maskAdminOnlyFields(model);
            return model;
        });
    }

    default Page<ServiceExtendedModel> findExtended(Pageable pageable) {
        return findExtended(pageable, null);
    }

    default Page<ServiceExtendedModel> findExtended(Pageable pageable, String rawQuery) {
        Pageable processedPageable = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                processSort(pageable.getSort())
        );

        Specification<DaoModel> spec = buildFinalSpecification(rawQuery);

        Page<DaoModel> page;
        if (getReadDao().getViewSelectQuery() != null) {
            page = executeViewQuery(processedPageable, spec);
        } else {
            page = getReadDao().findAll(spec, processedPageable);
        }

        return page.map(entity -> {
            if (isDeleted(entity)) return null;
            return getMapper().toServiceExtendedModel(entity);
        });
    }

    // --- Batch Find ---

    default List<ServiceExtendedModel> findAllByIds(Collection<ID> ids) {
        return getReadDao().findAllByIdIn(ids).stream()
                .filter(entity -> !isDeleted(entity))
                .map(getMapper()::toServiceExtendedModel)
                .toList();
    }

    // --- Count ---

    default long getCount(String rawQuery) {
        Specification<DaoModel> spec = buildFinalSpecification(rawQuery);
        return getReadDao().findAll(spec, Pageable.unpaged()).getTotalElements();
    }

    // --- Query Parsing (with i18n filter field resolution) ---

    default Specification<DaoModel> parseSpecification(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return (root, query, cb) -> null;
        }
        Set<String> i18nProperties = getMapper().getI18nSupportedProperties();
        String localeSuffix = resolveLocaleSuffix();
        return QueryParser.parse(rawQuery, getDaoModelClass(), i18nProperties, localeSuffix);
    }

    default Specification<DaoModel> buildFinalSpecification(String rawQuery) {
        Specification<DaoModel> userSpec = parseSpecification(rawQuery);
        Specification<DaoModel> accessSpec = addRequiredQuery();
        if (accessSpec != null) {
            return Specification.where(userSpec).and(accessSpec);
        }
        return userSpec;
    }

    // --- Extension Points ---

    default Specification<DaoModel> addRequiredQuery() {
        return null;
    }

    default boolean isDeleted(DaoModel entity) {
        return false;
    }

    default Set<String> getAdminOnlyFields() {
        return Set.of();
    }

    // --- I18n Sort Processing ---

    default Sort processSort(Sort sort) {
        if (sort.isUnsorted()) {
            return sort;
        }

        Set<String> i18nProperties = getMapper().getI18nSupportedProperties();
        if (i18nProperties == null || i18nProperties.isEmpty()) {
            return sort;
        }

        String suffix = resolveLocaleSuffix();

        List<Sort.Order> processedOrders = sort.stream()
                .map(order -> {
                    String property = order.getProperty();
                    String resolvedProperty = resolveI18nSortProperty(property, i18nProperties, suffix);
                    return new Sort.Order(order.getDirection(), resolvedProperty);
                })
                .toList();

        return Sort.by(processedOrders);
    }

    // --- I18n Filter Field Resolution ---

    default String resolveI18nFilterField(String field, Set<String> i18nProperties, String localeSuffix) {
        if (i18nProperties == null || i18nProperties.isEmpty()) {
            return field;
        }

        // Handle nested field paths: resolve i18n on the final segment
        if (field.contains(".")) {
            int lastDot = field.lastIndexOf('.');
            String prefix = field.substring(0, lastDot);
            String finalSegment = field.substring(lastDot + 1);
            if (i18nProperties.contains(finalSegment)) {
                return prefix + "." + finalSegment + localeSuffix;
            }
            return field;
        }

        if (i18nProperties.contains(field)) {
            return field + localeSuffix;
        }
        return field;
    }

    // --- View Support ---

    @SuppressWarnings("unchecked")
    default Page<DaoModel> executeViewQuery(Pageable pageable, Specification<DaoModel> spec) {
        String viewSql = getReadDao().getViewSelectQuery();
        if (viewSql == null) {
            throw new UnsupportedOperationException("No view query defined for this DAO");
        }

        Class<DaoModel> entityClass = getDaoModelClass();

        // Execute the main data query with pagination
        Query dataQuery = getEntityManager().createNativeQuery(viewSql, entityClass);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        List<DaoModel> results = dataQuery.getResultList();

        // Execute the count query wrapping the view SQL
        String countSql = "SELECT COUNT(*) FROM (" + viewSql + ") AS view_count";
        Query countQuery = getEntityManager().createNativeQuery(countSql);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        return new PageImpl<>(results, pageable, total);
    }

    // --- Permission Filtering ---

    default void maskAdminOnlyFields(Object model) {
        Set<String> adminFields = getAdminOnlyFields();
        if (adminFields.isEmpty()) return;

        // Check if the caller has admin role
        if (isCallerAdmin()) return;

        // Null out admin-only fields for non-admin callers
        for (String fieldName : adminFields) {
            nullOutField(model, fieldName);
        }
    }

    // --- Private Helper Methods ---

    private String resolveI18nSortProperty(String property, Set<String> i18nProperties, String suffix) {
        // Handle nested field sorting: resolve i18n on the final segment
        if (property.contains(".")) {
            int lastDot = property.lastIndexOf('.');
            String prefix = property.substring(0, lastDot);
            String finalSegment = property.substring(lastDot + 1);
            if (i18nProperties.contains(finalSegment)) {
                return prefix + "." + finalSegment + suffix;
            }
            return property;
        }

        if (i18nProperties.contains(property)) {
            return property + suffix;
        }
        return property;
    }

    private String resolveLocaleSuffix() {
        Locale locale = LocaleContextHolder.getLocale();
        if (locale != null && "ru".equalsIgnoreCase(locale.getLanguage())) {
            return "RU";
        }
        return "PL";
    }

    private boolean isCallerAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> role.equals("ROLE_ADMIN") || role.equals("ADMIN"));
    }

    private void nullOutField(Object model, String fieldName) {
        try {
            Field field = findFieldOnClass(model.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                field.set(model, null);
            }
        } catch (IllegalAccessException e) {
            // Silently skip fields that cannot be accessed
        }
    }

    private Field findFieldOnClass(Class<?> clazz, String fieldName) {
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
