package com.foremen.service.integration;

import com.foremen.dao.AdminDao;
import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.dao.model.BaseEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ReadOnlyAdminService;
import com.foremen.dao.integration.PostgresTestcontainerConfig;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for nested field filtering, collection joins, deeply nested paths,
 * and view query support.
 *
 * Validates: Requirements 9.1-9.4, 17.1-17.6
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigurationPackage(basePackages = "com.foremen")
@EnableJpaRepositories(considerNestedRepositories = true, basePackageClasses = NestedFieldIntegrationTest.class)
@Import(PostgresTestcontainerConfig.class)
@ActiveProfiles("integration-test")
class NestedFieldIntegrationTest {

    @Configuration
    @EnableJpaAuditing
    static class TestAuditConfig {
        @Bean
        AuditorAware<String> auditorAware() {
            return () -> Optional.of("test-user");
        }
    }

    // ==================== Test Entities ====================

    @Entity
    @Table(name = "nested_test_address")
    @Getter
    @Setter
    @NoArgsConstructor
    static class TestAddressEntity extends BaseEntity {
        @Column(name = "city")
        private String city;

        @Column(name = "street")
        private String street;
    }

    @Entity
    @Table(name = "nested_test_item")
    @Getter
    @Setter
    @NoArgsConstructor
    static class TestItemEntity extends BaseEntity {
        @Column(name = "status")
        private String status;

