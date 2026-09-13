package com.foremen.qa.pages;

import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.util.List;

/**
 * Page object for the employee email + password sign-in screen at {@code /login} (Requirement 5.1).
 *
 * <p>Owns the {@code /login} route and the login-form locators, mirroring the real frontend
 * {@code LoginPage} component:
 * <ul>
 *   <li>email input — {@code #login-email},</li>
 *   <li>password input — {@code #login-password},</li>
 *   <li>submit button — {@code button[type=submit]},</li>
 *   <li>form-level error region — {@code #login-form-error} (surfaces 401/403 messages),</li>
 *   <li>per-field validation messages — {@code #login-email-error} / {@code #login-password-error}.</li>
 * </ul>
 * These selectors are reconciled with {@code TestUserSteps}, which already drives the same three
 * controls. Steps hold assertions; this class only exposes intention-revealing actions/queries.
 */
public final class LoginPage {

    /** Public route owned by this page object (centralized here per the design). */
    public static final String ROUTE = "/login";

    private final Page page;

    public LoginPage(Page page) {
        this.page = page;
    }

    /** Absolute URL of the login route against the configured frontend base URL. */
    public static String url() {
        return TestConfig.frontendUrl() + ROUTE;
    }

    // ---- Navigation ----

    /** Navigate to {@code /login} and wait until the email field is visible. */
    public LoginPage open() {
        page.navigate(url());
        emailInput().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /** {@code true} when the browser is currently on the {@code /login} route. */
    public boolean isOnLogin() {
        return page.url().contains(ROUTE);
    }

    // ---- Locators ----

    private Locator emailInput() {
        return page.locator("#login-email");
    }

    private Locator passwordInput() {
        return page.locator("#login-password");
    }

    private Locator submitButton() {
        return page.locator("button[type=submit]");
    }

    /** The form-level error region (i18n {@code auth.*} messages on 401/403). */
    public Locator errorMessage() {
        return page.locator("#login-form-error");
    }

    // ---- Actions ----

    /**
     * Fill the credentials and submit the form. Does not wait for navigation so callers can assert
     * either the authenticated landing (valid creds) or an error/still-on-login (invalid creds).
     */
    public void login(String email, String password) {
        emailInput().fill(email);
        passwordInput().fill(password);
        submitButton().click();
    }

    /**
     * Submit the login form without filling any field, exercising client-side validation. The form
     * has {@code noValidate}, so submission is blocked by zod/react-hook-form and no
     * {@code POST /api/auth/login} request is sent (Requirement 5.2 — the step asserts the absence
     * of the request via network monitoring).
     */
    public void submitEmpty() {
        // Ensure both fields are empty, then click submit.
        emailInput().fill("");
        passwordInput().fill("");
        submitButton().click();
    }

    /** {@code true} when a per-field client-validation message is currently shown. */
    public boolean hasFieldValidationErrors() {
        return page.locator("#login-email-error").count() > 0
                || page.locator("#login-password-error").count() > 0;
    }

    // ---- Token inspection (Requirement 5.1) ----

    /**
     * Read a token value from {@code localStorage} by key. Returns {@code null} when the key is
     * absent. Used to assert access/refresh token presence after a successful login without echoing
     * the value into a report.
     */
    public String localStorageItem(String key) {
        Object value = page.evaluate("k => window.localStorage.getItem(k)", key);
        return value == null ? null : value.toString();
    }

    /** All {@code localStorage} keys currently set, for diagnosing token storage. */
    @SuppressWarnings("unchecked")
    public List<String> localStorageKeys() {
        Object value = page.evaluate("() => Object.keys(window.localStorage)");
        return (List<String>) value;
    }

    /** The Sign-in-with-Google control, present alongside the password form. */
    public Locator googleSignInButton() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(java.util.regex.Pattern.compile("Google")));
    }
}
