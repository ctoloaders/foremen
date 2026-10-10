package com.foremen.qa.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the design-stage {@code scheduleDesign} workspace tab — the Planning Gantt
 * (Harmonogram) of FOR-05-10 (frontend {@code project-schedule/components/ScheduleTab.tsx}), reached
 * at {@code /projects/{projectId}/scheduleDesign} via
 * {@link ProjectWorkspacePage#workspaceTabUrl(long, String)}.
 *
 * <p>The tab renders (loaded state) a toolbar (zoom Week/Day + Save/Discard/Auto-create), a summary
 * strip (finish, total days, crew, exceeds-end warning, no-start hint), the Gantt grid (a sticky
 * label column of per-category rows + a scrolling timeline with bars), and a calendar legend. The
 * states are loading / error+retry / empty / loaded. Locators key off the confirmed
 * {@code data-testid}s of the FOR-05-10 components. Holds no assertions.
 */
public final class ScheduleTab {

    /** The workspace tab key for the Planning Gantt (matches {@code WORKSPACE_TABS}). */
    public static final String TAB_KEY = "scheduleDesign";

    private final Page page;

    public ScheduleTab(Page page) {
        this.page = page;
    }

    // ---- Navigation ----

    /** Navigate to {@code /projects/{projectId}/scheduleDesign}. */
    public ScheduleTab open(long projectId) {
        page.navigate(ProjectWorkspacePage.workspaceTabUrl(projectId, TAB_KEY));
        return this;
    }

    /** The loaded-tab container (present only in the loaded state). */
    public Locator container() {
        return page.locator("[data-testid=schedule-tab]");
    }

    /** Wait until the loaded Gantt surface has rendered (not the loading/error/empty states). */
    public ScheduleTab waitUntilRendered() {
        container().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        settle();
        return this;
    }

    /** Wait until SOME terminal state (loaded / empty / error) has rendered. */
    public ScheduleTab waitUntilSettled() {
        page.waitForFunction(
                "() => document.querySelector('[data-testid=schedule-tab]')"
                        + " || document.querySelector('[data-testid=schedule-tab-empty]')"
                        + " || document.querySelector('[data-testid=schedule-tab-error]')",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));
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

    // ---- States ----

    public boolean isLoadingShown() {
        return isVisible("schedule-tab-loading");
    }

    public boolean isErrorShown() {
        return isVisible("schedule-tab-error");
    }

    public boolean isEmptyShown() {
        return isVisible("schedule-tab-empty");
    }

    public Locator retryButton() {
        return page.locator("[data-testid=schedule-retry]");
    }

    public Locator readonlyBanner() {
        return page.locator("[data-testid=schedule-readonly-banner]");
    }

    public boolean isReadOnlyBannerShown() {
        return isVisible("schedule-readonly-banner");
    }

    // ---- Summary ----

    public Locator summary() {
        return page.locator("[data-testid=schedule-summary]");
    }

    public String summaryText() {
        return summary().count() == 0 ? "" : safeText(summary().first());
    }

    public boolean isExceedsWarningShown() {
        return isVisible("schedule-exceeds-warning");
    }

    public boolean isNoStartHintShown() {
        return isVisible("schedule-no-start-hint");
    }

    public String crewText() {
        Locator crew = page.locator("[data-testid=schedule-summary-crew]");
        return crew.count() == 0 ? "" : safeText(crew.first());
    }

    // ---- Toolbar ----

    public Locator zoomWeekButton() {
        return page.locator("[data-testid=schedule-zoom-week]");
    }

    public Locator zoomDayButton() {
        return page.locator("[data-testid=schedule-zoom-day]");
    }

    public void switchToDayView() {
        zoomDayButton().first().click();
        settle();
    }

    public void switchToWeekView() {
        zoomWeekButton().first().click();
        settle();
    }

    public boolean isWeekViewActive() {
        return "true".equals(attr("schedule-zoom-week", "aria-pressed"));
    }

    public boolean isDayViewActive() {
        return "true".equals(attr("schedule-zoom-day", "aria-pressed"));
    }

    public Locator saveButton() {
        return page.locator("[data-testid=schedule-save]");
    }

    public Locator discardButton() {
        return page.locator("[data-testid=schedule-discard]");
    }

    public Locator autoCreateButton() {
        return page.locator("[data-testid=schedule-auto-create]");
    }

    public Locator autoCreateDisabledButton() {
        return page.locator("[data-testid=schedule-auto-create-disabled]");
    }

    public Locator unsavedBadge() {
        return page.locator("[data-testid=schedule-unsaved-badge]");
    }

    public boolean isUnsavedBadgeShown() {
        return isVisible("schedule-unsaved-badge");
    }

    public boolean isSaveEnabled() {
        Locator save = saveButton();
        return save.count() > 0 && save.first().isEnabled();
    }

    public void clickSave() {
        saveButton().first().click();
    }

    public void clickDiscard() {
        discardButton().first().click();
    }

    public void clickAutoCreate() {
        autoCreateButton().first().click();
    }

    // ---- Gantt grid + headers ----

    public Locator gantt() {
        return page.locator("[data-testid=schedule-gantt]");
    }

    public Locator timelineScroll() {
        return page.locator("[data-testid=schedule-timeline-scroll]");
    }

    public boolean isWeekHeaderShown() {
        return isVisible("schedule-header-week");
    }

    public boolean isDayHeaderShown() {
        return isVisible("schedule-header-day");
    }

    public Locator legend() {
        return page.locator("[data-testid=schedule-legend]");
    }

    public boolean isLegendShown() {
        return isVisible("schedule-legend");
    }

    public int weekendCellCount() {
        return page.locator("[data-testid^='schedule-weekend-']").count();
    }

    public int holidayCellCount() {
        return page.locator("[data-testid^='schedule-holiday-']").count();
    }

    public boolean isProjectEndMarkerShown() {
        return page.locator("[data-testid=schedule-project-end-marker]").count() > 0;
    }

    // ---- Rows ----

    public int rowLabelCount() {
        return page.locator("[data-testid^='schedule-row-label-']").count();
    }

    public Locator rowLabel(long workCategoryId) {
        return page.locator("[data-testid=schedule-row-label-" + workCategoryId + "]");
    }

    public Locator rowName(long workCategoryId) {
        return page.locator("[data-testid=schedule-row-name-" + workCategoryId + "]");
    }

    public Locator rowLines(long workCategoryId) {
        return page.locator("[data-testid=schedule-row-lines-" + workCategoryId + "]");
    }

    public Locator rowDuration(long workCategoryId) {
        return page.locator("[data-testid=schedule-row-duration-" + workCategoryId + "]");
    }

    public Locator rowValue(long workCategoryId) {
        return page.locator("[data-testid=schedule-row-value-" + workCategoryId + "]");
    }

    /** The number of money (categoryValue) cells across the visible rows. */
    public int rowValueCount() {
        return page.locator("[data-testid^='schedule-row-value-']").count();
    }

    public Locator rowChangedMark(long workCategoryId) {
        return page.locator("[data-testid=schedule-row-changed-" + workCategoryId + "]");
    }

    public int changedRowCount() {
        return page.locator("[data-testid^='schedule-row-changed-']").count();
    }

    public Locator rowToggle(long workCategoryId) {
        return page.locator("[data-testid=schedule-row-toggle-" + workCategoryId + "]");
    }

    public void toggleRow(long workCategoryId) {
        rowToggle(workCategoryId).first().click();
    }

    public Locator rowScheduleAction(long workCategoryId) {
        return page.locator("[data-testid=schedule-row-schedule-" + workCategoryId + "]");
    }

    public Locator rowEditAction(long workCategoryId) {
        return page.locator("[data-testid=schedule-row-edit-" + workCategoryId + "]");
    }

    public Locator rowClearAction(long workCategoryId) {
        return page.locator("[data-testid=schedule-row-clear-" + workCategoryId + "]");
    }

    // ---- Bars ----

    /** The editable bar for a category name (interactive bar; present when editable + scheduled). */
    public Locator editableBar(String categoryName) {
        return page.locator("[data-testid=schedule-bar-editable-" + categoryName + "]");
    }

    /** All editable bar handles across the grid. */
    public int editableBarCount() {
        return page.locator("[data-testid^='schedule-bar-editable-']").count();
    }

    /** The static (read-only) bar for a work category id (present when NOT editable + scheduled). */
    public Locator staticBar(long workCategoryId) {
        return page.locator("[data-testid=schedule-bar-" + workCategoryId + "]");
    }

    public Locator unscheduledHint(long workCategoryId) {
        return page.locator("[data-testid=schedule-row-unscheduled-" + workCategoryId + "]");
    }

    public boolean isRowUnscheduled(long workCategoryId) {
        return unscheduledHint(workCategoryId).count() > 0;
    }

    // ---- Bar edit dialog ----

    public Locator barEditDialog() {
        return page.locator("[data-testid=schedule-bar-edit-dialog]");
    }

    public boolean isBarEditDialogShown() {
        return isVisible("schedule-bar-edit-dialog");
    }

    public Locator barEditStart() {
        return page.locator("[data-testid=schedule-bar-edit-start]");
    }

    public Locator barEditDuration() {
        return page.locator("[data-testid=schedule-bar-edit-duration]");
    }

    public Locator barEditApply() {
        return page.locator("[data-testid=schedule-bar-edit-apply]");
    }

    public Locator barEditCancel() {
        return page.locator("[data-testid=schedule-bar-edit-cancel]");
    }

    public Locator barEditDurationError() {
        return page.locator("[data-testid=schedule-bar-edit-duration-error]");
    }

    public Locator barEditTooLongError() {
        return page.locator("[data-testid=schedule-bar-edit-toolong-error]");
    }

    // ---- Auto-create dialog ----

    public Locator autoCreateDialog() {
        return page.locator("[data-testid=schedule-auto-create-dialog]");
    }

    public boolean isAutoCreateDialogShown() {
        return isVisible("schedule-auto-create-dialog");
    }

    public Locator autoCreateFormula() {
        return page.locator("[data-testid=schedule-auto-create-formula]");
    }

    public Locator autoCreateCrew() {
        return page.locator("[data-testid=schedule-auto-create-crew]");
    }

    public Locator autoCreateConfirm() {
        return page.locator("[data-testid=schedule-auto-create-confirm]");
    }

    public Locator autoCreateCancel() {
        return page.locator("[data-testid=schedule-auto-create-cancel]");
    }

    // ---- Conflict / leave dialogs ----

    public Locator conflictDialog() {
        return page.locator("[data-testid=schedule-conflict-dialog]");
    }

    public boolean isConflictDialogShown() {
        return isVisible("schedule-conflict-dialog");
    }

    public Locator leaveDialog() {
        return page.locator("[data-testid=schedule-leave-dialog]");
    }

    public Locator leaveStay() {
        return page.locator("[data-testid=schedule-leave-stay]");
    }

    public Locator leaveConfirm() {
        return page.locator("[data-testid=schedule-leave-confirm]");
    }

    public boolean isLeaveDialogShown() {
        return isVisible("schedule-leave-dialog");
    }

    // ---- Whole-tab text (for i18n / no-raw-key assertions) ----

    public String tabText() {
        if (container().count() == 0) {
            return "";
        }
        return safeText(container().first());
    }

    // ---- internals ----

    private boolean isVisible(String testid) {
        Locator l = page.locator("[data-testid=" + testid + "]");
        return l.count() > 0 && l.first().isVisible();
    }

    private String attr(String testid, String name) {
        Locator l = page.locator("[data-testid=" + testid + "]");
        return l.count() == 0 ? null : l.first().getAttribute(name);
    }

    private static String safeText(Locator locator) {
        String text = locator.innerText();
        return text == null ? "" : text;
    }
}
