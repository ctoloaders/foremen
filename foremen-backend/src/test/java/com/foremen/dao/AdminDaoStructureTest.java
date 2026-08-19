package com.foremen.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.PagingAndSortingRepository;

import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based structural tests for {@link AdminDao}.
 * Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8
 */
@DisplayName("AdminDao — Structure Verification")
class AdminDaoStructureTest {

    private final Class<?> adminDaoClass = AdminDao.class;

    @Test
    @DisplayName("Should have @NoRepositoryBean annotation")
    void shouldHaveNoRepositoryBeanAnnotation() {
        assertTrue(
                adminDaoClass.isAnnotationPresent(NoRepositoryBean.class),
                "AdminDao must be annotated with @NoRepositoryBean"
        );
    }

    @Test
    @DisplayName("Should extend ReadOnlyAdminDao, PagingAndSortingRepository, and JpaSpecificationExecutor")
    void shouldExtendRequiredInterfaces() {
        assertTrue(
                ReadOnlyAdminDao.class.isAssignableFrom(adminDaoClass) || 
                hasDirectSuperInterface(adminDaoClass, ReadOnlyAdminDao.class),
                "AdminDao must extend ReadOnlyAdminDao"
        );
        assertTrue(
                PagingAndSortingRepository.class.isAssignableFrom(adminDaoClass),
                "AdminDao must extend PagingAndSortingRepository"
        );
        assertTrue(
                JpaSpecificationExecutor.class.isAssignableFrom(adminDaoClass),
                "AdminDao must extend JpaSpecificationExecutor"
        );
    }

    @Test
    @DisplayName("Should declare parent interfaces in correct order: ReadOnlyAdminDao first, PagingAndSortingRepository second, JpaSpecificationExecutor third")
    void shouldHaveCorrectInterfaceOrder() {
        Type[] genericInterfaces = adminDaoClass.getGenericInterfaces();

        assertEquals(3, genericInterfaces.length,
                "AdminDao must extend exactly 3 interfaces");

        String firstInterface = genericInterfaces[0].getTypeName();
        String secondInterface = genericInterfaces[1].getTypeName();
        String thirdInterface = genericInterfaces[2].getTypeName();

        assertTrue(firstInterface.contains("ReadOnlyAdminDao"),
                "First parent interface must be ReadOnlyAdminDao, but was: " + firstInterface);
        assertTrue(secondInterface.contains("PagingAndSortingRepository"),
                "Second parent interface must be PagingAndSortingRepository, but was: " + secondInterface);
        assertTrue(thirdInterface.contains("JpaSpecificationExecutor"),
                "Third parent interface must be JpaSpecificationExecutor, but was: " + thirdInterface);
    }

    @Test
    @DisplayName("Should have exactly 2 type parameters (DaoModel, ID)")
    void shouldHaveExactlyTwoTypeParameters() {
        TypeVariable<?>[] typeParameters = adminDaoClass.getTypeParameters();

        assertEquals(2, typeParameters.length,
                "AdminDao must have exactly 2 type parameters");
        assertEquals("DaoModel", typeParameters[0].getName(),
                "First type parameter must be named 'DaoModel'");
        assertEquals("ID", typeParameters[1].getName(),
                "Second type parameter must be named 'ID'");
    }

    @Test
    @DisplayName("Should reside in com.foremen.dao package")
    void shouldResideInCorrectPackage() {
        assertEquals("com.foremen.dao", adminDaoClass.getPackageName(),
                "AdminDao must reside in com.foremen.dao package");
    }

    @Test
    @DisplayName("Should declare zero methods (empty body)")
    void shouldDeclareZeroMethods() {
        assertEquals(0, adminDaoClass.getDeclaredMethods().length,
                "AdminDao must have an empty body with zero declared methods");
    }

    private boolean hasDirectSuperInterface(Class<?> clazz, Class<?> expectedInterface) {
        for (Class<?> iface : clazz.getInterfaces()) {
            if (iface.equals(expectedInterface)) {
                return true;
            }
        }
        return false;
    }
}
