package com.foremen.dao.model;

import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for BaseEntity equals/hashCode contract.
 * Validates: Requirements 6.1, 6.2, 6.3
 */
class BaseEntityEqualsHashCodePropertyTest {

    /**
     * Concrete test subclass of BaseEntity for property testing.
     */
    static class TestEntity extends BaseEntity {
    }

    private TestEntity entityWithId(long id) {
        TestEntity entity = new TestEntity();
        entity.setId(id);
        return entity;
    }

    // --- Property 1: Equals Contract (reflexivity, symmetry, transitivity) ---

    /**
     * Validates: Requirements 6.1, 6.3
     * Reflexivity: entity.equals(entity) is always true for entities with non-null IDs.
     */
    @Property(tries = 100)
    void reflexivity(@ForAll @LongRange(min = 1, max = Long.MAX_VALUE) long id) {
        TestEntity entity = entityWithId(id);
        assertThat(entity.equals(entity)).isTrue();
    }

    /**
     * Validates: Requirements 6.1, 6.3
     * Symmetry: if a.equals(b) then b.equals(a).
     */
    @Property(tries = 100)
    void symmetry(@ForAll @LongRange(min = 1, max = Long.MAX_VALUE) long id) {
        TestEntity a = entityWithId(id);
        TestEntity b = entityWithId(id);

        assertThat(a.equals(b)).isTrue();
        assertThat(b.equals(a)).isTrue();
    }

    /**
     * Validates: Requirements 6.1, 6.3
     * Transitivity: if a.equals(b) and b.equals(c) then a.equals(c).
     */
    @Property(tries = 100)
    void transitivity(@ForAll @LongRange(min = 1, max = Long.MAX_VALUE) long id) {
        TestEntity a = entityWithId(id);
        TestEntity b = entityWithId(id);
        TestEntity c = entityWithId(id);

        assertThat(a.equals(b)).isTrue();
        assertThat(b.equals(c)).isTrue();
        assertThat(a.equals(c)).isTrue();
    }

    /**
     * Validates: Requirements 6.1, 6.3
     * ID-based equality: same ID → equal, different ID → not equal.
     */
    @Property(tries = 100)
    void idBasedEquality(
            @ForAll @LongRange(min = 1, max = Long.MAX_VALUE) long id1,
            @ForAll @LongRange(min = 1, max = Long.MAX_VALUE) long id2) {
        Assume.that(id1 != id2);

        TestEntity a = entityWithId(id1);
        TestEntity b = entityWithId(id1);
        TestEntity c = entityWithId(id2);

        assertThat(a.equals(b)).isTrue();
        assertThat(a.equals(c)).isFalse();
    }

    // --- Property 3: Equals/HashCode Contract Compatibility ---

    /**
     * Validates: Requirements 6.1, 6.2
     * For any two entities where a.equals(b), verify a.hashCode() == b.hashCode().
     */
    @Property(tries = 100)
    void equalsImpliesHashCodeEqual(@ForAll @LongRange(min = 1, max = Long.MAX_VALUE) long id) {
        TestEntity a = entityWithId(id);
        TestEntity b = entityWithId(id);

        assertThat(a.equals(b)).isTrue();
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
