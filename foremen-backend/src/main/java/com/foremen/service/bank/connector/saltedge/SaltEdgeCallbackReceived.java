package com.foremen.service.bank.connector.saltedge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * FOR-07-04 (Requirement 10; design §Callbacks): a <b>verified</b> Salt Edge callback, published by
 * {@code SaltEdgeCallbackController} and handled asynchronously by {@link SaltEdgeCallbackHandler}.
 *
 * <p>Only the fields Foremen acts on are kept (never the raw payload, R24.1):
 * {@code data.connection_id}, {@code data.customer_id}, {@code data.custom_fields.foremenConnectionId},
 * {@code data.stage}, {@code data.error_class}, {@code data.error_message}.
 *
 * @param type                one of {@link #TYPES}
 * @param connectionId        Salt Edge {@code connection_id}; nullable
 * @param customerId          Salt Edge {@code customer_id}; nullable
 * @param foremenConnectionId {@code custom_fields.foremenConnectionId}; nullable
 * @param stage               {@code notify} stage; nullable
 * @param errorClass          {@code fail} error class; nullable
 * @param errorMessage        {@code fail} error message; nullable
 */
public record SaltEdgeCallbackReceived(String type,
                                       String connectionId,
                                       String customerId,
                                       String foremenConnectionId,
                                       String stage,
                                       String errorClass,
                                       String errorMessage) {

    public static final String SUCCESS = "success";
    public static final String FAIL = "fail";
    public static final String NOTIFY = "notify";
    public static final String DESTROY = "destroy";

    /** The callback types exposed at {@code /api/bank-feed/saltedge/callbacks/{type}} (R10.1). */
    public static final Set<String> TYPES = Set.of(SUCCESS, FAIL, NOTIFY, DESTROY);

    /** {@code true} when {@code type} is one of {@link #TYPES} (case-sensitive, as registered). */
    public static boolean isKnownType(String type) {
        return type != null && TYPES.contains(type);
    }

    /**
     * Parses a verified callback body {@code {data:{…}, meta:{…}}}.
     *
     * @return empty when the body is not a JSON object with a {@code data} object
     */
    public static Optional<SaltEdgeCallbackReceived> parse(String type, byte[] body, ObjectMapper mapper) {
        if (body == null || body.length == 0) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            return Optional.empty();
        }
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isObject()) {
            return Optional.empty();
        }
        JsonNode customFields = data.get("custom_fields");
        String foremenId = customFields != null && customFields.isObject()
                ? text(customFields.get(SaltEdgeConnector.CUSTOM_FIELD_CONNECTION_ID))
                : null;
        return Optional.of(new SaltEdgeCallbackReceived(
                type.toLowerCase(Locale.ROOT),
                text(data.get("connection_id")),
                text(data.get("customer_id")),
                foremenId,
                text(data.get("stage")),
                text(data.get("error_class")),
                text(data.get("error_message"))));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isContainerNode()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }
}
