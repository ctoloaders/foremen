package com.foremen.service.bank.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * FOR-07-04 task 6.9 (Requirement 15.1, 15.2, 15.3, 15.6, 15.7, 24.3) — the Gemini classifier with a
 * stubbed model call, plus the conditional wiring (exactly one classifier bean).
 */
class GeminiBankTransactionClassifierTest {

    private static final BankAiProperties PROPS =
            new BankAiProperties(true, "gemini-test", null, null, null, null, null);

    private static ClassificationInput input(String ibanField) {
        return new ClassificationInput(
                ClassificationInput.EXPENSE,
                new BigDecimal("200.00"),
                "PLN",
                LocalDate.of(2026, 3, 14),
                "Paliwo",
                "ORLEN SA",
                ibanField,
                "mBank",
                List.of(new ClassificationInput.Candidate("c1", "WORKER_PAYOUT", "Jan Kowalski",
                        new BigDecimal("200.00"), LocalDate.of(2026, 3, 13))),
                List.of(new ClassificationInput.Category("FUEL", "Paliwo", "Топливо")),
                List.of(new ClassificationInput.Project(7L, "Remont Mokotów", "Warszawa")));
    }

    @Test
    void validAnswerInFencesIsParsedAndValidated() {
        AtomicReference<String> model = new AtomicReference<>();
        AtomicReference<String> system = new AtomicReference<>();
        AtomicReference<String> user = new AtomicReference<>();
        var classifier = new GeminiBankTransactionClassifier(PROPS, (m, s, u) -> {
            model.set(m);
            system.set(s);
            user.set(u);
            return "```json\n{\"kind\":\"OTHER_EXPENSE\",\"candidateRef\":null,\"categoryCode\":\"fuel\","
                    + "\"projectId\":7,\"confidence\":0.82,\"rationale\":\"Tankowanie\"}\n```";
        });

        Optional<ClassifierAnswer> answer = classifier.classify(input("9999"));

        assertThat(answer).contains(
                new ClassifierAnswer("OTHER_EXPENSE", null, "FUEL", 7L, 0.82, "Tankowanie"));
        assertThat(model.get()).isEqualTo("gemini-test");
        assertThat(system.get()).contains("JSON ONLY").contains("WORKER_PAYOUT").contains("CLIENT_PAYMENT");
        assertThat(user.get())
                .contains("TRANSACTION:", "CANDIDATES:", "CATEGORIES:", "PROJECTS:")
                .contains("\"counterpartyIbanLast4\":\"9999\"")
                .contains("\"candidateRef\":\"c1\"")
                .contains("\"code\":\"FUEL\"")
                .contains("\"id\":7");
        assertThat(classifier.isEnabled()).isTrue();
    }

    @Test
    void fullIbanIsMaskedToLast4InThePrompt() {
        AtomicReference<String> user = new AtomicReference<>();
        var classifier = new GeminiBankTransactionClassifier(PROPS, (m, s, u) -> {
            user.set(u);
            return "{\"kind\":\"OTHER_EXPENSE\",\"confidence\":0.5}";
        });

        classifier.classify(input("PL61 1090 1014 0000 0712 1981 2874"));

        assertThat(user.get()).contains("\"counterpartyIbanLast4\":\"2874\"")
                .doesNotContain("PL61").doesNotContain("10901014");
    }

    @Test
    void invalidFieldsAreDroppedAndConfidenceCapped() {
        var classifier = new GeminiBankTransactionClassifier(PROPS, (m, s, u) ->
                "Sure! {\"kind\":\"WORKER_PAYOUT\",\"candidateRef\":\"nope\",\"categoryCode\":null,"
                        + "\"projectId\":999,\"confidence\":0.95,\"rationale\":\"x\"} done");

        ClassifierAnswer answer = classifier.classify(input("9999")).orElseThrow();

        assertThat(answer.kind()).isEqualTo("WORKER_PAYOUT");
        assertThat(answer.candidateRef()).isNull();
        assertThat(answer.projectId()).isNull();
        assertThat(answer.confidence()).isEqualTo(ClassifierAnswerValidator.CAP);
    }

    @Test
    void kindNotAllowedForDirectionYieldsEmpty() {
        var classifier = new GeminiBankTransactionClassifier(PROPS, (m, s, u) ->
                "{\"kind\":\"CLIENT_PAYMENT\",\"confidence\":0.9}");

        assertThat(classifier.classify(input("9999"))).isEmpty();
    }

    @Test
    void failuresYieldEmpty() {
        assertThat(new GeminiBankTransactionClassifier(PROPS, (m, s, u) -> {
            throw new IllegalStateException("quota");
        }).classify(input("9999"))).isEmpty();
        assertThat(new GeminiBankTransactionClassifier(PROPS, (m, s, u) -> "  ")
                .classify(input("9999"))).isEmpty();
        assertThat(new GeminiBankTransactionClassifier(PROPS, (m, s, u) -> "no json here")
                .classify(input("9999"))).isEmpty();
        assertThat(new GeminiBankTransactionClassifier(PROPS, (m, s, u) -> null)
                .classify(input("9999"))).isEmpty();
        assertThat(new GeminiBankTransactionClassifier(PROPS, (m, s, u) -> "{}")
                .classify(null)).isEmpty();
    }

    @Test
    void missingModelYieldsEmptyWithoutCalling() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        var props = new BankAiProperties(true, " ", null, null, null, null, null);
        var classifier = new GeminiBankTransactionClassifier(props, (m, s, u) -> {
            called.set(true);
            return "{}";
        });

        assertThat(classifier.classify(input("9999"))).isEmpty();
        assertThat(called.get()).isFalse();
    }

    @Test
    void disabledClassifierNeverAnswers() {
        var disabled = new DisabledBankTransactionClassifier();
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.classify(input("9999"))).isEmpty();
    }

    @Test
    void propertiesDefaultMaxPerRun() {
        assertThat(PROPS.maxPerRun()).isEqualTo(BankAiProperties.DEFAULT_MAX_PER_RUN);
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BankAiConfig.class, GeminiBankTransactionClassifier.class,
                    DisabledBankTransactionClassifier.class);

    @Test
    void exactlyOneClassifierBeanIsWired() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(BankTransactionClassifier.class);
            assertThat(ctx.getBean(BankTransactionClassifier.class))
                    .isInstanceOf(DisabledBankTransactionClassifier.class);
        });
        runner.withPropertyValues("foremen.bank-ledger.ai.enabled=false").run(ctx ->
                assertThat(ctx.getBean(BankTransactionClassifier.class))
                        .isInstanceOf(DisabledBankTransactionClassifier.class));
        runner.withPropertyValues("foremen.bank-ledger.ai.enabled=true",
                "foremen.bank-ledger.ai.model=gemini-x",
                "foremen.bank-ledger.ai.max-per-run=50").run(ctx -> {
            assertThat(ctx).hasSingleBean(BankTransactionClassifier.class);
            assertThat(ctx.getBean(BankTransactionClassifier.class))
                    .isInstanceOf(GeminiBankTransactionClassifier.class);
            assertThat(ctx.getBean(BankAiProperties.class).maxPerRun()).isEqualTo(50);
        });
    }
}
