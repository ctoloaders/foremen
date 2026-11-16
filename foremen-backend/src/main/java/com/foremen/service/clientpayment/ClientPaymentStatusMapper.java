package com.foremen.service.clientpayment;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * FOR-11-06 (R1.9): the single pure mapping between the per-kind stored status (TrancheStatus for WORKS,
 * ProformaStatus for MATERIALS, both given by enum name) and the kind-independent {@link DisplayStatus},
 * plus its inverse for the list status filter and the derived overdue flag.
 */
public final class ClientPaymentStatusMapper {

    /** Constraint on the due date that a stored status must satisfy to map to a given display status. */
    public enum DueRule {
        ANY,
        /** The due date is before today. */
        PAST_DUE,
        /** The due date is null or not before today. */
        NOT_PAST_DUE;

        public boolean accepts(LocalDate dueDate, LocalDate today) {
            return switch (this) {
                case ANY -> true;
                case PAST_DUE -> isPastDue(dueDate, today);
                case NOT_PAST_DUE -> !isPastDue(dueDate, today);
            };
        }
    }

    /** One stored status together with the due-date condition under which it maps to a display status. */
    public record StoredMatch(String storedStatus, DueRule dueRule) {
        public boolean accepts(String stored, LocalDate dueDate, LocalDate today) {
            return storedStatus.equals(stored) && dueRule.accepts(dueDate, today);
        }
    }

    private ClientPaymentStatusMapper() {
    }

    /**
     * Maps a stored status to its display status.
     *
     * @throws IllegalArgumentException when the stored status is not valid for the kind
     */
    public static DisplayStatus toDisplay(ClientPaymentKind kind, String storedStatus, LocalDate dueDate,
                                          LocalDate today) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(storedStatus, "storedStatus");
        Objects.requireNonNull(today, "today");
        return switch (kind) {
            case WORKS -> switch (storedStatus) {
                case "PENDING" -> DisplayStatus.PLANNED;
                case "DUE" -> DisplayStatus.DUE;
                case "PARTIALLY_PAID" -> DisplayStatus.PARTIALLY_PAID;
                case "PAID" -> DisplayStatus.PAID;
                case "OVERDUE" -> DisplayStatus.OVERDUE;
                default -> throw unknown(kind, storedStatus);
            };
            case MATERIALS -> switch (storedStatus) {
                case "DRAFT" -> DisplayStatus.DRAFT;
                case "ISSUED" -> isPastDue(dueDate, today) ? DisplayStatus.OVERDUE : DisplayStatus.ISSUED;
                case "PARTIALLY_PAID" ->
                        isPastDue(dueDate, today) ? DisplayStatus.OVERDUE : DisplayStatus.PARTIALLY_PAID;
                case "PAID" -> DisplayStatus.PAID;
                case "CANCELLED" -> DisplayStatus.CANCELLED;
                default -> throw unknown(kind, storedStatus);
            };
        };
    }

    /**
     * Inverse for the status filter: every (stored status, due-date rule) pair of the kind that maps to the
     * display status. Empty when the kind never shows that display status.
     */
    public static List<StoredMatch> fromDisplay(ClientPaymentKind kind, DisplayStatus display) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(display, "display");
        return switch (kind) {
            case WORKS -> switch (display) {
                case PLANNED -> List.of(new StoredMatch("PENDING", DueRule.ANY));
                case DUE -> List.of(new StoredMatch("DUE", DueRule.ANY));
                case PARTIALLY_PAID -> List.of(new StoredMatch("PARTIALLY_PAID", DueRule.ANY));
                case PAID -> List.of(new StoredMatch("PAID", DueRule.ANY));
                case OVERDUE -> List.of(new StoredMatch("OVERDUE", DueRule.ANY));
                case DRAFT, ISSUED, CANCELLED -> List.of();
            };
            case MATERIALS -> switch (display) {
                case DRAFT -> List.of(new StoredMatch("DRAFT", DueRule.ANY));
                case ISSUED -> List.of(new StoredMatch("ISSUED", DueRule.NOT_PAST_DUE));
                case PARTIALLY_PAID -> List.of(new StoredMatch("PARTIALLY_PAID", DueRule.NOT_PAST_DUE));
                case PAID -> List.of(new StoredMatch("PAID", DueRule.ANY));
                case CANCELLED -> List.of(new StoredMatch("CANCELLED", DueRule.ANY));
                case OVERDUE -> List.of(new StoredMatch("ISSUED", DueRule.PAST_DUE),
                        new StoredMatch("PARTIALLY_PAID", DueRule.PAST_DUE));
                case PLANNED, DUE -> List.of();
            };
        };
    }

    /**
     * Derived overdue flag: due date before today and the payment is neither paid nor cancelled. A stored
     * works status OVERDUE is always overdue.
     */
    public static boolean overdue(ClientPaymentKind kind, String storedStatus, LocalDate dueDate,
                                  LocalDate today) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(storedStatus, "storedStatus");
        Objects.requireNonNull(today, "today");
        // validates the pair
        toDisplay(kind, storedStatus, dueDate, today);
        if (kind == ClientPaymentKind.WORKS && "OVERDUE".equals(storedStatus)) {
            return true;
        }
        if ("PAID".equals(storedStatus) || "CANCELLED".equals(storedStatus)) {
            return false;
        }
        return isPastDue(dueDate, today);
    }

    private static boolean isPastDue(LocalDate dueDate, LocalDate today) {
        return dueDate != null && dueDate.isBefore(today);
    }

    private static IllegalArgumentException unknown(ClientPaymentKind kind, String storedStatus) {
        return new IllegalArgumentException("Unknown stored status '" + storedStatus + "' for kind " + kind);
    }
}
