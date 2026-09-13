package com.foremen.qa.steps;

import com.foremen.qa.pages.AppShell;
import com.foremen.qa.pages.LoginPage;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-03 authentication smoke steps (Requirements 5.1–5.5).
 *
 * <p>Drives the login/guard/logout flows through the browser via {@link LoginPage}/{@link AppShell}
 * and holds all assertions. Selectors live only in the page objects. The stack-ready background and
 * the "log in as the seeded admin" convenience are provided by {@link CommonSteps}; the reusable
 * role-based test-user steps live in {@link TestUserSteps} and are not duplicated here.
 *
 * <p>Token storage is asserted against the fixed frontend {@code localStorage} keys
 * ({@code foremen-access-token} / {@code foremen-refresh-token}); token <em>values</em> are never
 * echoed into the report (Requirement 10.10).
 */
public class AuthSteps {

    /** Frontend Token_Storage keys (Auth_Store {@code ACCESS_TOKEN_KEY} / {@code REFRESH_TOKEN_KEY}). */
    private static final String ACCESS_TOKEN_KEY = "foremen-access-token";
    private static final String REFRESH_TOKEN_KEY = "foremen-refresh-token";

    /** Path fragment of the login request whose absence/presence we monitor (Requirement 5.2). */
    private static final String LOGIN_REQUEST_PATH = "/api/auth/login";

    private LoginPage loginPage;

    /**
     * Set when a {@code POST /api/auth/login} request is observed after arming the network monitor.
     * A per-step instance is fine because Cucumber creates one step-definition instance per
     * scenario.
     */
    private final AtomicBoolean loginRequestSeen = new AtomicBoolean(false);

    private LoginPage loginPage() {
        if (loginPage == null) {
            loginPage = new LoginPage(Hooks.currentPage());
        }
        return loginPage;
    }

    // ---- Navigation ----

    @Given("I am on the login page")
    public void iAmOnTheLoginPage() {
        loginPage().open();
        assertThat(loginPage().isOnLogin())
                .as("browser should be on the /login route")
                .isTrue();
    }

    // ---- Valid login (Requirement 5.1) ----

    @When("I log in with the seeded admin credentials")
    public void iLogInWithTheSeededAdminCredentials() {
        loginPage().login(TestConfig.adminEmail(), TestConfig.adminPassword());
    }

    @Then("I land on the home page")
    public void iLandOnTheHomePage() {
        Page page = Hooks.currentPage();
        // A successful login navigates away from /login; wait for that transition then confirm the
        // authenticated shell rendered on the home route.
        page.waitForURL(url -> !url.contains(LoginPage.ROUTE));
        AppShell shell = new AppShell(page);
        shell.waitUntilRendered();
        assertThat(shell.isOnRoute(AppShell.HOME_ROUTE))
                .as("after a valid login the app should land on the home route")
                .isTrue();
        assertThat(shell.isRendered())
                .as("the authenticated app shell should render after login")
                .isTrue();
    }

    @Then("access and refresh tokens are stored")
    public void accessAndRefreshTokensAreStored() {
        assertThat(loginPage().localStorageItem(ACCESS_TOKEN_KEY))
                .as("access token should be present in localStorage after login")
                .isNotBlank();
        assertThat(loginPage().localStorageItem(REFRESH_TOKEN_KEY))
                .as("refresh token should be present in localStorage after login")
                .isNotBlank();
    }

    // ---- Empty-field client validation, no request (Requirement 5.2) ----

    @When("I submit the login form without filling any field")
    public void iSubmitTheLoginFormWithoutFillingAnyField() {
        // Arm a request monitor BEFORE submitting so we can assert no POST /api/auth/login fires.
        // The form is client-validated (zod/react-hook-form, noValidate) and must block submission.
        Page page = Hooks.currentPage();
        loginRequestSeen.set(false);
        page.onRequest(this::recordLoginRequest);
        loginPage().submitEmpty();
        // Give the SPA a beat to (not) issue the request; validation is synchronous, so a short
        // network-idle wait is enough to catch an erroneously-sent request without a fixed sleep.
        page.waitForTimeout(500);
    }

    private void recordLoginRequest(Request request) {
        if ("POST".equalsIgnoreCase(request.method()) && request.url().contains(LOGIN_REQUEST_PATH)) {
            loginRequestSeen.set(true);
        }
    }

    @Then("submission is blocked by client validation")
    public void submissionIsBlockedByClientValidation() {
        assertThat(loginPage().hasFieldValidationErrors())
                .as("empty required fields should surface client-side validation messages")
                .isTrue();
    }

    @Then("no login request is sent")
    public void noLoginRequestIsSent() {
        assertThat(loginRequestSeen.get())
                .as("no POST /api/auth/login should be sent when client validation blocks submission")
                .isFalse();
    }

    // ---- Invalid credentials (Requirement 5.3) ----

    @When("I log in with invalid credentials")
    public void iLogInWithInvalidCredentials() {
        // A well-formed but non-existent email + wrong password: passes client validation, so the
        // request reaches the backend, which returns a localized 401.
        loginPage().login("no-such-user@example.com", "wrong-password-123");
    }

    @Then("a localized login error message is shown")
    public void aLocalizedLoginErrorMessageIsShown() {
        // The form-level error region surfaces the server-localized message on 401. Wait for it to
        // appear (auto-retrying), then assert it carries visible text.
        loginPage().errorMessage().first().waitFor();
        String message = loginPage().errorMessage().first().innerText();
        assertThat(message)
                .as("a localized invalid-credentials error should be shown at form level")
                .isNotBlank();
    }

    // ---- Guard round-trip (Requirement 5.4) ----

    @When("I open the protected deep-link {string} while unauthenticated")
    public void iOpenTheProtectedDeepLinkWhileUnauthenticated(String route) {
        Page page = Hooks.currentPage();
        page.navigate(TestConfig.frontendUrl() + route);
    }

    @Then("I am redirected to the login page")
    public void iAmRedirectedToTheLoginPage() {
        Page page = Hooks.currentPage();
        page.waitForURL(url -> url.contains(LoginPage.ROUTE));
        assertThat(loginPage().isOnLogin())
                .as("an unauthenticated protected deep-link should redirect to /login")
                .isTrue();
    }

    @Then("I am returned to the deep-link {string}")
    public void iAmReturnedToTheDeepLink(String route) {
        Page page = Hooks.currentPage();
        // After a successful login the Return_Location round-trip navigates back to the original
        // deep-link rather than the home route.
        page.waitForURL(url -> url.contains(route));
        AppShell shell = new AppShell(page);
        assertThat(shell.isOnRoute(route))
                .as("after login the app should return to the originally requested deep-link")
                .isTrue();
    }

    // ---- Logout (Requirement 5.5) ----

    @When("I log out")
    public void iLogOut() {
        new AppShell(Hooks.currentPage()).logout();
    }

    // ---- Shared negative assertions ----

    @Then("I stay on the login page")
    public void iStayOnTheLoginPage() {
        assertThat(loginPage().isOnLogin())
                .as("the user should remain on /login")
                .isTrue();
    }

    @Then("no tokens are stored")
    public void noTokensAreStored() {
        assertThat(loginPage().localStorageItem(ACCESS_TOKEN_KEY))
                .as("no access token should be stored")
                .isNull();
        assertThat(loginPage().localStorageItem(REFRESH_TOKEN_KEY))
                .as("no refresh token should be stored")
                .isNull();
    }
}
