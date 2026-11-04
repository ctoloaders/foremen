package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-07-04 task 2.5 — tolerant parse (R15.3) and validation (R15.4) of classifier answers.
 */
class ClassifierAnswerValidatorTest {

    private static ClassificationInput input(String direction) {
        return new ClassificationInput(direction, new BigDecimal("200.00"), "PLN",
                LocalDate.of(2026, 3, 1), "Paliwo", "Orlen", "1234", "mBank",
                List.of(new ClassificationInput.Candidate("c1", "PROCUREMENT", "FV 12/2026",
                        new BigDecimal("200.00"), LocalDate.of(2026, 2, 28))),
                List.of(new ClassificationInput.Category("FUEL", "Paliwo", "Топливо")),
                List.of(new ClassificationInput.Project(7L, "Remont", "Warszawa")));
    }

    // --- parse ---------------------------------------------------------------------------------

    @Test
    void parsesBareJson() {
        ClassifierAnswer a = ClassifierAnswer.parse(
                "{\"kind\":\"OTHER_EXPENSE\",\"candidateRef\":null,\"categoryCode\":\"FUEL\","
                        + "\"projectId\":7,\"confidence\":0.8,\"rationale\":\"Stacja paliw\"}");
        assertThat(a).isEqualTo(new ClassifierAnswer("OTHER_EXPENSE", null, "FUEL", 7L, 0.8,
                "Stacja paliw"));
    }

    @Test
    void parsesFencedJsonAndJsonInProse() {
        String fenced = "```json\n{\"kind\":\"PROCUREMENT\",\"candidateRef\":\"c1\",\"confidence\":0.9}\n```";
        String prose = "Here you go: {\"kind\":\"PROCUREMENT\",\"candidateRef\":\"c1\","
                + "\"confidence\":\"0.9\",\"rationale\":\"a {b} c\"} thanks";
        assertThat(ClassifierAnswer.parse(fenced).candidateRef()).isEqualTo("c1");
        ClassifierAnswer p = ClassifierAnswer.parse(prose);
        assertThat(p.kind()).isEqualTo("PROCUREMENT");
        assertThat(p.confidence()).isEqualTo(0.9);
        assertThat(p.rationale()).isEqualTo("a {b} c");
    }

    @Test
    void unparseableReturnsNull() {
        assertThat(ClassifierAnswer.parse(null)).isNull();
        assertThat(ClassifierAnswer.parse("no json here")).isNull();
        assertThat(ClassifierAnswer.parse("[1,2]")).isNull();
        assertThat(ClassifierAnswer.parse("{\"kind\": ")).isNull();
    }

    @Test
    void stringProjectIdIsRead() {
        assertThat(ClassifierAnswer.parse("{\"kind\":\"OTHER_EXPENSE\",\"projectId\":\"7\"}")
                .projectId()).isEqualTo(7L);
    }

    // --- validate ------------------------------------------------------------------------------

    @Test
    void validAnswerIsKeptUnchanged() {
        ClassifierAnswer a = new ClassifierAnswer("OTHER_EXPENSE", null, "FUEL", 7L, 0.85, "ok");
        ClassifierAnswer v = ClassifierAnswerValidator.validate(a, input("EXPENSE"));
        assertThat(v).isEqualTo(a);
        assertThat(v.usable()).isTrue();
    }

    @Test
    void validCandidateRefIsKept() {
        ClassifierAnswer a = new ClassifierAnswer("procurement", "c1", null, null, 0.7, null);
        ClassifierAnswer v = ClassifierAnswerValidator.validate(a, input("EXPENSE"));
        assertThat(v.kind()).isEqualTo("PROCUREMENT");
        assertThat(v.candidateRef()).isEqualTo("c1");
        assertThat(v.confidence()).isEqualTo(0.7);
    }

    @Test
    void kindNotAllowedForDirectionIsDroppedAndCapped() {
        ClassifierAnswer a = new ClassifierAnswer("CLIENT_PAYMENT", null, null, null, 0.95, "x");
        ClassifierAnswer v = ClassifierAnswerValidator.validate(a, input("EXPENSE"));
        assertThat(v.kind()).isNull();
        assertThat(v.usable()).isFalse();
        assertThat(v.confidence()).isEqualTo(0.3);
    }

    @Test
    void incomeAllowsClientPayment() {
        ClassifierAnswer a = new ClassifierAnswer("CLIENT_PAYMENT", null, null, null, 0.95, "x");
        assertThat(ClassifierAnswerValidator.validate(a, input("INCOME")).kind())
                .isEqualTo("CLIENT_PAYMENT");
        ClassifierAnswer w = new ClassifierAnswer("WORKER_PAYOUT", null, null, null, 0.95, "x");
        assertThat(ClassifierAnswerValidator.validate(w, input("INCOME")).kind()).isNull();
    }

    @Test
    void unknownCandidateCategoryAndProjectAreDroppedAndCapped() {
        ClassifierAnswer a = new ClassifierAnswer("OTHER_EXPENSE", "c999", "NOPE", 99L, 0.9, "x");
        ClassifierAnswer v = ClassifierAnswerValidator.validate(a, input("EXPENSE"));
        assertThat(v.kind()).isEqualTo("OTHER_EXPENSE");
        assertThat(v.candidateRef()).isNull();
        assertThat(v.categoryCode()).isNull();
        assertThat(v.projectId()).isNull();
        assertThat(v.confidence()).isEqualTo(0.3);
    }

    @Test
    void lowConfidenceIsNotRaisedByTheCap() {
        ClassifierAnswer a = new ClassifierAnswer("OTHER_EXPENSE", null, "NOPE", null, 0.1, null);
        assertThat(ClassifierAnswerValidator.validate(a, input("EXPENSE")).confidence())
                .isEqualTo(0.1);
    }

    @Test
    void confidenceIsClampedAndRationaleTrimmed() {
        ClassifierAnswer a = new ClassifierAnswer("OTHER_EXPENSE", null, "fuel", null, 1.7,
                "x".repeat(500));
        ClassifierAnswer v = ClassifierAnswerValidator.validate(a, input("EXPENSE"));
        assertThat(v.categoryCode()).isEqualTo("FUEL");
        assertThat(v.confidence()).isEqualTo(1.0);
        assertThat(v.rationale()).hasSize(200);
    }

    @Test
    void maskIbanKeepsLastFour() {
        assertThat(ClassificationInput.maskIbanLast4("PL61 1090 1014 0000 0712 1981 2874"))
                .isEqualTo("2874");
        assertThat(ClassificationInput.maskIbanLast4("  ")).isNull();
        assertThat(ClassificationInput.maskIbanLast4(null)).isNull();
    }
}
