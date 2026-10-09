package com.foremen.qa.pages;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;
public final class DocumentDetail {
    private final Page page;
    public DocumentDetail(Page page) {
        this.page = page;
    }
    public Locator container() {
        return page.locator("[data-testid=document-detail]");
    }
    public void waitUntilRendered() {
        container().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
    }
    public boolean isRendered() {
        return container().count() > 0 && container().first().isVisible();
    }
    public String statusText() {
        Locator status = page.locator("[data-testid=document-detail-status]");
        if (status.count() == 0) { return ""; }
        String text = status.first().innerText();
        return text == null ? "" : text.trim();
    }
    public boolean isSignersTableShown() {
        Locator signers = page.locator("[data-testid=document-detail-signers]");
        return signers.count() > 0 && signers.first().isVisible();
    }
    public int signerRowCount() {
        return page.locator("[data-testid^='document-signer-']").count();
    }
    public boolean isProgressShown() {
        Locator progress = page.locator("[data-testid=document-detail-progress]");
        return progress.count() > 0 && progress.first().isVisible();
    }
    public String progressText() {
        Locator progress = page.locator("[data-testid=document-detail-progress]");
        if (progress.count() == 0) { return ""; }
        String text = progress.first().innerText();
        return text == null ? "" : text;
    }
    public String detailText() {
        if (container().count() == 0) { return ""; }
        String text = container().first().innerText();
        return text == null ? "" : text;
    }
    public boolean isActionVisible(String action) {
        Locator a = page.locator("[data-testid=action-" + action + "]");
        return a.count() > 0 && a.first().isVisible();
    }
    public boolean isRequestSignaturesVisible() {
        return isActionVisible("request-signatures");
    }
    public boolean isVoidVisible() {
        return isActionVisible("void");
    }
    public boolean isScanVisible() {
        return isActionVisible("scan");
    }
    public boolean isDeclineVisible() {
        return isActionVisible("decline");
    }
    public boolean isDownloadVisible() {
        return isActionVisible("download");
    }
    public int clickVoidAndConfirm() {
        Locator voidBtn = page.locator("[data-testid=action-void]");
        voidBtn.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        voidBtn.first().click();
        Locator confirm = page.locator("[data-testid=void-confirm-submit]");
        confirm.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        Response response = page.waitForResponse(
                r -> r.url().contains("/api/signable-documents/") && r.url().endsWith("/void")
                        && "POST".equalsIgnoreCase(r.request().method()),
                () -> confirm.first().click());
        return response.status();
    }
    public String downloadHref() {
        Locator a = page.locator("[data-testid=action-download]");
        if (a.count() == 0) {
            a = page.locator("[data-testid=document-detail-download]");
        }
        return a.count() == 0 ? null : a.first().getAttribute("href");
    }
    public int requestSignaturesByUserId(long signerUserId) {
        page.locator("[data-testid=action-request-signatures]").first().click();
        Locator uid = page.locator("[data-testid^='request-signatures-user-']");
        uid.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        uid.first().fill(String.valueOf(signerUserId));
        Locator submit = page.locator("[data-testid=request-signatures-submit]");
        Response response = page.waitForResponse(
                r -> r.url().endsWith("/request-signatures")
                        && "POST".equalsIgnoreCase(r.request().method()),
                () -> submit.first().click());
        return response.status();
    }
}
