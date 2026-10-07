package com.foremen.qa.pages;

import java.util.ArrayList;
import java.util.List;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the global top-bar notification bell (frontend
 * {@code NotificationBell.tsx}, FOR-05-07 R13.3–13.11). The bell lives in {@code TopBar} on every
 * authenticated surface, so it can be opened from any non-login page (e.g. the dashboard the client
 * lands on after OTP sign-in).
 *
 * <p>All locators key off the confirmed {@code data-testid}s added for the E2E:
 * <ul>
 *   <li>{@code notification-bell} — the trigger button;</li>
 *   <li>{@code notification-bell-badge} — the unread-count badge (present only when unread &gt; 0);</li>
 *   <li>{@code notification-list} — the open popover list container;</li>
 *   <li>{@code notification-item-<id>} — each row {@code <li>};</li>
 *   <li>{@code notification-type-<id>} — the row's localized title text element;</li>
 *   <li>{@code notification-link-<id>} — the clickable deep-link target in the row.</li>
 * </ul>
 *
 * <p>This object holds NO assertions — it only opens the bell and returns observable data (row
 * titles, badge count, raw-key presence) and drives the deep-link click; the steps assert.
 */
public final class NotificationBellUi {

    private final Page page;

    public NotificationBellUi(Page page) {
        this.page = page;
    }

    /** Open the bell: click the trigger and wait for the popover list to render. */
    public NotificationBellUi open() {
        Locator bell = testId("notification-bell");
        bell.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        bell.first().click();
        testId("notification-list").first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /** {@code true} when the unread badge is shown (i.e. unread count &gt; 0). */
    public boolean isBadgeShown() {
        return testId("notification-bell-badge").count() > 0;
    }

    /**
     * The unread-count shown on the badge, parsed from its text. Returns {@code 0} when no badge is
     * shown; the frontend renders {@code 99+} above 99, which this maps to {@code 100}.
     */
    public int unreadBadgeCount() {
        Locator badge = testId("notification-bell-badge");
        if (badge.count() == 0) {
            return 0;
        }
        String text = badge.first().innerText();
        if (text == null) {
            return 0;
        }
        String trimmed = text.trim();
        if (trimmed.contains("+")) {
            return 100;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The visible (localized) title text of every notification row, in list order (newest-first). */
    public List<String> typesShown() {
        Locator types = page.locator("[data-testid^='notification-type-']");
        int count = types.count();
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String text = types.nth(i).innerText();
            result.add(text == null ? "" : text.trim());
        }
        return result;
    }

    /** {@code true} when some notification row shows exactly {@code visibleText} as its title. */
    public boolean hasType(String visibleText) {
        return typesShown().contains(visibleText);
    }

    /**
     * {@code true} when ANY visible notification title is a raw i18n key (starts with
     * {@code "notification."}) — a missing-translation leak the bell must never show.
     */
    public boolean hasRawKeyTitle() {
        return typesShown().stream().anyMatch(text -> text.startsWith("notification."));
    }

    /**
     * Click the deep-link of the FIRST notification row whose localized title equals
     * {@code visibleText}. Locates the matching {@code notification-type-<id>} element, derives its
     * row id, and clicks the sibling {@code notification-link-<id>} (the clickable deep-link target).
     *
     * @throws IllegalStateException when no row with that title is present
     */
    public void clickDeepLinkForType(String visibleText) {
        Locator types = page.locator("[data-testid^='notification-type-']");
        int count = types.count();
        for (int i = 0; i < count; i++) {
            Locator typeEl = types.nth(i);
            String text = typeEl.innerText();
            if (text != null && text.trim().equals(visibleText)) {
                String testid = typeEl.getAttribute("data-testid");
                // testid is "notification-type-<id>"; the deep-link target is "notification-link-<id>".
                String id = testid.substring("notification-type-".length());
                testId("notification-link-" + id).first().click();
                return;
            }
        }
        throw new IllegalStateException(
                "No notification row with title '" + visibleText + "'; shown: " + typesShown());
    }

    // ---- Internals ----

    private Locator testId(String id) {
        return page.locator("[data-testid=\"" + id + "\"]");
    }
}
