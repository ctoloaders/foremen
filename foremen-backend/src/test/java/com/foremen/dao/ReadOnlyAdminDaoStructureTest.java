package com.foremen.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based structural tests for {@link ReadOnlyAdminDao}.
 * Validates interface contract defined in Requirements 1.1–1.11.
 */
@DisplayName("ReadOnlyAdminDao — Structure Tests")
class ReadOnlyAdminDaoStructureTest {

    private static final Class<?> DAO_CLASS = ReadOnlyAdminDao.class;

    @Test
    @DisplayName("Should have @NoRepositoryBean annotation")
    void shouldHaveNoRepositoryBeanAnnotation() {
        assertNotNull(
                DAO_CLASS.getAnnotation(NoRepositoryBean.class),
                "ReadOnlyAdminDao must be annotated with @NoRepositoryBean"
        );
    }

    @Test
    @DisplayName("Should extend Repository<DaoModel, ID>")
    void shouldExtendRepository() {
        assertTrue(
                Repository.class.isAssignableFrom(DAO_CLASS),
                "ReadOnlyAdminDao must extend org.springframework.data.repository.Repository"
        );
    }

    @Test
    @DisplayName("Should have exactly 2 type parameters (DaoModel, ID)")
    void shouldHaveExactlyTwoTypeParameters() {
        TypeVariable<?>[] typeParams = DAO_CLASS.getTypeParameters();
        assertEquals(2, typeParams.length, "ReadOnlyAdminDao must have exactly 2 type parameters");
        assertEquals("DaoModel", typeParams[0].getName());
        assertEquals("ID", typeParams[1].getName());
    }

    @Test
    @DisplayName("Should reside in com.foremen.dao package")
    void shouldResideInCorrectPackage() {
        assertEquals(
                "com.foremen.dao",
                DAO_CLASS.getPackageName(),
                "ReadOnlyAdminDao must reside in com.foremen.dao package"
        );
    }

    @Test
    @DisplayName("Should declare findAll(Pageable) method")
    void shouldDeclareFindAllPageable() throws NoSuchMethodException {
        Method method = DAO_CLASS.getDeclaredMethod("findAll", Pageable.class);
        assertNotNull(method);
        assertFalse(method.isDefault(), "findAll(Pageable) should not be a default method");
    }

    @Test
    @DisplayName("Should declare findAll(Specification, Pageable) method")
    void shouldDeclareFindAllSpecificationPageable() throws NoSuchMethodException {
        Method method = DAO_CLASS.getDeclaredMethod("findAll", Specification.class, Pageable.class);
        assertNotNull(method);
        assertFalse(method.isDefault(), "findAll(Specification, Pageable) should not be a default method");
    }

    @Test
    @DisplayName("Should declare findById(Object) method")
    void shouldDeclareFindById() throws NoSuchMethodException {
        Method method = DAO_CLASS.getDeclaredMethod("findById", Object.class);
        assertNotNull(method);
        assertFalse(method.isDefault(), "findById(Object) should not be a default method");
    }

    @Test
    @DisplayName("Should declare count() method")
    void shouldDeclareCount() throws NoSuchMethodException {
        Method method = DAO_CLASS.getDeclaredMethod("count");
        assertNotNull(method);
        assertFalse(method.isDefault(), "count() should not be a default method");
    }

    @Test
    @DisplayName("Should declare findAllByIdIn(Collection) method")
    void shouldDeclareFindAllByIdIn() throws NoSuchMethodException {
        Method method = DAO_CLASS.getDeclaredMethod("findAllByIdIn", Collection.class);
        assertNotNull(method);
        assertFalse(method.isDefault(), "findAllByIdIn(Collection) should not be a default method");
    }

    @Test
    @DisplayName("Should declare getViewSelectQuery() as a default method")
    void shouldDeclareGetViewSelectQueryAsDefault() throws NoSuchMethodException {
        Method method = DAO_CLASS.getDeclaredMethod("getViewSelectQuery");
        assertNotNull(method);
        assertTrue(method.isDefault(), "getViewSelectQuery() must be a default method");
    }

    @Test
    @DisplayName("Should declare exactly 6 methods")
    void shouldDeclareExactlySixMethods() {
        Method[] declaredMethods = DAO_CLASS.getDeclaredMethods();
        assertEquals(6, declaredMethods.length,
                "ReadOnlyAdminDao must declare exactly 6 methods. Found: " +
                        Arrays.stream(declaredMethods).map(Method::getName).collect(Collectors.joining(", "))
        );
    }

    @Test
    @DisplayName("Should NOT declare any write methods (save, delete, flush, etc.)")
    void shouldNotDeclareWriteMethods() {
        Set<String> writeMethodPatterns = Set.of(
                "save", "saveAll", "saveAndFlush", "saveAllAndFlush",
                "delete", "deleteById", "deleteAll", "deleteAllById", "deleteAllInBatch",
                "flush"
        );

        Set<String> declaredMethodNames = Arrays.stream(DAO_CLASS.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        for (String writeMethod : writeMethodPatterns) {
            assertFalse(
                    declaredMethodNames.contains(writeMethod),
                    "ReadOnlyAdminDao must NOT declare write method: " + writeMethod
            );
        }

        // Also check inherited methods from Repository (which should be none, since Repository is a marker)
        Set<String> allMethodNames = Arrays.stream(DAO_CLASS.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        for (String writeMethod : writeMethodPatterns) {
            assertFalse(
                    allMethodNames.contains(writeMethod),
                    "ReadOnlyAdminDao must NOT inherit write method: " + writeMethod
            );
        }
    }

    @Test
    @DisplayName("Should be an interface")
    void shouldBeAnInterface() {
        assertTrue(DAO_CLASS.isInterface(), "ReadOnlyAdminDao must be an interface");
    }
}
