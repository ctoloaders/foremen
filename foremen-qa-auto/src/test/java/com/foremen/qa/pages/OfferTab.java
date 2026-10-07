package com.foremen.qa.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the offer surface rendered on the workspace {@code pricing} tab
 * (frontend {@code OfferTab.tsx} / {@code OfferSummaryHeader.tsx} / {@code ClientMaterialSelection.tsx},
 * FOR-05-07). Reached at {@code /projects/{projectId}/pricing} via
 * {@link ProjectWorkspacePage#workspaceTabUrl(long, String)}.
 *
 * <p>All locators key off the confirmed {@code data-testid}s. Lifecycle actions are role-gated by the
 * backend/frontend (send is executor-only on DRAFT; approve/reject are client-only while the offer is
 * {@code ON_APPROVAL}; the manager responder and the client round responder appear only when an open
 * negotiation round exists), so a step must drive them in the correct identity/sequence. Where a round
 * id is dynamic, the FIRST element whose testid starts with the prefix is used
 * ({@code [data-testid^=round-manage-]} etc.). This object holds NO assertions.
 */
public final class OfferTab {

    private final Page page;

    public OfferTab(Page page) {
        this.page = page;
    }

    // ---- Navigation ----

    /** Navigate to the offer surface at {@code /projects/{projectId}/pricing}. */
    public OfferTab open(long projectId) {
        page.navigate(ProjectWorkspacePage.workspaceTabUrl(projectId, "pricing"));
        return this;
    }

    // ---- Structure ----

    /** The offer tab container. */
    public Locator container() {
        return testId("offer-tab");
    }

    /** Wait until the offer tab container has rendered. */
    public OfferTab waitUntilRendered() {
        container().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        // Let in-flight data loads settle so the per-step report screenshot captures a fully-rendered
        // page rather than a mid-load one. Best-effort and bounded: swallow a timeout so a page that
        // keeps a long-poll / websocket open can never hang the step.
        settleNetwork();
        return this;
    }

    /** Wait for network idle, bounded and non-throwing (screenshot-quality aid only). */
    private void settleNetwork() {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE);
        } catch (RuntimeException ignored) {
            // Best-effort: proceed if idle can't be reached within the default timeout.
        }
        // NETWORKIDLE alone is not enough: the offer summary header / finishing rows / matrix render
        // from React-Query data that paints skeleton (.animate-pulse) placeholders until the query
        // resolves AND re-renders. Wait for those skeletons to clear so the per-step report
        // screenshot captures the real offer content. Bounded + swallow timeout so it can't hang.
        try {
            page.waitForFunction(
                    "() => document.querySelectorAll('.animate-pulse').length === 0",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(5000));
        } catch (RuntimeException ignored) {
            // Best-effort.
        }
    }

    /** {@code true} once the offer tab container is visible. */
    public boolean isRendered() {
        return container().count() > 0 && container().first().isVisible();
    }

    /** The offer summary header (status / revision / package / totals). */
    public Locator summary() {
        return testId("offer-summary");
    }

    /** The terminal-state hint, shown only once the offer reaches a terminal status. */
    public Locator terminalHint() {
        return testId("offer-terminal-hint");
    }

    /** Trimmed localized status text from the summary status chip ({@code offer-status}). */
    public String statusText() {
        Locator status = testId("offer-status");
        if (status.count() == 0) {
            return "";
        }
        String text = status.first().innerText();
        return text == null ? "" : text.trim();
    }

    // ---- Lifecycle actions ----

    /** Click the executor "prepare offer" action (shown only in the empty state for an executor). */
    public void clickPrepare() {
        click("offer-prepare");
    }

    /** Click the executor "send" action (shown only on a DRAFT offer for an executor). */
    public void clickSend() {
        click("offer-send");
    }

    /**
     * Click the client "approve" action (shown only while ON_APPROVAL for a client with APPROVE),
     * waiting for the {@code POST .../approve} response so the mutation is confirmed before the
     * caller re-opens the tab to observe the terminal state. Returns the captured HTTP status.
     */
    public int clickApprove() {
        return clickAwaitingResponse("offer-approve", "/approve");
    }

    /**
     * Click the client "reject" action (shown only while ON_APPROVAL for a client with APPROVE),
     * waiting for the {@code POST .../reject} response so the mutation is confirmed before the
     * caller re-opens the tab to observe the terminal state. Returns the captured HTTP status.
     */
    public int clickReject() {
        return clickAwaitingResponse("offer-reject", "/reject");
    }

    /**
     * Click a lifecycle action by testid and synchronously capture the matching offer-mutation HTTP
     * response (matched by a URL suffix under {@code /api/offers}). The returned status lets a step
     * distinguish a real backend rejection (non-2xx) from a UI refetch-visibility lag.
     */
    private int clickAwaitingResponse(String testid, String urlSuffix) {
        Locator target = testId(testid);
        target.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        Response response = page.waitForResponse(
                r -> r.url().contains("/api/offers/") && r.url().endsWith(urlSuffix)
                        && "POST".equalsIgnoreCase(r.request().method()),
                () -> target.first().click());
        return response.status();
    }

    // ---- Finishing material line counters (ClientMaterialSelection.tsx) ----

    /**
     * Count of UNCHOSEN package finishing lines visible on the offer tab — rows that render the
     * {@code finishing-placeholder-{lineId}} badge. The offer tab renders only {@code
     * appliedFromPackage} finishing lines, so this counts the package-sourced placeholders.
     */
    public int placeholderCount() {
        return page.locator("[data-testid^='finishing-placeholder-']").count();
    }

    /** Count of CHOSEN package finishing lines ({@code finishing-chosen-{lineId}} badge). */
    public int chosenCount() {
        return page.locator("[data-testid^='finishing-chosen-']").count();
    }

    /**
     * The line id of the FIRST unchosen (Placeholder) finishing line on the offer tab, parsed from
     * its {@code finishing-placeholder-{lineId}} testid, or {@code null} when none is present. Used by
     * a client product-choice step to target a concrete placeholder line id dynamically.
     */
    public Long firstPlaceholderLineId() {
        Locator placeholders = page.locator("[data-testid^='finishing-placeholder-']");
        if (placeholders.count() == 0) {
            return null;
        }
        String testId = placeholders.first().getAttribute("data-testid");
        return parseLineId(testId, "finishing-placeholder-");
    }

    /**
     * All placeholder line ids currently rendered, in DOM order, parsed from the
     * {@code finishing-placeholder-{lineId}} testids. Lets a step pick a SECOND placeholder distinct
     * from the first one it already filled.
     */
    public java.util.List<Long> placeholderLineIds() {
        Locator placeholders = page.locator("[data-testid^='finishing-placeholder-']");
        int count = placeholders.count();
        java.util.List<Long> ids = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Long id = parseLineId(placeholders.nth(i).getAttribute("data-testid"),
                    "finishing-placeholder-");
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** {@code true} when the given line renders the {@code finishing-placeholder-{lineId}} badge. */
    public boolean lineIsPlaceholder(long lineId) {
        return testId("finishing-placeholder-" + lineId).count() > 0;
    }

    /** {@code true} when the given line renders the {@code finishing-chosen-{lineId}} badge. */
    public boolean lineIsChosen(long lineId) {
        return testId("finishing-chosen-" + lineId).count() > 0;
    }

    /**
     * Switch the per-line package on the Placeholder line {@code lineId}: open its shadcn
     * {@code Select} (trigger {@code finishing-package-select-{lineId}}) and click the option at the
     * given zero-based index among the rendered package options (0 is usually the offer's current
     * package). The options are {@code [role=option]} rows in the Radix listbox; picking a non-current
     * index selects a DIFFERENT package, so the subsequent product pick is sourced from that package.
     * If the Select has fewer than {@code optionIndex+1} options the FIRST option is clicked
     * (best-effort — the picker is still driven and the choose-concrete assertion carries the proof).
     *
     * @return {@code true} if a different (non-zero-index) option was selected, {@code false} if it
     *         fell back to the only/first option (so a step can note no alternate package existed)
     */
    public boolean selectLinePackageByIndex(long lineId, int optionIndex) {
        Locator trigger = testId("finishing-package-select-" + lineId);
        trigger.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        trigger.first().click();
        // Radix renders the open listbox options as [role=option]; wait for at least one.
        Locator options = page.locator("[role=option]");
        options.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        int count = options.count();
        int index = optionIndex < count ? optionIndex : 0;
        options.nth(index).click();
        return index == optionIndex && optionIndex > 0;
    }

    /**
     * Pick the FIRST concrete product for the Placeholder line {@code lineId} and save, returning the
     * {@code POST .../choose-concrete} HTTP status so a step can assert the client is no longer 403.
     *
     * <p>Drives the per-line {@link com.microsoft.playwright.Locator} chain:
     * {@code finishing-picker-{lineId}} wraps an {@code AsyncEntitySelect} whose trigger is a
     * {@code [role=combobox]} button; clicking it opens a popover that lazily loads option rows
     * ({@code [role=option]}). The method waits (bounded) for the first selectable option row to
     * appear, clicks it (staging the choice), then clicks the global {@code offer-save-finishing}
     * button while synchronously capturing the matching {@code choose-concrete} POST response. The
     * finishing endpoint returns the package-filtered products, so at least one option exists.
     */
    public int pickFirstProductAndSave(long lineId) {
        Locator picker = testId("finishing-picker-" + lineId);
        picker.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        Locator combobox = picker.locator("[role=combobox]");
        combobox.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        combobox.first().click();
        // The popover lazily loads option rows ([role=option]); wait (bounded) for the first one, then
        // pick it. A longer timeout absorbs the async options query on a cold cache.
        Locator options = page.locator("[role=option]");
        options.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(15_000));
        options.first().click();
        // Picking stages the choice; the save button becomes enabled. Click it and capture the
        // choose-concrete POST so the step can distinguish a real 2xx from a 403 (the whole point).
        Locator save = testId("offer-save-finishing");
        save.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        Response response = page.waitForResponse(
                r -> r.url().endsWith("/choose-concrete")
                        && "POST".equalsIgnoreCase(r.request().method()),
                () -> save.first().click());
        return response.status();
    }

    /** Parse the trailing numeric line id from a {@code {prefix}{lineId}} testid; {@code null} if absent. */
    private static Long parseLineId(String testId, String prefix) {
        if (testId == null || !testId.startsWith(prefix)) {
            return null;
        }
        try {
            return Long.parseLong(testId.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code true} once the terminal-state hint is visible (offer approved / rejected / withdrawn). */
    public boolean isTerminalHintShown() {
        terminalHint().first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        return terminalHint().count() > 0 && terminalHint().first().isVisible();
    }

    // ---- Discount request (client) ----

    /**
     * Open the per-line discount request on the FIRST finishing material line, fill the justification,
     * and submit. The request affordance toggles into a form carrying a justification input + submit,
     * both scoped to the same dynamic line id — so the first {@code finishing-request-*} button is
     * clicked, then the matching {@code finishing-justification-*} / {@code finishing-request-submit-*}
     * controls (also located by their shared prefix) are driven.
     */
    public int openDiscountRequestForFirstLine(String justification) {
        // The toggle testid is `finishing-request-{lineId}`; the submit is
        // `finishing-request-submit-{lineId}` and shares the same prefix. Match the TOGGLE precisely
        // (prefix match that explicitly excludes the submit) so `.first()` can never resolve to a
        // submit button of some other line.
        Locator toggle = page.locator(
                "[data-testid^='finishing-request-']:not([data-testid*='submit'])");
        toggle.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        toggle.first().click();
        // The toggle swaps itself for the justification input + submit (same dynamic line id).
        Locator justificationInput = page.locator("[data-testid^='finishing-justification-']");
        justificationInput.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        justificationInput.first().fill(justification);
        Locator submit = page.locator("[data-testid^='finishing-request-submit-']");
        submit.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        // Capture the discount-request response so a step can detect a non-2xx (e.g. the backend
        // rejecting the LINE-scoped targetId) rather than silently proceeding with no round created.
        // The backend OfferNegotiationController maps this to `/{offerId}/rounds/discount-request`.
        Response response = page.waitForResponse(
                r -> r.url().contains("/api/offers/")
                        && r.url().endsWith("/rounds/discount-request")
                        && "POST".equalsIgnoreCase(r.request().method()),
                () -> submit.first().click());
        return response.status();
    }

    // ---- Manager (executor) round response ----

    /**
     * As the executor, respond to the FIRST open client discount request with a manager proposal:
     * choose the kind, enter the value, and submit. The responder's controls carry the dynamic round
     * id, so each is located by its testid prefix on the first open responder.
     *
     * @param kind  the proposal kind option value ({@code PERCENT} or {@code ABSOLUTE})
     * @param value the numeric proposal value
     */
    public int managerProposeFirstOpenRound(String kind, String value) {
        Locator responder = page.locator("[data-testid^='round-manage-']");
        responder.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        // The proposal kind is a shadcn Select; selecting via its underlying trigger + option is
        // brittle, so set the value by opening the trigger and clicking the option by its visible
        // text is avoided — instead rely on the default kind (PERCENT) when it matches, otherwise
        // open the kind select. Keep it robust: only interact with the kind select when a non-default
        // kind is requested.
        if (!"PERCENT".equalsIgnoreCase(kind)) {
            page.locator("[data-testid^='propose-kind-']").first().click();
            // The listbox renders the ABSOLUTE/PERCENT options; click the one matching the kind.
            page.getByText(kind, new Page.GetByTextOptions().setExact(false)).last().click();
        }
        page.locator("[data-testid^='propose-value-']").first().fill(value);
        // Capture the propose response (POST /rounds/{roundId}/propose) so a step can detect a non-2xx
        // backend rejection rather than silently proceeding with no MANAGER_PROPOSAL round recorded
        // (which would surface only later as the client not seeing an open proposal to respond to).
        Locator submit = page.locator("[data-testid^='propose-submit-']");
        Response response = page.waitForResponse(
                r -> r.url().contains("/api/offers/rounds/") && r.url().endsWith("/propose")
                        && "POST".equalsIgnoreCase(r.request().method()),
                () -> submit.first().click());
        return response.status();
    }

    // ---- Client round response ----

    /** As the client, accept the manager's proposal on the FIRST open responder round. */
    public void clientAcceptFirstOpenRound() {
        Locator respond = page.locator("[data-testid^='round-respond-']");
        respond.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        page.locator("[data-testid^='round-accept-']").first().click();
    }

    /** As the client, decline the manager's proposal on the FIRST open responder round. */
    public void clientDeclineFirstOpenRound() {
        Locator respond = page.locator("[data-testid^='round-respond-']");
        respond.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        page.locator("[data-testid^='round-decline-']").first().click();
    }

    // ---- Negotiation state helpers ----

    /** {@code true} when an open manager-proposal responder round is present (client view). */
    public boolean hasOpenClientRespondRound() {
        return page.locator("[data-testid^='round-respond-']").count() > 0;
    }

    /** {@code true} when an open client-request responder round is present (executor view). */
    public boolean hasOpenManagerManageRound() {
        return page.locator("[data-testid^='round-manage-']").count() > 0;
    }

    // ---- Internals ----

    private Locator testId(String id) {
        return page.locator("[data-testid=" + id + "]");
    }

    private void click(String id) {
        Locator target = testId(id);
        target.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        target.first().click();
    }
}
