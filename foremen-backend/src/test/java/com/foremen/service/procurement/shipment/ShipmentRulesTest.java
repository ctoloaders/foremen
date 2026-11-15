package com.foremen.service.procurement.shipment;

import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.shipment.ShipmentRules.EtaCandidate;
import com.foremen.service.procurement.shipment.ShipmentRules.MethodFields;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static com.foremen.dao.model.ShipmentSource.MANUAL;
import static com.foremen.dao.model.ShipmentSource.TRACKING;
import static com.foremen.dao.model.ShipmentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentRulesTest {

    private static final Set<String> CARRIERS = Set.of("INPOST", "DPD");

    private static void assertKey(Runnable r, String key) {
        assertThatThrownBy(r::run).isInstanceOf(ForemenApiException.class).hasMessage(key);
    }

    // --- tracking number

    @Test
    void normalisesTrackingNumber() {
        assertThat(ShipmentRules.normaliseTrackingNumber("  ab 12-cd 34 ")).isEqualTo("AB12-CD34");
    }

    @Test
    void rejectsInvalidTrackingNumber() {
        for (String bad : new String[]{null, "", "ab1", "AB_1234", "ab12!", "A".repeat(41)}) {
            assertKey(() -> ShipmentRules.normaliseTrackingNumber(bad), "error.shipment.trackingNumber.invalid");
        }
        assertThat(ShipmentRules.normaliseTrackingNumber("a".repeat(40))).hasSize(40);
    }

    // --- method fields

    @Test
    void carrierRequiresKnownCodeAndNumber() {
        MethodFields f = ShipmentRules.validateMethod(DeliveryMethod.CARRIER, "inpost", "ab 1234", "http://x.pl",
                "https://shop.pl/o/1", "+48 123 456", CARRIERS);
        assertThat(f.carrierCode()).isEqualTo("INPOST");
        assertThat(f.trackingNumber()).isEqualTo("AB1234");
        assertThat(f.trackingUrl()).isNull();
        assertThat(f.shopOrderLink()).isNull();
        assertThat(f.driverPhone()).isEqualTo("+48 123 456");

        assertKey(() -> ShipmentRules.validateMethod(DeliveryMethod.CARRIER, null, "AB1234", null, null, null, CARRIERS),
                "error.shipment.carrier.required");
        assertKey(() -> ShipmentRules.validateMethod(DeliveryMethod.CARRIER, "FOO", "AB1234", null, null, null, CARRIERS),
                "error.shipment.carrier.unknown");
        assertKey(() -> ShipmentRules.validateMethod(DeliveryMethod.CARRIER, "DPD", null, null, null, null, CARRIERS),
                "error.shipment.trackingNumber.invalid");
    }

    @Test
    void otherCarrierKeepsTrackingUrlOptional() {
        MethodFields withUrl = ShipmentRules.validateMethod(DeliveryMethod.CARRIER, "OTHER", "ZZ99", "https://t.example/ZZ99",
                null, null, Set.of());
        assertThat(withUrl.trackingUrl()).isEqualTo("https://t.example/ZZ99");
        MethodFields without = ShipmentRules.validateMethod(DeliveryMethod.CARRIER, "OTHER", "ZZ99", " ", null, null, Set.of());
        assertThat(without.trackingUrl()).isNull();
    }

    @Test
    void shopDeliveryKeepsOnlyLinkAndPhone() {
        MethodFields f = ShipmentRules.validateMethod(DeliveryMethod.SHOP_DELIVERY, "DPD", "AB1234", "https://t.pl",
                "https://shop.pl/o/1", null, CARRIERS);
        assertThat(f.shopOrderLink()).isEqualTo("https://shop.pl/o/1");
        assertThat(f.carrierCode()).isNull();
        assertThat(f.trackingNumber()).isNull();
        assertThat(f.trackingUrl()).isNull();
        assertThat(ShipmentRules.validateMethod(DeliveryMethod.SHOP_DELIVERY, null, null, null, null, null, CARRIERS)
                .shopOrderLink()).isNull();
    }

    @Test
    void phoneContactRequiresPhone() {
        assertKey(() -> ShipmentRules.validateMethod(DeliveryMethod.PHONE_CONTACT, null, null, null, null, " ", CARRIERS),
                "error.shipment.phone.required");
        MethodFields f = ShipmentRules.validateMethod(DeliveryMethod.PHONE_CONTACT, "DPD", "AB1234", null, "https://s.pl",
                "(22) 123-45-67", CARRIERS);
        assertThat(f.driverPhone()).isEqualTo("(22) 123-45-67");
        assertThat(f.carrierCode()).isNull();
        assertThat(f.shopOrderLink()).isNull();
    }

    @Test
    void validatesPhoneAlphabetAndLength() {
        for (String bad : new String[]{"1234", "12345a", "1".repeat(41)}) {
            assertKey(() -> ShipmentRules.validateMethod(DeliveryMethod.PHONE_CONTACT, null, null, null, null, bad, CARRIERS),
                    "error.shipment.phone.invalid");
        }
        assertThat(ShipmentRules.validateMethod(DeliveryMethod.PHONE_CONTACT, null, null, null, null, "12345", CARRIERS)
                .driverPhone()).isEqualTo("12345");
    }

    @Test
    void validatesUrls() {
        for (String bad : new String[]{"ftp://a.pl", "javascript:alert(1)", "/relative", "https://", "not a url",
                "https://a.pl/" + "x".repeat(1000)}) {
            assertKey(() -> ShipmentRules.validateUrl(bad), "error.shipment.link.invalid");
        }
        assertThat(ShipmentRules.validateUrl("HTTP://a.pl/x")).isEqualTo("HTTP://a.pl/x");
        assertThat(ShipmentRules.validateUrl("  ")).isNull();
        assertThat(ShipmentRules.validateUrl(null)).isNull();
    }

    // --- cap and shippability

    @Test
    void capDependsOnSourcingPath() {
        BigDecimal ordered = new BigDecimal("5.000");
        BigDecimal qty = new BigDecimal("8.000");
        assertThat(ShipmentRules.capFor(SourcingPath.AGREED, ordered, qty)).isEqualTo(ordered);
        assertThat(ShipmentRules.capFor(SourcingPath.PACKAGE, ordered, qty)).isEqualTo(ordered);
        assertThat(ShipmentRules.capFor(SourcingPath.CLIENT_OWN, ordered, qty)).isEqualTo(qty);
    }

    @Test
    void shippabilityTable() {
        for (PositionStatus s : PositionStatus.values()) {
            boolean agreedOk = s == PositionStatus.SUPPLIER_PAID || s == PositionStatus.IN_DELIVERY
                    || s == PositionStatus.PARTIALLY_RECEIVED;
            boolean ownOk = s == PositionStatus.IN_DELIVERY || s == PositionStatus.PARTIALLY_RECEIVED;
            assertThat(ShipmentRules.canShip(SourcingPath.AGREED, s)).as("AGREED %s", s).isEqualTo(agreedOk);
            assertThat(ShipmentRules.canShip(SourcingPath.PACKAGE, s)).as("PACKAGE %s", s).isEqualTo(agreedOk);
            assertThat(ShipmentRules.canShip(SourcingPath.CLIENT_OWN, s)).as("CLIENT_OWN %s", s).isEqualTo(ownOk);
        }
        assertThat(ShipmentRules.canShip(null, PositionStatus.IN_DELIVERY)).isFalse();
    }

    // --- status overwrite

    @Test
    void trackingOwnedStatusIsAlwaysOverwritten() {
        assertThat(ShipmentRules.mayOverwriteStatus(OUT_FOR_DELIVERY, TRACKING, IN_TRANSIT)).isTrue();
        assertThat(ShipmentRules.mayOverwriteStatus(IN_TRANSIT, TRACKING, UNKNOWN)).isTrue();
    }

    @Test
    void manualStatusOnlyOverwrittenByForwardMove() {
        assertThat(ShipmentRules.mayOverwriteStatus(IN_TRANSIT, MANUAL, OUT_FOR_DELIVERY)).isTrue();
        assertThat(ShipmentRules.mayOverwriteStatus(IN_TRANSIT, MANUAL, IN_TRANSIT)).isFalse();
        assertThat(ShipmentRules.mayOverwriteStatus(OUT_FOR_DELIVERY, MANUAL, CREATED)).isFalse();
        assertThat(ShipmentRules.mayOverwriteStatus(IN_TRANSIT, MANUAL, EXCEPTION)).isFalse();
        assertThat(ShipmentRules.mayOverwriteStatus(IN_TRANSIT, MANUAL, UNKNOWN)).isFalse();
        assertThat(ShipmentRules.mayOverwriteStatus(EXCEPTION, MANUAL, IN_TRANSIT)).isTrue();
    }

    @Test
    void deliveredAlwaysWinsButTerminalNeverChanges() {
        assertThat(ShipmentRules.mayOverwriteStatus(CREATED, MANUAL, DELIVERED)).isTrue();
        assertThat(ShipmentRules.mayOverwriteStatus(EXCEPTION, MANUAL, DELIVERED)).isTrue();
        assertThat(ShipmentRules.mayOverwriteStatus(DELIVERED, TRACKING, IN_TRANSIT)).isFalse();
        assertThat(ShipmentRules.mayOverwriteStatus(CANCELLED, TRACKING, DELIVERED)).isFalse();
    }

    // --- effective ETA

    @Test
    void effectiveEtaIsLatestActiveEta() {
        LocalDate a = LocalDate.of(2026, 3, 5);
        LocalDate b = LocalDate.of(2026, 3, 9);
        assertThat(ShipmentRules.effectiveEta(List.of(new EtaCandidate(a), new EtaCandidate(null), new EtaCandidate(b)),
                LocalDate.of(2026, 3, 1), false, null)).isEqualTo(b);
    }

    @Test
    void effectiveEtaFallsBackToRequestDateOrKeepsCurrentWhenDelivered() {
        LocalDate request = LocalDate.of(2026, 3, 1);
        LocalDate current = LocalDate.of(2026, 3, 7);
        assertThat(ShipmentRules.effectiveEta(List.of(), request, false, current)).isEqualTo(request);
        assertThat(ShipmentRules.effectiveEta(List.of(new EtaCandidate(null)), request, false, current)).isEqualTo(request);
        assertThat(ShipmentRules.effectiveEta(List.of(), request, true, current)).isEqualTo(current);
        assertThat(ShipmentRules.effectiveEta(null, null, false, current)).isNull();
    }

    // --- delay detection

    @Test
    void delayNeedsBothDatesAndAtLeastOneDay() {
        LocalDate d = LocalDate.of(2026, 3, 5);
        assertThat(ShipmentRules.isDelayed(d, d.plusDays(1))).isTrue();
        assertThat(ShipmentRules.isDelayed(d, d.plusDays(10))).isTrue();
        assertThat(ShipmentRules.isDelayed(d, d)).isFalse();
        assertThat(ShipmentRules.isDelayed(d, d.minusDays(2))).isFalse();
        assertThat(ShipmentRules.isDelayed(null, d)).isFalse();
        assertThat(ShipmentRules.isDelayed(d, null)).isFalse();
    }

    private static void assertField(Runnable r, String key, String field) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getMessageCode()).isEqualTo(key);
            assertThat(e.getField()).isEqualTo(field);
        });
    }

    @Test
    void violationsNameTheOffendingRequestField() {
        assertField(() -> ShipmentRules.validateMethod(null, null, null, null, null, null, CARRIERS),
                "error.shipment.method.required", "deliveryMethod");
        assertField(() -> ShipmentRules.validateMethod(DeliveryMethod.CARRIER, null, "AB1234", null, null, null, CARRIERS),
                "error.shipment.carrier.required", "carrierCode");
        assertField(() -> ShipmentRules.validateMethod(DeliveryMethod.CARRIER, "NOPE", "AB1234", null, null, null, CARRIERS),
                "error.shipment.carrier.unknown", "carrierCode");
        assertField(() -> ShipmentRules.validateMethod(DeliveryMethod.CARRIER, "DPD", "x", null, null, null, CARRIERS),
                "error.shipment.trackingNumber.invalid", "trackingNumber");
        assertField(() -> ShipmentRules.validateMethod(DeliveryMethod.CARRIER, "OTHER", "AB1234", "ftp://x", null, null, CARRIERS),
                "error.shipment.link.invalid", "trackingUrl");
        assertField(() -> ShipmentRules.validateMethod(DeliveryMethod.SHOP_DELIVERY, null, null, null, "nope", null, CARRIERS),
                "error.shipment.link.invalid", "shopOrderLink");
        assertField(() -> ShipmentRules.validateMethod(DeliveryMethod.PHONE_CONTACT, null, null, null, null, "abc", CARRIERS),
                "error.shipment.phone.invalid", "driverPhone");
        assertField(() -> ShipmentRules.validateMethod(DeliveryMethod.PHONE_CONTACT, null, null, null, null, null, CARRIERS),
                "error.shipment.phone.required", "driverPhone");
    }
}
