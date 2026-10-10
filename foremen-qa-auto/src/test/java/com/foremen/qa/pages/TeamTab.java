package com.foremen.qa.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the design-stage {@code team} workspace tab (FOR-05-09 frontend
 * {@code project-team/components/TeamTab.tsx}), reached at {@code /projects/{projectId}/team} via
 * {@link ProjectWorkspacePage#workspaceTabUrl(long, String)}.
 *
 * <p>The tab renders three blocks in the fixed order ADMIN_STAFF → WORKERS → CLIENTS, each with a
 * localized title + a {@code (N)} count, a desktop table (≥768px) / mobile card stack (&lt;768px),
 * per-member status badges, worker-type details and a missing-type warning, an ADMIN_STAFF foreman
 * hint, a client-side tag filter, and per-block add / invite affordances. Locators key off the
 * confirmed {@code data-testid}s. Holds no assertions.
 */
public final class TeamTab {

    public static final String TAB_KEY = "team";

    private final Page page;

    public TeamTab(Page page) {
        this.page = page;
    }

    // ---- Navigation ----

    public TeamTab open(long projectId) {
        page.navigate(ProjectWorkspacePage.workspaceTabUrl(projectId, TAB_KEY));
        return this;
    }

    public Locator container() {
        return page.locator("[data-testid=team-tab]");
    }

    public TeamTab waitUntilRendered() {
        container().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        settle();
        return this;
    }

    private void settle() {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE);
        } catch (RuntimeException ignored) {
            // best-effort settle
        }
        try {
            page.waitForFunction("() => document.querySelectorAll('.animate-pulse').length === 0",
                    null, new Page.WaitForFunctionOptions().setTimeout(5000));
        } catch (RuntimeException ignored) {
            // best-effort settle
        }
    }

    public boolean isRendered() {
        return container().count() > 0 && container().first().isVisible();
    }

    // ---- Blocks + counts ----

    public Locator block(String block) {
        return page.locator("[data-testid=team-block-" + block + "]");
    }

    public boolean isBlockVisible(String block) {
        return block(block).count() > 0 && block(block).first().isVisible();
    }

    public Locator count(String block) {
        return page.locator("[data-testid=team-count-" + block + "]");
    }

    /** The integer in the block count span, e.g. {@code (2)} → 2; -1 when absent. */
    public int countValue(String block) {
        Locator c = count(block);
        if (c.count() == 0) {
            return -1;
        }
        String text = c.first().innerText();
        if (text == null) {
            return -1;
        }
        String digits = text.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? -1 : Integer.parseInt(digits);
    }

    public Locator blockTitleHeading(String block) {
        // The <h3> that wraps the title text and the count span.
        return block(block).locator("h3");
    }

    public boolean isEmptyStateShown(String block) {
        Locator empty = page.locator("[data-testid=team-empty-" + block + "]");
        return empty.count() > 0 && empty.first().isVisible();
    }

    public boolean isNoMatchShown(String block) {
        Locator nm = page.locator("[data-testid=team-no-match-" + block + "]");
        return nm.count() > 0 && nm.first().isVisible();
    }

    // ---- Members ----

    /** The desktop row for a member id (present at ≥768px). */
    public Locator row(long memberId) {
        return page.locator("[data-testid=team-row-" + memberId + "]");
    }

    public boolean hasRow(long memberId) {
        return row(memberId).count() > 0;
    }

    /** The number of desktop rows across all blocks. */
    public int rowCount() {
        return page.locator("[data-testid^='team-row-']").count();
    }

    // ---- Badges / warnings / hints ----

    public boolean isForemanHintShown() {
        Locator hint = page.locator("[data-testid=team-foreman-hint]");
        return hint.count() > 0 && hint.first().isVisible();
    }

    public int missingWorkerTypeWarningCount() {
        return page.locator("[data-testid=team-missing-worker-type-warning]").count();
    }

    public int workerTypeMissingBadgeCount() {
        return page.locator("[data-testid=worker-type-missing]").count();
    }

    public int workerTypeNameCount() {
        return page.locator("[data-testid=worker-type-name]").count();
    }

    public int memberTagsContainerCount() {
        return page.locator("[data-testid=member-tags]").count();
    }

    // ---- Add / invite affordances ----

    public Locator addButton(String block) {
        return page.locator("[data-testid=team-add-" + block + "]");
    }

    public boolean isAddButtonVisible(String block) {
        return addButton(block).count() > 0 && addButton(block).first().isVisible();
    }

    public Locator addNewWorkerButton() {
        return page.locator("[data-testid=team-add-new-worker]");
    }

    public Locator inviteClientButton() {
        return page.locator("[data-testid=team-invite-client]");
    }

    public boolean isInviteClientVisible() {
        return inviteClientButton().count() > 0 && inviteClientButton().first().isVisible();
    }

    public boolean isAddNewWorkerVisible() {
        return addNewWorkerButton().count() > 0 && addNewWorkerButton().first().isVisible();
    }

    // ---- Tag filter ----

    public Locator tagFilter() {
        return page.locator("[data-testid=team-tag-filter]");
    }

    public boolean isTagFilterShown() {
        return tagFilter().count() > 0 && tagFilter().first().isVisible();
    }

    public Locator tagFilterSelect() {
        return page.locator("[data-testid=team-tag-filter-select]");
    }

    /** Select a tag by its visible value; blocks that have no matching member show no-match. */
    public void selectTag(String tag) {
        tagFilterSelect().first().selectOption(tag);
        settle();
    }

    public void clearTagFilter() {
        Locator clear = page.locator("[data-testid=team-tag-filter-clear]");
        if (clear.count() > 0) {
            clear.first().click();
            settle();
        }
    }

    // ---- Open the per-member actions menu + items ----

    public void openMemberMenu(long memberId) {
        Locator trigger = page.locator("[data-testid=member-actions-trigger-" + memberId + "]");
        trigger.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        trigger.first().click();
    }

    public Locator memberMenu(long memberId) {
        return page.locator("[data-testid=member-actions-menu-" + memberId + "]");
    }

    public boolean hasMemberActionsTrigger(long memberId) {
        return page.locator("[data-testid=member-actions-trigger-" + memberId + "]").count() > 0;
    }

    public Locator memberActionWorkerType(long memberId) {
        return page.locator("[data-testid=member-action-worker-type-" + memberId + "]");
    }

    public Locator memberActionTags(long memberId) {
        return page.locator("[data-testid=member-action-tags-" + memberId + "]");
    }

    public Locator memberActionStatus(long memberId) {
        return page.locator("[data-testid=member-action-status-" + memberId + "]");
    }

    public Locator memberActionRemove(long memberId) {
        return page.locator("[data-testid=member-action-remove-" + memberId + "]");
    }

    // ---- Whole-tab text (for i18n / no-raw-key assertions) ----

    public String tabText() {
        if (container().count() == 0) {
            return "";
        }
        String text = container().first().innerText();
        return text == null ? "" : text;
    }
}
