package com.foremen.qa.report;

import java.util.ArrayList;
import java.util.List;

/**
 * The structured data model behind the client-facing demo report (Requirement 10).
 *
 * <p>The {@link DemoReportPlugin} populates a single {@link Run} from the Cucumber event stream
 * (one {@link Feature} per {@code .feature} file, one {@link Scenario} per pickle, one {@link Step}
 * per Gherkin step plus any explicit {@link com.foremen.qa.report.Demo} frames). {@link ReportWriter}
 * then renders {@code report.json}, the demo HTML, and the steering-standard MD tables from it.
 *
 * <p>All types are plain mutable holders (not records) so the plugin can append incrementally as
 * events arrive; they serialize cleanly to JSON via Gson. No secret values are ever stored here —
 * step text and captions are redacted upstream in {@link Secrets} before being handed to the model.
 */
public final class ReportModel {

    private ReportModel() {
    }

    /** Status of a step or scenario, mirrored from Cucumber's own result status. */
    public enum Status {
        PASSED,
        FAILED,
        SKIPPED,
        PENDING,
        UNDEFINED,
        AMBIGUOUS,
        UNKNOWN;

        /** Map a Cucumber status name (e.g. {@code "PASSED"}) to this enum, defensively. */
        public static Status fromCucumber(String name) {
            if (name == null) {
                return UNKNOWN;
            }
            try {
                return Status.valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }

        /** {@code true} for statuses that are neither passed nor a clean skip (i.e. a real problem). */
        public boolean isProblem() {
            return this == FAILED || this == PENDING || this == UNDEFINED || this == AMBIGUOUS;
        }
    }

    /** Run-level summary: environment, run-id, timestamp, and the features executed. */
    public static final class Run {
        public String runId;
        public String startedAt;      // ISO-8601 UTC
        public String finishedAt;     // ISO-8601 UTC
        public String frontendUrl;
        public String apiUrl;
        public String browser;
        public boolean headed;
        public String shotsMode;      // per-step | on-failure
        public boolean fullPageShots;
        public final List<Feature> features = new ArrayList<>();

        // Totals (computed at finalize time).
        public int totalFeatures;
        public int totalScenarios;
        public int passedScenarios;
        public int failedScenarios;
        public int skippedScenarios;
    }

    /** One {@code .feature} file: its name, source-spec tag ({@code @FOR-0N}), and scenarios. */
    public static final class Feature {
        public String name;
        public String uri;           // classpath URI of the .feature file
        public String sourceTag;     // e.g. "@FOR-02" (best-effort from feature/scenario tags)
        public String slug;          // filesystem/anchor-safe id, e.g. "FOR-02-roles_admin"
        public final List<Scenario> scenarios = new ArrayList<>();
    }

    /** One scenario (pickle): its name, tags, ordered steps, overall status, and timing. */
    public static final class Scenario {
        public String name;
        public String slug;          // scenario-scoped folder/anchor id
        public final List<String> tags = new ArrayList<>();
        public final List<Step> steps = new ArrayList<>();
        public Status status = Status.UNKNOWN;
        public String startedAt;
        public String finishedAt;
    }

    /**
     * One reportable step. Most are Gherkin steps captured on {@code TestStepFinished}; some are
     * explicit {@link com.foremen.qa.report.Demo#capture} frames inserted mid-step for a richer demo.
     */
    public static final class Step {
        public String keyword;       // "Given ", "When ", "Then ", or "" for Demo frames
        public String text;          // plain-language step text (redacted) or the Demo caption
        public String description;   // human-readable description (== text unless enriched)
        public String expected;      // plain-language expected outcome (best-effort)
        public String actual;        // plain-language actual outcome / error summary
        public Status status = Status.UNKNOWN;
        public String screenshot;    // relative path under the run folder, or null if none
        public String error;         // failure message (redacted), or null
        public boolean demoFrame;    // true for an explicit Demo.capture frame
        public long durationMs;
    }
}
