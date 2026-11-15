package com.foremen.service.model.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.LinkKind;
import com.foremen.service.model.ClientProformaPaymentServiceModel;
import com.foremen.service.model.ClientProformaServiceExtendedModel;

import jakarta.persistence.EntityManager;

/**
 * FOR-11-03 task 15.1 (R23.3, R24.5): staff detail carries the proforma's ledger payments, without any
 * counterparty data.
 *
 * <p>Validates: Requirements 23.3, 6.3
 */
class ClientProformaServiceMapperPaymentsTest {

    private final ClientProformaLineDao lineDao = mock(ClientProformaLineDao.class);
    private final BankTransactionLinkDao linkDao = mock(BankTransactionLinkDao.class);
    private final ClientProformaServiceMapper mapper =
            new ClientProformaServiceMapper(mock(EntityManager.class), lineDao, linkDao);

    @Test
    void extendedModelCarriesPaymentsFromProformaPaymentLinks() {
        ClientProformaEntity proforma = new ClientProformaEntity();
        proforma.setId(7L);

        BankTransactionEntity tx = new BankTransactionEntity();
        tx.setBookingDate(LocalDate.of(2026, 2, 3));
        tx.setTitle("Proforma PF/2026/000007");
        tx.setCounterpartyName("SECRET CLIENT");
        tx.setCounterpartyIban("PL61109010140000071219812874");
        BankTransactionLinkEntity link = new BankTransactionLinkEntity();
        link.setId(42L);
        link.setKind(LinkKind.PROFORMA_PAYMENT);
        link.setAmount(new BigDecimal("40.00"));
        link.setTransaction(tx);

        when(lineDao.findByProformaIdOrderByLineNo(7L)).thenReturn(List.of());
        when(linkDao.findProformaPaymentsByClientProformaId(7L)).thenReturn(List.of(link));

        ClientProformaServiceExtendedModel model = mapper.toServiceExtendedModel(proforma);

        assertThat(model.getPayments()).containsExactly(new ClientProformaPaymentServiceModel(
                42L, LocalDate.of(2026, 2, 3), new BigDecimal("40.00"), "Proforma PF/2026/000007"));
        assertThat(ClientProformaPaymentServiceModel.class.getRecordComponents())
                .extracting(c -> c.getName().toLowerCase())
                .noneMatch(n -> n.contains("iban") || n.contains("account") || n.contains("counterparty"));
    }

    @Test
    void unsavedProformaHasNoPaymentsAndNoQuery() {
        ClientProformaServiceExtendedModel model = mapper.toServiceExtendedModel(new ClientProformaEntity());

        assertThat(model.getPayments()).isEmpty();
        org.mockito.Mockito.verify(linkDao, org.mockito.Mockito.never())
                .findProformaPaymentsByClientProformaId(any());
    }

    @Test
    void listModelDoesNotQueryPayments() {
        ClientProformaEntity proforma = new ClientProformaEntity();
        proforma.setId(7L);

        mapper.toServiceModel(proforma);

        org.mockito.Mockito.verifyNoInteractions(linkDao);
    }
}