        @Column(name = "name")
        private String name;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "parent_id")
        private TestParentEntity parent;
    }

    @Entity
    @Table(name = "nested_test_parent")
    @Getter
    @Setter
    @NoArgsConstructor
    static class TestParentEntity extends BaseEntity {
        @Column(name = "name")
        private String name;

        @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
        @JoinColumn(name = "address_id")
        private TestAddressEntity address;

        @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
        private List<TestItemEntity> items = new ArrayList<>();

        void addItem(TestItemEntity item) {
            items.add(item);
            item.setParent(this);
        }
    }

    @Entity
    @Table(name = "nested_test_manager")
    @Getter
    @Setter
    @NoArgsConstructor
    static class TestManagerEntity extends BaseEntity {
        @Column(name = "name")
        private String name;

        @Column(name = "email")
        private String email;
    }

    @Entity
    @Table(name = "nested_test_project")
    @Getter
    @Setter
    @NoArgsConstructor
    static class TestProjectEntity extends BaseEntity {
        @Column(name = "title")
        private String title;

        @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
        @JoinColumn(name = "manager_id")
        private TestManagerEntity manager;
    }

    // ==================== Test DAOs ====================

    interface TestParentDao extends AdminDao<TestParentEntity, Long> {
    }

    interface TestProjectDao extends AdminDao<TestProjectEntity, Long> {
    }

    // ==================== Test Models ====================

    @Getter
    @Setter
    @NoArgsConstructor
    static class ParentModel {
        private Long id;
        private String name;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    static class ParentExtendedModel {
        private Long id;
        private String name;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    static class ProjectModel {
        private Long id;
        private String title;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    static class ProjectExtendedModel {
        private Long id;
        private String title;
    }

    // ==================== Test Setup ====================

    @Autowired
    private TestParentDao parentDao;

    @Autowired
    private TestProjectDao projectDao;

    @Autowired
    private EntityManager entityManager;

    private ParentReadOnlyService parentService;
    private ProjectReadOnlyService projectService;
    private ParentViewService parentViewService;

    @BeforeEach
    void setUp() {
        parentService = new ParentReadOnlyService(parentDao, entityManager);
        projectService = new ProjectReadOnlyService(projectDao, entityManager);
        parentViewService = new ParentViewService(parentDao, entityManager);
    }

    // ==================== Nested Field Filtering ====================

    @Test
    @DisplayName("Nested field filtering: address.city==Warsaw returns only matching entity")
    void nestedFieldFiltering_addressCity_returnsFiltered() {
        // Given
        TestAddressEntity warsawAddress = new TestAddressEntity();
        warsawAddress.setCity("Warsaw");
        warsawAddress.setStreet("Main St");

        TestParentEntity parent1 = new TestParentEntity();
        parent1.setName("Parent in Warsaw");
        parent1.setAddress(warsawAddress);

        TestAddressEntity krakowAddress = new TestAddressEntity();
        krakowAddress.setCity("Krakow");
        krakowAddress.setStreet("Old St");

        TestParentEntity parent2 = new TestParentEntity();
        parent2.setName("Parent in Krakow");
        parent2.setAddress(krakowAddress);

        parentDao.save(parent1);
        parentDao.save(parent2);
        entityManager.flush();
        entityManager.clear();

        // When
        Pageable pageable = PageRequest.of(0, 10);
        Page<ParentModel> result = parentService.find(pageable, "address.city==Warsaw");

        // Then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getName()).isEqualTo("Parent in Warsaw");
    }

    @Test
    @DisplayName("Nested field filtering: address.street with contains operator")
    void nestedFieldFiltering_addressStreetContains_returnsFiltered() {
        // Given
        TestAddressEntity address1 = new TestAddressEntity();
        address1.setCity("Gdansk");
        address1.setStreet("Long Market Street");

        TestParentEntity parent1 = new TestParentEntity();
        parent1.setName("Parent on Market");
        parent1.setAddress(address1);

        TestAddressEntity address2 = new TestAddressEntity();
        address2.setCity("Sopot");
        address2.setStreet("Beach Road");

        TestParentEntity parent2 = new TestParentEntity();
        parent2.setName("Parent on Beach");
        parent2.setAddress(address2);

        parentDao.save(parent1);
        parentDao.save(parent2);
        entityManager.flush();
        entityManager.clear();

        // When
        Pageable pageable = PageRequest.of(0, 10);
        Page<ParentModel> result = parentService.find(pageable, "address.street~ct~market");

        // Then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getName()).isEqualTo("Parent on Market");
    }

    // ==================== Collection Join Filtering ====================

    @Test
    @DisplayName("Collection join: items.status==done returns only parent with done items, no duplicates")
    void collectionJoinFiltering_itemsStatus_returnsDistinctFiltered() {
        // Given: Parent1 has items with status "done" and "pending"
        TestParentEntity parent1 = new TestParentEntity();
        parent1.setName("Parent with done items");

        TestItemEntity item1 = new TestItemEntity();
        item1.setStatus("done");
        item1.setName("Item 1");
        parent1.addItem(item1);

        TestItemEntity item2 = new TestItemEntity();
        item2.setStatus("pending");
        item2.setName("Item 2");
        parent1.addItem(item2);

        // Parent2 has only "pending" items
        TestParentEntity parent2 = new TestParentEntity();
        parent2.setName("Parent with only pending");

        TestItemEntity item3 = new TestItemEntity();
        item3.setStatus("pending");
        item3.setName("Item 3");
        parent2.addItem(item3);

        parentDao.save(parent1);
        parentDao.save(parent2);
        entityManager.flush();
        entityManager.clear();

        // When
        Pageable pageable = PageRequest.of(0, 10);
        Page<ParentModel> result = parentService.find(pageable, "items.status==done");

        // Then: only parent1 returned, and only once (DISTINCT applied)
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getName()).isEqualTo("Parent with done items");
    }

    @Test
    @DisplayName("Collection join: multiple done items do not produce duplicates due to DISTINCT")
    void collectionJoinFiltering_multipleDoneItems_noDuplicates() {
        // Given: Parent with multiple "done" items
        TestParentEntity parent = new TestParentEntity();
        parent.setName("Multi-done parent");

        TestItemEntity item1 = new TestItemEntity();
        item1.setStatus("done");
        item1.setName("Done Item 1");
        parent.addItem(item1);

        TestItemEntity item2 = new TestItemEntity();
        item2.setStatus("done");
        item2.setName("Done Item 2");
        parent.addItem(item2);

        TestItemEntity item3 = new TestItemEntity();
        item3.setStatus("done");
        item3.setName("Done Item 3");
        parent.addItem(item3);

        parentDao.save(parent);
        entityManager.flush();
        entityManager.clear();

        // When
        Pageable pageable = PageRequest.of(0, 10);
        Page<ParentModel> result = parentService.find(pageable, "items.status==done");

        // Then: parent returned only once despite 3 matching items
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getName()).isEqualTo("Multi-done parent");
    }

    // ==================== Deeply Nested Field ====================

    @Test
    @DisplayName("Deeply nested: manager.name~ct~john filters projects by manager name")
    void deeplyNestedFiltering_managerName_returnsFiltered() {
        // Given
        TestManagerEntity manager1 = new TestManagerEntity();
        manager1.setName("John Smith");
        manager1.setEmail("john@example.com");

        TestProjectEntity project1 = new TestProjectEntity();
        project1.setTitle("Project Alpha");
        project1.setManager(manager1);

        TestManagerEntity manager2 = new TestManagerEntity();
        manager2.setName("Jane Doe");
        manager2.setEmail("jane@example.com");

        TestProjectEntity project2 = new TestProjectEntity();
        project2.setTitle("Project Beta");
        project2.setManager(manager2);

        projectDao.save(project1);
        projectDao.save(project2);
        entityManager.flush();
        entityManager.clear();

        // When
        Pageable pageable = PageRequest.of(0, 10);
        Page<ProjectModel> result = projectService.find(pageable, "manager.name~ct~john");

        // Then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getTitle()).isEqualTo("Project Alpha");
    }

    @Test
    @DisplayName("Deeply nested: manager.email==jane@example.com filters exactly")
    void deeplyNestedFiltering_managerEmail_returnsExactMatch() {
        // Given
        TestManagerEntity manager1 = new TestManagerEntity();
        manager1.setName("John");
        manager1.setEmail("john@example.com");

        TestProjectEntity project1 = new TestProjectEntity();
        project1.setTitle("Project One");
        project1.setManager(manager1);

        TestManagerEntity manager2 = new TestManagerEntity();
        manager2.setName("Jane");
        manager2.setEmail("jane@example.com");

        TestProjectEntity project2 = new TestProjectEntity();
        project2.setTitle("Project Two");
        project2.setManager(manager2);

        projectDao.save(project1);
        projectDao.save(project2);
        entityManager.flush();
        entityManager.clear();

        // When
        Pageable pageable = PageRequest.of(0, 10);
        Page<ProjectModel> result = projectService.find(pageable, "manager.email==jane@example.com");

        // Then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getTitle()).isEqualTo("Project Two");
    }

    // ==================== View Query ====================

    @Test
    @DisplayName("View query: find with view DAO executes native SQL and returns results")
    void viewQuery_findWithViewDao_returnsResults() {
        // Given: persist some parent entities
        TestParentEntity parent1 = new TestParentEntity();
        parent1.setName("View Entity 1");

        TestParentEntity parent2 = new TestParentEntity();
        parent2.setName("View Entity 2");

        parentDao.save(parent1);
        parentDao.save(parent2);
        entityManager.flush();
        entityManager.clear();

        // When: using the view-based service (which has getViewSelectQuery != null)
        Pageable pageable = PageRequest.of(0, 10);
        Page<ParentModel> result = parentViewService.find(pageable);

        // Then: results are returned via native SQL view path
        assertThat(result.getContent()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("View query: pagination works with view DAO")
    void viewQuery_pagination_worksCorrectly() {
        // Given: persist multiple entities
        for (int i = 0; i < 5; i++) {
            TestParentEntity parent = new TestParentEntity();
            parent.setName("Paginated Entity " + i);
            parentDao.save(parent);
        }
        entityManager.flush();
        entityManager.clear();

        // When: request page of size 2
        Pageable pageable = PageRequest.of(0, 2);
        Page<ParentModel> result = parentViewService.find(pageable);

        // Then: page contains max 2 results with correct total
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isGreaterThanOrEqualTo(5);
    }

    // ==================== Inner Service Implementations ====================

    /**
     * Service for testing nested field and collection join filtering on TestParentEntity.
     */
    private static class ParentReadOnlyService
            implements ReadOnlyAdminService<ParentModel, ParentExtendedModel, TestParentEntity, Long> {

        private final TestParentDao dao;
        private final EntityManager em;

        ParentReadOnlyService(TestParentDao dao, EntityManager em) {
            this.dao = dao;
            this.em = em;
        }

        @Override
        public ServiceToDaoMapper<TestParentEntity, ParentModel, ParentExtendedModel> getMapper() {
            return new InlineParentMapper();
        }

        @Override
        public ReadOnlyAdminDao<TestParentEntity, Long> getReadDao() {
            return dao;
        }

        @Override
        public EntityManager getEntityManager() {
            return em;
        }

        @Override
        public Class<TestParentEntity> getDaoModelClass() {
            return TestParentEntity.class;
        }
    }

    /**
     * Service for testing deeply nested field filtering on TestProjectEntity.
     */
    private static class ProjectReadOnlyService
            implements ReadOnlyAdminService<ProjectModel, ProjectExtendedModel, TestProjectEntity, Long> {

        private final TestProjectDao dao;
        private final EntityManager em;

        ProjectReadOnlyService(TestProjectDao dao, EntityManager em) {
            this.dao = dao;
            this.em = em;
        }

        @Override
        public ServiceToDaoMapper<TestProjectEntity, ProjectModel, ProjectExtendedModel> getMapper() {
            return new InlineProjectMapper();
        }

        @Override
        public ReadOnlyAdminDao<TestProjectEntity, Long> getReadDao() {
            return dao;
        }

        @Override
        public EntityManager getEntityManager() {
            return em;
        }

        @Override
        public Class<TestProjectEntity> getDaoModelClass() {
            return TestProjectEntity.class;
        }
    }

    /**
     * Service that wraps the parent DAO to provide a non-null getViewSelectQuery(),
     * triggering the native SQL execution path.
     */
    private static class ParentViewService
            implements ReadOnlyAdminService<ParentModel, ParentExtendedModel, TestParentEntity, Long> {

        private final ReadOnlyAdminDao<TestParentEntity, Long> viewDao;
        private final EntityManager em;

        ParentViewService(TestParentDao actualDao, EntityManager em) {
            this.viewDao = new ViewDaoWrapper(actualDao);
            this.em = em;
        }

        @Override
        public ServiceToDaoMapper<TestParentEntity, ParentModel, ParentExtendedModel> getMapper() {
            return new InlineParentMapper();
        }

        @Override
        public ReadOnlyAdminDao<TestParentEntity, Long> getReadDao() {
            return viewDao;
        }

        @Override
        public EntityManager getEntityManager() {
            return em;
        }

        @Override
        public Class<TestParentEntity> getDaoModelClass() {
            return TestParentEntity.class;
        }
    }

    /**
     * Wraps a real DAO to provide a non-null getViewSelectQuery() for view query testing.
     */
    private static class ViewDaoWrapper implements ReadOnlyAdminDao<TestParentEntity, Long> {

        private final TestParentDao delegate;

        ViewDaoWrapper(TestParentDao delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getViewSelectQuery() {
            return "SELECT * FROM nested_test_parent";
        }

        @Override
        public Page<TestParentEntity> findAll(Pageable pageable) {
            return delegate.findAll(pageable);
        }

        @Override
        public Page<TestParentEntity> findAll(Specification<TestParentEntity> specification, Pageable pageable) {
            return delegate.findAll(specification, pageable);
        }

        @Override
        public Optional<TestParentEntity> findById(Long id) {
            return delegate.findById(id);
        }

        @Override
        public long count() {
            return delegate.count();
        }

        @Override
        public List<TestParentEntity> findAllByIdIn(Collection<Long> entityIds) {
            return delegate.findAllByIdIn(entityIds);
        }
    }

    // ==================== Inline Mappers ====================

    private static class InlineParentMapper
            implements ServiceToDaoMapper<TestParentEntity, ParentModel, ParentExtendedModel> {

        @Override
        public ParentModel toServiceModel(TestParentEntity source) {
            ParentModel model = new ParentModel();
            model.setId(source.getId());
            model.setName(source.getName());
            return model;
        }

        @Override
        public ParentExtendedModel toServiceExtendedModel(TestParentEntity source) {
            ParentExtendedModel model = new ParentExtendedModel();
            model.setId(source.getId());
            model.setName(source.getName());
            return model;
        }

        @Override
        public TestParentEntity toCreateDaoModel(ParentExtendedModel source) {
            TestParentEntity entity = new TestParentEntity();
            entity.setName(source.getName());
            return entity;
        }

        @Override
        public void updateFields(ParentExtendedModel source, TestParentEntity target) {
            if (source.getName() != null) {
                target.setName(source.getName());
            }
        }

        @Override
        public Set<String> getI18nSupportedProperties() {
            return Set.of();
        }
    }

    private static class InlineProjectMapper
            implements ServiceToDaoMapper<TestProjectEntity, ProjectModel, ProjectExtendedModel> {

        @Override
        public ProjectModel toServiceModel(TestProjectEntity source) {
            ProjectModel model = new ProjectModel();
            model.setId(source.getId());
            model.setTitle(source.getTitle());
            return model;
        }

        @Override
        public ProjectExtendedModel toServiceExtendedModel(TestProjectEntity source) {
            ProjectExtendedModel model = new ProjectExtendedModel();
            model.setId(source.getId());
            model.setTitle(source.getTitle());
            return model;
        }

        @Override
        public TestProjectEntity toCreateDaoModel(ProjectExtendedModel source) {
            TestProjectEntity entity = new TestProjectEntity();
            entity.setTitle(source.getTitle());
            return entity;
        }

        @Override
        public void updateFields(ProjectExtendedModel source, TestProjectEntity target) {
            if (source.getTitle() != null) {
                target.setTitle(source.getTitle());
            }
        }

        @Override
        public Set<String> getI18nSupportedProperties() {
            return Set.of();
        }
    }
}
