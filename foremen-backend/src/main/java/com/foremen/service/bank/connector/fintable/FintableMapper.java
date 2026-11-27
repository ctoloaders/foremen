package com.foremen.service.bank.connector.fintable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.connector.NormalizedTransaction;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.TxnStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * FOR-07-04a (Requirements 4.2, 7.1, 8.4, 8.5): pure mapping functions from Fintable JSON to the
 * connector SPI types. Every function tolerates missing or differently shaped input; a malformed
 * transaction maps to {@code null} (counted by the caller) and never throws.
 *
 * <p>The counterparty candidate paths below are hand-built from provider documentation and are
 * verified against real captures in task 8.2.
 */
public final class FintableMapper {

    /** Backing providers a Fintable connection id may carry ({@code conn_<provider>_…}). */
    public static final String NORDIGEN = "NORDIGEN";
    public static final String ENABLEBANKING = "ENABLEBANKING";
    public static final String PLAID = "PLAID";

    private static final String[][] NORDIGEN_OUT = {{"creditorName"}, {"creditorAccount", "iban"}};
    private static final String[][] NORDIGEN_IN = {{"debtorName"}, {"debtorAccount", "iban"}};
    private static final String[][] ENABLE_OUT = {{"creditor", "name"}, {"creditor_account", "iban"}};
    private static final String[][] ENABLE_IN = {{"debtor", "name"}, {"debtor_account", "iban"}};
    private static final String[][] PLAID_PATHS = {{"counterparties", "0", "name"}, {}};

    private FintableMapper() {
    }

    /** Payer / payee resolved for a transaction; both parts nullable. */
    public record Counterparty(String name, String iban) {
    }

    // ---------------------------------------------------------------- accounts

    /**
     * Maps a Fintable account object; {@code null} when it has no {@code id}.
     *
     * @param balanceAt the connection's {@code last_successful_update}; nullable
     */
    public static NormalizedAccount account(JsonNode acc, Instant balanceAt) {
        if (acc == null || !acc.isObject()) {
            return null;
        }
        String id = text(acc, "id");
        if (id == null) {
            return null;
        }
        String name = firstNonNull(text(acc, "display_name"), text(acc, "name"));
        return new NormalizedAccount(
                id,
                name == null ? id : name,
                nature(text(acc, "type")),
                text(acc, "iban"),
                text(acc, "account_number"),
                text(acc, "currency"),
                decimal(acc.get("balance")),
                decimal(acc.get("balance_available")),
                text(acc, "holder_name"),
                balanceAt);
    }

    /** Requirement 7.1 type → nature map. */
    static String nature(String type) {
        if (type == null) {
            return "account";
        }
        String t = type.toLowerCase(Locale.ROOT);
        if (t.startsWith("credit")) {
            return "card";
        }
        if (t.contains("savings") || t.contains("money market") || containsToken(t, "cd")) {
            return "savings";
        }
        return "account";
    }

