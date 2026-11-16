package com.foremen.service.clientpayment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pure rules of the client-payment list: per-role kind rule, stable default sort, LIKE escaping.
 *
 * <p><b>Validates: Requirements 1.5, 1.7, 3.1</b>
 */
class ClientPaymentQueryRulesTest {

    @Test
    void roleKindRule() {
        assertThat(ClientPaymentVisibility.forRole("ADMIN")).isEqualTo(ClientPaymentVisibility.ALL);
        assertThat(ClientPaymentVisibility.forRole("MANAGER")).isEqualTo(ClientPaymentVisibility.ALL);
        assertThat(ClientPaymentVisibility.forRole("FINANCIER")).isEqualTo(ClientPaymentVisibility.ALL);
        assertThat(ClientPaymentVisibility.forRole("FOREMAN")).isEqualTo(ClientPaymentVisibility.WORKS_ONLY);
        assertThat(ClientPaymentVisibility.forRole("BUYER")).isEqualTo(ClientPaymentVisibility.MATERIALS_ONLY);
        assertThat(ClientPaymentVisibility.forRole("CLIENT")).isEqualTo(ClientPaymentVisibility.CLIENT);
        assertThat(ClientPaymentVisibility.forRole("WORKER")).isEqualTo(ClientPaymentVisibility.NONE);
        assertThat(ClientPaymentVisibility.forRole(null)).isEqualTo(ClientPaymentVisibility.NONE);
    }

    @Test
    void allowsKindPerVisibility() {
        assertThat(ClientPaymentVisibility.WORKS_ONLY.allows(ClientPaymentKind.WORKS)).isTrue();
        assertThat(ClientPaymentVisibility.WORKS_ONLY.allows(ClientPaymentKind.MATERIALS)).isFalse();
        assertThat(ClientPaymentVisibility.MATERIALS_ONLY.allows(ClientPaymentKind.WORKS)).isFalse();
        assertThat(ClientPaymentVisibility.NONE.allows(ClientPaymentKind.WORKS)).isFalse();
    }

    @Test
    void clientSeesOnlyIssuedAndLaterMaterials() {
        assertThat(ClientPaymentVisibility.CLIENT_MATERIALS_STATUSES)
                .containsExactlyInAnyOrder("ISSUED", "PARTIALLY_PAID", "PAID");
    }

    @Test
    void unsortedPageGetsDueDateNullsLastThenId() {
        Pageable p = ClientPaymentQueryService.withStableSort(PageRequest.of(2, 20));
        assertThat(p.getPageNumber()).isEqualTo(2);
        assertThat(p.getPageSize()).isEqualTo(20);
        Sort.Order due = p.getSort().getOrderFor("dueDate");
        assertThat(due).isNotNull();
        assertThat(due.isAscending()).isTrue();
        assertThat(due.getNullHandling()).isEqualTo(Sort.NullHandling.NULLS_LAST);
        assertThat(p.getSort().stream().map(Sort.Order::getProperty)).containsExactly("dueDate", "id");
    }

    @Test
    void callerSortGetsIdTieBreaker() {
        Pageable p = ClientPaymentQueryService.withStableSort(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "amountGross")));
        assertThat(p.getSort().stream().map(Sort.Order::getProperty)).containsExactly("amountGross", "id");
    }

    @Test
    void likeWildcardsAreEscaped() {
        assertThat(ClientPaymentSpecifications.escapeLike("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");
    }
}
