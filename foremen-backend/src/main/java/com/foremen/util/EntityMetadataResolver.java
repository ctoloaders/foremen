package com.foremen.util;

import com.foremen.controller.model.MetadataResponse;
import jakarta.persistence.Embedded;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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

    /**
     * Registry resolving a target entity type to its API resource code and base path. Set once at
     * startup by {@link ReferenceResourceRegistry} (a Spring bean) so this static utility can emit
     * {@link MetadataResponse.ReferenceInfo} without becoming Spring-managed itself. When null
     * (e.g. in plain unit tests that do not boot Spring), reference descriptors are simply not
     * emitted and metadata behaves exactly as before this feature.
     */
    private static volatile ReferenceResourceRegistry referenceRegistry;

    private EntityMetadataResolver() {}

    /**
     * Wires the reference registry used to resolve reference descriptors. Called once at startup.
     * Clears the metadata cache so any entries built before the registry was available are
     * recomputed with reference descriptors.
     */
    static void setReferenceRegistry(ReferenceResourceRegistry registry) {
        referenceRegistry = registry;
        CACHE.clear();
    }

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
            MetadataResponse.ReferenceInfo reference = null;

            if (isNestedEntity(field)) {
                MetadataResponse nestedMeta = resolve(field.getType());
                nested = nestedMeta.fields();
                if (isReferenceAssociation(field)) {
                    reference = buildReferenceInfo(field.getName(), field.getType(), nestedMeta);
                }
            } else if (isEntityCollection(field)) {
                // @OneToMany to a managed entity: advertise the collection's element metadata with
                // its reference leaves qualified by the collection name (e.g. members.user.id,
                // members.projectRole.code) so the frontend can build nested-collection filters.
                // SpecificationBuilder already turns such a collection segment into a JOIN + distinct.
                Class<?> elementType = collectionElementType(field);
                if (elementType != null) {
                    nested = buildCollectionElementFields(elementType, field.getName());
                    dataType = MetadataResponse.DataType.STRING; // collection has no scalar type
                }
            }

            if (isI18n) {
                emittedI18nBases.add(name);
            }

            fields.add(new MetadataResponse.FieldInfo(name, dataType, isI18n, nested, reference));
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

    /**
     * A {@code @OneToMany} collection whose element type is itself a managed entity (i.e. carries
     * its own {@code @ManyToOne}/{@code @OneToOne} reference leaves worth advertising). Collections
     * of scalars/embeddables are not treated as reference-bearing collections.
     */
    private static boolean isEntityCollection(Field field) {
        if (!field.isAnnotationPresent(OneToMany.class)) {
            return false;
        }
        Class<?> element = collectionElementType(field);
        return element != null && element.isAnnotationPresent(jakarta.persistence.Entity.class);
    }

    /**
     * Resolves the element type of a {@code List<X>}/{@code Set<X>} collection field from its
     * generic type parameter. Returns null when the type argument is not a concrete class
     * (e.g. a raw collection or a wildcard/parameterized type argument).
     */
    private static Class<?> collectionElementType(Field field) {
        Type generic = field.getGenericType();
        if (generic instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> element) {
                return element;
            }
        }
        return null;
    }

    /**
     * Builds the advertised nested fields for a {@code @OneToMany} collection element, emitting a
     * reference descriptor for each of the element's {@code @ManyToOne}/{@code @OneToOne} leaves
     * with a <em>collection-qualified</em> {@code idPath}. For a {@code members} collection of
     * {@code ProjectMemberEntity} this yields {@code members.user.id} (target {@code users}) and
     * {@code members.projectRole.code} (target {@code roles}). The leaf key segment is a unique
     * {@code code} natural key when the target entity has one, otherwise {@code id}; this composes
     * with the {@link com.foremen.service.query.SpecificationBuilder} nested-collection join
     * (collection segment → JOIN + {@code distinct}, then {@code .user.id}/{@code .projectRole.code}).
     */
    private static List<MetadataResponse.FieldInfo> buildCollectionElementFields(
            Class<?> elementType, String collectionName) {
        List<MetadataResponse.FieldInfo> result = new ArrayList<>();
        for (Field leaf : getAllFields(elementType)) {
            String leafName = leaf.getName();
            if (leafName.startsWith("$$") || leafName.equals("serialVersionUID")) {
                continue;
            }
            MetadataResponse.DataType leafType = mapJavaType(leaf.getType());
            MetadataResponse.ReferenceInfo reference = null;
            if (isReferenceAssociation(leaf)) {
                Class<?> targetType = leaf.getType();
                MetadataResponse targetMeta = resolve(targetType);
                String keySegment = referenceKeySegment(targetType);
                String idPath = collectionName + "." + leafName + "." + keySegment;
                reference = new MetadataResponse.ReferenceInfo(
                        resolveTargetResource(targetType), resolveOptionsPath(targetType),
                        resolveLabelField(targetMeta).name(), resolveLabelField(targetMeta).i18n(),
                        idPath);
            }
            result.add(new MetadataResponse.FieldInfo(leafName, leafType, false, null, reference));
        }
        return result;
    }

    /**
     * The filter key segment for a reference target: a unique {@code code} natural key when the
     * target entity declares one (e.g. {@code RoleEntity.code} → {@code roles} filtered by code),
     * otherwise the primary-key {@code id}.
     */
    private static String referenceKeySegment(Class<?> targetType) {
        return hasUniqueCodeField(targetType) ? "code" : "id";
    }

    private static boolean hasUniqueCodeField(Class<?> targetType) {
        for (Field f : getAllFields(targetType)) {
            if (f.getName().equals("code")) {
                jakarta.persistence.Column column = f.getAnnotation(jakarta.persistence.Column.class);
                return column != null && column.unique();
            }
        }
        return false;
    }

    private static String resolveTargetResource(Class<?> targetType) {
        ReferenceResourceRegistry registry = referenceRegistry;
        if (registry != null) {
            var ref = registry.lookup(targetType);
            if (ref.isPresent()) {
                return ref.get().resourceCode();
            }
        }
        return null;
    }

    private static String resolveOptionsPath(Class<?> targetType) {
        ReferenceResourceRegistry registry = referenceRegistry;
        if (registry != null) {
            var ref = registry.lookup(targetType);
            if (ref.isPresent()) {
                return ref.get().basePath();
            }
        }
        return null;
    }

    /**
     * A reference association is a {@code @ManyToOne}/{@code @OneToOne} to another managed entity.
     * {@code @Embedded} value objects are nested but are not references (no target resource / id).
     */
    private static boolean isReferenceAssociation(Field field) {
        return field.isAnnotationPresent(ManyToOne.class) ||
               field.isAnnotationPresent(OneToOne.class);
    }

    /**
     * Builds the reference descriptor for a {@code @ManyToOne}/{@code @OneToOne} field.
     *
     * <ul>
     *   <li>{@code idPath} = {@code fieldName + ".id"} so it composes with the query grammar.</li>
     *   <li>{@code targetResource}/{@code optionsPath} come from the {@link #referenceRegistry}
     *       when it knows the target type; otherwise they are left null (reference still emitted
     *       so the frontend gets the id path and label, but with no options endpoint).</li>
     *   <li>{@code labelField}/{@code labelI18n} are resolved from the target entity's already
     *       computed metadata: prefer a {@code name} field that is i18n; else the first STRING
     *       field; else {@code id}.</li>
     * </ul>
     */
    private static MetadataResponse.ReferenceInfo buildReferenceInfo(
            String fieldName, Class<?> targetType, MetadataResponse targetMeta) {

        String idPath = fieldName + ".id";

        String targetResource = null;
        String optionsPath = null;
        ReferenceResourceRegistry registry = referenceRegistry;
        if (registry != null) {
            var ref = registry.lookup(targetType);
            if (ref.isPresent()) {
                targetResource = ref.get().resourceCode();
                optionsPath = ref.get().basePath();
            }
        }

        LabelField label = resolveLabelField(targetMeta);

        return new MetadataResponse.ReferenceInfo(
                targetResource, optionsPath, label.name(), label.i18n(), idPath);
    }

    private record LabelField(String name, boolean i18n) {}

    /**
     * Resolves the display label field of a target entity from its resolved metadata:
     * an i18n {@code name} field wins; otherwise the first STRING field; otherwise {@code id}.
     */
    private static LabelField resolveLabelField(MetadataResponse targetMeta) {
        for (MetadataResponse.FieldInfo f : targetMeta.fields()) {
            if (f.name().equals("name") && f.i18n()) {
                return new LabelField("name", true);
            }
        }
        for (MetadataResponse.FieldInfo f : targetMeta.fields()) {
            if (f.dataType() == MetadataResponse.DataType.STRING) {
                return new LabelField(f.name(), f.i18n());
            }
        }
        return new LabelField("id", false);
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
