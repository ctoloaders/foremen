package com.foremen.service.procurement.shipment.tracking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingPropertiesTest {

    @Test
    void emptyBindingYieldsSafeDefaults() {
        TrackingProperties p = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("foremen.procurement.tracking", TrackingProperties.class);
        assertThat(p.enabled()).isFalse();
        assertThat(p.cron()).isEqualTo("-");
        assertThat(p.zone()).isEqualTo("Europe/Warsaw");
        assertThat(p.minShipmentInterval()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.maxResponseBytes()).isEqualTo(1_048_576L);
        assertThat(p.stub().enabled()).isFalse();
        assertThat(p.isCarrierEnabled("DPD")).isFalse();
    }

    @Test
    void applicationYmlShipsEverythingOffWithRealDefaults() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        for (PropertySource<?> ps : new YamlPropertySourceLoader()
                .load("application", new FileSystemResource("src/main/resources/application.yml"))) {
            env.getPropertySources().addLast(ps);
        }
        Binder binder = Binder.get(env);
        TrackingProperties p = binder.bindOrCreate("foremen.procurement.tracking", TrackingProperties.class);
        assertThat(p.enabled()).isFalse();
        assertThat(p.cron()).isEqualTo("-");
        assertThat(p.batchSize()).isEqualTo(200);
        assertThat(p.minCallSpacing()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.refreshCooldown()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.carrierPause()).isEqualTo(Duration.ofHours(1));
        assertThat(p.backoffCap()).isEqualTo(Duration.ofHours(24));
        assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.stub().enabled()).isFalse();
        assertThat(p.carriers()).containsOnlyKeys("inpost");
        assertThat(env.resolvePlaceholders("${foremen.procurement.deadline-sweep.cron}")).isEqualTo("-");
    }

    @Test
    void carrierRequiresMasterSwitchAndOwnFlag() {
        var carriers = Map.of("dpd", new TrackingProperties.Carrier(true));
        var on = new TrackingProperties(true, null, null, 0, null, null, null, null, null, null, null, 0, carriers, null);
        var off = new TrackingProperties(false, null, null, 0, null, null, null, null, null, null, null, 0, carriers, null);
        assertThat(on.isCarrierEnabled("DPD")).isTrue();
        assertThat(on.isCarrierEnabled("GLS")).isFalse();
        assertThat(off.isCarrierEnabled("DPD")).isFalse();
    }
}
