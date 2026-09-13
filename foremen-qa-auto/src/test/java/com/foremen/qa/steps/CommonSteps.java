package com.foremen.qa.steps;

import com.foremen.qa.pages.AppShell;
import com.foremen.qa.pages.LoginPage;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.TestConfig;
import com.foremen.qa.support.WaitFor;
import com.microsoft.playwright.Page;

import io.cucumber.java.en.Given;

/**
 * Cross-cutting Gherkin steps shared by every feature slice: the stack-ready background and the
 * "log in as the seeded admin" convenience (Requirements 4.1, 5.1).
 *
 * <p>These are the composable building blocks other slices reuse for their {@code Background} and
 * for reaching an authenticated state without repeating the login mechanics. They deliberately hold
 * no raw selectors — page objects ({@link LoginPage}, {@link AppShell}) own those.
 *
 * <p>Steps that assert token storage, blocked submissions, localized errors, guard round-trips and
 * logout live in {@link AuthSteps}; this class only provides the plumbing every feature needs.
 */
public class CommonSteps {

    /**
     * Background step: gate the scenario on the live Docker stack being up (backend health +
     * frontend availability). {@link WaitFor#stackIsReady()} is idempotent and cached, so after the
     * first scenario confirms readiness this returns immediately (Requirement 2.2).
     */
    @Given("the application stack is ready")
    public void theApplicationStackIsReady() {
        WaitFor.stackIsReady();
    }

    /**
     * Reach an authenticated session as the seeded ADMIN by driving the {@code /login} form with the
     * configured admin credentials and waiting for the app shell to render. Reusable "login-as"
     * building block for slices that need to start already authenticated (e.g. logout).
     */
    @Given("I am logged in as the seeded admin")
    public void iAmLoggedInAsTheSeededAdmin() {
        Page page = Hooks.currentPage();
        LoginPage loginPage = new LoginPage(page);
        loginPage.open();
        loginPage.login(TestConfig.adminEmail(), TestConfig.adminPassword());
        page.waitForURL(url -> !url.contains(LoginPage.ROUTE));
        new AppShell(page).waitUntilRendered();
    }
}
