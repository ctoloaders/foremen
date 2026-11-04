package com.foremen.service.bank.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;

import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.service.bank.connection.BankConnectorErrors;
import com.foremen.service.bank.connector.BankFeedConnector;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.saltedge.SaltEdgeConnector;

/**
 * FOR-07-04 (Requirements 9.2, 19.9) — the live {@code Supported_Bank} catalogue of a provider, cached
 * per {@code (provider, mode, country)} for {@code foremen.bank-feed.saltedge.banks-cache-ttl}
 * (default 1 h). Nothing is hard-coded: the list always comes from {@link BankFeedConnector#listBanks}.
 *
 * <ul>
 *   <li>{@code refresh = true} bypasses (and replaces) the cache entry;</li>
 *   <li>{@link SupportedBanks#fetchedAt()} is when the provider was last asked;</li>
 *   <li>an empty list is not an error: {@link EmptyReason#PROVIDER_STATUS_LIMITED} when the provider
 *       exposes only test (fake / sandbox) banks or reports {@code ClientPending} (a Pending Salt Edge
 *       account), {@link EmptyReason#NO_BANKS} when it returns nothing at all;</li>
 *   <li>any other provider failure → {@code error.bank.provider.*}; an unavailable connector →
 *       {@code 409 error.bank.provider.notAvailable}.</li>
 * </ul>
 */
@Service
public class SupportedBanksService {

    private static final Logger log = LoggerFactory.getLogger(SupportedBanksService.class);

    public static final String DEFAULT_COUNTRY = "PL";
    static final String CLIENT_PENDING = "ClientPending";

    /** Why the list contains no real (non-test) bank of the country. */
    public enum EmptyReason {
        /** The provider account status limits the catalogue (e.g. Salt Edge Pending: fake banks only). */
        PROVIDER_STATUS_LIMITED,
        /** The provider returned no bank at all. */
        NO_BANKS
    }

    /**
     * The catalogue response.
     *
     * @param provider    the provider
     * @param mode        the Salt Edge mode; {@code null} for other providers
     * @param country     the upper-case country code
     * @param banks       the banks (test banks included as returned by the connector)
     * @param fetchedAt   when the provider was last asked
     * @param emptyReason non-null when no real bank of the country is available
     */
    public record SupportedBanks(FeedProvider provider, SaltEdgeMode mode, String country, List<ProviderBank> banks,
                                 Instant fetchedAt, EmptyReason emptyReason) {
        public SupportedBanks {
            banks = banks == null ? List.of() : List.copyOf(banks);
        }
    }

    record CacheKey(FeedProvider provider, SaltEdgeMode mode, String country) {
    }

    private final BankFeedConnectorRegistry registry;
    private final Duration ttl;
    private final Map<CacheKey, SupportedBanks> cache = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    @Autowired
    public SupportedBanksService(BankFeedConnectorRegistry registry,
                                 @Value("${foremen.bank-feed.saltedge.banks-cache-ttl:1h}") String ttl) {
        this(registry, parseTtl(ttl));
    }

    public SupportedBanksService(BankFeedConnectorRegistry registry, Duration ttl) {
        this.registry = registry;
        this.ttl = ttl == null || ttl.isNegative() ? DEFAULT_TTL : ttl;
    }

    static final Duration DEFAULT_TTL = Duration.ofHours(1);

    /** Accepts both {@code 1h} / {@code 30m} and ISO-8601 ({@code PT1H}); invalid ⇒ 1 h. */
    static Duration parseTtl(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TTL;
        }
        try {
            return DurationStyle.detectAndParse(raw.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid foremen.bank-feed.saltedge.banks-cache-ttl '{}'; using {}", raw, DEFAULT_TTL);
            return DEFAULT_TTL;
        }
    }

    /** Test hook. */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The supported banks of {@code provider} in {@code country} (default {@code PL}).
     *
     * @param refresh {@code true} bypasses the cache
     */
    public SupportedBanks list(FeedProvider provider, String country, boolean refresh) {
        BankFeedConnector connector = registry.find(provider)
                .filter(BankFeedConnector::isAvailable)
                .orElseThrow(BankConnectorErrors::providerNotAvailable);
        String countryCode = country == null || country.isBlank()
                ? DEFAULT_COUNTRY : country.trim().toUpperCase(Locale.ROOT);
        SaltEdgeMode mode = connector instanceof SaltEdgeConnector saltEdge ? saltEdge.mode() : null;
        CacheKey key = new CacheKey(provider, mode, countryCode);
        Instant now = clock.instant();
        if (!refresh) {
            SupportedBanks cached = cache.get(key);
            if (cached != null && now.isBefore(cached.fetchedAt().plus(ttl))) {
                return cached;
            }
        }
        SupportedBanks fresh;
        try {
            List<ProviderBank> banks = connector.listBanks(countryCode);
            fresh = new SupportedBanks(provider, mode, countryCode, banks, now, emptyReason(provider, banks));
        } catch (ConnectorError e) {
            if (isPending(e)) {
                log.info("{} reports a Pending account; returning an empty bank list", provider);
                // Not cached: the account status may change at any time.
                return new SupportedBanks(provider, mode, countryCode, List.of(), now,
                        EmptyReason.PROVIDER_STATUS_LIMITED);
            }
            throw BankConnectorErrors.toApiException(e);
        }
        cache.put(key, fresh);
        return fresh;
    }

    /** Drops every cached catalogue. */
    public void evictAll() {
        cache.clear();
    }

    /**
     * Applies the Supported banks tab / bank picker filters.
     *
     * @param q            case-insensitive name / code substring; blank = no filter
     * @param businessOnly only banks supporting business accounts
     * @param includeTest  keep fake / sandbox banks
     */
    public static List<ProviderBank> filter(List<ProviderBank> banks, String q, boolean businessOnly,
                                            boolean includeTest) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return banks.stream()
                .filter(b -> includeTest || !b.testBank())
                .filter(b -> !businessOnly || b.accountTypes().stream()
                        .anyMatch(t -> "business".equalsIgnoreCase(t)))
                .filter(b -> needle.isEmpty()
                        || (b.name() != null && b.name().toLowerCase(Locale.ROOT).contains(needle))
                        || (b.code() != null && b.code().toLowerCase(Locale.ROOT).contains(needle)))
                .toList();
    }

    /**
     * {@code NO_BANKS} for an empty list; for Salt Edge, {@code PROVIDER_STATUS_LIMITED} when only test
     * banks are present (a Pending account exposes only fake providers). The STUB catalogue consists of
     * test banks by design and is never reported as limited.
     */
    static EmptyReason emptyReason(FeedProvider provider, List<ProviderBank> banks) {
        if (banks == null || banks.isEmpty()) {
            return EmptyReason.NO_BANKS;
        }
        if (provider != FeedProvider.SALT_EDGE) {
            return null;
        }
        boolean anyReal = banks.stream().anyMatch(b -> !b.testBank());
        return anyReal ? null : EmptyReason.PROVIDER_STATUS_LIMITED;
    }

    private static boolean isPending(ConnectorError e) {
        return e.getErrorClass() == ConnectorErrorClass.CONFIG && CLIENT_PENDING.equals(e.getProviderErrorCode());
    }
}
