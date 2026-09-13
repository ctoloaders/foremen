package com.foremen.qa.pages;

import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Page object for the authenticated application shell (Requirements 6.1, 7.1).
 *
 * <p>The real frontend {@code AppShell} is responsive:
 * <ul>
 *   <li><b>Desktop (&gt;1024px)</b> — a fixed {@code <aside>} sidebar (nav links + a user footer)
 *       plus a {@code <header>} topbar; primary nav lives in {@code nav a[href=...]}.</li>
 *   <li><b>Tablet (768–1024px)</b> — the same sidebar, collapsed to icons (labels appear on hover);
 *       links are still present in the DOM.</li>
 *   <li><b>Mobile (&lt;768px)</b> — no sidebar; a bottom {@code nav} holds the {@code bottomNav}
 *       items and a hamburger opens a drawer with the full nav + user footer.</li>
 * </ul>
 *
 * <p>Navigation items are rendered as router {@code <Link>}s, i.e. anchors whose {@code href} is the
 * route path (e.g. {@code /users}). Because item labels are localized (RU/PL), this page object
 * locates nav items by their <em>route</em> ({@code nav a[href="…"]}) — a stable, locale-independent
 * hook — rather than by label text. The "current role name" the topbar requirement refers to is
 * rendered in the sidebar/drawer <b>user footer</b> (name + role code), so {@link #roleBadgeText()}
 * reads it there.
 */
public final class AppShell {

    /** Home route the app lands on after login (centralized here). */
    public static final String HOME_ROUTE = "/";

    private final Page page;

    public AppShell(Page page) {
        this.page = page;
    }

    /** Absolute home URL against the configured frontend base URL. */
    public static String homeUrl() {
        return TestConfig.frontendUrl() + HOME_ROUTE;
    }

    // ---- Shell readiness (Requirement 7.1) ----

    /**
     * {@code true} once the shell chrome has rendered: the topbar {@code <header>} plus at least one
     * navigation region ({@code <nav>}). This holds across breakpoints (desktop/tablet sidebar nav
     * and mobile bottom nav both use {@code <nav>}).
     */
    public boolean isRendered() {
        return topBar().count() > 0 && page.locator("nav").count() > 0;
    }

    /** Wait for the shell to render (topbar visible), useful right after a login navigation. */
    public AppShell waitUntilRendered() {
        topBar().first().waitFor();
        return this;
    }

    /** The topbar header element. */
    public Locator topBar() {
        return page.locator("header");
    }

    // ---- Role badge (Requirement 6.1) ----

    /**
     * The current user's role code as shown in the sidebar/drawer user footer. The footer button
     * carries the user's name as its {@code aria-label} and renders the name + role code as two
     * stacked lines; the role is the second line. Returns the trimmed text, or an empty string when
     * the footer is not present (e.g. an unauthenticated page).
     */
    public String roleBadgeText() {
        Locator footer = userFooterButton();
        if (footer.count() == 0) {
            return "";
        }
        // The footer button contains two <p> lines: name (first) then role code (second).
        Locator lines = footer.first().locator("p");
        if (lines.count() < 2) {
            return "";
        }
        String text = lines.nth(1).innerText();
        return text == null ? "" : text.trim();
    }

    /**
     * The user-footer trigger button (avatar + name + role). Present in the desktop/tablet sidebar;
     * on mobile it lives inside the drawer (open the drawer first to read it).
     */
    public Locator userFooterButton() {
        return page.locator("aside button[aria-haspopup=menu]");
    }

    // ---- Logout (Requirement 5.5) ----

    /**
     * Open the user-footer menu and click its Log_out {@code role=menuitem} action, then wait for
     * the SPA to land back on {@code /login}. Mirrors the frontend {@code UserFooter}: the footer
     * button opens a {@code role=menu} popover whose single menuitem triggers
     * {@code Auth_Store.logout()} (clears tokens) and navigates to {@code /login}. The menuitem
     * label is localized (RU/PL), so it is located by role, not text.
     */
    public void logout() {
        userFooterButton().first().click();
        Locator logoutItem = page.getByRole(AriaRole.MENUITEM);
        logoutItem.first().waitFor();
        logoutItem.first().click();
        page.waitForURL(url -> url.contains(LoginPage.ROUTE));
    }

    // ---- Navigation visibility (Requirement 6.1, 6.2) ----

    /**
     * A navigation anchor for the given route, located by its {@code href} regardless of surface
     * (sidebar, drawer, or bottom nav) or locale.
     */
    public Locator navItem(String route) {
        return page.locator("nav a[href=\"" + route + "\"]");
    }

    /**
     * {@code true} when a navigation item for {@code route} is present and visible in any nav
     * surface. Permission-hidden items are removed from the DOM by the frontend, so a hidden item
     * yields {@code false} here (Requirement 6.2).
     */
    public boolean isNavItemVisible(String route) {
        Locator item = navItem(route);
        return item.count() > 0 && item.first().isVisible();
    }

    /** Count of distinct navigation groups (section headers) currently rendered. */
    public int navGroupCount() {
        // Section headers are rendered as uppercase tracking-wider labels; the simplest stable
        // signal is the presence of the top-level nav plus its grouped links. We expose the raw
        // count of visible nav anchors instead, which steps assert against known routes.
        return page.locator("nav a[href]").count();
    }

    // ---- Navigation actions ----

    /** Click the nav item for {@code route} and wait for the SPA to land on it. */
    public void navigateTo(String route) {
        navItem(route).first().click();
        page.waitForURL(url -> url.endsWith(route) || url.contains(route + "?") || url.contains(route + "#"));
    }

    /**
     * Directly open a route by URL (deep-link). Useful for guard scenarios where the nav item is
     * hidden but the route is still reachable by URL.
     */
    public void openRoute(String route) {
        page.navigate(TestConfig.frontendUrl() + route);
    }

    /** {@code true} when the browser is currently on {@code route}. */
    public boolean isOnRoute(String route) {
        String url = page.url();
        return url.endsWith(route) || url.contains(route + "?") || url.contains(route + "#");
    }
}
