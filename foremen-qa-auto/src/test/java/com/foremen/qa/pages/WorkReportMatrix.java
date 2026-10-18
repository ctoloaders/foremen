package com.foremen.qa.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the FOR-06-01 Amendment A1 matrix batch-authoring surface - the replacement for
 * the removed single-report WorkReportBuilder. Locators key off the real frontend testids in
 * features/project-execution/work-reporting/components/. Holds no assertions.
 */
public final class WorkReportMatrix {

    private final Page page;

    public WorkReportMatrix(Page page) {
        this.page = page;
    }

    private Locator byTestId(String id) {
        return page.locator("[data-testid=" + id + "]");
    }

    public Locator dialog() {
        return byTestId("work-report-matrix");
    }

    public WorkReportMatrix waitOpen() {
        dialog().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    public boolean isOpen() {
        return dialog().count() > 0 && dialog().first().isVisible();
    }

    public Locator workerSelect() {
        return byTestId("work-report-matrix-worker");
    }

    public boolean hasWorkerPicker() {
        return workerSelect().count() > 0 && workerSelect().first().isVisible();
    }

    public void pickWorker(long workerId) {
        workerSelect().first().waitFor();
        page.locator("[data-testid=work-report-matrix-worker] option[value='" + workerId + "']")
                .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
        workerSelect().selectOption(String.valueOf(workerId));
    }

    public Locator grid() {
        return byTestId("work-report-matrix-grid");
    }

    public WorkReportMatrix waitGrid() {
        grid().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    public boolean isGridVisible() {
        return grid().count() > 0 && grid().first().isVisible();
    }

    public Locator emptyState() {
        return byTestId("work-report-matrix-empty");
    }

    public Locator categoryHeaders() {
        return byTestId("work-report-matrix-category");
    }

    public Locator rows() {
        return byTestId("work-report-matrix-row");
    }

    public Locator cell(long workItemId, long roomId) {
        return page.locator("[data-testid=work-report-matrix-cell][data-work-item-id='" + workItemId
                + "'][data-room-id='" + roomId + "']");
    }

    public Locator cellButton(long workItemId, long roomId) {
        return cell(workItemId, roomId).locator("[data-testid=work-report-matrix-cell-button]");
    }

    public boolean isCellComplete(long workItemId, long roomId) {
        Locator c = cell(workItemId, roomId);
        return c.count() > 0 && "true".equals(c.first().getAttribute("data-complete"));
    }

    public boolean isCellStaged(long workItemId, long roomId) {
        Locator c = cell(workItemId, roomId);
        return c.count() > 0 && "true".equals(c.first().getAttribute("data-staged"));
    }

    public void openCell(long workItemId, long roomId) {
        cellButton(workItemId, roomId).first().click();
    }

    public Locator cellStagedBadge(long workItemId, long roomId) {
        return cell(workItemId, roomId).locator("[data-testid=work-report-matrix-cell-staged]");
    }

    public Locator cellPopup() {
        return byTestId("work-report-cell-popup");
    }

    public WorkReportMatrix waitCellPopup() {
        cellPopup().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    public void cellModePercent() {
        byTestId("work-report-cell-mode-percent").first().click();
    }

    public void cellModeAbsolute() {
        byTestId("work-report-cell-mode-absolute").first().click();
    }

    public void cellQuantity(String value) {
        byTestId("work-report-cell-quantity").first().fill(value);
    }

    public Locator cellUnit() {
        return byTestId("work-report-cell-unit");
    }

    public Locator cellResolvedPreview() {
        return byTestId("work-report-cell-resolved");
    }

    public Locator cellNoPlanBlock() {
        return byTestId("work-report-cell-noplan");
    }

    public Locator cellFrom() {
        return byTestId("work-report-cell-from");
    }

    public Locator cellTo() {
        return byTestId("work-report-cell-to");
    }

    public void cellStepFromPrev() {
        byTestId("work-report-cell-from-prev").first().click();
    }

    public void cellStepFromNext() {
        byTestId("work-report-cell-from-next").first().click();
    }

    public void cellStepToPrev() {
        byTestId("work-report-cell-to-prev").first().click();
    }

    public void cellStepToNext() {
        byTestId("work-report-cell-to-next").first().click();
    }

    public void cellConfirm() {
        byTestId("work-report-cell-confirm").first().click();
    }

    public void cellCancel() {
        byTestId("work-report-cell-cancel").first().click();
    }

    public WorkReportMatrix waitCellPopupClosed() {
        cellPopup().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        return this;
    }

    public Locator draftPanel() {
        return byTestId("work-report-draft-panel");
    }

    public Locator draftLines() {
        return byTestId("work-report-draft-line");
    }

    public int draftLineCount() {
        return draftLines().count();
    }

    public Locator draftCountBadge() {
        return byTestId("work-report-draft-count");
    }

    public Locator draftEmpty() {
        return byTestId("work-report-draft-empty");
    }

    public Locator draftLine(long workItemId, long roomId) {
        return page.locator("[data-testid=work-report-draft-line][data-work-item-id='" + workItemId
                + "'][data-room-id='" + roomId + "']");
    }

    public void draftEdit(long workItemId, long roomId) {
        draftLine(workItemId, roomId).locator("[data-testid=work-report-draft-edit]").first().click();
    }

    public void draftRemove(long workItemId, long roomId) {
        draftLine(workItemId, roomId).locator("[data-testid=work-report-draft-remove]").first().click();
    }

    public Locator draftSave() {
        return byTestId("work-report-draft-save");
    }

    public void clickDraftSave() {
        draftSave().first().click();
    }

    public Locator draftReveal() {
        return byTestId("work-report-draft-reveal");
    }

    public Locator draftRevealCount() {
        return byTestId("work-report-draft-reveal-count");
    }

    public void clickDraftReveal() {
        draftReveal().first().click();
    }

    public Locator draftSheet() {
        return byTestId("work-report-draft-sheet");
    }

    public WorkReportMatrix waitDraftSheet() {
        draftSheet().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    public Locator draftPrompt() {
        return byTestId("work-report-matrix-draft-prompt");
    }

    public void draftPromptContinue() {
        byTestId("work-report-matrix-draft-continue").first().click();
    }

    public void draftPromptDiscard() {
        byTestId("work-report-matrix-draft-discard").first().click();
    }
}
