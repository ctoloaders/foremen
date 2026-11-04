package com.foremen.service.bank.connector.saltedge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.connector.NormalizedTransaction;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.TransactionPage;
import com.foremen.service.bank.connector.TxnStatus;
import com.foremen.service.bank.ingest.IbanNormalizer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FOR-07-04 (Requirements 9.2, 11.1, 11.3, 11.4; design §Data fetch + mapping, §Supported banks):
 * pure mapping of Salt Edge JSON (API v6 / Partners v1 — same resource shapes) to the connector SPI
 * records. JsonNode in → SPI records out; no I/O, no Spring.
 *
 * <p>Every list method accepts either the bare {@code data} array or the whole response envelope
 * ({@code {"data": [...], "meta": {...}}}).
 *
 * <h2>Accounts</h2>
 * {@code id → externalId}; {@code name}, {@code nature}, {@code currency_code}, {@code balance};
 * {@code extra.available_amount → availableBalance}; {@code extra.iban} / {@code extra.account_number};
 * {@code extra.client_name ?: extra.holder_name → holderName}; {@code updated_at → balanceAt}.
 *
 * <h2>Transactions</h2>
 * <ul>
 *   <li>{@code status} {@code posted|pending → POSTED|PENDING}; {@code amount} signed;</li>
 *   <li>{@code made_on → operationDate}; {@code extra.posting_date ?: made_on → bookingDate}
 *       (POSTED only, {@code null} for pending);</li>
 *   <li>{@code description} (+ {@code " · " + extra.information}) → {@code title};</li>
 *   <li>INCOME ({@code amount >= 0}): {@code extra.payer} / {@code extra.payer_information};
 *       EXPENSE: {@code extra.payee} / {@code extra.payee_information}. The name is the
 *       {@code payer|payee} value and the IBAN is the first IBAN / NRB found in the
 *       {@code *_information} text. When the {@code payer|payee} value itself is a bare account
 *       number (some providers report it that way), it becomes the IBAN and the information text
 *       becomes the name;</li>
 *   <li>{@code duplicated → providerDuplicate} (the ingest skips these rows);</li>
 *   <li>the whole object → {@code payload}; the used side's raw fields → {@code counterpartyRaw}.</li>
 * </ul>
 * Missing {@code extra} fields are tolerated. A row without a usable {@code id}, {@code amount},
 * {@code currency_code}, {@code made_on} or a known {@code status} is malformed: list mapping skips
 * and counts it instead of failing.
 *
 * <h2>Providers</h2>
 * Capabilities per the design table; {@code testBank} when {@code country_code = XF} or the name
 * contains "Sandbox"; {@code requiresOwnKeys} = CLIENT mode and the provider code is absent from the
 * {@code key_owner=saltedge} listing.
 */
public final class SaltEdgeMapper {

    /** Separator between {@code description} and {@code extra.information} in the title. */
    public static final String TITLE_SEPARATOR = " · ";
    /** Salt Edge fake-provider country code. */
    public static final String FAKE_COUNTRY = "XF";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int MIN_IBAN_LENGTH = 15;

    /** IBAN candidate: country, check digits, then 11–30 alphanumerics optionally grouped by spaces. */
    private static final Pattern IBAN_CANDIDATE =
            Pattern.compile("(?<![A-Z0-9])[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]){11,30}(?![A-Z0-9])");
    /** Polish NRB candidate: 26 digits, optionally grouped (2 + 6×4). */
    private static final Pattern NRB_CANDIDATE =
            Pattern.compile("(?<!\\d)\\d{2}(?:[ ]?\\d{4}){6}(?!\\d)");
    /** Cleaned shape of an account number (IBAN, or NRB after the {@code PL} prefix). */
    private static final Pattern ACCOUNT_SHAPE = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");

    private SaltEdgeMapper() {
        // Utility class: no instances.
    }

