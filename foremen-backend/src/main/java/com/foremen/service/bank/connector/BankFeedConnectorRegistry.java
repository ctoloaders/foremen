package com.foremen.service.bank.connector;

import com.foremen.dao.model.FeedProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FOR-07-04 (Requirements 4.3, 4.5): maps {@link FeedProvider} → {@link BankFeedConnector} from all
 * connector beans present in the context.
 *
 * <p>Zero connector beans is valid (all conditional connectors switched off); two beans for the same
 * provider fail start-up. {@link #available()} lists only providers whose connector reports
 * {@link BankFeedConnector#isAvailable()}, in enum order — this drives the "Add bank" dialog.
 */
@Component
public class BankFeedConnectorRegistry {

    private final Map<FeedProvider, BankFeedConnector> connectors;

    @Autowired
    public BankFeedConnectorRegistry(ObjectProvider<BankFeedConnector> connectorBeans) {
        this(connectorBeans.orderedStream().toList());
    }

    public BankFeedConnectorRegistry(Collection<? extends BankFeedConnector> connectorBeans) {
        Map<FeedProvider, BankFeedConnector> byProvider = new EnumMap<>(FeedProvider.class);
        for (BankFeedConnector connector : connectorBeans) {
            FeedProvider provider = connector.provider();
            if (provider == null) {
                throw new IllegalStateException("Bank feed connector " + connector.getClass().getName()
                        + " returns no provider");
            }
            BankFeedConnector previous = byProvider.putIfAbsent(provider, connector);
            if (previous != null) {
                throw new IllegalStateException("Duplicate bank feed connectors for provider " + provider + ": "
                        + previous.getClass().getName() + ", " + connector.getClass().getName());
            }
        }
        this.connectors = Collections.unmodifiableMap(byProvider);
    }

    /** The connector bean for {@code provider}, regardless of availability. */
    public Optional<BankFeedConnector> find(FeedProvider provider) {
        return Optional.ofNullable(provider == null ? null : connectors.get(provider));
    }

    /**
     * The connector for {@code provider} when it exists and is available.
     *
     * @throws ConnectorError {@link ConnectorErrorClass#CONFIG} when no bean exists or it is unavailable
     */
    public BankFeedConnector require(FeedProvider provider) {
        BankFeedConnector connector = find(provider)
                .orElseThrow(() -> new ConnectorError(ConnectorErrorClass.CONFIG,
                        "No bank feed connector for provider " + provider));
        if (!connector.isAvailable()) {
            throw new ConnectorError(ConnectorErrorClass.CONFIG,
                    "Bank feed connector for provider " + provider + " is not available");
        }
        return connector;
    }

    /** {@code true} when a connector bean exists for {@code provider} and reports available. */
    public boolean isAvailable(FeedProvider provider) {
        return find(provider).map(BankFeedConnector::isAvailable).orElse(false);
    }

    /** Providers whose connector reports {@code isAvailable() = true}, in enum order (Requirement 4.5). */
    public List<FeedProvider> available() {
        return connectors.values().stream()
                .filter(BankFeedConnector::isAvailable)
                .map(BankFeedConnector::provider)
                .toList();
    }

    /** All registered connectors (available or not), in enum order. */
    public List<BankFeedConnector> all() {
        return List.copyOf(connectors.values());
    }
}
