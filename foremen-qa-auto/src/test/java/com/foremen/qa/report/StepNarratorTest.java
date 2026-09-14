package com.foremen.qa.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StepNarrator} (Requirement 10). Pure, no browser or live stack: they only
 * exercise the normalization + override-table lookup + argument-substitution logic.
 */
class StepNarratorTest {

    @Test
    void mappedStepReturnsRussianDescriptionAndExpected() {
        String desc = StepNarrator.describe("Then ", "the users list renders");
        String exp = StepNarrator.expected("Then ", "the users list renders");

        assertEquals("Проверяем отрисовку списка пользователей.", desc);
        assertEquals(
                "Таблица пользователей отображается с колонками (Имя, E-mail, Роль, Статус).",
                exp);
    }

    @Test
    void parameterizedStepSubstitutesQuotedArgument() {
        String text = "I open the dictionary page \"/currencies\"";

        String desc = StepNarrator.describe("When ", text);
        String exp = StepNarrator.expected("When ", text);

        assertTrue(desc.contains("/currencies"), "description should contain the route: " + desc);
        assertTrue(exp.contains("/currencies"), "expected should contain the route: " + exp);
        // The placeholder token must have been fully replaced.
        assertFalse(desc.contains("{arg}"), "placeholder must be substituted: " + desc);
        assertFalse(exp.contains("{arg}"), "placeholder must be substituted: " + exp);
    }

    @Test
    void twoArgumentStepSubstitutesBothPlaceholders() {
        String text = "a role granting only \"USERS\" \"READ\" exists";

        String desc = StepNarrator.describe("Given ", text);
        String exp = StepNarrator.expected("Given ", text);

        assertTrue(desc.contains("USERS") && desc.contains("READ"), desc);
        assertTrue(exp.contains("USERS") && exp.contains("READ"), exp);
    }

    @Test
    void unmappedStepFallsBackWithoutThrowing() {
        String desc = StepNarrator.describe("When ", "I do something completely unmapped");
        String exp = StepNarrator.expected("When ", "I do something completely unmapped");

        assertNotNull(desc);
        assertNotNull(exp);
        // Description humanizes the raw text (drops leading "I ", capitalizes).
        assertEquals("Do something completely unmapped", desc);
        // Expected falls back to the keyword-based generic for a "when" step.
        assertEquals("Действие выполнено успешно.", exp);
    }

    @Test
    void fallbackExpectedIsKeywordAware() {
        assertEquals("Условие выполнено.",
                StepNarrator.expected("Then ", "some unmapped assertion"));
        assertEquals("Предусловие установлено.",
                StepNarrator.expected("Given ", "some unmapped precondition"));
    }

    @Test
    void normalizationCollapsesQuotesAndWhitespace() {
        assertEquals("i open the dictionary page {arg}",
                StepNarrator.normalize("I open   the dictionary page \"/vat-rates\""));
    }
}
