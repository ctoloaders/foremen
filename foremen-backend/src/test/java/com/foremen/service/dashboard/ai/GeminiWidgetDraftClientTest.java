package com.foremen.service.dashboard.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foremen.service.dashboard.ai.WidgetDraftException.Reason;
import com.foremen.service.dashboard.config.DatasetSchema;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.config.WidgetConfigValidator;
import com.foremen.service.dashboard.dataset.DatasetCatalogDto;
import com.foremen.service.dashboard.dataset.DatasetCatalogDto.FieldDto;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Client logic of {@link GeminiWidgetDraftClient} over a fake transport: parsing, validation, bounded
 * retry with violation feedback, failure mapping and the prompt/schema contents.
 *
 * <p>Feature: FOR-10-dashboard. <b>Validates: Requirements 13.3, 13.5, 13.6</b>
 */
class GeminiWidgetDraftClientTest {

    private static final String SECRET = "TOP-SECRET-ADMIN-TEXT";

    private static final DatasetSchema SCHEMA = new DatasetSchema("PROJECTS", true, List.of(
            new DatasetSchema.Field("id", FieldType.ID, true, true, false, true),
            new DatasetSchema.Field("status", FieldType.ENUM, true, true, true, false)));

    private static final DatasetCatalogDto CATALOG = new DatasetCatalogDto("PROJECTS", List.of("PROJECTS"),
            true, false, false, null, List.of(
                    new FieldDto("id", FieldType.ID, "k.id", List.of(), true, true, false, true),
                    new FieldDto("status", FieldType.ENUM, "k.status", List.of("NEW"), true, true, true, false)));

    private static final DraftRequest REQUEST =
            new DraftRequest(SECRET, "ru", List.of(CATALOG), List.of("/users"));

    private final WidgetConfigValidator validator =
            new WidgetConfigValidator(code -> "PROJECTS".equals(code) ? Optional.of(SCHEMA) : Optional.empty());

    private static String answer(String field) {
        return """
                {"names":{"ru":"Проекты","pl":"Projekty"},"descriptions":{"ru":"d","pl":"d"},
                 "colSpan":1,"rowSpan":1,"projectScope":"NONE",
                 "explanation":{"ru":"e","pl":"e"},
                 "config":{"schemaVersion":1,"dataset":"PROJECTS","visualization":"KPI",
                   "title":{"ru":"t","pl":"t"},
                   "measures":[{"field":"%s","agg":"COUNT","alias":"n","label":{"ru":"n","pl":"n"}}]}}
                """.formatted(field);
    }

    /** Fake transport replaying scripted answers and recording the prompts it received. */
    private static final class Fake implements WidgetDraftTransport {
        final List<String> answers;
        final List<String> prompts = new ArrayList<>();
        Duration timeout;

        Fake(String... answers) {
            this.answers = new ArrayList<>(List.of(answers));
        }

        @Override
        public String generate(String system, String user, Map<String, Object> schema, Duration t) {
            prompts.add(user);
            timeout = t;
            return answers.remove(0);
        }
    }

    private GeminiWidgetDraftClient client(WidgetDraftTransport t) {
        return new GeminiWidgetDraftClient(t, validator, GeminiWidgetDraftClient.DEFAULT_CALL_TIMEOUT);
    }