    /**
     * Result of mapping a list of rows: the mapped records plus the number of malformed rows skipped.
     */
    public record Mapped<T>(List<T> rows, int skippedMalformed) {
        public Mapped {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /** Thrown for a row that cannot be normalized; caught and counted by the list methods. */
    public static final class MalformedRowException extends RuntimeException {
        public MalformedRowException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ accounts

    /** Maps a list of accounts; malformed rows are skipped and counted. */
    public static Mapped<NormalizedAccount> toAccounts(JsonNode dataOrEnvelope) {
        List<NormalizedAccount> out = new ArrayList<>();
        int skipped = 0;
        for (JsonNode row : rows(dataOrEnvelope)) {
            try {
                out.add(toAccount(row));
            } catch (MalformedRowException e) {
                skipped++;
            }
        }
        return new Mapped<>(out, skipped);
    }

    /**
     * Maps one account.
     *
     * @throws MalformedRowException when {@code id} or {@code currency_code} is missing, or the
     *                               balance is not a number
     */
    public static NormalizedAccount toAccount(JsonNode a) {
        requireObject(a, "account");
        String id = requireText(a, "id");
        String currency = requireText(a, "currency_code").toUpperCase(Locale.ROOT);
        JsonNode extra = a.path("extra");
        String holder = text(extra, "client_name");
        if (holder == null) {
            holder = text(extra, "holder_name");
        }
        return new NormalizedAccount(
                id,
                text(a, "name"),
                text(a, "nature"),
                text(extra, "iban"),
                text(extra, "account_number"),
                currency,
                decimal(a, "balance"),
                decimal(extra, "available_amount"),
                holder,
                instant(a, "updated_at"));
    }

    // ------------------------------------------------------------------ transactions

    /** Maps a list of transactions (posted and / or pending); malformed rows are skipped and counted. */
    public static Mapped<NormalizedTransaction> toTransactions(JsonNode dataOrEnvelope) {
        List<NormalizedTransaction> out = new ArrayList<>();
        int skipped = 0;
        for (JsonNode row : rows(dataOrEnvelope)) {
            try {
                out.add(toTransaction(row));
            } catch (MalformedRowException e) {
                skipped++;
            }
        }
        return new Mapped<>(out, skipped);
    }

    /**
     * Builds a {@link TransactionPage} from a posted listing and a pending listing. Rows are routed by
     * their own mapped status, so a row reported in the "wrong" listing still lands correctly.
     *
     * @param postedDataOrEnvelope  posted rows (nullable)
     * @param pendingDataOrEnvelope pending rows (nullable)
     * @param nextCursor            cursor for the next call; {@code null} when there is nothing more
     * @param complete              {@code true} when this is the last page
     */
    public static TransactionPage toTransactionPage(JsonNode postedDataOrEnvelope, JsonNode pendingDataOrEnvelope,
                                                    String nextCursor, boolean complete) {
        Mapped<NormalizedTransaction> posted = toTransactions(postedDataOrEnvelope);
        Mapped<NormalizedTransaction> pending = toTransactions(pendingDataOrEnvelope);
        List<NormalizedTransaction> postedRows = new ArrayList<>();
        List<NormalizedTransaction> pendingRows = new ArrayList<>();
        for (NormalizedTransaction t : concat(posted.rows(), pending.rows())) {
            (t.status() == TxnStatus.PENDING ? pendingRows : postedRows).add(t);
        }
        return new TransactionPage(postedRows, pendingRows, nextCursor, complete,
                posted.skippedMalformed() + pending.skippedMalformed());
    }

    /**
     * Maps one transaction.
     *
     * @throws MalformedRowException when a mandatory attribute is missing or unparseable
     */
    public static NormalizedTransaction toTransaction(JsonNode t) {
        requireObject(t, "transaction");
        String id = requireText(t, "id");
        TxnStatus status = status(t);
        BigDecimal amount = decimal(t, "amount");
        if (amount == null) {
            throw new MalformedRowException("transaction " + id + ": missing amount");
        }
        String currency = requireText(t, "currency_code").toUpperCase(Locale.ROOT);
        LocalDate madeOn = date(t, "made_on");
        if (madeOn == null) {
            throw new MalformedRowException("transaction " + id + ": missing made_on");
        }
        JsonNode extra = t.path("extra");
        LocalDate bookingDate = null;
        if (status == TxnStatus.POSTED) {
            LocalDate posting = date(extra, "posting_date");
            bookingDate = posting != null ? posting : madeOn;
        }

        boolean income = amount.signum() >= 0;
        String nameField = income ? "payer" : "payee";
        String infoField = income ? "payer_information" : "payee_information";
        String party = text(extra, nameField);
        String info = text(extra, infoField);
        String counterpartyName = party;
        String iban = findAccountNumber(info);
        if (looksLikeAccount(party)) {
            // The provider put the account number in payer / payee: use it, take the name from info.
            if (iban == null) {
                iban = IbanNormalizer.clean(party);
            }
            counterpartyName = info != null && findAccountNumber(info) == null ? info : null;
        }
        ObjectNode raw = null;
        if (party != null || info != null) {
            raw = NODES.objectNode();
            raw.put("side", nameField);
            putIfPresent(raw, nameField, party);
            putIfPresent(raw, infoField, info);
        }

        return new NormalizedTransaction(
                id,
                status,
                amount,
                currency,
                madeOn,
                bookingDate,
                title(text(t, "description"), text(extra, "information")),
                counterpartyName,
                iban,
                text(t, "category"),
                raw,
                t.deepCopy(),
                t.path("duplicated").asBoolean(false));
    }

    /**
     * Highest numeric transaction id among the given rows (Salt Edge ids are integer strings), used
     * as the per-account cursor; {@code null} when there are none.
     */
    public static String maxId(Collection<NormalizedTransaction> transactions) {
        BigInteger max = null;
        String maxRaw = null;
        for (NormalizedTransaction t : transactions) {
            try {
                BigInteger v = new BigInteger(t.externalId());
                if (max == null || v.compareTo(max) > 0) {
                    max = v;
                    maxRaw = t.externalId();
                }
            } catch (NumberFormatException ignored) {
                // Non-numeric ids do not participate in the cursor.
            }
        }
        return maxRaw;
    }

    /**
     * Finds the first IBAN / Polish NRB in a free-text value. A candidate that passes the mod-97
     * check wins; otherwise the first shape-matching candidate is returned (cleaned) so the ingest can
     * keep it as an invalid raw value.
     *
     * @return the cleaned account number, or {@code null} when none is found
     */
    public static String findAccountNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        String firstCandidate = null;
        for (Pattern p : List.of(IBAN_CANDIDATE, NRB_CANDIDATE)) {
            Matcher m = p.matcher(upper);
            while (m.find()) {
                String cleaned = IbanNormalizer.clean(m.group());
                // The greedy match may swallow a following word ("… 2874 UL"): try shorter prefixes.
                for (int len = cleaned.length(); len >= MIN_IBAN_LENGTH; len--) {
                    String prefix = cleaned.substring(0, len);
                    if (IbanNormalizer.isValid(prefix)) {
                        return prefix;
                    }
                }
                if (firstCandidate == null) {
                    firstCandidate = cleaned;
                }
            }
        }
        return firstCandidate;
    }

    // ------------------------------------------------------------------ providers

    /**
     * Maps a provider listing.
     *
     * @param dataOrEnvelope   the {@code GET /providers} rows
     * @param saltEdgeKeyCodes CLIENT mode: provider codes from the {@code key_owner=saltedge} listing
     *                         (codes absent from it require own keys); {@code null} in PARTNER mode
     *                         (never requires own keys)
     * @return mapped providers in listing order; rows without a {@code code} are skipped and counted
     */
    public static Mapped<ProviderBank> toProviderBanks(JsonNode dataOrEnvelope, Set<String> saltEdgeKeyCodes) {
        List<ProviderBank> out = new ArrayList<>();
        int skipped = 0;
        for (JsonNode row : rows(dataOrEnvelope)) {
            try {
                String code = requireText(row, "code");
                boolean ownKeys = saltEdgeKeyCodes != null && !saltEdgeKeyCodes.contains(code);
                out.add(toProviderBank(row, ownKeys));
            } catch (MalformedRowException e) {
                skipped++;
            }
        }
        return new Mapped<>(out, skipped);
    }

    /** Collects the {@code code}s of a provider listing (used for the {@code key_owner} diff). */
    public static Set<String> providerCodes(JsonNode dataOrEnvelope) {
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode row : rows(dataOrEnvelope)) {
            String code = text(row, "code");
            if (code != null) {
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * Maps one provider.
     *
     * @throws MalformedRowException when {@code code} is missing
     */
    public static ProviderBank toProviderBank(JsonNode p, boolean requiresOwnKeys) {
        requireObject(p, "provider");
        String code = requireText(p, "code");
        String name = text(p, "name");
        String country = text(p, "country_code");
        boolean testBank = FAKE_COUNTRY.equalsIgnoreCase(country)
                || (name != null && name.toLowerCase(Locale.ROOT).contains("sandbox"));
        return new ProviderBank(
                code,
                name != null ? name : code,
                text(p, "logo_url"),
                text(p, "home_url"),
                text(p, "status"),
                text(p, "mode"),
                p.path("regulated").asBoolean(false),
                strings(p.path("supported_account_types")),
                strings(p.path("supported_fetch_scopes")),
                p.path("automatic_fetch").asBoolean(false),
                integer(p, "max_consent_days"),
                integer(p, "max_fetch_interval"),
                requiresOwnKeys,
                testBank);
    }

    // ------------------------------------------------------------------ holder info

    /**
     * Maps holder info. Accepts the {@code holder_info} object itself, a connection object carrying
     * {@code holder_info}, or a response envelope ({@code {"data": ...}}). Names come from
     * {@code names}; tax ids (NIP) from {@code tax_ids} / {@code tax_id} / {@code nip} at the top
     * level or under {@code extra}, when the provider reports them. Missing data → empty.
     */
    public static HolderInfo toHolderInfo(JsonNode node) {
        JsonNode h = node == null ? NODES.missingNode() : node;
        if (h.has("data")) {
            h = h.get("data");
        }
        if (h.has("holder_info")) {
            h = h.get("holder_info");
        }
        if (h == null || !h.isObject()) {
            return new HolderInfo(List.of(), List.of());
        }
        List<String> names = new ArrayList<>(strings(h.path("names")));
        Set<String> taxIds = new LinkedHashSet<>();
        for (JsonNode scope : List.of(h, h.path("extra"))) {
            taxIds.addAll(strings(scope.path("tax_ids")));
            for (String f : List.of("tax_id", "nip")) {
                JsonNode v = scope.path(f);
                if (v.isArray()) {
                    taxIds.addAll(strings(v));
                } else {
                    String s = text(scope, f);
                    if (s != null) {
                        taxIds.add(s);
                    }
                }
            }
        }
        return new HolderInfo(names, List.copyOf(taxIds));
    }

    // ------------------------------------------------------------------ helpers

    private static Iterable<JsonNode> rows(JsonNode dataOrEnvelope) {
        if (dataOrEnvelope == null || dataOrEnvelope.isNull() || dataOrEnvelope.isMissingNode()) {
            return List.of();
        }
        JsonNode data = dataOrEnvelope.isObject() && dataOrEnvelope.has("data")
                ? dataOrEnvelope.get("data") : dataOrEnvelope;
        if (!data.isArray()) {
            return List.of();
        }
        return data;
    }

    private static TxnStatus status(JsonNode t) {
        String s = text(t, "status");
        if (s == null) {
            throw new MalformedRowException("transaction: missing status");
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "posted" -> TxnStatus.POSTED;
            case "pending" -> TxnStatus.PENDING;
            default -> throw new MalformedRowException("transaction: unknown status " + s);
        };
    }

    /** {@code true} when the whole value is an account number (IBAN / NRB, spaces or dashes allowed). */
    private static boolean looksLikeAccount(String value) {
        String cleaned = IbanNormalizer.clean(value);
        return cleaned != null && ACCOUNT_SHAPE.matcher(cleaned).matches();
    }

    private static String title(String description, String information) {
        if (information == null || information.equals(description)) {
            return description;
        }
        return description == null ? information : description + TITLE_SEPARATOR + information;
    }

    private static void requireObject(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw new MalformedRowException(what + ": not an object");
        }
    }

    private static String requireText(JsonNode node, String field) {
        String v = text(node, field);
        if (v == null) {
            throw new MalformedRowException("missing " + field);
        }
        return v;
    }

    /** Trimmed textual / numeric value, or {@code null} when absent, null, a container or blank. */
    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isContainerNode()) {
            return null;
        }
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        String s = v.isTextual() ? v.asText().strip() : null;
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new MalformedRowException("bad number in " + field);
        }
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.intValue();
        }
        String s = v.asText().strip();
        try {
            return s.isEmpty() ? null : Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            throw new MalformedRowException("bad date in " + field);
        }
    }

    private static Instant instant(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null; // informational only — never fails the row
        }
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        if (array != null && array.isArray()) {
            for (JsonNode v : array) {
                if (v != null && v.isValueNode() && !v.isNull()) {
                    String s = v.asText().strip();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }
}
