package com.foremen.qa.pages;

import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the client passwordless OTP sign-in screen at {@code /auth/otp}
 * (frontend {@code OtpLoginPage.tsx} + {@code OtpCodeInput.tsx}, FOR-QA-AUTO-05).
 *
 * <p>The flow is a two-step state machine:
 * <ul>
 *   <li><b>Email step</b> — a single {@code <input type="email">} (no stable id) and a
 *       {@code button[type=submit]} ("Request code"). On HTTP 200 it always advances to the code
 *       step (anti-enumeration), so the step object just waits for the code boxes to appear.</li>
 *   <li><b>Code step</b> — the segmented {@code OtpCodeInput} renders six single-char numeric boxes
 *       ({@code input[inputmode=numeric][maxlength='1']}, {@code autocomplete=one-time-code}). Each
 *       box's {@code aria-label} is i18n-dependent (RU in QA), so the boxes are located generically
 *       by their input attributes rather than by label text. Filling the sixth box auto-submits via
 *       {@code onComplete}; an explicit verify {@code button[type=submit]} is clicked as a fallback
 *       when the page is still on {@code /auth/otp}.</li>
 * </ul>
 *
 * <p>The page is PUBLIC (no auth needed), but the UI locale must be pinned to RU so the flow matches
 * the other scenarios' RU surface. {@link #open()} first navigates to the app origin, writes
 * {@code localStorage['foremen-locale']='ru'}, then navigates to {@code /auth/otp} (i18n reads the
 * locale at init on the next full navigation). This page object holds NO assertions.
 */
public final class OtpLoginPage {

    /** Public route owned by this page object. */
    public static final String ROUTE = "/auth/otp";

    /** The six single-char numeric OTP boxes, located by their stable input attributes. */
    private static final String CODE_BOX_SELECTOR = "input[inputmode='numeric'][maxlength='1']";

    private final Page page;

    public OtpLoginPage(Page page) {
        this.page = page;
    }

    /** Absolute URL of the OTP route against the configured frontend base URL. */
    public static String url() {
        return TestConfig.frontendUrl() + ROUTE;
    }

    // ---- Navigation ----

    /**
     * Pin the UI locale to RU on the app origin, then navigate to {@code /auth/otp} and wait for the
     * email input to be visible. The origin visit is required so the {@code foremen-locale} write
     * lands on the app's localStorage before i18n initializes on the OTP page load.
     */
    public OtpLoginPage open() {
        page.navigate(TestConfig.frontendUrl() + "/");
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.evaluate("() => { try { localStorage.setItem('foremen-locale', 'ru'); } catch (e) {} }");
        page.navigate(url());
        emailInput().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /** {@code true} when the browser is currently on the {@code /auth/otp} route. */
    public boolean isOnOtp() {
        return page.url().contains(ROUTE);
    }

    // ---- Locators ----

    private Locator emailInput() {
        return page.locator("input[type=email]");
    }

    private Locator submitButton() {
        return page.locator("button[type=submit]");
    }

    private Locator codeBoxes() {
        return page.locator(CODE_BOX_SELECTOR);
    }

    // ---- Actions ----

    /**
     * Fill the email, submit the request, and wait for the code step (the six boxes) to render. On a
     * 200 the app always advances to the code step regardless of eligibility.
     */
    public OtpLoginPage requestCodeFor(String email) {
        emailInput().fill(email);
        submitButton().first().click();
        codeBoxes().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /**
     * Type each digit of the six-character code into its box in order. Filling the last box triggers
     * the component's auto-submit ({@code onComplete}); as a fallback, if the page is still on
     * {@code /auth/otp} shortly after, click the explicit verify submit button.
     *
     * @param sixDigits the six-digit code
     */
    public void enterCode(String sixDigits) {
        Locator boxes = codeBoxes();
        int count = Math.min(sixDigits.length(), boxes.count());
        for (int i = 0; i < count; i++) {
            boxes.nth(i).fill(String.valueOf(sixDigits.charAt(i)));
        }
        // Auto-submit (onComplete) usually fires; give it a brief window, then fall back to the
        // explicit verify control if we are still on the OTP route.
        if (stillOnOtpAfterShortWait()) {
            Locator verify = submitButton();
            if (verify.count() > 0) {
                verify.first().click();
            }
        }
    }

    /**
     * Wait a bounded time for navigation away from {@code /auth/otp}; returns {@code true} when the
     * page is STILL on the OTP route after that window (i.e. the explicit verify click is needed).
     */
    private boolean stillOnOtpAfterShortWait() {
        try {
            page.waitForURL(u -> !u.contains(ROUTE),
                    new Page.WaitForURLOptions().setTimeout(3000));
            return false;
        } catch (RuntimeException stillHere) {
            return isOnOtp();
        }
    }
}
