package com.foremen.qa.report;

import com.microsoft.playwright.Page;

/**
 * Optional helper for adding extra, captioned demo frames to the client-facing report
 * (Requirement 10.1, 10.3).
 *
 * <p>The {@link DemoReportPlugin} already captures one screenshot per Gherkin step automatically.
 * {@code Demo.capture} lets a page object or step definition add <em>additional</em> captioned
 * frames at sub-moments where a single end-of-step shot is not enough to tell the story — e.g. right
 * after filling a create form and just before submitting it, so the report shows the filled form and
 * the resulting success separately.
 *
 * <p>The screenshot is taken immediately (while the page is open and in the exact state the caller
 * wants shown) and buffered; the plugin flushes buffered demo frames into the current scenario's
 * step list when the enclosing Gherkin step finishes, keeping frames in the order they were taken.
 *
 * <p>This is best-effort and never throws into test code: if capture fails (e.g. the page closed),
 * the frame is simply skipped so a demo annotation can never fail an otherwise-green scenario.
 * Captions are redacted for secrets before being stored.
 */
public final class Demo {

    private Demo() {
    }

    /**
     * Capture a full-page (best-effort) screenshot of {@code page} and buffer it as a captioned demo
     * frame to be inserted into the report at the current step. Never throws.
     *
     * @param page    the live Playwright page to screenshot (typically {@code Hooks.currentPage()})
     * @param caption a short, plain-language caption describing what this frame shows
     */
    public static void capture(Page page, String caption) {
        if (page == null) {
            return;
        }
        DemoReportPlugin.bufferDemoFrame(page, Secrets.redact(caption));
    }
}
