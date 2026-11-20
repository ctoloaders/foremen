package com.foremen.service.offer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;
import com.foremen.service.offer.OfferSnapshotViews.SnapshotView;
import com.foremen.testsupport.MockMvcSecurityConfig;

/**
 * FOR-05-07b task 4.1: snapshot lifecycle. Refresh on send, explicit refresh of a DRAFT (A6), a legacy
 * DRAFT without a snapshot receives one (A7), refresh of a non-DRAFT is 409, the snapshot is frozen
 * after send (also across a restore of another offer).
 * Repeatability: per-run ids, rows removed by {@link OfferExplorationSupport#cleanUpExploration()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
class OfferSnapshotRefreshIntegrationTest extends OfferExplorationSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private OfferService offerService;
    @Autowired
    private OfferSnapshotService offerSnapshotService;
    @Autowired
    private MockMvc mockMvc;

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

    @Test
    @DisplayName("send refreshes a DRAFT snapshot to the live estimate; the snapshot is frozen afterwards")
    void sendRefreshesThenFreezes() {
        Fx f = emptyEstimateFixture("L1");
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Bazowy");
        assertThat(offerSnapshotService.snapshot(offer.getId(), false, true, false).groups()).isEmpty();

        addLine(f, new BigDecimal("10.00"), new BigDecimal("5.00"));
        OfferEntity sent = offerService.send(offer.getId());

        SnapshotView view = offerSnapshotService.snapshot(sent.getId(), false, true, false);
        assertThat(sent.getStatus()).isEqualTo(OfferStatus.SENT);
        assertThat(view.groups()).hasSize(1);
        assertThat(view.totalNet()).isEqualByComparingTo("50.00").isEqualByComparingTo(sent.getTotalNet());

        String frozen = storedSnapshotJson(sent.getId());
        addLine(f, new BigDecimal("7.00"), new BigDecimal("3.00"));
        assertThat(storedSnapshotJson(sent.getId())).isEqualTo(frozen);

        // Explicit refresh of a SENT offer is refused and changes nothing.
        assertThatThrownBy(() -> offerService.refreshSnapshot(sent.getId()))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessage()).contains("error.offer.illegal.transition"));
        assertThat(storedSnapshotJson(sent.getId())).isEqualTo(frozen);
    }

    @Test
    @DisplayName("explicit refresh of a DRAFT picks up estimate edits, recomputes totals and keeps DRAFT (A6)")
    void explicitRefreshOfDraft() {
        Fx f = emptyEstimateFixture("L2");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Draft");
        assertThat(offerSnapshotService.snapshot(offer.getId(), false, true, false).totalNet())
                .isEqualByComparingTo("20.00");

        addLine(f, new BigDecimal("5.00"), new BigDecimal("4.00"));
        OfferEntity refreshed = offerService.refreshSnapshot(offer.getId());

        assertThat(refreshed.getStatus()).isEqualTo(OfferStatus.DRAFT);
        SnapshotView view = offerSnapshotService.snapshot(offer.getId(), false, true, false);
        assertThat(view.totalNet()).isEqualByComparingTo("40.00").isEqualByComparingTo(refreshed.getTotalNet());
        assertThat(view.groups().get(0).rows()).hasSize(2);
    }

    @Test
    @DisplayName("a legacy DRAFT offer without a snapshot gets one on refresh, and on send (A7)")
    void legacyDraftGetsSnapshot() {
        Fx f = emptyEstimateFixture("L3");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity refreshOffer = offerService.prepareOffer(f.projectId, "LegacyRefresh");
        OfferEntity sendOffer = offerService.prepareOffer(f.projectId, "LegacySend");
        tx().executeWithoutResult(s -> {
            entityManager.find(OfferEntity.class, refreshOffer.getId()).setSnapshot(null);
            entityManager.find(OfferEntity.class, sendOffer.getId()).setSnapshot(null);
        });
        assertThat(storedSnapshotJson(refreshOffer.getId())).isNull();

        offerService.refreshSnapshot(refreshOffer.getId());
        assertThat(storedSnapshotJson(refreshOffer.getId())).isNotNull();
        assertThat(offerSnapshotService.snapshot(refreshOffer.getId(), false, true, false).totalNet())
                .isEqualByComparingTo("20.00");

        offerService.send(sendOffer.getId());
        assertThat(storedSnapshotJson(sendOffer.getId())).isNotNull();
    }

    @Test
    @DisplayName("POST /refresh-snapshot answers 409 error.offer.illegal.transition once the offer is SENT")
    void refreshEndpointRefusesNonDraft() throws Exception {
        Fx f = emptyEstimateFixture("L4");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Endpoint");

        offerService.send(offer.getId());
        String frozen = storedSnapshotJson(offer.getId());
        MvcResult conflict = mockMvc.perform(post("/api/offers/" + offer.getId() + "/refresh-snapshot")
                .with(user("admin-" + runId).roles("ADMIN"))).andReturn();
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString()).contains("error.offer.illegal.transition");
        assertThat(storedSnapshotJson(offer.getId())).isEqualTo(frozen);
    }

    @Test
    @DisplayName("a SENT snapshot stays byte-identical after an estimate edit and a restore of another offer")
    void frozenAcrossEditAndRestoreOfAnotherOffer() throws Exception {
        Fx f = emptyEstimateFixture("L5");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity sent = offerService.prepareOffer(f.projectId, "Sent");
        offerService.send(sent.getId());
        String frozen = storedSnapshotJson(sent.getId());

        OfferEntity other = offerService.prepareOffer(f.projectId, "Other");
        addLine(f, new BigDecimal("5.00"), new BigDecimal("3.00"));
        MvcResult restore = mockMvc.perform(post("/api/offers/" + other.getId() + "/restore")
                .with(user("admin-" + runId).roles("ADMIN"))).andReturn();

        assertThat(restore.getResponse().getStatus()).isEqualTo(200);
        assertThat(storedSnapshotJson(sent.getId())).isEqualTo(frozen);
    }
}
