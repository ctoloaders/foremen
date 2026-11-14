package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.foremen.dao.PositionApprovalOptionDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.procurement.approval.PositionApprovalService;
import com.foremen.service.procurement.approval.PositionApprovalService.AnalogCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.Decider;
import com.foremen.service.procurement.approval.PositionApprovalService.SendChoiceCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.SendCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.SuggestCommand;

/**
 * FOR-11-02 task 8.2 - controllers, ABAC and confidentiality of {@code /api/position-approvals/client} and
 * {@code /api/position-approvals/staff} through MockMvc with the real filter chain and
 * {@code PermissionInterceptor}.
 *
 * <p>The role matrix of changeset 235 is rebuilt per test (Liquibase is off in this profile): ADMIN (bypass),
 * MANAGER CRUDA, BUYER R, CLIENT R+APPROVE, WORKER none. The test-only CLIENT grant on PURCHASE_POSITIONS of
 * the base fixture is revoked so that CLIENT reaching {@code /api/purchase-positions} can be asserted as 403.
 * Callers authenticate with a {@code Long} user-id principal (the shape the JWT filter produces).
 *
 * <p>Repeatability: unique per-run data inside the rolled-back transaction of the base class.
 *
 * <p>Validates Requirements 1.2, 1.4, 3.1, 3.5, 3.6, 4.5, 4.8, 5.3, 22.2.
 */
class PositionApprovalControllersAbacIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String CLIENT = "/api/position-approvals/client";
    private static final String STAFF = "/api/position-approvals/staff";
    private static final String SELLER_SENTINEL = "SENTINEL_SELLER_ZX9";
    private static final List<String> NUMERIC_SENTINELS = List.of("4141.41", "4242.42", "3131.31", "7777.77");
    private static final List<String> FIELD_SENTINELS = List.of("internalunitpurchaseprice", "purchaseprice",
            "seller", "margin", "deliverycost", "actualpurchase");
    private static final List<String> ROLES = List.of("ADMIN", "MANAGER", "BUYER", "CLIENT", "WORKER");

    @Autowired
    private PositionApprovalService service;
    @Autowired
    private PositionApprovalOptionDao optionDao;

    private ProjectEntity project;
    private ProjectEntity foreignProject;
    private Map<String, UserEntity> users;
    private UserEntity foreignClient;
    private UserEntity foreignManager;
    private MaterialSellerEntity seller;

    @BeforeEach
    void setUpMatrix() {
        em.createNativeQuery("CREATE UNIQUE INDEX IF NOT EXISTS uq_par_open_per_position "
                + "ON position_approval_rounds (position_id) WHERE status = 'OPEN' AND kind <> 'CLIENT_SUGGESTION'")
                .executeUpdate();
        // Revoke the base fixture's test-only CLIENT READ grant on PURCHASE_POSITIONS (seed has none).
        List<RoleResourceEntity> clientRows = em.createQuery(
                "select rr from RoleResourceEntity rr where rr.role.code = 'CLIENT'", RoleResourceEntity.class)
                .getResultList();
        for (RoleResourceEntity rr : clientRows) {
            rr.getRole().getRoleResources().remove(rr);
            em.remove(rr);
        }
        em.flush();

        OperationEntity create = operation("CREATE");
        OperationEntity read = operation("READ");
        OperationEntity update = operation("UPDATE");
        OperationEntity delete = operation("DELETE");
        OperationEntity approve = operation("APPROVE");
        ResourceEntity approvals = resource("POSITION_APPROVALS");
        grant("MANAGER", approvals, create, read, update, delete, approve);
        grant("BUYER", approvals, read);
        grant("CLIENT", approvals, read, approve);
        em.flush();
        for (String code : List.of("ADMIN", "MANAGER", "BUYER", "CLIENT", "WORKER")) {
            permissionCache.invalidate(code);
        }

        project = project(ProjectStatus.ACTIVE);
        foreignProject = project(ProjectStatus.ACTIVE);
        users = new java.util.LinkedHashMap<>();
        users.put("ADMIN", user("ADMIN"));
        users.put("MANAGER", memberAs("MANAGER", "WORKER", project));
        users.put("BUYER", memberAs("BUYER", "WORKER", project));
        users.put("CLIENT", memberAs("CLIENT", "CLIENT", project));
        users.put("WORKER", memberAs("WORKER", "WORKER", project));
        foreignClient = memberAs("CLIENT", "CLIENT", foreignProject);
        foreignManager = memberAs("MANAGER", "WORKER", foreignProject);

        seller = new MaterialSellerEntity();
        seller.setCode("sentinel_" + unique());
        seller.setNameRU(SELLER_SENTINEL);
        seller.setNamePL(SELLER_SENTINEL);
        seller.setActive(true);
        em.persist(seller);
        em.flush();
    }

    // ------------------------------------------------------------------ fixtures

    private UserEntity memberAs(String globalRole, String projectRole, ProjectEntity p) {
        UserEntity u = user(globalRole);
        ProjectMemberEntity pm = new ProjectMemberEntity();
        pm.setUser(u);
        pm.setProjectId(p.getId());
        pm.setProjectRole(role(projectRole));
        pm.setAssignmentStatus(AssignmentStatus.ACTIVE);
        em.persist(pm);
        em.flush();
        projectAccessCache.invalidate(u.getId());
        return u;
    }

    private static RequestPostProcessor auth(UserEntity user, String roleCode) {
        return authentication(new UsernamePasswordAuthenticationToken(user.getId(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + roleCode))));
    }

    private RequestPostProcessor as(String roleCode) {
        return auth(users.get(roleCode), roleCode);
    }

    private PurchasePositionEntity agreed(ProjectEntity p) {
        FinishingMaterialEntity m = material(p);
        m.setSeller(seller);
        PurchasePositionEntity pos = position(p, m, SourcingPath.AGREED, PositionStatus.PLANNED);
        pos.setPlannedPurchasePrice(new BigDecimal("4141.41"));
        pos.setActualPurchasePrice(new BigDecimal("4242.42"));
        pos.setDeliveryCostShare(new BigDecimal("3131.31"));
        em.flush();
        return pos;
    }

    private static LocalDate deadline() {
        return LocalDate.now().plusDays(7);
    }

    /** MockMvc post-processors leave the last caller in the test thread's context; setup calls run as ADMIN. */
    private void adminContext() {
        UserEntity admin = users.get("ADMIN");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin.getId(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private Long send(PurchasePositionEntity pos) {
        adminContext();
        Long id = service.send(new SendCommand(List.of(pos.getId()), deadline(), "note")).get(0);
        em.flush();
        return id;
    }

    /** A fresh AGREED position with one open PROPOSAL round in the main project. */
    private Long[] openRound() {
        PurchasePositionEntity pos = agreed(project);
        return new Long[] {pos.getId(), send(pos)};
    }

    private Decider clientDecider() {
        adminContext();
        return Decider.client(users.get("CLIENT").getId());
    }

    private int status(MockHttpServletRequestBuilder request, RequestPostProcessor caller) throws Exception {
        em.flush();
        return mockMvc.perform(request.with(caller)).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private Long optionOf(Long roundId) {
        em.flush();
        return optionDao.findByRoundIdOrderBySortOrderAscIdAsc(roundId).get(0).getId();
    }

    /** Runs {@code request} once per role (fresh fixtures per call) and collects deviations. */
    private void assertMatrix(String name, Supplier<MockHttpServletRequestBuilder> request,
                              Map<String, Integer> expected, List<String> violations) throws Exception {
        for (String role : ROLES) {
            int actual = status(request.get(), as(role));
            if (actual != expected.get(role)) {
                violations.add(name + " as " + role + ": status " + actual + ", expected " + expected.get(role));
            }
        }
    }

    private static Map<String, Integer> exp(int admin, int manager, int buyer, int client, int worker) {
        return Map.of("ADMIN", admin, "MANAGER", manager, "BUYER", buyer, "CLIENT", client, "WORKER", worker);
    }

    // ------------------------------------------------------------------ client endpoints

    @Test
    @DisplayName("client reads: ADMIN/MANAGER/BUYER/CLIENT 200 (client view), WORKER 403")
    void clientReadsMatrix() throws Exception {
        Long[] r = openRound();
        List<String> violations = new ArrayList<>();
        Map<String, Integer> reads = exp(200, 200, 200, 200, 403);
        assertMatrix("GET client list", () -> get(CLIENT + "/projects/" + project.getId()), reads, violations);
        assertMatrix("GET client summary", () -> get(CLIENT + "/projects/" + project.getId() + "/summary"), reads,
                violations);
        assertMatrix("GET client position", () -> get(CLIENT + "/positions/" + r[0]), reads, violations);
        assertThat(violations).as("ABAC matrix violations").isEmpty();
    }

    @Test
    @DisplayName("client decisions: only a CLIENT member decides (204); ADMIN/MANAGER are not client members (404); BUYER/WORKER 403")
    void clientDecisionsMatrix() throws Exception {
        List<String> violations = new ArrayList<>();
        Map<String, Integer> decide = exp(404, 404, 403, 204, 403);
        // Each role gets a fresh round; CLIENT's call is the successful one.
        for (String role : ROLES) {
            Long[] a = openRound();
            Long[] b = openRound();
            Long[] c = openRound();
            Long[] d = openRound();
            check(violations, "POST accept", role, decide, status(
                    json(post(CLIENT + "/rounds/" + a[1] + "/accept"),
                            "{\"optionId\": " + optionOf(a[1]) + ", \"expectedUnitSalePrice\": 100.00}"),
                    as(role)));
            check(violations, "POST reject", role, decide, status(
                    json(post(CLIENT + "/rounds/" + b[1] + "/reject"), "{\"comment\": \"too expensive\"}"),
                    as(role)));
            check(violations, "POST suggest", role, decide, status(
                    json(post(CLIENT + "/positions/" + c[0] + "/suggest"),
                            "{\"link\": \"https://shop.example.com/x\", \"comment\": \"this\"}"), as(role)));
            check(violations, "POST client-own", role, decide, status(
                    json(post(CLIENT + "/positions/" + d[0] + "/client-own"), "{\"comment\": \"mine\"}"),
                    as(role)));
        }
        assertThat(violations).as("ABAC matrix violations").isEmpty();
    }

    private static void check(List<String> violations, String name, String role, Map<String, Integer> expected,
                              int actual) {
        if (actual != expected.get(role)) {
            violations.add(name + " as " + role + ": status " + actual + ", expected " + expected.get(role));
        }
    }

    // ------------------------------------------------------------------ staff endpoints

    @Test
    @DisplayName("staff reads: ADMIN/MANAGER/BUYER 200; CLIENT 403 (audience), WORKER 403")
    void staffReadsMatrix() throws Exception {
        Long[] r = openRound();
        List<String> violations = new ArrayList<>();
        Map<String, Integer> reads = exp(200, 200, 200, 403, 403);
        assertMatrix("GET staff list", () -> get(STAFF + "/projects/" + project.getId()), reads, violations);
        assertMatrix("GET staff rounds", () -> get(STAFF + "/positions/" + r[0] + "/rounds"), reads, violations);
        assertThat(violations).as("ABAC matrix violations").isEmpty();
    }

    @Test
    @DisplayName("staff writes: send/analog/withdraw/deadline/decline need ADMIN or MANAGER; BUYER, CLIENT, WORKER 403")
    void staffWritesMatrix() throws Exception {
        List<String> violations = new ArrayList<>();
        Map<String, Integer> write = exp(200, 200, 403, 403, 403);
        Map<String, Integer> noContent = exp(204, 204, 403, 403, 403);
        for (String role : ROLES) {
            PurchasePositionEntity toSend = agreed(project);
            check(violations, "POST send", role, write, status(
                    json(post(STAFF + "/send"), "{\"positionIds\": [" + toSend.getId() + "]}"), as(role)));

            Long[] analog = openRound();
            FinishingMaterialEntity analogMaterial = material(project);
            check(violations, "POST propose-analog", role, write, status(
                    json(post(STAFF + "/positions/" + analog[0] + "/propose-analog"),
                            "{\"materialId\": " + analogMaterial.getId() + ", \"unitSalePrice\": 90.00}"),
                    as(role)));

            Long[] withdraw = openRound();
            check(violations, "POST withdraw", role, noContent,
                    status(post(STAFF + "/rounds/" + withdraw[1] + "/withdraw"), as(role)));

            Long[] deadline = openRound();
            check(violations, "POST deadline", role, noContent, status(
                    json(post(STAFF + "/rounds/" + deadline[1] + "/deadline"),
                            "{\"decisionDeadline\": \"" + LocalDate.now().plusDays(14) + "\"}"), as(role)));

            Long[] sugg = openRound();
            Long suggestion = service.suggest(sugg[0], new SuggestCommand("https://shop.example.com/y", null),
                    clientDecider());
            check(violations, "POST suggestions decline", role, noContent, status(
                    json(post(STAFF + "/suggestions/" + suggestion + "/decline"), "{\"note\": \"not available\"}"),
                    as(role)));

            PurchasePositionEntity pkg = position(project, SourcingPath.PACKAGE, PositionStatus.PLANNED);
            FinishingMaterialEntity a = material(project);
            FinishingMaterialEntity b = material(project);
            check(violations, "POST send-choice", role, write, status(
                    json(post(STAFF + "/send-choice"), "{\"positionId\": " + pkg.getId() + ", \"materialIds\": ["
                            + a.getId() + ", " + b.getId() + "]}"), as(role)));
        }
        assertThat(violations).as("ABAC matrix violations").isEmpty();
    }

    @Test
    @DisplayName("on-behalf endpoints: only MANAGER/ADMIN succeed; CLIENT gets 403 (onBehalf.forbidden), BUYER/WORKER 403")
    void onBehalfOnlyManagerAndAdmin() throws Exception {
        List<String> violations = new ArrayList<>();
        Map<String, Integer> onBehalf = exp(204, 204, 403, 403, 403);
        for (String role : ROLES) {
            Long[] accept = openRound();
            check(violations, "POST decide-on-behalf accept", role, onBehalf, status(
                    json(post(STAFF + "/rounds/" + accept[1] + "/decide-on-behalf"),
                            "{\"decision\": \"ACCEPT\", \"expectedUnitSalePrice\": 100.00, \"comment\": \"phone\"}"),
                    as(role)));
            Long[] reject = openRound();
            check(violations, "POST decide-on-behalf reject", role, onBehalf, status(
                    json(post(STAFF + "/rounds/" + reject[1] + "/decide-on-behalf"),
                            "{\"decision\": \"REJECT\", \"comment\": \"phone\"}"), as(role)));
            Long[] own = openRound();
            check(violations, "POST client-own-on-behalf", role, onBehalf, status(
                    json(post(STAFF + "/positions/" + own[0] + "/client-own-on-behalf"),
                            "{\"comment\": \"phone\"}"), as(role)));
        }
        assertThat(violations).as("ABAC matrix violations").isEmpty();
    }

    @Test
    @DisplayName("CLIENT cannot reach /api/purchase-positions or /api/purchase-requests")
    void clientCannotReachProcurementApi() throws Exception {
        assertThat(status(get("/api/purchase-positions/planner"), as("CLIENT"))).isEqualTo(403);
        assertThat(status(get("/api/purchase-positions/board"), as("CLIENT"))).isEqualTo(403);
        assertThat(status(get("/api/purchase-positions"), as("CLIENT"))).isEqualTo(403);
        assertThat(status(get("/api/purchase-requests"), as("CLIENT"))).isEqualTo(403);
    }

    @Test
    @DisplayName("unauthenticated callers are rejected on client and staff endpoints")
    void unauthenticatedRejected() throws Exception {
        for (String url : List.of(CLIENT + "/projects/" + project.getId(), STAFF + "/projects/" + project.getId())) {
            int s = mockMvc.perform(get(url).with(org.springframework.security.test.web.servlet.request
                    .SecurityMockMvcRequestPostProcessors.anonymous())).andReturn().getResponse().getStatus();
            assertThat(s).as(url).isIn(401, 403);
        }
    }

    // ------------------------------------------------------------------ project confinement

    @Test
    @DisplayName("a CLIENT or MANAGER of another project cannot read or decide in this project")
    void projectConfinement() throws Exception {
        Long[] r = openRound();
        PurchasePositionEntity own = agreed(project);
        for (RequestPostProcessor foreign : List.of(auth(foreignClient, "CLIENT"), auth(foreignManager, "MANAGER"))) {
            assertThat(status(get(CLIENT + "/projects/" + project.getId()), foreign)).isIn(403, 404);
            assertThat(status(get(CLIENT + "/projects/" + project.getId() + "/summary"), foreign)).isIn(403, 404);
            assertThat(status(get(CLIENT + "/positions/" + r[0]), foreign)).isEqualTo(404);
            assertThat(status(json(post(CLIENT + "/rounds/" + r[1] + "/accept"),
                    "{\"optionId\": " + optionOf(r[1]) + ", \"expectedUnitSalePrice\": 100.00}"), foreign))
                    .isIn(403, 404);
            assertThat(status(json(post(CLIENT + "/rounds/" + r[1] + "/reject"), "{\"comment\": \"x\"}"),
                    foreign)).isIn(403, 404);
            assertThat(status(json(post(CLIENT + "/positions/" + r[0] + "/client-own"), "{}"), foreign))
                    .isIn(403, 404);
        }
        RequestPostProcessor fm = auth(foreignManager, "MANAGER");
        assertThat(status(get(STAFF + "/projects/" + project.getId()), fm)).isIn(403, 404);
        assertThat(status(get(STAFF + "/positions/" + r[0] + "/rounds"), fm)).isEqualTo(404);
        assertThat(status(json(post(STAFF + "/send"), "{\"positionIds\": [" + own.getId() + "]}"), fm))
                .isIn(403, 404);
        assertThat(status(post(STAFF + "/rounds/" + r[1] + "/withdraw"), fm)).isIn(403, 404);
        assertThat(status(json(post(STAFF + "/rounds/" + r[1] + "/decide-on-behalf"),
                "{\"decision\": \"REJECT\", \"comment\": \"x\"}"), fm)).isIn(403, 404);
        // The round was never touched by the foreign callers.
        assertThat(status(get(CLIENT + "/positions/" + r[0]), as("CLIENT"))).isEqualTo(200);
    }

    @Test
    @DisplayName("positions never sent for approval are invisible to the client (list and single)")
    void unsentPositionsInvisible() throws Exception {
        PurchasePositionEntity unsent = agreed(project);
        Long[] sent = openRound();
        em.flush();
        MvcResult list = mockMvc.perform(get(CLIENT + "/projects/" + project.getId()).with(as("CLIENT")))
                .andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        String body = list.getResponse().getContentAsString();
        assertThat(body).contains("\"positionId\":" + sent[0]).doesNotContain("\"positionId\":" + unsent.getId());
        assertThat(status(get(CLIENT + "/positions/" + unsent.getId()), as("CLIENT"))).isEqualTo(404);
    }

    // ------------------------------------------------------------------ confidentiality

    @Test
    @DisplayName("sentinel purchase price, delivery cost, seller and internal price never appear in any client endpoint JSON")
    void confidentialitySentinel() throws Exception {
        // PROPOSAL superseded by an ANALOG round carrying an internal purchase price sentinel (history + open).
        PurchasePositionEntity p1 = agreed(project);
        send(p1);
        FinishingMaterialEntity analog = material(project);
        analog.setSeller(seller);
        em.flush();
        service.proposeAnalog(p1.getId(), new AnalogCommand(analog.getId(), new BigDecimal("90.00"),
                new BigDecimal("7777.77"), deadline(), "cheaper", null));
        // A client suggestion next to the open round.
        service.suggest(p1.getId(), new SuggestCommand("https://shop.example.com/z", "mine"), clientDecider());
        // A PACKAGE_CHOICE round with seller-tagged options.
        PurchasePositionEntity pkg = position(project, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        pkg.setPlannedPurchasePrice(new BigDecimal("4141.41"));
        pkg.setActualPurchasePrice(new BigDecimal("4242.42"));
        pkg.setDeliveryCostShare(new BigDecimal("3131.31"));
        FinishingMaterialEntity a = material(project);
        FinishingMaterialEntity b = material(project);
        a.setSeller(seller);
        b.setSeller(seller);
        em.flush();
        service.sendChoice(new SendChoiceCommand(pkg.getId(), List.of(a.getId(), b.getId()), deadline(), "pick"));
        // An accepted position (money visible to the client) with sentinel purchase side.
        PurchasePositionEntity p3 = agreed(project);
        service.accept(send(p3), new PositionApprovalService.AcceptCommand(null, new BigDecimal("100.00"), null),
                clientDecider());
        em.flush();

        // Positive control: the staff view does carry the internal sentinel, so the negative checks mean something.
        String staff = body(get(STAFF + "/projects/" + project.getId()), as("MANAGER"));
        assertThat(staff).contains("7777.77");

        List<String> clientBodies = new ArrayList<>();
        for (String role : List.of("CLIENT", "MANAGER", "BUYER", "ADMIN")) {
            clientBodies.add(body(get(CLIENT + "/projects/" + project.getId()), as(role)));
            clientBodies.add(body(get(CLIENT + "/projects/" + project.getId()).param("onlyPending", "true"),
                    as(role)));
            clientBodies.add(body(get(CLIENT + "/projects/" + project.getId() + "/summary"), as(role)));
            for (PurchasePositionEntity p : List.of(p1, pkg, p3)) {
                clientBodies.add(body(get(CLIENT + "/positions/" + p.getId()), as(role)));
            }
        }
        assertThat(clientBodies).isNotEmpty().allSatisfy(json -> {
            assertThat(json).isNotBlank();
            for (String sentinel : NUMERIC_SENTINELS) {
                assertThat(json).doesNotContain(sentinel);
            }
            assertThat(json).doesNotContain(SELLER_SENTINEL);
            String lower = json.toLowerCase(Locale.ROOT);
            for (String field : FIELD_SENTINELS) {
                assertThat(lower).as("field token " + field).doesNotContain(field);
            }
        });
        // The list actually returned the sentinel-bearing positions (the check is not vacuous).
        assertThat(clientBodies.get(0)).contains("\"positionId\":" + p1.getId())
                .contains("\"positionId\":" + pkg.getId()).contains("\"positionId\":" + p3.getId());
    }

    private String body(MockHttpServletRequestBuilder request, RequestPostProcessor caller) throws Exception {
        em.flush();
        MvcResult result = mockMvc.perform(request.with(caller)).andReturn();
        assertThat(result.getResponse().getStatus()).as(request.toString()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }
}
