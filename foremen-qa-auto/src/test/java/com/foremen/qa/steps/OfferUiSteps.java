package com.foremen.qa.steps;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.foremen.qa.fixtures.TestUser;
import com.foremen.qa.pages.AppShell;
import com.foremen.qa.pages.LoginPage;
import com.foremen.qa.pages.OtpLoginPage;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.JsonPath;
import com.foremen.qa.support.QaHttp.Resp;
import com.foremen.qa.support.TestConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * FOR-QA-AUTO-05-07B UI steps for the offers tab, the estimate tab, the prepare dialog, the snapshot
 * matrix, restore, PDF and the comparison screen.
 *
 * <p>Elements are addressed by their data-testid (placeholders such as {A} or {WORK_1} are resolved
 * through {@link OfferCtx}). The browser records every API request so a step can assert that a call
 * was or was not sent, and a step can make the next matching request fail to check error handling.
 * Data is prepared through the API steps that share the same {@link OfferCtx}.
 */
public class OfferUiSteps {

    private static final double WAIT_MS = 15_000;
    private static final double ABSENT_MS = 4_000;

    private final OfferCtx ctx;
    private final List<String[]> requests = new ArrayList<>();
    private final List<String> consoleErrors = new ArrayList<>();
    private boolean recording;
    private byte[] download;

    public OfferUiSteps(OfferCtx ctx) {
        this.ctx = ctx;
    }

    private Page page() {
        Page page = Hooks.currentPage();
        if (!recording) {
            page.onRequest(this::record);
            page.onConsoleMessage(m -> {
                // Network failures are asserted through the request log; only script errors count here.
                // The Google sign-in script logs an origin warning on localhost; it is third-party noise.
                if ("error".equals(m.type()) && !m.text().startsWith("Failed to load resource")
                        && !m.text().startsWith("[GSI_LOGGER]")) {
                    synchronized (consoleErrors) {
                        consoleErrors.add(m.text());
                    }
                }
            });
            recording = true;
        }
        return page;
    }

    private void record(Request r) {
        if (r.url().contains("/api/")) {
            String body = r.postData();
            synchronized (requests) {
                requests.add(new String[] {r.method(), r.url(), body == null ? "" : body});
            }
        }
    }

    private static String sel(String testId) {
        return "[data-testid='" + testId + "']";
    }

    private Locator el(String testId) {
        return page().locator(sel(ctx.resolve(testId)));
    }

    private Locator el(String testId, String scopeTestId) {
        return page().locator(sel(ctx.resolve(scopeTestId))).first().locator(sel(ctx.resolve(testId)));
    }

