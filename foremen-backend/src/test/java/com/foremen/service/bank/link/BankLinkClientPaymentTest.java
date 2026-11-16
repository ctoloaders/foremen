package com.foremen.service.bank.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.ClientPaymentEntity;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.clientpayment.ClientPaymentKind;
import com.foremen.service.procurement.proforma.ProformaStatus;

/**
 * FOR-11-06 task 2.6: a ledger link addressed by {@code clientPaymentId} resolves to the stored
 * {@code CLIENT_PAYMENT} / {@code PROFORMA_PAYMENT} targets; the old fields stay accepted.
 *
 * <p><b>Validates: Requirements 1.8</b>
 */
class BankLinkClientPaymentTest {

    private LinkServiceFixture f;
    private BankLinkService service;

    @BeforeEach
    void setUp() {
        f = new LinkServiceFixture();
        service = f.service;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("7", null, java.util.List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private ClientPaymentEntity worksPayment(PaymentScheduleEntity schedule) {
        ClientPaymentEntity payment = new ClientPaymentEntity(ClientPaymentKind.WORKS, "DUE");
        payment.setId(f.ids.incrementAndGet());
        PaymentTrancheEntity tranche = new PaymentTrancheEntity();
        tranche.setSchedule(schedule);
        payment.setTranche(tranche);
        when(f.entityManager.find(ClientPaymentEntity.class, payment.getId())).thenReturn(payment);
        return payment;
    }

    private ClientProformaEntity materialsPayment() {
        ClientProformaEntity proforma = new ClientProformaEntity();
        proforma.setId(f.ids.incrementAndGet());
        proforma.setStatus(ProformaStatus.ISSUED);
        proforma.setTotalGross(new BigDecimal("300.00"));
        proforma.setProject(f.project());
        ClientPaymentEntity payment = proforma.getPayment();
        payment.setId(f.ids.incrementAndGet());
        payment.setProforma(proforma);
        when(f.clientProformaDao.findById(proforma.getId())).thenReturn(Optional.of(proforma));
        when(f.entityManager.find(ClientPaymentEntity.class, payment.getId())).thenReturn(payment);
        return proforma;
    }

    @Test
    void worksPaymentResolvesToClientPaymentOnItsSchedule() {
        PaymentScheduleEntity schedule = f.schedule();
        ClientPaymentEntity payment = worksPayment(schedule);
        BankTransactionEntity line = f.bankLine("100.00");

        service.createLink(line.getId(), LinkRequest.of(null, null).withClientPayment(payment.getId()), false);

        assertThat(f.links).singleElement().satisfies(l -> {
            assertThat(l.getKind()).isEqualTo(LinkKind.CLIENT_PAYMENT);
            assertThat(l.getPaymentSchedule()).isSameAs(schedule);
        });
    }

    @Test
    void materialsPaymentResolvesToProformaPayment() {
        ClientProformaEntity proforma = materialsPayment();
        BankTransactionEntity line = f.bankLine("500.00");

        service.createLink(line.getId(),
                LinkRequest.of(LinkKind.PROFORMA_PAYMENT, null).withClientPayment(proforma.getPayment().getId()),
                false);

        assertThat(f.links).singleElement().satisfies(l -> {
            assertThat(l.getKind()).isEqualTo(LinkKind.PROFORMA_PAYMENT);
            assertThat(l.getClientProforma()).isSameAs(proforma);
            assertThat(l.getAmount()).isEqualByComparingTo("300.00");
        });
    }

    @Test
    void conflictingKindOrOldTargetFieldIsRejected() {
        PaymentScheduleEntity schedule = f.schedule();
        ClientPaymentEntity payment = worksPayment(schedule);
        BankTransactionEntity line = f.bankLine("100.00");

        assertThat(code(() -> service.createLink(line.getId(),
                LinkRequest.of(LinkKind.PROFORMA_PAYMENT, null).withClientPayment(payment.getId()), false)))
                .isEqualTo(BankLinkService.TARGET_REQUIRED);
        assertThat(code(() -> service.createLink(line.getId(),
                LinkRequest.of(LinkKind.CLIENT_PAYMENT, null).withClientPayment(payment.getId())
                        .withPaymentSchedule(schedule.getId()), false)))
                .isEqualTo(BankLinkService.TARGET_REQUIRED);
        assertThat(f.links).isEmpty();
    }

    @Test
    void unknownPaymentIsNotFound() {
        BankTransactionEntity line = f.bankLine("100.00");
        assertThat(code(() -> service.createLink(line.getId(),
                LinkRequest.of(null, null).withClientPayment(999_999L), false)))
                .isEqualTo(BankLinkService.notFound().getMessage());
    }

    @Test
    void oldScheduleFieldStillWorks() {
        PaymentScheduleEntity schedule = f.schedule();
        BankTransactionEntity line = f.bankLine("100.00");

        service.createLink(line.getId(),
                LinkRequest.of(LinkKind.CLIENT_PAYMENT, null).withPaymentSchedule(schedule.getId()), false);

        assertThat(f.links).singleElement().satisfies(l -> assertThat(l.getPaymentSchedule()).isSameAs(schedule));
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (ForemenApiException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected ForemenApiException");
    }
}
