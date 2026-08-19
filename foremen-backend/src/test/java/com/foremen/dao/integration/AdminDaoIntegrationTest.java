package com.foremen.dao.integration;

import com.foremen.dao.AdminDao;
import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.dao.model.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.CrudRepository;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for {@link AdminDao} using Testcontainers with PostgreSQL.
 * Validates full CRUD lifecycle, batch operations, pagination with sorting,
 * and @NoRepositoryBean proxy prevention.
 * <p>
 * Validates: Requirements 2.2, 2.3, 2.4, 3.1, 3.7
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigurationPackage(basePackages = "com.foremen")
@EnableJpaRepositories(considerNestedRepositories = true, basePackageClasses = AdminDaoIntegrationTest.class)
@Import(PostgresTestcontainerConfig.class)
@ActiveProfiles("integration-test")
class AdminDaoIntegrationTest {

    @Configuration
    @EnableJpaAuditing
    static class TestAuditConfig {
        @Bean
        AuditorAware<String> auditorAware() {
            return () -> Optional.of("test-user");
        }
    }

    /**
     * Test-scoped entity extending BaseEntity for integration testing.
     */
    @Entity
    @Table(name = "test_admin_entity")
    @Getter
    @Setter
    @NoArgsConstructor
    static class TestAdminEntity extends BaseEntity {
        private String name;
        private int priority;

        TestAdminEntity(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }
    }

    /**
     * Concrete DAO extending AdminDao for CRUD testing.
     * Also extends CrudRepository to expose save/delete methods,
     * since PagingAndSortingRepository no longer extends CrudRepository in Spring Data 4.
     */
    interface TestAdminDao extends AdminDao<TestAdminEntity, Long>, CrudRepository<TestAdminEntity, Long> {
    }

    @Autowired
    private TestAdminDao testAdminDao;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void cleanUp() {
        testAdminDao.deleteAll();
    }

    @Test
    @DisplayName("CRUD lifecycle: save → findById → update (save again) → delete → verify gone")
    void shouldPerformFullCrudLifecycle() {
        // Save
        TestAdminEntity entity = new TestAdminEntity("item-1", 5);
        TestAdminEntity saved = testAdminDao.save(entity);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedDate()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo("test-user");

        // FindById
        Optional<TestAdminEntity> found = testAdminDao.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("item-1");
        assertThat(found.get().getPriority()).isEqualTo(5);

        // Update (save again)
        found.get().setName("item-1-updated");
        found.get().setPriority(10);
        TestAdminEntity updated = testAdminDao.save(found.get());
        assertThat(updated.getName()).isEqualTo("item-1-updated");
        assertThat(updated.getPriority()).isEqualTo(10);
        assertThat(updated.getId()).isEqualTo(saved.getId());

        // Delete
        testAdminDao.deleteById(updated.getId());

        // Verify gone
        Optional<TestAdminEntity> deleted = testAdminDao.findById(saved.getId());
        assertThat(deleted).isEmpty();
    }

    @Test
    @DisplayName("Batch saveAll and findAllByIdIn combination")
    void shouldSaveAllAndFindAllByIdIn() {
        List<TestAdminEntity> entities = List.of(
                new TestAdminEntity("batch-1", 1),
                new TestAdminEntity("batch-2", 2),
                new TestAdminEntity("batch-3", 3)
        );

        Iterable<TestAdminEntity> savedIterable = testAdminDao.saveAll(entities);
        List<TestAdminEntity> saved = new ArrayList<>();
        savedIterable.forEach(saved::add);
        assertThat(saved).hasSize(3);

        List<Long> ids = saved.stream().map(BaseEntity::getId).toList();
        List<TestAdminEntity> found = testAdminDao.findAllByIdIn(ids);
        assertThat(found).hasSize(3);
        assertThat(found).extracting(TestAdminEntity::getName)
                .containsExactlyInAnyOrder("batch-1", "batch-2", "batch-3");
    }

    @Test
    @DisplayName("findAll(Pageable) with sorting by priority descending")
    void shouldFindAllWithPaginationAndSorting() {
        testAdminDao.save(new TestAdminEntity("low", 1));
        testAdminDao.save(new TestAdminEntity("mid", 5));
        testAdminDao.save(new TestAdminEntity("high", 10));
        testAdminDao.save(new TestAdminEntity("very-high", 15));

        // Page 0, size 2, sorted by priority descending
        Page<TestAdminEntity> page = testAdminDao.findAll(
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "priority")));

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getName()).isEqualTo("very-high");
        assertThat(page.getContent().get(1).getName()).isEqualTo("high");

        // Page 1
        Page<TestAdminEntity> page2 = testAdminDao.findAll(
                PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "priority")));
        assertThat(page2.getContent()).hasSize(2);
        assertThat(page2.getContent().get(0).getName()).isEqualTo("mid");
        assertThat(page2.getContent().get(1).getName()).isEqualTo("low");
    }

    @Test
    @DisplayName("@NoRepositoryBean prevents direct proxy for ReadOnlyAdminDao")
    void shouldNotCreateBeanForReadOnlyAdminDao() {
        assertThrows(NoSuchBeanDefinitionException.class, () ->
                applicationContext.getBean(ReadOnlyAdminDao.class));
    }

    @Test
    @DisplayName("@NoRepositoryBean prevents direct proxy for AdminDao")
    void shouldNotCreateBeanForAdminDao() {
        // AdminDao is @NoRepositoryBean, so no standalone proxy is created.
        // The only bean assignable to AdminDao should be our concrete TestAdminDao.
        String[] beanNames = applicationContext.getBeanNamesForType(AdminDao.class);
        assertThat(beanNames).hasSize(1);
        assertThat(applicationContext.getBean(beanNames[0])).isInstanceOf(TestAdminDao.class);
    }
}
