package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.service.OfferService;
import com.foremen.testsupport.MockMvcSecurityConfig;

/**
 * FOR-05-07b task 3.7: restore of the estimate from an offer snapshot, against Testcontainers.
 * Repeatability: per-run ids, rows removed by {@link OfferExplorationSupport#cleanUpExploration()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
class OfferRestoreIntegrationTest extends OfferExplorationSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private OfferService offerService;
    @Autowired
    private MockMvc mockMvc;

    private MvcResult restore(Long offerId) throws Exception {
        return mockMvc.perform(post("/api/offers/" + offerId + "/restore")
                .with(user("admin-" + runId).roles("ADMIN"))).andReturn();
    }

    private List<Object[]> lineRows(Long estimateId) {
        return tx().execute(s -> {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = entityManager.createQuery(
                    "select l.quantity, l.valueNet, l.unitPrice from EstimateLineEntity l where l.estimate.id = :e")
                    .setParameter("e", estimateId).getResultList();
            return rows;
        });
    }

    @Test
    @DisplayName("restore replaces an edited estimate with the snapshot, recomputes totals and writes an audit row")
    void restoreReplacesEstimateAndAudits() throws Exception {
        Fx f = emptyEstimateFixture("R1");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Base");

        // Edit the estimate after the snapshot: two more lines.
        addLine(f, new BigDecimal("5.00"), new BigDecimal("3.00"));
        addLine(f, new BigDecimal("7.00"), new BigDecimal("4.00"));
        assertThat(lineRows(f.estimateId)).hasSize(3);

        MvcResult result = restore(offer.getId());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("restoredLines").asInt()).isEqualTo(1);
        assertThat(body.get("skippedRooms")).isEmpty();
        assertThat(body.get("estimateTotals").get("totalNet").decimalValue()).isEqualByComparingTo("20.00");

        List<Object[]> rows = lineRows(f.estimateId);
        assertThat(rows).hasSize(1);
        assertThat((BigDecimal) rows.get(0)[0]).isEqualByComparingTo("2");
        assertThat((BigDecimal) rows.get(0)[1]).isEqualByComparingTo("20.00");

        Long audits = tx().execute(s -> entityManager.createQuery(
                "select count(a) from AuditLogEntity a where a.entityClass = 'OfferEntity' "
                        + "and a.entityId = :id and a.operation = 'RESTORE_ESTIMATE'", Long.class)
                .setParameter("id", offer.getId()).getSingleResult());
        assertThat(audits).isEqualTo(1L);
    }

    @Test
    @DisplayName("restore of a locked estimate answers 409 and changes nothing")
    void lockedEstimateIsRefused() throws Exception {
        Fx f = emptyEstimateFixture("R2");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Locked");
        addLine(f, new BigDecimal("5.00"), new BigDecimal("3.00"));
        tx().executeWithoutResult(s -> entityManager.find(EstimateEntity.class, f.estimateId)
                .setStatus(EstimateStatus.APPROVED));

        MvcResult result = restore(offer.getId());

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("error.estimate.locked");
        assertThat(lineRows(f.estimateId)).hasSize(2);
    }

    @Test
    @DisplayName("restore of an offer without a snapshot or with a version 1 snapshot answers 404")
    void legacySnapshotIsRefused() throws Exception {
        Fx f = emptyEstimateFixture("R3");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Legacy");

        tx().executeWithoutResult(s -> entityManager.find(OfferEntity.class, offer.getId()).setSnapshot(null));
        MvcResult none = restore(offer.getId());
        assertThat(none.getResponse().getStatus()).isEqualTo(404);
        assertThat(none.getResponse().getContentAsString()).contains("error.offer.snapshot.missing");

        tx().executeWithoutResult(s -> entityManager.find(OfferEntity.class, offer.getId()).setSnapshot(
                new OfferSnapshot(1, "2024-01-01T00:00:00Z", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, List.of(), List.of())));
        MvcResult v1 = restore(offer.getId());
        assertThat(v1.getResponse().getStatus()).isEqualTo(404);
        assertThat(v1.getResponse().getContentAsString()).contains("error.offer.snapshot.missing");
        assertThat(lineRows(f.estimateId)).hasSize(1);
    }

    @Test
    @DisplayName("cells of rooms that no longer exist are skipped and listed; a SENT source offer is withdrawn")
    void missingRoomIsSkippedAndOfferIsUntouched() throws Exception {
        Fx f = emptyEstimateFixture("R4");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Rooms");
        final long ghostRoomId = 987_654_321L;

        tx().executeWithoutResult(s -> {
            OfferEntity o = entityManager.find(OfferEntity.class, offer.getId());
            OfferSnapshot snap = o.getSnapshot();
            OfferSnapshot.Group g = snap.groups().get(0);
            OfferSnapshot.Row r = g.rows().get(0);
            List<OfferSnapshot.Cell> cells = new java.util.ArrayList<>(r.cells());
            cells.add(new OfferSnapshot.Cell(ghostRoomId, new BigDecimal("5.0000")));
            OfferSnapshot.Row row = new OfferSnapshot.Row(r.workItemId(), r.nameRu(), r.namePl(), r.unitCode(),
                    r.unitNameRu(), r.unitNamePl(), r.quantity(), r.unitPrice(), r.baseUnitPrice(),
                    r.valueNet(), r.baseValueNet(), cells, r.unitPriceOverride(), r.comment(),
                    r.workPriceId(), r.lineNo());
            List<OfferSnapshot.Room> rooms = new java.util.ArrayList<>(snap.rooms());
            rooms.add(new OfferSnapshot.Room(ghostRoomId, "Ghost"));
            o.setSnapshot(new OfferSnapshot(snap.version(), snap.capturedAt(), snap.vatRatePct(), snap.totalNet(),
                    snap.totalVat(), snap.totalGross(), rooms,
                    List.of(new OfferSnapshot.Group(g.categoryId(), g.nameRu(), g.namePl(), List.of(row))),
                    snap.appliedPackageCode(), snap.appliedPackageNameRu(), snap.appliedPackageNamePl()));
            o.setStatus(OfferStatus.SENT);
        });

        MvcResult result = restore(offer.getId());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("restoredLines").asInt()).isEqualTo(1);
        assertThat(body.get("skippedRooms")).hasSize(1);
        assertThat(body.get("skippedRooms").get(0).get("roomId").asLong()).isEqualTo(ghostRoomId);
        assertThat(body.get("skippedRooms").get(0).get("label").asText()).isEqualTo("Ghost");
        assertThat(body.get("estimateTotals").get("totalNet").decimalValue()).isEqualByComparingTo("20.00");

        // D3: offers do not react to the restore; the seen (SENT) source offer keeps its status.
        OfferStatus status = tx().execute(s -> entityManager.find(OfferEntity.class, offer.getId()).getStatus());
        assertThat(status).isEqualTo(OfferStatus.SENT);
    }
}
