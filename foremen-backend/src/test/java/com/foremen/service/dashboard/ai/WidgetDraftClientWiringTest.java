package com.foremen.service.dashboard.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foremen.config.dashboard.DashboardAiProperties;
import com.foremen.service.dashboard.ai.WidgetDraftException.Reason;
import com.foremen.service.dashboard.config.WidgetConfigValidator;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Bean selection of {@link WidgetDraftClient} (exactly one active per configuration) and provider
 * error mapping of {@link GenAiWidgetDraftTransport} that needs no network.
 *
 * <p>Feature: FOR-10-dashboard. <b>Validates: Requirements 13.3, 13.6, 13.8</b>
 */
class WidgetDraftClientWiringTest {

    @Configuration
    @Import({DisabledWidgetDraftClient.class, StubWidgetDraftClient.class, GeminiWidgetDraftClient.class})
    static class Wiring {
        @Bean
        DashboardAiProperties dashboardAiProperties() {
            return new DashboardAiProperties(true, 10, null);
        }

        @Bean
        WidgetConfigValidator widgetConfigValidator() {
            return new WidgetConfigValidator(code -> Optional.empty());
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    @Test
    void disabledByDefaultWhenPropertyMissing() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(WidgetDraftClient.class);
            assertThat(ctx).hasSingleBean(DisabledWidgetDraftClient.class);
            assertThat(ctx.getBean(WidgetDraftClient.class).isEnabled()).isFalse();
        });
    }

    @Test
    void disabledWhenEnabledFalseEvenIfStubTrue() {
        runner.withPropertyValues("foremen.dashboard.ai.enabled=false", "foremen.dashboard.ai.llm.stub=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(WidgetDraftClient.class);
                    assertThat(ctx).hasSingleBean(DisabledWidgetDraftClient.class);
                });
    }

    @Test
    void stubWhenEnabledAndStub() {
        runner.withPropertyValues("foremen.dashboard.ai.enabled=true", "foremen.dashboard.ai.llm.stub=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(WidgetDraftClient.class);
                    assertThat(ctx).hasSingleBean(StubWidgetDraftClient.class);
                    assertThat(ctx.getBean(WidgetDraftClient.class).isEnabled()).isTrue();
                });
    }

    @Test
    void geminiWhenEnabledAndNotStub() {
        runner.withPropertyValues("foremen.dashboard.ai.enabled=true", "foremen.dashboard.ai.llm.stub=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(WidgetDraftClient.class);
                    assertThat(ctx).hasSingleBean(GeminiWidgetDraftClient.class);
                    assertThat(ctx.getBean(WidgetDraftClient.class).isEnabled()).isTrue();
                });
    }

    @Test
    void geminiWhenEnabledAndStubPropertyAbsent() {
        runner.withPropertyValues("foremen.dashboard.ai.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(WidgetDraftClient.class);
            assertThat(ctx).hasSingleBean(GeminiWidgetDraftClient.class);
        });
    }

    @Test
    void transportWithoutModelIsMappedToDisabled() {
        DashboardAiProperties props = new DashboardAiProperties(true, 10,
                new DashboardAiProperties.Llm(false, " ", null, null, null, null));
        GenAiWidgetDraftTransport transport = new GenAiWidgetDraftTransport(props);

        assertThatThrownBy(() -> transport.generate("sys", "user", Map.of(), Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(WidgetDraftException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.DISABLED));
    }

    @Test
    void transportRuntimeFailureIsMappedToLlmUnavailable() {
        // Model set but no credentials/project/key: client construction or the call fails with a
        // RuntimeException, which must surface as a handled LLM_UNAVAILABLE / TIMEOUT, never raw.
        DashboardAiProperties props = new DashboardAiProperties(true, 10,
                new DashboardAiProperties.Llm(false, "gemini-test", null, null, null, null));
        GenAiWidgetDraftTransport transport = new GenAiWidgetDraftTransport(props);

        assertThatThrownBy(() -> transport.generate("sys", "user", Map.of(), Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(WidgetDraftException.class,
                        e -> assertThat(e.getReason()).isIn(Reason.LLM_UNAVAILABLE, Reason.TIMEOUT));
    }
}