    private static boolean containsToken(String s, String token) {
        for (String part : s.split("[^a-z]+")) {
            if (part.equals(token)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ transactions

    /** Same as {@link #transaction(JsonNode, String)} with an unknown backing provider. */
    public static NormalizedTransaction transaction(JsonNode tx) {
        return transaction(tx, null);
    }

    /**
     * Maps a Fintable transaction; {@code null} when malformed (no id, unparsable amount, no
     * currency or no usable date).
     *
     * @param connectionId Fintable connection id used to detect the backing provider; nullable
     */
    public static NormalizedTransaction transaction(JsonNode tx, String connectionId) {
        try {
            return mapTransaction(tx, connectionId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static NormalizedTransaction mapTransaction(JsonNode tx, String connectionId) {
        if (tx == null || !tx.isObject()) {
            return null;
        }
        String id = text(tx, "id");
        BigDecimal amount = decimal(tx.get("amount"));
        String currency = firstNonNull(text(tx, "currency"), text(tx, "iso_currency_code"));
        LocalDate date = date(tx.get("date"));
        LocalDate authDate = date(tx.get("auth_date"));
        if (id == null || amount == null || currency == null) {
            return null;
        }
        LocalDate booking = date != null ? date : authDate;
        if (booking == null) {
            return null;
        }
        LocalDate operation = authDate != null ? authDate : booking;
        String title = firstNonNull(text(tx, "description"), text(tx, "external_memo"), text(tx, "merchant"));
        JsonNode category = tx.get("category");
        String providerCategory = category != null && category.isObject() ? text(category, "name")
                : (category != null && category.isTextual() ? blankToNull(category.asText()) : null);
        Counterparty cp = counterparty(tx, amount, detectProvider(connectionId));
        return new NormalizedTransaction(id, TxnStatus.POSTED, amount, currency, operation, booking, title,
                cp.name(), cp.iban(), providerCategory,
                counterpartyRaw(tx.get("raw"), amount, detectProvider(connectionId)), tx, false);
    }

    // ------------------------------------------------------------ counterparty

    /** Counterparty with an unknown backing provider (fallbacks only). */
    public static Counterparty counterparty(JsonNode tx, BigDecimal amount) {
        return counterparty(tx, amount, null);
    }

    /**
     * Resolves payer / payee by the sign of {@code amount}: outgoing (&lt; 0) uses the creditor,
     * incoming the debtor. Falls back to {@code merchant}, then {@code account_owner}, then null.
     */
    public static Counterparty counterparty(JsonNode tx, BigDecimal amount, String provider) {
        if (tx == null || !tx.isObject()) {
            return new Counterparty(null, null);
        }
        boolean outgoing = amount != null && amount.signum() < 0;
        String name = null;
        String iban = null;
        String[][] paths = paths(provider, outgoing);
        JsonNode raw = tx.get("raw");
        if (paths != null && raw != null && raw.isObject()) {
            name = blankToNull(textAt(raw, paths[0]));
            iban = blankToNull(textAt(raw, paths[1]));
        }
        if (name == null) {
            name = firstNonNull(text(tx, "merchant"), text(tx, "account_owner"));
        }
        return new Counterparty(name, iban);
    }

    private static String[][] paths(String provider, boolean outgoing) {
        if (provider == null) {
            return null;
        }
        return switch (provider) {
            case NORDIGEN -> outgoing ? NORDIGEN_OUT : NORDIGEN_IN;
            case ENABLEBANKING -> outgoing ? ENABLE_OUT : ENABLE_IN;
            case PLAID -> PLAID_PATHS;
            default -> null;
        };
    }

    private static JsonNode counterpartyRaw(JsonNode raw, BigDecimal amount, String provider) {
        String[][] paths = paths(provider, amount.signum() < 0);
        if (paths == null || raw == null || !raw.isObject()) {
            return null;
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (String[] path : paths) {
            if (path.length > 0 && raw.has(path[0])) {
                out.set(path[0], raw.get(path[0]).deepCopy());
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** Backing provider from {@code conn_<provider>_…}; null when unknown. */
    public static String detectProvider(String connectionId) {
        if (connectionId == null) {
            return null;
        }
        String id = connectionId.toLowerCase(Locale.ROOT);
        if (id.startsWith("conn_nordigen")) {
            return NORDIGEN;
        }
        if (id.startsWith("conn_enablebanking")) {
            return ENABLEBANKING;
        }
        if (id.startsWith("conn_plaid")) {
            return PLAID;
        }
        return null;
    }

    // --------------------------------------------------------------- catalogue

    /**
     * Maps one institution-group connection to a {@link ProviderBank}; {@code null} for a defunct
     * or slug-less entry.
     */
    public static ProviderBank bank(JsonNode group, JsonNode connection) {
        if (connection == null || !connection.isObject()) {
            return null;
        }
        String slug = text(connection, "slug");
        if (slug == null || isDefunct(group) || isDefunct(connection)) {
            return null;
        }
        String groupName = group == null ? null : text(group, "name");
        String name = firstNonNull(text(connection, "name"), groupName, slug);
        JsonNode providerNode = connection.get("provider");
        String via = null;
        if (providerNode != null) {
            via = providerNode.isObject() ? text(providerNode, "name")
                    : (providerNode.isTextual() ? blankToNull(providerNode.asText()) : null);
        }
        via = via == null ? null : via.toUpperCase(Locale.ROOT);
        boolean regulated = NORDIGEN.equals(via) || ENABLEBANKING.equals(via);
        String logo = group == null ? null : firstNonNull(text(group, "logo_url"), text(group, "logo"));
        return new ProviderBank(slug, name, logo, group == null ? null : homeUrl(text(group, "domain")),
                "active", "api", regulated, accountTypes(slug, name), Set.of(), true, null, null, false,
                connection.path("is_test_connection").asBoolean(false), via);
    }

    private static boolean isDefunct(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        return node.path("defunct").asBoolean(false) || node.path("is_defunct").asBoolean(false)
                || "defunct".equalsIgnoreCase(text(node, "status"));
    }

    private static String homeUrl(String domain) {
        if (domain == null) {
            return null;
        }
        return domain.contains("://") ? domain : "https://" + domain;
    }

    /** {@code business-and-personal} → both; business markers → business; retail / personal → personal. */
    static Set<String> accountTypes(String slug, String name) {
        String s = ((slug == null ? "" : slug) + " " + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
        boolean business = s.contains("business") || s.contains("corporate") || s.contains("biznes");
        boolean personal = s.contains("personal") || s.contains("retail");
        Set<String> types = new HashSet<>();
        if (business) {
            types.add("business");
        }
        if (personal || s.contains("business-and-personal")) {
            types.add("personal");
        }
        if (s.contains("business-and-personal")) {
            types.add("business");
        }
        if (types.isEmpty()) {
            // no marker in slug / name: the bank is not distinguishable, offer both
            types.add("personal");
            types.add("business");
        }
        return types;
    }

    // ------------------------------------------------------------- updated_at

    /** Max {@code payload.updated_at} over the rows; {@code null} when none carries a valid one. */
    public static Instant maxUpdatedAt(List<NormalizedTransaction> rows) {
        Instant max = null;
        if (rows == null) {
            return null;
        }
        for (NormalizedTransaction row : rows) {
            if (row == null || row.payload() == null) {
                continue;
            }
            Instant ts = instant(row.payload().get("updated_at"));
            if (ts != null && (max == null || ts.isAfter(max))) {
                max = ts;
            }
        }
        return max;
    }

    /** Parses an ISO instant / offset date-time; {@code null} when absent or invalid. */
    public static Instant instant(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String v = node.asText().trim();
        try {
            return Instant.parse(v);
        } catch (RuntimeException e) {
            try {
                return OffsetDateTime.parse(v).toInstant();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            if (node.isTextual()) {
                String v = node.asText().trim();
                return v.isEmpty() ? null : new BigDecimal(v);
            }
            if (node.isNumber()) {
                return node.isBigDecimal() ? node.decimalValue() : new BigDecimal(node.asText());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static LocalDate date(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String v = node.asText().trim();
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !(v.isTextual() || v.isNumber())) {
            return null;
        }
        return blankToNull(v.asText());
    }

    private static String textAt(JsonNode root, String[] path) {
        JsonNode cur = root;
        for (String seg : path) {
            if (cur == null) {
                return null;
            }
            if (cur.isArray()) {
                try {
                    cur = cur.get(Integer.parseInt(seg));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                cur = cur.get(seg);
            }
        }
        if (path.length == 0 || cur == null || !cur.isTextual()) {
            return null;
        }
        return cur.asText();
    }

    private static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
