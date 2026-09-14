package com.foremen.qa.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StepNarrator} (Requirement 10). {@code StepNarrator} is now the pure
 * <em>fallback</em> engine used only when a step carries no inline {@code # что:}/{@code # ожидание:}
 * annotation in the feature file: it humanizes the raw Gherkin text and produces a generic,
 * keyword-based expected result. There is no longer any hardcoded per-step override table.
 */
class StepNarratorTest {

    @Test
    void describeHumanizesRawText() {
        // Drops a leading "I " and capitalizes the first letter.
        assertEquals("Do something completely unmapped",
                StepNarrator.describe("When ", "I do something completely unmapped"));
        assertEquals("The users list renders",
                StepNarrator.describe("Then ", "the users list renders"));
    }

    @Test
    void expectedIsKeywordAware() {
        assertEquals("Действие выполнено успешно.",
                StepNarrator.expected("When ", "I log in with the seeded admin credentials"));
        assertEquals("Условие выполнено.",
                StepNarrator.expected("Then ", "the users list renders"));
        assertEquals("Условие выполнено.",
                StepNarrator.expected("And ", "access and refresh tokens are stored"));
        assertEquals("Предусловие установлено.",
                StepNarrator.expected("Given ", "the application stack is ready"));
    }

    @Test
    void unmappedStepNeverThrowsAndReturnsNonNull() {
        assertNotNull(StepNarrator.describe("When ", "anything at all"));
        assertNotNull(StepNarrator.expected("", "anything at all"));
        // Null/blank text is tolerated.
        assertNotNull(StepNarrator.describe(null, null));
        assertEquals("", StepNarrator.describe("When ", "   "));
    }

    @Test
    void normalizeCollapsesQuotesAndWhitespace() {
        assertEquals("i open the dictionary page {arg}",
                StepNarrator.normalize("I open   the dictionary page \"/vat-rates\""));
        assertEquals("a role granting only {arg} {arg2} exists",
                StepNarrator.normalize("a role granting only \"USERS\" \"READ\" exists"));
    }
}
