package com.foremen.dao.integration;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ReadOnlyAdminDao} verifying read operations
 * against a real PostgreSQL database via Testcontainers.
 *
 * Validates: Requirements 3.3, 3.4, 3.5, 3.6, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigurationPackage(basePackages = "com.foremen")
@EnableJpaRepositories(considerNestedRepositories = true, basePackageClasses = {
        ReadOnlyAdminDaoIntegrationTest.TestReadOnlyDao.class,
        ReadOnlyAdminDaoIntegrationTest.TestHelperRepository.class
})
@Import(PostgresTestcontainerConfig.class)
@ActiveProfiles("integration-test")
class ReadOnlyAdminDaoIntegrationTest {

    /**
     * Test-scoped entity extending BaseEntity for integration testing.
     */
    @Entity
    @Table(name = "test_read_only_entity")
    @Getter
    @Setter
    @NoArgsConstructor
    static class TestReadOnlyEntity extends BaseEntity {

        private String name;

        TestReadOnlyEntity(String name) {
            this.name = name;
        }
    }

    /**
     * Concrete ReadOnlyAdminDao for the test entity — this is what we're testing.
     */
    interface TestReadOnlyDao extends ReadOnlyAdminDao<TestReadOnlyEntity, Long> {
    }

    /**
     * Helper JpaRepository for persisting test data (since ReadOnlyAdminDao has no save methods).
     */
    interface TestHelperRepository extends JpaRepository<TestReadOnlyEntity, Long> {
    }

    /**
     * Test configuration providing JPA auditing support.
     */
    @Configuration
    @EnableJpaAuditing
    static class TestAuditConfig {
        @Bean
        public AuditorAware<String> auditorAware() {
            return () -> Optional.of("test-user");
        }
    }

    @Autowired
    private TestReadOnlyDao readOnlyDao;

    @Autowired
    private TestHelperRepository helperRepository;

    @BeforeEach
    void setUp() {
        helperRepository.deleteAll();
    }

    @Test
    @DisplayName("findAllByIdIn with all existing IDs returns exactly N entities (Property 3)")
    void findAllByIdIn_allExistingIds_returnsExactlyNEntities() {
        // Arrange
        TestReadOnlyEntity entity1 = helperRepository.save(new TestReadOnlyEntity("Entity 1"));
        TestReadOnlyEntity entity2 = helperRepository.save(new TestReadOnlyEntity("Entity 2"));
        TestReadOnlyEntity entity3 = helperRepository.save(new TestReadOnlyEntity("Entity 3"));

        List<Long> ids = List.of(entity1.getId(), entity2.getId(), entity3.getId());

        // Act
        List<TestReadOnlyEntity> result = readOnlyDao.findAllByIdIn(ids);

        // Assert
        assertThat(result).hasSize(3);
        assertThat(result).extracting(TestReadOnlyEntity::getId)
                .containsExactlyInAnyOrder(entity1.getId(), entity2.getId(), entity3.getId());
    }

    @Test
    @DisplayName("findAllByIdIn with empty collection returns empty list")
    void findAllByIdIn_emptyCollection_returnsEmptyList() {
        // Arrange
        helperRepository.save(new TestReadOnlyEntity("Entity 1"));

        // Act
        List<TestReadOnlyEntity> result = readOnlyDao.findAllByIdIn(Collections.emptyList());

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findAllByIdIn with partial matches returns only existing entities (Property 4)")
    void findAllByIdIn_partialMatches_returnsOnlyExistingEntities() {
        // Arrange
        TestReadOnlyEntity entity1 = helperRepository.save(new TestReadOnlyEntity("Entity 1"));
        TestReadOnlyEntity entity2 = helperRepository.save(new TestReadOnlyEntity("Entity 2"));
        helperRepository.save(new TestReadOnlyEntity("Entity 3"));

        // Mix real IDs with non-existing ones
        List<Long> ids = List.of(entity1.getId(), entity2.getId(), 99999L, 88888L);

        // Act
        List<TestReadOnlyEntity> result = readOnlyDao.findAllByIdIn(ids);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result).extracting(TestReadOnlyEntity::getId)
                .containsExactlyInAnyOrder(entity1.getId(), entity2.getId());
    }

    @Test
    @DisplayName("findAll(Pageable) returns correct page structure")
    void findAll_pageable_returnsCorrectPageStructure() {
        // Arrange
        for (int i = 0; i < 7; i++) {
            helperRepository.save(new TestReadOnlyEntity("Entity " + i));
        }

        // Act
        Page<TestReadOnlyEntity> page = readOnlyDao.findAll(PageRequest.of(0, 3));

        // Assert
        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(7);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getNumber()).isZero();
        assertThat(page.getSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("findById with existing entity returns present Optional")
    void findById_existingEntity_returnsPresent() {
        // Arrange
        TestReadOnlyEntity saved = helperRepository.save(new TestReadOnlyEntity("Find Me"));

        // Act
        Optional<TestReadOnlyEntity> result = readOnlyDao.findById(saved.getId());

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Find Me");
        assertThat(result.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("findById with non-existing ID returns empty Optional")
    void findById_nonExistingId_returnsEmpty() {
        // Act
        Optional<TestReadOnlyEntity> result = readOnlyDao.findById(99999L);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("count() returns correct number of persisted entities")
    void count_returnsPersistedCount() {
        // Arrange
        helperRepository.save(new TestReadOnlyEntity("Entity 1"));
        helperRepository.save(new TestReadOnlyEntity("Entity 2"));
        helperRepository.save(new TestReadOnlyEntity("Entity 3"));
        helperRepository.save(new TestReadOnlyEntity("Entity 4"));
        helperRepository.save(new TestReadOnlyEntity("Entity 5"));

        // Act
        long count = readOnlyDao.count();

        // Assert
        assertThat(count).isEqualTo(5);
    }

    @Test
    @DisplayName("getViewSelectQuery() default returns null")
    void getViewSelectQuery_defaultReturnsNull() {
        // Act
        String result = readOnlyDao.getViewSelectQuery();

        // Assert
        assertThat(result).isNull();
    }
}
