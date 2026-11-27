package com.foremen.service.bank.connector.fintable;

import com.fasterxml.jackson.databind.JsonNode;
import com.foremen.dao.model.FeedProvider;
import com.foremen.service.bank.PublicBaseUrl;
import com.foremen.service.bank.connection.BoundConnectionIds;
import com.foremen.service.bank.connector.AuthorizationStart;
import com.foremen.service.bank.connector.BankConnectionRef;
import com.foremen.service.bank.connector.BankFeedConnector;
import com.foremen.service.bank.connector.ConnectionState;
import com.foremen.service.bank.connector.ConnectionStatusHint;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.CredentialField;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.connector.NormalizedAccountRef;
import com.foremen.service.bank.connector.NormalizedTransaction;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.RefreshMode;
import com.foremen.service.bank.connector.RefreshResult;
import com.foremen.service.bank.connector.TransactionPage;
import com.foremen.service.bank.connector.fintable.dto.FintableTransactionsPage;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FOR-07-04a (Requirements 4-10; design §FintableConnector): the {@link FeedProvider#FINTABLE}
 * {@link BankFeedConnector}.
 *
 * <ul>
 *   <li><b>Catalogue</b> — search-only; results cached 1 h per {@code (country, q)}; blank query = popular
 *       banks; rows of one bank ordered by the configured provider preference.</li>
 *   <li><b>Authorization</b> — at most one link per minute (local guard); {@code redirect_url} only when the
 *       return URL is HTTPS; completion by {@code connection_id} or, as a fallback, the single unbound
 *       Fintable connection (through {@link BoundConnectionIds}).</li>
 *   <li><b>Transactions</b> — page cursor {@code p:<since>|<fintable cursor>}, resume cursor
 *       {@code u:<max updated_at>}; pending is never read.</li>
 *   <li><b>Sync</b> — {@link #requestRefresh} is spaced locally; {@link #awaitProviderSync} polls
 *       {@code GET /sync} until Fintable has finished (a timeout only logs and continues).</li>
 * </ul>
 */
public class FintableConnector implements BankFeedConnector {

    private static final Logger log = LoggerFactory.getLogger(FintableConnector.class);

    static final String PAGE_PREFIX = "p:";
    static final String RESUME_PREFIX = "u:";
    static final Duration LINK_GUARD = Duration.ofMinutes(1);
    static final Duration CATALOGUE_TTL = Duration.ofHours(1);
    static final int CATALOGUE_MAX_PER_MINUTE = 60;
    static final int MIN_QUERY_LENGTH = 3;
    /** Fallback binding window when the link mint time is unknown (restart). */
    static final Duration FALLBACK_BINDING_WINDOW = Duration.ofHours(24);
    /** Allowance for clock differences between Foremen and Fintable when comparing creation times. */
    static final Duration CLOCK_SLACK = Duration.ofMinutes(1);
    private static final Duration DEFAULT_RATE_LIMIT_BACKOFF = Duration.ofMinutes(1);
    private static final Set<String> RUNNING_STATES = Set.of("queued", "executing", "retrying");

    /** Sleeps between polls; replaced in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private record CacheEntry(Instant at, List<ProviderBank> banks) {
    }

    private final FintableClient client;
    private final FintableProperties properties;
    private final PublicBaseUrl publicBaseUrl;
    private final BoundConnectionIds boundConnectionIds;
    private final FintableRefreshTracker tracker;
    private final Clock clock;
    private final Sleeper sleeper;

    private final Map<String, CacheEntry> catalogueCache = new ConcurrentHashMap<>();
    private final Deque<Instant> catalogueCalls = new ArrayDeque<>();
    private final Object linkLock = new Object();
    private Instant lastLinkAt;

    public FintableConnector(FintableClient client, FintableProperties properties, PublicBaseUrl publicBaseUrl,
                             BoundConnectionIds boundConnectionIds, FintableRefreshTracker tracker, Clock clock) {
        this(client, properties, publicBaseUrl, boundConnectionIds, tracker, clock,
                duration -> Thread.sleep(duration.toMillis()));
    }

    FintableConnector(FintableClient client, FintableProperties properties, PublicBaseUrl publicBaseUrl,
                      BoundConnectionIds boundConnectionIds, FintableRefreshTracker tracker, Clock clock,
                      Sleeper sleeper) {
        this.client = Objects.requireNonNull(client, "client");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.publicBaseUrl = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
        this.boundConnectionIds = Objects.requireNonNull(boundConnectionIds, "boundConnectionIds");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public FeedProvider provider() {
        return FeedProvider.FINTABLE;
    }

    @Override
    public boolean isAvailable() {
        return properties.isAvailable(publicBaseUrl);
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of();
    }

    // ------------------------------------------------------------------ catalogue (Requirement 4)

    @Override
    public boolean catalogueSearchOnly() {
        return true;
    }

    /** Not used: the catalogue is search-only. */
    @Override
    public List<ProviderBank> listBanks(String countryCode) {
        return List.of();
    }

    @Override
    public List<ProviderBank> searchBanks(String countryCode, String query) {
        String country = countryCode == null || countryCode.isBlank()
                ? properties.countryCodes().get(0) : countryCode.trim();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return popularBanks(country);
        }
        if (q.length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        return cachedSearch(country, q);
    }

    private List<ProviderBank> popularBanks(String country) {
        Map<String, ProviderBank> merged = new LinkedHashMap<>();
        for (String popular : properties.popularBanks()) {
            for (ProviderBank bank : cachedSearch(country, popular)) {
                merged.putIfAbsent(bank.code(), bank);
            }
        }
        return List.copyOf(merged.values());
    }

    private List<ProviderBank> cachedSearch(String country, String q) {
        String key = country.toUpperCase(Locale.ROOT) + "|" + q.toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        CacheEntry cached = catalogueCache.get(key);
        if (cached != null && Duration.between(cached.at(), now).compareTo(CATALOGUE_TTL) < 0) {
            return cached.banks();
        }
        if (!reserveCatalogueCall(now)) {
            if (cached != null) {
                return cached.banks();
            }
            throw rateLimited(60L);
        }
        List<ProviderBank> banks;
        try {
            banks = mapInstitutions(client.institutions(q, country), country);
        } catch (FintableConnectorError e) {
            if (cached != null && e.getErrorClass() == ConnectorErrorClass.LIMIT) {
                return cached.banks();
            }
            throw e;
        }
        catalogueCache.put(key, new CacheEntry(now, banks));
        return banks;
    }

    private boolean reserveCatalogueCall(Instant now) {
        synchronized (catalogueCalls) {
            while (!catalogueCalls.isEmpty()
                    && Duration.between(catalogueCalls.peekFirst(), now).compareTo(Duration.ofMinutes(1)) >= 0) {
                catalogueCalls.pollFirst();
            }
            if (catalogueCalls.size() >= CATALOGUE_MAX_PER_MINUTE) {
                return false;
            }
            catalogueCalls.addLast(now);
            return true;
        }
    }

    private List<ProviderBank> mapInstitutions(JsonNode root, String country) {
        JsonNode groups = root.path("groups");
        if (!groups.isArray()) {
            groups = root.path("data");
        }
        List<ProviderBank> out = new ArrayList<>();
        if (!groups.isArray()) {
            return out;
        }
        List<String> preference = properties.providerPreference().stream()
                .map(s -> s.toUpperCase(Locale.ROOT)).toList();
        for (JsonNode group : inCountry(groups, country)) {
            List<ProviderBank> rows = new ArrayList<>();
            for (JsonNode connection : group.path("connections")) {
                ProviderBank bank = FintableMapper.bank(group, connection);
                if (bank != null) {
                    rows.add(bank);
                }
            }
            rows.sort(Comparator
                    .comparingInt((ProviderBank b) -> preferenceIndex(preference, b.via()))
                    .thenComparing(b -> b.name() == null ? "" : b.name(), String.CASE_INSENSITIVE_ORDER));
            out.addAll(rows);
        }
        return List.copyOf(out);
    }

    private static int preferenceIndex(List<String> preference, String via) {
        int idx = via == null ? -1 : preference.indexOf(via.toUpperCase(Locale.ROOT));
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    /**
     * Fintable's {@code country} parameter only reorders results; each group carries {@code countries}.
     * Keep the groups of the requested country; when none matches, keep everything (BUG-3).
     */
    static List<JsonNode> inCountry(JsonNode groups, String country) {
        List<JsonNode> all = new ArrayList<>();
        groups.forEach(all::add);
        if (country == null || country.isBlank()) {
            return all;
        }
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode group : all) {
            for (JsonNode c : group.path("countries")) {
                if (country.trim().equalsIgnoreCase(c.asText())) {
                    matching.add(group);
                    break;
                }
            }
        }
        return matching.isEmpty() ? all : matching;
    }

    // ------------------------------------------------------------------ authorization (Requirement 5)

    @Override
    public AuthorizationStart startAuthorization(BankConnectionRef connection, String providerBankCode,
                                                 URI returnUrl) {
        if (providerBankCode == null || providerBankCode.isBlank()) {
            throw new ConnectorError(ConnectorErrorClass.PROVIDER, "A Fintable bank slug is required");
        }
        JsonNode link = mintGuarded(() -> client.mintLink(providerBankCode.trim(), redirectUrl(returnUrl)));
        tracker.markLinkMinted(connection.id(), clock.instant());
        return authorizationStart(link);
    }

    @Override
    public AuthorizationStart startReconnect(BankConnectionRef connection, URI returnUrl) {
        String externalId = connection.externalConnectionId();
        if (externalId == null || externalId.isBlank()) {
            throw new ConnectorError(ConnectorErrorClass.PROVIDER,
                    "The connection has no Fintable connection id to reconnect");
        }
        JsonNode link = mintGuarded(() -> client.mintReconnectLink(externalId, redirectUrl(returnUrl)));
        tracker.markLinkMinted(connection.id(), clock.instant());
        return authorizationStart(link);
    }

    /** Only an HTTPS return URL is accepted by Fintable; otherwise {@code redirect_url} is omitted. */
    private static String redirectUrl(URI returnUrl) {
        if (returnUrl != null && "https".equalsIgnoreCase(returnUrl.getScheme())) {
            return returnUrl.toString();
        }
        return null;
    }

    /** The local 1-per-minute guard; the slot is released again when the call fails. */
    private JsonNode mintGuarded(java.util.function.Supplier<JsonNode> call) {
        Instant previous;
        synchronized (linkLock) {
            Instant now = clock.instant();
            if (lastLinkAt != null && now.isBefore(lastLinkAt.plus(LINK_GUARD))) {
                long remaining = Math.max(1, Duration.between(now, lastLinkAt.plus(LINK_GUARD)).toSeconds());
                throw rateLimited(remaining);
            }
            previous = lastLinkAt;
            lastLinkAt = now;
        }
        try {
            return call.get();
        } catch (RuntimeException e) {
            synchronized (linkLock) {
                if (!(e instanceof FintableConnectorError fe) || fe.getHttpStatus() == null
                        || fe.getHttpStatus() != 429) {
                    lastLinkAt = previous;
                }
            }
            throw e;
        }
    }

    private AuthorizationStart authorizationStart(JsonNode link) {
        String url = text(link, "url");
        if (url == null) {
            throw new ConnectorError(ConnectorErrorClass.PROVIDER, null, "error.bank.fintable.linkFailed",
                    "Fintable returned no authorization URL");
        }
        return new AuthorizationStart(URI.create(url), FintableMapper.instant(link.get("expires_at")), null);
    }

    @Override
    public ConnectionState completeAuthorization(BankConnectionRef connection, Map<String, String> returnParams) {
        Map<String, String> params = returnParams == null ? Map.of() : returnParams;
        String status = blankToNull(params.get("status"));
        if (status != null && "error".equalsIgnoreCase(status)) {
            return pending(connection);
        }
        String connectionId = blankToNull(params.get("connection_id"));
        if (connectionId == null) {
            connectionId = blankToNull(connection.externalConnectionId());
        }
        if (connectionId == null) {
            connectionId = findUnboundConnection(connection);
        }
        if (connectionId == null) {
            return pending(connection);
        }
        return toState(client.connection(connectionId), connection);
    }

    /** Requirement 5.6: the single unbound Fintable connection created since the link was minted. */
    private String findUnboundConnection(BankConnectionRef connection) {
        Set<String> bound = boundConnectionIds.externalConnectionIds(FeedProvider.FINTABLE);
        Instant minted = tracker.linkMintedAt(connection.id());
        Instant notBefore = (minted != null ? minted : clock.instant().minus(FALLBACK_BINDING_WINDOW))
                .minus(CLOCK_SLACK);
        List<String> candidates = new ArrayList<>();
        for (JsonNode c : listItems(client.connections())) {
            String id = text(c, "id");
            if (id == null || bound.contains(id)) {
                continue;
            }
            Instant created = FintableMapper.instant(c.get("created_at"));
            if (created != null && created.isBefore(notBefore)) {
                continue;
            }
            candidates.add(id);
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static ConnectionState pending(BankConnectionRef connection) {
        return new ConnectionState(ConnectionStatusHint.PENDING_AUTH, null, null, null, null,
                connection.providerCode(), null, null);
    }

    // ------------------------------------------------------------------ state (Requirement 6)

    @Override
    public ConnectionState fetchConnectionState(BankConnectionRef connection) {
        return toState(client.connection(requireExternalId(connection)), connection);
    }

    private static ConnectionState toState(JsonNode c, BankConnectionRef ref) {
        ConnectionStatusHint hint;
        boolean needsReconnect = c.path("needs_reconnect").asBoolean(false);
        boolean unhealthy = c.has("healthy") && !c.path("healthy").isNull() && !c.path("healthy").asBoolean(true);
        String syncState = text(c.path("sync_status"), "state");
        if (needsReconnect) {
            hint = ConnectionStatusHint.REAUTH_REQUIRED;
        } else if (unhealthy || "failed".equalsIgnoreCase(syncState)) {
            hint = ConnectionStatusHint.ERROR;
        } else {
            hint = ConnectionStatusHint.ACTIVE;
        }
        String id = text(c, "id");
        return new ConnectionState(hint, id != null ? id : ref.externalConnectionId(), null, null, null,
                ref.providerCode(), text(c, "institution_name"), null);
    }

    // ------------------------------------------------------------------ holder, accounts (Requirement 7)

    @Override
    public Optional<HolderInfo> fetchHolderInfo(BankConnectionRef connection) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode acc : client.accounts(requireExternalId(connection))) {
            String holder = text(acc, "holder_name");
            if (holder != null) {
                names.add(holder);
            }
        }
        if (names.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new HolderInfo(List.copyOf(names), List.of()));
    }

    @Override
    public List<NormalizedAccount> fetchAccounts(BankConnectionRef connection) {
        String externalId = requireExternalId(connection);
        Instant balanceAt = FintableMapper.instant(client.connection(externalId).get("last_successful_update"));
        List<NormalizedAccount> out = new ArrayList<>();
        for (JsonNode acc : client.accounts(externalId)) {
            NormalizedAccount account = FintableMapper.account(acc, balanceAt);
            if (account != null) {
                out.add(account);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ transactions (Requirement 8)

    @Override
    public TransactionPage fetchTransactions(BankConnectionRef connection, NormalizedAccountRef account,
                                             String cursor) {
        Instant since = null;
        String fintableCursor = null;
        if (cursor != null && cursor.startsWith(PAGE_PREFIX)) {
            String body = cursor.substring(PAGE_PREFIX.length());
            int bar = body.indexOf('|');
            if (bar >= 0) {
                since = parseInstant(body.substring(0, bar));
                fintableCursor = blankToNull(body.substring(bar + 1));
            }
        } else if (cursor != null && cursor.startsWith(RESUME_PREFIX)) {
            Instant resume = parseInstant(cursor.substring(RESUME_PREFIX.length()));
            since = resume == null ? null : resume.minus(properties.cursorOverlap());
        }
        FintableTransactionsPage page = client.transactions(account.externalId(), since, fintableCursor);
        List<NormalizedTransaction> posted = new ArrayList<>();
        int skipped = 0;
        for (JsonNode row : page.data()) {
            NormalizedTransaction tx = FintableMapper.transaction(row, connection.externalConnectionId());
            if (tx == null) {
                skipped++;
            } else {
                posted.add(tx);
            }
        }
        String next = page.hasNext()
                ? PAGE_PREFIX + (since == null ? "" : since.toString()) + "|" + page.nextCursor()
                : null;
        return new TransactionPage(posted, List.of(), next, !page.hasNext(), skipped);
    }

    @Override
    public String resumeCursor(String previous, List<TransactionPage> pages) {
        Instant previousAt = previous != null && previous.startsWith(RESUME_PREFIX)
                ? parseInstant(previous.substring(RESUME_PREFIX.length())) : null;
        Instant max = previousAt;
        if (pages != null) {
            for (TransactionPage page : pages) {
                Instant pageMax = FintableMapper.maxUpdatedAt(page.posted());
                if (pageMax != null && (max == null || pageMax.isAfter(max))) {
                    max = pageMax;
                }
            }
        }
        if (max == null) {
            return previous;
        }
        return RESUME_PREFIX + max;
    }

    // ------------------------------------------------------------------ refresh and waiting (Requirement 9)

    @Override
    public RefreshResult requestRefresh(BankConnectionRef connection, RefreshMode mode) {
        Instant now = clock.instant();
        Instant last = tracker.refreshRequestedAt(connection.id());
        Instant allowedAt = last == null ? now : last.plus(properties.manualSyncInterval());
        if (mode != RefreshMode.INTERACTIVE) {
            return RefreshResult.notYet(allowedAt);
        }
        if (last != null && now.isBefore(allowedAt)) {
            return RefreshResult.notYet(allowedAt);
        }
        try {
            client.syncOne(requireExternalId(connection));
        } catch (FintableConnectorError e) {
            if (e.getHttpStatus() != null && e.getHttpStatus() == 429) {
                Long retry = e.getRetryAfterSeconds();
                Duration wait = retry == null ? DEFAULT_RATE_LIMIT_BACKOFF : Duration.ofSeconds(retry);
                return RefreshResult.notYet(now.plus(wait));
            }
            throw e;
        }
        tracker.markRefreshRequested(connection.id(), now);
        return RefreshResult.started(now.plus(properties.manualSyncInterval()), null);
    }

    @Override
    public Duration providerSyncMaxWait() {
        return properties.syncWait();
    }

    @Override
    public void awaitProviderSync(BankConnectionRef connection, Duration maxWait) {
        String externalId = connection.externalConnectionId();
        if (externalId == null) {
            return;
        }
        Instant deadline = clock.instant().plus(maxWait == null ? Duration.ZERO : maxWait);
        while (true) {
            boolean waiting;
            try {
                waiting = isSyncRunning(externalId, tracker.refreshRequestedAt(connection.id()));
            } catch (RuntimeException e) {
                log.warn("Fintable sync status of connection {} is unavailable; not waiting: {}",
                        connection.id(), e.getMessage());
                return;
            }
            if (!waiting) {
                tracker.clearRefresh(connection.id());
                return;
            }
            if (!clock.instant().isBefore(deadline)) {
                log.warn("Fintable sync of connection {} did not finish within {}; continuing",
                        connection.id(), maxWait);
                return;
            }
            try {
                sleeper.sleep(properties.syncPollInterval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isSyncRunning(String externalId, Instant requestedAt) {
        JsonNode status = syncStatusOf(client.sync(), externalId);
        if (status == null) {
            return false;
        }
        String state = text(status, "state");
        if (state != null && RUNNING_STATES.contains(state.toLowerCase(Locale.ROOT))) {
            return true;
        }
        Instant finishedAt = FintableMapper.instant(status.get("finished_at"));
        return requestedAt != null && finishedAt != null && finishedAt.isBefore(requestedAt);
    }

    /** {@code connections} keyed by id, or an array of objects carrying the id. */
    private static JsonNode syncStatusOf(JsonNode root, String externalId) {
        JsonNode connections = root.path("connections");
        if (connections.isMissingNode() && root.path("data").isArray()) {
            connections = root.path("data");
        }
        JsonNode entry = null;
        if (connections.isObject()) {
            entry = connections.get(externalId);
        } else if (connections.isArray()) {
            for (JsonNode c : connections) {
                if (externalId.equals(text(c, "id")) || externalId.equals(text(c, "connection_id"))) {
                    entry = c;
                    break;
                }
            }
        }
        if (entry == null || entry.isNull()) {
            return null;
        }
        JsonNode nested = entry.get("sync_status");
        return nested != null && nested.isObject() ? nested : entry;
    }

    // ------------------------------------------------------------------ disable (Requirement 10)

    @Override
    public void disable(BankConnectionRef connection) {
        String externalId = blankToNull(connection.externalConnectionId());
        if (externalId == null) {
            return;
        }
        try {
            client.deleteConnection(externalId);
        } catch (FintableConnectorError e) {
            if (e.getHttpStatus() == null || e.getHttpStatus() != 404) {
                throw e;
            }
        }
        tracker.forget(connection.id());
    }

    // ------------------------------------------------------------------ helpers

    private static String requireExternalId(BankConnectionRef connection) {
        String id = blankToNull(connection.externalConnectionId());
        if (id == null) {
            throw new ConnectorError(ConnectorErrorClass.PROVIDER,
                    "The connection has no Fintable connection id");
        }
        return id;
    }

    private static FintableConnectorError rateLimited(long retryAfterSeconds) {
        return new FintableConnectorError(ConnectorErrorClass.LIMIT, null, FintableErrorMapper.KEY_RATE_LIMITED,
                "Fintable request rate limited locally; retry in " + retryAfterSeconds + " s", 429,
                retryAfterSeconds, null);
    }

    private static List<JsonNode> listItems(JsonNode root) {
        JsonNode data = root.isArray() ? root : root.path("data");
        if (!data.isArray()) {
            data = root.path("connections");
        }
        List<JsonNode> out = new ArrayList<>();
        if (data.isArray()) {
            data.forEach(out::add);
        }
        return out;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : blankToNull(v.asText());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
