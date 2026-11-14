package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.controller.PurchaseBoardController;
import com.foremen.controller.PurchasePlannerController;
import com.foremen.controller.PurchaseRequestController;
import com.foremen.controller.PurchaseSelectionController;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;

/**
 * FOR-11-01 task 6.5 - ABAC and visibility matrix of the planner endpoints.
 *
 * <p>Every endpoint is called as ADMIN / MANAGER / BUYER / FOREMAN / FINANCIER / CLIENT against the
 * seeded grant matrix of changeset 229 (PURCHASE_POSITIONS: MANAGER CRUD, BUYER CRU, FOREMAN R;
 * PURCHASE_REQUESTS: MANAGER CRUD, BUYER RU, FOREMAN R; FINANCIER and CLIENT have no grant). The
 * base fixture adds a test-only CLIENT READ grant, which the matrix test revokes. A caller is "allowed"
 * when the response is not 403; the endpoint-specific result (400/404/200) is covered by the 6.1-6.4 classes.
 *
 * <p>{@code GET /api/procurement/buyers} is guarded by PURCHASE_REQUESTS/READ (ADMIN, MANAGER, BUYER, FOREMAN).
 *
 * <p>Repeatability: unique per-run data inside the rolled-back transaction of the base class.
 *
 * <p>Validates Requirements 9.1, 9.2, 9.4, 9.5, 19.2.
 */
class PlannerAbacMatrixIntegrationTest extends ProcurementIntegrationTestBase {

    private static final Set<String> READERS = Set.of("ADMIN", "MANAGER", "BUYER", "FOREMAN");
    private static final Set<String> STAFF_FULL = Set.of("ADMIN", "MANAGER", "BUYER");
    private static final Set<String> CREATORS = Set.of("ADMIN", "MANAGER");
    private static final List<String> ROLES = List.of("ADMIN", "MANAGER", "BUYER", "FOREMAN", "FINANCIER", "CLIENT");

    @Autowired
    private ApplicationContext context;

    private record Endpoint(String name, Set<String> allowed,
                            Function<ProjectEntity, MockHttpServletRequestBuilder> request) {
    }

