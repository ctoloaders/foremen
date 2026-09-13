package com.foremen.qa.pages;

import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Page object for the Forbidden page ({@code /403}, Requirement 6.3).
 *
 * <p>The real frontend renders {@code /403} <em>inside</em> the authenticated AppShell with a
 * localized heading ({@code forbidden.title}), an explanatory message ({@code forbidden.message}),
 * and a keyboard-operable Go_Back button ({@code forbidden.goBack}). Because the visible text is
 * localized (RU/PL) this page object locates by structural role rather than by a specific string:
 * <ul>
 *   <li>heading — the {@code <h1>} on the page,</li>
 *   <li>Go_Back — the single primary {@code <button>} in the forbidden panel.</li>
 * </ul>
 * The route is the durable, locale-independent signal that the guard denied access, so
 * {@link #isShown()} keys off the URL.
 */
public final class ForbiddenPage {

    /** Protected route the PermissionGuard redirects to on denial (centralized here). */
    public static final String ROUTE = "/403";

    private final Page page;

    public ForbiddenPage(Page page) {
        this.page = page;
    }

    /** Absolute URL of the forbidden route against the configured frontend base URL. */
    public static String url() {
        return TestConfig.frontendUrl() + ROUTE;
    }

    /**
     * {@code true} when the browser is on {@code /403} and the forbidden heading has rendered.
     *
     * <p>The route change to {@code /403} can complete a beat before the AppShell finishes mounting
     * the ForbiddenPage {@code <h1>} (the shell shows loading skeletons in between), so this waits for
     * the localized heading (PL/RU) to become visible rather than taking an instantaneous
     * {@code count()} snapshot. The durable, locale-independent signal is the URL; the heading is the
     * content confirmation.
     */
    public boolean isShown() {
        if (!page.url().contains(ROUTE)) {
            return false;
        }
        try {
            heading().first().waitFor(new Locator.WaitForOptions()
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                    .setTimeout(10_000));
        } catch (com.microsoft.playwright.TimeoutError e) {
            return false;
        }
        return page.url().contains(ROUTE) && heading().count() > 0;
    }

    /**
     * The localized forbidden heading ({@code forbidden.title} = "Brak dostępu"). {@code /403} renders
     * inside the AppShell, which has its own topbar {@code <h1>} ("Panel"), so scope to the forbidden
     * heading by accessible name to avoid a strict-mode ambiguity.
     */
    public Locator heading() {
        return page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName(
                        java.util.regex.Pattern.compile("Brak dostępu|Нет доступа",
                                java.util.regex.Pattern.CASE_INSENSITIVE)));
    }

    /**
     * The Go_Back control ({@code forbidden.goBack} = "Wróć"). {@code /403} renders inside the
     * AppShell (nav toggles, user menu, locale button all present), so target the button by its
     * accessible name to avoid matching the other shell buttons.
     */
    public Locator goBackButton() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(
                        java.util.regex.Pattern.compile("Wróć|Вернуться назад",
                                java.util.regex.Pattern.CASE_INSENSITIVE)));
    }

    /** Click Go_Back, returning the user to their Last_Allowed_Location (or {@code /}). */
    public void goBack() {
        goBackButton().click();
    }
}
