package com.foremen.qa.report;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the client-facing report narration straight out of the {@code .feature} source (Requirement
 * 10), so that authoring a Gherkin scenario yields a correct RU report with no separate hardcoded
 * table. Three kinds of narration are lifted from the feature file:
 *
 * <ol>
 *   <li><b>Feature description</b> — the standard Gherkin description block: the free-text lines
 *       between the {@code Feature:} line and the first {@code Background:}/{@code Scenario:}/
 *       {@code Rule:}/{@code @tag}. If there is no description block, this falls back to
 *       concatenating the contiguous {@code #}-comment lines immediately under the {@code Feature:}
 *       line (leading {@code "# "} stripped). A real description block wins over comments.</li>
 *   <li><b>Scenario intent</b> — the free-text description block under a {@code Scenario:}/
 *       {@code Scenario Outline:} line, before its first step. Absent → {@code null} (the plugin then
 *       keeps the scenario name as the intent).</li>
 *   <li><b>Step narration</b> — authored inline as a trailing annotation comment on the line(s)
 *       immediately <em>after</em> a step, using this convention:
 *       <pre>
 *       Then the users list renders
 *       # что: Проверяем отрисовку списка пользователей.
 *       # ожидание: Таблица пользователей отображается с колонками (Имя, E-mail, Роль, Статус).
 *       </pre>
 *       A line matching {@code # что: <text>} (also {@code # что делаем:}) sets the step description;
 *       {@code # ожидание:} (also {@code # ожидаемый результат:}) sets the expected. The annotation
 *       is associated with the step it follows, by line order.</li>
 * </ol>
 *
 * <h2>Robustness</h2>
 * Parsing is line-based (no full Gherkin library) and never throws: any read/parse miss silently
 * yields an empty narration, and callers fall back to {@link StepNarrator}'s humanized narration.
 * Results are cached per source URI.
 *
 * <h2>Step matching</h2>
 * Cucumber replays a scenario's steps (Background steps first, then the scenario's own) on the event
 * stream. Because Background steps repeat across scenarios, steps are matched to their parsed
 * annotation by {@code (keyword + text)} on a first-unused-occurrence basis via
 * {@link Parsed#stepAnnotation(String, String)}, which walks the parsed step list in file order and
 * consumes each match once.
 */
public final class FeatureNarration {

    /** Per-URI cache of parsed feature sources. */
    private static final Map<String, Parsed> CACHE = new ConcurrentHashMap<>();

    private FeatureNarration() {
    }

    /**
     * Parse (or return the cached) narration for the feature at {@code uri}. {@code uri} is the
     * Cucumber {@code tc.getUri()} value (typically a {@code classpath:features/...} URI). Never
     * throws; on any failure a non-null empty {@link Parsed} is returned.
     */
    public static Parsed forUri(String uri) {
        if (uri == null) {
            return Parsed.EMPTY;
        }
        return CACHE.computeIfAbsent(uri, FeatureNarration::parseSafe);
    }

    /** Clears the parse cache (used by tests). */
    static void clearCache() {
        CACHE.clear();
    }

    /** Parse the given raw feature source text directly (used by tests and by {@link #parseSafe}). */
    static Parsed parseSource(String source) {
        if (source == null || source.isBlank()) {
            return Parsed.EMPTY;
        }
        try {
            return parse(source.split("\r\n|\r|\n", -1));
        } catch (RuntimeException e) {
            return Parsed.EMPTY;
        }
    }

    private static Parsed parseSafe(String uri) {
        try {
            String source = readSource(uri);
            return parseSource(source);
        } catch (RuntimeException e) {
            return Parsed.EMPTY;
        }
    }

    // ---- source resolution -------------------------------------------------

    /**
     * Resolve a Cucumber feature URI to its source text via the classloader. Cucumber hands us a
     * {@code classpath:features/...} URI whose scheme-specific path is the classpath resource name;
     * the {@code .feature} files live on the test classpath under {@code features/...}. Returns
     * {@code null} when the resource cannot be located/read.
     */
    static String readSource(String uri) {
        String resource = classpathResourceName(uri);
        if (resource == null) {
            return null;
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = FeatureNarration.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                int c;
                while ((c = r.read()) != -1) {
                    sb.append((char) c);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Turn a feature URI into a classloader-resource name. Handles {@code classpath:features/x}
     * (with or without a leading slash) and best-effort trims any {@code file:.../features/...}
     * path down to the {@code features/...} suffix so a full-file URI still resolves off the
     * classpath.
     */
    static String classpathResourceName(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        String s = uri.trim();
        String scheme = "classpath:";
        if (s.startsWith(scheme)) {
            s = s.substring(scheme.length());
        } else {
            // Best-effort: pull out the "features/..." suffix from any other URI form.
            try {
                s = URI.create(s).getSchemeSpecificPart();
            } catch (RuntimeException ignored) {
                // keep s as-is
            }
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        int idx = s.indexOf("features/");
        if (idx > 0) {
            s = s.substring(idx);
        }
        return s.isBlank() ? null : s;
    }

    // ---- line-based parser -------------------------------------------------

    private static Parsed parse(String[] lines) {
        List<String> featureDescLines = new ArrayList<>();
        List<String> featureCommentLines = new ArrayList<>();
        List<StepAnn> steps = new ArrayList<>();
        // Scenario intent captured per scenario-start line index (in file order).
        List<ScenarioIntent> scenarioIntents = new ArrayList<>();
        List<String> scenarioNames = new ArrayList<>();

        // Parsing phases within the file.
        boolean afterFeature = false;      // seen the Feature: line
        boolean featureBlockDone = false;  // description/comment gathering under Feature: has ended
        boolean inScenario = false;        // currently inside a scenario, before its first step
        int currentScenarioIndex = -1;

        StepAnn lastStep = null;           // the step whose trailing comments we may still gather

        for (String raw : lines) {
            String line = raw == null ? "" : raw;
            String trimmed = line.strip();

            if (!afterFeature) {
                if (isKeywordLine(trimmed, "Feature:")) {
                    afterFeature = true;
                }
                continue;
            }

            // ----- Feature description / comment block (until first Background/Scenario/Rule/@tag) -----
            if (!featureBlockDone) {
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (isKeywordLine(trimmed, "Background:")
                        || isScenarioLine(trimmed)
                        || isKeywordLine(trimmed, "Rule:")
                        || trimmed.startsWith("@")) {
                    featureBlockDone = true;
                    // fall through to the scenario/step handling below
                } else if (trimmed.startsWith("#")) {
                    featureCommentLines.add(stripComment(trimmed));
                    continue;
                } else {
                    featureDescLines.add(trimmed);
                    continue;
                }
            }

            // ----- Scenario / step body -----
            if (isScenarioLine(trimmed)) {
                inScenario = true;
                lastStep = null;
                ScenarioIntent si = new ScenarioIntent();
                scenarioIntents.add(si);
                scenarioNames.add(scenarioNameOf(trimmed));
                currentScenarioIndex = scenarioIntents.size() - 1;
                continue;
            }

            if (isKeywordLine(trimmed, "Background:")
                    || isKeywordLine(trimmed, "Rule:")
                    || isKeywordLine(trimmed, "Examples:")
                    || isKeywordLine(trimmed, "Scenarios:")) {
                inScenario = false;
                lastStep = null;
                continue;
            }

            if (trimmed.startsWith("@")) {
                // Tags for the next scenario: end any intent gathering.
                inScenario = false;
                lastStep = null;
                continue;
            }

            String stepKeyword = stepKeywordOf(trimmed);
            if (stepKeyword != null) {
                inScenario = false; // a step ends the scenario intent block
                String text = trimmed.substring(stepKeyword.length()).strip();
                StepAnn step = new StepAnn(stepKeyword, text);
                steps.add(step);
                lastStep = step;
                continue;
            }

            if (trimmed.startsWith("#")) {
                String body = stripComment(trimmed);
                String what = matchPrefix(body, "что делаем:", "что:");
                String exp = matchPrefix(body, "ожидаемый результат:", "ожидание:");
                if (lastStep != null && (what != null || exp != null)) {
                    if (what != null && lastStep.description == null) {
                        lastStep.description = what;
                    }
                    if (exp != null && lastStep.expected == null) {
                        lastStep.expected = exp;
                    }
                }
                // Any other comment line is ignored (does not break the trailing-annotation run;
                // but a non-annotation comment does terminate a step's annotation gathering only if
                // it is not a recognized prefix — we simply skip it).
                continue;
            }

            if (trimmed.isEmpty()) {
                // A blank line ends a step's trailing-annotation run and any scenario intent block.
                lastStep = null;
                continue;
            }

            // A data-table row, doc-string, or free text.
            if (inScenario && currentScenarioIndex >= 0 && !trimmed.startsWith("|")) {
                // Free-text line under a Scenario: before its first step → scenario intent.
                scenarioIntents.get(currentScenarioIndex).lines.add(trimmed);
            }
            // Otherwise (table rows, etc.) it terminates a step annotation run.
            lastStep = null;
        }

        String featureDescription = joinNonEmpty(featureDescLines);
        if (featureDescription == null) {
            featureDescription = joinNonEmpty(featureCommentLines);
        }

        List<String> intents = new ArrayList<>();
        for (ScenarioIntent si : scenarioIntents) {
            intents.add(joinNonEmpty(si.lines));
        }
        return new Parsed(featureDescription, scenarioNames, intents, steps);
    }

    // ---- line classification helpers ---------------------------------------

    private static final String[] STEP_KEYWORDS = {
            "Given ", "When ", "Then ", "And ", "But ", "* "
    };

    /** Returns the leading step keyword (with trailing space) if the line is a step, else null. */
    static String stepKeywordOf(String trimmed) {
        for (String kw : STEP_KEYWORDS) {
            if (trimmed.startsWith(kw)) {
                return kw;
            }
        }
        return null;
    }

    private static boolean isKeywordLine(String trimmed, String keyword) {
        return trimmed.equals(keyword) || trimmed.startsWith(keyword);
    }

    private static boolean isScenarioLine(String trimmed) {
        return isKeywordLine(trimmed, "Scenario:") || isKeywordLine(trimmed, "Scenario Outline:");
    }

    /** Extract the scenario name after {@code Scenario:} / {@code Scenario Outline:}. */
    static String scenarioNameOf(String trimmed) {
        String s = trimmed;
        String outline = "Scenario Outline:";
        String scen = "Scenario:";
        if (s.startsWith(outline)) {
            return s.substring(outline.length()).strip();
        }
        if (s.startsWith(scen)) {
            return s.substring(scen.length()).strip();
        }
        return s.strip();
    }

    /** Strip a leading {@code #} (and a following space) from a comment line. */
    static String stripComment(String trimmed) {
        String s = trimmed;
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        return s.strip();
    }

    /**
     * If {@code body} starts with any of the given prefixes (case-insensitive), return the text
     * after the matched prefix (stripped); otherwise null. Longer/more-specific prefixes should be
     * listed first.
     */
    static String matchPrefix(String body, String... prefixes) {
        if (body == null) {
            return null;
        }
        String lower = body.toLowerCase();
        for (String p : prefixes) {
            if (lower.startsWith(p.toLowerCase())) {
                return body.substring(p.length()).strip();
            }
        }
        return null;
    }

    private static String joinNonEmpty(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (l == null || l.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(l.strip());
        }
        String out = sb.toString().strip();
        return out.isEmpty() ? null : out;
    }

    // ---- parsed model ------------------------------------------------------

    /** A single parsed step with its optional inline annotation (immutable once parsed). */
    static final class StepAnn {
        final String keyword;      // normalized to "Given "/"When "/... ("* " → "* ")
        final String text;
        String description;        // # что: ...   (or null)
        String expected;           // # ожидание: ... (or null)

        StepAnn(String keyword, String text) {
            this.keyword = keyword;
            this.text = text;
        }
    }

    private static final class ScenarioIntent {
        final List<String> lines = new ArrayList<>();
    }

    /** Immutable-ish parse result for one feature file. */
    public static final class Parsed {
        static final Parsed EMPTY = new Parsed(null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        private final String featureDescription;
        private final List<String> scenarioNames;   // by scenario order in the file
        private final List<String> scenarioIntents; // parallel to scenarioNames
        private final List<StepAnn> steps;           // all steps in file order

        Parsed(String featureDescription, List<String> scenarioNames,
               List<String> scenarioIntents, List<StepAnn> steps) {
            this.featureDescription = featureDescription;
            this.scenarioNames = scenarioNames;
            this.scenarioIntents = scenarioIntents;
            this.steps = steps;
        }

        /** The feature description (Gherkin description block or leading comments), or null. */
        public String featureDescription() {
            return featureDescription;
        }

        /** The parsed intent for the scenario at file order {@code index}, or null. */
        public String scenarioIntent(int index) {
            if (index < 0 || index >= scenarioIntents.size()) {
                return null;
            }
            return scenarioIntents.get(index);
        }

        /**
         * The parsed intent for the runtime scenario named {@code runtimeName}, or null when absent.
         * Matches by exact name first; if no exact match (e.g. a {@code Scenario Outline} whose
         * runtime pickle name has {@code <route>} placeholders substituted), it falls back to the
         * single scenario whose name is a {@code <...>}-placeholder template of the runtime name.
         */
        public String scenarioIntent(String runtimeName) {
            if (runtimeName == null) {
                return null;
            }
            String rn = norm(runtimeName);
            for (int i = 0; i < scenarioNames.size(); i++) {
                if (norm(scenarioNames.get(i)).equals(rn)) {
                    return scenarioIntents.get(i);
                }
            }
            for (int i = 0; i < scenarioNames.size(); i++) {
                if (outlineNameMatches(scenarioNames.get(i), runtimeName)) {
                    return scenarioIntents.get(i);
                }
            }
            return null;
        }

        /**
         * True when {@code template} is an outline name containing {@code <...>} placeholders that,
         * with the placeholders replaced by any text, could produce {@code runtimeName}. Compared as
         * a simple literal-segments-in-order containment so it stays robust and never throws.
         */
        private static boolean outlineNameMatches(String template, String runtimeName) {
            if (template == null || runtimeName == null || !template.contains("<")) {
                return false;
            }
            String[] parts = template.split("<[^>]*>", -1);
            String hay = runtimeName;
            int from = 0;
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                int at = hay.indexOf(part, from);
                if (at < 0) {
                    return false;
                }
                from = at + part.length();
            }
            return true;
        }

        /** Number of scenarios (including outlines) parsed from the file. */
        public int scenarioCount() {
            return scenarioIntents.size();
        }

        /**
         * A fresh, stateful matcher over this feature's parsed steps. Because Cucumber replays the
         * Background steps for every scenario, callers create one matcher <em>per runtime
         * scenario</em> so first-unused-occurrence matching resets each time (the cached {@link
         * Parsed} itself stays immutable and thread-safe to share).
         */
        public StepMatcher stepMatcher() {
            return new StepMatcher(steps);
        }

        static String norm(String t) {
            return t == null ? "" : t.replaceAll("\\s+", " ").strip();
        }
    }

    /**
     * Per-scenario step-annotation matcher. Matches a runtime step on {@code (keyword + text)},
     * consuming the first not-yet-used parsed occurrence. Keyword matching is lenient: the runtime
     * keyword (e.g. {@code "Then "}) is compared case-insensitively; a second pass matches on text
     * alone so {@code And}/{@code But}/{@code *} conjunction steps (whose Cucumber effective keyword
     * may differ from the file) still resolve. Returns {@code null} when no unconsumed step matches
     * or the matched step has no annotation — callers then fall back to {@link StepNarrator}.
     */
    public static final class StepMatcher {
        private final List<StepAnn> steps;
        private final boolean[] consumed;

        StepMatcher(List<StepAnn> steps) {
            this.steps = steps;
            this.consumed = new boolean[steps.size()];
        }

        /** {@code [description, expected]} for the next matching step, or {@code null}. */
        public String[] stepAnnotation(String keyword, String text) {
            if (text == null) {
                return null;
            }
            String normText = Parsed.norm(text);
            String kw = keyword == null ? "" : keyword.strip().toLowerCase();
            // First pass: exact keyword + text match.
            for (int i = 0; i < steps.size(); i++) {
                if (consumed[i]) {
                    continue;
                }
                StepAnn s = steps.get(i);
                if (Parsed.norm(s.text).equals(normText)
                        && s.keyword.strip().toLowerCase().equals(kw)) {
                    consumed[i] = true;
                    return annOf(s);
                }
            }
            // Second pass: text match ignoring keyword (And/But/* effective-keyword drift).
            for (int i = 0; i < steps.size(); i++) {
                if (consumed[i]) {
                    continue;
                }
                StepAnn s = steps.get(i);
                if (Parsed.norm(s.text).equals(normText)) {
                    consumed[i] = true;
                    return annOf(s);
                }
            }
            // Third pass: Scenario Outline placeholder templates. The parsed step text carries the
            // literal outline text with "<param>" placeholders; the runtime pickle text has them
            // substituted. Match by literal-segments-in-order containment and, on a hit, substitute
            // the recovered <param> values back into the annotation so the report reads concretely.
            for (int i = 0; i < steps.size(); i++) {
                if (consumed[i]) {
                    continue;
                }
                StepAnn s = steps.get(i);
                Map<String, String> params = outlineParams(s.text, text);
                if (params != null) {
                    consumed[i] = true;
                    return annOf(s, params);
                }
            }
            return null;
        }

        /**
         * If {@code template} contains {@code <name>} placeholders whose literal segments occur, in
         * order, within {@code runtime}, return a map of placeholder-name → recovered value;
         * otherwise {@code null}. Comparison is on normalized (whitespace-collapsed) text; the
         * recovered value is taken from the normalized runtime text. Robust and non-throwing.
         */
        private static Map<String, String> outlineParams(String template, String runtime) {
            if (template == null || runtime == null || !template.contains("<")) {
                return null;
            }
            String tmpl = Parsed.norm(template);
            String run = Parsed.norm(runtime);
            java.util.regex.Matcher pm =
                    java.util.regex.Pattern.compile("<([^>]*)>").matcher(tmpl);
            List<String> names = new ArrayList<>();
            while (pm.find()) {
                names.add(pm.group(1));
            }
            String[] literals = tmpl.split("<[^>]*>", -1);
            Map<String, String> params = new LinkedHashMap<>();
            int from = 0;
            for (int k = 0; k < literals.length; k++) {
                String lit = literals[k];
                int at;
                if (lit.isEmpty()) {
                    at = from;
                } else {
                    at = run.indexOf(lit, from);
                    if (at < 0) {
                        return null;
                    }
                }
                // Capture the value that filled the placeholder BEFORE this literal (k>=1).
                if (k >= 1) {
                    String value = run.substring(from, Math.max(from, at));
                    params.put(names.get(k - 1), value);
                }
                from = at + lit.length();
            }
            // Trailing placeholder with no literal after it.
            if (names.size() >= literals.length) {
                params.put(names.get(literals.length - 1), run.substring(from));
            }
            return params;
        }

        private static String[] annOf(StepAnn s) {
            return annOf(s, null);
        }

        /**
         * Return {@code [description, expected]} for a matched step, substituting any {@code <name>}
         * placeholders in the annotation text with the recovered outline values in {@code params}
         * (when non-null) so the report shows the concrete run value.
         */
        private static String[] annOf(StepAnn s, Map<String, String> params) {
            if (s.description == null && s.expected == null) {
                return null;
            }
            return new String[] {
                    substituteParams(s.description, params),
                    substituteParams(s.expected, params)
            };
        }

        private static String substituteParams(String text, Map<String, String> params) {
            if (text == null || params == null || params.isEmpty()) {
                return text;
            }
            String out = text;
            for (Map.Entry<String, String> e : params.entrySet()) {
                out = out.replace("<" + e.getKey() + ">", e.getValue());
            }
            return out;
        }
    }
}
