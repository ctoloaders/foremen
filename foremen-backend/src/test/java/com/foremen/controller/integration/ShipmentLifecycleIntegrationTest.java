package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.procurement.externalorder.ExternalOrderStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FOR-11-04 task 5.1 - shipment lifecycle over the real HTTP API {@code /api/shipments} with real security:
 * create (stage change to {@code IN_DELIVERY}, position cap, order-line cap, validation), update, manual ETA
 * and its revert, cancel with the {@code IN_DELIVERY -> SUPPLIER_PAID} reversal, and project scope (404).
 *
 * <p>The STUB carrier is switched on so a {@code CARRIER} shipment is trackable (needed for the ETA revert).
 * The first tracking refresh is published AFTER_COMMIT and the test transaction never commits, so nothing
 * calls a carrier.
 *
 * <p>Repeatability: per-run unique data inside the rolled-back transaction of the base class.
 *
 * <p>Validates Requirements 3, 4, 5, 6, 11, 16, 17.
 */
@TestPropertySource(properties = {
        "foremen.procurement.tracking.enabled=true",
        "foremen.procurement.tracking.stub.enabled=true"
})
class ShipmentLifecycleIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String SHIPMENTS = "/api/shipments";

    @Autowired
    private ObjectMapper json;
    @Autowired
    private MessageResolver messages;

    @org.junit.jupiter.api.BeforeEach
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
        em.flush();
        for (String code : List.of("ADMIN", "BUYER", "MANAGER", "FOREMAN", "FINANCIER")) {
            permissionCache.invalidate(code);
        }
    }

    // ---------------------------------------------------------------- helpers

    private PurchasePositionEntity paid(ProjectEntity project, String ordered) {
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.SUPPLIER_PAID);
        p.setOrderedQuantity(new BigDecimal(ordered));
        em.flush();
        return p;
    }

    private ExternalOrderEntity order(ProjectEntity project, PurchasePositionEntity position, String qty) {
        MaterialSellerEntity seller = new MaterialSellerEntity();
        seller.setCode("sl_seller_" + unique());
        seller.setNameRU("Продавец");
        seller.setNamePL("Sprzedawca");
        em.persist(seller);
        ExternalOrderEntity o = new ExternalOrderEntity();
        o.setProject(project);
        o.setSeller(seller);
        o.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        o.setDeliveryCostNet(new BigDecimal("5.00"));
        em.persist(o);
        ExternalOrderLineEntity l = new ExternalOrderLineEntity();
        l.setExternalOrder(o);
        l.setPosition(position);
        l.setQuantity(new BigDecimal(qty));
        l.setUnitPurchaseNet(new BigDecimal("4.00"));
        l.setPurchaseVatRate(vat23);
        em.persist(l);
        em.flush();
        return o;
    }

    private String body(Long projectId, Long orderId, String eta, Object... lines) {
        StringBuilder ls = new StringBuilder();
        for (int i = 0; i < lines.length; i += 2) {
            if (i > 0) {
                ls.append(',');
            }
            ls.append("{\"positionId\":").append(lines[i]).append(",\"quantity\":").append(lines[i + 1]).append('}');
        }
        return "{\"projectId\":" + projectId
                + (orderId == null ? "" : ",\"externalOrderId\":" + orderId)
                + ",\"deliveryMethod\":\"PHONE_CONTACT\",\"driverPhone\":\"+48 600 100 200\""
                + ",\"driverName\":\"Jan\",\"note\":\"buyer note\""
                + (eta == null ? "" : ",\"etaDate\":\"" + eta + "\"")
                + ",\"lines\":[" + ls + "]}";
    }

    private String carrierBody(Long projectId, String eta, Object... lines) {
        StringBuilder ls = new StringBuilder();
        for (int i = 0; i < lines.length; i += 2) {
            if (i > 0) {
                ls.append(',');
            }
            ls.append("{\"positionId\":").append(lines[i]).append(",\"quantity\":").append(lines[i + 1]).append('}');
        }
        return "{\"projectId\":" + projectId + ",\"deliveryMethod\":\"CARRIER\",\"carrierCode\":\"STUB\""
                + ",\"trackingNumber\":\"STUBTRANSIT" + unique().replace("_", "") + "\""
                + (eta == null ? "" : ",\"etaDate\":\"" + eta + "\"")
                + ",\"lines\":[" + ls + "]}";
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode create(String payload) throws Exception {
        MvcResult r = call(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON).content(payload));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private void assertError(MvcResult r, int status, String key) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        String message = json.readTree(r.getResponse().getContentAsString()).get("message").asText();
        assertThat(message).isIn(
                messages.resolve(key, new Object[0], Locale.forLanguageTag("ru")),
                messages.resolve(key, new Object[0], Locale.forLanguageTag("pl")),
                messages.resolve(key, new Object[0], Locale.ENGLISH));
    }

    private PositionStatus statusOf(PurchasePositionEntity p) {
        em.flush();
        em.refresh(p);
        return p.getStatus();
    }

    private LocalDate plannedOf(PurchasePositionEntity p) {
        em.flush();
        em.refresh(p);
        return p.getPlannedDeliveryDate();
    }

    // ---------------------------------------------------------------- create (R3, R4)

    @Test
    @DisplayName("create: 201, status CREATED, TRACKING ETA source, position SUPPLIER_PAID -> IN_DELIVERY, one history event")
    void createMovesPositionToInDelivery() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");

        JsonNode s = create(body(project.getId(), null, null, p.getId(), "2"));

        assertThat(s.get("status").asText()).isEqualTo("CREATED");
        assertThat(s.get("statusSource").asText()).isEqualTo("MANUAL");
        assertThat(s.get("etaSource").asText()).isEqualTo("TRACKING");
        assertThat(s.get("etaDate").isNull()).isTrue();
        assertThat(s.get("projectId").asLong()).isEqualTo(project.getId());
        assertThat(s.get("lines")).hasSize(1);
        assertThat(s.get("lines").get(0).get("quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(s.get("events")).hasSize(1);
        assertThat(s.get("events").get(0).get("status").asText()).isEqualTo("CREATED");
        assertThat(s.has("price")).isFalse();
        assertThat(statusOf(p)).isEqualTo(PositionStatus.IN_DELIVERY);
    }

    @Test
    @DisplayName("create with a manual ETA: etaSource MANUAL and the position planned date follows the ETA")
    void createWithManualEtaSetsPlannedDate() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");
        LocalDate eta = LocalDate.now().plusDays(6);

        JsonNode s = create(body(project.getId(), null, eta.toString(), p.getId(), "5"));

        assertThat(s.get("etaSource").asText()).isEqualTo("MANUAL");
        assertThat(s.get("etaDate").asText()).isEqualTo(eta.toString());
        assertThat(plannedOf(p)).isEqualTo(eta);
    }

    @Test
    @DisplayName("create: a position not at a shippable stage answers 409 notShippable and creates nothing")
    void createRejectsNotShippablePosition() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        p.setOrderedQuantity(new BigDecimal("5"));
        em.flush();

        MvcResult r = call(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON)
                .content(body(project.getId(), null, null, p.getId(), "1")));

        assertError(r, 409, "error.shipment.position.notShippable");
        assertThat(statusOf(p)).isEqualTo(PositionStatus.PLANNED);
        assertThat(json.readTree(call(get(SHIPMENTS).param("projectId", String.valueOf(project.getId())))
                .getResponse().getContentAsString()).get("content")).isEmpty();
    }

    @Test
    @DisplayName("create: the sum of active shipments per position is capped by orderedQuantity")
    void createEnforcesPositionCap() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");

        assertError(call(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON)
                .content(body(project.getId(), null, null, p.getId(), "6"))), 409, "error.shipment.line.exceedsCap");
        create(body(project.getId(), null, null, p.getId(), "3"));
        assertError(call(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON)
                .content(body(project.getId(), null, null, p.getId(), "3"))), 409, "error.shipment.line.exceedsCap");
        // exactly the remainder fits
        create(body(project.getId(), null, null, p.getId(), "2"));
    }

    @Test
    @DisplayName("create: the quantity per position is capped by the external order line")
    void createEnforcesOrderLineCap() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");
        ExternalOrderEntity order = order(project, p, "2");

        assertError(call(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON)
                .content(body(project.getId(), order.getId(), null, p.getId(), "3"))),
                409, "error.shipment.line.exceedsOrderLine");
        JsonNode ok = create(body(project.getId(), order.getId(), null, p.getId(), "2"));
        assertThat(ok.get("externalOrderId").asLong()).isEqualTo(order.getId());
    }

    @Test
    @DisplayName("create: validation of lines and method fields answers 400")
    void createValidation() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");

        assertError(call(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON)
                .content(body(project.getId(), null, null))), 400, "error.shipment.line.empty");
        assertError(call(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON)
                .content(body(project.getId(), null, null, p.getId(), "0"))), 400, "error.shipment.line.empty");
        assertError(call(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON)
                .content(body(project.getId(), null, null, p.getId(), "1", p.getId(), "1"))),
                400, "error.shipment.line.duplicate");
        assertError(call(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":" + project.getId() + ",\"deliveryMethod\":\"PHONE_CONTACT\","
                        + "\"lines\":[{\"positionId\":" + p.getId() + ",\"quantity\":1}]}")),
                400, "error.shipment.phone.required");
        assertError(call(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":" + project.getId() + ",\"deliveryMethod\":\"CARRIER\","
                        + "\"carrierCode\":\"NOPE\",\"trackingNumber\":\"ABCD1234\","
                        + "\"lines\":[{\"positionId\":" + p.getId() + ",\"quantity\":1}]}")),
                400, "error.shipment.carrier.unknown");
        assertThat(statusOf(p)).isEqualTo(PositionStatus.SUPPLIER_PAID);
    }

    @Test
    @DisplayName("create: a position of another project answers 400 project mismatch")
    void createRejectsForeignProjectPosition() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        ProjectEntity b = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(b, "5.000");

        assertError(call(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON)
                .content(body(a.getId(), null, null, p.getId(), "1"))), 400, "error.shipment.project.mismatch");
    }

    // ---------------------------------------------------------------- update (R5) and ETA revert

    @Test
    @DisplayName("update: manual ETA moves the planned date and a later ETA is a delay; manual status IN_TRANSIT is accepted")
    void updateSetsEtaAndStatus() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");
        long id = create(body(project.getId(), null, LocalDate.now().plusDays(3).toString(), p.getId(), "5"))
                .get("id").asLong();
        LocalDate later = LocalDate.now().plusDays(10);

        MvcResult r = call(put(SHIPMENTS + "/" + id).contentType(MediaType.APPLICATION_JSON).content(
                "{\"deliveryMethod\":\"PHONE_CONTACT\",\"driverPhone\":\"+48 600 100 201\",\"driverName\":\"Piotr\","
                        + "\"note\":\"changed\",\"etaDate\":\"" + later + "\",\"status\":\"IN_TRANSIT\"}"));

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode s = json.readTree(r.getResponse().getContentAsString());
        assertThat(s.get("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(s.get("statusSource").asText()).isEqualTo("MANUAL");
        assertThat(s.get("etaDate").asText()).isEqualTo(later.toString());
        assertThat(s.get("driverName").asText()).isEqualTo("Piotr");
        assertThat(s.get("note").asText()).isEqualTo("changed");
        assertThat(s.get("events")).hasSize(2);
        assertThat(plannedOf(p)).isEqualTo(later);
    }

    @Test
    @DisplayName("update: lines of a CREATED shipment are replaced; the position left out returns to SUPPLIER_PAID")
    void updateReplacesLines() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity a = paid(project, "5.000");
        PurchasePositionEntity b = paid(project, "5.000");
        long id = create(body(project.getId(), null, null, a.getId(), "5")).get("id").asLong();
        assertThat(statusOf(a)).isEqualTo(PositionStatus.IN_DELIVERY);

        MvcResult r = call(put(SHIPMENTS + "/" + id).contentType(MediaType.APPLICATION_JSON).content(
                "{\"deliveryMethod\":\"PHONE_CONTACT\",\"driverPhone\":\"+48 600 100 200\","
                        + "\"lines\":[{\"positionId\":" + b.getId() + ",\"quantity\":4}]}"));

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode lines = json.readTree(r.getResponse().getContentAsString()).get("lines");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("positionId").asLong()).isEqualTo(b.getId());
        assertThat(statusOf(a)).isEqualTo(PositionStatus.SUPPLIER_PAID);
        assertThat(statusOf(b)).isEqualTo(PositionStatus.IN_DELIVERY);
    }

    @Test
    @DisplayName("manual ETA on a carrier shipment can be reverted to the tracking ETA; a second revert is 409")
    void manualEtaRevert() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");
        LocalDate eta = LocalDate.now().plusDays(4);
        JsonNode created = create(carrierBody(project.getId(), eta.toString(), p.getId(), "5"));
        long id = created.get("id").asLong();
        assertThat(created.get("etaSource").asText()).isEqualTo("MANUAL");
        assertThat(created.get("refreshAvailable").asBoolean()).isTrue();
        assertThat(plannedOf(p)).isEqualTo(eta);

        MvcResult r = call(post(SHIPMENTS + "/" + id + "/eta/revert"));

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode s = json.readTree(r.getResponse().getContentAsString());
        assertThat(s.get("etaSource").asText()).isEqualTo("TRACKING");
        assertThat(s.get("etaDate").isNull()).isTrue();
        assertThat(plannedOf(p)).isNull();
        assertError(call(post(SHIPMENTS + "/" + id + "/eta/revert")), 409, "error.shipment.eta.revertNotAvailable");
    }

    @Test
    @DisplayName("ETA revert is not available for a non-carrier shipment (409)")
    void revertNotAvailableForPhoneContact() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");
        long id = create(body(project.getId(), null, LocalDate.now().plusDays(2).toString(), p.getId(), "1"))
                .get("id").asLong();

        assertError(call(post(SHIPMENTS + "/" + id + "/eta/revert")), 409, "error.shipment.eta.revertNotAvailable");
    }

    // ---------------------------------------------------------------- cancel (R6)

    @Test
    @DisplayName("cancel: shipment CANCELLED, position back to SUPPLIER_PAID, cap released, second cancel 409")
    void cancelReversesPosition() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");
        long id = create(body(project.getId(), null, LocalDate.now().plusDays(5).toString(), p.getId(), "5"))
                .get("id").asLong();
        assertThat(statusOf(p)).isEqualTo(PositionStatus.IN_DELIVERY);
        assertThat(plannedOf(p)).isNotNull();

        MvcResult r = call(post(SHIPMENTS + "/" + id + "/cancel"));

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode s = json.readTree(r.getResponse().getContentAsString());
        assertThat(s.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(s.get("events").get(0).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(statusOf(p)).isEqualTo(PositionStatus.SUPPLIER_PAID);
        assertThat(plannedOf(p)).isNull();
        assertError(call(post(SHIPMENTS + "/" + id + "/cancel")), 409, "error.shipment.terminal");
        // the cap is released: the full quantity can be shipped again
        create(body(project.getId(), null, null, p.getId(), "5"));
    }

    @Test
    @DisplayName("cancel: a position with another active shipment stays IN_DELIVERY")
    void cancelKeepsPositionWithOtherActiveShipment() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");
        long first = create(body(project.getId(), null, null, p.getId(), "2")).get("id").asLong();
        create(body(project.getId(), null, null, p.getId(), "3"));

        call(post(SHIPMENTS + "/" + first + "/cancel"));

        assertThat(statusOf(p)).isEqualTo(PositionStatus.IN_DELIVERY);
    }

    @Test
    @DisplayName("terminal shipment: only the note can change; other changes are 409")
    void terminalShipmentAcceptsOnlyNote() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");
        long id = create(body(project.getId(), null, null, p.getId(), "1")).get("id").asLong();
        call(post(SHIPMENTS + "/" + id + "/cancel"));

        MvcResult noteOnly = call(put(SHIPMENTS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"after cancel\"}"));
        assertThat(noteOnly.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(noteOnly.getResponse().getContentAsString()).get("note").asText())
                .isEqualTo("after cancel");

        assertError(call(put(SHIPMENTS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_TRANSIT\"}")), 409, "error.shipment.terminal");
    }

    // ---------------------------------------------------------------- reads and project scope (R16, R17)

    @Test
    @DisplayName("list: filtered by project, sorted by ETA ascending with nulls last")
    void listSortedByEta() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity a = paid(project, "5.000");
        PurchasePositionEntity b = paid(project, "5.000");
        PurchasePositionEntity c = paid(project, "5.000");
        long noEta = create(body(project.getId(), null, null, a.getId(), "1")).get("id").asLong();
        long late = create(body(project.getId(), null, LocalDate.now().plusDays(9).toString(), b.getId(), "1"))
                .get("id").asLong();
        long soon = create(body(project.getId(), null, LocalDate.now().plusDays(1).toString(), c.getId(), "1"))
                .get("id").asLong();

        JsonNode content = json.readTree(call(get(SHIPMENTS).param("projectId", String.valueOf(project.getId())))
                .getResponse().getContentAsString()).get("content");

        assertThat(content).hasSize(3);
        assertThat(content.get(0).get("id").asLong()).isEqualTo(soon);
        assertThat(content.get(1).get("id").asLong()).isEqualTo(late);
        assertThat(content.get(2).get("id").asLong()).isEqualTo(noEta);
    }

    @Test
    @DisplayName("project scope: a MANAGER outside the project gets 404 on get/update/cancel/revert/refresh and an empty list; a member sees it")
    void projectScopeAnswers404() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        ProjectEntity other = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");
        long id = create(body(project.getId(), null, null, p.getId(), "1")).get("id").asLong();
        UserEntity outsider = memberUser("MANAGER", other);
        UserEntity member = memberUser("MANAGER", project);

        assertError(call(get(SHIPMENTS + "/" + id).with(as(outsider, "MANAGER"))), 404, "error.shipment.notFound");
        assertError(call(put(SHIPMENTS + "/" + id).with(as(outsider, "MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}")), 404, "error.shipment.notFound");
        assertError(call(post(SHIPMENTS + "/" + id + "/cancel").with(as(outsider, "MANAGER"))),
                404, "error.shipment.notFound");
        assertError(call(post(SHIPMENTS + "/" + id + "/eta/revert").with(as(outsider, "MANAGER"))),
                404, "error.shipment.notFound");
        assertError(call(post(SHIPMENTS + "/" + id + "/refresh").with(as(outsider, "MANAGER"))),
                404, "error.shipment.notFound");
        JsonNode outsiderList = json.readTree(call(get(SHIPMENTS).with(as(outsider, "MANAGER")))
                .getResponse().getContentAsString()).get("content");
        assertThat(ids(outsiderList)).doesNotContain(id);

        assertThat(call(get(SHIPMENTS + "/" + id).with(as(member, "MANAGER"))).getResponse().getStatus())
                .isEqualTo(200);
        JsonNode memberList = json.readTree(call(get(SHIPMENTS).with(as(member, "MANAGER")))
                .getResponse().getContentAsString()).get("content");
        assertThat(ids(memberList)).contains(id);
        // the unchanged shipment stays open
        assertThat(json.readTree(call(get(SHIPMENTS + "/" + id)).getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("project scope: create in a project the caller is not a member of answers 404")
    void createInForeignProjectIs404() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        ProjectEntity other = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");
        UserEntity buyerLike = memberUser("MANAGER", other);
        // MANAGER has no CREATE grant, so grant it for this role to reach the scope check
        em.createQuery("select rr from RoleResourceEntity rr where rr.role.code = 'MANAGER' "
                        + "and rr.resource.code = 'SHIPMENTS'", com.foremen.dao.model.RoleResourceEntity.class)
                .getSingleResult().getOperations().add(operation("CREATE"));
        em.flush();
        permissionCache.invalidate("MANAGER");

        MvcResult r = call(post(SHIPMENTS).with(as(buyerLike, "MANAGER")).contentType(MediaType.APPLICATION_JSON)
                .content(body(project.getId(), null, null, p.getId(), "1")));

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(404);
        assertThat(statusOf(p)).isEqualTo(PositionStatus.SUPPLIER_PAID);
    }

    @Test
    @DisplayName("unknown shipment id answers 404")
    void unknownIdIs404() throws Exception {
        assertError(call(get(SHIPMENTS + "/987654321")), 404, "error.shipment.notFound");
    }

    private static Set<Long> ids(JsonNode content) {
        java.util.HashSet<Long> ids = new java.util.HashSet<>();
        content.forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    @Test
    @DisplayName("list: delayed=true keeps only active shipments with an ETA before today")
    void listDelayedFilter() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity a = paid(project, "5.000");
        PurchasePositionEntity b = paid(project, "5.000");
        PurchasePositionEntity c = paid(project, "5.000");
        long overdue = create(body(project.getId(), null, LocalDate.now().minusDays(5).toString(), a.getId(), "1"))
                .get("id").asLong();
        long future = create(body(project.getId(), null, LocalDate.now().plusDays(5).toString(), b.getId(), "1"))
                .get("id").asLong();
        long noEta = create(body(project.getId(), null, null, c.getId(), "1")).get("id").asLong();
        JsonNode delayed = json.readTree(call(get(SHIPMENTS).param("projectId", String.valueOf(project.getId()))
                .param("delayed", "true")).getResponse().getContentAsString()).get("content");
        assertThat(delayed).hasSize(1);
        assertThat(delayed.get(0).get("id").asLong()).isEqualTo(overdue);
        JsonNode rest = json.readTree(call(get(SHIPMENTS).param("projectId", String.valueOf(project.getId()))
                .param("delayed", "false")).getResponse().getContentAsString()).get("content");
        assertThat(rest).extracting(n -> n.get("id").asLong()).containsExactlyInAnyOrder(future, noEta);
        // a cancelled overdue shipment is no longer delayed
        assertThat(call(post(SHIPMENTS + "/" + overdue + "/cancel")).getResponse().getStatus()).isEqualTo(200);
        JsonNode afterCancel = json.readTree(call(get(SHIPMENTS).param("projectId", String.valueOf(project.getId()))
                .param("delayed", "true")).getResponse().getContentAsString()).get("content");
        assertThat(afterCancel).isEmpty();
    }

    @Test
    @DisplayName("create: a rule violation reports fieldErrors at the offending field")
    void createRuleViolationReportsField() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = paid(project, "5.000");
        MvcResult r = call(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":" + project.getId() + ",\"deliveryMethod\":\"SHOP_DELIVERY\","
                        + "\"shopOrderLink\":\"not a link\",\"lines\":[{\"positionId\":" + p.getId()
                        + ",\"quantity\":1}]}"));
        assertError(r, 400, "error.shipment.link.invalid");
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(body.get("fieldErrors").has("shopOrderLink")).isTrue();
    }
}
