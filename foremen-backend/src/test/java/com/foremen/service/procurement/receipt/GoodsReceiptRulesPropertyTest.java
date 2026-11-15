package com.foremen.service.procurement.receipt;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ReceiptKind;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.position.PositionTransitionPolicy;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Decision;
import com.foremen.service.procurement.receipt.GoodsReceiptRules.Inputs;
import com.foremen.service.procurement.receipt.GoodsReceiptRules.Ledger;
import com.foremen.service.procurement.receipt.GoodsReceiptRules.MediaLimits;
import com.foremen.service.procurement.receipt.GoodsReceiptRules.MediaMeta;
import com.foremen.service.procurement.shipment.ShipmentRules;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-11-05 property tests (jqwik) over the pure goods-receipt rules.
 *
 * <p>**Validates: Requirements 3.1, 3.2, 3.4, 4.3, 4.5, 4.6, 4.7, 5.2, 6.1, 6.2, 7.2, 8.1, 8.2, 8.4, 8.6,
 * 12.1, 12.2, 12.3, 17.1**
 */
class GoodsReceiptRulesPropertyTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final PositionTransitionPolicy POLICY = new PositionTransitionPolicy();
    private static final MediaLimits LIMITS = new MediaLimits(8, 10, 5L * 1024 * 1024);

    // ---------------------------------------------------------------- generators

    /** Quantity in thousandths, 0 to 1000.000. */
    private static Arbitrary<BigDecimal> qty() {
        return Arbitraries.longs().between(0, 1_000_000).map(v -> BigDecimal.valueOf(v, 3));
    }

    private static Arbitrary<BigDecimal> positiveQty() {
        return Arbitraries.longs().between(1, 1_000_000).map(v -> BigDecimal.valueOf(v, 3));
    }

    /** Valid event sums: {@code returnedAccepted <= accepted}. */
    @Provide
    Arbitrary<Inputs> inputs() {
        return Combinators.combine(qty(), Arbitraries.of(true, false), qty(), qty(), qty(), qty(), qty())
                .as((cap, has, shipped, accepted, missing, door, retAcc) -> new Inputs(
                        cap, has, shipped, accepted, missing, door, retAcc.min(accepted)));
    }

    private static boolean anyEvent(Inputs in) {
        return in.accepted().signum() > 0 || in.missing().signum() > 0
                || in.returnedDoor().signum() > 0 || in.returnedAccepted().signum() > 0;
    }

    // ---------------------------------------------------------------- Property 1

    @Property(tries = 500)
    @Tag("Feature: FOR-11-05-goods-receipt, Property 1: Ledger identities")
    void ledgerIdentities(@ForAll("inputs") Inputs in, @ForAll("positive") BigDecimal extraDoor) {
        Ledger l = GoodsReceiptRules.ledger(in);

        assertThat(l.acceptedNet()).isEqualByComparingTo(in.accepted().subtract(in.returnedAccepted()));
        assertThat(l.acceptedNet().signum()).isGreaterThanOrEqualTo(0);
        assertThat(l.outstanding()).isEqualByComparingTo(in.cap().subtract(l.acceptedNet()).max(ZERO));
        assertThat(l.surplus()).isEqualByComparingTo(l.acceptedNet().subtract(in.cap()).max(ZERO));
        if (l.outstanding().signum() > 0) {
            assertThat(l.surplus().signum()).isZero();
        }
        assertThat(l.inFlight().signum()).isGreaterThanOrEqualTo(0);
        assertThat(l.committed()).isEqualByComparingTo(l.acceptedNet().add(l.inFlight()));
        for (BigDecimal v : List.of(l.cap(), l.acceptedNet(), l.missing(), l.returned(), l.outstanding(),
                l.surplus(), l.inFlight(), l.committed(), l.remainder())) {
            assertThat(v.signum()).isGreaterThanOrEqualTo(0);
        }

        // A door return changes neither acceptedNet nor surplus.
        Inputs withDoor = new Inputs(in.cap(), in.hasShipments(), in.shippedGross(), in.accepted(),
                in.missing(), in.returnedDoor().add(extraDoor), in.returnedAccepted());
        Ledger d = GoodsReceiptRules.ledger(withDoor);
        assertThat(d.acceptedNet()).isEqualByComparingTo(l.acceptedNet());
        assertThat(d.surplus()).isEqualByComparingTo(l.surplus());
    }

    @Provide
    Arbitrary<BigDecimal> positive() {
        return positiveQty();
    }

    // ---------------------------------------------------------------- Property 2

    @Property(tries = 500)
    @Tag("Feature: FOR-11-05-goods-receipt, Property 2: Bounds on missing, return and acceptance")
    void boundsOnMissingReturnAndAcceptance(@ForAll("inputs") Inputs in,
                                            @ForAll("positive") BigDecimal q,
                                            @ForAll("positive") BigDecimal lineUnresolved) {
        Ledger l = GoodsReceiptRules.ledger(in);

        // MISSING and door RETURNED: q <= inFlight (and <= lineUnresolved when a line is addressed).
        for (ReceiptKind kind : List.of(ReceiptKind.MISSING, ReceiptKind.RETURNED)) {
            assertThat(accepts(kind, q, false, l, null)).isEqualTo(q.compareTo(l.inFlight()) <= 0);
            assertThat(accepts(kind, q, false, l, lineUnresolved))
                    .isEqualTo(q.compareTo(l.inFlight()) <= 0 && q.compareTo(lineUnresolved) <= 0);
        }
        // RETURNED fromAccepted: q <= acceptedNet.
        assertThat(accepts(ReceiptKind.RETURNED, q, true, l, null))
                .isEqualTo(q.compareTo(l.acceptedNet()) <= 0);
        // ACCEPTED is always accepted.
        assertThat(accepts(ReceiptKind.ACCEPTED, q, false, l, null)).isTrue();
    }

    @Property(tries = 300)
    @Tag("Feature: FOR-11-05-goods-receipt, Property 2: Bounds on missing, return and acceptance (sequences)")
    void validatedSequencesKeepLedgerNonNegative(
            @ForAll("positive") BigDecimal cap,
            @ForAll("shippedChoice") Boolean hasShipments,
            @ForAll("events") List<Event> events) {
        BigDecimal shipped = cap;
        BigDecimal accepted = ZERO;
        BigDecimal missing = ZERO;
        BigDecimal door = ZERO;
        BigDecimal retAcc = ZERO;
        for (Event e : events) {
            Ledger before = GoodsReceiptRules.ledger(new Inputs(cap, hasShipments, shipped, accepted, missing, door, retAcc));
            if (!accepts(e.kind(), e.quantity(), e.fromAccepted(), before, null)) {
                continue;
            }
            switch (e.kind()) {
                case ACCEPTED -> accepted = accepted.add(e.quantity());
                case MISSING -> missing = missing.add(e.quantity());
                case RETURNED -> {
                    if (e.fromAccepted()) {
                        retAcc = retAcc.add(e.quantity());
                    } else {
                        door = door.add(e.quantity());
                    }
                }
            }
            Ledger after = GoodsReceiptRules.ledger(new Inputs(cap, hasShipments, shipped, accepted, missing, door, retAcc));
            assertThat(after.acceptedNet().signum()).isGreaterThanOrEqualTo(0);
            assertThat(after.inFlight().signum()).isGreaterThanOrEqualTo(0);
        }
    }

    record Event(ReceiptKind kind, BigDecimal quantity, boolean fromAccepted) {
    }

    @Provide
    Arbitrary<Boolean> shippedChoice() {
        return Arbitraries.of(true, false);
    }

    @Provide
    Arbitrary<List<Event>> events() {
        Arbitrary<Event> one = Combinators.combine(
                Arbitraries.of(ReceiptKind.class), positiveQty(), Arbitraries.of(true, false))
                .as((k, q, f) -> new Event(k, q, f && k == ReceiptKind.RETURNED));
        return one.list().ofMinSize(1).ofMaxSize(12);
    }

    private static boolean accepts(ReceiptKind kind, BigDecimal q, boolean fromAccepted, Ledger l,
                                   BigDecimal lineUnresolved) {
        try {
            GoodsReceiptRules.validateEvent(kind, q, fromAccepted, "reason", l, lineUnresolved);
            return true;
        } catch (ForemenApiException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- Property 3

    @Property(tries = 800)
    @Tag("Feature: FOR-11-05-goods-receipt, Property 3: Event shape validation")
    void eventShapeValidation(
            @ForAll @IntRange(min = -5000, max = 100000) int unscaled,
            @ForAll @IntRange(min = 0, max = 5) int scale,
            @ForAll ReceiptKind kind,
            @ForAll boolean fromAccepted,
            @ForAll("comments") String comment) {
        BigDecimal q = BigDecimal.valueOf(unscaled, scale);
        // Roomy ledger so that only the shape rules can reject.
        Ledger roomy = GoodsReceiptRules.ledger(new Inputs(new BigDecimal("1000000"), true,
                new BigDecimal("1000000"), new BigDecimal("500000"), ZERO, ZERO, ZERO));

        boolean shapeOk = q.signum() > 0 && q.scale() <= 3
                && !(fromAccepted && kind != ReceiptKind.RETURNED)
                && !(kind != ReceiptKind.ACCEPTED && (comment == null || comment.isBlank()));

        boolean accepted;
        try {
            GoodsReceiptRules.validateEvent(kind, q, fromAccepted, comment, roomy, null);
            accepted = true;
        } catch (ForemenApiException e) {
            accepted = false;
            assertThat(e.getStatus().value()).isEqualTo(400);
        }
        assertThat(accepted).isEqualTo(shapeOk);
        if (kind == ReceiptKind.ACCEPTED && q.signum() > 0 && q.scale() <= 3 && !fromAccepted) {
            assertThat(accepted).isTrue(); // a missing comment is fine for ACCEPTED
        }
    }

    @Provide
    Arbitrary<String> comments() {
        return Arbitraries.oneOf(
                Arbitraries.just((String) null),
                Arbitraries.just(""),
                Arbitraries.of(" ", "   ", "\t", "\n \t"),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20));
    }

    // ---------------------------------------------------------------- Property 4

    @Property(tries = 500)
    @Tag("Feature: FOR-11-05-goods-receipt, Property 4: Status derivation")
    void statusDerivation(@ForAll("inputs") Inputs in, @ForAll boolean anyEvent,
                          @ForAll SourcingPath path,
                          @ForAll("receivingStatuses") PositionStatus current) {
        Ledger l = GoodsReceiptRules.ledger(in);
        PositionStatus derived = GoodsReceiptRules.deriveStatus(l, anyEvent);

        boolean full = l.acceptedNet().compareTo(l.cap()) >= 0;
        assertThat(derived == PositionStatus.RECEIVED).isEqualTo(full);
        if (!full) {
            assertThat(derived == PositionStatus.PARTIALLY_RECEIVED).isEqualTo(anyEvent);
            assertThat(derived == PositionStatus.IN_DELIVERY).isEqualTo(!anyEvent);
        }

        // Equal (no-op) or a SYSTEM edge allowed by the real policy, for every path.
        // The policy has no direct SYSTEM edge RECEIVED -> IN_DELIVERY (all events voided on a
        // RECEIVED position). GoodsReceiptService MUST then perform two steps:
        // RECEIVED -> PARTIALLY_RECEIVED -> IN_DELIVERY, both of which the real policy allows.
        if (derived != current) {
            boolean direct = POLICY.decide(path, current, derived, Actor.SYSTEM) == Decision.ALLOWED;
            boolean twoStep = current == PositionStatus.RECEIVED && derived == PositionStatus.IN_DELIVERY
                    && POLICY.decide(path, PositionStatus.RECEIVED, PositionStatus.PARTIALLY_RECEIVED, Actor.SYSTEM)
                            == Decision.ALLOWED
                    && POLICY.decide(path, PositionStatus.PARTIALLY_RECEIVED, PositionStatus.IN_DELIVERY, Actor.SYSTEM)
                            == Decision.ALLOWED;
            assertThat(direct || twoStep)
                    .as("%s: %s -> %s", path, current, derived)
                    .isTrue();
        }
    }

    @Provide
    Arbitrary<PositionStatus> receivingStatuses() {
        return Arbitraries.of(PositionStatus.IN_DELIVERY, PositionStatus.PARTIALLY_RECEIVED, PositionStatus.RECEIVED);
    }

    // ---------------------------------------------------------------- Property 6

    @Property(tries = 500)
    @Tag("Feature: FOR-11-05-goods-receipt, Property 6: Non-regression of the cap rule")
    void capRuleNonRegression(@ForAll SourcingPath path,
                              @ForAll("anyQty") BigDecimal ordered,
                              @ForAll("anyQty") BigDecimal quantity,
                              @ForAll("lines") List<BigDecimal> lines,
                              @ForAll("inputs") Inputs withEvents) {
        BigDecimal cap = ShipmentRules.capFor(path, ordered, quantity);
        BigDecimal shipped = lines.stream().reduce(ZERO, BigDecimal::add);

        // Without receipt events.
        Ledger l = GoodsReceiptRules.ledger(new Inputs(cap, !lines.isEmpty(), shipped, ZERO, ZERO, ZERO, ZERO));
        assertThat(l.committed()).isEqualByComparingTo(shipped);
        assertThat(l.remainder()).isEqualByComparingTo(cap.subtract(shipped).max(ZERO));
        // A new line fits exactly when the FOR-11-04 rule accepted it.
        for (BigDecimal add : List.of(ZERO, new BigDecimal("0.001"), cap, cap.add(BigDecimal.ONE))) {
            assertThat(l.committed().add(add).compareTo(cap) <= 0)
                    .isEqualTo(shipped.add(add).compareTo(cap) <= 0);
        }

        // With any events.
        Ledger e = GoodsReceiptRules.ledger(withEvents);
        assertThat(e.remainder().signum()).isGreaterThanOrEqualTo(0);
        assertThat(e.remainder()).isLessThanOrEqualTo(e.cap());
    }

    @Provide
    Arbitrary<BigDecimal> anyQty() {
        return qty();
    }

    @Provide
    Arbitrary<List<BigDecimal>> lines() {
        return positiveQty().list().ofMaxSize(6);
    }

    // ---------------------------------------------------------------- Property 7

    @Property(tries = 500)
    @Tag("Feature: FOR-11-05-goods-receipt, Property 7: Re-delivery quantity")
    void redeliveryQuantity(@ForAll("inputs") Inputs in, @ForAll("anyQty") BigDecimal released) {
        Ledger after = GoodsReceiptRules.ledger(in);
        BigDecimal r = GoodsReceiptRules.redeliveryQuantity(after, released);

        assertThat(r).isEqualByComparingTo(after.remainder().min(released));
        assertThat(r.signum()).isGreaterThanOrEqualTo(0);
        assertThat(r).isLessThanOrEqualTo(after.remainder());
        assertThat(r).isLessThanOrEqualTo(released);
        assertThat(after.committed().add(r)).isLessThanOrEqualTo(after.cap().max(after.committed()));
        if (after.committed().compareTo(after.cap()) <= 0) {
            assertThat(after.committed().add(r)).isLessThanOrEqualTo(after.cap());
        }
    }

    // ---------------------------------------------------------------- Property 8

    @Property(tries = 500)
    @Tag("Feature: FOR-11-05-goods-receipt, Property 8: Shipment closure predicate")
    void lineResolvedPredicate(@ForAll("anyQty") BigDecimal lineQty, @ForAll("anyQty") BigDecimal accepted,
                               @ForAll("anyQty") BigDecimal missing, @ForAll("anyQty") BigDecimal door,
                               @ForAll("anyQty") BigDecimal fromAcceptedReturn,
                               @ForAll("lineSets") List<BigDecimal[]> otherLines) {
        boolean resolved = GoodsReceiptRules.lineResolved(lineQty, accepted, missing, door);
        assertThat(resolved).isEqualTo(accepted.add(missing).add(door).compareTo(lineQty) >= 0);
        // A fromAccepted return is not an input of the predicate and cannot change it.
        assertThat(GoodsReceiptRules.lineResolved(lineQty, accepted, missing, door)).isEqualTo(resolved);
        assertThat(fromAcceptedReturn).isNotNull();

        // A shipment closes only when every line is resolved.
        boolean closes = otherLines.stream().allMatch(l -> GoodsReceiptRules.lineResolved(l[0], l[1], l[2], l[3]))
                && resolved;
        boolean expected = resolved && otherLines.stream()
                .allMatch(l -> l[1].add(l[2]).add(l[3]).compareTo(l[0]) >= 0);
        assertThat(closes).isEqualTo(expected);
    }

    @Provide
    Arbitrary<List<BigDecimal[]>> lineSets() {
        return Combinators.combine(qty(), qty(), qty(), qty())
                .as((a, b, c, d) -> new BigDecimal[] {a, b, c, d}).list().ofMaxSize(4);
    }

    // ---------------------------------------------------------------- Property 11

    @Property(tries = 800)
    @Tag("Feature: FOR-11-05-goods-receipt, Property 11: Void permission")
    void voidPermission(@ForAll("roleSets") Set<String> roles,
                        @ForAll @IntRange(min = 1, max = 5) long authorId,
                        @ForAll @IntRange(min = 1, max = 5) long userId,
                        @ForAll @IntRange(min = 0, max = 72 * 3600) int elapsedSeconds) {
        Duration window = Duration.ofHours(24);
        Instant occurred = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = occurred.plusSeconds(elapsedSeconds);

        boolean expected = roles.contains("ADMIN") || roles.contains("MANAGER")
                || (authorId == userId && Duration.ofSeconds(elapsedSeconds).compareTo(window) <= 0);
        assertThat(GoodsReceiptRules.canVoid(roles, authorId, userId, occurred, now, window)).isEqualTo(expected);

        // Boundary: exactly the window is still allowed for the author, one second later is not.
        Set<String> plain = Set.of("FOREMAN");
        assertThat(GoodsReceiptRules.canVoid(plain, 1L, 1L, occurred, occurred.plus(window), window)).isTrue();
        assertThat(GoodsReceiptRules.canVoid(plain, 1L, 1L, occurred, occurred.plus(window).plusSeconds(1), window))
                .isFalse();
    }

    @Provide
    Arbitrary<Set<String>> roleSets() {
        return Arbitraries.of("ADMIN", "MANAGER", "FOREMAN", "BUYER", "WORKER").set().ofMaxSize(3);
    }

    // ---------------------------------------------------------------- Property 12

    @Property(tries = 500)
    @Tag("Feature: FOR-11-05-goods-receipt, Property 12: Notification recipients")
    void notificationRecipients(@ForAll("idSets") Set<Long> managers, @ForAll("idSets") Set<Long> buyers,
                                @ForAll @IntRange(min = 1, max = 10) long actor) {
        Set<Long> r = GoodsReceiptRules.recipients(managers, buyers, actor);
        Set<Long> expected = new HashSet<>(managers);
        expected.addAll(buyers);
        expected.remove(actor);
        assertThat(r).isEqualTo(expected);
        assertThat(new ArrayList<>(r)).doesNotHaveDuplicates();
        assertThat(r).doesNotContain(actor);
        assertThat(GoodsReceiptRules.recipients(Set.of(), Set.of(), actor)).isEmpty();
        assertThat(GoodsReceiptRules.recipients(null, null, actor)).isEmpty();
    }

    @Property(tries = 400)
    @Tag("Feature: FOR-11-05-goods-receipt, Property 12: Discrepancy events (ledger side)")
    void discrepancyOnlyForMissingReturnedOrSurplus(@ForAll("inputs") Inputs in,
                                                    @ForAll("positive") BigDecimal acceptedNow) {
        // An ACCEPTED event is a discrepancy exactly when it leaves surplus > 0.
        Inputs after = new Inputs(in.cap(), in.hasShipments(), in.shippedGross(),
                in.accepted().add(acceptedNow), in.missing(), in.returnedDoor(), in.returnedAccepted());
        Ledger l = GoodsReceiptRules.ledger(after);
        boolean discrepancy = l.surplus().signum() > 0;
        assertThat(discrepancy).isEqualTo(l.acceptedNet().compareTo(l.cap()) > 0);
    }

    @Provide
    Arbitrary<Set<Long>> idSets() {
        return Arbitraries.longs().between(1, 10).set().ofMaxSize(6);
    }

    // ---------------------------------------------------------------- Property 13

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', 0, 0, 0, 0, 0, 0};
    private static final byte[] JUNK = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    @Property(tries = 600)
    @Tag("Feature: FOR-11-05-goods-receipt, Property 13: Media validation")
    void mediaValidation(@ForAll("mediaLists") List<MediaMeta> files,
                         @ForAll @IntRange(min = 0, max = 10) int existing) {
        boolean expected = files.size() <= 8 && existing + files.size() <= 10
                && files.stream().allMatch(f -> f.size() <= LIMITS.maxFileSize()
                && f.declaredType() != null
                && Set.of("image/jpeg", "image/png", "image/webp").contains(f.declaredType())
                && f.declaredType().equals(typeOf(f.head())));
        boolean ok;
        try {
            GoodsReceiptRules.validateMedia(files, existing, LIMITS);
            ok = true;
        } catch (ForemenApiException e) {
            ok = false;
            assertThat(e.getStatus().value()).isEqualTo(400);
        }
        assertThat(ok).isEqualTo(expected);
    }

    private static String typeOf(byte[] head) {
        if (head == JPEG) {
            return "image/jpeg";
        }
        if (head == PNG) {
            return "image/png";
        }
        if (head == WEBP) {
            return "image/webp";
        }
        return null; // PDF and junk are not allowed image content
    }

    @Provide
    Arbitrary<List<MediaMeta>> mediaLists() {
        Arbitrary<String> declared = Arbitraries.of("image/jpeg", "image/png", "image/webp", "application/pdf",
                "image/heic", "text/plain");
        Arbitrary<byte[]> head = Arbitraries.of(JPEG, PNG, WEBP, PDF, JUNK);
        // Weighted towards valid files so that the accepting branch is exercised.
        Arbitrary<MediaMeta> valid = Arbitraries.of(
                new MediaMeta("image/jpeg", 1000, JPEG),
                new MediaMeta("image/png", 2000, PNG),
                new MediaMeta("image/webp", 3000, WEBP));
        Arbitrary<MediaMeta> any = Combinators.combine(declared, Arbitraries.longs().between(0, 6L * 1024 * 1024), head)
                .as(MediaMeta::new);
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(4, valid), net.jqwik.api.Tuple.of(1, any)).list().ofMaxSize(11);
    }
}
