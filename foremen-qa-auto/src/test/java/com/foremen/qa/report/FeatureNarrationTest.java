package com.foremen.qa.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FeatureNarration} (Requirement 10): parsing the client-report narration
 * straight out of the {@code .feature} source — the feature description, per-scenario intent, and
 * per-step inline {@code # что:}/{@code # ожидание:} annotations. Pure string parsing, no stack.
 */
class FeatureNarrationTest {

    @Test
    void parsesFeatureDescriptionBlockScenarioIntentAndStepAnnotations() {
        String src = """
                @smoke @FOR-02
                Feature: Users admin — list and create
                  Список пользователей и создание пользователя.

                  # this is an ordinary comment, not the description

                  Scenario: The users list renders
                    Что проверяем: список пользователей грузится и отрисовывается.
                    When I open the Users page
                    # что: Открываем страницу «Пользователи».
                    # ожидание: Загрузилась страница управления пользователями.
                    Then the users list renders
                    # что: Проверяем отрисовку списка пользователей.
                    # ожидание: Таблица пользователей отображается с колонками.
                """;

        FeatureNarration.Parsed p = FeatureNarration.parseSource(src);

        // Real description block wins (the "#" comment is ignored as the description).
        assertEquals("Список пользователей и создание пользователя.", p.featureDescription());

        // Scenario intent = the free-text block under the Scenario: line, before its first step.
        assertEquals("Что проверяем: список пользователей грузится и отрисовывается.",
                p.scenarioIntent("The users list renders"));

        FeatureNarration.StepMatcher m = p.stepMatcher();
        String[] open = m.stepAnnotation("When ", "I open the Users page");
        assertEquals("Открываем страницу «Пользователи».", open[0]);
        assertEquals("Загрузилась страница управления пользователями.", open[1]);

        String[] renders = m.stepAnnotation("Then ", "the users list renders");
        assertEquals("Проверяем отрисовку списка пользователей.", renders[0]);
        assertEquals("Таблица пользователей отображается с колонками.", renders[1]);
    }

    @Test
    void fallsBackToLeadingCommentLinesWhenNoDescriptionBlock() {
        String src = """
                @smoke
                Feature: Something
                  # Первая строка интро.
                  # Вторая строка интро.

                  Scenario: X
                    When I do a thing
                """;

        FeatureNarration.Parsed p = FeatureNarration.parseSource(src);
        assertEquals("Первая строка интро. Вторая строка интро.", p.featureDescription());
    }

    @Test
    void backgroundStepsMatchPerScenarioViaFreshMatcher() {
        String src = """
                Feature: Bg
                  Background:
                    Given the application stack is ready
                    # что: Готовность стенда.
                    # ожидание: Стенд готов.

                  Scenario: A
                    When I do A
                    # что: Делаем A.

                  Scenario: B
                    When I do B
                    # что: Делаем B.
                """;

        FeatureNarration.Parsed p = FeatureNarration.parseSource(src);

        // Scenario A replays the Background step then its own step.
        FeatureNarration.StepMatcher a = p.stepMatcher();
        assertEquals("Готовность стенда.",
                a.stepAnnotation("Given ", "the application stack is ready")[0]);
        assertEquals("Делаем A.", a.stepAnnotation("When ", "I do A")[0]);

        // Scenario B gets a fresh matcher — the Background step matches again.
        FeatureNarration.StepMatcher b = p.stepMatcher();
        assertEquals("Готовность стенда.",
                b.stepAnnotation("Given ", "the application stack is ready")[0]);
        assertEquals("Делаем B.", b.stepAnnotation("When ", "I do B")[0]);
    }

    @Test
    void scenarioOutlinePlaceholdersAreSubstitutedIntoAnnotation() {
        String src = """
                Feature: Dict
                  Scenario Outline: The dictionary page at <route> loads
                    When I open the dictionary page "<route>"
                    # что: Открываем страницу справочника <route>.
                    # ожидание: Страница справочника <route> загрузилась.

                    Examples:
                      | route       |
                      | /currencies |
                """;

        FeatureNarration.Parsed p = FeatureNarration.parseSource(src);
        FeatureNarration.StepMatcher m = p.stepMatcher();

        // Runtime pickle text has the placeholder substituted; the annotation must too.
        String[] ann = m.stepAnnotation("When ", "I open the dictionary page \"/currencies\"");
        assertEquals("Открываем страницу справочника /currencies.", ann[0]);
        assertEquals("Страница справочника /currencies загрузилась.", ann[1]);

        // The outline intent resolves via placeholder-tolerant name matching.
        assertEquals(null, p.scenarioIntent("The dictionary page at /currencies loads"));
    }

    @Test
    void alsoAcceptsChtoDelaemAndOzhidaemyjRezultatSynonyms() {
        String src = """
                Feature: Syn
                  Scenario: S
                    When I act
                    # что делаем: Выполняем действие.
                    # ожидаемый результат: Действие успешно.
                """;

        FeatureNarration.Parsed p = FeatureNarration.parseSource(src);
        String[] ann = p.stepMatcher().stepAnnotation("When ", "I act");
        assertEquals("Выполняем действие.", ann[0]);
        assertEquals("Действие успешно.", ann[1]);
    }

    @Test
    void missingAnnotationsYieldNullAndUnknownStepReturnsNull() {
        String src = """
                Feature: Plain
                  Scenario: S
                    When I have no annotation
                """;

        FeatureNarration.Parsed p = FeatureNarration.parseSource(src);
        FeatureNarration.StepMatcher m = p.stepMatcher();
        // A step with no annotation returns null (caller falls back to StepNarrator).
        assertNull(m.stepAnnotation("When ", "I have no annotation"));
        // An unmatched step returns null too.
        assertNull(m.stepAnnotation("When ", "some other step"));
    }

    @Test
    void blankOrNullSourceIsEmptyAndNeverThrows() {
        assertNull(FeatureNarration.parseSource("").featureDescription());
        assertNull(FeatureNarration.parseSource(null).featureDescription());
        assertEquals(0, FeatureNarration.parseSource("   ").scenarioCount());
    }
}
