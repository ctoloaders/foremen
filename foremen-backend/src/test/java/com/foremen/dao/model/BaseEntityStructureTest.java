package com.foremen.dao.model;

import jakarta.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based structural verification for BaseEntity.
 * Validates annotations, field declarations, and class modifiers without loading Spring context.
 */
class BaseEntityStructureTest {

    @Test
    void classIsAbstract() {
        assertTrue(Modifier.isAbstract(BaseEntity.class.getModifiers()),
                "BaseEntity must be declared abstract");
    }

    @Test
    void hasMappedSuperclassAnnotation() {
        assertNotNull(BaseEntity.class.getAnnotation(MappedSuperclass.class),
                "BaseEntity must be annotated with @MappedSuperclass");
    }

    @Test
    void hasEntityListenersWithAuditingEntityListener() {
        EntityListeners listeners = BaseEntity.class.getAnnotation(EntityListeners.class);
        assertNotNull(listeners, "BaseEntity must be annotated with @EntityListeners");

        Class<?>[] listenerClasses = listeners.value();
        assertEquals(1, listenerClasses.length);
        assertEquals(AuditingEntityListener.class, listenerClasses[0],
                "@EntityListeners must reference AuditingEntityListener.class");
    }

    @Test
    void idFieldHasIdAnnotation() throws NoSuchFieldException {
        Field idField = BaseEntity.class.getDeclaredField("id");
        assertNotNull(idField.getAnnotation(Id.class),
                "id field must be annotated with @Id");
    }

    @Test
    void idFieldHasGeneratedValueWithIdentityStrategy() throws NoSuchFieldException {
        Field idField = BaseEntity.class.getDeclaredField("id");
        GeneratedValue generatedValue = idField.getAnnotation(GeneratedValue.class);
        assertNotNull(generatedValue, "id field must be annotated with @GeneratedValue");
        assertEquals(GenerationType.IDENTITY, generatedValue.strategy(),
                "@GeneratedValue strategy must be IDENTITY");
    }

    @Test
    void createdDateFieldHasCreatedDateAnnotation() throws NoSuchFieldException {
        Field field = BaseEntity.class.getDeclaredField("createdDate");
        assertNotNull(field.getAnnotation(CreatedDate.class),
                "createdDate field must be annotated with @CreatedDate");
    }

    @Test
    void createdDateFieldHasColumnConstraints() throws NoSuchFieldException {
        Field field = BaseEntity.class.getDeclaredField("createdDate");
        Column column = field.getAnnotation(Column.class);
        assertNotNull(column, "createdDate field must be annotated with @Column");
        assertFalse(column.nullable(), "@Column on createdDate must have nullable = false");
        assertFalse(column.updatable(), "@Column on createdDate must have updatable = false");
    }

    @Test
    void createdByFieldHasCreatedByAnnotation() throws NoSuchFieldException {
        Field field = BaseEntity.class.getDeclaredField("createdBy");
        assertNotNull(field.getAnnotation(CreatedBy.class),
                "createdBy field must be annotated with @CreatedBy");
    }

    @Test
    void createdByFieldHasColumnUpdatableFalse() throws NoSuchFieldException {
        Field field = BaseEntity.class.getDeclaredField("createdBy");
        Column column = field.getAnnotation(Column.class);
        assertNotNull(column, "createdBy field must be annotated with @Column");
        assertFalse(column.updatable(), "@Column on createdBy must have updatable = false");
    }

    @Test
    void updatedDateFieldHasLastModifiedDateAnnotation() throws NoSuchFieldException {
        Field field = BaseEntity.class.getDeclaredField("updatedDate");
        assertNotNull(field.getAnnotation(LastModifiedDate.class),
                "updatedDate field must be annotated with @LastModifiedDate");
    }

    @Test
    void updatedByFieldHasLastModifiedByAnnotation() throws NoSuchFieldException {
        Field field = BaseEntity.class.getDeclaredField("updatedBy");
        assertNotNull(field.getAnnotation(LastModifiedBy.class),
                "updatedBy field must be annotated with @LastModifiedBy");
    }
}
