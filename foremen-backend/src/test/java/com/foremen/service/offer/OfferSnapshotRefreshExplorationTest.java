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
import com.foremen.service.OfferService;
import com.foremen.service.offer.OfferSnapshotViews.SnapshotView;

/**
 * FOR-05-07b task 1 (bugfix.md 1.1): prepare an offer on an empty estimate, fill the estimate, send the
 * offer, read the snapshot. EXPECTED TO FAIL on unfixed code (the offer 261 / project 1531 shape).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class OfferSnapshotRefreshExplorationTest extends OfferExplorationSupport {

    @Autowired
    private OfferService offerService;
    @Autowired
    private OfferSnapshotService offerSnapshotService;

    @Test
    @DisplayName("1.1: a snapshot prepared before the estimate was filled shows the filled estimate once sent")
    void sentSnapshotReflectsEstimateFilledAfterPrepare() {
        Fx f = emptyEstimateFixture("R");
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Bazowy");

        addLine(f, new BigDecimal("10.00"), new BigDecimal("5.00"));
        OfferEntity sent = offerService.send(offer.getId());

        SnapshotView view = offerSnapshotService.snapshot(sent.getId(), false, true, false);
        assertThat(sent.getTotalNet()).as("live offer total net").isNotNull();
        // Counterexample on unfixed code: rooms = [], groups = [], totalNet = 0 vs live total > 0.
        assertThat(view.groups()).as("snapshot groups").isNotEmpty();
        assertThat(view.rooms()).as("snapshot rooms").isNotEmpty();
        assertThat(view.totalNet()).as("snapshot totalNet equals live offer totalNet")
                .isEqualByComparingTo(sent.getTotalNet());
        assertThat(view.totalNet()).as("snapshot totalNet is above zero").isGreaterThan(BigDecimal.ZERO);
    }
}