    @Test
    void validAnswerIsReturnedWithoutRetry() {
        Fake fake = new Fake(answer("id"));
        DraftResponse r = client(fake).draft(REQUEST);

        assertThat(r.names().pl()).isEqualTo("Projekty");
        assertThat(r.config().path("dataset").asText()).isEqualTo("PROJECTS");
        assertThat(fake.prompts).hasSize(1);
        assertThat(fake.timeout).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void fencedJsonIsTolerated() {
        Fake fake = new Fake("```json\n" + answer("id") + "\n```");
        assertThat(client(fake).draft(REQUEST).colSpan()).isEqualTo(1);
    }

    @Test
    void invalidConfigIsRetriedOnceWithViolationsFedBack() {
        Fake fake = new Fake(answer("nope"), answer("id"));
        DraftResponse r = client(fake).draft(REQUEST);

        assertThat(r).isNotNull();
        assertThat(fake.prompts).hasSize(2);
        assertThat(fake.prompts.get(0)).doesNotContain("field.unknown");
        assertThat(fake.prompts.get(1)).contains("field.unknown").contains("measures[0].field");
    }

    @Test
    void staysInvalidAfterRetryFailsWithInvalidResponseAndViolations() {
        Fake fake = new Fake(answer("nope"), answer("nope"));

        assertThatThrownBy(() -> client(fake).draft(REQUEST))
                .isInstanceOfSatisfying(WidgetDraftException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.INVALID_RESPONSE);
                    assertThat(e.getViolations()).extracting(v -> v.code()).contains("field.unknown");
                    assertThat(e.getMessage()).doesNotContain(SECRET);
                });
        assertThat(fake.prompts).hasSize(2); // bounded: never a third call
    }

    @Test
    void malformedAndEmptyAnswersAreRetried() {
        Fake fake = new Fake("not json", "", answer("id"));
        GeminiWidgetDraftClient c = client(fake);

        assertThatThrownBy(() -> c.draft(REQUEST))
                .isInstanceOfSatisfying(WidgetDraftException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_RESPONSE));
        assertThat(fake.prompts).hasSize(2);
        assertThat(c.draft(REQUEST)).isNotNull();
    }

    @Test
    void structuralProblemsTriggerRetry() {
        String badSpan = answer("id").replace("\"colSpan\":1", "\"colSpan\":9");
        Fake fake = new Fake(badSpan, answer("id"));
        client(fake).draft(REQUEST);

        assertThat(fake.prompts.get(1)).contains("colSpan");
    }

    @Test
    void providerFailureIsNotRetried() {
        int[] calls = {0};
        WidgetDraftTransport down = (s, u, sc, t) -> {
            calls[0]++;
            throw new WidgetDraftException(Reason.TIMEOUT, "timed out");
        };

        assertThatThrownBy(() -> client(down).draft(REQUEST))
                .isInstanceOfSatisfying(WidgetDraftException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TIMEOUT));
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void llmUnavailableAndDisabledProviderErrorsPropagateUnchangedWithoutRetry() {
        for (Reason reason : List.of(Reason.LLM_UNAVAILABLE, Reason.DISABLED)) {
            int[] calls = {0};
            WidgetDraftTransport failing = (s, u, sc, t) -> {
                calls[0]++;
                throw new WidgetDraftException(reason, "provider error");
            };

            assertThatThrownBy(() -> client(failing).draft(REQUEST))
                    .isInstanceOfSatisfying(WidgetDraftException.class,
                            e -> assertThat(e.getReason()).isEqualTo(reason));
            assertThat(calls[0]).isEqualTo(1);
        }
    }

    @Test
    void errorAfterInvalidFirstAnswerIsStillSurfacedAsProviderError() {
        int[] calls = {0};
        WidgetDraftTransport t = (s, u, sc, to) -> {
            if (calls[0]++ == 0) {
                return answer("nope");
            }
            throw new WidgetDraftException(Reason.LLM_UNAVAILABLE, "down on retry");
        };

        assertThatThrownBy(() -> client(t).draft(REQUEST))
                .isInstanceOfSatisfying(WidgetDraftException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.LLM_UNAVAILABLE));
        assertThat(calls[0]).isEqualTo(2);
    }

    @Test
    void promptCarriesRequestAndCatalogOnlyAndSchemaRestrictsFields() {
        String user = WidgetDraftPrompts.user(REQUEST);
        assertThat(user).contains(SECRET).contains("\"code\":\"PROJECTS\"").contains("/users")
                .contains("aggregatable");

        Map<String, Object> schema = WidgetDraftSchemas.draft(REQUEST.catalog(), REQUEST.routes());
        assertThat(schema.toString()).contains("PROJECTS").contains("status").contains("/users")
                .contains("SINGLE_PROJECT");
    }

    @Test
    void stubAndDisabledClients() {
        DraftResponse r = new StubWidgetDraftClient().draft(REQUEST);
        assertThat(validator.validate(r.config(), r.singleProject()).valid()).isTrue();

        DraftRequest down = new DraftRequest(StubWidgetDraftClient.FAIL_MARKER, null, List.of(CATALOG), List.of());
        assertThatThrownBy(() -> new StubWidgetDraftClient().draft(down))
                .isInstanceOfSatisfying(WidgetDraftException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.LLM_UNAVAILABLE));

        DisabledWidgetDraftClient disabled = new DisabledWidgetDraftClient();
        assertThat(disabled.isEnabled()).isFalse();
        assertThatThrownBy(() -> disabled.draft(REQUEST))
                .isInstanceOfSatisfying(WidgetDraftException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.DISABLED));
    }
}
