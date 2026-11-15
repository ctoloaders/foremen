package com.foremen.service.procurement.receipt;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ReceiptKind;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.receipt.GoodsReceiptRules.Inputs;
import com.foremen.service.procurement.receipt.GoodsReceiptRules.Ledger;
import com.foremen.service.procurement.receipt.GoodsReceiptRules.MediaLimits;
import com.foremen.service.procurement.receipt.GoodsReceiptRules.MediaMeta;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FOR-11-05 task 3.2: example-based tests of {@link GoodsReceiptRules}.
 * Validates: Requirements 3.2, 4.3, 4.4, 4.7, 5.2, 7.2.
 */
class GoodsReceiptRulesTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final Duration WINDOW = Duration.ofHours(24);

    private static BigDecimal d(long v) {
        return BigDecimal.valueOf(v);
    }

    private static Ledger cap3(boolean hasShipments, long shipped, long accepted, long missing,
                               long returnedDoor, long returnedAccepted) {
        return GoodsReceiptRules.ledger(new Inputs(d(3), hasShipments, d(shipped), d(accepted), d(missing),
                d(returnedDoor), d(returnedAccepted)));
    }

    private static void assertLedger(Ledger l, long acceptedNet, long missing, long returned,
                                     long outstanding, long surplus, long inFlight, long remainder) {
        assertThat(l.acceptedNet()).isEqualByComparingTo(d(acceptedNet));
        assertThat(l.missing()).isEqualByComparingTo(d(missing));
        assertThat(l.returned()).isEqualByComparingTo(d(returned));
        assertThat(l.outstanding()).isEqualByComparingTo(d(outstanding));
        assertThat(l.surplus()).isEqualByComparingTo(d(surplus));
        assertThat(l.inFlight()).isEqualByComparingTo(d(inFlight));
        assertThat(l.remainder()).isEqualByComparingTo(d(remainder));
    }

    private static void assertError(Runnable call, HttpStatus status, String key) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getMessageCode()).isEqualTo(key);
                });
    }

    // ---------------------------------------------------------------- worked examples (cap 3)

    @Test
    void workedExample1_acceptedReturnedMissingOnFullLine() {
        assertLedger(cap3(true, 3, 1, 1, 1, 0), 1, 1, 1, 2, 0, 0, 2);
    }

    @Test
    void workedExample2_lineShortOfCap() {
        assertLedger(cap3(true, 2, 1, 0, 1, 0), 1, 0, 1, 2, 0, 0, 2);
    }

    @Test
    void workedExample3_returnFromAccepted() {
        assertLedger(cap3(true, 3, 3, 0, 0, 1), 2, 0, 1, 1, 0, 0, 1);
    }

    @Test
    void workedExample4_overDelivery() {
        assertLedger(cap3(true, 3, 4, 0, 0, 0), 4, 0, 0, 0, 1, 0, 0);
    }

    @Test
    void noShipmentsNoEvents_keepsCapRule() {
        Ledger l = cap3(false, 0, 0, 0, 0, 0);
        assertThat(l.committed()).isEqualByComparingTo(ZERO);
        assertThat(l.remainder()).isEqualByComparingTo(d(3));
        assertThat(l.inFlight()).isEqualByComparingTo(ZERO);
    }

    @Test
    void noShipmentsWithEvent_usesCapAsExpectation() {
        Ledger l = cap3(false, 0, 1, 0, 0, 0);
        assertThat(l.inFlight()).isEqualByComparingTo(d(2));
        assertThat(l.outstanding()).isEqualByComparingTo(d(2));
    }

    // ---------------------------------------------------------------- deriveStatus

    @Test
    void deriveStatus_coversThreeOutcomes() {
        assertThat(GoodsReceiptRules.deriveStatus(cap3(true, 3, 3, 0, 0, 0), true))
                .isEqualTo(PositionStatus.RECEIVED);
        assertThat(GoodsReceiptRules.deriveStatus(cap3(true, 3, 1, 0, 0, 0), true))
                .isEqualTo(PositionStatus.PARTIALLY_RECEIVED);
        assertThat(GoodsReceiptRules.deriveStatus(cap3(true, 3, 0, 0, 0, 0), false))
                .isEqualTo(PositionStatus.IN_DELIVERY);
    }

    // ---------------------------------------------------------------- validateEvent: error keys

    @Test
    void quantityInvalid_zeroNegativeNullAndScale() {
        Ledger before = cap3(true, 3, 0, 0, 0, 0);
        for (BigDecimal q : new BigDecimal[]{ZERO, d(-1), null, new BigDecimal("1.0001")}) {
            assertError(() -> GoodsReceiptRules.validateEvent(ReceiptKind.ACCEPTED, q, false, null, before, null),
                    HttpStatus.BAD_REQUEST, "error.goodsReceipt.quantity.invalid");
        }
        assertThatCode(() -> GoodsReceiptRules.validateEvent(ReceiptKind.ACCEPTED, new BigDecimal("1.123"),
                false, null, before, null)).doesNotThrowAnyException();
    }

    @Test
    void fromAcceptedInvalid_forNonReturnedKinds() {
        Ledger before = cap3(true, 3, 3, 0, 0, 0);
        for (ReceiptKind k : new ReceiptKind[]{ReceiptKind.ACCEPTED, ReceiptKind.MISSING}) {
            assertError(() -> GoodsReceiptRules.validateEvent(k, d(1), true, "c", before, null),
                    HttpStatus.BAD_REQUEST, "error.goodsReceipt.fromAccepted.invalid");
        }
    }

    @Test
    void commentRequired_forMissingAndReturned() {
        Ledger before = cap3(true, 3, 0, 0, 0, 0);
        for (ReceiptKind k : new ReceiptKind[]{ReceiptKind.MISSING, ReceiptKind.RETURNED}) {
            for (String c : new String[]{null, "", "   "}) {
                assertError(() -> GoodsReceiptRules.validateEvent(k, d(1), false, c, before, null),
                        HttpStatus.BAD_REQUEST, "error.goodsReceipt.comment.required");
            }
        }
        assertThatCode(() -> GoodsReceiptRules.validateEvent(ReceiptKind.ACCEPTED, d(1), false, null, before, null))
                .doesNotThrowAnyException();
    }

    @Test
    void exceedsInFlight_forMissingAndDoorReturn() {
        Ledger before = cap3(true, 3, 2, 0, 0, 0); // inFlight 1
        for (ReceiptKind k : new ReceiptKind[]{ReceiptKind.MISSING, ReceiptKind.RETURNED}) {
            assertError(() -> GoodsReceiptRules.validateEvent(k, d(2), false, "c", before, null),
                    HttpStatus.CONFLICT, "error.goodsReceipt.exceedsInFlight");
            assertThatCode(() -> GoodsReceiptRules.validateEvent(k, d(1), false, "c", before, null))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void exceedsInFlight_whenLineUnresolvedIsSmaller() {
        Ledger before = cap3(true, 3, 0, 0, 0, 0); // inFlight 3
        assertError(() -> GoodsReceiptRules.validateEvent(ReceiptKind.MISSING, d(2), false, "c", before, d(1)),
                HttpStatus.CONFLICT, "error.goodsReceipt.exceedsInFlight");
        assertThatCode(() -> GoodsReceiptRules.validateEvent(ReceiptKind.MISSING, d(1), false, "c", before, d(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void returnExceedsAccepted_forReturnFromAccepted() {
        Ledger before = cap3(true, 3, 2, 0, 0, 0); // acceptedNet 2
        assertError(() -> GoodsReceiptRules.validateEvent(ReceiptKind.RETURNED, d(3), true, "c", before, null),
                HttpStatus.CONFLICT, "error.goodsReceipt.returnExceedsAccepted");
        assertThatCode(() -> GoodsReceiptRules.validateEvent(ReceiptKind.RETURNED, d(2), true, "c", before, null))
                .doesNotThrowAnyException();
    }

    @Test
    void accepted_hasNoUpperBound() {
        Ledger before = cap3(true, 3, 3, 0, 0, 0);
        assertThatCode(() -> GoodsReceiptRules.validateEvent(ReceiptKind.ACCEPTED, d(100), false, null, before, null))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- batch ordering

    private record Ev(ReceiptKind kind, long qty) {
    }

    /** Applies events in order, validating each against the ledger built from the previous ones. */
    private static void applyBatch(List<Ev> batch) {
        long accepted = 0;
        long missing = 0;
        long returnedDoor = 0;
        for (Ev e : batch) {
            Ledger before = cap3(true, 3, accepted, missing, returnedDoor, 0);
            GoodsReceiptRules.validateEvent(e.kind(), d(e.qty()), false, "comment", before, null);
            switch (e.kind()) {
                case ACCEPTED -> accepted += e.qty();
                case MISSING -> missing += e.qty();
                case RETURNED -> returnedDoor += e.qty();
            }
        }
    }

    @Test
    void batchInOrder_acceptedReturnedMissing_isValid() {
        assertThatCode(() -> applyBatch(List.of(new Ev(ReceiptKind.ACCEPTED, 1), new Ev(ReceiptKind.RETURNED, 1),
                new Ev(ReceiptKind.MISSING, 1)))).doesNotThrowAnyException();
    }

    @Test
    void batchOverflowingInFlight_isRejectedAtTheOffendingEvent() {
        assertError(() -> applyBatch(List.of(new Ev(ReceiptKind.ACCEPTED, 1), new Ev(ReceiptKind.RETURNED, 1),
                        new Ev(ReceiptKind.MISSING, 2))),
                HttpStatus.CONFLICT, "error.goodsReceipt.exceedsInFlight");
    }

    // ---------------------------------------------------------------- canVoid

    @Test
    void canVoid_adminAndManagerAnyTime() {
        Instant now = Instant.parse("2026-01-10T12:00:00Z");
        Instant old = now.minus(Duration.ofDays(30));
        assertThat(GoodsReceiptRules.canVoid(Set.of("ADMIN"), 1L, 2L, old, now, WINDOW)).isTrue();
        assertThat(GoodsReceiptRules.canVoid(Set.of("MANAGER"), 1L, 2L, old, now, WINDOW)).isTrue();
    }

    @Test
    void canVoid_authorOnlyWithinWindow() {
        Instant now = Instant.parse("2026-01-10T12:00:00Z");
        Set<String> foreman = Set.of("FOREMAN");
        assertThat(GoodsReceiptRules.canVoid(foreman, 5L, 5L, now.minus(Duration.ofHours(1)), now, WINDOW)).isTrue();
        assertThat(GoodsReceiptRules.canVoid(foreman, 5L, 5L, now.minus(WINDOW), now, WINDOW)).isTrue();
        assertThat(GoodsReceiptRules.canVoid(foreman, 5L, 5L, now.minus(WINDOW).minusSeconds(1), now, WINDOW))
                .isFalse();
    }

    @Test
    void canVoid_nonAuthorWithoutPrivilegedRoleDenied() {
        Instant now = Instant.parse("2026-01-10T12:00:00Z");
        assertThat(GoodsReceiptRules.canVoid(Set.of("FOREMAN"), 5L, 6L, now, now, WINDOW)).isFalse();
        assertThat(GoodsReceiptRules.canVoid(Set.of(), null, null, now, now, WINDOW)).isFalse();
    }

    // ---------------------------------------------------------------- media

    private static final MediaLimits LIMITS = new MediaLimits(8, 10, 1024);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    private static List<MediaMeta> files(int n) {
        List<MediaMeta> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new MediaMeta("image/jpeg", 10, JPEG));
        }
        return out;
    }

    @Test
    void media_validFilesOfAllTypesAccepted() {
        assertThatCode(() -> GoodsReceiptRules.validateMedia(List.of(
                new MediaMeta("image/jpeg", 10, JPEG),
                new MediaMeta("image/png", 10, PNG),
                new MediaMeta("IMAGE/WEBP", 1024, WEBP)), 0, LIMITS)).doesNotThrowAnyException();
    }

    @Test
    void media_countLimits() {
        assertThatCode(() -> GoodsReceiptRules.validateMedia(files(8), 0, LIMITS)).doesNotThrowAnyException();
        assertError(() -> GoodsReceiptRules.validateMedia(files(9), 0, LIMITS),
                HttpStatus.BAD_REQUEST, "error.goodsReceipt.media.count");
        assertThatCode(() -> GoodsReceiptRules.validateMedia(files(3), 7, LIMITS)).doesNotThrowAnyException();
        assertError(() -> GoodsReceiptRules.validateMedia(files(3), 8, LIMITS),
                HttpStatus.BAD_REQUEST, "error.goodsReceipt.media.count");
    }

    @Test
    void media_typeRules() {
        assertError(() -> GoodsReceiptRules.validateMedia(List.of(new MediaMeta("application/pdf", 10, JPEG)), 0, LIMITS),
                HttpStatus.BAD_REQUEST, "error.goodsReceipt.media.type");
        assertError(() -> GoodsReceiptRules.validateMedia(List.of(new MediaMeta(null, 10, JPEG)), 0, LIMITS),
                HttpStatus.BAD_REQUEST, "error.goodsReceipt.media.type");
        // declared PNG but JPEG bytes
        assertError(() -> GoodsReceiptRules.validateMedia(List.of(new MediaMeta("image/png", 10, JPEG)), 0, LIMITS),
                HttpStatus.BAD_REQUEST, "error.goodsReceipt.media.type");
        // PDF magic bytes are not an accepted image
        assertError(() -> GoodsReceiptRules.validateMedia(
                        List.of(new MediaMeta("image/jpeg", 10, new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4'})), 0, LIMITS),
                HttpStatus.BAD_REQUEST, "error.goodsReceipt.media.type");
    }

    @Test
    void media_sizeLimit() {
        assertError(() -> GoodsReceiptRules.validateMedia(List.of(new MediaMeta("image/jpeg", 1025, JPEG)), 0, LIMITS),
                HttpStatus.BAD_REQUEST, "error.goodsReceipt.media.size");
    }
}
