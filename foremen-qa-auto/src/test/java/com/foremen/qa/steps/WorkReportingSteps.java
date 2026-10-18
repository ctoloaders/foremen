package com.foremen.qa.steps;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.qa.fixtures.TestUser;
import com.foremen.qa.fixtures.TestUserFixture;
import com.foremen.qa.fixtures.WorkReportFixture;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.WorkReportApiHelper;
import com.foremen.qa.support.WorkReportApiHelper.Resp;
import com.foremen.qa.support.World;
import com.google.gson.JsonObject;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * FOR-QA-AUTO-06-01 API steps for the FOR-06-01 work-reporting slice (Part A of test-cases.md).
 *
 * <p>Seeds a reportable ACTIVE project (members via the admin API, a priced estimate + per-room
 * plan quantities + the worker's Covering_Work category via the {@link WorkReportFixture} direct-DB
 * fixture), drives {@code /api/work-reports} CRUD + review endpoints, and asserts the HTTP contract
 * and the response-body derivations. Repeatability: {@link DataGen} run-id + LIFO teardown in
 * {@link World}; seed roles/resources are only read.
 */
public class WorkReportingSteps {

    private final World world;
    private final TestUserFixture userFixture = new TestUserFixture();
    private final WorkReportFixture reportFixture = new WorkReportFixture();

    private WorkReportApiHelper api;

    // Fixture state.
    private Long projectId;
    private TestUser worker;        // ACTIVE WORKER, covers the work item
    private TestUser worker2;       // ACTIVE WORKER, does NOT cover the work item
    private TestUser foreman;       // ACTIVE FOREMAN approver
    private TestUser manager;       // MANAGER (no CREATE)
    private TestUser estimator;     // ESTIMATOR (READ only)
    private TestUser client;        // CLIENT (no grant)
    private WorkReportFixture.WorkItemRef workItem;
    private WorkReportFixture.SeededEstimate estimate;
    private final List<Long> createdReports = new ArrayList<>();
    private Resp last;
    private Long lastReportId;

    public WorkReportingSteps(World world) {
        this.world = world;
    }

    private WorkReportApiHelper api() {
        if (api == null) {
            api = WorkReportApiHelper.create();
            api.loginAdmin();
            world.registerTeardown(() -> {
                if (api != null) {
                    api.close();
                    api = null;
                }
            });
        }
        return api;
    }

    private ApiHelper adminApi() {
        ApiHelper a = world.api();
        if (a.accessToken() == null) {
            a.loginAdmin();
        }
        return a;
    }

    private TestUser createUser(String roleCode) {
        TestUser u = userFixture.createActiveUser(roleCode);
        world.registerTeardown(() -> userFixture.deleteUserAndResources(u));
        return u;
    }

    // ---- Setup ----

