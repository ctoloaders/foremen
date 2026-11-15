package com.foremen.service.procurement.receipt;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ReceiptKind;
import com.foremen.exception.ForemenApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** FOR-11-05: pure goods-receipt rules (no Spring context, no persistence, no money). */
public final class GoodsReceiptRules {

    private static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private GoodsReceiptRules() {
    }

    /** Non-voided event sums of one position plus the shipment facts. */
    public record Inputs(BigDecimal cap, boolean hasShipments, BigDecimal shippedGross,
                         BigDecimal accepted, BigDecimal missing,
                         BigDecimal returnedDoor, BigDecimal returnedAccepted) {
    }

    public record Ledger(BigDecimal cap, BigDecimal acceptedNet, BigDecimal missing, BigDecimal returned,
                         BigDecimal outstanding, BigDecimal surplus, BigDecimal inFlight,
                         BigDecimal committed, BigDecimal remainder) {
    }

    public record MediaMeta(String declaredType, long size, byte[] head) {
    }

    public record MediaLimits(int maxPerRequest, int maxPerEvent, long maxFileSize) {
    }

    public static Ledger ledger(Inputs in) {
        BigDecimal returned = in.returnedDoor().add(in.returnedAccepted());
        // Without shipments the virtual expectation is the cap, but only once an event exists. With no events
        // and no shipments nothing is committed, so the FOR-11-04 cap rule is unchanged (Property 6).
        boolean anyEvent = in.accepted().signum() > 0 || in.missing().signum() > 0 || returned.signum() > 0;
        BigDecimal expected = in.hasShipments() ? in.shippedGross() : (anyEvent ? in.cap() : BigDecimal.ZERO);
        BigDecimal shippedEff = expected.subtract(in.missing()).subtract(returned).max(BigDecimal.ZERO);
        BigDecimal acceptedNet = in.accepted().subtract(in.returnedAccepted());
        BigDecimal committed = shippedEff.max(acceptedNet);
        BigDecimal inFlight = shippedEff.subtract(acceptedNet).max(BigDecimal.ZERO);
        BigDecimal outstanding = in.cap().subtract(acceptedNet).max(BigDecimal.ZERO);
        BigDecimal surplus = acceptedNet.subtract(in.cap()).max(BigDecimal.ZERO);
        BigDecimal remainder = in.cap().subtract(committed).max(BigDecimal.ZERO);
        return new Ledger(in.cap(), acceptedNet, in.missing(), returned, outstanding, surplus, inFlight,
                committed, remainder);
    }

    /** R6.1: {@code RECEIVED} when fully accepted, {@code PARTIALLY_RECEIVED} with any event, else {@code IN_DELIVERY}. */
    public static PositionStatus deriveStatus(Ledger l, boolean anyNonVoidedEvent) {
        if (l.acceptedNet().compareTo(l.cap()) >= 0) {
            return PositionStatus.RECEIVED;
        }
        return anyNonVoidedEvent ? PositionStatus.PARTIALLY_RECEIVED : PositionStatus.IN_DELIVERY;
    }

    public static void validateEvent(ReceiptKind kind, BigDecimal quantity, boolean fromAccepted,
                                     String comment, Ledger before, BigDecimal lineUnresolvedOrNull) {
        if (quantity == null || quantity.signum() <= 0 || quantity.scale() > 3) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.quantity.invalid")
                    .withField("quantity");
        }
        if (kind == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.quantity.invalid")
                    .withField("kind");
        }
        if (fromAccepted && kind != ReceiptKind.RETURNED) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.fromAccepted.invalid")
                    .withField("fromAccepted");
        }
        if (kind != ReceiptKind.ACCEPTED && (comment == null || comment.isBlank())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.comment.required")
                    .withField("comment");
        }
        if (kind == ReceiptKind.RETURNED && fromAccepted) {
            if (quantity.compareTo(before.acceptedNet()) > 0) {
                throw new ForemenApiException(HttpStatus.CONFLICT, "error.goodsReceipt.returnExceedsAccepted");
            }
        } else if (kind != ReceiptKind.ACCEPTED) {
            if (quantity.compareTo(before.inFlight()) > 0
                    || (lineUnresolvedOrNull != null && quantity.compareTo(lineUnresolvedOrNull) > 0)) {
                throw new ForemenApiException(HttpStatus.CONFLICT, "error.goodsReceipt.exceedsInFlight");
            }
        }
    }

    /** R5.2: ADMIN or MANAGER any time; the author only within the window. */
    public static boolean canVoid(Set<String> roles, Long authorId, Long userId,
                                  Instant occurredAt, Instant now, Duration window) {
        if (roles != null && (roles.contains("ADMIN") || roles.contains("MANAGER"))) {
            return true;
        }
        return authorId != null && authorId.equals(userId) && occurredAt != null && now != null
                && window != null && !Duration.between(occurredAt, now).minus(window).isPositive();
    }

    /** R8.6: a fromAccepted return never resolves a line, so it is not an argument. */
    public static boolean lineResolved(BigDecimal lineQuantity, BigDecimal accepted, BigDecimal missing,
                                       BigDecimal returnedDoor) {
        return accepted.add(missing).add(returnedDoor).compareTo(lineQuantity) >= 0;
    }

    public static BigDecimal redeliveryQuantity(Ledger after, BigDecimal releasedByRequest) {
        return after.remainder().min(releasedByRequest).max(BigDecimal.ZERO);
    }

    public static Set<Long> recipients(Set<Long> managers, Set<Long> buyers, Long actor) {
        Set<Long> out = new LinkedHashSet<>();
        if (managers != null) {
            out.addAll(managers);
        }
        if (buyers != null) {
            out.addAll(buyers);
        }
        out.remove(actor);
        return out;
    }

    public static void validateMedia(List<MediaMeta> incoming, int existingCount, MediaLimits limits) {
        if (incoming.size() > limits.maxPerRequest() || existingCount + incoming.size() > limits.maxPerEvent()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.media.count");
        }
        for (MediaMeta m : incoming) {
            String declared = m.declaredType() == null ? null : m.declaredType().toLowerCase();
            if (declared == null || !MEDIA_TYPES.contains(declared) || !declared.equals(sniff(m.head()))) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.media.type");
            }
            if (m.size() > limits.maxFileSize()) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.media.size");
            }
        }
    }

    /** Content type implied by the leading magic bytes (JPEG, PNG, WebP), else {@code null}. */
    static String sniff(byte[] h) {
        if (h == null) {
            return null;
        }
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) {
            return "image/png";
        }
        if (h.length >= 12 && "RIFF".equals(new String(h, 0, 4, StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(h, 8, 4, StandardCharsets.US_ASCII))) {
            return "image/webp";
        }
        return null;
    }
}
