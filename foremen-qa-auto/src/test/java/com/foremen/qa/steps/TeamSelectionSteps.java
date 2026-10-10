package com.foremen.qa.steps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.qa.fixtures.TestUser;
import com.foremen.qa.fixtures.TestUserFixture;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.TeamApiHelper;
import com.foremen.qa.support.TeamApiHelper.Resp;
import com.foremen.qa.support.World;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * FOR-QA-AUTO-09 steps for the FOR-05-08-adjacent FOR-05-09 team-selection API slice.
 *
 * <p>Seeds users and projects via the admin API / direct-DB fixture, drives the project-members
 * (Team) API, and asserts the HTTP contract. Repeatability: {@link DataGen} run-id + LIFO teardown
 * registered in {@link World}; seed roles/resources/worker-types are only read.
 */
public class TeamSelectionSteps {

    private final World world;
    private final TestUserFixture userFixture = new TestUserFixture();

    private TeamApiHelper team;

    // Scenario fixture state.
    private Long projectId;
    private Long lockedProjectId;
    private Long emptyProjectId;
    private Long managerUserId;
    private Long clientUserId;
    private final Map<String, TestUser> users = new LinkedHashMap<>();
    private Long lastWorkerUserId;
    private Resp last;

    public TeamSelectionSteps(World world) {
        this.world = world;
    }

    private TeamApiHelper team() {
        if (team == null) {
            team = TeamApiHelper.create();
            team.loginAdmin();
            world.registerTeardown(() -> {
                if (team != null) {
                    team.close();
                    team = null;
                }
            });
        }
        return team;
    }

    private ApiHelper adminApi() {
        ApiHelper api = world.api();
        if (api.accessToken() == null) {
            api.loginAdmin();
        }
        return api;
    }

    /** Create an ACTIVE user of the given role, remembered under a key, with teardown. */
    private TestUser createUser(String key, String roleCode) {
        TestUser u = userFixture.createActiveUser(roleCode);
        users.put(key, u);
        world.registerTeardown(() -> userFixture.deleteUserAndResources(u));
        return u;
    }

    private String lastProjectName;

