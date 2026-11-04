package com.foremen.service.bank.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.exception.ForemenApiException;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-07-04 task 6.7 — property tests of {@link BankLinkService} over random operation sequences on bank
 * and cash lines.
 *
 * <ul>
 *   <li><b>Property 3 (allocation bound):</b> after every operation (accepted or rejected), for every
 *       line Σ link amounts ≤ |amount|; for every payout Σ ≤ its amount; for every document with a known
 *       total Σ ≤ total; and the {@code link_status} cache equals {@link LinkStatusDeriver} over the
 *       persisted links.</li>
 *   <li><b>Property 9 (locked links):</b> a link with a payment transaction is never changed or removed,
 *       whatever remove / replace / remove-all / ignore operations are attempted.</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 13.4, 13.5, 13.6, 16.7, 17.1</b>
 */
@Tag("Feature: FOR-07-04-bank-ledger, Property 3 + 9: allocation bound, locked links")
class BankLinkAllocationPropertyTest {

    /** One random operation. {@code amountCents == 0} → default amount. */
    record Op(int type, int line, int kind, int target, int amountCents, int link) {
    }

    @Provide
    Arbitrary<List<Op>> ops() {
        return Combinators.combine(
                        Arbitraries.integers().between(0, 9),      // 0..5 create, 6..7 remove, 8..9 replace
                        Arbitraries.integers().between(0, 2),
                        Arbitraries.integers().between(0, 2),
                        Arbitraries.integers().between(0, 1),
                        Arbitraries.oneOf(Arbitraries.just(0), Arbitraries.integers().between(1, 80_000)),
                        Arbitraries.integers().between(0, 5))
                .as(Op::new)
                .list().ofMinSize(1).ofMaxSize(25);
    }

    @Property(tries = 200)
    void allocationBoundHoldsAfterEveryOperation(@ForAll("ops") List<Op> ops) {
        LinkServiceFixture f = new LinkServiceFixture();
        List<BankTransactionEntity> lines = List.of(f.bankLine("-500.00"), f.cashLine("-320.50"),
                f.bankLine("-75.00"));
        List<WorkerPayoutEntity> payouts = List.of(
                f.payout(new BigDecimal("500.00"), PayoutStatus.PLANNED),
                f.payout(new BigDecimal("400.00"), PayoutStatus.PAID));
        List<ProcurementDocumentEntity> docs = List.of(f.document(new BigDecimal("350.00")), f.document(null));
        UnplannedExpenseCategoryEntity category = f.category();
        LinkKind[] kinds = {LinkKind.WORKER_PAYOUT, LinkKind.PROCUREMENT, LinkKind.OTHER_EXPENSE};

        for (Op op : ops) {
            BankTransactionEntity line = lines.get(op.line());
            LinkRequest req = request(kinds[op.kind()], op, payouts, docs, category);
            try {
                if (op.type() <= 5) {
                    f.service.createLink(line.getId(), req, op.type() % 2 == 0);
                } else if (!f.links.isEmpty()) {
                    BankTransactionLinkEntity link = f.links.get(op.link() % f.links.size());
                    if (op.type() <= 7) {
                        f.service.removeLink(link.getId());
                    } else {
                        f.service.replaceLink(link.getId(), req, false);
                    }
                }
            } catch (ForemenApiException rejected) {
                // A rejected operation must leave the bound intact (checked below).
            }
            assertBounds(f, lines, payouts, docs);
        }
    }

    private static LinkRequest request(LinkKind kind, Op op, List<WorkerPayoutEntity> payouts,
                                       List<ProcurementDocumentEntity> docs, UnplannedExpenseCategoryEntity category) {
        BigDecimal amount = op.amountCents() == 0 ? null : BigDecimal.valueOf(op.amountCents(), 2);
        LinkRequest req = LinkRequest.of(kind, amount);
        return switch (kind) {
            case WORKER_PAYOUT -> req.withWorkerPayout(payouts.get(op.target()).getId());
            case PROCUREMENT -> req.withProcurementDocument(docs.get(op.target()).getId());
            default -> req.withUnplannedCategory(category.getId());
        };
    }

