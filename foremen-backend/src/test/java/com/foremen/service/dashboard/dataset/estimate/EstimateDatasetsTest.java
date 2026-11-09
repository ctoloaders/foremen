package com.foremen.service.dashboard.dataset.estimate;

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
 * Validates the estimate / offer / amendment / document datasets against the real JPA metamodel
 * (built without a database) through {@link DatasetRegistry}, and checks the sensitive-field rule.
 *
 * <p><b>Validates: Requirements 6.2, 8.1, 8.2</b>
 */
class EstimateDatasetsTest {

    private static final Set<String> EXPECTED_CODES = Set.of("ESTIMATES", "ESTIMATE_LINES", "OFFERS",
            "OFFER_NEGOTIATION_ROUNDS", "AMENDMENTS", "AMENDMENT_LINES", "SIGNABLE_DOCUMENTS",
            "SIGNABLE_DOCUMENT_TYPES", "DOCUMENT_TEMPLATES", "COMPANY_PROFILE");

    /** Field-name fragments that must never be exposed (design "sensitive field rule"). */
    private static final List<String> DENY = List.of("justification", "explanation", "clientcomment",
            "note", "description", "documenturi", "contenthash", "sourceref", "storageuri", "email",
            "nip", "regon", "passwordhash", "phone");

    private static SessionFactory sessionFactory;
    private static Metamodel metamodel;

    private final List<Dataset> datasets = List.of(new EstimatesDataset(), new EstimateLinesDataset(),
            new OffersDataset(), new OfferNegotiationRoundsDataset(), new AmendmentsDataset(),
            new AmendmentLinesDataset(), new SignableDocumentsDataset(), new SignableDocumentTypesDataset(),
            new DocumentTemplatesDataset(), new CompanyProfileDataset());

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
    @DisplayName("all ten datasets register and every path resolves against the JPA metamodel")
    void registryAcceptsAllDatasets() {
        DatasetRegistry registry = new DatasetRegistry(datasets, metamodel);
        assertThat(registry.all().stream().map(Dataset::code).collect(Collectors.toSet()))
                .isEqualTo(EXPECTED_CODES);
    }

    @Test
    @DisplayName("project-scoped datasets declare a project path, catalog datasets are company-wide")
    void scopes() {
        Set<String> companyWide = Set.of("SIGNABLE_DOCUMENT_TYPES", "DOCUMENT_TEMPLATES", "COMPANY_PROFILE");
        for (Dataset d : datasets) {
            assertThat(d.projectPath().isPresent()).as(d.code()).isEqualTo(!companyWide.contains(d.code()));
            assertThat(d.requiredResources()).as(d.code()).isNotEmpty();
        }
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
