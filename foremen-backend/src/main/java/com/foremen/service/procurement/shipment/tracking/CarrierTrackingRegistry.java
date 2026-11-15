package com.foremen.service.procurement.shipment.tracking;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * FOR-11-04 (R7.2, R7.4): resolves {@link CarrierTrackingAdapter} beans by carrier code. A missing code,
 * a disabled adapter or a disabled master switch resolves to empty, which means "manual only".
 */
@Component
public class CarrierTrackingRegistry {

    private final Map<String, CarrierTrackingAdapter> adapters;
    private final TrackingProperties properties;

    @Autowired
    public CarrierTrackingRegistry(ObjectProvider<CarrierTrackingAdapter> adapterBeans, TrackingProperties properties) {
        this(adapterBeans.orderedStream().toList(), properties);
    }

    public CarrierTrackingRegistry(Collection<? extends CarrierTrackingAdapter> adapterBeans,
                                   TrackingProperties properties) {
        Map<String, CarrierTrackingAdapter> byCode = new LinkedHashMap<>();
        for (CarrierTrackingAdapter adapter : adapterBeans) {
            String code = normalise(adapter.code());
            if (code == null) {
                throw new IllegalStateException("Carrier tracking adapter " + adapter.getClass().getName()
                        + " returns no code");
            }
            CarrierTrackingAdapter previous = byCode.putIfAbsent(code, adapter);
            if (previous != null) {
                throw new IllegalStateException("Duplicate carrier tracking adapters for code " + code + ": "
                        + previous.getClass().getName() + ", " + adapter.getClass().getName());
            }
        }
        this.adapters = Collections.unmodifiableMap(byCode);
        this.properties = properties;
    }

    /** The adapter for {@code code} when it exists, the master switch is on and it reports enabled. */
    public Optional<CarrierTrackingAdapter> find(String code) {
        String key = normalise(code);
        if (key == null || !properties.enabled()) {
            return Optional.empty();
        }
        return Optional.ofNullable(adapters.get(key)).filter(CarrierTrackingAdapter::isEnabled);
    }

    /** The adapter bean for {@code code} regardless of enablement (link building, soft checks). */
    public Optional<CarrierTrackingAdapter> findAny(String code) {
        String key = normalise(code);
        return key == null ? Optional.empty() : Optional.ofNullable(adapters.get(key));
    }

    /** Codes of every registered adapter regardless of enablement (carrier selection, R8.5). */
    public java.util.Set<String> allCodes() {
        return adapters.keySet();
    }

    /** Codes of adapters {@link #find(String)} would return, in registration order. */
    public List<String> availableCodes() {
        if (!properties.enabled()) {
            return List.of();
        }
        return adapters.entrySet().stream()
                .filter(e -> e.getValue().isEnabled())
                .map(Map.Entry::getKey)
                .toList();
    }

    private static String normalise(String code) {
        return code == null || code.isBlank() ? null : code.strip().toUpperCase(Locale.ROOT);
    }
}