    @Given("a reportable active project with a crew and a priced estimate exists")
    public void aReportableProjectExists() {
        api();
        ApiHelper admin = adminApi();

        worker = createUser("WORKER");
        worker2 = createUser("WORKER");
        foreman = createUser("FOREMAN");
        manager = createUser("MANAGER");
        estimator = createUser("ESTIMATOR");
        client = createUser("CLIENT");

        long managerRoleId = admin.resolveRoleIdByCode("MANAGER");
        long foremanRoleId = admin.resolveRoleIdByCode("FOREMAN");
        long workerRoleId = admin.resolveRoleIdByCode("WORKER");
        long clientRoleId = admin.resolveRoleIdByCode("CLIENT");
        long estimatorRoleId = admin.resolveRoleIdByCode("ESTIMATOR");

        // Create the project with the manager + client (required members), then add the rest.
        List<Map<String, Object>> members = new ArrayList<>();
        members.add(member(manager.id(), managerRoleId));
        members.add(member(client.id(), clientRoleId));
        String name = DataGen.nextProjectName();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("members", members);
        long pid = admin.createProject(body);
        this.projectId = pid;
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.deleteProject(pid);
            } catch (RuntimeException ignored) {
            }
        });

        // Add the worker, worker2, foreman, estimator as members.
        admin.assignProjectMember(worker.id(), pid, workerRoleId);
        admin.assignProjectMember(worker2.id(), pid, workerRoleId);
        admin.assignProjectMember(foreman.id(), pid, foremanRoleId);
        admin.assignProjectMember(estimator.id(), pid, estimatorRoleId);

        // Seed rooms (two with a plan, one without).
        long roomTypeId = admin.resolveFirstRowId("/api/room-types");
        long roomA = createRoom(admin, pid, roomTypeId, "Room A " + DataGen.runId());
        long roomB = createRoom(admin, pid, roomTypeId, "Room B " + DataGen.runId());
        long roomNoPlan = createRoom(admin, pid, roomTypeId, "Room NoPlan " + DataGen.runId());

        // Seed the priced estimate (frozen unit price) + per-room plan quantities (direct DB).
        this.workItem = reportFixture.firstWorkItemWithCategory();
        this.estimate = reportFixture.seedEstimate(pid, workItem, new BigDecimal("12.50"),
                roomA, new BigDecimal("20"), roomB, new BigDecimal("5"), roomNoPlan);
        world.registerTeardown(() -> reportFixture.deleteEstimate(estimate.estimateId()));

        // Link ONLY the first worker's membership to the work item's category (Covering_Work).
        long workerMemberId = reportFixture.projectMemberId(worker.id(), pid);
        reportFixture.linkMemberWorkCategory(workerMemberId, workItem.workCategoryId());

        // Move the project to ACTIVE so reporting is open.
        admin.loginAdmin();
        activateProject(admin, pid, name);
    }

    private static Map<String, Object> member(long userId, long roleId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("projectRoleId", roleId);
        return m;
    }

    private long createRoom(ApiHelper admin, long pid, long roomTypeId, String label) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", pid);
        body.put("roomTypeId", roomTypeId);
        body.put("label", label);
        body.put("ceilingHeight", 2.5);
        body.put("floorArea", 10);
        body.put("wallArea", 25);
        return admin.createRoom(body);
    }

    private void activateProject(ApiHelper admin, long pid, String name) {
        // PUT /api/projects/{id} requires name; set status ACTIVE so reporting is open.
        admin.updateProjectStatus(pid, name, "ACTIVE");
    }

    private long requireProject() {
        if (projectId == null) {
            throw new IllegalStateException("No project in scope; the reportable-project Given must run first.");
        }
        return projectId;
    }

    // ---- Report body builders ----

    private Map<String, Object> reportBody(long workerId, List<Map<String, Object>> rooms) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", requireProject());
        body.put("workItemId", workItem.workItemId());
        body.put("workerId", workerId);
        body.put("performedFrom", "2026-01-01");
        body.put("performedTo", "2026-01-02");
        body.put("note", "note " + DataGen.runId());
        body.put("rooms", rooms);
        return body;
    }

    private static Map<String, Object> row(long roomId, String quantity, String mode) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("roomId", roomId);
        r.put("quantity", new BigDecimal(quantity));
        r.put("inputMode", mode);
        return r;
    }

    private void rememberReport(Resp resp) {
        if (resp.ok() && resp.json() != null && resp.json().isJsonObject()
                && resp.obj().has("id")) {
            long id = resp.obj().get("id").getAsLong();
            this.lastReportId = id;
            if (!createdReports.contains(id)) {
                createdReports.add(id);
                world.registerTeardown(() -> reportFixture.deleteWorkReport(id));
            }
        }
    }

    /**
     * Create a report, remember it for teardown, and — on a successful create (the generic CRUD
     * create returns 200 with the {@code WorkReportCreateResponse} echo, which carries no status /
     * derivations) — follow with a by-id read so {@code last} holds the rich
     * {@code WorkReportDtoExtendedModel} (status, totalQuantity, revenueValue, clientUnitPrice,
     * approver). On a 4xx the create error response is kept as {@code last} for the status assertion.
     */
    private void createAndRead(Map<String, Object> body) {
        Resp created = api().create(body);
        rememberReport(created);
        if (created.ok() && created.json() != null && created.json().isJsonObject()
                && created.obj().has("id")) {
            long id = created.obj().get("id").getAsLong();
            // Read back as the authoring caller (own-records allows a WORKER to read own report).
            last = api().readOne(id);
        } else {
            last = created;
        }
    }

    private void loginAs(TestUser u) {
        api().loginAs(u.email(), u.password());
    }

    private void backToAdmin() {
        api().loginAdmin();
    }

    // ---- Create / derivations (Feature 1) ----

    @When("the worker creates an absolute report in two rooms")
    public void workerCreatesAbsoluteReport() {
        loginAs(worker);
        List<Map<String, Object>> rooms = List.of(
                row(estimate.roomWithPlanAId(), "3.000", "ABSOLUTE"),
                row(estimate.roomWithPlanBId(), "4.500", "ABSOLUTE"));
        createAndRead(reportBody(worker.id(), rooms));
        backToAdmin();
    }

    @When("the worker creates a percent report of {int} percent in the planned room")
    public void workerCreatesPercentReport(int percent) {
        loginAs(worker);
        List<Map<String, Object>> rooms = List.of(
                row(estimate.roomWithPlanAId(), String.valueOf(percent), "PERCENT"));
        createAndRead(reportBody(worker.id(), rooms));
        backToAdmin();
    }

    @When("the worker creates a percent report in the room with no plan")
    public void workerCreatesPercentNoPlan() {
        loginAs(worker);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomNoPlanId(), "30", "PERCENT"));
        last = api().create(reportBody(worker.id(), rooms));
        rememberReport(last);
        backToAdmin();
    }

    @When("the worker creates an absolute report in the room with no plan")
    public void workerCreatesAbsoluteNoPlan() {
        loginAs(worker);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomNoPlanId(), "5", "ABSOLUTE"));
        last = api().create(reportBody(worker.id(), rooms));
        rememberReport(last);
        backToAdmin();
    }

    @When("the worker creates a report with a duplicate room")
    public void workerCreatesDuplicateRoom() {
        loginAs(worker);
        List<Map<String, Object>> rooms = List.of(
                row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"),
                row(estimate.roomWithPlanAId(), "4", "ABSOLUTE"));
        last = api().create(reportBody(worker.id(), rooms));
        rememberReport(last);
        backToAdmin();
    }

    @When("the worker creates a report with quantity {string}")
    public void workerCreatesWithQuantity(String q) {
        loginAs(worker);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomWithPlanAId(), q, "ABSOLUTE"));
        last = api().create(reportBody(worker.id(), rooms));
        rememberReport(last);
        backToAdmin();
    }

    // ---- Work matching (Feature 2) ----

    @When("the uncovered worker creates a report")
    public void uncoveredWorkerCreatesReport() {
        loginAs(worker2);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"));
        last = api().create(reportBody(worker2.id(), rooms));
        rememberReport(last);
        backToAdmin();
    }

    @When("the worker creates a report for a work item not on the estimate")
    public void workerCreatesForWorkItemNotOnEstimate() {
        loginAs(worker);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"));
        Map<String, Object> body = reportBody(worker.id(), rooms);
        body.put("workItemId", 99_000_000L + DataGen.nextSeq());
        last = api().create(body);
        rememberReport(last);
        backToAdmin();
    }

    // ---- Status machine (Feature 3) ----

    private long submitWorkerReport() {
        loginAs(worker);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"));
        Resp created = api().create(reportBody(worker.id(), rooms));
        rememberReport(created);
        backToAdmin();
        assertThat(created.status()).as("submit a SUBMITTED report (body %s)", created.text())
                .isEqualTo(200);
        return created.obj().get("id").getAsLong();
    }

    @When("the foreman approves the submitted report")
    public void foremanApprovesSubmitted() {
        long id = submitWorkerReport();
        loginAs(foreman);
        last = api().approve(id);
        lastReportId = id;
        backToAdmin();
    }

    @When("the foreman rejects the submitted report without a reason")
    public void foremanRejectsWithoutReason() {
        long id = submitWorkerReport();
        loginAs(foreman);
        last = api().reject(id, "   ");
        lastReportId = id;
        backToAdmin();
    }

    @When("the foreman rejects the submitted report with a reason")
    public void foremanRejectsWithReason() {
        long id = submitWorkerReport();
        loginAs(foreman);
        last = api().reject(id, "incomplete work");
        lastReportId = id;
        backToAdmin();
    }

    @When("the foreman requests changes on the submitted report with a reason")
    public void foremanRequestsChanges() {
        long id = submitWorkerReport();
        loginAs(foreman);
        last = api().requestChanges(id, "add photos");
        lastReportId = id;
        backToAdmin();
    }

    @When("the author edits and resubmits the changes-requested report")
    public void authorResubmitsChangesRequested() {
        long id = submitWorkerReport();
        loginAs(foreman);
        Resp rc = api().requestChanges(id, "add photos");
        assertThat(rc.status()).as("request-changes (body %s)", rc.text()).isEqualTo(200);
        backToAdmin();
        // Author edits -> resubmit. The PUT returns the WorkReportUpdateResponse echo (no status),
        // so read the report back as the author for the status assertion (own-records allows it).
        loginAs(worker);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomWithPlanAId(), "6", "ABSOLUTE"));
        Map<String, Object> body = reportBody(worker.id(), rooms);
        Resp updated = api().update(id, body);
        assertThat(updated.status()).as("resubmit edit (body %s)", updated.text()).isEqualTo(200);
        last = api().readOne(id);
        lastReportId = id;
        backToAdmin();
    }

    @When("the foreman approves the resubmitted report")
    public void foremanApprovesResubmitted() {
        assertThat(lastReportId).as("a report must be in scope").isNotNull();
        loginAs(foreman);
        last = api().approve(lastReportId);
        backToAdmin();
    }

    @When("the foreman tries to edit the approved report")
    public void foremanEditsApprovedReport() {
        long id = submitWorkerReport();
        loginAs(foreman);
        Resp approved = api().approve(id);
        assertThat(approved.status()).as("approve first (body %s)", approved.text()).isEqualTo(200);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomWithPlanAId(), "9", "ABSOLUTE"));
        last = api().update(id, reportBody(worker.id(), rooms));
        lastReportId = id;
        backToAdmin();
    }

    @When("the foreman tries to approve a rejected report")
    public void foremanApprovesRejected() {
        long id = submitWorkerReport();
        loginAs(foreman);
        Resp rejected = api().reject(id, "no good");
        assertThat(rejected.status()).as("reject first (body %s)", rejected.text()).isEqualTo(200);
        last = api().approve(id);
        lastReportId = id;
        backToAdmin();
    }

    @When("the worker tries to approve their own submitted report")
    public void workerApprovesOwn() {
        long id = submitWorkerReport();
        loginAs(worker);
        last = api().approve(id);
        lastReportId = id;
        backToAdmin();
    }

    @When("the manager tries to approve the submitted report")
    public void managerApprovesSubmitted() {
        long id = submitWorkerReport();
        loginAs(manager);
        last = api().approve(id);
        lastReportId = id;
        backToAdmin();
    }

    // ---- Fast path (Feature 4) ----

    @When("the foreman creates a report for their own work")
    public void foremanSelfReport() {
        // The foreman must cover the work item too; link their membership category.
        long foremanMemberId = reportFixture.projectMemberId(foreman.id(), requireProject());
        reportFixture.linkMemberWorkCategory(foremanMemberId, workItem.workCategoryId());
        loginAs(foreman);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomWithPlanAId(), "4", "ABSOLUTE"));
        createAndRead(reportBody(foreman.id(), rooms));
        backToAdmin();
    }

    @When("the foreman creates a report for the worker")
    public void foremanReportsForWorker() {
        loginAs(foreman);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomWithPlanAId(), "4", "ABSOLUTE"));
        createAndRead(reportBody(worker.id(), rooms));
        backToAdmin();
    }

    // ---- Revenue freeze (Feature 5) ----

    @When("I read the last report")
    public void iReadLastReport() {
        assertThat(lastReportId).as("a report must be in scope").isNotNull();
        last = api().readOne(lastReportId);
    }

    // ---- ABAC / own-records (Feature 6) ----

    @When("I list reports without a token")
    public void listNoToken() {
        api().clearAuth();
        last = api().listNoToken(requireProject());
        backToAdmin();
    }

    @When("the worker lists reports")
    public void workerListsReports() {
        loginAs(worker);
        last = api().list(requireProject());
        backToAdmin();
    }

    @When("the worker tries to read the other worker's report")
    public void workerReadsOthersReport() {
        long otherId = submitForWorker2();
        loginAs(worker);
        last = api().readOne(otherId);
        lastReportId = otherId;
        backToAdmin();
    }

    @When("the worker creates a report for another worker")
    public void workerCreatesForAnother() {
        loginAs(worker);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"));
        Map<String, Object> body = reportBody(worker2.id(), rooms);
        last = api().create(body);
        rememberReport(last);
        backToAdmin();
    }

    @When("the manager tries to create a report")
    public void managerCreatesReport() {
        loginAs(manager);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"));
        last = api().create(reportBody(worker.id(), rooms));
        rememberReport(last);
        backToAdmin();
    }

    @When("the manager lists reports")
    public void managerListsReports() {
        loginAs(manager);
        last = api().list(requireProject());
        backToAdmin();
    }

    @When("the estimator lists reports")
    public void estimatorListsReports() {
        loginAs(estimator);
        last = api().list(requireProject());
        backToAdmin();
    }

    @When("the estimator tries to create a report")
    public void estimatorCreatesReport() {
        loginAs(estimator);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"));
        last = api().create(reportBody(worker.id(), rooms));
        rememberReport(last);
        backToAdmin();
    }

    @When("the client lists reports")
    public void clientListsReports() {
        loginAs(client);
        last = api().list(requireProject());
        backToAdmin();
    }

    @When("the foreman lists reports")
    public void foremanListsReports() {
        loginAs(foreman);
        last = api().list(requireProject());
        backToAdmin();
    }

    private long submitForWorker2() {
        // worker2 is uncovered, so a direct submit would be rejected; the FOREMAN authors on
        // worker2's behalf (fast path) to get a worker2-owned report for the own-records check.
        long worker2MemberId = reportFixture.projectMemberId(worker2.id(), requireProject());
        reportFixture.linkMemberWorkCategory(worker2MemberId, workItem.workCategoryId());
        loginAs(foreman);
        List<Map<String, Object>> rooms = List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"));
        Resp created = api().create(reportBody(worker2.id(), rooms));
        rememberReport(created);
        backToAdmin();
        assertThat(created.status()).as("foreman authors for worker2 (body %s)", created.text())
                .isEqualTo(200);
        return created.obj().get("id").getAsLong();
    }

    // ---- Assertions ----

    @Then("the work report API responds with HTTP {int}")
    public void apiRespondsWith(int code) {
        assertThat(last).as("a work-report API call must have been made").isNotNull();
        assertThat(last.status()).as("HTTP status (body: %s)", last.text()).isEqualTo(code);
    }

    @Then("the report status is {string}")
    public void reportStatusIs(String status) {
        assertThat(last.string("status")).as("status (body %s)", last.text()).isEqualTo(status);
    }

    @Then("the report total quantity is {string}")
    public void reportTotalQuantityIs(String expected) {
        BigDecimal actual = new BigDecimal(last.string("totalQuantity"));
        assertThat(actual).as("totalQuantity (body %s)", last.text())
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @Then("the report revenue value is {string}")
    public void reportRevenueValueIs(String expected) {
        BigDecimal actual = new BigDecimal(last.string("revenueValue"));
        assertThat(actual).as("revenueValue (body %s)", last.text())
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @Then("the report client unit price is {string}")
    public void reportClientUnitPriceIs(String expected) {
        BigDecimal actual = new BigDecimal(last.string("clientUnitPrice"));
        assertThat(actual).as("clientUnitPrice (body %s)", last.text())
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @Then("the report approver is set")
    public void reportApproverIsSet() {
        JsonObject o = last.obj();
        assertThat(o.has("approvedById") && !o.get("approvedById").isJsonNull())
                .as("approvedById set (body %s)", last.text()).isTrue();
        assertThat(o.has("approvedAt") && !o.get("approvedAt").isJsonNull())
                .as("approvedAt set (body %s)", last.text()).isTrue();
    }

    @Then("the report list contains the last report")
    public void listContainsLast() {
        assertThat(lastReportId).isNotNull();
        assertThat(last.rowById(lastReportId))
                .as("report %s present in %s", lastReportId, last.text()).isNotNull();
    }

    @Then("the report list does not contain the last report")
    public void listDoesNotContainLast() {
        assertThat(lastReportId).isNotNull();
        assertThat(last.rowById(lastReportId))
                .as("report %s should be absent from %s", lastReportId, last.text()).isNull();
    }

    @Then("the report list is non-empty")
    public void listNonEmpty() {
        assertThat(last.size()).as("list size (body %s)", last.text()).isGreaterThan(0);
    }
}
