package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.foremen.config.security.PermissionAnnotationValidator;
import com.foremen.controller.GoodsReceiptController;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.dashboard.dataset.DatasetRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FOR-11-05 task 3.6 - goods receipt over the real HTTP API {@code /api/goods-receipts} with real security:
 * record, batch (worked example of the ledger), retry with the same {@code clientRequestId}, void,
 * re-delivery, shipment closure, over-delivery, the ledger endpoint, validation errors, the ABAC matrix per
 * role (FOREMAN create and read, BUYER read only, FINANCIER and CLIENT 403), project scope (404) and the
 * startup annotation validation and dataset registration (the full context boots).
 *
 * <p>Receipt callers are real users (the service needs the numeric user id); shipments are created through
 * {@code /api/shipments} as the default ADMIN. Liquibase is off in this profile, so the grants of changeset
 * 245 are recreated per test; the changesets themselves are covered by
 * {@code GoodsReceiptsSchemaIntegrationTest}.
 *
 * <p>Repeatability: per-run unique data inside the rolled-back transaction of the base class.
 *
 * <p>Validates Requirements 1, 3, 4, 5, 6, 8, 9, 13, 14, 16.1, 16.2.
 */
class GoodsReceiptIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String RECEIPTS = "/api/goods-receipts";
    private static final String SHIPMENTS = "/api/shipments";
    private static final List<String> ROLES = List.of("ADMIN", "MANAGER", "FOREMAN", "BUYER", "FINANCIER", "CLIENT");

    @Autowired
    private ObjectMapper json;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private DatasetRegistry datasetRegistry;

    @BeforeEach
    void seedReceiptMatrix() {
        OperationEntity create = operation("CREATE");
        OperationEntity read = operation("READ");
        OperationEntity update = operation("UPDATE");
        OperationEntity delete = operation("DELETE");
        ResourceEntity shipments = resource("SHIPMENTS");
        grant("ADMIN", shipments, create, read, update, delete);
        grant("BUYER", shipments, create, read, update, delete);
        ResourceEntity receipts = resource("GOODS_RECEIPTS");
        grant("ADMIN", receipts, create, read, update, delete);
        grant("FOREMAN", receipts, create, read, update);
        grant("MANAGER", receipts, create, read, update);
        grant("BUYER", receipts, read);
        // the base grants CLIENT a test-only READ on positions; the seed gives CLIENT nothing on GOODS_RECEIPTS
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

    // ---------------------------------------------------------------- helpers

    private record Fixture(ProjectEntity project, PurchasePositionEntity position, long shipmentId) {
    }

    /** AGREED position (ordered 3) with one shipment of 3, so the position is IN_DELIVERY. */
    private Fixture inDelivery() throws Exception {
        return inDelivery(project(ProjectStatus.ACTIVE));
    }

    private Fixture inDelivery(ProjectEntity project) throws Exception {
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.SUPPLIER_PAID);
        p.setOrderedQuantity(new BigDecimal("3"));
        em.flush();
        MvcResult r = mockMvc.perform(post(SHIPMENTS).contentType(MediaType.APPLICATION_JSON).content(
                "{\"projectId\":" + project.getId() + ",\"deliveryMethod\":\"PHONE_CONTACT\","
                        + "\"driverPhone\":\"+48 600 100 200\",\"driverName\":\"Jan\","
                        + "\"lines\":[{\"positionId\":" + p.getId() + ",\"quantity\":3}]}")).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        long shipmentId = json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
        assertThat(statusOf(p)).isEqualTo(PositionStatus.IN_DELIVERY);
        return new Fixture(project, p, shipmentId);
    }

    private static String event(Long positionId, String kind, String quantity, String comment, boolean fromAccepted,
                                Long shipmentId, UUID requestId) {
        StringBuilder sb = new StringBuilder("{\"positionId\":").append(positionId)
                .append(",\"kind\":\"").append(kind).append("\",\"quantity\":").append(quantity);
        if (comment != null) {
            sb.append(",\"comment\":\"").append(comment).append('"');
        }
        if (fromAccepted) {
            sb.append(",\"fromAccepted\":true");
        }
        if (shipmentId != null) {
            sb.append(",\"shipmentId\":").append(shipmentId);
        }
        if (requestId != null) {
            sb.append(",\"clientRequestId\":\"").append(requestId).append('"');
        }
        return sb.append('}').toString();
    }

    private static String accepted(Long positionId, String quantity) {
        return event(positionId, "ACCEPTED", quantity, null, false, null, null);
    }

    private static String batch(String... events) {
        return "{\"events\":[" + String.join(",", events) + "]}";
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult record(UserEntity user, String role, String body) throws Exception {
        return call(post(RECEIPTS).with(as(user, role)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode recordOk(UserEntity user, String role, String body) throws Exception {
        MvcResult r = record(user, role, body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private PositionStatus statusOf(PurchasePositionEntity p) {
        em.flush();
        em.refresh(p);
        return p.getStatus();
    }

    private BigDecimal acceptedOf(PurchasePositionEntity p) {
        em.flush();
        em.refresh(p);
        return p.getAcceptedQuantity();
    }

    private JsonNode shipment(long id) throws Exception {
        return body(call(get(SHIPMENTS + "/" + id)));
    }

    private static JsonNode ledger(JsonNode batchResult, int index) {
        return batchResult.get("positions").get(index).get("ledger");
    }

    private void assertLedger(JsonNode ledger, String acceptedNet, String missing, String returned,
                              String outstanding, String surplus) {
        assertThat(ledger.get("acceptedNet").decimalValue()).as("acceptedNet").isEqualByComparingTo(acceptedNet);
        assertThat(ledger.get("missing").decimalValue()).as("missing").isEqualByComparingTo(missing);
        assertThat(ledger.get("returned").decimalValue()).as("returned").isEqualByComparingTo(returned);
        assertThat(ledger.get("outstanding").decimalValue()).as("outstanding").isEqualByComparingTo(outstanding);
        assertThat(ledger.get("surplus").decimalValue()).as("surplus").isEqualByComparingTo(surplus);
    }

    private void assertErrorStatus(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
    }

    private void assertErrorKey(MvcResult r, int status, String key) throws Exception {
        assertErrorStatus(r, status);
        assertThat(r.getResponse().getContentAsString()).as("error body").isNotBlank();
        assertThat(body(r).has("message")).isTrue();
        // the key itself is resolved to a localized message; the status and the per-key unit tests pin the key
        assertThat(body(r).get("message").asText()).as(key).isNotBlank().doesNotContain("error.goodsReceipt");
    }

    // ---------------------------------------------------------------- record (R4, R6, R8.6)

    @Test
    @DisplayName("record: partial then full ACCEPTED -> PARTIALLY_RECEIVED then RECEIVED, acceptedQuantity in step, shipment closed")
    void acceptedPartialThenFull() throws Exception {
        Fixture f = inDelivery();
        UserEntity foreman = memberUser("FOREMAN", f.project());

        JsonNode first = recordOk(foreman, "FOREMAN", batch(accepted(f.position().getId(), "1")));

        assertThat(first.get("receipts")).hasSize(1);
        assertThat(first.get("receipts").get(0).get("kind").asText()).isEqualTo("ACCEPTED");
        assertThat(first.get("receipts").get(0).get("shipmentId").asLong()).isEqualTo(f.shipmentId());
        assertThat(first.get("positions").get(0).get("positionStatus").asText()).isEqualTo("PARTIALLY_RECEIVED");
        assertLedger(ledger(first, 0), "1", "0", "0", "2", "0");
        assertThat(ledger(first, 0).get("inFlight").decimalValue()).isEqualByComparingTo("2");
        assertThat(first.get("redeliveryShipmentIds")).isEmpty();
        assertThat(statusOf(f.position())).isEqualTo(PositionStatus.PARTIALLY_RECEIVED);
        assertThat(acceptedOf(f.position())).isEqualByComparingTo("1");
        assertThat(shipment(f.shipmentId()).get("status").asText()).isEqualTo("CREATED");

        JsonNode second = recordOk(foreman, "FOREMAN", batch(accepted(f.position().getId(), "2")));

        assertThat(second.get("positions").get(0).get("positionStatus").asText()).isEqualTo("RECEIVED");
        assertLedger(ledger(second, 0), "3", "0", "0", "0", "0");
        assertThat(statusOf(f.position())).isEqualTo(PositionStatus.RECEIVED);
        assertThat(acceptedOf(f.position())).isEqualByComparingTo("3");
        JsonNode closed = shipment(f.shipmentId());
        assertThat(closed.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(closed.get("deliveredAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("batch: [ACCEPTED 1, RETURNED 1, MISSING 1] reproduces the worked example, closes the shipment and opens one re-delivery")
    void workedExampleBatch() throws Exception {
        Fixture f = inDelivery();
        UserEntity foreman = memberUser("FOREMAN", f.project());
        Long pid = f.position().getId();

        JsonNode result = recordOk(foreman, "FOREMAN", batch(
                accepted(pid, "1"),
                event(pid, "RETURNED", "1", "damaged", false, null, null),
                event(pid, "MISSING", "1", "not on the truck", false, null, null)));

        assertThat(result.get("receipts")).hasSize(3);
        assertLedger(ledger(result, 0), "1", "1", "1", "2", "0");
        assertThat(acceptedOf(f.position())).isEqualByComparingTo("1");
        assertThat(shipment(f.shipmentId()).get("status").asText()).isEqualTo("DELIVERED");

        assertThat(result.get("redeliveryShipmentIds")).hasSize(1);
        JsonNode redelivery = shipment(result.get("redeliveryShipmentIds").get(0).asLong());
        assertThat(redelivery.get("status").asText()).isEqualTo("CREATED");
        assertThat(redelivery.get("lines")).hasSize(1);
        assertThat(redelivery.get("lines").get(0).get("positionId").asLong()).isEqualTo(pid);
        assertThat(redelivery.get("lines").get(0).get("quantity").decimalValue()).isEqualByComparingTo("2");
        // R8.3: a new active shipment returns the position to IN_DELIVERY
        assertThat(statusOf(f.position())).isEqualTo(PositionStatus.IN_DELIVERY);
    }

    @Test
    @DisplayName("over-delivery: ACCEPTED above the cap -> RECEIVED with surplus")
    void overDelivery() throws Exception {
        Fixture f = inDelivery();
        UserEntity foreman = memberUser("FOREMAN", f.project());

        JsonNode result = recordOk(foreman, "FOREMAN", batch(accepted(f.position().getId(), "4")));

        assertLedger(ledger(result, 0), "4", "0", "0", "0", "1");
        assertThat(statusOf(f.position())).isEqualTo(PositionStatus.RECEIVED);
    }

    @Test
    @DisplayName("later complaint: RETURNED fromAccepted on a RECEIVED position reopens it; ACCEPTED on RECEIVED is 409")
    void laterComplaintReopens() throws Exception {
        Fixture f = inDelivery();
        UserEntity foreman = memberUser("FOREMAN", f.project());
        Long pid = f.position().getId();
        recordOk(foreman, "FOREMAN", batch(accepted(pid, "3")));
        assertThat(statusOf(f.position())).isEqualTo(PositionStatus.RECEIVED);

        assertErrorKey(record(foreman, "FOREMAN", batch(accepted(pid, "1"))), 409, "notReceivable");

        JsonNode result = recordOk(foreman, "FOREMAN", batch(
                event(pid, "RETURNED", "1", "cracked on opening", true, null, null)));

        assertLedger(ledger(result, 0), "2", "0", "1", "1", "0");
        assertThat(statusOf(f.position())).isEqualTo(PositionStatus.PARTIALLY_RECEIVED);
        assertThat(acceptedOf(f.position())).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("retry with the same clientRequestId returns the original rows and creates nothing")
    void retryWithSameClientRequestId() throws Exception {
        Fixture f = inDelivery();
        UserEntity foreman = memberUser("FOREMAN", f.project());
        Long pid = f.position().getId();
        UUID requestId = UUID.randomUUID();
        String payload = batch(event(pid, "ACCEPTED", "1", null, false, null, requestId));

        JsonNode first = recordOk(foreman, "FOREMAN", payload);
        // the retry payload is ignored: even a different quantity answers the stored result
        JsonNode retry = recordOk(foreman, "FOREMAN", batch(event(pid, "ACCEPTED", "2", null, false, null, requestId)));

        assertThat(retry.get("receipts")).hasSize(1);
        assertThat(retry.get("receipts").get(0).get("id").asLong())
                .isEqualTo(first.get("receipts").get(0).get("id").asLong());
        assertThat(retry.get("receipts").get(0).get("quantity").decimalValue()).isEqualByComparingTo("1");
        assertThat(acceptedOf(f.position())).isEqualByComparingTo("1");
        JsonNode list = body(call(get(RECEIPTS).with(as(foreman, "FOREMAN")).param("positionId", String.valueOf(pid))));
        assertThat(list.get("content")).hasSize(1);
    }

    @Test
    @DisplayName("record: validation answers 400 and ledger conflicts 409, nothing is written")
    void validationErrors() throws Exception {
        Fixture f = inDelivery();
        UserEntity foreman = memberUser("FOREMAN", f.project());
        Long pid = f.position().getId();

        assertErrorKey(record(foreman, "FOREMAN", batch(event(pid, "MISSING", "1", null, false, null, null))),
                400, "comment.required");
        assertErrorKey(record(foreman, "FOREMAN", batch(accepted(pid, "0"))), 400, "quantity.invalid");
        assertErrorKey(record(foreman, "FOREMAN", batch(accepted(pid, "1.2345"))), 400, "quantity.invalid");
        assertErrorKey(record(foreman, "FOREMAN", "{\"events\":[]}"), 400, "quantity.invalid");
        assertErrorKey(record(foreman, "FOREMAN", batch(
                event(pid, "MISSING", "5", "too many", false, null, null))), 409, "exceedsInFlight");
        assertErrorKey(record(foreman, "FOREMAN", batch(
                event(pid, "RETURNED", "1", "nothing accepted yet", true, null, null))), 409, "returnExceedsAccepted");
        assertErrorKey(record(foreman, "FOREMAN", batch(
                event(pid, "ACCEPTED", "1", null, true, null, null))), 400, "fromAccepted.invalid");
        assertErrorKey(record(foreman, "FOREMAN", batch(
                event(pid, "ACCEPTED", "1", null, false, 987654321L, null))), 400, "shipment.mismatch");

        // a position that is not yet in delivery is not receivable
        PurchasePositionEntity paid = position(f.project(), SourcingPath.AGREED, PositionStatus.SUPPLIER_PAID);
        assertErrorKey(record(foreman, "FOREMAN", batch(accepted(paid.getId(), "1"))), 409, "notReceivable");

        assertThat(acceptedOf(f.position())).isEqualByComparingTo("0");
        assertThat(statusOf(f.position())).isEqualTo(PositionStatus.IN_DELIVERY);
        JsonNode list = body(call(get(RECEIPTS).with(as(foreman, "FOREMAN")).param("positionId", String.valueOf(pid))));
        assertThat(list.get("content")).isEmpty();
    }

    // ---------------------------------------------------------------- void (R5)

    @Test
    @DisplayName("void: the author voids, the ledger and status return; a second void is 409; a reason is required")
    void voidByAuthor() throws Exception {
        Fixture f = inDelivery();
        UserEntity foreman = memberUser("FOREMAN", f.project());
        Long pid = f.position().getId();
        JsonNode recorded = recordOk(foreman, "FOREMAN", batch(accepted(pid, "2")));
        long receiptId = recorded.get("receipts").get(0).get("id").asLong();
        assertThat(recorded.get("receipts").get(0).get("canVoid").asBoolean()).isTrue();
        assertThat(statusOf(f.position())).isEqualTo(PositionStatus.PARTIALLY_RECEIVED);

        assertErrorKey(call(post(RECEIPTS + "/" + receiptId + "/void").with(as(foreman, "FOREMAN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}")), 400, "comment.required");

        MvcResult r = call(post(RECEIPTS + "/" + receiptId + "/void").with(as(foreman, "FOREMAN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"wrong position\"}"));

        assertErrorStatus(r, 200);
        JsonNode result = body(r);
        assertThat(result.get("receipt").get("voidedAt").isNull()).isFalse();
        assertThat(result.get("receipt").get("voidReason").asText()).isEqualTo("wrong position");
        assertLedger(result.get("position").get("ledger"), "0", "0", "0", "3", "0");
        assertThat(result.get("redeliveryKept").asBoolean()).isFalse();
        assertThat(statusOf(f.position())).isEqualTo(PositionStatus.IN_DELIVERY);
        assertThat(acceptedOf(f.position())).isEqualByComparingTo("0");

        assertErrorKey(call(post(RECEIPTS + "/" + receiptId + "/void").with(as(foreman, "FOREMAN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}")), 409, "alreadyVoided");
        // the voided row is hidden by default and listed on request
        JsonNode visible = body(call(get(RECEIPTS).with(as(foreman, "FOREMAN")).param("positionId", String.valueOf(pid))));
        assertThat(visible.get("content")).isEmpty();
        JsonNode all = body(call(get(RECEIPTS).with(as(foreman, "FOREMAN")).param("positionId", String.valueOf(pid))
                .param("includeVoided", "true")));
        assertThat(all.get("content")).hasSize(1);
    }

    @Test
    @DisplayName("void: another foreman is 403, a project MANAGER may void; voiding the released row cancels the unchanged re-delivery")
    void voidPermissionAndRedeliveryCancel() throws Exception {
        Fixture f = inDelivery();
        UserEntity author = memberUser("FOREMAN", f.project());
        UserEntity colleague = memberUser("FOREMAN", f.project());
        UserEntity manager = memberUser("MANAGER", f.project());
        Long pid = f.position().getId();
        JsonNode result = recordOk(author, "FOREMAN", batch(
                accepted(pid, "1"),
                event(pid, "MISSING", "2", "not delivered", false, null, null)));
        long missingId = result.get("receipts").get(1).get("id").asLong();
        long redeliveryId = result.get("redeliveryShipmentIds").get(0).asLong();
        assertThat(shipment(redeliveryId).get("status").asText()).isEqualTo("CREATED");

        MvcResult denied = call(post(RECEIPTS + "/" + missingId + "/void").with(as(colleague, "FOREMAN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not mine\"}"));
        assertErrorKey(denied, 403, "void.forbidden");

        MvcResult ok = call(post(RECEIPTS + "/" + missingId + "/void").with(as(manager, "MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"it arrived\"}"));

        assertErrorStatus(ok, 200);
        assertThat(body(ok).get("redeliveryKept").asBoolean()).isFalse();
        assertThat(shipment(redeliveryId).get("status").asText()).isEqualTo("CANCELLED");
        assertLedger(body(ok).get("position").get("ledger"), "1", "0", "0", "2", "0");
    }

    // ---------------------------------------------------------------- reads (R9)

    @Test
    @DisplayName("ledger endpoint, get by id and to-receive carry the ledger and no money")
    void readsCarryLedgerWithoutMoney() throws Exception {
        Fixture f = inDelivery();
        UserEntity foreman = memberUser("FOREMAN", f.project());
        Long pid = f.position().getId();
        JsonNode recorded = recordOk(foreman, "FOREMAN", batch(accepted(pid, "1")));
        long receiptId = recorded.get("receipts").get(0).get("id").asLong();

        MvcResult ledgerResult = call(get(POSITIONS + "/" + pid + "/ledger").with(as(foreman, "FOREMAN")));
        assertErrorStatus(ledgerResult, 200);
        JsonNode ledger = body(ledgerResult);
        assertThat(ledger.get("positionId").asLong()).isEqualTo(pid);
        assertThat(ledger.get("positionStatus").asText()).isEqualTo("PARTIALLY_RECEIVED");
        assertLedger(ledger.get("ledger"), "1", "0", "0", "2", "0");
        assertThat(ledger.get("ledger").get("cap").decimalValue()).isEqualByComparingTo("3");

        MvcResult one = call(get(RECEIPTS + "/" + receiptId).with(as(foreman, "FOREMAN")));
        assertErrorStatus(one, 200);
        assertThat(body(one).get("kind").asText()).isEqualTo("ACCEPTED");

        MvcResult toReceive = call(get(RECEIPTS + "/to-receive").with(as(foreman, "FOREMAN"))
                .param("projectId", String.valueOf(f.project().getId())));
        assertErrorStatus(toReceive, 200);
        JsonNode list = body(toReceive);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("positionId").asLong()).isEqualTo(pid);
        assertThat(list.get(0).get("shipments")).hasSize(1);
        assertThat(list.get(0).get("shipments").get(0).get("unresolvedQuantity").decimalValue())
                .isEqualByComparingTo("2");

        String all = ledgerResult.getResponse().getContentAsString() + one.getResponse().getContentAsString()
                + toReceive.getResponse().getContentAsString() + recorded;
        for (String forbidden : List.of("price", "Price", "amount", "Amount", "vat", "Vat", "net", "Net", "gross",
                "storageUri", "storage_uri")) {
            assertThat(all).as("no money or storage key: " + forbidden).doesNotContain("\"" + forbidden);
        }
        assertNoMoneyFields(ledger);
    }

    private static void assertNoMoneyFields(JsonNode node) {
        node.properties().forEach(e -> {
            String name = e.getKey().toLowerCase();
            assertThat(name).doesNotContain("price", "amount", "vat", "cost", "margin");
            if (e.getValue().isObject()) {
                assertNoMoneyFields(e.getValue());
            }
        });
    }

    // ---------------------------------------------------------------- ABAC and scope (R1, R16.2)

    private RequestPostProcessor caller(String role, Map<String, UserEntity> users) {
        return as(users.get(role), role);
    }

    private record Endpoint(String name, Set<String> allowed, Function<Long, MockHttpServletRequestBuilder> request) {
    }

    @Test
    @DisplayName("ABAC: FOREMAN/MANAGER/ADMIN create and read, BUYER reads only, FINANCIER and CLIENT get 403 on every endpoint")
    void endpointRoleMatrix() throws Exception {
        Fixture f = inDelivery();
        Map<String, UserEntity> users = new LinkedHashMap<>();
        for (String role : ROLES) {
            users.put(role, "ADMIN".equals(role) ? user("ADMIN") : memberUser(role, f.project()));
        }
        Long pid = f.position().getId();
        Set<String> readers = Set.of("ADMIN", "MANAGER", "FOREMAN", "BUYER");
        Set<String> writers = Set.of("ADMIN", "MANAGER", "FOREMAN");
        List<Endpoint> endpoints = new ArrayList<>();
        endpoints.add(new Endpoint("GET list", readers, id -> get(RECEIPTS)));
        endpoints.add(new Endpoint("GET to-receive", readers, id -> get(RECEIPTS + "/to-receive")
                .param("projectId", String.valueOf(f.project().getId()))));
        endpoints.add(new Endpoint("GET ledger", readers, id -> get(POSITIONS + "/" + pid + "/ledger")));
        // an empty batch is a 400 for a caller that passed ABAC, 403 otherwise
        endpoints.add(new Endpoint("POST create", writers, id -> post(RECEIPTS)
                .contentType(MediaType.APPLICATION_JSON).content("{\"events\":[]}")));
        endpoints.add(new Endpoint("POST void", writers, id -> post(RECEIPTS + "/" + id + "/void")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}")));
        endpoints.add(new Endpoint("GET by id", readers, id -> get(RECEIPTS + "/" + id)));

        UserEntity seeder = users.get("FOREMAN");
        long receiptId = recordOk(seeder, "FOREMAN", batch(accepted(pid, "1"))).get("receipts").get(0).get("id").asLong();

        List<String> violations = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            for (String role : ROLES) {
                int status = call(endpoint.request().apply(receiptId).with(caller(role, users)))
                        .getResponse().getStatus();
                boolean denied = status == 403;
                boolean expectedAllowed = endpoint.allowed().contains(role);
                if (denied == expectedAllowed) {
                    violations.add(endpoint.name() + " as " + role + ": status " + status
                            + ", expected " + (expectedAllowed ? "allowed" : "403"));
                }
            }
        }
        assertThat(violations).as("ABAC matrix violations").isEmpty();
        // the denied BUYER create did not write anything
        assertThat(acceptedOf(f.position())).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("scope: a foreman of another project gets 404 on record, read, ledger, list, to-receive and void; BUYER reads across projects")
    void projectScope() throws Exception {
        Fixture f = inDelivery();
        ProjectEntity other = project(ProjectStatus.ACTIVE);
        UserEntity member = memberUser("FOREMAN", f.project());
        UserEntity outsider = memberUser("FOREMAN", other);
        UserEntity buyer = memberUser("BUYER", other);
        Long pid = f.position().getId();
        long receiptId = recordOk(member, "FOREMAN", batch(accepted(pid, "1"))).get("receipts").get(0).get("id").asLong();

        assertErrorStatus(record(outsider, "FOREMAN", batch(accepted(pid, "1"))), 404);
        assertErrorStatus(call(get(RECEIPTS + "/" + receiptId).with(as(outsider, "FOREMAN"))), 404);
        assertErrorStatus(call(get(POSITIONS + "/" + pid + "/ledger").with(as(outsider, "FOREMAN"))), 404);
        assertErrorStatus(call(get(RECEIPTS + "/to-receive").with(as(outsider, "FOREMAN"))
                .param("projectId", String.valueOf(f.project().getId()))), 404);
        assertErrorStatus(call(post(RECEIPTS + "/" + receiptId + "/void").with(as(outsider, "FOREMAN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")), 404);
        JsonNode outsiderList = body(call(get(RECEIPTS).with(as(outsider, "FOREMAN"))));
        assertThat(outsiderList.get("content")).isEmpty();

        assertErrorStatus(call(get(RECEIPTS + "/" + receiptId).with(as(member, "FOREMAN"))), 200);
        assertErrorStatus(call(get(RECEIPTS + "/" + receiptId).with(as(buyer, "BUYER"))), 200);
        assertErrorStatus(call(get(POSITIONS + "/" + pid + "/ledger").with(as(buyer, "BUYER"))), 200);
        assertThat(acceptedOf(f.position())).isEqualByComparingTo("1");
        assertErrorStatus(call(get(RECEIPTS + "/987654321").with(as(member, "FOREMAN"))), 404);
    }

    // ---------------------------------------------------------------- startup validation and dataset (R14)

    @Test
    @DisplayName("startup: the annotation validator ran with GoodsReceiptController fully annotated; the GOODS_RECEIPTS dataset is registered")
    void startupValidationAndDatasetRegistration() {
        assertThat(applicationContext.getBeansOfType(PermissionAnnotationValidator.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(GoodsReceiptController.class)).hasSize(1);
        var dataset = datasetRegistry.get("GOODS_RECEIPTS");
        assertThat(dataset).isPresent();
        assertThat(dataset.get().requiredResources()).containsExactly("GOODS_RECEIPTS");
        assertThat(dataset.get().projectPath()).contains("project.id");
    }
}
