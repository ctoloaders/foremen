package com.foremen.dao.model;

import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for BaseEntity hashCode() consistency across state transitions.
 * <p>
 * Property 2: HashCode Consistency Across State Transitions
 * For any entity instance, hashCode() returns the same value regardless of whether
 * the entity is in transient state (id = null) or managed/detached state (id assigned).
 * <p>
 * <b>Validates: Requirements 6.2</b>
 */
class BaseEntityHashCodeConsistencyPropertyTest {

    static class TestEntity extends BaseEntity {}

    @Property(tries = 100)
    @Label("hashCode remains unchanged after setting ID")
    void hashCodeUnchangedAfterSettingId(@ForAll @LongRange(min = 1) long id) {
        TestEntity entity = new TestEntity();
        int hashBefore = entity.hashCode();

        entity.setId(id);
        int hashAfter = entity.hashCode();

        assertEquals(hashBefore, hashAfter,
                "hashCode must not change when id transitions from null to " + id);
    }

    @Property(tries = 100)
    @Label("hashCode is stable across multiple calls")
    void hashCodeStableAcrossMultipleCalls(@ForAll @LongRange(min = 1) long id) {
        TestEntity entity = new TestEntity();
        entity.setId(id);

        int first = entity.hashCode();
        int second = entity.hashCode();
        int third = entity.hashCode();

        assertEquals(first, second, "hashCode must be stable across consecutive calls");
        assertEquals(second, third, "hashCode must be stable across consecutive calls");
    }
}
