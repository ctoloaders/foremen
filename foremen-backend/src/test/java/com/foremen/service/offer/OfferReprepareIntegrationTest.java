package com.foremen.service.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.NotificationEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;

/**
 * FOR-05-07b Round 2 (task 7.4, D5): update an existing offer from the estimate.
 * Repeatability: per-run ids, rows removed by {@link OfferExplorationSupport#cleanUpExploration()};
 * project members created here are removed in {@link #cleanUpMembers()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class OfferReprepareIntegrationTest extends OfferExplorationSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private OfferService offerService;
    @Autowired
    private OfferSnapshotService offerSnapshotService;

    private Long memberProjectId;

    @AfterEach
    void cleanUpMembers() {
        tx().executeWithoutResult(s -> {
            entityManager.createQuery("delete from NotificationEntity n where n.recipient.email like :e")
                    .setParameter("e", "client+" + runId + "-%").executeUpdate();
            if (memberProjectId != null) {
                entityManager.createQuery("delete from ProjectMemberEntity m where m.projectId = :p")
                        .setParameter("p", memberProjectId).executeUpdate();
            }
        });
    }

    private OfferEntity reload(Long id) {
        return tx().execute(s -> entityManager.find(OfferEntity.class, id));
    }

    private String storedSnapshotJson(Long offerId) {
        return tx().execute(s -> {
            OfferSnapshot snap = entityManager.find(OfferEntity.class, offerId).getSnapshot();
            try {
                return snap == null ? null : JSON.writeValueAsString(snap);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private long auditCount(Long offerId) {
        return tx().execute(s -> entityManager.createQuery(
                "select count(a) from AuditLogEntity a where a.entityClass = 'OfferEntity' "
                        + "and a.entityId = :id and a.operation = 'REPREPARE'", Long.class)
                .setParameter("id", offerId).getSingleResult());
    }

    private List<NotificationEntity> notificationsOf(Long userId) {
        return tx().execute(s -> entityManager.createQuery(
                "select n from NotificationEntity n where n.recipient.id = :u", NotificationEntity.class)
                .setParameter("u", userId).getResultList());
    }

    private Fx filledFixture(String tag) {
        Fx f = emptyEstimateFixture(tag);
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        return f;
    }

    private Long addClientMember(Long projectId) {
        Long userId = createClientUser();
        tx().executeWithoutResult(s -> {
            ProjectMemberEntity member = new ProjectMemberEntity();
            member.setUser(entityManager.find(UserEntity.class, userId));
            member.setProjectId(projectId);
            member.setProjectRole(roleDao.findByCode("CLIENT").orElseThrow());
            entityManager.persist(member);
        });
        memberProjectId = projectId;
        return userId;
    }

    @Test
    @DisplayName("a SENT and inactive offer keeps status, name and flag, takes the new estimate, version + 1, one audit row")
    void sentInactiveOfferIsUpdated() {
        Fx f = filledFixture("R1");
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Bazowy");
        offerService.send(offer.getId());
        offerService.deactivate(offer.getId());
        OfferEntity before = reload(offer.getId());
        String frozen = storedSnapshotJson(offer.getId());

        addLine(f, new BigDecimal("5.00"), new BigDecimal("4.00"));
        offerService.reprepare(f.projectId, offer.getId());

        OfferEntity after = reload(offer.getId());
        assertThat(after.getStatus()).isEqualTo(OfferStatus.SENT);
        assertThat(after.getName()).isEqualTo("Bazowy");
        assertThat(after.isInactive()).isTrue();
        assertThat(after.getRevision()).isEqualTo(before.getRevision() + 1);
        assertThat(storedSnapshotJson(offer.getId())).isNotEqualTo(frozen);
        assertThat(offerSnapshotService.snapshot(offer.getId(), false, true, false).totalNet())
                .isEqualByComparingTo("40.00").isEqualByComparingTo(after.getTotalNet());
        assertThat(auditCount(offer.getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("a DRAFT offer is updated in place without a notification; the SENT offer notifies the client")
    void notificationOnlyWhenNotDraft() {
        Fx f = filledFixture("R2");
        Long clientId = addClientMember(f.projectId);
        OfferEntity draft = offerService.prepareOffer(f.projectId, "Szkic");
        OfferEntity sent = offerService.prepareOffer(f.projectId, "Wysłana");
        offerService.send(sent.getId());

        offerService.reprepare(f.projectId, draft.getId());
        assertThat(reload(draft.getId()).getStatus()).isEqualTo(OfferStatus.DRAFT);
        assertThat(reload(draft.getId()).getRevision()).isEqualTo(2);
        assertThat(notificationsOf(clientId)).isEmpty();

        offerService.reprepare(f.projectId, sent.getId());
        List<NotificationEntity> notes = notificationsOf(clientId);
        assertThat(notes).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("notification.offer.reprepared");
            assertThat(n.getBody()).contains("Wysłana").contains("Oferta").contains("Оферта").contains("2");
        });
    }

    @Test
    @DisplayName("terminal offers are refused with 409 error.offer.reprepare.terminal and stay unchanged")
    void terminalOfferRefused() {
        Fx f = filledFixture("R3");
        OfferEntity offer = offerService.prepareOffer(f.projectId, "A");
        offerService.withdraw(offer.getId());
        String frozen = storedSnapshotJson(offer.getId());

        assertThatThrownBy(() -> offerService.reprepare(f.projectId, offer.getId()))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo("error.offer.reprepare.terminal");
                });
        assertThat(reload(offer.getId()).getRevision()).isEqualTo(1);
        assertThat(storedSnapshotJson(offer.getId())).isEqualTo(frozen);
        assertThat(auditCount(offer.getId())).isZero();
    }

    @Test
    @DisplayName("an offer of another project answers 400")
    void otherProjectRefused() {
        Fx f = filledFixture("R4");
        Fx other = filledFixture("R4b");
        OfferEntity offer = offerService.prepareOffer(f.projectId, "A");

        assertThatThrownBy(() -> offerService.reprepare(other.projectId, offer.getId()))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(reload(offer.getId()).getRevision()).isEqualTo(1);
    }

    @Test
    @DisplayName("updating one offer leaves the snapshot of the other offers byte-identical")
    void otherOffersUnchanged() {
        Fx f = filledFixture("R5");
        OfferEntity sent = offerService.prepareOffer(f.projectId, "Sent");
        offerService.send(sent.getId());
        OfferEntity target = offerService.prepareOffer(f.projectId, "Target");
        String frozen = storedSnapshotJson(sent.getId());

        addLine(f, new BigDecimal("5.00"), new BigDecimal("3.00"));
        offerService.reprepare(f.projectId, target.getId());

        assertThat(storedSnapshotJson(sent.getId())).isEqualTo(frozen);
        assertThat(reload(sent.getId()).getRevision()).isEqualTo(1);
    }
}
