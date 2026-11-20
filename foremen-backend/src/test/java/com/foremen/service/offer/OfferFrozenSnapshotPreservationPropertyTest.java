package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.service.OfferService;

/**
 * FOR-05-07b task 2 (bugfix.md 3.1, 3.2, 3.3): for any offer past DRAFT (SENT, APPROVED, REJECTED,
 * WITHDRAWN) the stored snapshot JSON is byte-identical before and after estimate edits and after the
 * status transitions of the same offer. Passes on unfixed code (the column is {@code updatable=false}).
 *
 * <p>Implemented as a seeded randomized loop inside one Spring/Testcontainers context: jqwik has no
 * Spring integration in this project, and a per-try container is not practical. Each iteration draws
 * a target status and a random sequence of estimate edits.
 *
 * <p>Repeatability: per-run id fixtures from {@link OfferExplorationSupport}, removed after the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
@TestPropertySource(properties = "foremen.test.context-key=preservation-frozen")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OfferFrozenSnapshotPreservationPropertyTest extends OfferExplorationSupport {

    private static final int TRIES = 12;
    private static final OfferStatus[] PAST_DRAFT = {
            OfferStatus.SENT, OfferStatus.APPROVED, OfferStatus.REJECTED, OfferStatus.WITHDRAWN};

    @Autowired
    private OfferService offerService;

    @Test
    @DisplayName("snapshot JSON of an offer past DRAFT never changes after estimate edits or status changes")
    void frozenSnapshotBytesStayIdentical() {
        Random random = new Random(0x5EED_07BL);
        authenticateAdmin();
        for (int i = 0; i < TRIES; i++) {
            Fx f = emptyEstimateFixture("Z" + i);
            addLine(f, new BigDecimal("10.00"), new BigDecimal("5.00"));
            OfferEntity offer = offerService.prepareOffer(f.projectId, "Frozen " + i);
            OfferStatus target = PAST_DRAFT[random.nextInt(PAST_DRAFT.length)];

            // Leave DRAFT through the real send transition, then move to the drawn status.
            offerService.send(offer.getId());
            if (target != OfferStatus.SENT) {
                tx().executeWithoutResult(s -> entityManager.find(OfferEntity.class, offer.getId()).setStatus(target));
            }
            String frozen = snapshotText(offer.getId());
            assertThat(frozen).as("try %d: snapshot present after leaving DRAFT", i).isNotNull();

            int edits = 1 + random.nextInt(3);
            for (int e = 0; e < edits; e++) {
                BigDecimal qty = BigDecimal.valueOf(1 + random.nextInt(200));
                BigDecimal price = BigDecimal.valueOf(1 + random.nextInt(500));
                tx().executeWithoutResult(s -> {
                    EstimateLineEntity line = entityManager.createQuery(
                            "select l from EstimateLineEntity l where l.estimate.id = :e", EstimateLineEntity.class)
                            .setParameter("e", f.estimateId).setMaxResults(1).getSingleResult();
                    line.setQuantity(qty);
                    line.setUnitPrice(price);
                    line.setValueNet(price.multiply(qty));
                });
                assertThat(snapshotText(offer.getId())).as("try %d, edit %d (%s)", i, e, target).isEqualTo(frozen);
            }

            // A further status change of the same offer does not touch the snapshot either.
            if (target == OfferStatus.SENT) {
                offerService.withdraw(offer.getId());
                assertThat(snapshotText(offer.getId())).as("try %d after withdraw", i).isEqualTo(frozen);
            }
        }
    }

    private String snapshotText(Long offerId) {
        return (String) entityManager.createNativeQuery("select cast(snapshot as text) from offers where id = :id")
                .setParameter("id", offerId).getSingleResult();
    }
}
