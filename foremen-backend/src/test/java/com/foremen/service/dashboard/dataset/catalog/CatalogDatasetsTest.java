package com.foremen.service.dashboard.dataset.catalog;

import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.DatasetRegistry;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import jakarta.persistence.Entity;
import jakarta.persistence.Converter;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Embeddable;
import jakarta.persistence.metamodel.Metamodel;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the catalog and materials datasets against the real JPA metamodel
 * (built without a database) through {@link DatasetRegistry}, and checks the sensitive-field rule.
 *
 * <p><b>Validates: Requirements 6.2, 8.1, 8.2</b>
 */
class CatalogDatasetsTest {

    private static final Set<String> EXPECTED_CODES = Set.of("WORK_CATALOG", "WORK_PRICES",
            "WORK_MATERIAL_CONSUMPTION", "CONSTRUCTION_MATERIALS", "FINISHING_MATERIALS");

    /** Field-name fragments that must never be exposed (design "sensitive field rule"). */
    private static final List<String> DENY = List.of("justification", "sourcedoc", "sourceurl", "sourceref",
            "website", "image", "photo", "link", "features", "email", "phone");

    private static SessionFactory sessionFactory;
    private static Metamodel metamodel;

    private final List<Dataset> datasets = List.of(new WorkCatalogDataset(), new WorkPricesDataset(),
            new WorkMaterialConsumptionDataset(), new ConstructionMaterialsDataset(),
            new FinishingMaterialsDataset());

    @BeforeAll
    static void buildMetamodel() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .build();
        MetadataSources sources = new MetadataSources(registry);
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(MappedSuperclass.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Embeddable.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Converter.class));
        for (BeanDefinition bd : scanner.findCandidateComponents("com.foremen.dao.model")) {
            try {
                sources.addAnnotatedClass(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        sessionFactory = sources.buildMetadata().buildSessionFactory();
        metamodel = sessionFactory.getMetamodel();
    }

    @AfterAll
    static void close() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    @Test
    @DisplayName("all five datasets register and every path resolves against the JPA metamodel")
    void registryAcceptsAllDatasets() {
        DatasetRegistry registry = new DatasetRegistry(datasets, metamodel);
        assertThat(registry.all().stream().map(Dataset::code).collect(Collectors.toSet()))
                .isEqualTo(EXPECTED_CODES);
    }

    @Test
    @DisplayName("datasets are company-wide, gated by their resource, and have a valid default date field")
    void scopes() {
        for (Dataset d : datasets) {
            assertThat(d.projectPath()).as(d.code()).isEmpty();
            assertThat(d.requiredResources()).as(d.code()).hasSize(1);
        }
        assertThat(datasets.stream().filter(d -> d.code().equals("FINISHING_MATERIALS")).findFirst().get()
                .requiredResources()).containsExactly("MATERIALS_FINISHING");
    }

    @Test
    @DisplayName("sensitive fields are not exposed")
    void noSensitiveFields() {
        for (Dataset d : datasets) {
            for (FieldDescriptor f : d.allFields()) {
                String path = (f.jpaPath() == null ? f.name() : f.jpaPath()).toLowerCase();
                for (String bad : DENY) {
                    assertThat(path).as(d.code() + "." + f.name()).doesNotContain(bad);
                }
            }
        }
    }
}
