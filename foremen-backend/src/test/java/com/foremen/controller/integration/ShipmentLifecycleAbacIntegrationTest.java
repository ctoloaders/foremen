package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FOR-11-04 task 5.1 - ABAC matrix of {@code /api/shipments} through real security: the seeded SHIPMENTS grants
 * (changeset 242: ADMIN and BUYER full, MANAGER READ and UPDATE, FOREMAN and FINANCIER READ, CLIENT none) decide
 * 403, and a FOREMAN reads the shipment without the buyer's note (R16.4).
 *
 * <p>Repeatability: per-run unique data inside the rolled-back transaction of the base class.
 *
 * <p>Validates Requirements 16, 17.
 */
class ShipmentLifecycleAbacIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String SHIPMENTS = "/api/shipments";
    private static final List<String> ROLES = List.of("ADMIN", "BUYER", "MANAGER", "FOREMAN", "FINANCIER", "CLIENT");

    @Autowired
    private ObjectMapper json;

    private record Endpoint(String name, Set<String> allowed, Function<Long, MockHttpServletRequestBuilder> request) {
    }

    @BeforeEach
    void seedShipmentMatrix() {
        OperationEntity create = operation("CREATE");
        OperationEntity read = operation("READ");
        OperationEntity update = operation("UPDATE");
        OperationEntity delete = operation("DELETE");
        ResourceEntity shipments = resource("SHIPMENTS");
        grant("ADMIN", shipments, create, read, update, delete);
        grant("BUYER", shipments, create, read, update, delete);
        grant("MANAGER", shipments, read, update);
        grant("FOREMAN", shipments, read);
        grant("FINANCIER", shipments, read);
        // the base grants CLIENT a test-only READ on positions; CLIENT has no seeded SHIPMENTS grant
        List<RoleResourceEntity> clientRows = em.createQuery(
                "select rr from RoleResourceEntity rr where rr.role.code = 'CLIENT'", RoleResourceEntity.class)
                .getResultList();
        for (RoleResourceEntity rr : clientRows) {
            rr.getRole().getRoleResources().remove(rr);
            em.remove(rr);
        }
        em.flush();
        for (String code : ROLES) {
            permissionCache.invalidate(code);
        }
    }

    private List<Endpoint> endpoints() {
        Set<String> readers = Set.of("ADMIN", "BUYER", "MANAGER", "FOREMAN", "FINANCIER");
        Set<String> updaters = Set.of("ADMIN", "BUYER", "MANAGER");
        Set<String> creators = Set.of("ADMIN", "BUYER");
        List<Endpoint> list = new ArrayList<>();
        list.add(new Endpoint("GET list", readers, id -> get(SHIPMENTS)));
        list.add(new Endpoint("GET by id", readers, id -> get(SHIPMENTS + "/" + id)));
        list.add(new Endpoint("POST create", creators, id -> post(SHIPMENTS)
                .contentType(MediaType.APPLICATION_JSON).content("{}")));
        list.add(new Endpoint("PUT update", updaters, id -> put(SHIPMENTS + "/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"abac\"}")));
        list.add(new Endpoint("POST eta/revert", updaters, id -> post(SHIPMENTS + "/" + id + "/eta/revert")));
        list.add(new Endpoint("POST refresh", updaters, id -> post(SHIPMENTS + "/" + id + "/refresh")));
        list.add(new Endpoint("POST cancel", updaters, id -> post(SHIPMENTS + "/" + id + "/cancel")));
        return list;
    }

    private RequestPostProcessor caller(String role, Map<String, UserEntity> users) {
        if ("ADMIN".equals(role)) {
            return SecurityMockMvcRequestPostProcessors.user("admin@foremen.com").roles("ADMIN");
        }
        return as(users.get(role), role);
    }

    private Long createShipment(ProjectEntity project) throws Exception {
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.SUPPLIER_PAID);
        p.setOrderedQuantity(new java.math.BigDecimal("5"));
        em.flush();
        String payload = "{\"projectId\":" + project.getId() + ",\"deliveryMethod\":\"PHONE_CONTACT\","
                + "\"driverPhone\":\"+48 600 100 200\",\"driverName\":\"Jan\",\"note\":\"buyer note\","
                + "\"etaDate\":\"" + LocalDate.now().plusDays(3) + "\","
                + "\"lines\":[{\"positionId\":" + p.getId() + ",\"quantity\":1}]}";
        var r = mockMvc.perform(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json.readTree(r.getContentAsString()).get("id").asLong();
    }

    @Test
    @DisplayName("every shipment endpoint x ADMIN/BUYER/MANAGER/FOREMAN/FINANCIER/CLIENT follows the seeded grant matrix")
    void endpointRoleMatrix() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        Map<String, UserEntity> users = new LinkedHashMap<>();
        for (String role : ROLES) {
            if (!"ADMIN".equals(role)) {
                users.put(role, memberUser(role, project));
            }
        }
        List<String> violations = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            for (String role : ROLES) {
                // a fresh shipment per call: cancel is destructive
                Long id = createShipment(project);
                int status = mockMvc.perform(endpoint.request().apply(id).with(caller(role, users)))
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
    @DisplayName("FOREMAN member reads the shipment with driver contact but without the buyer's note; MANAGER sees the note")
    void foremanDoesNotSeeNote() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        Long id = createShipment(project);
        UserEntity foreman = memberUser("FOREMAN", project);
        UserEntity manager = memberUser("MANAGER", project);

        JsonNode asForeman = json.readTree(mockMvc.perform(get(SHIPMENTS + "/" + id).with(as(foreman, "FOREMAN")))
                .andReturn().getResponse().getContentAsString());
        assertThat(asForeman.get("note").isNull()).isTrue();
        assertThat(asForeman.get("driverName").asText()).isEqualTo("Jan");
        assertThat(asForeman.get("driverPhone").asText()).isEqualTo("+48 600 100 200");

        JsonNode asManager = json.readTree(mockMvc.perform(get(SHIPMENTS + "/" + id).with(as(manager, "MANAGER")))
                .andReturn().getResponse().getContentAsString());
        assertThat(asManager.get("note").asText()).isEqualTo("buyer note");
    }

    @Test
    @DisplayName("BUYER and FINANCIER are company-wide: they read a shipment of a project they are not a member of")
    void companyWideRolesSeeAllProjects() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        ProjectEntity other = project(ProjectStatus.ACTIVE);
        Long id = createShipment(project);
        UserEntity buyer = memberUser("BUYER", other);
        UserEntity financier = memberUser("FINANCIER", other);
        UserEntity foreman = memberUser("FOREMAN", other);

        assertThat(mockMvc.perform(get(SHIPMENTS + "/" + id).with(as(buyer, "BUYER"))).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get(SHIPMENTS + "/" + id).with(as(financier, "FINANCIER"))).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get(SHIPMENTS + "/" + id).with(as(foreman, "FOREMAN"))).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
    }
}
