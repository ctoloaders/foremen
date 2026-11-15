package com.foremen.service.dashboard.dataset.procurement;

import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.dashboard.query.QueryContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FOR-11-04 task 3.15 (R18.2): field catalog, scope and sensitive-field exclusions of {@code SHIPMENTS}. */
class ShipmentsDatasetTest {

    private static final Set<String> FORBIDDEN = Set.of("trackingNumber", "trackingUrl", "shopOrderLink",
            "driverName", "driverPhone", "note", "text", "lastEventText");

    private final ShipmentsDataset dataset = new ShipmentsDataset();

    @Test
    void exposesOnlyTheAllowedFields() {
        assertThat(dataset.code()).isEqualTo("SHIPMENTS");
        assertThat(dataset.requiredResources()).containsExactly("SHIPMENTS");
        assertThat(dataset.projectPath()).contains("project.id");
        assertThat(dataset.defaultDateField()).contains("etaDate");
        List<String> names = dataset.allFields().stream().map(FieldDescriptor::name).toList();
        assertThat(names).contains("status", "deliveryMethod", "carrierCode", "etaDate", "shippedAt",
                "deliveredAt", "degraded", "id");
        for (String n : names) {
            for (String segment : n.split("\\.")) {
                assertThat(FORBIDDEN).as(n).doesNotContain(segment);
            }
        }
        assertThat(dataset.allFields().stream().filter(FieldDescriptor::aggregatable).map(FieldDescriptor::name))
                .contains("id").doesNotContain("etaDate", "degraded");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buyerAndFinancierAreCompanyWide() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Root<?> root = mock(Root.class);
        Predicate all = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(all);
        for (String role : new String[]{"BUYER", "FINANCIER"}) {
            assertThat(dataset.nullProjectPredicate(
                    new QueryContext(1L, role, Set.of(10L), false, null, null, null, "ru"), cb, root))
                    .as(role).isSameAs(all);
        }
        for (String role : new String[]{"MANAGER", "FOREMAN", "ADMIN", null}) {
            assertThat(dataset.nullProjectPredicate(
                    new QueryContext(1L, role, Set.of(10L), false, null, null, null, "ru"), cb, root))
                    .as("role %s", role).isNull();
        }
    }
}
