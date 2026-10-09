package com.foremen.qa.pages;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
/**
 * Page object for the design-stage {@code documentSigning} workspace tab (FOR-05-08 frontend
 * {@code DocumentSigningTab.tsx}), reached at {@code /projects/{projectId}/documentSigning} via
 * {@link ProjectWorkspacePage#workspaceTabUrl(long, String)}. The tab lists the project documents
 * (type / status / progress / created) and opens one into {@link DocumentDetail}. Locators key off
 * the confirmed testids ({@code document-signing-tab}, {@code document-signing-list},
 * {@code document-row-{id}}, {@code document-open-{id}}, {@code document-signing-create},
 * {@code document-signing-empty}). Holds no assertions.
 */
public final class DocumentSigningTab {
    public static final String TAB_KEY = "documentSigning";
    private final Page page;
    public DocumentSigningTab(Page page) {
        this.page = page;
    }
    public DocumentSigningTab open(long projectId) {
        page.navigate(ProjectWorkspacePage.workspaceTabUrl(projectId, TAB_KEY));
        return this;
    }

    public Locator container() {
        return page.locator("[data-testid=document-signing-tab]");
    }
    public DocumentSigningTab waitUntilRendered() {
        container().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        settle();
        return this;
    }
    private void settle() {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE);
        } catch (RuntimeException ignored) {
        }
        try {
            page.waitForFunction("() => document.querySelectorAll('.animate-pulse').length === 0",
                    null, new Page.WaitForFunctionOptions().setTimeout(5000));
        } catch (RuntimeException ignored) {
        }
    }
    public boolean isRendered() {
        return container().count() > 0 && container().first().isVisible();
    }

    public Locator list() {
        return page.locator("[data-testid=document-signing-list]");
    }
    public boolean isListRendered() {
        return list().count() > 0 && list().first().isVisible();
    }
    public boolean isEmptyStateShown() {
        Locator empty = page.locator("[data-testid=document-signing-empty]");
        return empty.count() > 0 && empty.first().isVisible();
    }
    public int rowCount() {
        return page.locator("[data-testid^='document-row-']").count();
    }
    public boolean hasRow(long documentId) {
        return page.locator("[data-testid=document-row-" + documentId + "]").count() > 0;
    }
    public boolean isCreateButtonVisible() {
        Locator create = page.locator("[data-testid=document-signing-create]");
        return create.count() > 0 && create.first().isVisible();
    }
    public void openDocument(long documentId) {
        Locator open = page.locator("[data-testid=document-open-" + documentId + "]");
        open.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        open.first().click();
    }
    public String listText() {
        if (list().count() == 0) { return ""; }
        String text = list().first().innerText();
        return text == null ? "" : text;
    }
}
