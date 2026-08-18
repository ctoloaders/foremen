package com.foremen.dao.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BaseEntity equals() and hashCode() implementation.
 * Validates Requirements: 6.1, 6.2, 6.3, 6.4, 6.5
 */
class BaseEntityEqualsTest {

    // Two concrete subclasses for cross-type testing
    static class TestEntityA extends BaseEntity {}
    static class TestEntityB extends BaseEntity {}

    @Test
    @DisplayName("Transient entity (null id) is not equal to another transient entity")
    void transientEntitiesAreNotEqual() {
        TestEntityA a = new TestEntityA();
        TestEntityA b = new TestEntityA();

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("entity.equals(null) returns false")
    void equalsNullReturnsFalse() {
        TestEntityA entity = new TestEntityA();

        assertFalse(entity.equals(null));
    }

    @Test
    @DisplayName("Two entities with same non-null ID are equal")
    void entitiesWithSameIdAreEqual() {
        TestEntityA a = new TestEntityA();
        a.setId(1L);
        TestEntityA b = new TestEntityA();
        b.setId(1L);

        assertEquals(a, b);
    }

    @Test
    @DisplayName("Two entities with different non-null IDs are not equal")
    void entitiesWithDifferentIdsAreNotEqual() {
        TestEntityA a = new TestEntityA();
        a.setId(1L);
        TestEntityA b = new TestEntityA();
        b.setId(2L);

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("Entities of different concrete types with same ID are not equal")
    void entitiesOfDifferentTypesWithSameIdAreNotEqual() {
        TestEntityA a = new TestEntityA();
        a.setId(1L);
        TestEntityB b = new TestEntityB();
        b.setId(1L);

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("hashCode() is consistent before and after setting ID")
    void hashCodeConsistentBeforeAndAfterSettingId() {
        TestEntityA entity = new TestEntityA();
        int hashBefore = entity.hashCode();

        entity.setId(42L);
        int hashAfter = entity.hashCode();

        assertEquals(hashBefore, hashAfter);
    }
}
