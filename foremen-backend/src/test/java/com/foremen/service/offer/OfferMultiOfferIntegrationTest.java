package com.foremen.service.offer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.service.OfferService;

/**
 * FOR-05-07b task 4.1: several non-terminal offers per project (A2, A4).
 * Repeatability: per-run ids, rows removed by {@link OfferExplorationSupport#cleanUpExploration()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class OfferMultiOfferIntegrationTest extends OfferExplorationSupport {

    @Autowired
    private OfferService offerService;
    @Autowired
    private OfferSnapshotService offerSnapshotService;

    private OfferStatus statusOf(Long offerId) {
        return tx().execute(s -> entityManager.find(OfferEntity.class, offerId).getStatus());
    }

    private long auditCount(Long offerId, String operation) {
        return tx().execute(s -> entityManager.createQuery(
                "select count(a) from AuditLogEntity a where a.entityClass = 'OfferEntity' "
                        + "and a.entityId = :id and a.operation = :op", Long.class)
                .setParameter("id", offerId).setParameter("op", operation).getSingleResult());
    }

    @Test
    @DisplayName("a DRAFT and a SENT offer coexist and are both listed with their own status")
    void draftAndSentCoexist() {
        Fx f = emptyEstimateFixture("M1");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity first = offerService.prepareOffer(f.projectId, "First");
        offerService.send(first.getId());
        OfferEntity second = offerService.prepareOffer(f.projectId, "Second");

        assertThat(statusOf(first.getId())).isEqualTo(OfferStatus.SENT);
        assertThat(statusOf(second.getId())).isEqualTo(OfferStatus.DRAFT);
        assertThat(offerSnapshotService.listByProject(f.projectId, false))
                .extracting(OfferSnapshotViews.OfferSummary::id)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        // The CLIENT list contains only the offer it has seen.
        assertThat(offerSnapshotService.listByProject(f.projectId, true))
                .extracting(OfferSnapshotViews.OfferSummary::id).containsExactly(first.getId());
    }

    @Test
    @DisplayName("approving one of several offers leaves the other offers unchanged (Round 2 D2)")
    void approveLeavesOthersUnchanged() {
        Fx f = emptyEstimateFixture("M2");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity a = offerService.prepareOffer(f.projectId, "A");
        OfferEntity b = offerService.prepareOffer(f.projectId, "B");
        OfferEntity c = offerService.prepareOffer(f.projectId, "C");
        offerService.send(a.getId());
        offerService.send(b.getId());
        // c stays DRAFT.
        authenticateAs(createClientUser(), "ROLE_CLIENT");
        OfferEntity approved = offerService.approve(a.getId());

        assertThat(approved.getStatus()).isEqualTo(OfferStatus.APPROVED);
        assertThat(statusOf(a.getId())).isEqualTo(OfferStatus.APPROVED);
        assertThat(statusOf(b.getId())).isEqualTo(OfferStatus.SENT);
        assertThat(statusOf(c.getId())).isEqualTo(OfferStatus.DRAFT);
        assertThat(auditCount(b.getId(), "SYSTEM_WITHDRAW_ON_APPROVE")).isZero();
        assertThat(auditCount(c.getId(), "SYSTEM_WITHDRAW_ON_APPROVE")).isZero();
    }
}
