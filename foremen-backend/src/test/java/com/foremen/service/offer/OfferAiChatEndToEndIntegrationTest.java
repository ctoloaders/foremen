package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.OfferDao;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;
import com.foremen.service.estimate.EstimateScratchService;
import com.foremen.service.estimate.EstimateScratchService.Seed;
import com.foremen.service.estimateai.ai.OfferChatClient;
import com.foremen.service.estimateai.ai.StubOfferChatClient;
import com.foremen.service.estimateai.chat.OfferAiChatService;
import com.foremen.service.estimateai.chat.OfferAiChatService.TurnResult;

/**
 * FOR-05-04 task 6.3 (Requirements 10.4, 11.5, 11.7, 11.8, 12.6): end-to-end chat path with the stub model:
 * AI offer, price adjustment with confirmation, set volume, undo, restore into the estimate with totals
 * compared to the snapshot totals, and concurrency {@code busy}. Repeatability: per-run ids; rows are
 * removed by the exploration support clean-up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "foremen.estimate-ai.enabled=true",
        "foremen.estimate-ai.llm.stub=true",
        "foremen.estimate-ai.chat.rate-limit=50",
        "foremen.estimate-ai.chat.confirm-threshold=20"
})
@Testcontainers
@ActiveProfiles("integration-test")
@Import(OfferAiChatEndToEndIntegrationTest.GateConfig.class)
class OfferAiChatEndToEndIntegrationTest extends OfferExplorationSupport {

    static final String GATE = "GATE";
    static final AtomicReference<CountDownLatch> ENTERED = new AtomicReference<>(new CountDownLatch(1));
    static final AtomicReference<CountDownLatch> RELEASE = new AtomicReference<>(new CountDownLatch(1));

    /** The stub, except that a message containing {@code GATE} blocks until released. */
    @TestConfiguration
    static class GateConfig {
        @Bean
        @Primary
        OfferChatClient e2eGatedChatClient(StubOfferChatClient stub) {
            return new OfferChatClient() {
                @Override
                public boolean isEnabled() {
                    return true;
                }

                @Override
                public ChatResponse chat(ChatRequest request) {
                    if (request.userMessage() != null && request.userMessage().contains(GATE)) {
                        ENTERED.get().countDown();
                        try {
                            RELEASE.get().await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return stub.chat(request);
                }
            };
        }
    }

    @Autowired
    private OfferAiChatService chat;
    @Autowired
    private EstimateScratchService scratch;
    @Autowired
    private OfferService offerService;
    @Autowired
    private OfferDao offerDao;
    @Autowired
    private OfferRestoreService restoreService;

    private record Setup(Fx fx, Long offerId, String workCode) {
    }

    private Setup setup(String tag) {
        Fx f = emptyEstimateFixture(tag);
        addLine(f, new BigDecimal("10.00"), new BigDecimal("4.00"));
        authenticateAdmin();
        Long offerId = offerService.prepareOffer(f.projectId, "E2E " + tag + runId).getId();
        tx().executeWithoutResult(s -> entityManager.find(OfferEntity.class, offerId).setAiGenerated(true));
        String code = tx().execute(s -> entityManager.find(WorkItemEntity.class, f.workItemId).getCode());
        return new Setup(f, offerId, code);
    }

    private BigDecimal net(Long offerId) {
        return tx().execute(s -> offerDao.findById(offerId).orElseThrow().getTotalNet());
    }

    private BigDecimal snapshotNet(Long offerId) {
        return tx().execute(s -> offerDao.findById(offerId).orElseThrow().getSnapshot().totalNet());
    }

    /** Sum over the live estimate lines of round2(effective unit price x quantity), the matrix view of the total. */
    private BigDecimal effectiveNet(Long estimateId) {
        return tx().execute(st -> entityManager.createQuery(
                "select l from EstimateLineEntity l where l.estimate.id = :id", EstimateLineEntity.class)
                .setParameter("id", estimateId).getResultList().stream()
                .map(l -> (l.getUnitPriceOverride() != null ? l.getUnitPriceOverride() : l.getUnitPrice())
                        .multiply(l.getQuantity()).setScale(2, java.math.RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static void assertApi(Throwable t, int status, String code) {
        assertThat(t).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(status);
            assertThat(e.getMessageCode()).isEqualTo(code);
        });
    }

    @Test
    @DisplayName("e2e: set_volume, confirmed price adjust, undo, restore matches snapshot totals, then busy")
    void fullPath() throws Exception {
        Setup s = setup("E1");
        assertThat(net(s.offerId())).isEqualByComparingTo("40.00");

        TurnResult vol = chat.send(s.offerId(), "STUB:CALL set_volume {\"workCode\":\"" + s.workCode()
                + "\",\"roomId\":" + s.fx().roomId + ",\"quantity\":7,\"reason\":\"user asked\"}", "c1", null);
        assertThat(vol.applied()).isTrue();
        assertThat(net(s.offerId())).isEqualByComparingTo("70.00");

        TurnResult pending = chat.send(s.offerId(),
                "STUB:CALL adjust_price {\"scope\":\"PROJECT\",\"pct\":10,\"reason\":\"user asked\"}", "c2", null);
        assertThat(pending.applied()).isFalse();
        assertThat(net(s.offerId())).isEqualByComparingTo("70.00");
        Long pendingId = ((Number) pending.messages().get(1).pendingAction().get("id")).longValue();
        TurnResult confirmed = chat.send(s.offerId(), "", "c3", pendingId);
        assertThat(confirmed.applied()).isTrue();
        assertThat(net(s.offerId())).isEqualByComparingTo("77.00");

        // Restore of the price-adjusted snapshot: the restored lines carry the override (11.00) and their
        // effective net (override x quantity, as the estimate matrix shows it) equals the snapshot total.
        // estimates.total_net is not compared here: EstimateRecomputeService prices lines from unitPrice
        // only and ignores unitPriceOverride (pre-existing behaviour, reported with task 6.3).
        BigDecimal adjusted = snapshotNet(s.offerId());
        assertThat(adjusted).isEqualByComparingTo("77.00");
        OfferRestoreService.RestoreResult restoredAdjusted = restoreService.restore(s.offerId());
        assertThat(restoredAdjusted.restoredLines()).isEqualTo(1);
        assertThat(effectiveNet(s.fx().estimateId)).isEqualByComparingTo(adjusted);

        OfferAiChatService.UndoResult undo = chat.undo(s.offerId());
        assertThat(undo.snapshotVersion()).isGreaterThan(confirmed.snapshotVersion());
        assertThat(net(s.offerId())).isEqualByComparingTo("70.00");
        BigDecimal snapNet = snapshotNet(s.offerId());
        assertThat(snapNet).isEqualByComparingTo("70.00");
        assertThat(restoreService.restore(s.offerId()).estimateTotals().totalNet()).isEqualByComparingTo(snapNet);
        assertThat(effectiveNet(s.fx().estimateId)).isEqualByComparingTo(snapNet);

        ENTERED.set(new CountDownLatch(1));
        RELEASE.set(new CountDownLatch(1));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                authenticateAdmin();
                chat.send(s.offerId(), GATE + " hello", "c4", null);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        first.start();
        try {
            assertThat(ENTERED.get().await(30, TimeUnit.SECONDS)).isTrue();
            assertApi(org.assertj.core.api.Assertions.catchThrowable(
                    () -> chat.send(s.offerId(), "hello", "c5", null)), 409, OfferAiChatService.MSG_BUSY);
        } finally {
            RELEASE.get().countDown();
            first.join(30_000);
        }
        assertThat(failure.get()).isNull();
        assertThat(net(s.offerId())).isEqualByComparingTo("70.00");
    }
}
