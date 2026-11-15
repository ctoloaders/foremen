package com.foremen.service.bank.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.externalorder.ExternalOrderStatus;
import com.foremen.service.procurement.externalorder.PaidSource;
import com.foremen.service.procurement.proforma.ProformaStatus;

/**
 * FOR-11-03 task 5.1: ledger extension for {@code PROFORMA_PAYMENT} / {@code EXTERNAL_ORDER_PAYMENT}
 * (direction, currency, linkable status, caps, effects ports, removal, lock) and the
 * {@code PROCUREMENT} status-changed event.
 *
 * <p><b>Validates: Requirements 7.1, 7.3, 7.4, 7.5, 7.8</b>
 */
class BankLinkProformaOrderTest {

    private LinkServiceFixture f;
    private BankLinkService service;

    @BeforeEach
    void setUp() {
        f = new LinkServiceFixture();
        service = f.service;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("7", null, List.of()));
        when(f.linkDao.sumAmountByClientProformaId(anyLong())).thenAnswer(inv -> f.links.stream()
                .filter(l -> l.getClientProforma() != null && l.getClientProforma().getId().equals(inv.getArgument(0)))
                .map(BankTransactionLinkEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        when(f.linkDao.sumAmountByExternalOrderId(anyLong())).thenAnswer(inv -> f.links.stream()
                .filter(l -> l.getExternalOrder() != null && l.getExternalOrder().getId().equals(inv.getArgument(0)))
                .map(BankTransactionLinkEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static BigDecimal pln(String v) {
        return new BigDecimal(v);
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (ForemenApiException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected ForemenApiException");
    }

    private ClientProformaEntity proforma(ProformaStatus status, String totalGross) {
        ClientProformaEntity p = new ClientProformaEntity();
        p.setId(f.ids.incrementAndGet());
        p.setStatus(status);
        p.setTotalGross(pln(totalGross));
        p.setProject(f.project());
        when(f.clientProformaDao.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    /** One line 2 x 100.00 net at 23 % = 246.00 gross, no delivery. */
    private ExternalOrderEntity order(ExternalOrderStatus status, PaidSource paidSource) {
        ExternalOrderEntity o = new ExternalOrderEntity();
        o.setId(f.ids.incrementAndGet());
        o.setStatus(status);
        o.setPaidSource(paidSource);
        o.setProject(f.project());
        o.setDeliveryCostNet(BigDecimal.ZERO);
        VatRateEntity vat = new VatRateEntity();
        vat.setRate(pln("23"));
        ExternalOrderLineEntity line = new ExternalOrderLineEntity();
        line.setQuantity(pln("2"));
        line.setUnitPurchaseNet(pln("100.00"));
        line.setPurchaseVatRate(vat);
        when(f.externalOrderDao.findById(o.getId())).thenReturn(Optional.of(o));
        when(f.externalOrderLineDao.findByExternalOrderId(o.getId())).thenReturn(List.of(line));
        return o;
    }

    // --- PROFORMA_PAYMENT -------------------------------------------------------------------------

    @Test
    void proformaLinkDefaultsToRemainderAndCallsPort() {
        ClientProformaEntity p = proforma(ProformaStatus.ISSUED, "300.00");
        BankTransactionEntity line = f.bankLine("500.00");

        service.createLink(line.getId(),
                LinkRequest.of(LinkKind.PROFORMA_PAYMENT, null).withClientProforma(p.getId()), false);

        assertThat(f.links).singleElement().satisfies(l -> {
            assertThat(l.getAmount()).isEqualByComparingTo("300.00");
            assertThat(l.getClientProforma()).isSameAs(p);
            assertThat(l.getProject()).isSameAs(p.getProject());
        });
        verify(f.proformaEffects).onLinkChanged(p.getId());
    }

    @Test
    void proformaLinkRejectsDebitLine() {
        ClientProformaEntity p = proforma(ProformaStatus.ISSUED, "300.00");
        BankTransactionEntity debit = f.bankLine("-300.00");
        assertThat(code(() -> service.createLink(debit.getId(),
                LinkRequest.of(LinkKind.PROFORMA_PAYMENT, null).withClientProforma(p.getId()), false)))
                .isEqualTo(BankLinkService.KIND_DIRECTION);
    }

    @Test
    void proformaLinkRejectsNonLinkableStatusAndForeignCurrency() {
        ClientProformaEntity draft = proforma(ProformaStatus.DRAFT, "300.00");
        ClientProformaEntity issued = proforma(ProformaStatus.ISSUED, "300.00");
        BankTransactionEntity line = f.bankLine("300.00");
        assertThat(code(() -> service.createLink(line.getId(),
                LinkRequest.of(LinkKind.PROFORMA_PAYMENT, null).withClientProforma(draft.getId()), false)))
                .isEqualTo(BankLinkService.PROFORMA_NOT_LINKABLE);

        BankTransactionEntity eur = f.bankLine("300.00");
        eur.setCurrency("EUR");
        assertThat(code(() -> service.createLink(eur.getId(),
                LinkRequest.of(LinkKind.PROFORMA_PAYMENT, null).withClientProforma(issued.getId()), false)))
                .isEqualTo(BankLinkService.CURRENCY_MISMATCH);
    }

    @Test
    void proformaLinkIsCappedByTargetRemainder() {
        ClientProformaEntity p = proforma(ProformaStatus.ISSUED, "300.00");
        BankTransactionEntity line = f.bankLine("1000.00");
        service.createLink(line.getId(),
                LinkRequest.of(LinkKind.PROFORMA_PAYMENT, pln("200.00")).withClientProforma(p.getId()), false);

        assertThat(code(() -> service.createLink(line.getId(),
                LinkRequest.of(LinkKind.PROFORMA_PAYMENT, pln("100.01")).withClientProforma(p.getId()), false)))
                .isEqualTo(BankLinkService.TARGET_OVER_LINKED);
    }

    @Test
    void removingProformaLinkRunsReverseEffectAndReleasedLinkIsLocked() {
        ClientProformaEntity p = proforma(ProformaStatus.ISSUED, "300.00");
        BankTransactionEntity line = f.bankLine("300.00");
        Long linkId = service.createLink(line.getId(),
                LinkRequest.of(LinkKind.PROFORMA_PAYMENT, null).withClientProforma(p.getId()), false).linkId();

        service.removeLink(linkId);
        verify(f.proformaEffects, org.mockito.Mockito.times(2)).onLinkChanged(p.getId());
        assertThat(f.links).isEmpty();

        Long second = service.createLink(line.getId(),
                LinkRequest.of(LinkKind.PROFORMA_PAYMENT, null).withClientProforma(p.getId()), false).linkId();
        p.setReleasedAt(OffsetDateTime.now());
        assertThat(code(() -> service.removeLink(second))).isEqualTo(BankLinkService.LOCKED);
        assertThat(LinkEffects.isLocked(f.links.get(0))).isTrue();
    }

    // --- EXTERNAL_ORDER_PAYMENT -------------------------------------------------------------------

    @Test
    void externalOrderLinkUsesPayableGrossAsCap() {
        ExternalOrderEntity o = order(ExternalOrderStatus.SENT_FOR_PAYMENT, null);
        BankTransactionEntity line = f.bankLine("-1000.00");

        service.createLink(line.getId(),
                LinkRequest.of(LinkKind.EXTERNAL_ORDER_PAYMENT, null).withExternalOrder(o.getId()), false);

        assertThat(f.links).singleElement().satisfies(l -> {
            assertThat(l.getAmount()).isEqualByComparingTo("246.00");
            assertThat(l.getExternalOrder()).isSameAs(o);
        });
        verify(f.externalOrderEffects).onLinkChanged(o.getId());
        assertThat(code(() -> service.createLink(line.getId(),
                LinkRequest.of(LinkKind.EXTERNAL_ORDER_PAYMENT, pln("0.01")).withExternalOrder(o.getId()), false)))
                .isEqualTo(BankLinkService.TARGET_OVER_LINKED);
    }

    @Test
    void externalOrderLinkRejectsCreditLineAndNonLinkableOrders() {
        ExternalOrderEntity sent = order(ExternalOrderStatus.SENT_FOR_PAYMENT, null);
        ExternalOrderEntity draft = order(ExternalOrderStatus.DRAFT, null);
        ExternalOrderEntity ledgerPaid = order(ExternalOrderStatus.PAID, PaidSource.LEDGER);
        ExternalOrderEntity financierPaid = order(ExternalOrderStatus.PAID, PaidSource.FINANCIER);
        BankTransactionEntity credit = f.bankLine("100.00");
        BankTransactionEntity debit = f.bankLine("-1000.00");

        assertThat(code(() -> service.createLink(credit.getId(),
                LinkRequest.of(LinkKind.EXTERNAL_ORDER_PAYMENT, null).withExternalOrder(sent.getId()), false)))
                .isEqualTo(BankLinkService.KIND_DIRECTION);
        for (ExternalOrderEntity bad : List.of(draft, ledgerPaid)) {
            assertThat(code(() -> service.createLink(debit.getId(),
                    LinkRequest.of(LinkKind.EXTERNAL_ORDER_PAYMENT, null).withExternalOrder(bad.getId()), false)))
                    .isEqualTo(BankLinkService.EXTERNAL_ORDER_NOT_LINKABLE);
        }
        service.createLink(debit.getId(),
                LinkRequest.of(LinkKind.EXTERNAL_ORDER_PAYMENT, null).withExternalOrder(financierPaid.getId()), false);
        assertThat(f.links).hasSize(1);
    }

    // --- PROCUREMENT status event -----------------------------------------------------------------

    @Test
    void procurementLinkChangesPublishDocumentStatusEvent() {
        ProcurementDocumentEntity doc = f.document(pln("100.00"));
        BankTransactionEntity line = f.bankLine("-100.00");
        Long linkId = service.createLink(line.getId(),
                LinkRequest.of(LinkKind.PROCUREMENT, null).withProcurementDocument(doc.getId()), false).linkId();
        service.removeLink(linkId);

        assertThat(f.publishedEvents).containsExactly(
                new ProcurementDocumentPaymentStatusChangedEvent(doc.getId()),
                new ProcurementDocumentPaymentStatusChangedEvent(doc.getId()));
        verify(f.proformaEffects, org.mockito.Mockito.never()).onLinkChanged(any());
    }
}
