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
import com.foremen.qa.support.NotificationApiHelper;
import com.foremen.qa.support.WorkReportApiHelper;
import com.foremen.qa.support.WorkReportApiHelper.Resp;
import com.foremen.qa.support.World;
import com.google.gson.JsonObject;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class WorkReportMatrixApiSteps {

    private final World world;
    private final TestUserFixture userFixture = new TestUserFixture();
    private final WorkReportFixture reportFixture = new WorkReportFixture();
    private WorkReportApiHelper api;
    private Long projectId;
    private TestUser worker;
    private TestUser worker2;
    private TestUser foreman;
    private TestUser manager;
    private TestUser client;
    private WorkReportFixture.WorkItemRef workItem;
    private WorkReportFixture.SeededEstimate estimate;
    private final List<Long> createdReports = new ArrayList<>();
    private Resp last;
    private Resp lastProgress;

    public WorkReportMatrixApiSteps(World world) {
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

    private void loginAs(TestUser u) {
        api().loginAs(u.email(), u.password());
    }

    private void backToAdmin() {
        api().loginAdmin();
    }

    @Given("a reportable active project with a crew for the matrix exists")
    public void aReportableMatrixProjectExists() {
        api();
        ApiHelper admin = adminApi();
        worker = createUser("WORKER");
        worker2 = createUser("WORKER");
        foreman = createUser("FOREMAN");
        manager = createUser("MANAGER");
        client = createUser("CLIENT");
        long managerRoleId = admin.resolveRoleIdByCode("MANAGER");
        long foremanRoleId = admin.resolveRoleIdByCode("FOREMAN");
        long workerRoleId = admin.resolveRoleIdByCode("WORKER");
        long clientRoleId = admin.resolveRoleIdByCode("CLIENT");
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
        admin.assignProjectMember(worker.id(), pid, workerRoleId);
        admin.assignProjectMember(worker2.id(), pid, workerRoleId);
        admin.assignProjectMember(foreman.id(), pid, foremanRoleId);
        long roomTypeId = admin.resolveFirstRowId("/api/room-types");
        long roomA = createRoom(admin, pid, roomTypeId, "Room A " + DataGen.runId());
        long roomB = createRoom(admin, pid, roomTypeId, "Room B " + DataGen.runId());
        long roomNoPlan = createRoom(admin, pid, roomTypeId, "Room NoPlan " + DataGen.runId());
        this.workItem = reportFixture.firstWorkItemWithCategory();
        this.estimate = reportFixture.seedEstimate(pid, workItem, new BigDecimal("12.50"),
                roomA, new BigDecimal("20"), roomB, new BigDecimal("5"), roomNoPlan);
        world.registerTeardown(() -> reportFixture.deleteEstimate(estimate.estimateId()));
        long workerMemberId = reportFixture.projectMemberId(worker.id(), pid);
        reportFixture.linkMemberWorkCategory(workerMemberId, workItem.workCategoryId());
        admin.loginAdmin();
        admin.updateProjectStatus(pid, name, "ACTIVE");
    }

    private static Map<String, Object> member(long userId, long roleId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("projectRoleId", roleId);
        return m;
    }

    private long createRoom(ApiHelper admin, long pid, long roomTypeId, String label) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("projectId", pid);
        b.put("roomTypeId", roomTypeId);
        b.put("label", label);
        b.put("ceilingHeight", 2.5);
        b.put("floorArea", 10);
        b.put("wallArea", 25);
        return admin.createRoom(b);
    }

    private long requireProject() {
        if (projectId == null) {
            throw new IllegalStateException("No matrix project in scope; the Given must run first.");
        }
        return projectId;
    }

    private Map<String, Object> item(long workerId, List<Map<String, Object>> rooms) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("projectId", requireProject());
        b.put("workItemId", workItem.workItemId());
        b.put("workerId", workerId);
        b.put("performedFrom", "2026-02-01");
        b.put("performedTo", "2026-02-01");
        b.put("note", "batch " + DataGen.runId());
        b.put("rooms", rooms);
        return b;
    }

    private static Map<String, Object> row(long roomId, String quantity, String mode) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("roomId", roomId);
        r.put("quantity", new BigDecimal(quantity));
        r.put("inputMode", mode);
        return r;
    }

    private void rememberBatchCreated(Resp resp) {
        if (resp.ok() && resp.json() != null && resp.json().isJsonObject()
                && resp.obj().has("created")) {
            for (var el : resp.batchCreatedIds()) {
                long id = el.getAsLong();
                createdReports.add(id);
                world.registerTeardown(() -> reportFixture.deleteWorkReport(id));
            }
        }
    }

    private long seedSubmitted(String quantity) {
        loginAs(worker);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", requireProject());
        body.put("workItemId", workItem.workItemId());
        body.put("workerId", worker.id());
        body.put("performedFrom", "2026-02-01");
        body.put("performedTo", "2026-02-01");
        body.put("note", "batch " + DataGen.runId());
        body.put("rooms", List.of(row(estimate.roomWithPlanAId(), quantity, "ABSOLUTE")));
        Resp created = api().create(body);
        backToAdmin();
        assertThat(created.status()).as("seed (body %s)", created.text()).isEqualTo(200);
        long id = created.obj().get("id").getAsLong();
        createdReports.add(id);
        world.registerTeardown(() -> reportFixture.deleteWorkReport(id));
        return id;
    }

    // ---- Progress projection (TC-API-PROGRESS-01..04) ----

    @When("the worker reads the progress matrix for themselves")
    public void workerReadsOwnProgress() {
        loginAs(worker);
        lastProgress = api().progress(requireProject(), worker.id());
        last = lastProgress;
        backToAdmin();
    }

    @Then("the progress matrix has at least one room column")
    public void progressHasRoomColumn() {
        assertThat(lastProgress.progressRoomCount())
                .as("rooms[] (body %s)", lastProgress.text()).isGreaterThan(0);
    }

    @Then("the progress matrix contains the covered work item")
    public void progressContainsWorkItem() {
        assertThat(lastProgress.progressHasWorkItem(workItem.workItemId()))
                .as("work item present (body %s)", lastProgress.text()).isTrue();
    }

    @Then("the progress cell for room A has plan {string} and reported {string}")
    public void progressCellA(String plan, String reported) {
        JsonObject c = lastProgress.progressCell(workItem.workItemId(), estimate.roomWithPlanAId());
        assertThat(c).as("cell A present (body %s)", lastProgress.text()).isNotNull();
        assertThat(new BigDecimal(c.get("planQuantity").getAsString()))
                .as("planQuantity").isEqualByComparingTo(new BigDecimal(plan));
        assertThat(new BigDecimal(c.get("reportedQuantity").getAsString()))
                .as("reportedQuantity").isEqualByComparingTo(new BigDecimal(reported));
    }

    @When("reports in SUBMITTED, CHANGES_REQUESTED, APPROVED and REJECTED exist for room A")
    public void seedMixedStatusReports() {
        long rSub = seedSubmitted("5");
        long rChg = seedSubmitted("7");
        long rApp = seedSubmitted("3");
        long rRej = seedSubmitted("9");
        loginAs(foreman);
        assertThat(api().requestChanges(rChg, "fix it").status()).isEqualTo(200);
        assertThat(api().approve(rApp).status()).isEqualTo(200);
        assertThat(api().reject(rRej, "no good").status()).isEqualTo(200);
        backToAdmin();
        // rSub stays SUBMITTED; expected reportedQuantity for room A = 5 + 7 + 3 = 15 (REJECTED 9 excluded).
        loginAs(worker);
        lastProgress = api().progress(requireProject(), worker.id());
        last = lastProgress;
        backToAdmin();
    }

    @Then("the progress cell for room A reported quantity is {string}")
    public void progressCellAReported(String reported) {
        JsonObject c = lastProgress.progressCell(workItem.workItemId(), estimate.roomWithPlanAId());
        assertThat(c).as("cell A present (body %s)", lastProgress.text()).isNotNull();
        assertThat(new BigDecimal(c.get("reportedQuantity").getAsString()))
                .as("reportedQuantity (body %s)", lastProgress.text())
                .isEqualByComparingTo(new BigDecimal(reported));
    }

    @When("the worker reads the progress matrix for the other worker")
    public void workerReadsOtherProgress() {
        loginAs(worker);
        last = api().progress(requireProject(), worker2.id());
        backToAdmin();
    }

    @When("the foreman reads the progress matrix for the worker")
    public void foremanReadsWorkerProgress() {
        loginAs(foreman);
        last = api().progress(requireProject(), worker.id());
        backToAdmin();
    }

    @When("the client reads the progress matrix for the worker")
    public void clientReadsWorkerProgress() {
        loginAs(client);
        last = api().progress(requireProject(), worker.id());
        backToAdmin();
    }

    @When("the progress matrix is read without a token")
    public void progressNoToken() {
        api().clearAuth();
        last = api().progressNoToken(requireProject(), worker.id());
        backToAdmin();
    }

    // ---- Bulk create (TC-API-BATCH-01..05) ----

    @When("the worker submits a valid batch of two reports")
    public void workerSubmitsValidBatch() {
        loginAs(worker);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item(worker.id(), List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"))));
        items.add(item(worker.id(), List.of(row(estimate.roomWithPlanBId(), "2", "ABSOLUTE"))));
        last = api().createBatch(items);
        rememberBatchCreated(last);
        backToAdmin();
    }

    private int countWorkerReports() {
        loginAs(worker);
        Resp list = api().list(requireProject());
        backToAdmin();
        return list.size();
    }

    private int reportsBefore;

    @When("the worker submits a batch whose third item is invalid")
    public void workerSubmitsBatchWithBadItem() {
        reportsBefore = countWorkerReports();
        loginAs(worker);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item(worker.id(), List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"))));
        items.add(item(worker.id(), List.of(row(estimate.roomWithPlanBId(), "2", "ABSOLUTE"))));
        items.add(item(worker.id(), List.of(row(estimate.roomNoPlanId(), "30", "PERCENT"))));
        last = api().createBatch(items);
        rememberBatchCreated(last);
        backToAdmin();
    }

    @Then("the batch error identifies the failing item at index {int}")
    public void batchErrorIndex(int idx) {
        assertThat(last.batchErrorIndex())
                .as("failing item index (body %s)", last.text()).isEqualTo(idx);
    }

    @Then("no new reports were created for the worker")
    public void noNewReports() {
        assertThat(countWorkerReports())
                .as("report count unchanged after rollback").isEqualTo(reportsBefore);
    }

    @Then("the batch response created {int} reports")
    public void batchCreatedCount(int n) {
        assertThat(last.batchCreatedIds().size())
                .as("created count (body %s)", last.text()).isEqualTo(n);
    }

    @When("the foreman submits a self batch of two reports")
    public void foremanSubmitsSelfBatch() {
        long foremanMemberId = reportFixture.projectMemberId(foreman.id(), requireProject());
        reportFixture.linkMemberWorkCategory(foremanMemberId, workItem.workCategoryId());
        loginAs(foreman);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item(foreman.id(), List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"))));
        items.add(item(foreman.id(), List.of(row(estimate.roomWithPlanBId(), "2", "ABSOLUTE"))));
        last = api().createBatch(items);
        rememberBatchCreated(last);
        backToAdmin();
    }

    private NotificationApiHelper notifs;
    private long submittedNotifsBefore;

    private NotificationApiHelper notifs() {
        if (notifs == null) {
            notifs = NotificationApiHelper.create();
            world.registerTeardown(() -> notifs.close());
        }
        return notifs;
    }

    /** The foreman's current count of workReport.submitted notifications (acting-user-scoped). */
    private long countForemanSubmitted() {
        WorkReportApiHelper auth = WorkReportApiHelper.create();
        try {
            String token = auth.loginAs(foreman.email(), foreman.password());
            long count = 0;
            for (String type : notifs().listTypes(token)) {
                if ("notification.workReport.submitted".equals(type)) {
                    count++;
                }
            }
            return count;
        } finally {
            auth.close();
        }
    }

    @When("the foreman submitted-notification count is recorded")
    public void recordForemanSubmitted() {
        submittedNotifsBefore = countForemanSubmitted();
    }

    @Then("the foreman received {int} new submitted notifications")
    public void foremanReceivedSubmitted(int n) {
        long after = countForemanSubmitted();
        assertThat(after - submittedNotifsBefore)
                .as("new submitted notifications for the foreman").isEqualTo((long) n);
    }

    @When("the manager submits a batch")
    public void managerSubmitsBatch() {
        loginAs(manager);
        List<Map<String, Object>> items = List.of(item(worker.id(),
                List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"))));
        last = api().createBatch(items);
        rememberBatchCreated(last);
        backToAdmin();
    }

    @When("the worker submits a batch for another worker")
    public void workerSubmitsBatchForAnother() {
        loginAs(worker);
        List<Map<String, Object>> items = List.of(item(worker2.id(),
                List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"))));
        last = api().createBatch(items);
        rememberBatchCreated(last);
        backToAdmin();
    }

    @When("a batch is submitted without a token")
    public void batchNoToken() {
        api().clearAuth();
        List<Map<String, Object>> items = List.of(item(worker.id(),
                List.of(row(estimate.roomWithPlanAId(), "3", "ABSOLUTE"))));
        last = api().createBatchNoToken(items);
        backToAdmin();
    }

    @Then("the matrix API responds with HTTP {int}")
    public void matrixApiRespondsWith(int code) {
        assertThat(last).as("a matrix API call must have been made").isNotNull();
        assertThat(last.status()).as("HTTP status (body: %s)", last.text()).isEqualTo(code);
    }
}
