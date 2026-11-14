package com.foremen.service.dashboard.dataset.procurement;

import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.query.QueryContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FOR-11-00 (task 11.5): BUYER dashboard scope for the procurement datasets (Req 14.6, 14.7). */
class PurchaseDatasetsBuyerScopeTest {

    private final CriteriaBuilder cb = mock(CriteriaBuilder.class);
    private final Root<?> root = mock(Root.class);
    private final Predicate alwaysTrue = mock(Predicate.class);

    private QueryContext ctx(String role) {
        return new QueryContext(1L, role, Set.of(10L), false, null, null, null, "ru");
    }

    private void check(Dataset dataset) {
        when(cb.conjunction()).thenReturn(alwaysTrue);
        assertThat(dataset.nullProjectPredicate(ctx("BUYER"), cb, root)).isSameAs(alwaysTrue);
        for (String role : new String[]{"MANAGER", "FOREMAN", "ADMIN", "CLIENT", null}) {
            assertThat(dataset.nullProjectPredicate(ctx(role), cb, root))
                    .as("role %s", role).isNull();
        }
    }

    @Test
    void purchasePositionsDatasetGivesBuyerAlwaysTruePredicate() {
        check(new PurchasePositionsDataset());
    }

    @Test
    void purchaseRequestsDatasetGivesBuyerAlwaysTruePredicate() {
        check(new PurchaseRequestsDataset());
    }
}
