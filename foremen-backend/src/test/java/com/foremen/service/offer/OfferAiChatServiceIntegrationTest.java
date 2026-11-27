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
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;
import com.foremen.service.estimate.EstimateScratchService;
import com.foremen.service.estimate.EstimateScratchService.Seed;
import com.foremen.service.estimateai.ai.OfferChatClient;
import com.foremen.service.estimateai.ai.StubOfferChatClient;
import com.foremen.service.estimateai.chat.OfferAiChatService;
import com.foremen.service.estimateai.chat.OfferAiChatService.MessageDto;
import com.foremen.service.estimateai.chat.OfferAiChatService.TurnResult;

/**
 * FOR-05-04 task 4.2 (Requirements 10.1-10.8, 11.5-11.9, 9.5): {@link OfferAiChatService} with the stub
 * chat model against Testcontainers. Repeatability: per-run ids; rows are removed by the exploration
 * support clean-up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "foremen.estimate-ai.enabled=true",
        "foremen.estimate-ai.llm.stub=true",
        "foremen.estimate-ai.chat.rate-limit=6",
        "foremen.estimate-ai.chat.confirm-threshold=20"
})
@Testcontainers
@ActiveProfiles("integration-test")
@Import(OfferAiChatServiceIntegrationTest.GateConfig.class)
class OfferAiChatServiceIntegrationTest extends OfferExplorationSupport {

    static final String GATE = "GATE";
    static final AtomicReference<CountDownLatch> ENTERED = new AtomicReference<>(new CountDownLatch(1));
    static final AtomicReference<CountDownLatch> RELEASE = new AtomicReference<>(new CountDownLatch(1));

    /** The stub, except that a message containing {@code GATE} blocks until released (concurrency test). */
    @TestConfiguration
    static class GateConfig {
        @Bean
        @Primary
        OfferChatClient gatedChatClient(StubOfferChatClient stub) {
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

    private record Setup(Fx fx, Long offerId, String workCode) {
    }

    private Setup setup(String tag) {
        Fx f = emptyEstimateFixture(tag);
        addLine(f, new BigDecimal("10.00"), new BigDecimal("4.00"));
        authenticateAdmin();
        Long offerId = offerService.prepareOffer(f.projectId, "Chat " + tag + runId).getId();
        tx().executeWithoutResult(s -> entityManager.find(OfferEntity.class, offerId).setAiGenerated(true));
        String code = tx().execute(s -> entityManager.find(WorkItemEntity.class, f.workItemId).getCode());
        return new Setup(f, offerId, code);
    }

    private static String setVolume(Setup s, String qty) {
        return "STUB:CALL set_volume {\"workCode\":\"" + s.workCode() + "\",\"roomId\":" + s.fx().roomId
                + ",\"quantity\":" + qty + ",\"reason\":\"user asked\"}";
    }

    private static String adjustProject(String pct) {
        return "STUB:CALL adjust_price {\"scope\":\"PROJECT\",\"pct\":" + pct + ",\"reason\":\"user asked\"}";
    }

    private BigDecimal net(Long offerId) {
        return tx().execute(s -> offerDao.findById(offerId).orElseThrow().getTotalNet());
    }

    private OfferEntity offer(Long offerId) {
        return tx().execute(s -> {
            OfferEntity o = offerDao.findById(offerId).orElseThrow();
            o.getAiReport();
            return o;
        });
    }

    private static void assertApi(Throwable t, int status, String code) {
        assertThat(t).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(status);
            assertThat(e.getMessageCode()).isEqualTo(code);
        });
    }

    @Test
    @DisplayName("apply: a set_volume turn replaces the snapshot, bumps the revision, stores snapshot_before, appends changes[]")
    @SuppressWarnings("unchecked")
    void apply() {
        Setup s = setup("A1");
        TurnResult r = chat.send(s.offerId(), setVolume(s, "7"), "c1", null);
        assertThat(r.applied()).isTrue();
        assertThat(r.messages()).hasSize(2);
        assertThat(r.messages().get(0).role()).isEqualTo("USER");
        assertThat(r.messages().get(1).role()).isEqualTo("ASSISTANT");
        assertThat(r.messages().get(1).content()).isEqualTo(StubOfferChatClient.DONE_TEXT);
        assertThat(r.snapshotVersion()).isEqualTo(2);
        assertThat(r.offer().totalNet()).isEqualByComparingTo("70.00");
        assertThat(net(s.offerId())).isEqualByComparingTo("70.00");
        assertThat(r.messages().get(1).versionBefore()).isEqualTo(1);
        assertThat(r.messages().get(1).versionAfter()).isEqualTo(2);
        List<MessageDto> history = chat.history(s.offerId());
        assertThat(history).hasSize(2);
        List<Map<String, Object>> changes = (List<Map<String, Object>>) offer(s.offerId()).getAiReport().get("changes");
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0)).containsEntry("summaryCode", "chat.edit");
    }

    @Test
    @DisplayName("confirm: a project-wide price adjustment ends with a pendingAction, confirmPendingId applies it")
    void confirm() {
        Setup s = setup("C1");
        TurnResult pending = chat.send(s.offerId(), adjustProject("10"), "c1", null);
        assertThat(pending.applied()).isFalse();
        Map<String, Object> action = pending.messages().get(1).pendingAction();
        assertThat(action).isNotNull().containsKey("id");
        assertThat(net(s.offerId())).isEqualByComparingTo("40.00");
        assertThat(((Number) action.get("totalNetAfter")).doubleValue()).isEqualTo(44.0);
        Long pendingId = ((Number) action.get("id")).longValue();
        TurnResult done = chat.send(s.offerId(), "", "c2", pendingId);
        assertThat(done.applied()).isTrue();
        assertThat(net(s.offerId())).isEqualByComparingTo("44.00");
        // The pending action expired with the confirming turn.
        assertApi(catchThrowable(() -> chat.send(s.offerId(), "", "c3", pendingId)), 409,
                OfferAiChatService.MSG_PENDING_EXPIRED);
    }

    @Test
    @DisplayName("undo: restores snapshot_before (version + 1); an older turn is then undo_stale")
    void undoAndStale() {
        Setup s = setup("U1");
        chat.send(s.offerId(), setVolume(s, "7"), "c1", null);
        chat.send(s.offerId(), setVolume(s, "9"), "c2", null);
        assertThat(net(s.offerId())).isEqualByComparingTo("90.00");
        OfferAiChatService.UndoResult u = chat.undo(s.offerId());
        assertThat(u.snapshotVersion()).isEqualTo(4);
        assertThat(net(s.offerId())).isEqualByComparingTo("70.00");
        assertThat(chat.history(s.offerId()).stream().filter(MessageDto::undone)).hasSize(1);
        // The turn before is no longer the newest change: undo_stale.
        assertApi(catchThrowable(() -> chat.undo(s.offerId())), 409, OfferAiChatService.MSG_UNDO_STALE);
        assertThat(net(s.offerId())).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("undo with nothing applied is undo_stale")
    void undoNothing() {
        Setup s = setup("U2");
        assertApi(catchThrowable(() -> chat.undo(s.offerId())), 409, OfferAiChatService.MSG_UNDO_STALE);
    }

    @Test
    @DisplayName("busy: a concurrent turn on the same offer gets 409 error.estimateai.chat.busy")
    void busy() throws Exception {
        Setup s = setup("B1");
        ENTERED.set(new CountDownLatch(1));
        RELEASE.set(new CountDownLatch(1));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                authenticateAdmin();
                chat.send(s.offerId(), GATE + " hello", "c1", null);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        first.start();
        try {
            assertThat(ENTERED.get().await(30, TimeUnit.SECONDS)).isTrue();
            assertApi(catchThrowable(() -> chat.send(s.offerId(), "hello", "c2", null)), 409,
                    OfferAiChatService.MSG_BUSY);
        } finally {
            RELEASE.get().countDown();
            first.join(30_000);
        }
        assertThat(failure.get()).isNull();
        assertThat(chat.history(s.offerId())).hasSize(2);
    }

    @Test
    @DisplayName("rate limit: the turn over foremen.estimate-ai.chat.rate-limit gets 429")
    void rateLimit() {
        Setup s = setup("R1");
        for (int i = 0; i < 6; i++) {
            chat.send(s.offerId(), "hello " + i, "c" + i, null);
        }
        assertApi(catchThrowable(() -> chat.send(s.offerId(), "one more", "c9", null)), 429,
                OfferAiChatService.MSG_RATE);
    }

    @Test
    @DisplayName("non-DRAFT offer: 409 error.offer.not.editable")
    void nonDraft() {
        Setup s = setup("N1");
        offerService.withdraw(s.offerId());
        assertApi(catchThrowable(() -> chat.send(s.offerId(), "hello", "c1", null)), 409,
                OfferAiChatService.MSG_NOT_EDITABLE);
    }

    @Test
    @DisplayName("CLIENT: history, send and undo are 404")
    void clientNotFound() {
        Setup s = setup("L1");
        Long clientId = createClientUser();
        authenticateAs(clientId, "ROLE_CLIENT");
        assertApi(catchThrowable(() -> chat.history(s.offerId())), 404, OfferAiChatService.MSG_NOT_FOUND);
        assertApi(catchThrowable(() -> chat.send(s.offerId(), "hello", "c1", null)), 404,
                OfferAiChatService.MSG_NOT_FOUND);
        assertApi(catchThrowable(() -> chat.undo(s.offerId())), 404, OfferAiChatService.MSG_NOT_FOUND);
    }

    @Test
    @DisplayName("model failure: 503 error.estimateai.unavailable and nothing is persisted")
    void modelFailurePersistsNothing() {
        Setup s = setup("F1");
        assertApi(catchThrowable(() -> chat.send(s.offerId(), "STUB:FAIL", "c1", null)), 503,
                "error.estimateai.unavailable");
        assertThat(chat.history(s.offerId())).isEmpty();
        assertThat(net(s.offerId())).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("a rejected tool call (unknown work) changes nothing but the turn is recorded")
    void toolErrorAppliesNothing() {
        Setup s = setup("T1");
        TurnResult r = chat.send(s.offerId(), "STUB:CALL set_volume {\"workCode\":\"NOPE\",\"roomId\":1,"
                + "\"quantity\":3,\"reason\":\"x\"}", "c1", null);
        assertThat(r.applied()).isFalse();
        assertThat(r.snapshotVersion()).isEqualTo(1);
        assertThat(net(s.offerId())).isEqualByComparingTo("40.00");
    }

    private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
        return org.assertj.core.api.Assertions.catchThrowable(c);
    }
}