    private List<Endpoint> endpoints(long materialId) {
        List<Endpoint> list = new ArrayList<>();
        list.add(new Endpoint("GET planner", READERS, p -> get(POSITIONS + "/planner")));
        list.add(new Endpoint("GET planner/groups", READERS, p -> get(POSITIONS + "/planner/groups")));
        list.add(new Endpoint("GET planner/slice-counts", READERS, p -> get(POSITIONS + "/planner/slice-counts")));
        list.add(new Endpoint("GET scheduled-categories", READERS,
                p -> get(POSITIONS + "/scheduled-categories").param("projectId", String.valueOf(p.getId()))));
        list.add(new Endpoint("GET work-link-preview", READERS,
                p -> get(POSITIONS + "/work-link-preview").param("projectId", String.valueOf(p.getId()))
                        .param("materialId", String.valueOf(materialId))));
        list.add(new Endpoint("GET procurement/buyers", READERS, p -> get("/api/procurement/buyers")));
        list.add(new Endpoint("GET board", READERS, p -> get(POSITIONS + "/board")));
        list.add(new Endpoint("POST selection-total", STAFF_FULL,
                p -> post(POSITIONS + "/selection-total").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionIds\":[1]}")));
        list.add(new Endpoint("POST from-positions", CREATORS,
                p -> post(REQUESTS + "/from-positions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionIds\":[]}")));
        return list;
    }

    private void revokeTestOnlyClientGrant() {
        List<RoleResourceEntity> rows = em.createQuery(
                "select rr from RoleResourceEntity rr where rr.role.code = 'CLIENT'", RoleResourceEntity.class)
                .getResultList();
        for (RoleResourceEntity rr : rows) {
            rr.getRole().getRoleResources().remove(rr);
            em.remove(rr);
        }
        em.flush();
        permissionCache.invalidate("CLIENT");
    }

    private RequestPostProcessor caller(String role, ProjectEntity project, Map<String, UserEntity> users) {
        if ("ADMIN".equals(role)) {
            return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user("admin@foremen.com").roles("ADMIN");
        }
        return as(users.get(role), role);
    }

    @Test
    @DisplayName("every new endpoint x ADMIN/MANAGER/BUYER/FOREMAN/FINANCIER/CLIENT follows the seeded grant matrix")
    void endpointRoleMatrix() throws Exception {
        revokeTestOnlyClientGrant();
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        Map<String, UserEntity> users = new LinkedHashMap<>();
        for (String role : ROLES) {
            if (!"ADMIN".equals(role)) {
                users.put(role, memberUser(role, project));
            }
        }
        long materialId = material(project).getId();

        List<String> violations = new ArrayList<>();
        for (Endpoint endpoint : endpoints(materialId)) {
            for (String role : ROLES) {
                int status = mockMvc.perform(endpoint.request().apply(project)
                                .with(caller(role, project, users)))
                        .andReturn().getResponse().getStatus();
                boolean denied = status == 403;
                boolean expectedAllowed = endpoint.allowed().contains(role);
                if (denied == expectedAllowed) {
                    violations.add(endpoint.name() + " as " + role + ": status " + status
                            + ", expected " + (expectedAllowed ? "allowed" : "403"));
                }
            }
        }
        assertThat(violations).as("ABAC matrix violations").isEmpty();
    }

    @Test
    @DisplayName("GET /api/procurement/buyers returns active BUYER users only")
    void buyersListsActiveBuyersOnly() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity activeBuyer = memberUser("BUYER", project);
        UserEntity inactiveBuyer = memberUser("BUYER", project);
        inactiveBuyer.setActive(false);
        UserEntity manager = memberUser("MANAGER", project);
        em.flush();
        String body = body(mockMvc.perform(get("/api/procurement/buyers").with(as(manager, "MANAGER"))).andReturn());
        assertThat(body).contains("\"id\":" + activeBuyer.getId())
                .doesNotContain("\"id\":" + inactiveBuyer.getId())
                .doesNotContain("\"id\":" + manager.getId());
    }

    @Test
    @DisplayName("unauthenticated callers are rejected on every new endpoint")
    void unauthenticatedRejected() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        long materialId = material(project).getId();
        for (Endpoint endpoint : endpoints(materialId)) {
            int status = mockMvc.perform(endpoint.request().apply(project)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.anonymous()))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(endpoint.name()).isIn(401, 403);
        }
    }

    @Test
    @DisplayName("FOREMAN and CLIENT never receive money, margin or totals from planner, groups or board")
    void noMoneyLeakForNonStaff() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity request = request(project);
        PurchasePositionEntity accepted = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        PurchasePositionEntity withBuyer = position(project, SourcingPath.AGREED, PositionStatus.WITH_BUYER);
        attach(accepted, request);
        attach(withBuyer, request);

        UserEntity foreman = memberUser("FOREMAN", project);
        UserEntity client = memberUser("CLIENT", project);
        UserEntity manager = memberUser("MANAGER", project);

        // Positive control: STAFF_FULL does get a money block, so the negative checks are meaningful.
        String staffPlanner = body(mockMvc.perform(get(POSITIONS + "/planner").param("size", "200")
                .param("projectId", String.valueOf(project.getId())).with(as(manager, "MANAGER"))).andReturn());
        assertThat(staffPlanner).contains("\"money\":{");

        for (RequestPostProcessor auth : new RequestPostProcessor[] {as(foreman, "FOREMAN"), as(client, "CLIENT")}) {
            String planner = body(mockMvc.perform(get(POSITIONS + "/planner").param("size", "200")
                    .param("projectId", String.valueOf(project.getId())).with(auth)).andReturn());
            String groups = body(mockMvc.perform(get(POSITIONS + "/planner/groups")
                    .param("projectId", String.valueOf(project.getId())).with(auth)).andReturn());
            String board = body(mockMvc.perform(get(POSITIONS + "/board")
                    .param("projectId", String.valueOf(project.getId())).with(auth)).andReturn());

            assertThat(planner).contains("\"positionId\":" + accepted.getId()).doesNotContain("\"money\":{");
            assertThat(groups).contains("\"requestId\":" + request.getId()).doesNotContain("\"totals\":{");
            assertThat(board).contains("\"positionId\":" + withBuyer.getId()).doesNotContain("\"money\":{");
        }
    }

    @Test
    @DisplayName("selection-total is STAFF_FULL only: FOREMAN and CLIENT get 403 even with a READ grant")
    void selectionTotalNonStaffForbidden() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        UserEntity foreman = memberUser("FOREMAN", project);
        UserEntity client = memberUser("CLIENT", project);
        String json = "{\"positionIds\":[" + pos.getId() + "]}";

        for (RequestPostProcessor auth : new RequestPostProcessor[] {as(foreman, "FOREMAN"), as(client, "CLIENT")}) {
            assertThat(mockMvc.perform(post(POSITIONS + "/selection-total").contentType(MediaType.APPLICATION_JSON)
                    .content(json).with(auth)).andReturn().getResponse().getStatus()).isEqualTo(403);
        }
        assertThat(mockMvc.perform(post(POSITIONS + "/selection-total").contentType(MediaType.APPLICATION_JSON)
                .content(json).with(as(memberUser("BUYER", project), "BUYER")))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("the new controllers classify COMPLETE for PermissionAnnotationValidator and the context started")
    void validatorAcceptsNewControllers() {
        PermissionResolver resolver = new PermissionResolver();
        List<Class<?>> controllers = List.of(PurchasePlannerController.class, PurchaseBoardController.class,
                PurchaseSelectionController.class, PurchaseRequestController.class);
        int checked = 0;
        for (Class<?> type : controllers) {
            Object bean = context.getBean(type);
            Class<?> userClass = org.springframework.util.ClassUtils.getUserClass(bean);
            for (Method method : userClass.getDeclaredMethods()) {
                if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) {
                    continue;
                }
                PermissionResolver.Completeness completeness =
                        resolver.classifyCompleteness(new HandlerMethod(bean, method));
                assertThat(completeness).as(type.getSimpleName() + "." + method.getName())
                        .isEqualTo(PermissionResolver.Completeness.COMPLETE);
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(9);
    }

    private static String body(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }
}
