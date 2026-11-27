package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;
import com.foremen.service.estimate.EstimateAssignmentService.EditKind;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;
import com.foremen.service.estimate.EstimateScratchService;
import com.foremen.service.estimate.EstimateScratchService.Seed;
import com.foremen.service.estimate.recalc.EstimateChangedEvent;

/**
 * FOR-05-04 task 3.3 (Requirements 12.1-12.4, 12.6, 12.7): {@link EstimateScratchService} against
 * Testcontainers. Repeatability: per-run ids, rows removed by {@link OfferExplorationSupport#cleanUpExploration()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(EstimateScratchServiceIntegrationTest.EventProbeConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@TestPropertySource(properties = "foremen.estimate-ai.scratch-timeout=2s")
class EstimateScratchServiceIntegrationTest extends OfferExplorationSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Collects every published {@link EstimateChangedEvent}. */
    static final List<EstimateChangedEvent> EVENTS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class EventProbeConfig {
        @Bean
        EventProbe estimateScratchEventProbe() {
            return new EventProbe();
        }
    }

    static class EventProbe {
        @EventListener
        void on(EstimateChangedEvent event) {
            EVENTS.add(event);
        }
    }

    @Autowired
    private EstimateScratchService scratch;
    @Autowired
    private OfferService offerService;
    @Autowired
    private OfferRestoreService restoreService;

    private static StagedEdit setQuantity(Fx f, BigDecimal qty) {
        return new StagedEdit(EditKind.SET_QUANTITY, f.workItemId, f.roomId, null, null, null, null, null,
                null, qty, null, null, null);
    }

    private long count(String entity, Long estimateId) {
        return tx().execute(s -> entityManager.createQuery(
                "select count(x) from " + entity + " x where " + (entity.equals("EstimateLineEntity")
                        ? "x.estimate.id" : "x.line.estimate.id") + " = :e", Long.class)
                .setParameter("e", estimateId).getSingleResult());
    }

    private long auditRows() {
        return tx().execute(s -> entityManager.createQuery(
                "select count(a) from AuditLogEntity a", Long.class).getSingleResult());
    }

    private BigDecimal totalNet(Long estimateId) {
        return tx().execute(s -> entityManager.find(EstimateEntity.class, estimateId).getTotalNet());
    }

    /** The snapshot as a JSON tree without the fields that legitimately differ between two estimates. */
    private static JsonNode normalized(OfferSnapshot snapshot) {
        JsonNode tree = JSON.valueToTree(snapshot);
        strip(tree);
        return tree;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode o) {
            o.remove("lineId");
            o.remove("capturedAt");
        }
        node.forEach(EstimateScratchServiceIntegrationTest::strip);
    }

    private static boolean sameTree(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            var names = a.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!b.has(name) || !sameTree(a.get(name), b.get(name))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!sameTree(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private List<Object[]> lineRows(Long estimateId) {
        return tx().execute(s -> {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = entityManager.createQuery(
                    "select l.quantity, l.valueNet, l.unitPrice from EstimateLineEntity l "
                            + "where l.estimate.id = :e order by l.lineNo, l.unitPrice, l.quantity")
                    .setParameter("e", estimateId).getResultList();
            return rows;
        });
    }

    @Test
    @DisplayName("property: snapshot -> scratch(SNAPSHOT, no edits) -> snapshot is the identity and restores to the same estimate")
    void snapshotRoundTripIsIdentityAndRestoreEquivalent() {
        Random random = new Random(305);
        for (int run = 0; run < 5; run++) {
            Fx f = emptyEstimateFixture("RT" + run);
            int lines = 1 + random.nextInt(3);
            for (int i = 0; i < lines; i++) {
                addLine(f, BigDecimal.valueOf(1 + random.nextInt(500), 2),
                        BigDecimal.valueOf(1 + random.nextInt(2000), 2));
            }
            authenticateAdmin();
            OfferEntity offer = offerService.prepareOffer(f.projectId, "RT" + run);
            OfferSnapshot original = offer.getSnapshot();

            OfferSnapshot again = scratch.compute(f.projectId, Seed.snapshot(offer.getId()), List.of(), List.of());

            assertThat(sameTree(normalized(original), normalized(again)))
                    .as("round trip run %d: %s vs %s", run, normalized(original), normalized(again)).isTrue();

            // Restore equivalence: restoring the original and restoring the scratch result agree.
            restoreService.restore(offer.getId());
            List<Object[]> fromOriginal = lineRows(f.estimateId);
            BigDecimal netOriginal = totalNet(f.estimateId);
            tx().executeWithoutResult(s -> entityManager.find(OfferEntity.class, offer.getId()).setSnapshot(again));
            restoreService.restore(offer.getId());
            List<Object[]> fromScratch = lineRows(f.estimateId);
            assertThat(fromScratch).hasSameSizeAs(fromOriginal);
            for (int i = 0; i < fromOriginal.size(); i++) {
                for (int c = 0; c < 3; c++) {
                    assertThat((BigDecimal) fromScratch.get(i)[c]).isEqualByComparingTo((BigDecimal) fromOriginal.get(i)[c]);
                }
            }
            assertThat(totalNet(f.estimateId)).isEqualByComparingTo(netOriginal);
        }
    }

    @Test
    @DisplayName("nothing persists: row counts, estimate totals and audit rows are unchanged and no event is published")
    void nothingPersistsAndNoEventIsPublished() {
        Fx f = emptyEstimateFixture("NP");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "NP");
        tx().executeWithoutResult(s -> {
            EstimateEntity e = entityManager.find(EstimateEntity.class, f.estimateId);
            e.setTotalNet(new BigDecimal("20.00"));
        });
        long linesBefore = count("EstimateLineEntity", f.estimateId);
        long qtysBefore = count("EstimateLineRoomQtyEntity", f.estimateId);
        long auditBefore = auditRows();
        BigDecimal netBefore = totalNet(f.estimateId);
        EVENTS.clear();

        OfferSnapshot live = scratch.compute(f.projectId, Seed.LIVE, List.of(setQuantity(f, new BigDecimal("7.00"))), List.of());
        OfferSnapshot fromSnapshot = scratch.compute(f.projectId, Seed.snapshot(offer.getId()),
                List.of(setQuantity(f, new BigDecimal("3.00"))), List.of());

        assertThat(live.totalNet()).isEqualByComparingTo("70.00");
        assertThat(fromSnapshot.totalNet()).isEqualByComparingTo("30.00");
        assertThat(count("EstimateLineEntity", f.estimateId)).isEqualTo(linesBefore);
        assertThat(count("EstimateLineRoomQtyEntity", f.estimateId)).isEqualTo(qtysBefore);
        assertThat(auditRows()).isEqualTo(auditBefore);
        assertThat(totalNet(f.estimateId)).isEqualByComparingTo(netBefore);
        assertThat(EVENTS).isEmpty();
        List<Object[]> rows = lineRows(f.estimateId);
        assertThat(rows).hasSize(1);
        assertThat((BigDecimal) rows.get(0)[0]).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("a held estimate lock makes the scratch run fail with 503 error.estimateai.scratch.timeout within the time box")
    void heldLockTimesOut() throws Exception {
        Fx f = emptyEstimateFixture("TO");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderError = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                tx().executeWithoutResult(s -> {
                    estimateDao.findByProjectIdForUpdate(f.projectId).orElseThrow();
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable t) {
                holderError.set(t);
                locked.countDown();
            }
        });
        holder.start();
        try {
            assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            assertThatThrownBy(() -> scratch.compute(f.projectId, Seed.LIVE, List.of(), List.of()))
                    .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                        assertThat(e.getMessageCode()).isEqualTo("error.estimateai.scratch.timeout");
                        assertThat(e.getStatus().value()).isEqualTo(503);
                    });
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(15);
        } finally {
            release.countDown();
            holder.join(30_000);
        }
        assertThat(holderError.get()).isNull();
    }
}
