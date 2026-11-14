package com.foremen.service.nip;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.config.nip.NipLookupConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * FOR-11-00 task 2.2 — exactly one {@link NipLookupService} is selected by the configuration.
 *
 * <p><b>Validates: Requirements 6.2, 6.3</b>
 */
class NipLookupBeanSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NipLookupConfig.class, RestNipLookupService.class,
                    DisabledNipLookupService.class);

    @Test
    void defaultsToDisabledService() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(NipLookupService.class);
            assertThat(ctx).hasSingleBean(DisabledNipLookupService.class);
            assertThat(ctx.getBean(NipLookupService.class).isConfigured()).isFalse();
        });
    }

    @Test
    void enabledWithoutBaseUrlStaysDisabled() {
        runner.withPropertyValues("foremen.nip-lookup.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(DisabledNipLookupService.class);
            assertThat(ctx).doesNotHaveBean(RestNipLookupService.class);
        });
    }

    @Test
    void baseUrlWithoutEnabledStaysDisabled() {
        runner.withPropertyValues("foremen.nip-lookup.base-url=https://r.example.test/x").run(ctx -> {
            assertThat(ctx).hasSingleBean(DisabledNipLookupService.class);
            assertThat(ctx).doesNotHaveBean(RestNipLookupService.class);
        });
    }

    @Test
    void enabledWithBaseUrlSelectsRestService() {
        runner.withPropertyValues("foremen.nip-lookup.enabled=true",
                "foremen.nip-lookup.base-url=https://r.example.test/x",
                "foremen.nip-lookup.api-key=k").run(ctx -> {
            assertThat(ctx).hasSingleBean(NipLookupService.class);
            assertThat(ctx).hasSingleBean(RestNipLookupService.class);
            assertThat(ctx).doesNotHaveBean(DisabledNipLookupService.class);
            assertThat(ctx.getBean(NipLookupService.class).isConfigured()).isTrue();
        });
    }
}