    /**
     * Create an editable project owned by the given manager (and optional client) with admin-staff
     * members. The project is always created in an Editable_Status (the create endpoint rejects a
     * locked status); callers that need a Locked_Status transition it afterwards via PUT.
     */
    private long createProjectWith(TestUser manager, TestUser client) {
        ApiHelper api = adminApi();
        List<Map<String, Object>> members = new ArrayList<>();
        if (manager != null) {
            long managerRoleId = api.resolveRoleIdByCode("MANAGER");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", manager.id());
            m.put("projectRoleId", managerRoleId);
            members.add(m);
        }
        if (client != null) {
            long clientRoleId = api.resolveRoleIdByCode("CLIENT");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("userId", client.id());
            c.put("projectRoleId", clientRoleId);
            members.add(c);
        }
        String name = DataGen.nextProjectName();
        this.lastProjectName = name;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (!members.isEmpty()) {
            body.put("members", members);
        }
        long id = api.createProject(body);
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.deleteProject(id);
            } catch (RuntimeException ignored) {
            }
        });
        return id;
    }

    // ---- Setup ----

    @Given("a team project with a manager and a client exists")
    public void aTeamProjectWithAManagerAndAClientExists() {
        team();
        TestUser manager = createUser("MANAGER", "MANAGER");
        TestUser client = createUser("CLIENT", "CLIENT");
        this.managerUserId = manager.id();
        this.clientUserId = client.id();
        this.projectId = createProjectWith(manager, client);
    }

    @Given("a locked project in status {string} exists")
    public void aLockedProjectExists(String status) {
        team();
        TestUser manager = createUser("MANAGER_LOCK", "MANAGER");
        TestUser client = createUser("CLIENT_LOCK", "CLIENT");
        long id = createProjectWith(manager, client);
        // The create endpoint rejects a locked status, so transition the editable project via PUT.
        Resp put = team().updateProjectStatus(id, lastProjectName, status);
        assertThat(put.status()).as("transition project %s to %s (body %s)", id, status, put.text())
                .isEqualTo(200);
        this.lockedProjectId = id;
    }

    @Given("an empty project with no members exists")
    public void anEmptyProjectExists() {
        team();
        this.emptyProjectId = createProjectWith(null, null);
    }

    @Given("a candidate user with role {string} exists")
    public void aCandidateUserWithRoleExists(String role) {
        team();
        createUser(role + "_CAND", role);
    }

    private TestUser user(String key) {
        TestUser u = users.get(key);
        if (u == null) {
            throw new IllegalStateException("No test user under key " + key);
        }
        return u;
    }

    private long requireProject() {
        if (projectId == null) {
            throw new IllegalStateException("No project in scope; a project Given must run first.");
        }
        return projectId;
    }

    // ---- ABAC / scope ----

    @When("I list team members without a token")
    public void iListWithoutToken() {
        team().clearAuth();
        last = team().listMembers(requireProject());
        team().loginAdmin();
    }

    @When("a {string} user lists the team members")
    public void aRoleUserListsMembers(String role) {
        TestUser u = createUser(role + "_READER", role);
        team().loginAs(u.email(), u.password());
        last = team().listMembers(requireProject());
        team().loginAdmin();
    }

    @When("a {string} user tries to assign a member")
    public void aRoleUserTriesToAssign(String role) {
        TestUser reader = users.getOrDefault(role + "_READER", null);
        if (reader == null) {
            reader = createUser(role + "_READER", role);
        }
        TestUser target = createUser(role + "_TARGET2", "FOREMAN");
        team().loginAs(reader.email(), reader.password());
        last = team().assign(target.id(), requireProject());
        team().loginAdmin();
    }

    // ---- List ----

    @When("I list the team members")
    public void iListTheTeamMembers() {
        last = team().listMembers(requireProject());
    }

    @When("I list the members of the empty project")
    public void iListEmptyProject() {
        last = team().listMembers(emptyProjectId);
    }

    @When("I list team members with Accept-Language {string}")
    public void iListWithLanguage(String lang) {
        last = team().listMembers(requireProject(), lang);
    }

    @When("I list team members with raw projectId {string}")
    public void iListWithRawProjectId(String raw) {
        last = "<none>".equals(raw) ? team().listMembersRaw(null) : team().listMembersRaw(raw);
    }

    // ---- Assign ----

    @When("I assign a {string} user to the project")
    public void iAssignRoleUser(String role) {
        TestUser u = createUser(role + "_ASSIGN", role);
        last = team().assign(u.id(), requireProject());
    }

    @When("I assign the same {string} user again")
    public void iAssignSameAgain(String role) {
        TestUser u = user(role + "_ASSIGN");
        last = team().assign(u.id(), requireProject());
    }

    @When("I assign a non-existent user to the project")
    public void iAssignNonExistentUser() {
        last = team().assign(99_000_000L + DataGen.nextSeq(), requireProject());
    }

    @When("I assign with a missing userId")
    public void iAssignMissingUserId() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", requireProject());
        last = team().assignRaw(body);
    }

    @When("I assign with a non-positive userId")
    public void iAssignNonPositiveUserId() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", 0);
        body.put("projectId", requireProject());
        last = team().assignRaw(body);
    }

    @When("I assign a {string} user with a mismatched project role")
    public void iAssignWithMismatchedRole(String role) {
        TestUser u = createUser(role + "_MISMATCH", role);
        long clientRoleId = adminApi().resolveRoleIdByCode("CLIENT");
        last = team().assign(u.id(), requireProject(), clientRoleId, null, null);
    }

    @When("I assign an {string} role user to the project")
    public void iAssignAdminRoleUser(String role) {
        TestUser u = createUser(role + "_NOTASSIGN", role);
        last = team().assign(u.id(), requireProject());
    }

    // ---- Remove ----

    @When("I remove the {string} user from the project")
    public void iRemoveRoleUser(String role) {
        TestUser u = user(role + "_ASSIGN");
        last = team().remove(u.id(), requireProject());
    }

    @When("I remove a non-member user from the project")
    public void iRemoveNonMember() {
        TestUser u = createUser("NONMEMBER", "FOREMAN");
        last = team().remove(u.id(), requireProject());
    }

    @When("I remove with a missing userId")
    public void iRemoveMissingUserId() {
        last = team().removeRaw(null, String.valueOf(requireProject()));
    }

    @When("I remove with a non-numeric userId")
    public void iRemoveNonNumericUserId() {
        last = team().removeRaw("abc", String.valueOf(requireProject()));
    }

    @When("I remove the last active manager")
    public void iRemoveLastManager() {
        last = team().remove(managerUserId, requireProject());
    }

    @When("I remove the last active client")
    public void iRemoveLastClient() {
        last = team().remove(clientUserId, requireProject());
    }

    // ---- Status / worker type / tags (PATCH) ----

    @When("I deactivate the {string} user")
    public void iDeactivateRoleUser(String role) {
        TestUser u = user(role + "_ASSIGN");
        last = team().patchStatus(u.id(), requireProject(), "INACTIVE");
    }

    @When("I reactivate the {string} user")
    public void iReactivateRoleUser(String role) {
        TestUser u = user(role + "_ASSIGN");
        last = team().patchStatus(u.id(), requireProject(), "ACTIVE");
    }

    @When("I deactivate the last active manager")
    public void iDeactivateLastManager() {
        last = team().patchStatus(managerUserId, requireProject(), "INACTIVE");
    }

    @When("I patch an invalid assignment status for the {string} user")
    public void iPatchInvalidStatus(String role) {
        TestUser u = user(role + "_ASSIGN");
        last = team().patchStatus(u.id(), requireProject(), "FOO");
    }

    @When("I set a worker type on the {string} worker")
    public void iSetWorkerType(String role) {
        TestUser u = user(role + "_ASSIGN");
        long wt = team().resolveWorkerTypeId("BASE");
        last = team().patchWorkerType(u.id(), requireProject(), wt);
    }

    @When("I set a worker type on a non-worker {string} member")
    public void iSetWorkerTypeOnNonWorker(String role) {
        TestUser u = user(role + "_ASSIGN");
        long wt = team().resolveWorkerTypeId("BASE");
        last = team().patchWorkerType(u.id(), requireProject(), wt);
    }

    @When("I set a non-existent worker type on the {string} worker")
    public void iSetBadWorkerType(String role) {
        TestUser u = user(role + "_ASSIGN");
        last = team().patchWorkerType(u.id(), requireProject(), 99_000_000L + DataGen.nextSeq());
    }

    @When("I replace the tags of the {string} member with {string}")
    public void iReplaceTags(String role, String csv) {
        TestUser u = user(role + "_ASSIGN");
        last = team().patchTags(u.id(), requireProject(), splitCsv(csv));
    }

    @When("I patch a worker type for a non-member user")
    public void iPatchWorkerTypeNonMember() {
        TestUser u = createUser("NONMEMBER_WT", "WORKER");
        long wt = team().resolveWorkerTypeId("BASE");
        last = team().patchWorkerType(u.id(), requireProject(), wt);
    }

    // ---- Candidates ----

    @When("I search candidates")
    public void iSearchCandidates() {
        last = team().candidates(requireProject(), Map.of());
    }

    @When("I search candidates with role {string}")
    public void iSearchCandidatesRole(String role) {
        last = team().candidates(requireProject(), Map.of("role", role));
    }

    @When("I search candidates with an invalid role")
    public void iSearchCandidatesInvalidRole() {
        last = team().candidates(requireProject(), Map.of("role", "NOPE"));
    }

    @When("I search candidates with size {string}")
    public void iSearchCandidatesSize(String size) {
        last = team().candidates(requireProject(), Map.of("size", size));
    }

    @When("a {string} user searches candidates")
    public void aRoleUserSearchesCandidates(String role) {
        TestUser u = createUser(role + "_CANDREADER", role);
        team().loginAs(u.email(), u.password());
        last = team().candidates(requireProject(), Map.of());
        team().loginAdmin();
    }

    // ---- Projects list ----

    @When("I list the projects of the manager")
    public void iListProjectsOfManager() {
        last = team().listProjects(managerUserId);
    }

    @When("I list the projects of a non-existent user")
    public void iListProjectsNonExistent() {
        last = team().listProjects(99_000_000L + DataGen.nextSeq());
    }

    // ---- Readiness ----

    @When("I read the team readiness")
    public void iReadReadiness() {
        last = team().readiness(requireProject());
    }

    @When("I assign a {string} user and read readiness")
    public void iAssignForemanAndReadReadiness(String role) {
        TestUser u = createUser(role + "_READY", role);
        Resp assign = team().assign(u.id(), requireProject());
        assertThat(assign.status()).as("assign %s before readiness", role).isEqualTo(201);
        last = team().readiness(requireProject());
    }

    @When("a {string} user reads the team readiness")
    public void aRoleUserReadsReadiness(String role) {
        TestUser u = createUser(role + "_READYREADER", role);
        team().loginAs(u.email(), u.password());
        last = team().readiness(requireProject());
        team().loginAdmin();
    }

    // ---- Client flow ----

    @When("I register a new client on the project")
    public void iRegisterClient() {
        String email = "client+" + DataGen.runId() + "+" + DataGen.nextSeq() + "@example.com";
        last = team().registerClient("Client " + DataGen.runId(), email, requireProject(),
                List.of("vip"));
        registerClientCleanup(email);
    }

    @When("I register a client with a duplicate email")
    public void iRegisterDuplicateClient() {
        String email = "client+" + DataGen.runId() + "+" + DataGen.nextSeq() + "@example.com";
        Resp first = team().registerClient("Client " + DataGen.runId(), email, requireProject(),
                null);
        assertThat(first.status()).as("first client registration").isEqualTo(201);
        registerClientCleanup(email);
        last = team().registerClient("Client dup " + DataGen.runId(), email, requireProject(), null);
    }

    @When("I register a client with a blank name")
    public void iRegisterClientBlankName() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "");
        body.put("email", "client+" + DataGen.runId() + "+" + DataGen.nextSeq() + "@example.com");
        body.put("projectId", requireProject());
        last = team().registerClientRaw(body);
    }

    private void registerClientCleanup(String email) {
        world.registerTeardown(() -> {
            try {
                ApiHelper t = world.api();
                t.loginAdmin();
                long uid = t.resolveUserIdByEmail(email);
                t.deactivateUser(uid);
            } catch (RuntimeException ignored) {
            }
        });
    }

    // ---- Worker flow ----

    @When("I add a {string} worker with a worker type")
    public void iAddWorkerWithType(String kind) {
        long wt = team().resolveWorkerTypeId("BASE");
        String email = "worker+" + DataGen.runId() + "+" + DataGen.nextSeq() + "@example.com";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerKind", kind);
        body.put("name", "Worker " + DataGen.runId());
        body.put("email", email);
        body.put("workerTypeId", wt);
        body.put("tags", List.of("spec"));
        body.put("projectId", requireProject());
        last = team().registerWorker(body);
        if (last.ok() && last.json() != null && last.json().isJsonObject()
                && last.obj().has("userId")) {
            lastWorkerUserId = last.obj().get("userId").getAsLong();
            registerWorkerCleanup(email);
        }
    }

    @When("I add a worker without an email")
    public void iAddWorkerNoEmail() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerKind", "PERSON");
        body.put("name", "Worker NoMail " + DataGen.runId());
        body.put("projectId", requireProject());
        last = team().registerWorker(body);
        if (last.ok() && last.json() != null && last.json().isJsonObject()
                && last.obj().has("userId")) {
            lastWorkerUserId = last.obj().get("userId").getAsLong();
        }
    }

    @When("I add a worker with an invalid kind")
    public void iAddWorkerInvalidKind() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerKind", "X");
        body.put("name", "Worker " + DataGen.runId());
        body.put("projectId", requireProject());
        last = team().registerWorker(body);
    }

    @When("I add a company worker with an invalid NIP")
    public void iAddCompanyWorkerBadNip() {
        String email = "company+" + DataGen.runId() + "+" + DataGen.nextSeq() + "@example.com";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerKind", "COMPANY");
        body.put("name", "Company " + DataGen.runId());
        body.put("email", email);
        body.put("nip", "1234567890");
        body.put("projectId", requireProject());
        last = team().registerWorker(body);
    }

    @When("I invite the added worker")
    public void iInviteWorker() {
        assertThat(lastWorkerUserId).as("a worker must have been added first").isNotNull();
        last = team().inviteWorker(lastWorkerUserId);
    }

    @When("I invite a non-existent worker")
    public void iInviteNonExistentWorker() {
        last = team().inviteWorker(99_000_000L + DataGen.nextSeq());
    }

    private void registerWorkerCleanup(String email) {
        world.registerTeardown(() -> {
            try {
                ApiHelper t = world.api();
                t.loginAdmin();
                long uid = t.resolveUserIdByEmail(email);
                t.deactivateUser(uid);
            } catch (RuntimeException ignored) {
            }
        });
    }

    // ---- Locked project ----

    @When("I assign a {string} user to the locked project")
    public void iAssignToLocked(String role) {
        TestUser u = createUser(role + "_LOCKASSIGN", role);
        last = team().assign(u.id(), lockedProjectId);
    }

    @When("I list members of the locked project")
    public void iListLocked() {
        last = team().listMembers(lockedProjectId);
    }

    // ---- Assertions ----

    @Then("the Team API responds with HTTP {int}")
    public void theTeamApiRespondsWith(int code) {
        assertThat(last).as("a Team API call must have been made").isNotNull();
        assertThat(last.status()).as("Team API HTTP status (body: %s)", last.text()).isEqualTo(code);
    }

    @Then("the response is a JSON array")
    public void theResponseIsAnArray() {
        assertThat(last.json()).as("body: %s", last.text()).isNotNull();
        assertThat(last.json().isJsonArray()).as("expected JSON array, got %s", last.text()).isTrue();
    }

    @Then("the response array is empty")
    public void theResponseArrayIsEmpty() {
        assertThat(last.size()).as("array size (body: %s)", last.text()).isZero();
    }

    @Then("the response array is non-empty")
    public void theResponseArrayIsNonEmpty() {
        assertThat(last.size()).as("array size (body: %s)", last.text()).isGreaterThan(0);
    }

    @Then("the response array is sorted ascending")
    public void theResponseArrayIsSortedAscending() {
        long prev = Long.MIN_VALUE;
        for (JsonElement el : last.array()) {
            long v = el.getAsLong();
            assertThat(v).as("ascending order in %s", last.text()).isGreaterThanOrEqualTo(prev);
            prev = v;
        }
    }

    @Then("the team member list contains the {string} user")
    public void theListContainsRoleUser(String role) {
        TestUser u = user(role + "_ASSIGN");
        assertThat(last.memberByUserId(u.id()))
                .as("member for %s user %s in %s", role, u.id(), last.text()).isNotNull();
    }

    @Then("the team member list does not contain the {string} user")
    public void theListDoesNotContainRoleUser(String role) {
        TestUser u = user(role + "_ASSIGN");
        assertThat(last.memberByUserId(u.id()))
                .as("member for %s user %s should be gone", role, u.id()).isNull();
    }

    @Then("the assigned member has project role {string} and status {string}")
    public void theAssignedMemberHas(String roleCode, String status) {
        JsonObject o = last.obj();
        assertThat(TeamApiHelper.str(o, "projectRoleCode")).as("projectRoleCode").isEqualTo(roleCode);
    }

    @Then("the member view block is {string} with status {string}")
    public void theMemberViewBlock(String block, String status) {
        // The PATCH / list returns a TeamMemberView; look it up by the just-assigned user.
        JsonObject o = last.obj();
        assertThat(TeamApiHelper.str(o, "assignmentStatus")).as("assignmentStatus").isEqualTo(status);
    }

    @Then("the member view assignment status is {string}")
    public void theMemberViewStatusIs(String status) {
        assertThat(TeamApiHelper.str(last.obj(), "assignmentStatus")).as("assignmentStatus")
                .isEqualTo(status);
    }

    @Then("the member view worker type code is {string}")
    public void theMemberViewWorkerTypeCode(String code) {
        assertThat(TeamApiHelper.str(last.obj(), "workerTypeCode")).as("workerTypeCode")
                .isEqualTo(code);
    }

    @Then("the member view tags are {string}")
    public void theMemberViewTagsAre(String csv) {
        List<String> expected = splitCsv(csv);
        JsonObject o = last.obj();
        List<String> actual = new ArrayList<>();
        if (o.has("tags") && o.get("tags").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("tags")) {
                actual.add(el.getAsString());
            }
        }
        assertThat(actual).as("normalized tags").isEqualTo(expected);
    }

    @Then("the readiness state is {string}")
    public void theReadinessStateIs(String state) {
        assertThat(TeamApiHelper.str(last.obj(), "state")).as("readiness state (body %s)", last.text())
                .isEqualTo(state);
    }

    @Then("the readiness key is {string}")
    public void theReadinessKeyIs(String key) {
        assertThat(TeamApiHelper.str(last.obj(), "key")).as("readiness key").isEqualTo(key);
    }

    @Then("the readiness counts include all six roles")
    public void theReadinessCountsIncludeAllSixRoles() {
        JsonObject counts = last.obj().getAsJsonObject("counts");
        for (String role : List.of("manager", "foreman", "estimator", "worker", "financier", "client")) {
            assertThat(counts.has(role)).as("counts.%s present (body %s)", role, last.text()).isTrue();
        }
    }

    @Then("the readiness foreman count is at least {int}")
    public void theReadinessForemanCountAtLeast(int n) {
        int foreman = last.obj().getAsJsonObject("counts").get("foreman").getAsInt();
        assertThat(foreman).as("counts.foreman").isGreaterThanOrEqualTo(n);
    }

    @Then("the candidate page has a total count field")
    public void theCandidatePageHasTotal() {
        assertThat(last.obj().has("totalElements")).as("page totalElements (body %s)", last.text())
                .isTrue();
    }

    @Then("the candidate page size is at most {int}")
    public void theCandidatePageSizeAtMost(int max) {
        assertThat(last.pageContent().size()).as("candidate page content size")
                .isLessThanOrEqualTo(max);
    }

    @Then("the response is not a leaky member list")
    public void theResponseHasNoSecrets() {
        String body = last.text().toLowerCase();
        assertThat(body).as("member list must not leak secrets/cost")
                .doesNotContain("password").doesNotContain("\"token")
                .doesNotContain("tier").doesNotContain("\"cost").doesNotContain("rate");
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String s : csv.split(",")) {
            out.add(s);
        }
        return out;
    }
}
