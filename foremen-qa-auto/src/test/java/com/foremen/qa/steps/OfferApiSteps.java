package com.foremen.qa.steps;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import com.foremen.qa.support.JsonPath;
import com.foremen.qa.support.QaHttp;
import com.foremen.qa.support.QaHttp.Resp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * FOR-QA-AUTO-05-07B API steps for the FOR-05-07b offer-snapshot cases.
 *
 * <p>A small HTTP vocabulary (who calls what, what the response must contain) plus domain steps that
 * build the estimate and offers the cases need. Paths, bodies and expected values may carry
 * curly-brace placeholders resolved by {@link OfferCtx#resolve(String)}. Repeatability comes from
 * {@link OfferCtx}: run-unique project, users and offer names, removed by registered teardown.
 */
public class OfferApiSteps {

    private final OfferCtx ctx;
    private List<Resp> race = new ArrayList<>();

    public OfferApiSteps(OfferCtx ctx) {
        this.ctx = ctx;
    }

    // ================= Setup =================

    @Given("an offers project with a client and rooms A, B and C exists")
    public void projectWithClientAndRooms() {
        ctx.client = ctx.newClient();
        ctx.createProject("", List.of(ctx.client));
    }

    @Given("the offers estimate has two works in rooms and one work in no room")
    public void estimateIsFilled() {
        ctx.fillEstimate("");
    }

    @Given("a second offers project with another client and a filled estimate exists")
    public void secondProject() {
        ctx.outsider = ctx.newClient();
        ctx.createProject("X_", List.of(ctx.outsider));
        ctx.fillEstimate("X_");
    }

    @Given("the first offer package is applied to the estimate")
    public void packageApplied() {
        String code = ctx.fx.firstPackageCode();
        ctx.vars.put("PKG", code);
        ctx.vars.put("PKG_NAME_RU", ctx.fx.packageName(code, "ru"));
        ctx.vars.put("PKG_NAME_PL", ctx.fx.packageName(code, "pl"));
        ctx.fx.setAppliedPackage(ctx.id("EID"), code);
    }

    @Given("no package is applied to the estimate of the second project")
    public void noPackageOnSecond() {
        ctx.fx.setAppliedPackage(ctx.id("X_EID"), null);
    }

    @Given("a legacy offer {string} without a snapshot in status {string} exists")
    public void legacyOffer(String alias, String status) {
        ctx.vars.put(alias, String.valueOf(ctx.fx.insertLegacyOffer(ctx.id("PID"), ctx.id("EID"), status)));
    }

    @Given("an offer {string} with a version 1 snapshot in status {string} exists")
    public void v1Offer(String alias, String status) {
        ctx.vars.put(alias, String.valueOf(ctx.fx.insertV1Offer(ctx.id("PID"), ctx.id("EID"), status)));
    }

    @Given("an offer {string} with a version 1 snapshot holding a row without rooms in status {string} exists")
    public void v1OfferWithEmptyRow(String alias, String status) {
        long id = ctx.fx.insertV1OfferWithEmptyRow(ctx.id("PID"), ctx.id("EID"), status, ctx.id("ROOM_A"),
                ctx.var("ROOM_A_LABEL"), ctx.id("CAT_1"), ctx.id("WORK_1"), ctx.id("WORK_3"));
        ctx.vars.put(alias, String.valueOf(id));
    }

    @When("the estimate is locked")
    public void estimateLocked() {
        ctx.fx.setEstimateStatus(ctx.id("EID"), "PRICED");
    }

    @When("the estimate is unlocked")
    public void estimateUnlocked() {
        ctx.fx.setEstimateStatus(ctx.id("EID"), "DRAFT");
    }

    @When("the live estimate net is remembered as {string}")
    public void liveNetRemembered(String name) {
        ctx.vars.put(name, ctx.estimateNet(""));
    }

    @When("a fourth work is added to the estimate in room {string} with quantity {string}")
    public void fourthWork(String room, String quantity) {
        long[] work = ctx.fx.workItemsInDistinctCategories(4).get(3);
        long line = ctx.addLine(ctx.id("EID"), work, 4);
        ctx.vars.put("LINE_4", String.valueOf(line));
        ctx.vars.put("WORK_4", String.valueOf(work[0]));
        ctx.vars.put("CAT_4", String.valueOf(work[2]));
        ctx.vars.put("CATKEY_4", ctx.fx.categoryKey(work[2]));
        ctx.vars.put("QTY_4", String.valueOf(ctx.addQuantity(line, ctx.id(room), quantity)));
    }

    // ================= Calls =================

    @When("the {word} calls {word} {string}")
    public void call(String actor, String method, String path) {
        ctx.call(actor, method, path, null);
    }

    @When("the {word} calls {word} {string} with body {string}")
    public void callWithBody(String actor, String method, String path, String body) {
        ctx.call(actor, method, path, body);
    }

    @When("the {word} calls {word} {string} with language {string}")
    public void callWithLanguage(String actor, String method, String path, String lang) {
        ctx.callLang(actor, method, path, lang);
    }

    @When("the {word} successfully calls {word} {string}")
    public void callOk(String actor, String method, String path) {
        assertOk(ctx.call(actor, method, path, null), method, path, actor);
    }

    @When("the {word} successfully calls {word} {string} with body {string}")
    public void callWithBodyOk(String actor, String method, String path, String body) {
        assertOk(ctx.call(actor, method, path, body), method, path, actor);
    }

    private void assertOk(Resp r, String method, String path, String actor) {
        assertThat(r.status()).as("%s %s as %s -> %s", method, ctx.resolve(path), actor, r.abbreviate())
                .isBetween(200, 299);
    }

    @When("the admin prepares the offer {string}")
    public void prepare(String alias) {
        prepareNamed(alias, "Offer-{RUN}-" + alias);
    }

    @When("the admin prepares the offer {string} named {string}")
    public void prepareNamed(String alias, String name) {
        prepareIn(alias, "PID", "{\"name\":\"" + name + "\"}");
    }

    @When("the admin prepares the offer {string} in the second project")
    public void prepareInSecond(String alias) {
        prepareIn(alias, "X_PID", "{\"name\":\"Offer-{RUN}-" + alias + "\"}");
    }

    private void prepareIn(String alias, String pidVar, String body) {
        Resp r = ctx.call("admin", "POST", "/api/offers/project/{" + pidVar + "}/prepare", body);
        assertThat(r.status()).as("prepare %s -> %s", alias, r.abbreviate()).isEqualTo(200);
        ctx.vars.put(alias, String.valueOf(r.obj().getAsJsonObject("offer").get("offerId").getAsLong()));
    }

    @When("the admin sends the offer {string}")
    public void send(String alias) {
        callOk("admin", "POST", "/api/offers/{" + alias + "}/send");
    }

    @When("the admin prepares and sends the offer {string}")
    public void prepareAndSend(String alias) {
        prepare(alias);
        send(alias);
    }

    @When("the client approves the offers {string} and {string} at the same time")
    public void approveConcurrently(String first, String second) throws Exception {
        String token = ctx.token("client");
        String p1 = ctx.resolve("/api/offers/{" + first + "}/approve");
        String p2 = ctx.resolve("/api/offers/{" + second + "}/approve");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Resp> f1 = pool.submit(() -> separateCall(token, p1));
            Future<Resp> f2 = pool.submit(() -> separateCall(token, p2));
            race = new ArrayList<>(List.of(f1.get(), f2.get()));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Resp separateCall(String token, String path) {
        try (QaHttp own = QaHttp.create()) {
            return own.post(path, token, null);
        }
    }

    // ================= Remembering =================

    @When("the response field {string} is remembered as {string}")
    public void rememberField(String path, String name) {
        JsonElement e = JsonPath.read(ctx.last.json(), ctx.resolve(path));
        assertThat(e.isJsonNull()).as("field %s in %s", path, ctx.last.abbreviate()).isFalse();
        ctx.vars.put(name, e.getAsString());
    }

    @When("the response body is remembered as {string}")
    public void rememberBody(String name) {
        ctx.remembered.put(name, ctx.last.text());
    }

    @When("the state of the offer {string} is remembered")
    public void rememberState(String alias) {
        ctx.remembered.put("STATE_" + alias, offerState(alias));
    }

    @Then("the state of the offer {string} is unchanged")
    public void stateUnchanged(String alias) {
        assertThat(offerState(alias)).as("status, revision, totals, inactive flag and snapshot of %s", alias)
                .isEqualTo(ctx.remembered.get("STATE_" + alias));
    }

    @When("the state of the offers {string} is remembered")
    public void rememberStates(String aliases) {
        for (String alias : aliases.split(",")) {
            rememberState(alias.trim());
        }
    }

    @Then("the state of the offers {string} is unchanged")
    public void statesUnchanged(String aliases) {
        for (String alias : aliases.split(",")) {
            stateUnchanged(alias.trim());
        }
    }

    @Then("the response field {string} matches {string}")
    public void fieldMatches(String path, String regex) {
        String actual = JsonPath.show(JsonPath.read(ctx.last.json(), ctx.resolve(path)));
        assertThat(actual).as("field %s", path).matches(ctx.resolve(regex));
    }

    /** Status, revision, totals, inactive flag and the snapshot body of an offer, as seen by the admin. */
    private String offerState(String alias) {
        Resp offer = ctx.http().get(ctx.resolve("/api/offers/{" + alias + "}"), ctx.token("admin"));
        JsonObject o = offer.obj().getAsJsonObject("offer");
        Resp snap = ctx.http().get(ctx.resolve("/api/offers/{" + alias + "}/snapshot"), ctx.token("admin"));
        return o.get("status") + "|" + o.get("revision") + "|" + o.get("totalNet") + "|" + o.get("totalVat")
                + "|" + o.get("totalGross") + "|" + o.get("inactive") + "|" + snap.status() + "|" + snap.text();
    }

    @When("a room {string} is added to the project")
    public void roomAdded(String letter) {
        ctx.addRoom(letter);
    }

    @When("the value {string} times {string} is remembered as {string}")
    public void rememberProduct(String left, String right, String name) {
        BigDecimal v = new BigDecimal(ctx.resolve(left)).multiply(new BigDecimal(ctx.resolve(right)))
                .setScale(2, RoundingMode.HALF_UP);
        ctx.vars.put(name, v.toPlainString());
    }

    /** v minus the discount amount, where the discount amount is rounded to the grosz first (backend rule). */
    @When("the value {string} reduced by {int} percent is remembered as {string}")
    public void rememberDiscounted(String value, int pct, String name) {
        BigDecimal v = new BigDecimal(ctx.resolve(value));
        BigDecimal discount = v.multiply(BigDecimal.valueOf(pct)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        ctx.vars.put(name, v.subtract(discount).toPlainString());
    }

    @Given("the reference project {int} is present, otherwise the case is skipped")
    public void referenceProject(int projectId) {
        Resp r = ctx.http().get("/api/projects/" + projectId, ctx.token("admin"));
        if (r.status() != 200) {
            throw new org.opentest4j.TestAbortedException("reference project " + projectId
                    + " is not in this database (HTTP " + r.status() + ")");
        }
        ctx.vars.put("REF_PID", String.valueOf(projectId));
    }

    @Then("every listed offer without a snapshot answers 404 on snapshot and PDF")
    public void legacyOffersOfList() {
        JsonArray list = ctx.last.json().getAsJsonArray();
        for (JsonElement e : list) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("hasSnapshot").getAsBoolean()) {
                continue;
            }
            long id = o.get("id").getAsLong();
            assertThat(ctx.http().get("/api/offers/" + id + "/snapshot", ctx.token("admin")).status())
                    .as("snapshot of legacy offer %s", id).isEqualTo(404);
            assertThat(ctx.http().get("/api/offers/" + id + "/pdf", ctx.token("admin")).status())
                    .as("PDF of legacy offer %s", id).isEqualTo(404);
        }
    }

    @Then("every listed offer with a snapshot answers 200 on snapshot and PDF")
    public void snapshotOffersOfList() {
        JsonArray list = ctx.last.json().getAsJsonArray();
        for (JsonElement e : list) {
            JsonObject o = e.getAsJsonObject();
            if (!o.get("hasSnapshot").getAsBoolean()) {
                continue;
            }
            long id = o.get("id").getAsLong();
            assertThat(ctx.http().get("/api/offers/" + id + "/snapshot", ctx.token("admin")).status())
                    .as("snapshot of offer %s", id).isEqualTo(200);
            assertThat(ctx.http().get("/api/offers/" + id + "/pdf", ctx.token("admin")).isPdf())
                    .as("PDF of offer %s", id).isTrue();
        }
    }

    @When("the admin prepares {int} more offers named {string}")
    public void prepareMany(int count, String prefix) {
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            String alias = prefix + i;
            prepareNamed(alias, "Offer-{RUN}-" + alias);
            ids.append(ids.length() == 0 ? "" : ",").append(ctx.var(alias));
        }
        ctx.vars.put(prefix + "_IDS", ids.toString());
    }

    // ================= Response assertions =================

    @Then("the response status is {int}")
    public void statusIs(int status) {
        assertThat(ctx.last.status()).as("HTTP status; body: %s", ctx.last.abbreviate()).isEqualTo(status);
    }

    @Then("the response status is one of {string}")
    public void statusIsOneOf(String statuses) {
        List<Integer> allowed = new ArrayList<>();
        for (String s : statuses.split(",")) {
            allowed.add(Integer.parseInt(s.trim()));
        }
        assertThat(ctx.last.status()).as("HTTP status; body: %s", ctx.last.abbreviate()).isIn(allowed);
    }

    @Then("the response error code is {string}")
    public void errorCode(String code) {
        assertThat(JsonPath.show(JsonPath.read(ctx.last.json(), "code"))).as("error code; body: %s", ctx.last.abbreviate())
                .isEqualTo(code);
    }

    @Then("the response field {string} equals {string}")
    public void fieldEquals(String path, String expected) {
        JsonElement actual = JsonPath.read(ctx.last.json(), ctx.resolve(path));
        String want = ctx.resolve(expected);
        assertThat(JsonPath.valueEquals(actual, want))
                .as("field %s: expected <%s> but was <%s>; body: %s", path, want, JsonPath.show(actual), ctx.last.abbreviate())
                .isTrue();
    }

    @Then("the response field {string} does not equal {string}")
    public void fieldDiffers(String path, String expected) {
        JsonElement actual = JsonPath.read(ctx.last.json(), ctx.resolve(path));
        String want = ctx.resolve(expected);
        assertThat(JsonPath.valueEquals(actual, want)).as("field %s must differ from <%s>; body: %s", path, want,
                ctx.last.abbreviate()).isFalse();
    }

    @Then("the response field {string} equals {string} plus {int}")
    public void fieldEqualsPlus(String path, String base, int plus) {
        BigDecimal actual = JsonPath.read(ctx.last.json(), ctx.resolve(path)).getAsBigDecimal();
        BigDecimal want = new BigDecimal(ctx.resolve(base)).add(BigDecimal.valueOf(plus));
        assertThat(actual).as("field %s", path).isEqualByComparingTo(want);
    }

    @Then("the response field {string} is not empty")
    public void fieldNotEmpty(String path) {
        assertThat(JsonPath.isEmpty(JsonPath.read(ctx.last.json(), ctx.resolve(path))))
                .as("field %s must not be empty; body: %s", path, ctx.last.abbreviate()).isFalse();
    }

    @Then("the response field {string} is empty or absent")
    public void fieldEmpty(String path) {
        JsonElement e = JsonPath.read(ctx.last.json(), ctx.resolve(path));
        assertThat(JsonPath.isEmpty(e)).as("field %s must be empty but was <%s>", path, JsonPath.show(e)).isTrue();
    }

    @Then("the response field {string} is less than {string}")
    public void fieldLess(String path, String than) {
        BigDecimal actual = JsonPath.read(ctx.last.json(), ctx.resolve(path)).getAsBigDecimal();
        assertThat(actual).as("field %s", path).isLessThan(new BigDecimal(ctx.resolve(than)));
    }

    @Then("the response field {string} is at least {int}")
    public void fieldAtLeast(String path, int min) {
        BigDecimal actual = JsonPath.read(ctx.last.json(), ctx.resolve(path)).getAsBigDecimal();
        assertThat(actual).as("field %s", path).isGreaterThanOrEqualTo(BigDecimal.valueOf(min));
    }

    @Then("the response array {string} has {int} items")
    public void arraySize(String path, int size) {
        JsonElement e = JsonPath.read(ctx.last.json(), ctx.resolve(path));
        assertThat(e.isJsonArray()).as("%s must be an array; body: %s", path, ctx.last.abbreviate()).isTrue();
        assertThat(e.getAsJsonArray().size()).as("size of %s", path).isEqualTo(size);
    }

    @Then("the response array {string} has an item with {string} equal to {string}")
    public void arrayHasItem(String path, String field, String value) {
        assertThat(findItem(path, field, value)).as("%s must contain an item with %s=%s; body: %s", path, field,
                ctx.resolve(value), ctx.last.abbreviate()).isTrue();
    }

    @Then("the response array {string} has no item with {string} equal to {string}")
    public void arrayHasNoItem(String path, String field, String value) {
        assertThat(findItem(path, field, value)).as("%s must not contain an item with %s=%s", path, field,
                ctx.resolve(value)).isFalse();
    }

    private boolean findItem(String path, String field, String value) {
        JsonElement e = JsonPath.read(ctx.last.json(), ctx.resolve(path));
        if (!e.isJsonArray()) {
            return false;
        }
        String want = ctx.resolve(value);
        for (JsonElement item : e.getAsJsonArray()) {
            if (item.isJsonObject() && JsonPath.valueEquals(item.getAsJsonObject().get(field), want)) {
                return true;
            }
        }
        return false;
    }

    @Then("the response body equals the remembered {string}")
    public void bodyEqualsRemembered(String name) {
        assertThat(ctx.last.text()).as("body must equal the remembered %s", name).isEqualTo(ctx.remembered.get(name));
    }

    @Then("the response body differs from the remembered {string}")
    public void bodyDiffers(String name) {
        assertThat(ctx.last.text()).as("body must differ from the remembered %s", name)
                .isNotEqualTo(ctx.remembered.get(name));
    }

    @Then("the response body does not contain {string}")
    public void bodyDoesNotContain(String text) {
        assertThat(ctx.last.text()).doesNotContain(ctx.resolve(text));
    }

    @Then("the response body contains {string}")
    public void bodyContains(String text) {
        assertThat(ctx.last.text()).contains(ctx.resolve(text));
    }

    @Then("the response header {string} contains {string}")
    public void headerContains(String name, String text) {
        assertThat(ctx.last.header(name)).as("header %s", name).isNotNull().contains(ctx.resolve(text));
    }

    // ================= PDF =================

    @Then("the response is a PDF document")
    public void responseIsPdf() {
        assertThat(ctx.last.status()).as("status; body: %s", ctx.last.abbreviate()).isEqualTo(200);
        assertThat(ctx.last.header("content-type")).contains("application/pdf");
        assertThat(ctx.last.isPdf()).as("body starts with the PDF signature").isTrue();
    }

    @Then("the PDF text contains {string}")
    public void pdfContains(String text) throws IOException {
        assertThat(normalize(pdfText())).contains(normalize(ctx.resolve(text)));
    }

    @Then("the PDF text does not contain {string}")
    public void pdfDoesNotContain(String text) throws IOException {
        assertThat(normalize(pdfText())).doesNotContain(normalize(ctx.resolve(text)));
    }

    @Then("the PDF text contains the amount {string}")
    public void pdfContainsAmount(String amount) throws IOException {
        assertThat(compact(pdfText())).as("PDF amounts").contains(money(amount));
    }

    @Then("the PDF text does not contain the amount {string}")
    public void pdfDoesNotContainAmount(String amount) throws IOException {
        assertThat(compact(pdfText())).as("PDF amounts").doesNotContain(money(amount));
    }

    /** Polish money format without grouping: two decimals and a decimal comma. */
    private String money(String amount) {
        return new BigDecimal(ctx.resolve(amount)).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** PDF text without any whitespace (including non-breaking spaces used as thousands separators). */
    private static String compact(String s) {
        return s.replaceAll("[\\s\\u00a0\\u202f]+", "");
    }

    @Given("the case is skipped because {string}")
    public void skipped(String reason) {
        throw new org.opentest4j.TestAbortedException(reason);
    }

    private String pdfText() throws IOException {
        try (PDDocument doc = PDDocument.load(ctx.last.bytes())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // ================= Snapshot structure =================

    @Then("the snapshot has a row for work {string}")
    public void snapshotHasRow(String work) {
        assertThat(snapshotWorkIds()).as("snapshot works").contains(ctx.id(work));
    }

    @Then("the snapshot has no row for work {string}")
    public void snapshotHasNoRow(String work) {
        assertThat(snapshotWorkIds()).as("snapshot works").doesNotContain(ctx.id(work));
    }

    @Then("the snapshot has no group without rows")
    public void noEmptyGroups() {
        for (JsonElement g : ctx.last.obj().getAsJsonArray("groups")) {
            assertThat(g.getAsJsonObject().getAsJsonArray("rows").size()).as("rows of a group").isGreaterThan(0);
        }
    }

    @Then("every snapshot row has a cell with a positive quantity")
    public void everyRowPositive() {
        for (JsonElement g : ctx.last.obj().getAsJsonArray("groups")) {
            for (JsonElement r : g.getAsJsonObject().getAsJsonArray("rows")) {
                boolean positive = false;
                for (JsonElement c : r.getAsJsonObject().getAsJsonArray("cells")) {
                    positive |= c.getAsJsonObject().get("quantity").getAsBigDecimal().signum() > 0;
                }
                assertThat(positive).as("row %s needs a positive cell", r).isTrue();
            }
        }
    }

    @Then("the snapshot rooms are exactly {string}")
    public void roomsExactly(String roomVars) {
        List<Long> expected = new ArrayList<>();
        for (String v : roomVars.split(",")) {
            expected.add(ctx.id(v.trim()));
        }
        expected.sort(Long::compare);
        List<Long> actual = new ArrayList<>();
        for (JsonElement r : ctx.last.obj().getAsJsonArray("rooms")) {
            actual.add(r.getAsJsonObject().get("id").getAsLong());
        }
        actual.sort(Long::compare);
        assertThat(actual).as("snapshot room ids").isEqualTo(expected);
    }

    @Then("the snapshot quantity of work {string} in room {string} is {string}")
    public void snapshotCell(String work, String room, String quantity) {
        BigDecimal found = null;
        for (JsonElement g : ctx.last.obj().getAsJsonArray("groups")) {
            for (JsonElement r : g.getAsJsonObject().getAsJsonArray("rows")) {
                if (r.getAsJsonObject().get("workItemId").getAsLong() != ctx.id(work)) {
                    continue;
                }
                for (JsonElement c : r.getAsJsonObject().getAsJsonArray("cells")) {
                    if (c.getAsJsonObject().get("roomId").getAsLong() == ctx.id(room)) {
                        found = c.getAsJsonObject().get("quantity").getAsBigDecimal();
                    }
                }
            }
        }
        assertThat(found).as("snapshot cell %s x %s", work, room).isNotNull();
        assertThat(found).isEqualByComparingTo(new BigDecimal(quantity));
    }

    @Then("the snapshot totals are consistent with its VAT rate")
    public void totalsConsistent() {
        JsonObject o = ctx.last.obj();
        BigDecimal net = o.get("totalNet").getAsBigDecimal();
        BigDecimal vat = o.get("totalVat").getAsBigDecimal();
        BigDecimal gross = o.get("totalGross").getAsBigDecimal();
        BigDecimal pct = o.get("vatRatePct").getAsBigDecimal();
        assertThat(net.add(vat)).as("net plus VAT").isEqualByComparingTo(gross);
        assertThat(vat).as("VAT from the rate").isEqualByComparingTo(net.multiply(pct).divide(BigDecimal.valueOf(100), 2,
                RoundingMode.HALF_UP));
    }

    @Then("the comparison group values add up to each offer net total")
    public void groupsAddUp() {
        JsonObject o = ctx.last.obj();
        for (JsonElement offer : o.getAsJsonArray("offers")) {
            long id = offer.getAsJsonObject().get("id").getAsLong();
            BigDecimal sum = BigDecimal.ZERO;
            for (JsonElement g : o.getAsJsonArray("groups")) {
                for (JsonElement v : g.getAsJsonObject().getAsJsonArray("values")) {
                    JsonObject vo = v.getAsJsonObject();
                    if (vo.get("offerId").getAsLong() == id && !vo.get("valueNet").isJsonNull()) {
                        sum = sum.add(vo.get("valueNet").getAsBigDecimal());
                    }
                }
            }
            assertThat(sum).as("sum of groups of offer %s", id)
                    .isEqualByComparingTo(offer.getAsJsonObject().get("totalNet").getAsBigDecimal());
        }
    }

    private List<Long> snapshotWorkIds() {
        List<Long> ids = new ArrayList<>();
        for (JsonElement g : ctx.last.obj().getAsJsonArray("groups")) {
            for (JsonElement r : g.getAsJsonObject().getAsJsonArray("rows")) {
                ids.add(r.getAsJsonObject().get("workItemId").getAsLong());
            }
        }
        return ids;
    }

    // ================= Database checks =================

    @Then("the estimate quantity of work {string} in room {string} is {string}")
    public void estimateQuantity(String work, String room, String quantity) {
        BigDecimal q = ctx.fx.roomQuantity(ctx.id("EID"), ctx.id(work), ctx.id(room));
        if ("none".equals(quantity)) {
            assertThat(q).as("no quantity of %s in %s", work, room).isNull();
        } else {
            assertThat(q).as("quantity of %s in %s", work, room).isNotNull();
            assertThat(q).isEqualByComparingTo(new BigDecimal(quantity));
        }
    }

    @Then("the estimate has {int} lines of work {string}")
    public void lineCount(int count, String work) {
        assertThat(ctx.fx.lineCount(ctx.id("EID"), ctx.id(work))).as("lines of %s", work).isEqualTo(count);
    }

    @Then("the estimate applied package is {string}")
    public void appliedPackage(String code) {
        String actual = ctx.fx.appliedPackage(ctx.id("EID"));
        if ("none".equals(code)) {
            assertThat(actual).isNull();
        } else {
            assertThat(actual).isEqualTo(ctx.resolve(code));
        }
    }

    @Then("the project has {int} offers")
    public void offerCount(int count) {
        assertThat(ctx.fx.offerCount(ctx.id("PID"))).isEqualTo(count);
    }

    @Then("the project has exactly one approved offer")
    public void oneApproved() {
        assertThat(ctx.fx.approvedCount(ctx.id("PID"))).isEqualTo(1);
    }

    @Then("the offer {string} has {int} audit rows {string}")
    public void auditRows(String alias, int count, String operation) {
        assertThat(ctx.fx.auditCount(ctx.id(alias), operation)).as("%s audit rows of %s", operation, alias)
                .isEqualTo(count);
    }

    @Then("exactly one of the concurrent approvals succeeded and the other got {int}")
    public void raceResult(int otherStatus) {
        List<Integer> statuses = new ArrayList<>();
        for (Resp r : race) {
            statuses.add(r.status());
        }
        statuses.sort(Integer::compare);
        assertThat(statuses).as("statuses of the concurrent approvals").containsExactly(200, otherStatus);
    }

    @Then("the column {string} of table {string} is {string}")
    public void columnFacts(String column, String table, String facts) {
        assertThat(ctx.fx.columnFacts(table, column)).isEqualTo(facts);
    }

    @Then("the index {string} exists {int} times")
    public void indexCount(String name, int count) {
        assertThat(ctx.fx.indexCount(name)).as("index %s", name).isEqualTo(count);
    }

    // ================= Notifications =================

    @Then("the {word} has {int} notifications of type {string}")
    public void notifications(String actor, int count, String type) {
        int found = -1;
        for (int i = 0; i < 20; i++) {
            found = countNotifications(actor, type);
            if (found == count && i > 1) {
                break;
            }
            OfferCtx.sleep(500);
        }
        assertThat(found).as("notifications %s of %s", type, actor).isEqualTo(count);
    }

    private int countNotifications(String actor, String type) {
        Resp r = ctx.http().get("/api/notifications", ctx.token(actor));
        assertThat(r.status()).as("notifications of %s: %s", actor, r.abbreviate()).isEqualTo(200);
        JsonArray items = r.json().getAsJsonArray();
        int n = 0;
        for (JsonElement e : items) {
            if (type.equals(JsonPath.show(e.getAsJsonObject().get("type")))) {
                n++;
            }
        }
        return n;
    }
}
