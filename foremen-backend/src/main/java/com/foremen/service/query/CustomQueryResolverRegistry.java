package com.foremen.service.query;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny registry of {@link CustomQueryResolver}s keyed by {@code (entityClass, leadingSegment)}.
 *
 * <p>Both halves of the {@link CustomQueryResolver Custom_Predicate_Flow} consult this one registry so
 * they resolve to the <b>same</b> resolver instance and cannot drift: {@link SpecificationBuilder}
 * looks up the filter half, and the concrete service that overrides its read looks up the sort half.
 * A given entity may register at most one resolver per leading segment (FOR-04-12b design, "Registry").
 *
 * <p>The registry is generic — it hard-codes no entity type or segment name. This spec adds exactly one
 * registration, {@code (WorkPriceEntity, "prices")}, wired by the resolver's own configuration; other
 * entities can register their own synthetic pivots later without touching the shared query translator.
 */
@Component
public class CustomQueryResolverRegistry {

    /** Composite key identifying a resolver: the entity type and the leading query-path segment. */
    private record Key(Class<?> entityClass, String leadingSegment) {
    }

    private final Map<Key, CustomQueryResolver<?>> resolvers = new ConcurrentHashMap<>();

    /**
     * Wires this registry into the static {@link SpecificationBuilder} translator so its
     * {@code buildPredicate} can consult the registered resolvers for the custom-predicate filter
     * branch. Done once at startup; {@code SpecificationBuilder} is a static utility invoked from the
     * static {@link QueryParser} pipeline, so it cannot receive the bean by constructor injection.
     */
    @PostConstruct
    void wireIntoSpecificationBuilder() {
        SpecificationBuilder.setCustomQueryResolverRegistry(this);
    }

    /**
     * Registers {@code resolver} as the owner of {@code (entityClass, resolver.property())}.
     *
     * @throws IllegalStateException if a resolver is already registered for the same
     *                               {@code (entityClass, leadingSegment)} pair
     */
    public <T> void register(Class<T> entityClass, CustomQueryResolver<T> resolver) {
        Objects.requireNonNull(entityClass, "entityClass");
        Objects.requireNonNull(resolver, "resolver");
        Key key = new Key(entityClass, resolver.property());
        CustomQueryResolver<?> previous = resolvers.putIfAbsent(key, resolver);
        if (previous != null && previous != resolver) {
            throw new IllegalStateException(
                    "A CustomQueryResolver is already registered for entity "
                            + entityClass.getName() + " and segment '" + resolver.property() + "'");
        }
    }

    /**
     * Returns the resolver owning {@code (entityClass, leadingSegment)}, or empty when none is
     * registered.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<CustomQueryResolver<T>> find(Class<T> entityClass, String leadingSegment) {
        if (entityClass == null || leadingSegment == null) {
            return Optional.empty();
        }
        return Optional.ofNullable((CustomQueryResolver<T>) resolvers.get(new Key(entityClass, leadingSegment)));
    }

    /**
     * Returns {@code true} when a resolver is registered for {@code (entityClass, leadingSegment)}.
     * Convenience for the callers that only need to know whether to branch into the custom flow.
     */
    public boolean isRegistered(Class<?> entityClass, String leadingSegment) {
        if (entityClass == null || leadingSegment == null) {
            return false;
        }
        return resolvers.containsKey(new Key(entityClass, leadingSegment));
    }
}
