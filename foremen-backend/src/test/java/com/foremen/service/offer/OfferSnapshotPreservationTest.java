package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;
import com.foremen.service.offer.OfferSnapshotViews.Comparison;

/**
 * FOR-05-07b task 2: preservation tests (bugfix.md 3.2, 3.3, 3.4, 3.5). Observed on UNFIXED code and
 * MUST PASS there and after the fix. The JSON field sets recorded below are the allow-listed CLIENT
 * fields observed today; after the fix the actual set may only grow (additive), never lose one of
 * them, and never gain a staff-only field.
 *
 * <p>Repeatability: per-run id fixtures from {@link OfferExplorationSupport}, removed after each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
@TestPropertySource(properties = "foremen.test.context-key=preservation-views")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OfferSnapshotPreservationTest extends OfferExplorationSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> BASE_SNAPSHOT_FIELDS = Set.of(
            "offerId", "name", "status", "capturedAt", "vatRatePct", "totalNet", "totalVat", "totalGross",
            "rooms", "groups");
    private static final Set<String> BASE_GROUP_FIELDS = Set.of("categoryId", "name", "rows");
    private static final Set<String> BASE_ROW_FIELDS = Set.of(
            "workItemId", "name", "unitCode", "unitName", "quantity", "unitPrice", "valueNet", "cells");
    private static final Set<String> BASE_SUMMARY_FIELDS = Set.of(
            "id", "name", "revision", "status", "totalNet", "totalGross", "hasSnapshot", "createdDate");
    private static final Set<String> CLIENT_FORBIDDEN = Set.of(
            "baseUnitPrice", "baseValueNet", "unitPriceOverride", "workPriceId", "lineNo", "margin",
            "labourComparison");

    @Autowired
    private OfferService offerService;
    @Autowired
    private OfferSnapshotService offerSnapshotService;

    @Test
    @DisplayName("3.2: CLIENT gets 404 for a DRAFT offer; allow-listed fields are kept (additive only), no staff fields")
    void clientReadsKeepAllowListAndHideDraft() throws Exception {
        Fx f = emptyEstimateFixture("P1");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("5.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Client view");

        assertThatThrownBy(() -> offerSnapshotService.snapshot(offer.getId(), true, false, false))
                .isInstanceOf(ForemenApiException.class);
        assertThat(offerSnapshotService.listByProject(f.projectId, true)).isEmpty();

        offerService.send(offer.getId());

        JsonNode snap = MAPPER.valueToTree(offerSnapshotService.snapshot(offer.getId(), true, true, false));
        JsonNode summary = MAPPER.valueToTree(offerSnapshotService.listByProject(f.projectId, true).get(0));
        JsonNode group = snap.get("groups").get(0);
        JsonNode row = group.get("rows").get(0);

        assertThat(fields(snap)).containsAll(BASE_SNAPSHOT_FIELDS);
        assertThat(fields(group)).containsAll(BASE_GROUP_FIELDS);
        assertThat(fields(row)).containsAll(BASE_ROW_FIELDS);
        assertThat(fields(summary)).containsAll(BASE_SUMMARY_FIELDS);
        for (JsonNode n : List.of(snap, group, row, summary)) {
            assertThat(fields(n)).doesNotContainAnyElementsOf(CLIENT_FORBIDDEN);
        }

        String compareJson = MAPPER.writeValueAsString(
                offerSnapshotService.compare(offer.getId(), offer.getId(), true, false));
        assertThat(compareJson).doesNotContain("base").doesNotContain("margin").doesNotContain("labour");
    }

    @Test
    @DisplayName("3.3: a legacy offer without a snapshot reports hasSnapshot=false, no view, no compare")
    void legacyOfferWithoutSnapshot() {
        Fx f = emptyEstimateFixture("P2");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("1.00"));
        authenticateAdmin();
        OfferEntity legacy = offerService.prepareOffer(f.projectId, "Legacy");
        offerService.withdraw(legacy.getId());
        OfferEntity other = offerService.prepareOffer(f.projectId, "Other");
        tx().executeWithoutResult(s -> entityManager.createNativeQuery(
                "update offers set snapshot = null where id = :id").setParameter("id", legacy.getId()).executeUpdate());

        assertThat(offerSnapshotService.listByProject(f.projectId, false)).filteredOn(o -> o.id().equals(legacy.getId()))
                .singleElement().satisfies(o -> assertThat(o.hasSnapshot()).isFalse());
        assertThatThrownBy(() -> offerSnapshotService.snapshot(legacy.getId(), false, true, false))
                .isInstanceOf(ForemenApiException.class).hasMessageContaining("error.offer.snapshot.missing");
        assertThatThrownBy(() -> offerSnapshotService.compare(legacy.getId(), other.getId(), false, false))
                .isInstanceOf(ForemenApiException.class).hasMessageContaining("error.offer.snapshot.missing");
    }

    @Test
    @DisplayName("3.4: prepare trims the name, defaults a blank one to 'Offer N', rejects more than 255 characters")
    void prepareNaming() {
        Fx f = emptyEstimateFixture("P3");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("1.00"));
        authenticateAdmin();

        OfferEntity trimmed = offerService.prepareOffer(f.projectId, "   Premium   ");
        assertThat(trimmed.getName()).isEqualTo("Premium");
        offerService.withdraw(trimmed.getId());

        OfferEntity blank = offerService.prepareOffer(f.projectId, "  ");
        assertThat(blank.getName()).isEqualTo("Offer 2");
        offerService.withdraw(blank.getId());

        OfferEntity max = offerService.prepareOffer(f.projectId, "x".repeat(255));
        assertThat(max.getName()).hasSize(255);
        offerService.withdraw(max.getId());

        assertThatThrownBy(() -> offerService.prepareOffer(f.projectId, "x".repeat(256)))
                .isInstanceOf(ForemenApiException.class).hasMessageContaining("error.offer.name.too.long");
    }

    @Test
    @DisplayName("3.5: compare returns added, removed and changed works with the total delta; other project is 400")
    void compareSameAndDifferentProject() {
        Fx f = emptyEstimateFixture("P4");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("5.00")); // work A: 50.00
        Long workA = f.workItemId;
        authenticateAdmin();
        OfferEntity left = offerService.prepareOffer(f.projectId, "Left");
        offerService.withdraw(left.getId());

        // Replace work A with a new work B (80.00): A is removed, B is added.
        Long workB = newWorkItem(f, "B");
        tx().executeWithoutResult(s -> {
            entityManager.createQuery("delete from EstimateLineRoomQtyEntity q where q.line.estimate.id = :e")
                    .setParameter("e", f.estimateId).executeUpdate();
            entityManager.createQuery("delete from EstimateLineEntity l where l.estimate.id = :e")
                    .setParameter("e", f.estimateId).executeUpdate();
        });
        f.workItemId = workB;
        addLine(f, new BigDecimal("20.00"), new BigDecimal("4.00"));
        OfferEntity right = offerService.prepareOffer(f.projectId, "Right");

        Comparison c = offerSnapshotService.compare(left.getId(), right.getId(), false, false);
        assertThat(c.added()).extracting(OfferSnapshotViews.WorkDiff::workItemId).containsExactly(workB);
        assertThat(c.removed()).extracting(OfferSnapshotViews.WorkDiff::workItemId).containsExactly(workA);
        assertThat(c.changed()).isEmpty();
        assertThat(c.deltaTotalNet()).isEqualByComparingTo("30.00");

        Fx other = emptyEstimateFixture("P4x");
        addLine(other, new BigDecimal("10.00"), new BigDecimal("1.00"));
        OfferEntity foreign = offerService.prepareOffer(other.projectId, "Foreign");
        assertThatThrownBy(() -> offerSnapshotService.compare(left.getId(), foreign.getId(), false, false))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessage()).contains("error.offer.compare.different.project"));
    }

    private Long newWorkItem(Fx f, String tag) {
        Long[] id = new Long[1];
        tx().executeWithoutResult(s -> {
            WorkItemEntity existing = entityManager.find(WorkItemEntity.class, f.workItemId);
            WorkItemEntity item = new WorkItemEntity();
            item.setWorkCategory(existing.getWorkCategory());
            item.setUnit(entityManager.getReference(MeasurementUnitEntity.class, f.unitId));
            item.setNameRU("Work " + tag + " " + runId);
            item.setNamePL("Work " + tag + " " + runId);
            item.setCode("WI_" + tag + "_" + runId);
            item.setActive(true);
            entityManager.persist(item);
            entityManager.flush();
            id[0] = item.getId();
        });
        return id[0];
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new HashSet<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }
}