    private void settle() {
        try {
            page().waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(8_000));
        } catch (RuntimeException ignored) {
            // best effort: some screens keep a websocket open
        }
    }

    // ================= Sign-in =================

    private void resetBrowserSession() {
        Page page = page();
        page.navigate(TestConfig.frontendUrl() + "/login");
        page.evaluate("() => { try { localStorage.removeItem('foremen-access-token');"
                + " localStorage.removeItem('foremen-refresh-token');"
                + " localStorage.setItem('foremen-locale', 'ru'); } catch (e) {} }");
    }

    @Given("the admin is signed in to the browser")
    public void adminSignedIn() {
        resetBrowserSession();
        LoginPage login = new LoginPage(page());
        login.open();
        login.login(TestConfig.adminEmail(), TestConfig.adminPassword());
        page().waitForURL(url -> !url.contains(LoginPage.ROUTE));
        new AppShell(page()).waitUntilRendered();
    }

    @Given("the client is signed in to the browser")
    public void clientSignedIn() {
        otpSignIn(ctx.client);
    }

    @Given("the outsider is signed in to the browser")
    public void outsiderSignedIn() {
        otpSignIn(ctx.outsider);
    }

    private void otpSignIn(TestUser user) {
        assertThat(user).as("user to sign in").isNotNull();
        resetBrowserSession();
        OtpLoginPage otp = new OtpLoginPage(page());
        otp.open();
        String before = ctx.fx.latestOtp(user.email());
        otp.requestCodeFor(user.email());
        String code = null;
        for (int i = 0; i < 40; i++) {
            code = ctx.fx.latestOtp(user.email());
            if (code != null && !code.equals(before)) {
                break;
            }
            OfferCtx.sleep(250);
        }
        assertThat(code).as("OTP code for %s", user.email()).isNotNull();
        otp.enterCode(code);
        page().waitForURL(u -> !u.contains(OtpLoginPage.ROUTE));
        new AppShell(page()).waitUntilRendered();
    }

    // ================= Navigation =================

    @When("the user opens the offers tab")
    public void openOffersTab() {
        openPath("/projects/{PID}/pricing");
        el("offer-tab").first().waitFor(visible());
        settle();
    }

    @When("the user opens the offers tab of the second project")
    public void openOffersTabSecond() {
        openPath("/projects/{X_PID}/pricing");
        el("offer-tab").first().waitFor(visible());
        settle();
    }

    @When("the user opens the estimate tab")
    public void openEstimateTab() {
        openPath("/projects/{PID}/estimate");
        el("estimate-tab").first().waitFor(visible());
        settle();
    }

    @When("the user opens the path {string}")
    public void openPath(String path) {
        page().navigate(TestConfig.frontendUrl() + ctx.resolve(path));
        settle();
    }

    @When("the user reloads the page")
    public void reload() {
        page().reload();
        settle();
    }

    @When("the UI language is switched to {string}")
    public void switchLanguage(String lang) {
        page().evaluate("l => localStorage.setItem('foremen-locale', l)", lang);
        reload();
    }

    // ================= Actions =================

    @When("the user clicks {string}")
    public void click(String testId) {
        Locator l = el(testId).first();
        l.waitFor(visible());
        l.click();
        settle();
    }

    /** Click without waiting for the network to settle, so a short-lived toast is still on screen afterwards. */
    @When("the user clicks {string} in {string} and looks for a toast")
    public void clickInQuick(String testId, String scope) {
        Locator l = el(testId, scope).first();
        l.waitFor(visible());
        l.click();
    }

    @When("the user clicks {string} in {string}")
    public void clickIn(String testId, String scope) {
        Locator l = el(testId, scope).first();
        l.waitFor(visible());
        l.click();
        settle();
    }

    @When("the user types {string} into {string}")
    public void type(String text, String testId) {
        Locator l = el(testId).first();
        l.waitFor(visible());
        l.fill(ctx.resolve(text));
    }

    @When("the user presses {string}")
    public void press(String key) {
        page().keyboard().press(key);
        settle();
    }

    @When("the user downloads the PDF with {string}")
    public void downloadPdf(String testId) throws Exception {
        Download d = page().waitForDownload(() -> el(testId).first().click());
        download = Files.readAllBytes(d.path());
        settle();
    }

    @Given("the estimate has an unsaved quantity edit of work {string} in room {string} set to {string}")
    public void stageEdit(String work, String room, String quantity) {
        String key = "foremen.estimateMatrix." + ctx.var("PID");
        String value = "[{\"stagedId\":\"qa-" + ctx.var("RUN") + "\",\"payload\":{\"kind\":\"SET_QUANTITY\",\"workItemId\":"
                + ctx.var(work) + ",\"roomId\":" + ctx.var(room) + ",\"quantity\":" + quantity + "}}]";
        page().evaluate("([k, v]) => localStorage.setItem(k, v)", List.of(key, value));
    }

    @Then("the estimate has no unsaved edits")
    public void noStagedEdits() {
        Object v = page().evaluate("k => localStorage.getItem(k)", "foremen.estimateMatrix." + ctx.var("PID"));
        String s = v == null ? "" : v.toString();
        assertThat(s.isBlank() || "[]".equals(s.trim())).as("staged edits: %s", s).isTrue();
    }

    @Then("the estimate still has the unsaved edit")
    public void stagedEditKept() {
        Object v = page().evaluate("k => localStorage.getItem(k)", "foremen.estimateMatrix." + ctx.var("PID"));
        assertThat(v == null ? "" : v.toString()).as("staged edits").contains("qa-" + ctx.var("RUN"));
    }

    // ================= Network =================

    @When("the request log is cleared")
    public void clearLog() {
        page();
        synchronized (requests) {
            requests.clear();
        }
    }

    private List<String[]> matching(String method, String pathRegex) {
        Pattern p = Pattern.compile(ctx.resolve(pathRegex));
        List<String[]> out = new ArrayList<>();
        synchronized (requests) {
            for (String[] r : requests) {
                String path = r[1].replaceFirst("^https?://[^/]+", "");
                if (r[0].equalsIgnoreCase(method) && p.matcher(path).matches()) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    @Then("{int} {word} requests to {string} were sent")
    public void requestCount(int count, String method, String path) {
        settle();
        assertThat(matching(method, path)).as("%s %s requests", method, ctx.resolve(path)).hasSize(count);
    }

    @Then("no {word} request to {string} was sent")
    public void noRequest(String method, String path) {
        requestCount(0, method, path);
    }

    @Then("at least one {word} request to {string} was sent")
    public void someRequest(String method, String path) {
        settle();
        assertThat(matching(method, path)).as("%s %s requests", method, ctx.resolve(path)).isNotEmpty();
    }

    @Then("the last {word} request to {string} had the field {string} equal to {string}")
    public void lastRequestField(String method, String path, String field, String value) {
        List<String[]> m = matching(method, path);
        assertThat(m).as("%s %s requests", method, ctx.resolve(path)).isNotEmpty();
        String body = m.get(m.size() - 1)[2];
        JsonElement json = body.isBlank() ? null : JsonParser.parseString(body);
        JsonElement actual = json == null ? null : JsonPath.read(json, field);
        assertThat(JsonPath.valueEquals(actual, ctx.resolve(value)))
                .as("field %s of body %s", field, body).isTrue();
    }

    @Given("the next {word} request to {string} answers {int}")
    public void failNext(String method, String pathRegex, int status) {
        Pattern p = Pattern.compile(".*" + ctx.resolve(pathRegex) + "([?].*)?$");
        boolean[] used = {false};
        page().route(url -> p.matcher(url).matches(), route -> {
            if (!used[0] && route.request().method().equalsIgnoreCase(method)) {
                used[0] = true;
                route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setStatus(status)
                        .setContentType("application/json").setBody("{\"status\":" + status + ",\"code\":\"qa.injected\"}"));
            } else {
                route.resume();
            }
        });
    }

    // ================= Assertions =================

    private static Locator.WaitForOptions visible() {
        return new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(WAIT_MS);
    }

    private static void waitHidden(Locator l) {
        l.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(ABSENT_MS));
    }

    @Then("{string} is visible")
    public void isVisible(String testId) {
        el(testId).first().waitFor(visible());
    }

    @Then("{string} is visible in {string}")
    public void isVisibleIn(String testId, String scope) {
        el(testId, scope).first().waitFor(visible());
    }

    @Then("{string} is not shown")
    public void isAbsent(String testId) {
        settle();
        Locator l = el(testId);
        for (int i = 0; i < l.count(); i++) {
            waitHidden(l.nth(i));
        }
    }

    @Then("{string} is not shown in {string}")
    public void isAbsentIn(String testId, String scope) {
        settle();
        Locator l = el(testId, scope);
        for (int i = 0; i < l.count(); i++) {
            waitHidden(l.nth(i));
        }
    }

    @Then("{string} is present")
    public void isPresent(String testId) {
        el(testId).first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(WAIT_MS));
    }

    @Then("{string} contains the text {string}")
    public void containsText(String testId, String text) {
        Locator l = el(testId).first();
        l.waitFor(visible());
        String want = ctx.resolve(text);
        long end = System.currentTimeMillis() + (long) WAIT_MS;
        while (!l.innerText().contains(want) && System.currentTimeMillis() < end) {
            OfferCtx.sleep(200);
        }
        assertThat(l.innerText()).as("text of %s", ctx.resolve(testId)).contains(want);
    }

    @Then("{string} contains the text {string} in {string}")
    public void containsTextIn(String testId, String text, String scope) {
        Locator l = el(testId, scope).first();
        l.waitFor(visible());
        String want = ctx.resolve(text);
        long end = System.currentTimeMillis() + (long) WAIT_MS;
        while (!l.innerText().contains(want) && System.currentTimeMillis() < end) {
            OfferCtx.sleep(200);
        }
        assertThat(l.innerText()).as("text of %s in %s", ctx.resolve(testId), ctx.resolve(scope)).contains(want);
    }

    @Then("{string} does not contain the text {string}")
    public void notContainsText(String testId, String text) {
        Locator l = el(testId).first();
        l.waitFor(visible());
        assertThat(l.innerText().toLowerCase()).as("text of %s", ctx.resolve(testId))
                .doesNotContain(ctx.resolve(text).toLowerCase());
    }

    @Then("{string} matches the text pattern {string}")
    public void matchesText(String testId, String regex) {
        Locator l = el(testId).first();
        l.waitFor(visible());
        assertThat(l.innerText().trim()).matches(ctx.resolve(regex));
    }

    @Then("{string} matches the text pattern {string} in {string}")
    public void matchesTextIn(String testId, String regex, String scope) {
        Locator l = el(testId, scope).first();
        l.waitFor(visible());
        assertThat(l.innerText().trim()).matches(ctx.resolve(regex));
    }

    @Then("{string} is disabled")
    public void isDisabled(String testId) {
        Locator l = el(testId).first();
        l.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(WAIT_MS));
        assertThat(l.isDisabled()).as("%s disabled", ctx.resolve(testId)).isTrue();
    }

    @Then("{string} is enabled")
    public void isEnabled(String testId) {
        Locator l = el(testId).first();
        l.waitFor(visible());
        long end = System.currentTimeMillis() + (long) WAIT_MS;
        while (l.isDisabled() && System.currentTimeMillis() < end) {
            OfferCtx.sleep(200);
        }
        assertThat(l.isDisabled()).as("%s disabled", ctx.resolve(testId)).isFalse();
    }

    @Then("{string} is checked")
    public void isChecked(String testId) {
        assertThat(el(testId).first().isChecked()).as("%s checked", ctx.resolve(testId)).isTrue();
    }

    @Then("{string} shows the amount {string}")
    public void showsAmount(String testId, String amount) {
        Locator l = el(testId).first();
        l.waitFor(visible());
        String want = new BigDecimal(ctx.resolve(amount)).setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", "");
        long end = System.currentTimeMillis() + (long) WAIT_MS;
        while (!digits(l.innerText()).contains(want) && System.currentTimeMillis() < end) {
            OfferCtx.sleep(200);
        }
        assertThat(digits(l.innerText())).as("amount in %s (%s)", ctx.resolve(testId), l.innerText()).contains(want);
    }

    @Then("{string} shows the amount {string} in {string}")
    public void showsAmountIn(String testId, String amount, String scope) {
        Locator l = el(testId, scope).first();
        l.waitFor(visible());
        String want = new BigDecimal(ctx.resolve(amount)).setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", "");
        long end = System.currentTimeMillis() + (long) WAIT_MS;
        while (!digits(l.innerText()).contains(want) && System.currentTimeMillis() < end) {
            OfferCtx.sleep(200);
        }
        assertThat(digits(l.innerText())).as("amount in %s of %s (%s)", ctx.resolve(testId), ctx.resolve(scope), l.innerText())
                .contains(want);
    }

    private static String digits(String s) {
        return s.replaceAll("[^0-9]", "");
    }

    @Then("the attribute {string} of {string} is {string}")
    public void attribute(String attr, String testId, String value) {
        assertThat(el(testId).first().getAttribute(attr)).isEqualTo(ctx.resolve(value));
    }

    @Then("the value of {string} has {int} characters")
    public void valueLength(String testId, int length) {
        assertThat(el(testId).first().inputValue()).hasSize(length);
    }

    @Then("{int} elements match {string}")
    public void countMatching(int count, String testIdPrefix) {
        settle();
        // A value ending with '-' is a prefix (a family of elements); otherwise the test id is exact, so
        // that offer-card-12 does not also match offer-card-123.
        String id = ctx.resolve(testIdPrefix);
        Locator l = page().locator(id.endsWith("-") ? "[data-testid^='" + id + "']" : sel(id));
        assertThat(l.count()).as("elements %s*", ctx.resolve(testIdPrefix)).isEqualTo(count);
    }

    @Then("at least {int} elements match {string}")
    public void atLeastMatching(int count, String testIdPrefix) {
        settle();
        String id = ctx.resolve(testIdPrefix);
        Locator l = page().locator(id.endsWith("-") ? "[data-testid^='" + id + "']" : sel(id));
        assertThat(l.count()).as("elements %s", id).isGreaterThanOrEqualTo(count);
    }

    @Then("no element matches {string}")
    public void noneMatching(String testIdPrefix) {
        countMatching(0, testIdPrefix);
    }

    @Then("the URL path is {string}")
    public void urlPath(String path) {
        String want = ctx.resolve(path);
        // Navigation can follow a slow first request after a backend restart: allow twice the usual wait.
        long end = System.currentTimeMillis() + 2 * (long) WAIT_MS;
        while (!pathOf(page().url()).equals(want) && System.currentTimeMillis() < end) {
            OfferCtx.sleep(200);
        }
        assertThat(pathOf(page().url())).isEqualTo(want);
    }

    @Then("the URL contains {string}")
    public void urlContains(String part) {
        String want = ctx.resolve(part);
        long end = System.currentTimeMillis() + (long) WAIT_MS;
        while (!page().url().contains(want) && System.currentTimeMillis() < end) {
            OfferCtx.sleep(200);
        }
        assertThat(page().url()).contains(want);
    }

    private static String pathOf(String url) {
        return url.replaceFirst("^https?://[^/]+", "").replaceFirst("[?#].*$", "");
    }

    @Then("a toast is shown")
    public void toastShown() {
        page().locator("[data-sonner-toast]").first().waitFor(visible());
    }

    @Then("a toast containing {string} is shown")
    public void toastWith(String text) {
        Locator t = page().locator("[data-sonner-toast]").filter(new Locator.FilterOptions().setHasText(ctx.resolve(text)));
        t.first().waitFor(visible());
    }

    @Then("the downloaded file is a PDF")
    public void downloadedPdf() {
        assertThat(download).as("downloaded bytes").isNotNull();
        assertThat(new String(download, 0, Math.min(4, download.length))).isEqualTo("%PDF");
    }

    @Then("no dialog mentions withdrawing offers")
    public void noWithdrawWarning() {
        settle();
        Locator dialogs = page().locator("[role='dialog'], [role='alertdialog']");
        for (int i = 0; i < dialogs.count(); i++) {
            String text = dialogs.nth(i).innerText().toLowerCase();
            assertThat(text).doesNotContain("отозв").doesNotContain("wycof").doesNotContain("withdraw");
        }
    }

    // ================= History rows, read-only checks, package names =================

    /** The history row (offer-version-row) whose offer-version-name is exactly the given name. */
    private Locator historyRow(String name) {
        // Playwright hands the pattern to a JavaScript RegExp, which has no quote syntax: escape by hand.
        StringBuilder escaped = new StringBuilder();
        for (char c : ctx.resolve(name).toCharArray()) {
            if ("^$.|?*+()[]{}-\\".indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        Pattern exact = Pattern.compile("^" + escaped + "$");
        Locator nameEl = page().locator(sel("offer-version-name")).filter(new Locator.FilterOptions().setHasText(exact));
        Locator row = page().locator(sel("offer-version-row")).filter(new Locator.FilterOptions().setHas(nameEl)).first();
        row.waitFor(visible());
        return row;
    }

    @Then("{string} is visible in the history row named {string}")
    public void visibleInHistory(String testId, String name) {
        historyRow(name).locator(sel(ctx.resolve(testId))).first().waitFor(visible());
    }

    @Then("{string} is not shown in the history row named {string}")
    public void absentInHistory(String testId, String name) {
        Locator l = historyRow(name).locator(sel(ctx.resolve(testId)));
        for (int i = 0; i < l.count(); i++) {
            waitHidden(l.nth(i));
        }
    }

    @Then("the history row named {string} contains the text {string}")
    public void historyRowText(String name, String text) {
        assertThat(historyRow(name).innerText()).contains(ctx.resolve(text));
    }

    @When("the user clicks {string} in the history row named {string}")
    public void clickInHistory(String testId, String name) {
        Locator l = historyRow(name).locator(sel(ctx.resolve(testId))).first();
        l.waitFor(visible());
        l.click();
        settle();
    }

    /** Expand a collapsible header (aria-expanded) only when it is collapsed, so the step is idempotent. */
    @When("the group {string} is expanded")
    public void expandGroup(String testId) {
        Locator l = el(testId).first();
        l.waitFor(visible());
        if ("false".equals(l.getAttribute("aria-expanded"))) {
            l.click();
            settle();
        }
    }

    @When("the group {string} is toggled")
    public void toggleGroup(String testId) {
        Locator l = el(testId).first();
        l.waitFor(visible());
        l.click();
        settle();
    }

    @When("the group {string} is expanded in {string}")
    public void expandGroupIn(String testId, String scope) {
        Locator l = el(testId, scope).first();
        l.waitFor(visible());
        if ("false".equals(l.getAttribute("aria-expanded"))) {
            l.click();
            settle();
        }
    }

    /** Fire the click event on the element itself (for cells inside a scroll container that hangs a pointer click). */
    @When("the user triggers a click on {string}")
    public void dispatchClick(String testId) {
        Locator l = el(testId).first();
        l.waitFor(visible());
        l.dispatchEvent("click");
        settle();
    }

    @When("the user force-clicks {string} in {string}")
    public void forceClickIn(String testId, String scope) {
        Locator l = el(testId, scope).first();
        l.waitFor(visible());
        l.click(new Locator.ClickOptions().setForce(true));
        settle();
    }

    @Then("no visible element in {string} matches {string}")
    public void noVisiblePrefixIn(String scope, String prefix) {
        settle();
        Locator l = page().locator(sel(ctx.resolve(scope))).first().locator("[data-testid^='" + ctx.resolve(prefix) + "']");
        for (int i = 0; i < l.count(); i++) {
            assertThat(l.nth(i).isVisible()).as("%s is visible in %s", l.nth(i).getAttribute("data-testid"), ctx.resolve(scope)).isFalse();
        }
    }

    @Then("no input field is shown in {string}")
    public void noInputsIn(String scope) {
        settle();
        Locator l = page().locator(sel(ctx.resolve(scope))).first().locator("input:not([type='checkbox']), textarea, select, [contenteditable='true']");
        int shown = 0;
        for (int i = 0; i < l.count(); i++) {
            if (l.nth(i).isVisible()) {
                shown++;
            }
        }
        assertThat(shown).as("visible input fields in %s", ctx.resolve(scope)).isZero();
    }

    @Then("{string} shows the {word} name of the applied package in {string}")
    public void packageNameIn(String testId, String lang, String scope) {
        String want = ctx.fx.packageName(ctx.var("PKG"), lang);
        Locator l = el(testId, scope).first();
        l.waitFor(visible());
        long end = System.currentTimeMillis() + (long) WAIT_MS;
        while (!l.innerText().contains(want) && System.currentTimeMillis() < end) {
            OfferCtx.sleep(200);
        }
        assertThat(l.innerText()).as("package marker in %s", ctx.resolve(scope)).contains(want);
    }

    @Then("{string} shows the {word} name of the applied package")
    public void packageName(String testId, String lang) {
        String want = ctx.fx.packageName(ctx.var("PKG"), lang);
        containsText(testId, want);
    }

    @Then("the browser console has no errors")
    public void noConsoleErrors() {
        settle();
        synchronized (consoleErrors) {
            assertThat(consoleErrors).as("browser console errors").isEmpty();
        }
    }

    @When("the user waits for the offers list to refresh")
    public void waitForList() {
        settle();
        OfferCtx.sleep(500);
        settle();
    }

    // ================= Data lookups =================

    @When("the offer named {string} is remembered as {string}")
    public void rememberByName(String name, String alias) {
        String want = ctx.resolve(name);
        Resp r = null;
        // The offer may still be on its way (save, then prepare): poll the list for a while.
        for (int attempt = 0; attempt < 40; attempt++) {
            r = ctx.http().get(ctx.resolve("/api/offers/project/{PID}/list"), ctx.token("admin"));
            for (JsonElement e : r.json().getAsJsonArray()) {
                if (want.equals(JsonPath.show(e.getAsJsonObject().get("name")))) {
                    ctx.vars.put(alias, e.getAsJsonObject().get("id").getAsString());
                    return;
                }
            }
            OfferCtx.sleep(250);
        }
        throw new AssertionError("No offer named " + want + " in " + r.abbreviate());
    }

    @When("the newest offer is remembered as {string}")
    public void rememberNewest(String alias) {
        Resp r = ctx.http().get(ctx.resolve("/api/offers/project/{PID}/list"), ctx.token("admin"));
        long max = 0;
        for (JsonElement e : r.json().getAsJsonArray()) {
            max = Math.max(max, e.getAsJsonObject().get("id").getAsLong());
        }
        assertThat(max).as("newest offer").isPositive();
        ctx.vars.put(alias, String.valueOf(max));
    }
}
