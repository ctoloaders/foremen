package com.foremen.qa.report;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback narration engine for the client-facing demo report (Requirement 10). Turns a raw Gherkin
 * step ({@code keyword} + {@code text}) into human-readable Russian narration <em>only when the
 * feature file carries no inline annotation</em> for the step.
 *
 * <h2>Where the narration comes from now</h2>
 * The primary source of step narration is the {@code .feature} file itself: an author writes a
 * trailing annotation comment on the line(s) immediately after a step —
 * <pre>
 * Then the users list renders
 * # что: Проверяем отрисовку списка пользователей.
 * # ожидание: Таблица пользователей отображается с колонками (Имя, E-mail, Роль, Статус).
 * </pre>
 * {@link FeatureNarration} parses those annotations and {@link DemoReportPlugin} prefers them. This
 * class is invoked only as the fallback when a step has no {@code # что:}/{@code # ожидание:}
 * annotation: it produces a humanized description (drop a leading {@code "I "}, capitalize the first
 * letter) and a generic, keyword-based expected result. There is no longer any hardcoded per-step
 * override table — writing the Gherkin is what drives the report.
 *
 * <p>Pure and stateless — safe to call from the Cucumber plugin without any DI.
 */
public final class StepNarrator {

    /** Matches a double-quoted argument (non-greedy, no embedded quotes). */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    private StepNarrator() {
    }

    /**
     * A plain-language Russian description of what the step does. Humanizes the raw Gherkin text
     * (drops a leading {@code "I "} and capitalizes) — used only when the feature file has no inline
     * {@code # что:} annotation for this step.
     */
    public static String describe(String keyword, String text) {
        return fallbackDescription(text);
    }

    /**
     * A generic, keyword-based Russian expected result for the step — used only when the feature
     * file has no inline {@code # ожидание:} annotation for this step.
     */
    public static String expected(String keyword, String text) {
        return fallbackExpected(keyword);
    }

    // ---- normalization utility (kept for callers/tests) --------------------

    /**
     * Normalize step text for comparison/matching: trim, replace each quoted argument with a
     * positional placeholder ({@code {arg}}, {@code {arg2}}, …), collapse whitespace, and lower-case.
     * Retained as a small utility; the report narration itself no longer relies on a lookup table.
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String replaced = replaceArgsWithPlaceholders(text.trim());
        return replaced.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    /** Replace quoted arguments with {@code {arg}}/{@code {arg2}}/… placeholders (order preserved). */
    private static String replaceArgsWithPlaceholders(String text) {
        Matcher m = QUOTED.matcher(text);
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (m.find()) {
            idx++;
            String token = idx == 1 ? "{arg}" : "{arg" + idx + "}";
            m.appendReplacement(sb, Matcher.quoteReplacement(token));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- fallbacks ---------------------------------------------------------

    /** Humanize raw step text: drop a leading "I ", capitalize the first letter. */
    private static String fallbackDescription(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String t = text.trim();
        if (t.startsWith("I ")) {
            t = t.substring(2).trim();
        }
        if (t.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Keyword-based generic expected result when a step has no inline annotation. */
    private static String fallbackExpected(String keyword) {
        String k = keyword == null ? "" : keyword.trim().toLowerCase();
        return switch (k) {
            case "then", "and", "but" -> "Условие выполнено.";
            case "when" -> "Действие выполнено успешно.";
            case "given" -> "Предусловие установлено.";
            default -> "Шаг выполнен без ошибок.";
        };
    }
}
