package com.foremen.service.dashboard.dataset.procurement;

import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.dashboard.query.QueryContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FOR-11-03 task 8.1: company-wide scope and field exposure of the proforma and order datasets (R20.3, R27.2). */
class ProformaOrderDatasetsScopeTest {

    private static final Set<String> FORBIDDEN = Set.of("unitPurchaseNet", "purchaseVatRate", "lines",
            "documents", "note", "paidNote", "returnReason", "cancelReason", "orderLink", "pdfObjectKey",
            "paidBy", "issuedBy", "referenceNumber", "number");

    private final CriteriaBuilder cb = mock(CriteriaBuilder.class);
    private final Root<?> root = mock(Root.class);
    private final Predicate alwaysTrue = mock(Predicate.class);

    private QueryContext ctx(String role) {
        return new QueryContext(1L, role, Set.of(10L), false, null, null, null, "ru");
    }

    private void check(Dataset dataset) {
        when(cb.conjunction()).thenReturn(alwaysTrue);
        for (String role : new String[]{"BUYER", "FINANCIER"}) {
            assertThat(dataset.nullProjectPredicate(ctx(role), cb, root)).as(role).isSameAs(alwaysTrue);
        }
        for (String role : new String[]{"MANAGER", "FOREMAN", "ADMIN", "CLIENT", null}) {
            assertThat(dataset.nullProjectPredicate(ctx(role), cb, root)).as("role %s", role).isNull();
        }
        assertThat(dataset.projectPath()).contains("project.id");
        for (FieldDescriptor f : dataset.allFields()) {
            for (String segment : f.name().split("\\.")) {
                assertThat(FORBIDDEN).as("%s field %s", dataset.code(), f.name()).doesNotContain(segment);
            }
        }
    }

    @Test
    void clientProformasDatasetIsCompanyWideForBuyerAndFinancier() {
        Dataset d = new ClientProformasDataset();
        assertThat(d.requiredResources()).containsExactly("CLIENT_PAYMENTS");
        check(d);
    }

    @Test
    void externalOrdersDatasetIsCompanyWideForBuyerAndFinancier() {
        Dataset d = new ExternalOrdersDataset();
        assertThat(d.requiredResources()).containsExactly("EXTERNAL_ORDERS");
        check(d);
    }
}
