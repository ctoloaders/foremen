package com.foremen.qa.support;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;

import java.nio.file.Path;

/**
 * Owns the Playwright object graph and the per-scenario browser lifecycle
 * (Requirements 1.3, 3.4).
 *
 * <p>A single {@link Playwright} instance and a single {@link Browser} are launched lazily and
 * reused for the whole JVM run (browser launch is expensive). Each scenario, however, gets a
 * <em>fresh</em> {@link BrowserContext} + {@link Page}: a context is Playwright's incognito
 * sandbox, so {@code localStorage}/{@code sessionStorage}/cookies never leak between scenarios.
 *
 * <p>Browser engine ({@code chromium} default, {@code firefox}, {@code webkit}) and headed/headless
 * mode are driven by {@link TestConfig}. Tracing is optional ({@link TestConfig#trace()}).
 */
public final class PlaywrightFactory {

    private static Playwright playwright;
    private static Browser browser;

    private PlaywrightFactory() {
    }

    /** Lazily starts Playwright and launches the configured browser (once per JVM). */
    private static synchronized Browser browser() {
        if (browser == null) {
            playwright = Playwright.create();
            BrowserType.LaunchOptions options =
                    new BrowserType.LaunchOptions().setHeadless(!TestConfig.headed());
            browser = browserType().launch(options);
        }
        return browser;
    }

    private static BrowserType browserType() {
        String name = TestConfig.browser();
        return switch (name) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            case "chromium" -> playwright.chromium();
            default -> throw new IllegalStateException(
                    "Unsupported " + TestConfig.KEY_BROWSER + " value: '" + name
                            + "' (expected chromium, firefox, or webkit)");
        };
    }

    /**
     * Opens a fresh incognito context + page for a scenario. Default timeouts are applied from
     * {@link TestConfig}. When tracing is enabled, a trace is started with screenshots/snapshots.
     *
     * @return a {@link Session} bundling the context and page for the scenario
     */
    public static Session newSession() {
        BrowserContext context = browser().newContext();
        context.setDefaultTimeout(TestConfig.timeoutMs());
        context.setDefaultNavigationTimeout(TestConfig.timeoutMs());
        if (TestConfig.trace()) {
            context.tracing().start(new Tracing.StartOptions()
                    .setScreenshots(true)
                    .setSnapshots(true)
                    .setSources(true));
        }
        Page page = context.newPage();
        return new Session(context, page);
    }

    /** Closes Playwright and the shared browser at the end of the run. */
    public static synchronized void shutdown() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
    }

    /**
     * A per-scenario browser session: the incognito {@link BrowserContext} and its {@link Page}.
     * Closing the session stops any running trace (writing it to {@code tracePath} when supplied)
     * and closes the context, releasing the incognito sandbox.
     */
    public static final class Session {
        private final BrowserContext context;
        private final Page page;

        private Session(BrowserContext context, Page page) {
            this.context = context;
            this.page = page;
        }

        public BrowserContext context() {
            return context;
        }

        public Page page() {
            return page;
        }

        /**
         * Closes the scenario context. If tracing is enabled and {@code tracePath} is non-null, the
         * trace is flushed to that path before the context is closed.
         */
        public void close(Path tracePath) {
            try {
                if (TestConfig.trace()) {
                    Tracing.StopOptions stopOptions = new Tracing.StopOptions();
                    if (tracePath != null) {
                        stopOptions.setPath(tracePath);
                    }
                    context.tracing().stop(stopOptions);
                }
            } finally {
                context.close();
            }
        }
    }
}
