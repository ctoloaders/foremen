package com.foremen.service.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.model.OfferAction;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;

/**
 * FOR-05-07b Round 2 (task 7.1, D2): manual inactive flag and one approved offer per project.
 * Repeatability: per-run ids, rows removed by {@link OfferExplorationSupport#cleanUpExploration()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class OfferInactiveFlagIntegrationTest extends OfferExplorationSupport {

    @Autowired
    private OfferService offerService;
    @Autowired
    private OfferSnapshotService offerSnapshotService;

    private OfferEntity reload(Long id) {
        return tx().execute(s -> entityManager.find(OfferEntity.class, id));
    }

    private long auditCount(Long offerId, String operation) {
        return tx().execute(s -> entityManager.createQuery(
                "select count(a) from AuditLogEntity a where a.entityClass = 'OfferEntity' "
                        + "and a.entityId = :id and a.operation = :op", Long.class)
                .setParameter("id", offerId).setParameter("op", operation).getSingleResult());
    }

    private Fx filledFixture(String tag) {
        Fx f = emptyEstimateFixture(tag);
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        return f;
    }

    @Test
    @DisplayName("deactivate and activate flip the flag, write one audit row each and are idempotent")
    void deactivateAndActivate() {
        Fx f = filledFixture("I1");
        OfferEntity offer = offerService.prepareOffer(f.projectId, "A");
        assertThat(reload(offer.getId()).isInactive()).isFalse();

        offerService.deactivate(offer.getId());
        offerService.deactivate(offer.getId());
        assertThat(reload(offer.getId()).isInactive()).isTrue();
        assertThat(reload(offer.getId()).getStatus()).isEqualTo(OfferStatus.DRAFT);
        assertThat(auditCount(offer.getId(), "DEACTIVATE")).isEqualTo(1L);

        offerService.activate(offer.getId());
        assertThat(reload(offer.getId()).isInactive()).isFalse();
        assertThat(auditCount(offer.getId(), "ACTIVATE")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a terminal offer cannot be deactivated or activated (409 illegal transition)")
    void terminalOfferRefusesFlag() {
        Fx f = filledFixture("I2");
        OfferEntity offer = offerService.prepareOffer(f.projectId, "A");
        offerService.withdraw(offer.getId());

        assertThatThrownBy(() -> offerService.deactivate(offer.getId()))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo("error.offer.illegal.transition");
                });
    }

    @Test
    @DisplayName("an inactive offer is still sendable and withdrawable by the executor and listed with the flag")
    void inactiveOfferStillSendable() {
        Fx f = filledFixture("I3");
        OfferEntity offer = offerService.prepareOffer(f.projectId, "A");
        offerService.deactivate(offer.getId());

        offerService.send(offer.getId());
        assertThat(reload(offer.getId()).getStatus()).isEqualTo(OfferStatus.SENT);
        assertThat(offerSnapshotService.listByProject(f.projectId, true))
                .singleElement().satisfies(s -> {
                    assertThat(s.id()).isEqualTo(offer.getId());
                    assertThat(s.inactive()).isTrue();
                });
        assertThat(offerSnapshotService.snapshot(offer.getId(), true, false, false).inactive()).isTrue();

        offerService.withdraw(offer.getId());
        assertThat(reload(offer.getId()).getStatus()).isEqualTo(OfferStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("the client cannot approve, reject or request changes on an inactive offer (409 error.offer.inactive)")
    void clientDecisionsRefusedOnInactiveOffer() {
        Fx f = filledFixture("I4");
        OfferEntity offer = offerService.prepareOffer(f.projectId, "A");
        offerService.send(offer.getId());
        offerService.deactivate(offer.getId());

        authenticateAs(createClientUser(), "ROLE_CLIENT");
        assertInactive(() -> offerService.approve(offer.getId()));
        assertInactive(() -> offerService.reject(offer.getId()));
        assertInactive(() -> tx().executeWithoutResult(s -> offerService.transitionOfferStatus(
                entityManager.find(OfferEntity.class, offer.getId()), OfferAction.REQUEST_CHANGES)));
        assertThat(reload(offer.getId()).getStatus()).isEqualTo(OfferStatus.SENT);

        authenticateAdmin();
        offerService.activate(offer.getId());
        authenticateAs(createClientUser(), "ROLE_CLIENT");
        assertThat(offerService.approve(offer.getId()).getStatus()).isEqualTo(OfferStatus.APPROVED);
    }

    @Test
    @DisplayName("approve is refused with 409 error.offer.already.approved while the project has an APPROVED offer")
    void secondApproveRefused() {
        Fx f = filledFixture("I5");
        OfferEntity a = offerService.prepareOffer(f.projectId, "A");
        OfferEntity b = offerService.prepareOffer(f.projectId, "B");
        offerService.send(a.getId());
        offerService.send(b.getId());

        authenticateAs(createClientUser(), "ROLE_CLIENT");
        offerService.approve(a.getId());
        assertThatThrownBy(() -> offerService.approve(b.getId()))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo("error.offer.already.approved");
                });
        assertThat(reload(b.getId()).getStatus()).isEqualTo(OfferStatus.SENT);
    }

    private static void assertInactive(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessageCode()).isEqualTo("error.offer.inactive");
        });
    }
}
