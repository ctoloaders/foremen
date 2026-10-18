package com.foremen.qa.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the FOR-06-01 Work Reporting panel rendered on the execution-stage
 * {@code workReporting} workspace tab.
 *
 * <p>Locators key off the real frontend testids (confirmed in
 * {@code features/project-execution/work-reporting/}): the panel root
 * {@code [data-testid=work-reporting-panel]}, the "new report" action, the list / empty states, the
 * per-row review action, and the builder + review dialogs. Holds no assertions — steps assert
 * against these intention-revealing queries.
 */
public final class WorkReportingTab {

    private final Page page;

    public WorkReportingTab(Page page) {
        this.page = page;
    }

    // ---- Panel ----

    /** The Work Reporting panel root. */
    public Locator panel() {
        return page.locator("[data-testid=work-reporting-panel]");
    }

    public boolean isRendered() {
        return panel().count() > 0 && panel().first().isVisible();
    }

    public WorkReportingTab waitUntilRendered() {
        panel().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /** The "new report" action (only rendered for a CREATE-capable caller on an ACTIVE project). */
    public Locator newReportButton() {
        return page.locator("[data-testid=work-reporting-new]");
    }

    public boolean isNewReportVisible() {
        return newReportButton().count() > 0 && newReportButton().first().isVisible();
    }

    public void clickNewReport() {
        newReportButton().first().click();
    }

    /** The report list container (present once at least one report exists). */
    public Locator list() {
        return page.locator("[data-testid=work-reporting-list]");
    }

    /** The empty-state container (present when the project has no reports). */
    public Locator empty() {
        return page.locator("[data-testid=work-reporting-empty]");
    }

    /** All report rows. */
    public Locator rows() {
        return page.locator("[data-testid=work-reporting-row]");
    }

    public int rowCount() {
        return rows().count();
    }

    /** The per-row review action (approver only, on SUBMITTED / CHANGES_REQUESTED rows). */
    public Locator reviewButtons() {
        return page.locator("[data-testid=work-reporting-review]");
    }

    public boolean hasAnyReviewAction() {
        return reviewButtons().count() > 0;
    }

    // ---- Builder dialog ----

    public Locator builder() {
        return page.locator("[data-testid=work-report-builder]");
    }

    public WorkReportingTab waitBuilderOpen() {
        builder().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    public Locator builderWorkerSelect() {
        return page.locator("[data-testid=work-report-worker]");
    }

    public Locator builderWorkItemSelect() {
        return page.locator("[data-testid=work-report-work-item]");
    }

    public Locator builderRowRoomSelect() {
        return page.locator("[data-testid=work-report-row-room]");
    }

    public Locator builderRowModeSelect() {
        return page.locator("[data-testid=work-report-row-mode]");
    }

    public Locator builderRowQuantity() {
        return page.locator("[data-testid=work-report-row-quantity]");
    }

    public Locator builderPerformedFrom() {
        return page.locator("[data-testid=work-report-from]");
    }

    public Locator builderPerformedTo() {
        return page.locator("[data-testid=work-report-to]");
    }

    /** The live PERCENT -> absolute resolution preview (shown only for a PERCENT row with a plan). */
    public Locator builderRowResolved() {
        return page.locator("[data-testid=work-report-row-resolved]");
    }

    /** The no-plan block (shown for a PERCENT row against a room with no plan). */
    public Locator builderRowNoPlan() {
        return page.locator("[data-testid=work-report-row-noplan]");
    }

    public Locator builderNote() {
        return page.locator("[data-testid=work-report-note]");
    }

    public Locator builderDraftPrompt() {
        return page.locator("[data-testid=work-report-draft-prompt]");
    }

    public void clickDraftContinue() {
        page.locator("[data-testid=work-report-draft-continue]").first().click();
    }

    public void clickDraftDiscard() {
        page.locator("[data-testid=work-report-draft-discard]").first().click();
    }

    /** The builder submit action. */
    public Locator builderSubmit() {
        return page.locator("[data-testid=work-report-submit]");
    }

    // ---- Review dialog ----

    public Locator reviewDialog() {
        return page.locator("[data-testid=work-report-review-dialog]");
    }

    public WorkReportingTab waitReviewOpen() {
        reviewDialog().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    public Locator approveButton() {
        return page.locator("[data-testid=work-report-approve]");
    }

    public Locator rejectButton() {
        return page.locator("[data-testid=work-report-reject]");
    }

    public Locator requestChangesButton() {
        return page.locator("[data-testid=work-report-request-changes]");
    }

    public Locator reasonField() {
        return page.locator("[data-testid=work-report-review-reason-field]");
    }

    public Locator reasonConfirm() {
        return page.locator("[data-testid=work-report-reason-confirm]");
    }
}