    private static void assertBounds(LinkServiceFixture f, List<BankTransactionEntity> lines,
                                     List<WorkerPayoutEntity> payouts, List<ProcurementDocumentEntity> docs) {
        for (BankTransactionEntity line : lines) {
            assertThat(f.linkedOnLine(line)).isLessThanOrEqualTo(line.getAmount().abs());
            List<LinkStatusDeriver.LinkFact> facts = f.linksOf(line).stream()
                    .map(l -> new LinkStatusDeriver.LinkFact(l.getAmount(), l.getSource(), l.getConfirmedAt() != null))
                    .toList();
            if (!f.linksOf(line).isEmpty() || line.getLinkStatus() != null) {
                assertThat(line.getLinkStatus())
                        .isEqualTo(LinkStatusDeriver.derive(false, line.getAmount(), facts, 0));
            }
        }
        for (WorkerPayoutEntity p : payouts) {
            BigDecimal sum = f.links.stream().filter(l -> l.getWorkerPayout() == p)
                    .map(BankTransactionLinkEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isLessThanOrEqualTo(p.getAmount());
        }
        for (ProcurementDocumentEntity d : docs) {
            if (d.getDocumentTotal() != null) {
                BigDecimal sum = f.links.stream().filter(l -> l.getProcurementDocument() == d)
                        .map(BankTransactionLinkEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(sum).isLessThanOrEqualTo(d.getDocumentTotal());
            }
        }
        for (BankTransactionLinkEntity l : f.links) {
            assertThat(l.getAmount().signum()).isPositive();
        }
    }

    // --- Property 9 -----------------------------------------------------------------------------

    @Provide
    Arbitrary<List<Integer>> lockedOps() {
        return Arbitraries.integers().between(0, 5).list().ofMinSize(1).ofMaxSize(15);
    }

    @Property(tries = 150)
    void lockedLinksAreNeverChangedOrRemoved(@ForAll("lockedOps") List<Integer> ops) {
        LinkServiceFixture f = new LinkServiceFixture();
        BankTransactionEntity income = f.bankLine("1000.00");
        BankTransactionEntity cashIncome = f.cashLine("300.00");
        PaymentScheduleEntity schedule = f.schedule();
        List<Long> lockedIds = new ArrayList<>();
        lockedIds.add(f.service.createLink(income.getId(), LinkRequest.of(LinkKind.CLIENT_PAYMENT,
                new BigDecimal("600.00")).withPaymentSchedule(schedule.getId()), false).linkId());
        lockedIds.add(f.service.createLink(cashIncome.getId(), LinkRequest.of(LinkKind.CLIENT_PAYMENT, null)
                .withPaymentSchedule(schedule.getId()), false).linkId());
        Map<Long, Map<String, Object>> before = new java.util.HashMap<>();
        for (Long id : lockedIds) {
            before.put(id, BankLedgerAudit.linkSnapshot(find(f, id)));
        }

        int step = 0;
        for (int op : ops) {
            Long target = lockedIds.get(step++ % lockedIds.size());
            BankTransactionEntity line = find(f, target).getTransaction();
            try {
                switch (op) {
                    case 0 -> f.service.removeLink(target);
                    case 1 -> f.service.replaceLink(target, LinkRequest.of(LinkKind.OTHER_INCOME, null), true);
                    case 2 -> f.service.removeAllLinks(line.getId());
                    case 3 -> f.service.ignore(line.getId(), "test");
                    case 4 -> f.service.createLink(line.getId(), LinkRequest.of(LinkKind.OTHER_INCOME, null), false);
                    default -> f.service.confirmAuto(line.getId(), true);
                }
            } catch (ForemenApiException expected) {
                // locked / has links / over-allocated
            }
            for (Long id : lockedIds) {
                BankTransactionLinkEntity link = find(f, id);
                assertThat(link).as("locked link %s removed", id).isNotNull();
                assertThat(BankLedgerAudit.linkSnapshot(link)).isEqualTo(before.get(id));
            }
            assertThat(income.getIgnoredReason()).isNull();
        }
        assertThat(f.transfers).hasSize(2);
    }

    private static BankTransactionLinkEntity find(LinkServiceFixture f, Long id) {
        return f.links.stream().filter(l -> l.getId().equals(id)).findFirst().orElse(null);
    }
}
