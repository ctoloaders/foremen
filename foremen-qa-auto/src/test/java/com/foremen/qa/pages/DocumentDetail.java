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
        // The loading branch also renders the `document-detail` container; wait for the loaded state
        // (the status badge appears only once the document query resolves) so callers never assert
        // against the transient spinner. This matters since opening a document now navigates to a new
        // URL (deep link), remounting the detail with a fresh data fetch rather than reusing cache.
        page.locator("[data-testid=document-detail-status]").first()
                .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
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
    /**
     * Expands the collapsible DRAFT body preview and waits until it is visible. Idempotent: clicks
     * the toggle only when the preview is not already expanded (keyed off the toggle's
     * {@code aria-expanded}), then waits for the preview container to render.
     */
    public void openBodyPreview() {
        Locator toggle = page.locator("[data-testid=document-detail-preview-toggle]");
        toggle.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        if (!"true".equals(toggle.first().getAttribute("aria-expanded"))) {
            toggle.first().click();
        }
        page.locator("[data-testid=document-detail-preview]").first()
                .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
    }
    public boolean isBodyPreviewShown() {
        Locator preview = page.locator("[data-testid=document-detail-preview]");
        return preview.count() > 0 && preview.first().isVisible();
    }
    public String bodyPreviewText() {
        Locator preview = page.locator("[data-testid=document-detail-preview]");
        if (preview.count() == 0) {
            return "";
        }
        String text = preview.first().innerText();
        return text == null ? "" : text;
    }
    public boolean isFormFieldsPanelShown() {
        Locator panel = page.locator("[data-testid=form-fields-panel]");
        return panel.count() > 0 && panel.first().isVisible();
    }
    public boolean isFormFieldsEmpty() {
        return page.locator("[data-testid=form-fields-empty]").count() > 0;
    }
    public boolean hasFormFieldInput(String key) {
        return page.locator("[data-testid=form-field-input-" + key + "]").count() > 0;
    }
    /** Types a value into a declared form field and clicks save, returning the PUT HTTP status. */
    public int fillFormFieldAndSave(String key, String value) {
        Locator input = page.locator("[data-testid=form-field-input-" + key + "]");
        input.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        input.first().fill(value);
        Locator save = page.locator("[data-testid=form-fields-save]");
        save.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        Response response = page.waitForResponse(
                r -> r.url().endsWith("/form-fields") && "PUT".equalsIgnoreCase(r.request().method()),
                () -> save.first().click());
        return response.status();
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
