package com.foremen.service.estimateai.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.estimateai.EstimateAiConfig;
import com.foremen.config.estimateai.EstimateAiProperties;
import com.foremen.service.estimateai.ai.EstimateAiException.Reason;
import com.foremen.service.estimateai.ai.OfferChatClient.ChatRequest;
import com.foremen.service.estimateai.ai.OfferChatClient.ChatResponse;
import com.foremen.service.estimateai.ai.OfferChatClient.ToolResult;
import com.foremen.service.estimateai.ai.EstimatePlanClient.PlanRequest;

/**
 * Properties defaults, executor sizing, prompt resources and Disabled/Stub bean selection.
 *
 * <p>Feature: FOR-05-04-estimate-AI. <b>Validates: Requirements 2.8, 7.1, 7.2, 7.4, 7.5, 18.1, 18.4</b>
 */
class EstimateAiConfigurationTest {

    @Configuration
    @Import({EstimateAiConfig.class, DisabledEstimatePlanClient.class, DisabledOfferChatClient.class,
            StubEstimatePlanClient.class, StubOfferChatClient.class})
    @EnableConfigurationProperties(EstimateAiProperties.class)
    static class Wiring {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsMatchRequirement() {
        runner.run(ctx -> {
            EstimateAiProperties p = ctx.getBean(EstimateAiProperties.class);
            assertThat(p.enabled()).isFalse();
            assertThat(p.maxCatalogChars()).isEqualTo(120_000);
            assertThat(p.volumeTolerancePct()).isEqualTo(5.0);
            assertThat(p.scratchTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(p.timeout()).isEqualTo(Duration.ofMinutes(20));
            assertThat(p.chat().maxToolRounds()).isEqualTo(8);
            assertThat(p.chat().historyTurns()).isEqualTo(12);
            assertThat(p.chat().confirmThreshold()).isEqualTo(20);
            assertThat(p.chat().rateLimit()).isEqualTo(60);
            assertThat(p.llm().stub()).isFalse();
        });
    }

    @Test
    void executorHasTwoThreadsAndQueueTen() {
        runner.run(ctx -> {
            ThreadPoolTaskExecutor ex = ctx.getBean("estimateAiTaskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(ex.getCorePoolSize()).isEqualTo(2);
            assertThat(ex.getMaxPoolSize()).isEqualTo(2);
            assertThat(ex.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(10);
        });
    }

    @Test
    void promptsAreVersionedEnglishAndHaveNoPriceTable() {
        for (EstimateAiPrompts.Prompt p : List.of(EstimateAiPrompts.system(), EstimateAiPrompts.chatSystem())) {
            assertThat(p.version()).isEqualTo("1.0");
            assertThat(p.text()).isNotBlank().doesNotContain("prompt-version");
            assertThat(p.text()).doesNotContain("PRACE WSTĘPNE").doesNotContain("Zabezpieczenie na czas prac");
        }
        assertThat(EstimateAiPrompts.system().text()).contains("catalog `code`").contains("user instruction");
    }

    @Test
    void disabledClientsWhenEnabledFalse() {
        runner.withPropertyValues("foremen.estimate-ai.enabled=false", "foremen.estimate-ai.llm.stub=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EstimatePlanClient.class).hasSingleBean(OfferChatClient.class);
                    assertThat(ctx).hasSingleBean(DisabledEstimatePlanClient.class);
                    assertThat(ctx).hasSingleBean(DisabledOfferChatClient.class);
                    EstimatePlanClient plan = ctx.getBean(EstimatePlanClient.class);
                    assertThat(plan.isEnabled()).isFalse();
                    assertThatThrownBy(() -> plan.plan(new PlanRequest("s", "u", "c", "r", List.of(1L), List.of("1.01"))))
                            .isInstanceOfSatisfying(EstimateAiException.class,
                                    e -> assertThat(e.reason()).isEqualTo(Reason.DISABLED));
                });
    }

    @Test
    void stubPlanIsDeterministic() {
        runner.withPropertyValues("foremen.estimate-ai.enabled=true", "foremen.estimate-ai.llm.stub=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(StubEstimatePlanClient.class);
                    EstimatePlanClient c = ctx.getBean(EstimatePlanClient.class);
                    PlanRequest req = new PlanRequest("s", "plan it", "c", "r", List.of(7L, 8L), List.of("1.01", "1.02"));
                    JsonNode a = c.plan(req).plan();
                    JsonNode b = c.plan(req).plan();
                    assertThat(a).isEqualTo(b);
                    assertThat(a.at("/works/0/workCode").asText()).isEqualTo("1.01");
                    assertThat(a.at("/works/0/roomIds")).hasSize(2);
                    assertThat(a.at("/works/0/rationale").asText()).isNotBlank();
                    assertThat(c.plan(new PlanRequest("s", "STUB:EMPTY", "c", "r", List.of(7L), List.of("1.01")))
                            .plan().get("works")).isEmpty();
                    assertThatThrownBy(() -> c.plan(new PlanRequest("s", "STUB:FAIL", "c", "r", List.of(), List.of())))
                            .isInstanceOfSatisfying(EstimateAiException.class,
                                    e -> assertThat(e.reason()).isEqualTo(Reason.LLM_UNAVAILABLE));
                });
    }

    @Test
    void stubChatEmitsDeterministicToolCallThenText() {
        runner.withPropertyValues("foremen.estimate-ai.enabled=true", "foremen.estimate-ai.llm.stub=true")
                .run(ctx -> {
                    OfferChatClient c = ctx.getBean(OfferChatClient.class);
                    ChatRequest first = new ChatRequest("s", "snap", List.of(),
                            "STUB:CALL set_volume {\"workCode\":\"1.01\",\"value\":5,\"reason\":\"r\"}", List.of(), null);
                    ChatResponse r1 = c.chat(first);
                    assertThat(r1.toolCalls()).hasSize(1);
                    assertThat(r1.toolCalls().get(0).name()).isEqualTo("set_volume");
                    assertThat(r1.toolCalls().get(0).args().get("value").asInt()).isEqualTo(5);
                    assertThat(c.chat(first)).isEqualTo(r1);

                    ChatResponse r2 = c.chat(new ChatRequest("s", "snap", List.of(), first.userMessage(),
                            List.of(new ToolResult("set_volume", mapper.createObjectNode())), null));
                    assertThat(r2.toolCalls()).isEmpty();
                    assertThat(r2.text()).isEqualTo(StubOfferChatClient.DONE_TEXT);

                    assertThat(c.chat(new ChatRequest("s", "snap", List.of(), "hello", List.of(), null)).text())
                            .isEqualTo(StubOfferChatClient.PLAIN_TEXT);
                });
    }
}
