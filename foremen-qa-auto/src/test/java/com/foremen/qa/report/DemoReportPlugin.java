package com.foremen.qa.report;

import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Page;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.HookTestStep;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestRunStarted;
import io.cucumber.plugin.event.TestStep;
import io.cucumber.plugin.event.TestStepFinished;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cucumber event listener that builds the client-facing demo report (Requirement 10.1, 10.2, 10.4,
 * 10.7, 10.9).
 *
 * <p>Registered on the plugin list (see {@code RunSmokeTest}); Cucumber instantiates it once per run
 * via its public no-arg constructor. It subscribes to the event stream and, after each Gherkin step
 * finishes, captures a full-page screenshot of the current Playwright {@link Page} (from
 * {@link Hooks#currentPage()}) and records a {@link ReportModel.Step} with the step's business-language
 * text, status, timing, screenshot path, and (on failure) the error and trace link.
 *
 * <h2>Why the page is still available on {@code TestStepFinished}</h2>
 * A scenario's Gherkin steps all finish <em>before</em> its {@code @After} hooks run, and it is the
 * {@code @After(order = 0)} hook that closes the browser context. Because this listener only captures
 * for {@link PickleStepTestStep} events (not {@link HookTestStep} events), every capture happens while
 * the incognito page is open. Explicit {@link Demo#capture} frames are taken eagerly inside step code
 * (also while the page is open) and buffered here, then flushed into the step list in order.
 *
 * <h2>Output layout</h2>
 * Screenshots and the structured {@code report.json} are written under a run-scoped folder
 * ({@code <reportDir>/<run-id>} when history is kept). At {@code TestRunFinished} the accumulated
 * {@link ReportModel.Run} is finalized (totals) and handed to {@link ReportWriter} to render the demo
 * HTML, the MD run-report, and to persist {@code report.json}.
 *
 * <h2>Screenshot mode</h2>
 * {@code QA_SHOTS=per-step} (default) captures every step; {@code on-failure} captures only problem
 * steps, for faster CI runs. {@code QA_FULLPAGE_SHOTS} toggles full-page vs viewport captures.
 */
public class DemoReportPlugin implements ConcurrentEventListener {

    /** Buffered explicit demo frames awaiting attachment to the current step (Demo.capture). */
    private static final Deque<PendingFrame> DEMO_BUFFER = new ArrayDeque<>();

    /** The run-scoped folder the current run writes into; shared so Demo frames land beside steps. */
    private static volatile Path runFolder;

    /** Sequence for screenshot filenames within a scenario. */
    private final AtomicInteger shotSeq = new AtomicInteger();

    private ReportModel.Run run;
    private ReportModel.Scenario currentScenario;
    private ReportModel.Feature currentFeature;
    private String currentScenarioSlug;
    private int scenarioSeq;

    /** Public no-arg constructor required by Cucumber's plugin loader. */
    public DemoReportPlugin() {
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestRunStarted.class, this::onRunStarted);
        publisher.registerHandlerFor(TestCaseStarted.class, this::onCaseStarted);
        publisher.registerHandlerFor(TestStepFinished.class, this::onStepFinished);
        publisher.registerHandlerFor(TestCaseFinished.class, this::onCaseFinished);
        publisher.registerHandlerFor(TestRunFinished.class, this::onRunFinished);
    }

    // ---- Demo.capture buffering (called from step/page code, page still open) ----

    /**
     * Buffer a captioned demo frame: take the screenshot now (while the page is open) and stash the
     * bytes + caption to be flushed into the report when the enclosing step finishes. Never throws.
     */
    static void bufferDemoFrame(Page page, String caption) {
        try {
            byte[] png = page.screenshot(new Page.ScreenshotOptions()
                    .setFullPage(TestConfig.fullPageShots()));
            synchronized (DEMO_BUFFER) {
                DEMO_BUFFER.add(new PendingFrame(caption, png));
            }
        } catch (RuntimeException e) {
            // Best-effort: a failed demo capture must never fail the scenario.
        }
    }

    // ---- Event handlers ----

    private void onRunStarted(TestRunStarted event) {
        run = new ReportModel.Run();
        run.runId = DataGen.runId();
        run.startedAt = Instant.now().toString();
        run.frontendUrl = safe(TestConfig::frontendUrl);
        run.apiUrl = safe(TestConfig::apiUrl);
        run.browser = safe(TestConfig::browser);
        run.headed = TestConfig.headed();
        run.shotsMode = TestConfig.shots();
        run.fullPageShots = TestConfig.fullPageShots();
        runFolder = resolveRunFolder(run.runId);
        try {
            Files.createDirectories(runFolder.resolve("assets").resolve("screenshots"));
        } catch (Exception ignored) {
            // Directory creation failures surface later when writing; keep the run going.
        }
    }

    private void onCaseStarted(TestCaseStarted event) {
        TestCase tc = event.getTestCase();
        currentFeature = featureFor(tc);
        currentScenario = new ReportModel.Scenario();
        currentScenario.name = tc.getName();
        scenarioSeq++;
        currentScenarioSlug = String.format("%03d-%s", scenarioSeq, slug(tc.getName()));
        currentScenario.slug = currentScenarioSlug;
        currentScenario.startedAt = Instant.now().toString();
        for (String tag : tc.getTags()) {
            currentScenario.tags.add(tag);
        }
        currentFeature.scenarios.add(currentScenario);
        shotSeq.set(0);
        synchronized (DEMO_BUFFER) {
            DEMO_BUFFER.clear();
        }
    }

    private void onStepFinished(TestStepFinished event) {
        if (currentScenario == null) {
            return;
        }
        TestStep testStep = event.getTestStep();
        // Only Gherkin steps get a demo frame; hook steps (browser open/close, teardown) do not.
        if (!(testStep instanceof PickleStepTestStep pickleStep)) {
            return;
        }
        Result result = event.getResult();
        ReportModel.Status status = ReportModel.Status.fromCucumber(result.getStatus().name());

        // 1) Flush any buffered explicit Demo.capture frames taken during this step, in order.
        flushDemoFrames();

        // 2) The step's own frame.
        ReportModel.Step step = new ReportModel.Step();
        String keyword = pickleStep.getStep().getKeyword();
        step.keyword = keyword == null ? "" : keyword;
        step.text = Secrets.redact(pickleStep.getStep().getText());
        step.description = step.text;
        step.expected = expectedFor(step.keyword, status);
        step.actual = actualFor(status, result);
        step.status = status;
        step.durationMs = result.getDuration() == null ? 0L : result.getDuration().toMillis();
        if (result.getError() != null) {
            step.error = Secrets.redact(summarize(result.getError()));
        }
        step.screenshot = maybeCapture(status, step.keyword + step.text);
        currentScenario.steps.add(step);
    }

    private void onCaseFinished(TestCaseFinished event) {
        if (currentScenario == null) {
            return;
        }
        ReportModel.Status status =
                ReportModel.Status.fromCucumber(event.getResult().getStatus().name());
        currentScenario.status = status;
        currentScenario.finishedAt = Instant.now().toString();
        // Attach trace link (Playwright writes traces to build/qa-report/traces when QA_TRACE=true).
        currentScenario = null;
        currentFeature = null;
    }

    private void onRunFinished(TestRunFinished event) {
        if (run == null) {
            return;
        }
        run.finishedAt = Instant.now().toString();
        finalizeTotals(run);
        try {
            new ReportWriter(runFolder).write(run);
        } catch (Exception e) {
            System.err.println("[DemoReportPlugin] Failed to write report: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ---- Screenshot capture ----

    /**
     * Capture a screenshot for the just-finished step, honoring {@code QA_SHOTS}. Returns the
     * relative path (under the run folder) or {@code null} when no shot was taken or capture failed.
     */
    private String maybeCapture(ReportModel.Status status, String label) {
        boolean onFailureOnly = "on-failure".equals(run.shotsMode);
        if (onFailureOnly && !status.isProblem()) {
            return null;
        }
        Page page = safePage();
        if (page == null) {
            return null;
        }
        String file = String.format("%03d-%s.png", shotSeq.incrementAndGet(), slug(label));
        Path rel = Paths.get("assets", "screenshots", currentScenarioSlug, file);
        Path abs = runFolder.resolve(rel);
        try {
            Files.createDirectories(abs.getParent());
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(abs)
                    .setFullPage(run.fullPageShots));
            return rel.toString().replace('\\', '/');
        } catch (RuntimeException | java.io.IOException e) {
            // A capture failure should never fail the scenario; the step is still recorded.
            return null;
        }
    }

    /** Write buffered {@link Demo#capture} frames to disk and append them as demo steps in order. */
    private void flushDemoFrames() {
        List<PendingFrame> frames;
        synchronized (DEMO_BUFFER) {
            if (DEMO_BUFFER.isEmpty()) {
                return;
            }
            frames = new ArrayList<>(DEMO_BUFFER);
            DEMO_BUFFER.clear();
        }
        for (PendingFrame frame : frames) {
            String file = String.format("%03d-demo-%s.png",
                    shotSeq.incrementAndGet(), slug(frame.caption));
            Path rel = Paths.get("assets", "screenshots", currentScenarioSlug, file);
            Path abs = runFolder.resolve(rel);
            String screenshotPath = null;
            try {
                Files.createDirectories(abs.getParent());
                Files.write(abs, frame.png);
                screenshotPath = rel.toString().replace('\\', '/');
            } catch (Exception ignored) {
                // Skip a frame that cannot be written; keep the demo readable.
            }
            ReportModel.Step demoStep = new ReportModel.Step();
            demoStep.keyword = "";
            demoStep.text = frame.caption;
            demoStep.description = frame.caption;
            demoStep.demoFrame = true;
            demoStep.status = ReportModel.Status.PASSED;
            demoStep.screenshot = screenshotPath;
            currentScenario.steps.add(demoStep);
        }
    }

    // ---- Helpers ----

    private ReportModel.Feature featureFor(TestCase tc) {
        String uri = tc.getUri().toString();
        for (ReportModel.Feature f : run.features) {
            if (f.uri.equals(uri)) {
                return f;
            }
        }
        ReportModel.Feature feature = new ReportModel.Feature();
        feature.uri = uri;
        feature.name = featureNameFromUri(uri);
        feature.sourceTag = sourceTag(tc.getTags());
        feature.slug = featureSlug(feature.sourceTag, feature.name, uri);
        run.features.add(feature);
        return feature;
    }

    private static String featureNameFromUri(String uri) {
        int slash = uri.lastIndexOf('/');
        String file = slash >= 0 ? uri.substring(slash + 1) : uri;
        if (file.endsWith(".feature")) {
            file = file.substring(0, file.length() - ".feature".length());
        }
        return file;
    }

    /** Best-effort source spec tag: the first {@code @FOR-0N}-style tag on the scenario/feature. */
    private static String sourceTag(List<String> tags) {
        for (String tag : tags) {
            if (tag.matches("(?i)@FOR-\\d+")) {
                return tag.toUpperCase();
            }
        }
        return "@OTHER";
    }

    private static String featureSlug(String sourceTag, String name, String uri) {
        String tagPart = sourceTag == null ? "" : sourceTag.replace("@", "");
        return slug((tagPart.isEmpty() ? "" : tagPart + "-") + name);
    }

    private static String expectedFor(String keyword, ReportModel.Status status) {
        String k = keyword == null ? "" : keyword.trim().toLowerCase();
        return switch (k) {
            case "then", "and", "but" -> "The described condition holds.";
            case "when" -> "The action is performed successfully.";
            case "given" -> "The precondition is established.";
            default -> "Step completes without error.";
        };
    }

    private static String actualFor(ReportModel.Status status, Result result) {
        return switch (status) {
            case PASSED -> "As expected.";
            case SKIPPED -> "Not executed (skipped).";
            case FAILED -> "Failed — see error.";
            case PENDING -> "Pending — step not implemented.";
            case UNDEFINED -> "Undefined — no matching step.";
            case AMBIGUOUS -> "Ambiguous — multiple matching steps.";
            default -> "Unknown outcome.";
        };
    }

    private static String summarize(Throwable error) {
        String msg = error.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = error.getClass().getSimpleName();
        }
        // Keep the report readable: first ~600 chars of the message.
        return msg.length() > 600 ? msg.substring(0, 600) + " …" : msg;
    }

    private static void finalizeTotals(ReportModel.Run run) {
        run.totalFeatures = run.features.size();
        for (ReportModel.Feature f : run.features) {
            for (ReportModel.Scenario s : f.scenarios) {
                run.totalScenarios++;
                switch (s.status) {
                    case PASSED -> run.passedScenarios++;
                    case SKIPPED -> run.skippedScenarios++;
                    default -> run.failedScenarios++;
                }
            }
        }
    }

    private Path resolveRunFolder(String runId) {
        Path root = Paths.get(TestConfig.reportDir());
        if (TestConfig.reportKeepHistory()) {
            return root.resolve(runId);
        }
        return root.resolve("latest");
    }

    private static Page safePage() {
        try {
            return Hooks.currentPage();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safe(java.util.function.Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String slug(String name) {
        if (name == null || name.isBlank()) {
            return "x";
        }
        String slug = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            return "x";
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }

    /** A screenshot captured eagerly by {@link Demo#capture}, awaiting attachment to a step. */
    private record PendingFrame(String caption, byte[] png) {
    }
}
